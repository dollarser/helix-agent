# ADR-AGENT-001: Turn 执行引擎、输入交付与恢复

Status: accepted
Date: 2026-09-25
HXA: HXA-011, HXA-039, HXA-214, HXA-215, HXA-216, HXA-220
Deciders: Project owner

## Context

Helix 的 Turn、ModelCall、ToolCall、用户输入、GoalRun 和外部副作用具有不同的持久化与执行生命周期。历史实现分阶段引入了批量 ToolCall、Queue/Steer、编辑重发、进程恢复和人工副作用核查，但这些决定曾分散在多个 Agent ADR 中，容易让不同入口分别解释“谁拥有 Turn”“什么时候可以继续”“UNKNOWN 是否可重试”。

Android 进程死亡还要求区分两类事实：数据库可以原子提交的内部状态，以及数据库事务无法回滚的外部副作用。ToolDispatcher 在真正调用 executor 前先持久化 ToolCall=RUNNING，因此进程死亡时 PENDING 可确定为未跨 execution-start，而 RUNNING 才可能已经产生外部效果。

本文件是 Agent 执行功能的当前唯一长期决策。历史 Agent 008（输入交付）、009（编辑重发）和 012（TurnEngine/副作用核查）的有效内容已经收敛到这里；旧编号只在 Git 历史和完成记录中作为当时的工作定位，不再维护独立当前文件。

## Decision

### 1. TurnEngine 是唯一 durable lifecycle owner

应用层使用一个 TurnEngine 作为 Turn 生命周期的唯一持久化 owner。TurnEngine 可以由多个协作者组成，但只有这一条生产路径能够决定：

- fresh Turn admission、requestId 幂等与 session blocker；
- Turn/ModelCall 的持久推进；
- cancel intent、terminal settlement 与 parked settlement；
- NEEDS_REVIEW 的人工核查、旧 Turn 关闭与 successor-Turn continuation 准入；
- startup recovery、INTERRUPTED 终局与 RecoverySummary 事实投影；
- durable runtime snapshot 与当前 Turn 的预算/用量记账。

ChatService 可以在迁移期保留 UI、draft/composer、流式 frame 和 AgentLoop 的 process-local driver，但不得直接写 Turn lifecycle。Room 是 durable truth；Job、Flow、cancel signal、startGate 只属于可丢的进程内执行句柄。

生产 fresh Turn 必须从 TurnAdmission 进入。GoalRunCoordinator 和 TurnCoordinator 可以作为 Engine 内部 collaborator，但其他入口不得直接调用它们创建 Turn。

### 2. 每个 Turn 保存 immutable execution snapshot

从 schema v30 起，新 Turn 与 `turn_runtime_records` 在同一 Room transaction 创建。记录至少保存：

- providerId、modelId、providerSnapshot；
- Agent mode、chatToolsEnabled、reasoning；
- 实际获准的 TurnBudgets 与 GoalBudgets；
- 当前 Turn 执行期间需要持久记账的 model-call/token/tool-round usage。

配置字段创建后不可改；usage checkpoint 只能单调推进并使用 CAS，stale owner 不能退款或重复消费。Goal Turn 存的是 Goal clamp 后的 effective budget。

**进程死亡后不使用这些 checkpoint 复活旧 Turn。** 旧 Turn 的 runtime record 保留为审计、诊断和用量事实；successor Turn 重新经过 admission，创建新的 runtime snapshot 和新的 Turn budget。Goal 的累计预算/用量继续跨 Run 计算，不因 crash 或新 Turn 退款。

历史 v29 及更早 Turn 没有 runtime record 时仍可显示、核查和作为 RecoverySummary 来源；successor Turn 使用自己新建的 snapshot，不从当前 Settings 猜造旧 Turn 的执行配置。迁移期间已有 review-resume receipt/checkpoint 字段可保留到调用图证明无用后再删，不能作为继续 same-Turn 的理由。
### 3. ToolCall batch 与模型历史按原调用顺序结算

一次模型响应中的 ToolCalls 是一个 batch。每个 call 独立持久化状态与结果；只有平台证明不冲突的只读调用可以有界并发，模型历史按原模型 call sequence 回填，不按执行完成顺序回填。

模型产生 TOOL_CALLS 后，整个 batch 必须先 durable settle。只有所有 call 的 outcome 都可确定时，才能追加该批 TOOL_RESULT 并创建下一 ModelCall。

