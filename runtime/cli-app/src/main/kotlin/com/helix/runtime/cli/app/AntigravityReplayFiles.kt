package com.helix.runtime.cli.app

import com.helix.runtime.cli.client.CliReplayMaintenance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Bounded reads are shared by replay and maintenance; malformed evidence is never disposable. */
internal object AntigravityReplayFiles {
    fun bytes(file: File): ByteArray {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Replay is not a regular file" }
        return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= AntigravityReplayStore.MAX_BYTES) {
                val count =
                    input.read(
                        buffer,
                        0,
                        minOf(
                            buffer.size,
                            AntigravityReplayStore.MAX_BYTES + 1 - output.size(),
                        ),
                    )
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            require(output.size() <= AntigravityReplayStore.MAX_BYTES) { "Replay exceeds record limit" }
            output.toByteArray()
        }
    }

    fun read(file: File): JsonObject = decode(bytes(file))

    fun decode(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject.also(::validate)

    fun validate(root: JsonObject) {
        val required = setOf("binding", "parts", "partsHash")
        require(root.keys == required || root.keys == required + "owner")
        require(root.getValue("binding").jsonPrimitive.isString)
        CliReplayMaintenance.requireHash(root.getValue("binding").jsonPrimitive.content)
        val parts = root.getValue("parts") as JsonArray
        require(root.getValue("partsHash").jsonPrimitive.content == CliReplayMaintenance.hash(parts.toString()))
        root["owner"]?.jsonPrimitive?.let {
            require(it.isString)
            CliReplayMaintenance.requireOwner(it.content)
        }
    }
}
