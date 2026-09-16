# Harness 2.0 收尾与小模型交接

> 2026-09-16 接手更新：所有者已授权本任务接手并提交本工作树剩余WIP，不再等待原并行所有方。完整本地主机/构建/lint/制品门禁已通过；原27 detekt、12 lint、2项JGit阻断均为历史结果。HXA-200/201已完成；其他任务按各自剩余验收判断。依赖允许为兼容性与维护升级，须同步版本锁、验证材料和设备证据。当前证据与提交范围见[WIP接手记录](wip-takeover-2026-09-16.md)，下文旧基线/未提交/失败数字只保留追溯用途。

新接手先读[统一实施导航与交接 Prompt](harness-implementation-handoff.md)。本文保留 HXA-192 收尾规格及历史证据，不承担所有后续任务的调度；本地提交授权同样覆盖已批准的 HXA-207 独立切片。

> 2026-09-14 补充：Harness 工作树已实施 [HXA-193 单 APK Runtime](integrated-developer-runtimes.md)，当前组件形态以 [ADR-0049](../adr/0049-integrated-developer-runtimes.md) 为准。继续本包时使用新的 `scripts/verify-integrated-runtimes.py` 设备入口和双主 APK 产物门。门禁现状（R1+R4 实测）：`spotlessCheck` 与 `detekt` 已通过——原 27 项 detekt 债按行为保持重构清零，本轮再把迁移夹具拆分引入的 1 项 LongMethod（提取 `approvalColumns`/`assertLaterMigrationStepsLanded`）与 Plan 设备测试的一处格式清零；App lint 仅剩 2 项第三方 JGit `TrustAllX509TrustManager`（既定阻断项，未擅自豁免或降 TLS）。完整 `check-all.sh --all` 按 fail-fast 记录：`source_checks` 通过，`build_checks` 通过 spotless/detekt/test 后在 `lint` 因上述 2 项 JGit 停止，`assembles`/`check-lockfiles`/`artifact_checks` 未达。CI 资产准备已接线，本地归档输入和 360 ELF gate 通过；Docker 重建因旧 xz-libs 版本不在镜像索引而失败。合并前发布锁定 RootFS 并配置 `HELIX_ROOTFS_ARCHIVE_URL`，或恢复原包快照；不得擅自更新 lock。本轮未运行远端 CI。

更新时间：2026-09-14。本文是 HXA-192 的专项执行计划与本轮证据快照；实时状态仍以 [status](status.md) 为准，不能据本文把整个重构或发布记为完成。

## 1. 工作区与材料职责

- 研究正文和图集已归入 [docs/research](../research/helix-agent-complete-research-and-product-plan.md)，图集只表达关系，不维护第二套任务清单。两份材料引用的是 2026-09-13 main 快照；研究中的“候选”不能覆盖现行 ADR。
- 本轮开始：main 为 `27b643e895591464d88ea71d48528635768bfd60`，仅两份研究稿有未提交修订；`worktree-harness-2.0` 为 `a4a64039b4273620e6472bcb35db754fbe312a91`，工作树干净。产品源码修复只在 Harness 工作区；main 做文档归档、索引、交接，并同步本轮确认的国际化门禁修复与回归测试。
- 两个工作区现有本轮未提交修改，未执行 commit、push 或 merge。接手先刷新 `git status --short`、`git rev-parse HEAD`、`git log -5 --oneline`，不要 stash/reset/覆盖当前补丁。分支名和 hash 只是快照，不是远端同步保证。
- HXA-192 仅收尾已有重构，见 [路线](roadmap.md)和[验收矩阵](verification-matrix.md)。不另起全套 Harness，不恢复强制 verifier，不引入 Goal 自动续跑、Schedule、Hooks、Code Mode 或子 Agent。

## 2. 已替小模型解决的技术问题

### 2.1 Artifact v16 迁移

