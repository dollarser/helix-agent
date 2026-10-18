package com.helix.app.chat

import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
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
    @Test fun modelAndReasoningCommitTogetherBeforeMaterializationAndPreserveOtherControls() {
        val store = ChatDraftStore()
        store.open(draft(), control())
        val expected = requireNotNull(store.current)
        store.control("draft", control().copy(chatToolsEnabled = true))
        val applied = store.modelAndReasoning(expected, "next", "next", ReasoningEffort.HIGH)
        assertEquals(control().copy(chatToolsEnabled = true, reasoning = ReasoningEffort.HIGH), applied)
        store.persist("draft") {
            assertEquals("next", it.session.modelId)
            assertEquals(applied, it.control)
        }
        assertNull(store.modelAndReasoning(expected, "stale", "stale", ReasoningEffort.LOW))
    }

    @Test fun materializationWinningTheLockRejectsSaveAndNeverPersistsMixedFields() {
        val store = ChatDraftStore()
        store.open(draft(), control())
        val expected = requireNotNull(store.current)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val materialize =
                pool.submit {
                    store.persist("draft") {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        assertEquals(expected, it)
                    }
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val selection =
                pool.submit<RunControlConfig?> {
                    store.modelAndReasoning(expected, "next", "next", ReasoningEffort.HIGH)
                }
            release.countDown()
            materialize.get(5, TimeUnit.SECONDS)
            assertNull(selection.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun preparationAndStaleEditedFieldsRejectTheAtomicDraftUpdate() {
        val store = ChatDraftStore()
        store.open(draft(), control())
        val expected = requireNotNull(store.current)
        assertTrue(store.beginPreparation("draft"))
        assertNull(store.modelAndReasoning(expected, "next", "next", ReasoningEffort.HIGH))
        assertEquals(expected, store.current)
        store.finishPreparation()
        assertNotNull(store.modelAndReasoning(expected, "next", "next", ReasoningEffort.HIGH))
        assertNull(store.modelAndReasoning(expected, "stale", "stale", ReasoningEffort.LOW))
    }

    @Test fun metadataMaterializationPreservesAnExplicitTitleIncludingTheUiPlaceholder() {
        val store = ChatDraftStore()
        store.open(draft(), control())
        store.rename("draft", "新建会话")
        store.persist("draft") { assertEquals("新建会话", it.session.title) }
    }

    private fun draft(id: String = "draft") = SessionEntity(id, "", "provider", "model", 1L, null)

    private fun control() =
        RunControlConfig(AgentMode.ACT, false, TurnBudgetBounds.DEFAULT, goalBudgets = GoalBudgetDefaults.VALUE)

    @Test fun transientEditsAreSavedOnlyOnMaterialization() {
        val store = ChatDraftStore()
        store.open(draft(), control())
        store.directory("draft", "scope:workspace:work")
        store.model("draft", "other", "small")
        store.addAttachment("draft", DraftAttachment("a", "test-uri", "a.txt", 3L))
        var saved: SessionDraft? = null
        assertNull(store.persist("different") { saved = it })
        assertNull(saved)
        val attachments = store.persist("draft") { saved = it }
        assertEquals("", saved?.session?.title)
        assertEquals("scope:workspace:work", saved?.session?.directoryRef)
        assertEquals("other", saved?.session?.providerId)
        assertEquals(listOf("a"), attachments?.map { it.id })
        assertNull(store.current)
        store.persist("draft") { error("must not save twice") }
    }

    @Test fun failedPersistencePreservesDraftAndCanBeRetriedAfterRelease() {
        val store = ChatDraftStore()
        store.open(draft(), control())
        assertTrue(store.beginPreparation("draft"))
        val result = runCatching { store.persist("draft") { error("disk failure") } }
        assertTrue(result.isFailure)
        assertNotNull(store.current)
        assertTrue(store.preparing)
        store.finishPreparation()
        assertTrue(store.beginPreparation("draft"))
        store.persist("draft") { assertEquals("", it.session.title) }
        store.finishPreparation()
        assertNull(store.current)
    }

    @Test fun concurrentPreparationHasOneOwnerAndFreezesItsSnapshot() {
        val store = ChatDraftStore()
        store.open(draft(), control())
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
            assertFalse(store.open(draft("new"), control()))
            assertEquals(draft(), store.current?.session)
            store.finishPreparation()
            assertTrue(store.open(draft("new"), control()))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun attachmentLimitAndStaleDraftMutationsCannotCrossSessions() {
        val store = ChatDraftStore()
        store.open(draft(), control())
        repeat(AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE + 2) {
            store.addAttachment("draft", DraftAttachment("$it", "uri", "file", 1L))
        }
        assertEquals(AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE, store.current?.attachments?.size)
        store.open(draft("new"), control())
        store.rename("draft", "stale")
        store.addAttachment("draft", DraftAttachment("stale", "uri", "file", 1L))
        assertEquals(draft("new"), store.current?.session)
        assertTrue(store.current!!.attachments.isEmpty())
    }
}
