# HXA-126 合并前复核

日期：2026-09-21。所有者要求合并 `codex/hxa-126-connector-oauth`。本记录区分合并授权、源码问题及实际验收，不将已有提交数量视为交付证明。

## 当前结论

尚未将 OAuth 功能合入 main。候选原始 HEAD 为 `7c618e0e`（包含 GitHub Device Authorization 的最新提交），已无冲突合入 main `995c9baf`，形成候选 `dd40563d`。这只是 main → OAuth 的基线同步；没有反向合并、推送 OAuth 或删除工作树。

## 已确认的合并阻塞

1. **外部回调能触及 attempt 目录外的 JSON 文件。** `McpOAuthAttemptStore.consumeAttempt` 直接用回调 `state` 构造 `File(directory, "$state.json")`，读取后在解析前删除；异常分支也删除。没有 state 字符集、路径约束和存储 state 一致性检查。公开回调 Activity 将参数交给该路径。必须在任何文件访问前限制输入，并保证一次性消费的并发与落盘语义；增加路径越界、错误内容、并发重复回调回归。
2. **授权结果缺少绑定校验。** `McpOAuthCoordinator.executeCallback` 只取 query 中的 state/code，不核对回调 URI 和 issuer；attempt 不保存 resource。`ConnectorService.resolveOAuthMetadata` 对整个 URL 使用 `contains("slack.com")` / `contains("github.com")`，相似域名或 query 也会进入厂商分支，随后 testOAuth 使用原 Connector URL。必须以解析后的受支持主机/资源识别服务，并建立 issuer/resource/client/redirect 的完整凭据绑定。
3. **凭据与生命周期尚未闭环。** code verifier 直接进入普通 attempt JSON，未遵守 [OAuth ADR](../../adr/connectors/002-oauth.md) 的 SecretStore 约束；coordinator 只保存 access/refresh 字符串，没有持久化过期时间与绑定资料，实际 MCP 调用链未接 refresh。底层 refresh 的 Mutex 仅串行执行，没有复用已更新结果；不能把串行多次刷新称为 single-flight。
4. **撤销与网络边界存在不完整实现。** Slack metadata 将 `auth.test` 配置成 revocation endpoint；官方将其定义为[身份检查](https://docs.slack.dev/reference/methods/auth.test/)，撤销另有 [auth.revoke](https://docs.slack.dev/reference/methods/auth.revoke/)；UI 断开调用没有传入 endpoint，实际仅做本地清除。多处 `response.body.string()` 后才检查大小，不是有界读取；取消被部分通用 Exception 分支转换为普通错误。需分别处理真实厂商撤销状态、有界读取及协程取消，不声称当前已有这些保证。
5. **强制门禁失败。** `ConnectorSection.kt` 有 5 处硬编码中文（本次候选行 264、523、591、611、618），`./scripts/check-all.sh --all` 在 i18n 阶段 exit 1。后续 Gradle/制品步骤未执行，不能将其记为通过。

源码入口（候选分支）：`app/src/main/kotlin/com/helix/app/mcp/oauth/McpOAuthAttemptStore.kt`、`McpOAuthCoordinator.kt`，以及 `app/src/main/kotlin/com/helix/app/connector/ConnectorService.kt`。OAuth 新增源码目前只在候选分支存在。

## 验证记录

对候选 `dd40563d` 实际编译的 `McpOAuthAttemptStore` 执行隔离 Java 探针：只创建 `build/oauth-merge-review/` 下独占临时目录，将 `../unrelated` 传给 store。结果 `consumeReturnedNull=true`、`unrelatedJsonSurvived=false`，断言 exit 1，确认非 attempt JSON 在解析失败时仍被删除。未触碰设备或真实用户文件。探针以本地提交 `a80bf832` 保存于候选分支 `scripts/debug/2026-09-21/OAuthAttemptBoundaryProbe.java`；日志 `build/oauth-merge-review/path-probe.log`。

在候选工作树、共享 host slot 内运行 `./scripts/check-all.sh --all`：source 前置脚本和 docs/ADR 通过，i18n 失败，完整门禁未通过。日志为候选工作树 `build/oauth-merge-review/check-all.log`。

定向执行 `./gradlew :extensions:mcp:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest`：exit 0，Gradle 8 秒。MCP 50 项通过；consumer App 690 项通过、4 项跳过；developer App 735 项通过、4 项跳过。其中 OAuth 命名套件为 MCP 13 项、App 各 9 项，全通过；这些既有测试未覆盖上述阻塞。日志为候选工作树 `build/oauth-merge-review/targeted-tests.log`。绕过 source 仅为诊断，不替代完整门禁。

## 合并前应完成

按顺序修复回调/资源绑定与临时凭据持久化、刷新/撤销/取消、UI 资源和完整主机门禁，再补 API29/36 的回调、取消、过期、进程恢复及重放验收。同步 HXA-126/ADR 的真实实现范围；当前文档仍称未开始，不能继续与代码脱节。两家真实服务的账号、redirect、登录/只读调用/拒绝/刷新/撤销/重连仍须独立验收，缺失条件明确保留，不用 JVM fixture 关闭。

此前 199/206 验收与 CI 优化的 main 合并独立记录于[提交快检整合](ci-staged-gate-integration-2026-09-21.md)，不受本候选分支失败影响。
