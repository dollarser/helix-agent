# ADR-AGENT-004: 有界只读委托与工作流边界

Status: accepted
Date: 2026-09-16; revised 2026-10-15
HXA: HXA-105, HXA-287
Deciders: Project owner（2026-10-15 要求补足后续扩展边界；不启用生产子 Agent）

## Context

任务拆分需要独立运行身份和上下文，同时保留授权范围、用量归属、取消及结果关系；不能通过委托隐含扩大权限或绕过真实资源限制。

## Decision

本设计不直接启用生产子 Agent。以下只读/渠道/数量条款属于早期首版实验策略，不是通用 Engine 的类型或永久上限；当前仍不据此开放新能力。后续立项应按实际任务重新选择产品范围、并发、深度与期限，写回本 ADR。HXA-287 只验证独立运行基础，接线子 Agent 另有任务与验收：

1. **首版实验策略与用量归属**：原只读 developer/Advanced 设计采用最大深度 1、并发 2、每父 Turn 最多 4 个 child；这些是待生产立项复核的策略值，不写死到共享运行/存储接口。child 的模型调用、token、工具次数和墙钟保留自身明细并可归集父任务，避免重复记账；上下文窗口各自独立。遵循 [ADR-AGENT-006](006-model-data-budget-boundaries.md)，归集不恢复已废除的累计 token/调用数硬门禁；并发、物理容量、取消与显式运行期限分别处理。
2. **最小上下文与无授权继承**：child 接收自包含任务和最小只读 snapshot，或父会话已完成轮次的确定性截断；不继承 pending approval、Approval Proof、Secret、UI token、Root/Automation session 或可写 capability。
3. **只读工具面**：child 只可使用 `operationClass=READ_ONLY` 的工具，不能请求或消费 Tool Approval。需要变更时只返回结构化 proposal，由父 Turn 新建 ToolCall 并走完整 Policy/Approval。
4. **受限通信与持久图**：首版只允许 parent→child task/cancel、child→parent structured completion；不允许 peer 消息、递归派生或无限续话。父子拓扑、状态、用量归属、已确定的执行限制、取消和 completion result 必须持久化；不能直接复用父 Turn/ToolCall 身份或绕过原 session gate。
5. **声明式 Workflow 候选**：仅有版本、静态有界的 JSON DAG，节点类型封闭；每个节点编译回普通 Dispatcher ToolCall/只读委托/verifier，不执行用户或模型提供的 JS/Starlark 编排脚本。

同时明确不采纳：Agent 自修改/自挂插件、可编程 Policy DSL、递归多 Agent 群体、独立 ralph 生命周期、云端任务舰队、remote diff apply 和 deferred network approval。Goal 继续是唯一跨轮自治原语；Remote Worker 仍需未来独立 ADR。

### 运行基础的扩展约束

未来 child 复用同一 AgentLoop/Turn 领域实现及 Dispatcher，持有自己的运行与内容快照；父子协调不复制引擎或建立第二历史事实源。父取消如何处理 child、child 完成如何汇合、主进程死亡如何对账，在生产立项时明确。UI 选择、父上下文压缩或一个运行清理不得改变另一个运行的身份/权限/结果。当前 HXA-287 的普通多会话隔离测试只证明基础，不证明父子恢复已实现。

## Alternatives considered

1. **始终保持单 Agent。** 安全、费用和恢复最简单，可能已足够覆盖手机任务；如果实际任务质量提升不显著或资源成本过高，保持单 Agent 产品，不启用委托。
2. **完整移植桌面多 Agent 树与 peer communication。** 灵活但权限传播、预算爆炸、提示注入和恢复状态过于复杂，不适合首版 Android。
3. **直接提供 JavaScript/Starlark Workflow DSL。** 表达力高，但会新增第二套代码执行和 Policy 攻击面，也诱导自修改；Helix 已有 Tool/Skill/Goal，首版收益不足。
4. **把 child 放到云端任务服务。** 可降低手机资源压力，但改变 Remote Worker、数据出境、账号与 diff apply 边界，超出当前单机范围。
5. **所有 Profile 都开放只读 child。** 普通用户也可能受益，但会增加费用、功耗和 UI 复杂度；先在 Advanced 收集证据，后续若默认配置足够简单再重新考虑 Standard。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本决定仅接受有界设计，不声明生产子 Agent/Workflow 已接线。上线须有独立任务，验证所选深度/并发策略、用量归属、资源期限、取消、持久图、最小上下文及实际收益；不以历史固定数量或累计 token 门禁代替实际资源与任务证据。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## Decision history — 2026-10-15

按 Owner 扩展性评审，将早期实验策略与共享运行能力分开，澄清独立上下文及用量归集不等于累计资源硬门禁；HXA-287 承接运行隔离，不启用生产委托、可写 child 或额外渠道。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
