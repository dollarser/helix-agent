package com.helix.app.chat

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.ProviderDraft
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnBudgets
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties

/** Requires the host fixture runner: the HTTP server must survive the killed application. */
@RunWith(AndroidJUnit4::class)
class ModelStreamProcessKillDeviceTest {
    @Test fun actualModelStreamRecoversWithoutReplay() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = args.getString("model.kill.port")?.toIntOrNull()
            assumeTrue("Requires scripts/run-model-stream-process-kill.py", port != null)
            val phase = requireNotNull(args.getString("model.kill.phase"))
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val marker = File(app.filesDir, "model-stream-kill.properties")
            if (phase == "prepare") {
                check(!marker.exists()) { "Recover the existing model kill fixture first" }
                val protocol =
                    ProviderProtocol.valueOf(args.getString("model.kill.protocol") ?: "OPENAI_CHAT_COMPLETIONS")
                prepare(app, requireNotNull(port), marker, protocol, args.getString("model.kill.boundary") ?: "body")
            } else {
                require(phase in setOf("recover", "recover-final", "abort"))
                val facts = Properties().apply { marker.inputStream().use { load(it) } }
                if (phase != "abort") recover(app, facts)
                if (phase != "recover") cleanupFixture(app, facts, marker)
            }
        }

    /** Explicit cleanup after a failed host precondition is not recovery acceptance. */
    private suspend fun cleanupFixture(
        app: HelixApplication,
        facts: Properties,
        marker: File,
    ) {
        val container = app.appContainer
        container.chatService.stop()
        await { !container.chatService.screen.value.isSending }
        container.chatService.closeSession()
        container.chatService.setMode(AgentMode.valueOf(facts.getProperty("mode")))
        container.chatService.setTurnBudgets(TurnBudgets.parse(facts.getProperty("budgets")))
        container.privacyDeletionService.deleteGoal(facts.getProperty("goal"))
        container.providerService.delete(facts.getProperty("provider"))
        container.storage.sessions.archive(facts.getProperty("session"), System.currentTimeMillis())
        check(marker.delete())
    }

    private suspend fun prepare(
        app: HelixApplication,
        port: Int,
        marker: File,
        protocol: ProviderProtocol,
        boundary: String,
    ) {
        val container = app.appContainer
        val chat = container.chatService
        val previous = chat.runControl.value
        val provider = createProvider(container, port, protocol)
        val session = chat.createSession("Model kill fixture", provider, "fixture-model-a")
        val goal =
            chat.createGoal(
                "Interrupted HTTP model",
                listOf("Verified output"),
                GoalBudgets(3, 4, 100000, 120000, 60000, 0),
            )
        val facts =
            Properties().apply {
                setProperty("goal", goal)
                setProperty("session", session)
                setProperty("provider", provider)
                setProperty("mode", previous.mode.name)
                setProperty("budgets", previous.budgets.toStorageString())
            }
        marker.outputStream().use { facts.store(it, "Fixture ownership and pre-kill facts") }
        chat.openSession(session)
        await { chat.screen.value.openSessionId == session }
        chat.setMode(AgentMode.GOAL)
        chat.setTurnBudgets(TurnBudgets(3, 4, 10000, 128, 10000))
        chat.continueGoal(goal, "GOAL_MODEL_KILL_HOLD: reply with a short result.")
        awaitModelBoundary(app, goal, boundary)
        container.storage.withTransaction {
            val run =
                container.storage.goalRuns
                    .listByGoal(goal)
                    .single()
            val stored = container.storage.goals.resolve(goal)
            val pending = container.storage.goalUsageReservations.pendingForRun(run.id)
            assertTrue(pending.any { it.kind == "MODEL" })
            facts.setProperty("tokens", (stored.totalTokens + pending.sumOf { it.reservedTokens }).toString())
            facts.setProperty("millis", (stored.runTimeMillis + pending.sumOf { it.reservedMillis }).toString())
        }
        marker.outputStream().use { facts.store(it, "Durable model reservation before SIGKILL") }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "MODEL_STREAM_KILL_READY pid=${android.os.Process.myPid()}\n")
            },
        )
        Thread.sleep(30000)
        error("Host did not kill the active model stream")
    }

    private fun awaitModelBoundary(
        app: HelixApplication,
        goal: String,
        boundary: String,
    ) {
        require(boundary in setOf("headers", "body"))
        val container = app.appContainer
        if (boundary == "body") {
            await {
                container.chatService.screen.value.activeTurn
                    ?.streamingText
                    ?.contains("partial") == true
            }
        } else {
            await {
                container.storage.goalRuns.listByGoal(goal).singleOrNull()?.let { run ->
                    container.storage.goalUsageReservations
                        .pendingForRun(run.id)
                        .any { it.kind == "MODEL" }
                } == true
            }
        }
    }

    private suspend fun createProvider(
        container: com.helix.app.AppContainer,
        port: Int,
        protocol: ProviderProtocol,
    ): String {
        val provider =
            container.providerService.create(
                ProviderDraft(
                    null,
                    "Model kill fixture",
                    protocol,
                    NormalizedEndpoint.parse("http://10.0.2.2:$port/v1"),
                    "fixture-model-a",
                    "{}",
                    false,
                    CleartextAuthorization("10.0.2.2", port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        assertTrue(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
        return provider
    }

    private fun recover(
        app: HelixApplication,
        facts: Properties,
    ) {
        val storage = app.appContainer.storage
        val id = facts.getProperty("goal")
        await { storage.goals.resolve(id).state == "PAUSED" }
        val goal = storage.goals.resolve(id)
        val run = storage.goalRuns.listByGoal(id).single()
        assertEquals("INTERRUPTED", run.outcome)
        assertTrue(run.endedAt != null)
        assertEquals(
            "INTERRUPTED",
            storage.turns
                .listBySession(facts.getProperty("session"))
                .single()
                .state,
        )
        val turn = storage.turns.listBySession(facts.getProperty("session")).single()
        assertEquals(
            "INTERRUPTED",
            storage.modelCalls
                .listByTurn(turn.id)
                .single()
                .state,
        )
        assertTrue(storage.toolCalls.listByTurn(turn.id).isEmpty())
        assertEquals(1, goal.modelCalls)
        assertEquals(facts.getProperty("tokens").toLong(), goal.totalTokens)
        assertTrue(goal.runTimeMillis >= facts.getProperty("millis").toLong())
        assertEquals(0L, goal.currentWakeMillis)
        assertTrue(storage.goalUsageReservations.pendingForRun(run.id).isEmpty())
        assertTrue(RecoveryCoordinatorApp(storage, SystemClock()).recover().closedRuns.isEmpty())
        Thread.sleep(2000)
        assertEquals(goal, storage.goals.resolve(id))
        assertEquals(run, storage.goalRuns.listByGoal(id).single())
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString(
                    "stream",
                    "MODEL_STREAM_RECOVERED tokens=${goal.totalTokens} " +
                        "millis=${goal.runTimeMillis} calls=${goal.modelCalls}\n",
                )
            },
        )
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (!predicate()) {
            assertTrue("Model stream kill boundary timed out", android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(25)
        }
    }
}
