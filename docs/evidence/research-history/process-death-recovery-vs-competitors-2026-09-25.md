# 端侧 Agent 的进程死亡恢复：Helix 的设计依据与竞品对照

**日期**：2026-09-25
**基线**：Helix HEAD `43e8aef6`；当前执行决策已收敛到 `ADR-AGENT-001`，其 `Decision history` 记录 2026-09-25 的 TurnEngine/review/recovery 更新
**问题**：Helix 为什么要在"App 被杀如何恢复 turn"上投入这么多？竞品有这种设计吗？能不能只提供一个"开启新 turn"的开关，或让用户发消息开新 turn？大模型自己会回顾之前的工作是否结束吧？现在 harness 应该都以"浅 harness"为主——提供工具 + 权限控制，工作流交给模型自己。

---

## 〇、结论摘要

| 你的判断 | 裁定 |
|---|---|
| 主流 harness 以"浅"为主，只给工具 + 权限，工作流交给模型 | **对，但只在"工作流编排"这一轴上成立。** 在"副作用真相"轴上，主流 harness 几乎全部是空的 |
| 提供一个"开启新 turn"的开关就够了 | **部分已存在**（`ACKNOWLEDGED_UNKNOWN`）。但它不能是静默的——必须先让用户声明"那个副作用发生了没有" |
| 让用户发消息开新 turn 就行 | **这正是 Claude Code 的做法，而且它自己承认这是个缺口** |
| 大模型会主动回顾之前的工作有没有结束 | **做不到。** 因为这个事实从来不在它的上下文里，它是"猜"而不是"知道"，猜错 = 重复副作用 |
| Helix 做了很多工作 | **方向正确，但对全部 UNKNOWN 一视同仁是过度应用。** 本文给三条收窄建议 |

**一句话**：Helix 的重投入不是"理念上相信重 harness"，而是**手机平台的失败模型逼出来的**——被杀是常态、被杀时机经常正好在工具执行中、用户还看不见。这套机制在桌面 CLI 上可以省，在手机上省不掉。

---

## 一、先拆开一个被混在一起的问题

"App 被杀后怎么办"其实是**两个完全不同的问题**，它们只是共享同一个触发事件：

| | **A. 上下文重建** | **B. 副作用真相** |
|---|---|---|
| 问的是什么 | 进程死了，模型下一轮该看到什么？ | 进程死在工具执行中，那个副作用到底发生了没有？ |
| 事实在哪 | 在持久化的会话记录里（可重建） | **只在持久化的执行边界里**（不可从会话记录推出） |
| 主流 harness | ✅ 全都做，而且有的做得很重 | ❌ 几乎全都不做 |
| 猜错的后果 | 上下文偏移、重复注入、prompt cache 失效 | **重复副作用**（重删、重发、重付、对外系统收到两次） |

**Helix 让人感觉"做了很多工作"的部分，绝大多数是 B，不是 A。**

A 是"续聊天"——Codex、Claude Code、opencode 都做，且 Codex 做得比 Helix 重得多。B 是"对账"——本文调研的五个竞品里没有一个把它做完整。

---

## 二、Helix 到底在做什么

### 2.1 核心是那条"执行开始边界"

当前 `ADR-AGENT-001` 的 UNKNOWN/recovery 决策记录了一个已生产验证的机制：

> `ToolDispatcher` 在调用 executor 之前同步执行 `onExecutionStarting`；持久层在该 hook 中先把 ToolCall 从 `PENDING` 写为 `RUNNING`。**hook 写入失败会阻止 executor 进入。** 因此进程死亡时，`PENDING` 可以确定为"未跨过执行开始边界"，而 `RUNNING` 才表示外部效果可能已经发生。

这一条是整个设计的支点。它的关键性质是：**边界必须在副作用发生之前落盘**，所以它无法被"事后让模型回顾"替代——事后回顾时，边界信息已经丢了。

同一 ADR 的配套不变量是：
> 无法证明未发生副作用的执行契约异常进入 `NEEDS_REVIEW`。Repository 拒绝非法跳转，进程死亡使用明确的 interrupted 结算入口，**不另建串行兼容 reducer 决定生产调度**。

### 2.2 恢复的动作很小：不重放，只停泊

