package com.helix.app.chat

import com.helix.core.model.VisionLimits
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath

/** Restore only persisted, hash-bound attachments; normal send performs the full egress gates again. */
internal class MessageRevisionSource(
    private val storage: HelixStorage,
    private val staging: AttachmentStagingSupport,
) {
    fun attachments(
        sessionId: String,
        messageId: String,
    ): List<StagedAttachmentEntry> =
        storage.messageAttachments.listByMessage(messageId).map { binding ->
            val artifact = storage.artifacts.resolve(binding.artifactId)
            val path = FileScopePath.fromModelReference(artifact.relativePath)
            val file = staging.resolveWorkspacePath(path)
            val image = artifact.mediaType in VisionLimits.NORMALIZED_MEDIA_TYPES
            StagedAttachmentEntry(
                sessionId,
                artifact.id,
                path.name,
                artifact.size,
                binding.boundSha256,
                artifact.relativePath,
                file,
                normalizedArtifactId = if (image) artifact.id else null,
                normalizedSha256 = if (image) binding.boundSha256 else null,
                normalizedFile = if (image) file else null,
                normalizedMediaType = if (image) artifact.mediaType else null,
            )
        }

    fun text(
        messageId: String,
        attachmentCount: Int,
    ): String {
        val row = storage.messages.resolve(messageId)
        val body = storage.messages.readContentBounded(row, 8 * 1024 * 1024).orEmpty()
        // Legacy messages store the authored prefix and generated attachment blocks together.
        // Only split an unambiguous generated first-block marker; never guess at corrupt content.
        return AttachmentContext.authoredPrefix(body, attachmentCount)
    }
}
