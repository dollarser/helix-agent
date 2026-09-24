# 维度五:优化与删减

> 审查对象:`/Users/dollars/Helix`,基线 git HEAD `3cf89027`(2026-09-24)+ 未提交工作树(16 个改动 + 未跟踪的 Root 文件管理新功能:`app/src/{consumer,developer}/.../root/RootFileModule.kt`、`app/src/main/.../files/RootFileOperations.kt`、`tools/root/.../RootFileAccessor.kt`)。
> 方法:纯静态取证(grep/find/wc/diff/git log),未运行 gradle 构建/测试,仓库严格只读。所有"可移除"结论均附无引用 grep 证据。

## 总评

1. **仓库卫生延续优秀**(复核历史结论):36 个 lockfile(32 模块 + root + testing/spikes),坐标无多版本冲突;生产源码 **0 个 TODO/FIXME**(符合 AGENTS.md "无未解决 TODO");i18n 三语言完全一致(1363 key × 3,0 缺失);res 全部被引用(drawable 17/17、xml 2/2、`getIdentifier` 动态加载 0 处);`build/`、`__pycache__`、`.pyc` 均不入库。
2. **本轮最大发现:core/agent 存在一整族"已实现、从未接线"的死代码**(历史审查只标记了 `TurnReducer` 单点)。以 import 级证据确认:`TurnReducer/TurnState/TurnEvent/TurnEffect/TokenUsage`(1,230 行)、`GoalSchedule/GoalScheduleCodec/GoalForeground`(292 行)、`DeveloperRuntime`(247 行)在 main 生产源集中 **0 个 import**;core/model 另有 `ExecutionState`+`ToolExecutionEnvelope`+8 个 ID 值类(约 348 行)生产 0 引用。合计 **约 1,770 行生产死代码 + 约 1,620 行绑定测试**——这是当前可维护性最大的单一税项(双状态机心智负担 + 误读 KDoc + 测试维护成本)。
3. **SSE 解析三份逐行重复**(历史审查结论复核并量化):`AnthropicSseReader`(262)/`ChatSseReader`(242)/`ResponsesSse`(258)两个类体 diff 仅 **21/39 行**(差异=类名/事件类型名/注释),UTF-8 增量解码、行断处理、事件分发、fail 守卫约 230 行 × 3 字节级相同;解码器骨架(`ProtocolViolation`/`failProtocol`/`stringOf`/`MAX_TOOL_CALLS=32`)又在 3 个流式解码器中各复制一份。
4. **构建卫生总体健康但有 4 处重复/失配**:kotlin 2.3.21 vs KSP 2.3.11 仍失配(历史发现,未修);`okhttp→okhttp-jvm` substitution 3 份;根 `build.gradle.kts`(611 行)集中定义全部 32 模块,androidTest 三元组重复 10 次;jgit 定制 transform 硬编码 3 个坐标(已入 lockfile,但绕过版本目录)。版本目录 44 个 library alias **全部在用,无孤儿**。
5. **过度设计少,但"投机性功能实现"多**:核心行为接口(ModelProvider/Clock/CapabilityResolver/WireClient/ApprovalBroker 等)全部有测试 fake,抽象有收益;真正的问题是 DeveloperRuntime/GoalSchedule/GoalForeground 这类"按设计文档写完、无生产调用方"的功能。`CanonicalArgs`/`ToolSchemaCanonicalizer` 只在 tools:framework 单点实现,无重复。生产 0 feature flag(唯一 `BuildConfig.DEBUG` 是 quickjs 调试裂缝,合理)。
6. 工作树中的新 Root 文件管理功能 seam 设计合格(主源集 `RootFileOperations` 接口 74 行 + flavor 双实现 54/189 行),不构成本轮问题。

## 可移除清单(每条带无引用证据)

