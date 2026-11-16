package dev.cinematic.p0.metrics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.PowerManager

/** Installed by our startup provider before activities; observes only this application's UI. */
internal object AndroidSaveTimingEnvironment {
    private val visibility = SaveVisibilityCounter()
    private var registered = false
    @Synchronized fun install(context: Context?) = timingBestEffort {
        if (registered) return@timingBestEffort
        val app = context?.applicationContext as? Application ?: return@timingBestEffort
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = timingBestEffort { visibility.started() }
            override fun onActivityStopped(activity: Activity) = timingBestEffort { visibility.stopped() }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        visibility.initialize()
        registered = true
    }
    fun snapshot(context: Context): SaveTimingEnvironment {
        val foreground = try { visibility.snapshot() } catch (_: Throwable) { SaveVisibility.UNKNOWN }
        val thermal = try { context.getSystemService(PowerManager::class.java)?.currentThermalStatus?.takeIf { it in 0..6 } }
            catch (_: Throwable) { null }
        return SaveTimingEnvironment(foreground, thermal)
    }
}
