package com.helix.app.ui

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
