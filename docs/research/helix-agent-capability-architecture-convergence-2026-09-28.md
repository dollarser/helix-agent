# Helix Agent 能力架构收敛调研（2026-09-28）

> 状态：研究结论 / 架构输入，不直接替代 ADR、HXA 或实现状态。
>
> 本文汇总并校正 2026-09-28 关于 Helix 后续结构性重构的讨论，结合现有
> agent-plugin-ecosystem-and-mobile-use-2026-09-28.md、工具生态研究、当前 Plugin Platform /
> Mobile Use MVP，以及对 Codex、Cursor、Claude、VS Code、OpenCode、Operit / Operit2 的核验。
>
> 目标不是重新打开 Core Engine，而是明确：哪些边界已经稳定、哪些能力应在 Core 周围继续收敛、
> 哪些扩展应延后，避免 Helix 从 shallow harness 演化成第二套 workflow engine。

---

## 1. 结论摘要

本轮讨论的总体结论成立，核心方向不需要推翻；需要修正的是优先级与两个边界表述。

最终建议收敛为：

1. **P0：Tool Exposure / Admission / Discovery**
   先解决“能力已注册，但不应全部永久暴露给模型”的问题。Helix 已经出现实际工具窗口拥塞，
   因此这不是架构洁癖，而是任务正确性问题。

2. **P1-A：Atomic Tool Binding**
   ToolDescriptor 与 ToolExecutor 应以同一原子 binding snapshot 解析。该基础设施应比
   Marketplace 扩张更早完成，至少与 Plugin installation 同期，而不是排在扩展体系之后。

3. **P1-B：Plugin Platform convergence**
   统一 Plugin installation identity、version、source、component ownership、install/update/uninstall、
   session selection 和 Marketplace 顶层视图；MCP、Skill、Native runtime 继续保留各自技术职责。

4. **P1-C：ContextCompiler MVP**
   Expert、Memory、Workspace、AGENTS.md、Skill、Plugin、Reference、Attachment、Tool observation、
   Goal、Plan 等来源不能长期继续由 Request Assembler 手工堆叠，应开始形成统一上下文候选、
   provenance、priority 与 budget 模型。

5. **P1-D：Minimal ExecutionTarget seam**
   现在只引入极薄的执行目标抽象，默认仍是 ThisPhone。不要现在就实现完整分布式 Node runtime。

6. **P2：Execution Host / Node、Observation/Artifact、Subagent primitives**
   当 Desktop、SSH、Emulator、Remote Android 等真实远程执行需求出现后，再扩展完整 Host/Node；
   Browser/Mobile/Desktop Use 统一 observation/artifact 协议而不是统一 runtime；subagent 保持
   model-led shallow primitives。

7. **P3：Automation / Trigger 与更完整 remote orchestration**
   Automation 只负责“什么时候创建新的 Run / Turn”，不进入主 AgentLoop 充当 workflow brain。

与此同时，以下语义 ownership 应继续稳定：

- TurnEngine：Turn 生命周期；
- AgentLoop：模型/工具迭代；
- Dispatcher：工具执行单入口；
- Approval：审批事实；
- effect truth：外部副作用事实；
- Goal：durable objective 语义；
- recovery：successor Turn / Run，而不是复活旧执行栈。

---

## 2. 本轮研究回答的问题

在 Plugin Platform、Mobile Use、MCP、Skills、Connector、Marketplace 和统一 ToolRegistry 已逐步出现后，
Helix 面临的问题不再是“还缺哪个功能”，而是：

1. 工具越来越多时，模型究竟应该看到什么？
2. Plugin、MCP、Skill、Native tool 是否要合并成一个 registry？
3. descriptor 与 executor 动态更新时如何避免版本错配？
4. 手机、桌面、SSH、Emulator、Remote Android 是否需要统一执行节点抽象？
5. Memory、Skill、Workspace、Reference、Tool result 等上下文来源如何控制预算？
6. Mobile Use、Browser Use、Desktop Use 应统一到什么程度？
7. 是否需要复杂的 planner/manager/executor 多 Agent orchestration？
8. Automation / Workflow 是否应该进入主 AgentLoop？
9. 哪些 Core Engine ownership 已经足够稳定，不应该再次大改？

---

## 3. 核心设计原则

### 3.1 Available 不等于 Injected

