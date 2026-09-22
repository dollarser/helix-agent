package com.helix.app.chat

import com.helix.core.model.ProviderProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Offline vendor events only: production decoders and dispatchers still process every event. */
internal object SessionInputProtocolStreams {
    const val CALL_ID = "call_protocol"

    fun text(protocol: ProviderProtocol): String =
        if (protocol == ProviderProtocol.OPENAI_RESPONSES) {
            event("response.output_text.delta", """{"delta":"fixture answer"}""") + responsesEnd()
        } else {
            anthropicStart() +
                event("content_block_start", """{"index":0,"content_block":{"type":"text","text":""}}""") +
                event(
                    "content_block_delta",
                    """{"index":0,"delta":{"type":"text_delta","text":"fixture answer"}}""",
                ) + anthropicEnd("end_turn")
        }

    fun tool(
        protocol: ProviderProtocol,
        probe: Boolean,
    ): String {
        val name = if (probe) "echo" else "time.now"
        val args = if (probe) """{"text":"probe"}""" else "{}"
        return if (protocol == ProviderProtocol.OPENAI_RESPONSES) {
            responsesTool(name, args)
        } else {
            anthropicTool(name, args)
        }
    }

    private fun responsesTool(
        name: String,
        args: String,
    ): String =
        event(
            "response.output_item.added",
            """{"output_index":0,"item":{"id":"fc_protocol","type":"function_call",
                "call_id":"$CALL_ID","name":"$name","arguments":""}}""",
        ) +
            event(
                "response.function_call_arguments.done",
                buildJsonObject {
                    put("output_index", 0)
                    put("arguments", args)
                }.toString(),
            ) + responsesEnd()

    private fun responsesEnd(): String =
        event(
            "response.completed",
            """{"response":{"status":"completed","usage":{"input_tokens":10,"output_tokens":2}}}""",
        )

    private fun anthropicTool(
        name: String,
        args: String,
    ): String =
        anthropicStart() +
            event(
                "content_block_start",
                """{"index":0,"content_block":{"type":"tool_use","id":"$CALL_ID","name":"$name"}}""",
            ) +
            event(
                "content_block_delta",
                buildJsonObject {
                    put("index", 0)
                    put(
                        "delta",
                        buildJsonObject {
                            put("type", "input_json_delta")
                            put("partial_json", args)
                        },
                    )
                }.toString(),
            ) + anthropicEnd("tool_use")

    private fun anthropicStart(): String =
        event(
            "message_start",
            """{"message":{"id":"msg_protocol","type":"message","role":"assistant",
                "content":[],"usage":{"input_tokens":10,"output_tokens":1}}}""",
        )

    private fun anthropicEnd(reason: String): String =
        event("content_block_stop", """{"index":0}""") +
            event(
                "message_delta",
                """{"delta":{"stop_reason":"$reason"},"usage":{"output_tokens":2}}""",
            ) + event("message_stop", "{}")

    private fun event(
        type: String,
        fields: String,
    ): String {
        val payload =
            buildJsonObject {
                put("type", type)
                Json.parseToJsonElement(fields).jsonObject.forEach { (key, value) -> put(key, value) }
            }
        return "event: $type\ndata: $payload\n\n"
    }
}
