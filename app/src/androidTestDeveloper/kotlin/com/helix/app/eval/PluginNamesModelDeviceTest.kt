package com.helix.app.eval

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.openTestSessionControl
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.AgentMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real ChatService -> prompt/readiness -> configured model -> durable answer; no phone/browser action. */
class PluginNamesModelDeviceTest {
    @Test
    @Suppress("LongMethod") // Each owned conversation is stopped before restoring the original conversation.
    fun configuredModelRecognizesChineseAndEnglishNamesFromProductionCatalog() =
        runBlocking(Dispatchers.IO) {
            val args = InstrumentationRegistry.getArguments()
            val optIn = args.getString("helixPluginNamesModel")
            assumeTrue("Explicit real-model plugin-name test required", optIn != null)
            require(optIn == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            app.runtimeRecoveryReady.await()
            val c = app.appContainer
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            ) { "Owner task active; do not disturb" }
            val (providerId, modelId) = configuredModel(c, args)
            val original = c.chatService.screen.value.openSessionId
            val installed = c.pluginService.list()
            val expected =
                mapOf(
                    "mobile-use" to "手机操作",
                    "browser" to "浏览器",
                    "documents" to "文档",
                    "chat-reply" to "消息回复",
                )
            assertTrue(installed.mapNotNull { it.native?.pluginId }.containsAll(expected.keys))
            val prompts =
                listOf(
                    "请根据本次已安装插件目录，逐项给出手机操作、浏览器、文档、消息回复的英文名称和原始插件 ID。" +
                        "每个插件一行，每行保留中文名、英文名和 ID。只回答映射，不执行任何工具，不打开、选择或启用插件。",
                    "For Mobile Use, Browser, Documents and Chat Reply, give each Chinese display name " +
                        "and original installed plugin ID from the current plugin catalog. " +
                        "Use one line per plugin, containing both names and the ID. " +
                        "Do not call tools, open, select or enable any plugin.",
                )
            for ((index, prompt) in prompts.withIndex()) {
                val session =
                    c.chatService.createSession(
                        "Plugin names model fixture $index",
                        providerId,
                        modelId,
                    )
                val selected = c.pluginService.catalog.effectiveSelected(session)
                try {
                    val readiness = c.pluginService.requestReadiness(session)
                    for ((id, chinese) in expected) {
                        val row = readiness.plugins.single { it.pluginId == "bundled-plugin:$id" }
                        assertTrue(row.name.aliases.contains(chinese))
                    }
                    c.chatService.openTestSessionControl(session, AgentMode.ACT, TurnBudgets(maxOutputTokens = 2048))
                    val admission =
                        c.chatService
                            .sendSubmission(
                                ChatSubmission(session, 0, "plugin-names-$index-${System.nanoTime()}", prompt),
                            ).await()
                    assertTrue(admission.outcome.toString(), admission.outcome is ChatSubmissionOutcome.Accepted)
                    val deadline = SystemClock.elapsedRealtime() + 180_000
                    while (SystemClock.elapsedRealtime() < deadline &&
                        c.storage.turns
                            .listBySession(session)
                            .lastOrNull()
                            ?.let { TurnState.valueOf(it.state).isTerminal } != true
                    ) {
                        delay(200)
                    }
                    val turn =
                        c.storage.turns
                            .listBySession(session)
                            .last()
                    assertEquals(turn.errorCode, TurnState.COMPLETED.name, turn.state)
                    assertTrue(
                        c.storage.modelCalls
                            .listByTurn(turn.id)
                            .isNotEmpty(),
                    )
                    val answer =
                        c.storage.messages
                            .listBySession(session)
                            .last { it.role == "ASSISTANT" && it.kind == "TEXT" }
                            .let { requireNotNull(c.storage.messages.readContent(it)) }
                    expected.forEach { (id, chinese) ->
                        val english =
                            com.helix.app.plugin
                                .PluginName(id, id)
                                .displayName(listOf("en"))
                        assertTrue(
                            "Incorrect mapping for $chinese / $english / $id in retained answer",
                            answer.lineSequence().any {
                                it.contains(id) && it.contains(chinese) &&
                                    it.contains(english, ignoreCase = true)
                            },
                        )
                    }
                    assertTrue(
                        "Name recognition must not execute tools",
                        c.storage.toolCalls
                            .listByTurn(turn.id)
                            .isEmpty(),
                    )
                    assertEquals(selected, c.pluginService.catalog.effectiveSelected(session))
                    assertEquals(installed, c.pluginService.list())
                    println("PLUGIN_NAMES_MODEL_PASS language=${if (index == 0) "zh" else "en"} session=$session")
                } finally {
                    withContext(NonCancellable) {
                        stopAndJoinOwnedVisualCase(c, session)
                        original?.let { c.chatService.openSession(it) } ?: c.chatService.closeSession()
                    }
                }
            }
        }

    private suspend fun configuredModel(
        c: com.helix.app.AppContainer,
        args: android.os.Bundle,
    ): Pair<String, String> {
        val providerOverride = args.getString("helixPluginNamesProvider")
        val modelOverride = args.getString("helixPluginNamesModelId")
        require((providerOverride == null) == (modelOverride == null))
        val providerId = providerOverride ?: BuiltInModel.PROVIDER_ID
        val modelId = modelOverride ?: BuiltInModel.MODEL_ID
        if (BuiltInModel.owns(providerId)) c.providerService.builtInModels.refresh(force = false)
        check(c.providerService.chatSelectable(providerId)) { "Configured provider is not ready for chat" }
        return providerId to modelId
    }
}
