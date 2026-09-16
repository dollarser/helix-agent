# ADR-0048: 审阅模式（Chat/Plan）额外放行内置 METADATA 操作类别

Status: accepted
Date: 2026-09-13
HXA: HXA-192
Deciders: Project owner（2026-09-14 授权审查并接受合理部分；限本 ADR 的有界架构契约）
Supersedes: none
Superseded by: none

## Context

ADR-0003 规定审阅模式（Chat、Plan）的工具表准入为双重门：`operationClass == READ_ONLY`（主判断）且动态风险不超过该模式上限（Chat L0、Plan L1）。其替代方案里没有为“需要持久写入但不属于用户可见变更”的工具预留位置。

[研究候选方案 §5.1](../research/helix-agent-complete-research-and-product-plan.md#51-plan-审阅)建议提供可审阅的结构化计划。Plan 是只读调研；当用户需要版本化计划时，`plan.submit` 将计划持久化为 `PlanArtifact`（READY）。这是一种用户可见的内部元数据变更，但不写用户文件或外发。不能将真实持久写入伪装成 `READ_ONLY`；归为 `LOCAL_MUTATION` 又会使其无法在 Plan 使用。研究材料不是授权来源，普通调研允许以文本结束，不因未调用 `plan.submit` 判失败。

生产代码已引入独立的 `ToolOperationClass.METADATA`（`core:model` `Risk.kt`），并让审阅模式过滤经 `ToolOperationClass.isReviewModeAdmitted`（`READ_ONLY || METADATA`）消费这一门——这已改变 ADR-0003 的 class 门，但该 ADR 尚未同步。`METADATA` 是**闭合集合**：只由内置工具携带（`plan.submit`、todo 写入）；MCP annotation、A2A Agent Card/Skill 或远端状态不能将工具声明为 `READ_ONLY` 或 `METADATA`。其作用域由工具 schema 固定：绑定当前 session/Turn、输入不含文件路径或外部 Goal ID、大小/版本/更新冲突受限、写入留审计。

## Decision

2026-09-14 所有者要求接受合理部分，经审查接受闭合内置元数据契约：它明确区分内部持久元数据和用户文件/外部副作用，保留风险上限与工具审批。原接受前集成检查保留为生产启用及 HXA-192 关闭的强制门禁，不删除、不记为通过；此次接受允许继续实现和验证，不等于未经验证即可合入或发行。执行包见 [HXA-192 交接](../development/harness-2.0-next-work.md)。

审阅模式（Chat、Plan）的工具表 class 门从“仅 `READ_ONLY`”扩展为“`READ_ONLY` 或内置 `METADATA`”；动态风险上限不变（Chat L0、Plan L1）。`METADATA` 是闭合的内置类别，外部来源无法声明。`ModePolicy.evaluateChat` / `evaluatePlan` 经 `ToolOperationClass.isReviewModeAdmitted` 消费这一门。

本决定部分扩展 ADR-0003 的 class 门，不改变其“class 为主判断、风险上限不替代 class 判断”的核心，也不为 `METADATA` 提供在 L2/L3 放行审阅模式读取的路径。

补充边界：

- `METADATA` 只能由平台内置注册表提供，不能由模型、MCP、A2A、Skill 或动态插件自行赋予；输入不得选择其他 session、Turn 或 Goal。
- 会话/Turn 归属来自受信调用上下文；审批、取消、审计与持久结算仍经过生产 Dispatcher。审批计划版本只代表用户审阅该计划，绝不是其中工具的批准证明。
- `plan.submit` 是结构化计划入口，普通任务不强制生成计划或 Todo。Todo 的任务归属、更新版本冲突及取消语义也需各自满足集成验收。
- ADR-0003 已增加“部分扩展”的交叉引用，保留其风险上限及其他禁令，不将整条 ADR-0003 标为 superseded。

## Alternatives considered

1. **把 `plan.submit` 伪装成 `READ_ONLY`**：改动最小，但让一次持久写入借只读类别通过审阅模式边界，破坏 class 门的“只读效应”语义。研究文档 §5.1 明确禁止。未选择。
2. **归为 `LOCAL_MUTATION` 或 `EXTERNAL_ACTION`**：这些类别在审阅模式被拒绝，无法在 Plan 保存供审阅的内部计划。未选择。
3. **新增独立的“计划提交”模式**：边界最清晰，但为一个内置工具单列模式过重，且用户心智上 Plan 本就是“产出并落地计划”的阶段。未选择。

## Consequences

- 收益：Plan 能按文档产出并持久化版本化计划；审阅模式的 class 门仍区分“只读效应”与“内部元数据写入”，不靠风险等级放宽。
- 代价：审阅模式 class 门从单一类别变为闭合的两类别集合，需要持续保证 `METADATA` 只由内置工具携带（外部来源无法声明）。
- 迁移影响：既有 `READ_ONLY` 审阅模式行为不变；新增能力仅限内置元数据工具。
- 后续约束：新增内置元数据工具时必须保持 `METADATA` 的固定作用域（session/Turn 绑定、无文件路径/外部 Goal ID、大小/版本/冲突受限、留审计）。
- 仍然存在风险：审阅模式现在会放行一种有持久副作用的类别，其安全完全依赖“内置、闭合、schema 固定作用域、留审计”四重约束；任一被破坏即应回到本 ADR 重新讨论。

## Verification

### 静态契约（已核对代码）

- `core:model` `Risk.kt`：`ToolOperationClass.METADATA` 与 `isReviewModeAdmitted = READ_ONLY || METADATA`。
- `core:agent` `ModePolicy` 经 `isReviewModeAdmitted` 放行审阅模式 class 门；`ModePolicyTest` 覆盖该 class 门。
- `app` `plan/PlanTools.kt`：`plan.submit` 的 `operationClass = METADATA`、`baseRisk = L0`、`NON_IDEMPOTENT`，输入 schema 无文件路径或外部 Goal ID。
- 外部来源不能声明 `METADATA`：`tools:framework` `ToolSourceTest` / `ToolDescriptorTest` 断言远端/MCP/A2A 来源无法归为 `READ_ONLY` 或 `METADATA`（闭合内置集合）。

### 已执行（2026-09-14，HXA-192 R2/R3）

- "仅文本可结束"语义修正闭合：`plan.submit` 描述改为"普通回答可结束 turn；需要可审阅的版本化计划时才调用本工具"，契约按契约演化规则由 v1 升 v2（`ToolVersion(2)`），`PlanToolsTest` 更新为精确期望 v2。
- 单元门禁 `./gradlew :core:agent:test :core:policy:test :tools:framework:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest` 通过：`PlanToolsTest` 6/6、`PlanReviewServiceTest` 10/10、`ModePolicyTest` 及框架 schema/Dispatcher 用例全绿（consumer debug 单元 494 例）。
- 生产管线集成测试 `app/src/androidTest/.../plan/PlanSubmitIntegrationDeviceTest.kt` **已在独占设备执行通过**（R3：`Helix_API_36` 与 `Helix_API_29` 各 4/4，`connectedDeveloperDebugAndroidTest`，真实 Room + 生产 Registry/过滤/Dispatcher；不用 Fake PlanRepository、不手插 plan/audit 行）。覆盖：PLAN 模式 `plan.submit` 成功 → READY 行 + 工件 v1 + tool-call `COMPLETED`(v2) + audit `SUCCESS` + 每次调用恰好一行 plan；伪造 Turn 在写任何行前被拒（无 plan/tool-call 行）；缺 `acceptanceCriteria` 的参数被 `INVALID_ARGUMENTS` 拒绝且不写 plan 行；启动前取消 → 持久 `CANCELLED_BEFORE_START` 且无 plan 行。设备日志在 `build/r3-device-*/` 与 `build/r3-mig-rerun-*/`。

### 产品启用门禁（逐项记录，不以架构接受代替完成）

2026-09-16：完整本地`check-all.sh --all`已通过，JGit已升级并保留TLS拒绝路径，见[基线修复](../bug-fixes/2026-09-16-pre-hxa-baseline-regressions.md)与[WIP接手](../development/wip-takeover-2026-09-16.md)。下方2026-09-14门禁失败仅是历史记录；前两项Plan用户闭环及授权隔离仍待专项设备验收。

- 普通 Plan **文本结束**、结构化审阅、取消与转执行的**用户闭环**（R3 设备）：上条设备测试只覆盖 `plan.submit` 的**工具级**生产管线与持久一致性；文本结束/审阅/转执行属 UI 与 agent 层闭环，本轮未做 UI 级设备验证，不能据工具级通过推断整条用户闭环成立。
- 计划审阅不 mint 工具审批、Plan 不因此放行文件写入/外发：审阅后文件写入仍走正常 Policy/Approval 的**设备级证明**（本轮未覆盖——工具级只证明 `plan.submit` 在 PLAN 模式经 class 门自动放行，不证明"审阅计划 ≠ 批准其中工具"这一不变量在设备上成立）。
- 完整 `./scripts/check-all.sh --all`（R4，2026-09-14）：`source_checks` 通过；`build_checks` 通过 spotless/detekt/test 后在 `:app:lintConsumerDebug` fail-fast，唯一失败为 2 项第三方 JGit `TrustAllX509TrustManager`（锁定 jar 的 `NoCheckX509TrustManager` 两个校验方法直接 return；`TransportHttp` 有 `sslVerify=false` 路径）。本轮未擅自 suppress 或降 TLS——按交接 §3.1，清除它需先证明不可达边界并给出精确到依赖坐标/检测项的局部豁免 + 防回归，属独立决策。`assembles`/`check-lockfiles`/`artifact_checks` 因 fail-fast 未执行；核心 plan/storage 切片已本地 commit `0d52eae7`（未推送/合并）。

## Reconsider when

- `METADATA` 被扩展到非内置来源，或其固定作用域（session/Turn 绑定、无路径/外部 Goal ID）被放宽。
- 审阅模式需要对 `METADATA` 施加比 `READ_ONLY` 更紧的上限（如单独的审批或额度）。
- 研究文档改变 Plan 的产出契约，使计划不再需要持久化或改为文本计划。

## References

- [ADR-0003（审阅模式只读 class + 风险上限双门）](0003-plan-read-only-risk-ceiling.md)
- [架构 overview §5.1 模式边界](../architecture/overview.md)
- [架构 provider-mcp-skills-modes §6.1 模式语义](../architecture/provider-mcp-skills-modes.md)
