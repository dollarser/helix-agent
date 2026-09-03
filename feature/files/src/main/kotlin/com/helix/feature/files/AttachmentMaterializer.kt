package com.helix.feature.files

import com.helix.core.model.AttachmentCategory
import com.helix.core.model.AttachmentClassification
import com.helix.core.model.TextAttachmentKind
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
 * is at most [MAX_INLINE_TEXT_BYTES] leading bytes, [Text.sha256] binds the FULL file, and the
 * remainder stays reachable through chunked `read(offset, maxBytes)` — no binary or base64 ever
 * enters the model context. [AttachmentMaterialization.Unsupported] carries the closed
 * [AttachmentCategory] and is never parsed, decoded, OCR'd, rendered or media-processed;
 * surfacing it and blocking the send is the caller's job (HXA-049).
 */
object AttachmentMaterializer {
    /**
     * Inline bound for a materialized text view, in bytes.
     *
     * Pinned to [ContentProbe.SAMPLE_BYTES] by design: the probe confirms only the first
     * [ContentProbe.SAMPLE_BYTES] bytes are valid UTF-8, so the inline view is capped at exactly
     * that probed region and can never span bytes the probe did not confirm — a "UTF-8 prefix,
     * binary tail" file is therefore classified unsupported or inlined only over its confirmed
     * prefix, never over raw binary.
     */
    const val MAX_INLINE_TEXT_BYTES: Int = ContentProbe.SAMPLE_BYTES

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
                    val (content, size) = boundedText(file)
                    AttachmentMaterialization.Text(
                        fileName = name,
                        kind = classification.kind,
                        content = content,
                        sha256 = actual,
                        truncated = size > MAX_INLINE_TEXT_BYTES.toLong(),
                        sizeBytes = size,
                    )
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
     * Reads at most [MAX_INLINE_TEXT_BYTES] leading bytes of [file] and decodes them as UTF-8.
     *
     * The read is exactly the range the probe already confirmed is valid UTF-8 (see
     * [MAX_INLINE_TEXT_BYTES]), so the decode cannot hit a malformed sequence and no character is
     * ever split or replaced. Returns the decoded prefix plus the file's total size.
     */
    private fun boundedText(file: Path): Pair<String, Long> {
        val size = Files.size(file)
        val wanted = minOf(size, MAX_INLINE_TEXT_BYTES.toLong()).toInt()
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
}

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
     * @param content the BOUNDED inline view (at most [AttachmentMaterializer.MAX_INLINE_TEXT_BYTES]
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
