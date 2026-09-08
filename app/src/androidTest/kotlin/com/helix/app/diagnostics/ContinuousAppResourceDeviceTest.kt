package com.helix.app.diagnostics

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.ProductionMigrationDeviceTest
import com.helix.app.foreground.DataSyncForegroundServiceDeviceTest
import com.helix.app.runcontrol.AndroidResourceGateDeviceTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.JUnitCore
import java.io.File

/** Runs actual Room, foreground-service and resource-gate fixtures without restarting the process. */
class ContinuousAppResourceDeviceTest {
    @Test
    fun continuousAppResourceSoak() {
        val seconds = InstrumentationRegistry.getArguments().getString("helix.soak.seconds")?.toLong()
        assumeTrue("requires the HXA-103 host soak runner", seconds != null)
        require(seconds!! in 60L..86_400L)
        runCycle()
        logDescriptors("baseline")
        val baselineFd = count("fd")
        val baselineThreads = count("task")
        val baselinePss = Debug.getPss()
        val started = SystemClock.elapsedRealtime()
        var cycles = 0
        var peakFd = baselineFd
        var peakThreads = baselineThreads
        var peakPss = baselinePss
        val observe = InstrumentationRegistry.getArguments().getString("helix.soak.observe") == "true"
        do {
            runCycle()
            cycles += 1
            val fd = count("fd")
            val threads = count("task")
            val pss = Debug.getPss()
            logDescriptors("cycle-$cycles")
            peakFd = maxOf(peakFd, fd)
            peakThreads = maxOf(peakThreads, threads)
            peakPss = maxOf(peakPss, pss)
            if (!observe) checkBounds(baselineFd, baselineThreads, baselinePss, peakFd, peakThreads, peakPss)
            Log.i(
                "HelixSoak",
                "pid=${Process.myPid()} cycles=$cycles elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                    "fd=$fd threads=$threads pssKb=$pss",
            )
        } while (SystemClock.elapsedRealtime() - started < seconds * 1_000L)
        checkBounds(baselineFd, baselineThreads, baselinePss, peakFd, peakThreads, peakPss)
    }

    private fun checkBounds(
        baselineFd: Int,
        baselineThreads: Int,
        baselinePss: Long,
        peakFd: Int,
        peakThreads: Int,
        peakPss: Long,
    ) {
        assertTrue("cumulative FD drift: $baselineFd -> $peakFd", peakFd <= baselineFd + 8)
        assertTrue("cumulative thread drift: $baselineThreads -> $peakThreads", peakThreads <= baselineThreads + 16)
        assertTrue("cumulative PSS drift: $baselinePss -> $peakPss", peakPss <= baselinePss + 96 * 1024)
    }

    /** Diagnostic only: forced finalization below is never part of the acceptance soak. */
    @Test
    @Suppress("ExplicitGarbageCollectionCall") // diagnostic comparison, never acceptance or production
    fun diagnoseActivityDescriptorRetention() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("helix.soak.diagnose") == "true",
        )
        repeat(60) { cycle ->
            val result = JUnitCore.runClasses(AndroidResourceGateDeviceTest::class.java)
            assertTrue("activity fixture failed: ${result.failures}", result.wasSuccessful())
            assertTrue("activity fixture skipped", result.runCount == 1 && result.assumptionFailureCount == 0)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(1_000L)
            val samplePss = InstrumentationRegistry.getArguments().getString("helix.soak.samplePss") == "true"
            if (samplePss) Debug.getPss()
            Log.i("HelixSoakDiagnostic", "activityCycle=$cycle fd=${count("fd")}")
        }
        logDescriptors("before-finalization")
        System.gc()
        System.runFinalization()
        Thread.sleep(5_000L)
        logDescriptors("after-finalization")
        Log.i("HelixSoakDiagnostic", "afterFinalizationFd=${count("fd")}")
    }

    @Test
    @Suppress("ExplicitGarbageCollectionCall") // compares temporary framework native allocations
    fun diagnoseNotificationDescriptorRetention() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("helix.soak.diagnose") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val launcher =
            com.helix.app.foreground
                .AndroidForegroundServiceLauncher(context)
        launcher.start()
        try {
            Thread.sleep(2_000L)
            logDescriptors("notification-baseline")
            repeat(1_000) { sample ->
                assertTrue("notification missing", manager.activeNotifications.isNotEmpty())
                if (sample % 100 == 0) {
                    Log.i("HelixSoakDiagnostic", "notificationSample=$sample fd=${count("fd")}")
                }
            }
            logDescriptors("notification-before-finalization")
            System.gc()
            System.runFinalization()
            Thread.sleep(5_000L)
            logDescriptors("notification-after-finalization")
        } finally {
            launcher.stop()
        }
    }

    @Test
    @Suppress("ExplicitGarbageCollectionCall") // diagnostic comparison, never an acceptance pass
    fun diagnoseCombinedDescriptorRetention() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("helix.soak.diagnose") == "true")
        repeat(35) { cycle ->
            runCycle()
            Log.i("HelixSoakDiagnostic", "combinedCycle=$cycle fd=${count("fd")}")
        }
        logDescriptors("combined-before-finalization")
        System.gc()
        System.runFinalization()
        Thread.sleep(5_000L)
        logDescriptors("combined-after-finalization")
    }

    @Test
    fun diagnosePssSamplerDescriptorRetention() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("helix.soak.diagnose") == "true")
        val result = JUnitCore.runClasses(AndroidResourceGateDeviceTest::class.java)
        assertTrue("activity setup failed", result.wasSuccessful() && result.runCount == 1)
        logDescriptors("pss-baseline")
        repeat(100) { sample ->
            val pss = Debug.getPss()
            Log.i("HelixSoakDiagnostic", "pssSample=$sample fd=${count("fd")} pssKb=$pss")
        }
        logDescriptors("pss-final")
    }

    private fun logDescriptors(phase: String) {
        File("/proc/self/fd").listFiles()!!.take(512).forEach { entry ->
            try {
                Log.i("HelixSoakFd", "$phase ${entry.name} ${android.system.Os.readlink(entry.path)}")
            } catch (failure: android.system.ErrnoException) {
                if (failure.errno != android.system.OsConstants.ENOENT) throw failure
            }
        }
    }

    private fun count(entry: String): Int = File("/proc/self/$entry").list()?.size ?: error("missing proc $entry")

    private fun runCycle() {
        val result =
            JUnitCore.runClasses(
                ProductionMigrationDeviceTest::class.java,
                DataSyncForegroundServiceDeviceTest::class.java,
                AndroidResourceGateDeviceTest::class.java,
            )
        assertTrue("nested fixtures failed: ${result.failures}", result.wasSuccessful())
        assertTrue("nested fixtures must execute all seven cases", result.runCount == 7)
        assertTrue("nested fixtures must not skip", result.ignoreCount == 0 && result.assumptionFailureCount == 0)
        // ActivityScenario close precedes RenderThread fence/buffer cleanup on the emulator.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(1_000L)
    }
}
