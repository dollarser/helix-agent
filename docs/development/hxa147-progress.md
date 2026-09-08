# HXA-147 交互与界面进展

日期：2026-09-08。状态：done；逐要求验收见 [完成记录](../completion-records/HXA-147.md)。累计Git修改范围审核与暂存已完成，见 [收尾审计](main-closure-audit.md)。

## 官方资料与取舍

见 [研究记录](hxa147-interaction-research.md)。官方资料支持的交互与 Helix 自身取舍分开记录，不移植竞品权限或执行语义。

## 首次使用与发送确认

三语言首次使用说明改为当前本机/模型服务/外部工具、凭据配置入口和用户授权行为；删除面向用户的 ADR/FR 编号、旧“Standard不提供永久允许”和“规则未来提供”描述。Consumer 说明准确指向当前安装包的入口差异。单次发送确认说明只陈述本次授权，不改动已有规则。原来两端对齐的正文改为默认起始对齐，避免中文与英文产生不自然的字距。

DisclosureDialog 的 text slot 现在仅有一个带间距的可滚动 Column，明细和单次确认说明顺序布局，确认/取消按钮保持独立。新 DisclosureReadableUiTest 使用八个长文件名，验证最后附件和说明可滚动读取，说明完整位于可见容器内且不与确认按钮重叠，再能返回 Provider 明细。

`./gradlew spotlessApply detekt :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest --max-workers=1` 构建通过（`build/main-verification/hxa147-notice-build.log`）；新增测试最终构建见 `hxa147-disclosure-verified-build.log`。首次截图测试引用未安装的 UiAutomator 导致编译失败，改用平台 UiAutomation 截图，没有增加依赖。i18n 907 keys 通过。

API29/36 Consumer/Developer 各4/4，总16/16、无失败/跳过，精确命令、类列表、安装hash和截图在 `hxa147-notice-result.json` 与四个 `hxa147-notice-api*-*/`。覆盖 FirstLaunchNoticeTest、DisclosureDialogTest、DisclosureReadableUiTest、发行包各自的 ProfileConsumerFixedTest/AdvancedSwitchTest。人工核对 API29 Consumer 和 API36 Developer 的 disclosure.png：说明和按钮不重叠，长内容可滚动。测试使用固定中文环境；本轮不是完整中英文/大字体/可访问性验收。

## 最新回复与阅读位置

ConversationTimeline 已接入真实聊天页：跟随时滚动到末尾锚点，用户上翻或无障碍滚动后保持阅读位置，提供“回到最新”按钮；切换会话重置跟随。长回复增长、完成时节点切换、视口缩小、用户阅读期间追加内容与显式返回已在 API29/36 Consumer/Developer 通过12/12，见 `hxa147-scroll-result.json`。

真实模型 Goal UI 验收加强为未裁剪边界检查：两次 Continue 后均断言最新短回复整体位于聊天视口，末尾锚点可见；测试不主动滚动聊天列表。使用 30008 端口 SGLang 的 Qwen3.8-27B，双 API 双发行包4/4通过、共8次模型回复，安装hash、命令、截图与结果在 `hxa147-real-ui-api29-consumer/` 等四个目录。已人工核对 API29 Consumer conversation.png，最新回复完整可读。`hxa147-real-ui-build.log` 构建/Detekt通过。真实模型验收覆盖短回复；超长流、用户上翻保持由上述生产组件专项验证，不宣称所有模型/网络情形覆盖。

## 长目标与模型标识

新增 ExpandableSummary，Goal 标题默认三行、聊天模型/Provider 默认两行；超出时提供展开/收起，文本变化后重置折叠，短文本不显示无用入口。展开箭头与标题同行并提供三语言无障碍说明；聊天优先显示模型名称。最初单独一行的文字按钮在真实截图中抵消了节省的空间，已改为同行图标，使用本地简单矢量而不新增图标依赖。

首版展开与滚动组件回归16/16，真实模型接线复验2/2，见 `hxa147-summary-result.json`、`hxa147-summary-real-api29-consumer/` 和 `hxa147-summary-real-api36-developer/`；这些属于首版布局快照。同行图标最终构建 `hxa147-summary-inline-build2.log` 通过，最终设备与截图证据另列，不将首版结果冒充最终布局验收。测试编译首次使用不存在的 DpRect.height、图标首次引用项目未安装的 Material icons，均已修正，失败日志保留。

