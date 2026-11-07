package com.helix.feature.files

import com.helix.core.model.AttachmentCategory
import com.helix.core.model.AttachmentClassification
import com.helix.core.model.TextAttachmentKind
import com.helix.core.model.attachment.AttachmentMaterialization
import com.helix.core.model.input.InputAttachmentLimits
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.ContentProbe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The fail-closed, re-verified materialization of a bound chat attachment into model context
 * (ADR-0014 §2/§3, HXA-049 first batch).
 *
 * Trust model: the ONLY trusted fact is [materialize]'s [boundSha256] — the snapshot taken when
 * the file was imported. At materialization the file is re-read from disk and its SHA-256
 * recomputed; if it is no longer there or a single byte has changed, the result is
 * [AttachmentMaterialization.Unavailable] / [AttachmentMaterialization.Tampered] and the caller
 * must block the send — the content is never released. The classification is likewise
 * RE-DERIVED from the bytes on disk via [ContentProbe] + [AttachmentClassifier]; the import-time
 * classification is never trusted, so a stale or tampered label can only ever downgrade a file to
 * unsupported, never promote it to materializable.
 *
 * A [AttachmentMaterialization.Text] is a BOUNDED, source-labelled view of confirmed-UTF-8
 * content. The caller MUST surface it as an UNTRUSTED context item (doc 07): its [Text.content]
 * is at most [InputAttachmentLimits.MAX_INLINE_TEXT_BYTES] leading bytes, [Text.sha256] binds the FULL file, and the
 * remainder stays reachable through chunked `read(offset, maxBytes)` — no binary or base64 ever
 * enters the model context. [AttachmentMaterialization.Unsupported] carries the closed
 * [AttachmentCategory] and is never parsed, decoded, OCR'd, rendered or media-processed;
 * surfacing it and blocking the send is the caller's job (HXA-049).
 */
