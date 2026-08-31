# Implementation Status

更新时间：2026-08-31

## Completed

- Baseline 1.3 的产品、架构、本地代码执行、路线、环境、开源参考、安全与小模型实施文档已完成一致性复核。
- 项目所有者已选择 Apache License 2.0，根 `LICENSE` 与 `THIRD_PARTY_NOTICES.md` 已建立。
- M0 / HXA-001 已完成：Gradle 多模块、`consumer`/`developer` 变体、两个独立 Runtime application、固定工具链、version catalog、29 个 dependency lock 和变体边界扫描均已验收。
- M0 / HXA-002 已完成：Spotless、Detekt、Android lint、Gradle Wrapper validation workflow、dependency verification、lock diff、secret scan、ADR 门禁和 `git diff --check` 已配置并通过本地可执行门禁。
- M0 / HXA-003 已完成：手工 `AppContainer`、接口化 fake repository、会话/文件/浏览器/扩展/权限/设置/审计七个空状态 route 已实现；双变体 JVM 测试、构建和 API 36 模拟器仪器测试通过。
- consumer APK 已在 API 36、arm64-v8a 模拟器上实际安装和冷启动，默认展示“会话”空状态。完整证据见 [M0 完成记录](m0-completion-record.md)。
- 已新增 [HELIX-M1 小模型交接](small-model-handoff.md)、逐 HXA 完成记录约定和自动文档契约检查；继续开发者从本文件标明的当前唯一任务续接，不重复已完成 HXA。
- M1 / HXA-010 已完成：`core:model` 领域 ID（22 个 value class + IdGenerator）、Turn/ToolCall/Execution/Goal 状态机、RiskLevel/Capability/ToolOperationClass、TurnBudgets、ArtifactRef（不透明）、HelixError（12 错误类别）、Clock、ExecutionTargetDescriptor、ToolExecutionEnvelope 及手写严格 canonical JSON 存储编码；64 个纯 JVM 测试通过，[ADR-0001](adr/0001-canonical-json-storage-encoding.md) 已在收口审查中按窄范围接受。证据见 [HXA-010 完成记录](completion-records/HXA-010.md)。
- M1 / HXA-011 已完成：`core:agent` Turn reducer（`state + event -> state/effects`，纯函数 + 副作用分离），覆盖完成/拒绝、工具结果回填重入 context/model 循环、审批与拒绝、多工具调用串行、step/token/模型调用预算门控（usage 缺失按保守字节估算、绝不按 0）、模型失败、取消、进程中断与不确定副作用（解决后才可恢复，恢复不重放未执行调用）；57 个纯 JVM 测试通过；对 core:model `TurnState` 的 3 条增量转移边已作为契约解释记录在案。证据见 [HXA-011 完成记录](completion-records/HXA-011.md)。
- M1 / HXA-012 已完成：Chat/Plan/Act/Goal 模式策略（`ModePolicy`：Chat 默认无工具、显式启用后仅 READ_ONLY+L0；Plan 仅 READ_ONLY 且动态风险 ≤L1，风险判断不能替代 operation-class 判断；Act/Goal 模式层不加限）+ 版本化 `PlanArtifact`/`PlanStep`（core:model 领域值，canonical JSON 存储编码 + SHA-256，hash 随版本变化）；core:agent 66 个、core:model 70 个纯 JVM 测试通过。证据见 [HXA-012 完成记录](completion-records/HXA-012.md)。
- 2026-08-31 二次收口审查完成（仅文档与决策）：[ADR-0001](adr/0001-canonical-json-storage-encoding.md)按固定 shape、非通用 JSON 的窄范围接受；[ADR-0002](adr/0002-turn-state-intra-response-edges.md)接受三条 HXA-011 增量边并纠正恢复边归属；新增 [ADR-0003](adr/0003-plan-read-only-risk-ceiling.md)接受 Plan 的 READ_ONLY + 动态风险 ≤ L1 双重门；交接入口改为动态读取本状态文件。详见[文档复核记录 §7](documentation-review.md)。
- M1 / HXA-013 已完成：`core:agent` Goal reducer（`state + event -> state/effects`，复用 HXA-010 `GoalState` 零新增边）+ `GoalBudgets`（core:model，六项预算 + `stricterWith` + ADR-0001 canonical 编码）：run/wake 分离（只有用户显式继续创建新 run、重试不离开 RUNNING）、五类预算耗尽一律 PAUSED（`BudgetExhausted(limit)`，不得完成）、criterion 仅 verifier evidence 可满足且完成需全部有证据、进程死亡 RUNNING→PAUSED 且保留 checkpoint；`:app` WorkManager 可延迟提醒（唯一工作名 + REPLACE，过期 checkpoint 立即补发，worker 只发通知、零模型/工具调用——不变量计数器 + 设备测试证据，UI 文案无精确定时器）。core:agent 107 个、core:model 75 个、app consumer 8 个纯 JVM 测试 + API 36 模拟器 3 个仪器测试（提醒真实发出/REPLACE/取消）通过。run/wake 与预算耗尽语义记入已接受的 [ADR-0004](adr/0004-goal-run-wake-budget-semantics.md)；后续新增的 durable pause reason / 有界 crash 计量要求归 HXA-102，不反写为本任务已实现。证据见 [HXA-013 完成记录](completion-records/HXA-013.md)。
- 2026-08-31 起按用户授权建立 Git 提交基线：首个 commit 为 M0 + HXA-010～012 基线，此后每完成一个 HXA 提交一版（可回退管理）。
- M1 / HXA-014 已完成：`core:storage` Room 2.8.4 持久层（KSP 2.3.11）：doc 9.1 全部 22 张表（关系表 FK、唯一索引、`provider_configs`/`mcp_servers` 仅 alias 无明文密钥列）+ 21 个 DAO（`plan_steps` 经 `PlanDao` 访问，无独立 DAO）+ 21 个仓库（plan 行与步骤行同事务、审批/verified 一次性守卫、artifact 文件+hash 先校验、plan 按 ADR-0001 从规范化列恢复并以 hash 列绑定精确版本）+ `ContentStore`（内容寻址、写后校验、原子替换）+ 存储层严格 JSON 编解码（criteria/ContentRef）+ `HelixStorage` 组合根与 `withTransaction`（9.2 事务入口）。JVM 44 个测试（含解析已提交 v1 schema 导出对 doc 9.1 的契约对照）+ API 36 模拟器 10 个 Room migration fixture 仪器测试（导出↔代码双路径 schema 对照、`PRAGMA foreign_keys=1`、FK 违规/级联、全部表 round-trip）通过；v1 schema 导出提交入库作为未来 migration 基线。证据见 [HXA-014 完成记录](completion-records/HXA-014.md)。
- M1 / HXA-015 已完成：恢复协调器（进程重启恢复）：`core:agent` 纯决策层 `RecoveryCoordinator`（从持久化事实——`turns`/`tool_calls`/`goals` 行——判定：非终态 Turn→INTERRUPTED 且死亡时 RUNNING 调用为不确定副作用源、仅 PENDING/RUNNING 调用停泊（AWAITING_APPROVAL 从未执行不动）、RUNNING Goal→PAUSED；确定性 plan 只标记/停泊/关闭、类型层不含重执行；resume/wake 双门——不确定调用未解决不可恢复、唤醒仅 READY/PAUSED/INPUT_REQUIRED）+ `:core:storage` 恢复扫描（`TurnDao.listActive`、`GoalRunDao.listOpenByGoal`）与类型安全写入（`ToolCallState` 重载、`HelixStorage.open/close` 收口 Room 边界）+ `:app` 可执行协调器（`RecoveryCoordinatorApp`：扫描→plan→单一 `withTransaction` 内状态更新+审计同提交，doc 9.2；goal 停泊保留 checkpoint/清零 wake、死亡 run 以 INTERRUPTED 关闭）+ `HelixApplication` 启动后台触发（幂等 no-op 安全）。core:agent 126 个（+19 恢复决策/跨层一致性/不重放）、core:storage 44 个 JVM 测试 + API 36 模拟器 6 个设备测试（含 3 个进程恢复 fixture：死亡停泊全断言+审计+幂等、唤醒门跟随恢复且死亡 run 永不重放、无副作用 Turn 直接恢复不重放）通过；现有恢复实现符合 ADR-0004 主体，ADR 接受时新增的有界 crash 计量证明归 HXA-102。证据见 [HXA-015 完成记录](completion-records/HXA-015.md)。
- M1 / HXA-016 已完成：`core:agent` 可审计 Context Builder（doc 02 §5.4/§5.3、doc 07 §7）：13 类 `ContextSourceType`（六个内容源 WEB/FILE/MCP/SKILL/NOTIFICATION/ACCESSIBILITY 恒 `UNTRUSTED`、system/mode 契约恒 `TRUSTED`）+ `ContextSource`/`ContextItem`/`ContextBuildResult` 可审计字段（`sourceType/sourceId/trust/contentRef|contentHash`/估算 token，总和 = 各项和、每项 ≥1）；确定性裁剪三步（契约 + `retained` 项永不裁剪不截断、仅其超预算 fail-closed、剩余预算自新向旧整项填充、最终契约在前 + 快照序）；**永不字符级截断**（内联 ≤32 KiB、summary ≤2 KiB + `ArtifactRef` + 全量 SHA-256，超限 fail-closed；分块读属 HXA-041）；全文项 hash 构建器计算 + 提供须一致、summary 项透传全量 hash；Secret 无源类型无入口；保守字节估算（未知 usage 不当 0）。core:agent 147 个纯 JVM 测试（+21，覆盖不可信来源/超预算两种形态/稳定排序/多工具结果/token usage 缺失）通过，零新依赖。证据见 [HXA-016 完成记录](completion-records/HXA-016.md)。
- **M1 退出条件已满足**（状态机、预算、Context Builder 裁剪/信任标记、migration 测试全部通过，见上 HXA-010～016 证据）；M2（Provider 网络）按交接约束尚未开始。
- 2026-08-31 文档决策完成：[ADR-0005](adr/0005-standard-advanced-safety-profiles.md) 已接受 Standard/Advanced 两级安全配置、实际 endpoint residence、高敏数据出网门控、DNS rebinding 防线与 PRoot 离线边界。该条只表示产品/架构决定已接受；对应 Provider、Policy、网络和高级能力均尚未实现。
- 2026-08-31 分发决策完成：[ADR-0006](adr/0006-single-direct-main-package.md) 已接受“一个直接分发用户主应用”：工程内部 developer 构建默认 Standard，并在同一安装内可进入 Advanced；consumer 仅保留为未来受限渠道产物，PRoot/CLI 为 companion Runtime。现有 applicationId 未改，首次外部稳定发布前必须由 HXA-122 决定最终主应用 ID 和升级路径。
- 2026-08-31 Runtime 生命周期决策完成：[ADR-0007](adr/0007-companion-runtime-lifecycle.md) 已接受 PRoot/CLI 按需冷绑定、空闲可回收、jobId journal/查询对账、未知结果不重放、前台服务类型匹配和可选 wake lock 硬 deadline。该条只是文档决定；Runtime、Binder、FGS、journal 和恢复均尚未实现，分别归 HXA-083～086、HXA-103、HXA-110～113。
- 2026-08-31 Agent 设计参考约定完成：允许按当前 HXA 的具体问题参考 Claude Code、Gemini CLI、Codex CLI、Cline、Continue、Aider、OpenCode、DeepSeek Harness、goose、Amazon Q Developer CLI 和 Hermes；[06 §5.11](06-open-source-references.md#511-主流-coding-agent--agent-harness-设计参考)记录官方来源、维护/许可证事实、可借鉴点和 Android 不可照搬边界。该约定不新增依赖或实现状态。
- 2026-08-31 审批存储守卫加固：`ApprovalDecision` 收紧为 `APPROVED`/`DENIED` 封闭枚举，Repository 不再接受任意字符串，Room `decide` 在 SQL 层拒绝未知值，`consume` 只允许 `decision = 'APPROVED'`；新增 unknown/pending/denied/重复 consume 设备测试。同时补齐 Room 明确告警的 `skill_snapshots.skillId` 外键索引并同步 v1 schema export。`:core:model:test`、`:core:storage:testDebugUnitTest`、`spotlessCheck`、`detekt` 和 API 36 arm64 模拟器 14 个 storage instrumentation 测试通过。该修复只关闭 HXA-014 基础存储漏洞；HXA-034 的 approval hash/expiry/Proof/并发端到端语义及 HXA-036 UI 仍未实现。
- 2026-08-31 本地执行/审批/Git 文档边界收口：明确当前 E0/E1/E2/E2C 都在手机 CPU/内核上执行但使用不同 UID/权限边界，QuickJS/PRoot 不是 VM；通用 L2/L3 只有精确 ToolCall 的逐次批准，不提供模型自批、Advanced 自动批准或全局完全访问；新增 proposed [ADR-0008](adr/0008-git-workspace-management.md)与 HXA-088，在真机 Spike 决定仓库权威位置前，PRoot 的 Git 仅是离线 Job 工具而非持久仓库管理。上述 Git/Runtime/UI 能力均未因文档收口而实现。
- 2026-08-31 手机端 Tool 编排方案完成文档分析：新增 [11-mobile-tool-orchestration](11-mobile-tool-orchestration.md)，将外部 Harness 能力分为首版单 Agent 基础（单一安全管线、effect footprint、有界读并发、固定回填顺序、持久回放、interaction receipt）、HXA-105 后期候选（Advanced 深度 1 只读 child、声明式 JSON DAG）和明确不采纳项（权限/sandbox 自动升级、deferred network approval、可执行 Policy/Workflow DSL、自修改插件、云端任务舰队）。新增 HXA-037/HXA-105 与 proposed [ADR-0009](adr/0009-bounded-local-orchestration.md)。这些都是规划/候选，当前代码仍为单 Agent，M2/M3 未因此开始。
- 2026-08-31 远端基线已建立：`main` 与 `origin/main` 同步于 `1824569`（9 commits）。远端 Android CI 共 4 次运行：最早 1 次失败，随后 3 次连续成功；[最新成功运行 33364284426](https://github.com/dollarser/helix-agent/actions/runs/33364284426)发布了 `helix-apks-18245694c3ab96e81c0a595abce1a9da277065bc`（保留 1 天，截至核实时未过期）。远端 CI 证明构建/静态/文档门禁，不替代真机功能验收。
- 2026-08-31 全面审查修复完成（用户授权的单一 commit，不改变任何 HXA 交付物范围）：
  - **core:agent**：进程死亡不再泄漏 in-flight 模型调用 id（`committedCallId`/`activeCallId` 清零，resume/discard 从模型阶段死亡可用）；`FinalText(null)` 按契约完成（canonical `"stop"`）而非 `verify()` 崩溃；取消/丢弃时为未执行的排队调用记录 `Cancelled` 结局（不确定调用与已记录结局除外，行不留悬空 PENDING）；`RecoveryCoordinator.plan` 不再把终态 Turn 下的 PENDING 行停泊为“不确定副作用”；Goal reducer 预算可行性门（`Continued` 在剩余模型调用为 0 时忽略并保持 PAUSED、`BudgetsUpdated` 低于已用量的缩减忽略）；`ReminderPlan.forGoal` 与 ADR-0004 第 6 条对齐（仅 RUNNING/PAUSED 要提醒，INPUT_REQUIRED 入态时提醒已被 reducer 取消）。
  - **core:storage（v1 schema 未发布，直接加固并重新生成 v1 导出）**：`sessions.providerId`→`provider_configs`、`goals.planId`→`plans` 两个 SET-NULL FK + `goals.state`/`goals.planId`/`sessions.providerId`/`executions.toolCallId`（unique）四个索引；`FileContentStore` 并发写同内容竞态修复（唯一临时名 + rename 后回验）；`goalRuns.finish` 一次性守卫（`UPDATE … AND endedAt IS NULL` 影响行数 == 1）；`McpServerRepository.register` 默认 `enabled=false`（HXA-071 disabled-by-default）。
  - **core:model**：`ToolName` 严格 ASCII 字符集（Unicode 字母/数字拒绝）；`ExecutionLimits` 超时毫秒粒度（亚毫秒值在 canonical 编码 round-trip 会截断为 0）；`ProviderCapability.providerId` 类型化为 `ProviderId`；三处并行 hex 编码器统一为 `Hex.encode`；多份 KDoc 与文档/代码对齐（ExecutionState、Identifier、Risk、TurnState、GoalState 边图、GoalEvent 顺序契约、ReminderPlan）。
  - **测试**：JVM 新增 13 个（core:agent 147→159：死亡模型阶段 resume/discard、终态 `ProcessDied` 忽略、排队调用取消结局、`FinalText(null)`、终态 Turn 停泊守卫、预算门 ×3、outputCap headroom 分支；core:storage 44→45：并发写同内容 8 线程；`ExecutionLimits` 毫秒约束并入既有测试；core:model 75 不变；app 8/8 不变）；设备测试 core:storage 10→14（事务中途 FK 违规回滚、一次性守卫 ×5、孤儿 FK ×3 + executions 唯一索引、runtime/MCP/Skill/grants 七表 round-trip）+ app 6→7（时钟回拨早于 startedAt 的恢复钳制）。全量：`spotlessCheck`、`detekt`、5 个 shell 门禁、`git diff --check`、JVM 5 套件 + 设备 2 套件全部通过（API 36 arm64-v8a 模拟器）。
  - **门禁/构建**：`check-docs.sh` 新增完成记录契约（“Mx / HXA-NNN 已完成”条目必须有记录文件、M1+ 记录必含“决策记录：”）；`check-secrets.sh` 新增 `hf_`/`npm_`/JWT 模式并扫描二进制；`check-lockfiles.sh` 支持 Linux `sha256sum`；`verify-variant-boundaries.sh` consumer 侧对称扫描 `resources.arsc`；CI 增加 `:core:storage:lintDebug`（本地先行通过）、安装 `cmdline-tools;latest`（供 apkanalyzer）、超时 45→60 分钟；新增 `.editorconfig`（kt/kts `max_line_length=120`，与 detekt 对齐）；android library 统一 `testImplementation junit4` 基线（12 个 lockfile 重新锁定）；矩阵行号引用改为任务 ID 引用。
  - [ADR-0004](adr/0004-goal-run-wake-budget-semantics.md) 经主流 durable workflow / human-in-the-loop 实践核对后由项目所有者接受；主体保留，补入持久化 pause reason 与有界 crash 计量窗口，后者归 HXA-102 验证。
- M2 / HXA-020 已完成（M2 首个 HXA，2026-08-31）：`core:model` 封闭词表（`ProviderProtocol`/`ProviderResidence`/`NormalizedEndpoint`/`ProviderHeaders`/`SecretAlias`，全 fail-closed 解析）+ `provider:api` 类型化 `ProviderConfig`（含 `fromStorage` 严格恢复解析）+ `core:storage` `SecretStore` 接口与 `AndroidKeystoreSecretStore`（非导出 AES-256-GCM 主密钥、每 alias 加密文件 0600、篡改/密钥重置 fail-closed）+ `ProviderConfigSpec` 类型化 protocol 与规范存储（endpoint 存 `normalized.full`、headers 存排序规范 JSON）+ 显式 `overwrite`/`delete` 原语。residence 只从规范化实际 endpoint 派生（doc 10 §2.5 / ADR-0005 首个代码落点），同一 Ollama 模板指向 loopback/LAN/公网分类不同有双层测试断言（doc 07 §7.2）。Room schema 零新增列（alias-only 设计），`1.json` 无变化。core:model 96（+19）、provider:api 5（新）、core:storage JVM 46、core:agent 165、app 24 纯 JVM 测试 0 failures + API 36 arm64 模拟器 core:storage 28（+11，含真机 Keystore round-trip/篡改/无明文断言）与 app 7×2 flavor 0 failures；`spotlessCheck`/`detekt`/3 个 lint/5 个 shell 门禁/`git diff --check` 全部通过。证据见 [HXA-020 完成记录](completion-records/HXA-020.md)。

- M2 / HXA-021 已完成（2026-08-31）：`core:model` 内部统一契约——`ModelRequest`（文本消息 + 图像不透明引用 `ImageReference`（ArtifactRef + 封闭 mediaType）、`ModelToolSchema`（ToolName + 严格 JSON 对象 schema）、`ReasoningEffort`、采样参数，全 fail-closed 构造校验）+ `ModelEvent` sealed（doc 02 §6.1 九变体：TextDelta/ReasoningDelta/ToolCallStarted/ToolArgumentsDelta/ToolCallFinished/Usage/Refusal/Error/Completed）+ `ModelErrorCode` 封闭 8 类与 ErrorCode 映射。契约落位 core:model 共享内核（core:agent 唯一依赖），Agent Core 不依赖厂商 DTO。纯 JVM，core:model 119（+23）0 failures，全量回归与 5 个门禁脚本通过。证据见 [HXA-021 完成记录](completion-records/HXA-021.md)。

## In progress

- 无。

## Next task

- HXA-022 OpenAI Responses adapter（roadmap M2）：实现 Responses 流和 function calls；fixture 覆盖任意字节拆包、UTF-8、多个工具、拒绝、usage、无终止和断流。

## Blocked

- 无。

## Current interfaces

- `AppContainer` 目前只暴露 `ShellRepository` 接口；默认实现是 M0 使用的 `FakeShellRepository`。
- 七个导航 route 只承诺稳定的壳层入口，不表示对应业务能力已经实现。
- QuickJS 将使用主 App 内的 `isolatedProcess` Service。
- PRoot 将使用同签名但独立 applicationId/UID 的 Runtime APK，主 App 只通过 signature-protected Binder/PFD IPC 连接。
- 当前直接分发的唯一用户主应用计划由 developer 变体生成；consumer 继续构建/测试但不在默认下载清单。该发布角色尚未形成外部 release artifact。
- CLI 订阅后端若实施，将使用另一个有 INTERNET 的独立 UID；凭据由官方 CLI 持有。
- MCP 只实现 Client；Skill 按 `SKILL.md` 开放规范渐进加载。
- `read/write/edit/bash` 是规划中的稳定短工具名，但仍将受 scope、Policy、Approval 和执行域约束。

## Known limitations

- GitHub Actions 已有远端运行证据，但当前 workflow 不运行模拟器/真机测试；最新 artifact 是短期 debug 安装包，不是签名 release 或发布验收证据。
- Agent/Provider/MCP/Skill/Browser/文件操作/Accessibility/Root/QuickJS/PRoot 业务能力尚未实现；当前界面明确显示空状态。
- M0 只在 API 36 arm64-v8a 模拟器完成设备验收；API 29、多 ABI 和真机矩阵由后续能力任务按验收矩阵执行。
- 单一直接分发主包目前只是 ADR-0006 文档决定：developer APK 尚未以“Helix 主应用 + Standard 默认 + Advanced 切换”的完整产品流程完成设备验收，最终 applicationId/签名也未决定；不得把现有空壳 build 当作已发布主包。
- developer 主包未来声明 `MANAGE_EXTERNAL_STORAGE` 或 Accessibility Service 后，即使 Standard UI 不提供启用入口，Android 系统设置仍可能列出 Helix。Standard 保证“默认关闭、不会自动申请/启用、Agent 无 scope”，不保证系统设置完全隐藏这些声明。
- libsu/JitPack 仍是 HXA-094 的未来供应链决策点：当前仓库没有加入 JitPack 或 libsu 依赖。“计划路径 + 截止任务”只关闭了无人负责的治理缺口，不表示依赖来源已经接受或实现。
- Git 基线已建立（2026-08-31 用户授权）：首个 commit 覆盖 M0 + HXA-010～012，之后每完成一个 HXA 提交一版；继续开发前仍需先 `git status` 检查工作树。
- PRoot/CLI、结构化 Git UI 和持久 Git Workspace 尚未实现。HXA-081/086 未来即使通过也只证明离线 Runtime 中的 Git binary/smoke；HXA-088 与 ADR-0008 未决定前，不存在跨 Job `.git` 一致性、remote Git 或凭据能力。
- Tool Dispatcher/Scheduler、结构化用户 interaction receipt、child Agent、Agent graph 和 Workflow 尚未实现。ADR-0009 是 proposed，不表示未来一定加入多 Agent；云端任务/Remote Worker 仍明确不在当前范围。
- M0 空壳的中文 Compose 文本尚未资源化；简体中文/英文资源和硬编码扫描由 HXA-067 完成。
