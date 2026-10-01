package com.helix.app.agent

import com.helix.core.agent.ContextCheckpoint
import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TokenEstimator
import com.helix.core.agent.TurnContextRequest
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.MessageEntity
import com.helix.provider.api.ProviderContextSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Durable, file-backed checkpoints use the existing message/content transaction and privacy erasure. */
internal object ContextCompaction {
    const val KIND = "CONTEXT_CHECKPOINT_V1"
    const val MAX_SUMMARY_CHARS = com.helix.core.agent.ContextSummaryFormat.MAX_SUMMARY_CHARS
    val summaries =
        com.helix.core.agent.ContextSummaryFormat(
            com.helix.app.chat.packagedPromptTemplates
                .text("compaction")
                .trim(),
            com.helix.app.chat.packagedPromptTemplates
                .text("compaction-continuity")
                .trim(),
        )
    private val compiler =
        com.helix.core.agent
            .ContextCompiler(summaries)

    fun checkpoint(
        storage: HelixStorage,
        rows: List<MessageEntity>,
    ): ContextCheckpoint? =
        rows.lastOrNull { it.kind == KIND }?.let { row ->
            val json = Json.parseToJsonElement(requireNotNull(ContextHistory.read(storage, row))).jsonObject
            val through = requireNotNull(json["coveredThrough"]).jsonPrimitive.long
            val summary = requireNotNull(json["summary"]).jsonPrimitive.content
            require(
                through >= 0 && through < row.sequence && summary.isNotBlank() && summary.length <= MAX_SUMMARY_CHARS,
            )
            ContextCheckpoint(
                through,
                summary,
                json["sourceCallId"]?.jsonPrimitive?.content,
                json["estimatedInputTokens"]?.jsonPrimitive?.long,
                json["preservedMessageIds"]
                    ?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?.toSet()
                    .orEmpty(),
            )
        }

    fun summaryMessage(checkpoint: ContextCheckpoint): ModelMessage = summaries.summaryMessage(checkpoint)

    fun retained(
        rows: List<MessageEntity>,
        checkpoint: ContextCheckpoint?,
    ): List<MessageEntity> =
        rows.filter {
            it.kind != KIND && it.kind != com.helix.app.chat.SessionForkPlan.KIND &&
                (
                    checkpoint == null || it.sequence > checkpoint.coveredThrough ||
                        it.id in checkpoint.preservedMessageIds ||
                        it.role == ModelRole.SYSTEM.name
                )
        }

    fun pressure(request: TurnContextRequest): Long = request.inputTokens() + request.maxOutputTokens

    /** Bounded host snapshot acquisition followed by the single pure compiler. */
    @Suppress("LongParameterList") // The adapter preserves the existing explicit request binding.
    fun plan(
        storage: HelixStorage,
        sessionId: String,
        request: TurnContextRequest,
        control: RunControlConfig,
        settings: ProviderContextSettings,
        force: Boolean,
        currentTurnId: String,
        inputScale: Double = 1.0,
    ): ContextCompactionPlan? {
        require(inputScale.isFinite() && inputScale >= 1.0)
        val floor = ContextPressure.inputFloor(storage, sessionId, currentTurnId, request.model)
        if (!com.helix.core.agent.ContextCompiler
                .needsPlan(request, control, settings, force, floor)
        ) {
            return null
        }
        val history = ContextHistory.load(storage, sessionId)
        val turn = storage.turns.resolve(currentTurnId)
        require(turn.sessionId == sessionId) { "CONTEXT_TURN_SESSION_MISMATCH" }
        val snapshot =
            com.helix.core.agent.ContextSourceSnapshot(
                ContextHistoryMapping.rows(storage, history.rows),
                history.checkpoint,
                currentTurnId,
                turn.recoveryFromTurnId,
                floor,
            )
        return compiler.plan(snapshot, request, control, settings, force, inputScale)
    }

    fun persist(
        storage: HelixStorage,
        sessionId: String,
        turnId: String,
        id: String,
        plan: ContextCompactionPlan,
        summary: String,
        sourceCallId: String,
    ) {
        require(summary.isNotBlank() && summary.length <= MAX_SUMMARY_CHARS)
        val previous = ContextHistory.checkpoint(storage, sessionId)
        require(
            previous == null || plan.coveredThrough > previous.coveredThrough ||
                (
                    plan.coveredThrough == previous.coveredThrough &&
                        plan.preservedMessageIds.size < previous.preservedMessageIds.size
                ),
        )
        val json =
            buildJsonObject {
                put("coveredThrough", plan.coveredThrough)
                put("summary", summary)
                put("preservedMessageIds", buildJsonArray { plan.preservedMessageIds.forEach { add(it) } })
                put("sourceCallId", sourceCallId)
                val current = plan.retainedRequest
                val messages =
                    current.messages.filter { it.role == ModelRole.SYSTEM } +
                        summaryMessage(ContextCheckpoint(plan.coveredThrough, summary)) +
                        current.messages.filter { it.role != ModelRole.SYSTEM }
                put("estimatedInputTokens", current.copy(messages = messages).inputTokens())
            }.toString()
        storage.messages.append(id, sessionId, turnId, ModelRole.ASSISTANT.name, KIND, json)
    }

    fun summarizedRequest(
        plan: ContextCompactionPlan,
        summary: String,
    ): TurnContextRequest = summaries.summarizedRequest(plan, summary)

    fun hasUsefulGain(
        plan: ContextCompactionPlan,
        summary: String,
    ): Boolean = summaries.hasUsefulGain(plan, summary)

    internal fun summaryMessages(
        history: String,
        outputBudget: Long = 2048L,
    ): List<ModelMessage> = summaries.summaryMessages(history, outputBudget)
}
