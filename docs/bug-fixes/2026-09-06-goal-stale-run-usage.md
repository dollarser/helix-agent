# Bug Fix: 旧 Goal run 快照可以回退持久用量

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: core/storage, app

## Problem

调用者持有较早的 GoalRunEntity 时，checkpoint 或 finish 可以覆盖数据库中更新后的用量。

## Impact

延迟回调可能降低 run 的模型调用、工具调用、token 或执行时长，破坏恢复证据及累计用量的单调性。

## Root cause

checkpointUsage 的 Kotlin 校验只比较调用参数和传入的 run 快照；DAO 仅要求 run 未结束。finish 只验证非负用量，同样没有与数据库当前计数比较。持有旧快照的调用可以通过校验后写小已持久化计数。

## Fix and invariants

两种 DAO 更新均在同一 UPDATE 的 WHERE 中要求四项当前计数不大于拟写入值，duration 空值按零处理。条件不满足时影响行数为零，由仓储明确报错，原有行保持不变。保留关闭 run 不可重写的条件；有效的不减计数仍可结束 run。无需 schema 迁移。

## Alternatives considered

调用前重新读取一次仍存在读写之间被更新的窗口；只增加 Kotlin 对旧快照的比较无法保证数据库行不回退，因此约束放在 SQL 更新条件中。

## Regression verification

新增旧快照回归覆盖四项计数逐个回退、checkpoint/finish 两条写路径、拒绝后整行不变、允许相等用量结束，以及关闭后拒绝后续 checkpoint。API 34 的 GoalRunCoordinatorDeviceTest（8 项）与 ProcessRecoveryTest（9 项）共 17/17 通过、0 skipped。JDK 17 构建 consumer Debug/测试 APK、consumer JVM、storage Lint 和 Spotless 均通过。命令及结果见 `build/main-verification/goal-run-monotonic-gates.log`、`goal-run-monotonic-api34.log`；APK hash 见 `goal-run-monotonic-result.json`。

## Residual risk

这是存储层计数保护；尚未实现模型/工具执行前的 Goal 持久预算预留，不能证明 crash 未记账窗口已经闭合，也不等于 Goal 产品接线或真实模型固定评测通过。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
