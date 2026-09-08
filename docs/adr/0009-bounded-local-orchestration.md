# ADR-0009: 手机端有界委托与声明式 Workflow

Status: accepted
Date: 2026-08-31
HXA: HXA-105
Deciders: Project owner（2026-09-06 授权审查，合理时接受；接受架构约束，不将未完成验证记为通过）
Supersedes: none
Superseded by: none

## Context

Helix 当前产品和已实现 M1 领域模型采用单 Agent Turn/Goal。外部 Agent Harness 已证明子 Agent、并行工具、Workflow DSL、云端任务和 Agent 间通信可以提高复杂任务吞吐，但其桌面/云端假设不能直接套到 Android：手机有更严格的内存、热量、电池、后台和网络成本，Helix 还必须维持 Standard/Advanced、Android scope、逐次审批、独立 Runtime 与不重放边界。

核心 Tool 并发、确定性回填、持久事件和取消属于单 Agent Dispatcher 的基础能力，已由 HXA-037 规划，不依赖本 ADR。本记录只决定是否在基础能力稳定后增加 Helix 内部 child delegation 和声明式 Workflow。M7 的外部 A2A Client 由 [ADR-0016](0016-a2a-client-interoperability.md)另行定义：它是父 Turn 的网络 ToolCall，不是本 ADR 的 child/peer runtime。

## Decision

2026-09-06 接受以下有界架构约束。HXA-105 的隔离 Spike 不进入产品；后续生产实现
必须单独明确 HXA 范围，并通过下文全部启用门禁。接受设计不授权以当前 Spike 替代生产
Dispatcher、持久化或授权实现，也不声明已证明模型收益：

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

已执行的隔离 Spike 证据（HXA-105）：

HXA-105 已建立隔离的 `:spikes:bounded-orchestration` falsification harness；它不依赖 `:app`、生产 Dispatcher 或 Tool Registry。2026-09-05 执行 6 个 JVM 测试与 API 29/36 各 2 个 instrumentation 测试，证明 depth/concurrency/total、父预算跨重建、只读 ≤ L1 工具面、取消/恢复不重放、source/trust/hash/evidence refs 和确定性 call-sequence merge，以及 DAG 的未知依赖、环和节点上限拒绝。该证据只证明部分边界模型可实现，不证明质量/资源收益；2026-09-05 的结论因此保持 `proposed`。
2026-09-06 的架构接受决定见下节；产品仍继续单 Agent。

Required before production enablement（以下未执行项仍是阻断门禁）：

- 用固定研究/repo inspection/verifier 场景比较单 Agent 与 1～2 个 child 的正确率、模型调用、token、墙钟、网络字节、峰值内存、热量与电量；没有明显收益则拒绝。
- 证明 depth=1、concurrent=2、total-per-turn=4 和父预算在并发/恢复/时钟回拨下 fail closed。
- prompt-injection fixture 证明 child 不能获得写工具、Approval Proof、Secret、Root/Automation session、UI token 或扩大 context/scope。
- 在 spawn、running、completion persist、parent merge 前后 kill 进程，恢复不重复 child、不丢 completion、不把自述当 verifier evidence。
- 证明 completion 以 source/trust/hash/ToolResult/Artifact refs 回流，按父 call sequence 进入模型上下文。
- JSON DAG 覆盖未知 node、循环/无界 fan-out、超预算、取消、依赖失败、写节点审批和恢复；脚本/插件/Policy 节点拒绝。
- API 29/36 与代表性真机测 30 分钟并发任务，无不可接受温升、内存压力或后台误运行。

当前完成 HXA-105 隔离 Spike，但未实现生产 child、Agent graph 或 Workflow，也未接入普通 Agent 工具表。30 分钟真机资源/收益对照与真实 Room kill-point 持久化仍未完成，故不满足生产启用条件。

## 2026-09-06 授权收尾审查

项目所有者授权“如果合理就接受”。本次审查发现原 Spike 的负数 usage、累计整数溢出、
completion 自报 trusted，以及跨 coordinator 非原子准入缺少反例保护。现已补充测试并修复：
usage/budget 非负、累计 checked arithmetic、completion trust 固定 untrusted、共享 journal
上的准入/状态事务互斥。20 个同时请求者共用两个槽位的回归也已通过。
这些是隔离 Spike 的边界修复，不能反向改写 2026-09-05 的完成记录。

接受理由：共享父预算、只读低风险工具面、不继承授权、结果保留来源且不能自授信任，
与既有单 Agent Dispatcher 和 ADR-0004 的边界兼容；封闭且有界的 DAG 可以复用这些契约，
无需新增可执行编排语言。相比直接移植桌面递归 Agent，状态空间与权限传播可受控。

本次所有者授权下的决定范围是**架构约束接受**，不是**生产化收益验收**。
明确将旧提案混合在 “before acceptance” 下的模型收益、Room kill-point 与真机门禁
保留为 “before production enablement”；这是一项显式阶段划分，不是声称旧门禁已通过。
当前仍没有运行期间预算预留/持久 checkpoint、真实 Room graph、模型收益或物理热量/电量证明。
这些必须在后续生产实现前逐项补齐；若无明显收益，保持单 Agent 并重新评审本决定。
本轮不新增生产 child、Agent graph、Workflow 入口，不新增自动唤醒或授权继承。

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
