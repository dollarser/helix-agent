# ADR-0020: CLI Runtime 执行底座候选

Status: proposed
Date: 2026-09-05
HXA: HXA-111, HXA-112
Deciders: pending
Supersedes: none
Superseded by: none

## Context

Helix 需要先证明官方 CLI 存在厂商支持、可在 Android arm64 上运行的发布形态，才可把 executable 打入独立 CLI Runtime。HXA-111 核验了 OpenAI Codex `0.153.3`：官方 release 提供静态链接的 `aarch64-unknown-linux-musl` app-server，SHA-256 与 GitHub release digest 一致；API 29 arm64-v8a 模拟器的 Android shell 可执行 `--version` 并完成 app-server `initialize`。该进程把环境识别为 `Linux Unknown`，这只是实验兼容性。

OpenAI 当前安装文档只提供 macOS/Linux 与 Windows 路径，没有声明 Android 或 Termux 为支持平台。静态 Linux ELF 能在一个 Android kernel/shell 探针中启动，不等于厂商支持 Android，也没有证明应用 UID、SELinux、凭据存储、浏览器回调、更新和长期兼容性。

官方 app-server 协议公开 `chatgptDeviceCode`、登录取消、logout、account/plan type 与 rate-limit 查询；这些接口说明满足平台门禁后不必由主 App 接触 token。但平台门禁先失败，故未进行真实账号登录，也未创建或复制凭据。

## Decision

提议将“厂商明确支持 Android arm64”作为官方 CLI 生产打包的必要条件，而不是把一次 Linux ELF 兼容探针升级为支持承诺。当前 Codex 路线保持 `UNSUPPORTED_ANDROID_PLATFORM`，不得把 `codex-app-server` 打入生产 CLI APK，不实现登录 Activity、凭据持久化或 Agent backend。

HXA-112 仍应独立验证 Claude Code；Codex 的失败不自动否决 Claude。若未来任一官方 CLI 满足 Android 支持门禁，再比较原生静态 executable 与 CLI Runtime 私有 PRoot/RootFS，且继续受 ADR-0007 的独立 UID、冷绑定和对账约束。

## Alternatives considered

1. 直接打包官方 Linux-musl 二进制：设备探针已证明有限兼容，但把未支持 Android 的发布物当作生产基础会把 ABI、SELinux、认证和更新风险转嫁给用户，未选择。
2. 在 CLI Runtime 内增加私有 PRoot/RootFS：可能扩大 Linux 用户态兼容性，但不能把 Android 变成厂商支持平台，还增加 RootFS、补丁和许可证供应链；在候选 CLI 先通过支持门禁前不选择。
3. 非官方重编译、兼容层或复制桌面 `auth.json`：分别破坏官方发布物边界或凭据所有权，明确拒绝。
4. 保持独立 CLI/外部终端体验：不把它宣传成 Helix Agent backend，能力较弱但不伪造支持；当前 Codex 路线采用此结果。

## Consequences

- CLI Runtime 仍只包含 HXA-110 metadata，Codex artifact 的 `bundled` 保持 `false`。
- 不产生 ChatGPT token、cookie 或浏览器凭据；主 App 没有新 IPC、数据表或登录 UI。
- HXA-111 的登录/退出/限额场景因前置门禁失败而不执行，不能声称通过。
- HXA-112 可继续独立 Spike；本 ADR 在所有者接受前保持 `proposed`，不能作为既定架构。

## Verification

已执行：

- GitHub release API 与锁文件确认 `rust-v0.153.3` 官方 app-server，archive size `71499996`，SHA-256 `149e20ca79f76eee578e402146910f78647881b33cf0bf146b23ab363e57a281`。
- `ANDROID_SERIAL=emulator-5580 ./scripts/verify-codex-android-spike.sh` 与 `ANDROID_SERIAL=emulator-5582 ...`：API 29/36 arm64-v8a；ELF 静态 AArch64、`--version` 和 JSONL `initialize` 通过，server 自报 `platformOs=linux`。
- `./gradlew :runtime:cli-client:testDebugUnitTest :runtime:cli-app:testDebugUnitTest`：fail-closed eligibility 与既有 lock 测试。

Required before acceptance：项目所有者审查“厂商支持为必要门禁”的提议，并结合 HXA-112 证据决定是否接受或修订共同执行底座。

## Reconsider when

- OpenAI 官方文档或 release metadata 明确支持 Android/Termux arm64。
- OpenAI 提供 Android SDK/embedded app-server distribution，且认证与凭据所有权满足 Helix 边界。
- 项目所有者明确接受“实验兼容但厂商不支持”的发布风险，并以新的 ADR 给出更新、支持和撤回策略。

## References

- [Companion Runtime 生命周期](0007-companion-runtime-lifecycle.md)
- [本地代码执行方案](../architecture/local-code-execution.md)
- [Codex CLI](https://learn.chatgpt.com/docs/codex/cli)
- [Codex authentication](https://learn.chatgpt.com/docs/auth)
- [Codex app-server](https://learn.chatgpt.com/docs/app-server)
- [Codex 0.153.3 release](https://github.com/openai/codex/releases/tag/rust-v0.153.3)
