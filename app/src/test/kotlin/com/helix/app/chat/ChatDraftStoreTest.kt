package com.helix.app.chat

import com.helix.core.storage.entity.SessionEntity
import com.helix.feature.files.AttachmentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChatDraftStoreTest {
    private fun draft(id: String = "draft") = SessionEntity(id, "", "provider", "model", 1L, null)

    @Test fun transientEditsAreSavedOnlyOnMaterialization() {
        val store = ChatDraftStore()
        store.open(draft())
        store.directory("draft", "scope:workspace:work")
        store.model("draft", "other", "small")
        store.addAttachment("draft", DraftAttachment("a", "test-uri", "a.txt", 3L))
        var saved: SessionEntity? = null
        assertNull(store.persist("different", "hello", "fallback") { saved = it })
        assertNull(saved)
        val attachments = store.persist("draft", " hello\nworld ", "fallback") { saved = it }
        assertEquals("hello world", saved?.title)
        assertEquals("scope:workspace:work", saved?.directoryRef)
        assertEquals("other", saved?.providerId)
        assertEquals(listOf("a"), attachments?.map { it.id })
        assertNull(store.current)
        store.persist("draft", "again", "fallback") { error("must not save twice") }
    }

    @Test fun failedPersistencePreservesDraftAndCanBeRetriedAfterRelease() {
        val store = ChatDraftStore()
        store.open(draft())
        assertTrue(store.beginPreparation("draft"))
        val result = runCatching { store.persist("draft", "hello", "fallback") { error("disk failure") } }
        assertTrue(result.isFailure)
        assertNotNull(store.current)
        assertTrue(store.preparing)
        store.finishPreparation()
        assertTrue(store.beginPreparation("draft"))
        store.persist("draft", "hello", "fallback") { assertEquals("hello", it.title) }
        store.finishPreparation()
        assertNull(store.current)
    }

    @Test fun concurrentPreparationHasOneOwnerAndFreezesItsSnapshot() {
        val store = ChatDraftStore()
        store.open(draft())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts =
                (1..2).map {
                    pool.submit<Boolean> {
                        start.await()
                        store.beginPreparation("draft")
                    }
                }
            start.countDown()
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
            store.rename("draft", "changed")
            store.model("draft", "changed", "changed")
            store.clear()
            assertFalse(store.open(draft("new")))
            assertEquals(draft(), store.current?.session)
            store.finishPreparation()
            assertTrue(store.open(draft("new")))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun attachmentLimitAndStaleDraftMutationsCannotCrossSessions() {
        val store = ChatDraftStore()
        store.open(draft())
        repeat(AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE + 2) {
            store.addAttachment("draft", DraftAttachment("$it", "uri", "file", 1L))
        }
        assertEquals(AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE, store.current?.attachments?.size)
        store.open(draft("new"))
        store.rename("draft", "stale")
        store.addAttachment("draft", DraftAttachment("stale", "uri", "file", 1L))
        assertEquals(draft("new"), store.current?.session)
        assertTrue(store.current!!.attachments.isEmpty())
    }
}
