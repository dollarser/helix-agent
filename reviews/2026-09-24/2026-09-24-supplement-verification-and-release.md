# Helix 审查补充：验证体系、发布就绪度与门禁盲区

**日期**：2026-09-24
**基线**：HEAD `3cf89027`（工作树有未提交改动）
**范围**：本文补三份既有报告（`2026-09-24-code-review.md`、`review-reevaluation.md`、`structure-review.md`）**完全未覆盖**的四个维度，并对其中一处早期错误陈述做修正。
**方法**：全部结论来自只读命令（`grep`/`unzip`/`Read`），未修改任何项目文件。

---

## 〇、本文为什么重要：它解释了前几轮的共同失效模式

前几份报告反复出现同一类结论——**"实现存在 + 单测通过 + 端到端不成立"**：

| 实例 | 来源 |
|---|---|
| 孤儿 GC（`PrivacyDeletionService.cleanOrphanFiles`）生产零调用方 | 再评估 §四 |
| 工具耗时投影断链（`ChatScreenProjection` 不带 `durationMs`） | 再评估 §四 |
| JS 输出 64 KiB vs 契约 256 KiB（`PARCEL_INLINE_MAX_BYTES`） | 再评估 §四 |
| 进程死亡恢复 P0（`PersistedTurn.init` 断言 vs `ToolScheduler` 并行） | 主报告 P0 |

**本文给出的机制性解释：项目的验证体系与生产路径之间存在系统性断层。** 这不是四个孤立疏漏，而是同一原因的四次显形。

---

## 一、验证体系有效性（**P0**，本文最高优先级发现）

### 1.1 CI 从不执行任何设备测试

**证据链**：

1. `scripts/check-all.sh:22` —— `test_build_checks()` 只跑 `./gradlew test`（JVM 单元测试）：
   ```
   ./gradlew test :app:assembleConsumerDebug :app:assembleDeveloperDebug \
       :runtime:proot-app:assembleDebug :runtime:cli-app:assembleDebug \
       :app:assembleConsumerRelease :app:assembleDeveloperRelease \
       :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease
   ```
   `source_checks()`（`:11-24`）与 `analysis_checks()`（`:26`）同样不含设备测试。
2. `scripts/check-all.sh:3` 的注释是自认的：`# Device tests and network/runtime-asset qualification are explicit separate runs.`
3. `.github/workflows/ci.yml` 的 gate 矩阵只有 `[analysis, tests-build]`，加上 `source` / `artifacts` / `release-artifacts`，**全部经 `ci-run-gate.sh` 落到上述 JVM 命令**。
4. **决定性证据**：`grep -rn "connectedAndroidTest|connectedDebugAndroidTest|managedDevices|ManagedVirtualDevice" scripts/ .github/` → **0 命中**。

**而设备测试恰恰是项目对核心路径的主要验证手段**：

| 模块 | `src/test` 文件 | `src/androidTest` 文件 |
|---|---|---|
| `:app` | 114 | **194** |
| `:core` | 94 | 15 |

`:app` 的 androidTest 文件数是 unit test 的 **1.7 倍**。`docs/bug-fixes/` 里反复以设备测试作为验收证据，例如：

- `2026-09-07-cli-interrupt-wait-cancel.md:32`：「修复后 Client JVM 30/30、Developer 300 通过/3 既有跳过，**API 34 跨进程 fixture 9/9**」
- `2026-09-07-cli-job-remote-exception.md:32`：「**修复后 3/3 设备测试通过**」
- `2026-09-03-approval-device-tests-session-scoped-probe.md:11`：「3 个测试类共 **9 例**审批/审计设备测试全部失败」

**结论**：这 194 个设备测试是**一次性证据生成器，不是回归门禁**。

**影响范围**：全部核心路径。任何一次设备测试通过后都不会再被重跑——`2026-09-07` 通过的跨进程 CLI fixture 今天是否仍通过，无人知道；审批/审计链路（`:2026-09-03` 修好的 9 例）在此后所有改动中**没有任何回归保护**。

**严重程度**：**P0**。它使项目"有测试"的自我认知与"有回归保护"的事实之间存在缺口，且缺口在核心路径上。

**改进建议**：
1. 立即建立**可重复的设备测试入口**，哪怕不进每个 PR：新增 `scripts/check-all.sh --device` 分档（对应现有 `--artifacts` 的设计），或接入 Gradle Managed Devices（`managedDevices { localDevices { create("pixel2Api34") { ... } } }`）使其可在 CI runner 上无头执行。
2. 按**风险而非全量**排序首次纳管：`app/src/androidTest/.../recovery/`（恢复）、审批/审计、Room 迁移（`assets/com.helix.core.storage.HelixDatabase` 已备好 v1 fixture）、CLI/PRoot 跨进程。
3. 在 `status.md` 明确区分「已验证（一次性证据）」与「受门禁保护（回归）」两类状态——目前二者混同。

