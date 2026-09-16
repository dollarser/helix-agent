# ADR-0051: 后台 Job 与手动终端生产启用

Status: accepted
Date: 2026-09-14
HXA: HXA-196, HXA-197, HXA-198
Deciders: Project owner（2026-09-14 明确要求接受 ADR-0051）
Supersedes: none
Superseded by: none

## Context

项目所有者要求为命令详情、实时输出、后台执行和多终端会话设计小模型开发计划。现有 Linux 工具是有界一次性 Job；当前 owner 死亡触发取消，stdout/stderr 最终归档，不提供 PTY 会话。

已有单 APK/shared UID 决定见 ADR-0049。单 APK 不等于所有命令应变为持久 shell；界面、执行、授权和恢复需要分开定义。

## Decision

2026-09-14 所有者明确接受本 ADR，以下后台所有权、人工输入与多会话架构获得实施授权；无需再次请求同一架构接受。日志与职责沿用 [ADR-0050](0050-terminal-sessions-and-detached-jobs.md)。平台、组件与设备检查仍是生产启用/交付门禁，不记为已通过。先完成对应 Spike 再接线，前台 PTY 不必等待后台 Job 成功。

1. 复用已接受的 ADR-0050 日志能力；它不授予下述新增执行权限。
2. 新异步启动契约必须有持久身份、显式 Runtime 所有权、租期与累计预算。返回 accepted 不表示命令或 Goal 完成，结束事件不自动唤醒模型。仅在符合 ADR-0007 的有效后台执行路径中允许越过主进程 owner 死亡继续；旧同步 Job 的 death/cancel 语义不变。
3. 手动终端是 developer/Advanced 用户主动开启的可信执行入口，应用服务记录 USER origin、Workspace 关联与会话生命周期；人工输入不按字符触发工具审批。该用户授权不能供模型、MCP、网页、Skill 调用，首版不提供模型写入交互 Session 的接口。
4. 首版单个 live PTY，后续最多两个手动会话；可 detach/attach，只有一个写入连接。它们共享 UID/文件系统，不承诺目录或凭据隔离。手动域运行时与 Agent 本地代码/文件变更互斥；允许人工多会话是新的用户操作并发规则，不是对模型未知效应并发放行。
5. Runtime 拥有进程组/PTY/租期/日志，主应用拥有任务与授权绑定，UI 仅是观察/输入端。generation 区分进程生命期，未知副作用不重放，重启后不重建原 shell 内存。Session 状态与连接/执行标志分开。
6. 日志限额沿用 ADR-0050；异步 Job 以默认 5 分钟/最大 30 分钟作为初始实现限额，同时受原用户/Goal/平台剩余额度约束，不自动续期。手动会话不套用 Goal 预算，其租期/空闲回收须在接线前完成参数与设备记录。native/rendering 选择记录确切版本、许可证及设备结论；本接受不等于任意第三方依赖已通过许可审查，触发新的依赖/底座 ADR 时按约定处理。

本决定不完全取代旧 ADR；部分扩展 ADR-0007 的显式用户会话/有期限 detached owner，明确 ADR-0012 未覆盖的手动交互入口。模型工具授权、Goal 继续和完成语义、ADR-0049 的 UID/打包决定均保留。手动环境不是隔离沙箱，Workspace 关联范围不被描述为系统级限制。

## Alternatives considered

- 只做命令结果页：成本最低，HXA-194 可先交付；不能满足实时交互与长任务观察。
- 所有调用改为持久 shell：复用环境方便，但会破坏旧 Job 的输入/结果/审批契约，未选择。
- 每个按键走模型工具审批：无法形成实用交互体验，也混淆用户操作和模型请求，未选择。
- 多个独立终端 APK：可提供独立应用 UID，但增加安装/维护，当前产品仍按 ADR-0049 的单 APK 目标推进。
- 任意模型命令并发：未知代码效果与共享文件系统无法证明互不干扰，本包不采用。

## Consequences

用户获得可观察的命令结果和可重连的手动终端；新增 IPC/持久格式、资源限制和生命周期验证成本。手动输入属于此次明确接受的用户操作契约，不能用一个会话 ID 代替授权。

当前代码和文档中的单并发、owner 取消、60 秒工具上限不会因本文出现而自动失效。新的后台能力受 Android 实际允许的运行窗口约束，不能保证不被系统杀死。

## Verification

已执行：2026-09-14 读取 JobRunner、LinuxJobExecution、BackgroundTasks、ProotJobOwners、JobWire 与当前路线图；确认没有 PTY 产品入口、日志游标或会话 attach 协议。本 ADR 无功能实现或设备通过结论。

Required before production enablement / HXA completion（原接受前检查，仍待执行）：异步所有权/租期，或人工输入、Workspace 映射、占用和回收条款；后台切片须有 FGS 类型与拒绝降级证据，前台切片须明确前台限定及失去有效执行条件后的回收；PTY/native/rendering 切片须有固定版本、许可和设备 Spike。没有平台证据时不启用后台续跑，不用竞品 manifest 替代验证。各切片可单独开发和验收，某项通过不代表整套通过；日志和上述架构不重复决策。

## Reconsider when

需要模型控制交互式程序、多个自动写任务并发、超过候选租期的持续服务，或 PTY/FGS 的设备兼容性失败时重新决策。需要不可信代码隔离主数据时回到 ADR-0049 的隔离底座评估。

## References

- [开发计划与小模型任务包](../development/terminal-and-background-execution-plan.md)
- [Runtime 生命周期](0007-companion-runtime-lifecycle.md)
- [能力授权](0012-capability-first-advanced-grants.md)
- [单 APK Runtime](0049-integrated-developer-runtimes.md)
- [Goal 运行与预算](0004-goal-run-wake-budget-semantics.md)
