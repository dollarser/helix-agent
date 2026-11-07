package com.helix.feature.conversation.application.attachment

import com.helix.core.model.ImageReference
import com.helix.core.model.attachment.AttachmentSendDecision
import com.helix.feature.conversation.application.DraftAttachment
import com.helix.feature.conversation.presentation.ChatImageRef

/** Byte import, artifact resolution and verification, without exposing files or persistence records. */
@Suppress("TooManyFunctions") // One attachment I/O boundary for staging, explicit send, retry and local preview.
interface FeatureAttachmentPort {
    val workspaceScopeId: String

    fun sourceMetadata(uri: String): AttachmentSourceMetadata

    suspend fun importAttachment(
        uri: String,
        reported: AttachmentSourceMetadata,
        sessionId: String,
    ): AttachmentPreparation

    fun restore(
        artifactId: String,
        sessionId: String,
    ): StagedAttachmentCandidate?

    /** Reopens the captured identities; this operation never normalizes or replaces a snapshot. */
    fun evaluate(
        staged: List<StagedAttachmentCandidate>,
        credentialScan: (String) -> String?,
    ): AttachmentSendDecision

    suspend fun retry(
        sessionId: String,
        turnId: String,
        credentialScan: (String) -> String?,
    ): AttachmentRetryCheck

    fun historyPreview(reference: ChatImageRef): ByteArray?

    fun draftPreview(entry: DraftAttachment): ByteArray?

    fun stagedPreview(entry: StagedAttachmentCandidate): ByteArray?

    suspend fun imageReferencesFor(messageId: String): List<ImageReference>
}

/** Provider-reported labels are untrusted; the import adapter verifies the durable bytes. */
data class AttachmentSourceMetadata(
    val sizeBytes: Long,
    val mimeType: String?,
    val displayName: String?,
)

sealed interface AttachmentPreparation {
    data class Ready(
        val entry: StagedAttachmentCandidate,
    ) : AttachmentPreparation

    data class Rejected(
        val reason: String,
    ) : AttachmentPreparation
}

sealed interface AttachmentRetryCheck {
    data object None : AttachmentRetryCheck

    data object Unavailable : AttachmentRetryCheck

    data class Verified(
        val gate: AttachmentSendDecision,
    ) : AttachmentRetryCheck
}
