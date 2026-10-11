# Agent 执行与上下文

[全部决策](../README.md) · [实施状态](../../development/status.md)

## 当前有效决定

- accepted [ADR-AGENT-001](001-turn-coordination.md)：TurnEngine、batch settlement、Queue/Steer、修订重发、UNKNOWN/review、successor-Turn recovery、runtime snapshot 与 receipt；Engine/Core 既有交付追溯 HXA-220/223/234。2026-10-11 补充应用入口/表现/执行资源边界，恢复导航增量归 HXA-274，原生资源接线归 HXA-277，代码未实施；完整宿主提取保留进入条件。
- accepted [ADR-AGENT-002](002-context-compaction.md)：模型请求上下文与步骤边界压缩。
- accepted [ADR-AGENT-003](003-attachments.md)：附件快照与请求物化。
- accepted [ADR-AGENT-004](004-bounded-delegation.md)：有界只读委托与工作流边界。
- accepted [ADR-AGENT-005](005-session-jsonl-export.md)：会话执行追踪、request context manifest 与用户主动 JSONL 导出；HXA-211/217 已交付。
- accepted [ADR-AGENT-006](006-model-data-budget-boundaries.md)：模型结果投影、预算诊断与明确继续。
- accepted [ADR-AGENT-007](007-session-fork.md)：用户主动按消息创建会话分支，交付见 [HXA-213](../../completion-records/HXA-213.md)。

- accepted [ADR-AGENT-013](013-markdown-memory.md)：Markdown-native Memory、Global 首版与显式 Project identity 边界；对应 HXA-230。

- accepted [ADR-AGENT-011](011-tool-multimodal-vision-feedback.md)：Agent 自主图片读取、工具视觉回填与数据披露；所有者于 2026-09-29 接受，主机交付及设备/模型边界见 [HXA-225](../../completion-records/HXA-225.md)。

历史独立编号 008/009/010/012 已按功能边界收敛到 ADR-AGENT-001/005；决策变化见各 ADR 的 `Decision history`，旧文件不再作为当前入口。

accepted 只表示设计决定；交付与验收查实施状态和对应任务。跨主题修改同时核对[权限](../permissions/README.md)、[执行域](../runtime/README.md)与[工作目录](../workspace/README.md)，不把一个主题的许可推导成另一个主题的授权。
