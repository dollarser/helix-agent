package com.helix.app.infrastructure.chat

import com.helix.core.model.AttachmentPurpose
import com.helix.core.model.VisionLimits
import com.helix.core.model.attachment.AttachmentSendDecision
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.MessageAttachmentRepository
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import com.helix.core.workspace.FileScopePath
import com.helix.feature.conversation.application.EgressDisclosure
import com.helix.feature.conversation.application.attachment.AttachmentContext
import com.helix.feature.conversation.application.attachment.AttachmentContextBlock
import com.helix.feature.conversation.application.attachment.AttachmentSendAdmission
import com.helix.feature.conversation.application.input.AcceptedInputDisclosure
import com.helix.feature.conversation.application.input.InputDeliveryKey
import com.helix.feature.files.AttachmentSendGate
import com.helix.feature.files.StagedAttachment

/** Reopens the exact admitted artifact, never normalizes an image again or touches the composer. */
internal class SessionInputAttachments(
    private val storage: HelixStorage,
    private val staging: AttachmentStagingSupport,
    private val scan: (String) -> String?,
) : AcceptedInputDisclosure {
    fun materialize(input: SessionInputRecord): Pair<String, List<MessageAttachmentRepository.Binding>> {
        val text =
            com.helix.core.model.input.KnowledgeReferences.message(
                storage.sessionInputs.readText(input),
                input.knowledgeReferences,
                input.inputId,
            )
        require(
            scan(
                text +
                    input.knowledgeReferences.joinToString("\n") {
                        it.title
                    },
            ) == null,
        ) { "INPUT_CREDENTIAL_DETECTED" }
        val ready = verified(input)
        val blocks =
            ready.attachments.mapIndexed { index, value ->
                when (value) {
                    is com.helix.core.model.attachment.AttachmentMaterialization.Text -> {
                        AttachmentContextBlock.Text(
                            value,
                            storage.artifacts.resolve(input.attachments[index].artifactId).relativePath,
                            staging.workspaceScopeId,
                        )
                    }

                    is com.helix.core.model.attachment.AttachmentMaterialization.Image -> {
                        AttachmentContextBlock.Image(
                            value.fileName,
                            value.mediaType,
                            value.sha256,
                            value.sizeBytes,
                            value.width,
                            value.height,
                        )
                    }

                    else -> {
                        error("INPUT_ATTACHMENT_UNSUPPORTED")
                    }
                }
            }
        return AttachmentContext.buildUserMessageContent(text, blocks) to
            input.attachments.map {
                MessageAttachmentRepository.Binding(it.artifactId, AttachmentPurpose.REFERENCE, it.boundSha256)
            }
    }

    override fun disclosure(
        key: InputDeliveryKey,
        target: EgressDisclosure.EgressTarget,
        strings: (Int, Array<out Any>) -> String,
    ): AttachmentSendAdmission.Outcome {
        val input =
            storage.sessionInputs.get(key.inputId)?.takeIf {
                it.sessionId == key.sessionId && it.revision == key.revision &&
                    it.state in setOf(SessionInputState.PENDING, SessionInputState.NEEDS_ATTENTION)
            } ?: return AttachmentSendAdmission.Outcome.Blocked("INPUT_REVISION_CHANGED")
        return AttachmentSendAdmission.admit(
            verified(input),
            storage.sessionInputs.readText(input),
            target,
            strings,
            storage.sessionInputs.readReference(input),
            input.knowledgeReferences,
        )
    }

    private fun verified(input: SessionInputRecord): AttachmentSendDecision.Ready {
        val artifacts = input.attachments.map { storage.artifacts.resolve(it.artifactId) }
        val snapshots =
            artifacts.mapIndexed { index, artifact ->
                val file = staging.resolveWorkspacePath(FileScopePath.fromModelReference(artifact.relativePath))
                val image = artifact.mediaType in VisionLimits.NORMALIZED_MEDIA_TYPES
                StagedAttachment(
                    fileName = file.fileName.toString(),
                    boundSha256 = input.attachments[index].boundSha256,
                    file = file,
                    normalizedFile = if (image) file else null,
                    normalizedSha256 = if (image) input.attachments[index].boundSha256 else null,
                    mediaType = if (image) artifact.mediaType else null,
                )
            }
        return AttachmentSendGate.evaluate(snapshots, scan) as? AttachmentSendDecision.Ready
            ?: error("INPUT_ATTACHMENT_CHANGED")
    }
}