Helix 后续所有 capability 系统都应遵守：

~~~text
available / installed
        !=
admitted for current session / turn
        !=
model-visible in this request
        !=
authorized to execute
~~~

更具体地：

~~~text
registered
   ↓
admitted
   ↓
model-visible
   ↓
tool selected by model
   ↓
policy / capability / approval
   ↓
execution
~~~

**存在不等于每轮注入。**

这条原则同时适用于：

- Tool；
- Skill；
- Plugin；
- MCP catalog；
- Memory；
- Conversation reference；
- Workspace context；
- Tool observation；
- Subagent result。

### 3.2 统一产品身份，不机械合并 runtime

Helix 应统一：

- 安装身份；
- 版本；
- 来源；
- component ownership；
- 生命周期；
- Marketplace 顶层视图；
- session selection。

但不应把以下东西做成一个万能 registry：

- Plugin registry；
- Skill repository；
- MCP connection/runtime；
- Tool execution contract；
- Approval；
- AgentLoop。

推荐继续保持：

~~~text
Plugin Installation / Catalog
        │
        ├── Skill component  → SkillRepository
        ├── MCP component    → McpAppService
        └── Native component → host runtime
                                  │
                                  ↓
                           Tool Binding layer
                                  │
                                  ↓
                       Dispatcher / Policy / Approval
~~~

### 3.3 Shallow harness + model-controlled workflow

不要把产品能力增长误解为必须增加：

~~~text
PlannerAgent
  ↓
ManagerAgent
  ↓
ResearchAgent
  ↓
ExecutorAgent
  ↓
ReviewerAgent
~~~

Helix 的默认方向仍应是：

~~~text
model
  +
clear context
  +
high-quality tools
  +
durable state
  +
permission / approval boundaries
  +
verification feedback
~~~

复杂流程由模型根据任务决定；Harness 提供原语和安全边界。

---

## 4. P0：Tool Exposure / Admission / Discovery

### 4.1 为什么它是当前最高优先级

现有测试已经出现过类似链路：

~~~text
64 tools
  ↓
核心 write 被挤出模型窗口
  ↓
模型只剩 search / mkdir / linux 等工具
  ↓
本来可完成的任务失败
~~~

因此问题不只是 token 成本，而是：

- 核心工具可能不可见；
- 长尾工具增加错误选择率；
- schema 过多污染上下文；
- Plugin / MCP 越丰富，问题越严重。

### 4.2 推荐三层窗口

~~~text
Core window
  lifecycle
  result / discovery
  read / write / edit
  essential workspace

Contextual window
  mobile.*
  browser.*
  files.*
  skills.*
  task-specific native tools

Dynamic window
  MCP
  A2A
  plugin-discovered long-tail tools
~~~

### 4.3 应形成正式概念

建议将下面三层写进 Tool Framework：

~~~text
registered
admitted
model-visible
~~~

其中：

- registered：系统知道此工具；
- admitted：当前 Session / Turn / mode / provider / capability 允许考虑；
- model-visible：本轮真正投影进模型 schema。

后续执行授权仍由 Dispatcher / Policy / Approval 决定，因此：

> tool visibility 不是 permission。

### 4.4 不建议现在增加独立 Tool Router 模型

Helix 已有 registry、mode/policy、MCP discovery、request assembler。
第一阶段应使用 deterministic admission + discovery，而不是再引入一个“路由模型”。

只有当真实 eval 显示：

- 工具选择错误仍高；
- discovery 往返成本过大；
- deterministic rules 无法处理长尾；

才考虑 learned/model router。

---

## 5. P1-A：Atomic Tool Binding

### 5.1 当前结构的风险

当前方向中 descriptor 与 executor 是两个事实源：

~~~text
ToolRegistry
  (name, version) → ToolDescriptor

ToolImplementationRegistry
  (name, version) → ToolExecutor
~~~

动态 MCP / A2A replace 会连续更新两处；Dispatcher 也会分别 resolve。

即使两个 registry 单独线程安全，也不能保证跨 registry 的同一 generation 原子一致：

~~~text
new descriptor
+
old executor
~~~

理论上可能在极短窗口出现。

Plugin 数量增加只会放大这个问题。

### 5.2 推荐模型

