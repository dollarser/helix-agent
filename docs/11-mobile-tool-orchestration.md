# Helix 手机端 Tool 编排方案

文档状态：Baseline 1.3 补充规范
基线日期：2026-08-31

## 1. 目标

Helix 可以借鉴 Codex、DeepSeek Harness 等 Agent Harness 的编排思想，但手机端的约束不同：内存、CPU、热量、电池、后台存活和网络费用都更紧，且 Android 权限、Accessibility、Root 与独立 Runtime 不能抽象成桌面 shell 的单一“sandbox level”。因此优先实现可证明的安全与恢复原语，而不是追求最大并发或 Agent 数量。

本文件只定义推荐路线，不把未来功能写成当前实现。核心单 Agent Tool Loop 归 HXA-030～037；有界委托与声明式 Workflow 由 HXA-105 和 proposed [ADR-0009](adr/0009-bounded-local-orchestration.md)决定。远程 Worker、云端任务舰队仍不在当前范围。

## 2. 采纳矩阵

| 外部编排能力 | Helix 建议 | 手机端落法 | 任务 |
| --- | --- | --- | --- |
| 统一 approval → execution target → attempt → verify → audit 管道 | **首版采纳** | Dispatcher 唯一入口；target 在 approval hash 中，失败不回退到低隔离执行域 | HXA-035 |
| 参数级并发安全分类、读并行/写屏障 | **首版采纳** | 由 Helix 根据规范化参数生成 effect footprint；模型/MCP annotation 不能自报安全 | HXA-037 |
| 有界并发池、取消与按模型顺序回填 | **首版采纳** | 默认并发 2，候选硬上限 4；QuickJS/PRoot/Root/UI 动作各自单并发；完成时间单独审计，模型上下文按 call sequence 提交 | HXA-037 |
| model-visible ⇔ persisted/logged、回放恢复 | **首版采纳** | 任何进入模型的 ToolResult、用户回答、委托结果和 compaction summary 都必须可由持久事件重建 | HXA-035/037/102 |
| 分阶段 timing、decision source、correlation ID | **首版采纳** | 记录 queue/approval/execution/verification 时间和 Policy/User/Recovery 来源，不记录敏感正文 | HXA-035/037 |
| 结构化用户提问与迟到 receipt 拒绝 | **采纳** | 问题绑定 turn/request/version；已取消、已回答或状态变化后的答复不生效；提问不代替审批 | HXA-036/037 |
| 上下文预算、确定性截断/压缩 | **已选方向，继续强化** | 复用 Context Builder；只压缩已持久化内容，保留 hash/ref/trust，不能丢当前审批参数 | HXA-016/037/102 |
| 持久 Goal、预算、检查点 | **已采纳** | 复用 ADR-0004；不再另造 ralph/无限自治循环 | HXA-013/015/102 |
| 子 Agent 树、fork、完成回流 | **后期受限采纳** | 首版候选仅 Advanced 的只读委托：深度 1、并发 2、每 Turn 最多 4 个 child，共享父预算 | HXA-105 / ADR-0009 |
| Agent 间任意通信、递归群体编排 | **不采纳首版** | 只允许 parent ↔ child 的结构化任务/结果，不开放 peer 消息和递归派生 | HXA-105 |
| Workflow pipeline/parallel/phase | **只采纳声明式子集** | 若 Spike 成立，使用有版本 JSON DAG；节点仍经过 Dispatcher，不执行用户/模型提供的编排脚本 | HXA-105 / ADR-0009 |
| 可执行 JS/Starlark Policy/Workflow DSL | **不采纳** | Policy 使用封闭类型和代码审查过的规则；Skill/模型不能安装策略代码 | — |
| self-modification/Agent 自挂插件 | **不采纳** | 扩展只能由用户导入并验证的 MCP/Skill 提供，不能修改安全内核或 Tool Registry 所有权 | — |
| sandbox escalation retry | **不照搬** | 不因失败扩大 Android 权限、scope、网络或切换到低隔离 target；target/参数变化生成新审批。只允许零副作用、同 envelope、同/更强隔离的有界技术重试 | HXA-035/037 |
| deferred network approval（先连接/发送后补批） | **禁止** | DNS/连接/发送前完成 origin、数据类别、scope 和审批；网络失败不能变成授权 | HXA-033/035/066 |
| 本地创建云端任务、轮询并 apply diff | **当前不采纳** | 属于 Remote Worker/云端执行与数据出境，需未来新执行目标、威胁模型和 ADR | — |
| ACP/SDK/外部 Agent 配置迁移 | **延后** | 不影响单机 MVP；待本地协议稳定后再评估只读导入/导出 | — |
| 跨会话 memories | **延后且默认关闭** | 必须用户可见、可删、按数据类别授权；Secret 与高敏原文不得自动沉淀 | future ADR |

## 3. 首版确定性 Tool Scheduler

### 3.1 Effect footprint

每个已规范化 ToolCall 在 Policy 后、执行前生成不可由模型覆盖的 `EffectFootprint`：

```kotlin
data class EffectFootprint(
    val operationClass: ToolOperationClass,
    val executionTargetId: ExecutionTargetId,
    val scopeIds: Set<ScopeId>,
    val resourceKeys: Set<String>,
    val originKeys: Set<String>,
    val exclusive: Boolean,
)
```

`resourceKeys` 使用平台实现生成的稳定键，例如 Workspace canonical path、SAF document ID、browser tab/generation、Accessibility package/window、calendar/account 或 Runtime job lane。不能让模型、MCP annotation 或 Skill 的 `isConcurrencySafe=true` 直接决定并发。

