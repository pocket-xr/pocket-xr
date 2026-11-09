package com.hayeco.flipspaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.core.spatial.AxisMap
import com.hayeco.flipspaces.core.spatial.HeadTrackingStatus
import com.hayeco.flipspaces.core.spatial.OneImuProtocol
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.spatial.HeadLive

/** Facts read once when the screen opens. */
data class HeadTrackingFacts(val soc: String, val dynamicSensors: List<String>)

/**
 * 1.2.8 (Discussion #24, VITURE Beast glasses): the card for glasses that aren't XREAL's: the opt-in motion recording, and on
 * VITURE glasses the experimental reader with its two axis switches.
 */
data class OtherGlassesCard(
    /** The glasses a recording would read ("35CA:1201 "VITURE Beast""), null when none is plugged in. */
    val glasses: String?,
    val viture: Boolean,
    val vitureOn: Boolean,
    val vitureAxes: com.hayeco.flipspaces.core.spatial.VitureAxes,
    val recording: Boolean,
    val motionLine: String?,
    val onViture: (Boolean) -> Unit,
    val onVitureAxes: (com.hayeco.flipspaces.core.spatial.VitureAxes) -> Unit,
    val onRecord: () -> Unit,
)

@Composable
fun HeadTrackingScreen(
    status: HeadTrackingStatus,
    live: HeadLive?,
    networks: List<String>,
    ethernet: List<String>,
    connectResult: String?,
    /** 0.5.5: each route tried in the last round and how it ended; [route] is the one in use. */
    attempts: List<String>,
    route: String?,
    /** 0.5.6: in plain words whether the glasses answered on the IMU port, null before a try. */
    answer: String?,
    facts: HeadTrackingFacts,
    axes: AxisMap,
    predictMs: Int,
    onAxes: (AxisMap) -> Unit,
    onPredict: (Int) -> Unit,
    onRecentre: () -> Unit,
    onRetry: () -> Unit,
    /** 0.5.12: the display delay calibration's runs, whether it is saved, and the check in the Space. */
    delayRuns: List<Float> = emptyList(),
    delayCalibrated: Boolean = false,
    delayCheck: Boolean = false,
    /** 0.5.12: the Space's head timing line, null while no Space draws. */
    timing: String? = null,
    onDelayCheck: (Boolean) -> Unit = {},
    onSaveRun: () -> Unit = {},
    onClearRuns: () -> Unit = {},
    /** 0.5.15: the level calibration and the per-row pose settings. */
    levelCheck: Boolean = false,
    levelResult: String? = null,
    levelCalibrated: Boolean = false,
    mountRollDeg: Float = 0f,
    mountPitchDeg: Float = 0f,
    onLevelCheck: (Boolean) -> Unit = {},
    onSaveLevel: () -> Unit = {},
    onClearLevel: () -> Unit = {},
    rowPose2d: Boolean = true,
    rowPoseSbs: Boolean = true,
    onRowPose: (sbs: Boolean, on: Boolean) -> Unit = { _, _ -> },
    /** 0.5.25: the per-eye late warp in side by side 3D. */
    lateWarpSbs: Boolean = true,
    onLateWarp: (Boolean) -> Unit = {},
    /** 1.1.10: the whole screen as text ([com.hayeco.flipspaces.core.spatial.HeadDiagnosticsText]), copied or shared in full. */
    onCopyAll: () -> Unit = {},
    onShare: () -> Unit = {},
    /** 1.1.14: the logs and this screen's text in one zip on an email to Mike ([com.hayeco.flipspaces.SendLogs]). */
    onSendLogs: () -> Unit = {},
    /** 1.2.8: glasses that aren't XREAL's ([OtherGlassesCard]); null hides the card. */
    other: OtherGlassesCard? = null,
) {
    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Glasses head tracking", style = MaterialTheme.typography.headlineSmall) }
            item {
                val ok = status is HeadTrackingStatus.Streaming
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (ok) "Head tracking works" else "Head tracking not running", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text(status.summary, style = MaterialTheme.typography.bodyMedium) }
                        if (route != null) Text("Route: $route", style = MaterialTheme.typography.bodyMedium)
                        if (!ok && answer != null) Text(answer, style = MaterialTheme.typography.bodyMedium)
                        if (!ok) {
                            Text(
                                "Without it the Space still works: panels stay in front of you (head locked) and the trackpad turns the view.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onRecentre, enabled = ok) { Text("Recentre") }
                            OutlinedButton(onClick = onRetry) { Text("Try again") }
                        }
                        // 1.1.10 (a tester could copy only the start of one line): everything on this screen, in full.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onCopyAll) { Text("Copy all") }
                            OutlinedButton(onClick = onShare) { Text("Share") }
                        }
                        OutlinedButton(onClick = onSendLogs) { Text(com.hayeco.flipspaces.core.LogBundle.BUTTON) }
                        Text(com.hayeco.flipspaces.core.LogBundle.WHAT_IS_SENT, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (other != null) item { OtherGlasses(other) }
            item {
                Step(
                    "1. Glasses USB network",
                    when (status) {
                        is HeadTrackingStatus.NoNetwork -> "Not found."
                        // 1.1.10: these glasses have no USB network for head tracking; nothing is missing.
                        is HeadTrackingStatus.HidGlasses -> "Not used: these glasses send head tracking over USB HID."
                        is HeadTrackingStatus.VitureNotYet -> "Not used: VITURE glasses have no USB network."
                        HeadTrackingStatus.TurnedOff -> "Not looked for: head tracking is turned off in Settings."
                        HeadTrackingStatus.Searching, HeadTrackingStatus.Off -> "Looking."
                        else -> "Found."
                    },
                    networks.ifEmpty { listOf("Nothing listed yet.") } +
                        listOf("USB Ethernet (eth0, eth1) through Shizuku:") +
                        ethernet.ifEmpty { listOf("Not needed or not tried yet.") },
                )
            }
            item {
                Step(
                    "2. TCP ${OneImuProtocol.HOST}:${OneImuProtocol.STREAM_PORT}",
                    listOfNotNull(answer, connectResult).joinToString("\n").ifEmpty { "Not tried yet." },
                    listOf("Routes, in the order tried (the app's own socket, then the Shizuku shell helper):") +
                        attempts.ifEmpty { listOf("None tried yet.") },
                )
            }
            item {
                val counts = live?.counts
                Step(
                    "3. Sensor reports",
                    if (live == null) "None yet." else "%.0f IMU reports a second".format(live.rateHz),
                    if (counts == null) emptyList() else listOf(
                        "${counts.imu} IMU, ${counts.magnetometer} magnetometer, ${counts.unknownType} unknown type, " +
                            "${counts.badLength} other length, ${counts.droppedBytes} bytes skipped of ${counts.bytes}",
                    ),
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("4. Live orientation", style = MaterialTheme.typography.titleMedium)
                        if (live == null) {
                            Text("No pose yet.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                "yaw %+7.1f   pitch %+6.1f   roll %+6.1f".format(live.yaw, live.pitch, live.roll),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Turn right: yaw goes up. Look up: pitch goes up. Tilt right ear down: roll goes up. If not, pick another axis layout below.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Mono("gyro rad/s   ${fmt(live.gyro)}${if (live.still) "   (still)" else ""}")
                            Mono("bias rad/s   ${fmt(live.gyroBias)}")
                            Mono("accel raw    ${fmt(live.rawAccel)}")
                            val suggested = AxisMap.suggest(live.rawAccel)
                            if (live.still && suggested.isNotEmpty() && axes !in suggested) {
                                Text(
                                    "Gravity is not on this layout's up axis. Layouts that fit: ${suggested.joinToString { it.label }}.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Sensor axis layout", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "XREAL does not document the axes. Keep the glasses still and level; the right layout puts gravity on up.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AxisMap.entries.forEach { m ->
                                FilterChip(selected = axes == m, onClick = { onAxes(m) }, label = { Text(m.label) })
                            }
                        }
                        Text("Glasses display delay: $predictMs ms (added to the measured two frames the pose is predicted ahead; it no longer delays the frame itself)", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = predictMs.toFloat(),
                            onValueChange = { onPredict(it.toInt()) },
                            valueRange = 0f..40f,
                        )
                        Text(
                            if (delayCalibrated) "Calibrated on these glasses" else "Not calibrated: 8 ms is a guess",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Calibrate it: with the Space on the glasses, tap Check delay. A thin line appears fixed in the room. " +
                                "Turn your head gently left and right and move the slider until the line stays put, then tap Save run. " +
                                "Move the slider to one end before each run. Three runs within 1 ms of each other set the delay for these glasses.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDelayCheck(!delayCheck) }) { Text(if (delayCheck) "Stop check" else "Check delay") }
                            OutlinedButton(onClick = onSaveRun, enabled = delayCheck) { Text("Save run") }
                            TextButton(onClick = onClearRuns) { Text("Clear runs") }
                        }
                        if (delayRuns.isNotEmpty()) {
                            Text("Runs: " + delayRuns.joinToString { "%.0f ms".format(it) }, style = MaterialTheme.typography.bodySmall)
                        }
                        timing?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Level", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (levelCalibrated) "Calibrated: the glasses' sensor reads roll %.2f, pitch %.2f degrees when the picture is level.".format(mountRollDeg, mountPitchDeg)
                            else "Not calibrated: the sensor is taken as level with the picture.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Once per pair of glasses: with the Space on the glasses, tap Check level. A green line appears across the middle of the view. " +
                                "Look at a level edge at eye height (the horizon, a far shelf or window frame), tilt your head until the line lies on it, hold still, then tap Save level. " +
                                "The sensor may read up to 40 degrees of pitch (the Aura's sits about 14.5 degrees tilted, a One Pro more) and 10 of roll.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onLevelCheck(!levelCheck) }) { Text(if (levelCheck) "Stop level" else "Check level") }
                            OutlinedButton(onClick = onSaveLevel, enabled = levelCheck) { Text("Save level") }
                            TextButton(onClick = onClearLevel, enabled = levelCalibrated) { Text("Clear") }
                        }
                        levelResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("Per-row pose (each row of the picture drawn for the moment it lights)", style = MaterialTheme.typography.bodyMedium)
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = rowPose2d, onClick = { onRowPose(false, !rowPose2d) }, label = { Text("In 2D") })
                            FilterChip(selected = rowPoseSbs, onClick = { onRowPose(true, !rowPoseSbs) }, label = { Text("In 3D side by side") })
                            FilterChip(selected = lateWarpSbs, onClick = { onLateWarp(!lateWarpSbs) }, label = { Text("Late warp in 3D") })
                        }
                        Text(
                            "On in 3D since 0.5.25, with the 60 Hz row model: if the picture tilts during head turns in 3D, turn it off there. " +
                                "Late warp in 3D draws the Space early and turns it by the newest head pose just before each frame is due, so a slow frame no longer jumps.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                Step(
                    "Other checks",
                    "Chip: ${facts.soc}",
                    listOf(
                        "Dynamic sensors (an Android head tracker would show here): " +
                            facts.dynamicSensors.ifEmpty { listOf("none") }.joinToString("; "),
                        "Protocol: XREAL One family IMU stream, read only. See docs/SPATIAL.md 1.1.",
                    ),
                )
            }
        }
    }
}

