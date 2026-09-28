# Helix 异步 Job、等待与前后台执行竞品调研（2026-09-28）

> 状态：研究结论 / 架构输入，不直接替代 ADR、HXA 或实现状态。
>
> 本文研究 Helix 是否需要模型可调用的等待能力、后台 Job、前台转后台能力，以及这些能力应如何与现有 TurnEngine、ToolCall、PRoot Job、successor Turn、UNKNOWN / effect truth 和 shallow harness 组合。竞品依据以截至 2026-09-28 可核验的 OpenAI、Claude Code、VS Code / Copilot Agent、Cursor、OpenCode 与 Operit2 官方文档或公开仓库为主。
>
> 本文只提出研究建议，不修改当前已接受的 ADR-RUNTIME-002 与 ADR-AGENT-001。

---

## 1. 结论摘要

Helix **有必要提供等待后台工作完成的能力**，但不应把它设计成 sleep(seconds) 轮询。

推荐目标：

~~~text
async-capable operation
        ↓
     JobHandle
        ↓
 status / await / cancel / collect
~~~

Helix **也有必要考虑前台转后台**，但不是通用 background(anyToolCall)，而是 executor/runtime 明确支持的 same-execution promotion：

1. promotion 保留同一个 execution identity；
2. 不通过 cancel + restart 伪造转后台；
3. Runtime owner 可以脱离当前模型等待继续运行；
4. output/result 可以异步持久化；
5. cancel/reconcile 语义明确；
6. permission、scope、budget、ExecutionTarget 不因 promotion 改变。

推荐执行模式：

~~~text
ExecutionMode
├── FOREGROUND
├── BACKGROUND
└── AUTO
        │
        ▼
     JobHandle
        │
   ┌────┼─────┐
   │    │     │
status await cancel
        │
        ▼
      collect/result
~~~

最重要的是：**Helix 已经有 Linux async Job 基础，不应重写第二套后台任务系统。**

当前已有：

~~~text
code.linux.job.start
code.linux.job.status
code.linux.job.cancel
code.linux.job.collect
~~~

并已经具备 persistent identity、Runtime owner、lease / budget、bounded logs、terminal reconciliation、ORPHANED / UNKNOWN、process death 后不 replay、accepted != succeeded 等能力。

所以真正需要补的是：

> **把 Linux-specific Job 提升为通用 Agent async-handle / join 语义，同时继续让真实 execution state 留在各自 Runtime。**

---

## 2. “后台任务”必须拆成四类

### 2.1 Async tool call

~~~text
model launches operation
↓
tool returns handle
↓
model does independent work
↓
join when result becomes necessary
~~~

解决的是：模型不被一个慢工具长期阻塞。

### 2.2 Background process

~~~text
shell / build / server
↓
OS/runtime keeps running
↓
Agent no longer waits synchronously
~~~

解决的是：进程生命周期与当前 reasoning step 解耦。

### 2.3 Background subagent

~~~text
parent agent
├── child agent A
├── child agent B
└── parent continues
~~~

解决的是：Agent 工作流并发和独立 context。

### 2.4 Automation

~~~text
future trigger
↓
create new Run / Turn
~~~

解决的是：未来什么时候启动 Agent。

因此推荐概念边界：

~~~text
Job          = execution primitive
Subagent     = agent-work primitive
Automation  = trigger primitive
Turn / Goal  = intent and execution-attempt primitive
~~~

它们可以共享 Handle / status / observation 的一部分抽象，但不应共享完整 lifecycle owner。

---

## 3. OpenAI：Async Tool Calling 与 wait_for_tasks

OpenAI 当前 Async Tool Calling 允许工具声明 async: true。模型发起异步 tool call 后，可以继续处理不依赖其结果的工作，而不必立刻等待 tool output。

官方模式有几个重要事实：

1. Async Tool Calling 不替应用管理后台 Job，真正执行和生命周期仍由应用负责；
2. tool 完成后，应用在后续请求中返回结果；
3. 官方专门给出 wait_for_tasks 模式：异步工具返回 task handle，模型只在后续工作真正依赖结果时等待；
4. wait 应只等待指定 handle，而不是变成所有后台工作的全局 barrier；
5. 如果结果已经就绪，应用可以直接返回，而不需要为了形式先执行一次 wait。

来源：

- https://developers.openai.com/api/docs/guides/async-tool-calling

### 对 Helix 的启发

核心不是复制 OpenAI 的 API，而是采用：

> **launch 与 join 分离。**

