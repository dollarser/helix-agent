# 会话操作入口重设计

本轮先交付 [可交互原型](conversation-entries.html)，再接真实功能。它是现有全应用原型的会话入口专项修订，使用合成资料；本轮入口设计以本专项为准。生产修改基于 `c9af2c80`，已接入下述功能，主机与视觉/设备证据分别记录。

## 职责与原型

| 入口 | 内容 | 范围 |
| --- | --- | --- |
| 输入框加号 | 拍照、照片、文件、引用其他会话、引用知识库、选择已有专家、选择已有插件、使用技能 | 材料与技能进入当前消息；专家和插件明确用于当前会话的后续输入。这里不编辑配置、不自动发送。 |
| 顶部三点「会话管理」 | 会话设置、重命名、导出、当前任务与成果、Git；归档与删除仍在全部会话 | 整个当前会话；保留 Goal、计划、待回答问题、中断恢复和成果能力，按真实状态提供入口。 |
| 输入区三点「输入选项」 | 立即发送 / 排队 | 只保留消息投递控制。推理强度在模型选择中；预算在全局设置；上下文详情直接点击占用圆环。模型与权限快捷入口保留。 |
| 会话设置 | 项目归属、工作目录、会话权限、当前专家/能力选择 | 当前会话；模型上下文策略通过模型管理配置，不创建全局专家，不藏全局记忆设置。 |
| 应用设置 | 专家库的创建/导入/编辑/管理、全局记忆开关/自动写入/内容管理、插件配置与认证、其他既有设置 | 全局。项目记忆保留在项目设置。 |

引用其他会话：加号 → 单一选择面板 → 搜索真实已有会话 → 点击一条 → 当前草稿引用 chip。列表显示标题与归档状态；排除当前会话。摘要/近期消息类型由原服务在选择时核定，不在列表中展示未经读取确认的类型或示例日期。加载、读取失败可重试、没有其他会话、搜索无结果分别表达。取消不改草稿；发送时仍通过既有引用服务冻结有界内容，不因为选择就发送或复制无限历史。

选择专家：加号 → 已保存专家列表（包含默认助手）→ 点击选择 → 返回原会话并显示当前专家。创建/导入/编辑在应用设置的专家管理中完成。选择面板提供明确的管理跳转并记住来源，管理结束返回原选择列表；管理不隐式选择、不改已受理 Turn。空专家库说明真实空态，不填充假专家。

选择插件：加号 → 直接出现一个选择层 → 勾选已有插件 → 明确应用或取消。普通路径没有提示弹窗前置。待登录或待配置原因在同一选择层内显示，只有用户主动选择配置/登录才进入必要流程，返回原选择器并重新核对 readiness；取消不保存选择。实际授权、系统权限与敏感范围说明继续由对应边界处理。技能选择与配置管理分开。

原型入口支持手机/宽屏、自有明暗主题、键盘焦点循环/Escape、引用搜索及目录状态、专家创建/编辑/JSON 导入、插件暂存/取消、材料与能力 chip，以及示例会话切换后恢复各自草稿和选择。导出、模型和运行设置等保留入口只说明既有能力，不假装执行成功；待配置插件也不伪造认证。

## 初始基线证据与实现定位（c9af2c80）

