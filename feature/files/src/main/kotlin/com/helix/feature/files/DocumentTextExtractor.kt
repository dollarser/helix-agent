package com.helix.feature.files

import com.helix.core.model.TextAttachmentKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Total cap on DECOMPRESSED container content per extraction (zip-bomb / inflate-bomb guard):
 * a 10 MiB raw attachment must not be allowed to inflate into gigabytes of heap.
 */
internal const val MAX_DECOMPRESSED_BYTES: Long = 4L * 1024L * 1024L

/** Max extracted-text length; anything longer comes back as a bounded [ExtractedText.Truncated]. */
internal const val MAX_EXTRACTED_CHARS: Int = 500_000

private const val DOCX_DOCUMENT_ENTRY = "word/document.xml"
private const val ZIP_READ_BUFFER = 8 * 1024

/**
 * The closed outcome of a best-effort document extraction (doc PX-05):
 *
 * - [Success] — the extraction COMPLETED; [Success.text] may be empty, which is the honest
 *   "the document has no readable text" state (a valid container, nothing to show).
 * - [Truncated] — a size cap was hit (decompressed content or text length); [Truncated.text]
 *   is the bounded prefix that WAS extracted — partial, never fabricated.
 * - [Unreadable] — the bytes are not a parseable container of that kind (truncated zip, bad
 *   inflate, not a PDF): fail-closed, no text at all.
 *
 * The states are deliberately distinct so a caller can tell "no text" from "could not read":
 * only the first two release a (possibly empty) view of the content.
 */
sealed interface ExtractedText {
    /** The extraction completed; [text] may be empty (no readable text in the document). */
    data class Success(
        val text: String,
    ) : ExtractedText

    /** A size cap was hit; [text] is the bounded prefix that was extracted. */
    data class Truncated(
        val text: String,
    ) : ExtractedText

    /** The bytes are not a parseable container of the kind — fail-closed, no text. */
    data object Unreadable : ExtractedText
}

/**
 * On-device, ZERO-DEPENDENCY text extraction for the P0-B document batch (doc PX-05: PDF / DOCX /
 * HTML). A self-contained pure-JVM implementation (regex + `java.util.zip` + `Inflater`) so it
 * runs identically on the Android runtime and in the JVM unit tests, with no added AAR size — the
 * same native-first precedent the JGit reader sets.
 *
 * Extraction is deliberately BEST-EFFORT and FAILS CLOSED: it never crashes and never fabricates
 * a value, and the outcome is HONEST about its limits — a cap hit is [ExtractedText.Truncated]
 * (with the real bounded prefix), a broken container is [ExtractedText.Unreadable], and a valid
 * document without text is [ExtractedText.Success] with empty text. The caps are what make the
 * extraction safe on-device: [MAX_DECOMPRESSED_BYTES] bounds every inflate (the zip-bomb vector)
 * and [MAX_EXTRACTED_CHARS] bounds the text a caller ever has to hold.
 */
object DocumentTextExtractor {
    /**
     * Extracts the model-visible text of one [kind] from its raw [bytes]. See [ExtractedText] for
     * the closed outcome; a kind outside the extracted-document set is a completed no-op
     * ([ExtractedText.Success] with empty text), and any parse problem is [ExtractedText.Unreadable].
     */
    fun extract(
        kind: TextAttachmentKind,
        bytes: ByteArray,
    ): ExtractedText {
        val result: ExtractedText =
            try {
                when (kind) {
                    TextAttachmentKind.HTML -> extractHtml(bytes)
                    TextAttachmentKind.DOCX -> extractDocx(bytes)
                    TextAttachmentKind.PDF -> extractPdf(bytes)
                    else -> ExtractedText.Success("")
                }
            } catch (_: Exception) {
                return ExtractedText.Unreadable
            }
        return when (result) {
            ExtractedText.Unreadable -> {
                result
            }

            is ExtractedText.Success -> {
                capLength(result.text)
            }

            is ExtractedText.Truncated -> {
                // A Truncated is a cap hit that ALREADY happened — bound the prefix at the text
                // cap but keep the status ([capLength] alone would demote a short Truncated back
                // to Success, lying about the cap).
                val prefix =
                    if (result.text.length > MAX_EXTRACTED_CHARS) {
                        result.text.substring(0, MAX_EXTRACTED_CHARS)
                    } else {
                        result.text
                    }
                ExtractedText.Truncated(prefix)
            }
        }
    }

