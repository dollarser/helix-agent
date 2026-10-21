> 当前消息操作：用户消息仅编辑/复制，编辑不创建分支；模型回复保留创建分支。详见 [消息操作修订](MESSAGE_ACTIONS.md)。

> 当前语音交互已按用户要求改为识别结果直接进入输入框，结束后原处编辑。此前弹窗预览/加入草稿描述以 [语音输入修订](VOICE_INPUT.md) 为准。

# 设计规范与 Compose 实施映射

## 依据与取舍

读取 `AGENTS.md`、`docs/development/status.md`、`ShellDestination.kt`、`SecondaryRoutes.kt`、`GroupedNavigation.kt`、`DrawerSettingsNavigation.kt`、`DrawerEntry.kt`、Settings/Composer/Account 源码和权限、Provider、Goal、知识库、语音 ADR。参考 `build/ui-proposals/helix-drawer-proposal.html` 与 `helix-drawer-preview.html`：暖白、森林绿、紧凑顶部账号、16 dp 新会话按钮、46–48 dp 导航行、固定设置。旧原型没有改动。

本设计没有采用新应用框架或全局 Expressive 组件迁移。保留现有 Compose 状态/导航与领域所有权，用共用视觉参数降低 HTML 与 app 的字号、圆角、图标和留白差异。HTML 新增的子页形态（例如记忆独立页、成果阅读视图）是现有对话框/卡片的展示重组，属于**设计提案**，不是新增运行能力。

## Tokens（CSS px 对应 Compose dp；文字按 sp）

| 角色 | 浅色 | 暗色 | Compose 用途 |
| --- | --- | --- | --- |
| background | `#fffef9` | `#161c19` | colorScheme.background |
| surface | `#f4f7f0` | `#202823` | 抽屉、输入框、浅卡 |
| raised | `#fffef9` | `#28332c` | 表单与对话框 |
| onSurface | `#202a24` | `#e2eae2` | 正文/标题 |
| onSurfaceVariant | `#5c695f` | `#acbbae` | 辅助文本 |
| outlineVariant | `#dce3d8` | `#3e4b41` | 1 dp 分隔线/轮廓 |
| selected | `#dcebdd` | `#324b39` | 导航与选中 |
| primary / onPrimary | `#285e3b` / 白 | `#aed9b7` / `#12341e` | 主操作 |
| error | `#a73833` | `#ffb4ab` | 明确拒绝/失败 |
| warning | `#885315` | `#ffcb8a` | 完全访问与待核对 |

| 参数 | 值 | 使用规则 |
| --- | --- | --- |
| 正文 | 14 / 21.7 | 设置、列表、输入控件 |
| 消息 | 15 / 27 | 长文本连续阅读 |
| 辅助 | 12 / 18 | 描述、元数据；不要整页用小字 |
| 分类 | 11 / 16.5 | 分组与非关键状态 |
| 子标题 | 16 / 24.8 | 卡片标题 |
| 页面标题 | 26 / 33.8；手机 23 / 30 | 页面顶端 |
| 会话欢迎 | 30 / 39 | 空白态主标题 |
| 字重 | 400 / 500–550 | 正文 / 标题；避免满页粗体 |
| 基础间距 | 4、8、12、16、20、24、32 | 4 dp 基线 |
| 小/中/大圆角 | 8 / 16 / 24 | 轻操作 / 卡片按钮 / Sheet 与 composer |
| 图标 | 20 × 20，1.7 描边 | 本任务手写 SVG，无远端图标库 |
| 触控 | 最低 44；导航 46；新会话约 48 | 不随文字高度缩小点击区 |
| 内容最大宽 | 列表 900；表单 660；聊天 740 | 宽屏不无限拉长消息 |
| 导航宽 | 宽屏 254；手机 86%（最高约 330） | 手机遮罩抽屉、宽屏侧栏 |

