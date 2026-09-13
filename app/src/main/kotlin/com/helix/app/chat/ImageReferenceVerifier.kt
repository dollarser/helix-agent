package com.helix.app.chat

import com.helix.core.model.ArtifactRef
import com.helix.core.model.ImageReference
import com.helix.core.model.VisionLimits
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import java.nio.file.Files

/**
 * The HXA-055 image-binding re-verification the single context-construction system
 * ([ChatRequestAssembler], HX2-03) performs before re-carrying persisted image references:
 * each image binding is re-verified against its artifact and the request-wide base64 budget is
 * enforced; any miss fails the turn closed — no silent drop, no raw fallback.
 */
internal class ImageReferenceVerifier(
    private val storage: HelixStorage,
    private val attachmentStaging: AttachmentStagingSupport,
) {
    /**
     * The verified [ImageReference]s bound to one persisted USER message (HXA-055): every
     * binding whose artifact is an image (closed media type) is re-verified — artifact present,
     * bytes hash to the bound SHA-256, magic agrees with the registered type — and the
     * request-wide base64 budget is enforced. Any miss throws [IllegalArgumentException] and
     * the turn fails closed; there is no silent drop and no raw fallback.
     */
    suspend fun imageReferencesFor(messageId: String): List<ImageReference> {
        val bindings = storage.messageAttachments.listByMessage(messageId)
        if (bindings.isEmpty()) return emptyList()
        var totalBase64 = 0L
        val images = ArrayList<ImageReference>(bindings.size)
        for (binding in bindings) {
            val facts = verifiedImageBinding(binding) ?: continue // a text binding is not an image
            totalBase64 += facts.base64Bytes
            images += facts.reference
        }
        require(totalBase64 <= VisionLimits.MAX_TOTAL_BASE64_PER_REQUEST_BYTES) {
            "the session's image data exceeds the per-request budget — start a new session to send more images"
        }
        return images
    }

    /**
     * One persisted binding re-verified against its artifact (HXA-055): [null] when the binding
     * is NOT an image (a text attachment), a verified [ImageReference] + its base64 size when it
     * is, and an [IllegalArgumentException] (the turn fails closed) when the artifact changed or
     * vanished — the ADR's re-verify-before-send/retry/restore rule.
     */
    private data class ImageBindingFacts(
        val reference: ImageReference,
        val base64Bytes: Long,
    )

    @Suppress("ThrowsCount") // one throw per closed re-verification failure (existence / path / hash / magic)
    private suspend fun verifiedImageBinding(
        binding: com.helix.core.storage.entity.MessageAttachmentEntity,
    ): ImageBindingFacts? {
        val artifact =
            runCatching { storage.artifacts.resolve(binding.artifactId) }
                .getOrNull()
                ?: throw IllegalArgumentException("bound image artifact no longer exists — re-verify the session")
        if (artifact.mediaType !in VisionLimits.NORMALIZED_MEDIA_TYPES) return null // text binding
        require(artifact.size <= VisionLimits.MAX_NORMALIZED_RAW_BYTES) {
            "bound image exceeds the per-image wire budget"
        }
        val scopePath =
            runCatching { FileScopePath(attachmentStaging.workspaceScopeId, artifact.relativePath) }
                .getOrNull()
                ?: throw IllegalArgumentException("bound image artifact path is invalid — re-verify the session")
        val file =
            runCatching { attachmentStaging.resolveWorkspacePath(scopePath) }
                .getOrNull()
                ?: throw IllegalArgumentException("bound image artifact path escapes the workspace")
        require(Files.isRegularFile(file)) {
            "bound image artifact is missing — the message can no longer be restored"
        }
        val actualHash =
            try {
                AtomicFileWriter.sha256Hex(file)
            } catch (e: java.io.IOException) {
                throw IllegalArgumentException("bound image artifact is unreadable — re-verify the session", e)
            }
        require(actualHash == binding.boundSha256) {
            "bound image hash no longer matches the message binding"
        }
        val bytes =
            try {
                Files.readAllBytes(file)
            } catch (e: java.io.IOException) {
                throw IllegalArgumentException("bound image artifact is unreadable — re-verify the session", e)
            }
        val magic = ContentProbe.probeBytes(bytes, bytes.size.toLong()).mimeType
        require(magic == artifact.mediaType) { "bound image bytes do not match their registered type" }
        return ImageBindingFacts(
            reference = ImageReference(ArtifactRef(artifact.id), artifact.mediaType),
            base64Bytes = ((bytes.size + 2L) / 3L) * 4L,
        )
    }
}
