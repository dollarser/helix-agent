package com.helix.app.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.chat.ConversationContent
import com.helix.app.chat.conversationEntries
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Owner-opt-in keyless loopback Qwen; only synthetic workspace files. */
class LiveConversationPresentationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // One bounded real provider setup, task, assertions and cleanup.
    fun actualModelToolsRetainTheirPlacesAndContextCountsTheCommittedAnswer() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = args.getString("contextPort")?.toIntOrNull()
            val model = args.getString("contextModel")
            assumeTrue("Explicit model profile required", port != null && model != null)
            requireNotNull(port)
            requireNotNull(model)
            compose.resetDeterministicUiState()
            val c = compose.container()
            val chat = c.chatService
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        null,
                        "Presentation fixture",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                        model,
                        "{}",
                        false,
                    ),
                    null,
                )
            var session: String? = null
            try {
                check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                c.providerService.discoverContextWindow(provider, model)
                val id = chat.createSession("Presentation fixture", provider, model)
                session = id
                c.sessionPermissionEdit.saveSessionConfig(
                    id,
                    SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE_TRUSTED),
                    System.currentTimeMillis(),
                )
                chat.openSession(id)
                chat.setMode(AgentMode.ACT)
                chat.setReasoning(ReasoningEffort.OFF)
                chat.sendTestMessage(
                    id,
                    "先简短说明接下来写文件，再调用write把文本HELIX-PRESENTATION-42" +
                        "写到当前工作区presentation-check.txt。然后调用read读回，核实内容。" +
                        "最后简短回复实际结果。不要操作手机或其他文件。",
                )
                compose.waitUntil(240_000) {
                    c.storage.turns
                        .listBySession(id)
                        .lastOrNull()
                        ?.let { TurnState.valueOf(it.state).isTerminal } == true
                }
                val turn =
                    c.storage.turns
                        .listBySession(id)
                        .last()
                assertEquals(turn.errorCode, "COMPLETED", turn.state)
                val calls = c.storage.toolCalls.listByTurn(turn.id)
                assertTrue(
                    "Must actually write and read",
                    calls.any { it.name == "write" } && calls.any { it.name == "read" },
                )
                calls.forEach { call ->
                    assertEquals("COMPLETED", call.state)
                    assertEquals(
                        "SUCCEEDED",
                        c.storage.toolResults
                            .byToolCall(call.callId)
                            ?.status,
                    )
                }
                val finalMessage =
                    c.storage.messages.listBySession(id).last {
                        it.role == "ASSISTANT" && it.kind == "TEXT"
                    }
                compose.waitUntil(
                    10_000,
                ) {
                    val snapshot = chat.screen.value
                    snapshot.activeTurn?.state?.isTerminal == true &&
                        snapshot.messages.any { it.id == finalMessage.id } &&
                        snapshot.toolTimeline.size == calls.size
                }
                val entry = conversationEntries(chat.screen.value).single { it.key == turn.id }
                assertEquals(calls.map { it.callId }.toSet(), entry.tools.map { it.callId }.toSet())
                assertTrue(entry.tools.all { it.position != null })
                println(
                    "LIVE_TIMELINE raw=" +
                        c.storage.messages.listBySession(id).joinToString {
                            "${it.sequence}:${it.role}:${it.kind}"
                        } + " visible=" + entry.timeline.joinToString { "${it.sequence}:${it.key}" },
                )
                assertTrue(entry.timeline.last() is ConversationContent.Message)
                val keys = entry.timeline.map { it.key }
                chat.closeSession()
                chat.openSession(id)
                compose.waitUntil(10_000) {
                    chat.screen.value.openSessionId == id &&
                        chat.screen.value.toolTimeline.size == calls.size
                }
                assertEquals(
                    keys,
                    conversationEntries(chat.screen.value).single { it.key == turn.id }.timeline.map { it.key },
                )
                val usage = chat.screen.value.contextUsage
                assertNotNull(usage.projectedTokens)
                assertTrue(requireNotNull(usage.usedTokens) > requireNotNull(usage.inputTokens))
                compose.onNodeWithTag("chat-context-window").performClick()
                compose
                    .onNodeWithTag(
                        "chat-context-usage-details",
                    ).assertTextContains(requireNotNull(usage.usedTokens).toString(), substring = true)
                println(
                    "LIVE_CONVERSATION_PRESENTATION=PASS calls=${calls.size} " +
                        "input=${usage.inputTokens} projected=${usage.usedTokens}",
                )
            } finally {
                chat.closeSession()
                session?.let { c.storage.sessions.archive(it, System.currentTimeMillis()) }
                c.providerService.delete(provider)
            }
        }
}