| # | 对象 | 位置 | 无引用证据 | 预计省行数 | 严重度 |
|---|------|------|-----------|-----------|--------|
| R1 | **core/agent M1 串行 Turn 模型族**:`TurnReducer`(object)+`TurnState`(data class)+`TurnEvent`(sealed)+`TurnEffect`(sealed)+`TokenUsage` | `core/agent/.../TurnReducer.kt`(694)、`TurnState.kt`(145)、`TurnEvent.kt`(255)、`TurnEffect.kt`(55)、`TokenUsage.kt`(81) | `grep -rn "import com.helix.core.agent.Turn(Reducer\|State\|Event\|Effect)"` 于全部 main 源集 = **0 命中**(唯一 import 在 `app/src/androidTest/.../ProcessRecoveryTest.kt:10-11,38`);core/agent 内部引用全部为 KDoc(`TurnEvent.kt:12,71`、`RecoveryCoordinator.kt:113,170`、`TokenUsage.kt:5`、`TurnState.kt:91`、`TurnEffect.kt:8`);`TurnCoordinator.kt:60` KDoc 自述"deliberately does not reuse the M1 serial TurnReducer" | **1,230**(生产)+ 约 1,066 测试(6 个 `TurnReducer*Test` 894 行 + `TurnTestFixtures.kt` 172,同包无 import 直接引用) | **P1** |
| R2 | **Goal 调度子功能**:`GoalSchedule`(sealed)+`GoalScheduleCodec`+`GoalForeground`(含 `GoalPauseReason`) | `core/agent/.../GoalSchedule.kt`(83)、`GoalScheduleCodec.kt`(85)、`GoalForeground.kt`(124) | `grep -rlnw 'GoalSchedule'` 全仓 = 仅自身 2 文件 + 2 个测试文件;core/storage、app 主源集 **0 引用**(目标行表没有 schedule 列);`GoalForeground`/`GoalScheduleCodec` 全仓 word 搜索仅命中自身 | **292** + 221 测试(`GoalScheduleTest` 132 + `GoalScheduleCodecTest` 89)+ 103 测试(`GoalForegroundTest`) | **P1**(整个 once/daily/weekly 调度功能"实现了没接线") |
| R3 | **Developer Runtime 状态机**:`DeveloperRuntime` object + 事件/状态类型 | `core/agent/.../DeveloperRuntime.kt`(247) | import 级搜索:仅自身文件自 import(`DeveloperRuntimeState.Status/Step`);app 主源集 0 驱动方(KDoc 声称"the app's real provisioning actions drive it"——实际不存在) | **247** + 228 测试(`DeveloperRuntimeTest`) | **P1** |
| R4 | **core/model 8 个生产 0 引用 ID 值类**:`MessageId`/`ToolResultId`/`ArtifactId`/`AuditEventId`/`RuntimeInstallId`/`OperationId`/`ScopeId`/`WorkspaceId` | `core/model/.../Identifiers.kt:27-266`(24 个值类中的 8 个,每个 15 行) | 逐个 `grep -rw` 全仓(除自身文件、core/model 内部、测试):8 个全部 **0 命中**;唯一引用是 `IdentifiersTest.kt:14,34...` 各 1 行(历史审查 2026-09-24 已指出,现状未变;同文件 TurnId 29/ToolCallId 47/SessionId 11 在用,勿整文件删) | **约 120** + 约 40 测试 | **P2** |
| R5 | **ExecutionState 枚举 + ToolExecutionEnvelope** | `core/model/.../ExecutionState.kt`(53)、`ToolExecutionEnvelope.kt`(160,含唯一使用 `ExecutionId` 值类的地方) | `grep -rn 'ExecutionState\.'` 与 `grep -rlnw ToolExecutionEnvelope` 于生产 main = 0(core/model 自身文件除外);`ExecutionState.kt` KDoc 自述"until HXA-035+ wires an executor"——从未接线;`ExecutionId` 随之失去使用方 | **228**(含 `ExecutionId` 值类 15)+ 115 测试 | **P2** |
| R6 | **3 个死 Adapter 类**(历史发现,仍未删) | `provider/openai-chat/.../OpenAiChatAdapter.kt`(32)、`provider/anthropic/.../AnthropicAdapter.kt`(30)、`provider/openai-responses/.../OpenAiResponsesAdapter.kt`(28) | 逐个 `grep -rw` 全仓 = **各 1 命中(仅自身声明)**,生产 0 引用;`OpenAiChatProvider` 直接调 `ChatCompletionsRequestEncoder`/`ChatCompletionsStreamDecoder` 绕过 | **90** + 3 个公开类型 | **P2**(零风险) |
| R7 | **死 typealias** `McpOAuthPkceChallenge`(历史发现,仍未删) | `extensions/mcp/.../McpOAuthPkce.kt:57` | `grep -rn 'McpOAuthPkceChallenge'` 全仓 = 1 命中(自身定义) | 1 | **P2** |
| R8 | **死常量** `CodexPayloadJob.MAX_PAYLOAD_BYTES`(64MB,历史发现,仍未删) | `runtime/cli-app/.../CodexPayloadJob.kt:111` | `grep -rn 'MAX_PAYLOAD_BYTES'` 全仓(.kt/.kts/.xml/.md,排除 build)= 仅自身定义 + 2 份审查文档;代码 0 引用 | 1 | **P2** |
| R9 | **ToolSource 接缝**(投机抽象):`ToolSource` 接口 + `ToolSourceKind` 枚举 + `BuiltInToolSource` | `tools/framework/.../ToolSource.kt`(`interface ToolSource`:35、`class BuiltInToolSource`:53,约 70 行) | `ToolRegistry(sources = emptyList())`:生产唯一构造点 `DefaultAppContainer.kt:227` 传空列表,工具全部走 `register(...)`;`grep -rnw 'BuiltInToolSource'` 生产 = 0(仅 `ToolSourceTest`/`ToolRegistryTest`) | **约 70**(+ `ToolRegistry` 的 `sources` 参数) | **P2** |
| R10 | **未用字符串资源 9 个 key × 3 语言**:`connector_update_{title,changelog,components_changed,confirm_button,endpoints_reconfigure}`、`cap_status_connected`、`session_capability_status_disabled_globally`、`session_capability_toggle_desc`、`session_details_title` | `app/src/main/res/{values,values-en,values-zh-rCN}/strings.xml` | 全量扫描 1,363 key:对每个 key grep `string.<key>` 与字符串字面量 `"<key>"` 于 app/feature 全部源集 = 0 命中(15 个初判中有 6 个 `chat_command_*_desc` 实际以字面量形式被 `ComposerCommandParser.kt:79` 等引用,已排除);`connector_update_*` 簇对应 UI 不存在(`ConnectorUpdateDiffEvaluator` 生产逻辑在用,但无展示对话框) | **27 行**(9×3 语言) | **P2** |
| R11 | **desktopMode 全局死偏好**(历史发现,仍未删) | `feature/browser/.../BrowserStorage.kt:247,267`(读/写偏好)、`BrowserMenuSheet.kt:204`(`tab.isDesktopMode || preferences.desktopMode` 后半恒 false) | `preferences.desktopMode` 全模块 **无写点**(只有读点);tab 级 `desktopMode`(`WebViewTabHost.kt:270-409`)是活的 | 约 10 | **P2** |
| R12 | **重复调试脚本** `clean-packages.py` 两份内容相同 | `scripts/debug/2026-09-17/hxa194/clean-packages.py` 与 `scripts/debug/2026-09-17/p0/clean-packages.py`(各 17 行,`diff -q` 无差异) | — | 17 | **P2** |

**合计可移除:生产/资源约 2,330 行 + 绑定测试约 1,650 行 ≈ 4,000 行。**
R1-R3 建议打包做"core/agent 死族处置"决策(删除 或 ADR 归档为 M1 参考语义),无论哪种都要同步清理 6 处 KDoc 引用(`core/agent` 5 个文件 + `TurnCoordinator.kt:60`)。

**判定为"不可移除/保留"的对象(附证据,防止误删):**

- `:testing` 模块(105 行 `FixedEvalCatalogTest.kt` + lockfile):仍是孤儿(全仓 `grep project(":testing")` 0 命中),但 `docs/evidence/development/repository-hygiene-2026-09-22.md:10` owner 明确裁决"**保留 :testing 及其四项评测数据校验,不为减少模块数量迁移测试**",`scripts/README.md` 同句复述 → **接受债务,不列入可删**。
- spikes 3 模块(1,030 行:`a2a-sdk` Java 探针、`a2a-minimal` 593、`bounded-orchestration` 437):结论已归档(`HXA-077/078` 完成记录、`docs/adr/a2a/001`),且 `check-all.sh`/`check-lockfiles.sh`/两个 A2A spike 检查脚本显式 `-PincludeSpikes=true` 参与锁文件校验(`scripts/README.md` "Spike sources and historical evidence remain tracked")→ **保留**(删除会破坏历史命令复现与锁验证);默认不参与构建,成本≈0。
- `scripts/debug/`(697 个跟踪文件,18 个日期目录 + archive/2 + README + manifest):**归档纪律执行良好**——无散落文件、日期目录全部 YYYY-MM-DD 格式、`README.md` 声明三类材料 + `.py.txt` 惰性归档 + `archive-manifest.json` 记录 sha256 与脱敏;owner 明确"Do not blanket-ignore or remove dated directories" → 仅 R12 可动。
- `WebViewTabHost.kt:136` 的 `@Deprecated` override:是覆写 **Android 已废弃 API**(legacy `onReceivedError`),为捕获旧版主框架错误而故意保留,非死代码。
- `:feature:files-allfiles`(251 行,仅 developer):历史审查建议并入 `:feature:files`,体量缩小后收益下降 → 保留现状。
- `jgit` alias 0 个 `libs.jgit` accessor 引用:**不是孤儿**——`config/jgit/reject-insecure-tls.gradle.kts:202` 经 `findLibrary("jgit")` 程序化引用并做 build-time 加固 transform。

