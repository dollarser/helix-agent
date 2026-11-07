package com.helix.core.storage.repository

import com.helix.core.model.input.ConversationReferenceKind
import com.helix.core.model.input.ConversationReferenceManifest
import com.helix.core.model.input.ConversationReferenceSnapshotInput
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.dao.SessionHistoryDao
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.MessageReferenceSnapshotEntity

/** Identity supplied by the trusted Dispatcher, not JSON arguments or the currently visible page. */
data class SessionHistoryCaller(
    val sessionId: String,
    val turnId: String,
    val callId: String,
    val toolName: String,
)

data class SessionHistoryPage(
    val messages: List<MessageEntity>,
    val nextBeforeSequence: Long?,
)

data class SessionHistoryContent(
    val content: String,
    val sha256: String,
)

/** Created only after a persisted, caller-owned reference binding has been checked. */
class SessionReferenceAccess internal constructor(
    val messageId: String,
    val contentHash: String,
    val sourceSessionId: String,
    val manifest: ConversationReferenceManifest,
    internal val checkActive: () -> Unit,
    internal val rows: Map<String, MessageEntity>,
)

/** Existing Room facts and ContentStore only; no new transcript, permission, or execution owner. */
class SessionHistoryRepository(
    private val dao: SessionHistoryDao,
    private val contentStore: ContentStore,
) {
    /** Metadata exposure only; each call still needs its own durable owner and revision checks. */
    fun referenceToolNames(sessionId: String): Set<String> =
        buildSet {
            val knowledge = dao.hasKnowledgeReferences(sessionId)
            if (knowledge) addAll(listOf("knowledge.search", "knowledge.read"))
            if (knowledge || dao.hasConversationReferences(sessionId)) {
                addAll(listOf("session.history", "session.search", "session.read"))
            }
        }

    fun captureReference(
        sourceSessionId: String,
        throughSequence: Long? = null,
        checkActive: () -> Unit = {},
        onRow: (MessageEntity) -> Unit = {},
    ): ConversationReferenceManifest {
        requireNotNull(dao.sourceSession(sourceSessionId)) { "REFERENCE_SOURCE_UNAVAILABLE" }
        val ceiling =
            throughSequence ?: dao.page(sourceSessionId, Long.MAX_VALUE, 1).firstOrNull()?.sequence
                ?: throw IllegalArgumentException("REFERENCE_EMPTY")
        require(ceiling in 0 until Long.MAX_VALUE)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var before = ceiling + 1
        var count = 0
        var bytes = 0L
        while (true) {
            checkActive()
            val rows = dao.page(sourceSessionId, before, MAX_PAGE)
            if (rows.isEmpty()) break
            for (row in rows) {
                checkActive()
                val ref = row.contentRef?.let(ContentRef::parse)
                val identity =
                    kotlinx.serialization.json
                        .JsonArray(
                            listOf(
                                row.id,
                                row.sequence.toString(),
                                row.role,
                                row.contentRef.orEmpty(),
                            ).map { kotlinx.serialization.json.JsonPrimitive(it) },
                        ).toString()
                digest.update(identity.toByteArray(Charsets.UTF_8))
                onRow(row)
                count++
                bytes = Math.addExact(bytes, ref?.size ?: 0)
            }
            before = rows.last().sequence
        }
        require(count > 0 && bytes > 0) { "REFERENCE_EMPTY" }
        return ConversationReferenceManifest(
            ceiling,
            digest.digest().joinToString("") { "%02x".format(it) },
            count,
            bytes,
        )
    }

    fun referenceAccess(
        caller: SessionHistoryCaller,
        messageId: String,
        contentHash: String,
        checkActive: () -> Unit = {},
    ): SessionReferenceAccess {
        val owner = message(caller, messageId)
        require(owner.role == "USER") { "REFERENCE_NOT_USER_SHARED" }
        val snapshot = requireNotNull(dao.references(messageId).singleOrNull()) { "REFERENCE_NOT_SHARED" }
        require(snapshot.selectionKind == ConversationReferenceKind.HISTORY.name)
        require(snapshot.contentSha256 == contentHash) { "REFERENCE_IDENTITY_CHANGED" }
        val ref = ContentRef.parse(snapshot.contentRef)
        require(ref.sha256 == contentHash)
        val manifest =
            ConversationReferenceManifest.parse(
                contentStore.readBounded(
                    ref,
                    ConversationReferenceSnapshotInput.MAX_CONTENT_BYTES,
                ),
            )
        val rows = mutableMapOf<String, MessageEntity>()
        val current =
            captureReference(snapshot.sourceSessionId, manifest.throughSequence, checkActive) {
                rows[it.id] = it
            }
        require(current == manifest) { "REFERENCE_SOURCE_CHANGED" }
        caller(caller)
        return SessionReferenceAccess(
            messageId,
            contentHash,
            snapshot.sourceSessionId,
            manifest,
            checkActive,
            rows.toMap(),
        )
    }

    fun validateReference(
        caller: SessionHistoryCaller,
        reference: SessionReferenceAccess,
    ) {
        reference.checkActive()
        require(message(caller, reference.messageId).role == "USER")
        require(
            dao.references(reference.messageId).singleOrNull()?.let {
                it.contentSha256 == reference.contentHash && it.sourceSessionId == reference.sourceSessionId
            } == true,
        )
        require(
            captureReference(
                reference.sourceSessionId,
                reference.manifest.throughSequence,
                reference.checkActive,
            ) == reference.manifest,
        ) { "REFERENCE_SOURCE_CHANGED" }
        caller(caller)
    }

    fun caller(caller: SessionHistoryCaller) =
        requireNotNull(dao.caller(caller.sessionId, caller.turnId, caller.callId, caller.toolName)) {
            "SESSION_CALL_UNAVAILABLE"
        }

    fun page(
        caller: SessionHistoryCaller,
        before: Long,
        limit: Int,
        reference: SessionReferenceAccess? = null,
    ): SessionHistoryPage {
        require(before >= 0 && limit in 1..MAX_PAGE)
        caller(caller)
        val rows =
            if (reference == null) {
                dao.page(caller.sessionId, before, limit + 1)
            } else {
                reference.rows.values
                    .filter { it.sequence < before }
                    .sortedByDescending { it.sequence }
                    .take(limit + 1)
            }
        val page = rows.take(limit)
        return SessionHistoryPage(page, page.lastOrNull()?.sequence?.takeIf { rows.size > limit })
    }

    fun message(
        caller: SessionHistoryCaller,
        messageId: String,
    ): MessageEntity {
        caller(caller)
        return requireNotNull(dao.message(caller.sessionId, messageId)) { "SESSION_MESSAGE_UNAVAILABLE" }
    }

    fun references(
        caller: SessionHistoryCaller,
        messageId: String,
    ): List<MessageReferenceSnapshotEntity> {
        message(caller, messageId)
        return dao.references(messageId)
    }

    fun read(
        caller: SessionHistoryCaller,
        messageId: String,
        referenceOrdinal: Int?,
        reference: SessionReferenceAccess? = null,
    ): SessionHistoryContent {
        caller(caller)
        val message =
            if (reference == null) {
                message(caller, messageId)
            } else {
                require(referenceOrdinal == null)
                requireNotNull(reference.rows[messageId]).also { captured ->
                    require(dao.message(reference.sourceSessionId, messageId)?.contentRef == captured.contentRef) {
                        "REFERENCE_SOURCE_CHANGED"
                    }
                }
            }
        val ref =
            if (referenceOrdinal == null) {
                ContentRef.parse(requireNotNull(message.contentRef))
            } else {
                val snapshot = requireNotNull(references(caller, messageId).find { it.ordinal == referenceOrdinal })
                ContentRef.parse(snapshot.contentRef).also {
                    require(it.sha256 == snapshot.contentSha256) { "SESSION_REFERENCE_INTEGRITY" }
                }
            }
        val content = contentStore.readBounded(ref, MAX_CONTENT_BYTES)
        if (referenceOrdinal != null) ConversationReferenceManifest.parse(content)
        // A concurrent revision/deletion or terminated caller must not publish stale content.
        require(
            (
                if (reference == null) {
                    message(caller, messageId)
                } else {
                    requireNotNull(dao.message(reference.sourceSessionId, messageId))
                }
            ).contentRef == message.contentRef,
        ) { "SESSION_MESSAGE_CHANGED" }
        return SessionHistoryContent(content, ref.sha256)
    }

    fun rename(
        caller: SessionHistoryCaller,
        expectedTitle: String,
        title: String,
    ): Boolean {
        require(caller.toolName == "session.rename")
        require(expectedTitle.length <= 200 && title.isNotBlank() && title.length <= 200 && '\u0000' !in title)
        return dao.rename(
            caller.sessionId,
            caller.turnId,
            caller.callId,
            expectedTitle,
            title.trim(),
            com.helix.core.storage.SearchText
                .fold(title.trim()),
        ) == 1
    }

    companion object {
        const val MAX_PAGE = 40
        const val MAX_CONTENT_BYTES = 1_048_576
    }
}
