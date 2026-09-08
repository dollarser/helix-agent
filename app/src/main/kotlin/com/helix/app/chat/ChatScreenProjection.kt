package com.helix.app.chat

import com.helix.app.R
import com.helix.app.provider.ProviderBadgeUi
import com.helix.app.provider.ProviderService
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity

/** Repository-to-UI projection; live cards and streaming state remain owned by ChatService. */
internal class ChatScreenProjection(
    private val storage: HelixStorage,
    private val providerService: ProviderService,
    private val strings: (Int, Array<out Any>) -> String,
    private val modelTerminalCodeRes: (String) -> Int,
) {
    /**
     * The open session's tool timeline: the PERSISTED rows (tool_calls + tool_results,
     * every turn, newest session order) with the LIVE in-memory rows overlaid (the approval
     * card is a live display; its persisted identity is the approvals row). Bounded to the
     * newest [TOOL_TIMELINE_CAP] rows (doc 07 section 10: no unbounded list loads).
     */
    fun toolTimelineFor(
        sessionId: String?,
        liveRows: List<ToolTimelineRow>,
    ): List<ToolTimelineRow> {
        // No open session: KEEP the live rows as-is. A pending approval card lives ONLY
        // here (the dispatcher is still blocked in the broker while the user navigates
        // away); dropping it on close would leave the call "待审批" with no card to tap —
        // the turn un-approvable until stop or the 24h window expiry. The session-list
        // screen does not render the timeline, so this is invisible there and the overlay
        // is restored verbatim when the session reopens.
        if (sessionId == null) return liveRows
        val sessionTurns = storage.turns.listBySession(sessionId)
        val persisted =
            sessionTurns
                .flatMap { turn ->
                    storage.toolCalls
                        .listByTurn(turn.id)
                        .map { call ->
                            val result = storage.toolResults.byToolCall(call.callId)
                            val interruptedProot =
                                com.helix.app.proot
                                    .prootRecoveryEligible(turn.state, call.state) &&
                                    call.name in setOf("bash", "code.linux.run")
                            ToolTimelineRow(
                                turnId = turn.id,
                                callId = call.callId,
                                toolName = call.name,
                                requestSummary = call.argsJson,
                                stateLabel =
                                    persistedStateLabel(
                                        if (turn.state == TurnState.INTERRUPTED.name &&
                                            call.state == ToolCallState.AWAITING_APPROVAL.name
                                        ) {
                                            ToolCallState.INTERRUPTED.name
                                        } else {
                                            call.state
                                        },
                                    ),
                                resultSummary = result?.summary,
                                card = null,
                                prootRecoveryAvailable =
                                    com.helix.app.proot.ProotToolModule.AVAILABLE &&
                                        interruptedProot,
                            )
                        }
                }.takeLast(TOOL_TIMELINE_CAP)
        // Scope the overlay to THIS session's turns: a live row from another session
        // (e.g. a pending card left open when the user switched) must not appear here.
        val turnsInSession = sessionTurns.map { it.id }.toSet()
        val scoped = liveRows.filter { it.turnId in turnsInSession }
        return if (scoped.isEmpty()) {
            persisted
        } else {
            val liveByCall = scoped.associateBy { it.callId }
            persisted
                .map { row ->
                    val live = liveByCall[row.callId] ?: return@map row
                    row.copy(
                        card = live.card,
                        prootRecoveryBusy = live.prootRecoveryBusy,
                        prootRecoveryReport = live.prootRecoveryReport,
                        prootRecoveredOutput = live.prootRecoveredOutput,
                        prootResultUnavailable = live.prootResultUnavailable,
                        stateLabel = live.stateLabel,
                        resultSummary = live.resultSummary ?: row.resultSummary,
                    )
                }.plus(scoped.filter { live -> persisted.none { it.callId == live.callId } })
        }
    }

    /** A persisted tool_call state as its user label (corrupt values fail closed). */
    private fun persistedStateLabel(state: String): String =
        when (runCatching { ToolCallState.valueOf(state) }.getOrNull()) {
            ToolCallState.PENDING -> str(R.string.tool_state_processing)
            ToolCallState.AWAITING_APPROVAL -> str(R.string.tool_state_awaiting_approval)
            ToolCallState.RUNNING -> str(R.string.tool_state_running)
            ToolCallState.NEEDS_REVIEW -> str(R.string.tool_state_needs_review)
            ToolCallState.INTERRUPTED -> str(R.string.tool_state_interrupted)
            ToolCallState.COMPLETED -> str(R.string.tool_state_completed)
            ToolCallState.FAILED -> str(R.string.tool_state_failed)
            ToolCallState.CANCELLED -> str(R.string.tool_state_cancelled)
            ToolCallState.DENIED -> str(R.string.tool_state_denied)
            null -> str(R.string.tool_state_unknown)
        }

    /** The open session's persisted messages as UI rows (blank assistant rows drop out). */
    fun messagesFor(
        sessionId: String?,
        screen: ChatScreenState,
    ): List<MessageUi> {
        if (sessionId == null) return screen.messages
        return storage.messages
            .listBySession(sessionId)
            .mapNotNull { entity ->
                val content = storage.messages.readContent(entity)
                if (content.isNullOrBlank() && entity.role != ModelRole.USER.name) {
                    null
                } else {
                    MessageUi(entity.id, entity.role.lowercase(), content.orEmpty())
                }
            }
    }

    /**
     * The persisted last turn as the active-turn UI state. Corrupt enum
     * values fail closed to the conservative reading (FAILED / 请求失败) —
     * a stored row must never be shown as a healthy in-flight turn.
     */
    @Suppress("SwallowedException") // corrupt stored enum: the conservative fallback IS the handling
    fun turnUiFor(
        entity: TurnEntity,
        previousStreamingText: String?,
    ): TurnUi {
        val state =
            try {
                TurnState.valueOf(entity.state)
            } catch (e: IllegalArgumentException) {
                TurnState.FAILED
            }
        return TurnUi(
            id = entity.id,
            state = state,
            streamingText = if (state.isTerminal) null else previousStreamingText,
            errorLabel = entity.errorCode?.let { code -> str(modelTerminalCodeRes(code)) },
            retryable = state == TurnState.FAILED,
        )
    }

    /** Latest failed turn, shown as retryable only when its bound Goal permits an explicit continuation. */
    fun retryTargetFor(sessionId: String?): String? =
        sessionId
            ?.let { id ->
                storage.turns
                    .listBySession(id)
                    .lastOrNull { it.state == TurnState.FAILED.name }
                    ?.takeIf { turn ->
                        val binding = storage.goalTurnBindings.byTurn(turn.id)
                        if (binding == null) {
                            true
                        } else {
                            val goalId = storage.goalRuns.resolve(binding.runId).goalId
                            GoalSummaryQuery(storage).forSession(id).any { it.id == goalId && it.canContinue }
                        }
                    }?.id
            }

    fun badgeFor(sessionId: String): ProviderBadgeUi? {
        val session = storage.sessions.resolve(sessionId)
        val row = session.providerId?.let { pid -> providerService.rows.value.firstOrNull { it.id == pid } }
        return row?.let {
            ProviderBadgeUi(it.displayName, it.model, it.origin, it.residence, it.capabilityChips)
        }
    }

    private fun str(
        resId: Int,
        vararg args: Any,
    ): String = strings(resId, args)

    private companion object {
        const val TOOL_TIMELINE_CAP = 200
    }
}
