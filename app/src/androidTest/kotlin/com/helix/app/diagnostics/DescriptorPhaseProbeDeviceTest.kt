package com.helix.app.diagnostics

import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.ProductionMigrationDeviceTest
import com.helix.app.foreground.DataSyncForegroundServiceDeviceTest
import com.helix.app.runcontrol.AndroidResourceGateDeviceTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.JUnitCore
import java.io.File
import java.security.MessageDigest

/** Explicit diagnostic arm; no GC, excluded descriptors, adjusted gate, or leak verdict. */
class DescriptorPhaseProbeDeviceTest {
    @Suppress("LongMethod") // one probe = workload arm × (pre/post/idle/slot) sampling, kept linear
    @Test
    fun traceFixtureAndIdleDescriptorOwnership() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("dedicated FD attribution run", args.getString("helix.fd.phases") == "true")
        val workload = args.getString("helix.fd.workload") ?: "combined"
        val selected =
            when (workload) {
                "storage" -> {
                    listOf(ProductionMigrationDeviceTest::class.java)
                }

                "notification" -> {
                    listOf(DataSyncForegroundServiceDeviceTest::class.java)
                }

                "activity" -> {
                    listOf(AndroidResourceGateDeviceTest::class.java)
                }

                "combined" -> {
                    listOf(
                        ProductionMigrationDeviceTest::class.java,
                        DataSyncForegroundServiceDeviceTest::class.java,
                        AndroidResourceGateDeviceTest::class.java,
                    )
                }

                "idle" -> {
                    emptyList()
                }

                else -> {
                    error("unknown FD diagnostic workload")
                }
            }
        val iterations = (args.getString("helix.fd.iterations") ?: "10").toInt()
        require(iterations in 1..60)
        val slotSeconds = (args.getString("helix.fd.slotSeconds") ?: "30").toLong()
        require(slotSeconds in 1..300)
        val runId = requireNotNull(args.getString("helix.fd.runId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "fd-phases-$runId.jsonl")
        require(!file.exists()) { "FD run output already exists; use a fresh run ID" }
        snapshot(file, runId, workload, "baseline", 0)
        repeat(iterations) { cycle ->
            val slotStart = SystemClock.elapsedRealtime()
            for (testClass in selected) {
                snapshot(file, runId, workload, "before-${testClass.simpleName}", cycle)
                val result = JUnitCore.runClasses(testClass)
                snapshot(file, runId, workload, "after-${testClass.simpleName}", cycle)
                assertTrue("FD probe nested fixture failed: ${result.failures}", result.wasSuccessful())
                assertTrue(
                    "FD probe requires nonempty cases without skips",
                    result.runCount > 0 && result.ignoreCount == 0 && result.assumptionFailureCount == 0,
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(1_000)
            snapshot(file, runId, workload, "idle-1s", cycle)
            val remaining = slotStart + slotSeconds * 1000 - SystemClock.elapsedRealtime()
            check(remaining >= 0) { "FD workload exceeded its frozen slot; restart with reviewed config" }
            SystemClock.sleep(remaining)
            snapshot(file, runId, workload, "slot-end", cycle)
        }
        SystemClock.sleep(30_000)
        snapshot(file, runId, workload, "final-idle-30s", iterations)
    }

    private fun snapshot(
        file: File,
        runId: String,
        workload: String,
        phase: String,
        cycle: Int,
    ) {
        val entries = File("/proc/self/fd").listFiles()
        val rows = JSONArray()
        entries?.sortedBy { it.name.toIntOrNull() ?: -1 }?.take(4096)?.forEach { entry ->
            val row = JSONObject().put("fd", entry.name)
            try {
                val target = Os.readlink(entry.path)
                val stat = Os.stat(entry.path)
                val kind =
                    when {
                        target.startsWith("/dev/goldfish") -> "goldfish"
                        target.startsWith("/dev/") -> "device"
                        target.startsWith("socket:") -> "socket"
                        target.startsWith("pipe:") -> "pipe"
                        target.startsWith("anon_inode:") -> "anon_inode"
                        else -> "file"
                    }
                row
                    .put("kind", kind)
                    .put("device", stat.st_dev)
                    .put("inode", stat.st_ino)
                    .put(
                        "targetHash",
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(target.toByteArray())
                            .joinToString("") { "%02x".format(it) },
                    )
            } catch (failure: ErrnoException) {
                // Descriptor closure during the snapshot is evidence, not a missing descriptor counted as zero.
                row.put("unavailableErrno", failure.errno)
            }
            rows.put(row)
        }
        val record =
            JSONObject()
                .put("runId", runId)
                .put("workload", workload)
                .put("phase", phase)
                .put("cycle", cycle)
                .put("pid", Process.myPid())
                .put("monotonicMs", SystemClock.elapsedRealtime())
                .put("descriptors", rows)
                .put("count", entries?.size ?: JSONObject.NULL)
                .put("truncated", entries != null && entries.size > 4096)
        file.appendText(record.toString() + "\n")
    }
}
