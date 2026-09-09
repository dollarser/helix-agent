# DeepSeek Harness Goal 功能使用参考

> **本文档定位**：独立个人参考，**不属于** deepseek-harness 仓库的文档体系。
>
> **与项目文档的区分**（三重隔离）：
> 1. **目录隔离**——位于仓库根的 `.personal-notes/`（隐藏目录），不在 `docs/` 树内，
>    不遵循仓库文档规范（双语行对齐、i18n 元数据、doc-sync 门禁、单段单行、word budget），
>    也不会被 `pnpm run doc-sync` 检查、发布到文档网站。
> 2. **版本隔离**——`.personal-notes/` 已写入 `.git/info/exclude`（本地忽略，
>    不会提交、不影响他人 clone、不改仓库的 `.gitignore`），因此它**永远不会进入
>    git 历史**，`git status` 不显示它。
> 3. **权威隔离**——官方权威文档是 deepseek-harness 仓库的
>    `docs/subsystems/goal.md`（及 `.zh.md`，完整指针见下"参考实现指针"节）；
>    两者冲突时以该仓库代码与官方文档为准。本文只补充"实测记录 + 使用建议"。
>
> **事实基准**：2026-08-28 会话，`dsh 0.1.2-alpha.1`。文中机制均经本会话真实调用
> 验证或仓库源码（`packages/goal/`）核对。

---

## 参考实现指针（Helix 参考 DSH 实现 goal 时查阅）

> 2026-09-09 补写。下列路径均相对于 deepseek-harness 仓库根，钉住 commit
> `8edf473cec`（2026-09-05，`docs-zstd-log-manual-inspection` 分支）。
> **只引用、不复制**——官方参考页与 Agent Notes 均锚定 DSH 源码，
> 复制出仓库即失效；后续演进以钉住版本为基线做差异对照。

**源码（权威实现，四个包共约 2600 行 TS）**

| 包 | 路径 | 职责 |
|---|---|---|
| goal | `packages/goal/goal/` | GoalService：CAS revision 状态机、phase 转移规则、session-log 重放、`goal/changed` 通知 |
| tool-goal | `packages/goal/tool-goal/` | 模型侧 3 个工具（`create_goal`/`get_goal`/`update_goal`）+ blocked 轮数门槛（默认 3 轮） |
| command-goal | `packages/goal/command-goal/` | 人类侧 `/goal` 命令（显示/创建/edit/pause/resume/clear） |
| goal-round-driver | `packages/goal/goal-round-driver/` | 框架侧续跑轮驱动：每轮结束后判定是否自动发起下一轮 |

**设计决策 Agent Notes（记录"为什么"，比字段参考页更值得读）**

- `.agents/notes/implemented/feature/2026-07-19-persisted-same-session-goal-domain.md`
  —— 持久化与激活（armed/disarmed 双状态）模型的决策
- `.agents/notes/implemented/feature/2026-07-19-model-facing-goal-tools.md`
  —— 模型工具职责切分与操作权限矩阵
- `.agents/notes/implemented/feature/2026-07-19-human-goal-command.md`
  —— `/goal` 命令设计
- `.agents/notes/implemented/feature/2026-07-19-same-session-goal-round-driver.md`
  —— 续跑轮驱动机制
- `.agents/notes/implemented/feature/2026-08-01-goal-command-input-projection.md`
  —— 命令输入投影

**官方参考页（字段/变体精确记录，含生成的 Cordis API 表面）**

- `docs/subsystems/goal.md`（及 `.zh.md`）—— 类型块由 `verify-type-equiv` 对源码做
  机器校验，cordis-surface 段由 `gen-cordis-catalog.ts` 生成，离开 DSH 源码即无意义；
  需要时直接查 DSH 仓库该路径，勿复制

---

## 1. 一句话定位

Goal 是**同会话长任务目标**机制：人类给出一个目标后，框架在每轮模型工作结束
后**自动发起续跑轮**，跨多轮持续推进，直到模型报告 `complete`（达成）或
`blocked`（阻塞），或轮数用尽。它和"同会话多轮聊天"的区别在于：目标持久化、
轮次由框架驱动、有明确的生命周期状态机。

