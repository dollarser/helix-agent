package com.helix.app.goal

import com.helix.core.storage.content.FileContentStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WrittenArtifactContentTest {
    private val text = "实际写入内容\n"
    private val path = "scope:ws:output/result.txt"
    private val args =
        buildJsonObject {
            put("path", JsonPrimitive(path))
            put("content", JsonPrimitive(text))
        }
    private val result =
        buildJsonObject {
            put("path", JsonPrimitive(path))
            put("sizeBytes", JsonPrimitive(text.toByteArray().size))
            put("sha256", JsonPrimitive(FileContentStore.sha256Hex(text.toByteArray())))
        }

    @Test fun extractsExactWrittenBytes() {
        assertArrayEquals(text.toByteArray(), decode(result))
    }

    @Test fun rejectsPathSizeAndHashMismatch() {
        listOf(
            "path" to JsonPrimitive("scope:ws:output/other.txt"),
            "sizeBytes" to JsonPrimitive(1),
            "sha256" to JsonPrimitive("0".repeat(64)),
        ).forEach { (key, value) ->
            assertThrows(IllegalArgumentException::class.java) { decode(JsonObject(result + (key to value))) }
        }
    }

    @Test fun rejectsOtherToolsAndVersions() {
        listOf("edit" to "1", "write" to "2").forEach { (name, version) ->
            assertThrows(IllegalArgumentException::class.java) {
                WrittenArtifactContent.decode(name, version, args.toString(), result.toString())
            }
        }
    }

    @Test fun rejectsMissingInputAndInvalidJson() {
        listOf("{}", "[]", "invalid").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                WrittenArtifactContent.decode("write", "1", input, result.toString())
            }
        }
    }

    private fun decode(output: JsonObject) =
        WrittenArtifactContent.decode("write", "1", args.toString(), output.toString())
}
