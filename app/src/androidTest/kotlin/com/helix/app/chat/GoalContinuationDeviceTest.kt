package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.goal.GoalLifecycleService
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.GoalBudgets
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalContinuationDeviceTest {
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(2_000)
        }
    private val budgets = GoalBudgets(8, 12, 10_000, 60_000, 10_000, 0)
    private val limits = TurnBudgets(5, 10, 800, 800, 5_000)
    private val ids = { UUID.randomUUID().toString() }

    @Test fun userSuccessorPreservesGoalPredecessorUntilExplicitStop() =
        fixture { storage ->
            val coordinator = GoalRunCoordinator(storage, clock, ids)
            val goal = coordinator.create("Finish work", emptyList(), budgets)
            val first =
                requireNotNull(
                    coordinator.start(
                        GoalTurnStart(
                            goal,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("session", "goal-first", "goal-model", "snapshot", "work"),
                            limits,
                        ),
                    ),
                )
            val driver = GoalContinuationDriver(storage)
            val control =
                RunControlConfig(
                    mode = AgentMode.GOAL,
                    chatToolsEnabled = false,
                    budgets = limits,
                    goalBudgets = budgets,
                )
            driver.started("session", goal, "provider", control, "goal-first", null, "snapshot")
            first.coordinator.beginModelStream()
            first.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            driver.reserveUserHandoff("session", "goal-first")
            val user = turn(storage, "user-successor")
            driver.finishHandoff("session", "goal-first")
            user.beginModelStream()
            user.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            driver.reserveEligibleHandoff("session", "user-successor")
            assertNull(driver.next("session", "user-successor"))
            assertTrue(driver.hasActivation("session"))
            val resumed = requireNotNull(driver.resumeEligible("session"))
            assertEquals("goal-first", resumed.goalContinuation?.previousTurnId)
            assertEquals(goal, resumed.goalId?.value)
            assertEquals("user-successor", driver.handoffOwner("session"))
            val claim = requireNotNull(resumed.goalContinuation)
            assertTrue(driver.admits("session", goal, claim, "snapshot"))
            driver.finishHandoff("session", "goal-first")
            assertEquals("user-successor", driver.handoffOwner("session"))
            driver.finishHandoff("session", "user-successor")
            assertFalse(driver.hasHandoff)
            assertTrue(driver.hasActivation("session"))
            driver.reserveEligibleHandoff("session", "user-successor")
            driver.disarm("session")
            assertFalse(driver.admits("session", goal, claim, "snapshot"))
            assertNull(driver.resumeEligible("session"))
            assertFalse(driver.hasHandoff)
        }

    @Test fun settledRoundContinuesWithoutActivityAndStopInvalidatesQueuedClaim() =
        fixture { storage ->
            val coordinator = GoalRunCoordinator(storage, clock, ids)
            val goal = coordinator.create("Finish work", emptyList(), budgets)
            val first =
                requireNotNull(
                    coordinator.start(
                        GoalTurnStart(
                            goal,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("session", "first", "model-first", "snapshot", "work"),
                            limits,
                        ),
                    ),
                )
            val driver = GoalContinuationDriver(storage)
            val control =
                RunControlConfig(
                    mode = AgentMode.GOAL,
                    chatToolsEnabled = false,
                    budgets = limits,
                    goalBudgets = budgets,
                )
            driver.started("session", goal, "provider", control, "first", null, "snapshot")
            assertNull(driver.next("session", "first"))
            // Re-arm after testing that an unfinished predecessor cannot grant continuation.
            driver.started("session", goal, "provider", control, "first", null, "snapshot")
            first.coordinator.beginModelStream()
            first.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            val next = requireNotNull(driver.next("session", "first"))
            assertTrue(driver.hasHandoff)
            assertTrue(driver.admits("session", goal, requireNotNull(next.goalContinuation), "snapshot"))
            assertFalse(driver.admits("session", goal, next.goalContinuation!!, "changed endpoint"))
            assertFalse(driver.admits("other-session", goal, next.goalContinuation!!, "snapshot"))
            driver.disarmAll()
            assertFalse(driver.hasHandoff)
            assertFalse(driver.admits("session", goal, next.goalContinuation!!, "snapshot"))
            assertNull(GoalContinuationDriver(storage).next("session", "first"))
        }

    @Test fun creationIsOwnedDeferredAndRecoveryNeverArmsIt() =
        fixture { storage ->
            val turn = turn(storage, "create")
            val service = service(storage)
            val result =
                service.execute(
                    call(
                        "create_goal",
                        "create",
                        """{"objective":"Finish work","user_request":"please"}""",
                    ),
                )
            assertTrue(result is ToolExecutorResult.Completed)
            val goal = requireNotNull(service.current("session"))
            assertEquals("READY", goal.state)
            assertEquals("session", storage.goalControls.find(goal.id)?.sessionId)
            assertTrue(storage.goalRuns.listByGoal(goal.id).isEmpty())
            assertNotNull(storage.goalControls.find(goal.id)?.pendingJson)
            turn.beginModelStream()
            turn.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            service.settle("create")
            assertNull(storage.goalControls.find(goal.id)?.pendingJson)
            assertEquals(2L, storage.goalControls.find(goal.id)?.revision)
            assertNull(GoalContinuationDriver(storage).next("session", "create"))
        }

    @Test fun automaticAndForeignRequestsCannotEditAndStaleRevisionFails() =
        fixture { storage ->
            val service = service(storage)
            val goal = GoalRunCoordinator(storage, clock, ids).create("Original", emptyList(), budgets)
            service.bind(goal, "session")
            turn(storage, "edit")
            val args = """{"id":"$goal","expected_revision":0,"objective":"Changed","user_request":"please"}"""
            val automatic = service(storage, human = false)
            assertTrue(automatic.execute(call("update_goal", "edit", args)) is ToolExecutorResult.Failed)
            assertTrue(
                service.execute(call("update_goal", "edit", args).copy(sessionId = "foreign"))
                    is ToolExecutorResult.Failed,
            )
            assertTrue(service.execute(call("update_goal", "edit", args)) is ToolExecutorResult.Completed)
            assertTrue(service.execute(call("update_goal", "edit", args)) is ToolExecutorResult.Failed)
            assertEquals("Original", storage.goals.resolve(goal).objective)
            RecoveryCoordinatorApp(storage, clock).recover()
            assertNull(storage.goalControls.find(goal)?.pendingJson)
            assertEquals("Original", storage.goals.resolve(goal).objective)
        }

    @Test fun editsCommitTogetherOnlyAfterSuccessfulSettlement() =
        fixture { storage ->
            val service = service(storage)
            val goal = GoalRunCoordinator(storage, clock, ids).create("Original", emptyList(), budgets)
            service.bind(goal, "session")
            val edit = turn(storage, "edit")
            val args = """{"id":"$goal","expected_revision":0,"objective":"Changed",
            "budgets":{"max_model_calls":20},"user_request":"please"}"""
            assertTrue(service.execute(call("update_goal", "edit", args)) is ToolExecutorResult.Completed)
            edit.beginModelStream()
            edit.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            service.settle("edit")
            assertEquals("Changed", storage.goals.resolve(goal).objective)
            assertEquals(
                20,
                storage.goals
                    .resolve(goal)
                    .budgets.maxModelCalls,
            )
            val cancelled = turn(storage, "cancel")
            assertTrue(
                service.execute(
                    call(
                        "update_goal",
                        "cancel",
                        """{"id":"$goal","expected_revision":2,"objective":"Lost","user_request":"please"}""",
                    ),
                )
                    is ToolExecutorResult.Completed,
            )
            cancelled.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            service.settle("cancel")
            assertEquals("Changed", storage.goals.resolve(goal).objective)
            assertNull(storage.goalControls.find(goal)?.pendingJson)
        }

    @Test fun systemPauseIsDurableAndDoesNotContinue() =
        fixture { storage ->
            val coordinator = GoalRunCoordinator(storage, clock, ids)
            val goal = coordinator.create("Finish work", emptyList(), budgets)
            val started =
                requireNotNull(
                    coordinator.start(
                        GoalTurnStart(
                            goal,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("session", "timeout", "model-timeout", "snapshot", "work"),
                            limits,
                        ),
                    ),
                )
            val driver = GoalContinuationDriver(storage)
            driver.started(
                "session",
                goal,
                "provider",
                RunControlConfig(
                    AgentMode.GOAL,
                    false,
                    limits,
                    goalBudgets = budgets,
                ),
                "timeout",
                null,
                "snapshot",
            )
            assertTrue(storage.turns.requestPause("timeout", 2_000))
            started.coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, "FGS_TIMEOUT"))
            assertEquals("PAUSED", storage.goals.resolve(goal).state)
            assertEquals(
                "SYSTEM_PAUSED(FGS_TIMEOUT)",
                storage.goalRuns
                    .listByGoal(goal)
                    .single()
                    .outcome,
            )
            assertNull(driver.next("session", "timeout"))
        }

    @Test fun staleBudgetEditAndPendingDeletionAreRejected() =
        fixture { storage ->
            val coordinator = GoalRunCoordinator(storage, clock, ids)
            val goal = coordinator.create("Original", emptyList(), budgets)
            service(storage).bind(goal, "session")
            assertTrue(coordinator.updateBudgets(goal, budgets.copy(maxModelCalls = 10), 0))
            assertFalse(coordinator.updateBudgets(goal, budgets.copy(maxModelCalls = 20), 0))
            turn(storage, "edit")
            val result =
                service(storage).execute(
                    call(
                        "update_goal",
                        "edit",
                        """{"id":"$goal","expected_revision":1,"objective":"Changed","user_request":"please"}""",
                    ),
                )
            assertTrue(result is ToolExecutorResult.Completed)
            val row = GoalSummaryQuery(storage).forSession("session").single()
            assertFalse(row.canDelete)
            assertFalse(row.canEditBudgets)
            assertFalse(row.canEditObjective)
        }

    @Test fun currentGoalFollowsTheLastUsedGoalRatherThanControlInsertionOrder() =
        fixture { storage ->
            val coordinator = GoalRunCoordinator(storage, clock, ids)
            val first = coordinator.create("Current work", emptyList(), budgets)
            val other = coordinator.create("Other parked work", emptyList(), budgets)
            val service = service(storage)
            service.bind(first, "session")
            service.bind(other, "session")
            val started =
                requireNotNull(
                    coordinator.start(
                        GoalTurnStart(
                            first,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("session", "active", "model-active", "snapshot", "work"),
                            limits,
                        ),
                    ),
                )
            assertEquals(first, service.current("session")?.id)
            started.coordinator.beginModelStream()
            started.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals(first, service.current("session")?.id)
        }

    private fun service(
        storage: HelixStorage,
        human: Boolean = true,
    ) = GoalLifecycleService(storage, clock, ids, { _, _ -> if (human) budgets else null }, { _, _, _, _ -> })

    private fun turn(
        storage: HelixStorage,
        id: String,
    ) = TurnCoordinator.start(
        storage,
        clock,
        ids,
        TurnStartSpec("session", id, "model-$id", "snapshot", "please"),
    )

    private fun call(
        name: String,
        turn: String,
        args: String,
    ) = ExecutableToolCall(
        ids(),
        name,
        "1",
        Json.parseToJsonElement(args).jsonObject,
        ExecutionTargetType.LOCAL_ANDROID,
        Instant.MAX,
        NoCancellation,
        "session",
        turn,
    )

    private fun fixture(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "goal-continuation-${ids()}.db"
        val content = File(context.cacheDir, "goal-continuation-${ids()}")
        val storage = HelixStorage.open(context, name, content)
        try {
            storage.sessions.create("session", "Goal", null, null, 1_000)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }
}