## 2. 双状态模型（最容易混淆的点）

Goal 同时有两个维度的状态，**互不替代**：

| 维度 | 取值 | 性质 |
|---|---|---|
| `phase`（生命周期阶段） | `active` \| `paused` \| `blocked` \| `complete` | **持久化**（durable snapshot，落盘） |
| `activation`（自动续跑开关） | `armed` \| `disarmed` | **进程本地**，不落盘 |

- 框架只在 `phase === 'active' && activation === 'armed'` 时才自动续跑
- `create` 自动 arm；`pause` 自动 disarm；**会话 resume/fork 后自动 disarm**
  （所以恢复会话后想继续，必须显式 `resume`）

## 3. 分工总览：模型工具 vs 框架执行

| | 模型可调用的工具 | 框架执行（无对应模型工具） |
|---|---|---|
| 创建/读取/变更 | `create_goal`、`get_goal`、`update_goal`（5 个 action） | arming/disarming、自动续跑轮、轮数计数与上限、blocked 门槛强制、authority 校验、状态机持久化、收尾上下文注入 |

一句话：**模型只能"请求"状态变更，能否生效由框架在执行时裁决；"何时续跑"
完全由框架驱动，模型不能也不能需要"调用续跑"。**

## 4. 模型可调用的 3 个工具

### 4.1 `create_goal`

```
create_goal(objective: string, max_goal_rounds?: positive int)
```

- 从**直接人类请求**推断目标；执行时校验 authority——非人类、subagent 发起会被拒绝
- `max_goal_rounds` 可选，自动续跑轮数上限；不设则用部署默认
- 效果（实测）：`revision: 1`，`phase: active`，`activation: armed`（框架自动 arm）

### 4.2 `get_goal`

无参数。返回当前 goal 的：`id`、`revision`、`objective`、`phase`、
已跑续跑轮数（`roundsStarted`）、轮数上限、blocker 原因（仅 blocked 时）、
是否 armed。

**约定：调用 `update_goal` 前必须先 `get_goal`**，并逐字复制其 `goal_id`
和 `revision`（乐观并发控制，revision 不匹配会失败）。

### 4.3 `update_goal`

```
update_goal(goal_id, revision, action, objective?, max_goal_rounds?, blocked_reason?)
```

5 个 action 及权限矩阵（由框架在执行时强制，`authority.kind` 决定）：

| action | 允许的触发方 | 附加规则 |
|---|---|---|
| `edit` | **仅直接顶层人类请求** | 替换 `objective` 和/或 `max_goal_rounds`；参数只在此 action 下合法 |
| `pause` | **仅直接顶层人类请求** | 只允许 `active → paused`，同时自动 disarm（停掉自动续跑） |
| `resume` | **仅直接顶层人类请求** | 轮数已耗尽的 goal 拒绝恢复，提示先 `edit` 提高上限 |
| `complete` | **仅自动续跑轮内**（模型判断目标达成） | 不得携带 `blocked_reason` |
| `blocked` | **仅自动续跑轮内** | `blocked_reason` 必填；**连续轮数未达 `blockedAfterConsecutiveRounds`（默认 3）时被框架拒绝**（错误码 `GOAL_TOOL_BLOCK_THRESHOLD`） |

要点：
- 在人类回合里调 `complete`/`blocked` 会被拒绝——这两个只属于自动续跑轮
- "同一阻塞条件是否持续了 3 轮"的**判断**是模型的责任，"够不够格报 blocked"
  的**强制**是框架的责任

## 5. 人类入口：`/goal` 命令

```
/goal                    显示当前 goal 状态
/goal <目标文字>          创建 goal
/goal edit <新目标文字>   编辑目标
/goal pause              暂停（+disarm）
/goal resume             恢复（+arm）
/goal clear              清除
```