**判断依据是数据库版本，不是路径文本。** v15 存的是 app scope 下的裸相对路径；`scope:notes.txt`、`scope:other:output/x.txt` 也是合法旧文件名，不能误识别成新引用。旧 `WHERE relativePath NOT LIKE 'scope:%'` 会遗漏或重定向这些记录。

还有唯一索引的过渡冲突：旧库可同时保存 `output/legacy.txt` 和 `scope:app:output/legacy.txt`。即使改成全量加前缀，SQLite 逐行更新时仍可能撞上另一个尚未转换的旧 key，直接阻断升级。

已修改 Harness 的 `HelixMigrations.MIGRATION_15_16`：在 Room 的迁移事务内删除 `index_artifacts_sessionId_relativePath`、转换全部旧行、重建同名唯一索引。最终 schema 不变，id、hash、turnId 与附件引用不变。不要在应用每次启动时再“补前缀”，也不要为绕开索引冲突清空旧产物。

已补四类旧路径共存的 Android migration fixture，并将误称“当前 v15”的 schema 对照用例改为实际 v16。Host SQLite 负对照复现旧写法和朴素全量 UPDATE 的冲突；生产三条 SQL 的回归验证路径、唯一约束、元数据及附件 FK。**Host SQLite 和 androidTest 编译不能代替 Android Room 实测。**

这里不支持“同一个 v15 库混存实验性完整引用”的启发式兼容。若发现确有这种发行历史，先收集无用户内容的版本/写入路径证据，再单独定义迁移；不能猜测。已打开的错误 v16 库也不会重跑 15→16；发布前须核实 v16 是否曾分发，若已分发则另拟有版本标记的修复，禁止重装或降版本掩盖。

### 2.2 CLI 六个失败与 OOM

六个失败来自旧测试契约，不能只写“flaky”。`CliModelJobAwaiter` 的生产 `timeoutMs <= 0` 表示无隐式截止；旧测试用 0 期待立即超时，Fake 永远返回 RUNNING 并向列表追加，最终 OOM。

已修订四个测试文件，保留正向/失败/取消边界：

- 正数 deadline 覆盖边界轮询和恰好一次取消，并给 Fake 加有限调用保护；0 覆盖长时间后终态对账，以及线程中断取消。
- preview 超过原 2048 事件仍完整 round-trip；终态事件和错误 wire format 仍拒绝。
- 订阅流跨越原 2 MiB / 2048 事件边界时不截断，保持顺序并调用 finish；既有传输错误分类和终态不等待 EOF 用例保留。
- 原 64 MiB payload quota 测试改为稀疏大文件夹具，证明既有 payload 不构成已移除的正文配额；空/负请求仍拒绝。journal metadata 的条数/大小上限保持原契约。

依据是当前 main 已合入的 `3d6a2b57` 生产变化及[订阅读取契约](../bug-fixes/2026-09-10-act-history-recovery.md)，不是为了绿灯改变实现。没有恢复隐藏限流、增加堆内存或删除测试。流式内存占用仍应在后续专项量测，不能据此宣称资源无限。

### 2.3 门禁归因与决策顺序

- “不是 Priority 2 新增”不等于“与 Harness 无关”。`GitWorkspaceReader`、`ProjectInstructionsReader`、Git UI 和产物文案就在 Harness 交付范围内。
- 不在“重构整个 main / 放宽门禁 / 暂缓全部工作”之间选。采用局部 API 兼容修复、具体职责提取、定点回归，再执行原门禁；禁止全局 baseline、排除模块或跳过测试。
- ADR-0048 已于 2026-09-14 接受有界架构契约，部分扩展 ADR-0003；普通 Plan 可文本结束，内部元数据是真实持久副作用，计划审阅不代替工具审批。原集成要求仍是启用/完成门禁，不伪造完成记录。
- JGit 的 `TrustAllX509TrustManager` 检测不是虚构：当前锁定 jar 的 `NoCheckX509TrustManager` 两个校验方法确实直接 return；`TransportHttp` 存在 `sslVerify=false` 路径。Helix 当前只调用本地 status/diff，尚未证明所有配置/制品下不可达，因此本轮不放行扫描。R1 给出局部处理要求。

