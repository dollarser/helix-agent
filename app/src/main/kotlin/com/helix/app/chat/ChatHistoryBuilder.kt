package com.helix.app.chat

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds the model-visible message list for a chat turn from PERSISTED message
 * rows (HXA-028: the UI/services work on persisted state, doc 02 section 12;
 * HXA-037: tool-call steps and tool results are part of the model-visible
 * history — `model-visible ⇔ persisted`).
 *
 * Row kinds:
 * - [KIND_TEXT]: plain text (USER or ASSISTANT) — the M2 shape;
 * - [KIND_TOOL_CALLS] (ASSISTANT): the model's tool-call step, persisted as a JSON
 *   array `[{"id","name","arguments"}]` where `arguments` is the model's raw
 *   argument JSON object string; mapped back to an assistant [ModelMessage] with
 *   [ModelMessage.toolCalls] in the ORIGINAL call sequence;
 * - [KIND_TOOL_RESULT] (TOOL): one row per tool call, persisted as a JSON object
 *   `{"id","tool","status","summary"}`; mapped to a TOOL [ModelMessage] keyed by
 *   the call id (the vendor protocols key results by the call id — doc 02 5.3).
 *
 * Rules:
 * - roles map by their [ModelRole] names; an unrecognized role/kind combination is
 *   skipped (the history must never invent context);
 * - rows with missing/blank content are skipped (an assistant TEXT row is persisted
 *   only when the stream produced content);
 * - a row whose tool JSON is malformed is skipped AND the history is flagged via
 *   [toModelMessagesStrict]: the chat service fails the turn closed instead of
 *   silently presenting a truncated model context.
 * - the result keeps persisted order; the CALLER controls the final message
 *   (USER for a fresh send, TOOL for a back-fill request — both satisfy the
 *   [com.helix.core.model.ModelRequest] final-message invariant).
 */
object ChatHistoryBuilder {
    /** Plain text message (user or assistant). */
    const val KIND_TEXT = "TEXT"

    /** An assistant step whose content is exactly its tool calls (JSON array). */
    const val KIND_TOOL_CALLS = "TOOL_CALLS"

    /** One tool call's settled result (JSON object; TOOL role). */
    const val KIND_TOOL_RESULT = "TOOL_RESULT"

    data class PersistedRow(
        val turnId: String?,
        val role: String,
        val kind: String?,
        val content: String?,
    )

    fun toModelMessages(rows: List<PersistedRow>): List<ModelMessage> = toModelMessagesInternal(rows, strict = false)

    /**
     * Strict variant: throws [IllegalArgumentException] on a malformed tool row.
     * The chat service uses this for model-bound request building (a corrupted
     * model-visible row must fail the turn closed, never truncate the context).
     */
    fun toModelMessagesStrict(rows: List<PersistedRow>): List<ModelMessage> =
        toModelMessagesInternal(rows, strict = true)

    private fun toModelMessagesInternal(
        rows: List<PersistedRow>,
        strict: Boolean,
    ): List<ModelMessage> =
        rows.mapNotNull { row ->
            when (row.kind) {
                KIND_TOOL_CALLS -> assistantToolCallMessage(row, strict)
                KIND_TOOL_RESULT -> toolResultMessage(row, strict)
                else -> textMessage(row)
            }
        }

    private fun textMessage(row: PersistedRow): ModelMessage? {
        val text = row.content?.takeIf { it.isNotBlank() } ?: return null
        return when (row.role) {
            ModelRole.USER.name -> ModelMessage(role = ModelRole.USER, text = text)
            ModelRole.ASSISTANT.name -> ModelMessage(role = ModelRole.ASSISTANT, text = text)
            else -> null
        }
    }

    /**
     * Parses the persisted `[{"id","name","arguments"}]` array into a tool-call step.
     * Lenient: a malformed row is skipped. Strict: it throws (the model-bound request
     * fails the turn closed instead of truncating the context).
     */
    private fun assistantToolCallMessage(
        row: PersistedRow,
        strict: Boolean,
    ): ModelMessage? {
        if (row.role != ModelRole.ASSISTANT.name) return null
        return row.content?.let { content -> parseToolCallsStep(content, strict) } ?: null
    }

