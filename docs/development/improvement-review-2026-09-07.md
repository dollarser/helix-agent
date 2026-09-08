# Helix 全仓优化改进审查（2026-09-07，第二轮）

文档状态：快照（review snapshot）
性质：在 [2026-09-06 全仓审查](improvement-review-2026-09-06.md) 之后、仓库经历一大波开发（HXA-102 生产接线/Goal 模式硬化 + M11 订阅 Provider）后的第二轮全仓代码与文档审查。包含对上一轮问题清单的逐条回归核对、新代码引入的问题、文档治理现状与更新后的优先级路线图。**不是当前状态源**，当前状态以 [status.md](status.md) 为准。

## 0. 审查元信息

- **审查日期**：2026-09-07
- **基线**：`main` 4572911（领先 origin 88 提交）+ 工作区 **283 个路径未提交**（154 文件 +5,540/−2,267 行、129 个未跟踪新文件）。结论基于工作区（磁盘）状态。
- **本轮改动量化**（相对上轮基线 61bad35）：
  - 已提交：29 个 commit、140 文件 +10,393/−134 行——M11A 四平台（Codex/Claude/Grok/Copilot）订阅 Provider 主线、CLI Runtime handshake、订阅 Job 控制、编号消歧（`m11-main-numbering.md`，211fbb5）。
  - 未提交：Goal 模式硬化（HXA-102 生产接线收口）——app/goal 5 文件、app/chat Goal* 7 文件、core/agent GoalReducer/GoalEvent、core/storage v7→v9 迁移 + GoalTurnBinding/GoalUsageReservation 六文件、18 个新 Goal 设备测试、17 份新 bug-fixes；M11 静态清理（cli-app 登录 UI、CliRequestPipe/Awaiter/Wire）；ADR-0009 accepted、ADR-0028 proposed；ToolDispatcher/DispatchAudit 修改；OkHttpWireClient 异步化。
- **方法**：六个领域并行深度审查（Goal 硬化 / M11 安全 / core 回归+新改 / runtime+tools 回归+新改 / app+feature 回归+新改 / 文档体系），高严重度发现由父会话独立回源码复核（S1 重定向经四次独立确认：父会话 grep、M11 审查、app 审查、工作区 diff 比对）。
- **门禁基线（实测全绿）**：`check-docs.sh`（232 md / 130 HXA）、`verify-adr.sh`（28 ADR）、`check-i18n.sh`（843 键三套 parity、312 生产文件）、`check-secrets.sh`。

## 1. 总体评价

这轮"大优化"是**高质量的新功能工作**：M11 凭据隔离边界经机械验证非常扎实（7 个 ADR 全部 PASS）；Goal 的 reservation 恰好一次语义、恢复顺序、删除级联、提醒持久事实驱动都有真实设备测试钉住；测试改动经核查**零弱化**（16 个代表 diff 全是新增回归/flake 修复/等价门禁加强）；四道静态门禁全绿。

但两个结构性问题：
1. **上轮 P0/P1 清单几乎未被消费**——S1 重定向、S2 symlink 逃逸、U1~U6 用户缺陷、S5 raw 文案、C1~C11 并发项基本全部原样（详见 §2.3）；
2. **新代码自身带有 1 个产品级语义缺陷**（Goal maxRetries 死控，§3.1 H1）与若干 ADR-0004 可审计性缺口；治理层面 **status.md 与真实工作脱节**（HXA-102 收口不在 In progress，新会话会接错任务，§4 H1）。

---

## 2. 上轮问题回归核对

### 2.1 已修复

| 项 | 修法证据 |
| --- | --- |
| main-merged-verification.md "仍在执行"占位行（上轮 HIGH） | 文件已整体重写为实际结果（250 行，无占位词；文件本身未提交） |
| ADR-0009 状态（上轮 M10 收口条件） | 所有者 2026-09-06 授权接受；ADR 文件规范重写（"接受架构约束 vs 生产启用门禁"阶段划分 + 授权审查节 + 9 个补充反例测试）；AGENTS/roadmap/status/architecture/implementation-guide 五处同步 |
| OkHttpWireClient 取消语义（上轮 LOW 级"取消后读继续到下一 chunk"） | 重写为异步 `enqueue` + `suspendCancellableCoroutine`：`invokeOnCancellation{call.cancel()}`、放弃时 `abandoned.body.close()`；新增 `OkHttpCancellationTest` 钉住（**注意：S1 重定向问题未随之修复，见 2.3**） |
| ToolDispatcher InterruptedException 路径（上轮 C8 前半） | `ToolDispatcher.kt:713-717` 补 `future.cancel(true)` 再恢复中断位；新增 `awaitExecution`（:721-746）100ms 切片等待期响应取消；7 个新测试全钉真实行为（deadline=execStart+min(descriptor,预算)、阻塞中取消 <5s 结算 CANCELLED_AFTER_START 且线程确被中断、persist 顺序等） |
| QuickJS INTERRUPTED 重试缺陷 | `CodeJavascriptRunTool.kt:230-237`：INTERRUPTED + cancel → `Cancelled`（旧语义 `Failed(sideEffectFree=true)` 会触发 dispatcher 技术重试=**取消后重新执行 JS**）；`interruptedAfterUserCancellationSettlesAsCancelled` 钉住 |
| HttpFetch connect 阶段 deadline（上轮 S3 一半） | `HttpFetchBridgeImpl.kt:139-144, 366-371`：`remaining` 传入 connect，`minOf(CONNECT_TIMEOUT_MS, remainingMillis)` |
| 模型流 `finishReason=="length"` 冒充 Completed | `ModelStreamState.kt:138` → `protocolFailure("TOKEN_BUDGET_LIMIT")` + 三套资源 `model_error_token_budget_limit` 在位（正确修复，行为变化建议 HXA 记录留决定） |
| 上轮 F17 SGLang endpoint smoke 重跑 | 已闭合（三协议真实验证 + 固定评测推进至 36/45 + Goal 3 项当前 APK 证据） |

### 2.2 部分修复

| 项 | 现状 |
| --- | --- |
| S3 HttpFetch 慢速滴流 | 仅 connect 段兑现 deadline；`readUntilClosed`/`readLine`/`readFully`（:296-361）读循环**仍无 deadline 复查**，1 字节/19.9s 滴流仍可无限挂住线程+socket |
| C8 ToolDispatcher 线程池 | 中断路径已修；`EXECUTOR_SERVICE` 仍 `newCachedThreadPool` 无界（:983-986）；`ToolSchemaValidator`/`ToolSchema.check` 仍无递归深度上限 |
| P1 每 token SP 写 | `AppContainer.kt:732-737` 仍 collect 整个 `screen`，每 emission 调 `checkpointTurn`（参数收窄为 id+state，但无去重）；无 `distinctUntilChanged` |
| L6 SecretStore renameTo 回退 | 回退仍非原子 copy；已加注释但未记录"并发 get 读半写 → GCM 误报 corrupt"真实风险 |
| L4 JsExecutionService join 超时 | 仅补注释（"abandoned to unbind/reclamation"），无 interrupt |
| status SAF scope 三处矛盾（上轮 HIGH） | 措辞现可区分（工具 scope vs 浏览后端），残留歧义见 §4 L6 |
| core 测试缺口 | 部分补齐：A2aTaskRepository/McpConfig/HighSensitivityRule/MessageAttachment 已有 Fake-DAO 单测；**ConversationRepositories 其余 10 类与 PlanGoalRepositories 全部 3 类仍无直接单测** |

