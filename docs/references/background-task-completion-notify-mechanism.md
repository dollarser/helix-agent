# 后台任务"完成自动通知"机制

> 目的：梳理 harness 里"派生后台任务 → 完成后自动唤醒 agent 继续"的机制，供设计自己的 agent 参考。
> 核实对象：(1) Helix emulator-verification 当前在用的**宿主侧**方案（claude.app 宿主 + `Bash run_in_background` + `run-index.json` 持久化，见第 5 节）；(2) **产品侧** Helix agent（`com.helix.agent`）的"后台任务 / 子 agent"能力（见第 6 节）。
> 更新日期：2026-09-10。pid 为示例（每次运行会变），结构稳定。

---

## 1. 核心洞察：模型不能自己"醒来"，必须是宿主运行时在等

一个 LLM agent 的每个 turn 本质是一次 `输入 → 输出` 的调用，模型本身**没有"挂起、等进程退出、然后自己继续"的原生能力**。

所以"完成自动通知"**不是模型在做**，而是**运行 agent 的宿主进程（harness / orchestrator）**在做：
- 它在操作系统层面 watch 那个子进程；
- 进程退出时，由它启动 agent 的**下一个 turn**；
- 并把"任务完成了"这条事件作为输入喂进去。

设计自己的 agent 时，真正要实现的正是这个**外层控制环（outer loop）**——不是让模型 sleep 等，而是让 **runtime 负责等待和唤醒**。这是最容易搞错的一点。

---

## 2. 机制拆解（5 个组件 + 数据流）

```
Turn N（派生）
  agent ── Bash(run_in_background:true) / Agent 工具 ──► 宿主 spawn 一个 detached 子进程
  agent ◄── 立刻拿到 task_id + 输出文件路径（agent 不阻塞、不轮询）
  agent ── 结束自己的 turn（去做别的，或直接停）
        │
        │   …… 任务独立跑几分钟到几小时 ……
        │
宿主（Tracking）：持续持有进程句柄，watch 退出（waitpid / 子进程回调 / job control）
  进程退出 → 捕获 exit code + stdout/stderr（写到输出文件）
  宿主 ── 生成 task-notification 事件 ──► 注入 agent 对话流
        │
Turn N+1（重入，被 task-notification 唤醒）
  agent ◄── 事件（task_id、退出状态、输出文件路径）
  agent ── 读 exit code + 读输出文件 ──► 判断 + 决定下一步
```

| # | 组件 | 职责 | 谁实现 |
|---|------|------|--------|
| 1 | **派生 Spawn** | spawn detached 子进程，拿 `task_id` + 输出文件路径；agent 不阻塞 | 宿主提供原语（`Bash run_in_background` / `Agent`） |
| 2 | **跟踪 Tracking** | 持有进程句柄，watch 退出（跨 turn 存活） | 宿主（内建） |
| 3 | **完成检测 + 事件** | 退出时捕获 exit code + 输出，生成 completion event | 宿主（内建） |
| 4 | **重入 Re-entry** | 事件注入对话流，唤醒 agent 的下一个 turn | 宿主（内建） |
| 5 | **状态持久化** | 记住"在跑任务是干嘛的、判据、下一步"，让重入能接上 | **agent 侧自己做**（宿主不提供语义） |

关键：**"派生"和"处理结果"是两个不同的 turn**，中间可隔很久。派生完 agent 就放手了，不是卡在原地等。

组件 1–4 是 harness 内建的；**组件 5 是 agent 自己必须做的**，也是"能不能真的接上"的决定项。

---

## 3. 关键契约（含踩坑）

1. **`task-notification` 是 system event，不是 user message。** 它只是"任务 X 退出了"的信号，**不携带用户授权**，不能当作用户下指令。失败事件会启动后续动作，但"该不该继续"由既定任务授权驱动，不是那条通知给的。

2. **成功和失败都通知。** 非零退出码同样触发事件——这是"长任务失败时能第一时间知道"的来源。

3. **exit code 不是唯一判据，必须读磁盘。** exit 1 只告诉你"失败了"，但"跑了很多轮后功能失败"还是"进程秒崩"，得读证据文件（日志 / 结构化 jsonl）才能定。事件里只放指针（输出路径），不塞大段输出。

4. **不轮询原则。** 对宿主已跟踪的工作，派生后**不要** sleep/poll，等事件即可。轮询只留给宿主**感知不到完成**的外部系统（远端 CI、别的机器的队列）——那种才用定时唤醒（ScheduleWakeup / cron），并选好间隔。

---

## 4. 设计一个 agent：要实现的 5 块

