# ADR-CONNECTORS-002: Connector public-client OAuth

Status: accepted
Date: 2026-09-21
HXA: HXA-126
Deciders: project owner (2026-09-21 merge and bug-fix authorization)

## Context

所有者在合并前复核发现缺陷后，明确授权修复并合入 OAuth 分支。此决定接受端侧 public-client 登录设计，不把本地合并与真实服务账号验收混为一谈，也不改变既有工具授权边界。

## Decision

在现有 HTTP MCP 服务层增加独立 public-client 登录；保留原有 bearer。先合入经验证的预注册客户端切片，未交付与外部验收项继续由 HXA-126 追踪。

- 登录仅由用户在 Connector 原生页面主动发起，打开外部系统浏览器，不使用内置 WebView 登录、不读取第三方 Cookie、CLI 或其他 App token。
- 发现 resource metadata 和 authorization-server metadata，分别校验 endpoint/resource/issuer 绑定。元数据与重定向的网络请求复用现有 SSRF、响应大小、超时和重定向限制；发现不能扩大允许的网络域或工具权限。
- 本轮支持用户配置的预注册 public client；动态注册保留为 HXA-126 后续范围，尚未实现，不以 metadata 中存在 registration_endpoint 作为已支持的证明；client secret 不编进 App。HTTPS client metadata document 只有发行方拥有并正式配置域名后才启用，本次不虚构域名或自动发布文件。
- 服务明确支持时可使用 Device Authorization；设备码存 SecretStore，轮询受服务有效期、间隔和取消约束，token 仍绑定本地 server/resource/client。进程死亡不自动重放登录或恢复后台轮询。
- 使用 PKCE S256 和每次新的高熵 state。回调 URI 与实际 applicationId/登录 attempt 精确绑定；优先使用发行方已验证 App Link，尚无域名时只在服务接受的场景使用包名派生的私有 scheme，本轮为 `${applicationId}://oauth/mcp/callback`（同包也可配置 `/callback`）；consumer/developer 不争抢同一个通用 scheme。拒绝缺 state、重复回调、错误 issuer/resource、回调 URI 不一致和过期 attempt。
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

已核实当前实现、合并前文件删除复现及 Slack public-client PKCE 官方说明。测试数字与最终合并状态见 HXA-126 的任务及修复证据；没有两家真实服务的登录/刷新/撤销验收结果。

实现与合并门禁：运行 `:extensions:mcp:test`、双 flavor App JVM/构建、spotless/detekt/lintDebug/lintRelease、i18n/docs/ADR/secrets 门禁；建立 `accept-hxa-126-oauth` 专项测试，API29/36 验证错误 state/issuer/resource、取消、过期、进程死亡、重复回调、刷新竞争和断网。至少两家真实独立服务完成登录、只读 tools/call、权限拒绝、过期刷新、厂商撤销及重连；无账号时保持 HXA-126 外部验收未完成。

## Reconsider when

真实服务要求 confidential client、自有 HTTPS 域名不可用、Android 回调无法可靠恢复，或需要服务器代持/跨 UID 凭据流时，先重新评审，不能默默降级到导入第三方登录态。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
- [Slack PKCE](https://docs.slack.dev/authentication/using-pkce/)：公有客户端和自定义 scheme 条件，实际 App 仍须启用 PKCE。
- [Slack 撤销](https://docs.slack.dev/reference/methods/auth.revoke/)：服务结果与本地清除分开报告。
