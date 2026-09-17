package com.helix.app.ui

import com.helix.app.runcontrol.BudgetStopReasons
import com.helix.core.model.ErrorCode
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState

// HXA-204 slice 1: the read-only recovery projection. It maps PERSISTED structured facts —
// TurnState, ToolCallState, the owning Goal's continuation admission, the terminal turn's
// stable error code, and the budget-continuation admission — to one of the seven user-visible
// error classes and the explicit operations the user may perform.
//
// Rules this projection enforces (task HXA-204):
// - Side effects are never guessed from exception text or summaries: only the closed state
//   sets and the stable terminal code sets (ModelErrorCode names, ErrorCode names,
//   BudgetStopReasons, system-stop codes) drive the classification. Model refusals and
//   content-filter stops are execution failures (a NEW call with adjusted input may still
//   succeed); only user/policy denials and explicit cancellation are rejection outcomes.
// - Unknown results reconcile only: a parked call (NEEDS_REVIEW / INTERRUPTED) or an
//   INTERRUPTED turn yields RESULT_UNKNOWN with QUERY_RESULT — never a replay operation.
// - Cancellation never auto-continues: CANCELLED yields USER_REJECTED with no automatic
//   operation; a paused Goal may still offer its independent CONTINUE_GOAL path.
// - No second recovery state machine and no unified "resume": every operation keeps its own
//   identity and its own admission re-check at execution time (slice 2 owns the buttons).
// - Missing or corrupted records fail closed: an unparseable turn state claims no block and
//   no operation; an unparseable tool-call state is treated as pending review.

/** The seven user-visible error classes (HXA-204), plus NONE for turns with no blocker. */
internal enum class RecoveryBlockClass {
    NONE,
    AUTH,
    NETWORK,
    CAPABILITY,
    USER_REJECTED,
    BUDGET,
    EXECUTION_FAILED,
    RESULT_UNKNOWN,
}

/**
 * The explicit recovery operations. Each is a distinct user action with its own identity and
 * its own admission re-check when executed; they must never be collapsed into one "resume".
 * RETRY_NEW_CALL covers the ADR-AGENT-006 budget continuation (a new bounded Turn from
 * existing results) and plain failed-turn retries alike — both create a NEW call, never a
 * resume of the old one.
 */
internal enum class RecoveryOperation {
    RECONNECT,
    QUERY_RESULT,
    GRANT_PERMISSION,
    CONTINUE_GOAL,
    RETRY_NEW_CALL,
}

/** One persisted tool_call row of the turn (identity, name, state). */
internal data class ToolCallFact(
    val callId: String,
    val toolName: String,
    val state: String,
)

/**
 * The structured facts a caller gathers for one turn (storage reads happen at the call site;
 * the projection itself is pure). [goalContinuable] is the caller's evaluation of the Goal's
 * own continue admission (GoalSummaryQuery / GoalReducer); [budgetContinuationEligible] is
 * BudgetContinuation.eligible. The projection trusts those admissions as facts and re-derives
 * nothing that would bypass them.
 */
internal data class RecoveryFacts(
    val turnState: String,
    val turnErrorCode: String?,
    val toolCalls: List<ToolCallFact>,
    val userPaused: Boolean,
    val goalBound: Boolean,
    val goalState: String?,
    val goalContinuable: Boolean,
    val budgetContinuationEligible: Boolean,
    val artifactIds: List<String>,
)

/** The read-only recovery summary the UI renders. */
internal data class RecoverySummary(
    val blockClass: RecoveryBlockClass,
    val operations: List<RecoveryOperation>,
    val nextOperation: RecoveryOperation?,
    val completedActions: List<ToolCallFact>,
    val deniedActions: List<ToolCallFact>,
    val failedActions: List<ToolCallFact>,
    val pendingReview: List<ToolCallFact>,
    val artifactIds: List<String>,
    val turnErrorCode: String?,
    val userPaused: Boolean,
    val goalState: String?,
    val goalContinuable: Boolean,
    val budgetContinuationEligible: Boolean,
) {
    val blocked: Boolean
        get() = blockClass != RecoveryBlockClass.NONE
}

/**
 * Derives the recovery summary from [facts]. Pure and total: no storage access, no throwing on
 * missing records. Operation order is presentation order; [RecoverySummary.nextOperation] is
 * the first one.
 */
internal fun recoverySummary(facts: RecoveryFacts): RecoverySummary {
    val calls = facts.toolCalls
    val pendingReview =
        calls.filter { call ->
            call.state == ToolCallState.NEEDS_REVIEW.name ||
                call.state == ToolCallState.INTERRUPTED.name ||
                call.state !in CALL_STATE_NAMES
        }
    val blockClass = blockClassFor(facts, pendingReview)
    val operations = operationsFor(blockClass, facts)
    return RecoverySummary(
        blockClass = blockClass,
        operations = operations,
        nextOperation = operations.firstOrNull(),
        completedActions = calls.filter { it.state == ToolCallState.COMPLETED.name },
        deniedActions = calls.filter { it.state == ToolCallState.DENIED.name },
        failedActions = calls.filter { it.state == ToolCallState.FAILED.name },
        pendingReview = pendingReview,
        artifactIds = facts.artifactIds,
        turnErrorCode = facts.turnErrorCode,
        userPaused = facts.userPaused,
        goalState = facts.goalState,
        goalContinuable = facts.goalContinuable,
        budgetContinuationEligible = facts.budgetContinuationEligible,
    )
}