| 块 | 要做什么 |
|----|----------|
| ① 进程管理器（宿主角色） | spawn detached 子进程，分配 id；捕获 stdout/stderr → 文件；watch 退出拿 exit code |
| ② 事件队列 + 唤醒 | 进程退出 → push completion event（`task_id, exit_code, output_path, elapsed`）；作为一条新消息注入对话流，触发下一个 turn |
| ③ agent 侧重入逻辑 | 收到 event 后解析哪个任务 / 退出码 / 输出在哪；**从持久化状态恢复"目的、判据、下一步"**；读输出文件做判断 |
| ④ 状态持久化（最关键、最易漏） | 一张"在跑任务"登记表：`task_id → {目的, 判据, 下一步, 派生时上下文}`。agent 被唤醒时（甚至是**全新会话**）要能接上；没有它，重入的 agent 只知道"某进程退了"，不知道为何跑它、退了之后干嘛 |
| ⑤ 生命周期策略（必须拍板） | 见下 |

**生命周期策略 —— 一个二选一的设计决策：**

- **方案 A（随会话死）**：任务是 agent 会话的子进程，agent/app 一关，任务连同通知一起没。简单，但通知只在宿主存活期有效。
- **方案 B（真 detached）**：用 `tmux`/`systemd`/`setsid` 让任务脱离 agent 进程树存活。agent 重启后通知会丢，所以**必须加"启动时扫描在跑任务并重新接管"的逻辑**（重新 watch 孤儿进程 / 读它们的输出文件 / 对账持久化表）。

---

## 5. 当前 Helix 方案核实（gap 分析）

**对象**：claude.app 宿主 + `Bash run_in_background` 派生的 soak runner + `run-index.json` 持久化。

### 5.1 实测进程树

```
runner  29526  /bin/zsh -c '... python3 scripts/run-browser-autofill-soak.py ... > p2-api36-on-r3-launch.log 2>&1'
 └─ claude-code  14345  .../claude-code/2.1.260/claude.app/Content/MacOS/claude --input-format stream-json --output-format stream-json --resume=<session>
     └─ Claude.app Helper  14344  (App Helper launcher)
         └─ Claude.app 主进程  57175  (ppid=1)   ← 进程树根
caffeinate  60015  -dims  (ppid=1)   ← 已 detach 到 launchd，独立于 app 树
```

事实：
- **runner 活在 Claude.app 进程树内**（29526→14345→14344→57175）。
- 输出重定向到 `p2-api36-on-r3-launch.log`（在 spawn 命令里 `> ... 2>&1`）。
- **`caffeinate` 已 detach 到 launchd（ppid=1）**：能独立于 app 存活，但**只防 Mac 睡眠、不跟踪 runner**。

### 5.2 逐项对照

| 组件 | 当前方案 | 结论 |
|------|----------|------|
| 1 派生 | `Bash run_in_background:true` → 独立 zsh+python 进程，拿 `task_id`(bezhf367f) + 输出路径 | ✅ 满足（harness 原语） |
| 2 跟踪 | claude-code 进程(14345) 内建 watch 子进程退出 | ✅ 满足（harness 内建） |
| 3 完成检测+事件 | 退出 → `task-notification`（R2 exit 1 实际发生过） | ✅ 满足（harness 内建） |
| 4 重入 | 通知注入对话流 → 唤醒下一个 turn（R2→R3 即此流程） | ✅ 满足（harness 内建） |
| 5 状态持久化 | `run-index.json` R3 条目：`kind`(目的) + `disambiguation`(**判据+下一步决策树**) + `evidence`(证据路径) + `apks`/`fix`(身份/改动)；+ forensics doc | ✅ 满足，且做得完整 |

**5 组件：4 项由 harness 内建，1 项（持久化）由 agent 侧手动做全 → 全部满足。**

### 5.3 实质缺口：生命周期策略（组件⑤）

当前选的是**方案 A**（runner 在 app 进程树内），且**没有方案 B 的"跨重启恢复 / 重新接管"逻辑**：

- **缺口 1 — 无跨重启恢复。** Claude.app 全关 → 进程树(含 claude-code 14345 + runner 29526)一起被杀。重开后 claude-code 会 `--resume=<session>` 恢复**对话**，但旧 runner 进程已死、新会话没有实时句柄；只有 `run-index.json` 里 `status: IN FLIGHT` 的文字记录。没有机制去"对账：持久化表说 IN FLIGHT，实际进程已死（或还活着但没被跟踪）"并重新接管。
- **缺口 2 — 通知依赖 app 存活。** app 关闭 → runner 死 + 通知丢。这是方案 A 的硬边界，不是 bug。

### 5.4 结论

