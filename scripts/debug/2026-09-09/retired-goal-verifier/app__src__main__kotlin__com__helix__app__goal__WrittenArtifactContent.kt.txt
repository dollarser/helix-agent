package com.helix.app.goal

import com.helix.core.agent.ArtifactCriterionCheck
import com.helix.core.storage.content.FileContentStore
import com.helix.core.workspace.FileScopePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Exact UTF-8 bytes acknowledged by the built-in write v1 result, not a claim about the live file. */
internal object WrittenArtifactContent {
    fun decode(
        name: String,
        version: String,
        argsJson: String,
        resultJson: String,
    ): ByteArray {
        require(name == "write" && version == "1") { "unsupported output source" }
        val args =
            Json.parseToJsonElement(argsJson) as? JsonObject ?: throw IllegalArgumentException("invalid write input")
        val result =
            Json.parseToJsonElement(resultJson) as? JsonObject ?: throw IllegalArgumentException("invalid write result")
        val path = FileScopePath.fromModelReference(string(args, "path")).toModelReference()
        require(string(result, "path") == path) { "write output path mismatch" }
        val content = string(args, "content")
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= ArtifactCriterionCheck.MAX_CONTENT_BYTES) { "write output exceeds evidence limit" }
        require(bytes.toString(Charsets.UTF_8) == content) { "write output is not strict UTF-8" }
        require((result["sizeBytes"] as? JsonPrimitive)?.longOrNull == bytes.size.toLong()) { "write size mismatch" }
        require(string(result, "sha256") == FileContentStore.sha256Hex(bytes)) { "write hash mismatch" }
        return bytes
    }

    private fun string(
        value: JsonObject,
        key: String,
    ): String {
        val primitive = value[key] as? JsonPrimitive
        require(primitive?.isString == true) { "missing write field" }
        return primitive.content
    }
}
