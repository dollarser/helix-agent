# ADR-0002: Turn 状态机补充同响应内串行工具调用边与预调用预算失败边

Status: accepted
Date: 2026-08-31
HXA: HXA-011
Deciders: Project owner（通过 Codex 文档与决策收口审查授权）
Supersedes: none
Superseded by: none

## Context

架构文档 [02-architecture-design.md](../02-architecture-design.md) 第 5.2 节的 Turn 状态图最初只画了一条结果回边 `RECORDING_TOOL_RESULT → BUILDING_CONTEXT`，第 5.3 节伪代码则把 `contextBuilder.build()` 放在 `repeat` 循环体开头、把同一响应的多个工具调用放在一次上下文构建内的 `for` 循环中串行执行。两处读法存在张力：

- Provider 协议（OpenAI Responses / Chat Completions / Anthropic Messages）要求同一响应的每个工具调用在下次模型调用前都必须拿到结果，因此一个响应含 N>1 个调用时，记录完第 i 个结果后必须直接推进第 i+1 个调用，而不是每个调用都重新构建一次上下文；
- 第 5.3 节的预算规则（"每次模型调用前计算剩余 step/token"）意味着预算门控失败发生在提交模型调用之前，即 `BUILDING_CONTEXT` 阶段，该阶段因此必须能直接进入 `FAILED`；
- 恢复语义（第 5.2 节文字）要求 `INTERRUPTED` 可恢复，但恢复目标状态和丢弃路径没有画在图中。

HXA-011 在 `core:model` 的 `TurnState` 中以**严格增量**方式（不改动任何既有边）补上了三条边：`BUILDING_CONTEXT → FAILED`、`RECORDING_TOOL_RESULT → WAITING_APPROVAL`、`RECORDING_TOOL_RESULT → RUNNING_TOOL`，并用 12×12 全矩阵测试守卫。`INTERRUPTED → BUILDING_CONTEXT/CANCELLED` 已属于 HXA-010 的原始状态空间；本次只是把原图遗漏的既有恢复/丢弃语义补画出来。本 ADR 把三条新增边及相关图面澄清升级为正式决定，供 HXA-014（Room 持久化状态）和 HXA-015（恢复协调器）依赖。

## Decision

`TurnState`（`core:model`）的规范转移集合为第 5.2 节更新后的状态图，其中对本 ADR 之前的图新增以下边：

1. `BUILDING_CONTEXT → FAILED`：预调用预算门控（step / modelCalls / input 估算 / 累计 total）在提交模型调用前耗尽时进入 `FAILED`（`ErrorCode.POLICY`，不可重试）。
2. `RECORDING_TOOL_RESULT → WAITING_APPROVAL` 和 `RECORDING_TOOL_RESULT → RUNNING_TOOL`：同一模型响应的下一个工具调用串行推进（第一版串行，审批语义不变）；上下文**每模型响应构建一次**，不在每个工具调用之间重建。
3. 保留 HXA-010 已存在的 `INTERRUPTED → BUILDING_CONTEXT`（恢复，必须先完成副作用审查）和 `INTERRUPTED → CANCELLED`（丢弃），并将其补入规范图；这两条不是 HXA-011 的新增边。

同时补画既有代码语义但未在原图中出现的 `RUNNING_TOOL → RECORDING_TOOL_RESULT`（执行完成记录结果）边。`RECORDING_TOOL_RESULT → BUILDING_CONTEXT` 保留，语义收窄为"该响应的最后一个调用记录完毕"。

## Alternatives considered

1. **每个工具调用之间都重建上下文**（严格按原图字面执行）：语义最简单，但同一响应内重复构建 N 次上下文，浪费且与第 5.3 节伪代码的循环结构直接矛盾；Provider 协议也不要求每次模型调用之间插入上下文重建。未选择。
2. **引入专用中间状态**（如 `CONTINUING_SAME_RESPONSE`）表达"同响应内还有下一个调用"：状态空间更自描述，但为第一版串行执行引入只由实现细节驱动的额外状态，增大 Room 持久化和 UI 展示面；`RECORDING_TOOL_RESULT` 的出边集合已经足以表达该语义。若未来支持响应内并行工具调用（当前明确不做），重新评估。
3. **只改状态图、不改代码**：图与 `TurnState.canTransitionTo` 会再次漂移，HXA-015 的恢复门控需要 `INTERRUPTED` 的显式出边，无法只停留在图面。未选择。

## Consequences

- 收益：状态图与已交付的 `core:model` 契约一致；预算失败路径、同响应串行和恢复/丢弃语义都有显式边和全矩阵测试；HXA-014/015 可以直接依赖。
- 代价：`RECORDING_TOOL_RESULT` 的出边从 1 条变为 3 条，任何新的消费者（UI 时间线、审计）必须理解"回 BUILDING_CONTEXT"仅发生在最后一个调用之后；`StateMachinesTest` 的 12×12 期望表是该契约的回归基线，改边必须同步改表。
- 后续约束：HXA-014 持久化 `TurnState` 时使用同一枚举；HXA-015 实现恢复协调器时，`INTERRUPTED → BUILDING_CONTEXT` 的副作用审查（不确定 ToolCall 必须先解决）是该边的前置条件，不允许在协调器里绕过。
- 风险：第 5.3 节伪代码是示意性的，若未来协议或产品要求"每调用重建上下文"（例如按调用做提示缓存），本 ADR 的边需要重做。

## Verification

已执行（HXA-010/011 工作树，JDK 17，Gradle 9.5.0）：

- `./gradlew :core:model:test` → exit 0（含 `StateMachinesTest` 12×12 Turn 全矩阵，三条新增边与既有边共同被守卫）。
- `./gradlew :core:agent:test` → exit 0（57 个 reducer 测试，含 `RECORDING_TOOL_RESULT → RUNNING_TOOL` 新边、多调用串行、预算门控失败、进程死亡全 phase 穷举、恢复门控与丢弃）。
- [HXA-011 完成记录](../completion-records/HXA-011.md) 记录了完整的验收命令与结果。

2026-08-31 文档与决策收口审查接受本 ADR。接受范围是 HXA-011 的三条新增边及对 HXA-010 既有恢复/丢弃边的规范图澄清；实现完成度仍以 HXA-011 完成记录和测试证据为准。

## Reconsider when

- 产品决定在响应内并行执行工具调用（第一版明确串行）。
- 提示缓存或成本优化要求每个工具调用之间重建上下文。
- Provider 协议变化，使"同响应调用必须在下次模型调用前全部拿到结果"不再成立。
- HXA-015 恢复协调器实现中发现 `INTERRUPTED` 出边集合不足以表达副作用审查的子状态。

## References

- [02-architecture-design.md 第 5.2/5.3 节（Turn 状态与 Agent Loop）](../02-architecture-design.md)
- [07-security-testing-release.md（审批与不确定性副作用）](../07-security-testing-release.md)
- [HXA-011 完成记录](../completion-records/HXA-011.md)
- 实现：`core/model/src/main/kotlin/com/helix/core/model/TurnState.kt`（KDoc 含规范状态图）
- 测试：`core/model/src/test/kotlin/com/helix/core/model/StateMachinesTest.kt`、`core/agent/src/test/kotlin/com/helix/core/agent/TurnReducerInterruptionTest.kt`
