package com.hayeco.flipspaces.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.addon.IAddon
import com.hayeco.flipspaces.addon.IAddonCallback
import com.hayeco.flipspaces.phone.PhoneNotifications
import com.hayeco.flipspaces.pointer.PointerHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 1.1.0: the link to the Pocket XR add-on (the `addon` module), a second small app with the two services Google's
 * install check blocks on sideloaded apps in some countries: the pointer accessibility service and the notification
 * listener. Pocket XR itself declares neither, so it installs everywhere; the DeX cursor and the Notifications page
 * work once the add-on is installed and switched on.
 *
 * Binds the add-on's link service with BIND_AUTO_CREATE (both apps are signed with the same key: the ADDON_LINK
 * signature permission) and keeps it bound, so Android binds it again after the add-on restarts. [connect] is called
 * as the process starts and as each phone screen comes to the front, which also picks up an add-on installed since.
 */
object AddonLink {
    private const val TAG = "FlipSpacesAddon"
    const val PACKAGE = "com.hayeco.flipspaces.addon"

    /** The add-on's accessibility service and notification listener. */
    val POINTER = ComponentName(PACKAGE, "$PACKAGE.PointerAccess")
    val NOTES = ComponentName(PACKAGE, "$PACKAGE.NotesListener")
    private val LINK = ComponentName(PACKAGE, "$PACKAGE.LinkService")

    /** Where the README says how to get the add-on. */
    const val GET_URL = "https://github.com/pocket-xr/pocket-xr#the-add-on"

    /** 1.1.11: the add-on's Google Play page, for a copy of Pocket XR from Google Play. */
    const val PLAY_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"

    /** 1.1.11: the add-on's setup screen (its disclosures and consent, SetupActivity), and which one to show. */
    private val SETUP = ComponentName(PACKAGE, "$PACKAGE.SetupActivity")
    const val SETUP_POINTER = "pointer"
    const val SETUP_NOTES = "notes"

    /** Where to get the add-on: its Play page when Pocket XR came from Google Play, else the README. */
    fun getUrl(context: Context): String =
        if (com.hayeco.flipspaces.core.UpdateCheck.fromPlay(com.hayeco.flipspaces.UpdateChecker.installer(context, context.packageName))) PLAY_URL else GET_URL

    /**
     * 1.1.11 (Google Play): opens the add-on's disclosure for [which] ([SETUP_POINTER], [SETUP_NOTES]) on the phone's own
     * screen; after the user agrees there, it opens Android's page to switch the service on. False when the add-on is
     * not installed or is too old to have the screen.
     */
    fun openSetup(context: Context, which: String): Boolean {
        val intent = setupIntent(context, which) ?: return false
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
        return runCatching { context.startActivity(intent, options.toBundle()); true }
            .onFailure { Log.i(TAG, "the add-on's setup screen did not open: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }

    /** The intent [openSetup] starts, or null when no installed add-on has the screen. */
    fun setupIntent(context: Context, which: String): Intent? {
        val intent = Intent().setComponent(SETUP).putExtra("kind", which).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf { runCatching { context.packageManager.resolveActivity(it, 0) != null }.getOrDefault(false) }
    }

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var bound = false

    @Volatile private var remote: IAddon? = null

    private val _installed = MutableStateFlow(false)
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private val _pointerOn = MutableStateFlow(false)
    /** The add-on's pointer accessibility service is on and Pocket XR is linked to it. */
    val pointerOn: StateFlow<Boolean> = _pointerOn.asStateFlow()

    private val _notesOn = MutableStateFlow(false)
    /** The add-on's notification listener is connected and Pocket XR is linked to it. */
    val notesOn: StateFlow<Boolean> = _notesOn.asStateFlow()

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }
            .getOrElse { if (it is PackageManager.NameNotFoundException) false else throw it }

    /** Binds the add-on if it is installed and not bound yet. Main thread. */
    fun connect(context: Context) {
        val app = context.applicationContext
        appContext = app
        val there = isInstalled(app)
        _installed.value = there
        if (!there || bound) return
        bound = runCatching { app.bindService(Intent().setComponent(LINK), connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "the add-on could not be bound: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
        if (!bound) runCatching { app.unbindService(connection) }
        Log.i(TAG, if (bound) "binding the add-on" else "the add-on is installed but would not bind (signed with another key?)")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val r = IAddon.Stub.asInterface(service)
            remote = r
            runCatching { r.setCallback(callback) }
                .onSuccess { Log.i(TAG, "linked to the add-on (link version ${runCatching { r.version() }.getOrDefault(0)})") }
                .onFailure { Log.w(TAG, "the add-on did not take the callback: ${it.message}") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "the add-on went away; Android binds it again when it is back")
            lost()
        }

        override fun onBindingDied(name: ComponentName?) {
            // The add-on was updated or removed: bind afresh (or find it gone).
            Log.i(TAG, "the add-on's binding died; binding again")
            lost()
            val app = appContext ?: return
            runCatching { app.unbindService(this) }
            bound = false
            main.post { connect(app) }
        }
    }

    private fun lost() {
        remote = null
        main.post {
            pointer(false)
            notes(false)
        }
    }

    private val callback = object : IAddonCallback.Stub() {
        override fun pointer(on: Boolean) {
            main.post { this@AddonLink.pointer(on) }
        }

        override fun problem(message: String?) {
            main.post { PointerHub.report(message) }
        }

        override fun notesState(on: Boolean) {
            main.post { notes(on) }
        }

        override fun notes(rows: Bundle?) {
            PhoneNotifications.update(rows ?: Bundle())
        }
    }

    private fun pointer(on: Boolean) {
        if (_pointerOn.value == on) return
        _pointerOn.value = on
        val app = appContext ?: return
        if (on) PointerHub.addonOn(app) else PointerHub.addonOff()
    }

    private fun notes(on: Boolean) {
        _notesOn.value = on
        if (!on) PhoneNotifications.update(Bundle())
    }

    /** Runs [block] on the add-on; [fallback] when it is not linked or the call fails. Any thread. */
    fun <T> call(fallback: T, block: (IAddon) -> T): T {
        val r = remote ?: return fallback
        return try {
            block(r)
        } catch (e: Exception) {
            Log.w(TAG, "add-on call failed: ${e.javaClass.simpleName}: ${e.message}")
            fallback
        }
    }
}
