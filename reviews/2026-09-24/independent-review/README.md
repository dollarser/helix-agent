# Helix 项目综合代码审查报告（独立复审，2026-09-24）

**审查基线**：HEAD `3cf89027`（2026-09-24）+ 未提交工作树（16 个修改文件 + 未跟踪的 Root 文件管理新功能）。
**代码规模**：32 个恒建 Gradle 模块（另 3 个 opt-in spikes）/ 约 1,865 个 `.kt` 文件 / 主源码约 14–15 万行（`:app` 占约 38%）/ 测试约 14.5 万行。
**审查方法**：5 条独立只读审查线并行（每线一个专职代理，全部结论带 file:line 取证）+ 主代理独立机械核验（模块依赖图、i18n 键集合、KSP/kotlin 版本、2,371 条 markdown 链接、关键 P0/P1 代码路径逐行复核）。未运行构建/设备测试。
**与历史审查的关系**：仓库内已有同日 5 份审查（`reviews/2026-09-24/2026-09-24-*.md` 与 `reviews/2026-09-24/REVIEW-2026-09-24.md`，与本目录同级）。本次为独立复审：历史发现全部重新取证，**约七成仍成立，3–4 条被降级或推翻**（见各维度复核表）。

## 分维度详版报告

| 文件 | 维度 | 规模 |
| --- | --- | --- |
| [01-docs-consistency.md](01-docs-consistency.md) | 一、文档与代码一致性 | 212 行，30 行关键声明核验表 + 15 条问题清单 |
| [02-architecture.md](02-architecture.md) | 二、软件架构 | 627 行，模块依赖 mermaid 图、11 个大文件逐节分析、状态所有权与 DI 构造图 |
| [03-ui-interaction.md](03-ui-interaction.md) | 三、UI 与产品交互 | 489 行，导航图、5 条交互流状态矩阵 |
| [04-core-path-bugs.md](04-core-path-bugs.md) | 四、核心路径 bug | 228 行，逐条代码路径与历史裁定表 |
| [05-optimization.md](05-optimization.md) | 五、优化与删减 | 312 行，R1–R12 无引用证据表 |

## 总览

| 维度 | P0 | P1 | P2 | 结论 |
| --- | --- | --- | --- | --- |
| 一、文档与代码一致性 | 0 | 4 | 11 | **良好**。无"文档说 A 代码是 B"的硬偏差；问题集中在 09-22/23 分支收敛后未对账的状态快照 |
| 二、软件架构 | 2 | 8 | 5 | **模块级健康、应用层失守**。模块依赖是无环 DAG 且不变量全合规；但恢复契约与并行执行失配（P0）、ChatService 单点（P0）、UI↔应用包环 |
| 三、UI 与产品交互 | 0 | 10 | 32 | **偏弱**。i18n/颜色纪律优秀，但破坏性操作零确认、流式重组热路径未动、加载态系统性缺失、文件→Agent 断链 |
| 四、核心路径 bug | 1 | 9 | 7 | **有 1 个不可自愈的 P0**：并行批次进程死亡后全局恢复永久失效；新提交 8aa8ff97 的重新生成又引入 3 个 P1 |
| 五、优化与删减 | — | 4 | 17 | **仓库卫生优秀**（0 TODO、无孤儿依赖、锁健康）；最大税项是 core/agent 整族死代码（~1,770 行生产 + ~1,620 行测试）与三份逐行重复的 SSE 解析器 |

**最危险的三件事**（详见维度四）：
1. **C01/A01（P0）**：一个 Turn 有 2 个并行 RUNNING 工具调用时进程死亡 → 重启恢复在事务前抛 `IAE` → 该 Turn 永远卡在"运行中"，**且每次启动都失败、不可自愈**，一次坏 Turn 连带阻断全部恢复（含 Goal）。
2. **C02/C03（P1）**：最新提交 8aa8ff97 的"重新生成"在启动新 Turn **之前**先持久隐藏旧历史，失败不回滚；重生成较早回答还会连带隐藏其后未获回答的用户消息。
3. **C04（P1）**：框架合法产生的 UNKNOWN（副作用待复核）结算被批次不变量拒绝 → 整批成功结果无法回填模型上下文，Turn 被归为 INTERNAL。

---

## 维度一：文档与代码一致性

**总评**：文档纪律在同类项目中罕见地好。独立核验 30 类关键声明（模块数/执行域/渠道/ADR 状态/完成记录/manifest/进程/授权设计）**全部与代码一致**；三语字符串键集合完全对齐（1,362 键 × 3，0 缺失、0 占位符漂移）；完成记录索引 193 条目与实际文件零偏差。**没有发现掩盖数据/安全风险的 P0 硬偏差**。问题全部是 2026-09-22/23 分支收敛后**叙述性状态快照未同步**，会诱导实施者重做已完成工作（AGENTS.md 明令禁止）。

### P1 问题（4 条）

| # | 问题 | 证据（file:line） | 影响范围 | 严重度理由 | 改进建议 |
| --- | --- | --- | --- | --- | --- |
| D1-1 | `roadmap.md`"新增对话交互需求"整节为 09-22 过期快照，**5 处**与 status.md/自身表格/ADR 矛盾（历史 3 处复验仍成立，新增 2 处） | `docs/development/roadmap.md:19`（"129 尚未合并 main" ↔ `status.md:18` 已整合）、`:5`（"217 仅完成格式成本准备" ↔ 同文件 `:249`"已交付"，**同文件自相矛盾**）、`:24`（"proposed ADR-AGENT-010" ↔ `adr/agent/010:3` accepted）、`:26`、`:39` | 所有按 AGENTS.md 必读 roadmap 的实施者 | 明确状态失实+自相矛盾，直接诱导重做 129/217 已完成工作 | 整节改写为"已交付"口径并链接完成记录；或节首标注"历史快照，以 status.md 为准" |
| D1-2 | `next-work-plan.md` 整页为 09-23 旧快照，4 项与 status.md/HEAD 矛盾，且被 roadmap 作为活文档引用 | `docs/development/next-work-plan.md:3`（"当前 main 仍为此前整合基线"）等 | roadmap 下游读者、接手执行的 Agent | 4 项具体当前状态失实 | 更新为当前状态或标注截止时点并从 roadmap 移除活链接 |
| D1-3 | `extensions.md` 称"安装所有权与签名索引 ADR 仍为 proposed，实现必须等待显式接受"——**方向性失实**（实际已 accepted 且 HXA-129/130 已交付），与 ADR 权威入口直接冲突 | `docs/architecture/extensions.md:24` ↔ `adr/connectors/003:3`、`004:3`（均 accepted）、`adr/README.md:25` | Connector 方向实施者 | "文档说未批准、实际已批准并交付"，可能让实施者拒绝合法工作 | 改为"两 ADR 均已接受并交付离线范围" |
| D1-4 | `task-experience.md §2` 承诺"工作区详情页提供文件/任务/终端/变更四入口"，落位 HXA 全标已交付，**但代码中不存在该页**；文件→会话交接入口也不存在 | `docs/product/task-experience.md:7`；`app/src/main/.../ui/` 无 WorkspaceDetail（grep 0 命中）；`files` 包 `stageAttachment` 调用 0 | 按文档验收导航链的产品/测试人员；"从文件出发"的核心用户路径 | 文档把不存在的产品路径描述为已交付 | 二选一：实施统一工作区详情页+文件行"在当前任务使用"；或修订 §2 为未交付并立项 |

