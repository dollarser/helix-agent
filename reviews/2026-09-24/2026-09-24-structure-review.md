# Helix 结构与内聚性补充审查

**基线**：HEAD `3cf89027`，工作树含 8 个未提交修改
**范围**：本轮只补两块此前未覆盖的内容——**文档落地状态**与**结构性审查**（架构设计、目录结构、类内聚/耦合、文件拆分重组）
**方法**：对 1690 个生产类/对象做静态度量（方法数、构造依赖数、关注点跨度、fan-in），并对冲突项回源码复验

---

## 〇、文档落地状态（先回答"有没有补充到文档"）

### 结论：**没有进 `docs/`，两份报告都在 `reviews/`**

| 产物 | 位置 | 是否被项目文档体系引用 |
| --- | --- | --- |
| 五维度综合审查 | `reviews/2026-09-24/2026-09-24-code-review.md` | ❌ 无 |
| 再评估 | `reviews/2026-09-24/2026-09-24-review-reevaluation.md` | ❌ 无 |
| 本补充审查 | `reviews/2026-09-24/2026-09-24-structure-review.md` | ❌ 无 |

**当时的理由**：`scripts/check-docs.sh` 会校验 `docs/**/*.md` **与根目录 `*.md`** 的本地链接、必需条目清单（`required_doc_entries`）以及完成记录索引一致性。在未确认归属前写入，可能让文档门禁变红。

**但这个理由现在站不住了**——项目已有明确的落位惯例：`docs/README.md:12` 的"项目结构与执行引擎审查"一行，指向 `docs/research/project-structure-and-engine-review.md` 与 `docs/research/execution-engine-deep-review-2026-09-22.md`。本报告正属于该行的范围。**建议落位见 §五。**

### ⚠️ 更重要的发现：我的架构建议与现有已接受决定有 3 处冲突

现有 `docs/research/project-structure-and-engine-review.md`（基线 `9a9b25dd`，2026-09-22）已对结构治理作出决定。我此前**没有读到它**，导致三条建议与已接受立场直接抵触：

| # | 现有决定（原文） | 我的建议 | 裁决 |
| --- | --- | --- | --- |
| 1 | `:7`「**文件行数不是新建Gradle模块的充分理由**」 | 「把 `chat`/`agent`/`provider` 等按职责下沉为 `:feature:*` 或 `:app-*` 子模块」 | **我的表述有错，需修正**。该文档同一段已明确认可「在app内逐步分离运行资源/终局协调与页面、草稿适配」——即**模块内分解**是被接受的，**新增 Gradle 模块**才是被拒绝的。本报告 §四 已全部改写为模块内文件/类分解，不含新增 Gradle 模块 |
| 2 | `:14`「TurnReducer及测试：把参考串行模型与生产BatchTurnRuntime**覆盖清楚区分**；先映射并补齐生产不变量测试，再考虑移动参考代码；**不删测试换绿**」 | 「删除 `TurnReducer` 及约 2000 行测试」 | **我的建议与已接受决定冲突，撤回**。且更值得注意的是：该文档的处方（"先映射并补齐生产不变量测试"）**尚未执行**——而 P0 恢复缺陷恰恰是"生产不变量未被测到"的直接产物。**这条 P0 是该处方应当尽快执行的新证据，而不是绕开它的理由** |
| 3 | `:17`「组合根集中装配依赖是合理职责，**不因DefaultAppContainer较大就引入Service Locator**」 | 「`DefaultAppContainer` 是 ServiceLocator，建议拆成多个小工厂」 | **部分冲突，需收窄**。文档反对的是"因为大就改造"，我认同——**大小本身不是问题**。我真正要指出的是 `:437` 的**可变持有者** `ApprovalCardSinkHolder`：它被用来打破构造环，是环依赖的症状而非规模的症状。建议收窄为"消除可变持有者"，不提"拆分容器" |

**另有一条我自己的建议需保留但降级**：`:19` 明确 QuickJS isolated UID 与 PRoot 私有同 UID 是**已接受的不同执行域**，不得合并为"统一沙箱"。我此前提到的 `:runtime:proot-*` 四模块合并建议因此应维持"暂缓"（我原报告已如此标注）。

**行动建议**：`docs/research/project-structure-and-engine-review.md` 的基线是 `9a9b25dd`，当前 HEAD 是 `3cf89027`。建议在并入本报告结论时，**同步刷新该文档的基线并明确标注"§四 的模块内分解建议不与 §7 的'不新增模块'立场冲突"**。

---

## 一、架构设计评估

### 1.1 分层声明与实际的一致性：良好

