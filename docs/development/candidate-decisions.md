# 候选需求与待裁决索引

本页只索引设计状态、已实现边界和进入开发的条件，不安排第二份实施计划。当前执行顺序以 [status](status.md) 为准，正式任务以 [roadmap](roadmap.md) 为准；需求正文留在产品需求，设计正文留在对应 ADR/方案。核对日期：2026-09-30。

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
| [R1 原子工具绑定](../completion-records/HXA-231.md) | 已交付（主机范围） | 单一 binding 事实源、请求/调度/审批/执行绑定及全部来源迁移通过主机门禁 | 明确授权后有界设备/服务回归；后续 Core/插件/Job 不自动启动 |
| [Project Memory](../adr/agent/013-markdown-memory.md) | Global 首版及隔离边界 accepted；完整 Project 接线未启用 | Global 已交付，Project API/隔离测试存在；生产 `MemoryService` 默认 resolver 返回 null | 显式 Project 身份、会话关联、请求冻结与权限隔离方案及生产验收任务；不能用 Workspace 路径自动推导 Project |
| [子 Agent / Workflow](../adr/agent/004-bounded-delegation.md) | 有界设计 accepted，不直接启用生产子 Agent | [HXA-105](../completion-records/HXA-105.md) 是隔离 Spike；没有因此获得生产 `agent.spawn` 或任意 Workflow DSL | 独立任务限定只读、父预算、取消、持久拓扑、最小上下文和实际收益 |
| [OAuth / Connector](tasks/HXA-125.md)、[动态注册](tasks/HXA-126.md)、[订阅](tasks/HXA-190.md) | 正式开放任务，不是未接受提案 | 已有实现基础；真实服务验证与剩余实现分别由任务记账 | 按各任务区分可实现部分和账号/服务输入；缺账号不等于全部实现完成 |
| [渠道审计](tasks/HXA-120.md)、[发行验收](tasks/HXA-121.md)、[身份与升级](tasks/HXA-122.md)、[提交](tasks/HXA-123.md) | 正式开放任务 | debug 预发布不替代签名、升级、真实渠道验收或商店提交 | 所需产品输入、签名/账号及对应发行验收；发布须另有明确授权 |

## 尚未转为当前实施任务的目标设计

以下条目引用同一份[重构方案](../architecture/harness-refactor-plan.md)，不把其详细程度当成已授权范围。

| 项目 | 已有基础 | 缺口与进入开发条件 |
| --- | --- | --- |
| R2 Core 解耦 / ContextCompiler | AgentLoop、上下文装配和请求清单 | 定义并迁移中立端口，验证现有行为等价后再调上下文策略；独立任务与相关 ADR 边界 |
| R3＋最小 R5 插件统一生命周期 | Plugin / Mobile Use MVP、Connector/MCP/Skill 各自生命周期 | 包身份、组件归属、会话选择、局部失败、更新卸载和修复闭环；明确哪些旧 owner 删除 |
| J1 通用 Job 观察与等待 | Linux 后台 Job、status/cancel/collect | 通用 Handle/Observation、`jobs.await`、通知及有界等待；明确取消、预算、失效与恢复后再接受任务 |
| J2 同次执行前后台切换 | 已有后台启动与 durable Job | 稳定执行身份、等待方式与存活策略分离、手动继续后台；证明不重启/不重复执行，明确 J1 等实际依赖 |

## 产品已登记、未排期的十项候选

