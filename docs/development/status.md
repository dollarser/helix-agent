# Helix 实施状态

更新时间：2026-09-03

## Current summary

| 维度 | 当前事实 |
| --- | --- |
| 已验证范围 | M0～M4：HXA-001～003、010～016、020～028、030～046 |
| 已落地骨架 | Provider 配置与三协议适配、聊天流、Capability/Policy/Approval、Dispatcher/Scheduler、batch-safe Turn Coordinator、多步 Tool Loop 与持久审计、Workspace/文件工具、SAF 导入导出适配层与 developer All-files scope |
| 尚无业务执行器 | Browser、MCP、Skill、QuickJS、PRoot/CLI、Accessibility、Root |
| 当前检查点 | 无进行中任务；下一项为 M4 / HXA-047 Archive 工具（受限 zip/tar 创建与解压、Zip Slip/symlink/膨胀比防御） |
| 发布状态 | 仅开发/测试产物；尚无完成签名与发布验收的稳定版本 |

## Completed

已完成任务的详细实现、命令、exit code、设备和限制只在 [HXA 完成记录](../completion-records/README.md) 与各记录中维护；本文件不复制字段级历史。

| 里程碑 | 已完成范围 | 权威证据 |
| --- | --- | --- |
| M0 | HXA-001～003：工程、质量门禁、AppContainer/导航壳 | [M0 完成记录](../completion-records/M0.md)、[逐 HXA 索引](../completion-records/README.md) |
| M1 | HXA-010～016：领域状态、Plan/Goal、Room、恢复、Context Builder | [逐 HXA 索引](../completion-records/README.md) |
| M2 | HXA-020～028：Secret/Provider、三协议、能力探测、模板、聊天 UI | [逐 HXA 索引](../completion-records/README.md) |
| M3 | HXA-030～039：Tool/Schema/Capability/Policy/Approval、Dispatcher/Scheduler、Tool Loop、模型流状态与 batch-safe Turn Coordinator | [逐 HXA 索引](../completion-records/README.md) |
| M4 | HXA-040～046：Workspace/scope、原子文件存储、首批文件工具、copy/move/trash、SAF adapter 与 developer All-files scope、文件管理 UI（浏览/排序/多选/预览/冲突询问/长操作进度与取消/trash 恢复清空/FileProvider 分享；Workspace 恒可写、developer all-files 根只读、SAF 接线与导入导出 UI 显式推迟） | [逐 HXA 索引](../completion-records/README.md)、[文件工具 Bug 修复记录](../bug-fixes/2026-09-02-file-tool-safety-boundaries.md) |

架构决定的状态与理由见 [ADR 目录](../adr/README.md)；跨里程碑复核、事实修正和历史取舍见[文档复核记录](../history/documentation-review.md)。“有完成记录”不等于当前发布能力，当前可用边界只看本文件的摘要、接口和限制。

## In progress

- 无。

## Next task

- M4 / HXA-047 Archive 工具：受限 zip/tar 创建与解压；防 Zip Slip、symlink/device、文件数、总大小和膨胀比。

## Blocked

- 无。

## Current interfaces

- `AppContainer` 已组合 Provider、ChatService、Capability/Policy/Approval 与 Tool Pipeline/Scheduler；`ShellRepository` fake 只服务早期壳层 route。
- 导航 route 只承诺稳定入口；会话与 Provider 已有实现，其余页面是否可用以对应 HXA 完成记录为准。
- 生产 Tool Registry 已包含 `time.now` 与 Workspace 文件工具 `read`/`write`/`edit`/`files.*`；当前 scope 为 app-private 与 developer All-files，SAF tree 尚未接入工具 scope resolver。
- QuickJS 规划使用主 App 内的 `isolatedProcess` Service；PRoot 规划使用同签名、独立 applicationId/UID 的 Runtime APK，并通过 signature-protected Binder/PFD IPC 连接。
- accepted [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)将 Standard 定义为 Google Play、国内 Android 应用商店和官网的完整产品形态；consumer/developer 仍只是当前构建事实。HXA-122 尚未决定稳定主 applicationId、渠道命名与升级路径，也没有外部 release artifact。
- CLI 订阅后端若实施，将使用另一个有 INTERNET 的独立 UID，凭据由官方 CLI 持有。
- MCP 规划为 Client-only；Skill 规划按 `SKILL.md` 开放规范渐进加载，二者当前均未实现。
- `read`/`write`/`edit`/`files.*` 已实现；`bash` 仍只是规划中的稳定短工具名。所有工具继续受 scope、Policy、Approval 和执行域约束。

