# Helix Android 本机 Agent

Helix 是一个 Android 优先、能力优先、手机本地执行的个人 Agent，首要面向开发者与效率用户。模型可通过网络 API 或用户配置的自建模型服务调用，但 Agent Runtime、权限判断、工具调用、浏览器、文件工作区、代码执行、审批和审计均在手机上运行。

当前目标是让一个 Helix 产品同时覆盖 Google Play、国内 Android 应用商店和官网直接分发：所有安装默认运行完整的 `STANDARD`，需要时在同一安装内显式进入 `ADVANCED`。渠道 artifact 只按提交时的真实政策做最小能力差异，不把 Standard 做成聊天壳或能力阉割版；PRoot/CLI 仍是按需 companion Runtime。远程 Worker、云端沙箱、桌面配对与 HarmonyOS 客户端暂不实现。

## 文档入口

完整目录、阅读路径和维护规则统一在[文档中心](docs/README.md)。日常开发优先使用以下入口：

- [当前实施状态](docs/development/status.md)：唯一当前状态源。
- [产品需求](docs/product/requirements.md)与[市场、用户和商业化分析](docs/product/market-users-and-commercialization.md)：能力范围、目标用户、首发场景和商业假设。
- [开发路线](docs/development/roadmap.md)与[验收矩阵](docs/development/verification-matrix.md)：HXA 的范围与验收。
- [总体架构](docs/architecture/overview.md)与[Provider、MCP、A2A、Skills 和模式方案](docs/architecture/provider-mcp-skills-modes.md)：规范性设计与扩展协议边界。
- [完成记录](docs/completion-records/README.md)、[Bug 修复](docs/bug-fixes/README.md)与[ADR](docs/adr/README.md)：交付证据、缺陷根因与决策理由。

当前已验证范围、`In progress`、`Next task` 和能力限制只在[当前实施状态](docs/development/status.md)维护；本 README 不复制随 HXA 变化的快照。

## 稳定边界

- 产品运行在 Android 单机；基础任务不依赖电脑、云端 Worker 或远程沙箱。
- 原生 Tools、QuickJS、PRoot 与 CLI 分属不同执行域；本机执行不等于在主进程执行，也不等于虚拟机。
- 所有工具进入同一条 schema、Capability、Policy、Approval、执行、验证与审计管线；系统权限或 Runtime 权限不能替代 ToolCall 授权。
- `STANDARD` 是各分发渠道的完整产品形态，`ADVANCED` 在同一产品内开放额外能力；Trusted Workspace、有界长期规则与精确批量批准的边界见 [ADR-0012](docs/adr/0012-capability-first-advanced-grants.md)。
- 分发渠道、构建 flavor 与运行时安全配置互不等同；能力保留的分发决定见 [ADR-0013](docs/adr/0013-standard-store-capability-preserving-distribution.md)。
- 远程 Worker、云端沙箱、桌面配对、HarmonyOS、自动支付和无人确认的对外发送不在当前范围；M7 规划的 A2A 仅作为用户配置的远程 Agent Client，不把远端 Agent 变成 Helix Worker，也不开放 A2A Server、递归多 Agent 或任意 peer 通信；Tasker/Auto.js 与 Shizuku/ADB 仅是未排期研究候选。

具体技术选型、模块边界、applicationId、Runtime 生命周期和未来能力以[架构文档](docs/architecture/overview.md)、[产品需求](docs/product/requirements.md)及[有效 ADR](docs/adr/README.md)为准，避免在入口文档重复维护。

## 给实现者的第一条规则

不要直接从聊天页面堆功能。按[开发路线](docs/development/roadmap.md)的任务顺序实施，并在每项任务完成后执行该 HXA 列出的验收命令。

涉及 Android、依赖版本、服务登录方式、渠道政策和第三方仓库的事实会随时间变化；实施或发布前必须重新核实，不能把调研快照当成当前保证。
