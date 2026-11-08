package com.hayeco.flipspaces.core

import com.hayeco.flipspaces.core.AddonSetup.Action
import com.hayeco.flipspaces.core.AddonSetup.Download
import com.hayeco.flipspaces.core.AddonSetup.State
import com.hayeco.flipspaces.core.pointer.IntroArt
import com.hayeco.flipspaces.core.pointer.IntroPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.1: the add-on step in the tutorial and the glasses check: not installed, installed with notification access off
 * and on, the install permission missing, the download running, failed and handed to Android's prompt; and the newest
 * add-on picked from GitHub's release list.
 */
class AddonSetup0121Test {
    private fun step(
        installed: Boolean = false,
        accessOn: Boolean = false,
        download: Download = Download.Idle,
        canInstall: Boolean = true,
        selfInstall: Boolean = true,
    ) = AddonSetup.step(installed, accessOn, download, canInstall, selfInstall)

    private fun AddonSetup.Step.actions() = buttons.map { it.action }
    private fun AddonSetup.Step.primary() = buttons.single { it.primary }

    @Test fun notInstalledOffersOneTapInstall() {
        val s = step()
        assertEquals(State.NOT_INSTALLED, s.state)
        assertFalse(s.done)
        assertEquals(AddonSetup.INSTALL, s.primary().label)
        assertEquals(listOf(Action.INSTALL), s.actions())
        assertNull(s.progress)
    }

    @Test fun installedWithAccessOffIsDoneAndOpensTheAddonsSetup() {
        val s = step(installed = true, accessOn = false)
        assertEquals(State.ACCESS_OFF, s.state)
        assertTrue(s.done)
        assertEquals(Action.OPEN_SETUP, s.primary().action)
        assertFalse(s.actions().contains(Action.INSTALL))
        assertTrue(s.line.contains("notification access"))
    }

    @Test fun installedWithAccessOnIsDoneWithTheSetupStillThere() {
        val s = step(installed = true, accessOn = true)
        assertEquals(State.ACCESS_ON, s.state)
        assertTrue(s.done)
        assertEquals(listOf(Action.OPEN_SETUP), s.actions())
        assertTrue(s.buttons.none { it.primary })
    }

    @Test fun installedWinsOverAStaleDownload() {
        for (d in listOf(Download.Prompted, Download.Failed("x"), Download.Running(50))) {
            assertEquals("$d", State.ACCESS_OFF, step(installed = true, download = d).state)
        }
        assertEquals(State.ACCESS_ON, step(installed = true, accessOn = true, canInstall = false).state)
    }

    @Test fun downloadFailureSaysWhyWithRetryAndTheReleasesPage() {
        val s = step(download = Download.Failed(AddonSetup.reason(0)))
        assertEquals(State.FAILED, s.state)
        assertFalse(s.done)
        assertTrue(s.line.contains("no connection to GitHub"))
        assertEquals(AddonSetup.TRY_AGAIN, s.primary().label)
        assertEquals(Action.INSTALL, s.primary().action)
        assertTrue(s.actions().contains(Action.OPEN_RELEASES))
    }

    @Test fun failureReasonsArePlain() {
        assertEquals("no connection to GitHub", AddonSetup.reason(0))
        assertEquals("no add-on release found", AddonSetup.reason(404))
        assertTrue(AddonSetup.reason(403).contains("busy"))
        assertEquals("GitHub answered 500", AddonSetup.reason(500))
    }

    @Test fun downloadingShowsProgressAndNoButtons() {
        val s = step(download = Download.Running(42))
        assertEquals(State.DOWNLOADING, s.state)
        assertEquals(42, s.progress)
        assertTrue(s.line.contains("42%"))
        assertTrue(s.buttons.isEmpty())
        assertEquals(0, step(download = Download.Running(null)).progress)
    }

    @Test fun missingInstallPermissionSendsToTheSettingFirst() {
        val s = step(canInstall = false)
        assertEquals(State.NEEDS_PERMISSION, s.state)
        assertEquals(Action.ALLOW_INSTALLS, s.primary().action)
        assertTrue(s.actions().contains(Action.OPEN_RELEASES))
        assertTrue(s.line.contains("come back"))
    }

    @Test fun afterThePromptInstallCanBeTappedAgain() {
        val s = step(download = Download.Prompted)
        assertEquals(State.PROMPTED, s.state)
        assertEquals(Action.INSTALL, s.primary().action)
        assertTrue(s.actions().contains(Action.OPEN_RELEASES))
    }

    @Test fun googlePlayCopiesGetTheAddonFromPlay() {
        val s = step(selfInstall = false, canInstall = false)
        assertEquals(State.GET_FROM_STORE, s.state)
        assertEquals(listOf(Action.OPEN_RELEASES), s.actions())
    }

