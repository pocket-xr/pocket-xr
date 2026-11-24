package com.hayeco.flipspaces.core.spatial

/**
 * 1.6.16 (Crr66, Netflix): apps that protect their video. Android never lets another app capture protected video, so
 * in a Pocket XR window they play black, and the only place their picture shows is the glasses' own display. These
 * apps open there full screen at once, instead of in a window that would turn black and offer a DeX window later
 * (which started the app again and lost the user's place).
 *
 * The streaming apps known to protect their video ([KNOWN]) open that way from the start; any other app joins once
 * Pocket XR has seen it protect its screen in a window ([remember]). Stored as text, one package a line.
 */
class ProtectedApps(text: String? = null) {
    private val seen = LinkedHashSet<String>()

    init {
        text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { seen += it }
    }

    fun isProtected(packageName: String): Boolean = packageName in KNOWN || packageName in seen

    /** Remembers [packageName] as protecting its screen; true when it was new. */
    fun remember(packageName: String): Boolean = packageName.isNotEmpty() && packageName !in KNOWN && seen.add(packageName)

    fun encode(): String = seen.joinToString("\n")

    companion object {
        /** Streaming apps that protect their video (and show their phone layout in a narrow window too). */
        val KNOWN: Set<String> = WideViewStore.STARTS_ON
    }
}
