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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.R
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
    /** 1.2.12: the switch's name ([com.hayeco.flipspaces.core.spatial.VitureDevices.switchLabel]): no "Experimental" on the Beast. */
    val vitureLabel: String,
    /** 1.2.12: the glasses are a VITURE Beast, where the reader is on by default. */
    val vitureBeast: Boolean,
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
            item { Text(stringResource(R.string.head_title), style = MaterialTheme.typography.headlineSmall) }
            item {
                val ok = status is HeadTrackingStatus.Streaming
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(if (ok) R.string.head_works else R.string.head_not_running), style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text(status.summary, style = MaterialTheme.typography.bodyMedium) }
                        if (route != null) Text(stringResource(R.string.head_route, route), style = MaterialTheme.typography.bodyMedium)
                        if (!ok && answer != null) Text(answer, style = MaterialTheme.typography.bodyMedium)
                        if (!ok) {
                            Text(
                                stringResource(R.string.head_without_it),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onRecentre, enabled = ok) { Text(stringResource(R.string.head_recentre)) }
                            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.head_try_again)) }
                        }
                        // 1.1.10 (a tester could copy only the start of one line): everything on this screen, in full.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onCopyAll) { Text(stringResource(R.string.head_copy_all)) }
                            OutlinedButton(onClick = onShare) { Text(stringResource(R.string.head_share)) }
                        }
                        OutlinedButton(onClick = onSendLogs) { Text(com.hayeco.flipspaces.core.LogBundle.BUTTON) }
                        Text(com.hayeco.flipspaces.core.LogBundle.WHAT_IS_SENT, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (other != null) item { OtherGlasses(other) }
            item {
                Step(
                    stringResource(R.string.head_step_network),
                    stringResource(
                        when (status) {
                            is HeadTrackingStatus.NoNetwork -> R.string.head_net_not_found
                            // 1.1.10: these glasses have no USB network for head tracking; nothing is missing.
                            is HeadTrackingStatus.HidGlasses -> R.string.head_net_hid
                            is HeadTrackingStatus.VitureNotYet -> R.string.head_net_viture
                            HeadTrackingStatus.TurnedOff -> R.string.head_net_turned_off
                            HeadTrackingStatus.Searching, HeadTrackingStatus.Off -> R.string.head_net_looking
                            else -> R.string.head_net_found
                        },
                    ),
                    networks.ifEmpty { listOf(stringResource(R.string.head_nothing_listed)) } +
                        listOf(stringResource(R.string.head_usb_ethernet)) +
                        ethernet.ifEmpty { listOf(stringResource(R.string.head_not_needed)) },
                )
            }
            item {
                Step(
                    "2. TCP ${OneImuProtocol.HOST}:${OneImuProtocol.STREAM_PORT}",
                    listOfNotNull(answer, connectResult).joinToString("\n").ifEmpty { stringResource(R.string.head_not_tried) },
                    listOf(stringResource(R.string.head_routes_order)) +
                        attempts.ifEmpty { listOf(stringResource(R.string.head_none_tried)) },
                )
            }
            item {
                val counts = live?.counts
                Step(
                    stringResource(R.string.head_step_reports),
                    if (live == null) stringResource(R.string.head_none_yet) else stringResource(R.string.head_reports_rate, live.rateHz),
                    if (counts == null) emptyList() else listOf(
                        "${counts.imu} IMU, ${counts.magnetometer} magnetometer, ${counts.unknownType} unknown type, " +
                            "${counts.badLength} other length, ${counts.droppedBytes} bytes skipped of ${counts.bytes}",
                    ),
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.head_step_orientation), style = MaterialTheme.typography.titleMedium)
                        if (live == null) {
                            Text(stringResource(R.string.head_no_pose), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                "yaw %+7.1f   pitch %+6.1f   roll %+6.1f".format(live.yaw, live.pitch, live.roll),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.head_turn_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Mono("gyro rad/s   ${fmt(live.gyro)}${if (live.still) "   (still)" else ""}")
                            Mono("bias rad/s   ${fmt(live.gyroBias)}")
                            Mono("accel raw    ${fmt(live.rawAccel)}")
                            val suggested = AxisMap.suggest(live.rawAccel)
                            if (live.still && suggested.isNotEmpty() && axes !in suggested) {
                                Text(
                                    stringResource(R.string.head_gravity_wrong, suggested.joinToString { it.label }),
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
                        Text(stringResource(R.string.head_axis_layout), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.head_axis_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AxisMap.entries.forEach { m ->
                                FilterChip(selected = axes == m, onClick = { onAxes(m) }, label = { Text(m.text) })
                            }
                        }
                        Text(stringResource(R.string.head_display_delay, predictMs), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = predictMs.toFloat(),
                            onValueChange = { onPredict(it.toInt()) },
                            valueRange = 0f..40f,
                        )
                        Text(
                            stringResource(if (delayCalibrated) R.string.head_delay_calibrated else R.string.head_delay_not_calibrated),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(R.string.head_delay_howto),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDelayCheck(!delayCheck) }) { Text(stringResource(if (delayCheck) R.string.head_stop_check else R.string.head_check_delay)) }
                            OutlinedButton(onClick = onSaveRun, enabled = delayCheck) { Text(stringResource(R.string.head_save_run)) }
                            TextButton(onClick = onClearRuns) { Text(stringResource(R.string.head_clear_runs)) }
                        }
                        if (delayRuns.isNotEmpty()) {
                            Text(stringResource(R.string.head_runs, delayRuns.joinToString { "%.0f ms".format(it) }), style = MaterialTheme.typography.bodySmall)
                        }
                        timing?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.head_level), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (levelCalibrated) stringResource(R.string.head_level_calibrated, mountRollDeg, mountPitchDeg)
                            else stringResource(R.string.head_level_not_calibrated),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.head_level_howto),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onLevelCheck(!levelCheck) }) { Text(stringResource(if (levelCheck) R.string.head_stop_level else R.string.head_check_level)) }
                            OutlinedButton(onClick = onSaveLevel, enabled = levelCheck) { Text(stringResource(R.string.head_save_level)) }
                            TextButton(onClick = onClearLevel, enabled = levelCalibrated) { Text(stringResource(R.string.head_clear)) }
                        }
                        levelResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.head_row_pose), style = MaterialTheme.typography.bodyMedium)
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = rowPose2d, onClick = { onRowPose(false, !rowPose2d) }, label = { Text(stringResource(R.string.head_in_2d)) })
                            FilterChip(selected = rowPoseSbs, onClick = { onRowPose(true, !rowPoseSbs) }, label = { Text(stringResource(R.string.head_in_3d_sbs)) })
                            FilterChip(selected = lateWarpSbs, onClick = { onLateWarp(!lateWarpSbs) }, label = { Text(stringResource(R.string.head_late_warp)) })
                        }
                        Text(
                            stringResource(R.string.head_row_pose_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                Step(
                    stringResource(R.string.head_other_checks),
                    stringResource(R.string.head_chip, facts.soc),
                    listOf(
                        stringResource(
                            R.string.head_dynamic_sensors,
                            facts.dynamicSensors.ifEmpty { listOf(stringResource(R.string.head_none)) }.joinToString("; "),
                        ),
                        stringResource(R.string.head_protocol),
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
            Text(stringResource(R.string.head_other_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.head_other_explain),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(o.glasses?.let { stringResource(R.string.head_plugged_in, it) } ?: stringResource(R.string.head_no_other_glasses), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = o.onRecord, enabled = o.glasses != null && !o.recording && !(o.viture && o.vitureOn)) {
                Text(stringResource(if (o.recording) R.string.head_recording else R.string.head_record_20s))
            }
            o.motionLine?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
            if (o.viture) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Switch(checked = o.vitureOn, onCheckedChange = o.onViture)
                    Text(o.vitureLabel, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    stringResource(if (o.vitureBeast) R.string.head_viture_beast_note else R.string.head_viture_experimental_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = o.vitureAxes.flipUpDown,
                        onClick = { o.onVitureAxes(o.vitureAxes.copy(flipUpDown = !o.vitureAxes.flipUpDown)) },
                        label = { Text(stringResource(R.string.head_flip_up_down)) },
                        enabled = o.vitureOn,
                    )
                    FilterChip(
                        selected = o.vitureAxes.swapTurnTilt,
                        onClick = { o.onVitureAxes(o.vitureAxes.copy(swapTurnTilt = !o.vitureAxes.swapTurnTilt)) },
                        label = { Text(stringResource(R.string.head_swap_turn_tilt)) },
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
