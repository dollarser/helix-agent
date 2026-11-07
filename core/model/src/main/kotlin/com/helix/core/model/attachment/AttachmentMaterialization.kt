package com.helix.core.model.attachment

import com.helix.core.model.AttachmentCategory
import com.helix.core.model.TextAttachmentKind

/**
 * The closed outcome of materializing one bound attachment (ADR-0014).
 *
 * [Text] is the only branch the caller may place into model context — and it must be placed as an
 * UNTRUSTED item. [Unsupported] and the two fail-closed branches ([Tampered], [Unavailable]) are
 * never materialized; the caller surfaces [Unsupported] with its closed category and blocks the
 * send on any of the three (HXA-049). No branch carries a real filesystem path — [fileName] is the
 * sanitized label only.
 */
sealed interface AttachmentMaterialization {
    /**
     * A confirmed-UTF-8 first-batch text attachment, ready to surface as an UNTRUSTED block.
     *
     * @param fileName the sanitized source label (never a real path).
     * @param kind the closed first-batch kind (a shape label, not a claim of understanding).
     * @param content the BOUNDED inline view (at most [com.helix.core.model.input.InputAttachmentLimits.MAX_INLINE_TEXT_BYTES]
     *   leading bytes, decoded UTF-8).
     * @param sha256 the SHA-256 of the FULL content — the trust binding; the inline view may be a
     *   prefix but the hash is over the whole verified file.
     * @param truncated true when [content] is a prefix of the full file (the rest is chunked-read).
     * @param sizeBytes the full file size in bytes.
     */
    data class Text(
        val fileName: String,
        val kind: TextAttachmentKind,
        val content: String,
        val sha256: String,
        val truncated: Boolean,
        val sizeBytes: Long,
    ) : AttachmentMaterialization

    /**
     * A closed-category unsupported file (this build does not materialize it). Never parsed,
     * decoded, OCR'd, rendered or media-processed — the caller surfaces [category] and blocks the
     * send (HXA-049).
     */
    data class Unsupported(
        val fileName: String,
        val category: AttachmentCategory,
    ) : AttachmentMaterialization

    /** The on-disk bytes no longer hash to the bound snapshot — fail closed; block the send. */
    data class Tampered(
        val fileName: String,
        val expectedSha256: String,
        val actualSha256: String,
    ) : AttachmentMaterialization

    /**
     * A magic-confirmed image attachment (HXA-055). As returned by [materialize] the [sha256]
     * binds the RAW file and [width]/[height] are 0 (unknown without the normalized artifact);
     * the send gate re-verifies the NORMALIZED artifact (the bytes that actually leave) and
     * rewrites [sha256]/[sizeBytes]/[width]/[height] to its facts before the send proceeds.
     * The message binds the normalized artifact as an [com.helix.core.model.ImageReference].
     */
    data class Image(
        val fileName: String,
        val mediaType: String,
        val sha256: String,
        val sizeBytes: Long,
        val width: Int = 0,
        val height: Int = 0,
    ) : AttachmentMaterialization

    /** The file is missing or unreadable — fail closed; block the send. */
    data class Unavailable(
        val fileName: String,
    ) : AttachmentMaterialization
}
