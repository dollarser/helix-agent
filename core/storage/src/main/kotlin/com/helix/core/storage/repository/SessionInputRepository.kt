package com.helix.core.storage.repository

import com.helix.core.model.TurnState
import com.helix.core.storage.HelixDatabase
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.entity.SessionInputAttachmentEntity
import com.helix.core.storage.entity.SessionInputEntity
import kotlinx.serialization.json.jsonPrimitive

/** Bounded, transactionally accepted user intents. This repository never starts execution. */
@Suppress("TooManyFunctions") // One transactional owner for acceptance, consumption and parked-input CAS.
class SessionInputRepository internal constructor(
    private val database: HelixDatabase,
    private val contentStore: ContentStore,
) {
    private val dao get() = database.sessionInputDao()

    fun get(inputId: String): SessionInputRecord? = transaction { dao.byId(inputId)?.record() }

    fun readText(record: SessionInputRecord): String =
        contentStore.readBounded(ContentRef.parse(record.textRef), MAX_PENDING_BYTES.toInt())

    fun readReference(record: SessionInputRecord): ConversationReferenceSnapshotInput? =
        record.reference?.let { reference ->
            val ref = ContentRef.parse(reference.contentRef)
            require(ref.sha256 == reference.contentSha256 && ref.size == reference.contentBytes)
            ConversationReferenceSnapshotInput(
                sourceSessionId = reference.sourceSessionId,
                sourceSessionTitle = reference.sourceSessionTitle,
                selectionKind = reference.selectionKind,
                sourceMessageIds = reference.sourceMessageIds,
                content =
                    contentStore.readBounded(
                        ref,
                        ConversationReferenceSnapshotInput.MAX_CONTENT_BYTES,
                    ),
            )
        }

    /** Both pending states occupy capacity; a blocked head is returned rather than skipped. */
    fun listPending(
        sessionId: String,
        limit: Int = MAX_PENDING_COUNT,
    ): List<SessionInputRecord> {
        require(limit in 1..MAX_PENDING_COUNT)
        return transaction { dao.listPending(sessionId, limit).map { it.record() } }
    }

    fun headQueue(sessionId: String): SessionInputRecord? = transaction { dao.headQueue(sessionId)?.record() }

    fun recentAppended(
        sessionId: String,
        limit: Int = 8,
    ): List<SessionInputRecord> {
        require(limit in 1..8)
        return transaction { dao.recentAppended(sessionId, limit).map { it.record() } }
    }

    fun headSteer(
        sessionId: String,
        turnId: String,
    ): SessionInputRecord? = transaction { dao.headSteer(sessionId, turnId)?.record() }

    fun pendingRequest(
        turnId: String,
        limit: Int = MAX_PENDING_COUNT,
    ): List<SessionInputRecord> {
        require(limit in 1..MAX_PENDING_COUNT)
        return transaction { dao.pendingRequest(turnId, limit).map { it.record() } }
    }

    /** A request can include more historical inputs than the pending queue capacity. */
    fun appendedForMessages(
        turnId: String,
        messageIds: Set<String>,
    ): List<SessionInputRecord> {
        require(messageIds.size <= com.helix.core.model.ModelRequest.MAX_MESSAGES)
        return transaction {
            messageIds
                .chunked(MAX_PENDING_COUNT)
                .flatMap { dao.appendedForMessages(turnId, it).map { row -> row.record() } }
                .sortedBy { it.sequence }
        }
    }

    @Suppress("ReturnCount") // Reject before writing either a content file or database row.
    fun accept(spec: SessionInputSpec): SessionInputAcceptResult {
        SessionInputValidation.validate(spec)
        return transaction {
            val previous = dao.byId(spec.inputId)
            if (previous != null) {
                return@transaction if (sameIntent(previous, spec)) {
                    SessionInputAcceptResult.Accepted(previous.record(), true)
                } else {
                    SessionInputAcceptResult.Rejected("INPUT_ID_CONFLICT")
                }
            }
            val rejection = acceptanceFailure(spec)
            if (rejection != null) return@transaction SessionInputAcceptResult.Rejected(rejection)
            if (dao.pendingCount(spec.sessionId) >= MAX_PENDING_COUNT) {
                return@transaction SessionInputAcceptResult.Rejected("INPUT_QUEUE_FULL")
            }
            val bytes = SessionInputValidation.textBytes(spec) + SessionInputValidation.referenceBytes(spec)
            if (dao.pendingBytes(spec.sessionId) + bytes > MAX_PENDING_BYTES) {
                return@transaction SessionInputAcceptResult.Rejected("INPUT_QUEUE_BYTES")
            }
            val sequence = Math.addExact(dao.lastSequence(spec.sessionId), 1L)
            val referenceRef =
                spec.reference?.let { reference ->
                    contentStore
                        .write(
                            reference.content,
                        ).also { check(it.sha256 == reference.contentSha256) }
                        .toStorageString()
                }
            val row =
                SessionInputValidation.entity(
                    spec,
                    sequence,
                    contentStore.write(spec.text).toStorageString(),
                    referenceRef,
                )
            dao.insert(row)
            replaceAttachments(spec)
            SessionInputAcceptResult.Accepted(row.record(), false)
        }
    }

    /** Editing a parked input keeps it parked; explicit resume is a separate user action. */
    fun editPending(
        inputId: String,
        expectedRevision: Long,
        replacement: SessionInputSpec,
    ): Boolean {
        SessionInputValidation.validate(replacement)
        require(replacement.inputId == inputId && replacement.revision == Math.addExact(expectedRevision, 1L))
        return transaction {
            val old = editable(inputId, expectedRevision) ?: return@transaction false
            require(replacement.sessionId == old.sessionId)
            // A stale Steer may be edited while parked, but it cannot resume or be consumed.
            if (!validAttachments(replacement) || !targetOwned(replacement.sessionId, replacement.expectedTurnId)) {
                return@transaction false
            }
            val bytes =
                SessionInputValidation.textBytes(replacement) + SessionInputValidation.referenceBytes(replacement)
            if (dao.pendingBytes(old.sessionId) - old.textBytes - old.referenceContentBytes + bytes >
                MAX_PENDING_BYTES
            ) {
                return@transaction false
            }
            val referenceRef =
                replacement.reference?.let { reference ->
                    contentStore
                        .write(reference.content)
                        .also { check(it.sha256 == reference.contentSha256) }
                        .toStorageString()
                }
            val next =
                SessionInputValidation
                    .entity(
                        replacement,
                        old.sequence,
                        contentStore.write(replacement.text).toStorageString(),
                        referenceRef,
                    ).copy(
                        state = old.state,
                        blockedReason = old.blockedReason,
                        createdAt = old.createdAt,
                        updatedAt = maxOf(old.updatedAt, replacement.createdAt),
                    )
            check(dao.update(next) == 1)
            replaceAttachments(replacement)
            true
        }
    }

    fun withdrawPending(
        inputId: String,
        expectedRevision: Long,
        at: Long,
    ): Boolean =
        transaction {
            require(at >= 0)
            val old = editable(inputId, expectedRevision) ?: return@transaction false
            check(
                dao.update(
                    old.copy(
                        state = SessionInputState.WITHDRAWN.name,
                        revision = Math.addExact(old.revision, 1L),
                        blockedReason = null,
                        updatedAt = maxOf(old.updatedAt, at),
                    ),
                ) ==
                    1,
            )
            true
        }

    /** Only a selected, explicitly reconfirmed intent is released; other parked items remain parked. */
    fun resumePending(
        inputId: String,
        expectedRevision: Long,
        at: Long,
    ): Boolean =
        transaction {
            require(at >= 0)
            val old = editable(inputId, expectedRevision) ?: return@transaction false
            if (old.state != SessionInputState.NEEDS_ATTENTION.name || !targetAvailable(old)) return@transaction false
            check(
                dao.update(
                    old.copy(
                        state = SessionInputState.PENDING.name,
                        revision = Math.addExact(old.revision, 1L),
                        blockedReason = null,
                        updatedAt = maxOf(old.updatedAt, at),
                    ),
                ) ==
                    1,
            )
            true
        }

    fun parkSessionInputs(
        sessionId: String,
        reason: String,
        at: Long,
    ): Int =
        transaction {
            SessionInputValidation.park(reason, at)
            dao.parkSession(sessionId, reason, at)
        }

    fun parkAllPending(
        reason: String,
        at: Long,
    ): Int =
        transaction {
            SessionInputValidation.park(reason, at)
            dao.parkAll(reason, at)
        }

    fun markNeedsAttention(
        inputId: String,
        expectedRevision: Long,
        reason: String,
        at: Long,
    ): Boolean =
        transaction {
            SessionInputValidation.park(reason, at)
            val old = editable(inputId, expectedRevision) ?: return@transaction false
            check(
                dao.update(
                    old.copy(
                        state = SessionInputState.NEEDS_ATTENTION.name,
                        revision = Math.addExact(old.revision, 1L),
                        blockedReason = reason,
                        updatedAt = maxOf(old.updatedAt, at),
                    ),
                ) ==
                    1,
            )
            true
        }

    /** Ends an unconsumed input without attributing a withdrawal to the user. */
    fun failPending(
        inputId: String,
        expectedRevision: Long,
        reason: String,
        at: Long,
    ): Boolean =
        transaction {
            SessionInputValidation.park(reason, at)
            val old = editable(inputId, expectedRevision) ?: return@transaction false
            check(
                dao.update(
                    old.copy(
                        state = SessionInputState.FAILED.name,
                        revision = Math.addExact(old.revision, 1L),
                        blockedReason = reason,
                        updatedAt = maxOf(old.updatedAt, at),
                    ),
                ) == 1,
            )
            true
        }

    /** Transfers an unconsumed Steer to the ordinary queue; immutable content/configuration stay bound. */
    fun requeueAfterTargetFinished(
        inputId: String,
        expectedRevision: Long,
        at: Long,
    ): Boolean =
        transaction {
            val old = editable(inputId, expectedRevision) ?: return@transaction false
            if (old.delivery != SessionInputDelivery.STEER.name) return@transaction false
            val target = old.expectedTurnId?.let { database.turnDao().byId(it) } ?: return@transaction false
            if (!TurnState.valueOf(target.state).isTerminal) return@transaction false
            check(
                dao.update(
                    old.copy(
                        delivery = SessionInputDelivery.QUEUE.name,
                        expectedTurnId = null,
                        state = SessionInputState.NEEDS_ATTENTION.name,
                        blockedReason = "STEER_TARGET_FINISHED",
                        revision = Math.addExact(old.revision, 1L),
                        updatedAt = maxOf(old.updatedAt, at),
                    ),
                ) == 1,
            )
            true
        }

    /** Caller inserts USER + bindings in the same outer transaction; false must abort that consumption. */
    fun markAppended(
        inputId: String,
        expectedRevision: Long,
        turnId: String,
        messageId: String,
        at: Long,
    ): Boolean =
        transaction {
            require(at >= 0)
            val old = dao.byId(inputId) ?: return@transaction false
            if (old.state != SessionInputState.PENDING.name ||
                old.revision != expectedRevision
            ) {
                return@transaction false
            }
            validateMapping(old, turnId, messageId)
            check(
                dao.update(
                    old.copy(
                        state = SessionInputState.APPENDED.name,
                        consumedTurnId = turnId,
                        messageId = messageId,
                        revision = Math.addExact(old.revision, 1L),
                        blockedReason = null,
                        updatedAt = maxOf(old.updatedAt, at),
                    ),
                ) == 1,
            )
            true
        }

    /** Call only at actual request admission, never merely when a model-call row is allocated. */
    fun markRequestStarted(
        inputId: String,
        modelCallId: String,
        at: Long,
    ): Boolean =
        transaction {
            require(at >= 0)
            val old = dao.byId(inputId) ?: return@transaction false
            if (old.state != SessionInputState.APPENDED.name) return@transaction false
            if (old.requestModelCallId != null) return@transaction old.requestModelCallId == modelCallId
            val call = requireNotNull(database.modelCallDao().byId(modelCallId))
            require(call.turnId == old.consumedTurnId && call.state == "RUNNING")
            check(dao.update(old.copy(requestModelCallId = modelCallId, updatedAt = maxOf(old.updatedAt, at))) == 1)
            true
        }

    private fun validateMapping(
        old: SessionInputEntity,
        turnId: String,
        messageId: String,
    ) {
        val turn = requireNotNull(database.turnDao().byId(turnId))
        val message = requireNotNull(database.messageDao().byId(messageId))
        require(turn.sessionId == old.sessionId && message.sessionId == old.sessionId)
        require(message.turnId == turnId && message.role == "USER" && message.supersededBy == null)
        require(liveTarget(turn.state))
        if (old.delivery == SessionInputDelivery.STEER.name) require(old.expectedTurnId == turnId)
        if (old.delivery == SessionInputDelivery.QUEUE.name) require(turn.clientRequestId == old.inputId)
        val bindings =
            database.messageAttachmentDao().listByMessage(messageId).map {
                InputAttachment(it.artifactId, it.boundSha256)
            }
        require(bindings == old.record().attachments)
    }

    private fun acceptanceFailure(spec: SessionInputSpec): String? =
        when {
            database.sessionDao().byId(spec.sessionId) == null -> "INPUT_SESSION_MISSING"
            !validAttachments(spec) -> "INPUT_ATTACHMENT_CHANGED"
            !targetAvailable(spec.sessionId, spec.delivery.name, spec.expectedTurnId) -> "INPUT_TARGET_UNAVAILABLE"
            else -> null
        }

    private fun validAttachments(spec: SessionInputSpec): Boolean =
        spec.attachments.all { binding ->
            val artifact = database.artifactDao().byId(binding.artifactId)
            artifact != null && artifact.sessionId == spec.sessionId && artifact.sha256 == binding.boundSha256
        }

    private fun targetOwned(
        sessionId: String,
        expectedTurnId: String?,
    ): Boolean = expectedTurnId == null || database.turnDao().byId(expectedTurnId)?.sessionId == sessionId

    private fun targetAvailable(row: SessionInputEntity): Boolean =
        targetAvailable(row.sessionId, row.delivery, row.expectedTurnId)

    private fun targetAvailable(
        sessionId: String,
        delivery: String,
        expectedTurnId: String?,
    ): Boolean {
        if (expectedTurnId == null) return delivery == SessionInputDelivery.QUEUE.name
        return database.turnDao().byId(expectedTurnId)?.let { turn ->
            turn.sessionId == sessionId && (delivery == SessionInputDelivery.QUEUE.name || liveTarget(turn.state))
        } ?: false
    }

    private fun liveTarget(state: String): Boolean =
        !TurnState.valueOf(state).isTerminal &&
            state !in setOf(TurnState.CANCELLING.name, TurnState.NEEDS_REVIEW.name, TurnState.INTERRUPTED.name)

    private fun editable(
        inputId: String,
        revision: Long,
    ): SessionInputEntity? =
        dao.byId(inputId)?.takeIf {
            it.revision == revision &&
                it.state in setOf(SessionInputState.PENDING.name, SessionInputState.NEEDS_ATTENTION.name)
        }

    private fun replaceAttachments(spec: SessionInputSpec) {
        dao.deleteAttachments(spec.inputId)
        dao.insertAttachments(
            spec.attachments.mapIndexed { index, binding ->
                SessionInputAttachmentEntity(spec.inputId, index, binding.artifactId, binding.boundSha256)
            },
        )
    }

    private fun sameIntent(
        row: SessionInputEntity,
        spec: SessionInputSpec,
    ): Boolean {
        val record = row.record()
        return record.sessionId == spec.sessionId && record.delivery == spec.delivery &&
            record.expectedTurnId == spec.expectedTurnId && record.configuration == spec.configuration &&
            record.attachments == spec.attachments && readText(record) == spec.text &&
            readReference(record) == spec.reference
    }

    private fun SessionInputEntity.record() =
        SessionInputRecord(
            schemaVersion,
            inputId,
            sessionId,
            sequence,
            SessionInputDelivery.valueOf(delivery),
            expectedTurnId,
            revision,
            textRef,
            textBytes,
            dao.attachments(inputId).map { InputAttachment(it.artifactId, it.boundSha256) },
            InputConfiguration(providerId, modelId, mode, configurationFingerprint),
            SessionInputState.valueOf(state),
            consumedTurnId,
            messageId,
            requestModelCallId,
            blockedReason,
            createdAt,
            updatedAt,
            referenceRecord(),
        )

    private fun SessionInputEntity.referenceRecord(): InputConversationReference? {
        val sourceId = referenceSourceSessionId ?: return null
        val title = requireNotNull(referenceSourceSessionTitle)
        val kind = ConversationReferenceKind.valueOf(requireNotNull(referenceSelectionKind))
        val messageIds =
            (
                kotlinx.serialization.json.Json
                    .parseToJsonElement(requireNotNull(referenceSourceMessageIdsJson))
                    as kotlinx.serialization.json.JsonArray
            ).map { it.jsonPrimitive.content }
        return InputConversationReference(
            sourceSessionId = sourceId,
            sourceSessionTitle = title,
            selectionKind = kind,
            sourceMessageIds = messageIds,
            contentRef = requireNotNull(referenceContentRef),
            contentBytes = referenceContentBytes,
            contentSha256 = requireNotNull(referenceContentSha256),
        )
    }

    private fun <T> transaction(block: () -> T): T =
        contentStore.withPublication { database.runInTransaction(java.util.concurrent.Callable { block() }) }

    companion object {
        const val MAX_PENDING_COUNT = 32
        const val MAX_PENDING_BYTES = 1024L * 1024L
        const val MAX_TEXT_CHARS = 128_000
    }
}