## 重复代码

### D1. SSE 读取器三份逐行重复(最高价值重构,P1)

- 文件:`provider/anthropic/.../AnthropicSseReader.kt`(262)、`provider/openai-chat/.../ChatSseReader.kt`(242)、`provider/openai-responses/.../ResponsesSse.kt`(258)。
- 量化:`diff` 两个完整类体(30 行至文件尾)= **仅 21 行差异**(Anthropic vs Responses),差异全部是类名/`SseEvent` 类型名/注释措辞;Chat 变体是 data-only(不解析 `event:` 字段),diff 39 行。
- 逐行相同的重复段(以 `ResponsesSse.kt` 行号为准,另两份对应):
  - UTF-8 增量解码:`decodeStep`(:90-112)、`assembleCodePoint`(:115-134)、companion 的 `sequenceLength`/`minCodePoint`/`codePointChars`(:228-245)、`Utf8Step`(:248-251)
  - 行/事件状态机:`feed`(:48-64)、`finish`(:67-73)、`decodeInto`(:75-84)、`processLineBreaks`(:145-161)、`processLine`(:163-207)、`dispatch`(:209-216)、`fail`(:218-221)
  - 限额常量:`MAX_LINE_LENGTH=1MiB`/`MAX_EVENT_DATA_LENGTH=8MiB`(:224-225)
  - 约 25 行 WHATWG SSE 契约 KDoc 在三个文件逐字重复。
- 合并方案:把"UTF-8 增量 + 行断 + 事件缓冲 + fail 守卫"抽成 `:provider:api` 的 `internal class SseEventStream`(约 250 行,三个 provider 模块都依赖 :provider:api,无新增依赖边);三个薄映射(各约 40 行):Anthropic/Responses 映射 `SseEvent(type,data)`,Chat 只取 `data`。
- 收益:762 行 → 约 250+3×40 ≈ 370 行,**省约 400 行**;更重要的是协议级 bug(UTF-8 边界、CRLF、超长行)只需修一处。
- 风险:中。协议关键路径;现有护航:3 个 `*SseReader(Parser)Test` + 3 个 `*StreamDecoderTest`。

### D2. 流式解码器骨架四重复(P2)

- 文件:`AnthropicStreamDecoder.kt`(467)、`ResponsesStreamDecoder.kt`(482)、`ChatCompletionsStreamDecoder.kt`(394)。
- 三份各自完整复制的骨架(逐字或近逐字):
  - `private class ProtocolViolation(val detail: String) : RuntimeException` —— 3 处文件尾各一份(Anthropic :465-467、Responses :480-482、Chat :392-394)
  - `failProtocol(out, detail)`(protocolFailed 幂等守卫)—— 3 处完全相同(Anthropic :414-422、Responses :431-439、Chat :365-373)
  - `stringOf(element) = (element as? JsonPrimitive)?.contentOrNull` —— 3 处
  - `requireString(obj, key)` —— Anthropic :405、Responses :210 相同
  - `MAX_TOOL_CALLS = 32 // same bound as core:agent ModelTerminal.ToolCalls` —— 3 处,注释本身就承认"同源"
  - 终态守卫模式(`terminalEmitted`/`isPostTerminalViolation`,Responses :191)
- 合并方案:`:provider:api` 增加 `ProtocolSupport`(ProtocolViolation + failProtocol 状态 + stringOf/requireString)+ `ToolCallAccumulator`(按 index 累积分片 args 的通用逻辑,三家 tool-call 累积语义一致:Anthropic `startToolBlock`/`emitInputJsonDelta`、Chat `handleToolCallFragment`、Responses `handleArgumentsDelta`)+ 公共 `MAX_TOOL_CALLS` 常量(或引用 core:agent 的既有界)。`mapVendorError` 的错误码表是 vendor 私有,保留各自实现但改为接收统一的 `(code) -> Pair<ModelErrorCode, Boolean>` 映射类型。
- 收益:约 150-200 行;与 D1 合并后 provider 三包从 ~2,500 行降到 ~1,700 行。
- 风险:中(同上,测试护航齐全)。

### D3. developer/consumer flavor 接缝(重复受控,无需动作)

- 6 对同名 object 接缝(行数 consumer/developer):`DistributionModuleRegistry`(12/26)、`AllFilesModule`(39/265)、`AutomationModule`(24/123)、`ProotToolModule`(119/468)、`RootModule`(34/159)、`SubscriptionProviderModule`(30/196)。`diff` 确认各对差异=整个实现体,consumer 侧全部是 no-op/`error("...unavailable in this distribution")` 桩。
- 新 RootFileModule(未跟踪,54/189)改用了**主源集接口**模式:`RootFileOperations.kt`(74 行,`app/src/main`)定义 9 个方法,consumer 实现 54 行 NoOp,developer 实现 189 行(libsu `RootFileAccessor`)。
- 评估:flavor 分缝的重复是**本质性**的(源码集隔离,编译期裁剪),且每对 consumer 侧都很薄(≤119 行);两种缝法并存(同名 object vs 接口)带来轻微认知不一致。**P2 建议:后续新接缝统一走"main 接口 + flavor 实现"模式,存量不动。**

### D4. e.message / 原始异常直传用户可见状态(复核历史 ~10 处 → 现状 14 处)

当前工作树实测(`grep '${e.message}' / 'e.message ?:'` 于 main 源集,UI/服务可见方向):

| 位置 | 形态 | 可见面 |
|------|------|--------|
| `app/.../files/FileManagerService.kt:470,472,486,511,575` | `FileOpResult.Error(e.message ?: loc(R.string.files_error_*))` ×5 | 文件管理器 UI 错误条 |
| `app/.../files/FileManagerTrash.kt:67,89` | 同上(restore/purge) | 同上 |
| `app/.../ui/FilesScreenEffects.kt:37,79` | `loadError = it.message ?: ...`、`FilePreviewState.Failed(failure.message ?: ...)` | 文件屏 |
| `app/.../ui/FilesRecoveryPanel.kt:84` | `{ it.message ?: actions.str(...) }` | 恢复面板 |
| `app/.../git/GitWorkspaceReader.kt:45` | `GitWorkspaceResult.Error(it.message ?: "Could not read git status")` | Git 状态区 |
| `feature/browser/.../BrowserDownloadQueue.kt:120` | `DownloadStatus.FAILED to (e.message ?: ...generic)` | 下载 UI |

