package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.MainActivity
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

class SessionForkFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun messageActionBranchesAndNextModelRequestUsesOnlySelectedHistory() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                server.forceTextResponses = true
                val service = container.providerService
                val provider =
                    service.create(
                        providerDraft(server.port),
                        null,
                        cleartextConfirmed = true,
                    )
                check(service.runConnectionTest(provider) is ProbeOutcome.Ok)
                val source = chat.createSession("Fork fixture", provider, "fixture-model-a")
                val target = UUID.randomUUID().toString()
                try {
                    val firstId = UUID.randomUUID().toString()
                    val futureId = UUID.randomUUID().toString()
                    storage.messages.append(firstId, source, null, "USER", "TEXT", "KEEP-ORANGE-42")
                    storage.messages.append(target, source, null, "ASSISTANT", "TEXT", "Selected answer")
                    storage.messages.append(futureId, source, null, "USER", "TEXT", "EXCLUDE-FUTURE-99")
                    chat.openSession(source)
                    chat.setMode(AgentMode.CHAT)
                    compose.waitUntil(10_000) { chat.screen.value.messages.size == 3 }
                    compose.onNodeWithTag("chat-fork-$target").performScrollTo().performClick()
                    compose.waitUntil(10_000) { chat.screen.value.openSessionId != source && chat.screen.value.isFork }
                    val branch = requireNotNull(chat.screen.value.openSessionId)
                    compose.onNodeWithTag("session-fork-notice").assertExists()
                    assertTrue(storage.turns.listBySession(branch).isEmpty())
                    chat.send("Continue the alternate approach")
                    compose.waitUntil(20_000) {
                        storage.turns
                            .listBySession(branch)
                            .lastOrNull()
                            ?.state == "COMPLETED"
                    }
                    assertRequest(server.lastChatRequest.get().orEmpty())
                    assertEquals(3, storage.messages.listBySession(source).size)
                    compose.waitUntil(10_000) { chat.screen.value.messages.size >= 4 }
                    val entries =
                        com.helix.app.chat
                            .conversationEntries(chat.screen.value)
                    assertEquals(
                        "KEEP-ORANGE-42",
                        entries
                            .first()
                            .messages
                            .first()
                            .content,
                    )
                } finally {
                    chat.closeSession()
                    service.delete(provider)
                }
            }
        }

    private fun assertRequest(request: String) {
        assertTrue(request.contains("KEEP-ORANGE-42"))
        assertTrue(request.contains("Selected answer"))
        assertFalse(request.contains("EXCLUDE-FUTURE-99"))
        assertFalse(request.contains("sourceSessionId"))
    }

    private fun providerDraft(port: Int) =
        ProviderDraft(
            null,
            "Fork fixture",
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
            "fixture-model-a",
            "{}",
            false,
            CleartextAuthorization("127.0.0.1", port),
            emptyList(),
        )

    @Test fun corruptHistoryShowsFailureAndEarlierMessageCanStillFork() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            val source = UUID.randomUUID().toString()
            val first = UUID.randomUUID().toString()
            val last = UUID.randomUUID().toString()
            storage.sessions.create(source, "Recovery fork fixture", null, null, 1)
            storage.messages.append(first, source, null, "USER", "TEXT", "safe prefix")
            storage.messages.append(UUID.randomUUID().toString(), source, null, "ASSISTANT", "TOOL_CALLS", "invalid")
            storage.messages.append(last, source, null, "ASSISTANT", "TEXT", "after corrupt protocol")
            chat.openSession(source)
            compose.waitUntil(10_000) { chat.screen.value.messages.size == 2 }
            compose.onNodeWithTag("chat-fork-$last").performScrollTo().performClick()
            compose.waitUntil(10_000) { chat.screen.value.blockedReason != null }
            assertEquals(source, chat.screen.value.openSessionId)
            compose.onNodeWithTag("chat-blocked-reason").assertExists()
            compose.onNodeWithTag("chat-fork-$first").performScrollTo().performClick()
            compose.waitUntil(10_000) { chat.screen.value.openSessionId != source && chat.screen.value.isFork }
            assertEquals(
                "safe prefix",
                chat.screen.value.messages
                    .single()
                    .content,
            )
            chat.closeSession()
        }
}
