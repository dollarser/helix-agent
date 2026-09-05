# 本机验证缺口进展

更新时间：2026-09-05

本文记录 `codex/verification-gaps` 对可在本机完成的补充验证；不是 HXA 完成记录，
不修改 `docs/development/status.md`，也不把模拟器、结构性或本地服务证据写成物理真机或发布证据。

## API 34/35 arm64 模拟器

共同宿主：macOS arm64；Android Emulator 37.1.11；serial `emulator-5570`；专用 AVD
均以 `-wipe-data -no-snapshot` 启动。没有操作同时在线的日常/其他任务 AVD。

| API | AVD / SDK image | ABI / page size / build | fingerprint | 结果 |
| --- | --- | --- | --- | --- |
| 34 | `Helix_Verification_Root_API34` / `system-images;android-34;default;arm64-v8a` | `arm64-v8a` / 4096 / `userdebug` | `Android/sdk_phone64_arm64/emu64a:14/UE1A.230829.036.A1/11228894:userdebug/test-keys` | developer 216 tests、36 skipped、0 failed；consumer 121 tests、0 skipped、0 failed |
| 35 | `Helix_Verification_API35` / `system-images;android-35;default;arm64-v8a` rev 2 | `arm64-v8a` / 4096 / `userdebug` | `Android/sdk_phone64_arm64/emu64a:15/AE3A.240806.019/12368160:userdebug/test-keys` | root 6 tests、1 skipped、0 failed；developer 179 suites/216 cases、37 skipped、0 failed；consumer 121 tests、0 skipped、0 failed |

复现命令：

```sh
ANDROID_SERIAL=emulator-5570 ./gradlew \
  :tools:root:connectedDebugAndroidTest \
  :app:connectedConsumerDebugAndroidTest \
  :app:connectedDeveloperDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.helix.app.provider.SglangUiSmokeTest \
  --no-daemon --max-workers=2 --no-configuration-cache
```

API 35 命令 exit code 0，`BUILD SUCCESSFUL in 3m 41s`。API 34 的同口径 app 命令 exit code 0，
`BUILD SUCCESSFUL in 3m 58s`；Root rootless suite 在前一轮同设备执行并通过。JUnit XML 的
tests/failures/errors/skipped 均已解析，不只采信 Gradle/shell exit code。

skip 不是能力通过：developer 的 skip 包括未安装 PRoot companion 的跨 APK/lifecycle E2E、
未提供 Ollama/SelfHosted fixture，以及 root-only 路径；API 35 Root suite 的 1 skip 是 rooted-only
高阶工具。consumer 121/121 无 skip，提供 consumer flavor 不含 developer-only Root 实现的设备回归，
但不是签名 release 证据。

### 外部 SGLang smoke 的独立失败

第一次 API 34 全量命令未排除真实服务 smoke。测试默认探测 `10.0.2.2:30008`，恰好发现宿主
SGLang 服务，因而没有 assumption-skip；随后
`SglangUiSmokeTest.modelDiscoveryAgainstTheRealSglangEndpoint` 在模型 chip 打开编辑框处 10 秒超时。
该轮 developer 217 tests、1 failed，Gradle exit code 1。这是本地外部服务/UI smoke 失败证据，
不是 API 34 平台回归失败，也没有通过关闭服务、修改测试或增加 skip 隐藏。平台矩阵明确排除此类，
M7 外部协议/代理边界需在独立 fixture 中复现和处理。

## x86_64

SDK catalog 可获得 API 34/35 x86_64 system image，但本机 Apple Silicon 环境本轮未下载、未启动
x86_64 AVD，因此没有 x86_64 设备证据。当前仅有结构性构建证据：consumer/developer debug APK
各包含 x86_64 `libquickjs.so` 与 `libandroidx.graphics.path.so`，`file` 识别为 ELF64 x86-64。

| ELF | SHA-256（两个 flavor 一致） |
| --- | --- |
| `libquickjs.so` | `203a596604a5ba11f26da5579be1877a93161457651ab39a68e0a154f1e589dd` |
| `libandroidx.graphics.path.so` | `4e56c996f13670e70082658de7880c4020eabf4f25e43387f88ed78a713fc9f0` |

