package com.hayeco.flipspaces

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import com.hayeco.flipspaces.spatial.HeadDiagnosticsFile
import com.hayeco.flipspaces.spatial.HeadTracker
import com.hayeco.flipspaces.spatial.SpaceSession
import com.hayeco.flipspaces.spatial.SpaceStore
import com.hayeco.flipspaces.ui.FlipSpacesTheme
import com.hayeco.flipspaces.ui.HeadTrackingFacts
import com.hayeco.flipspaces.ui.HeadTrackingScreen

/**
 * Diagnostics, "Glasses head tracking": finds the glasses' USB network, connects to the One
 * family sensor stream and shows each step's result, the report rate and live yaw, pitch and
 * roll. The first thing to try on the phone.
 */
class HeadTrackingActivity : ComponentActivity() {
    private var settingsVersion by mutableIntStateOf(0)
    private var delayCheck by mutableStateOf(SpaceSession.delayCheck)
    private var levelCheck by mutableStateOf(SpaceSession.levelCheck)

    /**
     * 0.5.12: the slider's value is one run. 0.5.13: runs are kept per
     * glasses (EDID name and product id); three that agree within 1 ms set the delay to their
     * median and save it for those glasses. The result is logged either way.
     */
    private fun saveDelayRun(store: SpaceStore) {
        findGlasses(store)
        val (r, line) = store.saveDelayRun()
        if (r != null && r.repeatable) HeadTracker.applySettings(this)
        Log.i("FlipSpaces/Head", line)
        settingsVersion++
    }