### 2.4 国际化门禁假通过

最终检查发现 `check-i18n.sh` 以绝对路径排除 `/build/`、`/.claude/`，导致位于 `.claude/worktrees/` 下的当前仓库所有资源被跳过，曾报告“0 resource keys in parity”。这不是翻译通过的证据。

已在两个工作区改成按仓库相对路径排除，并扩展 `test-review-gates.py`：当前 checkout 位于 `build/.claude/worktrees/fixture` 时，缺翻译必须失败；补齐后应实际检查 1 个 key；仓库内部的 build 和嵌套 worktree 仍被排除。修复后准确检出五个 `artifacts_*` key 缺 en/zh，本轮已补齐。Harness 现在实际检查 1193 个资源 key，main 检查 1075 个。不要恢复旧绝对路径过滤，也不要把所有 missing translation 加入忽略列表。

## 3. 小模型执行包：按 R1 → R2 → R3 → R4

### Git 本地提交授权（2026-09-14 更新）

所有者后续明确要求扩充任务：[HXA-200～206 产品闭环包](product-completion-and-approval-plan.md) 同样适用以下本地提交授权与具名暂存纪律；不抢占当前收尾或夹带并行修改。

所有者已明确允许小模型提交 Git 代码。本授权覆盖 HXA-192/193 收尾和 HXA-194～199 已获实施授权的切片，替代原“不提交”要求；不接受 proposed ADR，也不授权 push、合并 main、发布或改写历史。

每个独立切片通过对应验证后，可自主本地 commit，无需重复确认。先记录 HEAD、`git status --short` 和已有暂存内容；使用具名路径或明确 hunk 暂存，禁止 `git add .`。既有补丁仅在归属明确、属于任务且已审查验证时纳入；无法分离的并行修改保留并报告，不 reset/stash 他人工作。

提交前检查 `git diff --cached --stat`、`git diff --cached`、`git diff --cached --check` 并执行切片门禁，不纳入 secrets、原始日志、机器路径或下载资产。全局已知失败不必阻止已验证的独立修复提交，但须记录失败，不宣称 HXA 完成。提交后报告 hash、范围、验证和剩余 dirty paths；本地提交不等于推送、合并或整体验收。

一次只推进一个包。每包先记录基线，再改允许的路径，按真实日志回填证据。R1～R4 是 HXA-192 的子步骤，不是新 HXA，也不关闭其他在途 HXA-190/191。

### Git 提交记录（R4，2026-09-14）

已按上述授权本地提交 HXA-192 的**可分离、已验证核心切片**（commit `0d52eae7`，5 文件，具名路径暂存、禁 `git add .`；提交前 `git diff --cached --stat` 恰 5 文件、`--check` 无空白错误，无 secrets/机器路径/原始日志）：
`app/src/main/kotlin/com/helix/app/plan/PlanTools.kt`（plan.submit v1→v2 文本结束契约）、`app/src/test/kotlin/com/helix/app/plan/PlanToolsTest.kt`（v2 精确期望）、`app/src/androidTest/kotlin/com/helix/app/plan/PlanSubmitIntegrationDeviceTest.kt`（新增生产管线设备集成测试）、`core/storage/src/main/kotlin/com/helix/core/storage/HelixMigrations.kt`（MIGRATION_15_16：删索引→全量加前缀→重建索引）、`core/storage/src/androidTest/kotlin/com/helix/core/storage/RoomMigrationFixtureTest.kt`（4 路径 v15→16 夹具 + 1→16 链补 MIGRATION_15_16 + `approvalColumns`/`assertLaterMigrationStepsLanded` 两个 helper）。

