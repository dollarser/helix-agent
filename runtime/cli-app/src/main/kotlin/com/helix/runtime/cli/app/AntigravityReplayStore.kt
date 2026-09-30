package com.helix.runtime.cli.app

import com.helix.core.model.ModelMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Retain signed provider parts privately; signatures are data, never tool arguments or authorization. */
internal class AntigravityReplayStore(
    private val directory: File,
) {
    fun save(
        model: String,
        revision: String,
        message: ModelMessage,
        parts: JsonArray,
    ) = synchronized(LOCK) {
        if (message.toolCalls.isEmpty()) return@synchronized
        check(directory.isDirectory || directory.mkdirs())
        val file = path(message)
        val text =
            buildJsonObject {
                put("binding", binding(model, revision, message))
                put("partsHash", hash(parts.toString()))
                put("parts", parts)
            }.toString().toByteArray(Charsets.UTF_8)
        require(text.size <= MAX_BYTES)
        val temporary = File.createTempFile("replay-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use {
                it.write(text)
                it.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
        directory
            .listFiles { item -> item.name.endsWith(".json") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(MAX_RECORDS)
            .forEach { it.delete() }
    }

    fun read(
        model: String,
        revision: String,
        message: ModelMessage,
    ): JsonArray? =
        synchronized(LOCK) {
            if (message.toolCalls.none { it.id.value.startsWith("agy_") }) return@synchronized null
            val file = path(message)
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Signed response unavailable" }
            val bytes =
                Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= MAX_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            require(bytes.size <= MAX_BYTES)
            val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            require(root["binding"]?.jsonPrimitive?.content == binding(model, revision, message)) {
                "Signed response belongs to another model, account, or message"
            }
            require(root.keys == setOf("binding", "parts", "partsHash"))
            val parts = root["parts"] as JsonArray
            require(root["partsHash"]?.jsonPrimitive?.content == hash(parts.toString()))
            // The persisted tool-call row is textless; visible TEXT is a separate row in ChatHistoryBuilder.
            require(message.text.isEmpty() || message.text == antigravityVisibleText(parts))
            val functions = parts.mapNotNull { it.jsonObject["functionCall"] as? kotlinx.serialization.json.JsonObject }
            require(functions.size == message.toolCalls.size)
            functions.zip(message.toolCalls).forEach { (function, call) ->
                require(function["name"]?.jsonPrimitive?.content == AntigravityRequest.wireName(call.name.value))
                require(function["args"] == Json.parseToJsonElement(call.argumentsJson))
            }
            parts
        }

    private fun path(message: ModelMessage): File =
        File(
            directory,
            hash(
                message.toolCalls
                    .first()
                    .id.value,
            ) + ".json",
        )

    private fun binding(
        model: String,
        revision: String,
        message: ModelMessage,
    ): String =
        hash(
            buildJsonObject {
                put("model", model)
                put("revision", revision)
                put(
                    "calls",
                    JsonArray(
                        message.toolCalls.map { call ->
                            buildJsonObject {
                                put("id", call.id.value)
                                put("name", call.name.value)
                                put("args", Json.parseToJsonElement(call.argumentsJson))
                            }
                        },
                    ),
                )
            }.toString(),
        )

    private fun hash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val LOCK = Any()
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_RECORDS = 128
    }
}
