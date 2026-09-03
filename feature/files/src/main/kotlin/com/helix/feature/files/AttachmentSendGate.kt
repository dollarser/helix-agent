package com.helix.feature.files

import com.helix.core.model.AttachmentCategory
import com.helix.core.workspace.AtomicFileWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * One attachment the user has staged for a send (ADR-0014, HXA-049): its bound snapshot
 * [boundSha256] (taken at import), the on-disk [file] the caller resolved, and [fileName], the
 * sanitized display name (a source label only — never a real filesystem path).
 *
 * For an image attachment (HXA-055) the caller additionally passes the NORMALIZED artifact
 * facts — [normalizedFile], [normalizedSha256], [mediaType] — the on-device re-encode whose
 * bytes actually leave the device. All three are null for a text attachment.
 */
data class StagedAttachment(
    val fileName: String,
    val boundSha256: String,
    val file: Path,
    val normalizedFile: Path? = null,
    val normalizedSha256: String? = null,
    val mediaType: String? = null,
    val normalizedWidth: Int = 0,
    val normalizedHeight: Int = 0,
)

/**
 * The closed send-gate for staged chat attachments (ADR-0014 §2/§4/§5, HXA-049).
 *
 * Staging or importing an attachment NEVER sends anything (no auto-send — HXA-049). This gate runs
 * on the explicit send: it materializes every staged attachment against its bound snapshot and
 * fails CLOSED — the send is blocked if ANY attachment is a closed-unsupported type
 * ([AttachmentSendDecision.UnsupportedType], the ADR "UNSUPPORTED_ATTACHMENT_TYPE" block), its
 * bound snapshot no longer matches the on-disk bytes ([AttachmentSendDecision.SnapshotBroken]: a
 * tampered or missing file), or its FULL content carries a credential shape
 * ([AttachmentSendDecision.CredentialDetected] — ADR-0014 §5 「凭据类内容仍拒绝出网」). Only when
 * every attachment is a confirmed-UTF-8 first-batch text file and credential-clean does the send
 * proceed ([AttachmentSendDecision.Ready]) — and even then the content goes out only
 * through the caller's egress policy, never auto-passed (HXA-049).
 *
 * The gate is a pure function of (file, snapshot, name): it carries no UI strings and no error
 * codes, so it is co-tested next to [AttachmentMaterializer] and the app maps the outcome to its
 * user-visible block.
 */
object AttachmentSendGate {
    /**
     * Materializes every staged attachment, scans the FULL content of every materialized text
     * file with [credentialScan], and decides the send.
     *
     * [credentialScan] is the caller's credential-shape scanner, dependency-injected (this module
     * has no app dependency): it receives the full UTF-8 content of one materialized attachment —
     * the WHOLE file, not just the bounded inline view, because the FULL content reaches the
     * model through the chunked `read(offset, maxBytes)` — and returns a user-visible refusal
     * reason or null (ADR-0014 §5 「凭据类内容仍拒绝出网」). Malformed UTF-8 bytes decode to the
     * replacement character; the credential patterns are ASCII and are preserved by the
     * replacement.
     *
     * @return [AttachmentSendDecision.Ready] when all are materializable text and
     *   credential-clean (in staged order); the FIRST problem attachment otherwise —
     *   [AttachmentSendDecision.CredentialDetected],
     *   [AttachmentSendDecision.UnsupportedType] or
     *   [AttachmentSendDecision.SnapshotBroken] — the block is deterministic in staged
     *   order, so the user is always told about the first problem attachment.
     * @throws IllegalArgumentException when more than the closed per-message cap are staged (an
     *   independent guard alongside the Room binding — fail closed before any materialization).
     */
    @Suppress("ReturnCount", "SwallowedException") // one fail-closed return per first problem attachment
    fun evaluate(
        staged: List<StagedAttachment>,
        credentialScan: (String) -> String?,
    ): AttachmentSendDecision {
        require(staged.size <= AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE) {
            "a message may stage at most ${AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE} attachments"
        }
        val ready = ArrayList<AttachmentMaterialization>(staged.size)
        for (s in staged) {
            when (val materialized = AttachmentMaterializer.materialize(s.file, s.boundSha256, s.fileName)) {
                is AttachmentMaterialization.Text -> {
                    val reason =
                        try {
                            // The materializer already verified existence + the full-file hash;
                            // this second read only feeds the credential scan (bounded by the
                            // 10 MiB import cap).
                            credentialScan(Files.readAllBytes(s.file).toString(Charsets.UTF_8))
                        } catch (e: IOException) {
                            // The file vanished between materialization and the scan: fail
                            // closed exactly like missing.
                            return AttachmentSendDecision.SnapshotBroken(s.fileName, SnapshotKind.MISSING)
                        }
                    if (reason != null) {
                        return AttachmentSendDecision.CredentialDetected(s.fileName, reason)
                    }
                    ready += materialized
                }

                is AttachmentMaterialization.Image -> {
                    when (val verified = verifyNormalizedImage(materialized, s)) {
                        is ImageVerification.VerifiedImage -> {
                            ready += verified.attachment
                        }

                        is ImageVerification.BrokenImage -> {
                            return AttachmentSendDecision.SnapshotBroken(s.fileName, verified.kind)
                        }
                    }
                }

                is AttachmentMaterialization.Unsupported -> {
                    return AttachmentSendDecision.UnsupportedType(s.fileName, materialized.category)
                }

                is AttachmentMaterialization.Tampered -> {
                    return AttachmentSendDecision.SnapshotBroken(s.fileName, SnapshotKind.TAMPERED)
                }

                is AttachmentMaterialization.Unavailable -> {
                    return AttachmentSendDecision.SnapshotBroken(s.fileName, SnapshotKind.MISSING)
                }
            }
        }
        return AttachmentSendDecision.Ready(ready)
    }

