package com.hayeco.flipspaces.core

/**
 * Unreleased (Mike: get the sideload users into the Play closed test, so Pocket XR can reach production): a one-time card
 * in the trackpad's Menu, next to the update row. Join opens the Google Group; back from it, Open Play invite opens Play's
 * opt-in page. Not now hides it for 7 days. I already joined, or opening both links, hides it for good. Never in the Play
 * build (BuildConfig.PLAY_BUILD), whose users are on Play already.
 */
object PlayTesters {
    const val GROUP_URL = "https://groups.google.com/a/mikehaye.com/g/pocketxr-testers"
    const val PLAY_URL = "https://play.google.com/apps/testing/com.hayeco.flipspaces"

    const val TITLE = "Help Pocket XR get on Google Play"
    const val BODY = "Join the testers in two taps. You'll need to uninstall this copy before installing from Play: first save your settings (Help and more, Save settings to a file), then restore them in the Play version."
    const val JOIN = "Join"
    const val OPEN_PLAY = "Open Play invite"
    const val NOT_NOW = "Not now"
    const val ALREADY_JOINED = "I already joined"

    /** How long Not now hides the card. */
    const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000

    /** What is saved: the group link opened, Not now's end (0 for none), and whether the card is gone for good. */
    data class State(val groupOpened: Boolean = false, val snoozedUntil: Long = 0L, val done: Boolean = false)

    /** The card's look: hidden, Join alone, or Join with Open Play invite once the group link was opened. */
    enum class Card { HIDDEN, JOIN, PLAY_INVITE }

    fun card(playBuild: Boolean, state: State, nowMs: Long): Card = when {
        playBuild || state.done || nowMs < state.snoozedUntil -> Card.HIDDEN
        state.groupOpened -> Card.PLAY_INVITE
        else -> Card.JOIN
    }

    fun joined(state: State): State = state.copy(groupOpened = true)

    /** The Play invite opened: both links done, so the card is gone for good. */
    fun playOpened(state: State): State = if (state.groupOpened) state.copy(done = true) else state

    fun notNow(state: State, nowMs: Long): State = state.copy(snoozedUntil = nowMs + SNOOZE_MS)

    fun alreadyJoined(state: State): State = state.copy(done = true)
}
