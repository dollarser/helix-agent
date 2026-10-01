package com.helix.app.proot

import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production adapter without starting Android or a Runtime process. */
class LinuxJobObservationPortTest {
    private val original = DetachedJobBinding("s", "turn", "call", "job_aaaaaaaaaaaa", "exec-a", "a".repeat(64))
    private var queries = 0
    private val recorded = mutableListOf<ProotJobRecord>()
    private var settled = false
    private var current = original
    private var reply: ProotJobRecord? =
        ProotJobRecord(original.jobId, original.executionId, original.inputManifestSha256, ProotJobState.RUNNING, 1_000)
    private val port =
        LinuxJobObservationPort(
            resolveOriginal = { session, handle ->
                require(session == original.sessionId && handle == original.toolCallId)
                current
            },
            queryOriginal = { binding ->
                assertEquals(original, binding)
                queries++
                reply
            },
            observe = { binding, record ->
                assertEquals(original, binding)
                recorded += record
            },
            isSettled = { settled },
            nowMillis = { 3_000L },
        )

    @Test fun observationKeepsOriginalIdentityAndDoesNotSettleExecution() {
        val binding = port.resolve("s", "call")
        val result = requireNotNull(port.query(binding))
        assertEquals(original.turnId, binding.turnId)
        assertEquals(original.executionId, binding.executionId)
        assertEquals(original.jobId, binding.generation)
        assertEquals("RUNNING", result.state)
        assertFalse(result.terminal)
        assertTrue(result.settlementPending)
        assertFalse(settled)
        assertEquals(1, queries)
        assertEquals(listOf(reply), recorded)
    }

    @Test fun terminalAndCollectedAreSeparateFacts() {
        reply = reply!!.copy(state = ProotJobState.SUCCEEDED, terminalAtEpochMs = 2_000, exitCode = 0)
        val binding = port.resolve("s", "call")
        val pending = requireNotNull(port.query(binding))
        assertTrue(pending.terminal)
        assertTrue(pending.settlementPending)
        assertEquals(reply!!.terminalCommit, pending.revision)
        settled = true
        assertFalse(requireNotNull(port.query(binding)).settlementPending)
    }

    @Test fun missingRuntimeRecordNeverBecomesInventedRunningOrSuccessfulState() {
        reply = null
        assertNull(port.query(port.resolve("s", "call")))
        assertTrue(recorded.isEmpty())
        assertFalse(settled)
    }

    @Test fun orphanedRecordRetainsUncertainEffects() {
        reply = reply!!.copy(state = ProotJobState.ORPHANED, terminalAtEpochMs = 2_000)
        val result = requireNotNull(port.query(port.resolve("s", "call")))
        assertTrue(result.terminal)
        assertTrue(result.requiresReview)
        assertTrue(result.settlementPending)
        assertNull(result.exitCode)
    }

    @Test fun foreignSessionAndHandleNeverReachRuntime() {
        assertThrows(IllegalArgumentException::class.java) { port.resolve("fork", "call") }
        assertThrows(IllegalArgumentException::class.java) { port.resolve("s", "missing") }
        assertEquals(0, queries)
        assertTrue(recorded.isEmpty())
    }

    @Test fun changedOriginalBindingFailsBeforePhysicalQuery() {
        val binding = port.resolve("s", "call")
        current = original.copy(executionId = "replacement")
        assertFalse(port.isCurrent(binding))
        assertThrows(IllegalArgumentException::class.java) { port.query(binding) }
        assertEquals(0, queries)
    }

    @Test fun everyForeignReplyIdentityIsRejectedBeforeRecording() {
        val binding = port.resolve("s", "call")
        val valid = requireNotNull(reply)
        val foreign =
            listOf(
                valid.copy(jobId = "job_bbbbbbbbbbbb"),
                valid.copy(executionId = "other-execution"),
                valid.copy(inputManifestSha256 = "b".repeat(64)),
            )
        foreign.forEach { value ->
            reply = value
            assertThrows(IllegalArgumentException::class.java) { port.query(binding) }
        }
        assertTrue(recorded.isEmpty())
        assertEquals(3, queries)
        assertFalse(settled)
    }
}
