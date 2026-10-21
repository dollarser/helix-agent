> 当前消息操作：用户消息仅编辑/复制，编辑不创建分支；模型回复保留创建分支。详见 [消息操作修订](MESSAGE_ACTIONS.md)。

> 当前语音交互已按用户要求改为识别结果直接进入输入框，结束后原处编辑。此前弹窗预览/加入草稿描述以 [语音输入修订](VOICE_INPUT.md) 为准。

## 共用面板与跨页流程

下表“可演示”指模拟状态变化；无真实设备、服务或文件效果。页面共用评审状态不能替代专项业务失败。

| 入口 | 页面/状态 | 交互、确认与取消 | Compose 映射 |
| --- | --- | --- | --- |
| 抽屉 | 未登录/已登录/过期、选中、分组展开 | 顶部账号、固定设置、遮罩关闭、窄屏不占聊天 | GroupedNavigation / DrawerProfileSwitch / DrawerSettingsNavigation |
| 模型 | 搜索、候选、忙碌、需登录 | 选择与保存分开；生成中拒绝保存；取消保留原模型 | ComposerModelMenu / ModelPickerEdit |
| 登录/注册 | 未登录、校验错、验证中、已登录、过期 | 示例邮箱、短密码/不一致拒绝；关闭/取消回到原模型搜索；不发送 | AccountLoginForm / AccountSettings |
| 来源 | API/订阅/本地、未知能力 | API URL 校验；连接与生成分开；取消 OAuth 不连接 | ProviderFormDialog / ProviderModelsDialog / LocalModelDialog / SubscriptionScreen |
| 附件 | 文件/照片选择、取消、选中 chips | 选中添加、逐项移除；拍照仅模拟确认 | ComposerAddSheet / FilesImportDialog |
| 引用 | 会话快照、文件/网页/成果/知识来源 | 加入草稿；不自动发送、不隐式源回写 | ConversationReferencePicker / FileConversationHandoff |
| 专家 | 默认/工程师/研究员、选中 | 影响后续上下文，不授予权限 | SessionExpertDialog |
| 技能/连接器 | 未连接、已连接、会话选中 | 配置/连接/使用分开；未连接选择被拒绝 | SkillSessionPanel / ConnectorSection |
| 模式命令 | Chat/Plan/Act/Goal、草稿候选 | `/` 只插入草稿，明确发送后更新；命令不发给模型 | ComposerCommandParser / ConversationModeControls |
| 权限预设 | 按需、只读、完全访问、确认/取消 | 会话预设与单次工具审批分离；完全访问不扩大 Plan | ComposerPermissionMenu |
| 消息 | 用户/助手、流式/已停止 | 用户消息仅编辑/复制、不创建分支；模型回复保留分支；模拟复制、重试、成果跳转 | ConversationMessage / MessageRevisionDialog |
| 录音 | 权限未授予/拒绝、加载、流式预览、编辑 | 结束后可编辑；加入草稿后仍需发送；取消保持原稿 | OfflineVoiceDialog |
| 朗读 | 资源未就绪、准备中、可用、模拟播放/停止 | 资源就绪不自动播；离开聊天/开始录音停止；无暂停定位 | LiveReplyReadAloud / SystemVoiceSettings |
| 工具 | 等待审批/拒绝/执行中/已核验/UNKNOWN | 绑定 call，允许一次；未知需核对事实，不能自动重放 | ApprovalCardScreen / ToolPurpose |
| 目标 | 运行/请求暂停/已暂停/取消/阻塞 | 继续新 Run；未知副作用先核对；轮数阻塞明确处理 | GoalDialog / TasksScreen |
| 计划 | 版本、步骤、待审阅/已审阅 | 审阅不是批准全部工具 | PlanReviewDialog |
| 用户问题 | 未选/已选/自填/取消 | 空回答拒绝；答案绑定原问题，取消不伪造答案 | UserQuestionDialog |
| 后台工作 | 运行/停止请求/执行器退出 | 结果详情与调用清单分开；不因页切换停止 | BackgroundTaskDialog / TurnCommandListDialog / TaskResultDialog |
| 提醒 | 启用/暂停/删除确认、时间预览 | 固定演示日期、时区/重复核对、取消保留字段 | ReminderInterpretScreen |
| 定时 Agent | 已暂停/启用、跳过/中断记录 | 保存必须确认模型/目录/工具/会话；停止当前与暂停未来分开 | ScheduledAgentScreen |
| 收集 | 文本/演示文件、空正文/超限 | 保存来源不创建 Turn；原件与笔记分开 | KnowledgeCaptureActivity |
| 知识问答 | 未选/已选、范围确认、回答/引用 | 空范围拒绝；展示去向；保存为笔记不删除来源 | KnowledgeQuestionScreen |
| 文件 | 正常/编辑/冲突、选择/恢复 | 冲突可另存或重载；直接编辑和执行副本分开 | FilesScreen / FilesPreviewDialog / FilesSafDialog |
| 浏览器 | 地址无效/有效、后退、标签 | 不加载外部网页；网页请求本地操作被隔离 | BrowserScreen |
| 终端 | 空命令/运行中/停止请求/退出 | 命令仅字符串，状态不冒充真实执行 | ManualTerminalScreen |
| 市场 | 未安装/确认/已安装/卸载 | 模拟安装，显示能力和前置，保留用户内容 | MarketplaceSection / PluginSetupRequiredDialog |
| 存储 | 分类、缓存清理确认/取消 | 不删除用户会话、知识库或真实文件 | StorageUsageSection / WorkspaceCleanupDialog |
| 启动 | Loading/Ready 过渡、失败、预览库需重建 | 明确破坏性操作说明；取消不重建，不访问真实数据库 | AppStartupScreen |
| 导出/分享/删除 | 预览、确认/取消 | 仅模拟内存改变或提示，不打开真实系统面板 | SessionExportDialog / FilesExportDialog |
| 设置表单 | 初值、编辑、保存、取消 | 默认值校验；LAN/出站/QuickJS/云端/语言为示例设置 | SettingsScreen 及各 Section |

