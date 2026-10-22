package com.helix.app.chat

import com.helix.app.R
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.app.proot.ProotToolModule
import com.helix.app.provider.ProviderService
import com.helix.app.provider.SelectedModelUi
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Repository-to-UI projection; live cards and streaming state remain owned by ChatService. */
internal class ChatScreenProjection(
    private val storage: HelixStorage,
    private val providerService: ProviderService,
    private val strings: (Int, Array<out Any>) -> String,
    private val modelTerminalCodeRes: (String) -> Int,
) {
    /** Load a display window independently of the model's effective history. */
    fun historyFor(
        sessionId: String?,
        current: ChatScreenState,
    ): ChatScreenState {
        val sameSession = current.openSessionId == sessionId
        val limit = if (sameSession) current.historyLimit else HISTORY_PAGE_SIZE
        val through = if (sameSession) current.historyThrough else Long.MAX_VALUE
        val page = sessionId?.let { storage.messages.recentBySession(it, limit + 1, through) }.orEmpty()
        val history = page.takeLast(limit)
        return ChatScreenState(
            sessions = current.sessions,
            openSessionId = sessionId,
            selectedModel = null,
            activeTurn = null,
            pendingDisclosure = current.pendingDisclosure,
            blockedReason = current.blockedReason,
            retryTargetTurnId = null,
            messages = messagesFor(sessionId, current, history),
            historyLimit = limit,
            hasOlderMessages = page.size > limit,
            historyThrough = through,
            hasNewerMessages = through != Long.MAX_VALUE,
            focusedHistoryMessage = current.focusedHistoryMessage.takeIf { sameSession },
            historyFocusRevision = current.historyFocusRevision,
            toolTimeline = toolTimelineFor(sessionId, current.toolTimeline, history, through),
        )
    }

    /** Bounded persisted timeline with active approvals and recovery controls overlaid. */
    fun toolTimelineFor(
        sessionId: String?,
        liveRows: List<ToolTimelineRow>,
        history: List<com.helix.core.storage.entity.MessageEntity> =
            sessionId?.let { storage.messages.recentBySession(it, HISTORY_PAGE_SIZE) }.orEmpty(),
        throughSequence: Long = Long.MAX_VALUE,
    ): List<ToolTimelineRow> {
        // No open session: KEEP the live rows as-is. A pending approval card lives ONLY
        // here (the dispatcher is still blocked in the broker while the user navigates
        // away); dropping it on close would leave the call "待审批" with no card to tap —
        // the turn un-approvable until stop or the 24h window expiry. The session-list
        // screen does not render the timeline, so this is invisible there and the overlay
        // is restored verbatim when the session reopens.
        if (sessionId == null) return liveRows
        val superseded = storage.messages.supersededTurns(sessionId)
        val sessionTurns = storage.turns.listBySession(sessionId).filterNot { it.id in superseded }
        val persisted =
            persistedToolTimeline(
                sessionId,
                history.firstOrNull()?.sequence ?: Long.MAX_VALUE,
                throughSequence,
                ChatTimelineOrder.positions(storage, history),
            )
        // Scope the overlay to THIS session's turns: a live row from another session
        // (e.g. a pending card left open when the user switched) must not appear here.
        val scoped = scopedLiveRows(sessionTurns, persisted, liveRows)
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
                        modelIntent = live.modelIntent ?: row.modelIntent,
                        durationMs = live.durationMs ?: row.durationMs,
                        stateLabel = live.stateLabel,
                        resultSummary = live.resultSummary ?: row.resultSummary,
                    )
                }.plus(scoped.filter { live -> persisted.none { it.callId == live.callId } })
        }
    }

    private fun persistedToolTimeline(
        sessionId: String,
        fromSequence: Long,
        throughSequence: Long,
        positions: Map<String, ToolTimelinePosition>,
    ): List<ToolTimelineRow> =
        storage.toolCalls
            .recentBySession(sessionId, fromSequence, throughSequence, TOOL_TIMELINE_CAP)
            .map { call ->
                val turn = storage.turns.resolve(call.turnId)
                val result = storage.toolResults.byToolCall(call.callId)
                val interruptedProot =
                    com.helix.app.proot
                        .prootRecoveryEligible(turn.state, call.state) &&
                        call.name in setOf("bash", "code.linux.run")
                ToolTimelineRow(
                    turnId = turn.id,
                    callId = call.callId,
                    toolName = call.name,
                    modelIntent = call.modelIntent,
                    requestSummary = call.argsJson,
                    stateLabel = str(persistedStateLabelRes(persistedCallState(turn, call.state))),
                    resultSummary = result?.summary,
                    card = null,
                    position =
                        call.modelCallId?.let { modelId ->
                            storage.modelCalls.find(modelId)?.toolMessageId?.let { messageId ->
                                ToolTimelinePosition(
                                    storage.messages.resolve(messageId).sequence,
                                    requireNotNull(call.callIndex),
                                )
                            }
                        } ?: positions[call.callId],
                    prootRecoveryAvailable = ProotToolModule.AVAILABLE && interruptedProot,
                )
            }

    /** Conversation text only; tool protocol rows are shown through the collapsed tool timeline. */
    fun messagesFor(
        sessionId: String?,
        screen: ChatScreenState,
        history: List<com.helix.core.storage.entity.MessageEntity> =
            sessionId?.let { storage.messages.recentBySession(it, HISTORY_PAGE_SIZE) }.orEmpty(),
    ): List<MessageUi> {
        if (sessionId == null) return screen.messages
        val edits =
            try {
                MessageEditHistory.read(storage, sessionId)
            } catch (
                _: java.io.IOException,
            ) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        val messages =
            history
                .filter {
                    (
                        it.role in setOf(ModelRole.USER.name, ModelRole.ASSISTANT.name) ||
                            (it.role == "SYSTEM" && it.kind == "RECOVERY_NOTICE")
                    ) &&
                        it.kind !in
                        setOf(
                            ContextCompaction.KIND,
                            ChatHistoryBuilder.KIND_TOOL_CALLS,
                            ChatHistoryBuilder.KIND_TOOL_RESULT,
                            UserQuestionService.KIND,
                        )
                }.mapNotNull { entity ->
                    val content = storage.messages.readContent(entity)
                    if (content.isNullOrBlank() && entity.role != ModelRole.USER.name) {
                        null
                    } else {
                        MessageUi(
                            entity.id,
                            entity.role.lowercase(),
                            content.orEmpty(),
                            entity.turnId,
                            entity.sequence,
                            images = messageImages(storage, sessionId, entity.id),
                            historyContext =
                                when {
                                    entity.role != ModelRole.ASSISTANT.name -> MessageHistoryContext.CURRENT
                                    edits == null -> MessageHistoryContext.UNAVAILABLE
                                    edits.any { entity.sequence in it } -> MessageHistoryContext.BEFORE_EDIT
                                    else -> MessageHistoryContext.CURRENT
                                },
                        )
                    }
                }
        return MessagePresentationPolicy.apply(
            history,
            messages,
            storage.turns.listBySession(sessionId).associate {
                it.id to
                    it.state
            },
        )
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
            errorLabel =
                entity.errorCode?.let { code ->
                    val label =
                        com.helix.app.provider.BuiltInModel
                            .errorLabelForTurn(storage, entity.id, code)
                    str(label ?: modelTerminalCodeRes(code))
                },
            retryable = state == TurnState.FAILED,
            continueFromResults = BudgetContinuation.eligible(storage, entity),
            budgetDetail = budgetDetail(entity),
        )
    }

    @Suppress("ReturnCount") // Missing/corrupt diagnostic fields omit detail rather than breaking chat rendering.
    private fun budgetDetail(entity: TurnEntity): String? {
        if (entity.errorCode !in com.helix.app.runcontrol.BudgetStopReasons.turn) return null
        val call = storage.modelCalls.listByTurn(entity.id).lastOrNull() ?: return null
        val event =
            storage.auditEvents.listByCorrelation(call.id).lastOrNull { it.type == "budget.request" } ?: return null
        val fields =
            runCatching { Json.parseToJsonElement(event.redactedPayload).jsonObject }.getOrNull() ?: return null
        val keys =
            listOf(
                "input",
                "inputLimit",
                "instructions",
                "tools",
                "history",
                "images",
                "used",
                "totalLimit",
                "admissionInput",
                "window",
            )
        val numbers = keys.map { (fields[it] as? JsonPrimitive)?.longOrNull?.takeIf { n -> n >= 0 } ?: return null }
        return strings(R.string.budget_request_detail, numbers.toTypedArray())
    }

    /** Latest failed turn, shown as retryable only when its bound Goal permits an explicit continuation. */
    fun retryTargetFor(sessionId: String?): String? =
        sessionId
            ?.let { id ->
                storage.turns
                    .listBySession(id)
                    .lastOrNull { it.state == TurnState.FAILED.name && it.id !in storage.messages.supersededTurns(id) }
                    ?.takeIf { turn ->
                        val binding = storage.goalTurnBindings.byTurn(turn.id)
                        if (binding == null) {
                            turn.errorCode !in com.helix.app.runcontrol.BudgetStopReasons.continuable ||
                                BudgetContinuation.eligible(storage, turn)
                        } else {
                            val goalId = storage.goalRuns.resolve(binding.runId).goalId
                            GoalSummaryQuery(storage).forSession(id).any { it.id == goalId && it.canContinue }
                        }
                    }?.id
            }

    fun selectedModelFor(sessionId: String): SelectedModelUi? {
        val session = storage.sessions.resolve(sessionId)
        return selectedModelForProvider(session.providerId, session.modelId)
    }

    fun selectedModelForProvider(
        providerId: String?,
        modelId: String? = null,
    ): SelectedModelUi? {
        if (!hasSelectedConversationModel(providerId, modelId)) return null
        val row = providerId?.let { pid -> providerService.rows.value.firstOrNull { it.id == pid } }
        return row?.let {
            SelectedModelUi(it.id, modelId ?: it.model)
        }
    }

    private fun str(
        resId: Int,
        vararg args: Any,
    ): String = strings(resId, args)

    private companion object {
        const val TOOL_TIMELINE_CAP = 200
        const val HISTORY_PAGE_SIZE = 100
    }
}

