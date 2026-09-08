# Bug Fix: main 合并后的隔离进程、图片缩放和 WebView 恢复回归

Status: fixed
Date: 2026-09-05
Related HXA: HXA-053, HXA-055, HXA-069, HXA-103
Affected modules: app, feature:files, feature:browser, runtime:quickjs

## Problem

main 各工作线单独验证后，应用端 `code.javascript.run` 仍返回 `BIND_FAILED`；
图片在首次编码超预算后直接返回失败，未尝试缩小；WebView 没有处理 renderer 退出。
库级 Lint 未进入共同 CI，API 29 不兼容调用和生命周期问题未被此前应用 Lint 覆盖。

## Impact

QuickJS 库级测试通过不能证明主 APK 的隔离服务能够启动。真实应用的四项 JavaScript
审批/执行/异常/串行化测试失败。合法的大图片无法按既有像素/字节限制归一化；
renderer 退出无法成为可重试的 tab 错误。

## Root cause

`HelixApplication` 在 Android 创建 isolated UID 进程时仍访问语言 SharedPreferences，
安装访问主应用存储的诊断器，并准备主应用恢复逻辑。隔离进程没有该存储与系统服务能力，
在 Service 创建前即崩溃。后加入的语言/诊断初始化与已有 QuickJS 跨模块冲突。

图片缩放循环从 `scale=1` 开始，首次编码超预算后计算出相同尺寸并立即退出；
quality ladder 不适用于 PNG 的质量参数，随机高熵 PNG 可以稳定暴露此缺陷。

WebView client 未实现 `onRenderProcessGone`；destroy 路径还创建了无 handler 的替代 client。
另外 WebKit 1.17 Lint 对自定义 client 的 super constructor 无条件报告该问题，
即使覆写 handler 仍报告，需区分实际缺陷与检测器假阳性。

## Fix and invariants

- 在 Application 的 `attachBaseContext` 与 `onCreate` 两处均检查 `Process.isIsolated()`；
  isolated UID 仅执行 Android 基础初始化，不能进入主应用偏好、诊断或恢复。API 自 28 可用。
- 图片每轮以原图尺寸与有界比例计算目标，逐步缩小；保留原有最小边长与质量/字节上限，
  正常、提前返回和异常路径都回收中间 bitmap。使用固定 AndroidX ExifInterface 读取 EXIF，
  重编码仍剥离元数据；API 29 使用受支持的 WEBP 常量。
- renderer 退出先释放 host/view、失效 evaluation 与 deadline、清除截图，再发布可重试错误。
  Retry 必须创建新 WebView。只在已有实际 handler 的 client 类局部抑制检测器假阳性。
- CI 增加所有 Android 库 `lintDebug`；consumer/developer 的 Debug/Release Lint 保持启用。

## Alternatives considered

不通过禁用应用初始化错误、扩大隔离进程权限、主进程执行脚本或放宽测试结果掩盖失败。
不扩大图片上限来规避失败；不以全局 Lint baseline 隐藏 WebView 问题。
冷绑定耗时不等于 in-flight 执行：取消测试观察真实 EXECUTE Binder 事务后触发取消，
仍断言 INTERRUPTED；deadline 测试继续覆盖 TIMEOUT、上界、回收与后续执行。

## Regression verification

`CodeJavascriptRunDeviceTest` 的四项失败在修复后 consumer/API 36、developer/API 29
均通过；最终完整矩阵见 [本轮验证报告](../development/main-merged-verification.md)。
`ImageNormalizerDeviceTest.oversizedEncodedPngUsesTheDownscaleLadder` 使用固定随机图片，
先证明旧代码 BUDGET_EXCEEDED，再在 API 29/36 证明缩图后成功；另两项覆盖 WebP 与 EXIF。
`BrowserSecurityDeviceTest` 在 API 29/36 实际终止 renderer 并验证 retry 使用新 view。
所有命令和完整复测统计集中于同一报告，不用构建结果代替功能验收。

## Residual risk

物理设备、OEM WebView/内存压力和长稳仍需独立验收。模拟器上的真实 isolated UID
与 renderer 测试不等同于 rooted 物理设备或商店发布验收。

## Related records

- [HXA-053](../completion-records/HXA-053.md)
- [HXA-055](../completion-records/HXA-055.md)
- [HXA-103](../completion-records/HXA-103.md)
- [main 合并后全量验证](../development/main-merged-verification.md)
