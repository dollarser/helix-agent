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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/** Dedicated host fixture owns the remote request while this process is killed. */
class BrowserGoalProcessKillDeviceTest {
    private val hold = ActionResultHold()
    private val unsettled get() =
        InstrumentationRegistry.getArguments().getString("browser.goal.kill.boundary") ==
            "unsettled"
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private var tabId = ""
    private val marker get() = File(app.filesDir, "browser-goal-kill.properties")

    @Test fun completedBrowserClickIsNotReplayedAfterProcessDeath() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = args.getString("browser.goal.kill.port")?.toIntOrNull()
            assumeTrue("Requires the dedicated Browser host kill runner", port != null)
            when (val phase = args.getString("browser.goal.kill.phase")) {
                "prepare" -> {
                    prepare(requireNotNull(port))
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
        check(!marker.exists()) { "Recover the existing owned fixture first" }
        val previous = container.chatService.runControl.value
        val facts =
            Properties().apply {
                setProperty("mode", previous.mode.name)
                setProperty("budgets", previous.budgets.toStorageString())
                setProperty("profile", container.profileStore.profile.name)
            }
        save(facts)
        configure(port, facts)
        preparePage(port, facts)
        val chat = container.chatService
        val session = chat.createSession("Browser kill fixture", facts.getProperty("provider"), "fixture-model-a")
        facts.setProperty("session", session)
        val goal =
            chat.createGoal(
                "Interrupted browser",
                listOf("Verified remote result"),
                GoalBudgets(3, 4, 100000, 120000, 60000, 0),
            )
        facts.setProperty("goal", goal)
        save(facts)
        chat.openSession(session)
        await { chat.screen.value.openSessionId == session }
        chat.setMode(AgentMode.GOAL)
        chat.setTurnBudgets(TurnBudgets(3, 4, 10000, 512, 10000))
        facts.setProperty("unsettled", unsettled.toString())
        if (unsettled) hold.install(container, "browser.click")
        chat.continueGoal(
            goal,
            "BROWSER_GOAL_KILL tab=${facts.getProperty(
                "tab",
            )} token=${facts.getProperty("token")} Click the exact fixture link once.",
        )
        awaitRunning(facts)
        val run =
            container.storage.goalRuns
                .listByGoal(goal)
                .single()
        await {
            container.storage.goalUsageReservations
                .pendingForRun(run.id)
                .any { it.kind == if (unsettled) "TOOL" else "MODEL" }
        }
        val pending = container.storage.goalUsageReservations.pendingForRun(run.id)
        val stored = container.storage.goals.resolve(goal)
        facts.setProperty("tokens", (stored.totalTokens + pending.sumOf { it.reservedTokens }).toString())
        facts.setProperty("millis", (stored.runTimeMillis + pending.sumOf { it.reservedMillis }).toString())
        save(facts)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "BROWSER_GOAL_KILL_READY pid=${android.os.Process.myPid()}\n")
            },
        )
        Thread.sleep(30000)
        error("Host did not kill the executing browser request")
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
                    "Browser kill fixture",
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
        facts.setProperty("tool", "browser.click")
        save(facts)
    }

    private fun preparePage(
        port: Int,
        facts: Properties,
    ) {
        tabId = main { container.browser.newTab() }
        facts.setProperty("tab", tabId)
        save(facts)
        val before =
            container.browser.state.value.tabs
                .first { it.id == tabId }
                .navigationGeneration
        val html = "<title>Kill fixture</title><a href='http://10.0.2.2:$port/action'>Owned action</a>"
        main { container.browser.navigate(tabId, "data:text/html," + android.net.Uri.encode(html)) }
        await {
            container.browser.state.value.tabs.first { it.id == tabId }.let {
                !it.isLoading && it.error == null && it.navigationGeneration > before
            }
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var value: com.helix.feature.browser.snapshot.SnapshotResult? = null
        main {
            container.browser.snapshot(tabId) {
                value = it
                latch.countDown()
            }
        }
        check(latch.await(15, java.util.concurrent.TimeUnit.SECONDS))
        val snapshot = (value as com.helix.feature.browser.snapshot.SnapshotResult.Success).snapshot
        facts.setProperty("token", snapshot.nodes.single { it.role == "link" }.token)
        save(facts)
    }

    private fun <T> main(action: () -> T): T {
        val result = java.util.concurrent.FutureTask(java.util.concurrent.Callable { action() })
        android.os.Handler(android.os.Looper.getMainLooper()).post(result)
        return result.get(30, java.util.concurrent.TimeUnit.SECONDS)
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
                assertTrue(call.argsJson.contains(facts.getProperty("token")))
                if (call.state == "AWAITING_APPROVAL") {
                    val approval = requireNotNull(container.storage.approvals.byToolCall(call.callId))
                    if (approved.add(approval.id)) {
                        container.chatService.approveApproval(approval.id)
                    }
                }
                facts.setProperty("call", call.callId)
                facts.setProperty("turn", turn.id)
            }
            if (unsettled) {
                call?.state == "RUNNING" && hold.reached
            } else {
                call?.state == "COMPLETED" && container.storage.modelCalls
                    .listByTurn(turn.id)
                    .size == 2
            }
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
        val wasUnsettled = facts.getProperty("unsettled").toBoolean()
        assertEquals(if (wasUnsettled) "INTERRUPTED" else "COMPLETED", call.state)
        val recoveryAudit =
            storage.auditEvents
                .listByCorrelation(turn.sessionId)
                .single { it.type == "recovery.turn_interrupted" }
        val uncertain = if (wasUnsettled) "\"${call.callId}\"" else "null"
        assertTrue(recoveryAudit.redactedPayload.contains("\"uncertainToolCall\":$uncertain"))
        assertEquals("INTERRUPTED", run.outcome)
        assertTrue(run.endedAt != null)
        assertEquals(!wasUnsettled, storage.toolResults.byToolCall(call.callId) != null)
        assertEquals(if (wasUnsettled) 1 else 2, goal.modelCalls)
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
        facts.getProperty("goal")?.let { id ->
            await {
                container.storage.goalRuns
                    .listByGoal(id)
                    .all { it.endedAt != null }
            }
            if (container.storage.goals.find(id) != null) container.privacyDeletionService.deleteGoal(id)
        }
        facts.getProperty("session")?.let { id ->
            if (container.storage.sessions
                    .resolve(id)
                    .archivedAt == null
            ) {
                container.storage.sessions.archive(id, System.currentTimeMillis())
            }
        }
        facts.getProperty("provider")?.let { id ->
            if (container.storage.providerConfigs
                    .list()
                    .any { it.id == id }
            ) {
                container.providerService.delete(id)
            }
        }
        container.profileStore.switchTo(SafetyProfile.valueOf(facts.getProperty("profile")))
        facts.getProperty("tab")?.let { id ->
            main {
                if (container.browser.state.value.tabs
                        .any { it.id == id }
                ) {
                    container.browser.closeTab(id)
                }
            }
        }
        check(marker.delete())
    }

    private fun save(facts: Properties) = marker.outputStream().use { facts.store(it, "Owned Browser kill fixture") }

    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (!predicate()) {
            assertTrue("Browser kill fixture timed out", android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(25)
        }
    }
}
