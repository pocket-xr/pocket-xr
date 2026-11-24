package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedAppsTest {
    @Test fun knownStreamingAppsAreProtected() {
        val p = ProtectedApps()
        assertTrue(p.isProtected("com.netflix.mediaclient"))
        assertTrue(p.isProtected("com.disney.disneyplus"))
        assertFalse(p.isProtected("com.example.notes"))
    }

    @Test fun anAppSeenProtectingIsRemembered() {
        val p = ProtectedApps()
        assertTrue(p.remember("com.example.bank"))
        assertFalse(p.remember("com.example.bank"))
        assertFalse(p.remember("com.netflix.mediaclient"))
        val saved = ProtectedApps(p.encode())
        assertTrue(saved.isProtected("com.example.bank"))
        assertFalse(saved.isProtected("com.example.notes"))
    }
}
