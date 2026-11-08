package com.hayeco.flipspaces.core.spatial

/**
 * 1.2.3 (issue 58: "A 3D video that is already split into two halves plays in Pocket XR as two flat pictures next to each
 * other"): Side by side 3D for a window. Pocket XR already draws every window once for each eye, so with it on the left
 * eye's pass samples the left half of the window's picture and the right eye's the right half, each across the whole
 * window. The window keeps its own place and depth in the room, and the cursor and menus stay as they are.
 *
 * Each half is drawn across the whole window, which is right for the common half width side by side video (two squeezed
 * halves in a 16:9 frame). With one eye pass (the glasses in 2D) the left half shows alone, a plain 2D picture.
 * Remembered per app ([SideBySideStore]), from the trackpad's Menu for the focused window.
 */
object SideBySide {
    /**
     * The part of [uv] (u0, v0, u1, v1, the picture's crop) the eye of pass [eyePass] samples, of [eyePasses] passes: its
     * own half across, the whole crop down.
     */
    fun eyeUv(uv: FloatArray, eyePass: Int, eyePasses: Int): FloatArray {
        val mid = (uv[0] + uv[2]) / 2f
        val right = eyePasses > 1 && eyePass == 1
        return if (right) floatArrayOf(mid, uv[1], uv[2], uv[3]) else floatArrayOf(uv[0], uv[1], mid, uv[3])
    }

    const val ON_NOTICE = "Side by side 3D: each eye sees its half"
    const val MONO_NOTICE = "Side by side 3D: switch the glasses to 3D to see depth"
    const val SWITCHING_NOTICE = "Side by side 3D: switching the glasses to 3D"
    const val OFF_NOTICE = "Side by side 3D off"
}

/** 1.2.3 (issue 58): the apps whose windows show [SideBySide], one package a line. */
class SideBySideStore(text: String? = null) {
    private val apps = LinkedHashSet<String>()

    init {
        text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { apps += it }
    }

    fun isOn(packageName: String): Boolean = packageName in apps

    fun set(packageName: String, on: Boolean) {
        if (on) apps += packageName else apps -= packageName
    }

    fun encode(): String = apps.joinToString("") { "$it\n" }
}
