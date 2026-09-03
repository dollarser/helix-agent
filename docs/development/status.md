# Helix 实施状态

更新时间：2026-09-03

## Current summary

| 维度 | 当前事实 |
| --- | --- |
| 已验证范围 | M0～M4：HXA-001～003、010～016、020～028、030～049；**M5 完成**：HXA-050（Zipline/QuickJS spike）、HXA-051（生产执行协议 isolated Service/Binder）、HXA-052（IIFE wrapper/JSON ABI/限制，API 29/36 arm64-v8a）、HXA-053（QuickJS 执行接 Tool/Policy/审计：`code.javascript.run` 全链路 + 11 状态回填 + §4.8 脱敏审计 + 完整代码审批，API 29/36 arm64-v8a）、HXA-054（攻击与端到端测试：8 项攻击点全链路 E2E + 设备证据，75 例 androidTest/设备 × 两台 + 84 例 JVM） |
| 已落地骨架 | Provider 配置与三协议适配、聊天流、Capability/Policy/Approval、Dispatcher/Scheduler、batch-safe Turn Coordinator、多步 Tool Loop 与持久审计、Workspace/文件工具（含受限 zip/tar Archive）、SAF 导入导出适配层与 developer All-files scope、会话附件导入/持久化与 UTF-8 文本物化（ADR-0014 首批）、QuickJS 生产执行模块（`:runtime:quickjs`，ADR-0015 选定 Zipline 1.27.0：isolated Service/Binder 通道 + §3.2 IIFE wrapper JSON ABI）+ `code.javascript.run` 工具（consumer/developer 双变体接线） |
| 尚无业务执行器 | Browser、MCP、Skill、PRoot/CLI、Accessibility、Root |
| 当前检查点 | **M5 完成**（HXA-050～054 全部有完成记录，HXA-048/049 收敛已在 main 落地）；M5A / HXA-055 图片输入与 Provider 视觉能力由他人并行处理；**M6 进行中：HXA-060 最小 WebView 浏览器 + HXA-061 Browser snapshot 完成**（见下方 Completed 表 M6 行），M6 下一项为 HXA-062 Browser actions |
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
| M5 | HXA-050～054（M5 完成）：Zipline 1.27.0 QuickJS spike（evaluate/memoryLimit/InterruptHandler/eval+Function 封堵/大于 6 MiB 调用线程栈/bindIsolatedService 唯一实例回收，各 25 例 androidTest 全过；16 KiB page 以四 ABI ELF PT_LOAD 对齐为替代证据；ADR-0015 accepted）+ 生产执行协议（isolated 非导出一次性 Service、手搓 onTransact execute/interrupt/info、64 KiB inline + PFD、deadline/interrupt/cancel/watchdog 与 Binder-death 回收、11 状态闭合错误集，36 例 androidTest/设备 + 33 例 JVM）+ IIFE wrapper JSON ABI（doc §3.2 严格 IIFE 为唯一生产路径、host 编码输入 + 局部 `const input` 闭包注入、JSON 输入输出 + 双层输出边界、19 例 wrapper 逃逸攻击套件，55 例 androidTest/设备 + 62 例 JVM）+ 执行接 Tool/Policy/审计（`code.javascript.run`：CODE_EXECUTION/L2/LOCAL_QUICKJS 单并发 lane、模型仅见 code+input 且 limits 固定 §4.1 默认不入 schema、11 状态稳定回填绝无假成功、§4.8 脱敏审计经 dispatcher 单一 emitter、审批卡完整代码块 + input 摘要 + 联网：否 + 固定 limits + 代码 SHA-256 短摘要；JVM 506 例（quickjs 74 + framework 136 + app 148×2）+ 5 例 androidTest × 两台设备全过）+ **攻击和端到端测试（HXA-054：无限循环 10 s wall TIMEOUT、64 MiB 堆 OOM 表面形态按 API 29/36 固定、输出洪泛 PFD 有界失败 + 256 KiB 边界±1、eval/fetch/require 生产链路调用尝试全 fail-closed、crash seam 稳定 CRASHED + 隔离 PID 死亡/主进程存活设备证据、2 MiB 输入/256 KiB 源 PFD 往返 + 超限预检不产生实例 + 服务端 inline-cap fail-closed、取消三竞态 50 轮收敛 + 一次性槽位不重放、verified artifact size+SHA-256 宿主侧重算采信前提 + JVM 失配分支、§10 NUL/emoji 输出方向/深嵌套/环引用（环引用 marker 设备固定为 `circular reference`）；75 例 androidTest/设备 × 两台 + 84 例 JVM 全过）** | [HXA-050 完成记录](../completion-records/HXA-050.md)、[ADR-0015](../adr/0015-zipline-quickjs-execution-base.md)、[HXA-051 完成记录](../completion-records/HXA-051.md)、[HXA-052 完成记录](../completion-records/HXA-052.md)、[HXA-053 完成记录](../completion-records/HXA-053.md)、[HXA-054 完成记录](../completion-records/HXA-054.md) |
| M6 | HXA-060～061 最小 WebView 浏览器 + Browser snapshot（`:feature:browser`：加固 System WebView（逐字段设置 + 设备回读重建再验证）、fail-closed URL 策略（仅 http(s)/about:blank/data:text/html）、标签状态机（typed/codeless 双通道错误页、用户 stop 非错误、`navigationGeneration` 为 HXA-061/062 token 绑定地基）、100 MiB 流式上限下载队列（SAF 显式选位、无自动落盘）、Cookie/缓存清除入口、Compose UI 与 app 接线、无永久 JS 桥；固定版本化 JS 提取有界语义树（宿主 fail-closed 再校验、密码值绝不读取）、node token 绑定 tab/origin/navigation generation/fingerprint/TTL、网页内容标记为不可信数据；99 例 JVM + 15 例 androidTest/设备 × 两台全过） | [HXA-060 完成记录](../completion-records/HXA-060.md)、[HXA-061 完成记录](../completion-records/HXA-061.md) |