~~~text
launch
↓
JobHandle
↓
independent work
↓
await selected handles
~~~

但 Helix 不适合在 Job 完成后回头改写已经 terminal 的 launch ToolResult。Helix 更适合：

~~~text
launch ToolCall
  -> terminal ToolResult = ACCEPTED + JobHandle

later
  -> jobs.await / jobs.collect
  -> new ToolCall / observation
  -> terminal Job result/ref
~~~

这样与现有 immutable ToolCall / ToolResult / effect truth 更一致。

---

## 4. Claude Code：最直接的 foreground → background 参考

Claude Code 官方支持：

- Bash 调用启动时直接选择 background；
- 用户按 Ctrl+B 将正在运行的 Bash 命令转入后台；
- 转后台后立即获得 task identity；
- /tasks 查看后台任务；
- TaskStop 停止任务；
- 输出可通过独立 output file 查看；
- foreground Bash 等待达到 timeout 后可以自动转后台，而不是简单杀掉；
- Ctrl+B 同样可用于正在工作的 agent。

来源：

- https://code.claude.com/docs/en/interactive-mode
- https://code.claude.com/docs/en/tools-reference

### 对 Helix 的三个启发

第一，前台转后台是 **runtime-specific** 的能力。Claude Code 支持 Bash / Agent，不是 arbitrary tool transformation。

第二，AUTO promotion 是成熟产品中真实存在的模式：

~~~text
foreground
↓
foreground wait budget reached
↓
same execution moves to background
↓
task handle
~~~

第三，backgrounding 是 execution lifecycle ownership 的变化，不是单纯隐藏 UI。后台 shell 的目录、副进程 lifetime 等都需要明确边界。

因此 Helix 可以吸收这一能力，但只能对能证明安全 detach 的 runtime 开放。

---

## 5. VS Code / Copilot Agent：Continue in Background

VS Code 官方 Agent tools 文档提供 Continue in Background。长时间 terminal command 在前台执行时，用户可以把它推到后台，命令继续运行，Agent 则继续其他任务。Agent 也可以一开始直接选择 background execution。

典型对象包括：

- dev server；
- watch build；
- long build。

来源：

- https://code.visualstudio.com/docs/agents/run/tools

### 对 Helix 的启发

Claude Code 与 VS Code 共同说明：

> **正在运行的 terminal/process 从 foreground 转 background，是值得实现的产品能力。**

但正确抽象应该是：

~~~text
code.linux.run
     │
     │ still running
     ▼
runtime.promoteSameExecution()
     │
     ▼
JobHandle
~~~

而不是：

~~~text
cancel foreground
start another background process
~~~

后者可能重复命令和副作用，不能称为 promotion。

---

## 6. Cursor：Foreground / Background Subagent

Cursor 官方 Subagents 文档区分：

| Mode | Behavior |
| --- | --- |
| Foreground | parent 等待 child 完成 |
| Background | 立即返回，child 独立工作 |

Background subagent 拥有独立 context，可并行运行，完成后把结果返回 parent，也可以使用独立 worktree / cloud VM。

Cursor SDK 进一步提供等待 run / follow-up work 的语义，Agent ACTIVE 状态也可能表示正在等待 background work。

来源：

- https://prod.cursor.com/docs/subagents
- https://cursor.com/docs/sdk/typescript
- https://prod.cursor.com/docs/cloud-agent/api/endpoints

### 对 Helix 的启发

至少应该区分两种 join：

~~~text
Job join
  shell / build / download result

Agent join
  subagent final report
~~~

可以共享 Handle、status、cancel、completion observation，但不应该让 Subagent 变成普通 ProcessJob。

---

## 7. OpenCode：Background child session

OpenCode v2 支持 subagent 在 foreground 或 background child session 中执行。Parent 保持可用，child 完成后向 parent 返回 result / failure；child 有自己的 context 和 configured permissions。

来源：

- https://opencode.ai/v2/docs/agents
- https://opencode.ai/v2/docs/commands

### 对 Helix 的启发

未来可以支持：

~~~text
agent.spawn(background = true)
  -> AgentHandle
~~~

但它属于 Subagent 层。

Helix 仍应坚持自己的安全 invariant：

~~~text
child effective permission
<= parent delegated ceiling
~~~

OpenCode 的权限模型与这一点并不完全相同，所以这里应标为 Helix 自己的安全决策，而非行业统一事实。

---

## 8. Operit2：跨节点 handoff 不迁移 in-flight execution

