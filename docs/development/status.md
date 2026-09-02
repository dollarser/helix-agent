# Helix 实施状态

更新时间：2026-09-03

## Current summary

| 维度 | 当前事实 |
| --- | --- |
| 已验证范围 | M0～M4：HXA-001～003、010～016、020～028、030～047；M5：HXA-050（Zipline/QuickJS spike）、HXA-051（生产执行协议 isolated Service/Binder）、HXA-052（IIFE wrapper/JSON ABI/限制，API 29/36 arm64-v8a）、HXA-053（QuickJS 执行接 Tool/Policy/审计：`code.javascript.run` 全链路 + 11 状态回填 + §4.8 脱敏审计 + 完整代码审批，API 29/36 arm64-v8a；HXA-048 收敛项在本分支未执行） |
| 已落地骨架 | Provider 配置与三协议适配、聊天流、Capability/Policy/Approval、Dispatcher/Scheduler、batch-safe Turn Coordinator、多步 Tool Loop 与持久审计、Workspace/文件工具（含受限 zip/tar Archive）、SAF 导入导出适配层与 developer All-files scope、QuickJS 生产执行模块（`:runtime:quickjs`，ADR-0015 选定 Zipline 1.27.0：isolated Service/Binder 通道 + §3.2 IIFE wrapper JSON ABI）+ `code.javascript.run` 工具（consumer/developer 双变体接线） |
| 尚无业务执行器 | Browser、MCP、Skill、PRoot/CLI、Accessibility、Root |
| 当前检查点 | M5 / HXA-053 QuickJS 执行接 Tool/Policy/审计完成（`code.javascript.run`：CODE_EXECUTION/L2/LOCAL_QUICKJS 单并发 lane、模型仅见 code+input 且 limits 不入 schema、11 状态稳定回填绝无假成功、§4.8 脱敏审计经 dispatcher 单一 emitter、审批卡完整代码块 + input 摘要 + 联网：否 + 固定 limits + 代码 SHA-256 短摘要；JVM 506 例（quickjs 74 + framework 136 + app 148×2）+ 5 例 androidTest × 两台设备全过）；下一项为 M5 / HXA-054 QuickJS 攻击套件与 E2E |
| 发布状态 | 仅开发/测试产物；尚无完成签名与发布验收的稳定版本 |

## Completed

已完成任务的详细实现、命令、exit code、设备和限制只在 [HXA 完成记录](../completion-records/README.md) 与各记录中维护；本文件不复制字段级历史。

| 里程碑 | 已完成范围 | 权威证据 |
| --- | --- | --- |
| M0 | HXA-001～003：工程、质量门禁、AppContainer/导航壳 | [M0 完成记录](../completion-records/M0.md)、[逐 HXA 索引](../completion-records/README.md) |
| M1 | HXA-010～016：领域状态、Plan/Goal、Room、恢复、Context Builder | [逐 HXA 索引](../completion-records/README.md) |
| M2 | HXA-020～028：Secret/Provider、三协议、能力探测、模板、聊天 UI | [逐 HXA 索引](../completion-records/README.md) |
| M3 | HXA-030～039：Tool/Schema/Capability/Policy/Approval、Dispatcher/Scheduler、Tool Loop、模型流状态与 batch-safe Turn Coordinator | [逐 HXA 索引](../completion-records/README.md) |
| M4 | HXA-040～047：Workspace/scope、原子文件存储、首批文件工具、copy/move/trash、SAF adapter 与 developer All-files scope、文件管理 UI（Workspace 恒可写、developer all-files 根只读、SAF 接线与导入导出 UI 显式推迟），以及受限 zip/tar archive/extract | [逐 HXA 索引](../completion-records/README.md)、[文件工具 Bug 修复记录](../bug-fixes/2026-09-02-file-tool-safety-boundaries.md) |
| M5 | HXA-050～053：Zipline 1.27.0 QuickJS spike（evaluate/memoryLimit/InterruptHandler/eval+Function 封堵/大于 6 MiB 调用线程栈/bindIsolatedService 唯一实例回收，各 25 例 androidTest 全过；16 KiB page 以四 ABI ELF PT_LOAD 对齐为替代证据；ADR-0015 accepted）+ 生产执行协议（isolated 非导出一次性 Service、手搓 onTransact execute/interrupt/info、64 KiB inline + PFD、deadline/interrupt/cancel/watchdog 与 Binder-death 回收、11 状态闭合错误集，36 例 androidTest/设备 + 33 例 JVM）+ IIFE wrapper JSON ABI（doc §3.2 严格 IIFE 为唯一生产路径、host 编码输入 + 局部 `const input` 闭包注入、JSON 输入输出 + 双层输出边界、19 例 wrapper 逃逸攻击套件，55 例 androidTest/设备 + 62 例 JVM）+ 执行接 Tool/Policy/审计（`code.javascript.run`：CODE_EXECUTION/L2/LOCAL_QUICKJS 单并发 lane、模型仅见 code+input 且 limits 固定 §4.1 默认不入 schema、11 状态稳定回填绝无假成功、§4.8 脱敏审计经 dispatcher 单一 emitter、审批卡完整代码块 + input 摘要 + 联网：否 + 固定 limits + 代码 SHA-256 短摘要；JVM 506 例（quickjs 74 + framework 136 + app 148×2）+ 5 例 androidTest × 两台设备全过） | [HXA-050 完成记录](../completion-records/HXA-050.md)、[ADR-0015](../adr/0015-zipline-quickjs-execution-base.md)、[HXA-051 完成记录](../completion-records/HXA-051.md)、[HXA-052 完成记录](../completion-records/HXA-052.md)、[HXA-053 完成记录](../completion-records/HXA-053.md) |

