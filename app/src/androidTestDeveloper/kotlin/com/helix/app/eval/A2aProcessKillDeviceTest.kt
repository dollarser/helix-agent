package com.helix.app.eval

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnBudgets
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/** Dedicated host fixture owns the remote request while this process is killed. */
class A2aProcessKillDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val marker get() = File(app.filesDir, "a2a-process-kill.properties")

    private val unknownTaskId get() = InstrumentationRegistry.getArguments().getString("a2a.kill.boundary") == "send"

    @Test fun executingA2aCallIsNotReplayedAfterProcessDeath() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = args.getString("a2a.kill.port")?.toIntOrNull()
            assumeTrue("Requires the dedicated A2A host kill runner", port != null)
            when (val phase = args.getString("a2a.kill.phase")) {
                "prepare" -> {
                    prepare(requireNotNull(port))
                }

                "recover", "recover-final", "abort" -> {
                    val facts = Properties().apply { marker.inputStream().use { load(it) } }
                    if (phase != "abort") recover(facts)
                    if (phase == "recover-final") verifyExplicitReconciliation(facts)
                    if (phase != "recover") cleanup(facts)
                }

                else -> {
                    error("Unknown host phase: $phase")
                }
            }
        }

    private fun verifyExplicitReconciliation(facts: Properties) {
        if (unknownTaskId) {
            org.junit.Assert.assertThrows(com.helix.extensions.a2a.A2aNeedsReviewException::class.java) {
                container.a2aService.reconcileTask(facts.getProperty("call"), facts.getProperty("session"))
            }
        } else {
            container.a2aService.reconcileTask(facts.getProperty("call"), facts.getProperty("session"))
            val task = requireNotNull(container.storage.a2aTasks.resolve(facts.getProperty("call")))
            assertEquals("task-kill", task.taskId)
            assertEquals("COMPLETED", task.state)
        }
    }

    private suspend fun prepare(port: Int) {
        check(!marker.exists()) { "Clean up or recover the existing owned fixture first" }
        val previous = container.chatService.runControl.value
        val facts =
            Properties().apply {
                setProperty("mode", previous.mode.name)
                setProperty("budgets", previous.budgets.toStorageString())
                setProperty("profile", container.profileStore.profile.name)
            }
        save(facts)
        configure(port, facts)
        val chat = container.chatService
        val session = chat.createSession("A2A kill fixture", facts.getProperty("provider"), "fixture-model-a")
        facts.setProperty("session", session)
        val goal =
            chat.createGoal(
                "Interrupted A2A",
                listOf("Verified remote result"),
                GoalBudgets(3, 4, 100000, 120000, 60000, 0),
            )
        facts.setProperty("goal", goal)
        save(facts)
        chat.openSession(session)
        await { chat.screen.value.openSessionId == session }
        chat.setMode(AgentMode.GOAL)
        chat.setTurnBudgets(TurnBudgets(3, 4, 10000, 512, 10000))
        chat.continueGoal(
            goal,
            "A2A_KILL_TOOL=${facts.getProperty("tool")} Invoke this tool once with task=kill and stream=false.",
        )
        awaitRunning(facts)
        val run =
            container.storage.goalRuns
                .listByGoal(goal)
                .single()
        val pending = container.storage.goalUsageReservations.pendingForRun(run.id)
        val stored = container.storage.goals.resolve(goal)
        facts.setProperty("tokens", (stored.totalTokens + pending.sumOf { it.reservedTokens }).toString())
        facts.setProperty("millis", (stored.runTimeMillis + pending.sumOf { it.reservedMillis }).toString())
        save(facts)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "A2A_KILL_READY pid=${android.os.Process.myPid()}\n")
            },
        )
        Thread.sleep(30000)
        error("Host did not kill the executing A2A request")
    }

    private suspend fun configure(
        port: Int,
        facts: Properties,
    ) {
        container.profileStore.switchTo(SafetyProfile.ADVANCED)
        val provider =
            container.providerService.create(
                ProviderDraft(
                    null,
                    "A2A kill fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
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
        facts.setProperty("provider", provider)
        save(facts)
        assertTrue(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
        val server = "a2a-kill-${System.nanoTime()}"
        facts.setProperty("server", server)
        val origin = "http://127.0.0.1:$port"
        if (origin !in container.lanScopeStore.origins.value) {
            facts.setProperty("addedOrigin", origin)
            save(facts)
            container.lanScopeStore.add(origin)
        }
        save(facts)
        container.a2aService.registerDisabled(server, "$origin/kill/card", null)
        container.a2aService.enable(container.a2aService.testConnection(server), setOf("fixture_task"))
        facts.setProperty(
            "tool",
            container.toolPipeline.registry
                .all()
                .single {
                    (it.origin as? ToolOrigin.A2aOrigin)?.agentId == server
                }.name.value,
        )
        save(facts)
    }

    private fun awaitRunning(facts: Properties) {
        val approved = mutableSetOf<String>()
        await {
            val turn =
                container.storage.turns
                    .listBySession(facts.getProperty("session"))
                    .singleOrNull()
            val call =
                turn?.let {
                    container.storage.toolCalls
                        .listByTurn(it.id)
                        .singleOrNull()
                }
            if (call != null) {
                assertEquals(facts.getProperty("tool"), call.name)
                assertEquals("{\"stream\":false,\"task\":\"kill\"}", call.argsJson)
                if (call.state == "AWAITING_APPROVAL") {
                    val approval = requireNotNull(container.storage.approvals.byToolCall(call.callId))
                    if (approved.add(approval.id)) container.chatService.approveApproval(approval.id)
                }
                facts.setProperty("call", call.callId)
                facts.setProperty("turn", turn.id)
            }
            call?.state == "RUNNING" &&
                container.storage.a2aTasks
                    .resolve(call.callId)
                    ?.let { if (unknownTaskId) it.taskId == null else it.taskId == "task-kill" } == true
        }
    }

    private fun recover(facts: Properties) {
        val storage = container.storage
        val id = facts.getProperty("goal")
        await { storage.goals.resolve(id).state == "PAUSED" }
        val goal = storage.goals.resolve(id)
        val run = storage.goalRuns.listByGoal(id).single()
        val turn = storage.turns.listBySession(facts.getProperty("session")).single()
        val call = storage.toolCalls.listByTurn(turn.id).single()
        assertEquals(facts.getProperty("turn"), turn.id)
        assertEquals(facts.getProperty("call"), call.callId)
        assertEquals("INTERRUPTED", turn.state)
        assertEquals("INTERRUPTED", call.state)
        val recoveryAudit =
            storage.auditEvents
                .listByCorrelation(turn.sessionId)
                .single { it.type == "recovery.turn_interrupted" }
        assertTrue(recoveryAudit.redactedPayload.contains("\"uncertainToolCall\":\"${call.callId}\""))
        assertEquals("INTERRUPTED", run.outcome)
        assertTrue(run.endedAt != null)
        assertEquals(if (unknownTaskId) null else "task-kill", storage.a2aTasks.resolve(call.callId)?.taskId)
        assertTrue(storage.a2aTasks.resolve(call.callId)?.state != "COMPLETED")
        val approval = requireNotNull(storage.approvals.byToolCall(call.callId))
        assertEquals("APPROVED", approval.decision)
        assertTrue(approval.consumedAt != null)
        assertEquals(1, goal.modelCalls)
        assertEquals(facts.getProperty("tokens").toLong(), goal.totalTokens)
        assertTrue(goal.runTimeMillis >= facts.getProperty("millis").toLong())
        assertTrue(storage.goalUsageReservations.pendingForRun(run.id).isEmpty())
        Thread.sleep(2000)
        assertEquals(goal, storage.goals.resolve(id))
        assertEquals(call, storage.toolCalls.listByTurn(turn.id).single())
    }

    private suspend fun cleanup(facts: Properties) {
        val chat = container.chatService
        chat.stop()
        await { !chat.screen.value.isSending }
        chat.closeSession()
        chat.setMode(AgentMode.valueOf(facts.getProperty("mode")))
        chat.setTurnBudgets(TurnBudgets.parse(facts.getProperty("budgets")))
        facts.getProperty("goal")?.let { container.privacyDeletionService.deleteGoal(it) }
        facts.getProperty("session")?.let { container.storage.sessions.archive(it, System.currentTimeMillis()) }
        facts.getProperty("server")?.let { ownedId ->
            if (container.storage.a2aAgents
                    .list()
                    .any { it.id == ownedId }
            ) {
                container.a2aService.delete(ownedId)
            }
        }
        facts.getProperty("provider")?.let { container.providerService.delete(it) }
        facts.getProperty("addedOrigin")?.let { container.lanScopeStore.remove(it) }
        container.profileStore.switchTo(SafetyProfile.valueOf(facts.getProperty("profile")))
        check(marker.delete())
    }

    private fun save(facts: Properties) = marker.outputStream().use { facts.store(it, "Owned A2A kill fixture") }

    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (!predicate()) {
            assertTrue("A2A kill fixture timed out", android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(25)
        }
    }
}
