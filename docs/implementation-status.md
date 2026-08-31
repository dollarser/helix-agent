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
- M1 / HXA-013 已完成：`core:agent` Goal reducer（`state + event -> state/effects`，复用 HXA-010 `GoalState` 零新增边）+ `GoalBudgets`（core:model，六项预算 + `stricterWith` + ADR-0001 canonical 编码）：run/wake 分离（只有用户显式继续创建新 run、重试不离开 RUNNING）、五类预算耗尽一律 PAUSED（`BudgetExhausted(limit)`，不得完成）、criterion 仅 verifier evidence 可满足且完成需全部有证据、进程死亡 RUNNING→PAUSED 且保留 checkpoint；`:app` WorkManager 可延迟提醒（唯一工作名 + REPLACE，过期 checkpoint 立即补发，worker 只发通知、零模型/工具调用——不变量计数器 + 设备测试证据，UI 文案无精确定时器）。core:agent 107 个、core:model 75 个、app consumer 8 个纯 JVM 测试 + API 36 模拟器 3 个仪器测试（提醒真实发出/REPLACE/取消）通过。run/wake 与预算耗尽语义记入 [ADR-0004](adr/0004-goal-run-wake-budget-semantics.md)（proposed）。证据见 [HXA-013 完成记录](completion-records/HXA-013.md)。
- 2026-08-31 起按用户授权建立 Git 提交基线：首个 commit 为 M0 + HXA-010～012 基线，此后每完成一个 HXA 提交一版（可回退管理）。
- M1 / HXA-014 已完成：`core:storage` Room 2.8.4 持久层（KSP 2.3.11）：doc 9.1 全部 22 张表（关系表 FK、唯一索引、`provider_configs`/`mcp_servers` 仅 alias 无明文密钥列）+ 22 DAO + 21 个仓库（plan 行与步骤行同事务、审批/verified 一次性守卫、artifact 文件+hash 先校验、plan 按 ADR-0001 从规范化列恢复并以 hash 列绑定精确版本）+ `ContentStore`（内容寻址、写后校验、原子替换）+ 存储层严格 JSON 编解码（criteria/ContentRef）+ `HelixStorage` 组合根与 `withTransaction`（9.2 事务入口）。JVM 44 个测试（含解析已提交 v1 schema 导出对 doc 9.1 的契约对照）+ API 36 模拟器 10 个 Room migration fixture 仪器测试（导出↔代码双路径 schema 对照、`PRAGMA foreign_keys=1`、FK 违规/级联、全部表 round-trip）通过；v1 schema 导出提交入库作为未来 migration 基线。证据见 [HXA-014 完成记录](completion-records/HXA-014.md)。
- M1 / HXA-015 已完成：恢复协调器（进程重启恢复）：`core:agent` 纯决策层 `RecoveryCoordinator`（从持久化事实——`turns`/`tool_calls`/`goals` 行——判定：非终态 Turn→INTERRUPTED 且死亡时 RUNNING 调用为不确定副作用源、仅 PENDING/RUNNING 调用停泊（AWAITING_APPROVAL 从未执行不动）、RUNNING Goal→PAUSED；确定性 plan 只标记/停泊/关闭、类型层不含重执行；resume/wake 双门——不确定调用未解决不可恢复、唤醒仅 READY/PAUSED/INPUT_REQUIRED）+ `:core:storage` 恢复扫描（`TurnDao.listActive`、`GoalRunDao.listOpenByGoal`）与类型安全写入（`ToolCallState` 重载、`HelixStorage.open/close` 收口 Room 边界）+ `:app` 可执行协调器（`RecoveryCoordinatorApp`：扫描→plan→单一 `withTransaction` 内状态更新+审计同提交，doc 9.2；goal 停泊保留 checkpoint/清零 wake、死亡 run 以 INTERRUPTED 关闭）+ `HelixApplication` 启动后台触发（幂等 no-op 安全）。core:agent 126 个（+19 恢复决策/跨层一致性/不重放）、core:storage 44 个 JVM 测试 + API 36 模拟器 6 个设备测试（含 3 个进程恢复 fixture：死亡停泊全断言+审计+幂等、唤醒门跟随恢复且死亡 run 永不重放、无副作用 Turn 直接恢复不重放）通过；ADR-0004 进程恢复条款落地（保持 proposed）。证据见 [HXA-015 完成记录](completion-records/HXA-015.md)。

## In progress

- 无。

## Next task

- HXA-016：Context Builder（roadmap：在 `core:agent` 实现可审计的上下文装配——`sourceType/sourceId/trust/contentHash`、Provider/token 预算、确定性裁剪、大 ToolResult 转 summary + `ArtifactRef`；只用 HXA-010 不透明引用 + fake repository，不做 artifact 文件存储/hash 生命周期/Workspace I/O（HXA-041）；当前工具参数、审批上下文与对应结果不得字符级截断；Secret 和未选中文件不进入请求）。

## Blocked

- 无。

## Current interfaces

- `AppContainer` 目前只暴露 `ShellRepository` 接口；默认实现是 M0 使用的 `FakeShellRepository`。
- 七个导航 route 只承诺稳定的壳层入口，不表示对应业务能力已经实现。
- QuickJS 将使用主 App 内的 `isolatedProcess` Service。
- PRoot 将使用同签名但独立 applicationId/UID 的 Runtime APK，主 App 只通过 signature-protected Binder/PFD IPC 连接。
- CLI 订阅后端若实施，将使用另一个有 INTERNET 的独立 UID；凭据由官方 CLI 持有。
- MCP 只实现 Client；Skill 按 `SKILL.md` 开放规范渐进加载。
- `read/write/edit/bash` 是规划中的稳定短工具名，但仍将受 scope、Policy、Approval 和执行域约束。

## Known limitations

- GitHub Actions workflow 已配置且 action 引用固定到 commit SHA；仓库尚未推送，因此没有远端 CI run 可引用。本地对应 Gradle 和 shell 门禁均已通过。
- Agent/Provider/MCP/Skill/Browser/文件操作/Accessibility/Root/QuickJS/PRoot 业务能力尚未实现；当前界面明确显示空状态。
- M0 只在 API 36 arm64-v8a 模拟器完成设备验收；API 29、多 ABI 和真机矩阵由后续能力任务按验收矩阵执行。
- Git 基线已建立（2026-08-31 用户授权）：首个 commit 覆盖 M0 + HXA-010～012，之后每完成一个 HXA 提交一版；继续开发前仍需先 `git status` 检查工作树。
- M0 空壳的中文 Compose 文本尚未资源化；简体中文/英文资源和硬编码扫描由 HXA-067 完成。
