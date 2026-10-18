package com.helix.app.terminal

import com.helix.runtime.proot.core.PtyProcessIdentity
import com.helix.runtime.proot.core.PtySessionOrigin
import com.helix.runtime.proot.core.PtySessionRecord
import com.helix.runtime.proot.ipc.PtySessionReply
import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRecoveryStateTest {
    private val record =
        PtySessionRecord(PtySessionOrigin("terminal", "g1", "e1", "/workspace", "r1", 7, 10_000, 1000, 61_000))
            .started(PtyProcessIdentity(123, 42))

    @Test fun failedAttachIsNotMisreportedAsReadOnlyConnection() {
        for (outcome in listOf("REQUEST_FAILED_QUERY_ORIGINAL", "NOT_FOUND", "OK", "WRITER_BUSY")) {
            assertThrows(
                IllegalStateException::class.java,
            ) { terminalAttachmentIsWriter(PtySessionReply(null, outcome = outcome)) }
        }
        assertThrows(IllegalStateException::class.java) { terminalAttachmentIsWriter(PtySessionReply(record)) }
        assertThrows(IllegalStateException::class.java) {
            terminalAttachmentIsWriter(PtySessionReply(record.runtimeLost(), "token"))
        }
        assertThrows(IllegalStateException::class.java) {
            terminalAttachmentIsWriter(PtySessionReply(record.stoppedTree(0), "token"))
        }
    }

    @Test fun validWriterAndBusyObserverKeepTheirDistinctMeaning() {
        assertTrue(terminalAttachmentIsWriter(PtySessionReply(record, "token")))
        assertFalse(terminalAttachmentIsWriter(PtySessionReply(record, outcome = "WRITER_BUSY")))
    }

    @Test fun missingJournalKeepsUncertainSubmittedIdentityVisibleWithoutStopProof() {
        val owner = ExecutionOwnership.Owner("retained", "g1")
        val state = terminalState(PtySessionReply(null, outcome = "NOT_FOUND"), owner)
        assertEquals(owner.executionId, state.sessionId)
        assertEquals("UNKNOWN", state.phase)
        assertFalse(state.canSettle)
        assertEquals("START_UNCONFIRMED", state.stopReason)
        assertThrows(IllegalStateException::class.java) {
            terminalState(PtySessionReply(null, outcome = "REQUEST_FAILED_QUERY_ORIGINAL"), owner)
        }
        assertThrows(IllegalStateException::class.java) { terminalState(PtySessionReply(null, outcome = "NOT_FOUND")) }
    }
}
