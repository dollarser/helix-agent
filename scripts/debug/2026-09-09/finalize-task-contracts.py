"""Align HXA-177 live contracts, docs and injected blocker audit clock."""
from pathlib import Path


def edit(path, old, new):
    p = Path(path)
    s = p.read_text()
    assert old in s, (path, old)
    p.write_text(s.replace(old, new))


edit('app/src/main/kotlin/com/helix/app/chat/ChatService.kt',
     'GoalBlockerResolution(storage).resolve', 'GoalBlockerResolution(storage, clock, idGenerator).resolve')
edit('app/src/androidTest/kotlin/com/helix/app/chat/BackgroundTaskStorageDeviceTest.kt',
     'GoalBlockerResolution(storage)', 'GoalBlockerResolution(storage, clock, ::id)')
edit('docs/architecture/overview.md',
     '`DRAFT/READY/RUNNING/INPUT_REQUIRED/PAUSED/COMPLETED/FAILED/CANCELLED`',
     '`DRAFT/READY/RUNNING/INPUT_REQUIRED/PAUSED/BLOCKED/COMPLETED/FAILED/CANCELLED`')
with Path('docs/architecture/overview.md').open('a') as f:
    f.write('\n### HXA-177 任务与阻塞语义\n\n任务列表以 Turn 为身份，结果回收时间和暂停请求持久化于 turns；会话切换不取消执行。前台服务观察全部正在传输的任务。Goal 的 BLOCKED 需要先处理并重新检查阻碍，PAUSED 可显式 Continue；预算耗尽、上下文容量不足或未知副作用不可通过继续绕过。暂停停止当前执行，未知副作用优先转为 BLOCKED，进程恢复不重放。旧 INPUT_REQUIRED 保留兼容用户输入流程。详见 [ADR-0039](../adr/0039-background-results-and-goal-blockers.md)。\n')
with Path('docs/architecture/provider-mcp-skills-modes.md').open('a') as f:
    f.write('\n### Goal 阻塞与暂停更新（HXA-177）\n\n[ADR-0039](../adr/0039-background-results-and-goal-blockers.md) 部分替代早期 PAUSED 三义：BLOCKED 记录待解决依赖，禁止直接 Continued；宿主在用户修复动作后复查，满足门槛才转 PAUSED，后续显式继续创建新 run。用户暂停保留原 Turn 的实际终态与暂停请求；Goal 不因暂停而取消。预算与证据仍跨 run 保留。此增量不增加自动续跑或子 Agent。\n')
edit('core/model/src/main/kotlin/com/helix/core/model/GoalState.kt',
     ' * RUNNING        -> INPUT_REQUIRED | PAUSED | COMPLETED | FAILED | CANCELLED',
     ' * RUNNING        -> INPUT_REQUIRED | PAUSED | BLOCKED | COMPLETED | FAILED | CANCELLED')
edit('core/model/src/main/kotlin/com/helix/core/model/GoalState.kt',
     ' * PAUSED         -> RUNNING | CANCELLED   (explicit user continue / discard)',
     ' * PAUSED         -> RUNNING | BLOCKED | CANCELLED (explicit continue / dependency / discard)\n * BLOCKED        -> PAUSED | CANCELLED (host recheck after repair / discard)')
edit('core/model/src/main/kotlin/com/helix/core/model/GoalState.kt',
     ' * for every acceptance criterion; budget exhaustion lands in [PAUSED] or [FAILED], never in',
     ' * for every acceptance criterion; budget exhaustion lands in [BLOCKED], never in')
edit('core/agent/src/main/kotlin/com/helix/core/agent/GoalEvent.kt',
     ' * when any budget is exhausted.', ' * when any budget is exhausted.')
