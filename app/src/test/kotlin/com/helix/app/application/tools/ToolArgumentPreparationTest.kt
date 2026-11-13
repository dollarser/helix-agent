package com.helix.app.application.tools

import com.helix.core.model.JsonInputLimits
import com.helix.core.workspace.FileScopePath
import com.helix.tools.files.WriteTool
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentPreparationTest {
    private val directory = FileScopePath("app", "project")

    @Test fun malformedJsonAndNonObjectsDoNotResolveTheDirectory() {
        for (raw in listOf("not json", "[]", "null", "42", "\"text\"", "{\"path\":}")) {
            assertEquals(
                ToolArgumentPreparation.Rejected.INVALID_JSON_OBJECT,
                ToolArgumentPreparation.prepare(raw, WriteTool.descriptor()) { error("must not resolve directory") },
            )
        }
    }

    @Test fun nestingAndNodeLimitsRemainPreExecutionRejections() {
        val deep = "{\"items\":" + "[".repeat(10000) + "0" + "]".repeat(10000) + "}"
        val wide = "{\"items\":[" + List(JsonInputLimits.MAX_NODES) { "0" }.joinToString(",") + "]}"
        for (raw in listOf(deep, wide)) {
            assertEquals(
                ToolArgumentPreparation.Rejected.JSON_LIMIT,
                ToolArgumentPreparation.prepare(raw, WriteTool.descriptor()) { error("must not resolve directory") },
            )
        }
    }

    @Test fun validJsonWithInvalidPathsGetsPathFeedbackWithoutLeakingOrRepairingInput() {
        for (path in listOf("../private-marker", "/sdcard/private-marker", "scope:bad", "x".repeat(513))) {
            val raw = buildJsonObject { put("path", JsonPrimitive(path)) }.toString()
            val result = ToolArgumentPreparation.prepare(raw, WriteTool.descriptor()) { directory }
            assertEquals(ToolArgumentPreparation.Rejected.INVALID_FILE_PATH, result)
            val detail = (result as ToolArgumentPreparation.Rejected).detail
            assertFalse(detail.contains(path))
            assertTrue(detail.contains("relative"))
            assertFalse(detail.contains("valid JSON"))
            assertEquals(raw, FileToolArguments.bindRequest(raw, directory, "write"))
        }
    }

    @Test fun trustedDirectoryFailuresAreNotMisreportedAsModelArgumentErrors() {
        val failure = IllegalArgumentException("trusted directory is unavailable")
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                ToolArgumentPreparation.prepare("{}", WriteTool.descriptor()) { throw failure }
            }
        assertTrue(thrown === failure)
    }

    @Test fun validPathsAreBoundOnceWithoutChangingBusinessValues() {
        val raw = """{"path":"notes.txt","content":"../literal","value":0.123456789012345678901}"""
        val parsed = Json.parseToJsonElement(raw).jsonObject
        val first = ToolArgumentPreparation.prepare(raw, WriteTool.descriptor()) { directory }
        val bound = (first as ToolArgumentPreparation.Ready).args
        assertEquals("scope:app:project/notes.txt", bound.getValue("path").jsonPrimitive.content)
        assertEquals(parsed["content"], bound["content"])
        assertEquals(parsed["value"], bound["value"])
        assertEquals(
            first,
            ToolArgumentPreparation.prepare(bound.toString(), WriteTool.descriptor()) { FileScopePath("other", "new") },
        )
    }

    @Test fun schemaErrorsRemainForDispatcherRatherThanBeingCoercedOrDropped() {
        val descriptor = WriteTool.descriptor()
        for (raw in listOf("", "   ", "{}", """{"path":42,"content":"text"}""")) {
            val prepared = ToolArgumentPreparation.prepare(raw, descriptor) { directory }
            val args = (prepared as ToolArgumentPreparation.Ready).args
            assertEquals(Json.parseToJsonElement(raw.ifBlank { "{}" }), args)
            assertTrue(ToolSchemaValidator.validate(descriptor.inputSchema, args) is ToolSchemaValidation.Invalid)
        }
    }

    @Test fun nonFileToolArgumentsAreNotInterpretedAsPaths() {
        val raw = """{"path":"../literal","nested":"{\"key\":1}"}"""
        val prepared =
            ToolArgumentPreparation.prepare(raw, null) { error("must not resolve directory") }
        assertEquals(Json.parseToJsonElement(raw), (prepared as ToolArgumentPreparation.Ready).args)
    }
}
