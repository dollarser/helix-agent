package com.helix.app.proot

import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-194: the command details projection is the ONLY place persisted facts become a
 * view. These tests pin every state mapping (running / succeeded / unknown / cancelled /
 * denied / failed / evidence-expired / read-failed), the stream-precedence rule
 * (local archive over persisted content), the detail-line gating, and the command-text
 * extraction — so the details page stays a pure read that cannot start, replay or
 * acknowledge anything on re-open.
 */
class CommandResultProjectionTest {
    private fun view(
        callState: String = "COMPLETED",
        turnState: String = "COMPLETED",
        resultStatus: String? = "FAILED",
        resultSummary: String? = null,
        resultContent: String? = null,
        browse: CommandBrowseFacts = CommandBrowseFacts(null, null, false),
        argsJson: String = """{"command":["ls -la"]}""",
    ) = CommandResultProjection.project(
        callId = "call-1",
        toolName = "bash",
        argsJson = argsJson,
        facts =
            CommandResultFacts(
                callState,
                turnState,
                "session-1",
                resultStatus,
                resultSummary,
                resultContent,
            ),
        browse = browse,
        scopeLabel = "app",
    )

    @Test
    fun runningCallInLiveTurnProjectsToRunningWithStatusOnly() {
        val v = view(callState = "RUNNING", turnState = "RUNNING_TOOL", resultStatus = null)
        assertEquals(CommandDetailState.RUNNING, v.state)
        assertNull(v.detail)
        assertNull(v.exitCode)
        assertFalse(v.noOutput)
    }

    @Test
    fun parkedCallInInterruptedTurnIsStillRunning() {
        // A NEEDS_REVIEW call while its turn is still INTERRUPTED has no settled outcome
        // yet: the details page shows the running state, never a guessed failure.
        val v = view(callState = "NEEDS_REVIEW", turnState = "INTERRUPTED", resultStatus = null)
        assertEquals(CommandDetailState.RUNNING, v.state)
    }

    @Test
    fun completedCallWithSucceededContentProjectsToSucceeded() {
        val v =
            view(
                callState = "COMPLETED",
                resultStatus = "SUCCEEDED",
                resultContent = """{"state":"SUCCEEDED","exitCode":0,"stdout":"hello","stderr":""}""",
            )
        assertEquals(CommandDetailState.SUCCEEDED, v.state)
        assertEquals(0, v.exitCode)
        assertEquals("hello", v.stdout)
        assertNull(v.detail)
    }

    @Test
    fun completedCallWithoutContentProjectsToUnknown() {
        val v = view(callState = "COMPLETED", resultStatus = "SUCCEEDED", resultContent = null)
        assertEquals(CommandDetailState.UNKNOWN, v.state)
        // The persisted fact does not prove the outcome: no detail line without a summary.
        assertNull(v.detail)
    }

    @Test
    fun completedCallWithMalformedContentProjectsToUnknown() {
        val v = view(callState = "COMPLETED", resultContent = "not-json{")
        assertEquals(CommandDetailState.UNKNOWN, v.state)
    }

    @Test
    fun cancelledCallProjectsToCancelledWithDetail() {
        val v = view(callState = "CANCELLED", resultStatus = "FAILED", resultSummary = "user cancelled")
        assertEquals(CommandDetailState.CANCELLED, v.state)
        assertEquals("user cancelled", v.detail)
    }

    @Test
    fun deniedCallProjectsToDeniedWithoutDetail() {
        val v = view(callState = "DENIED", resultStatus = "FAILED", resultSummary = "denied")
        assertEquals(CommandDetailState.DENIED, v.state)
        assertNull(v.detail)
    }

    @Test
    fun settledCallWithoutAnyResultProjectsToUnknown() {
        val v = view(callState = "FAILED", resultStatus = null)
        assertEquals(CommandDetailState.UNKNOWN, v.state)
    }

    @Test
    fun failedCallWithPlainSummaryProjectsToFailedWithExitCodeInText() {
        val v = view(callState = "FAILED", resultSummary = "Command failed with exit code 3")
        assertEquals(CommandDetailState.FAILED, v.state)
        assertEquals(3, v.exitCode)
        assertEquals("Command failed with exit code 3", v.detail)
    }

    @Test
    fun failedCallWithExpiredEvidenceSummaryProjectsToEvidenceExpired() {
        val v = view(callState = "FAILED", resultSummary = "Local evidence expired; the result was lost")
        assertEquals(CommandDetailState.EVIDENCE_EXPIRED, v.state)
    }