### P2 问题（11 条，摘要）

- **D1-5** `terminal.md:3` 页首"不据本页宣称已有独立终端"滞后（HXA-197/198 已交付）；**D1-6** `mobile-tool-orchestration.md:31` A2A 标"未实现"（HXA-077/078/079 已交付）；**D1-7** `overview.md:48` 终端链对冲措辞滞后。
- **D1-8** HXA-218/219 完成记录仍称"未合入 main"（实际 `1c1b8ab7` 已入 main，收敛记录在案）——证据链需跳转才能弥合。
- **D1-9** `TurnReducer`（694 行）在文档中作为已交付核心状态机，但**生产零引用**（仅 KDoc 提及）——文档未跟踪"串行参考 vs 并行生产"的架构分叉（与维度五 R1 同源）。
- **D1-10** 7 个 ui 文件直接 import `core.storage.repository.*`，`overview.md:9` 分层规则未对此表态（历史 10 个，略有收敛）。
- **D1-11** "turn regeneration"已进 main（`8aa8ff97`：ChatService +102 行、StorageGarbageCollector 新 143 行）但 status/roadmap/完成记录**全无足迹**——违反仓库自身"完成记录保存结果"纪律。
- **D1-12** requirements.md 功能清单缺"developer 手动终端"（代码 13 文件完整交付）。
- **D1-13** base strings 中 136 个键为纯英文值（HXA-069 声明 base=简体中文），`check-i18n.sh` 只校验键不校验值。
- **D1-14** task-experience §4 入口命名（"添加能力"）与实际 UI（"发现市场/已安装与自定义"双 Tab）及 3 态状态机不符。
- **D1-15** `android-platform-capabilities.md §3.2` 浏览器模块规划树与实际包结构不符。
- **D1-16**（主代理独立核验补充）`evals/m10/README.md:13` 相对链接 `../../docs/development/m10-closure-followup.md` **目标不存在**——全仓 2,371 条链接中唯一断链（审查线脚本未覆盖 evals/ 目录，漏检）。

**改进方向汇总**：① 立即一次性文档对账（<1 小时纯文档改动消 4 处 P1）；② task-experience §2 需产品决策（做页或改文档）；③ 在 `scripts/check-all.sh` 增加两条自动化一致性断言（roadmap 状态列 vs status.md 差集告警；"proposed/未实现"关键词必须能在 ADR Status/status.md 找到对应），防止状态再次漂移。

---

## 维度二：软件架构

**总评**
- **分层与模块划分**：模块级依赖是干净的无环 DAG，AGENTS.md 全部架构不变量（core 不依赖 UI/基础设施、UI 不直达 DAO/网络/执行器、consumer 排除 PRoot/CLI、QuickJS isolated 非导出、PRoot/CLI 私有进程、冷绑定、A2A 仅 Client、无 Hilt）**静态核查全部合规**。问题全部在 `:app` 内部。
- **依赖关系**：模块依赖集中在根 `build.gradle.kts`（611 行）统一声明（仅 app/proot-app/cli-app/terminal-renderer 有独立 build 文件）——DRY 但非局部化，见 A13。
- **可扩展性**：Tool/Provider/MCP/Skill/A2A 接缝稳定、可无 app 测试；"最先被压垮"的三点：① 新聊天功能仍要改 ChatService 2,875–3,532 行区域；② 新并发/恢复语义必须同步串行参考与并行生产模型（已在 RecoveryCoordinator.kt:34 失配）；③ 新文件后端要改 listing/preview/share/mutation 全部分支（未提交 Root 接入已展现该成本）。
- **耦合度**：风险集中在 ChatService、跨层恢复模型、DI 时序环、UI↔应用包环。

### P0 问题（2 条）

**A01 并发执行与启动恢复使用不同基数契约（与维度四 C01 同根）**
- 机制：生产 `ToolScheduler` 已支持并行（`ToolScheduler.kt:345,348` DEFAULT_MAX_CONCURRENCY=2 / HARD=4），但恢复输入 `PersistedTurn.init` 仍 `require(RUNNING ≤ 1)` 并注释"串行执行（第一版）"（`core/agent/.../RecoveryCoordinator.kt:32-36`）；`RecoveryCoordinatorApp.kt:135-147` 在恢复事务**之前**从 DB 行构造该 DTO；`HelixApplication.kt:68-81` 用单一 try/catch 包住 recover + onRecoveryCompleted + Goal 对账。
- 影响：合法并行快照（2 个 RUNNING 只读调用）+ 进程死亡 → 每次启动 IAE → Turn 永久 RUNNING_TOOL、Goal 不 park、修订路径被 REVISION_SESSION_BUSY 永久阻塞、**不可自愈**。
- 建议：`PersistedTurn` 改持 `runningCallIds: Set`；RecoveryPlan 逐 call 表达；`scanPersistedTurns` 对坏数据降级为"标记待核查"而非抛异常；拆分 Application 的 try 块使单 Turn 失败不阻断全局；回归固定"非终态父+2 RUNNING，连续两次启动"。