重复 tool identity 在执行前拒绝。外部副作用不放进 Room transaction，事务失败不意味着外部 effect 回滚，也不能据此自动 replay。

### 4. UNKNOWN 是 effect uncertainty，不是普通失败

只有系统无法证明外部 effect“已发生”或“未发生”时才进入 UNKNOWN 语义。

- ToolCall live UNKNOWN -> NEEDS_REVIEW；
- process death 时 RUNNING ToolCall -> INTERRUPTED；
- process death 时 PENDING / AWAITING_APPROVAL -> CANCELLED + interrupted-before-execution result；
- COMPLETED/FAILED/DENIED/CANCELLED 保留原 executor 事实。

Turn 的恢复语义改为“**旧 attempt 关闭，不复活旧 Turn**”：

```text
live UNKNOWN: RUNNING_TOOL / CANCELLING -> NEEDS_REVIEW
review complete: NEEDS_REVIEW -> INTERRUPTED (or explicit CANCELLED discard)
process death: any other nonterminal Turn -> INTERRUPTED
INTERRUPTED: execution-terminal; never -> BUILDING_CONTEXT / WAITING_MODEL
```

`NEEDS_REVIEW` 表示旧 Turn 的执行已经停止、但 effect truth 仍需用户/系统核查；它不是可以恢复运行的 phase。`INTERRUPTED` 是一次 Turn execution attempt 的终局结果。再次进程死亡不得改变这些事实。

Stop、Goal deadline 或 approval cancellation 与 UNKNOWN 同时发生时，UNKNOWN 优先，不能被 clean cancellation 或 FAILED/INTERNAL 覆盖。

### 5. 人工 review 是不可变事实，不改写 executor state

`tool_call_reviews` 是 ToolCall 的不可变人工核查事实：

- CONFIRMED_APPLIED；
- CONFIRMED_NOT_APPLIED；
- ACKNOWLEDGED_UNKNOWN。

相同 decision 重驱幂等返回 first-writer durable row；不同 decision 返回稳定冲突。review 不能授权能力、扩大 scope、改写 ToolCall state 或把 ToolResult 伪造成成功/失败。原 ToolCall / ToolResult 永远保留 executor 当时的事实。

review **只解决/记录 effect truth，不恢复旧 Turn**：

- 全部 uncertain call 为 CONFIRMED_APPLIED / CONFIRMED_NOT_APPLIED：旧 Turn 从 NEEDS_REVIEW 关闭为 INTERRUPTED，记录稳定 review-resolved reason；
- 任一 ACKNOWLEDGED_UNKNOWN：旧 Turn 同样关闭，保留“用户已接受仍未知”的事实；
- 显式放弃旧 attempt 可以进入 CANCELLED，但不能把未知 effect 改写成“未发生”。

CONFIRMED_NOT_APPLIED 只表示用户确认原 effect 没发生；后续模型若要执行同类动作，必须在 successor Turn 产生**新的 ToolCall**并重新经过正常 policy/approval。

### 6. 中断后的 continuation 使用 successor Turn

Process death、live UNKNOWN review 或用户显式继续后，Helix 不重新打开旧 Turn。用户点击“继续上次任务”或发送新的用户消息时，创建新的 successor Turn。

successor admission 必须：

1. 记录稳定的 predecessor/recovery-from Turn identity（专用字段或等价 durable fact，不能只靠 UI label）；
2. 重新经过 request receipt、DurableSessionGate、Provider/model/run-control snapshot 与 fresh Turn budget；
3. 构建有界、模型可见的 `RecoverySummary`，至少包含 predecessor、已完成事实、未完成/中断事实、uncertain effects、review decisions、相关 Artifact/check；
4. 以新的 ModelCall 从当前 durable world 开始，不 backfill 成旧 ModelCall 的继续帧；
5. 原 ToolCall 永不自动 replay；同类动作必须是新的 ToolCall。

模型负责根据 RecoverySummary 和当前文件/测试/外部状态判断“还差什么”；Harness 不恢复旧 model step/tool round 来替模型决定工作流。

#### 未决 effect 不冻结整个 Session

历史 NEEDS_REVIEW / INTERRUPTED 本身不再阻止创建 successor Turn。若仍存在未解决 effect：

