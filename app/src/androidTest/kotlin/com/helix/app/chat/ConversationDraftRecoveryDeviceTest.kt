package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Storage recovery only. Ordinary-process seed/kill/recover belongs to the later UI acceptance. */
class ConversationDraftRecoveryDeviceTest {
    @Test fun reopenedStoragePreservesTextIdentityAndAttachmentSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "composer-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, name)
        val draft = ChatSubmission("session", 0, "intent", "unsent text", listOf("artifact-a"))
        try {
            HelixStorage.open(context, name, root).useStorage { storage ->
                storage.sessions.create("session", "Draft", null, null, 1)
                assertTrue(storage.composerDrafts.save(draft.toDraftEntity(), null))
            }
            HelixStorage.open(context, name, root).useStorage { storage ->
                assertEquals(draft, storage.composerDrafts.get("session")?.toSubmission())
                assertTrue(storage.messages.listBySession("session").isEmpty())
                assertTrue(storage.turns.listBySession("session").isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    @Test fun staleSaveAndReceiptCannotReplaceOrDeleteNewerDraft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "composer-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, name)
        try {
            HelixStorage.open(context, name, root).useStorage { storage ->
                storage.sessions.create("a", "A", null, null, 1)
                storage.sessions.create("b", "B", null, null, 1)
                val first = ChatSubmission("a", 0, "first", "old").toDraftEntity()
                val second = first.copy(revision = 1, clientRequestId = "second", text = "new")
                assertTrue(storage.composerDrafts.save(first, null))
                assertTrue(storage.composerDrafts.save(second, 0))
                assertFalse(storage.composerDrafts.save(first, null))
                assertFalse(storage.composerDrafts.clear("a", 0, "first"))
                assertFalse(storage.composerDrafts.clear("b", 1, "second"))
                assertEquals(second, storage.composerDrafts.get("a"))
                assertTrue(storage.composerDrafts.clear("a", 1, "second"))
            }
        } finally {
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    private fun HelixStorage.useStorage(block: (HelixStorage) -> Unit) =
        try {
            block(this)
        } finally {
            close()
        }
}