(模型可见方向,非用户可见,不计入:工具结果 `PlanTools.kt:140`、`TodoWriteTool.kt:121`、`GoalLifecycleService.kt:67` 的 `ToolExecutorResult.Failed(e.message ?: ...)`——校验类异常给模型读是合理设计。)

- 历史点全部仍在,且 `FileManagerService` 工作树改动(+73 行 Root 功能)新增了 `FileSource(ROOT)` 路径,错误面进一步扩大。
- 合并方案:抽 `app/.../errors/ErrorMessages.kt`——`fun safeFileError(e: Throwable, fallbackResId: Int): String`(映射异常类型→安全文案+错误码,`e.message` 只进 Log/audit),14 处统一改道。**P2**。

### D5. UI 组件重复(仅标记,维度三深查)

- `AlertDialog` 75 处 / 32 个文件(无统一错误/确认组件);`.dp` 字面量 391 处、`MaterialTheme.colorScheme.primary/secondary` 39 处(无设计令牌);`ArtifactsScreen.kt:139` 私有 `SectionHeader` 等按文件私有的头组件;`SettingsActions`(app/ui/SettingsActions.kt:15)已被多处复用是正面案例。**P2,随维度三设计系统工作一并处理。**

### D6. 构建配置重复(P2)

- `okhttp→okhttp-jvm` 的 `resolutionStrategy.dependencySubstitution` **3 份**:`app/build.gradle.kts:188-194`、根 `build.gradle.kts:443-448`(`:spikes:a2a-minimal`)、`463-472`(`:extensions:a2a`)——抽 `config/okhttp-jvm/dependency-substitution.gradle.kts` 三处 `apply(from)`,与 `config/jgit` 同模式。
- 根 `build.gradle.kts` 的 androidTest 三元组(`androidTestCoreKtxDependency+androidTestRunnerDependency+androidTestJunitDependency` 三连 add)**10 处**(:core:storage 289-291、:runtime:cli-client/proot-ipc 305-309、:feature:files 314-316、:tools:android 323-325、:tools:automation 331-333、:tools:root 341-343、:feature:browser 382-384、:runtime:quickjs 401-403、:spikes:a2a-minimal 452-454、:spikes:bounded-orchestration 457-459)——统一进 `subprojects` 基线。

## 大文件拆分方案

### 3.1 ChatService.kt(4,047 行,app/chat)——P0/P1,必须拆到类型级

**现状职责清单**(183 个 fun;构造函数 14 参数 :139-181;已提取协作者:`ChatRequestAssembler`、`ChatScreenProjection`、`ChatToolCalls`(614)、`AgentLoop`、`ChatGoalActions`、`ChatRecoveryActions`、`ChatStatusLabels`、`StagedAttachmentProcessor`、`ChatAttachmentRetry`、`ChatDraftStore`;类内 10 个 StateFlow 屏幕状态 :285-345):

| 职责簇 | 行段(近似) |
|--------|------------|
| 模式/预算/连续 Goal 意图 | 371-520(setMode/setTurnBudgets/stopContinuousGoals/revokeGoalIntent) |
| 会话生命周期(搜索/草稿/重命名/目录/fork/归档/删除) | 655-960 |
| 分享草稿 + provider/model 绑定 | 989-1093 |
| 附件(暂存/导入/准入/移除) | 1117-1240 |
| 提交管线(send/sendSubmission/confirm/cancel/dedup) | 1662-2230(约 570 行,`sendSubmission` 1668-1933 265 行) |
| 任务控制(collectTaskResult/stopTask/refreshBackgroundTasks/retry/regenerate) | 2235-2560 |
| 会话输入队列(queue/withdraw/edit/resume/校验/权限绑定) | 2875-3330(约 450 行) |
| Turn 启动/终态(launchAndPublishTurn/turnStartSpec/applyEvent/terminalize/终态通知/结算) | 3333-3860(约 530 行,含历史 P1 的 `synchronized(turnGate)` 内 Room 事务 :3422/:3481) |
| 屏幕投影(refreshScreen/refreshPersistedScreen/stagedAttachmentsUi/publishTurn/setBlocked) | 3868-3990 + 273-290 |

**建议拆出的类型**(新类型名+职责+预计行数):

1. `ChatScreenState`(约 250 行):10 个 StateFlow + `refreshScreen`/`refreshPersistedScreen`/`stagedAttachmentsUi`/`publishTurn`/`setBlocked`/`terminalLabel`。即历史审查建议的 ChatStateHolder。
2. `ChatSubmissionPipeline`(约 550 行):`send`/`sendSubmission`/`confirmSubmission`/`cancelSubmission`/`submissionBlocked`/`resolveSubmitDedup`/`validHumanInput`/`isAcceptedInput` + 提交去重状态。
3. `ChatTurnLifecycle`(约 550 行):`turnStartSpec`/`launchAndPublishTurn`/`startCoordinatorForTurn`/`applyEvent`/`terminalize`/`dispatchTerminalNotifications`/`endTurnSettlement`/`syncGoalReminderForTurn` + `turnGate` 锁(锁随走;历史 P1 "synchronized 内事务"在此处一并裁决)。
4. `ChatSessionInputQueue`(约 450 行):`sessionInputQueue`/`withdrawSessionInput`/`editSessionInput`/`resumeSessionInput`/`resumeValidatedInput`/`queueStillConsumable`/`inputControl`/`bindInputAuthority`/`requestSessionDrain`。
5. `ChatSessionLifecycle`(约 300 行):open/close/archive/restore/rename/fork/preparePermanentDeletion + 搜索 + 草稿。
6. `ChatTaskControl`(约 300 行):后台任务收集/停止/重试/重生成/仪表盘刷新。

拆完 `ChatService` 保留:门面委托 + 协作者装配 + provider/model 切换 + Goal 意图(约 800 行)。
**拆分依赖顺序**:`ChatScreenState`(耦合最低,先拆)→ `ChatSessionInputQueue` → `ChatSubmissionPipeline` → `ChatTurnLifecycle` → 其余。每步单独提交。
**风险**:高(全仓测试最多的类:30 个测试文件;设备测试重;`turnGate` 锁与 lazy 协作者互相引用 `::refreshScreen`/`_screen`,拆时需用构造注入回调切断)。建议每拆一个类型跑对应测试簇 + 一轮设备冒烟。

### 3.2 ToolDispatcher.kt(1,174 行,tools/framework)

