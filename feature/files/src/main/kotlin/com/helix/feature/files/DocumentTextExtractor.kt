package com.helix.feature.files

import com.helix.core.model.TextAttachmentKind
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * On-device, ZERO-DEPENDENCY text extraction for the P0-B document batch (doc PX-05: PDF / DOCX /
 * HTML). A self-contained pure-JVM implementation (regex + `java.util.zip` + `Inflater`) so it
 * runs identically on the Android runtime and in the JVM unit tests, with no added AAR size — the
 * same native-first precedent the JGit reader sets.
 *
 * Extraction is deliberately BEST-EFFORT and FAILS CLOSED: any parse problem (a truncated
 * container, an unexpected shape, an inflate error) yields an empty string, never a crash and
 * never a fabricated value. An empty result is an honest "no readable text", not a success — the
 * caller still surfaces the attachment's source label and lets the model read the raw file through
 * the chunked `read(offset, maxBytes)` if it needs the bytes.
 */
object DocumentTextExtractor {
    /**
     * Extracts the model-visible text of one [kind] from its raw [bytes]. Returns "" when the kind
     * is not an extracted-document kind or when extraction fails (fail-closed, see class doc).
     */
    fun extract(
        kind: TextAttachmentKind,
        bytes: ByteArray,
    ): String =
        runCatching {
            when (kind) {
                TextAttachmentKind.HTML -> extractHtml(bytes)
                TextAttachmentKind.DOCX -> extractDocx(bytes)
                TextAttachmentKind.PDF -> extractPdf(bytes)
                else -> ""
            }
        }.getOrDefault("")

    /** Visible text of an HTML/HTM document: comments, scripts and styles dropped, block tags as
     * newlines, all other tags stripped, entities decoded. */
    fun extractHtml(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        val noComments = text.replace(COMMENT, " ")
        val noScript = noComments.replace(SCRIPT_STYLE, " ")
        val withBreaks = noScript.replace(BLOCK_TAG, "\n")
        val stripped = withBreaks.replace(TAG, " ")
        return normalize(decodeEntities(stripped))
    }

    /** Visible text of a .docx (a zip with `word/document.xml`): `<w:tab>`/`<w:br>`/`</w:p>`
     * become the tab / newline markers, then all XML tags are stripped and entities decoded. */
    fun extractDocx(bytes: ByteArray): String {
        val xml = readZipEntry(bytes, "word/document.xml") ?: return ""
        val withTabs = xml.replace(TAB, "\t")
        val withBreaks = withTabs.replace(BR, "\n")
        val paragraphs = withBreaks.replace(PARA_END, "\n")
        val stripped = paragraphs.replace(TAG, " ")
        return normalize(decodeEntities(stripped))
    }

    /** Visible text of a PDF: the content streams' text-showing operators (see [pdfText]). */
    fun extractPdf(bytes: ByteArray): String = pdfText(bytes)

    /** Reads one named entry from a zip [bytes] as UTF-8, or null when absent / not a zip. */
    fun readZipEntry(
        bytes: ByteArray,
        name: String,
    ): String? {
        ByteArrayInputStream(bytes).use { stream ->
            val zip = ZipInputStream(stream)
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) return zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return null
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
