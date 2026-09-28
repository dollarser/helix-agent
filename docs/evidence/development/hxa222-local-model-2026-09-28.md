# HXA-222 本地模型：实现与 host 验证

历史 checkpoint；后续修复与最终范围见[收口证据](hxa222-closeout-2026-09-28.md)。以下结果保留当时含义。

日期：2026-09-28。基点 `c70c44403ff7f78e5347833159723f6d03f585a0`，分支 `refactor/clean-slate-engine`。范围为本地未提交工作树；并行的 Workspace/Memory/HXA-227 修改保留。本记录不证明 main、远端 CI、设备推理或发行验收。

本记录是设备授权前的 host checkpoint，以下 `not requested` 与 APK hashes 属于当时状态。随后所有者授权的 API36 真实 GGUF 实测及修复见[单独记录](hxa222-qwen-real-task-2026-09-28.md)：基础 suite 通过，完整模型任务未通过。

## 实现

- provisioning / transport / auth 类型化组合矩阵，residence 从 transport 派生；Room 开发期 v1 baseline 更新，不新增 migration。
- `LocalModelProvider` 接入现有 ProviderFactory/ModelRequest/ModelEvent/probe；无 loopback server、网络 endpoint 或凭据。模型请求的 network egress 判定区分本地推理；后续工具仍走现有权限管线。
- `:model_runtime` 非导出私有进程，typed Binder + 只读模型 PFD + 有界请求/结果 PFD，主进程不加载 llama.cpp。
- cancel 必须证明 executor exit / Binder death；ACK、IPC 失败或强制终止均不能产生成功终态。重复/缺失 terminal 与截断工具调用 fail closed。
- 显式 HTTPS 下载、Range 重试、SHA-256/大小/GGUF 校验、原子发布、配额、卸载/删除；重建开发期数据库后重新登记保留文件。
- Provider 三组 UI；本地资产大小/CPU/context/threads、设备内 residence、probe、卸载与删除。不显示空 endpoint 或 API key。

## Native provenance

- upstream：<https://github.com/ggml-org/llama.cpp/tree/7fe450e19305b828c199d602c23a8337aaa1f03b>（v0.5.0）。
- archive SHA-256：`a6861d549427f814dc591c439e08206f67ffaba0248344d421589abf18199e67`，由 CMake FetchContent 固定和验证。
- NDK `28.2.13676358`、CMake `3.31.6`；CPU arm64-v8a / x86_64，JNI 16 KiB segment alignment。MIT 与所用 bundled dependency notices 随 APK assets 分发。
- 只使用模型内置模板和 upstream parser；本地诊断关闭，不记录 prompt/template exception 原文。
- 模型权重没有下载、打包或执行，故本记录没有真实模型 hash。

## Host 验证

针对最终边界运行：

```sh
./gradlew detekt \
  :app:compileConsumerDebugAndroidTestKotlin :app:compileDeveloperDebugAndroidTestKotlin \
  :provider:api:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest
./scripts/check-all.sh --all
./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
git diff --check
```

- 第一条通过；新的 `LocalModelProviderTest` 7 项、`ModelAssetStoreTest` 3 项、两个 flavor 的 `LocalRuntimeCodecTest` 各 2 项均 0 失败。
- `ProviderConnectionTest` 2 项矩阵与两个 flavor 的 `EgressDisclosureTest` 各 12 项、OpenAI Chat 9 项（含无凭据 header 回归）通过。上述类包含既有用例，不作为全部新增用例数。
- `check-all.sh --all` 通过：source、Spotless/detekt、各模块及双渠道 debug/release lint、全部 JVM tests、consumer/developer debug/release APK、依赖锁、variant/CLI/集成 runtime 制品边界。真实设备与条件外部 profile 不在该 host pass 内。
- consumer/developer AndroidTest APK 均构建通过；包含两个新增 instrumentation 类，未执行。
- 四个应用 APK 均静态确认包含 arm64-v8a/x86_64 的 `libhelix_model.so` 与许可证，所有该库 ELF LOAD segment alignment 至少 16384。这不是实际 16 KiB 设备运行证明。
- 原始日志在 ignored `build/hxa222/`：`final-boundaries.log`、`gate-final.log`、`androidtest-apks.log`。早期 style/detekt/fixture compile 失败均修复后重跑；第一次 native 全量构建因四组 Ninja 默认并发过高主动中止，随后为每组增加 2 个 compiler 的上限并重跑，不能把中止轮记通过。

## 应用制品 SHA-256

| 制品 | SHA-256 |
| --- | --- |
| consumer debug | `9bd981fd642dfc3791904e58f175325b440deb9fed2c04bd8e2aeb04f9a44be8` |
| developer debug | `6afe5b8458a2ca3009daf46d753a99238f3fa0f080dca987c618d21509910d48` |
| consumer release unsigned | `169a7a5696c2f8b2dad6f3525f63eeb4a49102f70e765c21ceffa27a6b518180` |
| developer release unsigned | `799dc38698ff851833995fd6bbe96bf88f1d6f5c5a8a729a7a011729df18eea8` |

release 构建仍为 unsigned，不是发行授权或发布证明。

## 已编写但未执行的设备场景

- `LocalModelRuntimeDeviceTest`：服务非导出/独立进程、坏 digest、无效 GGUF load、可观察 Binder death。
- `LocalProviderLoopDeviceTest`：真实 ChatService/AgentLoop/Dispatcher/Room 配合本地 fake runtime，`time.now` → durable tool result → 第二次模型请求回填；禁止 network/credential 使用。编译不是轨迹通过。

设备：**not requested**。当前任务未获得设备执行授权；未启动或使用模拟器/真机、未调用真实账号。完整 Room/AgentLoop 轨迹不能被 fake capability probe 冒充，保持待验证。

## 仍开放的验收边界

- 本机真实 GGUF 首次 load/warm reuse、工具任务能力、token rate、PSS/RSS、卸载回收、OOM/crash、取消到 exit 延迟、温升与持续运行。
- 窄屏/大字体交互，以及实际加载状态与资源 UI 的设备验收。
- 首版默认 2048 context / 2 threads / greedy，无图片、非零 temperature、自定义 stops；输出为完整生成后有界交付，不是逐 token 流。默认资源限制可能无法容纳完整工具目录；真实 Agent 任务适用性尚未证明，不宣称任意 GGUF 可用。
- HXA-222 继续保留开放任务，不创建完成记录。未提交、推送、合并或发布。
