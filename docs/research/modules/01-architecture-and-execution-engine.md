# 架构与执行引擎

> 更新：2026-10-11。§2、§5 的外部产品资料保留原核验窗口，本次未重新调研；§9 为当前 Helix 源码分析。研究不覆盖 accepted ADR。
> 当前执行契约/实现证据：[ADR-AGENT-001](../../adr/agent/001-turn-coordination.md)、[HXA-220](../../completion-records/HXA-220.md)、[HXA-221](../../completion-records/HXA-221.md)、[HXA-223](../../completion-records/HXA-223.md)；当前任务见[实施状态](../../development/status.md)。
> 进程死亡专题：[process-death-recovery-and-harness-depth.md](process-death-recovery-and-harness-depth.md)。

## 1. 总原则：浅策略，深不变量

综合 Codex、Claude Code、OpenCode、DeepSeek Harness 与 Helix 各阶段研究，最合理的 Harness 边界是：

> **模型决定工作流；Harness 决定事实、权限、执行边界和不可违反的不变量。**

Harness 应深管 Session/Thread persistence、context/compaction、tool schema/result pairing、permission/scope、cancel/interrupt、request idempotency、durable checkpoint、external-effect unknown、observation 和 live execution identity。

模型应主导下一步、是否重读/重测、plan 如何拆、任务是否完成，以及中断后从当前世界状态如何继续。

## 2. 主流 Harness 对照

### Codex

OpenAI App Server 把 Thread 定义为持久会话、Turn 定义为一次 agent work；同一 Codex core/harness 被 CLI、IDE、Desktop、Web 共用。App Server 管 thread lifecycle/persistence、config/auth、tool execution/extensions，客户端只消费稳定事件和 approval request。

来源：https://openai.com/index/unlocking-the-codex-harness/

### Claude Code

公开能力把 session/permissions/Plan、background agent、checkpoint/rewind、resume/branch 等交给 Harness；模型仍负责大部分任务分解和继续方式。中断恢复可以通过 continuation prompt 让模型接续。

来源：
- https://code.claude.com/docs/zh-CN/commands
- https://code.claude.com/docs/ko/env-vars

### OpenCode

Session Server 提供 create/fork/abort/revert/message，agents 划分 Build/Plan/subagent，并用 allow/ask/deny 控制工具。结构上同样是“Session/Tool/Permission 深，任务策略浅”。

来源：
- https://dev.opencode.ai/docs/server/
- https://opencode.ai/docs/zh-cn/agents/
- https://dev.opencode.ai/docs/permissions/

### DeepSeek Harness

append-only SessionEvent 是 source of truth，persistence/checkpoint policy 保护 request/tool effect；crash 时修复并关闭 interrupted Turn，而不是 partial-turn resume。是 Helix 恢复设计最有价值的直接参照。

来源：
- https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/session.md
- https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/persistence.md

## 3. Helix 当前架构判断

`core/`、provider、tools、runtime、feature、extensions 的现有边界整体合理；不要为了“架构更漂亮”继续拆 Gradle module。真正需要收敛的是 **owner 和状态解释**。

### TurnEngine single durable owner 是合理的

之前 ChatService/TurnCoordinator/Recovery/Goal/SessionTurnAdmission 分别解释 Turn 生命周期，真实存在 race 和重复 settlement。把 durable admission/cancel/review/terminal/recovery/receipt 收到 TurnEngine 是必要修复，而不是过度设计。

### live driver 也应收敛，但不能让 Engine 反向依赖 UI

HXA-223 后冻结的实际边界：

```text
UI / Chat projection
        ↓ commands
Application submission / SessionInputDelivery
        ↓
TurnEngine
  ├─ admission / cancel
  ├─ terminal settlement
  ├─ review park / resolve
  ├─ startup recovery
  ├─ live execution registry + AgentLoop driver
  └─ observation hub
        ↓
AgentLoop → TurnCoordinator
             └─ model/tool/compaction round checkpoints
        ↓
Room / Tool Dispatcher / Provider / Runtime

Turn terminal + owner release
        ↓
SessionWorkScheduler
  ├─ queued user input first
  └─ eligible Goal continuation
```

ChatService 保留 composer、screen projection、用户 submit/confirm/disclosure 与 UI-facing application commands；Session queue drain/Goal continuation algorithm 已移出，不能重新成为 Turn owner。`TurnCoordinator` 允许写 execution-round checkpoints，但 terminal/review/cancel/recovery durable truth 只属于 Engine-owned collaborators。

## 4. Recovery 冲突裁决

旧 HXA-220 设计倾向 deterministic review 后 same-Turn rehydrate。最新竞品/DSH 证据更支持：

> **旧 Turn 闭合为 interrupted，Session 保留；successor Turn 通过 RecoverySummary + 当前世界继续。**

真正必须保留的是 effect truth，而不是旧 Turn 的 control state。详细理由见专题文档。

该方向已由 ADR-AGENT-001 / ADR-GOAL-001 裁决，并在 HXA-220/223 完成生产 cutover；same-Turn old execution resurrection 不再是当前实现路径。

