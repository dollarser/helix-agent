# Bug Fix: Mobile Use 无障碍未就绪仍显示开启

Status: fixed
Date: 2026-10-04
Related HXA: HXA-244
Affected modules: app, tools/automation, extensions/mobile-use

## Problem

所有者进一步明确无需权限全部打开，有部分功能即可启用。之前将无障碍连接作为统一开启门槛，阻止了不需要屏幕访问的应用查询。

## Impact

许可与具体操作可用性混在一起，缺少一个系统条件就关闭整个插件。

## Root cause

`ui.apps` 不必要地通过无障碍运行态读取 PackageManager；插件 scope 和开启入口统一要求服务连接。

## Fix and invariants

- 应用查询使用应用 Context，只返回原会话允许且可启动的应用；读取前后复核原调用身份、精确授权范围、取消和截止时间。
- 插件按工具判断可用 scope。屏幕操作保持原有无障碍、窗口、物理资源及审核路径；Root/Shizuku 的后续独立精确点击见[独立点击记录](2026-10-04-mobile-use-independent-click.md)。
- 无障碍未连接允许保存范围并显示“已开启 · 仅应用查询”，提示开启无障碍解锁屏幕操作，不强制跳转；服务恢复不重放动作。
- 仅部分可用、未授权、完全不可用与保存失败分别处理。现有 Android 应用查询无需额外系统授权，但仍需用户会话许可。

## Alternatives considered

直接将 Root/Shizuku READY 当成可独立操作会夸大当前实现；直接放开所有工具则把不可用留到执行时才暴露。采用按工具提供 scope，并保留执行层再次检查。

## Regression verification

本次主机联合检查通过（41 秒）：automation 128 项与 mobile-use 10 项 JVM 测试，0 失败/错误/跳过；双渠道 Debug APK、Developer 设备测试 Kotlin 编译、spotlessCheck、detekt 通过。单元测试覆盖部分可用、无可用操作、无会话许可、服务断开后的工具差异和保存失败。设备回归更新为无障碍撤销后仅查询仍可用；设备 not requested，编译不等于执行。

```bash
./gradlew :tools:automation:testDebugUnitTest :extensions:mobile-use:testDebugUnitTest \
  :app:assembleDeveloperDebug :app:assembleConsumerDebug \
  :app:compileDeveloperDebugAndroidTestKotlin spotlessCheck detekt --console=plain
bash scripts/check-docs.sh
git diff --check
```

当前验证日志：ignored `build/mobile-use-partial-host.log`。保留其他未提交工作。

## Residual risk

未运行模拟器/真机，未验证 OEM 无障碍设置页或服务重连时序；未安装、提交、推送或发布。选择的应用范围是授权意图，并不表示当前有物理执行条件。

## Related records

- [会话授权决定](../adr/permissions/001-session-authorization.md)
- [HXA-244](../development/tasks/HXA-244.md)
- [当前实施状态](../development/status.md)
