# 维度三:UI 与产品交互

**审查基线**:HEAD `3cf89027` + 未提交工作树(FilesHome/FilesScreenActions/GroupedNavigation/FileManagerService/strings.xml 各 +4 根目录字符串/测试若干/未跟踪 RootFileModule+RootFileOperations)。
**审查方式**:只读静态走查(grep + 全文阅读关键 Composable);i18n 用临时脚本 `/tmp/i18n_check.py` 对比三份 strings.xml;未跑 gradle。所有结论附 file:line;未经设备验证的标注"待核实"。

---

## 总评

- **界面一致性**:i18n 工程纪律优秀(三语言 1363 键全对齐、零占位符漂移、用户可见中文零硬编码),但设计系统依然缺位(351 处硬编码 dp、33 处裸 AlertDialog、无 Spacing/Typography 令牌),同一概念术语分裂("产物"vs"成果"、"会话"vs"对话")。
- **交互流程**:聊天主链状态机完整(回执/披露/排队/转向/修订/重新生成/分享/预算停止都有 UI 落点),但破坏性操作确认普遍缺失(回收站清空/单条彻底删除、浏览器常驻三清除按钮),文件管理到 Agent 的交接断链仍未修复。
- **体验**:流式期间重组热路径(O(K×N) 条目重算 + 每帧 effect 重启 + 逐 token 全量 StateFlow emit + Room 读取)整体未动,长会话流式掉帧风险真实存在;加载态/进度指示仍显著少于异步操作点(12 个进度指示 vs 100+ IO 点)。

---

## 屏幕与导航盘点

### 顶层屏幕清单(16 个)

| 类别 | 屏幕 | 位置 |
| --- | --- | --- |
| 门禁 | FirstLaunchNoticeScreen(首启隐私说明,可关闭) | `ui/FirstLaunchNoticeScreen.kt` |
| 抽屉宿主 | MainActivity(Shell)+ GroupedNavigation(未提交:两级可折叠抽屉) | `MainActivity.kt:190-228`、`ui/GroupedNavigation.kt` |
| 实页 ×13 | Sessions(会话列表+聊天)/Tasks/Artifacts/Git/Files/Browser/Terminal(仅 developer,实为外部 Activity)/Extensions/Capabilities/Readiness/Permissions(会话授权,嵌在设置内)/Settings/Audit | `ShellDestination.kt:14-66` |
| 推入路由 ×2 | COMMAND_DETAIL_ROUTE(turnId,callId)、TASKS_TURN_ROUTE(turnId) | `MainActivity.kt:292-305` |

> 注:ShellDestination.kt:8 的 KDoc 写"twelve",实际枚举 13 项(含 Terminal)——文档性小漂移(P2,见问题清单 P2-17)。

### 导航模型

- **抽屉切换**:`navController.popUpTo(initialDestination) { inclusive = true }` 后 navigate(`MainActivity.kt:210-218`),栈不膨胀,返回行为=回会话列表。
- **Terminal 是"抽屉项但不是页"**:点击后 `ManualTerminalModule.open()` 拉外部 Activity(DeveloperManualTerminal 走 PRoot 进程;consumer 桩 `open()=Unit`,`app/src/developer/.../ManualTerminalModule.kt:9-16` / `app/src/consumer/.../ManualTerminalModule.kt:9-15`),随后 `popBackStack()`——**抽屉关闭、当前路由不变、外部 Activity 在前台**;返回 App 后回到点击前所在路由。抽屉选中态不会错误高亮(路由不变),但用户心智上"终端"像一个页面、实际是进程外活动,且 consumer 构建 Terminal 入口直接隐藏(`FakeShellRepository(terminalAvailable = manualTerminal != null)`,`DefaultAppContainer.kt:123-125`)。
- **跨页状态**:全部靠 `chatService.openSession(id)` 全局单例再 navigate(`MainActivity.kt:261,282,333,352`;goal 提醒 `:148`),不传导航参数(历史 UI-9 仍成立,P2)。
- **推入路由返回**:系统返回键经 `navController.backStackEntry` 回到打开前页面(`MainActivity.kt:243-246`),CommandResultDetailScreen 另有显式"返回会话"(`onOpenSession` → `MainActivity.kt:296-300`)。无导航死路;唯一"软死路"是 Terminal(见上)。

### 文本导航图

```
MainActivity (HelixTheme, ModalNavigationDrawer)
├─ [门禁] FirstLaunchNoticeScreen —— 首启未确认时全屏;确认后不再出现(FirstLaunchStore 持久)
└─ Scaffold(topBar = 非 Sessions 路由用 CompactPageHeader;Sessions 自带 AdaptiveConversationHeader)
   ├─ 抽屉 GroupedNavigation(未提交改动:两级)
   │   ├─ 会话(1 项,直显): 会话
   │   ├─ 工作(折叠组,6 项): 任务 · 成果 · Git · 文件 · 浏览器 · 终端*
   │   ├─ 扩展(1 项,直显): 扩展
   │   └─ 设置(折叠组,5 项): 能力 · 准备 · 权限 · 设置 · 审计
   │        * 终端:点击→外部 ManualTerminalActivity(developer);consumer 无此项
   ├─ NavHost(startDestination="sessions")
   │   ├─ sessions → ChatScreen(会话列表 ⇄ 打开的会话,内部状态切换,非路由)
   │   │    └─(推入) command-detail/{turnId}/{callId} → CommandResultDetailScreen
   │   ├─ tasks → TasksScreen;└─(推入) tasks-turn/{turnId} → TasksScreen(initialTurnId)
   │   ├─ artifacts/git/files/browser/extensions/capabilities/readiness/permissions/settings/audit → 对应页
   │   └─ terminal → (注册了路由,但入口走抽屉分支;直接 navigate 会开 Activity 后 popBackStack)
   └─ 进程死亡/首次启动 → 回 sessions(无 deeplink、无 last-open 持久;会话草稿按会话持久但需手动重开会话)
   旋转(config change)→ 活动重建:NavHost 栈幸存(Compose 保存),页面局部 remember 状态全丢(见 P2-11)
```

### 状态丢失点

| 场景 | 丢什么 | 证据 |
| --- | --- | --- |
| 旋转 | 抽屉展开态、文件页选择/搜索/排序视图/回收站展开、扩展页 tab、市场筛选/搜索、连接器草稿(jsonDraft/clientId/scope)、会话搜索激活态、GoalDialog 打开态;`rememberSaveable` 仅 12 处 | `AndroidManifest.xml:44-46` 无 configChanges;`grep -rc rememberSaveable app/ui` = 12;`GroupedNavigation.kt:38` |
| 进程死亡 | 当前打开的会话、所在路由(回 sessions)、浏览器全部标签(内存态)、终端页 | `ChatService.openSession` AtomicReference 无持久;`BrowserController` 标签仅内存(`hostView`/`tabs` 无磁盘持久) |

---

## 一致性审计

### a. 设计系统

**结论:无设计系统,问题较历史审查(304 dp)反而扩大(351 dp,含未提交的 GroupedNavigation 新代码)。**

| 指标 | 实测(本次) | 历史审查 | 变化 |
| --- | --- | --- | --- |
| 硬编码 `N.dp`(app/ui) | **351**(8dp×104、4dp×58、16dp×39、12dp×39) | 304 | ↑ |
| 硬编码 `fontSize = N.sp`(app+feature) | **71** | 71 | 持平 |
| `RoundedCornerShape(`(app+feature) | 37 | 19(仅 app/ui) | 口径扩大 |
| 裸 `AlertDialog(`(app/ui) | **33** | 40(全工程) | 略降 |
| `CircularProgressIndicator`+`LinearProgressIndicator`(app+feature) | **12 处**(7 文件) | 6 处 | 略增(文件导入/导出、会话导出、文件批量、上下文指示、浏览器顶栏) |
| 自定义 Card 各自实现 | 7+(ApprovalCard、ToolTimelineItem 内嵌 Surface、TaskLedgerCard、FileLocationCard、MarketplaceItemCard、GitHubDeviceCodeCard、Browser TabCard) | 7 | 持平 |
| Snackbar / Toast | **0 / 11**(app/main)+浏览器 4 | 0 / 13 | 略降 |
| 硬编码 `Color(0x…)` | **0**(全走 `MaterialTheme.colorScheme`) | 0 | 持平 ✅ |

**同类 UI 复制粘贴的具体重复(带行号)**:

