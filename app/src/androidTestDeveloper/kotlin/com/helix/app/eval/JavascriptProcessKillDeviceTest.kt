package com.helix.app.eval

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnBudgets
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/** Dedicated host fixture owns the remote request while this process is killed. */
class JavascriptProcessKillDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val marker get() = File(app.filesDir, "js-process-kill.properties")

    private val awaitingApproval get() =
        InstrumentationRegistry.getArguments().getString("js.kill.boundary") ==
            "approval"

    @Test fun executingJavascriptCallIsNotReplayedAfterProcessDeath() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = args.getString("js.kill.port")?.toIntOrNull()
            assumeTrue("Requires the dedicated JavaScript host kill runner", port != null)
            when (val phase = args.getString("js.kill.phase")) {
                "prepare" -> {
                    prepare(requireNotNull(port))
                }

                "resolve-denial", "verify-denial" -> {
                    val facts = Properties().apply { marker.inputStream().use { load(it) } }
                    verifyDeniedRecovery(facts, phase == "resolve-denial")
                    if (phase == "verify-denial") cleanup(facts)
                }

                "recover", "recover-final", "abort" -> {
                    val facts = Properties().apply { marker.inputStream().use { load(it) } }
                    if (phase != "abort") recover(facts)
                    if (phase != "recover") cleanup(facts)
                }

                else -> {
                    error("Unknown host phase: $phase")
                }
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
        val session = chat.createSession("JavaScript kill fixture", facts.getProperty("provider"), "fixture-model-a")
        facts.setProperty("session", session)
        val goal =
            chat.createGoal(
                "Interrupted JavaScript",
                listOf("Verified remote result"),
                GoalBudgets(3, 4, 100000, 120000, 60000, 0),
            )
        facts.setProperty("goal", goal)
        save(facts)
        chat.openSession(session)
        await { chat.screen.value.openSessionId == session }
        chat.setMode(AgentMode.GOAL)
        chat.setTurnBudgets(TurnBudgets(3, 4, 10000, 512, 10000))
        chat.continueGoal(goal, "JS_KILL_TOOL=${facts.getProperty("tool")} Run the exact synthetic loop once.")
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
                putString("stream", "JS_KILL_READY pid=${android.os.Process.myPid()}\n")
            },
        )
        Thread.sleep(30000)
        error("Host did not kill the executing JavaScript request")
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
                    "JavaScript kill fixture",
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
        facts.setProperty("tool", "code.javascript.run")
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
                assertTrue(call.argsJson.contains("while (true)"))
                if (call.state == "AWAITING_APPROVAL") {
                    val approval = requireNotNull(container.storage.approvals.byToolCall(call.callId))
                    if (!awaitingApproval &&
                        approved.add(approval.id)
                    ) {
                        container.chatService.approveApproval(approval.id)
                    }
                }
                facts.setProperty("call", call.callId)
                facts.setProperty("turn", turn.id)
            }
            call?.state == if (awaitingApproval) "AWAITING_APPROVAL" else "RUNNING"
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
        assertEquals(if (awaitingApproval) "AWAITING_APPROVAL" else "INTERRUPTED", call.state)
        val recoveryAudit =
            storage.auditEvents
                .listByCorrelation(turn.sessionId)
                .single { it.type == "recovery.turn_interrupted" }
        val uncertain = if (awaitingApproval) "null" else "\"${call.callId}\""
        assertTrue(recoveryAudit.redactedPayload.contains("\"uncertainToolCall\":$uncertain"))
        assertEquals("INTERRUPTED", run.outcome)
        assertTrue(run.endedAt != null)
        val approval = requireNotNull(storage.approvals.byToolCall(call.callId))
        assertEquals(if (awaitingApproval) null else "APPROVED", approval.decision)
        assertEquals(!awaitingApproval, approval.consumedAt != null)
        if (awaitingApproval) {
            assertEquals(null, storage.toolResults.byToolCall(call.callId))
            container.chatService.openSession(turn.sessionId)
            await {
                container.chatService.screen.value.toolTimeline
                    .any { it.callId == call.callId }
            }
            val row =
                container.chatService.screen.value.toolTimeline
                    .single { it.callId == call.callId }
            assertEquals(app.getString(R.string.tool_state_interrupted), row.stateLabel)
            assertEquals(null, row.card)
        }
        assertEquals(1, goal.modelCalls)
        assertEquals(facts.getProperty("tokens").toLong(), goal.totalTokens)
        assertTrue(goal.runTimeMillis >= facts.getProperty("millis").toLong())
        assertTrue(storage.goalUsageReservations.pendingForRun(run.id).isEmpty())
        Thread.sleep(2000)
        assertEquals(goal, storage.goals.resolve(id))
        assertEquals(call, storage.toolCalls.listByTurn(turn.id).single())
    }

    private fun verifyDeniedRecovery(
        facts: Properties,
        decide: Boolean,
    ) {
        check(awaitingApproval)
        val storage = container.storage
        val callId = facts.getProperty("call")
        val approval = requireNotNull(storage.approvals.byToolCall(callId))
        if (decide) {
            recover(facts)
            container.chatService.denyApproval(approval.id)
            await { storage.approvals.byToolCall(callId)?.decision == "DENIED" }
        }
        val denied = requireNotNull(storage.approvals.byToolCall(callId))
        assertEquals("DENIED", denied.decision)
        assertEquals(null, denied.consumedAt)
        assertEquals(null, storage.toolResults.byToolCall(callId))
        val turn = storage.turns.listBySession(facts.getProperty("session")).single()
        assertEquals(facts.getProperty("turn"), turn.id)
        assertEquals("INTERRUPTED", turn.state)
        assertEquals(
            callId,
            storage.toolCalls
                .listByTurn(turn.id)
                .single()
                .callId,
        )
        val goal = storage.goals.resolve(facts.getProperty("goal"))
        assertEquals("PAUSED", goal.state)
        assertEquals(1, goal.modelCalls)
        assertEquals(facts.getProperty("tokens").toLong(), goal.totalTokens)
        Thread.sleep(2000)
        assertEquals(denied, storage.approvals.byToolCall(callId))
        assertEquals(goal, storage.goals.resolve(goal.id))
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
        facts.getProperty("provider")?.let { container.providerService.delete(it) }
        container.profileStore.switchTo(SafetyProfile.valueOf(facts.getProperty("profile")))
        check(marker.delete())
    }

    private fun save(facts: Properties) = marker.outputStream().use { facts.store(it, "Owned JavaScript kill fixture") }

    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (!predicate()) {
            assertTrue("JavaScript kill fixture timed out", android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(25)
        }
    }
}
