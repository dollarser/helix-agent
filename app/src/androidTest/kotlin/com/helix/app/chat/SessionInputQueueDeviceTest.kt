package com.helix.app.chat

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.MainActivity
import com.helix.app.knowledge.createEmptyTestNote
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.test.TransferTestDocumentsProvider
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.repository.ConversationReferenceKind
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputState
import com.helix.core.workspace.FileScopePath
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Loopback requests are held at the wire while real service admission accepts another input. */
class SessionInputQueueDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun visibleReplyCanBeSavedAsNoteWithoutAnotherModelRequest() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val turn =
                    compose.awaitAdmittedTurn(
                        chat.sendSubmission(submission(session, "Synthetic reply to save")).await(),
                    )
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                release.countDown()
                compose.waitUntil(15_000) { requests.get() == 1 && !chat.screen.value.isSending }
                val reply =
                    compose.container().storage.messages.byTurn(turn).last {
                        it.role == "ASSISTANT" &&
                            it.kind == "TEXT"
                    }
                val library = (compose.activity.application as com.helix.app.HelixApplication).knowledge
                try {
                    compose.onNodeWithTag("chat-message-toggle-${reply.id}").performScrollTo().performClick()
                    compose.onNodeWithTag("chat-save-note-${reply.id}").performClick()
                    compose.waitUntil(5000) {
                        runBlocking {
                            library.noteService.notes.first().any {
                                it.originMessageId ==
                                    reply.id
                            }
                        }
                    }
                    compose.onNodeWithTag("chat-save-note-${reply.id}").performClick()
                    compose.waitForIdle()
                    val saved =
                        runBlocking {
                            library.noteService.notes
                                .first()
                                .filter { it.originMessageId == reply.id }
                        }
                    assertEquals(1, saved.size)
                    assertEquals("NOTE", saved.single().kind)
                    assertEquals("DRAFT", saved.single().state)
                    assertEquals("fixture answer", saved.single().body)
                    assertEquals(1, requests.get())
                    captureKnowledgeComposer("reply-saved-note")
                } finally {
                    library.noteService.notes.first().filter { it.originMessageId == reply.id }.forEach {
                        library.noteService.management.remove(it)
                    }
                }
            }
        }

    @Test fun realComposerSelectionCancelAddAndRemoveNeverSend() =
        runBlocking {
            fixture { chat, session, _, _, requests ->
                val library = (compose.activity.application as com.helix.app.HelixApplication).knowledge
                val id = library.noteService.createEmptyTestNote("Synthetic composer reference")
                try {
                    library.noteService.save(
                        requireNotNull(library.noteService.get(id)),
                        "Synthetic composer reference",
                        "Selected synthetic text",
                        false,
                    )
                    compose.waitUntil(10_000) {
                        compose
                            .onAllNodes(hasTestTag("chat-input") and isEnabled())
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    compose.onNodeWithTag("chat-input").performTextReplacement("Keep this unsent question")

                    fun openPicker() {
                        compose.onNodeWithTag("chat-add").performClick()
                        compose.onNodeWithTag("composer-add-knowledge-reference").performScrollTo().performClick()
                        compose.waitUntil(5000) {
                            compose
                                .onAllNodes(hasTestTag("knowledge-reference-select-$id"))
                                .fetchSemanticsNodes()
                                .isNotEmpty()
                        }
                    }
                    openPicker()
                    compose.onNodeWithTag("knowledge-reference-select-$id").performClick()
                    compose.onNodeWithTag("knowledge-reference-picker-close").performClick()
                    compose.onNodeWithTag("chat-input").assertTextContains("Keep this unsent question")
                    val cancelled = compose.onAllNodes(hasTestTag("knowledge-citation-$id")).fetchSemanticsNodes()
                    assertTrue(cancelled.isEmpty())
                    openPicker()
                    compose.onNodeWithTag("knowledge-reference-select-$id").performClick()
                    compose.onNodeWithTag("knowledge-reference-apply").assertIsDisplayed().performClick()
                    compose.waitUntil(5000) {
                        compose.onAllNodes(hasTestTag("knowledge-citation-$id")).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithTag("chat-input").assertTextContains("Keep this unsent question")
                    captureKnowledgeComposer()
                    compose.onNodeWithTag("knowledge-citation-remove-$id").assertIsDisplayed().performClick()
                    compose.onNodeWithTag("chat-input").assertTextContains("Keep this unsent question")
                    assertEquals(0, requests.get())
                    assertTrue(
                        compose
                            .container()
                            .storage.turns
                            .listBySession(session)
                            .isEmpty(),
                    )
                    val removed = compose.onAllNodes(hasTestTag("knowledge-citation-$id")).fetchSemanticsNodes()
                    assertTrue(removed.isEmpty())
                } finally {
                    library.noteService.management.remove(requireNotNull(library.noteService.get(id)))
                }
            }
        }

    @Test fun libraryHandoffPreservesDraftAndRejectsAnotherTarget() =
        runBlocking {
            fixture { chat, session, _, _, requests ->
                val storage = compose.container().storage
                val library = (compose.activity.application as com.helix.app.HelixApplication).knowledge
                val id = library.noteService.createEmptyTestNote("Synthetic handoff")
                try {
                    library.noteService.save(
                        requireNotNull(library.noteService.get(id)),
                        "Synthetic handoff",
                        "First selected version",
                        false,
                    )
                    val first = library.references.select("NOTE", id, "FULL")
                    val draft = submission(session, "Keep my unsent question")
                    assertTrue(storage.composerDrafts.save(draft.toInputSnapshot()))
                    assertFalse(chat.stageKnowledgeReferences("different-session", listOf(first)).await())
                    assertTrue(chat.stageKnowledgeReferences(session, listOf(first)).await())
                    library.noteService.save(
                        requireNotNull(library.noteService.get(id)),
                        "Synthetic handoff",
                        "Explicitly selected new version",
                        false,
                    )
                    val replacement = library.references.select("NOTE", id, "FULL")
                    assertTrue(chat.stageKnowledgeReferences(session, listOf(replacement)).await())
                    val saved = requireNotNull(storage.composerDrafts.get(session)).toSubmission()
                    assertEquals("Keep my unsent question", saved.text)
                    assertEquals(listOf(replacement), saved.knowledgeReferences)
                    assertEquals(0, requests.get())
                    assertTrue(storage.turns.listBySession(session).isEmpty())
                } finally {
                    library.noteService.management.remove(requireNotNull(library.noteService.get(id)))
                    storage.composerDrafts.removeSession(session)
                }
            }
        }

    @Test fun changedCitationCannotBeSentByAnOlderConfirmation() =
        runBlocking {
            fixture { chat, session, _, _, requests ->
                val library = (compose.activity.application as com.helix.app.HelixApplication).knowledge
                val id = library.noteService.createEmptyTestNote("Synthetic confirmation")
                try {
                    library.noteService.save(
                        requireNotNull(library.noteService.get(id)),
                        "Synthetic confirmation",
                        "Original selected text",
                        false,
                    )
                    val reference = library.references.select("NOTE", id, "FULL")
                    val input = submission(session, "Question").copy(knowledgeReferences = listOf(reference))
                    val pending = chat.sendSubmission(input).await()
                    assertTrue(pending.outcome is ChatSubmissionOutcome.PendingConfirmation)
                    library.noteService.save(
                        requireNotNull(library.noteService.get(id)),
                        "Synthetic confirmation",
                        "Changed before confirming",
                        false,
                    )
                    assertTrue(chat.confirmSubmission(input).await().outcome is ChatSubmissionOutcome.Rejected)
                    assertEquals(0, requests.get())
                    assertTrue(
                        compose
                            .container()
                            .storage.turns
                            .listBySession(session)
                            .isEmpty(),
                    )
                } finally {
                    library.noteService.management.remove(requireNotNull(library.noteService.get(id)))
                }
            }
        }

    @Test fun queuedKnowledgeRemainsFrozenAndEditingDoesNotSend() =
        runBlocking {
            val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
            fixture(observe = { bodies += it }) { chat, session, entered, release, requests ->
                val library = (compose.activity.application as com.helix.app.HelixApplication).knowledge
                val noteId = library.noteService.createEmptyTestNote("Synthetic selected note")
                try {
                    library.noteService.save(
                        requireNotNull(library.noteService.get(noteId)),
                        "Synthetic selected note",
                        "FrozenSelectedEvidence",
                        false,
                    )
                    val reference = library.references.select("NOTE", noteId, "FULL")
                    compose.awaitAdmittedTurn(chat.sendSubmission(submission(session, "first")).await())
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    val input =
                        submission(session, "Question with citation")
                            .copy(knowledgeReferences = listOf(reference))
                    val held = chat.sendSubmission(input).await()
                    assertTrue(held.outcome is ChatSubmissionOutcome.PendingConfirmation)
                    assertEquals(1, requests.get())
                    assertTrue(chat.confirmSubmission(input).await().outcome is ChatSubmissionOutcome.Enqueued)
                    library.noteService.save(
                        requireNotNull(library.noteService.get(noteId)),
                        "Synthetic selected note",
                        "LaterChangedEvidence",
                        true,
                    )
                    release.countDown()
                    compose.waitUntil(20_000) { requests.get() == 2 && !chat.screen.value.isSending }
                    val storage = compose.container().storage
                    val accepted = requireNotNull(storage.sessionInputs.get(input.clientRequestId))
                    assertEquals(listOf(reference), accepted.knowledgeReferences)
                    val message = storage.messages.resolve(requireNotNull(accepted.messageId))
                    val storedBody = requireNotNull(storage.messages.readContent(message))
                    assertTrue(storedBody.contains("FrozenSelectedEvidence"))
                    assertFalse(storedBody.contains("LaterChangedEvidence"))
                    assertTrue(bodies.last().contains("FrozenSelectedEvidence"))
                    assertFalse(bodies.last().contains("LaterChangedEvidence"))
                    assertEquals(1, Regex("FrozenSelectedEvidence").findAll(bodies.last()).count())
                    val editor = UserMessageEditor(storage)
                    assertEquals("Question with citation", editor.load(session, message.id).text)
                    val editResult = editor.save(editor.load(session, message.id), "Edited question")
                    assertEquals(MessageEditResult.SAVED, editResult)
                    assertEquals("Edited question", editor.load(session, message.id).text)
                    val editedBody = requireNotNull(storage.messages.readContent(storage.messages.resolve(message.id)))
                    assertTrue(editedBody.contains(reference.text))
                    assertEquals(2, requests.get())
                    assertEquals(2, storage.turns.listBySession(session).size)
                } finally {
                    library.noteService.management.remove(requireNotNull(library.noteService.get(noteId)))
                }
            }
        }

    @Test fun sendQueuedNowKeepsIdentityAndJoinsOnlyTheDisplayedTurnOnce() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val first = compose.awaitAdmittedTurn(chat.sendSubmission(submission(session, "first")).await())
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val queued = submission(session, "send this now")
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                val before = chat.sessionInputQueue(session).await().single()
                assertFalse(chat.sendQueuedInputNow(before.inputId, before.revision, "stale-turn").await())
                assertEquals(before, chat.sessionInputQueue(session).await().single())
                assertTrue(chat.sendQueuedInputNow(before.inputId, before.revision, first).await())
                assertFalse(chat.sendQueuedInputNow(before.inputId, before.revision, first).await())
                // Retrying the original submit must still find its existing receipt after promotion.
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                val promoted = chat.sessionInputQueue(session).await().single()
                assertEquals(before.textRef, promoted.textRef)
                assertEquals(before.configuration, promoted.configuration)
                assertEquals(SessionInputDelivery.STEER, promoted.delivery)
                release.countDown()
                compose.waitUntil(15_000) { requests.get() == 2 && !chat.screen.value.isSending }
                val storage = compose.container().storage
                assertEquals(1, storage.turns.listBySession(session).size)
                assertEquals(first, storage.sessionInputs.get(before.inputId)?.consumedTurnId)
                assertEquals(
                    1,
                    storage.messages.listBySession(session).count {
                        it.role == "USER" && storage.messages.readContent(it) == "send this now"
                    },
                )
            }
        }

    @Test fun queueWaitsForFinalAnswerAndCreatesExactlyOneSuccessor() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val first = chat.sendSubmission(submission(session, "first")).await()
                compose.awaitAdmittedTurn(first)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val next = submission(session, "queued")
                val receipt = chat.sendSubmission(next).await()
                assertTrue(receipt.outcome is ChatSubmissionOutcome.Enqueued)
                assertTrue(chat.sendSubmission(next).await().outcome is ChatSubmissionOutcome.Enqueued)
                assertEquals(1, requests.get())
                assertEquals(
                    1,
                    compose
                        .container()
                        .storage.turns
                        .listBySession(session)
                        .size,
                )
                release.countDown()
                compose.waitUntil(15_000) { requests.get() == 2 && !chat.screen.value.isSending }
                val storage = compose.container().storage
                assertEquals(2, storage.turns.listBySession(session).size)
                assertEquals(
                    listOf("first", "queued"),
                    storage.messages
                        .listBySession(session)
                        .filter { it.role == "USER" }
                        .map(storage.messages::readContent),
                )
                assertNotNull(storage.sessionInputs.get(next.clientRequestId)?.requestModelCallId)
            }
        }

    @Test fun steerContinuesTheActiveTurnAfterItsAnswerWithoutCreatingAnotherTurn() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val receipt = chat.sendSubmission(submission(session, "first")).await()
                val first = compose.awaitAdmittedTurn(receipt)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val next =
                    submission(session, "steer").copy(
                        delivery = SessionInputDelivery.STEER,
                        expectedTurnId = first,
                    )
                assertTrue(chat.sendSubmission(next).await().outcome is ChatSubmissionOutcome.Enqueued)
                assertEquals(1, requests.get())
                release.countDown()
                compose.waitUntil(15_000) { requests.get() == 2 && !chat.screen.value.isSending }
                val storage = compose.container().storage
                assertEquals(1, storage.turns.listBySession(session).size)
                assertEquals(first, storage.sessionInputs.get(next.clientRequestId)?.consumedTurnId)
                assertEquals(SessionInputState.APPENDED, storage.sessionInputs.get(next.clientRequestId)?.state)
            }
        }

    @Test fun explicitStopParksTheOldQueueAndDoesNotDeliverItOnANewSend() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val receipt = chat.sendSubmission(submission(session, "first")).await()
                val first = compose.awaitAdmittedTurn(receipt)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val queued = submission(session, "old queued")
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                chat.stopTurn(first)
                release.countDown()
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                val storage = compose.container().storage
                assertEquals(
                    SessionInputState.NEEDS_ATTENTION,
                    storage.sessionInputs.get(queued.clientRequestId)?.state,
                )
                val fresh = chat.sendSubmission(submission(session, "new explicit")).await()
                compose.awaitAdmittedTurn(fresh)
                compose.waitUntil(15_000) { requests.get() == 2 && !chat.screen.value.isSending }
                assertEquals(
                    SessionInputState.NEEDS_ATTENTION,
                    storage.sessionInputs.get(queued.clientRequestId)?.state,
                )
                val hasOldQueuedMessage =
                    storage.messages.listBySession(session).any {
                        storage.messages.readContent(it) == "old queued"
                    }
                assertFalse(hasOldQueuedMessage)
            }
        }

    @Test
    fun parkedConversationReferenceRequiresFreshDisclosureBeforeResume() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val first = chat.sendSubmission(submission(session, "first")).await()
                val firstTurn = compose.awaitAdmittedTurn(first)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val storage = compose.container().storage
                val source = "reference-source-${UUID.randomUUID()}"
                storage.sessions.create(source, "Reference source", null, null, System.currentTimeMillis())
                try {
                    storage.withTransaction {
                        storage.messages.append(
                            "reference-source-message-${UUID.randomUUID()}",
                            source,
                            null,
                            "USER",
                            "TEXT",
                            "Frozen source context",
                        )
                    }
                    val queued =
                        submission(session, "").copy(
                            referenceSourceSessionId = source,
                            referenceKind = ConversationReferenceKind.RECENT_MESSAGES,
                        )
                    assertTrue(
                        chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.PendingConfirmation,
                    )
                    assertTrue(chat.confirmSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)

                    chat.stopTurn(firstTurn)
                    release.countDown()
                    compose.waitUntil(15_000) {
                        storage.sessionInputs.get(queued.clientRequestId)?.state == SessionInputState.NEEDS_ATTENTION &&
                            !chat.screen.value.isSending
                    }
                    val parked = requireNotNull(storage.sessionInputs.get(queued.clientRequestId))
                    val resume = chat.resumeSessionInput(parked.inputId, parked.revision).await()
                    assertTrue(resume is ChatSubmissionOutcome.PendingConfirmation)
                    assertEquals(parked.inputId to parked.revision, chat.pendingSessionInputResume())
                    assertTrue(chat.cancelSessionInputResume(parked.inputId, parked.revision).await())
                    assertEquals(SessionInputState.NEEDS_ATTENTION, storage.sessionInputs.get(parked.inputId)?.state)
                    assertEquals(1, requests.get())
                } finally {
                    storage.deleteSessionPermanently(source)
                }
            }
        }

    @Test fun queuedInputParksWhenSessionModelChangesBeforeResume() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val first = chat.sendSubmission(submission(session, "first")).await()
                val firstTurn = compose.awaitAdmittedTurn(first)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val queued = submission(session, "model-bound queued")
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)

                chat.stopTurn(firstTurn)
                release.countDown()
                val storage = compose.container().storage
                compose.waitUntil(15_000) {
                    storage.sessionInputs.get(queued.clientRequestId)?.state == SessionInputState.NEEDS_ATTENTION &&
                        !chat.screen.value.isSending
                }
                val parked = requireNotNull(storage.sessionInputs.get(queued.clientRequestId))
                val providerId = requireNotNull(storage.sessions.resolve(session).providerId)
                // The catalog is not the conversation candidate list: opt in before selecting B.
                compose.container().providerService.saveSelectedModels(providerId, listOf("fixture-model-b"))
                chat.selectSessionModel(providerId, "fixture-model-b")
                compose.waitUntil(15_000) {
                    storage.sessions.resolve(session).modelId == "fixture-model-b"
                }

                val resume = chat.resumeSessionInput(parked.inputId, parked.revision).await()
                assertTrue(resume is ChatSubmissionOutcome.Rejected)
                compose.waitUntil(15_000) {
                    storage.sessionInputs.get(parked.inputId)?.blockedReason == "INPUT_REVALIDATION_FAILED"
                }
                val final = requireNotNull(storage.sessionInputs.get(parked.inputId))
                assertEquals(SessionInputState.NEEDS_ATTENTION, final.state)
                assertNull(final.consumedTurnId)
                assertNull(final.messageId)
                assertNull(final.requestModelCallId)
                assertEquals(1, requests.get())
                assertFalse(
                    storage.messages.listBySession(session).any {
                        storage.messages.readContent(it) == "model-bound queued"
                    },
                )
            }
        }

    @Test fun queuedInputParksWhenAttachmentHashChangesBeforeResume() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val first = chat.sendSubmission(submission(session, "first")).await()
                val firstTurn = compose.awaitAdmittedTurn(first)
                assertTrue(entered.await(10, TimeUnit.SECONDS))

                chat.stageAttachment(
                    Uri
                        .parse(
                            "content://${TransferTestDocumentsProvider.authority()}/document/honest",
                        ).toString(),
                )
                compose.waitUntil(15_000) { chat.screen.value.pendingAttachments.size == 1 }
                val attachmentId =
                    chat.screen.value.pendingAttachments
                        .single()
                        .id
                val queued = submission(session, "attachment-bound queued").copy(attachmentIds = listOf(attachmentId))
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.PendingConfirmation)
                assertTrue(chat.confirmSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)

                chat.stopTurn(firstTurn)
                release.countDown()
                val storage = compose.container().storage
                compose.waitUntil(15_000) {
                    storage.sessionInputs.get(queued.clientRequestId)?.state == SessionInputState.NEEDS_ATTENTION &&
                        !chat.screen.value.isSending
                }
                val parked = requireNotNull(storage.sessionInputs.get(queued.clientRequestId))
                val artifact = storage.artifacts.resolve(attachmentId)
                val storedFile =
                    File(compose.activity.applicationContext.filesDir, "workspaces/app")
                        .toPath()
                        .resolve(FileScopePath.fromModelReference(artifact.relativePath).relativePath)
                        .toFile()
                storedFile.writeText("tampered attachment\n")

                val resume = chat.resumeSessionInput(parked.inputId, parked.revision).await()
                assertTrue(resume is ChatSubmissionOutcome.Rejected)
                compose.waitUntil(15_000) {
                    storage.sessionInputs.get(parked.inputId)?.blockedReason == "INPUT_REVALIDATION_FAILED"
                }
                val final = requireNotNull(storage.sessionInputs.get(parked.inputId))
                assertEquals(SessionInputState.NEEDS_ATTENTION, final.state)
                assertNull(final.consumedTurnId)
                assertNull(final.messageId)
                assertNull(final.requestModelCallId)
                assertEquals(1, requests.get())
                assertFalse(
                    storage.messages.listBySession(session).any {
                        storage.messages.readContent(it) == "attachment-bound queued"
                    },
                )
            }
        }

    @Test fun normalCompletionEndsUnrecoverableQueueWhenProviderEndpointChanged() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val first = chat.sendSubmission(submission(session, "first")).await()
                val firstTurn = compose.awaitAdmittedTurn(first)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val queued = submission(session, "auto-drain model-bound input")
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                val storage = compose.container().storage
                val providerId = requireNotNull(storage.sessions.resolve(session).providerId)
                val providers = compose.container().providerService
                val previous = providers.storedConfig(providerId)
                // Session model selection itself rejects active Turns; Provider settings can change.
                // Exercise that real entry without Stop, so terminal drain must reject the old target.
                providers.update(
                    providerId,
                    ProviderDraft(
                        null,
                        previous.displayName,
                        previous.protocol,
                        NormalizedEndpoint.parse(previous.endpoint.origin + "/changed"),
                        previous.model,
                        "{}",
                        false,
                    ),
                    null,
                )
                release.countDown()
                compose.waitUntil(15_000) {
                    storage.sessionInputs.get(queued.clientRequestId)?.state == SessionInputState.FAILED &&
                        !chat.screen.value.isSending
                }
                val parked = requireNotNull(storage.sessionInputs.get(queued.clientRequestId))
                assertEquals(
                    storage.turns.resolve(firstTurn).errorCode,
                    "COMPLETED",
                    storage.turns.resolve(firstTurn).state,
                )
                assertEquals("INPUT_REVALIDATION_FAILED", parked.blockedReason)
                assertRecoveryEnded(storage, parked)
                assertEquals(queued.text, storage.sessionInputs.readText(parked))
                assertNull(parked.consumedTurnId)
                assertNull(parked.messageId)
                assertNull(parked.requestModelCallId)
                assertEquals(1, requests.get())
                assertEquals(1, storage.turns.listBySession(session).size)
                assertEquals(
                    listOf("first"),
                    storage.messages
                        .listBySession(session)
                        .filter { it.role == "USER" }
                        .map(storage.messages::readContent),
                )
            }
        }

    @Test fun normalCompletionEndsUnrecoverableQueueWhenAttachmentBytesChanged() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val first = chat.sendSubmission(submission(session, "first")).await()
                val firstTurn = compose.awaitAdmittedTurn(first)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                chat.stageAttachment(
                    "content://${TransferTestDocumentsProvider.authority()}/document/honest",
                )
                compose.waitUntil(15_000) { chat.screen.value.pendingAttachments.size == 1 }
                val attachmentId =
                    chat.screen.value.pendingAttachments
                        .single()
                        .id
                val queued =
                    submission(session, "auto-drain attachment-bound input")
                        .copy(attachmentIds = listOf(attachmentId))
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.PendingConfirmation)
                assertTrue(chat.confirmSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                val storage = compose.container().storage
                val artifact = storage.artifacts.resolve(attachmentId)
                val storedFile =
                    File(compose.activity.applicationContext.filesDir, "workspaces/app")
                        .toPath()
                        .resolve(FileScopePath.fromModelReference(artifact.relativePath).relativePath)
                        .toFile()
                storedFile.writeText("changed after queued confirmation\n")
                release.countDown()
                compose.waitUntil(15_000) {
                    storage.sessionInputs.get(queued.clientRequestId)?.state == SessionInputState.FAILED &&
                        !chat.screen.value.isSending
                }
                val parked = requireNotNull(storage.sessionInputs.get(queued.clientRequestId))
                assertEquals(
                    storage.turns.resolve(firstTurn).errorCode,
                    "COMPLETED",
                    storage.turns.resolve(firstTurn).state,
                )
                assertEquals("INPUT_REVALIDATION_FAILED", parked.blockedReason)
                assertRecoveryEnded(storage, parked)
                assertEquals(queued.text, storage.sessionInputs.readText(parked))
                assertEquals(listOf(attachmentId), parked.attachments.map { it.artifactId })
                assertNull(parked.consumedTurnId)
                assertNull(parked.messageId)
                assertNull(parked.requestModelCallId)
                assertEquals(1, requests.get())
                assertEquals(1, storage.turns.listBySession(session).size)
                assertEquals(
                    listOf("first"),
                    storage.messages
                        .listBySession(session)
                        .filter { it.role == "USER" }
                        .map(storage.messages::readContent),
                )
            }
        }

    private fun captureKnowledgeComposer(name: String = "conversation-citations") {
        val profile =
            androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
                .getString("knowledgeUiCapture") ?: return
        require(profile.matches(Regex("[a-z0-9-]{1,30}")))
        val bitmap =
            com.helix.app.ui
                .stableChatLayoutScreenshot()
        val root = File(compose.activity.cacheDir, "knowledge-ui-captures")
        check(root.isDirectory || root.mkdirs())
        try {
            File(root, "$profile-$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertRecoveryEnded(
        storage: com.helix.core.storage.HelixStorage,
        input: com.helix.core.storage.repository.SessionInputRecord,
    ) {
        assertEquals(SessionInputState.FAILED, input.state)
        assertEquals(
            1,
            storage.auditEvents.listByCorrelation(input.sessionId).count {
                it.id == "input-recovery:${input.inputId}"
            },
        )
        assertEquals(
            1,
            storage.messages.listBySession(input.sessionId).count {
                it.id == "input-ended:${input.inputId}" && it.kind == "RECOVERY_NOTICE"
            },
        )
    }

    private fun submission(
        session: String,
        text: String,
    ): ChatSubmission = ChatSubmission(session, 0, UUID.randomUUID().toString(), text)

    private suspend fun fixture(
        observe: (String) -> Unit = {},
        block: suspend (ChatService, String, CountDownLatch, CountDownLatch, AtomicInteger) -> Unit,
    ) {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService
        val previous = chat.runControl.value
        LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
            server.start()
            val provider =
                container.providerService.create(
                    ProviderDraft(
                        null,
                        "Input queue fixture",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                        "fixture-model-a",
                        "{}",
                        false,
                    ),
                    null,
                )
            val release = CountDownLatch(1)
            check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
            val session = chat.createSession("Queue fixture", provider, "fixture-model-a")
            try {
                val entered = CountDownLatch(1)
                val requests = AtomicInteger()
                server.scriptedChat = { body ->
                    observe(body)
                    if (requests.incrementAndGet() == 1) {
                        entered.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "Input test did not release the first request" }
                    }
                    textAnswerStream("fixture answer")
                }
                openQueueFixture(chat, session)
                block(chat, session, entered, release, requests)
            } finally {
                release.countDown()
                println(
                    "QUEUE_FIXTURE_TERMINAL=${container.storage.turns.listBySession(
                        session,
                    ).map { it.state to it.errorCode }}",
                )
                container.storage.turns
                    .listBySession(session)
                    .forEach { chat.stopTurn(it.id) }
                compose.waitUntil(10_000) { !chat.screen.value.isSending }
                chat.closeSession()
                chat.setTurnBudgets(previous.budgets)
                container.storage.sessions.archive(session, System.currentTimeMillis())
                container.providerService.delete(provider)
            }
        }
    }

    private fun openQueueFixture(
        chat: ChatService,
        session: String,
    ) {
        chat.openSession(session)
        // Queue/Steer success must not inherit another fixture's one-token negative budget.
        val budgets = com.helix.core.agent.TurnBudgetBounds.DEFAULT
        chat.setTurnBudgets(budgets)
        compose.waitUntil(10_000) {
            chat.screen.value.openSessionId == session &&
                compose
                    .container()
                    .storage.sessionRunControls
                    .forSession(session)
                    ?.budgets == budgets
        }
    }
}