- `ComposerAddSheet.kt` 当前确有 `actions.onSessionSettings()`，还把专家/插件归为会话配置。删除此回调及所有当前消费者，不保留无用兼容参数；将加号限于材料/已有能力。
- `ChatScreen.kt` 的 `expertOpen` 打开 `SessionExpertDialog`，该组件提供名称/指令输入并调用 `saveSessionExpert`。这是真实编辑器，不是已有专家选择器。`SessionExpertRepository` 仅存会话级快照；没有可复用的全局专家目录。实现必须补全持久专家库、严格导入校验与应用设置入口，再通过现有会话专家快照和 Turn admission 接入，不凭专家内容获得权限。当前字段上限为名称 80、指令 8192，格式版本保持 1。
- `ConversationReferencePicker.kt` 当前使用 `sessions.filter { it.id != currentSessionId }`；列表非硬编码空壳，但没有搜索/加载/失败状态。它直接接收 `screen.sessions`。`ChatService.sessions` 实际来自独立 `SessionDirectoryService`，`refreshSessions()` 只刷新目录，`refreshScreen()` 才复制到屏幕快照；两者无自动同步收集。因此目录刷新后屏幕引用列表可能滞后，这是一条可静态复现的候选数据流缺口，尚不能断言就是用户设备空列表的唯一根因。需要主机测试复现并让选择器订阅真实目录状态；同时检查当前会话排除、持久会话存在、归档与读取异常。
- `SessionDao.list()` 返回所有持久会话，包含归档项，没有按当前项目过滤。不能把“默认只显示同项目”当成既定行为。选择后 `referenceKindForSession` 会优先摘要、否则近期消息，并在服务层 prepare；发送时继续冻结快照。
- `ConversationReferencePicker.onMemory` 直接打开 `MemoryDialog`，而该组件包含全局启用、全局/项目自动写入和内容编辑。`MemoryScope` 实际是 Global/Project，不存在独立 Session scope；应迁移配置入口到应用/项目设置，不凭名称新造会话记忆设置。引用资料继续走已有知识库引用能力。
- `ConnectorSessionPanel` 目前是即时持久选择（`state.select`），并不是取消即不保存的暂存选择器；`PluginSetupRequiredDialog` 在选中失败且确需配置时才弹出。当前 `ChatScreen.onConnectors` 直接打开该面板，未找到正常入口无条件 Toast/通知前置，不能虚构根因或删掉真实授权说明。实施时需覆盖用户报告的实际前置提示路径，重构为一个清晰选择层，并保证取消无配置写入。
- `AdaptiveConversationHeader` 包含会话设置、后台任务，附加内容来自 `ConversationSection` 的重命名/导出/目录/Git；输入区 `optionsContent` 又包含成果、TaskLedger、Goal 和运行控制，职责确实交叉。调整入口前要逐项保留现有可达能力，尤其知识库与中断提示。
- `ChatScreen` 引用选择异步 await 后未重新核对 openSessionId；专家/技能打开回调在 materialize 后也未像插件入口那样检查仍处于原会话。实现须把来源 sessionId 和选择请求身份绑定，取消/切换/迟到回调不能修改其他草稿或重开旧面板；保存草稿失败应中止导航。重建以保存的轻量选择身份恢复，重要事实由原服务/持久库拥有。

必要文件范围：本目录原型与入口文档；`ComposerAddSheet/ComposerActions/ComposerToolbar`、`ConversationSection/ConversationIntents/AdaptiveConversationHeader`、`ChatScreen/ConversationReferencePicker/SessionExpertDialog/SessionSettingsScreen/SettingsScreen`；应用导航的设置返回链；专家库应用服务与当前持久存储；插件会话选择服务及界面；引用目录投影；相关中英文资源、主机测试和设备测试源码。只有改变实际契约时才更新对应现有 ADR 的 Decision history；不新增框架、版本升级、迁移链或临时兼容入口。

## 检查与剩余边界

离线检查：`DOM_MODULE=<现有 linkedom 安装绝对路径> node docs/design/helix-ui-prototype/verify-conversation-entries.cjs`。它检查入口分工、引用列表/搜索/空/失败/重试、专家选择与管理返回、导入校验、插件单层选择/取消、草稿保留、不可信文本转义及会话隔离。离线 DOM 不能证明实际排版、触控尺寸、屏幕阅读器、旋转或 Android 接入通过。

浏览器渲染 **blocked**：本轮尝试在 Edge 中打开本地 `file://` 专项原型，浏览器安全策略拒绝（仅允许 http/https，并明确禁止借由转发地址、其他浏览器表面或间接执行绕过）。未开启转发服务、未更换浏览器、未截图；不能声称视觉验收通过。原型已通过 Library 向用户展示。父会话明确视觉渲染不是用户授权生产实现的前置审批，因此独立继续生产接入；不声称视觉通过。

生产接入已实施；主机门禁结果见下方。设备验证 **not requested**，未启动/使用设备、安装或清理数据；未提交、推送或发布。

## 生产接入与入口迁移对照

用户继续明确要求功能不能因界面优化而减少，且 Agent 专用接口不需要可视入口。本轮保留实际能力，以下为迁移与静态接线证据，不把源码可达称为设备体验通过。

