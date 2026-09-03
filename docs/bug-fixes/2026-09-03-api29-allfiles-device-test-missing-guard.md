# Bug Fix: AllFilesDeviceTest 直调 API 30+ 平台方法，API 29 设备 NoSuchMethodError

Status: fixed
Date: 2026-09-03
Related HXA: HXA-045
Affected modules: `app`（androidTestDeveloper 测试代码）

## Problem

`:app:connectedDeveloperDebugAndroidTest` 在 **API 29** 设备上，
`AllFilesDeviceTest` 6 例中 3 例以同一异常失败：

```
java.lang.NoSuchMethodError: No static method isExternalStorageManager()Z
in class Landroid/os/Environment (declaration of 'android.os.Environment'
appears in /system/framework/framework.jar!classes2.dex)
```

失败用例：`scopeResolutionAgreesWithTheLiveSystemGrant`、
`anEnabledRootResolvesAndStaysContainedWithinTheGrant`、
`consentScreenShowsLiveStateAndTheSettingsJumpWhenDenied`——三处都直接调用
`Environment.isExternalStorageManager()`（**API 30+** 才有），API 29（Android 10，
`minSdk = 29`）平台运行时中该方法不存在。

## Impact

- developer 变体设备矩阵在 API 29 上不可全绿（3/6 例恒定失败，非 flake）。
- **无生产影响**：逐一核查过生产侧与其余测试，全部已有
  `Build.VERSION.SDK_INT < 30` 门控——`AllFilesModule.probe`（"Guarded to API 30+
  — the method is absent on API 29"）、`SystemCapabilityResolver.manageAllFilesState`
  （API<30 返回 `GrantState.UNAVAILABLE`）、`SystemCapabilityResolverTest`
  （expected 值同样按 SDK 分支）。**只有本测试类漏了门控**。
- HXA-045 的 verification-matrix 行指定 API 36 模拟器验收，当时未暴露；
  之后 connected 测试自动拾取所有已连接设备，API 29 设备进入 developer 全量
  后此缺陷才显形。

## Root cause

测试代码复制了"直读平台方法"的写法但没有复制生产侧的 SDK 门控：
生产侧把方法收敛在 `probe` 抽象后面（API<30 恒 false），测试却绕过抽象
直接调平台方法。与
[2026-09-03-jvm-stdlib-calls-missing-on-api29](2026-09-03-jvm-stdlib-calls-missing-on-api29.md)
同属"API 29 缺失方法"缺陷类，但方向相反：那次是**生产代码**调了平台缺失方法
（能力缺失），这次是**测试代码**（门禁缺失，能力本身不受影响）。

## Fix and invariants

三处直调全部加 SDK 分支，且 API 29 分支**断言真实的结构性契约**（不是跳过）：

- `scopeResolutionAgreesWithTheLiveSystemGrant`：API<30 → `resolveScopeRoot`
  必须返回 null（grant 在平台层面不存在 → probe 恒 false → 与未授权同路
  fail-closed）。
- `anEnabledRootResolvesAndStaysContainedWithinTheGrant`：
  `Assume.assumeTrue` 条件前置 `Build.VERSION.SDK_INT >= 30 &&`——happy path
  本来就需要 live grant（假设跳过是既定的测试设计，注释已说明 fail-closed 分支
  由其余测试无条件证明）。
- `consentScreenShowsLiveStateAndTheSettingsJumpWhenDenied`：API<30 断言
  屏幕显示 `系统状态：此系统/版本不提供（API 低于 30）`（生产
  `GrantState.UNAVAILABLE` 的既有文案），且 **不提供**系统设置跳转
  （一个平台不存在的权限没有可跳的设置页）——与生产
  `AllFilesModule` 的 state 映射逐字核对。

不变式：**developer 设备矩阵在任何 minSdk 及以上的设备上都必须可全绿；
平台缺失的能力在低 API 设备上断言其结构性契约（UNAVAILABLE / fail-closed），
而不是跳过或直调不存在的方法**。

## Alternatives considered

- 用 `Assume` 把 API 29 整类跳过：违背"所有系统权限必须测 unavailable/denied/
  granted/revoked"的项目测试纪律——API 29 的 unavailable 分支正是本缺陷的
  正确覆盖面，跳过等于放弃。
- 给测试加 `@TargetApi`/lint 抑制：只消除告警，不修行为。
- 把矩阵行限定回"developer 套件只跑 API 36"：把门禁问题转嫁给运行环境，
  且 connected 测试拾取全部已连接设备的现状下无法机械保证。

## Regression verification

- API 29 设备上 `AllFilesDeviceTest` 6/6 通过（3 例 API<30 分支断言真实
  UNAVAILABLE 契约，1 例 happy path 按设计 Assume 跳过，2 例本就不依赖 grant）。
- API 36 设备上 `AllFilesDeviceTest` 6 例行为不变（grant 由运行环境 appops 授予，
  走原分支）。
- 完整双设备 developer 全量结果见 status.md 设备矩阵条目。

## Residual risk

- 未来在 androidTest/developer 测试中直调任何平台方法时，必须带与生产侧相同的
  SDK 门控；建议沿用"生产 probe 抽象 + 测试走同一抽象"的写法，杜绝绕过。

## Related records

- [2026-09-03-jvm-stdlib-calls-missing-on-api29](2026-09-03-jvm-stdlib-calls-missing-on-api29.md)
  （同缺陷类的生产代码实例）
- [2026-09-03-approval-device-tests-session-scoped-probe](2026-09-03-approval-device-tests-session-scoped-probe.md)
  （同一批设备套件收敛工作）
