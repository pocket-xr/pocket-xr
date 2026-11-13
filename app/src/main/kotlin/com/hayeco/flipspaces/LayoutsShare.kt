package com.hayeco.flipspaces

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.hayeco.flipspaces.core.AppCatalog
import com.hayeco.flipspaces.core.spatial.LayoutsFile
import com.hayeco.flipspaces.core.spatial.LayoutsImport
import com.hayeco.flipspaces.platform.InstalledApps
import com.hayeco.flipspaces.spatial.SpaceStore
import java.io.File

/**
 * 1.4.5 (a Reddit user asked whether saved layouts carry over to the Steam Deck): Share layouts and Import layouts,
 * the window presets as the JSON file Pocket XR for Steam Deck also reads and writes ([LayoutsFile]). Share hands the
 * file to Android's share sheet through the reports FileProvider and keeps a copy in Downloads (MediaStore, no
 * permission needed); Import reads a file the user picks and adds its presets ([LayoutsImport]).
 */
object LayoutsShare {
    private const val TAG = "FlipSpaces/Layouts"

    /** A layouts file is a few KB; anything far bigger is not one. */
    private const val MAX_BYTES = 1_000_000

    fun fileName(): String = LayoutsFile.fileName(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()))

    private fun version(app: Context): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    /**
     * Opens the share sheet with the presets' file, and saves a copy in Downloads. The copy's name in Downloads, or
     * null when there was none; throws with a plain reason when there is nothing to share or the file can't be made.
     */
    fun share(context: Context): String? {
        val app = context.applicationContext
        val presets = SpaceStore(app).presets
        val text = LayoutsFile.encode(presets, version(app))
        val count = (LayoutsFile.read(text) as? LayoutsFile.Read.Ok)?.contents?.presets?.size ?: 0
        require(count > 0) { app.getString(R.string.pad_layouts_nothing_to_share) }
        val name = fileName()
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Under files/reports/, the folder the reports FileProvider shares (res/xml/report_paths.xml).
        val dir = File(app.filesDir, "reports/layouts").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, name).apply { writeBytes(bytes) }
        val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".reports", file)
        val copy = runCatching { toDownloads(app, name, bytes) }
            .onFailure { Log.w(TAG, "layouts: no copy in Downloads: ${it.message}") }
            .getOrNull()
        val send = Intent(Intent.ACTION_SEND).setType(LayoutsFile.MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(name, uri) }
        context.startActivity(Intent.createChooser(send, app.getString(R.string.pad_share_layouts)).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        Log.i(TAG, "layouts shared: $count presets in $name, ${bytes.size} bytes${if (copy != null) ", a copy in Downloads as $copy" else ""}")
        return copy
    }

    /** [bytes] saved as Downloads/[name]; the name it got there (Android adds " (1)" to one in use). */
    private fun toDownloads(app: Context, name: String, bytes: ByteArray): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, LayoutsFile.MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val u = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore gave no place for the file")
        try {
            resolver.openOutputStream(u, "w")?.use { it.write(bytes) } ?: error("cannot write")
            resolver.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(u, null, null)
            throw e
        }
        return resolver.query(u, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: name
    }

    /** Adds the presets in [uri] and says what happened, in a sentence or two for the user. */
    fun import(context: Context, uri: Uri): String {
        val app = context.applicationContext
        val text = runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                // InputStream.readNBytes needs Android 13; this reads at most one byte past the limit.
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() <= MAX_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                if (out.size() > MAX_BYTES) null else out.toByteArray().decodeToString()
            }
        }.getOrNull()
        val contents = when (val read = LayoutsFile.read(text)) {
            is LayoutsFile.Read.Ok -> read.contents
            is LayoutsFile.Read.TooNew -> {
                Log.i(TAG, "layouts import refused: version ${read.version} file, this Pocket XR reads ${LayoutsFile.VERSION}")
                return app.getString(R.string.pad_layouts_newer_file)
            }
            LayoutsFile.Read.NotLayouts -> {
                Log.i(TAG, "layouts import refused: ${if (text == null) "the file could not be read" else "not a layouts file"}")
                return app.getString(if (text == null) R.string.pad_layouts_cannot_open else R.string.pad_layouts_not_layouts_file)
            }
        }
        if (contents.presets.none { it.windows.isNotEmpty() }) return app.getString(R.string.pad_layouts_empty_file)
        val installed = AppCatalog.prepare(InstalledApps(app).load(), app.packageName)
        val store = SpaceStore(app)
        val result = LayoutsImport.import(store.presets, contents, installed, System.currentTimeMillis())
        if (result.added > 0) store.presets = result.presets
        Log.i(
            TAG,
            "layouts imported from ${contents.madeBy} ${contents.appVersion}: ${result.added} added, ${result.leftOutWindows} windows left out" +
                if (result.missingApps.isEmpty()) "" else " (${result.missingApps.joinToString()})",
        )
        return summary(app, result)
    }

    /** "Added 3 layouts. 2 windows were left out: Messages and Phone aren't on this phone." */
    private fun summary(app: Context, r: LayoutsImport.Result): String {
        val res = app.resources
        val added = if (r.added > 0) res.getQuantityString(R.plurals.pad_layouts_added, r.added, r.added) else app.getString(R.string.pad_layouts_none_added)
        if (r.leftOutWindows == 0) return added
        val windows = res.getQuantityString(R.plurals.pad_layouts_windows_left_out, r.leftOutWindows, r.leftOutWindows)
        val apps = res.getQuantityString(R.plurals.pad_layouts_not_on_phone, r.missingApps.size, list(app, r.missingApps))
        return app.getString(R.string.pad_layouts_summary, added, app.getString(R.string.pad_layouts_left_out, windows, apps))
    }

    /** "Messages", "Messages and Phone", "Messages, Phone and Maps" in the phone's language. */
    private fun list(app: Context, names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> app.getString(
            R.string.pad_list_two,
            names.dropLast(1).reduce { a, b -> app.getString(R.string.pad_list_more, a, b) },
            names.last(),
        )
    }
}