    @Test
    fun failedCallWithUnknownOutcomeSummaryProjectsToUnknown() {
        val v = view(callState = "FAILED", resultSummary = "The runtime no longer knows the outcome")
        assertEquals(CommandDetailState.UNKNOWN, v.state)
    }

    @Test
    fun parkedCallAfterTerminalTurnResolvesThroughResultFacts() {
        // A NEEDS_REVIEW/INTERRUPTED call whose turn already settled is no longer "running":
        // the persisted result facts decide the state.
        val v = view(callState = "INTERRUPTED", turnState = "FAILED", resultSummary = "boom")
        assertEquals(CommandDetailState.FAILED, v.state)
    }

    @Test
    fun unknownStateStringsNeverPretendToBeRunning() {
        // Corrupt state strings: isSettled fails closed (not running) while the turn is
        // still live — and the terminal-turn default keeps the detail honest.
        val v = view(callState = "SOMETHING", turnState = "ALSO_SOMETHING", resultStatus = null)
        assertEquals(CommandDetailState.UNKNOWN, v.state)
    }

    @Test
    fun archiveReadFailureShortCircuitsToReadFailed() {
        val binding = CommandJobBindingFacts("job-1", "exec-1", "sha-1")
        val v = view(browse = CommandBrowseFacts(binding, null, true))
        assertEquals(CommandDetailState.READ_FAILED, v.state)
        assertEquals(binding, v.binding)
        assertEquals("", v.stdout)
        assertEquals("", v.stderr)
        assertTrue(v.files.isEmpty())
    }

    @Test
    fun localArchiveStreamsOverridePersistedContent() {
        val archive =
            ProotRecoveredOutput(
                stdout = "archived",
                stderr = "archived-err",
                truncated = true,
                files = listOf(ProotRecoveredFile("/tmp/out", 42, "sha-2")),
                acknowledged = null,
            )
        val v =
            view(
                resultContent = """{"state":"SUCCEEDED","exitCode":0,"stdout":"old","stderr":""}""",
                browse = CommandBrowseFacts(null, archive, false),
            )
        assertEquals(CommandDetailState.SUCCEEDED, v.state)
        assertEquals("archived", v.stdout)
        assertEquals("archived-err", v.stderr)
        assertTrue(v.truncated)
        assertEquals(1, v.files.size)
        assertEquals("/tmp/out", v.files[0].path)
        assertNull(v.acknowledged)
    }

    @Test
    fun terminalBlankStreamsReportNoOutput() {
        val v = view(callState = "COMPLETED", resultContent = null)
        assertTrue(v.noOutput)
    }

    @Test
    fun identityAndScopeAreCarriedThrough() {
        val v =
            CommandResultProjection.project(
                callId = "call-9",
                toolName = "code.linux.run",
                argsJson = """{"script":"pwd"}""",
                facts =
                    CommandResultFacts(
                        callState = "COMPLETED",
                        turnState = "FAILED",
                        sessionId = "session-9",
                        resultStatus = "FAILED",
                        resultSummary = null,
                        resultContent = null,
                    ),
                browse = CommandBrowseFacts(null, null, false),
                scopeLabel = "dir-9",
            )
        assertEquals("call-9", v.callId)
        assertEquals("code.linux.run", v.toolName)
        assertEquals("session-9", v.sessionId)
        assertEquals("dir-9", v.scopeLabel)
        assertEquals("pwd", v.commandText)
    }

    @Test
    fun commandTextJoinsCommandArrayWithNewlines() {
        val text =
            CommandResultProjection.commandTextFromArgs(
                """{"command":["echo a","echo b"]}""",
            )
        assertEquals("echo a\necho b", text)
    }

    @Test
    fun commandTextPrefersCommandStringOverScript() {
        assertEquals(
            "ls",
            CommandResultProjection.commandTextFromArgs("""{"command":"ls","script":"pwd"}"""),
        )
    }

    @Test
    fun commandTextFallsBackToScriptThenRawArgs() {
        assertEquals("pwd", CommandResultProjection.commandTextFromArgs("""{"script":"pwd"}"""))
        assertEquals("raw", CommandResultProjection.commandTextFromArgs("raw"))
        // An empty command array is not a command: it falls through to the raw arguments.
        assertEquals(
            """{"command":[]}""",
            CommandResultProjection.commandTextFromArgs("""{"command":[]}"""),
        )
    }
}
