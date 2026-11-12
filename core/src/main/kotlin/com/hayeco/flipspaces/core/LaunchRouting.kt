package com.hayeco.flipspaces.core

enum class FailureKind {
    /** "Permission Denial ... with launchDisplayId=N": the display refused the launch. */
    DISPLAY_DENIED,

    /** Some other SecurityException. */
    PERMISSION_DENIED,
    APP_NOT_FOUND,
    DISPLAY_GONE,
    OTHER,
}

/** A failed start, with the platform's own error text kept word for word. */
data class LaunchFailure(val kind: FailureKind, val message: String) {
    companion object {
        /** [exceptionName] is the simple class name, for example SecurityException. */
        fun classify(exceptionName: String, message: String?): LaunchFailure {
            val text = message?.trim().orEmpty()
            val shown = text.ifEmpty { "$exceptionName with no message" }
            val lower = text.lowercase()
            val kind = when {
                "launchdisplayid" in lower || "launch on display" in lower -> FailureKind.DISPLAY_DENIED
                "ActivityNotFound" in exceptionName || "unable to find explicit activity" in lower ||
                    "does not exist" in lower -> FailureKind.APP_NOT_FOUND
                exceptionName == "SecurityException" || "permission denial" in lower -> FailureKind.PERMISSION_DENIED
                "display" in lower && ("invalid" in lower || "not found" in lower || "removed" in lower) ->
                    FailureKind.DISPLAY_GONE
                else -> FailureKind.OTHER
            }
            return LaunchFailure(kind, shown)
        }

        /**
         * 0.5.20 (`Space start ... from the app: refused (the pointer service
         * went away)` at both starts, with the pointer service bound): a start through [host], whose
         * launch returns null on success. 0.5.19 wrote `host?.let { launch(it) } ?: NoHost`, which
         * read a successful start (null) as no host, logged the wrong cause and started the Space a
         * second time through the shell. Only a missing host is "went away".
         */
        fun <T : Any> through(host: T?, launch: (T) -> LaunchFailure?): LaunchFailure? =
            if (host == null) classify("NoHost", "the pointer service went away") else launch(host)
    }
}

enum class ShizukuStatus(private val labelKey: String, private val labelEnglish: String) {
    NOT_INSTALLED("core_shizuku_not_installed", "Shizuku is not installed"),
    NOT_RUNNING("core_shizuku_not_running", "Shizuku is installed but not running"),
    NO_PERMISSION("core_shizuku_no_permission", "Shizuku is running but Pocket XR is not allowed yet"),
    READY("core_shizuku_ready", "Shizuku is ready"),
    ;

    val label: String get() = UiText.get(labelKey, labelEnglish)
}

/** Ways to start an app on the glasses. */
enum class LaunchStep {
    /** Start Pocket XR's home activity on the glasses first, then launch from it. */
    START_HOME,

    /** Launch with the home activity on the glasses as the caller. */
    FROM_HOME,

    /** Launch from the phone activity: only when the home activity could not start for another reason. */
    FROM_PHONE,

    /** `am start --display` with shell identity through Shizuku. */
    SHIZUKU,
}

enum class StopReason {
    MIRROR_MODE,
    SHIZUKU_NEEDS_PERMISSION,
    SHIZUKU_UNAVAILABLE,
    FAILED,
}

sealed interface RouteDecision {
    data class Go(val step: LaunchStep) : RouteDecision
    data class Stop(val reason: StopReason, val error: String?) : RouteDecision
}

/** Picks the next way to launch, given the display mode and what already failed. */
object LaunchRouter {
    fun firstStep(assessment: HostingAssessment, homeRunning: Boolean): RouteDecision = when {
        assessment.mode == ContentMode.MIRROR -> RouteDecision.Stop(StopReason.MIRROR_MODE, assessment.lastError)
        homeRunning -> RouteDecision.Go(LaunchStep.FROM_HOME)
        else -> RouteDecision.Go(LaunchStep.START_HOME)
    }