~~~text
ToolBinding
├── id / name / version
├── descriptor
├── executor
├── source
├── generation
├── executionTarget
└── capabilityRequirements
~~~

单次调用：

~~~text
ToolCall
  ↓
resolve(name, version)
  ↓
ToolBinding snapshot
  ├── descriptor
  └── executor
~~~

要求：

- register 原子；
- replace owner snapshot 原子；
- remove 原子；
- Dispatcher 一次 resolve；
- model exposure 读取该 binding 的 descriptor projection；
- approval contract 继续绑定 descriptor contractHash；
- MCP/A2A/Plugin 动态更新走同一 binding transaction。

### 5.3 与 Plugin Platform 的关系

ToolBinding 属于执行基础设施，PluginInstallation 属于扩展生命周期。

两者应解耦开发，但优先级上 ToolBinding 不应拖到 Marketplace 之后。

推荐：

~~~text
P0 Tool Exposure
 ↓
P1-A Atomic Tool Binding
 ↓
P1-B Plugin lifecycle convergence
~~~

也可以让 P1-A / P1-B 并行，但不要先大量扩展 dynamic source，再长期保留 split binding。

---

## 6. P1-B：Plugin / Connector / Marketplace 收敛

### 6.1 Agent Plugins 作为 portable floor

当前最合理的可移植底座仍是：

~~~text
plugin.json
skills/
mcp.json
~~~

Host-specific 能力继续进入 Helix namespace / native extension。

因此 Helix 的 Plugin 方向应是：

~~~text
Agent Plugins portable floor
+
Helix host-known native extension
~~~

而不是发明一个能够执行任意代码的万能插件 ABI。

### 6.2 ConnectorService 应继续收窄

目标：

~~~text
PluginInstallationService
  preview / validate
  install / update / uninstall
  revision / content hash
  component ownership
  selection

ConnectorService
  endpoint config
  credentials / OAuth
  connection test
  MCP enable / disable
  revoke / reconnect
  remote consent lifecycle
~~~

### 6.3 Marketplace 应 Plugin-first

目标：

~~~text
MarketplaceItem
  type = PLUGIN
  components = [SKILL, MCP, NATIVE]
~~~

UI 可继续按 Skills、Connections、Device、Coding、Productivity 等维度筛选，
但安装、版本、更新、卸载的顶层身份统一成 Plugin。

### 6.4 明确非目标

近期不要开放：

- arbitrary Kotlin/JAR；
- arbitrary DEX/APK；
- arbitrary JS hooks in app process；
- Plugin 自带第二套 AgentLoop；
- Plugin 绕过 Dispatcher / Policy / Approval。

---

## 7. P1-C：ContextCompiler / ContextBudgeter

### 7.1 为什么需要前移

Helix 当前或即将拥有：

~~~text
System
Session config
Expert
Memory
Workspace
AGENTS.md
Skill
Plugin
Conversation reference
Attachments
Tool observations
Goal
Plan
Recovery summary
~~~

如果继续由 ChatRequestAssembler 针对每一种来源手工拼接：

- priority 会散落在不同模块；
- token budget 无统一语义；
- provenance 难追踪；
- 新增来源必须侵入 assembler；
- summarize / drop / on-demand 很难统一。

### 7.2 推荐最小数据模型

~~~text
ContextCandidate
├── kind
├── source
├── priority
├── estimatedTokens
├── freshness
├── provenance
├── scope
└── contentRef
~~~

然后：

~~~text
ContextCompiler
      ↓
ContextBudgeter
      ↓
mandatory
selected
on-demand
summarized
dropped
      ↓
CompiledContext
~~~

### 7.3 第一版不要做复杂 reranker

MVP 只需要解决：

- mandatory pinning；
- priority；
- provenance；
- token estimate；
- per-kind quota；
- summarize/drop；
- stable ordering。

这已经能把“很多来源拼 Prompt”升级为真正的 context engineering substrate。

---

## 8. P1-D / P2：ExecutionTarget 与 Execution Host / Node

### 8.1 为什么这个抽象长期成立

随着未来出现：

~~~text
ThisPhone
Desktop
SSHHost
Emulator
RemoteAndroid
~~~

如果每类执行环境各自增加 Desktop Connector、SSH Connector、ADB Connector、
Remote Android Connector，就会重复：