系统字体只保证可用，不保证每台 Android 与浏览器的汉字字形完全一致；Compose 应统一已有字体族与上述度量，最终以实际排版测量确认。状态不能仅靠颜色，须有文字与图标。CSS 所有颜色、间距和圆角应继续收敛到 tokens；当前少量布局级固定值属于原型度量，不是第二套主题。

## 导航与响应式

默认会话，宽屏固定轻侧栏，手机抽屉覆盖聊天并提供遮罩关闭。取消底部全局 tab 栏，避免挤占输入与消息。顶栏仅保留标题、搜索/更多；账号在抽屉顶部，设置在底部。项目/模型/插件直接到列表，工作/工具使用可展开分组。Git 属于当前会话，不提升为全局主入口。

二级页面有显式返回和“返回会话”。返回用访问栈，模型搜索与会话草稿不因登录、取消或弹窗关闭丢失。宽屏 Sheet 居中、手机贴底，最大高度 92%，内容可滚动。表格式详情使用单列卡片降低手机横向溢出。390 px 画布和 760 px CSS 断点已编写；实际 320/390/768/1280 和放大字体仍需人工浏览器验证。

## 共用组件

- `DrawerRow`：前置线性图标 + 标题 + 可选描述 + 尾部箭头；选中保留圆角底色。不要只移植颜色而漏掉图标和行高。
- `PageHeader`：返回、单行可截断标题、辅助描述、末端轻操作。正文内页标题与动作可换行。
- `ListRow / Card / StatusPill / Notice`：状态文字明确；长内容允许换行。危险操作用语义颜色、二次确认和取消。
- `SearchField / SegmentedTabs / CheckboxRow`：真实过滤结果、选择态、空态和清除路径；禁止只有外观的筛选。
- `ConversationComposer`：24 dp 容器、64 dp 最低文字区、附件与引用 chips、模型入口、添加、麦克风、明确发送/停止。模式从 `/` 命令写草稿，发送才生效。
- `ConversationSheet`：标题、关闭、正文、底部确认。Esc/遮罩/关闭等价取消；焦点圈定并尽量恢复原入口。实际 TalkBack 与键盘遍历需设备/浏览器验收。
- `MessageActions`：复制、朗读、更多；用户消息编辑不分支；模型回复创建分支保留原记录，停止保留已收到消息。朗读是停止/重新开始，不设计不真实的暂停/拖动定位。

## 必须保留的交互契约

1. 账号：未登录可配置 API/订阅/本地；只有内置来源受 Helix 登录约束。登录回到原模型选择，搜索和候选、原草稿保留；不自动保存模型、不自动发送。生成中模型选择不得改当前冻结目标。
2. 模式：新会话默认 Act，Chat/Plan/Act/Goal 命令只先编辑草稿。任务模式和会话权限预设不是同一维度。完全访问不扩张 Plan。
3. 语音：主动录音、预览、编辑、加入草稿、另行发送。拒绝/取消不改原文。切换会话、后台和焦点丢失停止；原型只演示部分生命周期，不假装已通过 Android 音频焦点验收。
4. 工具：schema → policy → authorization → limits → verification/audit；允许本次与拒绝对应具体 call。执行、效果核验、结果保存、模型消费分别呈现；UNKNOWN 不当失败回滚，不自动重放。
5. Goal：目标、Run、激活分开；停止请求不等于执行器退出；轮数耗尽需调整原目标并明确解决阻塞，默认设置不复活旧任务。计划审阅不批准全部工具。
6. 文件：直接编辑、请求绑定目录、执行副本、成果分别命名；冲突保留编辑并可重载/另存，成果不自动源回写。
7. 知识库：原件、提取、来源附注、知识笔记和冻结引用分别展示；先选来源再确认模型/去向；保存笔记不自动核对事实。
8. 提醒与定时：通知不调用模型；定时 Agent 单独 PLAN 只读规则、时间/时区/范围确认，暂停未来与停止当前分开。
9. 插件/技能/连接器：配置、认证、安装和会话选择分别呈现，外部内容不能自授本地权限。