---

### 1.2 `ProcessRecoveryTest` 的 9 个测试全部只 seed **单个**工具调用

这是上一轮 P0 恢复缺陷**能长期存活**的直接原因。

**证据**：`app/src/androidTest/kotlin/com/helix/app/recovery/ProcessRecoveryTest.kt`（26,990 字节）有 9 个 `@Test`（`:73, :108, :144, :180, :349, :388, :417, :449, :470`）。逐条检查所有 `seedCall` 调用：

```
:155  dying.seedCall("tc-1", "turn-1", "call-1", "bash", """{"cmd":"ls"}""", ToolCallState.RUNNING)
:335  storage.seedCall("tc-1", "turn-1", "call-1", "bash", """{"cmd":"sleep 5"}""", ToolCallState.RUNNING)
```

**每一条都是 `tc-1`，全部只 seed 一个 RUNNING 调用。**

**为什么这是决定性的**：`core/agent/.../RecoveryCoordinator.kt:32-36` 的 `PersistedTurn.init` 硬断言「至多 1 个 RUNNING 工具调用」。而生产侧 `ToolScheduler` 的 `DEFAULT_MAX_CONCURRENCY = 2` 允许并行，`ChatToolCalls.kt:261` 的批量入口确实走 `scheduleBatch`。

> **测试与被测契约共享了同一个错误前提**（"工具调用是串行的"），因此这个测试**永远不可能**发现该契约已经被并行化推翻。它不是"覆盖不足"，而是"以错误假设写成的测试会伪装成覆盖"。

这也解释了 `structure-review.md` §0 冲突项 2 的处方为何关键：现有决定「先补齐生产不变量测试再考虑移动」**尚未执行**，而这条 P0 正是"生产不变量未被测到"的产物。

**改进建议**：给 `ProcessRecoveryTest` 增加一条显式用例——**seed 2 个并发 RUNNING 调用**（`tc-1` + `tc-2`），断言恢复后两者都得到持久终态且不抛异常。这是 P0 的最小复现，也是修复后的验收断言。

---

### 1.3 门禁脚本清单校正（**修正我此前的一处错误陈述**）

上一轮我在再评估报告中写「`scripts/` 里 **106 个** `accept-hxa-*.sh` 验收脚本本身未被审查」——**这是错的**。106 是 `scripts/` 下 `.sh` 文件的总数口径，我误当成了 `accept-hxa-*` 的数量。

**实测**：

| 类别 | 数量 |
|---|---|
| `scripts/` 下 `*.sh` 总数 | **28** |
| `check-*.sh` | **12** |
| `accept-hxa-*.sh` | **10** |

10 个 accept 脚本：`083-lifecycle`、`086-lifecycle`、`087-updates`、`124-connectors`、`125-connectors`、`125-sample`、`125-workbuddy`、`148-skill-creator`、`149-skill-installer`、`150-mcp-installer`。

**结论修正**：真正未被审查的是 **10 个 accept 脚本 + 12 个 check 脚本的断言有效性**（数量比我此前声称的少一个量级，但问题性质不变）。特别值得注意：

- 这 10 个 accept 脚本对应的 HXA（083/086/087/124/125/148/149/150）**全部是扩展与生命周期类**，即 MCP/Skill/Connector/更新链路——**正是"实现存在但端到端不成立"的高发区**。
- `check-*.sh` 中 `check-docs.sh`、`check-i18n.sh`、`check-secrets.sh`、`verify-adr.sh` 是**文档与静态契约**门禁，`check-lockfiles.sh`、`verify-variant-boundaries.sh`、`check-cli-runtime-boundary.sh` 是**边界**门禁。**12 个 check 脚本里没有一个是"端到端行为"门禁**——与 1.1 的结论互为印证。

---

## 二、发布与分发就绪度（**P1**，前几轮完全未涉及）

`:app` 是 store-facing 的完整产品（AGENTS.md 明确 Standard 为「complete store-facing product」），但发布链路处于 M0 状态未动。

### 2.1 APK 体积：consumer release **45 MiB**，其中 **约 90% 是未裁剪 DEX**

**实测**（`unzip -l` 对 `app/build/outputs/apk/consumer/release/app-consumer-release-unsigned.apk`）：