架构决定的状态与理由见 [ADR 目录](../adr/README.md)；跨里程碑复核、事实修正和历史取舍见[文档复核记录](../history/documentation-review.md)。“有完成记录”不等于当前发布能力，当前可用边界只看本文件的摘要、接口和限制。

## In progress

- 无。

## Next task

- M5 / HXA-054 攻击和端到端测试：无限循环、内存、输出洪泛、eval/fetch/require、进程崩溃、Binder 大输入、取消竞态和 verified artifact（基于 HXA-053 接好的 `code.javascript.run` 与 `JsExecutionClient`；触发/审计/E2E 入口见 [HXA-053 完成记录](../completion-records/HXA-053.md)）。
- M4 / HXA-048 全项目审查后续收敛仍待执行（本分支按指令直接执行 HXA-050/051/053）：统一 ChatService 每会话 turn 模型，并按实测决定 Workspace quota 与大目录 list/search 是否需要优化；删除确认无生产引用的 `WorkspacePath` 镜像抽象。

## Blocked

- 无。

## Current interfaces

- `AppContainer` 已组合 Provider、ChatService、Capability/Policy/Approval 与 Tool Pipeline/Scheduler；`ShellRepository` fake 只服务早期壳层 route。
- 导航 route 只承诺稳定入口；会话与 Provider 已有实现，其余页面是否可用以对应 HXA 完成记录为准。
- 生产 Tool Registry 已包含 `time.now`、Workspace 文件工具 `read`/`write`/`edit`/`files.*`（含受限 `files.archive`/`files.extract`）与 QuickJS 代码执行工具 `code.javascript.run`（CODE_EXECUTION/L2/LOCAL_QUICKJS 单并发 lane；consumer + developer 双变体接线，HXA-053）；文件工具当前 scope 为 app-private 与 developer All-files，SAF tree 尚未接入工具 scope resolver。
- QuickJS 执行通道已落地并接 Tool：主 App 内 isolated 非导出 Service（每执行唯一 `js_` + 32-hex 实例）+ 主进程 `JsExecutionClient`（同步有界、不重试；wrapper JSON ABI：输入 = 恰一个合法 JSON 文档、输出 = ≤ maxOutputBytes 的单个 JSON 文档、11 状态闭合集、PROTOCOL_VERSION=2；API 见 [HXA-052 完成记录](../completion-records/HXA-052.md)），经 `code.javascript.run` 接生产 Tool 管线（模型仅见 code+input、limits 固定 §4.1 默认不入 schema、§4.8 脱敏审计经 dispatcher 单一 emitter；接线见 [HXA-053 完成记录](../completion-records/HXA-053.md)）；PRoot 规划使用同签名、独立 applicationId/UID 的 Runtime APK，并通过 signature-protected Binder/PFD IPC 连接。
- accepted [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)将 Standard 定义为 Google Play、国内 Android 应用商店和官网的完整产品形态；consumer/developer 仍只是当前构建事实。HXA-122 尚未决定稳定主 applicationId、渠道命名与升级路径，也没有外部 release artifact。
- CLI 订阅后端若实施，将使用另一个有 INTERNET 的独立 UID，凭据由官方 CLI 持有。
- MCP 规划为 Client-only；Skill 规划按 `SKILL.md` 开放规范渐进加载，二者当前均未实现。
- `read`/`write`/`edit`/`files.*` 已实现；`bash` 仍只是规划中的稳定短工具名。所有工具继续受 scope、Policy、Approval 和执行域约束。

