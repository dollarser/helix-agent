package com.helix.app.chat

import com.helix.app.ui.RecoveryBlockClass
import com.helix.app.ui.RecoveryOperation
import com.helix.app.ui.RecoverySummary
import com.helix.app.ui.recoverySummary
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * HXA-204 slice 2: executes the panel operations. Each operation keeps its OWN identity and
 * re-checks its OWN admission against freshly read persisted facts at execution time — a stale
 * button (a duplicate tap, a late result, a repair made elsewhere) must re-earn its admission
 * now, not trust the render-time panel. Nothing here resumes or replays: reconnect and grant
 * navigate to the repair surface, query reconciles persisted facts (and inspects parked proot
 * jobs read-only), and continue / retry each start their own explicit new work through the
 * existing services.
 */
internal class TurnRecoveryActions(
    private val storage: HelixStorage,
    private val workScope: CoroutineScope,
    private val screenState: MutableStateFlow<ChatScreenState>,
    private val retryTargetFor: (String?) -> String?,
    private val onContinueGoal: (String, String) -> Unit,
    private val onRetry: () -> Unit,
    private val onInspectProot: (String, String, Boolean) -> Unit,
    private val onReconciled: () -> Unit,
) {
    private val mutex = Mutex()

    /**
     * Auth/network repair: the user fixes the provider, then starts a NEW call — no re-submit.
     * Returns freshly admitted facts; the requesting page owns navigation and its lifecycle.
     */
    fun reconnect(turnId: String) = repairTarget(turnId, RecoveryOperation.RECONNECT, RecoveryRepairDestination.MODELS)

    /**
     * Unknown result: reconcile only. Inspect the parked proot jobs read-only (developer
     * builds), then re-read the persisted facts — the turn is never replayed.
     */
    fun queryResult(turnId: String) =
        execute(turnId, RecoveryOperation.QUERY_RESULT) { summary, source ->
            if (com.helix.app.proot.ProotToolModule.AVAILABLE) {
                summary.pendingReview
                    .filter { it.toolName in PROOT_TOOL_NAMES }
                    .forEach { call -> onInspectProot(source.turnId, call.callId, false) }
            }
            onReconciled()
        }

    /** Capability repair: the user grants the missing permission, then a NEW call exercises it. */
    fun grantPermission(turnId: String) =
        repairTarget(turnId, RecoveryOperation.GRANT_PERMISSION, RecoveryRepairDestination.PERMISSIONS)

    private fun repairTarget(
        turnId: String,
        operation: RecoveryOperation,
        destination: RecoveryRepairDestination,
    ) = workScope.async {
        mutex.withLock {
            val sessionId = screenState.value.openSessionId ?: return@withLock null
            val source = admittedSource(turnId, operation) ?: return@withLock null
            RecoveryRepairTarget(sessionId, source.second.turnId, destination, source.first.blockClass)
                .takeIf { screenState.value.openSessionId == sessionId }
        }
    }

    /** The bound Goal's own explicit continue path — its admission was just re-verified. */
    fun continueGoal(turnId: String) =
        execute(turnId, RecoveryOperation.CONTINUE_GOAL) { _, source ->
            val goalId = source.goalId
            if (goalId != null) onContinueGoal(goalId, source.goalObjective.orEmpty())
        }

    /**
     * A NEW call for the retry target (plain retry or the ADR-AGENT-006 budget continuation).
     * The panel button renders only on the single admitted retry target; re-verify that
     * admission now, then hand off to the existing retry, which re-checks its full gate.
     */
    fun retryNewCall(turnId: String) =
        execute(turnId, RecoveryOperation.RETRY_NEW_CALL) { _, _ ->
            if (retryTargetFor(screenState.value.openSessionId) == turnId) onRetry()
        }

    private fun execute(
        turnId: String,
        operation: RecoveryOperation,
        body: suspend (RecoverySummary, TurnRecoverySource) -> Unit,
    ) {
        workScope.launch {
            mutex.withLock {
                admittedSource(turnId, operation)?.let { (summary, source) ->
                    setBusy(turnId, operation, true)
                    try {
                        body(summary, source)
                    } finally {
                        setBusy(turnId, operation, false)
                    }
                }
            }
        }
    }

    private fun admittedSource(
        turnId: String,
        operation: RecoveryOperation,
    ): Pair<RecoverySummary, TurnRecoverySource>? {
        val sessionId = screenState.value.openSessionId ?: return null
        val source =
            loadTurnRecoverySources(storage, sessionId, includeTurnId = retryTargetFor(sessionId))
                .singleOrNull { it.turnId == turnId }
        return source?.let {
            val summary = recoverySummary(it.facts)
            val supersededRetry = operation == RecoveryOperation.RETRY_NEW_CALL && it.supersededByCompleted
            (summary to it).takeIf { summary.blocked && operation in summary.operations && !supersededRetry }
        }
    }

    private fun setBusy(
        turnId: String,
        operation: RecoveryOperation,
        busy: Boolean,
    ) {
        val key = "$turnId:${operation.name}"
        screenState.update { current ->
            current.copy(
                recoveryBusy =
                    if (busy) {
                        current.recoveryBusy + key
                    } else {
                        current.recoveryBusy - key
                    },
            )
        }
    }

    private companion object {
        val PROOT_TOOL_NAMES: Set<String> = setOf("bash", "code.linux.run")
    }
}

internal enum class RecoveryRepairDestination { MODELS, PERMISSIONS }

internal data class RecoveryRepairTarget(
    val sessionId: String,
    val turnId: String,
    val destination: RecoveryRepairDestination,
    val reason: RecoveryBlockClass,
) {
    fun matches(
        currentSessionId: String?,
        requestedTurnId: String,
    ): Boolean = sessionId == currentSessionId && turnId == requestedTurnId
}
