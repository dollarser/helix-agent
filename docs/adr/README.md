# Helix 当前架构决策

这里只维护当前有效决定和仍有价值的候选方案。过时记录、旧编号、增量补丁说明和版本兼容规则不保留；需要追查决策演变时查看 `v0.0.1` 分支及 Git 历史。没有历史 ADR 目录或旧编号跳转层。

## 按需求阅读

| 主题 | 范围 |
| --- | --- |
| [Goal](goal/README.md) | 目标生命周期、连续执行、预算与完成 |
| [Provider](provider/README.md) | 模型选择、元数据与连接验证、订阅适配、任务路由与流式结果 |
| [权限与审批](permissions/README.md) | 会话授权预设、自定义权限与工具禁用、Chat/Plan 审阅与内置元数据操作、工具执行准入、精确审批与持久审计 |
| [工作目录与文件](workspace/README.md) | 会话目录、相对路径与内容身份、独立文件管理与传输恢复、Git 仓库一致性与产品边界、独立会话工作目录与外部资源绑定、有界PDF/DOCX文档读取 |
| [Runtime 与终端](runtime/README.md) | 执行域、打包、生命周期与结果对账、命令日志、后台 Job 与手动终端、QuickJS 隔离执行底座、RootService 依赖与调用边界 |
| [工具契约](tools/README.md) | 工具描述契约与审批绑定 |
| [MCP](mcp/README.md) | MCP Client、传输与工具接入 |
| [Skill](skills/README.md) | Skill 创作、安装与 MCP 配置闭环 |
| [Connector](connectors/README.md) | Connector 能力包、Connector public-client OAuth、Connector 安全替换、安装归属与会话启停、Connector 签名索引与来源 |
| [A2A](a2a/README.md) | A2A Client 与远端任务对账 |
| [Agent 执行与上下文](agent/README.md) | Turn 批次协调与持久结算、模型请求上下文与步骤边界压缩、附件快照与请求物化、有界只读委托与工作流边界、按会话 JSONL 导出、fork、统一输入调度、编辑重发与轻量请求清单 |
| [平台与基础设施](platform/README.md) | 产品完整性与渠道分发、浏览器 View 与逻辑标签生命周期、领域值的严格存储编码 |

## 决策与交付

- `accepted` 是已授权的设计；实际能力和证据查[实施状态](../development/status.md)、[路线](../development/roadmap.md)及完成记录。
- `proposed` 仍需决定，不能因整理文档而接受。工具 descriptor 与原子绑定已由 ADR-TOOLS-001 接受；会话工作目录绑定已 accepted 并由 HXA-210 完成本地交付。Connector 安全替换/会话启停已接受，HXA-129 已本地交付，整合状态见实施页；签名索引已接受并交付离线范围。
- 会话权限新方案已接受并由 HXA-209 交付（见[完成记录](../completion-records/HXA-209.md)）；Goal 按 HXA-208 的范围交付。二者不能共用“全绿”结论。
- 已授权方案可以要求重构现有代码。实现尚未跟上不是架构冲突，不恢复被废弃的兼容行为；未授权的范围变化才需新的决定。

## 插件优化任务的 ADR 归属（2026-10-11）

