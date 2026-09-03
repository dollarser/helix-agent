package com.helix.feature.files

import com.helix.core.model.AttachmentCategory
import com.helix.core.model.AttachmentClassification
import com.helix.core.model.TextAttachmentKind
import com.helix.core.workspace.ContentProbe

/**
 * Classifies an imported file as a materializable UTF-8 text attachment or a closed-category
 * unsupported one (ADR-0014 §2, HXA-049 first batch).
 *
 * Trust model: the ONLY trusted input is [ContentProbe.Result] — re-detected from the bytes
 * actually on disk. The provider-reported MIME and file name are untrusted (doc 07) and are used
 * only to *select a label* (which of the four text kinds, which unsupported category), never to
 * trust or interpret the content. A file is a [AttachmentClassification.TextAttachment] iff its
 * bytes decode as UTF-8 (not UTF-16 / binary / empty) AND its extension names one of the four
 * supported kinds. Everything else is [AttachmentClassification.UnsupportedAttachment].
 *
 * The closed category of an unsupported file is a best-effort label for surfacing to the user; it
 * never affects whether the file is materialized (it is not, in this milestone). Because the probe
 * has no magic table entries for audio / video / Office (an .docx is detected as `application/zip`),
 * those categories fall back to the untrusted extension — safe, because a wrong extension can only
 * change the label of an already-unsupported file, not promote it to materializable.
 */
object AttachmentClassifier {
    /** ADR-0014 per-file attachment cap: 10 MiB (distinct from the 256 MiB SAF-import cap). */
    const val MAX_ATTACHMENT_BYTES: Long = 10L shl 20

    /** ADR-0014: at most 4 attachments per message. */
    const val MAX_ATTACHMENTS_PER_MESSAGE: Int = 4

    /** The trusted, magic-derived image media types (HXA-055; the bytes win, the label never does). */
    private val IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    @Suppress("ReturnCount") // one return per closed branch: image / text / unsupported
    fun classify(
        probe: ContentProbe.Result,
        fileName: String?,
    ): AttachmentClassification {
        // HXA-055 (ADR-0014 §2/§4): a magic-confirmed image is an ImageAttachment — the media
        // type comes from the file's BYTES (ContentProbe's magic table), never from the
        // provider-reported MIME or the extension.
        if (probe.mimeType in IMAGE_MEDIA_TYPES) {
            return AttachmentClassification.ImageAttachment(probe.mimeType)
        }
        // The trusted, byte-derived encoding gate: only clean UTF-8 is a text attachment.
        // UTF-16 / binary / empty are never materialized as text here.
        if (probe.encoding != ContentProbe.Encoding.UTF8) {
            return AttachmentClassification.UnsupportedAttachment(unsupportedCategory(probe, fileName))
        }
        val kind = TEXT_KIND_BY_EXTENSION[extensionOf(fileName)]
        return if (kind != null) {
            AttachmentClassification.TextAttachment(kind)
        } else {
            // UTF-8 text, but not one of the four supported first-batch kinds.
            AttachmentClassification.UnsupportedAttachment(AttachmentCategory.OTHER)
        }
    }

    private fun unsupportedCategory(
        probe: ContentProbe.Result,
        fileName: String?,
    ): AttachmentCategory {
        val ext = extensionOf(fileName)
        return when {
            probe.encoding == ContentProbe.Encoding.UTF16 -> AttachmentCategory.TEXT_ENCODING

            ext in AUDIO_EXTENSIONS -> AttachmentCategory.AUDIO

            ext in VIDEO_EXTENSIONS -> AttachmentCategory.VIDEO

            ext in DOCUMENT_EXTENSIONS || probe.mimeType == "application/pdf" -> AttachmentCategory.DOCUMENT

            // Every other unrecognized binary (archives, unknown formats, ...).
            else -> AttachmentCategory.OTHER
        }
    }

    /** The lower-cased file extension (text after the last dot), or null when there is none. */
    private fun extensionOf(fileName: String?): String? {
        if (fileName.isNullOrEmpty()) return null
        val dot = fileName.lastIndexOf('.')
        // No dot, or a trailing dot ("trailing."), leaves no extension segment.
        return if (dot in 0 until fileName.length - 1) fileName.substring(dot + 1).lowercase() else null
    }

    private val TEXT_KIND_BY_EXTENSION: Map<String, TextAttachmentKind> =
        mapOf(
            "txt" to TextAttachmentKind.TXT,
            "md" to TextAttachmentKind.MARKDOWN,
            "markdown" to TextAttachmentKind.MARKDOWN,
            "csv" to TextAttachmentKind.CSV,
            "json" to TextAttachmentKind.JSON,
        )

    private val AUDIO_EXTENSIONS: Set<String> =
        setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "oga", "opus", "wma", "mid", "midi")

    private val VIDEO_EXTENSIONS: Set<String> =
        setOf("mp4", "mov", "mkv", "avi", "webm", "m4v", "3gp", "flv", "wmv")

    private val DOCUMENT_EXTENSIONS: Set<String> =
        setOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "odt", "ods", "odp", "rtf", "epub")
}