| 原入口 / 缺口 | 当前入口与作用域 | 实际接线 / 验证 |
| --- | --- | --- |
| 加号中的会话设置 | 顶部三点 → 会话设置 | `AdaptiveConversationHeader` → 原 `SessionSettingsScreen`；移除加号回调的所有当前消费者。 |
| 专家入口直接编辑会话指令 | 加号 / 会话设置只选择；应用设置 → 专家管理创建/编辑/严格 JSON 导入 | `ExpertLibraryRepository` → Room `experts`；`ChatService.selectExpert` 复制到 `session_experts`。原会话和 Turn 快照独立；主机覆盖导入界限/失败/重建。 |
| 引用中的记忆配置 | 全局记忆在应用设置；项目记忆在项目详情 | 原 `MemoryDialog`/`MemoryService` 保留；项目入口不再展示全局配置开关；没有新建会话记忆 scope。 |
| 插件及独立技能混合即时保存 | 加号选择插件，应用才保存；独立技能会话开关在会话设置，加号技能入口仅加入当前消息 | `applySessionSelection` 在同一事务里做基线/就绪校验；主机覆盖暂存取消、失败、迟到应用、取消传播及部分就绪。配置仍在插件管理，必要授权说明保留。 |
| 输入区成果、TaskLedger、Goal、分支说明 | 顶部会话管理保留 | 原 `ConversationArtifacts`、`TaskLedgerCard`、Goal 回调均保留；相关测试源码按新入口更新。消息上的分支/编辑/引用也未裁剪。 |
| 输入区消息投递方式 | 输入区三点 → 输入选项 | 原 `SessionInputDeliverySelector` 和 `setImmediateMessages`，作用于当前会话；空闲/处理中和单条队列操作语义不变。 |
| 未知上下文用量藏在输入菜单 | 统一点击占用圆环（含未知用量） | 原 `ContextWindowDetails`；详情不重复到输入菜单。手动压缩仍通过既有 `/compact`，清理输入 `/clear` 与模式命令保留。 |
| 推理与预算 | 推理在模型选择；输出/Goal 默认预算在抽屉设置 → Agent 默认行为 | 原 `ComposerModelMenu` / `AppAgentDefaultsScreen`；没有把内部固定资源上限变成新的用户设置，没有会话预算菜单。 |
| 工具权限与工作范围 | 当前权限快捷入口、会话设置；全局权限与安全/系统权限仍在设置组 | 原 `SessionPermissionEditService` 与完整规则/工具可用性页面；没有通过选插件或专家隐式授权。 |
| 审查发现 Chat 工具开关只有展示测试调用、无生产入口 | 会话设置的模式说明下，仅 Chat 模式显示工具开关 | `ModeControlSection` 现直接连接 `setChatToolsEnabled`，按当前会话及运行中限制保存；移除不再使用的 `ConversationIntents.onSetChatTools`，替换测试保留显式开启边界。 |

引用选择等待期间，切会话、再次打开选择、移除引用或接受发送回执都会使旧选择失效；技能加入也复核原会话和草稿可编辑状态。专家管理通过设置子路由返回原选择器，创建/导入不自动选中。插件导航到全局配置后返回原选择器并重新读取事实；同页插件管理与系统/手机配置采用各自既有返回边界。未提交插件选择在页面重建时重新读取持久事实，不自动保存；专家编辑文字和原会话草稿使用保存状态恢复。

## 扩展的用户功能可达性审查

范围：对照 `ShellDestination` 的 14 个现行主目的地、`GroupedNavigation`/`DrawerSettingsNavigation`、`SecondaryRoutes`、`MainActivity` 的真实页面与回调，以及相关功能页内子入口。没有声称扫描并证明所有内部函数、所有 OEM、全部账号或全部条件分支。

