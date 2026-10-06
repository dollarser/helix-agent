# 会话选模与思考强度按需检测

日期：2026-10-06。所有者要求当前模型旁显示思考强度，未检测时可点击检测；随后明确授权模拟器及当前真实模型测试。

## 修改

- 思考强度放在选模列表的当前模型旁。新模型切换尚未提交时不显示旧模型的选项，按 provider/model 隔离临时检测状态。
- 没有可用选项时显示“思考强度 · 检测”，点击才发起检测。优先刷新服务端模型元数据；明确声明的档位优先，明确空列表不假装支持。无声明时复用既有精确模型能力探测和发布身份校验。
- 检测中阻止重复点击；失败、未确认支持与可用选项分别显示。失败可重新点击重试，不修改已有会话模型、消息或默认设置。打开模型选择页本身不调用真实服务。
- 元数据没有列出档位时，既有通用 reasoning 能力合同仍提供 OFF/LOW/MEDIUM/HIGH；这是兼容参数选项，不是逐档效力证明。

## 模拟器与真实服务结果

设备 emulator-5554 / API36 arm64 / Developer，当前模型 Qwen3.8-27B，真实 API 使用前获得本轮授权。

- `ReasoningDetectionDeviceTest` 与 `ProviderModelPickerDeviceTest`：8/8，通过按点击检测、结果选择、失败无虚构档位、延迟选模隔离等组件交互；使用合成 Provider 数据，不冒充真实接口验证。
- `ReasoningRealProviderDeviceTest#detectFromPickerUsingConfiguredModel`：真实 ProviderService 检测，通过生产 UI 回调展开 OFF/LOW/MEDIUM/HIGH 并选中 HIGH，约 7.4 秒（instrumentation 总时长）。检测不是只查连接，可能包含多次能力请求并消耗额度。
- `ReasoningRealProviderDeviceTest#configuredModelAcceptsHighReasoning`：一次额外真实生成，经过当前 Provider 的 HIGH 解析，正确回答 17×19=323，约 4.1 秒。现有 ChatCompletionsRequestEncoder 对 Qwen3.8 的 HIGH 映射为线上的 xhigh；不宣称已验证所有档位的推理效果差异。
- 主机 9 项 SupportedReasoning/ProviderProbePublication/ProviderModelEvidence 测试通过，Developer APK 与 AndroidTest APK 构建、detekt、spotlessCheck 通过。新增 AndroidTest 已实际运行，不只编译。
- 最新 APK 已覆盖安装，并打开真实会话选模页，确认当前模型右侧入口可展开默认/低/中/高且均可点击；原会话未改选模型或思考强度；能力检测结果按正常产品逻辑持久保存。日志及设备截图在忽略目录 `build/reasoning-picker-device/`。

## 两项相关调查

Mobile Use 当前悬浮窗由 HelixAccessibilityService 创建 TYPE_ACCESSIBILITY_OVERLAY；高权限路径的 liveDeviceGrant 不负责绑定该悬浮窗。因此仅有 Root/Shizuku 时缺少悬浮窗是当前呈现层的耦合，不代表执行能力不可用，本轮未改变其生命周期。

此前安装任务的最后请求已注入截图工具；48k 左右输入与最终短文本不能证明上下文窗口耗尽。提示词和历史工具反馈可能影响模型，但没有同上下文对照实验可确认因果。已有内置技能明确最终文本会结束轮次，需要截图时应实际调用工具；不根据“截图查看”等关键词强制续跑。