**A02 ChatService 同时拥有提交、运行资源与产品页面的单点**
- 证据：`ChatService.kt` 4,047 行、86 个直接公开函数（含 5 override）、16 参数构造器、10+ 个 StateFlow 屏幕状态；草稿/附件/egress/Goal/Queue/Steer/取消结算/全部 UI 投影共用一个类（`:210-269, :398-419, :1668, :2875, :3384, :3733, :3888`）。
- 影响：会话/任务/后台运行/审批/恢复/附件/Provider 选择与全部 UI 测试；是维度三多数交互问题与维度四 C02/C03 的结构性根因。
- 建议：见维度五拆分方案（先 `TurnRuntimeOwner`+`SubmissionAdmission`+`ChatPresentationStore` 三刀，字段所有权随类型一起迁移，留 150–250 行兼容门面）。

### P1 问题（8 条）

| # | 问题 | 证据 | 影响 | 建议 |
| --- | --- | --- | --- | --- |
| A03 | 持久 Turn 状态与 UI 状态多写入者：coordinator 自称 single owner，但 live cancel（`ChatService.kt:2696,2718`）与恢复（`RecoveryCoordinatorApp.kt:172`）直接更新 turn；`_screen` MutableStateFlow 被传给 ChatRecoveryActions/TurnRecoveryActions/ChatToolTimeline 多方写 | `TurnCoordinator.kt:150-152,537`；`TurnRepository.kt:78-85`（校验后无 CAS） | 停止/终态竞争、恢复展示、流式 overlay 的正确性依赖人工复制约定 | 窄 `TurnLifecycleStore` 统一转移契约；`ChatPresentationStore` 单 writer + typed events；流式文本 keyed by turnId |
| A04 | app 内 feature/kind 混排形成 **UI↔应用包环**：恢复操作准入复用放在 ui 的 recoverySummary，应用服务反向依赖展示目录 | `chat/TurnRecoveryActions.kt:3-5,99-107`；`ui/RecoveryFactsProjection.kt:48-116`（ui→chat 59 条 import，chat→ui 反向 7 条） | 表示层模型变更可改变应用动作准入；阻碍独立业务测试与模块化 | `RecoveryDecision` 领域投影迁 recovery 包；feature-first 收拢 UI |
| A05 | **egress UI 绕过应用服务直接操作持久仓储**（边界违规链完整）：MainActivity 传 `container.storage.highSensitivityRules` → EgressRuleSection 直接 all/save/revoke | `MainActivity.kt:407`；`ui/SettingsScreen.kt:64`；`egress/EgressRuleSection.kt:59,72,107,124` | Advanced 出网规则设置的时序/审计无法无 UI 验证；字面违反"UI 经应用服务访问"不变量 | `EgressRuleService`（100–160 行，注入 Clock/Repository），UI 仅编辑 draft |
| A06 | 手工 DI 的**时序耦合**：broker→ChatService→pipeline 构造环靠 nullable holder 后置安装（提前回调 fail-closed 抛错而非自动批准，安全但脆弱）；Provider image/Goal/PRoot 回调依赖声明顺序 | `DefaultAppContainer.kt:155-175,418,445-460,701`；`tool/ApprovalCardSinkHolder.kt:18-28` | 合法可调用时点不由类型表达，重构易引入初始化故障 | 先构造 `ApprovalEventHub` 双方依赖窄端口；增加 `AppAssemblyInputs` 显式测试构造；不引入 Hilt |
| A07 | ProotJobRunner 将协议准入、资源 owner、进程启动、结果物化耦合（PFD/executor/LiveJob/名额/deadline/archive 同交织），资源 finally 正确性跨多阶段 | `ProotJobRunner.kt:124-173,204-282,284-393,616-689,704-770` | 后端扩展/输出协议修改波及生命周期关键路径 | 抽 `JobInputPreparer`/`ProotProcessLauncher`/`JobOutputMaterializer`/`JobLeaseSupervisor`，Runner 保留唯一资源 owner |
| A08 | 浏览器 facade 内含持久偏好、页面生命周期与工具能力状态；直接公开可变协作者 | `BrowserController.kt:78-89,535-589,722-770`；`BrowserScreen.kt:70-124` | 持久化与 View 生命周期难独立验证 | `BrowserProfileStore`/`BrowserPageSession`/`BrowserToolSession`；隐藏可变协作者为只读 StateFlow |
| A09 | **Connector OAuth 轮询协议由 Compose scope 驱动**：页面退出改变协议任务寿命；device-code 到期/SlowDown 循环在 UI 内 | `ConnectorSection.kt:469-473,517-522,698-743` | device-code 登录在旋转/导航下无法只测协调器覆盖 | `ConnectorOAuthCoordinator`（attempt identity + StateFlow，注入 Clock/Delay） |
| A10 | 文件后端接口不统一：Root/SAF/workspace 在门面逐方法分支，mutation 另走 ManualFileOperations 或旧 fallback，接口返回门面嵌套模型（**未提交 Root 接入正是暴露点**） | `FileManagerService.kt:254-255,329-365,430-463,496-502` | 每新增一个后端迫使多个现有方法修改 | `FileBrowseBackend`/`FileBackendResolver` + 独立值对象；fake 同一生产 backend 接缝 |

### P2 问题（5 条）
A11 Dispatcher 内部阶段状态聚集（对外仅 2 函数、领域内聚高，抽内部类型即可，勿做可重排插件链）；A12 WorkspaceArtifactStore 宽 API（按 Reader/Publisher/Mutations 窄接口）；A13 **根 build.gradle.kts 611 行集中定义全部 32 模块**（15 处特判 + 10 处 androidTest 三元组 + 2 处坐标冲突 group hack——hack 正是集中定义的症状；建议 convention plugin + 每模块 build 文件）；A14 flavor 同名 object 接缝缺共同类型（RootModule 混 UI/工具注册/全局状态）；A15 新协议 Provider 仍需改中心枚举/工厂。

### 历史审查复核（要点）
"三条反向模块依赖"（历史 P0）**撤回**——实际边全部是正常领域/port 依赖；"app 占 46%"收窄为主源集口径 38.3%（46% 含测试/源集口径）；"TurnReducer 应直接删"**撤回删除建议**（RecoveryCoordinator 仍活用其串行假设，先补契约映射再处置）；"runtime 8 模块过拆"无充分证据（执行域/JVM schema/Binder/client/service/第三方 renderer 边界各有依据）。

