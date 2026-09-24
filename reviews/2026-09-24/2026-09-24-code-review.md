# Helix 项目代码综合审查报告

**审查基线**：HEAD `3cf89027`（2026-09-24），工作树含 8 个未提交修改（含 `ChatService.kt`、`GroupedNavigation.kt`、`SessionDao.kt`）
**代码规模**：32 个 Gradle 模块 / 1854 个 `.kt` 文件 / 296,560 行（`:app` 单独占 832 文件、137,807 行 = **46%**）
**审查方式**：只读代码走查 + 文档交叉核对 + git 取证；所有结论均附文件:行号，关键项已二次核验

---

## 结论摘要

| 维度 | P0 | P1 | P2 | 整体评价 |
| --- | --- | --- | --- | --- |
| 一、文档与代码一致性 | 0 | 1 组 | 3 | **良好**，无硬偏差，问题集中在文档内部滞后与自相矛盾 |
| 二、软件架构 | 2 | 4 | 3 | **需干预**，模块化名义大于实质，`:app` 已成单体 |
| 三、UI 与产品交互 | 0 | 8 | 8 | **偏弱**，缺设计系统与加载态；文档宣称的关键交接路径未实现 |
| 四、核心路径 bug | 1 | 3 | 3 | **有严重缺陷**，进程死亡恢复存在永久失效路径 |
| 五、优化与删减 | — | — | — | **仓库卫生优秀**，维护成本集中在 scripts 与重复的 SSE 解析 |

**最需要立即处理的三件事**

1. **进程在并行工具批次中死亡后，恢复流程永久失效**（维度四 P0）——Turn 永久卡在"运行中"，且每次启动都失败，属不可自愈故障。
2. **拆解 `ChatService`（4047 行 / 81 个公开方法）**（维度二 P0）——单点风险最高的文件，也是维度三多数交互问题的根因。
3. **修复文档内部矛盾**（维度一 P1）——`roadmap.md` 对 129/217/ADR-010 的状态陈述与 `status.md`、与自身表格三处冲突，会误导实施者。

---

## 维度一：文档与代码一致性

### 总评：无"文档说 A、代码是 B"的硬偏差

这是一个文档纪律罕见的项目。抽查的 8 类关键声明中，7 类**完全一致**：

| 声明 | 核验方式 | 结论 |
| --- | --- | --- |
| QuickJS 在非导出 isolated 进程 | `runtime/quickjs/src/main/AndroidManifest.xml:9-14`：`isolatedProcess="true"`、`exported="false"`、`process=":helix_js"` | ✅ 一致 |
| PRoot/订阅在私有进程 | `runtime/proot-app/.../AndroidManifest.xml:8-25`（`:proot`）、`runtime/cli-app/.../AndroidManifest.xml:7-27`（`:subscriptions`），全部 `exported="false"` | ✅ 一致 |
| consumer 排除 PRoot/CLI | `app/build.gradle.kts:129-141` 全部为 `developerImplementation`；consumer 侧有桩实现（`ProotToolModule.kt:20 AVAILABLE=false`） | ✅ 一致 |
| STANDARD/ADVANCED 编译期切换 | consumer `AdvancedProfileAvailability.kt:14 = false` vs developer `:17 = true` | ✅ 一致 |
| 模块清单 32 个 | `settings.gradle.kts:30-63` 实计 32，无文档提到但代码不存在的模块 | ✅ 一致 |
| HXA-209 会话授权 | `SessionPermissionResolver.kt`、`SessionPermissionConfig.kt`、`SessionPermissionMode.kt:31`（四预设）、`ToolAvailabilityState.kt:14`（二态）；旧三态已彻底清除 | ✅ 一致 |
| UI 层不访问 DAO/网络/执行器 | `grep` 于 `app/.../ui/` 与 `feature/`：`androidx.room`/`retrofit2`/`okhttp3`/`runtime.*` **0 命中**；feature 模块依赖表不含 `:core:storage`，结构上无法访问 | ✅ 一致 |
| ADR accepted ≠ 已实现 | 35 个 accepted 中"仅设计未接线"者（如 `ADR-AGENT-004:38`）均在 ADR 与 `status.md:65` 显式声明 | ✅ 一致 |

### 问题 1（P1）：`roadmap.md` 状态陈述过期，且与自身表格、`status.md` 冲突

**影响范围**：所有按 `AGENTS.md:9` 要求"必读 roadmap 当前 HXA"的实施者
**证据**：

- `roadmap.md:19`：「129已完成本地实现与验收，**尚未合并main**」
  ↔ `status.md:18`：「HXA-129 …**已整合入 main**」（git 有 `bf79be09 merge: integrate HXA-129`）
- `roadmap.md:5`：「**217仅完成格式成本准备**」
  ↔ `roadmap.md:249` 表格「HXA-217 **已交付**」 ↔ `status.md:19`「已完成本地实现与验收」——**同一文件自相矛盾**
- `roadmap.md:24`：「依赖 … **proposed** ADR-AGENT-010接受」
  ↔ `docs/adr/agent/010-request-context-manifest.md:3`：`Status: accepted`（2026-09-23）

**改进建议**：`roadmap.md` 的叙述段与状态表格需要一次性对齐；建议在 `scripts/check-all.sh` 中增加"roadmap 表格状态 vs status.md 关键条目"的一致性检查，避免手工维护两份状态。

### 问题 2（P2）：`HXA-218.md` 完成记录未同步 main 收敛状态

**证据**：`docs/completion-records/HXA-218.md:43` 称"未合入main" ↔ `status.md:16` 称"214/215/216/218/219 已整合至本地 main"。证据链断裂。

### 问题 3（P2）：`TurnReducer` 被文档当作核心状态机，但生产路径未使用

**证据**：`roadmap.md:54` 记「HXA-011 Turn reducer **已交付**」；`core/agent/.../TurnReducer.kt`（694 行）在 `app/src/main` 中的引用**全部是 KDoc 注释**，唯一提到它行为的代码注释是 `TurnCoordinator.kt:60`——「It deliberately does not reuse the M1 serial TurnReducer」。

这不是文档撒谎（HXA-011 当时确实交付了），而是**文档未反映后续架构分叉**。该分叉直接导致了维度四的 P0 缺陷。详见维度二 F5 与维度四缺陷 1。

### 问题 4（P2）：UI 层持有 storage repository，与分层意图有张力

**证据**：`ui/SettingsScreen.kt:39,64` 持有 `HighSensitivityRuleRepository`；`ui/SessionPermissionSection.kt:33`、`ui/ConversationDraftBuffer.kt:56` 等共 10 个 ui 文件直接引用 `core.storage.repository.*`。

**说明**：文档字面（"不直接访问 DAO、网络客户端或执行器"）成立——repository 不属于这三类。但 UI 直连持久化 DTO 与"UI 不承担持久化职责"的**意图**相悖。