当前 `ADR-AGENT-001` 的 process-death recovery 规则很短：

| ToolCall durable state | 解释 | recovery |
|---|---|---|
| `PENDING` | 未跨过执行开始边界，executor 未进入 | `CANCELLED`，写确定的 cancelled-before-start |
| `AWAITING_APPROVAL` | 未执行 | 保持 |
| `RUNNING` | 外部效果**可能**已发生 | `INTERRUPTED`，加入 uncertain calls |
| `NEEDS_REVIEW` / `INTERRUPTED` | 已停泊 | 保持 |
| terminal | 已结算 | 保持 |

两个硬约束：
- **`RUNNING` 不自动 replay**（"恢复只读取 Room 事实，不根据旧进程内存猜测"）
- **同一 Turn 可以有多个 RUNNING，全部都必须成为独立 uncertain identity**（这正是此前 P0 缺陷的修复目标）

### 2.3 然后交给人，但只是一次"事实声明"

§4 明确 review **不是权限审批**："review 不能授权新能力、扩大 scope、消费 approval proof 或修改 session permission。" 首期只有三个 decision：

- `CONFIRMED_APPLIED` —— 效果确实发生了
- `CONFIRMED_NOT_APPLIED` —— 效果确实没发生
- `ACKNOWLEDGED_UNKNOWN` —— 无法确定，**明确放弃当前 Turn**

**第三个就是你说的那个开关。** 它已经在设计里了（§5：`ACKNOWLEDGED_UNKNOWN` → Turn 转 `CANCELLED`，保留稳定 finish/audit reason，session slot 释放，但 Queue 输入不自动执行）。

而 §5 的另一半是：前两个 decision 齐了，**继续原 Turn**（不新建），但**绝不重放原 ToolCall**；模型看到的是一条"用户核查事实"的 resolution，而不是伪造的 tool_result。这解决了你说的"让用户发消息开新 turn"——Helix 的做法是**继续原 turn 但注入事实**，比开新 turn 保留了 budget、steering 和因果边界（§Alternatives 明确拒绝了"所有 UNKNOWN 都创建新 Turn"）。

---

## 三、你的三个方案逐条评估

### 3.1 "让用户发消息开启新 turn" —— Claude Code 就是这么做的

这是本文最值得看的一条。Claude Code 的源码级分析（`deserializeMessagesWithInterruptDetection`）：

> 它会先迁移 legacy attachment type，剔除无效 permission mode；**再过滤未解决的 tool_use**、thinking-only assistant message、只有空白文本的 assistant message。如果检测到中断中的 turn，它会追加一个 meta user message：**`Continue from where you left off.`**

也就是说：**丢掉那个悬空的工具调用，注入一句"接着干"，让模型自己看着办。** 而那篇分析自己承认了缺口：

> 这一步修复的是**消息协议形状**，不会恢复被中断进程的执行栈，也不会在反序列化时重新执行工具。**比如 Bash 已经修改文件但 tool_result 尚未落盘，清理孤立 tool_use 并不撤销磁盘上的修改；后续继续任务前仍需重新检查实际文件和任务状态。**

**所以你的方案是真实存在的成熟设计，Claude Code 在用。** 它成立的前提见 §五——桌面能省，手机省不掉。

### 3.2 "大模型会主动回顾之前的工作有没有结束" —— 这里有个硬约束

**模型无法回顾出它不知道的事。**

进程死在工具执行中时，持久历史里的形状是：
```
function_call / tool_use          ← 有（已发出）
tool_result / function_call_output ← 无（从未产生）
```

模型看到的是一个**悬空的调用**。它可以：
- 猜"应该成功了吧" → 若实际没发生，任务静默不完整
- 猜"应该失败了吧" → 若实际发生了，**重复执行**

**猜错的代价不对称**：第二种是重复副作用，可能不可逆。

而"让模型用工具去验证"只在**可验证的效果**上成立：
- ✅ 文件写没写 → `read` 一下就知道
- ✅ 本地状态变没变 → 查一下
- ❌ **MCP 服务端有没有收到我的请求？**
- ❌ **PRoot 里的命令跑完了没有？**
- ❌ **下载在服务端完成了没有？**
- ❌ 支付/发消息这类外部动作

