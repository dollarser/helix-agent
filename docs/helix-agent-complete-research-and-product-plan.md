# Helix Agent 完整竞争研究、Harness 架构评估与产品优化方案

> 研究对象：`dollarser/helix-agent`\
> 直接移动端竞品：Operit、Operit2、PalmClaw、RikkaHub、AndCode、ClawMobile、DSHA\
> 桌面 / 通用 Agent 参照：Codex、Claude Code、DeepSeek Harness、pi coding agent、WorkBuddy 等\
> 核验基线：2026-09-11\
>
> 本文把此前的 **Agent Harness 深度架构评估** 与 **产品功能横评** 完整合并，并在最前面增加统一判断、统一优先级和产品路线。后两部分保留原专题报告的详细论证，以免为了去重而丢失有价值的实现细节。

---

## 0. 如何阅读这份报告

这份报告分为三个层次：

1. **Part I — 综合结论与统一路线**：适合做产品决策、版本规划和对外定位。
2. **Part II — Harness / 架构深度评估**：适合做技术设计、重构和 HXA / Issue 拆分。
3. **Part III — 产品功能横评**：适合做 PRD、竞品分析、UI 信息架构和功能优先级决策。

如果只看一部分，优先看 Part I；如果准备继续开发 Helix Harness 2.0，看 Part II；如果准备做下一版产品规划、官网、README 和 Demo，看 Part III。

---

# Part I — 综合结论与统一优化路线

## 1. Helix 当前所处的位置

Helix 已经不应被定义成“一个支持工具调用的 Android 聊天客户端”。从当前实现与文档看，它更接近一个 **Android-native Agent Harness + 本机执行工作台**：Agent Loop、工具调用、审批、工作区、浏览器、代码执行、Goal、MCP、A2A、Skill、恢复和审计都已经形成较明确的边界。

当前最大矛盾不是“底层能力不足”，而是：

> **工程成熟度高于用户感知成熟度，Harness 设计成熟度高于生产主干抽象成熟度。**

这会同时带来两种风险：

- 技术侧继续加 Schedule、Channel、Subagent、Workflow、Default Assistant 等能力后，`ChatService / ChatModelLoop / ChatRequestAssembler` 可能继续膨胀；
- 产品侧虽然底层已经支持很多能力，但用户仍可能把 Helix 理解成“另一个 Android AI Chat”，因为 Tasks、Plan、Progress、Artifacts、Git Diff、Capability Center、Scheduled Goal 等缺少足够强的产品表达。

因此下一阶段不应简单追求“功能更多”，而应同时完成两件事：

```text
Harness 主干收敛
        +
现有能力产品化
```

---

## 2. 最核心的竞争判断

### 2.1 不应该和 Operit 拼功能总数

Operit 已经形成高能力密度的一体化 Android Agent 平台，覆盖聊天、模型、Workspace、Ubuntu、浏览器、Android 自动化、Workflow、Memory、Skill、MCP、ToolPkg、市场、语音、悬浮入口、默认助理等。Helix 如果按功能清单逐项补齐，很容易丢掉当前最大的工程优势。

### 2.2 不应该只和 AndCode 拼 Coding

AndCode 的优势是把现有 Coding Agent Runtime 包装成触控优先的移动工作台。Helix 应补 Git/Diff/Test 形成开发者闭环，但更重要的差异化是：

```text
Coding + Browser + Android + Files + MCP/A2A
```

也就是跨域小任务，而不是完整 IDE。

### 2.3 不应该只宣传“更安全”

Helix 的 Policy / Approval / isolation / audit 很强，但“安全机制数量”不是首要购买理由。安全必须转化成用户收益：

- 能知道 Agent 改了什么；
- 失败后不会盲目重试副作用；
- App 被杀后可以继续；
- 能理解什么能力已授权；
- 能把某类低风险动作只授权给这个任务或这个 Workspace。

### 2.4 真正有机会形成壁垒的是组合

```text
Android-native
+ Provider-neutral
+ durable Agent state
+ deterministic tool settlement
+ recoverable side effects
+ files/web/code/device composition
+ mobile-first task UX
```

单独的 MCP、Skill、PRoot、Browser、Android Tool 都不是壁垒。

---

## 3. 统一产品定义

推荐定位：

> **Helix 是 Android 上的 AI 执行工作台：连接用户自己的模型，将文件、网页、代码和手机能力组合成可执行、可检查、可恢复的任务。**

更短的宣传语：

> **让 AI 真正在你的 Android 手机上工作。**

英文：

> **Helix is a local-first AI execution workspace for Android — connect your own models and let agents work with files, web, code and device capabilities directly on your phone.**

三大首屏卖点建议只保留：

- **Works on your phone** — 基础任务不依赖电脑或云 Worker；
- **Bring your own model** — OpenAI、Claude、DeepSeek、自建 endpoint 等；
- **Tasks you can inspect and resume** — 有过程、有产物、中断可继续。

---

## 4. 推荐的产品信息架构

```text
Chats
Tasks
Workspace
Capabilities
Settings
```

### Chats

负责普通问答、一次性 Act、Plan 发起和上下文交互。

### Tasks

负责 Goal、Scheduled、Running、Needs You、Completed、Failed。Tasks 应成为 Helix 从 Chat App 转向 Agent Product 的核心页面。

### Workspace

负责项目、文件、最近修改、Git Diff、Artifacts 和 Runtime 工作区。

### Capabilities

负责 Browser、Android、Notifications、Calendar、Accessibility、Developer Runtime、Root、MCP、Skills 等能力的状态、安装、测试、修复、范围和关闭。

### Settings

只保留 Provider、模型、显示、语言、安全高级选项等真正的设置项。

---

## 5. 推荐的统一 Harness 目标形态

```text
Chat / Goal / Share / Voice / Widget / Channel
                     │
                     ▼
                AgentRuntime
                     │
          ┌──────────┴──────────┐
          ▼                     ▼
   TurnCoordinator          GoalDriver
          │                     │
          └──────────┬──────────┘
                     ▼
                 AgentLoop
                     │
       ┌─────────────┼─────────────┐
       ▼             ▼             ▼
 PromptAssembly   ContextEngine   ModelRouter
                     │
                     ▼
                 ToolRuntime
                     │
                Dispatcher
              /      |       \
         Android   QuickJS   PRoot
```

统一原则：

- Chat / Plan / Act / Goal 是同一个 Loop 上的模式策略，不是四套 Loop；
- 所有入口只调用 `AgentRuntime`；
- Prompt 统一由 Registry 组合；
- Context 只有一个生产构建入口；
- Goal state 与 GoalDriver 解耦；
- 所有真实执行继续经过 Dispatcher；
- Hooks / Skill / MCP / A2A 只能收紧、观察或扩充上下文，不能绕过安全主干。

---

## 6. 推荐的完整任务模型

用户看见的是 `Task`，内部才映射到 Turn / ModelCall / ToolCall / Approval / Execution。

推荐 UI 模型：

```text
Task
├── Objective
├── Plan
├── Progress / Todo
├── Runs
├── Approvals
├── Tool Activity
├── Changes
├── Verification
└── Artifacts
```

一个完整 Act / Goal 最好不是以“assistant 停止输出”为结束，而是形成：

```text
Plan（可选）
→ Execute
→ Progress
→ Verify
→ Completion Report
→ Artifacts
```

---

## 7. P0：下一阶段最值得做的事项

### P0-A：Harness 主干收敛

1. `ChatModelLoop` → `AgentLoop`；
2. 真正落地统一 `AgentRuntime`；
3. `ContextBuilder` 与生产聊天 Context 路径统一；
4. Prompt Section Registry；
5. Plan Submit / Review / Execute；
6. Act Completion Contract；
7. Task Ledger / Todo；
8. GoalDriver；
9. 清理 Goal ADR 演进后过时的 KDoc / 注释语义。

### P0-B：现有能力产品化

1. Tasks Dashboard；
2. Plan Review UI；
3. Progress / Todo UI；
4. Artifact Center；
5. Git status / diff / changed files；
6. PDF / DOCX；
7. Capability Center；
8. 强化 Share-to-Helix。

这两个 P0 应并行推进。只做技术重构会继续导致用户感知弱；只做 UI 功能则会扩大现有 orchestration 债务。

---

## 8. P1：形成开发者和移动端闭环

开发者方向：

```text
Git Diff
Test / Build
Structured verification
Project Instructions
Developer Runtime onboarding
```

移动方向：

```text
Scheduled Goal
Foreground Task
Notification Resume
Home-screen Widget
Voice input
Official Skills
```

建议支持 `AGENTS.md / CLAUDE.md / HELIX.md` 等项目级指令，并建立清晰的优先级和可信度模型。

---

## 9. P2：扩展能力

当 P0/P1 的任务完成率和复用率得到验证后，再增加：

```text
Default Assistant
Learned Mobile Skills
Tasker interoperability
Shizuku / Wireless ADB
Read-only Subagent
Code Mode
Web Access
A2A 产品化入口
```

其中 Subagent 第一版只建议 read-only、depth=1、共享父任务预算，不建议直接做 swarm。

---

## 10. P3：暂缓事项

以下功能很容易把 Helix 带向“功能大全”，但与当前核心价值关联较弱：

```text
Visual Workflow DAG Editor
大型开放插件市场
Multi-agent swarm
Remote Worker / Cloud fleet
完整桌面 Client
Local LLM 大而全
Full IDE
角色群聊
Avatar
复杂本地语音模型
```

应由真实任务留存、模板复用率和用户访谈来决定是否进入路线。

---

## 11. 推荐的四阶段产品路线

### Phase 1 — Harness 2.0 + Task Productization

目标：让已有 Agent 能力成为稳定、统一、可理解的产品。

```text
AgentRuntime
AgentLoop
PromptRegistry
ContextEngine
Plan
Completion
Task Ledger
Tasks UI
Artifacts
Recovery UI
```

### Phase 2 — Developer Task Loop

目标：让开发者第一次真正因为“没有电脑也能完成一个开发小任务”选择 Helix。

```text
Git status
Diff
Test / Build
Project Instructions
Developer Runtime onboarding
```

### Phase 3 — Mobile Task Loop

目标：发挥 Android 产品而不是桌面 CLI 移植的优势。

```text
Share
Widget
Voice
Foreground Goal
Schedule
Notification Resume
```

### Phase 4 — Automation & Ecosystem

目标：让一次成功任务变成可复用能力。

```text
Saved Task
Learned Skill
Tasker
Advanced device capabilities
Official Skill Gallery
Connector presets
Read-only Subagent
```

---

## 12. 推荐的首批核心 Demo

### Demo 1 — Coding

> “检查这个项目为什么测试失败并修复。”

```text
Repo → Plan → Search → Edit → Diff → Test → Artifact
```

### Demo 2 — Research

> “调研这 5 个开源 Agent，并生成 Markdown 报告。”

```text
Browser → Extract → Compare → Sources → Report → Artifact
```

### Demo 3 — Files

> “整理 Downloads，但先让我看计划。”

```text
Inspect → Plan → Approval → Move → Verify → Summary
```

### Demo 4 — Android

> “根据这条通知帮我创建日历事件。”

优先走 Native Intent / Calendar API，不用 UI click。

### Demo 5 — Recovery

执行中强制杀 App，再打开后展示：

```text
3 actions completed
1 operation unknown
2 steps remaining
[Review] [Continue Safely] [Stop]
```

这个 Demo 最能把 Helix 已有的工程优势变成用户可理解的差异化。

---

## 13. 推荐产品指标

不要只统计 Tool 数量、Provider 数量、测试用例数量。产品层更重要的是：

