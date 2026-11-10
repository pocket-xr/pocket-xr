package com.hayeco.flipspaces.spatial;

import android.view.Surface;
import com.hayeco.flipspaces.spatial.IPanelEvents;

/**
 * Runs as the shell user inside a Shizuku user service: creates the trusted virtual displays
 * behind app panels, launches apps on them and injects input by display id. Methods that can
 * fail return null on success or the platform's error text.
 */
interface IPanelHost {
    /** Shizuku calls this transaction to stop the service. */
    void destroy() = 16777114;

    /**
     * Returns the new display id, or -1; then lastError() says why. [refreshRate] is the rate
     * the display asks for (0.4.1: 30 when the phone runs warm).
     */
    int createDisplay(String name, int width, int height, int dpi, int flags, in Surface surface, float refreshRate) = 1;

    /**
     * 0.5.52: as createDisplay, the display made on the shell's own virtual device (VirtualDeviceManager), so Android gives
     * it a display group of the device's instead of the phone's: the phone's screens can sleep while its apps run on.
     * Returns -1 (lastError() says why) when the device cannot be made; the caller then uses createDisplay.
     */
    int createDisplayOnDevice(String name, int width, int height, int dpi, int flags, in Surface surface, float refreshRate) = 27;

    String resizeDisplay(int displayId, int width, int height, int dpi) = 2;

    /**
     * 0.5.3: the display renders into [surface] from now on (a panel's new ImageReader after a
     * resize, since an ImageReader's buffers keep the size it was made with).
     */
    String setDisplaySurface(int displayId, in Surface surface) = 15;

    void releaseDisplay(int displayId) = 3;

    String launch(int displayId, String packageName, String activityName) = 4;

    /**
     * 0.5.37 (Mike: "Open a new instance if the app is already running" in the Menu): as launch, with [newInstance] adding
     * FLAG_ACTIVITY_MULTIPLE_TASK, so an app already running opens again in a window of its own.
     */
    String launchNew(int displayId, String packageName, String activityName, boolean newInstance) = 26;

    /** MotionEvent action (0 down, 1 up, 2 move, 3 cancel) at display pixels x, y. */
    String injectTouch(int displayId, int action, float x, float y, long downTime) = 5;

    String lastError() = 6;

    /** Android's own view of the display: Display.toString(), or null when it is gone. */
    String describeDisplay(int displayId) = 7;

    /**
     * 0.4.1: any MotionEvent (touch with several pointers, mouse hover, mouse wheel) on the
     * display. [action] carries the pointer index for pointer down and up; [source] is the
     * InputDevice source; [vScroll] and [hScroll] are wheel notches for ACTION_SCROLL.
     */
    String injectMotion(int displayId, int action, in float[] xs, in float[] ys, int source, float vScroll, float hScroll, long downTime) = 8;

    /** 0.4.1: a key event (down 0, up 1) from a keyboard, on the display. */
    String injectKey(int displayId, int action, int keyCode, int metaState, int repeat, long downTime) = 9;

    /**
     * 0.4.1: makes the display the top focused one, so key events and the phone's keyboard go
     * to its app. Returns null when Android took it, else what was tried.
     */
    String focusDisplay(int displayId) = 10;

    /** 0.4.1: where onPaused, onResumed and onStopped of every panel display go. */
    void setEvents(IPanelEvents events) = 11;

    /** 0.4.1: packages with a FLAG_SECURE window on the display, from dumpsys window. */
    String[] secureWindows(int displayId) = 12;

    /**
     * 0.4.2: what runs on the display, as {root tasks, visible root tasks, activities in the
     * top task}; null when Android would not say. An empty display means the app closed.
     */
    int[] displayTasks(int displayId) = 13;

    /**
     * 0.4.7: with hold, moves the task of the activity named spaceClass on the display into a
     * root task created for it (ITaskOrganizerController.createRootTask), which Android removes
     * with all its tasks when the display goes away; without, deletes that root task. Returns
     * what was done, or "failed: " and the error.
     */
    String holdSpace(int displayId, boolean hold, String spaceClass) = 14;

    /**
     * 0.5.7: whether SurfaceFlinger composes a layer of the activity named spaceClass on the
     * display (its "HWC layers" table): "composed: ...", "not composed: ..." with what it does
     * compose there, or "unknown: ..."; then the task layers it knows, for the log.
     */
    String spaceOnScreen(int displayId, String spaceClass) = 17;

