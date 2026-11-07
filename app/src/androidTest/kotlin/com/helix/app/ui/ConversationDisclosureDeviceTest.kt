package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.chat.textAnswerStream
import com.helix.app.chat.toolCallStream
import com.helix.app.openTestSessionControl
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.test.discoverFixtureTool
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Production shell, conversation submission and Dispatcher output; no fabricated chat/artifact rows. */
class ConversationDisclosureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // One owned conversation covers quiet states and the actual tool-output handoff.
    fun secondaryInformationStaysInItsSingleOnDemandLocation() =
        runBlocking<Unit> {
            val c = compose.container()

            val originalSession = c.chatService.screen.value.openSessionId
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            compose.resetDeterministicUiState()
            assertQuiet()
            compose.onNodeWithTag("chat-context-window").assertIsDisplayed()
            assertEquals(0L, c.chatService.screen.value.contextUsage.usedTokens)
            screenshot("new-draft")
            val chat = c.chatService
            val owned = mutableListOf<String>()
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider =
                    c.providerService.create(
                        ProviderDraft(
                            null,
                            "Disclosure fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                            "fixture-model-a",
                            "{}",
                            false,
                        ),
                        null,
                    )
                try {
                    check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                    check(c.providerService.runCapabilityTest(provider) is ProbeOutcome.Ok)
                    val session = chat.createSession("Conversation disclosure", provider, "fixture-model-a")
                    owned += session
                    chat.openTestSessionControl(session, AgentMode.ACT, chat.runControl.value.budgets)
                    server.scriptedChat = { textAnswerStream("已收到这段会话。") }
                    compose.onNodeWithTag("chat-input").performTextReplacement("记录一段普通会话")
                    compose.onNodeWithTag("chat-send").performClick()
                    awaitCompleted(session, 1)
                    assertQuiet()
                    screenshot("existing-no-outputs")
                    compose.onNodeWithTag("chat-conversation-details").performClick()
                    compose.onNodeWithTag("session-settings-open").assertIsDisplayed()
                    compose.onNodeWithTag("chat-artifacts-open").assertIsDisplayed().performClick()
                    compose.onNodeWithTag("chat-conversation-details-sheet").assertDoesNotExist()
                    compose.waitUntil(
                        10_000,
                    ) { compose.onAllNodesWithTag("chat-artifacts-empty").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("chat-artifacts-empty").assertIsDisplayed()
                    compose.onNodeWithTag("chat-artifacts-close").performClick()
                    compose.onNodeWithTag("conversation-project").assertDoesNotExist()
                    compose.onNodeWithTag("conversation-directory").assertDoesNotExist()
                    compose.onNodeWithTag("chat-composer-options").performClick()
                    compose.onNodeWithTag("conversation-project").assertDoesNotExist()
                    compose.onNodeWithTag("chat-artifacts-open").assertDoesNotExist()
                    compose.onNodeWithTag("chat-composer-options-close").performClick()
                    val requests = AtomicInteger()
                    server.scriptedChat = {
                        if (requests.incrementAndGet() == 1) {
                            toolCallStream(
                                "disclosure-write",
                                Json.encodeToString("write"),
                                """{"path":"disclosure-result.txt","content":"A real Dispatcher output"}""",
                            )
                        } else {
                            textAnswerStream("文件已生成。")
                        }
                    }
                    c.sessionPermissionEdit.saveSessionConfig(
                        session,
                        SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                        System.currentTimeMillis(),
                    )
                    chat.setChatToolsEnabled(true)
                    compose.waitUntil(10_000) { chat.runControl.value.chatToolsEnabled }
                    discoverFixtureTool(c.toolPipeline, session, "write")
                    compose.onNodeWithTag("chat-input").performTextReplacement("生成本次测试文件")
                    compose.onNodeWithTag("chat-send").performClick()
                    awaitCompleted(session, 2)
                    val turn =
                        c.storage.turns
                            .listBySession(session)
                            .last()
                    assertTrue(
                        c.storage.toolCalls
                            .listByTurn(turn.id)
                            .any { it.name == "write" && it.state == "COMPLETED" },
                    )
                    val outputs = c.conversationDetails.conversationArtifacts(session)
                    assertEquals(1, outputs.size)
                    assertQuiet()
                    screenshot("existing-with-output")
                    compose.onNodeWithTag("chat-conversation-details").performClick()
                    compose.waitUntil(
                        10_000,
                    ) { compose.onAllNodesWithTag("chat-artifacts-open").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("chat-artifacts-open").performClick()
                    compose.waitUntil(10_000) {
                        compose
                            .onAllNodesWithTag(
                                "artifact-file-row-${outputs.single().id}",
                            ).fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    compose.onNodeWithTag("artifact-file-row-${outputs.single().id}").performClick()
                    compose.waitUntil(
                        10_000,
                    ) { compose.onAllNodesWithTag("artifact-file-preview").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("artifact-file-preview").assertIsDisplayed()
                    compose.onNodeWithText("A real Dispatcher output", substring = true).assertIsDisplayed()
                    screenshot("output-on-demand")
                    compose.onNodeWithTag("chat-artifacts-close").performClick()
                    assertQuiet()
                } finally {
                    chat.stop()
                    compose.waitUntil(10_000) { !chat.screen.value.isSending }
                    chat.closeSession()
                    owned.forEach { c.storage.deleteSessionPermanently(it) }
                    c.providerService.delete(provider)
                    originalSession?.takeIf { c.storage.sessions.find(it) != null }?.let(chat::openSession)
                }
            }
        }

    private fun assertQuiet() {
        compose
            .onAllNodes(
                SemanticsMatcher("No per-turn artifact shortcut") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("turn-artifacts-") == true
                },
            ).assertCountEquals(0)
        listOf("conversation-project", "conversation-directory", "chat-artifacts-open", "chat-document-sources")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithTag("chat-header").assertIsDisplayed()
        compose.onNodeWithTag("chat-input").assertIsDisplayed()
        compose.onNodeWithTag("chat-context-window").assertIsDisplayed()
    }

    private fun awaitCompleted(
        session: String,
        count: Int,
    ) {
        val storage = compose.container().storage
        compose.waitUntil(20_000) {
            val turns = storage.turns.listBySession(session)
            turns.size == count && turns.last().state == "COMPLETED"
        }
    }

    private fun screenshot(name: String) {
        val app = compose.activity.application
        val out = File(app.filesDir, "chat-disclosure-evidence").apply { mkdirs() }
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(out, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