    @Suppress("TooGenericExceptionCaught")
    // Corrupted persisted JSON: kotlinx's exception type is version-stable; the contract
    // is a stable IAE, so the lenient/strict split only needs the IAE itself.
    private fun parseToolCallsStep(
        content: String,
        strict: Boolean,
    ): ModelMessage? =
        try {
            val parsed = Json.parseToJsonElement(content)
            val array = parsed as? JsonArray ?: failStrict("a tool-call step row must be an array")
            val calls = array.map { parseToolCallElement(it) }
            require(calls.isNotEmpty()) { "a tool-call step must carry at least one call" }
            ModelMessage(
                role = ModelRole.ASSISTANT,
                text = "",
                toolCalls = calls,
            )
        } catch (e: IllegalArgumentException) {
            if (strict) failStrict("malformed tool-call step row", e)
            null
        } catch (e: Exception) {
            // Corrupted JSON text: kotlinx throws its own (version-stable) exception type —
            // the strict path treats it like any other bad shape, the lenient one skips.
            // (The contract is a stable IAE, so normalize before the strict rethrow.)
            if (strict) failStrict("malformed tool-call step row", e)
            null
        }

    /**
     * The persisted rows that form ONE turn's model-visible history:
     *
     * - fresh send ([retryTurnId] == null): every row (the just-persisted
     *   user message is the newest, so the list ends with USER);
     * - retry: every row EXCEPT the retried turn's own rows, with that turn's
     *   USER row re-appended LAST. A newer turn may have followed the failed
     *   one, so the naive "exclude the retried turn's rows" filter need not
     *   end with USER — re-appending restores the invariant;
     * - a retried turn without a user row (corruption) degrades to the
     *   filtered history; the caller's end-with-USER guard then fails closed
     *   and the turn surfaces as FAILED with a safe label.
     */
    fun rowsForTurn(
        rows: List<PersistedRow>,
        retryTurnId: String?,
    ): List<PersistedRow> {
        if (retryTurnId == null) return rows
        val rest = rows.filter { it.turnId != retryTurnId }
        val retried = rows.firstOrNull { it.turnId == retryTurnId && it.role == ModelRole.USER.name }
        return if (retried == null) rest else rest + retried
    }

    /** Parses the persisted `{"id","tool","status","summary"}` object into a TOOL message. */
    private fun toolResultMessage(
        row: PersistedRow,
        strict: Boolean,
    ): ModelMessage? {
        if (row.role != ModelRole.TOOL.name) return null
        return row.content?.let { content -> parseToolResult(content, strict) } ?: null
    }
}

/** One `[{"id","name","arguments"}]` element of a persisted tool-call step. */
private fun parseToolCallElement(element: JsonElement): AssistantToolCall {
    val obj = element as? JsonObject ?: throw IllegalArgumentException("a tool-call element must be an object")
    return AssistantToolCall(
        ToolCallId(obj.requiredField("id")),
        ToolName(obj.requiredField("name")),
        obj.requiredField("arguments"),
    )
}

/** The bounded required string field of a persisted row (fail closed, never a default). */
private fun JsonObject.requiredField(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("missing $key")

/** The strict-mode failure: the model-bound request fails the turn closed. */
private fun failStrict(
    detail: String,
    cause: Throwable? = null,
): Nothing = throw IllegalArgumentException(detail, cause)

/** Parses a persisted tool-result row: lenient skips malformed, strict throws the stable IAE. */
@Suppress("TooGenericExceptionCaught")
// Corrupted persisted JSON: same version-stable handling as the tool-call step.
private fun parseToolResult(
    content: String,
    strict: Boolean,
): ModelMessage? =
    try {
        val parsed = Json.parseToJsonElement(content)
        val obj = parsed as? JsonObject ?: failStrict("a tool-result row must be an object")
        val id = obj.requiredField("id")
        val tool = obj.requiredField("tool")
        val status = (obj["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "UNKNOWN"
        val summary = (obj["summary"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        // The model sees the settled outcome: status + bounded summary (exactly the
        // text the timeline shows — model-visible ⇔ persisted).
        val text = "[$status] $summary".trim()
        require(text.isNotBlank()) { "a tool result row needs a status or summary" }
        ModelMessage(
            role = ModelRole.TOOL,
            text = text,
            toolCallId = ToolCallId(id),
            toolName = ToolName(tool),
        )
    } catch (e: IllegalArgumentException) {
        if (strict) failStrict("malformed tool-result row", e)
        null
    } catch (e: Exception) {
        // Corrupted JSON text: the strict path treats it like any other bad shape.
        if (strict) failStrict("malformed tool-result row", e)
        null
    }
