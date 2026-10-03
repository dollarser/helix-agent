# Helix 文档中心

先按问题选择入口，不从旧报告中的“下一步”开始执行。

| 要回答的问题 | 主入口 / 必要补充 |
| --- | --- |
| 现在做到哪里、接下来做什么？ | [当前实施状态](development/status.md) |
| 某个 HXA 的范围与验收是什么？ | [任务索引](development/roadmap.md) → 具体任务或完成记录 |
| 哪些建议还没有接受或启用？ | [候选需求与待裁决索引](development/candidate-decisions.md) |
| 先做功能还是重构、何时停止？ | [开发原则与推进策略](development/feature-refactor-strategy.md) |
| 所有者有哪些开发偏好、哪些经验可跨项目复用？ | [个人工程手册](development/engineering-playbook.md)；解释取舍与案例，不替代项目规则或当前任务 |
| 如何实施和交接？ | [实施指南](development/implementation-guide.md)、[开发控制面](development/README.md) |
| 如何减少开发阅读量？ | [CodeGraph 与按需导航](development/context-navigation.md)；已知任务直达相关章节/源码，不默认重读全仓 |
| 现有结构与目标结构有什么不同？ | [架构入口](architecture/README.md)、[当前总体架构](architecture/overview.md)、[Harness 重构方案](architecture/harness-refactor-plan.md) |
| 为什么作出这个长期决定？ | [按主题组织的 ADR](adr/README.md) |
| 产品能做什么、怎么使用？ | [产品入口](product/README.md)、[需求](product/requirements.md)、[操作体验](product/task-experience.md) |
| 如何验证、内测和准备发行？ | [开发环境](development/environment.md)、[公共验收](development/verification-matrix.md)、[Agent Eval](development/agent-eval.md)、[发行就绪条件](development/release-readiness.md) |
| 调研依据和竞品结论在哪里？ | [研究入口](research/README.md)、[竞品总览](product/competitive-landscape.md)、[外部参考](references/README.md) |
| 哪次测试通过、以前为什么这样设计？ | [完成记录索引](completion-records/index.md)、[证据入口](evidence/README.md)、[历史研究](evidence/research-history/README.md) |

## 文档职责

- `product/`：需求、用户体验、定位和竞品分析，见 [product/README.md](product/README.md)；研究结论不直接成为实现要求。
- `architecture/`：跨模块职责与当前契约，见 [architecture/README.md](architecture/README.md)；计划功能明确标注交付状态。
- `adr/`：按功能保存**当前有效长期决策**；已接受增量直接合入所属 Decision 正文，`Decision history` 只解释变更，不依赖读者叠加补丁，不保存版本化 ADR 链。
- `development/`：当前开发控制面，见 [development/README.md](development/README.md)；`status.md` 是唯一当前状态/下一步入口，`roadmap.md` 是 HXA 索引，`tasks/HXA-NNN.md` 只保存未完成的代码/产品开发工作规格；纯文档整理/Research 综合不单独创建 HXA。
- `completion-records/`：每个已完成 HXA 的交付快照、命令与证据，不作为今天的运行指令。
- `evidence/`：验收过程、外部材料和诊断快照，不记录当前 Agent 分工。
- `research/`：`modules/` 保存综合判断，`topics/` 保存有日期/范围的专项输入；已被承接的旧设计归历史证据。见 [research/README.md](research/README.md)，研究结论不是任务授权。`references/` 只保存外部来源与机制参考，产品使用说明归 `product/`。
- `bug-fixes/`、`postmortems/`：只保存值得跨任务长期复用的缺陷机制/事故；普通当轮修复归 HXA 记录，不默认一 bug 一文件。
- 根目录 `reviews/`：只保存**时间点项目审查报告**；实施计划、模型交接、Wave playbook 完成后必须把有效内容迁入 ADR/HXA/evidence 并删除。

同一信息只保留一个当前入口：**当前状态/下一步在 status，工作范围在当前 HXA，长期决定在功能 ADR，执行结果在 completion record，审查证据在 reviews/evidence。** 不再维护独立 next-work-plan 或按模型命名的 handoff/playbook。旧 Agent 的工作树、模拟器占用和会话分工不约束后续接手。编码须遵守根目录 [AGENTS.md](../AGENTS.md)。

一次性交接完成后，独有证据合入完成记录或 evidence，再删除指令文件及更新入链；不保留重定向占位。历史材料不再维护‘当前状态’，过时的阻塞或分工须在页首标明历史适用时间。

## 命名与整理规则

长期维护页使用稳定主题名，例如 `harness-refactor-plan.md`、`release-readiness.md`，日期放正文；固定时点评审、研究和验收保留日期。ADR/HXA 保留既有编号，不为统一文件名重编身份。

“旧”分三类：内容有效但名称不清晰 → 改名并修引用；原观点被现行决定承接 → 历史归档并在页首说明；纯重复且无独有证据 → 合并后删除重复正文。日期早、文件长或数量多，都不是删除证据的理由。

移动文件同步修复入链、出链和文档内仓库路径，不保留只写“请跳转”的空壳。大专题有独立目的时保留分文件导航，不合成一个巨型报告。

源码说明实际行为，ADR 说明接受的契约，两者不一致须记录差异，不能用其中一个静默覆盖另一个。较新的研究或外部资料不自动授予实现权限。

改名、归档、正文承接及检查记录统一见[文档整理记录](evidence/development/documentation-convergence-2026-09-29.md)；日期化记录区分各轮结果，不作为新的当前排期。
