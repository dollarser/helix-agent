package com.helix.app.proot

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.provider.ScriptedTaskModelServer
import com.helix.app.ui.resetDeterministicUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class DetachedGoalJourneyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun terminalTurnKeepsItsGoalRunPendingUntilManualCollection() =
        runBlocking {
            compose.resetDeterministicUiState()
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            ScriptedTaskModelServer().use { server ->
                server.start()
                val f = DetachedGoalFixture(app, server)
                try {
                    f.prepare()
                    compose.waitUntil(10_000) { f.container.chatService.screen.value.openSessionId == f.session }
                    f.submit(collectInModel = false)
                    compose.waitUntil(30_000) {
                        f.storage.turns
                            .resolve(f.turn)
                            .state == "COMPLETED"
                    }
                    val run =
                        f.storage.goalRuns
                            .listByGoal(f.goal)
                            .single()
                    assertNull(run.endedAt)
                    assertEquals(
                        "RUNNING",
                        f.storage.goals
                            .resolve(f.goal)
                            .state,
                    )
                    f.originalResult()
                    val job = DetachedJobDashboard.read(f.storage).single { it.sessionId == f.session }
                    f.container.chatService.performBackgroundJobAction(job, BackgroundJobAction.COLLECT)
                    compose.waitUntil(30_000) {
                        val action = f.container.chatService.backgroundJobAction.value
                        action?.callId == job.callId && !action.busy
                    }
                    assertManualSettlement(f, run.id)
                } finally {
                    f.close()
                }
            }
        }

    private fun assertManualSettlement(
        f: DetachedGoalFixture,
        runId: String,
    ) {
        val action = f.container.chatService.backgroundJobAction.value
        assertEquals(BackgroundJobActionOutcome.SETTLED, action?.outcome)
        assertEquals(
            "PAUSED",
            f.storage.goals
                .resolve(f.goal)
                .state,
        )
        assertTrue(
            f.storage.goalRuns
                .resolve(runId)
                .endedAt != null,
        )
        assertTrue(
            f.storage.goalUsageReservations
                .pendingForRun(runId)
                .isEmpty(),
        )
        assertEquals("goal-result", f.output.readText())
        assertEquals(
            1,
            f.storage.turns
                .listBySession(f.session)
                .size,
        )
        assertEquals(
            1,
            f.storage.goalRuns
                .listByGoal(f.goal)
                .size,
        )
        assertEquals(
            2,
            f.storage.toolCalls
                .listByTurn(f.turn)
                .size,
        )
    }

    @Test fun goalCannotFinishUntilItsOriginalJobIsCollectedAndLeaseSettled() =
        runBlocking {
            compose.resetDeterministicUiState()
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            ScriptedTaskModelServer().use { server ->
                server.start()
                val f = DetachedGoalFixture(app, server)
                try {
                    f.prepare()
                    compose.waitUntil(10_000) { f.container.chatService.screen.value.openSessionId == f.session }
                    f.submit()
                    compose.waitUntil(60_000) {
                        f.storage.goalRuns
                            .listByGoal(f.goal)
                            .singleOrNull()
                            ?.endedAt != null
                    }
                    assertEquals(
                        "COMPLETED",
                        f.storage.goals
                            .resolve(f.goal)
                            .state,
                    )
                    val run =
                        f.storage.goalRuns
                            .listByGoal(f.goal)
                            .single()
                    assertEquals("MODEL_COMPLETED", run.outcome)
                    assertTrue(
                        f.storage.goalUsageReservations
                            .pendingForRun(run.id)
                            .isEmpty(),
                    )
                    val lease = requireNotNull(f.storage.goalUsageReservations.byId(requireNotNull(f.leaseId)))
                    assertEquals("SETTLED", lease.state)
                    assertTrue(requireNotNull(lease.chargedMillis) in 1..60_000)
                    assertEquals("goal-result", f.output.readText())
                    val calls = f.storage.toolCalls.listByTurn(f.turn)
                    assertEquals(4, calls.size)
                    val reports = calls.filter { it.name == "goal.report" }.map { it.state }
                    assertEquals(listOf("FAILED", "COMPLETED"), reports)
                    assertTrue(calls.filter { it.name != "goal.report" }.all { it.state == "COMPLETED" })
                    assertEquals(
                        1,
                        f.storage.turns
                            .listBySession(f.session)
                            .size,
                    )
                    assertNull(ExecutionOwnershipStore(File(app.filesDir, "execution-admission/owner")).read())
                } finally {
                    f.close()
                }
            }
        }
}
