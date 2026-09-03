package com.helix.feature.files

import com.helix.core.model.AttachmentCategory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * One attachment the user has staged for a send (ADR-0014, HXA-049): its bound snapshot
 * [boundSha256] (taken at import), the on-disk [file] the caller resolved, and [fileName], the
 * sanitized display name (a source label only — never a real filesystem path).
 */
data class StagedAttachment(
    val fileName: String,
    val boundSha256: String,
    val file: Path,
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
    @Suppress("ReturnCount", "SwallowedException") // the scan I/O failure IS handled as missing
    fun evaluate(
        staged: List<StagedAttachment>,
        credentialScan: (String) -> String?,
    ): AttachmentSendDecision {
        require(staged.size <= AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE) {
            "a message may stage at most ${AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE} attachments"
        }
        val ready = ArrayList<AttachmentMaterialization.Text>(staged.size)
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
}

/** The closed outcome of the attachment send-gate. */
sealed interface AttachmentSendDecision {
    /**
     * Every staged attachment materialized as text — the send may proceed (subject to egress).
     * [attachments] is in staged order, so the caller pairs [attachments].elementAt(i) with the
     * i-th staged attachment.
     */
    data class Ready(
        val attachments: List<AttachmentMaterialization.Text>,
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
