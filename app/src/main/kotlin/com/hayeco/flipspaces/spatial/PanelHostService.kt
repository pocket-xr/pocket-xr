package com.hayeco.flipspaces.spatial

import android.hardware.display.VirtualDisplayConfig
import androidx.annotation.RequiresApi
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import com.hayeco.flipspaces.core.DesktopFullscreen
import com.hayeco.flipspaces.core.HoldStep
import com.hayeco.flipspaces.core.ShellLaunch
import com.hayeco.flipspaces.core.SpaceHold
import com.hayeco.flipspaces.core.SpaceTasks
import com.hayeco.flipspaces.core.TaskApi
import com.hayeco.flipspaces.core.TaskHoldApi
import com.hayeco.flipspaces.core.spatial.PanelDisplayEvent
import com.hayeco.flipspaces.core.spatial.PanelDisplays
import com.hayeco.flipspaces.core.spatial.SecureWindows
import com.hayeco.flipspaces.core.spatial.DexChrome
import com.hayeco.flipspaces.core.spatial.SurfaceFlingerLayers
import com.hayeco.flipspaces.core.spatial.OnScreen
import com.hayeco.flipspaces.core.spatial.SpaceVisibility
import com.hayeco.flipspaces.core.spatial.WindowDump
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The Shizuku user service behind app panels. Shizuku starts it in its own process with the
 * shell user's identity (uid 2000), which holds ADD_TRUSTED_DISPLAY, INJECT_EVENTS and
 * INTERNAL_SYSTEM_WINDOW (docs/SPATIAL.md 2.1 and 2.2). That is what lets it put any app on a
 * virtual display whose pixels Pocket XR draws as a panel.
 *
 * The framework's display, input and window services are reached through their binder
 * interfaces by reflection, the way scrcpy's server does; nothing here is copied from another
 * project. Every failure is kept word for word in [lastError].
 *
 * 0.4.1: any motion event (several pointers, mouse hover and wheel) and key events, top focus
 * for a display so typing reaches its app, Android's pause, resume and stop callbacks for each
 * display passed back through [IPanelEvents], and the FLAG_SECURE windows on a display.
 *
 * 0.4.2: the tasks on a display ([displayTasks]), so a panel whose app closed says so and Back
 * at an app's last activity can ask first.
 *
 * 0.4.7: the Space held in a root task of its own on the glasses ([holdSpace]), which Android
 * removes with the display, so the Space is destroyed with it instead of moved to the phone.
 * (0.4.6 set the display's remove content mode, which Android 15 does not read on removal.)
 *
 * 0.5.0: on DeX the Space's task sits in DeX's freeform "Desk" root task, which 0.4.7 took for
 * a hold; now the Space's own task is moved out into the full screen root task with its
 * freeform mode and bounds cleared, which takes away the caption bar and the DeX taskbar.
 *
 * 0.5.1: Android 17 has no createRootTask; the holding task is made with createTask and a
 * TaskCreationParams (full screen, not moved to the phone on removal, children kept from
 * overriding its windowing mode) and removed with deleteTask ([TaskHoldApi]).
 *
 * 0.5.7: the new task's leash is shown, SurfaceFlinger is asked whether the Space is really
 * composed on the glasses ([spaceOnScreen]), and the Space can go back into the DeX Desk it came
 * from ([windowSpace]) when the full screen task is not seen there.
 */
class PanelHostService : IPanelHost.Stub() {
    private val tag = "FlipSpaces/PanelHost"
    @Volatile private var error: String? = null

    /** The callback token DisplayManager keys each virtual display by. */
    private val tokens = ConcurrentHashMap<Int, Any>()

    private val displayManager: Any by lazy { service("display", "android.hardware.display.IDisplayManager") }
    private val inputManager: Any by lazy { service("input", "android.hardware.input.IInputManager") }
    private val windowManager: Any by lazy { service("window", "android.view.IWindowManager") }
    private val taskManager: Any by lazy { service("activity_task", "android.app.IActivityTaskManager") }
    private val windowOrganizer: Any by lazy {
        method(taskManager, "getWindowOrganizerController") { it.parameterTypes.isEmpty() }.invoke(taskManager)
            ?: error("No window organizer controller")
    }
    private val taskOrganizer: Any by lazy {
        method(windowOrganizer, "getTaskOrganizerController") { it.parameterTypes.isEmpty() }.invoke(windowOrganizer)
            ?: error("No task organizer controller")
    }

    /** The root task holding the Space on each display, by its WindowContainerToken (0.4.7). */
    private val holders = ConcurrentHashMap<Int, Any>()
    @Volatile private var events: IPanelEvents? = null
    private val callbackClass: Class<*> by lazy { Class.forName("android.hardware.display.IVirtualDisplayCallback") }

    override fun destroy() {
        tokens.keys.toList().forEach { releaseDisplay(it) }
        System.exit(0)
    }

    override fun lastError(): String? = error

    private val joyCons = JoyConReader { hand, readNs, buttons, stickX, stickY, gx, gy, gz, ax, ay, az ->
        runCatching { events?.onJoyCon(hand, readNs, buttons, stickX, stickY, gx, gy, gz, ax, ay, az) }
    }

    override fun startJoyCons(): String = joyCons.start().also { logI("Joy-Cons: $it") }

    override fun stopJoyCons() = joyCons.stop()

    override fun setEvents(events: IPanelEvents?) {
        this.events = events
        if (events != null) startRelay()
    }

    /**
     * The phone's panels by SurfaceControl's power mode, the same way screen mirroring tools turn a phone's screen off.
     * Only the phone's own panels: the glasses are a physical display too, so every external display is left out.
     * Android 14 moved the display tokens into services.jar's DisplayControl, so that is tried when the old way is gone.
     */
    override fun setPhonePanel(on: Boolean): String? = guardText {
        val mode = if (on) 2 else 0
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val setMode = surfaceControl.getMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType)
        val tokens = runCatching {
            listOf(surfaceControl.getMethod("getInternalDisplayToken").invoke(null) as android.os.IBinder)
        }.getOrElse { phoneDisplayTokens() }
        if (tokens.isEmpty()) error("no phone panels found")
        tokens.forEach { setMode.invoke(null, it, mode) }
        logI("phone panel ${if (on) "on" else "off"} (${tokens.size} displays)")
        null
    }

    /** DisplayControl, loaded once: a second class loader cannot load android_servers again. */
    private var displayControl: Class<*>? = null

    // This runs as the shell user, like the rest of this service. If Android refuses, the phone's panel just stays on.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun loadDisplayControl(): Class<*> {
        displayControl?.let { return it }
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod(
            "createClassLoader", String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
        )
        val loader = create.invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
        val control = loader.loadClass("com.android.server.display.DisplayControl")
        val load = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        load.isAccessible = true
        load.invoke(Runtime.getRuntime(), control, "android_servers")
        displayControl = control
        return control
    }

    private fun phoneDisplayTokens(): List<android.os.IBinder> {
        val control = loadDisplayControl()
        val external = externalDisplayIds()
        val ids = control.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
        val token = control.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
        return ids.filter { it !in external }.map { token.invoke(null, it) as android.os.IBinder }
    }

    /** The physical ids of external displays, from `cmd display get-displays`. Fails rather than guess. */
    private fun externalDisplayIds(): Set<Long> {
        val process = ProcessBuilder("cmd", "display", "get-displays").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        val shown = lines.filter { it.contains("DisplayInfo{") }
        if (shown.isEmpty()) error("no display list")
        return shown.filter { it.contains("type EXTERNAL") }.map {
            Regex("uniqueId \"local:(\\d+)\"").find(it)?.groupValues?.get(1)?.toLong() ?: error("an external display has no id")
        }.toSet()
    }

    /**
     * 0.5.25 (this process's lines, the layer checks among them, were only in logcat; the
     * app's recorder keeps the app's uid only): each line goes to logcat as before and into [relay], whose batches
     * go to the app over [IPanelEvents.onLogLines] once a second, a few lines at most, for its own log.
     */
    private val relay = com.hayeco.flipspaces.core.spatial.PanelLogRelay()
    @Volatile private var relaying = false

    private fun logI(msg: String) {
        Log.println(Log.INFO, tag, msg)
        relay.add(msg)
    }

    private fun logW(msg: String) {
        Log.println(Log.WARN, tag, msg)
        relay.add("warning: $msg")
    }

    /** 0.5.25: the relay's thread, started with the first callback binder; a batch only when there are lines. */
    @Synchronized
    private fun startRelay() {
        if (relaying) return
        relaying = true
        Thread({
            while (true) {
                Thread.sleep(com.hayeco.flipspaces.core.spatial.PanelLogRelay.EVERY_MS)
                val e = events ?: continue
                if (relay.pending == 0) continue
                val batch = relay.take()
                // A dead app binder: the lines stay lost, the next app's binder gets the next ones.
                runCatching { e.onLogLines(batch.toTypedArray()) }.onFailure { Log.w(tag, "log lines not passed to the app: ${describe(it)}") }
            }
        }, "panel-log-relay").apply { isDaemon = true; start() }
    }

    override fun createDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int, surface: Surface, refreshRate: Float): Int =
        create(name, width, height, dpi, flags, surface, refreshRate, asked = false)

    override fun createDisplayOnDevice(name: String, width: Int, height: Int, dpi: Int, flags: Int, surface: Surface, refreshRate: Float): Int =
        create(name, width, height, dpi, flags, surface, refreshRate, asked = true)

    /**
     * 0.5.52 (Mike: "work on getting the window displays their own display group so the phone can sleep without closing my
     * apps"): the shell's virtual device, made once a service run. Samsung's Android 16 puts every virtual display with no
     * group name in the phone's group 0 (LogicalDisplayMapper, DisplayGroupAllocator's content mode), ahead of
     * VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP; a display of a virtual device gets the device's own group instead. The device
     * needs a companion device association of the shell's with the app streaming profile, made here when there is none
     * (`cmd companiondevice associate`; the shell holds CREATE_VIRTUAL_DEVICE and ASSOCIATE_COMPANION_DEVICES).
     */
    @Volatile private var virtualDevice: Any? = null

    @Synchronized
    private fun virtualDevice(): Any {
        virtualDevice?.let { return it }
        val association = shellAssociation() ?: run {
            val made = run(listOf("cmd", "companiondevice", "associate", "0", SHELL_PACKAGE, DEVICE_MAC, STREAMING_PROFILE))
            logI("companion association for the shell: $made".take(300))
            shellAssociation() ?: error("no companion association for the shell after `cmd companiondevice associate`: ${made.take(200)}")
        }
        val vdm = service("virtualdevice", "android.companion.virtual.IVirtualDeviceManager")
        val builder = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder").getConstructor().newInstance()
        runCatching { builder.javaClass.getMethod("setName", String::class.java).invoke(builder, "Pocket XR windows") }
        val params = builder.javaClass.getMethod("build").invoke(builder)
        val attribution = android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL_PACKAGE).build()
        val create = method(vdm, "createVirtualDevice") { m -> m.parameterTypes.any { it == params.javaClass } }
        val args = create.parameterTypes.map { type ->
            when {
                type == IBinder::class.java -> Binder()
                type == android.content.AttributionSource::class.java -> attribution
                type == Int::class.javaPrimitiveType -> association
                type == params.javaClass -> params
                // The activity and sound listeners: binders that take every call and answer nothing.
                type.isInterface -> {
                    val b = Binder()
                    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, _ -> if (m.name == "asBinder") b else null }
                }
                else -> null
            }
        }
        val device = create.invoke(vdm, *args.toTypedArray()) ?: error("createVirtualDevice returned null")
        logI("virtual device made for the window displays (association $association, ${device.javaClass.name})")
        virtualDevice = device
        return device
    }

    /** 0.5.52: the shell's app streaming association's id from `cmd companiondevice list 0`, or null. */
    private fun shellAssociation(): Int? = run(listOf("cmd", "companiondevice", "list", "0")).lines()
        .firstOrNull { it.contains("mPackageName='$SHELL_PACKAGE'") && it.contains(STREAMING_PROFILE) && !it.contains("mRevoked=true") }
        ?.let { Regex("mId=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun create(name: String, width: Int, height: Int, dpi: Int, flags: Int, surface: Surface, refreshRate: Float, asked: Boolean): Int = guard(-1) {
        // 1.1.1 (an S20 FE on Android 11, a Retroid Nova on 13): panels below Android 14 too. The display goes on the
        // shell's virtual device only on 14 and up; below, it is a plain virtual display, as with the switch off.
        val onDevice = asked && Build.VERSION.SDK_INT >= 34
        if (asked && !onDevice) logI("$name: no virtual device on Android ${Build.VERSION.SDK_INT}; a plain virtual display instead")
        val (made, sizeResult) = if (Build.VERSION.SDK_INT >= 34) config(name, width, height, dpi, flags, surface, refreshRate)
        else legacyConfig(name, width, height, dpi, flags, surface, refreshRate)
        val config: Any = made
        val binder = Binder()
        // The display's id is only known once it exists; a callback before that has no panel yet.
        val displayId = java.util.concurrent.atomic.AtomicInteger(-1)
        val token = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "FlipSpacesPanel($name)"
                "onPaused" -> displayEvent(displayId.get(), PanelDisplayEvent.PAUSED)
                "onResumed" -> displayEvent(displayId.get(), PanelDisplayEvent.RESUMED)
                "onStopped" -> displayEvent(displayId.get(), PanelDisplayEvent.STOPPED)
                else -> null
            }
        }
        val id = if (onDevice) {
            // 0.5.52: on the shell's virtual device ([virtualDevice]): IVirtualDevice.createVirtualDisplay(config, callback).
            val device = virtualDevice()
            method(device, "createVirtualDisplay") { it.parameterTypes.size == 2 && it.parameterTypes[0] == config.javaClass }.invoke(device, config, token) as Int
        } else {
            val method = method(displayManager, "createVirtualDisplay") { it.parameterTypes.firstOrNull() == config.javaClass }
            val args = method.parameterTypes.map { type ->
                when {
                    type == config.javaClass -> config
                    type == callbackClass -> token
                    type == String::class.java -> SHELL_PACKAGE
                    type == Int::class.javaPrimitiveType -> 0
                    type == Boolean::class.javaPrimitiveType -> false
                    else -> null // IMediaProjection and anything newer
                }
            }
            method.invoke(displayManager, *args.toTypedArray()) as Int
        }
        if (id < 0) {
            error = "DisplayManager returned display id $id"
            return@guard -1
        }
        tokens[id] = token
        displayId.set(id)
        // Keep the keyboard on the phone: the Flip is the keyboard for every panel.
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                method(windowManager, "setDisplayImePolicy") { it.parameterTypes.size == 2 }
                    .invoke(windowManager, id, PanelDisplays.IME_POLICY_FALLBACK_DISPLAY)
            } else if (com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.trusted(flags)) {
                // 1.1.1: Android 11's form: no keyboard on this display, so it shows on the phone.
                method(windowManager, "setShouldShowIme") { it.parameterTypes.size == 2 }.invoke(windowManager, id, false)
            } else {
                // Android 11 refuses it for an untrusted display, and shows such a display's keyboard on the phone anyway.
                logI("display $id: the keyboard shows on the phone (Android ${Build.VERSION.SDK_INT}, an untrusted display)")
            }
        }.onFailure { logW("IME policy for display $id: ${describe(it)}") }
        // 0.5.22: Android's large screen rule on for the display, both ways.
        sizeResults[id] = sizeResult
        val orientation = ignoreOrientation(id)
        logI("created display $id ${width}x$height at $refreshRate Hz flags=0x${flags.toString(16)}${if (onDevice) " on the virtual device" else ""}; ignores activity size restrictions: $sizeResult; ignores orientation requests: $orientation")
        id
    }

    /** Android's callback for a panel display, passed on to Pocket XR. Binder thread. */
    private fun displayEvent(id: Int, event: PanelDisplayEvent): Any? {
        logI("display $id ${event.name.lowercase()}")
        if (id < 0) return null
        runCatching { events?.onDisplayEvent(id, event.ordinal) }.onFailure { logW("display $id ${event.name}: ${describe(it)}") }
        return null
    }

    /**
     * 0.5.22 (Wide view letterboxed on all 4 presses): each panel display is made
     * to ignore activity size restrictions where Android has it (SDK 36, trusted displays only), so Android's
     * large screen rule reaches every app on it ([com.hayeco.flipspaces.core.spatial.PanelDisplayCompat]). The
     * builder call is found by name, so a framework without it makes the display as before and says so.
     */
    @RequiresApi(34)
    private fun config(name: String, width: Int, height: Int, dpi: Int, flags: Int, surface: Surface, refreshRate: Float): Pair<VirtualDisplayConfig, String> {
        val builder = VirtualDisplayConfig.Builder(name, width, height, dpi)
            .setFlags(flags)
            .setSurface(surface)
            .setRequestedRefreshRate(if (refreshRate > 0f) refreshRate else 60f)
        val ask = com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.request(Build.VERSION.SDK_INT, flags)
        var result = if (!ask.ignoreActivitySizeRestrictions) {
            "not asked (Android ${Build.VERSION.SDK_INT}, ${if (com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.trusted(flags)) "trusted" else "not trusted"})"
        } else {
            runCatching {
                builder.javaClass.getMethod("setIgnoreActivitySizeRestrictions", Boolean::class.javaPrimitiveType).invoke(builder, true)
                SIZE_SET
            }.getOrElse { "refused: ${describe(it)}" }
        }
        val config = builder.build()
        // Read back what the config carries (VirtualDisplayConfig.isIgnoreActivitySizeRestrictions).
        if (result == SIZE_SET) {
            runCatching { config.javaClass.getMethod("isIgnoreActivitySizeRestrictions").invoke(config) as? Boolean }.getOrNull()?.let { on ->
                if (!on) result = "asked, but the config reads false"
            }
        }
        return config to result
    }

    /**
     * 1.1.1: [config] for Android 11 to 13. VirtualDisplayConfig and IDisplayManager.createVirtualDisplay(config, ...)
     * are there since Android 11 (public only from 13), so the builder is reached by name. Its requested refresh rate is
     * Android 14's, so these displays run at the rate Android gives them, and say so.
     */
    private fun legacyConfig(name: String, width: Int, height: Int, dpi: Int, flags: Int, surface: Surface, refreshRate: Float): Pair<Any, String> {
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderClass.getConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(name, width, height, dpi)
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        val config = builderClass.getMethod("build").invoke(builder) ?: error("VirtualDisplayConfig.Builder.build returned null")
        logI("$name: VirtualDisplayConfig made by name on Android ${Build.VERSION.SDK_INT}; the requested ${if (refreshRate > 0f) refreshRate else 60f} Hz is Android 14's, so it runs at Android's own rate")
        return config to "not asked (Android ${Build.VERSION.SDK_INT})"
    }

    /** 0.5.22: what [config] did with ignore activity size restrictions, per display. */
    private val sizeResults = ConcurrentHashMap<Int, String>()

    /** 0.5.22: each package's target SDK, read once a service run (`dumpsys package` is long). */
    private val targets = ConcurrentHashMap<String, Int>()

    /**
     * 0.5.22: the display ignores orientation requests (`IWindowManager.setIgnoreOrientationRequest`, what
     * `cmd window set-ignore-orientation-request -d <id> true` calls), then it is read back. Returns the
     * readback for the log.
     */
    private fun ignoreOrientation(displayId: Int): String {
        if (Build.VERSION.SDK_INT < com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.ORIENTATION_REQUEST_SDK) return "not on Android ${Build.VERSION.SDK_INT}"
        val set = runCatching {
            method(windowManager, "setIgnoreOrientationRequest") { it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
                .invoke(windowManager, displayId, true)
            null
        }.getOrElse { describe(it) }
        return when (val now = orientationSetting(displayId)) {
            com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.Setting.ON -> "on"
            else -> "${now.word}${set?.let { " (set: $it)" } ?: " after the call"}"
        }
    }

    /** 0.5.22: whether the display ignores orientation requests now (`IWindowManager.getIgnoreOrientationRequest`, else the shell's `cmd window`). */
    private fun orientationSetting(displayId: Int): com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.Setting {
        val direct = runCatching {
            method(windowManager, "getIgnoreOrientationRequest") { it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
                .invoke(windowManager, displayId) as? Boolean
        }.getOrNull()
        if (direct != null) return if (direct) com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.Setting.ON else com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.Setting.OFF
        val reply = runCatching { run(listOf("cmd", "window", "get-ignore-orientation-request", "-d", displayId.toString())) }.getOrNull()
        return com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.parseOrientationReply(reply)
    }

    /** 0.5.22: the display's state for the `appShape` header ([com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.State]). */
    private fun displayCompat(displayId: Int): com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.State {
        val size = when (sizeResults[displayId]) {
            SIZE_SET -> com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.Setting.ON
            null -> com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.Setting.UNKNOWN
            else -> com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.Setting.OFF
        }
        return com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.State(displayId, size, orientationSetting(displayId))
    }

    /** Runs [argv] as the shell and returns its output (10 s at most). */
    private fun run(argv: List<String>): String {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy()
            return "$output (did not finish in 10 s)"
        }
        return output
    }

    /**
     * 0.5.22: Wide view's per app fallback ([com.hayeco.flipspaces.core.spatial.WideViewOverride]). "read"
     * returns `dumpsys platform_compat`'s line for the change; the other actions run the one `am compat`
     * command they stand for, for [packageName] only. Anything else is refused.
     */
    override fun appCompat(packageName: String, action: String): String = try {
        if (action == "read") {
            val dump = dumpsys("platform_compat")
            dump.lineSequence().firstOrNull { it.contains("ChangeId(${com.hayeco.flipspaces.core.spatial.WideViewOverride.CHANGE_ID};") }
                ?: "unknown: ${com.hayeco.flipspaces.core.spatial.WideViewOverride.CHANGE_NAME} is not in dumpsys platform_compat"
        } else {
            val cmd = com.hayeco.flipspaces.core.spatial.WideViewOverride.command(action, packageName)
                ?: return "refused: not an override action or package ($action, $packageName)"
            val out = run(cmd.argv()).trim()
            logI("${cmd.argv().joinToString(" ")}: ${out.ifEmpty { "done" }}")
            "done: ${cmd.argv().joinToString(" ")}${if (out.isEmpty()) "" else ": $out"}"
        }
    } catch (e: Throwable) {
        "failed: ${describe(e)}"
    }

    override fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): String? = guardText {
        val token = tokens[displayId] ?: return@guardText "Display $displayId is not one of Pocket XR's panels"
        method(displayManager, "resizeVirtualDisplay") { it.parameterTypes.size == 4 }
            .invoke(displayManager, token, width, height, dpi)
        null
    }

    /**
     * 0.5.13: a new requested refresh rate for a panel display. VirtualDisplayConfig
     * sets it only at creation; this tries IDisplayManager's hidden calls that take the display's
     * callback token and a rate, by name, and says so when the platform has none.
     */
    override fun setDisplayRefreshRate(displayId: Int, refreshRate: Float): String? = guardText {
        val token = tokens[displayId] ?: return@guardText "Display $displayId is not one of Pocket XR's panels"
        val m = displayManager.javaClass.methods.firstOrNull { m ->
            m.name.contains("RefreshRate", ignoreCase = true) && m.name.contains("Virtual", ignoreCase = true) &&
                m.parameterTypes.size == 2 && m.parameterTypes[0] == callbackClass && m.parameterTypes[1] == Float::class.javaPrimitiveType
        } ?: return@guardText "Android ${Build.VERSION.SDK_INT} has no IDisplayManager call to change a virtual display's requested rate; it keeps the rate it was made with"
        m.invoke(displayManager, token, refreshRate)
        logI("display $displayId asks for $refreshRate Hz (${m.name})")
        null
    }

    /**
     * 0.5.14: the Space started as the shell with launch bounds of the
     * whole display. `am start` cannot pass bounds, so DeX's freeform desk applied the half-width
     * bounds it remembers for the package (a 960 x 1080 portrait window); ActivityOptions with
     * bounds came up 1920 x 1080 at once. IActivityTaskManager.startActivityAsUser is matched by
     * name and its arguments by type, as createDisplay does, since its signature moves between
     * Android versions.
     */
    override fun startSpace(displayId: Int, component: String, left: Int, top: Int, right: Int, bottom: Int): String? = guardText {
        val cn = android.content.ComponentName.unflattenFromString(component) ?: return@guardText "Not a component: $component"
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .setComponent(cn)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        if (right > left && bottom > top) options.setLaunchBounds(android.graphics.Rect(left, top, right, bottom))
        val bundle = options.toBundle()
        val m = taskManager.javaClass.methods.filter { it.name == "startActivityAsUser" && android.content.Intent::class.java in it.parameterTypes }
            .maxByOrNull { it.parameterTypes.size } ?: return@guardText "IActivityTaskManager has no startActivityAsUser on Android ${Build.VERSION.SDK_INT}"
        val types = m.parameterTypes
        val lastInt = types.indexOfLast { it == Int::class.javaPrimitiveType }
        // The user is the caller's: Pocket XR's own (uid / 100000, Android's per-user range).
        val user = Binder.getCallingUid() / 100_000
        var strings = 0
        val args = types.mapIndexed { i, type ->
            when {
                type == android.content.Intent::class.java -> intent
                type == android.os.Bundle::class.java -> bundle
                type == String::class.java -> if (strings++ == 0) SHELL_PACKAGE else null
                type == Int::class.javaPrimitiveType -> if (i == lastInt) user else 0
                type == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        val result = m.invoke(taskManager, *args.toTypedArray()) as? Int ?: 0
        logI("startSpace on display $displayId with launch bounds ${right - left}x${bottom - top}: ${m.name}(${types.size} args) returned $result")
        // START_SUCCESS 0, START_TASK_TO_FRONT 2, START_DELIVERED_TO_TOP 3; errors are negative.
        if (result < 0) "startActivityAsUser returned $result" else null
    }

    override fun setDisplaySurface(displayId: Int, surface: Surface?): String? = guardText {
        val token = tokens[displayId] ?: return@guardText "Display $displayId is not one of Pocket XR's panels"
        method(displayManager, "setVirtualDisplaySurface") { it.parameterTypes.size == 2 }
            .invoke(displayManager, token, surface)
        null
    }

    override fun releaseDisplay(displayId: Int) {
        sizeResults.remove(displayId)
        val token = tokens.remove(displayId) ?: return
        runCatching {
            method(displayManager, "releaseVirtualDisplay") { it.parameterTypes.size == 1 }.invoke(displayManager, token)
        }.onFailure { error = describe(it) }
    }

    override fun launch(displayId: Int, packageName: String, activityName: String): String? = launchNew(displayId, packageName, activityName, false)

    override fun launchNew(displayId: Int, packageName: String, activityName: String, newInstance: Boolean): String? = guardText {
        val process = ProcessBuilder(PanelDisplays.launchCommand(displayId, packageName, activityName, newInstance))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy()
            return@guardText "am start did not finish in 10 seconds. Output: $output"
        }
        ShellLaunch.failureIn(process.exitValue(), output)?.message
    }

    override fun refresh(displayId: Int, packageName: String, activityName: String): String? = guardText {
        val process = ProcessBuilder(PanelDisplays.refreshCommand(displayId, packageName, activityName))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy()
            return@guardText "am start did not finish in 10 seconds. Output: $output"
        }
        ShellLaunch.failureIn(process.exitValue(), output)?.message
    }

    override fun injectTouch(displayId: Int, action: Int, x: Float, y: Float, downTime: Long): String? =
        injectMotion(displayId, action, floatArrayOf(x), floatArrayOf(y), InputDevice.SOURCE_TOUCHSCREEN, 0f, 0f, downTime)

    override fun injectMotion(
        displayId: Int,
        action: Int,
        xs: FloatArray,
        ys: FloatArray,
        source: Int,
        vScroll: Float,
        hScroll: Float,
        downTime: Long,
    ): String? = guardText {
        val n = minOf(xs.size, ys.size)
        if (n == 0) return@guardText "No pointers"
        val now = SystemClock.uptimeMillis()
        val mouse = source == InputDevice.SOURCE_MOUSE
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = if (mouse) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                x = xs[i]
                y = ys[i]
                pressure = if (mouse) 0f else 1f
                size = 1f
                if (mouse) {
                    setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                    setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
                }
            }
        }
        val event = MotionEvent.obtain(if (downTime > 0) downTime else now, now, action, n, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
        try {
            inject(event, displayId, "motion ${MotionEvent.actionToString(action)}")
        } finally {
            event.recycle()
        }
    }

    override fun injectKey(displayId: Int, action: Int, keyCode: Int, metaState: Int, repeat: Int, downTime: Long): String? = guardText {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            if (downTime > 0) downTime else now, now, action, keyCode, repeat, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        inject(event, displayId, "key ${KeyEvent.keyCodeToString(keyCode)}")
    }

    /** Sets the event's display (hidden, by reflection) and injects it as the shell. */
    private fun inject(event: InputEvent, displayId: Int, what: String): String? {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        val inject = method(inputManager, "injectInputEvent") { it.parameterTypes.size == 2 && it.parameterTypes[0] == InputEvent::class.java }
        val ok = inject.invoke(inputManager, event, INJECT_ASYNC) as Boolean
        return if (ok) null else "InputManager refused the $what on display $displayId"
    }

    /**
     * Top focus for the display: IActivityTaskManager.focusTopTask moves its top task, and with
     * it the display (a display with its own focus may take top focus), to the front, so key
     * events and the IME follow. Shell holds MANAGE_ACTIVITY_TASKS for it.
     */
    override fun focusDisplay(displayId: Int): String? = guardText {
        runCatching {
            val top = taskManager.javaClass.methods.firstOrNull { it.name == "focusTopTask" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
            if (top != null) {
                top.invoke(taskManager, displayId)
            } else {
                // 1.1.1: Android 11 has no focusTopTask: setFocusedTask on the top task of the display's top stack.
                val stack = rootTaskInfos(displayId)?.firstOrNull() ?: error("no task on display $displayId")
                val taskId = (field(stack, "taskIds") as? IntArray)?.lastOrNull() ?: taskField(stack, "taskId") as? Int ?: error("no task id on display $displayId")
                method(taskManager, "setFocusedTask") { it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
                    .invoke(taskManager, taskId)
            }
        }.fold(onSuccess = { null }, onFailure = { "focus display $displayId: ${describe(it)}" })
    }

    /**
     * 0.4.7: on removal Android moves a display's standard tasks to the phone, but removes a
     * root task created by a task organizer with all its tasks
     * (`TaskDisplayArea.remove`). As the shell (MANAGE_ACTIVITY_TASKS) this creates such a
     * root task on the display (`ITaskOrganizerController.createRootTask`, fullscreen, kept
     * when its organizer goes) and moves the Space's task into it with a
     * WindowContainerTransaction; without [hold] it deletes that root task again.
     */
    override fun holdSpace(displayId: Int, hold: Boolean, spaceClass: String): String = try {
        if (hold) hold(displayId, spaceClass) else release(displayId, spaceClass)
    } catch (e: Throwable) {
        "failed: ${describe(e)}".also { error = it; logW(it) }
    }

    private fun hold(displayId: Int, spaceClass: String): String {
        val roots = rootTasks(displayId)
        val top = roots.firstOrNull { showsSpace(it, spaceClass) } ?: return "failed: no Space task on display $displayId"
        val topToken = field(top, "token") ?: return "failed: the Space's task has no token"
        val children = childTasks(topToken)
        val mode = windowingMode(top)
        // 0.5.0: a root task with children is a hold only when it is full screen; on DeX it is
        // the freeform Desk, which 0.4.7 took for its own hold (Mike's 0.4.8 log).
        val step = SpaceHold.decide(children.isNotEmpty(), mode, holders[displayId] == topToken)
        if (step == HoldStep.ALREADY_HELD) {
            holders[displayId] = topToken
            (field(top, "taskId") as? Int)?.let { created += it }
            // 0.5.2: asked again to bring the Space forward (instead of a second start): its task
            // to the top. 0.5.6: only when it is not on top already, and its own task loses a
            // freeform mode it kept inside the full screen holder, in the same transaction.
            val leaf = children.firstOrNull { showsSpace(it, spaceClass) }?.takeIf { windowingMode(it) == DesktopFullscreen.WINDOWING_MODE_FREEFORM }
            val reorder = SpaceTasks.needsReorder(topRootTaskId(displayId), field(top, "taskId") as? Int)
            val front = if (!reorder && leaf == null) {
                ", already in front"
            } else {
                runCatching {
                    val wct = Class.forName("android.window.WindowContainerTransaction").getConstructor().newInstance()
                    leaf?.let { field(it, "token") }?.let { clearFreeform(wct, it) }
                    if (reorder) {
                        method(wct, "reorder") { it.parameterTypes.size == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType }
                            .invoke(wct, topToken, true)
                    }
                    method(windowOrganizer, "applyTransaction") { it.parameterTypes.size == 1 }.invoke(windowOrganizer, wct)
                }.fold(
                    onSuccess = { (if (reorder) ", brought to the front" else ", already in front") + (if (leaf != null) ", its freeform mode cleared" else "") },
                    onFailure = { ", not brought to the front (${describe(it)})" },
                )
            }
            return "already held in root task ${field(top, "taskId")} (${modeName(mode)})$front${sweep(displayId, topToken)}"
        }
        // 0.5.2: a holder the Space was taken out of (back into the DeX Desk) is left empty: it goes.
        val stale = holders.remove(displayId)?.takeIf { it != topToken }
        val moving = if (step == HoldStep.MOVE_LEAF) {
            children.firstOrNull { showsSpace(it, spaceClass) } ?: children.firstOrNull()
                ?: return "failed: no Space task inside root task ${field(top, "taskId")}"
        } else {
            top
        }
        val movingToken = field(moving, "token") ?: return "failed: the Space's task has no token"
        // 0.5.7: the DeX Desk the Space came out of, for the way back ([windowSpace]).
        if (step == HoldStep.MOVE_LEAF) desks[displayId] = topToken
        val holderToken: Any
        val holderId: Any?
        var leash: SurfaceControl? = null
        when (taskApi) {
            TaskApi.CREATE_TASK -> {
                // 0.5.1, Android 17: createTask(TaskCreationParams) returns the new task itself.
                val info = try {
                    method(taskOrganizer, "createTask") { it.parameterTypes.size == 1 }.invoke(taskOrganizer, taskCreationParams(displayId))
                } catch (e: Throwable) {
                    return "failed: Android refused createTask for a full screen task on display $displayId: ${describe(e)}"
                } ?: return "failed: createTask returned nothing on display $displayId"
                val task = runCatching { info.javaClass.getMethod("getTaskInfo").invoke(info) }.getOrNull()
                    ?: return "failed: createTask's TaskAppearedInfo has no task info"
                holderToken = field(task, "token") ?: return "failed: the new task has no token"
                holderId = field(task, "taskId")
                leash = runCatching { info.javaClass.getMethod("getLeash").invoke(info) as? SurfaceControl }.getOrNull()
                logI("created full screen task $holderId on display $displayId (${modeName(windowingMode(task))}) with createTask")
            }
            TaskApi.CREATE_ROOT_TASK -> {
                val before = roots.map { field(it, "taskId") }.toSet()
                val create = method(taskOrganizer, "createRootTask") { it.parameterTypes.size >= 3 }
                val args = mutableListOf<Any?>(displayId, WINDOWING_MODE_FULLSCREEN, Binder())
                if (create.parameterTypes.size == 4) args += false // removeWithTaskOrganizer: kept when the organizer goes
                create.invoke(taskOrganizer, *args.toTypedArray())
                val holder = rootTasks(displayId).firstOrNull { field(it, "taskId") !in before }
                    ?: return "failed: the new root task did not show on display $displayId"
                holderToken = field(holder, "token") ?: return "failed: the new root task has no token"
                holderId = field(holder, "taskId")
            }
            TaskApi.NONE -> return "failed: ${TaskHoldApi.line(TaskApi.NONE, organizerMethods(), Build.VERSION.SDK_INT)}"
        }
        val wct = Class.forName("android.window.WindowContainerTransaction").getConstructor().newInstance()
        method(wct, "reparent") { it.parameterTypes.size == 3 && it.parameterTypes[2] == Boolean::class.javaPrimitiveType }
            .invoke(wct, movingToken, holderToken, true)
        // Out of DeX's freeform Desk (0.5.0), and 0.5.6 also for a Space task that was a freeform
        // root task of its own: no freeform mode or bounds of its own, so it fills the full screen
        // root task and draws in its window as in 0.5.4, not in the Presentation fallback.
        clearFreeform(wct, movingToken)
        // A new root task goes in under the display's home; the Space must stay in front.
        method(wct, "reorder") { it.parameterTypes.size == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType }
            .invoke(wct, holderToken, true)
        method(windowOrganizer, "applyTransaction") { it.parameterTypes.size == 1 }.invoke(windowOrganizer, wct)
        // The move is applied with Android's next transition; an empty root task is not left behind.
        val deadline = SystemClock.uptimeMillis() + HOLD_WAIT_MS
        while (childTasks(holderToken).isEmpty()) {
            if (SystemClock.uptimeMillis() > deadline) {
                deleteRootTask(holderToken)
                return "failed: task ${field(moving, "taskId")} did not move into root task $holderId; that root task was deleted"
            }
            Thread.sleep(50)
        }
        holders[displayId] = holderToken
        (holderId as? Int)?.let { created += it }
        val shown = showLeash(displayId, leash) + toTop(displayId, holderToken, holderId as? Int)
        // 0.5.23: the leash handle createTask gave this process is kept, so the release can let go of it.
        if (leash != null) leashes.put(displayId, leash)?.let { old -> if (old !== leash) runCatching { old.release() } }
        (holderId as? Int)?.let { heldIds[displayId] = it }
        val dropped = stale?.let { old ->
            if (runCatching { childTasks(old).isEmpty() && deleteRootTask(old) }.getOrDefault(false)) "; the empty task it held before was deleted" else ""
        } ?: ""
        val from = if (step == HoldStep.MOVE_LEAF) " out of root task ${field(top, "taskId")} (${modeName(mode)})" else ""
        // 0.5.1: say what the Space's task really is now; the desktop shell may still turn it freeform.
        val now = childTasks(holderToken).firstOrNull()?.let { modeName(windowingMode(it)) } ?: "mode unknown"
        return "task ${field(moving, "taskId")} moved$from into full screen root task $holderId (${taskApi.create}); the Space's task is now $now$shown$dropped${sweep(displayId, holderToken)}" +
            "; ${taskState(holderToken, "full screen task")}"
    }

    /** 0.5.7: the DeX Desk root task each display's Space was taken out of. */
    private val desks = ConcurrentHashMap<Int, Any>()

    /**
     * 0.5.23 (`Task=9344(FlipSpaces Space)#16797`, `9351` and `9371` were still in SurfaceFlinger
     * after their tasks were deleted): createTask hands this process a SurfaceControl for the task's leash. While
     * the handle lives, SurfaceFlinger keeps the layer, task or no task, and this service never let go of it.
     */
    private val leashes = ConcurrentHashMap<Int, SurfaceControl>()

    /** 0.5.23: the full screen task id made on each display, for the layer check after its release. */
    private val heldIds = ConcurrentHashMap<Int, Int>()

    /** 0.5.23: lets go of [displayId]'s leash handle (taking its layer off the screen first); what was done, for the reply. */
    private fun dropLeash(displayId: Int): String {
        val leash = leashes.remove(displayId) ?: return ""
        return runCatching {
            if (leash.isValid) SurfaceControl.Transaction().reparent(leash, null).apply()
            leash.release()
        }.fold(onSuccess = { "; its leash let go of" }, onFailure = { "; its leash could not be let go of (${describe(it)})" })
    }

    /**
     * 0.5.23: 5 s after a release, SurfaceFlinger's layer list is read for a `FlipSpaces Space` layer of a task this
     * service made and deleted; any left is logged, so the next test can tell whether letting go of the leash was enough.
     */
    private fun checkLayersLater(displayId: Int, taskIds: Set<Int>) {
        if (taskIds.isEmpty()) return
        // 0.5.24: read at 5 s and, if any is left, again at 15 s; the app holds the service until then.
        Thread({
            var slept = 0L
            for ((i, at) in SpaceTasks.LAYER_CHECKS_MS.withIndex()) {
                Thread.sleep(at - slept)
                slept = at
                val list = runCatching { dumpsys("SurfaceFlinger", "--list") }.getOrNull() ?: return@Thread
                val left = SpaceTasks.leftoverLayers(list, taskIds)
                val last = i == SpaceTasks.LAYER_CHECKS_MS.lastIndex
                logI(SpaceTasks.layerCheckLine(displayId, at, left, taskIds, last))
                if (left.isEmpty()) return@Thread
                if (last) removeLeftovers(displayId, list, left, taskIds)
            }
        }, "panel-layer-check").apply { isDaemon = true; start() }
    }

    /**
     * 0.5.25 (Task 9475 from display 114 and 9482 from 116 were still in SurfaceFlinger 15 s
     * after their release, and at a later dump): the last check's Space layers still left. A task of this
     * service's own that the window manager still has (named `FlipSpaces Space` in `dumpsys activity activities`, and
     * not a holder in use now) is removed, with IActivityTaskManager.removeTask as the shell (REMOVE_TASKS), and the
     * layer list read again [SpaceTasks.AFTER_REMOVAL_MS] later. A task already gone leaves a layer the service holds
     * no handle to: in vc45 both were offscreen (no parent, so composed on no display), as DeX's own Desk layers
     * were; that is said, with the count of those. No other task is ever touched ([SpaceTasks.leftoverPlan]).
     *
     * The other way out, moving the Space's task off the glasses before their display goes, has no
     * hook: Android removes a physical display before any callback, and both vc45 releases came after the removal
     * (`the Space closed after the glasses went`).
     */
    private fun removeLeftovers(displayId: Int, list: String, left: List<String>, taskIds: Set<Int>) {
        val named = runCatching { SpaceTasks.namedIn(dumpsys("activity", "activities")) }.getOrElse {
            logW("display $displayId released: the window manager's tasks could not be read (${describe(it)}), so no leftover Space task is removed")
            return
        }
        val plan = SpaceTasks.leftoverPlan(left, taskIds, named, inUse = heldIds.values.toSet())
        val removed = LinkedHashMap<Int, String>()
        for (id in plan.remove) {
            removed[id] = runCatching {
                val ok = method(taskManager, "removeTask") { it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
                    .invoke(taskManager, id) as? Boolean
                created -= id
                when (ok) {
                    true -> "removed (removeTask returned true)"
                    false -> "Android did not remove it (removeTask returned false)"
                    null -> "removeTask returned nothing"
                }
            }.getOrElse { "removeTask failed: ${describe(it)}" }
        }
        logI(SpaceTasks.leftoverLine(displayId, plan, removed, left, SpaceTasks.otherOffscreenTaskLayers(list)))
        if (plan.remove.isEmpty()) return
        Thread.sleep(SpaceTasks.AFTER_REMOVAL_MS)
        val again = runCatching { dumpsys("SurfaceFlinger", "--list") }.getOrNull() ?: return
        logI(SpaceTasks.afterRemovalLine(displayId, plan.remove, SpaceTasks.leftoverLayers(again, plan.remove.toSet()), SpaceTasks.AFTER_REMOVAL_MS))
    }

    /**
     * 0.5.19 (the held task was never seen; DeX's SecondaryLauncher composed
     * instead): the root tasks' z order right after the hold, top first, and when the Space's full
     * screen task is not on top, the top of the display: reordered to the top again and the DeX Desk
     * and DeX's own launcher tasks sent to the bottom, in one transaction, then the order again.
     */
    private fun toTop(displayId: Int, holderToken: Any, holderId: Int?): String = runCatching {
        val before = zOrder(displayId)
        val top = topRootTaskId(displayId)
        if (holderId == null || top == holderId) return@runCatching "; z order top first: $before"
        val wct = Class.forName("android.window.WindowContainerTransaction").getConstructor().newInstance()
        val reorder = method(wct, "reorder") { it.parameterTypes.size == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType }
        // Everything above the Space down, then the Space up.
        for (r in rootTasks(displayId)) {
            val token = field(r, "token") ?: continue
            if (token == holderToken) continue
            if (field(r, "isVisible") as? Boolean == false) continue
            reorder.invoke(wct, token, false)
        }
        reorder.invoke(wct, holderToken, true)
        method(windowOrganizer, "applyTransaction") { it.parameterTypes.size == 1 }.invoke(windowOrganizer, wct)
        Thread.sleep(50)
        val after = zOrder(displayId)
        logI("Space task $holderId was under task $top on display $displayId ($before): the others sent down, the Space to the top ($after)")
        "; z order was $before, the Space was not on top: sent to the top, now $after"
    }.getOrElse { "; z order check failed (${describe(it)})" }

    /** 0.5.19: the display's root tasks top first: id, mode, top activity, hidden ones marked. */
    private fun zOrder(displayId: Int): String = runCatching {
        val infos = rootTaskInfos(displayId) ?: return@runCatching "unknown"
        infos.filterNotNull().take(5).joinToString(" > ") { r ->
            val name = listOf("topActivity", "baseActivity").firstNotNullOfOrNull { (field(r, it) as? android.content.ComponentName)?.shortClassName } ?: "empty"
            "${taskField(r, "taskId")} ${modeName(windowingMode(r))} $name" + if (taskField(r, "isVisible") as? Boolean == false) " hidden" else ""
        }.ifEmpty { "none" }
    }.getOrElse { "unknown (${describe(it)})" }

    /**
     * 0.5.7: shows the new task's own surface (its leash, from createTask's TaskAppearedInfo) at
     * full alpha. The desktop shell organizes every task on a DeX display and sets its leash's
     * visibility in its transitions; a task the shell did not create may be left hidden, which
     * the window manager still counts as visible. Returns what was done for the reply.
     */
    private fun showLeash(displayId: Int, leash: SurfaceControl?): String {
        if (leash == null) return "; no leash to show (${taskApi.create} gave none)"
        return runCatching {
            SurfaceControl.Transaction().setVisibility(leash, true).setAlpha(leash, 1f).apply()
        }.fold(
            onSuccess = { "; its leash shown at alpha 1" },
            onFailure = { "; its leash could not be shown (${describe(it)})" },
        ).also { logI("full screen task on display $displayId$it") }
    }

    /** 0.5.7: what the window manager says of a task, for the log: id, mode, visible, display area. */
    private fun taskState(token: Any, what: String): String = runCatching {
        val info = rootTaskInfo(token) ?: return@runCatching "$what: no task info"
        val parts = listOfNotNull(
            "task ${field(info, "taskId")}",
            modeName(windowingMode(info)),
            (field(info, "isVisible") as? Boolean)?.let { "visible $it" },
            (field(info, "isVisibleRequested") as? Boolean)?.let { "visible requested $it" },
            (field(info, "displayAreaFeatureId") as? Int)?.let { "display area feature $it" },
        )
        "$what ${parts.joinToString(", ")}"
    }.getOrElse { "$what: ${describe(it)}" }

    private fun rootTaskInfo(token: Any): Any? {
        val displayId = holders.entries.firstOrNull { it.value == token }?.key
        val displays = listOfNotNull(displayId) + holders.keys
        for (d in displays.distinct()) rootTasks(d).firstOrNull { field(it, "token") == token }?.let { return it }
        return null
    }

    /**
     * 0.5.7: SurfaceFlinger's own answer to "is the Space on the glasses": its HWC layer table for
     * the display's physical id ([SurfaceFlingerLayers.check]), then the root tasks and the task
     * layers it lists for them, so the log shows what hid it if it is not composed.
     */
    override fun spaceOnScreen(displayId: Int, spaceClass: String): String = try {
        val dump = runCatching { dumpsys("SurfaceFlinger") }.getOrNull()
        val physical = SurfaceFlingerLayers.physicalIdIn(describeDisplay(displayId))
        val pkg = spaceClass.substringBeforeLast(".spatial.").ifEmpty { spaceClass.substringBeforeLast('.') }
        val activity = spaceClass.substringAfterLast('.')
        val sf = SurfaceFlingerLayers.check(dump, pkg, activity, physical)
        // 0.5.10: One UI 8's dump can have no layer table; the window manager answers then, and
        // only positive evidence ever says "not seen" (SpaceVisibility).
        val windows = if (sf is OnScreen.Unknown) WindowDump.parse(runCatching { dumpsys("window", "windows") }.getOrNull()) else emptyList()
        val answer = SpaceVisibility.judge(sf, windows, displayId, pkg, activity)
        val roots = runCatching { rootTasks(displayId) }.getOrDefault(emptyList())
        val ids = roots.mapNotNull { field(it, "taskId") as? Int }
        val tasks = roots.take(4).joinToString { r ->
            val name = listOf("topActivity", "baseActivity").firstNotNullOfOrNull { (field(r, it) as? android.content.ComponentName)?.shortClassName } ?: "empty"
            "${field(r, "taskId")} ${modeName(windowingMode(r))} $name" + ((field(r, "isVisible") as? Boolean)?.let { if (it) " visible" else " hidden" } ?: "")
        }
        val layers = SurfaceFlingerLayers.taskLines(dump, ids.take(3), perTask = 1)
        // 0.5.9: whether DeX's taskbar, title bar or border is composed there too (DeX full screen check).
        // 0.5.10: "unknown" when nothing could tell; 0.5.9 said "none" for a dump with no table.
        val tables = dump?.let { SurfaceFlingerLayers.composedLayers(it) }.orEmpty()
        val composed = if (tables.isEmpty()) null else physical?.let { tables[it] } ?: tables.values.flatten()
        val chrome = composed?.let { DexChrome.of(it).line() }
            ?: SpaceVisibility.chrome(windows, displayId, pkg, activity)?.let { "${it.line()} (window manager)" }
            ?: DexChrome.UNKNOWN
        answer.line + DexChrome.REPLY_PREFIX + chrome + "; physical display ${physical ?: "unknown"}; root tasks top first: ${tasks.ifEmpty { "none" }}" +
            if (layers.isEmpty()) "" else "; task layers: ${layers.joinToString(" / ")}"
    } catch (e: Throwable) {
        "unknown: ${describe(e)}".also { error = it }
    }

    /**
     * 0.5.19: the Space's SurfaceView layer by its full name from
     * `dumpsys SurfaceFlinger --list`, then its `--latency` table, as SurfaceFlinger keeps it (the
     * last 128 frames). Runs off the render thread, in the shell's process.
     */
    override fun presentTimes(spaceClass: String): String = try {
        val pkg = spaceClass.substringBeforeLast(".spatial.").ifEmpty { spaceClass.substringBeforeLast('.') }
        val activity = spaceClass.substringAfterLast('.')
        val list = dumpsys("SurfaceFlinger", "--list")
        val line = list.lines().map { it.trim() }.lastOrNull { it.contains("SurfaceView[") && it.contains("(BLAST)") && SurfaceFlingerLayers.isSpace(it, pkg, activity) }
            ?: return "unknown: no SurfaceView layer of the Space in dumpsys SurfaceFlinger --list"
        // 0.5.20: this Android prints `RequestedLayerState{<name> parentId=N}`;
        // `--latency` wants the bare name (the whole line gave only the period line, 0 of 43 tables).
        val layer = SurfaceFlingerLayers.latencyName(line)
        "layer $layer\n" + dumpsys("SurfaceFlinger", "--latency", layer)
    } catch (e: Throwable) {
        "unknown: ${describe(e)}"
    }

    /** 0.5.19: the first exit record of [packageName] (`dumpsys activity exit-info`), trimmed. */
    override fun appShape(displayId: Int): String = try {
        val text = dumpsys("activity", "activities")
        val start = text.indexOf("Display #$displayId ")
        if (start < 0) {
            "unknown: display $displayId is not in dumpsys activity activities"
        } else {
            val next = text.indexOf("\nDisplay #", start + 1)
            val section = text.substring(start, if (next < 0) text.length else next)
            // 0.5.22: the display's ignore settings and the top app's target SDK, as header lines.
            val pkg = com.hayeco.flipspaces.core.spatial.AppShape.parse(section, displayId)?.packageName
            val target = pkg?.let { p ->
                targets[p] ?: runCatching { com.hayeco.flipspaces.core.spatial.AppTarget.parsePackageDump(dumpsys("package", p)) }.getOrNull()?.also { targets[p] = it }
            }
            buildString {
                append(com.hayeco.flipspaces.core.spatial.PanelDisplayCompat.header(displayCompat(displayId))).append('\n')
                if (pkg != null) append(com.hayeco.flipspaces.core.spatial.AppTarget.header(pkg, target)).append('\n')
                append(section)
            }
        }
    } catch (e: Throwable) {
        "unknown: ${describe(e)}"
    }

    override fun exitReason(packageName: String): String = try {
        val text = dumpsys("activity", "exit-info", packageName)
        com.hayeco.flipspaces.core.spatial.AppExit.parse(text)?.line() ?: "unknown: no exit record for $packageName"
    } catch (e: Throwable) {
        "unknown: ${describe(e)}"
    }

    /**
     * 0.5.7: the way back from a full screen task the glasses do not show: the Space's task goes
     * back into the DeX Desk it was taken out of (else to the display, as a root task), freeform
     * at the display's size, on top; then the empty full screen task is deleted. That is where DeX
     * itself puts the Space when Pocket XR is opened again, the state Mike sees.
     */
    override fun windowSpace(displayId: Int, spaceClass: String): String = try {
        toWindow(displayId, spaceClass)
    } catch (e: Throwable) {
        "failed: ${describe(e)}".also { error = it; logW(it) }
    }

    private fun toWindow(displayId: Int, spaceClass: String): String {
        val roots = rootTasks(displayId)
        val holder = holders[displayId]
            ?: roots.firstOrNull { showsSpace(it, spaceClass) && windowingMode(it) == WINDOWING_MODE_FULLSCREEN }?.let { field(it, "token") }
            ?: return "failed: the Space is not in a full screen task on display $displayId"
        val holderId = roots.firstOrNull { field(it, "token") == holder }?.let { field(it, "taskId") }
        val space = childTasks(holder).let { c -> c.firstOrNull { showsSpace(it, spaceClass) } ?: c.firstOrNull() }
            ?: return "failed: full screen task $holderId holds no task"
        val spaceToken = field(space, "token") ?: return "failed: the Space's task has no token"
        val desk = desks[displayId]?.takeIf { d -> roots.any { field(it, "token") == d } }
        val deskId = desk?.let { d -> roots.firstOrNull { field(it, "token") == d }?.let { field(it, "taskId") } }
        val size = displaySize(displayId)
        val wct = Class.forName("android.window.WindowContainerTransaction").getConstructor().newInstance()
        // A null parent puts a task straight on the display as a root task of its own.
        method(wct, "reparent") { it.parameterTypes.size == 3 && it.parameterTypes[2] == Boolean::class.javaPrimitiveType }
            .invoke(wct, spaceToken, desk, true)
        method(wct, "setWindowingMode") { it.parameterTypes.size == 2 && it.parameterTypes[1] == Int::class.javaPrimitiveType }
            .invoke(wct, spaceToken, DesktopFullscreen.WINDOWING_MODE_FREEFORM)
        size?.let { (w, h) ->
            runCatching {
                method(wct, "setBounds") { it.parameterTypes.size == 2 && it.parameterTypes[1] == android.graphics.Rect::class.java }
                    .invoke(wct, spaceToken, android.graphics.Rect(0, 0, w, h))
            }
        }
        if (desk != null) {
            method(wct, "reorder") { it.parameterTypes.size == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType }
                .invoke(wct, desk, true)
        }
        method(windowOrganizer, "applyTransaction") { it.parameterTypes.size == 1 }.invoke(windowOrganizer, wct)
        val deadline = SystemClock.uptimeMillis() + HOLD_WAIT_MS
        while (childTasks(holder).isNotEmpty()) {
            if (SystemClock.uptimeMillis() > deadline) return "failed: task ${field(space, "taskId")} did not leave full screen task $holderId"
            Thread.sleep(50)
        }
        holders.remove(displayId)
        heldIds.remove(displayId)
        val deleted = runCatching { deleteRootTask(holder) }.getOrDefault(false)
        if (deleted) (holderId as? Int)?.let { created -= it }
        val where = if (deskId != null) "back into the DeX Desk (root task $deskId)" else "onto the display as a root task of its own"
        val bounds = size?.let { " at ${it.first}x${it.second}" } ?: ""
        return "task ${field(space, "taskId")} moved out of full screen task $holderId $where, freeform$bounds; " +
            (if (deleted) "the full screen task was deleted" else "Android did not delete the full screen task") + dropLeash(displayId)
    }

    /** The display's logical size from DisplayManager's DisplayInfo, or null. */
    private fun displaySize(displayId: Int): Pair<Int, Int>? = runCatching {
        val info = method(displayManager, "getDisplayInfo") { it.parameterTypes.size == 1 }.invoke(displayManager, displayId) ?: return@runCatching null
        val w = info.javaClass.getField("logicalWidth").getInt(info)
        val h = info.javaClass.getField("logicalHeight").getInt(info)
        if (w > 0 && h > 0) w to h else null
    }.getOrNull()

    /** 0.5.6: holding tasks this service created, so a leftover is known even without its name in the dump. */
    private val created = ConcurrentHashMap.newKeySet<Int>()

    /** Clears [token]'s own windowing mode and bounds in [wct], so it takes its parent's (full screen). */
    private fun clearFreeform(wct: Any, token: Any) {
        runCatching {
            method(wct, "setWindowingMode") { it.parameterTypes.size == 2 && it.parameterTypes[1] == Int::class.javaPrimitiveType }
                .invoke(wct, token, WINDOWING_MODE_UNDEFINED)
        }.onFailure { logW("setWindowingMode for the Space: ${describe(it)}") }
        runCatching {
            method(wct, "setBounds") { it.parameterTypes.size == 2 && it.parameterTypes[1] == android.graphics.Rect::class.java }
                .invoke(wct, token, android.graphics.Rect())
        }.onFailure { logW("setBounds for the Space: ${describe(it)}") }
    }

    /** The top root task's id on [displayId] (getAllRootTaskInfosOnDisplay lists the top first), or null. */
    private fun topRootTaskId(displayId: Int): Int? = runCatching {
        val infos = rootTaskInfos(displayId)
        infos?.firstOrNull()?.let { taskField(it, "taskId") as? Int }
    }.getOrNull()

    /**
     * 0.5.6: deletes empty "FlipSpaces Space" tasks on [displayId] other than [keepToken]'s, so
     * one Space task is left ([SpaceTasks.leftovers]). Returns what it did for the reply.
     */
    private fun sweep(displayId: Int, keepToken: Any?): String = runCatching {
        val named = SpaceTasks.namedIn(dumpsys("activity", "activities"))
        val roots = rootTasks(displayId)
        val keepId = roots.firstOrNull { field(it, "token") == keepToken }?.let { field(it, "taskId") as? Int }
        val facts = roots.mapNotNull { r ->
            val id = field(r, "taskId") as? Int ?: return@mapNotNull null
            val token = field(r, "token") ?: return@mapNotNull null
            val top = listOf("topActivity", "baseActivity").firstNotNullOfOrNull { (field(r, it) as? android.content.ComponentName)?.flattenToShortString() }
            SpaceTasks.RootTask(id, childTasks(token).size, field(r, "numActivities") as? Int, top, id in named || id in created) to token
        }
        val gone = SpaceTasks.leftovers(facts.map { it.first }, keepId).mapNotNull { id ->
            val token = facts.first { it.first.taskId == id }.second
            val ok = runCatching { deleteRootTask(token) }.getOrDefault(false)
            logI("leftover empty Space task $id on display $displayId: ${if (ok) "deleted" else "Android did not delete it"}")
            if (ok) {
                created -= id
                id
            } else {
                null
            }
        }
        if (gone.isEmpty()) "" else "; leftover empty Space task ${gone.joinToString()} deleted"
    }.getOrElse { "; leftover check failed (${describe(it)})" }

    private fun dumpsys(vararg args: String): String {
        val process = ProcessBuilder(listOf("dumpsys") + args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(5, TimeUnit.SECONDS)
        return text
    }

    /** The task organizer's methods as (name, parameter count), for [TaskHoldApi]. */
    private fun organizerMethods(): List<Pair<String, Int>> = taskOrganizer.javaClass.methods.map { it.name to it.parameterTypes.size }

    /** 0.5.1: which create and delete calls this Android has, picked once and logged. */
    private val taskApi: TaskApi by lazy {
        val methods = organizerMethods()
        TaskHoldApi.pick(methods, Build.VERSION.SDK_INT).also { logI("Space hold: ${TaskHoldApi.line(it, methods, Build.VERSION.SDK_INT)}") }
    }

    /**
     * Android 17's TaskCreationParams for the Space's own task on [displayId], built by reflection
     * (hidden): full screen and on top, with a TaskPropertiesRequest that removes the task with the
     * display instead of moving it to the phone (0.4.7's unplug hold) and keeps the desktop shell
     * from making its children freeform again.
     */
    private fun taskCreationParams(displayId: Int): Any {
        val props = Class.forName("android.window.TaskPropertiesRequest").getConstructor().newInstance()
        call(props, "setReparentOnDisplayRemoval", false)
        call(props, "setDisallowOverrideWindowingModeForChildren", true)
        val builder = Class.forName("android.window.TaskCreationParams\$Builder").getConstructor().newInstance()
        call(builder, "setDisplayId", displayId)
        call(builder, "setWindowingMode", WINDOWING_MODE_FULLSCREEN)
        call(builder, "setOnTop", true)
        runCatching { call(builder, "setName", "FlipSpaces Space") }
        call(builder, "setTaskPropertiesRequest", props)
        return builder.javaClass.getMethod("build").invoke(builder) ?: error("TaskCreationParams.Builder.build returned null")
    }

    /** Calls the one-argument method [name] on [target] with [value], matching primitives. */
    private fun call(target: Any, name: String, value: Any) {
        method(target, name) { m ->
            val p = m.parameterTypes
            p.size == 1 && when (value) {
                is Boolean -> p[0] == Boolean::class.javaPrimitiveType
                is Int -> p[0] == Int::class.javaPrimitiveType
                else -> p[0].isInstance(value)
            }
        }.invoke(target, value)
    }

    /** A TaskInfo's windowing mode (hidden getWindowingMode, else its configuration's text), or null. */
    private fun windowingMode(info: Any): Int? =
        runCatching { info.javaClass.getMethod("getWindowingMode").invoke(info) as Int }.getOrNull()
            ?: (field(info, "configuration") as? android.content.res.Configuration)?.let { DesktopFullscreen.windowingModeIn(it.toString()) }

    private fun modeName(mode: Int?): String = when (mode) {
        WINDOWING_MODE_FULLSCREEN -> "full screen"
        DesktopFullscreen.WINDOWING_MODE_FREEFORM -> "freeform"
        null -> "mode unknown"
        else -> "mode $mode"
    }

    private fun release(displayId: Int, spaceClass: String): String {
        desks.remove(displayId)
        // 0.5.21 (after an unplug the empty holding task #9280 sat on display 0):
        // when the glasses go, Android moves the held root task to the phone's display, so it is swept there too.
        val phone = if (displayId != PHONE_DISPLAY) sweep(PHONE_DISPLAY, null).let { if (it.isEmpty()) "" else it.replaceFirst("; ", "; on the phone's display: ") } else ""
        // 0.5.23 (`leftover check failed (IllegalArgumentException: Display 88 doesn't exist)`):
        // a display already gone is not swept, and the leash this process held is let go of either way.
        val gone = displaySize(displayId) == null
        val sweepHere = { if (gone) "; display $displayId is gone, so nothing is left on it to sweep" else sweep(displayId, null) }
        val ids = setOfNotNull(heldIds.remove(displayId))
        // 0.5.7: only a full screen task: a Space back in the DeX Desk must never take the Desk with it.
        val token = holders.remove(displayId)
            ?: (if (gone) null else runCatching { rootTasks(displayId) }.getOrDefault(emptyList()).firstOrNull { showsSpace(it, spaceClass) && windowingMode(it) == WINDOWING_MODE_FULLSCREEN }
                ?.let { field(it, "token") }?.takeIf { childTasks(it).isNotEmpty() })
        if (token == null) {
            val leash = dropLeash(displayId)
            checkLayersLater(displayId, ids)
            return "no full screen task to release$leash${if (gone) sweepHere() else ""}$phone"
        }
        val here = (if (runCatching { deleteRootTask(token) }.getOrDefault(false)) "root task deleted" else "failed: Android did not delete the root task") + dropLeash(displayId) + sweepHere()
        checkLayersLater(displayId, ids)
        return here + phone
    }

    private fun rootTasks(displayId: Int): List<Any> =
        (method(taskOrganizer, "getRootTasks") { it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
            .invoke(taskOrganizer, displayId, null) as? List<*>)?.filterNotNull() ?: emptyList()

    private fun childTasks(token: Any): List<Any> =
        (method(taskOrganizer, "getChildTasks") { it.parameterTypes.size == 2 }.invoke(taskOrganizer, token, null) as? List<*>)
            ?.filterNotNull() ?: emptyList()

    /** Deletes the Space's holding task: deleteRootTask, or deleteTask on Android 17 (0.5.1). */
    private fun deleteRootTask(token: Any): Boolean {
        val name = taskApi.delete.ifEmpty { "deleteRootTask" }
        return method(taskOrganizer, name) { it.parameterTypes.size == 1 }.invoke(taskOrganizer, token) as? Boolean ?: false
    }

    /** A RunningTaskInfo whose top or base activity is the Space. */
    private fun showsSpace(info: Any, spaceClass: String): Boolean =
        listOf("topActivity", "baseActivity").any { (field(info, it) as? android.content.ComponentName)?.className == spaceClass }

    private fun field(o: Any, name: String): Any? = runCatching { o.javaClass.getField(name).get(o) }.getOrNull()

    /**
     * 1.1.1: the display's root tasks, top first: IActivityTaskManager.getAllRootTaskInfosOnDisplay (RootTaskInfo, Android
     * 12 and up), else Android 11's getAllStackInfosOnDisplay (ActivityManager.StackInfo). Null when neither is there.
     */
    private fun rootTaskInfos(displayId: Int): List<*>? {
        val name = taskManager.javaClass.methods.firstOrNull { m ->
            (m.name == "getAllRootTaskInfosOnDisplay" || m.name == "getAllStackInfosOnDisplay") &&
                m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: throw NoSuchMethodException("IActivityTaskManager has no root task or stack list for a display (Android ${Build.VERSION.SDK_INT})")
        return name.invoke(taskManager, displayId) as? List<*>
    }

    /** 1.1.1: [field] of a RootTaskInfo, or the same thing in Android 11's StackInfo (stackId, visible). */
    private fun taskField(o: Any, name: String): Any? = field(o, name) ?: when (name) {
        "taskId" -> field(o, "stackId")
        "isVisible" -> field(o, "visible")
        else -> null
    }

    override fun secureWindows(displayId: Int): Array<String> = guard(emptyArray()) {
        val process = ProcessBuilder("dumpsys", "window", "windows").redirectErrorStream(true).start()
        val dump = process.inputStream.bufferedReader().readText()
        process.waitFor(5, TimeUnit.SECONDS)
        SecureWindows.securePackages(dump, displayId).toTypedArray()
    }

    /**
     * IActivityTaskManager.getAllRootTaskInfosOnDisplay as the shell (MANAGE_ACTIVITY_TASKS),
     * top task first. Each RootTaskInfo is a TaskInfo: its public isVisible and numActivities
     * fields are read by reflection, so a field this Android lacks is left out, not guessed.
     */
    override fun displayTasks(displayId: Int): IntArray? = guard(null) {
        val infos = rootTaskInfos(displayId) ?: return@guard null
        val visible = infos.count { info -> info != null && (taskField(info, "isVisible") as? Boolean ?: true) }
        // 1.1.1: Android 11's StackInfo has no activity count: 2, "more than one", so Back is never taken for the last.
        val top = infos.firstOrNull()?.let { taskField(it, "numActivities") as? Int } ?: if (infos.isEmpty()) 0 else 2
        intArrayOf(infos.size, visible, top)
    }

    /** 1.1.15: the display's root tasks as "taskId package" lines, top first ([com.hayeco.flipspaces.core.spatial.LaunchDisplay]). */
    override fun rootTaskLines(displayId: Int): Array<String>? = guard(null) {
        val infos = rootTaskInfos(displayId) ?: return@guard null
        infos.filterNotNull().mapNotNull { r ->
            val id = taskField(r, "taskId") as? Int ?: return@mapNotNull null
            val pkg = listOf("topActivity", "baseActivity").firstNotNullOfOrNull { (field(r, it) as? android.content.ComponentName)?.packageName }
                ?: return@mapNotNull null
            com.hayeco.flipspaces.core.spatial.LaunchDisplay.line(id, pkg)
        }.toTypedArray()
    }

    /**
     * 1.1.15: root task [taskId] onto [displayId]: IActivityTaskManager.moveRootTaskToDisplay (Android 12 and up), else
     * Android 11's moveStackToDisplay, as the shell (MANAGE_ACTIVITY_TASKS), what `am display move-stack` runs.
     */
    override fun moveTaskToDisplay(taskId: Int, displayId: Int): String? = guardText {
        val m = taskManager.javaClass.methods.firstOrNull { m ->
            (m.name == "moveRootTaskToDisplay" || m.name == "moveStackToDisplay") &&
                m.parameterTypes.size == 2 && m.parameterTypes.all { it == Int::class.javaPrimitiveType }
        } ?: return@guardText "IActivityTaskManager has no moveRootTaskToDisplay on Android ${Build.VERSION.SDK_INT}"
        m.invoke(taskManager, taskId, displayId)
        logI("task $taskId moved to display $displayId (${m.name})")
        null
    }

    override fun describeDisplay(displayId: Int): String? = runCatching {
        method(displayManager, "getDisplayInfo") { it.parameterTypes.size == 1 }.invoke(displayManager, displayId)?.toString()
    }.getOrNull()

    private fun service(name: String, iface: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder
        return Class.forName("$iface\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("No $name service")
    }

    private fun method(target: Any, name: String, filter: (Method) -> Boolean): Method =
        target.javaClass.methods.firstOrNull { it.name == name && filter(it) }
            ?: throw NoSuchMethodException("${target.javaClass.name}.$name with the expected parameters (Android ${Build.VERSION.SDK_INT})")

    private inline fun <T> guard(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        error = describe(e)
        logW(error!!)
        fallback
    }

    private inline fun guardText(block: () -> String?): String? = try {
        block()
    } catch (e: Throwable) {
        describe(e).also { error = it; logW(it) }
    }

    private fun describe(e: Throwable): String {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"

        /** 0.5.52: the companion association made for the shell's virtual device: a locally administered address, app streaming. */
        const val DEVICE_MAC = "02:46:4c:53:50:01"
        const val STREAMING_PROFILE = "android.app.role.COMPANION_DEVICE_APP_STREAMING"

        /** 0.5.22: [config]'s word for ignore activity size restrictions taken. */
        const val SIZE_SET = "set"
        const val INJECT_ASYNC = 0
        const val WINDOWING_MODE_UNDEFINED = 0
        const val WINDOWING_MODE_FULLSCREEN = 1
        const val HOLD_WAIT_MS = 2_000L
        const val PHONE_DISPLAY = 0
    }
}