最终同行版本在 API29/36 Consumer/Developer 展开与滚动回归16/16通过，证据 `hxa147-summary-inline-result.json`。API29 Consumer 真实模型再次通过1/1（两次Continue），见 `hxa147-inline-real-api29-consumer/`，人工截图核对模型名称优先可见、箭头同行、最新回复完整可读。该最终布局只新增这一组真实模型复验，其余三组真实模型属于此前滚动版本。i18n910键、文档277文件/131任务、ADR28和diff检查通过。

## 窄屏与大字体模式入口

新增 ModeLayoutDeviceTest，在240dp容器、1.8倍字体下检查所有模式完整位于视口并具有至少48dp触摸高度。修改前API29 Consumer基线失败：`chat-mode-goal`不可见，见 `hxa147-mode-baseline.log`。生产布局改为 FlowRow 内 FilterChip，当前模式使用 selected 语义和选中外观；运行期间全部禁用，点击已选模式不重新触发模式修改。测试继续检查选中状态、实际模式切换和运行期间禁用。

模式布局修复后双API双发行包8/8通过（新窄屏测试与既有模式入口测试各一次），见 `hxa147-mode-result.json`；`hxa147-mode-build.log`构建及Detekt通过。这里只验证240dp/1.8倍字体的模式组件及选中语义，不代表完整App大字体/屏幕阅读器验收。

## 输入区布局

将输入框从附件/语音/发送的同一行提取为全宽编辑区，最多显示五行（更长输入由输入框滚动），下方保留附件/语音和发送/停止操作。辅助操作可换行，发送/停止占独立空间。回调、输入期间禁用和仅附件可发送的规则沿用原行为。新增 ConversationComposerDeviceTest 检查240dp/1.8倍字体的完整可见、编辑宽度、空输入/附件/文字发送、运行时禁用和停止回调。

输入区/模式/滚动组合在API29/36 Consumer/Developer共20/20通过，见 `hxa147-composer-result.json`。`hxa147-composer-build2.log`构建和Detekt通过；首次构建的MatchingDeclarationName失败已通过独立ComposerActions.kt修正。i18n910键、文档/ADR/diff检查通过。

本次最终布局的真实模型验收API29 Consumer、API36 Developer共2/2通过（各两次Continue），见 `hxa147-composer-real-api29-consumer/` 和 `hxa147-composer-real-api36-developer/`；安装hash、命令和截图随结果保存。已人工核对API29 Consumer conversation.png：Goal选中态清楚，输入区全宽，附件/语音/发送可见，最新回复完整。截图为默认中文/普通字体，完整App大字体与英文矩阵仍待后续验证。

## 执行状态反馈

原界面在没有流式文字时一律显示“等待模型响应”，工具执行/审批和取消时含义不准确。新增 TurnProgressLabel 按当前Turn状态显示准备、等待模型、接收响应、工具执行、结果保存、中断及停止状态；RUNNING_TOOL中当前Turn存在PENDING审批卡时提示等待审批。取消优先于仍存在的审批卡；完成/失败沿用原结果或错误区。已有流式文字保留并与状态纵向排列。状态文本带polite live-region语义，三语言资源一致，不改变执行/重试/授权。

状态组件、输入区与滚动组合双API双发行包20/20通过，见 `hxa147-progress-result.json`。`hxa147-progress-build2.log`构建和Detekt通过；首次格式门禁的超长行已修复。另新增实际聊天页停止验收，使用保持打开的loopback SSE，从界面点击Stop，检查连接关闭、Goal/Turn取消、run结束及预算预留结算。

实际停止验收最终测试构建及Detekt见 `hxa147-stop-ui-build3.log`；新增测试超过方法长度门限后提取了独立结算检查，未删减断言。当前三语言918键、文档/ADR/diff检查通过。

实际停止首轮API29 Consumer通过，Developer在连接前超时；定向查询该测试会话的持久化Turn证实FAILED/TOKEN_BUDGET_LIMIT（`hxa147-stop-ui-preflight-diagnosis.json`），并非Stop执行失败。夹具原来继承设备历史Turn预算，现显式设置测试预算并在finally恢复。首轮失败目录保留，修复后的完整矩阵另行记录。

修正夹具预算后API29/36 Consumer/Developer实际Stop与状态组件共8/8通过，见 `hxa147-stop-ui-fixed-result.json`，精确类列表、安装hash及日志在四个fixed目录。每组实际Stop测试均从聊天按钮触发，等待真实loopback SSE断开、Goal/Turn CANCELLED、run结束和预算预留清空，界面“已停止”与发送入口恢复。此项为受控模型连接的生产UI/服务/存储接线验收，不等同远端真实模型、所有工具后端或断网验收。最终测试构建/Detekt见 `hxa147-stop-ui-build4.log`。

