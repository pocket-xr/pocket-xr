package com.hayeco.flipspaces.platform

import android.content.Context
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.FreeformSetup
import com.hayeco.flipspaces.core.ShizukuStatus

/**
 * 1.5.4 (a vivo V2562 on Android 17 mirrors its screen to the glasses and hides the switches that would change that):
 * turns on Android's settings for apps on an external display ([FreeformSetup.all]) through Shizuku, as adb would, and
 * reads them back. A restart is needed before the window manager uses them; that is left to the person. Whether a
 * phone's maker honours them is up to the phone.
 */
object DesktopModeSetter {
    private const val TAG = "FlipSpaces/Desktop"

    sealed interface Result {
        data object NoShizuku : Result
        data object NeedsPermission : Result
        /** Every setting already had its value. */
        data object AlreadyOn : Result
        /** These were changed, and read back with their new value. */
        data class TurnedOn(val keys: List<String>) : Result
        data class Failed(val why: String) : Result
    }

    /** Blocking: call off the main thread. */
    fun turnOn(context: Context): Result {
        when (ShizukuBridge.status(context)) {
            ShizukuStatus.NOT_INSTALLED, ShizukuStatus.NOT_RUNNING -> return Result.NoShizuku
            ShizukuStatus.NO_PERMISSION -> {
                ShizukuBridge.requestPermission()
                return Result.NeedsPermission
            }
            ShizukuStatus.READY -> Unit
        }
        val before = read()
        val missing = FreeformSetup.toTurnOn(before)
        Log.i(TAG, "turn on through Shizuku: before ${before.entries.joinToString { "${it.key}=${it.value}" }}; to change: ${missing.joinToString { it.key }.ifEmpty { "none" }}")
        if (missing.isEmpty()) return Result.AlreadyOn
        for (s in missing) {
            val r = ShizukuBridge.shell(FreeformSetup.putCommand(s))
            if (r.exitCode != 0) {
                Log.w(TAG, "settings put global ${s.key} ${s.value}: exit ${r.exitCode}, ${r.output.trim()}")
                return Result.Failed("${s.key}: ${r.output.trim().ifEmpty { "exit ${r.exitCode}" }}")
            }
        }
        val after = read()
        val still = FreeformSetup.toTurnOn(after)
        Log.i(TAG, "turn on through Shizuku: after ${after.entries.joinToString { "${it.key}=${it.value}" }}")
        if (still.isNotEmpty()) return Result.Failed("${still.joinToString { it.key }} did not keep the new value")
        return Result.TurnedOn(missing.map { it.key })
    }

    private fun read(): Map<String, String?> = FreeformSetup.all.associate { s ->
        val r = ShizukuBridge.shell(FreeformSetup.getCommand(s))
        s.key to (if (r.exitCode == 0) r.output.trim() else null)
    }
}
