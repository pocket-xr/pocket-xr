package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.4: turning the external display settings on through Shizuku (a vivo hides their switches). */
class FreeformShizuku154Test {
    @Test
    fun allOffNeedsAllThree() {
        val current = FreeformSetup.all.associate { it.key to "0" }
        assertEquals(FreeformSetup.all, FreeformSetup.toTurnOn(current))
    }

    @Test
    fun unreadAndNullCountAsOff() {
        // `settings get` prints "null" for a key that was never set; a failed read is null here.
        val current = mapOf(FreeformSetup.enableFreeform.key to "null", FreeformSetup.forceResizable.key to null)
        assertEquals(FreeformSetup.all, FreeformSetup.toTurnOn(current))
    }

    @Test
    fun onesAlreadyOnAreLeftAlone() {
        val current = mapOf(
            FreeformSetup.enableFreeform.key to "1\n",
            FreeformSetup.forceResizable.key to " 1 ",
            FreeformSetup.desktopOnExternal.key to "0",
        )
        assertEquals(listOf(FreeformSetup.desktopOnExternal), FreeformSetup.toTurnOn(current))
        assertTrue(FreeformSetup.toTurnOn(FreeformSetup.all.associate { it.key to "1" }).isEmpty())
    }

    @Test
    fun theShellCommandsAreAdbsWithoutAdb() {
        assertEquals(listOf("settings", "get", "global", "force_desktop_mode_on_external_displays"),
            FreeformSetup.getCommand(FreeformSetup.desktopOnExternal))
        assertEquals(listOf("settings", "put", "global", "force_desktop_mode_on_external_displays", "1"),
            FreeformSetup.putCommand(FreeformSetup.desktopOnExternal))
        FreeformSetup.all.forEach {
            assertEquals("adb shell " + FreeformSetup.putCommand(it).joinToString(" "), it.adbCommand)
        }
    }
}
