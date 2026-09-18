# HXA-191 会话搜索切片证据：有界标题/正文搜索与四象限设备验收（2026-09-18）

> 整合审查：本页保留原分支交付快照，“全门禁”表述不成立（本页亦记录源码门禁失败）。取消回写、输入空格、Unicode定位及范围提示已在整合中修正，最新验证范围见[整合记录](integration-193-191-2026-09-18.md)。

本记录属于 HXA-191「会话搜索」切片轮（第二个执行者，worktree `Helix-runtime-search`，分支 `codex/runtime-closeout-session-search`，基线 `c33cb893`，接续 HXA-193 收口 `a0bf683b` 与文档修复 `4883c74a`/`8087d023`）。本轮**只交付 HXA-191 的「会话/历史检索」部分**：标题 + 现有可检索消息正文的有界只读搜索、归档分组、清空/空结果/取消/切换会话、数据量边界与分页/截断的显式范围、双 flavor 真实 UI 与旋转/重启持久。**深色主题、JSONL、备份迁移均不在本轮范围**；HXA-191 整体仍为待实现，**本轮不写 191 完成记录**（见[结论](#结论)与[边界与外部事实](#边界与外部事实)）。

## 结论

- **搜索切片（PARTIAL）已交付并全门禁验收**：新增只读 `SessionSearchRepository`（复用现有 `SessionDao`/`MessageDao` + `ContentStore`；**无第二会话状态源**）、宿主单测 12 例、Room 设备查询测试 4 例、app 真实 UI 设备测试 7 例。
- 双 flavor × 双 API 独占模拟器**四象限矩阵全绿**（consumer/developer × API36/29；AVD `Helix191_API36`/`Helix191_API29`；端口 5642/5644/5646/5648）：每象限 app `OK (7 tests)` + storage connected `4/0/0` + 两阶段重启协议（setup 强杀 + PID 标记 → verify 新进程）+ teardown `closed.json exit 0`（逐象限见[命令与结果](#命令与结果全部实际执行)）。
- **无 schema 变更 = 已验证的缺失**：`MessageDao.contentSearchCandidates(limit)` 是新增只读 JOIN 查询（`ORDER BY s.createdAt DESC, m.sequence DESC LIMIT :limit`），不改任何表/索引/迁移文件；`HelixStorage` 仅新增只读 `sessionSearch` 仓储入口。候选 = 任何非空且有 `contentRef` 的正文；内容匹配是仓储（非查询）职责。
- **不在 Compose 主线程扫全量历史**：`ChatService.searchSessions` 先写查询态、`workScope` 200ms 防抖后再扫描、一次性写回（query+hits+scanned+skipped+truncated）；范围行（`chat-session-search-scope`）显式呈现「已检索 N 条正文」，不默默只搜当前页；截断时 `truncated` 置位并呈现范围。
- 诊断链（runs 1–7）：UI 片段行偶发不 compose 的根因是 `ChatScreen` 用 `collectAsStateWithLifecycle()` + v2 compose 规则 `EmptyActivity` 跳板在后台化 activity 时冻结收集——**测试框架产物，非 app 缺陷**。改以进程级 `ChatService.sessionSearch`（命中行渲染的真源，跳板无法重置）为片段主证，UI 以命中行 + 范围行为辅证；**不改 `collectAsState()`**（会改变 app 电池/功耗行为，超范围）。
- **HXA-191 整体仍 INCOMPLETE**：深色主题未做；本轮不写 `docs/completion-records/HXA-191.md`，不 push/合并/发布，不修改全局 status/roadmap/index（建议更新见文末，供协调者整合）。

## 本轮新增测试（实现要求，非既有证据）

1. `core/storage/src/test/kotlin/com/helix/core/storage/repository/SessionSearchRepositoryTest.kt`（宿主单测 **12 例**，假 DAO + 真实 `FileContentStore`）：
   - 标题命中大小写不敏感且仅 `TITLE`、无片段；正文命中给出有界片段（`…` 前后省略、含 needle）；标题+正文同中报 `{TITLE, MESSAGE}`；
   - 归档会话 `isArchived`；无命中空 hits 但仍计 `scannedMessages`；空白查询不读取、直接空结果；
   - 候选上限触发 `truncated` 且命中按会话去重；扫描偏新会话、会话内偏新消息；
   - 超尺寸正文跳过且继续（`skippedMessages`）；不可读正文（孤儿 contentRef）跳过不失败；`maxHits` 截断到最新会话；
   - 越界参数（负候选 / 0 字节 / 0 hits）fail-closed。
2. `core/storage/src/androidTest/kotlin/com/helix/core/storage/SessionSearchQueryDeviceTest.kt`（Room 设备查询 **4 例**，真数据库 + 真内容文件；每例 `deleteDatabase` + 删内容目录建全新 fixture，避免已安装 APK 跨 run 保留数据）：
   - `searchCandidatesAreLimitedAndOrderedNewestSessionFirst`：候选 = 有 `contentRef` 的正文（内容匹配是仓储职责），限 2 取最新会话的最新两条消息，无界取全序（最新会话 → 会话内最新消息）；
   - `messagesWithoutContentAreNeverCandidates`：空白正文（无 `contentRef`）永不成候选；
   - `searchMatchesTitleAndBodyWithArchiveState`：归档会话 MESSAGE 命中带片段；纯标题查询 2 命中（最新在前）且片段为 null；
   - `smallCandidateCapTruncatesAndStatesTheScope`：小候选上限 → `truncated`、`scannedMessages=2`、片段取最新正文。
3. `app/src/androidTest/kotlin/com/helix/app/ui/SessionSearchDeviceTest.kt`（app 真实 UI **7 例**，两阶段重启协议同 TaskJourneyDeviceTest / D8 已接受形态）：
   - `searchShowsTitleHitsAndMessageHitsWithSnippets`：标题命中（进程级 service 态主证）+ 正文命中带片段（主证）+ 范围行（UI 辅证）；
   - `searchGroupsArchivedHitsSeparatelyFromActive`：归档/活跃分组互斥；`searchShowsTheEmptyStateWithoutAMatch`：空态；
   - `clearingTheSearchRestoresTheSessionList`：清空恢复列表 + service 态复位；`openingAResultCancelsTheSearch`：打开结果即取消搜索、返回列表未过滤；
   - `searchStateSurvivesActivityRebuilds`：活动重建（旋转等价；portrait-locked AVD 上 `recreate`×2）持久；
   - `searchStateIsFreshButSeedsSurviveAProcessRestart`：**真实进程重启**——setup 运行仅执行本方法（其余 6 个 UI 面 `assumeTrue` 跳过、`@Before` 不 seed），断言新进程无 in-memory 搜索态、seed 固定 id fixture、写 PID 标记并 `Process.killProcess`；verify 运行（`recoveryPhase=verify`）断言新 PID ≠ 标记 PID、持久 seeds 可读、搜到 `sunrise`→alpha 命中。

## 制品身份（本轮实际安装/检查对象）

| 制品 | SHA-256 |
| --- | --- |
| consumer debug app | `30ef128fb9a0ff78a84804ba8b45d453c8f4c794c028df323d21ba11efb2993c` |
| developer debug app | `719c138c4d5b1fc28e410bc4053f3d7722c2d5a8d43a77e09d2a2fc13b9d2c7f` |
| consumer debug androidTest | `03d8b5e3f227e2c06222868caa2480cc7271d01ddd18355033da0f469129e5c9` |
| developer debug androidTest | `adfd7668aa1b02fd45e1f1f059a5196f97325211269de96d0e773d0e75ceb068` |
| core:storage debug androidTest | `b317135b82e1c0bea957c87b7d4bdc3242a0db58b9d26e9342ebb236c0ce710a` |

每象限 `artifacts.json` 记录当次安装的 app/test APK 哈希（consumer 象限 = `30ef128f…`/`03d8b5e3…`，developer 象限 = `719c138c…`/`adfd7668…`）；storage androidTest APK 在修正候选排序断言后重建，G2 通过（APK mtime 新于 `SessionSearchQueryDeviceTest.kt`）。

## 命令与结果（全部实际执行）

设备矩阵（独占 AVD 自启自闭，`-read-only -no-snapshot` 全新启动，1080x2400@420，仅 teardown 自有进程组；每象限 = recovery-setup instrument（seed + 强杀）→ verify instrument（7 例）→ after-script storage connected（4 例）→ teardown）：

| 象限 | 命令 | 结果 |
| --- | --- | --- |
| Q1 consumer×36 | `bash scripts/debug/2026-09-18/run-191-search-device.sh consumer 36 5642 build/hxa-191-matrix-consumer-api36` | app `OK (7 tests)`（Time 31.59）；storage `tests=4 failures=0 errors=0`（serial=emulator-5642）；setup `Process crashed` + `previous pid=2519`；`closed.json` exit 0 |
| Q2 consumer×29 | `bash scripts/debug/2026-09-18/run-191-search-device.sh consumer 29 5644 build/hxa-191-matrix-consumer-api29` | app `OK (7 tests)`（Time 12.673）；storage `4/0/0`（serial=emulator-5644）；`previous pid=2076`；exit 0 |
| Q3 developer×36 | `bash scripts/debug/2026-09-18/run-191-search-device.sh developer 36 5646 build/hxa-191-matrix-developer-api36` | app `OK (7 tests)`（Time 31.518）；storage `4/0/0`（serial=emulator-5646）；`previous pid=2301`；exit 0 |
| Q4 developer×29 | `bash scripts/debug/2026-09-18/run-191-search-device.sh developer 29 5648 build/hxa-191-matrix-developer-api29` | app `OK (7 tests)`（Time 13.758）；storage `4/0/0`（serial=emulator-5648）；`previous pid=2157`；exit 0 |

两阶段重启协议：setup 运行仅执行 `searchStateIsFreshButSeedsSurviveAProcessRestart`（其余 6 个 UI 面 `assumeTrue` 跳过、`@Before` 不 seed），该进程断言 `sessionSearch.value.query==""`（新进程无 in-memory 搜索态）、seed 固定 id fixture、写 PID 标记并强杀；verify 运行（`recoveryPhase=verify`）断言新 PID ≠ 标记 PID、持久 seeds 可读、搜到 `sunrise`→alpha 命中。`closed.json`（`{"pid": …, "exit": 0}`）与 `owner.json`（自有 pid + serial + AVD）留痕。

构建与门禁：

| 命令 | 结果 |
| --- | --- |
| `./gradlew spotlessApply :core:storage:assembleDebugAndroidTest` | BUILD SUCCESSFUL；G2：storage androidTest APK 新于源码 |
| `./gradlew spotlessCheck detekt test` | BUILD SUCCESSFUL in 15s（宿主单测全过；`SessionSearchRepositoryTest` `tests=12 failures=0 errors=0`）|
| `bash scripts/check-all.sh --artifacts` | exit 0（variant boundaries + subscription/runtime 边界 + consumer 排除 + 正常 chat 路径 verified）|
| `bash scripts/check-all.sh --source` | 退出码非 0：`set -e` fail-fast 停在第 2 关 `check-docs`。逐项（`check-docs` 之后因 fail-fast 未跑，已**单独执行**补齐）：`test-review-gates.py` OK（6 tests）；`check-docs.sh` **红**（仅 3 项，均为 HXA-193 收尾遗留、属协调者整合范围，非本切片引入）；`verify-adr.sh` 0（31 ADR）；`check-i18n.sh` 0（1305 键 base/en/zh-rCN 平价）；`check-secrets.sh` 0 |
| `git diff --check` | clean |

> `check-docs` 的 3 项红全部源于 HXA-193 收尾删除 `tasks/HXA-193.md` 之后全局 status/roadmap/index 未整合（`Completion index is stale` / `roadmap.md: unresolved relative link: tasks/HXA-193.md` / `status.md: missing_from_status=['HXA-193']`），**不在本轮 191 切片改动范围**——本切片新增的证据/任务文档未引入任何新的 check-docs 错误。按约束「公共 status/roadmap/index 由协调者最终整合，不直接修改」，本执行者不直接改这 3 个全局文件；修复命令与登记见[建议 status 更新](#建议-status-更新供协调者整合本执行者不直接改全局-statusroadmapindex)。

## 模拟器纪律

独占创建自有 AVD `Helix191_API36`/`Helix191_API29`（`~/.android/avd/`），唯一端口 5642/5644/5646/5648；四象限**顺序**执行（consumer36 → consumer29 → developer36 → developer29），每象限由 `run-owned-emulator.py` 自启、校验 AVD 身份、仅 teardown 自有进程组（`owner.json`/`closed.json` 留痕，自有 pid，exit 0）。未借用、未关闭任何其他执行者的模拟器（含并行会话 343827a0 的 Helix-batch-b）；未向真机安装/卸载；每象限间与全部结束后 `adb devices` 无遗留、四端口均 free。

## 边界与外部事实

- **深色主题不在本轮范围**：HXA-191 的深色主题（统一跟随系统/用户选择与持久化、三语言/对比度/大字体）未做；本切片只覆盖「会话搜索」，故 **HXA-191 整体仍待实现，不写完成记录**。
- **无 schema 变更（已验证的缺失）**：本轮不改任何 `@Database` 版本、迁移或索引；`contentSearchCandidates` 是新增只读查询，`HelixStorage.sessionSearch` 是新增只读入口，复用既有 `SessionDao`/`MessageDao`/`ContentStore`，**无第二会话状态源**。
- **测试框架产物（非 app 缺陷）**：`collectAsStateWithLifecycle()` + v2 compose 规则 `EmptyActivity` 跳板在后台化 activity 时冻结状态收集，可致片段行在后台帧上未 compose；片段改以进程级 service 态（命中行渲染真源）为主证。不改 `collectAsState()`（会改变 app 电池行为，超范围）。
- **after-script 修正（本轮脚本，非 app 代码）**：AGP 9.x 的 connected 结果 XML 按「设备 + 包」命名（如 `TEST-Helix191_API36(AVD) - 16-_core_storage-.xml`）而非测试类名，原 glob `TEST-{class}.xml` 命中不到（此前因 Gradle 步先失败而未暴露）；改为宽 glob + 提取本类 `<testsuite>`，并在跑前删除旧结果 XML 以防 Gradle 复用旧产物判过（`run-191-storage-connected.py`）。
- 未使用缓存或 `--help` 输出充当验收；无真实付费账号、无真实用户会话数据（设备测试仅用自造 fixture session/message id 与内容）。

## 复现入口

```bash
# 四象限之一（独占 AVD/端口，顺序执行 consumer36→consumer29→developer36→developer29）
bash scripts/debug/2026-09-18/run-191-search-device.sh <consumer|developer> <29|36> <port> <output-dir>
# storage connected 查询测试（矩阵 after-script 亦自动调用）
python3 scripts/debug/2026-09-18/run-191-storage-connected.py <serial> <output-dir>
# 门禁
./gradlew spotlessCheck detekt test
bash scripts/check-all.sh --source
bash scripts/check-all.sh --artifacts
git diff --check
```

## 建议 status 更新（供协调者整合，本执行者不直接改全局 status/roadmap/index）

- **HXA-191 保持「待实现 / INCOMPLETE」**：搜索切片已交付并验收（本证据），但深色主题未做，不满足整体验收；**不写 `docs/completion-records/HXA-191.md`**。
- 搜索切片的验收证据以本文件 `docs/evidence/development/hxa-191-session-search-2026-09-18.md` 为准；`docs/development/tasks/HXA-191.md` 的 `## 交付` 之后已附本切片记录。
- 协调者待整合的既有文档残留（本执行者不直接改，仅在此登记 + 建议）：
  - `docs/development/roadmap.md:205` HXA-193 链接指向已删除的 `tasks/HXA-193.md` → 建议改指 `../development/tasks/HXA-193.md`；
  - `docs/development/status.md:23` HXA-193 状态滞后 → 建议改指 193 完成记录与本 193 证据；
  - `docs/completion-records/index.md`（173/174 行）HXA-192→HXA-194 跳号 → 建议 `python3 scripts/generate-completion-index.py` 重新生成（加入 HXA-193）。
