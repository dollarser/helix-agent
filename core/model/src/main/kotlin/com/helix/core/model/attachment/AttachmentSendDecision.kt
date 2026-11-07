package com.helix.core.model.attachment

import com.helix.core.model.AttachmentCategory

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
