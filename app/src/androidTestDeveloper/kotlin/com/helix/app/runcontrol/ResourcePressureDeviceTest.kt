package com.helix.app.runcontrol

import android.app.ActivityManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Host-driven bounded native allocation; no fake MemoryInfo or forced GC. */
class ResourcePressureDeviceTest {
    @Test fun actualLowMemoryLowersAllowanceAndReleaseRestoresIt() {
        val runId = InstrumentationRegistry.getArguments().getString("resource.pressure.runId")
        assumeTrue("Requires the bounded resource-pressure host fixture", runId != null)
        require(requireNotNull(runId).matches(Regex("[a-z0-9-]{1,48}")))
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val probe = PlatformDeviceResourceProbe(app)
        val gate = AndroidResourceGate(probe)
        val manager = app.getSystemService(ActivityManager::class.java)
        val log = File(app.cacheDir, "resource-pressure-$runId.csv")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertTrue(probe.isForeground())
                assertEquals(2, gate.allowance())
                log.writeText("elapsedMs,availableBytes,thresholdBytes,lowMemory,allowance\n")
                observePressure(manager, gate, log, File(app.cacheDir, "resource-pressure-$runId.released"))
                assertTrue(probe.isForeground())
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(probe)
        }
    }

    private fun observePressure(
        manager: ActivityManager,
        gate: AndroidResourceGate,
        log: File,
        released: File,
    ) {
        val start = android.os.SystemClock.elapsedRealtime()
        var lowSeen = false
        while (android.os.SystemClock.elapsedRealtime() - start < 90_000) {
            val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val allowance = gate.allowance()
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            log.appendText("$elapsed,${memory.availMem},${memory.threshold},${memory.lowMemory},$allowance\n")
            if (memory.lowMemory && allowance == 1) lowSeen = true
            val healthy = !memory.lowMemory && allowance == 2
            if (lowSeen && released.exists() && healthy) return
            Thread.sleep(100)
        }
        error("No real low-memory then healthy transition; lowSeen=$lowSeen")
    }
}