- 模型请求、历史检查和 read-only/inspection 工具可以继续；
- side-effecting ToolCall 在 effect review/state verification 完成前 fail closed；首版可以保守阻止该 successor Turn 的 side-effecting tool，而不是试图猜 effect overlap；
- ACKNOWLEDGED_UNKNOWN 允许用户继续，但可能重复原未知 effect 的新 ToolCall不能复用旧 approval/receipt，必须重新经过显式策略/审批；后续可在有可靠 effect-footprint 证据后细化为 overlap gate。

这保证 Session 可继续理解/检查当前世界，同时不会因为“开了新 Turn”就盲重放危险副作用。

### 7. Goal 是长期 intent，GoalRun 是一次执行 attempt

Goal 可以跨多个 Turn/Run 持久存在，但 crash/review 后不恢复 same open GoalRun。当前 Run 随旧 Turn 结束为 interrupted/blocked outcome；用户显式继续或合法的后继准入创建新的 GoalRun/Turn。

- runCount 按新 Run 增加；
- 已消费 Goal 累计 usage 不退款；
- successor Turn 获得新的 Turn budget，再受剩余 Goal budget clamp；
- review decision 成为新 Run 的 RecoverySummary 事实，不触发 `BLOCKED -> RUNNING` 的 same-run 特例；
- Goal 有 unresolved effect 时可保持 BLOCKED；核查完成后进入可重新准入的 PAUSED/READY 语义，再由新的 Run 继续。

### 8. session durable gate 与输入交付

没有 live Job 不等于可以绕过 Engine，但 gate 的职责是防止**多个 live owner**，不是永远冻结存在历史中断记录的 Session。

- exact clientRequestId dedup 在 blocker 前处理；原 accepted request 重驱返回原 Turn；
- 真正 live 的 nonterminal Turn 继续阻止第二 live Turn；
- INTERRUPTED 是旧 attempt 的终局，不阻止 successor admission；
- NEEDS_REVIEW 不阻止 successor Turn/模型检查，但 effect gate 按 §6 阻止危险 side effect；
- 普通用户发送默认 Queue；显式 Steer 只在当前仍 live 的 Turn 合法协调点进入；
- process recovery 不自动消费 Queue，也不自动启动 successor Turn。恢复必须来自用户“继续”/新消息，或未来另行接受的明确自动激活契约。

Queue、Goal continuation、revise、regenerate、Share/Voice 等 successor 最终都必须重新经过 TurnEngine admission，不能只看 process-local Job map。
### 9. REVISE 与 REGENERATE

最新用户消息可以在原会话修订重发；更早历史编辑应从修改位置 fork，而不是重写历史。

REGENERATE 必须显式携带 session、target assistant identity/version 与 clientRequestId。在同一 Room transaction 内：

1. re-read 并校验 target；
2. 校验 request fingerprint；
3. 处理 duplicate/conflict；
4. 创建 replacement Turn/runtime snapshot/首个 ModelCall；
5. 标记旧 answer superseded；
6. commit 后才启动 live loop。

transaction 失败时旧 answer 保持可见；不得先隐藏旧 answer 再尝试 submit。

### 10. receipt 与幂等

创建 Turn 的 SEND/REVISE/REGENERATE 使用 `TurnEntity.clientRequestId + inputFingerprint` 作为 durable accepted receipt。fingerprint 覆盖 intent、session、target identity/version、规范化输入身份和必要 immutable configuration identity。

- same requestId + same fingerprint -> 返回原实体；
- same requestId + different fingerprint -> REQUEST_ID_CONFLICT。

QUEUE/STEER 使用 SessionInputEntity.inputId。Tool effect review 使用独立 immutable review fact；successor Turn 使用自己的 submit receipt，不需要为 same-Turn next ModelCall 维护 review-resume receipt。InteractionReceiptEntity 只属于模型结构化提问，不复用。

### 11. cancellation 与 terminal settlement

TurnEngine 是 cancel 与 terminal 的唯一 durable 入口。

live Turn 先 durable 写 CANCELLING/输入停泊，再通知 process-local driver cancel。NEEDS_REVIEW 的“放弃/接受未知”通过 review/abandon command 关闭旧 attempt，不用普通 cancel 伪造 effect truth；INTERRUPTED 已是 execution-terminal，不再提供 resume/discard 两种互斥解释。