    /** The closed outcome of the NORMALIZED artifact re-verification (HXA-055). */
    private sealed interface ImageVerification {
        data class VerifiedImage(
            val attachment: AttachmentMaterialization.Image,
        ) : ImageVerification

        data class BrokenImage(
            val kind: SnapshotKind,
        ) : ImageVerification
    }

    /**
     * The raw file already verified at materialization; now the NORMALIZED artifact (the bytes
     * that actually leave the device) must re-verify against its own bound snapshot — missing
     * or changed is the same fail-closed [SnapshotKind] as a broken text file. An I/O failure
     * while hashing is handled as MISSING (the file cannot prove itself).
     */
    @Suppress("SwallowedException", "ReturnCount") // the hashing I/O failure IS handled as the closed MISSING outcome
    private fun verifyNormalizedImage(
        materialized: AttachmentMaterialization.Image,
        s: StagedAttachment,
    ): ImageVerification {
        val normalized = s.normalizedFile
        val normalizedSha = s.normalizedSha256
        val normalizedType = s.mediaType
        if (normalized == null || normalizedSha == null || normalizedType == null) {
            return ImageVerification.BrokenImage(SnapshotKind.MISSING)
        }
        val actualNormalized =
            try {
                if (!Files.exists(normalized) || !Files.isRegularFile(normalized)) {
                    return ImageVerification.BrokenImage(SnapshotKind.MISSING)
                }
                AtomicFileWriter.sha256Hex(normalized)
            } catch (e: IOException) {
                return ImageVerification.BrokenImage(SnapshotKind.MISSING)
            }
        if (actualNormalized != normalizedSha) {
            return ImageVerification.BrokenImage(SnapshotKind.TAMPERED)
        }
        // Dimensions: carried from the staged normalizer facts on a live send (always > 0);
        // 0 on a retry (not persisted) — re-verification does not need them.
        return ImageVerification.VerifiedImage(
            materialized.copy(
                mediaType = normalizedType,
                sha256 = normalizedSha,
                sizeBytes = Files.size(normalized),
                width = s.normalizedWidth,
                height = s.normalizedHeight,
            ),
        )
    }
}

/** The closed outcome of the attachment send-gate. */
sealed interface AttachmentSendDecision {
    /**
     * Every staged attachment materialized (text or image) — the send may proceed (subject to
     * egress and, for images, the target provider's confirmed vision capability).
     * [attachments] is in staged order, so the caller pairs [attachments].elementAt(i) with the
     * i-th staged attachment. For an [AttachmentMaterialization.Image] the [AttachmentMaterialization.Image.sha256]
     * / [AttachmentMaterialization.Image.sizeBytes] bind the NORMALIZED artifact (the bytes that
     * leave the device), not the raw file.
     */
    data class Ready(
        val attachments: List<AttachmentMaterialization>,
    ) : AttachmentSendDecision

    /** A staged attachment is a closed-unsupported type — the send is blocked; surface [category]. */
    data class UnsupportedType(
        val fileName: String,
        val category: AttachmentCategory,
    ) : AttachmentSendDecision

    /** A staged attachment's bound snapshot no longer matches the on-disk file — fail closed; block. */
    data class SnapshotBroken(
        val fileName: String,
        val kind: SnapshotKind,
    ) : AttachmentSendDecision

    /**
     * Credential-shaped content was found in a staged attachment's FULL content (ADR-0014 §5
     * 「凭据类内容仍拒绝出网」) — a REFUSAL, exactly like a credential typed in the box:
     * block the send with [reason]; the matched content is never echoed.
     */
    data class CredentialDetected(
        val fileName: String,
        val reason: String,
    ) : AttachmentSendDecision
}

/** Why a staged attachment's snapshot no longer matched (fail closed either way). */
enum class SnapshotKind {
    /** The on-disk bytes hash to a different SHA-256 than the bound snapshot. */
    TAMPERED,

    /** The file is missing or unreadable. */
    MISSING,
}
