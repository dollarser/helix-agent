# 架构与执行引擎

> 更新：2026-10-14。§2、§5 保留原外部资料核验窗口，§9 是 2026-10-11 分析；§10 重新核验当前源码与主流 Agent 一手资料。研究不覆盖 accepted ADR。
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
| Engine 管持久和 live 生命周期 | [TurnEngine](https://github.com/dollarser/helix-agent/blob/9965d2dff694441458b4036942c4b8b9749f0f8e/app/src/main/kotlin/com/helix/app/engine/TurnEngine.kt)、[TurnExecutionDriver](https://github.com/dollarser/helix-agent/blob/9965d2dff694441458b4036942c4b8b9749f0f8e/app/src/main/kotlin/com/helix/app/engine/TurnExecutionDriver.kt) | 准入、取消、恢复、review 和 terminal 同一权威；terminalize 先持久结算，再释放 live owner、发布结果 |
| Core 与宿主之间有明确适配 | [AgentLoopHost](../../../app/src/main/kotlin/com/helix/app/agent/AgentLoopHost.kt)、[TurnCoordinator](https://github.com/dollarser/helix-agent/blob/9965d2dff694441458b4036942c4b8b9749f0f8e/app/src/main/kotlin/com/helix/app/agent/TurnCoordinator.kt)、[ChatToolCalls](https://github.com/dollarser/helix-agent/blob/9965d2dff694441458b4036942c4b8b9749f0f8e/app/src/main/kotlin/com/helix/app/chat/ChatToolCalls.kt) | 分别装配 Core ports、承载 round checkpoint、适配工具批次与持久结果；保持原 Room 事务 |
| UI 已用应用服务和读投影，但仍有宽依赖 | [ChatScreen](../../../app/src/main/kotlin/com/helix/app/ui/ChatScreen.kt) 的参数与 DisposableEffect | 订阅 StateFlow；recoveryModelsNavigation / recoveryPermissionsNavigation 写入 ChatService，是具体表现层耦合，不是已复现泄漏或卡顿 |
| core 目录包含 Android 基础设施 | [模块依赖声明](../../../build.gradle.kts) | model/agent/policy/workspace 是 JVM，storage 是 Room Android Library；模块名字不能证明可独立部署 |
| 工具调度与授权分工已经存在 | [ToolScheduler](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolScheduler.kt)、[ToolDispatcher](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDispatcher.kt) | Scheduler 负责有界调度和原顺序结算；Dispatcher 负责绑定/schema、实时能力/策略/会话授权、精确批准及开始执行前复检 |
| 超时与实际退出已分开 | [ToolDeadlineRunner](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDeadlineRunner.kt)、[ExecutionOwnership](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ExecutionOwnership.kt) | 超时/取消只尝试中断；执行线程未退出仍占物理容量，原执行身份另按 Runtime 证据对账；不可将观察槽位释放误判为物理容量释放 |

系统权限、插件选择、就绪投影、会话操作策略与精确审批分别解决不同问题，最终在原 Dispatcher 汇合。权限的多层事实有必要保留；可以合并重复展示推导，不能用一个 ready/allowed 布尔值覆盖全部含义。手动浏览、文件管理和设置仍可通过各自应用服务工作，无需先建立 Turn。

### 9.3 优化建议与任务去重

下表登记当时建议的归属；[status](../../development/status.md#已交付与历史证据)提供后续交付入口，不由历史映射选择当前工作。本次不新增 HXA 或 ADR 编号。

| 建议 | 承接 | 具体增量 / 退出标准 |
| --- | --- | --- |
| 插件内容、调用资格和生效状态统一 | HXA-272～275 | 沿用原任务范围；274 追加恢复目标数据化与当前页面导航处理，替换所涉 ChatService 页面回调，保留271已交付产品行为 |
| Browser 统一贡献与共享资源生命周期 | HXA-276、277 | 277 追加四类 owner 映射与跨层取消/退出场景：Turn、调度槽位、实际执行、插件引用；接入原 Runtime 事实，不重复执行注册表 |
| Skill 上下文和 UI 观察质量 | HXA-278、279 | 按原契约验证内容失效/压缩一致性、标签发现和有界等待；模型收益以固定任务对照证明 |
| 应用命令和读模型持续收窄 | HXA-274 的恢复展示切片先实施；其余随有实际需求的原任务处理 | 原用户动作身份、门禁、队列、Room 和 Engine 保持单一；没有消费者时不造通用 facade/event bus，不以 ChatService 行数或空壳化验收 |
| 完整 Turn 宿主提取 | [HXA-287](../../development/tasks/HXA-287.md)，2026-10-15已排期 | 原10月11日要求新消费者；本次评审以 App/Room 解耦和完整无页面测试为实际需求，与应用命令合并目标。尚未实施，不预建 CLI/远程 Server |

已交付工作保留原归属：HXA-220/223 是 Engine owner 与 live driver，HXA-234 是 Core 迁移，HXA-254/255 是项目/权限应用接口，HXA-256 是生产测试桥，HXA-257/258 是会话状态与事务；这些均不重复登记。HXA-271 的授权恢复及开放设备验收仍归原任务，274 只扩展投影及承载接口；HXA-235 的安装/快照引用仍由原任务负责，277 只增加原生资源。验收事实以各任务和当前 status 为准。

### 9.4 实施取舍与验证边界

按单一目标实现替换受影响入口；不安排旧版本/旧数据库/旧会话/旧批准迁移，不留双注册、双写或兼容 adapter。本次文档修改不操作持久数据。当前合法调用的幂等、取消、UNKNOWN、原顺序回填与渠道规则仍须保留。

实施时先完成独立主机切片，再按当轮授权执行设备/模型验收；UI 重建/无订阅、迟到回调、权限撤销和 executor 未退出分别覆盖。结构收敛只能证明边界更明确，性能、任务完成率、OEM 与长稳改善必须另有测量。本次没有发现并复现新的运行故障，不能借本节自动关闭或重跑全部历史问题。

## 10. 2026-10-14 执行边界与新目标设计复核

**2026-10-15 后续承接：**Owner 已要求按本次评审优化文档与任务，原主题 ADR 和 HXA-286～294 已同步；B+E 合并目标、C+D 产物共同接线，Memory/上下文独立归属，完整 Turn 不再等待第二消费者。开发期不新增历史库迁移链，当前版本原子性/恢复继续验证；具体范围见 [§23.7](../../architecture/harness-refactor-plan.md#237-合并设计边界按完整路径替换)。以下10月14日记录保留当时“仅提案、未改ADR/排期”的事实，不再代表最新设计状态。本次未改代码，按 Owner 要求未运行文档门禁。

### 10.1 范围和证据身份

Owner 要求评估“接口明确、默认同进程、Memory/插件各守职责”的方案，并明确新设计不兼容旧方案或旧接口，以当前合理性及扩展成本为目标。本轮交付研究与设计，不实施生产代码迁移、不建立新的执行域，也不把设计标成 accepted。新方案统一写入[Harness 方案 §23](../../architecture/harness-refactor-plan.md#23-2026-10-14-执行边界重构提案)。

核验起点为 `main/dd36b673588aa7ee5e78030da7b3674c0c239168` 加当时未提交工作区。开始时 HXA-284 正在改造 documents 插件及请求、图片、存储链路；结束前并行所有者将其提交为 `14824251bc8578162ff4c7ed5ba61bb66c9014c3`，status 已记录其主机切片交付。本轮复核所记录的九个关键源码 SHA-256 全部仍一致；该并行提交及测试不归本次交付。先记录 `git status` 和完整已有 diff，再使用 CodeGraph 的 query/callers/callees，并核对当前原文；索引显示 up to date 仍不替代源码。根 AGENTS 适用，未发现嵌套 AGENTS，`.agents/skills` 不存在。基线和选定源码 SHA-256 留在忽略目录 `build/execution-boundary-review-2026-10-14/`；没有改动本机历史记忆或并行源码。

### 10.2 当前已实现与剩余接缝

| 事实及源码 | 对新方案的影响 |
| --- | --- |
| [AgentLoop](../../../core/agent/src/main/kotlin/com/helix/core/agent/AgentLoop.kt) 的 runToolRound 调用 [AgentToolGateway](../../../core/agent/src/main/kotlin/com/helix/core/agent/AgentToolGateway.kt)；Core 不依赖 App/Room | 不是从零“抽出 Agent”；复用唯一 Loop。Gateway 还混有 prepareModelCalls、loopProgress/resetLoopProgress，可按实际消费者分出绑定准备和循环事实端口 |
| [ToolExecution](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolExecution.kt) 已有 ExecutableToolCall：调用身份、参数、target、deadline、cancel、会话/Turn、scopeRef；[ToolDispatcher](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDispatcher.kt) 已做准入、执行前复检、结果和审计 | “先建统一执行请求”不能重复立项。需要的是收紧现有边界：治理后的身份非空、执行目标精确、授权引用与后端约束分清；目标信息不是模型参数 |
| [ChatToolCalls](https://github.com/dollarser/helix-agent/blob/9965d2dff694441458b4036942c4b8b9749f0f8e/app/src/main/kotlin/com/helix/app/chat/ChatToolCalls.kt) 同时持 Room、Scheduler、审批卡、MutableStateFlow ChatScreenState、字符串资源；executionStarting 同时写 RUNNING 和发布 UI | 有具体表现层依赖，支持提取工具调用应用服务及独立投影；不能把持久开始屏障和卡片回调原样搬到一个“通用执行器” |
| Dispatcher 的 isCompletion/startObservation 判断 JobObservationExecutor / JobLaunchWaitExecutor；[JobLaunchWaitExecutor](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/JobLaunchWaitExecutor.kt) 从输出 JSON 读取 originalCallId | 现有 J1 可用不等于所有 backend 已可替换。新目标可用可信注册的调用形态与类型化续接事实替代具体类分支和 JSON 协议反解，不让 MCP metadata 自授观察/控制特权 |
| [ToolDeadlineRunner](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDeadlineRunner.kt)、[ExecutionOwnership](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ExecutionOwnership.kt)、[ToolExecutionActivity](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolExecutionActivity.kt) 已区分调用结算、原执行身份和物理退出 | 不建立第二份执行注册表；suspend/Future 完成、取消和 Turn terminal 都不自动释放原执行容量 |
| [TurnEngine](https://github.com/dollarser/helix-agent/blob/9965d2dff694441458b4036942c4b8b9749f0f8e/app/src/main/kotlin/com/helix/app/engine/TurnEngine.kt)、[TurnExecutionDriver](https://github.com/dollarser/helix-agent/blob/9965d2dff694441458b4036942c4b8b9749f0f8e/app/src/main/kotlin/com/helix/app/engine/TurnExecutionDriver.kt) 依赖 HelixStorage 和 App collaborators；[AppAgentRuntime](../../../app/src/main/kotlin/com/helix/app/chat/AppAgentRuntime.kt) 回调原提交/取消命令 | “完整 Turn 宿主与具体数据库解耦”仍是真实增量，远大于替换工具 DTO；优先定义事务命令端口，不复制 Room 到新数据库或内存状态机 |
| [MemoryService](../../../app/src/main/kotlin/com/helix/app/memory/MemoryService.kt)、[MarkdownMemoryStore](../../../core/workspace/src/main/kotlin/com/helix/core/workspace/memory/MarkdownMemoryStore.kt)、[MemoryContext](../../../core/workspace/src/main/kotlin/com/helix/core/workspace/memory/MemoryContext.kt) 已分开管理、正文与摘要装载；[MemoryTools](../../../app/src/main/kotlin/com/helix/app/memory/MemoryTools.kt) 进入 Dispatcher | 不新建 Memory 服务或搬进 Executor。可把 MemoryContext 对具体 store 的依赖替换为有界文档快照；项目身份、开关、hash 冲突、路径限制继续在原领域落实 |
| [HelixPlugin / PluginRegistry](../../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/PluginRegistry.kt)、[NativeResource](../../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/NativeResource.kt) 已有贡献与真实资源引用；[MobileUsePresentedExecutor](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/MobileUsePresentedExecutor.kt) 组合原执行、浮窗与引用；[AutomationGestureDispatch](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/automation/AutomationGestureDispatch.kt) 等实际回调释放 | Mobile Use 已在插件中，不重做 HXA-277，不拉回宿主。新生命周期必须保留插件取消新准入和旧引用排空的区别 |
| [VisualArtifact](../../../core/model/src/main/kotlin/com/helix/core/model/VisualArtifact.kt) 已是文件引用＋哈希；[WorkspaceToolImagePublisher](../../../app/src/main/kotlin/com/helix/app/vision/WorkspaceToolImagePublisher.kt) 在 HXA-284 提交后仍按 documents.view 名称决定内部观察用途 | 不发明第二视觉通路。产物用途应由可信生产者以类型表达；以后续切片替换现有目的分类，不重做 documents 基础 |
| [ExecutionTarget](../../../core/model/src/main/kotlin/com/helix/core/model/ExecutionTarget.kt) 注释仍把 PRoot/CLI 写成独立 APK/UID，与 accepted [Runtime ADR](../../adr/runtime/001-execution-domains.md) 不符 | 是本轮确认的说明债务，不能据此设计错误的隔离保证；迁移时一并清理。本轮未据此声明运行故障 |

### 10.3 主流 Agent 一手资料重新核验

下表资料于 2026-10-14 重新读取。官网与 `main/master` 链接是读取时的公开文档/源码，不是已固定发布版本；没有运行这些产品做性能对照，不将公开 Codex 源码当作本机私有桌面版本的实现证明。研究只采用结构与语义，不复制第三方实现。

| 对象与一手证据 | 核验事实 | Helix 采用与限制 |
| --- | --- | --- |
| Codex：[App Server 工程说明](https://openai.com/index/unlocking-the-codex-harness/)、[ToolOrchestrator 源码](https://github.com/openai/codex/blob/main/codex-rs/core/src/tools/orchestrator.rs) | Core 包含循环、持久化与工具；App Server 是面向客户端的协议/宿主。工具编排集中审批和执行环境选择，委托具体 ToolRuntime | 区分 Agent API、工具治理与后端接口；复用一套循环。多个客户端的协议需求不推出 Helix 现在必须开 Server，也不照搬沙箱失败后的重试策略 |
| Claude Code：[运行机制](https://code.claude.com/docs/en/how-claude-code-works)、[Memory](https://code.claude.com/docs/en/memory)、[插件](https://code.claude.com/docs/en/plugins) | Harness 管工具和上下文；记忆有独立 Markdown 索引及按需主题；插件组织 Skill、Agent、hooks、MCP 等组件 | 保留独立模型工具、记忆与内容装载职责。Helix 沿用自身显式 Project、用户开关和不可信标记，不借“都是文件”合并授权；不新增 hooks/子 Agent |
| Gemini CLI：[Core 文档](https://github.com/google-gemini/gemini-cli/blob/main/docs/core/index.md)、[工具参考](https://geminicli.com/docs/reference/tools/) | CLI 表现与 Core 区分；Core 管模型、上下文和工具；ToolRegistry 管注册 | 支持逻辑分层与统一注册，不意味着所有工具都需独立服务。Helix 已有 Core/Registry，增量不能重新算作基础建设 |
| OpenCode：[Server](https://opencode.ai/docs/server/) | TUI 与 HTTP Server 分离，公开会话控制和 SSE 事件接口，支持多客户端 | 借鉴命令与观察分开；Server 是部署选择，不是模块清晰的必要条件。Helix 先同进程应用端口，不新增监听端口或远程认证 |
| DeepSeek Harness：[Session](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/session.md)、[Persistence](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/persistence.md) | 持久化端口复用原 SessionEvent；单写句柄与 flush 屏障明确；恢复由 reader 补 interrupted closers，不重新执行旧工具 | 借鉴单一事实、持久屏障和生命周期 owner；Helix 保留 Room 及 Runtime journal，不改造成平行 JSONL 日志。一次内部事务不代表外部效果可回滚 |

结论：原建议方向成立，但 Helix 已完成大部分基础分层。新设计应针对真实接缝替换内部接口，而非添加名字不同的第二层 Gateway；已有隔离必须保留。“可扩展”通过精确身份、独立能力、结果事实和契约测试实现，不能等价为提前构建远程服务。

### 10.4 交付与审查记录

本轮只修改本综合研究、既有 Harness 方案和架构导航，没有修改 status 的实施顺序或 HXA-284 范围。新接口、迁移顺序及验收矩阵见方案 §23；后续实施时在原 Agent/Tools/Permissions/Runtime/Memory/Plugin ADR 更新有效正文与 Decision history，不把本研究直接升级为生效协议。

验证只包含文档/ADR 门禁与差异检查：`check-docs.sh` 通过（859 份 Markdown、267 项 HXA），`verify-adr.sh` 通过（37 份当前 ADR），`git diff --check` 通过；日志保存在 `build/execution-boundary-review-2026-10-14/`。本轮未运行 JVM/编译、模拟器、真机、真实 Provider 或 FFmpeg/PRoot 媒体端到端；没有生产行为变化或性能改善的验收结论。历史验收不转记到本设计。剩余事项是新增契约的 ADR 接受和分阶段实现/回归，不是已经确认的新运行缺陷；HXA-284 共享文件实施前须重新核对。未提交或推送。

### 10.5 同日审核：目标架构与完整 Turn 范围

Owner 提供另一份审核，要求评估“大幅重构可以接受、目标先于迁移规模、完整 Turn 宿主不必等待第二个消费者”。核对结果：删除兼容入口、单一治理、分阶段完整路径替换已在 §23；需修正的是“首轮不新增模块”和“首版默认只到 A～D”的预设。这些约束不能替代对合理终态的判断，现已修改原方案正文，未新增并行方案。

在 `main/14824251` 再次通过 CodeGraph 定位并读取当前 TurnEngine、TurnExecutionDriver、AgentTurnStore、AppAgentRuntime 和 ChatService：Engine/Driver 仍依赖 HelixStorage，AgentTurnStore 当前仅封装终态提交，既有统一 AgentRuntime 的提交/取消实现仍由 ChatService 回调提供。现有无页面测试、页面/通知/插件浮窗接入和持久边界本身构成重构价值，不需假设第二个产品或远程宿主。这里确认的是耦合及验证边界，不把它描述成已发生的执行故障。

方案现以 A～E 为完整目标：A 共同确定工具/执行/Memory/产物/Turn 边界，模块可按依赖重组，E 覆盖完整 Turn 领域宿主；中间交付不冒充整体完成。补充完整无页面契约、真实 Room 适配和依赖检查；数据结构可以调整，但须保留当前数据、原授权/执行身份及 UNKNOWN 事实，不能从“无旧接口兼容”推导清库权限，也不能把回退 APK 等同回滚数据库或外部效果。远程协议、新执行域、永久过渡层仍无新增需求。

本次仅更新 Harness 方案和本节研究记录，保留开始时所有已有文档/脚本改动；status/ADR 的当前实施范围未变，完整 Turn 重构仍是提案。增量基线与门禁日志放在 `build/execution-boundary-review-2026-10-14/large-refactor-review/`；没有运行生产代码迁移、JVM/编译、设备、真实模型或媒体端到端验证。

### 10.6 2026-10-17 个人 Agent 产品的部署边界复核

下表记录官方公开说明，不代表本项目执行过这些产品的账户或安全验证，也不将产品宣传作为效果测量。

| 来源 | 已核实的公开行为 | Helix 取舍 |
| --- | --- | --- |
| [OpenAI dots](https://openai.com/index/introducing-dots/) | 默认使用自己的云电脑，可经用户许可连接笔记本；网页、桌面、移动端均可交互，任务支持持续跟进 | 控制界面、模型推理与执行位置分开表达；有手机入口不代表在手机执行，不从“持续工作”推导 LLM 必须常转 |
| [Grok Bot computer](https://docs.x.ai/grok-bot/computer-and-apps)、[mobile](https://docs.x.ai/grok-bot/mobile) | 账号内 Bots 共享持久云电脑的文件/浏览器状态；移动端连接同一工作面，关 App 不停止云端任务；单独屏幕不是安全隔离 | 资源共享、会话范围与权限边界必须具体说明；不照搬其账号共享范围到 Helix 对话授权 |
| [Meta Muse 架构说明](https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse) | 专属 VM 中运行 agent cell；持久状态、凭据、权限 authority、推理/网络代理分工，客户端连接 VM | 借鉴明确事实 owner 与授权边界；Android 私有进程/共享 UID 不等于其 VM、内核和凭据隔离，不以抽接口宣称隔离升级 |

本项目判断：Helix 当前仍以手机本地控制与持久事实为中心，模型可以远端推理；Agent 调度、执行资源与 UI 按现有 ADR 分责。持续任务由持久状态、系统允许的唤醒/运行窗口和通知驱动；任务事实、长期 Memory 与临时上下文分别管理，模型总结不能升级为执行事实。远程 transport 只有在具体获准用户旅程需要时才推进认证、身份、重连与结果归属，不先建设 VM/云电脑平台。以上为对既有 286～288 方向的研究补充，本轮不增加部署域或实现大重构。

> 2026-10-24 目录迁移说明：已删除旧类的链接指向公开 v0.0.7 发布基线，供追溯该旧路径；不证明当年未提交快照逐字可重建。当前替换入口和验收以 [HXA-287](../../development/tasks/HXA-287.md) 为准，旧结构不再是现行规范。

旧 App Engine 源码入口固定到公开 v0.0.7，供历史结构导航，不表示逐字恢复审查时的未提交代码；当前落点见[实施状态](../../development/status.md)。