1. **列表行**:文件 `FileRow`(`FilesScreenComponents.kt:92-135`)/`FileGridItem`(`:150-178`)与任务 `TaskRow`(`TasksScreen.kt` 内)、市场 `MarketplaceItemCard`(`MarketplaceSection.kt:256+`)各自实现 padding/圆角/选中态,无共享 `ListRow`。
2. **分区头/空态**:`EmptyConversationHint`(`ui/EmptyConversationHint.kt:23`)、`EmptyDestination`(每个 destination 的空态经 `ShellDestination.emptyStateRes`,`MainActivity.kt:307-318`)、`tasks_empty`(`TasksScreen.kt:219`)、`browser_downloads_empty`(`BrowserScreen.kt:291`)、`audit_count_empty`(`AuditScreen.kt:125`)——五种各自为政的空态,文案与版式不统一。
3. **错误条**:全部是 `Text(x, color = error)` 内联:连接器 `ConnectorSection.kt:116-118,434-436,570-572`、市场 `MarketplaceSection.kt:124(failMsg)`、会话输入队列 `SessionInputQueuePanel.kt:151-156`、文件 `FilesScreenLayout.kt:148-153`、Git `GitStatusScreen.kt:94-95`——无统一 ErrorBanner 组件、无重试按钮约定(仅会话队列有重试)。
4. **加载条**:文件批量(`FilesScreenLayout.kt:58-73`)、导入(`FilesImportDialog.kt:88`)、导出(`FilesExportDialog.kt:159`、`SessionExportDialog.kt:114`)各自 `LinearProgressIndicator` + 文案拼装,无共享 ProgressCard。
5. **图标按钮 + "▾/✓" 字符菜单**:模式 `ComposerMenus.kt:79,84`、模型 `ComposerModelMenu.kt:41,61`、文件排序 `FilesLocationBar.kt:44,136`、投递选择 `SessionInputDeliverySelector.kt:35-45`、抽屉徽章 `GroupedNavigation.kt:60`(未提交)——同一"下拉菜单 pill"模式五处手写。

### b. i18n

**脚本对比结果(`/tmp/i18n_check.py`,三份 strings.xml)**:

- 键数:base(zh)= **1363** / zh-rCN = **1363** / en = **1363**;缺失键 **0**、多余键 **0**、占位符漂移 **0**、标签类型漂移 0。✅(未提交的 +4 键 `files_root_fs/files_request_root/files_root_granted/files_root_required` 三份同步添加,含占位符一致性)
- 硬编码用户可见字符串:全量扫描 app(含 developer/consumer 源集)+ feature 共 **14 处代码内 CJK 字面量,全部非 UI 路径**——`AttachmentContext.kt:53-127`(注入模型上下文的附件说明文本,模型可见而非用户可见)、`feature/browser/snapshot/BrowserActionScript.kt:87`/`BrowserSnapshotScript.kt:63`(可访问性标签关键字"密码",脚本常量)。**Compose Text() 内硬编码中文/英文 0 处**(此前 49 处命中经逐处核验均为 KDoc/注释)。✅
- **例外(新增发现)**:
  - 工作区来源显示名硬编码英文 `"Workspace"`:`FileManagerService.kt:186`(其余来源均走 `loc(R.string…)`),中文界面文件页会显示英文。
  - 英文枚举名/内部 phase 直出(见 c 与问题清单 P1-7)。

### c. 文案风格(术语一致性)

| 概念 | 不一致叫法 | 证据 |
| --- | --- | --- |
| Artifact | **"产物"**(聊天/任务/命令详情 8 处:`chat_artifacts_title=本会话产物`、`tasks_artifacts=产物`、`command_detail_files=产物文件`、`recovery_artifacts=可用产物`)vs **"成果"**(Artifact Center 页:`nav_artifacts=成果`、`empty_artifacts=还没有已交付的成果…`) | `values/strings.xml`(nav_artifacts 行)vs 上述 8 键;en 侧统一 Artifacts,分裂只存在于中文 |
| 会话/对话 | 主词"会话"(nav_sessions=会话);**"对话"** 7 处:`tasks_kind_turn=对话任务`、`empty_sessions=…模型对话…`、`readiness_goal_chat=对话`、`background_task_result_empty=…对话文本…`、`notice_section_3=…写入对话…` 等 | `values/strings.xml` |
| 权限/授权 | 导航"权限"(nav_permissions)= 会话授权预设页,能力页又叫"能力"(nav_capabilities),系统权限入口在能力页内——同一"权限"概念两个入口两叫法 | `ShellDestination.kt` Capabilities/Permissions |
| 英文枚举名直出 | 模式菜单 Chat/Plan/Act/Goal(`ComposerMenus.kt:34,36` `mode.name.lowercase()`);终端页 `[phase]`/`stopReason` 原始值(`ManualTerminalScreen.kt:130,200`;`DeveloperManualTerminal.kt:235-237 record.phase.name`) | 中文界面显示英文技术词 |

---

## 交互流走查

### a. 聊天主链(状态矩阵)

| 状态 | UI 表现 | 证据 | 缺口 |
| --- | --- | --- | --- |
| 输入 | OutlinedTextField maxLines=5、语音、附件(选择器只暂存不发送)、`/` 命令补全、模式/模型胶囊、推理菜单(底部 sheet) | `ConversationComposer.kt:131-167`;`composer/ComposerCommandParser.kt` | 模式菜单英文枚举名;`/plan`、`/act` 斜杠命令失效(都切到 CHAT) |
| 发送(回执) | `send()`→`pendingComposerSubmission`→`await()`→`handleReceipt`;失败经 `ChatSubmissionErrorMapper.mapReason` 映射为安全文案 `showBlockedReason`;10 类拒绝原因(草稿变更/附件准备失败/确认过期/会话变更…)各有独立字符串 | `ChatScreen.kt:469-522`;`ChatService.kt:967` 清 blockedReason;`strings.xml chat_submission_rejected_*` 10 键 | 无(回执链路完整) |
| 出网披露 | DisclosureDialog:provider 目标+附件逐条(名称/类型/大小),确认/取消;确认绑定 provider+origin,漂移则失效重发 | `ui/DisclosureDialog.kt:31+`;`ChatService.kt:1922,1975` | 无 |
| 流式输出 | `TurnProgressLabel`(10 态,含 liveRegion 无障碍)+ 流式消息行(streamingText,Markdown+Thinking 手风琴)+自动跟随滚动+回到底部按钮 | `ui/TurnProgressLabel.kt:18-69`;`ConversationSection.kt:360-364`;`ConversationTimeline.kt:79-95` | 流式重组热路径(见"性能与体验") |
| 工具执行内联预览 | 工具行=摘要+**结果前 3 行内联预览**(HXA-217)+展开(请求 3 行/结果 5 行折叠)+状态徽章+耗时+命令详情入口 | `ToolTimelineItem.kt:66-86,255-283` | 无(已实现) |
| 审批请求 | ApprovalCard 第 4 类消息:**仅"本次批准/拒绝"两键**(ADR-0005/HXA-209 B4,无"模型帮我批准/此后允许/记规则");折叠态=scope+参数摘要(3 行)+风险+出网;展开=全量字段(来源/目标/输入来源/限制/SHA256/verifier);终态卡无可操作 | `ApprovalCardScreen.kt:44-273`;`ApprovalCardUi.kt:19` | 无"记规则"(设计上移到会话权限);折叠态看不到"预期影响/verifier/代码"(P2);**PENDING 卡无剩余时间提示**,过期后仅变终态卡 |
| 停止 | 输入行下方停止键(仅 isSending 时出现)+Turn 级 stopTurn(排队面板/修订对话框/Goal 对话框均可 stopTurn);停止中按钮禁用 | `ConversationComposer.kt:169-177`;`SessionInputQueuePanel` stopTurn;`ChatService` stopTurn | 停止键出现导致输入区高度跳变(P2) |
| 重新生成(8aa8ff97 新增) | 末条 assistant 消息"重新生成"按钮(仅 !isSending);`regenerateLatestTurn`;INTERRUPTED turn 不再占门(工作树修 `turnGateHolds`,`ChatService.kt:1093-1100`) | `ConversationSection.kt:318-322`;`ConversationMessage.kt`(MessageActionRow onRegenerate);`ChatScreen.kt:375` | 无 |
| 会话内修订 | 用户消息"修订"按钮→MessageRevisionDialog(可改文本/附件/停止原 turn);`reviseMessage` 走 `revisedMessageId` 新 turn,旧消息标记 superseded | `ChatScreen.kt:355-370`;`ui/MessageRevisionDialog.kt:33+` | 无 |
| 排队/显式转向 | 运行中发送→入队;队列面板(pendingCount/appendedCount)+投递选择(排队/转向/立即,`SessionInputDeliverySelector`)+每行展开/编辑/撤回/恢复;turn 完成未消费的转向→NEEDS_ATTENTION 提示 | `SessionInputQueuePanel.kt:78-260+`;`SessionInputDeliverySelector.kt:20+` | 无(本链路是全 app 状态处理最完整的 UI) |
| 预算停止 | Turn 失败卡:`chat_turn_failed`+`errorLabel`+`budgetDetail`(预算超限明细)+重试(仅可重试时) | `ConversationSection.kt:376-399`;`strings.xml budget_*` 组 | 无 |
| 错误(网络/模型/工具) | 终态安全标签(terminalLabel)+重试;工具失败进工具行结果;订阅恢复卡(查看/恢复结果);**blockedReason 横条**(红色容器+关闭) | `ChatService.kt:3711-3724`(边界异常→FAILED 安全标签);`ConversationSection.kt:239-255`;`TurnRecoveryPanel.kt:27+` | 错误文案未统一"问题+已完成+下一动作"模板(P1-9) |
| 分享 | 每条消息复制/分享(ACTION_SEND);会话级 JSONL 导出(SessionExportDialog:进度/取消/中断恢复提示) | `ConversationMessage.kt`(ShareTextButton);`ui/SessionExportDialog.kt:32-118` | 无 |
| 空会话 | 引导语(未绑定模型/Goal/普通 3 态)+**起始 prompt 建议卡**(f37a5875 新增,点击填充 composer) | `EmptyConversationHint.kt:23-99` | 建议点击**覆盖**已有未发送草稿(与语音草稿"追加"不一致,P2-20) |

