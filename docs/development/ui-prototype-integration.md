# UI 原型接入与功能分层

范围：以当前真实能力为准，将原型作为视觉和任务组织参考，不以像素一致率为验收。保留现有 UI/账号在途修改和所有用户数据；首轮交付时未提交；后续复核获准本地提交，见[发现与语音复核](tool-discovery-followup.md)。基线为 `168cdbe5`，局部原始 diff 与源码库存保存在 ignored `build/ui-integration/`。

## 覆盖映射

共 55 个设计视图、21 个场景。下表不把对话框/Activity 强行变成独立导航页；加载/空/失败仅在真实状态存在时展示，评审模拟开关不进产品。下表分别记录重新设计、复用既有交互、仅共享主题影响和未验边界；55 是设计视图映射规模，不是 55 项新功能或全部业务验收通过。

| 视图 | Compose / 现有状态与接口 | 操作与适配边界 | 当前验收 |
| --- | --- | --- | --- |
| `startup` 启动与恢复 | [AppStartupScreen.kt](../../app/src/main/kotlin/com/helix/app/AppStartupScreen.kt)；AppStartupScreen / StartupState | 启动、失败、预览库重建确认。独立启动首帧；不因视觉改版清库。 | 修复首帧主题；明暗加载帧设备测试通过 |
| `question-input` 请求用户输入 | [UserQuestionDialog.kt](../../app/src/main/kotlin/com/helix/app/ui/UserQuestionDialog.kt)；UserQuestionService / UserQuestionDialog | 单选、多选、自填、取消。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `background` 后台工作与调用结果 | [BackgroundTaskDialog.kt](../../app/src/main/kotlin/com/helix/app/ui/BackgroundTaskDialog.kt)；ChatService / BackgroundTaskUi | 状态、停止、结果、调用清单。运行、停止请求、退出、结果读取及模型消费分别呈现。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `chat` 当前会话 | [ConversationSection.kt](../../app/src/main/kotlin/com/helix/app/ui/ConversationSection.kt)；ChatService.screen / ConversationIntents | 发送、停止、消息操作、草稿。需薄 UI 状态：原处语音、保留草稿、独立消息编辑；不触发隐式发送。 | 重新设计导航/输入/独立编辑；交互回归通过 |
| `history` 全部会话 | [ConversationHistoryScreen.kt](../../app/src/main/kotlin/com/helix/app/chat/ui/ConversationHistoryScreen.kt)；ChatService.sessions / sessionSearch | 搜索、筛选、打开、重命名、删除。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `search` 会话内搜索 | [ConversationSearch.kt](../../app/src/main/kotlin/com/helix/app/ui/ConversationSearch.kt)；ConversationSearchController / ChatService.findHistoryMessage | 搜索、定位消息。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `session` 会话设置 | [SessionSettingsScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/SessionSettingsScreen.kt)；ChatService / SessionPermissionEditService | 工作区、专家、连接器、导出、归档。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `projects` 项目 | [ProjectsScreen.kt](../../app/src/main/kotlin/com/helix/app/projects/ui/ProjectsScreen.kt)；ProjectService / FileManagerService | 新建、检索、打开项目。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 统一内容卡片；四配置列表巡检通过；项目业务未逐项复验 |
| `project` 项目详情 | [ProjectDetailScreen.kt](../../app/src/main/kotlin/com/helix/app/projects/ui/ProjectDetailScreen.kt)；AppContainer / ProjectService / ProjectRecords | 说明、项目会话、工作目录。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用详情与会话操作；统一卡片；未单独设备验 |
| `models` 模型与连接 | [SettingsScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/SettingsScreen.kt)；ProviderService / ProviderManager | 来源分组、连接、配置、选择。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用真实来源/目录；主题与导航更新；四配置巡检通过 |
| `provider` 自配 API | [ProviderFormDialog.kt](../../app/src/main/kotlin/com/helix/app/provider/ui/ProviderFormDialog.kt)；ProviderService / ProviderFormDialog | 模板、校验、保存、连接检测。直接复用三协议与校验；不复制 HTML 校验器。 | 复用原表单/校验；主题更新；5 项表单设备回归通过 |
| `subscription` 订阅服务 | [SubscriptionScreen.kt](../../runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/SubscriptionScreen.kt)；SubscriptionScreen / CLI Runtime | 登录、版本、认证目录。保留独立 Runtime Activity 与认证所有权，不模拟订阅登录。 | 原生视图换色/字号/限宽；首页四配置通过；OAuth 未请求 |
| `local` 本地模型 | [LocalModelStorageSection.kt](../../app/src/main/kotlin/com/helix/app/ui/LocalModelStorageSection.kt)；LocalModelStorageSnapshot / 既有 load、clear 回调 | 导入、加载、卸载、能力信息。仅呈现真实资产状态；不新增推荐目录或自动下载。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `account` 账号与服务 | [AccountSettings.kt](../../app/src/main/kotlin/com/helix/app/ui/AccountSettings.kt)；AccountSession / AccountSettings | 未登录、已登录、过期、退出。已有真实登录/过期；保留原模型搜索返回，不强制登录。 | 入口/视觉接入既有在途修改；3 项几何与入口回归通过；账号链独立记录 |
| `tasks` 全部任务 | [TasksScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/TasksScreen.kt)；ChatService.backgroundTasks / recoveryPanels | 筛选、打开、精确停止。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用真实任务投影/动作；共享主题；四配置巡检通过 |
| `goal` 目标详情 | [GoalDialog.kt](../../app/src/main/kotlin/com/helix/app/ui/GoalDialog.kt)；ChatService / Goal controls | 进展、阻塞、暂停、继续、取消。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `plan` 计划审阅 | [PlanReviewDialog.kt](../../app/src/main/kotlin/com/helix/app/ui/PlanReviewDialog.kt)；ChatService / PlanReviewDialog | 版本、审阅、执行边界。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `scheduled` 定时 Agent | [ScheduledAgentScreen.kt](../../app/src/main/kotlin/com/helix/app/scheduled/ScheduledAgentScreen.kt)；ScheduledAgentService | 规则、时间、范围、启停、发生记录。无新增错过策略字段；保留 PLAN 与冻结范围。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `artifacts` 全部成果 | [ArtifactsScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/ArtifactsScreen.kt)；ChatService.artifactFiles / backgroundTasks | 类型筛选、预览、引用、实际能力内分享。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 统一内容卡片；6 项会话成果交互回归及四配置巡检通过 |
| `artifact` 成果详情 | [CommandResultDetailScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/CommandResultDetailScreen.kt)；ChatService / FileManagerService | 正文、来源、结果状态。复用既有有界模态预览与详情；宽屏保留原生阅读面，避免新建文件读取/权限路径。 | 复用有界原生预览/详情；不新增并行文件读取路径 |
| `knowledge` 知识库 | [KnowledgeScreen.kt](../../app/src/main/kotlin/com/helix/app/knowledge/KnowledgeScreen.kt)；KnowledgeService / noteService | 来源/笔记、搜索、多选、问答。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 列表重排为单一滚动流/卡片/开关名称；6 项交互与四配置通过 |
| `source` 来源详情 | [KnowledgeScreen.kt](../../app/src/main/kotlin/com/helix/app/knowledge/KnowledgeScreen.kt)；KnowledgeService | 原件、提取、引用、笔记。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用真实提取/引用/删除界面；知识库交互回归覆盖部分动作 |
| `note` 知识笔记 | [KnowledgeScreen.kt](../../app/src/main/kotlin/com/helix/app/knowledge/KnowledgeScreen.kt)；KnowledgeNoteService | 编辑、引用快照、保存。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用原编辑/保存服务；知识库交互回归覆盖；生成模型另记 |
| `question` 知识问答 | [KnowledgeQuestionScreen.kt](../../app/src/main/kotlin/com/helix/app/knowledge/KnowledgeQuestionScreen.kt)；KnowledgeQuestionService / ProviderService | 范围、内容去向、生成、引用、保存笔记。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用真实检索问答；共享主题；两项真实模型测试通过，非完整 UI 链 |
| `collect` 收集到 Helix | [KnowledgeCaptureActivity.kt](../../app/src/main/kotlin/com/helix/app/knowledge/KnowledgeCaptureActivity.kt)；KnowledgeService / KnowledgeCaptureActivity | 材料校验、收集、不自动发送。保留系统 SEND/SEND_MULTIPLE 与 URI 校验，不把模拟 URI 当真实文件。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `memory` 人格与记忆 | [MemoryDialog.kt](../../app/src/main/kotlin/com/helix/app/ui/MemoryDialog.kt)；MemoryService / MemoryDialog | 会话人格、记忆编辑、显式保存。保留现有会话/项目记忆所有权；不增加云同步。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `reminders` 提醒 | [ReminderScreen.kt](../../app/src/main/kotlin/com/helix/app/reminder/ReminderScreen.kt)；ReminderService | 列表、创建、暂停、时间解释。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `reminder` 编辑提醒 | [ReminderInterpretScreen.kt](../../app/src/main/kotlin/com/helix/app/reminder/ReminderInterpretScreen.kt)；ReminderService / ReminderInterpretScreen | 时间/时区/重复、预览、确认。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `files` 文件管理器 | [FilesScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/FilesScreen.kt)；FileManagerService / SafTreeService | 位置、选择、预览、编辑、恢复、引用。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 统一入口卡片与共享主题；文件/浏览器导航回归通过 |
| `file` 文件编辑与预览 | [FilesScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/FilesScreen.kt)；FileManagerService / FilesPreviewDialog | 直接编辑、保存冲突、恢复。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `location` 文件位置 | [FileLocationNavigation.kt](../../app/src/main/kotlin/com/helix/app/ui/FileLocationNavigation.kt)；LocalFileLocation / FileConversationHandoff | 应用工作区、授权目录。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `terminal` 终端 | [ManualTerminalScreen.kt](../../app/src/developer/kotlin/com/helix/app/terminal/ManualTerminalScreen.kt)；ManualTerminalViewModel / ManualTerminalModule | 命令、运行、停止、会话。Developer 条件能力；保留真实 PTY、停止/退出/UNKNOWN 与恢复边界。 | 复用现有终端与主题；10 项入口/恢复合成控制器回归通过；真实 PTY 布局未重验 |
| `browser` 浏览器 | [BrowserScreen.kt](../../feature/browser/src/main/kotlin/com/helix/feature/browser/ui/BrowserScreen.kt)；BrowserController / BrowserScreen | 地址、后退、标签、网页边界。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 仅原生外壳共享主题；导航回归通过；不改外部网页内容 |
| `git` Git 状态 | [GitStatusScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/GitStatusScreen.kt)；ChatService.gitWorkspaceReader / GitWorkspaceReader | 当前会话仓库、差异、边界。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `extensions` 插件 | [ExtensionsScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/ExtensionsScreen.kt)；PluginService / ExtensionsScreen | 已添加/发现/添加；配置/提示与工具/详情。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 统一已装插件卡片与共享主题；四配置入口巡检通过 |
| `plugin` 插件详情 | [ExtensionsScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/ExtensionsScreen.kt)；PluginService / plugin setup | 能力、前置、配置、启用、卸载。不添加全局插件启用开关；配置/工具/会话选择分开。 | 复用真实配置/工具/详情与权限；共享卡片；未安装新插件 |
| `market` 市场 | [ExtensionsScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/ExtensionsScreen.kt)；MarketplaceSection / existing install service | 检索、详情、实际能力内安装。仅用实际可用目录与安装能力；无可用目录时显示真实空/错误。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `connectors` 连接器 | [ExtensionsScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/ExtensionsScreen.kt)；ConnectorSection / MCP service | 配置、OAuth 实际能力内、状态、会话启用。保留真实 OAuth/设备码/配置入口，不新增 IMA/腾讯文档伪连接器。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `skills` 技能 | [StandaloneSkillsSection.kt](../../app/src/main/kotlin/com/helix/app/ui/StandaloneSkillsSection.kt)；StandaloneSkillsSection / Skill service | 导入、创作、查看、使用。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `skill` 技能编辑 | [SkillSessionPanel.kt](../../app/src/main/kotlin/com/helix/app/ui/SkillSessionPanel.kt)；SkillSessionPanel / Skill service | 内容、来源、保存、会话选择。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `settings` 通用设置 | [SettingsScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/SettingsScreen.kt)；SettingsScreen / preference services | 账号、语言、语音、网络、关于。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 入口改为图标行/统一分组；四配置巡检通过 |
| `defaults` Agent 默认行为 | [RunControlSettingsSection.kt](../../app/src/main/kotlin/com/helix/app/ui/RunControlSettingsSection.kt)；RunControlStore | 后续 Turn、输出、Goal 自动轮数。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用现有参数/保存契约；统一分组；四配置巡检通过 |
| `permissions` 权限与安全 | [SettingsScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/SettingsScreen.kt)；SafetyProfileStore / SessionPermissionEditService / EgressRuleService | 会话预设、profile、LAN、出站规则。保留既有权限权威，不能从原型开关授予权限。 | 复用既有安全语义；统一分组；四配置巡检通过；未扩大权限 |
| `system` 系统权限 | [SystemPermissionsScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/SystemPermissionsScreen.kt)；SystemPermissionsScreen / system launchers | 麦克风、通知、文件授权、拒绝。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用系统 launcher；四配置巡检通过；未授予新权限 |
| `runtime` 运行环境 | [SettingsScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/SettingsScreen.kt)；ProotToolModule / RuntimeSetupScreen | QuickJS、PRoot、订阅运行时。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用真实运行时状态与入口；四配置巡检通过；未重建环境 |
| `proot` PRoot 环境 | [ProotEnvScreen.kt](../../runtime/proot-client/src/main/kotlin/com/helix/runtime/proot/client/ProotEnvScreen.kt)；ProotEnvScreen / Runtime client | 安装、进度、取消、状态。Developer 条件能力；重建/清理不在视觉验收中实际执行。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `storage` 存储管理 | [StorageUsageSection.kt](../../app/src/main/kotlin/com/helix/app/ui/StorageUsageSection.kt)；StorageUsageService / StorageUsageSection | 分类、缓存、模型、清理确认。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用存储管理；统一分组；四配置巡检通过；未实际清理 |
| `audit` 诊断与审计 | [AuditScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/AuditScreen.kt)；AuditLogService | 记录、筛选、报告、能力链接。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用真实筛选/脱敏投影；纯列表提取；四配置巡检通过 |
| `readiness` 就绪检查 | [CapabilityReadinessScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/CapabilityReadinessScreen.kt)；AppContainer / readiness projection | 检查、缺失条件、对应设置。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用就绪事实；共享主题；四配置巡检通过 |
| `capabilities` 能力目录 | [CapabilitiesScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/CapabilitiesScreen.kt)；AppContainer / capability projection | 渠道、条件、实际可用性。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 统一内容卡片；四配置巡检通过 |
| `setup` 首次配置 | [FirstLaunchNoticeScreen.kt](../../app/src/main/kotlin/com/helix/app/ui/FirstLaunchNoticeScreen.kt)；FirstLaunchNoticeScreen / FirstLaunchStore | 告知、选择模型、就绪。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 重排原生告知页；保留原确认行为；全链未单验 |
| `speech` 语音设置 | [CloudSpeechSettings.kt](../../app/src/main/kotlin/com/helix/app/ui/CloudSpeechSettings.kt)；SpeechSettingsStore / VoiceCapture / ReadAloud | 本地/云端、资源、上传说明。原处转写；HTTP 分段与 WS 持续流式按实际配置标注；不称原生全双工。 | 替换语音弹窗为原处输入；5 项 fake 输入边界通过；真实服务另记 |
| `network` 网络设置 | [NetworkSettingsActivity.kt](../../app/src/main/kotlin/com/helix/app/network/NetworkSettingsActivity.kt)；NetworkSettingsActivity / hosts service | hosts 编辑、保存、校验。原型 catalog 的代理/DNS 文案不采用，实际只呈现已有 hosts 编辑器。 | 复用 hosts Activity；入口图标/主题；未修改网络配置 |
| `mobile` 设备自动化 | [MobileUseSettings.kt](../../app/src/developer/kotlin/com/helix/app/automation/MobileUseSettings.kt)；MobileUseSettings / Advanced availability | Advanced、授权、可用性。Developer 条件能力；不新增系统权限或自动化授权。 | 复用既有真实界面；共享主题影响；本轮未单独设备验 |
| `about` 关于 Helix | [AboutHelixSection.kt](../../app/src/main/kotlin/com/helix/app/ui/AboutHelixSection.kt)；AboutHelixSection / build metadata | 渠道、版本、许可。直接复用现有领域服务；仅调整呈现、导航和操作可发现性。 | 复用真实构建信息；共享分组；设置巡检覆盖首屏 |

