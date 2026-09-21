package com.helix.app.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.helix.app.AppContainer
import com.helix.app.MainActivity
import com.helix.app.provider.ProviderDraft
import com.helix.app.provider.ScriptedTaskModelServer
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** A deterministic model fixture, real chat/dispatcher/files/UI. This is not model-quality evaluation. */
class ProductFileJourneyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun readsMaterialModifiesFileAndOpensVerifiedArtifactWithoutExtraModelCalls() =
        journey(
            SessionPermissionMode.FULL_ACCESS,
            "readsMaterialModifiesFileAndOpensVerifiedArtifactWithoutExtraModelCalls",
        )

    @Test
    fun workspacePresetCompletesTheSameFileTaskWithoutApprovalCards() =
        journey(SessionPermissionMode.WORKSPACE, "workspacePresetCompletesTheSameFileTaskWithoutApprovalCards")

    @Test
    fun readOnlyPresetApprovesTheMutationOnceThenOpensItsArtifact() =
        journey(SessionPermissionMode.READ_ONLY, "readOnlyPresetApprovesTheMutationOnceThenOpensItsArtifact")

    @Test
    fun customWorkspaceCopyCompletesTheFileTaskWithoutApprovalCards() =
        journey(SessionPermissionMode.CUSTOM, "customWorkspaceCopyCompletesTheFileTaskWithoutApprovalCards")

    @Suppress("LongMethod") // One measured user journey, with all owned fixture cleanup in finally.
    private fun journey(mode: SessionPermissionMode, method: String) =
        runBlocking<Unit> {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val previous = chat.runControl.value
            val relative = "output/product-${UUID.randomUUID()}.txt"
            val file = File(compose.activity.filesDir, "workspaces/app/$relative")
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            file.writeText("source material\n")
            ScriptedTaskModelServer().use { server ->
                server.start()
                val provider = createProvider(container, server.port)
                val session = chat.createSession("Product file journey", provider, ScriptedTaskModelServer.MODEL_ID)
                try {
                    val config =
                        if (mode == SessionPermissionMode.CUSTOM) {
                            SessionPermissionConfig.custom(
                                SessionPermissionConfig.copyPreset(SessionPermissionMode.WORKSPACE),
                            )
                        } else {
                            SessionPermissionConfig.of(mode)
                        }
                    container.sessionPermissionEdit.saveSessionConfig(
                        session,
                        config,
                        System.currentTimeMillis(),
                    )
                    val permissionBefore = container.sessionPermissionEdit.activeConfigFor(session)
                    chat.openSession(session)
                    chat.setMode(AgentMode.ACT)
                    chat.setChatToolsEnabled(true)
                    val path = "scope:app:$relative"
                    server.arm(
                        listOf(
                            ScriptedTaskModelServer.Step("read") { JSONObject().put("path", path).toString() },
                            ScriptedTaskModelServer.Step("write") {
                                JSONObject()
                                    .put("path", path)
                                    .put("content", "source material\nreviewed result\n")
                                    .put("overwrite", true)
                                    .toString()
                            },
                            ScriptedTaskModelServer.Step("read") { JSONObject().put("path", path).toString() },
                        ),
                        "The reviewed result is ready.",
                    )
                    var steps = 0
                    compose.waitUntil(10000) {
                        compose.onAllNodesWithTag("chat-input").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose
                        .onNodeWithTag("chat-input")
                        .performTextInput("Read the material, update it, and show the result.")
                    steps++
                    compose.onNodeWithTag("chat-send").performClick()
                    steps++
                    if (mode == SessionPermissionMode.READ_ONLY) {
                        compose.waitUntil(30000) {
                            container.storage.turns
                                .listBySession(session)
                                .flatMap { container.storage.toolCalls.listByTurn(it.id) }
                                .any { container.storage.approvals.byToolCall(it.id) != null }
                        }
                        val pendingTurn =
                            container.storage.turns
                                .listBySession(session)
                                .single()
                        val approval =
                            container.storage.toolCalls
                                .listByTurn(pendingTurn.id)
                                .mapNotNull { container.storage.approvals.byToolCall(it.id) }
                                .single()
                        val tag = "approval-approve-${approval.id}"
                        compose.onNodeWithTag("chat-timeline").performScrollToNode(hasTestTag(tag))
                        compose.onNodeWithTag(tag).performClick()
                        steps++
                    }
                    compose.waitUntil(45000) {
                        container.storage.turns
                            .listBySession(session)
                            .any { TurnState.valueOf(it.state).isTerminal }
                    }
                    val turn =
                        container.storage.turns
                            .listBySession(session)
                            .single()
                    assertEquals("COMPLETED", turn.state)
                    val calls = container.storage.toolCalls.listByTurn(turn.id)
                    assertEquals(listOf("read", "write", "read"), calls.map { it.name })
                    assertTrue(
                        "Unexpected call states: ${calls.map { it.name to it.state }}",
                        calls.all {
                            it.state ==
                                "COMPLETED"
                        },
                    )
                    val approvals = calls.count { container.storage.approvals.byToolCall(it.id) != null }
                    assertEquals(
                        "Unexpected repeated or missing approval card",
                        if (mode == SessionPermissionMode.READ_ONLY) 1 else 0,
                        approvals,
                    )
                    assertEquals("source material\nreviewed result\n", file.readText())
                    val result = requireNotNull(container.storage.toolResults.byToolCall(calls.last().id))
                    val resultContent = requireNotNull(container.storage.toolResults.readContent(result))
                    assertTrue(resultContent.contains("reviewed result"))
                    val modelCallsBeforeOpen =
                        container.storage.modelCalls
                            .listByTurn(turn.id)
                            .size
                    val artifact =
                        container.storage.artifacts
                            .listByTurn(turn.id)
                            .single { it.relativePath == path }
                    compose.onNodeWithTag("open-navigation").performClick()
                    steps++
                    compose.waitForIdle()
                    compose.onNodeWithTag("navigation-artifacts").performClick()
                    steps++
                    compose.waitForIdle()
                    compose.waitUntil(10000) {
                        compose.onAllNodesWithTag("artifact-file-row-${artifact.id}").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithTag("artifact-file-row-${artifact.id}").performScrollTo().performClick()
                    steps++
                    compose.waitUntil(10000) {
                        compose
                            .onAllNodesWithTag("artifact-file-preview", useUnmergedTree = true)
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    compose
                        .onNodeWithTag("artifact-file-preview", useUnmergedTree = true)
                        .assertTextContains("reviewed result", substring = true)
                    assertEquals(
                        modelCallsBeforeOpen,
                        container.storage.modelCalls
                            .listByTurn(turn.id)
                            .size,
                    )
                    assertEquals(permissionBefore, container.sessionPermissionEdit.activeConfigFor(session))
                    android.util.Log.i(
                        "HelixAcceptance",
                        JSONObject()
                            .put("test_class", this@ProductFileJourneyDeviceTest.javaClass.name)
                            .put("test_method", method)
                            .put(
                                "metrics",
                                JSONObject()
                                    .put("user_steps", steps)
                                    .put("approval_cards", approvals)
                                    .put("artifact_openable", true)
                                    .put("extra_model_calls_on_open", 0)
                                    .put("write_executions", calls.count { it.name == "write" }),
                            ).toString(),
                    )
                } finally {
                    chat.stop()
                    compose.waitUntil(10000) { !chat.screen.value.isSending }
                    chat.closeSession()
                    chat.setMode(previous.mode)
                    chat.setChatToolsEnabled(previous.chatToolsEnabled)
                    chat.setTurnBudgets(previous.budgets)
                    container.storage.deleteSessionPermanently(session)
                    container.providerService.delete(provider)
                    file.delete()
                }
            }
        }

    private suspend fun createProvider(
        container: AppContainer,
        port: Int,
    ): String {
        val id =
            container.providerService.create(
                ProviderDraft(
                    null,
                    "Product fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                    ScriptedTaskModelServer.MODEL_ID,
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        assertTrue(container.providerService.runConnectionTest(id) is ProbeOutcome.Ok)
        return id
    }
}
