package com.helix.app.chat

import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.test.TransferTestDocumentsProvider
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputState
import com.helix.core.workspace.FileScopePath
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
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

    @Test fun queueWaitsForFinalAnswerAndCreatesExactlyOneSuccessor() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val first = chat.sendSubmission(submission(session, "first")).await()
                assertTrue(first.outcome is ChatSubmissionOutcome.Accepted)
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
                val first = receipt.outcome as ChatSubmissionOutcome.Accepted
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val next =
                    submission(session, "steer").copy(
                        delivery = SessionInputDelivery.STEER,
                        expectedTurnId = first.turnId,
                    )
                assertTrue(chat.sendSubmission(next).await().outcome is ChatSubmissionOutcome.Enqueued)
                assertEquals(1, requests.get())
                release.countDown()
                compose.waitUntil(15_000) { requests.get() == 2 && !chat.screen.value.isSending }
                val storage = compose.container().storage
                assertEquals(1, storage.turns.listBySession(session).size)
                assertEquals(first.turnId, storage.sessionInputs.get(next.clientRequestId)?.consumedTurnId)
                assertEquals(SessionInputState.APPENDED, storage.sessionInputs.get(next.clientRequestId)?.state)
            }
        }

    @Test fun explicitStopParksTheOldQueueAndDoesNotDeliverItOnANewSend() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val receipt = chat.sendSubmission(submission(session, "first")).await()
                val first = receipt.outcome as ChatSubmissionOutcome.Accepted
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val queued = submission(session, "old queued")
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                chat.stopTurn(first.turnId)
                release.countDown()
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                val storage = compose.container().storage
                assertEquals(
                    SessionInputState.NEEDS_ATTENTION,
                    storage.sessionInputs.get(queued.clientRequestId)?.state,
                )
                val fresh = chat.sendSubmission(submission(session, "new explicit")).await()
                assertTrue(fresh.outcome is ChatSubmissionOutcome.Accepted)
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

    @Test fun queuedInputParksWhenSessionModelChangesBeforeResume() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val first = chat.sendSubmission(submission(session, "first")).await()
                val firstTurn = (first.outcome as ChatSubmissionOutcome.Accepted).turnId
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
                val firstTurn = (first.outcome as ChatSubmissionOutcome.Accepted).turnId
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

    @Test fun normalCompletionParksQueuedInputWhenProviderEndpointChanged() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val first = chat.sendSubmission(submission(session, "first")).await()
                val firstTurn = (first.outcome as ChatSubmissionOutcome.Accepted).turnId
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
                        CleartextAuthorization(previous.endpoint.host, previous.endpoint.port),
                        emptyList(),
                    ),
                    null,
                    cleartextConfirmed = true,
                )
                release.countDown()
                compose.waitUntil(15_000) {
                    storage.sessionInputs.get(queued.clientRequestId)?.state == SessionInputState.NEEDS_ATTENTION &&
                        !chat.screen.value.isSending
                }
                val parked = requireNotNull(storage.sessionInputs.get(queued.clientRequestId))
                assertEquals("COMPLETED", storage.turns.resolve(firstTurn).state)
                assertEquals("INPUT_REVALIDATION_FAILED", parked.blockedReason)
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

    @Test fun normalCompletionParksQueuedInputWhenAttachmentBytesChanged() =
        runBlocking {
            fixture { chat, session, entered, release, requests ->
                val first = chat.sendSubmission(submission(session, "first")).await()
                val firstTurn = (first.outcome as ChatSubmissionOutcome.Accepted).turnId
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
                    storage.sessionInputs.get(queued.clientRequestId)?.state == SessionInputState.NEEDS_ATTENTION &&
                        !chat.screen.value.isSending
                }
                val parked = requireNotNull(storage.sessionInputs.get(queued.clientRequestId))
                assertEquals("COMPLETED", storage.turns.resolve(firstTurn).state)
                assertEquals("INPUT_REVALIDATION_FAILED", parked.blockedReason)
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

    private fun submission(
        session: String,
        text: String,
    ): ChatSubmission = ChatSubmission(session, 0, UUID.randomUUID().toString(), text)

    private suspend fun fixture(
        block: suspend (ChatService, String, CountDownLatch, CountDownLatch, AtomicInteger) -> Unit,
    ) {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService
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
                        CleartextAuthorization("127.0.0.1", server.port),
                        emptyList(),
                    ),
                    null,
                    cleartextConfirmed = true,
                )
            val release = CountDownLatch(1)
            check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
            val session = chat.createSession("Queue fixture", provider, "fixture-model-a")
            try {
                val entered = CountDownLatch(1)
                val requests = AtomicInteger()
                server.scriptedChat = {
                    if (requests.incrementAndGet() == 1) {
                        entered.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "Input test did not release the first request" }
                    }
                    textAnswerStream("fixture answer")
                }
                chat.openSession(session)
                block(chat, session, entered, release, requests)
            } finally {
                release.countDown()
                container.storage.turns
                    .listBySession(session)
                    .forEach { chat.stopTurn(it.id) }
                compose.waitUntil(10_000) { !chat.screen.value.isSending }
                chat.closeSession()
                container.storage.sessions.archive(session, System.currentTimeMillis())
                container.providerService.delete(provider)
            }
        }
    }
}
