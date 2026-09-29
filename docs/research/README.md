# 研究入口

研究按综合模块、专题输入、历史快照分层。目录整理核对：2026-09-29，该次整理只核对仓库归属与交付引用。随后补充的工具曝光专题单独核验了相关外部一手资料；各专题分别标注日期与范围，不据此宣称全部竞品研究已刷新。

Research 不是实现授权，也不是 current backlog。使用顺序：

1. 当前事实/下一步：[`development/status.md`](../development/status.md)；
2. 当前工作范围：active HXA；
3. 长期契约：[`ADR`](../adr/README.md)；
4. 方案、竞品、冲突裁决：[`modules/`](modules/README.md)；
5. 需要追查旧基线/旧 Agent 原始报告：[`evidence/research-history/`](../evidence/research-history/README.md)。

## 三类材料

| 层级 | 入口 | 阅读目的 |
| --- | --- | --- |
| 综合研究 | [modules/](modules/README.md) | 按产品、Core、上下文、UI、工具、Runtime、Eval 和能力方法查找综合判断 |
| 专题研究 | [topics/](topics/README.md) | 工具曝光/发现、插件/Mobile Use 与异步 Job 的专门比较，保留研究日期与证据边界 |
| 历史研究 | [research-history/](../evidence/research-history/README.md) | 追查已被实现/方案承接的原始结论，不恢复其旧排期 |

## 已被当前文档承接的主题

| 研究主题 | 当前入口 |
| --- | --- |
| Core 生命周期、successor Turn | [Agent ADR](../adr/agent/001-turn-coordination.md)、[HXA-220](../completion-records/HXA-220.md) / [HXA-223](../completion-records/HXA-223.md) |
| Conversation-first、活动展示、Memory | [HXA-228](../completion-records/HXA-228.md)、[HXA-229](../completion-records/HXA-229.md)、[Memory 产品边界](../product/memory.md) |
| Workspace 与设备内模型 | [Workspace](../product/workspace.md)、[本地模型](../product/local-models.md)；不再以旧研究判断它们尚未立项 |
| 自主图片读取与工具视觉回填 | [使用说明](../product/image-reading.md)、[HXA-225](../completion-records/HXA-225.md)；手机整屏截图仍是独立候选 |
| 工具数量、MCP/Skill 大目录与按需发现 | [工具综合研究 §2](modules/04-tools-browser-and-extensions.md#2-tool-exposure)、[2026-09-29 专题](topics/tool-exposure-and-discovery-2026-09-29.md)；区分当前源码、历史评测和待接受策略 |
| 后续能力架构与实施顺序 | [Harness 方案](../architecture/harness-refactor-plan.md)、[开发原则](../development/feature-refactor-strategy.md)、[候选索引](../development/candidate-decisions.md) |

以上只提供承接关系，当前完成/验证范围仍看 status 与具体证据；不能把部分交付扩大为全部能力完成。

## 冲突裁决与维护

源码与制品证据解释实际行为，accepted ADR 解释接受的契约；两者不一致时明确记录差异，不静默用源码改写决定。外部一手资料只支持其记载版本和范围；新研究不自动覆盖有效决定。

证据不足时保留待核实项，不仅按日期选出一个“最新正确答案”。一个问题的当前综合判断只维护在一个模块，其他页面链接；专题保留独有比较，历史页保留原话。进入实施仍须已有授权任务，不在研究文件里维护第二份 backlog。
