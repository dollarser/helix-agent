package com.helix.app.chat

import com.helix.core.model.ModelRole
import com.helix.core.model.VisionLimits
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.feature.files.StagedAttachment

/** Resolves immutable attachment bindings for retry; never changes admission or pending UI state. */
internal class ChatAttachmentRetry(
    private val storage: HelixStorage,
    private val attachmentStaging: AttachmentStagingSupport,
) {
    sealed interface RetryStagedCheck {
        /** The retried turn has no bound attachments. */
        data object None : RetryStagedCheck

        /** The bound files, in the message's binding (ordinal/staged) order. */
        data class Staged(
            val attachments: List<StagedAttachment>,
        ) : RetryStagedCheck

        /** A bound artifact can no longer be resolved — the retry must be blocked. */
        data object Unavailable : RetryStagedCheck
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught") // ANY resolution failure = Unavailable
    suspend fun retryStagedFor(sessionId: String, turnId: String): RetryStagedCheck =
        try {
            val messageId =
                storage.messages
                    .listBySession(sessionId)
                    .firstOrNull { it.turnId == turnId && it.role == ModelRole.USER.name }
                    ?.id
            val bindings = messageId?.let { storage.messageAttachments.listByMessage(it) }
            if (bindings.isNullOrEmpty()) {
                RetryStagedCheck.None
            } else {
                RetryStagedCheck.Staged(
                    bindings.map { binding ->
                        val artifact = storage.artifacts.resolve(binding.artifactId)
                        val scopePath =
                            FileScopePath(attachmentStaging.workspaceScopeId, artifact.relativePath)
                        val file = attachmentStaging.resolveWorkspacePath(scopePath)
                        // HXA-055: an image binding points at the NORMALIZED artifact (the
                        // bytes that leave) — the retry re-verifies exactly that file, twice
                        // (as `file` and as `normalizedFile`; the raw artifact is local-only
                        // and no longer part of the binding). Dimensions are unknown at retry
                        // (not persisted) and are 0 — the gate does not need them to re-verify.
                        val isImage = artifact.mediaType in VisionLimits.NORMALIZED_MEDIA_TYPES
                        StagedAttachment(
                            fileName = scopePath.name,
                            boundSha256 = binding.boundSha256,
                            file = file,
                            normalizedFile = if (isImage) file else null,
                            normalizedSha256 = if (isImage) binding.boundSha256 else null,
                            mediaType = if (isImage) artifact.mediaType else null,
                            normalizedWidth = 0,
                            normalizedHeight = 0,
                        )
                    },
                )
            }
        } catch (_: Exception) {
            // The exception can carry a real path or a corrupt row — it is NOT logged;
            // the caller blocks the retry fail-closed.
            RetryStagedCheck.Unavailable
        }
}