## 关键状态与操作

| 场景 | 接入与验收边界 |
| --- | --- |
| `blank` 新会话 / 空白 | 真实空会话截图、导航及四种显示配置巡检通过。 |
| `login-return` 模型 → 登录 → 返回 | 模拟账号登录返回及草稿/查询保留通过；真实账号恢复、刷新、退出及实际对话独立通过。 |
| `expired` 账号过期 / 保留草稿 | 复用现有过期反馈；账号访问回归覆盖部分边界，未单独注入真实服务令牌到期。 |
| `stream` 生成中 / 停止 | 保留既有生成/停止；真实对话重建、切换会话、单次最终持久化通过；未做长时间流式压力测试。 |
| `voice` 语音 → 输入框实时识别 → 原处编辑 | 5 项 fake PCM 原处输入测试通过；真实服务合成音频往返另通过，不等于真实麦克风体验。 |
| `approval` 工具审批 / 拒绝 | 审批布局回归通过；Activity 重建后实际点击拒绝，原绑定哈希不变且持久化 DENIED，通过。 |
| `unknown` 副作用未知 / 不重放 | 复用原 UNKNOWN/查询/不重放契约；10 项终端合成控制器回归通过，未重验真实 PTY。 |
| `blocked` Goal 阻塞 / 恢复 | 复用已有 Goal 状态与操作，本轮未专门设备验收。 |
| `permission` 麦克风拒绝 | 未新增麦克风权限；假权限检查覆盖拒绝不采集，不声称系统授权面板已实测。 |
| `error` 加载错误 / 重试 | 模型表单与历史绑定回归通过；其余错误状态复用，本轮未逐项故障注入。 |
| `empty` 检索空结果 | 知识库及内容入口空态、检索交互回归通过。 |
| `loading` 加载中 / 取消 | 启动明暗主题与实际像素检查通过；部分采集取消使用 fake 流验证。 |
| `conflict` 文件版本冲突 | 保留既有版本冲突与恢复，本轮未单独设备验收。 |
| `first` 首次启动 | 首次提示/启动布局复用并换主题；Release 启动、导航与版本冒烟通过。 |
| `voice-timeout` 录音达到 120 秒 | 沿用原 120 秒采集上限，本轮未实际持续录音 120 秒。 |
| `voice-focus-loss` 音频焦点丢失 / 停止 | 5 项朗读 fake 音频回归覆盖焦点、后台、切会话等停止边界；不算真实扬声器测试。 |
| `source-failure` 来源提取失败 / 保留原件 | 复用现有来源提取失败与原件保留，本轮未专项注入。 |
| `catalog-signature` 目录签名失败 / 禁止安装 | 复用现有签名校验与禁止安装，本轮未安装插件或专项注入。 |
| `context-save-failure` 上下文保存失败 / 重试 | 复用原持久化错误；编辑存储事务回滚回归通过，不代表上下文保存全链专项通过。 |
| `system-picker` 系统选择器模拟 / 拒绝 URI | 保留原生 SAF/分享与 URI 校验；本轮不真实外发分享、不开新授权。 |
| `restart-review` 重启恢复 / 未知效果核对 | 编辑重建、真实账号进程恢复、真实对话 Activity 重建通过；未知副作用恢复语义未改。 |

