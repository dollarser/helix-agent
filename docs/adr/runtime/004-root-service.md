# ADR-RUNTIME-004: RootService 依赖与调用边界

Status: accepted
Date: 2026-09-16
HXA: HXA-094
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

Root 是实际系统能力，受控服务和依赖来源需要明确；取得 Root 不自动批准 Agent 调用。

## Decision

采用 libsu core/service 作为 Root shell/RootService 底座。仅 developer 的 tools:root 依赖它，consumer 制品不含 libsu。

JitPack 使用 exclusiveContent，仅允许 com.github.topjohnwu.libsu；不开放任意 group、HTTP Maven 或 flatDir。确切版本、锁文件和验证 metadata 同步维护，升级或 checksum 变化必须核对来源与许可，不能自动接受变化。

Root shell 仅由用户明确请求创建；查询状态、构造 adapter、切换 Profile、启动 App 和刷新 Registry 不调用 Shell.getShell 或冷绑定服务。使用非 daemon RootService，Binder 丢失、服务 crash、授权撤销或用户断开后不自动重绑和重放。

Root grant 只满足系统能力。业务工具仍经当前会话授权、执行限制、取消与审计；底座可运行不等于任意 Root 操作获准。实际 rooted 设备验证 grant/deny/revoke/crash 和进程清理，模拟接口不能替代设备证据。

## Alternatives considered

每次裸 su 进程自行管理 IPC 增加生命周期与注入风险；扩大整个仓库的 Maven 源范围没有必要。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
