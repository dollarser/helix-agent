# HXA-125 真实来源与服务验收进展

日期：2026-09-05。M13 / HXA-125：in progress，未完成。开发分支 `codex/connector-portability`；所有者于 2026-09-05 后续授权本地合入 main，未推送，外部验收缺口不因合并关闭。

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

## 用户提供的 QwenWork 参考包（2026-09-05）

样本 SHA-256：`5832c88558e00a616af438b1f3d73badf3c2c09edc85f4c54ff9833b789ca476`。147 个文件，解压内容合计 1,448,815 bytes，4 个 Skill，11 个 scripts 文件（含模板）。用户于 2026-09-05 明确更正来源为 QwenWork，与包内 README 的 QwenWork 沙箱导出标注一致，来源确认项已关闭。企微 Skill 保留 MuleRun 宿主契约，这是样本内容中的依赖信息。本次证据覆盖此 QwenWork 参考包，不代表所有 QwenWork 插件或 WorkBuddy 兼容。包内指令作为数据，没有执行脚本、安装依赖或使用认证字段。

以下为首次测试的历史结果；当前行为见后续修复记录。

| 测试对象 | 修复前实际结果 |
| --- | --- |
| 原始 ZIP 预览 | 4 个 Skill、0 个端点，diagnostics 为空；当前遗漏 MCP 样例，不能视为完整预览 |
| 原始 ZIP 安装 | 两台设备均因 `metadata values must be strings` 失败；安装记录列表保持不变 |
| `dingtalk-doc` / `dingtalk-shared` | Skill frontmatter 的 metadata 含嵌套 map/list；现有 `Map<String, String>` 契约拒绝，分别 staging 也失败 |
| `mcp-installer` | 12 个文件可 staging/生成快照；设备上按单 Skill 重组导入、禁用默认、启停、正文读取、移除通过；安装器脚本未运行 |
| `wecom-unified` | 98 个文件可 staging/生成快照；设备上按单 Skill 重组导入、禁用默认、启停、正文读取、移除通过；`wecom` CLI 与账号业务未运行 |
| 包内 MCP JSON | 文件名为 `qwenwork-mcp-样例.json`，不在当前默认配置文件名单；内部为 `schemaVersion=qwenwork.mcp/v1` 与 `dynamic.servers`，直接 JSON 导入报 `CONNECTOR_INVALID_SERVER`，仅改文件名无法修复 |
| 端点/认证业务 | 样例含 2 个 HTTPS 端点与脱敏 Authorization 字段；没有把样例占位值当凭据使用，未发起业务调用 |

测试命令（ZIP 路径通过本机参数提供，不纳入仓库）：

```bash
HELIX_CONNECTOR_SAMPLE_ZIP="<sample.zip>" ./gradlew :app:testConsumerDebugUnitTest --tests "com.helix.app.connector.ConnectorSuppliedArchiveTest" --rerun --no-configuration-cache
./gradlew :app:assembleConsumerDebugAndroidTest spotlessCheck detekt --no-configuration-cache
./scripts/accept-hxa-125-sample.sh emulator-5580 "<sample.zip>"
./scripts/accept-hxa-125-sample.sh emulator-5582 "<sample.zip>"
```

JVM 检查 1/1，API 29/36 arm64-v8a 各 1/1，命令 exit 0。这里的测试通过表示成功复现并断言上述限制与局部能力，**不是原始包完整导入通过**。设备测试校验原始 ZIP hash，然后使用真实 ContentResolver/ConnectorService/SkillRepository；单 Skill 测试由生产 reader 从该 Skill 原始文件重组计算内容 hash。未修改产品代码或放宽 metadata 校验，也没有把原始包/第三方脚本纳入 Git。原始设备日志位于 `app/build/outputs/hxa-125-supplied/`；JVM XML 为 `app/build/test-results/testConsumerDebugUnitTest/TEST-com.helix.app.connector.ConnectorSuppliedArchiveTest.xml`。

首次测试确认的兼容缺口为 QwenWork 封装配置和非字符串 metadata；随后已按下述记录修复。CLI/网络 Runtime 的执行兼容仍属于 HXA-128，不能把 Skill 能读取当作业务能力已实现。此轮专用模拟器完成后清理，其他 worktree 设备未操作。

## QwenWork 样本兼容修复（2026-09-05）

