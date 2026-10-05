# Provider 入口与订阅交互优化（2026-10-05）

## 范围与实现

所有者要求合并 SGLang/vLLM/Ollama 设置，安装到模拟器，并精简订阅首页及区分 Codex 登录方式。

- Helix 中三者都是配置兼容协议、API 地址、可选 API Key 的网络来源。将它们与通用兼容模板合为首位“自定义服务”，保留 DeepSeek、OpenAI 等厂商预填模板。没有新增 Ollama 原生协议，没有改动已有来源的地址、调用协议和凭据。
- 订阅首页共用一次非官方提醒；移除每行重复的运行位置、成功/未测试解释及就绪说明，保留账号状态、失败信息和当前下一步。
- Codex 将浏览器登录与验证码登录分区，解释前者适合本机、后者适合回调不可用或跨设备授权。验证码操作仅在生成后出现；已登录时隐藏登录入口；客户端版本归入默认收起的高级设置。
- 点击“复制验证码并打开验证页”先复制当前验证码，再启动外部浏览器。继续复用敏感剪贴板标记和原来的取消/过期逻辑，不向 URL 拼接未经确认的参数。

## 订阅方案判断

应统一账号状态、登录入口、模型管理、错误和下一步提示；不应把订阅统一成 API 地址与 Key 表单。各服务的 OAuth、Device Flow、资格检查与令牌刷新仍需独立适配。API Key 型套餐继续属于 API 来源。

[OpenAI 官方认证说明](https://learn.chatgpt.com/docs/auth)介绍浏览器回调与设备代码登录：设备代码流程需要先开启相应账号权限，然后在验证页输入一次性代码。本轮未确认稳定的自动填码 URL 契约，因此实现“一键复制并打开”，不承诺网页自动填入。不以登录成功外推所选模型和工具能力已验证。

后续应按真实账号问题优先改善过期重登、网络错误恢复和选模入口；本轮不改变服务身份、OAuth scope 或额度/资格规则。

## 验证与边界

- 主机：`build/provider-unified-complete.log`，`:app:testDeveloperDebugUnitTest`、`:runtime:cli-app:testDebugUnitTest`、双渠道 APK、Developer AndroidTest APK、detekt、spotlessCheck 通过。初次静态检查发现排版与 UI 状态方法复杂度，修正后重新通过。
- 设备：所有者指定安装的日常 `Helix_API_36` / `emulator-5554` / API36 / Developer。没有启动新实例，也未更新另一台测试实例。
- 安装前备份数据库和偏好到忽略目录 `build/provider-unified-device/before-config.tar`，覆盖安装保留现有数据，不卸载应用。包 `com.helix.agent.developer` 的设备 SHA-256 与构建 APK 一致：`77dafdbfd4b089285839e3dcbbc12a6d4ca55459703275f21ae647fe86d08c4b`。
- 表单设备回归：`ProviderSettingsFormDeviceTest` 5/5 通过，覆盖合并入口、必填项提示、HTTP 提示、协议切换、可选 Key 和模型多选。初次测试命令使用了错误 runner，改用仓库的 `HelixAndroidJUnitRunner`；首次执行 4/5，一条旧测试缺少“添加模型”操作，补齐前置操作后重跑 5/5，没有删除原断言。日志 `build/provider-unified-device/form-tests-final.log`。
- 手动核对订阅首页、Codex 登录方法说明、条件按钮与高级设置。核查期间观察到设备验证码，已返回退出流程，未继续账号授权；不能将本轮记录成全程纯离线。未完成真实登录、浏览器粘贴或模型生成验收；未消耗模型生成额度。
- 未提交、未推送，不代表其他 API/OEM、真实账号或发布验证通过。
