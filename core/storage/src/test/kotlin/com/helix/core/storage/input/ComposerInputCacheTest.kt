package com.helix.core.storage.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** File-only tests: no Room, Android Context, model, or device is needed. */
class ComposerInputCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sessionsHaveIndependentFilesAndSurviveReopen() {
        val root = temporary.newFolder()
        val store = ComposerInputCache(root)
        val first = input("a", 0, "first", "会话 A")
        val second = input("b", 0, "second", "会话 B")
        assertTrue(store.save(first))
        assertTrue(store.save(second))
        val reopened = ComposerInputCache(root)
        assertEquals(first, reopened.get("a"))
        assertEquals(second, reopened.get("b"))
        assertEquals(2, root.listFiles()!!.size)
        assertTrue(root.listFiles()!!.all { it.extension == "json" })
    }

    @Test fun currentInputReplacesCacheWithoutAnExpectedDatabaseRevision() {
        val store = ComposerInputCache(temporary.newFolder())
        assertTrue(store.save(input("a", 1, "old", "old")))
        val current = input("a", 700, "current", "current input")
        assertTrue(store.save(current))
        assertEquals(current, store.get("a"))
    }

    @Test fun delayedOlderWriteCannotUndoNewerTyping() {
        val store = ComposerInputCache(temporary.newFolder())
        val old = input("a", 1, "old", "old")
        val current = input("a", 2, "new", "new")
        assertTrue(store.save(current))
        assertFalse(store.save(old))
        assertEquals(current, store.get("a"))
    }

    @Test fun acceptedInputDeletesItsFileButNotTheNextInput() {
        val root = temporary.newFolder()
        val store = ComposerInputCache(root)
        val sent = input("a", 1, "sent", "sent")
        val next = input("a", 2, "next", "next")
        assertTrue(store.save(sent))
        assertTrue(store.save(next))
        assertFalse(store.clear("a", sent.revision, sent.clientRequestId))
        assertEquals(next, store.get("a"))
        assertTrue(store.clear("a", next.revision, next.clientRequestId))
        assertNull(store.get("a"))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun acceptedUncachedInputAlsoRemovesTheOlderCachedText() {
        val root = temporary.newFolder()
        val store = ComposerInputCache(root)
        assertTrue(store.save(input("a", 1, "old", "outdated")))
        assertTrue(store.clear("a", 2, "actually-sent"))
        assertNull(store.get("a"))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun lateWriteAfterAcknowledgementDoesNotResurrectSentInput() {
        val store = ComposerInputCache(temporary.newFolder())
        val sent = input("a", 1, "sent", "sent")
        assertTrue(store.save(sent))
        assertTrue(store.clear("a", 1, "sent"))
        assertFalse(store.save(sent))
        assertNull(store.get("a"))
        assertTrue(store.save(input("a", 2, "next", "new message")))
    }

    @Test fun cacheWriteFailureKeepsCurrentMemoryAndCanBeOverwrittenLater() {
        val blocker = temporary.newFile()
        val directory = File(blocker, "inputs")
        val store = ComposerInputCache(directory)
        val current = input("a", 1, "current", "must not disappear")
        assertFalse(store.save(current))
        assertEquals(current, store.get("a"))
        assertTrue(blocker.delete())
        assertTrue(blocker.mkdir())
        assertTrue(store.save(current))
        assertEquals(current, ComposerInputCache(directory).get("a"))
    }

    @Test fun corruptOptionalFileIsACacheMissAndDoesNotBlockReplacement() {
        val root = temporary.newFolder()
        val old = input("a", 1, "old", "old")
        assertTrue(ComposerInputCache(root).save(old))
        root.listFiles()!!.single().writeText("[]")
        val store = ComposerInputCache(root)
        assertNull(store.get("a"))
        val current = input("a", 2, "current", "new")
        assertTrue(store.save(current))
        assertEquals(current, ComposerInputCache(root).get("a"))
    }

    @Test fun clearingInputRemovesCacheInsteadOfKeepingAnEmptyHistory() {
        val root = temporary.newFolder()
        val store = ComposerInputCache(root)
        assertTrue(store.save(input("a", 1, "old", "text")))
        assertTrue(store.save(input("a", 2, "empty", "")))
        assertNull(store.get("a"))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun filenamesDoNotInterpretSessionIdsAsPaths() {
        val root = temporary.newFolder()
        val store = ComposerInputCache(root)
        val current = input("../../outside", 1, "input", "text")
        assertTrue(store.save(current))
        assertEquals(current, ComposerInputCache(root).get(current.sessionId))
        assertTrue(
            root
                .listFiles()!!
                .single()
                .name
                .matches(Regex("[a-f0-9]{64}\\.json")),
        )
    }

    @Test fun removingSessionDoesNotDeleteAnotherSessionOrAllowALateWriter() {
        val store = ComposerInputCache(temporary.newFolder())
        val a = input("a", 1, "a", "a")
        val b = input("b", 1, "b", "b")
        assertTrue(store.save(a))
        assertTrue(store.save(b))
        assertTrue(store.removeSession("a"))
        assertFalse(store.save(a.copy(revision = 2)))
        assertNull(store.get("a"))
        assertEquals(b, store.get("b"))
    }

    @Test fun fileSnapshotRetainsReferencesAttachmentsAndEditTargetWithoutARoomTable() {
        val root = temporary.newFolder()
        val current =
            input("session", 4, "request", "current text").copy(
                attachmentIdsJson = "[\"artifact-a\",\"artifact-b\"]",
                revisedMessageId = "message",
                delivery = "STEER",
                expectedTurnId = "turn",
                referenceSourceSessionId = "source-session",
                referenceKind = "RECENT_MESSAGES",
            )
        assertTrue(ComposerInputCache(root).save(current))
        assertEquals(current, ComposerInputCache(root).get("session"))
    }

    private fun input(
        session: String,
        revision: Long,
        request: String,
        text: String,
    ) = ComposerInputSnapshot(session, revision, request, text, "[]")
}