terminal settlement 原子提交最终 assistant、Turn terminal、当前 ModelCall terminal 和当前 GoalRun settlement。ChatService 只消费 settlement 结果更新 UI/frame/reminder/scheduling，不再次补写 durable terminal。

### 12. process-death recovery

startup recovery 只从 TurnEngine 进入并只读取 Room fact，不 replay 外部动作。

- terminal：不动；
- NEEDS_REVIEW：保持 review-pending、execution-stopped；
- 其他 nonterminal Turn：转 INTERRUPTED execution-terminal；
- RUNNING ModelCall：转 INTERRUPTED；
- 同一 Turn 的多个 RUNNING ToolCall 全部成为独立 uncertain identity；
- PENDING / AWAITING_APPROVAL ToolCall 确定性取消为 interrupted-before-execution；
- recovery 重复执行不产生新外部调用、不创建 successor Turn。

恢复完成后，Engine 只暴露“存在可继续的中断工作/未决 effect”事实。用户后续点击继续或发送新消息时才创建 successor Turn，并通过 RecoverySummary 让模型检查当前世界。旧 review transaction、旧 ModelCall identity、old Turn budget checkpoint 不用于恢复原 Turn。
### 13. ExecutionOwnership 与 timeout

Deadline Expired、Executor Exited、Effect Settled/Isolated 是不同事件。coroutine timeout/cancel 不能释放仍执行中的 effect ownership。

ordinary executor 的 permit 在实际 executor return 后才释放；retained PRoot/PTY owner 只有在明确 no-start proof 或 terminal reconciliation 后释放。超时后 executor 若仍运行，冲突资源的新操作继续被阻断。

### 14. Observation

Room 是 terminal/interrupted、review、receipt、runtime snapshot、successor relation 的权威。内存流可以提供 token/progress 增量，但 Activity recreate、晚订阅、process restart 必须可以只从 Room 读取：

- Turn terminal / NEEDS_REVIEW / INTERRUPTED；
- predecessor/successor continuation identity；
- unresolved/reviewed ToolCalls；
- Queue/Steer disposition；
- Goal state / Run outcome；
- submit/review receipt。

UI 不直接访问 DAO。

## Decision history

- **2026-09-22**：建立 batch-aware Turn coordination、确定性 ToolCall 顺序和 durable settlement（原 ADR-AGENT-001）。
- **2026-09-22**：统一普通发送 Queue、显式 Steer、统一 Stop 与 Goal 输入交付（当时单独记录为 Agent 008，现并入本文件）。
- **2026-09-22**：接受最新用户消息会话内修订重发；更早编辑使用 fork（当时单独记录为 Agent 009，现并入本文件）。
- **2026-09-25**：clean-slate Engine 复审后接受 TurnEngine single durable owner、NEEDS_REVIEW、人工 effect review、batch-aware recovery、runtime snapshot 与 ExecutionOwnership 边界（当时单独记录为 Agent 012，现并入本文件）。
- **2026-09-25**：HXA-220 实施中进一步收敛 v30 `turn_runtime_records`、DurableSessionGate、Engine-owned cancel/park/terminal/recovery。
- **2026-09-25**：结合 Codex/Claude/OpenCode/DeepSeek Harness 与移动端竞品复核，**废止 same-Turn/same-GoalRun crash resume 方向**；采用 old Turn execution-terminal + successor-Turn continuation + RecoverySummary。effect review、execution-start boundary、request idempotency 和 ExecutionOwnership 保留。
- **2026-09-26**：项目尚未正式上线，R1-F 采用 clean-slate cutover：当前 `turn_runtime_records` 删除 same-Turn review receipt/old ModelCall identity；Turn 级 review 命令幂等独立到 immutable `turn_review_receipts`，逐 ToolCall effect truth 仍在 `tool_call_reviews`，不保留 `RESUMED/ABANDONED` production compatibility。

## Alternatives considered

