# HXA-125 真实来源与服务验收进展

日期：2026-09-05。M13 / HXA-125：in progress，未完成。独立分支 `codex/connector-portability`，不合入 main、不推送。

## 决定与范围

所有者明确要求“接受 adr-0023 并开始后续工作”，[ADR-0023](../adr/0023-connector-portable-bundles.md) 已标记 accepted。接受范围仍为 HXA-124 首版 MCP endpoint + Skill snapshot 组合、独立 bearer 和现有执行授权管线；OAuth/CLI/市场不是此次接受的附带能力。

## 已执行证据

```bash
python3 scripts/fetch-hxa-125-samples.py
HELIX_CONNECTOR_ACCEPTANCE_DIR="$PWD/app/build/outputs/hxa-125-samples" ./gradlew :app:testConsumerDebugUnitTest --tests "com.helix.app.connector.ConnectorExternalAcceptanceTest" --rerun --no-configuration-cache
```

两条命令 exit 0。新专项测试 2 项，failures/errors/skipped 均为 0。实际执行生产 `ConnectorPackageReader` 和 `McpClients.sdk`，没有 mock 远端服务。测试 XML 位于 `app/build/test-results/testConsumerDebugUnitTest/TEST-com.helix.app.connector.ConnectorExternalAcceptanceTest.xml`。常规离线测试未设置环境变量时显式跳过这两项，不会自动联网；外部复跑需 `--rerun` 避免 Gradle 缓存被误当新证据。

- 真实配置：Anthropic 官方插件仓库的 Linear HTTP 可解析；GitHub bearer 模板被识别，模板值不进入导入端点；Playwright stdio 明确诊断为需要 Android Runtime。
- 真实 manifest：Cloudflare 官方 Skills 仓库的 Codex/Claude manifest 分别与原始 MCP JSON 组合，均解析到其 API endpoint。此次没有下载/执行整个 Skill，不能声称 Skill 业务完成。
- 匿名服务：连接 `https://docs.mcp.cloudflare.com/mcp`，协商 `2025-11-25`，发现 2 个工具；调用 `search_cloudflare_documentation`，查询固定的公开文档短语，返回非错误、非空文本。未发送工作区内容或账号凭据。
- 多 host 限制：同时包含 `.codex-plugin/plugin.json` 与 `.claude-plugin/plugin.json` 的包当前报 `CONNECTOR_AMBIGUOUS_MANIFEST`。本测试明确验证这一边界；当前需先选定 host 再打包，未声称完整仓库 ZIP 可迁移。host 选择 UI/打包体验改进待生命周期任务评估。

上述是桌面 JVM 的生产 reader/SDK 外部证据，尚不是 Android ConnectorService、Dispatcher/Policy 全链路、独立 bearer、平台登录或端到端任务验收。首次调用不覆盖撤销、会话重启或商用服务权益。

收尾门禁：`./gradlew spotlessCheck detekt --no-configuration-cache`、`./scripts/check-docs.sh`、`./scripts/verify-adr.sh`、`./scripts/check-i18n.sh`、`./scripts/check-lockfiles.sh`、`./scripts/check-secrets.sh`、`git diff --check` 均 exit 0。

## 样本来源与复现

只下载公开 manifest/MCP JSON 到忽略的 build 输出；固定上游 commit 与 SHA-256，脚本对不匹配内容失败，不将第三方源码或配置正文纳入产品。样本清单位于 `app/build/outputs/hxa-125-samples/manifest.json`。

| 样本 | 固定来源 | SHA-256 |
| --- | --- | --- |
| `anthropic-linear.json` | [原始文件](https://raw.githubusercontent.com/anthropics/claude-plugins-official/85cce0381e7860082641b59d961a2b8c368b8b79/external_plugins/linear/.mcp.json) | `60bc954e5c2018171f5efa358ddbfa6062a63b456f8d7fa567fd926910030e9f` |
| `anthropic-github.json` | [原始文件](https://raw.githubusercontent.com/anthropics/claude-plugins-official/85cce0381e7860082641b59d961a2b8c368b8b79/external_plugins/github/.mcp.json) | `b536ea03380d2f2f93f31c87a158e21e74cce5f664d20f7bdb81c6710418e06d` |
| `anthropic-playwright.json` | [原始文件](https://raw.githubusercontent.com/anthropics/claude-plugins-official/85cce0381e7860082641b59d961a2b8c368b8b79/external_plugins/playwright/.mcp.json) | `b6fd7e9ccee1682af353854195516826da6970026b68c86ad800ff4d931d3250` |
| `cloudflare-mcp.json` | [原始文件](https://raw.githubusercontent.com/cloudflare/skills/b8aeca6d7e2d614d7bd0e5220c8dd7645fe58a93/.mcp.json) | `6608aeaa3ce8be52077c96271b9da647683f12f42c51035d1107e5915194b690` |
| `cloudflare-codex.json` | [原始文件](https://raw.githubusercontent.com/cloudflare/skills/b8aeca6d7e2d614d7bd0e5220c8dd7645fe58a93/.codex-plugin/plugin.json) | `0bad09fc15347fc5e9e918e734ce12b2a149584eb61826a1e068f3cb9526379f` |
| `cloudflare-claude.json` | [原始文件](https://raw.githubusercontent.com/cloudflare/skills/b8aeca6d7e2d614d7bd0e5220c8dd7645fe58a93/.claude-plugin/plugin.json) | `43ed8be9f2942a7fba98501bc47e37fcf337cf57707b0912d593d8e85fcbafcf` |

公开服务 endpoint 依据 [Cloudflare 官方 MCP 服务列表](https://developers.cloudflare.com/agents/model-context-protocol/cloudflare/servers-for-cloudflare/)；配置结构参照 [Claude 插件规范](https://code.claude.com/docs/en/plugins-reference)。来源配置解析与公共文档服务连接是两个独立验收维度，不能相互替代。

## 剩余门禁

| 项目 | 状态 / 下一步 |
| --- | --- |
| Codex/Claude 来源格式 | 上述真实 MCP/manifest 子集通过；完整插件、Skill 工具名和脚本依赖仍需具体样本 |
| WorkBuddy/QwenWork | 仍仅 HXA-124 调研 fixture；等待实际导出样本与对应平台版本 |
| 受保护服务 | 等待独立测试账号/服务选择；token 只在 Helix SecretStore 中配置，不通过聊天或 fixture 保存 |
| Android 真实服务 | 待显式隔离设备上的导入、连接、Dispatcher 调用、关闭与跨进程恢复专项验收；本轮未安装 APK |
| 拒绝/撤销 | 待真实服务的无效凭据、权限拒绝、厂商撤销、重新连接结果；不得用匿名服务推导 bearer 通过 |

HXA-125 保持 in progress，不创建完成记录、不推进依赖它的 HXA-126。HXA-127～130 尚未开始。