| 指标 | 说明 |
|---|---|
| Time to First Value | 安装到第一个真实任务成功需要多久 |
| First Task Success | 新用户首次任务成功率 |
| Task Completion Rate | Act / Goal 正常完成比例 |
| Human Intervention | 每任务需要几次人工接管 |
| Approval Burden | 每任务审批次数与拒绝率 |
| Recovery Rate | 中断后可成功继续的比例 |
| Duplicate Side Effect | 恢复 / retry 是否造成重复副作用 |
| Artifact Success | 是否交付正确、可打开的结果 |
| 7d / 30d Task Retention | 是否再次回来执行真实任务 |
| Capability Activation | 哪些 Advanced 能力真正被使用 |

---

## 14. 最终综合判断

Helix 当前不是“架构落后的后来者”。真正的问题是：

> **已经具备严肃 Agent Harness 的很多底层条件，但还没有完全完成从 Chat-centric application 到 Task-centric agent product 的抽象跃迁。**

如果接下来先收敛 Harness 主干，再把 Goal、Plan、Recovery、Workspace、Browser、PRoot 等现有能力包装成 Tasks / Progress / Artifact / Capability / Diff 等用户概念，Helix 会比单纯增加更多 Tool 更容易形成自己的位置。

最推荐的战略顺序仍然是：

```text
Harness 2.0
→ Task Productization
→ Developer Loop
→ Mobile Loop
→ Automation
→ Ecosystem
```

---

# Part II — Agent Harness 与实现架构深度评估

## Helix Agent Harness 深度架构评估与产品优化方案

> 研究对象：`dollarser/helix-agent`\
> 对比对象：Operit / Operit2 / PalmClaw / RikkaHub / AndCode / ClawMobile / DSHA，以及 Codex、Claude Code、DeepSeek Harness、pi coding agent、WorkBuddy 等桌面或通用 Agent Harness。\
> 结论基于 2026-09-11 可见的公开仓库、官方文档与 Helix 当前主分支实现。本文重点关注 **Agent Harness 本身**，而不是单纯比较功能数量。

---

### 1. 执行摘要

Helix 当前最明显的特点不是“功能最多”，而是 **Android 本机 Agent 的执行边界、安全模型、持久状态和故障语义设计得相当认真**。

从已检查的实现看，Helix 已经具备不少桌面 Agent 才会认真处理的基础设施：

- Turn / ModelCall / ToolCall / Approval / Execution 分开建模；
- ToolCall 统一经过 Schema → Capability → Policy → Approval → Execute → Verify → Audit；
- 模型不是权限主体；
- QuickJS、PRoot、CLI Runtime 采用不同执行域；
- 工具结果遵循“先持久化，再回填模型”的原则；
- Tool batch 有确定性回填顺序；
- 取消、超时、重试、副作用不确定状态都有单独语义；
- Goal 有独立状态机、预算、run/wake、进程死亡恢复；
- 高敏数据出网与 Tool 权限是不同维度；
- Provider / MCP / A2A / Skill / ExecutionTarget 没有混为一个概念。

这些方面，Helix **明显优于大量以“LLM + Android 工具列表 + while loop”为核心的移动 Agent 实现**。

但目前最大的架构风险也很清楚：

> **Helix 的“理论 Agent Runtime”已经设计得很完整，而生产执行主干仍然带有从聊天功能逐步演化出来的痕迹。**

最典型的证据是：

- 实际模型多步循环仍由 `ChatModelLoop` 承担；
- 请求构建由 `ChatRequestAssembler` 承担；
- 架构文档中的统一 `AgentRuntime` 端口尚未真正成为生产主干；
- `ContextBuilder` 有一套很完整的来源、可信度和裁剪抽象，但当前聊天请求组装路径实际上又存在另一套 persisted-history + compaction 逻辑；
- Chat / Plan / Act / Goal 在产品上是四种模式，但执行层还没有完全收敛成“一个 Harness + 多个 Collaboration/Policy Overlay”。

这在功能还不多时问题不大，但一旦继续增加：

- Goal 自动续跑；
- 定时任务；
- Hooks；
- 子 Agent；
- Workflow；
- 多种 UI 入口；
- Android 默认助理；
- 外部 Channel；
- 多模型协作；

就会很容易出现“每种入口再写一套状态协调代码”。

因此，Helix 下一阶段最重要的不是继续增加工具数量，而是：

**先完成 Agent Harness 主干收敛，再在这个主干上补高价值移动产品能力。**

本文给出的最高优先级建议是：

1. 将 `ChatModelLoop` 收敛为真正的 `AgentLoop / AgentRuntime`；
2. 把 Chat / Plan / Act / Goal 改成同一个 Loop 上的独立模式策略；
3. 把 Prompt 做成可组合、可排序、可作用域覆盖的 Prompt Section Registry；
4. 把 Goal 的“状态”与“自动推进驱动器”进一步解耦；
5. 将 Plan 从“模式约束”升级为“可审阅、可修改、可执行的持久 Plan Artifact”完整闭环；
6. 给 Act 增加明确的“完成协议”和任务摘要，而不是仅依赖“模型停止调用工具”；
7. 增加 Hook/Event 扩展面，但保持 Tool Dispatcher 的单一安全入口不可绕过；
8. 加入 Todo/Task Ledger，解决长任务中模型遗忘计划的问题；
9. 增加移动端独有的 Resume / Handoff / Foreground execution / Notification control；
10. 产品定位不要和 Operit 拼“功能数量”，而应强调 **手机本机、Provider-neutral、可恢复、可检查、真正执行任务**。

---

## 2. Helix 当前 Harness 架构判断

### 2.1 当前执行主干

从代码来看，Helix 当前生产执行链大致可以抽象为：

```text
User
 │
 ▼
ChatService / Chat UI
 │
 ▼
ChatRequestAssembler
 │
 ▼
ModelProvider
 │
 ▼
ChatModelLoop
 │
 ├── stream model
 │
 ├── collect tool calls
 │
 ▼
ToolScheduler
 │
 ▼
ToolDispatcher
 │
 ├── schema
 │
 ├── capability
 │
 ├── policy
 │
 ├── approval
 │
 ├── deadline / cancel
 │
 ├── execute
 │
 ├── output verify
 │
 └── audit
 │
 ▼
persist ToolResult
 │
 ▼
rebuild ModelRequest
 │
 └─────────────── loop
```

这个结构本身是合理的。

尤其值得保留的是：

> **model-visible ⇔ persisted**

即工具结果先成为持久事实，再进入下一轮模型输入。

这一点和 DeepSeek Harness 的 event-sourced session spine 思路高度一致：Agent Loop 不应该维护一个“只有内存知道”的隐藏会话状态，下一步模型请求应该从持久事实重新派生。[1]

这对于 Android 尤其重要，因为：

- Activity 随时重建；
- App 可能进后台；
- 进程可能被杀；
- Binder / Runtime 可能断开；
- 网络可能切换；
- 用户可能在审批过程中离开 App。

所以 **Helix 不应为了追求桌面 Agent 的流畅感而牺牲这个原则**。

---

### 2.2 当前主干最大的结构性问题

`ChatModelLoop` 实际已经不再只是“Chat”。

它负责：

- 模型步骤；
- Context compaction；
- Tool round；
- Goal time budget；
- cancellation；
- model call accounting；
- ToolCall → ToolResult → next model request；
- retry / backfill。

这已经是标准的 Agent Loop。

因此建议：

```text
ChatModelLoop
     ↓
AgentLoop
```

并建立真正的：

```kotlin
interface AgentRuntime {
    suspend fun submit(command: SubmitTurnCommand): TurnId
    suspend fun resume(turnId: TurnId): ResumeResult
    suspend fun cancel(turnId: TurnId): CancelResult
    fun observe(turnId: TurnId): Flow<TurnSnapshot>
}
```

然后：

```text
Chat UI
Plan UI
Goal UI
Share Intent
Notification Action
Widget
Voice
External Channel
```

全部只调用 `AgentRuntime`。

不要让任何入口直接驱动 Provider 或 Tool pipeline。

#### 推荐结构

```text
                  ┌──────── Chat UI
                  ├──────── Share
                  ├──────── Voice
                  ├──────── Goal UI
                  ├──────── Widget
                  └──────── Channels
                           │
                           ▼
                    AgentRuntime
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
        TurnCoordinator            GoalDriver
              │                         │
              └────────────┬────────────┘
                           ▼
                       AgentLoop
                           │
              ┌────────────┼──────────────┐
              ▼            ▼              ▼
        PromptAssembly   ModelRouter   ToolRuntime
                                         │
                                         ▼
                                    Dispatcher
```

这会是 Helix 下一阶段最值得做的架构收敛。

---

## 3. Chat / Plan / Act / Goal 模式设计评估

### 3.1 总体结论

Helix 把模式定义为：

| 模式 | 当前语义 |
|---|---|
| Chat | 默认无工具；显式开启后只允许 READ_ONLY + L0 |
| Plan | READ_ONLY，动态风险 ≤ L1 |
| Act | 完整 Tool Policy |
| Goal | Act + 持久目标 + budget + checkpoint |

方向是合理的。

但建议进一步明确：

> **Mode 不应该等于 Agent Loop。**

四种模式都应该运行在同一个 Agent Loop 上，只改变：

- System Prompt；
- Tool exposure；
- Policy overlay；
- completion protocol；
- persistence policy；
- UI。

这与当前领先 Harness 趋势一致。

DeepSeek Harness 的 plan mode甚至明确设计成一个独立 collaboration-state 插件，Agent Loop 本身并不知道 Plan Mode。[2]

---

## 4. Plan 功能深度评估

### 4.1 Helix 当前优点

Helix Plan 最大的优点是：

#### 真正强制只读

`ModePolicy` 和 `PolicyEngine` 都会限制 Plan：

```text
operationClass == READ_ONLY
dynamicRisk <= L1
```

因此即使模型因为 Prompt Injection 试图写文件，也会被 Harness 拒绝。

这是非常正确的。

相比之下，DeepSeek Harness 明确说明：

> Plan Mode 本身只是 soft guidance；所有 Tool 仍然可注册，真正限制来自独立 sandbox / approval policy。[2]

这种设计在桌面 Harness 中有扩展性优势，但移动产品不一定需要完全照搬。

Claude Code 的 `plan` permission mode则更接近 Helix：模型可以分析，但禁止修改文件和执行危险命令。[3]

因此：

**Helix 的 Plan 硬约束不需要改掉。**

---

### 4.2 当前不足：Plan 还不够“产品化”

Helix 架构文档已经设计了：

```kotlin
PlanArtifact(
    objective,
    assumptions,
    steps,
    acceptanceCriteria,
    risks,
    version
)
```

这是对的。

但建议把它真正升级为 Harness 一等对象。

推荐完整生命周期：

```text
DRAFT
  ↓
PLANNING
  ↓
REVIEW_REQUIRED
  ├── revise → PLANNING
  ├── reject → CANCELLED
  └── approve
        ↓
     APPROVED
        ↓
   ACT / GOAL
```

不要只把 Plan 看作一次 assistant 文本。

---

### 4.3 应增加 `plan.submit`

建议像 DeepSeek Harness 的 `exit_plan_mode` 一样，为模型提供结构化结束工具：

```json
{
  "name": "plan.submit",
  "parameters": {
    "objective": "...",
    "assumptions": [],
    "steps": [],
    "acceptanceCriteria": [],
    "risks": []
  }
}
```

Harness：

1. 校验 schema；
2. 持久化 PlanArtifact；
3. 进入 `REVIEW_REQUIRED`；
4. UI 展示；
5. 用户选择：
   - 执行；
   - 修改；
   - 继续规划；
   - 取消。

用户批准后才产生：

```text
PlanExecutionBinding
planId
planVersion
planHash
```

再进入 Act / Goal。

---

### 4.4 对比 Operit

