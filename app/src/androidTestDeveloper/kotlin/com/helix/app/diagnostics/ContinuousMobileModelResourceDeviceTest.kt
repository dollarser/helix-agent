package com.helix.app.diagnostics

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.plugin.ProgressiveSkillModelDeviceTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Repeated real Qwen phone actions, independent business assertions and the existing physical resource bounds. */
class ContinuousMobileModelResourceDeviceTest {
    /** Diagnostic only: retained physical descriptors are not an acceptance pass. */
    @Test
    @Suppress("ExplicitGarbageCollectionCall") // Distinguish deferred framework cleanup from permanent retention.
    fun diagnoseMobileDescriptorRetention() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("helix.soak.diagnose") == "true")
        val pairs = (args.getString("helix.soak.diagnosticPairs") ?: "6").toInt()
        require(pairs in 1..60)
        try {
            ProgressiveSkillModelDeviceTest().mobileContinuous { pair ->
                val before = sample()
                println("MOBILE_DIAGNOSTIC pair=$pair fd=${before[0]} threads=${before[1]} pss=${before[2]}")
                val counts = descriptors().map { it.substringAfter(':') }.groupingBy { it }.eachCount()
                println("MOBILE_DESCRIPTOR_COUNTS=$counts")
                recordMobileDescriptors("pair-$pair-immediate")
                Thread.sleep(1_000)
                recordMobileDescriptors("pair-$pair-idle-1s")
                Thread.sleep(4_000)
                recordMobileDescriptors("pair-$pair-idle-5s")
                pair < pairs
            }
        } finally {
            println("MOBILE_DIAGNOSTIC_BEFORE_FINALIZATION=" + sample())
            System.gc()
            System.runFinalization()
            Thread.sleep(5_000)
            println("MOBILE_DIAGNOSTIC_AFTER_FINALIZATION=" + sample())
        }
    }

    @Test
    fun continuousMobileModelResourceSoak() {
        val args = InstrumentationRegistry.getArguments()
        val duration = args.getString("helix.soak.seconds")
        org.junit.Assume.assumeTrue(duration != null)
        val seconds = requireNotNull(duration).toLong()
        require(seconds in 60..7200)
        requireNotNull(args.getString("pluginSkillPort"))
        requireNotNull(args.getString("pluginSkillModel"))
        var baseline: List<Long>? = null
        var baselineDescriptors = emptySet<String>()
        var started = 0L
        var cycles = 0
        ProgressiveSkillModelDeviceTest().mobileContinuous { pair ->
            if (pair == 1) {
                val warm = sample()
                baseline = warm
                baselineDescriptors = descriptors()
                started = SystemClock.elapsedRealtime()
                Log.i(
                    "HelixSoak",
                    "baseline pid=${Process.myPid()} fd=${warm[0]} threads=${warm[1]} pssKb=${warm[2]}",
                )
                return@mobileContinuous true
            }
            val bounds = requireNotNull(baseline)
            cycles++
            val current = sample()
            Log.i(
                "HelixSoak",
                "resource pid=${Process.myPid()} fd=${current[0]} threads=${current[1]} pssKb=${current[2]}",
            )
            if (current[0] > bounds[0] + 8) {
                reportDescriptors("MOBILE_FD", baselineDescriptors)
            }
            assertResourceBounds(bounds, current, "task")
            Log.i(
                "HelixSoak",
                "pid=${Process.myPid()} cycles=$cycles elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                    "fd=${current[0]} threads=${current[1]} pssKb=${current[2]}",
            )
            // Include time between real tasks while independently reporting liveness to the host sampler.
            val next = minOf(started + seconds * 1000, SystemClock.elapsedRealtime() + 30_000)
            while (SystemClock.elapsedRealtime() < next) {
                Thread.sleep(5_000)
                val idle = sample()
                Log.i(
                    "HelixSoak",
                    "pid=${Process.myPid()} cycles=$cycles elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                        "fd=${idle[0]} threads=${idle[1]} pssKb=${idle[2]}",
                )
                if (idle[0] > bounds[0] + 8) {
                    reportDescriptors("MOBILE_IDLE_FD", baselineDescriptors)
                }
                assertResourceBounds(bounds, idle, "idle")
            }
            SystemClock.elapsedRealtime() - started < seconds * 1000
        }
        println("MOBILE_MODEL_SOAK=PASS cycles=$cycles businessTasks=${cycles * 2}")
    }

    private fun assertResourceBounds(
        baseline: List<Long>,
        current: List<Long>,
        phase: String,
    ) {
        assertTrue("cumulative FD drift: ${baseline[0]} -> ${current[0]} ($phase)", current[0] <= baseline[0] + 8)
        assertTrue("cumulative thread drift: ${baseline[1]} -> ${current[1]} ($phase)", current[1] <= baseline[1] + 16)
        assertTrue(
            "cumulative PSS drift: ${baseline[2]} -> ${current[2]} ($phase)",
            current[2] <= baseline[2] + 96 * 1024,
        )
    }

    private fun reportDescriptors(
        prefix: String,
        baseline: Set<String>,
    ) {
        val current = descriptors()
        println("${prefix}_DELTA=" + (current - baseline).joinToString())
        current.sorted().forEach { println("${prefix}_DESCRIPTOR=$it") }
    }

    private fun descriptors(): Set<String> =
        requireNotNull(File("/proc/self/fd").list())
            .mapNotNull { number ->
                try {
                    "$number:" + android.system.Os.readlink("/proc/self/fd/$number")
                } catch (closed: android.system.ErrnoException) {
                    Log.d("HelixSoak", "Descriptor closed during diagnostic sampling", closed)
                    null // A descriptor can close during diagnostics; counts still use the full physical listing.
                }
            }.toSet()

    private fun sample(): List<Long> =
        listOf(
            requireNotNull(File("/proc/self/fd").list()).size.toLong(),
            requireNotNull(File("/proc/self/task").list()).size.toLong(),
            Debug.getPss(),
        )
}