所有者要求将 HXA-272～279 的规划同步为现有主题 ADR，并按单一目标实现设计、不兼容旧版。以下增量均为 **accepted，代码未实施**；当前顺序见 [status](../development/status.md#插件优化队列2026-10-11)，研究依据见[任务映射](../research/topics/helix-plugin-architecture-optimization-2026-10-11.md#7-任务承接与验收)。

| HXA | 主决策归属 | 跨主题边界 |
| --- | --- | --- |
| 272、273、278 | [ADR-SKILLS-001](skills/001-authoring-and-installation.md)：统一内容、调用资格、渐进装载 | 包/组件归属沿用 CONNECTORS-003；上下文复用 ContextCompiler |
| 274、275 | [ADR-CONNECTORS-003](connectors/003-ownership-and-installation.md)：状态、生效时点、支持矩阵、作者样例 | 复用 HXA-271 授权恢复；274 的恢复目标与页面导航边界由 [AGENT-001](agent/001-turn-coordination.md)约束；内容资格由 SKILLS-001 定义 |
| 276 | [ADR-CONNECTORS-003](connectors/003-ownership-and-installation.md)：Browser 贡献与默认集合 | [PLATFORM-002](platform/002-browser-lifecycle.md)负责 View；[TOOLS-001](tools/001-descriptor-contract.md)负责唯一绑定 |
| 277 | [ADR-CONNECTORS-003 §11](connectors/003-ownership-and-installation.md#11-原生实例共享资源与释放hxa-277)：原生 owner、引用与释放 | AGENT-001 区分 Turn/调度/执行/插件引用；PLATFORM-002 负责 Browser 具体引用，TOOLS-001 保持原准入边界 |
| 279 | [ADR-PLATFORM-002](platform/002-browser-lifecycle.md)：标签发现、条件等待 | TOOLS-001 定义可核实观察结果；CONNECTORS-003 定义资源释放 |

## 系统分层优化的决策承接（2026-10-11）

[本轮源码分析](../research/modules/01-architecture-and-execution-engine.md#9-2026-10-11-分层复核与优化承接)沿用现有主题，无新 ADR 编号。ADR-AGENT-001 补应用命令/表现与执行资源边界；CONNECTORS-003 补 HXA-274 的恢复目标数据及 HXA-277 对原执行事实的复用，均追加 Decision history。原权限、工具和 Runtime 决策继续生效。

Core/Engine 和 HXA-257/258 已交付范围不重开；完整 Turn 宿主提取仅接受[进入条件](../development/candidate-decisions.md#系统分层的条件性增量)，未排入实现。优先级与顺序归 [status](../development/status.md#系统分层优化承接2026-10-11)，ADR accepted 不代表本次增量已实施或验收。

## 编写与更新

ADR 按**功能/长期契约**建文件，不按每次讨论、每个补丁或每轮重构建文件。文件为 `<topic>/NNN-short-title.md`，标题为 `ADR-TOPIC-NNN`；编号只在主题内唯一。一个功能后续发生设计变化时直接更新同一 ADR，不为“v2/v3/修订版”继续造编号。跨主题职责用链接，单个功能契约不复制到多个目录。

使用 `Status`、`Date`、`HXA`、`Deciders` 字段及 Context、Decision、Alternatives considered、Consequences、Verification、Reconsider when、References 章节。功能经历重要取舍变化时增加或更新 `Decision history`，只记录日期、改变了什么、为什么改变，不复制旧全文。新功能默认 proposed；只有所有者明确授权才 accepted。不要使用 implemented 作为 ADR 状态。

同一功能的调整直接覆盖现行 Decision；重要取舍获授权后，把当前有效规则写回正文，并在 `Decision history` 留一条变更记录。过时方案只在 Alternatives 中保留仍有解释价值的“不采用及原因”。不保留 Supersedes/Superseded by 链、旧 ADR 副本或重定向占位；需要逐字追查旧内容时使用 Git 历史。删除文档不授权删除用户数据或审计证据。

改变授权、信任、执行域、数据持久化、跨模块契约、核心依赖或发行边界需要明确决策。若属于现有功能，则更新该功能 ADR；只有独立的新功能边界才新建 ADR。普通 bug 修复和事实性路径更新不制造 ADR。任务完成记录链接当前相关决定并说明验收范围；过去的完成记录不能被解释为新方案已通过。

依赖名称和选型理由可写入决定，当前确切版本以 catalog/lockfile 为准；不要把一次 Spike 版本或旧测试数量写成永久约束。Verification 区分验收要求与已执行证据，不复制长篇流水账。

## 检查

运行 `./scripts/check-all.sh --source`：递归检查主题/编号/标题、状态、字段、章节及本地链接。代码、数据库和设备改动还需对应 HXA 的功能门禁。文档整理不修改验收结论，也不降低现有测试要求。
