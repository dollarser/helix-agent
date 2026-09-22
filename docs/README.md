# Helix 文档中心

| 目的 | 入口 |
| --- | --- |
| 当前进展与下一步 | [实施状态](development/status.md) |
| 查找任务与验收范围 | [开发路线](development/roadmap.md) |
| 交给任意编码 Agent | [实施指南与交接 Prompt](development/implementation-guide.md) |
| 后续开发顺序 | [工作计划](development/next-work-plan.md) |
| 环境和验证命令 | [开发环境](development/environment.md)、[公共验收规则](development/verification-matrix.md) |
| 产品和操作体验 | [产品需求](product/requirements.md)、[操作链](product/task-experience.md) |
| 架构与约束 | [总体架构](architecture/overview.md)、[安全与发布](security/testing-and-release.md) |
| 项目结构与执行引擎审查 | [结构与文档治理](research/project-structure-and-engine-review.md) · [核心路径深度复审与复现](research/execution-engine-deep-review-2026-09-22.md) |
| 执行引擎与端侧差距 | [Helix 与 Codex、DSH、Claude Code 对比](research/execution-engine-comparison.md)（调用链、Runtime 边界、模型数据、预算与补足方向） |
| 对话历史与运行中干预 | [上下文、编辑重发与转向优化](research/conversation-context-and-steering.md)（事实核验；开发契约已收敛至 Agent ADR-008/009/010 与 HXA-214～217） |
| UI 与交互优化 | [审查核验与优化方案](research/ui-interaction-optimization.md)（当前源码校正、会话与工作台设计、实施顺序及验收目标；集成版已记录实施状态） · [第一批重构交付](completion-records/HXA-218.md) · [产物就地预览](completion-records/HXA-219.md) |
| 工具曝光与能力复用优化 | [优化方向建议](research/tool-exposure-optimization.md)（未立项） |
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

一次性交接完成后，独有证据合入完成记录或 evidence，再删除指令文件及更新入链；不保留重定向占位。历史材料不再维护‘当前状态’，过时的阻塞或分工须在页首标明历史适用时间。