## Known limitations

- GitHub Actions 已有远端运行证据，但 workflow 不运行模拟器/真机测试；现有 artifact 是短期 debug 安装包，不是签名 release 或发布验收证据。
- Browser、MCP、Skill、QuickJS、PRoot/CLI、Accessibility、Root 等用户可感知执行能力尚未实现。
- M0 只在 API 36 arm64-v8a 模拟器完成设备验收；API 29、多 ABI 和真机矩阵仍待执行。
- 多渠道能力保留分发目前只是 ADR-0013 的产品决定；核心任务矩阵、权限申报、listing、最终 applicationId/签名与真实商店审核均未完成。
- 当前 developer manifest 包含 Advanced 能力声明；即使默认关闭，Android 系统设置仍可能列出相关服务或权限。最终各渠道 manifest 由 HXA-122 按实测和审核证据收口。
- libsu/JitPack 仍是 HXA-094 的未来供应链决策点；当前仓库没有加入相关依赖。
- PRoot/CLI、结构化 Git UI 和持久 Git Workspace 尚未实现；ADR-0008 accepted 前不存在跨 Job `.git` 一致性、remote Git 或凭据能力。
- 低内存/后台/热限制实信号、可配置 TurnBudgets UI 和 Plan/Goal 工具入口归 HXA-099；child Agent、Agent graph 与 Workflow 未实现，ADR-0009 仍是 proposed。
- `TurnCoordinator` 是当前生产 Turn/ModelCall 协调器，旧 `TurnReducer` 仅保留兼容；`ChatService` 仍聚合 send gate、ToolCall 准备、Timeline/Approval 投影和 UI facade，后续按真实测试 seam 渐进提取。
- M0 壳层中文 Compose 文本尚未资源化；简体中文/英文资源和硬编码扫描归 HXA-067。
- 全量 developer 设备套件仍有 4 个 HXA-036 审批/审计测试因 seed 竞态失败；HXA-045 自身 `AllFilesDeviceTest` 6 例通过。根因修复需单独跟进，不能把局部设备通过写成全量通过。
- ADR-0012 的持久规则、Trusted Workspace 和精确批量批准尚未实现；生产规则集为空时回退逐次审批。Tasker/Auto.js、Shizuku/ADB 仅为未排期候选。
- 文件能力仍有三项边界：mutation 工具统一 L2、trash 长路径可能触发 `NAME_MAX`、超时 abandon 可能留下计入 scope 配额的临时文件；age-based reclaim 与 SAF scope 接线归后续文件管理工作。
- 文件管理 UI（HXA-046）当前边界：Workspace 恒可写；developer all-files 根本里程碑**只读**（区域守卫拒绝非 workspace 布局的变更，浏览/排序/预览/分享可用，重命名/复制/移动/trash 隐藏）；SAF scope 接线（persisted tree grant 进 scope 解析）与用户导入/导出 UI 入口在 HXA-046 中**显式推迟**、归后续跟进（roadmap 的 HXA-046 任务书不含 SAF；HXA-044/045 完成记录曾预期到 HXA-046）。
- `ChatService` 的 turn 准入同时使用全局 `activeTurnJob`/`turnGate` 与按 turn 的 `turnCancels`，而 `turnGate` 的 KDoc 声称每会话只有一个 active turn；并发真相未固化，且缺少 ChatService 单元测试骨架（当前仅仪器测试覆盖 turn 行为）。统一为每会话模型并补测试归 HXA-048。
- Workspace 文件层三项收敛归 HXA-048：`WorkspaceQuota.usageBytes` 每次文件操作全量 walk scope 目录报告当前用量（低优先，目录规模成实际瓶颈前可维持现状）；删除生产无引用的 `WorkspacePath` 死代码（连同其测试、`FileScopePathTest` 中镜像它的 oracle 与 `PathSyntax` KDoc 引用）；`files.listDir` 先物化排序整个目录再分页、app 层 TIME/SIZE 排序只作用于按名截断前缀（低优先）。
- 全项目审查判定为**有意设计、不改动**：`RecoveryCoordinator.canResumeTurn`/`wakeAllowed` 是有测试覆盖、为未实现恢复/继续 UI 预留的门禁 seam；`tools/android`、`tools/browser` 空子项目是 M6 占位模块（验收矩阵与 roadmap M6 已规划其测试）。
