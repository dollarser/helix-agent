package com.helix.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SessionTitleStorageDeviceTest {
    @Test fun untitledStateSurvivesReopenAndNamesOnlyOnce() =
        withStorage { open ->
            open().use { it.sessions.create("s", "", null, null, 1) }
            open().use { storage ->
                assertEquals("", storage.sessions.resolve("s").title)
                val directory = storage.sessions.resolve("s").directoryRef
                assertTrue(storage.sessions.nameUntitled("s", "整理明天的待办"))
                assertFalse(storage.sessions.nameUntitled("s", "later input"))
                assertEquals(directory, storage.sessions.resolve("s").directoryRef)
            }
            open().use {
                assertEquals("整理明天的待办", it.sessions.resolve("s").title)
                assertEquals("整理明天的待办", it.sessions.resolve("s").searchTitle)
            }
        }

    @Test fun manualPlaceholderTitleAndUnrelatedSessionsCannotBeReplaced() =
        withStorage { open ->
            open().use { storage ->
                storage.sessions.create("manual", "新建会话", null, null, 1)
                storage.sessions.create("other", "", null, null, 2)
                assertFalse(storage.sessions.nameUntitled("manual", "automatic"))
                assertFalse(storage.sessions.nameUntitled("deleted", "automatic"))
                storage.sessions.archive("other", 3)
                assertFalse(storage.sessions.nameUntitled("other", "automatic"))
                assertEquals("新建会话", storage.sessions.resolve("manual").title)
                assertEquals("", storage.sessions.resolve("other").title)
            }
        }

    @Test fun failedAcceptanceRollsBackTitleAndManualRenameWinsAfterInitialization() =
        withStorage { open ->
            open().use { storage ->
                val session = storage.sessions.create("s", "", null, null, 1)
                val failed =
                    runCatching {
                        storage.withTransaction {
                            storage.sessions.nameUntitled("s", "automatic")
                            error("input persistence failed")
                        }
                    }
                assertTrue(failed.isFailure)
                assertEquals("", storage.sessions.resolve("s").title)
                assertTrue(storage.sessions.nameUntitled("s", "automatic"))
                storage.sessions.updateDetails("s", "My title", session.directoryRef)
                assertFalse(storage.sessions.nameUntitled("s", "late automatic"))
                assertEquals("My title", storage.sessions.resolve("s").title)
            }
        }

    private fun withStorage(block: (() -> HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "session-title-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name)
        try {
            block { HelixStorage.open(context, name, root) }
        } finally {
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    private inline fun HelixStorage.use(block: (HelixStorage) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
