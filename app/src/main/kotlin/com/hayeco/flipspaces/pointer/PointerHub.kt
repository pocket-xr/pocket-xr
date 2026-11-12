package com.hayeco.flipspaces.pointer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.hayeco.flipspaces.FlipSpacesApp
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.hands.HandProviderKind
import com.hayeco.flipspaces.core.hands.HandStatus
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.platform.AddonLink
import com.hayeco.flipspaces.spatial.SpaceSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where every input provider sends its pointer signals. It hands them, on the main thread, to
 * the pointer session, which owns the cursor and the touches on the glasses. Without it
 * nothing reaches the glasses outside the Space, and the setup screen says so.
 *
 * 1.1.0: the session runs while the add-on's pointer accessibility service is on ([AddonLink]).
 */
object PointerHub {
    private val main = Handler(Looper.getMainLooper())
    private var session: PointerSession? = null

    private val _connected = MutableStateFlow(false)
    /** The add-on's pointer service is on and linked. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _displayId = MutableStateFlow<Int?>(null)
    /** The glasses display the cursor is on, or null when there is none. */
    val displayId: StateFlow<Int?> = _displayId.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    /** The last thing that went wrong with injection or the overlay, word for word. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun submit(signal: PointerSignal, source: HandProviderKind) {
        // While the Space is on the glasses, the pointer is its 3D ray, not the DeX cursor.
        if (SpaceSession.active) {
            SpaceSession.submit(signal)
            return
        }
        main.post {
            val s = session
            if (s == null) {
                if (signal is PointerSignal.Down || (signal as? PointerSignal.ForHand)?.signal is PointerSignal.Down) report(FlipSpacesApp.text(R.string.sys_pointer_install_addon))
                return@post
            }
            s.onSignal(signal, source)
        }
    }

    fun handStatus(status: HandStatus?, source: HandProviderKind) {
        main.post { session?.onHandStatus(status, source) }
    }

    /** The add-on's pointer service came on. Main thread. */
    internal fun addonOn(context: Context) {
        if (session != null) return
        session = PointerSession(context).also { it.start() }
        _connected.value = true
    }

    /** It went off, or the add-on went away. Main thread. */
    internal fun addonOff() {
        session?.stop()
        session = null
        _connected.value = false
        _displayId.value = null
    }

    /** 0.5.10: the words of the reply when the service is off, which the phone prompt looks for. */
    const val SERVICE_OFF = "the Pocket XR pointer accessibility service is off"

    /**
     * 0.5.9: presses DeX's own full screen button on the Space's window on [displayId]
     * ([PointerSession.pressDexFullScreen]); [done] gets what happened, on the main thread.
     */
    fun pressDexFullScreen(displayId: Int, done: (String) -> Unit) {
        val s = session
        if (s == null) {
            main.post { done("not pressed: $SERVICE_OFF, so nothing may press DeX's buttons; install the Pocket XR add-on and turn it on in Settings, Accessibility") }
            return
        }
        Thread { val result = s.pressDexFullScreen(displayId); main.post { done(result) } }.start()
    }

    internal fun displayChanged(id: Int?) {
        _displayId.value = id
    }

    internal fun report(problem: String?) {
        _problem.value = problem
    }

    /** Whether the user has the add-on's pointer service switched on in Settings (it may not be bound yet). */
    fun enabledInSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { android.content.ComponentName.unflattenFromString(it) == AddonLink.POINTER }
    }
}