    /** 0.5.13: the glasses plugged in now, if Android names them; else the ones the Space last ran on. */
    private fun findGlasses(store: SpaceStore) {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java) ?: return
        dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstNotNullOfOrNull { SpaceStore.glassesId(it) }
            ?.let { id -> if (id != store.glasses) store.useCalibrationFor(id)?.let { Log.i("FlipSpaces/Head", it) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = SpaceStore(this)
        findGlasses(store)
        val facts = HeadTrackingFacts(
            soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE,
            dynamicSensors = getSystemService(SensorManager::class.java)?.getDynamicSensorList(Sensor.TYPE_ALL)
                ?.map { "${it.name} (${it.stringType})" }.orEmpty(),
        )
        setContent {
            FlipSpacesTheme {
                val status by HeadTracker.status.collectAsStateWithLifecycle()
                val live by HeadTracker.live.collectAsStateWithLifecycle()
                val networks by HeadTracker.networks.collectAsStateWithLifecycle()
                val connect by HeadTracker.connectResult.collectAsStateWithLifecycle()
                val ethernet by HeadTracker.ethernet.collectAsStateWithLifecycle()
                val attempts by HeadTracker.attempts.collectAsStateWithLifecycle()
                val route by HeadTracker.route.collectAsStateWithLifecycle()
                val answer by HeadTracker.answer.collectAsStateWithLifecycle()
                val levelResult by HeadTracker.level.collectAsStateWithLifecycle()
                val motionLine by com.hayeco.flipspaces.spatial.HidMotionRecorder.line.collectAsStateWithLifecycle()
                // 1.2.8 (Discussion #24): read again as the status or a setting changes (a plug-in changes the status).
                val other = (status to settingsVersion).let { _ ->
                    val pick = com.hayeco.flipspaces.core.spatial.GlassesVendors.recordable(com.hayeco.flipspaces.spatial.GlassesUsbList.read(this))
                    com.hayeco.flipspaces.ui.OtherGlassesCard(
                        glasses = pick?.let { "%04X:%04X \"%s\"".format(java.util.Locale.ROOT, it.vendorId, it.productId, it.name) },
                        viture = pick?.vendorId == com.hayeco.flipspaces.core.spatial.VitureImuProtocol.VENDOR || store.model == com.hayeco.flipspaces.core.spatial.GlassesModel.VITURE,
                        vitureOn = store.vitureHeadTracking,
                        vitureAxes = store.vitureAxes,
                        recording = com.hayeco.flipspaces.spatial.HidMotionRecorder.busy || motionLine?.startsWith("Recording") == true || motionLine?.startsWith("Allow") == true,
                        motionLine = motionLine ?: com.hayeco.flipspaces.spatial.HidMotionRecorder.newestLine(this)?.let { "Last recording: $it" },
                        onViture = { on ->
                            // Off closes the open stream at once and releases the glasses; on asks for USB access if
                            // it is missing and tries now.
                            HeadTracker.setVitureEnabled(this, on)
                            settingsVersion++
                        },
                        onVitureAxes = { a ->
                            // 1.2.10: applied to the open stream as it reads; the reader is not started again.
                            HeadTracker.setVitureAxes(this, a)
                            settingsVersion++
                        },
                        onRecord = {
                            if (com.hayeco.flipspaces.spatial.HidMotionRecorder.start(this)) settingsVersion++
                        },
                    )
                }
                HeadTrackingScreen(
                    other = other,
                    status = status,
                    live = live,
                    networks = networks,
                    ethernet = ethernet,
                    connectResult = connect,
                    attempts = attempts,
                    route = route,
                    answer = answer,
                    facts = facts,
                    axes = store.axisMap.also { settingsVersion },
                    predictMs = store.predictMs.also { settingsVersion },
                    onAxes = {
                        store.axisMap = it
                        HeadTracker.applySettings(this)
                        settingsVersion++
                    },
                    onPredict = {
                        store.predictMs = it
                        // A value set by hand outside the check is no longer the calibrated one.
                        if (!SpaceSession.delayCheck) store.delaySetByHand()
                        HeadTracker.applySettings(this)
                        settingsVersion++
                    },
                    onRecentre = { HeadTracker.recentre("the head tracking screen") },
                    delayRuns = store.delayRuns.also { settingsVersion },
                    delayCalibrated = store.delayCalibrated.also { settingsVersion },
                    delayCheck = delayCheck,
                    timing = SpaceSession.timingLine.also { live },
                    onDelayCheck = {
                        delayCheck = it
                        SpaceSession.delayCheck = it
                    },
                    onSaveRun = { saveDelayRun(store) },
                    onClearRuns = {
                        store.clearDelayRuns()
                        settingsVersion++
                    },
                    levelCheck = levelCheck,
                    levelResult = levelResult.also { settingsVersion },
                    levelCalibrated = store.levelCalibrated.also { settingsVersion; levelResult },
                    mountRollDeg = store.mountRollDeg.also { settingsVersion; levelResult },
                    mountPitchDeg = store.mountPitchDeg.also { settingsVersion; levelResult },
                    onLevelCheck = {
                        levelCheck = it
                        SpaceSession.levelCheck = it
                    },
                    onSaveLevel = { HeadTracker.takeLevelCalibration() },
                    onClearLevel = {
                        HeadTracker.clearLevelCalibration(this)
                        settingsVersion++
                    },
                    rowPose2d = store.rowPose.also { settingsVersion },
                    rowPoseSbs = store.rowPoseSbs.also { settingsVersion },
                    onRowPose = { sbs, on ->
                        if (sbs) store.rowPoseSbs = on else store.rowPose = on
                        Log.i("FlipSpaces/Head", "per-row pose ${if (on) "on" else "off"} ${if (sbs) "in side by side" else "in 2D"} (set on the head tracking screen)")
                        settingsVersion++
                    },
                    lateWarpSbs = store.lateWarpSbs.also { settingsVersion },
                    onLateWarp = { on ->
                        store.lateWarpSbs = on
                        Log.i("FlipSpaces/Head", "late warp in side by side ${if (on) "on" else "off"} (set on the head tracking screen)")
                        settingsVersion++
                    },
                    onCopyAll = {
                        val text = HeadDiagnosticsFile.text(this, facts.soc, facts.dynamicSensors, SpaceSession.timingLine)
                        val ok = HeadDiagnosticsFile.copy(this, text)
                        android.widget.Toast.makeText(this, if (ok) "Copied all of it (${text.lines().size} lines)" else "Android did not allow the copy; try Share", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onShare = {
                        HeadDiagnosticsFile.share(this, HeadDiagnosticsFile.text(this, facts.soc, facts.dynamicSensors, SpaceSession.timingLine))
                    },
                    onSendLogs = { SendLogs.send(this, this) },
                    onRetry = {
                        HeadTracker.release()
                        HeadTracker.acquire(this)
                        // 0.5.6: with the Space also holding the tracker it keeps running; try now, backoff reset.
                        HeadTracker.kick("Try again on the head tracking screen")
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        HeadTracker.acquire(this)
    }

    override fun onResume() {
        super.onResume()
        GlassesAutoOpen.resumed(this)
    }

    override fun onPause() {
        GlassesAutoOpen.paused(this)
        super.onPause()
    }

    override fun onStop() {
        // The check's line is only for while this screen is open.
        SpaceSession.delayCheck = false
        delayCheck = false
        SpaceSession.levelCheck = false
        levelCheck = false
        HeadTracker.release()
        super.onStop()
    }
}
