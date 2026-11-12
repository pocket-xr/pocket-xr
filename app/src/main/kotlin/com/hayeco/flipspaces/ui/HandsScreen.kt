package com.hayeco.flipspaces.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.hands.HandProviderKind
import com.hayeco.flipspaces.core.hands.ProviderStatus
import com.hayeco.flipspaces.hands.GlassesCameraProvider

/** Everything the setup screen shows, gathered by HandsActivity. */
data class HandsSetup(
    val statuses: Map<HandProviderKind, ProviderStatus>,
    val active: HandProviderKind,
    /** 1.1.0: the Pocket XR add-on, which holds the pointer service, is installed. */
    val addonInstalled: Boolean = true,
    val serviceEnabled: Boolean,
    val serviceConnected: Boolean,
    val glassesDisplayId: Int?,
    val problem: String?,
    val usbDevice: String?,
    val usbAllowed: Boolean,
    val cameraAllowed: Boolean,
    val modelReady: Boolean,
    val downloading: Boolean,
    val report: String?,
    val stats: String?,
    val preview: GlassesCameraProvider.Preview?,
    val mirror: Boolean,
    val message: String?,
    /** 0.5.0: hands start by themselves with the glasses. */
    val autoStart: Boolean = false,
    /** 0.5.1: aim with 0.5.0's shoulder ray, an option; off is the camera ray through the stable pinch point (0.5.2). */
    val shoulderRay: Boolean = false,
    /** 0.5.4: track two hands (slower) instead of one. */
    val twoHands: Boolean = false,
    /** 0.5.0: show the one time "Always open Pocket XR" hint. */
    val usbHint: Boolean = false,
    /** 0.5.0: the saved hand calibration, in words. */
    val calibration: String = "Not calibrated",
    /** 0.5.1: RECORD_AUDIO is granted, so Android offers "Always" on the plug-in prompt. */
    val micAllowed: Boolean = false,
    /** 0.5.1: what the plug-in prompt hint says now (core UsbAsk.hint). */
    val usbHintText: String = "",
)

@Composable
fun HandsScreen(
    setup: HandsSetup,
    onMessageShown: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    /** 1.1.0: opens where to get the Pocket XR add-on. */
    onGetAddon: () -> Unit = {},
    onAskCamera: () -> Unit,
    onAskUsb: () -> Unit,
    onDownloadModel: () -> Unit,
    onReadFormats: () -> Unit,
    onStartHands: () -> Unit,
    onStopHands: () -> Unit,
    onRefresh: () -> Unit,
    onAutoStartChange: (Boolean) -> Unit = {},
    onShoulderRayChange: (Boolean) -> Unit = {},
    onAllowMic: () -> Unit = {},
    onUsbHintDone: () -> Unit = {},
    onCalibrate: () -> Unit = {},
    onResetCalibration: () -> Unit = {},
    onResetAll: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(setup.message) {
        setup.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.hands_title), style = MaterialTheme.typography.headlineMedium) }
            // 0.5.1: first, so its reason is on screen when Android asks for the microphone.
            if (!setup.micAllowed) item { UsbHintCard(setup, onAllowMic, onUsbHintDone) }
            item { PointerServiceCard(setup, onOpenAccessibility, onOpenAppInfo, onGetAddon) }
            // 0.5.101 (Mike's settings audit): the providers list, camera formats and the shoulder ray are under Developer;
            // the mirror and Track two hands switches are gone (the glasses' camera faces out, and two hands have been
            // always on since 0.5.37); the trackpad card went to the trackpad's own Menu; the gestures are the Space's.
            item { GlassesCameraCard(setup, onAskCamera, onAskUsb, onDownloadModel, onStartHands, onStopHands) }
            if (setup.usbHint && setup.micAllowed) item { UsbHintCard(setup, onAllowMic, onUsbHintDone) }
            item { HandsAutoCard(setup, onAutoStartChange, onCalibrate, onResetCalibration) }
            item { GesturesCard() }
            item { HandsDeveloperCard(setup, onRefresh, onReadFormats, onShoulderRayChange) }
            item {
                ResetButton(
                    stringResource(R.string.hands_title),
                    stringResource(R.string.hands_reset_text),
                    onResetAll,
                )
            }
        }
    }
}

