"""HXA-177 budget state and UI strings. Run from owning checkout."""
from pathlib import Path


def edit(path, old, new):
    p = Path(path)
    s = p.read_text()
    assert old in s, (path, old)
    p.write_text(s.replace(old, new))


edit('core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt',
     'val parked = next.copy(state = GoalState.PAUSED, currentWakeMillis = 0L)',
     'val parked = next.copy(state = GoalState.BLOCKED, currentWakeMillis = 0L)')
edit('core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt',
     '        val checkpoint = state.nextCheckpoint\n        val next = state.copy(state = GoalState.PAUSED',
     '''        if (!state.canStartRun()) {
            return step(state, state.copy(state = GoalState.BLOCKED, currentWakeMillis = 0L),
                listOf(GoalEffect.BudgetExhausted("remainingBudget"), GoalEffect.ReminderCancelled))
        }
        val checkpoint = state.nextCheckpoint
        val next = state.copy(state = GoalState.PAUSED''')
edit('app/src/main/kotlin/com/helix/app/recovery/GoalDurableUsageLedger.kt',
     'else GoalState.PAUSED.name', 'else GoalState.BLOCKED.name')
edit('app/src/main/kotlin/com/helix/app/chat/GoalRunSettlement.kt',
     '            outcome,\n            clock.now()',
     '            if (next.state.state == GoalState.BLOCKED && !outcome.startsWith("BLOCKED(")) "BUDGET_EXHAUSTED(remainingBudget)" else outcome,\n            clock.now()')
strings = {
    'background_task_result': ('查看结果', 'View result'),
    'background_task_result_missing': ('结果已不可用，请回到原会话检查。', 'Result unavailable. Inspect the original conversation.'),
    'background_task_result_empty': ('本次任务未生成对话文本，请在原会话查看工具记录与中断原因。', 'This task produced no conversation text. Open the original conversation for tool records and interruption details.'),
}
for directory in ('values', 'values-zh-rCN', 'values-en'):
    p = Path('app/src/main/res') / directory / 'strings.xml'
    additions = ''.join(f'    <string name="{key}">{value[directory == "values-en"]}</string>\n' for key, value in strings.items())
    p.write_text(p.read_text().replace('</resources>', additions + '</resources>'))
edit('core/model/src/test/kotlin/com/helix/core/model/StateMachinesTest.kt',
     '            GoalState.RUNNING to GoalState.PAUSED,',
     '''            GoalState.RUNNING to GoalState.PAUSED,
            GoalState.RUNNING to GoalState.BLOCKED,
            GoalState.PAUSED to GoalState.BLOCKED,
            GoalState.BLOCKED to GoalState.PAUSED,
            GoalState.BLOCKED to GoalState.CANCELLED,''')
edit('core/storage/src/androidTest/kotlin/com/helix/core/storage/RoomMigrationFixtureTest.kt',
     '                    HelixDatabase.MIGRATION_9_10,',
     '                    HelixDatabase.MIGRATION_9_10,\n                    HelixDatabase.MIGRATION_10_11,')
