package com.helix.core.agent.turn

import com.helix.core.agent.AgentTurnStore
import com.helix.core.agent.ConversationReferenceIntent
import com.helix.core.agent.GoalWakeReason
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnReviewCheckpoint
import com.helix.core.agent.runtime.TurnStateReader
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ToolEffectReviewDecision
import com.helix.core.model.TurnState

data class TurnAdmissionIdentity(
    val sessionId: String,
    val turnId: String,
    val firstModelCallId: String,
    val clientRequestId: String? = null,
    val inputFingerprint: String? = null,
) {
    init {
        require(sessionId.isNotBlank() && turnId.isNotBlank() && firstModelCallId.isNotBlank())
    }
}

/** Model/provider identity, not credentials or permission to use that provider. */
data class TurnModelBinding(
    val providerId: String,
    val modelId: String,
    val snapshot: String,
)

data class TurnAdmissionInput(
    val text: String?,
    val attachments: List<TurnInputAttachment> = emptyList(),
    val references: List<ConversationReferenceIntent> = emptyList(),
)

data class QueuedAdmissionExpectation(
    val inputId: String,
    val revision: Long,
    val requireHead: Boolean,
    val configuration: TurnSteeringConfiguration,
)

sealed interface TurnHistoryMutation {
    data object None : TurnHistoryMutation

    data class Revise(
        val messageId: String,
    ) : TurnHistoryMutation

    data class Regenerate(
        val messageId: String,
    ) : TurnHistoryMutation
}

sealed interface TurnGoalIntent {
    data object None : TurnGoalIntent

    data class Explicit(
        val goalId: String,
    ) : TurnGoalIntent

    data class ReuseOrCreate(
        val objective: String,
        val criteria: List<String>,
        val budgets: GoalBudgets,
    ) : TurnGoalIntent
}

data class TurnRecoveryAdmission(
    val parentTurnId: String,
    val inspection: Boolean = false,
)

/**
 * One atomic admission. Infrastructure rechecks every expectation; these facts never grant scope.
 * Goal selection/create/bind/start stays in that transaction, not in an outer preparation step.
 */
@Suppress("LongParameterList") // Independent identity, content and expectation facts are explicit.
data class TurnAdmissionCommand(
    val identity: TurnAdmissionIdentity,
    val provider: TurnModelBinding,
    val input: TurnAdmissionInput,
    val control: RunControlConfig,
    val wakeReason: GoalWakeReason,
    val expectedSessionModelId: String?,
    val queuedInput: QueuedAdmissionExpectation? = null,
    val history: TurnHistoryMutation = TurnHistoryMutation.None,
    val recovery: TurnRecoveryAdmission? = null,
    val goal: TurnGoalIntent = TurnGoalIntent.None,
)

sealed interface SubmitReceiptDecision {
    data object Fresh : SubmitReceiptDecision

    data class Deduplicated(
        val turnId: String,
    ) : SubmitReceiptDecision

    data object Conflict : SubmitReceiptDecision
}

sealed interface SessionTurnBlocker {
    val code: String

    data class Live(
        val turnId: String,
        val state: TurnState,
    ) : SessionTurnBlocker {
        override val code = "SESSION_TURN_BUSY"
    }

    data class Conflict(
        val turnIds: List<String>,
    ) : SessionTurnBlocker {
        override val code = "SESSION_TURN_CONFLICT"
    }
}

data class AdmittedTurn(
    val coordinator: TurnRoundCoordinator,
    val control: RunControlConfig,
    val goalId: String?,
)

/** GoalRefused may retain authorized Goal/budget facts; it never claims a Turn was launched. */
sealed interface TurnAdmissionResult {
    data class Started(
        val turn: AdmittedTurn,
    ) : TurnAdmissionResult

    data class Deduplicated(
        val turnId: String,
    ) : TurnAdmissionResult

    data class Blocked(
        val blocker: SessionTurnBlocker,
    ) : TurnAdmissionResult

    data object Conflict : TurnAdmissionResult

    data object FreshRejected : TurnAdmissionResult

    data object GoalRefused : TurnAdmissionResult
}

data class TurnLifecycleSnapshot(
    val turnId: String,
    val sessionId: String,
    val phase: TurnState,
)

data class CancelTurnCommand(
    val turnId: String,
    val sessionId: String,
    val hasLiveDriver: Boolean,
    val parkTerminalDelivery: Boolean,
    val reason: String,
)

sealed interface TurnCancellationReceipt {
    data object NotFound : TurnCancellationReceipt

    data class AlreadyTerminal(
        val phase: TurnState,
    ) : TurnCancellationReceipt

    data object SignalLive : TurnCancellationReceipt

    data object ReviewRequired : TurnCancellationReceipt
}

data class ToolReviewSubmission(
    val toolCallId: String,
    val decision: ToolEffectReviewDecision,
    val reviewedAt: Long? = null,
)

data class ReviewResolutionCommand(
    val turnId: String,
    val clientActionId: String,
    val expectedTurnState: TurnState? = null,
    val submissions: List<ToolReviewSubmission> = emptyList(),
)

sealed interface TurnReviewResolutionResult {
    data class Resolved(
        val turnId: String,
    ) : TurnReviewResolutionResult
}

data class TurnRecoveryReport(
    val interruptedTurns: Map<String, List<String>>,
    val cancelledToolCalls: Map<String, List<String>>,
    val parkedToolCalls: Map<String, List<String>>,
    val parkedGoals: List<String>,
    val closedRuns: List<String>,
)

data class TurnGoalDeliveryInvalidation(
    val sessionId: String,
    val goalId: String,
)

data class TurnTerminalReleaseReceipt(
    val discardedGoals: List<TurnGoalDeliveryInvalidation> = emptyList(),
    val recoveryParentTurnId: String? = null,
)

/** Closed durable operations; no entity, arbitrary transaction callback or alternate state owner. */
@Suppress("TooManyFunctions") // One lifecycle's atomic operations, separate from the round journal.
interface TurnLifecycleStore :
    AgentTurnStore,
    TurnStateReader {
    fun admit(command: TurnAdmissionCommand): TurnAdmissionResult

    fun submissionReceipt(
        clientRequestId: String,
        sessionId: String,
        inputFingerprint: String,
    ): SubmitReceiptDecision

    fun recoveryPredecessor(
        sessionId: String,
        clientRequestId: String,
    ): String?

    fun sessionBlocker(sessionId: String): SessionTurnBlocker?

    fun findTurn(turnId: String): TurnLifecycleSnapshot?

    fun requestGoalPause(turnId: String): Boolean

    fun cancel(command: CancelTurnCommand): TurnCancellationReceipt

    fun parkForReview(checkpoint: TurnReviewCheckpoint)

    suspend fun resolveReview(command: ReviewResolutionCommand): TurnReviewResolutionResult

    fun recoverOnStartup(): TurnRecoveryReport

    fun finishAutomaticRecovery(
        parentId: String,
        reason: String,
        inspectionFailed: Boolean,
    )

    /** Post-terminal queue/Goal facts; a failure must not cause another terminal/model execution. */
    fun prepareTerminalRelease(
        turnId: String,
        sessionId: String,
        outcome: ModelStreamTerminal,
    ): TurnTerminalReleaseReceipt

    fun parkFailedTerminalDelivery(
        turnId: String,
        sessionId: String,
    )

    fun parkUnknownDelivery(
        turnId: String,
        sessionId: String,
    )
}
