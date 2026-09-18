package com.helix.app.ui

import com.helix.app.runcontrol.BudgetStopReasons
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-204 slice 1: the recovery projection is a pure read-only map from persisted structured
 * facts to the seven error classes and the explicit operations. Covers unknown results
 * (reconcile-only), cancellation (no auto-continue), budget (new bounded Turn admission),
 * auth/network/capability (repair then NEW call), and fail-closed handling of missing or
 * corrupted records.
 */
class RecoveryFactsProjectionTest {
    @Test
    fun completedTurnHasNoBlockAndListsCompletedActionsAndArtifacts() {
        val summary =
            recoverySummary(
                facts(
                    turnState = TurnState.COMPLETED.name,
                    calls = listOf(call("COMPLETED", "read", "c1"), call("COMPLETED", "write", "c2")),
                ).copy(artifactIds = listOf("a1")),
            )
        assertEquals(RecoveryBlockClass.NONE, summary.blockClass)
        assertFalse(summary.blocked)
        assertTrue(summary.operations.isEmpty())
        assertNull(summary.nextOperation)
        assertEquals(listOf("c1", "c2"), summary.completedActions.map { it.callId })
        assertEquals(listOf("a1"), summary.artifactIds)
    }

    @Test
    fun inProgressTurnClaimsNoRecovery() {
        val summary = recoverySummary(facts(turnState = TurnState.WAITING_MODEL.name))
        assertEquals(RecoveryBlockClass.NONE, summary.blockClass)
        assertTrue(summary.operations.isEmpty())
    }

    @Test
    fun cancellingTurnClaimsNoRecovery() {
        val summary = recoverySummary(facts(turnState = TurnState.CANCELLING.name))
        assertEquals(RecoveryBlockClass.NONE, summary.blockClass)
        assertTrue(summary.operations.isEmpty())
    }

    @Test
    fun unparseableTurnStateFailsClosedToNoBlock() {
        val summary = recoverySummary(facts(turnState = "NOT_A_STATE"))
        assertEquals(RecoveryBlockClass.NONE, summary.blockClass)
        assertTrue(summary.operations.isEmpty())
    }

    @Test
    fun parkedReviewCallIsResultUnknownWithQueryOnly() {
        val summary =
            recoverySummary(
                facts(
                    turnState = TurnState.FAILED.name,
                    calls = listOf(call("NEEDS_REVIEW", "bash", "c1")),
                ),
            )
        assertEquals(RecoveryBlockClass.RESULT_UNKNOWN, summary.blockClass)
        assertEquals(listOf(RecoveryOperation.QUERY_RESULT), summary.operations)
        assertEquals(RecoveryOperation.QUERY_RESULT, summary.nextOperation)
        assertEquals(listOf("c1"), summary.pendingReview.map { it.callId })
    }

    @Test
    fun interruptedTurnWithNoCallsIsResultUnknown() {
        val summary = recoverySummary(facts(turnState = TurnState.INTERRUPTED.name))
        assertEquals(RecoveryBlockClass.RESULT_UNKNOWN, summary.blockClass)
        assertEquals(listOf(RecoveryOperation.QUERY_RESULT), summary.operations)
    }

    @Test
    fun interruptedProcessDeathParkedCallIsResultUnknown() {
        val summary =
            recoverySummary(
                facts(
                    turnState = TurnState.INTERRUPTED.name,
                    calls = listOf(call("INTERRUPTED", "bash", "c1")),
                ),
            )
        assertEquals(RecoveryBlockClass.RESULT_UNKNOWN, summary.blockClass)
        assertEquals(listOf(RecoveryOperation.QUERY_RESULT), summary.operations)
        assertEquals(listOf("c1"), summary.pendingReview.map { it.callId })
    }

    @Test
    fun corruptedCallStateFailsClosedToPendingReview() {
        val summary =
            recoverySummary(
                facts(
                    turnState = TurnState.FAILED.name,
                    calls = listOf(call("NOT_A_STATE", "bash", "c1")),
                ),
            )
        assertEquals(RecoveryBlockClass.RESULT_UNKNOWN, summary.blockClass)
        assertEquals(listOf(RecoveryOperation.QUERY_RESULT), summary.operations)
        assertEquals(listOf("c1"), summary.pendingReview.map { it.callId })
    }

    @Test
    fun parkedCallBeatsBudgetCode() {
        val summary =
            recoverySummary(
                facts(
                    turnState = TurnState.FAILED.name,
                    errorCode = "TOKEN_BUDGET_LIMIT",
                    calls = listOf(call("NEEDS_REVIEW", "bash", "c1")),
                    budgetContinuationEligible = true,
                ),
            )
        assertEquals(RecoveryBlockClass.RESULT_UNKNOWN, summary.blockClass)
        assertEquals(listOf(RecoveryOperation.QUERY_RESULT), summary.operations)
    }

