package com.helix.app.chat

import com.helix.feature.files.AttachmentClassifier

/** Owns transient input facts only; Room receipts and Turn execution stay authoritative elsewhere. */
internal class ComposerInputState {
    val drafts = ChatDraftStore()
    private val stagedLock = Any()

    @Volatile private var entries: List<StagedAttachmentEntry> = emptyList()
    val staged: List<StagedAttachmentEntry> get() = entries

    @Volatile var shareText: String? = null
        private set

    fun share(text: String?) {
        shareText = text
    }

    fun clearStaged() = synchronized(stagedLock) { entries = emptyList() }

    fun removeStaged(ids: Collection<String>) =
        synchronized(stagedLock) {
            entries = entries.filterNot { it.artifactId in ids }
        }

    fun replaceStaged(
        restored: List<StagedAttachmentEntry>,
        expectedSessionId: String,
        currentSession: () -> String?,
    ): Boolean =
        synchronized(stagedLock) {
            if (currentSession() != expectedSessionId) return@synchronized false
            entries = restored.toList()
            true
        }

    fun stagedIds(expectedSessionId: String?): List<String> =
        synchronized(stagedLock) {
            entries.filter { expectedSessionId == null || it.sessionId == expectedSessionId }.map { it.artifactId }
        }

    fun admit(
        entry: StagedAttachmentEntry,
        currentSession: () -> String?,
    ): Admission =
        synchronized(stagedLock) {
            when {
                currentSession() != entry.sessionId -> {
                    Admission.STALE_SESSION
                }

                entries.size >= AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE -> {
                    Admission.FULL
                }

                else -> {
                    entries = entries + entry
                    Admission.ADDED
                }
            }
        }

    enum class Admission { ADDED, STALE_SESSION, FULL }
}
