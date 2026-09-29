package com.helix.core.storage.repository

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.dao.MessageReferenceSnapshotDao
import com.helix.core.storage.entity.MessageReferenceSnapshotEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

enum class ConversationReferenceKind {
    SUMMARY,
    RECENT_MESSAGES,
}

/** Prepared immutable bytes carried across Turn admission; never dereferenced after binding. */
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

data class ConversationReferenceSnapshot(
    val sourceSessionId: String,
    val sourceSessionTitle: String,
    val selectionKind: ConversationReferenceKind,
    val sourceMessageIds: List<String>,
    val content: String,
    val contentSha256: String,
)

class MessageReferenceSnapshotRepository(
    private val dao: MessageReferenceSnapshotDao,
    private val contentStore: ContentStore,
) {
    /** Caller owns the surrounding Turn/message transaction. */
    fun bind(
        messageId: String,
        inputs: List<ConversationReferenceSnapshotInput>,
        nowEpochMillis: Long,
    ) = contentStore.withPublication {
        require(inputs.size <= MAX_REFERENCES_PER_MESSAGE)
        inputs.forEachIndexed { ordinal, input ->
            val ref = contentStore.write(input.content)
            check(ref.sha256 == input.contentSha256)
            dao.insert(
                MessageReferenceSnapshotEntity(
                    messageId = messageId,
                    ordinal = ordinal,
                    sourceSessionId = input.sourceSessionId,
                    sourceSessionTitle = input.sourceSessionTitle,
                    selectionKind = input.selectionKind.name,
                    sourceMessageIdsJson = encodeIds(input.sourceMessageIds),
                    contentRef = ref.toStorageString(),
                    contentSha256 = input.contentSha256,
                    createdAtEpoch = nowEpochMillis,
                ),
            )
        }
    }

    fun forMessage(messageId: String): List<ConversationReferenceSnapshot> =
        dao.byMessage(messageId).map { entity ->
            val ref = ContentRef.parse(entity.contentRef)
            require(ref.sha256 == entity.contentSha256) { "reference snapshot hash metadata mismatch" }
            val content = contentStore.readBounded(ref, ConversationReferenceSnapshotInput.MAX_CONTENT_BYTES)
            ConversationReferenceSnapshot(
                sourceSessionId = entity.sourceSessionId,
                sourceSessionTitle = entity.sourceSessionTitle,
                selectionKind = ConversationReferenceKind.valueOf(entity.selectionKind),
                sourceMessageIds = decodeIds(entity.sourceMessageIdsJson),
                content = content,
                contentSha256 = entity.contentSha256,
            )
        }

    private fun encodeIds(ids: List<String>): String = JsonArray(ids.map(::JsonPrimitive)).toString()

    private fun decodeIds(value: String): List<String> =
        (Json.parseToJsonElement(value) as JsonArray).map { it.jsonPrimitive.content }

    companion object {
        const val MAX_REFERENCES_PER_MESSAGE = 1
    }
}
