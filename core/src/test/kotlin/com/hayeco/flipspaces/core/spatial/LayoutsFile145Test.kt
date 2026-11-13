package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.5 (a Reddit user: do saved layouts carry over to the Steam Deck?): Share layouts and Import layouts. */
class LayoutsFile145Test {
    private val phone = listOf(
        AppEntry("Firefox", "org.mozilla.firefox", "org.mozilla.fenix.HomeActivity"),
        AppEntry("Chrome", "com.android.chrome", "com.google.android.apps.chrome.Main"),
        AppEntry("YouTube", "com.google.android.youtube", "com.google.android.youtube.app.honeycomb.Shell\$HomeActivity"),
        AppEntry("Messages", "com.google.android.apps.messaging", "com.google.android.apps.messaging.ui.ConversationListActivity"),
        AppEntry("VLC", "org.videolan.vlc", "org.videolan.vlc.StartActivity"),
    )

    private fun panel(id: Int, app: AppEntry, yaw: Float, pitch: Float = 0f, distance: Float = 1.4f, w: Float = 40f, h: Float = 22.5f, curve: Float = 0f) =
        SpacePanel(id, app.label, app.packageName, app.activityName, yaw, pitch, distance, w, h, curve).withAppSize()

    private val presets = listOf(
        WindowPreset(2, "Browsing", SpaceLayout(listOf(panel(1, phone[0], 30f, -5f, 1.5f), panel(2, phone[1], -30f, 0f, 1.5f, 50f, 28f, 0.6f)))),
        WindowPreset(1, "Movie \"night\"", SpaceLayout(listOf(panel(1, phone[2], 0f, 5f, 3f, 80f, 45f, 1.2f), panel(2, phone[3], -65f, -20f, 1f, 24f, 30f)))),
    )

    private fun ok(text: String): LayoutsFile.Contents = (LayoutsFile.read(text) as LayoutsFile.Read.Ok).contents

    @Test
    fun aRoundTripKeepsEveryWindowWhereItWas() {
        val text = LayoutsFile.encode(presets, "1.4.5")
        assertTrue(text.startsWith("{\n  \"format\": \"pocket-xr-layouts\",\n  \"version\": 1,\n  \"made_by\": \"android\""))
        assertTrue("LF only", '\r' !in text)
        val contents = ok(text)
        assertEquals("android", contents.madeBy)
        assertEquals("1.4.5", contents.appVersion)
        val back = LayoutsImport.import(emptyList(), contents, phone, 1_000L)
        assertEquals(2, back.added)
        assertEquals(0, back.leftOutWindows)
        assertEquals(listOf("Browsing", "Movie \"night\""), back.presets.map { it.name })
        presets.zip(back.presets).forEach { (a, b) ->
            assertEquals(a.layout.panels.size, b.layout.panels.size)
            a.layout.panels.zip(b.layout.panels).forEach { (p, q) ->
                assertEquals(p.packageName, q.packageName)
                assertEquals(p.activityName, q.activityName)
                assertEquals(p.yawDeg, q.yawDeg, 1e-4f)
                assertEquals(p.pitchDeg, q.pitchDeg, 1e-4f)
                assertEquals(p.distanceM, q.distanceM, 1e-4f)
                assertEquals(p.widthDeg, q.widthDeg, 1e-4f)
                assertEquals(p.heightDeg, q.heightDeg, 1e-4f)
                assertEquals(p.curve, q.curve, 1e-4f)
            }
        }
        // And through the phone's own preset store.
        assertEquals(back.presets.map { it.apps }, WindowPresets.decode(WindowPresets.encode(back.presets)).map { it.apps })
    }

    @Test
    fun yawIsLeftPositiveInTheFile() {
        // A window 30 degrees to the right on the phone (right positive) is -30 in the file, as the Deck has it.
        val w = ok(LayoutsFile.encode(presets.take(1), "1.4.5")).presets[0].windows
        assertEquals(-30f, w[0].yawDeg, 0f)
        assertEquals(30f, w[1].yawDeg, 0f)
        assertEquals("org.mozilla.firefox", w[0].app.android)
        assertNull(w[0].app.deck)
        assertEquals("Firefox", w[0].app.name)
    }

