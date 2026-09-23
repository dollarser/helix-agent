# Agent 执行与上下文

[全部决策](../README.md) · [实施状态](../../development/status.md)

## 当前有效决定

- accepted [ADR-AGENT-001](001-turn-coordination.md)：Turn 批次协调与持久结算
- accepted [ADR-AGENT-002](002-context-compaction.md)：模型请求上下文与步骤边界压缩
- accepted [ADR-AGENT-003](003-attachments.md)：附件快照与请求物化
- accepted [ADR-AGENT-004](004-bounded-delegation.md)：有界只读委托与工作流边界
- accepted [ADR-AGENT-005](005-session-jsonl-export.md)：按会话导出 JSONL 执行记录，供调试、历史检索与评测；交付边界见 [HXA-211](../../completion-records/HXA-211.md)。
- accepted [ADR-AGENT-006](006-model-data-budget-boundaries.md)：模型结果投影、预算诊断与明确继续
- accepted [ADR-AGENT-007](007-session-fork.md)：用户主动按消息创建会话分支，交付见 [HXA-213](../../completion-records/HXA-213.md)。

- accepted [ADR-AGENT-008](008-user-input-delivery.md)：统一发送回执、停止、Queue/Steer 与 Goal 输入调度；对应 HXA-214/216。
- accepted [ADR-AGENT-009](009-edit-and-resend.md)：最新用户消息在原会话修订重发；对应 HXA-215。更早历史编辑以后按修改位置 fork。
- accepted [ADR-AGENT-010](010-request-context-manifest.md)：轻量请求来源记录与 JSONL；详情页和详细诊断延期；对应 HXA-217。

## 待接受的开发提案

009已交付；008按所有者后续开发授权接受，Goal与Turn有效条款已同步，[216已完成本地验收](../../completion-records/HXA-216.md)，main整合与远端CI独立记账。010已由所有者授权并由 HXA-217 交付。

accepted 只表示设计决定；交付与验收查实施状态和对应任务。跨主题修改同时核对[权限](../permissions/README.md)、[执行域](../runtime/README.md)与[工作目录](../workspace/README.md)，不把一个主题的许可推导成另一个主题的授权。
