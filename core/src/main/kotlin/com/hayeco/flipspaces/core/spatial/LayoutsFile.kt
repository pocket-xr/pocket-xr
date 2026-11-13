package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.AppEntry

/**
 * 1.4.5 (a Reddit user asked whether saved layouts carry over to the Steam Deck): the window presets as one small JSON
 * file that Pocket XR on Android and Pocket XR for Steam Deck both write (Share layouts) and read (Import layouts).
 * The format is shared with the Deck, so it changes only with it:
 *
 * ```
 * { "format": "pocket-xr-layouts", "version": 1, "made_by": "android", "app_version": "1.4.5",
 *   "presets": [ { "name": "Workspace 1", "windows": [ { "app": { "name": "Firefox", "android": "org.mozilla.firefox",
 *   "deck": null }, "yaw_deg": 0.0, "pitch_deg": -5.0, "distance_m": 1.5, "width_deg": 40.0, "height_deg": 22.5,
 *   "curve": 0.0 } ] } ] }
 * ```
 *
 * The file's yaw is left positive (the Deck's way); a [SpacePanel]'s is right positive ([Vec3.direction]), so it is
 * turned round both ways. Sizes are angles in both, so they go across as they are. Unknown keys are ignored.
 */
object LayoutsFile {
    const val FORMAT = "pocket-xr-layouts"
    const val VERSION = 1
    const val MIME = "application/json"
    const val MADE_BY = "android"

    /** "pocketxr-layouts-2026-11-13.json"; [date] is yyyy-MM-dd. */
    fun fileName(date: String): String = "pocketxr-layouts-$date.json"

    /** One window's app: the label the user sees and its id on each platform (null when made on the other). */
    data class App(val name: String, val android: String?, val deck: String?)

    /** One window in the file's units: yaw left positive, pitch up positive, degrees; distance in metres. */
    data class Window(
        val app: App,
        val yawDeg: Float,
        val pitchDeg: Float,
        val distanceM: Float,
        val widthDeg: Float,
        val heightDeg: Float,
        val curve: Float,
    )

    data class Preset(val name: String, val windows: List<Window>)

    data class Contents(val madeBy: String, val appVersion: String, val presets: List<Preset>)

    sealed interface Read {
        data class Ok(val contents: Contents) : Read

        /** Not JSON, or its `format` is not [FORMAT]. */
        data object NotLayouts : Read

        /** A `version` above [VERSION]: made by a newer Pocket XR. */
        data class TooNew(val version: Int) : Read
    }

    /** A [SpacePanel] in the file's units. */
    fun windowOf(p: SpacePanel): Window = Window(
        App(p.label, p.packageName.ifEmpty { null }, null),
        yawDeg = -p.yawDeg + 0f,
        pitchDeg = p.pitchDeg,
        distanceM = p.distanceM,
        widthDeg = p.widthDeg,
        heightDeg = p.heightDeg,
        curve = if (PanelCurve.isCurved(p)) p.curve else 0f,
    )

    /**
     * [presets] as the file's text. Only app windows go in: Pocket XR's own panels (no package) have nothing either
     * app can open. A preset left with no window is left out.
     */
    fun encode(presets: List<WindowPreset>, appVersion: String): String {
        val list = presets.mapNotNull { p ->
            val flat = if (p.layout.wrap != null) SpaceWrap.off(p.layout) else p.layout
            val windows = flat.panels.filter { it.isApp }.map(::windowOf)
            if (windows.isEmpty()) null else Preset(p.name, windows)
        }
        return write(Contents(MADE_BY, appVersion, list))
    }

