package com.helix.app.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandResultProjectionTest {
    private val input =
        CommandResultInput(
            "call",
            "bash",
            "{}",
            "COMPLETED",
            "COMPLETED",
            "session",
            "SUCCESS",
            null,
            """{"state":"SUCCEEDED","exitCode":0,"stdout":"verified","stderr":""}""",
            CommandBrowseFacts(null, null, false),
            "scope:app",
        )

    @Test fun corruptArchiveDoesNotFallBackToUnverifiedStreams() {
        val result = CommandResultProjection.project(input.copy(browse = CommandBrowseFacts(null, null, true)))
        assertEquals(CommandDetailState.READ_FAILED, result.state)
        assertEquals("", result.stdout)
        assertNull(result.exitCode)
    }

    @Test fun terminalTurnWithUnsettledCallIsUnknownInsteadOfRunning() {
        val result = CommandResultProjection.project(input.copy(callState = "RUNNING", resultStatus = null))
        assertEquals(CommandDetailState.UNKNOWN, result.state)
    }

    @Test fun failedCallRetainsItsExitCodeAndReason() {
        val result = CommandResultProjection.project(input.copy(callState = "FAILED", resultSummary = "exit code 7"))
        assertEquals(CommandDetailState.FAILED, result.state)
        assertEquals(7, result.exitCode)
        assertEquals("exit code 7", result.detail)
    }

    @Test fun expiredEvidenceRemainsDistinctFromOrdinaryFailure() {
        val result =
            CommandResultProjection.project(
                input.copy(callState = "FAILED", resultSummary = "evidence expired"),
            )
        assertEquals(CommandDetailState.EVIDENCE_EXPIRED, result.state)
        assertNull(result.exitCode)
    }
}
