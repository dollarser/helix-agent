package com.helix.extensions.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** Rejects an initialize response whose raw wire JSON omitted the required negotiated version. */
internal object McpInitializeResponseGuard : Interceptor {
    @Suppress("ReturnCount") // Non-initialize/error responses pass through; only a proven omission is rejected.
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!request.isInitializeRequest() || !response.isSuccessful || !response.header("Content-Type").isJson()) {
            return response
        }
        val prefix = response.peekBody(MAX_INITIALIZE_RESPONSE_PREFIX_BYTES).bytes()
        if (!prefix.hasExplicitInitializeProtocolVersion()) {
            response.close()
            throw McpProtocolVersionMissingException()
        }
        return response
    }
}

/**
 * Finds a literal, non-empty `result.protocolVersion` without retaining the whole response.
 * Escaped keys and keys after the bounded prefix are intentionally rejected.
 */
@Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ComplexCondition", "ReturnCount")
// This is a bounded JSON lexer: keeping depth/string transitions together makes the security state auditable.
private fun ByteArray.hasExplicitInitializeProtocolVersion(): Boolean {
    var index = 0
    var depth = 0
    var resultDepth = -1
    while (index < size) {
        when (this[index].toInt() and 0xff) {
            OPEN_BRACE -> {
                depth++
                index++
            }

            CLOSE_BRACE -> {
                if (depth == resultDepth) resultDepth = -1
                depth--
                index++
            }

            QUOTE -> {
                val end = findJsonStringEnd(index + 1)
                if (end < 0) return false
                val colon = nextNonWhitespace(end + 1)
                if (colon < size && this[colon].toInt() == COLON) {
                    val value = nextNonWhitespace(colon + 1)
                    if (depth == 1 && literalEquals(index + 1, end, RESULT_KEY)) {
                        if (value < size && this[value].toInt() == OPEN_BRACE) resultDepth = depth + 1
                    } else if (
                        depth == resultDepth &&
                        literalEquals(index + 1, end, PROTOCOL_VERSION_KEY) &&
                        value < size &&
                        this[value].toInt() == QUOTE
                    ) {
                        val valueEnd = findJsonStringEnd(value + 1)
                        return valueEnd > value + 1
                    }
                }
                index = end + 1
            }

            else -> {
                index++
            }
        }
    }
    return false
}

private fun ByteArray.findJsonStringEnd(start: Int): Int {
    var escaped = false
    for (index in start until size) {
        val value = this[index].toInt() and 0xff
        if (escaped) {
            escaped = false
        } else if (value == BACKSLASH) {
            escaped = true
        } else if (value == QUOTE) {
            return index
        }
    }
    return -1
}

private fun ByteArray.nextNonWhitespace(start: Int): Int {
    var index = start
    while (index < size && (this[index].toInt() and 0xff) in JSON_WHITESPACE) index++
    return index
}

private fun ByteArray.literalEquals(
    start: Int,
    end: Int,
    expected: ByteArray,
): Boolean {
    if (end - start != expected.size) return false
    return expected.indices.all { offset -> this[start + offset] == expected[offset] }
}

/** Enforces the MCP JSON wire ceiling in OkHttp, before Ktor or the SDK can buffer the body. */
internal object McpOkHttpResponseLimit : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        if (response.header("Content-Type").isEventStream()) {
            return response.newBuilder().body(SseEventLimitedResponseBody(body)).build()
        }
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_JSON_RESPONSE_BYTES) {
            response.close()
            throw McpResponseTooLargeException()
        }
        return response.newBuilder().body(JsonLimitedResponseBody(body)).build()
    }
}