仅当两个调用都被证明为只读、effect footprint 不冲突、执行域允许并发且共享输出预算仍有余量时才能并行。任何未知 footprint、写入、删除、代码执行、Root、Accessibility 动作、同一浏览器 tab 动作或同一 Runtime lane 默认排他。多个写操作即使路径不同，首版也可保守串行；以后放宽需要竞争测试证据。

### 3.2 顺序与取消

- 调度可以并行开始安全调用，但进入模型上下文的 ToolResult 必须按原始 `callId/sequence` 提交，避免完成速度改变推理历史。
- 每次调用记录 `QUEUED → WAITING_APPROVAL → RUNNING → VERIFYING → terminal`；queue、审批等待、执行和验证耗时分开。
- 用户取消后，未启动项得到持久 `ABORTED_BEFORE_START` 结果；已启动项收到 cancel，并等待 terminal/unknown outcome 对账。不能直接丢弃行。
- 一个并行项失败不自动取消已产生外部副作用的其他项；未启动的依赖项按 DAG/序列标为 `SKIPPED_DEPENDENCY`。
- 内存压力、前后台切换或热限制可以降低并发到 1，但不能提高审批权限或改变结果顺序。

### 3.3 Attempt 与重试

一个 ToolCall 可有多个 `attemptId`，但重试必须有稳定理由和硬上限。以下任一变化都必须创建新 ToolCall/approval，不能复用旧批准：参数、scope、origin、数据类别、代码/命令、execution target、页面/UI token、Android 权限需求或副作用类别。

只允许在确认前一 attempt 没有发生副作用时，对相同 envelope 做有限技术重试，例如 Binder 在接受 Job 前死亡、连接尚未发送请求、或换用同 target 内更严格的资源限制。不得从 QuickJS/PRoot 失败回退到主 App shell，不得因 sandbox/权限拒绝自动请求 Root、All-files、Accessibility、LAN 或更宽目录。

## 4. 持久事件与用户交互

必须满足：**任何 model-visible 输入都可由持久事件和内容 hash/ref 重建**。至少覆盖 ToolCall/attempt/Result、Approval、Policy decision source、结构化用户回答、委托结果、compaction summary、取消和恢复结论。瞬时遥测可以不进入模型，但不能成为恢复语义的唯一来源。

结构化用户问题用于补齐缺失意图，不用于伪装审批。每个 request 绑定 session/turn/requestId/version/expiry；答案以一次性 receipt 消费。状态已经推进、问题已取消、版本变化或迟到回复均返回稳定 `NOT_PENDING`，不能覆盖新状态。

## 5. 有界只读委托（后期候选）

子 Agent 对手机端有价值的场景是并行网页研究、多个文件/模块的独立只读检查、候选方案比较和 verifier 复核；不适合用来并发执行写入、UI 动作或 shell。

首版候选边界：

- 仅 developer/Advanced 实验入口；Standard 保持单 Agent，避免普通用户承担额外费用、功耗和复杂度。
- 最大深度 1、并发 child 2、每父 Turn 最多创建 4 个；模型调用、token、墙钟和 Tool 次数全部计入父 Goal/Turn 预算。
- child 得到自包含任务与最小只读 context snapshot，或只继承父会话已完成且经过确定性截断的轮次；不继承 pending approval、Secret、UI token、Root/Automation session 或可写 capability。
- child 只注册 `READ_ONLY` 且动态风险不高于 L1 的工具；不能请求/持有 Approval Proof，不能执行 L2/L3。需要变更时只返回 proposal，由父 Turn 重新构造 ToolCall 并走正常审批。
- 首版只有 parent → child 任务、parent → child cancel 和 child → parent structured result；不提供 peer-to-peer 消息、递归派生或后台无限续话。
- Agent graph、状态、父子关系、预算占用和 completion result 持久化。完成消息带 source/trust/hash/evidence refs，不能把 child 自述当 verifier 证据。

这部分必须经过 HXA-105 与 ADR-0009 接受后才实现；不得因为参考工具已有 subagent API 就提前加入普通 Agent 工具表。

## 6. 声明式 Workflow（后期候选）

如果 HXA-105 证明用户任务确实需要重复编排，只考虑有版本、可校验、可审计的 JSON DAG，提供 `tool`、`readOnlyDelegate`、`barrier`、`condition`、`verifier` 等封闭节点。所有节点必须编译为普通 ToolCall/委托请求并经过相同 Policy、Approval、预算、取消和恢复管线。

首版不执行模型生成的 JS/Starlark 编排脚本，不允许 Workflow 动态安装插件、改 Policy、创建 scope、切换 Profile 或展开无上限循环。Goal 已承担跨轮自治；Workflow 不再复制一套 ralph/goal 生命周期。

## 7. 发布阻断条件

- 任一 L2/L3 调用在没有对应 Approval Proof 时执行。
- 并行结果按完成顺序而非模型 call sequence 进入上下文，导致同一 fixture 非确定。
- 未启动调用在取消/崩溃后消失，或恢复时被自动重放。
- 调度失败后回退到更低隔离执行域，或自动扩大权限/scope/origin。
- 网络在完成 origin/数据类别/scope 门控前建立连接或发送数据。
- child 获得写工具、Approval Proof、Secret、Root/Automation session，或预算未计入父级。
- 可执行 Workflow/Policy 脚本绕过 Tool Registry、Policy、Approval 或审计。
- 云端任务/diff apply 在没有 Remote Worker ADR、数据出境说明和本地二次验证时进入产品。

## 8. 参考映射原则

外部项目名称、源码位置和行为会变化，进入 HXA 时按 [开源参考 §5.11](06-open-source-references.md#511-主流-coding-agent--agent-harness-设计参考)重新核实。Helix 采纳的是可验证 invariant——确定性顺序、有界并发、单一安全管线、持久回放、预算与取消——不是复制其桌面权限、云端基础设施或插件模型。
