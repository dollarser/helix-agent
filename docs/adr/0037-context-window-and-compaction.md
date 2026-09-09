# ADR-0037: 上下文窗口配置与会话压缩

Status: superseded
Date: 2026-09-09
HXA: HXA-174
Deciders: Project owner（明确要求默认 200k、服务端读取、比例触发自动压缩和手动压缩）
Supersedes: none
Superseded by: [ADR-0038](0038-step-boundary-context-compaction.md)

## Context

实际对话请求仍发送全部历史，预算组件不能替代模型窗口，现有圆环只有请求输入快照。[调研](../development/context-window-and-shared-storage.md)中的 Codex、Claude Code 和 DeepSeek Harness 共同要求区分窗口、压缩阈值与持久上下文，且压缩不能损坏原始历史。

## Decision

按 Provider ID、规范 endpoint 和 model 绑定配置，默认窗口 200000 tokens、自动压缩开启、80% 触发，可设置 10～95%。服务返回精确模型窗口时自动采用；用户手动值不能突破已知服务器上限。未知元数据不让连接测试失败，不跨模型借值，不从模型名猜测。配置与累计 Turn/Goal 预算分离；不改变 accepted ADR-0004 的继续/暂停/预算语义。

压缩由当前模型执行无工具摘要请求，保留最近完整轮次、当前输入及工具调用/结果配对，原始 messages 不删除。摘要以 session checkpoint 持久化，记录覆盖序列与摘要；模型可见历史只在完整摘要成功后切换，原消息仍可在会话 UI 查看。摘要是低信任历史资料，不是 system 指令或审批凭据。

摘要模型调用经过现有 Provider、出站授权、Turn/Goal 调用与 token 预算；自动压缩不额外唤醒 Goal、不自动重放未知结果。当前会话只允许一个活动轮次。手动按钮触发普通显式 Turn；只有已绑定 GoalRun 的自动压缩同时计入该 Goal，按钮不创建或继续 GoalRun。增加 RECEIVING_MODEL → BUILDING_CONTEXT 的受控内部边，用于已成功结束摘要调用后构建原任务请求，不伪造工具调用。取消、拒绝、失败、预算不足不发布未完成摘要；持久替换与调用关闭原子提交。手动压缩是显式用户操作，复用同一预算与停止路径。

## Consequences

压缩有模型成本和摘要遗漏风险；保留原始历史与近期原文用于回查。单个不可分割的大轮次可能无法缩到上限，应明确失败而非删除工具结果或无限摘要。checkpoint 复用现有 file-backed messages 的独立 kind 与事务，不增加 Room schema；需要旧会话/重启验收，禁止以可编译或界面按钮代替完成证据。

## Verification

HXA-174 要覆盖默认/自动/手动/模型切换、阈值/工具配对/不可压缩项、自动与手动摘要成功、拒绝/取消/预算/进程死亡、旧库重启兼容、原消息保留、真实 SGLang 元数据及摘要。本文是决定，不宣称实现已完成。

## References

- [ADR-0004](0004-goal-run-wake-budget-semantics.md)
- [Codex 配置](https://learn.chatgpt.com/docs/config-file/config-reference)
- [Claude Code 上下文管理](https://code.claude.com/docs/en/how-claude-code-works)
- [DeepSeek Harness 压缩](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/compaction.md)

## Alternatives considered

只显示 usage 不满足压缩要求；静默删除旧消息损坏回查；复用累计 Turn 预算作为窗口混淆两个独立上限。复用现有消息内容与事务可避免为单一 checkpoint 新增数据库 schema。

## Reconsider when

模型元数据标准变化、摘要质量不足、不可压缩大轮次频繁出现或实际 token 估算偏差显著时复审；服务端原生 compaction 需单独验证协议、持久化和权限边界。
