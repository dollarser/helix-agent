package com.helix.core.model

/**
 * The closed set of UNSUPPORTED attachment categories (ADR-0014 §2).
 *
 * Exactly these five values by design — the set is deliberately NOT one entry per format, so a
 * newly-unsupported file never requires a new error code. Since HXA-055 a file is "unsupported"
 * whenever it is neither a confirmed UTF-8 txt/md/csv/json text attachment nor a magic-confirmed
 * image (png/jpeg/webp/gif); images are the [ImageAttachment] branch and no longer land in
 * [AttachmentCategory.OTHER].
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

    /** Anything else — including archives and other binaries. */
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
 * [TextAttachment] becomes a bounded, source-labelled, UNTRUSTED context block (full content stays
 * reachable through chunked `read(offset, maxBytes)`). [ImageAttachment] (HXA-055) becomes a
 * normalized, hash-bound [ImageReference] on the user model message after the on-device
 * normalization pass (ADR-0014 §4). [UnsupportedAttachment] carries the closed
 * [AttachmentCategory] and is never parsed, decoded, OCR'd, rendered or otherwise treated as
 * understanding of its content.
 */
sealed interface AttachmentClassification {
    /**
     * A confirmed-UTF-8 first-batch text file ([kind]: txt / md / csv / json): a bounded,
     * source-labelled, UNTRUSTED context block, with the full content reachable through chunked
     * `read(offset, maxBytes)`.
     */
    data class TextAttachment(
        val kind: TextAttachmentKind,
    ) : AttachmentClassification

    /**
     * A magic-confirmed image (HXA-055): [mediaType] is one of `image/png` / `image/jpeg` /
     * `image/webp` / `image/gif`, derived from the file's BYTES (the trusted source) — the
     * provider-reported MIME and extension are labels only. The send path normalizes it on-device
     * (decode within [VisionLimits], EXIF strip, re-encode) and binds it to the message as an
     * [ImageReference]; a normalization failure keeps the raw artifact local (save/preview stays
     * possible) but blocks the send with an actionable error (ADR-0014 §4/§6).
     */
    data class ImageAttachment(
        val mediaType: String,
    ) : AttachmentClassification {
        init {
            require(mediaType in ImageReference.MEDIA_TYPES) {
                "image attachment mediaType is not in the closed set: $mediaType"
            }
        }
    }

    /**
     * A closed-unsupported file ([category]) — never parsed, decoded, OCR'd, rendered or otherwise
     * treated as understanding of its content; the send-gate blocks it fail-closed with the
     * category surfaced (ADR-0014 §2 「UNSUPPORTED_ATTACHMENT_TYPE」).
     */
    data class UnsupportedAttachment(
        val category: AttachmentCategory,
    ) : AttachmentClassification
}