**未纳入本轮提交（保留在工作树，不 reset/stash）：** R1 的 detekt 27 项清零、i18n 门禁修复、CLI 旧契约测试修复（散在 `app/src/main/**`、`tools/**`、`runtime/**`，与 HXA-193 runtime 改动部分同文件，须逐 hunk 核验归属后单独提交）；ADR-0048/0003、研究归档、本交接与 status/roadmap/matrix（文档更新，随多任务 WIP 待提交；ADR-0048 正文链接依赖研究归档，不宜与代码切片分离）；`scripts/debug/2026-09-14/`（含 `ANDROID_HOME=/Users/dollars/...` 机器路径，§3 不纳入）；HXA-193 runtime 与 HXA-194~199 文档/CI/lockfile/README（非本 HXA 范围）。

**这不是整体验收：** 完整 `check-all.sh --all` 未全绿（JGit 2 项 `TrustAllX509TrustManager` 阻断项属独立决策，§3.1 不擅自 suppress；27 detekt 债在未提交的 R1 文件里），`build_checks` 在 `lint` 后 fail-fast，`assembles`/`check-lockfiles`/`artifact_checks` 未达。HXA-192 未关闭（ADR-0048 启用门禁：UI 级用户闭环、审阅不 mint 审批的设备证明仍缺）。本地 commit ≠ 推送/合并/发布/验收。

### R1：清现有主机门禁

允许：下表对应实现、直接调用处、相应测试/资源和必要的局部 lint 配置。禁止无关职责搬迁与整仓 suppression；依赖可按2026-09-16授权升级并补齐兼容性验证。

| 子项 | 已明确的实现方向 | 验收 |
| --- | --- | --- |
| API 29 兼容 | `GitWorkspaceReader` 用 `out.toByteArray().toString(Charsets.UTF_8)`；`ProjectInstructionsReader` 用有界读取循环，保持 64,000 bytes 限制、关闭流和 scope resolver；不能换成无界 `readBytes()` | Git/项目指令 JVM；API29 设备实际读取留到 R3；双 flavor lint |
| 通知权限 | `POST_NOTIFICATIONS` 只在 API33+ 分支引用与请求；保持用户动作触发，不给 API29～32 假造拒绝 | 现有权限分类回归与 lint |
| Compose/资源 | 五个 `artifacts_*` key 已补齐，不重复实现。重命名三个 Unit Composable 并改引用；`%d connected` 用真正复数资源并同步调用；删除确认无引用的资源、不可达低版本分支，应用已存在的 toUri 扩展 | `check-i18n.sh`、受影响 UI 编译、六项 lint |
| JGit 第三方检测 | 先用含 `http.sslVerify=false`、远程地址和代表性 Git 配置的合成仓库验证 status/diff 不发网络请求、不改仓库；审查传输/过滤器入口及 Debug/Release 制品。仅当能说明不可达边界时，提交精确到当前依赖坐标/检测项的局部豁免理由及防回归；做不到则保留该项阻断并给出隔离替代方案 | 禁止全局忽略 TrustAll 或降低 TLS 验证；不凭“只读”两个字判安全，也不默认 R8 会删掉问题类 |
| Detekt 27 项 | 用新 SARIF 分组；字符串折行先做，随后按纯分类函数、UI 子区域、provider/lifecycle helper 局部提取。取消、事务和状态机留在原所有者中，不为指标把它们切散 | 每组相关 JVM；最终 `spotlessCheck detekt`。无新增只是中间指标，收尾要求完整门禁通过 |

Detekt 起点包括 `SensitiveFieldClassifier`、`WriteTool`、`FilesCopyTool/FilesMoveTool`、`FileToolArguments`、`AuditScreen`、`CodexSubscriptionProvider`、`DataSyncForegroundService`、`ToolModelResult`、`ProviderConnectionCheck`、`CodexSmokeStream` 和两个设备夹具；以重跑报告为准，不把旧 27 当成永恒数量。失去明确业务边界时停止这一拆分，先记录可比较的方案。

推荐命令（在 Harness 工作区，JDK17）：

```bash
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest
./gradlew spotlessCheck detekt
./gradlew lintDebug lintRelease lintConsumerDebug lintDeveloperDebug lintConsumerRelease lintDeveloperRelease
./scripts/check-all.sh --source
```

