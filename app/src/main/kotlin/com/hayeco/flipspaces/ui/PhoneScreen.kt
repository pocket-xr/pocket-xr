package com.hayeco.flipspaces.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.ContentMode
import com.hayeco.flipspaces.core.GlassesState
import com.hayeco.flipspaces.core.HostingAssessment
import com.hayeco.flipspaces.core.HostingAssessor
import com.hayeco.flipspaces.core.Look
import com.hayeco.flipspaces.core.SettingsTarget
import com.hayeco.flipspaces.core.spatial.FlatBanner
import com.hayeco.flipspaces.core.spatial.FlatFix

/*
 * 0.5.87 (Pocket XR; Mike: "do those now", the phone's settings screens of the redesign): the app's home on the phone. It was a
 * long engineering list (the window slot map, adb commands, every app with its package name). Now:
 * the name and icon with the glasses' status and the two things done here (open the trackpad, show Pocket XR on the
 * glasses); a settings list; and everything for setting up or debugging under Advanced, closed until opened. Cards are the
 * tokens' glass with a hairline rim ([Look]).
 */
@Composable
fun PhoneScreen(
    glasses: GlassesState,
    hosting: HostingAssessment?,
    settingsTarget: SettingsTarget?,
    apps: List<AppEntry>,
    query: String,
    message: String?,
    banner: FlatBanner?,
    onFix: (FlatFix) -> Unit,
    iconLoader: IconLoader,
    onQueryChange: (String) -> Unit,
    onMessageShown: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenSettings: (SettingsTarget) -> Unit,
    onRetry: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onOpenHands: () -> Unit,
    onOpenTrackpad: () -> Unit,
    onOpenTrackpadSettings: () -> Unit,
    onOpenSpatial: () -> Unit,
    onOpenHeadTracking: () -> Unit,
    onGlassesReport: () -> Unit,
    onSendLogs: () -> Unit = {},
    onGlassesCheck: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val connected = glasses is GlassesState.Connected
    // 0.5.97: the in-app update check ([com.hayeco.flipspaces.UpdateChecker]).
    val context = androidx.compose.ui.platform.LocalContext.current
    // 1.1.15: the banner, unless put off with Later until the next version.
    val update by com.hayeco.flipspaces.UpdateChecker.banner.collectAsState()
    var checkUpdates by remember { mutableStateOf(com.hayeco.flipspaces.UpdateChecker.enabled(context)) }
    val updateStatus by com.hayeco.flipspaces.UpdateChecker.status.collectAsState()
    // 1.1.14 (a tester asked for a way to turn head tracking on and off): saved, applied at once.
    var headTracking by remember { mutableStateOf(com.hayeco.flipspaces.spatial.SpaceStore(context).headTracking) }
    var recentreFollowsTilt by remember { mutableStateOf(com.hayeco.flipspaces.spatial.SpaceStore(context).recentreFollowsTilt) }
    // 1.4.0: Steady mode in 6DoF, shown for the glasses that can hold it (the Aura).
    var steadySixDof by remember { mutableStateOf(com.hayeco.flipspaces.spatial.SpaceStore(context).steadySixDof) }
    val sixDofGlasses = remember { com.hayeco.flipspaces.core.spatial.SteadyMode.sixDofOn(com.hayeco.flipspaces.spatial.SpaceStore(context).model) }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1.1.15: a newer version first on the screen, with Update and Later.
            update?.let { u ->
                item {
                    GlassCard(container = Color(Look.GLASS_RAISED)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(com.hayeco.flipspaces.core.UpdateCheck.bannerLine(u.version), style = MaterialTheme.typography.titleMedium)
                            u.notes.lineSequence().map { it.trim().trimStart('#', '*', '-').trim() }.firstOrNull { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { com.hayeco.flipspaces.UpdateChecker.open(context, u) }, shape = RoundedCornerShape(22.dp)) { Text("Update") }
                                OutlinedButton(onClick = { com.hayeco.flipspaces.UpdateChecker.later(context) }, shape = RoundedCornerShape(22.dp)) { Text("Later") }
                            }
                        }
                    }
                }
            }
            item { Header(connected) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onOpenTrackpad, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) { Text("Open trackpad") }
                    if (connected) {
                        OutlinedButton(onClick = onOpenHome, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) { Text("Show on glasses") }
                    }
                }
            }
            // 1.1.11: not on a copy from Google Play or F-Droid, which bring their own updates. 1.1.15: the version and Check
            // for updates moved here from the end of the settings list, where few found them.
            if (com.hayeco.flipspaces.UpdateChecker.offered(context)) {
                item {
                    GlassCard {
                        ToggleRow("Check for updates", "Version ${com.hayeco.flipspaces.UpdateChecker.installed}. ${updateLine(checkUpdates, updateStatus)}", checkUpdates) {
                            checkUpdates = it
                            com.hayeco.flipspaces.UpdateChecker.setEnabled(context, it)
                        }
                        if (checkUpdates && updateStatus?.result != com.hayeco.flipspaces.UpdateChecker.Result.CHECKING) {
                            TextButton(
                                onClick = { com.hayeco.flipspaces.UpdateChecker.checkIfDue(context, force = true) },
                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                            ) { Text("Check now") }
                        }
                    }
                }
            }
            banner?.let { b -> item { FlatBannerCard(b, onFix) } }
            item { GlassesCard(glasses, hosting) }
            if (hosting?.mode == ContentMode.MIRROR) {
                item { MirrorCard(hosting, settingsTarget, onOpenSettings, onRetry) }
            }
            item { SectionTitle("Settings") }
            item {
                GlassCard {
                    // 0.5.101 (Mike: each settings screen only what is needed, each with a reset): Track two hands and
                    // Calibrate hand aim are on the Hands screen only; Trackpad opens the trackpad's own Menu, where its
                    // settings are, instead of repeating the Open trackpad button.
                    NavRow("Spatial windows", "Screen size, 3D, eye distance, window glow and colour, hand pointer; Shizuku setup", onOpenSpatial)
                    RowDivider()
                    ToggleRow(
                        "Head tracking",
                        if (headTracking) "On: windows stay put as you turn your head" else "Off: windows stay in front of you and turn with your head",
                        headTracking,
                    ) {
                        headTracking = it
                        com.hayeco.flipspaces.spatial.HeadTracker.setEnabled(context, it)
                    }
                    RowDivider()
                    // 1.2.13 (Discussion #81): a recenter lying back or looking down puts the windows where you look.
                    ToggleRow(
                        "Recenter follows head tilt",
                        if (recentreFollowsTilt) "On: recenter puts the windows where you look, up or down" else "Off: recenter keeps the windows on the horizon",
                        recentreFollowsTilt,
                    ) {
                        recentreFollowsTilt = it
                        com.hayeco.flipspaces.spatial.HeadTracker.setRecentreFollowsTilt(context, it)
                    }
                    RowDivider()
                    if (sixDofGlasses) {
                        // 1.4.0 (docs/6DOF-PLAN.md): the Aura's Spatial Anchor holds the Steady mode screen in 6DoF.
                        ToggleRow(
                            "Steady mode in 6DoF",
                            if (steadySixDof) "On: in Steady mode the wide screen stays put as you lean and move. The glasses' Spatial Anchor does it, and 3D is off"
                            else "Off: Steady mode keeps the wide screen still as you turn your head",
                            steadySixDof,
                        ) {
                            steadySixDof = it
                            com.hayeco.flipspaces.spatial.SpaceStore(context).steadySixDof = it
                        }
                        RowDivider()
                    }
                    // 1.1.16 (issue 12): the settings these glasses need and the head turn check, at any time.
                    NavRow("Glasses setup check", "The settings your glasses need, and a 10 second check that windows stay put", onGlassesCheck)
                    RowDivider()
                    NavRow("Hands", "Hand tracking, calibrating hand aim, gestures", onOpenHands)
                    RowDivider()
                    NavRow("Trackpad", "Cursor and scroll speeds, scroll strip, window curve, in its Menu", onOpenTrackpadSettings)
                    // 1.1.14: the logs to Mike by email, the one line saying what goes.
                    RowDivider()
                    NavRow(com.hayeco.flipspaces.core.LogBundle.BUTTON, com.hayeco.flipspaces.core.LogBundle.WHAT_IS_SENT, onSendLogs)
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Advanced", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(if (advanced) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (advanced) {
                item {
                    GlassCard {
                        NavRow("Head tracking diagnostics", "The glasses' sensor stream, delay check and levels", onOpenHeadTracking)
                        RowDivider()
                        // 0.5.102 (Mike): from the trackpad's Menu to here.
                        NavRow("Glasses report", "What these glasses report and what worked, saved to share", onGlassesReport)
                    }
                }
                // 0.5.101 (Mike): the freeform setup card is gone (it asked for adb settings only the old flat DeX windows
                // used, so it said "incomplete" while the Space worked), and so is the window slots card (DeX windows'
                // slots; windows are placed and arranged in the Space).
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        label = { Text("Open an app on the glasses") },
                    )
                }
                items(apps, key = { it.key }) { app ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onLaunch(app) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app, 40.dp, iconLoader)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            // 1.1.15: the Ko-fi card that sat here is now the first row of the trackpad's Menu.
        }
    }
}

