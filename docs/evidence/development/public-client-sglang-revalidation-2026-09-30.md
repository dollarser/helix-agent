# Antigravity 公开客户端恢复与 SGLang 复验

## 授权与范围

所有者明确接受恢复 Developer 实验性 Antigravity 的上游公开客户端参数、保留自定义覆盖，并仅对固定参数申请 GitHub 误报放行；另授权 API36 与本机 SGLang 真实服务测试。不使用真实订阅账号，不将客户端参数视为用户访问令牌，也不声称 Google 已授权 Helix 使用该身份。

## 实现

- Runtime 仍只进入 Developer 包，默认参数来自已核对的 `V1ki/dsh-plugin-subscriptions`；环境变量必须成对提供，同时空值可禁用。PKCE、state、loopback 和私有进程不变。
- 本地扫描仅豁免 `runtime/cli-app/build.gradle.kts` 中两个指定变量声明的精确 SHA-256 匹配；其他路径、变量、参数变化或其他凭据继续拒绝。全工作树和暂存区共用该规则，错误 fail closed，不输出命中内容。不是关闭 Google 格式检测。
- 原缺配置及零网络请求测试保留，设备夹具调整为默认配置可用但不自动登录。
- 方案与限制记入[Provider ADR](../../adr/provider/002-subscription-adapters.md)，先前清理历史和显式配置的验收仍属当时版本，不改写其结果。

## 验证

- 本机 `/v1/models` 已响应：`Qwen3.8-27B`，`max_model_len=262144`。
- Runtime JVM 171/171；Developer 单元测试 1083 项、0 failure/error、4 项既有 skip。Developer APK/AndroidTest APK、lint、Spotless、detekt 通过，见 `build/public-client-compaction-host.log`。
- 扫描门禁回归 10/10；精确例外反例 1/1（两个参数分别验证原声明、其他路径、修改值和改名）。
- API36 arm64、4096 MiB、4 核独占模拟器：`build/public-client-sglang-api36-r1`；使用 ADB reverse 30008，r1 为 2 PASS / 1 FAIL：登录页与 Provider UI smoke 通过；上下文上限、首次摘要及后续问答通过，但重复压缩在 4287 input / 4096 output tokens 触发 OUTPUT_TOKEN_LIMIT。未将截断标记成功；提高有界输出余量并保持用户预算与摘要正文目标后进行 r2。

- r2 重复压缩已完成（4136 input / 4056 output），但后续模型回答 `UNKNOWN|NO|PENDING`，未通过原始约束断言。r3 仅增加合成摘要取证，不改变正确性标准，完整用例 1/1 PASS，70.752 秒；首次压缩及两轮再压缩、后续三项约束问答全部通过。两轮耗时 19.631 / 27.198 秒，末轮用量 4462 input / 5062 output。证据 `build/public-client-sglang-api36-r3`，APK 哈希和设备属性在各轮目录中；模拟器已自动关闭。
- r2 的模型偶发 `UNKNOWN` 未获得确定根因，不能用 r3 通过宣称根治。保持该可靠性边界，不放宽断言、不把模型回答作为执行事实。

## 远端

恢复提交 `35af47c0` 首推仅命中 Google OAuth Client ID / Client Secret。所有者授权下通过官方 API 对两个实际 placeholder 设置 `false_positive`，两次均成功；未禁用仓库或账号的推送保护。后续正常推送回执见 `build/public-client-push-r2.log`。真实 Antigravity 账号登录、刷新和生成未请求/未执行。本次定向 SGLang 复验不替代固定 15-case 正式 P5。

## 来源

- [上游公开客户端配置](https://github.com/V1ki/dsh-plugin-subscriptions/blob/main/src/providers/antigravity-oauth-client.ts)：两参数与本地恢复值逐项比对一致，不运行上游代码。
- [GitHub 官方针对性放行 API](https://docs.github.com/en/rest/secret-scanning/secret-scanning#create-a-push-protection-bypass)：使用实际命中的 placeholder 与明确原因，不关闭保护。
