package com.helix.app.provider

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnState
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, opt-in real-account smoke. Normal CI skips it and never consumes subscription quota. */
@RunWith(AndroidJUnit4::class)
class CodexSubscriptionProviderRealAccountDeviceTest {
    @get:org.junit.Rule
    val activity =
        androidx.test.ext.junit.rules
            .ActivityScenarioRule(MainActivity::class.java)

    @Test fun realSubscriptionCompletesThroughHelixChat() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("realSubscription") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val container = app.appContainer
            val providerId =
                when (InstrumentationRegistry.getArguments().getString("realProvider")) {
                    null, "codex" -> SubscriptionProviderModule.CODEX_ID
                    "copilot" -> SubscriptionProviderModule.COPILOT_ID
                    else -> error("unsupported real-account smoke provider")
                }
            verifyAndReportRuntimeState(app)
            val probe = container.providerService.runConnectionTest(providerId)
            assertTrue("subscription probe failed safely: $probe", probe is ProbeOutcome.Ok)
            if (InstrumentationRegistry.getArguments().getString("probeOnly") == "true") return@runBlocking
            val row =
                container.providerService.rows.value
                    .single { it.id == providerId }

            val arguments = InstrumentationRegistry.getArguments()
            val selectedModel = arguments.getString("realModel") ?: row.model
            val effort = ReasoningEffort.valueOf(arguments.getString("realEffort") ?: "OFF")
            val mode = AgentMode.valueOf(arguments.getString("realMode") ?: "CHAT")
            require(mode != AgentMode.GOAL)
            val previous = container.runControlStore.current
            val sessionId = container.chatService.createSession("subscription mode smoke", row.id, selectedModel)
            container.chatService.openSession(sessionId)
            await("session opens") { container.chatService.screen.value.openSessionId == sessionId }
            try {
                container.chatService.setMode(mode)
                container.chatService.setReasoning(effort)
                if (effort != ReasoningEffort.OFF) {
                    assertTrue(
                        "effort discovered for selected model",
                        effort in container.providerService.reasoningOptions(row.id, selectedModel),
                    )
                }
                container.chatService.send(PROMPT)
                await("real model turn terminates", timeoutMillis = 120_000) {
                    container.chatService.screen.value.activeTurn
                        ?.state
                        ?.isTerminal == true
                }

                assertCompletion(container, sessionId)
            } finally {
                container.runControlStore.setMode(previous.mode)
                container.runControlStore.setReasoning(previous.reasoning)
            }
        }

    /**
     * Verifies the CLI runtime for the real-account smoke, streams its state to the instrumentation
     * output, and (opt-in via the runtimeForeground argument) brings the runtime app to the
     * foreground for manual observation. Setup only — no assertions live here.
     */
    private suspend fun verifyAndReportRuntimeState(app: HelixApplication) {
        val runtimeState =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.helix.runtime.cli.client
                    .CliRuntimeSupervisor(app)
                    .verify()
            }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("stream", "runtime=$runtimeState\n")
            },
        )
        if (InstrumentationRegistry.getArguments().getString("runtimeForeground") == "true") {
            app.startActivity(
                android.content
                    .Intent()
                    .setComponent(
                        android.content.ComponentName(
                            "com.helix.runtime.cli",
                            "com.helix.runtime.cli.app.CliRuntimeHomeActivity",
                        ),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Thread.sleep(1000)
        }
    }

    private fun assertCompletion(
        container: com.helix.app.AppContainer,
        sessionId: String,
    ) {
        val turn =
            container.storage.turns
                .listBySession(sessionId)
                .single()
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val diagnostics =
            container.storage.modelCalls.listByTurn(turn.id).map { modelCall ->
                runCatching {
                    SubscriptionResultStore(container.storage, java.io.File(app.filesDir, "workspaces/app"))
                        .readLocal(LocalModelCallContext(turn.id, modelCall.id))
                        ?.filterIsInstance<com.helix.core.model.ModelEvent.Error>()
                }.fold({ "errors=$it" }, { "localResult=" + it.javaClass.simpleName })
            }
        assertEquals(
            "${container.chatService.screen.value.activeTurn?.errorLabel}; $diagnostics",
            TurnState.COMPLETED.name,
            turn.state,
        )
        assertEquals(
            1,
            container.storage.modelCalls
                .listByTurn(turn.id)
                .size,
        )
        assertEquals(
            "HELIX_OK",
            container.chatService.screen.value.messages
                .last()
                .content
                .trim(),
        )
    }

    private fun await(
        label: String,
        timeoutMillis: Long = 4_000,
        condition: () -> Boolean,
    ) {
        repeat((timeoutMillis / 20).toInt()) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("timed out: $label")
    }

    private companion object {
        const val PROMPT = "Reply exactly HELIX_OK and nothing else."
    }
}