- 用 generic FAILED/INTERNAL 表示 UNKNOWN：会允许错误 retry，并丢失 effect uncertainty，拒绝。
- process death 后自动 replay RUNNING ToolCall：外部 effect 可能已发生，拒绝。
- PENDING 也视为 UNKNOWN：execution-start hook 已证明 executor 尚未进入，确定性取消。
- review 后把原 ToolCall 改成 COMPLETED/FAILED：会篡改 executor fact，拒绝。
- **same-Turn rehydrate / same-open-GoalRun resume**：曾实现并通过主机验证，但要求恢复旧 ModelCall、tool-round、budget checkpoint、review receipt 和 same-run 状态，复杂度高且与主流 Session-continuation Harness 不一致；现改为 successor Turn。
- process death 后完全丢弃历史、只靠模型自由猜：会丢失 effect uncertainty 和可审计事实，拒绝；采用 RecoverySummary。
- unresolved effect 阻塞整个 Session：安全但过重；改为允许 successor Turn/inspection、阻止 side-effecting call。
- 只靠 process-local Job map 做 admission：process death 后失真，拒绝。
- 用 audit JSON 作为执行事实源：audit 是诊断数据，不升级为生命周期 source of truth。
- 一次性把 ChatService 全部搬进 Engine：扩大耦合与迁移风险，采用 durable authority → live registry → driver/observation 分阶段收敛。
- 所有 ToolCall 强制串行：不能替代正确 identity/settlement/recovery，只损失安全读并发。
## Consequences

TurnEngine 仍是唯一 durable owner，UNKNOWN、取消、恢复、Queue、Goal 和 review 不再由不同入口各自解释；但恢复模型从“复活 old Turn”简化为“关闭 old attempt + 新 Turn 继续”。

`turn_runtime_records` 只保留 immutable execution config 与 live usage/audit checkpoint；same-Turn review receipt、next ModelCall rehydrate、old Turn budget restore 和 same-run Goal review 已从当前生产模型删除。Turn 级 review 命令幂等由独立 `turn_review_receipts` 承担，逐 ToolCall effect truth 由 `tool_call_reviews` 承担。新增成本是 successor/predecessor identity、RecoverySummary 和 unresolved-effect side-effect gate；这些事实比恢复旧 control state 更直接可解释。

迁移期 ChatService 仍可以作为 live AgentLoop driver，但不得绕过 Engine 写 durable lifecycle。后续 HXA 继续把 Job registry、cancel/timer、AgentLoop 和 TurnLiveFrames 收进 Engine，不因恢复策略变化回退 single-owner 方向。

## Verification

当前实现迁移与交付证据以 HXA-220 为准。新恢复模型至少覆盖：

- process death 下多 RUNNING、RUNNING+PENDING/AWAITING_APPROVAL；
- old Turn 只进入 NEEDS_REVIEW/INTERRUPTED/CANCELLED，不再转回 BUILDING_CONTEXT；
- review 不改写原 ToolCall/ToolResult，也不创建 old Turn 的下一 ModelCall；
- successor Turn 有 durable predecessor identity 和 bounded RecoverySummary；
- successor 使用新 runtime snapshot / Turn budget，Goal 累计 usage 不退款；
- unresolved effect 时模型/inspection 可继续、side-effecting ToolCall fail closed；
- ACKNOWLEDGED_UNKNOWN 后重复危险 effect 不复用旧 approval/receipt；
- exact submit dedup、Queue/Steer、regenerate 原子性保持；
- timeout executor 未退出时 ownership 不释放；
- late observer / Activity recreate 从 Room 重建中断/Review/Successor 事实；
- v30→v31 cutover 物理删除 runtime same-Turn receipt 字段；当前 schema 不再表达 old ModelCall review resume。

AI 代理执行 host tests、静态检查和 AndroidTest APK 编译；模拟器/真机验证仅在项目所有者对当前任务明确要求时执行。未要求时记 `not requested`，已要求但未完成时记 `pending`。
## Reconsider when

如果 Provider/执行域提供真正可恢复的 server-side Turn identity、exactly-once effect ledger，或真实任务评估证明 successor Turn 明显劣于 same-Turn continuation，可重新评审恢复边界。跨设备/远端 worker 也需要重新评审 owner 和 recovery，但普通实现修复/内部类拆分不另建 Agent execution ADR。

## References

- [Agent 主题入口](README.md)
- [Goal 生命周期](../goal/001-lifecycle-and-completion.md)
- [Runtime 执行域](../runtime/001-execution-domains.md)
- [权限与审计](../permissions/README.md)
- [实施状态](../../development/status.md)
- [HXA-220](../../development/tasks/HXA-220.md)