| 产品域 | 已核对的用户入口 → 真实实现 | 本轮结论 |
| --- | --- | --- |
| 会话 / 历史 / 搜索 | 抽屉当前/新建/全部会话 → `ChatScreen` / `ConversationHistoryScreen`；标题搜索 → 当前搜索控制器 | 保留；引用改用真实目录观察并开面板刷新，新增“屏幕快照生成后才插入来源”的设备测试源码，未执行设备。 |
| 项目与记忆 | 抽屉项目 → `ProjectsScreen` → `ProjectDetailScreen` 的会话/文件/指令/记忆 | 保留稳定 projectId；Global 配置不藏在项目编辑器中。 |
| 知识库 | 抽屉知识库 / 加号引用 → `KnowledgeScreen` / 原资料选择与 `stageKnowledgeReferences` | 保留资料、笔记、来源、编辑、引用与回到当前草稿；未改上一任务知识库实现。 |
| 提醒 / 定时 Agent | 工作 → 提醒 → `ReminderScreen`；`scheduled-entry` → `ScheduledAgentScreen`，自然语言 → `ReminderInterpretScreen` | 页内有真实服务回调；不是只有隐藏的定时路由。未调用真实服务。 |
| 任务 / Goal / 计划 / 中断 | 工作任务 → `TasksScreen`；会话管理 Goal/任务；消息/持久投影 → 原计划审阅、用户提问、审批与恢复面板 | 保留原恢复事实与动作；不把完成 Turn 当副作用验收，不删中断提示。 |
| 成果 | 工作成果 / 会话管理 → `ArtifactsScreen` / `ConversationArtifacts`，打开任务/来源/预览仍接原服务 | 会话入口迁移到顶部，未裁剪导出或文件能力。 |
| 模型 / 账号 / 订阅 / 本地模型 | 抽屉模型 → `ProviderManager` → `ProviderScreen`；模型对话框推理、上下文配置、`LocalModelDialog`、原订阅 Activity | 保存/取消、模型来源和认证所有权保留；本地资源页并非无入口。 |
| 语音 | 应用设置 → 云语音/系统语音 → 原语音设置、离线 ASR/TTS 资源；输入麦克风与回复朗读 | 配置、资源与使用入口均有生产消费者；未申请麦克风或调用语音账号。 |
| 插件 / Skill / MCP | 抽屉插件 → `ExtensionsScreen` 的管理/导入/市场/内容/配置；`ConnectorSection` 处理端点/认证；加号与会话设置负责选用 | 区分安装、配置、会话选择与调用授权；独立技能开关迁移已有明确归宿。 |
| 文件 / Git / 手动终端 / 浏览器 | 工具导航 → `FilesScreen` / `BrowserScreen`；Git 入口 → `GitStatusScreen`；受渠道能力约束的终端 → 原 `ManualTerminalModule` Activity | 文件读写/导入导出/选择/会话 handoff、浏览器与终端保持原边界；不把工具协议直接暴露为任意执行入口。 |
| 权限 / 运行环境 / 存储 / 诊断 | 抽屉设置组 → 原权限、运行时、存储、审计路由；诊断 → 就绪/能力页 | 全局工具开关、系统授权与诊断报告有实际生产接线；无新增默认授权或数据清理。 |
| 专家 | 原先没有全局目录，只有会话指令编辑 | 本轮补真实保存目录及管理页，选用与管理分开；不是给空选择器改标签。 |

分类边界：`DefaultAppContainer` 中 Skill/插件安装工具、Workspace/Android 文件工具、Vision、QuickJS/PRoot/CLI 运行工具与远端 MCP 发现走既有 Registry → Dispatcher/Policy/Approval；这些 Agent 调用接口不要求逐个设置页。后台恢复、队列 drain、调度 wake 和持久回执是内部生命周期，不增用户页面。渠道不支持的运行时、测试/基准/实验组件不因本轮审查被上线。另见 `SessionCapabilitySection`：它是未挂生产的旧展示组件，但其会话选择能力已有当前 `ConnectorSessionPanel` 承担，不是额外遗漏的产品功能；不为重复展示组件另造页面。

本轮明确补齐的额外孤立产品入口是 Chat 模式工具开关；其余检查域未发现需要新产品决策才能上线的独立能力。本结论仅覆盖上述现行导航与已检查消费者，不替代真实设备/账号的全应用探索验收。

## 本轮验证结果

- 完整主机门禁：`GRADLE_OPTS='-Dorg.gradle.daemon=false -Dorg.gradle.workers.max=1' scripts/check-all.sh --debug` **退出 0**。源码侧 302 项脚本测试、文档/ADR、国际化和秘密扫描通过；Spotless/Detekt/Debug Lint、JVM 单测、两变体 Debug APK、AndroidTest 源码编译、依赖锁与渠道/集成运行时边界通过。没有运行 Release/R8 发布门禁。
- 新增核心主机回归：插件面板状态 5 项、原子选择变更校验 4 项、专家目录/严格导入 3 项，以及当前数据库 schema 契约。全门禁包含这些测试；不把两渠道重复运行合并成额外独立能力数量。
- 专项原型离线交互 19 项通过；`git diff --check` 通过。最终 Library 仍使用同一文件身份，已同步用户对推理、全局预算、上下文圆环及功能保留的后续要求。
- 先前失败属于本轮变更的格式、事务返回类型、schema 契约清单和插件回调复杂度，已修复后重跑全门禁；没有放宽规则、基线或跳过失败测试。源脚本测试日志中“设备恢复失败”等文字是模拟夹具预期输出，不代表本轮执行设备命令。
- 本地日志和成功候选输入/APK 摘要保存在 ignored `build/conversation-entries-2026-10-28/`；以 `debug-gate-final-2.log` 的成功完整门禁为准，不把此前失败构建残留 APK 算作通过。
- 生产版本与内部版本未升级；Room 在版本 1 基线增加独立专家表，未增加旧测试库迁移链，也未执行安装、重建或数据删除。原有预发布库状态检查继续适用。
- 视觉渲染仍 **blocked**，无截图；设备验证 **not requested**；真实模型、MCP/OAuth、语音账号和长期运行未做本轮验收。全应用可达性结论限于上述源码接线审查及主机检查，不是逐设备、逐账号全功能通过。

