package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionModelDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod")
    fun selectionPersistsAndNextRequestUsesTheModelWhileActiveSelectionIsRejected() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            val service = container.providerService
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider =
                    service.create(
                        ProviderDraft(
                            null,
                            "Models fixture",
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
                val alternate =
                    service.create(
                        ProviderDraft(
                            null,
                            "Second provider",
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
                var session: String? = null
                try {
                    check(service.runConnectionTest(provider) is ProbeOutcome.Ok)
                    check(service.runConnectionTest(alternate) is ProbeOutcome.Ok)
                    chat.newSessionDraft()
                    compose.waitUntil { chat.screen.value.isDraft }
                    chat.selectSessionModel(provider, "fixture-model-b")
                    compose.waitUntil {
                        chat.screen.value.badge
                            ?.model == "fixture-model-b"
                    }
                    assertTrue(storage.sessions.list().none { it.id == chat.screen.value.openSessionId })
                    chat.send("First model choice")
                    compose.waitUntil(20_000) {
                        chat.screen.value.activeTurn
                            ?.state
                            ?.isTerminal == true
                    }
                    session = requireNotNull(chat.screen.value.openSessionId)
                    val id = session
                    val history = storage.messages.listBySession(id)
                    assertTrue(
                        server.lastChatRequest
                            .get()
                            .orEmpty()
                            .contains("\"model\":\"fixture-model-b\""),
                    )
                    chat.setReasoning(ReasoningEffort.MEDIUM)
                    compose.onNodeWithTag("chat-model-menu").performClick()
                    compose.onNodeWithTag("chat-model-$alternate-fixture-model-c").performClick()
                    compose.waitUntil {
                        chat.screen.value.badge
                            ?.model == "fixture-model-c"
                    }
                    assertEquals(ReasoningEffort.OFF, chat.runControl.value.reasoning)
                    assertEquals(alternate, storage.sessions.resolve(id).providerId)
                    assertEquals(history, storage.messages.listBySession(id))
                    chat.closeSession()
                    chat.openSession(id)
                    compose.waitUntil {
                        chat.screen.value.badge
                            ?.model == "fixture-model-c"
                    }
                    compose.onNodeWithTag("chat-model-menu").assertIsDisplayed()
                    server.holdChatStreams.set(true)
                    chat.send("Hold selected model")
                    compose.waitUntil(20_000) { server.heldStreams.get() == 1 }
                    compose.onNodeWithTag("chat-model-menu").assertIsNotEnabled()
                    chat.selectSessionModel(provider, "fixture-model-a")
                    compose.waitForIdle()
                    assertEquals("fixture-model-c", storage.sessions.resolve(id).modelId)
                    assertTrue(
                        server.lastChatRequest
                            .get()
                            .orEmpty()
                            .contains("\"model\":\"fixture-model-c\""),
                    )
                    var rejected = false
                    try {
                        storage.sessions.selectModel(id, provider, "fixture-model-a")
                    } catch (
                        _: IllegalArgumentException,
                    ) {
                        rejected =
                            true
                    }
                    assertTrue("Storage also refuses a live target change", rejected)
                } finally {
                    chat.stop()
                    compose.waitUntil(10_000) { !chat.screen.value.isSending }
                    chat.closeSession()
                    session?.let { storage.sessions.archive(it, System.currentTimeMillis()) }
                    service.delete(provider)
                    service.delete(alternate)
                }
            }
        }
}
