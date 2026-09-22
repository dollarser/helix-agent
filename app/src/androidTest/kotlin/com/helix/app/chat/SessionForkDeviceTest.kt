package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.ContextHistory
import com.helix.app.agent.ContextSegments
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.MessageAttachmentRepository
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SessionForkDeviceTest {
    @Test fun prefixIsIndependentAndUsesNewSessionDefaultsWithoutExecutionRows() =
        withStorage { storage, _ ->
            val permissions = SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS)
            storage.sessionPermissionConfigs.setForSession("s", permissions, 1)
            storage.sessions.updateDetails("s", "Source", "scope:app:original")
            text(storage, "first", "USER", "original question")
            text(storage, "answer", "ASSISTANT", "original answer")
            text(storage, "future", "USER", "future secret")
            val originals = storage.messages.listBySession("s")
            fork(storage, "answer")
            val copied = ContextHistory.load(storage, "fork").rows
            val bodies = copied.map { storage.messages.readContent(it) }
            assertEquals(listOf("original question", "original answer"), bodies)
            assertTrue(copied.all { it.turnId == null && it.id !in originals.map { row -> row.id } })
            assertEquals(originals.take(2).map { it.contentRef }, copied.map { it.contentRef })
            assertEquals(SessionPermissionMode.READ_ONLY, storage.sessionPermissionConfigs.forSession("fork")?.mode)
            assertNull(storage.sessions.resolve("fork").directoryRef)
            assertTrue(storage.turns.listBySession("fork").isEmpty())
            assertEquals(originals, storage.messages.listBySession("s"))
            assertEquals(1, storage.auditEvents.listByCorrelation("fork").count { it.type == "session.fork" })
        }

    @Test fun checkpointBeforeBoundaryIsRebasedButLaterSummaryCannotLeak() =
        withStorage { storage, _ ->
            text(storage, "old", "USER", "old raw")
            text(storage, "keep", "USER", "pinned constraint")
            storage.messages.append(
                "cp",
                "s",
                null,
                "ASSISTANT",
                ContextCompaction.KIND,
                """{"coveredThrough":1,"summary":"past summary","preservedMessageIds":["keep"]}""",
            )
            text(storage, "target", "ASSISTANT", "branch here")
            text(storage, "later", "USER", "future detail")
            storage.messages.append(
                "future-cp",
                "s",
                null,
                "ASSISTANT",
                ContextCompaction.KIND,
                """{"coveredThrough":4,"summary":"FUTURE SUMMARY"}""",
            )
            fork(storage, "target")
            val snapshot = ContextHistory.load(storage, "fork")
            assertEquals("past summary", snapshot.checkpoint?.summary)
            val bodies = snapshot.rows.map { storage.messages.readContent(it) }
            assertEquals(listOf("pinned constraint", "branch here"), bodies)
            assertTrue(snapshot.checkpoint?.sourceCallId == null)
            SessionFork(storage).create("fork", snapshot.rows.last().id, "nested", "Nested", 3)
            assertEquals("past summary", ContextHistory.load(storage, "nested").checkpoint?.summary)
            SessionFork(storage).create("s", "keep", "earlier", "Earlier", 2)
            assertNull(ContextHistory.load(storage, "earlier").checkpoint)
            assertEquals(2, ContextHistory.load(storage, "earlier").rows.size)
        }

    @Test fun completeToolsStayPairedAndIncompleteBatchRetreatsToPreviousMessage() =
        withStorage { storage, _ ->
            text(storage, "user", "USER", "question")
            val calls =
                """[{"id":"call-a","name":"read","arguments":"{}"},""" +
                    """{"id":"call-b","name":"read","arguments":"{}"}]"""
            storage.messages.append("calls", "s", null, "ASSISTANT", "TOOL_CALLS", calls)
            storage.messages.append(
                "result-a",
                "s",
                null,
                "TOOL",
                "TOOL_RESULT",
                """{"id":"call-a","tool":"read","status":"SUCCEEDED","summary":"a"}""",
            )
            fork(storage, "result-a")
            val partial = ContextHistory.load(storage, "fork").rows.map { storage.messages.readContent(it) }
            assertEquals(listOf("question"), partial)
            storage.messages.append(
                "result-b",
                "s",
                null,
                "TOOL",
                "TOOL_RESULT",
                """{"id":"call-b","tool":"read","status":"SUCCEEDED","summary":"b"}""",
            )
            SessionFork(storage).create("s", "result-b", "complete", "Complete", 2)
            val rows = ContextHistory.load(storage, "complete").rows
            val messages =
                ChatHistoryBuilder.toModelMessagesStrict(
                    rows.map {
                        ChatHistoryBuilder.PersistedRow(null, it.role, it.kind, storage.messages.readContent(it))
                    },
                )
            assertEquals(listOf("call-a", "call-b"), messages[1].toolCalls.map { it.id.value })
            assertEquals(listOf("call-a", "call-b"), messages.drop(2).map { it.toolCallId?.value })
            assertTrue(storage.turns.listBySession("complete").isEmpty())
        }

    @Test fun damagedLaterHistoryDoesNotPreventBranchingBeforeIt() =
        withStorage { storage, _ ->
            text(storage, "good", "USER", "recover here")
            storage.messages.append("bad", "s", null, "ASSISTANT", "TOOL_CALLS", "broken json")
            assertThrows(IllegalArgumentException::class.java) { fork(storage, "bad") }
            assertEquals(listOf("s"), storage.sessions.list().map { it.id })
            fork(storage, "good")
            val saved = ContextHistory.load(storage, "fork").rows.single()
            assertEquals("recover here", storage.messages.readContent(saved))
        }

    @Test fun attachmentBindingsKeepTheirSnapshotAndHash() =
        withStorage { storage, directory ->
            text(storage, "attached", "USER", "attachment")
            val file = File(directory, "fixture.txt").apply { writeText("snapshot") }
            val hash = FileContentStore.sha256Hex(file)
            storage.artifacts.register(
                "artifact",
                "s",
                "scope:app:fixture.txt",
                "text/plain",
                file.length(),
                hash,
                file,
            )
            val bindingSource = MessageAttachmentRepository.Binding("artifact", "document", hash)
            storage.messageAttachments.bind("attached", listOf(bindingSource))
            fork(storage, "attached")
            val copied = ContextHistory.load(storage, "fork").rows.single()
            val binding = storage.messageAttachments.listByMessage(copied.id).single()
            assertTrue("artifact" != binding.artifactId)
            assertEquals("fork", storage.artifacts.resolve(binding.artifactId).sessionId)
            assertEquals(hash, binding.boundSha256)
            assertEquals(0, binding.ordinal)
            val erased = storage.deleteSessionPermanently("s")
            assertTrue(erased.unreferencedWorkspacePaths.isEmpty())
            assertEquals(1, storage.messageAttachments.listByMessage(copied.id).size)
            assertEquals("attachment", storage.messages.readContent(copied))
        }

    @Test fun cancellationDuringCopyRollsBackSessionPermissionsAndMessages() =
        withStorage { storage, _ ->
            repeat(10) { text(storage, "m-$it", "USER", "message $it") }
            assertThrows(CancellationException::class.java) {
                SessionFork(storage).create("s", "m-9", "fork", "Branch", 2) {
                    if (storage.messages.listBySession("fork").size == 3) {
                        throw CancellationException("fixture")
                    }
                }
            }
            assertEquals(listOf("s"), storage.sessions.list().map { it.id })
            assertTrue(storage.messages.listBySession("fork").isEmpty())
            assertNull(storage.sessionPermissionConfigs.forSession("fork"))
            assertTrue(storage.auditEvents.listByCorrelation("fork").isEmpty())
        }

    @Test fun oversizedActiveBodyAndWrongSessionAreRejectedWithoutPartialSession() =
        withStorage { storage, _ ->
            text(storage, "large", "USER", "a".repeat(ContextHistory.MAX_BODY_BYTES + 1))
            assertThrows(IllegalArgumentException::class.java) { fork(storage, "large") }
            storage.sessions.create("other", "Other", null, null, 1)
            assertThrows(IllegalArgumentException::class.java) {
                SessionFork(storage).create("other", "large", "fork", "Branch", 2)
            }
            assertFalse(storage.sessions.list().any { it.id == "fork" })
        }

    @Test fun inheritedHistoryRemainsCompactableAndNestedForkSurvivesDatabaseReopen() =
        withStorage { storage, _ ->
            repeat(4) { text(storage, "m-$it", if (it % 2 == 0) "USER" else "ASSISTANT", "message $it") }
            fork(storage, "m-3")
            val rows = ContextHistory.load(storage, "fork").rows
            assertEquals(3, ContextSegments.candidates(storage, rows, "new-turn").flatten().size)
            SessionFork(storage).create("fork", rows[1].id, "nested", "Nested", 3)
            assertEquals(2, ContextHistory.load(storage, "nested").rows.size)
            assertEquals(1, storage.messages.listBySession("nested").count { it.kind == SessionForkPlan.KIND })
        }

    @Test fun compactionKeepsLatestInheritedToolBatchAndItsTail() =
        withStorage { storage, _ ->
            text(storage, "user", "USER", "original question")
            listOf("a", "b").forEach { id ->
                val calls = """[{"id":"$id","name":"read","arguments":"{}"}]"""
                val result = """{"id":"$id","tool":"read","status":"SUCCEEDED","summary":"$id"}"""
                storage.messages.append("calls-$id", "s", null, "ASSISTANT", "TOOL_CALLS", calls)
                storage.messages.append("result-$id", "s", null, "TOOL", "TOOL_RESULT", result)
            }
            text(storage, "tail", "ASSISTANT", "latest result explanation")
            fork(storage, "tail")
            val history = ContextHistory.load(storage, "fork").rows
            val eligible = ContextSegments.candidates(storage, history, "new-turn").flatten()
            assertEquals(history.take(3), eligible)
            assertEquals(history.drop(3), history.filter { it !in eligible })
        }

    @Test fun missingToolBodyCannotBeSilentlyImportedAsCompleteHistory() =
        withStorage { storage, _ ->
            text(storage, "good", "USER", "safe prefix")
            storage.messages.append("empty-call", "s", null, "ASSISTANT", "TOOL_CALLS", "")
            assertThrows(IllegalArgumentException::class.java) { fork(storage, "empty-call") }
            assertEquals(listOf("s"), storage.sessions.list().map { it.id })
            fork(storage, "good")
            assertEquals(1, ContextHistory.load(storage, "fork").rows.size)
        }

    private fun fork(
        storage: HelixStorage,
        message: String,
    ) = SessionFork(storage).create("s", message, "fork", "Branch", 2)

    private fun text(
        storage: HelixStorage,
        id: String,
        role: String,
        body: String,
    ) = storage.messages.append(id, "s", null, role, "TEXT", body)

    private fun withStorage(block: (HelixStorage, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fork-${UUID.randomUUID()}.db"
        val directory = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, directory)
        try {
            storage.sessions.create("s", "Source", null, null, 1)
            block(storage, directory)
            val sessions = storage.sessions.list()
            storage.close()
            val reopened = HelixStorage.open(context, name, directory)
            try {
                assertEquals(sessions, reopened.sessions.list())
                if (sessions.any { it.id == "nested" }) {
                    assertEquals(2, ContextHistory.load(reopened, "nested").rows.size)
                }
            } finally {
                reopened.close()
            }
        } finally {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }
}
