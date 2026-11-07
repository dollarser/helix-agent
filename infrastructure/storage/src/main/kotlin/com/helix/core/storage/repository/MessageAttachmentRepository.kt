package com.helix.core.storage.repository

import com.helix.core.storage.dao.MessageAttachmentDao
import com.helix.core.storage.entity.MessageAttachmentEntity

/**
 * Binds a message to the immutable Artifact snapshots it carries (ADR-0014, HXA-049). The bind
 * MUST run inside [com.helix.core.storage.HelixStorage.withTransaction] alongside the message
 * insert so the pair is atomic (doc 9.2: paired writes in one transaction).
 *
 * [Binding.boundSha256] is the hash captured at bind time; the explicit send, its disclosure
 * confirmation and a retry of the bound turn re-verify the artifact file against it and fail
 * closed on any change or a missing file (the persisted inlined snapshot is re-sent verbatim on
 * retry; a plain session re-open reads the persisted message and does NOT re-verify).
 * [Binding.purpose] is the attachment's role in the message — a stable, bounded, non-sensitive
 * descriptor, deliberately NOT the closed classification (which is re-derived from the hash-verified
 * bytes, so a stale `purpose` cannot change what the model reads).
 */
class MessageAttachmentRepository(
    private val dao: MessageAttachmentDao,
) {
    /** One attachment bound to a message: the durable artifact + its bind-time hash + role. */
    data class Binding(
        val artifactId: String,
        val purpose: String,
        val boundSha256: String,
    )

    /**
     * Binds [messageId] to [bindings] in list order — the caller's order IS the ordinal, assigned
     * here as 0..n-1. Enforces the ADR-0014 closed limit of [MAX_ATTACHMENTS_PER_MESSAGE] per
     * message as a fail-closed persistence guard (independent of the UI). All validation happens
     * before any insert, so a rejected bind leaves no partial rows; the message/artifact FKs are
     * CASCADE, so deleting a message removes its bindings.
     */
    fun bind(
        messageId: String,
        bindings: List<Binding>,
    ) {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        require(bindings.size <= MAX_ATTACHMENTS_PER_MESSAGE) {
            "a message may bind at most $MAX_ATTACHMENTS_PER_MESSAGE attachments, got ${bindings.size}"
        }
        bindings.forEachIndexed { ordinal, binding ->
            require(binding.artifactId.isNotBlank()) { "attachment $ordinal artifactId must not be blank" }
            require(binding.purpose.isNotBlank()) { "attachment $ordinal purpose must not be blank" }
            require(isSha256(binding.boundSha256)) { "attachment $ordinal boundSha256 must be 64 lowercase hex chars" }
        }
        bindings.forEachIndexed { ordinal, binding ->
            dao.insert(
                MessageAttachmentEntity(
                    rowId = 0L, // autoGenerate: Room assigns the rowid on insert
                    messageId = messageId,
                    artifactId = binding.artifactId,
                    ordinal = ordinal,
                    purpose = binding.purpose,
                    boundSha256 = binding.boundSha256,
                ),
            )
        }
    }

    /** The message's bindings, ordinal ascending — the materialization order. */
    fun listByMessage(messageId: String): List<MessageAttachmentEntity> = dao.listByMessage(messageId)

    private fun isSha256(value: String): Boolean = value.length == 64 && value.all { it in "0123456789abcdef" }

    companion object {
        /** ADR-0014 closed limit: at most 4 attachments per message (mirrors the classifier cap). */
        const val MAX_ATTACHMENTS_PER_MESSAGE = 4
    }
}
