# 架构与执行引擎

> 更新：2026-09-26。当前综合研究，不覆盖 accepted ADR。
> 当前执行契约/实现证据：[ADR-AGENT-001](../../adr/agent/001-turn-coordination.md)、[HXA-220](../../completion-records/HXA-220.md)、[HXA-221](../../completion-records/HXA-221.md)、[HXA-223](../../completion-records/HXA-223.md)；当前任务见[实施状态](../../development/status.md)。
> 进程死亡专题：[process-death-recovery-and-harness-depth.md](process-death-recovery-and-harness-depth.md)。

## 1. 总原则：浅策略，深不变量

综合 Codex、Claude Code、OpenCode、DeepSeek Harness 与 Helix 各阶段研究，最合理的 Harness 边界是：

> **模型决定工作流；Harness 决定事实、权限、执行边界和不可违反的不变量。**

Harness 应深管 Session/Thread persistence、context/compaction、tool schema/result pairing、permission/scope、cancel/interrupt、request idempotency、durable checkpoint、external-effect unknown、observation 和 live execution identity。

模型应主导下一步、是否重读/重测、plan 如何拆、任务是否完成，以及中断后从当前世界状态如何继续。

## 2. 主流 Harness 对照

### Codex

OpenAI App Server 把 Thread 定义为持久会话、Turn 定义为一次 agent work；同一 Codex core/harness 被 CLI、IDE、Desktop、Web 共用。App Server 管 thread lifecycle/persistence、config/auth、tool execution/extensions，客户端只消费稳定事件和 approval request。

来源：https://openai.com/index/unlocking-the-codex-harness/

### Claude Code

公开能力把 session/permissions/Plan、background agent、checkpoint/rewind、resume/branch 等交给 Harness；模型仍负责大部分任务分解和继续方式。中断恢复可以通过 continuation prompt 让模型接续。

来源：
- https://code.claude.com/docs/zh-CN/commands
- https://code.claude.com/docs/ko/env-vars

### OpenCode

Session Server 提供 create/fork/abort/revert/message，agents 划分 Build/Plan/subagent，并用 allow/ask/deny 控制工具。结构上同样是“Session/Tool/Permission 深，任务策略浅”。

来源：
- https://dev.opencode.ai/docs/server/
- https://opencode.ai/docs/zh-cn/agents/
- https://dev.opencode.ai/docs/permissions/

### DeepSeek Harness

append-only SessionEvent 是 source of truth，persistence/checkpoint policy 保护 request/tool effect；crash 时修复并关闭 interrupted Turn，而不是 partial-turn resume。是 Helix 恢复设计最有价值的直接参照。

来源：
- https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/session.md
- https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/persistence.md

## 3. Helix 当前架构判断

`core/`、provider、tools、runtime、feature、extensions 的现有边界整体合理；不要为了“架构更漂亮”继续拆 Gradle module。真正需要收敛的是 **owner 和状态解释**。

### TurnEngine single durable owner 是合理的

之前 ChatService/TurnCoordinator/Recovery/Goal/SessionTurnAdmission 分别解释 Turn 生命周期，真实存在 race 和重复 settlement。把 durable admission/cancel/review/terminal/recovery/receipt 收到 TurnEngine 是必要修复，而不是过度设计。

### live driver 也应收敛，但不能让 Engine 反向依赖 UI

HXA-223 后冻结的实际边界：

```text
UI / Chat projection
        ↓ commands
Application submission / SessionInputDelivery
        ↓
TurnEngine
  ├─ admission / cancel
  ├─ terminal settlement
  ├─ review park / resolve
  ├─ startup recovery
  ├─ live execution registry + AgentLoop driver
  └─ observation hub
        ↓
AgentLoop → TurnCoordinator
             └─ model/tool/compaction round checkpoints
        ↓
Room / Tool Dispatcher / Provider / Runtime

Turn terminal + owner release
        ↓
SessionWorkScheduler
  ├─ queued user input first
  └─ eligible Goal continuation
```

ChatService 保留 composer、screen projection、用户 submit/confirm/disclosure 与 UI-facing application commands；Session queue drain/Goal continuation algorithm 已移出，不能重新成为 Turn owner。`TurnCoordinator` 允许写 execution-round checkpoints，但 terminal/review/cancel/recovery durable truth 只属于 Engine-owned collaborators。

## 4. Recovery 冲突裁决

旧 HXA-220 设计倾向 deterministic review 后 same-Turn rehydrate。最新竞品/DSH 证据更支持：

> **旧 Turn 闭合为 interrupted，Session 保留；successor Turn 通过 RecoverySummary + 当前世界继续。**

真正必须保留的是 effect truth，而不是旧 Turn 的 control state。详细理由见专题文档。

该方向已由 ADR-AGENT-001 / ADR-GOAL-001 裁决，并在 HXA-220/223 完成生产 cutover；same-Turn old execution resurrection 不再是当前实现路径。

## 5. Goal 的合理层级

Goal 更适合作为长期 objective / completion contract；Turn/GoalRun 是一次执行 attempt。

Codex 最新 Goal 公开设计也把 continuation 放在 safe turn boundary，并要求 objective completion 结合实际证据，而不是让一个 Turn 无限运行。

研究推荐：

- Goal 可 durable；
- continuation 发生在新 Turn/新 Run；
- model 可以报告/请求完成，但系统/用户持有 pause/resume/clear/budget 等生命周期权力；
- crash 不要求 same GoalRun resurrection。

来源：https://developers.openai.com/cookbook/examples/codex/using_goals_in_codex

## 6. 并发、取消与副作用

必须保持：

- tool concurrency 由 platform 根据 effect footprint 决定，不由模型自行并发；
- only proven non-conflicting read parallelism；
- result 按模型原调用顺序回填；
- timeout/cancel != executor exited；
- effect owner 直到 executor exit / explicit isolation / settlement 才释放；
- process-local Job 不成为 durable truth。

这些约束比“恢复 old Turn”更重要，也更接近真正的 Harness 职责。

## 7. 不建议的方向

- 为 Chat/Plan/Act/Goal 建四套 loop；
- 用 event sourcing/新 DB 再造 Room 的第二事实源；
- Engine 反向持有 ChatService/UI StateFlow；
- 把所有 effect 都包装成可自动 retry；
- 用复杂 workflow DSL 替代模型自身 planning；
- 为减少行数删除仍承担 fault oracle 的 reducer/test。

## 8. HXA-223 后优先级

1. **冻结 Core owner 边界**：没有新的实证缺陷时，不再为行数、命名或竞品形式继续拆 TurnEngine/TurnCoordinator。
2. **建立固定任务 eval**：用真实 Queue/Steer/Goal/review/recovery 长轨迹验证 task success、recovery-adjusted success、人工介入和 context fidelity，而不是继续凭代码结构判断 Agent 能力。
3. **UI/IA 第二轮可独立推进**：UI 只消费 application commands/read models，不把 durable lifecycle 写权重新带回 Compose/Chat facade。
4. **新增能力按 ADR/任务条件进入**：Workspace 仍等待 ADR-WORKSPACE-004；本地模型 HXA-222 已规划但暂缓；Subagent/Workflow 不因竞品存在而自动进入主线。
