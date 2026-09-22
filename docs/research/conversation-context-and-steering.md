# 对话历史、模型上下文与运行中用户干预

核验日期：2026-09-22。性质：源码核验、官方产品对比与优化设计，尚未实现的部分均为建议，不作为验收结论或自动改变现有 ADR。

源码快照：当前应用工作分支 `codex/marketplace-catalog` 的 `d5dc51ac`；另核对 `codex/session-fork` 的 `98b904b5`（包含压缩优化 `5c2a11df`）。核验时本地 main 为 `c36d556b`，fork 尚未进入 main。本页源码链接以本文所在 fork 分支为准，共有路径已交叉核对；不推断真机安装版本。

## 1. 结论

Helix 已将用户界面与模型请求分开投影，但没有各保存一份完整独立对话。持久消息及执行记录是事实源，UI 选择展示，模型在每次请求前重新组装上下文。这个方向合理，应完善可观察性而不是复制第二套可变历史。

目前有停止回复，没有通用的已发送消息编辑重发、普通输入队列或同 Turn 引导。Goal 有人工输入让位的应用服务路径，但聊天输入框在运行中禁用，不能把这个内部路径算成完整的运行中输入产品能力。fork 提供了编辑重发的基础，但当前只复制至指定消息，不支持替换这条消息。

优先顺序：发送回执与统一停止 → 基于 fork 的编辑重发 → 持久待输入与工具批边界引导 → 请求上下文清单。它们是应用/引擎通用能力，不新增 Agent Tool、专用规划器或业务状态机。

## 2. 实际保存了什么

| 层次 | 当前事实 | 不应混淆 |
| --- | --- | --- |
| 消息历史 | `messages` 保存 USER/ASSISTANT 文本、工具协议行、压缩记录；正文通过 ContentRef 引用 | 不是只有聊天气泡 |
| 执行事实 | Turn、ModelCall、ToolCall、ToolResult、审批、用量及审计各有持久记录 | 不是所有记录都发给模型 |
| UI 投影 | `ChatScreenProjection.messagesFor` 过滤工具协议和压缩记录；工具走时间线；流式状态另行叠加 | UI 折叠不改变模型输入；流式可见也不证明已持久化 |
| 模型上下文 | `ChatRequestAssembler` 选择压缩后的历史，恢复工具协议和附件，加入系统提示及工具 schema | 不等于屏幕全文，也不是整个数据库 |
| 请求证据 | ModelCall 有 providerSnapshot、usage、requestId、promptFingerprint、promptSections | 没有逐次完整 wire request 快照；prompt 指纹不是整个请求的指纹 |
| JSONL 导出 | 既有 HXA-211 导出会话执行记录和引用 | 不保证可以逐字节重放当年的 Provider 请求 |

源码入口：[UI 投影](../../app/src/main/kotlin/com/helix/app/chat/ChatScreenProjection.kt)、[历史协议映射](../../app/src/main/kotlin/com/helix/app/agent/ChatHistoryBuilder.kt)、[请求组装](../../app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt)、[请求对象](../../app/src/main/kotlin/com/helix/app/agent/ChatContextRequest.kt)、[ModelCall 字段](../../core/storage/src/main/kotlin/com/helix/core/storage/entity/ConversationEntities.kt)。

具体例子：原来界面中出现的 `{"id":...,"tool":...,"status":...,"summary":...}` 是持久工具结果封套。模型映射将其恢复为 TOOL 消息，携带调用 ID、工具名和 `[状态] 结果正文`，并非一定原样发送整个封套。UI 可以仅显示折叠卡，模型仍能读取有界结果。完整大输出是否可见，取决于工具结果投影、引用及后续读取工具，不能把“工具运行成功”解释为“全部原始日志进入上下文”。

压缩成功后，旧聊天仍可展示，但请求改用摘要、保留消息和后续历史。系统提示、工具目录和部分环境信息不需要作为普通聊天气泡展示。fork 的来源元数据不进模型，而继承的有效历史可继续参与压缩。

## 3. 编辑、停止与转向的真实现状

| 操作 | 核验结果 |
| --- | --- |
| 修改尚未发送的草稿 | 支持；运行中输入框禁用 |
| 编辑已发送 USER 消息并重新生成 | 没有入口；MessageRow 只有复制，fork 分支另有创建分支 |
| 失败后重试 | 有专门 retry 路径，不能等同于编辑、回滚或恢复某条历史前缀 |
| 停止当前回复 | 支持取消信号、审批等待取消和 Job cancellation；不保证撤销已发生的外部效果 |
| 切会话是否停止 | 不停止；运行属于会话/服务，而非当前页面 |
| 普通 Turn 中发送新指令 | UI 不允许；后端 active-session admission 拒绝新 Turn，没有通用收件箱 |
| Goal 中人工接管 | `send` 可撤销续跑意图，`yieldToHumanInput` 请求暂停、取消并 join Goal Turn；是暂停后新请求，不是同 Turn steer |
| 从历史新建会话 | HXA-213 fork 分支已实现并记录验收，但核验时未合 main |

