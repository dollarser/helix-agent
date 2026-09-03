# Bug Fix: GoalReminderTest 在"worker 已处理"与"通知已张贴"之间的窗口上直接断言

Status: fixed
Date: 2026-09-03
Related HXA: HXA-013
Affected modules: `app`（androidTest 测试代码）

## Problem

`GoalReminderTest` 在双模拟器并发全量负载下出现一次性通知时序 flake
（单独重跑通过；status.md 既记"需单独跟进"）。

## Impact

- consumer/developer 设备门禁偶发红（低概率、负载相关），与"真缺陷"不可区分。
- 无生产影响（纯测试时序缺陷）。

## Root cause

`GoalReminderWorker.doWork` 的顺序是**先写证据槽、后张贴通知**：

```
lastProcessedObjective.set(objective)   // 测试的"worker 已处理"证据
ensureReminderChannel(...)              // NotificationManager 服务调用
manager.notify(...)                     // NotificationManager 服务调用
return Result.success()
```

测试的 `waitUntilWorkerProcessed` 以证据槽为准，一匹配就立刻断言
`activeNotifications` 含目标通知——但证据槽匹配只证明"该 worker 执行到了开头"，
**不证明 `notify` 已经完成**（channel ensure + notify 是两次系统服务交互）。
正常负载下窗口是毫秒级，双模拟器并发全量负载下该窗口被放大过一次，断言踩中。
次要因素：worker 运行在**被测 App 进程内**（WorkManager），全量套件期间该进程
重度负载，120 s 的 worker 启动边界也偏紧。

## Fix and invariants

测试侧两处（不改生产——证据槽"先于张贴"是既定设计：证据回答"哪个 objective
被执行了"，与张贴无关）：

- 通知断言改为**有界重轮询**（15 s，100 ms 间隔）：证据槽匹配后轮询
  `activeNotifications` 直到通知出现或超时——把"已处理 ≠ 已张贴"的合法窗口
  从断言点移入等待。
- worker 处理边界 120 s → 300 s（证据槽轮询），注释写明原因（worker 与全量
  套件同进程、并发负载下的实测长尾）。

不变式：**设备测试断言一个系统服务副作用（通知张贴）前，必须等到该副作用本身
可观察，而不是等到产生它的进程内的证据槽**。

## Alternatives considered

- 只放宽通知断言的重试、不动 worker 边界：修复一次性 flake 的主要成因，但
  120 s 边界在同进程全量负载下仍有长尾风险；两者成本都极低，一并处理。
- 改生产 worker 顺序（先 notify 后写证据槽）：改变证据语义（证据将意味着
  "已张贴"，但 `notify` 返回也不保证系统实际展示），且为测试时序问题改生产
  代码不值得。
- 用 `WorkInfo == SUCCEEDED` 替代证据槽轮询：`SUCCEEDED` 确实蕴含 notify 已调用，
  但 WorkInfo 状态查询同样是服务调用且刷新更慢，不优于有界重轮询。

## Regression verification

- 本 flake 为一次性负载现象，无法按需复现；修复依据是代码审计出的确定性窗口
  （证据槽先于两次系统服务调用）。
- 修复后双设备全量设备套件（含 GoalReminderTest ×2）通过；后续任何
  "reminder notification expected after the worker ran" 失败将携带 15 s 重轮询
  耗尽的信息，可直接判定为真实生产缺陷而非窗口踩点。

## Residual risk

- 300 s 边界仍是经验值：若未来套件规模显著增大导致 worker 启动长尾超过 300 s，
  需要再评估（信号：同一断言信息再次出现）。

## Related records

- [2026-09-03-approval-device-tests-session-scoped-probe](2026-09-03-approval-device-tests-session-scoped-probe.md)
  （同一批设备套件收敛工作）
