package com.helix.tools.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

@Suppress("TooManyFunctions")
internal object HttpFetchResponseParser {
    fun read(
        input: InputStream,
        request: HttpFetchRequest,
    ): ParsedResponse {
        val statusLine = readLine(input) ?: throw ProtocolError()
        val status = parseStatus(statusLine)
        val headers = readHeaders(input)
        val (body, bodyBytes, truncated) =
            if (request.method == "HEAD") {
                Triple("", 0L, false)
            } else {
                readBody(input, headers.contentLength, headers.chunked, request.maxBodyBytes)
            }
        return ParsedResponse(status, headers.location, headers.contentType, body, bodyBytes, truncated)
    }

    private fun parseStatus(statusLine: String): Int =
        statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: throw ProtocolError()

    @Suppress("LoopWithTooManyJumpStatements") // header parser: early-exit on blank line / non-header
    private fun readHeaders(input: InputStream): HeaderSet {
        var location: String? = null
        var contentType = ""
        var contentLength = -1L
        var chunked = false
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (name) {
                "location" -> location = value
                "content-type" -> contentType = value
                "content-length" -> contentLength = value.toLongOrNull() ?: -1L
                "transfer-encoding" -> chunked = value.lowercase().contains("chunked")
            }
        }
        return HeaderSet(location, contentType, contentLength, chunked)
    }

    /**
     * Reads the response body bounded to [maxBodyBytes]. With a Content-Length, at most
     * [maxBodyBytes] are read (the full length is still reported so [truncated] is meaningful);
     * otherwise the body is read until the connection closes, cut at the cap. Chunked framing is
     * decoded before bounding.
     */
    @Suppress("ReturnCount")
    private fun readBody(
        input: InputStream,
        contentLength: Long,
        chunked: Boolean,
        maxBodyBytes: Long,
    ): Triple<String, Long, Boolean> {
        if (chunked) {
            val all = readUntilClosed(input, maxBodyBytes)
            val decoded = decodeChunked(all)
            return if (decoded.size > maxBodyBytes) {
                Triple(
                    String(decoded.copyOfRange(0, maxBodyBytes.toInt()), StandardCharsets.UTF_8),
                    decoded.size.toLong(),
                    true,
                )
            } else {
                Triple(String(decoded, StandardCharsets.UTF_8), decoded.size.toLong(), false)
            }
        }
        if (contentLength >= 0) {
            val toRead = minOf(contentLength, maxBodyBytes)
            val buf = ByteArray(toRead.toInt())
            readFully(input, buf)
            val text = String(buf, StandardCharsets.UTF_8)
            return Triple(text, contentLength, contentLength > maxBodyBytes)
        }
        val all = readUntilClosed(input, maxBodyBytes)
        val truncated = all.size >= maxBodyBytes
        val bounded = if (truncated) all.copyOfRange(0, maxBodyBytes.toInt()) else all
        return Triple(String(bounded, StandardCharsets.UTF_8), all.size.toLong(), truncated)
    }

    /** Reads bytes until EOF, never exceeding [cap]. */
    private fun readUntilClosed(
        input: InputStream,
        cap: Long,
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (buffer.size() < cap) {
            val want = minOf(block.size.toLong(), cap - buffer.size()).toInt()
            val n = input.read(block, 0, want)
            if (n < 0) break
            buffer.write(block, 0, n)
        }
        return buffer.toByteArray()
    }

    /** Decodes HTTP `transfer-encoding: chunked` framing from raw bytes (best-effort, v1). */
    @Suppress("LoopWithTooManyJumpStatements") // chunked parser: stop at the first malformed frame
    private fun decodeChunked(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < raw.size) {
            val lineEnd = indexOfCrlf(raw, offset)
            if (lineEnd < 0) break
            val sizeHex = String(raw, offset, lineEnd - offset, StandardCharsets.US_ASCII).trim().split(";").first()
            val size = sizeHex.toIntOrNull(16) ?: break
            offset = lineEnd + 2
            if (size == 0) break
            val chunkEnd = minOf(offset + size, raw.size)
            out.write(raw, offset, chunkEnd - offset)
            offset = chunkEnd + 2
        }
        return out.toByteArray()
    }

    private fun indexOfCrlf(
        data: ByteArray,
        from: Int,
    ): Int {
        for (i in from until data.size - 1) {
            if (data[i].toInt() == '\r'.code && data[i + 1].toInt() == '\n'.code) return i
        }
        return -1
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        return sb.toString()
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
    ) {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) break
            off += n
        }
    }

    private class ProtocolError : Exception()

    data class ParsedResponse(
        val status: Int,
        val location: String?,
        val contentType: String,
        val body: String,
        val bodyBytes: Long,
        val truncated: Boolean,
    )

    private data class HeaderSet(
        val location: String?,
        val contentType: String,
        val contentLength: Long,
        val chunked: Boolean,
    )
}