### 目录结构与大文件（跨维度合并）
- **目录结构**：顶层按执行域（core/provider/runtime/tools/extensions/feature）划分清晰且与 ADR 对齐；`:app` 内 35 个包 feature/kind 混排（`ui` 108 文件/18,222 行平铺，`chat` 59/10,849）。`app/internal`（实为 LineStore 持久化端口，包名过泛）、`app/companions`（仅一个生产零引用的 `RuntimeApkPolicy`，旧 companion 模型遗留）是命名误导点。Kotlin `internal` 是模块级可见性，包重命名不建立编译隔离——模块化决策应基于测量收益而非目录对称。
- **大文件拆分方案**（11 个文件逐一分析，见 02/05 报告）：ChatService 4,047（必须拆，6 型方案）、ToolDispatcher 1,174（5 型内抽，入口稳定不必拆类）、ProotJobRunner 942（4 协作者）、BrowserController 877（4 型）、WorkspaceArtifactStore 779（内聚可接受，低优先）、DefaultAppContainer 766（3 段装配抽取）、TurnCoordinator 681（先修 P1 再拆结算）、TurnReducer 694（不拆——整族死代码，见维度五 R1）、HelixMigrations 615（**形态合理，不建议按版本拆文件**，保持 append-only 单文件）。

---

## 维度三：UI 与产品交互

**总评**
- **界面一致性**：i18n 工程纪律优秀（三语言键集合全对齐、0 占位符漂移、Compose 用户可见硬编码 0 处）；但设计系统缺位——351 处硬编码 dp、33 处裸 AlertDialog、无 Spacing/Typography 令牌、五类组件（列表行/空态/错误条/加载条/菜单 pill）复制粘贴；中文术语分裂（"产物"8 键 vs "成果"2 键、"会话" vs "对话"7 处）。
- **交互流程**：聊天主链状态机是全 app 最完整的（回执/披露/排队/转向/修订/重新生成/预算停止都有 UI 落点）；但**破坏性操作确认普遍缺失**、文件管理→Agent 交接断链、加载态系统性缺失。
- **体验**：流式期间重组热路径整体未动（历史发现复验全部仍成立 + 新发现每 token 一次 Room 读），长会话流式掉帧/弱机 ANR 风险真实。

### P1 问题（10 条）

| # | 问题 | 证据（file:line） | 影响范围 | 严重度理由 | 改进建议 |
| --- | --- | --- | --- | --- | --- |
| U1 | 回收站"清空"与"单条彻底删除"**无确认直接永久删除**；未提交 Root scope 进入该面板后风险面扩大到系统文件 | `ui/FilesScreenLayout.kt:124-129`；`ui/FilesScreenActions.kt:297-320` | 全部用户的文件管理核心流；误触=不可逆数据丢失 | 破坏性批量操作零确认 | 两处加确认对话框（含数量/不可逆提示）；Root scope 下显式提示系统文件 |
| U2 | 浏览器底部**常驻三个 11sp 小字清除按钮**（清 Cookie/缓存/历史）无确认，`clearHistory` 还关闭全部标签重置为空白 | `feature/browser/.../ui/BrowserScreen.kt:263-265,579-597`；`BrowserController.kt:465-488` | 全部浏览器用户；误触=丢登录态/历史/全部标签 | 低门槛高频误触 + 多类数据丢失 | 加确认（至少"清历史"）或收进菜单二级 |
| U3 | 文件管理器→Agent **无"附加到会话/在当前任务使用"入口**；工作区四入口详情页不存在（文档 §2 承诺） | `stageAttachment` 全 app 仅 `ui/ChatScreen.kt:401`；`files/` 0 命中 | "从文件出发"的核心产品路径 | 文档级产品路径缺失，文件流与 Agent 流互不相通 | 文件行/预览加"附加到会话"（调 `chatService.stageAttachment`）单向桥先行 |
| U4 | 流式重组热路径整体未动：每 token 全量 StateFlow emit + `conversationEntries` O(K×N) 重算（LazyListScope 内每次重组直接调）+ contentVersion 每帧重启 LaunchedEffect + Markdown 行内 4 处 Regex 重编译 + **新发现每 token 一次 Room 读** + 搜索态每 token 主线程全文扫描 | `chat/ConversationEntry.kt:11-31`；`ui/ConversationSection.kt:104-108,272-278,294`；`ui/MarkdownBlock.kt:40,44,53,77`；`ChatService.kt:3722-3726,3975` | 长会话（数十轮以上）全部用户；100 轮/400 消息会话 ≈ 4–12 万次 filter/token | 核心聊天流用户可见性能退化，修复成本低 | conversationEntries 单次遍历分桶；contentVersion 去 effect-key 化；Regex 提常量；publishTurn 去掉每 token Room 读；搜索移 IO+debounce（<100 行改动消最大两项成本） |
| U5 | 市场卡片文案错配（展开键显示"命令详情"、卸载弹窗取消键复用"关闭"）+ 状态机仅 3 态（文档要求 6 阶段）+ 安装后无"在当前任务使用"落点 + 启停/卸载主线程直调 | `marketplace/MarketplaceSection.kt:349-354,540,548-574,151-157,103` | 全部扩展安装/管理用户 | 用户可见的键位语义错乱 + 文档路径断裂 | 语义化文案；状态机扩 6 阶段；启用后加落点；动作移 IO |
| U6 | 斜杠命令 `/plan`、`/act` 选中后都切到 CHAT——Plan/Act 从斜杠入口不可达 | `ui/ConversationComposer.kt:83-91` | 100% 斜杠命令用户；Plan 模式可达性受损 | 核心模式入口断链，**修复仅两行** | 改 `AgentMode.PLAN` / `AgentMode.ACT` |
| U7 | 模式菜单/终端页**英文枚举名直出**（中文界面显示 Chat/Plan/Act/Goal、phase/stopReason 原始枚举） | `ui/ComposerMenus.kt:34,36`；`developer/.../ManualTerminalScreen.kt:130,200`；`DeveloperManualTerminal.kt:235-237` | 中文界面全部用户；developer 终端用户 | i18n 整体优秀，此处是显眼的术语断裂 | 模式名走 stringResource（4 键）；枚举→stringRes 映射表 |
| U8 | 加载态系统性缺失（12 个进度指示 vs 100+ IO 点）：文件列表加载中误报"此目录为空"、能力页空白、MCP 连接测试/市场安装/浏览器下载 SAVING/Root 请求/准备页均无进度 | `ui/FilesScreenEffects.kt:28-44`；`ui/CapabilitiesScreen.kt:74-82`；`connector/ConnectorSection.kt:333-352`；`BrowserScreen.kt:548-553` 等 | 全部长操作用户，多流共性 | 用户无法判断"在干什么/卡没卡" | 统一 ProgressCard；文件列表区分 空/加载/错误；连接测试加超时提示 |
| U9 | 错误展示不规范：`OAUTH_DEVICE_EXPIRED` 原始码直出、连接器失败直出 `e.message`（全仓 14 处原始异常直传用户可见状态）、不满足"问题+已完成+下一动作"规范 | `connector/ConnectorSection.kt:380-383,570-572`；`ui/FilesScreenEffects.kt:40` 等 14 处 | 扩展/连接器/文件页用户在错误场景下的理解与恢复 | 与 §5 验收目标直接冲突 | 建 `ErrorMapper`（安全码→stringRes 三元组），UI 只渲染映射结果，全工程禁 `Text(e.message)` |
| U10 | 浏览器旋转即丢页面内容：MainActivity 无 configChanges，onDestroy detach 销毁 WebView，重建后仅占位文案，**无"重新加载"入口**，用户须手动重输 URL | `AndroidManifest.xml:44-46`；`MainActivity.kt:152-160`；`BrowserScreen.kt:255-260` | 浏览器全部用户（旋转/字体缩放都触发） | 核心浏览流在常见手势下内容丢失且恢复路径不显式 | 占位页改"该页面已停止，点击重新加载"；中期 WebView saveState/restoreState |

