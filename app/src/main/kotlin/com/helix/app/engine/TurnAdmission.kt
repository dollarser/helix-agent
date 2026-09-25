package com.helix.app.engine

import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.chat.GoalRunCoordinator
import com.helix.app.chat.GoalTurnStart
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity

/** Durable Turn-start boundary: receipt/session/history/Goal/Turn/runtime facts commit together. */
internal class TurnAdmission(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    fun recoveryPredecessor(
        sessionId: String,
        clientRequestId: String,
    ): String? {
        val existing = storage.turns.resolveByClientRequestId(clientRequestId)
        if (existing != null && existing.sessionId == sessionId) return existing.recoveryFromTurnId
        val latest = storage.turns.listBySession(sessionId).lastOrNull()
        return latest?.id?.takeIf {
            TurnState.valueOf(latest.state) in setOf(TurnState.INTERRUPTED, TurnState.NEEDS_REVIEW)
        }
    }

    fun receipt(
        clientRequestId: String,
        sessionId: String,
        inputFingerprint: String,
    ): SubmitReceiptDecision =
        submitReceiptDecision(
            storage.turns.resolveByClientRequestId(clientRequestId),
            sessionId,
            inputFingerprint,
        )

    fun start(
        spec: TurnStartSpec,
        control: RunControlConfig,
        wakeReason: GoalWakeReason,
        providerId: String,
        modelId: String,
        freshGuard: () -> Boolean = { true },
        resolveGoal: () -> String? = { null },
    ): TurnAdmissionResult {
        var result: TurnAdmissionResult? = null
        storage.withTransaction {
            val receipt = receipt(spec)
            if (receipt is SubmitReceiptDecision.Deduplicated) {
                result = TurnAdmissionResult.Deduplicated(receipt.turnId)
                return@withTransaction
            }
            if (receipt == SubmitReceiptDecision.Conflict) {
                result = TurnAdmissionResult.Conflict
                return@withTransaction
            }
            DurableSessionGate(storage).blocker(spec.sessionId)?.let {
                result = TurnAdmissionResult.Blocked(it)
                return@withTransaction
            }
            if (!freshGuard()) {
                result = TurnAdmissionResult.FreshRejected
                return@withTransaction
            }
            validateRecoveryPredecessor(spec)

            val goalId = resolveGoal()
            applyHistoryMutation(spec, goalId)
            val goalStart =
                goalId?.let {
                    GoalRunCoordinator(storage, clock, idGenerator).start(
                        GoalTurnStart(it, wakeReason, spec, control.budgets),
                    )
                }
            if (goalId != null && goalStart == null) {
                result = TurnAdmissionResult.GoalRefused
                return@withTransaction
            }

            val coordinator = goalStart?.coordinator ?: TurnCoordinator.start(storage, clock, idGenerator, spec)
            val effectiveControl =
                goalStart?.let { control.copy(mode = AgentMode.GOAL, budgets = it.budgets) } ?: control
            storage.turnRuntimeRecords.create(
                TurnRuntimeRecordCodec.startRecord(
                    turnId = spec.turnId,
                    providerId = providerId,
                    modelId = modelId,
                    providerSnapshot = spec.providerSnapshot,
                    control = effectiveControl,
                ),
            )
            result = TurnAdmissionResult.Started(AdmittedTurn(coordinator, effectiveControl, goalId))
        }
        return requireNotNull(result) { "Turn admission produced no decision" }
    }

    private fun validateRecoveryPredecessor(spec: TurnStartSpec) {
        val predecessorId = spec.recoveryFromTurnId ?: return
        require(predecessorId != spec.turnId) { "RECOVERY_PREDECESSOR_SELF" }
        val predecessor =
            requireNotNull(storage.turns.find(predecessorId)) {
                "RECOVERY_PREDECESSOR_NOT_FOUND"
            }
        require(predecessor.sessionId == spec.sessionId) { "RECOVERY_PREDECESSOR_SESSION_MISMATCH" }
        val state = TurnState.valueOf(predecessor.state)
        require(state in setOf(TurnState.INTERRUPTED, TurnState.NEEDS_REVIEW)) {
            "RECOVERY_PREDECESSOR_STATE_INVALID: $state"
        }
    }

    private fun receipt(spec: TurnStartSpec): SubmitReceiptDecision {
        val requestId = spec.clientRequestId
        val fingerprint = spec.inputFingerprint
        return if (requestId == null || fingerprint == null) {
            SubmitReceiptDecision.Fresh
        } else {
            receipt(requestId, spec.sessionId, fingerprint)
        }
    }

    /**
     * REVISE/REGENERATE is an admission concern, not a TurnCoordinator concern. The caller's outer
     * Room transaction commits this mutation together with Goal/Turn/ModelCall/runtime creation.
     */
    private fun applyHistoryMutation(
        spec: TurnStartSpec,
        goalId: String?,
    ) {
        spec.revisedMessageId?.let { messageId ->
            require(spec.userText != null && goalId == null) { "REVISION_REQUIRES_PLAIN_USER_TURN" }
            storage.messages.reviseLatest(spec.sessionId, messageId, requireNotNull(spec.clientRequestId))
        }
        spec.regenerateMessageId?.let { messageId ->
            require(spec.userText == null) { "REGENERATE_USER_TEXT_NOT_ALLOWED" }
            require(spec.revisedMessageId == null) { "CANNOT_REVISE_AND_REGENERATE" }
            storage.messages.regenerateLatest(spec.sessionId, messageId, requireNotNull(spec.clientRequestId))
        }
    }

    private fun submitReceiptDecision(
        existing: TurnEntity?,
        sessionId: String,
        fingerprint: String,
    ): SubmitReceiptDecision = SubmitReceipt.decide(existing, sessionId, fingerprint)
}

/** Pure request-receipt rule shared by Engine admission and JVM contract tests. */
internal object SubmitReceipt {
    fun decide(
        existing: TurnEntity?,
        incomingSessionId: String,
        incomingFingerprint: String,
    ): SubmitReceiptDecision =
        when {
            existing == null -> {
                SubmitReceiptDecision.Fresh
            }

            existing.sessionId == incomingSessionId && existing.inputFingerprint == incomingFingerprint -> {
                SubmitReceiptDecision.Deduplicated(existing.id)
            }

            else -> {
                SubmitReceiptDecision.Conflict
            }
        }
}

internal sealed interface SubmitReceiptDecision {
    data object Fresh : SubmitReceiptDecision

    data class Deduplicated(
        val turnId: String,
    ) : SubmitReceiptDecision

    data object Conflict : SubmitReceiptDecision
}

internal sealed interface TurnAdmissionResult {
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

internal data class AdmittedTurn(
    val coordinator: TurnCoordinator,
    val control: RunControlConfig,
    val goalId: String?,
)
