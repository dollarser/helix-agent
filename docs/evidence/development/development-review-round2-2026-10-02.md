# 第二轮高风险链路审查：流式解析与恢复一致性

日期：2026-10-02（执行环境日期）。所有者授权继续审查并修复；不提交、不推送、不执行设备或真实账号测试。

## 基线与归属

HEAD 仍为 `0619409dd1d7c07e87608def5df388459f6bf71a`。保留[第一轮](development-review-2026-10-02.md)五文件及其他任务的 `docs/README.md`、`docs/development/README.md` 和 `engineering-playbook.md`；这些不是第二轮新增交付。HXA-244 已在基线内，不归为本轮工作。未修改 Mobile Use、重排 HXA 或新增功能框架。

## 已修复的确定问题

### Provider SSE 分块与损坏数据

Chat Completions、Responses、Anthropic 三个独立 SSE reader 都在整个 chunk 解码后处理换行，存在同样的边界错误：

- chunk 恰好在 CR / LF 之间切开时，下个 LF 被当作空行；多行 data 提前分派，typed event 可能丢失类型。修为逐码点处理行边界，以一位状态跨 chunk 识别 CRLF。
- 多条短行共处一个大 chunk 时，累计 chunk 内容错误触发单行 1 MiB 限制。现在每行及时处理，单行及每事件的原容量限制保留；补充双 UTF-16 单元码点的行长计数。
- UTF-8 surrogate 编码被接受，EOF 的不完整 UTF-8 被忽略，甚至仍分派待处理事件。现在拒绝 surrogate 与不完整 EOF，由既有 decoder 返回不可重试 PROTOCOL，不改变协议选择或增加请求重试。

每个 reader 新增四项测试：遍历所有单个分块切点的 CRLF 多行事件、surrogate 拒绝、不完整 EOF、多个短行大 chunk。这 **12 项先在原实现实际失败**，日志 `build/review-round2-2026-10-02/baseline.log`；三个模块原有测试未失败。另加三项 decoder 回归，确认畸形 UTF-8 / EOF 最终只产生一个不可重试 Error，重复 finish 不重复终态。共 15 项新增 JVM 方法，分块和字节案例在方法内部循环。

### 重启恢复与终结事务

`ToolCallDao.unsettledUnderTerminalTurns` 原先把已停泊的 INTERRUPTED 调用再次作为漏结算记录捞出；第二次恢复改为 NEEDS_REVIEW 并新增 audit。现在查询整体排除 NEEDS_REVIEW / INTERRUPTED，不被“没有结果”分支重新选中；PENDING / RUNNING / AWAITING_APPROVAL、缺结果的其他终态和未验证 COMPLETED 仍按原补偿路径处理。

`ProcessRecoveryTest` 原二次恢复只检查部分 correlation 的 audit 数量，漏掉 turnId correlation 的新增审计。补为再次检查所有原调用状态、未决调用仍无伪造结果，以及 fixture 全部审计列表不变。

`TurnSettlement.persist` 原先直接用墙钟作为 endedAt；时钟回拨会触发 Repository 的 endedAt >= startedAt 约束，导致 assistant / Turn / ModelCall 整笔事务失败。现在与已有恢复和 Goal settlement 一样 clamp 至 startedAt。增加真实 Room fixture 的回拨成功及重复提交幂等检查。原 rollback fixture 曾把回拨用作故障，改为在同一 assistant append 后明确注入 clock 异常，继续验证事务回滚，并未删除失败路径。

以上两项由当前源码、DAO 条件和既有事务约束确定；新增/增强的真实 Room 回归 **仅编译，未执行**，不声称已在设备复现或复测。没有为得到主机绿色另造 SQL 抽取器或假 Room。

## 实际审查覆盖

- Provider：完整读取 WireModelProvider 的请求编码/凭据解析/HTTP 错误/流式 feed/finish/finally 关闭，OkHttpWireClient 的 header 等待与阻塞 read 取消绑定；核对现有本机 socket 取消测试。读取三个 SSE reader 与 decoder 的 feed/finish 错误收口。顺带修正 OkHttpWireClient 的旧注释：非 2xx 按状态映射并关闭，并非先 drain body。未访问真实 Provider。
- 恢复：Core recovery plan、Engine startup/terminal settlement、Application 启动时序、GoalRunSettlement / GoalUsageReservations、Turn / ToolCall / ModelCall DAO 与 repository、既有 Room fixture。未把局部阅读称作完整恢复系统审计。
- 插件：只读核对 Installer / Service / Catalog / ConnectorDao 的候选归属、单次发布、CAS 删除、清理重试、共享 Skill 引用、retired endpoint；McpAppService 的在途计数/延迟凭据清理；OAuth generation 和删除后的晚响应校验。相同 hash 幂等返回符合 ADR；没有证据支持放宽 sessionScoped 端点权限。未找到另一项确定缺陷，不做猜测性重构。