Operit 已经有 Plan Mode 示例插件：

- 写计划；
- 获取计划；
- 完成计划；
- Plan 内容持久化为文件；
- Prompt 显式要求实现前先读 Plan；
- 只有全部计划完成后才能调用 complete。[4]

这个方案简单直接，产品感很强。

Helix 的 Plan 内部数据模型更严谨，但 UI 和执行闭环应向这种体验靠拢。

---

## 5. Act 模式评估

### 5.1 当前问题

目前 Act 在核心语义上主要等价于：

```text
ModePolicy = allow
PolicyEngine = normal
```

而“任务完成”实际上是：

```text
模型停止产生 ToolCall
→ 当前 Turn 完成
```

对于简单 Agent 足够，但桌面级 coding agent 已经证明：

> “没有工具调用”不等于“任务真的完成”。

典型情况：

- 改代码后没跑测试；
- 文件写了但没验证；
- 网页按钮点了但状态没确认；
- 下载任务启动但文件不存在；
- Android UI 操作发出但页面没变化。

---

### 5.2 建议加入 Completion Contract

Act 应引入轻量 completion protocol。

不是 Goal 那么复杂，但可以要求：

```json
{
  "status": "complete | partial | blocked",
  "summary": "...",
  "artifacts": [],
  "verification": [],
  "remaining": []
}
```

可以实现为：

```text
turn.report
```

或者作为 model final structured output。

建议规则：

```text
有副作用的 Act
    ↓
如果最后一次状态没有 completion report
    ↓
UI 显示 “模型已停止，但任务未确认完成”
```

而不是直接绿色 Done。

---

## 6. Goal 功能深度评估

### 6.1 Helix 的 Goal 是目前最有潜力形成差异化的部分

当前 Goal 已包含：

- objective；
- criteria；
- DRAFT / READY / RUNNING / PAUSED / INPUT_REQUIRED / BLOCKED / COMPLETED / FAILED / CANCELLED；
- model call budget；
- tool call budget；
- token budget；
- runtime budget；
- wake runtime budget；
- retry budget；
- wake reason；
- checkpoint；
- process-death parking；
- model semantic completion；
- Harness execution-state validation。

这已经不是普通“循环 prompt”。

从架构严谨程度看，它甚至比多数移动 Agent 的 Cron / Always-on 更成熟。

---

### 6.2 和 DeepSeek Harness Goal 对比

DeepSeek Harness 当前的 Goal 设计和 Helix 非常接近：

- Goal 是 durable state；
- Goal 不是 scheduler；
- 同一个 session 继续推进；
- goal round 有 cap；
- activation 不持久化；
- resume/fork 后需要新的 human-authorized activation。[5]

这印证了 Helix 的方向是正确的。

---

### 6.3 但 Helix 缺一层：Goal Driver

现在 Goal domain 状态机很强，但“如何持续推进”相对保守：

```text
每次新 run 都要求用户显式 Continue
```

这非常安全，但会限制产品价值。

建议不要修改 Goal 状态机，而是增加独立：

```text
GoalDriver
```

```kotlin
interface GoalDriver {
    suspend fun admit(goalId: GoalId, source: GoalWakeSource): GoalRunAdmission
}
```

支持几种 Driver：

```text
USER
NOTIFICATION
FOREGROUND_CONTINUATION
SCHEDULED_CHECKPOINT
CHANNEL_EVENT
```

Goal 本身只负责：

```text
Can this run start?
```

Driver 负责：

```text
Should we attempt to start one?
```

这样未来加 Cron / Channel / A2A / push 时，不会污染 Goal reducer。

---

### 6.4 不建议直接做“永久后台自主 Agent”

Android 上真正长期自治受：

- Doze；
- Foreground Service；
- OEM 后台策略；
- battery optimization；
- 网络；
- App standby；
- 通知权限；

影响很大。

PalmClaw 的 Always-on / Cron 是一个很吸引人的功能，但 Helix 更适合做：

```text
Durable Goal
+
Foreground Execution
+
Checkpoint Notification
+
Optional Explicit Automation Grant
```

而不是宣传：

> 24/7 autonomous agent

---

### 6.5 Goal completion 机制评价

ADR-0040 将完成判断改成：

```text
模型调用 goal.report
status = complete / in_progress / blocked
```

Harness 不再强制把每条 criterion 绑定到 verifier。

这个改变是正确的。

因为通用目标无法全部转换成确定性规则。

但建议下一步做 **Hybrid Completion**：

```text
semantic completion = model
deterministic verification = tools/hooks when available
```

例如：

```text
Goal: 修复测试

model:
  complete

hook:
  ./gradlew test failed

最终:
  BLOCKED / incomplete
```

因此推荐引入：

```text
CompletionHook
```

而不是重新引入旧的全局 verifier。

---

### 6.6 一个需要立即修的代码一致性问题

当前 `GoalReducer` 的注释仍写着类似：

> CompleteRequested only when every criterion carries verifier evidence

但 ADR-0040 已经废弃了强制 evidence binding，实际 `onCompleteRequested()` 也已经不检查 evidence。

这是一个典型的：

```text
architecture evolution
→ code changed
→ KDoc stale
```

问题。

建议马上清理。

因为 Harness 项目中最危险的不是普通注释错误，而是：

> **安全/完成语义注释与真正代码行为不一致。**

---

## 7. Tool Call 架构评估

### 7.1 Helix 当前 Tool Dispatcher 是强项

当前 Dispatcher 流程：

```text
resolve
→ input schema validation
→ capability
→ policy
→ approval
→ cancellation/deadline
→ execution
→ output schema validation
→ bounded output
→ hash
→ audit
```

设计非常合理。

尤其值得保留：

#### 1. Approval Proof 在 execution start 才消费

这避免：

```text
用户批准
→ 任务排队
→ 用户取消
→ 批准仍被“浪费/复用”
```

#### 2. retry 只允许 confirmed side-effect-free

这是非常专业的设计。

大量 Agent 会对失败命令直接 retry。

但写文件、发请求、UI click 等失败后：

```text
失败 ≠ 没有产生副作用
```

Helix 明确区分这一点非常重要。

#### 3. same-turn denial fingerprint

用户拒绝同一个操作后，模型不能马上原样再请求一次审批。

这是很好的 UX / safety 机制。

---

## 8. 与 DeepSeek Harness Tool Runtime 对比

DeepSeek Harness 的 Tool Runtime 结构是：

```text
pre-execute
→ monotonic guards
→ execute wrapper
→ post-execute
→ finalizeContent
→ tools/result
```

并允许插件挂：

- permission；
- sandbox；
- hooks；
- timeout；
- retry；
- metrics；
- result transform。[6]

Helix 的 Dispatcher 安全性很强，但扩展性没有 DeepSeek Harness 那么高。

---

### 8.1 建议增加 Hook seam，但不要开放绕过 Dispatcher

推荐：

```text
ToolCall
 │
 ▼
PrePolicyHooks        ← only add restrictions / annotations
 │
 ▼
PolicyEngine
 │
 ▼
Approval
 │
 ▼
PreExecuteHooks       ← cannot widen authority
 │
 ▼
Executor
 │
 ▼
PostExecuteHooks
 │
 ▼
Verification
 │
 ▼
ResultObservers
```

原则：

```text
extension may restrict
extension may observe
extension may enrich
extension MUST NOT bypass
```

也就是：

> monotonic security

这会让未来：

- Skill；
- Plugin；
- Enterprise policy；
- Task template；
- custom automation；

更容易扩展。

---

## 9. Prompt 设计评估

### 9.1 Helix 目前最需要补的 Harness 能力之一

从目前代码可以看到：

- `ContextBuilder` 已区分 SYSTEM / MODE_POLICY / USER / TOOL / FILE / WEB / MCP / SKILL 等；
- 有 trusted / untrusted；
- SYSTEM / MODE_POLICY 不可裁剪；
- 外部内容不能成为授权来源。

这套 Context Domain 很好。

但当前生产请求构建主要由：

```text
ChatRequestAssembler
+
ChatHistoryBuilder
+
ContextCompaction
```

完成。

换言之，目前存在潜在的“双 Context 架构”：

```text
Architecture ContextBuilder
vs
Production Chat Request Builder
```

建议尽快合并。

---

## 10. 推荐 Prompt Assembly 架构

参考 DeepSeek Harness 的 system-prompt registry：

每个组件注册：

```kotlin
PromptSection(
    name,
    order,
    scope,
    provider
)
```

例如：

```text
-1000 harness.identity
-900  safety.invariants
-800  runtime.capabilities
-700  workspace.policy
0     persona
50    mode.plan
60    goal.context
100   tool.guidance
120   skill.instructions
200   project.instructions
```

最终每一步动态 assemble。

---

### 10.1 为什么比“一个大字符串”更好

未来 Helix 会同时出现：

- Chat mode；
- Plan mode；
- Goal；
- Skill；
- MCP；
- Project instruction；
- Android Capability；
- Runtime；
- Tool usage guidance；
- Provider-specific compatibility；
- Custom user instructions。

如果都拼字符串：

```text
prompt += ...
prompt += ...
prompt += ...
```

最终一定会出现：

- 顺序依赖；
- 重复；
- 冲突；
- 模式切换残留；
- 缓存失效；
- Prompt Injection 边界模糊。

DeepSeek Harness 当前已经把 prompt 做成：

```text
ordered section registry
+
scope override
+
dynamic runtime context
```

这是很值得 Helix 借鉴的设计。[7]

---

## 11. Context 与 Prompt Injection

Helix 已经做对了一件非常重要的事情：

```text
FILE
WEB
MCP
A2A
SKILL
NOTIFICATION
ACCESSIBILITY
```

都默认标记：

```text
UNTRUSTED
```

应继续坚持：

> untrusted content 只能影响模型推理，不能直接修改 Policy。

但建议进一步引入：

```text
ContextEnvelope
```

```json
{
  "source": "web",
  "origin": "...",
  "trust": "untrusted",
  "contentHash": "...",
  "content": "..."
}
```

并在 model prompt 中明确包围。

---

## 12. Context Compaction 改进

Helix 当前 ContextBuilder 第一版主要按：

```text
retained
+
recency
```

裁剪。

这是稳定但比较初级。

桌面级 coding agent 长任务最大的竞争力之一其实是：

> **Context engineering**

建议逐步升级为：

```text
Tier 0: system / mode / active goal
Tier 1: current user request
Tier 2: unfinished tool state
Tier 3: current plan / todo
Tier 4: relevant workspace context
Tier 5: recent conversation
Tier 6: compressed historical summary
```

不是单纯 newest-first。

---

## 13. 建议增加 Todo / Task Ledger

这是当前 Helix 非常值得补的一项能力。

DeepSeek Harness 有 `todo_write`。

pi agent 示例里也存在 plan mode + step tracking。

Coding Agent 在长任务里需要一个：

```text
Working Memory Ledger
```

建议：

```kotlin
TaskLedger {
   items: [
      TODO,
      IN_PROGRESS,
      DONE,
      BLOCKED
   ]
}
```

注意：

它不是 Goal。

区别：

```text
Goal = 用户的长期目标
Plan = 解决方案
TaskLedger = Agent 当前执行进度
```

示例：

```text
Goal
  修复项目中的登录问题

Plan
  1. 找认证入口
  2. 复现
  3. 修改
  4. 测试

Task Ledger
  ✓ 找到 AuthRepository
  ✓ 复现 refresh race
  → 修改 mutex
  ○ 跑 instrumentation test
```

这会显著提高用户对长任务的可理解性。

---

## 14. Approval 设计与桌面 Agent 对比

### 14.1 Helix 当前审批设计偏“安全内核级”

相比 Claude Code / Codex，Helix 的审批 Binding 更严格：

