# ADR-0048: 审阅模式（Chat/Plan）额外放行内置 METADATA 操作类别

Status: proposed
Date: 2026-09-13
HXA: pending
Deciders: pending
Supersedes: none
Superseded by: none

## Context

ADR-0003 规定审阅模式（Chat、Plan）的工具表准入为双重门：`operationClass == READ_ONLY`（主判断）且动态风险不超过该模式上限（Chat L0、Plan L1）。其替代方案里没有为“需要持久写入但不属于用户可见变更”的工具预留位置。

研究文档 §5.1 要求 Plan 模式提供 `plan.submit` 结构化工具：Plan 是只读调研，但计划本身必须离开模型并落库为版本化 `PlanArtifact`（READY）供用户审阅。这类调用的唯一持久副作用是写入内部 harness 元数据（计划行、todo 账本），不是用户可见的本地变更，也不是外发。若把它伪装成 `READ_ONLY`，会让一次真实写入借只读类别通过审阅模式边界（研究文档 §5.1 明确“不能把持久元数据写入伪装成普通 READ_ONLY”）；若归为 `LOCAL_MUTATION`，则会在唯一要求它的 Plan 模式被拒绝。

生产代码已引入独立的 `ToolOperationClass.METADATA`（`core:model` `Risk.kt`），并让审阅模式过滤经 `ToolOperationClass.isReviewModeAdmitted`（`READ_ONLY || METADATA`）消费这一门——这已改变 ADR-0003 的 class 门，但该 ADR 尚未同步。`METADATA` 是**闭合集合**：只由内置工具携带（`plan.submit`、todo 写入）；MCP annotation、A2A Agent Card/Skill 或远端状态不能将工具声明为 `READ_ONLY` 或 `METADATA`。其作用域由工具 schema 固定：绑定当前 session/Turn、输入不含文件路径或外部 Goal ID、大小/版本/更新冲突受限、写入留审计。

## Decision

审阅模式（Chat、Plan）的工具表 class 门从“仅 `READ_ONLY`”扩展为“`READ_ONLY` 或内置 `METADATA`”；动态风险上限不变（Chat L0、Plan L1）。`METADATA` 是闭合的内置类别，外部来源无法声明。`ModePolicy.evaluateChat` / `evaluatePlan` 经 `ToolOperationClass.isReviewModeAdmitted` 消费这一门。

本决定部分扩展 ADR-0003 的 class 门，不改变其“class 为主判断、风险上限不替代 class 判断”的核心，也不为 `METADATA` 提供在 L2/L3 放行审阅模式读取的路径。

## Alternatives considered

1. **把 `plan.submit` 伪装成 `READ_ONLY`**：改动最小，但让一次持久写入借只读类别通过审阅模式边界，破坏 class 门的“只读效应”语义。研究文档 §5.1 明确禁止。未选择。
2. **归为 `LOCAL_MUTATION` 或 `EXTERNAL_ACTION`**：class 语义正确，但这些类别在审阅模式被拒绝，`plan.submit` 将无法在唯一要求它的 Plan 模式落地。未选择。
3. **新增独立的“计划提交”模式**：边界最清晰，但为一个内置工具单列模式过重，且用户心智上 Plan 本就是“产出并落地计划”的阶段。未选择。

## Consequences

- 收益：Plan 能按文档产出并持久化版本化计划；审阅模式的 class 门仍区分“只读效应”与“内部元数据写入”，不靠风险等级放宽。
- 代价：审阅模式 class 门从单一类别变为闭合的两类别集合，需要持续保证 `METADATA` 只由内置工具携带（外部来源无法声明）。
- 迁移影响：既有 `READ_ONLY` 审阅模式行为不变；新增能力仅限内置元数据工具。
- 后续约束：新增内置元数据工具时必须保持 `METADATA` 的固定作用域（session/Turn 绑定、无文件路径/外部 Goal ID、大小/版本/冲突受限、留审计）。
- 仍然存在风险：审阅模式现在会放行一种有持久副作用的类别，其安全完全依赖“内置、闭合、schema 固定作用域、留审计”四重约束；任一被破坏即应回到本 ADR 重新讨论。

## Verification

- `core:model` `Risk.kt`：`ToolOperationClass.METADATA` 与 `isReviewModeAdmitted = READ_ONLY || METADATA`。
- `core:agent` `ModePolicy` 经 `isReviewModeAdmitted` 放行审阅模式 class 门；`ModePolicyTest` 覆盖该 class 门。
- `app` `plan/PlanTools.kt`：`plan.submit` 的 `operationClass = METADATA`、`baseRisk = L0`、`NON_IDEMPOTENT`，输入 schema 无文件路径或外部 Goal ID。
- required before acceptance：补一条集成/设备级断言，确认 `plan.submit` 在 Plan 模式进入工具表且其写入被审计（当前证据集中在纯函数与 Fake Port 层）。

## Reconsider when

- `METADATA` 被扩展到非内置来源，或其固定作用域（session/Turn 绑定、无路径/外部 Goal ID）被放宽。
- 审阅模式需要对 `METADATA` 施加比 `READ_ONLY` 更紧的上限（如单独的审批或额度）。
- 研究文档改变 Plan 的产出契约，使计划不再需要持久化或改为文本计划。

## References

- [ADR-0003（审阅模式只读 class + 风险上限双门）](0003-plan-read-only-risk-ceiling.md)
- [架构 overview §5.1 模式边界](../architecture/overview.md)
- [架构 provider-mcp-skills-modes §6.1 模式语义](../architecture/provider-mcp-skills-modes.md)