## 当前差异与 Android 取舍

- 原型 55 路由不是 55 个 Android 全局目的地；系统授权、文件选择、订阅登录保留原生界面和 Activity 边界。
- 共用颜色、字重、行距与圆角；Android 触控目标至少 48dp，不照搬 HTML 的 44px 下限。宽屏限制内容宽度，窄屏优先保留输入与主操作，不增加底部全局导航。
- 原处语音是一项明确交互变更：复用 VoiceCapture，不改 ASR/TTS 协议。partial 只作输入框瞬时预览；final 才提交可编辑草稿；取消/会话切换/后台恢复原稿；不发送。云端上传告知和麦克风授权继续存在。
- 用户消息编辑不是“修订并发送”：必须单独提供不创建 Turn、不分支、不删除后续消息的命令。保留原消息证据，并将旧回复标为基于编辑前内容；草稿独立。
- OAuth、目录下载、市场安装、分享和音频不得以演示数据冒充成功。不新增云同步、购买、远程设备或全双工能力。

## 分层与已有计划

沿用 [现有重构原则](feature-refactor-strategy.md) 与 [Harness 目标依赖](../architecture/harness-refactor-plan.md)：可信手动入口调用应用服务，模型操作仍经过原 Dispatcher；不重启历史 R/J 阶段。UI 的可重组状态、焦点、选择/取消和布局归呈现层；持久事实、校验与操作完成归既有服务。不要一页面一组 usecase/repository 空接口，不新增导航/事件框架。

