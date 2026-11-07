package com.helix.feature.conversation.presentation

import com.helix.core.agent.TurnInterruptionNotice
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import com.helix.feature.conversation.application.ConversationHistorySnapshot
import com.helix.feature.conversation.application.ConversationMessageVisibility
import com.helix.feature.conversation.application.ConversationScreenSnapshot
import com.helix.feature.conversation.application.ConversationTimelineTurn
import com.helix.feature.conversation.application.ConversationTurnSnapshot

/** Pure screen projection. Reads, durable eligibility and localized diagnostic loading are ports. */
class ChatScreenProjection(
    private val toolStateLabel: (ToolCallState?) -> String,
    private val cancelledBeforeStart: () -> String,
    private val bindingIssueLabel: (ProviderBindingIssue) -> String,
) {
    /** Pure CAS body: a refresh never reads Room or copies streaming text across Turn identities. */
    fun applyScreen(
        snapshot: ConversationScreenSnapshot,
        current: ChatScreenState,
        currentRevision: Long,
    ): ChatScreenState {
        if (!snapshot.request.matches(current, currentRevision)) return current
        val history = historyFor(snapshot.history, current)
        val session = snapshot.session
        val sameSession = current.openSessionId == session?.id
        val blocked =
            if (session?.modelBindingIssue == null && sameSession &&
                current.modelBindingIssue?.let(bindingIssueLabel) == current.blockedReason
            ) {
                null
            } else {
                history.blockedReason
            }
        val subscriptions =
            snapshot.subscriptionRecoveries.map { fact ->
                (
                    current.subscriptionRecoveries.singleOrNull {
                        sameSession && it.turnId == fact.turnId && it.modelCallId == fact.modelCallId
                    } ?: SubscriptionRecoveryUi(fact.turnId, fact.modelCallId)
                ).copy(localResultAvailable = fact.localResultAvailable)
            }
        val visibleTurns = history.messages.mapNotNull { it.turnId }.toSet() + listOfNotNull(snapshot.activeTurn?.id)
        return history.copy(
            sessionTitle = session?.title.orEmpty(),
            directoryRef = session?.directoryRef,
            projectId = session?.projectId,
            isDraft = false,
            isNewConversation = session?.isNewConversation == true,
            workspaceRecovered = session?.workspaceRecovered == true,
            selectedModel = session?.selectedModel,
            modelBindingIssue = session?.modelBindingIssue,
            modelSelectionSnapshot = session?.modelSelection,
            contextUsage = session?.contextUsage ?: ChatContextUsage.emptyConversation(),
            subscriptionRecoveries = subscriptions,
            activeTurn = activeTurnFor(snapshot, current),
            turns = snapshot.turns.filter { it.id in visibleTurns }.map { turnUiFor(it, null) },
            blockedReason = blocked,
            retryTargetTurnId = snapshot.retryTargetTurnId,
            recoveryPanels = recoveryPanelsFor(snapshot.recoverySources, snapshot.retryTargetTurnId),
            taskLedger = session?.taskLedger.orEmpty(),
            hasGoals = session?.hasGoals == true,
            isFork = session?.isFork == true,
        )
    }

    private fun activeTurnFor(
        snapshot: ConversationScreenSnapshot,
        current: ChatScreenState,
    ): TurnUi? {
        val previous = current.activeTurn.takeIf { current.openSessionId == snapshot.session?.id }
        return snapshot.activeTurn?.let { fact ->
            val projected = turnUiFor(fact, previous?.takeIf { it.id == fact.id }?.streamingText)
            if (previous?.id == fact.id && previous != snapshot.request.sourceActiveTurn &&
                !projected.state.isTerminal
            ) {
                previous
            } else {
                projected
            }
        }
    }

    /** Use inside StateFlow.update: stale reads never replace a newer navigation or history window. */
    fun applyHistory(
        snapshot: ConversationHistorySnapshot,
        current: ChatScreenState,
        currentRevision: Long,
    ): ChatScreenState =
        if (snapshot.request.matches(current, currentRevision)) historyFor(snapshot, current) else current

    /** Merge persisted history with the latest transient state, not the state captured before IO. */
    fun historyFor(
        snapshot: ConversationHistorySnapshot,
        current: ChatScreenState,
    ): ChatScreenState {
        val sameSession = current.openSessionId == snapshot.sessionId
        return current.copy(
            openSessionId = snapshot.sessionId,
            selectedModel = current.selectedModel.takeIf { sameSession },
            activeTurn = current.activeTurn.takeIf { sameSession },
            blockedReason = current.blockedReason.takeIf { sameSession },
            blockedReasonEpoch = current.blockedReasonEpoch + if (sameSession) 0 else 1,
            retryTargetTurnId = current.retryTargetTurnId.takeIf { sameSession },
            messages = if (snapshot.sessionId == null) current.messages else messagesFor(snapshot),
            historyLimit = snapshot.request.limit,
            hasOlderMessages = snapshot.hasOlderMessages,
            historyThrough = snapshot.request.through,
            hasNewerMessages = snapshot.request.through != Long.MAX_VALUE,
            focusedHistoryMessage = current.focusedHistoryMessage.takeIf { sameSession },
            toolTimeline = toolTimelineFor(snapshot, current.toolTimeline),
        )
    }

    /** Conversation text only; tool protocol rows remain available to the presentation policy. */
    fun messagesFor(snapshot: ConversationHistorySnapshot): List<MessageUi> {
        val messages =
            snapshot.messages.mapNotNull { message ->
                val fact = message.presentation
                val visible =
                    message.visibility != ConversationMessageVisibility.HIDDEN &&
                        (
                            fact.role in setOf("USER", "ASSISTANT") ||
                                (
                                    fact.role == "SYSTEM" &&
                                        message.visibility == ConversationMessageVisibility.INPUT_RECOVERY_NOTICE
                                )
                        )
                if (!visible || (message.content.isNullOrBlank() && fact.role != "USER")) {
                    null
                } else {
                    MessageUi(
                        fact.id,
                        fact.role.lowercase(),
                        message.content.orEmpty(),
                        fact.turnId,
                        fact.sequence,
                        images = message.images,
                        knowledgeReferences = message.knowledgeReferences,
                        historyContext =
                            when {
                                fact.role != "ASSISTANT" -> MessageHistoryContext.CURRENT
                                snapshot.editRanges == null -> MessageHistoryContext.UNAVAILABLE
                                snapshot.editRanges.any { fact.sequence in it } -> MessageHistoryContext.BEFORE_EDIT
                                else -> MessageHistoryContext.CURRENT
                            },
                    )
                }
            }
        val notices =
            snapshot.notices
                .mapNotNull { (turnId, fact) ->
                    TurnInterruptionNotice
                        .forDisplay(
                            fact.saved,
                            fact.state,
                            fact.userStopped,
                            fact.unresolvedEffects,
                            fact.inspection,
                        )?.let { turnId to it }
                }.toMap()
        return MessagePresentationPolicy.apply(
            snapshot.messages.map { it.presentation },
            attachTurnNotices(messages, notices),
            snapshot.turnStates,
        )
    }

    /** Persisted original-call order with only this session's live controls overlaid. */
    fun toolTimelineFor(
        snapshot: ConversationHistorySnapshot,
        liveRows: List<ToolTimelineRow>,
    ): List<ToolTimelineRow> {
        // Closing a screen cannot discard the only approval card while its dispatcher is waiting.
        if (snapshot.sessionId == null) return liveRows
        val persisted =
            snapshot.tools.map { call ->
                val state =
                    if (call.turnState == TurnState.INTERRUPTED.name &&
                        call.state == ToolCallState.AWAITING_APPROVAL.name
                    ) {
                        ToolCallState.INTERRUPTED.name
                    } else {
                        call.state
                    }
                val parsed = ToolCallState.entries.firstOrNull { it.name == state }
                ToolTimelineRow(
                    turnId = call.turnId,
                    callId = call.callId,
                    toolName = call.toolName,
                    modelIntent = call.modelIntent,
                    requestSummary = call.requestSummary,
                    stateLabel = toolStateLabel(parsed),
                    state = parsed ?: ToolCallState.NEEDS_REVIEW,
                    resultSummary = persistedToolSummary(state, call.resultSummary, cancelledBeforeStart),
                    card = null,
                    position = call.position,
                    prootRecoveryAvailable = call.prootRecoveryAvailable,
                )
            }
        val scoped = scopedLiveRows(snapshot.liveTurns, persisted, liveRows)
        val liveByCall = scoped.associateBy { it.callId }
        return persisted.map { row ->
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
                state = live.state,
                startedAtElapsedMs = live.startedAtElapsedMs,
                resultSummary = live.resultSummary ?: row.resultSummary,
            )
        } + scoped.filter { live -> persisted.none { it.callId == live.callId } }
    }

    fun turnUiFor(
        fact: ConversationTurnSnapshot,
        previousStreamingText: String?,
    ): TurnUi {
        // Corrupt persisted state must never display a healthy in-flight execution.
        val state = TurnState.entries.firstOrNull { it.name == fact.state } ?: TurnState.FAILED
        return TurnUi(
            fact.id,
            state,
            previousStreamingText.takeUnless { state.isTerminal },
            fact.errorLabel,
            state == TurnState.FAILED,
            fact.continueFromResults,
            fact.budgetDetail,
            fact.modelStep,
        )
    }
}

fun scopedLiveRows(
    turns: List<ConversationTimelineTurn>,
    persisted: List<ToolTimelineRow>,
    live: List<ToolTimelineRow>,
): List<ToolTimelineRow> {
    val owned = turns.map { it.id }.toSet()
    val active =
        turns
            .filter { turn ->
                TurnState.entries.firstOrNull { it.name == turn.state }?.isTerminal == false
            }.map { it.id }
            .toSet()
    val selected = persisted.map { it.callId }.toSet()
    return live.filter {
        it.turnId in owned && (it.callId in selected || it.turnId in active || it.card != null || it.prootRecoveryBusy)
    }
}

/** CANCELLED proves no execution; unknown/after-start effects retain their actual diagnostics. */
fun persistedToolSummary(
    state: String,
    summary: String?,
    cancelledBeforeStart: () -> String,
): String? = if (state == ToolCallState.CANCELLED.name) cancelledBeforeStart() else summary