**改进建议**：二选一——(a) 在 `overview.md:9` 明确"UI 可持有 repository，不可持有 DAO"；(b) 将这些 repository 下沉到 app 协调层，UI 只依赖 UI 模型。推荐 (b)，因为它是维度二 F3 的同一根因。

---

## 维度二：软件架构

### 依赖图（实测，标注违规边）

依赖**未写在子模块 build 脚本**，而是集中在根 `build.gradle.kts`（611 行）的 `when(path)` + `projectDependencies` map 中。所有 `project(...)` 均为 `implementation`，**无循环依赖、无 `api` 传递泄漏**，`:app` 是纯叶子。

```
:app  ──→ core:{model,agent,policy,storage,workspace}
      ──→ provider:{api,openai-chat,openai-responses,anthropic,catalog}
      ──→ extensions:{mcp,a2a,skills}
      ──→ feature:{files,browser}, tools:{browser,android,files,framework}, runtime:quickjs
      ─.developer.→ feature:files-allfiles, tools:{automation,root},
                     runtime:{proot-client,cli-app,terminal-renderer}

core:storage    ──→ core:policy        ⚠️ 违规：数据层 → 策略层
runtime:quickjs ──→ tools:framework    ⚠️ 违规：runtime → tools
feature:browser ──→ tools:browser      ⚠️ 违规：feature → tools
feature:files-allfiles ──→ feature:files  ⚠️ feature 间横向依赖
extensions:{mcp,a2a} ──→ tools:framework   （可接受，扩展即工具来源）
runtime:proot-client ──→ proot-ipc ──→ proot-core  （拆分合理）
```

### F1（P0）：`:app` 是事实上的单体

**证据**：832 文件 / 137,807 行 = 全项目 46%；内含 34 个包（`ui` 106 文件、`chat` 59、`agent` 27）。
**影响范围**：全局。编译增量、模块边界、代码复用、测试隔离全部受损。
**评价**：32 个模块的"模块化"大半是名义上的——真正承载业务逻辑的代码堆在 `:app`。
**改进建议**：为新增业务立规——逻辑默认落 `:feature:*` 或新建 `:app-*` 子模块，`:app` 只做 wiring 与 UI 装配。存量按 F4 的拆分方案逐步下沉。

### F2（P0）：`ChatService` 上帝类

**证据**（实测）：
- 4047 行，106 个 import
- **81 个公开方法**
- 约 22 个构造注入协作者（`ChatService.kt:138-182`）
- **11 个 `MutableStateFlow` + 5 个 `ConcurrentHashMap`** 可变状态
- 混合职责：会话 CRUD（`:667-737`）、草稿/附件暂存（`:1113-1235`）、出网决策、提交准入（`:1658-2212`）、Turn 启动编排（`:3380-3781`）、恢复动作（`:2395-2433`）、Goal 续跑（`:399-643`）、Plan 评审、后台任务、通知

**影响范围**：整个 chat 主链路；也是维度三多数交互问题与维度四缺陷 4 的共同根因。
**改进建议**：保留其对外门面，按下表抽出组件（多数已有 lazy 协作者，抽离成本可控）：

| 建议组件 | 职责边界 | 现证据行 |
| --- | --- | --- |
| `ChatStateHolder` | 全部 StateFlow 状态持有 | `:285-345,362-369` |
| `SessionCatalogService` | 会话 CRUD / 搜索 / 归档 / fork | `:667-942` |
| `ComposerDraftService` | 草稿读写、receipt、materialize | `:1400-1564` |
| `AttachmentStagingCoordinator` | 暂存 / 移除 / 恢复 / 出网复验 | `:1113-1235,2152-2198` |
| `SubmissionAdmission` | 准入、去重、egress 决策 | `:1658-2212,1881-1929` |
| `TurnLauncher` | launchTurn / runTurn / applyEvent / terminalize | `:3380-3781` |
| `SessionInputQueueService` | 输入队列读 / 编辑 / 撤回 / 恢复 | `:2871-3329` |
| `GoalContinuationController` | 目标续跑与时间/模型预算 | `:399-643` |

目标：降到 800 行以内，只保留门面与跨组件编排。

### F3（P1）：分层反向依赖三条

**证据**：
- `core:storage → core:policy`：7 个文件（`HelixMigrations.kt`、`entity/SessionPermissionConfigEntity.kt`、`repository/ApprovalRepository.kt` 等）
- `runtime:quickjs → tools:framework`（`build.gradle.kts:188`）
- `feature:browser → tools:browser`（`build.gradle.kts:182`）

**影响范围**：core 层的可独立测试性、runtime 的执行域边界声明。
**改进建议**：把被跨层引用的契约（`ToolDescriptor`、`SessionPermissionConfig` 相关类型）上提到 `core:model`，一次性切断三条边。这是**投入最小、恢复分层可验证性收益最大**的一项。

### F4（P1）：UI 直连 storage/provider 内部类型

**证据**：`ui/SessionInputQueuePanel.kt:27-29`、`SessionPermissionCustomEditor.kt:21`、`PlanReviewDialog.kt:27`、`SettingsScreen.kt:39`、`SessionPermissionSection.kt:33`、`ProviderFormDialog.kt`（4 处）等 10 个文件。
**改进建议**：为这些状态定义 UI 层自有模型，由 `ChatService`/Projection 映射。与维度一问题 4 同源。

### F5（P1）：双份 Turn 状态机，其中一份生产未用

**证据**：`core/agent/.../TurnReducer.kt`（694 行，串行状态机）在生产路径**零引用**（已 grep 核验，仅 KDoc 引用）；生产用 `app/.../agent/TurnCoordinator.kt:65-147` 的 `BatchTurnRuntime`（并行批次，不同不变量）。约 2000 行测试绑定在非生产实现上。
**影响范围**：`core:agent` 的测试全部不覆盖线上行为；两者语义已分叉（串行工具队列 vs 并行批次）。
**改进建议**：二选一——(a) 让 `BatchTurnRuntime` 复用 `TurnReducer` 的转移与不变量；(b) 明确标注 `TurnReducer` 为参考实现，从"核心路径"文档中移除，并删除其专属测试。
**注意**：这正是维度四缺陷 1 的成因——`TurnReducer` 时代的"串行执行"假设被写进了 `PersistedTurn` 的数据契约，而调度器早已并行化。

### F6（P1）：手工 DI 巨型容器 + 可变单例破环

**证据**：`DefaultAppContainer.kt`（758 行，`internal class`，25+ lazy 单例，0 public 方法）是 ServiceLocator；用**可变持有者** `ApprovalCardSinkHolder`（`:437`）打破构造环。
**影响范围**：全 app 可测性——单元测试必须构造整个容器；环依赖靠可变状态掩盖。
**改进建议**：按层拆成 `StorageModule`/`AgentModule`/`ProviderModule` 等小工厂，对外暴露窄接口而非一个巨型 `AppContainer`。