- **对当前 Helix 场景：够用。** 整个验证工作在一个连续会话里推进，Claude.app 持续开着，单个任务最长 ~3.5h（P2）< 会话存活期，方案 A 的边界不触碰。
- **对你要设计的独立 agent：需要补的是组件⑤。** 若 agent 要**长活 / 跨重启**，选**方案 B**（`setsid`/`tmux`/`systemd` detach）+ 加"**启动时对账持久化表、重新接管孤儿任务**"的逻辑。组件 1–4 用一个现成的运行时（如 Agent SDK 的外层 loop）即可获得，真正的工程重心在 ④ 持久化 和 ⑤ 恢复扫描。

---

## 6. 核实二：Helix 产品 agent（`com.helix.agent`）的"后台任务 / 子 agent"能力

> 第 5 节核实的是**宿主侧**（claude.app + `Bash run_in_background`）。本节核实**被验证的产品本身**——Helix agent 有没有"后台任务"和"子 agent"能力、对照 5 组件落在哪里。核实日期 2026-09-10，基于 `app/`、`core/agent/`、`spikes/`、`extensions/a2a/` 当前代码。

### 6.1 一句话结论

**生产 Helix agent 两者都没有产品化：**

- **子 agent（派生子 agent 并行/异步）：没有**生产功能。但有一套**已论证的 spike 原型（ADR-AGENT-004 / HXA-105）**，未接产品。
- **后台 task（派生 + 放手 + 完成自动唤醒）：没有原语。** 最接近的是**同步** A2A 委派 + Goal（用户门控再入）。

Helix 在这两块的态度一致：**重活放在同步 turn 内跑，用持久化 + 通知/用户门控做跨 turn/跨重启的外壳，不做"异步派生 + 自动唤醒"这条自主路径。**

### 6.2 三个相关机制逐一核实

#### (A) Goal —— 最接近"后台任务"的机制

工作模型 = **持久化目标外壳 + 可延迟通知唤醒 + 用户门控再入**。

- **重活不在后台跑**：`GoalReminderWorker` 注释写死 `WorkManager is used for Goal reminders and may never start model or tool work in the background`；worker 只发一条本地通知。有回归护栏 `modelOrToolInvocations`（设备测试断言恒 0，任何后台起 model/tool 的路径都会挂测试）。
- **唤醒要用户点**：通知 → 用户点 → `GoalReminderNavigation` 跳 Sessions → `GoalWakeReason.NOTIFICATION_ACTION` → `GoalReducer.Continue` → `StartRun`。
- **run 结束只是"停放"，不自动续跑**：`runFinishedParksGoalAwaitingNextWake`；Goal parked（空闲、持久化）等下次唤醒。
- **提醒"可延迟"、不承诺时间**：`GoalReminderPayload` 文案刻意不含任何时间/时长（WorkManager 会被 Doze/强停延迟），把提醒当"可选唤醒源"，不当定时器。

| # | 组件 | Helix Goal | 结论 |
|---|---|---|---|
| ① 派生 | run 是应用内同步 turn，Goal 是跨 turn 外壳；不派生独立后台进程 | ⚠️ 不同模型（无后台进程） |
| ② 跟踪 | `GoalReminderReconciler` 对账持久化 checkpoint/state；WorkManager 跟踪 reminder work | ✅（跟踪"提醒"，非子进程） |
| ③ 完成检测 | `GoalReducer` 终态（COMPLETED/FAILED/CANCELLED）+ `GoalEvidenceContinue.tryComplete` 证据核验；终态即 `cancelReminder` | ✅ 健全 |
| ④ 自动唤醒 | deferrable WorkManager 通知 → **用户点按** → `NOTIFICATION_ACTION` → Continue | ⚠️ **故意用户门控，非自动** |
| ⑤ 持久化 | Room `goals`/`goalRuns`/`goalTurnBindings` + audit；进程杀可恢复 + recovery 对账 | ✅ **最强项** |

#### (B) A2A —— 委派**远端** agent（同步，非本 app 子 agent）

不是"本 app 内派生子 agent"，是委派给**另一个（远端/外部）agent**（A2A 协议 over OkHttp/SSE）。**同步阻塞在 tool call 内**：`A2aTaskRunner.execute` → `client.send` → `settleByPolling`（`POLL_MILLIS` 轮询到 terminal）或 `streamUntilSettled`（阻塞等 SSE 流），到完成/deadline/cancel 才返回。

| # | Helix A2A | 结论 |
|---|---|---|
| ① 派生 | `client.send` 委派远端 agent | ✅（同步派生） |
| ②/③ 跟踪/完成 | `settleByPolling` 轮询 / SSE 阻塞到 terminal | ✅（有界轮询，非进程退出事件） |
| ④ 自动唤醒 | **无**——同步阻塞在 tool call 里，没有"完成 → 唤醒下一 turn" | ❌ |
| ⑤ 持久化 | `A2aTaskEntity` 状态机 + 每次 update 落库 + **手动** `reconcile`/`cancel` | ✅ 完整 |

