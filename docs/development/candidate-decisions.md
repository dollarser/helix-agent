# 候选需求与待裁决索引

本页只索引设计状态、已实现边界和进入开发的条件，不安排第二份实施计划。当前执行顺序以 [status](status.md) 为准，正式任务以 [roadmap](roadmap.md) 为准；需求正文留在产品需求，设计正文留在对应 ADR/方案。核对日期：2026-10-11。

## 状态如何解释

- **已授权任务**：按现有 HXA 执行，不重复请求立项；完成仍需任务验收。
- **已接受有限边界**：ADR accepted 只覆盖正文约定，不能推导未启用功能已获生产接线授权。
- **待裁决规范**：存在 proposed ADR，已有实现与规范整体接受是两个事实。
- **候选需求/目标设计/研究建议**：保留来源和进入条件，不分配未经接受的 HXA、不承诺交付日期。

ADR 状态计数不能代表产品剩余工作数量；Spike、host、device、账号和发行证据也不能互相替代。

## 已有实现、有限决定与正式任务

| 项目与来源 | 设计/任务状态 | 当前实现与缺口 | 进入下一步的条件 |
| --- | --- | --- | --- |
| [工具描述与审批契约](../adr/tools/001-descriptor-contract.md) | accepted，2026-09-30 所有者授权 R1 | contractHash、稳定实现身份、请求绑定与撤销规则已统一到同一 ADR | 实现验证见 HXA-231；不扩展成完整插件生命周期 |
| [R1 原子工具绑定](../completion-records/HXA-231.md) | 已交付；验证范围见完成记录 | 单一 binding 事实源、请求/调度/审批/执行绑定及全部来源迁移；后续授权的有界设备/服务证据已由原记录承接 | 未覆盖故障/OEM 按具体授权验收；Core/插件/Job 不自动启动，不在此复制测试进度 |
| [Project Memory](../adr/agent/013-markdown-memory.md) | Global/Project 有限契约及 HXA-246 本地交付 | 显式 Project 身份、会话关联、生产 resolver/权限接线及 API36 双渠道 UI、真正进程恢复通过 | HXA-251 补双API边界；真实模型质量/长稳独立，不能由 Workspace 路径推导 Project |
| [子 Agent / Workflow](../adr/agent/004-bounded-delegation.md) | 有界设计 accepted，不直接启用生产子 Agent | [HXA-105](../completion-records/HXA-105.md) 是隔离 Spike；没有因此获得生产 `agent.spawn` 或任意 Workflow DSL | 独立任务限定只读、父预算、取消、持久拓扑、最小上下文和实际收益 |
| [OAuth / Connector](tasks/HXA-125.md)、[动态注册](tasks/HXA-126.md)、[订阅](tasks/HXA-190.md) | 正式开放任务，不是未接受提案 | 已有实现基础；真实服务验证与剩余实现分别由任务记账 | 按各任务区分可实现部分和账号/服务输入；缺账号不等于全部实现完成 |
| [渠道审计](tasks/HXA-120.md)、[发行验收](tasks/HXA-121.md)、[身份与升级](tasks/HXA-122.md)、[提交](tasks/HXA-123.md) | 正式开放任务 | debug 预发布不替代签名、升级、真实渠道验收或商店提交 | 所需产品输入、签名/账号及对应发行验收；发布须另有明确授权 |

## 分阶段推进的目标设计

以下条目引用同一份[重构方案](../architecture/harness-refactor-plan.md)。2026-10-01 所有者已要求依次完成本地可执行的 R2-A、R3、J1/J2 与 Project Memory；各阶段以当前 status 和 HXA 为准，设备/账号/发行输入仍独立，不由方案详细程度自动扩展其他候选。