**现状**:`ToolDispatchRequest`(:55-111)/`BoundToolResult`(:112-123)/`ToolDispatchOutcome`(sealed,:124-210)三个类型 + `ToolDispatcher` 类 29 个方法:阶段管线 `dispatch`→`runAttempt`→`policyStage`/`validateStage`→`acquireApproval`→`executeStage`→`commitExecutionStart`→`finish`/`finishStop`/`finishExecutionFailure` + `DispatchContext` 私有类(:1146-1174)+ 指纹/sha256 工具函数。
**拆出**:
1. `ToolDispatchTypes.kt`(约 156 行):3 个数据/sealed 类型。零风险,先做。
2. `ToolFingerprint.kt`(约 80 行):`actionFingerprint`/`sha256Hex`/`charUtf8Length`。
3. `ToolPolicyStage.kt`(约 200 行):`policyStage`/`sessionPermissionResolution`/`effectiveToolAvailability`/`denialCode`/`composedPermissionDetail`(:367-558)。
4. `ToolApprovalStage.kt`(约 180 行):`acquireApproval`(:655-733)+ 审批等待/中断。
5. `ToolExecutionCommit.kt`(约 200 行):`executeStage`/`commitExecutionStart`/`mayStart`/`finishExecutionFailure`(:733-930)。
保留 `ToolDispatcher`:dispatch/runAttempt 编排 + finish/finishStop + `DispatchContext` 作为共享参数对象(约 350 行)。
**依赖顺序**:types → fingerprint → policy → approval → execution。**风险**:低中(9 个测试文件 + `ToolDispatcherTest` 1,444 行护航;阶段间共享 `DispatchContext` 状态是主要拆分成本)。

### 3.3 ProotJobRunner.kt(942 行,runtime/proot-app)

**现状**:13 个成员方法:容量预留(`reserveDetached`/`releaseDetached`/`reserveManualTerminal`/`releaseManualTerminal`/`expireDetached`,:124-172)+ `LiveJob`(:174-184)+ `start`/`submitWithOwner`(:184-284)+ **`runJob` 268 行巨型方法**(:284-552,launch→monitor→collect 三阶段)+ 终态簇 `terminalInputInvalid`/`stopBeforeLaunch`/`terminalFailed`/`terminal`(104 行)/`publishTerminal`(:552-752)+ 截止期 `enforceDeadlines`(:752-770)+ `sweepOrphans`(:770)。
**拆出**:
1. `ProotJobCapacity.kt`(约 90 行):预留/释放/过期。
2. `ProotJobLauncher.kt`(约 220 行):`start`/`submitWithOwner` + `runJob` 的 launch 段。
3. `ProotJobTerminalizer.kt`(约 220 行):终态簇 + `publishTerminal`。
4. `runJob` 剩余 monitor/collect 段留在主类或抽 `ProotJobMonitor`(约 250 行)。
**依赖顺序**:capacity(独立)→ terminalizer → launcher/monitor。**风险**:中(companion APK 作业状态机是 HXA-083+ 核心;历史 P2 "PRoot 提交拒绝时泄漏 PFD" 位于 `runJob` 段,拆分时顺带修复;6 个测试文件 + `ProotJobRunnerDeviceTest` 600 行)。

### 3.4 BrowserController.kt(877 行,feature/browser)

**现状**:65 个成员函数,六簇:标签生命周期+导航(`newTab`/`closeTab`/`select`/`navigate`/`goBack`/`goForward`/`reload`/`stop`/`retry`,:118-330)、快照/捕获(`snapshot`/`capturePagePng`/`verifyNodeToken`,:330-434)、下载(:454-465)、存储清理(`clearCookies`/`clearCache`/`clearHistory`,:465-478)、查找(:497-525)、书签/历史/快链/用户脚本/设置开关(:562-710)、页面动作(`evaluateFixed`/`extractSource`/`extractReader`/`injectEruda`,:383-434,691-710)、attach/detach 生命周期(:722-765)。
**拆出**:
1. `BrowserTabNavigator.kt`(约 250 行):标签 CRUD + 导航。
2. `BrowserTabPersistence.kt`(约 150 行):书签/历史/快链读写(底层 store 已在 `BrowserStorage`)。
3. `BrowserTabPreferences.kt`(约 100 行):搜索引擎/广告拦截/无图/夜间/eruda 开关(顺带裁决 R11 的 desktopMode 死偏好)。
4. `BrowserPageActions.kt`(约 130 行):快照/捕获/抽取/evaluate/查找。
保留 `BrowserController`:标签 map + attach/detach/publish(约 250 行)。
**依赖顺序**:preferences(最独立)→ persistence → pageActions → navigator。**风险**:低中(12 个测试文件;纯层 `BrowserTabController` 状态机已独立,本类是 IO 薄壳,拆分主要是组织性)。

### 3.5 TurnCoordinator.kt(681 行,app/agent)

**现状**:M2 并行 turn 状态机(与死掉的 M1 `TurnReducer` 并存,`:60` KDoc 明言"deliberately does not reuse the M1 serial TurnReducer");含历史 P1 bug 点 `requireBatchSettled()`(:125-129,事务前抛丢成功结果)。
**拆出**:
1. `TurnBatchSettlement.kt`(约 150 行):并行批次结算 + `requireBatchSettled`(**先做维度四 P1 语义修复再拆**)。
2. `TurnInterruptRecovery.kt`(约 150 行):中断/恢复/交接。
保留主状态机(约 380 行)。
**依赖顺序**:先修 P1 → 拆 settlement。**风险**:中(25 个测试文件)。**前置**:R1 死族处置后,core/agent 中 5 处提及 `TurnReducer` 的 KDoc 一并清理,消除"双状态机"误导。

### 3.6 TurnReducer.kt(694 行,core/agent)

**不拆——见可移除清单 R1**。整个类型生产 0 引用;若 owner 选择"保留为 M1 参考语义",则应:移出主源集编译(或 `@Deprecated` + ADR 标注)+ 删除 1,066 行配套测试 + 清理 KDoc 引用,三者必须同时发生,否则"死代码 + 活测试"组合会继续产生维护税。

### 3.7 DefaultAppContainer.kt(766 行,app)

**现状**:手工 DI 容器,约 25 个 lazy val:底座(:93-230 context/store/clock/credentials/vision/scope)+ toolRegistry/connectorCatalog(:227-282)+ `fileServices`(`AppFileServices.kt` 205 行,**已按此模式拆出的正面案例** :322)+ jsExecution(:314-322)+ **`toolPipeline` 构造块约 115 行**(:445-560:broker/auditSink/sessionPermissions/effectClassifier/disabledToolFilter/dispatcher/scheduler)+ 历史 P1 "可变单例破环" `ApprovalCardSinkHolder`(:445 附近)+ **`ChatService` 构造(54 参数调用 + lambda 适配,约 100 行)**(:662-760)。
**拆出**:
1. `AppToolPipelineFactory.kt`(约 150 行):toolPipeline 全块。最自包含,先做。
2. `AppChatWiring.kt`(约 130 行):ChatService 构造 + strings/locale 等 lambda 适配。
3. `AppProviderStack.kt`(约 80 行):credentials/provider 构造段。
保留容器骨架 + lazy 装配(约 400 行)。
**依赖顺序**:toolPipeline → chatWiring → providerStack;`ApprovalCardSinkHolder` 破环随 chatWiring 拆分解决。**风险**:低(纯装配;端到端由设备测试覆盖)。

