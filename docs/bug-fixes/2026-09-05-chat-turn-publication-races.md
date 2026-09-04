# Bug Fix: 合并后 Turn 启动、终态与界面刷新存在发布竞态

Status: fixed
Date: 2026-09-05
Related HXA: HXA-028, HXA-079
Affected modules: `app`（`ChatService`、`SessionTurnAdmission`）

## Problem

M7 合入主线后的 App 全量设备回归暴露三类同源时序问题：极快的模型任务可能在
active-turn 登记前开始运行，用户此时停止任务会找不到待取消 Job；持久终态已经写入、
但协程尚未退出时立即发送下一条消息，会被仍占用的 session admission 丢弃；同时进行的
整屏刷新会基于旧的 `StateFlow.value` 重建状态，覆盖刚发布的拒绝、披露或流式界面状态。

## Impact

- “停止”操作在窄窗口内可能无效，而界面没有说明取消未命中。
- 用户看到终态后立即发送的下一条消息可能不启动。
- 附件拒绝、出网披露或流式文本可能被较晚完成的刷新恢复成旧状态。
- 单分支单测和构建可以全部通过，只有合并后的并发执行与设备回归暴露问题。

## Root cause

三个路径都缺少明确的发布先后关系：worker coroutine 创建后立即可调度，但 active-turn
登记和初始 UI 发布发生在其后；session 槽位过去只在 Job 的 completion callback 中释放，
晚于 durable terminal 和用户可见终态；`refreshScreen` 则先读取 `_screen.value`，完成其他
查询后再整体赋值，期间的针对性 UI 更新会被旧快照覆盖。

## Fix and invariants

- worker 先等待 `CompletableDeferred` start gate；只有 active-turn 已登记且初始 UI 已发布，
  `ChatService` 才打开 gate。任何可运行 Turn 都必须已能被停止路径找到。
- durable terminal 写入后、terminal UI 发布前，以 `sessionId + turnId` 条件释放 admission；
  旧 Turn 的清理不得删除后来登记的新 Turn。
- `refreshScreen` 使用 `MutableStateFlow.update`，并从 update lambda 收到的当前状态派生新值；
  不得用跨挂起或跨查询保存的 read-copy-write 快照覆盖较新的 targeted publication。
- 终态顺序长期保持为：持久终态 → 清理运行事实 → 条件释放 admission → 发布终态 UI。

## Alternatives considered

- 在测试或生产加入固定 `sleep`：只能缩小复现概率，不能建立 happens-before 关系。
- 等 Job 完全退出后再释放 session：会保留“用户已看到终态但下一条发送被丢弃”的窗口。
- 用一个全局 mutex 包住全部界面刷新：扩大临界区并串行化无关 session；原子更新已足以
  保护此处的状态合并。

## Regression verification

- `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest`，其中
  `SessionTurnAdmissionTest.aDurablyTerminalTurnFreesItsSlotBeforeTheJobCompletes` 固定终态
  提前释放语义，`staleTerminalCleanupDoesNotDropANewerTurn` 固定 turn-id 条件清理。
- 合并后分别执行 `:app:connectedConsumerDebugAndroidTest` 与
  `:app:connectedDeveloperDebugAndroidTest`：API 29 为 117/136 项、API 36 为 116/135 项，
  均 0 失败；覆盖停止、附件拒绝、流式状态和连续发送相关设备路径。

## Residual risk

start gate 和 admission 条件清理已有直接回归；整屏 refresh 与每一种未来 targeted
publication 仍需在新增 UI 状态时共同审查。新增字段若不从 `update` 的当前值合并，仍可能
重新引入同类覆盖竞态。

## Related records

- [M7 合并与验证进展](../development/m7-non-device-progress.md)
- [Tool Scheduler 准入与结算](2026-09-01-tool-scheduler-admission-and-settlement.md)