PRoot Runtime 源树仍只有 `arm64-v8a/libhelix_loader.so`；不能从主 App 的 x86_64 QuickJS ELF 推导
PRoot x86_64 可运行。结论保持“结构性 ELF 存在”，不写成 ABI 设备通过。

## 16 KiB page

SDK catalog 提供官方 `system-images;android-35;google_apis_ps16k;arm64-v8a` rev 5。旧
`sdkmanager` 运行约 8 分钟没有可见下载进展，改用新版
`android sdk install "system-images;android-35;google_apis_ps16k;arm64-v8a"` 后从
`dl.google.com/android/repository/sys-img/google_apis/arm64-v8a-ps16k-35_r05.zip` 完整安装。
专用 AVD `Helix_Verification_API35_16K` 以 wipe-data/no-snapshot 启动，设备自身返回：API 35、
`arm64-v8a`、`getconf PAGESIZE=16384`、`userdebug/dev-keys`，fingerprint 为
`google/sdk_gphone16k_arm64/emu64a16k:15/AE3A.240806.043/12960925:userdebug/dev-keys`。

镜像 SHA-256：kernel `e8ac73e75665e94ae692c782cdde9a5f98f905d47eee4d9fde48892510232a58`；
ramdisk `5c5bd7b9577fe26df5f8b531dd8e0c7cbd432b7943e85a2cb84663fdb53a2e24`；system
`f540461938c431f81bc00d07d3e8f7154811133193282615984424cca1ed77a5`；vendor
`966beabfa21a6ac8a3d3d45c78c062f055a433f6d4222dd05c888ec86f14e90e`。

初始设备矩阵不是全绿：

- Root rootless suite：6 tests、1 rooted-only skip、0 failed。
- consumer 全量：121 tests，只有
  `AttachmentE2eDeviceTest.cancelMidStreamTerminalizesAndTheResendSucceeds` 失败；取消后固定等待窗口内
  仍为 `isSending=true`。同方法立即单独复跑 1/1 通过。
- developer 全量：216 cases、37 skipped，同一取消终态方法再次失败，除此之外无第二项失败。

根因已定位为真实的 turn 启动竞态，而不是等待预算：`send` 先登记非终态 turn，再把协程调度到
IO pool；若用户在协程进入 `runTurn` 的 `try/catch` 前按 Stop，Job 会直接取消，持久化终态逻辑没有
机会运行。修复使协程以 `CoroutineStart.UNDISPATCHED` 进入受保护的 `startGate.await()` 后再发布，
并使原“mid-stream”测试等待 wire 已打开且首个 delta 已显示，而不是把初始 `WAITING_MODEL` 误判为
streaming。JVM 两 flavor、AndroidTest 编译和 Spotless 均通过；16 KiB AVD 上 consumer/developer 定向
方法各 1/1 通过。

修复后 consumer 全量的原取消方法通过，但在 101/121 时出现另一独立失败：
`FilesScreenTest.previewsTextFileWithHashInfo` 等待 `files-preview-text` 30 秒超时；同方法单独复跑仍失败。
因此本轮仍不能写为 16 KiB 全量绿色，且 developer 全量尚需在该新边界处理后重跑。
16 KiB 模拟器证据仍不能替代 16 KiB 物理真机。

## PRoot 生命周期与资产可重复性

初次执行因 gitignore 的生成资产不存在而在 archive 阶段失败。随后发现脚本默认使用 Alpine 官方
CDN，而 HXA-073 的锁定资产实际由 `https://mirrors.aliyun.com/alpine` 构建；即使包版本完全相同，
mutable repository 的包字节差异仍会改变最终 tar。官方 CDN 构建得到 137,286,656 bytes / SHA-256
`cb1b928b8ec890cdae4a465654aa79f496b01930853b131efd2d0d02c16feb2a`，脚本按预期 fail closed。

