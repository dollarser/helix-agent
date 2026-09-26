package com.helix.app.chat

import com.helix.core.storage.repository.ConversationReferenceSnapshotInput
import com.helix.core.storage.repository.MessageAttachmentRepository
import java.security.MessageDigest

/**
 * The input fingerprint behind the persistent submit-dedup receipt (research doc section 34;
 * HX2-01 §2e): a stable SHA-256 over the exact content a submission carries — the user text plus
 * each attachment's artifactId and its bound snapshot hash, in binding order. Two re-drives with
 * the same client-request id fingerprint to the same value ONLY if they carry identical content,
 * so a same-id re-drive with different content is a detectable CONFLICT, never a silent dedup to
 * the wrong turn. Pure (no storage / provider / coroutine) so it is unit-testable on the JVM, where
 * the heavy [ChatService] cannot be constructed.
 */
internal object TurnInputFingerprint {
    fun of(
        text: String?,
        attachments: List<MessageAttachmentRepository.Binding>,
        references: List<ConversationReferenceSnapshotInput> = emptyList(),
        revisedMessageId: String? = null,
        regenerateMessageId: String? = null,
        recoveryFromTurnId: String? = null,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(text.orEmpty().toByteArray(Charsets.UTF_8))
        digest.update(SEPARATOR)
        attachments.forEach { binding ->
            digest.update(binding.artifactId.toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
            digest.update(binding.boundSha256.toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
        }
        references.forEach { reference ->
            digest.update("reference:".toByteArray(Charsets.UTF_8))
            digest.update(reference.sourceSessionId.toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
            digest.update(reference.selectionKind.name.toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
            digest.update(reference.contentSha256.toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
        }
        if (revisedMessageId != null) {
            digest.update("revision:".toByteArray(Charsets.UTF_8))
            digest.update(revisedMessageId.toByteArray(Charsets.UTF_8))
        }
        if (regenerateMessageId != null) {
            digest.update("regenerate:".toByteArray(Charsets.UTF_8))
            digest.update(regenerateMessageId.toByteArray(Charsets.UTF_8))
        }
        if (recoveryFromTurnId != null) {
            digest.update(SEPARATOR)
            digest.update("recovery:".toByteArray(Charsets.UTF_8))
            digest.update(recoveryFromTurnId.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    // The ASCII "unit separator" (0x1F) between fields, so adjacent fields cannot merge into a
    // colliding digest (e.g. text "ab" + artifact "c" vs text "a" + artifact "bc").
    private val SEPARATOR: Byte = 0x1F.toByte()
}
