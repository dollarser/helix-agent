# HXA-214/215 对话输入整合边界

HXA-214 核心、普通 composer UI 与 HXA-215 会话内修订现已在 `codex/conversation-convergence` 收敛。本文件保留核心接口和整合不变式，最终交付与验证以 [HXA-214 完成记录](../completion-records/HXA-214.md)、[HXA-215 完成记录](../completion-records/HXA-215.md)及[联合证据](../evidence/development/conversation-convergence-2026-09-22.md)为准。

## 核心接口

- `ChatSubmission` 捕获 sessionId、revision、clientRequestId、文本、附件身份和可选修订目标。编辑创建新 revision 和 request ID；发送期间不改写已提交快照。
- `sendSubmission` 返回服务拥有的 Deferred 回执，结果区分 Accepted、PendingConfirmation、Rejected。UI 等待取消不等于取消已发出的提交，不自动停止普通 Turn，也不增加队列。
- `confirmSubmission` 和 `cancelSubmission` 绑定原始快照，拒绝旧版本或跨会话操作。已接收请求只从持久 Turn 查询回执，不重发。
- `saveComposerDraft` / `loadComposerDraft` 使用 expectedRevision 做 CAS；相同快照重复保存幂等，非法版本递增或旧写入不覆盖新稿。Accepted 只清除与持久 Turn、revision 和 clientRequestId 全部匹配的草稿。
- `stopTurn(turnId)` 是聊天、Tasks 和 AgentRuntime 的稳定停止入口。先持久 CANCELLING 再发取消信号，终局竞争返回真实 AlreadyTerminal；独立 Job/PTY 不随普通 Turn 批量取消。

## 普通 composer 整合

1. `ConversationDraftBuffer` 按会话保存不可变输入意图；500ms 防抖、生命周期 flush、显式发送和附件同步共享同一互斥边界。挂起自动保存完成后，发送复用同一 intent 的已持久快照，不以 revision 差异重复写入相同 request ID。
2. 文本或附件的真实用户修改立即生成新 clientRequestId。旧回执只能确认自身快照，不能清除切会话后的输入或同会话的新编辑。
3. 旋转或普通主进程恢复先查询持久回执，再恢复仍存在的当前附件。服务提交锁串行确认副作用，输入区锁串行回执结算与附件同步；ack、重载和本地状态更新使用短的不可取消临界段。
4. 发送中允许编辑下一份草稿，但 send 仍受 busy gate 限制；HXA-216 之前不启用 Queue/Steer，也不改变 Goal 新输入语义。
5. 附件从 artifact 元数据重建并重新校验 hash、类型和尺寸；缺失附件保留文本并提示，不把显示名称当作可发送证明。

## 与 HXA-215 修订整合

- Room v25 保留 `composer_drafts.revisedMessageId` 和 `messages.supersededBy`。非空修订目标只交由 `MessageRevisionDialog` 与修订回执处理；普通 composer 不覆盖、清除或重生成其身份。
- 修订编辑器保存后可主动关闭、返回列表并从同一消息重开；普通 composer 的生命周期 flush 对保留修订稿无害。应用重启默认恢复未关闭的修订编辑器。
- 修订确认保留原附件并在发送时重新检查。已接收回执由修订编辑器确认；确认与 editor 关闭是短的不可取消本地结算，避免 `screen.messages` 更新取消 effect 后留下已清稿但未关闭的对话框。
- 原消息、旧 Turn、工具/用量/审计仍保留；替代后缀不再进入有效历史、搜索、分页或压缩输入。更早历史编辑继续通过 HXA-213 的显式 fork，不在本轮改为原会话重写。

## Runner 与验收边界

`scripts/accept-conversation-interaction.py` 保留独立 `--scope 214`、`--scope 215`，并以 `--scope both` 动态发现当前方法集合。联合 scope 去重共享类，额外包含市场 UI 回归；每个 flavor/API/阶段新建独占模拟器进程，普通恢复使用 seed、SIGKILL 主 PID、verify 两阶段。存储迁移和 JSONL 使用独立 storage runner。

联合验收覆盖回执拒收与 CAS、会话/旋转/进程恢复、附件恢复与修订归属、稳定 Turn 停止及 CANCELLING 竞争、实际 loopback 请求、Goal/审批/fork/压缩回归和市场 UI。最终数字、APK hash、PID 变化、失败尝试取舍和远端 CI 见完成记录与联合证据。

loopback Provider 不是真实外部账号；模拟器 host 的 SIGKILL 权限不等于 App Root 或物理 OEM 验收。196/199 物理专项、125/126 真实服务账号、订阅实付、发行签名和渠道验收仍按各自任务保留。HXA-216 的队列/转向在独立分支实施，不把本轮接口存在写成该功能已交付。
