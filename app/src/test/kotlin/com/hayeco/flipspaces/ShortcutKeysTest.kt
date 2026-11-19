package com.hayeco.flipspaces

import android.view.KeyEvent
import com.hayeco.flipspaces.ShortcutKeys.Key
import com.hayeco.flipspaces.ShortcutKeys.Mod
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutKeysTest {
    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    @Test fun plainKeyIsDownThenUp() = assertEquals(
        listOf(Key(down, KeyEvent.KEYCODE_ESCAPE, 0), Key(up, KeyEvent.KEYCODE_ESCAPE, 0)),
        ShortcutKeys.combo(KeyEvent.KEYCODE_ESCAPE, emptySet()),
    )

    @Test fun ctrlCHoldsCtrlAroundTheKey() = assertEquals(
        listOf(
            Key(down, KeyEvent.KEYCODE_CTRL_LEFT, Mod.CTRL.meta),
            Key(down, KeyEvent.KEYCODE_C, Mod.CTRL.meta),
            Key(up, KeyEvent.KEYCODE_C, Mod.CTRL.meta),
            Key(up, KeyEvent.KEYCODE_CTRL_LEFT, 0),
        ),
        ShortcutKeys.combo(KeyEvent.KEYCODE_C, setOf(Mod.CTRL)),
    )

    @Test fun ctrlShiftEscPressesInOrderAndReleasesInReverse() {
        val keys = ShortcutKeys.combo(KeyEvent.KEYCODE_ESCAPE, setOf(Mod.SHIFT, Mod.CTRL))
        assertEquals(
            listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT),
            keys.map { it.keyCode },
        )
        assertEquals(0, keys.last().metaState)
        assertEquals(Mod.CTRL.meta or Mod.SHIFT.meta, keys[2].metaState)
    }

    /** Win goes as its stand-in with no meta bit: Android kept the real Meta key from the PC screen. */
    @Test fun winAlone() = assertEquals(
        listOf(Key(down, ShortcutKeys.WIN_STAND_IN, 0), Key(up, ShortcutKeys.WIN_STAND_IN, 0)),
        ShortcutKeys.alone(Mod.WIN),
    )

    @Test fun winDHoldsTheStandInWithNoMetaBit() = assertEquals(
        listOf(
            Key(down, ShortcutKeys.WIN_STAND_IN, 0),
            Key(down, KeyEvent.KEYCODE_D, 0),
            Key(up, KeyEvent.KEYCODE_D, 0),
            Key(up, ShortcutKeys.WIN_STAND_IN, 0),
        ),
        ShortcutKeys.combo(KeyEvent.KEYCODE_D, setOf(Mod.WIN)),
    )
}
