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

/**
 * HXA-191 search slice — real Room database and content files: the read-only candidate
 * query (JOIN + newest-session/newest-message ordering + LIMIT, no schema change) and the
 * bounded search repository end-to-end on the on-device database.
 */
@RunWith(AndroidJUnit4::class)
class SessionSearchQueryDeviceTest {
    private lateinit var context: Context

    @Test
    fun searchCandidatesAreLimitedAndOrderedNewestSessionFirst() {
        withStorage("search-order.db") { storage ->
            val older = storage.sessions.create("search-order-old", "untitled", null, null, 1000L)
            val newer = storage.sessions.create("search-order-new", "untitled", null, null, 3000L)
            storage.messages.append("m-old-0", older.id, null, "USER", "TEXT", "needle older")
            storage.messages.append("m-new-0", newer.id, null, "USER", "TEXT", "needle new one")
            storage.messages.append("m-new-1", newer.id, null, "USER", "TEXT", "needle new two")
            storage.messages.append("m-new-2", newer.id, null, "USER", "TEXT", "needle new three")

            // Candidates are any body with a contentRef (content matching is the
            // repository's job, not the query's). Capped at 2: the two most recent
            // bodies, both in the newer session, newest message first.
            val candidates = storage.database.messageDao().contentSearchCandidates(2)
            assertEquals(2, candidates.size)
            assertEquals("m-new-2", candidates[0].id)
            assertEquals("m-new-1", candidates[1].id)
            candidates.forEach { assertNotNull(it.contentRef) }

            // Unbounded: newest session first, newest message within a session.
            val all = storage.database.messageDao().contentSearchCandidates(10)
            assertEquals(listOf("m-new-2", "m-new-1", "m-new-0", "m-old-0"), all.map { it.id })
        }
    }

    @Test
    fun messagesWithoutContentAreNeverCandidates() {
        withStorage("search-norefs.db") { storage ->
            val session = storage.sessions.create("search-noref", "untitled", null, null, 1000L)
            storage.messages.append("m-empty", session.id, null, "USER", "TEXT", "")
            storage.messages.append("m-body", session.id, null, "USER", "TEXT", "alpha body")
            val candidates = storage.database.messageDao().contentSearchCandidates(10)
            assertEquals(listOf("m-body"), candidates.map { it.id })
        }
    }

    @Test
    fun searchMatchesTitleAndBodyWithArchiveState() {
        withStorage("search-match.db") { storage ->
            val newer = storage.sessions.create("search-match-new", "plain title", null, null, 2000L)
            val older = storage.sessions.create("search-match-old", "plain old", null, null, 1000L)
            storage.sessions.archive(older.id, 9000L)
            storage.messages.append("m-new", newer.id, null, "USER", "TEXT", "alpha-needle token here")
            storage.messages.append("m-old", older.id, null, "USER", "TEXT", "gamma-needle archived body")

            val bodyHit =
                storage.sessionSearch
                    .search("gamma-needle")
                    .hits
                    .single()
            assertEquals(older.id, bodyHit.sessionId)
            assertTrue(bodyHit.isArchived)
            assertEquals(setOf(SessionSearchMatchKind.MESSAGE), bodyHit.matchedKinds)
            assertTrue(
                "the snippet must contain the match",
                bodyHit.messageSnippet != null && bodyHit.messageSnippet.contains("gamma-needle"),
            )

            val titleHits = storage.sessionSearch.search("plain").hits
            assertEquals(2, titleHits.size)
            assertEquals("newest session first", newer.id, titleHits.first().sessionId)
            assertTrue(titleHits.all { it.matchedKinds.contains(SessionSearchMatchKind.TITLE) })
            assertTrue(titleHits.all { it.messageSnippet == null })
        }
    }

    @Test
    fun smallCandidateCapTruncatesAndStatesTheScope() {
        withStorage("search-cap.db") { storage ->
            val session = storage.sessions.create("search-cap", "untitled", null, null, 1000L)
            repeat(4) { index ->
                storage.messages.append("m-$index", session.id, null, "USER", "TEXT", "needle $index")
            }
            val result = storage.sessionSearch.search("needle", maxMessageCandidates = 2)
            assertTrue("the cap was reached, so the scope must be stated", result.truncated)
            assertEquals(2, result.scannedMessages)
            assertEquals(0, result.skippedMessages)
            assertEquals(1, result.hits.size)
            // The scan order is newest-first, so the snippet comes from the newest body.
            val snippet = result.hits.single().messageSnippet
            assertTrue(snippet != null && snippet.contains("needle 3"))
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
