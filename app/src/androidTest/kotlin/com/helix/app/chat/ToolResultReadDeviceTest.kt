package com.helix.app.chat

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Real Room/files: paging is a read of verified durable data, never replay of a tool. */
class ToolResultReadDeviceTest {
    @Test fun deletedPayloadFailsWithoutReexecution() =
        withStorage { storage ->
            val result = storage.toolResults.byToolCall("row")!!
            storage.contentStore.delete(
                com.helix.core.storage.content.ContentRef
                    .parse(result.contentRef!!),
            )
            assertFailed(storage, request())
            assertEquals("COMPLETED", storage.toolCalls.byTurnAndCallId("source", "saved")!!.state)
            assertTrue(storage.toolCalls.listByTurn("current").isEmpty())
        }

    @Test fun reopeningPreservesResultsAndSessionOwnership() =
        withStorage { storage ->
            val call = request()
            val output = ToolResultReadTool.read(storage, call) as ToolExecutorResult.Completed
            assertEquals(
                "saved output",
                output.output.jsonObject
                    .getValue("content")
                    .jsonPrimitive.content,
            )
            assertFailed(storage, call.copy(sessionId = "other", turnId = "foreign"))
            assertFailed(storage, call.copy(turnId = "foreign"))
            assertFailed(storage, call.copy(sessionId = null))
            assertEquals(1, storage.toolCalls.listByTurn("source").size)
            assertTrue(storage.toolCalls.listByTurn("current").isEmpty())
        }

    @Test fun unsettledMissingAndUnverifiedResultsAreNotReadable() =
        withStorage { storage ->
            val call = request()
            val source = storage.toolCalls.byTurnAndCallId("source", "saved")!!
            storage.toolCalls.updateState(source, ToolCallState.NEEDS_REVIEW)
            assertFailed(storage, call)
            storage.toolCalls.updateState(source, ToolCallState.COMPLETED)
            storage.toolCalls.append("unverified", "source", "unverified", "time.now", "1", "{}", "COMPLETED")
            storage.toolResults.append("unverified-result", "unverified", "SUCCEEDED", "summary", "secret")
            for (reference in listOf("source/missing", "source/unverified", "foreign/saved", "../saved")) {
                assertFailed(storage, call.copy(args = args(reference)))
            }
        }

    @Test fun cancellationDeadlineAndInvalidOffsetsHaveNoEffects() =
        withStorage { storage ->
            val call = request()
            val cancelled =
                call.copy(
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled() = true
                        },
                )
            assertEquals(ToolExecutorResult.Cancelled, ToolResultReadTool.read(storage, cancelled))
            val expired = call.copy(deadline = Instant.EPOCH)
            assertEquals(ToolExecutorResult.TimedOut, ToolResultReadTool.read(storage, expired))
            assertFailed(storage, call.copy(args = args("source/saved", 100)))
            val saved = storage.toolResults.byToolCall("row")!!
            assertEquals("saved output", storage.toolResults.readContent(saved))
        }

    @Test fun budgetContinuationRequiresLatestFailedTurnAndSettledSession() =
        withStorage { storage ->
            val current =
                storage.turns.updateState(
                    storage.turns.resolve("current"),
                    TurnState.BUILDING_CONTEXT,
                    0,
                    null,
                    null,
                )
            val failed = storage.turns.updateState(current, TurnState.FAILED, 0, 3, "INPUT_TOKEN_LIMIT")
            assertTrue(BudgetContinuation.eligible(storage, failed))
            val source = storage.toolCalls.byTurnAndCallId("source", "saved")!!
            storage.toolCalls.updateState(source, ToolCallState.INTERRUPTED)
            assertFalse(BudgetContinuation.eligible(storage, failed))
            storage.toolCalls.updateState(source, ToolCallState.COMPLETED)
            assertFalse(BudgetContinuation.eligible(storage, failed.copy(errorCode = "NETWORK_ERROR")))
            storage.turns.start("newer", "session", 4)
            assertFalse(BudgetContinuation.eligible(storage, failed))
        }

    private fun assertFailed(
        storage: HelixStorage,
        call: ExecutableToolCall,
    ) {
        assertTrue(ToolResultReadTool.read(storage, call) is ToolExecutorResult.Failed)
    }

    private fun args(
        reference: String = "source/saved",
        offset: Int = 0,
    ) = buildJsonObject {
        put("resultRef", reference)
        put("offset", offset)
    }

    private fun request() =
        ExecutableToolCall(
            "read",
            ToolResultReadTool.NAME,
            "1",
            args(),
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(30),
            NoCancellation,
            "session",
            "current",
        )

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val name = "result-read-${UUID.randomUUID()}.db"
        val files = File(app.filesDir, name)
        var storage = HelixStorage.open(app, name, files)
        try {
            storage.sessions.create("session", "Result read", null, null, 1)
            storage.sessions.create("other", "Other", null, null, 1)
            storage.turns.start("source", "session", 1)
            storage.turns.start("current", "session", 2)
            storage.turns.start("foreign", "other", 1)
            storage.toolCalls.append("row", "source", "saved", "time.now", "1", "{}", "COMPLETED")
            val result = storage.toolResults.append("result", "row", "SUCCEEDED", "summary", "saved output")
            storage.toolResults.markVerified(result)
            storage.close()
            storage = HelixStorage.open(app, name, files)
            block(storage)
        } finally {
            storage.close()
            app.deleteDatabase(name)
            files.deleteRecursively()
        }
    }
}
