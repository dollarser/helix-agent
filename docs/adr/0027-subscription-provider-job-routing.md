# ADR-0027: 订阅模型 Job 显式平台路由

Status: proposed
Date: 2026-09-06
HXA: HXA-144
Deciders: pending
Supersedes: none
Superseded by: none

## Context

所有者要求继续实现 Claude、Grok、Copilot 对话 Provider。accepted ADR-0025 已允许 developer/Advanced 的第三方订阅 Provider，凭据仍属于独立 Runtime UID。

当前 `CliModelRequestCodec` 的 version 1 只编码 `ModelRequest`，没有平台标识；`CliRuntimeService` 解码后固定创建 `CodexSubscriptionModel`。主 App 的 `SubscriptionProviderModule` 只注册 Codex。模型名不能唯一标识平台：相同模型可能通过原供应商与 Copilot 提供。

本提案只补充多平台 IPC 路由，不重新决定官方 CLI 路线、渠道或凭据所有权。未获所有者明确接受前保持 proposed，不启动依赖本决定的生产路由。

## Decision

提议采用 version 2 请求封套，包含封闭的字符串 `providerId` 与现有模型请求字段。`providerId` 的协议值为 `codex`、`claude`、`grok`、`copilot`；它不是 Room 的配置 ID、模型名、URL 或凭据 alias。编码器保持确定性字段顺序，SHA-256 覆盖完整封套，因此换平台必须改变 request hash；同 jobId 异 hash 沿用拒绝规则。

Runtime 在读取任何 vault 或发出网络请求前严格解码平台与字段，再从编译期封闭路由选择对应凭据、刷新器与 adapter。未实现的平台返回稳定不支持结果，不能回退 Codex，也不能由封套指定任意 endpoint、header 或凭据。各平台仍分 HXA 顺序交付；协议枚举不表示平台已经实现。

兼容性提议如下：

- 保留现有 Binder descriptor、transaction 与 status version；只升级 PFD 请求封套版本。旧 Runtime 的严格 v1 decoder 会拒绝 v2，新平台请求不会被旧端按 Codex 执行。
- 新 Runtime 支持旧 v1 请求，并将其严格限定为历史 Codex 路由；新客户端对 Codex 继续发送原字节格式。Claude 及后续平台发送 v2；旧 Runtime 返回不支持时提示更新，不重发或降级。
- 旧 journal、事件 codec 和 job record 格式不变。已有非终态仍按原恢复规则停泊；已有终态仍按原 jobId 查询、输出 hash 校验和对账删除，绝不重编码旧请求后重新提交。
- 保留请求 512 KiB、事件 1 MiB/2048 条、单终态、signature caller 校验、显式冷绑定及空闲解绑。新平台没有自动登录、后台常驻或独立 Tool 执行权。

主 App 复用统一订阅 `ModelProvider` facade，通过受管理配置选择平台；平台差异留在 Runtime。首轮只提供文本模型能力，tool/vision 未验收前关闭；所有对话继续经过既有 ProviderService、ChatService、出网门控、持久化与 Audit。登录入口由固定平台到 Activity 映射选择，不接受任意 ComponentName。

## Alternatives considered

1. 由模型名前缀推断：不能区分同一 Claude 模型的直接服务与 Copilot，容易选择错误凭据，未推荐。
2. 每个平台复制一套 Binder/Job client：隔离明确，但会重复取消、断连与恢复逻辑，长期容易漂移，未推荐。
3. 把整个 Binder 协议升为 v2：版本协商明确，但会阻断原有 Codex 调用与旧任务对账。本次封套扩展可以严格拒绝未知版本，暂不推荐整体升级；如设备验证发现旧端拒绝不稳定，应重新比较。

## Consequences

同模型名可以在不同订阅 Provider 中安全区分；跨平台同 jobId 不能共享执行记录。生产路由、受管理配置与账号入口应各有单一映射，不复制模型调用管线。

代价是短期需要同时测试 v1/v2，且新的非 Codex 平台需要更新 Runtime APK。协议可用不代表订阅资格或真实额度可用：Claude/Grok 无付费账号时只记录 fixture/设备证据；Copilot 实测依赖用户登录及实际额度。各平台网络格式、认证失败与额度错误在各自 HXA 验证，本 ADR 不声称其端点可调用。

## Verification

已执行：在合并 main 后检查 `CliModelPayloadCodec.kt`、`CliModelJobClient.kt`、`CliRuntimeService.kt` 和 developer `SubscriptionProviderModule.kt`，确认上述 v1/Codex-only 现状。

required before implementation acceptance：

- codec：v1 字节兼容；v2 平台 round-trip；未知版本/平台/字段、超限、图片引用拒绝；相同 request 换平台改变 hash。
- 路由：相同模型名分平台选择；未实现平台不读 vault、不联网；同 jobId 异平台拒绝；取消胜过迟到响应；401/403/429 与断流不伪报成功。
- API 29/36 arm64-v8a：旧 Codex 回归、新平台 fixture 对话、PFD、断连只查询原 jobId、Runtime 重建、对账删除和空闲解绑；新客户端/旧 Runtime 组合稳定拒绝新平台。
- consumer APK/依赖图不含订阅实现；developer 普通 Provider/ChatService 链路保持一致。

## Reconsider when

- 新平台需要不同凭据所有权、任意 endpoint 或 Runtime 内置工具执行；
- 旧端拒绝 v2 无法稳定区分、影响既有 Codex 或任务对账；
- 真实平台证据要求改变数据格式、预算或生命周期边界。

## References

- [ADR-0025 Developer 订阅渠道](0025-developer-subscription-provider-channel.md)
- [ADR-0007 Runtime 生命周期](0007-companion-runtime-lifecycle.md)
- [Roadmap](../development/roadmap.md)
- [编号迁移记录](../development/m11-main-numbering.md)