    @Test
    fun aFileMadeOnTheDeckFindsAppsByName() {
        val deck = """
            {"format":"pocket-xr-layouts","version":1,"made_by":"deck","app_version":"0.4.0","extra":{"x":[1,2]},
             "presets":[
              {"name":"Desk","windows":[
                {"app":{"name":"Firefox","android":null,"deck":"org.mozilla.firefox"},"yaw_deg":20,"pitch_deg":0,"distance_m":1.4,"width_deg":40,"height_deg":22.5,"curve":0,"future":true},
                {"app":{"name":"Google Chrome","deck":"google-chrome"},"yaw_deg":-20,"pitch_deg":0,"distance_m":1.4,"width_deg":40,"height_deg":22.5,"curve":0.5},
                {"app":{"name":"Dolphin","android":null,"deck":"org.kde.dolphin"},"yaw_deg":0,"pitch_deg":-25,"distance_m":1.2,"width_deg":30,"height_deg":20,"curve":0}
              ]},
              {"name":"Steam","windows":[
                {"app":{"name":"Steam","deck":"steam"},"yaw_deg":0,"pitch_deg":0,"distance_m":2,"width_deg":60,"height_deg":34,"curve":0}
              ]}
             ]}
        """.trimIndent()
        val r = LayoutsImport.import(emptyList(), ok(deck), phone, 5L)
        assertEquals(1, r.added)
        assertEquals(2, r.leftOutWindows)
        assertEquals(listOf("Dolphin", "Steam"), r.missingApps)
        val desk = r.presets.single()
        assertEquals("Desk", desk.name)
        assertEquals(listOf("org.mozilla.firefox", "com.android.chrome"), desk.layout.panels.map { it.packageName })
        assertEquals(listOf("Firefox", "Chrome"), desk.layout.panels.map { it.label })
        assertEquals(-20f, desk.layout.panels[0].yawDeg, 0f)
        assertEquals(0.5f, desk.layout.panels[1].curve, 0f)
    }

    @Test
    fun matchingIsByPackageThenNameThenAlias() {
        assertEquals("org.videolan.vlc", LayoutsImport.match(LayoutsFile.App("Anything", "org.videolan.vlc", null), phone)?.packageName)
        assertEquals("org.mozilla.firefox", LayoutsImport.match(LayoutsFile.App("fire-fox ", null, "firefox"), phone)?.packageName)
        assertEquals("org.videolan.vlc", LayoutsImport.match(LayoutsFile.App("VLC media player", null, "org.videolan.VLC"), phone)?.packageName)
        assertEquals("com.android.chrome", LayoutsImport.match(LayoutsFile.App("google-chrome", null, "google-chrome"), phone)?.packageName)
        // A package that isn't here still finds the app by name.
        assertEquals("org.mozilla.firefox", LayoutsImport.match(LayoutsFile.App("Firefox", "org.mozilla.firefox_beta", null), phone)?.packageName)
        // A phone-only app is never swapped for another, and an alias needs its own app.
        assertNull(LayoutsImport.match(LayoutsFile.App("Phone", "com.google.android.dialer", null), phone))
        assertNull(LayoutsImport.match(LayoutsFile.App("Microsoft Edge", null, "microsoft-edge"), phone))
        assertNull(LayoutsImport.match(LayoutsFile.App("...", null, null), phone))
    }

