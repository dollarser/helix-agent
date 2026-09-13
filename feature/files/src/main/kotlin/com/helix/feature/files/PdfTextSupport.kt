package com.helix.feature.files

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Minimal, ZERO-DEPENDENCY PDF text extraction (P0-B document batch, doc PX-05). It walks the raw
 * PDF bytes, decompresses each content stream (zlib when present) and reads the text-showing
 * operators (`Tj` / `TJ`) between `BT`…`ET` blocks. No PDF library is added: the reader is bounded,
 * pure-JVM (identical on the Android runtime and the JVM unit tests) and FAILS CLOSED — a
 * malformed or non-text PDF yields "" rather than a crash (best-effort, see [pdfText]).
 */
internal const val MAX_PDF_TEXT = 256_000

/** Cap on the number of content streams scanned (bounds the work on a pathological file). */
private const val MAX_STREAMS = 1_000

/** The closed set of PDF text-positioning operators that start a new line. */
private val POSITIONING_OPS = setOf("T*", "Td", "TD", "Tm")

/** A list of byte values (each 0..255) as a [ByteArray] (8-bit sign-extension is lossless). */
private fun List<Int>.toPdfBytes(): ByteArray = ByteArray(size) { this[it].toByte() }

/**
 * Extracts the visible text of the PDF [bytes]. Verifies the `%PDF` header, then for each content
 * stream decompresses it (zlib when the data is deflate-compressed) and reads the `BT`…`ET`
 * text blocks. Returns "" for a non-PDF or a PDF with no readable text (fail-closed).
 */
internal fun pdfText(bytes: ByteArray): String {
    val data = bytes.toString(Charsets.ISO_8859_1)
    if (!data.startsWith("%PDF")) return ""
    val out = StringBuilder()
    for (raw in findStreamDatas(data)) {
        val content = (inflate(raw) ?: raw).toString(Charsets.ISO_8859_1)
        appendTextFromStream(content, out)
    }
    var text = out.toString()
    if (text.length > MAX_PDF_TEXT) text = text.substring(0, MAX_PDF_TEXT)
    return text.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
}

/**
 * The raw byte ranges of every `stream`…`endstream` body. Each keyword is boundary-checked (the
 * character before must be `>`, a space or a newline) so a stray "stream" inside data is skipped;
 * the EOL the PDF spec requires after the keyword is consumed before the body starts.
 */
@Suppress("LoopWithTooManyJumpStatements") // a bounded forward scan; the boundary and end checks exit early
private fun findStreamDatas(data: String): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var from = 0
    while (out.size < MAX_STREAMS) {
        val s = data.indexOf("stream", from)
        if (s < 0) break
        val before = if (s > 0) data[s - 1] else ' '
        if (before !in " >\r\n") {
            from = s + 1
            continue
        }
        var start = s + 6 // "stream".length
        val eol = data.getOrNull(start)
        if (eol == '\r') {
            start += if (data.getOrNull(start + 1) == '\n') 2 else 1
        } else if (eol == '\n') {
            start += 1
        }
        val e = data.indexOf("endstream", start)
        if (e < 0) break
        out += data.substring(start, e).toByteArray(Charsets.ISO_8859_1)
        from = e + 9 // "endstream".length
    }
    return out
}

/** Appends the text of every `BT`…`ET` block in [content] to [out] (in order). */
private fun appendTextFromStream(
    content: String,
    out: StringBuilder,
) {
    var i = 0
    while (true) {
        val bt = findWord(content, i, "BT")
        if (bt < 0) return
        val et = findWord(content, bt + 2, "ET")
        if (et < 0) return
        appendBlockText(content, bt + 2, et, out)
        i = et + 2
    }
}

/** Appends the decoded text of the `(`…`)` literals in [content][start]..[content][end), inserting
 * a newline at each text-positioning operator (T* / Td / TD / Tm). */
@Suppress("NestedBlockDepth") // a single scan: char-class branch, then read the operator word
private fun appendBlockText(
    content: String,
    start: Int,
    end: Int,
    out: StringBuilder,
) {
    var i = start
    while (i < end) {
        val c = content[i]
        when {
            c == '(' -> {
                val (text, next) = readPdfString(content, i)
                out.append(text)
                i = next
            }

            c.isLetter() -> {
                // Read the whole operator word; a positioning op starts a new line.
                var j = i
                while (j < end) {
                    val cj = content[j]
                    if (cj.isWhitespace() || cj in "()<>{}/%") break
                    j++
                }
                if (content.substring(i, j) in POSITIONING_OPS) out.append('\n')
                i = j
            }

            else -> {
                i++
            }
        }
    }
}

