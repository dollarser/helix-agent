# 审查报告目录

本目录只保存**时间点项目审查/复审报告**：记录当时基线、发现、证据与评估，不承担当前实施计划、模型分工或长期架构契约。

当前状态以 [docs/development/status.md](../docs/development/status.md) 为唯一入口；当前任务范围看对应 HXA；长期决定看 ADR。审查结论再次使用前必须按“当时基线 → 当前源码/ADR/HXA”复核。

实施 playbook、Wave 计划和模型 handoff 属于临时工作材料：有效内容进入 ADR/HXA/completion/evidence 后删除，不在本目录长期维护。竞品与机制研究归 `docs/research/`。

## 2026-09-24

主要基线为 HEAD `3cf89027` 及当时工作树；个别后续复核在文内标明自己的基线。

| 报告 | 范围 |
| --- | --- |
| [2026-09-24/REVIEW-2026-09-24.md](2026-09-24/REVIEW-2026-09-24.md) | 四线深查：历史修复状态、QuickJS/PRoot/tools/extensions、安全边界与新功能质量 |
| [2026-09-24/2026-09-24-code-review.md](2026-09-24/2026-09-24-code-review.md) | 文档一致性、架构、UI/交互、核心路径 bug、优化删减综合审查 |
| [2026-09-24/2026-09-24-review-reevaluation.md](2026-09-24/2026-09-24-review-reevaluation.md) | 对综合审查逐项回源码复验和结论降级/确认 |
| [2026-09-24/2026-09-24-structure-review.md](2026-09-24/2026-09-24-structure-review.md) | 架构与目录/类内聚结构专项 |
| [2026-09-24/2026-09-24-supplement-verification-and-release.md](2026-09-24/2026-09-24-supplement-verification-and-release.md) | 验证体系、发布就绪度与静态门禁盲区 |
| [2026-09-24/helix-review-reevaluation-2026-09-24.md](2026-09-24/helix-review-reevaluation-2026-09-24.md) | 后续独立复核与优先级再评估 |
| [2026-09-24/helix-ui-navigation-settings-review-2026-09-24.md](2026-09-24/helix-ui-navigation-settings-review-2026-09-24.md) | 导航、菜单命名、Settings IA 与移动端交互专项 |
| [2026-09-24/independent-review/](2026-09-24/independent-review/README.md) | 五维度独立复审及分维度详版 |

## 已迁出的材料

- 浏览器竞品/能力研究已移到 [docs/research/codex-browser-vs-helix-2026-09-24.md](../docs/research/codex-browser-vs-helix-2026-09-24.md)。
- 进程死亡恢复与竞品机制研究已移到 [docs/research/process-death-recovery-vs-competitors-2026-09-25.md](../docs/research/process-death-recovery-vs-competitors-2026-09-25.md)。
- 2026-09-24～25 的 clean-slate/Wave0/Wave1/强模型/小模型实施与交接文档已经收敛到 [ADR-AGENT-001](../docs/adr/agent/001-turn-coordination.md) 与 [HXA-220](../docs/development/tasks/HXA-220.md)，原临时文件已删除；逐字历史可从 Git 获取。