- tool；
- args；
- scope；
- target；
- egress；
- UI token；
- hash；
- dynamic risk。

这是优势。

但移动端用户无法忍受频繁弹卡。

---

### 14.2 Codex 值得借鉴的方向

Codex 将：

```text
Sandbox
```

和：

```text
Approval
```

拆成两个概念。

沙箱定义：

```text
agent physically cannot exceed
```

Approval 定义：

```text
agent may request escalation
```

并允许：

```text
approve once
approve this type for session
```

OpenAI 还公开介绍了 Auto-review：低风险 escalation 可由独立 reviewer 自动判断，高风险继续要求人工。[8]

---

### 14.3 Claude Code 值得借鉴的方向

Claude Code 有：

```text
allow rules
deny rules
permission mode
project policy
user policy
managed policy
```

并允许规则类似：

```text
Bash(git diff:*)
Read
Edit
```

这对高级用户体验很好。[3]

---

### 14.4 Helix 推荐的最终模型

保持底层 Proof 不变，但 UI 增加：

```text
Allow once
Allow for this task
Allow in this workspace
Always ask
Deny
```

其中长期授权必须映射成有限规则：

```text
tool
+
scope
+
risk ceiling
+
target
+
duration
```

而不是：

```text
Allow all
```

Helix 现有 `Trusted Workspace` 和 bounded rule 正好可以发展成这一体验。

---

## 15. 自动审批建议

可以参考 Codex Auto-review，但不要直接让主 Agent 自己批准自己。

推荐：

```text
Agent proposes ToolCall
        │
        ▼
Deterministic Policy
        │
        ├── allow
        ├── deny
        └── reviewable
                │
                ▼
         Optional Reviewer
                │
        ┌───────┴───────┐
        ▼               ▼
   safe-to-auto      human
```

Reviewer 只能在：

```text
policy-defined ceiling
```

内批准。

例如：

```text
L1
read-only
workspace scoped
no sensitive egress
```

这是 P2 能力，不建议现在优先实现。

---

## 16. 工具暴露策略需要改进

目前 Helix 会向模型暴露当前 mode 可用的 tools。

随着：

- MCP；
- Android tools；
- browser；
- files；
- root；
- A2A；

增加后，Tool 数量很快会变得过大。

建议加入：

```text
Tool Capability Router
```

先给模型一小组：

```text
files
browser
android
code
external
```

或根据 task 自动选择工具子集。

避免：

```text
100+ JSON Schema
```

全部塞进请求。

这会降低：

- context；
- tool selection accuracy；
- provider compatibility；
- token cost。

---

## 17. Code Mode 值得研究

DeepSeek Harness 支持：

```text
native function calling
code mode
both
```

Code Mode 的思路是：

模型只看到一个：

```text
run_code
```

在受控脚本环境中调用一组 SDK tool。

优点：

- 多工具组合 token 更低；
- 循环和转换可以在本地完成；
- 减少模型-API round trip。

Helix 已有 QuickJS，因此非常适合尝试：

```text
Agent Code Mode
```

例如模型生成：

```javascript
const files = await helix.files.list(...)
const txt = await helix.files.read(...)
return ...
```

但必须保证：

```text
每个 SDK tool
仍进入 Dispatcher
```

QuickJS 不能直接访问 Android API。

这是 Helix 非常有潜力做出差异化的方向。

---

## 18. Background Tool / Subagent / Workflow

### 18.1 当前判断

Helix 已有：

```text
bounded background tool tasks
```

但没有真正：

```text
subagent
agent graph
workflow
```

这是合理的阶段选择。

---

### 18.2 不建议现在做任意 Agent Graph

移动端资源约束明显：

- RAM；
- battery；
- API 调用成本；
- foreground restrictions。

建议先支持：

```text
read-only child task
```

只用于：

- research；
- repo exploration；
- independent summarization。

并限制：

```text
depth = 1
max children
shared parent budget
no direct mutation
```

这与你现有 ADR 的方向一致。

---

## 19. Recovery / Robustness 对比

Helix 的恢复机制是目前非常重要的优势。

相比很多移动 Agent：

```text
app 被杀
→ 当前任务消失
```

Helix 已经设计：

- Turn persistence；
- Tool outcome；
- Goal persistence；
- process-death parking；
- unknown side effect；
- runtime reconciliation。

建议将其提升为真正用户可见功能：

```text
Task Interrupted

✓ 3 actions completed
✓ 2 files changed
? 1 operation status unknown

[Review]
[Continue safely]
[Stop]
```

不要只把这些能力留在内部审计表里。

---

## 20. 与主要移动竞品对比

### 20.1 Operit

#### 强项

Operit 当前在“能力密度”上明显领先：

- Android 自动化；
- browser；
- Ubuntu；
- code workspace；
- SSH；
- workflow；
- memory；
- role；
- local models；
- Skill；
- MCP；
- ToolPkg；
- marketplace；
- voice；
- floating window；
- default assistant。

因此 Helix 不适合追求：

> 功能数量超过 Operit

短期不现实，而且会破坏架构节奏。

#### Helix 可以赢的方向

```text
可靠执行
+
统一状态
+
执行隔离
+
恢复
+
Provider-neutral
+
更清晰的任务闭环
```

---

## 21. Operit2

Operit2 的战略方向是：

```text
个人 Device Space
```

多个 CoreNode：

```text
phone
desktop
cloud
```

共享上下文与任务接续。

这是一个更大的方向。

Helix 当前明确不做 Remote Worker / desktop pairing 是合理的。

但长期会受到 Operit2 的压力。

建议先预留：

```text
ExecutionTarget
```

但不要现在做跨设备系统。

---

## 22. PalmClaw

PalmClaw 当前强项：

- on-device loop；
- session；
- memory；
- skills；
- tools；
- MCP；
- channels；
- Cron；
- heartbeat；
- Always-on。

其产品故事非常简单：

> 手机上的本地 Agent。

Helix 在 Harness 安全和执行语义上更严格，但 PalmClaw 在：

```text
让用户感知到 Agent 一直在工作
```

方面更直接。

因此 Helix 应补：

- scheduled Goal；
- foreground run；
- notification resume；
- external channels。

---

## 23. AndCode

AndCode 是非常值得 Helix 产品层学习的竞品。

它没有重新发明所有 coding agent。

而是把：

```text
OpenCode
Claude Code
Antigravity
```

包装为 Android touch-first runtime。

它的优势是：

- repo；
- diff；
- terminal；
- approvals；
- schedules；
- session；
- local / remote handoff；
- assistant role；
- widget；
- voice。

Helix 开发者体验至少应补：

```text
Git status
Diff review
Patch apply
Test result
Artifact
```

否则即使底层 Runtime 更漂亮，用户仍然会认为 AndCode “更像真正能工作的 coding agent”。

---

## 24. RikkaHub

RikkaHub 的核心优势不是 Agent 深度，而是：

> 非常成熟的 Android LLM 客户端体验。

包括：

- Provider；
- Material You；
- multimodal；
- memory；
- MCP；
- web access；
- search；
- proot workspace。

这意味着 Helix 的基础聊天体验必须至少达到：

```text
成熟聊天 App
```

否则用户不会因为 Agent 能力而容忍：

- 模型配置难；
- 消息滚动问题；
- Markdown 不完善；
- 附件体验差；
- 历史难管理。

---

## 25. ClawMobile

ClawMobile 最值得关注的是：

```text
learn reusable mobile skills
```

即：

```text
一次成功 UI 操作
→ trace
→ generated skill
→ 下次复用
```

这是移动 Agent 很有价值的一条路线。

Helix 当前 Skill 更像：

```text
预定义知识/流程
```

未来可以增加：

```text
Task Replay Skill
```

但应该以：

```text
selector / semantic anchor / verification
```

为核心，而不是纯坐标 replay。

---

## 26. DSHA

DSHA 的优势非常实际：

> 把完整 DeepSeek Harness 搬到 Android 并解决安装问题。

它证明用户确实愿意为了：

```text
完整桌面 Harness
```

在手机上承受较大 APK 和 Linux Runtime。

因此 Helix 不需要担心：

> PRoot 太重，用户一定不会接受。

真正问题是：

```text
它是否给用户带来足够价值
```

Helix 当前“原生工具优先 + PRoot 按需”的架构实际上更符合移动设备。

---

## 27. 桌面 Agent 对 Helix 的真正启示

桌面 Agent 的领先点不只是：

```text
shell
```

而是 Harness 能力：

```text
Prompt composition
Task state
Tool policy
Context engineering
Hooks
Plan
Todo
Diff
Verification
Recovery
Subagents
Telemetry
```

因此移动 Agent 不应只学习桌面 Agent 的 shell。

---

## 28. Codex 对 Helix 的启示

重点学习：

#### Sandbox 与 Approval 分离

```text
physical boundary
vs
authorization
```

#### workspace-first

大多数开发操作在 workspace 内应该尽量无摩擦。

#### managed rules

高阶用户可以写规则。

#### telemetry

Agent 原生日志记录：

- prompt；
- approvals；
- tool；
- MCP；
- network policy。

Helix Audit 已经有很好的基础。

---

## 29. Claude Code 对 Helix 的启示

Claude Code 最值得借鉴的是：

```text
CLAUDE.md
```

即项目级 Agent Instructions。

建议 Helix 增加：

```text
HELIX.md
```

或兼容：

```text
AGENTS.md
CLAUDE.md
```

并提供：

```text
workspace instructions
user instructions
session instructions
```

优先级：

```text
system
managed
workspace
user
session
skill
```

必须明确。

---

## 30. DeepSeek Harness 对 Helix 的启示

它最值得 Helix 学习的不是某个功能，而是：

> **Agent Loop 极度薄，所有高级能力变成插件。**

核心 Loop 只做：

```text
model
→ tool
→ model
```

Plan、Goal、retry、compaction、sandbox、permission、subagent 都是扩展。

Helix 当前 Tool pipeline 已经具备这种潜力。

下一步应让：

```text
Agent Loop
```

也拥有类似的 extension seam。

---

## 31. pi agent 对 Helix 的启示

pi coding agent 的 extension examples 展示了很多实用小扩展：

- plan mode；
- tool enable / disable；
- handoff；
- subagent；
- SSH；
- presets；
- status line。

这说明：

> 很多高级 Agent 能力不应该成为核心 Loop 的 if/else。

Helix 可以采用：

```text
AgentExtension
```

机制。

---

## 32. WorkBuddy 对 Helix 的启示

WorkBuddy 的产品定位值得学习：

> 它没有宣传“更强模型”，而是强调理解你的真实工作环境。

核心是：

```text
memory
workflow
notes
browser
tasks
projects
```

Helix 面向 Android 可以对应成：

```text
files
browser
notifications
calendar
workspace
apps
```

因此定位可以从：

> Android AI Agent

升级为：

> **Android 上你的个人执行工作台。**

---

## 33. 推荐的 Helix Harness 2.0 架构

```text
┌──────────────── UI / Entry Points ────────────────┐
│ Chat │ Goal │ Share │ Voice │ Widget │ Channel   │
└───────────────────────┬───────────────────────────┘
                        │
                        ▼
                  AgentRuntime
                        │
               ┌────────┴────────┐
               ▼                 ▼
        TurnCoordinator       GoalDriver
               │
               ▼
                 AgentLoop
               │
      ┌────────┼──────────┬──────────────┐
      ▼        ▼          ▼              ▼
 Prompt     Context     Model          Hooks
Assembly    Engine      Router
      │        │          │
      └────────┴──────┬───┘
                      ▼
                  ToolRuntime
                      │
               Tool Dispatcher
                      │
    ┌─────────────────┼──────────────────┐
    ▼                 ▼                  ▼
 Android          QuickJS             PRoot
 Tools            Isolate             Runtime
```

