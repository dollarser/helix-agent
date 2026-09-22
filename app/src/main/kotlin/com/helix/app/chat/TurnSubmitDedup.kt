package com.helix.app.chat

import com.helix.core.storage.entity.TurnEntity
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
        revisedMessageId: String? = null,
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
        if (revisedMessageId != null) {
            digest.update("revision:".toByteArray(Charsets.UTF_8))
            digest.update(revisedMessageId.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    // The ASCII "unit separator" (0x1F) between fields, so adjacent fields cannot merge into a
    // colliding digest (e.g. text "ab" + artifact "c" vs text "a" + artifact "bc").
    private val SEPARATOR: Byte = 0x1F.toByte()
}

/** How a re-driven client-request id resolves against the persistent dedup receipt (the turn row). */
internal sealed interface TurnDedupDecision {
    /** The id is new — proceed to start the turn and record the receipt. */
    object Fresh : TurnDedupDecision

    /** The id already started [turnId] with the SAME session + input — return it, start nothing. */
    data class Dedup(
        val turnId: String,
    ) : TurnDedupDecision

    /** The id was already used for a DIFFERENT session or input — refuse (fail-closed). */
    object Conflict : TurnDedupDecision
}

/**
 * Decides how a re-driven client-request id resolves against the turn row it already bound
 * (research doc section 34). [existing] is the receipt row (null when the id is new); the incoming
 * submission is identified by [incomingSessionId] + [incomingFingerprint]. A match on BOTH the
 * session and the input fingerprint is a dedup to the existing turn; ANY divergence (a different
 * session, or different content under the same id) is a conflict — the safe default is to refuse,
 * so one client-request id can never start two different turns.
 */
internal object TurnDedup {
    fun decide(
        existing: TurnEntity?,
        incomingSessionId: String,
        incomingFingerprint: String,
    ): TurnDedupDecision =
        when {
            existing == null -> {
                TurnDedupDecision.Fresh
            }

            existing.sessionId == incomingSessionId && existing.inputFingerprint == incomingFingerprint -> {
                TurnDedupDecision.Dedup(existing.id)
            }

            else -> {
                TurnDedupDecision.Conflict
            }
        }
}