## Known limitations

- HXA-050/051 的环境限制（替代证据已在完成记录）：无 API 34 系统镜像（API 29/36 覆盖 minSdk 与上界）、arm64 Mac 无 x86_64 模拟器镜像（x86_64 仅有 AAR 内 `.so` 存在性 + ELF 对齐证据，未声称设备运行）、设备均为 4 KiB page（16 KiB 以四 ABI PT_LOAD 对齐 16 KiB + 4 KiB 全能力运行为替代证据）。API 34、x86_64 设备与 16 KiB 真机运行验证归 HXA-054 与后续设备矩阵。
- QuickJS 引擎事实（HXA-051/052 设备测试与进程内探针固定）：顶层数组结果 → List、`memoryUsage` 在失败分配后回落基线、跨线程 evaluate 为 ASLR 布局相关 UB（服务永远只在其执行线程上 create/evaluate/close）；**wrapper 模式 OOM 表面形态（HXA-052 固定）**：API 29 64 MiB 堆批量耗尽时 catch 到字面 `null`（引擎无法分配 Error 对象，wrapper 原样重抛 → 宿主空消息 → OOM；用户 `throw null` 同形态不可区分、已接受），API 36 为 Error `"out of memory"`（wrapper 前缀后子串存活）；API 29 1600 万元素循环耗尽 64 MiB → 引擎原生 SIGSEGV（SEGV_ACCERR，永不作测试源）；HXA-051 的 raw 顶层对象 → `null` 事实自 HXA-052 起作废（wrapper 的 `JSON.stringify` 消除）。Zipline 升级时须复核。
- QuickJS 执行控制面不含 `killProcess`/`System.exit`：超时/取消走 interrupt + 有界宽限 + 解绑，实例回收完全交给系统（Binder death 为主观察信号）；crash 注入 seam 仅 `BuildConfig.DEBUG` + 显式测试标志双门，release 编译移除。
- **API 29 生产迁移缺陷（HXA-053 设备验收复核发现，待 core:storage 后续修复）**：`MIGRATION_1_2` 使用 `ALTER TABLE approvals RENAME COLUMN`（HXA-034），API 29 的 SQLite（<3.25）不支持该语法，API 29 设备首次 V1→V2 升级会 `SQLiteException: syntax error`。HXA-053 全量 consumer 设备套件复核：API 36 52/52 全过；API 29 另 8 例失败（`FilesScreenTest` 6 例 headless 慢速 AVD Compose 10 s 超时、`GoalReminderTest` 2 例 `grantRuntimePermission` 在 API 29 抛 `SecurityException`）均为基线已存在的环境/测试问题，与 M5 改动无关。
- **`ToolScheduler` 跨批 waiter 潜伏死锁（HXA-053 发现，未修）**：跨批等待者只监听本批 future，两个并发且互斥的独立 `scheduleBatch` 会互相等待。生产不变量（turn 串行、批不重叠、Act 一次一调用）下潜伏；修复归框架后续，本分支不触碰。
- GitHub Actions 已有远端运行证据，但 workflow 不运行模拟器/真机测试；现有 artifact 是短期 debug 安装包，不是签名 release 或发布验收证据。
- Browser、MCP、Skill、PRoot/CLI、Accessibility、Root 等用户可感知执行能力尚未实现；QuickJS 执行已接 `code.javascript.run`（HXA-053），但攻击套件与端到端边界测试（无限循环/内存/输出洪泛/eval/fetch/require/进程崩溃/Binder 大输入/取消竞态/verified artifact）归 HXA-054。
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