/**
 * The plug-in prompt, the only USB grant Android keeps across unplugs. 0.5.1: Android shows its
 * "Always" box only once Pocket XR has the microphone permission (the glasses have a
 * microphone), so the card asks for that first, then says to tick Always on the single prompt.
 */
@Composable
private fun UsbHintCard(setup: HandsSetup, onAllowMic: () -> Unit, onDone: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(if (setup.micAllowed) R.string.hands_tick_always else R.string.hands_remember_glasses), style = MaterialTheme.typography.titleMedium)
            if (!setup.micAllowed) {
                Text(
                    stringResource(R.string.hands_mic_reason),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(setup.usbHintText, style = MaterialTheme.typography.bodySmall)
            if (setup.micAllowed) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.hands_got_it)) }
            } else {
                Button(onClick = onAllowMic) { Text(stringResource(R.string.hands_allow_mic)) }
            }
        }
    }
}

/** 0.5.0: hands that start by themselves, the hand ray and its calibration. */
@Composable
private fun HandsAutoCard(
    setup: HandsSetup,
    onAutoStartChange: (Boolean) -> Unit,
    onCalibrate: () -> Unit,
    onResetCalibration: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.hands_with_glasses), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.hands_start_with_glasses), Modifier.weight(1f))
                Switch(checked = setup.autoStart, onCheckedChange = onAutoStartChange)
            }
            Text(
                stringResource(R.string.hands_auto_note),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.hands_cursor_note),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.hands_calibrate_state, setup.calibration), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.hands_calibrate_howto),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCalibrate) { Text(stringResource(R.string.hands_calibrate)) }
                OutlinedButton(onClick = onResetCalibration) { Text(stringResource(R.string.hands_reset_calibration)) }
            }
        }
    }
}

@Composable
private fun PointerServiceCard(setup: HandsSetup, onOpenAccessibility: () -> Unit, onOpenAppInfo: () -> Unit, onGetAddon: () -> Unit) {
    val ok = setup.serviceConnected
    Card(
        Modifier.fillMaxWidth(),
        colors = if (ok) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.hands_pointer_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.hands_pointer_why),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.hands_pointer_does),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    !setup.addonInstalled -> stringResource(R.string.hands_addon_missing)
                    ok && setup.glassesDisplayId != null -> stringResource(R.string.hands_on_display, setup.glassesDisplayId)
                    ok -> stringResource(R.string.hands_on_no_display)
                    setup.serviceEnabled -> stringResource(R.string.hands_switched_on)
                    else -> stringResource(R.string.hands_off)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            setup.problem?.let { Text(stringResource(R.string.hands_last_problem, it), style = MaterialTheme.typography.bodySmall) }
            if (!setup.addonInstalled) {
                Button(onClick = onGetAddon) { Text(stringResource(R.string.hands_get_addon)) }
                return@Column
            }
            Button(onClick = onOpenAccessibility) { Text(stringResource(R.string.hands_open_accessibility)) }
            if (!ok) {
                Text(
                    stringResource(R.string.hands_restricted),
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(
                        "adb shell settings put secure enabled_accessibility_services com.hayeco.flipspaces.addon/.PointerAccess\n" +
                            "adb shell settings put secure accessibility_enabled 1",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onOpenAppInfo) { Text(stringResource(R.string.hands_addon_app_info)) }
            }
        }
    }
}

@Composable
private fun describe(status: ProviderStatus?): String = when (status) {
    null -> stringResource(R.string.hands_checking)
    is ProviderStatus.Unavailable -> stringResource(R.string.hands_not_available, status.reason)
    is ProviderStatus.NeedsPermission -> stringResource(R.string.hands_needs, status.what)
    ProviderStatus.Ready -> stringResource(R.string.hands_ready)
    is ProviderStatus.Running -> stringResource(R.string.hands_running, status.detail)
    is ProviderStatus.Failed -> stringResource(R.string.hands_failed, status.error)
}

