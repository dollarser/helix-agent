# Bug Fix: JVM 模块调用了 API 29 平台缺失的 `java.*` 标准库方法（`Stream.toList`、`InputStream.skipNBytes`）

Status: fixed
Date: 2026-09-03
Related HXA: HXA-042, HXA-046, HXA-048
Affected modules: `core:workspace`, `app`

## Problem

在 API 29（Android 10，`minSdk = 29`）模拟器上，文件管理能力整体不可用，表现为两层：

1. `FilesScreenTest` ×6 全部 `ComposeTimeoutException`：文件条目节点永远不出现。
   手动 adb 复现（装 consumer debug APK → 进入文件页）看到页面直接显示错误文本
   `No interface method toList()Ljava/util/List; in class java.util.stream.BaseStream…`
   —— `WorkspaceArtifactStore.listDir` 调用的 `Stream.toList()` 是 Java 16 的接口默认方法，
   Android 平台 API 31 起才有，API 29 上运行时 `NoSuchMethodError`，被 UI 的
   `runCatching` 捕获后渲染为错误态（所以不崩、logcat 无 crash，只有 30 秒静默）。
2. 修复第 1 处后，同 6 例中 4 例（凡是打开文件预览的）又暴露同类缺陷：
   `java.lang.NoSuchMethodError: No virtual method skipNBytes(J)V in class
   Ljava/io/InputStream`（core-oj.jar）—— 路径
   `ReadWindow$Companion.read(ReadWindow.kt) → WorkspaceArtifactStore.readWindow →
   FileManagerService.previewText`。`InputStream.skipNBytes(long)` 是 Java 11 方法，
   API 29 平台运行时中不存在（设备实测），API 36 存在（`javap` 对 android-36
   `android.jar` 验证）。

两处方法在 API 36 模拟器与宿主机 JDK 17（JVM 单测）上都存在，因此此前所有 JVM
测试与 API 36 设备矩阵都是绿的——只有 API 29 设备能抓到。

## Impact

任何真实的 Android 10（API 29）用户：文件管理页（HXA-046）无法列出 Workspace 任何
目录；`read` 工具与文本预览对任何文件都失败（`ReadWindow` 是 `read` 的唯一读路径）。
文件工具区（`files.list`/`read`/预览）在该 API 等级上整体不可用。是生产能力缺失，
不是测试问题。

## Root cause

- `:core:workspace` 是**纯 JVM 库模块**（根 `build.gradle.kts` 的 `jvmLibraries`，
  `jvmToolchain(17)`），对着 JDK 17 类库编译，随后被 D8 打进 App 在设备上运行。
- D8 在**未启用** core-library desugaring 时不会回填 Java 9+ 标准库方法；平台
  运行时接口/类里没有该方法 → 运行期 `NoSuchMethodError`。
- 为什么没被拦住：
  (a) JVM 单测跑在宿主机 JDK 17，两个方法都存在；
  (b) JVM 模块没有 lint task（`:core:workspace:lintDebug` 不存在），Android 模块上
  生效的 lint NewApi（minSdk 29、`abortOnError = true`）对这类模块完全失守；
  (c) HXA-048 之前设备矩阵只在 API 36 上跑过。
- 与 [Room 迁移 `RENAME COLUMN` 缺陷](2026-09-03-room-migration-sqlite-rename-column.md)
  同一类：代码按作者手边最新环境（JDK 17 / API 36 设备）编写，而不是按 minSdk 编写。
- 曾误诊为"API 29 模拟器慢 + 冷启动 `waitTag` 超时抖动"（每例耗时恰好 = 预算 + ~1s）。
  把预算提到 30s 重跑仍 6/6 失败，且手动设备复现看到页面处于错误态，才定位到
  `NoSuchMethodError`。教训：每例精确顶满预算 ≠ 慢，可能是等待的节点永远不会出现。

## Fix and invariants

- `WorkspaceArtifactStore.listDir`：`Stream.toList()` →
  `.collect(Collectors.toList())`（`java.util.stream.Collectors.toList` 是 Java 8 /
  API 24+ 可用；行为不变）。
- `ReadWindow.read`：`input.skipNBytes(offset)` → 用固定 8 KiB 缓冲 `read()` 排空
  offset（语义等价：offset 内提前 EOF 抛 `EOFException`；相比 `skip()` 不会短跳，
  且对 >2 GiB offset 无 int 截断问题）。
- 测试侧：`FilesScreenTest` 的 `waitTag` 冷启动预算 10s → 30s（健康运行时
  `waitUntil` 条件即满足即返回，零成本；只覆盖 API 29 镜像冷启动 + 首帧）。
