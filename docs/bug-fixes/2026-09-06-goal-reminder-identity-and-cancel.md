# Bug Fix: Goal 提醒共享点击目标且取消后仍显示通知

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: app

## Problem

所有 Goal 通知都用 requestCode=0、相同 Component 构建 PendingIntent，只有 extras 不同。Android 匹配 PendingIntent 时不比较 extras，FLAG_UPDATE_CURRENT 会更新同一个对象。通知本身只按 Goal ID 的 hash 数值区分，也存在不同 ID 的 hash 冲突。取消提醒只取消 WorkManager unique work，已经发布的通知没有移除。

## Impact

多个目标的提醒可能共享最后更新的点击目标，hash 冲突时通知相互覆盖；用户取消提醒后仍可能看到已发布通知。

## Root cause

把 Intent extras 当作 PendingIntent 身份，以及把 hash 当作唯一标识。任务队列取消和 NotificationManager 已发布通知的取消是不同操作。

## Fix and invariants

Intent data 使用按完整 Goal ID 编码的独立 URI，保持 FLAG_IMMUTABLE；通知使用完整 Goal ID 作为 tag，与稳定数值 ID 共同定位。生产 GoalReminderScheduler.cancelReminder 同时取消 unique work 和本 Goal 已发布通知，并清理旧版本同数值 ID 的无 tag 通知。没有增加模型调用、Tool 执行或自动 Continue。

## Alternatives considered

只修改 requestCode 仍可能有 hash 冲突；仅修改 extras 无法区分 PendingIntent。只取消 WorkManager 不能撤回已经发布的通知，所以显式处理两种生命周期。

## Regression verification

API 34 的 GoalReminderTest 3/3 通过，包括实际 WorkManager 发布、替换/取消，以及两个具有相同 hash 的 Goal 同时显示、PendingIntent 不同、取消一个后另一个仍在。consumer JVM 288 项中 285 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint、Spotless 通过。联合 Gradle 仍因既有 Detekt 53 项 exit 1。日志 `build/main-verification/goal-reminder-identity-gates.log`、`goal-reminder-identity-api34.log`，APK hash 见 `goal-reminder-identity-result.json`。

## Residual risk

本次覆盖通知身份和已发布通知的取消，不等于完整 Goal 提醒流程完成。检查点用户入口、Goal 状态同步、并发取消/发布、点击通知定位目标以及系统延迟/强停恢复仍待生产接线与验证。真机和长稳未运行。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
