package com.helix.feature.conversation.application

import com.helix.core.model.input.InputAttachmentLimits
import com.helix.feature.conversation.application.attachment.StagedAttachmentCandidate

/** Owns transient input facts only; Room receipts and Turn execution stay authoritative elsewhere. */
class ComposerInputState {
    val drafts = ChatDraftStore()
    private val stagedLock = Any()
    private var revision = 0L

    @Volatile private var entries: List<StagedAttachmentCandidate> = emptyList()
    val staged: List<StagedAttachmentCandidate> get() = entries
    val stagedRevision: Long get() = synchronized(stagedLock) { revision }

    @Volatile var shareText: String? = null
        private set

    fun share(text: String?) {
        shareText = text
    }

    fun clearStaged() =
        synchronized(stagedLock) {
            entries = emptyList()
            revision += 1
        }

    fun removeStaged(ids: Collection<String>) =
        synchronized(stagedLock) {
            entries = entries.filterNot { it.artifactId in ids }
            revision += 1
        }

    fun replaceStaged(
        restored: List<StagedAttachmentCandidate>,
        expectedSessionId: String,
        expectedRevision: Long,
        currentSession: () -> String?,
    ): Boolean =
        synchronized(stagedLock) {
            if (currentSession() != expectedSessionId || revision != expectedRevision) return@synchronized false
            if (restored.size > InputAttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE ||
                restored.any { it.sessionId != expectedSessionId } ||
                restored.map { it.artifactId }.distinct().size != restored.size
            ) {
                return@synchronized false
            }
            entries = restored.toList()
            revision += 1
            true
        }

    fun stagedIds(expectedSessionId: String?): List<String> =
        synchronized(stagedLock) {
            entries.filter { expectedSessionId == null || it.sessionId == expectedSessionId }.map { it.artifactId }
        }

    fun admit(
        entry: StagedAttachmentCandidate,
        currentSession: () -> String?,
    ): Admission =
        synchronized(stagedLock) {
            when {
                currentSession() != entry.sessionId -> {
                    Admission.STALE_SESSION
                }

                entries.any { it.artifactId == entry.artifactId } -> {
                    Admission.DUPLICATE
                }

                entries.size >= InputAttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE -> {
                    Admission.FULL
                }

                else -> {
                    entries = entries + entry
                    revision += 1
                    Admission.ADDED
                }
            }
        }

    /** An explicit send must enumerate exactly the current session's ordered attachment set. */
    fun snapshotForSend(
        expectedSessionId: String,
        approvedArtifactIds: List<String>,
    ): List<StagedAttachmentCandidate>? =
        synchronized(stagedLock) {
            entries
                .takeIf { snapshot ->
                    snapshot.all { it.sessionId == expectedSessionId } &&
                        snapshot.map { it.artifactId } == approvedArtifactIds
                }?.toList()
        }

    /** A stale receipt must not consume a restored or edited snapshot with the same artifact id. */
    fun consumeStaged(accepted: List<StagedAttachmentCandidate>) =
        synchronized(stagedLock) {
            entries = entries.filterNot { it in accepted }
            revision += 1
        }

    enum class Admission { ADDED, STALE_SESSION, FULL, DUPLICATE }
}
