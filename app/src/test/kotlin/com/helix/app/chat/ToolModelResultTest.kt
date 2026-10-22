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
        for (name in listOf("bash", "code.linux.run", "code.linux.job.start")) {
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