Operit2 将执行主体抽象为 CoreNode / Host，并通过 Space / Binding 支持跨设备继续任务。

其公开设计对 Helix 最重要的边界是：

> 跨设备 handoff 位于 tool result 已经持久化、下一 model request 尚未开始的稳定边界。

它不迁移正在运行的：

- model request；
- terminal process；
- browser session。

来源：

- https://github.com/AAswordman/Operit2

### 对 Helix 的启发

> **后台化不等于跨 ExecutionTarget 迁移。**

未来即使 Helix 有：

~~~text
ThisPhone
Desktop
SSHHost
RemoteAndroid
~~~

默认也不应该提供：

~~~text
moveRunningJob(jobId, newHost)
~~~

除非具体 runtime 提供真正的 checkpoint / migration protocol。

默认模型：

~~~text
running work stays on original ExecutionTarget
↓
persist terminal result
↓
next Turn / task may bind another target
~~~

---

## 9. 竞品矩阵

| 产品 / 机制 | 启动时后台 | 运行中转后台 | Wait / Join | 完成后回到 Agent | 核心特点 |
| --- | --- | --- | --- | --- | --- |
| OpenAI Async Tool Calling | 是 | 不负责 runtime promotion | 官方推荐 wait_for_tasks | 后续 request 返回结果 | app 管 Job |
| Claude Code Bash | 是 | **是：Ctrl+B / timeout auto-background** | task / output 管理 | task ID + output file | shell-specific |
| VS Code Agent terminal | 是 | **是：Continue in Background** | 后续检查 output | terminal invocation | terminal-specific |
| Cursor subagent | 是 | launch mode 为主 | run wait / follow-up | parent follow-up turn | separate context |
| OpenCode subagent | 是 | launch mode 为主 | child completion | result/failure 回 parent | child session |
| Operit2 handoff | 下一阶段可换 Node | **不迁移 in-flight execution** | 稳定边界后继续 | 下一 model request | cross-node continuation |
| Helix Linux Job | 是 | 尚未统一提供 | status / collect | explicit collect | durable Runtime owner |

---

## 10. Helix 已经有的基础

当前 accepted Runtime 设计已经把下面三类对象分开：

~~~text
one-shot Job
async Job
manual PTY Session
~~~

Linux async Job 已经提供：

~~~text
code.linux.job.start
code.linux.job.status
code.linux.job.cancel
code.linux.job.collect
~~~

并已有：

- persistent identity；
- Runtime owner；
- lease；
- cumulative budget；
- bounded log spool；
- cancellation；
- terminal reconciliation；
- ORPHANED / UNKNOWN；
- process death 后不 replay；
- accepted != succeeded；
- status 与 collect 分离；
- original identity binding；
- collect 时重新校验 authorization。

因此不应该再增加一套 GenericBackgroundTaskDatabase 来复制这些状态。

建议增加的是通用 projection：

~~~text
AsyncHandle
├── handleId
├── kind
├── sourceToolCallId
├── executionTarget
├── providerRef
└── generation
        │
        ▼
provider-specific durable owner
~~~

真实 process state 继续属于 PRoot Runtime、Subagent runtime、Remote provider 等各自 owner。

---

## 11. 最关键的兼容点：ToolCall batch 必须先 settle

当前 ADR-AGENT-001 要求一个模型响应产生的 ToolCall batch durable settle 后，才能继续下一 ModelCall。

如果把一个 20 分钟后台 Job 表示成：

~~~text
ToolCall = RUNNING
~~~

直到 build/test 完成，AgentLoop 依然被阻塞，所谓异步没有意义。

### 11.1 Launch ToolCall 与 Job 必须是两个生命周期

正确方式：

~~~text
ToolCall: code.linux.job.start
        │
        ├── validate / authorize / submit
        └── terminal ToolResult:
              ACCEPTED + JobHandle
                         │
                         ▼
                      Job RUNNING
~~~

也就是说：

> **launch ToolCall 在 durable submit 成功后 settle；Job 独立继续运行。**

于是：

~~~text
ToolCall batch settled
↓
next ModelCall allowed
~~~

后台同时仍是：

~~~text
Job RUNNING
~~~

### 11.2 Job terminal 不改写原 ToolCall

Job 后来进入 SUCCEEDED / FAILED / CANCELLED / ORPHANED 时，不能回头改写 launch ToolResult。

最终结果通过新的 jobs.await / jobs.collect，或者现有 code.linux.job.collect 获取。

这保留了 Helix immutable effect/history 语义。

---

