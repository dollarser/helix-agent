package com.helix.app.ui

import androidx.compose.runtime.saveable.SaverScope
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.chat.ChatSubmissionReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDraftBufferTest {
    @Test fun changingDeliveryKeepsTextAndCreatesANewPersistedIntent() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.edit("follow up")
            assertTrue(fixture.save(buffer))
            val original = requireNotNull(buffer.saved)
            buffer.delivery(com.helix.core.storage.repository.SessionInputDelivery.STEER, "active-turn")
            assertNotEquals(original.clientRequestId, buffer.value.clientRequestId)
            assertEquals("follow up", buffer.value.text)
            assertTrue(buffer.dirty)
            assertTrue(fixture.save(buffer))
            assertEquals("active-turn", fixture.disk?.expectedTurnId)
            assertEquals(com.helix.core.storage.repository.SessionInputDelivery.STEER, fixture.disk?.delivery)
        }

    @Test fun queuedReceiptClearsOnlyItsDraftAndResetsDeliveryForNextInput() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.edit("adjust")
            buffer.delivery(com.helix.core.storage.repository.SessionInputDelivery.STEER, "turn")
            assertTrue(fixture.save(buffer))
            val submitted = requireNotNull(buffer.saved)
            buffer.accepted(
                ChatSubmissionReceipt(
                    submitted,
                    ChatSubmissionOutcome.Enqueued(submitted.clientRequestId),
                ),
                {
                    fixture.disk = null
                    true
                },
                { fixture.disk },
            )
            assertEquals("", buffer.value.text)
            assertEquals(com.helix.core.storage.repository.SessionInputDelivery.QUEUE, buffer.value.delivery)
            assertNull(buffer.value.expectedTurnId)
            assertNull(buffer.saved)
        }

    @Test fun persistenceExceptionsKeepTheDraftAndCanBeRetried() =
        runBlocking {
            val fixture = Fixture()
            val buffer = ConversationDraftBuffer("session")
            assertFalse(buffer.initialize { error("storage unavailable") })
            assertTrue(buffer.failed)
            assertTrue(buffer.initialize { fixture.disk })
            buffer.edit("unsaved")
            assertFalse(buffer.persist({ it }, { fixture.disk }) { _, _ -> error("disk full") })
            assertEquals("unsaved", buffer.value.text)
            assertNull(buffer.saved)
            assertTrue(buffer.failed)
            assertTrue(fixture.save(buffer))
        }

    @Test fun cancellationIsNotReportedAsAStorageFailure() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.edit("keep")
            try {
                buffer.persist({ it }, { fixture.disk }) { _, _ -> throw CancellationException("cancelled") }
                error("expected cancellation")
            } catch (_: CancellationException) {
                assertFalse(buffer.failed)
                assertEquals("keep", buffer.value.text)
            }
        }

    @Test fun submissionPersistsTheClickedSnapshotWhileKeepingNewerTyping() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.edit("clicked")
            val request = buffer.value
            buffer.edit("typed later")
            assertTrue(
                buffer.persist({ it }, { fixture.disk }, request) { submitted, _ ->
                    fixture.disk = submitted
                    true
                },
            )
            assertEquals("clicked", fixture.disk?.text)
            assertEquals("typed later", buffer.value.text)
            assertTrue(buffer.dirty)
        }

    @Test fun anImmediateEditSurvivesAnAcceptedOlderReceipt() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.edit("first")
            assertTrue(fixture.save(buffer))
            val sent = requireNotNull(buffer.saved)
            buffer.edit("next")
            assertNotEquals(sent.clientRequestId, buffer.value.clientRequestId)
            buffer.accepted(ChatSubmissionReceipt(sent, ChatSubmissionOutcome.Accepted("turn")), {
                fixture.disk = null
                true
            }, { fixture.disk })
            assertEquals("next", buffer.value.text)
            assertNull(buffer.saved)
            assertTrue(fixture.save(buffer))
            assertEquals("next", fixture.disk?.text)
            assertEquals(0L, fixture.disk?.revision)
        }

    @Test fun editsDuringAnInflightSaveAreNotClaimedAsPersisted() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.edit("old")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val saving =
                async {
                    buffer.persist({ it }, { fixture.disk }) { request, _ ->
                        entered.complete(Unit)
                        release.await()
                        fixture.disk = request
                        true
                    }
                }
            entered.await()
            buffer.edit("new")
            release.complete(Unit)
            assertTrue(saving.await())
            assertEquals("old", buffer.saved?.text)
            assertEquals("new", buffer.value.text)
            assertTrue(buffer.dirty)
            assertTrue(fixture.save(buffer))
            assertEquals(1L, fixture.disk?.revision)
        }

    @Test fun sendReusesTheSameIntentPersistedByAnInflightAutosave() =
        runBlocking {
            val fixture = Fixture()
            fixture.disk = ChatSubmission("session", 0, "original", "before")
            val buffer = fixture.open()
            buffer.edit("send me")
            val clicked = buffer.value
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var writes = 0
            val autosave =
                async {
                    buffer.persist({ it }, { fixture.disk }) { request, expected ->
                        writes += 1
                        assertEquals(0L, expected)
                        entered.complete(Unit)
                        release.await()
                        fixture.disk = request
                        true
                    }
                }
            entered.await()
            val send =
                async {
                    buffer.persist({ it }, { fixture.disk }, clicked) { _, _ ->
                        writes += 1
                        false
                    }
                }
            release.complete(Unit)

            assertTrue(autosave.await())
            assertTrue(send.await())
            assertEquals(1, writes)
            assertEquals(clicked.clientRequestId, buffer.saved?.clientRequestId)
            assertEquals("send me", buffer.saved?.text)
            assertEquals(1L, buffer.saved?.revision)
        }

    @Test fun recreatedBufferAdoptsTheIntentCommittedByTheOldInflightSave() =
        runBlocking {
            val fixture = Fixture()
            fixture.disk = ChatSubmission("session", 0, "original", "before")
            val old = fixture.open()
            old.edit("survive rotation")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val oldSave =
                async {
                    old.persist({ it }, { fixture.disk }) { request, _ ->
                        entered.complete(Unit)
                        release.await()
                        fixture.disk = request
                        true
                    }
                }
            entered.await()
            val saveScope =
                object : SaverScope {
                    override fun canBeSaved(value: Any): Boolean = true
                }
            val savedState =
                with(ConversationDraftBuffer.Saver) {
                    requireNotNull(saveScope.save(old))
                }
            val recreated = requireNotNull(ConversationDraftBuffer.Saver.restore(savedState))
            assertTrue(recreated.initialize { fixture.disk })

            release.complete(Unit)
            assertTrue(oldSave.await())
            var duplicateWrites = 0
            assertTrue(
                recreated.persist({ it }, { fixture.disk }) { _, _ ->
                    duplicateWrites += 1
                    false
                },
            )
            assertEquals(0, duplicateWrites)
            assertFalse(recreated.failed)
            assertEquals("survive rotation", recreated.saved?.text)
            assertEquals(1L, recreated.saved?.revision)
        }

    @Test fun rejectedCasAdoptsAnIdenticalIntentCommittedByAnotherBuffer() =
        runBlocking {
            val fixture = Fixture()
            fixture.disk = ChatSubmission("session", 0, "original", "before")
            val buffer = fixture.open()
            buffer.edit("same intent")

            assertTrue(
                buffer.persist({ it }, { fixture.disk }) { request, _ ->
                    fixture.disk = request
                    false
                },
            )
            assertFalse(buffer.failed)
            assertEquals(fixture.disk, buffer.saved)
            assertEquals(1L, buffer.saved?.revision)
        }

    @Test fun rejectedCasRetainsTextAndDoesNotAdvanceSavedRevision() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.edit("keep me")
            assertFalse(buffer.persist({ it }, { fixture.disk }) { _, _ -> false })
            assertNull(buffer.saved)
            assertTrue(buffer.failed)
            assertEquals("keep me", buffer.value.text)
            assertTrue(fixture.save(buffer))
            assertFalse(buffer.failed)
        }

    @Test fun attachmentOnlyChangesChangeIdentityAndMissingFilesNeedExplicitRemoval() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.restoredAttachments(emptyList())
            val original = buffer.value.clientRequestId
            buffer.attachments(listOf("file"))
            assertNotEquals(original, buffer.value.clientRequestId)
            assertTrue(fixture.save(buffer))
            buffer.restoredAttachments(listOf("file"))
            buffer.attachments(emptyList())
            assertEquals(listOf("file"), buffer.value.attachmentIds)
            buffer.discardMissingAttachments()
            assertTrue(fixture.save(buffer))
            assertTrue(requireNotNull(fixture.disk).attachmentIds.isEmpty())
        }

    @Test fun acceptedReceiptRecoveryKeepsTheSavedAttachmentIntentAfterUiRefresh() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.restoredAttachments(emptyList())
            buffer.edit("with attachment")
            buffer.attachments(listOf("file"))
            assertTrue(fixture.save(buffer))
            val submitted = requireNotNull(buffer.acceptedReceiptCandidate)

            buffer.attachments(emptyList())
            assertNotEquals(submitted.clientRequestId, buffer.value.clientRequestId)
            assertEquals(submitted, buffer.acceptedReceiptCandidate)
            buffer.accepted(ChatSubmissionReceipt(submitted, ChatSubmissionOutcome.Accepted("turn")), {
                fixture.disk = null
                true
            }, { fixture.disk })

            assertNull(buffer.saved)
            assertEquals("with attachment", buffer.value.text)
            assertTrue(buffer.value.attachmentIds.isEmpty())
        }

    @Test fun defaultSaveReadsTheEditorOnlyAfterAnAcceptedReceiptReleasesTheGate() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.edit("accepted")
            assertTrue(fixture.save(buffer))
            val submitted = requireNotNull(buffer.saved)
            val acknowledged = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val accepting =
                async {
                    buffer.accepted(ChatSubmissionReceipt(submitted, ChatSubmissionOutcome.Accepted("turn")), {
                        fixture.disk = null
                        acknowledged.complete(Unit)
                        release.await()
                        true
                    }, { fixture.disk })
                }
            acknowledged.await()
            var resurrectedWrites = 0
            val saving =
                async {
                    buffer.persist({ it }, { fixture.disk }) { _, _ ->
                        resurrectedWrites += 1
                        true
                    }
                }

            release.complete(Unit)
            accepting.await()
            assertTrue(saving.await())
            assertEquals(0, resurrectedWrites)
            assertNull(buffer.saved)
            assertEquals("", buffer.value.text)
        }

    @Test fun attachmentRefreshReadsServiceStateAfterAcceptedReceiptReleasesTheGate() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.restoredAttachments(emptyList())
            buffer.edit("accepted attachment")
            buffer.attachments(listOf("file"))
            assertTrue(fixture.save(buffer))
            val submitted = requireNotNull(buffer.saved)
            val loading = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val accepting =
                async {
                    buffer.accepted(ChatSubmissionReceipt(submitted, ChatSubmissionOutcome.Accepted("turn")), {
                        fixture.disk = null
                        false
                    }, {
                        loading.complete(Unit)
                        releaseLoad.await()
                        fixture.disk
                    })
                }
            loading.await()
            val attachmentRead = CompletableDeferred<Unit>()
            val refreshing =
                async {
                    buffer.synchronizeAttachments {
                        attachmentRead.complete(Unit)
                        emptyList()
                    }
                }
            assertFalse(attachmentRead.isCompleted)

            releaseLoad.complete(Unit)
            accepting.await()
            refreshing.await()
            assertTrue(attachmentRead.isCompleted)
            assertNull(buffer.saved)
            assertTrue(buffer.value.text.isEmpty())
            assertTrue(buffer.value.attachmentIds.isEmpty())
        }

    @Test fun cancellationAfterAcknowledgementStillSettlesTheAcceptedEditor() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.restoredAttachments(emptyList())
            buffer.edit("accepted through cancellation")
            buffer.attachments(listOf("file"))
            assertTrue(fixture.save(buffer))
            val submitted = requireNotNull(buffer.saved)
            val acknowledging = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val accepting =
                async {
                    buffer.accepted(ChatSubmissionReceipt(submitted, ChatSubmissionOutcome.Accepted("turn")), {
                        fixture.disk = null
                        acknowledging.complete(Unit)
                        release.await()
                        true
                    }, { fixture.disk })
                }
            acknowledging.await()
            accepting.cancel()
            assertFalse(accepting.isCompleted)

            release.complete(Unit)
            accepting.join()
            assertNull(fixture.disk)
            assertNull(buffer.saved)
            assertTrue(buffer.value.text.isEmpty())
            assertTrue(buffer.value.attachmentIds.isEmpty())
        }

    @Test fun revisionDraftCannotBecomeOrdinaryComposerInputOrBeOverwritten() =
        runBlocking {
            val fixture = Fixture()
            fixture.disk = ChatSubmission("session", 0, "edit", "revision", revisedMessageId = "message")
            val buffer = fixture.open()
            assertEquals("message", buffer.revisionMessageId)
            assertEquals("", buffer.value.text)
            buffer.edit("ordinary")
            assertFalse(fixture.save(buffer))
            assertEquals("revision", fixture.disk?.text)
        }

    @Test fun anotherSessionReceiptCannotClearThisEditor() =
        runBlocking {
            val fixture = Fixture()
            val buffer = fixture.open()
            buffer.edit("B")
            val request = ChatSubmission("other", 0, "request", "A")
            buffer.accepted(ChatSubmissionReceipt(request, ChatSubmissionOutcome.Accepted("turn")), {
                error("must not acknowledge another session")
            }, { fixture.disk })
            assertEquals("B", buffer.value.text)
        }

    @Test fun revisionReceiptCannotBeAcknowledgedByTheOrdinaryComposer() =
        runBlocking {
            val fixture = Fixture()
            val revision = ChatSubmission("session", 1, "revision", "edited", revisedMessageId = "message")
            fixture.disk = revision
            val buffer = fixture.open()
            var acknowledged = false

            buffer.accepted(ChatSubmissionReceipt(revision, ChatSubmissionOutcome.Accepted("turn")), {
                acknowledged = true
                fixture.disk = null
                true
            }, { fixture.disk })

            assertFalse(acknowledged)
            assertEquals(revision, fixture.disk)
        }

    private class Fixture {
        var disk: ChatSubmission? = null

        suspend fun open() = ConversationDraftBuffer("session").also { it.initialize { disk } }

        suspend fun save(buffer: ConversationDraftBuffer) =
            buffer.persist({ it }, { disk }) { request, expected ->
                if (disk?.revision != expected) {
                    false
                } else {
                    disk = request
                    true
                }
            }
    }
}
