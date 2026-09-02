# ADR-0005: Standard 与 Advanced 两级安全配置

Status: superseded
Date: 2026-08-31
HXA: HXA-020, HXA-028, HXA-033, HXA-066, HXA-093, HXA-094
Deciders: Project owner（明确要求普通用户采用严格默认，高级用户显式开放受控能力）
Supersedes: none
Superseded by: [ADR-0012](0012-capability-first-advanced-grants.md)

## Context

Helix 同时面向不熟悉 Android 权限与故障恢复的普通用户，以及理解刷机、Root、Shell 和局域网服务风险的高级用户。只用一套可自由配置的安全设置会产生两个问题：普通用户需要理解过多高风险选项，高级用户又缺少访问 All-files、Accessibility、PRoot、Root 和局域网端点的受控路径。

工程已经通过 `consumer`/`developer` product flavor 裁剪高级模块，但构建变体只决定 APK 中有哪些代码，不能替代运行时 Policy。相反，只增加运行时“高级模式”开关也不能把 developer-only 代码安全地藏在 consumer APK 中。因此发布组成和运行时安全配置必须是两个正交维度。

Provider、MCP 和 `http.fetch` 还会把用户数据发送到不同位置。OpenAI、Anthropic、自建服务、Ollama 或 SGLang 等产品名称不能证明数据驻留位置；同一模板可以指向本机、局域网或公网。数据出网策略必须依据规范化后的实际 endpoint、数据类别和用户 scope，而不是模板名称或模型自述。

## Decision

Helix 采用两个运行时安全配置：

1. `STANDARD` 是所有安装的默认配置，面向普通用户。它提供完整、保守的默认值，隐藏无关的专家参数；高敏数据出网逐次确认，高级 Android 能力默认不可用。
2. `ADVANCED` 只在包含相应代码的 `developer` 变体中出现，必须由用户在风险说明后显式开启。它可以开放 All-files、Accessibility、离线 PRoot、Root、精确局域网 origin 和有界专家设置，但每项能力仍需单独启用、限定 scope、可立即撤销。

`consumer` 变体始终运行 `STANDARD`，不得通过远程配置或隐藏开关启用 developer-only 模块。`developer` 变体默认仍运行 `STANDARD`；开启 `ADVANCED` 不自动开启任何系统权限、Runtime 或 Root session。

本 ADR 不要求两个变体都作为用户下载。当前直接分发只提供 developer 构建的一个主应用，consumer 保留为受限渠道产物；发布角色由 [ADR-0006](0006-single-direct-main-package.md)定义。

以下安全内核在两个配置中完全相同，`ADVANCED` 不得关闭或降级：

- 模型只能提出 ToolCall，不能授予 Capability、scope 或审批。
- JSON/schema 验证、参数规范化、动态风险、Policy、Approval、执行限制、变更后验证和审计管线。
- 审批绑定 tool/version/schema/参数/scope/session/execution target/transient token，且批准凭证只能消费一次；拒绝记录不能产生或消费批准凭证。
- Secret、密码、Cookie、认证码和 Provider/CLI token 不进入模型、MCP、生成代码、日志或普通审计正文。
- QuickJS isolated process、PRoot/CLI 独立 UID、signature IPC 和主进程不执行生成代码等隔离边界。
- 支付、认证、系统授权、安装器、Root 管理器、刷写系统分区、关闭 SELinux、提取其他 App 凭据等禁止项。
- 新 origin、数据类别、scope、目标 App、页面/UI generation、代码、命令或执行目标使旧授权失效。
- 用户可见的停止、撤销、审计、超时、取消和不明确副作用恢复规则。

数据发送按实际 endpoint 分类为 `ON_DEVICE_LOOPBACK`、`USER_AUTHORIZED_LAN`、`PUBLIC_CLOUD` 或 `CUSTOM_REMOTE_UNKNOWN`。该分类描述数据去向，不代表端点可信；自建、Ollama、SGLang、局域网和 loopback 都不能跳过输入限制、认证保护或出网 Policy。

数据至少分为普通内容、高敏内容和禁止发送内容：

- 禁止发送内容包括 API key、OAuth token、Cookie、密码、验证码、认证字段和其他凭据；两个配置都拒绝发送。
- `STANDARD` 将联系人、通知正文、精确位置、文件正文、浏览器页面内容、Accessibility 内容等高敏数据逐次展示“数据类别 + Provider/MCP + origin + scope”并确认，不提供永久允许。
- `ADVANCED` 可以保存高敏数据规则，但规则必须绑定稳定 Provider/MCP ID、规范 origin、数据类别、scope 和有效期，可查看、立即撤销；新 origin 或类别仍重新确认。首版只提供 `1 小时 / 24 小时 / 7 天 / 30 天` 固定期限，默认 24 小时、硬上限 30 天，不允许“永久”或自定义更长值，也不因再次使用滑动续期。记录必须保存 `createdAt/expiresAt`；当前时间早于 `createdAt`（时钟回拨）或不早于 `expiresAt` 时 fail closed，回到逐次确认。

