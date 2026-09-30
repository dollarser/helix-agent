# 工具详情默认折叠

按所有者 2026-10-01 的要求，工具行收起时只显示名称、Harness 状态和已有耗时。Intent、请求参数、结果、调用 ID、命令详情与 PRoot 诊断操作都在展开区；取消原先收起时仍显示三行结果的预览。待审批卡继续直接可见。

相关审查同时修正两处问题：不再以缺少结果文本或本地化省略号猜测“正在执行”的颜色；工具行按 Turn/Call 身份建立 Compose key，避免排序更新导致展开状态丢失，单行状态也绑定同一身份。

设备用例更新覆盖默认隐藏、展开再收起、诊断入口隐藏，以及 320/360/412dp 大字体下状态和展开入口可见。审批测试保留可操作性要求。本轮设备验证 not requested，编译设备测试不等于设备通过。主机结果以 `build/tool-collapse-host.log` 与 `build/tool-collapse-final.log` 为准；本记录仅覆盖本次工具展示变更，不宣称全产品无缺陷。

主机结果：Developer unit、lint、AndroidTest Kotlin 编译、spotlessCheck、detekt 与 `git diff --check` 通过。未提交、未推送，未安装设备。

## 后续所有者授权的设备验证

2026-10-01 所有者追加“使用模拟器验证”，在已有 `emulator-5566`、API36、Developer 构建上覆盖安装最新 app 与 AndroidTest APK，保留账号和历史。

执行 `ToolTimelineLayoutDeviceTest`、`ApprovalLayoutDeviceTest`、`ContextWindowDeviceTest`，13/13 通过，用时 18.746 秒。覆盖默认完整折叠、展开再收起、诊断入口可见性、长参数/结果展开、窄屏大字体、待审批操作，以及圆环向下取整和 0% 显示。本轮未调用真实模型或使用物理设备。

命令脚本 `scripts/debug/2026-10-01/verify-tool-collapse.sh` 通过共享 build/device slot 执行；日志 `build/tool-collapse-device.txt`，APK 编译记录 `build/tool-collapse-apks.log`。测试后恢复原先无语言覆盖的配置并重新打开应用，模拟器保留供用户测试。本段更新前文设备 not requested/未安装的历史状态为本次定向 passed，不扩展为全产品验收。

## 后续折叠标题调整

同日所有者进一步要求：折叠标题优先显示有效 intent，无 intent 回退 ToolPurpose；工具名称移入详情。状态、耗时、待审批入口仍可见，标题最多三行，完整 intent 可展开查看。已同步 320/360/412dp 大字体测试的标题/工具名可见性断言；本次仅做主机验证，未重新执行设备用例，之前设备通过记录不覆盖此次标题布局。

后续 v0.0.4 发布验证已补跑最新 intent 标题与相关交互，见 [21 项 API36 回归](v0.0.4-release-2026-10-01.md)。上文“未重新执行”仅指当时的小改轮次。