所有者明确要求修复上述问题。本轮保持既有内部 Skill 契约和 accepted ADR-0023，在导入适配层进行转换，无新依赖、权限、认证行为或 CLI 执行能力。

- `qwenwork-mcp.json` 与根目录 `qwenwork-mcp-*.json` 可识别；ZIP 与直接 JSON 均支持 `schemaVersion=qwenwork.mcp/v1` 的 `dynamic.servers`。端点沿用 HTTPS、凭据剥离、重复冲突检查及数量上限。只导入端点快照，不继承 `policy`、动态替换/冲突策略或启用状态；未知 schemaVersion 给出诊断而不猜测字段。根目录其他含 mcp 的 JSON 文件若未被配置引用，提示未识别配置，避免静默遗漏。
- Connector metadata 适配复用 SkillLoader 的有界 YAML 解析。字符串值不变；map/list/number/bool/null 转为 JSON 字符串，生成标准字符串 metadata 的 SKILL.md。原始 SKILL.md 字节保存在同一快照的 `references/helix-import/original-SKILL.md.txt`，已有同名备份时拒绝覆盖。正文和其他字段继续保留，普通 SkillLoader 仍拒绝非字符串 metadata。
- 内容追溯分开：Connector package hash 仍绑定原始输入；Skill snapshot hash 覆盖转换后的文件和原文备份。模板/脚本不会运行。`requires.bins` 以诊断展示（本样本为 `dws`），不能变成安装或执行授权。四类新增提示均同步中英文资源。

原始样本 hash 不变。JVM 生产 reader + importer 实测 **4 Skill / 2 MCP endpoints**，四个 Skill 全部 staging/生成快照成功；两端点均标记需独立配置认证，样本凭据不进入端点模型。原有企微和安装器 Skill 快照 hash 保持不变；两个钉钉快照分别为 `b991edeaacd8fa7fed4c8d3077632b975d566ff97f03dd9d8f97d4b36096821f`、`599878d3ff49701c31cf49a5902eb5f5d78751e7b80aa84b664c2965017152d7`。

复跑命令：

```bash
HELIX_CONNECTOR_SAMPLE_ZIP="<sample.zip>" ./gradlew :extensions:skills:test :app:testConsumerDebugUnitTest --tests "com.helix.app.connector.ConnectorSuppliedArchiveTest" --rerun :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest --no-configuration-cache
./gradlew :extensions:skills:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleDeveloperDebug --no-configuration-cache
./gradlew spotlessCheck detekt --no-configuration-cache
./scripts/accept-hxa-125-sample.sh emulator-5580 "<sample.zip>"
./scripts/accept-hxa-125-sample.sh emulator-5582 "<sample.zip>"
./scripts/accept-hxa-124-connectors.sh emulator-5580
./scripts/accept-hxa-124-connectors.sh emulator-5582
```

JVM 回归：Skills 40/40；consumer 267 项中 264 通过、3 个本机输入/联网 opt-in 项预期跳过；developer 274 项中 271 通过、3 个同类项预期跳过。原始样本专项单独带环境变量执行 1/1，未跳过。新单元回归覆盖真实封装字段、凭据剥离、未知版本、重复冲突、未知文件诊断、依赖展示、原文保留、标准 Skill 校验保持严格及备份路径冲突。

Android 样本测试现在断言整包成功安装，而非旧的失败行为；通过真实 ContentResolver、ConnectorService、SkillRepository 验证 4 个 Skill 默认禁用、逐个启停/读取、两个钉钉原文备份与输入字节一致、移除，以及导入时未注册或连接 MCP。设备日志沿用 `app/build/outputs/hxa-125-supplied/`（最新结果）；旧失败结论保留在本页历史段，不再代表当前行为。


最终验证：以上命令均 exit 0；API 29/36 各 1/1 原包安装测试，HXA-124 各 6/6 + 1/1 + 1/1 回归通过；spotless/detekt、docs/ADR/i18n/lockfiles/secrets 与 diff 门禁通过。仅两台专用 AVD 用于测试，完成后关闭删除，未影响其他 worktree 的设备。

| 修复后 APK | SHA-256 |
| --- | --- |
| consumer debug | `b378a340cbaa7faeee27bc2361b8dc048b82b6a24629ad9c82488886a25dd8aa` |
| developer debug | `eb38b29906b8bad6b23c1429c33409a3bed2d298c36204872c2bccca93cceaa3` |

