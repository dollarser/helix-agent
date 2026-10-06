# Mobile Use 独立悬浮窗

日期：2026-10-06。范围：所有者要求悬浮窗不依赖 Helix 无障碍服务。本记录只覆盖本次变更，不复用此前设备/真实模型结果。

## 实现

- Helix 集中授权页增加“悬浮窗”，用户主动进入 Android 设置授权。只在声明该权限的 Developer 渠道显示，Consumer 不增加此权限。
- 宿主提供窗口 Context：优先普通悬浮窗，未授权时回退已连接的无障碍服务；两者均不可用时不显示窗口，不因此禁用后端操作。
- Mobile Use 共用一份任务显示状态，所有后端工具调用绑定原会话/轮次；停止与返回仍交给宿主处理原任务。无障碍服务断开不再关闭共享呈现对象。
- 执行前重查当前授权，移除窗口并等待帧边界，隐藏无法确认则返回无副作用失败。取消、异常均释放隐藏租约；物理输入仍占用时不恢复窗口。普通状态窗口透明度控制在触摸遮挡限制以下。
- 沿用宿主现有任务生命周期，不新增常驻前台服务。Root/Shizuku 权限与悬浮窗权限分别管理。

决策见 [权限 ADR](../../adr/permissions/001-session-authorization.md) 中同日 Decision history。

## 主机验证

- `./gradlew detekt spotlessCheck :extensions:mobile-use:testDebugUnitTest :app:assembleConsumerDebug :app:assembleDeveloperDebug :extensions:mobile-use:assembleDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`：通过。
- `./gradlew :app:lintConsumerDebug :app:lintDeveloperDebug :extensions:mobile-use:lintDebug`：通过。
- `bash scripts/check-all.sh --source`：通过（文档、ADR、国际化、源码规则与密钥检查）；`git diff --check`：通过。
- Mobile Use JVM：210 tests，0 failures/errors/skipped；新增 4 项覆盖原身份绑定、接管取消、缺少/撤销授权、隐藏失败和异常释放。
- 设备测试新增普通窗口无无障碍显示、操作前隐藏/恢复、权限撤销和无窗口继续操作；测试 APK 已编译。

## 验证边界

本任务设备验证与真实模型均为 **not requested**；未安装新版、未执行新增设备场景，不宣称任何 API/OEM 已通过独立悬浮窗验收。后续设备回归需检查普通窗口与无障碍回退、授权撤销、系统弹窗、截图无窗口残影、停止原任务及异步操作结束后恢复。系统或目标应用主动隐藏普通悬浮窗时，以通知和会话控制为备用入口，不保证所有页面都能显示窗口。
