# ADR-0009: 手机端有界委托与声明式 Workflow

Status: proposed
Date: 2026-08-31
HXA: HXA-105
Deciders: pending
Supersedes: none
Superseded by: none

## Context

Helix 当前产品和已实现 M1 领域模型采用单 Agent Turn/Goal。外部 Agent Harness 已证明子 Agent、并行工具、Workflow DSL、云端任务和 Agent 间通信可以提高复杂任务吞吐，但其桌面/云端假设不能直接套到 Android：手机有更严格的内存、热量、电池、后台和网络成本，Helix 还必须维持 Standard/Advanced、Android scope、逐次审批、独立 Runtime 与不重放边界。

核心 Tool 并发、确定性回填、持久事件和取消属于单 Agent Dispatcher 的基础能力，已由 HXA-037 规划，不依赖本 ADR。本记录只决定是否在基础能力稳定后增加 child delegation 和声明式 Workflow。

## Decision

提议 HXA-105 只评估以下有界能力，证据通过并由项目所有者接受后才进入产品：

1. **只读 child delegation**：仅 developer/Advanced 实验入口；最大深度 1、并发 2、每父 Turn 最多 4 个 child。child 的模型调用、token、Tool 次数和墙钟全部计入父 Turn/Goal 预算。
2. **最小上下文与无授权继承**：child 接收自包含任务和最小只读 snapshot，或父会话已完成轮次的确定性截断；不继承 pending approval、Approval Proof、Secret、UI token、Root/Automation session 或可写 capability。
3. **只读工具面**：child 只可使用 `operationClass=READ_ONLY` 且动态风险 ≤ L1 的工具，不能请求或消费 Tool Approval。需要变更时只返回结构化 proposal，由父 Turn 新建 ToolCall 并走完整 Policy/Approval。
4. **受限通信与持久图**：首版只允许 parent→child task/cancel、child→parent structured completion；不允许 peer 消息、递归派生或无限续话。父子拓扑、状态、预算占用、取消和 completion result 必须持久化。
5. **声明式 Workflow 候选**：仅有版本、静态有界的 JSON DAG，节点类型封闭；每个节点编译回普通 Dispatcher ToolCall/只读委托/verifier，不执行用户或模型提供的 JS/Starlark 编排脚本。

同时明确不采纳：Agent 自修改/自挂插件、可编程 Policy DSL、递归多 Agent 群体、独立 ralph 生命周期、云端任务舰队、remote diff apply 和 deferred network approval。Goal 继续是唯一跨轮自治原语；Remote Worker 仍需未来独立 ADR。

## Alternatives considered

1. **始终保持单 Agent。** 安全、费用和恢复最简单，可能已足够覆盖手机任务；如果 HXA-105 的质量提升不显著或资源成本过高，应选择此方案并拒绝本 ADR。
2. **完整移植桌面多 Agent 树与 peer communication。** 灵活但权限传播、预算爆炸、提示注入和恢复状态过于复杂，不适合首版 Android。
3. **直接提供 JavaScript/Starlark Workflow DSL。** 表达力高，但会新增第二套代码执行和 Policy 攻击面，也诱导自修改；Helix 已有 Tool/Skill/Goal，首版收益不足。
4. **把 child 放到云端任务服务。** 可降低手机资源压力，但改变 Remote Worker、数据出境、账号与 diff apply 边界，超出当前单机范围。
5. **所有 Profile 都开放只读 child。** 普通用户也可能受益，但会增加费用、功耗和 UI 复杂度；先在 Advanced 收集证据，后续若默认配置足够简单再重新考虑 Standard。

## Consequences

- HXA-037 的确定性单 Agent scheduler 先完成，HXA-105 不阻塞首个可用版本。
- child 无法直接完成写任务，但权限链清晰：它只提出证据化 proposal，父 Turn 是唯一审批与执行主体。
- 深度/数量/预算硬限制会牺牲大型并行研究吞吐，换取可预测费用和恢复状态。
- 声明式 DAG 比脚本 DSL 表达力低，但可做 schema validation、静态 fan-out 检查和确定性回放。
- 若最终拒绝本 ADR，HXA-105 的 fixture 和测量仍可作为“保持单 Agent”的证据，不应留下半实现工具入口。

## Verification

Required before acceptance（HXA-105）：

- 用固定研究/repo inspection/verifier 场景比较单 Agent 与 1～2 个 child 的正确率、模型调用、token、墙钟、网络字节、峰值内存、热量与电量；没有明显收益则拒绝。
- 证明 depth=1、concurrent=2、total-per-turn=4 和父预算在并发/恢复/时钟回拨下 fail closed。
- prompt-injection fixture 证明 child 不能获得写工具、Approval Proof、Secret、Root/Automation session、UI token 或扩大 context/scope。
- 在 spawn、running、completion persist、parent merge 前后 kill 进程，恢复不重复 child、不丢 completion、不把自述当 verifier evidence。
- 证明 completion 以 source/trust/hash/ToolResult/Artifact refs 回流，按父 call sequence 进入模型上下文。
- JSON DAG 覆盖未知 node、循环/无界 fan-out、超预算、取消、依赖失败、写节点审批和恢复；脚本/插件/Policy 节点拒绝。
- API 29/36 与代表性真机测 30 分钟并发任务，无不可接受温升、内存压力或后台误运行。

当前只完成文档分析；未执行 HXA-105，也未实现 child、Agent graph 或 Workflow。

## Reconsider when

- HXA-105 证明只读委托在代表性任务上稳定提高质量/时延，且资源、费用和恢复满足门禁。
- 模型具备更强的单 Agent context/并行 ToolCall 能力，使 child 收益消失。
- Android 后台、热管理或 Provider 费用变化使候选上限不可接受。
- 产品需要 Standard 也使用委托、递归 Agent、peer communication 或 Remote Worker；这些变化需要修改或取代本 ADR。

## References

- [手机端 Tool 编排方案](../architecture/mobile-tool-orchestration.md)
- [总体技术方案](../architecture/overview.md)
- [Provider/MCP/Skills/模式](../architecture/provider-mcp-skills-modes.md)
- [路线 HXA-037/HXA-105](../development/roadmap.md)
- [安全测试与发布门禁](../security/testing-and-release.md)
- [ADR-0004：Goal run/wake/budget](0004-goal-run-wake-budget-semantics.md)
- [ADR-0005：Standard/Advanced](0005-standard-advanced-safety-profiles.md)
