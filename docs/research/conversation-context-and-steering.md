# 对话历史、模型上下文与运行中用户干预

核验日期：2026-09-22。性质：源码核验、官方产品对比与优化设计，尚未实现的部分均为建议，不作为验收结论或自动改变现有 ADR。

源码快照：当前应用工作分支 `codex/marketplace-catalog` 的 `d5dc51ac`；另核对 `codex/session-fork` 的 `98b904b5`（包含压缩优化 `5c2a11df`）。核验时本地 main 为 `c36d556b`，fork 尚未进入 main。本页源码链接以本文所在 fork 分支为准，共有路径已交叉核对；不推断真机安装版本。

## 1. 结论

Helix 已将用户界面与模型请求分开投影，但没有各保存一份完整独立对话。持久消息及执行记录是事实源，UI 选择展示，模型在每次请求前重新组装上下文。这个方向合理，应完善可观察性而不是复制第二套可变历史。

目前有停止回复，没有通用的已发送消息编辑重发、普通输入队列或同 Turn 引导。Goal 有人工输入让位的应用服务路径，但聊天输入框在运行中禁用，不能把这个内部路径算成完整的运行中输入产品能力。fork 提供了编辑重发的基础，但当前只复制至指定消息，不支持替换这条消息。

已规划顺序：发送回执与统一停止 → 基于 fork 的编辑重发 → 持久待输入与工具批边界引导 → 请求上下文清单。它们是应用/引擎通用能力，不新增 Agent Tool、专用规划器或业务状态机。

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
| 运行中干预 | Queue 等当前运行结束；Steer 加入当前运行，可配置默认。App Server `turn/steer` 要求 expectedTurnId 匹配，`turn/interrupt` 独立 | 运行中输入可排队，工具调用结束后可在当前 Turn 交付；有主动中断后发送路径 | 稳定身份、明确交付边界、不要改写已发出请求 |
| 历史恢复 | Thread 与 Turn/Item 分离；提供 fork；旧 rollback 接口已标记 deprecated | rewind 可只恢复会话；原 prompt 回填输入框供修改 | 默认“编辑并创建分支”，保留原记录 |
| 文件与对话 | 旧 rollback 仅改变上下文并写标记，不是文件恢复 | 文件 checkpoint 不追踪任意 Bash 修改；会话恢复可保留代码 | 不在手机端承诺通用文件/外部状态回滚 |
| 压缩后历史 | API 的线程记录与模型有效输入有不同职责 | 摘要释放上下文，原消息仍留在 transcript | 保留事实源与独立投影，不复制两份可变聊天 |

来源（2026-09-22 实际打开核验）：[Codex Queue/Steer](https://learn.chatgpt.com/docs/prompting#steering-and-queuing)、[Codex App Server](https://learn.chatgpt.com/docs/app-server)、[Claude Code interactive mode](https://code.claude.com/docs/en/interactive-mode)、[Claude Code checkpointing](https://code.claude.com/docs/en/checkpointing)。API 能力不证明所有 Codex 客户端具有同样按钮；Claude Code 热键受版本/终端支持影响。不从公开文档推断 Claude Code 内部数据库实现。

## 5. 需求已收敛至 ADR 与 HXA

本页只保留核验事实与外部来源，不再维护第二份开发契约。2026-09-22 后续讨论明确：发送不自动停止当前任务，不因 Goal 使用另一套交互；排队的交付边界由 Queue/Steer 明确表达。

| 决策提案 | 开发任务 | 交付职责 |
| --- | --- | --- |
| [ADR-AGENT-008](../adr/agent/008-user-input-delivery.md) | [HXA-214](../development/tasks/HXA-214.md)、[HXA-216](../development/tasks/HXA-216.md) | 回执/停止修复；普通发送默认 Queue、显式 Steer，统一 Goal/普通 Turn |
| [ADR-AGENT-009](../adr/agent/009-edit-and-resend.md) | [HXA-215](../completion-records/HXA-215.md) | 最新消息按所有者新指令在原会话修订（已交付）；更早消息编辑以后再从修改位置fork |
| [ADR-AGENT-010](../adr/agent/010-request-context-manifest.md) | [HXA-217](../development/tasks/HXA-217.md) | 轻量请求来源记录与 JSONL；详情页和详细诊断延期 |

ADR-AGENT-009已接受并完成215分支交付；008/010仍为proposed。默认顺序214→215→216→217，215与216技术上仅共享214前置，但默认串行规避 chat/UI/storage 冲突。依赖、实施/接受门槛、实际状态以[任务索引](../development/roadmap.md)和[实施状态](../development/status.md)为准。

现行 Goal ADR 的“新输入解除激活”是旧有效契约，008 明确列出接受时的同步修订要求；研究中解释该路径不等于推荐继续保留它。新方案不新增场景规划器、子 Agent、云端 Worker、常驻服务或任意文件回滚。