- **长期不变式**：凡在设备上运行的模块（全部 `jvmLibraries` 与 Android 模块），
  生产代码不得调用 API 29 平台缺失的 `java.*` 标准库方法。已知缺失/需逐一核实的
  包括：`Stream.toList`（API 31+）、`InputStream.skipNBytes`/`readNBytes`、
  `OutputStream.writeNBytes`/`flushNBytes`（API 29 平台缺失，设备/javap 实测）、
  `List.of`/`Map.of`/`Set.of`/`Map.entry`/`Collectors.toUnmodifiable*`/
  `Objects.requireNonNullElse`（API 30+）、`Files.mismatch`（API 31+）。
  使用任何 Java 9+ 新增的 `java.*` 方法前必须核对其 Android API level。
- 本次已对全部生产源码 grep 审计上述清单：除已修两处外无其他命中；
  `feature:files-allfiles` 的 `Path.of` 属 Android 模块，由 lint NewApi（minSdk 29）
  持续覆盖且历次门禁全绿，判定安全，不改。

## Alternatives considered

**Core-library desugaring（`isCoreLibraryDesugaringEnabled` + `desugar_jdk_libs`）。**
拒绝：为两处调用点引入新的、体积可观的供应链依赖，并改变所有变体的 dex/链接行为；
收益不对称。JVM 模块无法被 lint 覆盖的问题，desugaring 只解决 `java.*` 这一类，
不解决"没有静态检查"本身。

**抬高 `minSdk` 到 30/31。** 拒绝：放弃 Android 10 用户，与 ADR-0013 的完整产品形态
定位冲突；与 `RENAME COLUMN` 记录中拒绝的理由相同。

**重写 `listDir`/`ReadWindow`（DirectoryStream / FileChannel）。** 拒绝：改变既有
契约（排序、分页、窗口边界语义），两处各几行的等价替换即可消除缺陷，保持最小 diff。

## Regression verification

缺陷回归由**设备**运行承载（JVM 无法到达平台运行时的方法缺失）：

- JVM：`./gradlew :core:workspace:test :tools:files:test` → BUILD SUCCESSFUL。
  `ReadToolTest` 的 10 MiB / 10 窗口分页用例（"no byte skipped or re-read"）与
  4 字节多字节 UTF-8 窗口用例完整覆盖 offset > 0 的新排空路径。
- 设备（2026-09-03，`./gradlew :app:connectedConsumerDebugAndroidTest`，consumer 变体，
  双模拟器）：
  - **API 29 — `Helix_API_29(AVD) - 10`**：47/47，0 失败（修复前同一套件为 8 失败：
    migration ×2 已由前一条记录修复 + `FilesScreenTest` ×6）。`FilesScreenTest` 6 例
    全部 PASS。
  - **API 36 — `Helix_API_36(AVD) - 16`**：47/47，0 失败，无回归。
    （首轮全量复测中 `GoalReminderTest.deferrableReminderPostsNotificationWithoutModelOrToolWork`
    在 API 36 出现 1 次失败，双模拟器并发全量负载下的一次性 flake：单独重跑
    2/2 通过（API 36 与 API 29），且前一轮（不含本次 `ReadWindow` 改动的 APK）
    同例通过；与本改动无关，见 Residual risk。）

## Residual risk

- 该缺陷类（JVM 模块调用 `java.*` 新方法）**没有**编译期/lint 防线，唯一运行时防线是
  API 29 设备矩阵；今后新增此类调用只能靠上文不变式 + API 29 设备门禁兜底。
- `GoalReminderTest` 在双模拟器并发全量负载下存在通知投递时序 flake（单独重跑通过，
  本改动前后均出现过），非本缺陷，建议单独跟进（如放宽等待或串行化该用例）。
- 多 ABI 与真机矩阵仍待执行（见 status.md）。

## Related records

- [HXA-042 完成记录](../completion-records/HXA-042.md)（引入 `ReadWindow` 与 `WorkspaceArtifactStore.listDir`）
- [HXA-046 完成记录](../completion-records/HXA-046.md)（文件管理 UI，症状入口）
- [HXA-048 完成记录](../completion-records/HXA-048.md)（API 29 设备验证暴露本缺陷）
- [status.md](../development/status.md)（设备矩阵状态）
- [Room 迁移 `RENAME COLUMN` 记录](2026-09-03-room-migration-sqlite-rename-column.md)（同缺陷类）
- `core/workspace/src/main/kotlin/com/helix/core/workspace/WorkspaceArtifactStore.kt`（`listDir`）
- `core/workspace/src/main/kotlin/com/helix/core/workspace/ReadWindow.kt`（`read`）
- `app/src/androidTest/kotlin/com/helix/app/ui/FilesScreenTest.kt`（设备级回归 ×6 + 预算常量）
