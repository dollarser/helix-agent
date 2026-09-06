# HXA-146 Copilot Provider 进展

日期：2026-09-06。以下保留实施过程与失败证据；最终已收口，见 [HXA-146 完成记录](../completion-records/HXA-146.md)，本文件不是当前状态源。

## 范围与事实

依照 accepted ADR-0025/0026/0027，加入 developer 受管理 Copilot Provider、固定登录入口、Runtime Chat Completions 文本 adapter、短期 token 预刷新。复用现有 Chat encoder/decoder、共享 HTTP 限额与错误、v2 PFD 路由和 job journal。没有新增外部依赖或复制第三方代码；只增加既有内部 `provider:openai-chat` 模块依赖。consumer 不包含订阅，工具和图片仍关闭。

参考插件 `dsh-plugin-subscriptions@0.7.0`（MIT）仅作协议证据。它支持动态模型目录、Chat/Responses 按模型选择及动态 editor version；本次只接 Chat Completions 固定端点，尚未声称任意 Copilot 模型可用。保留既有 Device Flow identity 和固定 editor headers；旧 editor identity 可能被服务拒绝，不动态换身份或自动重发模型 POST。

2026-09-06 访问：

- [GitHub 套餐](https://docs.github.com/en/copilot/get-started/plans)：当前 Free 有有限 AI credits，模型仅 auto selection，不能沿用旧固定模型免费或每月 50 次的假设。
- [GitHub SDK 认证](https://docs.github.com/en/copilot/how-tos/copilot-sdk/auth/authenticate)：仅作认证背景，不重新引入已停止的 Android CLI 路线。
- [参考插件](https://github.com/V1ki/dsh-plugin-subscriptions)：本地当前 `lib/providers/copilot.js` 记录固定 internal token exchange 与 `/chat/completions`。不是供应商对 Helix 的支持保证。

配置默认 `auto` 是待真实服务验证的 wire 候选，不把官方 UI 自动选模说明当成此非公开端点接受 `model=auto` 的证据。若服务拒绝，必须先核对实际目录/错误后修正，不能宣称免费用户不可用。

## 当前验收

- 登录后追加实测：最初 Runtime 尚在等待设备授权，probe 返回 AUTH；之后 UI 确认登录及 entitlement 成功，再执行同一 opt-in 命令返回 HTTP_ERROR（非 AUTH）。真实对话仍未通过，不能推断为额度不足或风控，需继续定位服务端拒绝原因。
- 修复 Copilot 登录页遗漏的设备码/URL 复制按钮，复用 `DeviceCodeClipboard` 敏感剪贴板标记；状态文字支持选择。仅当前授权有效时可复制，结束后清空并禁用；不为验证按钮而删除已成功登录的凭据。Runtime JVM/debug 构建通过（71 tasks），三语言资源 parity 与 diff 检查通过。

- roadmap 指定 JVM、双变体 debug 构建、developer lint：通过（696 tasks）。
- `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleRelease -Phelix.cli.r8=true --no-configuration-cache --no-daemon --max-workers=2`：通过（130 tasks）；只声称 Runtime R8，不声称主 App release 混淆。
- Copilot 专项 JVM 覆盖预算、平台凭据隔离、预刷新、401/403/429、断流、不重放；刷新器覆盖交换成功与失败保留原凭据。
- API 29/36 arm64-v8a 专属模拟器：每台 7/7，Copilot 普通 Provider/账号入口/运行中取消与进程死亡/PFD 对账删除，及 Codex/Claude/Grok 普通 Provider 回归。均为 debug fixture，不消费账号额度。
- `check-lockfiles.sh`（34 files）、`check-secrets.sh`、`check-docs.sh`（193 Markdown/130 HXA）、`verify-adr.sh`（27）、`check-i18n.sh`、`check-cli-runtime-boundary.sh` 与 `git diff --check` 全部通过。
- 真实免费账号调用尚未验收。真实测试只通过普通 Provider/ChatService，不读取或导出 vault。专属 API 36 AVD 改以可见窗口启动，`hw.keyboard=yes` 与 `-no-snapshot` 冷启动，避免旧快照恢复导致键盘输入失效；未操作其他任务模拟器。

真实 smoke 命令（需用户在同一专属模拟器登录，默认测试不会消费额度）：

```sh
adb -s <专属serial> shell am instrument -w -r -e realSubscription true -e realProvider copilot -e class com.helix.app.provider.CodexSubscriptionProviderRealAccountDeviceTest com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner
```
