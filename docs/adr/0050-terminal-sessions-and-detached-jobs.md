# ADR-0050: 有界日志与终端职责拆分

Status: accepted
Date: 2026-09-14
HXA: HXA-195
Deciders: Project owner（2026-09-14 授权审查并接受合理部分；限下述决定）
Supersedes: none
Superseded by: none

## Context

原提案捆绑日志 IPC、后台 owner、人工输入、PTY 选型和多会话并发，使日志也等待后台证据。此次收窄为已明确的日志与职责契约；原未决条款移至 [ADR-0051](0051-terminal-runtime-enablement.md)，稳定文件名保留。

当前一次性 Job 有身份、归档、取消和 owner 死亡处理，无 PTY 产品入口及实时日志协议。ADR-0049 的单 APK/shared UID 决定保持。

## Decision

1. 接受 HXA-195 有界日志：版本化 IPC、新事务号，保留旧查询、执行上限、审批、最终 hash/import 与结算契约；预览不是验证产物。
2. 每批绑定 Job 身份与运行代次，带序号/游标、stream、字节和结束/截断标志。读取按授权身份限定；重复幂等、缺口可见、跨 Job 拒绝。慢读端不能阻塞进程 drain 或积累无界 callback，UTF-8 跨批解码。
3. 初始日志限额：每批 IPC 32 KiB、UI 热缓存每 Job 256 KiB、spool 每 Job 4 MiB/总计 16 MiB。它们不提高旧 Job 更严格的输出硬限额；日志滚动截断、低存储明确失败。参数调整须有设备/压力证据并同步计划，不扩大执行权限。
4. UI 展示与表达意图；app 应用服务拥有来源、任务绑定、查询和取消；client/IPC 传输；Runtime 拥有进程、日志和退出事实。复用现有 Job 状态，不建第二套执行器。
5. 一次性 Job 与未来 Session 分开建模；Session 使用身份和 generation，PID 不作授权或重放依据。前台 PTY 不以后台 Job 成功为架构前提。本条不授予人工 shell 或模型 PTY 输入能力。
6. 成功、失败、取消、EOF 和未知副作用都持久结算。观察不启动任务，UI 断开不改变现有 owner/cancel，未知副作用不重放；Goal 显式继续和完成语义不变。

HXA-194/195 在相关基线通过后可实施，不等待 ADR-0051。后台 owner、手动输入授权、Workspace 实时映射、PTY 组件、多会话及租期参数不在此次接受范围。

## Alternatives considered

- 一次接受整套提案：缺平台/组件依据，未选择。
- 只展示最终输出：保留为 HXA-194，但不能满足实时观察。
- 用通用 Session 替换 Job：破坏身份与结算，没有必要。

## Consequences

日志不再被后台与 PTY 决策阻塞，新增 IPC 兼容、配额和流控验证成本。没有新增产品实现或设备通过结论；同 UID 信任边界不变，日志内容不能触发系统操作或成为模型系统指令。

## Verification

已执行：2026-09-14 对照 JobRunner、JobWire、owner/cancel、开发计划与 ADR-0049；确认缺少实时日志及 PTY。本次仅接受设计，不修改生产代码。

Required before HXA-195 completion：计划 G1/G2/G3、输出在命令结束前可见、背压/低存储/UTF-8/取消/EOF/跨 Job/断线、归档完整性及 consumer 排除；本次无这些新增设备结果。

## Reconsider when

设备证明限额不适用、IPC 无法兼容旧契约、日志需要触发工具，或需要改变 owner/授权时重新评审。

## References

- [Runtime 启用决定](0051-terminal-runtime-enablement.md)
- [开发计划](../development/terminal-and-background-execution-plan.md)
- [Runtime 生命周期](0007-companion-runtime-lifecycle.md)
- [单 APK](0049-integrated-developer-runtimes.md)