### P2 要点（32 条，摘要）
审批卡折叠态缺预期影响/verifier、PENDING 无剩余时间；停止键出现致输入区高度跳变；"▴/▾/✓/🕶️"字符图标族无障碍混乱（10 处 Icon 无 contentDescription）；空会话建议点击覆盖未发送草稿；会话搜索态跨会话串扰；跨页状态靠全局单例（`openSession` 不传导航参数）；旋转丢局部状态（rememberSaveable 仅 12 处）；进程死亡无 last-open 恢复；Terminal 抽屉项是外部 Activity 的"假路由"；文件 scope 失效静默弹回首页无原因；能力页未知 key `error()` 崩溃路径（潜在 P1）；准备页无进度/取消；MCP 设备码无倒计时；Toast 11 处（0 Snackbar）；文件列表非懒加载（≤500 项全量组合）；工作区来源名硬编码英文 "Workspace"（`FileManagerService.kt:186`）。完整 34 条见 [03-ui-interaction.md](03-ui-interaction.md)。

### 未提交工作树评估（Root 文件来源 + 两级抽屉）
方向合理、渠道隔离正确（consumer 桩 isSupported=false、UI 入口正确隐藏）、测试同步更新。但合并前需补：① `requestRoot()` 无进度反馈；② `sources()` 在 composition 首次于**主线程**做 libsu root 检查（弱设备可能阻塞数百 ms~秒级）；③ 抽屉展开态 `remember(currentRoute)` 随路由重置；④ Root scope 可写时 U1 的无确认删除直接作用于系统文件（风险放大）。ChatService 两处 WIP 修复（草稿按会话隔离、INTERRUPTED 不占门）是 UX 正确性改善 ✅。

### 历史复核
UI-1（斜杠失效）/UI-2（英文枚举）/UI-4（加载态）/UI-5（原始错误）/UI-6（文件断链）/UI-7（市场文案）全部仍成立；UI-3（设计系统）降 P2（颜色 token 100% 合规、i18n 优秀，属维护成本债）；UI-8（审批卡）降 P2（折叠态已比历史版本好，两键设计符合 HXA-209）。

---

## 维度四：核心路径 bug

**方法说明**：CONFIRMED = 错误分支及后果有完整静态证据（未设备复现）；PLAUSIBLE = 依赖外部时序/平台行为。

**总评**（各关键路径风险评级）：停止与进程恢复 **严重**；Turn 发送/修订/重新生成 **高**（新重新生成路径绕过修订的原子替换）；工具批结算 **高**（UNKNOWN 合法态被不变量拒绝）；Provider 流式 **高**（Responses 参数缓冲无累计上限）；Runtime IPC **高**（QuickJS 契约不符、PRoot 异常路径孤儿风险）；存储/产物/压缩/A2A/MCP **中**。

### CONFIRMED（11 条）

**C01 — P0：并行批次进程死亡后，全局启动恢复重复失败（历史 P0，复验仍成立，8aa8ff97 与工作树均未修复）**
- 代码路径：`AgentLoop.kt:407-410` 开始批次 → `ToolScheduler.kt:239-249` 可并发提交多个不冲突读 → `ChatToolCalls.kt:423` 各自持久化为 RUNNING → SIGKILL → 重启 `HelixApplication.kt:68-81` → `RecoveryCoordinatorApp.kt:75→135-147` → `RecoveryCoordinator.kt:29-36` 构造 `PersistedTurn` 时计数 2 抛 `IllegalArgumentException("at most one RUNNING tool call per turn (serial execution)")`。
- 为什么每次失败：断言在恢复事务**之前**的扫描阶段抛出，事务与"终态父 Turn 下未结算子调用"补救都没机会运行；Application 只记日志不改行。
- 影响：Turn 永久 RUNNING_TOOL；`onRecoveryCompleted()`/排队输入 park/Goal park/提醒对账全部跳过；**一个坏 Turn 阻断整个此次恢复**（不只自身）；修订的全会话终态检查也不通过。普通 Stop 修不了（重启后无内存 active owner，进入只允许 INTERRUPTED 的分支抛错）；唯一清理路径是隐私永久删除会话（破坏性）。
- 修复方向：见 A01；回归应覆盖 2+ RUNNING、混合 pending/approval、重复恢复、终态父场景。

**C02 — P1：重新生成先隐藏历史、再尝试启动，替换失败没有回滚（新提交 8aa8ff97 引入）**
- `ChatService.kt:2591-2602` 单独执行 supersede + 刷新 UI，**然后**才 submitTurn；supersede 未与新 Turn 创建绑定在同一事务（`MessageRepository.kt:92-98`）。submitTurn 可返回 false（`:2800-2801,2834-2838`）或 busy 拒绝（`:3450-3452`），调用者不处理返回值。
- 触发：supersede 成功后进程死亡；或检查后另一发送先占会话；或 Provider 检查后变化致准入拒绝。
- 后果：旧回答及之后有效消息被持久标记 superseded（普通历史查询过滤，`MessageDao.kt:18,49-56`），但没有替代 Turn；`supersededBy` 可指向从未创建的请求。
- 修复：仿照修订路径（`TurnCoordinator.start():598-631`）在同一事务内提交 supersede + 新 Turn + 回执；启动拒绝前不改变有效历史。