| 项目 | 已有基础 | 缺口与进入开发条件 |
| --- | --- | --- |
| R2 Core 解耦 / ContextCompiler | [HXA-234](tasks/HXA-234.md)：实际 Core Loop、中立执行/提交端口与纯上下文编译已有生产接线 | 当前主机/真实存储及设备边界见任务；R2-B 的策略优化不混入等价迁移 |
| R3＋最小 R5 插件统一生命周期 | [HXA-235](tasks/HXA-235.md)：统一包身份/目录、会话选择、局部失败、更新/停用/修复已接生产并完成本地主机验证 | 保留真实 Room/设备旅程，不重做已交付主体；安装仍不是审批 |
| 插件优化 A/C/E1/B/E2/D | 2026-10-11 Owner要求拆解并更新计划，HXA-272～279 已登记，未开工；源码复核与研究映射见[优化方案 §7](../research/topics/helix-plugin-architecture-optimization-2026-10-11.md#7-任务承接与验收) | 按 [status](status.md#插件优化队列2026-10-11) 的硬依赖推进；单一目标实现、不兼容旧版。F/G 及新增执行域继续按下方条件队列，不由登记任务推定功能交付 |
| J1 通用 Job 观察与等待 | [HXA-236](tasks/HXA-236.md)：原身份观察/等待、统一上下文及可信类型进展判断已接生产 | HXA-249 真实 Qwen J1、HXA-251 双API Job 六项通过；不扩大到全部生命周期/OEM |
| J2 同次执行前后台切换 | [HXA-248](tasks/HXA-248.md)：AUTO/USER 释放等待，保留同一原执行与 lease，本地已交付 | API36 Runtime/UI 八项及 HXA-249 真实 AUTO 通过；完整取消/恢复组合仍独立 |

## 产品已登记的候选与后续实施边界

需求正文与兼容性边界均在[产品需求 §2.4](../product/requirements.md#24-未排期未来能力候选)。本表保留登记身份；Shizuku 的后续有限实施不把其余候选变成当前交付承诺。

| ID | 方向 | 现有基础与主要进入条件 |
| --- | --- | --- |
| FUT-AUTO-001 | Tasker 互操作 | 复用受控动作入口；限定 action/event/state 和命名 Task/结果回传，不承诺任意 Profile 兼容 |
| FUT-AUTO-002 | Auto.js/AutoJs6 兼容 | 先做引擎/API/权限/模块兼容报告与支持矩阵，确定许可证和执行边界 |
| FUT-AUTO-003 | 独立脚本 Runtime | 独立应用/UID 与受控 IPC 方案；不借现有 QuickJS 开放特权桥 |
| FUT-SYS-001 | Shizuku Provider | HXA-243/244 已接 Mobile Use 授权及观察/动作；[平台边界](../architecture/android-platform-capabilities.md)和 status 承接有限实测。完整撤权/Binder death/OEM 仍独立，不再列为完全未实现 |
| FUT-SYS-002 | 无线 ADB | 用户配对、密钥与连接生命周期、供应链和断连恢复方案 |
| FUT-MEDIA-001 | PDF/Office 对话读取 | 已有附件与视觉通道不等于文档解析；先界定格式、来源、截断、恶意输入、资源和许可证 |
| FUT-MEDIA-002 | 视频理解 | 比较有界抽帧与原生 Provider 上传；确定时长/像素/音轨/成本/留存边界 |
| FUT-A2A-001 | 前台局域网 A2A Server | 已有 A2A Client；新增入站服务须明确身份、端口、锁屏/后台/重启关闭边界 |
| FUT-A2A-002 | 用户自备远程通道 | 明确 VPN/隧道/中继的身份和费用；通道在线不等于手机任务可执行 |
| FUT-A2A-003 | 远端到本机双向任务 | 首步只接结构化 proposal，父 Turn 经正常授权执行；不继承远端权限或审批 |

## 研究建议与后续增量

| 来源/项目 | 当前边界 | 进入条件 |
| --- | --- | --- |
| [Mobile Use 设备就绪、锁屏接续与可靠性](../research/topics/mobile-use-device-readiness-and-reliability-2026-09-29.md) | 已有受限语义自动化与 ui.wait；当前熄屏/锁定结束许可，不支持自动解开安全锁；旧评审项按 HXA-232 及当前源码复验，已授权目标自动恢复不再列为缺失 | 先验证并修复执行事实/等待反馈风险；锁屏后用户接续、有条件亮屏、新预算策略分别接受；HXA-232 已移除周期 checkpoint 确认，不重新列为待开发，不自动启用定时任务或无人值守打卡 |
| [Schedule / Channel 自动激活](../evidence/research-history/helix-agent-complete-research-and-product-plan.md) | Goal 连续执行/提醒已存在，不等于定时或外部事件创建任务 | 用户开启、事件身份、去重、错过执行、重启 disarm、权限与预算 |
| 同来源：可选 CompletionHook | 研究候选；不恢复普通 Goal 强制 verifier | 用户选择、确定性验收、超时/取消/记账、失败后可修复 |
| 同来源：QuickJS Code Mode | 已有隔离 JavaScript，不等于可组合特权工具 | 先证明收益，明确受控工具调用、跨 UID、嵌套等待、取消和预算 |
| [Workspace checkpoint / 按归属回滚](../research/modules/07-agent-capability-determinants-and-improvement-guide.md) | Workspace 已接受并交付；只读 Git 状态/文件恢复不等于 Agent rollback | 变更归属、并行用户修改、Git/SAF/外部效果差异及恢复验收；独立决定 |
| [Mobile Use 手机截图](../architecture/plugin-platform-plan.md#10-artifact--screenshot) | HXA-243 已接手机截图/原生手势，按实际后端就绪能力选择语义/视觉；实现见 status 与平台能力文档 | 受保护内容、有效期、资源和 OEM 限制继续成立，不由有限通过推导全设备可截图 |
| [移动入口与模板触发建议](../product/market-users-and-commercialization.md) | 有产品建议，未建立当前独立实施任务；语音输入已有交付 | 先定义用户任务与平台边界，再决定是否立项 |
| 审查材料中的可复用任务、TTS、小组件、唤醒词线索 | 本轮未找到这些条目的现行 Helix 实施决定；材料中的不可解析引用不能充当仓库依据，竞品有功能也不是 Helix 承诺 | 先恢复原始来源和需求范围；本行仅记录待核实线索，不登记为已接受需求 |

### 插件研究的条件性增量

以下承接[优化方案 §5.6–5.9、§6.5](../research/topics/helix-plugin-architecture-optimization-2026-10-11.md)，给出准备工作与进入实现的条件，不维护第二份排期。

| 建议 | 当前承接与准备步骤 | 进入实现的条件 / 边界 |
| --- | --- | --- |
| F：外部异步 Job provider | 复用 HXA-236/248 的原 Job、观察/等待及释放；先为一个明确服务整理 start/status/wait/collect/cancel/continue、幂等键、账号/endpoint、结果回执，准备断连/未知启动/重复收取的受控服务 fixture | 有实际消费者、固定协议和验证环境后创建具体 provider HXA，并更新原 Job/权限主题；未确认远端停止不能报取消成功，不另建任务框架 |
| G：一项文件工作流 | 沿用 FUT-MEDIA-001；先固定一个格式与用户任务，盘点双渠道执行依赖和许可证，定义输入/输出、结构检查、可用渲染、截断及资源上限；复用 Workspace/Artifact/view_image | 格式、执行环境与样例明确后创建对应 HXA；保存、可读、已渲染、业务正确分别验收，不由桌面 Skill 推定 Android 已有运行依赖 |
| MCP 服务端导出手机能力 | 现有 MCP client 只导入外部工具；先确定外部调用者、部署/认证、会话与目标绑定、撤销和原 Dispatcher 准入，准备拒绝/断连/未知效果协议样例 | 有跨宿主消费者且入口设计接受后再立项；不因 HXA-272～279 而把 Helix 变成远程设备平台 |
| Code Mode / 可执行 hooks / LSP / Agent 定义 | HXA-275 只做组件诊断；HXA-277 只做最小类型化生命周期通知。若需要执行，先确定具体任务、运行边界、取消/预算、嵌套调用及安全责任 | 按对应既有 ADR/候选单独确定范围；Skill 或 manifest 不创建运行时或执行权限 |
| Browser close / 完整 role-label 定位器 / 通用能力归组 | HXA-279 首版限定标签发现、有界等待和观察事实；HXA-274 保留真实提供方身份；先用冻结旅程记录剩余定位/选择困难 | 实际缺口与可测收益明确后单独拆任务；不按同名工具或 schema 自动合并，不扩建通用语义路由器 |
| 三种作者样例与 Chat Reply 复用 | 纯 Skill/native/MCP 样例已归 HXA-272/275/276；合成聊天草稿用于 HXA-272/273。独立 Chat Reply WIP 按原归属核对 | 样例随生产通路交付，不另建文档 HXA。长期监听、OCR 专用解析、自动发送、第二个 AgentLoop 不由样例自动进入范围 |

### 系统分层的条件性增量

来源：[2026-10-11 源码分层分析](../research/modules/01-architecture-and-execution-engine.md#9-2026-10-11-分层复核与优化承接)。HXA-274 已承接恢复投影/导航边界，HXA-277 已承接原生资源；以下只登记进入条件，不重复排期或预建新宿主。

| 建议 | 现有基础与准备步骤 | 进入实施与退出条件 |
| --- | --- | --- |
| 其他应用命令/读模型收窄 | 复用 AgentRuntime、HXA-257/258 的目录、composer、dashboard 和 SessionCatalog；按实际调用者列出必要命令、读模型、状态 owner 及门禁，不把 ChatService 所有方法搬入一个新 facade | 明确新入口/页面变更或有证据的耦合问题后并入对应原HXA；迁移实际调用者、删除被替换入口，保持单一提交身份/队列/Room/Engine。不以行数、空壳化或新增通用 event bus 验收 |
| 完整 Turn 宿主与物理模块提取 | Core Loop 已由 HXA-234 端口化，AppAgentRuntime 仍回调 ChatService，Engine 仍依赖 App/Room；先固定一个真实消费者和所需存储、Goal 联动、观察接口 | 消费者、部署范围、可测需求和原子结算/取消/恢复方案明确后决定是否单独HXA，沿用 ADR-AGENT-001；默认先最小同进程接口。未满足前不建设独立 Server/CLI/远程宿主，不生成第二份执行事实或旧接口兼容层 |

这些条件不会阻塞 HXA-272～279；当前默认顺序以 [status](status.md#系统分层优化承接2026-10-11)为准。候选设计允许评估模块提取，但不授权新增平台、设备操作或执行服务。

## 维护规则

状态变化时修改原需求/ADR/HXA，再更新本页对应链接和边界；完成后引用完成记录，不复制验收流水账。不得因 `accepted`、存在代码或 Spike 成功推导生产交付；不得从历史文档恢复过时阻塞。本轮事实与代码验证见[收敛证据](../evidence/development/contract-document-convergence-2026-09-29.md)。
