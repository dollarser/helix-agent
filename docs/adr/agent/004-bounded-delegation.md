# ADR-AGENT-004: 有界只读委托与工作流边界

Status: accepted
Date: 2026-09-16
HXA: HXA-105
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

任务拆分不能产生隐含授权继承、无界子任务或独立预算。

## Decision

本设计不直接启用生产子 Agent。接线须明确任务范围并验收以下边界：

1. **只读 child delegation**：仅 developer/Advanced 实验入口；最大深度 1、并发 2、每父 Turn 最多 4 个 child。child 的模型调用、token、Tool 次数和墙钟全部计入父 Turn/Goal 预算。
2. **最小上下文与无授权继承**：child 接收自包含任务和最小只读 snapshot，或父会话已完成轮次的确定性截断；不继承 pending approval、Approval Proof、Secret、UI token、Root/Automation session 或可写 capability。
3. **只读工具面**：child 只可使用 `operationClass=READ_ONLY` 且动态风险 ≤ L1 的工具，不能请求或消费 Tool Approval。需要变更时只返回结构化 proposal，由父 Turn 新建 ToolCall 并走完整 Policy/Approval。
4. **受限通信与持久图**：首版只允许 parent→child task/cancel、child→parent structured completion；不允许 peer 消息、递归派生或无限续话。父子拓扑、状态、预算占用、取消和 completion result 必须持久化。
5. **声明式 Workflow 候选**：仅有版本、静态有界的 JSON DAG，节点类型封闭；每个节点编译回普通 Dispatcher ToolCall/只读委托/verifier，不执行用户或模型提供的 JS/Starlark 编排脚本。

同时明确不采纳：Agent 自修改/自挂插件、可编程 Policy DSL、递归多 Agent 群体、独立 ralph 生命周期、云端任务舰队、remote diff apply 和 deferred network approval。Goal 继续是唯一跨轮自治原语；Remote Worker 仍需未来独立 ADR。

## Alternatives considered

1. **始终保持单 Agent。** 安全、费用和恢复最简单，可能已足够覆盖手机任务；如果实际任务质量提升不显著或资源成本过高，保持单 Agent 产品，不启用委托。
2. **完整移植桌面多 Agent 树与 peer communication。** 灵活但权限传播、预算爆炸、提示注入和恢复状态过于复杂，不适合首版 Android。
3. **直接提供 JavaScript/Starlark Workflow DSL。** 表达力高，但会新增第二套代码执行和 Policy 攻击面，也诱导自修改；Helix 已有 Tool/Skill/Goal，首版收益不足。
4. **把 child 放到云端任务服务。** 可降低手机资源压力，但改变 Remote Worker、数据出境、账号与 diff apply 边界，超出当前单机范围。
5. **所有 Profile 都开放只读 child。** 普通用户也可能受益，但会增加费用、功耗和 UI 复杂度；先在 Advanced 收集证据，后续若默认配置足够简单再重新考虑 Standard。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本决定仅接受有界设计，不声明生产子 Agent/Workflow 已接线。上线须有独立任务和深度/数量/预算、取消、持久图、最小上下文及实际收益证据。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
