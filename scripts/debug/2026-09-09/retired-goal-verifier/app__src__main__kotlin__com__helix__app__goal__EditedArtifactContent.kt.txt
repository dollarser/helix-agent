package com.helix.app.goal

import com.helix.core.agent.ArtifactCriterionCheck
import com.helix.core.storage.content.FileContentStore
import com.helix.core.workspace.FileScopePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Validates host-read full bytes against the acknowledged edit result; never resolves file scope. */
internal data class EditedArtifactContent private constructor(
    val path: FileScopePath,
    val size: Long,
    val sha256: String,
) {
    fun verify(bytes: ByteArray): ByteArray {
        require(bytes.size.toLong() == size) { "edit output size mismatch" }
        require(FileContentStore.sha256Hex(bytes) == sha256) { "edit output hash mismatch" }
        val text =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        require(text.none { it == '\u0000' }) { "edit output contains NUL" }
        return bytes.copyOf()
    }

    companion object {
        fun parse(
            name: String,
            version: String,
            argsJson: String,
            resultJson: String,
        ): EditedArtifactContent {
            require(name == "edit" && version == "1") { "unsupported edit output source" }
            val args = Json.parseToJsonElement(argsJson) as? JsonObject
            val result = Json.parseToJsonElement(resultJson) as? JsonObject
            require(args != null && result != null) { "invalid edit evidence" }
            val path = FileScopePath.fromModelReference(string(args, "path"))
            require(string(result, "path") == path.toModelReference()) { "edit output path mismatch" }
            val sizeField = result["sizeBytes"] as? JsonPrimitive
            val size = sizeField?.takeUnless { it.isString }?.longOrNull
            require(size != null && size in 0..ArtifactCriterionCheck.MAX_CONTENT_BYTES.toLong()) {
                "edit output exceeds evidence limit"
            }
            val hash = string(result, "sha256")
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "invalid edit output hash" }
            return EditedArtifactContent(path, size, hash)
        }

        private fun string(
            value: JsonObject,
            key: String,
        ): String {
            val field = value[key] as? JsonPrimitive
            require(field?.isString == true) { "missing edit field" }
            return field.content
        }
    }
}
