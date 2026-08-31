# ADR-0003: Plan 模式同时限制只读操作类别与动态风险上限

Status: accepted
Date: 2026-08-31
HXA: HXA-012
Deciders: Project owner（通过 Codex 文档与决策收口审查授权）
Supersedes: none
Superseded by: none

## Context

Helix 的 `ToolOperationClass` 描述操作效应，动态 `RiskLevel` 则结合数据敏感度、scope、网络 origin、工具来源和执行目标。两者正交：一个工具可以是 `READ_ONLY`，但读取 Root 日志、通知、联系人或新的敏感 scope 后动态风险仍可能升到 L2/L3。

原路线只明确 Plan 必须是 `READ_ONLY`，专项方案同时出现了 L0/L1 风险描述。HXA-012 的 `ModePolicy` 将两者解释为双重门：先拒绝非 `READ_ONLY`，再拒绝动态风险高于 L1 的读取。该选择改变模式层的工具暴露边界，属于安全/审批决定，应由 ADR 固化，而不应只留在完成记录和代码 KDoc 中。

## Decision

Plan 模式的工具表必须同时满足：

1. `operationClass == READ_ONLY`；该条件是主判断，风险等级不能把写入、联网、代码执行、外部动作或特权操作伪装成可用工具。
2. `dynamicRisk <= L1`；即使属于 `READ_ONLY`，动态风险升到 L2/L3 时也不进入 Plan 工具表。

Plan 模式不提供通过一次审批临时放行 L2/L3 读取的路径。需要敏感读取时，用户应明确切换到 Act/Goal，并继续经过完整的 Policy、Approval 和审计管线。模式过滤只是工具表准入，不是执行授权。

## Alternatives considered

1. **允许任意风险的 READ_ONLY**：调研能力最强，但会让 Plan 在用户通常理解为“只规划”的阶段读取高敏感数据；操作无副作用不等于数据暴露风险低。未选择。
2. **Plan 只允许 L0 READ_ONLY**：边界最简单，但会排除经用户授予普通 scope 的有限读取，使文件分析和已有页面研究过度受限。未选择。
3. **Plan 对 L2/L3 弹审批后放行**：比完全拒绝灵活，但会让 Plan 承担高敏感执行语义，模糊 Plan 与 Act 的产品边界。首版不选择；如真实场景表明切换模式成本过高，再通过取代 ADR 评估。

## Consequences

- 收益：只读效应和数据敏感度均有独立门控；Plan 不会因工具错误标注或敏感 scope 而暴露 L2/L3 读取。
- 代价：Plan 无法自行完成需要高敏感证据的调研，必须提示用户切换模式。
- 后续约束：HXA-032/033 落地真实动态风险计算后，`ModePolicy` 必须消费其结果；MCP annotation、Skill 指令和静态 `baseRisk` 都不能降低动态风险。
- UI 必须把“因操作类别拒绝”和“因动态风险拒绝”显示为不同原因，避免用户误以为只需重新授权文件即可放行写操作。

## Verification

HXA-012 已执行：

- `./gradlew :core:agent:test` → 66 tests, 0 failures, 0 skipped，exit 0。
- `ModePolicyTest` 覆盖 Plan 允许 READ_ONLY/L0/L1、拒绝 READ_ONLY/L2，以及拒绝处于 L1 的 write/http.fetch/bash/browser.click/ui.click。
- [HXA-012 完成记录](../completion-records/HXA-012.md)保存完整命令和结果。

动态风险目前仍由测试 profile 提供；HXA-032/033 接入真实 Policy Engine 后必须补集成测试，这不影响本模式边界决定的接受状态。

## Reconsider when

- 固定评测表明大量合法 Plan 任务必须频繁切换到 Act，显著破坏可用性。
- 动态风险模型改变，L1 不再能代表普通 scope 内的有限读取。
- 产品引入独立的“敏感只读调研”模式或可证明不泄露内容的摘要执行器。

## References

- [总体架构第 5.1 节](../02-architecture-design.md)
- [Provider、MCP、Skills 与模式方案第 6.1 节](../10-provider-mcp-skills-modes.md)
- [HXA-012 完成记录](../completion-records/HXA-012.md)
