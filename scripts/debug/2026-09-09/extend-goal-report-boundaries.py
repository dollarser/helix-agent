from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/GoalModelReportDeviceTest.kt')
s=p.read_text().replace('import com.helix.app.goal.goalModelReport','''import com.helix.app.goal.GoalReportTool
import com.helix.app.goal.goalModelReport
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry''')
idx=s.index('    private fun report(')
s=s[:idx]+'''    @Test fun executorRejectsForeignSessionAndMissingBinding() =
        fixture { s, _, _ ->
            val implementations = ToolImplementationRegistry()
            GoalReportTool.register(ToolRegistry(), implementations, s)
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
            val cancelled = object : CancelSignal { override fun isCancelled(): Boolean = true }
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

    private fun executableReport(): ExecutableToolCall = ExecutableToolCall(
        "call", "goal.report", "1",
        buildJsonObject { put("status", "complete"); put("summary", "Checked") },
        ExecutionTargetType.LOCAL_ANDROID, Instant.ofEpochMilli(5000), NoCancellation, "s", "t",
    )

'''+s[idx:]
p.write_text(s)
# A nonexistent turn has no binding and must fail before looking up its session.
p=Path('app/src/main/kotlin/com/helix/app/goal/GoalReportTool.kt')
s=p.read_text().replace('val owned = call.turnId?.let { storage.turns.resolve(it).sessionId == call.sessionId } == true','val owned = binding != null &&\n                        call.turnId?.let { storage.turns.resolve(it).sessionId == call.sessionId } == true')
p.write_text(s)
