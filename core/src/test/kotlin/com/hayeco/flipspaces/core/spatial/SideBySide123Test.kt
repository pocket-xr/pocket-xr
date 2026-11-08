package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.3 (issue 58): a side by side picture split between the eyes, and the per app store. */
class SideBySide123Test {
    private val whole = floatArrayOf(0f, 0f, 1f, 1f)

    @Test
    fun eachEyeSamplesItsOwnHalf() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0.5f, 1f), SideBySide.eyeUv(whole, eyePass = 0, eyePasses = 2), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 1f, 1f), SideBySide.eyeUv(whole, eyePass = 1, eyePasses = 2), 1e-6f)
    }

    @Test
    fun withOneEyePassTheLeftHalfShowsAloneAsA2dPicture() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0.5f, 1f), SideBySide.eyeUv(whole, eyePass = 0, eyePasses = 1), 1e-6f)
    }

    @Test
    fun aCroppedPictureIsSplitWithinItsCrop() {
        // A picture from before a resize, cropped about its centre ([PanelUv.cover]): the halves are of the crop.
        val crop = floatArrayOf(0.1f, 0.05f, 0.9f, 0.95f)
        assertArrayEquals(floatArrayOf(0.1f, 0.05f, 0.5f, 0.95f), SideBySide.eyeUv(crop, 0, 2), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, 0.05f, 0.9f, 0.95f), SideBySide.eyeUv(crop, 1, 2), 1e-6f)
    }

    @Test
    fun theStoreRemembersEachApp() {
        val s = SideBySideStore()
        s.set("org.videolan.vlc", true)
        s.set("com.google.android.youtube", true)
        s.set("com.google.android.youtube", false)
        val back = SideBySideStore(s.encode())
        assertTrue(back.isOn("org.videolan.vlc"))
        assertFalse(back.isOn("com.google.android.youtube"))
    }
}