## 失败重试入口

新增实际UI重试测试首先揭示失败Goal仍展示重试按钮的问题：断开模型流后Goal已进入FAILED，点击重试被现有Goal准入拒绝，没有第二个Turn。首轮 `hxa147-retry-ui-api29-consumer/` 失败日志与定向持久化诊断保留。ADR-0004与当前GoalReducer语义未改变；不通过放宽终态重启让界面测试通过。

ChatService的retryTargetFor呈现查询现在对绑定Goal复用GoalSummaryQuery.canContinue（状态/预算/未决调用/open run），保持最新失败Turn选择规则，不回退重试更旧的Turn。普通聊天仍可显式重试；不可继续的Goal保留错误说明和“目标”管理入口。新增测试分开验证普通聊天重试产生恰好两个Turn（FAILED、COMPLETED）、失败Goal无重试按钮且不增Turn、实际Stop结算。构建与Detekt通过 `hxa147-retry-eligibility-build.log`。

最终双API双发行包三条生产流程加状态组件共16/16通过，见 `hxa147-retry-eligibility-result.json`；每组精确命令、APK安装hash和instrumentation状态码保存在对应目录。正常聊天显式重试成功、Goal不可继续时不再提供无效重试入口，原Stop结算保持通过。测试均为受控loopback模型，不扩写为全部远端故障或外部工具重试验收。

## 空会话与Goal主操作

空会话根据Provider是否绑定及当前Goal模式显示下一步提示，已有消息/工具/恢复行/Turn时不显示空态。Goal模式输入区原“发送”实际只打开目标管理面板，现明确标为“打开目标”，空输入也允许查看已有目标；普通聊天保留空输入禁止发送，运行时仍为Stop。没有改动目标创建、验收条件、Continue或授权语义。

实际UI测试增加空态文案与可见性断言，Goal空输入点击打开目标再关闭后Turn数仍为0，开始请求后空态消失。生产构建见 `hxa147-empty-build.log`，测试构建/Detekt见 `hxa147-empty-test-build.log`。

双API双发行包空态/打开目标/重试/Stop/状态组合16/16通过，精确测试、安装hash和日志见 `hxa147-empty-result.json`。本次覆盖已绑定Provider的Chat/Goal实际页面；未绑定Provider提示和完整英文/大字体页面仍在后续布局矩阵内。三语言922键、文档及diff检查通过。

## 实际页面语言与布局矩阵

新增显式布局参数与截图夹具，使用实际Chat/Goal空态、运行、Stop、失败和重试流程，保存六种状态图片，并断言Activity真实资源语言。首轮API29英文三个流程通过但缺少两张空态截图，整体不通过；API36受系统per-app语言覆盖，仅持久化选择未改变Activity语言，三例失败。原始 `hxa147-layout-en-api29-consumer/`、`hxa147-layout-en-api36-developer/` 保留。

修正夹具改为实际设置页语言选择入口，等待Activity语言生效，退出恢复既有测试中文固定语言；默认runner不增加语言覆盖行为。补齐空会话截图调用。此处修正的是验收夹具，没有改动产品语言机制。

通过设置页切换并补齐截图后，英文API29 Consumer/Developer、API36 Developer共9/9实际流程通过，三组六张截图齐全（`hxa147-layout-en-fixed-api*/`）。人工核对API36 Developer empty-goal.png：英文资源、模式选中、Provider和空态说明、Open goals入口可见。尚未完成API36 Consumer与中文对照，也未以此声明小屏/大字体通过。

首个320×640dp/1.3倍字体实际页面轮次在首次说明初始化失败：说明页可滚动，但原辅助函数未滚动到屏外Continue即点击，后续会话列表等待超时。修正辅助函数为先performScrollTo再点击，不跳过首次说明。`hxa147-small-display.json`确认运行后显示尺寸和字体已恢复；该失败轮次保留于 `hxa147-layout-small-en-api36-developer/`。

修正首次说明辅助点击后，普通尺寸API29 Consumer中文对照3/3且六截图齐全（`hxa147-layout-zh-api29-consumer/`）。320×640dp/1.3倍字体第二轮进入聊天但三条流程都无法找到空态节点，仍FAIL（`hxa147-layout-small2-en-api36-developer/`）。`hxa147-small-display2.json`确认尺寸/字体恢复。此为当前实际页面布局待修问题，未以普通尺寸或组件测试替代小屏验收；正在补失败页面截图定位空间分配。

