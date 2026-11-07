package com.helix.app.infrastructure.chat

import com.helix.app.R
import com.helix.core.model.ImageReference
import com.helix.core.model.attachment.AttachmentSendDecision
import com.helix.core.model.attachment.SnapshotKind
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.feature.conversation.application.DraftAttachment
import com.helix.feature.conversation.application.attachment.AttachmentPreparation
import com.helix.feature.conversation.application.attachment.AttachmentRetryCheck
import com.helix.feature.conversation.application.attachment.AttachmentSourceMetadata
import com.helix.feature.conversation.application.attachment.FeatureAttachmentPort
import com.helix.feature.conversation.application.attachment.StagedAttachmentCandidate
import com.helix.feature.conversation.presentation.ChatImageRef
import com.helix.feature.files.AttachmentSendGate
import com.helix.feature.files.ImportRefusal
import com.helix.feature.files.ImportStatus
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafSourceMetadata
import com.helix.feature.files.StagedAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import com.helix.feature.conversation.R as ConversationR

/** Owns attachment files and registry access. Composer membership and send decisions stay in the feature. */
@Suppress("TooManyFunctions") // Implements the single attachment boundary and its storage conversions.
class StorageAttachmentAdapter(
    private val storage: HelixStorage,
    private val staging: AttachmentStagingSupport,
    idGenerator: () -> String,
    private val strings: (Int, Array<out Any>) -> String,
) : FeatureAttachmentPort {
    private val processor = StagedAttachmentProcessor(storage, staging, idGenerator, strings)
    private val previews = ChatImagePreview(storage, staging)
    private val retry = ChatAttachmentRetry(storage, staging)
    private val imageReferences = ImageReferenceVerifier(storage, staging)

    override val workspaceScopeId: String get() = staging.workspaceScopeId

    override fun sourceMetadata(uri: String): AttachmentSourceMetadata =
        staging.sourceMetadata(uri).let { AttachmentSourceMetadata(it.sizeBytes, it.mimeType, it.displayName) }

    override suspend fun importAttachment(
        uri: String,
        reported: AttachmentSourceMetadata,
        sessionId: String,
    ): AttachmentPreparation {
        val context = currentCoroutineContext()
        context.ensureActive()
        val result =
            staging.importer.importAttachment(
                workspaceScopeId = workspaceScopeId,
                sourceUri = uri,
                reported = SafSourceMetadata(reported.sizeBytes, reported.mimeType, reported.displayName),
                cancel = SafCancelToken { !context.isActive },
                sink = null,
                sessionId = sessionId,
            )
        val prepared =
            when (result.status) {
                ImportStatus.CANCELLED -> {
                    AttachmentPreparation.Rejected(str(R.string.chat_blocked_import_cancelled))
                }

                ImportStatus.REFUSED -> {
                    AttachmentPreparation.Rejected(importRefusalReason(result.refusal))
                }

                ImportStatus.COMPLETED -> {
                    when (val prepared = processor.prepare(result, sessionId)) {
                        is StagedAttachmentProcessor.Preparation.Ready -> {
                            AttachmentPreparation.Ready(
                                prepared.entry.candidate(),
                            )
                        }

                        is StagedAttachmentProcessor.Preparation.Rejected -> {
                            AttachmentPreparation.Rejected(prepared.reason)
                        }
                    }
                }
            }
        // A completed durable import may remain inert, but cancellation never stages it in the live composer.
        context.ensureActive()
        return prepared
    }

    override fun restore(
        artifactId: String,
        sessionId: String,
    ): StagedAttachmentCandidate? = processor.restoreEntry(artifactId, sessionId)?.candidate()

    override fun evaluate(
        staged: List<StagedAttachmentCandidate>,
        credentialScan: (String) -> String?,
    ): AttachmentSendDecision {
        val resolved = ArrayList<StagedAttachment>(staged.size)
        for (candidate in staged) {
            val entry =
                resolve(candidate)
                    ?: return AttachmentSendDecision.SnapshotBroken(candidate.fileName, SnapshotKind.MISSING)
            resolved += entry.gateInput()
        }
        return AttachmentSendGate.evaluate(resolved, credentialScan)
    }

    override suspend fun retry(
        sessionId: String,
        turnId: String,
        credentialScan: (String) -> String?,
    ): AttachmentRetryCheck =
        when (val check = retry.retryStagedFor(sessionId, turnId)) {
            ChatAttachmentRetry.RetryStagedCheck.None -> {
                AttachmentRetryCheck.None
            }

            ChatAttachmentRetry.RetryStagedCheck.Unavailable -> {
                AttachmentRetryCheck.Unavailable
            }

            is ChatAttachmentRetry.RetryStagedCheck.Staged -> {
                AttachmentRetryCheck.Verified(AttachmentSendGate.evaluate(check.attachments, credentialScan))
            }
        }

    override fun historyPreview(reference: ChatImageRef): ByteArray? = previews.history(reference)

    override fun draftPreview(entry: DraftAttachment): ByteArray? = previews.draft(entry)

    override fun stagedPreview(entry: StagedAttachmentCandidate): ByteArray? = resolve(entry)?.let(previews::staged)

    override suspend fun imageReferencesFor(messageId: String): List<ImageReference> =
        imageReferences.imageReferencesFor(messageId)

    /** Resolve only the captured identities. Never restore/normalize here, including after a failed verification. */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
    private fun resolve(candidate: StagedAttachmentCandidate): StagedAttachmentEntry? =
        try {
            val raw = storage.artifacts.resolve(candidate.artifactId)
            val rawPath = FileScopePath.fromModelReference(raw.relativePath)
            val identityMatches =
                raw.sessionId == candidate.sessionId && raw.sha256 == candidate.boundSha256 &&
                    raw.size == candidate.sizeBytes
            val locationMatches = rawPath.scopeId == workspaceScopeId && rawPath.relativePath == candidate.relativePath
            if (!identityMatches || !locationMatches) {
                null
            } else {
                resolveNormalized(candidate, rawPath)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private fun resolveNormalized(
        candidate: StagedAttachmentCandidate,
        rawPath: FileScopePath,
    ): StagedAttachmentEntry? {
        val normalized = candidate.normalizedArtifactId?.let(storage.artifacts::resolve)
        val normalizedPath = normalized?.let { FileScopePath.fromModelReference(it.relativePath) }
        if (normalized != null) {
            val identityMatches =
                normalized.id != candidate.artifactId && normalized.sessionId == candidate.sessionId &&
                    normalized.sha256 == candidate.normalizedSha256
            val formatAndScopeMatch =
                normalized.mediaType == candidate.normalizedMediaType &&
                    normalizedPath?.scopeId == workspaceScopeId
            if (!identityMatches || !formatAndScopeMatch) return null
        }
        return StagedAttachmentEntry(
            sessionId = candidate.sessionId,
            artifactId = candidate.artifactId,
            fileName = candidate.fileName,
            sizeBytes = candidate.sizeBytes,
            boundSha256 = candidate.boundSha256,
            relativePath = candidate.relativePath,
            file = staging.resolveWorkspacePath(rawPath),
            normalizedArtifactId = candidate.normalizedArtifactId,
            normalizedSha256 = candidate.normalizedSha256,
            normalizedFile = normalizedPath?.let(staging.resolveWorkspacePath),
            normalizedWidth = candidate.normalizedWidth,
            normalizedHeight = candidate.normalizedHeight,
            normalizedMediaType = candidate.normalizedMediaType,
            imageSendError = candidate.imageSendError,
        )
    }

    private fun StagedAttachmentEntry.candidate() =
        StagedAttachmentCandidate(
            sessionId = sessionId,
            artifactId = artifactId,
            fileName = fileName,
            sizeBytes = sizeBytes,
            boundSha256 = boundSha256,
            relativePath = relativePath,
            normalizedArtifactId = normalizedArtifactId,
            normalizedSha256 = normalizedSha256,
            normalizedWidth = normalizedWidth,
            normalizedHeight = normalizedHeight,
            normalizedMediaType = normalizedMediaType,
            imageSendError = imageSendError,
        )

    private fun StagedAttachmentEntry.gateInput() =
        StagedAttachment(
            fileName = fileName,
            boundSha256 = boundSha256,
            file = file,
            normalizedFile = normalizedFile,
            normalizedSha256 = normalizedSha256,
            mediaType = normalizedMediaType,
            normalizedWidth = normalizedWidth,
            normalizedHeight = normalizedHeight,
        )

    private fun importRefusalReason(refusal: ImportRefusal?): String =
        str(
            when (refusal) {
                ImportRefusal.REPORTED_SIZE_EXCEEDS_LIMIT, ImportRefusal.STREAM_LIMIT_EXCEEDED -> {
                    R.string.chat_import_size_exceeded
                }

                ImportRefusal.QUOTA_EXCEEDED -> {
                    R.string.chat_import_quota_exceeded
                }

                ImportRefusal.SOURCE_UNOPENABLE -> {
                    R.string.chat_import_source_unopenable
                }

                ImportRefusal.STREAM_SIZE_MISMATCH -> {
                    R.string.chat_import_size_mismatch
                }

                ImportRefusal.DESTINATION_EXISTS -> {
                    R.string.chat_import_destination_exists
                }

                ImportRefusal.SCOPE_UNAVAILABLE, ImportRefusal.INVALID_TARGET -> {
                    R.string.chat_import_scope_unavailable
                }

                ImportRefusal.IO_FAILURE, null -> {
                    ConversationR.string.chat_import_io_failure
                }
            },
        )

    private fun str(resId: Int): String = strings(resId, emptyArray())
}
