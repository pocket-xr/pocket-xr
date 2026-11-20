package com.hayeco.flipspaces.pcscreen

/**
 * PC screens: the setup page lists PCs, not Apollo instances. Every instance on a PC answers mDNS with the PC's own name
 * ("Mike", then "Mike(2)", "Mike(3)"), so the instances are grouped by address and each PC screen is given the right
 * one behind its PC's single button.
 */
object PcScreenPick {
    /** The PC's name from an instance's mDNS name: "Mike(2)" and "MIKE screen 2" are both "Mike"'s. */
    fun pcName(serviceName: String): String =
        serviceName.trim().replace(Regex("""\(\d+\)$"""), "").replace(Regex("""\s+screen\s+\d+$""", RegexOption.IGNORE_CASE), "").trim()
            // A host name with the network's suffix ("Halcyons-Air.mynetworksettings.com", a Mac's Sunshine): the name only.
            .let { if (it.contains('.') && it.first().isLetter()) it.substringBefore('.') else it }
            .ifEmpty { serviceName }

    /**
     * The found instances by PC (address and name), each PC named by its main instance, in the order found. One PC's
     * Apollo instances share a name ("Mike", "Mike(2)", "MIKE screen 3"); another host on the same PC with its own name
     * ("MIKE Pocket XR", Pocket XR for Windows as a host) is a row of its own (Mike, 20 Nov 2026: it was hidden in "Mike").
     */
    fun byPc(hosts: List<PcScreenFinder.Host>): List<Pair<String, List<PcScreenFinder.Host>>> =
        hosts.groupBy { it.address + "|" + pcName(it.service).lowercase() }.values.map { list ->
            val main = list.firstOrNull { it.port == PcScreenAddress.DEFAULT_PORT } ?: list.minBy { it.port }
            pcName(main.service) to list.sortedBy { it.port }
        }

    /**
     * The instance PC screen [slot] uses: PC screen 1 the main Apollo (the usual port) first; PC screens 2 and 3 the
     * extra ones first, so the main stays PC screen 1's. Only instances [free] says no other PC screen uses; null if none.
     */
    fun instanceFor(slot: Int, instances: List<PcScreenFinder.Host>, free: (PcScreenFinder.Host) -> Boolean): PcScreenFinder.Host? {
        val main = instances.filter { it.port == PcScreenAddress.DEFAULT_PORT }
        val extra = instances.filter { it.port != PcScreenAddress.DEFAULT_PORT }.sortedBy { it.port }
        val order = if (slot == 0) main + extra else extra + main
        return order.firstOrNull(free)
    }
}
