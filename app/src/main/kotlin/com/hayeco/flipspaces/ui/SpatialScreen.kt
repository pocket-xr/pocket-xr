package com.hayeco.flipspaces.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.ModeChoice
import com.hayeco.flipspaces.core.spatial.SpatialMode
import com.hayeco.flipspaces.spatial.PanelHost
import com.hayeco.flipspaces.spatial.SpaceSnapshot
import kotlin.math.roundToInt

data class SpaceSettings(
    /** 0.5.26: the glasses' screen size in 2D and in their 3D mode (their Display size, 0.8 to 1.3), the projection's. */
    val screen2d: Float,
    val screen3d: Float,
    val handsSteer: Boolean,
    /** 0.5.9: side by side 3D when the glasses send 3840 x 1080, the eyes [ipdMm] apart. */
    val stereo: Boolean = true,
    val ipdMm: Float = 63f,
    /** 0.5.9, experimental: the glasses as a plain display with DeX off while the Space runs. */
    val plainDisplay: Boolean = false,
    /** 1.2.2: the focused window's glow, 0 (off) to 100 % (the look it always had). */
    val windowGlow: Int = com.hayeco.flipspaces.core.spatial.WindowGlow.DEFAULT_PERCENT,
    /** 1.2.2: the focused window's glow colour, opaque ARGB, the accent blue unless changed. */
    val windowGlowColor: Int = com.hayeco.flipspaces.core.spatial.WindowGlow.DEFAULT_COLOR,
)

@Composable
fun SpatialScreen(
    choice: ModeChoice,
    shizuku: ShizukuStatus,
    host: PanelHost.State,
    space: SpaceSnapshot,
    glassesName: String?,
    settings: SpaceSettings,
    message: String?,
    onMessageShown: () -> Unit,
    onOpenSpace: () -> Unit,
    onToggleLauncher: () -> Unit,
    onRemove: (Int) -> Unit,
    onRecentre: () -> Unit,
    onClear: () -> Unit,
    onSettings: (SpaceSettings) -> Unit,
    onReset: () -> Unit,
    onAllowShizuku: () -> Unit,
    onGetShizuku: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.home_spatial_windows), style = MaterialTheme.typography.headlineSmall) }
            item { ModeCard(choice, glassesName, space, onOpenSpace, onToggleLauncher, onRecentre) }
            if (shizuku != ShizukuStatus.READY) item { ShizukuCard(shizuku, onAllowShizuku, onGetShizuku) }
            if (host is PanelHost.State.Failed) {
                item { Problem(stringResource(R.string.home_panel_service), host.error) }
            }
            space.problem?.let { item { Problem(stringResource(R.string.home_last_problem), it) } }
            if (space.panels.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.home_panels_count, space.panels.size, 6), style = MaterialTheme.typography.titleMedium)
                            space.panels.forEach { p ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(p.status, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { onRemove(p.id) }) { Text(stringResource(R.string.home_close)) }
                                }
                            }
                            TextButton(onClick = onClear) { Text(stringResource(R.string.home_close_all_panels)) }
                            space.perf?.let { Text("Perf: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                        }
                    }
                }
            }
            item { SettingsCard(settings, onSettings) }
            // 0.5.101 (Mike's settings audit): the app list is gone from here (the launcher on the glasses and Advanced on
            // the main screen open apps); the plain display test is under Developer; and a reset to the defaults.
            item { DeveloperCard(settings, onSettings) }
            item { ResetButton(stringResource(R.string.home_spatial_windows), stringResource(R.string.home_spatial_reset_what), onReset) }
        }
    }
}

@Composable
private fun ModeCard(
    choice: ModeChoice,
    glassesName: String?,
    space: SpaceSnapshot,
    onOpenSpace: () -> Unit,
    onToggleLauncher: () -> Unit,
    onRecentre: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(choice.mode.label, style = MaterialTheme.typography.titleMedium)
            choice.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                glassesName?.let { stringResource(R.string.home_glasses_name, it) } ?: stringResource(R.string.home_glasses_not_connected),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.home_secure_note), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenSpace, enabled = glassesName != null && choice.mode != SpatialMode.FLAT) {
                    Text(if (space.running) stringResource(R.string.home_show_space) else stringResource(R.string.home_open_space))
                }
                OutlinedButton(onClick = onRecentre) { Text(stringResource(R.string.home_recentre)) }
            }
            if (space.running) {
                OutlinedButton(onClick = onToggleLauncher) { Text(if (space.launcherOpen) stringResource(R.string.home_close_launcher) else stringResource(R.string.home_open_launcher)) }
            }
        }
    }
}

