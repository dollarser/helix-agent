package com.helix.core.model

/**
 * The closed set of UNSUPPORTED attachment categories (ADR-0014 §2).
 *
 * Exactly these five values by design — the set is deliberately NOT one entry per format, so a
 * newly-unsupported file never requires a new error code. In this milestone (HXA-049) a file is
 * "unsupported" whenever it is not a confirmed UTF-8 txt/md/csv/json text attachment. HXA-055 will
 * materialize images, which until then land in [AttachmentCategory.OTHER].
 */
enum class AttachmentCategory {
    /** A text encoding this build does not materialize (e.g. UTF-16). */
    TEXT_ENCODING,

    /** A document container (PDF / Office / ODF / RTF / ...) this build does not parse. */
    DOCUMENT,

    /** Audio this build does not transcribe or decode. */
    AUDIO,

    /** Video this build does not decode or frame-extract. */
    VIDEO,

    /** Anything else — including images before HXA-055 and archives. */
    OTHER,
}

/**
 * The first batch of confirmed-UTF-8 text attachment kinds (ADR-0014 「首批」). A kind is a
 * *label* of the file's shape, not a claim of any semantic understanding: the content is always
 * carried as UNTRUSTED data.
 */
enum class TextAttachmentKind {
    TXT,
    MARKDOWN,
    CSV,
    JSON,
}

/**
 * The closed classification of an imported file for chat-attachment materialization (ADR-0014 §2).
 *
 * [TextAttachment] is the only branch materializable in this milestone — it becomes a bounded,
 * source-labelled, UNTRUSTED context block (full content stays reachable through chunked
 * `read(offset, maxBytes)`). [UnsupportedAttachment] carries the closed [AttachmentCategory] and is
 * never parsed, decoded, OCR'd, rendered or otherwise treated as understanding of its content.
 */
sealed interface AttachmentClassification {
    /**
     * A confirmed-UTF-8 first-batch text file ([kind]: txt / md / csv / json) — the ONLY branch
     * materializable in this milestone: a bounded, source-labelled, UNTRUSTED context block, with
     * the full content reachable through chunked `read(offset, maxBytes)`.
     */
    data class TextAttachment(
        val kind: TextAttachmentKind,
    ) : AttachmentClassification

    /**
     * A closed-unsupported file ([category]) — never parsed, decoded, OCR'd, rendered or otherwise
     * treated as understanding of its content; the send-gate blocks it fail-closed with the
     * category surfaced (ADR-0014 §2 「UNSUPPORTED_ATTACHMENT_TYPE」).
     */
    data class UnsupportedAttachment(
        val category: AttachmentCategory,
    ) : AttachmentClassification
}
