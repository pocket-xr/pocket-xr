package com.hayeco.flipspaces.core

/**
 * 1.0.1 (Mike: a "Buy me a beer" link, designed well). 1.1.15 (Mike: move it to the very top of the trackpad's Menu): one
 * slim row above the Menu's first card, in place of the card that sat at the bottom of Pocket XR Settings. No popup, no
 * reminder, no timer. Builds from GitHub show it; the Google Play build hides it (BuildConfig.PLAY_BUILD), as Play's
 * payments policy does not allow a link to tip the developer outside Play's own billing.
 */
object Support {
    const val KO_FI_URL = "https://ko-fi.com/mikehaye"

    /** The row's line, and the quiet name of where it goes at its end. */
    const val LABEL = "Buy me a beer"
    const val WHERE = "Ko-fi"

    /** What TalkBack reads for the row. */
    const val DESCRIPTION = "Buy me a beer on Ko-fi. Opens ko-fi.com in the browser."

    /**
     * 1.2.6 (Mike: the pill in the Pocket XR blue at a reduced opacity, not its own colour): the accent ([Look.ACCENT], the
     * window glow's blue) at 18 % for the fill, 40 % for the rim, the line in white and "Ko-fi" in the accent itself.
     * Over the Menu's page ([Look.PAGE]) the fill comes to #1A253B: white reads at about 15 to 1, the accent at about 5 to 1.
     */
    const val FILL_ALPHA = 0.18f
    const val RIM_ALPHA = 0.40f
    val FILL: Int get() = Look.alpha(Look.ACCENT, FILL_ALPHA)
    val RIM: Int get() = Look.alpha(Look.ACCENT, RIM_ALPHA)
    const val TEXT = Look.INK
    const val WHERE_TEXT = Look.ACCENT

    /** Whether the row shows: in every build but the Google Play one. */
    fun shows(playBuild: Boolean): Boolean = !playBuild

    /** The Menu's first rows, top down. */
    enum class MenuTop { KO_FI, UPDATE }

    /** The Ko-fi row first, then the update row when there is a newer version ([update]); nothing more in the Play build. */
    fun menuTop(playBuild: Boolean, update: String?): List<MenuTop> =
        listOfNotNull(MenuTop.KO_FI.takeIf { shows(playBuild) }, MenuTop.UPDATE.takeIf { update != null })
}