## Compose 映射与实施顺序

逐页映射见覆盖表的源文件列。建议按以下顺序以可比较截图落地，每步保留实际领域状态和现有测试契约：

| 顺序 | 原型对应 | Compose 主要位置 | 实施内容 / 接受点 |
| --- | --- | --- | --- |
| 1 | theme / 壳层 | 现有 theme、GroupedNavigation、DrawerVisuals、DrawerSettingsNavigation、AdaptiveConversationHeader | tokens、排版、图标、行高、手机抽屉/宽屏侧栏；同一界面逐项测量 |
| 2 | chat / add / message | ConversationSection、ConversationComposer、ComposerToolbar/AddSheet、ConversationMessage、MessageRevisionDialog | 默认会话、输入框、消息操作、分支和停止；取消与草稿保留 |
| 3 | models / account | ComposerModelMenu、ModelPickerEdit、AccountLoginForm、AccountSettings、ProviderFormDialog | 模型选择保存回执、忙碌拒绝、登录回跳与搜索；不改变认证域 |
| 4 | voice / speech | OfflineVoiceDialog、LiveReplyReadAloud、CloudSpeechSettings、SystemVoiceSettings | 流式识别预览、确认合并、资源就绪不自动播、停止与生命周期 |
| 5 | tool / goal / plan | ApprovalCardScreen、TasksScreen、TurnProgressLabel | 四个完成事实、UNKNOWN 核对、Goal 状态与原身份；不可用假回执 |
| 6 | history / project / artifact | ConversationHistoryScreen、ProjectsScreen、ProjectDetailScreen、ArtifactsScreen | 列表、搜索、筛选、详情、返回与空态 |
| 7 | knowledge / reminder / scheduled | KnowledgeScreen/QuestionScreen、ReminderScreen/InterpretScreen、ScheduledAgentScreen | 来源选择、冻结引用、时间预览、只读规则与明确范围 |
| 8 | files / browser / terminal / git | FilesScreen、WorkspaceTreePicker、BrowserScreen、ManualTerminalScreen、GitStatusScreen | 路径映射、冲突、选择与恢复、网页边界、渠道可用性 |
| 9 | plugin / connector / skill | ExtensionsScreen、StandaloneSkillsSection、SkillSessionPanel | 安装来源/前置、连接、内容查看与会话使用 |
| 10 | settings 全分类 | SettingsScreen、SessionSettingsScreen、RuntimeSetupScreen、StorageUsageSection、AuditScreen | 共用行与表单、系统权限导航、精确状态；不把诊断当控制面 |

本任务没有修改 Kotlin 或提出框架迁移，也没有更改任何 ADR。实施时对同一组手机/宽屏、浅/暗、正常/空/错误/拒绝状态做 Compose 对照，再按当轮明确授权安排设备验证。

## 第二版补齐的实施重点

详见 [DETAILS.md](DETAILS.md)。Provider 对齐当前 18 模板与三协议；上下文策略使用现有 ProviderContextSettings 边界；网络页改为当前 hosts 编辑器；LAN 使用 origin，出站规则绑定目标与 TTL；CUSTOM 使用七类效应快照。不要将 HTML 模拟校验替代现有 Kotlin 服务。`details.js` 为这些高级表单和纯内存数据的单一正常视图实现，`app.js` 保留会话壳、通用失败态和领域流程。

模拟系统选择器、额外筛选/预览及错过展示策略属于评审设计；真实 Android 表面和已有服务所有权保持原契约。

第三轮按 [复审说明](REVIEW.md) 调整：插件已添加/发现、项目工作台、渠道与 profile 分离、新会话默认权限；成果侧览采用手机模态阅读/宽屏并排。新增布局不改变执行域或真实资源身份。
