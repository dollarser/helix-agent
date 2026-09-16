# Helix 架构研究图集

> 核对日期：2026-09-16；源码基线：`main` 的 `782a70424fa3430f62067e0abb1024f246d8aebf`，核对开始时工作树干净。
> 与[重构研究正文](helix-agent-complete-research-and-product-plan.md)配套。图集解释职责、状态与执行域，不替代[架构规范](../architecture/overview.md)、[ADR](../adr/README.md)或[任务索引](../development/roadmap.md)。实现和验收进度以[状态文档](../development/status.md)为准。

## 1. 阅读约定

| 标记 | 含义 |
| --- | --- |
| 【实】 | 当前源码已有，不代表所有端到端验收完成 |
| 【计】 | 已接受的方案，相关 HXA 尚待交付或验收 |
| 【研】 | 尚不能据此实施的研究候选 |
| 【外】 | Android 或外部服务 |

每幅图独立说明时态。实线表达该图中的控制或数据流；虚线表达观察、约束或待接入关系，不代表权限继承。框可以代表职责，不要求新增同名模块。省略的内部步骤不表示可以绕过现有门禁。

## 2. 已实现：入口与状态所有权

统一入口已有 `AgentRuntime` 契约和 `AppAgentRuntime` 接线；后续应按实际依赖收敛实现，不能再以“新增统一入口”为起点。手动文件管理、浏览器和能力安装保留各自服务路径。

```mermaid
flowchart TB
    UI["【实】会话 / Goal 入口"] --> API["【实】AgentRuntime<br/>submit / cancel / observe"]
    API --> APP["【实】AppAgentRuntime / 运行协调"]
    APP --> TURN["【实】Turn 事务与取消"]
    APP --> LOOP["【实】AgentLoop<br/>Chat / Plan / Act / Goal 共用"]
    LOOP --> MODEL["【实】模型请求组装与 Provider"]
    LOOP --> TOOLS["【实】工具调度 / Dispatcher"]
    TURN --> DB["【实】持久会话 / Turn / Goal / 调用结果"]
    TOOLS --> DB
    DB -.-> TASKS["【实】任务与产物投影"]
    TASKS -->|"取消 / 继续等显式动作"| API
    FILEUI["【实】手动文件管理"] --> FILES["【实】文件应用服务"]
    WEBUI["【实】浏览器界面"] --> WEB["【实】BrowserController / WebView"]
    SETUP["【实】能力设置"] --> SERVICES["【实】验证 / 安装 / 修复服务"]
    SERVICES --> OS["【外】Android 系统授权"]
```

源码：[入口契约](../../core/agent/src/main/kotlin/com/helix/core/agent/AgentRuntime.kt)、[应用接线](../../app/src/main/kotlin/com/helix/app/chat/AppAgentRuntime.kt)、[共享循环](../../app/src/main/kotlin/com/helix/app/agent/AgentLoop.kt)。任务界面是持久事实的投影，不再维护一套独立任务生命周期。

## 3. 已实现：请求、Prompt 与上下文

`PromptRegistry` 与生产请求组装已存在。排序和作用域不提升来源权限；Skill、项目文件、网页和外部工具结果不能因进入模型上下文而变成授权。下图是逻辑组装关系，不要求把各类内容都注册为系统 Prompt。

```mermaid
flowchart TB
    BUILTIN["【实】内置 Prompt sections"] --> REG["【实】PromptRegistry<br/>固定顺序与作用域"]
    REG --> ASM["【实】ChatRequestAssembler"]
    HIST["【实】持久历史 / 摘要"] --> ASM
    ATT["【实】附件恢复 / 图片绑定"] --> ASM
    EXT["【实】项目 / Skill / 外部内容<br/>保留来源与不可信边界"] --> ASM
    EXP["【实】模式工具曝光<br/>MCP search 与会话窗口"] --> ASM
    ASM --> CHECK["【实】请求前压缩检查与预算准入"]
    CHECK -->|"需要压缩"| SUMMARY["【实】摘要结算"]
    SUMMARY -->|"重建上下文"| ASM
    CHECK -->|"可请求"| PROVIDER["【实】ModelProvider"]
    PROVIDER --> RESULT["【实】响应 / 工具结果持久回填"]
    RESULT --> HIST
```