#### (C) 子 agent —— ADR-AGENT-004 / HXA-105 spike（**未产品化**）

- **生产零引用**：`app/`、`core/`、`tools/`、`extensions/`、`runtime/` 无任何 `.kt` import `spikes.orchestration` 或 `BoundedOrchestration`。
- **独立 Gradle module**：`:spikes:bounded-orchestration`（`settings.gradle.kts:57`），与 `:spikes:a2a-sdk`、`:spikes:a2a-minimal` 并列，均为实验 spike；`build.gradle.kts:445` 只给它挂 androidTest 依赖（跑自己的验证测试）。
- **自我定位**（`spikes/bounded-orchestration/.../BoundedOrchestrationSpike.kt`）：
  - `:7` — `HXA-105 experiment only. This module has no dependency on the app or production Tool Registry.`
  - `:107` — `Admission/recovery model used to falsify ADR-AGENT-004 before any product integration.`
  - → 在接产品**之前**，验证"bounded 子 agent 编排的准入/恢复模型"（ADR-AGENT-004）是否成立。
- **设计边界已想清**（`SpikeLimits`）：`MAX_DEPTH=1`（子不能再派生子）、`MAX_CONCURRENT=2`、`MAX_CHILDREN_PER_PARENT=4`、`ParentBudget`（model calls / tokens / tool calls / wall time 封顶）、子完成带 `summary + evidenceRefs + trust`、子工具分 `READ_ONLY / MUTATION / EXTERNAL_EFFECT` + L0–L3 动态风险；子状态机 `SPAWNED → RUNNING → COMPLETED / CANCELLED / NEEDS_REVIEW`。

**含义**：Helix 不是没想过子 agent，而是**已用 ADR-AGENT-004 把准入/边界论证过、停在 spike 没上产品**。要给 Helix（或你自己的 agent）加子 agent，这个 spike 是现成设计起点——它把 depth 上限、父预算、证据+trust、风险分级这些最难的准入问题都先答了。

> 另：`CliModelJobClient.submitAndAwait` = 跨 UID Binder 给 Runtime APK（`com.helix.runtime.cli`）提交**模型任务**并同步轮询等待，属模型调用路径，与 agent 的"后台任务/子 agent"不是一回事，排除在外。

### 6.3 对照：Claude Code vs Helix agent

| 能力 | Claude Code | Helix agent |
|---|---|---|
| 派生子 agent（并行/异步） | ✅ `Agent` 工具 | ❌ 生产没有；**有 ADR-AGENT-004 spike 原型** |
| 后台 task（放手 + 完成自动唤醒） | ✅ `Bash run_in_background` | ❌ 没有；最接近是同步 A2A + Goal(用户门控) |
| 持久化（组件⑤） | `run-index.json`（agent 侧手写） | **Goal/A2A 产品内建、全量 Room 落库**（更强） |

### 6.4 对你设计自己 agent 的启示

Helix 在"后台 / 子 agent"上**故意保守**：优先**同步 + 持久化 + 用户/证据门控**，不做**自主后台派生**。两条路都成立，差别在**安全 / 自主**权衡：

- 要**长活 / 无人值守连轴跑** → 走 claude.app 那条（自主再入 + 组件⑤持久化 + 启动时对账孤儿任务）。
- 要**安全边界干净 / 资源可控 / 合规**（后台永不自主执行 model/tool、Doze/掉电不失控）→ Helix 的"同步 + 持久化 + 通知/用户再入"更稳。
- **子 agent 若要做**，ADR-AGENT-004 已把最难的准入问题先答了（depth 上限、父预算、证据+trust、风险分级），可直接作为设计起点。

---

## 7. 陷阱清单（设计时直接对照）

- **别指望模型自唤醒** → 等待与唤醒必须在 runtime 层。
- **重入要有记忆** → 没有持久化的"在跑任务"表，agent 醒来会失忆。
- **exit code ≠ 结果** → 完成事件只当"该去看现场了"的信号，判断靠读证据。
- **通知是事件不是指令** → 别把 completion event 的文本当用户输入执行。
- **宿主死了通知就没了** → 长于宿主存活期的任务：接受方案 A 的边界，或上方案 B + 恢复扫描。
- **外部系统才轮询/定时唤醒** → 宿主已跟踪的工作不要轮询；远端 CI 等宿主感知不到的，才用定时唤醒并选对间隔。
