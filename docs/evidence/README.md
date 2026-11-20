# 验收与诊断证据

本目录保存**时间点证据**：验收结果、诊断快照、外部材料、分支整合记录和历史验证计划。它们不是当前任务指令，也不自动代表今天仍通过。

当前进展看 [development/status.md](../development/status.md)，未完成任务看 [development/roadmap.md](../development/roadmap.md)，长期契约看 [ADR](../adr/README.md)。旧工作树、设备 serial、owner、命令和模型分工只用于解释当时证据，不能直接复用。

## 分类入口

- [development/](development/README.md)：开发/验收/整合/恢复/调查的主要证据库。
- [research-history/](research-history/README.md)：已被后续决定或设计承接的历史研究，保留原基线与推理，不恢复旧排期。
- [development/verification-plans/](development/verification-plans/README.md)：历史设备与长稳验证计划；不是当前设备授权。
- [connectors/import-materials.md](connectors/import-materials.md)：Connector 来源/导入材料。
- [diagnostics/hxa185-device-protocol.md](diagnostics/hxa185-device-protocol.md)：专项设备协议诊断。
- [bug-fixes/](../bug-fixes/README.md)：值得跨任务复用的已修复根因与回归机制。
- [completion-records/](../completion-records/index.md)：每个已完成 HXA 的交付时点证据，是查具体任务结果的首选入口。

证据文件不维护“当前 backlog”。如果历史 evidence 中出现 `todo`、`pending`、旧 HXA owner 或旧设备状态，先看文件日期/基线，再回到 status/HXA 判断今天是否仍成立。

## 本地原始输出的保留边界

2026-11-19 按 Owner 要求清理可重建产物、增量缓存及历史运行输出。旧文档中的 `build/...`、截图和 logcat 路径仅标识当时来源，不保证原件仍存在，也不代表当前代码通过。发布包及身份/签名/校验、符号映射的独有归档保留；源码、fixture、黄金快照、基准输入和恢复备份不属清理对象。后续验证可以重新生成本地产物，不恢复旧证据。

可复用结论：发布与旧版更新检测见 [v0.0.12 发布记录](development/v0.0.12-publication-2026-11-18.md)；账号人工确认见 [HXA-307](../development/tasks/HXA-307.md#后续人工验收确认)；附件及 PDF 验收边界见 [HXA-049](../completion-records/HXA-049.md#2026-11-18-后续收尾)；CI 超时、表达式失败和构建耗时分析见 [CI 复核](development/ci-137-timing-2026-11-19.md)。具体数量和未验证范围以对应记录为准。
