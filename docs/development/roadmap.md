# 开发任务索引


任务范围与验收只在未完成任务文件维护；已完成任务只链接交付证据。编号不是强制执行顺序，不补造空号。当前优先级见[实施状态](status.md)，通用规则见[实施指南](implementation-guide.md)及[验收规则](verification-matrix.md)。

`tasks/` 保存正式开放、已排期和保留验收边界的 HXA，具体状态看下表与[实施状态](status.md)；不维护固定开放数量。已交付能力通过完成记录追溯，不因历史摘要重开。内测与发行的依赖/退出条件见[发行就绪条件](release-readiness.md)。

2026-10-01 [阶段收尾](../evidence/development/phase-closeout-2026-10-01.md)的历史范围保留；236 的后续统一观察上下文与可信进展接线已由[后续证据](../evidence/development/hxa236-context-progress-2026-10-01.md)承接，126/233/234/235/236/237 保留收尾验收。开放数量不等于仍缺实现的功能数量，主机/编译不替代设备、真实模型或发行验收；未创建整体完成记录或文档 HXA。

候选需求和目标设计不混入正式实现任务；见[候选需求与待裁决索引](candidate-decisions.md)。

2026-10-11 所有者要求依据插件调研与当前代码登记 **HXA-272～279**，按单一目标实现设计，不兼容旧版。272～279已有界交付；279测试AVD中文拼音环境失败及其他未测范围保留；已交付事实集中在 [status](status.md#已交付与历史证据)，研究与任务映射见[优化方案](../research/topics/helix-plugin-architecture-optimization-2026-10-11.md#7-任务承接与验收)。本次不新增远程设备服务、任意 hooks 或未选定格式的文档运行时。

同日系统分层分析已并入[现有执行引擎研究](../research/modules/01-architecture-and-execution-engine.md#9-2026-10-11-分层复核与优化承接)：HXA-274 追加恢复导航的应用/表现边界，HXA-277 追加跨层资源 owner 与退出事实接线；其余复用原任务或登记条件项，不新增编号。历史交付见 [status](status.md#已交付与历史证据)，不重启已交付 Core/Engine/会话状态重构。

## 能力交付取舍

所有者新增授权 [HXA-213](../completion-records/HXA-213.md) 已交付：按消息创建会话分支，复用持久历史、压缩和附件，不新增 Agent 工具；独立于上述历史未闭合义务。

遵循[能力规划规则](implementation-guide.md#应用能力与-agent-工具的规划规则)：191搜索/主题、204恢复、205准备是应用功能；195实时输出是宿主协议与展示；207复用扩展与统一工具管线；206验收这些边界。任务数量和默认顺序不因本次规则增加或重排，已授权并行切片仍按其所有权执行。

后续业务扩展优先完善MCP/Connector/Skill接入与现有通用工具；新增原生工具必须有模型主动调用的具体需求。JSONL 已获所有者实施授权并独立立项 HXA-211，首版为用户主动导出；生产子Agent、通用hooks与新编排框架不因对标而自动进入批次B/C。同Turn转向归216，ADR-AGENT-001已接受且本地实现验收完成，不回填旧批次为已交付。此取舍不撤销已接受设计，也不关闭既有验收义务。

工具发现与请求工作集已有 HXA-280/281 交付；新的策略比较见[工具研究](../research/modules/04-tools-browser-and-extensions.md)，按新增范围测量和评审，不重新立项已完成主体。
ADR-PROVIDER-001 已接受本地模型作为一等 `ModelProvider`，可驱动完整 Agent loop；HXA-222 首版已完成本地交付，真实模型及资源验收边界见完成记录，当前顺序以 status 为准。

2026-10-15 Owner另行授权的 `helix-docs` 内置帮助切片不新增HXA编号，复用Skills资源读取与工具发现；当前阶段及版本/设备边界见 [status](status.md) 和[设计实现记录](../research/topics/helix-docs-design-2026-10-14.md)。


## 架构收敛回顾（2026-09-26）

本节保留当轮交付范围；跨阶段变化见[演进索引](../evidence/research-history/architecture-evolution-2026-10-14.md)。旧“冻结/不重启”表示不重复已完成工作，不限制新获授权的接口、模块或持久边界重构。

HXA-220 已完成并见[交付证据](../completion-records/HXA-220.md)：TurnEngine durable/live owner、successor recovery、AgentLoop driver/observation 与 legacy serial state cleanup 已通过完整 host gate。

HXA-221 已完成并见[交付证据](../completion-records/HXA-221.md)：当轮建立唯一 v1 / 45-table clean-slate baseline，删除旧内部 migration/Connector/Provider/Criteria/Chat 兼容路径；外部协议与 Android API 支持范围独立。该表数量与清库决定按当时版本理解，不是当前 schema 或下一轮数据处理授权。

HXA-223 已完成并见[交付证据](../completion-records/HXA-223.md)：R4 stale-state CAS、Engine terminal/review/recovery owner、Session next-work scheduler 与 SessionInput delivery application boundary 已收口，Core Engine clean-slate 重构进入冻结状态。设备内本地模型 [HXA-222](../completion-records/HXA-222.md) 已完成首版本地交付；ADR-WORKSPACE-004 已于 2026-09-27 获所有者接受，HXA-210 已完成本地验收。

同阶段 UI、Conversation-first、活动展示、评测、Workspace、Memory 与本地模型的交付分别见下方 HXA-210/222/226～230；后续 Project Memory 由 HXA-246 承接。HXA-225 的自主读图/工具视觉回填已主机交付，其他层级验证按原记录。跨轮状态以 status 为准，不保留当时“Project 尚未启用”或“下一步只做发行”的限制。

## 执行顺序与依赖

2026-10-16 [HXA-295](tasks/HXA-295.md) A 已交付，301 已补有界 Room/UI 与 Standard R8/真实模型证据；B/C 的受控跨会话列表、创建/消息投递仍未实施，承接 286/287 对应切片，纳入 294 已交付范围冻结验收。预览后规划复核见 [Harness §23](../architecture/harness-refactor-plan.md#23-2026-10-14-执行边界重构提案)，各项优先级和未知输入归原任务；不新增重复总计划，不自动启动重构。

2026-10-15 评审已登记 HXA-286～294，完整架构队列均未实施；303 的独立发现配套子集不代表 286 完成。v0.0.6 发布先于生产重构，已记录的设计依赖见 [status](status.md#后续架构队列未启动)。286 前置身份、四个完成条件、取消/reconcile/清理和上下文正确性契约；其后 287-A 完整 Turn 最小纵切与 288-A 执行核心分别推进、共同集成，再扩展持久宿主和领域适配，不设整任务循环依赖。293 策略后置，294 汇总各阶段已积累证据。范围与具体退出条件在各任务维护，不把原条件候选或历史完成记录直接改成新能力已交付。

2026-10-17 [目录/执行环境研究](../research/topics/tool-and-plugin-competitive-review-2026-10-15.md#11-2026-10-17-目录工作副本与执行环境)已补入既有任务及 [Harness §23.7.3](../architecture/harness-refactor-plan.md#2373-目录优化与工作副本迁移检查点)：近期信息/选择器与后续身份、映射、发布/回写分开验收。仅更新规划，不新增 HXA、不改变当前顺序或默认 Job 模式。

当前任务接续对话进度；[status](status.md) 与 HXA 提供事实和契约。下表仅保留已交付产品批次的溯源，不是待执行步骤；未闭合验收由各原任务继续记录。

| 历史批次 | 当时承接任务 | 当时交付目标 |
| --- | --- | --- |
| A：看懂任务、拿到结果 | **202 → 194 → 203** | 会话进入对应任务，准确显示等待/取消/结束，打开命令详情及任务产物，并能返回来源；194交付时补验202入口 |
| B：能恢复、能准备 | **204 → 205**；193已收尾 | 区分重连/对账/继续/重试，新用户完成准备，初始化失败可修复；193提供干净资产、升级及CI证据 |
| C：过程可见、扩展可用 | **207 → 191（已全部交付）**（195已交付） | 长命令结束前可见真实输出；扩展完成添加到实际调用；会话搜索与深色主题可用 |
| D：核心路径验收 | **206** | 汇总A～C、已交付192/209及自身验收项，不等待完整终端 |

批次 A～D 以及 HXA-196～199 的后台 Job/PTY/终端集成已有各自完成记录；不再执行旧“提前准备 206”或“后续整理 198”的指令。原阶段取舍见[09-16 复核](../evidence/development/open-hxa-review-2026-09-16.md)。真实订阅/OAuth 服务及 HXA-120～123 的发行条件继续由各任务和[发行就绪条件](release-readiness.md)维护；历史设备/账号授权不延续到新任务，构建成功不能代替实际服务或发行验收。

## HXA 清单

| 任务 | 分类 | 范围 | 规格或证据 |
| --- | --- | --- | --- |
| HXA-210 | 已交付 | 独立会话目录、外部资源绑定与生命周期 | [交付证据](../completion-records/HXA-210.md) |
| HXA-001 | 已交付 | Gradle 多模块工程 | [交付证据](../completion-records/M0.md) |
| HXA-002 | 已交付 | 质量和供应链门禁 | [交付证据](../completion-records/M0.md) |
| HXA-003 | 已交付 | AppContainer 与导航壳 | [交付证据](../completion-records/M0.md) |
| HXA-010 | 已交付 | 领域 ID、错误和执行目标 | [交付证据](../completion-records/HXA-010.md) |
| HXA-011 | 已交付 | Turn reducer | [交付证据](../completion-records/HXA-011.md) |
| HXA-012 | 已交付 | PlanArtifact 与模式策略 | [交付证据](../completion-records/HXA-012.md) |
| HXA-013 | 已交付 | Goal reducer 与预算 | [交付证据](../completion-records/HXA-013.md) |
| HXA-014 | 已交付 | Room schema 与 Repository | [交付证据](../completion-records/HXA-014.md) |
| HXA-015 | 已交付 | 恢复协调器 | [交付证据](../completion-records/HXA-015.md) |
| HXA-016 | 已交付 | Context Builder | [交付证据](../completion-records/HXA-016.md) |
| HXA-020 | 已交付 | SecretStore 与 Provider 配置 | [交付证据](../completion-records/HXA-020.md) |
| HXA-021 | 已交付 | 内部 ModelRequest/ModelEvent | [交付证据](../completion-records/HXA-021.md) |
| HXA-022 | 已交付 | OpenAI Responses adapter | [交付证据](../completion-records/HXA-022.md) |
| HXA-023 | 已交付 | OpenAI Chat Completions adapter | [交付证据](../completion-records/HXA-023.md) |
| HXA-024 | 已交付 | Anthropic Messages adapter | [交付证据](../completion-records/HXA-024.md) |
| HXA-025 | 已交付 | Provider 连接与能力探测 | [交付证据](../completion-records/HXA-025.md) |
| HXA-026 | 已交付 | 模板目录 | [交付证据](../completion-records/HXA-026.md) |
| HXA-027 | 已交付 | 自建服务真机 smoke | [交付证据](../completion-records/HXA-027.md) |
| HXA-028 | 已交付 | 聊天 UI | [交付证据](../completion-records/HXA-028.md) |
| HXA-030 | 已交付 | ToolDescriptor、Registry 和 ToolSource | [交付证据](../completion-records/HXA-030.md) |
| HXA-031 | 已交付 | JSON Schema 子集 | [交付证据](../completion-records/HXA-031.md) |
| HXA-032 | 已交付 | Capability Center | [交付证据](../completion-records/HXA-032.md) |
| HXA-033 | 已交付 | Policy Engine | [交付证据](../completion-records/HXA-033.md) |
| HXA-034 | 已交付 | Approval hash 与一次性消费 | [交付证据](../completion-records/HXA-034.md) |
| HXA-035 | 已交付 | Dispatcher 与审计 | [交付证据](../completion-records/HXA-035.md) |
| HXA-036 | 已交付 | Timeline 和审批卡 | [交付证据](../completion-records/HXA-036.md) |
| HXA-037 | 已交付 | 确定性 Tool Scheduler 与交互 receipt | [交付证据](../completion-records/HXA-037.md) |
| HXA-038 | 已交付 | 模型流状态合同与 ChatService 第一阶段拆分 | [交付证据](../completion-records/HXA-038.md) |
| HXA-039 | 已交付 | 批量语义 Turn Coordinator | [交付证据](../completion-records/HXA-039.md) |
| HXA-040 | 已交付 | WorkspacePath 和 FileScopePath | [交付证据](../completion-records/HXA-040.md) |
| HXA-041 | 已交付 | Artifact、配额和原子文件操作 | [交付证据](../completion-records/HXA-041.md) |
| HXA-042 | 已交付 | Pi 风格基础工具 | [交付证据](../completion-records/HXA-042.md) |
| HXA-043 | 已交付 | Copy/Move/Delete/Trash | [交付证据](../completion-records/HXA-043.md) |
| HXA-044 | 已交付 | SAF adapter | [交付证据](../completion-records/HXA-044.md) |
| HXA-045 | 已交付 | All files access | [交付证据](../completion-records/HXA-045.md) |
| HXA-046 | 已交付 | 文件管理 UI | [交付证据](../completion-records/HXA-046.md) |
| HXA-047 | 已交付 | Archive 工具 | [交付证据](../completion-records/HXA-047.md) |
| HXA-048 | 已交付 | 全项目审查后续收敛 | [交付证据](../completion-records/HXA-048.md) |
| HXA-049 | 已交付 | 会话附件导入、持久化与文本输入 | [交付证据](../completion-records/HXA-049.md) |
| HXA-050 | 已交付 | Zipline Spike | [交付证据](../completion-records/HXA-050.md) |
| HXA-051 | 已交付 | isolated Service/Binder | [交付证据](../completion-records/HXA-051.md) |
| HXA-052 | 已交付 | JS ABI 与限制 | [交付证据](../completion-records/HXA-052.md) |
| HXA-053 | 已交付 | `code.javascript.run` | [交付证据](../completion-records/HXA-053.md) |
| HXA-054 | 已交付 | 攻击和端到端测试 | [交付证据](../completion-records/HXA-054.md) |
| HXA-055 | 已交付 | 图片输入与 Provider 视觉能力 | [交付证据](../completion-records/HXA-055.md) |
| HXA-056 | 已交付 | 文本/图片附件端到端硬化与发布边界 | [交付证据](../completion-records/HXA-056.md) |
| HXA-057 | 已交付 | persisted SAF tree scope 接线 | [交付证据](../completion-records/HXA-057.md) |
| HXA-058 | 已交付 | 文件管理器导入/导出入口 | [交付证据](../completion-records/HXA-058.md) |
| HXA-059 | 已交付 | Provider 模型自动发现与选择 | [交付证据](../completion-records/HXA-059.md) |
| HXA-060 | 已交付 | 最小 WebView 浏览器 | [交付证据](../completion-records/HXA-060.md) |
| HXA-061 | 已交付 | Browser snapshot | [交付证据](../completion-records/HXA-061.md) |
| HXA-062 | 已交付 | Browser actions | [交付证据](../completion-records/HXA-062.md) |
| HXA-063 | 已交付 | Browser download | [交付证据](../completion-records/HXA-063.md) |
| HXA-064 | 已交付 | 分享、Intent 和剪贴板 | [交付证据](../completion-records/HXA-064.md) |
| HXA-065 | 已交付 | 通知和日历 | [交付证据](../completion-records/HXA-065.md) |
| HXA-066 | 已交付 | HTTP fetch 与前台任务 | [交付证据](../completion-records/HXA-066.md) |
| HXA-067 | 已交付 | 语音输入 | [交付证据](../completion-records/HXA-067.md) |
| HXA-068 | 已交付 | Advanced 有界出网规则管理 | [交付证据](../completion-records/HXA-068.md) |
| HXA-069 | 已交付 | 国际化与 App 语言切换 | [交付证据](../completion-records/HXA-069.md) |
| HXA-070 | 已交付 | MCP Kotlin SDK Android Spike | [交付证据](../completion-records/HXA-070.md) |
| HXA-071 | 已交付 | MCP Server 配置和握手 | [交付证据](../completion-records/HXA-071.md) |
| HXA-072 | 已交付 | MCP 动态 Tool bridge | [交付证据](../completion-records/HXA-072.md) |
| HXA-073 | 已交付 | MCP stdio bridge | [交付证据](../completion-records/HXA-073.md) |
| HXA-074 | 已交付 | Skill validator 和 catalog | [交付证据](../completion-records/HXA-074.md) |
| HXA-075 | 已交付 | Skill 导入和快照 | [交付证据](../completion-records/HXA-075.md) |
| HXA-076 | 已交付 | Skill 工具与首批内置 Skills | [交付证据](../completion-records/HXA-076.md) |
| HXA-077 | 已交付 | A2A v1.0 Android Client Spike | [交付证据](../completion-records/HXA-077.md) |
| HXA-078 | 已交付 | A2A Agent 配置、发现与快照 | [交付证据](../completion-records/HXA-078.md) |
| HXA-079 | 已交付 | A2A Task Tool bridge 与恢复 | [交付证据](../completion-records/HXA-079.md) |
| HXA-080 | 已交付 | Runtime manifest/许可证 schema | [交付证据](../completion-records/HXA-080.md) |
| HXA-081 | 已交付 | 构建期固定资产 | [交付证据](../completion-records/HXA-081.md) |
| HXA-082 | 已交付 | RootFS installer | [交付证据](../completion-records/HXA-082.md) |
| HXA-083 | 已交付 | 独立 Runtime APK/IPC | [交付证据](../completion-records/HXA-083.md) |
| HXA-084 | 已交付 | PRoot runner | [交付证据](../completion-records/HXA-084.md) |
| HXA-085 | 已交付 | `bash` Tool | [交付证据](../completion-records/HXA-085.md) |
| HXA-086 | 已交付 | 真机 smoke 与隔离 | [交付证据](../completion-records/HXA-086.md) |
| HXA-087 | 已交付 | 更新/卸载/法律页 | [交付证据](../completion-records/HXA-087.md) |
| HXA-088 | 已交付 | Git Workspace 语义 Spike 与 ADR | [交付证据](../completion-records/HXA-088.md) |
| HXA-090 | 已交付 | Accessibility Service 与权限中心 | [交付证据](../completion-records/HXA-090.md) |
| HXA-091 | 已交付 | UI snapshot/token | [交付证据](../completion-records/HXA-091.md) |
| HXA-092 | 已交付 | UI actions | [交付证据](../completion-records/HXA-092.md) |
| HXA-093 | 已交付 | Accessibility 攻击/恢复测试 | [交付证据](../completion-records/HXA-093.md) |
| HXA-094 | 已交付 | Root 依赖与授权生命周期验收 | [交付证据](../completion-records/HXA-094.md) |
| HXA-095 | 已交付 | Root 高层工具作用域与失权验收 | [交付证据](../completion-records/HXA-095.md) |
| HXA-096 | 已交付 | Root L3 控制台 | [交付证据](../completion-records/HXA-096.md) |
| HXA-097 | 已交付 | Android UI Skill | [交付证据](../completion-records/HXA-097.md) |
| HXA-099 | 已交付 | 模式、预算与资源降级运行控制 | [交付证据](../completion-records/HXA-099.md) |
| HXA-100 | 已交付 | 固定评测集 | [交付证据](../completion-records/HXA-100.md) |
| HXA-101 | 已交付 | Prompt injection | [交付证据](../completion-records/HXA-101.md) |
| HXA-102 | 已交付 | 恢复和副作用 | [交付证据](../completion-records/HXA-102.md) |
| HXA-103 | 已交付 | 资源/稳定性 | [交付证据](../completion-records/HXA-103.md) |
| HXA-104 | 已交付 | 隐私/诊断/删除 | [交付证据](../completion-records/HXA-104.md) |
| HXA-105 | 已交付 | 有界只读委托与声明式 Workflow Spike | [交付证据](../completion-records/HXA-105.md) |
| HXA-110 | 已交付 | CLI Runtime manifest 与独立 UID | [交付证据](../completion-records/HXA-110.md) |
| HXA-111 | 已交付 | Codex app-server 登录 Spike | [交付证据](../completion-records/HXA-111.md) |
| HXA-112 | 已交付 | Claude Code stream-json/SDK Spike | [交付证据](../completion-records/HXA-112.md) |
| HXA-113 | 已交付 | 工具与审批兼容性结论 | [交付证据](../completion-records/HXA-113.md) |
| HXA-114 | 已交付 | DSH subscription adapter 来源、凭据与条款 Spike | [交付证据](../completion-records/HXA-114.md) |
| HXA-115 | 已交付 | 独立 Runtime 凭据 vault | [交付证据](../completion-records/HXA-115.md) |
| HXA-116 | 已交付 | 订阅平台目录与 0.7.0 供应链重基线 | [交付证据](../completion-records/HXA-116.md) |
| HXA-117 | 已交付 | GitHub Copilot 官方 SDK/OAuth App Android Spike | [交付证据](../completion-records/HXA-117.md) |
| HXA-118 | 已交付 | Codex 第三方侧载 OAuth 登录生命周期 | [交付证据](../completion-records/HXA-118.md) |
| HXA-119 | 已交付 | GitHub Copilot Free 第三方侧载 Device Code 登录生命周期 | [交付证据](../completion-records/HXA-119.md) |
| HXA-137 | 已交付 | Claude Free 身份登录与 Claude Code 订阅资格门禁 | [交付证据](../completion-records/HXA-137.md) |
| HXA-138 | 已交付 | Grok Free 官方 Device Code 登录与套餐门禁 | [交付证据](../completion-records/HXA-138.md) |
| HXA-139 | 已交付 | Device Code 可用性、诊断与 Codex 官方兼容流 | [交付证据](../completion-records/HXA-139.md) |
| HXA-140 | 已交付 | Codex 订阅最小模型 Smoke 与实验边界收口 | [交付证据](../completion-records/HXA-140.md) |
| HXA-141 | 已交付 | Runtime 持久 Codex 模型 Job 内核 | [交付证据](../completion-records/HXA-141.md) |
| HXA-142 | 已交付 | 订阅协议实验停止线与注册门禁 | [交付证据](../completion-records/HXA-142.md) |
| HXA-143 | 已交付 | Developer 订阅 Provider 渠道门禁修正 | [交付证据](../completion-records/HXA-143.md) |
| HXA-131 | 已交付 | CLI Runtime 共享握手契约 | [交付证据](../completion-records/HXA-131.md) |
| HXA-132 | 已交付 | 主 App 冷绑定与状态握手 | [交付证据](../completion-records/HXA-132.md) |
| HXA-133 | 已交付 | 固定 Codex 跨 APK 持久 Job 控制面 | [交付证据](../completion-records/HXA-133.md) |
| HXA-134 | 已交付 | 有界模型载荷与对账删除 | [交付证据](../completion-records/HXA-134.md) |
| HXA-135 | 已交付 | Developer 对话 Provider 接入 | [交付证据](../completion-records/HXA-135.md) |
| HXA-136 | 已交付 | 订阅登录入口与真实对话验收 | [交付证据](../completion-records/HXA-136.md) |
| HXA-144 | 已交付 | Claude 订阅 Provider 与显式平台 Job 路由 | [交付证据](../completion-records/HXA-144.md) |
| HXA-145 | 已交付 | Grok 订阅 Provider | [交付证据](../completion-records/HXA-145.md) |
| HXA-146 | 已交付 | Copilot 订阅 Provider | [交付证据](../completion-records/HXA-146.md) |
| HXA-120 | 发行队列 | 渠道能力与最终制品审计 | [任务规格](tasks/HXA-120.md) |
| HXA-121 | 发行队列 | 候选发布包综合验收 | [任务规格](tasks/HXA-121.md) |
| HXA-122 | 发行队列 | 发行身份、签名与升级路径 | [任务规格](tasks/HXA-122.md) |
| HXA-123 | 发行队列 | 选定渠道提交准备与审核证据 | [任务规格](tasks/HXA-123.md) |
| HXA-124 | 已交付 | Connector 调研、插件导入与管理 | [交付证据](../completion-records/HXA-124.md) |
| HXA-125 | 条件性范围；Notion/Linear子范围已结案 | 其他Connector受保护服务与来源验收仅按另行选定范围推进 | [任务规格](tasks/HXA-125.md) |
| HXA-126 | 本地实现已交付；Notion/Linear真实服务子范围已结案 | 自有HTTPS/App Link与正式签名为独立发行条件；不重开已关闭账号测试 | [任务规格](tasks/HXA-126.md) |
| HXA-127 | 已交付 | 大 catalog 渐进工具发现 | [交付证据](../completion-records/HXA-127.md) |
| HXA-128 | 已交付 | CLI/stdio Connector 可移植性 Spike | [交付证据](../completion-records/HXA-128.md) |
| HXA-129 | 已交付 | Connector 安全替换、安装归属与会话启停 | [交付证据](../completion-records/HXA-129.md) |
| HXA-130 | 已交付 | Connector 签名索引与来源验证 | [交付证据](../completion-records/HXA-130.md) |
| HXA-147 | 已交付 | 任务交互与移动界面统一 | [交付证据](../completion-records/HXA-147.md) |
| HXA-148 | 已交付 | Skill Creator 与草稿校验 | [交付证据](../completion-records/HXA-148.md) |
| HXA-149 | 已交付 | Skill Installer 与精确内容安装 | [交付证据](../completion-records/HXA-149.md) |
| HXA-150 | 已交付 | MCP Installer 与 Connector 导入接线 | [交付证据](../completion-records/HXA-150.md) |
| HXA-151 | 已交付 | Skill/MCP 原生操作状态收敛 | [交付证据](../completion-records/HXA-151.md) |
| HXA-152 | 已交付 | 浏览器原生引用短时归因 | [交付证据](../completion-records/HXA-152.md) |
| HXA-153 | 已交付 | JNI 引用与 Binder 代理释放追踪 | [交付证据](../completion-records/HXA-153.md) |
| HXA-154 | 已交付 | 浏览器生产路径引用核实 | [交付证据](../completion-records/HXA-154.md) |
| HXA-155 | 已交付 | 浏览器网络取消与 Activity 生命周期 | [交付证据](../completion-records/HXA-155.md) |
| HXA-156 | 已交付 | 状态与产品研究文档收口 | [交付证据](../completion-records/HXA-156.md) |
| HXA-157 | 已交付 | 工具发现被模型数量上限截断修复 | [交付证据](../completion-records/HXA-157.md) |
| HXA-158 | 已交付 | 未使用 WebView 宿主延迟分配与原生问题折中验证 | [交付证据](../completion-records/HXA-158.md) |
| HXA-159 | 已交付 | 大文件职责重构与浏览器 Context 评估 | [交付证据](../completion-records/HXA-159.md) |
| HXA-160 | 已交付 | Activity 级浏览器宿主与 Context 功能验证 | [交付证据](../completion-records/HXA-160.md) |
| HXA-161 | 已交付 | 真机会话输入区与推理参数接线 | [交付证据](../completion-records/HXA-161.md) |
| HXA-162 | 已交付 | 会话时间线与复制 | [交付证据](../completion-records/HXA-162.md) |
| HXA-163 | 已交付 | 草稿会话与目录归属 | [交付证据](../completion-records/HXA-163.md) |
| HXA-164 | 已交付 | 即时消息发布与 Markdown | [交付证据](../completion-records/HXA-164.md) |
| HXA-165 | 已交付 | 参考移动对话界面的视觉整理 | [交付证据](../completion-records/HXA-165.md) |
| HXA-166 | 已交付 | 会话内模型选择 | [交付证据](../completion-records/HXA-166.md) |
| HXA-167 | 已交付 | 单行横向选项栏 | [交付证据](../completion-records/HXA-167.md) |
| HXA-168 | 已交付 | 顶部空间与胶囊选项 | [交付证据](../completion-records/HXA-168.md) |
| HXA-169 | 已交付 | 顶部主菜单与标题编辑 | [交付证据](../completion-records/HXA-169.md) |
| HXA-170 | 已交付 | 导航样式与扩展入口修复 | [交付证据](../completion-records/HXA-170.md) |
| HXA-171 | 已交付 | 独立文件管理界面 | [交付证据](../completion-records/HXA-171.md) |
| HXA-172 | 已交付 | 归档列表、共享存储入口与上下文用量 | [交付证据](../completion-records/HXA-172.md) |
| HXA-173 | 已交付 | 归档恢复与共享根目录 | [交付证据](../completion-records/HXA-173.md) |
| HXA-174 | 已交付 | 窗口配置和上下文压缩 | [交付证据](../completion-records/HXA-174.md) |
| HXA-175 | 已交付 | 调试脚本留存、紧凑会话控件与系统栏避让 | [交付证据](../completion-records/HXA-175.md) |
| HXA-176 | 已交付 | 长 Turn / Goal 压缩实战强化 | [交付证据](../completion-records/HXA-176.md) |
| HXA-177 | 已交付 | 后台任务与 Goal 阻塞恢复 | [交付证据](../completion-records/HXA-177.md) |
| HXA-178 | 已交付 | 模型判断 Goal 完成 | [交付证据](../completion-records/HXA-178.md) |
| HXA-179 | 已交付 | ChatService 职责拆分 | [交付证据](../completion-records/HXA-179.md) |
| HXA-180 | 已交付 | 独立文件管理变更能力 | [交付证据](../completion-records/HXA-180.md) |
| HXA-181 | 已交付 | 当前规范清理 | [交付证据](../completion-records/HXA-181.md) |
| HXA-182 | 已交付 | 大类职责继续收敛与文件传输恢复 | [交付证据](../completion-records/HXA-182.md) |
| HXA-183 | 已交付 | 大类职责拆分 | [交付证据](../completion-records/HXA-183.md) |
| HXA-184 | 已交付 | 谨慎提取与文件装配整理 | [交付证据](../completion-records/HXA-184.md) |
| HXA-185 | 已交付 | 合并后长稳夹具适配与失败取证 | [交付证据](../completion-records/HXA-185.md) |
| HXA-186 | 已交付 | API35 真机隔离回归 | [交付证据](../completion-records/HXA-186.md) |
| HXA-187 | 已交付 | QuickJS 真机超时停滞修复 | [交付证据](../completion-records/HXA-187.md) |
| HXA-188 | 已交付 | 真机浏览器冻结、后台恢复与 Root 验证 | [交付证据](../completion-records/HXA-188.md) |
| HXA-189 | 已交付 | 第三轮审查复核与缺陷修复 | [交付证据](../completion-records/HXA-189.md) |
| HXA-190 | 收尾验收 | 订阅 Provider 能力与真实账号验收 | [任务规格](tasks/HXA-190.md) |
| HXA-191 | 已交付 | 深色主题与会话搜索 | [交付证据](../completion-records/HXA-191.md) |
| HXA-192 | 已交付 | Plan 审阅到执行的用户闭环验收 | [交付证据](../completion-records/HXA-192.md) |
| HXA-193 | 已交付 | 包内 Runtime 可复现资产与升级验收 | [交付证据](../completion-records/HXA-193.md) |
| HXA-194 | 已交付 | 命令详情与现有结果导航 | [交付证据](../completion-records/HXA-194.md) |
| HXA-195 | 已交付 | 有界日志协议与实时输出 | [交付证据](../completion-records/HXA-195.md) |
| HXA-196 | 已交付 | 有租期的独立后台命令 Job | [交付证据](../completion-records/HXA-196.md) |
| HXA-197 | 已交付 | 单个手动 PTY 终端 | [交付证据](../completion-records/HXA-197.md) |
| HXA-198 | 已交付 | 手动终端多会话与重连 | [交付证据](../completion-records/HXA-198.md) |
| HXA-199 | 已交付 | 终端专项集成与交付 | [交付证据](../completion-records/HXA-199.md) |
| HXA-200 | 已交付 | 三态审批偏好与执行解析 | [交付证据](../completion-records/HXA-200.md) |
| HXA-201 | 已交付 | 工具设置与审批卡 | [交付证据](../completion-records/HXA-201.md) |
| HXA-202 | 已交付 | 任务过程与跨页面操作导航 | [交付证据](../completion-records/HXA-202.md) |
| HXA-203 | 已交付 | 产物可用性与文件交付闭环 | [交付证据](../completion-records/HXA-203.md) |
| HXA-204 | 已交付 | 跨执行域错误与恢复交互 | [交付证据](../completion-records/HXA-204.md) |
| HXA-205 | 已交付 | 首次配置与能力准备修复 | [交付证据](../completion-records/HXA-205.md) |
| HXA-206 | 已交付 | 核心产品闭环综合验收 | [交付证据](../completion-records/HXA-206.md) |
| HXA-207 | 已交付 | 现有扩展来源的添加到使用闭环 | [交付证据](../completion-records/HXA-207.md) |
| HXA-208 | 已交付 | 完整 Goal 工具与前后台连续运行 | [交付证据](../completion-records/HXA-208.md) |
| HXA-209 | 已交付 | 会话授权预设、工具禁用与自定义权限 | [交付证据](../completion-records/HXA-209.md) |
| HXA-211 | 已交付 | 按会话导出 JSONL 执行记录 | [交付证据](../completion-records/HXA-211.md) |
| HXA-212 | 已交付 | 内置扩展市场与典型目录 | [交付证据](../completion-records/HXA-212.md) |
| HXA-213 | 已交付 | 按消息创建会话分支 | [交付证据](../completion-records/HXA-213.md) |
| HXA-214 | 已交付 | 发送回执、草稿保留与停止一致性 | [交付证据](../completion-records/HXA-214.md) |
| HXA-215 | 已交付 | 最新用户消息在会话内修订重发 | [交付证据](../completion-records/HXA-215.md) |
| HXA-216 | 已交付 | 普通 Turn 与 Goal 统一排队和转向 | [交付证据](../completion-records/HXA-216.md) |
| HXA-217 | 已交付 | 轻量请求来源记录与 JSONL 可追踪性 | [交付证据](../completion-records/HXA-217.md) |
| HXA-218 | 已交付 | 会话工作台 UI 与交互重构（第一批） | [交付证据](../completion-records/HXA-218.md) |
| HXA-219 | 已交付 | 产物就地预览 | [交付证据](../completion-records/HXA-219.md) |
| HXA-220 | 已交付 | Core Engine / TurnEngine 生命周期收敛 | [交付证据](../completion-records/HXA-220.md) |
| HXA-221 | 已交付 | Pre-release clean-slate baseline cleanup | [交付证据](../completion-records/HXA-221.md) |
| HXA-222 | 已交付（主机与 API36 定向） | 设备内本地模型一等 Provider | [完成记录](../completion-records/HXA-222.md) |
| HXA-223 | 已交付 | Post-clean-slate Core Boundary Convergence | [交付证据](../completion-records/HXA-223.md) |
| HXA-225 | 已交付（主机范围） | Agent 自主图片读取与工具视觉回填 | [完成记录](../completion-records/HXA-225.md) |
| HXA-226 | 已交付 | UI / IA 第二轮收敛 | [交付证据](../completion-records/HXA-226.md) |
| HXA-227 | 已交付（主机 + 设备补验） | 统一轨迹级 Agent Eval 与失败归因 | [交付证据](../completion-records/HXA-227.md) |
| HXA-228 | 已交付 | Conversation-first Shell 与 Session Context Control | [交付证据](../completion-records/HXA-228.md) |
| HXA-229 | 已交付 | Model-authored Agent Activity Presentation | [交付证据](../completion-records/HXA-229.md) |
| HXA-230 | 已交付（主机范围） | Markdown-native Hierarchical Agent Memory | [完成记录](../completion-records/HXA-230.md) |
| HXA-231 | 已交付（验证范围见记录） | 问题收口与原子工具绑定 | [完成记录](../completion-records/HXA-231.md) |
| HXA-232 | 进行中 | 自主恢复与最小人工介入 | [任务规格](tasks/HXA-232.md) |
| HXA-233 | 收尾验收 | 订阅回放依赖生命周期与有界清理 | [任务规格](tasks/HXA-233.md) |
| HXA-234 | 收尾验收 | R2-A 中立 Core 与上下文等价迁移 | [任务规格](tasks/HXA-234.md) |
| HXA-235 | 收尾验收 | R3 插件安装、组件归属与会话生命周期 | [任务规格](tasks/HXA-235.md) |
| HXA-236 | 收尾验收 | J1 原执行身份观察与有界等待 | [任务规格](tasks/HXA-236.md) |
| HXA-237 | 收尾验收 | 订阅引导、Codex 客户端版本与移动终端体验 | [任务规格](tasks/HXA-237.md) |
| HXA-238 | 收尾验收 | 执行锁收窄与 Provider 表单反馈 | [任务规格](tasks/HXA-238.md) |
| HXA-239 | 收尾验收 | 输入框选模、指令光标与权限快捷切换 | [任务规格](tasks/HXA-239.md) |
| HXA-240 | 收尾验收 | Advanced Bash / Linux Job 复用 FFmpeg | [任务规格](tasks/HXA-240.md) |
| HXA-241 | 收尾验收 | 历史模拟器扫描与当前主线缺陷复核 | [任务规格](tasks/HXA-241.md) |
| HXA-242 | 收尾验收 | HTTP 仅提示、滚动/输入反馈与自动化体验 | [任务规格](tasks/HXA-242.md) |
| HXA-243 | 收尾验收 | Mobile Use 全机许可、原生手势/截图与结果收敛 | [任务规格](tasks/HXA-243.md) |
| HXA-244 | 收尾验收 | Conversation 持久授权、应用选择与屏幕共享 | [任务规格](tasks/HXA-244.md) |
| HXA-245 | 收尾验收 | Mobile Use 插件半透明穿透悬浮交互 | [任务规格](tasks/HXA-245.md) |
| HXA-246 | 收尾验收 | 本机项目与跨会话工作组织 | [任务规格](tasks/HXA-246.md) |
| HXA-247 | 收尾验收 | 文件发现、会话交接与成果定位 | [任务规格](tasks/HXA-247.md) |
| HXA-248 | 本地交付；模型边界开放 | J2 同执行 AUTO 与用户后台释放 | [任务规格](tasks/HXA-248.md) |
| HXA-249 | 本地交付；真实 J1/AUTO 通过 | 实际模型多 system 消息协议兼容 | [任务规格](tasks/HXA-249.md) |
| HXA-250 | 本地交付；API36 模型返回/停留 2/2 | Mobile Use 完成任务后返回原对话 | [任务规格](tasks/HXA-250.md) |
| HXA-251 | 本地有限补验已交付 | 重构余项设备/模型对照与状态核对 | [任务规格](tasks/HXA-251.md) |
| HXA-252 | 已交付；API36 有界模型通过 | 通用工具发现提示与多任务对照 | [完成记录](../completion-records/HXA-252.md) |
| HXA-253 | 已交付；151 项 host 通过 | 主机测试统一发现与漏测保护 | [完成记录](../completion-records/HXA-253.md) |
| HXA-254 | 已交付；主机及 API36 双渠道各8项通过 | 项目会话实时读模型 | [完成记录](../completion-records/HXA-254.md) |
| HXA-255 | 已交付；主机/API36双渠道通过 | 权限页面外发规则应用接口 | [完成记录](../completion-records/HXA-255.md) |
| HXA-256 | 本地主机/API36有界交付 | 测试生命周期夹具迁移与生产桥清理 | [完成记录](../completion-records/HXA-256.md) |
| HXA-257 | 本地主机/API36有界交付 | 会话目录、输入与任务投影职责 | [完成记录](../completion-records/HXA-257.md) |
| HXA-258 | 本地主机/API36有界交付 | 会话元数据事务职责 | [完成记录](../completion-records/HXA-258.md) |
| HXA-259 | 主机交付；设备not requested | 项目与模型页面功能归属 | [完成记录](../completion-records/HXA-259.md) |
| HXA-260 | 主机交付；设备not requested | 历史入口与复杂边界维护 | [完成记录](../completion-records/HXA-260.md) |
| HXA-261 | 有界本地交付 | 有界会话性能对照 | [完成记录](../completion-records/HXA-261.md) |
| HXA-262 | 有界本地交付 | 页面迁移与真实模型连续压缩补验 | [完成记录](../completion-records/HXA-262.md) |
| HXA-263 | 有界本地交付 | 自有设备测试日志完整采集 | [完成记录](../completion-records/HXA-263.md) |
| HXA-264 | 有界本地交付 | 停止与恢复组合本地收尾 | [完成记录](../completion-records/HXA-264.md) |
| HXA-265 | 有界本地交付 | 双渠道有界资源稳定性收尾 | [完成记录](../completion-records/HXA-265.md) |
| HXA-266 | 有界本地交付 | 当前Goal契约与真实模型旅程收尾 | [完成记录](../completion-records/HXA-266.md) |
| HXA-267 | 主机交付；设备not requested | 门禁与历史调试脚本维护 | [完成记录](../completion-records/HXA-267.md) |
| HXA-268 | 主机交付；设备not requested | 历史版本脚本与日志退役 | [完成记录](../completion-records/HXA-268.md) |
| HXA-269 | 主线程修复交付；2026-10-12按Owner规则关闭黑屏问题 | 真机后台返回黑屏诊断、主线程修复与Qwen回归；其他后端补验独立保留 | [任务](tasks/HXA-269.md) |
| HXA-271 | 会话/浮窗/Qwen及日常API36有界交付；授权恢复、链接说明修正、阶段9先说明再设置的主机/source/制品通过，新UI夹具仅编译；提示交互、后端恢复执行、模型新说明遵循率及其他设备边界开放 | 会话执行交互与信息层级 | [任务](tasks/HXA-271.md) |
| HXA-270 | 实现、主机及OnePlus有界Qwen验收完成；其他设备矩阵pending | Mobile Use 悬浮审批与等待状态同步 | [任务](tasks/HXA-270.md) |
| HXA-272 | 实现/全仓主机/source、Developer API36 11项含Qwen、Standard纯Skill 1项及样例解析通过；其他OEM/长稳边界保留 | 统一 Skill 内容贡献与原生插件独立激活 | [任务](tasks/HXA-272.md) |
| HXA-273 | 有界本地交付；全仓主机/source、Developer API36含Qwen5项、storage17项及Standard窄屏2项通过 | Skill 用户与模型调用资格及显式激活 | [任务](tasks/HXA-273.md) |
| HXA-274 | 有界本地交付；全仓主机/source、Developer组合20项与内容/Qwen补验4项、Standard窄屏11项通过 | 插件就绪原因与配置生效状态统一投影 | [任务](tasks/HXA-274.md) |
| HXA-275 | 有界本地交付；主机、API36双渠道及既有Qwen通过 | 插件组件兼容诊断与可执行作者样例 | [任务](tasks/HXA-275.md) |
| HXA-276 | 主机、API36双渠道9/9与共享引擎29/29 passed；真实模型未调用 | Browser 原生插件贡献与统一选择入口 | [任务](tasks/HXA-276.md) |
| HXA-277 | 主机、API36真实后端8/8、Browser32/32与双渠道应用20/20、19/19 passed；模型未调用 | 原生插件资源归属与停止释放契约 | [任务](tasks/HXA-277.md) |
| HXA-278 | 主机、Developer内容6/6、consumer适用内容5/5 passed；Qwen三类各2轮基线/候选均6/6，成本混合 | Skill 渐进装载与上下文失效一致性 | [任务](tasks/HXA-278.md) |
| HXA-279 | 本地有界交付；主机/双渠道/六类Qwen已验，Gboard拼音失败与未测边界保留 | UI 观察反馈与 Browser 标签发现及有界等待 | [任务](tasks/HXA-279.md) |
| HXA-280 | 本地有界交付；完整主机、API36双渠道及OnePlus/Qwen通过，长期/OEM边界保留 | 工具发现与请求工作集优化 | [完成记录](../completion-records/HXA-280.md) |
| HXA-281 | 有界交付；全仓主机、API36两渠道与OnePlus/Qwen补验passed，公网账号/其他OEM边界保留 | 工具发现指导与外部插件市场复用 | [完成记录](../completion-records/HXA-281.md) |
| HXA-282 | 协议/市场/回填及诊断关联有界交付；R6旧制品两小时PASS，新稳定观察4业务/8返回通过；新版本长期资源及真实服务边界保留 | 外部服务、市场恢复与连续 Agent 验收 | [任务](tasks/HXA-282.md) |
| HXA-283 | 主机/双渠道制品交付；原生与模型设备验收not requested | 有界 PDF 读取与总结 | [任务](tasks/HXA-283.md) |
| HXA-284 | 有界交付；API36原生文档及Qwen摘要补验通过，其他API/峰值边界保留 | 统一PDF/DOCX文档插件 | [任务](tasks/HXA-284.md) |
| HXA-285 | 有界交付；API36 Room及保存引用的Qwen压缩/跟进通过 | 引用历史与压缩快照一致性收尾 | [任务](tasks/HXA-285.md) |
| HXA-286 | 已排期；发现配套子集归 303 | 统一工具契约、插件贡献与发现前置依赖 | [任务](tasks/HXA-286.md) |
| HXA-287 | 已排期，未实施 | 应用命令与完整 Turn 领域宿主 | [任务](tasks/HXA-287.md) |
| HXA-288 | 已排期，未实施 | 执行结果、Job 生命周期与类型化产物 | [任务](tasks/HXA-288.md) |
| HXA-289 | 已排期，未实施 | Workspace 路径匹配与文件内容搜索 | [任务](tasks/HXA-289.md) |
| HXA-290 | 已排期，未实施 | 网页搜索与正文读取工作流 | [任务](tasks/HXA-290.md) |
| HXA-291 | 已排期，未实施 | MCP Resources 发现、读取与引用 | [任务](tasks/HXA-291.md) |
| HXA-292 | 已排期，未实施 | Browser 表单与 Mobile Use 动作契约 | [任务](tasks/HXA-292.md) |
| HXA-293 | 已排期，未实施 | Memory 快照与上下文选择质量 | [任务](tasks/HXA-293.md) |
| HXA-294 | 已排期，未实施 | 目标架构冻结后的集成与恢复验收 | [任务](tasks/HXA-294.md) |
| HXA-295 | A 已交付，301补有界运行；B/C 未实施 | 会话查询、管理与受控任务投递 | [任务](tasks/HXA-295.md) |
| HXA-296 | 主机交付；设备/模型 not requested | 系统提示一致性与文件读写契约收尾 | [任务](tasks/HXA-296.md) |
| HXA-297 | 主机范围完成；设备/模型 not requested | 会话存储、查询与执行来源优化 | [任务](tasks/HXA-297.md) |
| HXA-298 | 已完成；主机/API36 有界验收 | 插件提示与统一配置管理 UI、清理并发修复 | [任务](tasks/HXA-298.md) |
| HXA-299 | 已完成；主机/API36/Qwen 有界验收 | 插件上下文诊断与可复用外部样本验收 | [任务](tasks/HXA-299.md) |
| HXA-300 | 主机完成；设备/模型 not requested | 插件导入预览、更新与完成引导 | [任务](tasks/HXA-300.md) |
| HXA-301 | 本地完成；有界模拟器/有提示真实模型通过，未推送发布 | v0.0.6 最新工作区本地发布验收 | [任务](tasks/HXA-301.md) |
| HXA-302 | 本地收尾完成；主机/最终R8 UI通过，模型OCR失败保留，暂不发布 | 插件、工作导航、终端与会话图片体验 | [任务](tasks/HXA-302.md) |
| HXA-303 | 已完成；主机通过，设备 not requested | 有效预算契约、声明式工具发现与重构任务收敛 | [任务](tasks/HXA-303.md) |
| HXA-304 | 完成；指定主机/ARM64 设备/Qwen 范围通过 | 预览升级确认与重构前双渠道验收 | [任务](tasks/HXA-304.md) |
| HXA-305 | 本地收尾完成；主机、独立 ARM64 / R8 / Qwen 有界验收通过 | 内置 Linux 环境默认可用与维护体验 | [主机](../evidence/development/bundled-linux-environment-2026-10-17.md) · [追加收尾](../evidence/development/linux-environment-closeout-2026-10-17.md) |
| HXA-306 | 已完成；仅本地收尾 | 手测反馈、浏览器与会话交互 | [任务](tasks/HXA-306.md) |
| HXA-307 | 主机/有界模拟器交付；真实网关联调 pending | 内置 Qwen 云端来源与公开 Client ID | [任务](tasks/HXA-307.md) |
| HXA-308 | 第一阶段本地交付 | 系统分享收集与本机知识库第一阶段 | [任务](tasks/HXA-308.md) / [证据](../evidence/development/local-knowledge-2026-10-19.md) |
| HXA-309 | 本地实现；最终真机待连接 | 本机备忘与任务提醒 | [任务](tasks/HXA-309.md) / [证据](../evidence/development/local-reminders-2026-10-19.md) |
| HXA-310 | 本地实现；真机主动试听待补 | 本机回复主动朗读 | [任务](tasks/HXA-310.md) / [证据](../evidence/development/read-aloud-2026-10-19.md) |
| HXA-311 | 本地自动验收交付；真机/客户端手测待补 | 三阶段收尾与回复朗读等待优化 | [任务](tasks/HXA-311.md) / [证据](../evidence/development/phase-closeout-2026-10-19.md) |
| HXA-312 | 本地主机/ARM64/真实合成模型交付；真机手测延后 | 本地知识库检索问答 | [任务](tasks/HXA-312.md) / [证据](../evidence/development/knowledge-questions-2026-10-19.md) |
| HXA-313 | 本地主机/ARM64/真实中文模型交付；真机手测延后 | 确认式自然语言提醒 | [任务](tasks/HXA-313.md) / [证据](../evidence/development/reminder-interpretation-2026-10-19.md) |
| HXA-314 | 本地主机/ARM64/真实合成模型交付，含规则预算与授权漂移补验；真机延后 | 最小定时 Agent 生命周期 | [任务](tasks/HXA-314.md) / [证据](../evidence/development/scheduled-agent-2026-10-19.md) |