对第二类，模型没有任何可靠手段，只能猜。这就是 B 类机制存在的唯一理由。

### 3.3 "浅 harness 是主流" —— 对，但要看是哪一轴

你的描述（提供足够多的工具 + 合理的权限控制，工作流交给模型）**准确地描述了主流 harness 在"工作流编排"轴上的姿态**。ADR-AGENT-001 的当前方向与之一致：它不是在规定模型怎么干活，而是在收敛**状态解释**——唯一 durable owner 可以由多个协作者组成，后续 HXA-220 负责删除旧的第二状态解释和无用 adapters。

但"浅 harness"这个词容易掩盖一件事：**harness 可以在 A 轴上极重、在 B 轴上为零**。Codex 就是典型（见 §四）。所以"浅/重"不是一个标量，至少要分两轴看。

---

## 四、竞品对照

| | **A. 上下文/历史持久化** | **B. 工具执行状态持久化** | **C. 副作用对账** |
|---|---|---|---|
| **Claude Code** | ✅ transcript JSONL，且附属状态很全：file history snapshot、content replacement、worktree、agent definition、cost state、session metadata；resume 是"重建运行时状态"而非"续聊天" | ❌ | ❌ **明确不做**（过滤悬空 tool_use + 注入 `Continue from where you left off.`，并把"不撤销磁盘修改"写进分析） |
| **Codex CLI** | ✅✅ **最重**。rollout = append-only typed JSONL（`SessionMeta`/`ResponseItem`/`CompactedItem`/`TurnContextItem`/`EventMsg`/`WorldState`/`TokenUsageRecord`/`RetainedContext`）；四层恢复（客户端事件/模型历史/上下文基线/thread 身份）；`reconstruct_history_from_rollout` **倒序找最新幸存 checkpoint → 正序 replay** | ❌ 该源码分析中未见 | ❌ 未见 |
| **opencode** | ✅ SQLite（`Session`/`Message`/`Part`）+ `SessionRevert` 走**文件系统快照**回滚；`SessionRunState` 做"session 不忙"并发控制 | ❌ | ❌ **靠第三方插件补**：`opencode-antigravity-auth` 用"synthetic 消息"修复中断的工具调用；`opencode-auto-resume` 检测 "stalls, broken tool calls" |
| **DSH（DeepSeek Harness）** | ✅ Append-only Event Log（含 reasoning、tool calls、tool outputs、context injections、sub-agent scheduling），支持 Resume / Search / Fork / Replay | **部分**——`Agent = Model + Harness`，明确把"**状态恢复、错误自愈**"列为 Harness 职责 | 未明确（资料未涉及效果对账） |
| **Operit** | 有本地存储（聊天 / 角色 / 记忆图谱 + 混合检索） | ❌ 无公开证据 | ❌ 无公开证据 |
| **Helix** | ✅ Room，Turn / ModelCall / ToolCall **分粒度** | ✅ **PENDING/RUNNING execution-start 边界** | ✅ **`NEEDS_REVIEW` + `tool_call_reviews` 表 + 三个 decision** |

### 4.1 三条值得单独说的

**① Claude Code 是决定性反例，也是决定性证据。**
它同时是"重 harness"（恢复 file history / content replacement / worktree / agent / cost，比 Helix 做得多）和"B 轴为零"（明确不碰副作用真相）。**这证明 A 和 B 是正交的**——不是"重 harness 自然就会做 B"，Claude Code 把 A 做得很重却完全不做 B。Helix 的投入落在一条别人没占的轴上。

**② opencode 说明"核心浅 ⇒ 恢复变成插件补丁"。**
核心不做，于是社区写了 `opencode-antigravity-auth`（注入 synthetic 消息修复中断的工具调用）和 `opencode-auto-resume`（检测 broken tool calls）。**当核心不提供结构保证，恢复能力就退化成生态补丁**——质量、覆盖面和一致性都无法保证。

**③ Codex 是"历史极重、效果为零"，而且它的持久化承诺有明确边界。**
rollout 的写入纪律是 `record_canonical_items → queue`、`persist() → materialize`、`flush() → wait for file writes`、`shutdown() → final drain`，但源码分析特别提醒：**`flush()` 之后没有 `fsync`/`sync_all`**——

