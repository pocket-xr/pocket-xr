package com.hayeco.flipspaces.core.spatial

/**
 * 1.5.6 (issue 119: "Youtube can be scrolled down to shrink the current video ... Solid explorer can scroll sideways to
 * switch tabs"): the apps with Scroll by swiping on, one package a line. Their windows take the trackpad's scrolls (two
 * fingers, the scroll strip and Spatial Mouse's one finger scroll) as a finger dragged across the app
 * ([SpatialPointer.swipePanels]) rather than the mouse wheel, so swipe gestures that a wheel never starts work. Off by
 * default: the wheel scrolls long pages further and smoother.
 */
class SwipeScrollStore(text: String? = null) {
    private val apps = LinkedHashSet<String>()

    init {
        text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { apps += it }
    }

    fun isOn(packageName: String): Boolean = packageName.isNotEmpty() && packageName in apps

    fun set(packageName: String, on: Boolean) {
        if (packageName.isEmpty()) return
        if (on) apps += packageName else apps -= packageName
    }

    fun encode(): String = apps.joinToString("") { "$it\n" }
}