## 官方竞品复核与小幅原型优化（2026-10-28）

本次实际联网核对官方帮助资料和 Android 项目官方仓库 README。以下分别列出事实与 Helix 的采用判断；不是全产品逐版本截图对比，也不宣称各竞品所有客户端菜单完全一致。只读引用，未复制第三方实现。

| 官方来源 | 本次可支持的事实 | Helix 判断与处理 |
| --- | --- | --- |
| [ChatGPT Image Inputs FAQ](https://help.openai.com/en/articles/8400551-chatgpt-image-inputs-faq) | 输入区加号提供照片/文件添加；资料覆盖 Web 与移动端。 | 支持加号承担材料添加。保留拍照/照片/文件/引用，不加入会话或全局配置。此资料不证明所有可用工具均只在加号，也不证明竞品有 Helix 的跨会话快照选择器。 |
| [ChatGPT release notes：2026-04-28 Easier model selection](https://help.openai.com/en/articles/6825453-chatgpt-release-notes) | 官方明确模型选择在 composer，思考强度移到模型选择器；该条说明的是当时 Web 付费方案。 | 保留现有模型选择与推理流程，不在输入三点复制。没有据此宣称各移动端布局与 Helix 像素相同。 |
| [Creating and editing GPTs](https://help.openai.com/en/articles/8554397-creating-and-editing-gpts) | 官方区分已有 GPT 的使用和编辑器/管理流程；移动端可使用、创建与编辑在 Web。该页当前还包含 GPT 向插件迁移及账号限制。 | 支持“选用与作者管理分离”的方向，不能将 GPT 等同 Helix 专家或宣称所有个人账号现在都能创建。Helix 原生管理页是自身单设备范围及用户明确要求，不照搬 Web 限制。 |
| [Claude custom connectors / remote MCP](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp) | 配置/认证在 Customize → Connectors；已配置的连接器可从聊天加号按会话选用。 | 保留全局配置与会话选择分离；已选能力在 composer 可见，普通路径直接进入选择。Helix 的暂存、明确应用、取消不保存是用户要求，不声称 Claude 使用相同提交协议。 |
| [Claude connector guide](https://support.claude.com/en/articles/11176164-use-connectors-to-extend-claude-s-capabilities) | 既可在 Customize 管理，也允许从加号进入 Manage connectors。 | 这是具体差异：不能说“主流竞品加号都没有管理”。Helix 按用户要求不把设置塞入加号，仍保留选用面板到合适管理页及返回来源的路径。 |
| [Claude Android conversation management](https://support.claude.com/en/articles/8230524-delete-or-rename-a-conversation) | Android 当前会话右上角三点提供重命名/删除，聊天列表也有管理操作。 | 支持顶部三点承载整个会话操作。Helix 仍保留现有全部会话列表中的归档/删除，不因参考竞品而新增破坏性操作或重复入口。 |
| [Claude projects](https://support.claude.com/en/articles/9519177-how-can-i-create-and-manage-projects) | 项目指令、项目知识、项目管理有各自范围；官方也提供加号中的会话 Memory 开关。 | 只采用清楚表达范围的思路。Helix 的 Global/Project Memory 与 Claude 并不等价，不能照搬其跨范围引用或会话开关；保留自身配置入口与用户要求。 |
| [RikkaHub 官方仓库](https://github.com/rikkahub/rikkahub) | 当前 README 列出 Android、多 Provider、多模态、MCP/Skills、Agent 自定义与文件记忆等能力。 | 支持移动端能力需要可发现入口，不能为了简化聊天删去管理。README 不足以确认具体按钮位置或取消提交行为。尝试读取其两个候选 UI 源码目录 URL 被 Web 工具标记 restricted URL，未绕过；未声称已逐个核验源码菜单。 |
| [Operit 官方仓库](https://github.com/AAswordman/Operit) | 当前 README 分列对话材料、模型配置、角色/记忆、工具与扩展市场等能力域。 | 借鉴能力域分组，不把大量功能一股脑塞入加号；不根据 README 推断其每个 UI 控件，也不扩展 Helix 到其全部能力、远程节点或多角色产品范围。 |

采用结论：现有方案的材料/模型/已有能力/配置/会话管理分工合理，有上述局部直接证据；输入三点仅保留投递方式、预算全局、圆环打开上下文，以及专家和插件的具体持久作用域仍属于 Helix 需求与设计判断，不包装成竞品一致事实。

本次仅在既有专项原型补充每项作用范围，并将引用列表调整为与当前真实 UI 一致的标题/归档状态，去掉未接通的日期和摘要类型展示。没有增加页面、减少能力、改生产行为或重建另一套原型；保持同一 Library 文件身份更新。

验证：复核前，上轮 `successful-candidate.json` 记录的所有输入与两份 Debug APK 指纹均相符；生产代码和构建配置本轮未改，因此沿用上轮完整 Debug 退出 0 的证据，不重复构建。修改后的原型重新执行 19 项离线交互检查与 `git diff --check`；本次文档/原型变化不被冒充成新生产候选。视觉仍 blocked，设备仍 not requested。没有尝试绕过先前浏览器拒绝。

## 按既有原型落实 App 呈现与交互（2026-10-28 后续）

Owner 再次明确要求实际 App 按原型优化。本阶段不再以修改原型替代实现，也不重写前轮领域服务。对照 HTML/CSS 与 Compose 结构，确认并落实以下差异：

| 原型要求 | 此前 App 的具体差异 | 本阶段实际生产改动 |
| --- | --- | --- |
| 加号为分组整行入口，说明消息/会话范围 | 使用按文字宽度收缩的 TextButton，缺少范围说明与当前能力摘要 | `ConversationMenuRow` 提供整行触控、换行标题、说明、分隔与禁用反馈；加号显示作用范围、当前专家和插件，保持原动作及使能条件。 |
| 专家为底部选择面板，显示摘要和当前选择 | 居中 AlertDialog 只有名称文字按钮；当前专家只在独立提示中出现 | 复用 `ConversationSheet`，每行显示指令摘要与选中标记；全局库编辑后，与会话快照内容不相同的项目不会误标为已选，明确保留旧快照直到用户重选。 |
| 插件为一个底部选择层，确认/取消易于到达 | 居中 AlertDialog；点击待配置勾选可能直接跳配置 | 改成底部面板；整行可选目标与独立配置按钮分离；未就绪项显示事实原因，不隐式选中/授权。既有组件详情、失败重试、原会话/选择基线校验保留。 |
| 列表可滚动，底部操作独立于正文 | 专家编辑/导入与按钮均在弹窗布局里，选择行不是统一宽度 | 为 `ConversationSheet` 增加可选固定 footer 与 IME 避让，正文占剩余可滚动空间；专家与插件页脚用 FlowRow 适应长文案/大字体，编辑框全宽且限制可见行数，保留草稿内容。 |
| 引用列表可搜索、识别当前选择 | 搜索框未明确全宽；归档标签与标题挤在同一横向文字组；无选中语义 | 全宽搜索、整行选择、独立归档说明；重开面板时标记当前草稿引用。保持读取真实目录及全部空/错/加载状态。 |
| 顶部三点为会话管理列表 | 附加操作部分仍是横向 FlowRow 与窄文字按钮 | 改为纵向整行入口；重命名/导出/工作目录/Git/Goal 等动作先收起管理面板，再进入原功能，保留任务、成果、分支提示与原回调。 |

本阶段额外修正：插件读取失败后不允许按“应用”把未加载事实当作空选择提交；必须先成功重试。未就绪勾选不可操作，但配置按钮独立于禁用的选择行，仍真实可达。没有新增通知前置或虚假成功反馈。

平台取舍：保留 Android Material 3、系统返回与既有颜色体系；采用至少 56dp 的整行目标、最大 560dp 且受窗口约束的滚动面板、可换行 footer，未照搬 HTML 像素。IME 避让只对本阶段的引用/专家/插件面板显式开启；其他现有面板不因此改变授权或数据行为。主机编译不证明窄屏/大字体/键盘的实际设备几何通过。

验证覆盖：保留此前状态/权限/取消主机回归，并增强失败读取不可应用的断言；增加窄屏大字体整行触控与选中态测试源码、引用重开选中态、专家库更新后原会话快照不被误标/自动替换、插件显式配置和返回后仍需主动选择的测试源码。设备测试仅编译，未执行。原型只同步专家快照选中反馈的真实性，未删减已确认设计表现。

恢复复核补充：专家新建和导入在进入编辑状态时分配稳定记录 ID，并随现有 `rememberSaveable` 编辑状态恢复；后端接收该 ID，不在每次保存重试时重新生成。这样在保存与界面重建交错后，再次显式保存仍更新同一条库记录。没有新增迁移、内部版本或权限来源。主机仓库回归增加同身份重复保存只保留一条记录的断言。

### 本阶段最终验证

`GRADLE_OPTS='-Dorg.gradle.daemon=false -Dorg.gradle.workers.max=1' scripts/check-all.sh --debug` 最后一次完整运行 **退出 0**，日志为 ignored `build/conversation-prototype-ui-2026-10-28/debug-gate-verified.log`。包含源脚本、文档/ADR、国际化/秘密扫描、Spotless/Detekt、Debug Lint、全部 JVM 单测、两变体 Debug APK、AndroidTest 源码编译、锁文件及渠道/集成 Runtime 边界。没有跑 Release/R8、设备 instrumentation 或安装。

本阶段生产代码确有改变，没有用前一轮源码指纹复用门禁。成功候选的 HEAD、实际修改输入和两份 Debug APK 摘要保存在同一 ignored evidence 目录的 `successful-candidate.json`。初始格式与测试方法长度失败保留在本地日志；通过拆分测试步骤及格式修复解决，未放宽静态规则或断言。

专项原型 20 项离线交互检查、最终 `git diff --check` 通过。新增/增强的设备 UI 测试只完成编译，包括整行目标在窄宽/大字体下的几何、真实引用选中态、专家管理与重选、待配置插件的独立配置入口；**未执行，因此没有窄屏、大字体、键盘或像素设备通过声明**。浏览器原型渲染仍 blocked，设备仍 not requested。Library 沿用原文件身份，仅同步专家快照的准确选中反馈；没有用删原型表现代替本阶段 App 实现。
# 实屏视觉与预览重建补充（2026-10-28）

语音输入采用听写而非持续语音对话。官方依据：[ChatGPT Voice Dictation FAQ](https://help.openai.com/en/articles/12168547-voice-dictation-faq) 明确转写返回可编辑文字、再由用户发送；[Gboard Type with your voice](https://support.google.com/gboard/answer/2781851?hl=en) 明确麦克风入口、“Speak now”状态及暂停操作。由此推导本产品方案：输入区内一个状态面板，准备/录音/识别中状态明确，停止并识别是主要动作，取消始终丢弃临时结果；成功追加原草稿后可编辑，不自动发送。失败或空结果保留原草稿，在同一面板重新录制或取消，不退回后再靠通知解释。后台中断关闭并恢复草稿，切会话不回填迟到结果，重试必须等待上一轮物理资源释放。不变更提供商、模型、上传授权或系统权限边界。真实麦克风/音频上传未经授权，本轮只用合成PCM和模拟状态验收。

真机截图暴露了加号面板的重复长说明、浅色面板内白色矩形行与连续分隔线造成的碎片感。本轮保留全部入口与作用域，统一使用主题低层级表面、圆角透明行和轻选中底色；行高保留至少 56dp，摘要最多两行、标题继续换行，不牺牲触控和大字可读性。HTML 原型同步这些视觉规则；浏览器本地文件限制仍未绕过，实际视觉验证来自已授权设备。

重建页灰按钮来源于旧终端运行未确认停止；不能移除安全条件。待重启时提供有效的主要“重新检查”动作，满足条件后才显示清空按钮。已通过模拟器 OS 重启获得停止证据，再经 App 正常确认完成已授权主库重建；没有使用 pm clear、卸载或删除共享文档/Linux 工作区。真机由用户自行清空，本轮不再次清理。设备安装成功与 UI 验收结果分别记录于忽略的本地证据目录。