本轮按实际消费者抽取展示组件与独立编辑命令；未建设平行运行时或空转用例层。Agent 引擎、工具执行器、Provider 编码/鉴权、知识库存储、PRoot 与技术恢复的执行语义保持。

采用 Android 官方的[可选 Domain 层](https://developer.android.com/topic/architecture/domain-layer)、[状态提升](https://developer.android.com/develop/ui/compose/state-hoisting)和[UI 事件](https://developer.android.com/topic/architecture/ui-layer/events)原则：页面状态汇合既有服务；滚动、展开、布局留在最低公共 UI 拥有者；重要审批和执行状态继续读取原持久事实，不新建容易丢事件的中转总线。

### 已落地的边界与删除

- `AdaptiveHelixNavigation` 只决定同一份导航内容采用永久侧栏还是模态抽屉，宽度判断可独立测试，不改变渠道/能力。
- `InlineVoiceInput` 拥有前台采集与 Android 授权交互，`InlineVoiceControls` 只接收状态和回调；`VoiceCapture` 与服务协议保持原实现。删除 `OfflineVoiceDialog` 及其旧交互测试，替换为原处编辑测试。
- `MessageEditDialog` 通过 `ChatService.prepareMessageEdit/saveMessageEdit` 调用独立 `UserMessageEditor`，不调用发送、分支或 Provider。删除旧 `MessageRevisionDialog`、`MessageRevisionSource` 及无生产调用的旧编辑准备/保存/丢弃接口。普通 composer 不再有修订模式或自动弹旧编辑器。`loadComposerInput` 在既有 admission/cache 锁内核对旧缓存：已受理仅清匹配回执；未受理保留文本/附件为新的普通未发送草稿，不重放。旧已持久化 REVISE 请求的执行/回执契约不改，显式请求测试继续验证出网确认、附件绑定和去重。
- 用户明确要求的独立编辑需要新增受控存储命令：原消息 ID/序号保留，以版本条件更新正文和搜索投影，原正文保留为 SYSTEM 证据；只失效从旧正文派生的压缩摘要，不删除回复或执行记录。新增旧回复范围标记及分支复制标记；没有表结构/迁移变化。这是明确交互能力所需的持久化改动，并非声称所有存储文件未变。
- `SettingsEntryRow` 是原生设置入口的共享展示组件，实际动作继续调用原入口；不为开页面增加 usecase。
- `HelixCard` 统一项目、知识库、成果、文件、Git、插件和能力列表的原生内容分组。保留原点击/选择/语义 tag，不复制数据流或改变审批卡的强调语义。

## 验证与交付边界

本轮只使用现有 emulator-5554（API36 ARM64）和既有授权测试账号、合成资料；没有操作真机，没有清除日常应用数据，没有新增系统权限或真实分享。临时 Debug 包与独立 Release 包均与日常包分开。证据保存在 ignored `build/ui-integration/`，来源输入、安装候选和 APK 摘要由本地证据记录绑定，不把失败构建残留 APK 计为通过。

### 主机

- 最终生产代码完整门禁：`GRADLE_OPTS='-Dorg.gradle.daemon=false -Dorg.gradle.workers.max=1' scripts/check-all.sh --all`，退出 0，见 `final-host-all-window-resource.log`。包括源约束、文档/国际化/秘密扫描、单测、Lint、Consumer 与 Developer Release/R8、测试 APK 编译及依赖/运行时边界检查。
- 前次完整门禁确有 Compose 资源读取 Lint 失败；已使用 `LocalResources` / `LocalWindowInfo` 正确 API 修复，无 suppress 或 baseline 放宽。旧失败日志保留。之后生产代码冻结，新增设备测试单独构建和静态复核。
- 临时 Debug 包切换曾触发 zipflinger 4 GiB 堆 OOM；限定单次 JVM 6 GiB、worker 1 后成功，未修改全局 Gradle 配置或杀全局进程。失败候选未安装。
- 独立签名 Consumer Release/R8 构建成功；`scripts/run-release-smoke.py --suite basic` 在模拟器执行 2 项通过，独立包已移除。不是将 Debug 通过算发布通过。

### 模拟器和真实服务

- 编辑/语音/账号/导航/审批布局/工具时间线/知识库/成果交互一组 46 项通过；启动主题/账号几何/Provider 表单/终端合成控制器一组 20 项通过；账号表单/返回/历史模型绑定一组 8 项通过；草稿与分支一组 7 项通过。各组有重叠，不合并成独立功能数量。
- 最新主候选四种配置各 4 项，共 16 次通过：竖屏浅色、横屏暗色字体 1.3、平板暗色永久侧栏、竖屏字体 1.6。包括 15 个页面入口巡检、原生订阅首页、窗口主题及实际 Compose 像素。不能据此宣称全部页面的全部动作验收。
- 横屏大字体实测发现知识库固定头部挤掉搜索框；改为同一 LazyColumn 滚动流后复验通过。账号 48dp 最小触控目标按实际像素整数验证通过。编辑重建保持原草稿、消息身份与旧回复，不新建 Turn、不自动发送；持久化范围排除内部压缩摘要后通过。
- 真实账号完成登录、目录、短回复、进程恢复、刷新、退出与退出后访问拒绝。真实知识库模型 2 项通过。日志发现第一次模型未选到目标工具，原查询未留存，原因未确定；一次有界对照中模型确实调用 `tools.search` → 原 operation 的 `code.linux.job.read_log`，消费合成 stderr 标记和 exit 7，原操作提交次数 1。不能泛化为发现稳定性证明。
- 真实对话 UI 1 项通过：输入框发送合成短文本、Activity 重建、切邻会话并返回，准确最终答案只持久化一次、1 个 COMPLETED Turn、0 次工具调用、邻会话 0 Turn。首轮测试误用大写 UI role 而超时，修正为既有小写投影；未修改生产投影或放宽答案断言。
- 合成 TTS 24 kHz 音频转换为相同 16 kHz PCM，HTTP/WS ASR 返回一致中文；双头鉴权、ready/end/取消/重连和实际 1013 忙状态有记录。质量未作严格断言，没有真实麦克风体验或云服务改动。
- 保留原中文 IME，实际拼音组合与中文提交已观察；未安装新输入法。显示尺寸、密度、字体与夜间配置复原。

### 截图

本地可查看 `build/ui-integration/representative/` 的聊天、抽屉和空登录表单；`layout-cases/portrait-light/` 的设置与知识库；`layout-cases/tablet-dark/` 的暗色永久导航与设置；`layout-cases/landscape-dark-large-font/` 的知识库。具体候选来源在各 evidence 目录记录，不把截图存在本身当作所有功能已验。截图不含测试凭据。

### 范围与未验证

55 视图映射已逐项区分实改、复用、仅主题影响和未单独验收。没有新增虚假 IMA/腾讯文档连接器、同步、支付、远程设备或原生全双工功能。OAuth、真实分享送达、真实麦克风、真实 PTY、大型本地模型加载、其他 OEM 与长期压力不在本轮已通过范围。

UI/state：主题、导航布局、共享卡片/入口、composer、原处语音、消息编辑器和旧回复标记。接口：独立手动消息编辑应用接口及页面状态投影。数据库命令：版本条件更新正文/搜索，保留原文证据，只失效派生摘要；无表结构或迁移变更。核心引擎、Dispatcher、Provider 协议、知识库索引、PRoot 执行与技术恢复未重构。CLI 子模块仅原生订阅 UI 与主题资源变化。

首轮交付时修改保留在工作区待审查；后续获准本地提交，见[复核记录](tool-discovery-followup.md)。既有账号/抽屉/原型与无关文档改动保留。最终审批重建与清理结果补记于下。

### 最终补验

- `ApprovalFlowDeviceTest#profileSwitchDoesNotChangePendingDecisionOnDevice` 在实际 Activity 重建后通过可见界面点击拒绝，核对原 approval binding hash、Dispatcher 拒绝结果和持久化 DENIED，1 项通过。初次失败保留：实际无障碍树已显示按钮，平台按文字查询未返回 Compose 虚拟节点；测试改为遍历同一应用可见节点后通过，没有改生产审批流程、直接调用拒绝替代 UI 或放宽持久化断言。测试 fixture 明确完成引导前置条件。
- 最后 test-only 改动后 `:app:assembleDeveloperDebugAndroidTest spotlessCheck detekt` 成功；`scripts/check-all.sh --source` 退出 0（286 项脚本测试、文档/ADR、国际化、秘密扫描）。新增测试没有要求再次运行生产 Release/R8。
- 所有临时验收包均已卸载；保留现有模拟器与日常包。反向端口映射已移除，显示与输入配置保持原状。首轮交付时未提交；后续提交与验证见复核记录，始终未推送。
