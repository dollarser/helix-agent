package com.helix.app.chat

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.core.model.ModelRole
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.repository.ConversationReferenceKind
import com.helix.core.storage.repository.ConversationReferenceSnapshotInput

/** Materializes a bounded other-conversation snapshot exactly once at submission acceptance. */
internal class ConversationReferenceResolver(
    private val storage: HelixStorage,
) {
    fun prepare(
        targetSessionId: String,
        sourceSessionId: String,
        kind: ConversationReferenceKind,
    ): ConversationReferenceSnapshotInput {
        require(targetSessionId != sourceSessionId) { "REFERENCE_SELF" }
        val source = storage.sessions.resolve(sourceSessionId)
        val selection =
            when (kind) {
                ConversationReferenceKind.SUMMARY -> summary(sourceSessionId)
                ConversationReferenceKind.RECENT_MESSAGES -> recentMessages(sourceSessionId)
            }
        require(selection.content.isNotBlank()) { "REFERENCE_EMPTY" }
        return ConversationReferenceSnapshotInput(
            sourceSessionId = sourceSessionId,
            sourceSessionTitle = source.title,
            selectionKind = kind,
            sourceMessageIds = selection.messageIds,
            content = boundedUtf8(selection.content, ConversationReferenceSnapshotInput.MAX_CONTENT_BYTES),
        )
    }

    fun hasSummary(sessionId: String): Boolean =
        storage.messages.latestOfKind(sessionId, ContextCompaction.KIND) != null

    private fun summary(sessionId: String): Selection {
        val row =
            requireNotNull(storage.messages.latestOfKind(sessionId, ContextCompaction.KIND)) {
                "REFERENCE_SUMMARY_UNAVAILABLE"
            }
        val checkpoint =
            requireNotNull(
                ContextCompaction.checkpoint(storage, listOf(row)),
            ) { "REFERENCE_SUMMARY_UNAVAILABLE" }
        return Selection(listOf(row.id), checkpoint.summary)
    }

    private fun recentMessages(sessionId: String): Selection {
        val candidates =
            storage.messages
                .listBySession(sessionId)
                .asSequence()
                .filter { it.supersededBy == null }
                .filter { it.kind == ChatHistoryBuilder.KIND_TEXT }
                .filter { it.role == ModelRole.USER.name || it.role == ModelRole.ASSISTANT.name }
                .filter { it.contentRef != null }
                .toList()
                .takeLast(ConversationReferenceSnapshotInput.MAX_SOURCE_MESSAGES)
        val selected = mutableListOf<Pair<String, String>>()
        var used = 0
        for (row in candidates.asReversed()) {
            val block = recentMessageBlock(row, ConversationReferenceSnapshotInput.MAX_CONTENT_BYTES - used) ?: continue
            val bytes = block.toByteArray(Charsets.UTF_8).size
            selected += row.id to block
            used += bytes + 2
        }
        val chronological = selected.asReversed()
        return Selection(
            chronological.map { it.first },
            chronological.joinToString("\n\n") { it.second },
        )
    }

    private fun recentMessageBlock(
        row: com.helix.core.storage.entity.MessageEntity,
        remainingBytes: Int,
    ): String? =
        ContentRef.parse(requireNotNull(row.contentRef)).let { ref ->
            if (ref.size > remainingBytes) {
                null
            } else {
                storage.messages.readContentBounded(row, remainingBytes)?.let { content ->
                    val block = "${row.role}:\n$content"
                    block.takeIf { it.toByteArray(Charsets.UTF_8).size <= remainingBytes }
                }
            }
        }

    private fun boundedUtf8(
        value: String,
        maxBytes: Int,
    ): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val out = StringBuilder()
        val iterator = value.codePoints().iterator()
        var bytes = 0
        while (iterator.hasNext()) {
            val piece = String(Character.toChars(iterator.nextInt()))
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            out.append(piece)
            bytes += size
        }
        return out.toString()
    }

    private data class Selection(
        val messageIds: List<String>,
        val content: String,
    )
}