## 12. jobs.await：建议增加

结论：**需要。**

但它不是：

~~~text
wait(seconds = 30)
~~~

模型无法可靠预测任务多久结束，sleep 会退化成：

~~~text
sleep 30
status
sleep 30
status
...
~~~

推荐：

~~~text
jobs.await(
  handles = [...],
  condition = ANY | ALL
)
~~~

它表达的是：

> **我的下一步 reasoning 依赖这些 Job。**

而不是“暂停 N 秒”。

也不要做全局 await_all_running_tasks，因为后台可能长期存在 dev server、watcher、download、subagent 或用户终端。

---

## 13. await 与 Android process death

### 同进程存活

短等待可以正常 suspend：

~~~text
ModelCall
↓
jobs.await(job-A)
↓
job-A terminal
↓
ToolResult
↓
next ModelCall
~~~

### 进程死亡

不能恢复 old coroutine、old ToolCall stack 或 old Turn。

继续遵守现有裁决：

~~~text
old Turn -> INTERRUPTED
~~~

而 Job 可以独立继续或 reconciliation：

~~~text
Job continues / reconciles
↓
user continues or future accepted continuation trigger
↓
successor Turn
↓
RecoverySummary + current Job fact
↓
model continues
~~~

因此：

> **await dependency 可以 durable 记录，但绝不能成为 same-Turn crash resume 的后门。**

---

## 14. Job terminal 默认不自动唤醒模型

ADR-RUNTIME-002 当前规定 completion event 不自动唤醒模型，这一点建议继续保持。

原因：

1. server / watcher 可能长期运行；
2. completed Job 可能已经不相关；
3. 自动 wake 会产生额外 inference；
4. 会重新引入 crash 后自动 replay / deep harness；
5. 用户可能在 Job 运行期间改变 Workspace。

推荐：

~~~text
Job terminal
↓
durable completion fact
↓
UI update
↓
next normal ModelCall gets bounded JobObservation
~~~

未来如果明确接受 continueWhenComplete，则应该：

~~~text
Job terminal
↓
fresh successor Turn
↓
fresh admission / budget / policy
~~~

这属于 continuation trigger / Automation，而不是 Job 默认行为。

---

## 15. 前台转后台：做 runtime promotion，不做万能 Tool

结论：

> **不做 model-facing background(anyToolCall)。**

但：

> **允许 executor-specific foreground → background promotion。**

只有满足以下条件才可声明 supportsPromotion=true：

1. promotion 前后是同一 execution identity；
2. 不 replay side effect；
3. Runtime owner 可脱离当前等待继续存在；
4. output 可异步持久化；
5. cancel / reconcile 有定义；
6. permission / scope 不变；
7. ExecutionTarget 不变。

自然候选：

~~~text
PRoot process / build / test
download
durable remote command
background subagent
~~~

通常不适合：

~~~text
ui.click
files.write
one-shot MCP mutation
browser one-shot action
~~~

---

## 16. ExecutionMode

对明确 async-capable 的 executor 建议支持：

~~~text
ExecutionMode
├── FOREGROUND
├── BACKGROUND
└── AUTO
~~~

### FOREGROUND

同步等待结果。

### BACKGROUND

~~~text
submit
↓
JobHandle
↓
launch ToolCall terminal
↓
Job continues
~~~

### AUTO

~~~text
start foreground
↓
within foreground wait budget?
├── yes → normal result
└── no
     ↓
same execution promoted
     ↓
JobHandle
~~~

AUTO 应由平台/runtime决定，而不是让模型猜“先等 10 秒还是 30 秒”。

---

## 17. foregroundWaitBudget != executionDeadline

必须区分：

~~~text
foregroundWaitBudget
executionDeadline
~~~

foregroundWaitBudget 到期只说明：

> Agent 不再同步等待。

executionDeadline 到期才说明：

> Job 运行预算到期。

因此 foreground → background **不能重置执行 deadline、lease 或预算**。

Promotion 也必须发生在 Runtime owner 内，不能由 AgentLoop 重新提交命令。

---

## 18. 通用 AsyncHandle

建议未来通用投影：

~~~text
AsyncHandle
├── id
├── kind
├── sourceSessionId
├── sourceTurnId
├── sourceToolCallId
├── executionTargetId
├── providerRef
└── generation
~~~

### Handle 不是 authority

查询时仍要：

~~~text
handle
↓
trusted stored binding
↓
current session / scope / policy
↓
provider-specific query
~~~

模型传来的 path、command、target、permission、owner 都不能成为授权事实。