### b. 文件管理

| 状态 | UI 表现 | 证据 | 缺口 |
| --- | --- | --- | --- |
| 首页(FilesHome) | 位置卡(Workspace/SAF/Root*)+共享存储卡+快捷目录(input/work/output)+SAF 来源/导入/请求 Root 按钮(未提交) | `FilesHome.kt:31-145`(Root 按钮为工作树新增) | 位置卡显示名英文 "Workspace"(`FileManagerService.kt:186`) |
| 浏览 | 面包屑+排序(名称/时间/大小)+列表/网格切换+多选(长按);**列表模式用非懒加载 `Column(verticalScroll)` 全量渲染(≤500 项)**;网格才用 LazyVerticalGrid | `FilesScreenLayout.kt:166-180`(Column)vs `:182-212`(grid);`FileManagerService.kt:651 MAX_LIST_ENTRIES=500` | 大目录(数百项)列表模式首帧重组开销大(P2-21) |
| 选择/预览 | 点文件→FilesPreviewDialog(文本/图片,加载/失败态完整,`FilePreviewState.Loading/Ready/Failed`) | `FilesPreviewDialog.kt`;`FilesScreenEffects.kt:66-108` | 无 |
| 搜索 | 位置栏内搜索框,内存过滤(`visibleEntries`) | `FilesScreenState.kt:133`;`FilesScreenLayout.kt:134-141` | 仅当前目录名过滤,非全树搜索(功能缺口,非 bug) |
| 加载态 | **无**:列表加载前 `entries=emptyList()` → 显示"此目录为空"而非 spinner;错误仅红字无重试 | `FilesScreenEffects.kt:28-44`;`FilesScreenLayout.kt:148-165` | P1-8(状态误报)、P2-22(无重试) |
| 删除 | 工作区删除→回收站(软删);非工作区删除→确认对话框(永久删除 N 项);回收站:单条恢复/**单条彻底删除无确认**/**清空回收站无确认**(直接 `emptyTrashPanel`) | `FilesScreenActions.kt:297-320`;`FilesScreenComponents.kt:189-215`(TrashRow);`FilesScreenLayout.kt:124-129`(清空按钮) | **P1-1:两处破坏性操作零确认** |
| 回收站 | 面板(恢复/彻底删除);批量操作(复制/移动/删除)+进度条+取消+部分失败清单(≤10 行,"… 等 N 项"折叠) | `FilesRecoveryPanel.kt`;`FilesScreenLayout.kt:58-98`;`FilesScreenComponents.kt:219`(MAX_FAILURE_DETAIL_LINES) | 批量删除走软删(工作区)或确认(非工作区)✅ |
| 导入/导出 | 文件/文件夹、冲突策略(询问/重命名/覆盖)+进度+结果;导出到新文档/SAF 树 | `FilesImportDialog.kt`、`FilesExportDialog.kt`、`FilesMutationDialogs.kt:35-257` | 无 |
| scope 切换/失效 | 位置选择器列出全部来源;`replaceSources`:当前 scope 消失→**重置回首页**并清空所有选择/对话框(安全但粗暴);SAF 授权失效在打开面板时 re-verify 剔除 | `FilesScreenState.kt:75-92`;`FilesScopeEffects.kt:64-70` | 无显式"该位置已失效"提示,用户从目录深潜中被弹回首页,需自己理解原因(P2-23) |
| → Agent 交接 | **无**:全 app 无 `stageAttachment` 调用(仅 composer 附件选择器);无"在当前任务使用/附加到会话" | `grep stageAttachment app/src/main` 仅 `ChatScreen.kt:401` 与 ChatService 内部 | **P1-3(历史 UI-6,仍未修)** |
| 终端入口 | app scope 下"打开终端"按钮→外部 Activity(仅 developer) | `FilesScreenLayout.kt:49-55` | 文档要求"进入关联会话选择",实际直接拉起(P2-24) |

### c. 浏览器(feature/browser)

| 状态 | UI 表现 | 证据 | 缺口 |
| --- | --- | --- | --- |
| 标签管理 | 卡片式 Tab Switcher(新建/关闭/关闭全部/隐身);上限 8(DEFAULT_MAX_TABS);**达上限时"+"与隐身按钮仅置灰,无任何提示** | `BrowserTabController.kt:79,83,309-310`;`BrowserTabSwitcher.kt:148,155,168` | P2-25(容量拒绝无 UX);隐身按钮前缀 "🕶️" emoji 硬编码 |
| 导航 | Omnibox(智能解析 URL/搜索)+建议(书签/历史 top5)+SSL 锁+进度条+前进/后退/首页/停止+错误页(**带重试**) | `BrowserScreen.kt:171-220,480-508`;`BrowserTopBar.kt:245` | 无 |
| **旋转后页面丢失** | MainActivity.onDestroy `browser.detach()` 销毁 WebView → 重建后 `hostView==null` → 显示占位文案 `browser_enter_address`,**用户必须手动重输 URL**;标签列表(controller 内存)幸存但内容全部丢失 | `MainActivity.kt:152-160`;`BrowserScreen.kt:255-260`;`BrowserController.kt:327-330` | **P1-10** |
| 进程死亡 | 标签全部丢失(无磁盘持久),回首页空白标签 | `BrowserController` 无 tabs 持久化 | P2-26 |
| 下载队列 | 下载对话框(空态/选择位置/已保存/失败/拒绝)+**常驻内联面板**(有下载时顶起网页);**SAVING 状态无任何进度条/取消按钮**;下载前 SAF CreateDocument 选位置(不写未确认字节) | `BrowserScreen.kt:254-257,287-301,512-568`;`WebViewTabHost.kt`(setDownloadListener) | P2-27 |
| 权限 | **一律拒绝**:地理定位 callback(origin,false,false)、PermissionRequest.deny()(doc 09 §3.4 设计);**无任何用户可见的"已拒绝"提示** | `WebViewTabHost.kt:258-266` | 页面内可能静默失败,用户无解释(P2-28) |
| **常驻清除按钮(无确认)** | 浏览器底部**常驻** ClearRow:清 Cookie/清缓存/清历史,`fontSize=11.sp` 小字,点击立即执行;`clearHistory` 还会**关闭全部标签**重置为空白 | `BrowserScreen.kt:263-265`(ClearRow 无条件渲染)、`579-597`;`BrowserController.kt:465-488` | **P1-2:三处破坏性操作无确认,误触即丢浏览器登录态/历史/全部打开的标签** |
| 安全 UX | 无特权 JS 桥(仅固定版本化脚本 snapshot/action,模型不能提交脚本)✅;页面 title 直出 tab 卡(未消毒但仅本地显示) | `BrowserController.kt:344-377`(evaluateFixed) | 无 |

### d. 市场与连接器(Extensions 页)

| 状态 | UI 表现 | 证据 | 缺口 |
| --- | --- | --- | --- |
| 页结构 | 两 tab:市场 / 管理(Skill 创作+安装+连接器) | `ExtensionsScreen.kt:34-62` | tab 用 Button/OutlinedButton 手写切换,非 M3 TabRow |
| 市场浏览 | 搜索+类型筛选+卡片(认证标签);**列表在 composition 内直调 `service.items()`**(main 线程) | `MarketplaceSection.kt:103` | P2-29 |
| 安装 | 按钮文案变"安装中"+禁用(**无 spinner**);失败仅通用文案 `marketplace_install_failed` | `MarketplaceSection.kt:61,124,131-145,164,366-367` | 无进度、无失败细节 |
| 状态机 | **3 态**:NOT_INSTALLED(安装/配置并安装)/INSTALLED_INACTIVE(启用)/ACTIVE(禁用);文档要求 6 阶段(已添加/待配置/待连接验证/未启用/可使用/需处理) | `MarketplaceSection.kt:548-574` | **P1-5(历史 UI-7 部分仍在)** |
| 卡片文案错配 | 展开键折叠态显示 `command_detail_title`("命令详情")、展开态显示 `session_export_close`("关闭");卸载弹窗取消键复用 `session_export_close` | `MarketplaceSection.kt:349-354,540` | **P1-5:用户可见的键位语义错乱** |
| 启停/卸载 | **主线程直调** `service.disable/enableSkill/uninstall`(仅 install 走 IO);卸载有确认对话框 ✅ | `MarketplaceSection.kt:151-157,173-175`;`:522-524` | 主线程 IO(P2-29) |
| 安装后 | **无"在当前任务使用/新建任务"动作**(文档 task-experience.md §4 要求) | `MarketplaceSection.kt`(无对应 UI) | P1-5 |
| MCP 添加 | JSON 粘贴/文档导入+预览(变更摘要:skills/endpoints 数)+替换旧连接器 | `ConnectorSection.kt:108-190` | 无 |
| 连接测试 | 按钮禁用(!busy)+`connector_connection_failed` 红字;**挂起时无 spinner、无超时提示**(测试多久算挂起取决于 MCP 客户端,UI 无独立超时反馈) | `ConnectorSection.kt:333-352,434-436` | P1-8(进度缺失);P1-9(原始 message 直出,见下) |
| OAuth | Bearer(填 token)/OAuth 两种;GitHub 设备码流程:展示 userCode(大字)+打开验证页+复制+等待文案+取消;**无过期倒计时、无轮询进度指示**,过期后 `onError("OAUTH_DEVICE_EXPIRED")` **原始码直出用户可见红字** | `ConnectorSection.kt:469-497,570-572,657-701,380-383`;`GitHubDeviceCodeCard :657+` | **P1-9(历史 UI-5 仍在)**;P2-30(无倒计时) |
| 撤销 OAuth | 撤销/本地清除区分(Toast 反馈) | `ConnectorSection.kt:485-506` | 用 Toast 而非内联状态(一致性,P2) |

