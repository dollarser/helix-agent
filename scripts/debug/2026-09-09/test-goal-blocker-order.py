from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/GoalModelReportDeviceTest.kt');s=p.read_text();idx=s.index('    private fun executableReport()');s=s[:idx]+'''    @Test fun newestContextBlockerWinsWhenRunTimestampsTie() =
        fixture { s, g, start ->
            start.coordinator.beginModelStream()
            start.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            val next = requireNotNull(GoalRunCoordinator(s, clock, ::id).start(
                GoalTurnStart(g, GoalWakeReason.USER_OPEN,
                    TurnStartSpec("s", "next", "next-message", "snapshot", "continue"),
                    TurnBudgets(5, 10, 800, 800, 5000)),
            ))
            next.coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_WINDOW_LIMIT"))
            assertFalse(GoalBlockerResolution(s, clock, ::id).resolve(g, "s", false))
            assertEquals("BLOCKED", s.goals.resolve(g).state)
        }

'''+s[idx:];p.write_text(s)
