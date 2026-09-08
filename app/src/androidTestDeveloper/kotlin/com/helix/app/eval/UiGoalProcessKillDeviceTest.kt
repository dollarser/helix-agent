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
class UiGoalProcessKillDeviceTest {
    private val hold = ActionResultHold()
    private val unsettled get() =
        InstrumentationRegistry.getArguments().getString("ui.goal.kill.boundary") ==
            "unsettled"
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val center get() =
        com.helix.tools.automation
            .AutomationPermissionCenter(app)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() =
        instrumentation.getUiAutomation(
            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
    private val marker get() = File(app.filesDir, "ui-goal-kill.properties")

    @Test fun completedUiClickIsNotReplayedAfterProcessDeath() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = args.getString("ui.goal.kill.port")?.toIntOrNull()
            assumeTrue("Requires the dedicated UI host kill runner", port != null)
            when (val phase = args.getString("ui.goal.kill.phase")) {
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
        prepareUi(facts)
        val chat = container.chatService
        val session = chat.createSession("UI kill fixture", facts.getProperty("provider"), "fixture-model-a")
        facts.setProperty("session", session)
        val goal =
            chat.createGoal(
                "Interrupted UI action",
                listOf("Verified remote result"),
                GoalBudgets(4, 4, 100000, 120000, 60000, 0),
            )
        facts.setProperty("goal", goal)
        save(facts)
        chat.openSession(session)
        await { chat.screen.value.openSessionId == session }
        chat.setMode(AgentMode.GOAL)
        chat.setTurnBudgets(TurnBudgets(3, 4, 10000, 512, 10000))
        facts.setProperty("unsettled", unsettled.toString())
        if (unsettled) hold.install(container, "ui.click")
        chat.continueGoal(goal, "UI_GOAL_KILL Take a fresh ui.snapshot and click the Fixture click button once.")
        awaitRunning(facts)
        await { fixtureClicked(automation) }
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
                putString("stream", "UI_GOAL_KILL_READY pid=${android.os.Process.myPid()}\n")
            },
        )
        Thread.sleep(30000)
        error("Host did not kill the executing UI request")
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
                    "UI kill fixture",
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
        facts.setProperty("tool", "ui.click")
        save(facts)
    }

    private fun observeEvents() {
        val count =
            java.util.concurrent.atomic
                .AtomicInteger()
        automation.setOnAccessibilityEventListener { event ->
            if (count.incrementAndGet() <= 64) {
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString(
                            "stream",
                            "UI_EVENT time=${event.eventTime} type=${event.eventType} " +
                                "window=${event.windowId} package=${event.packageName}\n",
                        )
                    },
                )
            }
        }
    }

    private fun prepareUi(facts: Properties) {
        observeEvents()
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "UI_SERVICE_READY\n") })
        await { center.serviceState() == com.helix.tools.automation.AutomationServiceState.CONNECTED }
        facts.setProperty("allowlist", center.allowlistedPackages().joinToString(":"))
        save(facts)
        center.stopSession()
        val fixturePackage = instrumentation.context.packageName
        center.replaceAllowlist(setOf(fixturePackage))
        app.startActivity(
            android.content
                .Intent()
                .setClassName(fixturePackage, AutomationEvaluationActivity::class.java.name)
                .putExtra("recordClicks", true)
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
        )
        await {
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("FIXTURE_UNCHANGED")?.isNotEmpty() ==
                true
        }
        val started = center.startSession(setOf(fixturePackage))
        check(started.status == com.helix.tools.automation.AutomationSessionStartStatus.STARTED)
        automation.waitForIdle(300, 5000)
        await {
            center
                .snapshot()
                .snapshot
                ?.nodes
                ?.any { it.text == "Fixture click" } == true
        }
        facts.setProperty(
            "token",
            center
                .snapshot()
                .snapshot!!
                .nodes
                .single { it.text == "Fixture click" }
                .token,
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
                        .singleOrNull { it.name == "ui.click" }
                }
            if (call != null) {
                assertEquals(facts.getProperty("tool"), call.name)
                assertTrue(call.argsJson.contains("token"))
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
                    .size == 3
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
        val call = storage.toolCalls.listByTurn(turn.id).single { it.name == "ui.click" }
        assertEquals(2, storage.toolCalls.listByTurn(turn.id).size)
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
        assertEquals(if (wasUnsettled) 2 else 3, goal.modelCalls)
        assertEquals(facts.getProperty("tokens").toLong(), goal.totalTokens)
        assertTrue(goal.runTimeMillis >= facts.getProperty("millis").toLong())
        assertTrue(storage.goalUsageReservations.pendingForRun(run.id).isEmpty())
        Thread.sleep(2000)
        assertEquals(goal, storage.goals.resolve(id))
        assertEquals(call, storage.toolCalls.listByTurn(turn.id).single { it.name == "ui.click" })
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
        center.stopSession()
        facts
            .getProperty(
                "allowlist",
            )?.let { center.replaceAllowlist(it.split(':').filter(String::isNotBlank).toSet()) }
        check(marker.delete())
    }

    private fun save(facts: Properties) = marker.outputStream().use { facts.store(it, "Owned UI kill fixture") }

    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (!predicate()) {
            assertTrue("UI kill fixture timed out", android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(25)
        }
    }
}

private fun fixtureClicked(automation: android.app.UiAutomation): Boolean =
    automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("FIXTURE_CLICKED")?.isNotEmpty() == true
