package com.helix.app.chat

import com.helix.app.ui.RecoveryOperation
import com.helix.app.ui.RecoverySummary
import com.helix.app.ui.recoverySummary
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val onOpenSettings: () -> Unit,
    private val onContinueGoal: (String, String) -> Unit,
    private val onRetry: () -> Unit,
    private val onInspectProot: (String, String, Boolean) -> Unit,
    private val onReconciled: () -> Unit,
) {
    private val mutex = Mutex()

    /**
     * Auth/network repair: the user fixes the provider, then starts a NEW call — no re-submit.
     * The navigation to the repair surface hops to the main thread (this service runs on its
     * IO scope) so the NavController back-stack lifecycle is driven on the main thread.
     */
    fun reconnect(turnId: String) =
        execute(turnId, RecoveryOperation.RECONNECT) { _, _ ->
            withContext(Dispatchers.Main.immediate) { onOpenSettings() }
        }

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
        execute(turnId, RecoveryOperation.GRANT_PERMISSION) { _, _ ->
            withContext(Dispatchers.Main.immediate) { onOpenSettings() }
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
                val sessionId = screenState.value.openSessionId
                val retryTarget = sessionId?.let { retryTargetFor(it) }
                val source =
                    sessionId?.let { id ->
                        loadTurnRecoverySources(storage, id, includeTurnId = retryTarget)
                            .singleOrNull { it.turnId == turnId }
                    }
                val summary = source?.let { recoverySummary(it.facts) }
                // The render-time retry admission (retryAllowed) is re-earned here: a late
                // successful result settling between render and click supersedes the failure
                // and must void the retry, exactly as the panel button would have disappeared.
                val admitted =
                    source != null &&
                        summary != null &&
                        summary.blocked &&
                        operation in summary.operations &&
                        (
                            operation != RecoveryOperation.RETRY_NEW_CALL ||
                                !source.supersededByCompleted
                        )
                if (admitted) {
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
