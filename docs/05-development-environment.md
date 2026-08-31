# Helix 开发环境与依赖基线

基线日期：2026-08-31。版本用于可重复启动，不代表永久最新。任何升级必须单独任务、更新锁文件并通过全量门禁。

## 1. 主机要求

推荐：

- macOS 14+、Linux x86-64 或 Linux arm64。
- 内存至少 16 GiB，推荐 32 GiB。
- 可用磁盘至少 30 GiB；加入 PRoot/多模拟器后推荐 60 GiB。
- Git 2.40+。
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

使用 Android Studio 当前稳定版，JDK 指向 17。命令行包：

```bash
sdkmanager \
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

验证：

```bash
java -version
adb version
sdkmanager --list_installed
```

`java -version` 必须显示 17。不要依赖开发者机器全局 Gradle，所有构建使用 `./gradlew`。

## 5. 模拟器和真机矩阵

### 5.1 每次 PR

- 当前无设备 CI：JVM unit tests、Spotless、Detekt、双变体 Lint、四个 M0 APK、依赖/变体/文档扫描。
- 含 Android UI、生命周期、Room migration 或平台能力改动的 PR：在合并前增加 API 36 instrumentation 证据；可先使用开发机 AVD，远端 emulator job 建立后再强制自动运行。
- 当前 Apple Silicon 开发机使用 API 36 arm64-v8a AVD；Linux CI 可使用 x86_64。报告必须写真实 ABI，不能把一种架构的结果冒充另一种。

### 5.2 每个里程碑

| 设备 | 架构/API | 用途 |
| --- | --- | --- |
| Emulator A | x86_64 / API 29 | 最低版本行为 |
| Emulator B | x86_64 / API 36 | 当前 target、自动测试 |
| Device A | arm64 / API 34 或 35 | 主流真机、功耗/后台 |
| Device B | arm64 / API 36 | 新系统、真实 16 KiB page、QuickJS/PRoot 全部 ELF/smoke、前台限制 |
| Low-memory device | arm64 / 4–6 GiB RAM | QuickJS/PRoot 资源压力 |
| Rooted test device | arm64 / API 34+ | libsu 授权、拒绝、RootService；不得使用主力手机 |
| Secondary automation device | arm64 / API 34+ | Accessibility 跨 App 和敏感界面拒绝 |

QuickJS、WebView、PRoot、Accessibility、Root 和 CLI Runtime 不能只在模拟器验收。Root/Accessibility 自动化使用专用测试设备和自建 fixture App。

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
./gradlew lintConsumerDebug
./gradlew connectedConsumerDebugAndroidTest
```

安装：

```bash
adb devices
adb install -r app/build/outputs/apk/consumer/debug/app-consumer-debug.apk
```

PRoot/CLI 开发者模式安装顺序：先安装与主 App 同签名证书构建的所需 Runtime APK，再安装 developer 主 App；启动时执行协议、版本和签名握手。不要为调试关闭签名校验。

不要把 `adb shell pm grant` 当正常用户权限流程。权限必须从产品 UI 引导用户在系统界面授予。

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

workflow 的 action 均固定到 commit SHA，但仓库尚未推送，因此目前只有本地等价命令证据，没有远端 run。HXA-003 已在本机 API 36 arm64-v8a AVD 通过 instrumentation。

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

新开发者或编码 Agent 在没有任何业务 secret 的情况下，必须能够：

```bash
git clone <helix-repository>
cd Helix
./gradlew :app:assembleConsumerDebug test lintConsumerDebug
```

若还需手工下载未记录文件、修改绝对路径或复制他人 `local.properties` 才能构建，则开发环境文档不合格。

继续当前仓库开发时先读[小模型继续开发交接](small-model-handoff.md)，其中记录了本机已安装环境与交接约定；Git 基线（`e5e3558` 起，每完成一个 HXA 提交一版）与里程碑状态（M1 已完成、M2 待授权）以 [implementation-status](implementation-status.md) 为准。交接文件是时间点快照；与 `git status`、实际 SDK 或测试冲突时，以实时检查为准并同步更新交接。