    @Test
    fun theVersionAndFormatAreChecked() {
        assertEquals(LayoutsFile.Read.TooNew(2), LayoutsFile.read("""{"format":"pocket-xr-layouts","version":2,"presets":[]}"""))
        assertEquals(LayoutsFile.Read.NotLayouts, LayoutsFile.read("""{"format":"pocket-xr-settings","version":1,"prefs":{}}"""))
        assertEquals(LayoutsFile.Read.NotLayouts, LayoutsFile.read("""{"format":"pocket-xr-layouts"}"""))
        assertEquals(LayoutsFile.Read.NotLayouts, LayoutsFile.read("not json"))
        assertEquals(LayoutsFile.Read.NotLayouts, LayoutsFile.read("""{"format":"pocket-xr-layouts","version":1,"presets":[{"na"""))
        assertEquals(LayoutsFile.Read.NotLayouts, LayoutsFile.read(""))
        assertEquals(LayoutsFile.Read.NotLayouts, LayoutsFile.read("[1,2]"))
        // A byte order mark and an older or equal version are fine.
        assertTrue(LayoutsFile.read("﻿{\"format\":\"pocket-xr-layouts\",\"version\":1,\"presets\":[]}") is LayoutsFile.Read.Ok)
    }

    @Test
    fun theSkipRules() {
        val existing = listOf(
            WindowPreset(10, "Browsing", presets[0].layout),
            WindowPreset(9, "Workspace 3", presets[0].layout),
        )
        val file = LayoutsFile.Contents(
            "deck", "0.4.0",
            listOf(
                // Only phone apps the phone lacks: the whole preset is left out.
                LayoutsFile.Preset("Calls", listOf(window("Phone", "com.google.android.dialer"), window("Phone", "com.google.android.dialer"))),
                LayoutsFile.Preset("Browsing", listOf(window("Firefox", null), window("Messages", null))),
                LayoutsFile.Preset("browsing", listOf(window("Chrome", null))),
                LayoutsFile.Preset("   ", listOf(window("YouTube", null), window("YouTube", null))),
            ),
        )
        val r = LayoutsImport.import(existing, file, phone, 10L)
        assertEquals(3, r.added)
        assertEquals(2, r.leftOutWindows)
        assertEquals(listOf("Phone"), r.missingApps)
        assertEquals(listOf("Browsing (2)", "browsing (3)", "Workspace 4", "Browsing", "Workspace 3"), r.presets.map { it.name })
        assertEquals(listOf(2, 1, 1), r.presets.take(3).map { it.layout.panels.size })
        assertEquals("ids stay unique", 5, r.presets.map { it.id }.toSet().size)
    }

    @Test
    fun theLimitKeepsTheNewestAndTheNumbersStayInRange() {
        val existing = (1..8).map { WindowPreset(it.toLong(), "Old $it", presets[0].layout) }
        val wild = LayoutsFile.Window(LayoutsFile.App("YouTube", null, null), 400f, 95f, 50f, 500f, 1f, 9f)
        val file = LayoutsFile.Contents("deck", "", (1..3).map { LayoutsFile.Preset("New $it", listOf(wild)) })
        val r = LayoutsImport.import(existing, file, phone, 100L)
        assertEquals(3, r.added)
        assertEquals(WindowPresets.MAX, r.presets.size)
        assertEquals(listOf("New 1", "New 2", "New 3", "Old 1", "Old 2", "Old 3", "Old 4", "Old 5"), r.presets.map { it.name })
        val p = r.presets[0].layout.panels.single()
        assertEquals(-40f, p.yawDeg, 1e-4f)
        assertEquals(PanelLimits.MAX_PITCH, p.pitchDeg, 0f)
        assertEquals(PanelLimits.MAX_DISTANCE, p.distanceM, 0f)
        assertEquals(PanelLimits.MAX_WIDTH_DEG, p.widthDeg, 0f)
        assertEquals(PanelLimits.DEPTH_MIN_SIZE_DEG, p.heightDeg, 0f)
        assertEquals(PanelCurve.MAX, p.curve, 0f)
    }

    @Test
    fun ownPanelsAndEmptyPresetsAreNotShared() {
        val own = SpacePanel(1, "Phone screen", "", "", 0f, 0f)
        val text = LayoutsFile.encode(listOf(WindowPreset(1, "Mine", SpaceLayout(listOf(own))), presets[1]), "1.4.5")
        assertEquals(listOf("Movie \"night\""), ok(text).presets.map { it.name })
    }

    private fun window(name: String, android: String?) =
        LayoutsFile.Window(LayoutsFile.App(name, android, null), 0f, 0f, 1.4f, 40f, 22.5f, 0f)
}