修复将 HXA-073 已验证的阿里云地址设为 canonical 默认值，同时保留 `ALPINE_MIRROR` 仅供诊断覆盖；
最终 raw tar hash 仍是权威门禁。无环境变量运行 `bash scripts/build-proot-assets.sh` 后，三个 Termux
组件均通过锁定 URL、size、SHA-256 校验，Docker image digest 为
`alpine@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce`，37 个
精确版本包安装完成；raw tar 精确恢复为 137,287,680 bytes / SHA-256
`674aa3ac68200bfe67b01964573fce2c6780c726fe23ca640e998337cf47f509`，360 个 ELF 均满足
16 KiB min-align。生成资产仍保持 gitignored，未入库。

随后在 API 35/4096-byte 专用 AVD `emulator-5570` 执行
`scripts/accept-hxa-086-lifecycle.sh emulator-5570`，脚本 exit 0 且输出
`HXA-086 lifecycle acceptance PASSED`。覆盖 force-stop、cold bind、idle recycle、screen off、
low-memory signal、长任务、主 App/companion 中途死亡、wakelock/FGS 采样、exactly-once、smoke、
隔离及通知。这是模拟器生命周期与模拟系统信号证据；安全锁屏、真实 Doze/物理低内存及物理热限制
仍无真机证据。

## M7 MCP/A2A 本地协议与 release

API 35/4096-byte 专用 AVD：

- `McpAndroidSpikeDeviceTest`：12/12，exit 0；本地真实 HTTP/SSE fixture 覆盖 initialize/ping、版本
  协商、401、bearer、取消、关闭后重连、SSE 断线 + `Last-Event-ID`、TLS 中断和 wire limits。
- `A2aTaskRunnerDeviceTest`：2/2，exit 0；本地协议 fixture 覆盖原 Task 对账、不明确送达不重发及
  Artifact 幂等复用/篡改拒绝。
- `:extensions:mcp:test :extensions:a2a:test`：MCP 35 tests、A2A 13 tests，全部通过。

这些属于本地协议服务与模拟断线证据，不等同第三方生产服务；未使用真实账号 Secret。当前没有
独立的显式 HTTP proxy device fixture，因此不能把已有 MockWebServer/TLS failure 写成代理通过。

严格 Android/R8 spike 均通过：MCP minApi 29、DEX 1,198,764 bytes / 7,726 method IDs；A2A
minimal minApi 29、DEX 15,544 bytes / 169 method IDs。consumer/developer release 和 lintVital 构建
exit 0；产物分别为 39,710,970 bytes、SHA-256
`7f956bd4243b16c45428e2de9fbcb46bbdcbd33542cc7a451afe8bb4bad6a555`，以及 40,291,915 bytes、
SHA-256 `a3287bfdd784437234040def3163f3869628578eab912145848844e7354b6c32`。两者文件名均含
`-unsigned.apk`，build-tools 36.0.0 `apksigner verify` 均 exit 1、`DOES NOT VERIFY`。这是 unsigned
release/R8 结构性构建证据，不是签名 release、商店提交或发布证据。

## `status.md` 只读过期项

以下仅供 main 合并时统一修订，本分支不直接改公共状态文件：

- 环境限制段仍写“无 API 34 系统镜像”，现已被本次 API 34 专用 AVD 实测推翻；应保留旧完成
  记录当时的历史条件，同时补充新的日期化证据，而不是追改历史。
- 一处 M7 汇总仍写“Accessibility、Root 仍未实现”；当前相邻条目已明确 HXA-090～097 已实现、
  Root HXA-094/095 非真机范围已实现且只缺物理门禁，因此该句已过期。
- “arm64 Mac 无 x86_64 模拟器镜像”应改为“官方 catalog 有镜像，但本机未形成 x86_64 设备
  运行证据”；镜像可获得性与宿主可运行性需要分开。
- M9 唯一正式门禁仍是 rooted 物理设备；本次 rooted AVD 撤权失败进一步说明不能降级该门禁。

## 尚未执行

16 KiB Files preview 新失败、root manager revoke 修复后的 rooted AVD 回归、显式 HTTP proxy fixture、
签名 release、x86_64 设备、安全锁屏/真实 Doze/物理热限制和 rooted/16 KiB 物理机仍没有完整新证据。
商店发布保持外部门禁。