## 21 个可复位专项场景

`blank` 新会话；`login-return` 登录回跳；`expired` 过期；`stream` 生成停止；`voice` 录音预览；`approval` 审批；`unknown` 未知副作用；`blocked` 轮数阻塞；`permission` 麦克风拒绝；`error` 加载失败；`empty` 无结果；`loading` 加载取消；`conflict` 文件冲突；`first` 首次配置。

切换专项场景会重置模拟业务状态，保证演示可重复；顶部复位会清除全部模拟数据。当前页正常/加载/空/错误开关在评审目录，不放入产品界面。返回与搜索保留可手动连续演示，不依赖重新载入场景。

## 设计提案与诚实边界

- 全部排版、主题、组件尺寸、宽屏导航和页面组合是本次视觉提案；源码存在只证明该领域有当前入口，不证明布局已在 Compose 实施。
- `memory`、`goal`、`plan`、`artifact`、`question-input`、`background` 等把现有对话框/卡片展开为易评审视图；不声称新增了 Android 全局导航目的地。启动页在原型保留评审壳层，实际 AppStartupScreen 不应依赖该壳层或偏好服务。
- 终端/PRoot/设备自动化显示渠道说明。本次原型为评审保留入口；实施时依据实际渠道/平台可用性隐藏不可用控制，不机械迁移所有入口到 Standard。
- OAuth、连接、安装、语音加载/录音/播放、时间解释、工具执行、后台任务、知识回答、分享和删除均是固定/内存模拟。任何“完成”提示只针对模拟数据。
- 定时只读 Agent 有当前源码；不限时后台执行、远程多设备、自动云端同步、自动发送、付费订阅购买、实时全双工通话、全局子 Agent 调度均未设计为已实现能力。

## 第二版已补齐的第一版缺项

以下均有实际字段、数据变化或失败/取消行为，不再只以页面入口算覆盖。更细逐项规则见 [DETAILS.md](DETAILS.md)。