第三轮失败截图 `hxa147-layout-small3-en-api36-developer/empty-goal.png` 明确显示根因：会话列表与Profile占据Provider同行两侧，在320dp/1.3倍字体下中间列极窄，origin/residence/chips不断换行，把聊天区及输入区挤出屏幕。不是模型/服务或空态条件错误。现将导航/Profile移到独立可换行行，Provider区域使用整行宽度；保留完整来源和能力信息及原绑定入口。第三轮显示设置已恢复，见 `hxa147-small-display3.json`。

单纯Provider分行的第四轮普通中文3/3通过（`hxa147-layout-header-zh-api29-consumer/`），但小屏仍3例失败，截图 `hxa147-layout-small4-en-api36-developer/empty-goal.png` 显示常驻模式说明/预算/Provider详情仍使聊天视口为零，不能宣称分行已解决小屏。显示设置已恢复。

进一步新增AdaptiveConversationHeader：屏幕高度≤640dp或字体≥1.3时，主页面保留模式/模型摘要、返回和会话设置入口，完整模式/预算/Provider内容置于可滚动设置对话框。普通布局继续直接展示详情；执行、授权和模型配置行为不变。实际小屏测试增加打开设置、滚动查看预算和Provider、关闭后继续原流程。生产与测试构建/Detekt分别见 `hxa147-adaptive-header-build.log`、`hxa147-adaptive-header-test-build.log`。

紧凑标题第五轮小屏英文三流程3/3通过，见 `hxa147-layout-small5-en-api36-developer/result.json`；设置内容滚动可达，Stop/重试正常。人工核对stopped.png确认聊天内容、停止状态和输入/操作可见；empty-goal.png捕获了设置窗口关闭动画中间帧，因此不能作为稳定空态截图。截图夹具进一步改为有界等待连续相同像素帧，原过渡图片保留，最终图另行采集。`hxa147-small-display5.json`确认显示尺寸和字体恢复。

第六轮小屏3/3与当前普通中文3/3（`hxa147-layout-adaptive-zh-api29-consumer/`）通过；小屏empty-goal图仍是设置面板，说明仅像素稳定不足以驱动Compose待处理的关闭状态。已在关闭后显式waitForIdle并断言关闭按钮消失，之后才截图；未把第六轮空态图作为布局通过证据。显示已恢复，见 `hxa147-small-display6.json`。新增测试构建见 `hxa147-layout-idle-build.log`。

第七轮API36 Developer、320×640dp、1.3倍字体、英文三流程3/3通过且六张稳定截图齐全，见 `hxa147-layout-small7-en-api36-developer/`。人工核对empty-goal.png，确认设置已关闭、完整空态说明/输入框/附件/语音/Open goals可见，聊天区域不再为零；设置预算与Provider可滚动读取已由测试验证。`hxa147-small-display7.json`确认原尺寸和字体恢复。此通过只覆盖该明确组合，其他API/发行包/语言、更大字体与跨页面矩阵继续待验。

320×640dp/1.3倍字体的API29/36 × Consumer/Developer × zh-CN/en完整八组合24/24通过，目录 `hxa147-small-matrix-api*-*-*/` 每组六状态截图、语言断言、实际设置查看与Stop/重试记录齐全；构建 `hxa147-matrix-test-build.log`。原恢复检查API29因字体设置缺省null被系统归一化成1.0而报字符串不等，保留原脚本失败；后续独立核对两台物理尺寸恢复且字体有效值均1.0，见 `hxa147-small-matrix-restoration.json`。不将恢复脚本字符串失败当作App测试失败，也不掩盖记录。

## 工具执行卡片

240dp/1.8倍字体基线复现长工具名导致状态不可见（`hxa147-tool-row-baseline.log`）。工具名/状态改为FlowRow；参数默认三行、结果默认五行，并复用可展开全文组件。原请求/结果字符串未截断存储，审批卡完整字段和动作保持原样，不改动Dispatcher/权限。

双API双发行包工具布局两例、展开组件一例及审批卡四例共28/28通过，见 `hxa147-tool-row-result.json`；验证长名状态可见、参数/结果展开后布局增长且完整文字保留、审批字段与动作边界。构建/Detekt通过 `hxa147-tool-row-build.log`。这组是生产UI组件验收；实际工具后端流程保留既有独立证据。

## 当前宿主回归

