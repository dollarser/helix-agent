package com.helix.app.infrastructure.conversation

import com.helix.app.R
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.app.chat.BudgetContinuation
import com.helix.app.chat.ChatTimelineOrder
import com.helix.app.chat.GoalSummaryQuery
import com.helix.app.chat.MessageEditHistory
import com.helix.app.chat.ModelFailureLabels
import com.helix.app.chat.UserQuestionService
import com.helix.app.infrastructure.agent.AutomaticRecoveryPolicy
import com.helix.app.infrastructure.chat.messageImages
import com.helix.app.proot.ProotToolModule
import com.helix.app.proot.prootRecoveryEligible
import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.ProviderService
import com.helix.core.agent.BudgetStopReasons
import com.helix.core.agent.TurnInterruptionNotice
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import com.helix.core.model.input.InputRecoveryPolicy
import com.helix.core.model.input.KnowledgeReference
import com.helix.core.model.input.KnowledgeReferences
import com.helix.core.policy.DiagnosticRedaction
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.feature.conversation.application.ConversationHistoryReader
import com.helix.feature.conversation.application.ConversationHistoryRequest
import com.helix.feature.conversation.application.ConversationHistorySnapshot
import com.helix.feature.conversation.application.ConversationMessageSnapshot
import com.helix.feature.conversation.application.ConversationMessageVisibility
import com.helix.feature.conversation.application.ConversationNoticeSnapshot
import com.helix.feature.conversation.application.ConversationTimelineTurn
import com.helix.feature.conversation.application.ConversationToolSnapshot
import com.helix.feature.conversation.application.ConversationTurnSnapshot
import com.helix.feature.conversation.application.attachment.AttachmentContext
import com.helix.feature.conversation.presentation.ChatScreenProjection
import com.helix.feature.conversation.presentation.SelectedModelUi
import com.helix.feature.conversation.presentation.ToolTimelinePosition
import com.helix.feature.conversation.presentation.hasSelectedConversationModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Room owns each complete read, including existence, content bindings and timeline ordering. */
@Suppress("TooManyFunctions") // One read adapter; private helpers decode the separate durable display facts.
internal class RoomConversationHistoryReader(
    private val storage: HelixStorage,
    private val providers: ProviderService,
    private val strings: (Int, Array<out Any>) -> String,
    private val modelTerminalCodeRes: (String) -> Int,
) : ConversationHistoryReader {
    override fun positionForCall(
        sessionId: String,
        callId: String,
    ): ToolTimelinePosition? {
        var result: ToolTimelinePosition? = null
        storage.withTransaction { result = ChatTimelineOrder.positions(storage, sessionId)[callId] }
        return result
    }

    override fun read(request: ConversationHistoryRequest): ConversationHistorySnapshot {
        require(request.limit > 0 && request.limit < Int.MAX_VALUE)
        lateinit var snapshot: ConversationHistorySnapshot
        storage.withTransaction {
            snapshot = readWithinSnapshot(request)
        }
        return snapshot
    }

    /** The complete screen reader already holds the same Room snapshot. */
    internal fun readWithinSnapshot(request: ConversationHistoryRequest): ConversationHistorySnapshot {
        require(request.limit > 0 && request.limit < Int.MAX_VALUE)
        val sessionId =
            request.sessionId?.takeIf { storage.sessions.find(it) != null }
                ?: return ConversationHistorySnapshot(request, null)
        val page = storage.messages.recentBySession(sessionId, request.limit + 1, request.through)
        return readHistory(request, sessionId, page.takeLast(request.limit), page.size > request.limit)
    }

    override fun messagesForTurn(turnId: String): ConversationHistorySnapshot {
        val request =
            ConversationHistoryRequest(
                null,
                0,
                ConversationHistoryRequest.PAGE_SIZE,
                Long.MAX_VALUE,
                null,
                ConversationHistoryRequest.PAGE_SIZE,
                Long.MAX_VALUE,
                0,
                emptySet(),
            )
        var snapshot = ConversationHistorySnapshot(request, null)
        storage.withTransaction {
            val turn = storage.turns.find(turnId) ?: return@withTransaction
            if (storage.sessions.find(turn.sessionId) == null) return@withTransaction
            snapshot =
                readHistory(
                    request.copy(sessionId = turn.sessionId),
                    turn.sessionId,
                    storage.messages.byTurn(turnId),
                    false,
                    includeTimeline = false,
                )
        }
        return snapshot
    }

    private fun readHistory(
        request: ConversationHistoryRequest,
        sessionId: String,
        history: List<MessageEntity>,
        hasOlder: Boolean,
        includeTimeline: Boolean = true,
    ): ConversationHistorySnapshot {
        val turns = storage.turns.byIds(history.mapNotNull { it.turnId })
        val superseded = storage.messages.supersededTurns(sessionId)
        val liveTurns =
            storage.turns.byIds(request.liveTurnIds.toList()).filter {
                it.sessionId == sessionId && it.id !in superseded
            }
        val positions = if (includeTimeline) ChatTimelineOrder.positions(storage, history) else emptyMap()
        return ConversationHistorySnapshot(
            request = request,
            sessionId = sessionId,
            messages = history.map { message(sessionId, it) },
            hasOlderMessages = hasOlder,
            editRanges = editRanges(sessionId),
            turnStates = turns.associate { it.id to it.state },
            notices = turns.associate { it.id to notice(it) },
            tools =
                if (includeTimeline) {
                    toolSnapshots(
                        sessionId,
                        history.firstOrNull()?.sequence ?: Long.MAX_VALUE,
                        request.through,
                        positions,
                    )
                } else {
                    emptyList()
                },
            liveTurns = liveTurns.map { ConversationTimelineTurn(it.id, it.state) },
        )
    }

    private fun message(
        sessionId: String,
        row: MessageEntity,
    ): ConversationMessageSnapshot {
        val visibility =
            when (row.kind) {
                ContextCompaction.KIND, ChatHistoryBuilder.KIND_TOOL_CALLS,
                ChatHistoryBuilder.KIND_TOOL_RESULT, UserQuestionService.KIND,
                -> ConversationMessageVisibility.HIDDEN

                InputRecoveryPolicy.NOTICE_KIND -> ConversationMessageVisibility.INPUT_RECOVERY_NOTICE

                else -> ConversationMessageVisibility.CONVERSATION
            }
        val visible =
            visibility != ConversationMessageVisibility.HIDDEN &&
                (
                    row.role in setOf("USER", "ASSISTANT") ||
                        (row.role == "SYSTEM" && visibility == ConversationMessageVisibility.INPUT_RECOVERY_NOTICE)
                )
        val references =
            if (visible) {
                storage.sessionInputs
                    .forMessage(
                        row.id,
                    )?.knowledgeReferences
                    .orEmpty()
            } else {
                emptyList()
            }
        return ConversationMessageSnapshot(
            messagePresentationFact(row),
            visibility,
            if (visible) visibleContent(row.id, storage.messages.readContent(row), references) else null,
            if (visible) messageImages(storage, sessionId, row.id) else emptyList(),
            references,
        )
    }

    private fun visibleContent(
        messageId: String,
        content: String?,
        references: List<KnowledgeReference>,
    ): String? {
        val source =
            storage.sessionInputs
                .forMessage(messageId)
                ?.delegation
                ?.sourceSessionId
        val origin =
            source
                ?.let {
                    strings(com.helix.feature.conversation.R.string.session_input_delegated_from, arrayOf(it)) + "\n\n"
                }.orEmpty()
        if (references.isEmpty()) return if (source == null) content else origin + content.orEmpty()
        val original = content.orEmpty()
        val prefix =
            AttachmentContext.authoredPrefix(
                original,
                storage.messageAttachments.listByMessage(messageId).size,
            )
        return origin +
            KnowledgeReferences.authoredPrefix(
                prefix,
                references,
                storage.sessionInputs
                    .forMessage(messageId)
                    ?.inputId
                    .orEmpty(),
            ) +
            original.substring(prefix.length)
    }

    private fun editRanges(sessionId: String): List<LongRange>? =
        try {
            MessageEditHistory.read(storage, sessionId)
        } catch (_: java.io.IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun notice(turn: TurnEntity): ConversationNoticeSnapshot {
        val row =
            storage.messages.find(TurnInterruptionNotice.idFor(turn.id))?.takeIf {
                it.kind == TurnInterruptionNotice.KIND && it.turnId == turn.id && it.sessionId == turn.sessionId &&
                    it.role == "SYSTEM" && it.supersededBy == null
            }
        val saved =
            row?.let { record ->
                val value = storage.messages.readContent(record)
                TurnInterruptionNotice.entries.firstOrNull { it.name == value }
            }
        return ConversationNoticeSnapshot(
            saved,
            turn.state,
            turn.errorCode == "USER_STOP" || turn.pauseRequestedAt != null,
            storage.toolCallReviews.hasUnresolvedEffects(turn.id),
            AutomaticRecoveryPolicy.isInspection(turn),
        )
    }

    private fun toolSnapshots(
        sessionId: String,
        from: Long,
        through: Long,
        positions: Map<String, ToolTimelinePosition>,
    ): List<ConversationToolSnapshot> =
        storage.toolCalls.recentBySession(sessionId, from, through, TOOL_TIMELINE_CAP).map { call ->
            val turn = storage.turns.resolve(call.turnId)
            val result = storage.toolResults.byToolCall(call.callId)
            val interruptedProot =
                prootRecoveryEligible(turn.state, call.state) &&
                    call.name in setOf("bash", "code.linux.run", "skills.run")
            ConversationToolSnapshot(
                turnId = turn.id,
                callId = call.callId,
                toolName = call.name,
                modelIntent = call.modelIntent,
                requestSummary = DiagnosticRedaction.redact(call.argsJson),
                state = call.state,
                turnState = turn.state,
                resultSummary = result?.summary?.let(DiagnosticRedaction::redact),
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

    override fun turn(turnId: String): ConversationTurnSnapshot? {
        var fact: ConversationTurnSnapshot? = null
        storage.withTransaction {
            val row = storage.turns.find(turnId) ?: return@withTransaction
            fact = turnWithinSnapshot(row)
        }
        return fact
    }

    internal fun turnWithinSnapshot(row: TurnEntity) =
        ConversationTurnSnapshot(
            row.id,
            row.state,
            row.errorCode?.let { code ->
                val base = BuiltInModel.errorLabelForTurn(storage, row.id, code) ?: modelTerminalCodeRes(code)
                ModelFailureLabels.forTurn(storage, row.id, code, strings(base, emptyArray()), strings)
            },
            BudgetContinuation.eligible(storage, row),
            budgetDetail(row),
            row.stepCount,
        )

    @Suppress("ReturnCount") // Missing/corrupt diagnostic fields omit detail without breaking history.
    private fun budgetDetail(turn: TurnEntity): String? {
        if (turn.errorCode !in BudgetStopReasons.turn) return null
        val call = storage.modelCalls.listByTurn(turn.id).lastOrNull() ?: return null
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

    override fun retryTarget(sessionId: String?): String? {
        var target: String? = null
        storage.withTransaction {
            target = retryTargetWithinSnapshot(sessionId)
        }
        return target
    }

    internal fun retryTargetWithinSnapshot(sessionId: String?): String? {
        val id = sessionId?.takeIf { storage.sessions.find(it) != null } ?: return null
        return storage.turns
            .latestVisible(id)
            ?.takeIf { it.state == TurnState.FAILED.name }
            ?.takeIf { turn ->
                val binding = storage.goalTurnBindings.byTurn(turn.id)
                if (binding == null) {
                    turn.errorCode !in BudgetStopReasons.continuable || BudgetContinuation.eligible(storage, turn)
                } else {
                    val goalId = storage.goalRuns.resolve(binding.runId).goalId
                    GoalSummaryQuery(storage).forSession(id).any { it.id == goalId && it.canContinue }
                }
            }?.id
    }

    override fun selectedModel(sessionId: String): SelectedModelUi? {
        var selected: SelectedModelUi? = null
        storage.withTransaction {
            val session = storage.sessions.find(sessionId) ?: return@withTransaction
            selected = selectedModel(session.providerId, session.modelId)
        }
        return selected
    }

    override fun selectedModel(
        providerId: String?,
        modelId: String?,
    ): SelectedModelUi? {
        if (!hasSelectedConversationModel(providerId, modelId)) return null
        return providers.rows.value.firstOrNull { it.id == providerId }?.let {
            SelectedModelUi(
                it.id,
                modelId ?: it.model,
            )
        }
    }

    private companion object {
        const val TOOL_TIMELINE_CAP = 200
    }
}