源码：[PromptRegistry](../../core/agent/src/main/kotlin/com/helix/core/agent/PromptRegistry.kt)、[Assembler](../../app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt)、[压缩轮次](../../app/src/main/kotlin/com/helix/app/agent/ContextCompactionRound.kt)。后续优化须保留工具调用配对、图片归属、取消与错误结果；旧 `ContextBuilder` 不应被重新引入替换生产路径。

## 4. 已实现：每次工具调用的结算职责

这是现有控制点的逻辑视图，不是精确线程时序。当前审批实现与 HXA-209 的目标策略必须区分；不能把本图理解为所有调用都必须弹卡或消费证明。

```mermaid
flowchart TB
    CALL["【实】模型 ToolCall"] --> NORMAL["【实】schema / 参数归一化 / effect footprint"]
    NORMAL --> QUEUE["【实】Scheduler<br/>仅确定不冲突的读取可并行"]
    QUEUE --> DISPATCH["【实】Dispatcher<br/>Policy / 当前偏好 / 能力检查"]
    DISPATCH --> DECISION{"执行决定"}
    DECISION -->|"拒绝"| SETTLE["【实】逐调用持久结算 / 审计"]
    DECISION -->|"需要确认"| WAIT["【实】审批等待"]
    DECISION -->|"已满足授权"| START["【实】执行开始前复检 / 取消检查"]
    WAIT -->|"批准"| START
    WAIT -->|"拒绝 / 取消 / 异常"| SETTLE
    START -->|"需要证明时才消费<br/>精确绑定的一次性 proof"| EXEC["【实】执行与限额"]
    START -->|"不再允许 / 已取消"| SETTLE
    EXEC -->|"成功 / 失败 / 取消 / 未知副作用"| SETTLE
    SETTLE --> ORDER["【实】按原调用序列回填模型"]
```

队列取消、审批等待取消和执行后的未知副作用均需留存结果。审批通过不等于执行已开始；证明在执行开始时消费。恢复不可把未知结果自动重放成成功。

## 5. 已接受待交付：HXA-209 会话授权

依据 [ADR-PERMISSIONS-001](../adr/permissions/001-session-authorization.md) 与 [HXA-209](../development/tasks/HXA-209.md)。这不是当前旧 resolver 已完成迁移的证明。应先证明执行域能落实限制，再接入预设和自定义策略。

```mermaid
flowchart TB
    ENABLE["【计】工具 ENABLED / DISABLED"] --> EXPOSURE["【计】会话曝光过滤"]
    CALL["调用到达执行入口"] --> LIVE["【计】重新检查工具启用与当前约束"]
    ENABLE --> LIVE
    PRESET["【计】FULL_ACCESS / WORKSPACE / READ_ONLY<br/>三者允许 Agent 工具联网"] --> RESOLVE["【计】按规范化效果求值"]
    CUSTOM["【计】CUSTOM<br/>各效果 ALLOW / ASK / DENY"] --> RESOLVE
    LIVE --> RESOLVE
    RESOLVE --> PRIORITY{"DENY 优先于 ASK<br/>ASK 优先于 ALLOW"}
    PRIORITY -->|"DENY / 工具禁用"| DENIED["拒绝并结算"]
    PRIORITY -->|"ASK"| CARD["精确确认"]
    PRIORITY -->|"ALLOW"| DELETE{"明确解析到 rm -rf dir？"}
    DELETE -->|"是"| CARD
    DELETE -->|"否"| START["执行前重检当前约束"]
    CARD -->|"批准且绑定有效"| START
    START --> EXEC["受约束执行 / 持久结算"]
```

`DISABLED` 同时影响曝光与执行，旧调用或旧 proof 不能绕过。Chat/Plan 的模式限制仍独立生效；网络许可不授予文件权限，Provider 推理联网与 Agent 工具联网分开。特殊递归删除确认目前只覆盖明确解析出的 `rm -rf dir`，不能宣称覆盖所有等价删除程序；其他删除仍按效果策略处理。自定义拒绝写入必须覆盖 Shell 等间接路径，不能仅禁用 `write` 工具名。

## 6. 已实现：执行域与 Provider 链路

developer 的 PRoot、Subscriptions 是同 APK、同 UID 的私有进程；consumer 不打包这些组件。进程隔离不等于 UID 权限隔离。QuickJS 仍是独立 isolated UID，无特权主机桥。依据 [ADR-RUNTIME-001](../adr/runtime/001-execution-domains.md)；资产、干净 CI 与升级验收仍见 [HXA-193](../development/tasks/HXA-193.md)。