### 3.8 WorkspaceArtifactStore.kt(779 行,core/workspace)

**现状**:工件文件引擎,方法扁平:layout(:75-127)、write/writeArtifactStream(:127-248)、read/probe(:248-296)、stat/listDir(:306-387)、search(:387-422)、mkdir/copy/move(:422-545)、trash 薄委托(:545-553)、`streamCopyAndHash`(:582-639,57 行)、data class 簇(:724-779)。
**评估**:779 行是"存储引擎"而非"上帝类",内聚可接受。
**可选拆出**(低优先级):`ArtifactTrash.kt`(约 80)、`ArtifactSearch.kt`(约 80)、`StreamHash.kt`(约 80);主 store 留约 540 行。
**依赖顺序**:独立。**风险**:低(35 个测试文件,全仓最多),但收益主要是组织性 → 排在 ChatService/ToolDispatcher 之后。

### 3.9 HelixMigrations.kt(615 行,core/storage)

**现状**:24 个迁移(`MIGRATION_1_2`…`MIGRATION_24_25`),每个是自包含 `object : Migration(from,to)`(6-56 行,平均 25 行;最大 `MIGRATION_19_20` 56 行),单 `internal object` 聚合,线性降序排列。
**评估**:**当前形态合理,不建议按版本拆文件**。理由:(a) Room/Android 惯例即单迁移清单文件;(b) 24 个 × 25 行的"线性 schema 历史"是文件价值——一眼看全库演化;(c) 拆成 24 个小文件只增加导航成本,零收益;(d) 迁移必须 append-only,单文件 + git blame 最易保证。
**规则建议**:保持单文件 + 在文件头写明"append-only,禁止改写已发布迁移";若未来超过 ~1,000 行,按里程碑拆两个文件(v1-15 归档 / v16+ 现役),注册顺序不变。**非 P 级问题。**

### 3.10 ConversationSection.kt(515 行,app/ui)

**现状**:单一 `@Composable`(:46-515)——会话列表区(搜索框 + 会话行 + 草稿栏 + 空态)。0 个直接测试文件(靠屏级设备测试覆盖)。
**拆出**:
1. `ConversationSessionRow.kt`(约 150 行):会话行 + 行菜单。
2. `ConversationEmptyState.kt`(约 80 行)。
保留 section 骨架(约 280 行)。
**依赖顺序**:独立。**风险**:低但无单测护航——拆分需配合设备冒烟;建议与维度三的设计系统工作(会话行是 304 dp/71 sp 重灾区)同批做。

## 构建与依赖卫生

1. **版本目录全量 alias 审计(44 library + 6 plugin + 32 version):无孤儿**。逐个 accessor 计数(含嵌套点号)全部 ≥1;`jgit`(0 个 `libs.jgit` accessor)是特例——`config/jgit/reject-insecure-tls.gradle.kts:196-204` 经 `VersionCatalogsExtension.findLibrary("jgit")` 程序化引用并做 build-time 加固(替换 `NoCheckX509TrustManager` 为 fail-closed 实现)。
2. **kotlin 2.3.21 vs KSP 2.3.11 失配仍在**(`libs.versions.toml:3,10`;KSP 仅 :core:storage 应用,根 `build.gradle.kts:265`)。历史审查(2026-09-24,`reviews/2026-09-24-code-review.md:510-564` 关联项)已指出,未修。→ **对齐 KSP 到与 2.3.21 匹配的发布版**(P2;Room 编译路径,需重跑 :core:storage 全测试 + 锁文件刷新)。
3. **锁文件健康**:36 个 `gradle.lockfile`(每模块 + root + 3 spikes + testing),root 锁仅 detektCli;全锁文件扫描 **0 个坐标存在多版本**。
4. **根 `build.gradle.kts` 611 行集中定义 32 模块**("上帝化",历史 P2 仍在):
   - `if (path == ...)` 特判分支约 15 处(:core:storage、:runtime:proot-ipc/cli-client、:feature:files、:tools:android/automation/root、:feature:browser、:runtime:quickjs、3×spikes、:extensions:a2a/mcp/skills、:provider:api、:tools:framework、:runtime:proot-core、:testing);
   - androidTest 三元组重复 10 处(见 D6);
   - 坐标冲突改名 hack ×2(:tools:files `group="com.helix.tools"` :495-497、:tools:browser :232-234,均有注释说明原因——必要,但正是"集中定义"导致必须 hack 的症状)。
   - 建议:迁移到每模块 `build.gradle.kts`(32 个小文件)+ 2-3 个 convention 函数(`androidTestBaseline()`、`okhttpJvmSubstitution()`、`kotlinxJsonImpl()`)。成本中,收益:消除 15 处特判与 10 处三元组,新模块零中央脚本改动。
5. **jgit transform 硬编码坐标 3 个**(`reject-insecure-tls.gradle.kts:215-217`:`JavaEWAH:1.2.3`、`slf4j-api:2.0.18`、`commons-codec:1.22.1`)——已进 `app/gradle.lockfile`(第 251/258-259/442 行,锁健康),但**绕过版本目录**,升级时不会出现在 catalog diff 里。→ 收入 `libs.versions.toml`(P2,1 行×3)。
6. `app` R8 关闭(`isMinifyEnabled=false`,proguard-rules.pro 存在)——维度三/发布就绪度问题,此处仅记录。
7. `gradle.properties` 健康(4g 堆、parallel、caching、configuration-cache);spotless `misc` target 的 `.github/workflows/*.yml` 有效(`ci.yml` 存在,非死配置)。

## 过度设计与简化机会