    @Test
    fun interruptedErrorCodeIsResultUnknown() {
        val summary =
            recoverySummary(
                facts(turnState = TurnState.FAILED.name, errorCode = "INTERRUPTED"),
            )
        assertEquals(RecoveryBlockClass.RESULT_UNKNOWN, summary.blockClass)
        assertEquals(listOf(RecoveryOperation.QUERY_RESULT), summary.operations)
    }

    @Test
    fun cancelledTurnIsUserRejectedWithNoAutomaticOperations() {
        val summary = recoverySummary(facts(turnState = TurnState.CANCELLED.name))
        assertEquals(RecoveryBlockClass.USER_REJECTED, summary.blockClass)
        assertTrue(summary.operations.isEmpty())
        assertNull(summary.nextOperation)
    }

    @Test
    fun cancelledUserPausedTurnWithoutGoalOffersNothing() {
        val summary =
            recoverySummary(facts(turnState = TurnState.CANCELLED.name).copy(userPaused = true))
        assertEquals(RecoveryBlockClass.USER_REJECTED, summary.blockClass)
        assertTrue(summary.userPaused)
        assertTrue(summary.operations.isEmpty())
    }

    @Test
    fun pausedGoalTurnOffersOnlyGoalContinue() {
        val summary =
            recoverySummary(
                facts(
                    turnState = TurnState.CANCELLED.name,
                    goalBound = true,
                    goalState = "PAUSED",
                    goalContinuable = true,
                ).copy(userPaused = true),
            )
        assertEquals(RecoveryBlockClass.USER_REJECTED, summary.blockClass)
        assertEquals(listOf(RecoveryOperation.CONTINUE_GOAL), summary.operations)
    }

    @Test
    fun modelRefusalAndContentFilterAreExecutionFailuresWithNewCallRetry() {
        for (code in listOf("REFUSAL", "CONTENT_FILTER")) {
            val summary =
                recoverySummary(facts(turnState = TurnState.FAILED.name, errorCode = code))
            assertEquals(code, RecoveryBlockClass.EXECUTION_FAILED, summary.blockClass)
            assertEquals(
                listOf(RecoveryOperation.RETRY_NEW_CALL),
                summary.operations,
            )
        }
    }

    @Test
    fun denialOnlyFailureIsRejectionWithoutRetry() {
        val summary =
            recoverySummary(
                facts(
                    turnState = TurnState.FAILED.name,
                    calls = listOf(call("DENIED", "bash", "c1"), call("DENIED", "write", "c2")),
                ),
            )
        assertEquals(RecoveryBlockClass.USER_REJECTED, summary.blockClass)
        assertTrue(summary.operations.isEmpty())
        assertEquals(listOf("c1", "c2"), summary.deniedActions.map { it.callId })
    }

    @Test
    fun denialAlongsideCompletedCallIsExecutionFailure() {
        val summary =
            recoverySummary(
                facts(
                    turnState = TurnState.FAILED.name,
                    calls =
                        listOf(
                            call("COMPLETED", "read", "c1"),
                            call("DENIED", "bash", "c2"),
                        ),
                ),
            )
        assertEquals(RecoveryBlockClass.EXECUTION_FAILED, summary.blockClass)
        assertEquals(listOf(RecoveryOperation.RETRY_NEW_CALL), summary.operations)
    }

    @Test
    fun everyBudgetStopCodeIsBudgetClass() {
        BudgetStopReasons.turn.forEach { code ->
            val summary =
                recoverySummary(facts(turnState = TurnState.FAILED.name, errorCode = code))
            assertEquals(code, RecoveryBlockClass.BUDGET, summary.blockClass)
        }
    }

    @Test
    fun budgetFailureEligibleOffersNewCallRetry() {
        val summary =
            recoverySummary(
                facts(
                    turnState = TurnState.FAILED.name,
                    errorCode = "TOKEN_BUDGET_LIMIT",
                    budgetContinuationEligible = true,
                ),
            )
        assertEquals(RecoveryBlockClass.BUDGET, summary.blockClass)
        assertEquals(listOf(RecoveryOperation.RETRY_NEW_CALL), summary.operations)
        assertTrue(summary.budgetContinuationEligible)
    }

    @Test
    fun budgetFailureIneligibleWithoutGoalOffersNothing() {
        val summary =
            recoverySummary(
                facts(
                    turnState = TurnState.FAILED.name,
                    errorCode = "OUTPUT_TOKEN_LIMIT",
                    budgetContinuationEligible = false,
                ),
            )
        assertEquals(RecoveryBlockClass.BUDGET, summary.blockClass)
        assertTrue(summary.operations.isEmpty())
        assertNull(summary.nextOperation)
    }

    @Test
    fun budgetFailureIneligibleWithContinuableGoalOffersGoalContinue() {
        val summary =
            recoverySummary(
                facts(
                    turnState = TurnState.FAILED.name,
                    errorCode = "MODEL_CALL_LIMIT",
                    goalBound = true,
                    goalState = "PAUSED",
                    goalContinuable = true,
                ),
            )
        assertEquals(RecoveryBlockClass.BUDGET, summary.blockClass)
        assertEquals(listOf(RecoveryOperation.CONTINUE_GOAL), summary.operations)
    }

