package com.helix.core.model

/**
 * Compact request context entry identifying an input message.
 * roleCode: 's' for SYSTEM, 'u' for USER, 'a' for ASSISTANT, 't' for TOOL.
 */
data class MessageRefEntry(
    val messageId: String,
    val roleCode: Char,
) {
    init {
        require(roleCode in VALID_ROLE_CODES) { "invalid role code: $roleCode" }
    }

    companion object {
        const val ROLE_SYSTEM = 's'
        const val ROLE_USER = 'u'
        const val ROLE_ASSISTANT = 'a'
        const val ROLE_TOOL = 't'

        val VALID_ROLE_CODES = setOf(ROLE_SYSTEM, ROLE_USER, ROLE_ASSISTANT, ROLE_TOOL)

        fun fromModelRole(role: ModelRole): Char =
            when (role) {
                ModelRole.SYSTEM -> ROLE_SYSTEM
                ModelRole.USER -> ROLE_USER
                ModelRole.ASSISTANT -> ROLE_ASSISTANT
                ModelRole.TOOL -> ROLE_TOOL
            }
    }
}

/**
 * HXA-217 / ADR-AGENT-010 Lightweight request context manifest.
 * Records the exact ordered sequence of messages, revision input IDs and compaction boundary.
 */
data class RequestContextManifest(
    val callId: String,
    val timestamp: Long,
    val checkpoint: Long? = null,
    val messages: List<MessageRefEntry>,
    val inputIds: List<String>,
    val isTruncated: Boolean = false,
) {
    companion object {
        const val MAX_SINGLE_LINE_BYTES = 256 * 1024 // 256 KiB JSONL single line limit
        const val MAX_SAFE_MESSAGES = 512
        const val MAX_SAFE_INPUT_IDS = 512
    }
}

/**
 * Pure compact JSON codec and size estimator for RequestContextManifest.
 */
object CompactManifestCodec {
    /**
     * Serializes manifest to a compact JSON string conforming to ADR-AGENT-010 evaluation.
     */
    fun encodeCompact(manifest: RequestContextManifest): String {
        val sb = StringBuilder(1024)
        sb.append("{\"c\":\"").append(escapeJson(manifest.callId)).append("\",")
        sb.append("\"t\":").append(manifest.timestamp).append(",")
        if (manifest.checkpoint != null) {
            sb.append("\"cp\":").append(manifest.checkpoint).append(",")
        }
        if (manifest.isTruncated) {
            sb.append("\"tr\":true,")
        }

        // Messages array as compact tuples [["id","u"],["id2","a"]]
        sb.append("\"m\":[")
        manifest.messages.forEachIndexed { index, m ->
            if (index > 0) sb.append(",")
            sb
                .append("[\"")
                .append(escapeJson(m.messageId))
                .append("\",\"")
                .append(m.roleCode)
                .append("\"]")
        }
        sb.append("],")

        // Input IDs array
        sb.append("\"i\":[")
        manifest.inputIds.forEachIndexed { index, id ->
            if (index > 0) sb.append(",")
            sb.append("\"").append(escapeJson(id)).append("\"")
        }
        sb.append("]}")

        val result = sb.toString()
        return result
    }

    /**
     * Creates a bounded manifest that guarantees line length <= MAX_SINGLE_LINE_BYTES.
     */
    fun bounded(
        callId: String,
        timestamp: Long,
        checkpoint: Long? = null,
        messages: List<MessageRefEntry>,
        inputIds: List<String>,
    ): RequestContextManifest {
        var safeMessages = messages.take(RequestContextManifest.MAX_SAFE_MESSAGES)
        var safeInputs = inputIds.take(RequestContextManifest.MAX_SAFE_INPUT_IDS)
        var truncated = (messages.size > safeMessages.size) || (inputIds.size > safeInputs.size)

        var candidate =
            RequestContextManifest(
                callId = callId,
                timestamp = timestamp,
                checkpoint = checkpoint,
                messages = safeMessages,
                inputIds = safeInputs,
                isTruncated = truncated,
            )

        // If byte length still exceeds 256 KiB (e.g. extreme Unicode input IDs), drop older entries
        var encoded = encodeCompact(candidate)
        while (encoded.toByteArray(Charsets.UTF_8).size > RequestContextManifest.MAX_SINGLE_LINE_BYTES &&
            (safeMessages.isNotEmpty() || safeInputs.isNotEmpty())
        ) {
            truncated = true
            if (safeInputs.size > 10) {
                safeInputs = safeInputs.take(safeInputs.size / 2)
            } else if (safeMessages.size > 10) {
                safeMessages = safeMessages.take(safeMessages.size / 2)
            } else {
                safeInputs = emptyList()
                safeMessages = emptyList()
            }
            candidate =
                RequestContextManifest(
                    callId = callId,
                    timestamp = timestamp,
                    checkpoint = checkpoint,
                    messages = safeMessages,
                    inputIds = safeInputs,
                    isTruncated = true,
                )
            encoded = encodeCompact(candidate)
        }

        return candidate
    }

    private fun escapeJson(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
