package com.hayeco.flipspaces.spatial;

/**
 * Pocket XR's callback for the panel displays (0.4.1): the shell side passes on Android's
 * IVirtualDisplayCallback onPaused, onResumed and onStopped for each display.
 */
oneway interface IPanelEvents {
    /** [event] is 0 paused, 1 resumed, 2 stopped (core.spatial.PanelDisplayEvent order). */
    void onDisplayEvent(int displayId, int event);

    /**
     * 0.5.25: the service's own recent log lines, oldest first, a few at a time about
     * once a second (core.spatial.PanelLogRelay), for the app's log under FlipSpaces/Panels.
     */
    void onLogLines(in String[] lines);

    /**
     * One Joy-Con motion sample with its latest buttons and stick (JoyConReader). [hand] is 0 left, 1 right; gyro in
     * radians per second and accelerometer in g, in the sensors' own axes. [readNs] is System.nanoTime when the read
     * that brought the sample returned, the same clock in both processes.
     */
    void onJoyCon(int hand, long readNs, int buttons, float stickX, float stickY, float gx, float gy, float gz, float ax, float ay, float az);
}