    /** [contents] as pretty JSON, two spaces in, LF line ends. */
    fun write(contents: Contents): String = buildString {
        append("{\n")
        append("  \"format\": ").append(str(FORMAT)).append(",\n")
        append("  \"version\": ").append(VERSION).append(",\n")
        append("  \"made_by\": ").append(str(contents.madeBy)).append(",\n")
        append("  \"app_version\": ").append(str(contents.appVersion)).append(",\n")
        append("  \"presets\": [")
        contents.presets.forEachIndexed { i, p ->
            append(if (i == 0) "\n" else ",\n")
            append("    {\n")
            append("      \"name\": ").append(str(p.name)).append(",\n")
            append("      \"windows\": [")
            p.windows.forEachIndexed { j, w ->
                append(if (j == 0) "\n" else ",\n")
                append("        {\n")
                append("          \"app\": { \"name\": ").append(str(w.app.name))
                append(", \"android\": ").append(w.app.android?.let(::str) ?: "null")
                append(", \"deck\": ").append(w.app.deck?.let(::str) ?: "null").append(" },\n")
                append("          \"yaw_deg\": ").append(num(w.yawDeg)).append(",\n")
                append("          \"pitch_deg\": ").append(num(w.pitchDeg)).append(",\n")
                append("          \"distance_m\": ").append(num(w.distanceM)).append(",\n")
                append("          \"width_deg\": ").append(num(w.widthDeg)).append(",\n")
                append("          \"height_deg\": ").append(num(w.heightDeg)).append(",\n")
                append("          \"curve\": ").append(num(w.curve)).append("\n")
                append("        }")
            }
            append(if (p.windows.isEmpty()) "]\n" else "\n      ]\n")
            append("    }")
        }
        append(if (contents.presets.isEmpty()) "]\n" else "\n  ]\n")
        append("}\n")
    }

    /**
     * The file in [text]. Refuses one that is not JSON or whose `format` differs ([Read.NotLayouts]) and one with a
     * higher `version` ([Read.TooNew]). A window without an app name or id is dropped; a missing number takes the
     * Space's default.
     */
    fun read(text: String?): Read {
        val clean = text?.removePrefix("﻿")?.trim()
        if (clean.isNullOrEmpty()) return Read.NotLayouts
        val root = runCatching { JsonLite.parse(clean) }.getOrNull() as? Map<*, *> ?: return Read.NotLayouts
        if (root["format"] != FORMAT) return Read.NotLayouts
        val version = (root["version"] as? Double)?.takeIf { it.isFinite() } ?: return Read.NotLayouts
        if (version > VERSION) return Read.TooNew(version.toInt())
        val presets = (root["presets"] as? List<*>).orEmpty().mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            val windows = (m["windows"] as? List<*>).orEmpty().mapNotNull { w -> windowFrom(w as? Map<*, *>) }
            Preset((m["name"] as? String).orEmpty(), windows)
        }
        return Read.Ok(Contents((root["made_by"] as? String).orEmpty(), (root["app_version"] as? String).orEmpty(), presets))
    }

    private fun windowFrom(m: Map<*, *>?): Window? {
        if (m == null) return null
        val a = m["app"] as? Map<*, *> ?: return null
        val android = (a["android"] as? String)?.trim()?.ifEmpty { null }
        val deck = (a["deck"] as? String)?.trim()?.ifEmpty { null }
        val name = (a["name"] as? String)?.trim()?.ifEmpty { null } ?: android ?: deck ?: return null
        fun f(key: String, fallback: Float): Float = (m[key] as? Double)?.toFloat()?.takeIf { it.isFinite() } ?: fallback
        return Window(
            App(name, android, deck),
            yawDeg = f("yaw_deg", 0f),
            pitchDeg = f("pitch_deg", 0f),
            distanceM = f("distance_m", PanelLimits.DEFAULT_DISTANCE),
            widthDeg = f("width_deg", PanelLimits.DEFAULT_WIDTH_DEG),
            heightDeg = f("height_deg", PanelLimits.DEFAULT_HEIGHT_DEG),
            curve = f("curve", 0f),
        )
    }

    private fun num(v: Float): String = (if (v.isFinite()) v + 0f else 0f).toString()

    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}

/**
 * 1.4.5: Import layouts. Each window's app is found on this phone by its package, then by its name (case, spaces and
 * punctuation ignored), then through [ALIASES]; windows whose app isn't here are left out, and a preset with none
 * left is too. The rest are added at the top as when saving one, at most [WindowPresets.MAX] kept (the oldest
 * dropped), a name in use getting " (2)" and so on.
 */
object LayoutsImport {
    /**
     * Apps with different names on the phone and on the Deck (a Flatpak's name or window class). Short on purpose: only
     * names that really are the same app on both. Written as [normalise]d names.
     */
    val ALIASES: List<Set<String>> = listOf(
        setOf("chrome", "googlechrome"),
        setOf("firefox", "mozillafirefox", "firefoxwebbrowser"),
        setOf("edge", "microsoftedge"),
        setOf("brave", "bravebrowser", "bravewebbrowser"),
        setOf("vlc", "vlcmediaplayer"),
        setOf("telegram", "telegramdesktop"),
    )

