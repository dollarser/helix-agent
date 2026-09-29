# 架构文档入口

本目录说明跨模块**当前职责与结构边界**；长期取舍与授权看 [ADR](../adr/README.md)，实际交付状态看 [development/status.md](../development/status.md)。架构图或目标结构不等于功能已实现。

主入口：

- [overview.md](overview.md)：总体职责与调用路径。
- [agent-modes.md](agent-modes.md)：Chat/Plan/Act/Goal 模式与执行入口。
- [local-code-execution.md](local-code-execution.md)：本地执行域与进程边界。
- [terminal.md](terminal.md)：终端与后台 Job。
- [providers.md](providers.md)：Provider 协议、连接与订阅通路。
- [extensions.md](extensions.md)：MCP、Skill、Connector、A2A 总体扩展边界。
- [connector-portability.md](connector-portability.md)：Connector 可移植性。
- [mobile-tool-orchestration.md](mobile-tool-orchestration.md)：移动端工具协调。
- [android-platform-capabilities.md](android-platform-capabilities.md)：Android 系统能力边界。
- [session-export.md](session-export.md)：会话导出结构。

目标设计与重构：

- [本机 Harness 详细重构方案](harness-refactor-plan.md)：历史讨论与当前源码对照、Agent Core/工具治理/Execution Host 分层、逐卡迁移、竞品依据和验收。R1 当前范围看 HXA-231；后续设计不等于实现授权。
- [Plugin Platform 专项设计](plugin-platform-plan.md)：保留包格式与 Mobile Use MVP 的详细设计输入；通用生命周期目标以 Harness 方案 §17 为唯一正文，旧问题列表不是当前未实现清单。
- [功能与重构的推进原则](../development/feature-refactor-strategy.md)：选择顺序与停止条件，不重复上述技术契约。

同一长期规则不要同时在 architecture 与 ADR 维护两份完整正文：architecture 解释结构，ADR 保存决策，status/HXA 保存当前实施阶段。