`docs/architecture/overview.md:9` 声明 UI/feature 层"不承担：直接访问 DAO、网络客户端或执行器"。实测：

| 检查项 | 结果 |
| --- | --- |
| `app/.../ui/` 与 `feature/` 下 import `androidx.room` / `retrofit2` / `okhttp3` / `runtime.*` | **0 命中** ✅ |
| `feature` 模块依赖表是否含 `:core:storage` | **不含**（结构上无法访问 DAO）✅ |
| `:core:model`/`:agent`/`:policy`/`:workspace` 是否保持纯 JVM | **是**（仅 `core:storage` 依赖 `Context`，属数据层合理例外）✅ |
| 是否有绕过 `ToolDispatcher` 的工具执行路径 | **无**（生产仅 `DefaultAppContainer.kt:494` 构造一次）✅ |

**分层声明可信，是这份代码库最扎实的部分之一。**

### 1.2 但存在三条反向依赖（P1，维持原结论）

```
core:storage    ──→ core:policy         ⚠️ 数据层 → 策略层
runtime:quickjs ──→ tools:framework     ⚠️ runtime → tools
feature:browser ──→ tools:browser       ⚠️ feature → tools
```

**为何是问题**：它使"core 可独立于 Android 基础设施"与"tools 是最底层执行层"两个声明无法被机械验证。
**修法（低风险、收益最高）**：把被跨层引用的契约（`ToolDescriptor`、`SessionPermissionConfig` 相关类型）上提到 `core:model`，一次切断三条边。**这不需要新增模块，与 §0 的冲突项 1 无关。**

### 1.3 扩展成本实测（可扩展性硬指标）

| 新增 | 需改文件数 | 主要改动点 |
| --- | --- | --- |
| **Tool** | **2–4** | ①`tools/<x>/` descriptor+executor ②register 函数 ③`DefaultAppContainer.kt:362-429` 加 1 行 ④（若新模块）根脚本 map |
| **Provider** | **6+** | ①`core/model/.../ProviderProtocol.kt`（改枚举）②根 `build.gradle.kts` 两处 map ③`provider/<x>/` 适配器 9–11 文件 ④`app/build.gradle.kts:123-127` ⑤`ProviderFactory.kt:44-48`（`when` 分支 + 3 个 ImageResolver）⑥`provider/catalog` 模板 |
| **MCP 传输** | **2–3** | ①`SdkMcpClientFacade.kt:34`（传输硬编码 `StreamableHttp`）+ 新 Transport ②app 侧 wiring |

**结论**：Tool 扩展成本**健康**。Provider 的 `when(enum)` 扩散是**抽象不足**（封闭枚举 + 工厂分支 + 核心枚举改动三处联动）。MCP 传输选择**硬编码在 facade**，扩展需改核心类。

### 1.4 架构设计总评

| 维度 | 评价 |
| --- | --- |
| 分层清晰度 | **好** —— 声明与实现一致，违规仅 3 条且修法明确 |
| 依赖方向 | **中** —— 三条反向边 |
| 可扩展性 | **中** —— Tool 好、Provider 差 |
| 模块粒度 | **中** —— 32 个模块但 46% 代码在 `:app` |
| 执行域边界 | **好** —— QuickJS/PRoot/订阅三个执行域的隔离声明经 manifest 复验成立 |

---

## 二、目录结构合理性

### 2.1 实测数据

**包声明与目录不一致**：仅 **2 处**，且都在 `scripts/debug/2026-09-16/isolated-proot-spike/fixtures/`（已归档的一次性 spike 夹具）。
→ **主源码树的包/目录一致性是 100% 干净的。** 这一点优于绝大多数同规模工程。

**各模块生产文件数**：

| 模块 | 文件数 | 模块 | 文件数 |
| --- | --- | --- | --- |
| `app` | **427** | `runtime/quickjs` | 21 |
| `core/storage` | 109 | `runtime/cli-client` | 21 |
| `runtime/cli-app` | 48 | `tools/android` | 19 |
| `feature/browser` | 43 | `tools/automation` | 17 |
| `core/model` | 42 | `tools/files` | 16 |
| `runtime/proot-app` | 34 | `extensions/skills` | 14 |
| `runtime/proot-core` | 34 | `provider/api` | 14 |
| `core/agent` | 25 | `runtime/proot-ipc` | 14 |
| `feature/files` | 24 | `core/workspace` | 11 |
| `core/policy` | 22 | `tools/browser` | 10 |
| `tools/framework` | 22 | `extensions/a2a` | 7 |
| `extensions/mcp` | 21 | `provider/*` | 6/5/5/2 |

