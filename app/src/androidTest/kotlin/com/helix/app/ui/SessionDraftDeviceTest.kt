package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionDraftDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Suppress("LongMethod") // one lifecycle with guaranteed cleanup
    @Test
    fun draftSavesOnlyOnSendAndMetadataSurvivesReopen() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val service = container.providerService
                val provider =
                    service.create(
                        ProviderDraft(
                            null,
                            "Draft fixture",
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
                try {
                    check(service.runConnectionTest(provider) is ProbeOutcome.Ok)
                    val before = storage.sessions.list().size
                    chat.newSessionDraft()
                    compose.waitUntil { chat.screen.value.isDraft }
                    chat.closeSession()
                    compose.waitUntil { chat.screen.value.openSessionId == null }
                    assertEquals(before, storage.sessions.list().size)
                    chat.newSessionDraft()
                    compose.waitUntil { chat.screen.value.isDraft }
                    chat.bindProviderToSession(provider, "fixture-model-a")
                    chat.setSessionDirectory("scope:app:work")
                    compose.waitUntil { chat.screen.value.directoryRef == "scope:app:work" }
                    val id = requireNotNull(chat.screen.value.openSessionId)
                    chat.send("First question about a project")
                    chat.send("Must not create another turn")
                    compose.waitUntil(20_000) { storage.sessions.list().any { it.id == id } }
                    assertEquals(before + 1, storage.sessions.list().size)
                    assertEquals("First question about", storage.sessions.resolve(id).title)
                    compose.waitUntil(20_000) {
                        chat.screen.value.activeTurn
                            ?.state
                            ?.isTerminal == true
                    }
                    compose.onNodeWithTag("chat-title").performClick()
                    compose.onNodeWithTag("session-title").performTextReplacement("Renamed")
                    compose.onNodeWithTag("session-rename-save").performClick()
                    compose.waitUntil { storage.sessions.resolve(id).title == "Renamed" }
                    chat.closeSession()
                    chat.openSession(id)
                    compose.waitUntil { chat.screen.value.sessionTitle == "Renamed" }
                    assertEquals("scope:app:work", chat.screen.value.directoryRef)
                    assertEquals(1, storage.turns.listBySession(id).size)
                } finally {
                    chat.stop()
                    chat.closeSession()
                    service.delete(provider)
                }
            }
        }
}
