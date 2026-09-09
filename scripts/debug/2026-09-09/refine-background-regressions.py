"""Keep pre-existing pause/compaction tests scoped to their original verified behavior."""
from pathlib import Path


def edit(path, old, new):
    p = Path(path)
    s = p.read_text()
    assert old in s, (path, old)
    p.write_text(s.replace(old, new))


binding = '''            val stored = storage.goals.resolve(goal)
            storage.goals.updateGoal(stored.copy(criteria = stored.criteria.map {
                it.copy(binding = com.helix.core.model.CriterionVerificationBinding(
                    com.helix.core.model.CriterionVerificationMethod.LOCAL_TOOL_SUCCESS, "read"))
            }))
'''
edit('app/src/androidTest/kotlin/com/helix/app/chat/LongTurnCompactionDeviceTest.kt',
     '            val first =\n', binding + '            val first =\n')
edit('app/src/androidTest/kotlin/com/helix/app/chat/LongTurnCompactionDeviceTest.kt',
     'second.coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_WINDOW_LIMIT"))\n            assertEquals("PAUSED",',
     'second.coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_WINDOW_LIMIT"))\n            assertEquals("BLOCKED",')
edit('app/src/androidTest/kotlin/com/helix/app/chat/GoalUsageReservationsDeviceTest.kt',
     '            started = requireNotNull(coordinator().start(request("first")))',
     binding.replace('resolve(goal)', 'resolve(goalId)') +
     '            started = requireNotNull(coordinator().start(request("first")))')
edit('app/src/androidTest/kotlin/com/helix/app/chat/GoalUsageReservationsDeviceTest.kt',
     '            assertEquals(300L, goal.totalTokens)\n            assertEquals("PAUSED", goal.state)',
     '            assertEquals(300L, goal.totalTokens)\n            assertEquals("BLOCKED", goal.state)')
edit('app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt',
     '        return request.inputTokens() <= control.budgets.maxInputTokens &&',
     '        return request.messages.size <= ModelRequest.MAX_MESSAGES &&\n            request.inputTokens() <= control.budgets.maxInputTokens &&')
with Path('docs/adr/0039-background-results-and-goal-blockers.md').open('a') as f:
    f.write('\n## Goal 指南采纳\n\n已阅读所有者提供的 [Goal 使用参考](../references/goal-feature-guide.md)，并核对 DeepSeek 当前官方 goal-round-driver 文档。采纳生命周期与执行开关分离、用户暂停中止当前轮、恢复不自动重放、稳定阻塞原因及执行前复查。保留 Helix 的证据验收；不采用模型独立自证完成。三轮门槛用于主观阻塞报告，不应用于已证实的预算/容量/未知副作用。此次仍不增加自动续跑驱动，原显式 Continue 约束保留；自动调度需要另行定义运行激活与轮次准入。\n')