> 不要把"已接受入队""已完成文件写入"和"断电后保证稳定介质持久性"视为同一承诺。

这条值得 Helix 对照自查：Helix 的 `PENDING → RUNNING` 写入是否依赖了超出实际保证的持久性？

**④ DSH 是唯一明确把"状态恢复、错误自愈"写进 Harness 职责的**，而且它的权限模型与 Helix 惊人地像：
- 两个独立旋钮：沙箱档位（`read-only` / `workspace-write` / `danger-full-access`）× 审批策略（`ask` / `never`）
- **fail-closed**："审批链无应答者、应答者抛异常或返回值不合规时，结果为 `unavailable`，一律按拒绝处理，绝不静默放行"
- 甚至也有 Turn 边界约束："**Fork 只能在'稳定边界'上切分（所选前缀必须结束在一个完整的 Turn 之外）**" —— 和 Helix 的 Turn 边界是同一个问题

**所以"端侧 agent 都做浅 harness"不成立。** DSH 是明确的重 harness，只是它的重放在 A 轴和治理上。

**⑤ Operit 能力面宽但没有 B 轴证据。**
UI 自动化三条通道（无障碍 / Shizuku / Root）、PRoot Ubuntu 24.04、图谱记忆、ToolPkg/MCP/Skill 统一市场、权限三档（自动允许 / 每次询问 / 禁止，默认询问）。但公开资料里**没有**工具执行状态持久化或副作用对账的描述。它的长任务（PRoot 终端、UI 自动化）同样面临被杀风险，公开信息未见对应机制。

---

## 五、真正的分水岭：不是"深浅"，是**失败模型**

这是本文最重要的一节。**桌面 CLI 可以省掉 B，是因为它们的失败模型和手机不一样：**

| | 桌面 CLI agent | Android 端侧 agent |
|---|---|---|
| 进程被杀 | 少见（用户主动关、崩溃） | **常态**：LMK、Doze、厂商杀后台，且**用户看不见** |
| 被杀时机 | 多在等用户输入 / 等模型响应 | **经常正好在工具执行中** |
| 工具时长 | 通常秒级 | PRoot 任务 / 下载 / MCP 外呼可达**分钟级** |
| "死在效果中间"的窗口 | 窄 | **宽** |
| 用户能否察觉 | 能（终端还在、看到报错、看到 exit code） | **不能**（App 回来像没事一样） |
| 会话生命周期 | 用户显式 `--resume` | 系统随时杀，用户无感 |

**推论**：
- Claude Code 的"丢悬空 tool_use + 注入 Continue"在桌面够用：被杀少见、窗口窄、**用户在场且知道发生过中断**。
- **同样的设计搬到手机上会系统性失效**：被杀是常态、窗口宽、**用户不知情**，而"让模型自己核对"对不可验证的效果无效。

这也解释了为什么 ADR-AGENT-001 的措辞是**条件性**的而不是教条的：

> 若平台能提供强于当前 execution-start + reconciliation 的外部事务/幂等保证，可以减少人工 review。

它不是"我认为 harness 应该重"，而是"在这个平台上，没有更强的保证可用"。

---

## 六、Helix 确实过度的地方：三条收窄建议

方向正确 ≠ 没有过度。以下三条我认为是可以立刻收窄的。

### 6.1 没有按"效果可验证性 / 幂等性"分级（**最高性价比**）

`ToolDescriptor` **已经有 `Idempotency` 字段**（例：`BrowserScreenshotTool` 声明 `Idempotency.NON_IDEMPOTENT`）。但 ADR-AGENT-001 的 parked + 人工核查机制目前对**所有** UNKNOWN 使用统一 fail-closed 基线。