### e. 会话权限 / 能力 / 终端

- **会话权限(Permissions 页,嵌 SettingsScreen)**:四预设(FULL_ACCESS/WORKSPACE/READ_ONLY/CUSTOM)+新会话默认(仅预设,写服务拒绝 CUSTOM 默认)+app 级工具启停列表+CUSTOM 规则编辑器(从预设复制)+每变更独立审计。**只读经 `SessionPermissionEditService`,IO 全在 Dispatchers.IO**(9 处),失败显示通用 `common_operation_failed`。符合 HXA-209 设计;无逐工具 ASK(设计上已取消)✅。`SessionPermissionSection.kt:61-251`。
- **能力页**:每能力一行(状态 chip+what/why/scope+测试/修复/禁用);`buildCapabilityRows` 走 IO ✅;但 **rows 初始 null 期间整页空白(无加载指示)**,且 `copyFor` 对未知 key `error()` **会崩**(`CapabilitiesScreen.kt:74-82,118-125,373-382`)——未来加能力行忘加文案=用户崩溃(P2-31,潜在 P1)。
- **准备页(Readiness)**:按目标(聊天/任务/终端)投影 item 状态(READY/MISSING/NOT_AVAILABLE)+下一动作(ADD_MODEL/VERIFY_RUNTIME/REPAIR_RUNTIME)+验证结果 note;**加载中显示 "…"**(无 spinner);verify 期间按钮仅禁用,无进度(P2-32,对照 task-experience.md §3"正在准备→查看进度/取消"仅部分满足)。
- **终端页(developer,ManualTerminalActivity)**:会话 tab 栏+状态横幅(errorMessage/isObserver)+会话详情+控制(start/stop/settle)+libvterm 视口+软件键盘开关+许可证对话框。缺口:tab 标签与状态行**直出原始 phase/stopReason 英文枚举**(`ManualTerminalScreen.kt:130,154-160,200`);busy 期间按钮仅禁用无进度;许可证文本在 composition 内读 assets(IO on main,`:258-268`);硬编码展示 `/workspaces/app`(`:186-190`)。
- **审计页**:分页(最新一页)+空态+过滤器仅作用于当前页(有注释说明)✅。

---

## UX 缺口清单

### 缺加载态(按屏幕)
| 屏幕 | 表现 | 证据 |
| --- | --- | --- |
| Files 目录列表 | 加载中显示"此目录为空" | `FilesScreenEffects.kt:28-44`+`FilesScreenLayout.kt:154-165` |
| Capabilities | rows==null 期间空白 LazyColumn;加载异常则永久空白 | `CapabilitiesScreen.kt:74-82,118-125` |
| Command 详情页 | 首帧空白(view==null 且非 notFound) | `CommandResultDetailScreen.kt:60-88` |
| 连接器/市场操作 | 按钮禁用代替,无 spinner | `ConnectorSection.kt:80-107,333-352`;`MarketplaceSection.kt:131-145` |
| 浏览器下载 | SAVING 无进度无取消 | `BrowserScreen.kt:548-553` |
| Root 授权请求(未提交) | 无进度,结果落到状态行 | `FilesScreenActions.kt:42-52` |
| 准备页 | "…"文本;verify 仅按钮禁用 | `CapabilityReadinessScreen.kt:269-271,233-252` |
| 终端页 | busy 仅禁用 | `ManualTerminalScreen.kt` TerminalActionControls |

### 缺错误态
- Files 列表错误:红字无重试(`FilesScreenLayout.kt:148-153`)。
- Capabilities 加载失败:无(静默空白)。
- 连接器:有红字但**内容可能是原始 e.message/错误码**(P1-9)。
- 命令详情 notFound:有 ✅;Git 错误/非仓库:有 ✅。

### 缺空态
- 基本覆盖(每 destination 有 emptyStateRes;会话/任务/文件/市场/下载/审计均有);**例外**:浏览器 Tab Switcher 无标签时(理论上 ≥1 标签,不成立);审批卡 PENDING 无剩余时间(非空态,见上)。

### 破坏性操作缺确认(汇总)
| 操作 | 位置 | 确认 | 严重度 |
| --- | --- | --- | --- |
| 清空回收站 | `FilesScreenLayout.kt:124-129`→`FilesScreenActions.kt:312-320` | ❌ | P1 |
| 回收站单条彻底删除 | `FilesScreenComponents.kt:206-211`→`FilesScreenActions.kt:297-310` | ❌ | P1 |
| 浏览器清 Cookie/缓存/历史 | `BrowserScreen.kt:263-265,579-597`→`BrowserController.kt:465-488` | ❌(且 clearHistory 关闭全部标签) | P1 |
| 非工作区文件永久删除 | `FilesMutationDialogs.kt:35-55` | ✅ | — |
| 会话/消息删除 | PrivacyDeletionService 对话框(ChatScreen 各 delete 路径) | ✅(有 confirm) | — |
| 连接器撤销 OAuth | Toast 反馈,无二次确认(单操作可接受) | ⚠️ | P2 |
| 浏览器关闭全部标签 | Tab Switcher `browser_tab_close_all`(`BrowserTabSwitcher.kt:88`) | ❌ | P2 |

### 长操作无进度反馈
- 市场安装(`MarketplaceSection.kt:131-145` 仅文案)、MCP 连接测试/enable(`ConnectorSection.kt:333-352,402-414`)、会话 JSONL 导出 ✅ 有进度(正面)、文件批量 ✅ 有进度+取消(正面)、PRoot verify(`CapabilityReadinessScreen.kt:136-145` 仅 busy)。

### 无障碍(抽样 10 处)
1. `FilesScreenComponents.kt:115`(FileRow 图标)与 `:164`(FileGridItem 图标)——`Icon(painter, null)`,行内文本可部分补救,但图标语义缺失。
2. `FilesHome.kt:80` 与 `:128`(位置卡/快捷目录文件夹图标)——`Icon(painter, null)`。
3. `AdaptiveConversationHeader.kt:88` 新建会话图标 `Icon(painter, null)`(按钮内有文本,可接受,仍建议补)。
4. `GroupedNavigation.kt:60`(未提交)——`badge = { Text("▴"/"▾") }`,无 contentDescription/语义,TalkBack 读出"三角形"。
5. `ComposerMenus.kt:79,84`、`ComposerModelMenu.kt:41,61`、`FilesLocationBar.kt:44,136`、`SessionInputDeliverySelector.kt:35-45`——"▾/✓" 字符直接拼入标签文本,读屏拼接语义混乱。
6. `BrowserTabSwitcher.kt:155`——"🕶️ " emoji 前缀,读屏读"眼镜"。
7. `TurnProgressLabel` ✅ 有 `liveRegion` 正面样板;`ConversationTimeline` 回到底部按钮 ✅ 有文本。
8. 可点击卡片无 role/onClickLabel:`FileLocationCard`(`FilesHome.kt:110-130` Card(onClick))、市场卡片(`MarketplaceSection.kt:435-440`)——TalkBack 需读完全部子文本才能操作。
9. `BrowserBottomBar.kt:120` "☰" 文本按钮(有 BottomBarItem 文本可读,但无语义标签)。
10. `TaskLedgerCard.kt:96` "✓" 字符状态(与 5 同类)。

### 输入框/键盘/长文本
- composer maxLines=5(`ConversationComposer.kt:155`)——长输入需字段内滚动,可接受;`imePadding` ✅(`ChatScreen.kt:269`)。
- 长文本消息:SelectionContainer 可选中文本 ✅;代码块横向滚动+折叠展开 ✅(`MarkdownText.kt`);但 **Markdown 流式重解析每帧全量**(见性能节)。
- 对话搜索:流式期间每 token 主线程全量扫描(`ConversationSection.kt:104-108`+`ConversationSearch.kt:46-55`),长会话+搜索激活+流式=周期性主线程 O(总文本)(REVIEW-2026-09-24.md:210 复核仍成立)。

