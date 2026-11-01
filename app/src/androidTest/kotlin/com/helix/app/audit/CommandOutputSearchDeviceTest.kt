package com.helix.app.audit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated Room and synthetic output only; no Runtime process or real conversation is touched. */
class CommandOutputSearchDeviceTest {
    @Test
    fun paginatesSavedOutputAndReportsCorruptionWithoutExecuting(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "output-search-${UUID.randomUUID()}.db"
            val content = File(context.cacheDir, "$name/content")
            val storage = HelixStorage.open(context, name, content)
            try {
                storage.sessions.create("synthetic", "Synthetic output", null, null, 1)
                storage.turns.start("turn", "synthetic", 1)
                repeat(55) { index ->
                    val id = "call-$index"
                    storage.toolCalls.append(id, "turn", id, "bash", "1", "{}", "COMPLETED")
                    val result =
                        storage.toolResults.append(
                            "result-$index",
                            id,
                            "SUCCEEDED",
                            "Synthetic output",
                            """{"state":"SUCCEEDED","exitCode":0,"stdout":"MARKER-$index","stderr":""}""",
                        )
                    if (index != 0) storage.toolResults.markVerified(result)
                }
                val first = CommandOutputSearch.find(storage, "marker", null)
                val second = CommandOutputSearch.find(storage, "marker", requireNotNull(first.next))
                val matches = first.matches + second.matches
                assertEquals(50, first.scanned)
                assertEquals(5, second.scanned)
                assertEquals(54, matches.size)
                assertEquals(54, matches.map { it.callId }.toSet().size)
                assertFalse(matches.any { it.callId == "call-0" })
                assertTrue(first.limited || second.limited)
                assertEquals(null, second.next)
                assertEquals(55, storage.toolCalls.listByTurn("turn").size)
                assertEquals("CREATED", storage.turns.resolve("turn").state)
                val result = requireNotNull(storage.toolResults.byToolCall("call-1"))
                val ref =
                    com.helix.core.storage.content.ContentRef
                        .parse(requireNotNull(result.contentRef))
                File(content, ref.relativePath).writeText("synthetic corruption")
                val corrupted =
                    com.helix.app.proot.CommandResultBrowser
                        .browseSync(storage, "turn", "call-1")
                assertEquals(com.helix.app.proot.CommandDetailState.READ_FAILED, corrupted?.state)
                val after = CommandOutputSearch.find(storage, "marker", null)
                val older = CommandOutputSearch.find(storage, "marker", requireNotNull(after.next))
                assertEquals(53, after.matches.size + older.matches.size)
                assertTrue(after.limited || older.limited)
            } finally {
                storage.close()
                context.deleteDatabase(name)
                content.parentFile?.deleteRecursively()
            }
        }
}