### R2：完成 Plan/METADATA 契约和集成证据

允许：`app/plan`、Plan prompt/曝光/审阅 UI、`core/model`/`core/agent`/`core/policy`/`tools/framework` 中直接相关路径及集成测试；不扩展通用文件/网络权限。

1. 检索并修正 `PlanTools` 的 `ONLY way to finish Plan mode` 和对应 prompt/UI：普通调研允许文本完成，用户需要可审阅的版本化计划时才提交结构化计划。若变更已绑定 descriptor 文本/契约，遵守既有 Tool version 规则并更新精确期望。
2. 用生产 Registry/过滤/Dispatcher 和真实 Room 串起 `plan.submit`；模型可以用合成 Provider，不能用 Fake PlanRepository 或手工插入 plan/audit 行冒充集成。断言归属、版本、READY 行及 tool call/audit 结果均一致。
3. 覆盖取消前/写入后的持久结果、同一调用不重复插入、外部来源不能声明 METADATA、伪造 session/Turn/Goal 参数被拒绝；审阅计划后文件写入仍通过正常 Policy/Approval。不要顺手改 Goal 完成和继续语义。
4. ADR-0048 架构已接受，无需重复请求接受；将真实集成证据补入 Verification，严格区分已执行与仍待启用门禁。R1/R3 独立工作继续。

命令：`./gradlew :core:agent:test :core:policy:test :tools:framework:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:compileConsumerDebugAndroidTestKotlin :app:compileDeveloperDebugAndroidTestKotlin`。真实 Room/Dispatcher 设备集成在 R3 运行，不能用编译通过替代。

### R3：独占设备验证迁移与用户闭环

允许：相关 Android 测试、日期调试脚本及确认缺陷的局部修复。先读 [环境](environment.md)、[验收矩阵](verification-matrix.md)和独占设备规则。脚本保存在 `scripts/debug/YYYY-MM-DD/`，只启动新模拟器、拒绝已有 serial，在 finally 关闭自己拥有的进程。不借用他人设备、不清空用户数据。

- API29 与 API36 各跑当前 `RoomMigrationFixtureTest`（含真实 15→16、旧 schema 全链和 v16 export/code）；进程重开后检查路径及索引；报每组真实数量、失败、skip 和环境。
- 产物 app/用户授权 scope 同名路径不能合并，覆盖后 id/附件仍稳定；合法 `scope:` 旧名迁移可打开；撤销 scope、文件缺失、hash 变化显示真实不可用原因。图片附件恢复、retry 和结果入口分别检查，不能只读数据库行。
- 跑 R2 的 Plan 集成；普通 Plan 文本结束、结构化审阅、取消和转执行分别验证。
- API29 实际执行 Git diff 与项目指令读取，证明 API 替换不只是 lint 通过。
- Room 命令：独占 runner 内执行 `./gradlew :core:storage:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.helix.core.storage.RoomMigrationFixtureTest`；环境只向此 runner 暴露其自建 serial。App 使用对应 `connectedConsumerDebugAndroidTest` / `connectedDeveloperDebugAndroidTest` 和新增测试的实际全限定类名，写入日志。

本包是定点设备回归，不等同浏览器/Autofill 24h 长稳、OEM 全矩阵或真实账号验收。

### R4：决策、最终集成与交付记录

前提：R1～R3 证据齐全；尚有未知副作用/迁移失败时不得关闭。

1. 核对 accepted ADR-0048 的有界契约与规范同步；完成其仍未通过的生产启用/集成门禁，未通过不得关闭 HXA-192。
2. 执行原 `./scripts/check-all.sh --all`；按 fail-fast 行为记录哪一步实际执行。不可用旧的局部绿灯覆盖当前完整门禁。
3. HXA-192 完成记录、status、roadmap、matrix 同步；记录源码、主机、设备、架构接受和发布验证五类证据，各自不足单列。执行完成记录索引脚本和 docs/ADR/i18n/secrets/diff 门禁。
4. 按上述授权分切片本地提交。推送/合并仍待单独授权；合入 main 前重新比较双方 HEAD 与 dirty paths，只整合归属明确的改动，避免覆盖同内容文档及并行工作。分别报告提交、推送、合并和验收，不将本地 commit 写成已集成。