### F7（P2）：根构建脚本成为"上帝构建文件"

**证据**：`build.gradle.kts` 611 行集中定义 32 个模块；模块路径**三重声明**（`settings.gradle.kts` 35 处 + 根 build 85 处：`androidLibraries` map / `jvmLibraries` set / `projectDependencies` map）。新增一个模块要改 3 处。
**更值得注意的是**：靠改 group 规避坐标冲突——`:tools:browser` 与 `:feature:browser`、`:tools:files` 与 `:feature:files` 同名会静默丢类，被迫改名（`:232-234`、`:495-497`）。这是命名与配置集中双重气味的叠加。
**改进建议**：迁移到 build-logic convention plugin，或每个模块一个 `.kts`。风险中（改动构建，需全量 CI 验证）。

### F8（P2）：薄模块

`:testing` 仅 1 个文件；`:provider:catalog` 3 文件（静态模板数据）；`feature:files-allfiles` 6 文件。
**说明**：`:tools:automation`（27 文件）与 `:tools:root`（16 文件）**名实相符，非空壳**；`:runtime:proot-*` 四模块拆分**合理**（`proot-core` 零依赖 schema 与 `proot-ipc` 协议被主 APK 与伴随 APK 共享）。

### F9（P2）：developer Manifest 组件膨胀

合并后 developer 变体 438 行：16 Activity / 12 Service / 2 Provider / 6 Receiver / 13 权限；consumer 为 6/6/2/4。源清单本身很小，规模来自库清单合并，属可接受，但 developer 组件数近乎三倍。

### 扩展成本实测（可扩展性硬指标）

| 新增 | 需改动文件数 | 具体路径 |
| --- | --- | --- |
| **Provider** | **6+ 处** | ①`core/model/.../ProviderProtocol.kt`（改枚举）②根 `build.gradle.kts` 两处 map ③`provider/<x>/` 适配器约 9–11 文件 ④`app/build.gradle.kts:123-127` ⑤`app/.../provider/ProviderFactory.kt:44-48`（`when` 分支 + 3 个 ImageResolver）⑥`provider/catalog` 模板 |
| **Tool** | **2–4 个** | ①`tools/<x>/` descriptor + executor ②register 函数 ③`DefaultAppContainer.kt:362-429` 加 1 行 ④（若新模块）根脚本 map |
| **MCP 传输** | **2–3 个** | ①`extensions/mcp/.../SdkMcpClientFacade.kt:34`（传输硬编码 `StreamableHttp`）+ 新 Transport 类 ②app 侧 wiring |

**结论**：Tool 扩展成本**健康**；Provider 的 `when(enum)` + 核心枚举 + 工厂分支是**抽象不足**（封闭枚举扩散，`additionalFactory` 只救得了 dev-only provider）；MCP 传输选择**硬编码在 facade**，扩展需改核心类。

### 过度抽象：未发现严重问题

`ShellRepository`/`LineStore`/`ForegroundServiceLauncher`/`ReminderEnqueuer`/`DeviceResourceProbe`/`SecretStore` 等单实现接口多为**测试缝**，属合理用法。

---

## 维度三：UI 与产品交互

### 导航结构

```
HelixApp (MainActivity.kt:176)
├─ FirstLaunchNoticeScreen                隐私首启门禁 (:178)
└─ ModalNavigationDrawer                  抽屉 (:203)
   ├─ GroupedNavigation  4 组 / 13 个 destination (GroupedNavigation.kt:22)
   │   会话 │ 工作: Tasks·Artifacts·Git·Files·Browser·Terminal
   │        │ 扩展: Extensions
   │        └ 设置: Capabilities·Readiness·Permissions·Settings·Audit
   └─ Scaffold + NavHost (:229)
      ├─ 13 条 destination route → DestinationScreen (:302)
      ├─ COMMAND_DETAIL_ROUTE(turnId, callId) → CommandResultDetailScreen
      └─ TASKS_TURN_ROUTE(turnId)            → TasksScreen(initialTurnId)
```

**合格项**：抽屉切换用 `popUpTo(initialDestination)`（`MainActivity.kt:216`），不堆栈膨胀；仅 2 条推入式路由，返回路径清晰。**硬编码颜色 `Color(0x...)` 实测 0 处**，全部走 `MaterialTheme.colorScheme`。硬编码中文实测 49 处但**几乎全在 KDoc/注释**，用户可见的仅 `AttachmentContext.kt` 12 处。

### 问题清单

#### UI-1（P1）：斜杠命令 `/plan` 与 `/act` 失效

**证据**：`app/src/main/kotlin/com/helix/app/ui/ConversationComposer.kt:83-91`

```kotlin
"slash:plan" -> { onMode(AgentMode.CHAT); onInput("") }
"slash:act"  -> { onMode(AgentMode.CHAT); onInput("") }   // 两者都切到 CHAT
```

**影响范围**：100% 会话——Plan/Act 两个核心模式从斜杠入口**不可达**。
**改进建议**：改为 `AgentMode.PLAN` / `AgentMode.ACT`。这是本次审查中**修复成本最低、收益最高**的一项。

#### UI-2（P1）：模式菜单直接显示英文枚举名

**证据**：`ui/composer/ComposerMenus.kt:34,36` 用 `mode.name.lowercase()` → 中文界面显示 "Chat/Plan/Act/Goal"。
**改进建议**：改用 `stringResource` 的模式名。

#### UI-3（P1）：无设计系统，间距/圆角/字号全硬编码

**实测数据**：

| 指标 | 数值 | 分布 |
| --- | --- | --- |
| 硬编码 `N.dp` | **304 处**（app/ui） | 8dp×105、4dp×58、16dp×39、12dp×37 |
| `RoundedCornerShape(` | 19 处 | — |
| 硬编码 `fontSize = N.sp` | 71 处 | 集中在 `feature/browser` + `ContextWindowIndicator` |
| 自定义 Card | 7 个各自实现 | `ApprovalCard:46`、`PendingApprovalCard`(ToolTimelineItem.kt:181)、`TaskLedgerCard:27`、`FileLocationCard`(FilesHome.kt:108)、`MarketplaceItemCard:256`、`GitHubDeviceCodeCard:657`、`TabCard` |
| 裸 `AlertDialog(` | **40 处**（实测） | app/main 35、developer 1、feature 5 |
| 复用空态 | 仅 2 个 | `EmptyConversationHint:23`、`EmptyDestination` |

**改进建议**：建 `Spacing`/`Shapes`/`Typography` 令牌对象，优先收敛 dp（304 处）与 AlertDialog（40 处）两个最大头。

#### UI-4（P1）：加载态严重缺失