### 深色模式
- `HelixTheme` 显式接 `isSystemInDarkTheme()` ✅(`Theme.kt:30-33`)+values-night 平台侧 ✅;硬编码 `Color(0x…)` 0 处 ✅;全部颜色走 colorScheme token ✅。**遗留**:M3 默认紫调色板,无品牌色/动态取色(历史 UI-13,产品决策项,非 bug)。

---

## 性能与体验

**流式热路径复核(历史 §4.1 三项 + 新发现)**:

| # | 热点 | 现状 | 证据 | 用户可见影响 |
| --- | --- | --- | --- | --- |
| 1 | 每 token `publishTurn → _screen.update{copy(activeTurn)}` 全量 StateFlow emit | **仍在**(模式固有,单字段 copy) | `ChatService.kt:3722-3726,3966-3971` | 每个 token 触发 ChatScreen 全子树重组判定;长会话可见卡顿 |
| 2 | `conversationEntries(screen)` 每帧 O(K×N):K 个 key × 对 N 条 messages/tools/recoveries 各 filter 一遍,且在 LazyListScope 内每次重组直接调用 | **仍在** | `ConversationEntry.kt:11-31`;`ConversationSection.kt:294` | 100 轮/400 消息会话 ≈ 4-12 万次 filter+分配/token;中端机流式掉帧 |
| 3 | `contentVersion = listOf(messages, recoveries, timeline, activeTurn)` 新实例每帧 → `LaunchedEffect(contentVersion,…)` 每帧取消重启+`scrollToItem` | **仍在** | `ConversationSection.kt:272-278`;`ConversationTimeline.kt:79-88` | 自动滚动 effect 每帧重启(协程取消/重建),额外 GC 压力 |
| 4 | Markdown 解析:流式行 `remember(source)` 每 token 失配→`markdownBlocks` 全量重解析,内部 **4 处行内 `Regex(…)` 每次调用重新编译** | **仍在** | `MarkdownText.kt`(remember(source) markdownBlocks);`MarkdownBlock.kt:40,44,53,77` | 流式消息 O(长度)重解析+每行正则编译,长回复尾段明显变慢 |
| 5 | **新发现**:每 token `publishTurn` 内 `storage.turns.resolve(turn.id)` Room 单行查询 | **新增于 8aa8ff97 前后** | `ChatService.kt:3975` | IO 池上每 token 一次 DB 往返;与 1-4 叠加 |
| 6 | 搜索激活时流式每 token 主线程全文扫描 | **仍在** | `ConversationSection.kt:104-108`;`ConversationSearch.kt:46-55` | 搜索态流式=周期性主线程长任务,ANR 边缘风险 |
| 7 | LazyColumn 内直接 DB/IO | **基本合规**:产物行异步(IO)✅、ArtifactsScreen 行级 availability 异步 ✅;**例外**:`MarketplaceSection.kt:103` composition 内 `service.items()`、`ConnectorSection.kt:249` `remember{}` 初始化器内 `service.hasOAuthToken(endpoint)`(main 线程)、`FilesScreenState` 构造(=composition 首次)`fileManager.sources()`(main 线程,含 SAF 校验/未提交后含 libsu root 检查) | 见左 | 首帧卡顿;root 检查(libsu `Shell.isAppGrantedRoot()`)在主线程可能阻塞数百 ms~秒级(待核实:取决于设备 su 响应) |

**评估**:用户可见影响集中在"长会话 + 流式输出 + 中低端机":掉帧、消息出现节奏抖动、搜索态卡顿;未见 ANR 必现路径,但 #2+#6 叠加在弱机上有现实 ANR 风险。修复成本低收益高:conversationEntries 改为单次遍历分桶(O(N)),contentVersion 换成 (count, lastUpdatedEpoch) 或去掉 effect key、Regex 提为伴生常量、publishTurn 去掉每 token Room 读(缓存 sessionId)。

---

## 文档承诺 vs 实现

对照 `docs/product/task-experience.md` 与 `docs/architecture/session-export.md`:

| 文档承诺 | 实现状态 | 证据 |
| --- | --- | --- |
| §2 工作区详情页"文件/任务/终端/变更"四入口 | **未实现**:无统一工作区详情页,四者是四个抽屉页+Files 内终端按钮 | 全仓无 workspace detail 路由;`FilesScreenLayout.kt:49-55` 仅单按钮 |
| §2 工作区文件→"在终端中打开所在目录→进入关联会话选择;没有会话时由用户明确新建" | **部分**:Files 直接拉起终端 Activity(无会话选择);终端页内部自行建/连会话 | `FilesScreenLayout.kt:49-55`;`ManualTerminalActivity.kt:26-31` |
| §2 产物→查看来源任务/所在位置 | ✅ Artifacts 行 onOpenTask + 就地预览 | `ArtifactsScreen.kt`(onOpenTask);`ConversationArtifacts.kt`(HXA-219) |
| §2 任务/聊天→打开所属工作区 | ✅ 会话目录对话框/文件页回跳 | `ChatScreen.kt` directory 入口 |
| §2 返回不丢位置/草稿 | **部分**:草稿持久 ✅(ConversationDraftBuffer);列表位置/搜索态旋转丢失 | `ChatScreen.kt:210-217`;P2-11 |
| §2 文件→Agent 交接("工作区作为操作起点") | **未实现**(无 stageAttachment 入口) | 见 P1-3 |
| §3 开发环境准备页(尚未准备→准备环境→下载/校验步骤;正在准备→查看进度/取消;需要修复→修复) | **部分**:验证/修复入口+结果 note ✅;"准备中"进度与"取消" ❌(仅 busy 禁用) | `CapabilityReadinessScreen.kt:228-252,136-145` |
| §4 扩展六状态(已添加/待配置/待连接验证/未启用/可使用/需处理) | **未实现**:市场 3 态、连接器各自布尔 | `MarketplaceSection.kt:548-574` |
| §4 可使用后"在当前任务使用/新建任务" | **未实现** | `MarketplaceSection.kt`(无该动作) |
| §4 MCP 连接测试不自动启用全部工具 | ✅(enable 需用户选择 tools,`ConnectorSection.kt:402-414` selection) | ✅ |
| §5 错误文案"问题+已完成结果+下一动作" | **未统一**:多数为单句红字/原始 message | `ConnectorSection.kt:570-572` 等 |
| §5 屏幕阅读标签/大字体/软键盘遮挡纳入验收 | **部分**:liveRegion 样板有;contentDescription 缺口 10 处(见上) | 见 UX 缺口 |
| session-export.md(JSONL v1 导出) | ✅ 完整:导出对话框(进度/取消/中断恢复提示)+格式校验器脚本 | `SessionExportDialog.kt:32-118`;`scripts/validate-session-export.py` |

---

## 未提交改动评估(工作树)

**改动内容**(+245 行):在文件管理器中新增 **Root 文件系统来源**(developer 专属,libsu 经 `tools/root/RootFileAccessor`):

1. `FileManagerService.kt`:`ROOT_SCOPE_ID="root"` 常量;`sources()` 追加 Root 来源(仅 `rootOperations?.isRootGranted()`,`:214-222`);新增 `isRootSupported/isRootGranted/requestRoot` 委托;`FileSourceKind.ROOT`;preview/share 路径对 root scope 走 `rootOperations.realFileFor` 暂存到 `cache/share`(共享前复制,不暴露原始 root 路径——设计正确)。
2. `AppFileServices.kt`/`DefaultAppContainer.kt`:backend 路由注入 `RootFileModule`(consumer 桩 `NoOpRootFileOperations` 全 false/抛 FileNotFoundException,developer 真实现);`rootOperations?.manualBackend() ?: error("Root manual backend not available")`。
3. `FilesHome.kt`(+8):`isRootSupported && !isRootGranted` 时显示"请求 Root 权限"按钮。
4. `FilesScreenActions.kt`(+12):`requestRoot()` → IO 上 `fileManager.requestRoot()`,结果写 `state.status`。
5. `GroupedNavigation.kt`(+73/-31):平铺分组 → **两级可折叠抽屉**(单条目组直显;组头 NavigationDrawerItem 可展开;`navigationGroupTag` 测试钩子;设备测试同步更新)。
6. 字符串 +4×3:root 相关四键(三份同步 ✅)。
7. `ChatService.kt`(+10):两个修复——`sessionDraft` 取用时 `takeIf { it.session.id == sessionId }`(防止把 A 会话草稿应用到 B 会话,UX 正确性修复 ✅);`turnGateHolds` 把 `INTERRUPTED` 视为不占门(使"重新生成"在中断后 turn 上可用,配合 8aa8ff97 ✅)。
8. `SessionDao.kt`:selectModel 排除 `INTERRUPTED`(与 7 一致的模型选择语义)。

