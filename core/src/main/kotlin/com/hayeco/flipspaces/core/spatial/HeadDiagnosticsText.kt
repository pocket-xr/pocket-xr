package com.hayeco.flipspaces.core.spatial

/**
 * 1.1.10 (a tester: "The head tracking diagnostics isn't available to copy in its entirety, so I only got the starting
 * part"): the whole head tracking screen as one plain text, for its Copy all and Share buttons. Each section is written
 * out in full: no line is shortened, and nothing is left out for being long.
 */
data class HeadDiagnosticsText(
    val appVersion: String,
    val phone: String,
    val android: String,
    val timeUtc: String,
    val status: String,
    val tracking: Boolean,
    val route: String?,
    val answer: String?,
    val networks: List<String>,
    val ethernet: List<String>,
    val connectResult: String?,
    val attempts: List<String>,
    val reports: String,
    val reportCounts: String?,
    val orientation: String?,
    val axes: String,
    val predictMs: Int,
    val delayCalibrated: Boolean,
    val levelCalibrated: Boolean,
    val timing: String?,
    val soc: String,
    val dynamicSensors: List<String>,
    /**
     * The USB devices and their interfaces, one line each, with each device's head tracking link. 1.2.6: every device
     * Android lists, not only XREAL's (Discussion #24's VITURE Beast showed "none").
     */
    val usb: List<String>,
    /** 1.2.6: the newest motion recording ([HidRecording]) in one line; null when there is none. */
    val motion: String? = null,
) {
    fun text(): String = buildString {
        appendLine("Pocket XR head tracking diagnostics")
        appendLine("Made $timeUtc (UTC), Pocket XR $appVersion, $phone, Android $android")
        appendLine()
        appendLine("Head tracking: ${if (tracking) "works" else "not running"}")
        appendLine(status)
        route?.let { appendLine("Route: $it") }
        answer?.let { appendLine(it) }
        appendLine()
        appendLine("USB devices (glasses of every make):")
        if (usb.isEmpty()) appendLine("- none")
        usb.forEach { appendLine("- $it") }
        appendLine("Motion recording: ${motion ?: "none yet (Record motion for 20 seconds, for glasses that aren't XREAL's)"}")
        appendLine()
        appendLine("1. Glasses USB network:")
        if (networks.isEmpty()) appendLine("- nothing listed yet")
        networks.forEach { appendLine("- $it") }
        appendLine("USB Ethernet (eth0, eth1) through Shizuku:")
        if (ethernet.isEmpty()) appendLine("- not needed or not tried yet")
        ethernet.forEach { appendLine("- $it") }
        appendLine()
        appendLine("2. TCP ${OneImuProtocol.HOST}:${OneImuProtocol.STREAM_PORT}:")
        connectResult?.let { appendLine(it) }
        appendLine("Routes, in the order tried:")
        if (attempts.isEmpty()) appendLine("- none tried yet")
        attempts.forEach { appendLine("- $it") }
        appendLine()
        appendLine("3. Sensor reports: $reports")
        reportCounts?.let { appendLine(it) }
        appendLine()
        appendLine("4. Live orientation: ${orientation ?: "no pose yet"}")
        appendLine()
        appendLine("Settings: axis layout $axes; glasses display delay $predictMs ms (${if (delayCalibrated) "calibrated" else "not calibrated"}); level ${if (levelCalibrated) "calibrated" else "not calibrated"}")
        timing?.let { appendLine("Timing: $it") }
        appendLine()
        appendLine("Other checks:")
        appendLine("- Chip: $soc")
        appendLine("- Dynamic sensors: " + dynamicSensors.ifEmpty { listOf("none") }.joinToString("; "))
    }
}
