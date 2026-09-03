# Helix 实施状态

更新时间：2026-09-03

## Current summary

| 维度 | 当前事实 |
| --- | --- |
| 已验证范围 | M0～M4：HXA-001～003、010～016、020～028、030～049 |
| 已落地骨架 | Provider 配置与三协议适配、聊天流、Capability/Policy/Approval、Dispatcher/Scheduler、batch-safe Turn Coordinator、多步 Tool Loop 与持久审计、Workspace/文件工具（含受限 zip/tar Archive）、SAF 导入导出适配层与 developer All-files scope、会话附件导入/持久化与 UTF-8 文本物化（ADR-0014 首批） |
| 尚无业务执行器 | Browser、MCP、Skill、QuickJS、PRoot/CLI、Accessibility、Root |
| 当前检查点 | 无进行中任务；下一项为 M5 / HXA-050 Zipline Spike |
| 发布状态 | 仅开发/测试产物；尚无完成签名与发布验收的稳定版本 |

## Completed

已完成任务的详细实现、命令、exit code、设备和限制只在 [HXA 完成记录](../completion-records/README.md) 与各记录中维护；本文件不复制字段级历史。

| 里程碑 | 已完成范围 | 权威证据 |
| --- | --- | --- |
| M0 | HXA-001～003：工程、质量门禁、AppContainer/导航壳 | [M0 完成记录](../completion-records/M0.md)、[逐 HXA 索引](../completion-records/README.md) |
| M1 | HXA-010～016：领域状态、Plan/Goal、Room、恢复、Context Builder | [逐 HXA 索引](../completion-records/README.md) |
| M2 | HXA-020～028：Secret/Provider、三协议、能力探测、模板、聊天 UI | [逐 HXA 索引](../completion-records/README.md) |
| M3 | HXA-030～039：Tool/Schema/Capability/Policy/Approval、Dispatcher/Scheduler、Tool Loop、模型流状态与 batch-safe Turn Coordinator | [逐 HXA 索引](../completion-records/README.md) |
| M4 | HXA-040～049：Workspace/scope、原子文件存储、首批文件工具、copy/move/trash、SAF adapter 与 developer All-files scope、文件管理 UI（Workspace 恒可写、developer all-files 根只读、SAF 接线与导入导出 UI 显式推迟）、受限 zip/tar archive/extract、会话附件导入/持久化与 UTF-8 文本物化（ADR-0014 首批，fail-closed 再校验 + egress 目标/附件集绑定），以及全项目审查收敛（ChatService 每会话 turn 模型 + 单测骨架、删除 WorkspacePath 死代码） | [逐 HXA 索引](../completion-records/README.md)、[文件工具 Bug 修复记录](../bug-fixes/2026-09-02-file-tool-safety-boundaries.md) |

架构决定的状态与理由见 [ADR 目录](../adr/README.md)；跨里程碑复核、事实修正和历史取舍见[文档复核记录](../history/documentation-review.md)。“有完成记录”不等于当前发布能力，当前可用边界只看本文件的摘要、接口和限制。

## In progress

- 无。

## Next task

- M5 / HXA-050 Zipline Spike：在 Android 29/34/36、arm64/x86_64 上验证 evaluate、memoryLimit、InterruptHandler、`Function`/constructor 禁用、大调用线程 stack、16 KiB page 与 `bindIsolatedService` 唯一实例回收，按 ADR 约定产出决定与证据。

## Blocked

- 无。

## Current interfaces

- `AppContainer` 已组合 Provider、ChatService、Capability/Policy/Approval 与 Tool Pipeline/Scheduler；`ShellRepository` fake 只服务早期壳层 route。
- 导航 route 只承诺稳定入口；会话与 Provider 已有实现，其余页面是否可用以对应 HXA 完成记录为准。
- 生产 Tool Registry 已包含 `time.now` 与 Workspace 文件工具 `read`/`write`/`edit`/`files.*`，其中包括受限 `files.archive`/`files.extract`；当前 scope 为 app-private 与 developer All-files，SAF tree 尚未接入工具 scope resolver。
- QuickJS 规划使用主 App 内的 `isolatedProcess` Service；PRoot 规划使用同签名、独立 applicationId/UID 的 Runtime APK，并通过 signature-protected Binder/PFD IPC 连接。
- accepted [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)将 Standard 定义为 Google Play、国内 Android 应用商店和官网的完整产品形态；consumer/developer 仍只是当前构建事实。HXA-122 尚未决定稳定主 applicationId、渠道命名与升级路径，也没有外部 release artifact。
- CLI 订阅后端若实施，将使用另一个有 INTERNET 的独立 UID，凭据由官方 CLI 持有。
- MCP 规划为 Client-only；Skill 规划按 `SKILL.md` 开放规范渐进加载，二者当前均未实现。
- `read`/`write`/`edit`/`files.*` 已实现；`bash` 仍只是规划中的稳定短工具名。所有工具继续受 scope、Policy、Approval 和执行域约束。

