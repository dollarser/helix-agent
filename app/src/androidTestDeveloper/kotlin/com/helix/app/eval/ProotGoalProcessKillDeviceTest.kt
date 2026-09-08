package com.helix.app.eval

import android.os.Bundle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.app.proot.ProotJobBindingStore
import com.helix.app.proot.ProotToolModule
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnBudgets
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Properties

/** Dedicated host fixture owns the remote request while this process is killed. */
class ProotGoalProcessKillDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val successful get() =
        InstrumentationRegistry.getArguments().getString("proot.goal.kill.successful") == "true"
    private val marker get() = File(app.filesDir, "proot-goal-kill.properties")

    private val awaitingApproval = false
    private val scratchBefore = File(app.filesDir, "proot-jobs").listFiles().orEmpty().toSet()
    private val deliveryFailure get() =
        InstrumentationRegistry.getArguments().getString("proot.goal.kill.phase") == "delivery-failure"
    private val normalResult get() =
        InstrumentationRegistry.getArguments().getString("proot.goal.kill.phase") == "normal-result"

    @Test fun executingProotCallIsNotReplayedAfterProcessDeath() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = args.getString("proot.goal.kill.port")?.toIntOrNull()
            assumeTrue("Requires the dedicated PRoot host kill runner", port != null)
            when (val phase = args.getString("proot.goal.kill.phase")) {
                "prepare", "delivery-failure", "normal-result" -> {
                    prepare(requireNotNull(port))
                }

                "result-boundary" -> {
                    val facts = Properties().apply { marker.inputStream().use { load(it) } }
                    await {
                        container.storage.turns
                            .resolve(facts.getProperty("turn"))
                            .state == "INTERRUPTED"
                    }
                    pauseProotResultRecovery(app, container.storage, facts)
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
        saveProotFacts(marker, facts)
        configure(port, facts)
        val chat = container.chatService
        val title = "PRoot fixture ${java.util.UUID.randomUUID()}"
        val session = chat.createSession(title, facts.getProperty("provider"), "fixture-model-a")
        facts.setProperty("session", session)
        val goal =
            chat.createGoal(
                "Interrupted PRoot",
                listOf("Verified remote result"),
                GoalBudgets(3, 4, 100000, 120000, 60000, 0),
            )
        facts.setProperty("goal", goal)
        saveProotFacts(marker, facts)
        chat.openSession(session)
        await { chat.screen.value.openSessionId == session }
        chat.setMode(AgentMode.GOAL)
        chat.setTurnBudgets(TurnBudgets(3, 4, 10000, 512, 10000))
        chat.continueGoal(goal, "PROOT_GOAL_KILL_TOOL=${facts.getProperty("tool")} Run the exact synthetic loop once.")
        awaitRunning(facts)
        await {
            container.storage.auditEvents
                .listByCorrelation(session)
                .any { it.type == "proot.job_prepared" }
        }
        val binding = ProotJobBindingStore(container.storage).resolve(facts.getProperty("call"))
        facts.setProperty("binding", binding.toString())
        facts.setProperty("job", binding.getValue("jobId").jsonPrimitive.content)
        val run =
            container.storage.goalRuns
                .listByGoal(goal)
                .single()
        val pending = container.storage.goalUsageReservations.pendingForRun(run.id)
        val stored = container.storage.goals.resolve(goal)
        facts.setProperty("tokens", (stored.totalTokens + pending.sumOf { it.reservedTokens }).toString())
        facts.setProperty("millis", (stored.runTimeMillis + pending.sumOf { it.reservedMillis }).toString())
        saveProotFacts(marker, facts)
        if (deliveryFailure || normalResult) {
            verifyProotDeliveryFailure(app, facts, scratchBefore, normalResult) { exerciseRecoveryButtons(facts) }
            cleanup(facts)
        } else {
            announceProotReady(facts)
            Thread.sleep(30000)
            error("Host did not kill the executing PRoot request")
        }
    }

    private suspend fun configure(
        port: Int,
        facts: Properties,
    ) {
        container.profileStore.switchTo(SafetyProfile.ADVANCED)
        assertTrue(ProotToolModule.verifyNow() is ProotRuntimeAvailability.Verified)
        val provider =
            container.providerService.create(
                ProviderDraft(
                    null,
                    "PRoot kill fixture",
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
        saveProotFacts(marker, facts)
        assertTrue(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
        facts.setProperty("tool", "code.linux.run")
        saveProotFacts(marker, facts)
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
                assertTrue(call.argsJson.contains("PROOT_GOAL_STARTED"))
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
        reconcileOriginalJob(facts)
    }

    private fun reconcileOriginalJob(facts: Properties) {
        if (InstrumentationRegistry.getArguments().getString("proot.goal.kill.offline") == "true") {
            val chat = container.chatService
            chat.openSession(facts.getProperty("session"))
            await {
                chat.screen.value.toolTimeline
                    .any { it.callId == facts.getProperty("call") }
            }
            compose.setContent {
                val screen by chat.screen.collectAsState()
                MaterialTheme {
                    androidx.compose.foundation.layout.Column {
                        screen.toolTimeline.singleOrNull { it.callId == facts.getProperty("call") }?.let {
                            ProotFixtureRecoveryActions(it, chat)
                        }
                    }
                }
            }
            verifyRecoveredOutput(facts.getProperty("call"))
            return
        }
        val binding = ProotJobBindingStore(container.storage).resolve(facts.getProperty("call"))
        assertEquals(facts.getProperty("binding"), binding.toString())
        val client = ProotJobClient(ProotRuntimeSupervisor(app))
        val id = binding.getValue("jobId").jsonPrimitive.content
        val record = (client.query(id) as ProotJobClient.JobStateOutcome.Ok).record
        assertEquals(binding.getValue("executionId").jsonPrimitive.content, record.executionId)
        assertEquals(binding.getValue("inputManifestSha256").jsonPrimitive.content, record.inputManifestSha256)
        exerciseRecoveryButtons(facts)
        val terminal = (client.awaitTerminal(id, 100, 10000) as ProotJobClient.AwaitOutcome.Terminal).record
        val expected =
            if (successful) {
                com.helix.runtime.proot.ipc.ProotJobState.SUCCEEDED
            } else {
                com.helix.runtime.proot.ipc.ProotJobState.CANCELLED
            }
        assertEquals(expected, terminal.state)
        val reconciled = (client.reconcile(id) as ProotJobClient.JobStateOutcome.Ok).record
        assertEquals(terminal.terminalCommit, reconciled.terminalCommit)
    }

    private fun exerciseRecoveryButtons(facts: Properties) {
        val chat = container.chatService
        val callId = facts.getProperty("call")
        openProotConversation(compose, app, facts)
        clickProotAction(compose, "proot-query-$callId")
        await {
            chat.screen.value.toolTimeline
                .single { it.callId == callId }
                .prootRecoveryReport != null
        }
        val report =
            chat.screen.value.toolTimeline
                .single { it.callId == callId }
                .prootRecoveryReport
        assertTrue(report?.labelRes != R.string.proot_recovery_unknown)
        if (successful) verifyRecoveredOutput(callId)
        if (report?.canStop == true) {
            clickProotAction(compose, "proot-stop-$callId")
            await {
                chat.screen.value.toolTimeline
                    .single { it.callId == callId }
                    .prootRecoveryReport
                    ?.labelRes ==
                    R.string.proot_recovery_stop_requested
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString("stream", "PROOT_RECOVERY_UI_STOP_CLICKED\n")
                },
            )
        }
        clickProotAction(compose, "proot-query-$callId")
        await {
            !chat.screen.value.toolTimeline
                .single { it.callId == callId }
                .prootRecoveryBusy
        }
    }

    private fun verifyRecoveredOutput(callId: String) {
        val chat = container.chatService
        clickProotAction(compose, "proot-result-$callId")
        await {
            chat.screen.value.toolTimeline
                .single { it.callId == callId }
                .prootRecoveredOutput != null
        }
        val row =
            chat.screen.value.toolTimeline
                .single { it.callId == callId }
        assertTrue(!row.prootResultUnavailable)
        if (InstrumentationRegistry.getArguments().getString("proot.goal.kill.offline") != "true") {
            clickProotAction(compose, "proot-result-$callId")
            await {
                chat.screen.value.toolTimeline.single { it.callId == callId }.let {
                    !it.prootRecoveryBusy && it.prootRecoveredOutput?.acknowledged == null
                }
            }
            clickProotAction(compose, "proot-ack-$callId")
            await {
                chat.screen.value.toolTimeline
                    .single { it.callId == callId }
                    .prootRecoveredOutput
                    ?.acknowledged == true
            }
        }
        assertEquals("PROOT_RESULT_READY\n", requireNotNull(row.prootRecoveredOutput).stdout)
        revealProotAction(compose, "proot-result-text-$callId")
        compose.onNodeWithTag("proot-result-text-$callId").assertTextContains("PROOT_RESULT_READY", substring = true)
        assertEquals(
            if (normalResult) {
                "COMPLETED"
            } else if (deliveryFailure) {
                "FAILED"
            } else {
                "INTERRUPTED"
            },
            container.storage.turns
                .resolve(row.turnId)
                .state,
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "PROOT_RESULT_UI_VERIFIED\n") },
        )
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

    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (!predicate()) {
            assertTrue("PRoot kill fixture timed out", android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(25)
        }
    }
}

private fun saveProotFacts(
    marker: File,
    facts: Properties,
) = marker.outputStream().use { facts.store(it, "Owned PRoot kill fixture") }
