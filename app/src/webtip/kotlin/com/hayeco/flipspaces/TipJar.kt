package com.hayeco.flipspaces

import android.app.Activity
import android.content.Context

/**
 * 1.6.26: the tip through Google Play exists only in the Play build (src/playtip). Builds from GitHub tip on Ko-fi
 * ([SupportLink]) and carry no billing library; this keeps the calls the same in both.
 */
object TipJar {
    /** Play's price for the tip, or null (always, here). */
    val price: String? get() = null

    fun warm(context: Context) = Unit

    /** False: there is no Play tip here, so the caller opens Ko-fi. */
    fun tip(activity: Activity): Boolean = false
}