    /** Visible text of an HTML/HTM document: comments, the `<head>` section (title / meta /
     * scripts / styles), and any remaining scripts and styles dropped; block tags become
     * newlines, all other tags are stripped, entities decoded. */
    internal fun extractHtml(bytes: ByteArray): ExtractedText {
        val text = bytes.toString(Charsets.UTF_8)
        val noComments = text.replace(COMMENT, " ")
        val noHead = noComments.replace(HEAD_SECTION, " ")
        val noScript = noHead.replace(SCRIPT_STYLE, " ")
        val withBreaks = noScript.replace(BLOCK_TAG, "\n")
        val stripped = withBreaks.replace(TAG, " ")
        return ExtractedText.Success(normalize(decodeEntities(stripped)))
    }

    /** Visible text of a .docx (a zip with `word/document.xml`): `<w:tab>`/`<w:br>`/`</w:p>`
     * become the tab / newline markers, then all XML tags are REMOVED (adjacent text runs
     * concatenate directly, as in Word — unlike HTML, tags do not imply a space) and entities
     * decoded. The entry read is decompression-capped; a bomb-sized document is a bounded
     * prefix. */
    internal fun extractDocx(bytes: ByteArray): ExtractedText {
        val entry = readZipEntryCapped(bytes, DOCX_DOCUMENT_ENTRY) ?: return ExtractedText.Success("")
        val withTabs = entry.text.replace(TAB, "\t")
        val withBreaks = withTabs.replace(BR, "\n")
        val paragraphs = withBreaks.replace(PARA_END, "\n")
        val stripped = paragraphs.replace(TAG, "")
        val text = normalize(decodeEntities(stripped))
        return if (entry.hitBudget) ExtractedText.Truncated(text) else ExtractedText.Success(text)
    }

    /** Visible text of a PDF: the content streams' text-showing operators (see [pdfText]). */
    internal fun extractPdf(bytes: ByteArray): ExtractedText = pdfText(bytes)

    /** The leading [MAX_EXTRACTED_CHARS] chars of [text], flagged [ExtractedText.Truncated] past
     * the cap (the cap protects the caller's heap; the prefix is real extracted text). */
    private fun capLength(text: String): ExtractedText =
        if (text.length > MAX_EXTRACTED_CHARS) {
            ExtractedText.Truncated(text.substring(0, MAX_EXTRACTED_CHARS))
        } else {
            ExtractedText.Success(text)
        }

    private class ZipEntryRead(
        val text: String,
        val hitBudget: Boolean,
    )

    /**
     * Reads one named entry from a zip [bytes] as UTF-8, decompression-BOUNDED: the TOTAL
     * inflated size across the whole entry walk is capped at [MAX_DECOMPRESSED_BYTES] — a
     * zip bomb (tiny raw bytes, gigabyte expansion) is refused with an honest
     * [ZipEntryRead.hitBudget] instead of an OOM. Returns null when the entry is absent or the
     * bytes are not a zip (the latter throws [java.util.zip.ZipException] to the caller).
     */
    private fun readZipEntryCapped(
        bytes: ByteArray,
        name: String,
    ): ZipEntryRead? {
        val zip = ZipInputStream(ByteArrayInputStream(bytes))
        try {
            var decompressed = 0L
            var sawEntry = false
            for (entry in generateSequence(zip.nextEntry) { zip.nextEntry }) {
                sawEntry = true
                val (data, hit) = readEntryCapped(zip, MAX_DECOMPRESSED_BYTES - decompressed)
                if (entry.name == name || hit) {
                    return stopResult(entry, name, data, hit)
                }
                decompressed += data.size.toLong()
            }
            // A stream that yields no entries at all is not a zip: fail closed with a
            // ZipException the caller maps to Unreadable.
            if (!sawEntry) throw ZipException("not a zip archive: no entries found")
            return null
        } finally {
            zip.close()
        }
    }