架构决定的状态与理由见 [ADR 目录](../adr/README.md)；跨里程碑复核、事实修正和历史取舍见[文档复核记录](../history/documentation-review.md)。“有完成记录”不等于当前发布能力，当前可用边界只看本文件的摘要、接口和限制。

## In progress

- 无。

## Next task

- M5A / HXA-055 图片输入与 Provider 视觉能力：实现 production `ArtifactImageResolver`（只解析与当前会话消息绑定、hash 复核通过的 app-private Artifact），接通 `ModelRequest.ImageReference` 与三协议 vision probe / Turn capability snapshot；解码失败、Artifact 变化或超限一律 fail-closed，不回退裸 base64 文本（详见 [roadmap §9A](roadmap.md)）。
- M6 / HXA-062 Browser actions：实现 open/navigate/back/forward/reload/find/click/type/scroll/screenshot（doc 09 §3.3）；导航或 DOM 变化使旧 token 失效；密码/验证码/支付字段拒绝。

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

- HXA-050/051 的环境限制（替代证据已在完成记录）：无 API 34 系统镜像（API 29/36 覆盖 minSdk 与上界）、arm64 Mac 无 x86_64 模拟器镜像（x86_64 仅有 AAR 内 `.so` 存在性 + ELF 对齐证据，未声称设备运行）、设备均为 4 KiB page（16 KiB 以四 ABI PT_LOAD 对齐 16 KiB + 4 KiB 全能力运行为替代证据；HXA-054 沿用该口径，环境无 API 34 / x86_64 镜像，未重复论证）。API 34、x86_64 设备与 16 KiB 真机运行验证归后续设备矩阵。
- QuickJS 引擎事实（HXA-051/052 设备测试与进程内探针固定）：顶层数组结果 → List、`memoryUsage` 在失败分配后回落基线、跨线程 evaluate 为 ASLR 布局相关 UB（服务永远只在其执行线程上 create/evaluate/close）；**wrapper 模式 OOM 表面形态（HXA-052 固定）**：API 29 64 MiB 堆批量耗尽时 catch 到字面 `null`（引擎无法分配 Error 对象，wrapper 原样重抛 → 宿主空消息 → OOM；用户 `throw null` 同形态不可区分、已接受），API 36 为 Error `"out of memory"`（wrapper 前缀后子串存活）；API 29 1600 万元素循环耗尽 64 MiB → 引擎原生 SIGSEGV（SEGV_ACCERR，永不作测试源）；HXA-051 的 raw 顶层对象 → `null` 事实自 HXA-052 起作废（wrapper 的 `JSON.stringify` 消除）；**环引用 stringify 真实消息为 `circular reference`（HXA-054 两台设备固定）**——V8 措辞 `Converting circular structure to JSON` 不存在于 QuickJS 2021-03-27 二进制（strings 佐证），HXA-052 的 `CIRCULAR_RESULT_MARKER` 是未经设备验证的假设常量，已按固定事实修正（service 环引用 → OUTPUT_LIMIT 重分类因此恢复可达）。Zipline 升级时须复核以上全部事实。
- QuickJS 执行控制面不含 `killProcess`/`System.exit`：超时/取消走 interrupt + 有界宽限 + 解绑，实例回收完全交给系统（Binder death 为主观察信号）；crash 注入 seam 仅 `BuildConfig.DEBUG` + 显式测试标志双门，release 编译移除。
- **API 29 生产迁移缺陷（HXA-053 设备验收复核发现，已在 main 修复）**：`MIGRATION_1_2` 使用 `ALTER TABLE approvals RENAME COLUMN`（HXA-034），API 29 的 SQLite（<3.25）不支持该语法，API 29 设备首次 V1→V2 升级会 `SQLiteException: syntax error`。已改用 copy-and-swap 修复并复测 API 29/36 47/47 无回归（见 [Bug 修复记录](../bug-fixes/2026-09-03-room-migration-sqlite-rename-column.md)）；HXA-053 复核时 API 29 的另 8 例失败（`FilesScreenTest` ×6、`GoalReminderTest` ×2）亦已随 main 的 API 29 修复一并解决，详见下方设备矩阵条目。
- **`ToolScheduler` 跨批 waiter 潜伏死锁（HXA-053 发现，已修复）**：原实现中等待准入的批次只监听本批 future，而准入冲突来自跨批共享的 inFlight 足迹——两个并发且互斥（容量占满或排他 lane）的独立 `scheduleBatch` 会互相等待、永不唤醒。修复：准入等待循环在监听本批 future 之外追加监听调度器全局“槽位状态变化”信号（每次 `releaseSlot` 触发，任何批次），任何批次释放槽位都会唤醒全部准入等待者；信号实例的读-挂接顺序保证不漏唤醒（挂到已完成实例立即回调，漏窗口内的释放必然完成所读实例）。`ToolSchedulerTest` 增加 2 例跨批存活回归（容量阻塞 + 排他写 lane 阻塞，均先证明被阻塞方确实卡在准入等待再释放）——修复前实测复现（释放槽位后另一批 `join` 超时）。生产不变量（turn 串行、批不重叠、Act 一次一调用）下该缺陷原本潜伏；此修复是框架契约的一部分。
- GitHub Actions 已有远端运行证据，但 workflow 不运行模拟器/真机测试；现有 artifact 是短期 debug 安装包，不是签名 release 或发布验收证据。
- Browser、MCP、Skill、PRoot/CLI、Accessibility、Root 等用户可感知执行能力尚未实现；QuickJS 执行已接 `code.javascript.run`（HXA-053），攻击套件与端到端边界测试（无限循环/内存/输出洪泛/eval/fetch/require/进程崩溃/Binder 大输入/取消竞态/verified artifact）已由 HXA-054 完成（两台设备 75 例 androidTest + 84 例 JVM 全过，含真机崩溃/内存/取消设备证据）。
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
