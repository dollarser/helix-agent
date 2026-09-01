# Helix 开发环境与依赖基线

基线日期：2026-09-01。本文同时承担“固定依赖基线”和“新开发机从零配置手册”两种职责。版本用于可重复启动，不代表永久最新；任何升级必须单独任务、更新锁文件并通过全量门禁。

配置原则：仓库内只保存版本、相对命令和验收规则，不保存用户名、设备序列号、真实 API Key、签名材料或某台机器的绝对 SDK 路径。历史开发会话中的环境快照只能帮助找线索；当前事实必须由本文列出的命令、Gradle 配置和实际设备输出共同证明。

## 1. 主机要求

推荐：

- macOS 14+、Linux x86-64 或 Linux arm64。
- 内存至少 16 GiB，推荐 32 GiB。
- 可用磁盘至少 30 GiB；加入 PRoot/多模拟器后推荐 60 GiB。
- Git 2.40+。
- Bash、`ripgrep`、`unzip`、`strings`，以及 `sha256sum` 或 `shasum`；仓库门禁会直接调用它们。
- Android 真机至少一台 arm64。

M0-M7 不要求 Docker、Node.js、Python 或 Rust。M8 构建 PRoot assets 需要 Python 3.11+；M11 构建 CLI Runtime 需要 Node.js/CLI 对应工具链。脚本必须显式检查，不能依赖开发机偶然已有版本。

## 2. 固定 Android 工具链

| 组件 | 基线 | 理由/来源 |
| --- | --- | --- |
| JDK | 17 | AGP 9.3 默认/最低 JDK |
| Android Gradle Plugin | 9.3.2 | 9.3 当前补丁版；修复 9.3 lint/JDK 17 问题 |
| Gradle Wrapper | 9.5.0 | AGP 9.3 最低/默认 |
| Kotlin Android plugin | 2.3.21 | Android 官方 AGP 9.3 示例组合 |
| compileSdk | 36 | 使用稳定 Android 16 API |
| targetSdk | 36 | 与 compile 基线一致 |
| minSdk | 29 | 统一 scoped storage 时代行为，降低兼容分支 |
| SDK Build Tools | 36.0.0 | AGP 9.3 默认 |
| NDK | 28.2.13676358 | AGP 9.3 默认；native/PRoot 准备 |
| Compose BOM | 2026.06.01 | 最新验证可与 compileSdk 36 共用的稳定 BOM；2026.08.00 中 Compose 1.12 要求 compileSdk 37 |
| Java/Kotlin bytecode | 17 | 与 JDK 基线一致 |

官方依据：