private fun persistedCallState(
    turn: TurnEntity,
    callState: String,
): String =
    if (turn.state == TurnState.INTERRUPTED.name && callState == ToolCallState.AWAITING_APPROVAL.name) {
        ToolCallState.INTERRUPTED.name
    } else {
        callState
    }

internal fun scopedLiveRows(
    turns: List<TurnEntity>,
    persisted: List<ToolTimelineRow>,
    live: List<ToolTimelineRow>,
): List<ToolTimelineRow> {
    val owned = turns.map { it.id }.toSet()
    val active = turns.filterNot { TurnState.valueOf(it.state).isTerminal }.map { it.id }.toSet()
    val selected = persisted.map { it.callId }.toSet()
    // Do not reintroduce completed rows from a previously displayed history window.
    return live.filter {
        it.turnId in owned &&
            (it.callId in selected || it.turnId in active || it.card != null || it.prootRecoveryBusy)
    }
}

/** A persisted tool_call state as its user label (corrupt values fail closed). */
private fun persistedStateLabelRes(state: String): Int =
    when (runCatching { ToolCallState.valueOf(state) }.getOrNull()) {
        ToolCallState.PENDING -> R.string.tool_state_processing
        ToolCallState.AWAITING_APPROVAL -> R.string.tool_state_awaiting_approval
        ToolCallState.RUNNING -> R.string.tool_state_running
        ToolCallState.NEEDS_REVIEW -> R.string.tool_state_needs_review
        ToolCallState.INTERRUPTED -> R.string.tool_state_interrupted
        ToolCallState.COMPLETED -> R.string.tool_state_completed
        ToolCallState.FAILED -> R.string.tool_state_failed
        ToolCallState.CANCELLED -> R.string.tool_state_cancelled
        ToolCallState.DENIED -> R.string.tool_state_denied
        null -> R.string.tool_state_unknown
    }
