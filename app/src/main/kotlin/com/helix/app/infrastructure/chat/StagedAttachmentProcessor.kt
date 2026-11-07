package com.helix.app.infrastructure.chat

import android.graphics.BitmapFactory
import com.helix.app.R
import com.helix.core.model.AttachmentClassification
import com.helix.core.model.VisionLimits
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import com.helix.feature.files.AttachmentImportResult
import com.helix.feature.files.ImageNormalizer
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
                        relativePath = scopePath.toModelReference(),
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
                normalizeStagedImage(realPath, scopePath, sessionId, artifactId, sha, classification.mediaType)
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

    /** Reconstructs and re-verifies a staged snapshot from persisted artifact facts. */
    @Suppress("ReturnCount")
    fun restoreEntry(
        artifactId: String,
        sessionId: String,
    ): StagedAttachmentEntry? {
        val artifact =
            try {
                storage.artifacts.resolve(artifactId)
            } catch (_: Exception) {
                return null
            }
        if (artifact.sessionId != sessionId) return null
        val scopePath =
            try {
                FileScopePath.fromModelReference(artifact.relativePath)
            } catch (_: Exception) {
                return null
            }
        val realPath =
            try {
                attachmentStaging.resolveWorkspacePath(scopePath)
            } catch (_: Exception) {
                return null
            }
        val file = realPath.toFile()
        if (!file.isFile || file.length() != artifact.size ||
            com.helix.core.storage.content.FileContentStore
                .sha256Hex(file) != artifact.sha256
        ) {
            return null
        }
        val isImage = artifact.mediaType.startsWith("image/")
        val normalized =
            if (isImage) {
                normalizeStagedImage(realPath, scopePath, sessionId, artifactId, artifact.sha256, artifact.mediaType)
            } else {
                null
            }
        return StagedAttachmentEntry(
            sessionId = sessionId,
            artifactId = artifactId,
            fileName = realPath.fileName.toString(),
            sizeBytes = artifact.size,
            boundSha256 = artifact.sha256,
            relativePath = scopePath.relativePath,
            file = realPath,
            normalizedArtifactId = normalized?.id,
            normalizedSha256 = normalized?.sha256,
            normalizedFile = normalized?.file,
            normalizedWidth = normalized?.width ?: 0,
            normalizedHeight = normalized?.height ?: 0,
            normalizedMediaType = normalized?.mediaType,
            imageSendError = normalized?.failureReason,
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

    /** A raw identity owns one derived registry identity; file names never prove normalization. */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun normalizeStagedImage(
        rawFile: Path,
        scopePath: FileScopePath,
        sessionId: String,
        rawArtifactId: String,
        rawHash: String,
        rawMediaType: String,
    ): NormalizedStagedImage {
        val key =
            com.helix.core.storage.content.FileContentStore.sha256Hex(
                "$sessionId\n$rawArtifactId\n$rawHash".toByteArray(Charsets.UTF_8),
            )
        val derivedId = "art_normalized_$key"
        // This root cannot collide with an imported basename: raw imports are always below
        // input/attachments/<id>/<name>, including extensionless names such as "derived".
        val directory = FileScopePath(scopePath.scopeId, "input/attachment-derived/$key")
        existingNormalized(derivedId, directory, sessionId, rawArtifactId, rawFile)?.let { return it }
        val root =
            runCatching { attachmentStaging.resolveWorkspacePath(directory) }.getOrNull()
                ?: return failedStagedNormalization()
        val outcome =
            try {
                Files.createDirectories(root)
                ImageNormalizer.normalize(rawFile, rawMediaType, root)
            } catch (_: Exception) {
                return failedStagedNormalization()
            }
        val image = (outcome as? NormalizationOutcome.Ok)?.image ?: return failedStagedNormalization()
        val relative = verifiedDerivedPath(rawFile, rawHash, image.file, root, directory)
        if (relative == null) {
            deleteQuietly(image.file)
            return failedStagedNormalization()
        }
        return try {
            storage.artifacts.register(
                derivedId,
                sessionId,
                relative.toModelReference(),
                image.mediaType,
                image.sizeBytes,
                image.sha256,
                image.file.toFile(),
            )
            NormalizedStagedImage(
                derivedId,
                image.sha256,
                image.file,
                image.width,
                image.height,
                image.mediaType,
                null,
            )
        } catch (_: Exception) {
            // Each normalizer owns a unique file. Losing registration cannot overwrite or
            // delete the winning artifact, including a winner already bound to a message.
            val winner = existingNormalized(derivedId, directory, sessionId, rawArtifactId, rawFile)
            val published = runCatching { storage.artifacts.resolve(derivedId) }.getOrNull()
            if (published != null && published.relativePath != relative.toModelReference()) {
                deleteQuietly(image.file)
            }
            winner ?: failedStagedNormalization()
        }
    }

    /** Recheck the admitted raw bytes after encoding and prove the derived path cannot overwrite them. */
    private fun verifiedDerivedPath(
        rawFile: Path,
        rawHash: String,
        imageFile: Path,
        root: Path,
        directory: FileScopePath,
    ): FileScopePath? {
        val rawStillBound =
            runCatching {
                com.helix.core.storage.content.FileContentStore
                    .sha256Hex(rawFile.toFile()) == rawHash
            }.getOrDefault(false)
        return if (!rawStillBound) {
            null
        } else {
            val relative =
                FileScopePath(
                    directory.scopeId,
                    directory.relativePath + "/" + root.relativize(imageFile).toString().replace('\\', '/'),
                )
            val resolved = runCatching { attachmentStaging.resolveWorkspacePath(relative) }.getOrNull()
            if (resolved?.toFile()?.canonicalFile != imageFile.toFile().canonicalFile ||
                imageFile.toFile().canonicalFile == rawFile.toFile().canonicalFile
            ) {
                null
            } else {
                relative
            }
        }
    }

    @Suppress("ReturnCount") // Each failed identity or byte proof closes this cached-artifact read immediately.
    private fun existingNormalized(
        id: String,
        directory: FileScopePath,
        sessionId: String,
        rawArtifactId: String,
        rawFile: Path,
    ): NormalizedStagedImage? {
        val artifact = runCatching { storage.artifacts.resolve(id) }.getOrNull() ?: return null
        val path =
            runCatching { FileScopePath.fromModelReference(artifact.relativePath) }.getOrNull()
                ?: return failedStagedNormalization()
        val identityMatches = artifact.sessionId == sessionId && artifact.id != rawArtifactId
        val locationMatches =
            path.scopeId == directory.scopeId &&
                path.relativePath.startsWith(directory.relativePath + "/")
        if (!identityMatches || !locationMatches) {
            return failedStagedNormalization()
        }
        val verified = verifyExistingNormalizedImage(artifact, path) ?: return failedStagedNormalization()
        return if (verified.file?.toFile()?.canonicalFile == rawFile.toFile().canonicalFile) {
            failedStagedNormalization()
        } else {
            verified
        }
    }

    /** Re-verifies an existing normalized snapshot before reusing it after process recovery. */
    @Suppress("ReturnCount")
    private fun verifyExistingNormalizedImage(
        artifact: com.helix.core.storage.entity.ArtifactEntity,
        normalizedRelative: FileScopePath,
    ): NormalizedStagedImage? {
        if (artifact.mediaType !in VisionLimits.NORMALIZED_MEDIA_TYPES ||
            artifact.size <= 0L ||
            artifact.size > VisionLimits.MAX_NORMALIZED_RAW_BYTES
        ) {
            return null
        }
        val file =
            runCatching { attachmentStaging.resolveWorkspacePath(normalizedRelative) }
                .getOrNull() ?: return null
        if (!Files.isRegularFile(file)) return null
        val actualSize = runCatching { Files.size(file) }.getOrNull() ?: return null
        if (actualSize != artifact.size) return null
        val actualHash =
            runCatching {
                com.helix.core.storage.content.FileContentStore
                    .sha256Hex(file.toFile())
            }.getOrNull()
        if (actualHash != artifact.sha256) return null
        val actualMediaType = runCatching { ContentProbe.probe(file).mimeType }.getOrNull()
        if (actualMediaType != artifact.mediaType) return null
        val dimensions =
            runCatching {
                BitmapFactory.Options().also { options ->
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(file.toString(), options)
                }
            }.getOrNull() ?: return null
        if (!VisionLimits.normalizedEdgeFits(dimensions.outWidth, dimensions.outHeight)) return null
        return NormalizedStagedImage(
            artifact.id,
            artifact.sha256,
            file,
            dimensions.outWidth,
            dimensions.outHeight,
            artifact.mediaType,
            null,
        )
    }

    /** The closed, fail-closed staging outcome of a failed image normalization (HXA-055). */
    private fun failedStagedNormalization() =
        NormalizedStagedImage(
            null,
            null,
            null,
            0,
            0,
            null,
            str(R.string.chat_capability_image_normalization_failed, VisionLimits.MAX_EDGE_PX),
        )

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
