# Goal

[全部决策](../README.md) · [实施状态](../../development/status.md)

## 当前有效决定

- accepted [ADR-GOAL-001](001-lifecycle-and-completion.md)：目标生命周期、连续执行、预算与完成；crash/review 后 continuation 使用 new GoalRun / successor Turn

accepted 只表示设计决定；交付与验收查实施状态和对应任务。跨主题修改同时核对[权限](../permissions/README.md)、[执行域](../runtime/README.md)与[工作目录](../workspace/README.md)，不把一个主题的许可推导成另一个主题的授权。

## 统一输入交付

accepted [ADR-AGENT-001](../agent/001-turn-coordination.md)将普通发送统一为排队/显式转向，不再仅因新输入暂停 Goal；HXA-216 已交付该输入语义。2026-09-25 的恢复裁决进一步规定：process death/review 不复活旧 GoalRun，后续 continuation 创建新的 GoalRun/Turn。
