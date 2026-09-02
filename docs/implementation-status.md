# Implementation Status

更新时间：2026-09-02

## Current summary

| 维度 | 当前事实 |
| --- | --- |
| 已验证范围 | M0～M4：HXA-001～003、010～016、020～028、030～044 |
| 已落地骨架 | Provider 配置与三协议适配、聊天流、Capability/Policy/Approval、Dispatcher/Scheduler、batch-safe Turn Coordinator、多步 Tool Loop 与持久审计、WorkspacePath/FileScopePath 路径 value object 与 scope 边界、Workspace 原子文件持久化（目录布局/hash/临时写+fsync+replace/前置 hash/配额/bounded MIME/Artifact 登记 seam）、SAF 导入/导出适配层（fail-closed 管线、persisted tree grant 注册表、撤销检测、流式原子写 seam） |
| 尚无业务执行器 | Browser、MCP、Skill、QuickJS、PRoot/CLI、Accessibility、Root |
| 当前检查点 | 无进行中任务；下一项为 M4 / HXA-045 All files access（实现说明页、系统设置跳转、`Environment.isExternalStorageManager()` 验证和 Helix roots） |
| 发布状态 | 仅开发/测试产物；尚无完成签名与发布验收的稳定版本 |

## Completed

已完成任务的详细实现、命令、exit code、设备和限制只在 [HXA 完成记录](completion-records/README.md) 与各记录中维护；本文件不复制字段级历史。

| 里程碑 | 已完成范围 | 权威证据 |
| --- | --- | --- |
| M0 | HXA-001～003：工程、质量门禁、AppContainer/导航壳 | [M0 完成记录](m0-completion-record.md)、[逐 HXA 索引](completion-records/README.md) |
| M1 | HXA-010～016：领域状态、Plan/Goal、Room、恢复、Context Builder | [逐 HXA 索引](completion-records/README.md) |
| M2 | HXA-020～028：Secret/Provider、三协议、能力探测、模板、聊天 UI | [逐 HXA 索引](completion-records/README.md) |
| M3 | HXA-030～039：Tool/Schema/Capability/Policy/Approval、Dispatcher/Scheduler、Tool Loop、模型流状态与 batch-safe Turn Coordinator | [逐 HXA 索引](completion-records/README.md) |
| M4 | HXA-040～044：WorkspacePath 与 FileScopePath 路径 value object、规范化、越界/symlink 拒绝与 scope adapter 边界；Artifact、配额和原子文件操作（目录布局、hash、临时写+fsync+replace、前置 hash、配额、bounded MIME/encoding detection、Artifact 登记 seam）；首批业务文件工具 `read`/`write`/`edit`/`files.stat`/`files.list`/`files.search`/`files.mkdir`（offset/maxBytes 分页、编码边界、稳定 EOF、10 MiB 分块、有界搜索、region 边界、真实路径不外泄）与覆盖完整安全 descriptor 的 contractHash 审批失效门槛（[ADR-0011](adr/0011-full-descriptor-contract-hash.md)，proposed）；`files.copy`/`files.move`/`files.delete` 显式冲突策略（已存在目标未带 overwrite 拒绝、目录目标一律拒绝）与删除进 `.helix/trash` 回收站（entry 名自描述可逆、恢复与物理清空为独立 store seam、跨 scope move 先发布后删除不丢源）；SAF 导入/导出适配层（对谎报 provider 全链路 fail-closed：admission/硬上限/EOF 复核三重 size 防御、MIME 重探、display name 消毒、流式原子写 seam `writeAtomicStream`+类型化 abandon、persisted tree grant 注册表（确定性 scopeId/损坏隔离/逐条防篡改）与 query 探测撤销 sweep、destination 写后大小复核，in-APK 恶意 ContentProvider 设备验证） | [逐 HXA 索引](completion-records/README.md) |

架构决定的状态与理由见 [ADR 目录](adr/README.md)；跨里程碑复核、事实修正和历史取舍见[文档复核记录](documentation-review.md)。“有完成记录”仍不等于当前发布能力，当前可用边界只看本文件的摘要、接口和限制。

## In progress

- 无。

## Next task

- M4 / HXA-045 All files access：实现说明页、系统设置跳转、`Environment.isExternalStorageManager()` 验证和 Helix roots；即使获系统权限，scope 外路径仍拒绝。

## Blocked

- 无。

## Current interfaces

- `AppContainer` 已组合 Provider、ChatService、Capability/Policy/Approval 与 Tool Pipeline/Scheduler；`ShellRepository` fake 仍只服务早期壳层 route，不能代表业务工具已经实现。
- 导航 route 只承诺稳定入口；会话与 Provider 已有实现，其余页面是否可用必须以对应 HXA 完成记录为准。
- QuickJS 将使用主 App 内的 `isolatedProcess` Service。
- PRoot 将使用同签名但独立 applicationId/UID 的 Runtime APK，主 App 只通过 signature-protected Binder/PFD IPC 连接。
- 当前直接分发的唯一用户主应用计划由 developer 变体生成；consumer 继续构建/测试但不在默认下载清单。该发布角色尚未形成外部 release artifact。
- CLI 订阅后端若实施，将使用另一个有 INTERNET 的独立 UID；凭据由官方 CLI 持有。
- MCP 规划为 Client-only，当前尚无生产适配器；Skill 规划按 `SKILL.md` 开放规范渐进加载，当前也未实现。
- `read/write/edit/bash` 是规划中的稳定短工具名，但仍将受 scope、Policy、Approval 和执行域约束。

