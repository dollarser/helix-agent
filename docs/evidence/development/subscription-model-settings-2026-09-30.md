# 订阅设置与会话模型收敛

范围：所有者要求统一所有订阅的账号/模型入口，扩大模型勾选热区、去掉重复选择控件；随后明确取消默认模型，新对话继承当前会话模型，没有则为空。本轮明确授权 API36 定向验证，不新增账号生成测试。

## 实现

- 所有 MANAGED_ACCOUNT 共用管理登录 → 管理对话模型 → 检查账号连接。账号页不再重复上下文/能力操作，也不把来源基础模型或其能力摘要冒充实际选中的模型。
- 模型整行及名称可勾选，只有左侧一个复选框；忙碌时不可修改。详情按基础生成、能力检测、上下文、排序、应用到会话排列。
- 删除默认模型 UI 与运行时字段。旧偏好 default 被忽略，显式模型列表保持；新偏好不写 default。新建会话继承当前 Provider/model，不按来源、目录或候选顺序挑选替代项；未绑定时保持为空。
- Antigravity 的目录解析保留实际 inputTokenLimit / maxInputTokens，公共订阅适配器按精确模型传递 contextWindow。接口没有提供已支持的窗口字段时显示未报告，并允许手动设置；不复制参考适配器的固定窗口兜底来冒充官方上限。本轮没有获得新的服务端字段证据，不宣称已探测出官方窗口。

## 验证

主机首轮格式门禁发现一处行过长，修复后 `build/subscription-layout-host-r2.log` 的 Developer 单元测试、应用/AndroidTest APK、格式化及 detekt 通过。包含旧 default 被忽略且不再写入的回归。

设备：所有者授权的 API36 arm64 可见模拟器，Developer。`build/subscription-layout-device-r1.txt`：6/6 通过，10.066s。覆盖五种订阅的共享入口顺序、模型名称勾选/取消/忙碌禁用、会话模型与推理选择、继承精确模型及无绑定保持为空。均为本地合成夹具，不代表五个真实账号生成通过。

本轮未提交、未推送，不扩大此前账号验收范围。

最终版本复验：`build/subscription-layout-device-final.txt`，6/6 通过，10.388s；`build/subscription-layout-final-host.log` 的 Consumer 单元测试、双渠道 lint、应用/AndroidTest APK 与 detekt 通过，最终文案打包和 spotlessCheck 见 `build/subscription-layout-package.log`。源码门禁 `build/subscription-layout-source.log`、最终国际化检查与 `git diff --check` 通过。已覆盖安装到可见模拟器，保留账号数据并恢复测试前的语言偏好状态。
