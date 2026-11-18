package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayHostingTest {
    /** Shaped like Mike's Flip 7 report: display 6, XREAL 1 Ultra, mirror content mode. */
    private val flip7Text = "Display id 6: DisplayInfo{\"XREAL 1 Ultra\", displayId 6, displayGroupId 1, " +
        "FLAG_SECURE, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_ALLOWS_CONTENT_MODE_SWITCH, real 1920 x 1080, " +
        "type EXTERNAL, state ON, canHostTasks false}"

    private val glasses = DisplayCandidate(
        6, "XREAL 1 Ultra", 1920, 1080, refreshRateHz = 120f, isPresentation = true, type = DisplayType.EXTERNAL,
    )

    @Test
    fun parsesFlip7DisplayInfo() {
        val info = DisplayInfoText.parse(flip7Text)
        assertEquals(false, info.canHostTasks)
        assertEquals(true, info.isTrusted)
        assertEquals(true, info.allowsContentModeSwitch)
        assertTrue("FLAG_SECURE" in info.flags)
    }

    @Test
    fun parsesEqualsFormAndTrue() {
        assertEquals(true, DisplayInfoText.parse("mDisplayInfo canHostTasks=true FLAG_PRESENTATION").canHostTasks)
    }

    @Test
    fun missingCanHostTasksIsUnknown() {
        val info = DisplayInfoText.parse("DisplayInfo{\"HDMI\", FLAG_PRESENTATION, type EXTERNAL}")
        assertNull(info.canHostTasks)
        assertEquals(false, info.isTrusted)
        assertNull(DisplayInfoText.parse(null).isTrusted)
    }

    @Test
    fun canHostTasksFalseIsMirror() {
        val a = HostingAssessor.assess(glasses.copy(canHostTasks = false), homeRunning = false, denial = null)
        assertEquals(ContentMode.MIRROR, a.mode)
        assertFalse(a.mode.canHostApps)
    }

    @Test
    fun canHostTasksTrueIsExtended() {
        val a = HostingAssessor.assess(glasses.copy(canHostTasks = true), homeRunning = false, denial = null)
        assertEquals(ContentMode.EXTENDED, a.mode)
    }

    @Test
    fun dexOnDisplayIsDesktop() {
        val dex = glasses.copy(canHostTasks = true, desktopModeActive = true)
        assertEquals(ContentMode.DESKTOP, HostingAssessor.assess(dex, false, null).mode)
        val dexOnly = glasses.copy(desktopModeActive = true)
        assertEquals(ContentMode.DESKTOP, HostingAssessor.assess(dexOnly, false, null).mode)
        assertTrue(HostingAssessor.assess(dexOnly, false, null).dexOwnsDisplay)
    }

    @Test
    fun runningHomeProvesHosting() {
        val a = HostingAssessor.assess(glasses.copy(canHostTasks = false), homeRunning = true, denial = null)
        assertEquals(ContentMode.EXTENDED, a.mode)
    }

    @Test
    fun denialOfOwnHomeMeansMirrorAndKeepsRealError() {
        val error = "Permission Denial: starting Intent { cmp=com.hayeco.flipspaces/.GlassesHomeActivity } " +
            "from null (pid=1, uid=10055) with launchDisplayId=6"
        val denial = LaunchDenial(6, error, glasses.hostingSignature)
        val a = HostingAssessor.assess(glasses, homeRunning = false, denial = denial)
        assertEquals(ContentMode.MIRROR, a.mode)
        assertEquals(error, a.lastError)
    }

    @Test
    fun staleDenialIsIgnored() {
        val denial = LaunchDenial(6, "denied", HostingSignature(null, null))
        val nowDex = glasses.copy(desktopModeActive = false, canHostTasks = null)
        assertEquals(ContentMode.UNKNOWN, HostingAssessor.assess(nowDex, false, denial).mode)
        val otherDisplay = LaunchDenial(7, "denied", glasses.hostingSignature)
        assertEquals(ContentMode.UNKNOWN, HostingAssessor.assess(glasses, false, otherDisplay).mode)
    }

    @Test
    fun mirrorSettingAndUnknown() {
        assertEquals(ContentMode.MIRROR, HostingAssessor.assess(glasses, false, null, mirrorSettingOn = true).mode)
        assertEquals(ContentMode.UNKNOWN, HostingAssessor.assess(glasses, false, null, mirrorSettingOn = false).mode)
    }

    @Test
    fun settingsTargetsPreferDexWhenInstalled() {
        assertEquals(SettingsTarget.DEX, SettingsTarget.forMirror(dexInstalled = true).first())
        assertFalse(SettingsTarget.DEX in SettingsTarget.forMirror(dexInstalled = false))
        assertEquals(SettingsTarget.SETTINGS_HOME_OTHER, SettingsTarget.forMirror(false).last())
    }

    /** 1.5.3: a phone without DeX (a vivo on Android 17) was told to turn on Samsung DeX. */
    @Test
    fun phonesWithoutDexAreNeverToldAboutDex() {
        val other = SettingsTarget.forMirror(dexInstalled = false)
        assertEquals(listOf(SettingsTarget.EXTERNAL_DISPLAY, SettingsTarget.DEVELOPER_OPTIONS, SettingsTarget.SETTINGS_HOME_OTHER), other)
        other.forEach { assertFalse(it.hint, it.hint.contains("DeX")) }
        assertFalse(HostingAssessor.mirrorMessage(dexInstalled = false).contains("DeX"))
        assertTrue(HostingAssessor.mirrorMessage(dexInstalled = true).contains("DeX"))
        assertTrue(SettingsTarget.DEVELOPER_OPTIONS.hint.contains("Force desktop mode"))
    }

    @Test
    fun mirrorMessageHasNoDashes() {
        for (m in listOf(HostingAssessor.MIRROR_MESSAGE, HostingAssessor.MIRROR_MESSAGE_OTHER) + SettingsTarget.entries.map { it.hint }) {
            assertFalse(m, m.contains('\u2014') || m.contains('\u2013'))
        }
    }
}
