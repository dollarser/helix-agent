# ADR-0004: Goal 的 run/wake 模型与预算耗尽语义

Status: proposed
Date: 2026-08-31
HXA: HXA-013
Deciders: pending
Supersedes: none
Superseded by: none

## Context

[HXA-013](../04-roadmap-and-backlog.md) 要求实现 Goal reducer：Goal 状态、验收条件、模型/工具/token/时长/重试预算、checkpoint、`INPUT_REQUIRED`；首版只有用户显式继续创建新 run；预算耗尽不得完成；只有 verifier evidence 可满足 criterion。

规范给出的是状态与约束，不是事件/运行模型：

- [02 §6.2](../02-architecture-design.md) 的 `GoalState` 图（HXA-010 已实现并有全矩阵测试）只有 `DRAFT → READY → RUNNING` 与 RUNNING 的五个出边（`INPUT_REQUIRED/PAUSED/COMPLETED/FAILED/CANCELLED`），**没有** RUNNING → RUNNING 边，也没有独立的“运行之间等待”状态。
- [10 §6.1](../10-provider-mcp-skills-modes.md) 规定“首版只有用户显式继续才创建新 `goal_run`”，唤醒源只有 `USER_OPEN`/`NOTIFICATION_ACTION`；WorkManager 只发可延迟提醒，Doze/强制停止可延迟或取消提醒。
- [10 §6.2](../10-provider-mcp-skills-modes.md) 规定“预算耗尽是 `PAUSED` 或 `FAILED(BUDGET_EXCEEDED)`，不是成功”，两种归宿都被允许，但没指定取哪个。
- `GoalBudgets` 同时含“运行时长”与“单次唤醒时长”两个上限，说明一次 run 内可以有多次唤醒（wake），否则两个上限重合。

HXA-013 实现必须把“run / wake / 唤醒源 / 预算归属”落成 reducer 可判定的事件语义，且该语义将直接约束 HXA-014（Room `goals`/`goal_runs` 持久化哪些计数）与 HXA-015（恢复协调器如何消费 `GoalEffect`）。

## Decision

`GoalReducer`（`core:agent`）采用如下事件/语义：

1. **run 与 wake 分离**：`Continued(USER_OPEN|NOTIFICATION_ACTION)` 创建一次新 run（`runCount+1`，发出 `StartRun` 效果，携带剩余预算与 plan hash 供协调器组装 `TurnBudgets`）。一次 run 内的每次唤醒（wake）是一次 Turn；`WakeFailed` 在 `maxRetries` 内重试时**不离开 RUNNING**（不占状态机边，是同一 run 的新 wake）；唤醒失败次数消耗 `maxRetries`，重试耗尽或不可重试错误进入 `FAILED`。
2. **正常结束的 wake 使 Goal park 到 PAUSED**：`RunFinished`（Turn 正常结束、未耗尽预算、未请求完成）使 `RUNNING → PAUSED` 并发出 `RunFinished` 效果（若设置了 `nextCheckpoint` 则追加 `ScheduleCheckpointReminder`）。PAUSED 是 §6.2 中唯一的非终态“等待用户”状态：它同时承载“run 结束等下次唤醒”“预算耗尽”“进程死亡 park”三种情形；下一次唤醒只来自显式用户继续（`Continued` 从 `PAUSED`/`INPUT_REQUIRED`/`READY` 出发）。
3. **预算耗尽一律 PAUSED，不是 COMPLETED 也不是 FAILED**：`WakeUsageReported`（每次唤醒的聚合用量报告）按固定顺序检查 `maxModelCalls → maxToolCalls → maxTotalTokens → maxWakeDurationMillis → maxDurationMillis`，第一个超限即 `RUNNING → PAUSED` 并发出 `BudgetExhausted(limit)`（limit 为预算字段名）。选 PAUSED 而非 `FAILED(BUDGET_EXCEEDED)` 的原因：PAUSED 可被 `BudgetsUpdated`（仅 parked 时允许）+ 显式 `Continued` 恢复，符合“预算耗尽不得完成”且保留用户继续的路径；FAILED 是终态，会把本可恢复的目标杀死。`FAILED` 只保留给 wake 失败（不可重试或重试耗尽）。
4. **计数归属**：模型/工具/token/时长计数在 Goal 上跨 run 累计（goal 生命周期预算）；`currentWakeMillis` 只属于当前 wake，run 结束、park、失败重试时清零。
5. **进程死亡**：`afterProcessDeath` 复用 HXA-010 的 `GoalState.stateAfterProcessDeath()`（仅 `RUNNING → PAUSED`），重置 `currentWakeMillis`（未记账的部分唤醒作废），**保留** `nextCheckpoint`——提醒通知是合法的 `NOTIFICATION_ACTION` 唤醒源。
6. **提醒生命周期**：`ScheduleCheckpointReminder` 只在 RUNNING/PAUSED（有 checkpoint 时）发出；`INPUT_REQUIRED`、`COMPLETED`、`FAILED`、`CANCELLED` 发出 `ReminderCancelled`。提醒计划（`ReminderPlan`）纯函数化：checkpoint 已过期时延迟为 0（立即排程，Doze/强停后的补发恢复），null checkpoint 跳过；UI 文案不含精确时间。
7. **criterion 与完成**：`CriterionSatisfied` 只接受 verifier evidence（`verifier` 非空白 ≤128，且 `artifactRef` 与 `toolCallId` 至少一个非空）；`CompleteRequested` 只在 RUNNING 且**全部** criterion 已有 evidence 时被接受（`COMPLETED`，`finishReason="completed"`），否则忽略。