### 2.2 问题 1（P1）：`app/ui` 是 106 文件的扁平包

**实测**：`app/src/main/kotlin/com/helix/app/ui/` 下有 **106 个 `.kt`**，仅有一个子包 `composer/`（2 文件）。即 **104 个文件平铺在一个包里**。

**为何是问题**：包是 Kotlin 唯一的可见性组织单位，106 文件平铺使"这个文件属于会话 UI、那个属于文件管理器 UI、那个属于设置"完全不可见；新成员无法通过目录导航，只能靠全文搜索。

**建议的子包划分**（按现有文件名可无歧义归类）：

```
app/ui/
├── conversation/   ChatScreen, ConversationSection, ConversationMessage,
│                   ConversationTimeline, ConversationSearch, ConversationComposer,
│                   ConversationDraftBuffer, ConversationIntents, ConversationSheet,
│                   ConversationArtifacts, ConversationModeControls, AdaptiveConversationHeader,
│                   EmptyConversationHint, ThinkingAccordion, MarkdownText, ...
├── files/          FilesScreen, FilesScreenState, FilesScreenActions, FilesHome,
│                   FilesLocationBar, FilesImportDialog, FilesExportDialog,
│                   FilesMutationDialogs, FilesScreenComponents, FilesScreenLayout,
│                   FilePreviewState, FileLocationCard, ...
├── settings/       SettingsScreen, SessionPermissionSection, SessionPermissionCustomEditor,
│                   ProviderFormDialog, ThemeSection, LanguageSection, ...
├── extensions/     ExtensionsScreen, ConnectorSection, SkillInstallationSection,
│                   MarketplaceSection, McpSection, ...
├── tasks/          TasksScreen, TasksDashboard, TaskLedgerCard, BackgroundJobControls,
│                   BackgroundJobRow, BackgroundTaskDialog, ...
└── common/         CompactPageHeader, ExpandableSummary, DisclosureDialog,
                    EmptyDestination, ContextWindowIndicator, ...
```

**成本**：纯移动文件 + 改 `package` 声明 + 修 import。**无逻辑改动，无行为风险**，可由脚本半自动完成（IDE 的 Move 重构）。
**收益**：`app/ui` 从 1 个 106 文件包变为 6 个 ~15–25 文件包。

### 2.3 问题 2（P2）：`app/chat` 59 文件混合了 UI 模型与业务编排

**实测**：`app/chat/` 下同时存在
- 业务编排：`ChatService.kt`、`ChatToolCalls.kt`、`ChatSubmission.kt`、`BudgetContinuation.kt`、`TurnCoordinator`（在 `agent/`）
- UI 模型：`ChatUiModels.kt`、`ArtifactRowUi.kt`、`ChatStatusLabels.kt`、`ChatScreenProjection.kt`

`ChatUiModels.kt` 定义 `SessionRowUi`/`MessageUi`/`TurnUi`/`ChatScreenState` —— 这些是**给 Compose 消费的 UI 模型，却在 `chat/` 而非 `ui/`**。

**建议**：把 `*Ui.kt`/`*UiModels.kt`/`*Projection.kt`（投影层）归入 `app/ui/conversation/` 或新建 `app/ui/projection/`。这与 §0 冲突项 1 无关——**是包内归类，不是模块拆分**。

### 2.4 问题 3（P2）：flavor 平行层次重复 9 组

`consumer`/`developer` 源集各有一份同名文件（`DistributionModuleRegistry`、`AllFilesModule`、`AutomationModule`、`AdvancedProfileAvailability`、`ProotToolModule`、`SubscriptionProviderModule`、`RootModule`、`ManualTerminalModule`）。

**这是有意的编译期 flavor 切换**（`README:5`、`requirements.md:105-108` 声明，`build.gradle.kts:24-32` 实现），**不是缺陷**。但值得注意：`app/proot` 相关文件在 `main`(14) / `developer`(25) / `consumer`(1) / `developerDebug`(1) 四处分布，共 41 个源集文件——**新增一个 PRoot 能力要同时改多个源集**，是扩展成本的一部分。

### 2.5 问题 4（P2）：两处同名类造成阅读歧义

