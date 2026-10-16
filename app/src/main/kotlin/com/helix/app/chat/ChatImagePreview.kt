package com.helix.app.chat

import com.helix.core.model.VisionLimits
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Display references never carry a path or a SAF URI supplied by the UI. */
data class ChatImageRef(
    val sessionId: String,
    val messageId: String?,
    val attachmentId: String,
)

data class ChatImageUi(
    val reference: ChatImageRef,
    val fileName: String,
)

/** Local reads only. Message bindings authorize forked snapshots as well as original messages. */
internal class ChatImagePreview(
    private val storage: HelixStorage,
    private val staging: AttachmentStagingSupport,
) {
    @Suppress("ReturnCount") // Reject each missing identity, binding or non-image before opening a file.
    fun history(reference: ChatImageRef): ByteArray? {
        val messageId = reference.messageId ?: return null
        if (storage.messages.resolve(messageId).sessionId != reference.sessionId) return null
        val binding =
            storage.messageAttachments
                .listByMessage(messageId)
                .singleOrNull { it.artifactId == reference.attachmentId } ?: return null
        val artifact = storage.artifacts.resolve(binding.artifactId)
        if (artifact.mediaType !in VisionLimits.NORMALIZED_MEDIA_TYPES) return null
        val file = staging.resolveWorkspacePath(FileScopePath.fromModelReference(artifact.relativePath))
        return verified(file, binding.boundSha256)
    }

    fun staged(entry: StagedAttachmentEntry): ByteArray? {
        if (entry.normalizedArtifactId == null && entry.imageSendError == null) return null
        return verified(entry.normalizedFile ?: entry.file, entry.normalizedSha256 ?: entry.boundSha256)
    }

    fun draft(entry: DraftAttachment): ByteArray? =
        if (entry.isImage) staging.openPreviewSource(entry.uri).use(::boundedImageBytes) else null

    private fun verified(
        file: Path,
        hash: String,
    ): ByteArray? {
        val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use(::boundedImageBytes) ?: return null
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return bytes.takeIf { actual == hash }
    }
}

internal fun messageImages(
    storage: HelixStorage,
    sessionId: String,
    messageId: String,
): List<ChatImageUi> =
    storage.messageAttachments.listByMessage(messageId).mapNotNull { binding ->
        val artifact = storage.artifacts.resolve(binding.artifactId)
        if (artifact.mediaType in VisionLimits.NORMALIZED_MEDIA_TYPES) {
            ChatImageUi(
                ChatImageRef(sessionId, messageId, binding.artifactId),
                artifact.relativePath.substringAfterLast('/'),
            )
        } else {
            null
        }
    }

/** Never allocate from untrusted metadata or read an unbounded content-provider stream. */
internal fun boundedImageBytes(input: InputStream): ByteArray? {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return result.toByteArray().takeIf { it.isNotEmpty() }
        if (count == 0 || result.size().toLong() + count > VisionLimits.MAX_INPUT_BYTES) return null
        result.write(buffer, 0, count)
    }
}
