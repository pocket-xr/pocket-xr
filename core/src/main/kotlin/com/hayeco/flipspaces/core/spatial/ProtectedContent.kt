package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.UiText

/**
 * Spots a panel that only ever shows black because its app protects its window (FLAG_SECURE)
 * or plays DRM video (0.4.1): SurfaceFlinger leaves secure layers out of a display that is not
 * secure, and the shell cannot make a secure one. The renderer reads back a few pixels of a
 * small mip level now and then and feeds their brightest value here.
 *
 * Black for [blackForMs] while the app keeps sending at least [minFrames] new frames is
 * protected; a frame with anything brighter than [blackLevel] starts over. A panel that shows
 * no new frames is only [blackLongEnough]: an idle app that may just be dark, which counts as
 * protected only if the window manager reports a FLAG_SECURE window for it ([SecureWindows]).
 */
class BlackFrameDetector(
    val blackLevel: Float = 8f / 255f,
    val blackForMs: Long = 4000,
    val minFrames: Int = 10,
) {
    private var blackSinceMs: Long? = null
    private var framesSinceBlack = 0

    /** True once the panel counts as protected; it stays so until a bright sample. */
    var protected: Boolean = false
        private set

    /** Black for at least [blackForMs], new frames or not. */
    var blackLongEnough: Boolean = false
        private set

    /** One sample: the brightest of the read back pixels (0..1), [newFrames] app frames since the last sample. */
    fun sample(maxLuma: Float, newFrames: Int, nowMs: Long): Boolean {
        if (maxLuma > blackLevel) {
            reset()
            return false
        }
        val since = blackSinceMs ?: nowMs.also { blackSinceMs = it }
        framesSinceBlack += newFrames
        blackLongEnough = nowMs - since >= blackForMs
        if (!protected && blackLongEnough && framesSinceBlack >= minFrames) protected = true
        return protected
    }

    fun reset() {
        blackSinceMs = null
        framesSinceBlack = 0
        protected = false
        blackLongEnough = false
    }
}

/** What the window manager says about secure windows on a panel's display. */
object SecureWindows {
    /**
     * Packages with a FLAG_SECURE window on [displayId], from `dumpsys window windows`. Each
     * window block starts with "Window #n Window{hash u0 package/activity}:", names its display
     * with "mDisplayId=N" and lists its flags on an "fl=" line, where FLAG_SECURE reads SECURE.
     */
    fun securePackages(dump: String, displayId: Int): Set<String> {
        val out = LinkedHashSet<String>()
        var pkg: String? = null
        var display: Int? = null
        var secure = false
        fun flush() {
            val p = pkg
            if (p != null && display == displayId && secure) out += p
        }
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            val header = WINDOW_HEADER.find(line)
            if (header != null) {
                flush()
                pkg = header.groupValues[1].substringBefore('/').takeIf { it.isNotEmpty() }
                display = null
                secure = false
                continue
            }
            DISPLAY_ID.find(line)?.let { display = it.groupValues[1].toIntOrNull() }
            if (line.startsWith("fl=") || line.contains(" fl=")) {
                val flags = line.substringAfter("fl=").split(' ', '|')
                if (flags.any { it == "SECURE" || it == "FLAG_SECURE" }) secure = true
            }
        }
        flush()
        return out
    }

    private val WINDOW_HEADER = Regex("""^Window #\d+ Window\{\S+ u\d+ ([^\s}]+)\}""")
    private val DISPLAY_ID = Regex("""\bmDisplayId=(\d+)""")
}

/** What a panel shows the user when its app looks protected (0.4.1). */
object ProtectedOffer {
    fun message(app: String, secure: Boolean): String =
        if (secure) {
            UiText.format("spatial_protected_secure", "%1\$s protects its screen (FLAG_SECURE), so it shows black on a panel. Pinch here to open it as a DeX window.", app)
        } else {
            UiText.format("spatial_protected_drm", "%1\$s shows only black here, likely protected video (DRM). Pinch here to open it as a DeX window.", app)
        }
}
