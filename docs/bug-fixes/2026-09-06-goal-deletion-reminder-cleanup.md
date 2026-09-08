# Bug Fix: Goal 删除遗留提醒并可移除活动 run 状态

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: app, core/storage

## Problem

PrivacyDeletionService.deleteGoal 原来直接删除 Goal/audit/plan，既不取消 WorkManager/系统提醒，也不检查是否存在活动 run。删除后的旧提醒恢复请求会抛出目标不存在异常。

## Impact

目标删除后仍可能留下通知或待执行提醒；运行中的 Goal 可失去预算、恢复和结算所需的持久记录；旧恢复快照可能中断后续提醒同步。

## Root cause

将删除仅视为数据库行删除，遗漏提醒生命周期和正在执行的 run；恢复路径则假定此前列出的目标始终存在。

## Fix and invariants

GoalDeletionCoordinator 与 GoalReminderReconciler 共用串行边界。在同一 Room 事务中先要求目标不是 RUNNING 且所有 run 已关闭，随后等待提醒取消和通知撤回成功，最后删除既有 audit/Goal/无引用 plan。活动目标要求先停止；取消异常使数据库记录保留。删除成功后清除该目标的当前提醒查看请求。

恢复以可空查找处理被删除的 Goal，转为取消提醒，不因旧快照终止。相同串行边界防止已读取旧目标的恢复操作越过删除后重新排程。没有自动停止后重放、没有后台模型调用或权限变化。

## Alternatives considered

删除后才取消提醒会在进程死亡或取消失败时留下无主提醒。只检查 Goal 状态不足以防护仍有未关闭 run 的不一致状态，因此同时检查两者。直接删除活动 run 会破坏预算/副作用结算；本次沿用已有会话删除的先停止边界。

## Regression verification

API 34：GoalDeletionDeviceTest、GoalReminderPublicationDeviceTest、GoalRunCoordinatorDeviceTest 共 16/16。生产删除入口清理实际已发布/延迟提醒；隔离数据库覆盖活动 Goal 与未关闭 run 保留、关闭 run 后删除，以及取消异常保留 Goal。consumer JVM 291 项中 288 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint、Spotless 通过。Detekt 仍为既有 53 项，未声称全仓门禁通过。

证据：`build/main-verification/goal-deletion-result.json`、`goal-deletion-gates.log`、`goal-deletion-api34.log`。

## Residual risk

删除中途进程死亡和完整删除 UI 尚未设备验收。取消已成功但删除事务尚未提交时若失败，Goal 仍在，后续恢复可能依据保留检查点重新安排提醒；这属于未完成删除，不返回成功。底层 repository 供存储/隔离测试使用，产品删除必须经过生产协调入口。真机和长稳未运行。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
