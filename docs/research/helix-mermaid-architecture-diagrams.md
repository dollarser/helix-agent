# Helix 现状架构与候选演进图

> 文档性质：源码事实视图与候选设计图集，不替代现有架构规范。
> 核对日期：2026-09-13。基线：本地 `main` HEAD `0bcd9d34d299974f950094101e5d34c956ebe569` 加未提交工作区。
> 基线 hash、事实修正、候选职责与验收统一见[研究与产品演进方案](helix-agent-complete-research-and-product-plan.md)。
> 2026-09-14 分类更新：两份材料统一归入 `docs/research/`。下文的“现状/本轮”均指 2026-09-13 的 main 研究快照，不是 Harness 分支的当前实现；源码链接固定到取证结束时的 main 修订。重构收尾以[专项交接](../development/harness-2.0-next-work.md)和[实施状态](../development/status.md)为准。

## 1. 图例与职责

图集负责表达“谁调用谁、谁拥有状态、在哪个执行域运行、何时恢复”。产品优先级、竞品问题和 HXA 拆分只在配套正文维护。图中的组件可以是职责，不要求为每个框创建一个类或模块。

| 标记 | 含义 |
| --- | --- |
| 【现】 | 本轮源码/状态记录支持的生产结构；不等于本轮通过设备验收 |
| 【候】 | 渐进演进候选；正式实施仍需对应 HXA |
| 【研】 | 涉及新契约/平台可行性的研究；不能直接接入生产 |
| 【平台】/【外部】 | Android 或用户配置的外部服务，不属于 Helix 内部模块 |
| 实线 | 所在视图内的调用、数据或状态流，具体含义以边标签为准 |
| 虚线 | 观察、约束、引用或尚未接入的候选关系；不表示授权继承 |

先读第 2～7 节的现状，再读第 8～10 节的候选/研究。当前状态来源为[实施状态](../development/status.md)；契约需结合[ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)、[ADR-0039](../adr/0039-background-results-and-goal-blockers.md)、[ADR-0040](../adr/0040-model-judged-goal-completion.md)。不能把旧 ADR 的已取代片段或架构伪代码当作当前实现。

## 2. 现状：应用入口与执行所有权

图 A 是现有职责的概览，省略具体工具。手动文件、浏览器和安装操作使用各自应用服务；只有 Agent 请求进入循环，系统授权也不自动成为 Agent 的 Tool Approval。

```mermaid
flowchart TB
    USER["用户"]
    CHAT["【现】对话与 Goal 操作"]
    TASKUI["【现】任务列表 / 结果回收"]
    FILEUI["【现】独立文件管理"]
    BROWSERUI["【现】浏览器 / 标签页 / 下载区"]
    SETTINGS["【现】设置 / 能力状态 / 安装修复"]
    SERVICE["【现】ChatService<br/>请求准入与运行协调"]
    TURN["【现】TurnCoordinator<br/>Turn / ModelCall 事务所有者"]
    LOOP["【现】ChatModelLoop<br/>Chat / Plan / Act / Goal 共用"]
    REQUEST["【现】请求 / 历史 / 附件 / 压缩"]
    MODEL["【现】ModelProvider"]
    TOOL["【现】Scheduler + Dispatcher<br/>Policy / Approval / 结算"]
    DB["【现】持久会话 / Turn / Goal / 工具结果"]
    FILES["【现】文件应用服务<br/>Workspace / 共享存储 / SAF"]
    WEBVIEW["【现】BrowserController<br/>Activity / WebView owner"]
    CAP["【现】能力与 Runtime 安装/验证服务"]
    OS["【平台】权限 / Picker / 安装确认"]

    USER --> CHAT
    USER --> TASKUI
    USER --> FILEUI
    USER --> BROWSERUI
    USER --> SETTINGS
    CHAT --> SERVICE
    TASKUI -->|"精确取消 / 用户继续"| SERVICE
    SERVICE --> TURN
    SERVICE --> LOOP
    LOOP -->|"持久模型步骤与回填"| TURN
    LOOP --> REQUEST
    REQUEST -->|"读取历史"| DB
    LOOP -->|"组装后的请求"| MODEL
    MODEL -->|"模型事件"| LOOP
    LOOP --> TOOL
    TOOL -->|"每调用持久结算"| DB
    TURN --> DB
    DB -.->|"查询 / 投影"| TASKUI
    FILEUI --> FILES
    FILES --> OS
    BROWSERUI --> WEBVIEW
    SETTINGS --> CAP
    CAP -->|"用户动作"| OS
```

