# ADR-0028: Goal 验收条件的验证绑定与人工证据复核

Status: superseded
Date: 2026-09-06
HXA: HXA-102
Deciders: Project owner（2026-09-08 明确接受 ADR-0028 并授权继续实现）
Supersedes: none
Superseded by: [ADR-0040](0040-model-judged-goal-completion.md)

## Context

ADR-0004 要求只有真实 ToolResult/Artifact verifier 支持的验收条件才能完成 Goal。当前 Criterion/StoredCriterion 仅保存 id、description、evidence；CriterionEvidence 仅保存 verifier 名称及 Artifact/ToolCall 引用。它们能拒绝无引用的口头声明，但不能表达引用与自然语言条件之间的对应关系。App 尚无 CriterionSatisfied/CompleteRequested 的生产调用者，直接接入任何 verified=true ToolResult 都可能把无关工具成功误作目标完成。

本方案新增验收绑定及证据来源契约，涉及 core:agent、core:storage 与 app 的公开值类型和持久格式，因此按 ADR 约定提交决定。它补充 ADR-0004 的证据生产契约，不改变 run/wake、预算耗尽、显式继续、恢复或状态边；接受后仍须实现及验收，不能以本文件接受代替生产接线。

接受依据：2026-09-08，项目所有者明确回复“接受 ADR-0028，继续实现”。本记录接受下列契约；生产接线与验收仍由 HXA-102 记录，不以 accepted 表示实现完成。

## Decision

以下契约已获所有者接受并授权在 HXA-102 中实现：

1. 每项条件保留用户可读描述，并可绑定一种封闭的验证方式。条件 id、描述和绑定具有稳定版本/hash；只允许显式用户操作保存/变更绑定，模型可提出建议但不能自行接受、放宽或替换。变更绑定使该条件已有证据失效，不修改历史运行记录。
2. 首版提供两条有明确边界的完成路径：
   - 确定性规则：对本 Goal 历史中可归属的持久 Artifact 快照验证 SHA-256，或验证有界 UTF-8 内容包含用户确认的字面文本；亦可检查用户明确选择的本地 Tool 名称对应的实际成功/verified 结果。后一种只证明该工具操作成功，不推断任意自然语言目标完成，UI 必须明确展示这个有限命题。
   - 人工证据复核：用于不能表达为上述规则的自然语言条件。用户查看本 Goal 的真实 Artifact 或 ToolResult 后显式确认对应条件；系统先核实来源、完整性及终态，将“用户复核 + 引用验证”与自动规则结果区分显示。普通勾选、没有可核实引用、模型自称完成均不构成证据。
3. 候选证据必须归属同一 Goal 的持久 run/Turn，不接受跨会话、其他 Goal、未完成、INTERRUPTED、NEEDS_REVIEW、已删除或完整性失效的引用。外部 MCP/A2A 的成功声明不能替代 Helix 本地验证；Artifact 字节验证仍不得推断远端业务副作用真实完成。
4. 确定性规则由宿主封闭代码执行；无正则/脚本/表达式语言、无插件提供的 verifier、无网络、无新文件 scope 或审批权限。内容检查有明确字节上限，超出即未验证并提供人工复核入口，不截断后假称全文通过。校验返回结构化事实，不把 Artifact/ToolResult 中的文本当指令。
5. 新证据记录验证方式/版本、条件绑定 hash、原始 Artifact/ToolCall 引用及内容 hash、来源 run/Turn、验证时间和结论。引用完整性由宿主复核，不能信任模型传入的 verifier 名称或 verified 标志。存储迁移兼容旧文本条件；旧条件保留为未绑定/待复核，不凭历史裸引用推断满足新契约。
6. 证据录入不创建 run、不扩预算、不自动恢复。保持 ADR-0004 的 RUNNING 完成边界：自动规则在活跃 run 的结算前验证；暂停中的人工复核先形成待验证选择，用户显式 Continue 才开启相应 run 并复核/消费证据。若预算或未知副作用阻止 Continue，保持原有暂停/输入状态，不能借“复核”绕过。全部条件有效且没有未决副作用时才提交 CompleteRequested，Goal/run/outcome/audit 原子结算。
7. UI 明确显示每条条件的规则或人工复核方式、证据来源、验证结果及失效原因。删除、改变条件或重试不能偷偷恢复旧证据；模型和外部内容没有任何写入该绑定、人工确认或完成事实的入口。

## Alternatives considered

- 将任意成功 ToolResult 作为所有条件证据：只能证明工具调用成功，不能证明条件相关性，拒绝。
- 让同一个模型自由判断自己是否完成：不能替代真实 verifier，容易把自述或注入内容当验收，拒绝。
- 只提供手动“标记完成”：没有真实引用验证，不满足 FR-AGENT-009，拒绝。
- 只支持已知 SHA-256：可严格验证，但用户事先通常不知道生成物 hash，无法覆盖现有自由文本条件；作为确定性子集保留，不独占完成路径。
- 开放脚本 verifier：会引入执行、安全和生命周期边界，不在本次范围。

## Consequences

收益是为自动验证和自然语言人工验收提供可审计的生产路径，同时保留真实证据约束。代价是增加绑定/证据迁移、来源关联与 UI 复核流程；任意自然语言条件不能自动被转换成可靠判定程序。首版确定性规则集合较小，不能把其通过扩写为目标之外的业务成功。

本方案不改变 Standard/Advanced 能力差异，不改变 ADR-0009 的 child/workflow 启用门禁，不引入审批或执行 DSL。若要求暂停态直接完成、无证据手动完成或模型自行接受绑定，需要另行取代相关已接受决策，本提案不包含这些变化。

## Verification

2026-09-08 已开始实现：绑定/存储、来源验证、人工复核暂存、生产终态结算与显式 Continue 前置复验已有组件验收；完整证据 UI、真实模型与生命周期验收仍未完成。逐项证据见 [HXA-102 完成记录](../completion-records/HXA-102.md)，以下门禁不能以组件通过替代：

- 迁移/编码：旧条件保留、未知版本失败、绑定改变使证据失效、合法值往返稳定。
- 来源/完整性：其他 Goal/run/会话、伪造 verifier、未决 ToolCall、缺失/篡改 Artifact、超限内容、外部虚假 completed 均不能完成。
- 正向：本地真实已验证工具与 Artifact 的确定性规则可完成；用户明确复核真实引用可完成自由文本条件；普通文本声明不能完成。
- 生命周期：证据录入/复核/结算各阶段取消与 kill/restart，恢复不重放副作用，不重复记账，不绕过预算；Goal/run/audit 原子结算。
- UI：创建/修改绑定、查看证据、显式复核、暂停后 Continue、失效提示；至少一条真实模型任务形成可验证产物并走到 COMPLETED。
- 继续执行 HXA-102 要求的 JVM、Lint、模拟器与实际后端中断矩阵；不将本 ADR 接受当作通过。

## Reconsider when

需要更广的自动语义验收、插件 verifier、远端副作用证明、暂停态直接完成，或现有封闭规则和人工证据复核无法覆盖目标产品任务。

## References

- [ADR-0004](0004-goal-run-wake-budget-semantics.md)
- [ADR 约定](README.md)
- [需求 FR-AGENT-009](../product/requirements.md)
- [Goal 模式规范](../architecture/provider-mcp-skills-modes.md)
- [HXA-102](../completion-records/HXA-102.md)

当前替代决策：[ADR-0040](0040-model-judged-goal-completion.md)。本文件保留历史，不再指导生产完成机制。
