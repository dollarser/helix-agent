# ADR-0024: 订阅协议适配器生产接入停止线

Status: accepted
Date: 2026-09-06
HXA: HXA-129
Deciders: Project owner（明确指出 CLI 路线已废弃，并要求按当前证据完成剩余收口）
Supersedes: none
Superseded by: none

## Context

[ADR-0020](0020-cli-runtime-execution-base.md) 和 HXA-111/112 已停止官方 Codex/Claude CLI/SDK 的
Android/bionic 生产打包路线。[ADR-0021](0021-third-party-subscription-protocol-adapter.md) 随后允许明确
标注非官方的第三方订阅协议 adapter 在独立 Runtime UID 中获得并持有自己的 OAuth grant，但把完整
Dispatcher/Policy/Approval/Verification/Audit 与 jobId 对账列作未来生产接入工作。

HXA-118/119/124～128 已证明部分登录生命周期、Codex 真实极小订阅调用和 Runtime 私有 journal；这些
是个人侧载可行性证据，不是服务商授权。当前没有 OpenAI、Anthropic、xAI 或 GitHub/Microsoft 对 Helix
复用对应消费订阅 OAuth identity、私有/内部 endpoint 并向用户分发该能力的可核验授权。继续实现跨 APK
模型 Job 会扩大对未公开协议的产品依赖，并容易把“技术上成功”误报为“可发布后端”。

## Decision

当前停止两条生产路线：不再打包官方 CLI/SDK Android 执行底座；不再把第三方订阅协议 adapter 的
登录、固定 smoke 或私有 journal 延伸为 Helix Provider、Tool、Chat/Act/Goal 或跨 APK 模型 Job。

历史 `cli-app` module 和 `com.helix.runtime.cli` applicationId 暂时保留，以免破坏已安装实验 APK 及其
Runtime 私有 vault；产品语义统一为 developer/Advanced 个人侧载的“第三方订阅协议适配器实验”。它只
允许用户可见登录、重新认证、退出和固定无工具 smoke，不接收任意 prompt 或 Workspace 数据。

任何未来注册必须同时具备厂商支持的目标执行形态、内置工具受控、按原 jobId 对账且不重放，以及供应商
对 Helix 分发和消费订阅接口的可核验授权。公开 client id、协议可访问、真实账号成功或用户自担风险均不
替代授权门禁。重开生产接入须建立新 HXA；若改变本停止线，须新增取代 ADR 并由项目所有者明确接受。

本决定不改变 ADR-0021 的凭据隔离规则：实验 token 仍只属于独立 Runtime UID，主 App 不接收、读取或
复制 token，也不得导入浏览器或其他 App/CLI 的 credential。

## Alternatives considered

1. 立即实现 Binder/PFD、Dispatcher 和 Provider：能扩大功能，但在供应商授权缺失时只会扩大不可发布
   面和账号/协议风险，未选择。
2. 删除整个实验 APK 和 vault：边界最简单，但会丢失用户已完成的个人实验登录，也抹去可复现研究入口；
   当前没有必要执行破坏性迁移，未选择。
3. 保留“待实现 Provider”的模糊状态：改动最少，但会持续把 HXA-128 journal 误解为产品接线前置，未选择。
4. 使用项目自有 API key Provider：仓库已有受支持的 Provider 路线，不消费 ChatGPT/Claude 等消费者订阅；
   这是当前正式模型接入方式，但不是本实验的替代实现。

## Consequences

- M11A 以有界 developer 实验收口，而不是以订阅 Provider 交付收口。
- `agentBackendState` 必须保持 `NOT_REGISTERED`；consumer artifact 不依赖 `runtime:cli-client`。
- HXA-128 journal 继续仅为固定 smoke 提供取消、恢复和不重放证据，不新增跨 APK transaction。
- 历史 CLI 命名仍存在于 module/applicationId，可能造成理解成本；规范和 UI 必须避免宣称官方 CLI。
- Claude/Grok 付费资格未核实不再阻塞收口，也不能被写成正向支持。

## Verification

- `CliAgentBackendEligibility` 把供应商分发授权作为独立 fail-closed gate；单元测试证明其他三项均满足时，
  授权缺失仍不能注册。
- developer App 测试证明当前 evidence 不可注册，Provider protocol/catalog 没有 CLI template。
- consumer dependency graph 不含 `runtime:cli-client`。
- `check-cli-runtime-boundary.sh` 证明 CLI Runtime wire 没有模型 Job transaction，且固定 smoke 仍无任意 prompt。
- HXA-127/128 的真实 Codex smoke 和 journal 证据只支持个人实验可行性，不被提升为授权证据。

## Reconsider when

- 供应商发布允许第三方 Android 产品使用消费者订阅的公开 OAuth/API、分发条款和稳定协议；或
- 供应商提供受支持的 Android SDK/CLI 与项目自有 OAuth application 注册方式；并且
- 新 HXA 完成 Dispatcher/Policy/Approval/Verification/Audit、jobId 恢复和 API 29/36/真机发布门禁。

## References

- [ADR-0007 Companion Runtime 生命周期](0007-companion-runtime-lifecycle.md)
- [ADR-0020 CLI Runtime 执行底座候选](0020-cli-runtime-execution-base.md)
- [ADR-0021 第三方订阅协议适配器候选边界](0021-third-party-subscription-protocol-adapter.md)
- [Provider 与订阅账号边界](../architecture/provider-mcp-skills-modes.md)
- [HXA-129 完成记录](../completion-records/HXA-129.md)
