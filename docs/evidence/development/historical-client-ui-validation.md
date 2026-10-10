# 早期客户端交互与验证经验

整理：2026-10-10。以下为2026-09-10～10-01旧版报告的综合，不是当前功能规格或本轮测试结果。原报告已删除，来源与承接关系见[整理记录](documentation-convergence-2026-09-29.md#2026-10-10-旧版报告按主题承接)。当前行为以[状态](../../development/status.md)、有效ADR和源码为准。

## 会话模型与导航

2026-09-30：五种MANAGED_ACCOUNT统一“管理登录 → 管理对话模型 → 检查账号连接”；勾选整行可点击、单一复选框、忙碌时禁止修改。删除默认模型字段，旧default忽略且不再写入；新会话精确继承当前Provider/model，无绑定则为空，不按目录顺序找替代模型。真实服务返回的inputTokenLimit/maxInputTokens沿精确模型传递，缺少窗口字段时标注未报告，允许手动设置，不能将固定fallback冒充官方窗口。

历史验证为API36 arm64 Developer本地合成夹具6/6，首次10.066秒、最终复验10.388秒；覆盖入口顺序、勾选/忙碌状态、模型与推理选择、精确继承及空态。不是五个真实账号生成通过。初轮格式行过长修正后重跑；两渠道unit/lint、APK/AndroidTest、detekt、格式、source及diff检查通过。覆盖安装保留账号数据并恢复原语言设置。相关旧日志已在HXA-268退役。

2026-10-01后续：模型/连接成为独立一级入口，排在Work前，Recent删除；当前会话、新建及完整历史入口保留。模型候选管理/本地安装取消“应用到当前会话”回调，切换由会话选择器负责。该增量只有主机unit/lint/detekt/格式和设备测试编译，设备not requested；不能继承前一轮6/6。

## 工具详情与审批

2026-10-01先将参数、结果、调用ID、命令、诊断入口及intent完整移入展开区，收起仅保留工具名、状态和耗时；待审批卡仍直接可操作。状态不再通过缺少结果或本地化省略号猜测，Compose key与展开状态绑定Turn/Call身份，排序变化不丢状态。

初轮仅主机验证；随后所有者授权API36 Developer定向测试，ToolTimelineLayoutDeviceTest、ApprovalLayoutDeviceTest、ContextWindowDeviceTest共13/13、18.746秒。覆盖展开/收起、长内容、诊断可见性、320/360/412dp大字体、审批、圆环向下取整及0%。未调用真实模型或使用物理手机，测试后恢复语言配置。

同日标题再改为有效intent优先、缺省ToolPurpose，工具名称移入详情；状态/耗时/审批仍可见，标题最多三行、完整intent可展开。该增量最初仅主机验证，后来由[v0.0.4的21项定向回归](historical-provider-and-release-validation.md#v004发布与回放依赖)补验；之前13/13不能单独证明标题增量通过。

## 终端发现与准备

2026-10-01：Developer终端从Work折叠组移为独立入口；菜单主体可滚动、模式切换固定底部，Advanced保留风险确认，Consumer不显示不支持的升级按钮。终端页增加Runtime配置返回入口，未创建时说明安装/验证/启动流程。

现场服务连接验证成功仍报`Runtime requires preparation`，原因是RootFS未安装。通过既有安装页面准备后才成功启动；连接可达与环境准备必须分别验证。未删除会话/账号或改变授权。

历史API36 Developer：GroupedNavigationDeviceTest 1/1，短屏大字体全部入口可达且footer可见；实际Advanced→Standard→Advanced确认及Runtime往返/准备/启动成功。ProotTerminalUiDeviceTest#chineseInputReplAndRotationPreserveTheOriginalShell 1/1、7.877秒，包含真实shell中文输入输出、IME、Python REPL、旋转保留环境变量及停止结算，fixture清理自己的目录与终端。主机编译/lint/APK/AndroidTest/格式/detekt通过；无真实模型/账号调用，不代表其他设备或所有终端功能通过。

## 设置与审计窄屏布局

2026-09-10：多按钮区整颗换行，设置按功能分组；审计筛选与日期按宽度换行、长选项摘要省略但菜单可见。审计卡默认结果/工具/风险/本地时间，展开身份和详情。查询、过滤范围及脱敏白名单不变。DNS默认域名的国际化lint错误修正。

该轮与[账号检查变更](historical-provider-and-release-validation.md#账号连接与dns)共同完成33项host测试、两渠道与订阅APK、lint/国际化/ADR/文档/diff验证；未连接设备。真实字体缩放、许可/Root/无障碍按钮可达与审计视觉效果当时仍待人工检查，不补造设备结果。

## Goal输入与过期预算说明

2026-09-10：Goal任务直接在会话输入并发送，空文本拒绝；Provider、附件和外发披露通过后串行创建，Goal与首个run/Turn同事务持久化，失败不留孤立目标。长消息完整保存并发送，objective仅前1024字摘要。同一未终态Goal继续保持身份和账本，不能另建目标绕过阻塞；编辑默认值与编辑当前目标分开，编辑不隐式启动执行，损坏偏好不重置其他自定义项。

当时25项Goal/RunControlStore host测试和构建/lint/格式/国际化/文档/diff通过，设备仅编译；全局detekt仍有21处其他模块告警，不能称全门禁通过。附件取消、创建回滚、长消息、跨会话隔离等设备场景没有在那一轮执行。

旧报告中按累计token/调用/时长停止Goal的默认值和继续逻辑已经被2026-10-07 DSH契约撤销，不保留为操作指引。现行[Goal ADR](../../adr/goal/001-lifecycle-and-completion.md)和[预算对齐证据](dsh-budget-policy-2026-10-07.md)分别规定自动轮allowance与累计用量记账；[后续验证](dsh-budget-followup-2026-10-08.md)及HXA-266记录属于独立较新执行，不与上述25项合并。
