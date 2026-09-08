# Bug Fix: 已打开应用未重新消费同一 Goal 提醒

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: app

## Problem

关闭提醒并重建 Activity 后，再次发送生产通知 PendingIntent，目标没有重新打开。新增 API 34 回归在未修改实现上失败，提示 New notification click was not consumed。

## Impact

用户再次点击同一目标提醒时可能只回到应用，未重新进入该目标。

## Root cause

通知 Intent 未明确指定已有 Activity 的复用及重新投递方式。onNewIntent 已实现重置消费状态，但原默认启动路径未满足这个回归场景的重新打开要求。失败前后仅修改启动标志，实际 PendingIntent 回归验证了该路由修复；不据此宣称所有 Android 启动模式都有同一问题。

## Fix and invariants

增加 FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP，复用现有 MainActivity 处理新的通知请求。Activity 重建仍不会重新消费旧请求，新的用户请求则进入 onNewIntent；不新增 Goal run/Turn，不修改预算或审批，也不自动继续。

## Alternatives considered

清除已消费标识而不区分重建与新请求会重新打开已关闭提醒。每次新建 Activity 会累积导航实例且绕过既有 onNewIntent 路径。现明确复用方式，并同时断言 Activity 实例与 Goal/run 不变。

## Regression verification

API 34，未修改实现的导航 2 项中 1 项失败；修复后 2/2，再与真实通知及提醒/预算 UI 组合 7/7。测试直接发送生产 PendingIntent，并检查重新打开和同一 Activity 实例。consumer/测试 APK、Spotless、consumer Lint 通过；Detekt 仍既有 53 项，联合门禁不是全绿。

证据：`build/main-verification/goal-repeat-notification-result.json`、`goal-repeat-notification-before-api34.log`、`goal-repeat-notification-flags-api34.log`、`goal-repeat-notification-final-api34.log`。

## Residual risk

后续补充 API 34 实际通知栏节点点击：真实通知打开后选定 Goal 可见，状态/run/Turn 不变，相关组合 7/7；证据见 `build/main-verification/goal-notification-shade-result.json`。这不是全部 API 版本或进程外启动验收；其他 API 设备矩阵仍在整体待办中。完整真实模型流程和 ADR-0028 完成证据契约未在本次解决。真机、长稳未运行。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