@Composable
private fun ShizukuCard(status: ShizukuStatus, onAllow: () -> Unit, onGet: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.home_shizuku_setup_title), style = MaterialTheme.typography.titleMedium)
            Text(status.label + ".", style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.home_shizuku_why),
                style = MaterialTheme.typography.bodySmall,
            )
            listOf(
                stringResource(R.string.home_shizuku_step1),
                stringResource(R.string.home_shizuku_step2),
                stringResource(R.string.home_shizuku_step3),
                stringResource(R.string.home_shizuku_step4),
                stringResource(R.string.home_shizuku_step5),
                stringResource(R.string.home_shizuku_step6),
                stringResource(R.string.home_shizuku_after_reboot),
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status == ShizukuStatus.NOT_INSTALLED) OutlinedButton(onClick = onGet) { Text(stringResource(R.string.home_get_shizuku)) }
                if (status == ShizukuStatus.NO_PERMISSION) Button(onClick = onAllow) { Text(stringResource(R.string.home_allow_pocket_xr)) }
            }
        }
    }
}

/** 0.5.26: the degrees a 1920 x 1080 picture fills at the glasses' screen size [factor]. */
private fun degreesOf(factor: Float): Pair<Float, Float> =
    com.hayeco.flipspaces.core.spatial.GlassesScreen(factor, 1f, "").degrees()

