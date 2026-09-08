# Bug Fix: Goal 恢复无条件替换同一检查点并重复提醒

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: app

## Problem

恢复协调器只看到非空 nextCheckpoint 就 REPLACE，即使同一检查点的 work 仍待执行或已经成功结束。成功检查点也没有消费路径。

## Impact

恢复重复创建 work；成功提醒可再次排入并重发，Goal 中已处理检查点长期残留。

## Root cause

调度只计算相对延迟，未携带绝对检查点身份，也未比较既有 WorkManager 状态；Goal 恢复不消费成功结果。

## Fix and invariants

以 Goal unique work name 和绝对检查点 tag 匹配既有任务。相同检查点的非终态或 SUCCEEDED work 保留，不同检查点或失败/取消的 work 才替换。协调器读取匹配成功事实，在 Room 事务中重读 Goal，仅清除仍匹配的 RUNNING/PAUSED 检查点并记 SYSTEM audit。不得覆盖更新的检查点，不改变 Goal 状态、预算或 run，不自动 Continue。

Worker 不读 Goal 数据，既有 WorkManager 是任务状态依据。成功记录仅表示 Worker 正常结束，不表示用户看到、阅读或接受通知。

## Alternatives considered

单纯 KEEP 在任务已完成时仍可新建 work，且不能区分新旧检查点；仅按 Goal ID 消费会错误清除用户更新后的检查点。按进程内集合去重不能覆盖恢复。现复用既有持久任务身份，不另建调度数据库。

## Regression verification

API 34：GoalCheckpointDeviceTest 与原提醒/删除/并发用例合计 11/11。覆盖 pending Work ID 保留、变更替换、成功消费与重复恢复不重建、旧成功不清除新检查点。consumer JVM 291 项中 288 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint、Spotless 通过。Detekt 仍为既有 53 项。

命令与产物：`build/main-verification/goal-checkpoint-gates.log`、`goal-checkpoint-api34.log`、`goal-checkpoint-result.json`。

## Residual risk

WorkManager 历史可能被系统清理；旧版本记录也没有检查点 tag。无法确认成功时保留 ADR-0004 的过期补发行为。通知发布与 Worker 成功记录不是跨系统事务，不能保证 exactly-once。真机、长稳和完整通知 UI 尚未在本组验收。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
