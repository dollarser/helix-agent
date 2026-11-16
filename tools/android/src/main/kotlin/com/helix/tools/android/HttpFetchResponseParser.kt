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

    /** Bounds decoded bytes, never the chunk framing. Unknown lengths are observed lower bounds. */
    private fun readBody(
        input: InputStream,
        contentLength: Long,
        chunked: Boolean,
        maxBodyBytes: Long,
    ): Triple<String, Long, Boolean> {
        val cap = maxBodyBytes.toInt()
        require(maxBodyBytes in 0..Int.MAX_VALUE.toLong())
        return when {
            chunked -> {
                readChunks(input, cap)
            }

            contentLength >= 0 -> {
                val bytes = ByteArray(minOf(contentLength, maxBodyBytes).toInt())
                readFully(input, bytes)
                Triple(String(bytes, StandardCharsets.UTF_8), contentLength, contentLength > maxBodyBytes)
            }

            else -> {
                readClosedBody(input, cap)
            }
        }
    }

    private fun readClosedBody(
        input: InputStream,
        cap: Int,
    ): Triple<String, Long, Boolean> {
        val output = ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (output.size() < cap) {
            val count = input.read(block, 0, minOf(block.size, cap - output.size()))
            if (count < 0) break
            output.write(block, 0, count)
        }
        val truncated = output.size() == cap && input.read() != -1
        return Triple(output.toString("UTF-8"), output.size().toLong() + if (truncated) 1 else 0, truncated)
    }

    private fun readChunks(
        input: InputStream,
        cap: Int,
    ): Triple<String, Long, Boolean> {
        val output = ByteArrayOutputStream()
        while (true) {
            val size = readChunkSize(input)
            if (size == 0L) {
                readTrailers(input)
                return Triple(output.toString("UTF-8"), output.size().toLong(), false)
            }
            val remaining = cap - output.size()
            val bytes = ByteArray(minOf(size, remaining.toLong()).toInt())
            readFully(input, bytes)
            output.write(bytes)
            if (size > remaining) {
                if (input.read() == -1) throw ProtocolError()
                return Triple(output.toString("UTF-8"), output.size().toLong() + 1, true)
            }
            if (input.read() != '\r'.code || input.read() != '\n'.code) throw ProtocolError()
        }
    }

    private fun readChunkSize(input: InputStream): Long {
        val sizeText = readRequiredLine(input).substringBefore(';')
        if (sizeText.isEmpty() || sizeText.any { it.digitToIntOrNull(16) == null }) throw ProtocolError()
        return sizeText.toLongOrNull(16) ?: throw ProtocolError()
    }

    private fun readRequiredLine(input: InputStream): String = readLine(input) ?: throw ProtocolError()

    private fun readTrailers(input: InputStream) {
        // An incomplete terminating frame must not look like a complete body.
        do {
            val trailer = readLine(input) ?: throw ProtocolError()
        } while (trailer.isNotEmpty())
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        if (b == -1) throw ProtocolError()
        return sb.toString()
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
    ) {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) throw ProtocolError()
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
