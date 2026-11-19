package com.hayeco.flipspaces.pcscreen

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * PC screens: Wake-on-LAN for a PC that is asleep or off. The host gives its network card's MAC address in serverinfo
 * (`mac`), saved after pairing; a magic packet to it wakes a PC whose card and BIOS have Wake-on-LAN on.
 */
object PcScreenWake {
    /** The six bytes of a MAC address written `aa:bb:cc:dd:ee:ff` or `aa-bb-...`, or null for none or a bad one. */
    fun macBytes(mac: String?): ByteArray? {
        val parts = mac?.trim()?.split(':', '-') ?: return null
        if (parts.size != 6) return null
        val bytes = parts.map { it.toIntOrNull(16)?.takeIf { v -> v in 0..255 } ?: return null }
        if (bytes.all { it == 0 }) return null
        return ByteArray(6) { bytes[it].toByte() }
    }

    /** The magic packet: six 0xFF bytes, then the MAC sixteen times. */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        val out = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
        for (i in 0 until 16) mac.copyInto(out, 6 + i * 6)
        return out
    }

    /** Sends the packet to the broadcast address and to the PC's own address, on ports 9 and 7. Blocking: any thread. */
    fun send(mac: String?, pcAddress: String): Boolean {
        val bytes = macBytes(mac) ?: return false
        val packet = magicPacket(bytes)
        val targets = listOfNotNull(
            runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull(),
            runCatching { InetAddress.getByName(pcAddress) }.getOrNull(),
        )
        var sent = false
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (t in targets) for (port in intArrayOf(9, 7)) {
                    runCatching { socket.send(DatagramPacket(packet, packet.size, t, port)); sent = true }
                }
            }
        }
        return sent
    }
}