### 不复制 provider state

通用层只保留 reference / binding / observation。

真实 execution state 继续属于 PRoot、Subagent、Remote、Download 等 provider。

---

## 19. 推荐 Agent-facing tools

### jobs.status

只读查询一个或多个 handle，返回：

- running / terminal / unknown；
- progress summary；
- terminal kind；
- resultRef；
- stale / source unavailable。

### jobs.await

~~~text
jobs.await(handles, condition = ANY | ALL)
~~~

表达 reasoning dependency。

单次 resident wait budget 由平台决定，不允许模型无限占住主进程。

### jobs.cancel

仅 provider 明确 cancellable 时可用。

必须保持：

~~~text
cancel requested != execution stopped
~~~

只有 terminal reconciliation / no-start proof 才能释放 resource owner。

### jobs.collect

若结果涉及 file import、artifact materialization、delayed Workspace effect 或 budget settlement，collect 继续独立。

现有 Linux Job 已经说明：

> **status 与 collect 分离是有价值的安全边界。**

第一版不一定需要独立 jobs.result；小结果由 status 返回 summary/ref，大结果按需从 artifact、log、collect、tool.result.read、file read 获取。

---

## 20. UI 与 Tasks

Conversation 不应被一个后台 Job 锁死。

示例：

~~~text
● Running tests
  ./gradlew test

  Background · 2m 13s

  [View logs] [Cancel]
~~~

完成：

~~~text
✓ Tests finished
  214 tests · 0 failed

  [View result]
~~~

未知：

~~~text
! Execution state unknown
  Result cannot be assumed.

  [Review] [Inspect]
~~~

对 supportsPromotion=true 的 command card，可以参考 Claude Code / VS Code 提供：

~~~text
[Continue in Background]
~~~

用户点击后调用 Runtime promotion，不生成一个假的新模型 ToolCall。

Helix 已经有 Tasks 页面，因此不建议新增第二套 Background Tasks 页面。未来可以逐步扩展成：

~~~text
Tasks
├── Linux Jobs
├── Subagents
├── Downloads
└── Remote Jobs
~~~

统一展示语义，不统一底层 Runtime。

---

## 21. 与 Subagent、Automation、ExecutionHost 的关系

### Subagent

未来可以：

~~~text
agent.spawn(background = true)
  -> AgentHandle
~~~

但 AgentHandle != ProcessJob。Subagent 还有 model、context、tool window、permission ceiling、child session、final report。

可共享：

~~~text
AsyncHandle
├── PROCESS_JOB
├── SUBAGENT
├── DOWNLOAD
└── REMOTE_TASK
~~~

共享 observation / join，底层 runtime contract 分开。

### Automation

Job finished 不是 Automation triggered。

只有用户明确要求“Job 完成后自动继续”时，才建立：

~~~text
JobCompleted trigger
↓
fresh successor Turn
~~~

### ExecutionHost

Job 绑定原 ExecutionTarget，默认不在运行中迁移。

~~~text
phone Job terminal / checkpoint
↓
persist facts
↓
next Turn may bind Desktop
~~~

而不是迁移正在执行的进程。

---

## 22. 与 ContextCompiler 的关系

后台工作增加后，不能把所有 status/log 注入每次 ModelCall。

建议增加：

~~~text
ContextCandidate(kind = JOB_OBSERVATION)
~~~

只注入：

- 当前任务仍相关的 Job；
- 自上次 ModelCall 后新 terminal 的 Job；
- 模型明确 await 的 Job；
- blocking dependency。

模型看到的应类似：

~~~text
job test-123: SUCCEEDED
summary: 214 tests passed
artifactRef: ...
~~~

完整 log 再按需读取。

---

## 23. 状态与资源模型

通用 projection 可以有：

~~~text
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCEL_REQUESTED
CANCELLED
UNKNOWN
~~~

provider-specific 状态继续保留。例如 PRoot 的 ORPHANED / INPUT_INVALID 不必强行重命名；通用层可以把 ORPHANED 投影为 UNKNOWN / needs reconciliation，同时 UI/audit 保留 raw state。

后台不等于免费。仍要限制：

- execution deadline；
- lease；
- output / storage；
- CPU / memory；
- battery / thermal；
- concurrent cap；
- effect footprint ownership。

需要明确区分：

~~~text
foregroundWaitBudget
executionLease
modelTurnBudget
GoalBudget
~~~

---

## 24. 安全 invariant

