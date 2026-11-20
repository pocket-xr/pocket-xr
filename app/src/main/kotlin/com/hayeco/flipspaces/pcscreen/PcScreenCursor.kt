package com.hayeco.flipspaces.pcscreen

import com.hayeco.flipspaces.core.spatial.PanelHit
import com.hayeco.flipspaces.core.spatial.PanelZone

/**
 * PC screens: whether the Space's own pointer dot hides, so that only the PC's mouse pointer shows.
 *
 * The PC's pointer comes in the stream, drawn by the host where the mouse is. Over the picture of a PC screen, Pocket
 * XR's dot would be a second pointer a little ahead of it, so it hides there. It still shows over the window's title
 * bar, frame and buttons, where the clicks are Pocket XR's own. The Space hides it only while the panel shows a live
 * stream (PanelGl.streamLive), so the PC screen's own Pair and Connect page keeps the dot.
 */
object PcScreenCursor {
    private val ACTIVITY = PcScreenActivity::class.java.name

    fun hidesDot(hover: PanelHit?): Boolean =
        hover != null && !hover.frame && hover.zone == PanelZone.BODY && hover.panel.activityName == ACTIVITY
}