`hxa147-host-result.json`：强制执行App Consumer315、Developer339，共654 JVM测试，无失败/错误/跳过；spotlessCheck、detekt、根lintDebug与lintRelease全部通过，日志 `hxa147-host.log`。命令使用已记录force-tests init脚本与既有Connector样本配置，未跳过样本测试。1160个源码/配置文件在执行期间hash一致。这是当前App/根静态门禁，不把654计作全仓JVM数，也不声明整个HXA-147或发布验收完成。

## 当前收口状态

HXA-147实现、功能及布局验收已完成，见 [逐要求对应表](../completion-records/HXA-147.md)。下文保留各轮快照和失败修复过程，历史“待验”由后续对应证据覆盖，不重复列为当前待办。累计Git修改已逐路径核对并暂存，并行修改边界保留；真机、长稳及既有付费账号边界保持后置。

## 2 倍字体截图复核与空态滚动

320×640dp、2倍字体、API36 Developer 英文首轮三流程通过（`hxa147-layout-font2-en-api36-developer/`），但人工截图发现 Attachment 被同排主操作挤成两行，空态说明被末尾跟随滚至最后。这组仅是流程通过，不能作为完整布局通过；原截图保留，显示恢复见 `hxa147-font2-display.json`。

输入区操作改为整个 FlowRow 按可用宽度换行，避免主操作先占宽后挤压附件/语音；新增320dp/2倍字体英文Goal组件检查，连同已有编辑/发送/Stop双API双发行包8/8通过（`hxa147-composer-flow-result.json`）。空会话关闭自动末尾跟随，保留从开头手动阅读；出现实际对话内容后恢复正常跟随。新增说明开头到实际内容末尾的转换测试。两次构建与Detekt通过 `hxa147-composer-flow-build.log`、`hxa147-empty-follow-build.log`；最终组合设备回归与实际页面截图仍以随后结果为准。

最终输入区/时间线组合双API双发行包24/24通过（`hxa147-empty-follow-result.json`）。API36 Developer英文、320×640dp/2倍字体实际Chat/Goal空态、Stop与重试三流程3/3通过，六截图齐全（`hxa147-layout-font2-fixed-en-api36-developer/`）；人工复核empty-goal.png，说明从开头显示，Attachment与Voice各自完整一行，Open goals可见。长说明仍需滚动阅读，不声称整段同时在屏。显示尺寸及字体恢复见 `hxa147-font2-fixed-display.json`。三语言924键、文档277/任务131、ADR28和diff检查通过。其余2倍字体组合、长说明实际滚动读取及跨页面验收继续；此前654 JVM/根Lint属于本次两项UI修正之前的快照，最终门禁需按最终源码刷新。

2倍字体的320×640dp完整API29/36 × Consumer/Developer × zh-CN/en八组合24/24通过，每组六张稳定截图、语言核验、设置查看与Stop/重试证据见 `hxa147-font2-matrix-api*-*-*/`；两台显示恢复记录为 `hxa147-font2-matrix-api29.json`、`hxa147-font2-matrix-api36.json`。人工复核API29 Consumer中文empty-goal.png，完整说明、输入和主操作可见；英文长说明从开头显示但需要滚动，不把这组流程验收扩写为全App布局验收。新增跨页面只读检查继续验证设置、Provider草稿、文件与浏览器，不清空已有Provider或工作区。

## Provider 滚动与跨页面首轮

新增NavigationLayoutDeviceTest，仅打开实际设置、Provider模板/草稿、文件、浏览器和会话入口，不清空已有Provider、不写工作区、不触发外部模型。API36 Developer英文2倍字体首轮在Ollama模板点击后仍停留模板列表，`provider-form-model`不存在；失败截图 `hxa147-navigation-font2-en-api36-developer/provider-form.png`实际为模板窗口。根因是模板Column先verticalScroll再heightIn，限制了滚动内容的测量高度。改为先限制视口再滚动，并为此前不可滚动的Provider表单增加同样的有界滚动容器；配置、保存与授权语义未变。

构建/Detekt通过 `hxa147-provider-scroll-build.log`。同组合复验通过1/1，模型字段滚动可达、取消后未保存、五张页面截图齐全（`hxa147-navigation-font2-fixed-en-api36-developer/`），显示恢复见 `hxa147-navigation-font2-fixed-display.json`。这是有限入口/表单检查，截图暴露尚待修复的文件工具栏文字重叠和浏览器Clear history被挤出；不将该测试绿灯当作全页面验收。接下来补强对应控件/文件列表断言并修正布局；Provider模板说明仍含内部文档引用，也需纳入面向用户文案整理。