- capability；
- target identity；
- permissions；
- secrets locality；
- routing；
- result provenance。

因此长期应有统一 execution target/host 概念。

### 8.2 但完整 Node runtime 不应现在实现

当前只需要一个极薄 seam：

~~~text
ExecutionTarget
├── targetId
├── kind
├── capabilities
└── locality
~~~

第一阶段：

~~~text
targetId = this-phone
~~~

ToolBinding 可引用：

~~~text
ToolBinding
  executionTarget
~~~

以后再扩展：

~~~text
desktop:macbook
ssh:server-a
emulator:pixel-api36
android:remote-device
~~~

### 8.3 P2 再实现完整 Host/Node runtime

等真实需求出现后，再引入：

- discovery；
- pairing；
- heartbeat；
- routing；
- lease；
- remote execution；
- secret locality；
- reconnect / stale target；
- cross-device observation。

不要因为 Operit2 / Codex Remote 已有 Node/Remote 概念，就提前复制完整分布式系统。

---

## 9. 权限归属：原结论需要修正

不应使用：

> 权限属于 Node，而不是属于 Session 或 Plugin。

更准确的模型是：

### 9.1 Node / Host 决定物理能力

例如：

- filesystem；
- Root；
- Shizuku；
- Accessibility；
- camera；
- local credentials；
- SSH key；
- local process access。

### 9.2 Session / Agent / Tool policy 决定 AI 是否可用

推荐 invariant：

~~~text
EffectivePermission
=
HostCapability
∩ SessionPolicy
∩ AgentDelegation
∩ ToolPolicy
∩ UserApproval
~~~

可以概括成：

> **Node 决定“能不能做”；Policy 决定“AI 被不被允许做”。**

Plugin metadata 只能声明 requirement / provenance，不能授予 permission。

---

## 10. P2：Observation / Artifact substrate

### 10.1 不统一 Runtime

不要做一个巨大 ComputerUseEngine 来同时承载 Android、Browser、Desktop。

这些 runtime 的底层环境完全不同：

- Mobile Use → Android Accessibility / Shizuku / Root / screenshot；
- Browser Use → DOM / accessibility tree / browser snapshot / screenshot；
- Desktop Use → OS accessibility / UI automation / screenshot；
- Terminal → stdout/stderr / process state。

### 10.2 统一 Observation 协议

真正值得共享的是：

~~~text
ObservationRef
├── kind
├── snapshotId
├── artifactRef
├── summary
├── generation
├── sourceHost
└── createdAt / freshness
~~~

以及：

~~~text
ArtifactRef
stale snapshot semantics
generation check
source target provenance
multimodal projection
~~~

目标：

~~~text
Mobile Use ─────┐
Browser Use ────┼→ Observation / Artifact protocol
Desktop Use ────┘
~~~

这样统一的是模型“如何观察世界”的协议，而不是执行 runtime。

---

## 11. P2：Model-led Subagent primitives

### 11.1 应提供原语，而不是工作流角色

推荐：

~~~text
agent.spawn
agent.message
agent.result
agent.cancel
~~~

必要时增加 wait/status，但不预设角色图。

每个 child 可以拥有：

~~~text
separate context
model
tool window
permission ceiling
workspace view
~~~

### 11.2 Helix 自己应采用 delegated permission ceiling

建议：

~~~text
child effective permissions
<=
parent delegated permissions
~~~

这是一条 Helix 安全 invariant，不是所有主流 Agent 当前都统一采用的既成事实。

Subagent 不应通过切换 model、加载 Plugin、选择新的 tool set 或创建新 session，
重新获得 full access。

---

## 12. P3：Automation / Trigger

Automation 不应进入主 AgentLoop。

正确关系：

~~~text
Trigger
  ↓
Automation
  ↓
create new Run / Turn
  ↓
existing AgentLoop
~~~

而不是：

~~~text
AgentLoop
  ↓
Workflow DAG runtime
  ↓
AgentLoop
~~~

Scheduler / Trigger 只负责：

> **什么时候启动 Agent。**

如何完成目标仍由模型 + tools + existing AgentLoop 决定。

适合未来承载：

- scheduled summary；
- notification trigger；
- GitHub issue change；
- file change；
- device event；
- webhook；
- recurring maintenance。

