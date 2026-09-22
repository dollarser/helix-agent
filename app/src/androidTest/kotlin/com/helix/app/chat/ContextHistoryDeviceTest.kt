package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ContextCapacityException
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.ContextHistory
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ContextHistoryDeviceTest {
    @Test fun pagedHistoryPreservesPinnedRowsAndSystemMessagesAcrossCheckpoint() =
        withStorage { storage ->
            repeat(600) { index ->
                val role = if (index == 0) "SYSTEM" else "USER"
                storage.messages.append("m-$index", "s", null, role, "TEXT", "text-$index")
            }
            val checkpointJson = """{"coveredThrough":550,"summary":"notes","preservedMessageIds":["m-100"]}"""
            storage.messages.append("cp", "s", null, "ASSISTANT", ContextCompaction.KIND, checkpointJson)
            val snapshot = ContextHistory.load(storage, "s")
            assertEquals(listOf("m-0", "m-100") + (551..599).map { "m-$it" }, snapshot.rows.map { it.id })
            assertEquals(601, storage.messages.listBySession("s").size)
            assertEquals("notes", snapshot.checkpoint?.summary)
            assertEquals(
                snapshot.rows,
                ContextCompaction.retained(storage.messages.listBySession("s"), snapshot.checkpoint),
            )
        }

    @Test fun oversizedRetainedContentFailsBeforeReadingItsBodyAndKeepsHistory() =
        withStorage { storage ->
            storage.messages.append("large", "s", null, "USER", "TEXT", "a".repeat(ContextHistory.MAX_BODY_BYTES + 1))
            val failure = assertThrows(ContextCapacityException::class.java) { ContextHistory.load(storage, "s") }
            assertEquals("CONTEXT_MATERIALIZATION_LIMIT", failure.code)
            assertEquals(1, storage.messages.listBySession("s").size)
            assertTrue(storage.messages.resolve("large").contentRef != null)
        }

    @Test fun coveredLargeBodyDoesNotConsumeTheActiveHistoryBudget() =
        withStorage { storage ->
            storage.messages.append("large", "s", null, "USER", "TEXT", "a".repeat(ContextHistory.MAX_BODY_BYTES + 1))
            storage.messages.append(
                "cp",
                "s",
                null,
                "ASSISTANT",
                ContextCompaction.KIND,
                """{"coveredThrough":0,"summary":"archived"}""",
            )
            storage.messages.append("new", "s", null, "USER", "TEXT", "continue")
            assertEquals(listOf("new"), ContextHistory.load(storage, "s").rows.map { it.id })
        }

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "context-history-${UUID.randomUUID()}.db"
        val directory = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, directory)
        try {
            storage.sessions.create("s", "History fixture", null, null, 1000)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }
}