依据：[ChatService](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/ChatService.kt)、[TurnCoordinator](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/TurnCoordinator.kt)、[BrowserScreen](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/feature/browser/src/main/kotlin/com/helix/feature/browser/ui/BrowserScreen.kt)、[当前接口](../development/status.md#current-interfaces)。文件/浏览器也可由工具驱动，但那条路径须经图 C 的 Dispatcher；不因此强迫手动操作先建立 Agent Turn。

## 3. 现状：单循环、请求与工具曝光

图 B 的 ModePolicy 是曝光与执行模式约束；动态风险最终仍由每次调用的 Policy 判断。图中不存在四套 Loop，也不把测试中的旧 ContextBuilder 放进生产请求路径。

```mermaid
flowchart TB
    INPUT["【现】RunControlConfig<br/>模式 / 模型 / 预算"]
    MODE["【现】ModePolicy<br/>Chat 默认无工具<br/>开启后只读 L0；Plan 只读至 L1<br/>Act / Goal 逐调用 Policy"]
    DISCOVERY["【现】McpToolDiscovery<br/>tools.search + 会话窗口"]
    HISTORY["【现】持久历史 / 摘要 / 附件绑定"]
    BUILTIN["【现】内置环境模板 / Goal 上下文"]
    ASSEMBLER["【现】ChatRequestAssembler<br/>build / backfill / rebuild"]
    LOOP["【现】ChatModelLoop"]
    PREPARE["【现】每次模型请求前<br/>ContextCompactionRound.prepare"]
    ADMISSION["【现】模型窗口 / Turn / Goal 预算准入"]
    PROVIDER["【现】ModelProvider.stream"]
    DECISION{"本次响应用途与结果"}
    SUMMARY["【现】摘要持久结算"]
    TOOL["【现】工具执行与持久结算"]
    FINISH["【现】Turn 结束 / 失败 / 取消<br/>Goal 按绑定独立结算"]

    INPUT --> MODE
    MODE --> DISCOVERY
    DISCOVERY -->|"可见 schemas"| ASSEMBLER
    HISTORY --> ASSEMBLER
    BUILTIN --> ASSEMBLER
    INPUT --> LOOP
    ASSEMBLER --> LOOP
    LOOP --> PREPARE
    PREPARE --> ADMISSION
    ADMISSION -->|"通过"| PROVIDER
    ADMISSION -->|"窗口或预算不足"| FINISH
    PROVIDER --> DECISION
    DECISION -->|"摘要响应"| SUMMARY
    SUMMARY -->|"重新构建"| ASSEMBLER
    DECISION -->|"普通 ToolCall"| TOOL
    TOOL -->|"可继续时按调用顺序回填"| ASSEMBLER
    DECISION -->|"正常停止 / 错误 / 取消"| FINISH
```

依据：[ChatModelLoop](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/ChatModelLoop.kt)、[ChatRequestAssembler](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt)、[ContextCompactionRound](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/ContextCompactionRound.kt)、[McpToolDiscovery](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/mcp/McpToolDiscovery.kt)。图简化控制流；压缩失败、未知副作用或取消也会阻止回填后的下一次请求。

## 4. 现状：ToolCall 执行、审批与持久结算

图 C 展示逻辑执行阶段，组件拆文件不改变这些边界。审批解析可由 L0、有效低风险规则或精确批次自动完成，不必每次弹窗。只有需要且获得的类型化 APPROVED proof 才在执行开始阶段消费；proof 消费不是成功凭证。

```mermaid
flowchart TB
    CALL["模型提出 ToolCall"]
    NORMAL["【现】规范参数 / scope / target<br/>绑定当前调用身份"]
    QUEUE["【现】Scheduler<br/>影响范围判定并发 / 原调用序号"]
    SCHEMA["【现】Registry / Schema 校验"]
    POLICY["【现】实时 Capability + Policy<br/>动态风险 / 出网规则"]
    APPROVAL["【现】Approval 解析<br/>规则或精确用户批准"]
    START{"取消 / 准入 / deadline 检查"}
    SPEND["【现】执行开始阶段<br/>需要时消费一次性 APPROVED proof"]
    EXEC["【现】调用执行器"]
    OUTCOME{"执行器结果"}
    VERIFY["【现】输出 schema / 内容限制 / hash 验证"]
    KNOWN["【现】可确认的失败结果"]
    UNKNOWN["【现】开始后取消 / 超时 / 未知副作用<br/>requiresReview / 待对账"]
    NOEXEC["【现】拒绝 / 开始前取消<br/>没有调用执行器"]
    SETTLE["【现】逐调用审计与持久结果<br/>异常也保留独立结算"]
    READY{"批次全部结算且允许继续？"}
    BACKFILL["【现】按原调用顺序回填<br/>下一 ModelCall 由 TurnCoordinator 提交"]
    PARK["【现】终止 / 中断 / 待核查<br/>禁止盲目重放"]

    CALL --> NORMAL --> QUEUE --> SCHEMA --> POLICY --> APPROVAL --> START
    SCHEMA -->|"校验失败"| NOEXEC
    POLICY -->|"拒绝"| NOEXEC
    APPROVAL -->|"拒绝 / 取消"| NOEXEC
    START -->|"停止"| NOEXEC
    START -->|"开始"| SPEND --> EXEC --> OUTCOME
    OUTCOME -->|"返回输出"| VERIFY
    OUTCOME -->|"已确认失败"| KNOWN
    OUTCOME -->|"无法证明副作用状态"| UNKNOWN
    VERIFY -->|"成功或验证失败"| SETTLE
    KNOWN --> SETTLE
    UNKNOWN --> SETTLE
    NOEXEC --> SETTLE
    SETTLE --> READY
    READY -->|"是"| BACKFILL
    READY -->|"否"| PARK
```

依据：[ToolDispatcher.executeStage](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDispatcher.kt)、[ToolScheduler](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/tools/framework/src/main/kotlin/com/helix/tools/framework/ToolScheduler.kt)、[工具编排](../architecture/mobile-tool-orchestration.md)。外部副作用不处于 Room transaction 内；验证失败或持久化失败不得推断副作用不存在。只有已确认无副作用的失败才可能按原契约有限重试。

图 C2 区分工具执行域与外部工具服务。QuickJS 没有特权 Host Bridge，PRoot 不挂载真实 Workspace。MCP/A2A 的请求由本地适配器发起，远端输出不可信，A2A 服务不是 Helix Remote Worker。

```mermaid
flowchart LR
    DISPATCH["【现】Dispatcher"]
    NATIVE["【现】原生工具<br/>主 App 受限能力路径"]
    JSCLIENT["【现】QuickJS client"]
    JS["【现】非导出 isolated Service<br/>系统分配 UID / 无特权桥"]
    PROOTCLIENT["【现】PRoot client"]
    PROOT["【现】独立 PRoot APK / UID<br/>离线 Job 快照"]
    MCPCLIENT["【现】本地 MCP Tool adapter"]
    MCP["【外部】用户启用的 MCP 服务"]
    A2ACLIENT["【现】本地 A2A Tool adapter<br/>原 taskId 查询 / 订阅 / 取消"]
    A2A["【外部】用户配置的 A2A Agent"]
    DISPATCH --> NATIVE
    DISPATCH --> JSCLIENT
    JSCLIENT <-->|"Binder / 有界输入输出"| JS
    DISPATCH --> PROOTCLIENT
    PROOTCLIENT <-->|"签名保护 Binder / PFD"| PROOT
    DISPATCH --> MCPCLIENT
    MCPCLIENT <-->|"工具请求 / 不可信结果"| MCP
    DISPATCH --> A2ACLIENT
    A2ACLIENT <-->|"任务请求 / 不可信结果"| A2A
```

依据：[本地执行](../architecture/local-code-execution.md)、[ADR-0007](../adr/0007-companion-runtime-lifecycle.md)、[ADR-0016](../adr/0016-a2a-client-interoperability.md)。这里只表达执行域和请求方向，不把跨网络服务画成拥有本地 Capability 或 Approval 的模块。

## 5. 现状：模型 Provider 与订阅 UID

图 D 把订阅模型链与普通工具链分开。`Helix Subscriptions` 的内部代码仍位于 `runtime/cli-*`；其第三方协议适配不等于官方 CLI 在 Android 直接运行。

```mermaid
flowchart TB
    LOOP["【现】ChatModelLoop"]
    PORT["【现】ModelProvider<br/>内部 ModelRequest / ModelEvent"]
    API["【现】API 协议 adapter<br/>凭据由对应主 App 配置持有"]
    SERVER["【外部】用户选择的模型 API"]
    SUB["【现】订阅 ModelProvider facade"]
    CLIENT["【现】订阅客户端<br/>CliModelJobClient / Supervisor"]
    IPC["【现】签名保护 Binder / PFD<br/>输入快照 / 事件 / Job 对账"]
    UID["【现】Helix Subscriptions 独立 UID<br/>自有 OAuth grant / 刷新 / 协议适配"]
    REMOTE["【外部】订阅服务端"]
    DISPATCH["【现】Tool Scheduler / Dispatcher"]

    LOOP --> PORT
    PORT --> API
    API <-->|"HTTPS 请求 / 响应"| SERVER
    PORT --> SUB
    SUB --> CLIENT
    CLIENT <--> IPC
    IPC <--> UID
    UID <-->|"认证模型请求 / 响应"| REMOTE
    PORT -->|"统一模型事件"| LOOP
    LOOP -->|"模型返回 ToolCall 后"| DISPATCH
```

依据：[订阅 Provider](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/developer/kotlin/com/helix/app/provider/CodexSubscriptionProvider.kt)、[ModelProvider](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/provider/api/src/main/kotlin/com/helix/provider/api/ModelProvider.kt)、[ADR-0021](../adr/0021-third-party-subscription-protocol-adapter.md)。订阅凭据不返回主 App；连接检查、目录、能力检测和实际模型调用是不同操作，其成功状态不能互相替代。实现存在不代表本轮真实账号/长回复验收完成。

## 6. 现状：Goal 状态机与完成结算

图 E 对照当前 `GoalState` 与 reducer，不再遗漏 DRAFT、FAILED、CANCELLED 或 BLOCKED 的恢复边。标注的准入条件由协调器、状态机和预算共同执行；枚举允许的边不代表 UI 可跳过门控。

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> READY: Ready
    DRAFT --> CANCELLED: 用户取消
    READY --> RUNNING: 用户显式 Continue / 准入及剩余预算通过
    READY --> CANCELLED: 用户取消
    RUNNING --> INPUT_REQUIRED: 需要用户输入
    RUNNING --> PAUSED: 正常停泊 / 用户暂停 / 进程中断
    RUNNING --> BLOCKED: 预算不足 / 未决副作用 / 有效 blocked 报告
    RUNNING --> COMPLETED: 当前轮有效 complete 报告且结算门控通过
    RUNNING --> FAILED: 不可重试错误或重试耗尽
    RUNNING --> CANCELLED: 取消且按当前结算规则收口
    INPUT_REQUIRED --> RUNNING: 用户补充后显式继续 / 准入及预算通过
    INPUT_REQUIRED --> CANCELLED: 用户取消
    PAUSED --> RUNNING: 用户显式继续 / 准入及预算通过
    PAUSED --> BLOCKED: 发现实际阻塞
    PAUSED --> CANCELLED: 用户取消
    BLOCKED --> PAUSED: 用户修复后显式复查 / 无未决调用且预算可启动
    BLOCKED --> CANCELLED: 用户取消
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
    note right of RUNNING
        失败 wake 的重试预算不授权正常跨轮续跑
        停止与未知副作用优先于模型完成声明
    end note
    note right of BLOCKED
        不可直接 Continue
        补预算不清零已用量，也不自行启动
    end note
```

依据：[GoalState](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/core/model/src/main/kotlin/com/helix/core/model/GoalState.kt)、[GoalReducer](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt)、[GoalRunSettlement](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/GoalRunSettlement.kt)、[GoalBlockerResolution](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/GoalBlockerResolution.kt)。预算进入 BLOCKED 按 ADR-0039；模型报告完成按 ADR-0040。reducer 内旧预算 KDoc 的 PAUSED 表述不作为图的依据。

图 E2 说明报告与真实终态的关系。`goal.report` 执行成功只说明报告已记录，最终完成在 Turn 结算时决定。图是判断摘要，不替代源码分支优先级。

```mermaid
flowchart TB
    REPORT["【现】模型 goal.report<br/>complete / in_progress / blocked"]
    TOOL["【现】正常工具管线<br/>绑定当前 Goal / Turn 并持久记录"]
    END["【现】Turn 终态事务"]
    GATE{"取消 / 暂停 / 预算 / 未决副作用<br/>是否允许消费完成报告？"}
    HOST["【现】按宿主结算结果处理<br/>PAUSED / BLOCKED / FAILED / CANCELLED"]
    VALID{"当前轮最后有效报告"}
    COMPLETE["【现】Goal COMPLETED<br/>来源标明模型判断"]
    BLOCK["【现】Goal BLOCKED<br/>保留模型原因"]
    PAUSE["【现】正常轮次 park<br/>PAUSED；额度不足则 BLOCKED"]
    REPORT --> TOOL
    TOOL -.->|"报告引用"| END
    END --> GATE
    GATE -->|"不允许"| HOST
    GATE -->|"允许"| VALID
    VALID -->|"complete"| COMPLETE
    VALID -->|"blocked"| BLOCK
    VALID -->|"in_progress 或无报告"| PAUSE
```

`COMPLETED` 表示模型报告完成且运行门控允许，不保证独立业务认证。可选工作流验收另见第 10 节，不恢复全局强制 verifier。

## 7. 现状：四种恢复与 Android 生命周期

图 F 把“恢复”拆开。查询记录、收回结果、用户继续和未知副作用核查是不同操作，没有一个通用 `resume` 箭头自动重启模型或工具。

```mermaid
flowchart TB
    OPEN["用户重新打开 / Runtime 断连处理"]
    LOAD["【现】加载持久记录<br/>Session / Turn / Goal / ToolCall / Approval"]
    HISTORY["【现】历史与摘要恢复<br/>呈现已有消息 / 附件 / 产物引用"]
    INTERRUPT["【现】恢复中断状态与用量<br/>活动 Turn 中断，Goal 先 park"]
    QUERY["【现】按原 jobId / taskId 和输入 hash<br/>查询结果 / 对账；不重新提交"]
    CHECK{"有匹配且可验证的结果？"}
    RESULT["【现】提交结果与审计<br/>可供用户回收 / 查阅"]
    UNKNOWN["【现】保留未知副作用<br/>NEEDS_REVIEW / Goal BLOCKED"]
    REPAIR["用户核查 / 修复依赖 / 必要时补预算"]
    RECHECK["【现】显式复查<br/>无未决调用、状态和预算通过"]
    PAUSED["【现】可继续的 PAUSED"]
    CONTINUE["用户明确 Continue"]
    ADMIT["【现】新 run / Turn 准入<br/>复用原 Goal 累计预算"]
    EXEC["【现】开始下一轮<br/>既有工具授权重新判断"]

    OPEN --> LOAD
    LOAD --> HISTORY
    LOAD --> INTERRUPT
    INTERRUPT -->|"存在外部执行记录"| QUERY
    QUERY --> CHECK
    CHECK -->|"是"| RESULT
    CHECK -->|"否 / 证据失效"| UNKNOWN
    RESULT -->|"仅标记已查看"| HISTORY
    RESULT -->|"对账后状态与预算允许继续"| PAUSED
    INTERRUPT -->|"无未决副作用且可继续"| PAUSED
    UNKNOWN --> REPAIR --> RECHECK
    RECHECK -->|"通过"| PAUSED
    RECHECK -->|"仍未解决"| UNKNOWN
    PAUSED --> CONTINUE --> ADMIT
    ADMIT -->|"通过"| EXEC
    ADMIT -->|"不通过"| PAUSED
```

依据：[当前恢复边界](../development/status.md#known-limitations)、[ADR-0007](../adr/0007-companion-runtime-lifecycle.md)、[ADR-0039](../adr/0039-background-results-and-goal-blockers.md)。图中 Runtime/A2A 查询路径按各自契约执行；仅凭消息文本、取消请求已发送或文件名存在不能确认副作用。手动文件复制/移动日志有独立恢复协议，不是 Agent run 的 checkpoint，也不承诺断电原子事务或字节续传。

图 G 区分已有提醒与正在执行的前台服务。提醒 worker 没有自动启动模型的边。

```mermaid
flowchart TB
    CHECKPOINT["【现】用户设置 checkpoint 提醒"]
    WORK["【平台】WorkManager 可延迟提醒"]
    NOTICE["【现】提醒通知"]
    TAP["用户点击通知 / 打开后继续"]
    ADMIT["【现】Goal 准入 / 预算检查"]
    RUN["【现】有界任务执行"]
    FGS["【现】与真实工作匹配的 FGS<br/>用户可见且可停止"]
    WAIT["【现】等待审批/输入或无活跃工作"]
    STOP["【现】停止对应前台服务"]
    OS["【平台】拒绝启动 / 回收 / 权限变化"]
    REC["【现】持久中断、对账与用户恢复"]

    CHECKPOINT --> WORK --> NOTICE
    NOTICE --> TAP --> ADMIT
    ADMIT -->|"通过"| RUN
    RUN -->|"符合平台条件且确需后台"| FGS
    RUN --> WAIT --> STOP
    FGS -->|"完成 / 取消"| STOP
    OS --> REC
    REC -->|"用户处理后明确继续"| ADMIT
```

依据：[ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)、[ADR-0007](../adr/0007-companion-runtime-lifecycle.md)、[Android FGS 启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。FGS 按全部活跃任务的实际需求管理，单任务等待不应错误停止其他任务所需服务；图省略聚合器细节。平台允许启动服务不等于 ToolCall 已批准，也不保证服务永远存活。

## 8. 候选：最小职责收敛与任务体验

图 H 对应正文 S1/S2。优先新增读模型和轻量 facade，不要求同时创建 GoalDriver、Plan Engine、Hooks 或 Task Orchestrator。手动文件/浏览器/安装继续走图 A。

```mermaid
flowchart TB
    ENTRY["【现】Agent 请求入口"]
    FACADE["【候】app 层统一入口职责<br/>submit / cancel / observe<br/>continue 与 reconcile 明确区分"]
    TURN["【现】TurnCoordinator<br/>唯一活动 Turn 所有者"]
    LOOP["【现】共用 ChatModelLoop<br/>按收益决定是否更名"]
    REQUEST["【候】生产请求组装职责收敛<br/>复用历史 / 附件 / 压缩"]
    SECTIONS["【候】内置 Prompt sections"]
    MODEL["【现】ModelProvider"]
    TOOLS["【现】工具管线"]
    STORE["【现】持久状态 / 工具结果 / Artifact refs"]
    PROJECTION["【候】任务 / 产物 / 恢复摘要投影"]
    UI["【候】已有页面的体验增量"]
    ENTRY --> FACADE
    FACADE --> TURN
    FACADE --> LOOP
    LOOP --> REQUEST
    SECTIONS --> REQUEST
    LOOP -->|"已组装请求"| MODEL
    LOOP --> TOOLS
    TURN --> STORE
    TOOLS --> STORE
    STORE -.->|"只读派生"| PROJECTION
    PROJECTION --> UI
    UI -->|"用户动作与精确 ID"| FACADE
```

候选 facade 不创建第二个运行表，任务投影不接管 Goal 状态。请求主干迁移必须保持工具调用配对、图片重试绑定和摘要后的恢复；不能使用旧 ContextBuilder 直接替换生产路径。

## 9. 候选：Prompt 来源与 Plan 审阅

图 I 表达 Helix 的候选来源边界。排序或 scope 覆盖只影响组装，不提升信任或权限。外部内容进入模型上下文时始终保留来源；模型最终输出仍需真实 Policy 判断。

```mermaid
flowchart TB
    BUILTIN["【现】发布包内置模板"]
    USER["【现】用户直接输入"]
    PROJECT["【候】已选 Workspace 项目指令"]
    EXTERNAL["【现】Skill / 文件 / 网页 / MCP / A2A<br/>不可信来源"]
    STATIC["【候】白名单 sections<br/>稳定 ID / 顺序 / 请求 scope"]
    ENVELOPE["【候】来源封装<br/>source / trust / hash / ref"]
    HISTORY["【现】持久历史 / 工具配对 / 附件"]
    CTX["【候】沿生产路径组装与预算分配"]
    ADAPTER["【现】Provider wire adapter<br/>role / instructions 依协议映射"]
    MODEL["【外部】模型推理"]
    POLICY["【现】Tool Policy / Approval<br/>权限来自用户或有效既有规则"]
    BUILTIN --> STATIC
    PROJECT --> ENVELOPE
    EXTERNAL --> ENVELOPE
    USER --> CTX
    STATIC --> CTX
    ENVELOPE --> CTX
    HISTORY --> CTX
    CTX --> ADAPTER --> MODEL
    MODEL -->|"仅提出 ToolCall"| POLICY
```

设计参考 [DeepSeek system-prompt](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/system-prompt.md) 的有序 sections 与动态 context；本图中的信任包装与权限关系是 Helix 设计要求，不冒充上游原样实现。首版只整理内置组件，不开放外部插件注册或全系统提示词替换。

图 J 是候选 Plan 产品流程。普通 Act 可直接发起；结构化元数据工具尚需单独契约，Plan 审阅记录不能作为后续所有 ToolCall 的 Approval Proof。

```mermaid
flowchart TB
    USER["用户任务"]
    PLAN["【现】Plan 只读调研与回复"]
    DRAFT["【候】计划草稿 / 版本"]
    REVIEW["【候】用户审阅<br/>方案 / 预期变更 / 风险"]
    CHOICE{"用户决定"}
    REVISE["【候】修订并生成新版本"]
    BIND["【候】记录审阅版本 / planHash<br/>不是 Tool Approval"]
    START["【现】Act / Goal 用户启动路径"]
    POLICY["【现】每个 ToolCall<br/>schema / scope / Policy / 必要审批"]
    SUMMARY["【候】本轮摘要 / 产物 / 检查 / 剩余事项"]
    CANCEL["【候】取消本次计划流程"]
    USER --> PLAN --> DRAFT --> REVIEW --> CHOICE
    CHOICE -->|"修改 / 继续规划"| REVISE --> DRAFT
    CHOICE -->|"按此执行"| BIND --> START
    CHOICE -->|"取消"| CANCEL
    USER -->|"普通任务直接 Act"| START
    START --> POLICY
    POLICY --> SUMMARY
```

Plan/Todo 的内部元数据更新不能伪装成任意 READ_ONLY 文件写；必须绑定当前会话、版本、大小和来源。Act 摘要缺失不直接记任务失败，不强制每个简单操作多调用一次 `turn.report`。

## 10. 研究：自动续跑、工作流门禁与 Code Mode

图 K 是**未接入的研究模型**，不是当前 Goal 状态机。自动轮次要改变显式 Continue 语义，先完成正文第 6 节所列决策。候选 activation 在重启后默认关闭，Schedule/Channel 不能直接连到执行器。

```mermaid
flowchart TB
    USER["用户明确开启连续运行"]
    ACTIVE["【研】运行激活状态<br/>与持久 Goal 生命周期分离"]
    DRIVER["【研】GoalDriver<br/>仅空闲且激活时预约一轮"]
    SOURCE["【研】Schedule / Channel 候选事件"]
    SOURCEGATE["【研】来源授权 / occurrence 去重<br/>Android 平台可行性准入"]
    ADMISSION["【研】轮次准入<br/>Goal 版本 / 预算 / 用户消息竞争"]
    COMMIT["【研】提交轮次身份后才开始<br/>复用现有 Turn 和 Tool 管线"]
    STOP["用户停止 / 新输入抢占"]
    DISARM["【研】撤销未启动预约 / 关闭准入<br/>取消当前轮并保留结算"]
    RESTORE["进程恢复"]
    OFF["【研】未激活<br/>只恢复事实，等待用户动作"]
    USER --> ACTIVE --> DRIVER
    SOURCE -.-> SOURCEGATE
    SOURCEGATE -.-> ACTIVE
    DRIVER --> ADMISSION
    ADMISSION -->|"通过"| COMMIT
    ADMISSION -->|"失效 / 无预算"| DISARM
    STOP --> DISARM
    RESTORE --> OFF
    OFF -->|"用户重新开启"| ACTIVE
```

图 L 区分可选验收失败与真实阻塞。普通 Goal 不经过该门禁；可执行 Hook 的每项动作仍经过 Dispatcher 并记账。

```mermaid
flowchart TB
    OPT["用户选择特定工作流门禁"]
    CLAIM["模型提出完成报告"]
    HOOK["【研】CompletionHook<br/>有界检查 / 正常 Tool 授权"]
    PASS{"检查结果"}
    CAN{"失败后还能主动修复且有预算？"}
    FIX["继续修复与检查<br/>不直接报告 blocked"]
    BLOCK["记录真实依赖 / 预算 / 副作用原因<br/>按适用状态停泊与复查"]
    END["返回合法完成结算<br/>保留模型与检查两类来源"]
    OPT -.-> HOOK
    CLAIM --> HOOK --> PASS
    PASS -->|"通过"| END
    PASS -->|"失败"| CAN
    CAN -->|"是"| FIX
    FIX --> CLAIM
    CAN -->|"否"| BLOCK
```

QuickJS Code Mode 不画成已经存在的工具桥：当前图 C2 的 isolated Service 没有 `helix.files.*` 宿主回调。若要增加，必须另行设计跨 UID 请求身份、审批等待、资源预算、嵌套调用、取消和对账，并重新评审无特权 Host Bridge 的现有边界。生产 child/Workflow 亦不在图 H 中预留空模块，依据 [ADR-0009](../adr/0009-bounded-local-orchestration.md) 的门禁独立评估。

## 11. 图文维护与校验

维护时以源码符号和正文候选编号为锚，不依赖会漂移的行号。每幅图须标明现状/候选/研究、状态所有者、跨域边和失败路径；改变箭头前先确认是否改变运行或授权契约。

校验分三层：Mermaid 语法与渲染、相对链接与目录、人工对照源码/ADR 的语义检查。渲染通过不证明架构已实现；源码结构存在不证明真实账号、OEM/Doze 或完整恢复验收通过。

本轮使用 Mermaid CLI `11.17.0` 完成全部 14 张图的 SVG 渲染，并抽查图 A/C/E/F/K 的可视输出。产物保存在本机忽略目录 `build/docs-refinement-2026-09-13/`；完整检查结果见[正文](helix-agent-complete-research-and-product-plan.md#9-维护与本轮检查边界)。

本次保留用户指定文件路径，因此仍受正文第 9 节说明的 `docs/` 根目录分类门禁阻塞；不为图集放宽全仓库检查。未来归档时按现状/候选职责决定归属并更新引用，不直接覆盖现有 `architecture/overview.md` 或新增一整套重叠的规范文档。
