package com.helix.core.model

import com.helix.core.model.internal.Json
import com.helix.core.model.internal.parseJson
import com.helix.core.model.internal.requireBool
import com.helix.core.model.internal.requireObject
import com.helix.core.model.internal.requireString
import com.helix.core.model.internal.requireStringObject

/**
 * Stable error categories (architecture doc section 13). The set is closed for UI, audit and
 * model-facing error mapping; new categories require an explicit review.
 */
enum class ErrorCode {
    VALIDATION,
    PERMISSION,
    APPROVAL,
    POLICY,
    NETWORK,
    PROVIDER_AUTH,
    PROVIDER_RATE_LIMIT,
    TOOL_TIMEOUT,
    EXECUTION,
    STORAGE,
    INTERRUPTED,
    INTERNAL,
}

/**
 * Structured, user- and model-safe error (architecture doc section 13).
 *
 * Invariants enforced at construction:
 * - [userMessage] is a bounded, non-blank, pre-authored message. Raw exception messages are
 *   never copied here because they may contain paths, URL queries, headers or secrets.
 * - [safeDetails] carries a bounded number of identifier-style keys with control-character-free
 *   values, so the map can be persisted or logged without a redactor pass.
 * - [correlationId] anchors the error to the audit chain
 *   `sessionId -> turnId -> modelCallId/toolCallId -> approvalId/executionId`.
 *
 * Storage encoding: fixed-order canonical JSON (see ADR-0001).
 */
data class HelixError(
    val code: ErrorCode,
    val userMessage: String,
    val retryable: Boolean,
    val safeDetails: Map<String, String> = emptyMap(),
    val correlationId: CorrelationId,
) {
    init {
        require(userMessage.isNotBlank()) { "userMessage must not be blank" }
        require(userMessage.length <= MAX_USER_MESSAGE_LENGTH) {
            "userMessage exceeds $MAX_USER_MESSAGE_LENGTH characters"
        }
        require(safeDetails.size <= MAX_SAFE_DETAILS) {
            "safeDetails may hold at most $MAX_SAFE_DETAILS entries"
        }
        safeDetails.forEach { (key, value) ->
            require(key.length in 1..MAX_DETAIL_KEY_LENGTH) {
                "safeDetails key length must be 1..$MAX_DETAIL_KEY_LENGTH"
            }
            key.forEach { c ->
                require(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.') {
                    "safeDetails key contains invalid character"
                }
            }
            require(value.length <= MAX_DETAIL_VALUE_LENGTH) {
                "safeDetails value exceeds $MAX_DETAIL_VALUE_LENGTH characters"
            }
            value.forEach { c -> require(c.code >= 0x20) { "safeDetails value contains a control character" } }
        }
    }

    fun toStorageString(): String {
        val details =
            Json.objectFromSortedEntries(
                safeDetails.entries.map { (k, v) -> k to Json.string(v) },
            )
        val pairs =
            listOf(
                "code" to Json.string(code.name),
                "userMessage" to Json.string(userMessage),
                "retryable" to Json.bool(retryable),
                "safeDetails" to details,
                "correlationId" to Json.string(correlationId.value),
            )
        return Json.objectBody(pairs)
    }

    companion object {
        const val MAX_USER_MESSAGE_LENGTH = 512
        const val MAX_SAFE_DETAILS = 16
        const val MAX_DETAIL_KEY_LENGTH = 64
        const val MAX_DETAIL_VALUE_LENGTH = 1024

        private val FIELDS = listOf("code", "userMessage", "retryable", "safeDetails", "correlationId")

        private val CODES_BY_NAME: Map<String, ErrorCode> = ErrorCode.entries.associateBy { it.name }

        fun parse(text: String): HelixError {
            val fields = parseJson(text).requireObject("HelixError", FIELDS)
            val codeName = fields.requireString("code")
            val code = CODES_BY_NAME[codeName] ?: throw IllegalArgumentException("unknown error code")
            return HelixError(
                code = code,
                userMessage = fields.requireString("userMessage"),
                retryable = fields.requireBool("retryable"),
                safeDetails = fields.requireStringObject("safeDetails"),
                correlationId = CorrelationId(fields.requireString("correlationId")),
            )
        }
    }
}
