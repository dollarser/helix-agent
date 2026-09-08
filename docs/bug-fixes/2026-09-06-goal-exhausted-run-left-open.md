# Bug Fix: Goal 预算耗尽后 run 未关闭

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: app

## Problem

预算耗尽后 Goal 为 PAUSED，但当前 run 仍打开且 outcome 为空。

## Impact

重启后仍保留孤立 open run，无法从 run outcome 得到稳定暂停原因。

## Root cause

`GoalDurableUsageLedger` 达到预算后将 Goal 改为 PAUSED，但只更新 run 用量，没有关闭 run 或填写 outcome。启动恢复只处理 RUNNING Goal，因此这个已暂停 Goal 的 open run 不会被后续恢复修复；ADR-0004 要求的持久暂停原因在 run 中缺失。

同时达到两项时长上限时，ledger 先检查累计时长，与 ADR-0004 的单次 wake 时长优先顺序不同。

## Fix and invariants

在已有 Room transaction 中先保存 Goal 与 run 用量，耗尽时以更新后的计数关闭 run，写入 `BUDGET_EXHAUSTED(limit)`，并保留同事务 usage audit。墙钟回拨时 endedAt 夹紧至 startedAt，实际用量不变。两项时长同时耗尽时优先记录 maxWakeDurationMillis。

不增加自动继续或副作用重放，不改变预算耗尽为 PAUSED 的语义。Goal 正常模型/工具生产接线仍未完成，本修复是其持久化前置项。

## Alternatives considered

不依赖下一次进程恢复补写，因为恢复只处理 RUNNING Goal，且会错把已知预算耗尽归类为进程中断。

## Regression verification

专用 API 34 模拟器上 `ProcessRecoveryTest` 9/9 通过、0 skipped：保留既有恢复测试，扩充预算边界断言，新增重开数据库/重复恢复/墙钟回拨与双时长限制优先级测试。证据为本地 `build/main-verification/goal-budget-device-api34.log` 及同名 result JSON，记录本次 APK SHA-256。

consumer Debug 与 androidTest APK、CLI androidTest APK 构建完成；CLI app 单元测试执行通过。Spotless 通过。全仓 Detekt 失败，共 53 项，已单独记录在当前待办；不把整个联合 Gradle 命令计为通过。

consumer Debug Lint、core agent JVM 和 CLI client JVM 回归联合命令 exit 0，日志 `goal-budget-regression.log`。

## Residual risk

尚不构成真实模型 Goal 固定评测、完整 Goal 产品接通、API 29/36 新版设备复验或长稳通过。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
