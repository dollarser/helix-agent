# Helix 文档中心

| 目的 | 入口 |
| --- | --- |
| 当前进展与下一步 | [实施状态](development/status.md) |
| 查找任务与验收范围 | [开发路线](development/roadmap.md) |
| 交给任意编码 Agent | [实施指南与交接 Prompt](development/implementation-guide.md) |
| 环境和验证命令 | [开发环境](development/environment.md)、[公共验收规则](development/verification-matrix.md) |
| 产品和操作体验 | [产品入口](product/README.md)、[产品需求](product/requirements.md)、[操作链](product/task-experience.md) |
| 架构与约束 | [架构入口](architecture/README.md)、[总体架构](architecture/overview.md)、[安全与发布](security/testing-and-release.md) |
| 项目结构与执行引擎研究 | [结构与文档治理（历史基线）](research/modules/01-architecture-and-execution-engine.md) · [核心路径深度复审（历史基线）](research/modules/01-architecture-and-execution-engine.md) |
| 执行引擎与端侧差距 | [Helix 与 Codex、DSH、Claude Code 对比（历史 Helix 基线）](research/modules/01-architecture-and-execution-engine.md)（竞品机制仍可参考；当前实现看 ADR/HXA） |
| 对话历史与运行中干预 | [上下文、编辑重发与转向优化](research/modules/02-context-input-and-session.md)（研究基线；当前执行/输入/review 契约见 [ADR-AGENT-001](adr/agent/001-turn-coordination.md)，请求追踪见 [ADR-AGENT-005](adr/agent/005-session-jsonl-export.md)） |
| UI 与交互优化 | [审查核验与优化方案](research/modules/03-ui-ia-and-workbench.md)（当前源码校正、会话与工作台设计、实施顺序及验收目标；集成版已记录实施状态） · [第一批重构交付](completion-records/HXA-218.md) · [产物就地预览](completion-records/HXA-219.md) |
| 工具曝光与能力复用优化 | [优化方向建议](research/modules/04-tools-browser-and-extensions.md)（未立项） |
| 端侧模型 Provider（候选） | [本机小模型接入方案](research/modules/05-runtime-provider-and-on-device-models.md)（现状核实、consumer 明文阻断、引擎选型、分阶段路径；未立项） |
| 当前决定 | [按主题组织的 ADR](adr/README.md) |
| 已交付结果 | [完成记录索引](completion-records/index.md) |
| 历史诊断和研究 | [证据索引](evidence/README.md)、[研究入口](research/README.md) |

## 文档职责

- `product/`：需求、用户体验、定位和竞品分析，见 [product/README.md](product/README.md)；研究结论不直接成为实现要求。
- `architecture/`：跨模块职责与当前契约，见 [architecture/README.md](architecture/README.md)；计划功能明确标注交付状态。
- `adr/`：按功能保存**当前有效长期决策**；同一功能直接更新原 ADR，并在 `Decision history` 记录重要变化，不保存版本化 ADR 链。
- `development/`：当前开发控制面，见 [development/README.md](development/README.md)；`status.md` 是唯一当前状态/下一步入口，`roadmap.md` 是 HXA 索引，`tasks/HXA-NNN.md` 只保存未完成的代码/产品开发工作规格；纯文档整理/Research 综合不单独创建 HXA。
- `completion-records/`：每个已完成 HXA 的交付快照、命令与证据，不作为今天的运行指令。
- `evidence/`：验收过程、外部材料和诊断快照，不记录当前 Agent 分工。
- `research/`：候选方案、竞品与机制分析，见 [research/README.md](research/README.md)；研究结论不是任务授权。`references/` 保存外部来源与机制参考，见 [references/README.md](references/README.md)。
- `bug-fixes/`、`postmortems/`：只保存值得跨任务长期复用的缺陷机制/事故；普通当轮修复归 HXA 记录，不默认一 bug 一文件。
- 根目录 `reviews/`：只保存**时间点项目审查报告**；实施计划、模型交接、Wave playbook 完成后必须把有效内容迁入 ADR/HXA/evidence 并删除。

同一信息只保留一个当前入口：**当前状态/下一步在 status，工作范围在当前 HXA，长期决定在功能 ADR，执行结果在 completion record，审查证据在 reviews/evidence。** 不再维护独立 next-work-plan 或按模型命名的 handoff/playbook。旧 Agent 的工作树、模拟器占用和会话分工不约束后续接手。编码须遵守根目录 [AGENTS.md](../AGENTS.md)。

一次性交接完成后，独有证据合入完成记录或 evidence，再删除指令文件及更新入链；不保留重定向占位。历史材料不再维护‘当前状态’，过时的阻塞或分工须在页首标明历史适用时间。