### 2.3 仍未修复（上轮 P0/P1 主力，本轮未消费）

**安全**：
- **S1 Provider 重定向**（`provider/api/.../OkHttpWireClient.kt:41-46` 仍默认 builder，全仓 `followRedirects` 零命中；影响 = 所有 API Key Provider 流量，307/308 可携自定义凭据头+完整会话 body 跨主机转发、https→http 静默降级。订阅 Runtime 侧 9 处客户端全部正确设了 `followRedirects(false)`，缺口只在主 App wire 路径）
- **S2 PRoot symlink 逃逸**（prootArgs 仍无 `-S`；buildOutputArchive/JobZipWriter 仍跟随链接；PRoot 三模块自 HXA-084 零改动）
- **S5 raw 异常文案上屏**（FileManagerService :587/589/609/650/697/714、FilesScreen :360/:576、EgressRuleSection :80/114/118/127 全部原样）
- **S6** SSRF scoped 主机名跨类 rebinding；**S7** 时间窗口 Long 溢出；**S8** A2A 50ms 固定轮询（POLL_MILLIS=50L 未变）

**用户缺陷**：
- **U1** FilesScreen SAF 移除崩溃（:230 `sources.first{}` 未变）
- **U2** 拦截丢 composer 文本（`send()` 仍 fire-and-forget 返回 Unit）
- **U3** share intent 重放（MainActivity :76-78 仍无 `savedInstanceState==null` 守卫）
- **U4** >1 MiB 输出 PRoot Job 假失败（ProotJobRecord :71-72,159 仍绑 1 MiB 元数据预算）
- **U5** PENDING 阶段 Job 不可取消（cancelRequested 仍为 runJob 局部变量）
- **U6** ArtifactRepository.register `file.readBytes()` OOM（ConversationRepositories.kt:747 未变）
- U7 Skill 导入/激活 dead zone；U8 bounded() 513 字符越界

**并发正确性**：C1（A2A UPDATE 仍无 CAS、markDirectCompleted 仍无条件清 taskId）、C2（decodeAutomation/decodeRoot 仍无 exactly() 守卫，短输入仍抛未捕获 IOOBE）、C3（SHA-256 校验仍 7 处互不一致，且 ApprovalBinding.kt:114 是第 6 处 hex 编码重复）、C4、C5、C6、C7、C9、C10、C11、runtime L1/L2/L3/L6/L7/L8 全部维持原判（逐条 文件:行 证据见 runtime 域报告）

**工程**：CI 缺 check-i18n.sh 门禁（ci.yml 本轮改动只是 lint 任务合并为单条 gradlew 命令）；spotless/detekt 仍未排除 `.claude`/`.codegraph`；check-lockfiles 仍硬编码 35。

### 2.4 恶化（2 处，优先注意）

