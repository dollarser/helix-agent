package com.helix.extensions.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

/** Immutable, bounded argv selected from a persisted MCP server configuration. */
class McpStdioServerCommand private constructor(
    arguments: List<String>,
) {
    val arguments: List<String> = Collections.unmodifiableList(arguments.toList())

    /** Stable identity stored with a job so request data can never mutate the command. */
    val fingerprintSha256: String =
        sha256(arguments.joinToString(separator = "") { argument -> "${argument.length}:$argument" })

    companion object {
        fun locked(arguments: List<String>): McpStdioServerCommand {
            require(arguments.size in 1..64) { "MCP stdio command must contain 1..64 arguments" }
            require(arguments.all { it.length in 1..4096 && '\u0000' !in it && '\n' !in it && '\r' !in it }) {
                "MCP stdio command argument is invalid"
            }
            return McpStdioServerCommand(arguments)
        }
    }
}

data class McpStdioLimits(
    val maxMessages: Int = 256,
    val maxLineBytes: Int = 1024 * 1024,
    val maxStdoutBytes: Int = 2 * 1024 * 1024,
    val maxStderrBytes: Int = 64 * 1024,
) {
    init {
        require(maxMessages in 1..1024)
        require(maxLineBytes in 256..(4 * 1024 * 1024))
        require(maxStdoutBytes in maxLineBytes..(8 * 1024 * 1024))
        require(maxStderrBytes in 1024..(1024 * 1024))
    }
}

enum class McpStdioFailure {
    STDOUT_LIMIT,
    STDERR_LIMIT,
    INVALID_UTF8,
    UNTERMINATED_FRAME,
    EMPTY_FRAME,
    LINE_LIMIT,
    MESSAGE_LIMIT,
    INVALID_JSON_RPC,
    REVERSE_REQUEST,
}

class McpStdioProtocolException(
    val failure: McpStdioFailure,
) : IllegalArgumentException("MCP stdio protocol rejected: ${failure.name}")

data class McpStdioOutput(
    val messages: List<JsonObject>,
    /** Diagnostic only. It is never decoded as JSON-RPC or inserted into a Tool result. */
    val stderr: String,
)

/** Strict JSON-lines framing for the PRoot stdio transport. */
object McpStdioProtocol {
    private val json = Json { ignoreUnknownKeys = false }

    fun encodeClientMessages(
        messages: List<JsonObject>,
        limits: McpStdioLimits = McpStdioLimits(),
    ): ByteArray {
        if (messages.size > limits.maxMessages) reject(McpStdioFailure.MESSAGE_LIMIT)
        val lines =
            messages.map { message ->
                requireJsonRpcVersion(message)
                val method = message["method"] as? JsonPrimitive
                if (method?.contentOrNull.isNullOrBlank() || "result" in message || "error" in message) {
                    reject(McpStdioFailure.INVALID_JSON_RPC)
                }
                val line = message.toString().encodeToByteArray()
                if (line.size > limits.maxLineBytes) reject(McpStdioFailure.LINE_LIMIT)
                line
            }
        val size = lines.sumOf { it.size + 1 }
        if (size > limits.maxStdoutBytes) reject(McpStdioFailure.STDOUT_LIMIT)
        return ByteArray(size).also { output ->
            var offset = 0
            lines.forEach { line ->
                line.copyInto(output, offset)
                offset += line.size
                output[offset++] = '\n'.code.toByte()
            }
        }
    }

    fun parseServerOutput(
        stdout: ByteArray,
        stderr: ByteArray,
        limits: McpStdioLimits = McpStdioLimits(),
    ): McpStdioOutput {
        if (stdout.size > limits.maxStdoutBytes) reject(McpStdioFailure.STDOUT_LIMIT)
        if (stderr.size > limits.maxStderrBytes) reject(McpStdioFailure.STDERR_LIMIT)
        val stderrText = decodeUtf8(stderr, strict = false)
        if (stdout.isEmpty()) return McpStdioOutput(emptyList(), stderrText)
        if (stdout.last() != '\n'.code.toByte()) reject(McpStdioFailure.UNTERMINATED_FRAME)
        val frames = splitFrames(stdout)
        if (frames.size > limits.maxMessages) reject(McpStdioFailure.MESSAGE_LIMIT)
        val messages =
            frames.map { frame ->
                if (frame.isEmpty()) reject(McpStdioFailure.EMPTY_FRAME)
                val normalized = if (frame.last() == '\r'.code.toByte()) frame.copyOf(frame.size - 1) else frame
                if (normalized.isEmpty()) reject(McpStdioFailure.EMPTY_FRAME)
                if (normalized.size > limits.maxLineBytes) reject(McpStdioFailure.LINE_LIMIT)
                val text = decodeUtf8(normalized, strict = true)
                val element =
                    runCatching { json.parseToJsonElement(text) }
                        .getOrElse { reject(McpStdioFailure.INVALID_JSON_RPC) }
                val message = element as? JsonObject ?: reject(McpStdioFailure.INVALID_JSON_RPC)
                validateServerMessage(message)
                message
            }
        return McpStdioOutput(messages, stderrText)
    }

    private fun validateServerMessage(message: JsonObject) {
        requireJsonRpcVersion(message)
        val hasMethod = (message["method"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
        val hasId = "id" in message
        val hasResult = "result" in message
        val hasError = "error" in message
        when {
            hasMethod && hasId -> {
                reject(McpStdioFailure.REVERSE_REQUEST)
            }

            hasMethod && !hasResult && !hasError -> {
                Unit
            }

            // server notification
            !hasMethod && hasId && hasResult.xor(hasError) -> {
                validateId(message.getValue("id"))
                if (hasError) validateError(message.getValue("error"))
            }

            else -> {
                reject(McpStdioFailure.INVALID_JSON_RPC)
            }
        }
    }

    private fun requireJsonRpcVersion(message: JsonObject) {
        val version = message["jsonrpc"] as? JsonPrimitive
        if (version?.contentOrNull != "2.0") reject(McpStdioFailure.INVALID_JSON_RPC)
    }

    private fun validateId(id: JsonElement) {
        val primitive = id as? JsonPrimitive ?: reject(McpStdioFailure.INVALID_JSON_RPC)
        val valid = id !is JsonNull && (primitive.isString || primitive.content.toLongOrNull() != null)
        if (!valid) {
            reject(McpStdioFailure.INVALID_JSON_RPC)
        }
    }

    private fun validateError(error: JsonElement) {
        val value = error as? JsonObject ?: reject(McpStdioFailure.INVALID_JSON_RPC)
        if ((value["code"] as? JsonPrimitive)?.intOrNull == null ||
            (value["message"] as? JsonPrimitive)?.contentOrNull == null
        ) {
            reject(McpStdioFailure.INVALID_JSON_RPC)
        }
    }

    private fun splitFrames(bytes: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] == '\n'.code.toByte()) {
                result += bytes.copyOfRange(start, index)
                start = index + 1
            }
        }
        return result
    }

    private fun decodeUtf8(
        bytes: ByteArray,
        strict: Boolean,
    ): String {
        val decoder = Charsets.UTF_8.newDecoder()
        if (strict) {
            decoder.onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        } else {
            decoder.onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        }
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { reject(McpStdioFailure.INVALID_UTF8) }
    }

    private fun reject(failure: McpStdioFailure): Nothing = throw McpStdioProtocolException(failure)
}

private fun sha256(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