| 同名 | 位置 A | 位置 B | 性质 |
| --- | --- | --- | --- |
| **`TurnState`** | `core/model/.../TurnState.kt:37` → `enum class TurnState`（阶段枚举） | `core/agent/.../TurnState.kt:103` → `data class TurnState`（运行时状态容器） | **真冲突**。`core/agent/TurnState.kt:13` 已被迫写 `import com.helix.core.model.TurnState as TurnPhase` —— **团队自己已经感到痛了** |
| **`SkillRepository`** | `core/storage/.../repository/SkillRepository.kt:6`（Room/DAO 后端，被 `HelixStorage.kt:161` 使用） | `extensions/skills/.../SkillRepository.kt:43`（文件系统后端，被 app 全面使用） | **真冲突，两份都在用** |
| `ProtocolViolation` | `provider/anthropic/.../AnthropicStreamDecoder.kt` | `provider/openai-chat/...`、`provider/openai-responses/...` | 三份平行 SSE 解码器的产物（见 §四.3） |
| `SnapshotNode` | `feature/browser/.../snapshot/BrowserSnapshot.kt` | `tools/automation/.../AutomationSnapshotEngine.kt` | 两个领域各自的节点模型，可接受 |

**建议**：
- `core/agent/TurnState.kt` 的 `data class TurnState` → 改名 `TurnRuntimeState`（文件同改）
- `core/storage/repository/SkillRepository` → 改名 `SkillRowRepository`，或 `extensions/skills/SkillRepository` → `SkillSnapshotRepository`（后者更贴其实质：它管 snapshots/state/trash 三目录）

### 2.6 目录结构总评

| 维度 | 评价 |
| --- | --- |
| 包/目录一致性 | **优** —— 主源码树 100% 一致 |
| 顶层模块划分 | **良** —— `core/provider/tools/runtime/feature/extensions` 方向清晰 |
| 包内组织 | **差** —— `app/ui` 104 文件平铺是最大问题 |
| 命名唯一性 | **中** —— 2 处真同名冲突 |
| flavor 组织 | **良** —— 有意设计，成本已记录 |

---

## 三、类职责：内聚与耦合（量化）

### 3.1 低内聚候选（关注点跨度 = 公开方法名横跨的关注域数）

| 类 | 公开方法 | 构造依赖 | 关注点跨度 | fan-in | LOC | 关注点分布 |
| --- | --- | --- | --- | --- | --- | --- |
| **`ChatService`** | **113** | **40** | **7** | 37 | 4048 | session:65, tool:9, persist:9, runtime:7, ui:5, file:4, network:3 |
| **`BrowserController`** | **59** | 13 | **5** | 10 | 878 | persist:11, ui:3, network:2, tool:1, session:1 |
| `ApprovalUiMapper` | 17 | 1 | 5 | 7 | 495 | ui:3, tool:2, network:1, ext:1, file:1 |
| `PrivacyDeletionService` | 10 | — | 5 | — | — | persist:6, session:3, ext:3, file:3, network:1 |
| `ConnectorService` | 21 | 10 | 4 | 10 | 352 | network:8, ext:2, persist:1, session:1 |
| `FilesScreenActions` | 19 | 7 | 4 | 12 | 403 | file:4, persist:1, network:1, runtime:1 |

**判读**：

- **`ChatService`：严重低内聚。** 113 个公开方法、40 个构造依赖、7 个关注域，且关注点分布**极不均衡**（session 类 65 个 vs network 类 3 个）——说明它本质是"会话域的超大聚合根"，其他关注点是被历史堆进来的。这是**全项目唯一的单点，也是最高优先级**。
- **`BrowserController`：中度低内聚，且有一处明确错位。** 一个 **Controller** 里 `persist:11` —— 控制器在做持久化。`BrowserStorage.kt` 已存在，说明持久化逻辑被部分外提但**没提干净**。这是"低内聚"里最容易修的一条。
- `ApprovalUiMapper` / `ConnectorService` / `FilesScreenActions`：跨度 4–5 但方法数少（10–21），属"薄但杂"，可接受；`FilesScreenActions` 的问题不是内聚而是**位置错**（见 §四.4）。

### 3.2 高耦合候选（构造依赖数）

| 类 | 构造依赖 | 公开方法 | fan-in | 判读 |
| --- | --- | --- | --- | --- |
| **`ChatService`** | **40** | 113 | 37 | 同上，严重 |
| `DefaultAppContainer` | 38 | 0 | 2 | **组合根，集中装配是合理职责**（见 §0 冲突项 3）。真正的问题是 `:437` 的**可变持有者**破环，非依赖数 |
| `HelixStorage` | 38 | 4 | **96** | 数据层门面，依赖多但职责单一（持有各 Repository）。**可接受**，但 96 的 fan-in 意味着它是全项目最脆弱的数据契约点 |
| `ConversationIntents` | **36** | **0** | 5 | **⚠️ 36 个字段、0 个方法的数据包** —— 高耦合且零内聚，典型"参数对象膨胀"。建议按 UI 区域拆为 `ConversationIntents` / `ComposerIntents` / `SessionIntents` |
| `EgressDisclosure` | 35 | 2 | 6 | 同上，35 字段 2 方法 |
| `FileManagerService` | 35 | 25 | 20 | 服务类，依赖偏多 |
| `AppContainer` | 31 | 0 | 8 | 接口，字段多是设计（见 §0 冲突项 3） |