/** The index of [word] in [s] at/after [from] as a whole token (no identifier char on either
 * side), or -1. */
private fun findWord(
    s: String,
    from: Int,
    word: String,
): Int {
    var idx = from
    while (true) {
        val f = s.indexOf(word, idx)
        if (f < 0) return -1
        val b = if (f > 0) s[f - 1] else ' '
        val a = s.getOrNull(f + word.length) ?: ' '
        val bIdent = !b.isWhitespace() && b !in "()<>{}/%"
        val aIdent = !a.isWhitespace() && a !in "()<>{}/%"
        if (!bIdent && !aIdent) return f
        idx = f + 1
    }
}

/** Reads the PDF string literal starting at [s][i] (a `(`), returning its decoded text and the
 * index just past the closing `)`. Balanced parentheses and backslash escapes are honored. */
private fun readPdfString(
    s: String,
    i: Int,
): Pair<String, Int> {
    var depth = 0
    var j = i
    val raw = ArrayList<Int>()
    while (j < s.length) {
        val c = s[j]
        when {
            c == '\\' && j + 1 < s.length -> {
                val (b, adv) = decodeEscape(s, j)
                raw.add(b)
                j += adv
            }

            c == '(' -> {
                depth++
                raw.add(0x28)
                j++
            }

            c == ')' -> {
                depth--
                if (depth == 0) return decodePdfString(raw.toPdfBytes()) to (j + 1)
                raw.add(0x29)
                j++
            }

            else -> {
                raw.add(c.code and 0xFF)
                j++
            }
        }
    }
    return decodePdfString(raw.toPdfBytes()) to s.length
}

/** Decodes the escape at [s][i] (a backslash) into a single byte value and how many chars it
 * consumes (the backslash plus what follows). */
@Suppress("ReturnCount") // one return per closed escape form (named / octal / unknown)
private fun decodeEscape(
    s: String,
    i: Int,
): Pair<Int, Int> {
    val c = s[i + 1]
    SIMPLE_ESCAPES[c]?.let { return it to 2 }
    if (c in '0'..'7') {
        var value = c - '0'
        var k = i + 2
        var consumed = 2
        while (k < s.length && consumed < 4 && s[k] in '0'..'7') {
            value = (value shl 3) + (s[k] - '0')
            k++
            consumed++
        }
        return value.coerceIn(0, 255) to consumed
    }
    // Unknown escape: keep the escaped character as-is (best-effort).
    return (c.code and 0xFF) to 2
}

/** Decodes the raw byte sequence of a PDF string literal: UTF-16BE when it looks like it (even
 * length with high null density on the even indices), else ISO-8859-1 (the right mapping for the
 * Standard / WinAnsi encodings the vast majority of simple PDFs use). */
private fun decodePdfString(bytes: ByteArray): String {
    if (bytes.size >= 4 && bytes.size % 2 == 0) {
        var nullsEven = 0
        for (k in 0 until bytes.size step 2) {
            if (bytes[k] == 0.toByte()) nullsEven++
        }
        if (nullsEven * 2 >= bytes.size) return String(bytes, Charsets.UTF_16BE)
    }
    return bytes.toString(Charsets.ISO_8859_1)
}

/**
 * Inflates a zlib-wrapped stream, or null when [raw] is not deflate data (a raw stream) or the
 * inflate fails (fail-closed: the caller falls back to the raw bytes, which then simply yield no
 * readable text blocks).
 */
private fun inflate(raw: ByteArray): ByteArray? {
    val inf = Inflater()
    try {
        inf.setInput(raw)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (!inf.finished() && !inf.needsInput()) {
            val n = inf.inflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return if (inf.finished()) out.toByteArray() else null
    } catch (_: Exception) {
        return null
    } finally {
        inf.end()
    }
}

/** The simple PDF backslash escapes to their byte values. */
private val SIMPLE_ESCAPES: Map<Char, Int> =
    mapOf(
        'n' to 0x0A,
        'r' to 0x0D,
        't' to 0x09,
        'b' to 0x08,
        'f' to 0x0C,
        '(' to 0x28,
        ')' to 0x29,
        '\\' to 0x5C,
    )
