# 进程死亡恢复与 Harness 深度：Session Continuation 优先于 Same-Turn Rehydrate

> 日期：2026-09-25
> 性质：当前综合研究结论；核心恢复裁决已于 2026-09-25 提升到 ADR-AGENT-001 / ADR-GOAL-001，代码迁移已由 HXA-220 交付。
> 当前实现权威：[ADR-AGENT-001](../../adr/agent/001-turn-coordination.md)、[HXA-220 交付记录](../../completion-records/HXA-220.md)。
> 本文解决：Helix 是否有必要在 Android App 被杀后恢复“同一个 Turn / 同一个 GoalRun / 同一个预算进度”，还是只恢复 Session 和事实，让模型在 successor Turn 中继续。

## 1. 结论

当前研究更推荐：

> **浅工作流，深边界。Harness 保存事实、权限和不可违反的不变量；LLM 决定任务如何继续。App/进程死亡后默认关闭旧 Turn，恢复 Session/工作区/历史，并通过一个新的 successor Turn 继续，而不是精确 rehydrate 原 Turn。**

Helix 当前 same-Turn rehydrate 不是“错误实现”，它解决了真实的副作用不确定性；但它把两个不同问题绑定得过紧：

1. **上下文连续性**：模型需要知道之前做到哪里、有哪些文件/测试/结果；
2. **外部副作用真相**：某个已 dispatch 的操作究竟有没有发生。

第一类问题可以由 durable Session/history + Recovery Summary + 模型自己检查当前世界解决；第二类问题必须由 Harness fail-closed 保存事实，不能让模型猜。

因此推荐保留 effect-safety，弱化 execution-state recovery。

## 2. 主流 Harness 的共同形态

### 2.1 Codex：durable Thread，Turn 是一次工作单元

OpenAI 2026 App Server 公开架构把 Thread 定义为持久会话容器，把 Turn 定义为一次用户输入触发的 agent work。Thread 可以 create/resume/fork/archive，历史持久化；客户端断开后可以重新连接同一 Thread。Codex Web 甚至明确要求浏览器不能成为长任务的 source of truth，工作状态留在 server/harness。

这支持的核心抽象是：

```text
Thread / Session     = durable long-lived conversation
Turn                 = one execution attempt
Client               = disposable projection / control surface
```

公开资料没有要求客户端在崩溃后精确恢复旧 Turn 的内部 model-call/tool-round 状态；重点是 Thread persistence、agent loop、tools、approval 和 reconnect。

Codex 的 Goal 设计也把 continuation 放在**安全 turn boundary**：当前 turn 结束、thread idle、无待处理用户输入后才考虑下一 continuation turn。Goal 是 thread-scoped objective，不等于一个永不结束的 Turn。

外部来源：

- https://openai.com/index/unlocking-the-codex-harness/
- https://developers.openai.com/cookbook/examples/codex/using_goals_in_codex

### 2.2 Claude Code：恢复 Session，并用 continuation prompt 让模型继续

Claude Code 支持 `/resume` / `/continue` 恢复会话；当前公开环境变量还提供：

- `CLAUDE_CODE_RESUME_INTERRUPTED_TURN=1`
- `CLAUDE_CODE_RESUME_PROMPT`

默认 continuation message 是 `Continue from where you left off.`。这说明 Claude Code 对“中断后继续”的公开模型更接近：**恢复历史 + 给模型一个 continuation input**，而不是要求 Harness 重新进入旧 Turn 的内部步骤。

Claude Code 的 `/rewind` / checkpointing 可以恢复 conversation/file changes，但不能据此推导所有外部 side effect 都可事务回滚。

外部来源：

- https://code.claude.com/docs/ko/env-vars
- https://code.claude.com/docs/zh-CN/commands

### 2.3 DeepSeek Harness：最直接的先例——关闭崩溃 Turn，不做 partial-turn resume

DSH 当前 persistence 文档对 crash recovery 描述最明确：持久日志保留 crash 前已经 durable 的事件；如果最后一个 Turn 缺少 `turn/end`，resume reader 会追加：

- 缺失 tool call 的 synthetic error/result；
- 缺失 `step/end`；
- `turn/end { interrupted }`。

其官方 package README 明确说明：

> Synthetic closers are the only crash story；没有“partial-turn resume that continues an interrupted turn instead of closing it”。

同时 checkpoint policy 在 tool effect 之前做 durability checkpoint；crash 后 unmatched call 会得到模型可见的 `TOOL_OUTCOME_UNKNOWN`，read-only/idempotent 工作可以重试，可能有副作用的调用要求先验证状态或用户确认，**不会自动 retry**。

这套设计与本文推荐最接近：

