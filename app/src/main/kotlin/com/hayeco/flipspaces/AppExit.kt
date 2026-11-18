package com.hayeco.flipspaces

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.hayeco.flipspaces.Log
import com.hayeco.flipspaces.core.GlassesReady
import com.hayeco.flipspaces.hands.HandTrackingService
import com.hayeco.flipspaces.spatial.HeadTracker
import com.hayeco.flipspaces.spatial.SpaceActivity
import com.hayeco.flipspaces.spatial.SpaceSession
import java.lang.ref.WeakReference

/**
 * 1.1.10 (a tester: "I think an exit app button would be nice, as it stays active in the notification bar. I just use the
 * stop app via the settings"): Exit Pocket XR, from the ongoing notification or the trackpad Menu's last row. It closes the
 * Space (whose onDestroy releases its panel displays, the glasses' display control and the panel service), stops hand
 * tracking, head tracking and its Shizuku helper, stops the glasses ready service and its notification, then finishes
 * every Pocket XR screen.
 *
 * After Exit the plug-in watcher opens nothing ([exited]) until Pocket XR is opened again from its icon or the phone
 * restarts (boot starts the ready service again, as before).
 */
object AppExit {
    private const val TAG = "FlipSpaces/Mode"

    /** True from Exit until a Pocket XR screen is opened again ([reopened]). */
    @Volatile var exited = false
        private set

    private val activities = mutableListOf<WeakReference<Activity>>()
    private val main = Handler(Looper.getMainLooper())

    /** Keeps a list of the open Pocket XR screens, so Exit can finish each one. Once, from the Application. */
    fun track(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                synchronized(activities) {
                    activities.removeAll { it.get() == null }
                    activities += WeakReference(activity)
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                synchronized(activities) { activities.removeAll { it.get() == null || it.get() === activity } }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    /** A Pocket XR screen was opened by the user after Exit: the watcher and the ready service come back. */
    fun reopened(context: Context) {
        if (!exited) return
        exited = false
        Log.i(TAG, "Pocket XR opened again after Exit: watching for the glasses again")
        GlassesReadyService.start(context, GlassesReady.From.PHONE_SCREEN)
    }

    /** Quits Pocket XR. Any thread; the work runs on the main thread. */
    fun exit(context: Context, from: String) {
        val app = context.applicationContext
        main.post {
            Log.i(TAG, "Exit Pocket XR ($from): closing the Space, hands, head tracking and the ready service")
            exited = true
            SpaceActivity.alive()?.let { space ->
                SpaceSession.active = false
                space.finishAndRemoveTask()
            }
            runCatching { HandTrackingService.stop(app) }
            HeadTracker.stopForExit()
            runCatching { app.stopService(Intent(app, GlassesReadyService::class.java)) }
            runCatching { app.getSystemService(NotificationManager::class.java)?.cancelAll() }
            val open = synchronized(activities) { activities.mapNotNull { it.get() }.also { activities.clear() } }
            open.filter { !it.isFinishing }.forEach { runCatching { it.finishAndRemoveTask() } }
        }
    }
}
