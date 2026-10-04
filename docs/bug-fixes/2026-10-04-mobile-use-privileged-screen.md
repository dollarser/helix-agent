# Bug Fix: Mobile Use 屏幕工具与无障碍服务耦合

Status: fixed
Date: 2026-10-04
Related HXA: HXA-244
Affected modules: core/model, core/policy, tools/automation, extensions/mobile-use, app

## Problem

所有者授权按独立后端建议优化：Root/Shizuku 不应为观察屏幕、截图和手势再强制要求开启 Helix 无障碍服务。

## Impact

原屏幕工具和 frame 绑定 service lease；此外 Dispatcher 的统一 ACCESSIBILITY_AUTOMATION 能力门槛会提前拦截无服务时的应用查询及高权限路径。只替换执行接口不能完成解耦。

## Root cause

平台无障碍授权、插件可用性、原会话范围和物理资源被视作同一个前提；高权限协议此前只支持精确点击，未提供观察/截图/手势的数据和取消合同。

## Fix and invariants

- 三项屏幕工具使用既有 Root/Shizuku 服务，按 Root → Shizuku → 无障碍选择可用后端；frame 绑定原会话、scopeRef、目标、界面树版本和后端，不因失败自动重放。
- 新 MOBILE_USE 入口只代表插件已启用并发布工具；真实授权、锁屏、取消、超时与范围继续逐次校验。语义节点、启动应用和系统动作仍使用无障碍能力。
- 整屏高权限截图只用于全手机且无排除项的授权；单应用范围使用 API34+ 无障碍窗口截图，否则报告不可用。不把裁剪误当隔离，不擅自扩宽授权。
- 图片有像素/字节预算，经只读共享内存及正常图像发布链交付，不使用临时图片文件。读取及发布前保留授权来源校验。
- 有界单指/多指轨迹生成正确的 DOWN/MOVE/POINTER_UP/UP 序列；取消和注入失败尝试清除残留触摸，结果不明仍为 UNKNOWN。共享物理票据在执行器退出后释放。
- 观察版本检测目标、旋转及界面树变化，不声称检测纯像素动画；默认显示之外拒绝。私有平台桥仅在 shell/root UID 下连接。

## Alternatives considered

全局取消无障碍检查会影响仍依赖它的语义节点工具，因此增加独立软件能力入口并保留逐操作系统检查。整屏图片裁剪无法证明不存在透明窗口/其他应用像素，因此不作为单应用截图方案。Binder 内联图片会触及消息大小限制，临时文件增加失败后的数据残留，采用只读共享内存。

## Regression verification

本次主机门禁通过：793 项 JVM 测试（model 162、policy 198、framework 255、automation 135、mobile-use 10、app 定向 33）及 10 项 Python 合同测试均无失败或跳过。Developer/Consumer debug APK、两个 app AndroidTest APK 和 automation AndroidTest APK 编译通过；两个渠道 Lint、Spotless、Detekt、集成 APK 合同校验、文档检查与 git diff --check 通过。设备 not requested，不沿用上一轮安装授权。回归覆盖能力分离、跨会话/旧 frame、撤销期间丢弃图片、受限截图不进入整屏后端、遮挡/方向/版本变化、轨迹边界、多指顺序、取消前后及清理失败。

```bash
./gradlew :core:model:test :core:policy:test :tools:framework:test \
  :tools:automation:testDebugUnitTest :extensions:mobile-use:testDebugUnitTest \
  :app:testDeveloperDebugUnitTest --tests 'com.helix.app.automation.shizuku.*' \
  --tests 'com.helix.app.chat.DurableToolLoopProgressTest' \
  :app:assembleDeveloperDebug :app:assembleConsumerDebug \
  :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebugAndroidTest \
  :tools:automation:assembleDebugAndroidTest :app:lintDeveloperDebug :app:lintConsumerDebug \
  spotlessCheck detekt --console=plain
python3 -m unittest scripts.tests.test_mobile_use_contract
python3 scripts/verify-integrated-runtime-apks.py
bash scripts/check-docs.sh
git diff --check
```

补充 lint 揭示两处已有文件工作流问题：FileConversationActions 在 composition 直接读取 StateFlow.value，改为订阅状态；FileLibrary 的同步 commit 被 UseKtx 建议替换，但 KTX 不返回提交成功与否，故抽成窄 helper 并保留 Boolean 失败检查及有理由的局部 lint 标注。没有删除测试、建立新 lint baseline 或清理其他工作树改动。高权限桥已有反射按 shell/root-only 边界作局部 PrivateApi 标注，并增加 UID 守卫；不能将静态通过当作系统版本兼容性证明。

原始日志在 ignored `build/mobile-use-screen/`。本轮未安装、提交、推送或发布。

## Residual risk

未运行 Root/Shizuku 实际截图、触控、旋转、遮挡、服务中断和锁屏设备矩阵；主机计划/授权测试与 APK 编译不能替代设备验收。单应用截图仍需 API34+ 无障碍窗口路径；语义节点工具、应用启动及系统动作没有迁移。系统保护画面和纯像素变化仍受平台限制。

## Related records

- [会话授权决定](../adr/permissions/001-session-authorization.md)
- [Root 服务决定](../adr/runtime/004-root-service.md)
- [平台能力](../architecture/android-platform-capabilities.md)
- [独立点击阶段记录](2026-10-04-mobile-use-independent-click.md)
- [当前实施状态](../development/status.md)