## Alternatives considered

1. **预算耗尽直接 `FAILED(BUDGET_EXCEEDED)`**：文档同样允许；语义更“终局”，UI 无需区分“等资源”与“失败”。未选择：`GoalState` 无 FAILED → 任意状态的出边，失败即不可恢复，与“用户扩预算后继续”的常见产品路径冲突；PAUSED + `BudgetExhausted(limit)` 保留了文档允许的另一分支且更可恢复。若产品后续要求“耗尽即终局”，可用取代 ADR 改本条。
2. **新增 `WAITING`/`BETWEEN_RUNS` 状态**表达“run 结束、等下次唤醒”：状态更自描述，但 `GoalState` 是 HXA-010 已定契约并有全矩阵测试，扩枚举需要 ADR-0002 同级别的增量决定并波及 HXA-014 Room 枚举列；§6.2 的 PAUSED 已能承载该等待语义（UI 用最后效果区分“等资源/等唤醒/进程死亡”）。首版不选；若 UI 需要显式区分再评估。
3. **提醒 worker 读取 Goal 当前状态后再决定是否发通知**：可避免过期提醒，但 worker 在 HXA-013 时点无存储访问（Room 归 HXA-014），且 worker 进程与主进程解耦后共享内存证据不可靠。未选择：worker 只发“检查点到了，打开 Helix 继续”的通知，是否真的唤醒由协调器在 `NOTIFICATION_ACTION` 时按 Goal 当前状态决定（过期提醒被 reducer 的 `Continued` 状态门控自然忽略）。
4. **每次 `WakeUsageReported` 都按独立 wake 清零 `currentWakeMillis`**：会把“一次唤醒内多次部分上报”误判为多次唤醒，突破 `maxWakeDurationMillis` 语义。未选择：`currentWakeMillis` 在一次 wake 内累加，仅在 wake 边界（`Continued`/重试/park/失败）清零。

## Consequences

