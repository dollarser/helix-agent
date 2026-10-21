package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Owned synthetic Room fixture only; no model, tools, permissions or user files are touched. */
class UserMessageEditorDeviceTest {
    @Test fun editingKeepsOriginalEvidenceRepliesAndSearchWithoutCreatingAnyWork() =
        fixture { storage ->
            val user = storage.messages.append("user", "s", null, "USER", "TEXT", "before needle")
            storage.messages.append("answer", "s", null, "ASSISTANT", "TEXT", "original answer")
            storage.messages.append("checkpoint", "s", null, "ASSISTANT", ContextCompaction.KIND, "derived old summary")
            val editor = UserMessageEditor(storage)
            assertEquals(MessageEditResult.SAVED, editor.save(editor.load("s", "user"), "after 中文"))
            val edited = storage.messages.resolve("user")
            assertEquals(user.id, edited.id)
            assertEquals(user.sequence, edited.sequence)
            assertNotEquals(user.contentRef, edited.contentRef)
            assertEquals("after 中文", storage.messages.readContent(edited))
            assertEquals("original answer", storage.messages.readContent(storage.messages.resolve("answer")))
            val evidence = storage.messages.allRevisions("s").single { it.kind == "MESSAGE_EDIT_EVIDENCE" }
            assertEquals(user.contentRef, evidence.contentRef)
            assertEquals("before needle", storage.messages.readContent(evidence))
            assertEquals("SYSTEM", evidence.role)
            assertEquals(listOf(0L..1L), MessageEditHistory.read(storage, "s"))
            assertTrue(storage.turns.listBySession("s").isEmpty())
            assertEquals(1, storage.sessions.list().size)
            assertNull(storage.messages.latestOfKind("s", ContextCompaction.KIND))
            assertFalse(
                storage.sessionSearch
                    .search("needle")
                    .hits
                    .isNotEmpty(),
            )
            assertTrue(
                storage.sessionSearch
                    .search("中文")
                    .hits
                    .isNotEmpty(),
            )
        }

    @Test fun staleVersionAndOtherSessionCannotOverwriteCurrentMessage() =
        fixture { storage ->
            storage.messages.append("user", "s", null, "USER", "TEXT", "original")
            val editor = UserMessageEditor(storage)
            val snapshot = editor.load("s", "user")
            assertThrows(IllegalArgumentException::class.java) { editor.load("other", "user") }
            assertEquals(MessageEditResult.SAVED, editor.save(snapshot, "first edit"))
            assertEquals(MessageEditResult.CHANGED, editor.save(snapshot, "late overwrite"))
            assertEquals("first edit", storage.messages.readContent(storage.messages.resolve("user")))
            assertEquals(1, storage.messages.allRevisions("s").count { it.kind == "MESSAGE_EDIT_EVIDENCE" })
        }

    @Test fun surroundingTransactionRollbackRetainsOriginalTextSearchAndHistory() =
        fixture { storage ->
            val original = storage.messages.append("user", "s", null, "USER", "TEXT", "original rollback needle")
            val editor = UserMessageEditor(storage)
            assertThrows(IllegalStateException::class.java) {
                storage.withTransaction {
                    assertEquals(
                        MessageEditResult.SAVED,
                        editor.save(editor.load("s", "user"), "rolled back replacement"),
                    )
                    error("Synthetic transaction abort")
                }
            }
            assertEquals(original.contentRef, storage.messages.resolve("user").contentRef)
            assertEquals(listOf(original), storage.messages.allRevisions("s"))
            assertTrue(
                storage.sessionSearch
                    .search("needle")
                    .hits
                    .isNotEmpty(),
            )
            assertTrue(
                storage.sessionSearch
                    .search("replacement")
                    .hits
                    .isEmpty(),
            )
            assertTrue(storage.turns.listBySession("s").isEmpty())
        }

    @Test fun activeWorkBlocksEditAndLeavesAllRecordsUnchanged() =
        fixture { storage ->
            storage.messages.append("user", "s", null, "USER", "TEXT", "original")
            val editor = UserMessageEditor(storage)
            val snapshot = editor.load("s", "user")
            storage.turns.start("active", "s", 1)
            val before = storage.messages.allRevisions("s")
            assertEquals(MessageEditResult.BUSY, editor.save(snapshot, "must not replace"))
            assertEquals(before, storage.messages.allRevisions("s"))
            assertEquals(1, storage.turns.listBySession("s").size)
        }

    @Test fun futureReplyIsCurrentAndBranchRetainsOldReplyAnnotations() =
        fixture { storage ->
            storage.messages.append("user", "s", null, "USER", "TEXT", "original")
            val answer = storage.messages.append("answer", "s", null, "ASSISTANT", "TEXT", "old reply")
            val editor = UserMessageEditor(storage)
            editor.save(editor.load("s", "user"), "edited")
            val future = storage.messages.append("future", "s", null, "ASSISTANT", "TEXT", "future reply")
            assertFalse(MessageEditHistory.read(storage, "s").any { future.sequence in it })
            SessionFork(storage).create("s", answer.id, "fork", "Synthetic branch", 2)
            val copiedAnswer = storage.messages.listBySession("fork").single { it.role == "ASSISTANT" }
            assertTrue(MessageEditHistory.read(storage, "fork").any { copiedAnswer.sequence in it })
            assertTrue(storage.turns.listBySession("fork").isEmpty())
            val rows =
                storage.messages.listBySession("fork").map {
                    ChatHistoryBuilder.PersistedRow(
                        it.turnId,
                        it.role,
                        it.kind,
                        storage.messages.readContent(it),
                        it.id,
                    )
                }
            assertEquals(listOf("edited", "old reply"), ChatHistoryBuilder.toModelMessagesStrict(rows).map { it.text })
        }

    private fun fixture(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".asrvalidation"))
        val name = "ui-message-edit-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, root)
        try {
            storage.sessions.create("s", "Synthetic edit", null, null, 1)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