## 5. Goal 的合理层级

Goal 更适合作为长期 objective / completion contract；Turn/GoalRun 是一次执行 attempt。

Codex 最新 Goal 公开设计也把 continuation 放在 safe turn boundary，并要求 objective completion 结合实际证据，而不是让一个 Turn 无限运行。

研究推荐：

- Goal 可 durable；
- continuation 发生在新 Turn/新 Run；
- model 可以报告/请求完成，但系统/用户持有 pause/resume/clear/budget 等生命周期权力；
- crash 不要求 same GoalRun resurrection。

来源：https://developers.openai.com/cookbook/examples/codex/using_goals_in_codex

## 6. 并发、取消与副作用

必须保持：

- tool concurrency 由 platform 根据物理容量及具体不可重入引擎/控制资源决定；effect footprint 的业务读写分类不自动生成全局串行屏障；
- 读、写、代码与终端在各自能力和真实资源边界内均可并发；需要前项结果的调用由模型在后续 batch 发出；
- result 按模型原调用顺序回填；
- timeout/cancel != executor exited；
- 执行线程容量直到真实退出才释放；原 Job 身份和 retained owner 按原 Runtime 的 no-start/terminal reconciliation 证据处理，Turn 终态或等待完成不能替代；
- process-local Job 不成为 durable truth。

这些约束比“恢复 old Turn”更重要，也更接近真正的 Harness 职责。

## 7. 不建议的方向

- 为 Chat/Plan/Act/Goal 建四套 loop；
- 用 event sourcing/新 DB 再造 Room 的第二事实源；
- Engine 反向持有 ChatService/UI StateFlow；
- 把所有 effect 都包装成可自动 retry；
- 用复杂 workflow DSL 替代模型自身 planning；
- 为减少行数删除仍承担 fault oracle 的 reducer/test。

## 8. HXA-223 后优先级

1. **冻结 Core owner 边界**：没有新的实证缺陷时，不再为行数、命名或竞品形式继续拆 TurnEngine/TurnCoordinator。
2. **建立固定任务 eval**：用真实 Queue/Steer/Goal/review/recovery 长轨迹验证 task success、recovery-adjusted success、人工介入和 context fidelity，而不是继续凭代码结构判断 Agent 能力。
3. **UI/IA 第二轮可独立推进**：UI 只消费 application commands/read models，不把 durable lifecycle 写权重新带回 Compose/Chat facade。
4. **新增能力按 ADR/任务条件进入**：2026-09-29 归属核对时，Workspace/HXA-210 与本地模型/HXA-222 已有交付，不能继续沿用旧“等待/暂缓”描述；完整 Project Memory、生产 Subagent/Workflow 仍按[候选索引](../../development/candidate-decisions.md)分别判断。验证范围看对应完成记录。

后续 Core 接口和模块迁移目标统一见[Harness 方案](../../architecture/harness-refactor-plan.md)，推进和停止条件见[开发策略](../../development/feature-refactor-strategy.md)。本模块保留此前研究理由，不维护第二份 R1/R2/J1 排期。

## 9. 2026-10-11 分层复核与优化承接

### 9.1 核验范围与结论

基于 `7b4976d217a6738023f6a760fc94db98689483b2` 及当时工作区，使用 CodeGraph 与当前文件复核生产调用链、模块声明、状态与 ADR。本次是源码和文档分析，没有运行应用/测试、设备或真实模型，也不把并行未提交实现归为本次交付。外部产品信息沿用已有研究，本节不声称重新验证其最新实现。

Helix 已有单一 AgentLoop、单一 Turn 生命周期权威及统一工具执行准入。需要继续收敛的是应用入口/表现依赖、插件状态和资源归属；没有证据要求合并 Loop、TurnEngine、Coordinator 或重建执行框架。职责图见[架构总览](../../architecture/overview.md)，有效契约见 [ADR-AGENT-001](../../adr/agent/001-turn-coordination.md)。

### 9.2 当前代码证据