- 收益：状态机零新增边（全部复用 HXA-010 契约），run/wake 语义可测试且与“只有用户显式继续创建新 run”逐字一致；预算可恢复，`FAILED` 语义收窄为真正的 wake 失败；提醒/恢复决策纯函数化（`ReminderPlan`），HXA-015 协调器可直接消费 `GoalEffect`。
- 代价：PAUSED 承担三种等待情形，UI 与审计必须读最后效果（`RunFinished`/`BudgetExhausted`/进程死亡记录）区分原因；`WakeUsageReported` 是“每 wake 一次聚合上报”的契约，协调器若拆成多次上报必须在 wake 边界外清零（本 reducer 不提供该事件，HXA-015 接入时必须遵守）。
- 后续约束：HXA-014 持久化时 `goals` 行存 goal 级累计计数、`goal_runs` 行存每次 run 的 `wakeReason`/outcome（与 [02 §9.1](../02-architecture-design.md) 表一致）；HXA-015 恢复协调器必须把 `StartRun` 的剩余预算与 provider/用户限制取 `stricterWith`（[02 §5.3](../02-architecture-design.md)），且 `NOTIFICATION_ACTION` 唤醒必须先过 reducer 状态门控。
- 风险：`RunFinished → PAUSED` 意味着“一个长 Goal 的每次 run 结束都会 park”，用户必须显式继续下一次 run；这是文档首版语义（只有用户显式继续创建新 run）的直接结果，若产品体验过碎需要重新讨论（见 Reconsider when）。

## Verification

已执行（HXA-013 工作树，JDK 17，Gradle 9.5.0，2026-08-31）：

- `./gradlew :core:agent:test` → 107 tests, 0 failures, 0 skipped，exit 0。其中 `GoalReducerLifecycleTest`(16)、`GoalReducerBudgetTest`(12)、`GoalReducerCriteriaTest`(8)、`ReminderPlanTest`(5) 覆盖：run 结束 park + checkpoint 重排、五类预算耗尽各自 park 并报告正确 limit、跨 wake 累计时长超限、重试/重试耗尽、进程死亡 park 且保留 checkpoint、criterion 仅 verifier evidence 可满足、完成需全部 criterion 有证据、提醒计划的状态门控与过期补发。
- `./gradlew :app:connectedConsumerDebugAndroidTest` → 3 tests, 0 failures（API 36 arm64-v8a 模拟器 `Helix_API_36`）：WorkManager 可延迟提醒真实发出通知、worker 从不触碰模型/工具（不变量计数器为 0）、reschedule 以 REPLACE 语义替换而非叠加、取消后无 pending 工作。
- [HXA-013 完成记录](../completion-records/HXA-013.md) 保存完整命令与结果。

required before acceptance：无额外实验；本 ADR 的语义已由上述测试锁定。

## Reconsider when

- 产品要求“run 正常结束后自动开始下一次 wake”（无需用户显式继续）——与 [10 §6.1](../10-provider-mcp-skills-modes.md) 首版语义冲突，必须先改规范。
- 产品要求预算耗尽即终局（`FAILED(BUDGET_EXCEEDED)`），或需要独立的“等待下一次唤醒”UI 状态。
- HXA-015 恢复协调器发现 `WakeUsageReported` 的“每 wake 一次聚合上报”契约无法从真实 Turn 执行器满足（例如流式 usage 必须多次上报），需要 wake 边界事件。
- `GoalState` 因其他 HXA 扩展枚举时，需同步复核本 ADR 第 2 条（PAUSED 三义）是否仍成立。

## References

- [02-architecture-design.md 第 5.3/6.2 节与 §9.1（Goal 状态、预算、Room 表）](../02-architecture-design.md)
- [10-provider-mcp-skills-modes.md 第 6.1/6.2 节（Goal 语义与状态）](../10-provider-mcp-skills-modes.md)
- [ADR-0002](0002-turn-state-intra-response-edges.md)（Turn 状态机增量边的先例）
- [HXA-013 完成记录](../completion-records/HXA-013.md)
- 实现：`core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt`、`GoalEvent.kt`、`GoalEffect.kt`、`ReminderPlan.kt`、`app/src/main/kotlin/com/helix/app/goal/GoalReminderScheduler.kt`
