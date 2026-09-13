from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/GoalComposerStartDeviceTest.kt')
p.write_text('''package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.runcontrol.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalComposerStartDeviceTest {
    private val clock = object : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000)
    }
    private val config = RunControlConfig(AgentMode.GOAL, false, TurnBudgetBounds.DEFAULT)

    @Test fun creationKeepsFullPromptAndResumesWithoutResettingBudget() = withStorage { storage ->
        val helper = GoalComposerStart(storage, clock) { UUID.randomUUID().toString() }
        val prompt = "Task instructions. ".repeat(100)
        assertNotNull(helper.start(prompt, spec("session", "first", prompt), config))
        val goal = storage.goals.resolve(storage.goals.list().single().id)
        assertEquals(config.goalBudgets, goal.budgets)
        assertEquals(prompt, storage.messages.readContent(storage.messages.listBySession("session").first { it.role == "USER" }))
        park(storage, goal.id, "PAUSED")
        assertNotNull(helper.start("Continue", spec("session", "second", "Continue"),
            config.copy(goalBudgets = config.goalBudgets.copy(maxTotalTokens = 9_000_000))))
        val resumed = storage.goals.resolve(goal.id)
        assertEquals(1, storage.goals.list().size)
        assertEquals(goal.budgets, resumed.budgets)
        assertEquals(7L, resumed.totalTokens)
        assertEquals(2, resumed.runCount)
    }

    @Test fun blockedGoalDoesNotCreateAReplacementAndOtherSessionIsIndependent() = withStorage { storage ->
        val helper = GoalComposerStart(storage, clock) { UUID.randomUUID().toString() }
        helper.start("Task", spec("session", "first", "Task"), config)
        val id = storage.goals.list().single().id
        park(storage, id, "BLOCKED")
        assertNull(helper.start("Again", spec("session", "second", "Again"), config))
        assertEquals(1, storage.goals.list().size)
        assertEquals(1, storage.turns.listBySession("session").size)
        storage.sessions.create("other", "Other", null, null, 1_000)
        assertNotNull(helper.start("New task", spec("other", "other-turn", "New task"), config))
        assertEquals(2, storage.goals.list().size)
    }

    @Test fun failedFirstTurnRollsBackGoalCreation() = withStorage { storage ->
        val helper = GoalComposerStart(storage, clock) { UUID.randomUUID().toString() }
        assertThrows(IllegalArgumentException::class.java) {
            helper.start("Task", spec("absent", "turn", "Task"), config)
        }
        assertEquals(0, storage.goals.list().size)
    }

    private fun park(storage: HelixStorage, goalId: String, state: String) {
        val run = storage.goalRuns.listByGoal(goalId).single()
        storage.goalRuns.finish(run, "RUN_FINISHED", 2_000, 0, 0, 0, 7)
        val goal = storage.goals.resolve(goalId)
        storage.goals.updateGoal(goal.copy(state = state, totalTokens = 7))
    }

    private fun spec(session: String, turn: String, prompt: String) =
        TurnStartSpec(session, turn, "model-$turn", "snapshot", prompt)

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "goal-composer-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, "goal-composer-${UUID.randomUUID()}")
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
''')
p=Path('app/src/main/kotlin/com/helix/app/ui/GoalDialog.kt');s=p.read_text().replace('if (initial == null) R.string.goal_create else R.string.goal_edit_budgets','if (budgetsOnly) R.string.goal_edit_budgets else if (initial == null) R.string.goal_create else R.string.goal_edit_budgets');p.write_text(s)