**实测数据**：`CircularProgressIndicator` 全工程仅 **2 处**、`LinearProgressIndicator` 4 处；而 UI 层异步调用点 `Dispatchers.IO` 在 app/ui 有 **52 处 / 16 文件**，app/chat 32 处。
**影响范围**：约 80+ IO 点 vs 6 个进度指示。多数以「禁用按钮」代替加载态（`ConnectorSection.kt:80,169` 等 `enabled = !action.busy`），用户无法判断是否在执行。
**改进建议**：为扩展安装/连接/测试三条链路（`ConnectorSection.kt:333-352`、`SkillInstallationSection.kt:89-96`）挂进度指示，这是用户感知最强的缺口。

#### UI-5（P1）：错误展示不规范

**证据**：
- 原始错误码直出：`onError("OAUTH_DEVICE_EXPIRED")`（`ConnectorSection.kt:742`）、`diagnosticText()` 回显 `SKILL_METADATA_*`（`:638-653`）
- 违反 `docs/product/task-experience.md:72` 要求的「问题 + 已完成结果 + 下一动作」文案规范
- 错误 UI 均为临时内联红字（`ConnectorSection.kt:116-118,434-436`、`SkillInstallationSection.kt:120-125`、`MarketplaceSection.kt:94-101`），**无统一错误边界**
- 全工程 **0 个 Snackbar，13 处 Toast**

#### UI-6（P1）：文件管理器 → Agent 工作区断链

**证据**（已二次核验）：`stageAttachment` 在 `app/src/main` 中的调用点**只有** `ui/ChatScreen.kt:401`（composer 附件选择器）与 `ChatService` 内部流程。`app/src/main/kotlin/com/helix/app/files/` 下 **0 命中**。

**影响范围**：文件列表/首页没有任何「在当前任务使用 / 附加到会话」动作。选择文件仅弹预览（`FilesScreen.kt:68 FilesPreviewDialog`）。与 `docs/product/task-experience.md:7`「工作区作为操作起点」**直接冲突**。
**改进建议**：在 `FilesScreenComponents.kt` 文件行与预览弹窗加「在当前任务使用」，调用 `chatService.stageAttachment`。同时按文档要求补一个统一工作区详情页（文件/任务/终端/变更四入口）——目前只有 Files 内按目录 `openTerminal`（`FilesScreen.kt:91-99`）。

#### UI-7（P1）：市场卡片文案错配 + 状态机不完整

**证据**：
- 展开键折叠态显示 `R.string.command_detail_title`（"命令详情"）、展开态显示 `R.string.session_export_close`（"关闭"）（`MarketplaceSection.kt:349-355`）；卸载弹窗取消键复用"关闭"（`:540`）
- 仅 3 态（NOT_INSTALLED/INSTALLED_INACTIVE/ACTIVE，`:548-574`），文档要求 6 阶段（已添加/待配置/待连接验证/未启用/可使用/需处理，`task-experience.md:52`）
- 安装成功后无带入会话的动作（文档 `task-experience.md:56` 要求）

#### UI-8（P1）：审批卡片决策关键信息默认折叠

**证据**：`approval/ApprovalCardScreen.kt:74-86` 折叠态只显示 scope + 参数摘要 + 风险；`预期影响`/`verifier`/`codeOrCommand` 需点「展开」（`:88-93`）。
**正面**：信息量本身充足（来源/目标/scope/参数/风险/Profile/Provider/出网/数据类别/预期影响/verifier 全量，`:118-173`）。
**改进建议**：把「预期影响」提到折叠态；并在卡片上提供跳转到 设置→会话权限 的入口（当前常驻偏好唯一入口在 `SettingsScreen.kt:154`，发现性差）。

#### UI-9（P2）：跨页状态靠全局单例而非导航参数

**证据**：`container.chatService.openSession(sessionId)` 后再 `navigate`（`MainActivity.kt:261,283,333,352`）；`chatService.openGoalReminder(goalId)`（:148）。目标页读取服务里的「当前会话」隐式状态。
**更严重的一处**：`chatService.recoverySettingsNavigation = onProviders`（`ChatScreen.kt:96`）在**组合过程中**向 Service 写导航回调——既产生组合期副作用，又使 Service 反向依赖 UI 导航。

#### UI-10（P2）：旋转丢态

**证据**：`MainActivity` **无 configChanges**（`AndroidManifest.xml:40-42`）→ 旋转即重建；而 `ui` 包 `rememberSaveable` 仅 12 处。受影响：Extensions tab（`ExtensionsScreen.kt:43`）、Marketplace 筛选/搜索（`:55-56`）、Connector 草稿（`:46-52`）。

#### UI-11（P2）：UI 层承载业务逻辑与 IO

**证据**：`ui/FilesScreenActions.kt:15-40` 在 ui 包内持有 Service、`AtomicBoolean` 与 `withContext(Dispatchers.IO)`；`MarketplaceSection.kt:103` 在 **composition 内直调** `service.items()`；`:151-157,173-175` 的 `disable/enable/uninstall` 在**主线程**直调（仅 install 走 IO）；`ConnectorSection.kt:249,252,257` 在 `remember` 初始化器里读服务。

#### UI-12（P2）：超大 Composable

`ChatScreen` 单函数约 470 行（`ChatScreen.kt:53-524`，文件 524 行）；`ConversationSection` 约 466 行（`ConversationSection.kt:49-515`，文件 515 行）；`ConnectorSection` 约 195 行。均以 `@Suppress("LongMethod","CyclomaticComplexMethod")` 压制。`ChatScreen` 有 ~12 个 `LaunchedEffect`，其中一个带 10 个 key（`:162-173`），脆弱难验证。

#### UI-13（P2）：图标与交互细节

- 图标来源混杂：`NavigationMenuIcon()` 用 `Text("☰")`（`AdaptiveConversationHeader.kt:104-110`，被 3 处复用）；`"$label ▾"`、`"✓ $text"`（`ComposerMenus.kt:79,84`）
- Terminal 是抽屉项但不是页面（`MainActivity.kt:209-212 / 455-462`）：点击后抽屉关闭、外部 Activity 拉起、当前路由 `popBackStack`——抽屉「选中态」会短暂指向一个不存在的页
- 发送/停止布局跳动：停止键仅 `isSending` 时出现在输入行**下方**（`ConversationComposer.kt:169-177`），输入区高度跳变
- 可点击容器无 `role`/`onClickLabel`：`SessionListSection.kt:183-188`、`MarketplaceSection.kt:435-440`
- 深色模式机制正确（`Theme.kt:25` 按 `isSystemInDarkTheme()` 切换，`values-night/themes.xml` 补窗口侧），但用 M3 **默认紫色调色板**，无品牌色、无动态取色

### ⚠️ 一条需要撤回的初判

**「默认资源语言是中文」不是缺陷。** `app/src/main/res/values/strings.xml:11-12` 有明确注释：