### 3.3 高 fan-in（改动风险最高）

| 类 | fan-in | LOC | 说明 |
| --- | --- | --- | --- |
| `Json`（`core/model/internal/Json.kt`） | **103** | 398 | 全项目最被依赖的类型。**任何签名改动波及 103 个文件** |
| `HelixStorage` | **96** | 311 | 数据门面 |
| `Goal`（`core/agent/Goal.kt`） | **83** | 131 | 领域模型 |
| `ToolDescriptor` | **68** | 207 | 工具契约 |
| `ToolExecutorResult` | 63 | 192 | 工具结果 |
| `ToolRegistry` / `ToolImplementationRegistry` | 62 / 59 | — | 注册表 |
| `FileScopePath` | 57 | 150 | 路径类型 |
| `RiskLevel` / `ToolOperationClass` | 55 / 52 | 79 / 79 | 风险枚举 |
| `WorkspaceArtifactStore` | 45 | 780 | 同时是"高 fan-in"与"长文件"，**双重风险** |

**判读**：`Json`、`RiskLevel`、`ToolName`、`ToolVersion` 等高 fan-in 类都**很小**（27–398 行）且职责单一 —— 这是**健康的**高 fan-in（基础类型被广泛复用）。真正需要警惕的是 **`WorkspaceArtifactStore`**：780 行 + 45 fan-in + 20 公开方法，**长、被广泛依赖、且职责多**，三者叠加。

### 3.4 内聚/耦合总评

| 维度 | 评价 |
| --- | --- |
| 高内聚类占比 | **高** —— 1690 个类中，跨度 ≤3 且方法 ≤10 的占绝大多数 |
| 基础类型设计 | **优** —— 高 fan-in 类普遍小而单一，是健康复用而非耦合 |
| 单点低内聚 | **1 个严重**（`ChatService`）+ **1 个明确**（`BrowserController` 的 persist 错位） |
| 参数对象膨胀 | **2 个**（`ConversationIntents` 36 字段、`EgressDisclosure` 35 字段） |
| 过度抽象 | **未发现** —— 单实现接口（`ShellRepository`/`LineStore`/`SecretStore` 等）均为**测试缝**，属合理用法 |

**一句话结论**：这份代码库的类设计整体是**高内聚低耦合**的，问题高度集中在 `:app` 的少数几个类上，而非全局性的设计失败。

---

## 四、文件拆分重组清单

> **前提声明**：以下全部为**模块内**的文件/类分解，**不含任何新增 Gradle 模块的建议** —— 与 `docs/research/project-structure-and-engine-review.md:7`「文件行数不是新建Gradle模块的充分理由」一致。

### 4.1 必须拆（按优先级）

| # | 文件 | 现状 | 拆分方案 | 风险 |
| --- | --- | --- | --- | --- |
| 1 | `app/chat/ChatService.kt` | **4048 行 / 113 方法 / 40 依赖 / 7 关注域** | 抽 9 个组件：`ChatStateHolder`（状态持有）、`SessionCatalogService`（会话 CRUD/搜索/fork）、`ComposerDraftService`（草稿）、`AttachmentStagingCoordinator`（附件暂存）、`SubmissionAdmission`（准入/egress）、`TurnLauncher`（launchTurn/runTurn/terminalize）、`SessionInputQueueService`（输入队列）、`GoalContinuationController`（Goal 续跑）。多数已有 lazy 协作者，抽离成本可控。目标 <800 行 | 高（主链路） |
| 2 | `tools/framework/ToolDispatcher.kt` | 1175 行 / 4 类型 | 按权限、执行、结果校验提取**内部阶段数据**（现有文档已认可该方向）。**保留**统一入口、执行前授权重读、审批消费、每 attempt 审计 | 高（授权路径） |
| 3 | `feature/browser/BrowserController.kt` | 878 行 / 59 方法 / **persist:11** | ①把持久化逻辑彻底移入 `BrowserStorage.kt`（**这是唯一一处"控制器做持久化"的明确错位**）②把同文件内的 `FindInPageState`（59 方法！）拆出独立文件 | 中 |
| 4 | `core/workspace/WorkspaceArtifactStore.kt` | 780 行 / 9 类型 / 45 fan-in / 20 方法 | 按操作族拆：`ArtifactStat`（StatInfo/ListResult/SearchResult）、`ArtifactWrite`（WriteOutcome/CopyMoveOutcome）、`ArtifactTrash`（TrashEntry/TrashRestoreOutcome/PurgeOutcome）。**长 + 高 fan-in + 多职责三者叠加，风险最高** | 中高 |
| 5 | `app/agent/TurnCoordinator.kt` | 682 行 / 8 类型 | 拆出 `BatchTurnRuntime`（`TurnCoordinator.kt:65-147`）、`TurnMessageDraft`/`TurnStartSpec`/`TurnSteeringDraft` 三个数据类到 `TurnModels.kt` | 中 |
| 6 | `app/files/FileManagerService.kt` | 647 行 / 25 方法 / 35 依赖 | 按 `FileSource`/`SortKey`/`ConflictPolicy` 枚举与 `BatchResult`/`FileEntry` 结果类型外提，本体只留服务方法 | 中 |
| 7 | `app/ui/CapabilitiesScreen.kt` | 511 行 / 4 类型 | UI 拆子组件 + 状态外提 | 低 |
| 8 | `app/ui/SessionInputQueuePanel.kt` | 454 行 / 2 类型 | 同上 | 低 |