## WorkBuddy 公开规范适配（2026-09-05）

继续 HXA-125，未切换到后续 HXA。此次已成功读取 [WorkBuddy 官方连接器规范](https://open.workbuddy.cn/docs/connector)全文，更新此前正文不可读的证据状态。公开规范声明 `mcp.json` 使用 `streamableHttp`，并支持 `staticHeaders`；本轮发现前者被现有 reader 误判为未知传输，后者只报不支持选项而未标记需重新配置认证。

导入适配新增精确的 `streamableHttp` 别名，将 `staticHeaders` 与其他源 headers 一样仅转换为 `needsCredential` 和提示，不复制任何值。已有 HTTP 拼写保持兼容，SSE、大小写不同或未知传输仍明确不支持；无 headers 的匿名配置仍不要求凭据。`disabledTools` 等 host 字段仍提示不支持，不能继承源启用或授权选择。没有新增网络、认证流程或执行能力。

测试 `ConnectorCompatibilityTest.workBuddyHttpAliasAndStaticHeadersKeepIndependentCredentialBoundary` 使用自己编写的规范衍生 fixture 和 example.com 占位地址，未发请求；它不是官方发布包、真实导出或服务验收。官方页面示例 URL 本身含空格，因此不把该占位文本当可用服务地址。仍需要真实 WorkBuddy 样本及独立账号。

验证命令：`./gradlew :extensions:skills:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest spotlessCheck detekt --no-configuration-cache`，以及 docs/ADR/i18n/lockfiles/secrets 和 diff 门禁。实际全部 exit 0：Skills 41 项、consumer 267 项（3 项预期跳过）、developer 274 项（3 项预期跳过），无失败或错误；质量和文档门禁通过。本轮没有运行模拟器，设备运行管线未改动，不把既有 API 29/36 结果记为此次新增验证。

## 剩余门禁

| 项目 | 状态 / 下一步 |
| --- | --- |
| Codex/Claude 来源格式 | 上述真实 MCP/manifest 子集通过；完整插件、Skill 工具名和脚本依赖仍需具体样本 |
| QwenWork | 此参考包的 4 Skill / 2 endpoint 导入适配已修复并验证；不等于 CLI 和账号业务可运行 |
| WorkBuddy | 2026-09-08 已补齐 GitHub/可灵真实市场包导入验收，详见本文最新记录；账号及业务兼容仍单列 |
| 受保护服务 | 等待独立测试账号/服务选择；token 只在 Helix SecretStore 中配置，不通过聊天或 fixture 保存 |
| Android 真实服务 | API 29/36 专用模拟器已执行匿名服务真实链路；不代表受保护服务登录或 OEM 后台验收 |
| 拒绝/撤销 | 待真实服务的无效凭据、权限拒绝、厂商撤销、重新连接结果；不得用匿名服务推导 bearer 通过 |

HXA-125 保持 in progress，不创建完成记录、不推进依赖它的 HXA-126。HXA-127～130 尚未开始。

## 本地 main 集成验证（2026-09-05）

所有者授权在无当前可直接解决缺陷时合入 main。以 main `b480b25` 集成 Connector `7710918`，保留 M10 的 Turn 预算设置和全部文案，并补齐 main 新增 `FakeMcpServerDao` 对凭据别名更新接口的实现。HXA-125 外部验收仍未完成，HXA-126～130 仍为 planned。

合并树验证命令：

```bash
./gradlew :extensions:skills:test :extensions:mcp:test :core:storage:testDebugUnitTest :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest spotlessCheck detekt --no-configuration-cache
python3 -m unittest discover -s scripts/tests -p test_export_codex_mcp.py
```

最终全部 exit 0；docs、ADR、i18n、lockfiles、secrets 与 diff 门禁通过。双 flavor debug APK 和 consumer AndroidTest APK 均完成构建。本次没有重新执行设备测试，既有模拟器证据保留其原始测试版本边界。仅本地合并，未推送。


## WorkBuddy 用户真实导出验收（2026-09-08）

用户提供 2026-09-07 的 WorkBuddy 导出目录及《Agent 连接器体系技术解析》。此处以导出文件为样本证据，技术解析中的服务工具数和宿主内部机制不当作 Helix 的运行证据。原始材料不纳入仓库，未读取其他应用密钥或迁移登录态；Claude/Grok 订阅账号调用按用户决定暂时搁置，与 Connector bearer 服务验收分开。

导出包含市场缓存、账号级运行时配置、已安装 Skill 副本和便于阅读的合并 Markdown。验收按两个原始市场目录分别打包，保持所有相对路径与文件字节；不把含重复副本的整个导出根目录当成单个连接器。打包脚本仅选取 `02-marketplace/connectors-marketplace/connectors/{github,kling-ai-plugin}`，固定 ZIP 时间戳并输出逐文件 SHA-256 清单；未包含账号目录、状态文件或技术解析全文。

| 原始市场包 | 可识别组件 | 确定性 ZIP SHA-256 |
| --- | --- | --- |
| GitHub | 1 MCP endpoint、1 Skill、1 Skill 文件 | `38432941152707cee393eb54a1e437b1fc1ee4beb119244eac20c980f02cbcec` |
| 可灵 | 1 MCP endpoint、3 Skills、12 Skill 文件（含9个引用文件） | `851247253981d1edec38dca3d99134f614c300c07b1d0fabc388b76771abacb2` |

首轮真实 JVM 测试失败：`name must match the parent directory name`。GitHub 的目录 `skill` 声明 `name: github`，可灵总纲目录 `kling-ai-plugin` 声明 `name: kling-ai`。Connector 导入适配层现使用原文声明的名称建立内部目录，不改 SKILL.md/引用文件，不放宽普通 SkillLoader 校验。声明名必须是安全单层路径，同包重复声明名拒绝，避免静默覆盖；名称规范、正文和 metadata 仍由既有安装校验执行。增加声明名保真、重复名及路径穿越回归。

验证命令（先准备本地样本，再构建和运行；输入路径仅通过参数提供）：

```bash
python3 scripts/prepare-workbuddy-samples.py <export-directory> build/main-verification/workbuddy-sample
HELIX_WORKBUDDY_SAMPLE_DIR="$PWD/build/main-verification/workbuddy-sample" ./gradlew :extensions:skills:test :extensions:mcp:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest spotlessCheck detekt --no-configuration-cache
bash scripts/accept-hxa-125-workbuddy.sh <dedicated-api29-serial> build/main-verification/workbuddy-sample
bash scripts/accept-hxa-125-workbuddy.sh <dedicated-api36-serial> build/main-verification/workbuddy-sample
```

真实样本 JVM 测试覆盖四个 Skill staging/commit 后的全部文件字节一致性。Android 专项走真实 ContentResolver/ConnectorService/SkillRepository，校验 ZIP hash、默认禁用、显式启用/读取正文及全部 references、停用和移除；导入前后 MCP 注册列表不变，不连接真实服务。每个 API 的单个测试方法遍历两个包和全部四个 Skill，不能把方法数写成服务调用数。

原始日志位于本地忽略目录 `build/main-verification/workbuddy-sample/`，首轮功能失败保留为 `initial-failure.xml`，格式和静态检查的中间失败亦保留；最新结果单独记录。真实样本测试在无输入环境下仍为显式 opt-in，不能把默认跳过当成功。

剩余边界：这两个市场包的导入验收补齐 WorkBuddy 真实样本缺口；账号级配置中的其他端点仅作结构检查，没有证明其握手、认证或业务兼容。市场包没有认证字段，`needsCredential=false` 只说明配置未声明凭据，不能推断服务可匿名调用。Skill 中宿主名称/工具名仍原样保留，不承诺原文可在 Helix 自动完成 GitHub/可灵任务。独立 bearer 服务的无效凭据、拒绝、厂商撤销与重连仍待账号；HXA-125 保持 in progress，不提前启动 HXA-126。


最终结果：上述 Gradle 命令 exit 0（`final-regression.log`）；Skills 42/42、MCP 37/37；consumer 316 项中313通过、3个其他样本/联网 opt-in 跳过，developer 340项中337通过、3个同类跳过，均无失败/错误。WorkBuddy 真实样本在双 flavor 各1/1，未跳过。API29/36 最终测试 APK 各1/1，状态码0且无失败/假设跳过；两次每次覆盖两个包、4个Skill、全部9个引用文件。两项已有导出脚本测试、样本打包重现 hash、Shell/Python 语法及 docs/ADR/i18n/lockfiles/secrets/Runtime边界检查通过。生产 APK 与测试 APK 指纹及原始 XML 汇总见同目录 `result.json`；不把此受影响回归称为全项目全量重跑。