@Composable
private fun OtherGlasses(o: OtherGlassesCard) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Glasses that aren't XREAL's", style = MaterialTheme.typography.titleMedium)
            Text(
                "Record motion for 20 seconds saves what your glasses' motion sensor sends over USB, so support for them can be added. " +
                    "It asks for USB access, reads for 20 seconds while you move your head, and keeps only the sensor's numbers; " +
                    "Send logs takes it along. On VITURE glasses it also sends VITURE's documented start command after 5 seconds.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(o.glasses?.let { "Plugged in: $it" } ?: "No glasses other than XREAL's are plugged in.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = o.onRecord, enabled = o.glasses != null && !o.recording && !o.vitureOn) {
                Text(if (o.recording) "Recording..." else "Record motion for 20 seconds")
            }
            o.motionLine?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
            if (o.viture) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Switch(checked = o.vitureOn, onCheckedChange = o.onViture)
                    Text("Experimental VITURE head tracking", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Experimental. It follows a public description of VITURE's newer glasses; your model may not answer. " +
                        "On the VITURE Beast the sensor axis layout starts at x right, y down, z front, with both switches off. " +
                        "On other models, if turning your head moves the view the wrong way, try the switches below; they apply at once.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = o.vitureAxes.flipUpDown,
                        onClick = { o.onVitureAxes(o.vitureAxes.copy(flipUpDown = !o.vitureAxes.flipUpDown)) },
                        label = { Text("Flip up and down") },
                        enabled = o.vitureOn,
                    )
                    FilterChip(
                        selected = o.vitureAxes.swapTurnTilt,
                        onClick = { o.onVitureAxes(o.vitureAxes.copy(swapTurnTilt = !o.vitureAxes.swapTurnTilt)) },
                        label = { Text("Swap turn and tilt") },
                        enabled = o.vitureOn,
                    )
                }
            }
        }
    }
}

@Composable
private fun Step(title: String, result: String, details: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(result, style = MaterialTheme.typography.bodyMedium) }
            details.forEach { Mono(it) }
        }
    }
}

@Composable
private fun Mono(text: String) {
    SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
}

private fun fmt(v: Vec3) = "%+8.3f %+8.3f %+8.3f".format(v.x, v.y, v.z)