```
<!-- HXA-069: base/primary locale is Simplified Chinese. values-en and values-zh-rCN must keep
     this exact key set (checked by scripts/check-i18n.sh). -->
```

这是**有意的、被脚本守护的设计决定**（三份文件键数实测一致：1358/1358/1358）。审查中一度将其列为 P1，此处更正。

### 一致性度量总表

| 指标 | 实测 | 评价 |
| --- | --- | --- |
| 顶层 Screen | 16（12 实页 + 1 stub + 2 推入路由 + 1 门禁） | — |
| 共享组件库 | **无**（0 个 Dimens/Spacing/Typography 定制） | 差 |
| 硬编码颜色 `Color(0x` | **0** | 优 |
| 硬编码 dp | **304** | 差 |
| 硬编码 fontSize | **71** | 差 |
| 裸 AlertDialog | **40** | 差 |
| CircularProgressIndicator | **2** | 差 |
| 硬编码中文（Kotlin） | 49，用户可见 ≈0 | 良 |
| 硬编码英文用户串 | ≥5（"Skill:"、"MCP JSON"、模式枚举名） | 中 |
| contentDescription 覆盖 | ≈79%（27/34，3 处装饰性 null） | 良 |
| 国际化 | 2 语言，基础 locale 中文（**有意设计**） | 良 |
| 反馈机制 | 0 Snackbar / 13 Toast / 若干内联红字 | 差 |
| 旋转保态 | `rememberSaveable` 仅 12 处，无 configChanges | 差 |

---

## 维度四：核心路径 bug

### P0-1：进程在并行工具批次中死亡后，恢复流程**永久失效**

**位置**
- `core/agent/src/main/kotlin/com/helix/core/agent/RecoveryCoordinator.kt:32-36`
- `app/src/main/kotlin/com/helix/app/recovery/RecoveryCoordinatorApp.kt:135-148`
- `app/src/main/kotlin/com/helix/app/HelixApplication.kt:68-85`

**触发条件**：一个模型响应里有 ≥2 个可并行调用（如两次 `file.read`，或两次只读 `browser.snapshot`），调度器并发执行期间进程被杀。

**证据**——数据契约假设串行，但调度器已并行（均为实测）：

```kotlin
// RecoveryCoordinator.kt:32-36  —— 假设"串行执行，至多 1 个 RUNNING"
// Serial execution (first version, doc 02 section 5.3): at most one call is RUNNING
// at any time, so a turn that died mid-execution has at most one uncertain call.
require(toolCalls.count { it.state == ToolCallState.RUNNING } <= 1) {
    "at most one RUNNING tool call per turn (serial execution)"
}
```

```kotlin
// ToolScheduler.kt:345,348
const val DEFAULT_MAX_CONCURRENCY = 2      // 并发度 2
const val HARD_MAX_CONCURRENCY = 4
```

```kotlin
// RecoveryCoordinatorApp.kt:135-148 —— 用 listActive() 构造，任一轮次越界即抛
private fun scanPersistedTurns(): List<PersistedTurn> =
    storage.turns.listActive().map { turn ->
        PersistedTurn(turnId = ..., phase = ..., toolCalls = ...)   // init 校验在此触发
    }
```

```kotlin
// HelixApplication.kt:73-85 —— 宽泛 catch，吞掉异常并"下次重试"
try {
    recoveryCoordinator.recover()
    appContainer.chatService.onRecoveryCompleted()
    GoalReminderReconciler(...).reconcileAll()
} catch (t: Exception) {
    Log.e(TAG, "process recovery failed; will retry at next start", t)
}
```

**影响**（严重程度：P0，用户可见后果最重的一条）
1. Turn 永远停在 `RUNNING_TOOL`，UI 永久显示"运行中"
2. `onRecoveryCompleted()` 不执行、Goal 不 park
3. 两个 `tool_calls` 行永远 `RUNNING`
4. 该会话的修订路径被 `REVISION_SESSION_BUSY` **永久阻塞**
5. **下一次启动仍然失败**（同样的 2 个 RUNNING 行）——**不可自愈**
6. 因 catch 包住了整个恢复块，**一个坏轮次会连带阻断所有轮次与 Goal 提醒的恢复**

**修复建议**
- `PersistedTurn` 改为持有 `runningCallIds: List<ToolCallId>`（或删除该 `require`）；`TurnRecovery.Interrupt.uncertainToolCall` 改为集合
- `scanPersistedTurns` 不应因数据形状直接抛异常——降级为"标记待核查"而非崩溃
- 拆分 `HelixApplication` 的 try 块，让单个轮次的失败不阻断全局恢复
- 让 `recover()` 的失败**可见**（上报/阻塞 UI），而不是静默重试

**置信度：高**（已逐行核对生产装配与调用链）

---

### P1-1：批次中任一"需复核"的失败会让整轮以 INTERNAL 失败，并丢失成功调用的工具结果

**位置**：`app/.../agent/TurnCoordinator.kt:125-129,297`；`app/.../chat/ChatToolCalls.kt:290,313-320`；`app/.../agent/AgentLoop.kt:410-419`

**触发条件**：批次里任一调用以 `requiresReview=true` 结束（工具 `TIMEOUT`、`CANCELLED_AFTER_START`、`INVALID_OUTPUT`，见 `ToolDispatcher.kt:896-910,958-966`）。

**证据**（实测，断言在事务之前）：

```kotlin
// TurnCoordinator.kt:125-129 —— UNKNOWN 也被拒绝
fun requireBatchSettled() {
    require(batchCalls.isNotEmpty()) { "no active tool batch" }
    require(batchCalls.values.none { it == BatchCallResolution.PENDING }) { "tool batch still has pending calls" }
    require(batchCalls.values.none { it == BatchCallResolution.UNKNOWN }) { "tool batch has unknown side effects" }
}
```

```kotlin
// TurnCoordinator.kt:294-300 —— requireBatchSettled() 在 withTransaction 之前
fun openNextModelCall(messages: List<TurnMessageDraft>, nextModelCallId: String) {
    runtime.requireBatchSettled()          // ← UNKNOWN 存在则在此抛
    val current = runtime.snapshot()
    var cancelled = false
    storage.withTransaction {              // ← 结果消息永远写不进去
        ...
```

**影响**：同批次**成功调用**的 model-visible 结果消息（`toolResultDraft`）永远不写入——违反文档"model-visible ⇔ persisted"；Turn 被 `runTurn` 的泛 catch 归为 `FAILED/INTERNAL`，用户只看到笼统内部错误，而非"副作用待复核"。

**修复建议**：把 `requireBatchSettled()` 拆成"允许 UNKNOWN、禁止 PENDING"；UNKNOWN 存在时应把已完成结果照常落库并进入 HXA-015 复核态（`INTERRUPTED`/`NEEDS_REVIEW` 的 Turn 级状态），而非整轮 FAILED。

**置信度：高**

---

