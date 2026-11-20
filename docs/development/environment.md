# Helix 开发环境与依赖基线

本文用于配置开发机与理解依赖选择。确切版本以 [version catalog](../../gradle/libs.versions.toml)、[Wrapper](../../gradle/wrapper/gradle-wrapper.properties)、模块构建配置和锁文件为准；下列表格便于阅读，不构成第二套锁。升级按影响验证并更新锁/校验元数据，涉及 Release/native 边界时执行相应完整门禁。

配置原则：仓库内只保存版本、相对命令和验收规则，不保存用户名、设备序列号、真实 API Key、签名材料或某台机器的绝对 SDK 路径。历史开发会话中的环境快照只能帮助找线索；当前事实必须由本文列出的命令、Gradle 配置和实际设备输出共同证明。

## 1. 主机要求

推荐：

- macOS 14+、Linux x86-64 或 Linux arm64。
- 内存至少 16 GiB，推荐 32 GiB。
- 可用磁盘至少 30 GiB；加入 PRoot/多模拟器后推荐 60 GiB。
- Git 2.40+。
- Bash、`ripgrep`、`unzip`、`strings`，以及 `sha256sum` 或 `shasum`；仓库门禁会直接调用它们。
- 真实平台验收需对应设备；主机构建不要求连接真机，设备操作按 AGENTS 区分项目模拟器预授权与真机的当前任务明确授权。

当前主机门禁及 Developer APK 的 RootFS 压缩打包需要 Python 3.11+（含标准库 `lzma`）；
不要求为普通 Android 构建安装 Docker、Node.js 或 Rust。官方
CLI/SDK Android 打包路线已停止；当前第三方订阅协议适配器是 Kotlin/Android 模块，内置 developer APK，不要求 Node.js
或官方 CLI 工具链。脚本必须显式检查，不能依赖开发机偶然已有版本。

## 2. 固定 Android 工具链