| 构成 | 大小 | 占比 |
|---|---|---|
| `classes.dex` | 14.07 MiB | |
| `classes5.dex` | 11.97 MiB | |
| `classes4.dex` | 11.54 MiB | |
| `classes6.dex` | 4.66 MiB | |
| `classes3.dex` | 0.013 MiB | |
| **DEX 合计** | **40.30 MiB** | **≈ 90%** |
| `resources.arsc` | 1.14 MiB | |
| `lib/*/libquickjs.so` ×4 ABI | 2.96 MiB | |
| 其余（okhttp PSL、jgit 资源、META-INF 等） | 余量 | |

四个变体产物体积：

| 变体 | 体积 |
|---|---|
| consumer debug | 59 MiB |
| **consumer release** | **45 MiB** |
| developer release | 101 MiB |
| developer debug | 123 MiB |

**根因**：`app/build.gradle.kts:36` `isMinifyEnabled = false`，且 `app/proguard-rules.pro` 全文只有 1 行占位注释：

```
# M0 keeps release shrinking disabled. Rules will be added with real features.
```

**影响**：45 MiB 中约 40 MiB 是未经 R8 裁剪的字节码。本项目 32 模块、引入 JGit、4 个 Provider SDK、MCP SDK、Room、Compose——其中大量代码在任一具体运行路径上不可达。R8 是**单一最高杠杆的体积动作**，且它同时带来混淆与优化收益。

**风险提示**（这是它长期未开启的真实原因，需在开启时逐项处理）：反射密集区域——WebView JS bridge、PRoot/CLI 的 Binder 跨进程类型、Room 实体、Provider 的 JSON 反序列化 DTO、MCP SDK。`config/jgit/reject-insecure-tls.gradle.kts` 与 `config/jgit/AndroidInputStreams.java` 的存在说明 JGit 已有特殊接线，需一并纳入 keep 规则。

**改进建议**：分两步——先开 `isMinifyEnabled = true` + `isShrinkResources = true` 并**只加 keep 规则到能过现有测试**（不追求激进优化），再逐模块收紧。同时应加一条体积回归门禁（如 CI 中记录 APK 字节数并在超阈值时告警），否则体积会再次无声膨胀。

### 2.2 4 个 ABI 全量打包，无 ABI 切分

**证据**：`grep -rn "abiFilters|splits|universalApk|enableSplit" --include="*.kts" app/ build.gradle.kts` → **0 命中**。

| ABI | `libquickjs.so` |
|---|---|
| `x86_64` | 872,528 B |
| `arm64-v8a` | 854,336 B |
| `x86` | 827,904 B |
| `armeabi-v7a` | 547,644 B |

`x86` + `x86_64` 合计 **约 1.62 MiB**，对真机用户是纯浪费（仅模拟器需要）。App Bundle 会按 ABI 切分，但直接分发（developer 渠道）不会。

**严重程度**：P1（体积）。**改进建议**：若走 Play 则依赖 AAB 切分即可；若保留直接分发渠道，考虑 `splits { abi { ... } }` 或显式 `ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }`（本项目已有 arm64/armeabi 的 RootFS 资产，x86 支持的真实需求需确认）。

### 2.3 `versionCode = 1` / `versionName = "0.1.0"` 自 M0 起未动

**证据**：`app/build.gradle.kts:18-19`：

```kotlin
versionCode = 1
versionName = "0.1.0"
```

全仓 `versionCode`/`versionName` 仅此一处声明（另 `runtime/proot-app/build.gradle.kts:29` 只是注释引用）。

**影响**：`scripts/accept-hxa-087-updates.sh` 的存在表明"更新链路"在项目范围内。版本号恒定意味着：无法灰度、无法回滚、无法区分用户实际运行版本、`HXA-087` 的更新链路缺乏真实版本演进可测。

**严重程度**：P1（发布就绪度）。**改进建议**：确立版本来源单一化（如从 `libs.versions.toml` 或 CI 注入），并在 `status.md` 记录发版流程。

### 2.4 无 release 签名配置

**证据**：`grep -n "signingConfig" app/build.gradle.kts` → 0 命中；产物名为 `app-consumer-release-unsigned.apk`。

**严重程度**：P2。若走 Play App Signing 则上传密钥仍需配置；若直接分发则必须补。建议在 `local.properties`/CI secrets 注入，不入库。

---

## 三、静态分析门禁的盲区（**P2**）

### 3.1 `UnusedResources` 被主动关闭

**证据**：`config/lint/lint.xml:9`

```xml
<issue id="UnusedResources" severity="ignore" />
```

同文件另忽略 `AndroidGradlePluginVersion`、`GradleDependency`、`NewerVersionAvailable`、`OldTargetApi`、`PluralsCandidate`。