关键路径：[ChatService](../../app/src/main/kotlin/com/helix/app/chat/ChatService.kt) 的 `send`、`yieldToHumanInput`、`launchTurn`、`stop`、`stopTask`；[输入框](../../app/src/main/kotlin/com/helix/app/ui/ConversationComposer.kt)；[发送点击处理](../../app/src/main/kotlin/com/helix/app/ui/ChatScreen.kt)；[fork 决策](../adr/agent/007-session-fork.md)。

### 已发现的交互问题

1. `ChatScreen.onSend` 调用异步 `send` 后立即清空 input。提供商无效、会话忙或其他准入失败时，并没有统一的“已接收”回执保障草稿。这是代码路径证据，本次未通过设备复现所有错误组合。
2. `stop()` 直接发取消信号，`stopTask()` 则先持久化 CANCELLING 并投影“等待结算”。聊天与任务页应收敛为按稳定 Turn ID 取消的同一入口；现有差异不证明工具最终一定未结算，但会造成取消中间态与恢复意图不一致。
3. 运行时连草稿都不能输入。允许写下一条草稿属于 UI 改善，不能只解除按钮禁用就宣称支持 steer。
4. `ChatHistoryBuilder.rowsForTurn` 的 retry 保留其他 Turn 历史，再把重试 USER 放到末尾。若后面已发生其他对话，语义不是“回到当时重试”。未来编辑操作必须用严格前缀，不能复用 retry 充当回滚。
5. 模型请求只有部分证据，难以回答“用户这条补充到底进入了哪一次调用”。这是增量清单的需求，不是保存所有网络正文的理由。

## 4. Codex 与 Claude Code 的可借鉴机制

| 维度 | Codex 官方契约 | Claude Code 官方交互 | Helix 取舍 |
| --- | --- | --- | --- |
| 运行中干预 | App Server `turn/steer` 向当前 Turn 追加输入，要求 expectedTurnId 匹配；`turn/interrupt` 独立 | 运行中输入可排队，工具调用结束后可在当前 Turn 交付；有主动中断后发送路径 | 稳定身份、明确交付边界、不要改写已发出请求 |
| 历史恢复 | Thread 与 Turn/Item 分离；提供 fork；旧 rollback 接口已标记 deprecated | rewind 可只恢复会话；原 prompt 回填输入框供修改 | 默认“编辑并创建分支”，保留原记录 |
| 文件与对话 | 旧 rollback 仅改变上下文并写标记，不是文件恢复 | 文件 checkpoint 不追踪任意 Bash 修改；会话恢复可保留代码 | 不在手机端承诺通用文件/外部状态回滚 |
| 压缩后历史 | API 的线程记录与模型有效输入有不同职责 | 摘要释放上下文，原消息仍留在 transcript | 保留事实源与独立投影，不复制两份可变聊天 |

