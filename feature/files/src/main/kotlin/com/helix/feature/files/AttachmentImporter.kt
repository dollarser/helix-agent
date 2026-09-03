package com.helix.feature.files

import com.helix.core.model.AttachmentClassification
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import java.util.UUID

/**
 * Imports one SAF document as a CHAT ATTACHMENT (HXA-049, ADR-0014). A thin orchestration over
 * [SafImportPipeline]: it pins the file into a per-attachment private sub-path under the 10 MiB
 * attachment cap, then classifies the durable bytes.
 *
 * Layout (ADR-0014 — a one-time private copy, no tree grant): `input/attachments/<attachment-id>/
 * <sanitized-name>`. The `<attachment-id>` is the stable handle Room persists; the
 * provider-reported display name is re-sanitized by [SafNameSanitizer] before it becomes a name.
 * The pipeline creates the bounded sub-dir at copy time, so a refused import leaves nothing on disk.
 *
 * The 10 MiB per-file attachment cap (distinct from the 256 MiB SAF-import cap) is enforced by the
 * pipeline as a hard mid-stream byte limit — a file that would exceed it is refused before publish,
 * so nothing over-cap survives on disk.
 */
class AttachmentImporter(
    private val pipeline: SafImportPipeline,
) {
    /**
     * Imports ONE picked SAF document as a chat attachment (the one-time private copy into
     * `input/attachments/<attachment-id>/<sanitized-name>`).
     *
     * This is IMPORT ONLY — the import is never a send and never reaches the model: the
     * caller (the chat service) verifies the result, stages it in memory, and only an
     * EXPLICIT send materializes it (ADR-0014 §5, HXA-049).
     *
     * @param workspaceScopeId the workspace scope id the attachment is pinned into.
     * @param sourceUri the SAF `content://` uri of the picked document (never a real path).
     * @param reported the provider-reported source facts (display name, declared size).
     * @param cancel the caller's cancel token (a cancelled import is surfaced, not an error).
     * @param sink the optional artifact-registration sink; when null the caller registers
     *   the artifact itself after staging checks (the chat service path).
     * @param sessionId the owning session id (passed through to [sink] when set).
     * @return the [AttachmentImportResult]; a REFUSED or CANCELLED import leaves NO file
     *   behind (the pipeline refuses before publish) — the caller can surface the refusal
     *   without any cleanup.
     */
    fun importAttachment(
        workspaceScopeId: String,
        sourceUri: String,
        reported: SafSourceMetadata,
        cancel: SafCancelToken,
        sink: WorkspaceArtifactStore.ArtifactSink?,
        sessionId: String?,
    ): AttachmentImportResult {
        val attachmentId = "att_" + UUID.randomUUID().toString().replace("-", "")
        val name = SafNameSanitizer.sanitize(reported.displayName, "attachment")
        val relativePath = WorkspaceLayout.INPUT + "/attachments/" + attachmentId + "/" + name
        val outcome =
            pipeline.importDocument(
                workspaceScopeId = workspaceScopeId,
                sourceUri = sourceUri,
                reported = reported,
                targetNameOverride = null,
                cancel = cancel,
                sink = sink,
                sessionId = sessionId,
                targetRelativePath = relativePath,
                maxImportBytesOverride = AttachmentClassifier.MAX_ATTACHMENT_BYTES,
            )
        return AttachmentImportResult.from(outcome, attachmentId, name)
    }
}

/**
 * The outcome of a chat-attachment import. [status] mirrors [ImportStatus]; on
 * [ImportStatus.COMPLETED] the file is durable under `input/attachments/<attachmentId>/` and
 * [classification] is set. No real path appears in this type: [modelRef] is the model-safe
 * `scope:<scopeId>:input/attachments/<attachmentId>/<name>` reference only.
 */
@Suppress("LongParameterList") // flat outcome record: the fields are heterogeneous
data class AttachmentImportResult(
    val status: ImportStatus,
    val refusal: ImportRefusal? = null,
    val detail: String? = null,
    val attachmentId: String? = null,
    val artifactId: String? = null,
    val modelRef: String? = null,
    val fileName: String? = null,
    val sizeBytes: Long = -1L,
    val sha256: String? = null,
    val mimeType: String? = null,
    val classification: AttachmentClassification? = null,
) {
    companion object {
        /**
         * Maps a pipeline [SafImportOutcome] onto an attachment result. Only a [ImportStatus.COMPLETED]
         * import carries a classification — a refused/cancelled import has no durable bytes to
         * classify and [classification] stays null.
         */
        internal fun from(
            outcome: SafImportOutcome,
            attachmentId: String,
            name: String,
        ): AttachmentImportResult =
            if (outcome.status == ImportStatus.COMPLETED) {
                // Re-derive the classification from the byte-derived fields the pipeline already
                // captured. The classifier reads only the encoding + MIME, so the sample CRC and
                // truncation flag are irrelevant here and set to neutral values.
                val probe =
                    ContentProbe.Result(
                        mimeType = outcome.mimeType.orEmpty(),
                        encoding = outcome.encoding ?: ContentProbe.Encoding.BINARY,
                        isText = outcome.isText,
                        sizeBytes = outcome.sizeBytes,
                        sampleCrc32 = 0L,
                        truncated = false,
                    )
                AttachmentImportResult(
                    status = outcome.status,
                    refusal = outcome.refusal,
                    detail = outcome.detail,
                    attachmentId = attachmentId,
                    artifactId = outcome.artifactId,
                    modelRef = outcome.targetModelRef,
                    fileName = outcome.targetName,
                    sizeBytes = outcome.sizeBytes,
                    sha256 = outcome.sha256,
                    mimeType = outcome.mimeType,
                    classification = AttachmentClassifier.classify(probe, name),
                )
            } else {
                AttachmentImportResult(
                    status = outcome.status,
                    refusal = outcome.refusal,
                    detail = outcome.detail,
                    attachmentId = attachmentId,
                    fileName = name,
                    sizeBytes = outcome.sizeBytes,
                )
            }
    }
}