### P1-2：Goal 时长预算耗尽被呈现为"用户取消"

**位置**：`app/.../agent/GoalTimeBudget.kt:84-88,100-102,206-217`；`app/.../agent/AgentLoop.kt:51-60`

**触发条件**：Goal 的剩余时长/唤醒预算归零，`pulse()` 的 `renew()` 返回 false。

**证据**：`renew()` 在 `duration <= 0` 或 `reserve` 失败时返回 false，且**不设置 `stopCode`**：

```kotlin
// GoalTimeBudget.kt:84-88
} else {
    storage.withTransaction {
        if (current.allocation == null) { settle(elapsed); continued = renew(current.started + elapsed) }
```

```kotlin
// GoalTimeBudget.kt:57-63 —— stopCode 为 null 时返回 null
return stopCode ?: if (current != null && ...) {...} else null
```

**影响**：`expiredCode()` 返回 null → `run()` 重抛 `CancellationException` → `runToolLoop` 以 `CANCELLED` 返回 → `terminalize(CANCELLED, errorCode=null)`。用户看到"已取消"，而真实原因是预算耗尽；`runTurn` 的 `GoalTimeLimitException` 分支（`ChatService.kt:3685-3687`）**不会命中**。

**修复建议**：`renew()` 失败时显式 `stopCode = "GOAL_BUDGET_LIMIT"`，使 `expiredCode()` 非空、`run()` 抛 `GoalTimeLimitException`，Turn 记为 `FAILED`。

**置信度：中高**（静态推演，建议单测复现）

---

### P1-3：`synchronized(turnGate)` 内执行阻塞 Room 事务

**位置**：`app/.../chat/ChatService.kt:3422`（`launchTurn`，锁内 `:3481 storage.withTransaction`）、`:3742`（`terminalize`）、`:2659`（`cancelTurn`）、`:3335-3373`（`submissionGate` 内再取 `turnGate`）

```kotlin
// ChatService.kt:3422
synchronized(turnGate) {
    ...
    storage.withTransaction { ... }   // :3481，阻塞 DB
```

**影响**：JVM monitor 内做 Room IO，持锁可达数十毫秒；`stop()`、`requestSessionDrain`、`refreshBackgroundTasks` 都要拿 `turnGate`，造成 `Dispatchers.IO` 线程被长时间占住与 UI 卡顿。**锁序目前一致（`submissionGate → turnGate → DB`），未发现死锁**——属健壮性隐患而非现存故障。

**修复建议**：`turnGate` 换成 `Mutex` 并在 `withContext(Dispatchers.IO)` 中 `withLock`；或 monitor 内只做内存态变更、DB 事务移出。

---

### P2 级问题

| # | 问题 | 位置 | 影响 |
| --- | --- | --- | --- |
| P2-1 | 生产未装配 `ResourceKeyExtractor`，`conflictsWith` 也不比较 `scopeIds`——"按归一化效果足迹判并发"只实现了一半 | `ToolScheduler.kt:53`（默认 `NoResourceKeys`）、`EffectFootprint.kt:38-43,106,114`、`DefaultAppContainer.kt:528-534`（未传该参数） | `resourceKeys` 只有 lane key；同一工作区同一文件的两次只读调用、同一浏览器 tab 的两次只读调用都会并行（浏览器无 lane key）。对只读多数无害，但一旦未来把有状态操作误标 `READ_ONLY` 就会真实冲突。建议装配真实提取器（workspace 规范化路径、browser tab/generation、SAF docId）并在 `conflictsWith` 中比较 `scopeIds`，或删除该字段以免误导 |
| P2-2 | `ProotJobRunner` 提交被线程池拒绝时泄漏 PFD 且记录永久 PENDING | `runtime/proot-app/.../ProotJobRunner.kt:257-262` | `store.put(pending)` 后 `jobExecutor.submit{...}` 若抛 `RejectedExecutionException`，`inputPfd`/`outputPfd` 永不关闭，journal 留下永久 PENDING 记录，后续 `isExecutionAvailable()` **恒为 false**（`store.activeJobIds()` 非空）。建议 submit 包 try/catch，失败时关闭两个 PFD 并写 FAILED |
| P2-3 | `McpOAuthCoordinator` 结果事件无 replay 且用 `tryEmit` | `app/.../mcp/oauth/McpOAuthCoordinator.kt:49-50,133` | `MutableSharedFlow(extraBufferCapacity = 16)`（`replay = 0`）+ `tryEmit`；UI 若在 `handleCallback` 之后才订阅则事件丢失。建议 `replay = 1` 或 `Channel(CONFLATED)` |

---

### "看起来像 bug 但实际没问题"（排查过并确认安全）

这一节用于界定审查深度——以下 12 项高风险点已逐项排查，**结论是安全的**：

1. **MCP/模型的"并发安全"自声明无法影响并行决策**：`EffectFootprintBuilder`（`EffectFootprint.kt:103-118`）只取 `descriptor.operationClass` 与 `requiredCapabilities`；`McpDynamicToolBridge.kt:167` 硬编码 `operationClass = NETWORK`（exclusive），`serverProvidedHints` 无路径进入。**文档承诺成立**
2. **`ToolScheduler` 无丢唤醒、无超发**：`scheduleReservedBatch:161-176` 先取 `slotStateSignal` 再 `admitNext`；`releaseSlot:310-319` 在锁内 `getAndSet` + `complete`；`tryClaimSlot` 把检查与占位放同一把锁；`inFlight` 以全局唯一 `toolCallId` 为键
3. **`MIGRATION_22_23` 的 `require(mode != CUSTOM)` 不可达**：`SessionPermissionConfigRepository.kt:85,101` 与 `SessionPermissionEditService.kt:109-110` 均拒绝把 CUSTOM 写入 app 默认行
4. **`bindOutput` 的 `maxOutputBytes.toInt()` 不会溢出**：`ToolDescriptor.MAX_OUTPUT_BYTES_CAP = 8 MiB`（`ToolDescriptor.kt:146`）
5. **`TurnLiveFrames` 的 `replay=1 + DROP_OLDEST` 可能吞终态帧，但有兜底**：`AppAgentRuntime.observe`（`AppAgentRuntime.kt:80-88`）在未观测到终态时用 `persistedPhase` 补齐
6. **路径穿越防护完整**：`PathResolution.resolveWithinRoot` 先证明 `candidate.startsWith(root)` 再逐段查符号链接，最终比对 canonical real path；`WorkspaceArtifactStore` 每个文件操作都过 `resolveContained` + region 校验
7. **命令注入不存在**：PRoot 走 `ProcessBuilder(list)`，`Argv` 是 argv 数组，`Script` 是调用方显式声明的 `/bin/sh -c`（`ProotJobRunner.kt:338-346,379-380`）
8. **`CancellationException` 捕获点均已 rethrow**：`ChatService.kt:3688-3690`、`ArtifactAvailability.kt:251-255`、`GoalTimeBudget.kt:214-217`、`McpOAuthCoordinator.kt:128-129`、`ChatService.kt:3360-3361`
9. **无 `GlobalScope`、无非结构化 `launch`**：所有 `CoroutineScope` 均为 `SupervisorJob() + Dispatchers.X`
10. **Room 迁移未发现非幂等破坏**：`MIGRATION_15_16` 的 `DROP INDEX` 无 `IF EXISTS`，但只会在 v15 库上运行一次；其余为 `CREATE TABLE/INDEX IF NOT EXISTS` 或 `ALTER TABLE ADD COLUMN`
11. **`ToolDispatcher` 的 `maxAttempts=2` 重试在生产中不可达**：全仓 `app/src/main` 无任何 `.copy(maxAttempts = 2)`（仅测试）
12. **Provider 流式解析健壮**：SSE reader 处理跨 chunk 的 UTF-8 边界、行长/事件上限；解码器把 `SerializationException` 映射为 `ProtocolError`；响应体在 `finally` 关闭（`WireModelProvider.kt:145-147`）
13. **无绕过 `ToolDispatcher` 的工具执行路径**：生产仅 `DefaultAppContainer.kt:494` 构造一次 `ToolDispatcher`，`.execute()` 调用点全在测试内

