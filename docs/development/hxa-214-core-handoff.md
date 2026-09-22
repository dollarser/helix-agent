# HXA-214 核心与 UI 接线边界

本轮所有者只要求 214 核心；任务尚不关闭，主界面接线及完整产品验收另行完成。实现与测试状态随本文件收口更新，不将接口存在等同产品可用。

## 核心接口

- `ChatSubmission` 捕获 sessionId、revision、clientRequestId、文本及附件身份。编辑创建新 revision 和 request ID；发送期间不得改写已提交快照。
- `sendSubmission` 返回服务拥有的 Deferred 回执，结果区分 Accepted、PendingConfirmation、Rejected。UI 等待取消不等于取消已发出的提交，不自动停止 Goal 之外的普通 Turn，也不增加队列。
- `confirmSubmission` 必须传原始快照，拒绝旧版本或跨会话确认；现有出网目标及附件校验保留。
- `cancelSubmission` 绑定同一快照；取消旧确认不会误取消新提交。已接收的请求可从持久 Turn 恢复回执；带附件的回执恢复还要求原草稿未被编辑或清除，无法核对时拒绝重用 ID，不重新执行。
- `saveComposerDraft` / `loadComposerDraft` 操作已持久化会话的草稿；保存带 expectedRevision，失败返回 false 不覆盖新稿。Room v24 只新增一会话一条 composer_drafts，不修改消息/执行记录；会话硬删除级联清除。文本与附件 ID 不写审计，不参与历史/压缩/JSONL。
- 新草稿以 revision=0、expectedRevision=null 保存；编辑使用旧 revision 作为 expectedRevision，新 revision 加一并换 request ID。Accepted 草稿确认清除后，下一份新草稿重新从 0 开始且必须使用新 request ID。相同快照重复保存幂等；参数格式或版本递增非法会拒绝，UI 不应把保存失败显示为已保存。
- `acknowledgeSubmission` 只清除 Accepted 且与持久 Turn、草稿 revision/request ID 匹配的记录。待确认、拒绝、旧回执均不能清空新稿。
- `stopTurn(turnId)` 是显式停止入口；聊天、Tasks 和 AgentRuntime 汇合，持久 CANCELLING 后才发取消信号，终局竞争返回实际 AlreadyTerminal。独立 Job/PTY 不随之批量取消。

## 后续 UI 执行者的范围

1. 将 ChatScreen 的本地 remember 输入接到按会话保存/加载；实现有界防抖及明确保存回执，切换/旋转/后台切换前处理未落盘修改。新建的瞬态会话需明确物化入口，不能对不存在的 sessionId 直接写入草稿表。
2. 去掉发送后立即 `input = ""`。捕获快照，等待回执，只有 Accepted 且当前 session/revision 仍一致才清空屏幕并 acknowledge；其他结果保留输入并映射为三语可读提示，不显示内部错误码。
3. 运行中只开放编辑，发送仍遵循现有 busy gate；不接 Queue/Steer，不改变 Goal 新输入规则。GoalDialog 的继续操作与共享/语音预填也必须遵守 revision 保护。
4. 恢复附件 ID 时从已有 artifact 元数据重建并重新校验 staged 快照，不能只显示附件名称就默认可发；缺失附件保留草稿并给明确处理入口。本轮存储恢复用例不替代附件重新挂载产品旅程。
5. Stop 绑定渲染时的 Turn ID，不在异步任务中重新选择当前 Turn。按钮状态与取消中的真实持久阶段一致。
6. 完成任务规格要求的三个产品测试类、scope214 runner 和普通应用 seed/kill/recover；核心测试使用数据库关闭重开，仅证明存储恢复。

## 与215修订草稿整合

215沿本核心基线新增可选 `ChatSubmission.revisedMessageId`，Room v25在既有草稿中保存该目标，同时给消息增加 `supersededBy`。后续合并UI时必须保留字段：非空目标交由 `MessageRevisionDialog` 及其修订回执处理，不能当作普通新消息恢复、清空或重新生成提交身份；普通composer也不能覆盖已有修订草稿。不要再占用迁移24→25。`accept-conversation-interaction.py` 当前只实现scope215；214执行者应扩展独立scope214，不覆盖已有215验收。215不关闭本任务的普通composer接线及产品验收。

旧 `send` / `confirmSend` / `cancelPendingSend` 入口仍用于未迁移的页面。附件发送被拒时保留旧页面的一次性恢复；新持久草稿路径不得依赖该无 revision 的恢复字段。`Rejected.reason` 暂时包含新内部错误码和原有本地化 gate 提示，接线时须映射内部错误码，保留已有用户提示，不能将所有 reason 当成原始码展示。

## 验证与交付

核心切片已完成本地验证：全量主机门禁通过，API29/36 × consumer/developer 应用回归 160/160，独立 Room 迁移 72/72，失败/跳过均为 0。命令、制品 hash 和日志见[验证记录](../evidence/development/hxa-214-core-2026-09-22.md)。

核心回归入口为 `scripts/debug/2026-09-22/accept-hxa214-core.py`，存储入口为同目录 `accept-hxa214-storage.py`。它们与完整 `accept-conversation-interaction.py --scope 214` 不同，不能替代完整 214 交付门禁。基线为 `645fa680`；本轮只提交独立分支，不合并或推送。后续合入新 main 时需按实际差异复核，不把本分支证据直接称为新 main 验收。
