# Harness 2.0 收尾与小模型交接

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
- ADR-0048 的候选方向可继续验证，但不先改 `accepted` 或伪造 HXA 完成记录。已明确其部分扩展 ADR-0003、内部元数据是持久副作用、普通 Plan 可文本结束、计划审阅不是工具审批等边界；该 ADR 目前仍为 proposed，归入 HXA-192。源代码已存在不替代接受证据。
- JGit 的 `TrustAllX509TrustManager` 检测不是虚构：当前锁定 jar 的 `NoCheckX509TrustManager` 两个校验方法确实直接 return；`TransportHttp` 存在 `sslVerify=false` 路径。Helix 当前只调用本地 status/diff，尚未证明所有配置/制品下不可达，因此本轮不放行扫描。R1 给出局部处理要求。

### 2.4 国际化门禁假通过

最终检查发现 `check-i18n.sh` 以绝对路径排除 `/build/`、`/.claude/`，导致位于 `.claude/worktrees/` 下的当前仓库所有资源被跳过，曾报告“0 resource keys in parity”。这不是翻译通过的证据。

已在两个工作区改成按仓库相对路径排除，并扩展 `test-review-gates.py`：当前 checkout 位于 `build/.claude/worktrees/fixture` 时，缺翻译必须失败；补齐后应实际检查 1 个 key；仓库内部的 build 和嵌套 worktree 仍被排除。修复后准确检出五个 `artifacts_*` key 缺 en/zh，本轮已补齐。Harness 现在实际检查 1193 个资源 key，main 检查 1075 个。不要恢复旧绝对路径过滤，也不要把所有 missing translation 加入忽略列表。

## 3. 小模型执行包：按 R1 → R2 → R3 → R4

一次只推进一个包。每包先记录基线，再改允许的路径，按真实日志回填证据。R1～R4 是 HXA-192 的子步骤，不是新 HXA，也不关闭其他在途 HXA-190/191。

### R1：清现有主机门禁

允许：下表对应实现、直接调用处、相应测试/资源和必要的局部 lint 配置。禁止无关职责搬迁、依赖升级与整仓 suppression。

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
4. 将证据补入 ADR-0048 的 Verification，保持 required-before-acceptance 与已执行结果分开。没有收到可追溯的接受结论前，保留 proposed；R1/R3 的独立验证不需要为此停下。

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

1. ADR-0048 获授权接受后记录 Deciders、理由、证据，并同步其部分扩展的规范章节；否则显式列为整合阻断，不假设研究文档已授权模式改变。
2. 执行原 `./scripts/check-all.sh --all`；按 fail-fast 行为记录哪一步实际执行。不可用旧的局部绿灯覆盖当前完整门禁。
3. HXA-192 完成记录、status、roadmap、matrix 同步；记录源码、主机、设备、架构接受和发布验证五类证据，各自不足单列。执行完成记录索引脚本和 docs/ADR/i18n/secrets/diff 门禁。
4. 合入 main 前重新比较双方 HEAD 与 dirty paths，只整合有明确归属的改动；本轮已有同内容文档修改，避免重复覆盖。提交/推送/合并按后续明确授权执行，分别报告事实。未合入、未推送或未验收不能写成完成。

## 4. 本轮实际证据

全部源码测试发生在 Harness 工作区。日志位于本机忽略目录 `build/harness-review-2026-09-14/`，不提交其中机器路径或其他原始环境数据。归档脚本在 main 的 `scripts/debug/2026-09-14/`；SQL 和测试汇总脚本在 Harness 的同日期目录。

| 检查 | 本轮结果 |
| --- | --- |
| `:runtime:cli-client:testDebugUnitTest` | 36 tests，0 failure/error/skip |
| `:runtime:cli-app:testDebugUnitTest` | 126 tests，0 failure/error/skip |
| `:core:storage:testDebugUnitTest` | 89 tests，0 failure/error/skip |
| `:core:storage:compileDebugAndroidTestKotlin` | 通过；未运行设备 |
| `python3 scripts/debug/2026-09-14/check-artifact-migration.py` | 旧 SQL/朴素全量 UPDATE 负对照成立；生产迁移的四条路径、元数据、唯一约束及附件 FK 通过 |
| `spotlessCheck` | 通过 |
| `detekt` | 未通过，27 项；本轮修改的 Kotlin 文件无报告项 |
| lint | 已分析原 17 错报告；五项缺翻译已修，完整 lint 未重跑，剩余数量由 R1 实测，不能直接减法宣称已绿 |
| `python3 scripts/test-review-gates.py` | 5 项通过，含嵌套 checkout 国际化漏扫回归 |
| `check-i18n.sh` | main 1075、Harness 1193 个资源 key 实际校验通过 |
| `check-all.sh --source`、`git diff --check` | 两个工作区均通过；main 394 / Harness 395 篇文档，47 / 48 条 ADR，5 项脚本回归及真实资源 key 校验通过 |
| `:app:processConsumerDebugResources :app:processDeveloperDebugResources` | 补齐翻译后双 flavor 资源处理通过 |
| Android Room / Plan 设备集成、完整 `check-all --all` | 未执行，留给 R2～R4；不声明已可合并/发布 |

## 5. 可直接交给小模型的启动说明

在 `worktree-harness-2.0` 继续 HXA-192。先读 README、status、roadmap 中 HXA-192 和本文，核对工作树并保留当前未提交补丁；不要重做已修复的 CLI 测试与 v16 迁移。先执行 R1 的 API 兼容和 lint 子项，再按新 SARIF 局部处理 Detekt。每次只做一个有明确验收的小切片；通过后推进 R2、R3，最后 R4。不得先把 ADR-0048 改 accepted、跳过测试或放宽全局门禁。遇到具体架构接受/外部分发历史缺口，记录精确阻断及已准备好的证据，继续不依赖它的工作；不要重新索要已经授权的常规修复权限。