    @Test fun theCheckShowsTheStepUntilAccessIsOn() {
        assertTrue(AddonSetup.showAfterCheck(installed = false, accessOn = false))
        assertTrue(AddonSetup.showAfterCheck(installed = true, accessOn = false))
        assertFalse(AddonSetup.showAfterCheck(installed = true, accessOn = true))
    }

    @Test fun wordsArePlain() {
        val words = buildList {
            add(AddonSetup.TITLE); add(AddonSetup.WHAT); add(AddonSetup.NOTE); addAll(AddonSetup.ITEMS)
            for (installed in listOf(false, true)) for (access in listOf(false, true)) for (can in listOf(false, true)) {
                for (d in listOf(Download.Idle, Download.Running(10), Download.Failed("no connection to GitHub"), Download.Prompted)) {
                    for (self in listOf(false, true)) {
                        val s = step(installed, access, d, can, self)
                        add(s.line)
                        s.buttons.forEach { add(it.label) }
                    }
                }
            }
        }
        for (w in words) {
            assertFalse(w, w.contains('—') || w.contains('–'))
            assertFalse(w, Regex("\\b(we|our|us)\\b", RegexOption.IGNORE_CASE).containsMatchIn(w))
        }
    }

    private fun release(tag: String, apk: String?, prerelease: Boolean = false, draft: Boolean = false) = """
        {"url":"https://api.github.com/repos/pocket-xr/pocket-xr/releases/1","assets_url":"https://api.github.com/x",
         "tag_name":"$tag","draft":$draft,"prerelease":$prerelease,
         "assets":[${apk?.let { """{"url":"https://api.github.com/a","name":"$it","browser_download_url":"https://github.com/pocket-xr/pocket-xr/releases/download/$tag/$it"}""" } ?: ""}],
         "body":"notes"}
    """.trimIndent()

    @Test fun picksTheNewestAddonAmongPocketXrsReleases() {
        val json = "[" + listOf(
            release("v1.2.0", "pocketxr-1.2.0.apk"),
            release("addon-v1.1", "pocketxr-addon-1.1.apk"),
            release("v1.1.10", "pocketxr-1.1.10.apk"),
            release("addon-v1.0", "pocketxr-addon-1.0.apk"),
        ).joinToString(",") + "]"
        val r = AddonSetup.latest(json)
        assertNotNull(r)
        assertEquals("1.1", r!!.version)
        assertEquals("https://github.com/pocket-xr/pocket-xr/releases/download/addon-v1.1/pocketxr-addon-1.1.apk", r.apkUrl)
    }

    @Test fun picksByVersionNotOrder() {
        val json = "[" + release("addon-v1.0", "pocketxr-addon-1.0.apk") + "," + release("addon-v1.10", "pocketxr-addon-1.10.apk") + "]"
        assertEquals("1.10", AddonSetup.latest(json)!!.version)
    }

    @Test fun skipsPreReleasesDraftsAndOtherHosts() {
        val json = "[" + listOf(
            release("addon-v2.0", "pocketxr-addon-2.0.apk", prerelease = true),
            release("addon-v1.9", "pocketxr-addon-1.9.apk", draft = true),
            release("addon-v1.1", "pocketxr-addon-1.1.apk"),
        ).joinToString(",") + "]"
        assertEquals("1.1", AddonSetup.latest(json)!!.version)
        val elsewhere = """[{"url":"u","assets_url":"a","draft":false,"prerelease":false,"assets":[{"browser_download_url":"https://example.com/pocketxr-addon-9.9.apk"}]}]"""
        assertNull(AddonSetup.latest(elsewhere))
    }

    @Test fun noAddonReleaseOrUnreadableAnswer() {
        assertNull(AddonSetup.latest("[" + release("v1.2.0", "pocketxr-1.2.0.apk") + "]"))
        assertNull(AddonSetup.latest("[]"))
        assertNull(AddonSetup.latest("not json"))
    }

    @Test fun theReleasesPageIsTheReadmesLink() {
        assertEquals("https://github.com/pocket-xr/pocket-xr/releases?q=addon&expanded=true", AddonSetup.RELEASES_PAGE)
    }

    @Test fun theTutorialHasTheAddonPageBeforeItsLastPage() {
        val pages = IntroPages.firstRun(landscapeHandheld = false, spatialMouse = true)
        assertEquals(IntroArt.ADDON, pages[pages.size - 2].art)
        assertEquals(AddonSetup.TITLE, pages[pages.size - 2].title)
        // The Menu's own pages do not have it; the tutorial still ends on where Exit is.
        assertTrue(IntroPages.trackpad(landscapeHandheld = false, spatialMouse = true).none { it.art == IntroArt.ADDON })
        assertTrue(pages.last().note!!.contains(IntroPages.EXIT_LABEL))
    }
}