object AttachmentMaterializer {
    /**
     * Materializes the attachment file at [file] against its bound snapshot [boundSha256].
     *
     * [fileName] is the sanitized display name the caller already has (a source label only — the
     * extension helps select the text *kind* / unsupported *category* but the bytes always win,
     * see [AttachmentClassifier]).
     *
     * @return [AttachmentMaterialization.Text] for a confirmed-UTF-8 first-batch kind;
     *   [AttachmentMaterialization.Unsupported] with the closed category otherwise;
     *   [AttachmentMaterialization.Tampered] when the on-disk bytes no longer hash to
     *   [boundSha256]; [AttachmentMaterialization.Unavailable] when the file is missing or
     *   unreadable. Both of the last two are fail-closed and must block the send.
     * @throws IllegalArgumentException when [boundSha256] is not 64 characters (a caller bug —
     *   the Room binding already enforces 64 lowercase-hex).
     */
    @Suppress("ReturnCount", "SwallowedException")
    fun materialize(
        file: Path,
        boundSha256: String,
        fileName: String?,
    ): AttachmentMaterialization {
        require(boundSha256.length == 64) { "boundSha256 must be 64 hex chars" }
        val name = fileName.orEmpty()
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return AttachmentMaterialization.Unavailable(name)
        }
        return try {
            val actual = AtomicFileWriter.sha256Hex(file)
            if (actual != boundSha256) {
                return AttachmentMaterialization.Tampered(name, boundSha256, actual)
            }
            when (val classification = AttachmentClassifier.classify(ContentProbe.probe(file), fileName)) {
                is AttachmentClassification.TextAttachment -> {
                    if (classification.kind.isExtractedDocument) {
                        // PDF / DOCX / HTML: the raw bytes are a container, not UTF-8 text. The
                        // model-visible content is EXTRACTED on-device (best-effort, fail-closed);
                        // the SHA and size still bind the FULL raw file.
                        extractedText(name, file, classification.kind, actual)
                    } else {
                        val (content, size) = boundedText(file)
                        AttachmentMaterialization.Text(
                            fileName = name,
                            kind = classification.kind,
                            content = content,
                            sha256 = actual,
                            truncated = size > InputAttachmentLimits.MAX_INLINE_TEXT_BYTES.toLong(),
                            sizeBytes = size,
                        )
                    }
                }

                is AttachmentClassification.ImageAttachment -> {
                    // The raw image verified against its bound snapshot; the caller (the send
                    // gate) re-verifies the NORMALIZED artifact separately and binds it to the
                    // message as an ImageReference (HXA-055, ADR-0014 §4).
                    AttachmentMaterialization.Image(
                        fileName = name,
                        mediaType = classification.mediaType,
                        sha256 = actual,
                        sizeBytes = Files.size(file),
                    )
                }

                is AttachmentClassification.UnsupportedAttachment -> {
                    AttachmentMaterialization.Unsupported(name, classification.category)
                }
            }
        } catch (e: IOException) {
            // The file was present a moment ago but is not readable (a raced delete, or an I/O
            // error): fail closed exactly like missing — the content is not available to send.
            AttachmentMaterialization.Unavailable(name)
        }
    }

    /**
     * Reads at most [InputAttachmentLimits.MAX_INLINE_TEXT_BYTES] leading bytes of [file] and decodes them as UTF-8.
     *
     * The read is exactly the range the probe already confirmed is valid UTF-8 (see
     * [InputAttachmentLimits.MAX_INLINE_TEXT_BYTES]), so the decode cannot hit a malformed sequence and no character is
     * ever split or replaced. Returns the decoded prefix plus the file's total size.
     */
    private fun boundedText(file: Path): Pair<String, Long> {
        val size = Files.size(file)
        val wanted = minOf(size, InputAttachmentLimits.MAX_INLINE_TEXT_BYTES.toLong()).toInt()
        val buffer = ByteArray(wanted)
        Files.newInputStream(file).use { input ->
            var total = 0
            while (total < wanted) {
                val n = input.read(buffer, total, wanted - total)
                if (n < 0) break
                total += n
            }
            // The file was verified (SHA-256 matched) just above, so a short read here means it
            // shrank underneath us mid-read (a raced same-uid delete/trash). That is the SAME
            // "content may no longer be what we verified" situation as a raced delete, so throw
            // IOException to let [materialize]'s catch map it to Unavailable (fail closed) rather
            // than escape as an uncaught IllegalArgumentException.
            if (total !=
                wanted
            ) {
                throw IOException("short read of attachment inline prefix; file changed during materialization")
            }
        }
        return buffer.toString(Charsets.UTF_8) to size
    }

    /**
     * The extracted-document branch (P0-B PDF / DOCX / HTML): reads the FULL raw file (already
     * hash-verified), extracts its text on-device, and returns a [Text] whose [Text.content] is a
     * bounded UTF-8 view of the EXTRACTED text, while [Text.sha256] / [Text.sizeBytes] bind the
     * FULL raw file. The extraction's honest outcome maps straight onto the view: a cap hit
     * ([ExtractedText.Truncated]) forces [Text.truncated]; an unreadable container
     * ([ExtractedText.Unreadable]) is an empty (but valid) view — fail-closed, never a crash and
     * never a fabricated byte.
     */
    private fun extractedText(
        name: String,
        file: Path,
        kind: TextAttachmentKind,
        sha: String,
    ): AttachmentMaterialization.Text {
        val size = Files.size(file)
        return when (val extracted = DocumentTextExtractor.extract(kind, Files.readAllBytes(file))) {
            is ExtractedText.Success -> {
                val content = prefixUtf8(extracted.text, InputAttachmentLimits.MAX_INLINE_TEXT_BYTES)
                AttachmentMaterialization.Text(
                    fileName = name,
                    kind = kind,
                    content = content,
                    sha256 = sha,
                    truncated = content != extracted.text,
                    sizeBytes = size,
                )
            }

            is ExtractedText.Truncated -> {
                val content = prefixUtf8(extracted.text, InputAttachmentLimits.MAX_INLINE_TEXT_BYTES)
                AttachmentMaterialization.Text(
                    fileName = name,
                    kind = kind,
                    content = content,
                    sha256 = sha,
                    truncated = true,
                    sizeBytes = size,
                )
            }

            ExtractedText.Unreadable -> {
                AttachmentMaterialization.Text(
                    fileName = name,
                    kind = kind,
                    content = "",
                    sha256 = sha,
                    truncated = false,
                    sizeBytes = size,
                )
            }
        }
    }

    /** The leading UTF-8 prefix of [s] within [maxBytes] bytes, never splitting a code point. */
    private fun prefixUtf8(
        s: String,
        maxBytes: Int,
    ): String {
        var bytes = 0
        var limit = s.length
        for (i in s.indices) {
            val code = s[i].code
            bytes +=
                if (code < 0x80) {
                    1
                } else if (code < 0x800) {
                    2
                } else if (code in 0xD800..0xDFFF) {
                    2
                } else {
                    3
                }
            if (bytes > maxBytes) {
                limit = i
                break
            }
        }
        return s.substring(0, limit)
    }
}
