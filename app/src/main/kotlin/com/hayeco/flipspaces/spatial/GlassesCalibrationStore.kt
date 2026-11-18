package com.hayeco.flipspaces.spatial

import android.content.Context
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.spatial.FactoryCalibration
import java.io.File

/**
 * 0.5.65 (research Y2): the glasses' factory calibration JSON (control request 10015), kept in the app's files per
 * serial, with the newest also as `last.json` so the fusion has it from the first sample of the next start, before the
 * control link has answered.
 */
object GlassesCalibrationStore {
    private const val TAG = "FlipSpaces/Calibration"
    private const val DIR = "glasses-calibration"
    private const val LAST = "last.json"

    /** 0.5.91: whose calibration `last.json` is (their serial), so other glasses never get it. */
    private const val LAST_SERIAL = "last.serial"

    /** 0.5.91: the model `last.json` came from ([com.hayeco.flipspaces.core.spatial.GlassesModel.key]). */
    private const val LAST_MODEL = "last.model"

    @Volatile private var cached: FactoryCalibration? = null
    @Volatile private var loaded = false

    /** 0.5.91: the serial [cached] belongs to; null for a calibration saved before 0.5.91 or with no serial. */
    @Volatile private var cachedSerial: String? = null

    private fun fileName(serial: String?): String = (serial?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() } ?: "unknown") + ".json"

    fun save(context: Context, serial: String?, json: String) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val name = fileName(serial)
        runCatching {
            File(dir, name).writeText(json)
            File(dir, LAST).writeText(json)
            if (serial != null) File(dir, LAST_SERIAL).writeText(serial) else File(dir, LAST_SERIAL).delete()
            File(dir, LAST_MODEL).writeText(SpaceStore(context).model.key)
        }.onFailure { Log.i(TAG, "calibration not saved: ${it.message}") }
        val parsed = runCatching { FactoryCalibration.parse(json) }.getOrNull()
        cached = parsed
        cachedSerial = serial
        loaded = true
        Log.i(TAG, parsed?.line() ?: "the glasses' calibration (${json.length} characters) has no gyro matrix or display mount")
    }

    /** The newest calibration saved, or null; read from the file once. */
    fun last(context: Context): FactoryCalibration? {
        if (!loaded) {
            loaded = true
            val dir = File(context.filesDir, DIR)
            val f = File(dir, LAST)
            cached = if (f.isFile) runCatching { FactoryCalibration.parse(f.readText()) }.getOrNull() else null
            cachedSerial = runCatching { File(dir, LAST_SERIAL).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
            // 0.5.91: saved from another model than the glasses the Space opened on last: not theirs (a calibration
            // saved before 0.5.91 has no model and was the Aura's, the only glasses it was read from).
            val savedModel = runCatching { File(dir, LAST_MODEL).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
                ?: com.hayeco.flipspaces.core.spatial.GlassesModel.AURA.key
            val now = SpaceStore(context).model
            if (cached != null && savedModel != now.key) {
                Log.i(TAG, "the saved factory calibration is from other glasses ($savedModel), not the ${now.label}'s: none used until these glasses send theirs")
                cached = null
                cachedSerial = null
            }
            Log.i(TAG, cached?.let { "loaded the saved " + it.line() + " (glasses ${cachedSerial ?: "not named"}, until the glasses on now say their serial)" } ?: "no saved factory calibration yet")
        }
        return cached
    }

    /** 0.5.91: for the Glasses report: whether a calibration is in use and whose. */
    // 1.1.4: with its numbers (gyro scale, display mount), so a report shows what the fusion was given.
    fun reportLine(context: Context): String = last(context)?.let { "read from the glasses${cachedSerial?.let { s -> " ending ${s.takeLast(4)}" } ?: ""} and in use; ${it.line()}" }
        ?: "none (the glasses did not send one, or have not been asked yet)"

    /**
     * 0.5.91 (Mike's One was given his Aura's calibration): the glasses on now answered [serial]. Their own saved
     * calibration is used, or none until theirs is read; true when what the fusion should use changed.
     */
    fun select(context: Context, serial: String): Boolean {
        last(context)
        if (cachedSerial == serial && cached != null) return false
        val f = File(File(context.filesDir, DIR), fileName(serial))
        val mine = if (f.isFile) runCatching { FactoryCalibration.parse(f.readText()) }.getOrNull() else null
        val changed = mine != cached
        cached = mine
        cachedSerial = serial
        Log.i(TAG, mine?.let { "glasses $serial: their saved " + it.line() } ?: "glasses $serial: no calibration saved for them yet; none used until they send theirs")
        return changed
    }
}
