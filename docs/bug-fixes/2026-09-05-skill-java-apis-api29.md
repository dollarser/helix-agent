# Bug Fix: Skill 纯 JVM 模块再次调用 API 29 缺失的 Java 新方法

Status: fixed
Date: 2026-09-05
Related HXA: HXA-074, HXA-075, HXA-076
Affected modules: `extensions:skills`, `app`

## Problem

HXA-076 的 API 29 设备验收前复核发现，`:extensions:skills` 作为纯 JVM 模块对 JDK 17 编译，三处生产调用会在 Android 10 上到达平台不存在的方法：

- `SkillCatalogLoader.scan` 的 `Stream.toList()`；
- `SkillImportService.locateManifestRoot` 的 `Stream.toList()`；
- `SkillRepository.persistState` 的 `Files.writeString()`。

`javap -c` 确认前两处字节码直接调用 `java/util/stream/Stream.toList`，第三处直接调用 `java/nio/file/Files.writeString`。JVM fixture 与 API 36 均不会暴露；首次 catalog 扫描/目录导入或持久 enable/disable 在 API 29 上会 `NoSuchMethodError`。

## Impact

Android 10 用户无法可靠扫描或导入本地 Skill；即使已有 Skill 可列出，首次 global enable/disable 也会在状态持久化时失败。这会让 HXA-074～076 的核心产品路径在 minSdk 上不可用，不能归类为测试专用问题。

## Root cause

与 [2026-09-03 的同类缺陷](2026-09-03-jvm-stdlib-calls-missing-on-api29.md)相同：纯 JVM 模块没有 Android lint `NewApi` 覆盖，宿主 JDK 17 单测也不代表 minSdk 29 的 `java.*` 运行时。此前 HXA-074/075 的 verification matrix 均为 JVM-only，App 合并回归只初始化 Skill repository，没有实际执行 catalog/import/persistState 路径，因此出现假绿盲区。

## Fix and invariants

- 两处 `Stream.toList()` 改为 Java 8/API 24+ 的 `collect(Collectors.toList())`。
- `Files.writeString()` 改为显式 UTF-8 `Files.write(byteArray, TRUNCATE_EXISTING)`。
- 新增 `SkillToolsDeviceTest`，在生产 `AppContainer` 上实际执行 catalog 扫描、目录导入、分页 list、会话 enable（触发 state persistence）、read、read_resource 与 recoverable remove；API 29/36 均覆盖。
- 长期不变量保持不变：任何会进入 Android APK 的纯 JVM 模块在调用 Java 9+ `java.*` 方法前必须核对 Android API 等级；JVM-only 验收不能宣称 minSdk 兼容。

## Alternatives considered

- 启用 core-library desugaring：会为三个可等价替换的调用扩大依赖与所有变体的 dex/link 行为，且不能解决纯 JVM 模块缺少 Android lint 的一般问题，不采用。
- 抬高 minSdk：与当前 Android 10 产品边界冲突，不采用。
- 只增加 API 29 测试而保留调用：测试会稳定证明生产失败，不能形成修复，不采用。

## Regression verification

- `./gradlew :app:testConsumerDebugUnitTest :extensions:mcp:test :extensions:skills:test`：App 262、MCP 35、Skills 31 tests，均 0 failed/0 skipped。
- `SkillToolsDeviceTest`：专用 API 29/36 arm64-v8a 模拟器各 1/1，0 failed/0 skipped。
- `./gradlew :app:connectedConsumerDebugAndroidTest`：API 29 为 118 tests、0 failed、1 个 SDK 条件 skip；API 36 为 117 tests、0 failed、0 skipped。
- 修复后 `javap` 扫描 `SkillImportService`、`SkillCatalogLoader`、`SkillRepository` 已无 `Stream.toList` 或 `Files.writeString` 调用。

## Residual risk

纯 JVM 模块仍没有 Android lint 静态防线；本次以字节码复核和 minSdk 设备 E2E 补足已知路径。后续新增 Java 标准库调用仍须执行同一审查，不能仅依赖 API 36 或宿主 JVM。

## Related records

- [HXA-076 完成记录](../completion-records/HXA-076.md)
- [此前同类 API 29 Java 方法缺陷](2026-09-03-jvm-stdlib-calls-missing-on-api29.md)
- [M7 合并与验证进展](../development/m7-non-device-progress.md)
