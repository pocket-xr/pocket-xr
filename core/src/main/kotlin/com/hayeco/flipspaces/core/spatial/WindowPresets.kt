package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText

/**
 * 0.5.37 (Mike: "an option for users to save preset app windows so that every time they click a preset, it opens those
 * exact app windows in the exact size and close/far they were at"): a preset is a saved [SpaceLayout] (each window's app,
 * place, size, distance and curve) with a name. Saved as one line per preset, tab separated: id, name, then the layout in
 * [LayoutCodec]'s form with its line breaks escaped.
 */
data class WindowPreset(val id: Long, val name: String, val layout: SpaceLayout) {
    /** "YouTube · Twitch · Messages", for the Menu. */
    val apps: String get() = layout.panels.joinToString(" \u00b7 ") { it.label }
}

object WindowPresets {
    const val MAX = 8

    /** The name a new preset gets: "Workspace N", one past the highest in use. */
    fun nextName(existing: List<WindowPreset>): String {
        val top = existing.mapNotNull { number(it.name) }.maxOrNull() ?: 0
        return UiText.format("spatial_preset_name", NAME, top + 1)
    }

    /** The name's pattern in English; the phone's language may word it differently ([number] reads both). */
    private const val NAME = "Workspace %1\$d"

    /** N of a "Workspace N" name, in English (saved before the app spoke other languages) or the phone's language. */
    private fun number(name: String): Int? {
        name.removePrefix("Workspace ").toIntOrNull()?.let { return it }
        val pattern = UiText.get("spatial_preset_name", NAME)
        val at = pattern.indexOf("%1\$d").takeIf { it >= 0 } ?: return null
        val before = pattern.substring(0, at)
        val after = pattern.substring(at + 4)
        if (name.length < before.length + after.length || !name.startsWith(before) || !name.endsWith(after)) return null
        return name.substring(before.length, name.length - after.length).trim().toIntOrNull()
    }

    /** [current] saved as a new preset at the top (unwrapped; at most [MAX], the oldest dropped), or null with no windows. */
    fun save(existing: List<WindowPreset>, current: SpaceLayout, nowMs: Long): List<WindowPreset>? {
        if (current.panels.isEmpty()) return null
        val flat = if (current.wrap != null) SpaceWrap.off(current) else current
        val preset = WindowPreset(nowMs, nextName(existing), flat)
        return (listOf(preset) + existing).take(MAX)
    }

    /** 0.5.47: the longest name a preset keeps. */
    const val MAX_NAME = 40

    /**
     * 0.5.47 (Mike: "users should be able to rename their window presets"): [presets] with [id]'s name set to [name],
     * trimmed, on one line, at most [MAX_NAME] characters; a blank name leaves it as it was.
     */
    fun rename(presets: List<WindowPreset>, id: Long, name: String): List<WindowPreset> {
        val clean = name.replace('\t', ' ').replace('\n', ' ').trim().take(MAX_NAME).trim()
        if (clean.isEmpty()) return presets
        return presets.map { if (it.id == id) it.copy(name = clean) else it }
    }

    fun encode(presets: List<WindowPreset>): String = presets.joinToString("\n") { p ->
        listOf(p.id.toString(), p.name.replace('\t', ' ').replace('\n', ' '), LayoutCodec.encode(p.layout).replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")).joinToString("\t")
    }

    fun decode(text: String?): List<WindowPreset> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lines().mapNotNull { line ->
            val f = line.split('\t', limit = 3)
            if (f.size < 3) return@mapNotNull null
            val id = f[0].toLongOrNull() ?: return@mapNotNull null
            val raw = StringBuilder()
            var i = 0
            val s = f[2]
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> raw.append('\n')
                        't' -> raw.append('\t')
                        '\\' -> raw.append('\\')
                        else -> raw.append(s[i + 1])
                    }
                    i += 2
                } else {
                    raw.append(ch)
                    i++
                }
            }
            val layout = LayoutCodec.decode(raw.toString())
            if (layout.panels.isEmpty()) null else WindowPreset(id, f[1], layout)
        }
    }
}
