package com.helix.app.chat

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.core.model.SecretAlias
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ToolCallOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.UUID

class ToolInspectionQueryDeviceTest {
    @Test fun originalSessionOnlyRedactsInstalledCredentialsWithoutExecutingOrMutating() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val name = "inspection-${UUID.randomUUID()}.db"
        val root = File(app.filesDir, name)
        val storage = HelixStorage.open(app, name, root)
        val alias = SecretAlias("inspection-fixture-${UUID.randomUUID()}")
        val secret = "synthetic-credential-123456789"
        try {
            storage.secrets.put(alias, secret)
            storage.sessions.create("s", "Synthetic inspector", null, null, 1)
            storage.turns.start("t", "s", 2)
            storage.toolCalls.append("row", "t", "call", "read", "1", "{\"text\":\"$secret\"}", "COMPLETED")
            storage.toolResults.append("result", "row", "SUCCEEDED", "bounded", "{\"body\":\"$secret\"}")
            val query = ToolInspectionQuery(storage)
            val read = requireNotNull(query.read("s", "t", "call"))
            assertFalse(read.arguments.contains(secret))
            assertFalse(requireNotNull(read.result).contains(secret))
            assertEquals("{\"text\":\"$secret\"}", storage.toolCalls.resolve("row").argsJson)
            assertEquals(
                "{\"body\":\"$secret\"}",
                storage.toolResults.readContent(storage.toolResults.resolve("result")),
            )
            assertNull(query.read("other", "t", "call"))
            assertNull(query.read("s", "t", "missing"))
            storage.toolCalls.append("big", "t", "big", "read", "1", "{}", "COMPLETED")
            storage.toolResults.append("big-result", "big", "SUCCEEDED", "bounded", "x".repeat(1_048_577))
            assertThrows(IllegalArgumentException::class.java) { query.read("s", "t", "big") }
            assertEquals(2, storage.toolCalls.listByTurn("t").size)
            assertEquals("COMPLETED", storage.toolCalls.resolve("row").state)
        } finally {
            storage.secrets.delete(alias)
            storage.close()
            app.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    @Test fun repeatedInspectionPreservesInternalMetadataAndAuditAcrossDatabaseReopen() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val name = "inspection-originals-${UUID.randomUUID()}.db"
        val root = File(app.filesDir, name)
        var storage = HelixStorage.open(app, name, root)
        val args = """{"callId":"original-call","snapshotHash":"args-snapshot","path":"notes.txt"}"""
        val result =
            """{"callId":"original-call","snapshotHash":"result-snapshot",""" +
                """"body":"业务原文：call id 和 snapshotHash 是字段名","error":"文件读取失败"}"""
        val auditPayload =
            """{"callId":"original-call","snapshotHash":"audit-snapshot","argsHash":"original-args-hash"}"""
        try {
            storage.sessions.create("s", "Original tool log", null, null, 1)
            val turn = storage.turns.start("t", "s", 2)
            val model =
                storage.modelCalls.append(
                    "model",
                    "t",
                    """{"snapshotHash":"provider-snapshot"}""",
                    "COMPLETED",
                    requestManifest = """{"snapshotHash":"request-snapshot","toolCallId":"original-call"}""",
                )
            val call =
                storage.toolCalls.append(
                    "row",
                    "t",
                    "original-call",
                    "read",
                    "1",
                    args,
                    "FAILED",
                    modelIntent = "读取项目笔记",
                    origin = ToolCallOrigin("model", 0),
                )
            val savedResult = storage.toolResults.append("result", "row", "FAILED", "文件读取失败", result)
            val audit = storage.auditEvents.append("audit", "s", "tool.failed", "SYSTEM", auditPayload, 3)
            storage.close()
            storage = HelixStorage.open(app, name, root)

            // Inspect the actual reopened Room/content-store records, not a presentation fixture.
            inspectRepeatedly(storage, args, result)
            storage.close()
            storage = HelixStorage.open(app, name, root)

            // Inspection must not acknowledge, re-dispatch, sanitize, or rewrite durable evidence.
            assertEquals(listOf(turn), storage.turns.listBySession("s"))
            assertEquals(listOf(model), storage.modelCalls.listByTurn("t"))
            assertEquals(listOf(call), storage.toolCalls.listByTurn("t"))
            assertEquals(savedResult, storage.toolResults.resolve("result"))
            assertEquals(result, storage.toolResults.readContent(storage.toolResults.resolve("result")))
            assertEquals(listOf(audit), storage.auditEvents.listByCorrelation("s"))
            assertEquals(listOf(audit), storage.auditEvents.recent(10))
            assertNull(storage.executions.byToolCall("row"))
        } finally {
            storage.close()
            app.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    private fun inspectRepeatedly(
        storage: HelixStorage,
        args: String,
        result: String,
    ) {
        val query = ToolInspectionQuery(storage)
        repeat(3) {
            val inspected = requireNotNull(query.read("s", "t", "original-call"))
            assertEquals(args, inspected.arguments)
            assertEquals(result, inspected.result)
            assertNull(query.read("other-session", "t", "original-call"))
            assertNull(query.read("s", "t", "missing-call"))
        }
    }
}