## 4. 本轮实际证据

全部源码测试发生在 Harness 工作区。日志位于本机忽略目录 `build/harness-review-2026-09-14/`，不提交其中机器路径或其他原始环境数据。归档脚本在 main 的 `scripts/debug/2026-09-14/`；SQL 和测试汇总脚本在 Harness 的同日期目录。

| 检查 | 本轮结果 |
| --- | --- |
| `:runtime:cli-client:testDebugUnitTest` | 36 tests，0 failure/error/skip |
| `:runtime:cli-app:testDebugUnitTest` | 126 tests，0 failure/error/skip |
| `:core:storage:testDebugUnitTest` | 89 tests，0 failure/error/skip |
| `:core:storage:compileDebugAndroidTestKotlin` | 通过；未运行设备 |
| `python3 scripts/debug/2026-09-14/check-artifact-migration.py` | 旧 SQL/朴素全量 UPDATE 负对照成立；生产迁移的四条路径、元数据、唯一约束及附件 FK 通过 |
| `detekt` | 通过（0 项）：原 27 项按行为保持重构清零；迁移夹具拆分引入的 1 项 LongMethod 经提取 `approvalColumns`/`assertLaterMigrationStepsLanded` 两个 helper 清零 |
| `spotlessCheck` | 通过（Plan 设备测试一处 ktlint 折行经 `spotlessApply` 清零，无逻辑改动） |
| 六项 lint（Debug/Release × consumer/developer） | 已跑：非第三方 15 项清零（API 级读取、三处 Composable 命名、五缺翻译、复数/UnusedResources/UseKtx/ObsoleteSdkInt）；剩 2 项第三方 JGit `TrustAllX509TrustManager`（既定阻断项，未 suppress、未降 TLS） |
| `python3 scripts/test-review-gates.py` | 5 项通过，含嵌套 checkout 国际化漏扫回归 |
| `check-i18n.sh` | main 1075、Harness 1193 个资源 key 实际校验通过 |
| `check-all.sh --source`、`git diff --check` | 两个工作区均通过；main 394 / Harness 395 篇文档，47 / 48 条 ADR，5 项脚本回归及真实资源 key 校验通过 |
| `:app:processConsumerDebugResources :app:processDeveloperDebugResources` | 补齐翻译后双 flavor 资源处理通过 |
| Android Room / Plan 设备集成 | 已通过：独占 `Helix_API_36`+`Helix_API_29`（自建、已关闭），`RoomMigrationFixtureTest` 各 28/28、`PlanSubmitIntegrationDeviceTest` 各 4/4，0 failure/error/skip（日志 `build/r3-device-*/`、`build/r3-mig-rerun-*/`） |
| 完整 `check-all.sh --all` | `source_checks` 通过；`build_checks` 过 spotless/detekt/test 后在 `:app:lintConsumerDebug` 因 2 项 JGit `TrustAllX509TrustManager` fail-fast（GATE_EXIT=1）；`assembles`/`check-lockfiles`/`artifact_checks` 未达。核心切片已本地 commit `0d52eae7`；不声明已可合并/发布 |

## 5. 可直接交给小模型的启动说明

在 `worktree-harness-2.0` 继续 HXA-192。先读 README、status、roadmap 和本文，保留既有补丁，核查后不重复修复。按 R1→R2→R3→R4 推进；ADR-0048 已接受架构，但其真实集成和普通文本 Plan 修正仍须验证。不得跳过测试或放宽全局门禁。验证通过的独立切片按上述授权本地 commit；不推送合并。遇到ADR-0051 尚未满足的启用条件 或外部条件时只暂停依赖项，继续独立工作。