- 人类侧**没有** `complete`/`blocked` 子命令——达成/阻塞只能由模型在自动
  续跑轮内报告；`clear` 是人类专属
- 挂载关系：`/goal` 命令和 goal 服务在 **host 平面**（一个实例服务所有会话），
  preset 只决定该会话的 agent 能否调用模型工具 `tool-goal`

## 6. 框架执行的行为清单

| 行为 | 说明 |
|---|---|
| 自动 arm / disarm | `create` 时 arm；`pause`、会话 resume/fork、各类终止事件 disarm。不存在"activate"工具 |
| 自动续跑轮 | `goal-round-driver` 在 `active + armed` 时于模型回合结束后自动发起下一轮，并向模型注入 `<goal_round>` 上下文（含目标、轮次 x/y） |
| 轮数计数与上限 | `roundsStarted` 由框架维护；达到 `maxGoalRounds` 后停止续跑，`resume` 也会被拒绝 |
| blocked 最小轮数 | `blockedAfterConsecutiveRounds`（默认 3，cordis.yml 可配），防模型一遇到困难就报阻塞 |
| authority 校验 | `create` 拒绝非人类/subagent；`update` 各 action 按上表强制 |
| 状态机与持久化 | 所有迁移走 durable snapshot；非法迁移（如 paused 直接 complete 之外的跳变）拒绝 |
| 收尾上下文注入 | `complete`/`blocked` 发生在续跑轮内时，框架自动 defer 一条 wrap-up 上下文，让模型写收尾消息 |

## 7. 本会话实测的完整生命周期（2026-08-28）

| 步骤 | 执行方 | 结果 |
|---|---|---|
| `create_goal`（`max_goal_rounds: 2`） | 模型（人类请求） | `goal-dd8ba634…`，revision 1，`active`，自动 `armed` |
| `get_goal` | 模型 | 拿到精确 id/revision |
| `update_goal(action: edit)` | 模型（人类请求） | revision 1→2 |
| `get_goal` + 汇报 | 模型 | 确认 revision 2，`active`，`armed` |
| **自动发起续跑第 1 轮**（`roundsStarted` 0→1） | **框架** | 无调用，`<goal_round>` 上下文注入 |
| `get_goal` 重新核验持久化状态 | 模型（续跑轮） | edit 后的 objective 已落盘，状态仍 `active`/`armed` |
| `update_goal(action: complete)` | 模型（续跑轮 authority 下合法） | revision 2→3，`phase: complete`，`disarmed` |

终态：`complete` + `disarmed`，无 paused/blocked 残留。

## 8. 实用建议与坑

1. **创建时给 `max_goal_rounds`**：测试性目标设小值（如 2），防止意外烧轮
2. **objective 写成可验证的成功判据**：它是跨轮的工作指令，续跑轮的模型按它
   判断"做没做完"，判据模糊会导致过早 complete 或空转
3. **会话中断后**：resume/fork 会自动 disarm——想继续就显式要求 `resume`
   （或对模型说"继续"，模型应调 `update_goal(resume)`）
4. **想中途停**：`/goal pause`（人类）或让模型 `pause`（人类请求驱动）；
   停了之后 `roundsStarted` 保留，`resume` 继续累计
5. **blocked 不是失败出口**：它要求同一具体阻塞条件连续 3 轮，并给出
   `blocked_reason`；困难、不确定、还有活干都不算 blocked
6. **complete 之后**：goal 可被新 create 替换（仅 complete 态可替换）
7. **验证优先于叙述**：续跑轮开始时框架会明确要求重新检查持久化状态再声称完成

## 9. 配置

preset 的 `cordis.yml` 中（模型工具条目）：

```yaml
- id: tool-goal
  name: '@deepseek-ai/dsh-tool-goal'
  config:
    blockedAfterConsecutiveRounds: 3   # 可选，默认 3，最小 1
```

`/goal` 命令与 goal 服务本身在 host 平面，preset 无需配置。

## 10. 源码与演进（仅供追溯，不构成文档承诺）