    @Test
    fun goalContinueRequiresBothGoalFacts() {
        val boundNotContinuable =
            recoverySummary(
                facts(
                    turnState = TurnState.CANCELLED.name,
                    goalBound = true,
                    goalContinuable = false,
                ),
            )
        val continuableNotBound =
            recoverySummary(
                facts(
                    turnState = TurnState.CANCELLED.name,
                    goalBound = false,
                    goalContinuable = true,
                ),
            )
        assertTrue(boundNotContinuable.operations.isEmpty())
        assertTrue(continuableNotBound.operations.isEmpty())
    }

    @Test
    fun authCodesOfferReconnectThenNewCall() {
        for (code in listOf("AUTH", "PROVIDER_AUTH")) {
            val summary =
                recoverySummary(facts(turnState = TurnState.FAILED.name, errorCode = code))
            assertEquals(code, RecoveryBlockClass.AUTH, summary.blockClass)
            assertEquals(
                listOf(RecoveryOperation.RECONNECT, RecoveryOperation.RETRY_NEW_CALL),
                summary.operations,
            )
            assertEquals(RecoveryOperation.RECONNECT, summary.nextOperation)
        }
    }

    @Test
    fun networkCodesOfferReconnectThenNewCall() {
        for (code in listOf("TRANSPORT", "TIMEOUT", "RATE_LIMITED", "NETWORK")) {
            val summary =
                recoverySummary(facts(turnState = TurnState.FAILED.name, errorCode = code))
            assertEquals(code, RecoveryBlockClass.NETWORK, summary.blockClass)
            assertEquals(
                listOf(RecoveryOperation.RECONNECT, RecoveryOperation.RETRY_NEW_CALL),
                summary.operations,
            )
        }
    }

    @Test
    fun permissionCodeOffersGrantThenNewCall() {
        val summary =
            recoverySummary(
                facts(turnState = TurnState.FAILED.name, errorCode = "PERMISSION"),
            )
        assertEquals(RecoveryBlockClass.CAPABILITY, summary.blockClass)
        assertEquals(
            listOf(RecoveryOperation.GRANT_PERMISSION, RecoveryOperation.RETRY_NEW_CALL),
            summary.operations,
        )
    }

    @Test
    fun unknownSystemAndProviderCodesFallToExecutionFailureWithRetry() {
        val codes =
            listOf(
                "SERVER_ERROR",
                "HTTP_ERROR",
                "PROTOCOL",
                "CONTEXT_NOT_COMPACTABLE",
                "CONTEXT_NO_GAIN",
                "CONTEXT_SUMMARY_INVALID",
                "GOAL_BUDGET_LIMIT",
                "GOAL_TIME_WINDOW_EXPIRED",
                "FGS_START_REJECTED",
                "FGS_TIMEOUT",
                "FGS_SERVICE_LOST",
                "TOOL_STREAM_TRUNCATED",
                "TOOL_ARGS_OVERFLOW",
                "MODEL_TEXT_OVERFLOW",
                "TOTALLY_UNKNOWN",
                null,
            )
        codes.forEach { code ->
            val summary =
                recoverySummary(facts(turnState = TurnState.FAILED.name, errorCode = code))
            assertEquals(
                "code=$code",
                RecoveryBlockClass.EXECUTION_FAILED,
                summary.blockClass,
            )
            assertEquals(
                "code=$code",
                listOf(RecoveryOperation.RETRY_NEW_CALL),
                summary.operations,
            )
        }
    }

    @Test
    fun emptyFactsDoNotCrash() {
        val summary = recoverySummary(facts())
        assertEquals(RecoveryBlockClass.NONE, summary.blockClass)
        assertTrue(summary.operations.isEmpty())
        assertTrue(summary.completedActions.isEmpty())
        assertTrue(summary.pendingReview.isEmpty())
        assertTrue(summary.artifactIds.isEmpty())
    }
}

private fun facts(
    turnState: String = "COMPLETED",
    errorCode: String? = null,
    calls: List<ToolCallFact> = emptyList(),
    goalBound: Boolean = false,
    goalState: String? = null,
    goalContinuable: Boolean = false,
    budgetContinuationEligible: Boolean = false,
): RecoveryFacts =
    RecoveryFacts(
        turnState = turnState,
        turnErrorCode = errorCode,
        toolCalls = calls,
        userPaused = false,
        goalBound = goalBound,
        goalState = goalState,
        goalContinuable = goalContinuable,
        budgetContinuationEligible = budgetContinuationEligible,
        artifactIds = emptyList(),
    )

private fun call(
    state: String,
    toolName: String = "bash",
    callId: String? = null,
): ToolCallFact = ToolCallFact(callId ?: "call-$state-$toolName", toolName, state)
