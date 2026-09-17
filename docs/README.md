# Helix 文档中心

| 目的 | 入口 |
| --- | --- |
| 当前进展与下一步 | [实施状态](development/status.md) |
| 查找任务与验收范围 | [开发路线](development/roadmap.md) |
| 交给任意编码 Agent | [实施指南与交接 Prompt](development/implementation-guide.md) |
| 环境和验证命令 | [开发环境](development/environment.md)、[公共验收规则](development/verification-matrix.md) |
| 产品和操作体验 | [产品需求](product/requirements.md)、[操作链](product/task-experience.md) |
| 架构与约束 | [总体架构](architecture/overview.md)、[安全与发布](security/testing-and-release.md) |
| 执行引擎与端侧差距 | [Helix 与 Codex、DSH、Claude Code 对比](research/execution-engine-comparison.md) |
| 当前决定 | [按主题组织的 ADR](adr/README.md) |
| 已交付结果 | [完成记录索引](completion-records/index.md) |
| 历史诊断和研究 | [证据索引](evidence/README.md)、`research/` |

## 文档职责

- `product/`：需求、用户体验、定位和竞品分析；研究结论不直接成为实现要求。
- `architecture/`：跨模块职责与当前契约；计划功能明确标注交付状态。
- `adr/`：按主题保存当前有效决定和 proposed 决策，不保留旧编号兼容路径。
- `development/`：状态、任务索引、公共命令；`tasks/` 只保存未完成任务规格。
- `completion-records/`：交付时点的命令与证据，不作为今天的运行指令。
- `evidence/`：验收过程、外部材料和诊断快照，不记录当前 Agent 分工。
- `research/`、`references/`、`history/`：候选方案、来源和仍有价值的历史材料。
- `bug-fixes/`、`postmortems/`：缺陷根因、回归与系统性事故教训。

同一信息只保留一个当前入口：状态在 status，范围在任务，命令在公共规则或任务，决定在 ADR，结果在完成记录。旧 Agent 的工作树、模拟器占用和会话分工不约束后续接手。编码须遵守根目录 [AGENTS.md](../AGENTS.md)。
