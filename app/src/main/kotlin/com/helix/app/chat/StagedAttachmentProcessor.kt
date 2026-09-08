package com.helix.app.chat

import com.helix.app.R
import com.helix.core.model.AttachmentClassification
import com.helix.core.model.VisionLimits
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.feature.files.AttachmentImportResult
import com.helix.feature.files.ImageNormalizer
import com.helix.feature.files.NormalizationCode
import com.helix.feature.files.NormalizationOutcome
import java.nio.file.Files
import java.nio.file.Path

/** Prepares durable attachment facts; never admits them to a live session or mutates UI state. */
internal class StagedAttachmentProcessor(
    private val storage: HelixStorage,
    private val attachmentStaging: AttachmentStagingSupport,
    private val idGenerator: () -> String,
    private val strings: (Int, Array<out Any>) -> String,
) {
    /**
     * Completes staging for a COMPLETED import (fail closed at every step): the file must
     * classify as a first-batch UTF-8 text attachment (unsupported types are surfaced and
     * NOT staged), its snapshot must be complete, and the artifact row must register
     * (`message_attachments.artifactId` is an FK to `artifacts`; the register re-verifies
     * the durable bytes — hash and size). The resolved real path stays service-internal.
     */
    @Suppress(
        "ReturnCount",
        "SwallowedException",
        "TooGenericExceptionCaught",
        "LongMethod",
        "CyclomaticComplexMethod",
    ) // one fail-closed return per staging step; the HXA-055 image branch adds its closed failure ladder
    fun prepare(
        result: AttachmentImportResult,
        sessionId: String,
    ): Preparation {
        val fileName = result.fileName.orEmpty()
        val classification = result.classification
        if (classification !is AttachmentClassification.TextAttachment &&
            classification !is AttachmentClassification.ImageAttachment
        ) {
            // The classifier re-derived this from the durable bytes; unsupported types are
            // never parsed/decoded/rendered — the user is told and the file is not staged.
            // Classification necessarily happens after the one-time private copy so it can
            // trust the bytes rather than the provider label; discard that unregistered copy
            // now so every unsupported attempt leaves neither an Artifact nor an orphan payload.
            discardUnsupportedImport(result.modelRef)
            return Preparation.Rejected(str(R.string.chat_blocked_unsupported_type, fileName))
        }
        val sha = result.sha256
        if (sha == null || result.sizeBytes < 0) {
            return Preparation.Rejected(str(R.string.chat_blocked_snapshot_incomplete))
        }
        val scopePath =
            try {
                FileScopePath.fromModelReference(result.modelRef.orEmpty())
            } catch (e: IllegalArgumentException) {
                return Preparation.Rejected(str(R.string.chat_blocked_path_check_failed))
            }
        val realPath =
            try {
                attachmentStaging.resolveWorkspacePath(scopePath)
            } catch (e: RuntimeException) {
                // The scope vanished or the path escaped containment: fail closed. The
                // exception is not logged — it can carry a real path, which never may be.
                return Preparation.Rejected(str(R.string.chat_blocked_workspace_path_unavailable))
            }
        val artifactId =
            try {
                storage.artifacts
                    .register(
                        id = "art_" + idGenerator(),
                        sessionId = sessionId,
                        relativePath = scopePath.relativePath,
                        mediaType =
                            when (classification) {
                                is AttachmentClassification.ImageAttachment -> classification.mediaType
                                else -> result.mimeType ?: "text/plain"
                            },
                        size = result.sizeBytes,
                        sha256 = sha,
                        file = realPath.toFile(),
                    ).id
            } catch (e: IllegalArgumentException) {
                // A failed registration means the durable file no longer verifies against
                // its snapshot — do not leave a reference the user could never send.
                deleteQuietly(realPath)
                return Preparation.Rejected(str(R.string.chat_blocked_register_failed))
            }
        // HXA-055 (ADR-0014 §4): a staged image is normalized ON-DEVICE right here —
        // decode within VisionLimits, manual EXIF orientation, re-encode (the EXIF strip),
        // and the size-budget ladder — so the send path only ever moves verified, bounded
        // bytes. A normalization failure does NOT drop the attachment: the raw artifact stays
        // local (save/preview possible) and the entry carries an actionable, user-visible
        // send error (fail closed — never a raw-base64 fallback).
        val normalized =
            if (classification is AttachmentClassification.ImageAttachment) {
                normalizeStagedImage(realPath, scopePath, sessionId, classification.mediaType)
            } else {
                null
            }
        return Preparation.Ready(
            StagedAttachmentEntry(
                sessionId = sessionId,
                artifactId = artifactId,
                fileName = fileName,
                sizeBytes = result.sizeBytes,
                boundSha256 = sha,
                relativePath = scopePath.relativePath,
                file = realPath,
                normalizedArtifactId = normalized?.id,
                normalizedSha256 = normalized?.sha256,
                normalizedFile = normalized?.file,
                normalizedWidth = normalized?.width ?: 0,
                normalizedHeight = normalized?.height ?: 0,
                normalizedMediaType = normalized?.mediaType,
                imageSendError = normalized?.failureReason,
            ),
        )
    }

    /** The registered facts of one successfully normalized staged image (all nulls + a reason on failure). */
    private class NormalizedStagedImage(
        val id: String?,
        val sha256: String?,
        val file: Path?,
        val width: Int,
        val height: Int,
        val mediaType: String?,
        val failureReason: String?,
    )

    /**
     * Normalizes one staged image and registers the result as a SECOND, app-private artifact
     * in the same staging directory (`normalized.<ext>`). The raw artifact stays registered —
     * it is the local save/preview source; only the normalized artifact is ever sendable.
     */
    @Suppress(
        "ReturnCount",
        "SwallowedException",
        "TooGenericExceptionCaught",
    ) // every failure step maps to the closed NormalizationCode path
    private fun normalizeStagedImage(
        rawFile: Path,
        scopePath: FileScopePath,
        sessionId: String,
        rawMediaType: String,
    ): NormalizedStagedImage {
        val stagingDir = rawFile.parent ?: return failedStagedNormalization()
        val outcome =
            try {
                ImageNormalizer.normalize(rawFile, rawMediaType, stagingDir)
            } catch (e: Exception) {
                // A crash in the decode path (not a caught OOM) is the same closed outcome:
                // fail the normalization, keep the raw file, never crash the app.
                NormalizationOutcome.Failed(NormalizationCode.DECODE_FAILED, "unexpected")
            }
        val ok = outcome as? NormalizationOutcome.Ok ?: return failedStagedNormalization()
        val normalizedPath = ok.image.file
        if (!Files.exists(normalizedPath) || !Files.isRegularFile(normalizedPath)) {
            return failedStagedNormalization()
        }
        // The normalizer wrote `normalized.<ext>` into the RAW file's staging directory —
        // derive the scope-relative path from the raw one (same dir, fixed file name).
        val dirRel = scopePath.relativePath.substringBeforeLast('/')
        val ext = normalizedPath.fileName?.toString()?.substringAfterLast('.', missingDelimiterValue = "") ?: ""
        val normalizedRelative =
            runCatching {
                FileScopePath(scopePath.scopeId, "$dirRel/normalized.$ext")
            }.getOrNull()
        if (normalizedRelative == null || ext.isEmpty()) {
            deleteQuietly(normalizedPath)
            return failedStagedNormalization()
        }
        // Containment: the registered path must resolve to EXACTLY the file the normalizer
        // wrote — never a stray file the scope would also accept (fail closed).
        val resolved =
            runCatching { attachmentStaging.resolveWorkspacePath(normalizedRelative) }.getOrNull()
        if (resolved?.toFile()?.canonicalFile != normalizedPath.toFile().canonicalFile) {
            deleteQuietly(normalizedPath)
            return failedStagedNormalization()
        }
        val id =
            try {
                storage.artifacts
                    .register(
                        id = "art_" + idGenerator(),
                        sessionId = sessionId,
                        relativePath = normalizedRelative.relativePath,
                        mediaType = ok.image.mediaType,
                        size = ok.image.sizeBytes,
                        sha256 = ok.image.sha256,
                        file = normalizedPath.toFile(),
                    ).id
            } catch (e: IllegalArgumentException) {
                // The register re-verifies the bytes; a mismatch deletes nothing (the row is
                // absent) and the normalization is treated as failed (fail closed).
                return failedStagedNormalization()
            }
        return NormalizedStagedImage(
            id,
            ok.image.sha256,
            normalizedPath,
            ok.image.width,
            ok.image.height,
            ok.image.mediaType,
            null,
        )
    }

    /** The closed, fail-closed staging outcome of a failed image normalization (HXA-055). */
    private fun failedStagedNormalization() =
        NormalizedStagedImage(null, null, null, 0, 0, null, imageNormalizationBlockText())

    /** The fixed, user-visible (Chinese) send block for a failed image normalization (HXA-055). */
    private fun imageNormalizationBlockText(): String =
        str(R.string.chat_capability_image_normalization_failed, VisionLimits.MAX_EDGE_PX)

    /** Best-effort delete of a file whose staging failed (an unreferenced orphan otherwise). */
    private fun deleteQuietly(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }

    /** Removes an unsupported import's unregistered payload and its now-empty attachment-id dir. */
    private fun discardUnsupportedImport(modelRef: String?) {
        val scopePath =
            runCatching { FileScopePath.fromModelReference(modelRef.orEmpty()) }
                .getOrNull() ?: return
        val path =
            runCatching { attachmentStaging.resolveWorkspacePath(scopePath) }
                .getOrNull() ?: return
        deleteQuietly(path)
        // The per-import directory is unique and contains only this payload before staging.
        // deleteIfExists fails harmlessly if deletion above failed or an unexpected entry exists.
        runCatching { Files.deleteIfExists(path.parent) }
    }

    private fun str(
        resId: Int,
        vararg args: Any,
    ): String = strings(resId, args)

    sealed interface Preparation {
        data class Ready(
            val entry: StagedAttachmentEntry,
        ) : Preparation

        data class Rejected(
            val reason: String,
        ) : Preparation
    }
}