@Composable
private fun GlassesCameraCard(
    setup: HandsSetup,
    onAskCamera: () -> Unit,
    onAskUsb: () -> Unit,
    onDownloadModel: () -> Unit,
    onStartHands: () -> Unit,
    onStopHands: () -> Unit,
) {
    val status = setup.statuses[HandProviderKind.GLASSES_CAMERA]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.hands_camera_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.hands_camera_explain),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.hands_usb_camera, setup.usbDevice ?: stringResource(R.string.hands_not_found)), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!setup.cameraAllowed) Button(onClick = onAskCamera) { Text(stringResource(R.string.hands_allow_camera)) }
                if (setup.usbDevice != null && !setup.usbAllowed) Button(onClick = onAskUsb) { Text(stringResource(R.string.hands_allow_usb)) }
                if (!setup.modelReady) {
                    Button(onClick = onDownloadModel, enabled = !setup.downloading) {
                        Text(stringResource(if (setup.downloading) R.string.hands_downloading else R.string.hands_download_model))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status is ProviderStatus.Running) {
                    Button(onClick = onStopHands) { Text(stringResource(R.string.hands_stop)) }
                } else {
                    Button(onClick = onStartHands, enabled = status == ProviderStatus.Ready || status is ProviderStatus.Failed) {
                        Text(stringResource(R.string.hands_start))
                    }
                }
            }
            setup.stats?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            setup.preview?.let { CameraPreview(it, setup.mirror) }
        }
    }
}

@Composable
private fun CameraPreview(preview: GlassesCameraProvider.Preview, mirror: Boolean) {
    val bitmap = preview.frame
    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))) {
        Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.hands_camera_preview), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize()) {
            val box = GlassesCameraProvider.airBox
            drawRect(
                Color(0x8800E5FF),
                topLeft = Offset(box.left * size.width, box.top * size.height),
                size = androidx.compose.ui.geometry.Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
            )
            preview.hand?.landmarks?.forEach { p ->
                drawCircle(Color(0xFF5ADCFF), radius = 5f, center = Offset(p.x * size.width, p.y * size.height))
            }
        }
        if (mirror) Text(stringResource(R.string.hands_mirrored), Modifier.align(Alignment.BottomStart).padding(4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/** 0.5.101: the Space's hand gestures (the DeX era's dock and title bars are gone). */
@Composable
private fun GesturesCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.hands_gestures), style = MaterialTheme.typography.titleMedium)
            listOf(
                R.string.hands_gesture_point,
                R.string.hands_gesture_click,
                R.string.hands_gesture_move,
                R.string.hands_gesture_resize,
                R.string.hands_gesture_scroll,
                R.string.hands_gesture_menu,
                R.string.hands_gesture_all,
            ).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** 0.5.101: what developers check, closed until opened: the input providers, the camera's formats, the shoulder ray. */
@Composable
private fun HandsDeveloperCard(setup: HandsSetup, onRefresh: () -> Unit, onReadFormats: () -> Unit, onShoulderRayChange: (Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.hands_developer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(stringResource(if (open) R.string.hands_hide else R.string.hands_show), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (open) {
                Text(stringResource(R.string.hands_driven_by, setup.active.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                HandProviderKind.entries.forEach { kind ->
                    Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                    Text(describe(setup.statuses[kind]), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.hands_check_again)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.hands_shoulder_ray), Modifier.weight(1f))
                    Switch(checked = setup.shoulderRay, onCheckedChange = onShoulderRayChange)
                }
                if (setup.usbDevice != null && setup.usbAllowed) OutlinedButton(onClick = onReadFormats) { Text(stringResource(R.string.hands_read_formats)) }
                setup.report?.let {
                    Text(stringResource(R.string.hands_camera_offers), style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