```text
old Turn -> interrupted + protocol balanced
Session  -> durable and resumable
next Turn -> inspect current world and continue
unknown effect -> fact preserved, no blind replay
```

外部来源：

- https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/persistence.md
- https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/session/session-persistence/README.md
- https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/session/session-checkpoint-policy/README.md

### 2.4 OpenCode：Session 是恢复边界，Agent/Tool/Permission 由 Harness 控制

OpenCode 公开接口以 Session 为持久交互单位，提供 list/create/fork/abort/summarize/revert/unrevert/message；TUI 的 `/sessions` 兼具 `/resume` / `/continue`，`/undo` 同时恢复 conversation 与 Git-backed file changes。

OpenCode 同时把 Build/Plan/subagent、tool permissions、session lifecycle 做成 Harness 约束，但公开资料没有展示 Helix 式 same-Turn crash rehydrate。

外部来源：

- https://opencode.ai/docs/tui/
- https://dev.opencode.ai/docs/server/
- https://opencode.ai/docs/zh-cn/agents/
- https://dev.opencode.ai/docs/permissions/

### 2.5 移动端：Operit / PalmClaw 更强调 Session、Workspace、权限和 Runtime

Operit 当前 Android 版公开能力包括 chat history/branch/summary、parallel conversations、Workspace binding、Ubuntu/terminal、browser agent、Allow/Ask/Deny tool permission、backup/crash repair 等；PalmClaw 强调 native Android runtime、per-session processing、统一 permission、tool/skill/channel。公开资料都没有展示“恢复同一个 Turn + 同一个 GoalRun + old model-call budget”的必要性。

这不能证明它们内部完全没有恢复状态，但说明**移动端公开产品设计的重心也不是精确 Turn rehydrate，而是 Session/Workspace/Runtime/Permission 的连续性**。

外部来源：

- https://github.com/AAswordman/Operit
- https://github.com/ModalityDance/PalmClaw

## 3. “浅 Harness”应该如何定义

“浅 Harness”不等于只有工具列表。现代 Harness 在**边界层很深**：

- Session / Thread persistence；
- context compaction / memory；
- tool schema 和 effect dispatch；
- allow / ask / deny、sandbox、workspace scope；
- cancel / interrupt；
- request idempotency；
- tool result ordering；
- crash durability checkpoint；
- external effect unknown；
- observation / UI event stream。

应该“浅”的是**工作流策略**：

- 先读什么文件；
- 是否重新跑测试；
- 任务还差哪一步；
- 是否要继续调查；
- 怎么拆 plan；
- 当前世界是否满足目标。

这些问题让模型在新的 Turn 中根据 durable history + 当前文件/测试状态自己判断，通常比 Harness 保存旧 Turn 的细粒度 control state 更自然。

因此推荐术语：

> **Shallow policy, deep invariants（浅策略，深不变量）。**

## 4. Helix 当前 same-Turn 方案为什么会变复杂

HXA-220 为 same-Turn review resume 引入或强化了：

- `TurnState.NEEDS_REVIEW`；
- `tool_call_reviews`；
- `turn_runtime_records`；
- provider/model immutable snapshot；
- consumed model/token/tool-round checkpoints；
- review resolution receipt / next ModelCall identity；
- `TurnCoordinator.rehydrateWaitingModel()`；
- same-open-GoalRun `BLOCKED -> RUNNING`；
- session-wide parked gate；
- BACKFILL continuation。

其中**effect truth 与 idempotency**部分价值很高；但“为了恢复同一 Turn 而恢复旧 model-call/tool-round/budget/run identity”的部分属于可疑复杂度。

## 5. 推荐的新恢复模型

### 5.1 Process death

```text
PENDING ToolCall
  -> cancelled-before-start

RUNNING ToolCall
  -> interrupted / outcome unknown when effect may have escaped

open model/tool protocol
  -> append/synthesize balanced interrupted result as needed

old Turn
  -> INTERRUPTED / closed

Session
  -> stays durable and resumable
```

旧 Turn **不重新进入 RUNNING/BUILDING_CONTEXT/WAITING_MODEL**。

### 5.2 用户继续

UI 提供一等入口：

- `继续上次任务`；
- 或用户直接发送新消息。

两者都创建 **new successor Turn**。

Harness 给 successor Turn 注入一个有界 `RecoverySummary`：

```text
Previous turn was interrupted.
Completed facts:
- ...

Uncertain effects:
- call X: execution started; completion not observed

Current durable artifacts/checks:
- ...

Continue from the current world state.
Do not repeat unresolved side effects blindly.
```

模型再主动：

- 看 diff；
- 读文件；
- 跑测试；
- 检查外部状态；
- 决定下一步。

### 5.3 UNKNOWN 仍然必须是 Harness 事实

不能把 effect uncertainty 交给模型猜。

建议保留：