恢复与插件使用并行只读审查；恢复问题确认后，仅授权同一协作者修改四个指定文件，主代理统一运行验证。CodeGraph 用于导航，源码为最终依据。审查覆盖不等于所运行测试的覆盖。

## 验证与限制

主机命令使用已有 JDK 17。Provider 修复前失败记录、修复后运行、编译及最终门禁分别保存在 `build/review-round2-2026-10-02/`。一次误用模块级 `spotlessKotlinApply` 在任务选择阶段失败，没有执行测试；随后使用根任务及精确文件列表，未批量格式化其他工作。首次最终静态检查发现三个新函数 return 数超限，已改写控制流，没有放宽规则或 suppression。

主机任务：`:provider:api:test :provider:openai-chat:test :provider:openai-responses:test :provider:anthropic:test :core:agent:test :core:storage:testDebugUnitTest :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest`，另 `spotlessCheck detekt`。最终 Provider API 与 Core Agent 使用 task `--rerun`，保证本轮重新执行既有传输取消和 Agent 用例；其他依赖允许增量复用。

最终联合命令包含上述任务和两渠道 AndroidTest Kotlin 编译，exit 0，38 秒，382 tasks（18 executed / 364 up-to-date），日志 `verified-host.log`。`./scripts/check-all.sh --source` exit 0，706 篇 Markdown / 227 个 HXA、35 份 ADR、907 个生产源文件 / 1962 个资源键与 Secret / 脚本门禁通过，日志 `source.log`。计数含并行任务新增文档，不视为本轮新增文件数。`git diff --check` exit 0。

| JVM task | 报告总数 | 通过 | 跳过 |
| --- | ---: | ---: | ---: |
| provider:api:test | 115 | 115 | 0 |
| provider:openai-chat:test | 69 | 69 | 0 |
| provider:openai-responses:test | 69 | 69 | 0 |
| provider:anthropic:test | 89 | 89 | 0 |
| core:agent:test | 296 | 296 | 0 |
| core:storage:testDebugUnitTest | 219 | 219 | 0 |
| app:testConsumerDebugUnitTest | 1081 | 1077 | 4 |
| app:testDeveloperDebugUnitTest | 1178 | 1174 | 4 |

合计 3116 条报告记录、3108 通过、8 跳过，非不同方法总数。跳过仍是每渠道两项外部 Connector opt-in 和两项缺本地样本，条件未改，详见第一轮记录。复用 `scripts/summarize-android-tests.py` 分任务核验；App 报告因跳过为 INCOMPLETE，不伪装外部验收通过。

编译范围：`:app:compileConsumerDebugAndroidTestKotlin :app:compileDeveloperDebugAndroidTestKotlin` 通过；`:core:storage:assembleDebugAndroidTest` 已生成测试 APK。没有打包新的 App APK，没有运行 instrumentation。设备状态 **not requested**；真实 Provider / OAuth 账号、FFmpeg / PRoot 媒体端到端、OEM / 进程强杀和长稳均未执行。

后续应在所有者明确授权设备验证时执行增强的 ProcessRecoveryTest、TurnCommitStoreDeviceTest 及既有 ToolSettlementRecoveryDeviceTest。ModelCall 在其他终态父 Turn 下的遗留 RUNNING 情况尚缺可达异常链证据，本轮不据猜测扩大修改。

## 第二轮文件清单

- `provider/openai-chat/.../ChatSseReader.kt`、`ChatSseReaderTest.kt`、`ChatCompletionsStreamDecoderTest.kt`。
- `provider/openai-responses/.../ResponsesSse.kt`、`ResponsesSseParserTest.kt`、`ResponsesStreamDecoderTest.kt`。
- `provider/anthropic/.../AnthropicSseReader.kt`、`AnthropicSseReaderTest.kt`、`AnthropicStreamDecoderTest.kt`。
- `provider/api/src/main/kotlin/com/helix/provider/api/wire/OkHttpWireClient.kt`：仅修注释。
- `core/storage/src/main/kotlin/com/helix/core/storage/dao/ToolCallDao.kt`。
- `app/src/main/kotlin/com/helix/app/engine/TurnSettlement.kt`。
- `app/src/androidTest/kotlin/com/helix/app/recovery/ProcessRecoveryTest.kt`、`app/src/androidTest/kotlin/com/helix/app/engine/TurnCommitStoreDeviceTest.kt`。
- 本记录及 `docs/evidence/development/README.md` 的第二轮导航条目。

共 16 个第二轮涉及文件，其中证据索引与第一轮共用；未新增测试汇总脚本、SQL 抽取工具或兼容层。