1. **ChatService 3041 → 3244 行**（Goal 接线 +200，无任何拆分；上轮建议的 ⑥⑦⑧ 纯 JVM 单元未动）
2. **`JsAbiAssembly` KDoc（:131-135）现明确声称 "backslash continuation fails CLOSED as a JS syntax error"——经构造验证为假**：userSource 末行 `\` 续行可吞掉闭括号，wrapper 控制段整体被吞进 helixMain 函数体，IIFE 永不调用 helixMain、顶层返回 undefined → 桥接 "null" → **假 SUCCESS**（用户代码未执行）。`build()`（:142-171）无任何守卫、JsAbiAttackTest 无此用例。文档把可构造的假成功路径声明成 fail-closed，比上轮更危险。

---

## 3. 新代码引入的问题

### 3.1 Goal 模式硬化（HXA-102 生产接线，未提交）

**HIGH**

**G-H1. `maxRetries` 重试机制在生产路径完全失效：任何非限额 turn 失败都终态杀死整个 Goal；若将来接入 retryable 错误会造出永久卡死 Goal**
- **位置**：`app/.../chat/GoalRunSettlement.kt:88-101`（decision()）；`core/agent/.../GoalReducer.kt:141-156`（onWakeFailed）；`app/.../ui/GoalDialog.kt:167,183`（retry 字段 UI）
- **证据**：① 生产唯一 `GoalEvent.WakeFailed` 调用点在 `GoalRunSettlement.decision()`，HelixError 硬编码 `retryable=false`——用户可编辑、可持久化、UI 展示的 `goal_retry_limit` 字段（默认 0，编辑器允许改大）在真实运行中**没有任何效果**：一次网络抖动/INTERNAL 错误 → 直接终态 `FAILED`，用户必须重建目标。与 ADR-0004 §1 "WakeFailed 在 maxRetries 内重试时不离开 RUNNING" 不符。② 设计地雷：即使接上 retryable，`decision()` 对 WakeFailed 一律返回 outcome "FAILED"（关闭 run），而 reducer 重试路径保持 Goal=RUNNING 并发 `RetryWake`——**全仓无 RetryWake 消费者**（grep 0 命中），结果 = Goal 停在 RUNNING + run 已关 + 无 open run + `onContinued` 只接受 READY/PAUSED/INPUT_REQUIRED → **永久卡死无出口**。现有测试 `cancelledAndFailedTurnsCloseGoalsAndCannotContinue` 固化了当前行为，但无人注意到 maxRetries 死了。
- **建议**：二选一——(a) 完整接线：按 turn errorCode 分类可重试性、重试路径不关闭 run、协调器消费 RetryWake；(b) **首版收窄（推荐，ROI 最高）**：从 UI 移除/禁用 retry 字段，把"可重试型"失败（网络/INTERNAL）映射为 `RunFinished→PAUSED`（可恢复）而非终态 FAILED；同时给 decision() retry 分支加测试封锁。

**MEDIUM（6 项）**

- **G-M1 时间分块结算跨预算边界**：`app/.../recovery/GoalUsageReservations.kt:92-104`（trailingMillis 5s 分块 while 循环）× `GoalDurableUsageLedger.kt:73`（`require(goal.state == RUNNING)`）。finish() 可结算 >5s 的 elapsed；若某中间分块恰好耗尽预算（persist 翻 PAUSED + 关 run），下一分块 require 抛异常 → settle 事务回滚 → turn 被终态化为 `FAILED(INTERNAL)` 覆盖原 COMPLETED → settlement 映射 INTERNAL→WakeFailed→**Goal 终态 FAILED**（本应 PAUSED(BUDGET_EXHAUSTED) 可恢复）。触发条件：监控协程停顿 >5s 且预算边界落在 overrun 区间（设备重载/ANR 级 IO 延迟可达）。现有测试只覆盖未耗尽的 6.5s 场景。**建议**：分块循环遇预算耗尽即停（或 ledger 对已耗尽 Goal 后续分块带审计 no-op）+ 跨边界设备测试。改动 <30 行。
- **G-M2 UI 状态竞态 → AssertionError 崩溃**：`GoalDialog.kt:63`（`check(service.updateGoalBudgets(...))`）、`GoalReminderControls.kt:33`（`check(service.setGoalReminder(...))`）把"reducer 因状态已变而忽略"当 check 失败；点击与保存之间并发 wake（WorkManager 提醒触发的 run）可致返回 false → `AssertionError` 是 Error，现有 catch（IAE/ISE）不接 → 崩溃。**建议**：false 当普通业务失败走 `goal_save_failed`/`goal_reminder_sync_failed` 本地化路径 + "保存期间 Goal 翻 RUNNING 显示错误文案"设备测试。
- **G-M3 PAUSED 原因编码混淆**：ADR-0004 §2 要求三种稳定持久化原因（RUN_FINISHED/BUDGET_EXHAUSTED/PROCESS_INTERRUPTED），全仓 "PROCESS_INTERRUPTED" 零命中——进程死亡 park 与时间窗停滞（GOAL_TIME_WINDOW_EXPIRED）**都**写 run outcome "INTERRUPTED"，UI 给同一标签，审计/恢复无法区分。**建议**：进程死亡用独立 outcome，时间窗停滞用独立语义，同步 goalPauseLabel 与测试。
- **G-M4 删除在 Room 事务内阻塞 WorkManager**：`GoalDeletionCoordinator.delete` 在 `storage.withTransaction` 内调 `cancelReminder`（生产 enqueuer `await(cancelUniqueWork)` 最坏 10s）→ 删除事务持 DB 写锁 10s，其他会话 turn 结算/提醒 reconcile 全排队。**建议**：cancel 移出事务，靠既有 `GoalReminderReconciler`（goal 不存在即 cancel，本批已实现有测试）兜底 + 补"删除后 reminder 迟到发布"断言。
- **G-M5 无 run 的 READY Goal 在所有会话重复出现**：`GoalSummaryUi.kt:48-50` 过滤条件对 runs 为空的 Goal 直接放行（goals 表无会话归属列，首个 run 的 bind 才绑定会话）→ A 会话创建、B 会话可见并可"继续"（Goal 归属 B）。**建议**：加创建会话归属或 UI 过滤 + `forSession` 设备测试。
- **G-M6 时间窗过期对监控停顿零容忍**：`GoalTimeBudget.kt:61-76` 监控协程两次 pulse 间被挂起 ~4s+（GC/IO 争用）即判 GOAL_TIME_WINDOW_EXPIRED 取消整个 turn——即使剩余预算充足。**建议**：过期分支先尝试一次 settle+renew，仅 renew 因预算不足失败才停。

**LOW（8 项）**：① 通知文案硬编码英文（`GoalReminderPayload.text()` "Goal checkpoint - open Helix to continue." 与 worker title 未走资源键——本批新增 33 键三语齐全，这两处遗留应顺手收编）；② `GoalSummaryQuery.forSession` N+1（每 turn 一次 byTurn、每 Goal 一次 listByGoal/resolve，对话框每次刷新全量跑）；③ `GoalModelCallBudget.finish` 的 `requireNotNull(byId(id))` 热路径 IAE 风险（已结算应幂等 no-op+审计）；④ `continueGoal` 只捕获 IAE，`bind`/`start` 的 ISE（"Goal cannot span sessions"）会崩进程；⑤ `goalStateLabel` else 分支把未知/损坏 state 显示为"已取消"；⑥ AppContainer 每次 goalReminderSync 都 new `GoalReminderScheduler.create(context)`（建议单例化）；⑦ `consumeDelivered` 内联全限定 `UUID.randomUUID()`；⑧ **CI/验证缺口**：bug-fix 文档自述"全仓 Detekt 仍有原有 53 项问题，联合命令 exit 1"，本轮 CI 改为 `spotlessCheck detekt test lintDebug lintRelease lintConsumerDebug lintDeveloperDebug lintConsumerRelease lintDeveloperRelease` 但**未验证当前工作区能否全绿**——合入前必须本地跑一遍；18 个设备测试中 3 个 host-fixture 型（ProcessKill 系列）依赖 `scripts/run-*.py` 手动编排，不在 GitHub CI 内。

**Goal 测试覆盖缺口（未被钉住的不变量）**：① 时间结算跨预算边界（G-M1）；② maxRetries 生产行为（reducer 层测了、接线层没测——死控正因如此）；③ 进程死亡 vs 时间窗停滞的原因区分（都断言 "INTERRUPTED"）；④ **带工具调用的 Goal turn E2E**（现有 `GoalModelCancellationDeviceTest` 显式断言 toolCalls isEmpty；TOOL 预算只有 reservation 层测试）；⑤ **模型流正常完成的 Goal turn E2E**（现有 E2E 全是中断/取消/kill 路径，没有一条"Goal 模式真的能跑完一个任务"的设备证据）；⑥ UI 保存/提醒竞态；⑦ READY Goal 跨会话展示。

**Goal 做得好（不应改动）**：`goal_usage_reservations` PENDING→SETTLED/INTERRUPTED 条件 UPDATE 从 SQL 层保证恰好一次（`GoalUsageReservationDao.settle` `WHERE state='PENDING'`）；`recoverRun` 只对 PENDING 记账一次绝不重放；`MAX_UNACCOUNTED_MILLIS=5s` 把 crash 未记账窗口真正有界化（ADR-0004 §5 落地）；`applyGoalPark` 先 recoverRun 再 park、双跑幂等多测试重复断言；结算与 turn 终态同事务（重复 settle 幂等）；提醒子系统"持久事实驱动"（reconciler 只读 goals 行重建 WorkManager 队列，通知点击只导航不 Continued，重复/丢失/乱序三态有真实 WorkManager 设备测试）；删除 fail-closed 且级联完整（kill-in-transaction 与 kill-after-commit 两相测试）；ADR-0004 检查顺序被钉住（wake 先于 lifetime）；迁移保守（"existing Turns are never guessed into Goals"）；ChatService 接线克制（预算/结算/协调/reconcile 全在独立小类，UI 不触 DAO）；host-fixture SIGKILL 测试方法论强（恢复断言精确到 modelCalls=1、tokens=预留额、二跑 recover 零写入）。

### 3.2 core/ 新改动（同批）

**无 HIGH**。迁移与 reducer 质量高：v7→v8（goal_turn_bindings）→v9（goal_usage_reservations）均空表无猜测回填、`IF NOT EXISTS` 幂等、MigrationTestHelper 全链路 1→9 + schema JSON parity 升到 v9、`ALL_MIGRATIONS` 双入口注册、SQL 与 @Entity/8.json/9.json 逐列比对一致；`GoalRunDao.checkpointUsage/updateOutcome` 的单调性 CAS（`COALESCE(...) <= :new` + repository `require(==1)` + 稳定文案）是全仓缺失的 C1 类守卫的**正确范式**（先例现已在树内）；reducer 纯函数纪律保持（`object` 无状态、每步经 `step()`→`verify()`）。

**MEDIUM（4 项）**

- **B-M1 `GoalTurnBindingDao.bind` 是新 check-then-act（TOCTOU）**：`dao/GoalTurnBindingDao.kt:50-58`（`@Transaction` 内 `require(activeRun)==1` + `require(otherSessionBindings)==0` + insert）。SQLite deferred 事务下两个并发 bind（不同 session 绑同一 run）可在对方 insert 可见前都通过检查 → 两行都落库，goal run 跨 session——直接破坏 ADR-0028 §3（proposed）"候选证据必须归属同一 Goal 的持久 run/Turn，不接受跨会话"归属不变量，也破坏 `GoalTurnBindingRepository.sessionForGoal` 的 `singleOrNull()` 单 session 假设（跨 session 时静默返回 null）。**建议**：合成单条原子语句 `INSERT ... SELECT ... WHERE NOT EXISTS(...)` + `check(rowsAffected == 1)`，与 GoalRunDao 的 WHERE 守卫同范式。
- **B-M2 `updateGoal` 整行回写、无 CAS，且本次扩大了写面**：`PlanGoalRepositories.kt:113-136` + PlanGoalDaos（`WHERE id = :id` 仅此）。本次把 budgets/criteria/planId/planHash 加入 SET 子句（修复了真实缺口：此前整行更新不写这 4 列，BudgetsUpdated 后进程死亡会丢预算扩展与 verifier 证据），但代价是陈旧 in-memory `StoredGoal` 现在也能把这 4 列**回退**（lost update），而 goal 主行无任何 state/version CAS（对比同文件 GoalRunDao 已有单调性守卫）。**建议**：UPDATE 加 `AND state = :expectedState`（或单调 runCount/版本列）+ `require(affected == 1)`。
- **B-M3 时长预算 `==` 边界：park 原因与可恢复性矛盾（ADR-0004 第 2 条）**：`GoalReducer.kt:258-260`（canStartRun：`runTimeMillis < max`）vs `:263-271`（firstExhaustedLimit：`runTimeMillis > max` 严格大于）。`runTimeMillis == max` 时：不发射 BudgetExhausted → RunFinished 以 RUN_FINISHED 原因 park；但下次 Continued 被 canStartRun 静默忽略（`<` 不成立）→ 用户看到"运行结束、点继续"，继续却无反应直到扩预算。RUN_FINISHED 语义上可恢复，此处不可。该 off-by-one 在 calls/tokens 维度本就存在（`remaining >= 1` vs `>`），本次扩到时长维度；新测试钉住了 == 边界"Continued 被忽略"但没钉 park 侧 BudgetExhausted 缺失。**建议**：统一边界 + 补"usage 恰好到 max → park 且发 BudgetExhausted"测试。
- **B-M4 新 core 仓库无直接 JVM 单测**：GoalTurnBindingRepository/GoalUsageReservationRepository（新增 6 文件）均无 core/storage/src/test 测试（模块内 Fake-DAO 模式现成，6 个先例）；settle 的 "charged once, never replayed"、insert 封闭 kind 校验、bind 两条 require、sessionForGoal 0/≥2 session 语义均无 core 层钉住——延续上轮"core 最大测试缺口"模式。**建议**：补两个 Fake-DAO 测试类。

**LOW（5 项）**：① CheckpointCleared 扩展了 ADR-0004 第 6 条提醒生命周期（加性扩展、无冲突，但应在 HXA-102 完成记录或 ADR-0004 补记"不改变已接受语义"；同落点的 `onCheckpointScheduled` 放宽到 PAUSED 反而修复了与 ADR 的不一致，方向好）；② INPUT_REQUIRED 下 checkpoint 无法清除（核心语义自洽，UI 需处理该窗口）；③ onCheckpointCleared 幂等空操作带 effect（ReminderCancelled 仍发，协调器须当 no-op，建议补测试）；④ `RoomMigrationFixtureTest.kt` 全链路注释仍写 "1 -> ... -> 7"（代码已到 9）；⑤ **8.json/9.json 与 6 个新 kt 文件均 untracked，漏提 JSON 会使 `runMigrationsAndValidate` v8/v9 校验在 CI 直接挂**——提交检查项。

### 3.3 M11 订阅 Provider（已合入 + 部分未提交）

**总体**：凭据隔离是真正机械的——vault 在独立 UID + `allowBackup=false` + Keystore AES-256-GCM（key 不出 AndroidKeyStore）+ 0600 + 原子 rename + schema 严格校验；wire 层只有 jobId（正则）/SHA-256/枚举事件；status 只有登录态；Room 只有 `NO_KEY_ALIAS`；错误面全部封闭分类；runtime 源码零 `Log.*`。`CliCallerVerifier` 与 ProotCallerVerifier 同语义且**逐 onTransact 执行**。Job 生命周期纪律完整（单线程执行器、单活动 job、取消胜过迟到响应、reconcile 经 PFD 回传 + output hash 校验、断连只查原 jobId、重启停泊 INTERRUPTED 不重放、Awaiter 超时/中断恰好取消一次）。边界脚本 `check-cli-runtime-boundary.sh` 把 consumer 排除/IPC 无凭据词/固定 endpoint/禁外凭据导入变成 DEX 级机械断言。

**HIGH**

- **M-H1 = 上轮 S1**：`OkHttpWireClient.kt:41-46` 仍未设 `followRedirects(false)`（本波只改了取消语义）。详见 §2.3。

**MEDIUM（3 项）**

- **M-M1 Codex loopback OAuth 回调服务器**：`CodexLoopbackServer.kt:101-117` `ServerSocket(port, 4)` 未指定 InetAddress → 绑 0.0.0.0（RFC 8252 建议只绑 loopback；局域网对端可直连 1455/1457）；`soTimeout` 只对每次 accept 生效，错 state 回调循环可**无限拖延 180s 登录窗口**（登录窗口 DoS + 占 backlog 阻塞合法回调）；裸 HTTP 请求行解析（8KiB 上限/`GET`+`HTTP/1.x` 校验/路径前缀/一次性消费）安全敏感面**零单测**。**建议**：`ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))` + 全局墙钟 deadline + readTarget/state 错配/一次性消费/超时单测。
- **M-M2 vault 加密层零测试，ADR-0021 验收未闭环**：`CliSubscriptionCredentialVault.kt:133-218`（`AndroidCliSecretStore`）的 AES-256-GCM 文件存储层——IV 前缀布局、GCM 认证失败 fail-closed、原子 rename、`Os.chmod 0600`、`MAX_ENCRYPTED_BYTES` 边界——零测试（现有 vault 测试全用 MemorySecretStore；androidTest 只有 2 个文件均不覆盖）。ADR-0021 HXA-115 acceptance 明确写"删除与篡改 fail-closed 威胁模型 + API 29/36 arm64-v8a 设备测试"——**篡改路径（flip 1 byte → GCM 认证失败 → 稳定拒绝而非崩溃/误判 LOGGED_IN）至今无证据**。**建议**：补 cli-app 设备测试（篡改密文 → 稳定 IAE；写入中途 kill 的原子性；logout 后文件消失；publicState 在 CREDENTIAL_ERROR 下不出 token）。
- **M-M3 ProviderFactory consumer 纵深防御缺口**：`ProviderFactory.kt:43-48` 对任何未知 config 回落普通协议适配器；consumer 侧当前不可触发（flavor 不注册 + applicationId 不同 Room 不共享），但若某路径（未来迁移/备份恢复/手动注入）让 consumer Room 出现 `subscription-codex` 行，会构造一个用占位凭据向 chatgpt.com backend-api 发裸请求的 OpenAiResponsesProvider 而非稳定拒绝。managed 的编辑/删除都有 `require(!isManaged)` 拦截，唯独"创建"是开放回落。**建议**：managed id 但 additionalFactory 返回 null 时抛稳定 IAE + consumer 单测固定。

**LOW（5 项）**：① `CliEmbeddedBaseline.kt:31` 仍上报 `VAULT_READY_ADAPTERS_NOT_REGISTERED`（标签漂移，developer 已注册四平台）；② 401 刷新重试不对称（Codex 有 401→refresh→重发，Claude/Grok/Copilot 仅预刷新、401 直接 AUTH 错误——均满足 ADR-0027，统一建议留测试）；③ **协议仿冒头为已接受的残余风险，建议挂账 status.md**：Copilot 带 `User-Agent: GitHubCopilotChat/0.35.0` 等、Grok 带 `x-grok-client-surface: ui`、Codex 带 `originator: codex_cli_rs`——ADR-0025/0026 所有者已接受的第三方 adapter 路线固有 ToS/风控暴露（固定 client identity + 私有 endpoint + 官方客户端 UA），非缺陷；"身份被撤销/endpoint 漂移/封号"应列为运行风险跟踪；④ LanScope 语义：`LanScopeStore.normalize` 丢 scheme（http scope 也放行 https——符合 ADR-0005 "精确 host+port" 字面但与数据目的地分类的 scheme 感知不完全一致）；本波未加剧上轮 S6 地址类缺口（`checkLanHost` 仍全候选校验 + peer 复验），LanScopeStore 自身 fail-closed 纪律正确（损坏→空集、add 要求 Advanced、64 条上限）；⑤ Codex device flow 的"PKCE"实为服务端签发 verifier（只验响应内部 S256 一致性，不构成客户端 PKCE 保护，安全依赖 TLS——澄清项，实现与协议一致）。

**ADR 符合性核对表**：0007 companion 生命周期 **PASS**（冷绑定/预检/单活动 job/重启停泊/不重放全证据在）；0020 CLI 执行底座 **PASS 附流程说明**（`bundled=false` 强制、agentBackendState 恒 NOT_REGISTERED；0020 正文"不实现登录 Activity"在实质层由 accepted 0021+0025 改边界授权，但 0020 仍标 proposed 且未标 superseded-by，建议补状态注记）；0021 第三方 adapter 边界 **PASS**（边界脚本 grep cookie/外凭据导入零命中、UI 非官方警告文案在位）；0024 生产停止线 **PASS**（consumer DEX 字符串检查）；0025 developer 渠道 **PASS**（NO_KEY_ALIAS、CONSUMER_STORE 保留 DISTRIBUTION_AUTHORIZATION_UNPROVEN 门禁、accountIntent 固定 ComponentName、wire 无 token）；0026 Copilot 固定身份 **PASS**（固定 client id + 三 endpoint 逐行锚定、verification_uri 严格相等、交换失败不落盘、11 个用例覆盖）；0027 显式路由 **PASS**（v2 封套封闭枚举、SHA-256 覆盖完整封套→同 jobId 换平台=hash mismatch、512KiB/1MiB 上限、旧 Runtime 严格 v1 decoder 用真实旧 APK 回归）。

### 3.4 runtime/ + tools/ + spikes/（新改动）

上轮 19 条核对：15 not fixed、3 partially fixed（S3 connect 段/C8 中断路径/L4 注释）、1 恶化（L5 KDoc 假主张，见 §2.4）。PRoot 三模块本波零改动。

**新 MEDIUM（2 项）**

- **R-M1 `awaitExecution` cancel 分支丢合法结果**：`ToolDispatcher.kt:721-746`——100ms 切片内"用户点 Stop 时 executor 恰好完成"，`future.cancel(true)` 对已完成 future 返回 false 但结果被丢弃，一次合法 Completed（可能已产生副作用）被结算为 CANCELLED_AFTER_START（"side-effect state is unknown"）。保守方向没错但丢了本可交付的结果。**建议**：检查 `cancel(true)` 返回值，false 时 `future.get()` 取回真实结果。
- **R-M2 MCP 到 deadline 结果码非确定**：`McpToolRuntime` 的 `withTimeout` 抛 TimeoutCancellationException → dispatcher `rethrowExecutorFailure` 原样上抛 → catch(Throwable) 结算 **TOOL_FAILED**（"unexpected dispatch failure: TimeoutCancellationException"）而非稳定 TIMEOUT；新 awaitExecution 切片使 dispatcher 自身 TIMEOUT 最坏晚 100ms → **MCP 工具到 deadline 几乎总是 TOOL_FAILED**。同一条件两种模型可见文案/审计码。**建议**：dispatch catch 对 kotlinx CancellationException 单独映射稳定码（或 McpToolRuntime 捕获转 TimedOut 语义）。

**新改动正面确认**：`CliRequestPipe`（新）修复旧 `Thread.start()` fire-and-forget 竞态（构造即写、AutoCloseOutputStream 写毕自关给 server EOF、close() join(1s) 后 hasFailed 覆盖为 HANDSHAKE_FAILED）；`CliModelJobAwaiter`（新）wait 被中断时**先按 job ID cancel 再返回 TimedOut**（修了旧裸返回不取消的 bug）+ 6 JVM/4 设备用例；`CliModelJobWire`（新）异常全映射 HANDSHAKE_FAILED 无跨边界泄漏、outputSha256 校验保留；`McpToolCancellationTest`（新）钉住的是生产真实实现（withTimeout + NonCancellable close + 取消不重放）；`spikes/bounded-orchestration` 改动与 ADR-0009 方向一致（全部协调方法 `synchronized(journal)` 消除 check-then-act、`Math.addExact` 防溢出、`complete()` 新增 `require(trust == "untrusted")` 防 child 自我提权；20 线程争 2 槽真验证）——LOW 残留：synchronized 正确性依赖"同 parent 共享同一 journal 实例"前提应在 KDoc 写明；settings.gradle.kts 生命周期注释仍未加。QuickJS `JsExecutionWire readInfo` 的 classLoader 修正等价无回归；androidTest 的 `clientObservingExecute` 修掉了 300ms sleep 与冷绑定竞态的 flake 源。CodeJavascriptRunTool 的 mapResult INTERRUPTED→Failed(sideEffectFree=true) 分支现为近似死代码（非单调信号触发仍允许重试，建议该分支 sideEffectFree=false 或断言）。

### 3.5 app/ + feature/（新改动，Goal 文件除外）

上轮 15 条核对：12 not fixed、2 not fixed 但形态变化、1 regressed（A1，见 §2.4）。逐条 文件:行 证据见 app 域报告。

**测试弱化核查（专项结论）**：**未发现任何弱化断言/放宽门禁性质的修改**。154 文件树变更中测试改动全部是四类：新增回归测试（全部对应 bug-fixes/ 记录）、flake 修复（waitForIdle→waitUntil、观测真实事务替代 sleep 线程）、等价门禁+更好诊断（断言 service row status is Passed 比原 UI 断言更强）、唯一一处时限放宽（QuickJS watchdog 1.5s→10s，注释明确为冷绑定 flake 治理，核心断言未动——备案）；`ConnectorProcessRecoveryDeviceTest` 的 assumeTrue 相位门是 driver 设计而非弱化（反而堵住旧版假绿/假红路径）。

**新 MEDIUM（3 项）**
- **F-M1 `BrowserController.onRendererGone` 与关闭路径不对称**：`:506-513` renderer 死亡时 `hosts.remove(id)` 不带 `?.destroy()`（关闭路径 :85 带）。当前不出错（onRenderProcessGone 已先 disposeView），但正确性依赖"两个回调严格先后"的隐含约定；未来调整 dispose 时序即泄漏/复用死 WebView。**建议**：统一 `hosts.remove(id)?.destroy()`（destroy 已幂等）。
- **F-M2 `ImageNormalizer` 引入 `androidx.exifinterface:1.4.2`**：版本 pin + THIRD_PARTY_NOTICES 补条目 + lockfile 更新，合规；属 AGENTS.md "任务未要求不升级依赖" 的边界情形（替换被 Lint 标记的框架 ExifInterface，修复性引入），建议对应 HXA 记录留一句依据。
- **F-M3 TOKEN_BUDGET_LIMIT 行为变化**（见 §2.1）：对故意在 maxOutput 截断但前缀合法的合法流（token 预算压很小的自托管 provider）现在一律 FAILED——语义正确（输出确实不完整），属行为变化，建议 HXA 记录有决定。

**新 LOW（4 项）**：① `app/src/androidTestDeveloper` 的 `AutomationEvaluationActivity` `exported="true"` 且无 permission（对照 debug 的 DiagnosticFailureActivity 用了 DUMP）——developer 测试 APK 内、无状态 fixture，风险低，建议加 DUMP 或 exported=false；② `ProviderEditTemplate.kt`（从 ProviderScreen 抽出的纯函数，含非显然 credentialRequired 覆盖规则）无对应 JVM 测试——抽取目的即可测性，建议补 3-4 条用例；③ `BrowserController.kt:511` 内联全限定 `ERROR_UNKNOWN`；④ i18n 上轮点名的 4 处英文字面量（ProviderScreen "API Key"、ChatScreen `mode.name`、EgressRuleSection "Provider"/"MCP"、ConnectorSection "Skill:"）**一个没修**，且 check-i18n 的 CJK 扫描器对英文字面量天然失明。

**新改动正面确认**：DataSyncForegroundService 冷启动修复设计正确 fail-closed（onCreate 立即 startAsForeground + startId 感知 stopSelfResult）；`HelixApplication` 的 `Process.isIsolated()` 双守卫是正确修复（隔离 UID 不再实例化时读宿主 prefs/Room）；`LanScopeStore` fail-closed 纪律正确；`AllFilesRootsStore` Path.of→Paths.get 兼容硬化；flavor seam 改进（consumer/developer 共同实现新接口 `SubscriptionProviderIntegration`，seam 显式化）；i18n 843 键三套 parity 全绿、developer flavor 新键一致、新增键翻译质量正常。

---

## 4. 文档与治理

基线：`check-docs.sh`/`verify-adr.sh` 全过（232 md / 28 ADR / 130 HXA）。

**HIGH**

- **D-H1 status.md（唯一当前状态源）未反映实际在做的 HXA-102 生产接线**：工作区存在大规模未提交 Goal 硬化（GoalDurableUsageLedger/ReminderScheduler/Worker/TurnCoordinator/ChatService 接线、Room v8/v9、17 份 bug-fixes、ADR-0028、`main-optimization-todo.md`（L7："所有者明确要求设置持续 Goal……当前检查点为 HXA-102 生产接线"）、`m10-closure-followup.md` 787 行、HXA-102.md +551 行），但 status.md In progress 只列 M13/HXA-125、Next task 只提 M11A 目录评估 + M9 094/095。AGENTS.md 任务纪律"In progress 非空则继续该 HXA"——**新会话/小模型会去继续 HXA-125 而完全无法从状态源发现 HXA-102 收口正在进行**；持续 Goal 进度事实只存在于 docs/README 未收录的 main-optimization-todo.md，双事实源。**建议**：In progress 增加 HXA-102 条目并链 main-optimization-todo.md 待办节；docs/README 登记该文档。~5 行改动，消除治理盲区。
- **D-H2 AGENTS.md 仍把 ADR-0008 标为 proposed**（上轮 HIGH 未修）：本波只修了 ADR-0009 行；ADR-0008 文件头 `Status: accepted`（Deciders 2026-09-05）。AGENTS.md 的 "Proposed ADRs must not be treated as accepted" 规则会直接误导 Git Workspace 后续工作。
- **D-H3 ADR-0011 仍 proposed/Deciders pending 但契约已在生产执行**（上轮 HIGH 未修）：本轮全部新进展文档与 status.md 均无所有者决定记录。新增 **ADR-0028（proposed）是 HXA-102 收口硬前置**（Goal 完成证据契约）——两个 ADR 决定都在等所有者一句话，分别阻塞"契约决策状态"与"Goal 完成证据"两条线。

**MEDIUM（6 项）**

- **D-M1 完成记录不可变约定被侵蚀**：HXA-102.md 追加 551 行（全 append-only 合规、原快照保留；首节"集成复核边界"是正当结论更正——做得好）。但其后 ~500 行是持续 Goal 完整工作日志（~30 节），其中大量与 Goal 无关（CLI PFD/Binder、Codex smoke stream、Detekt 53→0 等 M11 收口工作）——与 completion-records/README 明文规则"**不把大段后续修复史追加到旧交付快照**"直接冲突；同一事实三处维护（HXA-102.md / m10-closure-followup / main-optimization-todo），收口时还需再写第四版。**建议**：HXA-102.md 保留更正节 + 收口一次性终版追加；进行中进度单一来源归进展文档。附带：HXA-068 与 HXA-100 的 LAN 补充是同一事实两份拷贝，建议其一改链接。
- **D-M2 roadmap §2 里程碑退出表缺 M11A 行**（13 个已完成 HXA + 9 个 ADR 的最新里程碑无退出条件）；M13 行仍是独立单行表（上轮 L5 未修）。
- **D-M3 安全文档 M11 订阅威胁面只覆盖边界层**：本轮 +7 行已覆盖信任表"订阅实验 Runtime"行、"订阅 token 泄露"行、§9 lock 对账；**未覆盖**（M12 发布门禁依据本文档，HXA-121 验收时暴露）：① 第三方 adapter 供应链（dsh-plugin-subscriptions MIT 审计/0.7.0 重基线）；② Device Flow identity 复用（ADR-0026）；③ 个人订阅 ToS/风控边界；④ vault 篡改/Keystore/refresh 401。建议 §3 补 3~4 行威胁/控制 + §9 补边界脚本引用。
- **D-M4 status.md 检查点行数字不一致**：检查点行写 M11A "HXA-114～119、137～143、131～136"（止于 143），同页头行写 "131～146 已合入 main"，Completed 段列出 144/145/146，且检查点行正文本身描述了 144~146 的验收事实。
- **D-M5 进展文档治理基本未落地（上轮延续）**：`hxa-146-progress.md` **0 入链**（完全孤儿，而 HXA-144 记录链了 hxa-144-progress——同批次惯例不一致）；`verification-gaps-progress.md`、`m9-rooted-emulator-experiment.md` 实质孤儿（内容冻结 09-05，无"已被取代"标注）；docs/README 本轮只登记 improvement-review 一份，**15+ 份进展/交接文档未登记**（m10-closure-followup、m11-handoff、m11-main-numbering、main-merged-verification、main-optimization-todo、hxa-125/144/146-progress、connector-handoff、m7/m9-non-device、verification-gaps、webview-native-reference-investigation）。**建议**：docs/README 建"进展/交接文档"登记小节（性质+有效性）；hxa-146-progress 入链；verification-gaps 标注取代或归档。
- **D-M6 ADR-0024 superseded 后状态引用未同步**：roadmap L650 "依 **accepted** ADR-0024"（现已被 0025 取代）；HXA-142 记录 L12 "（accepted）"；HXA-088 记录 L11 仍 "ADR-0008 保持 proposed（待所有者决定）"无指引；ADR-0008 文件 L59 小节标题 "Status 仍为 proposed" 与文件头自相矛盾。（status.md L76 处理正确，可作范本。）

**LOW（9 项）**：① implementation-guide.md 只改了 ADR-0009 行，ADR-0008 旧措辞（L200）仍在；② security §7.5 "在 accepted 前"病句未改（上轮遗留）；③ **ADR 状态引用无跨文档机械检查**（verify-adr.sh 只查 ADR 文件自身；本轮人工又发现 4 处过期引用，证明纯人盯不收敛）；④ 编号治理仍缺失（HXA-089 幽灵前置无说明、无编号分配小节、ADR 跨分支占号无登记规则——而 m11-main-numbering.md 证明占号冲突已实际发生且被正确处理，规则有现实价值）；⑤ m10-closure-followup.md 头部日期未随 09-07 更新，"emulator-5590 正在长稳期间不得安装/强停"是**过期操作性指令**（长稳已被所有者延后），可能被并行会话误当当前约束；⑥ status SAF 表述（L130 vs L158）建议明写"文件管理器浏览后端已接线（HXA-057），模型工具 scope resolver 未接线"防误读；⑦ hxa-146-progress 定位清楚（自述"不是当前状态源"），问题仅在无入链；⑧ **Connector 交接材料（150 文件含 QwenWork 原包）仍在 `app/build/outputs/connector-handoff-672dc5a/`，`gradlew clean` 即丢失**（上轮遗留，仍未入 Git/备份）；⑨ 正向确认：roadmap HXA-137 显式记录占号理由、HXA-141 尾部"不能再按此句启动实现"注记——活文档内自引用失效标注是好做法，建议推广。

**上轮文档问题核对表（22 条）**：fixed ×3（#10 验证报告占位行重写、#19 ADR-0009 收口、#21 SGLang smoke 闭合）；partially ×3（#6 基线散落、#11 检查点行——新增漏 144~146、#12 SAF 矛盾实质化解）；not-fixed ×16（#1 病句、#2 旧 doc 编号、#3/#4/#13 孤儿文档、#5 M13 单行表+新增 M11A 缺行、#7 HXA-088 指引、#8 AGENTS 0008、#9 ADR-0011、#14 进展文档游离增至 15+、#15 HXA-089+编号治理、#16 ADR-0008 无实现 HXA+L59 标题、#17 status 大表重复维护（单元格更长 >9000 字符）、#18 implementation-guide 半修、#20 Connector 材料、#22 ADR-0011 决定）。

**正面确认（文档）**：编号消歧质量高且执行彻底——211fbb5 用 Git rename 完成 ADR-0023→0026、HXA-124~130→137~143；roadmap/status/matrix/records 全量同步，旧编号仅残留于历史语境；HXA-110~146 记录↔roadmap↔matrix 一一对应（机械强制+手工复核）；`m11-main-numbering.md`（重编号表+保留规则+验证记录）是好的先例；ADR 0020~0028 格式全部合规；新完成记录（HXA-141~146）真实失败如实记录（Copilot `model=auto` 400 → 固定 claude-haiku-4.5）、旧 Runtime 兼容用真实旧 APK 回归、真实账号测试只输出状态码分类不记录 token/正文。

---

## 5. 当前未决事项清单（2026-09-07 快照）

对照上轮 §5 清单的增量版；实施时以 status.md 为准。

**A. 进行中 / 持续 Goal**
1. **HXA-102 生产接线（持续 Goal 当前检查点，status.md 未列——见 D-H1）**：各后端执行中断/取消/对账矩阵、完成证据入口（CriterionSatisfied/CompleteRequested 尚无生产调用者，**依赖 ADR-0028 决定**）、真实模型完整 UI 流程；45 项固定评测当前 APK 统一复验（现 36/45 + Goal 3 项当前 APK）；API 29/36 设备回归；M11 Provider 集成回归；非长稳生命周期/资源回归；汇总收口。清单在 main-optimization-todo.md 第一阶段（~30 项 [x]、8 项 [ ]）。
2. **ADR-0028 所有者决定**（Goal 验收条件验证绑定与人工证据复核）——HXA-102 终局收口硬前置。
3. M13/HXA-125（status In progress）：独立测试账号/bearer 握手与撤销重连、WorkBuddy 真实导出样本、Codex/Claude 完整插件样本、QwenWork CLI 与账号业务（requires.bins=dws 未运行）。
4. M9/HXA-094~095 rooted 物理设备验收（无合格设备时保持未完成）。

**B. M11A 边界**
5. Claude/Grok **真实付费调用未核实**（所有者决定保持）；Copilot 仅当前账号 + Claude Haiku 4.5 验证，不推导所有免费账号/模型可用；consumer/store 硬门禁保持；动态模型目录/更多模型协议 = Next task（未立 HXA）。
6. ADR-0020（CLI 执行底座）、ADR-0022（Copilot SDK Android 底座）保持 proposed（负向结论已收口，重开条件在 ADR 内）；ADR-0020 建议补 superseded-by 状态注记。

**C. 里程碑门禁（状态基本未变）**
7. M10 release evidence：24h 长稳（**当前被所有者延后**；此前两轮 FAIL，浏览器 JNI 弱引用/Binder 累积问题待定位）、真实 Doze/secure keyguard/OEM 热限/物理低内存、物理设备（含 16 KiB）、多 Provider/模型逐 case 完整评测。
8. HXA-086 遗留：真机 4/16 KiB smoke、Doze/锁屏/热限、最低设备集真机证据。
9. HXA-105 遗留：30 分钟真机收益/资源对照；ADR-0009 生产启用门禁（接受 ≠ 启用）。
10. x86_64 仅 AAR/ELF 静态证据，无设备运行证据。
11. M12/HXA-120~123 未开始（渠道矩阵、发布门禁、签名/稳定 applicationId、商店提交）；发布状态仅开发/测试产物。
12. M13/HXA-126~130 planned。

**D. 未实现功能（未变）**：SAF 写后端；ADR-0012 Trusted Workspace/精确批量批准；持久 Git Workspace（ADR-0008 accepted，实现 HXA 未立项）；生产 child Agent/Workflow（ADR-0009 accepted，启用门禁未过）；文件层 age-based reclaim。

**E. 外部/环境依赖**
13. **Connector 交接材料仍在 build/ 忽略目录，`gradlew clean` 即丢失**（两轮遗留）。
14. ADR-0011 所有者决定（生产已在执行其契约）。
15. 工作区 283 路径未提交（含 8.json/9.json schema 导出、18 个新设备测试、42 个新文档）——**提交完整性风险**：漏提 schema JSON 会使 CI 迁移校验直接挂（B-L5）。
16. CI 新 lint 组合命令（9 个任务）未验证当前工作区能否全绿（bug-fix 文档自述 Detekt 53 项存量）。

**F. 文档治理**：ADR 状态机械检查（D-M6/L3）、编号治理小节（L4）、进展文档登记/归档（D-M5）、status 大表瘦身（#17）、安全文档 M11 威胁面补行（D-M3）。

---

## 6. 优先级路线图（更新版）

### P0（正确性/数据风险，新代码内——HXA-102 收口前必须处理）

1. **G-H1 maxRetries 死控 + 卡死地雷**：收窄方案——移除/禁用 retry 死控件、瞬时失败（网络/INTERNAL）映射 PAUSED 可恢复而非终态 FAILED、给 decision() retry 分支加测试封锁、补"瞬时失败不杀 Goal"设备测试。
2. **G-M1 分块结算越界**（<30 行 + 跨边界设备测试）+ **B-M1 GoalTurnBindingDao.bind 原子插入**（消灭跨 session TOCTOU）+ **B-M3 时长 `==` 边界统一**——三者同属"预算/结算边界"，一次收口。
3. **M-M2 vault 加密层设备测试**（GCM 篡改 fail-closed、原子写、logout 清理）——ADR-0021 验收闭环，只补测试不改代码。
4. **D-H1 status.md In progress 补 HXA-102 条目 + docs/README 登记 main-optimization-todo.md**（~5 行，防止新会话接错任务）。

### P1（遗留安全缺口，上轮 P0 仍未消费）

1. **S1 Provider 重定向**（~3 行 + wire 测试；wire 层已异步化，改动点现成）——本审查唯一可直接造成会话数据外泄的缺口，两轮未修。
2. **S2 PRoot symlink 加固**（先真机 POC 验证 `-S` 对 /proc 工具的影响；buildOutputArchive/JobZipWriter 拒绝 symlink；逃逸设备测试）。
3. **U4 stdoutBytes 上限**（<10 行 + 1 条设备测试）。
4. **S5 封闭错误码→资源键映射**（FileManagerService 6 处 + EgressRuleSection 4 处 + FilesScreen share 路径；顺带清 4 处英文字面量）。

### P2（用户可见缺陷 + 新代码 MEDIUM）

1. **U1+U2+U3 三连修**（合计 ~10 行 + 设备测试：SAF 移除崩溃、拦截丢文本、share 重放）。
2. **Goal G-M2/G-M4**：UI 竞态本地化（2 处 check(false) 改 ISE/失败文案）+ 删除事务去阻塞（cancelReminder 移出事务靠 reconciler 兜底）——合计半天内换掉 2 个崩溃/长锁路径。
3. **M-M1 loopback 绑 127.0.0.1 + 全局墙钟 deadline + readTarget 单测**；**M-M3 ProviderFactory managed-id fail-closed**。
4. **G-M3 PAUSED 原因去混淆**（"PROCESS_INTERRUPTED" 独立 outcome；ADR-0004 §2 可审计要求，成本低）。
5. **G-M5/M6**：READY Goal 会话归属 + 时间窗过期先 settle 再判死。

### P3（语义与并发遗留）

1. **C1 A2A CAS + markDirectCompleted 守卫**（树内已有范式：GoalRunDao WHERE 守卫；~6 行 + 1 测试）；**U6 流式哈希**（3 行消 1 GiB OOM）；**C2 exactly() 守卫**；**C3 共享 requireSha256**（7+ 处）。
2. **R-M1 awaitExecution 丢结果竞态**（检查 cancel(true) 返回值）；**R-M2 MCP deadline 稳定码**（CancellationException 单独映射）。
3. **B-M2 updateGoal state CAS**；**S3 读循环兑现 deadline + C8 线程池收敛**（长期线程/socket 耗尽）。
4. **U5 PENDING 可取消 + C7 submit 锁 + C9 PFD 总所有权 + C10 重活移出 binder 线程**（PRoot 事务面一次收口，顺带 L1 INTERNAL_ERROR 拒绝码、L6 版本检查）。
5. **S8 A2A settle 指数退避**；**S7 Long 溢出**；**S6 NetworkOriginScope 地址类钉住**。

### P4（测试缺口）

1. **两条 Goal 正常路径 E2E**：(a) 带工具调用的 Goal turn 完整流（reserve→执行→deadline 传入→finish 结算→预算计数）；(b) 模型流正常完成的 Goal turn（finish 按真实 usage 结算→PAUSED(RUN_FINISHED)）——当前 E2E 全是中断/取消/kill 路径，这是"Goal 模式真的能跑完一个任务"的直接证据，也是 ADR-0028 接线前置。
2. **B-M4** GoalTurnBinding/GoalUsageReservation 两个新仓库 Fake-DAO JVM 单测（settle-once、bind 守卫、sessionForGoal 边界）。
3. loopback 服务器单测（M-M1）；vault 篡改设备测试（P0 #3）；Goal M2 竞态错误文案测试；M11 M3 consumer fail-closed 单测。
4. **合入前本地跑 CI 新 lint 组合命令确认全绿**（16 项）。

### P5（治理、工程与中长期）

1. **ADR 决定催办**：ADR-0011（生产已在执行）+ ADR-0028（HXA-102 收口硬前置）——都是所有者一句话。
2. **ADR 状态引用一次性清账 + 机械检查**：AGENTS.md 0008、implementation-guide 0008、ADR-0008 L59 标题、roadmap 0024 superseded 注记、HXA-088 指引；check-docs.sh 增"文档中 ADR 状态词与文件头一致"检查（本类问题两轮共 6+ 处，人盯不收敛）。
3. **完成记录约定恢复**（D-M1）：HXA-102.md 留更正节、日志归单一来源、收口一次性终版追加。
4. **里程碑与进展文档治理**：roadmap §2 补 M11A 行、M13 并回；docs/README 建进展文档登记小节（15+ 份）；hxa-146-progress 入链、verification-gaps 标注取代或归档；编号治理小节（HXA-089 + 占号规则）；status 检查点行扩到 131~146。
5. **安全文档 M11 威胁面补行**（D-M3）+ **M11 仿冒头/固定身份残余风险挂账 status.md**（M-L3）。
6. **工程**：CI 加 check-i18n gate、spotless/detekt 排除 `.claude`/`.codegraph`、check-lockfiles 去硬编码 35、CI 拆并行 job + spike gate 接入、shellcheck、quickjs AAR 配置期解析移任务执行期、detekt 升级评估。
7. **ChatService 拆分启动**（已 3244 行）：⑧ 错误码映射（立即可 JVM 测）→ ⑦ 屏幕状态构建 → ⑥ 工具调用结算，配合存储 seam 接口化。
8. **上轮 P4/P5 未动项**：SSE 读取器收敛、边界常量收敛 tools/framework、spikes 归档（a2a 两个 + bounded-orchestration 生命周期注释）、ConversationRepositories 补 Fake-DAO 单测、JsAbiAssembly 行续符守卫 + KDoc 修正（§2.4 恶化项，建议提前至 P2）。

---

## 7. 附录：做得好、不应改动的部分（本轮新增确认）

- **M11 凭据隔离**：vault 独立 UID + Keystore-GCM + 0600 + 原子 rename + schema 严格校验；wire 只有 jobId/hash/枚举事件；逐 onTransact 签名校验；错误面封闭分类；runtime 零日志；边界脚本 DEX 级机械断言；完成记录如实记录失败与修复。
- **Goal reservation 基石**：PENDING→SETTLED 条件 UPDATE 恰好一次；recoverRun 绝不重放；5s 未记账窗口有界化（ADR-0004 §5）；结算与 turn 终态同事务幂等；提醒持久事实驱动（重复/丢失/乱序三态真实 WorkManager 测试）；删除级联完整 + kill 两相测试；host-fixture SIGKILL 恢复断言精确到字段级。
- **core 新范式**：v7→v9 迁移保守（不臆造数据、全链路测试、schema parity）；GoalRunDao 单调性 CAS 守卫（全仓缺失的 C1 类守卫先例，应推广到 A2A/goal 主行）。
- **CLI IPC 新实现**：CliRequestPipe（修 fire-and-forget 竞态、EOF 语义正确、fail-closed）；CliModelJobAwaiter（中断先 cancel 再超时、恰好取消一次）；Wire 无跨边界异常、hash 校验保留。
- **取消/中断纪律**：OkHttpWireClient 异步化后取消真实关流；ToolDispatcher 中断路径 + 等待期取消响应；QuickJS INTERRUPTED→Cancelled（防取消后重执行）；MCP 取消关流不重放——四个执行面本轮一致改善。
- **测试文化**：本轮测试改动零弱化；新设备测试全部钉真实行为（真实 WorkManager、真实 SIGKILL、真实旧 Runtime APK、真实账号状态码分类）；flake 治理用观测替代 sleep。
- **持续有效（上轮确认，本轮未变）**：SSRF 三层防线、原子写 fsync 顺序、reducer 纯函数 + verify()、fail-closed 稳定错误文案、flavor 机械门禁、依赖卫生（全 pinned + lockfile + 无 mutable 版本）、ADR/完成记录格式纪律。
