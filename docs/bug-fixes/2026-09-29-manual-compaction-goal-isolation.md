# Bug Fix: 手动上下文压缩与 Goal 执行隔离

Status: fixed
Date: 2026-09-29
Related HXA: HXA-174, HXA-176, HXA-177
Affected modules: app

## Problem

Goal 模式发送 `/compact` 仍走普通输入准入，可能创建/绑定 Goal，并在压缩结束后自动续跑；用户观察到 get_goal/update_goal。压缩请求本身没有工具，错误在其外层目标生命周期与后继调度。

## Impact

只想压缩历史的用户可能意外启动目标执行、消耗目标预算或改变目标状态。

## Root cause

Turn 准入依据会话模式选择 Goal，没有区分手动维护指令；重试仍读取当前会话配置，且普通成功 Turn 会进入自动续跑调度。

## Fix and invariants

- 在统一 Turn 准入识别手动压缩及原始消息为 `/compact` 的重试，使用仅该 Turn 生效的 CHAT/无工具配置，不改变会话模式。
- 手动压缩不创建或绑定 Goal，不登记目标编辑的直接用户授权；成功准入后解除旧的进程内自动续跑资格，不修改持久 Goal。
- 仍使用现有压缩、模型预算、持久 checkpoint 与取消结算路径。已有目标状态和累计用量保持不变。
- 任务内按上下文压力触发的自动压缩不受此分支影响，继续计入原 Turn/Goal。

## Alternatives considered

不通过提示词要求模型忽略 Goal，也不隐藏活动记录掩盖调用。入口按钮单独绕过不足以覆盖手输和重试；隔离放在共同准入边界。

## Regression verification

- 主机：双渠道 `testConsumerDebugUnitTest/testDeveloperDebugUnitTest`、`lintConsumerDebug/lintDeveloperDebug`、debug APK / AndroidTest APK 构建、`spotlessCheck`、`detekt` 通过；源码/文档检查 `check-all.sh --source` 与 `git diff --check` 通过。
- 所有者授权 API36 arm64 独占模拟器 `Helix_HXA229_Closeout_API36`（4 GiB）：consumer **27/27**、developer **27/27**。运行 `GoalInputSchedulingDeviceTest`、`ContextCompactionDeviceTest`、`LongTurnCompactionDeviceTest`；完成后自动关闭设备。
- 新增四类真实 ChatService/Room 场景：无 Goal、已有暂停 Goal、运行中取消、无可压缩历史后重试。断言摘要无工具、无 Goal 绑定/新建/续跑，既有 Goal 及用量和所选 GOAL 模式保持不变；同时覆盖既有队列与自动压缩回归。
- 中间失败保留：第一轮 consumer 23/27，仅一轮历史被最新历史保护规则全部保留，三个摘要请求等待超时；补足三轮历史后第二轮 26/27。剩余重试测试在 UI 发布可重试目标前调用，调整为等待 `retryTargetTurnId` 与目标一致，第三轮双渠道全部通过。未降低断言或修改压缩保护规则。
- 可复跑入口：`scripts/debug/2026-09-29/run-compact-goal-checks.py`，通过 `scripts/with-host-slot.py` 串行执行；再次运行须使用新的输出目录。当前源码基于 `7480141b` 加本轮未提交修改。原始本机日志与 APK 摘要位于忽略目录 `build/compact-goal-api36-r3-{consumer,developer}/`（`artifacts.json`），主机日志 `build/compact-goal-host-final.log`、测试编译日志 `build/compact-goal-test-r7.log`。

## Residual risk

使用本机 loopback 测试服务，不调用真实模型或账号。设备验证仅限 API36 定向范围，不证明所有模型或 OEM 表现。未自动提交、推送或替换已发布 APK；README 的待提交升级提醒保留。

## Related records

- [上下文压缩 ADR](../adr/agent/002-context-compaction.md)
- [输入恢复审查](2026-09-29-interaction-recovery-audit.md)
