package com.hayeco.flipspaces.core

/**
 * 1.0.1 (Mike: a "Buy me a beer" link, designed well). 1.1.15 (Mike: move it to the very top of the trackpad's Menu): one
 * slim row above the Menu's first card, in place of the card that sat at the bottom of Pocket XR Settings. No popup, no
 * reminder, no timer. Builds from GitHub show it; the Google Play build hides it (BuildConfig.PLAY_BUILD), as Play's
 * payments policy does not allow a link to tip the developer outside Play's own billing.
 */
object Support {
    const val KO_FI_URL = "https://ko-fi.com/mikehaye"

    /**
     * 1.6.26 (Mike: a one-time "tip" in-app product): the Play build's pill buys this one-time product through Play
     * Billing instead (app/src/playtip TipJar), consumed after each tip so it can be bought again.
     */
    const val TIP_PRODUCT = "tip_beer"

    /** The row's line, and the quiet name of where it goes at its end. */
    val LABEL: String get() = UiText.get("core_support_label", "Buy me a beer")
    const val WHERE = "Ko-fi"

    /** What TalkBack reads for the row. */
    val DESCRIPTION: String get() = UiText.get("core_support_description", "Buy me a beer on Ko-fi. Opens ko-fi.com in the browser.")

    /** 1.6.26: what TalkBack reads for the Play build's row, the tip through Google Play. */
    val DESCRIPTION_PLAY: String get() = UiText.get("core_support_description_play", "Buy me a beer: a one-time tip through Google Play.")

    fun description(playBuild: Boolean): String = if (playBuild) DESCRIPTION_PLAY else DESCRIPTION

    /** 1.6.26: the quiet word at the row's end: "Ko-fi", or in the Play build the tip's price once Play has said it. */
    fun where(playBuild: Boolean, price: String?): String = if (playBuild) price.orEmpty() else WHERE

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

    /** Whether the row shows: 1.6.26, in every build (the Play build's tips through Play, [TIP_PRODUCT]). */
    @Suppress("UNUSED_PARAMETER")
    fun shows(playBuild: Boolean): Boolean = true

    /** The Menu's first rows, top down. */
    enum class MenuTop { KO_FI, UPDATE }

    /** The tip row first, then the update row when there is a newer version ([update]). */
    fun menuTop(playBuild: Boolean, update: String?): List<MenuTop> =
        listOfNotNull(MenuTop.KO_FI.takeIf { shows(playBuild) }, MenuTop.UPDATE.takeIf { update != null })
}