Provider表单及有限跨页面入口检查在320×640dp/2倍字体的双API双发行包中英文八组合8/8通过，见 `hxa147-navigation-matrix-api*-*-*/`；每组五截图、安装hash、命令和语言核验齐全，两台显示恢复记录在对应matrix-api29/36.json。文件工具栏/列表空间、浏览器清理入口和Provider文案仍按上述截图缺口继续修复，8/8不代表这些问题已经解决。

## 文件控件与列表空间

2倍字体英文截图已证实文件工具栏重叠且目录区几乎没有剩余空间（`hxa147-navigation-font2-fixed-en-api36-developer/files.png`）。新增AdaptiveFileControls：≤640dp高度或≥1.3倍字体时主页面保留当前来源/路径及文件选项入口，原来源、面包屑、排序、视图和动作在可滚动面板中显示；普通尺寸保留直接显示。排序/动作改用FlowRow，长面包屑可横向滚动。只改变呈现，原授权、数据与文件动作回调保持不变。

NavigationLayoutDeviceTest补充面板打开、来源/网格/导入/新建文件夹入口滚动可见、关闭并点击work目录。API36 Developer英文320×640dp/2倍字体1/1通过，六截图齐全（`hxa147-files-controls-font2-en-api36-developer/`）；人工检查files-options.png各动作不重叠，files.png显示/work路径与原有文件列表。没有创建、删除或清空文件。构建/Detekt通过 `hxa147-files-controls-build.log`，显示恢复记录 `hxa147-files-controls-font2-display.json`。完整语言/发行包矩阵及当前宿主门禁正在刷新，浏览器清理入口仍待修复。

八组合设备流程均通过8/8，见 `hxa147-files-controls-matrix-api*-*-*/`。API36 Developer英文测试已返回OK(1 test)，随后ADB短暂offline造成四张截图拉取和显示恢复失败，原result.json与matrix日志保持失败。连接恢复后从原测试缓存取回六张图，已拉取的两张hash一致；独立 `recovered-evidence.json`记录补齐hash与恢复的1080×2280/1.0，没有重跑测试或覆盖原失败结果。API29完整矩阵与恢复直接通过。

当前源码宿主门禁见 `hxa147-files-host-result.json`：App Consumer315/Developer339，共654 JVM，零失败/错误/跳过；Spotless、Detekt、根lintDebug和lintRelease通过，1162项源码/配置hash在验证期间保持一致。i18n925键、文档277/任务131、ADR28、diff检查通过。此轮仍是文件控件/有限跨页面验收，浏览器被遮挡的清理入口、普通尺寸文件动作回归、长文件/路径与Provider说明文案继续待收口。

## 浏览器清理入口

NavigationLayoutDeviceTest补强三个清理按钮的完整视口边界检查，API36 Developer英文2倍字体红测明确失败于browser-clear-history不可见，见 `hxa147-browser-layout-red-en-api36-developer/`。清理按钮Row改FlowRow，原清理回调不变；roadmap按所有者统一界面优化授权补齐feature/browser UI模块边界，不涉及WebView执行或权限。

构建/Detekt通过 `hxa147-browser-layout-build.log`。320×640dp/2倍字体双API双发行包中英文八组合8/8通过，六截图/安装hash/语言断言及恢复记录见 `hxa147-browser-layout-matrix-api*-*-*/` 和matrix-api29/36.json。人工复核API29 Consumer英文browser.png，Clear cookies/cache/history均完整可见；测试只核对入口不点击清理，不将其当作清理后端或长稳验收。普通尺寸跨页面回归继续。

普通物理尺寸1080×2280/1.0倍字体的API29/36双发行包中英文跨页面回归8/8通过，见 `hxa147-navigation-normal-api*-*-*/`。包括Provider模型字段滚动/取消、文件原直接控件可见、进入work目录和浏览器三个清理入口边界；六截图及语言/安装hash记录齐全。人工复核API29 Consumer英文files-options.png，文件操作与input/output/work列表同屏且不重叠。两种尺寸合计16/16有限导航/布局流程，不代表文件修改/清理功能重新全测。i18n925键、文档/ADR/diff检查通过；完整HXA-147验收、Provider面向用户说明与最终真实模型关键流程继续。

## Provider 说明本地化

Provider表单此前直接展示catalog英文notes，中文界面仍出现英文及provider doc 2.5内部引用。App展示层为OpenAI、通用OpenAI兼容、Ollama、SGLang、OpenRouter、vLLM和LM Studio七类已有说明接入三语言资源，清晰描述地址、协议选择、连接测试和原HTTP主机准入；catalog配置和授权规则未改，未知模板保留其原说明。新增实际Ollama说明完整文案断言。