前五项有合理理由（版本被有意钉住、见文件头注释）。但 **`UnusedResources` 的关闭与"优化与删减"维度直接冲突**：那份报告试图统计未使用资源，而门禁主动让这类问题不可见。在一个 45 MiB、含大量历史 drawable/string 的工程里，这是持续累积的隐性债务。

**改进建议**：改为 `severity="warning"` 并生成 baseline，或至少周期性跑一次 `lint --check UnusedResources` 做人工清理。

### 3.2 detekt 门禁的实际通过状态**存在矛盾，需一次实跑裁定**

**证据**：

- `config/detekt/detekt.yml` 全文 **23 行**，唯一关闭的规则是 `style.MagicNumber`（`:21-23`），其余走 detekt 默认（`build.gradle.kts:85` 的 `--build-upon-default-config`）。
- `build.gradle.kts:58-94` 的 `detekt` 任务：`JavaExec`，`--input projectDir.absolutePath`，`--excludes "**/build/**,**/.gradle/**,**/*.gradle.kts"`，**无 `--baseline`、无 `ignoreFailures`、无 `maxIssues`**。
- `scripts/check-all.sh:25` 在 `analysis_checks()` 中执行 `./gradlew spotlessCheck detekt lintDebug ...`。
- **但** `docs/bug-fixes/` 多处记录根 detekt 未通过：`2026-09-07-cli-interrupt-wait-cancel.md:32`「**根 Detekt 仍有 18 项**」、`2026-09-07-cli-job-remote-exception.md:32`「**根 Detekt 剩余 42 项未通过**」。

**矛盾**：`JavaExec` 非零退出即门禁失败，且无 baseline 豁免——这与"仍有 18/42 项未通过"不能同时为真。三种可能，需实跑 `./gradlew detekt` 一次裁定：

1. 那些记录描述的是**该配置生效之前**的状态（文档滞后）；
2. detekt 实际通过，记录中的数字来自不同调用方式（如按模块跑）；
3. **门禁实际是红的**，即 `check-all.sh --all` 目前不能通过——这是最需要排除的情况。

**另注**：`--excludes` **未排除测试源码**，`**/src/test/**` 与 `**/src/androidTest/**` 都在扫描范围内。若那 18/42 项主要来自测试代码，则说明门禁对测试代码的规则严格度与生产代码相同，这本身值得重新权衡（测试代码对 `LongMethod`/`MagicNumber` 的容忍度通常应更高）。

**严重程度**：P2（但若属第 3 种情况则升级为 P0——门禁失效）。**改进建议**：实跑一次并记录结果到 `status.md`；若确有存量，建 baseline 并锁死"不再新增"，而不是长期容忍。

### 3.3 Room 索引：**核对一致，无问题**

`core/storage/src/main/kotlin/com/helix/core/storage/entity/` 下 17 个 entity 文件，其中 10 个显式声明 `indices`（`ConversationEntities.kt` 最多，10 处；`A2aEntities.kt`/`ConfigEntities.kt`/`GoalEntities.kt`/`McpSkillEntities.kt` 各 2 处）。索引覆盖看起来是有意设计的，未发现明显缺失。此项**核对一致**。

---

## 四、明确仍未覆盖的盲区（建议作为下一轮）

前几轮 + 本文，以下仍然**完全没有证据**。按价值排序：

| # | 盲区 | 为什么重要 | 建议动作 |
|---|---|---|---|
| 1 | **运行时复现** | 四份报告全部是静态审查。含本文 P0 与上一轮 P0，**零运行时证据** | 一条 `androidTest`：并发只读 ×2 + 执行中 `SIGKILL` + 重启断言；先在真机跑通它，再改代码 |
| 2 | **性能基线** | 主报告 §4.1/§4.2 的启动与重组项跨两个审查周期"仍存在"，因为没有基线就无法证明改善或回归 | 先跑 Macrobenchmark（启动 + 滚动）与 Compose recomposition count，再谈优化排序 |
| 3 | **门禁脚本的断言有效性** | §1.3：10 accept + 12 check 脚本**无一为端到端行为门禁** | 逐个读，确认每个脚本的断言是否真能失败（"能在缺陷存在时变红"是门禁的最低标准） |
| 4 | **可观测性/故障现场** | `app/src/main` 下仅 **4 个文件**使用 `android.util.Log`（`HelixApplication.kt`、`ChatService.kt`、`ChatToolCalls.kt`、`ProviderScreen.kt`），`app/diagnostics/` 无统一日志/脱敏门面 | 低风险（日志少 = 泄漏面小），但设备上故障时现场信息可能不足。评估 `DiagnosticBundle` 是否够用 |
| 5 | **无障碍 / i18n 运行时 / 横屏字体缩放的真机验证** | UI 报告已给出 `contentDescription` 覆盖率等静态度量，但无真机 TalkBack / 字体缩放 200% / 横屏证据 | 一轮真机走查 |
| 6 | **供应链许可证合规** | `THIRD_PARTY_NOTICES.md` 与 `gradle/libs.versions.toml` 的实际依赖集是否一致（引入 JGit、4 个 Provider SDK、MCP SDK、PRoot 资产后未复核） | 生成依赖清单比对；`packaging.resources.excludes` 已剥离 `META-INF/LICENSE*`/`NOTICE*`（`app/build.gradle.kts:62`），需确认剥离后合规声明仍在 `THIRD_PARTY_NOTICES.md` 中完整 |

