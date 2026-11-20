package com.hayeco.flipspaces.pcscreen

/**
 * PC screens: several at once. Moonlight's core keeps one stream per process (its globals), so each PC screen is its
 * own activity in its own process (`:pcscreen`, `:pcscreen2`, `:pcscreen3`), with its own surface service and its own
 * saved PC. A host streams one session at a time, so each screen needs a host of its own: a second Apollo instance on
 * another port, or another PC.
 */
object PcScreenSlots {
    const val COUNT = 3

    private val activities = listOf(
        PcScreenActivity::class.java.name,
        PcScreen2Activity::class.java.name,
        PcScreen3Activity::class.java.name,
    )

    private val services = listOf(
        PcScreenSurfaceService::class.java,
        PcScreenSurfaceService2::class.java,
        PcScreenSurfaceService3::class.java,
    )

    /** The slot (0, 1 or 2) of a PC screen activity's class name, or -1 for any other. */
    fun slotOf(activityName: String): Int = activities.indexOf(activityName)

    fun activity(slot: Int): String = activities[slot]

    fun service(slot: Int): Class<*> = services[slot]
}

/**
 * A host address as typed: `192.168.1.20`, `192.168.1.20:48989`, `pc.local`, `[fe80::1]:48989` or a bare IPv6 address.
 * The port is the host's HTTP port (Sunshine's and Apollo's "port" setting, 47989 by default); a second Apollo
 * instance has its own.
 */
object PcScreenAddress {
    const val DEFAULT_PORT = 47989

    /** Host and port, or null for an empty or malformed address. */
    fun parse(text: String): Pair<String, Int>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end < 2) return null
            val host = t.substring(1, end)
            val rest = t.substring(end + 1)
            if (rest.isEmpty()) return host to DEFAULT_PORT
            if (!rest.startsWith(":")) return null
            return port(rest.substring(1))?.let { host to it }
        }
        // More than one colon and no brackets: a bare IPv6 address, no port.
        if (t.count { it == ':' } > 1) return t to DEFAULT_PORT
        val colon = t.indexOf(':')
        if (colon < 0) return t to DEFAULT_PORT
        val host = t.substring(0, colon)
        if (host.isEmpty()) return null
        return port(t.substring(colon + 1))?.let { host to it }
    }

    private fun port(s: String): Int? = s.toIntOrNull()?.takeIf { it in 1..65535 }
}
