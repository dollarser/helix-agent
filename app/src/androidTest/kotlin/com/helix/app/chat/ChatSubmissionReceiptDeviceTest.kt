package com.helix.app.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
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
import java.util.UUID

class ChatSubmissionReceiptDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun rejectedSubmissionPreservesComposerInputAndMapsSafeReason() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            // Create session with no provider bound (providerId = null)
            val sessionId = UUID.randomUUID().toString()
            storage.sessions.create(sessionId, "Unbound session", null, null, 1)
            try {
                chat.openSession(sessionId)
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == sessionId }

                compose.onNodeWithTag("chat-input").performTextInput("Draft text to preserve")
                compose.onNodeWithTag("chat-send").performClick()

                // Submission is rejected because NO_PROVIDER
                compose.waitUntil(10_000) {
                    chat.screen.value.blockedReason != null
                }

                // Verify input was preserved on screen
                compose.onNodeWithTag("chat-input").assertTextEquals("Draft text to preserve")
                // Verify blocked reason is human-readable localized message, not raw internal code
                val blocked = chat.screen.value.blockedReason
                assertNotNull(blocked)
                assertFalse(blocked!!.contains("NO_PROVIDER"))
                assertFalse(blocked.contains("ADMISSION_FAILED"))
                assertEquals(compose.activity.getString(R.string.chat_blocked_no_provider_bound), blocked)
            } finally {
                chat.closeSession()
                storage.sessions.archive(sessionId, System.currentTimeMillis())
            }
        }

    @Test
    fun acceptedSubmissionClearsComposerAndAcknowledgesDraft() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val providerId = createProvider(server.port)
                val session = chat.createSession("Accepted receipt session", providerId, "fixture-model-a")
                try {
                    chat.openSession(session)
                    compose.waitUntil(10_000) { chat.screen.value.openSessionId == session }

                    compose.onNodeWithTag("chat-input").performTextInput("Hello Assistant")
                    compose.onNodeWithTag("chat-send").performClick()

                    // Wait for accepted turn and input cleared
                    compose.waitUntil(10_000) {
                        chat.screen.value.activeTurn != null &&
                            chat.screen.value.messages
                                .any { it.role == "user" }
                    }

                    // On Accepted, the screen input should be cleared
                    compose.onNodeWithTag("chat-input").assertTextEquals("")
                    // And the composer_drafts table should be acknowledged and cleared
                    compose.waitUntil(10_000) {
                        storage.composerDrafts.get(session) == null
                    }
                    assertNull(storage.composerDrafts.get(session))
                } finally {
                    chat.stop()
                    chat.closeSession()
                    storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(providerId)
                }
            }
        }

    @Test
    fun sessionSwitchDoesNotClearDifferentSessionOnLateReceipt() =
        runBlocking {
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            val sessionA = UUID.randomUUID().toString()
            val sessionB = UUID.randomUUID().toString()
            storage.sessions.create(sessionA, "Session A", null, null, 1)
            storage.sessions.create(sessionB, "Session B", null, null, 2)
            try {
                // Draft in A
                val draftA = ChatSubmission(sessionA, 0L, "req-a", "Draft in session A")
                assertTrue(chat.saveComposerDraft(draftA, null))

                // Draft in B
                val draftB = ChatSubmission(sessionB, 0L, "req-b", "Draft in session B")
                assertTrue(chat.saveComposerDraft(draftB, null))

                // Open B
                chat.openSession(sessionB)
                val loadedB = chat.loadComposerDraft(sessionB)
                assertEquals("Draft in session B", loadedB?.text)

                // Suppose receipt for A arrives as accepted for turn-a
                val receiptA = ChatSubmissionReceipt(draftA, ChatSubmissionOutcome.Accepted("turn-a"))
                assertFalse(chat.acknowledgeSubmission(receiptA))

                // B's draft is untouched
                val currentB = storage.composerDrafts.get(sessionB)?.toSubmission()
                assertEquals(draftB, currentB)
            } finally {
                chat.closeSession()
                storage.sessions.archive(sessionA, System.currentTimeMillis())
                storage.sessions.archive(sessionB, System.currentTimeMillis())
            }
        }

    @Test
    fun editedDraftDoesNotClearOnStaleReceipt() =
        runBlocking {
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            val session = UUID.randomUUID().toString()
            storage.sessions.create(session, "Session Edit", null, null, 1)
            try {
                val draftV0 = ChatSubmission(session, 0L, "req-v0", "Initial draft")
                assertTrue(chat.saveComposerDraft(draftV0, null))

                // Edit to v1
                val draftV1 = ChatSubmission(session, 1L, "req-v1", "Edited draft")
                assertTrue(chat.saveComposerDraft(draftV1, 0L))

                // Stale v0 receipt arrives
                val receiptV0 = ChatSubmissionReceipt(draftV0, ChatSubmissionOutcome.Accepted("turn-v0"))
                assertFalse(chat.acknowledgeSubmission(receiptV0))

                // v1 draft remains intact
                val current = storage.composerDrafts.get(session)?.toSubmission()
                assertEquals(draftV1, current)
            } finally {
                storage.sessions.archive(session, System.currentTimeMillis())
            }
        }

    @Test
    fun duplicateSubmissionDeduplicatesWithoutStartingSecondTurn() =
        runBlocking {
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val providerId = createProvider(server.port)
                val session = chat.createSession("Dedup session", providerId, "fixture-model-a")
                try {
                    chat.openSession(session)
                    val reqId = UUID.randomUUID().toString()
                    val submission = ChatSubmission(session, 0L, reqId, "Dedup message")

                    val receipt1 = chat.sendSubmission(submission).await()
                    val outcome1 = receipt1.outcome as ChatSubmissionOutcome.Accepted

                    // Second send with same snapshot
                    val receipt2 = chat.sendSubmission(submission).await()
                    val outcome2 = receipt2.outcome as ChatSubmissionOutcome.Accepted

                    assertEquals(outcome1.turnId, outcome2.turnId)
                    assertEquals(1, storage.turns.listBySession(session).size)
                } finally {
                    chat.stop()
                    chat.closeSession()
                    storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(providerId)
                }
            }
        }

    private suspend fun createProvider(port: Int): String {
        val service = compose.container().providerService
        val id =
            service.create(
                ProviderDraft(
                    null,
                    "Submission receipt fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                    "fixture-model-a",
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        try {
            check(service.runConnectionTest(id) is ProbeOutcome.Ok)
        } catch (failure: Throwable) {
            service.delete(id)
            throw failure
        }
        return id
    }
}