### 4.2 可抽象（最高性价比的结构性重构）

**`runtime/cli-app` 有 5 份结构高度平行的 OAuth 实现，共约 1585 行**

| 文件 | LOC | Attempt | Poll | Protocol | Transport | OkHttp*Transport | LoginController | 异常类 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `CodexDeviceOAuth.kt` | 323 | ✅ | ✅ sealed | ✅ object | ✅ | ✅ | ✅ | 3 |
| `CodexOAuth.kt` | 322 | ✅ | — | ✅ | ✅ | ✅ | ✅ | 1 + `BoundedDnsCache` |
| `ClaudeOAuth.kt` | 296 | ✅ | — | ✅ | ✅ | ✅ | ✅ | 2 |
| `GrokDeviceOAuth.kt` | 357 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 2 |
| `CopilotDeviceOAuth.kt` | 287 | ✅ | ✅ sealed | ✅ object | ✅ | ✅ | ✅ | 1 + `DeviceLoginCancellation` |

全部为 `internal`（模块内可见），形状完全一致：`<P>Attempt` / `<P>Poll`(sealed) / `<P>Protocol`(object) / `<P>Transport`(interface) / `OkHttp<P>Transport` / `<P>LoginController`。

**建议**：抽 `DeviceCodeOAuthEngine`（状态机 + 轮询 + 退避 + 取消），各 provider 只保留**配置**（端点、clientId、scope、响应字段映射）与**少量特有逻辑**（如 `BoundedDnsCache`、`DeviceLoginCancellation`）。预计 1585 行 → 引擎约 350 行 + 5 个 ~80 行配置，**净减约 850 行**。

**⚠️ 需所有者确认**：`ADR-PROVIDER-002` 允许"显式标注的第三方协议适配器"各自获取与刷新 OAuth 授权。本建议**不改变该边界**（适配器仍各自持有自己的 grant、端点与凭据），只共享**无状态的协议机制**。若所有者认为"各自独立实现"本身就是该 ADR 的意图（便于逐家标注与审计），则本条应撤回。

### 4.3 应合并/统一（消除三份平行实现）

| 对象 | 证据 | 收益 |
| --- | --- | --- |
| **三份 SSE 解析器** | `ResponsesSse.kt`(258) 与 `AnthropicSseReader.kt`(262) 的 UTF-8 增量解码（`decodeStep`/`assembleCodePoint`/`sequenceLength`/`minCodePoint`）、`processLineBreaks`、`feed`/`finish`、`SseEvent` **逐行相同**；`ChatSseReader.kt`(242) 为 data-only 变体。三者各自定义了自己的 `ProtocolViolation`（§2.5） | 762 行 → 公共约 250 行 + 3 个薄映射，**净减约 400 行**；且三处协议 bug 只需修一次。**风险中（协议关键路径），需现有 `*SseReaderTest`/`*SseParserTest` 全绿护航** |
| 三份 `ProtocolViolation` | 上述产物 | 随 SSE 抽取自然消除 |

### 4.4 应移动（位置错，非内聚问题）

