package com.helix.app.runcontrol

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.PowerManager
import com.helix.tools.framework.ToolScheduler
import java.util.concurrent.atomic.AtomicInteger

/** Real Android resource facts sampled at every scheduler admission. */
interface DeviceResourceProbe {
    fun processorCount(): Int

    fun isForeground(): Boolean

    fun isLowMemory(): Boolean

    fun isThermallyConstrained(): Boolean
}

/** Sampled CPU budget, bounded by the scheduler's existing physical pool; pressure lowers it to one. */
class AndroidResourceGate(
    private val probe: DeviceResourceProbe,
) {
    fun allowance(): Int =
        if (!probe.isForeground() || probe.isLowMemory() || probe.isThermallyConstrained()) {
            1
        } else {
            probe.processorCount().coerceIn(1, ToolScheduler.HARD_MAX_CONCURRENCY)
        }
}

/**
 * Process-level probe. Foreground is driven by Activity lifecycle; memory and thermal state are
 * queried from the platform on every admission, so recovery is observed without a synthetic
 * "pressure cleared" event.
 */
@Suppress("TooManyFunctions") // Android lifecycle callbacks and resource readings form a single platform probe.
class PlatformDeviceResourceProbe(
    application: Application,
) : DeviceResourceProbe,
    Application.ActivityLifecycleCallbacks {
    private val activityManager = application.getSystemService(ActivityManager::class.java)
    private val powerManager = application.getSystemService(PowerManager::class.java)
    private val startedActivities = AtomicInteger(0)

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun isForeground(): Boolean = startedActivities.get() > 0

    override fun processorCount(): Int = Runtime.getRuntime().availableProcessors()

    override fun isLowMemory(): Boolean = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo).lowMemory

    override fun isThermallyConstrained(): Boolean =
        powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

    override fun onActivityStarted(activity: Activity) {
        startedActivities.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    override fun onActivityCreated(
        activity: Activity,
        state: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        state: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