1. **单实现接口**:对 core:{model,agent,policy,storage,workspace}、provider:api、tools:framework、extensions:* 的 public interface 抽样计数实现(生产+测试 fake):`ModelProvider`(4 生产实现 + 3 fake)、`Clock`(1 + **42** fake)、`CapabilityResolver`(1 + 6)、`WireClient`(1 + 10)、`ApprovalBroker`(1 + 7)、`AuditSink`(1 + 6)、`AgentRuntime`(1 + 2)、`ToolExecutor`(53 + 39)——**全部抽象都有测试 fake 消费,属合理可测性设计,非投机抽象**。
2. **真正的"投机层"只有一个**:`ToolSource`/`BuiltInToolSource`/`ToolSourceKind`(见 R9)——生产唯一构造 `ToolRegistry()` 传空列表。
3. **Canonical 层无重复**:`CanonicalArgs.kt`(50)+ `ToolSchemaCanonicalizer.kt`(53)仅 tools:framework 单点;其余文件(ToolDescriptor/ApprovalUiMapper/ChatToolCalls 等)是**消费方**,无第二份实现。
4. **wire limit 层健康**:cli-client/proot-ipc 的 MAX_* 常量全部被强制点引用(复核历史 §3.3d 结论"全量强制");唯一死 limit 常量仍是 R8 的 `CodexPayloadJob.MAX_PAYLOAD_BYTES`。
5. **功能开关:生产 0 个**(`buildConfigField` 0 处;唯一 `BuildConfig.DEBUG` 用于 :runtime:quickjs 的调试裂缝,AGP `buildConfig=true` 自动生成,合理)。
6. **更深的"过度设计"是投机性功能**(非接口):DeveloperRuntime/GoalSchedule/GoalForeground/ExecutionState/ToolExecutionEnvelope 都是"按设计文档实现、无调用方"。建议流程性改进:**新能力合入的验收项加一条"生产调用方存在 + 至少一条设备测试触达"**(呼应历史审查"四个静默失效"的共同模式)。
7. 生产 0 TODO/FIXME/HACK(AGENTS.md 合规);宽 catch 无系统性问题(未单列)。

## 测试债

