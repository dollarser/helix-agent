package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.SessionSearchMatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production Room transactions and query projection; host SQL coverage is separate. */
@RunWith(AndroidJUnit4::class)
class SessionSearchQueryDeviceTest {
    private lateinit var context: Context

    @Test fun olderSessionBodyBeyondCandidateCapAndLargeBodyAreSearchable() {
        withStorage("search-complete.db") { storage ->
            storage.sessions.create("old", "untitled", null, null, 1)
            storage.sessions.create("new", "untitled", null, null, 2)
            storage.withTransaction {
                repeat(510) { storage.messages.append("n$it", "new", null, "USER", "TEXT", "irrelevant $it") }
                storage.messages.append("target", "old", null, "USER", "TEXT", "x".repeat(300_000) + " 中文%_Needle")
            }
            val hit =
                storage.sessionSearch
                    .search("中文%_NEEDLE")
                    .hits
                    .single()
            assertEquals("old", hit.sessionId)
            assertTrue(hit.messageSnippet!!.contains("中文%_Needle"))
            assertTrue(hit.messageSnippet.length <= 240)
        }
    }

    @Test fun missingOriginalFileDoesNotBreakThePersistedSearchSnapshot() {
        withStorage("search-projection.db") { storage ->
            storage.sessions.create("s", "plain", null, null, 1)
            val row = storage.messages.append("m", "s", null, "USER", "TEXT", "needle")
            storage.contentStore.delete(
                com.helix.core.storage.content.ContentRef
                    .parse(row.contentRef!!),
            )
            assertEquals(
                "needle",
                storage.sessionSearch
                    .search("needle")
                    .hits
                    .single()
                    .messageSnippet,
            )
        }
    }

    @Test fun revisedProtocolAndBlankBodiesDoNotAppearAsVisibleTextMatches() {
        withStorage("search-effective.db") { storage ->
            storage.sessions.create("s", "plain", null, null, 1)
            storage.messages.append("m", "s", null, "USER", "TEXT", "old-needle")
            storage.messages.supersedeFrom("s", 0, "replacement")
            storage.messages.append("tool", "s", null, "ASSISTANT", "TOOL_CALLS", "protocol-needle")
            storage.messages.append("empty", "s", null, "USER", "TEXT", "")
            assertTrue(
                storage.sessionSearch
                    .search("needle")
                    .hits
                    .isEmpty(),
            )
        }
    }

    @Test fun renameArchiveAndHitLimitPreserveTheActualSearchScope() {
        withStorage("search-metadata.db") { storage ->
            repeat(3) { storage.sessions.create("s$it", "plain", null, null, it.toLong()) }
            storage.sessions.updateDetails("s0", "Renamed 中文", null)
            storage.sessions.archive("s0", 10)
            val renamed =
                storage.sessionSearch
                    .search("renamed")
                    .hits
                    .single()
            assertTrue(renamed.isArchived)
            assertEquals(setOf(SessionSearchMatchKind.TITLE), renamed.matchedKinds)
            assertTrue(storage.sessionSearch.search("plain", maxHits = 1).truncated)
            assertEquals(
                1,
                storage.sessionSearch
                    .search("plain", maxHits = 1)
                    .hits.size,
            )
            assertTrue(!storage.sessionSearch.search("plain", maxHits = 2).truncated)
        }
    }

    @Test fun projectionFailureRollsBackMessageAndActivity() {
        withStorage("search-atomic.db") { storage ->
            storage.sessions.create("s", "plain", null, null, 1)
            storage.database.openHelper.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER reject_projection BEFORE INSERT ON message_search " +
                    "BEGIN SELECT RAISE(ABORT, 'fixture'); END",
            )
            org.junit.Assert.assertThrows(RuntimeException::class.java) {
                storage.messages.append("failed", "s", null, "USER", "TEXT", "needle")
            }
            org.junit.Assert.assertNull(storage.database.messageDao().byId("failed"))
            assertEquals(1L, storage.sessions.resolve("s").lastActivityAt)
            assertTrue(
                storage.sessionSearch
                    .search("needle")
                    .hits
                    .isEmpty(),
            )
        }
    }

    private inline fun withStorage(
        dbName: String,
        block: (HelixStorage) -> Unit,
    ) {
        context = ApplicationProvider.getApplicationContext()
        // Fresh fixture per run: installed APKs keep app data between connected-test runs,
        // so stale rows would break the assertions.
        context.deleteDatabase(dbName)
        File(context.cacheDir, "content-$dbName").deleteRecursively()
        val db = Room.databaseBuilder(context, HelixDatabase::class.java, dbName).build()
        val storage = HelixStorage(db, FileContentStore(File(context.cacheDir, "content-$dbName")), TestSecretStore())
        try {
            block(storage)
        } finally {
            db.close()
        }
    }
}