    /** [name] lower case with only its letters and digits: "Google Chrome" is "googlechrome". */
    fun normalise(name: String): String = name.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    /** The app on this phone for [app] from [installed] (the launcher's apps), or null when it isn't here. */
    fun match(app: LayoutsFile.App, installed: List<AppEntry>): AppEntry? {
        app.android?.let { pkg -> installed.firstOrNull { it.packageName == pkg }?.let { return it } }
        val name = normalise(app.name)
        if (name.isEmpty()) return null
        installed.firstOrNull { normalise(it.label) == name }?.let { return it }
        val group = ALIASES.firstOrNull { name in it } ?: return null
        return installed.firstOrNull { normalise(it.label) in group }
    }

    /**
     * What an import did: [presets] is the whole new list to save. [added] layouts were added; [leftOutWindows] windows
     * were left out because their apps ([missingApps], by name, each once) aren't on this phone.
     */
    data class Result(val presets: List<WindowPreset>, val added: Int, val leftOutWindows: Int, val missingApps: List<String>)

    fun import(existing: List<WindowPreset>, contents: LayoutsFile.Contents, installed: List<AppEntry>, nowMs: Long): Result {
        var leftOut = 0
        val missing = LinkedHashSet<String>()
        val names = existing.map { it.name }.toMutableList()
        val ids = existing.map { it.id }.toMutableSet()
        var nextId = nowMs
        val imported = ArrayList<WindowPreset>()
        for (p in contents.presets) {
            val panels = ArrayList<SpacePanel>()
            for (w in p.windows) {
                val app = match(w.app, installed)
                if (app == null) {
                    leftOut++
                    missing += w.app.name
                    continue
                }
                // An app has one task, so one window ([SpaceLayout.showing]); and the Space holds MAX_PANELS.
                if (panels.any { it.packageName == app.packageName } || panels.size >= PanelLimits.MAX_PANELS) continue
                panels += panelOf(panels.size + 1, w, app)
            }
            if (panels.isEmpty()) continue
            while (nextId in ids) nextId++
            ids += nextId
            val name = uniqueName(p.name, names, existing + imported)
            names += name
            imported += WindowPreset(nextId, name, SpaceLayout(panels))
        }
        val kept = imported.take(WindowPresets.MAX)
        return Result((kept + existing).take(WindowPresets.MAX), kept.size, leftOut, missing.toList())
    }

    /** [w] as a window on this phone showing [app], its numbers kept within the Space's limits. */
    fun panelOf(id: Int, w: LayoutsFile.Window, app: AppEntry): SpacePanel = SpacePanel(
        id = id,
        label = app.label,
        packageName = app.packageName,
        activityName = app.activityName,
        yawDeg = wrapDegrees(-w.yawDeg),
        pitchDeg = w.pitchDeg.coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH),
        distanceM = w.distanceM.coerceIn(PanelLimits.MIN_DISTANCE, PanelLimits.MAX_DISTANCE),
        widthDeg = w.widthDeg.coerceIn(PanelLimits.DEPTH_MIN_SIZE_DEG, PanelLimits.MAX_WIDTH_DEG),
        heightDeg = w.heightDeg.coerceIn(PanelLimits.DEPTH_MIN_SIZE_DEG, PanelLimits.MAX_HEIGHT_DEG),
        curve = w.curve.coerceIn(0f, PanelCurve.MAX).takeIf { it > PanelCurve.FLAT_EPS } ?: 0f,
    ).withAppSize()

    /**
     * [wanted] cleaned as a rename does ([WindowPresets.rename]), or the next "Workspace N" when blank, with " (2)",
     * " (3)" and so on when [taken] has it already (case ignored). Always within [WindowPresets.MAX_NAME].
     */
    fun uniqueName(wanted: String, taken: List<String>, forNumbering: List<WindowPreset>): String {
        val clean = wanted.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(WindowPresets.MAX_NAME).trim()
        val base = clean.ifEmpty { WindowPresets.nextName(forNumbering) }
        fun used(n: String) = taken.any { it.equals(n, ignoreCase = true) }
        if (!used(base)) return base
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val name = base.take(WindowPresets.MAX_NAME - suffix.length).trim() + suffix
            if (!used(name)) return name
            n++
        }
    }
}