来源（2026-09-22 实际打开核验）：[Codex App Server](https://learn.chatgpt.com/docs/app-server)、[Claude Code interactive mode](https://code.claude.com/docs/en/interactive-mode)、[Claude Code checkpointing](https://code.claude.com/docs/en/checkpointing)。API 能力不证明所有 Codex 客户端具有同样按钮；Claude Code 热键受版本/终端支持影响。不从公开文档推断 Claude Code 内部数据库实现。

## 5. 建议的产品与实现契约

### A. 发送与停止先可靠

- 发送以明确 sessionId、clientRequestId、草稿版本及附件快照提交，返回 Accepted / PendingConfirmation / Rejected。只有应用已持久接收输入后才清空对应版本草稿；失败、取消确认保留/恢复草稿，不能覆盖用户随后新输入。
- 运行中允许编辑草稿。停止与发送分开，不让一个图标在有新草稿时产生歧义。
- 聊天页、Tasks 和引擎 cancel 共用稳定 Turn ID 路径：先持久化取消意图，再发信号，完成结算后显示终态。重复停止幂等；旧页面不能误停后来启动的 Turn。
- 区分“停止回复”和独立后台 Job/手动终端。是否取消相关 Job 由已有执行契约和明确操作决定，不能把所有进程都杀掉。

### B. 编辑并重发：复用 fork，严格排除旧消息

用户在 USER 消息菜单选择“编辑并重发”，原文和附件进入可取消的编辑草稿。确认后新建分支，历史只取到目标 USER **之前**，再追加修改后的新 USER；目标原文、它的回答、后续工具结果以及包含它们的未来摘要均不得进入新请求。

必须支持编辑会话第一条消息：允许空前缀，不能调用现有 inclusive fork 后简单再追加修正版。目标已被后来的摘要覆盖时，使用边界之前的合法检查点与原始前缀，必要时走正常压缩；不能复用已掺入错误原文的摘要。

沿用 HXA-213 的正文/附件引用、默认权限、完整工具配对、来源映射和资源限制。记录被替换的源 messageId 及新 messageId；原会话和执行审计不变。来源有正在执行的任务时明确标注其独立继续；停止来源是独立用户动作，不因编辑自动取消或重放。

提交新分支后若 Provider/外发准入失败，保留新会话与编辑稿并给出修复入口；幂等重试不能再创建第二个分支。附件变更/失效继续走既有 hash 与出网验证。

### C. 运行中输入：两种明确操作，共用小型持久收件箱

1. **调整当前任务**：下一次合适的模型请求读取，正常情况保留原 Turn 与预算，不取消整个任务再重开来伪装 steer。
2. **完成后发送**：当前 Turn 结束后按顺序启动后继 Turn；用户可编辑/撤回未交付项。

收件箱是应用数据，只有用户输入可写，MCP/Skill/模型不能冒充用户。建议最小字段为 inputId、sessionId、expectedTurnId、类型、顺序、内容/附件引用、接收时间、交付状态和消费目标。具体 schema 先经 ADR 决定；限制单条和会话待处理量，满时明确拒绝并保留草稿，不静默丢弃。无需新调度框架或系统常驻服务。

交付规则：

- 模型正在流式输出：保存输入，当前 HTTP 请求不可变；在下一模型请求边界交付。产品显示“等待模型接收”，不提前显示已采纳。用户需要立即停止时使用独立停止操作。
- 工具批执行中：整批已开始/排队调用按原机制结算、结果保持原序配对后，才能插入 USER 指令。不得插在 assistant tool_calls 与配套 TOOL results 中间。
- 等待审批：显示新增指令仍待交付；提供明确停止当前操作的入口。新指令不代表批准/拒绝，也不自动改变已有审批绑定。
- 模型准备最终结束：在同一串行协调边界检查收件箱，若有可交付 steer，则继续循环；结束已提交时返回明确状态，不能悄悄附到其他 Turn。后继发送类型则按已授权语义启动新 Turn。
- 消费、USER 行追加与交付记录需原子化。恢复不盲目重发已在途模型调用；记录“已纳入请求”不等于远端已收到，更不等于模型执行了指令。
- 死进程后保留待输入与已交付身份，恢复沿现有 INTERRUPTED/未知副作用检查；不因为队列存在就后台重放工具或无条件续跑。
- steer 不重置 Turn 预算，不改变当前模型/授权快照；预算已耗尽就明确停止。Goal 的人工接管先保留现有暂停/撤销续跑语义，不能将补充话语自动改写为持久 Goal 目标或恢复权限。
- 若待输入超出上下文，参与同一压缩和硬容量门禁。失败保留待输入，不能既标记交付成功又实际没进请求。

### D. 上下文可观察性：记录清单，不保存第二套全文

在模型请求完成最终组装与准入后，按 modelCallId 记录有界的版本化清单：来源 message IDs/序号、压缩 checkpoint、工具 schema 指纹、system prompt 指纹、附件 hash/引用、输入估算、模型参数，以及此次消费的 input IDs。

区分 request-prepared、request-started、完成/中断/结果未知；清单是逻辑 ModelRequest 证据，不等于 Provider 最终序列化字节。若需要精确 wire 排错，采用用户显式启用、有大小/期限上限且可清理的诊断方式，过滤认证字段；不要默认长期重复保存所有正文和图片。

普通聊天不显示该清单，调用详情可按需查看；JSONL 可通过版本化新增记录导出。若宣称精确重建请求，则必须额外保存当时不可变的系统/工具内容及 adapter 版本；单独保存 hash 只能比对，不能重建。

## 6. 建议实施切片与验收

| 顺序 | 交付内容 | 必须验证 |
| --- | --- | --- |
| P0 | 发送回执、草稿版本保护、统一停止 | 准入拒绝不丢稿；确认取消；连续点击；切会话；旧 Turn 停止；取消中进程死亡；工具结果结算 |
| P1 | 编辑并创建分支重发 | 第一条/中间/压缩前后；旧原文和未来摘要不泄漏；原历史不变；附件；取消编辑；重复发送幂等 |
| P1 | 持久输入、批边界 steer 与后继发送 | 流式/工具批/审批/最终文本竞争；队列编辑撤回；预算耗尽；Goal 接管；真实主进程死亡；双会话隔离 |
| P2 | 请求清单与详情/JSONL | 实际 loopback 捕获请求对应消息及 input IDs；隐私/大小上限；版本解析；清理及引用完整性 |

每切片独立通过 `./scripts/check-all.sh --all`；涉及应用行为覆盖 API29/36 × consumer/developer 的独占设备旅程。协议断言应检查 loopback Provider 实际收到的请求，不只断言 UI 文字。进程死亡验收由普通应用启动场景和宿主定点终止自有主进程完成，不能把 Room close/reopen 当作进程死亡。

本次仅源码与官方文档核验，不新增功能通过结论。P0 可作为现行行为修复；编辑语义、持久输入及同 Turn 转向涉及数据/引擎契约，应在实施时收敛 ADR 与 HXA，不能直接只改 Compose 开关。

## 7. 不需要为此建设的东西

不新增双份聊天数据库、通用事件溯源平台、分支 DAG 查询引擎、子 Agent、云端 Worker、常驻前台服务或任意文件回滚。也不假定所有 Provider 支持在同一 HTTP 流里插入用户消息。端侧完全可以依赖 Room、现有服务协程及请求/工具批边界实现上述交互，成本主要在并发、持久交付和恢复验收，而非手机缺乏算力。
