package com.hayeco.flipspaces

import android.view.KeyEvent

/**
 * The trackpad keyboard's shortcut row: Esc, Tab, the arrows and Delete, and Ctrl, Alt, Shift and Win, which stay held
 * for the next key (typed or from the row), as on a laptop's sticky keys. A PC screen passes each key to the PC as it
 * is ([com.hayeco.flipspaces.pcscreen.PcScreenActivity]), so a combination goes as its real keys: each modifier down,
 * the key down and up, then each modifier up.
 */
object ShortcutKeys {
    /** A modifier in the row: its bit in [KeyEvent] meta state, and its own key. */
    enum class Mod(val meta: Int, val keyCode: Int) {
        CTRL(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, KeyEvent.KEYCODE_CTRL_LEFT),
        ALT(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON, KeyEvent.KEYCODE_ALT_LEFT),
        SHIFT(KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON, KeyEvent.KEYCODE_SHIFT_LEFT),
        // Android keeps a real Win (Meta) key, and keys with Meta held, for itself: none reached the PC screen (phone
        // test, 19 Nov 2026). Win goes as a stand-in key with no meta bit, which a PC screen sends to the PC as Win.
        WIN(0, WIN_STAND_IN),
    }

    /** The Win key's stand-in: a gamepad button no keyboard sends, from the virtual keyboard device only. */
    const val WIN_STAND_IN = KeyEvent.KEYCODE_BUTTON_16

    /** One key event to send: [KeyEvent.ACTION_DOWN] or [KeyEvent.ACTION_UP], the key, and the meta state with it. */
    data class Key(val action: Int, val keyCode: Int, val metaState: Int)

    /** The plain keys of the row, in order. */
    val PLAIN = listOf(
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_FORWARD_DEL,
    )

    private val modifierCodes = Mod.entries.map { it.keyCode }.toSet() +
        setOf(KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_META_RIGHT)

    fun isModifier(keyCode: Int) = keyCode in modifierCodes

    /** [keyCode] with the [held] modifiers: each modifier down, the key down and up, each modifier up. */
    fun combo(keyCode: Int, held: Set<Mod>): List<Key> {
        val mods = Mod.entries.filter { it in held }
        val out = ArrayList<Key>()
        var meta = 0
        for (m in mods) {
            meta = meta or m.meta
            out += Key(KeyEvent.ACTION_DOWN, m.keyCode, meta)
        }
        out += Key(KeyEvent.ACTION_DOWN, keyCode, meta)
        out += Key(KeyEvent.ACTION_UP, keyCode, meta)
        for (m in mods.reversed()) {
            meta = meta and m.meta.inv()
            out += Key(KeyEvent.ACTION_UP, m.keyCode, meta)
        }
        return out
    }

    /** A modifier on its own (Win alone opens the Start menu): down and up. */
    fun alone(mod: Mod): List<Key> = listOf(
        Key(KeyEvent.ACTION_DOWN, mod.keyCode, mod.meta),  // (Win's meta is 0: see WIN)
        Key(KeyEvent.ACTION_UP, mod.keyCode, 0),
    )

    /** What the row's key shows. */
    fun label(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> "Esc"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_DPAD_LEFT -> "←"
        KeyEvent.KEYCODE_DPAD_UP -> "↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Del"
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }

    fun label(mod: Mod): String = when (mod) {
        Mod.CTRL -> "Ctrl"
        Mod.ALT -> "Alt"
        Mod.SHIFT -> "Shift"
        Mod.WIN -> "Win"
    }
}
