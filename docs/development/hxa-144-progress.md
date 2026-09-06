# HXA-144 实施过程记录

2026-09-06。以下保留实施过程中的初次验证、失败与缺口；随后所有者授权同步 diagnostics 修复，余下验收已补齐，最终状态和产物以 [HXA-144 完成记录](../completion-records/HXA-144.md) 为准。

## 实现

Claude 注册为 developer 受管理 Provider，通过普通 ProviderService/ChatService 选择；固定账号入口指向 ClaudeLoginActivity。v1 请求仍为 Codex，v2 明确平台且 hash 覆盖平台字段；未知或未实现平台不回退 Codex。Claude 凭据只在 Runtime 读取，发送前过期刷新，不自动重放模型 POST。复用 `provider:anthropic` encoder/decoder 与共享有界响应读取，保留原 Job journal、取消/恢复和 PFD 对账。

设备首轮发现配置 endpoint 不允许 query，已将主 App endpoint 修正为 `https://api.anthropic.com/v1`；`?beta=true` 仅由 Runtime 固定网络请求使用。

## 外部证据

访问日期均为 2026-09-06：

- [Anthropic 官方 streaming](https://platform.claude.com/docs/en/build-with-claude/streaming)：Messages SSE 事件形态；不能据此证明消费订阅 OAuth 可用。
- [Claude Code setup](https://code.claude.com/docs/en/setup)：认证/订阅背景，不作为 Android CLI 打包可行证据。
- [参考插件](https://github.com/V1ki/dsh-plugin-subscriptions)、[Claude adapter](https://github.com/V1ki/dsh-plugin-subscriptions/blob/main/src/providers/claude.ts)：本地包元数据 0.7.0/MIT，当前 `lib/providers/claude.js` 使用 Messages beta URL、Bearer 和 OAuth beta；`lib/index.js` catalog 包含 `claude-sonnet-5`，本次作为待真实验证默认模型。

未复制插件代码或导入其凭据。Helix 采用现有自主 Anthropic 编解码；不添加插件的 CLI User-Agent 伪装、凭据导入或额外平台能力。服务是否接受该最小请求头组合仍需真实付费账号验证，不能声称与插件实测等价。官方 Messages 主 API 页读取因响应过大失败，流式文档读取成功。

## 实际命令与结果

- `./gradlew :runtime:cli-client:test :runtime:cli-app:testDebugUnitTest :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebug :app:assembleDeveloperDebug :runtime:cli-app:assembleDebug :app:assembleDeveloperDebugAndroidTest --no-configuration-cache --no-daemon --max-workers=2`：exit 0；最新运行 440 tasks，13 executed、427 up-to-date。codec 6/6、payload job 5/5、Claude model 3/3。包含不同平台同模型隔离、同 jobId 不同平台拒绝、缺失凭据、401/403/429/503、截断流和共享取消/恢复测试。
- 上述任务追加 `:app:lintDeveloperDebug`：exit 1，3 个 `UseKtx`，全部在 main 合入的 `ProcessDiagnostics.kt` 第 41/51/69 行。主工作树对该文件有尚未提交的并行修复（包括 crash 持久化语义），本 HXA 未修改或导入该文件。
- `./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :runtime:cli-app:assembleRelease :app:assembleConsumerRelease :app:assembleDeveloperRelease --no-configuration-cache --no-daemon --max-workers=2`：exit 0（默认模型最终从候选 4-6 改为参考 catalog 的 Sonnet 5 前运行）。App release 配置 `isMinifyEnabled=false`，这不是 R8 通过证据；最终源码 release 与专项 R8 尚待收口。
- `./scripts/check-cli-runtime-boundary.sh`、`./scripts/check-lockfiles.sh`、`./scripts/check-secrets.sh`：exit 0。边界脚本仅为已实现 Claude 解除 endpoint 禁令，Grok/Copilot 模型 endpoint 仍拒绝；consumer DEX 排除检查通过。

独占设备为 `emulator-5602`（API 29 arm64-v8a）、`emulator-5604`（API 36 arm64-v8a），对应 M11 Test AVD，使用 `-no-snapshot -no-audio -no-window` 启动；没有触碰 M9 的 5590，也没有消费真实账号额度。

两设备安装当前 Runtime、developer App 和测试 APK 后均执行：

```sh
adb -s <dedicated-serial> shell am instrument -w -r -e class 'com.helix.app.provider.CodexSubscriptionProviderE2eDeviceTest#developerProviderUsesTheNormalModelContract,com.helix.app.provider.CodexSubscriptionProviderE2eDeviceTest#claudeProviderUsesTheNormalModelContract,com.helix.app.provider.CliRuntimeHandshakeE2eDeviceTest#claudePayloadSurvivesDisconnectAndIsDeletedAfterReconcile,com.helix.app.provider.CliRuntimeHandshakeE2eDeviceTest#modelPayloadUsesPfdAndIsDeletedAfterReconcile' com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner
```

API 29 `OK (4 tests)` / 2.472 秒；API 36 `OK (4 tests)` / 3.151 秒。设备 fixture 验证路由/对话持久化链路，不发真实 Claude 网络请求；HTTP 协议由 JVM interceptor fixture 单独验证。Binder kill 发生在终态对账后，不冒充 RUNNING 时 kill 矩阵。两设备 `dumpsys activity services com.helix.runtime.cli` 均为 `(nothing)`。

最终 Debug artifact SHA-256：

- developer App：`0fe5d42532162ed2f0554d1391035bec22b1c667d67c7eb26ae6cef166b148ac`
- Runtime：`154d51ab2cb2e2de34d378c7ecb25455e67686597d563641dc2b517707d94642`

## 剩余验收

解决或同步共享 diagnostics lint 修复；旧 Runtime/new App 混合版本设备拒绝；运行中断连/取消与账号入口专项设备覆盖；最新源码专项 R8/license 审计与完成记录。Claude 真实付费模型调用按所有者决定保持未核实，不能因此伪造外部成功。Grok/Copilot Provider 尚未实施。
