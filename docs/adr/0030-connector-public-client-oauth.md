# ADR-0030: Connector 的 Android public-client OAuth

Status: proposed
Date: 2026-09-08
HXA: HXA-126
Deciders: pending
Supersedes: none
Superseded by: none

## Context

所有者授权推进 Connector 后续能力。ADR-0023 首版只接独立配置的 bearer；ADR-0029 明确 OAuth 必须独立设计。MCP 凭据由主 App SecretStore 管理，与 ADR-0021 中独立 CLI Runtime 持有的订阅 Provider 凭据不是同一种身份。

当前可作为真实验收候选的是 Notion hosted MCP 和 Atlassian Rovo MCP：前者有构建自有 OAuth/PKCE client 的官方说明，后者公开 OAuth 及组织准入配置。当前没有这两家的独立测试账号、确认过的 redirect 注册或厂商准入证据；公开支持不等于 Helix 已能登录。真实服务选择仍须验证，允许以其他满足 public-client 条件的独立服务替换候选。

## Decision

提议在现有 HTTP MCP 服务层增加独立 public-client 登录，按以下范围实现；本记录尚未批准，生产仍仅使用原有 bearer。

- 登录仅由用户在 Connector 原生页面主动发起，打开外部系统浏览器，不使用内置 WebView 登录、不读取第三方 Cookie、CLI 或其他 App token。
- 发现 resource metadata 和 authorization-server metadata，分别校验 endpoint/resource/issuer 绑定。元数据与重定向的网络请求复用现有 SSRF、响应大小、超时和重定向限制；发现不能扩大允许的网络域或工具权限。
- 首版支持用户配置的预注册 public client 与服务公开支持的动态注册；client secret 不编进 App。HTTPS client metadata document 只有发行方拥有并正式配置域名后才启用，本次不虚构域名或自动发布文件。
- 使用 PKCE S256 和每次新的高熵 state。回调 URI 与实际 applicationId/登录 attempt 精确绑定；优先使用发行方已验证 App Link，尚无域名时只在服务接受的场景使用包名派生的私有 scheme。拒绝缺 state、重复回调、错误 issuer/resource、回调 URI 不一致和过期 attempt。
- attempt 保存 serverId、issuer、resource、clientId、精确 redirect、scope 和截止时间；code verifier 与 token 进入 SecretStore，普通 journal 只存别名及非敏感状态。10 分钟 attempt TTL，收到回调后一次性消费。取消或失效后清理临时 Secret。
- refresh 为相同 issuer/resource/client 绑定上的 single-flight 操作，结果先安全落盘再切换引用。token 交换/刷新中崩溃且远端结果不明确时显示重新登录，不假设重放一定安全。回调重放不得二次兑换 code。
- access token 只发送给绑定的 MCP resource，refresh token 只发送给绑定的 token endpoint；不进入模型参数、日志、安装包、Skill 或 Connector 导出。撤销分别显示本地清除与厂商 revoke 是否成功，不能把删本地 token 冒充厂商撤销。
- OAuth 成功仍不自动启用工具；回到原有 testConnection、用户工具选择、MCP bridge、Dispatcher/Approval。运行中遇到需要新 scope 的响应只呈现登录动作，不让远端响应替用户增加 scope 或重放有副作用调用。

实现模块：extensions/mcp 的协议/状态机与 transport，app/mcp 的 SecretStore/attempt 协调与回调 Activity、原生 UI；不变更 CLI Runtime、核心 Tool Approval 或 Room schema。持久 attempt 使用 app-private 原子文件；若实现中证明需要跨库事务，须回到本记录补充设计。

## Alternatives considered

- 继续手填 bearer：保留作为兼容路径，但不能接仅支持 OAuth 的服务。
- 复制源宿主 token/client identity：不选择，无法形成 Helix 自有身份、生命周期与撤销保证。
- 后端代持 OAuth：引入项目后端、用户凭据托管和额外运维，不在本次 Android 单机范围。
- 无差别 loopback HTTP 回调：需额外监听端口和存活管理，不作为 Android 首选；仅在明确服务约束与独立设计下再评估。

## Consequences

新增一个 OAuth 状态机和回调入口，需控制配置/后台恢复成本；用户保留现有 bearer 用法。MCP OAuth 不赋予 Android 权限或 Tool Approval，也不改订阅凭据归属。服务不支持 public client、redirect 或组织未准入时明确不可连接，不能以 Web 登录成功代替 MCP 调用。

## Verification

已核实：现有 McpAppService/McpStorageBridge 的 bearer 接线，以及下列官方协议和服务说明。没有真实 OAuth 登录/刷新/撤销测试结果。

required before acceptance：所有者审查此新增凭据/回调方案。批准后实现并运行 `:extensions:mcp:test`、双 flavor App JVM/构建、spotless/detekt/lintDebug/lintRelease、i18n/docs/ADR/secrets 门禁；建立 `accept-hxa-126-oauth` 专项测试，API29/36 验证错误 state/issuer/resource、取消、过期、进程死亡、重复回调、刷新竞争和断网。至少两家真实独立服务完成登录、只读 tools/call、权限拒绝、过期刷新、厂商撤销及重连；无账号时保持 HXA-126 外部验收未完成。

## Reconsider when

真实服务要求 confidential client、自有 HTTPS 域名不可用、Android 回调无法可靠恢复，或需要服务器代持/跨 UID 凭据流时，先重新评审，不能默默降级到导入第三方登录态。

## References

- [MCP HTTP 授权规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- [Notion 自有 MCP client 指南](https://developers.notion.com/guides/mcp/build-mcp-client)
- [Atlassian Rovo MCP 准入设置](https://support.atlassian.com/security-and-access-policies/docs/control-atlassian-rovo-mcp-server-settings/)
- [ADR-0023](0023-connector-portable-bundles.md)、[ADR-0029](0029-skill-and-mcp-authoring-installation.md)
- [ADR-0021：订阅凭据边界](0021-third-party-subscription-protocol-adapter.md)