| 组件 | 基线 | 理由/来源 |
| --- | --- | --- |
| JDK | 17 | AGP 9.3 默认/最低 JDK |
| Android Gradle Plugin | 9.3.2 | 9.3 当前补丁版；修复 9.3 lint/JDK 17 问题 |
| Gradle Wrapper | 9.5.0 | AGP 9.3 最低/默认 |
| Kotlin Android plugin | 2.3.21 | Android 官方 AGP 9.3 示例组合 |
| compileSdk | 37 | HXA-197 termlib 0.2.1 要求；targetSdk 仍为 36 |
| targetSdk | 36 | 与 compileSdk 分别维护，不从编译版本推断目标行为 |
| minSdk | 29 | 统一 scoped storage 时代行为，降低兼容分支 |
| SDK Build Tools | 36.0.0 | AGP 9.3 默认 |
| NDK | 28.2.13676358 | AGP 9.3 默认；native/PRoot 准备 |
| Compose BOM | 2026.09.00 | HXA-197 显式兼容升级；具体设备范围见任务证据 |
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
| Compose | `androidx.compose:compose-bom` | `2026.09.00` |
| Lifecycle | `androidx.lifecycle:lifecycle-*` | `2.10.0` |
| Activity Compose | `androidx.activity:activity-compose` | `1.13.0` |
| Navigation Compose | `androidx.navigation:navigation-compose` | `2.9.8` |
| Room | `androidx.room:room-*` | `2.8.4` |
| WorkManager | `androidx.work:work-runtime-ktx` | `2.11.2` |
| HTTP/SSE | `com.squareup.okhttp3:okhttp`, `okhttp-sse` | `5.5.0` |
| JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.9.0` |
| QuickJS | `app.cash.zipline:zipline` | `1.27.0` |
| 图片 EXIF | `androidx.exifinterface:exifinterface` | `1.4.2`（全量回归修复平台 EXIF Lint，保留 ADR-AGENT-003 边界） |
| WebView compat | `androidx.webkit:webkit` | `1.17.0` |
| MCP Client | `io.modelcontextprotocol:kotlin-sdk-client` | `0.15.0` |
| MCP HTTP engine | `io.ktor:ktor-client-okhttp`, `ktor-client-sse` | `3.5.2` |
| Root | `com.github.topjohnwu.libsu:core`, `service` | `6.0.0` |
| Unit test | JUnit4 | `4.13.2` |
| AndroidX test | core/runner `1.7.0`、ext JUnit `1.3.0`、Espresso `3.7.0` | version catalog 固定 |

说明：

- Activity/AndroidX test 等依赖以 `gradle/libs.versions.toml` 为当前事实源；升级按第 12 节验证，本文只同步仍有使用价值的解释。
- 不引入 Hilt/Koin；采用手工 `AppContainer`。
- 不引入 LangChain4j/Semantic Kernel；自研有限 Agent Loop。
- Provider 流协议直接使用 OkHttp；Ktor 只封装在 MCP module，因为官方 SDK 依赖 Ktor。
- HXA-077 / accepted ADR-A2A-001 选择 Helix 自有 `A2aClientFacade` + 既有 OkHttp 5.5.0 / `okhttp-sse` 5.5.0 / kotlinx.serialization 1.9.0 实现最小 A2A v1.0 Client；官方 A2A Java SDK 1.3.1.Final 只保留在 `:spikes:a2a-sdk` 作为 JVM/R8 决策证据，不得进入 production module。该 Spike 不代表当前全产品验收；后续实现及验证范围见 [A2A ADR](../adr/a2a/001-client-interoperability.md) 和 HXA-077～079 完成记录。
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
  "platforms;android-37.0" \
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
- `sdkmanager --list_installed` 必须至少包含 Platform 36 与编译用 Platform 37.0、Build Tools 36.0.0、Platform Tools、NDK 28.2.13676358 和 CMake 3.31.6。
- 不依赖开发机的全局 Gradle，所有构建只使用 `./gradlew`。

## 5. 模拟器和真机矩阵

设备覆盖选择、AVD 创建、并行占用、真机安装及自建模型连通性调试已集中到[设备配置与调试](device-setup.md)。授权和测试证据分别遵循 AGENTS 与验收矩阵。

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

依赖替换必须放在实际解析该依赖的 producer 模块，并覆盖 lint、unit、Android/R8 等相关
configuration；只在 `app` 做 substitution 不能修复 extension/library 自己解析到的不兼容
variant。每次替换后检查 producer 模块的 dependency insight 与 lock diff，并运行会解析该
variant 的 lint/build。若 Android artifact 要求高于项目基线的 compileSdk，先判断是否存在
同版本、同 API 契约的 JVM artifact 或可隔离 adapter；普通功能任务不得为通过解析临时升级
compile/target SDK，也不得在未验证的配置中全局强制版本。

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

### 6.4 Build variants 与内置 Runtime

```text
主 App：consumerDebug / consumerRelease
主 App：developerDebug / developerRelease
PRoot library：:runtime:proot-app:assembleDebug / assembleRelease
CLI Runtime library：:runtime:cli-app:assembleDebug / assembleRelease
```

developer APK 内置 PRoot/RootFS 与订阅 Runtime 模块，通过非导出的 `:proot`、`:subscriptions` 私有进程按需运行；这些进程共享主 App UID、数据访问和网络权限，不是独立 APK，也不提供离线或凭据隔离保证。consumer 排除这些模块，QuickJS 仍使用 isolated UID。CI 验证最终 APK 的组件、进程及制品边界，契约见 [ADR-RUNTIME-001](../adr/runtime/001-execution-domains.md)。

发布角色与 Gradle 名称分开：Standard 是 Google Play、国内 Android 应用商店与官网的完整产品；当前 consumer/developer 只用于开发和边界测试，不预设哪个等于最终商店包。开发、CI 和路径继续使用现有 flavor 名，未经 HXA-122 的 applicationId/签名/channel 决定不得机械重命名。`proot-app` / `cli-app` 是现有模块名称，不表示需要用户另外安装 Runtime APK。

applicationId 基线：`consumer=com.helix.agent`、`developer=com.helix.agent.developer`；Runtime library 没有独立 applicationId。变体使用 `developerImplementation` + `src/developer` 隔离 Advanced 模块，不仅依赖运行时 feature flag 隐藏 consumer 入口。

Release 已启用 R8 代码与资源优化，Debug 保持可调试。默认 APK 含 arm64-v8a/x86_64；分别生成未签名分发候选并验证最终边界：

```sh
./scripts/build-release-apks.sh all
# 只构建手机 ARM64 候选：
./scripts/build-release-apks.sh arm64-v8a
```

产物、对应 mapping 和校验值保存在 `build/distributions/<abi>/`。单次 Gradle 构建也可传
`-Phelix.abi=arm64-v8a` 或 `-Phelix.abi=x86_64`；普通验证不传此参数。构建成功不代表
正式签名、发布或设备验收完成；x86_64 包不提供仅 ARM64 支持的 PRoot/FFmpeg 执行。
RootFS 以 XZ 存储，安装后内容不变；详见[本轮验证](../evidence/development/apk-size-optimization-2026-10-15.md)。

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

下图是目标模块/目录布局，不是实时实现清单。目录存在、可参与 Gradle 构建或包含 marker 都不表示业务能力已落位；当前模块以 `settings.gradle.kts` 和源码为准，交付事实见[实施状态](status.md)，执行任务由当前用户请求决定。

```text
Helix/
├── .github/workflows/
├── app/
├── core/
├── provider/
├── extensions/
│   ├── mcp/
│   ├── a2a/                  # 已有有界客户端实现
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
│   ├── product/
│   ├── architecture/
│   ├── development/
│   ├── security/
│   ├── references/
│   ├── adr/
│   ├── completion-records/
│   ├── bug-fixes/
│   ├── evidence/
│   ├── research/
│   └── postmortems/
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
```

设备 instrumentation 不属于默认主机命令。按改动影响及 AGENTS 对相应设备的授权范围执行，例如：

```bash
./gradlew connectedConsumerDebugAndroidTest connectedDeveloperDebugAndroidTest
```

GitHub Actions 不执行上述 device 命令。

仅在当前任务已有对应设备/安装授权时安装 developer 测试构建；先核对指定设备，命令显式绑定其 serial：

```bash
adb devices
adb -s OWNER_AUTHORIZED_SERIAL install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk
```

consumer 是完整 Standard 产品渠道，需要验证时按授权单独安装 `app-consumer-debug.apk`；其商店资格和最终发行 artifact 由 HXA-120～123 决定，Debug 安装不证明商店验收。

测试 PRoot/CLI 时安装 developer 主 APK，按对应用例触发验证、修复、登录或已授权 Job，私有 Runtime 再冷绑定；不另外安装 companion APK。普通启动、切换 Advanced 或被动刷新不得启动 Runtime。不要为调试绕过组件身份、协议或版本校验。

不要把 `adb shell pm grant` 当正常用户权限流程。权限必须从产品 UI 引导用户在系统界面授予。

### 9.1 日常最小反馈环

按 [verification matrix](verification-matrix.md)选择相关检查。纯文档改动检查文档/链接和语义；源码先用受影响模块测试定位，再完成当前任务要求的门禁，日常默认 Debug：

```bash
./gradlew :core:model:test # 示例；替换为当前 HXA 的最窄任务
./gradlew spotlessCheck detekt
./scripts/check-docs.sh
./scripts/verify-adr.sh
git diff --check
```

Android UI、持久化恢复、生命周期或平台能力变化时，按影响准备对应 flavor 的设备用例；按 AGENTS 对应设备的授权范围执行。被要求的设备验收未完成时不能宣称该验收通过，未要求的设备矩阵不自动阻断独立主机交付。不要用 `./gradlew test` 替代设备证据，也不要为凑绿临时升级依赖或降低 compile/target SDK。旧开发测试数据不新增 App 迁移链，数据处置与当前状态恢复边界见[验收参考](verification-matrix.md#设备恢复与发布)。

## 10. PRoot 资产构建环境

构建 PRoot 资产时按现行脚本准备：

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

当前 [Android CI](../../.github/workflows/ci.yml) 按变更选择 source、debug、full，具体门禁、缓存、手动完整验证和证据见 [CI 分层](verification-matrix.md#history-ci)。Action 固定 commit SHA；当前制品是 consumer/developer 两个主 App Debug APK，PRoot/订阅 Runtime 内置于 developer，不再是额外 Runtime APK。

远端 CI 不运行模拟器、真机或付费模型；HXA 设备、真实账号和发行验收分别执行。CI 缓存不包含 secret、Runtime home 或真实 Provider/A2A 响应。不要用源码检查通过推断 Android 构建通过，也不要用 Debug 档代替 HXA 完整主机门禁。

## 12. 版本升级流程

兼容/维护所需升级在当前授权范围内处理，不为每次升级另建固定流程。先核对官方 release note、breaking changes 与实际消费者；更新 version catalog、锁和校验元数据，复核依赖差异、repository、动态代码与许可证。

按受影响边界验证：普通库完成相关测试/静态分析和构建；Release、native、JNI/反射影响执行完整相关门禁。QuickJS/NDK 的隔离与 16 KiB page、PRoot/RootFS 的 manifest/许可和安装失败恢复、MCP/A2A 的 Android/R8/transport、Skill 规范 fixture、WebKit/libsu 的权限与攻击边界不能因升级后编译通过而略去。现有任务明确要求的用例仍必过；研究 Spike 仅在其假设仍受本次变化影响时执行。

真机/模拟器用例按当前 owner 授权准备与执行，未要求或尚未完成如实记录，不把主机验证当成设备通过。不增加旧 Helix 开发数据迁移链，不自动重放未知效果或清理数据。这里讨论外部依赖版本，Helix 自有版本仍按 [AGENTS.md](../../AGENTS.md)固定。

## 13. 新环境验收

### 13.1 从零启动清单

1. 安装 JDK 17、Android Studio 与第 4 节 SDK 包。
2. 设置 shell 环境变量，并让 Android Studio Gradle JDK 指向同一 JDK 17。
3. Clone 仓库；允许 Android Studio 生成本机 `local.properties`，但确认其被 Git 忽略。
4. 执行工具链诊断和 Gradle Wrapper 验证。
5. 只做主机构建时无需设备；当前已获授权的设备任务再准备/启动相应 AVD 或连接指定真机。
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

继续当前仓库开发时，按当前用户请求及有效交接摘要续做；[实施状态](status.md)用于核对事实与开放边界，不提供自动启动的 `In progress` / `Next task` 队列。已有完成记录的 HXA 不重复实现。分支、commit、CI、SDK 和设备均以实时检查为准，不在长期文档中固化机器快照。

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
| 共享模拟器上安装失败或测试类突然不存在 | 停止继续覆盖安装，确认设备是否被其他任务占用，并核对主包、test APK、companion、applicationId 和 provider authority；无法确认空闲时改用独立 AVD。 |
| AVD 无法启动或极慢 | 核对镜像 ABI 与主机架构，优先冷启动并检查可用磁盘/虚拟化；不要把 x86_64 结果记录成 arm64 证据。 |
| 依赖突然要求 compileSdk 37 | 先检查 version catalog、lockfile 和依赖 diff；197 已显式升级 compileSdk 37，targetSdk 仍为 36；不得绕过依赖 metadata。 |
| Gradle 输出 Kotlin 2.3.20，但 catalog 是 2.3.21 | 前者是 Gradle 自带 Kotlin，后者才是项目 Kotlin plugin；以 catalog 和 resolved dependency 为准。 |
| Unit test 通过但功能仍异常 | 查看当前 HXA 的 verification matrix；涉及 Android/Room/WebView/权限/Runtime 时补跑指定设备测试和真实边界 fixture。 |
| instrumentation 报 `Failed to inject touch input` | `androidx.compose.ui.test` 的误导包装，真实原因在紧跟的 `Reason:` 行，多为 `could not find any node`（目标不在组合树里）而非输入注入失败；按 `Reason` 定位，应核对节点是否存在、是否需要滚动、当前屏幕/字体与布局是否匹配；不能一律提高分辨率掩盖窄屏缺陷（见[AVD 配置](device-setup.md#3-创建-helix-avd)）。 |
