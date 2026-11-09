package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Unreleased: the Menu's card asking sideload users to join the Play closed test ([PlayTesters]). */
class PlayTestersTest {
    private val now = 1_800_000_000_000L
    private val fresh = PlayTesters.State()

    @Test
    fun aFreshInstallSeesJoin() {
        assertEquals(PlayTesters.Card.JOIN, PlayTesters.card(playBuild = false, fresh, now))
    }

    @Test
    fun thePlayBuildNeverSeesIt() {
        assertEquals(PlayTesters.Card.HIDDEN, PlayTesters.card(playBuild = true, fresh, now))
        assertEquals(PlayTesters.Card.HIDDEN, PlayTesters.card(playBuild = true, PlayTesters.joined(fresh), now))
    }

    @Test
    fun joinThenShowsThePlayInvite() {
        assertEquals(PlayTesters.Card.PLAY_INVITE, PlayTesters.card(false, PlayTesters.joined(fresh), now))
    }

    @Test
    fun bothLinksHideItForGood() {
        val done = PlayTesters.playOpened(PlayTesters.joined(fresh))
        assertEquals(PlayTesters.Card.HIDDEN, PlayTesters.card(false, done, now))
        assertEquals(PlayTesters.Card.HIDDEN, PlayTesters.card(false, done, now + 10 * PlayTesters.SNOOZE_MS))
    }

    @Test
    fun thePlayInviteAloneDoesNotFinishIt() {
        assertFalse(PlayTesters.playOpened(fresh).done)
    }

    @Test
    fun notNowHidesItForSevenDays() {
        val later = PlayTesters.notNow(fresh, now)
        assertEquals(PlayTesters.Card.HIDDEN, PlayTesters.card(false, later, now))
        assertEquals(PlayTesters.Card.HIDDEN, PlayTesters.card(false, later, now + PlayTesters.SNOOZE_MS - 1))
        assertEquals(PlayTesters.Card.JOIN, PlayTesters.card(false, later, now + PlayTesters.SNOOZE_MS))
        assertEquals(7L * 24 * 60 * 60 * 1000, PlayTesters.SNOOZE_MS)
    }

    @Test
    fun notNowAfterJoinComesBackWithThePlayInvite() {
        val later = PlayTesters.notNow(PlayTesters.joined(fresh), now)
        assertEquals(PlayTesters.Card.PLAY_INVITE, PlayTesters.card(false, later, now + PlayTesters.SNOOZE_MS))
    }

    @Test
    fun alreadyJoinedHidesItForGood() {
        val done = PlayTesters.alreadyJoined(fresh)
        assertEquals(PlayTesters.Card.HIDDEN, PlayTesters.card(false, done, now + 100 * PlayTesters.SNOOZE_MS))
    }

    @Test
    fun theLinksAndWordsAreMikes() {
        assertEquals("https://groups.google.com/g/pocketxr-testers", PlayTesters.GROUP_URL)
        assertEquals("https://play.google.com/apps/testing/com.hayeco.flipspaces", PlayTesters.PLAY_URL)
        for (line in listOf(PlayTesters.TITLE, PlayTesters.BODY, PlayTesters.JOIN, PlayTesters.OPEN_PLAY, PlayTesters.NOT_NOW, PlayTesters.ALREADY_JOINED)) {
            assertFalse(line, '—' in line)
            assertFalse(line, Regex("\\b(we|our|us)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line))
        }
    }
}