---

## 五、汇总

### 5.1 本轮新增发现

| # | 发现 | 维度 | 严重程度 | 置信度 |
|---|---|---|---|---|
| 1 | CI 从不执行任何设备测试（194 个 androidTest 文件无回归保护） | 验证体系 | **P0** | 高（四环证据链） |
| 2 | `ProcessRecoveryTest` 9 个测试全部只 seed 单个工具调用，与错误契约共享前提 | 验证体系 | **P0** | 高（逐行核验） |
| 3 | consumer release 45 MiB，约 90% 为未裁剪 DEX；R8 关闭且 proguard 规则为空 | 发布就绪 | **P1** | 高（`unzip` 实测） |
| 4 | 4 个 ABI 全量打包，x86/x86_64 约 1.62 MiB 对真机无用 | 发布就绪 | P1 | 高 |
| 5 | `versionCode=1`/`versionName="0.1.0"` 自 M0 未动，与 HXA-087 更新链路不匹配 | 发布就绪 | P1 | 高 |
| 6 | `UnusedResources` 被 lint 主动忽略，死资源不可见 | 门禁盲区 | P2 | 高 |
| 7 | detekt 门禁通过状态自相矛盾（无 baseline/ignoreFailures vs 文档记录 18/42 项未通过） | 门禁盲区 | P2（可能升 P0） | 中——需实跑裁定 |
| 8 | 无 release 签名配置 | 发布就绪 | P2 | 高 |
| 9 | 12 个 `check-*.sh` 中无一是端到端行为门禁 | 验证体系 | P2 | 高 |

### 5.2 本轮修正

| 项 | 原陈述 | 修正 |
|---|---|---|
| `accept-hxa-*.sh` 数量 | 「106 个」（再评估报告 §六） | **10 个**。106 是 `scripts/` 下 `.sh` 总数，口径混用所致 |

### 5.3 本轮核对一致（无问题）

- Room 索引覆盖（17 个 entity，10 个显式 `indices`）
- lint 采用 `abortOnError = true` + `warningsAsErrors = true`（`app/build.gradle.kts:55-56`）——严格度良好
- 无 lint/detekt baseline 文件被用来掩盖存量（`Glob **/*baseline*.xml` → 0 命中）——**这一点优于多数同规模工程**
- consumer flavor 的依赖排除真实生效（`app/build.gradle.kts:129-141` 的 11 个 `developerImplementation` 项覆盖 allfiles/automation/root/proot 全族/cli 全族/terminal-renderer）

### 5.4 建议优先级（本轮新增部分）

| 优先级 | 动作 | 理由 |
|---|---|---|
| **P0** | 给 `ProcessRecoveryTest` 加"2 个并发 RUNNING 调用"用例 | §1.2：上一轮 P0 的最小复现，同时是修复后的验收断言。**在改代码之前先让测试红** |
| **P0** | 建立可重复的设备测试入口（`--device` 分档或 Gradle Managed Devices） | §1.1：194 个文件目前零回归保护，是"端到端不成立"类缺陷的机制性成因 |
| **P1** | 开启 R8 + `shrinkResources`，先保守加 keep 规则 | §2.1：单一最高杠杆体积动作（40 MiB DEX） |
| **P1** | 实跑一次 `./gradlew detekt` 并记录结果 | §3.2：排除"门禁实际是红的"这一最坏情况 |
| **P2** | `UnusedResources` 改为 warning + baseline；配置 ABI 切分；确立版本号来源 | §3.1 / §2.2 / §2.3 |

---

*本文补四个此前未覆盖的维度（验证体系、发布就绪度、静态分析盲区、可观测性），并对一处早期数量陈述做修正。所有结论附命令或文件:行号。未修改任何项目文件。*