private class JsonLimitedResponseBody(
    private val delegate: ResponseBody,
) : ResponseBody() {
    private val limitedSource: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            private var receivedBytes = 0L

            override fun read(
                sink: okio.Buffer,
                byteCount: Long,
            ): Long {
                val count = super.read(sink, byteCount)
                if (count > 0) {
                    receivedBytes += count
                    if (receivedBytes > MAX_JSON_RESPONSE_BYTES) {
                        delegate.close()
                        throw McpResponseTooLargeException()
                    }
                }
                return count
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = limitedSource
}

/** Bounds one SSE event without imposing a cumulative ceiling on a healthy long-lived stream. */
private class SseEventLimitedResponseBody(
    private val delegate: ResponseBody,
) : ResponseBody() {
    private val limitedSource: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            private var eventBytes = 0L
            private var lineHasContent = false
            private var previousWasCarriageReturn = false
            private val scanBuffer = okio.Buffer()

            override fun read(
                sink: okio.Buffer,
                byteCount: Long,
            ): Long {
                val count = super.read(sink, byteCount)
                if (count > 0) inspectAppendedBytes(sink, count)
                return count
            }

            private fun inspectAppendedBytes(
                sink: okio.Buffer,
                count: Long,
            ) {
                check(scanBuffer.exhausted())
                sink.copyTo(scanBuffer, sink.size - count, count)
                while (!scanBuffer.exhausted()) {
                    inspectByte(scanBuffer.readByte().toInt() and 0xff)
                }
            }

            private fun inspectByte(value: Int) {
                eventBytes++
                when (value) {
                    CARRIAGE_RETURN -> {
                        finishLine()
                        previousWasCarriageReturn = true
                    }

                    LINE_FEED -> {
                        if (!previousWasCarriageReturn) finishLine()
                        previousWasCarriageReturn = false
                    }

                    else -> {
                        lineHasContent = true
                        previousWasCarriageReturn = false
                    }
                }
                if (eventBytes > MAX_SSE_EVENT_BYTES) {
                    delegate.close()
                    throw McpSseEventTooLargeException()
                }
            }

            private fun finishLine() {
                if (!lineHasContent) eventBytes = 0
                lineHasContent = false
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = limitedSource
}

private class McpResponseTooLargeException : IOException("MCP HTTP response exceeds $MAX_JSON_RESPONSE_BYTES bytes")

private class McpSseEventTooLargeException : IOException("MCP SSE event exceeds $MAX_SSE_EVENT_BYTES bytes")

private class McpProtocolVersionMissingException :
    IOException("MCP initialize protocolVersion missing from bounded prefix")

@Suppress("ReturnCount") // Cheap shape gates avoid copying arbitrary or one-shot request bodies.
private fun okhttp3.Request.isInitializeRequest(): Boolean {
    if (method != "POST" || header("MCP-Protocol-Version") != null) return false
    val requestBody = body ?: return false
    val length = requestBody.contentLength()
    if (requestBody.isOneShot() || length !in 0..MAX_INITIALIZE_REQUEST_BYTES) return false
    val copy = okio.Buffer()
    requestBody.writeTo(copy)
    return try {
        Json
            .parseToJsonElement(copy.readUtf8())
            .jsonObject["method"]
            ?.jsonPrimitive
            ?.content == "initialize"
    } catch (_: SerializationException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun String?.isEventStream(): Boolean =
    this?.substringBefore(';')?.trim()?.equals("text/event-stream", true) == true

private fun String?.isJson(): Boolean = this?.substringBefore(';')?.trim()?.equals("application/json", true) == true

private const val MAX_JSON_RESPONSE_BYTES = 16L * 1024 * 1024
private const val MAX_SSE_EVENT_BYTES = 16L * 1024 * 1024
private const val CARRIAGE_RETURN = 13
private const val LINE_FEED = 10
private const val MAX_INITIALIZE_REQUEST_BYTES = 64L * 1024
private const val MAX_INITIALIZE_RESPONSE_PREFIX_BYTES = 64L * 1024
private const val OPEN_BRACE = '{'.code
private const val CLOSE_BRACE = '}'.code
private const val QUOTE = '"'.code
private const val BACKSLASH = '\\'.code
private const val COLON = ':'.code
private val JSON_WHITESPACE = setOf(' '.code, '\t'.code, '\r'.code, '\n'.code)
private val RESULT_KEY = "result".toByteArray(Charsets.US_ASCII)
private val PROTOCOL_VERSION_KEY = "protocolVersion".toByteArray(Charsets.US_ASCII)