网络端点在解析、重定向和建连时执行同一 SSRF Policy。`STANDARD` 的通用 `http.fetch` 只允许公网 HTTP(S) 目标；`ADVANCED` 只有在用户从设置中创建精确 `NetworkOriginScope` 后才能访问指定 LAN/loopback host + port。云 metadata 和平台保留端点始终拒绝。PRoot Runtime 在 `ADVANCED` 中仍保持无 `INTERNET`；未来若需要联网 Shell，必须使用新的执行域和取代或补充 ADR，不能增加一个关闭离线边界的开关。

## Alternatives considered

1. **只按 consumer/developer 区分用户**：构建边界清晰，但 developer 安装后会默认暴露过多配置和能力，也无法表达“developer APK 以普通安全默认运行”。未选择。
2. **只使用一个运行时高级模式开关**：实现简单，但会诱使实现者把高级代码打入 consumer，再依赖 UI 隐藏；也容易把一个总开关误作所有权限的授权。未选择。
3. **完全固定单一严格策略**：普通用户最安全，但无法满足 Root、PRoot、局域网和跨 App 自动化等已在范围内的高级场景。未选择。
4. **Advanced 允许关闭 Policy/审批/审计**：灵活性最高，但会把能力扩大变成安全内核绕过，模型或不可信内容可直接使用真实手机权限。明确拒绝。

## Consequences

- 收益：普通用户获得低配置量和安全默认；高级用户获得明确、可撤销、按能力分开的扩展路径。
- 收益：变体裁剪、运行时 Policy 和系统权限各自承担清晰职责，小模型不能把三者混成一个布尔值。
- 代价：Provider 配置、权限中心、Policy、审批 UI 和测试矩阵必须同时覆盖 profile、endpoint residence 和数据类别。
- 代价：`developer` 变体需要维护 Standard 默认状态及 Advanced 启用后的组合测试。
- 约束：安全配置不是 ToolCall 参数，模型、MCP annotation、Skill 或导入内容不能切换它。
- 约束：本 ADR 只接受产品与架构边界，不表示任何后续 Provider、Policy、PRoot、Accessibility 或 Root 功能已经实现。

## Verification

本决定的当前证据：

- 项目所有者明确要求普通用户采用严格、低配置量的默认边界，并为理解 Root/刷机风险的高级用户开放受控能力。
- `consumer`/`developer` 的编译期裁剪已经由 M0 变体门禁验证；这只证明构建边界，不证明本 ADR 的运行时 profile 已实现。

后续 HXA 验收必须证明：

- consumer APK 不包含或暴露 Advanced-only 模块和入口；developer 首次启动仍为 `STANDARD`。
- 切换 `ADVANCED` 不自动授予 All-files/Accessibility/Root，不安装 Runtime，不产生网络请求。
- 高敏数据在 `STANDARD` 下没有永久允许路径；`ADVANCED` 规则精确绑定并可撤销。
- Advanced 高敏规则默认 24 小时、最大 30 天，时钟回拨/到期/重启后不会延长；续期必须是新的显式用户动作。
- Provider/MCP residence 根据规范化实际 endpoint 判定，模板名称不能降级提示或 Policy。
- SSRF 测试覆盖 A/AAAA、IPv4-mapped IPv6、DNS rebinding、重定向、连接地址复验和 metadata 拒绝。
- `DENIED` 决定不能生成或消费 Approval Proof；并发仅一个已批准凭证消费成功。
- PRoot Runtime APK 在 Advanced 路径中仍无 `INTERNET`。

## Reconsider when

- 用户研究证明两级模型仍使普通用户无法理解关键数据发送或审批提示。
- Android 权限、应用商店政策或 Runtime 隔离原语发生变化，使当前变体/UID 边界不可行。
- 有经过威胁建模和真机验证的新执行域，可以在不接触主 App 数据与凭据的前提下提供联网 Shell。
- 固定评测证明某类高敏数据逐次确认造成严重审批疲劳，且已有更安全的可撤销授权表达。

## References

- [产品需求](../product/requirements.md)
- [总体架构](../architecture/overview.md)
- [安全、测试与发布门禁](../security/testing-and-release.md)
- [Android 平台能力](../architecture/android-platform-capabilities.md)
- [Provider、MCP、Skills 与模式方案](../architecture/provider-mcp-skills-modes.md)
- [ADR-0006：单一直接分发主应用](0006-single-direct-main-package.md)