| 事实 | 当前入口 | 解释与边界 |
| --- | --- | --- |
| 唯一模型/工具循环在纯 JVM Core | [AgentLoop](../../../core/agent/src/main/kotlin/com/helix/core/agent/AgentLoop.kt) 的 runToolLoop、runToolRound | 端口化模型、日志、上下文、工具和预算；可由替代宿主提供适配，不等于整个产品已有独立运行服务 |
| 运行接口与宿主仍有应用耦合 | [AgentRuntime](../../../core/agent/src/main/kotlin/com/helix/core/agent/AgentRuntime.kt)、[AppAgentRuntime](../../../app/src/main/kotlin/com/helix/app/chat/AppAgentRuntime.kt)、[ChatService](../../../app/src/main/kotlin/com/helix/app/chat/ChatService.kt) 的 agentRuntime 接线 | submit/cancel 回调原 ChatService 命令，observe 消费 Engine view；完整 Turn 生命周期仍依赖 App/Room |
| Engine 管持久和 live 生命周期 | [TurnEngine](../../../app/src/main/kotlin/com/helix/app/engine/TurnEngine.kt)、[TurnExecutionDriver](../../../app/src/main/kotlin/com/helix/app/engine/TurnExecutionDriver.kt) | 准入、取消、恢复、review 和 terminal 同一权威；terminalize 先持久结算，再释放 live owner、发布结果 |
| Core 与宿主之间有明确适配 | [AgentLoopHost](../../../app/src/main/kotlin/com/helix/app/agent/AgentLoopHost.kt)、[TurnCoordinator](../../../app/src/main/kotlin/com/helix/app/agent/TurnCoordinator.kt)、[ChatToolCalls](../../../app/src/main/kotlin/com/helix/app/chat/ChatToolCalls.kt) | 分别装配 Core ports、承载 round checkpoint、适配工具批次与持久结果；保持原 Room 事务 |
| UI 已用应用服务和读投影，但仍有宽依赖 | [ChatScreen](../../../app/src/main/kotlin/com/helix/app/ui/ChatScreen.kt) 的参数与 DisposableEffect | 订阅 StateFlow；recoveryModelsNavigation / recoveryPermissionsNavigation 写入 ChatService，是具体表现层耦合，不是已复现泄漏或卡顿 |
| core 目录包含 Android 基础设施 | [模块依赖声明](../../../build.gradle.kts) | model/agent/policy/workspace 是 JVM，storage 是 Room Android Library；模块名字不能证明可独立部署 |
| 工具调度与授权分工已经存在 | [ToolScheduler](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolScheduler.kt)、[ToolDispatcher](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDispatcher.kt) | Scheduler 负责有界调度和原顺序结算；Dispatcher 负责绑定/schema、实时能力/策略/会话授权、精确批准及开始执行前复检 |
| 超时与实际退出已分开 | [ToolDeadlineRunner](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDeadlineRunner.kt)、[ExecutionOwnership](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ExecutionOwnership.kt) | 超时/取消只尝试中断；执行线程未退出仍占物理容量，原执行身份另按 Runtime 证据对账；不可将观察槽位释放误判为物理容量释放 |

系统权限、插件选择、就绪投影、会话操作策略与精确审批分别解决不同问题，最终在原 Dispatcher 汇合。权限的多层事实有必要保留；可以合并重复展示推导，不能用一个 ready/allowed 布尔值覆盖全部含义。手动浏览、文件管理和设置仍可通过各自应用服务工作，无需先建立 Turn。

### 9.3 优化建议与任务去重

下表登记建议归属；实时优先级和默认顺序只由 [status](../../development/status.md#系统分层优化承接2026-10-11)维护。本次不新增 HXA 或 ADR 编号。

| 建议 | 承接 | 具体增量 / 退出标准 |
| --- | --- | --- |
| 插件内容、调用资格和生效状态统一 | HXA-272～275 | 沿用原任务范围；274 追加恢复目标数据化与当前页面导航处理，替换所涉 ChatService 页面回调，保留271已交付产品行为 |
| Browser 统一贡献与共享资源生命周期 | HXA-276、277 | 277 追加四类 owner 映射与跨层取消/退出场景：Turn、调度槽位、实际执行、插件引用；接入原 Runtime 事实，不重复执行注册表 |
| Skill 上下文和 UI 观察质量 | HXA-278、279 | 按原契约验证内容失效/压缩一致性、标签发现和有界等待；模型收益以固定任务对照证明 |
| 应用命令和读模型持续收窄 | HXA-274 的恢复展示切片先实施；其余随有实际需求的原任务处理 | 原用户动作身份、门禁、队列、Room 和 Engine 保持单一；没有消费者时不造通用 facade/event bus，不以 ChatService 行数或空壳化验收 |
| 完整 Turn 宿主提取 | [条件项](../../development/candidate-decisions.md#系统分层的条件性增量) | 已有 Core/AgentRuntime 作为基础；实际消费者、所需端口、原子结算及恢复方案明确后，再决定同进程接口调整或模块提取；不预建 CLI/远程 Server |

已交付工作保留原归属：HXA-220/223 是 Engine owner 与 live driver，HXA-234 是 Core 迁移，HXA-254/255 是项目/权限应用接口，HXA-256 是生产测试桥，HXA-257/258 是会话状态与事务；这些均不重复登记。HXA-271 的授权恢复及开放设备验收仍归原任务，274 只扩展投影及承载接口；HXA-235 的安装/快照引用仍由原任务负责，277 只增加原生资源。验收事实以各任务和当前 status 为准。

### 9.4 实施取舍与验证边界

按单一目标实现替换受影响入口；不安排旧版本/旧数据库/旧会话/旧批准迁移，不留双注册、双写或兼容 adapter。本次文档修改不操作持久数据。当前合法调用的幂等、取消、UNKNOWN、原顺序回填与渠道规则仍须保留。

实施时先完成独立主机切片，再按当轮授权执行设备/模型验收；UI 重建/无订阅、迟到回调、权限撤销和 executor 未退出分别覆盖。结构收敛只能证明边界更明确，性能、任务完成率、OEM 与长稳改善必须另有测量。本次没有发现并复现新的运行故障，不能借本节自动关闭或重跑全部历史问题。