- [AGP 9.3 compatibility](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
- [AGP/Gradle/Kotlin 配置示例](https://developer.android.com/build/releases/about-agp)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)

不要使用 `+`、`latest.release`、未固定 Git branch 或 SNAPSHOT 依赖。

## 3. 当前库基线

M0 已按下表创建 `gradle/libs.versions.toml`。当前构建和 lockfile 是实现事实源；新增库前优先使用 Kotlin/Android 标准库已有能力。

| 能力 | 依赖 | 基线 |
| --- | --- | --- |
| Compose | `androidx.compose:compose-bom` | `2026.06.01` |
| Lifecycle | `androidx.lifecycle:lifecycle-*` | `2.10.0` |
| Activity Compose | `androidx.activity:activity-compose` | `1.13.0` |
| Navigation Compose | `androidx.navigation:navigation-compose` | `2.9.8` |
| Room | `androidx.room:room-*` | `2.8.4` |
| WorkManager | `androidx.work:work-runtime-ktx` | `2.11.2` |
| HTTP/SSE | `com.squareup.okhttp3:okhttp`, `okhttp-sse` | `5.5.0` |
| JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.9.0` |
| QuickJS | `app.cash.zipline:zipline` | `1.27.0` |
| WebView compat | `androidx.webkit:webkit` | `1.17.0` |
| MCP Client | `io.modelcontextprotocol:kotlin-sdk-client` | `0.15.0` |
| MCP HTTP engine | `io.ktor:ktor-client-okhttp`, `ktor-client-sse` | `3.5.2` |
| Root | `com.github.topjohnwu.libsu:core`, `service` | `6.0.0`，M9 才引入 |
| Unit test | JUnit4 | `4.13.2` |
| AndroidX test | core/runner `1.7.0`、ext JUnit `1.3.0`、Espresso `3.7.0` | version catalog 固定 |

说明：

- Activity/AndroidX test 等快速更新依赖以 `gradle/libs.versions.toml` 为当前事实源；升级必须按第 12 节单独验证并更新本文。
- 不引入 Hilt/Koin；采用手工 `AppContainer`。
- 不引入 LangChain4j/Semantic Kernel；自研有限 Agent Loop。
- Provider 流协议直接使用 OkHttp；Ktor 只封装在 MCP module，因为官方 SDK 依赖 Ktor。
- 不引入通用 shell/process 库；PRoot Runner 自己封装明确的 argv 和 lifecycle。
- 不使用已 deprecated 的 `androidx.security:security-crypto` 作为新设计核心；使用 Android Keystore + 明确的加密存储封装。
- Agent Skills 自行实现 Kotlin parser/loader；官方 `skills-ref` 只作规范 fixture，不作 Android production 依赖。
- `app.cash.zipline:zipline:1.27.0` 就是包含 Android target/QuickJs API 的 multiplatform artifact；不替换为历史 `zipline-quickjs-android`/`quickjs-android` 坐标。升级仍需核对 resolved AAR/native ABI 和 API dump。

## 4. SDK 安装

使用 Android Studio 当前稳定版，Gradle JDK 和终端 `JAVA_HOME` 都指向 JDK 17。Android Studio 的 Settings / Build Tools / Gradle 中也选择同一个 JDK，避免 IDE Sync 与命令行构建使用不同 Java。

### 4.1 macOS Apple Silicon 环境变量

Homebrew 安装 JDK 17 后，在 shell 配置中使用可移植路径，不写具体用户名：

```bash
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="${HOME}/Library/Android/sdk"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${ANDROID_HOME}/cmdline-tools/latest/bin:${PATH}"
```

修改 shell 配置后新开终端，或在当前终端重新加载配置。Linux 的 SDK 根通常为 `${HOME}/Android/Sdk`；其余变量和 `PATH` 结构相同。不要把这些展开后的绝对路径提交到仓库。

`ANDROID_HOME` 是脚本优先读取的 SDK 根；`ANDROID_SDK_ROOT` 只为仍读取旧变量的工具保持兼容。两者若同时存在必须指向同一目录。

### 4.2 SDK 包

命令行包：

```bash
sdkmanager \
  "cmdline-tools;latest" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "ndk;28.2.13676358" \
  "cmake;3.31.6"
```

接受许可证：

```bash
yes | sdkmanager --licenses
```

命令行工具 22.0 起会提示 `sdkmanager` 已弃用，并推荐新的 `android sdk`。本机 2026-08-31 验证中，`android sdk` 首次运行仍依赖额外在线引导下载；在该引导流程纳入可重复安装验证前，上述 `sdkmanager` 命令作为已验证的兼容路径保留。后续升级命令行工具时必须单独验证并迁移，不能只机械替换命令名。

### 4.3 `local.properties`

Android Studio 首次 Sync 通常会生成仅含本机 SDK 定位信息的 `local.properties`。该文件已经被 `.gitignore` 排除，必须保持本机私有；不得复制其他开发者的文件，也不得在文档、脚本或 CI 中固化 `/Users/<name>/...` 一类路径。CI 由 Android SDK setup action 提供 SDK，不依赖仓库内的 `local.properties`。

### 4.4 工具链验证

在仓库根执行：

```bash
java -version
adb version
sdkmanager --list_installed
./gradlew --version
```

验收要点：

- `java -version` 与 `./gradlew --version` 的 Launcher/Daemon JVM 都必须是 17。
- `./gradlew --version` 显示的 Gradle 自带 Kotlin 版本不是项目 Kotlin plugin 版本；项目版本只以 `gradle/libs.versions.toml` 为准。
- `sdkmanager --list_installed` 必须至少包含 Platform 36、Build Tools 36.0.0、Platform Tools、NDK 28.2.13676358 和 CMake 3.31.6。
- 不依赖开发机的全局 Gradle，所有构建只使用 `./gradlew`。

## 5. 模拟器和真机矩阵

### 5.1 每次 PR

- 当前无设备 CI：JVM unit tests、Spotless、Detekt、双变体 Lint、四个 M0 APK、依赖/变体/文档扫描。
- 含 Android UI、生命周期、Room migration 或平台能力改动的 PR：在合并前增加 API 36 instrumentation 证据；可先使用开发机 AVD，远端 emulator job 建立后再强制自动运行。
- 当前 Apple Silicon 开发机使用 API 36 arm64-v8a AVD；Linux CI 可使用 x86_64。报告必须写真实 ABI，不能把一种架构的结果冒充另一种。

### 5.2 每个里程碑

| 设备 | 架构/API | 用途 |
| --- | --- | --- |
| Emulator A | 主机原生 ABI / API 29 | 最低版本行为；Apple Silicon 用 arm64-v8a，Linux x86-64 用 x86_64 |
| Emulator B | 主机原生 ABI / API 36 | 当前 target、自动测试；Apple Silicon 用 arm64-v8a，Linux x86-64 用 x86_64 |
| Device A | arm64 / API 34 或 35 | 主流真机、功耗/后台 |
| Device B | arm64 / API 36 | 新系统、真实 16 KiB page、QuickJS/PRoot 全部 ELF/smoke、前台限制 |
| Low-memory device | arm64 / 4–6 GiB RAM | QuickJS/PRoot 资源压力 |
| Rooted test device | arm64 / API 34+ | libsu 授权、拒绝、RootService；不得使用主力手机 |
| Secondary automation device | arm64 / API 34+ | Accessibility 跨 App 和敏感界面拒绝 |

QuickJS、WebView、PRoot、Accessibility、Root 和 CLI Runtime 不能只在模拟器验收。Root/Accessibility 自动化使用专用测试设备和自建 fixture App。

### 5.3 创建 Helix AVD

安装与主机架构匹配的 Google APIs 镜像。Apple Silicon 使用 `arm64-v8a`；Linux x86-64 使用 `x86_64`，不能为了复用命令而安装错误 ABI：

```bash
helix_host_abi="arm64-v8a" # Linux x86-64 改为 x86_64
sdkmanager \
  "system-images;android-29;google_apis;${helix_host_abi}" \
  "system-images;android-36;google_apis;${helix_host_abi}"

printf 'no\n' | avdmanager create avd \
  --name Helix_API_29 \
  --package "system-images;android-29;google_apis;${helix_host_abi}"
printf 'no\n' | avdmanager create avd \
  --name Helix_API_36 \
  --package "system-images;android-36;google_apis;${helix_host_abi}"
```

若同名 AVD 已存在，不要用 `--force` 覆盖；先通过 Android Studio Device Manager 检查其 API、ABI、磁盘和快照状态。普通开发从 Device Manager 启动即可；命令行冷启动参考：

```bash
emulator -avd Helix_API_36 -no-snapshot-load
adb wait-for-device
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
```

预期分别为 `36` 和与所装镜像一致的 ABI。设备测试报告必须记录 API、ABI、模拟器/真机和实际 test count；`BUILD SUCCESSFUL` 不能单独替代功能证据。

### 5.4 真机准备

1. 在专用测试设备开启 Developer options 与 USB debugging。
2. 首次连接时在设备端确认电脑指纹；不要在共享或不可信电脑上选择永久信任。
3. 执行 `adb devices -l`，状态必须为 `device`，不能是 `unauthorized` 或 `offline`。
4. 同时连接多个设备时，先设置当前终端的 `ANDROID_SERIAL`，避免 instrumentation 跑到错误设备；完成后取消该临时变量。
5. 不在日志、完成记录或仓库中保存硬件序列号、个人通知、真实账号或用户文件。

### 5.5 真机安装、启动与日志闭环

先确认目标设备与实际系统边界，再构建当前直接分发的 developer 主包。下列占位符不得替换后提交到仓库：

```bash
export ANDROID_SERIAL="<adb-device-serial>"

adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi

./gradlew :app:assembleDeveloperDebug
adb install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk
adb shell monkey -p com.helix.agent.developer -c android.intent.category.LAUNCHER 1
adb shell pidof com.helix.agent.developer
```

`pidof` 有输出只证明进程存活，不代表界面、Provider 或 Tool 功能已验收。冷启动和异常日志可分别检查：

```bash
adb shell am force-stop com.helix.agent.developer
adb shell monkey -p com.helix.agent.developer -c android.intent.category.LAUNCHER 1
adb logcat -d -v threadtime | rg -i \
  'FATAL EXCEPTION|ANR in com\.helix\.agent\.developer'
```

首次启动告知、通知权限、Accessibility、All files access 和 Root 等都必须由测试人员在 App/系统 UI 中做真实选择。只读查验通知状态的示例：

```bash
adb shell dumpsys package com.helix.agent.developer | \
  rg -A2 'android.permission.POST_NOTIFICATIONS'
```

不使用 `pm grant`、直接改 App 数据库或自动点击敏感系统页伪造用户授权。如果只需重装测试 APK，不要执行 `pm clear` 或卸载主包，否则会丢失用户已确认的首次启动与 Provider 配置。完成后执行 `unset ANDROID_SERIAL`。

### 5.6 真机访问本机或远程自建模型

先分清两个“本机”：手机中的 `127.0.0.1` 是手机自己，开发机中的 `127.0.0.1` 是开发机自己。`ssh -L 30008:127.0.0.1:30008 <ssh-host>` 默认只在开发机的 loopback 上监听，手机不能通过开发机的局域网 IP 访问它。

USB 连接调试时优先使用 `adb reverse`，无需向局域网开放模型端口：

```bash
# 终端 A：只向开发机 loopback 建立到远程模型服务的隧道
ssh -N -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:30008:127.0.0.1:30008 <ssh-host>

# 终端 B：将手机 127.0.0.1:30008 反向到开发机 127.0.0.1:30008
adb reverse tcp:30008 tcp:30008
adb reverse --list
```

此时 App 或 instrumentation 使用 `http://127.0.0.1:30008/v1`。远程 SGLang 可以继续只监听远程主机的 `127.0.0.1:30008`；SSH 进程是远程目标的访问者。调试结束后移除 reverse，并在终端 A 按 `Ctrl-C` 停止 SSH：

```bash
adb reverse --remove tcp:30008
```

只有在验证 Wi-Fi/实际 LAN 路径时，才把 SSH 本地转发绑定到开发机的具体局域网地址：

```bash
helix_host_ip="<development-host-lan-ip>"

ssh -N -o ExitOnForwardFailure=yes \
  -L "${helix_host_ip}:30008:127.0.0.1:30008" <ssh-host>
```

不建议绑定 `0.0.0.0`；它会向开发机所有可达网卡暴露端口。手机和开发机必须位于互通网段，并允许 macOS/Linux 防火墙接收该端口。分层验证：

```bash
# 开发机：必须显示具体 LAN IP，而不是只有 127.0.0.1
lsof -nP -iTCP:30008 -sTCP:LISTEN
curl --fail --max-time 5 "http://${helix_host_ip}:30008/v1/models"

# 手机：先只验证 TCP 可达，再跑 Helix Provider smoke
adb shell nc -zvw 3 "${helix_host_ip}" 30008
```

developer 真机的 SGLang 产品类型冒烟测试可按方法级逐项执行，避免 OEM 后台策略中止整类 instrumentation 后丢失已完成项的证据：

```bash
./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest
adb install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk
adb install -r app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk

helix_smoke_class="com.helix.app.provider.SelfHostedSmokeTest"
helix_runner="com.helix.agent.developer.test/androidx.test.runner.AndroidJUnitRunner"

adb shell am instrument -w -r \
  -e helix.smoke.host "${helix_host_ip}" \
  -e class "${helix_smoke_class}#sglangConfigurationCheckAndModelListPass" \
  "${helix_runner}"
adb shell am instrument -w -r \
  -e helix.smoke.host "${helix_host_ip}" \
  -e class "${helix_smoke_class}#sglangTextStreamCompletesWithTextDelta" \
  "${helix_runner}"
adb shell am instrument -w -r \
  -e helix.smoke.host "${helix_host_ip}" \
  -e class "${helix_smoke_class}#sglangToolCallCompletesWithClosedToolIndex" \
  "${helix_runner}"
```

使用 USB reverse 时将 `helix_host_ip` 设为 `127.0.0.1`。上述 SGLang smoke 固定使用 `30008`；同类中 Ollama smoke 使用 `11434`，服务不存在时必须显式 skip，不能记录为通过。明文 HTTP 可达性不是授权：Helix 仍要求用户在 UI 中确认精确 `host:port`，Android 系统权限、MCP annotation 或进入 Advanced 都不能代替该授权。无认证模型端口只能在可信网络中临时开放。

收尾时只卸载 instrumentation 测试包，保留主 App 及其用户数据：

```bash
adb uninstall com.helix.agent.developer.test
adb shell pm list packages | rg 'com\.helix\.agent'
```

## 6. Gradle 约定

### 6.1 Version catalog

所有第三方版本放 `gradle/libs.versions.toml`。模块 build 文件不得散落版本号，Compose BOM 例外也通过 catalog 引用。

### 6.2 Dependency locking

根工程启用：

```kotlin
dependencyLocking {
    lockAllConfigurations()
}
```

首次生成：

```bash
./gradlew dependencies --write-locks
```

提交 `gradle.lockfile`/各模块 lock file。升级时禁止无关锁文件大面积漂移。

### 6.3 Repository

默认只允许：

```kotlin
repositories {
    google()
    mavenCentral()
}
```

libsu 目前使用 JitPack，因此 M9 允许唯一例外，并限制 group：

```kotlin
exclusiveContent {
    forRepository { maven("https://jitpack.io") }
    filter { includeGroup("com.github.topjohnwu.libsu") }
}
```

同时固定 `6.0.0`、启用 Gradle dependency verification 并保存 resolved artifact checksum。其他 JitPack、HTTP Maven 和 `flatDir` 仍禁止。

### 6.4 Build variants 和独立 Runtime

```text
主 App：consumerDebug / consumerRelease
主 App：developerDebug / developerRelease
Runtime APK：:runtime:proot-app:assembleDebug / assembleRelease
CLI Runtime APK：:runtime:cli-app:assembleDebug / assembleRelease
```

主 App developer 包含高级能力和 Runtime IPC client，但不包含 PRoot/RootFS/CLI binary。`runtime:proot-app` 是无 INTERNET 的独立 APK/UID；`runtime:cli-app` 是有 INTERNET、无 All-files/Accessibility/Root 的独立 APK/UID。CI 分别验证权限和禁止内容。

发布角色与 Gradle 名称分开：当前直接分发只把 developer 构建作为用户主应用，产品名仍为 Helix；consumer 只为未来受限渠道保留。开发、CI 和路径继续使用现有 flavor 名，未经 HXA-122 的 applicationId/签名迁移决定不得机械重命名。PRoot/CLI APK 是按需 companion，不得出现在“选择主应用版本”的 UI 中。

applicationId 基线：`consumer=com.helix.agent`、`developer=com.helix.agent.developer`、`runtime:proot-app=com.helix.runtime.proot`、`runtime:cli-app=com.helix.runtime.cli`。变体使用 `developerImplementation` + `src/developer` 隔离 `feature:files-allfiles`、`tools:automation`、`tools:root`、`runtime:proot-client`、`runtime:cli-client`，不仅依赖运行时 feature flag 隐藏 consumer 入口。

## 7. 本地配置和 Secret

不提交：

- `local.properties`
- `.env`
- keystore、签名密码
- API Key/Authorization fixture
- 用户真实文件和通知
- 下载后的 RootFS

调试 Provider 推荐通过 App 设置 UI 输入。instrumentation 如需 secret，从本机环境注入只用于手工 smoke，不写入 BuildConfig 和 APK：

```text
HELIX_TEST_BASE_URL
HELIX_TEST_MODEL_ID
HELIX_TEST_API_KEY
```

自动 CI 使用 FakeProvider/MockWebServer，不调用付费模型。

## 8. 推荐仓库目录

下图是目标模块/目录布局，不是实时实现清单。目录存在、可参与 Gradle 构建或包含 marker 都不表示业务能力已落位；已实现范围、空骨架和唯一当前任务以[实施状态](implementation-status.md)为准。

```text
Helix/
├── .github/workflows/
├── app/
├── core/
├── provider/
├── extensions/
│   ├── mcp/
│   └── skills/
├── feature/
│   ├── browser/
│   ├── files/
│   └── files-allfiles/
├── runtime/
│   ├── quickjs/
│   ├── proot-client/
│   ├── proot-app/
│   ├── cli-client/
│   └── cli-app/
├── tools/
│   ├── framework/
│   ├── android/
│   ├── automation/
│   ├── browser/
│   ├── files/
│   └── root/
├── testing/
├── docs/
│   ├── adr/
│   ├── completion-records/
│   └── fixtures/
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── scripts/
├── config/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── LICENSE
├── THIRD_PARTY_NOTICES.md
└── README.md
```

## 9. 基础构建命令

```bash
./gradlew --version
./gradlew :app:assembleConsumerDebug
./gradlew :app:assembleDeveloperDebug
./gradlew :runtime:proot-app:assembleDebug
./gradlew :runtime:cli-app:assembleDebug
./gradlew test
./gradlew lintConsumerDebug lintDeveloperDebug
./gradlew connectedConsumerDebugAndroidTest connectedDeveloperDebugAndroidTest
```

安装当前直接分发主应用的开发构建：

```bash
adb devices
adb install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk
```

consumer 只用于受限渠道和裁剪门禁 smoke，需要验证时单独安装 `app-consumer-debug.apk`，不能把该命令写成当前普通用户安装路径。

PRoot/CLI 是可选 companion。需要测试时，先安装与主 App 同签名证书构建的所需 Runtime APK，再安装 developer 主 App；启动时执行协议、版本和签名握手。不测试高级 Runtime 时只安装主 App，不要求普通用户预装 companion。不要为调试关闭签名校验。

不要把 `adb shell pm grant` 当正常用户权限流程。权限必须从产品 UI 引导用户在系统界面授予。

### 9.1 日常最小反馈环

纯 Kotlin/文档修改先跑最窄的任务，再按 [verification matrix](verification-matrix.md) 扩大范围：

```bash
./gradlew :core:model:test # 示例；替换为当前 HXA 的最窄任务
./gradlew spotlessCheck detekt
./scripts/check-docs.sh
./scripts/verify-adr.sh
git diff --check
```

Android UI、Room migration、生命周期或平台能力有变化时，必须追加对应 flavor 的 `connected...AndroidTest`。不要用根 `./gradlew test` 的成功替代设备测试，也不要为了通过本地构建临时升级依赖或降低 compile/target SDK。

## 10. PRoot 资产构建环境

M8 开始前再启用：

- Python 3.11+，仅用于确定性下载/解包/生成 manifest。
- `curl`、`sha256sum` 或 `shasum -a 256`。
- `readelf`/`llvm-readelf` 检查 ABI 和动态依赖。
- 许可证清单生成器。

脚本必须：

- 只接受固定 HTTPS URL。
- 验证 size 和 SHA-256。
- 输出 `runtime-lock.json`。
- 不使用 `curl | sh`。
- 不从 mutable branch 构建 release binary。
- 保存上游 source archive hash。

## 11. 当前 CI 与后续扩展

当前 [Android CI](../.github/workflows/ci.yml) 已实现：

```text
checkout
→ verify Gradle Wrapper
→ JDK 17 / Android SDK 36
→ Spotless / Detekt / unit tests / 双变体 Lint
→ 四个 M0 debug APK
→ dependency lock / secret / ADR / 文档契约扫描
→ 主 App 变体和两个 Runtime APK 边界扫描
→ git diff --check
```

workflow 的 action 均固定到 commit SHA。2026-08-31 `main` 已推送到 GitHub：最早 1 次 Android CI 失败后，依赖验证/Action 版本修复带来 3 次连续成功；最新成功运行是 [33364284426](https://github.com/dollarser/helix-agent/actions/runs/33364284426)，并生成保留 1 天的 debug APK bundle。当前远端 workflow 仍不运行 emulator/真机；HXA-003 的 API 36 arm64-v8a instrumentation 证据来自本机，不能由远端构建替代。

后续扩展：API 29/36 instrumentation、dependency/SBOM 报告、WebView/MCP fixture、基准 fixture、RootFS/CLI manifest 链接检查。CI 缓存不包含 secret、Runtime home 或真实 Provider 响应。只有在对应能力进入实现后才加入专项 job，不提前加入永远空跑的占位流水线。

## 12. 版本升级流程

1. 单独创建 `dependency-upgrade` 任务。
2. 阅读官方 release note 和 breaking changes。
3. 更新 version catalog 与 lock file。
4. 查看 dependency diff，确认没有新增 repository/动态代码/许可证变化。
5. 跑全部 unit、lint、instrumentation、consumer/developer build。
6. QuickJS/NDK 升级必须重跑隔离、16 KiB page 和真机攻击测试。
7. PRoot/RootFS 升级必须生成新 runtime manifest、许可证和 rollback 测试。
8. MCP SDK 升级必须重跑 Android/R8/transport Spike；Skill 规范升级必须重跑官方 fixture。
9. WebKit/libsu 升级必须重跑对应真机权限和攻击测试。

## 13. 新环境验收

### 13.1 从零启动清单

1. 安装 JDK 17、Android Studio 与第 4 节 SDK 包。
2. 设置 shell 环境变量，并让 Android Studio Gradle JDK 指向同一 JDK 17。
3. Clone 仓库；允许 Android Studio 生成本机 `local.properties`，但确认其被 Git 忽略。
4. 执行工具链诊断和 Gradle Wrapper 验证。
5. 创建/启动 API 36 AVD，或连接已授权的测试真机。
6. 先构建双主 App 变体，再执行当前 HXA 在验收矩阵中的命令。
7. 不配置真实 Provider secret 也必须能完成基础构建和自动测试；网络协议测试使用 fixture/FakeProvider/MockWebServer。

新开发者或编码 Agent 在没有任何业务 secret 的情况下，最低必须能够：

```bash
git clone <helix-repository>
cd Helix
./gradlew --version
./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug
./gradlew :runtime:proot-app:assembleDebug :runtime:cli-app:assembleDebug
./gradlew test lintConsumerDebug lintDeveloperDebug
./gradlew spotlessCheck detekt
./scripts/check-docs.sh
./scripts/verify-adr.sh
./scripts/check-lockfiles.sh
./scripts/check-secrets.sh
./scripts/verify-variant-boundaries.sh
git diff --check
```

若还需手工下载未记录文件、修改绝对路径或复制他人 `local.properties` 才能构建，则开发环境文档不合格。

继续当前仓库开发时，任务状态只以 [implementation-status](implementation-status.md) 为准：`In progress` 非空则续接，否则使用 `Next task`；已有完成记录的 HXA 不重复实现。分支、commit、CI、SDK 和设备均以实时检查为准，不在长期文档中固化机器快照。

### 13.2 已验证参考工作站

2026-09-01 在 macOS arm64 / 24 GiB 主机上重新执行第 4 节诊断，确认以下组合可用：JDK 17.0.20.1、Android Studio 2026.1（build `AI-261.26222.65.2613.16025427`）、Gradle Wrapper 9.5.0、Platform Tools 37.0.1、Emulator 37.1.11、Platform 36、Build Tools 36.0.0、NDK 28.2.13676358、CMake 3.31.6，以及 API 29/36 Google APIs ARM64 镜像和 `Helix_API_29`/`Helix_API_36` AVD。

这只是已知可工作的参考快照，不是要求所有机器逐补丁一致。项目的规范性版本仍以第 2、3 节和锁文件为准；Android Studio、ADB、Emulator 的补丁漂移必须通过实际构建/设备测试判断，不能仅凭版本号宣布兼容。

## 14. 常见问题定位

| 症状 | 检查与处理 |
| --- | --- |
| `JAVA_HOME is set to an invalid directory` | 执行 `java -version`、`./gradlew --version`，确保 shell 与 IDE 都指向 JDK 17；不要修改 Wrapper 脚本绕过检查。 |
| `SDK location not found` | 检查 `ANDROID_HOME`、Android Studio SDK Location 和本机 `local.properties` 是否指向同一 SDK；不要提交 `local.properties`。 |
| 缺少 Platform/Build Tools/NDK/CMake | 对照第 4.2 节重新运行 `sdkmanager`，再用 `--list_installed` 验证；不要随意把项目版本改成机器碰巧已有的版本。 |
| `adb` 显示 `unauthorized`/`offline` | 解锁设备并确认调试指纹，重新插拔或冷启动 AVD；仍失败时先停止测试，不用 `pm grant` 或关闭安全检查规避。 |
| 有多个设备，测试跑错目标 | 用 `adb devices -l` 确认目标，在当前终端临时设置 `ANDROID_SERIAL` 后再运行 connected test。 |
| AVD 无法启动或极慢 | 核对镜像 ABI 与主机架构，优先冷启动并检查可用磁盘/虚拟化；不要把 x86_64 结果记录成 arm64 证据。 |
| 依赖突然要求 compileSdk 37 | 先检查 version catalog、lockfile 和依赖 diff；当前基线保持 compileSdk 36，不在普通功能任务中升级 SDK。 |
| Gradle 输出 Kotlin 2.3.20，但 catalog 是 2.3.21 | 前者是 Gradle 自带 Kotlin，后者才是项目 Kotlin plugin；以 catalog 和 resolved dependency 为准。 |
| Unit test 通过但功能仍异常 | 查看当前 HXA 的 verification matrix；涉及 Android/Room/WebView/权限/Runtime 时补跑指定设备测试和真实边界 fixture。 |