> **已核实（2026-09-25 回源码逐点核对，比初稿更严重）**：`Idempotency` 目前在生产**运行时决策中零消费**——它唯一的生产读取点是 `ToolDescriptor.kt:201`，即把它折叠进 `contractHash` 做安全契约绑定；**`ToolDispatcher.kt` 全文件不含 `idempotency` 这个标识符**。重试门禁的真实判定在 `ToolDispatcher.kt:306-309`：
> ```kotlin
> outcome is ToolDispatchOutcome.ExecutionFailed &&
> outcome.sideEffectFree &&
> attempt < request.maxAttempts
> ```
> 只读执行器上报的 `sideEffectFree` 与 `maxAttempts`，不读静态幂等类别。
>
> 而 `Idempotency.kt:7-13` 的 KDoc 却写着它参与 dispatcher 的重试门禁（"the dispatcher's bounded technical-retry gate additionally requires…"）——**枚举自述与代码不一致**。
>
> 附带发现：`maxAttempts` 的唯一生产构造点是 `ChatDispatchRequests.kt:76-105`，未设置该字段 → 恒为默认值 `1`；全仓无生产侧 `.copy(maxAttempts = 2)`（仅 `ToolSchedulerTest.kt:324`、`ToolDispatcherTest.kt` 多处测试设置）。**因此 HXA-037 的"有界技术重试"在生产中是死代码**，而 `ToolScheduler.kt:142` 只补 `queuedAt`。
>
> 结论修正：我初稿说"主要缺的是恢复路径去消费它"**低估了**。真实情况是**没有任何运行时决策消费它**——所以这不是"改一处"，而是要先裁定"谁读它、读了之后行为怎么变"。两件本可以**避免**停泊 UNKNOWN 的机制（幂等分级、有界重试）目前**同时处于未接线状态**。

合理的分级：

| 效果类型 | 例子 | 建议处理 |
|---|---|---|
| 幂等 + 模型可自验 | 读文件、查本地状态、幂等写 | **不停泊**，注入事实后让模型继续 |
| 非幂等 + 模型可验证 | 普通文件写入 | 停泊但给模型一次自验机会 |
| 非幂等 + **不可验证** | MCP 外呼、PRoot 长任务、对外发送、支付 | **停泊 + 人工核查**（当前行为） |

这能把人工核查的触发面从"全部 UNKNOWN"缩到真正危险的一小撮。字段已在（`ToolDescriptor.idempotency`，且每个工具都已声明），**但没有任何运行时决策读它**——所以第一步不是"加分级逻辑"，而是先裁定"谁读它"（见上方已核实块）。

### 6.2 "放弃当前 turn"的入口要做显眼

`ACKNOWLEDGED_UNKNOWN` 已存在，但它在 §5 的语义是"无法确定 + 明确放弃"，容易埋在核查流程里。**你说的那个开关应该做成一眼可见的动作**："我不知道 → 放弃这个 turn 重新开始"，而不是要求用户先走完核查。

理由：核查是给"能确定"的场景用的；用户**确实不知道**时，逼他做三选一里的第三项会让人以为前两项是必填。让"放弃"成为一等公民，比让用户猜要诚实。

### 6.3 补一条量化证据再决定简化多少

现在**没人测过**"手机被杀时正处在工具执行中的实际频率"。如果实测发现绝大多数进程死亡发生在"等模型响应"或"等用户输入"时（而非工具执行中），那 B 类机制的触发面本来就很小，可以进一步简化。

**但顺序是先测再简化，不要凭直觉砍。** 依据：本文 §五 的推论是定性的，缺数据。建议用一条 androidTest 或 evals 度量：`SIGKILL 时机分布 × 工具时长分布 × UNKNOWN 实际发生率`。

### 6.4 不建议做的事

**不要**照搬 Claude Code 的"过滤悬空 tool_use + `Continue from where you left off.`"。在手机上这会**静默丢失**"某个副作用可能已发生"这个事实，而手机恰恰是最容易发生这种情况的平台。可以借鉴它的**注入合成消息让模型继续**的形式，但不能丢掉执行边界这个事实源。

---

## 七、证据与置信度

