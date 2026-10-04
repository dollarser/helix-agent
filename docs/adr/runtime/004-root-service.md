# ADR-RUNTIME-004: RootService 依赖与调用边界

Status: accepted
Date: 2026-10-04
HXA: HXA-094
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

Root 是实际系统能力，受控服务和依赖来源需要明确；取得 Root 不自动批准 Agent 调用。

## Decision

采用 libsu core/service 作为 Root shell/RootService 底座。仅 developer 的 tools:root 依赖它，consumer 制品不含 libsu。

JitPack 使用 exclusiveContent，仅允许 com.github.topjohnwu.libsu；不开放任意 group、HTTP Maven 或 flatDir。确切版本、锁文件和验证 metadata 同步维护，升级或 checksum 变化必须核对来源与许可，不能自动接受变化。

Root shell 仅由用户明确请求创建；查询状态、构造 adapter、切换 Profile、启动 App 和刷新 Registry 不调用 Shell.getShell 或冷绑定服务。使用非 daemon RootService，Binder 丢失、服务 crash、授权撤销或用户断开后不自动重绑和重放。

Root grant 只满足系统能力。业务工具仍经当前会话授权、执行限制、取消与审计；底座可运行不等于任意 Root 操作获准。实际 rooted 设备验证 grant/deny/revoke/crash 和进程清理，模拟接口不能替代设备证据。

## Decision history — 2026-10-04

所有者要求验证 Root UI 操作并接入 Mobile Use。developer 宿主可扩展既有非 daemon `HelixRootService`，增加封闭的精确节点点击协议；libsu service 通过 `tools:root` 的 API 依赖供该子类编译，consumer 仍不含 libsu。Mobile Use 单独显式请求和断开服务连接以支持切换目标应用，不借用 `root.*` 的业务 scope，也不改变原只读 Root 工具的后台失权规则。两者使用同一 libsu 底座，不声称进程/凭据隔离。查询与自动路由不请求授权、不冷绑定、不自动重放；即时撤权局限与逐动作安全边界见 [ADR-PERMISSIONS-001](../permissions/001-session-authorization.md)。

## Decision history — 2026-10-04：独立精确点击

Root 的 Mobile Use 精确点击可在 Helix 无障碍服务关闭时运行。复用现有 UserService 与封闭点击协议，由高权限进程获取窗口事实，宿主维护原调用授权、取消和共享物理占用；仍不新增任意执行入口、自动提权或服务自动重绑。具体边界见 [ADR-PERMISSIONS-001](../permissions/001-session-authorization.md)。

## Decision history — 2026-10-04：独立屏幕工具

所有者授权观察、截图和手势继续使用既有 RootService/Shizuku UserService。增加封闭的三操作 IPC，不新增任意 shell：请求/轨迹有界，图片通过只读 SharedMemory 返回，像素和编码字节遵循既有 VisionLimits。系统私有 API 仅限 shell/root 进程的平台桥，调用前检查 UID，兼容性失败不冒充成功。截图范围、frame、取消与后端选择合同见 [ADR-PERMISSIONS-001](../permissions/001-session-authorization.md)。

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
