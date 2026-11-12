package com.hayeco.flipspaces

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.DisplayCandidate
import com.hayeco.flipspaces.core.FailureKind
import com.hayeco.flipspaces.core.FreeformStatus
import com.hayeco.flipspaces.core.HostingAssessment
import com.hayeco.flipspaces.core.HostingAssessor
import com.hayeco.flipspaces.core.LaunchDenial
import com.hayeco.flipspaces.core.LaunchFailure
import com.hayeco.flipspaces.core.LaunchPlanner
import com.hayeco.flipspaces.core.LaunchRouter
import com.hayeco.flipspaces.core.LaunchSpec
import com.hayeco.flipspaces.core.LaunchStep
import com.hayeco.flipspaces.core.RouteDecision
import com.hayeco.flipspaces.core.StopReason
import com.hayeco.flipspaces.platform.AppLauncher
import com.hayeco.flipspaces.platform.ShizukuBridge
import com.hayeco.flipspaces.platform.WindowingProbe

/**
 * Runs the launch route from core.LaunchRouter: refuse in mirror mode, otherwise put Pocket XR's
 * home activity on the glasses first and launch apps with it as the caller, falling back to the
 * phone activity and then to Shizuku. Every message carries Android's own error text.
 */
object SpacesLauncher {
    private val planner = LaunchPlanner()

    fun assess(context: Context, display: DisplayCandidate): HostingAssessment {
        val hosting = SpacesState.hosting.value
        return HostingAssessor.assess(
            display = display,
            homeRunning = GlassesHomeActivity.runningOn(display.id) != null,
            denial = hosting.denial,
            mirrorSettingOn = WindowingProbe.mirrorBuiltInDisplay(context),
        )
    }

    /** Opens the home surface on the glasses. Returns null on success, else the message to show. */
    fun openHome(phone: Activity, display: DisplayCandidate): String? {
        val failure = AppLauncher.openGlassesHome(phone, display.id) ?: return null
        noteHomeFailure(display, failure)
        return phone.getString(R.string.home_home_refused, failure.message)
    }

    /** A tap on the phone. Returns the message for the phone snackbar. */
    fun launchFromPhone(phone: Activity, app: AppEntry, display: DisplayCandidate, windowing: FreeformStatus): String {
        val assessment = assess(phone, display)
        val home = GlassesHomeActivity.runningOn(display.id)
        return when (val decision = LaunchRouter.firstStep(assessment, home != null)) {
            is RouteDecision.Stop -> stopMessage(app, decision)
            is RouteDecision.Go -> when (decision.step) {
                LaunchStep.FROM_HOME -> run(home ?: phone, phone, LaunchStep.FROM_HOME, app, display, windowing, assessment)
                else -> startHomeThenLaunch(phone, app, display, windowing, assessment)
            }
        }
    }

    /** A tap on the glasses dock, or a launch queued while the home was starting. */
    fun launchFromHome(home: Activity, app: AppEntry, display: DisplayCandidate, windowing: FreeformStatus): String =
        run(home, home, LaunchStep.FROM_HOME, app, display, windowing, assess(home, display))

    private fun startHomeThenLaunch(
        phone: Activity,
        app: AppEntry,
        display: DisplayCandidate,
        windowing: FreeformStatus,
        assessment: HostingAssessment,
    ): String {
        val failure = AppLauncher.openGlassesHome(phone, display.id)
        if (failure == null) {
            SpacesState.queue(SpacesState.PendingLaunch(app, display.id, SystemClock.elapsedRealtime()))
            return phone.getString(R.string.home_opening_home_then, app.label)
        }
        noteHomeFailure(display, failure)
        return follow(phone, LaunchStep.START_HOME, failure, null, app, display, windowing, assessment)
    }

    private fun run(
        caller: Activity,
        phone: Activity,
        step: LaunchStep,
        app: AppEntry,
        display: DisplayCandidate,
        windowing: FreeformStatus,
        assessment: HostingAssessment,
    ): String {
        val spec = plan(app, display, windowing, assessment)
        val failure = AppLauncher.launch(caller, spec) ?: return placed(spec, step)
        return follow(phone, step, failure, spec, app, display, windowing, assessment)
    }

