package com.hayeco.flipspaces.spatial

import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.spatial.ModeChoice
import com.hayeco.flipspaces.core.spatial.ModeDecision

/** One logcat line per mode decision, with its reasons: `adb logcat -s FlipSpaces/Mode`. */
object ModeLog {
    const val TAG = "FlipSpaces/Mode"

    fun decided(where: String, choice: ModeChoice, outcome: String) {
        Log.i(TAG, ModeDecision(where, choice, outcome).line())
    }
}