---

## 维度五：优化与删减

### 仓库卫生：优秀（出乎预期）

| 指标 | 实测值 | 结论 |
| --- | --- | --- |
| `git ls-files \| wc -l` | 3435 | — |
| 仓库体积 | **36M** | 健康 |
| `git ls-files build \| wc -l` | **0** | ✅ `build/` 未入库（本地 57G 仅磁盘问题） |
| `__pycache__`/`.pyc` 跟踪数 | **0** | ✅ 已 gitignore |
| `.bin` / `.flat` 跟踪数 | **0 / 0** | ✅ 未入库 |
| `.DS_Store` | 已忽略（`.gitignore:6`） | ✅ |
| 最大被跟踪文件 | 9 张 docs 截图 jpg（5.3M 合计）+ `gradle/verification-metadata.xml`(428K) | 合理 |
| **应删除（git 层面）** | **0 文件 / 0 体积** | 仓库本身干净 |

**唯一建议**：`.claude/` 当前仅在 `.git/info/exclude` 本地生效，新克隆会看到 `CLAUDE.local.md`/`settings.local.json`/`worktrees/`。建议补入 `.gitignore`（1 行）。

### 可删除（证据确凿，风险低）

| # | 对象 | 证据 | 收益 |
| --- | --- | --- | --- |
| 1 | **3 个死包装类** | `OpenAiChatAdapter.kt`(32行)、`OpenAiResponsesAdapter.kt`(28行)、`AnthropicAdapter.kt`(30行)——**已实测**：全仓引用数各为 1（即仅自身定义），生产 0 引用；`OpenAiChatProvider.kt:45-46` 直接调 `ChatCompletionsRequestEncoder`/`ChatCompletionsStreamDecoder` 绕过 adapter | 删 ~90 行 + 3 个公开类型，**零风险** |
| 2 | **8 个仅测试使用的 ID 值类** | `core/model/.../Identifiers.kt` 中 `MessageId`/`ToolResultId`/`ArtifactId`/`AuditEventId`/`RuntimeInstallId`/`OperationId`/`ScopeId`/`WorkspaceId`——唯一引用是 `IdentifiersTest.kt` | 删 ~120 行（24 个值类中的 33%）。**先确认无迁移规划** |
| 3 | **1 个死 typealias** | `extensions/mcp/.../McpOAuthPkce.kt:57` | 删 1 行 |
| 4 | **`:testing` 孤儿模块** | **已实测**：`grep project(":testing")` 于所有 `.kts` **0 命中**；模块内仅 `FixedEvalCatalogTest.kt`(105行) | 减 1 个模块 + 105 行。逻辑可并入 `:core:model` 测试 |
| 5 | **`scripts/debug/` 惰性归档** | 718 文件（19 个日期目录），占脚本总量 87%；含 140 个 `*.py.txt`/`*.sh.txt` 归档 | 删/归档 ~700 文件、约 3.5M。**注意**：该目录 README 明确要求"不得一刀切删除"，需先核对引用与来源 commit |

### 可精简

| 对象 | 证据 | 收益 | 风险 |
| --- | --- | --- | --- |
| `scripts/debug/` 过度留存 | 718/823 = **87%** 的脚本文件在此 | 脚本维护面降 ~85% | 中（历史验收命令复现依赖路径） |
| `docs/` 历史快照 | 524 个 md / **11M**：`completion-records/` 195 个(2.2M)、`bug-fixes/` 121 个(592K)、`evidence/` 104 个(1.6M)、`adr/` 49 个 | docs 减 ~50% | 低（只读历史，不影响构建） |
| 根 `build.gradle.kts` 中央 if/else | 611 行；模块路径三重声明 | 见 F7 | 中 |
| 重复的 SSE 契约注释 | "Contract (WHATWG SSE...)" ~25 行在 3 个 reader 中逐字重复 | 随 SSE 抽取一并删 ~75 行 | 低 |
| 本地 `build/` 磁盘膨胀 | `du -sh build` = **57G**，其中 `build/worktree-archives/` 3.0G（含两份全仓源码快照） | 释放 ~57G（**不影响 git**） | 无 |

### 可重构：提取公共 SSE 解析器（本次最高价值重构）

**证据**：`provider/*/.../ResponsesSse.kt`(258行) 与 `AnthropicSseReader.kt`(262行) 的 UTF-8 增量解码（`decodeStep`/`assembleCodePoint`/`sequenceLength`/`minCodePoint`）、`processLineBreaks`、`feed`/`finish`、`SseEvent` data class **逐行相同**；`ChatSseReader.kt`(242行) 为 data-only 变体。

**收益**：762 行 → 公共 ~250 行 + 3 个薄映射，**省 ~400 行**；更重要的是**三处协议 bug 只需修一次**。
**风险**：中（协议关键路径，需现有 `*SseReaderTest`/`*SseParserTest` 全绿护航）。

### 测试面

生产 1155 文件 / 171,212 行 vs 测试 691 文件 / **124,756 行**（比值 0.73）；仅 app 就 55k 测试行（androidTest 39,617 + test 15,650）。
**说明**：测试即验收证据（`docs/development/verification-matrix.md` 有此约定），**不可轻删**；但结合 F5（`TurnReducer` 生产未用）可看出约 2000 行测试绑定在非生产实现上——这部分是明确的收敛空间。

### 模块合并建议