```mermaid
flowchart LR
    subgraph SHARED["【实】developer APK / 共享应用 UID"]
        DISPATCH["主进程 Dispatcher"]
        PROVIDER["ModelProvider"]
        CLIENT["Subscriptions 客户端"]
        SUB["私有 :subscriptions 进程"]
        PROOT["私有 :proot 进程"]
        DISPATCH -->|"受控 Job / Binder / PFD"| PROOT
        PROVIDER --> CLIENT
        CLIENT -->|"Binder / PFD"| SUB
    end
    DISPATCH -->|"受限脚本执行"| JS["【实】QuickJS isolated UID<br/>非导出 / 无特权桥"]
    SUB --> SERVER["【外】订阅服务端"]
    PROVIDER --> API["【外】API 模型服务端"]
    DISPATCH --> MCP["【外】用户启用的 MCP / A2A 服务"]
```

订阅协议适配是 Provider 路径，不是 Dispatcher 下的普通工具执行目标。同 UID 不提供凭据、文件或网络的内核隔离；相关约束须落实到真实执行入口，不能用图上的进程框充当安全证明。

## 7. 已实现：Goal 状态与运行激活

HXA-208 已交付自动续跑和模型工具面。状态图依据当前 `GoalState` 允许边；激活、预算与恢复准入另外求值，不把“持久未完成”解释为“现在可以自动运行”。

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> READY
    DRAFT --> CANCELLED
    READY --> RUNNING
    READY --> CANCELLED
    RUNNING --> INPUT_REQUIRED
    RUNNING --> PAUSED
    RUNNING --> BLOCKED
    RUNNING --> COMPLETED
    RUNNING --> FAILED
    RUNNING --> CANCELLED
    INPUT_REQUIRED --> RUNNING: 用户输入与准入通过
    INPUT_REQUIRED --> CANCELLED
    PAUSED --> RUNNING: 已激活续跑或用户继续，准入通过
    PAUSED --> BLOCKED: 预算或依赖不满足
    PAUSED --> CANCELLED
    BLOCKED --> PAUSED: 修复后复查通过
    BLOCKED --> CANCELLED
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```

```mermaid
flowchart TB
    USER["【实】用户明确目标 / 继续意图"] --> SERVICE["【实】GoalLifecycleService<br/>create / get / update / report"]
    SERVICE --> ACTIVE["【实】本进程运行激活"]
    ACTIVE --> ADMISSION["【实】GoalDriver 准入<br/>空闲 / 状态 / 预算 / 停止检查"]
    ADMISSION --> RUN["【实】绑定 Goal 的 Turn"]
    RUN --> SETTLE["【实】轮次结算 / 持久预算 / 模型报告"]
    SETTLE -->|"仍需推进且保持激活"| CONTINUE["【实】GoalContinuationDriver<br/>前台或受系统允许的后台服务"]
    CONTINUE --> ADMISSION
    SETTLE -->|"完成 / 输入 / 阻塞 / 停止"| PARK["停止自动续跑"]
    DEATH["进程死亡 / 冷启动恢复"] --> DISARM["【实】不恢复运行激活<br/>运行中 Goal 停驻"]
    DISARM -->|"用户明确继续"| SERVICE
```

依据 [Goal ADR](../adr/goal/001-lifecycle-and-completion.md)、[状态枚举](../../core/model/src/main/kotlin/com/helix/core/model/GoalState.kt)、[续跑驱动](../../app/src/main/kotlin/com/helix/app/chat/GoalContinuationDriver.kt)。旧注释中“所有新 run 只能由用户逐次创建”不再概括当前行为；进程死亡后明确继续的约束仍成立。模型报告完成不恢复全局强制 verifier；测试失败且还能修复，不应直接判为无法推进的 blocked。

## 8. 已实现的恢复原则与待补体验

恢复界面仍需 [HXA-204](../development/tasks/HXA-204.md) 收口，但底层恢复必须区分四件事，不能只有一条笼统的 resume 箭头。

```mermaid
flowchart TB
    OPEN["冷启动 / 打开会话"] --> HISTORY["历史恢复<br/>消息 / 附件 / 持久状态投影"]
    OPEN --> RECON["结果对账<br/>按调用或 Job 身份查询"]
    RECON --> KNOWN["有确定结果：持久结算"]
    RECON --> UNKNOWN["未知副作用：保留不确定性<br/>不盲目重放"]
    HISTORY --> SUMMARY["【计】统一恢复摘要与导航"]
    KNOWN --> SUMMARY
    UNKNOWN --> REVIEW["【计】用户核查入口"]
    SUMMARY --> USER["用户明确继续"]
    REVIEW --> USER
    USER --> CHECK["当前授权 / 预算 / 能力重新准入"]
    CHECK --> NEW["创建新的执行轮次"]
