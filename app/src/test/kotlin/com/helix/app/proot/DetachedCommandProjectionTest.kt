package com.helix.app.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetachedCommandProjectionTest {
    private fun project(
        terminal: DetachedCommandFacts? = null,
        content: String = """{"accepted":true,"state":"SUCCEEDED","executionComplete":false}""",
        callState: String = "COMPLETED",
    ) = CommandResultProjection.project(
        "original",
        "code.linux.job.start",
        "{}",
        CommandResultFacts(callState, "COMPLETED", "session", "SUCCEEDED", "Submission accepted", content),
        CommandBrowseFacts(null, null, false, terminal),
        "app",
    )

    @Test fun acceptedStartNeverProvesExecutionSucceededEvenAfterTurnCompletes() {
        val view = project()
        assertEquals(CommandDetailState.SUBMITTED, view.state)
        assertTrue(view.settlementPending)
        assertFalse(view.noOutput)
        assertTrue("code.linux.job.start" in COMMAND_TOOL_NAMES)
    }

    @Test fun verifiedSuccessStillNeedsHostSettlement() {
        val view = project(DetachedCommandFacts("SUCCEEDED", 0, false))
        assertEquals(CommandDetailState.SUCCEEDED, view.state)
        assertTrue(view.settlementPending)
        assertFalse(view.noOutput)
        assertEquals(0, view.exitCode)
        assertFalse(project(DetachedCommandFacts("SUCCEEDED", 0, true)).settlementPending)
    }

    @Test fun failedCancelledAndOrphanedJobsDoNotInheritSuccessfulStartOutcome() {
        assertEquals(CommandDetailState.FAILED, project(DetachedCommandFacts("FAILED", 7, false)).state)
        assertEquals(7, project(DetachedCommandFacts("FAILED", 7, false)).exitCode)
        assertNull(project(DetachedCommandFacts("FAILED", 7, false)).detail)
        val cancelled = project(DetachedCommandFacts("CANCELLED", null, false))
        assertEquals(CommandDetailState.CANCELLED, cancelled.state)
        assertTrue(cancelled.settlementPending)
        assertEquals(CommandDetailState.UNKNOWN, project(DetachedCommandFacts("ORPHANED", null, false)).state)
    }

    @Test fun missingOrRefusedSubmissionDoesNotInventAJob() {
        assertEquals(CommandDetailState.UNKNOWN, project(content = "{}").state)
        assertFalse(project(content = "{}", callState = "DENIED").settlementPending)
        assertEquals(CommandDetailState.DENIED, project(content = "{}", callState = "DENIED").state)
        assertFalse(project(content = "{}", callState = "FAILED").settlementPending)
    }
}
