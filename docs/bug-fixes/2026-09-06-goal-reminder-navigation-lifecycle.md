# Bug Fix: Goal 提醒导航早于导航栈就绪及重建重复消费

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: app, core/storage

## Problem

原提醒 Intent 没有生产导航消费入口。补接后，真实 Activity 重建测试触发 `Cannot navigate to sessions. Navigation graph has not been set`；仅打开一次通过的结果没有覆盖初始化竞态。提醒请求若不记录消费状态，也会在 Activity 重建时再次打开。

## Impact

点击提醒可能无法定位目标或导致应用崩溃；关闭提醒后的重建可能重新展示已关闭的目标。

## Root cause

Compose LaunchedEffect 开始不代表 NavHost 已经安装 graph。Activity 的启动请求也不是一次性的 UI 事件，必须区分新的 onNewIntent 与旧请求的重建。

## Fix and invariants

从持久化绑定查询唯一会话；Intent 的 URI 与 extra 只提供 Goal ID，不接受外部传入的会话绑定。导航协程等待第一个 back-stack entry，再打开会话页面。统一首次启动和 onNewIntent 的消费入口，并保存已消费 ID；onNewIntent 才重新接收一次用户点击。保持原始启动 Intent 身份不变。打开提醒仅导航，不新建 run/Turn、不调用模型或批准工具；显式 Continue 仍通过服务和 reducer。

## Alternatives considered

固定延迟不能证明导航栈已就绪；捕获异常后忽略会丢失点击请求。修改 Intent data 会改变 Activity 启动身份，实际导致 ActivityScenario 无法匹配生命周期；改为保存独立消费状态。没有用移除重建断言来取得通过。

## Regression verification

当前 consumer APK，API 34：GoalReminderNavigationDeviceTest、GoalDialogDeviceTest、GoalRunCoordinatorDeviceTest 合计 13/13，通过后导航用例再次 2/2 通过。覆盖实际 Activity 入口、持久绑定会话、Goal/run/Turn 不变、关闭后重建不再发布打开请求。consumer JVM 285 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint、Spotless 通过。Detekt 仍有既有 53 项，未宣称全仓门禁完成。证据见 `build/main-verification/goal-reminder-navigation-result.json` 与 `goal-reminder-navigation-stable-api34.log`、`goal-reminder-navigation-repeat-api34.log`。

## Residual risk

提醒按钮、通知栏到目标详情再显式 Continue 的完整 UI 流程以及 onNewIntent 独立设备断言仍需补齐；Worker 发布/取消竞态、检查点消费和删除清理未在本次收口。真机、长稳未运行。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
