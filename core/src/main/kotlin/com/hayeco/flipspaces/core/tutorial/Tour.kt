package com.hayeco.flipspaces.core.tutorial

import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.GlassesModel
import com.hayeco.flipspaces.core.spatial.SteadyMode

/**
 * 1.5.0 (Mike, 15 November 2026: a tutorial new users see after installing and can reopen whenever they want): the tour.
 * Its steps in order, when it opens by itself, and what it says for the glasses and the setup this phone has. The screen
 * (TourActivity) only draws what this returns. See docs/TUTORIAL.md.
 *
 * Before it there was the trackpad's card of gesture pages ([com.hayeco.flipspaces.core.pointer.IntroPages]), which
 * stays for the cover screen and as the Menu's "How to use the trackpad".
 */
object Tour {
    /** One idea each, in the order shown. */
    enum class Step { WELCOME, SETUP, POINT, THREE_FINGERS, WINDOWS, LOOK, DEPTH, MORE, DONE }

    val STEPS: List<Step> = Step.entries

    /** The step [by] steps on from [from], kept inside the tour. */
    fun move(from: Step, by: Int): Step = STEPS[(from.ordinal + by).coerceIn(0, STEPS.lastIndex)]

    fun isFirst(step: Step): Boolean = step == STEPS.first()
    fun isLast(step: Step): Boolean = step == STEPS.last()

    // ---- when it opens ----

    /** How this copy of Pocket XR came to 1.5.0: installed with the tour in it, or updated from a version without. */
    enum class Arrival { NEW_INSTALL, UPDATE }

    /**
     * What the phone remembers. [arrival] is null until 1.5.0 first runs. [gestureCardsShown] is the old first-run flag
     * (the trackpad's "IntroShown"): true on every copy that ran before 1.5.0.
     */
    data class Memory(
        val arrival: Arrival? = null,
        val gestureCardsShown: Boolean = false,
        val seen: Boolean = false,
        val offerAnswered: Boolean = false,
    )

    /** What the trackpad does as it opens. */
    enum class Opening {
        /** The tour, over the trackpad. */
        SHOW,

        /** A card: there is a new tour, "Take the tour" or "Not now". */
        OFFER,

        /** The cover screen's first run: the gesture cards, as before 1.5.0. */
        GESTURE_CARDS,

        NOTHING,
    }

    /**
     * [m] with [Memory.arrival] decided, once: a copy whose old first-run flag is already set was in use before the tour
     * existed, so it is an update. A restored settings file brings its own answer along.
     */
    fun arrived(m: Memory): Memory =
        if (m.arrival != null) m else m.copy(arrival = if (m.gestureCardsShown) Arrival.UPDATE else Arrival.NEW_INSTALL)

    /**
     * What the trackpad shows as it opens. Never anything over a trackpad that [openedByItself] (the glasses plugged in
     * with the phone closed): it waits for the next ordinary open. A new install gets the tour once, on the main screen;
     * on the [cover] screen, too small for it, the gesture cards as before. An update never gets the tour by itself, only
     * the offer, once.
     */
    fun onTrackpadOpen(memory: Memory, openedByItself: Boolean, cover: Boolean): Opening {
        val m = arrived(memory)
        if (openedByItself) return Opening.NOTHING
        if (cover) return if (m.gestureCardsShown) Opening.NOTHING else Opening.GESTURE_CARDS
        if (m.seen) return Opening.NOTHING
        return when (m.arrival) {
            Arrival.UPDATE -> if (m.offerAnswered) Opening.NOTHING else Opening.OFFER
            else -> Opening.SHOW
        }
    }

    /** After [opening] was acted on. Shown counts as seen even when skipped; the offer is made once, whatever the answer. */
    fun after(memory: Memory, opening: Opening): Memory {
        val m = arrived(memory)
        return when (opening) {
            Opening.SHOW -> m.copy(seen = true, gestureCardsShown = true)
            Opening.OFFER -> m.copy(offerAnswered = true)
            Opening.GESTURE_CARDS -> m.copy(gestureCardsShown = true)
            Opening.NOTHING -> m
        }
    }