如果未来需要 deterministic repeatable workflow，可作为额外 automation product surface，
而不是 Core AgentLoop 的 mandatory brain。

---

## 13. Core Engine：冻结 semantic ownership，而不是冻结所有接口

不建议再重新设计：

~~~text
TurnEngine ownership
AgentLoop ownership
Dispatcher single entry
approval ownership
effect truth
Goal durable semantics
recovery = successor Turn / Run
~~~

但可以在稳定 Core 周围增加 seam：

~~~text
ToolAdmission
ToolBindingSnapshot
ContextCompiler
ExecutionTarget
ObservationRef
~~~

因此正确表述不是：

> Core Engine 完全冻结。

而是：

> **Core semantic ownership 冻结；外围 capability substrate 可以继续演进。**

---

## 14. 推荐最终架构

~~~text
                        ┌──────────────────────────┐
                        │        AgentLoop         │
                        │ model-led workflow       │
                        └────────────┬─────────────┘
                                     │
                         CompiledContext + ToolWindow
                                     │
                 ┌───────────────────┴───────────────────┐
                 │                                       │
        ┌────────▼─────────┐                    ┌────────▼────────┐
        │ ContextCompiler  │                    │ Tool Admission  │
        │ / Budgeter       │                    │ / Discovery     │
        └────────┬─────────┘                    └────────┬────────┘
                 │                                       │
     Memory / Skill / Workspace /              registered → admitted
     Expert / Ref / Observation                → model-visible
                 │                                       │
                 └───────────────────┬───────────────────┘
                                     │
                            ┌────────▼────────┐
                            │ ToolBinding     │
                            │ atomic snapshot │
                            └────────┬────────┘
                                     │
                              ExecutionTarget
                                     │
                            ┌────────▼────────┐
                            │   Dispatcher    │
                            │ Policy/Approval │
                            └────────┬────────┘
                                     │
                  ┌──────────────────┼──────────────────┐
                  │                  │                  │
               ThisPhone          Desktop           SSH/Remote
                  │                  │                  │
              Mobile Use        Desktop Use         shell/tools
                  │                  │                  │
                  └──────────┬───────┴──────────┬──────┘
                             │                  │
                       ObservationRef       ArtifactRef
~~~

Plugin Platform 从侧面提供 capability：

~~~text
PluginInstallation
  ├── Skill
  ├── MCP
  └── Native
        ↓
registered capability
        ↓
Tool / Context admission
~~~

注意：Plugin 不拥有 AgentLoop，不直接拥有 permission，也不直接绕过 Dispatcher。

---

## 15. 推荐实施路线

| 阶段 | 主题 | 主要目标 | 是否改 Core ownership |
| --- | --- | --- | --- |
| P0 | Tool Exposure / Admission | 解决核心工具被长尾 schema 挤出；形成 registered/admitted/visible | 否 |
| P1-A | Atomic Tool Binding | descriptor + executor 同一 binding snapshot | 否 |
| P1-B | Plugin lifecycle convergence | Plugin installation / Connector / Marketplace ownership 收敛 | 否 |
| P1-C | ContextCompiler MVP | 上下文候选、provenance、priority、budget、drop/summarize | 否 |
| P1-D | Minimal ExecutionTarget | 先定义 this-phone target seam | 否 |
| P2-A | Execution Host / Node | Desktop / SSH / Emulator / Remote Android | 否 |
| P2-B | Observation / Artifact | Mobile / Browser / Desktop 共用 observation substrate | 否 |
| P2-C | Subagent primitives | model-led spawn/message/result/cancel | 否 |
| P3 | Automation / Trigger | 外部事件触发新 Run / Turn | 否 |

### 15.1 与现有 Plugin 研究文档相比的两处顺序修正

现有 Plugin 研究把 PluginPackageReader、PluginInstallationService、ConnectorService、
Marketplace、Session selection 作为主线，ToolBinding 独立并行。

本轮核验后建议进一步强调：

1. **ToolBinding 应在 P1 更靠前**
   可以与 Plugin installation 并行，但不应等 Marketplace 扩张后再处理。

2. **ContextCompiler MVP 应前移到 P1/P2 边界**
   因为 Plugin / Skill / Expert / Reference / Memory 都在增加；上下文污染与 tool exposure
   是同一个“available 不等于 inject every turn”问题的两个侧面。