    /** The honest outcome at a walk stop: the (possibly bounded) target entry, or a budget hit
     * on a non-target entry (the target is then unreliably reachable). */
    private fun stopResult(
        entry: ZipEntry,
        name: String,
        data: ByteArray,
        hit: Boolean,
    ): ZipEntryRead =
        if (entry.name == name) {
            ZipEntryRead(data.toString(Charsets.UTF_8), hit)
        } else {
            ZipEntryRead("", true)
        }

    /**
     * Drains the current zip entry, collecting at most [budget] DECOMPRESSED bytes: returns the
     * collected (bounded) data and whether the budget was hit before the entry ended.
     */
    private fun readEntryCapped(
        zip: ZipInputStream,
        budget: Long,
    ): Pair<ByteArray, Boolean> {
        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(ZIP_READ_BUFFER)
        var consumed = 0L
        var open = true
        while (open && consumed < budget) {
            val n = zip.read(buffer)
            if (n < 0) {
                open = false
            } else {
                val take = minOf(n, (budget - consumed).toInt())
                sink.write(buffer, 0, take)
                consumed += take
                open = take >= n
            }
        }
        return sink.toByteArray() to (consumed >= budget)
    }

    /** Decodes numeric and a small closed set of named HTML entities; unknown names are left as-is. */
    fun decodeEntities(s: String): String =
        s.replace(ENTITY) { m ->
            val hex = m.groupValues[1]
            val dec = m.groupValues[2]
            when {
                hex.isNotEmpty() -> codePoint(hex.toIntOrNull(16) ?: return@replace m.value)
                dec.isNotEmpty() -> codePoint(dec.toIntOrNull(10) ?: return@replace m.value)
                else -> namedEntity(m.groupValues[3]) ?: m.value
            }
        }
}

/** Trims trailing line spaces and collapses runs of blank lines into a single blank line. */
private fun normalize(s: String): String =
    s
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

/** A small closed set of common named entities; anything else is left verbatim. */
private val NAMED_ENTITIES: Map<String, String> =
    mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "laquo" to "«",
        "raquo" to "»",
    )

private fun namedEntity(name: String): String? = NAMED_ENTITIES[name]

/** A code point (validated) to its UTF-16 char sequence; out-of-range yields "". */
private fun codePoint(code: Int): String = if (code in 0..0x10FFFF) String(Character.toChars(code)) else ""

private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

/** The whole `<head>…</head>` section: its title / meta / script / style content is not visible
 * text, so it is dropped before the rest of the document is reduced. */
private val HEAD_SECTION =
    Regex(
        "<\\s*head\\b[^>]*>[\\s\\S]*?</head\\s*>",
        RegexOption.IGNORE_CASE,
    )

private val SCRIPT_STYLE =
    Regex(
        "<(script|style)\\b[^>]*>[\\s\\S]*?</(script|style)>",
        RegexOption.IGNORE_CASE,
    )

/** Opening or closing tags of common block elements (and <br>): each becomes a newline. */
private val BLOCK_TAG =
    Regex(
        "<\\s*/?(pre|p|div|li|ul|ol|tr|table|h[1-6]|br|hr|section|" +
            "article|header|footer|blockquote|dl|dt|dd|form|main|nav)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )

private val TAG = Regex("<[^>]+>")

private val TAB = Regex("<w:tab\\s*/?>")
private val BR = Regex("<w:(br|cr)\\s*/?>")
private val PARA_END = Regex("</w:p\\s*>")

private val ENTITY = Regex("&#x([0-9a-fA-F]+);|&#([0-9]+);|&([a-zA-Z][a-zA-Z0-9]*);")