| 原缺项 | 当前模拟覆盖 |
| --- | --- |
| Provider 完整字段 | 18 来源模板；三实际协议；名称/地址/API Key 保留语义/Header/模型 ID/显示名；创建与编辑区分；非法地址/Header/长度定位反馈 |
| 模型与上下文 | 发现运行/取消/失败/选择；可见性、明确空选择、顺序、显示名；精确模型独立窗口、自动/手动、压缩开关、10–95 阈值、发现失败与保存重试；本机窗口单独约束 |
| 主要设置 | hosts 全文、行号错误、IPv4/IPv6/别名/IDN/大小与地址上限；默认输出和轮数边界；七类 CUSTOM 快照；全局工具开关；精确 LAN origin；Advanced 出站 TTL/撤销/过期/损坏 |
| Runtime / 语音 | QuickJS 原生访问确认；环境变量允许列表与凭据结构拒绝；本地模型高级字段、推荐来源、下载/校验/取消/失败；云端 HTTP/WS/账号固定目标/取消；系统设置成功/缺 Activity 模拟 |
| 项目/历史/分支 | 多项目独立说明/目录；9 会话分页/检索/筛选；原分支保留、切换、逐消息选择与冻结引用 |
| 文件与系统选择器 | 嵌套目录、文本编辑/版本冲突、图片与两页 PDF 示意、页引用、新建/改名、多选移动/冲突、恢复副本、导入/导出范围；content URI/私有 FileProvider/大小与重复校验 |
| 知识库 | 原件、提取失败/不支持/缺原件/处理中状态；附注、人工核对、来源版本变更与冻结引用不变；选定范围才问答；收集重复与边界拒绝 |
| 提醒/定时 | 正文、启用、精确通知请求、当地日期/时区/四种重复、月末/DST 多次预览；规则首日/目标会话/模型/目录/工具/启用/时长；编辑范围撤销确认；发生项中断/UNKNOWN/显式新 attempt |
| 扩展 | MCP JSON 语法/地址/transport/凭据拒绝→预览→默认未启用导入；OAuth client/scope/redirect/文档/Bearer 示例字段；设备码取消/过期；市场类型/签名失败/安装目标/确认；技能结构化字段、正文、路径、预览导入/导出 |
| 工具表面 | 浏览器独立标签/历史/引用/网页桥拒绝；终端多会话/命令草稿/运行/停止退出分开；存储分类清理、工作区清理预览；诊断敏感正文/路径默认不选 |
| 用户输入与生命周期 | 单选/多选、自填 4000 字符边界；录音达到 120 秒进入预览、焦点丢失保留原稿；重启 UNKNOWN 不重放；对话框取消恢复设置草稿/原目录 |

增加的 7 场景：`voice-timeout`、`voice-focus-loss`、`source-failure`、`catalog-signature`、`context-save-failure`、`system-picker`、`restart-review`。录音超时和焦点丢失是可触发的模拟结果，不声称真实计时器或音频设备工作。

## 仍未真实验收的边界

| 依赖 | 模拟已覆盖 | 仍需实际平台/服务验证 |
| --- | --- | --- |
| 浏览器渲染环境 | HTML/路由/事件/状态、浅暗/响应式 CSS、焦点与按钮语义 | 截图、实际溢出/点击命中、中文字体、软键盘避让、200% 字号、屏幕阅读器；浏览器工具限制仍有效 |
| Android 系统 | 选择器、授权拒绝、系统设置缺失、录音停止/取消、文件范围、通知预览 | SAF URI 生命周期、真实拍照/相册/系统分享、OEM 权限、音频焦点/原生资源释放、通知/WorkManager 行为 |
| 外部模型/认证/插件源 | Endpoint/Header/参数校验、目录/连接/生成分开、OAuth/设备码、签名失败状态 | 真实协议、目录能力、账号轮换/额度、TLS/签名/下载字节哈希、OAuth callback、全部供应商响应变体 |
| 运行时/存储 | 状态分离、停止请求到退出、UNKNOWN review、恢复副本、冲突 | 真实进程/Room 原子性、断电/重启竞争、执行器退出与物理容量、真实磁盘改写/回滚不可推定、实际模型消费 |
| 多格式资料与检索 | PDF/图片示意、来源提取状态、冻结片段/版本、选择/重复/大小边界 | 真正 PDF/图像渲染、提取质量、FTS/中文检索、真实附件字节摘要及 URI 拷贝原子性 |
| 时区与调度 | 本地 Intl 日历模拟、固定示例和月末/DST 测试、规则/发生/attempt 区分 | Android tzdata 与浏览器 Intl 版本差异、所有历史时区、OS 延迟/Doze/通知送达；未运行任何后台任务 |

本轮没有剩余以“以后再补字段”为由省略的主要表单路径；也不承诺穷举所有远端协议响应和所有业务数据。视觉重组、更多筛选/预览控制以及原系统页的模拟容器是设计提案；“错过展示策略”等源码没有独立字段的控件在原型明确标为提案。上表边界没有算作真实功能通过。

## 第三轮意图复审与差异化布局

见 [REVIEW.md](REVIEW.md)：补上项目共享指令/记忆/归档/成员保护，拆分构建渠道和 profile，统一插件已添加/发现及添加流程，修正新会话权限继承，增加精确知识范围和聊天成果侧览。手机侧览为有遮罩和返回的模态阅读区；宽屏为可并排操作的非模态侧栏。当前 76 项 DOM 检查；布局与字体的真实视觉效果仍未验收。