    /** After the user opened the tour themself (the Menu, Settings, or the offer's "Take the tour"): no offer after that. */
    fun afterOpenedByUser(memory: Memory): Memory = arrived(memory).copy(seen = true, offerAnswered = true)

    // ---- the setup checklist ----

    /** The one thing to do next about Shizuku. */
    enum class ShizukuAction { GET, OPEN, ALLOW, NONE }

    fun shizukuAction(status: ShizukuStatus): ShizukuAction = when (status) {
        ShizukuStatus.NOT_INSTALLED -> ShizukuAction.GET
        ShizukuStatus.NOT_RUNNING -> ShizukuAction.OPEN
        ShizukuStatus.NO_PERMISSION -> ShizukuAction.ALLOW
        ShizukuStatus.READY -> ShizukuAction.NONE
    }

    /** What is still open at the end. The add-on is optional, so it is never on the list. */
    enum class Remaining { PLUG_IN_GLASSES, SHIZUKU }

    fun remaining(glassesConnected: Boolean, shizuku: ShizukuStatus): List<Remaining> = buildList {
        if (shizuku != ShizukuStatus.READY) add(Remaining.SHIZUKU)
        if (!glassesConnected) add(Remaining.PLUG_IN_GLASSES)
    }

    // ---- what is known for these glasses (the README's tiers; change both together) ----

    enum class Support {
        /** Run by the project: the Aura and the One. */
        TESTED,

        /** Confirmed by community testers: the One Pro and the 1S. */
        COMMUNITY,

        /** The Air family: the Air 2 is confirmed by a tester, the others share its head tracking and have no tester. */
        AIR_FAMILY,

        /** VITURE: the Beast is confirmed by a tester, the others are not tried. */
        VITURE_FAMILY,

        /** Early: a retest is pending (the One Pro+). */
        EARLY,

        /** Not on the list: plain fallbacks, head tracking only if they send it the way a known model does. */
        UNLISTED,
    }

    /** The tier of [model], or null when no glasses are known yet (none plugged in, none used before). */
    fun support(model: GlassesModel?): Support? = when (model) {
        null -> null
        GlassesModel.AURA, GlassesModel.ONE -> Support.TESTED
        GlassesModel.ONE_PRO, GlassesModel.ONE_S -> Support.COMMUNITY
        GlassesModel.AIR -> Support.AIR_FAMILY
        GlassesModel.VITURE -> Support.VITURE_FAMILY
        GlassesModel.ONE_PRO_PLUS -> Support.EARLY
        GlassesModel.OTHER_XREAL, GlassesModel.UNKNOWN -> Support.UNLISTED
    }

    /** How these glasses get into 3D, for the Depth step's note. */
    enum class Depth {
        /** Pocket XR switches them itself where they allow it (also said when no glasses are known). */
        BY_ITSELF,

        /** 3D is turned on in the glasses' own menu first (VITURE). */
        GLASSES_MENU,

        /** Pocket XR cannot switch them to 3D yet (the Air family, issue 54). */
        NOT_YET,
    }

    fun depth(model: GlassesModel?): Depth = when (model) {
        GlassesModel.VITURE -> Depth.GLASSES_MENU
        GlassesModel.AIR -> Depth.NOT_YET
        else -> Depth.BY_ITSELF
    }

    /** Steady mode for these glasses, for the Depth step's note. */
    enum class Steady {
        /** No glasses known: say which glasses have it. */
        SAY_WHICH,

        /** These glasses have it, and the project has run it on them (the Aura). */
        HERE_TESTED,

        /** These glasses have it, untested by the project (the One series). */
        HERE_UNTESTED,

        /** These glasses do not have it. */
        NOT_HERE,
    }

    fun steady(model: GlassesModel?): Steady = when {
        model == null -> Steady.SAY_WHICH
        !SteadyMode.offeredOn(model) -> Steady.NOT_HERE
        // Proven on the Aura (1.2.0, 7 November 2026); the One series should work and is untested.
        model == GlassesModel.AURA -> Steady.HERE_TESTED
        else -> Steady.HERE_UNTESTED
    }
}
