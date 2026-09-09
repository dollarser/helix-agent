package com.helix.app.goal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.agent.Criterion
import com.helix.core.agent.Goal
import com.helix.core.model.CorrelationId
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalId
import com.helix.core.model.GoalState
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.ContentRef
import com.helix.tools.framework.BuiltInToolSource
import com.helix.tools.framework.TimeNowTool
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalToolEvidenceReaderDeviceTest {
    @Test
    fun readsVerifiedLocalResultWithStableGoalSourceAndHash() =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage)
            val reader = goalEvidenceReader(storage)
            val first = reader.read("goal", "call")
            assertEquals(first, reader.read("goal", "call"))
            assertEquals("turn", first.source.turnId.value)
            assertEquals("run", first.source.runId.value)
            assertEquals("session", first.source.sessionId.value)
            assertEquals("真实输出", first.content)
        }

    @Test
    fun rejectsAnotherGoalAndUnknownCall() =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage)
            assertThrows(IllegalArgumentException::class.java) { goalEvidenceReader(storage).read("other", "call") }
            assertThrows(IllegalArgumentException::class.java) { goalEvidenceReader(storage).read("goal", "missing") }
        }

    @Test
    fun rejectsInterruptedTurnEvenWithVerifiedToolResult() =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage, TurnState.INTERRUPTED)
            assertThrows(IllegalArgumentException::class.java) { goalEvidenceReader(storage).read("goal", "call") }
        }

    @Test
    fun rejectsUnverifiedResult() =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage, verified = false)
            assertThrows(IllegalArgumentException::class.java) { goalEvidenceReader(storage).read("goal", "call") }
        }

    @Test
    fun rejectsAnyUnsettledCallInGoal() {
        listOf("NEEDS_REVIEW", "INTERRUPTED", "PENDING", "AWAITING_APPROVAL", "RUNNING").forEach { state ->
            withGoalEvidenceStorage { storage ->
                seedGoalToolEvidence(storage)
                storage.toolCalls.append("uncertain", "turn", "uncertain", "time.now", "1", "{}", state)
                assertThrows(IllegalArgumentException::class.java) { goalEvidenceReader(storage).read("goal", "call") }
            }
        }
    }

    @Test
    fun rejectsDeletedContentAndUnregisteredTool() =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage)
            val result = requireNotNull(storage.toolResults.byToolCall("call"))
            storage.contentStore.delete(ContentRef.parse(requireNotNull(result.contentRef)))
            assertThrows(IllegalArgumentException::class.java) { goalEvidenceReader(storage).read("goal", "call") }
            assertThrows(IllegalArgumentException::class.java) {
                GoalToolEvidenceReader(storage, ToolRegistry(emptyList())).read("goal", "call")
            }
        }
}

internal fun goalEvidenceReader(storage: HelixStorage): GoalToolEvidenceReader =
    GoalToolEvidenceReader(storage, ToolRegistry(listOf(BuiltInToolSource(listOf(TimeNowTool.descriptor())))))

internal fun seedGoalToolEvidence(
    storage: HelixStorage,
    terminal: TurnState = TurnState.COMPLETED,
    verified: Boolean = true,
) {
    storage.sessions.create("session", "Goal evidence", null, null, 1000)
    val goal =
        Goal
            .initial(
                GoalId("goal"),
                "Verify output",
                listOf(Criterion("c1", "Check result")),
                GoalBudgets(10, 20, 10000, 60000, 60000, 0),
                CorrelationId("corr"),
            ).copy(state = GoalState.RUNNING, runCount = 1)
    storage.goals.save(goal.toStoredGoal())
    storage.goalRuns.open("run", "goal", "USER_OPEN", 1000)
    var turn = storage.turns.start("turn", "session", 1000)
    storage.goalTurnBindings.bind("turn", "run")
    val states =
        listOf(TurnState.BUILDING_CONTEXT, TurnState.WAITING_MODEL, TurnState.RECEIVING_MODEL) +
            (if (terminal == TurnState.CANCELLED) listOf(TurnState.CANCELLING, terminal) else listOf(terminal))
    states.forEach {
        turn = storage.turns.updateState(turn, it, 0, if (it == terminal) 2000 else null, null)
    }
    storage.toolCalls.append("call", "turn", "call", "time.now", "1", "{}", "COMPLETED")
    val result = storage.toolResults.append("result", "call", "SUCCEEDED", "clock result", "真实输出")
    if (verified) storage.toolResults.markVerified(result)
}

internal fun withGoalEvidenceStorage(block: (HelixStorage) -> Unit) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val name = "goal-evidence-${UUID.randomUUID()}.db"
    val content = File(context.cacheDir, "goal-evidence-${UUID.randomUUID()}")
    val storage = HelixStorage.open(context, name, content)
    try {
        block(storage)
    } finally {
        storage.close()
        context.deleteDatabase(name)
        content.deleteRecursively()
    }
}
