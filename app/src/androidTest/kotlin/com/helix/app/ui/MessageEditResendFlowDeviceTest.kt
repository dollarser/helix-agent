package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.MainActivity
import com.helix.app.agent.ContextCompaction
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@Suppress("LongMethod") // One end-to-end interaction with provider payload assertions.
class MessageEditResendFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun latestEditStaysInSessionAndActualProviderRequestExcludesReplacedSuffix() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                server.forceTextResponses = true
                val provider =
                    container.providerService.create(
                        ProviderDraft(
                            null,
                            "Revision fixture",
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
                check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                val session = chat.createSession("Revision fixture", provider, "fixture-model-a")
                val target = UUID.randomUUID().toString()
                val first = UUID.randomUUID().toString()
                try {
                    storage.messages.append(first, session, null, "USER", "TEXT", "KEEP-PREFIX-42")
                    storage.messages.append(target, session, null, "USER", "TEXT", "OLD-INPUT-99")
                    storage.messages.append(
                        UUID.randomUUID().toString(),
                        session,
                        null,
                        "ASSISTANT",
                        "TEXT",
                        "OLD-ANSWER-99",
                    )
                    storage.messages.append(
                        UUID.randomUUID().toString(),
                        session,
                        null,
                        "ASSISTANT",
                        "TOOL_CALLS",
                        """[{"id":"old-call","name":"read","arguments":"{}"}]""",
                    )
                    storage.messages.append(
                        UUID.randomUUID().toString(),
                        session,
                        null,
                        "TOOL",
                        "TOOL_RESULT",
                        """{"id":"old-call","tool":"read","status":"SUCCEEDED","summary":"OLD-TOOL-99"}""",
                    )
                    storage.messages.append(
                        UUID.randomUUID().toString(),
                        session,
                        null,
                        "ASSISTANT",
                        ContextCompaction.KIND,
                        """{"coveredThrough":4,"summary":"OLD-SUMMARY-99"}""",
                    )
                    storage.messages.append(
                        UUID.randomUUID().toString(),
                        session,
                        null,
                        "SYSTEM",
                        "SESSION_FORK",
                        "ORIGIN-METADATA-99",
                    )
                    chat.openSession(session)
                    chat.setMode(AgentMode.CHAT)
                    compose.waitUntil(10_000) { chat.screen.value.messages.size == 3 }
                    compose.onNodeWithTag("chat-edit-$first").assertDoesNotExist()
                    compose.onNodeWithTag("chat-edit-$target").performScrollTo().performClick()
                    compose.waitUntil(
                        10_000,
                    ) { runBlocking { chat.loadComposerDraft(session) }?.revisedMessageId == target }
                    compose.waitUntil(10_000) {
                        compose
                            .onAllNodesWithTag("message-revision-input", useUnmergedTree = true)
                            .fetchSemanticsNodes()
                            .size == 1
                    }
                    compose
                        .onNodeWithTag("message-revision-input", useUnmergedTree = true)
                        .performTextReplacement("NEW-INPUT-77")
                    compose.onNodeWithTag("message-revision-send").performClick()
                    compose.waitUntil(20_000) {
                        storage.turns
                            .listBySession(session)
                            .lastOrNull()
                            ?.state == "COMPLETED"
                    }
                    val outgoing = server.lastChatRequest.get().orEmpty()
                    assertTrue(outgoing.contains("KEEP-PREFIX-42"))
                    assertTrue(outgoing.contains("NEW-INPUT-77"))
                    assertFalse(outgoing.contains("OLD-INPUT-99"))
                    assertFalse(outgoing.contains("OLD-ANSWER-99"))
                    assertFalse(outgoing.contains("OLD-SUMMARY-99"))
                    assertFalse(outgoing.contains("OLD-TOOL-99"))
                    assertFalse(outgoing.contains("old-call"))
                    assertFalse(outgoing.contains("ORIGIN-METADATA-99"))
                    assertEquals(session, chat.screen.value.openSessionId)
                    assertEquals("OLD-INPUT-99", storage.messages.readContent(storage.messages.resolve(target)))
                    compose.waitUntil(10_000) {
                        chat.screen.value.messages
                            .none { it.id == target }
                    }
                    assertTrue(storage.messages.resolve(target).supersededBy != null)
                    assertTrue(chat.screen.value.isFork)
                } finally {
                    chat.closeSession()
                    container.providerService.delete(provider)
                }
            }
        }
}
