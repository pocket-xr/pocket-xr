package com.hayeco.flipspaces.ui

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import com.hayeco.flipspaces.R
import java.util.Locale

/**
 * 0.5.68 (Mike: one bundled font, so the glasses and the phone match whatever the phone's own font is set to): Inter
 * (SIL Open Font License, docs/LICENSES.md) in the two weights [com.hayeco.flipspaces.core.Look] uses, Regular and
 * Medium. Loaded once at app start ([init]); before that, or if the font cannot load, the system sans-serif.
 *
 * Inter has no Korean, Japanese or Chinese letters, so in those languages ([systemFontLanguage]) the text drawn on the
 * glasses and the trackpad uses the phone's own font, which has them, in both weights.
 */
object LookType {
    private val systemRegular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val systemMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    @Volatile private var interRegular: Typeface? = null
    @Volatile private var interMedium: Typeface? = null

    val regular: Typeface
        get() = interRegular?.takeUnless { systemFontLanguage() } ?: systemRegular

    val medium: Typeface
        get() = interMedium?.takeUnless { systemFontLanguage() } ?: systemMedium

    fun init(context: Context) {
        try {
            interRegular = context.resources.getFont(R.font.inter_regular)
            interMedium = context.resources.getFont(R.font.inter_medium)
        } catch (e: Exception) {
            Log.w("FlipSpaces", "Inter did not load; the system sans-serif stays", e)
        }
    }

    /** True when the app shows Korean, Japanese or Chinese, which Inter cannot draw. */
    fun systemFontLanguage(locale: Locale = Locale.getDefault()): Boolean = locale.language in CJK

    private val CJK = setOf("ko", "ja", "zh")
}