@Composable
private fun SettingsCard(settings: SpaceSettings, onChange: (SpaceSettings) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.home_space_settings), style = MaterialTheme.typography.titleMedium)
            // 0.5.26: the projection is the glasses' own screen; its size is theirs to set.
            val steps = com.hayeco.flipspaces.core.spatial.GlassesScreen.STEPS
            Text(stringResource(R.string.home_screen_size_2d, settings.screen2d * 100, degreesOf(settings.screen2d).first, degreesOf(settings.screen2d).second), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = steps.indexOf(com.hayeco.flipspaces.core.spatial.GlassesScreen.nearestStep(settings.screen2d)).toFloat(),
                onValueChange = { onChange(settings.copy(screen2d = steps[it.toInt().coerceIn(0, steps.size - 1)])) },
                valueRange = 0f..(steps.size - 1).toFloat(),
                steps = steps.size - 2,
            )
            Text(stringResource(R.string.home_screen_size_3d, settings.screen3d * 100, degreesOf(settings.screen3d).first, degreesOf(settings.screen3d).second), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = steps.indexOf(com.hayeco.flipspaces.core.spatial.GlassesScreen.nearestStep(settings.screen3d)).toFloat(),
                onValueChange = { onChange(settings.copy(screen3d = steps[it.toInt().coerceIn(0, steps.size - 1)])) },
                valueRange = 0f..(steps.size - 1).toFloat(),
                steps = steps.size - 2,
            )
            Text(
                stringResource(R.string.home_screen_size_help),
                style = MaterialTheme.typography.bodySmall,
            )
            Toggle(stringResource(R.string.home_stereo), settings.stereo) { onChange(settings.copy(stereo = it)) }
            Text(
                stringResource(R.string.home_stereo_help, settings.ipdMm),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = settings.ipdMm,
                onValueChange = { onChange(settings.copy(ipdMm = it)) },
                valueRange = com.hayeco.flipspaces.core.spatial.StereoLayout.MIN_IPD_MM..com.hayeco.flipspaces.core.spatial.StereoLayout.MAX_IPD_MM,
            )
            // 1.2.2 (a user: "reduce opacity or intensity of the blue drop shadow around the windows").
            Text(windowGlowLabel(settings.windowGlow, LocalContext.current), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = settings.windowGlow.toFloat(),
                onValueChange = { onChange(settings.copy(windowGlow = windowGlowStep(it))) },
                valueRange = com.hayeco.flipspaces.core.spatial.WindowGlow.MIN_PERCENT.toFloat()..com.hayeco.flipspaces.core.spatial.WindowGlow.MAX_PERCENT.toFloat(),
                steps = WINDOW_GLOW_STEPS,
            )
            Text(
                stringResource(R.string.home_window_glow_help),
                style = MaterialTheme.typography.bodySmall,
            )
            GlowColorChoice(settings.windowGlowColor) { onChange(settings.copy(windowGlowColor = it)) }
            Text(stringResource(R.string.home_hand_pointer), style = MaterialTheme.typography.bodyMedium)
            Choice(stringResource(R.string.home_hand_ray), settings.handsSteer) { onChange(settings.copy(handsSteer = true)) }
            Choice(stringResource(R.string.home_look_and_pinch), !settings.handsSteer) { onChange(settings.copy(handsSteer = false)) }
            Text(
                stringResource(R.string.home_look_and_pinch_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 1.2.2: Window glow's slider stops between off and full, every 10 %. */
internal const val WINDOW_GLOW_STEPS = 9

/** 1.2.2: the slider's position [value] as a Window glow percent, on its 10 % stops. */
internal fun windowGlowStep(value: Float): Int =
    com.hayeco.flipspaces.core.spatial.WindowGlow.clamp((value / 10f).roundToInt() * 10)

/** 1.2.2: Window glow's title at [percent], in the phone's language given a [context] (the English without, for the tests). */
internal fun windowGlowLabel(percent: Int, context: android.content.Context? = null): String =
    if (com.hayeco.flipspaces.core.spatial.WindowGlow.shown(percent)) {
        context?.getString(R.string.home_window_glow_percent, percent) ?: "Window glow: $percent %"
    } else {
        context?.getString(R.string.home_window_glow_off) ?: "Window glow: Off"
    }

/** 1.2.2: Window glow colour's title for [argb], in the phone's language given a [context] (the English without). */
internal fun windowGlowColorLabel(argb: Int, context: android.content.Context? = null): String =
    com.hayeco.flipspaces.core.spatial.WindowGlow.name(argb).let { name ->
        context?.getString(R.string.home_glow_colour, name) ?: "Glow colour: $name"
    }

/**
 * 1.2.2 (Mike: users should be able to change the glow's colour): the preset swatches, the default blue first, and a
 * Custom swatch that opens hue and colour sliders. Each change reaches a running Space at once, like the intensity.
 */
@Composable
private fun GlowColorChoice(argb: Int, onChange: (Int) -> Unit) {
    val glow = com.hayeco.flipspaces.core.spatial.WindowGlow
    var customOpen by rememberSaveable { mutableStateOf(glow.preset(argb) == null) }
    // The hue is kept here too, so taking the colour down to white and back up does not lose it.
    var hue by rememberSaveable { mutableStateOf(glow.hueSaturation(argb).first) }
    var saturation by rememberSaveable { mutableStateOf(glow.hueSaturation(argb).second) }
    Text(windowGlowColorLabel(argb, LocalContext.current), style = MaterialTheme.typography.bodyMedium)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        glow.PRESETS.forEach { s ->
            GlowSwatch(s.name, SolidColor(Color(s.argb)), glow.preset(argb) == s) {
                customOpen = false
                onChange(s.argb)
            }
        }
        GlowSwatch(stringResource(R.string.home_custom), Brush.sweepGradient(RAINBOW), glow.preset(argb) == null) {
            customOpen = true
            val (h, sat) = glow.hueSaturation(argb)
            if (sat > 0f) hue = h
            saturation = sat
        }
    }
    if (customOpen || glow.preset(argb) == null) {
        Text(stringResource(R.string.home_hue), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = hue,
            onValueChange = { hue = it; onChange(glow.custom(hue, saturation)) },
            valueRange = 0f..360f,
        )
        Text(stringResource(R.string.home_colour_saturation), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = saturation,
            onValueChange = { saturation = it; onChange(glow.custom(hue, saturation)) },
            valueRange = 0f..1f,
        )
    }
}

private val RAINBOW = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

@Composable
private fun GlowSwatch(name: String, fill: Brush, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(fill)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                CircleShape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
    )
}

/** 0.5.101: tests for developers, closed until opened. */
@Composable
private fun DeveloperCard(settings: SpaceSettings, onChange: (SpaceSettings) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_developer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (open) stringResource(R.string.home_hide) else stringResource(R.string.home_show), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (open) {
                Toggle(stringResource(R.string.home_plain_display), settings.plainDisplay) { onChange(settings.copy(plainDisplay = it)) }
                Text(
                    stringResource(R.string.home_plain_display_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Problem(title: String, text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