| 包 | 职责 |
|---|---|
| `packages/goal/goal` | 状态域：phase/activation、snapshot 持久化、迁移规则 |
| `packages/goal/tool-goal` | 模型工具（create/get/update）+ 轮数门槛配置 |
| `packages/goal/goal-round-driver` | 自动续跑轮驱动与 disarm 时机 |
| `packages/goal/command-goal` | 人类 `/goal` 命令 |

演进（git 实证）：

- **2026-07-19** 核心一天落地：持久化域（`a525776015`）→ 模型工具含全部
  5 action 与 blocked 门槛（`0129063ae7`）→ 续跑轮驱动（`25555c9cfc`）
- 2026-07-20 交互回合保持修复；08 月上旬事件签名统一、跨回合测试覆盖、
  TypeRT 网关示例；08-11 GUI 消息面上线；08-24 最小化禁用修复；
  08-25 API unary 域删除（简化）
- **2026-08-28** 随 `dsh 0.1.2-alpha.1` 发布（本文档实测版本）

仓库整体处于 pre-release 立场（第一个正式 tag 未出），goal 仍在活跃演进，
本文档的行为描述以 `0.1.2-alpha.1` 为准，升级后请重新核对。

## 11. 与 Codex `/goal` 的阶段词对照（2026-08-28 源码核实）

OpenAI Codex CLI 的 Goal mode（0.128.0 引入，2026-05 GA）与 DSH goal 是
**同构设计**：持久化目标 + 自动续跑 + 人类控制。两边共用的阶段词：
`active` / `paused` / `blocked` / `complete` —— **DSH 确实用了 `paused` 和
`blocked` 这两个词，与 Codex 逐字相同**。

Codex 侧的权威定义（openai/codex 开源仓库，2026-08-28 核实 main 分支
`codex-rs/state/src/model/thread_goal.rs`）：

```rust
pub enum ThreadGoalStatus {
    Active,          // "active"
    Paused,          // "paused"
    Blocked,         // "blocked"
    UsageLimited,    // "usage_limited"
    BudgetLimited,   // "budget_limited"（is_terminal）
    Complete,        // "complete"（is_terminal）
}
```

| | DSH `GoalPhase` | Codex `ThreadGoalStatus` |
|---|---|---|
| 阶段值 | 4：`active` `paused` `blocked` `complete` | 6：上述 4 个 + `usage_limited` `budget_limited` |
| 终态 | 仅 `complete` | `complete` 和 `budget_limited` |
| 预算耗尽 | **不是阶段**——轮数上限（`maxGoalRounds`）耗尽是一个阻止 resume 的门槛，goal 停在原 phase | **是阶段**——超预算直接把 status 写成 `budget_limited`（终态）/ `usage_limited`（非终态），且 goal 行自带 `token_budget`/`tokens_used`/`time_used_seconds` 字段 |
| 续跑开关 | 独立维度 `activation: armed/disarmed`（进程本地，与 phase 正交） | 未见独立维度——暂停/恢复直接体现在 status 列上 |
| blocked 门槛 | 有：连续轮数 < `blockedAfterConsecutiveRounds`（默认 3）时框架拒绝，且必须带结构化原因（code + message） | 未核实（status 只是一个枚举值，原因存储位置未查） |

差异小结：

- **词表**：两边 `active/paused/blocked/complete` 逐字一致；Codex 多出
  `usage_limited`/`budget_limited` 两个预算态
- **预算建模**：Codex 把"钱/用量用完了"建模成 goal 的一个**状态**（且预算
  是 token 维度、记录在 goal 行）；DSH 把"轮数用完了"建模成**恢复门槛**
  （预算是轮数维度、不是状态）
- **状态维度**：DSH 用 phase（持久）× activation（进程本地）两个维度表达
  "该不该自动续跑"；Codex 目前看是单状态列
- **注意**：2026-05 的早期中文报道只提到 `active/paused/complete` +
  `budget_limited`，当前 main 已是 6 值——`blocked`/`usage_limited` 是
  后期演进加入的（具体版本未核实）