---

## 34. 建议新增核心抽象

### AgentRuntime

统一所有入口。

### AgentLoop

只负责：

```text
step
tool
step
```

### PromptRegistry

负责所有 system/mode/skill/project prompt。

### ContextEngine

唯一上下文构建入口。

### GoalDriver

负责是否启动下一轮 Goal。

### CompletionReporter

统一 Act / Goal completion semantics。

### TaskLedger

模型执行工作记忆。

### AgentHooks

扩展生命周期。

---

## 35. 推荐事件模型

```text
agent/session-start
agent/turn-start
agent/pre-step
agent/request
agent/model-start
agent/model-end
tool/proposed
tool/pre-policy
tool/approval
tool/pre-execute
tool/post-execute
tool/result
agent/post-step
agent/turn-stopping
agent/turn-end
goal/run-start
goal/run-end
```

但：

> Hook 不应直接拥有执行权限。

---

## 36. 移动端必须额外解决的 Harness 问题

桌面 Agent 很少遇到：

```text
screen off
app background
process death
Doze
OEM kill
Activity recreation
permission revoked
network switched
battery thermal
```

因此 Helix 应把：

```text
Mobile Runtime State
```

作为 Prompt + Policy 的动态输入。

例如：

```text
Battery: 12%
Foreground: false
Network: metered
Accessibility: revoked
PRoot: stopped
```

模型才能做出合理决策。

---

## 37. UI 应直接暴露 Agent 状态，而不是聊天消息

建议每个任务顶部有：

```text
Goal
Status
Plan
Progress
Runtime
Permissions
Cost
Artifacts
```

并允许展开：

```text
Model calls
Tool calls
Approvals
Logs
```

移动端不能让用户通过翻几十条聊天消息才能知道任务做到哪里。

---

## 38. 开发者工作区优先补什么

推荐优先：

#### P0

- Git status；
- diff；
- staged / unstaged；
- Agent changed files；
- Run test；
- Test result；
- Artifact preview。

#### P1

- branch；
- commit；
- GitHub PR；
- remote repo。

#### P2

- full IDE。

不要优先做完整编辑器。

---

## 39. Android 自动化优先补什么

建议分三层：

```text
Native Intent / API
        ↓
Accessibility
        ↓
ADB / Shizuku / Root
```

模型优先选择最稳定工具。

例如：

```text
打开设置页
```

优先 Intent。

不要 UI click。

---

## 40. Skill 体系建议

Skill 建议区分：

```text
Instruction Skill
Tool Skill
Workflow Skill
Learned Mobile Skill
```

而不是都叫 Skill。

每种有：

```text
source
hash
capabilities
tools
trust
version
```

---

## 41. MCP / A2A 判断

Helix 当前 MCP / A2A 的边界设计是合理的。

尤其：

```text
A2A Agent
≠
ExecutionTarget
```

这一点非常重要。

远端 Agent 返回：

```text
proposal
```

本地动作仍重新进入 Dispatcher。

应坚持。

---

## 42. 当前架构优先级评分

| 维度 | 评分 | 判断 |
|---|---:|---|
| Tool Safety | 9/10 | 非常强 |
| Approval Binding | 9/10 | 强 |
| Execution Isolation | 8.5/10 | 移动端优秀 |
| Goal State | 8.5/10 | 很有潜力 |
| Recovery | 8.5/10 | 强 |
| Provider abstraction | 8/10 | 合理 |
| Context architecture | 7/10 | 设计强，生产路径需统一 |
| Prompt architecture | 6/10 | 需要 Registry 化 |
| Plan UX | 6/10 | 数据模型好，闭环不足 |
| Act completion | 6/10 | 缺明确完成协议 |
| Agent Loop abstraction | 6/10 | ChatModelLoop 需要升级 |
| Hooks/extensions | 5.5/10 | Tool 层强，Agent 层不足 |
| Task progress | 5/10 | 建议 Todo/Ledger |
| Coding workspace UX | 5.5/10 | 和 AndCode/Operit 有明显差距 |
| Mobile entry points | 5/10 | widget / assistant / channel / schedule 仍可补 |
| Product polish | 5-6/10 | 当前工程成熟度高于用户感知成熟度 |

---

## 43. 最优先改进清单

### P0 — Harness 收敛

#### H1
`ChatModelLoop` → `AgentLoop`

#### H2
真正实现统一 `AgentRuntime`

#### H3
ContextBuilder 与 ChatRequestAssembler 统一

#### H4
Prompt Section Registry

#### H5
Plan Submit / Review / Execute 闭环

#### H6
Act Completion Report

#### H7
Task Ledger / Todo

#### H8
GoalDriver 与 Goal state 解耦

---

## 44. P1 — 开发者价值闭环

- Git status；
- diff review；
- test command；
- structured test result；
- patch artifact；
- project instruction；
- workspace trust；
- one-tap “continue task”。

---

## 45. P1 — 移动 Agent 产品能力

- Foreground Goal；
- schedule；
- notification resume；
- default assistant；
- share-to-Helix；
- widget；
- voice shortcut；
- task dashboard。

---

## 46. P2 — Agent 平台能力

- Hooks；
- read-only subagent；
- Code Mode；
- learned mobile skill；
- Tasker integration；
- optional Shizuku；
- ADB；
- remote handoff。

---

## 47. 不建议近期做

不建议为了和 Operit 对齐而优先做：

- avatar；
- 多角色群聊；
- 大型插件商城；
- 本地大模型；
- 全功能 IDE；
- 任意 Workflow DAG；
- 多 Agent swarm；
- 云 worker；
- iOS；
- HarmonyOS。

这些都会显著分散 Helix 当前真正有机会形成优势的 Harness 质量。

---

## 48. 推荐产品定位

不建议：

> Android 上最强 AI Agent

太泛。

不建议：

> 最安全的 Android Agent

安全不是主要购买理由。

推荐：

> **Helix 是一个真正运行在 Android 手机上的 AI 执行工作台。连接你自己的模型，让 AI 在手机本地组合文件、网页、代码和 Android 能力完成任务，并且每一步都可检查、可恢复。**

英文：

> **Helix is a local-first AI execution workspace for Android — connect your own models and let agents work with files, web, code and device capabilities directly on your phone.**

---

## 49. 首批最适合宣传的 Demo

不要宣传：

```text
我们支持 80 个 tools
```

应该展示完整任务。

#### Demo 1

```text
“帮我检查这个 GitHub 项目为什么测试失败”
```

展示：

```text
repo
→ search
→ edit
→ diff
→ test
→ result
```

---

#### Demo 2

```text
“把下载目录里的这些文件整理一下”
```

展示：

```text
preview
→ plan
→ approval
→ execute
→ summary
```

---

#### Demo 3

```text
“调查三个产品并生成 Markdown 报告”
```

展示：

```text
browser
→ files
→ citations
→ artifact
```

---

#### Demo 4

```text
“这个任务明天继续提醒我”
```

展示：

```text
Goal
→ checkpoint
→ notification
→ resume
```

---

#### Demo 5

```text
“这个操作以后在该工作区不用每次问”
```

展示 Helix bounded trust。

---

## 50. 最重要的竞争壁垒

真正可能形成壁垒的不是：

```text
MCP
Skill
PRoot
Android tools
```

因为竞品都会做。

更有价值的是组合：

```text
Android-native
+
Provider-neutral
+
durable Agent state
+
safe local execution
+
deterministic tool settlement
+
recoverable side effects
+
good mobile UX
```

也就是：

> **A mobile-native agent harness rather than a mobile chat app with tools.**

---

## 51. 最终判断

Helix 当前并不是架构落后。

恰恰相反：

> **Helix 当前最大的风险，是底层设计已经接近“严肃 Agent Harness”，但产品和执行主干还没有完全完成从 Chat App 到 Agent Runtime 的最后一次抽象跃迁。**

现在继续加大量功能，可能会让：

```text
ChatService
ChatModelLoop
ChatRequestAssembler
```

承担越来越多职责。

如果现在先把：

```text
AgentRuntime
AgentLoop
PromptRegistry
ContextEngine
GoalDriver
Completion
TaskLedger
Hooks
```

这些主干收敛好，后续：

```text
schedule
channel
assistant
workflow
subagent
remote runtime
```

都会自然很多。

因此建议下一阶段路线不是：

> 再增加 30 个功能

而是：

```text
Harness 2.0
↓
Developer Task Loop
↓
Mobile Task UX
↓
Automation
↓
Ecosystem
```

这条路线最符合 Helix 当前已经建立的工程优势。

---

## 参考资料

[1] DeepSeek Harness, Core / Agent Loop architecture:\
https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/core.md

[2] DeepSeek Harness, Plan Mode:\
https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/plan.md

[3] Anthropic, Claude Code permissions / CLI:\
https://docs.anthropic.com/en/docs/claude-code/cli-usage

[4] Operit Plan Mode implementation:\
https://github.com/AAswordman/Operit/tree/main/examples/plan_mode

[5] DeepSeek Harness Goal concepts:\
https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/glossary.md

[6] DeepSeek Harness Tool Runtime:\
https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/tools/README.md

[7] DeepSeek Harness System Prompt Assembly:\
https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/system-prompt.md

[8] OpenAI, Running Codex safely at OpenAI, 2026-05-08:\
https://openai.com/index/running-codex-safely/

[9] Helix architecture:\
https://github.com/dollarser/helix-agent/blob/main/docs/architecture/overview.md

[10] Helix Agent modes / Provider / MCP / Skills:\
https://github.com/dollarser/helix-agent/blob/main/docs/architecture/provider-mcp-skills-modes.md

[11] Helix Goal ADR-0040:\
https://github.com/dollarser/helix-agent/blob/main/docs/adr/0040-model-judged-goal-completion.md

[12] Helix ToolDispatcher:\
https://github.com/dollarser/helix-agent/blob/main/tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDispatcher.kt

[13] Helix PolicyEngine:\
https://github.com/dollarser/helix-agent/blob/main/core/policy/src/main/kotlin/com/helix/core/policy/PolicyEngine.kt

[14] Helix ContextBuilder:\
https://github.com/dollarser/helix-agent/blob/main/core/agent/src/main/kotlin/com/helix/core/agent/ContextBuilder.kt

[15] Helix ChatModelLoop:\
https://github.com/dollarser/helix-agent/blob/main/app/src/main/kotlin/com/helix/app/chat/ChatModelLoop.kt

[16] Helix ChatRequestAssembler:\
https://github.com/dollarser/helix-agent/blob/main/app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt

[17] Operit:\
https://github.com/AAswordman/Operit

[18] Operit2:\
https://github.com/AAswordman/Operit2

[19] PalmClaw:\
https://github.com/ModalityDance/PalmClaw

[20] RikkaHub:\
https://github.com/rikkahub/rikkahub

[21] AndCode:\
https://github.com/yuga-hashimoto/and-code

[22] ClawMobile:\
https://github.com/ClawMobile/ClawMobile

[23] DSHA:\
https://github.com/DSH-APP/DSHA

[24] WorkBuddy docs:\
https://docs.work-buddy.ai/

[25] pi coding agent extension examples:\
https://github.com/badlogic/pi-mono/tree/main/packages/coding-agent/examples/extensions


---

# Part III — 产品功能横评、产品体验与功能路线

## Helix Agent 产品功能横评与优化路线

> 研究对象：`dollarser/helix-agent`\
> 直接竞品：Operit、Operit2、PalmClaw、RikkaHub、AndCode、ClawMobile、DSHA\
> 桌面 / 通用参照：Codex、Claude Code、DeepSeek Harness、pi coding agent、WorkBuddy\
> 核验时间：2026-09-11\
>
> 本文只讨论**用户可感知的产品能力与功能闭环**。Agent Harness 内部实现、Tool Dispatcher、Goal Reducer、Prompt Registry 等架构细节另见《Helix Agent Harness 深度架构评估与产品优化方案》。