构建/Detekt通过 `hxa147-provider-copy-build.log`。首轮两台中文因测试预期漏掉“模板说明：”前缀而失败，原日志明确显示正确中文正文（`hxa147-provider-copy-matrix-api29-consumer-zh-CN/`及API36对应目录）；改为核对完整格式化文案，不放宽正文匹配。修正后的矩阵继续，未将首轮失败计作通过。

修正完整文案预期后，320×640dp/2倍字体双API双发行包中英文八组合8/8通过，见 `hxa147-provider-copy-fixed-matrix-api*-*-*/`。实际Ollama说明匹配当前Activity语言的完整资源文案，手工核对API29 Consumer中文provider-form.png确认内部引用已消失。其他六类说明由展示层资源映射和三语言932键检查覆盖，未声称逐个模板都打开实测。两台显示恢复、构建/Detekt（`hxa147-provider-copy-test-build.log`）及文档/ADR/diff检查通过。当前30008模型列表已重新核实为Qwen3.8-27B，继续当前APK真实Goal UI复验。

## 当前 APK 真实 Goal UI 刷新

使用重新核实的30008 SGLang/Qwen3.8-27B，API29/36 × Consumer/Developer四组均返回OK(1 test)，设备证据均为2次模型调用/2个run、PAUSED预算状态，创建不运行、显式Continue、界面扩展预算后再次显式Continue、最新回复完整可见且测试不滚动聊天均通过。目录 `hxa147-final-real-ui-api*-*/`保存当前APK安装hash、命令与设备证据。手工检查API29 Consumer conversation.png，最新回复和输入区可见。

API29两组主机汇总直接通过；API36两组因截图拉取不完整保留原result.json的passed=false，设备文件仍完整。随后只读取回原conversation/budget-paused图并核对设备result.json与首轮证据完全一致，已有conversation图hash一致，独立recovered-evidence.json记录补齐结果；没有重复模型调用。不推定本次传输不完整的根因。这四组不包含工具执行、Goal完成判定或全部恢复场景；本轮布局与普通GoalContinue证据不替代那些专项。

## 当前 APK 真实工具与 Goal 完成

安装核对首先发现两台旧PRoot Runtime hash与当前构建不一致，原 `hxa147-tool-completion-api29/36-run.log`保留；该轮在任何工具/模型测试前停止。随后安装当前App、测试与PRoot Runtime并逐个核对hash，运行当前界面接线：write使用Responses、edit使用Anthropic Messages、PRoot使用Chat Completions，各在API29/36各一次，共6/6通过。证据位于 `hxa147-tool-completion-current-api29/`、`hxa147-tool-completion-current-api36/`；每组命令、安装hash、instrumentation状态码、结果JSON与截图均齐全，readFailures均为空。

各组实际创建Goal并确认hash条件绑定，真实模型请求后在ChatScreen显式批准精确工具调用，原工具执行一次，制品内容/hash核验通过，Goal/run为COMPLETED，模型调用均2次。PRoot使用真实跨UID Runtime归档证据；人工核对API36 PRoot完成截图，已验证完成、条件1/1和禁用Continue可见。测试仅清理自身Goal/Provider/输出文件，不重放未知操作。

这六项是当前UI的三条工具路径与三种协议分配复验，不宣称再次执行了工具×协议全部18组合；完整协议交叉由前阶段独立功能矩阵证明。长文本/可访问性、受影响普通动作与M11边界回归、最终门禁和文档/Git收口继续。

## 长文本可访问性

新增SummaryAccessibilityDeviceTest，在240dp容器/2倍字体下分别构造中文与英文资源上下文，验证展开/收起的可访问名称和点击语义、实际48×48dp触摸区域、完整文本保留、语义滚动至全文末尾、返回收起后恢复原高度。与既有展开和聊天跟随/阅读位置测试在双API双发行包共28/28通过，见 `hxa147-summary-accessibility-touch-result.json`。本轮为组件语义/操作验收，不冒充实际TalkBack语音播报或真机操作。

首轮误把IconButton视觉边界作为触摸区域，2项失败（`hxa147-summary-accessibility-api29-consumer/`）；本地Compose 1.11.4公开API核对后改用assertTouchWidthIsEqualTo/assertTouchHeightIsEqualTo。中间曾引用非公开getTouchBoundsInRoot导致编译失败，已改正，日志保留。最终测试构建/Detekt见 `hxa147-summary-accessibility-touch-build2.log`，没有因视觉测量差异修改产品按钮。小屏长审批组件检查继续。

