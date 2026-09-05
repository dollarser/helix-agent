# ADR-0022: GitHub Copilot SDK Android 执行底座候选

Status: proposed
Date: 2026-09-05
HXA: HXA-117
Deciders: pending
Supersedes: none
Superseded by: none

## Context

GitHub 官方 Copilot SDK 提供 signed-in user 与项目自有 GitHub OAuth App 认证，并允许有
Copilot subscription 的用户使用该 SDK。SDK 并非纯网络 client：Node package 绑定一个特定
Copilot CLI runtime。HXA-117 固定 `@github/copilot-sdk@1.0.13`，其 package metadata 绑定
`copilotCliVersion=1.0.83`，要求 Node `^20.19.0 || >=22.12.0`。

官方同版本只发布 Darwin、Windows、Linux glibc 与 Linux musl 的 x64/arm64 runtime package，
没有 Android/bionic artifact。两个 arm64 Linux runtime 分别请求
`/lib/ld-linux-aarch64.so.1` 与 `/lib/ld-musl-aarch64.so.1`；API 29 和 API 36 arm64-v8a
Android 设备均因 loader 不存在而不能直接执行。这是平台前置条件失败，不是 OAuth、账号或
网络失败。

## Decision

提议当前不把 GitHub Copilot SDK/runtime 打包进 CLI Runtime APK，也不注册 Copilot Provider。
HXA-117 只锁定 `bundled=false` 的供应链证据。不得把 Linux 构件改名为 Android 构件，或用
参考插件的固定 Copilot CLI client identity/internal token endpoint 绕过官方 SDK。

独立 PRoot/RootFS 执行可能提供 Linux loader，但它会引入新的有网 RootFS、Node/SDK/CLI
供应链、内置工具隔离、文件快照、进程恢复与分发体积边界；本 HXA 不选择该架构。若要推进，
项目所有者需单独接受覆盖这些边界的 ADR，随后再验证 ADR-0007 jobId 对账以及所有模型工具
请求回到 Dispatcher/Policy/Approval/Verification/Audit。

## Alternatives considered

1. 直接打包官方 Linux arm64 runtime：Android/bionic 缺少其 ELF interpreter，实测失败。
2. 在独立 PRoot/RootFS 中运行官方 SDK：技术上可能，但不是直接 Android 支持，且扩大运行时、
   网络和工具攻击面；留待独立架构决定。
3. 移植 `dsh-plugin-subscriptions` 的 Copilot 路线：使用非官方固定 CLI identity/internal endpoint，
   不等价于官方 SDK/OAuth App；不选。
4. 继续使用现有 API key/self-hosted Provider：不消费 Copilot subscription，但当前已受支持。

## Consequences

- SDK/OAuth App 的官方认证接口存在，但 Copilot 订阅后端仍不可用。
- OAuth 登录、模型调用、限额、取消、内置工具禁用/代理和断连按 jobId 恢复均未执行；不能把
  平台负向测试表述为这些门禁通过。
- lock 增加三个 `official-sdk`、`bundled=false` 证据项，不向 APK 加入 executable。
- ADR 保持 `proposed`；项目所有者未接受前，不把等待官方 Android artifact 或 RootFS 候选写成
  既定产品架构。

## Verification

- `./scripts/verify-copilot-sdk-android-spike.sh`：校验三个 npm tarball 的 size/SHA-256、SDK
  runtime 绑定、Node engine、libc metadata 与 ELF interpreter。
- API 29/36 arm64-v8a 分别运行同一脚本：glibc 与 musl runtime 均按预期以缺失 loader 拒绝。
- `ANDROID_SERIAL=<serial> ./gradlew :runtime:cli-app:connectedDebugAndroidTest
  --no-configuration-cache`：两台设备各 6/6，锁文件和 APK 无 executable 边界通过。
- 接受本 ADR 前仍需项目所有者选择“等待官方 Android/bionic artifact”或另立 RootFS ADR；
  当前没有正向登录或模型调用证据。

## Reconsider when

- GitHub 发布并支持 Android/bionic arm64 Copilot SDK runtime。
- SDK 提供不依赖 Copilot CLI runtime 的官方 Android transport。
- 项目所有者明确接受有网独立 RootFS 的供应链、体积、工具与恢复边界，并安排设备 Spike。

## References

- [Companion Runtime 生命周期](0007-companion-runtime-lifecycle.md)
- [第三方订阅 adapter 边界](0021-third-party-subscription-protocol-adapter.md)
- [GitHub Copilot SDK authentication](https://docs.github.com/en/copilot/how-tos/copilot-sdk/auth/authenticate)
- [GitHub Copilot SDK OAuth setup](https://docs.github.com/en/copilot/how-tos/copilot-sdk/setup/github-oauth)
- [GitHub Copilot SDK getting started](https://docs.github.com/en/copilot/how-tos/copilot-sdk/getting-started)
- [GitHub Copilot SDK v1.0.13](https://github.com/github/copilot-sdk/releases/tag/v1.0.13)
- [GitHub Copilot SDK MIT license](https://github.com/github/copilot-sdk/blob/v1.0.13/LICENSE)