**C03 — P1：重新生成较早"回答"会隐藏其后的未获回答用户消息（8aa8ff97 引入）**
- `ConversationSection.kt:318-322` 只要求目标是最后一个**非 user** 消息，未要求它属于最新用户输入。可确定触发：U1→A1 成功；再发 U2，Provider 在产生任何 assistant 前失败 → 最后非 user 仍是 A1，重生成按钮可用 → 点击后 U2 被 supersede，重跑的却是 U1。
- 后果：用户只是重生成上一回答，后续已提交问题从有效历史及后续模型上下文消失；过期 UI 回调还能传更早 assistant（服务端无 latest/superseded 守卫）。
- 修复：定义"最新可重生成回答"与最新用户输入的绑定；同一事务验证目标仍有效、目标之后无新 USER；更早消息按 fork 处理。

**C04 — P1：合法 UNKNOWN 结算阻止全批结果进入模型历史，Turn 归类 INTERNAL（历史 P1，复验仍成立）**
- `ToolDispatcher.kt:896-908,958-963` 合法返回 requiresReview → `ChatToolCalls.kt:268-290` 先持久结算再标 UNKNOWN → `TurnCoordinator.kt:125-128,297` 在消息事务**前** `require(none UNKNOWN)` 抛 IAE → `ChatService.kt:3703-3712` 归 FAILED/INTERNAL。
- 触发不需要取消竞争：一个正常调用 + 一个超时/输出 schema 错误的调用同批即可到达。
- 后果：tool_results 表已有结果，但整批（含成功 slot）的模型可见 TOOL 消息未写入（`:306-314` 事务没执行）；用户看到笼统"内部错误"而非"副作用待复核"。
- 修复：`requireBatchSettled` 分离 PENDING/UNKNOWN；已完成结果先按原序原子回填，UNKNOWN 进入明确复核/中断终态。

**C05 — P1：Responses 参数累计缓冲无上限**
- `ResponsesStreamDecoder.kt:275,291-305` 为每个函数调用建 StringBuilder 并无条件追加全部 arguments delta，直到 done；上层 `ModelStreamState.kt:134-141` 的限额保护的是另一份副本且只设 errorCode，`AgentLoop.kt:365-372` 继续 collect，传输层 `WireModelProvider.kt:131-135` 只按 decoder 协议结束停止。
- 触发：自建/异常 Responses 服务对已开始函数调用持续发送合法小块 delta 不发 done → 主进程堆持续增长，设计的参数上限约束不了实际内存。
- 修复：decoder 自身加逐调用+总参数字节上限，超限清缓冲结束流；accumulator 不可恢复错误向传输传播取消。

**C06 — P1：JavaScript 工具契约 256 KiB、实际 64 KiB 即失败（历史 #2 复验成立）**
- `CodeJavascriptRunTool.kt:94,222-230` 用默认 256 KiB 限制但不传 outputFile；`JsExecutionService.kt:380-388` 无 PFD 且超 `PARCEL_INLINE_MAX_BYTES`（64 KiB）返回 OUTPUT_LIMIT。
- 触发：`return "x".repeat(70000)` 执行正常却失败；错误文案还提示"超过 256 KiB"，按提示缩小到 100 KiB 仍失败。
- 修复：生产工具创建私有 outputFile 有界读回 + finally 清理，或同步降低契约。

**C07 — P1：未提交 Root 文件后端把流式传输变成无界整文件堆缓存（工作树新发现）**
- `RootFileModule.kt:145-148`（developer）先 `readAllBytes`；`:160-167` 写端 `ByteArrayOutputStream` close 再 toByteArray；`RootFileAccessor.kt:143-147` 先收集整份 Base64 行再 join 解码；`ManualFileTree.kt:26-38,98-105` 的 hash 复验再整文件读。
- 触发：developer 文件管理器复制/移动大型 Root 文件 → 主进程同时持有 Base64 文本+拼接文本+解码数组，取消检查被延迟，无文件尺寸准入。
- 修复：**合并前**必须恢复真实流式（PFD/暂存文件 + 分块读写 hash + 取消传播 + 配额）。
- 注：工作树在审查期间持续演进（另一并行会话在开发），合并前应对最新版重核本节。

**C08 — P2：孤儿 GC 无生产触发入口**——8aa8ff97 的 `StorageGarbageCollector`（143 行）仅被 `PrivacyDeletionService` 封装，全仓生产无 `cleanOrphanFiles` 调用者；孤儿内容经过一小时仍不会自行回收。（注：接线前必须先解决 P06 的并发协议，否则误删风险。）
**C09 — P2：聊天工具时间线先全量查库、最后才截断**——`ChatScreenProjection.kt:43-78` 查全部 Turn→逐 Turn 查调用→逐调用查结果→`takeLast(200)`，长期会话 O(T+C) 查询 + N+1。
**C10 — P2：QuickJS 拒绝已解析请求时不显式关闭接收的 PFD**——`JsExecutionService.kt:110-133` 三个拒绝分支直接 return，PFD 关闭只在 `:181-185` 的执行路径（bounded，isolated 实例回收后可释放，非永久泄漏）。
**C11 — P2：OAuth 回调经 replay=0 SharedFlow + tryEmit 发送**——授权期间页面离开 composition，回调在无订阅者时完成：token 已保存可重探（凭据不丢），但自动测试不补执行、失败原因消失（`McpOAuthCoordinator.kt:49-50,125-134`；`ConnectorSection.kt:261-282`）。

### PLAUSIBLE（6 条）
P01（P1）PRoot 运行期异常可能留下无人管理的进程组（`ProotJobRunner.kt:526-546` catch 时 process.isAlive 不 kill，finally 又撤 watchdog/移除 liveJobs；清理缺口确定，平台异常未注入）；P02（P1）重新生成附件检查 suspend 期间切换会话，可能把原请求重跑在新会话（`ChatService.kt:2565,2589,2592,2596-2602` 未传 expectedSessionId）；P03（P1）MCP 缺应用级整体握手/元数据 deadline（应用侧无 timeout 确定；SDK 0.15.0 内部行为未展开，故历史"必然永久挂起"降为 PLAUSIBLE）；P04（P2）QuickJS bind 消耗执行 deadline（计时事实确定，历史"小超时必灭 P0"撤回——生产工具固定 10 秒）；P05（P2）QuickJS 大输入先完整包装后检查大小（isolated 进程 OOM 风险，非 host 堆）；P06（P2）GC 接线后旧内容 hash 复用可绕过一小时宽限被并发删除（今天无生产触发者，接入后无互斥可升 P0 数据丢失）。