- PENDING/RUNNING execution-start boundary；
- immutable review fact；
- no blind replay；
- provider/idempotency key 支持时透传 stable call identity；
- effect footprint/ownership；
- timeout != executor exited。

但 review 的结果应成为 **successor Turn 可见事实**，不必恢复旧 Turn。

### 5.4 Session 不必因为一个 UNKNOWN 完全冻结

当前 Helix 的 session-wide NEEDS_REVIEW gate 安全但偏重。

更轻的候选方案：

- 允许用户创建 successor Turn；
- read-only / inspection tools 可以继续；
- unresolved side-effecting footprint 不允许 blind replay；
- 模型和 UI 明确看到 unresolved effects；
- 如果要再次做重叠 side effect，先要求 review/state verification。

这一点需要单独 ADR 设计 effect-overlap policy，本文不直接授权实现。

### 5.5 Goal：长期 intent，Run 是一次尝试

推荐：

```text
Goal
  Run N -> INTERRUPTED / BLOCKED
  Run N+1 -> continuation after explicit user/system admission
```

不要求 review 后恢复 same open GoalRun。Goal 的累计预算/用户意图可持续，但 Run/Turn 作为一次执行 attempt 更容易闭合、审计和恢复。

## 6. 哪些 Helix 设计应保留

即使改为 successor-Turn recovery，下列工作仍应保留：

- `ToolCall PENDING vs RUNNING` execution-start boundary；
- `tool_call_reviews` 或等价 immutable effect review fact；
- `clientRequestId + fingerprint` request idempotency；
- batch result 原调用顺序；
- `ExecutionOwnership`：timeout/cancel 不等于 executor exited；
- Room durable Session/Turn/Tool/Goal facts；
- DurableSessionGate 的“不能发生双 live owner”部分；
- TurnEngine single durable owner；
- context compaction / request manifest / artifacts。

## 7. 哪些当前设计可重新评审/简化

如果接受 successor-Turn recovery，可重新评审：

- same-Turn review resume；
- `ReviewedTurnResumeTicket`；
- `TurnCoordinator.rehydrateWaitingModel()`；
- review-specific next ModelCall receipt；
- consumed model-call/tool-round checkpoint 作为 crash-resume 必需事实；
- same-open-GoalRun review resolution；
- NEEDS_REVIEW 对整个 session 的全面 fresh-Turn 阻断。

注意：`turn_runtime_records` 仍可能保留 immutable execution configuration 作为审计/重放诊断事实；“不再用于 same-Turn crash resume”不等于整表应删除。

## 8. 冲突裁决

| 冲突 | 旧结论 | 当前综合裁决 |
| --- | --- | --- |
| App crash 后如何继续 | Helix HXA-220 倾向 same-Turn rehydrate | **Research 推荐 old Turn closed + successor Turn**；effect facts 保留 |
| 谁判断工作没做完 | Harness 恢复旧 phase/model step | **模型根据 RecoverySummary + 当前世界判断**；Harness 只保事实 |
| UNKNOWN | whole Turn/session parked，再恢复旧 Turn | **仍 fail-closed，但不要求旧 Turn 复活** |
| Goal | same GoalRun `BLOCKED -> RUNNING` | **更倾向新 GoalRun continuation**，长期 Goal 保留 |
| Budget | 恢复 old Turn model/tool-round budget | **new Turn 重新计 Turn budget；Goal/Session 长期预算另行累计** |
| 安全性 | 深恢复更安全 | **真正安全来自 effect truth / idempotency / ownership，不来自恢复 old Turn control state** |

## 9. 对 HXA-220 的影响

该方向已经由项目所有者接受并写入 ADR-AGENT-001 / ADR-GOAL-001。HXA-220 已改为 successor-Turn 迁移任务；本节保留 Research 层的原因与边界，不再承担实施顺序。

在正式裁决前：

- E1-B3a/B3b 的 single live owner、cancel/timer owner 收敛继续执行；
- HXA-220 在 E1-B4 前完成 old-Turn terminal / successor-Turn / RecoverySummary / new GoalRun cutover；
- 已有 C5-B same-Turn 代码在迁移卡完成前保持正确，之后按 caller graph 删除，不再新增依赖。

## 10. 重新评审条件

以下情况可能使 same-Turn resume 再次有价值：

- Provider 支持真正可恢复的 server-side run/turn identity；
- 工具系统有 exactly-once effect ledger；
- 用户明确需要暂停/迁移同一次执行 attempt，而不是“继续任务”；
- successor Turn 的模型恢复质量经真实任务评估显著差于 same-Turn；
- regulatory/audit 要求一次业务执行必须保持固定 Turn/Run identity。

否则默认选择更简单、可解释、接近主流 Harness 的 successor-Turn continuation。