1. **总量**:测试源集 145,134 行 vs 主源集约 141k 行(比值 ~1.0,健康;"测试=验收证据"是项目约定,`docs/development/verification-matrix.md`,不可轻删)。
2. **每模块**(main/test 行数):app 55,193/**74,731**(1.35)、core 22,527/18,397、runtime 23,150/16,506、tools 16,623/14,323、feature 13,095/9,269、provider 5,384/6,410、extensions 7,592/4,558、testing 0/105。
3. **关键类覆盖**(测试文件数):WorkspaceArtifactStore 35、ChatService 30、TurnCoordinator 25、BrowserController 12、ToolDispatcher 9、ProotJobRunner 6、McpOAuthClient 5、ProviderService 8、HelixMigrations 1(RoomMigrationFixtureTest)、DefaultAppContainer 1(仅装配级)、ApprovalUiMapper 1、**ConversationSection 0**、SignedConnectorIndexParser 0 直接(经 `SignedConnectorIndexVerifierTest` 包装覆盖,可接受但建议给 595 行解析器加直测)。
4. **绑定死代码的测试(可随 R1-R3 删除):约 1,618 行** = `TurnReducer*Test` 6 文件 894 + `TurnTestFixtures` 172 + `GoalScheduleTest` 132 + `GoalScheduleCodecTest` 89 + `DeveloperRuntimeTest` 228 + `GoalForegroundTest` 103(`RecoveryCoordinatorTest` 389 行部分相关,保留)。
5. **超大测试文件**:`AttachmentE2eDeviceTest.kt` 1,871、`RoomMigrationFixtureTest.kt` 1,592、`ToolDispatcherTest.kt` 1,444、`ToolSchedulerTest.kt` 1,119——建议各自按子场景拆 2-3 个文件(组织性,低优先)。
6. **androidTest(39,649 行,194 文件)vs androidTestDeveloper(19,172 行,95 文件):同名文件 0 个,无重复**(developer 测试全部独立命名)。

## 问题清单(按 P1/P2 排序)

**P1(显著拖慢开发/理解成本很高)**

| # | 问题 | 位置 | 改进 |
|---|------|------|------|
| P1-1 | core/agent 死代码族:M1 串行 Turn 模型 5 文件 + Goal 调度 3 文件 + DeveloperRuntime,生产 0 import,双状态机心智负担 | `core/agent/.../TurnReducer.kt:28` 等(见 R1-R3) | 删除(或 ADR 归档)+ 清 6 处 KDoc + 删 ~1,618 行测试;省 1,769 生产行 |
| P1-2 | SSE 读取器三份逐行重复(协议关键路径,bug 要修 3 次) | `ResponsesSse.kt:30-252` 等 3 文件 | 抽 `:provider:api` 共享 `SseEventStream`;省 ~400 行 |
| P1-3 | ChatService 上帝类 4,047 行/183 方法(历史 P0,优化维度为最高维护成本) | `app/.../chat/ChatService.kt:138` | 拆 6 个类型(见 3.1),留 ~800 行门面 |
| P1-4 | core/model 死代码:8 个 ID 值类 + ExecutionState + ToolExecutionEnvelope + ToolSource 接缝 | `Identifiers.kt:27-266`、`ExecutionState.kt`、`ToolExecutionEnvelope.kt`、`ToolSource.kt:35` | 删除 R4-R9;省 ~650 生产行 |

**P2(轻微)**

| # | 问题 | 位置 | 改进 |
|---|------|------|------|
| P2-1 | 3 个死 Adapter 类(历史发现,仍未删) | `OpenAiChatAdapter.kt` 等 3 文件 | 删 90 行,零风险 |
| P2-2 | 死 typealias `McpOAuthPkceChallenge` | `McpOAuthPkce.kt:57` | 删 1 行 |
| P2-3 | 死常量 `CodexPayloadJob.MAX_PAYLOAD_BYTES` | `CodexPayloadJob.kt:111` | 删 1 行 |
| P2-4 | kotlin 2.3.21 vs KSP 2.3.11 失配(历史发现,仍未修) | `libs.versions.toml:3,10` | 对齐 KSP + 刷新 :core:storage 锁 |
| P2-5 | 流式解码器骨架四重复(ProtocolViolation/failProtocol/MAX_TOOL_CALLS×3) | 3 个 StreamDecoder 文件尾 | 抽 `ProtocolSupport`+`ToolCallAccumulator`;省 ~150-200 行 |
| P2-6 | e.message 直传用户可见状态 14 处(历史 ~10 处,复核后增加) | `FileManagerService.kt:470-575` 等(见 D4 表) | 抽 `ErrorMessages.kt` 安全映射 |
| P2-7 | 根 build.gradle.kts 611 行上帝化:15 处特判 + 10 处 androidTest 三元组 + 2 处 group hack | `build.gradle.kts` | 每模块 build 文件 + convention 函数 |
| P2-8 | okhttp-jvm substitution ×3 | `app/build.gradle.kts:188`、根 :443、:463 | 抽共享 apply 脚本 |
| P2-9 | jgit transform 3 个硬编码坐标绕过版本目录(已入锁) | `reject-insecure-tls.gradle.kts:215-217` | 收入 catalog |
| P2-10 | 未用字符串 9 key ×3 语言(其中 connector_update_* 5 key 暗示升级 UI 未接线) | `res/{values,values-en,values-zh-rCN}/strings.xml` | 删 27 行或接线 UI |
| P2-11 | desktopMode 全局死偏好(读不写) | `BrowserStorage.kt:247,267`、`BrowserMenuSheet.kt:204` | 删偏好或接设置开关 |
| P2-12 | `.workbuddy-ai/`(24K agent 缓存)未忽略,.claude 同类问题已本地 exclude | 仓库根 | 补 `.gitignore`/本地 exclude |
| P2-13 | 中大文件:ToolDispatcher 1,174 / ProotJobRunner 942 / BrowserController 877 / DefaultAppContainer 766 | 见 3.2-3.4、3.7 | 按节内方案拆 |
| P2-14 | ConversationSection 515 行单 Composable + 0 直接测试 | `app/.../ui/ConversationSection.kt:46` | 拆 row/empty + 补设备冒烟;随设计系统 |
| P2-15 | scripts/debug 重复:`clean-packages.py` 两份相同 | `scripts/debug/2026-09-17/{hxa194,p0}/` | 留一份,另一份归档 |
| P2-16 | 超大测试文件 4 个(1,119-1,871 行) | `AttachmentE2eDeviceTest.kt` 等 | 按场景拆(组织性) |
| P2-17 | flavor 接缝风格不一致(同名 object ×6 vs 接口 ×1) | `app/src/{consumer,developer}` | 新接缝统一走 main 接口模式 |

**合计:21 条(P1×4,P2×17)。**

## 历史审查"维度五"发现的状态复核

| 历史发现(reviews/2026-09-24/2026-09-24-code-review.md §维度五 / REVIEW-2026-09-24.md §3.3d) | 现状(基线 3cf89027 + 工作树) |
|---|---|
| 仓库卫生优秀(3435 文件/36M/build 0 入库) | **成立**。复核:36 lockfile、0 多版本坐标、0 TODO、i18n 1,363×3 一致、res 全引用 |
| 3 个死 Adapter 类,建议第一批删除 | **仍未删,结论成立**(各 1 引用=自身;`OpenAiChatProvider` 绕过) |
| 8 个仅测试引用的 ID 值类 | **成立,数量维持 8**(Identifiers.kt 现 24 个值类,TurnId/ModelCallId/ToolCallId/SessionId/ApprovalId 5 个已在用) |
| 1 个死 typealias(McpOAuthPkce.kt:57) | **仍未删,结论成立** |
| :testing 孤儿模块,建议删除 | **状态变化**:仍孤儿,但 2026-09-22 owner 裁决明确保留(`docs/evidence/development/repository-hygiene-2026-09-22.md:10` + `scripts/README.md`)→ 改判"接受债务" |
| scripts/debug 87% 惰性归档,718 文件 | **纪律执行良好,owner 声明不整删**:18 日期目录 + manifest + README,697 跟踪文件;仅 `clean-packages.py` 重复 2 份可动 |
| 重复 SSE 契约注释/3 reader 逐行相同(省 ~400 行) | **成立并量化**:两 reader 类体 diff 仅 21 行;方案不变(抽 :provider:api) |
| KSP/kotlin 版本失配(toml:3,10) | **仍未修**:kotlin 2.3.21 vs ksp 2.3.11 |
| CodexPayloadJob.MAX_PAYLOAD_BYTES 死常量(proot 侧零引用) | **复核成立**:全仓 .kt/.kts/.xml 0 引用(仅审查文档提及),仍在 :111 |
| TurnReducer 生产未用(维度二 P1,"双 Turn 状态机") | **复核成立并扩大**:Turn* 5 文件整族 + GoalSchedule/GoalScheduleCodec/GoalForeground + DeveloperRuntime 均生产 0 import;绑定测试 ~1,618 行(历史估计"~2000 行"偏保守于整族口径,基本吻合) |
| .claude/ 未入 .gitignore | .claude 已有本地 exclude;**同类问题再现于 `.workbuddy-ai/`**(git status 可见,无任何 ignore) |
| desktopMode 死偏好(REVIEW §2.3) | **仍未修**:`preferences.desktopMode` 仍无写点 |

## 精简路线图(按 收益/成本 排序)

1. **【1 天,零风险】一次性死代码清理**(P2-1/2/3/10/11 + P1-4):删 3 Adapter(90)+ typealias(1)+ MAX_PAYLOAD_BYTES(1)+ 8 ID 值类(120)+ ExecutionState/envelope(228)+ ToolSource 接缝(70)+ 9 字符串 key(27)+ desktopMode(10)≈ **550 行**,每步独立 commit,grep 证据已备。
2. **【1-2 天,低风险】构建卫生**:KSP 对齐 kotlin(P2-4);okhttp-jvm substitution 抽共享脚本(P2-8);jgit 3 坐标入 catalog(P2-9);`.workbuddy-ai/` 忽略(P2-12)。
3. **【2-3 天,中风险,单点收益最高】SSE 共享解析器**(P1-2 + P2-5):`:provider:api` 落 `SseEventStream` + `ProtocolSupport`/`ToolCallAccumulator`,3 reader + 3 decoder 改薄映射,省 ~550-600 行,协议 bug 单点修复;护航 = 6 个现有 SSE/decoder 测试全绿。
4. **【3-5 天,中风险】core/agent 死族处置**(P1-1):owner 决策删/归档;删除路径省 **1,769 生产 + ~1,618 测试** 行,清 6 处 KDoc;若归档则必须"移出编译 + 删测试 + 标注"三件事同时做。
5. **【1-2 周,高风险高收益】ChatService 六型拆分**(P1-3):按 3.1 顺序(ChatScreenState→InputQueue→Submission→TurnLifecycle→Lifecycle→TaskControl),每步单独提交 + 测试簇回归;同步裁决 turnGate 锁与 synchronized-事务(P1 历史项)。
6. **【1 周】装配与调度拆分**:DefaultAppContainer 三抽(3.7,低)+ ToolDispatcher 五抽(3.2,低中)。
7. **【持续,随功能批次】ProotJobRunner/BrowserController/TurnCoordinator/ConversationSection 拆分**(3.3-3.5、3.10);TurnCoordinator 拆分前必须先做维度四 P1(`requireBatchSettled`)修复。
8. **【可选,需 owner 批准】scripts/debug 旧日期目录归档**(owner 已声明不整删,仅建议把 09-05~09-10 的 424 个文件压为 .txt 惰性归档,跟踪文件数 -300+)。

---
*本报告基于 HEAD 3cf89027 + 工作树的静态取证(grep/find/diff/wc/git log),未运行构建或测试;所有"可移除"结论的 grep 证据已在上文逐条给出。审查过程未修改仓库任何文件。*
