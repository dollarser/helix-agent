from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/GoalModelReportDeviceTest.kt');s=p.read_text().replace('import com.helix.tools.framework.ToolRegistry','''import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator''');idx=s.index('    private fun executableReport()');s=s[:idx]+'''    @Test fun reportSchemaRejectsExtraGoalIdAndInvalidStatusOrSummary() =
        fixture { s, _, _ ->
            val registry = ToolRegistry()
            GoalReportTool.register(registry, ToolImplementationRegistry(), s)
            val schema = registry.resolve(ToolName("goal.report"), ToolVersion(1)).inputSchema
            val invalid = listOf(
                buildJsonObject { put("status", "complete"); put("summary", "done"); put("goalId", "foreign") },
                buildJsonObject { put("status", "anything"); put("summary", "done") },
                buildJsonObject { put("status", "complete"); put("summary", "") },
            )
            invalid.forEach { assertTrue(ToolSchemaValidator.validate(schema, it) is ToolSchemaValidation.Invalid) }
        }

    @Test fun previousTurnReportCannotCompleteAResumedGoal() =
        fixture { s, g, start ->
            report(s, "t", "complete")
            s.turns.requestPause("t", 2001)
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            val next = requireNotNull(GoalRunCoordinator(s, clock, ::id).start(
                GoalTurnStart(g, GoalWakeReason.USER_OPEN,
                    TurnStartSpec("s", "next", "next-message", "snapshot", "continue"),
                    TurnBudgets(5, 10, 800, 800, 5000)),
            ))
            next.coordinator.beginModelStream()
            next.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("PAUSED", s.goals.resolve(g).state)
            assertNull(GoalSummaryQuery(s).forSession("s").single().status.modelSummary)
        }

'''+s[idx:];p.write_text(s)
p=Path('docs/architecture/provider-mcp-skills-modes.md');s=p.read_text().replace('验收条件仍需要 Helix 可验证证据。','Helix 模型应结合实际结果判断目标完成，远端状态不直接控制本机 Goal（ADR-0040）；本机工具副作用仍由工具验证器核实。').replace('持续推进有验收条件的目标','持续推进目标，可附补充要求');p.write_text(s)