---

## 16. 当前不建议做的重构

近期明确不做：

1. 重写 AgentLoop；
2. 重写 TurnEngine；
3. 引入固定 Planner/Manager/Executor pipeline；
4. 把 Workflow DAG 嵌进 AgentLoop；
5. 把 PluginRegistry 做成万能 service locator；
6. 把 Skill/MCP/Tool runtime 合成一个 registry；
7. 所有 installed Plugin schemas 永久注入模型；
8. 立即实现完整跨设备 Space/Node 分布式系统；
9. 用统一 ComputerUseEngine 强行合并 Android/Browser/Desktop runtime；
10. 开放 arbitrary DEX/JAR/APK 或任意主进程 JS hook；
11. 让 Plugin/Skill/MCP metadata 自行授权；
12. 为旧开发期 schema/enum 保留无必要兼容层。

---

## 17. 与已有文档的关系

本文是横向架构收敛研究，不替代以下专项文档：

- docs/research/agent-plugin-ecosystem-and-mobile-use-2026-09-28.md
  - Plugin ecosystem、Mobile Use、Connector / Marketplace 详细研究。

- docs/architecture/plugin-platform-refactor-2026-09-28.md
  - 当前 Plugin Platform 的具体重构设计。

- docs/research/modules/04-tools-browser-and-extensions.md
  - Tool exposure、Browser、MCP/Skill/Connector/A2A 的模块化研究。

- docs/research/modules/07-agent-capability-determinants-and-improvement-guide.md
  - Agent 能力决定因素、长任务成功率、移动端 Harness 能力提升框架。

后续若将本文结论正式裁决为架构要求，应分别回写：

- Tool Framework / Plugin topic ADR；
- Context / Session topic ADR；
- Runtime / Provider / remote execution topic ADR；
- active HXA implementation sequence。

本次任务只记录研究结论，不修改 ADR/HXA。

---

## 18. 外部依据

本轮讨论与核验主要参考：

- Agent Plugins 1.0 Specification
  <https://agent-plugins.org/specification>

- OpenAI Plugin architecture / Skills
  <https://developers.openai.com/plugins/concepts/plugins>
  <https://developers.openai.com/plugins/concepts/skills>

- Cursor Plugins / Customize / Cloud Agent Automations
  <https://prod.cursor.com/docs/plugins>
  <https://cursor.com/changelog/customize>
  <https://cursor.com/docs/cloud-agent/automations>

- VS Code Agent tool sets / AI extensibility
  <https://code.visualstudio.com/docs/agent-customization/tool-sets>
  <https://code.visualstudio.com/api/extension-guides/ai/ai-extensibility-overview>

- Anthropic long-running agent harness / Agent Skills / MCP
  <https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents>
  <https://docs.claude.com/en/docs/agents-and-tools/agent-skills/best-practices>
  <https://docs.anthropic.com/en/docs/mcp>

- OpenCode permissions / agents / plugins
  <https://opencode.ai/docs/permissions/>
  <https://opencode.ai/docs/agents/>
  <https://opencode.ai/docs/zh-cn/plugins/>

- OpenAI Codex harness
  <https://openai.com/index/unlocking-the-codex-harness/>

- Operit / Operit2
  <https://github.com/AAswordman/Operit>
  <https://github.com/AAswordman/Operit2>

这些来源用于支持方向判断；Helix 的最终优先级仍以项目已有缺陷证据、实际 eval、
架构约束和 owner 裁决为准。

---

## 19. 最终判断

Helix 当前不需要再进行一次“大 Core 重构”。

下一阶段真正需要的是把已经出现的能力层变得可组合但不互相污染：

~~~text
Tool catalog
  → 有界 exposure

Dynamic tool source
  → atomic binding

Plugin ecosystem
  → unified lifecycle, layered runtime

Context sources
  → compiler + budget

Execution environments
  → thin target abstraction, later host/node

Computer/Mobile/Browser use
  → shared observation protocol, separate runtime

Subagents
  → model-led primitives

Automation
  → trigger new Run/Turn
~~~

这条路线既吸收当前桌面 Agent 的工具、上下文、插件、remote/subagent 设计，也保留 Helix
作为 Android 单设备产品最重要的优势：**durable truth、明确权限边界、shallow harness 和模型主导工作流。**