**UX 自洽性评估**:
- ✅ 入口隐藏正确:consumer 无按钮(桩 isSupported=false);developer 未授权时显示请求按钮、已授权时 Root 出现在来源列表。
- ⚠️ **无进度反馈**:`requestRoot()` 触发系统 su 对话框期间 UI 无任何状态(按钮不变、无 spinner);su 拒绝后仅一行 `files_root_required` 状态文字(易被忽略)。
- ⚠️ **主线程 root 检查**:`FilesScreenState` 构造(composition 首次)调 `fileManager.sources()` → `isRootGranted()` → libsu;`requestRoot()` 完成后 `replaceSources(fileManager.sources())` 也在主线程(`FilesScreenActions.kt:45`,IO 只包了 requestRoot 本身)。弱设备上 su 探测可能阻塞 UI(待核实)。
- ⚠️ **Root 来源可写性显示**:`supportsMutation = manual?.canWrite(ROOT_SCOPE_ID)`——root 的 manual backend 是否支持写(删/改名)由实现决定;若支持,则回收站/彻底删除那套**无确认的删除 UX(P1-1)将直接作用于系统文件**,风险放大。
- ⚠️ **GroupedNavigation 新代码**:
  - `expandedGroups by remember(currentRoute)`——路由一变,用户手动展开的组被重置为"当前路由所在组",展开态不可控(体验不一致,P2-33)。
  - 组头 `selected = !isExpanded && hasSelectedChild` + `badge Text("▴"/"▾")`:选择态逻辑晦涩(展开时不选、折叠时才选),字符徽章无无障碍语义(P2-34,与 P2-4 合并)。
  - 新增 `2.dp/28.dp` 硬编码间距,继续背离"无设计系统"基线(与 P1-4 设计系统项合并)。
- ✅ 测试同步:GroupedNavigationDeviceTest 为折叠组加了"先展开再断言"逻辑,说明作者意识到选中态变化;MainActivityTest/AppUiTestSupport 同步更新。

**结论**:Root 文件来源本身方向合理且渠道隔离正确,但 (a) 无进度/无主线程防护、(b) 删除确认缺口被放大、(c) 抽屉两级化引入展开态失控——建议合并前补齐,见改进建议。

---

## 问题清单(按严重度排序)

> 严重度:P0=崩溃/数据丢失/核心流不可用;P1=核心用户流明显受阻或一致性破坏;P2=体验瑕疵。

### P1(10 条)

**P1-1 回收站"清空"与"单条彻底删除"无确认(数据丢失路径)**
- 问题:回收站面板内"清空回收站"按钮直接 `emptyTrashPanel()`(全量永久删除当前 scope 回收站);"彻底删除"按钮直接 `purgeTrashEntry(id)`(单条永久删除)。两者均无 AlertDialog/二次确认;未提交改动使 Root scope 也进入该面板,风险面扩大到系统文件。
- 证据:`app/src/main/kotlin/com/helix/app/ui/FilesScreenLayout.kt:124-129`;`ui/FilesScreenComponents.kt:189-215`(TrashRow);`ui/FilesScreenActions.kt:297-320`;`files/FileManagerService.kt:595-600`(purge/emptyTrash)
- 影响:developer/Standard 全部用户;文件管理核心流;误触=不可逆数据丢失。
- 严重度+理由:P1(破坏性批量操作零确认,符合"一致性破坏/明显受阻"上限;因上下文在回收站内且单文件删除另有确认,未定 P0)。
- 建议:两处加确认对话框(复用 `FilesMutationDialogs` 的 AlertDialog 模式),文案含数量/不可逆提示;Root scope 下再显式提示"系统文件,删除不可恢复"。

**P1-2 浏览器常驻三个清除按钮无确认,clearHistory 连带关闭全部标签**
- 问题:ClearRow(清 Cookie/清缓存/清历史)以 11sp 小字**常驻**在浏览器主界面(每个页面底部),点击立即执行;`clearHistory()` 清空 ownerBinding+快照并循环 closeTab 重置为单一空白标签。无确认、无撤销。
- 证据:`feature/browser/src/main/kotlin/com/helix/feature/browser/ui/BrowserScreen.kt:263-265,579-597`;`feature/browser/src/main/kotlin/com/helix/feature/browser/BrowserController.kt:465-488`
- 影响:全部使用浏览器的用户;误触=丢失浏览器内登录态(清 Cookie)、浏览历史、全部打开的标签。
- 严重度+理由:P1(低门槛高频误触 + 多类数据丢失;浏览器是独立核心流)。
- 建议:三个操作加确认(至少"清历史"需确认并提示将关闭标签);或收进 Menu sheet 的二级"清除浏览数据",与浏览器惯例一致。

**P1-3 文件管理器→Agent 工作区断链(历史 UI-6,未修)**
- 问题:文件页无任何"在当前任务使用/附加到会话"动作;`stageAttachment` 全 app 仅 composer 附件选择器调用。文档 `task-experience.md §2`"工作区作为操作起点"+"文件→在任务中使用"不可达;统一工作区详情页(文件/任务/终端/变更四入口)不存在。
- 证据:`grep stageAttachment app/src/main` → 仅 `ui/ChatScreen.kt:401`;`app/src/main/kotlin/com/helix/app/files/` 0 命中;`docs/product/task-experience.md`(§2 表格)
- 影响:所有"从文件出发"的用户;核心产品承诺(工作区起点)不可用。
- 严重度+理由:P1(文档级产品路径缺失,文件流与 Agent 流互不相通)。
- 建议:文件行/预览对话框加"附加到会话"(调 `chatService.stageAttachment`);规划统一工作区详情页(四入口),至少先交付"文件→会话"单向桥。

**P1-4 流式期间重组热路径未动(历史 §4.1b/c/d + 新发现),长会话流式掉帧/弱机 ANR 风险**
- 问题:每 token:①`_screen.update(copy(activeTurn))` 全量 emit;②`conversationEntries` O(K×N) 重算;③`contentVersion` 新实例→LaunchedEffect 每帧重启;④流式 Markdown 全量重解析+4 处行内 Regex 重编译;⑤`storage.turns.resolve` 每 token Room 读;⑥搜索激活时主线程全文扫描。
- 证据:`app/src/main/kotlin/com/helix/app/chat/ChatService.kt:3722-3726,3966-3981`;`chat/ConversationEntry.kt:11-31`;`ui/ConversationSection.kt:104-108,272-278,294`;`ui/MarkdownBlock.kt:40,44,53,77`;`ui/ConversationSearch.kt:46-55`
- 影响:长会话(数十轮以上)所有用户;流式输出体验;弱机掉帧/ANR 边缘。
- 严重度+理由:P1(核心聊天流的用户可见性能退化,且修复成本低)。
- 建议(按收益):conversationEntries 单次遍历分桶;contentVersion 去 effect-key 化(或改 (count,lastTs));Regex 提常量;publishTurn 缓存 turn→sessionId 免每 token 查询;搜索扫描移 IO+debounce。

**P1-5 市场卡片文案错配 + 状态机不完整 + 安装后无落点(历史 UI-7,部分未修)**
- 问题:展开/折叠键显示"命令详情/关闭"(语义错乱);卸载弹窗取消键复用"关闭";状态仅 3 态(文档要求 6 阶段);安装成功无"在当前任务使用";启停/卸载主线程直调。
- 证据:`app/src/main/kotlin/com/helix/app/marketplace/MarketplaceSection.kt:349-354,540,548-574,151-157,173-175,103`;`docs/product/task-experience.md`(§4 状态与路径)
- 影响:所有安装/管理扩展的用户;扩展是 Standard 完整产品组成部分。
- 严重度+理由:P1(用户可见的键位语义错误 + 文档路径断裂)。
- 建议:展开键改用语义化字符串("展开详情/收起");状态机扩到 6 阶段(数据源已有:status+connected+enabled 组合);启用后加"在当前任务使用"(带入能力选择/草稿,不自动发送);启停/卸载移 IO。

**P1-6 斜杠命令 /plan、/act 失效(历史 UI-1,未修)**
- 问题:`/plan`、`/act` 选中后都执行 `onMode(AgentMode.CHAT); onInput("")`——Plan/Act 从斜杠入口不可达(模式菜单可选,但 `/` 补全的承诺行为错误)。
- 证据:`app/src/main/kotlin/com/helix/app/ui/ConversationComposer.kt:83-91`;模式存在性:`core/model/src/main/kotlin/com/helix/core/model/AgentMode.kt:15-20`;PLAN 有实际语义(prompt 注入 plan 上下文:`chat/PromptEnvironmentSections.kt:68`)
- 影响:100% 会话的斜杠命令用户;Plan 模式可达性受损。
- 严重度+理由:P1(核心模式入口断链;修复=两行)。
- 建议:改为 `AgentMode.PLAN` / `AgentMode.ACT`。

**P1-7 模式菜单/终端页英文枚举名直出,中文界面术语断裂(历史 UI-2 扩展)**
- 问题:模式菜单用 `mode.name.lowercase().replaceFirstChar(uppercase)` → 中文界面显示 "Chat/Plan/Act/Goal";终端页 tab 标签与状态行直出 `record.phase.name`/`stopReason?.name`(英文内部枚举)。
- 证据:`app/src/main/kotlin/com/helix/app/ui/ComposerMenus.kt:34,36`;`app/src/developer/kotlin/com/helix/app/terminal/ManualTerminalScreen.kt:130,200`;`app/src/developer/kotlin/com/helix/app/terminal/DeveloperManualTerminal.kt:235-237`
- 影响:中文界面所有用户(模式菜单);developer 终端用户(状态行)。
- 严重度+理由:P1(一致性破坏:i18n 整体优秀,此处是显眼的术语断裂)。
- 建议:模式名走 stringResource(4 键);phase/stopReason 建枚举→stringRes 映射表。