1. **删除 `:testing`**——无任何依赖方，纯孤儿（已实测）
2. **`feature:files-allfiles` → `feature:files`**——6 文件 / 479 行，已是下游模块（32 → 31）
3. **（可选）`runtime:proot-ipc` 与 `proot-core`**——边界清晰（schema vs 协议），合并会模糊"跨 APK 协议岛"语义，**建议暂缓**
4. **`spikes/`**（3 模块 / 6 kt）——已用 `includeSpikes` 开关隔离（`settings.gradle.kts:66`），默认不构建，**保留**

---

## 优先级总表

| 优先级 | 维度 | 问题 | 位置 |
| --- | --- | --- | --- |
| **P0** | 四 | 并行批次进程死亡后恢复永久失效 | `RecoveryCoordinator.kt:32-36` + `HelixApplication.kt:73-85` |
| **P0** | 二 | `ChatService` 上帝类（4047 行 / 81 方法） | `app/.../chat/ChatService.kt` |
| **P0** | 二 | `:app` 单体（46% 代码） | `app/src` |
| **P1** | 四 | `requireBatchSettled()` 在事务前抛，丢成功结果 | `TurnCoordinator.kt:125-129,297` |
| **P1** | 四 | Goal 预算耗尽被记为"用户取消" | `GoalTimeBudget.kt:84-88,57-63` |
| **P1** | 四 | `synchronized` 内做 Room 事务 | `ChatService.kt:3422,3481` |
| **P1** | 三 | 斜杠 `/plan` `/act` 失效 | `ConversationComposer.kt:83-91` |
| **P1** | 三 | 文件管理器 → 会话断链 | `app/.../files/` 无 `stageAttachment` |
| **P1** | 三 | 加载态缺失（2 个指示 vs 80+ IO 点） | 全局 |
| **P1** | 三 | 原始错误码直出 + 无统一错误 UI | `ConnectorSection.kt:638-653,742` |
| **P1** | 三 | 无设计系统（304 dp / 71 sp / 40 AlertDialog） | 全局 |
| **P1** | 三 | 市场卡片文案错配 + 3 态状态机 | `MarketplaceSection.kt:349-355,548-574` |
| **P1** | 三 | 审批关键信息默认折叠 | `ApprovalCardScreen.kt:74-93` |
| **P1** | 三 | 模式菜单显示英文枚举名 | `ComposerMenus.kt:34,36` |
| **P1** | 二 | 分层反向依赖三条 | `core:storage→core:policy` 等 |
| **P1** | 二 | UI 直连 storage/provider 内部类型（10 文件） | `app/.../ui/` |
| **P1** | 二 | 双 Turn 状态机，`TurnReducer` 生产未用 | `core/agent/TurnReducer.kt` |
| **P1** | 二 | 手工 DI 巨型容器 + 可变单例破环 | `DefaultAppContainer.kt:437` |
| **P1** | 一 | `roadmap.md` 三处状态过期/自相矛盾 | `roadmap.md:5,19,24` |
| **P2** | 四 | 并发判定缺 `ResourceKeyExtractor` | `ToolScheduler.kt:53` |
| **P2** | 四 | PRoot 提交拒绝时泄漏 PFD | `ProotJobRunner.kt:257-262` |
| **P2** | 四 | MCP OAuth 事件无 replay | `McpOAuthCoordinator.kt:49-50` |
| **P2** | 三 | 跨页状态靠全局单例；组合期写导航回调 | `MainActivity.kt:261`；`ChatScreen.kt:96` |
| **P2** | 三 | 旋转丢 tab/筛选/草稿态 | `AndroidManifest.xml:40-42` |
| **P2** | 三 | UI 层承载业务逻辑与主线程 IO | `MarketplaceSection.kt:103,151-157` |
| **P2** | 三 | 超大 Composable（470 行 + 10-key LaunchedEffect） | `ChatScreen.kt:53-524` |
| **P2** | 二 | 根构建脚本上帝化 + 坐标冲突改名 | `build.gradle.kts:232-234,495-497` |
| **P2** | 二 | developer Manifest 组件膨胀 | 438 行 / 16 Activity |
| **P2** | 一 | `HXA-218.md` 未同步 main 状态 | `HXA-218.md:43` |
| **P2** | 一 | UI 持有 storage repository（文档未表态） | `overview.md:9` |
| **P2** | 五 | `.claude/` 未入 `.gitignore` | `.gitignore` |

---

## 建议的执行顺序

**第一批（立即，低成本高收益）**
1. 修 `RecoveryCoordinator` 的并行假设 + 拆分 `HelixApplication` 的 try 块（P0，不可自愈故障）
2. 修斜杠 `/plan` `/act` 映射（`ConversationComposer.kt:83-91`，2 行改动）
3. 对齐 `roadmap.md:5,19,24` 三处状态（纯文档）
4. 删 3 个死 Adapter 类 + 1 个死 typealias（零风险）

**第二批（本迭代，结构性）**
5. 抽 `ChatStateHolder` + `TurnLauncher` + `SubmissionAdmission`，把 `ChatService` 降到 800 行内
6. 上提跨层契约到 `core:model`，切断三条反向依赖边
7. 拆 `requireBatchSettled()` 语义；`renew()` 失败时设 `stopCode`
8. 补文件管理器 → 会话的交接入口

**第三批（下迭代，体验与债务）**
9. 建设计令牌对象，收敛 304 处 dp 与 40 处 AlertDialog
10. 全链路加载态 + 错误文案规范化
11. 提取公共 SSE 解析器（省 ~400 行）
12. 决定 `TurnReducer` 去留（合并语义 or 删除 + 清 2000 行测试）
13. 删除 `:testing` 孤儿模块；归档 `scripts/debug/`

---

## 后续文档

本报告的五维度之外，另有三份同批次文档：

| 文档 | 内容 |
|---|---|
| `2026-09-24-review-reevaluation.md` | 对 `REVIEW-2026-09-24.md` 的再评估：17 条回源码复验，3 条降级、1 条裁决、13 条确认为真 |
| `2026-09-24-structure-review.md` | 架构设计、目录结构、类内聚耦合量化（1690 类）、文件拆分重组清单 |
| `2026-09-24-supplement-verification-and-release.md` | **验证体系（CI 不跑设备测试）、发布就绪度（45 MiB APK / R8 关闭）、静态分析门禁盲区** |
| `2026-09-24-codex-browser-vs-helix.md` | 与 Codex `control-in-app-browser` / `mcp__node_repl__js` 的浏览器能力差距分析 |

补充审查 §1 为本报告 P0（进程死亡恢复）给出了**机制性成因**：CI 从不执行设备测试，且 `ProcessRecoveryTest` 的 9 个用例全部只 seed 单个工具调用——与 `PersistedTurn.init` 的错误断言共享同一前提，因此永远无法发现该缺陷。

---

*本报告所有结论均基于 HEAD `3cf89027` 的静态审查与 git 取证。标注"置信度中"的项目建议以设备/单测复现确认。审查过程未修改任何项目文件。*