    private fun follow(
        phone: Activity,
        step: LaunchStep,
        failure: LaunchFailure,
        spec: LaunchSpec?,
        app: AppEntry,
        display: DisplayCandidate,
        windowing: FreeformStatus,
        assessment: HostingAssessment,
    ): String = when (val next = LaunchRouter.afterFailure(step, failure, assessment, ShizukuBridge.status(phone))) {
        is RouteDecision.Stop -> stopMessage(app, next)
        is RouteDecision.Go -> when (next.step) {
            LaunchStep.FROM_PHONE -> run(phone, phone, LaunchStep.FROM_PHONE, app, display, windowing, assessment)
            LaunchStep.SHIZUKU -> {
                val shellSpec = spec ?: plan(app, display, windowing, assessment)
                Thread {
                    val shellFailure = ShizukuBridge.launch(shellSpec)
                    SpacesState.post(
                        if (shellFailure == null) {
                            placed(shellSpec, LaunchStep.SHIZUKU)
                        } else {
                            FlipSpacesApp.text(R.string.home_shizuku_failed_too, app.label, shellFailure.message)
                        },
                    )
                }.start()
                phone.getString(R.string.home_refused_trying_shizuku, app.label, failure.message)
            }
            else -> stopMessage(app, RouteDecision.Stop(StopReason.FAILED, failure.message))
        }
    }

    private fun plan(app: AppEntry, display: DisplayCandidate, windowing: FreeformStatus, assessment: HostingAssessment): LaunchSpec {
        val choices = SpacesState.choices.value
        val freeformReady = windowing is FreeformStatus.Ready ||
            (windowing is FreeformStatus.NeedsSetup && !windowing.freeformOff)
        return planner.plan(
            app = app,
            display = display,
            slotIndex = choices.selectedSlot,
            size = choices.size,
            freeformReady = LaunchRouter.windowed(assessment.mode, freeformReady),
            newInstance = choices.newInstance,
            insets = LaunchRouter.insetsFor(assessment.mode, display.densityDpi, SpacesState.hosting.value.insets[display.id]),
        )
    }

    private fun placed(spec: LaunchSpec, step: LaunchStep): String {
        SpacesState.preferDisplay(spec.displayId)
        SpacesState.placed(spec.slot.index, spec.app)
        val shell = step == LaunchStep.SHIZUKU
        return if (spec.windowed) {
            FlipSpacesApp.text(if (shell) R.string.home_opened_in_slot_shizuku else R.string.home_opened_in_slot, spec.app.label, spec.slot.label)
        } else {
            FlipSpacesApp.text(if (shell) R.string.home_opened_full_screen_shizuku else R.string.home_opened_full_screen, spec.app.label)
        }
    }

    private fun noteHomeFailure(display: DisplayCandidate, failure: LaunchFailure) {
        if (failure.kind == FailureKind.DISPLAY_DENIED) {
            SpacesState.recordDenial(LaunchDenial(display.id, failure.message, display.hostingSignature))
        }
    }

    private fun stopMessage(app: AppEntry, stop: RouteDecision.Stop): String {
        // %2$s in the messages below: this " Android said: ..." sentence, or nothing.
        val error = stop.error?.let { " " + FlipSpacesApp.text(R.string.home_android_said, it) }.orEmpty()
        return when (stop.reason) {
            StopReason.MIRROR_MODE -> HostingAssessor.MIRROR_MESSAGE + error
            StopReason.SHIZUKU_NEEDS_PERMISSION -> {
                ShizukuBridge.requestPermission()
                FlipSpacesApp.text(R.string.home_refused_allow_shizuku, app.label, error)
            }
            StopReason.SHIZUKU_UNAVAILABLE ->
                FlipSpacesApp.text(R.string.home_refused_start_shizuku, app.label, error)
            StopReason.FAILED -> FlipSpacesApp.text(R.string.home_could_not_open, app.label, error)
        }
    }
}
