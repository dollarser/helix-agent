package com.helix.core.agent

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** In-memory contract fixture only; actual Room atomicity is tested by TurnCommitStoreDeviceTest. */
internal class HeadlessTurnJournal : AgentTurnJournal {
    override val id = "turn"
    val runtime = BatchTurnRuntime("model-1")
    val history = mutableListOf(ModelMessage(ModelRole.USER, "Read the two fixture values and report the result."))
    val log = mutableListOf<String>()
    var failManifest = false
    var compactions = 0

    override fun snapshot() = runtime.snapshot()

    override fun currentStream() = runtime.currentStream()

    override suspend fun beginModelStream(compacting: Boolean): ModelStreamState {
        log += "begin:${snapshot().modelCallId}"
        return runtime.beginModelStream()
    }

    override suspend fun retryEmptyModelStream(nextModelCallId: String) {
        log += "retry:$nextModelCallId"
        runtime.closeSummary(nextModelCallId)
    }

    override suspend fun recordDiagnostic(
        kind: String,
        body: String,
    ) {
        log += "diagnostic:$kind"
    }

    override suspend fun recordPromptSnapshot(
        prompt: PromptSnapshot?,
        compacting: Boolean,
    ) {
        log +=
            "prompt:${snapshot().modelCallId}"
    }

    override suspend fun recordRequestManifest(manifestJson: String?) {
        check(!failManifest) { "manifest disk failure" }
        requireNotNull(manifestJson)
        log += "manifest:${snapshot().modelCallId}"
    }

    override suspend fun recordInputRequestStarted(messageIds: Set<String>) {
        log += "input:${snapshot().modelCallId}"
    }

    override suspend fun beginToolBatch(callIds: List<String>) {
        runtime.beginBatch(callIds)
    }

    override suspend fun commitModelToolStep(toolCallsJson: String) {
        val calls =
            Json.parseToJsonElement(toolCallsJson).jsonArray.map { value ->
                val row = value.jsonObject
                AssistantToolCall(
                    ToolCallId(row.getValue("id").jsonPrimitive.content),
                    ToolName(row.getValue("name").jsonPrimitive.content),
                    row.getValue("arguments").jsonPrimitive.content,
                )
            }
        history += ModelMessage(ModelRole.ASSISTANT, currentStream().text, toolCalls = calls)
        log += "assistant-committed"
        runtime.markModelCallClosed()
    }

    override fun settleBatchCall(
        callId: String,
        sideEffectUnknown: Boolean,
    ) {
        log += "settled:$callId"
        runtime.settleCall(callId, sideEffectUnknown)
    }

    override suspend fun openNextModelCall(
        messages: List<TurnMessageDraft>,
        nextModelCallId: String,
    ) {
        messages.forEach { draft ->
            if (draft.role == ModelRole.TOOL) {
                val value = Json.parseToJsonElement(draft.content).jsonObject
                history +=
                    ModelMessage(
                        ModelRole.TOOL,
                        value.getValue("value").jsonPrimitive.content,
                        toolCallId = ToolCallId(value.getValue("id").jsonPrimitive.content),
                        toolName = ToolName(value.getValue("name").jsonPrimitive.content),
                    )
            } else {
                history += ModelMessage(draft.role, draft.content)
            }
        }
        log += "results-committed"
        runtime.advanceModelCall(nextModelCallId)
    }

    override suspend fun commitCompaction(
        plan: ContextCompactionPlan,
        nextModelCallId: String?,
        notice: String?,
        failureReason: String?,
        saveSummary: Boolean,
    ) {
        if (saveSummary && failureReason == null) {
            compactions++
            history.clear()
            history.addAll(
                ContextSummaryFormat(
                    "Summarize.",
                    "Preserve facts.",
                ).summarizedRequest(plan, currentStream().text).messages,
            )
        }
        log += "compaction-committed:$saveSummary"
        runtime.closeSummary(nextModelCallId)
    }
}