### 历史发现复核（关键裁定）
仍成立：C01(P0)、C04(P1)、C06(P1)、C09(P2)、C10(P2)、C11(P2)、P01、P04（计时事实）。**不成立/降级**：Goal 时长预算被当用户取消（`:259` 已设 GOAL_BUDGET_LIMIT，专门终局）；"未 ACK 终端记录永久锁死容量"（`DeveloperManualTerminal.kt:109-119` 存在 query+ACK 释放入口）；MCP"无任何超时"（绝对结论证据不足，降 PLAUSIBLE）；QuickJS oneway 必崩进程（异常抛出不等于 Binder 终止进程）；BudgetContinuation"必然 O(T²)"（先要求 Turn 为最新，单次 O(T+C)，非嵌套）；Workspace refresh→insert 竞态**已修复**（`ToolArtifactRegistrationSink.kt:29-40` withTransaction 包住）；旧引擎单槽部分提交**已修复**。

### 风险组合（最危险）
① 并行读 + 进程死亡 + 全局恢复单一 try（C01，第一优先）；② 工具超时/未知输出 + 多调用批次（C04，重试时用户不知道哪些效果已发生）；③ 重新生成 + 后续未答消息/竞争发送/切会话（C02/C03/P02）；④ Responses 持续参数流 + 并行工具 Turn（C05 OOM 杀进程可衔接 C01）；⑤ 未提交 Root 大文件传输 + 并行聊天（C07，合入前解决）；⑥ GC 接线 + 老 hash 复用（C08 接线必须与 P06 协议同批）。
**建议修复验证顺序**：C01 → C02/C03/C04 → C05/C06/C07 → P01 时序注入 → GC 协议与接线 → 其余 P2。

---

## 维度五：优化与删减

**总评**：仓库卫生延续优秀——生产源码 **0 个 TODO/FIXME**（AGENTS.md 合规）、版本目录 44 个 library alias **无孤儿**（jgit 经 `findLibrary` 程序化引用）、36 个 lockfile **0 多版本冲突**、res 全部被引用、`build/`/`__pycache__` 不入库、scripts/debug 697 文件归档纪律执行良好（owner 明确不整删）。核心行为接口（ModelProvider/Clock/WireClient/ApprovalBroker 等）全部有测试 fake 消费，**无投机抽象**；真正的税项是"按设计文档写完、从未接线"的投机性**功能**与三份逐行重复的 SSE 解析。

### P1 问题（4 条）

**P1-1 core/agent 整族死代码（最大可维护性税项，历史"TurnReducer 未用"复核成立并扩大为整族）**
- `TurnReducer/TurnState/TurnEvent/TurnEffect/TokenUsage`（1,230 行）+ `GoalSchedule/GoalScheduleCodec/GoalForeground`（292 行，整个 once/daily/weekly 调度功能"实现了没接线"）+ `DeveloperRuntime`（247 行，KDoc 声称"app 驱动它"——实际不存在）：生产源集 **0 个 import**（唯一 import 在 androidTest；core 内部引用全为 KDoc）。
- 影响：双状态机心智负担（M1 串行参考 vs M2 并行生产）、KDoc 误读、~1,618 行绑定测试持续维护；且 C01 的串行假设正是这族代码留在恢复路径的后果。
- 建议：owner 决策删除或 ADR 归档为 M1 参考语义；无论哪种都同步清理 6 处 KDoc + 删除绑定测试。省 **1,769 生产行 + ~1,618 测试行**。

**P1-2 SSE 读取器三份逐行重复（协议关键路径，bug 要修 3 次）**
- `AnthropicSseReader`(262)/`ChatSseReader`(242)/`ResponsesSse`(258)：类体 diff 仅 21/39 行（差异全是类名/事件类型名/注释）；UTF-8 增量解码、行断状态机、事件缓冲、fail 守卫、限额常量、25 行 WHATWG 契约 KDoc 约 230 行 ×3 字节级相同。
- 建议：抽 `:provider:api` 的 `internal SseEventStream`（~250 行）+ 三个薄映射（各 ~40 行）；762 行 → ~370 行，**省 ~400 行**，协议级 bug（UTF-8 边界/CRLF/超长行）单点修复；6 个现有测试护航。

**P1-3 ChatService 上帝类**（结构根因见 A02）：4,047 行/183 方法/14 参数构造器/30 个测试文件。类型级六拆方案：`ChatScreenState`(250，耦合最低先拆) → `ChatSessionInputQueue`(450) → `ChatSubmissionPipeline`(550) → `ChatTurnLifecycle`(550，turnGate 锁随走) → `ChatSessionLifecycle`(300) → `ChatTaskControl`(300)，留 ~800 行门面。风险高（全仓测试最多的类），每步单独提交 + 测试簇回归 + 设备冒烟。

**P1-4 core/model 死代码 + ToolSource 投机接缝**
- 8 个生产 0 引用 ID 值类（`Identifiers.kt:27-266` 的 MessageId/ToolResultId/ArtifactId/AuditEventId/RuntimeInstallId/OperationId/ScopeId/WorkspaceId，~120 行）；`ExecutionState`(53，KDoc 自述"until HXA-035+ wires an executor"——从未接线) + `ToolExecutionEnvelope`(160)；`tools:framework` 的 `ToolSource/BuiltInToolSource/ToolSourceKind`（生产唯一构造 `ToolRegistry()` 传空列表，工具全走 `register`）。
- 建议：删除（省 ~650 生产行）；注意 Identifiers.kt 中 TurnId/ToolCallId/SessionId 等在用，勿整文件删。

