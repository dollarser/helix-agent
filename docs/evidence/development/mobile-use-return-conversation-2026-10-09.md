# Mobile Use 返回原对话：2026-10-09

范围：[HXA-250](../../development/tasks/HXA-250.md)。用户报告任务完成后仍停留第三方 App。源码确认现有 Skill 只要求验证后结束；悬浮窗返回交互仅在用户点击时调用宿主 openConversation，并会停止自动任务，不适合提示模型点击它收尾。保留该手动接管行为。

修改 Skill 首段、详细收尾流程、Skill 摘要及 ui.launch 工具描述：验证业务结果→发现原宿主包→授权内启动→确认原对话→最终回复。尊重显式停留、前台活动和手动接管；返回未知/拒绝不影响已有业务成功事实，不猜包/对话、不扩大权限、不重放或循环导航。沿用现有工具，不声称提示就是可靠的强制导航实现。

`build/mobile-use-return-host-r3.log`：现有 Mobile Use 主机测试 216/216，零失败/跳过；全仓 detekt 与双渠道 Debug APK 构建通过。`build/mobile-use-return-format-final.log` 最终 spotlessCheck；`build/mobile-use-return-source-final-r2.log` 源码/文档/ADR/i18n/密钥门禁。首次检查及格式应用复跑因两条提示字符串超过 120 字符失败，拆分字符串修正；首次文档门禁因任务标题不符合现有模板失败，修正为三个规定章节，没有删除或跳过测试。Developer APK 的 assets 内确认含 Task-completion handoff；Consumer 按现有渠道不包含该插件资产，保持渠道契约。没有新增框架、依赖或工具合同版本。

设备验证 not requested；没有操作模拟器、使用真实模型额度、更新安装或清理设备数据。APK 打包成功不代表已安装；模型遵循率、实际正确会话返回、渠道/OEM 行为尚无本次运行证据。

## 后续授权模拟器实测

所有者随后要求“帮我用模拟器测试”。指定现有 `emulator-5554`，API36 arm64 Developer，现有 Qwen3.8-27B；只建 synthetic 会话和测试 APK 内页面，不操作账号或其他 App。先只读核查活动 Turn 为 0，Room identity 为 `c34065205d0987bb3b495a2f4a663c43`，与生成实现相同。主 APK `710694153ed29a362bb76858828201ee99f0f7cc4ca534fbf38c5014eeb93802` 经 install -r 成功，未清数据、卸载或重置；前置核查与安装见 `build/mobile-return-preinstall-inventory.txt`、`build/mobile-return-app-install.txt`。

新 `MobileUseReturnModelDeviceTest` 两项分别提交无显式返回指令的任务、完成后明确停留指令；都要求填写合成输入、滚动后只点击一次完成并核查独立 fixture 结果。返回同时验原 Activity 焦点、实际 chat-title 和原 session；测试期间不代模型导航或收尾。

失败轨迹保留：`build/mobile-return-live-r1.txt`、`r2` 两项各失败，只有只读调用，实际任务未完成；默认 UiAutomation 可抑制无障碍，即使不抑制也与 Shizuku 的 UiAutomation 连接争抢资源。日志为 UNSUPPORTED_UI/PRIVILEGED_DEVICE_FAILED/NO_ACTIVE_SESSION，不能证明返回指引失败或通过。移除测试 UiAutomation，复用 Compose 界面断言；`r3`、`r4` 又在模型提交前被重复 Activity 生命周期/同名标题节点夹具错误阻挡。修正为单一规则 Activity、限定 chat-title，没有放宽业务完成与原会话返回断言。测试编译首次因不存在 UiAutomator 依赖失败，改用项目现有能力，未增加依赖。

最终 `build/mobile-return-live-r5.txt`：2/2 通过，45.871 秒。明确停留任务为 10 次模型/9 次工具调用，目标结果 TEST_DONE:你好 Helix 且点击一次、原 Activity 无焦点；默认返回任务为 13 次模型/12 次工具调用，验证目标结果后实际 ui.launch→ui.find→ui.snapshot，原 Activity 有焦点、chat-title 含本轮唯一标题、session 身份一致。返回任务中一次 ui.scroll 为 TOKEN_UNKNOWN（旧观察 token 已失效），模型重新 snapshot 后使用新 token，未重复完成操作；保留该失败而不宣称全调用无错误。每项只有一次有效完整模型验收，不作为普遍成功率或性能收益。

最终测试编译/格式见 `build/mobile-return-device-test-build-r6.log`，detekt/格式见 `build/mobile-return-device-static-r3.log`，源码门禁见 `build/mobile-return-device-source-final.log`。测试恢复原安全 profile、插件全局配置及会话选择，归档仅本轮 synthetic 会话。安装后只读 identity 同安装前、活动 Turn=0（`build/mobile-return-postinstall-inventory.txt`）。结束后另行打开 Helix 交还日常使用（`build/mobile-return-owner-handoff.txt`），此动作发生在模型断言和 JUnit 通过之后，不计入自动返回证据。未新增或修改系统授权，未清数据、卸载、重置、推送或发布；未连接真机，其他渠道/后端/OEM、停留的其他语义及手动接管实测仍开放。
