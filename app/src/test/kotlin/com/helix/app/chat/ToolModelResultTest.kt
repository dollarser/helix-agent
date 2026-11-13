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
    @Test fun knownEnvelopeIsReducedBeforeApplyingTheInlineBudget() {
        val data = "\"".repeat(9000)
        val inner =
            kotlinx.serialization.json
                .JsonPrimitive(data)
                .toString()
        val original =
            kotlinx.serialization.json
                .buildJsonObject {
                    put("result", kotlinx.serialization.json.JsonPrimitive(inner))
                    put("outputBytes", kotlinx.serialization.json.JsonPrimitive(inner.toByteArray().size))
                }.toString()
        assertTrue(original.toByteArray().size > 32_768)
        val projected = ToolModelResult.project("code.javascript.run", original, "t/c")
        val body =
            kotlinx.serialization.json.Json
                .parseToJsonElement(projected)
                .jsonObject
        assertEquals(data, body.getValue("result").jsonPrimitive.content)
        assertFalse(body.containsKey("truncated"))
        assertFalse(body.containsKey("outputBytes"))
    }

    @Test fun stillLargeDecodedResultsKeepOffsetsIntoOriginalSavedBody() {
        val original =
            kotlinx.serialization.json
                .buildJsonObject {
                    put("result", kotlinx.serialization.json.JsonPrimitive("\"" + "x".repeat(40_000) + "\""))
                    put("outputBytes", kotlinx.serialization.json.JsonPrimitive(40_002))
                }.toString()
        val projected =
            kotlinx.serialization.json.Json
                .parseToJsonElement(
                    ToolModelResult.project("code.javascript.run", original, "t/c"),
                ).jsonObject
        val preview = projected.getValue("preview").jsonPrimitive.content
        val offset = projected.getValue("nextOffset").jsonPrimitive.int
        assertEquals(original.take(offset), preview)
        assertEquals(
            original.substring(offset, offset + 2048),
            ToolResultReadTool
                .page("t/c", original, offset)
                .getValue("content")
                .jsonPrimitive.content,
        )
    }

    @Test fun largeProjectionPreservesSourceCutAndUnicodeDiagnosticBoundary() {
        val error = "x" + "😀".repeat(600)
        val payload =
            kotlinx.serialization.json
                .buildJsonObject {
                    put("error", kotlinx.serialization.json.JsonPrimitive(error))
                    put("truncated", kotlinx.serialization.json.JsonPrimitive(true))
                    put("status", kotlinx.serialization.json.JsonPrimitive("UNKNOWN"))
                    put("data", kotlinx.serialization.json.JsonPrimitive("x".repeat(40_000)))
                }.toString()
        val projected =
            kotlinx.serialization.json.Json
                .parseToJsonElement(
                    ToolModelResult.project("future.tool", payload, "t/c"),
                ).jsonObject
        assertTrue(projected.getValue("sourceTruncated").jsonPrimitive.boolean)
        assertEquals("UNKNOWN", projected.getValue("status").jsonPrimitive.content)
        assertEquals(
            error.substring(0, error.offsetByCodePoints(0, 512)) + "…",
            projected.getValue("error").jsonPrimitive.content,
        )
    }

    @Test fun fileMutationsRemoveOnlyTopLevelAccountingAndPreserveOutcome() {
        val payload =
            """{"usageBytesAfter":100,"sha256":"version","overwritten":true,"trashRef":"recovery",""" +
                """"content":{"usageBytesAfter":5}}"""
        for (name in listOf(
            "write",
            "edit",
            "files.copy",
            "files.move",
            "files.delete",
            "files.archive",
            "files.extract",
        )) {
            val projected =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(
                        ToolModelResult.project(name, payload),
                    ).jsonObject
            assertFalse(projected.containsKey("usageBytesAfter"))
            assertEquals("version", projected.getValue("sha256").jsonPrimitive.content)
            assertTrue(projected.getValue("overwritten").jsonPrimitive.boolean)
            assertEquals("recovery", projected.getValue("trashRef").jsonPrimitive.content)
            assertEquals(
                5,
                projected
                    .getValue("content")
                    .jsonObject
                    .getValue("usageBytesAfter")
                    .jsonPrimitive.int,
            )
        }
    }

    @Test fun contentAndExecutionToolsNeverDropStreamsOrMatchingUserFields() {
        val payload =
            """{"stdout":"essential answer","stderr":"warning","exitCode":7,"usageBytesAfter":12,""" +
                """"outputBytes":50,"error":"diagnosis","text":"file body"}"""
        for (name in listOf(
            "read",
            "grep",
            "glob",
            "ui.snapshot",
            "bash",
            "code.linux.run",
            "skills.run",
            "code.linux.job.start",
            "mcp.server.write",
        )) {
            assertEquals(payload, ToolModelResult.project(name, payload, "t/c"))
        }
    }

    @Test fun inlineLimitIsExactly32KiBUtf8AcrossToolsAndUnicodeWidths() {
        for (unit in listOf("a", "中", "😀")) {
            for (size in listOf(16_384, 18_000, 32_767, 32_768, 32_769)) {
                val width = unit.toByteArray().size
                val payload = unit.repeat(size / width) + "a".repeat(size % width)
                assertEquals(size, payload.toByteArray().size)
                for (tool in listOf("ui.snapshot", "mcp.search", "future.tool")) {
                    assertInlineBoundary(tool, payload, size)
                }
            }
        }
    }

    private fun assertInlineBoundary(
        tool: String,
        payload: String,
        size: Int,
    ) {
        val result = ToolModelResult.project(tool, payload, "turn/call")
        if (size <= 32_768) {
            assertEquals(payload, result)
        } else {
            val projected =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(result)
                    .jsonObject
            assertTrue(projected.getValue("truncated").jsonPrimitive.boolean)
            val preview = projected.getValue("preview").jsonPrimitive.content
            val nextOffset = projected.getValue("nextOffset").jsonPrimitive.int
            assertEquals(2048, preview.codePointCount(0, preview.length))
            assertFalse(preview.last().isHighSurrogate())
            assertEquals(payload.substring(0, payload.offsetByCodePoints(0, nextOffset)), preview)
        }
    }

    @Test fun missingReadCapabilityPreservesFullBodyAndReadResultsAreNeverReprojected() {
        val payload = "😀".repeat(10_000)
        assertEquals(payload, ToolModelResult.project("ui.snapshot", payload))
        assertEquals(payload, ToolModelResult.project(ToolResultReadTool.NAME, payload, "turn/call"))
    }

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
        assertEquals("result_body", projected.getValue("paginationScope").jsonPrimitive.content)
        assertEquals(ToolResultReadTool.PAGINATION_HINT, projected.getValue("readHint").jsonPrimitive.content)
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
            if (page.getValue("truncated").jsonPrimitive.boolean) {
                assertEquals(ToolResultReadTool.PAGINATION_HINT, page.getValue("readHint").jsonPrimitive.content)
                assertTrue(page.getValue("nextOffset").jsonPrimitive.int > offset)
            } else {
                assertFalse(page.containsKey("readHint"))
                assertFalse(page.containsKey("nextOffset"))
            }
            restored.append(page.getValue("content").jsonPrimitive.content)
            offset = page["nextOffset"]?.jsonPrimitive?.int ?: -1
        } while (offset >= 0)
        assertEquals(payload, restored.toString())
        assertEquals(payload, ToolModelResult.project("mcp.search", payload))
    }

    @Test fun largerResultPagesAreBoundedAndUnicodeLossless() {
        for (payload in listOf("😀".repeat(9000), "\u0001".repeat(9000), "中".repeat(9000))) {
            val first = ToolResultReadTool.page("t/c", payload, 0, maxCharacters = 8192)
            val content = first.getValue("content").jsonPrimitive.content
            assertEquals(8192, content.codePointCount(0, content.length))
            assertEquals(8192, first.getValue("nextOffset").jsonPrimitive.int)
            assertTrue(first.toString().toByteArray().size < 65_536)
            assertEquals(
                com.helix.tools.framework.ToolSchemaValidation.Valid,
                com.helix.tools.framework.ToolSchemaValidator
                    .validate(ToolResultReadTool.outputSchema, first),
            )
            val last = ToolResultReadTool.page("t/c", payload, 8192, maxCharacters = 8192)
            assertFalse(last.getValue("truncated").jsonPrimitive.boolean)
            assertEquals(payload, content + last.getValue("content").jsonPrimitive.content)
        }
        assertEquals(
            "a",
            ToolResultReadTool
                .page("t/c", "abc", 0, maxCharacters = 1)
                .getValue("content")
                .jsonPrimitive.content,
        )
        for (limit in listOf(0, -1, 8193, Int.MAX_VALUE)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                ToolResultReadTool.page("t/c", "abc", 0, maxCharacters = limit)
            }
        }
    }

    @Test fun exactEndAndLargeReadResultRemainTruthful() {
        val payload = "\u0001".repeat(8192)
        val page = ToolResultReadTool.page("r".repeat(260), payload, 0, maxCharacters = 8192)
        assertFalse(page.getValue("truncated").jsonPrimitive.boolean)
        assertFalse(page.containsKey("nextOffset"))
        assertTrue(page.toString().toByteArray().size < 65_536)
        assertEquals(page.toString(), ToolModelResult.project("tools.read_result", page.toString(), "t/c"))
        val empty = ToolResultReadTool.page("t/c", payload, 8192, maxCharacters = 8192)
        assertEquals("", empty.getValue("content").jsonPrimitive.content)
        assertFalse(empty.getValue("truncated").jsonPrimitive.boolean)
        assertFalse(empty.containsKey("nextOffset"))
    }

    @Test fun finishingResultPagesPreservesSourceTruncationAndDoesNotRefreshTokens() {
        val payload = """{"truncated":true,"token":"original-token","items":"${"x".repeat(5000)}"}"""
        val restored = StringBuilder()
        var offset = 0
        do {
            val page = ToolResultReadTool.page("turn/call", payload, offset, "ui.snapshot")
            restored.append(page.getValue("content").jsonPrimitive.content)
            offset = page["nextOffset"]?.jsonPrimitive?.int ?: -1
        } while (offset >= 0)
        assertEquals(payload, restored.toString())
        val source =
            kotlinx.serialization.json.Json
                .parseToJsonElement(restored.toString())
                .jsonObject
        assertTrue(source.getValue("truncated").jsonPrimitive.boolean)
        assertEquals("original-token", source.getValue("token").jsonPrimitive.content)
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
