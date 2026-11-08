package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.3 (issue 59: holding W or Space "does nothing in a game like Terraria"): Game keys send a key down when a finger
 * lands and a key up when it lifts, several at once, and a slide from key to key moves the press.
 */
class GameKeys123Test {
    private val left = GameKeyPresses(GameKeyLayout.LEFT)
    private val right = GameKeyPresses(GameKeyLayout.RIGHT)
    private fun down(code: Int) = GameKeyChange(code, true)
    private fun up(code: Int) = GameKeyChange(code, false)

    @Test
    fun theKeysAreWhereTheyShouldBe() {
        assertEquals(GameKeyLayout.W, left.keyAt(0.5f, 0.1f)?.keyCode)
        assertEquals(GameKeyLayout.A, left.keyAt(0.1f, 0.5f)?.keyCode)
        assertEquals(GameKeyLayout.D, left.keyAt(0.9f, 0.5f)?.keyCode)
        assertEquals(GameKeyLayout.SPACE, right.keyAt(0.5f, 0.9f)?.keyCode)
        assertEquals(GameKeyLayout.EXIT, right.keyAt(0.9f, 0.5f)?.keyCode)
        assertEquals(null, left.keyAt(1.2f, 0.5f))
        // Every key inside its cluster, none overlapping.
        for (keys in listOf(GameKeyLayout.LEFT, GameKeyLayout.RIGHT)) {
            for (k in keys) assertTrue(k.label, k.x >= 0f && k.y >= 0f && k.x + k.w <= 1.0001f && k.y + k.h <= 1.0001f)
            for (k in keys) assertEquals(k.label, k, keys.single { it.contains(k.x + k.w / 2, k.y + k.h / 2) })
        }
    }

    @Test
    fun aHeldKeyStaysDownUntilTheFingerLifts() {
        assertEquals(listOf(down(GameKeyLayout.W)), left.down(0, 0.5f, 0.1f))
        assertEquals(emptyList<GameKeyChange>(), left.move(0, 0.52f, 0.12f))
        assertEquals(listOf(up(GameKeyLayout.W)), left.up(0))
    }

    @Test
    fun severalFingersHoldSeveralKeys() {
        assertEquals(listOf(down(GameKeyLayout.W)), left.down(0, 0.5f, 0.1f))
        assertEquals(listOf(down(GameKeyLayout.SHIFT)), left.down(1, 0.1f, 0.9f))
        assertEquals(setOf(GameKeyLayout.W, GameKeyLayout.SHIFT), left.held.map { it.keyCode }.toSet())
        assertEquals(listOf(up(GameKeyLayout.SHIFT)), left.up(1))
        assertEquals(listOf(up(GameKeyLayout.W)), left.up(0))
    }

    @Test
    fun aSlideFromKeyToKeyMovesThePress() {
        left.down(0, 0.1f, 0.5f)
        assertEquals(listOf(up(GameKeyLayout.A), down(GameKeyLayout.S)), left.move(0, 0.5f, 0.5f))
        // Off every key: the press goes.
        assertEquals(listOf(up(GameKeyLayout.S)), left.move(0, 1.5f, 0.5f))
        assertEquals(emptyList<GameKeyChange>(), left.up(0))
    }

    @Test
    fun twoFingersOnOneKeyAreOnePress() {
        assertEquals(listOf(down(GameKeyLayout.SPACE)), right.down(0, 0.2f, 0.9f))
        assertEquals(emptyList<GameKeyChange>(), right.down(1, 0.8f, 0.9f))
        assertEquals(emptyList<GameKeyChange>(), right.up(0))
        assertEquals(listOf(up(GameKeyLayout.SPACE)), right.up(1))
    }

    @Test
    fun cancelLetsGoOfEverythingButTheWayOut() {
        left.down(0, 0.5f, 0.1f)
        left.down(1, 0.5f, 0.5f)
        assertEquals(setOf(up(GameKeyLayout.W), up(GameKeyLayout.S)), left.cancel().toSet())
        assertTrue(left.held.isEmpty())
        right.down(0, 0.9f, 0.5f)
        assertEquals(emptyList<GameKeyChange>(), right.cancel())
    }
}
