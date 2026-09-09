from pathlib import Path
p=Path('core/agent/src/test/kotlin/com/helix/core/agent/GoalModelCompletionTest.kt');p.write_text('''package com.helix.core.agent

import com.helix.core.model.GoalState
import org.junit.Assert.*
import org.junit.Test

class GoalModelCompletionTest {
    @Test fun modelReportCompletesWithoutBoundCriteria() {
        val running = runningGoal().copy(criteria = listOf(Criterion("a", "Clear explanation"), Criterion("b", "Useful UI")))
        val step = GoalReducer.reduce(running, GoalEvent.CompleteRequested)
        assertEquals(GoalState.COMPLETED, step.state.state)
        assertEquals(running.criteria, step.state.criteria)
        assertEquals(running.totalTokens, step.state.totalTokens)
        assertTrue(step.effects.contains(GoalEffect.GoalCompleted))
    }
    @Test fun onlyActiveGoalCanConsumeCompletion() {
        for (state in GoalState.entries.filter { it != GoalState.RUNNING }) {
            assertTrue(GoalReducer.reduce(runningGoal().copy(state = state), GoalEvent.CompleteRequested).ignored)
        }
    }
    @Test fun ordinaryRoundEndDoesNotImplySemanticCompletion() {
        assertEquals(GoalState.PAUSED, GoalReducer.reduce(runningGoal(), GoalEvent.RunFinished).state.state)
    }
    @Test fun malformedCriterionStillRejected() {
        assertThrows(IllegalArgumentException::class.java) { Criterion("", "Description") }
        assertThrows(IllegalArgumentException::class.java) { Criterion("ok", " ") }
    }
}
''')
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/GoalModelReportDeviceTest.kt');p.write_text('''package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.goal.goalModelReport
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.*
import com.helix.core.storage.HelixStorage
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class GoalModelReportDeviceTest {
    private val clock = object : Clock { override fun now(): Instant = Instant.ofEpochMilli(2000) }
    @Test fun modelCanCompleteAnOpenEndedUnboundGoal() = fixture { s, g, start ->
        report(s, "t", "complete")
        start.coordinator.beginModelStream()
        start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        assertEquals("COMPLETED", s.goals.resolve(g).state)
        assertEquals("MODEL_COMPLETED", s.goalRuns.resolve(start.runId).outcome)
        assertEquals("Checked the work", GoalSummaryQuery(s).forSession("s").single().status.modelSummary)
        assertTrue(s.auditEvents.listByCorrelation(s.goals.resolve(g).correlationId).any { it.type == "goal.model_report" })
    }
    @Test fun noReportDoesNotCompleteOrBlockForMissingBindings() = fixture { s, g, start ->
        start.coordinator.beginModelStream()
        start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        assertEquals("PAUSED", s.goals.resolve(g).state)
        assertTrue(GoalSummaryQuery(s).forSession("s").single().canContinue)
    }
    @Test fun unfinishedReportRemainsResumable() = fixture { s, g, start ->
        report(s, "t", "in_progress")
        start.coordinator.beginModelStream()
        start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        assertEquals("PAUSED", s.goals.resolve(g).state)
    }
    @Test fun modelBlockerRequiresExplicitRepair() = fixture { s, g, start ->
        report(s, "t", "blocked")
        start.coordinator.beginModelStream()
        start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        assertEquals("BLOCKED", s.goals.resolve(g).state)
        assertFalse(GoalSummaryQuery(s).forSession("s").single().canContinue)
        assertTrue(GoalBlockerResolution(s, clock, ::id).resolve(g, "s", true))
        assertEquals("PAUSED", s.goals.resolve(g).state)
        assertEquals(1, s.goalRuns.listByGoal(g).size)
    }
    @Test fun cancellationOverridesCompletedReport() = fixture { s, g, start ->
        report(s, "t", "complete")
        start.coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
        assertEquals("CANCELLED", s.goals.resolve(g).state)
    }
    @Test fun manualPauseOverridesCompletedReport() = fixture { s, g, start ->
        report(s, "t", "complete")
        s.turns.requestPause("t", 2001)
        start.coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
        assertEquals("PAUSED", s.goals.resolve(g).state)
    }
    @Test fun unknownEffectOverridesCompletedReport() = fixture { s, g, start ->
        s.toolCalls.append("unknown", "t", "unknown", "files.write", "1", "{}", "NEEDS_REVIEW")
        report(s, "t", "complete")
        start.coordinator.beginModelStream()
        start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        assertEquals("BLOCKED", s.goals.resolve(g).state)
    }
    @Test fun laterWorkInvalidatesEarlierReport() = fixture { s, g, start ->
        report(s, "t", "complete")
        s.toolCalls.append("later", "t", "later", "time.now", "1", "{}", "COMPLETED")
        assertNull(s.goalModelReport("t"))
        start.coordinator.beginModelStream()
        start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        assertEquals("PAUSED", s.goals.resolve(g).state)
    }
    @Test fun capacityStopDoesNotCompleteDespiteReport() = fixture { s, g, start ->
        report(s, "t", "complete")
        start.coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_WINDOW_LIMIT"))
        assertEquals("BLOCKED", s.goals.resolve(g).state)
    }
    private fun report(s: HelixStorage, turn: String, status: String) {
        val call = id()
        val args = buildJsonObject { put("status", status); put("summary", "Checked the work") }.toString()
        s.toolCalls.append(call, turn, call, "goal.report", "1", args, "COMPLETED")
        val result = s.toolResults.append(id(), call, "SUCCEEDED", "Report accepted", args)
        s.toolResults.markVerified(result)
    }
    private fun fixture(block: (HelixStorage, String, StartedGoalTurn) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "model-report-${id()}"
        val content = File(context.cacheDir, name)
        val s = HelixStorage.open(context, name, content)
        try {
            s.sessions.create("s", "Fixture", null, null, 1000)
            val coordinator = GoalRunCoordinator(s, clock, ::id)
            val goal = coordinator.create("Explain clearly", listOf("Helpful answer"), GoalBudgets(10, 10, 100000, 60000, 10000, 0))
            val start = requireNotNull(coordinator.start(GoalTurnStart(
                goal, GoalWakeReason.USER_OPEN, TurnStartSpec("s", "t", "m", "snapshot", "input"),
                TurnBudgets(5, 10, 800, 800, 5000),
            )))
            block(s, goal, start)
        } finally { s.close(); context.deleteDatabase(name); content.deleteRecursively() }
    }
    private fun id(): String = UUID.randomUUID().toString()
}
''')
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/GoalUsageReservationsDeviceTest.kt');s=p.read_text().replace('            assertEquals(0, paused.satisfiedCriteria)\n','            assertTrue(paused.criteria.isNotEmpty())\n');p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/BackgroundTaskStorageDeviceTest.kt');s=p.read_text();a=s.index('    @Test fun missingBindingBlocks');b=s.index('\n    @Test',a);s=s[:a]+'''    @Test fun missingBindingsNeverBlockOrdinaryProgress() =
        fixture { storage, coordinator, goal ->
            val first = requireNotNull(coordinator.start(request(goal, "first")))
            first.coordinator.beginModelStream()
            first.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("PAUSED", storage.goals.resolve(goal).state)
            RecoveryCoordinatorApp(storage, clock).recover()
            assertNotNull(coordinator.start(request(goal, "second")))
        }
'''+s[b:];p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/ChatCompactionFlowDeviceTest.kt');s=p.read_text().replace('assertEquals("BLOCKED", storage.goals.resolve(goalId).state)','assertEquals("PAUSED", storage.goals.resolve(goalId).state)');p.write_text(s)
