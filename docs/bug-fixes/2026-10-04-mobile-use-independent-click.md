# Bug Fix: Root/Shizuku 精确点击依赖 Helix 无障碍服务

Status: fixed
Date: 2026-10-04
Related HXA: HXA-244
Affected modules: app, tools/automation, extensions/mobile-use

## Problem

所有者要求先安装当前最新版，再继续解除 Root/Shizuku 点击对无障碍服务的依赖。高权限进程已能观察和点击，但宿主统一要求 Accessibility 运行态，服务关闭时该能力无法使用。

## Impact

具有 Root/Shizuku 授权仍无法在关闭 Helix 无障碍后执行精确点击；按权限提供部分能力的 UI 与后端能力未对齐。

## Root cause

原会话授权、物理占用和屏幕校验都通过无障碍 service lease 获取；高权限进程没有独立的点击位置遮挡校验。

## Fix and invariants

- 精确 `packageName + viewId + text` 点击新增独立分支，无需开启 Helix AccessibilityService。连接无障碍时保留既有路径及操作呈现。
- 宿主在派发及后端回调中核查原会话、精确 scopeRef、应用范围、锁屏、取消和截止时间。底层读取界面树仍使用平台 UiAutomation，不宣称完全脱离 Android 无障碍基础设施。
- 高权限进程读取并复核唯一目标，点击前核对活动应用、旋转、窗口和其他覆盖该点击位置的窗口；缺少目标窗口或发生遮挡则拒绝。仍使用有界固定命令，未扩大任意 shell 接口。
- Root/Shizuku 与无障碍服务共享物理输入票据；服务重连不另建槽，晚到回调只能释放自己持有的票据。
- scope 和设置页按实际工具能力开放。无服务时 READY Root/Shizuku 仅额外提供精确目标点击，不解锁截图、手势、任意坐标点击或界面读取。
- 自动路由保持 Root → Shizuku → 无障碍。操作选定后失败/未知不跨后端重放；不自动申请 Root、不冷绑定、不伪造系统授权。

## Alternatives considered

删除所有宿主校验会丢失原调用身份和撤权边界。仅移动权限判断、保留服务私有输入槽，会导致断开/重连期间并行操作。采用独立授权校验、共享物理票据及高权限侧屏幕校验。

## Regression verification

主机命令：

```bash
./gradlew :tools:automation:testDebugUnitTest :extensions:mobile-use:testDebugUnitTest \
  :app:testDeveloperDebugUnitTest --tests 'com.helix.app.automation.shizuku.*' \
  :app:assembleDeveloperDebug :app:assembleConsumerDebug \
  :app:compileDeveloperDebugAndroidTestKotlin spotlessCheck detekt --console=plain
python3 -m unittest scripts.tests.test_mobile_use_contract
bash scripts/check-docs.sh
git diff --check
```

162 项定向 JVM 测试：automation 130、mobile-use 10、宿主 Shizuku/Root 点击相关 22；新增跨会话、撤销、改范围、锁屏、取消、过期和后端状态组合回归。另有 10 项 Python 渠道/权限契约检查。双渠道构建、设备测试 Kotlin 编译、spotlessCheck 和 detekt 均通过（39 秒）。完整主机门禁结果在 `build/mobile-use-independent/host.log`；修复检查过程中出现的新增字符串行长格式问题后复跑。

安装授权：当前用户明确要求安装模拟器。先在 `Helix_API_36` / `emulator-5554` / API36 覆盖安装 Developer Debug 并打开 MainActivity；安装前本地备份 databases/shared_prefs 至 ignored `build/mobile-use-independent/pre-install-data.tar`，不归档用户数据。未卸载、清空数据、变更系统权限或使用真实账号。主机检查通过后再次覆盖安装优化版本并打开 MainActivity，安装返回 Success；Developer APK SHA-256：`c394c107d0feb91e8aa344c3f9a2a5e14f3e0d9d918f71013cfcd810b605b561`。

## Residual risk

设备功能验证 not requested；安装和启动不代表独立点击通过。未运行无障碍关闭的 Root/Shizuku 点击、遮挡、锁屏及服务中断设备矩阵，不能据此宣布 OEM/Android 版本兼容性通过。当前独立分支只覆盖精确点击；其他屏幕工具仍依赖无障碍。无提交、推送或发布。

## Related records

- [会话权限决定](../adr/permissions/001-session-authorization.md)
- [Root 服务决定](../adr/runtime/004-root-service.md)
- [按功能启用修复](2026-10-04-mobile-use-enablement.md)
- [当前实施状态](../development/status.md)