@Composable
private fun Header(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Pocket XR", style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (connected) Color(Look.ACCENT) else Color(Look.INK_3))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (connected) "Glasses connected" else "Plug in your glasses to begin",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(color) }
}

/** 0.5.87: the tokens' card (Look.CARD) with the hairline rim (Look.RIM). */
@Composable
private fun GlassCard(container: Color = Color(Look.CARD), content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Look.RADIUS_L.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, Color(Look.RIM)),
    ) {
        Column { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun RowDivider() = HorizontalDivider(color = Color(Look.RIM), modifier = Modifier.padding(horizontal = 16.dp))

@Composable
private fun NavRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Why the glasses show the flat DeX home instead of the Space, with the one-tap fix. Shared with the glasses home. */
@Composable
fun FlatBannerCard(banner: FlatBanner, onFix: (FlatFix) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Look.RADIUS_L.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(banner.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(banner.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            banner.fix?.let { fix -> OutlinedButton(onClick = { onFix(fix) }) { Text(fix.buttonLabel) } }
        }
    }
}

@Composable
private fun GlassesCard(glasses: GlassesState, hosting: HostingAssessment?) {
    GlassCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (glasses) {
                is GlassesState.Connected -> {
                    Text(glasses.display.kindLabel, style = MaterialTheme.typography.titleMedium)
                    Text(glasses.display.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    hosting?.let { Text("Mode: ${it.mode.label}", style = MaterialTheme.typography.bodyMedium) }
                    if (hosting?.dexOwnsDisplay == true) {
                        Text("Samsung DeX is running the glasses' display.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is GlassesState.NotConnected -> {
                    Text("No glasses yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Plug your XR glasses straight into the phone's USB-C port. If the glasses show a copy of the phone and " +
                            "nothing appears here, the phone is mirroring: turn on its external display mode (on Samsung, " +
                            "DeX on an external display).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MirrorCard(
    hosting: HostingAssessment,
    settingsTarget: SettingsTarget?,
    onOpenSettings: (SettingsTarget) -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Look.RADIUS_L.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("The glasses are mirroring the phone", style = MaterialTheme.typography.titleMedium)
            Text(HostingAssessor.MIRROR_MESSAGE, style = MaterialTheme.typography.bodyMedium)
            hosting.lastError?.let {
                Text("Android said:", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            settingsTarget?.let { target ->
                Text(target.hint, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onOpenSettings(target) }) { Text(target.buttonLabel) }
            }
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** 0.5.101: the update check's line: what the last check found and when, or that it is off. */
private fun updateLine(on: Boolean, status: com.hayeco.flipspaces.UpdateChecker.Status?): String {
    if (!on) return "Off. Turn on to hear about new versions on GitHub Releases"
    val s = status ?: return "Every 12 hours, on GitHub Releases"
    val now = System.currentTimeMillis()
    val ago = if (now - s.atMs < 60_000L) "just now" else android.text.format.DateUtils.getRelativeTimeSpanString(s.atMs, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
    return when (s.result) {
        com.hayeco.flipspaces.UpdateChecker.Result.CHECKING -> "Checking GitHub Releases..."
        com.hayeco.flipspaces.UpdateChecker.Result.UP_TO_DATE -> "Up to date. Checked $ago"
        com.hayeco.flipspaces.UpdateChecker.Result.AVAILABLE -> "A new version is available. Checked $ago"
        com.hayeco.flipspaces.UpdateChecker.Result.NO_RELEASES -> "No releases published yet. Checked $ago"
        com.hayeco.flipspaces.UpdateChecker.Result.FAILED -> "Could not reach GitHub. Tried $ago"
    }
}
