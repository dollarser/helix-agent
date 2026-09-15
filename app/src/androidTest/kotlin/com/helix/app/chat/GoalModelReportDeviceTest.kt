package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnStartSpec
import com.helix.app.goal.GoalReportTool
import com.helix.app.goal.goalModelReport
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class GoalModelReportDeviceTest {
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(2000)
        }

    @Test fun modelCanCompleteAnOpenEndedUnboundGoal() =
        fixture { s, g, start ->
            report(s, "t", "complete")
            start.coordinator.beginModelStream()
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("COMPLETED", s.goals.resolve(g).state)
            assertEquals("MODEL_COMPLETED", s.goalRuns.resolve(start.runId).outcome)
            assertEquals(
                "Checked the work",
                GoalSummaryQuery(s)
                    .forSession("s")
                    .single()
                    .status.modelSummary,
            )
            assertTrue(
                s.auditEvents.listByCorrelation(s.goals.resolve(g).correlationId).any {
                    it.type ==
                        "goal.model_report"
                },
            )
        }

    @Test fun noReportDoesNotCompleteOrBlockForMissingBindings() =
        fixture { s, g, start ->
            start.coordinator.beginModelStream()
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("PAUSED", s.goals.resolve(g).state)
            assertTrue(GoalSummaryQuery(s).forSession("s").single().canContinue)
        }

    @Test fun unfinishedReportRemainsResumable() =
        fixture { s, g, start ->
            report(s, "t", "in_progress")
            start.coordinator.beginModelStream()
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("PAUSED", s.goals.resolve(g).state)
        }

    @Test fun modelBlockerRequiresExplicitRepair() =
        fixture { s, g, start ->
            report(s, "t", "blocked")
            start.coordinator.beginModelStream()
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("BLOCKED", s.goals.resolve(g).state)
            assertFalse(GoalSummaryQuery(s).forSession("s").single().canContinue)
            assertTrue(GoalBlockerResolution(s, clock, ::id).resolve(g, "s", true))
            assertEquals("PAUSED", s.goals.resolve(g).state)
            assertEquals(1, s.goalRuns.listByGoal(g).size)
        }

    @Test fun cancellationOverridesCompletedReport() =
        fixture { s, g, start ->
            report(s, "t", "complete")
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals("CANCELLED", s.goals.resolve(g).state)
        }

    @Test fun manualPauseOverridesCompletedReport() =
        fixture { s, g, start ->
            report(s, "t", "complete")
            s.turns.requestPause("t", 2001)
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals("PAUSED", s.goals.resolve(g).state)
        }

    @Test fun unknownEffectOverridesCompletedReport() =
        fixture { s, g, start ->
            s.toolCalls.append("unknown", "t", "unknown", "files.write", "1", "{}", "NEEDS_REVIEW")
            report(s, "t", "complete")
            start.coordinator.beginModelStream()
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("BLOCKED", s.goals.resolve(g).state)
        }

    @Test fun laterWorkInvalidatesEarlierReport() =
        fixture { s, g, start ->
            report(s, "t", "complete")
            s.toolCalls.append("later", "t", "later", "time.now", "1", "{}", "COMPLETED")
            assertNull(s.goalModelReport("t"))
            start.coordinator.beginModelStream()
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("PAUSED", s.goals.resolve(g).state)
        }

    @Test fun capacityStopDoesNotCompleteDespiteReport() =
        fixture { s, g, start ->
            report(s, "t", "complete")
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_WINDOW_LIMIT"))
            assertEquals("BLOCKED", s.goals.resolve(g).state)
        }

    @Test fun executorRejectsForeignSessionAndMissingBinding() =
        fixture { s, _, _ ->
            val implementations = ToolImplementationRegistry()
            val registry = ToolRegistry()
            GoalReportTool.register(registry, implementations, s)
            val descriptor = registry.resolve(ToolName("goal.report"), ToolVersion(1))
            ModelToolSchema(descriptor.name, descriptor.description, descriptor.inputSchema.toString())
            val executor = implementations.resolve(ToolName("goal.report"), ToolVersion(1))
            val call = executableReport()
            assertTrue(executor.execute(call) is ToolExecutorResult.Completed)
            assertTrue(executor.execute(call.copy(sessionId = "foreign")) is ToolExecutorResult.Failed)
            assertTrue(executor.execute(call.copy(turnId = null)) is ToolExecutorResult.Failed)
            assertTrue(executor.execute(call.copy(turnId = "missing")) is ToolExecutorResult.Failed)
        }

    @Test fun executorRejectsCancelledAndClosedRuns() =
        fixture { s, _, start ->
            val implementations = ToolImplementationRegistry()
            GoalReportTool.register(ToolRegistry(), implementations, s)
            val executor = implementations.resolve(ToolName("goal.report"), ToolVersion(1))
            val cancelled =
                object : CancelSignal {
                    override fun isCancelled(): Boolean = true
                }
            assertEquals(ToolExecutorResult.Cancelled, executor.execute(executableReport().copy(cancel = cancelled)))
            start.coordinator.beginModelStream()
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertTrue(executor.execute(executableReport()) is ToolExecutorResult.Failed)
        }

    @Test fun unverifiedReportCannotCompleteGoal() =
        fixture { s, g, start ->
            val args = executableReport().args.toString()
            s.toolCalls.append("unverified", "t", "report", "goal.report", "1", args, "COMPLETED")
            s.toolResults.append("result", "unverified", "SUCCEEDED", "unchecked", args)
            assertNull(s.goalModelReport("t"))
            start.coordinator.beginModelStream()
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("PAUSED", s.goals.resolve(g).state)
        }

    @Test fun failedTurnOverridesCompletedReport() =
        fixture { s, g, start ->
            report(s, "t", "complete")
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "NETWORK"))
            assertEquals("FAILED", s.goals.resolve(g).state)
        }

    @Test fun reportSchemaRejectsExtraGoalIdAndInvalidStatusOrSummary() =
        fixture { s, _, _ ->
            val registry = ToolRegistry()
            GoalReportTool.register(registry, ToolImplementationRegistry(), s)
            val schema = registry.resolve(ToolName("goal.report"), ToolVersion(1)).inputSchema
            val invalid =
                listOf(
                    buildJsonObject {
                        put("status", "complete")
                        put("summary", "done")
                        put("goalId", "foreign")
                    },
                    buildJsonObject {
                        put("status", "anything")
                        put("summary", "done")
                    },
                    buildJsonObject {
                        put("status", "complete")
                        put("summary", "")
                    },
                )
            invalid.forEach { assertTrue(ToolSchemaValidator.validate(schema, it) is ToolSchemaValidation.Invalid) }
        }

    @Test fun previousTurnReportCannotCompleteAResumedGoal() =
        fixture { s, g, start ->
            report(s, "t", "complete")
            s.turns.requestPause("t", 2001)
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            val next =
                requireNotNull(
                    GoalRunCoordinator(s, clock, ::id).start(
                        GoalTurnStart(
                            g,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("s", "next", "next-message", "snapshot", "continue"),
                            TurnBudgets(5, 10, 800, 800, 5000),
                        ),
                    ),
                )
            next.coordinator.beginModelStream()
            next.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("PAUSED", s.goals.resolve(g).state)
            assertNull(
                GoalSummaryQuery(s)
                    .forSession("s")
                    .single()
                    .status.modelSummary,
            )
        }

    @Test fun newestContextBlockerWinsWhenRunTimestampsTie() =
        fixture { s, g, start ->
            start.coordinator.beginModelStream()
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            val next =
                requireNotNull(
                    GoalRunCoordinator(s, clock, ::id).start(
                        GoalTurnStart(
                            g,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("s", "next", "next-message", "snapshot", "continue"),
                            TurnBudgets(5, 10, 800, 800, 5000),
                        ),
                    ),
                )
            next.coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_WINDOW_LIMIT"))
            assertFalse(GoalBlockerResolution(s, clock, ::id).resolve(g, "s", false))
            assertEquals("BLOCKED", s.goals.resolve(g).state)
        }

    private fun executableReport(): ExecutableToolCall =
        ExecutableToolCall(
            "call",
            "goal.report",
            "1",
            buildJsonObject {
                put("status", "complete")
                put("summary", "Checked")
            },
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.ofEpochMilli(5000),
            NoCancellation,
            "s",
            "t",
        )

    private fun report(
        s: HelixStorage,
        turn: String,
        status: String,
    ) {
        val call = id()
        val args =
            buildJsonObject {
                put("status", status)
                put("summary", "Checked the work")
            }.toString()
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
            val goal =
                coordinator.create(
                    "Explain clearly",
                    listOf("Helpful answer"),
                    GoalBudgets(10, 10, 100000, 60000, 10000, 0),
                )
            val start =
                requireNotNull(
                    coordinator.start(
                        GoalTurnStart(
                            goal,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("s", "t", "m", "snapshot", "input"),
                            TurnBudgets(5, 10, 800, 800, 5000),
                        ),
                    ),
                )
            block(s, goal, start)
        } finally {
            s.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }

    private fun id(): String = UUID.randomUUID().toString()
}
