package com.helix.app.provider

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.core.model.AgentMode
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.ProbeOutcome
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Full ChatService + independent Runtime fixture; never sends account traffic. */
@RunWith(AndroidJUnit4::class)
class SubscriptionChatBoundaryDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container = app.appContainer
    private val chat = container.chatService
    private val storage = container.storage
    private val sessions = mutableListOf<String>()
    private val platforms = listOf("codex", "claude", "grok", "copilot").map { "subscription-$it" }

    @Test fun exhaustedBudgetNeverStartsSubscriptionJob() =
        forEachPlatform { provider ->
            chat.setTurnBudgets(TurnBudgets(2, 1, 1, 1, 1))
            val session = start(provider, "helix-fixture")
            val turn = terminal(session)
            assertEquals("TOKEN_BUDGET_LIMIT", turn.errorCode)
            assertEquals(TurnState.FAILED.name, turn.state)
            assertTrue(storage.auditEvents.listByCorrelation(session).none { it.type == "cli.job_prepared" })
            assertTrue(storage.modelCalls.listByTurn(turn.id).all { it.state == "FAILED" })
        }

    @Test fun stopCancelsTheOriginalRuntimeJob() =
        forEachPlatform { provider ->
            val session = start(provider, "helix-fixture-wait")
            val client = CliModelJobClient(CliRuntimeSupervisor(app))
            val jobId = runningJob(session, client)
            chat.stop()
            assertEquals(TurnState.CANCELLED.name, terminal(session).state)
            awaitSubscriptionBoundary {
                (client.query(jobId) as? CliModelJobClient.StateOutcome.Ok)?.record?.state == CliModelJobState.CANCELLED
            }
            assertEquals(1, storage.modelCalls.listByTurn(terminal(session).id).size)
        }

    @Test fun runtimeDeathFailsChatWithoutReplay() =
        forEachPlatform { provider ->
            val session = start(provider, "helix-fixture-wait")
            val client = CliModelJobClient(CliRuntimeSupervisor(app))
            val jobId = runningJob(session, client)
            client.debugKillRuntime()
            assertEquals(TurnState.FAILED.name, terminal(session).state)
            val record = (client.query(jobId) as CliModelJobClient.StateOutcome.Ok).record
            assertEquals(CliModelJobState.INTERRUPTED, record.state)
            chat.closeSession()
            chat.openSession(session)
            awaitSubscriptionBoundary { chat.screen.value.openSessionId == session }
            assertEquals(record, (client.query(jobId) as CliModelJobClient.StateOutcome.Ok).record)
            assertEquals(1, storage.modelCalls.listByTurn(terminal(session).id).size)
        }

    @Test fun switchingSessionsPreservesProviderAndCompletedTurns() =
        forEachPlatform { provider ->
            val first = start(provider, "helix-fixture")
            assertEquals(TurnState.COMPLETED.name, terminal(first).state)
            val nextProvider = platforms[(platforms.indexOf(provider) + 1) % platforms.size]
            val second = start(nextProvider, "helix-fixture")
            assertEquals(TurnState.COMPLETED.name, terminal(second).state)
            chat.openSession(first)
            awaitSubscriptionBoundary { chat.screen.value.openSessionId == first && !chat.screen.value.isSending }
            assertEquals(provider, storage.sessions.resolve(first).providerId)
            assertEquals(nextProvider, storage.sessions.resolve(second).providerId)
            assertPlatform(first, provider)
            assertPlatform(second, nextProvider)
            assertEquals(
                storage.providerConfigs.resolve(provider).displayName,
                chat.screen.value.badge
                    ?.displayName,
            )
            assertEquals(
                "HELIX_OK",
                chat.screen.value.messages
                    .last()
                    .content,
            )
            assertEquals(1, storage.turns.listBySession(first).size)
            assertEquals(1, storage.turns.listBySession(second).size)
        }

    private fun assertPlatform(
        session: String,
        provider: String,
    ) {
        val turn = storage.turns.listBySession(session).single()
        val call = storage.modelCalls.listByTurn(turn.id).single()
        val binding = SubscriptionJobBindingStore(storage).resolve(call.id)
        assertEquals(
            provider.removePrefix("subscription-").uppercase(),
            (binding.getValue("platform") as JsonPrimitive).content,
        )
    }

    private fun forEachPlatform(check: suspend (String) -> Unit) =
        runBlocking {
            val previous = chat.runControl.value
            val originals = platforms.map(storage.providerConfigs::resolve)
            try {
                chat.setMode(AgentMode.CHAT)
                chat.setChatToolsEnabled(false)
                for (original in originals) {
                    storage.providerConfigs.overwrite(spec(original, "helix-fixture"))
                    assertTrue(container.providerService.runConnectionTest(original.id) is ProbeOutcome.Ok)
                }
                for (provider in platforms) {
                    chat.setTurnBudgets(TurnBudgets(3, 2, 10000, 128, 10000))
                    check(provider)
                }
            } finally {
                chat.stop()
                awaitSubscriptionBoundary { !chat.screen.value.isSending }
                chat.closeSession()
                try {
                    sessions.forEach { session ->
                        val paths = storage.artifacts.listBySession(session).map { it.relativePath }
                        container.privacyDeletionService.deleteSession(session)
                        paths.forEach { path ->
                            assertTrue(!java.io.File(app.filesDir, "workspaces/app/$path").exists())
                        }
                    }
                } finally {
                    for (original in originals) storage.providerConfigs.overwrite(spec(original, original.model))
                    container.providerService.refresh()
                    chat.setMode(previous.mode)
                    chat.setChatToolsEnabled(previous.chatToolsEnabled)
                    chat.setTurnBudgets(previous.budgets)
                }
            }
        }

    private fun spec(
        original: com.helix.core.storage.entity.ProviderConfigEntity,
        model: String,
    ) = ProviderConfigSpec(
        original.id,
        original.displayName,
        ProviderProtocol.parse(original.protocol),
        original.endpoint,
        model,
        original.headersJson,
        original.secretAlias,
        original.capabilitySnapshot,
    )

    private suspend fun start(
        provider: String,
        model: String,
    ): String {
        val session = chat.createSession("Subscription boundary fixture", provider, model)
        sessions.add(session)
        chat.openSession(session)
        awaitSubscriptionBoundary { chat.screen.value.openSessionId == session }
        chat.send("hello from subscription boundary fixture")
        return session
    }

    private fun terminal(session: String): com.helix.core.storage.entity.TurnEntity {
        awaitSubscriptionBoundary {
            storage.turns
                .listBySession(session)
                .singleOrNull()
                ?.let { TurnState.valueOf(it.state).isTerminal } == true
        }
        awaitSubscriptionBoundary { !chat.screen.value.isSending }
        return storage.turns.listBySession(session).single()
    }

    private fun runningJob(
        session: String,
        client: CliModelJobClient,
    ): String {
        var jobId: String? = null
        awaitSubscriptionBoundary {
            val turn = storage.turns.listBySession(session).singleOrNull()
            val call = turn?.let { storage.modelCalls.listByTurn(it.id).singleOrNull() }
            val binding =
                call
                    ?.takeIf { candidate ->
                        storage.auditEvents.listByCorrelation(session).any { it.id == "cli-job-${candidate.id}" }
                    }?.let { SubscriptionJobBindingStore(storage).resolve(it.id) }
            jobId = (binding?.get("jobId") as? JsonPrimitive)?.content
            jobId?.let { (client.query(it) as? CliModelJobClient.StateOutcome.Ok)?.record?.state } ==
                CliModelJobState.RUNNING
        }
        return requireNotNull(jobId)
    }
}

private fun awaitSubscriptionBoundary(condition: () -> Boolean) {
    repeat(500) {
        if (condition()) return
        Thread.sleep(20)
    }
    error("subscription chat boundary timed out")
}