| 文件 | 现状位置 | 建议位置 | 理由 |
| --- | --- | --- | --- |
| `app/ui/FilesScreenActions.kt` | `ui/` | `app/files/` | 已复验：它持有 `FileManagerService`、`SafTreeScopeService`、`CoroutineScope`、`Context`、`Resources`，并在 `:53` 用 `withContext(Dispatchers.IO)` 做 IO。**这是事件处理器/业务胶水，不是 UI** |
| `app/ui/FilesScreenState.kt`、`FilePreviewState.kt` | `ui/` | `app/files/` | 同上（文件域状态） |
| `app/chat/ChatUiModels.kt`、`ArtifactRowUi.kt`、`ChatStatusLabels.kt` | `chat/` | `ui/conversation/` | 定义 `MessageUi`/`TurnUi`/`ChatScreenState` —— **给 Compose 消费的模型却在 chat/** |
| `app/chat/ChatScreenProjection.kt` | `chat/` | `ui/projection/`（或保留） | 投影层，是 UI 与持久化的边界，位置可辩 |
| `app/chat/ConversationIntents.kt` 的 36 字段 | `ui/`（已在此） | 按区域拆 3 份 | 见 §3.2 |
| `scripts/debug/2026-09-16/.../fixtures/*.kt`（2 文件） | 包声明 `com.helix.app.proot` 与目录不符 | 修正包声明或移入 `spikes/` | 全项目仅此 2 处包/目录不一致 |

### 4.5 应重命名

| 对象 | 现名 | 建议 | 理由 |
| --- | --- | --- | --- |
| `core/agent/.../TurnState.kt:103` | `data class TurnState` | `TurnRuntimeState` | 与 `core/model/.../TurnState.kt:37` 的 `enum class TurnState` 同名冲突；`core/agent/TurnState.kt:13` 已被迫用 `as TurnPhase` 别名 |
| `core/storage/.../repository/SkillRepository.kt:6` | `SkillRepository` | `SkillRowRepository` | 与 `extensions/skills/SkillRepository.kt:43` 同名冲突，两者**都在使用** |
| `DefaultAppContainer.kt:114-123` | `FakeShellRepository` | `StaticShellRepository` | 生产代码用 `Fake` 前缀（`Fake` 惯指测试替身） |

### 4.6 不应拆（反直觉，避免误改）

以下文件类型数多但**有明确内聚**，是 Kotlin 的惯用组织方式，**不建议拆分**：

| 文件 | 类型数 | 为何保留 |
| --- | --- | --- |
| `core/model/.../Identifiers.kt` | 23 | 全部是 `<X>Id` 值类，同一模式、同一用途。拆成 23 个文件只会增加导航成本 |
| `core/storage/.../entity/ConversationEntities.kt` | 11 | 全部是会话域 Room 实体，同表族 |
| `tools/automation/.../AutomationActions.kt` | 12 | 同一命令族的状态/结果密封类型 |
| `tools/browser/.../BrowserToolBridge.kt` | 20 | 同一工具桥的 Outcome 密封类型（但 228 行里塞 20 个类型，可考虑移入 `BrowserToolOutcomes.kt` 之类同包文件，非必须） |
| `core/agent/.../RecoveryCoordinator.kt` | 9 | 恢复域的全部类型，高内聚（本报告 P0 缺陷在此，但**问题是逻辑不是组织**） |
| `extensions/a2a/.../A2aTaskModels.kt` | 9 | A2A 任务模型族 |

### 4.7 拆分重组总评

| 类别 | 数量 | 预计净变化 |
| --- | --- | --- |
| 必须拆的文件 | 8 | 净增约 25 个文件，`ChatService` 从 4048 → <800 行 |
| 可抽象的重复 | 2 组（OAuth、SSE） | **净减约 1250 行** |
| 应移动 | 7 | 无行数变化，包归属修正 |
| 应重命名 | 3 | 无行数变化，消除歧义 |
| **不应拆** | 6 | **0 改动（防止误改）** |

---

## 五、建议的文档落地方案

若要把本轮与上一轮结论并入项目文档体系，建议**最小改动、不破坏门禁**：

| # | 动作 | 文件 | 说明 |
| --- | --- | --- | --- |
| 1 | 新建 | `docs/research/project-structure-and-engine-review.md` 的**同主题续篇**，例如 `docs/research/code-review-2026-09-24.md` | 汇总五维度审查 + 再评估 + 本结构审查的**当前有效结论**。按该目录既有命名惯例（`execution-engine-deep-review-2026-09-22.md`） |
| 2 | 更新 | `docs/research/project-structure-and-engine-review.md` | ①刷新基线 `9a9b25dd` → `3cf89027` ②在 `:7` 后补一句明确"模块内分解 ≠ 新增 Gradle 模块"③`§四` 的 `ChatService` 拆分行补上具体组件清单（现仅一句方向性描述） |
| 3 | 更新 | `docs/README.md:12` | 在"项目结构与执行引擎审查"行追加新文档链接 |
| 4 | 更新 | `docs/development/status.md:27` | 该行已引用结构审查；补一句本轮结论指向 |
| 5 | **需先决策** | `docs/development/roadmap.md:5,19,24` | 三处状态过期（见上一轮报告 P1）。**建议先修这三处**，再谈并入新报告 |
| 6 | **需先决策** | P0 恢复缺陷 | 是否立 HXA。这是唯一需要**立即**进入任务体系的一条 |

**门禁影响**：动作 1–4 均在 `docs/**`，会受 `scripts/check-docs.sh` 校验（本地链接 + 必需条目 + 完成记录索引）。执行前建议先跑 `./scripts/check-docs.sh` 记录基线，改完复跑对比。

**不建议**：把审查报告写进 `reviews/` 之外但仍在 `docs/` 之外的位置（如根目录），因为 `check-docs.sh:17` 的 glob 含 `root.glob("*.md")`，根目录 `.md` 会被扫描 —— 用户已放在根目录的 `REVIEW-2026-09-24.md` 正属此类，**建议移入 `reviews/` 或 `docs/research/`**。

---

## 六、修订后的优先级（仅本报告新增/修正部分）

| 优先级 | 项 | 依据 |
| --- | --- | --- |
| **P0** | 修 P0 恢复缺陷（并行假设） | 上一轮已裁决，五环证据链完整 |
| **P1** | 消除 `core:storage→core:policy`、`runtime:quickjs→tools:framework`、`feature:browser→tools:browser` 三条反向边 | 上提契约到 `core:model`，**不新增模块** |
| **P1** | `ChatService` 拆 9 组件（4048 → <800 行） | §3.1/§4.1：全项目唯一严重低内聚单点 |
| **P1** | `app/ui` 106 文件划 6 子包 | §2.2：纯移动，零行为风险，收益立竿见影 |
| **P1** | `BrowserController` 的持久化移入 `BrowserStorage` | §3.1：唯一一处"控制器做持久化"的明确错位 |
| **P2** | 抽公共 SSE 解析器（净减 ~400 行） | §4.3：三份逐行相同的实现 |
| **P2** | `ConversationIntents`/`EgressDisclosure` 按区域拆（36/35 字段 → 0 方法） | §3.2：参数对象膨胀 |
| **P2** | 消除 2 处同名类（`TurnState`、`SkillRepository`）+ 1 处 `Fake` 前缀 | §2.5/§4.5 |
| **P2** | 修正 `scripts/debug/.../fixtures/` 2 处包/目录不一致 | §2.1：全项目仅此 2 处 |
| **待裁决** | 抽公共 DeviceCode OAuth 引擎（净减 ~850 行） | §4.2：需确认是否与 ADR-PROVIDER-002 的"独立适配器"意图冲突 |
| **已撤回** | ~~删除 `TurnReducer` 及测试~~ | §0 冲突项 2：与已接受决定冲突。**改为止损：执行该文档的处方（补齐生产不变量测试），P0 即其证据** |

---

## 七、后续补充（2026-09-24 增补）

本报告的四个维度（架构设计、目录结构、类内聚耦合、文件拆分重组）之外，另有一轮补充审查覆盖了**验证体系、发布就绪度、静态分析门禁盲区**三个此前完全未涉及的维度，见 `2026-09-24-supplement-verification-and-release.md`。

其中与本报告直接相关的两条：

1. **§0 冲突项 2（`TurnReducer` 处方）得到了机制性支持**：补充审查 §1.2 发现 `app/src/androidTest/.../ProcessRecoveryTest.kt` 的 9 个 `@Test` **全部只 seed 单个工具调用**（`tc-1`），与 `PersistedTurn.init` 的「至多 1 个 RUNNING」断言共享同一错误前提。这证明现有决定「先补齐生产不变量测试再考虑移动」**尚未执行**，而 P0 恢复缺陷正是其产物——不是绕开该处方的理由，而是执行它的证据。
2. **§0 冲突项 1（不新增 Gradle 模块）在发布维度上得到印证**：`consumer release` 45 MiB 中约 90% 是未裁剪 DEX，且 R8 全程关闭。模块数量的讨论应让位于这一更直接的分发成本问题。

---

*本报告对 1690 个生产类/对象做了静态度量，并对 3 处与现有已接受决定的冲突做了裁决（1 处修正表述、1 处撤回、1 处收窄）。未修改任何项目文件。*
