# 验收与诊断证据

本目录保存**时间点证据**：验收结果、诊断快照、外部材料、分支整合记录和历史验证计划。它们不是当前任务指令，也不自动代表今天仍通过。

当前进展看 [development/status.md](../development/status.md)，未完成任务看 [development/roadmap.md](../development/roadmap.md)，长期契约看 [ADR](../adr/README.md)。旧工作树、设备 serial、owner、命令和模型分工只用于解释当时证据，不能直接复用。

## 分类入口

- [development/](development/README.md)：开发/验收/整合/恢复/调查的主要证据库。
- [development/verification-plans/](development/verification-plans/README.md)：历史设备与长稳验证计划；不是当前设备授权。
- [connectors/import-materials.md](connectors/import-materials.md)：Connector 来源/导入材料。
- [diagnostics/hxa185-device-protocol.md](diagnostics/hxa185-device-protocol.md)：专项设备协议诊断。
- [bug-fixes/](../bug-fixes/README.md)：值得跨任务复用的已修复根因与回归机制。
- [completion-records/](../completion-records/index.md)：每个已完成 HXA 的交付时点证据，是查具体任务结果的首选入口。

## 近期高价值入口

- [2026-09-22 分支整合验证](development/branch-integration-2026-09-22.md)
- [199/206 联合验收](development/acceptance-199-206-2026-09-21.md)
- [已完成交接归属](development/completed-handoffs-2026-09-22.md)
- [2026-09-22 文档、审查与遗留内容收敛](development/document-review-convergence-2026-09-22.md)
- [2026-09-22 脚本入口与实验构建整理](development/repository-hygiene-2026-09-22.md)
- [2026-09-22 引擎与浏览器修复整合](../bug-fixes/2026-09-22-engine-browser-convergence.md)
- [早期文档审查历史](development/documentation-review-history-2026-09-02.md)

证据文件不维护“当前 backlog”。如果历史 evidence 中出现 `todo`、`pending`、旧 HXA owner 或旧设备状态，先看文件日期/基线，再回到 status/HXA 判断今天是否仍成立。
