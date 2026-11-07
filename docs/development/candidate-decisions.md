# 候选需求与待裁决索引

本页只维护候选身份、已有基础与进入条件，不安排第二份实施计划。当前实施事实见 [status](status.md)，任务范围见 [roadmap](roadmap.md) 与各 HXA，设计决定归原主题 ADR；后续结构设计见 [Harness 第 24 节](../architecture/harness-refactor-plan.md#24-总体架构蓝图与渐进重构路线)。2026-11-08 定向核对：删除 10-16“286～294 均未实施”等过时判断；已交付基础、未实现功能、待验证范围与未授权候选分别记账。

## 状态如何解释

- **已交付基础**：只在原记录的实现与验证范围内成立，不因仍有后续候选重新立项。
- **正式任务的剩余范围**：按原任务区分未实现、仅待验证、服务输入及实验结论；历史任务存在不授予本次设备、账号或发布权限。
- **已接受有限边界**：ADR accepted 是约定范围的设计决定，不等于全部生产接线或验收完成。
- **候选需求/研究建议**：保留来源和进入条件，未获准的新产品或执行域不因结构基础完成而自动启动。

Spike、host、device、真实账号、模型消费和发行证据不能互相替代；Owner 结束某项测试不等于未测部分 passed。

## 已有实现、有限决定与正式任务

| 项目与来源 | 已有基础 | 剩余范围 / 决策边界 |
| --- | --- | --- |
| 工具绑定、Core、插件与 Job | [HXA-231](../completion-records/HXA-231.md)、[234](tasks/HXA-234.md)、[235](tasks/HXA-235.md)、[236](tasks/HXA-236.md)、[248](tasks/HXA-248.md)及 272～279 已有各自有界交付 | 不重排 R1/R2-A/R3/J1/J2；未覆盖设备/服务和后续能力按原任务，不复制历史验收流水 |
| Project Memory | [HXA-246](../completion-records/HXA-246.md)已有显式项目身份、会话关联与生产接线；[ADR-AGENT-013](../adr/agent/013-markdown-memory.md)约束全局/项目范围 | 原项目生命周期、双渠道 UI/Memory 与有界进程恢复已验收；自动写入、长上下文摘要保真率、其他设备及普遍模型质量未由此证明，不能从 Workspace 路径推导 Project |
| 子 Agent / Workflow | [ADR-AGENT-004](../adr/agent/004-bounded-delegation.md)有界设计 accepted；[HXA-105](../completion-records/HXA-105.md)是隔离 Spike | 未启用生产 `agent.spawn` 或任意 Workflow DSL；独立任务须限定委托/父预算、取消、持久拓扑、最小上下文与收益 |
| Connector / OAuth / 订阅 | [HXA-125](tasks/HXA-125.md)受保护服务验收、[126](tasks/HXA-126.md)OAuth 与客户端注册、[190](tasks/HXA-190.md)订阅各有已有实现 | Notion/Linear 既定测试已结案，不重开自然刷新等未验项；自有 HTTPS/App Link/签名等输入与其他服务范围仍分开。付费账号暂缓见 status，缺账号不等于全部实现完成 |
| 知识库、提醒、TTS 与定时 Agent | HXA-308～314 已有有界交付；[本机朗读](tasks/HXA-310.md)、[确认式提醒](../completion-records/HXA-313.md)、[最小定时 Agent](../completion-records/HXA-314.md)不是“尚无决定” | 定时首版为用户明确启用、绑定已有会话的 PLAN 只读分析/检索，不包含一般外部事件触发或任意副作用。真机听感、麦克风/耳机及服务端语音问题见[当前边界](status.md)，不由合成音频通过外推 |
| 渠道与发行 | [HXA-120](tasks/HXA-120.md)～[123](tasks/HXA-123.md)与[发行就绪](release-readiness.md)分别承接审计、验收、身份升级及提交 | 开发预览已有发布不等于正式签名、全部渠道或商店验收；不得把早期发行清单理解为产品从未发布，也不由本页授予发布权限 |

## 分阶段推进的目标设计

结构基础与功能队列分开；下表是状态导航，不是执行顺序。

| 范围 | 当前事实 | 后续边界 |
| --- | --- | --- |
| [HXA-286](../completion-records/HXA-286.md)、[293-A](../completion-records/HXA-293.md) | 类型化内容贡献与共同预算接缝已交付 | 不重建 Registry/Compiler；新贡献类型按实际消费者进入原契约 |
| [HXA-287](../completion-records/HXA-287.md) | A 应用命令、B round/展示接缝、C 完整中立宿主已交付，见[生命周期记录](../evidence/development/harness-stage1-lifecycle-2026-10-24.md) | 不是全部 App 已模块化；新增装配/输入/编译边界按蓝图 24 核对 |
| [HXA-288](tasks/HXA-288.md) | Job、物理容量、视觉产物、原位执行及结果/日志已有基础；[收尾裁决](../evidence/development/architecture-closeout.md)保留有实际职责的适配 | 仅有收益的局部接缝候选；显式 snapshot 跨来源 manifest 未交付，不作为默认原位可用的前置 |
| [HXA-289](tasks/HXA-289.md)～[292](tasks/HXA-292.md) | 文件 glob/字面量 grep 有界能力已交付；网页搜索/正文与 MCP Resources 正文闭环按 [290](tasks/HXA-290.md)/[291](tasks/HXA-291.md)仍待实施；Browser/Mobile Use 保留已交付动作基线 | Search provider/费用/配置只阻塞其实际接线；新增表单提交/选择/上传与已有动作的 OEM/生命周期待验分开，不是结构重构前置 |
| [HXA-293-B](../completion-records/HXA-293.md) | 有界 Memory/日志效果实验完成，未证明候选优于默认，见[实验记录](../evidence/development/memory-log-effect-experiment.md) | 保留默认策略；日志消费仍波动，不写作“尚未实验”或“优化已证明有效” |
| [HXA-294](../completion-records/HXA-294.md) | 已交付切片的 A/B（含 287-C）集成证据已存在 | 按实际改动验证；不外推未实现功能、全部 Runtime/OEM 或模型效果，也不为无改动的 288 制造验收包 |
| [总体蓝图 24](../architecture/harness-refactor-plan.md#241-结论基线与范围) | 11-07 已交付显式依赖、ConversationComposition 装配、输入契约/视图、上下文事实与映射分离，以及 conversation-input 编译边界；Debug 主机通过 | 整个 conversation 表现/应用层模块化、Room 模块迁移等未完成；该轮设备行为仅编译未执行。不将局部通过泛称蓝图完成，也不重复原 Engine 提取 |

## 产品已登记的候选与后续实施边界

需求身份与兼容性边界见[产品需求 第 2.4 节](../product/requirements.md#24-未排期未来能力候选)。已有有限交付不扩大其余候选范围。

| ID | 方向 | 已有基础与进入条件 |
| --- | --- | --- |
| FUT-AUTO-001 | Tasker 互操作 | 复用受控动作入口；限定 action/event/state、命名 Task 与结果回传，不承诺任意 Profile 兼容 |
| FUT-AUTO-002 | Auto.js/AutoJs6 兼容 | 先确定引擎/API/权限/模块支持矩阵、许可证及执行边界 |
| FUT-AUTO-003 | 独立脚本 Runtime | 先明确独立应用/UID 与受控 IPC 方案；不借现有 QuickJS 开放特权桥 |
| FUT-SYS-001 | Shizuku Provider | HXA-243/244 已接 Mobile Use 授权、观察与动作；[平台边界](../architecture/android-platform-capabilities.md)与 status 承接后续实测，完整 OEM/撤权/恢复组合不外推 |
| FUT-SYS-002 | 无线 ADB | [电脑 ADB helper](computer-adb-helper.md)已有有限实现，不等于手机内无线配对；无线方向仍须配对、密钥、连接生命周期、供应链及断连恢复方案 |
| FUT-MEDIA-001 | PDF/Office 对话读取 | HXA-283/284 的统一 PDF/DOCX 阅读已有交付；[现行 PDF 契约](../adr/workspace/006-pdf-reading.md)与 status 承接页引用和扫描 PDF 视觉路径。其他 Office 格式、编辑/生成、专门 OCR 与完整排版不随阅读基础启用 |
| FUT-MEDIA-002 | 视频理解 | 比较有界抽帧与原生 Provider 上传，确定时长/像素/音轨/成本/留存边界 |
| FUT-A2A-001 | 前台局域网 A2A Server | 已有 A2A Client；入站服务须明确身份、端口、锁屏/后台/重启关闭边界 |
| FUT-A2A-002 | 用户自备远程通道 | 明确 VPN/隧道/中继身份和费用；通道在线不等于手机任务可执行 |
| FUT-A2A-003 | 远端到本机双向任务 | 首步只接结构化 proposal，父 Turn 经正常授权执行，不继承远端权限或审批 |

## 研究建议与后续增量

| 来源 / 方向 | 已有基础与候选边界 | 进入条件 |
| --- | --- | --- |
| [Mobile Use 就绪与可靠性](../research/topics/mobile-use-device-readiness-and-reliability-2026-09-29.md) | 按现行[权限 ADR](../adr/permissions/001-session-authorization.md)区分会话授权与运行状态；锁屏/断连/进程死亡不自动撤权，恢复不能复用旧观察/动作凭据 | 新增亮屏/无人值守策略单独限定范围；不恢复旧周期确认或自动撤权，不支持自动解开安全锁 |
| [Schedule / Channel](../evidence/research-history/helix-agent-complete-research-and-product-plan.md) | 最小定时已由 HXA-314 交付；一般外部事件、新任务自动创建和更广执行资格仍是增量 | 明确用户开启、事件身份/去重、错过执行、恢复、权限与预算；沿现行 Goal ADR，不沿用研究中的旧重启策略 |
| 同来源：CompletionHook | 可选候选，不恢复普通 Goal 强制 verifier | 用户选择、确定性验收、超时/取消/记账与失败后修复 |
| [Workspace checkpoint / 按归属回滚](../research/modules/07-agent-capability-determinants-and-improvement-guide.md) | 现有 Workspace、文件恢复与应用备份不等于 Agent rollback | 先裁决变更归属、并行用户修改、Git/SAF/外部效果差异及恢复验收 |
| [移动入口与模板触发](../product/market-users-and-commercialization.md) | 语音输入/TTS/定时已有各自实现；可复用任务、小组件、唤醒词未由此成为获准功能 | 先恢复具体来源、定义用户任务和平台边界，再决定是否立项；不把竞品线索或不可解析引用当仓库决定 |

### 插件研究的条件性增量

承接[插件优化方案 第 5.6～5.9、6.5 节](../research/topics/helix-plugin-architecture-optimization-2026-10-11.md)，不重复 272～279 的已交付主体。

| 建议 | 进入条件与保留边界 |
| --- | --- |
| F：外部异步 Job provider | 选定实际服务/消费者、协议及验证环境，再确定 start/status/wait/collect/cancel/continue、幂等、费用和回执；复用原 Job。未确认远端停止不能报取消成功，不另建任务框架 |
| G：文件工作流增量 | 不重新选择已交付 PDF/DOCX 首版格式；新增格式、编辑/生成或 OCR 须明确任务、双渠道依赖/许可证、来源与产物、资源上限。保存、可读、渲染和业务正确分别验收 |
| MCP 服务端导出手机能力 | 有跨宿主消费者后，明确部署/认证、会话目标绑定、撤销与原 Dispatcher 准入；现有 MCP client 不替代服务端设计，不随插件管理成为远程设备平台 |
| Code Mode / 可执行 hooks / LSP / Agent 定义 | 现有隔离 JavaScript、组件诊断和最小类型化生命周期通知不是可组合特权工具或执行授权；逐项证明消费者/收益，确定运行、取消、预算、嵌套调用及安全责任 |
| Browser close / 完整 role-label 定位器 / 通用能力归组 | 已有标签发现、有界等待与观察事实；用冻结旅程证明定位/选择缺口后再拆任务，不按同名工具/schema 合并提供方，不扩建通用语义路由器 |
| 独立聊天辅助产品 | 既有纯 Skill/native/MCP 样例和合成聊天草稿不授权长期监听、OCR 专门解析、自动发送或第二 AgentLoop。正式承接时选择目标 App/会话、手动草稿/确认发送/规则触发边界；独立 Chat Reply WIP 按原归属核对 |
| 包内 CLI/脚本及账号服务 | 组件诊断和 Advanced 底座不代表导入即 setup/登录；按 [第 23.12 节](../architecture/harness-refactor-plan.md#2312-插件管理与提示审查接续)明确目标包、许可证、ABI/解释器/依赖、Runtime、凭据 owner 与用户范围。同 UID 不是秘密隔离 |
| 常驻 stdio MCP | 有界 JSONL/Job 不是双向常驻会话；先确定 server/依赖/认证、协商/背压/取消/真实退出夹具与实际缺少的资源接缝。不重做已交付 286/288 基础，不扩容 HXA-291 或重复启动 Job 模拟持久连接 |

### 系统分层的条件性增量

完整无页面 Turn 已由 HXA-287 交付，不再等待第二产品消费者，也不再标“已排期未实施”。当前结构接续统一见[总体蓝图 24](../architecture/harness-refactor-plan.md#24-总体架构蓝图与渐进重构路线)，按实际耦合和消费者收敛应用装配、输入与编译边界。

**新部署宿主仍需独立需求**：CLI、独立 Server、远程 Agent、多设备及新增执行域须先确定真实消费者、身份/授权、部署与恢复责任。领域分离和可扩展接缝不构成这些平台的实施授权。

## 本轮插件与分层评审的决策结论

保留本标题供研究页引用；不再抄录 2026-10-11 的整套历史决定。接入/安装归属见 [ADR-CONNECTORS-003](../adr/connectors/003-ownership-and-installation.md)，内容与调用资格见 [ADR-SKILLS-001](../adr/skills/001-authoring-and-installation.md)，执行/持久边界见 [ADR-AGENT-001](../adr/agent/001-turn-coordination.md)。既有决定不因本次整理重新待裁决。

后续需要 Owner 选择的独有问题已集中于上方候选表：独立聊天辅助范围、新部署宿主、手机能力导出、首个外部异步 provider、文件增量及可编程扩展。条件未满足不要求现在逐项回答；竞品具备功能不自动形成 Helix 优先级。

新增收益对照先固定模型/版本、任务样本、设备/API/渠道、成功判据和成本取舍；历史有界通过不外推，缺服务输入不阻塞无依赖的实现。

## 2026-10-15 工具与插件评审承接

[竞品研究](../research/topics/tool-and-plugin-competitive-review-2026-10-15.md)中已登记的 286～294 按上方状态表和原任务维护，不再沿用当时统一“未实施”的基线。新功能与结构重构分开；既定 Notion/Linear 测试已结案而非等待重启，长稳/黑屏等范围以 status 与当前明确授权为准。

### 后续扩展的准备与立项边界

持久终端、子 Agent、hooks、LSP 等结构约束见[第 23.9 节](../architecture/harness-refactor-plan.md#239-后续扩展约束)。接缝和当前反例验证不自动启用模型写入 PTY、子任务调度、脚本或语言服务器；后续分别确定输入/接管与租期、父子权限/恢复、事件决策语义与平台依赖，不重新立项已有基础。

## 资料检索、展示与输入增量

此节保留 status 的链接入口，区分已有接线与验证边界，不把依赖少作为目标。

| 方向 | 已有实现 | 剩余边界 |
| --- | --- | --- |
| 知识库语义/混合检索 | 内置账号嵌入目录/请求、明确所选资料索引、本机可重建向量缓存及 RRF/词法融合，复用知识引用工具并重核正文 | 默认关闭，须明确所选文本/查询外发；修订/权限隔离、删除失效与离线词法降级保持。不自动上传整库，不部署向量数据库；同名服务模型更新需显式修订或重建，不与 Project Memory 合库 |
| 公式、Mermaid 与 SVG | [公式 ADR](../adr/platform/006-formula-preview.md)及 status 承接 KaTeX 离线块级/行内、Mermaid Tiny 和受限静态 SVG 预览；保留源码及有界缓存 | 行内每段最多八项；超限/未闭合/不支持图种回退源码，不执行外部资源、图内链接或自定义配置。不承诺全部图种、所有 WebView 或硬实时中止 |
| 连续语音/插话 | 加号“连续转写”复用 Silero VAD 分段，停顿后追加可编辑草稿，不自动发送；保留单次输入，开始前等待朗读物理退出 | 分段/队列饱和/迟到隔离/释放须随变更回归；合成与播放控制结果不等于真实麦克风/耳机或声学回声验收，不宣称全双工 |
| SAF 自动同步 | 显式双向规则、共同基线/实际 hash、立即/周期同步、暂停与冲突状态，复用传输恢复 | 删除不传播；双方修改/类型/撤权/中断暂停，周期至少 15 分钟且非准点。已有 SAF 单项实测不等于完整真实目录同步验收，跨设备需重新授权绑定 |

具体通过/失败和候选范围见 [status](status.md)、[非真机客户端验收](../evidence/development/nondevice-client-acceptance.md)及[日常体验收尾](usability-closeout.md)；登记本身不构成新增验收。

## 维护规则

状态变化先更新原需求/ADR/HXA，再更新本页链接和条件；完成后引用原记录，不复制测试流水。历史决定从 Git 与原主题 Decision history 追溯，不在索引保留第二套合同或旧阻塞。文档整理不改变授权，不把未实现、未验证或被暂停的部分写成完成。
