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

上述首轮结果是桌面 JVM 的生产 reader/SDK 外部证据；下文单独记录随后执行的 Android 匿名服务链路。两者均不代表独立 bearer、平台登录或商用服务权益验收。

收尾门禁：`./gradlew spotlessCheck detekt --no-configuration-cache`、`./scripts/check-docs.sh`、`./scripts/verify-adr.sh`、`./scripts/check-i18n.sh`、`./scripts/check-lockfiles.sh`、`./scripts/check-secrets.sh`、`git diff --check` 均 exit 0。

## Android 模拟器验收（2026-09-05）

所有者明确要求使用模拟器测试。使用本任务新建的独立 API 29/36 arm64-v8a AVD，显式 serial 为 5580/5582；其他 worktree 的 5554/5556 未安装、清数据或强停。完成后仅关闭并删除本任务两台临时 AVD，其他设备保持运行。

初次两台均握手成功，但工具注册失败：`unknown keyword '$schema' (not in the tool schema subset)`。远端搜索 schema 的根声明为 `https://json-schema.org/draft/2020-12/schema`，其余为 `object`、`properties.query.type=string` 和 `required=[query]`，均在已有子集中。桌面 SDK smoke 不经过注册，未能暴露此缺陷。

修复位于 app 的 `McpToolSchemaAdapter`，由 `McpAppService.enable` 调用：仅转换上述已知根 dialect 声明，并立即检查剩余 schema 完全属于既有 ToolSchema 子集；不删除其他关键字、不递归删除嵌套声明，不支持的 dialect、`$ref`、`unevaluatedProperties` 仍拒绝。远端 `schemaHash` 保留，原始 handshake metadata 仍持久化，故远端 schema 变化仍参与来源/审批绑定。依据 [JSON Schema dialect 规范](https://json-schema.org/understanding-json-schema/reference/schema)，`$schema` 声明方言，不能把任意未知方言当作注释移除。

决策记录：沿用 accepted [ADR-0023](../adr/0023-connector-portable-bundles.md) 的既有执行管线适配；未改变 core schema 子集、审批、凭据或 Runtime 契约，无新增 ADR。

执行命令：

```bash
./gradlew :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest --no-configuration-cache
./scripts/accept-hxa-125-connectors.sh emulator-5580
./scripts/accept-hxa-125-connectors.sh emulator-5582
./scripts/accept-hxa-124-connectors.sh emulator-5580
./scripts/accept-hxa-124-connectors.sh emulator-5582
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :extensions:mcp:test :app:assembleDeveloperDebug --no-configuration-cache
./gradlew spotlessCheck detekt --no-configuration-cache
```

最终上述命令均 exit 0；质量与文档门禁通过。

| 设备 | HXA-125 seed + recover | 新进程 PID | HXA-124 回归 |
| --- | --- | --- | --- |
| API 29 arm64-v8a | 1/1 + 1/1 | 5770 → 5853 | 6/6 + 1/1 + 1/1 |
| API 36 arm64-v8a | 1/1 + 1/1 | 5584 → 5685 | 6/6 + 1/1 + 1/1 |

| 本轮产物 | SHA-256 |
| --- | --- |
| consumer debug APK | `ee5004139e6c2279d2c0408fe22f199b81061ba2aa4521187bb3f277ab50d840` |
| developer debug APK | `d35e6cbda2129ff5016088021ce7c1344950eaabcbaeed25c69ad687f1359dd9` |
| consumer AndroidTest APK | `5761ef004c3f85d22230252a5a34939013f9e6fe75b3f84f71f1ccf497f2b080` |

HXA-125 两阶段测试明确传入 `connectorExternal=true`：第一阶段从 ContentResolver 导入公共配置，通过真实 `ConnectorService/McpAppService` 握手及注册，只选择文档搜索；通过生产 `ChatService → Scheduler/Dispatcher → Policy → StorageApprovalBroker → MCP runtime → audit` 调用，测试驱动经现有 ChatService 的批准/拒绝动作，不使用 mock transport 或伪造 proof。核对 DENIED 不消费、无发送；APPROVED 消费、有一次发送、COMPLETED 与审计；停用使 Registry 移除且捕获的旧 executor 拒绝。第二阶段在 force-stop 后新 PID 中检查配置恢复、远端工具未自动激活，再由显式连接启用后调用成功，最后移除连接器。

生产 schema 适配新增两项 JVM 回归覆盖必填参数仍校验、来源 hash 保留、未知方言/约束及嵌套声明仍拒绝。两 flavor 离线 JVM 与 MCP 合计 574 项：570 通过、4 项预期跳过（两 flavor 各 2 项外部联网 opt-in 测试），无失败或错误。

原始日志在 `app/build/outputs/hxa-125-device/`，保留 `before-schema-fix` 初始失败日志、最终两阶段 instrument 日志及仅含协议/PID 的 logcat 摘要。HXA-124 回归日志位于 `app/build/outputs/connector-acceptance/`。模拟器设备验收为 consumer 的共用实现，developer 本轮仅构建/JVM 验证。此测试不经过 LLM 自动选工具或手工 UI 点击，不承诺完整 agent 任务成功。

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
| Android 真实服务 | API 29/36 专用模拟器已执行匿名服务真实链路；不代表受保护服务登录或 OEM 后台验收 |
| 拒绝/撤销 | 待真实服务的无效凭据、权限拒绝、厂商撤销、重新连接结果；不得用匿名服务推导 bearer 通过 |

HXA-125 保持 in progress，不创建完成记录、不推进依赖它的 HXA-126。HXA-127～130 尚未开始。
