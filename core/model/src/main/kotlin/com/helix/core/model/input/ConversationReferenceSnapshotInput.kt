package com.helix.core.model.input

import java.security.MessageDigest

enum class ConversationReferenceKind {
    HISTORY,
}

/** Immutable read manifest carried across admission; bodies are read only through scoped tools. */
data class ConversationReferenceSnapshotInput(
    val sourceSessionId: String,
    val sourceSessionTitle: String,
    val selectionKind: ConversationReferenceKind,
    val sourceMessageIds: List<String>,
    val content: String,
) {
    init {
        require(sourceSessionId.isNotBlank())
        require(sourceSessionTitle.length <= MAX_TITLE_LENGTH)
        require(sourceMessageIds.size <= MAX_SOURCE_MESSAGES)
        require(sourceMessageIds.all { it.isNotBlank() && it.length <= MAX_ID_LENGTH })
        require(content.isNotBlank() && content.toByteArray(Charsets.UTF_8).size <= MAX_CONTENT_BYTES)
        ConversationReferenceManifest.parse(content)
    }

    val contentSha256: String
        get() =
            MessageDigest
                .getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_CONTENT_BYTES = 16_384
        const val MAX_SOURCE_MESSAGES = 8
        const val MAX_TITLE_LENGTH = 256
        const val MAX_ID_LENGTH = 256
    }
}
