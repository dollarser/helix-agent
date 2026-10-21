package com.helix.core.storage.repository

import com.helix.core.storage.SearchText
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.dao.MessageDao
import com.helix.core.storage.dao.SessionSearchDao
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.MessageSearchEntity

@Suppress("TooManyFunctions") // Effective-history and retained-evidence operations share one repository.
class MessageRepository(
    private val dao: MessageDao,
    private val contentStore: ContentStore,
    private val search: SessionSearchDao,
    private val transaction: (() -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Publishes canonical content, then atomically records the message and its search/activity projection.
     * The ContentStore publication lock serializes sequence allocation against other repository writers;
     * callers may include this operation in a larger storage transaction.
     */
    fun append(
        id: String,
        sessionId: String,
        turnId: String?,
        role: String,
        kind: String,
        content: String,
    ): MessageEntity =
        contentStore.withPublication {
            require(role.isNotBlank()) { "role must not be blank" }
            require(kind.isNotBlank()) { "kind must not be blank" }
            // maxSequence is COALESCE(MAX(sequence), -1), so max + 1 is always a valid sequence.
            val sequence = dao.maxSequence(sessionId) + 1
            val contentRef =
                if (content.isBlank()) {
                    null
                } else {
                    contentStore.write(content).toStorageString()
                }
            val entity =
                MessageEntity(
                    id,
                    sessionId,
                    turnId,
                    role,
                    kind,
                    contentRef,
                    sequence,
                    supersededBy = turnId?.let { dao.supersededRequest(it) },
                    createdAt = clock(),
                )
            transaction {
                dao.insert(entity)
                if (role in setOf("USER", "ASSISTANT") && kind in setOf("TEXT", "TOOL_PROGRESS")) {
                    if (contentRef != null) {
                        search.insert(MessageSearchEntity(id, sessionId, contentRef, content, SearchText.fold(content)))
                    }
                    if (entity.supersededBy == null) search.touch(sessionId, entity.createdAt)
                }
            }
            entity
        }

    /** A trusted manual edit retains the prior body and atomically replaces its search projection. */
    fun editUserText(
        sessionId: String,
        messageId: String,
        expectedContent: String?,
        content: String,
        evidenceId: String,
    ): MessageEntity =
        contentStore.withPublication {
            val original = resolve(messageId)
            require(original.sessionId == sessionId && original.role == "USER" && original.kind == "TEXT")
            require(original.supersededBy == null && original.contentRef == expectedContent) { "MESSAGE_EDIT_CHANGED" }
            require(content.isNotBlank())
            val replacement = contentStore.write(content).toStorageString()
            transaction {
                check(dao.replaceUserContent(messageId, sessionId, expectedContent, replacement) == 1)
                dao.insert(
                    original.copy(
                        id = evidenceId,
                        role = "SYSTEM",
                        kind = "MESSAGE_EDIT_EVIDENCE",
                        sequence = dao.maxSequence(sessionId) + 1,
                    ),
                )
                search.replace(
                    MessageSearchEntity(messageId, sessionId, replacement, content, SearchText.fold(content)),
                )
                search.touch(sessionId, clock())
            }
            original.copy(contentRef = replacement)
        }

    /** Invalidates derived metadata only; callers use the fixed summary kind, never history/effects. */
    fun invalidateDerivedKind(
        sessionId: String,
        kind: String,
        editId: String,
    ) {
        dao.invalidateDerivedKind(sessionId, kind, editId)
    }

    fun retainedByKindAfter(
        kind: String,
        afterId: String?,
        limit: Int,
    ): List<MessageEntity> {
        require(limit in 1..256)
        return dao.retainedByKindAfter(kind, afterId, limit)
    }

    fun resolve(id: String): MessageEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("message not found: $id")
    }

    /** Copy immutable history references only, never the source execution identity. Caller owns the transaction. */
    fun copyHistory(
        source: MessageEntity,
        id: String,
        sessionId: String,
    ): MessageEntity =
        contentStore.withPublication {
            val copied =
                source.copy(
                    id = id,
                    sessionId = sessionId,
                    turnId = null,
                    sequence =
                        dao.maxSequence(sessionId) + 1,
                )
            transaction {
                dao.insert(copied)
                search.copy(source.id, id, sessionId)
            }
            copied
        }

    fun supersededTurns(sessionId: String): Set<String> = dao.supersededTurns(sessionId).toSet()

    fun latestUser(sessionId: String): MessageEntity? = dao.latestUser(sessionId)

    fun allRevisions(sessionId: String): List<MessageEntity> = dao.allRevisions(sessionId)

    fun latestAssistant(sessionId: String): MessageEntity? = dao.latestAssistant(sessionId)

    /** Caller owns the transaction with replacement Turn creation; history is retained for audit. */
    fun reviseLatest(
        sessionId: String,
        messageId: String,
        requestId: String,
    ) {
        val target = requireNotNull(dao.latestUser(sessionId)) { "REVISION_TARGET_CHANGED" }
        require(target.id == messageId) { "REVISION_TARGET_CHANGED" }
        dao.supersedeFrom(sessionId, target.sequence, requestId)
    }

    /** Caller owns the transaction with replacement Turn creation; history is retained for audit. */
    fun regenerateLatest(
        sessionId: String,
        messageId: String,
        requestId: String,
    ) {
        val target = requireNotNull(dao.byId(messageId)) { "REGENERATE_TARGET_NOT_FOUND" }
        require(target.sessionId == sessionId) { "REGENERATE_SESSION_MISMATCH" }
        require(target.role == "ASSISTANT") { "REGENERATE_ROLE_INVALID" }
        require(target.supersededBy == null) { "REGENERATE_ALREADY_SUPERSEDED" }
        val latestAssistant = requireNotNull(dao.latestAssistant(sessionId)) { "REGENERATE_TARGET_CHANGED" }
        require(latestAssistant.id == messageId) { "REGENERATE_TARGET_CHANGED" }
        dao.supersedeFrom(sessionId, target.sequence, requestId)
    }

    fun supersedeFrom(
        sessionId: String,
        fromSequence: Long,
        requestId: String,
    ) {
        dao.supersedeFrom(sessionId, fromSequence, requestId)
    }

    fun listBySession(sessionId: String): List<MessageEntity> = dao.listBySession(sessionId)

    fun recentBySession(
        sessionId: String,
        limit: Int,
        through: Long = Long.MAX_VALUE,
    ): List<MessageEntity> {
        require(limit > 0)
        return dao.recentBySession(sessionId, limit, through).reversed()
    }

    fun byTurn(turnId: String): List<MessageEntity> = dao.byTurn(turnId)

    fun pageAfter(
        sessionId: String,
        after: Long,
        limit: Int,
    ): List<MessageEntity> {
        require(limit in 1..256)
        return dao.pageAfter(sessionId, after, limit)
    }

    fun latestOfKind(
        sessionId: String,
        kind: String,
        includeSuperseded: Boolean = false,
    ): MessageEntity? = dao.latestOfKind(sessionId, kind, includeSuperseded)

    fun readContentBounded(
        message: MessageEntity,
        maxBytes: Int,
    ): String? = message.contentRef?.let { contentStore.readBounded(ContentRef.parse(it), maxBytes) }

    fun readContent(message: MessageEntity): String? {
        val ref = message.contentRef ?: return null
        return contentStore.read(ContentRef.parse(ref))
    }
}