1. **Background 不提升权限**：promotion 沿用原 scope、approval、credentials、target、resource footprint。
2. **Cancel 不代表停止**：cancel receipt 不能提前释放 effect ownership。
3. **Await timeout 不代表 Job failed**，更不代表 effect 未发生。
4. **Process death 不 replay**：后台 owner 可继续则继续；无法确认则 UNKNOWN / ORPHANED。
5. **Promotion 不允许 restart masquerade**：无法证明 same execution identity 就不支持 promotion。
6. **Foreground → background 不重置预算或 deadline**。
7. **Background 不隐式迁移 ExecutionTarget**。

---

## 25. 推荐分阶段实施

### Phase J0 — 当前已有

- Linux async Job；
- start / status / cancel / collect；
- Runtime owner；
- lease / budget；
- bounded logs；
- Tasks UI；
- ORPHANED / UNKNOWN / reconcile；
- process death 不 replay。

**不重做。**

### Phase J1 — Generic async observation / join

增加：

~~~text
AsyncHandle / JobHandle projection
jobs.status
jobs.await
jobs.cancel
~~~

先只接 Linux Job。

退出条件：

- launch ToolCall settle 后 AgentLoop 可继续；
- await 只 join 指定 handles；
- Job completion 不改写原 ToolResult；
- relevant JobObservation 可进入 ContextCompiler；
- crash 后仍用 successor Turn。

### Phase J2 — Launch mode

为 async-capable executor 增加：

~~~text
FOREGROUND
BACKGROUND
AUTO
~~~

优先 Linux build/test/command、long download，以及未来 subagent。

### Phase J3 — Safe foreground promotion

只对具备 same-execution detach proof 的 Runtime 开启：

~~~text
Continue in Background
~~~

优先 Linux process。

**不做 generic background(anyToolCall)。**

### Phase J4 — Additional providers

逐步接入：

- subagent；
- remote command；
- download；
- MCP async task（仅协议/runtime 真实支持时）。

### Phase J5 — Optional continuation trigger

只有产品明确需要时再增加 continueWhenComplete：

~~~text
Job terminal
↓
fresh successor Turn
~~~

不恢复 old Turn。

---

## 26. 对总体架构路线的影响

在 helix-agent-capability-architecture-convergence-2026-09-28.md 的路线中，建议加入：

~~~text
P1
Tool Exposure
Atomic Tool Binding
Plugin lifecycle
ContextCompiler
Minimal ExecutionTarget
Async Job observation / await

P2
ExecutionHost / Node
Observation / Artifact
Subagent primitives
AUTO / safe promotion expansion

P3
Automation / Trigger
optional automatic continuation
~~~

这不会重新打开 AgentLoop / TurnEngine ownership。

它反而强化 shallow harness：

~~~text
launch settles
Job runs independently
join explicitly
old Turn never replayed
~~~

---

## 27. 外部依据

### OpenAI

- Async Tool Calling\
  https://developers.openai.com/api/docs/guides/async-tool-calling

### Claude Code

- Interactive mode / Background Bash commands\
  https://code.claude.com/docs/en/interactive-mode
- Tools reference / Background commands\
  https://code.claude.com/docs/en/tools-reference

### VS Code / GitHub Copilot Agent

- Use tools with agents / Continue terminal commands in background\
  https://code.visualstudio.com/docs/agents/run/tools

### Cursor

- Subagents\
  https://prod.cursor.com/docs/subagents
- TypeScript SDK\
  https://cursor.com/docs/sdk/typescript
- Cloud Agent API\
  https://prod.cursor.com/docs/cloud-agent/api/endpoints

### OpenCode

- Agents\
  https://opencode.ai/v2/docs/agents
- Commands / background child session\
  https://opencode.ai/v2/docs/commands

### Operit2

- CoreNode / Host / Space / Binding / cross-device handoff\
  https://github.com/AAswordman/Operit2

### Helix 现有依据

- docs/adr/runtime/002-terminal-and-jobs.md
- docs/architecture/terminal.md
- docs/adr/agent/001-turn-coordination.md
- docs/research/helix-agent-capability-architecture-convergence-2026-09-28.md

---

## 28. 最终建议

> **Helix 应增加“异步执行 + 明确 join”的通用能力，而不是增加一个万能后台工具：等待用 jobs.await 表达依赖；后台化优先在启动时选择 FOREGROUND / BACKGROUND / AUTO；运行中 promotion 只对能证明 same-execution detach 的 Runtime 开放；现有 Linux Job 是基础，不应重写。**
