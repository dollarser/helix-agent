# ADR-0025: Developer 订阅协议 Provider 渠道边界

Status: accepted
Date: 2026-09-06
HXA: HXA-130
Deciders: Project owner（明确接受已跑通的认证方式进入 developer Provider，且无需供应商授权）
Supersedes: [ADR-0024](0024-subscription-adapter-production-stop-line.md)
Superseded by: none

## Context

官方 CLI/SDK Android/bionic 打包路线已经停止，但 HXA-118/119/124～128 实现的第三方订阅协议认证
并不运行官方 CLI：OAuth grant 和模型请求位于独立 Runtime APK/UID。ADR-0024 错误地把缺少供应商公开
授权提升为所有 Provider 注册的硬门禁，并停止了 developer Provider 路线。

项目所有者澄清：已跑通的非官方认证方式可以进入 developer/Advanced Provider，无需供应商授权；用户
应在对话中像选择 API Key Provider 一样直接选择订阅 Provider。独立 APK/UID 凭据边界继续保留。

## Decision

废弃的仅是官方 CLI 认证/执行方式。已跑通的第三方订阅协议 adapter 可以在 developer/Advanced 个人
侧载渠道注册 `ModelProvider`，供应商授权不是该渠道的注册前置。它必须明确标注非官方/实验性，并披露
账号、协议漂移和停止服务风险。

consumer/store 渠道仍把供应商对 Helix 分发及消费订阅接口的可核验授权作为硬门禁。渠道差异不得影响
其他技术门禁：Android 执行形态、内置工具控制、Dispatcher/Policy/Approval/Verification/Audit、持久
jobId 取消/查询/断连对账与未知结果不重放仍必须真实完成。

UI 和上层调用统一使用现有 `ModelProvider`、`ModelRequest`、`ModelEvent`、模型列表与会话 provider/model
选择。API Key 与订阅只在 credential/network adapter 内不同。订阅 token 继续只保存在独立 Runtime
APK/UID；主 App 不接收 token。保留独立 APK 不应要求用户日常打开第二个应用，登录以外调用通过显式
签名 Binder 冷绑定，遵守 ADR-0007。

## Alternatives considered

1. 把订阅 token 合入主 APK：安装更简单，但与 Helix 的文件、浏览器和高级权限共享 UID，降低已验证的
   凭据隔离；未选择。
2. 保持 ADR-0024，完全停止 Provider：最保守，但不符合项目所有者对 developer 个人侧载能力的决定。
3. 无渠道差异地进入 consumer/store：体验统一，但会把缺少公开授权的协议放入正式分发，未选择。

## Consequences

- developer/Advanced 可以实现 Codex 等订阅 Provider；这不表示官方支持或商店可发布。
- consumer 继续不依赖订阅 adapter client，也不展示这些 Provider。
- 独立 Runtime APK 仍是安装依赖，但对话选择、模型事件和日常使用全部整合在 Helix UI。
- 当前 HXA-130 只修正门禁；跨 APK模型协议与实际 Provider 注册必须由后续 HXA 独立实现并验收。

## Verification

- 单元测试证明相同 evidence 在 developer/Advanced 不因缺少授权被拒，在 consumer/store 则被拒。
- 当前 evidence 仍因跨 APK jobId 对账等技术条件未完成而不能提前注册。
- consumer dependency graph 不包含 `runtime:cli-client`。

## Reconsider when

- 供应商明确授权或禁止该第三方集成方式；
- developer 渠道改为公开商店分发；
- Android 平台提供能维持同等凭据隔离的单 APK credential owner。

## References

- [ADR-0007 Companion Runtime 生命周期](0007-companion-runtime-lifecycle.md)
- [ADR-0021 第三方订阅协议适配器候选边界](0021-third-party-subscription-protocol-adapter.md)
- [ADR-0024 已取代的停止线](0024-subscription-adapter-production-stop-line.md)
- [Provider 与订阅账号边界](../architecture/provider-mcp-skills-modes.md)
