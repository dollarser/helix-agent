package com.helix.app.chat

import com.helix.app.R
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.LocalToolCallBatch
import com.helix.app.agent.SettledCall
import com.helix.app.agent.TurnMessageDraft
import com.helix.core.agent.TestRunResult
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
    fun toolResultDraft(settled: SettledCall): TurnMessageDraft {
        val status: String
        val summary: String
        when (val o = settled.outcome) {
            is ToolDispatchOutcome.Succeeded -> {
                status = "SUCCEEDED"
                summary = withTestSummary(settled.toolName, o.result.payload)
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

    /**
     * P1 (research doc section 44 "structured test result"): for an exec tool, if the output
     * parses as a test/build aggregate, append the concise structured line so the model sees
     * "N passed, M failed" without re-reading the whole log. Non-exec tools and unrecognized
     * output pass through unchanged (the parser is fail-open: a miss yields the payload as-is).
     */
    private fun withTestSummary(
        toolName: String,
        payload: String,
    ): String =
        if (toolName in EXEC_TOOLS) {
            TestRunResult.parse(payload)?.let { "$payload\n${it.line}" } ?: payload
        } else {
            payload
        }
}

// The exec tools whose output may carry a test/build summary (research doc section 44).
private val EXEC_TOOLS = setOf("bash", "code.linux.run")