**P1-8 加载态系统性缺失:文件列表"加载中=空目录"、能力页空白、连接测试/市场安装/浏览器下载/Root 请求/准备页均无进度**
- 问题:12 个进度指示 vs 100+ IO 点;最伤的是文件列表加载被误报为空目录;能力页 rows==null 空白;MCP 连接测试挂起无反馈;浏览器 SAVING 无进度无取消;未提交的 Root 授权请求无进度。
- 证据:`ui/FilesScreenEffects.kt:28-44`+`ui/FilesScreenLayout.kt:154-165`;`ui/CapabilitiesScreen.kt:74-82,118-125`;`connector/ConnectorSection.kt:80-107,333-352`;`marketplace/MarketplaceSection.kt:131-145`;`feature/browser/.../BrowserScreen.kt:548-553`;`ui/FilesScreenActions.kt:42-52`;`ui/CapabilityReadinessScreen.kt:136-145`
- 影响:全部长操作用户;文件/扩展/浏览器/准备核心流。
- 严重度+理由:P1(用户无法判断"在干什么/卡没卡",多流共性)。
- 建议:统一 ProgressCard(进度条+文案+可选取消);文件列表引入"加载态"(区分 空/加载/错误);连接测试加最小超时提示(如 10s 后提示仍在进行)。

**P1-9 错误展示不规范:原始错误码/异常 message 直出用户(历史 UI-5,未修)**
- 问题:OAuth 过期直出 `OAUTH_DEVICE_EXPIRED`;连接器操作失败直出 `e.message`(任意内容);文件列表错误直出异常 message;均不满足文档"问题+已完成结果+下一动作"规范;错误条均为临时内联红字,无统一边界。
- 证据:`app/src/main/kotlin/com/helix/app/connector/ConnectorSection.kt:380-383,570-572,434-436`;`ui/FilesScreenEffects.kt:40`;`docs/product/task-experience.md`(§5 错误文案)
- 影响:扩展/连接器用户、文件页用户;错误场景下的理解与恢复。
- 严重度+理由:P1(一致性破坏+可恢复性受损;与 §5 验收目标直接冲突)。
- 建议:建 ErrorMapper(安全码→stringRes 三元组:问题/已完成/下一动作),UI 只渲染映射结果;全工程禁 `Text(e.message)`。

**P1-10 浏览器旋转即丢页面内容(无 WebViewState 保存/恢复,无重导航提示)**
- 问题:MainActivity 无 configChanges → 旋转重建;onDestroy `browser.detach()` 销毁 WebView;重建后标签幸存但 hostView==null,UI 显示占位文案 `browser_enter_address`,用户需手动重输 URL 才能恢复页面;无"页面已失效,点击重新加载"的显式提示与动作。
- 证据:`app/src/main/AndroidManifest.xml:44-46`;`app/src/main/kotlin/com/helix/app/MainActivity.kt:152-160`;`feature/browser/.../ui/BrowserScreen.kt:255-260`;`feature/browser/.../BrowserController.kt:327-330`
- 影响:浏览器全部用户(横竖屏切换、字体缩放都触发)。
- 严重度+理由:P1(核心浏览流在常见手势下内容丢失,且恢复路径不显式)。
- 建议:占位页改为"该页面已停止,点击重新加载"+重导航按钮;中期用 WebView saveState/restoreState 恢复滚动与内容。

### P2(34 条,按主题分组;其中 P2-17、P2-34 为备查/合并注记,实质 32 条)

**审批/聊天体验**
- P2-1 审批卡折叠态缺"预期影响/verifier/代码"(历史 UI-8 部分仍在):`ui/ApprovalCardScreen.kt:71-93`。建议:预期影响提入折叠态;卡片加"会话权限设置"跳转入口。
- P2-2 审批 PENDING 无剩余时间/倒计时提示:`ui/ApprovalCardScreen.kt:100-173`(无 expiry 展示)。建议:折叠态显示"剩余 mm:ss"。
- P2-3 停止键出现导致输入区高度跳变(历史 UI-13 部分):`ui/ConversationComposer.kt:169-177`。建议:停止键与发送键同位切换(固定占位)。
- P2-4 字符图标族(历史 UI-13 部分):"▴/▾"(`ui/GroupedNavigation.kt:60`,未提交)、"▾/✓"(`ui/ComposerMenus.kt:79,84`、`ui/ComposerModelMenu.kt:41,61`、`ui/FilesLocationBar.kt:44,136`、`ui/SessionInputDeliverySelector.kt:35-45`)、"🕶️"(`feature/browser/.../BrowserTabSwitcher.kt:155`)、"✓"(`ui/TaskLedgerCard.kt:96`)。建议:换 M3 图标/语义化。
- P2-5 空会话建议点击覆盖未发送草稿(`ui/ConversationSection.kt:287` onSelectPrompt=onInput;`EmptyConversationHint.kt:68-99`),与语音草稿追加行为不一致。建议:有内容时追加或弹确认。
- P2-6 会话搜索态跨会话串扰(`ui/ConversationSection.kt:103` searchActive 为 remember 非 per-session):切会话搜索条保持打开并对新会话立即重扫。建议:切会话 clear。
- P2-7 跨页状态靠全局单例(历史 UI-9):`MainActivity.kt:261,282,333,352` openSession+navigate;`ChatScreen.kt:96` 组合期写 `chatService.recoverySettingsNavigation`。建议:导航参数化;回调移出组合期(onRemembered/DisposableEffect)。

**导航/生命周期**
- P2-8 旋转丢局部状态(历史 UI-10):`AndroidManifest.xml:44-46` 无 configChanges;rememberSaveable 仅 12 处;扩展 tab、市场筛选、连接器草稿、文件选择等全丢。建议:关键 UI 态上 rememberSaveable/ViewModel。
- P2-9 进程死亡无恢复:回 sessions 列表,无 last-open 会话持久、无浏览器标签持久。建议:持久 lastSessionId+deep link。
- P2-10 Terminal 抽屉项=外部 Activity 的"假路由"(`MainActivity.kt:209-212,455-463`;NavHost 对全部 destination 注册路由 `:234-235`,含 terminal,但入口走抽屉分支):心智不一致。建议:抽屉项标注"外部"或干脆从 NavHost 移除 terminal 路由。
- P2-11 文件 scope 失效静默弹回首页(`ui/FilesScreenState.kt:75-92`):无"该位置已失效"说明。建议:状态行给出原因+重新选择入口。
- P2-12 ShellDestination KDoc 写 twelve 实为 13 项(`ShellDestination.kt:8` vs 枚举 13 项)。建议:修注释。

**一致性/设计系统**
- P2-13 无设计系统(历史 UI-3):351 dp/71 sp/37 RoundedCornerShape/33 裸 AlertDialog/7 自定义卡;空态/错误条/加载条/列表行/菜单 pill 五类复制粘贴(见一致性审计)。建议:Spacing/Shapes/Typography 令牌 + ErrorBanner/ProgressCard/ListRow 共享组件;优先收敛 AlertDialog 与 dp。
- P2-14 术语分裂:产物/成果(8 vs 2 键)、会话/对话(主词 vs 7 处)、权限/能力两入口两叫法(见一致性审计 c 节,strings.xml 对应键)。建议:全中文文档定名,批量改键值(en 不动)。
- P2-15 工作区来源名硬编码英文 "Workspace"(`files/FileManagerService.kt:186`)。建议:loc(R.string.files_local 或新键)。
- P2-16 硬编码 scope/路径:"app"(`ui/FilesScreenLayout.kt:49`)、"/workspaces/app"(`app/src/developer/.../ManualTerminalScreen.kt:186-190`)。建议:引用常量。
- P2-17 英文枚举直出已列 P1-7;此处补充 `CommandResultDetailScreen.kt:174` 仅 testTag(无用户可见,不计)。

**文件管理**
- P2-18 文件列表非懒加载(`ui/FilesScreenLayout.kt:166-180`,Column+verticalScroll,≤500 项全量组合)。建议:改 LazyColumn(与网格对称)。
- P2-19 文件列表错误无重试(`ui/FilesScreenLayout.kt:148-153`)。建议:错误态加"重试"(reloadTick++ 机制已存在)。
- P2-20 Root 请求无进度+主线程 root 检查(未提交):`ui/FilesScreenActions.kt:42-52`、`ui/FilesScreenState.kt:28`、`files/FileManagerService.kt:214-222`。建议:sources() 的 root 探测移 IO 并缓存;请求中按钮转 spinner。

**浏览器**
- P2-21 标签上限拒绝无提示(`feature/browser/.../ui/BrowserTabSwitcher.kt:148,168`;`BrowserTabController.kt:79,309`)。建议:达上限时 toast/行内文案"最多 8 个标签"。
- P2-22 进程死亡标签丢失(无持久化)。建议:标签列表持久(标题/URL/隐身)。
- P2-23 下载 SAVING 无进度无取消(`feature/browser/.../ui/BrowserScreen.kt:548-553`)。建议:DownloadItem 加字节进度+取消。
- P2-24 权限一律拒绝无用户提示(`feature/browser/.../webview/WebViewTabHost.kt:258-266`)。建议:被拒时在顶栏/状态条提示"此页面请求的权限已被 Helix 拒绝"(保持拒绝策略)。

