package com.helix.app.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.infrastructure.conversation.RoomConversationTurnHistory
import com.helix.app.infrastructure.conversation.UserMessageEditor
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.MessageEditResult
import com.helix.feature.conversation.application.MessageRevisionAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Room and content bytes only; no HelixApplication, account or live execution. */
class ConversationPersistenceBoundaryDeviceTest {
    @Test fun retrySourceDoesNotBorrowFutureOrForeignInput() =
        withStorage { storage ->
            storage.sessions.create("s", "Session", null, null, 1)
            storage.sessions.create("other", "Other", null, null, 1)
            storage.turns.start("first", "s", 2)
            storage.messages.append("u1", "s", "first", "USER", "TEXT", "original")
            storage.turns.start("retry", "s", 3)
            storage.turns.start("future", "s", 4)
            storage.messages.append("u2", "s", "future", "USER", "TEXT", "newer")
            val reader = RoomConversationTurnHistory(storage)
            assertEquals("first", reader.retrySource("s", "retry")?.inputTurnId)
            assertNull(reader.retrySource("other", "retry"))
            assertNull(reader.retrySource("s", "missing"))
        }

    @Test fun regenerateChecksMessageOwnerAndSourceFromOneRead() =
        withStorage { storage ->
            storage.sessions.create("s", "Session", null, null, 1)
            storage.sessions.create("other", "Other", null, null, 1)
            storage.turns.start("turn", "s", 2)
            storage.messages.append("user", "s", "turn", "USER", "TEXT", "question")
            storage.messages.append("assistant", "s", "turn", "ASSISTANT", "TEXT", "answer")
            val reader = RoomConversationTurnHistory(storage)
            assertEquals("turn", reader.regenerateSource("s", "assistant"))
            assertNull(reader.regenerateSource("other", "assistant"))
            assertNull(reader.regenerateSource("s", "user"))
            assertEquals(MessageRevisionAvailability.BUSY, reader.revisionAvailability("s", "user"))
            assertEquals(MessageRevisionAvailability.TARGET_CHANGED, reader.revisionAvailability("s", "missing"))
        }

    @Test fun locatedWindowIsDetachedFromLaterHistoryWrites() =
        withStorage { storage ->
            storage.sessions.create("s", "Session", null, null, 1)
            storage.messages.append("hit", "s", null, "USER", "TEXT", "uniqueneedle")
            (1..60).forEach { index ->
                storage.messages.append("m$index", "s", null, "ASSISTANT", "TEXT", "row $index")
            }
            val reader = RoomConversationTurnHistory(storage)
            val focus = requireNotNull(reader.locate("s", "uniqueneedle", null, null))
            assertEquals("hit", focus.messageId)
            assertEquals(storage.messages.resolve("m50").sequence, focus.through)
            storage.messages.append("newhit", "s", null, "USER", "TEXT", "uniqueneedle")
            assertEquals("hit", focus.messageId)
            assertNotEquals(Long.MAX_VALUE, focus.through)
        }

    @Test fun messageEditorUsesOpaqueVersionAndRefusesStaleSnapshot() =
        withStorage { storage ->
            storage.sessions.create("s", "Session", null, null, 1)
            storage.messages.append("user", "s", null, "USER", "TEXT", "original")
            val editor = UserMessageEditor(storage)
            val first = editor.load("s", "user")
            assertNotEquals(storage.messages.resolve("user").contentRef, first.contentVersion)
            assertEquals(MessageEditResult.SAVED, editor.save(first, "first edit"))
            assertEquals(MessageEditResult.CHANGED, editor.save(first, "stale edit"))
            assertEquals("first edit", editor.load("s", "user").text)
        }

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(Application::class.java, context.javaClass)
        assertFalse(context.packageName == "com.helix.agent.developer")
        val name = "conversation-persistence-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, "conversation-persistence-${UUID.randomUUID()}")
        val storage = HelixStorage.open(context, name, File(root, "content"))
        try {
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
