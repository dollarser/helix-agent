@file:Suppress(
    "EmptyCatchBlock", // the empty catch IS the assertion: the fail() before it proves the throw
    "SwallowedException", // same idiom; nothing to preserve from an expected rejection
)

package com.helix.runtime.proot.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HXA-084: the job spec is the bounded, already-screened submission unit. Its
 * bounds keep the wire, the journal, and the process table all under cap; argv
 * and script are mutually exclusive (no unescaped command assembly here).
 */
class ProotJobSpecTest {
    private val base: ProotJobSpec =
        ProotJobSpec(
            executionId = "exec-1",
            jobId = "job_001122334455",
            command = ProotJobCommand.Argv(listOf("/bin/sh", "-c", "echo hi")),
            relativeWorkingDirectory = "",
            environment = mapOf("PATH" to "/usr/bin:/bin"),
            deadlineMs = 60_000L,
            maxOutputBytes = 1_048_576L,
            inputManifestSha256 = "0".repeat(64),
        )

    @Test
    fun aValidSpecIsAccepted() {
        assertEquals(listOf("/bin/sh", "-c", "echo hi"), (base.command as ProotJobCommand.Argv).arguments)
        // Script mode is the only other legal command shape.
        val script = base.copy(command = ProotJobCommand.Script("echo hi"))
        assertTrue(script.command is ProotJobCommand.Script)
    }

    @Test
    fun commandShapesAreExclusive() {
        // Empty argv is not a command.
        try {
            base.copy(command = ProotJobCommand.Argv(emptyList()))
            fail("empty argv accepted")
        } catch (e: IllegalArgumentException) {
        }
        // An empty script is not a command.
        try {
            base.copy(command = ProotJobCommand.Script(""))
            fail("empty script accepted")
        } catch (e: IllegalArgumentException) {
        }
        // Huge argv entries are out of bounds.
        try {
            base.copy(command = ProotJobCommand.Argv(listOf("x".repeat(8193))))
            fail("oversized argv entry accepted")
        } catch (e: IllegalArgumentException) {
        }
        // Huge scripts are out of bounds.
        try {
            base.copy(command = ProotJobCommand.Script("x".repeat((256 * 1024) + 1)))
            fail("oversized script accepted")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun theWorkingDirectoryIsStrictlySafe() {
        for (bad in listOf(
            "../etc",
            "a/b",
            "a\\b",
            ".",
            "..",
            "a b",
            "a/b/",
            "x".repeat(257),
        )) {
            try {
                base.copy(relativeWorkingDirectory = bad)
                fail("accepted working directory: $bad")
            } catch (e: IllegalArgumentException) {
            }
        }
        base.copy(relativeWorkingDirectory = "sub-dir_1") // legal
    }

    @Test
    fun theEnvironmentIsBounded() {
        val env = (1..65).associate { "K$it" to "v" }
        try {
            base.copy(environment = env)
            fail("oversized env accepted")
        } catch (e: IllegalArgumentException) {
        }
        try {
            base.copy(environment = mapOf("K" to "v".repeat(8193)))
            fail("oversized env value accepted")
        } catch (e: IllegalArgumentException) {
        }
        try {
            base.copy(environment = mapOf("" to "v"))
            fail("empty env name accepted")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun deadlinesAndOutputCapsAreBounded() {
        try {
            base.copy(deadlineMs = 999L)
            fail("sub-second deadline accepted")
        } catch (e: IllegalArgumentException) {
        }
        try {
            base.copy(deadlineMs = 3_600_001L)
            fail("over-an-hour deadline accepted")
        } catch (e: IllegalArgumentException) {
        }
        try {
            base.copy(maxOutputBytes = 1023L)
            fail("sub-kilobyte output cap accepted")
        } catch (e: IllegalArgumentException) {
        }
        try {
            base.copy(maxStderrBytes = 1023L)
            fail("sub-kilobyte stderr cap accepted")
        } catch (e: IllegalArgumentException) {
        }
        try {
            base.copy(maxStderrBytes = base.maxOutputBytes + 1)
            fail("stderr cap larger than the total output cap accepted")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun stdinPathIsStrictlyRelative() {
        base.copy(stdinRelativePath = "mcp/input.jsonl")
        for (bad in listOf("", "/input", "../input", "mcp//input", "mcp\\input", "mcp/input name")) {
            try {
                base.copy(stdinRelativePath = bad)
                fail("accepted stdin path: $bad")
            } catch (e: IllegalArgumentException) {
            }
        }
    }
}
