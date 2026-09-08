# Bug Fix: 取消或替换提醒后旧 Worker 仍可发布通知

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: app

## Problem

原生产路径调用 cancelUniqueWork 后立即取消系统通知，但取消操作异步且不会保证正在执行的 Worker 停止。Worker 若随后调用 notify，会重新显示已经取消的提醒；替换为未来检查点也可能保留旧通知。

## Impact

用户移除提醒或 Goal 进入需要取消提醒的状态后，仍可能收到旧通知。

## Root cause

把 WorkManager 取消请求当作已完成取消，并且没有序列化发布和通知撤回。官方文档明确取消是 best-effort，已经执行的 work 可能继续，见 [WorkManager API](https://developer.android.com/reference/androidx/work/WorkManager.html)。

## Fix and invariants

当前单进程提醒路径使用统一发布临界区。发布前读取该 Work ID 的真实 WorkManager RUNNING 状态；生产替换/取消在同一临界区等待队列操作完成后取消对应通知。已经发布则后续取消移除，取消先完成则旧发布被拒绝。替换也撤回旧通知。操作等待上限 10 秒；失败、取消、超时不返回成功。

不增加新的持久化系统，不读取 Goal 状态，不改变模型、审批或执行边界。WorkManager 仍仅负责可延迟通知，运行必须用户显式继续。

## Alternatives considered

仅检查 isStopped 留下检查到 notify 之间的竞态。仅等待取消操作而不串行化发布，也不能防止已经通过检查的发布。加入内存取消 ID 集合无法覆盖进程重启；额外持久化取消代次会重复 WorkManager 已有状态。现复用既有持久任务状态和当前单进程同步边界。

## Regression verification

API 34：三项实际 WorkManager 状态/系统通知的并发用例，加三项真实 GoalReminderWorker 发布、替换、hash 身份回归，共 6/6。并发用例控制迟到 publisher 调用同一生产发布门，覆盖取消后发布、替换后旧发布、发布进入临界区后取消；最终系统通知不存在。consumer JVM 291 项中 288 通过、3 条既有条件跳过，包括失败/取消/超时不误报成功。consumer/测试 APK、consumer Lint、Spotless 通过；Detekt 仍有既有 53 项。

命令、日志与 APK hash 见 `build/main-verification/goal-reminder-publication-result.json`、`goal-reminder-publication-gates.log`、`goal-reminder-publication-final-gates.log`、`goal-reminder-publication-api34.log`。

## Residual risk

适用于目前的同进程 WorkManager 提醒路径；不声称跨进程临界区或终止任何模型/工具后端。进程在取消操作或通知撤回中间死亡仍依赖恢复重建；删除 Goal 的通知/队列清理与检查点消费仍需接线验证。系统可延迟或丢弃提醒，真机和长稳未运行。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
