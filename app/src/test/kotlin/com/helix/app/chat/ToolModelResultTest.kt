package com.helix.app.chat

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolModelResultTest {
    @Test fun savedExecutionPagesKeepOriginalLogHandleWithoutRepeatingTheAction() {
        for (name in listOf("bash", "code.linux.run", "skills.run", "code.linux.job.start")) {
            val content = "{\"error\":\"${"x".repeat(3000)}\",\"exitCode\":7,\"logTruncated\":true}"
            val page = ToolResultReadTool.page("original/call", content, 0, name)
            assertEquals("original/call", page.getValue("logRef").jsonPrimitive.content)
            assertEquals("code.linux.job.read_log", page.getValue("logReadTool").jsonPrimitive.content)
            assertEquals(7, page.getValue("exitCode").jsonPrimitive.int)
            assertTrue(page.getValue("logTruncated").jsonPrimitive.boolean)
            assertTrue(page.getValue("truncated").jsonPrimitive.boolean)
            assertFalse(
                page
                    .getValue("content")
                    .jsonPrimitive.content
                    .contains("exitCode"),
            )
            assertEquals(
                com.helix.tools.framework.ToolSchemaValidation.Valid,
                com.helix.tools.framework.ToolSchemaValidator
                    .validate(ToolResultReadTool.outputSchema, page),
            )
        }
        assertFalse(ToolResultReadTool.page("t/c", "failure", 0, "files.archive").containsKey("logRef"))
    }

    @Test fun completeResultBodyDoesNotClaimSeparateStreamsWereRead() {
        val body = """{"error":"inspect stderr","exitCode":7,"logTruncated":false}"""
        for (offset in listOf(0, body.length)) {
            val page = ToolResultReadTool.page("t/c", body, offset, "bash")
            assertEquals("result_body", page.getValue("paginationScope").jsonPrimitive.content)
            assertFalse(page.getValue("truncated").jsonPrimitive.boolean)
            assertFalse(page.getValue("logTruncated").jsonPrimitive.boolean)
            val logs = page.getValue("logContent").jsonObject
            assertEquals("absent_from_result_body", logs.getValue("stderr").jsonPrimitive.content)
            assertEquals("absent_from_result_body", logs.getValue("stdout").jsonPrimitive.content)
        }
    }

    @Test fun inlineStreamPreviewsRemainDistinctFromTheSeparateCapture() {
        val body = """{"stdout":"","stderr":"partial","logTruncated":true}"""
        val page = ToolResultReadTool.page("t/c", body, 0, "bash")
        val logs = page.getValue("logContent").jsonObject
        assertEquals("inline_preview", logs.getValue("stdout").jsonPrimitive.content)
        assertEquals("inline_preview", logs.getValue("stderr").jsonPrimitive.content)
        assertEquals(body, page.getValue("content").jsonPrimitive.content)
        val unrelated = ToolResultReadTool.page("t/c", body, 0, "mcp.example")
        assertFalse(unrelated.containsKey("logContent"))
        assertFalse(unrelated.containsKey("logReadTool"))
    }

    @Test fun unrecognizedLogBodiesStayUnknownRatherThanClaimingAbsence() {
        val unknownBodies =
            listOf("not json", "[]", "null", """{"stderr":42,"stdout":false}""", """{"stderr":[],"stdout":{}}""")
        for (content in unknownBodies) {
            val page = ToolResultReadTool.page("t/c", content, 0, "bash")
            val logs = page.getValue("logContent").jsonObject
            assertEquals("unknown", logs.getValue("stderr").jsonPrimitive.content)
            assertEquals("unknown", logs.getValue("stdout").jsonPrimitive.content)
            assertEquals(
                com.helix.tools.framework.ToolSchemaValidation.Valid,
                com.helix.tools.framework.ToolSchemaValidator
                    .validate(ToolResultReadTool.outputSchema, page),
            )
        }
        val nullOrMissing = ToolResultReadTool.page("t/c", """{"stderr":null}""", 0, "bash")
        val absent = nullOrMissing.getValue("logContent").jsonObject
        assertEquals("absent_from_result_body", absent.getValue("stderr").jsonPrimitive.content)
        assertEquals("absent_from_result_body", absent.getValue("stdout").jsonPrimitive.content)
    }

    @Test fun largeResultCanBeReadLosslesslyWithoutRepeatingTheOriginalTool() {
        val payload = "{\"userContent\":\"" + "😀\\\"data".repeat(5000) + "\"}"
        val reference = "turn/call"
        val projected =
            kotlinx.serialization.json.Json
                .parseToJsonElement(
                    ToolModelResult.project("mcp.search", payload, reference),
                ).jsonObject
        assertTrue(projected.getValue("truncated").jsonPrimitive.boolean)
        assertEquals("tools.read_result", projected.getValue("readTool").jsonPrimitive.content)
        var offset = 0
        val restored = StringBuilder()
        do {
            val page = ToolResultReadTool.page(reference, payload, offset)
            assertEquals(
                com.helix.tools.framework.ToolSchemaValidation.Valid,
                com.helix.tools.framework.ToolSchemaValidator
                    .validate(ToolResultReadTool.outputSchema, page),
            )
            assertTrue(page.toString().toByteArray().size < 16_384)
            restored.append(page.getValue("content").jsonPrimitive.content)
            offset = page["nextOffset"]?.jsonPrimitive?.int ?: -1
        } while (offset >= 0)
        assertEquals(payload, restored.toString())
        assertEquals(payload, ToolModelResult.project("mcp.search", payload))
    }

    @Test fun javascriptResultIsDecodedOnlyAtItsKnownEnvelopeBoundary() {
        assertEquals(
            """{"result":{"status":"user data","values":[1,2]}}""",
            ToolModelResult.project(
                "code.javascript.run",
                """{"outputBytes":30,"result":"{\"status\":\"user data\",\"values\":[1,2]}"}""",
            ),
        )
        val opaque = """{"result":"not json","outputBytes":8}"""
        assertEquals(opaque, ToolModelResult.project("code.javascript.run", opaque))
        org.junit.Assert.assertThrows(
            IllegalArgumentException::class.java,
        ) { ToolResultReadTool.page("t/c", "abc", -1) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ToolResultReadTool.page("t/c", "abc", 4) }
    }

    @Test fun writesKeepVersionAndOutcomeButOmitAccounting() {
        val payload =
            """{"path":"scope:ws:a","sha256":"hash","overwritten":true,"usageBytesAfter":50,""" +
                """"encoding":"utf-8","mimeType":"text/plain"}"""
        val model = ToolModelResult.project("write", payload)
        assertTrue(model.contains("sha256"))
        assertTrue(model.contains("overwritten"))
        assertFalse(model.contains("usageBytesAfter"))
        assertFalse(model.contains("encoding"))
        assertTrue(payload.contains("usageBytesAfter"))
    }

    @Test fun readsExternalToolsAndUnrecognizedFormatsRemainLossless() {
        val payload = """{"content":{"usageBytesAfter":"user text"},"sha256":"hash","truncated":true,"next":123}"""
        for (name in listOf("read", "browser.snapshot", "mcp.server.write", "future.tool")) {
            assertEquals(payload, ToolModelResult.project(name, payload))
        }
        assertEquals("not json", ToolModelResult.project("write", "not json"))
        assertEquals(payload, ToolModelResult.project("write", payload))
    }

    @Test fun deletionPreservesRecoveryReference() {
        assertEquals(
            """{"trashRef":"ref","sizeBytes":9}""",
            ToolModelResult.project("files.delete", """{"trashRef":"ref","sizeBytes":9,"usageBytesAfter":99}"""),
        )
    }
}
