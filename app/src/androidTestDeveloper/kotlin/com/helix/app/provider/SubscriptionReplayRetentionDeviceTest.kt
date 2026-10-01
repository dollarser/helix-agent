package com.helix.app.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.chat.SessionFork
import com.helix.core.storage.HelixStorage
import com.helix.runtime.cli.client.CliReplayEntry
import com.helix.runtime.cli.client.CliReplayMaintenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SubscriptionReplayRetentionDeviceTest {
    @Test fun parentDeletionKeepsArchivedBranchReferenceAcrossDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "replay-retention-${UUID.randomUUID()}.db"
        val directory = File(context.filesDir, name)
        var storage = HelixStorage.open(context, name, directory)
        try {
            storage.sessions.create("parent", "Parent", null, null, 1)
            storage.messages.append("user", "parent", null, "USER", "TEXT", "Read a file")
            storage.messages.append(
                "calls",
                "parent",
                null,
                "ASSISTANT",
                "TOOL_CALLS",
                """[{"id":"agy_shared","name":"read","arguments":"{}"}]""",
            )
            storage.messages.append(
                "result",
                "parent",
                null,
                "TOOL",
                "TOOL_RESULT",
                """{"id":"agy_shared","tool":"read","status":"SUCCEEDED","summary":"fixture"}""",
            )
            storage.messages.append("tail", "parent", null, "ASSISTANT", "TEXT", "Done")
            SessionFork(storage).create("parent", "tail", "branch", "Branch", 2)
            storage.sessions.archive("branch", 3)
            storage.deleteSessionPermanently("parent")
            val entry =
                CliReplayEntry(
                    CliReplayMaintenance.hash("agy_shared"),
                    "a".repeat(64),
                    CliReplayMaintenance.hash("parent"),
                    100,
                )
            assertTrue(SubscriptionReplayRetention(storage).unreferenced(listOf(entry)).isEmpty())
            storage.close()
            storage = HelixStorage.open(context, name, directory)
            assertTrue(SubscriptionReplayRetention(storage).unreferenced(listOf(entry)).isEmpty())
            storage.deleteSessionPermanently("branch")
            assertEquals(listOf(entry), SubscriptionReplayRetention(storage).unreferenced(listOf(entry)))
        } finally {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    @Test fun actualRoomPaginationIncludesEmptySessionId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database =
            androidx.room.Room
                .inMemoryDatabaseBuilder(
                    context,
                    com.helix.core.storage.HelixDatabase::class.java,
                ).build()
        try {
            val sessions =
                com.helix.core.storage.repository
                    .SessionRepository(database.sessionDao())
            sessions.create("", "Fixture", null, null, 1)
            assertEquals(listOf(""), sessions.pageIds(null, 128))
            assertEquals(emptyList<String>(), sessions.pageIds("", 128))
        } finally {
            database.close()
        }
    }
}