### P2 问题（17 条，摘要）
3 个死 Adapter 类（90 行，历史发现仍未删，零风险删除）；死 typealias `McpOAuthPkceChallenge`（`McpOAuthPkce.kt:57`）；死常量 `CodexPayloadJob.MAX_PAYLOAD_BYTES` 64MB（`CodexPayloadJob.kt:111`，历史发现仍未删，全仓 0 引用）；**kotlin 2.3.21 vs KSP 2.3.11 版本失配仍未修**（`libs.versions.toml:3,10`，历史发现）；流式解码器骨架四重复（ProtocolViolation/failProtocol/MAX_TOOL_CALLS ×3，省 150–200 行）；**e.message 直传用户可见状态 14 处**（FileManagerService.kt:470-575 ×5、FileManagerTrash.kt:67,89、FilesScreenEffects.kt:37,79、FilesRecoveryPanel.kt:84、GitWorkspaceReader.kt:45、BrowserDownloadQueue.kt:120——抽 `ErrorMessages.kt` 安全映射）；根 build.gradle.kts 611 行"上帝化"（15 特判+10 三元组+2 group hack）；okhttp-jvm substitution ×3；jgit transform 3 个硬编码坐标绕过版本目录；9 个未用字符串 key ×3 语言（其中 `connector_update_*` 5 键暗示升级 UI 未接线）；desktopMode 全局死偏好（读不写）；`.workbuddy-ai/`（24K agent 缓存）未忽略（`.claude` 同类问题已 exclude，同类问题再现）；`clean-packages.py` 两份相同；4 个超大测试文件（1,119–1,871 行）按场景拆；flavor 接缝风格不一致（同名 object ×6 vs 接口 ×1，新接缝统一走"main 接口 + flavor 实现"模式）。完整清单见 [05-optimization.md](05-optimization.md)。

### 不可移除清单（防误删，均附证据）
`:testing` 孤儿模块（owner 2026-09-22 明确裁决保留）；spikes 3 模块（结论已归档 + check-all.sh 显式启用参与锁校验）；`scripts/debug/` 日期目录（owner 声明不整删）；`WebViewTabHost.kt:136` @Deprecated（覆写 Android 已废弃 API 的故意保留）；jgit alias（程序化引用非死）。

### 精简路线图（按收益/成本）
1. 【1 天零风险】一次性死代码清理（3 Adapter + typealias + 死常量 + 8 ID + ExecutionState/envelope + ToolSource 接缝 + 9 字符串 + desktopMode ≈ **550 行**，每步独立 commit）
2. 【1–2 天低风险】构建卫生：KSP 对齐 + substitution 抽共享脚本 + jgit 坐标入 catalog + `.workbuddy-ai/` 忽略
3. 【2–3 天中风险，单点收益最高】SSE 共享解析器（省 ~550–600 行）
4. 【3–5 天中风险】core/agent 死族处置（省 ~3,400 行）
5. 【1–2 周高风险高收益】ChatService 六型拆分
6. 【1 周】DefaultAppContainer 三抽 + ToolDispatcher 五抽
7. 【持续】ProotJobRunner/BrowserController/TurnCoordinator/ConversationSection 拆分（TurnCoordinator 前必须先修 C04）

---

## 跨维度结论与优先行动清单

**跨维度观察**
1. **同一根因出现在三个维度**：恢复串行假设（维度二 A01 = 维度四 C01 = 维度五 R1/P1-1）。并行调度落地后恢复契约从未更新，且 M1 串行参考代码继续留在生产路径——一处修复、三处收益。
2. **最新提交 8aa8ff97（turn regeneration）净增债务**：引入 C02/C03/P02 三个 P1 缺陷 + C08 无触发入口 + 文档零足迹（维度一 D1-11）。功能方向正确，但原子性、作用域守卫与文档同步都缺。
3. **未提交 Root 文件功能是当前工作树的最高风险改动**：seam 设计合格（维度五正面结论），但 C07（整文件堆物化）+ 主线程 root 检查 + 无确认删除放大（U1）必须在合并前解决。
4. **ChatService 是全库单点**：架构 P0（A02）、UI 性能 P1（U4）、bug P1（C02/C03 位置）都汇聚于此；其拆分是所有架构整改的前提。
5. **文档纪律是项目最强项**（30/30 关键声明一致、链接/索引/ADR 状态全对齐），但"分支收敛后状态快照漂移"已连续两次审查被发现——需要自动化门禁而非人工对账。
6. **历史审查质量可信**：本次独立复审确认其绝大多数结论（含 P0），同时纠正了 4 条过度断言（Goal 预算、终端容量、MCP 绝对超时、oneway 崩溃）——历史报告可作为可靠基线。

**优先行动 Top 10**

| 优先 | 行动 | 维度 | 成本 |
| --- | --- | --- | --- |
| 1 | 修复 C01：恢复契约支持并行 RUNNING 集合 + 逐 Turn 隔离 + 失败可见 | 二/四 P0 | 中 |
| 2 | 修复 C02/C03：重新生成的 supersede 与新 Turn 同事务、作用域绑定最新用户输入、失败回滚 | 四 P1 | 中 |
| 3 | 修复 C04：UNKNOWN 与 PENDING 分离，成功结果先回填，UNKNOWN 进复核终态 | 四 P1 | 中 |
| 4 | U1/U2：回收站清空/彻底删除 + 浏览器三清除加确认（数据丢失路径，改动极小） | 三 P1 | 低 |
| 5 | U6：`/plan` `/act` 两行修复 | 三 P1 | 极低 |
| 6 | U4 前三项：conversationEntries 单次分桶 + contentVersion 去 key + Regex 提常量（<100 行消流式最大两项成本） | 三 P1 | 低 |
| 7 | C07：Root 文件后端改真实流式（合并前门槛） | 四 P1 | 中 |
| 8 | C05/C06：Responses 参数累计上限；JS 工具 outputFile 或契约降为 64KiB | 四 P1 | 中 |
| 9 | 文档一次性对账（roadmap 5 处 + next-work-plan + extensions.md + task-experience §2 决策）+ check-all.sh 加一致性断言 | 一 P1 | 低 |
| 10 | 死代码 ~550 行一次性清理 + core/agent 死族处置决策（省 ~3,400 行）+ SSE 共享解析器 | 五 P1 | 低–中 |

---

**审查边界声明**：全部结论基于只读静态阅读与 grep/git 取证，未运行 Gradle/设备测试；C01 等 P0/P1 为源码链路推导的静态可达结论，设备级复现与 OOM/竞态实测需在后续授权任务中执行。本审查未修改仓库任何文件（仅新增本目录 6 个报告文件）。