## Known limitations

- GitHub Actions 已有远端运行证据，但 workflow 不运行模拟器/真机测试；现有 artifact 是短期 debug 安装包，不是签名 release 或发布验收证据。
- Browser、MCP、Skill、QuickJS、PRoot/CLI、Accessibility、Root 等用户可感知执行能力尚未实现。
- 设备矩阵：consumer 变体在 API 36 arm64-v8a 模拟器全绿（HXA-048 实测 47/47；迁移修复后复测仍 47/47 无回归）。API 29 已实测，HXA-048 发现的已知失败均已修复：`ProductionMigrationDeviceTest` ×2（`MIGRATION_1_2` 使用 `RENAME COLUMN`，需 SQLite ≥3.25/API 30+，Android 10 上 v1→v2 迁移会崩，既有产品缺陷）已改用 copy-and-swap 修复（见 [Bug 修复记录](../bug-fixes/2026-09-03-room-migration-sqlite-rename-column.md)）；`GoalReminderTest` ×2（POST_NOTIFICATIONS 授予 helper 未对 SDK<33 设防）已修复（helper 在 API<33 时 no-op）；`FilesScreenTest` ×6 的根因**不是**“API 29 模拟器慢 / 超时抖动”，而是两处生产代码调用了 API 29 平台缺失的 `java.*` 方法（`WorkspaceArtifactStore.listDir` 的 `Stream.toList()`（API 31+）与 `ReadWindow.read` 的 `InputStream.skipNBytes`），已修复并复测：API 29 47/47、API 36 47/47 无回归（见 [Bug 修复记录](../bug-fixes/2026-09-03-jvm-stdlib-calls-missing-on-api29.md)）。以上均不再是已知失败；`GoalReminderTest` 在双模拟器并发全量负载下存在一次性通知时序 flake（单独重跑通过），需单独跟进。多 ABI 与真机矩阵仍待执行。
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
- 文件管理 UI（HXA-046）当前边界：Workspace 恒可写；developer all-files 根本里程碑**只读**（区域守卫拒绝非 workspace 布局的变更，浏览/排序/预览/分享可用，重命名/复制/移动/trash 隐藏）；HXA-046 明确推迟的 persisted SAF tree scope 接线与用户导入/导出入口现已分别由 HXA-057/HXA-058 承接，仍未实现。
- 聊天附件（HXA-049 首批）现为**有界 UTF-8 文本**：UI/`ChatService` 可暂存并经系统 picker 导入单文件到当前会话 app-private Workspace（复用 HXA-044 `SafImportPipeline`，单文件导入不依赖 SAF tree grant），发送/重试前对 `message_attachments` 绑定的 SHA-256 快照 fail-closed 再校验，egress disclosure 同时绑定精确 Provider/origin 与被枚举的附件集合；只有经 probe 确认的 UTF-8 txt/md/csv/json 进入模型上下文，且以带来源、`UNTRUSTED` 标记与整文件哈希的有界（≤8 KiB 内联视图）context item 呈现，超限经 `read(offset,maxBytes)` 分块，绝不 base64 进上下文。**仍不支持**：图片（production image resolver 明确拒绝；底层 `ImageReference` 与三协议图片编码已存在但不构成产品支持）、系统分享草稿与端到端硬化（规划 HXA-055～056）；UTF-16、PDF/PPT/DOC、音频、视频及其他二进制只归入 ADR-0014 的封闭 category 并稳定返回 unsupported，不做文档/媒体解析、渲染、OCR 或 Provider upload。`read` 返回 base64 也不等于模型理解，以上均受 accepted [ADR-0014](../adr/0014-session-attachment-materialization.md)约束。
- Workspace 文件层两项低优先特性经 HXA-048 评估后**维持现状**（目录规模 / 大目录成为实际瓶颈前不改）：`WorkspaceQuota.usageBytes` 每次文件操作全量 walk scope 目录报告当前用量；`files.listDir` 先物化排序整个目录再分页、app 层 TIME/SIZE 排序只作用于按名截断前缀。
- 全项目审查判定为**有意设计、不改动**：`RecoveryCoordinator.canResumeTurn`/`wakeAllowed` 是有测试覆盖、为未实现恢复/继续 UI 预留的门禁 seam；`tools/android`、`tools/browser` 空子项目是 M6 占位模块（验收矩阵与 roadmap M6 已规划其测试）。
