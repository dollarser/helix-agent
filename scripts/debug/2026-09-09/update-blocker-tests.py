"""Adapt regression expectations to explicitly authorized BLOCKED contract."""
from pathlib import Path


def edit(path, old, new):
    p = Path(path)
    s = p.read_text()
    assert old in s, (path, old)
    p.write_text(s.replace(old, new))


test = 'core/agent/src/test/kotlin/com/helix/core/agent/GoalReducerBudgetTest.kt'
p = Path(test)
s = p.read_text()
# These functions specifically exhaust budget; unrelated pause/retry tests retain PAUSED.
names = ['eachSingleWakeBudgetExhaustionParksGoal', 'totalDurationExhaustsAcrossWakes',
         'cumulativeUsageAcrossWakesExhaustsBudget', 'budgetUpdateOnlyWhileParked',
         'continuedIsIgnoredWhenNoModelCallsRemain']
for name in names:
    start = s.index('    fun ' + name)
    end = s.find('    @Test', start)
    if end < 0:
        end = len(s)
    s = s[:start] + s[start:end].replace('GoalState.PAUSED', 'GoalState.BLOCKED') + s[end:]
s = s.replace('val continued = reduceGoal(extended, GoalEvent.Continued',
              'assertTrue(GoalReducer.reduce(extended, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).ignored)\n        val repaired = reduceGoal(extended, GoalEvent.BlockerResolved).state\n        val continued = reduceGoal(repaired, GoalEvent.Continued')
p.write_text(s)
edit('core/agent/src/test/kotlin/com/helix/core/agent/GoalReducerLifecycleTest.kt',
     '        assertEquals(GoalState.PAUSED, goal.state)\n        goal = reduceGoal(goal, GoalEvent.BudgetsUpdated',
     '        assertEquals(GoalState.BLOCKED, goal.state)\n        goal = reduceGoal(goal, GoalEvent.BudgetsUpdated')
edit('core/agent/src/test/kotlin/com/helix/core/agent/GoalReducerLifecycleTest.kt',
     '        val fromPaused = reduceGoal(goal, GoalEvent.Continued',
     '        goal = reduceGoal(goal, GoalEvent.BlockerResolved).state\n        val fromPaused = reduceGoal(goal, GoalEvent.Continued')
edit('app/src/androidTest/kotlin/com/helix/app/chat/BackgroundTaskStorageDeviceTest.kt',
     'storage.sessions.create("s", "Fixture", 1000)', 'storage.sessions.create("s", "Fixture", null, null, 1000)')
# Other tests already bind verifiable criteria. Only unknown effects move from INPUT_REQUIRED.
for file in ['app/src/androidTest/kotlin/com/helix/app/goal/GoalCompletionDeviceTest.kt',
             'app/src/androidTest/kotlin/com/helix/app/chat/GoalRunCoordinatorDeviceTest.kt']:
    p = Path(file)
    s = p.read_text().replace('"INPUT_REQUIRED(NEEDS_REVIEW)"', '"BLOCKED(NEEDS_REVIEW)"')
    s = s.replace('assertEquals("INPUT_REQUIRED", storage.goals', 'assertEquals("BLOCKED", storage.goals')
    s = s.replace('assertEquals(GoalState.INPUT_REQUIRED.name, storage.goals', 'assertEquals(GoalState.BLOCKED.name, storage.goals')
    p.write_text(s)
# Preserve the normal-park test's purpose: provide a real, user-selected binding before running.
edit('app/src/androidTest/kotlin/com/helix/app/chat/GoalRunCoordinatorDeviceTest.kt',
     '    fun normalTurnParksGoalAndContinueKeepsThePreviousOutcome() =\n        withStorage { storage ->\n            val coordinator = coordinator(storage)\n            val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)',
     '''    fun normalTurnParksGoalAndContinueKeepsThePreviousOutcome() =
        withStorage { storage ->
            val coordinator = coordinator(storage)
            val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)
            val stored = storage.goals.resolve(id)
            storage.goals.updateGoal(stored.copy(criteria = stored.criteria.map {
                it.copy(binding = com.helix.core.model.CriterionVerificationBinding(
                    com.helix.core.model.CriterionVerificationMethod.LOCAL_TOOL_SUCCESS, "read"))
            }))''')
edit('app/src/androidTest/kotlin/com/helix/app/ui/ChatCompactionFlowDeviceTest.kt',
     'assertEquals("PAUSED", storage.goals.resolve(goalId).state)',
     'assertEquals("BLOCKED", storage.goals.resolve(goalId).state)')
