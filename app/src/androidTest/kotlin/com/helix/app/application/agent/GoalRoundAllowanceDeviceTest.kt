package com.helix.app.application.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.agent.TurnStartSpec
import com.helix.app.application.agent.GoalBlockerResolution
import com.helix.app.test.FixtureGoalRunCoordinator
import com.helix.app.test.settleFixtureTerminal
import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.agent.GoalWakeReason
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
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
class GoalRoundAllowanceDeviceTest {
    @Test fun default256CountsOnlyAdmittedAutomaticPromptsAndSurvivesReopen() =
        Fixture().use { f ->
            repeat(3) { f.finish(f.start(GoalWakeReason.USER_OPEN)) }
            assertEquals(0, GoalRoundAllowance(f.storage).used(f.goal))
            repeat(256) { index ->
                val turn = f.start(GoalWakeReason.FOREGROUND_CONTINUATION)
                assertEquals(index + 1, GoalRoundAllowance(f.storage).used(f.goal))
                assertFalse(GoalRoundAllowance(f.storage).blockIfExhausted(f.goal))
                f.finish(turn)
            }
            f.reopen()
            assertEquals(256, GoalRoundAllowance(f.storage).used(f.goal))
            assertTrue(GoalRoundAllowance(f.storage).blockIfExhausted(f.goal))
            assertEquals(
                "BLOCKED(round-limit)",
                f.storage.goals
                    .resolve(f.goal)
                    .finishReason,
            )
            assertNull(f.coordinator().start(f.request(GoalWakeReason.FOREGROUND_CONTINUATION)))
            assertFalse(GoalBlockerResolution(f.storage, f.clock, f.ids).resolve(f.goal, "session", true))
            assertTrue(f.coordinator().updateBudgets(f.goal, GoalBudgetDefaults.VALUE.copy(maxGoalRounds = 257)))
            assertTrue(GoalBlockerResolution(f.storage, f.clock, f.ids).resolve(f.goal, "session", true))
            assertNotNull(f.coordinator().start(f.request(GoalWakeReason.FOREGROUND_CONTINUATION)))
            assertEquals(257, GoalRoundAllowance(f.storage).used(f.goal))
        }

    @Test fun cancellationDoesNotRefundRoundAndHumanInputDoesNotSpendIt() =
        Fixture().use { f ->
            val turn = f.start(GoalWakeReason.FOREGROUND_CONTINUATION)
            val turnId = turn.coordinator.id
            f.storage.turns.requestPause(turnId, 2000)
            turn.coordinator.settleFixtureTerminal(ModelStreamTerminal(TurnState.CANCELLED, "USER_STOP"))
            assertEquals(1, GoalRoundAllowance(f.storage).used(f.goal))
            f.reopen()
            f.finish(f.start(GoalWakeReason.USER_OPEN))
            assertEquals(1, GoalRoundAllowance(f.storage).used(f.goal))
        }

    @Test fun continuationQueriesNeverPersistRoundExhaustionAndTerminalPreparationOwnsIt() =
        Fixture(maxRounds = 1).use { f ->
            val turn = f.start(GoalWakeReason.FOREGROUND_CONTINUATION)
            val driver = GoalContinuationDriver(f.storage)
            driver.started(
                "session",
                f.goal,
                "synthetic-provider",
                com.helix.core.agent.RunControlConfig(
                    com.helix.core.model.AgentMode.GOAL,
                    false,
                    TurnBudgetBounds.DEFAULT,
                ),
                turn.coordinator.id,
                null,
                "snapshot",
            )
            f.finish(turn)
            val before = f.storage.goals.resolve(f.goal)
            assertEquals("PAUSED", before.state)
            assertNull(driver.next("session", turn.coordinator.id))
            assertEquals(before, f.storage.goals.resolve(f.goal))
            val store =
                com.helix.app.infrastructure.agent.RoomTurnLifecycleStore(
                    f.storage,
                    f.clock,
                    f.ids,
                    { "Synthetic recovery unavailable" },
                )
            store.prepareTerminalRelease(turn.coordinator.id, "session", ModelStreamTerminal(TurnState.COMPLETED, null))
            val blocked = f.storage.goals.resolve(f.goal)
            assertEquals("BLOCKED", blocked.state)
            assertEquals("BLOCKED(round-limit)", blocked.finishReason)
            store.prepareTerminalRelease(turn.coordinator.id, "session", ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals(blocked, f.storage.goals.resolve(f.goal))
            assertFalse(driver.hasActivation("session"))
        }

    @Test fun pendingGoalEditIsReconciledEvenWhenTheCompletedHumanTurnHasNoGoalBinding() =
        Fixture(maxRounds = 1).use { f ->
            f.finish(f.start(GoalWakeReason.FOREGROUND_CONTINUATION))
            val edit =
                com.helix.app.test.startFixtureTurn(
                    f.storage,
                    f.clock,
                    f.ids,
                    TurnStartSpec("session", "edit", "edit-model", "snapshot", "Synthetic edit"),
                )
            if (f.storage.goalControls.find(f.goal) == null) {
                f.storage.goalControls.insert(
                    com.helix.core.storage.entity
                        .GoalControlEntity(f.goal, "session", 0, null, null),
                )
            }
            val revision = requireNotNull(f.storage.goalControls.find(f.goal)).revision
            assertEquals(
                1,
                f.storage.goalControls.stage(
                    f.goal,
                    revision,
                    "edit",
                    """{"objective":"Edited synthetic target"}""",
                ),
            )
            kotlinx.coroutines.runBlocking { edit.beginModelStream() }
            edit.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertNull(f.storage.goalTurnBindings.byTurn("edit"))
            val store =
                com.helix.app.infrastructure.agent.RoomTurnLifecycleStore(
                    f.storage,
                    f.clock,
                    f.ids,
                    { "Synthetic recovery unavailable" },
                )
            store.prepareTerminalRelease("edit", "session", ModelStreamTerminal(TurnState.COMPLETED, null))
            val goal = f.storage.goals.resolve(f.goal)
            assertEquals("Edited synthetic target", goal.objective)
            assertEquals("BLOCKED", goal.state)
            assertEquals("BLOCKED(round-limit)", goal.finishReason)
        }

    private class Fixture(
        maxRounds: Int = 256,
    ) : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ids = { UUID.randomUUID().toString() }
        val name = "goal-rounds-${ids()}.db"
        val content = File(context.cacheDir, "goal-rounds-${ids()}")
        var storage = HelixStorage.open(context, name, content)
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(2000)
            }
        val goal: String

        init {
            storage.sessions.create("session", "Goal rounds", null, null, 1000)
            goal =
                coordinator().create(
                    "Finish work",
                    emptyList(),
                    GoalBudgetDefaults.VALUE.copy(maxGoalRounds = maxRounds),
                )
        }

        fun coordinator() = FixtureGoalRunCoordinator(storage, clock, ids)

        fun request(reason: GoalWakeReason) =
            GoalTurnStart(
                goal,
                reason,
                TurnStartSpec("session", ids(), ids(), "snapshot", "work"),
                TurnBudgetBounds.DEFAULT,
            )

        fun start(reason: GoalWakeReason) = requireNotNull(coordinator().start(request(reason)))

        fun finish(turn: StartedGoalTurn) {
            kotlinx.coroutines.runBlocking { turn.coordinator.beginModelStream() }
            turn.coordinator.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
        }

        fun reopen() {
            storage.close()
            storage = HelixStorage.open(context, name, content)
        }

        override fun close() {
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }
}