    /**
     * After [step] failed. A denial of our own home activity means the display hosts no tasks
     * (mirror mode). A denial of the app while the display does host tasks goes to Shizuku, which
     * is never used for a mirror or unknown display.
     */
    fun afterFailure(
        step: LaunchStep,
        failure: LaunchFailure,
        assessment: HostingAssessment,
        shizuku: ShizukuStatus,
    ): RouteDecision = when (step) {
        LaunchStep.START_HOME ->
            if (failure.kind == FailureKind.DISPLAY_DENIED) {
                RouteDecision.Stop(StopReason.MIRROR_MODE, failure.message)
            } else {
                RouteDecision.Go(LaunchStep.FROM_PHONE)
            }
        LaunchStep.FROM_HOME, LaunchStep.FROM_PHONE -> {
            val displayHosts = step == LaunchStep.FROM_HOME || assessment.mode.canHostApps
            if (failure.kind == FailureKind.DISPLAY_DENIED && displayHosts) {
                when (shizuku) {
                    ShizukuStatus.READY -> RouteDecision.Go(LaunchStep.SHIZUKU)
                    ShizukuStatus.NO_PERMISSION -> RouteDecision.Stop(StopReason.SHIZUKU_NEEDS_PERMISSION, failure.message)
                    else -> RouteDecision.Stop(StopReason.SHIZUKU_UNAVAILABLE, failure.message)
                }
            } else {
                RouteDecision.Stop(StopReason.FAILED, failure.message)
            }
        }
        LaunchStep.SHIZUKU -> RouteDecision.Stop(StopReason.FAILED, failure.message)
    }

    /** DeX displays are freeform by design, so slot bounds apply even with the global switches off. */
    fun windowed(mode: ContentMode, freeformReady: Boolean): Boolean = mode == ContentMode.DESKTOP || freeformReady

    /** Reported insets win; under DeX assume its taskbar until the home activity reports. */
    fun insetsFor(mode: ContentMode, densityDpi: Int, reported: DisplayInsets?): DisplayInsets = when {
        reported != null -> reported
        mode == ContentMode.DESKTOP -> {
            val scale = (if (densityDpi > 0) densityDpi else 160) / 160f
            DisplayInsets(bottomPx = (DisplayInsets.DEX_TASKBAR_DP * scale).toInt())
        }
        else -> DisplayInsets.NONE
    }
}

/** The shell launch used by the Shizuku fallback: `am start --display`. */
object ShellLaunch {
    const val WINDOWING_MODE_FREEFORM = 5
    private val safeName = Regex("""[A-Za-z0-9_.$]+""")

    fun command(spec: LaunchSpec): List<String> {
        require(safeName.matches(spec.app.packageName) && safeName.matches(spec.app.activityName)) {
            "Unexpected characters in ${spec.app.key}"
        }
        return buildList {
            addAll(listOf("am", "start", "--user", "current", "--display", spec.displayId.toString()))
            if (spec.windowed) addAll(listOf("--windowingMode", WINDOWING_MODE_FREEFORM.toString()))
            if (LaunchFlag.MULTIPLE_TASK in spec.flags) add("--activity-multiple-task")
            addAll(listOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"))
            addAll(listOf("-n", "${spec.app.packageName}/${spec.app.activityName}"))
        }
    }

    /** am prints errors to its output and does not always exit non zero, so read both. Null means it started. */
    fun failureIn(exitCode: Int, output: String): LaunchFailure? {
        val text = output.trim()
        val errorLine = text.lines().any {
            val line = it.trim()
            line.startsWith("Error") || line.startsWith("Exception") || "Permission Denial" in line ||
                line.startsWith("java.lang.")
        }
        if (exitCode == 0 && !errorLine) return null
        return LaunchFailure.classify("am start", text.ifEmpty { "am start exited with code $exitCode" })
    }
}