```

恢复历史不重新激活 Goal；恢复审批展示也不意味着旧等待槽或旧 proof 可继续使用。对账与新执行必须分离，尤其是外发、文件写入和 Runtime Job。

## 9. 已有 Plan / 投影与后续产品闭环

Plan 审阅服务、任务与产物界面已有实现。剩余工作是明确验收和导航闭环，不应重新创建 Plan Engine 或平行任务数据库。

```mermaid
flowchart LR
    PLAN["【实】PlanReviewService / 审阅 UI"] --> BIND["【实】计划版本绑定"]
    BIND --> ENTRY["【实】执行入口"]
    ENTRY --> AUTH["每个操作仍按当前授权求值"]
    STORE["【实】Goal / Todo / Job / 调用 / Artifact"] --> PROJ["【实】TaskLedgerProjection"]
    PROJ --> TASKS["【实】Tasks / Artifacts UI"]
    TASKS -.-> NAV["【计】HXA-202 / 203<br/>工作区、文件、输出与产物互达"]
    PLAN -.-> ACCEPT["【计】HXA-192<br/>UI 到执行及不铸造授权的验收"]
    AUTH -.-> ACCEPT
```

审阅计划只确认版本，不批准未来全部调用；按 HXA-209 选择的会话授权可以影响后续执行，但不能由 Plan 审阅暗中设置。任务显示状态、Turn 结束和 Goal 完成应分开表达。

## 10. 已接受待交付：终端与后台命令

依据 [ADR-RUNTIME-002](../adr/runtime/002-terminal-and-jobs.md)。现有 Job 能力不等于完整终端产品；HXA-194～199 是交付任务，不再等待一次重复的架构决策。

```mermaid
flowchart TB
    JOB["【实】PRoot Job / 取消 / 对账"] -.-> DETAIL["【计】194 命令详情"]
    DETAIL -.-> LOG["【计】195 有界日志 / 检索 / 导出"]
    JOB -.-> DETACH["【计】196 显式 detached Job<br/>owner / 有限 lease / 到期停止"]
    USER["用户手动输入"] -.-> PTY["【计】197 手动 USER PTY"]
    PTY -.-> MULTI["【计】198 多会话<br/>单写入者 / 数量上限"]
    PTY -.-> MUTEX["【计】同 Workspace<br/>手动执行与 Agent 写入互斥"]
    DETACH -.-> ACCEPT["【计】199 集成验收"]
    LOG -.-> ACCEPT
    MULTI -.-> ACCEPT
    MUTEX -.-> ACCEPT
```

手动 PTY 不接收模型输入；它与 Agent 工具不是同一授权通道。后台 Job 不自动续租，死亡后不盲目重放；日志和会话数按 ADR 限额实现。197 不必等待 196，详细依赖与验收仅在任务索引维护。

## 11. 研究候选：不要据图自动启用

```mermaid
flowchart LR
    SCHEDULE["【研】Schedule / Channel 触发"] -.-> CONTRACT["明确用户授权、Android 生命周期<br/>预算、去重与恢复契约"]
    HOOK["【研】可选工作流验收 Hook"] -.-> CONTRACT
    CODE["【研】带主机能力的 Code Mode"] -.-> CONTRACT
    CHILD["【研】子 Agent / 工作流"] -.-> CONTRACT
    CONTRACT -.-> DECISION["独立 ADR / 明确 HXA / 可验证启用条件"]
```

已有 Goal 自动续跑不是上述研究项；不要把完成的 HXA-208 再排一次。QuickJS 解释器存在也不等于可以新增主机桥；工作流验收不能恢复普通 Goal 的全局强制证据门禁。

## 12. 文档校验边界

本次仅更新研究正文与图集，不修改生产行为或宣布 HXA 完成。源码基线与图例固定在本文开头；后续刷新应整段更新事实，避免通过多层“增量说明优先”覆盖互相矛盾的旧图。文档检查与 Mermaid 渲染结果在提交说明中记录，不复用旧版本的验收数字。
