package com.hayeco.flipspaces

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 1.3.3 (a tester: "is there a way to preserve settings? I've got the standalone apk set up exactly how I want"): every
 * Pocket XR setting to one file the user keeps, and back. Moving from the GitHub build to the Google Play one means
 * uninstalling (the two are signed differently), and Android's own backup is off for Pocket XR, so this is how the
 * layouts, presets, glasses and trackpad settings, hand calibration and Joy-Con fit come along.
 *
 * The file is JSON: each preferences file with its values' types, and the glasses calibration files. Left out: the PC
 * helper's pairing (a secret for the PC; pairing again is one tap) and anything that is not a setting (logs, the hand
 * models, which download again).
 */
object SettingsBackup {
    private const val TAG = "FlipSpaces/Backup"
    const val FORMAT = "pocket-xr-settings"
    const val VERSION = 1
    const val MIME = "application/json"

    /** Preferences that stay on this phone. */
    private val SKIP_PREFS = setOf("pcvr_helper", "updates")

    /** Android's own files among the preferences (android.app.ActivityThread.IDS and the like) are not Pocket XR's. */
    private fun ours(name: String) = name !in SKIP_PREFS && !name.startsWith("android.")

    /** Folders under the app's files that hold settings ([com.hayeco.flipspaces.spatial.GlassesCalibrationStore]). */
    private val FOLDERS = listOf("glasses-calibration")

    fun fileName(): String = "pocketxr-settings-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()) + ".json"

    /** Writes every setting to [uri]. The number of preferences files saved; throws when it can't write. */
    fun save(context: Context, uri: Uri): Int {
        val app = context.applicationContext
        val prefs = JSONObject()
        for (name in prefNames(app)) {
            if (!ours(name)) continue
            val values = JSONObject()
            for ((key, value) in app.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val entry = when (value) {
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
                    else -> null
                } ?: continue
                values.put(key, entry)
            }
            prefs.put(name, values)
        }
        val files = JSONObject()
        for (folder in FOLDERS) {
            val dir = File(app.filesDir, folder)
            dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                files.put("$folder/${f.name}", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            }
        }
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("app", version ?: "")
            .put("saved", System.currentTimeMillis())
            .put("prefs", prefs)
            .put("files", files)
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString(2).toByteArray()) }
            ?: error("the file could not be opened")
        Log.i(TAG, "settings saved: ${prefs.length()} preferences files, ${files.length()} other files, Pocket XR $version")
        return prefs.length()
    }

    /**
     * Puts the settings in [uri] in place of this phone's. Each preferences file in it is replaced as a whole; ones it
     * doesn't have are left alone. Throws with a plain reason when the file isn't a Pocket XR settings file.
     */
    fun restore(context: Context, uri: Uri): Int {
        val app = context.applicationContext
        val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("the file could not be opened")
        val root = runCatching { JSONObject(text) }.getOrNull()
        require(root != null && root.optString("format") == FORMAT) { "This isn't a Pocket XR settings file." }
        require(root.optInt("version") <= VERSION) { "This file is from a newer Pocket XR. Update Pocket XR first." }
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        for (name in prefs.keys()) {
            if (!ours(name) || name.contains('/') || name.contains("..")) continue
            val values = prefs.getJSONObject(name)
            val edit = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (key in values.keys()) {
                val e = values.getJSONObject(key)
                when (e.optString("t")) {
                    "b" -> edit.putBoolean(key, e.getBoolean("v"))
                    "i" -> edit.putInt(key, e.getInt("v"))
                    "l" -> edit.putLong(key, e.getLong("v"))
                    "f" -> edit.putFloat(key, e.getDouble("v").toFloat())
                    "s" -> edit.putString(key, e.getString("v"))
                    "ss" -> {
                        val a = e.getJSONArray("v")
                        edit.putStringSet(key, (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                }
            }
            edit.commit()
        }
        val files = root.optJSONObject("files") ?: JSONObject()
        for (path in files.keys()) {
            val folder = path.substringBefore('/')
            val name = path.substringAfter('/')
            if (folder !in FOLDERS || name.isEmpty() || name.contains('/') || name.contains("..")) continue
            val dir = File(app.filesDir, folder).apply { mkdirs() }
            File(dir, name).writeBytes(Base64.decode(files.getString(path), Base64.NO_WRAP))
        }
        Log.i(TAG, "settings restored from Pocket XR ${root.optString("app")}: ${prefs.length()} preferences files, ${files.length()} other files")
        return prefs.length()
    }

    /** This app's preferences files, by name. */
    private fun prefNames(app: Context): List<String> {
        val dir = File(app.applicationInfo.dataDir, "shared_prefs")
        return dir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.map { it.name.removeSuffix(".xml") }?.sorted().orEmpty()
    }
}