| 结论 | 证据 | 置信度 |
|---|---|---|
| Helix 的执行开始边界（PENDING/RUNNING）是恢复设计支点 | `docs/adr/agent/001-turn-coordination.md` 的 UNKNOWN / process-death recovery 决策与生产 ToolDispatcher 边界 | **高** |
| Helix 的"放弃 turn"开关已存在 | ADR-AGENT-001：`ACKNOWLEDGED_UNKNOWN` -> `CANCELLED`，且不自动消费 Queue | **高** |
| Helix 未按幂等性分级；`Idempotency` 在生产运行时**零消费** | 全仓 `idempotency` 检索：唯一生产读取点 `ToolDescriptor.kt:201`（折叠进 `contractHash`）；`ToolDispatcher.kt` 全文件无该标识符；重试判定在 `ToolDispatcher.kt:306-309` 只读 `sideEffectFree` + `maxAttempts` | **高**——已回源码逐点核对（2026-09-25） |
| `Idempotency.kt` 的 KDoc 与代码不一致 | `Idempotency.kt:7-13` 声称参与 dispatcher 重试门禁；`ToolDispatcher.kt` 无任何引用 | **高** |
| HXA-037 有界技术重试在生产未启用 | 唯一生产构造点 `ChatDispatchRequests.kt:76-105` 未设 `maxAttempts`（默认 1）；全仓无生产 `.copy(maxAttempts = 2)`；`ToolScheduler.kt:142` 仅补 `queuedAt` | **高** |
| Claude Code 过滤悬空 tool_use + 注入 `Continue from where you left off.`，且明确不撤销磁盘修改 | 源码级分析（基于 2026-03-31 公开镜像 `5a774a2b`），引 `conversationRecovery.ts:154-245` | **中高**——非 Anthropic 官方仓库，但逐函数引用源码 |
| Codex rollout 四层恢复、倒序找 checkpoint + 正序 replay、`flush()` 不含 `fsync` | 源码级分析（基于 `openai/codex` `5c5308fc`），引 `history/src/lib.rs`、`rollout_reconstruction.rs`、`rollout/src/recorder.rs` | **中高**——引用官方开源仓库具体行号 |
| opencode 核心不做工具中断恢复，靠第三方插件 | `opencode-antigravity-auth` session-recovery 文档、`Mte90/opencode-auto-resume`；`SessionRevert` 走文件快照 | **中高** |
| DSH 明确把"状态恢复、错误自愈"列为 Harness 职责；权限模型 fail-closed；Fork 需稳定边界 | DeepSeek Harness 架构解析（`Agent = Model + Harness`，Cordis 插件框架，v0.1 rc） | **中**——第三方解析文章，未核对官方源码 |
| Operit 无 B 轴机制证据 | 公开资料（GitHub README、Hysen Labs 评测、KB 条目）均未涉及执行状态持久化/对账 | **中**——"无公开证据"不等于"不存在"，未读其源码 |
| Claude Code 存在中断工具调用导致会话损坏的已知 bug | anthropics/claude-code issue #3003 | **中**——未核对当前是否已修 |

**未核实项（建议后续）**：
1. ~~Helix 恢复路径是否已消费 `ToolDescriptor.Idempotency`~~ → **已核实（2026-09-25）：零消费，见 §6.1 与上表**
2. Codex 对"`function_call` 无对应 output"在 resume 时的实际处理（本文引用的源码分析未覆盖该分支）
3. Operit 源码中是否存在会话/任务恢复实现
4. Helix 的 `PENDING → RUNNING` 写入是否依赖超出平台实际提供的持久性保证（对照 §4.1③ 的 Codex 教训）

---

## 八、与当前实现工作的关系

本文只解释**为什么端侧 Agent 需要把“上下文恢复”与“外部副作用真相”分开处理**，不是当前任务规格。2026-09-25 同日产生的 Wave/playbook/handoff 文档已经完成使命，其有效结论已收敛到两个长期入口：

- [ADR-AGENT-001](../../adr/agent/001-turn-coordination.md)：TurnEngine、UNKNOWN/review、process-death recovery、runtime snapshot、Queue/Steer、same-Turn resume 与 ExecutionOwnership 的当前长期契约；
- [HXA-220 交付记录](../../completion-records/HXA-220.md)：上述契约的实现结果与主机验收证据。

原临时实现/交接文档已删除，逐字历史可从 Git 获取；不要再从历史 Wave 卡片恢复已被当前 ADR/HXA 改写的顺序或结论。

本文 §6 的幂等分级、放弃入口可见性和“先量化再简化”仍作为研究建议。是否进入产品实现由当前 ADR/HXA 另行收敛；研究建议本身不自动扩 HXA-220 范围。

---

*本文的 Helix 侧结论来自本仓 ADR 与源码；竞品侧来自公开文档与源码级分析文章，已逐条标注置信度。本文是研究材料，不是当前实现授权。*
