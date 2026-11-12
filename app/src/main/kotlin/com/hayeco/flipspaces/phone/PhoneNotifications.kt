package com.hayeco.flipspaces.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import com.hayeco.flipspaces.R
import androidx.core.app.NotificationManagerCompat
import com.hayeco.flipspaces.core.spatial.NoteRow
import com.hayeco.flipspaces.platform.AddonLink
import com.hayeco.flipspaces.platform.ShizukuBridge

/**
 * 0.5.46 (Mike: "a Notifications button so users can see their phone notifications in an extremely well designed way"):
 * the phone's notifications for the Space's Notifications page. The page asks for notification access with one press,
 * which turns it on through Shizuku (`cmd notification allow_listener`).
 *
 * 1.1.0: the listener is in the Pocket XR add-on ([AddonLink]), which sends the rows here as they change and
 * dismisses notifications when asked. Without the add-on the page says to install it.
 */
object PhoneNotifications {
    private const val TAG = "FlipSpacesNotes"

    @Volatile var rows: List<NoteRow> = emptyList()
        private set

    /** Bumped on every change, for the page to paint again. */
    @Volatile var version = 0L
        private set

    private val icons = HashMap<String, Bitmap?>()

    /** The add-on is installed and has notification access. */
    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(AddonLink.PACKAGE)

    /**
     * Notification access for the add-on; null when it was turned on, else what to tell the user.
     *
     * 1.1.11 (Google Play): the add-on's disclosure comes first, on the phone's screen, and the user switches access on
     * from there in Android's own page. Only an add-on too old to have that screen is still turned on through Shizuku
     * (blocking).
     */
    fun grant(context: Context): String? {
        if (!AddonLink.isInstalled(context)) return context.getString(R.string.sys_notes_install_addon, AddonLink.getUrl(context))
        if (AddonLink.openSetup(context, AddonLink.SETUP_NOTES)) return context.getString(R.string.sys_notes_finish_on_phone)
        val r = ShizukuBridge.shell(listOf("cmd", "notification", "allow_listener", AddonLink.NOTES.flattenToString()))
        Log.i(TAG, "notification access asked for through Shizuku: exit ${r.exitCode} ${r.output.take(200)}")
        return if (r.exitCode == 0) null else r.output.ifBlank { context.getString(R.string.sys_shizuku_not_running) }
    }

    fun packageOf(key: String): String? = rows.firstOrNull { it.key == key }?.packageName

    fun dismiss(key: String) = AddonLink.call(Unit) { it.dismissNote(key) }

    fun clearAll() = AddonLink.call(Unit) { it.clearNotes() }

    /** The add-on's rows, as [com.hayeco.flipspaces.addon.IAddonCallback.notes] describes them. Any thread. */
    fun update(b: Bundle) {
        val keys = b.getStringArray("keys").orEmpty()
        val apps = b.getStringArray("apps").orEmpty()
        val packages = b.getStringArray("packages").orEmpty()
        val titles = b.getStringArray("titles").orEmpty()
        val texts = b.getStringArray("texts").orEmpty()
        val posted = b.getLongArray("posted") ?: LongArray(0)
        val clearable = b.getBooleanArray("clearable") ?: BooleanArray(0)
        rows = keys.indices.mapNotNull { i ->
            val pkg = packages.getOrNull(i) ?: return@mapNotNull null
            NoteRow(keys[i], apps.getOrNull(i) ?: pkg, pkg, titles.getOrNull(i).orEmpty(), texts.getOrNull(i).orEmpty(), posted.getOrElse(i) { 0L }, clearable.getOrElse(i) { false })
        }
        version++
    }

    /** The app's icon, round, 96 px, cached (main or GL thread). */
    fun icon(context: Context, pkg: String): Bitmap? = synchronized(icons) {
        icons.getOrPut(pkg) {
            runCatching {
                val d = context.packageManager.getApplicationIcon(pkg)
                val size = 96
                val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                c.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), p)
                p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                d.draw(Canvas(layer))
                c.drawBitmap(layer, 0f, 0f, p)
                out
            }.getOrNull()
        }
    }
}