## Known limitations

- GitHub Actions 已有远端运行证据，但当前 workflow 不运行模拟器/真机测试；最新 artifact 是短期 debug 安装包，不是签名 release 或发布验收证据。
- Provider 配置、三协议聊天与 M3 的 Tool Loop/安全管线已实现；但除 `time.now` 验证工具和 HXA-042/043 的 Workspace/文件工具（`read`/`write`/`edit`/`files.*`）外，Browser、MCP、Skill、QuickJS、PRoot/CLI、Accessibility、Root 等用户可感知执行能力尚未实现。
- M0 只在 API 36 arm64-v8a 模拟器完成设备验收；API 29、多 ABI 和真机矩阵由后续能力任务按验收矩阵执行。
- 单一直接分发主包目前只是 ADR-0006 文档决定：developer APK 尚未以“Helix 主应用 + Standard 默认 + Advanced 切换”的完整产品流程完成设备验收，最终 applicationId/签名也未决定；不得把现有空壳 build 当作已发布主包。
- developer 主包未来声明 `MANAGE_EXTERNAL_STORAGE` 或 Accessibility Service 后，即使 Standard UI 不提供启用入口，Android 系统设置仍可能列出 Helix。Standard 保证“默认关闭、不会自动申请/启用、Agent 无 scope”，不保证系统设置完全隐藏这些声明。
- libsu/JitPack 仍是 HXA-094 的未来供应链决策点：当前仓库没有加入 JitPack 或 libsu 依赖。“计划路径 + 截止任务”只关闭了无人负责的治理缺口，不表示依赖来源已经接受或实现。
- Git 基线已建立（2026-08-31 用户授权）：首个 commit 覆盖 M0 + HXA-010～012，之后每完成一个 HXA 提交一版；继续开发前仍需先 `git status` 检查工作树。
- PRoot/CLI、结构化 Git UI 和持久 Git Workspace 尚未实现。HXA-081/086 未来即使通过也只证明离线 Runtime 中的 Git binary/smoke；HXA-088 与 ADR-0008 未决定前，不存在跨 Job `.git` 一致性、remote Git 或凭据能力。
- Tool Dispatcher/Scheduler、结构化 interaction receipt 与持久回填已由 HXA-035～037 实现；低内存/后台/热限制实信号、可配置 TurnBudgets UI 和 Plan/Goal 工具入口明确归 HXA-099。child Agent、Agent graph 和 Workflow 未实现，ADR-0009 仍是 proposed；云端任务/Remote Worker 明确不在当前范围。
- 架构层级债：HXA-039 已由 accepted [ADR-0010](adr/0010-batch-turn-coordinator.md)取代旧串行生产决定，并以唯一 `TurnCoordinator` 驱动聊天 Turn/ModelCall 生命周期、批量 checkpoint 和事务化模型回填；M1 `TurnReducer` 只保留历史测试/旧恢复兼容，不接入新生产 Turn。`ChatService` 仍承担 egress/send gate、ToolCall 准备/Timeline/Approval 投影和 UI facade，后续只按真实测试 seam 渐进提取；`ToolDispatcher` 继续保留单一公开入口，不按 LOC 机械拆散安全管线。
- M0 空壳的中文 Compose 文本尚未资源化；简体中文/英文资源和硬编码扫描由 HXA-067 完成。
- M3 收口代码审查的修复、验证矩阵和保留取舍已迁入[文档复核记录 §14](documentation-review.md#14-m3-收口代码审查与修复2026-09-01)，不再在唯一状态源重复维护。
- ApprovalBinding 现含 `contractHash`（完整安全 descriptor 的 SHA-256，[ADR-0011](adr/0011-full-descriptor-contract-hash.md)，proposed）：timeout、输出上限、Capability、风险、幂等性或 origin 等安全字段变化会强制旧审批凭证失效（`ContractHashGateTest` 机械证明）；`serverProvidedHints`（不可信展示文本）刻意不进入契约，其变化不使审批失效。
- ADR-0005 的 Advanced 高敏出网规则引擎与展示 seam 已有，但持久化、创建和撤销 UI 尚未实现，明确归 HXA-068；store 不可用时生产规则集保持空并回到逐次审批。
- `files.copy`/`files.move`/`files.delete` 的 `baseRisk` 一律 L2（逐次审批）：policy 引擎无 per-call/per-argument 风险升级机制，PRD“跨 scope 或覆盖时提升风险”不可表达，fail-closed 取统一 L2；per-call L3 升级为已知限制（HXA-043 完成记录，决策记录第 1 条）。trash 的恢复/物理清空只是 store seam，无 model 工具、无 UI；trash 面由 HXA-046 文件管理 UI 实现。
- SAF 导入/导出已实现为适配层（HXA-044，`AppContainer.featureFiles` 适配器束，恶意 provider 防御经 in-APK 恶意 ContentProvider 设备验证），但 persisted SAF tree grant 尚未接入文件工具的 scope 解析，也无用户导入/导出 UI 入口：模型与文件工具当前仍只见 app 私有 scope（`app`）。SAF scope 接入与 All-files scope 归 HXA-045/046（HXA-044 完成记录，决策记录第 6 条）。
