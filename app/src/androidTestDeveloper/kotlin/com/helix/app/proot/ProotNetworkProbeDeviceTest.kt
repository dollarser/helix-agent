package com.helix.app.proot

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in native feasibility probe. This is not production network-DENY acceptance. */
@RunWith(AndroidJUnit4::class)
class ProotNetworkProbeDeviceTest {
    @Test
    fun probeRealApplicationExecutionDomain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("hxa209NetworkProbe") == "true")
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "hxa209-network-probe")
        val input = File(root, "input").apply { writeText("synthetic input") }
        val observations = File(root, "observations.txt")
        observations.writeText("uid=${Process.myUid()}\n")

        fun run(
            name: String,
            vararg arguments: String,
        ): Pair<Int, String> {
            val binary = File(root, name)
            assertTrue("Missing staged probe: $name", binary.isFile)
            val output = File(root, "$name.log")
            val process =
                ProcessBuilder(listOf("/system/bin/linker64", binary.absolutePath) + arguments)
                    .redirectInput(input)
                    .redirectOutput(output)
                    .redirectErrorStream(true)
                    .start()
            try {
                assertTrue("Probe timed out: $name", process.waitFor(30, TimeUnit.SECONDS))
                val text = output.readText()
                observations.appendText("$name rc=${process.exitValue()}\n$text\n")
                return process.exitValue() to text
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
        assertEquals(0, run("diag").first)
        val filter = run("filterprobe")
        assertEquals(filter.second, 0, filter.first)
        assertEquals(4, Regex("PASS").findAll(filter.second).count())
        val inherited = run("fdprobe", File(root, "guard").absolutePath)
        assertEquals(inherited.second, 0, inherited.first)
        assertTrue(inherited.second.contains("PASS: inherited socket"))
        // Record, rather than mistake a device-specific denial for universal isolation.
        val delegation = run("delegation-probe", File(root, "guard").absolutePath)
        assertEquals(delegation.second, 0, delegation.first)
        assertTrue(delegation.second.contains("synthetic unfiltered parent request="))
    }
}