## 窄屏审批操作

新增ApprovalLayoutDeviceTest，240dp宽/2倍字体下分别使用中英文资源，检查长参数完整语义文本、滚动到批准/拒绝、视口边界与回调。首轮API29 Consumer英文明确失败于approval-deny-layout不可见，中文和既有四项审批测试通过（`hxa147-approval-layout-api29-consumer/`）。生产审批动作Row改FlowRow，并增加至少48dp可见宽度断言；不改审批字段、状态、授权或回调含义。

构建/Detekt通过 `hxa147-approval-layout-fixed-build.log`，三语言932键及文档/ADR/diff通过；最终设备组合结果随后记录。测试回调只计数，不执行工具或创建Approval Proof；真实审批管线仍由独立工具完成流程验证。

修复后双API双发行包24/24通过（每组中英文窄屏两例加既有审批四例），精确类列表、安装hash、命令与原始状态码见 `hxa147-approval-layout-fixed-result.json` 及对应四目录。两种语言均可滚动查看长参数，批准/拒绝分别完整落在视口内、宽度至少48dp，点击各触发对应回调一次；既有字段/状态/动作限制保持通过。整个HXA-147仍等待受影响动作/M11回归和最终门禁，未以组件回调替代真实授权管线。


## 最终受影响操作与宿主门禁

文件操作在独立临时API29/36模拟器、Consumer/Developer各10项，共40/40通过，包括列表、预览、重命名冲突、回收站、目录、分享与导入/导出入口。旧模拟器工作区未清空；测试后停止并删除仅本轮创建的临时AVD，环境记录保留。证据 `build/main-verification/hxa147-file-actions-result.json` 与各 `hxa147-file-actions-api*/environment.json`。M11完整订阅聊天边界API29/36各4项，共8/8通过，证据 `hxa147-subscription-chat-boundary-result.json`；这是隔离Runtime fixture，不冒充新增付费账号验收。

最终宿主执行34个JVM测试任务，2713项零失败/错误/跳过，1185项源码配置指纹在该运行期间不变；根Debug/Release Lint、Spotless、Detekt以及App双发行包和两个Runtime的Debug/Release构建通过。`hxa147-final-host-command.json` 保存完整命令，`hxa147-final-host-result.json` 保存各任务计数，`hxa147-final-host-audit.json`核对每个测试任务实际执行及8个生产APK SHA-256。Release产物未据此声称签名分发或真机安装验收。

恢复界面原矩阵API29正常/输出丢失、API36正常三组通过；API36输出丢失组在打开会话后断言读到null失败，保留 `hxa147-recovery-ui-api36-missing/instrumentation.log`。源码确认关闭会话会保留工具行以维护审批恢复，原测试只等待工具行存在，可能在异步会话刷新前返回。`openProotConversation`改为从同一screen快照同时等待目标会话ID及目标工具行，不修改生产导航/执行逻辑。首次abort遗漏host参数而被assumption跳过，日志末尾的OK不代表清理；新fixture被遗留标记阻止，未执行工具。补齐参数后原abort入口真实执行（status code 0），并从设备确认拥有的marker已删除，没有重放原Job；新的独立fixture复验另存目录。测试APK构建、Spotless与Detekt通过 `hxa147-recovery-nav-build.log`。这项测试源码修正晚于完整宿主指纹，完整JVM结果属于此前快照，生产源码与8个生产APK未因此更改。


API36输出丢失组最终复验通过1/1（status code 0，四次脚本模型请求、原Job只读恢复/精确确认、Goal/Turn/Call及预算保持不变），完成后设备marker不存在。证据 `hxa147-recovery-ui-api36-missing-nav-verified/`，四组汇总 `hxa147-recovery-ui-verified-result.json` 保留前三组旧测试APK与后一组修正测试APK的独立指纹；此前失败及跳过日志未改写。对完整宿主源码清单逐项比较，唯一差异为测试辅助函数 `ProotConversationNavigation.kt`，8个生产APK均不变（`hxa147-recovery-nav-source-delta.json`）。最终i18n932键、文档277文件/131任务、ADR28与diff检查通过；HXA-147完成记录及累计Git范围审核仍待收口。


最终全局收口：566个交付文件已逐路径审核并暂存，工作树/索引一致，缓存已忽略；文档、ADR及差异检查通过。此前段落中的阶段性Git待办由 [最终审计](main-closure-audit.md) 覆盖；未创建本地提交或推送。