---

### 1. 结论先行

Helix 当前已经跨过“聊天客户端 + 几个 Tool”的阶段，产品骨架更接近：

> **Android 本机 AI 执行工作台**

但从用户视角看，Helix 目前存在一个明显的不平衡：

> **底层能力和工程完整度已经较高，但能被用户一眼理解、第一次使用就获得价值的产品功能闭环仍弱于头部移动竞品。**

尤其与 Operit、AndCode、PalmClaw、ClawMobile 比较时，Helix 并不缺底层“能力”，更缺的是：把能力包装成明确任务场景；把多个能力串成端到端工作流；降低首次配置成本；提供移动端高频入口；让任务进度、失败、恢复、产物更加可见；形成“为什么不用桌面 Agent / 为什么不用 Operit”的清晰理由。

从产品竞争角度，Helix 不应以“功能最多”为目标。Operit 当前已经覆盖模型、文件、终端、浏览器、Android 自动化、工作流、Skill、MCP、角色、记忆、本地模型、语音、悬浮窗、默认助理和市场等大量功能。其官方 README 当前仍把自己定位成高度一体化的 Android Agent 平台。参考：[Operit README](https://github.com/AAswordman/Operit)。

更适合 Helix 的路线是：

> **用少量高价值功能形成比竞品更可靠、更清晰、更容易恢复的本机任务闭环。**

推荐产品支柱：

```text
1. 手机上的真实执行
2. 文件 / 网页 / 代码 / Android 能力组合
3. BYOK / Provider-neutral
4. Task / Plan / Goal 可持续推进
5. 结果可检查
6. 失败可恢复
7. Advanced 能力渐进开启
```

---

### 2. 竞品产品定位地图

| 产品 | 核心定位 | 用户最容易理解的使用理由 |
|---|---|---|
| **Helix** | Android 本机 AI 执行工作台 | 自己的模型直接操作手机文件、网页、代码和 Android 能力 |
| **Operit** | 功能极全的 Android Agent 平台 | 一个 App 覆盖聊天、开发、自动化、模型、工作流和插件生态 |
| **Operit2** | 跨设备个人 Agent Space | 手机、桌面、云端节点协同与任务接续 |
| **PalmClaw** | 原生 Android 常驻 Agent | 手机直接运行 Agent，支持 Skills、Channels、Cron、Always-on |
| **RikkaHub** | 高完成度 Android LLM 客户端 + Agent Workspace | 多模型聊天体验成熟，同时有 Workspace、MCP、Memory |
| **AndCode** | 手机上的 Coding Agent GUI | 不用电脑、不用终端，在 Android 上直接跑 OpenCode / Claude Code 等 |
| **ClawMobile** | Agent-first phone / 移动技能 Agent | 手机是 Agent runtime，重复操作可以沉淀为 reusable skills |
| **DSHA** | DeepSeek Harness Android 启动器 | 一键在 Android 上跑完整 DeepSeek Harness + Ubuntu |
| **Codex** | 云/桌面开发 Agent | 仓库任务、代码修改、测试、评审、自动化开发工作 |
| **Claude Code** | Terminal-first coding agent | 项目理解、计划、代码修改、工具权限、项目规则 |
| **DeepSeek Harness** | 可扩展 Agent Harness | Plan / Goal / Schedule / Todo / Hooks / Code Mode / Subagent 等完整 Harness |
| **pi** | 极简可扩展 coding harness | 核心很小，通过 Extension / Skill / Prompt 扩展 |
| **WorkBuddy** | AI 工作伙伴 | 将浏览器、工作内容、记忆和任务组织成持续工作上下文 |

---

### 3. 总体功能成熟度横评

说明：`●●●` 表示已形成较完整产品体验；`●●○` 表示功能存在但体验或覆盖仍有限；`●○○` 表示基础能力 / 部分实现；`—` 表示非重点或未发现成熟实现。Helix 按当前仓库状态评估，不把未来 Roadmap 当已实现能力。

| 功能领域 | Helix | Operit | PalmClaw | RikkaHub | AndCode | ClawMobile | DSHA |
|---|---:|---:|---:|---:|---:|---:|---:|
| 多模型 / BYOK | ●●● | ●●● | ●●● | ●●● | ●●○ | ●●○ | ●○○ |
| 基础聊天 UX | ●●○ | ●●● | ●●○ | ●●● | ●●○ | ●●○ | ●●○ |
| 多会话 / 历史 | ●●● | ●●● | ●●● | ●●● | ●●● | ●●○ | ●●○ |
| 图片输入 | ●●○ | ●●● | ●●○ | ●●● | ●●○ | ●●○ | ●●● |
| PDF / DOC / PPT | ●○○ | ●●● | ●○○ | ●●● | ●○○ | ●●○ | ●●● |
| 文件管理 | ●●● | ●●● | ●●○ | ●●○ | ●●● | ●●○ | ●●● |
| Workspace | ●●● | ●●● | ●●○ | ●●● | ●●● | ●●○ | ●●● |
| Shell / Linux | ●●○ | ●●● | ●○○ | ●●○ | ●●● | ●●● | ●●● |
| 代码执行 | ●●● | ●●● | ●○○ | ●●○ | ●●● | ●●○ | ●●● |
| Git UI | ●○○ | ●●● | — | ●○○ | ●●● | ●○○ | ●●○ |
| Diff Review | ●○○ | ●●● | — | ●○○ | ●●● | ●○○ | ●●○ |
| 内置浏览器 | ●●● | ●●● | ●○○ | ●●○ | ●○○ | ●○○ | ●●● |
| Browser Agent | ●●● | ●●● | ●○○ | ●○○ | — | ●○○ | ●●○ |
| Android 原生 Tool | ●●● | ●●● | ●●○ | ●○○ | ●○○ | ●●● | ●●○ |
| Accessibility 自动化 | ●●○ | ●●● | Roadmap | — | — | ●●● | ●●○ |
| ADB / Shizuku | — | ●●● | — | — | — | ●●● | ●●● |
| Root | ●●○ | ●●● | — | — | — | ●○○ | — |
| Plan | ●●○ | ●●○ | ●○○ | — | Agent-dependent | ●○○ | ●●● |
| Goal | ●●● | ●○○ | ●○○ | — | — | ●●○ | ●●● |
| Todo / Task progress | ●○○ | ●●○ | ●○○ | — | ●●○ | ●●○ | ●●● |
| Scheduled Task | — | ●●● | ●●● | — | ●●● | ●○○ | ●●● |
| Always-on / Background | ●○○ | ●●○ | ●●● | — | ●○○ | ●●○ | ●●○ |
| 通知恢复 / Resume | ●●○ | ●●○ | ●●○ | — | ●●○ | ●●○ | ●●○ |
| 长期记忆 | — / 非重点 | ●●● | ●●● | ●●● | Agent-dependent | ●○○ | ●●● |
| Skills | ●●● | ●●● | ●●● | ●●○ | Agent-dependent | ●●● | ●●● |
| MCP | ●●● | ●●● | ●●● | ●●● | ●●● | ●●○ | ●●● |
| A2A | ●●● | — | — | — | — | ●●○ | — |
| 插件市场 | ●○○ | ●●● | ClawHub | — | — | ●○○ | ●●○ |
| Workflow | — | ●●● | — | — | — | ●●○ | ●●● |
| Subagent | — | ●●○ | — | — | Agent-native | ●○○ | ●●● |
| 语音输入 | ●○○ | ●●● | ●○○ | ●●○ | ●●● | ●○○ | ●○○ |
| TTS | ●○○ | ●●● | ●○○ | ●●○ | ●●● | ●○○ | ●○○ |
| Wake word | — | ●●○ | — | — | ●●● | — | — |
| 桌面 Widget | — | ●●● | — | — | ●●● | — | — |
| 默认系统助理 | — | ●●● | — | — | ●●● | — | — |
| 悬浮窗 / Bubble | — | ●●● | — | — | — | — | — |
| Share-to-Agent | ●●○ | ●●● | ●●○ | ●●○ | ●○○ | ●●● | ●○○ |
| Web Access | — | ●●● | — | ●●● | — | — | ●●● |
| 跨设备执行 | — | Operit2 | — | Web only | ●●● remote | — | LAN Web |
| 任务审计 | ●●● | ●●○ | ●●○ | ●○○ | ●●○ | ●●○ | ●●○ |
| 副作用恢复 | ●●● | ●●○ | ●●○ | ●○○ | ●●○ | ●●○ | ●●● |
| 能力 / 权限中心 | ●●● | ●●● | ●●○ | ●○○ | ●●○ | ●●● | ●●○ |
| 首次安装低门槛 | ●●○ | ●●○ | ●●○ | ●●● | ●●● | ●●○ | ●●● |

> 注意：这是产品成熟度判断，不是实验室基准测试。不同产品的“有该功能”并不代表能力范围、安全边界、稳定性和任务成功率相同。

---

### 4. 聊天、模型与基础 AI 客户端体验

#### Helix 已有优势

Helix 已支持 OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、兼容 API、自建模型服务、Provider 能力探测、模型选择、推理配置、多会话、上下文压缩以及文本/图片附件。这一层已经足够支撑“BYOK Android Agent”的核心定位。

#### 相比 RikkaHub / Operit 的差距

RikkaHub 和 Operit 在“聊天客户端成熟度”上更强。RikkaHub公开支持 Material You、PDF / DOCX、Message Branching、Prompt Variables、Memory、Search、Translation、Provider QR 导入导出以及 Web Access；Operit进一步提供图片、音频、视频、文档、多种本地/云模型、消息分支、并行对话和多模型分工。参考：[RikkaHub](https://github.com/rikkahub/rikkahub)、[Operit](https://github.com/AAswordman/Operit)。

#### Helix 应补

P0：PDF、DOCX、Markdown/HTML；P1：message branch、conversation export、会话内搜索、更好的附件预览；P2：音频/视频理解。

短期不建议做角色卡、多角色群聊和虚拟形象，因为这不是 Helix 第一目标用户的核心需求。

---

### 5. 文件管理与 Workspace

Helix 已有 Workspace、SAF、All-files advanced capability、read/write/edit、copy/move/trash、archive/extract、文件管理 UI、attachment import 和 interruption reconciliation。这一块底层成熟度较高。

Operit产品层更完整：Workspace 文件树、代码编辑、语法高亮、实时预览、备份、项目模板、SSH / SFTP，以及多种项目模板。这意味着用户看到 Operit 时会觉得“这是完整工作台”，而 Helix 用户更容易觉得“这是 Agent 可以调用文件工具”。

建议把 Workspace 从设置型功能升级成一级产品入口。推荐主导航：

```text
Chats
Tasks
Workspace
Capabilities
Settings
```

Workspace 首页直接展示 Recent projects、Recent files、Agent changes、Running tasks 和 Artifacts，而不是只做文件浏览器。

---

### 6. Coding Agent 产品能力

AndCode 是这一领域最值得 Helix 学习的移动竞品。它已经把 OpenCode、Claude Code、Antigravity、PRoot、Git、Repo、Diff、Terminal、Tool approvals、Sessions 包装成移动端 Coding Agent，并进一步提供 Scheduled tasks、Voice、Wake Word、Android default assistant、Home-screen widget、local/remote runtime handoff。参考：[AndCode](https://github.com/yuga-hashimoto/and-code)。

Helix 当前已经有 files、code execution、PRoot、CLI、workspace，但缺少真正的 Developer UX。可以概括为：

```text
底层能力较强
产品闭环偏弱
```

建议新增 `Project Task View`，至少包含 Project、Agent、Changes、Verification、Result 五部分：显示 branch / changed files / runtime；Plan / Progress / Tool activity；Diff / Revert / Accept；Test / Lint / Build；Artifacts / Summary。

---

### 7. Git 功能优先级

不需要立刻做完整 Git GUI。

P0：`git status`、`git diff`、changed files、diff viewer。P1：branch、commit、restore。P2：remote、push、PR、merge。

AndCode 已经证明，Diff 是移动 Coding Agent 非常重要的产品组件。如果 Agent 可以改代码，却无法让用户轻松查看修改内容，信任成本会非常高。

---

### 8. Shell / Linux Runtime

Operit 内置 Ubuntu、Python、Node、vim、SSH、tmux、Git、APKTool 等，用户会把它理解成“手机上的完整开发环境”。AndCode更值得学习的地方是，它没有把 PRoot 当卖点，而是将它隐藏到 `Workspaces → This Android Device → Set up on device` 的任务路径里。DSHA则进一步把 Ubuntu、Node、DeepSeek Harness、插件、ADB、浏览器、备份等封装成“一键启动完整 Harness”的产品。参考：[DSHA](https://github.com/DSH-APP/DSHA)。

Helix 不要宣传 PRoot 本身，而应该做 `Enable Developer Runtime`：检查环境 → 下载 → 校验 → 安装 → 自测 → Ready。状态至少包括 Not installed、Installing、Ready、Broken、Repair、Update available。

---

### 9. 浏览器能力

Helix 当前已经有内置 WebView、页面 snapshot、click、input、scroll、screenshot、download 和 browser tool，这已经接近真正的 Browser Agent。

Operit浏览器产品层更成熟：tabs、history、bookmarks、downloads、user scripts、permissions、multi-window 和 Agent automation。

Helix 建议 P0 补 tab UI、download history、`Open page in Agent context`；P1 补 bookmarks、reading list、saved page；P2 再考虑 browser profile、user scripts。

更重要的是不要单独宣传“浏览器自动化”，而应做 `Research Task`：search → browser → extract → compare → citations → Markdown → artifact。最终 UI 直接展示 Sources、Artifact、Task log。

---

### 10. Android Device Agent

Operit目前在这一领域覆盖很广，包括 Intent、Accessibility、Shizuku、Root、ADB、screen understanding、virtual display、screenshots、PhoneAgent / AutoGLM。ClawMobile则用更简单的产品故事表达：“Agent 使用手机本地工具，并逐步开启更强权限”，同时强调重复 UI 操作可沉淀为 reusable skills。参考：[ClawMobile](https://github.com/ClawMobile/ClawMobile)。

Helix 不建议一开始追求“万能点击 Agent”，而应明确三级能力：

```text
Level 1 — Native
Intent / Calendar / Notification / Share / Clipboard

Level 2 — Accessibility
UI tree / click / input / scroll / app navigation

Level 3 — Advanced
Shizuku / ADB / Root
```

Agent 默认选择最低风险、最确定的实现。

---

### 11. Capability Center

这是 Helix 很适合打造的特色 UI。

建议增加一级页面 `Capabilities`：

| Capability | 状态 |
|---|---|
| Files | Ready |
| Browser | Ready |
| Notifications | Needs permission |
| Calendar | Ready |
| Accessibility | Off |
| Developer Runtime | Not installed |
| Root | Unavailable |
| MCP | 3 connected |

点击后展示 What it enables、Why needed、Current scope、Test、Repair、Disable。

这比把所有权限塞进 Settings 更产品化，也更符合 Helix“渐进开启能力”的方向。

---

### 12. Plan

桌面 Agent 已经把 Plan 变成标准能力。DeepSeek Harness 当前 Plan 支持 `/plan`、explore、design、review、approve、continue planning；Plan Mode 本身是协作状态，最终计划通过 review 交给用户。参考：[DeepSeek Harness Plan](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/plan/README.md)。

Helix 当前已有 Plan mode 和只读限制，但 UI 闭环还不够突出。推荐 Plan UI 直接展示 Objective、Plan、Risks、Expected changes，并提供 `Continue Planning`、`Edit`、`Execute`。

Plan 不应该只是一段 assistant Markdown。

---

### 13. Goal 与 Tasks

Goal 是 Helix 最值得做成核心产品特色的功能。DeepSeek Harness 已经把 Goal 做成 durable same-session objective，并将自动推进拆成独立 goal-round driver。参考：[DeepSeek Harness Goal](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/goal/README.md)。

Helix 建议把 Goal 从聊天模式概念提升成一级 `Tasks` 产品入口。

任务卡示例：

```text
Fix build failure
RUNNING
3/8 steps
12 min
2 approvals

Last activity:
Tests failed in module X

[Open] [Pause]
```

Tasks 页面至少包含 Running、Needs You、Scheduled、Completed、Failed。`Needs You` 聚合 approval、input、permission、runtime install 等状态。这会让 Helix 与普通 Chat App 产生非常明显的产品差异。

---

### 14. Scheduled Tasks

这是目前 Helix 明显缺失的高价值产品能力。

PalmClaw 已公开提供 Cron、Heartbeat、Always-on；AndCode支持 one-time schedule、recurring schedule、run history；Operit Workflow支持 manual、scheduled、Tasker、Intent、voice、app-start；DeepSeek Harness已经将 `schedule` 作为稳定产品包。参考：[PalmClaw](https://github.com/ModalityDance/PalmClaw)、[DeepSeek Harness Packages](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/README.md)。

Helix 第一版只需要支持 One-time、Daily、Weekly，而且高风险动作仍要求运行时人工确认。不要立刻做复杂 Workflow。

---

### 15. Background / Always-on

PalmClaw 的 Always-on 很有传播力，但 Android 后台环境复杂。Helix 可以做更可信的版本：`Foreground Goal`。

通知显示：

```text
Helix is working
Step 3/6
Battery usage
[Open] [Pause]
```

如果被系统中断，则明确显示 `Task paused by Android` 和 `Resume`，而不是宣传无限后台自治。

---

### 16. Task Ledger / Todo

DeepSeek Harness 已将 `todo_write` 作为稳定产品组件，pi 的 subagent 示例也会实时展示 running / done / failed。参考：[DeepSeek Harness Packages](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/README.md)、[pi subagent](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/examples/extensions/subagent/README.md)。

Helix 应在 Task detail 中增加 Progress：

```text
✓ Inspect project
✓ Reproduce bug
→ Modify AuthRepository
○ Run tests
○ Review diff
```

这是开发成本相对低、但能明显提高长任务可理解性的功能。

---

### 17. 审批体验

Helix 底层审批很强，但用户产品体验不能只是 `Approve / Deny`。建议提供：Allow once、Allow this task、Allow this workspace、Always ask、Deny。

审批卡应该把 JSON 转换成人话，例如：

```text
Helix wants to modify 3 files

src/Auth.kt
src/Login.kt
tests/AuthTest.kt

Reason
Fix token refresh race

[Review diff]

Allow:
( ) Once
( ) For this task

[Deny] [Allow]
```

安全底层仍可继续沿用当前 Proof 绑定模型。

---

### 18. Skills

主流 Agent 都在向 Skills 靠拢：Operit 有 Skill / ToolPkg / Market；PalmClaw 有 Skill / ClawHub；ClawMobile强调 reusable mobile skills；DeepSeek Harness有 Skills；pi有 Skills、Prompt Templates 和 Extensions。

Helix 产品 UI 不建议把所有扩展都叫 Skill。建议区分：Instructions、Tools、Automations、Mobile Skills。

短期不用直接做开放 Market。先做 `Official Skills`，例如 Git Project Review、Research Report、Downloads Organizer、Log Analyzer、APK Inspector、Website Extractor。用官方模板验证真实需求后，再决定是否发展市场。

---

### 19. MCP 与 Connector 产品化

Helix MCP 已经属于较完整能力，但普通用户不应该第一眼面对 `Add MCP Server`。建议产品层改名为 `Connect Tools`，提供 GitHub、Filesystem、Database、Search、Browser、Developer tools 等预设，高级设置再显示 MCP 细节。

理想流程是：Choose connector → Sign in / Config → Test → Capabilities → Ready。

协议属于实现细节，不应成为首次使用门槛。

---

### 20. A2A

A2A 是 Helix 相对少见的潜力点，但对普通用户过于抽象。不要宣传“支持 A2A”，而应表达成 `Connect another Agent`。

例如：Research Agent、Coding Agent、Home Agent。用户只需要理解：Helix 可以把特定任务交给另一个 Agent，同时本机权限仍受 Helix 控制。

---

### 21. Memory

Operit、RikkaHub、PalmClaw 都已经提供长期记忆，但对 Helix 第一目标用户——开发者、power user、automation user——Memory 并不是最关键功能。

更值得先做的是 Project Instructions、Task History、Artifact History、Workspace Context。它们比“用户画像式长期记忆”更直接地提升真实任务完成率。

---

### 22. Workflow

Operit 已经有成熟可视化 Workflow，DeepSeek Harness也有 dynamic workflow 能力。但 Helix 目前不应该直接做 DAG Editor。

建议先做 `Save as reusable task`：一次成功任务可以保存，抽取 Folder、Output 等参数，之后支持 Run、Schedule、Share。只有真实模板数量足够多后，再考虑可视化 Workflow。

---

### 23. Subagent

桌面 Agent 越来越常见 Subagent，但移动端资源有限。Helix 第一版可以只做 `Research helper`：read-only、no Android actions、no high-risk tools，并在 UI 展示 Main task 下各 worker 的状态。

不要现阶段做 swarm。

---

### 24. 语音

Operit 与 AndCode 在语音入口明显领先。AndCode已经有 Push-to-talk、Wake word、TTS、Default assistant。

Helix建议 P1 先做 push-to-talk，P2 再做 TTS，P3 才考虑 wake word。Android 系统 STT/TTS 足够验证需求，不需要先投入复杂本地语音模型。

---

### 25. 移动端入口

这是 Helix 当前产品差距最大、但开发成本相对不高的一组。

Operit 已提供 floating window、bubble、widget、default assistant、voice wake；AndCode已提供 widget、default assistant、wake word。

Helix建议：P0 强化 Android Share，例如网页 / 图片 / 文件 → Share → Helix → Summarize / Research / Process；P1 增加 Home-screen widget，提供 Ask Helix、New Task、Continue Task；P1 增加 Default Assistant；P2 再考虑 Floating bubble。

---

### 26. Web Access 与跨设备

RikkaHub 和 Operit 都提供 Web Access。Operit2更进一步，将 phone / desktop / cloud 组织成个人设备 Space；AndCode支持 local → remote PC handoff。

Helix 当前不做 remote worker / desktop pairing 是合理的，因为这会引入 identity、sync、credential、transport、trust、conflict、process handoff 等大量复杂度。

短期应该坚持：**手机本机自己完成任务**。比 Web Access 更优先的是任务导出和 Artifact 分享。

---

### 27. 恢复体验

这是 Helix 最应该“把工程优势变成产品优势”的领域。

Helix 已有 Turn recovery、Goal persistence、Tool reconciliation、file operation recovery、unknown side effect、process death parking 等能力。UI 应该显性展示：

```text
Task interrupted

Completed
✓ Read repository
✓ Modified 2 files

Unknown
? npm install may have finished

Not started
○ Run tests

[Review]
[Continue Safely]
[Stop]
```

这是非常适合 Helix 做差异化宣传的能力。

---

### 28. Artifact Center

桌面 Agent 和 Work 类产品越来越强调结果而不是聊天文本。Helix 应建立统一 Artifact 概念：File、Report、Patch、Diff、Archive、Image、Downloaded file、Test report、Web snapshot。

Task Completed 页面可以展示：Summary、Artifacts、Changes、Verification、Cost，并提供 Share、Open、Run Again。

从“聊天输出”升级为“任务产物”，是 Helix 成为工作台的关键一步。

---

### 29. Product Onboarding

第一次打开 Helix，不应该让用户同时理解 Provider、API URL、Model ID、Safety Profile、Runtime、MCP、A2A。

推荐首次启动只做三步：

```text
Step 1: Choose Model
OpenAI / Anthropic / DeepSeek / Custom

Step 2: What do you want Helix to help with?
Files / Research / Coding / Android tasks

Step 3: Start a real demo task
```

权限只在任务真正需要时申请。

推荐三个 starter tasks：Organize Files、Research、Analyze Project。目标是让用户在很短时间内完成第一次真实任务，而不是完成一堆配置。

---

### 30. Helix 应如何与各直接竞品竞争

#### 对 Operit

不要竞争功能总数、角色、Avatar、本地模型、Market 规模、Workflow 节点数量。应该竞争任务完成率、任务恢复、执行透明度、Provider flexibility、移动工作流简洁度。

#### 对 AndCode

AndCode更像“手机上的 Claude Code / OpenCode UI”。Helix应强调“不只 Coding”，可以组合 Git + Browser + Android + Files + MCP。

#### 对 PalmClaw

PalmClaw强在 Always-on、Cron、Channels、Memory、Skills。Helix应补 Scheduled Goal、Notification Resume、Task Dashboard，但继续强化执行审计和恢复。

#### 对 ClawMobile

ClawMobile强调 phone becomes agent environment。Helix可以强调 structured local tools + workspace + runtime isolation + recovery，并逐步增加 learned task。

#### 对 RikkaHub

RikkaHub是成熟聊天客户端。Helix基础 Chat UX 至少要达到“长期日常可用”，然后突出 Agent Tasks。

#### 对 DSHA

DSHA核心价值是把完整 DeepSeek Harness 一键装到手机。Helix优势是无需所有任务都进入 Linux Harness：Native tool → QuickJS → Developer Runtime 分级执行。用户界面不需要显示 E0/E1/E2，只显示 Native、Script、Developer Runtime 即可。

---

### 31. 推荐 Helix 主导航

```text
Chats
Tasks
Workspace
Capabilities
Settings
```

Chats 负责对话；Tasks 管理 Goal / Scheduled / Background / Completed；Workspace 管理文件、项目、Artifacts；Capabilities 管理 Browser、Developer Runtime、Android、MCP、Skills；Settings 管理 Provider、UI、安全与 Advanced。

---

### 32. 推荐 Chat 页面

```text
Header
Model
Mode

Messages

Task state
├ Plan
├ Progress
├ Tool activity
└ Artifacts

Composer
Attachment
Voice
Mode
```

Tool logs 默认折叠，不要淹没聊天。

Mode 保留 Chat / Plan / Act / Goal，但给普通用户解释：Chat = Ask questions；Plan = Explore and create a plan without making changes；Act = Let Helix complete this task now；Goal = Keep working on a longer objective and resume later。

---

### 33. P0 功能优先级

未来 1–2 个大版本建议优先完成：

| 功能 | 原因 |
|---|---|
| Tasks Dashboard | Agent 产品核心信息架构 |
| Plan Review | Plan 从能力变成产品 |
| Progress / Todo | 长任务可理解 |
| Artifact Center | 从聊天升级为工作台 |
| Git Diff | Developer 闭环 |
| PDF / DOCX | 扩大真实任务覆盖 |
| Capability Center | 降低权限与 Runtime 门槛 |
| Share-to-Helix | Android 高频入口 |

---

### 34. P1 功能优先级

| 功能 | 原因 |
|---|---|
| Scheduled Goal | 对标 PalmClaw / AndCode |
| Foreground Task | 移动长期任务 |
| Widget | 高频入口 |
| Voice | 手机自然入口 |
| Official Skills | 降低提示词门槛 |
| Test / Build result | Coding 完整闭环 |
| Project Instructions | 对标桌面 Coding Agent |

---

### 35. P2 功能优先级

Default Assistant、Learned Mobile Skill、Tasker、Shizuku / ADB、Read-only Subagent、Web Access。

---

### 36. P3：有数据再做

Visual Workflow、Plugin Marketplace、Multi-agent、Remote Worker、Desktop Client、Local LLM、Voice Wake、Full IDE、Role System、Avatar。

这些功能成本高，而且不直接构成 Helix 当前最有机会建立的差异化。

---

### 37. 推荐开发节奏

#### Phase 1 — Task Productization

完成 Tasks、Plan、Progress、Artifacts、Recovery UI。

#### Phase 2 — Developer Loop

完成 Git、Diff、Test、Project Instructions、Developer Runtime onboarding。

#### Phase 3 — Mobile Loop

完成 Share、Widget、Voice、Foreground Goal、Schedule。

#### Phase 4 — Automation

完成 Accessibility、Saved Task、Learned Skill、Tasker。

#### Phase 5 — Ecosystem

完成 Skill Gallery、Connector presets、Subagent、A2A UX。

---

### 38. 功能评价指标

Helix 以后不应只统计“实现了多少 Tool”，更应该统计：First Task Success、Time to First Value、Task Completion Rate、Recovery Rate、Approval Burden、Artifact Success、7/30 日真实任务复用。

这些指标会比 GitHub Star 或 Tool 数量更真实地告诉你产品是不是变好了。

---

### 39. 目标用户

第一目标：开发者 / AI 工具用户，手机上处理 repo、文件、脚本、GitHub、日志、网页。第二目标：Android power user，已经使用或理解 Tasker、Termux、Auto.js、Root、Shizuku。第三目标：AI 重度用户，拥有 OpenAI、Claude、DeepSeek、Gemini 或自建 API，希望一个 App 驱动真实任务。

暂时不要主攻普通聊天用户、完全零配置用户和企业客户。

---

### 40. 推荐宣传语言

不要用“Android 最强 AI Agent”或“最安全的 AI Agent”作为第一句。

推荐：

> **让 AI 真正在你的 Android 手机上工作。**

副文案：

> **连接你自己的模型，让 AI 在手机本地处理文件、网页、代码和 Android 任务。**

英文：

> **Helix is a local-first AI execution workspace for Android — connect your own models and let agents work with files, web, code and device capabilities directly on your phone.**

首页只讲三个卖点：Local execution、Your models、Recoverable tasks。

---

### 41. 最值得做的 Demo

#### Demo A — Developer

“检查这个项目为什么测试失败并修复”：inspect → plan → edit → diff → test → artifact。

#### Demo B — Research

“调研这 5 个开源 Agent，生成 Markdown”：browse → extract → compare → report。

#### Demo C — Files

“整理 Downloads，但先让我看计划”：Plan → approval → move → summary。

#### Demo D — Android

“根据这条通知帮我创建日历事件”：优先使用 Native Tool，而不是 UI 点击。

#### Demo E — Recovery

任务执行中强制杀 App，重新打开直接 `Resume Task`。这是非常适合 Helix 的差异化宣传视频。

---

### 42. 最终产品定位

Helix 不应该成为另一个 AI Chat，也不应该成为另一个 Termux UI，更不应该成为“功能更多但体验更复杂的 Operit”。

最适合的产品定义是：

> **Helix 是 Android 上的 AI 执行工作台：连接用户自己的模型，将文件、网页、代码和手机能力组合成可执行、可检查、可恢复的任务。**

未来所有功能都可以用一个问题判断是否值得做：

> **这个功能是否能让用户在手机上完成更多真实任务，或者让已有任务更容易开始、理解、恢复和重复？**

如果答案是否定的，就不应该进入近期路线。

---

### 参考资料

1. Helix Agent — https://github.com/dollarser/helix-agent\
2. Operit — https://github.com/AAswordman/Operit\
3. Operit2 — https://github.com/AAswordman/Operit2\
4. PalmClaw — https://github.com/ModalityDance/PalmClaw\
5. RikkaHub — https://github.com/rikkahub/rikkahub\
6. AndCode — https://github.com/yuga-hashimoto/and-code\
7. ClawMobile — https://github.com/ClawMobile/ClawMobile\
8. DSHA — https://github.com/DSH-APP/DSHA\
9. DeepSeek Harness Packages — https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/README.md\
10. DeepSeek Harness Plan — https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/plan/README.md\
11. DeepSeek Harness Goal — https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/goal/README.md\
12. DeepSeek Harness Tool Runtime — https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/tools/README.md\
13. pi coding agent — https://github.com/earendil-works/pi/tree/main/packages/coding-agent\
14. pi subagent example — https://github.com/earendil-works/pi/blob/main/packages/coding-agent/examples/extensions/subagent/README.md\
15. Claude Code — https://docs.anthropic.com/en/docs/claude-code\
16. OpenAI Codex — https://openai.com/codex/\
17. WorkBuddy — https://work-buddy.ai/\

---

# Part IV — 统一行动清单

## A. 可以立即拆成开发任务的 P0 项

| 编号 | 任务 | 主要产出 | 价值 |
|---|---|---|---|
| HX2-01 | AgentRuntime 收敛 | 统一 submit/resume/cancel/observe | 避免入口各自编排 |
| HX2-02 | AgentLoop 重构 | `ChatModelLoop` 去 Chat 化 | 为 Goal/Schedule/Channel 打基础 |
| HX2-03 | Context 单主干 | 合并 ContextBuilder 与生产请求路径 | 消除双上下文体系 |
| HX2-04 | Prompt Registry | ordered/scoped prompt sections | 控制模式、Skill、项目指令冲突 |
| HX2-05 | Plan Artifact 闭环 | submit/review/revise/execute | Plan 产品化 |
| HX2-06 | Act Completion | complete/partial/blocked report | 避免“模型停了=完成” |
| HX2-07 | Task Ledger | todo/in-progress/done/blocked | 长任务可理解 |
| HX2-08 | GoalDriver | user/notification/schedule driver | Goal 与自动推进解耦 |
| PX-01 | Tasks Dashboard | Running/Needs You/Completed/Failed | 从 Chat App 转向 Agent Product |
| PX-02 | Artifact Center | report/diff/file/test artifact | 结果可交付 |
| PX-03 | Git Diff | status/changed files/diff viewer | 开发者闭环 |
| PX-04 | Capability Center | 状态/授权/测试/修复/关闭 | 降低 Advanced 门槛 |
| PX-05 | Document Attachments | PDF/DOCX/HTML/Markdown | 提高研究/办公任务覆盖 |
| PX-06 | Share-to-Helix | 网页/图片/文件分享直接建任务 | Android 高频入口 |

## B. 产品发布前建议达到的最小闭环

一个外部用户安装 Helix 后，理想路径应该是：

```text
Install
→ Choose Provider
→ Choose Starter Task
→ Grant only required capability
→ See Plan / Progress
→ Review critical action
→ Receive Artifact
→ Kill / background app without losing task facts
→ Open Tasks and resume
```

只要这个闭环足够强，即使 Helix 暂时没有 Operit 那么多功能，也已经具备独立产品价值。

## C. 每次新增功能前的判断问题

以后每个候选功能都建议先回答三个问题：

1. **它是否让用户完成新的真实任务？**
2. **它是否显著降低已有任务的开始、理解、恢复或重复成本？**
3. **它是否可以复用现有 AgentRuntime / Dispatcher / Task / Artifact 主干，而不是再造一条特殊执行路径？**

只有至少满足一项产品价值，并且不破坏第三项架构原则，才值得进入近期版本。

## D. 最终一句话

> **Helix 最值得做的不是“Android 上功能最多的 Agent”，而是“Android 上最像真正 Agent Runtime、同时又最像移动产品的执行工作台”。**
