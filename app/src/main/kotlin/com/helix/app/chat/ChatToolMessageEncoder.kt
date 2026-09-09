package com.helix.app.chat

import com.helix.app.R
import com.helix.core.model.ModelRole
import com.helix.tools.framework.ToolDispatchOutcome
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Encodes ordered tool messages; never dispatches or settles a call. */
internal class ChatToolMessageEncoder(
    private val strings: (Int, Array<out Any>) -> String,
) {
    private fun str(
        id: Int,
        vararg args: Any,
    ): String = strings(id, args)

    fun assistantToolStepJson(batch: LocalToolCallBatch): String =
        buildJsonArray {
            batch.calls.forEach { call ->
                add(
                    buildJsonObject {
                        put("id", batch.wireId(call.callId))
                        put("localId", call.callId)
                        put("name", call.name)
                        put("arguments", call.arguments)
                    },
                )
            }
        }.toString()

    /**
     * Persists ONE settled tool result as a TOOL message (called in CALL SEQUENCE — the
     * back-fill order the next model request re-carries). The content is the bounded
     * `{"id","tool","status","summary"}` envelope. Successful content retains the
     * Dispatcher's size-bounded payload; the timeline's shorter preview must not truncate
     * structured fields or node tokens needed by the next model call.
     */
    fun toolResultDraft(settled: ChatToolCalls.SettledCall): TurnMessageDraft {
        val status: String
        val summary: String
        when (val o = settled.outcome) {
            is ToolDispatchOutcome.Succeeded -> {
                status = "SUCCEEDED"
                summary = o.result.payload
            }

            is ToolDispatchOutcome.Denied -> {
                status = o.code.name
                summary = o.detail
            }

            is ToolDispatchOutcome.ExecutionFailed -> {
                status = o.code.name
                summary = o.detail
            }

            ToolDispatchOutcome.Cancelled -> {
                status = "CANCELLED"
                summary = str(R.string.tool_summary_cancelled_before_start)
            }
        }
        val body =
            buildJsonObject {
                put("id", settled.callId)
                put("tool", settled.toolName)
                put("status", status)
                put("summary", summary)
            }
        return TurnMessageDraft(
            role = ModelRole.TOOL,
            kind = ChatHistoryBuilder.KIND_TOOL_RESULT,
            content = body.toString(),
        )
    }
}