private fun blockClassFor(
    facts: RecoveryFacts,
    pendingReview: List<ToolCallFact>,
): RecoveryBlockClass {
    val turn =
        TurnState.entries.firstOrNull { it.name == facts.turnState }
            ?: return RecoveryBlockClass.NONE
    return when {
        !turn.isTerminal && turn != TurnState.INTERRUPTED -> RecoveryBlockClass.NONE
        pendingReview.isNotEmpty() -> RecoveryBlockClass.RESULT_UNKNOWN
        turn == TurnState.INTERRUPTED -> RecoveryBlockClass.RESULT_UNKNOWN
        turn == TurnState.CANCELLED -> RecoveryBlockClass.USER_REJECTED
        turn == TurnState.FAILED -> failedBlockClass(facts)
        else -> RecoveryBlockClass.NONE
    }
}

private fun failedBlockClass(facts: RecoveryFacts): RecoveryBlockClass {
    val code = facts.turnErrorCode
    val calls = facts.toolCalls
    return when {
        code in BudgetStopReasons.turn -> RecoveryBlockClass.BUDGET
        code in AUTH_CODES -> RecoveryBlockClass.AUTH
        code in NETWORK_CODES -> RecoveryBlockClass.NETWORK
        code in CAPABILITY_CODES -> RecoveryBlockClass.CAPABILITY
        code == ErrorCode.INTERRUPTED.name -> RecoveryBlockClass.RESULT_UNKNOWN
        code == null && isDenialOnlyFailure(calls) -> RecoveryBlockClass.USER_REJECTED
        else -> RecoveryBlockClass.EXECUTION_FAILED
    }
}

/**
 * A FAILED turn whose persisted calls are ALL denials (and there is nothing else recorded) is
 * a rejection outcome: the request was refused by user/policy decision and repeating it cannot
 * succeed, so no retry operation is offered.
 */
private fun isDenialOnlyFailure(calls: List<ToolCallFact>): Boolean {
    if (calls.isEmpty()) return false
    return calls.all { it.state == ToolCallState.DENIED.name }
}

private fun operationsFor(
    blockClass: RecoveryBlockClass,
    facts: RecoveryFacts,
): List<RecoveryOperation> {
    val goalOperation: List<RecoveryOperation> =
        if (facts.goalBound && facts.goalContinuable) {
            listOf(RecoveryOperation.CONTINUE_GOAL)
        } else {
            emptyList()
        }
    return when (blockClass) {
        RecoveryBlockClass.NONE -> {
            emptyList()
        }

        // Reconcile only: query the settled result of the interrupted work, never replay it.
        RecoveryBlockClass.RESULT_UNKNOWN -> {
            listOf(RecoveryOperation.QUERY_RESULT) + goalOperation
        }

        // Cancellation is a user decision: nothing continues automatically; a paused Goal
        // keeps its own explicit continue path.
        RecoveryBlockClass.USER_REJECTED -> {
            goalOperation
        }

        // Budget stop: continue from existing results as a NEW bounded Turn (ADR-AGENT-006)
        // when its admission holds; otherwise a continuable Goal is the only offer.
        RecoveryBlockClass.BUDGET -> {
            if (facts.budgetContinuationEligible) {
                listOf(RecoveryOperation.RETRY_NEW_CALL)
            } else {
                goalOperation
            }
        }

        // Auth/network: the user repairs the connection, then starts a NEW call. Repair never
        // re-submits or re-activates the old one.
        RecoveryBlockClass.AUTH, RecoveryBlockClass.NETWORK -> {
            listOf(RecoveryOperation.RECONNECT, RecoveryOperation.RETRY_NEW_CALL)
        }

        // Capability: grant the missing permission, then a NEW call exercises it.
        RecoveryBlockClass.CAPABILITY -> {
            listOf(RecoveryOperation.GRANT_PERMISSION, RecoveryOperation.RETRY_NEW_CALL)
        }

        RecoveryBlockClass.EXECUTION_FAILED -> {
            listOf(RecoveryOperation.RETRY_NEW_CALL)
        }
    }
}

private val CALL_STATE_NAMES: Set<String> =
    ToolCallState.entries.map { it.name }.toSet()

private val AUTH_CODES: Set<String> =
    setOf(ModelErrorCode.AUTH.name, ErrorCode.PROVIDER_AUTH.name)

private val NETWORK_CODES: Set<String> =
    setOf(
        ModelErrorCode.TRANSPORT.name,
        ModelErrorCode.TIMEOUT.name,
        ModelErrorCode.RATE_LIMITED.name,
        ErrorCode.NETWORK.name,
    )

private val CAPABILITY_CODES: Set<String> = setOf(ErrorCode.PERMISSION.name)
