package com.helix.app.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.MainActivity
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.MessageHistoryContext
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Native UI with a synthetic loopback Provider; edit must never request model generation. */
@Suppress("LongMethod") // One bounded interaction verifies UI, persistence, draft and no-generation together.
class MessageEditFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun olderMessageEditKeepsDraftAndRepliesWithoutSendingOrForking() =
        runBlocking<Unit> {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider =
                    container.providerService.create(
                        ProviderDraft(
                            null,
                            "Independent edit fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                            "fixture-model-a",
                            "{}",
                            false,
                        ),
                        null,
                    )
                check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                server.lastChatRequest.set(null)
                val session = chat.createSession("Independent edit fixture", provider, "fixture-model-a")
                val user = UUID.randomUUID().toString()
                val answer = UUID.randomUUID().toString()
                try {
                    storage.messages.append(user, session, null, "USER", "TEXT", "original input")
                    storage.messages.append(answer, session, null, "ASSISTANT", "TEXT", "retained answer")
                    storage.messages.append(UUID.randomUUID().toString(), session, null, "USER", "TEXT", "later input")
                    chat.saveComposerDraft(ChatSubmission(session, 1, "ordinary-draft", "unsent draft"))
                    chat.openSession(session)
                    chat.setMode(AgentMode.CHAT)
                    compose.waitUntil(10_000) { chat.screen.value.messages.size == 3 }
                    compose.onNodeWithTag("chat-message-toggle-$user").performScrollTo().performClick()
                    compose.onNodeWithTag("chat-edit-$user").performScrollTo().performClick()
                    awaitEditor("original input")
                    compose.onNodeWithTag("message-edit-input").performTextReplacement("discarded candidate")
                    compose.onNodeWithTag("message-edit-cancel").performClick()
                    assertEquals("original input", storage.messages.readContent(storage.messages.resolve(user)))
                    compose.onNodeWithTag("chat-edit-$user").performScrollTo().performClick()
                    awaitEditor("original input")
                    compose.onNodeWithTag("message-edit-input").assertTextContains("original input")
                    compose.onNodeWithTag("message-edit-input").performTextReplacement("edited input")
                    compose.runOnUiThread { compose.activity.recreate() }
                    compose.waitForIdle()
                    awaitEditor("edited input")
                    compose.onNodeWithTag("message-edit-input").assertTextContains("edited input")
                    compose.onNodeWithTag("message-edit-save").performClick()
                    compose.waitUntil(10_000) {
                        chat.screen.value.messages
                            .any { it.id == user && it.content == "edited input" }
                    }
                    assertEquals("edited input", storage.messages.readContent(storage.messages.resolve(user)))
                    assertEquals("retained answer", storage.messages.readContent(storage.messages.resolve(answer)))
                    assertNull(storage.messages.resolve(user).supersededBy)
                    assertEquals(
                        MessageHistoryContext.BEFORE_EDIT,
                        chat.screen.value.messages
                            .single { it.id == answer }
                            .historyContext,
                    )
                    assertTrue(storage.turns.listBySession(session).isEmpty())
                    assertEquals(1, storage.messages.allRevisions(session).count { it.kind == "MESSAGE_EDIT_EVIDENCE" })
                    assertFalse(chat.screen.value.isFork)
                    assertNull(server.lastChatRequest.get())
                    assertEquals(session, chat.screen.value.openSessionId)
                    compose.onNodeWithTag("chat-input").assertTextContains("unsent draft")
                } finally {
                    chat.closeSession()
                    container.providerService.delete(provider)
                }
            }
        }

    private fun awaitEditor(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("message-edit-input") and hasText(text)).fetchSemanticsNodes().size == 1
        }
    }
}
