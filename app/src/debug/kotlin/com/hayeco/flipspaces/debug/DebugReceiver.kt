package com.hayeco.flipspaces.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hayeco.flipspaces.core.DisplaySelector
import com.hayeco.flipspaces.core.hands.ReplayCodec
import com.hayeco.flipspaces.core.hands.ReplayFormatException
import com.hayeco.flipspaces.core.hands.ReplayFrame
import com.hayeco.flipspaces.core.hands.ReplayScenarios
import com.hayeco.flipspaces.core.hands.SyntheticHands
import com.hayeco.flipspaces.hands.GlassesCameraProvider
import com.hayeco.flipspaces.hands.HandFeed
import com.hayeco.flipspaces.platform.AppLauncher
import com.hayeco.flipspaces.platform.DisplayMonitor
import com.hayeco.flipspaces.spatial.SpaceActivity
import com.hayeco.flipspaces.spatial.SpaceSession
import java.io.File

/**
 * Debug builds only: adb commands for testing on an emulator without the glasses.
 * Protected by android.permission.DUMP, which adb shell holds.
 *
 * - REPLAY: plays a replay file (`--es file <path>`) or a built-in scenario (`--es scenario
 *   <name>`, see core's ReplayScenarios) into the hand pipeline. Scenario parameters are display
 *   pixels (`--ef x`, `y`, `x2`, `y2`, `gap`, `gap2`) or, in the Space, panel coordinates
 *   (`--ef u`, `v`, `u2`, `v2`, optional `--ei panel <id>`, default the front panel). Also
 *   `--ei latency <ms>` (default 37) and `--ei repeat <n>`.
 * - REPLAY_STOP: stops a running replay.
 * - REPLAY_DUMP: writes a scenario as a replay file to edit (`--es out <path>`, default the
 *   app's external files folder).
 * - OPEN: opens the Spaces home or a Space (`--es what home|space`) on `--ei display <id>`,
 *   default the largest external display. `--ez presentation true` (0.5.1) draws the Space in
 *   its Presentation fallback; `false` turns that off again.
 * - STALL (0.5.3): the Space's render thread sleeps once for `--ei ms <n>` (default 6000, at most
 *   60000), so the frame stall watchdog can be watched shedding, releasing and recovering.
 * - ADD_APP (1.1.1): opens `--es pkg <package>`'s launcher activity as a window in the Space, as the launcher does, so
 *   panels can be tested on an emulator without aiming pinches at the launcher.
 * - POSITION (1.4.0): plays a scripted head position (`--es scenario sway|lean|square|lost`, `--ei repeat <n>`) as a
 *   6DoF source, so Space locked (6DoF) can be tested without 6DoF glasses. POSITION_STOP stops it.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                ACTION_REPLAY -> replay(context, intent)
                ACTION_STOP -> HandReplayPlayer.stop()
                ACTION_DUMP -> dump(context, intent)
                ACTION_OPEN -> open(context, intent)
                ACTION_RIG -> GameRigActivity.running?.command(intent) ?: rigless(context, intent)
                ACTION_ADD_APP -> addApp(context, intent)
                ACTION_POSITION -> {
                    val name = intent.getStringExtra("scenario") ?: "sway"
                    val scenario = com.hayeco.flipspaces.core.spatial.PositionScenario.named(name)
                    if (scenario == null) Log.w(TAG, "No position scenario \"$name\": ${com.hayeco.flipspaces.core.spatial.PositionScenario.entries.joinToString { it.name.lowercase() }}")
                    else PositionReplayPlayer.play(scenario, number(intent, "repeat")?.toInt() ?: 1)
                }
                ACTION_POSITION_STOP -> PositionReplayPlayer.stop()
                ACTION_STALL -> com.hayeco.flipspaces.spatial.SpaceSession.debugStallMs = (number(intent, "ms")?.toLong() ?: 6_000L).coerceIn(0L, 60_000L)
                // 0.5.101: the update check against another repository's latest release ("url"; none: back to Pocket XR's).
                ACTION_UPDATE_TEST -> {
                    com.hayeco.flipspaces.UpdateChecker.testUrl = intent.getStringExtra("url")
                    com.hayeco.flipspaces.UpdateChecker.checkIfDue(context, force = true)
                    Log.i(TAG, "update check against ${com.hayeco.flipspaces.UpdateChecker.testUrl ?: "Pocket XR's releases"}")
                }
            }
        } catch (e: ReplayFormatException) {
            Log.w(TAG, "Replay file not understood: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "${intent.action} failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun replay(context: Context, intent: Intent) {
        val (name, frames) = frames(context, intent) ?: return
        HandReplayPlayer.play(context, name, frames, number(intent, "latency")?.toLong() ?: 37L, number(intent, "repeat")?.toInt() ?: 1)
    }

    private fun dump(context: Context, intent: Intent) {
        val (name, frames) = frames(context, intent) ?: return
        val out = intent.getStringExtra("out")?.let(::File) ?: File(context.getExternalFilesDir(null), "$name.replay.txt")
        out.writeText(ReplayCodec.encode(frames))
        Log.i(TAG, "Wrote ${frames.size} frames of $name to ${out.absolutePath}")
    }

    /** The replay an intent asks for: a file, or a scenario with its parameters. */
    private fun frames(context: Context, intent: Intent): Pair<String, List<ReplayFrame>>? {
        intent.getStringExtra("file")?.let { path ->
            val file = File(path)
            return file.name to ReplayCodec.decode(file.readText())
        }
        val scenario = intent.getStringExtra("scenario")
        if (scenario == null) {
            Log.w(TAG, "Give --es file <path> or --es scenario <name>. Scenarios:\n" + ReplayScenarios.help.entries.joinToString("\n") { "  ${it.key}: ${it.value}" })
            return null
        }
        HandFeed.resize()
        val (w, h) = HandFeed.displaySize(context)
        val params = HashMap<String, Float>()
        for (key in listOf("x", "y", "x2", "y2", "gap", "gap2")) number(intent, key)?.let { params[key] = it }
        // Panel coordinates in the Space: the display pixels that aim there as the Space stands now.
        val u = number(intent, "u")
        val v = number(intent, "v")
        if (u != null && v != null) {
            val aim = SpaceSession.aim ?: run {
                Log.w(TAG, "Panel coordinates need the Space open (OPEN --es what space)")
                return null
            }
            val panel = number(intent, "panel")?.toInt()
            val p = aim.pixelOf(panel, u, v) ?: run {
                Log.w(TAG, "Panel ${panel ?: "(front)"} at $u,$v is not in view")
                return null
            }
            params["x"] = p.x
            params["y"] = p.y
            val u2 = number(intent, "u2")
            val v2 = number(intent, "v2")
            if (u2 != null && v2 != null) aim.pixelOf(panel, u2, v2)?.let {
                params["x2"] = it.x
                params["y2"] = it.y
            }
            Log.i(TAG, "Panel ${panel ?: "(front)"} at u $u, v $v is display pixel ${p.x.toInt()},${p.y.toInt()}")
        }
        val frames = ReplayScenarios.build(scenario, params, SyntheticHands(w, h, GlassesCameraProvider.airBox))
        Log.i(TAG, "Scenario $scenario ${params.entries.joinToString { "${it.key}=${it.value.toInt()}" }}: ${ReplayScenarios.help[scenario]}")
        return scenario to frames
    }

    private fun open(context: Context, intent: Intent) {
        val displays = DisplayMonitor(context).snapshot()
        val id = number(intent, "display")?.toInt()
            ?: displays.filter(DisplaySelector::isEligible).maxByOrNull { it.widthPx.toLong() * it.heightPx }?.id
        if (id == null) {
            Log.w(TAG, "No external display. Displays: ${displays.joinToString { it.summary }}")
            return
        }
        val what = intent.getStringExtra("what") ?: "home"
        // 0.5.1: --ez presentation true draws the Space in its Presentation fallback, as when DeX keeps it freeform.
        if (intent.hasExtra("presentation")) SpaceActivity.forcePresentation = intent.getBooleanExtra("presentation", false)
        val failure = when (what) {
            "home" -> AppLauncher.openGlassesHome(context, id)
            "space" -> AppLauncher.openSpace(context, id)
            else -> {
                Log.w(TAG, "OPEN takes --es what home or space, not $what")
                return
            }
        }
        Log.i(TAG, if (failure == null) "Opened $what on display $id" else "Could not open $what on display $id: ${failure.message}")
    }

    private fun addApp(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: run {
            Log.w(TAG, "ADD_APP takes --es pkg <package>")
            return
        }
        val component = context.packageManager.getLaunchIntentForPackage(pkg)?.component ?: run {
            Log.w(TAG, "ADD_APP: $pkg has no launcher activity")
            return
        }
        val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        com.hayeco.flipspaces.spatial.SpaceSession.send(com.hayeco.flipspaces.spatial.SpaceCommand.AddApp(com.hayeco.flipspaces.core.AppEntry(label, component.packageName, component.className), inFrontOfGaze = true))
        Log.i(TAG, "ADD_APP: $label (${component.flattenToShortString()}) sent to the Space")
    }

    /** A number extra given with --ef, --ei, --el or --es. */
    @Suppress("DEPRECATION")
    private fun number(intent: Intent, key: String): Float? = when (val v = intent.extras?.get(key)) {
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull()
        else -> null
    }

    /**
     * Commands that work on the real glasses with no rig: "wearer" puts the rig's turning head in place of the glasses'
     * (`--ef yaw --ef hz`), "nowearer" gives the glasses back, "smooth" (`--ez on`) the game head's smoothing, and "arm"
     * (`--ef x --ef y --ef z`) moves both Joy-Con hands.
     */
    private fun rigless(context: Context, intent: Intent) {
        when (intent.getStringExtra("do")) {
            "wearer" -> {
                val yaw = intent.getFloatExtra("yaw", 30f)
                val hz = intent.getFloatExtra("hz", 0.25f)
                val start = System.nanoTime()
                com.hayeco.flipspaces.openxr.OpenXrHeadFeed.testPose = { now ->
                    val half = yaw * kotlin.math.sin(2.0 * Math.PI * hz * (now - start) / 1e9) * Math.PI / 360.0
                    com.hayeco.flipspaces.core.spatial.Quat(kotlin.math.cos(half).toFloat(), 0f, kotlin.math.sin(half).toFloat(), 0f)
                }
                Log.i(TAG, "test wearer on the glasses: $yaw degrees each way at $hz Hz")
            }
            "nowearer" -> {
                com.hayeco.flipspaces.openxr.OpenXrHeadFeed.testPose = null
                Log.i(TAG, "the glasses' own head again")
            }
            "smooth" -> {
                com.hayeco.flipspaces.openxr.OpenXrHeadFeed.smooth = intent.getBooleanExtra("on", true)
                Log.i(TAG, "game head smoothing ${if (com.hayeco.flipspaces.openxr.OpenXrHeadFeed.smooth) "on" else "off"}")
            }
            // 1.3.2: Record and Steady mode from adb, to test a recording across the switches.
            "record" -> com.hayeco.flipspaces.spatial.SpaceSession.recordWanted = intent.getBooleanExtra("on", true)
            "steady" -> com.hayeco.flipspaces.spatial.GlassesDisplayControl.steady(context, intent.getBooleanExtra("on", true))
            "pcvr" -> {
                val tuning = com.hayeco.flipspaces.pcvr.PcVrTuning
                tuning.zoom = intent.getFloatExtra("zoom", tuning.zoom).coerceIn(1f, 2f)
                tuning.operatingRate = intent.getIntExtra("rate", tuning.operatingRate)
                tuning.direct = intent.getBooleanExtra("direct", tuning.direct)
                if (intent.hasExtra("hands")) {
                    if (intent.getBooleanExtra("hands", true)) com.hayeco.flipspaces.hands.HandTrackingService.start(context)
                    else com.hayeco.flipspaces.hands.HandInputManager.stopHands(context)
                    Log.i(TAG, "PC VR test: hands ${if (intent.getBooleanExtra("hands", true)) "on" else "off"}")
                }
                tuning.aimPitchDeg = intent.getFloatExtra("aimpitch", tuning.aimPitchDeg)
                tuning.aimYawDeg = intent.getFloatExtra("aimyaw", tuning.aimYawDeg)
                tuning.controllerForwardM = intent.getFloatExtra("forward", tuning.controllerForwardM)
                Log.i(TAG, "PC VR hand aim: pitch ${tuning.aimPitchDeg}, yaw ${tuning.aimYawDeg}, forward offset ${tuning.controllerForwardM} m")
                Log.i(TAG, "PC VR zoom asked: ${tuning.zoom}")
                // As the launcher's tile does: `--ez open true` opens PC VR over the Space, false closes it.
                if (intent.hasExtra("open")) {
                    if (intent.getBooleanExtra("open", true)) com.hayeco.flipspaces.pcvr.PcVrSession.open("the debug command")
                    else com.hayeco.flipspaces.pcvr.PcVrSession.close("the debug command")
                }
            }
            "handaim" -> {
                // The direct hand cursor (1.2.5) on or off, and its filter, live.
                val t = com.hayeco.flipspaces.spatial.DirectAimTuning
                t.on = intent.getBooleanExtra("direct", t.on)
                t.minCutoffHz = intent.getFloatExtra("cutoff", t.minCutoffHz)
                t.beta = intent.getFloatExtra("beta", t.beta)
                t.glideMs = intent.getFloatExtra("glide", t.glideMs)
                t.speedFloorDegS = intent.getFloatExtra("floor", t.speedFloorDegS)
                Log.i(TAG, "Space hand cursor: ${if (t.on) "direct" else "the eased aim"}, cut-off ${t.minCutoffHz} Hz, beta ${t.beta}, floor ${t.speedFloorDegS} deg/s, glide ${t.glideMs} ms")
            }
            "gameclock" -> {
                com.hayeco.flipspaces.spatial.SpaceRenderer.feedGameClock = intent.getBooleanExtra("on", true)
                Log.i(TAG, "game clock to Monado ${if (com.hayeco.flipspaces.spatial.SpaceRenderer.feedGameClock) "on" else "off"}")
            }
            "arm" -> {
                val now = com.hayeco.flipspaces.openxr.OpenXrPads.armOffset(context)
                val offset = com.hayeco.flipspaces.core.spatial.Vec3(
                    intent.getFloatExtra("x", now.x), intent.getFloatExtra("y", now.y), intent.getFloatExtra("z", now.z),
                )
                com.hayeco.flipspaces.openxr.OpenXrPads.setArmOffset(context, offset)
                if (intent.hasExtra("spread")) com.hayeco.flipspaces.openxr.OpenXrPads.setArmSpread(context, intent.getFloatExtra("spread", 1f))
            }
            else -> Log.w(TAG, "no game rig is running")
        }
    }

    companion object {
        private const val TAG = HandReplayPlayer.TAG
        const val ACTION_REPLAY = "com.hayeco.flipspaces.debug.REPLAY"
        const val ACTION_STOP = "com.hayeco.flipspaces.debug.REPLAY_STOP"
        const val ACTION_DUMP = "com.hayeco.flipspaces.debug.REPLAY_DUMP"
        const val ACTION_OPEN = "com.hayeco.flipspaces.debug.OPEN"
        const val ACTION_RIG = "com.hayeco.flipspaces.debug.RIG"
        const val ACTION_STALL = "com.hayeco.flipspaces.debug.STALL"
        const val ACTION_ADD_APP = "com.hayeco.flipspaces.debug.ADD_APP"
        const val ACTION_UPDATE_TEST = "com.hayeco.flipspaces.debug.UPDATE_TEST"
        const val ACTION_POSITION = "com.hayeco.flipspaces.debug.POSITION"
        const val ACTION_POSITION_STOP = "com.hayeco.flipspaces.debug.POSITION_STOP"
    }
}