需求正文与兼容性边界均在[产品需求 §2.4](../product/requirements.md#24-未排期未来能力候选)。这些是已记录的未来方向，不是被否决，也不是当前交付承诺。

| ID | 方向 | 现有基础与主要进入条件 |
| --- | --- | --- |
| FUT-AUTO-001 | Tasker 互操作 | 复用受控动作入口；限定 action/event/state 和命名 Task/结果回传，不承诺任意 Profile 兼容 |
| FUT-AUTO-002 | Auto.js/AutoJs6 兼容 | 先做引擎/API/权限/模块兼容报告与支持矩阵，确定许可证和执行边界 |
| FUT-AUTO-003 | 独立脚本 Runtime | 独立应用/UID 与受控 IPC 方案；不借现有 QuickJS 开放特权桥 |
| FUT-SYS-001 | Shizuku Provider | 显式服务授权、断开/撤权/Binder death/OEM 验收；接入正常 Tool Policy |
| FUT-SYS-002 | 无线 ADB | 用户配对、密钥与连接生命周期、供应链和断连恢复方案 |
| FUT-MEDIA-001 | PDF/Office 对话读取 | 已有附件与视觉通道不等于文档解析；先界定格式、来源、截断、恶意输入、资源和许可证 |
| FUT-MEDIA-002 | 视频理解 | 比较有界抽帧与原生 Provider 上传；确定时长/像素/音轨/成本/留存边界 |
| FUT-A2A-001 | 前台局域网 A2A Server | 已有 A2A Client；新增入站服务须明确身份、端口、锁屏/后台/重启关闭边界 |
| FUT-A2A-002 | 用户自备远程通道 | 明确 VPN/隧道/中继的身份和费用；通道在线不等于手机任务可执行 |
| FUT-A2A-003 | 远端到本机双向任务 | 首步只接结构化 proposal，父 Turn 经正常授权执行；不继承远端权限或审批 |

## 研究建议与后续增量

| 来源/项目 | 当前边界 | 进入条件 |
| --- | --- | --- |
| [Mobile Use 设备就绪、锁屏接续与可靠性](../research/topics/mobile-use-device-readiness-and-reliability-2026-09-29.md) | 已有受限语义自动化与 ui.wait；当前熄屏/锁定结束许可，不支持自动解开安全锁；动作失败分类、观察和启动反馈存在本次评审项 | 先验证并修复执行事实/等待反馈风险；锁屏后用户接续、有条件亮屏、预算/checkpoint 调整分别接受，不自动启用定时任务或无人值守打卡 |
| [Schedule / Channel 自动激活](../evidence/research-history/helix-agent-complete-research-and-product-plan.md) | Goal 连续执行/提醒已存在，不等于定时或外部事件创建任务 | 用户开启、事件身份、去重、错过执行、重启 disarm、权限与预算 |
| 同来源：可选 CompletionHook | 研究候选；不恢复普通 Goal 强制 verifier | 用户选择、确定性验收、超时/取消/记账、失败后可修复 |
| 同来源：QuickJS Code Mode | 已有隔离 JavaScript，不等于可组合特权工具 | 先证明收益，明确受控工具调用、跨 UID、嵌套等待、取消和预算 |
| [Workspace checkpoint / 按归属回滚](../research/modules/07-agent-capability-determinants-and-improvement-guide.md) | Workspace 已接受并交付；只读 Git 状态/文件恢复不等于 Agent rollback | 变更归属、并行用户修改、Git/SAF/外部效果差异及恢复验收；独立决定 |
| [Mobile Use 手机截图](../architecture/plugin-platform-plan.md#10-artifact--screenshot) | 当前以 Accessibility 语义树为主；[视觉回填契约](../adr/agent/011-tool-multimodal-vision-feedback.md) 提供已有图片/浏览器截图的视觉回填，不获取手机屏幕 | 明确截图授权、受保护内容、有效期、图片产物和资源边界；复用现有视觉通道 |
| [移动入口与模板触发建议](../product/market-users-and-commercialization.md) | 有产品建议，未建立当前独立实施任务；语音输入已有交付 | 先定义用户任务与平台边界，再决定是否立项 |
| 审查材料中的可复用任务、TTS、小组件、唤醒词线索 | 本轮未找到这些条目的现行 Helix 实施决定；材料中的不可解析引用不能充当仓库依据，竞品有功能也不是 Helix 承诺 | 先恢复原始来源和需求范围；本行仅记录待核实线索，不登记为已接受需求 |

## 维护规则

状态变化时修改原需求/ADR/HXA，再更新本页对应链接和边界；完成后引用完成记录，不复制验收流水账。不得因 `accepted`、存在代码或 Spike 成功推导生产交付；不得从历史文档恢复过时阻塞。本轮事实与代码验证见[收敛证据](../evidence/development/contract-document-convergence-2026-09-29.md)。