**扩展/能力/终端**
- P2-25 能力页空白+未知 key 崩溃路径(`ui/CapabilitiesScreen.kt:74-82,118-125,373-382` `error()`):未来加能力行忘加文案=崩溃。建议:copyFor 兜底默认文案+加载/错误态。
- P2-26 准备页无下载进度/取消(`ui/CapabilityReadinessScreen.kt:136-145,269-271`;"…"文本加载)。建议:进度事件流+取消;对齐 task-experience §3。
- P2-27 MCP OAuth 设备码无倒计时/轮询进度(`connector/ConnectorSection.kt:657-701`)。建议:显示 expiresInSeconds 倒计时+轮询次数/间隔。
- P2-28 终端页:phase/stopReason 英文直出(并入 P1-7)、busy 无进度、许可证 composition 内读 assets IO(`app/src/developer/.../ManualTerminalScreen.kt:258-268`)。建议:assets 移 IO;busy 加指示。
- P2-29 市场 composition 内 IO+主线程启停(并入 P1-5 证据;`marketplace/MarketplaceSection.kt:103,151-157,173-175`;`connector/ConnectorSection.kt:249`)。建议:列表读移 LaunchedEffect+IO;动作全走 scope+IO。

**其他**
- P2-30 无障碍:5 处 Icon 无 contentDescription(`FilesScreenComponents.kt:115,164`、`FilesHome.kt:80,128`、`AdaptiveConversationHeader.kt:88`);可点击卡片无 role/onClickLabel(`FilesHome.kt:110-130`、`MarketplaceSection.kt:435-440`)。建议:补语义;卡片加 clickable 语义标签。
- P2-31 Toast 11 处(0 Snackbar):连接器撤销/URL 复制/存储失败(`ConnectorSection.kt:494-501`、`BrowserScreen.kt:102,328,642,680`)。建议:关键状态改内联(Snackbar/状态行),Toast 仅留给系统级轻提示。
- P2-32 未提交抽屉:展开态随路由重置(`ui/GroupedNavigation.kt:31-38` remember(currentRoute));组头 selected 逻辑晦涩(`:59`)。建议:展开态 rememberSaveable+独立于路由;组头去 selected 用 badge 图标。
- P2-33 命令详情页 500ms 轮询无首帧加载指示(`ui/CommandResultDetailScreen.kt:60-88`)。建议:首帧加 spinner;轮询保持。
- P2-34 composer maxLines=5(`ui/ConversationComposer.kt:155`)长输入需内滚——可接受,列备查。

---

## 历史审查"维度三"发现的状态复核

| 历史编号 | 结论 | 本次复核(2026-09-24 基线+工作树) |
| --- | --- | --- |
| UI-1 斜杠 /plan /act 失效 | P1 | **仍存在**:`ConversationComposer.kt:83-91` 原样 → 本清单 P1-6 |
| UI-2 模式菜单英文枚举名 | P1 | **仍存在**:`ComposerMenus.kt:34,36` 原样;新增终端页同类问题 → P1-7 |
| UI-3 无设计系统 | P1 | **仍存在且扩大**:304→351 dp(未提交代码新增 28.dp/2.dp 硬编码);无 Spacing/Typography 令牌;7 自定义卡不变 → P2-13(降级为 P2 的原因见下注) |
| UI-4 加载态缺失 | P1 | **仍存在,略有改善**:进度指示 6→12(文件导入/导出、会话导出、批量操作已有);但文件列表"空目录"误报、能力页空白、连接测试/市场安装/浏览器下载/Root 请求无进度 → P1-8 |
| UI-5 错误展示不规范 | P1 | **仍存在**:`OAUTH_DEVICE_EXPIRED` 直出(`ConnectorSection.kt:383`)、`e.message` 直出(`:570-572`);仍 0 Snackbar → P1-9 |
| UI-6 文件→Agent 断链 | P1 | **仍存在**:stageAttachment 仍仅 composer 一处;工作区四入口详情页仍不存在 → P1-3 |
| UI-7 市场文案错配+3 态 | P1 | **部分仍存在**:展开键/取消键文案错配原样(`MarketplaceSection.kt:349-354,540`);3 态原样;改善:安装中按钮文案("安装中")+禁用、卸载确认对话框已有 → P1-5 |
| UI-8 审批卡关键信息折叠 | P1 | **降级 P2**:折叠态现有 scope+参数+风险+出网(比历史版本好);预期影响/verifier/代码仍在展开态;两键设计符合 ADR-0005/HXA-209 B4(不是缺陷,是设计)→ P2-1 |
| UI-9 跨页单例+组合期写回调 | P2 | **仍存在**:`MainActivity.kt:261,282,333,352`;`ChatScreen.kt:96` 原样 → P2-7 |
| UI-10 旋转丢态 | P2 | **仍存在**:manifest 无 configChanges 原样;rememberSaveable 仍 12 处 → P2-8 |
| UI-11 UI 层 IO | P2 | **仍存在**:MarketplaceSection:103、ConnectorSection:249、FilesScreenState 构造 sources();新增未提交 requestRoot/sources() 主线程 root 检查 → P2-20/P2-29 |
| UI-12 超大 Composable | P2 | **仍存在**:ChatScreen 524 行单函数(:53-524)、ConversationSection 515 行(:49-515) → 归入 P2-13 组件化建议 |
| UI-13 图标/交互细节 | P2 | **部分仍存在**:☰ 已补语义(✅);5 处 Icon 无 cd;"▾/✓" 原样;新增 "▴/▾"(未提交)、"🕶️";发送/停止布局跳变原样 → P2-3/P2-4/P2-30 |
| (撤回项)默认中文 locale | — | 维持撤回:base=zh 是 HXA-069 设计,脚本守护(本次 1363/1363/1363 再次验证)✅ |
| REVIEW-2026-09-24 §4.1a 每 token 全量 emit | 部分修复 | **维持"部分修复"**:publishTurn 单字段 copy ✅;但每 token 仍全量 StateFlow emit+新增每 token Room 读 → 并入 P1-4 |

> 注:UI-3 由 P1 降 P2 的理由:颜色 token 全量合规(0 硬编码颜色)、i18n 工程纪律优秀、组件虽重复但各自功能正确;风险是维护成本而非用户流受阻。若团队接受"无设计系统"为现状,本项可作长期技术债;但 AlertDialog×33 + 五类复制粘贴组件建议仍按 P1 节奏推进收敛。

---

## 改进建议汇总(按优先级)

**立即(本周,低成本高收益)**
1. `/plan` `/act` 两行修复(P1-6)。
2. 回收站清空/单条彻底删除加确认(P1-1);浏览器三清除加确认或移入菜单(P1-2)。
3. 市场展开/取消键文案改正(P1-5 子项);模式菜单 4 个中文模式名(P1-7 子项)。
4. conversationEntries 单次遍历分桶 + contentVersion 去 effect-key + Markdown Regex 提常量(P1-4 前 3 项,预计 <100 行改动,消除流式最大两项成本)。

**短期(1-2 周)**
5. 文件页"附加到会话"动作 + 错误重试按钮 + 加载态区分(P1-3 单向桥、P1-8 文件部分、P2-19)。
6. 统一 ErrorMapper(问题/已完成/下一动作)+ 共享 ErrorBanner/ProgressCard 组件(P1-9、P1-8 系统性)。
7. 浏览器:占位页改"重新加载"按钮(P1-10);标签上限提示(P2-21);SAVING 进度+取消(P2-23);权限拒绝提示(P2-24)。
8. 市场状态机 6 阶段 + 启用后"在当前任务使用"(P1-5 主体);MCP 测试超时提示 + 设备码倒计时(P2-27)。
9. 未提交改动合并前:root 探测移 IO + 请求进度(P2-20);抽屉展开态 rememberSaveable(P2-32);Root scope 删除再确认(P1-1 联动)。

**中期(技术债)**
10. 设计系统:Spacing/Shapes/Typography 令牌;ListRow/ErrorBanner/ProgressCard/MenuPill 共享组件;AlertDialog 收敛(33→<10)(P2-13)。
11. 中文术语定名:成果/产物统一、对话→会话统一(P2-14);Workspace 本地化(P2-15)。
12. 生命周期:关键 UI 态 rememberSaveable/ViewModel;last-open 会话 deep link;浏览器标签持久(P2-8/9/22)。
13. 审批卡:预期影响入折叠态 + 剩余时间 + 会话权限入口(P2-1/2);停止键固定占位(P2-3)。
14. 无障碍:Icon contentDescription 批量补 + 卡片语义(P2-30);字符图标换 M3 图标(P2-4)。
15. 能力页加载/错误态 + copyFor 兜底(防崩溃)(P2-25);准备页进度/取消对齐 §3(P2-26);文件列表 LazyColumn(P2-18)。