    /**
     * 0.5.7: the Space's task out of its full screen task and back into the DeX Desk it came from
     * (or a freeform root task of its own), the full screen task deleted. Returns what was done,
     * or "failed: " and the error.
     */
    String windowSpace(int displayId, String spaceClass) = 18;

    /**
     * 0.5.13: the refresh rate the panel display asks for (60 focused, 30 not).
     * Returns null when Android took it, else why not (Android 14 sets the rate only when the
     * display is made; a later change needs a hidden IDisplayManager call that may not exist).
     */
    String setDisplayRefreshRate(int displayId, float refreshRate) = 19;

    /**
     * 0.5.14: starts [component] (package/class) on the display as the
     * shell, through IActivityTaskManager with ActivityOptions that carry launch bounds
     * [left, top, right, bottom] (the whole display), which `am start` cannot pass. Returns null when
     * Android started it, else why not.
     */
    String startSpace(int displayId, String component, int left, int top, int right, int bottom) = 20;

    /**
     * 0.5.19: SurfaceFlinger's frame times for the Space's own layer (the
     * activity named spaceClass's SurfaceView): `dumpsys SurfaceFlinger --latency` for it, as text
     * (the period, then desired present, actual present and frame ready per frame), or "unknown: ...".
     */
    String presentTimes(String spaceClass) = 21;

    /**
     * 0.5.19: why [packageName]'s last process ended, from the activity
     * manager's exit records (`dumpsys activity exit-info`): its reason and description, or
     * "unknown: ..." when there is none.
     */
    String exitReason(String packageName) = 22;

    /**
     * 0.5.21: the window's Refresh: the app's launcher activity started again on the display with
     * NEW_TASK | CLEAR_TASK, so it is made anew and reloads; null when started, else the error.
     */
    String refresh(int displayId, String packageName, String activityName) = 23;

    /**
     * 0.5.21 (Wide view): what runs on the display as Android sees it, the
     * display's part of `dumpsys activity activities` (its tasks and activities, each with its requested
     * orientation, resize mode and letterbox state), or "unknown: ..." when there is none.
     */
    String appShape(int displayId) = 24;

    /**
     * 0.5.22 (Wide view's per app fallback, core.spatial.WideViewOverride): [action] "read" returns
     * dumpsys platform_compat's line for OVERRIDE_ANY_ORIENTATION_TO_USER; "enable", "enable-no-kill",
     * "disable-no-kill" and "reset" run that one am compat command for [packageName]. Returns "done: ...",
     * "refused: ..." or "failed: ...".
     */
    String appCompat(String packageName, String action) = 25;

    /**
     * 1.1.15: the root tasks on [displayId], top first, one "taskId package" line each (core.spatial.LaunchDisplay.parse),
     * the package of each task's top activity (else its base one); null when Android would not say.
     */
    String[] rootTaskLines(int displayId) = 28;

    /**
     * 1.1.15: moves root task [taskId] onto [displayId] (IActivityTaskManager.moveRootTaskToDisplay, what
     * `am display move-stack` runs), for an app's new screen that Android put on the phone instead of its window.
     * Returns null when Android took it, else why not.
     */
    String moveTaskToDisplay(int taskId, int displayId) = 29;
    /** Starts reading Joy-Cons for onJoyCon. Returns what was found. */
    String startJoyCons() = 40;

    /** Stops reading Joy-Cons. */
    void stopJoyCons() = 41;

    /**
     * Turns the phone's own panel off or back on without putting the phone to sleep, so a game keeps running. Pressing
     * the power button brings the panel back. Returns null on success, or why it could not.
     */
    String setPhonePanel(boolean on) = 42;

    /**
     * 1.2.3 (issue 59): a virtual gamepad through /dev/uhid (core UhidGamepad), which the shell may open: Android lists a
     * new game controller until gamepadClose(). Returns null when it is up, else why not.
     */
    String gamepadOpen() = 30;

    /** 1.2.3: one input report of the virtual gamepad (core UhidGamepad.report). */
    String gamepadInput(in byte[] report) = 31;

    /** 1.2.3: the virtual gamepad goes (its device closed). */
    void gamepadClose() = 32;

    /**
     * 1.2.3 (issue 59): touches with their own pointer ids (a game's on-screen controls held at once), at display pixels.
     * [action] carries the pointer index for pointer down and up.
     */
    String injectTouches(int displayId, int action, in int[] ids, in float[] xs, in float[] ys, long downTime) = 33;
}
