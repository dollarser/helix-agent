# main 合并后全量验证

日期：2026-09-05～2026-09-06。基线：`61bad35`，工作分支 `main`。本轮由所有者授权验证
当前 main 的 M0～M10 并收尾实际失败；物理真机不在本轮范围。

## 验证范围

覆盖全仓 JVM、全部 Android 库 Lint、consumer/developer Debug 与 Release Lint、
四个应用 Debug/Release 构建、所有已有 Android instrumentation source set、
MCP/A2A 字节码 Spike、供应链/文档/国际化/脚本门禁，以及模拟器 host lifecycle。
官方 A2A SDK 仅在 JVM/R8 中作预期拒绝 Spike，没有 Android instrumentation source set；
不把无测试 source set 的自动生成 APK 当作产品验收目标。

M13 的真实账号与用户来源样本仍按 HXA-125 记录管理，本轮全仓常规测试会覆盖已合并代码，
不把环境门控跳过当作真实服务通过。签名发布、商店提交、物理设备和真实多 Provider
完整评测保持各自验收边界。

## 结果与证据

完整命令日志、首次失败和 XML 快照保留在本地 `build/main-verification/`。
通过统计仅使用最终复测；Gradle 的 console 会把 assumption case 重复计入 Finished，
因此 Gradle instrumentation 数量采用 XML 的 `tests/failures/errors/skipped`，不直接抄 console 总数。
最终 consumer/developer 在两台设备直接运行完整 AndroidJUnitRunner，无 class 过滤；
按逐 case status 分别统计 145/204 个唯一结果，并检查最终 OK 与无 crash/failure。
最终应用日志与 JSON 为 `app-*-api*-settled.*`，JVM 快照为 `jvm-settled-results/`，
构建和静态检查日志为 `host-settled.log`。

| 验证项 | 当前结果 |
| --- | --- |
| 全仓 JVM（强制执行，禁用 Test up-to-date/cache） | 258 suites，2,421 tests，0 failures/errors/skipped |
| Spotless / detekt | 通过 |
| 全部 Android 库 Debug/Release Lint、App 双 flavor Debug/Release Lint | 通过 |
| consumer、developer、PRoot、CLI 的 Debug/Release APK | 8 个构建通过；Release 未签名不等于发布 |
| MCP / A2A minimal / A2A SDK Android Spike | 三脚本 exit 0；SDK 的 java.net.http 拒绝是预期的 negative gate |
| 依赖锁/Secret/ADR/文档/i18n/CLI lock/variant boundaries | 全部通过；35 个依赖锁 |
| Python 脚本测试 | 2/2 |
| PRoot 资产 | 当前 RootFS 字节数与 SHA-256 对锁一致；360 个 ELF，最小 PT_LOAD 16,384，arm64 ABI gate 通过 |
| API 34、35 资源/前后台/旋转 | App 各 7/7，WebView resource 各 2/2，0 skipped |
| API 35 / 16 KiB arm64 | QuickJS 75/75，浏览器 27/27，0 skipped |
| consumer 应用完整回归 | API 29：145 tests / 6 skipped；API 36：145 / 5；均 0 failures/errors，host 项另跑 |
| developer 应用完整回归 | API 29：204 tests / 15 skipped；API 36：204 / 14；均 0 failures/errors，host 项另跑 |

常规应用 suite 的跳过项为：两种 flavor 各 5 个 Connector host 阶段；developer
另有 8 个 PRoot host 阶段及 1 个 All-files 授权前提。API 29 两种 flavor 还各有
1 个 API 33 语言同步不适用项。Host 阶段和 API 36 All-files 已授权路径另行执行，
不将这些 assumption 计作普通 suite 的通过 case。

### API 29/36 全部库 instrumentation

All-files 的 API 36 已授权 happy path 另以真实 appops 授权执行通过；测试后恢复原默认状态。
API 29 不存在该系统 grant，按 API 不适用处理。

以下每格是 `tests / skipped`，全部 0 failures/errors。PRoot 与 automation 的常规
suite 跳过项随后通过宿主脚本实际执行；Root 的物理设备项不在本轮范围。

| 模块 | API 29 | API 36 |
| --- | --- | --- |
| core:storage | 45 / 0 | 45 / 0 |
| feature:files | 38 / 0 | 38 / 0 |
| feature:browser | 27 / 0 | 27 / 0 |
| runtime:quickjs | 75 / 0 | 75 / 0 |
| runtime:proot-app | 57 / 1 | 57 / 4 |
| runtime:cli-app | 3 / 0 | 3 / 0 |
| tools:android | 30 / 0 | 30 / 0 |
| tools:automation | 3 / 2 | 3 / 2 |
| tools:root | 6 / 1 | 6 / 1 |
| spikes:a2a-minimal | 3 / 0 | 3 / 0 |
| spikes:bounded-orchestration | 2 / 0 | 2 / 0 |

### 宿主分阶段与真实服务

以下脚本在 `emulator-5590`（API 29）及 `emulator-5592`（API 36）分别 exit 0：

- `scripts/accept-hxa-083-lifecycle.sh`：8 个跨 APK 冷绑定、进程死、force-stop、卸载与修复阶段。
- `scripts/accept-hxa-086-lifecycle.sh`：安装/冷绑定、空闲/熄屏回收、主进程与 companion
  在途死亡、同 jobId ORPHANED 对账、wake-lock/FGS 采样、重复 jobId、smoke/isolation/通知停止。
- `scripts/accept-hxa-087-updates.sh`：7 项 companion + 1 项 anchor + 4 项 App update/legal。
- `tools/automation/scripts/run-hxa093-force-stop-matrix.sh`：实际强停和新进程后 session/token/通知不恢复。
- `scripts/accept-hxa-124-connectors.sh`：6 项常规/UI 与 seed/recover 两个不同 PID 阶段。
- `scripts/accept-hxa-125-connectors.sh`：匿名真实 Cloudflare 文档服务调用与跨进程连接恢复。
- `scripts/accept-hxa-125-sample.sh`：交接目录中的用户 ZIP 经真实 Reader/Importer 导入；不执行依赖。

上述 Connector 脚本覆盖 consumer；developer 也在两台设备各实际执行 5 个
seed/recover、真实服务与原包导入阶段，全部通过且无 assumption。日志为
`developer-connectors-api29.log` / `developer-connectors-api36.log`。

JVM 的公开来源与原包测试本次均显式启用并实际通过，不再记为缺失输入。
HXA-125 的独立账号、OAuth/订阅凭据和未提供的真实来源仍属于该任务的外部验收。
本地 Ollama 和现有 SGLang 仅作真实服务 smoke，不替代多协议/多模型逐 case 的完整评测。

## 可复跑命令

环境为 JDK 17、项目固定 SDK/Gradle/依赖；所有设备命令都显式绑定专用 emulator serial。
API 34/35/16 KiB 额外 AVD 由本轮启动、测试后关闭；其他工作线设备未操作。

```bash
# 本轮用本地 init script 为所有 Test task 设置 outputs.upToDateWhen { false }
# 及 outputs.cacheIf { false }，防止以历史缓存充当本次执行。
HELIX_CONNECTOR_ACCEPTANCE_DIR="$PWD/app/build/outputs/hxa-125-samples" \
HELIX_CONNECTOR_SAMPLE_ZIP="$PWD/app/build/outputs/connector-handoff-672dc5a/inputs/connector-参考包.zip" \
./gradlew test spotlessCheck detekt lintDebug lintRelease \
  lintConsumerDebug lintDeveloperDebug lintConsumerRelease lintDeveloperRelease \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerRelease :app:assembleDeveloperRelease \
  :runtime:proot-app:assembleDebug :runtime:proot-app:assembleRelease \
  :runtime:cli-app:assembleDebug :runtime:cli-app:assembleRelease \
  --init-script build/main-verification/force-tests.gradle --continue --no-configuration-cache

# 对 API 29、36 分别执行。按真实 source set 枚举，不生成无测试模块的 phantom APK。
ANDROID_SERIAL=emulator-5590 ./gradlew \
  :core:storage:connectedDebugAndroidTest :feature:files:connectedDebugAndroidTest \
  :feature:browser:connectedDebugAndroidTest :runtime:quickjs:connectedDebugAndroidTest \
  :runtime:proot-app:connectedDebugAndroidTest :runtime:cli-app:connectedDebugAndroidTest \
  :tools:android:connectedDebugAndroidTest :tools:automation:connectedDebugAndroidTest \
  :tools:root:connectedDebugAndroidTest :spikes:a2a-minimal:connectedDebugAndroidTest \
  :spikes:bounded-orchestration:connectedDebugAndroidTest --continue --no-parallel --no-configuration-cache

# 每个 serial 的 consumer/developer 分别完整执行；真实模型明确选择。
ANDROID_SERIAL=emulator-5590 ./gradlew :app:connectedConsumerDebugAndroidTest \
  :app:connectedDeveloperDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.helix.smoke.model=qwen2.5:3b-instruct \
  -Pandroid.testInstrumentationRunnerArguments.helix.smoke.ollamaUiModel=qwen3-vl:2b-instruct \
  --no-parallel --no-configuration-cache

# 本轮两种 flavor 最终完整复测使用无 class 过滤的等价 instrumentation（两台均执行）。
# consumer 对应 package 为 com.helix.agent.test。
# 先安装上方构建的 developer APK 与 AndroidTest APK。
adb -s emulator-5590 shell am instrument -w -r \
  -e helix.smoke.model qwen2.5:3b-instruct \
  -e helix.smoke.ollamaUiModel qwen3-vl:2b-instruct \
  com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner

python3 -B -m unittest discover -s scripts/tests
bash scripts/check-lockfiles.sh
bash scripts/check-secrets.sh
bash scripts/verify-adr.sh
bash scripts/check-docs.sh
bash scripts/check-i18n.sh
bash scripts/check-cli-runtime-lock.sh
bash scripts/check-mcp-android-spike.sh
bash scripts/check-a2a-sdk-android-spike.sh
bash scripts/check-a2a-minimal-android-spike.sh
bash scripts/verify-variant-boundaries.sh
git diff --check
```

## 失败与复测口径

首次全仓 Lint、隔离进程、图片缩放、分享 badge、PRoot 夹具与脚本失败均保留日志，
有机制性缺陷的修复与回归见下文。无测试 source set 的 SDK Spike APK、临时 rootfs
解压路径和测试驱动脚本调整中的失败不算产品通过，也不以忽略错误获得 exit 0。
文件预览 metadata 超时随后再次出现；补充长文本滚动回归并统一发布/滚动后复测。
SGLang 64-token 探测独立复现截断；快速 foreground start/stop 也复现迟到系统异常，
均按机制修复并保留原失败日志，不以重跑成功掩盖。

## 未计作本轮通过的边界

物理 rooted grant/revoke/loss、RootService crash 和高阶读取仍按 HXA-094/095 保留；
不使用 emulator root 替代物理 Root 验收。物理低内存、OEM 热限制、secure keyguard/Doze、
x86_64 实际运行（当前 arm64 主机仅有静态 ABI/ELF 证据）、
24 小时长稳、真实 crash/ANR 联合诊断、HXA-105 的 30 分钟真机收益对照、签名与商店发布
也没有因本轮短时测试而关闭。M0～M10 的实现/模拟器门禁与 release acceptance 是不同结论。


## 已发现并修复的合并收尾项

- 库 Lint 未进 CI，漏掉 All-files `Path.of` 的 API 34 依赖、图片 WebP API 30 常量、
  平台 EXIF、QuickJS Bundle loader 以及 WebView renderer 退出处理。
- 图片编码 quality ladder 首次失败后以 scale=1 计算同尺寸立即退出，无法实际缩图；
  修复为基于原图有界比例重试，并在所有路径释放中间 bitmap。
- PRoot developer 专用资源留在 main，consumer Lint 报 unused；资源连同三语言移动到 developer。
- PRoot 法律页测试硬编码中文，英文系统下失败；断言当前 locale 的完整离线声明。
- QuickJS in-flight 取消夹具从测试线程启动后计时，实际可能仍在冷绑定；
  改为观察真实 EXECUTE Binder 事务后取消。超时测试使用含冷绑定的常规 10 秒预算，
  继续断言 TIMEOUT、时间上界、进程回收与下一次新实例成功。
- HXA-083/087 脚本仍引用旧 instrumentation runner；与当前 App runner 对齐。
- M10 bounded-orchestration Spike 漏交依赖锁，锁检查未枚举该模块；补齐后检查 35 个锁。


- `HelixApplication` 的语言/诊断初始化进入 QuickJS isolated UID，导致应用级 Binder 启动失败；
  两个初始化入口均隔离主应用存储与恢复逻辑。详见 [集成回归修复](../bug-fixes/2026-09-05-main-integration-regressions.md)。
- 无 Provider 的会话/分享草稿继承旧 Provider badge，隐藏显式绑定入口；
  以当前持久会话的 null 为准清除 badge。详见 [分享草稿修复](../bug-fixes/2026-09-05-share-draft-stale-provider-badge.md)。
- Connector recovery 两个方法需要不同进程，普通 suite 不具备该前提；明确 host phase marker，
  并在两台设备实际跑 seed/recover，防止把 assumption 的 OK 当作执行成功。
- PRoot descriptor 测试硬编码历史 lock hash，改为独立读取已安装 companion asset 并计算 canonical hash。
- Provider UI 保存需要等待 IO 提交后 dialog 关闭；`waitForIdle()` 不保证 Room/Keystore 完成。
- Ollama smoke 显式选模型；UI 连接失败立即报告阶段/类型，不再空等 180 秒。
  文本 smoke 的原 32-token 与实际 TextDelta 断言保留，不能用 reasoning-only completion 冒充文本成功。

- 工具能力探测的 64-token 默认预算会被思考内容耗尽；提升为有界 256，截断仍失败。
  详见 [探测预算回归](../bug-fixes/2026-09-05-provider-probe-token-budget.md)。
- 短 Turn 停止不能取消未确认的 foreground 启动；以 startId 确认后停止。
  详见 [前台服务回归](../bug-fixes/2026-09-05-data-sync-cold-start-promotion.md)。
- 文件正文与 metadata 改为完整读取后统一发布、共用滚动容器。
  详见 [文件预览回归](../bug-fixes/2026-09-05-file-preview-metadata-scroll.md)。

## HXA-102 收口期间：全仓 JVM、Lint 与构建统一复验

本轮在同一源码/配置快照执行根 `test`，通过 init script 强制所有 Test task 重新执行并禁用其输出缓存。启用现有 Connector 样本目录与交接 ZIP，包含公开文档 MCP 的只读查询，不调用受保护账号或执行样本代码。根任务计划与实际日志逐项匹配：34 个有源码 Test task 实际执行，1 个 NO-SOURCE，合计 **2,602/2,602、零失败、零跳过**。Consumer 291、Developer 303；此前两变体共 6 个 Connector 条件实例全部通过。这不构成 Connector 受保护账号或完整业务兼容性验收。

执行命令（JDK 17）：

```bash
HELIX_CONNECTOR_ACCEPTANCE_DIR="$PWD/app/build/outputs/hxa-125-samples" \
HELIX_CONNECTOR_SAMPLE_ZIP="$PWD/app/build/outputs/connector-handoff-672dc5a/inputs/connector-参考包.zip" \
./gradlew test lintDebug --init-script build/main-verification/force-tests.gradle \
  --continue --no-configuration-cache --no-daemon --max-workers=2

./gradlew lintRelease lintConsumerDebug lintDeveloperDebug lintConsumerRelease lintDeveloperRelease \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerRelease :app:assembleDeveloperRelease \
  :runtime:proot-app:assembleDebug :runtime:proot-app:assembleRelease \
  :runtime:cli-app:assembleDebug :runtime:cli-app:assembleRelease \
  --continue --no-daemon --max-workers=2
```

两组命令均 **exit 0**。根 lintDebug 18 模块、根 lintRelease 18 模块、App 四组变体 Lint 通过；App Consumer/Developer 及 PRoot/CLI Runtime 共 **8 个 Debug/Release APK** 构建通过。Root lint 与 App flavor lint 分开计数，NO-SOURCE 不算测试通过数；完整 Lint 已运行，不以 lintVital 的跳过冒充验证。

证据：`build/main-verification/full-main-jvm-lint-result.json`、`full-main-release-flavor-build-result.json`。记录任务范围、逐模块测试计数、条件实例、输入 hash、八个 APK hash；`full-main-jvm-lint-source.json` 的 1,031 个源码/配置文件在两组命令期间均未变化。构建和 Lint 可使用有效缓存，JVM Test task 本轮全部实际执行。

本轮未安装上述 APK，没有将主机侧门禁扩写为设备、签名/商店或真机验收。依赖锁/许可证/secrets 检查、同当前 APK 的固定评测与设备完整矩阵、剩余功能边界及第二阶段统一交互优化继续待办。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：依赖锁与辅助门禁

执行 `bash scripts/check-lockfiles.sh` 首轮发现 CLI Client 的 debugAndroidTestLintChecksClasspath 未完整落锁。保留原文件备份与首轮 diff，确认唯一变化是该 Lint 配置（其余运行/编译/Test 配置坐标未变），接受 Gradle 生成结果后重跑，**35 个依赖锁文件通过**。额外备份包含 settings-gradle.lockfile，共 36 文件，不能混同锁脚本的计数。`./gradlew :runtime:cli-client:lintDebug spotlessCheck --no-daemon --max-workers=2` 随后通过。

CLI 边界脚本首轮仍要求 PFD 实现在旧 Client 文件，并依赖被格式化的单行写法。更新到实际 Wire/RequestPipe/Awaiter 接线，保留 PFD 读写、取消、协议上限、Manifest/权限与 Consumer APK 排除检查；凭据扫描范围同时覆盖新拆出的 IPC 文件，匹配仅输出文件名。`bash scripts/check-cli-runtime-boundary.sh`（ANDROID_HOME 已配置）与 `bash scripts/verify-variant-boundaries.sh` 均通过。

其余结果：

- `bash scripts/check-secrets.sh`：通过。
- `bash scripts/check-i18n.sh`：312 个生产源文件、843 个资源 key，中英/中文资源一致。
- `python3 -B -m unittest discover -s scripts/tests`：2/2。
- `bash scripts/check-mcp-android-spike.sh`：minApi 29 的 R8 字节码检查通过。
- `bash scripts/check-a2a-sdk-android-spike.sh`：**预期拒绝符合检查结果**，原 SDK 缺失 java.net.http.HttpClient；不称 SDK Android 兼容成功。
- `bash scripts/check-a2a-minimal-android-spike.sh`：最小客户端 minApi 29 R8 通过。
- `bash scripts/check-cli-runtime-lock.sh`：七个固定制品下载 hash、锁元数据与 APK 不捆绑边界通过。OpenAI terms-of-use URL 自动读取返回 **403**，按脚本原规则保留记录；声明链接检查不是法律条款完整读取或分发授权。

日志与校验范围见 `build/main-verification/main-auxiliary-gates-result.json`；锁配置差异见 `cli-client-lint-lock-diff.json`。先前 2,602 JVM 与 APK/Lint 结果保持其原源码快照，本轮只补充 Lint 锁配置及边界脚本适配，不伪造重跑全量 JVM 的记录。PRoot 实际资产检查、设备矩阵、固定模型统一评测和功能/UI 剩余项继续；持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：PRoot 实际资产复核

直接核验当前 runtime-lock 与 APK 字节：RootFS 原始 tar **137,287,680 字节**、SHA256 与锁一致；其 apk 数据库的 **53 个包**版本/许可证元数据与锁及 ALPINE-README 表逐项一致。Debug/Release 两个 PRoot APK 内 **11 项资产**均与源资产逐字节一致，包含锁与许可证材料。

下载三个锁定上游 deb 并验证大小/hash，提取其中 proot、loader、libtalloc、libandroid-shmem，四个二进制与打包源文件逐字节一致。未运行下载的二进制、未执行 RootFS，也没有更新锁或重新构建 Docker RootFS。

从已验证 tar 提取所有常规 ELF 文件，加上四个 PRoot 侧二进制，执行 `./gradlew :runtime:proot-core:assetGate "-PassetGateArgs=--min-align 16384 --expect-machine 183 $PWD/build/main-verification/proot-assets-audit" --no-daemon --max-workers=2`（JDK 17）：**179 个 ELF、min PT_LOAD p_align=16384、e_machine=183，通过**。本次不重复计算符号链接别名，因此不能与历史整树遍历的 364 条路径计数直接比较。初轮相对路径在模块工作目录下不存在而失败；绝对路径复验通过，两轮均留证。

结果及两 APK hash 见 `build/main-verification/proot-assets-audit-result.json`，文件映射和上游对应关系在 `proot-assets-audit/manifest.json` 与 `upstream-binary-parity.json`。这是锁、打包资产、许可证元数据/材料一致性及 ELF 字节检查，不作为法律分发结论或设备运行验收。当前主机侧既定门禁已获得记录，设备矩阵、统一固定评测、剩余功能及交互优化继续；持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：当前 APK 的 API 34 模块与 CLI 恢复复验

2026-09-07，专用 Helix_Verification_Root_API34（arm64-v8a）执行当前构建：CLI client 模块 **16/16**、storage 模块 **47/47**，均无跳过或失败。覆盖 Binder/PFD/请求管道、绑定释放，以及 Room 迁移、约束、删除、审批凭据与配置存储测试。构建命令为 `./gradlew :runtime:cli-client:assembleDebugAndroidTest :core:storage:assembleDebugAndroidTest --no-daemon --max-workers=2`（JDK 17），通过。

再执行 `./gradlew :app:assembleDeveloperDebugAndroidTest :app:assembleDeveloperDebug :runtime:cli-app:assembleDebug --no-daemon --max-workers=2`，通过；安装当前 Runtime、developer 主包和测试包，逐包核对设备 base.apk SHA256 与构建文件一致。选定 CLI 跨 UID PFD/取消/Runtime 死亡恢复/客户端中断等待 **9/9**，无跳过或失败；四平台 RUNNING 用例各覆盖 cancel 与实际 Runtime 进程退出，按同一 jobId 查询及 reconcile，额外 observer 绑定下中断等待仍落为 CANCELLED。

完整命令、安装日志、五个 APK 的设备/主机 hash、测试输出及计数见 `build/main-verification/current-api34-modules/`（`result.json`、`recovery-apks.json`、`recovery-command.json`、`recovery-result.json`）。本组是模拟器和 debug model fixture 证据，不是四平台真实账号模型调用，不替代主 App 执行中死亡、API 29/36 矩阵、统一固定评测或长稳。持续 Goal 保持 active。

## HXA-102 收口期间：API 29/36 当前 APK 模块矩阵

2026-09-07，新建独立 `Helix_Main_Verify_API29`、`Helix_Main_Verify_API36` arm64 AVD，安装当前五个 APK 并验证设备 base.apk SHA256；两台安装内容完全一致。每台通过 **77/77**、无跳过：显式 Runtime 界面入口 1、CLI client 16、storage 47、CLI 跨 UID 恢复 9、四平台 Provider fixture 契约 4。记录含设备 fingerprint、命令、逐用例状态和 APK hash：`build/main-verification/current-api29-modules-ready/result.json` 与 `current-api36-modules-ready/result.json`。

首轮 API 29 在刚安装、尚未显式打开 Runtime 时，CLI client 16 项中 6 个注入绑定测试失败，实际为 `FORCE_STOPPED`；本地状态检查先于注入的 Binder 分支，符合生产冷启动约束。保留原始 `current-api29-modules/cli-client.log`、`result.json` 和 `current-module-matrix.log`。经 developer 的显式账户界面入口打开 Runtime 后复验全部通过；没有删除测试、修改生产门禁或调用登录/付费服务。绑定注入测试要求 Runtime 已安装且经显式打开，不能把首轮失败称为 API 29 Binder 平台缺陷。

Provider 契约使用 debug `helix-fixture`，覆盖 probe/普通模型流/usage/完成及聊天落库；跨 UID 恢复使用 fixture wait，覆盖取消、Runtime 进程退出和按原 jobId 查询、不重放。以上不等于供应商真实网络取消、主 App 执行中死亡或完整 App 设备矩阵通过。统一 45 项模型评测、其余后端中断与功能/UI 优化继续，长稳保持后置。

## HXA-102 收口期间：Goal API 29/36 两发行包回归与固定评测启动

2026-09-07，当前 consumer/developer 各在 API 29/36 执行 12 类、44 项 Goal 测试。首轮四组均有通知点击后可见 Goal 断言失败；API 29 developer 另有一次 Worker 启动超时。截图确认新装 AVD 的首次使用须知遮挡了 Goal 界面，修正已有用户提醒测试的首启前置条件后，四组均 **44/44，总计 176/176**，无跳过。没有修改生产通知逻辑或移除可见性/预算/不自动 Continue 断言。Worker 超时在整组重跑中未复现，原因未确定，不宣称修复其根因。详见 [缺陷记录](../bug-fixes/2026-09-07-goal-reminder-first-launch-fixture.md)。

原始失败在 `build/main-verification/current-goal-api*/`，修复后命令、安装包 hash 和逐用例结果在 `current-goal-fixed-api*/`；构建和 spotlessCheck 通过。本组含真实 loopback socket 的取消，不含分阶段宿主 kill 测试，不等于完整 App 验收。

同一 API 34 安装包的统一 45 项 SGLang 固定评测已启动，证据根目录 `build/main-verification/current-fixed45-api34/`。Provider 9、文件 4、JavaScript 4、Plan 3、浏览器 4、Skills 4 已通过（28 项）。MCP 四份设备记录写 PASS，但宿主记录 `mcp-004` 服务执行次数为 0，未满足必须在远端执行中取消的验收，**MCP 组保持 FAIL**；不按设备记录冒充通过。其他组仍运行，最终计数须按各组最终结果及宿主条件核实。评测期间未替换 API 34 的安装包；新增首启修复仅在 API 29/36 测试包上验证。

## HXA-102 收口期间：MCP 固定评测取消边界修正

首轮统一固定评测结束：同一安装包的 11 组共 45 项，41 项位于通过组，MCP 组保持 FAIL；完整 hash 一致性及组结果在 `build/main-verification/current-fixed45-api34/aggregate-result.json`。

MCP 取消不再按本地 RUNNING 后固定两秒触发，而是由本地 fixture 的执行开始探针确认真实 tools/call 已进入服务端后停止。新测试包 API 34 MCP **4/4**，宿主执行计数 **0/1/1/1**；原门禁未放宽。构建、Spotless、Detekt 和 Python 2/2 通过，详见 [缺陷记录](../bug-fixes/2026-09-07-mcp-eval-cancel-before-remote-start.md)。

新一轮统一评测根目录 `build/main-verification/fixed45-final-api34/`，MCP 已通过，其余 41 项正在同一新测试 APK 上重跑。不得将旧 41 项与新 MCP 4 项拼接为统一通过。未改生产模型/IPC/审批代码；持续 Goal active。

## HXA-102 收口期间：扩展核心矩阵及 Provider 测试修正

2026-09-07，16 类/87 项聊天、附件、审批、工具调度、迁移恢复、Provider UI、前台服务与导入导出测试：API 29/36 consumer 各 **87/87**；developer 各 **83/87**，均在四项 Provider discovery 的准备阶段遇到 `managed provider cannot be deleted`。原始结果在 `build/main-verification/current-core-api*/`。

清理助手仅删除可编辑配置，保留 M11 Runtime 管理行；Provider discovery/flow 的控件和状态选择限定到目标可编辑行，并滚动到目标后操作。中间的屏外控件/同名状态标签失败亦保留。最终 API 29/36 × consumer/developer 各 **5/5，总计 20/20**，无跳过；构建、Spotless、Detekt 通过。证据 `current-provider-ui-final-api*/`，详见 [缺陷记录](../bug-fixes/2026-09-07-provider-ui-managed-row-fixtures.md)。不将针对性通过伪写成新 APK 的完整 87 项重跑。

API 34 独立模块：files **38/38**，QuickJS **74/75**；`JsAbiAttackTest.deepNestingRoundTrip` 在主机 JSON 结构校验的递归路径抛 StackOverflowError，已列入实际待修复项，原深度上限/测试保持。证据 `build/main-verification/current-api34-extra-modules/`。另外八个模块测试 APK 构建通过仅作为构建结果，未执行的用例不计通过。

第二轮统一固定评测结束：相同安装 APK 的其他十组 **42 项通过**；Accessibility 首项 ui.click 返回 STALE_TOKEN，组内只保存 1/3 记录，后两项未执行。本轮仍非 45/45。结果与相同 APK hash 核验在 `build/main-verification/fixed45-final-api34/aggregate-result.json`；保留首轮 FAIL 与本轮 FAIL，不拼接旧 Accessibility 结果。下一步优先修复 QuickJS 实际栈溢出，并继续定位 Accessibility 时序及余下设备矩阵。持续 Goal active。

## HXA-102 收口期间：QuickJS JSON 栈溢出修复

将共享 JSON 结构校验由递归下降改为显式容器栈，保留 512 层上限和原有 UTF-8/语法/完整文档约束。原 API 34 `deepNestingRoundTrip` StackOverflowError 的 300 层实际 QuickJS 往返测试保持不变。修复后 JVM **85/85**，API 29/34/36 各 **75/75，总计 225/225**，无跳过；Debug/Release Lint、Spotless、Detekt 通过。详见 [缺陷记录](../bug-fixes/2026-09-07-quickjs-json-validator-stack-overflow.md)。

两发行包 Debug/Release 四 APK 重建通过，hash 见 `build/main-verification/json-stack-app-apks.json`；本轮只安装 QuickJS 模块测试 APK，未将新主 App 构建声称为已安装验收。JVM、模块设备 hash 与逐例输出在 `json-stack-jvm-result.json`、`json-stack-device/`。原 74/75 FAIL 与第一次静态失败均保留。主仓全量结果与固定评测仍按旧快照解释，后续须验证新构建；Accessibility stale token、其余设备/恢复/UI 范围继续，Goal active。

## HXA-102 收口期间：当前快照评测与扩展设备结果

Accessibility 评测改为模型真实 ui.snapshot 后选择 token、申请精确点击；不改生产失效机制，并移除 setup token 的测试审批捷径。API 34 三项真实 SGLang 场景 **3/3**：正常 snapshot→click、切换应用保持暂停、敏感 UI 拒绝。已安装含 QuickJS 修复的当前 developer 主包/测试包，详见 [缺陷记录](../bug-fixes/2026-09-07-accessibility-eval-current-snapshot.md)。

使用当前构建补跑核心 16 类：API29/36 × consumer/developer 四组各 **87/87，总计348/348**，无跳过；此前 developer Provider 四项准备失败现已在整组复验中通过。精确命令及各 APK hash 在 `build/main-verification/current-core-fixed-api*/`。API29 另通过浏览器 **27/27**（明确只选两项短时资源测试，未选长稳/原生对照长循环）、文件 **38/38**、Android 工具 **30/30**；证据 `current-api29-basic-modules/result.json`。

第三轮统一评测在 `build/main-verification/fixed45-current-snapshot-api34/` 继续。浏览器组在 real Provider probe 的 phase4 失败：`tool fixture did not complete a tool call (finishReason=stop)`；尚未开始四个 browser case，因此 count0、该组FAIL。这不是浏览器四项已执行后失败，也不得拿旧轮次四项通过补齐。其余组继续采集，整体45项仍未完成；后续检查模型连接探测失败的具体请求与验收语义。Goal active，长稳仍后置。

## HXA-102 收口期间：同一当前 APK 的固定45项结果

2026-09-07，`fixed45-current-snapshot-api34` 全部11组完成。browser原轮在Provider phase4返回stop而未形成工具调用，探测按既有规则失败，尚未执行browser用例。未修改代码、请求、模型配置或探测门禁，完成一次同配置复验后browser **4/4**。这只说明此次复验通过，不声明已找到或修复模型输出波动的代码根因。

选择本轮同一主包/测试包的结果（browser使用明确标记的本轮复验），对照不可变TSV逐ID核对：**45/45**，无缺项/重复；11组配置中的两个安装APK hash完全相同，并与当前构建文件逐字节hash一致。MCP/A2A宿主执行计数门禁均通过。证据 `build/main-verification/fixed45-current-snapshot-api34/aggregate-result.json`，其中 `allPassed=true`、`firstAttemptAllPassed=false`、`retryCount=1`；原browser FAIL路径与每个case文件均列出。不是使用此前不同APK的结果拼接，也不是首轮全过。

模型为真实本地SGLang；MCP/A2A和Automation目标仍是合成fixture，Root是rooted AVD，不扩大为物理设备或受保护账号验收。API36浏览器短时27/27、文件38/38、Android工具30/30亦通过（`current-api36-basic-modules/result.json`）。PRoot模块两API测试正在执行，宿主分阶段生命周期、其他设备和剩余功能/UI仍待完成；持续Goal active。

PRoot模块后续结果：API29、API36各 **56/56**，无跳过或失败；包括真实内置RootFS安装、执行、隔离、取消与通知模块用例。API36测试前显式授予专用Runtime通知权限；宿主专用 `ProotLifecycleHostSetupDeviceTest` 不在本组选择中，仍须随分阶段宿主脚本另验。两台Runtime与测试APK安装hash均核对，命令/逐例日志见 `build/main-verification/current-proot-api29/`、`current-proot-api36/`。这是模块模拟器验收，不冒充完整主App跨进程生命周期矩阵或真机。


## 2026-09-07：PRoot宿主前置修正与当前主机复验

HXA-083脚本在API29/36各八阶段通过。修正HXA-086脚本的前台 `am kill` 假前置：先退到后台，再确认主App PID消失；两API完整脚本均通过，包含执行中主App/companion死亡与job ID对账、通知停止、短时wake-lock采样和跨UID隔离。原前台kill返回0但PID不变的证据保留。独立forced idle脚本在两API各1/1通过，作业前后确认deep IDLE/force=true并恢复原配置。证据 `build/main-verification/proot-host-lifecycle/`；见 [修复记录](../bug-fixes/2026-09-07-proot-lifecycle-host-preconditions.md)。受控后台kill不是实际低内存压力，forced idle不是自然Doze/后台调度或真机/长稳验收。

QuickJS修复后的全仓 `test lintDebug spotlessCheck detekt` 使用禁用Test缓存/up-to-date的init脚本执行成功：34个有XML的实际Test任务共2,602/2,602，零failure/error/skip；PRoot Runtime一个NO-SOURCE、18个聚合test任务另列，根Debug Lint覆盖18模块。当前源码/配置1,057项hash与独立XML保存于 `post-json-full-host-source.json`、`post-json-full-host-xml/`，汇总为 `post-json-full-host-result.json`。全量Goal仍在推进，其他设备/功能/统一交互待办不由本项替代。


## 2026-09-07：Accessibility夹具清理与运行中强停

额外模块在API29/36各22/22通过：Root未授权5、Accessibility完整服务1、CLI Runtime本地契约16，零跳过，证据 `current-remaining-local-modules/`。后续宿主矩阵发现两处真实证据缺口并修复：空组件列表的settings命令缺少值，返回Bad arguments且残留component；同步setup结束后instrumentation已先杀掉服务，宿主force-stop晚于进程退出。前者改为删除空设置并读回验证，后者改为setup保持运行、发布ready，宿主验证live PID与通知后再强停。

修正后的两API均通过完整服务1/1、确认清理、运行中宿主强停及恢复1/1；setup被有意中断，不计为JUnit通过。旧失败和旧非运行中结果保留，当前证据 `build/main-verification/current-automation-live-force-stop/`；Spotless/Detekt、模块Debug Lint/测试APK构建通过。仅androidTest与测试宿主脚本改变，生产授权/服务行为不变。见 [清理修复](../bug-fixes/2026-09-07-accessibility-test-empty-settings-cleanup.md)、[强停顺序修复](../bug-fixes/2026-09-07-accessibility-host-kill-after-instrumentation-exit.md)。额外19类App回归（每发行包59项）已启动，尚未计为通过。


## 2026-09-07：其余App回归236项通过

20个实际测试类覆盖文件UI/导入导出、审批卡片、运行控制、系统能力、语音、图像、诊断、A2A与Connector等。API29/36 × consumer/developer各59/59，共236/236，零跳过，设备安装hash与主机APK一致，证据 `build/main-verification/current-additional-app-fixed-result.json` 及各组原始日志。首次选择器误用 `RunControlUiDeviceTest.kt` 文件名作为类名（实际是Mode与Settings两个类），四组各57项通过加1个初始化失败；原失败保留，修正选择器后完整复验，没有因此改生产代码。

CLI匿名端点初轮API36为4/4，API29为3/4；后者Grok连接失败，设备解析地址与宿主不一致。Wi-Fi重连未解决；空闲专用API29模拟器使用已实测的显式DNS参数重启后，解析已与宿主一致，匿名端点复验4/4通过（`current-cli-anonymous-endpoints-api29-dns-fixed/`）。原失败/只读DNS诊断与重启参数日志位于 `current-cli-anonymous-endpoints/`，不拼成同条件首轮全过。以上匿名检查不是账号登录/付费模型验收，凭据配置不变。


发行包入口差异追加复验：API29/36 × consumer/developer各2/2，总8/8、零跳过；consumer固定Standard/egress缺席与developer切换/egress流程通过。证据 `current-flavor-ui-result.json`。Accessibility宿主测试后的两设备设置另读回为enabled列表null、accessibility_enabled=0，见 `current-automation-live-force-stop/settings-post-check.json`。当前持续Goal仍active，Goal完成证据契约/剩余后端恢复与M11集成、统一交互优化继续；长稳仍后置。


## 2026-09-07：后端与语言/能力剩余矩阵

API29/36每台PRoot主App17/17、M11/MCP35/35，无跳过；前者覆盖真实跨APK作业、stdio取消/输出限额、锁基线变更及法律/移除入口，后者覆盖四订阅适配器合成模型契约、PFD对账、取消/Runtime死亡重绑与MCP Android流式/认证/限额等。证据 `current-proot-main-remaining/` 与 `current-m11-mcp-main/`，APK设备/主机hash一致；不扩写为真实账号或分发法律验收。

语言与all-files条件矩阵共38通过、4个明确assumption、零失败：API29两发行包各缺API33系统语言双向同步1项；API29 all-files实际授权不存在、API36拒绝授权组各有1项happy-path前提不满足。API36授权组6/6，原AppOp已恢复default。证据 `current-language-allfiles/`，不把4项assumption算成通过。

开始补Goal真实模型UI路径：从已有会话在界面创建Goal、第一次显式Continue至预算暂停、界面扩预算且不自动运行、第二次显式Continue，核对真实回复/持久Goal-run-Turn关联和计数。新用例构建中，尚无设备通过结论；完成判据仍受ADR-0028待决边界约束。


## 2026-09-07：真实Goal UI预算与显式Continue验收

新增共同Android用例 `GoalRealModelUiDeviceTest`，provider配置/连接测试与已有会话是前置fixture，Goal创建/预算修改/Continue全部通过实际界面点击。SGLang真实模型经OpenAI Chat Completions执行：API29/36 × consumer/developer共4/4、零跳过，累计8次Goal模型调用（连接探测另计）；每组两次回复、两个持久run、Goal-run-Turn绑定、预算计数与PAUSED/BUDGET_EXHAUSTED一致。创建与扩预算不自动运行，第二次显式Continue后仍不冒充Goal完成。清理仅针对自建Goal/session/provider。构建双测试APK、Spotless、Detekt通过；初次枚举名编译错误已修正，日志保留。

证据 `build/main-verification/goal-real-ui-current-matrix/verified-summary.json`、各组实际命令/安装hash/JSON与对话及预算截图。另有API29 consumer初次无截图1/1记录，未拼入当前四组结果。当前修改仅增加测试，生产代码不变。

视觉复核发现：长Provider/模型标识使会话头过高，第二条回复未自动跟随到完整可见位置；Compose assertIsDisplayed接受部分可见，不能将此次流程通过扩写为视觉验收。该项已加入统一交互阶段，要求兼顾自动跟随与用户上翻阅读。Goal完成条件绑定仍待ADR-0028决定，剩余各副作用后端中断/恢复矩阵继续。


## 2026-09-07：API 29/36 SIGKILL 信号权限修复与恢复扩展

API 29 首轮在发送信号时失败：`run-as kill` 报 `unknown pid`，但 PID 仍存在；shell builtin 与 SELinux AVC 进一步证明 runas_app → untrusted_app 的 SIGKILL 被拒绝。原始失败、失败 fixture 的显式 abort 清理与系统证据保留在 `build/main-verification/model-kill-api29-36/`。abort 只恢复自建 fixture 状态，不计恢复验收。

三个宿主脚本改用共享 `android_process_control.py`：只接受显式模拟器 serial，先验证已有 host su 0 权限，核对应用包/PID 后发送 SIGKILL，再核实 PID 消失。结果明确记录信号权限；不改 SELinux，不扩大应用 Root 权限。详见 [缺陷记录](../bug-fixes/2026-09-07-emulator-sigkill-selinux-authority.md)。

修复后 consumer API 29/36 × Chat/Responses/Anthropic × headers/body 共 **12/12**，实际 12 次 SIGKILL、24 次启动恢复断言，零失败/跳过。每组有真实 held HTTP、socket EOF、调用数 1、预算幂等、启动后请求计数不增长；使用宿主脚本模型。安装主 APK 与测试 APK 的 hash 均比对当前产物。证据 `build/main-verification/model-kill-api29-36-host-signal-fixed/verified-summary.json`。

同两台设备的 Goal 预算预留与删除强杀回归 **4/4**：每组两个真实强杀边界，累计 8 次 SIGKILL，各 control/final 断言通过；证据 `build/main-verification/goal-kill-api29-36-host-signal-fixed/`。这两类仍分别是预算窗口、生产删除协调器/Room/WorkManager fixture，不能替代工具执行中断。consumer 测试 APK 构建、Spotless、Detekt、四个 Python 脚本语法检查通过。实际 MCP/A2A/其他副作用后端 SIGKILL 与统一 UI 优化继续，整体 HXA-102 和开发 Goal 尚未完成。


## 2026-09-07：MCP 执行中真实主进程 SIGKILL

新增 developer `McpProcessKillDeviceTest` 与 `scripts/run-mcp-process-kill.py`。通过生产 Goal/Chat/Dispatcher、精确工具审批、MCP SDK 与 HTTP 发送合成远端请求；宿主同时观察到 tools/call 开始、端上 RUNNING 和 ready PID 后执行 SIGKILL。API 29/36 各一组通过，共两次实际强杀、四次启动恢复；真实 socket EOF、工具调用总数 1、模型请求计数不变、原审批已消费、Turn/ToolCall/run 为 INTERRUPTED、审计 uncertainToolCall 精确指向原调用、Goal 预算不返还。安装主 APK/测试 APK hash 已比对。证据 `build/main-verification/mcp-live-kill-api29-unique-call/result.json` 与 `mcp-live-kill-api36/result.json`。脚本模型与合成 MCP 服务，不替代真实外部服务、显式后续 Continue 的产品流程或其他后端中断矩阵。

首轮 fixture 错把 SDK 的合法 `_meta` 附加字段当成非法参数，修正为精确校验工具名及业务参数；原失败保留在 `mcp-live-kill-api29/`，显式 abort 仅清理自建数据，不计恢复验收。第二轮暴露独立产品缺陷：重复远端工具调用 ID `fixture-call` 跨 Turn 导致 `tool_calls.id` 主键冲突，Turn 以 INTERNAL 失败；`mcp-live-kill-api29-fixed/system.log` 和专用模拟器数据库快照保留证据。后续强杀 fixture 使用唯一调用 ID 以隔离这一问题，**产品重复 ID 问题尚未修复**，下一检查项必须处理本地持久标识与 Provider 调用关联，不能仅改 fixture 宣称修复。

当前新 Android 用例与宿主脚本已通过测试 APK 构建、Spotless、Detekt、Python 语法检查；生产代码本轮未改。HXA-102 与完整优化 Goal 保持进行中。


## 2026-09-07：Provider 工具 ID 与本地执行标识分离

已修复上一轮发现的跨 Turn 重复 Provider ID 主键冲突。每个工具轮次分配新的本地调用 ID，执行/审批/预算/结果/恢复使用本地 ID；assistant 历史同时持久原 `id` 与 `localId` 映射，模型结果回填保留原协议 ID。无需迁移旧数据，原有单响应内重复 ID 校验保留。详见 [缺陷记录](../bug-fixes/2026-09-07-provider-tool-id-local-identity.md)。

当前 developer 安装 APK hash 核实后，API 29 保留旧 `fixture-call` 并连续两次复用该 ID，API 36 再一组，共三组真实 MCP 执行/强杀通过、六次启动恢复不重放。证据 `build/main-verification/mcp-duplicate-id-api29-first/`、`mcp-duplicate-id-api29-second/`、`mcp-duplicate-id-api36/`。独立审批已消费与 uncertainToolCall 审计仍通过。当前 app consumer/developer JVM 292/304 通过，各三项 Connector 外部样本/服务 assumption；596 通过、6 跳过、零失败，不混入旧全仓 2602 结论。developer 构建、lint、Spotless、Detekt通过。

这次修改了生产 ChatService，旧全仓/固定45等证据不自动升级为当前快照。接下来补普通完成/模型回填、拒绝、同一 Turn 多轮复用 ID 的设备覆盖，再按影响刷新全量门禁。整体 Goal 与 HXA-102 尚未完成。


## 2026-09-07：本地工具 ID 修复后的普通完成/拒绝/回填与全仓门禁

共同 Android E2E 新增同一 Turn 两轮复用 `same-wire-id`：time.now 实际完成和未注册合法工具被 UNKNOWN_TOOL 拒绝两类。每轮有独立本地 ToolCall/result，发送给模型的两条 TOOL 消息都保留原协议 ID，并且本地 ID 不泄露到请求体。连同既有单轮工具完成、长 payload 超越 timeline 预览的回填用例，API29/36 × consumer/developer **16/16**，零跳过。证据 `build/main-verification/repeated-tool-round-device-final/result.json` 与四组日志/安装 APK hash。使用 scripted SSE wire 和生产 Provider adapter/ChatService/工具管线/Room，不属于真实模型或真实 HTTP 服务验收。

测试开发阶段三个不通过轮次分别保留：非法工具名在 decoder 终止；非对象参数在严格历史校验终止；合法未知工具的状态原断言误写 FAILED、实际为 DENIED。对应 `repeated-tool-round-device/`、`repeated-tool-round-device-fixed/`、`repeated-tool-round-device-valid-rejection/`。最终用例核实的是合法可回填拒绝，没有放宽生产解析或删除历史失败。中间参数签名编译失败日志也保留，最终双测试 APK、Spotless、Detekt通过。

生产代码 ID 分离后的全仓 JVM 使用 force-tests.gradle 强制实际重跑、禁用测试缓存，并提供已有 Connector 外部样本配置：**2610/2610、零失败/跳过，34 个产生 XML 的 Test task**。根 lintDebug、Spotless、Detekt通过，日志 `local-tool-id-full-host.log`，逐任务计数 `local-tool-id-full-host-result.json`，原 XML 已归档 `local-tool-id-full-host-xml/`。聚合 test 与 NO-SOURCE 不重复计数。此后仅 Android 测试断言调整，生产/JVM 源码未变。固定45与其他受影响设备快照、剩余副作用后端 SIGKILL 和最终交互优化仍继续，不将全仓 JVM 通过扩写为整体收口。


## 2026-09-07：ID 分离后真实模型 MCP/A2A 刷新

API34 当前 developer 主/测试 APK 重装后，SGLang 经现有30018转发端口完成 MCP 4/4 与 A2A 4/4，固定数据集 hash 不变；两组各 Plan 0 次远端执行，其余三项各1次，未出现重复发送。MCP/A2A 的外部服务仍是固定合成 fixture，模型推理与生产 Provider/审批/工具链路是真实执行。证据 `build/main-verification/local-id-real-mcp-api34/` 与 `local-id-real-a2a-api34/`；各 config 记录安装 APK hash，结果不扩写为全部固定45或真实第三方服务验收。


## 2026-09-07：核心预算测试关联修正与 Goal/附件组合刷新

ID 分离后的首轮核心矩阵 API29/36 × 双发行包，每组88通过、1失败；失败均是 Goal 工具预算测试仍用远端 one/two 查询本地主键。已将测试改为读取持久 TOOL_CALLS 的 id→localId 映射，继续断言获准项 COMPLETED、超预算项 FAILED、两个结果均持久化且只计一次工具预算。没有更改生产行为或放宽预算断言。原失败证据保留在 `build/main-verification/local-id-core-api*/`，不改写成首轮全绿。

修正后对完整 AttachmentE2e 26项 + Goal组件/UI 44项组合重跑，API29/36 × consumer/developer每组70/70，共**280/280，零跳过**。证据 `local-id-goal-attachment-summary.json`、`local-id-goal-attachment-api*/`；每组核实实际安装APK hash。构建双测试APK、Spotless、Detekt与文档检查通过。其他核心类的首轮通过证据与此组合结果分开报告，不拼成同一APK首轮356/356。

真实模型固定评测追加Goal3/3、Files4/4，连同本轮MCP4/4与A2A4/4，共**15/45**已刷新到ID分离后的生产快照；`local-id-real-subset-summary.json`明确full45Complete=false。真实SGLang推理、文件生产路径及精确审批/回填通过；MCP/A2A远端仍为合成fixture。剩余30项与其他后端强杀恢复继续，完整Goal与HXA-102尚未完成。


## 2026-09-07：当前生产快照固定45与 A2A Task 查询中 SIGKILL

Provider9、Plan3、JavaScript4、Browser4、Skill4、Accessibility3、Root3全部首轮通过，连同此前Files4/MCP4/A2A4/Goal3，核实11组相同安装主/测试APK hash和固定数据集hash后，**45/45、firstAttemptAllPassed=true、retryCount=0**。证据 `build/main-verification/local-id-fixed45-summary.json` 与 `local-id-remaining-evals/`。这是ID分离后的生产快照；新A2A强杀用例单独构建测试APK，不混入该45的安装hash。Root是合成系统授权/时钟负向边界，MCP/A2A是合成远端服务，真实SGLang推理与生产工具路径通过，不扩写为物理Root/付费账号/长稳或真实第三方服务验收。API34 Accessibility fixture清理后系统服务列表为null。

新增 `A2aProcessKillDeviceTest` 与 `scripts/run-a2a-process-kill.py`：生产Goal/Chat/精确审批/A2A HTTP已经发送一次并持久保存 `task-kill`，宿主在实际GetTask保持连接、端上RUNNING和ready PID后SIGKILL。API29/36两组通过，两次强杀、四次启动恢复；调用/Turn/run与uncertainToolCall审计保留，预算不返还，审批已消费，startup不发送也不查询。随后测试模拟显式对账动作，仍以原task-kill执行第二次GetTask至COMPLETED，SendMessage总数保持1。各组核实安装APKhash，证据 `build/main-verification/a2a-live-kill-current/verified-summary.json`。仅覆盖已知Task ID、查询执行中断边界，不声称覆盖发送响应前未知Task ID或全部A2A阶段。

初次新fixture使用10.0.2.2明文Card，被生产HTTPS约束正常拒绝；改为现有A2A测试采用的loopback + adb reverse，结束后删除自建reverse。显式abort清理又发现注册失败后删除不存在Agent会抛错，已按实际自建记录是否存在清理。原失败及abort记录保留 `a2a-process-kill-api29/`，首次loopback成功记录单列 `a2a-process-kill-api29-loopback/`；最终双设备结果使用补强后的“不自动GetTask”断言。没有放宽生产网络规则；abort不计恢复验收。最终测试APK构建、Spotless、Detekt与Python语法检查通过。

本轮未改生产代码。剩余各后端中断阶段、Goal完成证据决定与统一交互优化继续，HXA-102和完整开发Goal尚未完成。


## 2026-09-07：A2A 发送响应前未知 Task ID 强杀边界

`A2aProcessKillDeviceTest`/宿主runner新增send边界：远端已经收到一次SendMessage、响应头/Task ID尚未返回，端上持久调用RUNNING及A2A记录存在但taskId为空时，宿主观察真实发送开始后SIGKILL。API29/36各通过：socket EOF、两次启动恢复保留INTERRUPTED与uncertainToolCall、审批已消费、Goal预算不返还，远端SendMessage总数1、GetTask总数0。显式reconcileTask抛稳定A2aNeedsReviewException，不能生成Task ID或改成重发。

同一主/测试APK再回归已知Task ID的poll边界两组，显式原ID对账COMPLETED，SendMessage1/GetTask2。合计4组真实强杀、8次启动恢复，安装hash一致；证据 `build/main-verification/a2a-process-boundaries-summary.json`，send原始日志 `a2a-unknown-id-current/`，poll `a2a-known-id-refreshed/`。send结果中的reconciledTaskId=null；初版通用scope文案的same-task reconciliation表示显式对账尝试，并非成功获取结果，宿主文案已改为reconciliation boundary，新输出另列REVIEW_REQUIRED/COMPLETED。

本轮只扩展测试，无生产代码变更；构建、Spotless、Detekt、Python语法和文档检查通过。A2A这两个实际进程边界完成不等于所有远端服务或取消阶段全验收；继续其他执行后端的主进程中断与恢复。真机和长稳仍后置。


## 2026-09-07：QuickJS 执行中主进程 SIGKILL

新增 developer `JavascriptProcessKillDeviceTest` 与 `scripts/run-javascript-process-kill.py`。脚本模型通过生产Goal/Chat/Dispatcher请求受原有时间上限约束的合成无限循环；仅匹配该工具与代码的精确审批被确认，端上RUNNING后发ready。宿主找到本应用独立helix_js进程的helix-js-execution线程，采样0.5秒并要求CPU tick至少增加5，再SIGKILL主App PID；随后要求隔离worker在15秒观察窗口内消失。

API29/36同一主/测试APK各通过，两个真实强杀、四次启动恢复：原ToolCall/Turn/run为INTERRUPTED，恢复审计uncertainToolCall指向原调用，审批已消费，预算不返还，模型请求计数不增长，未再创建worker。证据 `build/main-verification/javascript-live-kill-summary.json`、`javascript-live-kill-api29/`、`javascript-live-kill-api36/`；记录实际main/worker/thread PID与前后CPU计数。模型为scripted HTTP fixture，QuickJS执行/隔离Service/Room恢复为真实生产路径。宿主管理权限只用来读取进程事实和发送信号，不等于应用Root能力或自然LMK/真机验收。

这次验证的是主App死亡导致实际执行worker退出及持久恢复，没有修改QuickJS运行时、取消机制或时间上限，没有用Runtime自身注入崩溃替代主进程强杀。本轮仅新增测试/runner；测试APK构建、Spotless、Detekt、Python语法与diff检查通过。审批等待、PRoot/CLI等剩余阶段继续，完整HXA-102/优化Goal尚未完成。


## 2026-09-07：审批等待中主进程强杀

在JavascriptProcessKillDeviceTest/宿主runner增加approval边界，真实模型调用使用scripted HTTP fixture，请求实际code.javascript.run工具，持久AWAITING_APPROVAL后保持等待，不执行approve。宿主确认没有本应用QuickJS worker后SIGKILL主进程。API29/36各通过，两次启动均保留AWAITING_APPROVAL，审批decision/consumedAt为空，没有工具结果，恢复审计uncertainToolCall=null（尚未执行，不伪称副作用不确定）；Turn/run INTERRUPTED、Goal PAUSED，预算不返还，无模型重发或worker创建。

同一主/测试APK复核execution边界各一组，通过真实worker执行线程CPU活动后强杀、worker退出与两次恢复不重放。总计4组强杀、8次启动恢复，只有2组包含实际JavaScript执行；approval组的worker=null，原runner的workerExited=true字段仅代表杀后worker不存在，不能计为存在过worker或执行过脚本。证据 `build/main-verification/js-approval-boundaries/verified-summary.json` 与各边界日志/安装hash。

本轮未修改生产代码，构建测试APK、Spotless、Detekt、Python语法检查通过。等待审批恢复的用户后续操作、其他工具实现和PRoot/CLI等边界继续按具体证据验证，不扩写成所有审批/工具流程验收。完整HXA-102与开发Goal仍进行中。


## 2026-09-07：M11 CLI Runtime 客户端主进程死亡

新增developer `CliOwnerProcessKillDeviceTest` 与 `scripts/run-cli-owner-process-kill.py`。使用生产CliModelJobClient/CliRuntimeSupervisor和独立Runtime UID，但模型是DEBUG的helix-fixture-wait（不读取账号、不执行付费调用）。Job持久RUNNING、客户端等待尚未结束后输出ready；宿主记录Runtime PID，只SIGKILL主App PID。新启动只query/cancel/reconcile原Job ID，核对requestSha256与createdAt不变、终态无事件载荷、重复查询/对账不变化。

API29/36 × CODEX/CLAUDE/GROK/COPILOT共**8/8**，八次真实主进程SIGKILL、十六次恢复检查，全部以原Job取消后CANCELLED稳定结束。三APK（主App/test/CLI Runtime）安装hash一致且比对当前产物。证据 `build/main-verification/cli-owner-kill-matrix/verified-summary.json`。没有对Runtime发kill，没有清理真实账号或重置应用数据。marker仅属于该用例，第二次恢复后移除；Runtime原Job终态记录保留作为不重放证据。

这证明主客户端死亡后的原Job身份和取消/对账边界，不能替代实际订阅模型/账号、ChatService/Goal绑定或所有CLI生命周期验收；RUNNING是持久作业状态，debug wait不是真实远端模型流。全Goal的生产绑定和模型HTTP流恢复证据另列，不拼成端到端订阅Provider强杀结论。测试APK构建、Spotless、Detekt与Python语法检查通过，生产代码未变。PRoot及其他剩余阶段继续。


## 2026-09-07：PRoot guest 执行中主客户端死亡

新增 `ProotOwnerProcessKillDeviceTest` 与 `scripts/run-proot-owner-process-kill.py`。生产跨 UID 客户端提交真实 guest shell，宿主读到 guest 写入的启动标记后仅 SIGKILL 主 App；两次恢复只查询、取消和对账原 Job，验证 executionId、输入 manifest SHA、创建时间与 terminal commit 不变，不重新提交。API 29/36 共 **2/2**、两次真实强杀、四次恢复检查，均稳定 CANCELLED；主 App、test、PRoot APK hash 在两台一致。仅移除本用例目录，Runtime 终态日志保留。

证据 `build/main-verification/proot-owner-kill-summary.json` 及两系统 `proot-owner-kill-api*/` 原始日志。API 29 原 result 的 scope 文案误带旧 CLI fixture 名称，原证据保留；实际测试类、guest 命令和启动标记均为 PRoot，聚合记录明确校正。测试 APK 构建、Spotless、Detekt通过（`proot-owner-kill-build.log`）。运行命令为 `python3 scripts/run-proot-owner-process-kill.py --adb <adb> --serial <emulator> --output <new-evidence-dir>`。

此结果证明实际 guest 启动后的直接 Runtime 客户端恢复，不替代完整 ChatService/Goal 的 bash 接线强杀，也不代表自然低内存回收或真机验收。后续仍验证生产绑定及文件写、浏览器、UI 动作等剩余阶段；开发 Goal 保持 active。


## 2026-09-07：等待审批恢复后的显式拒绝

在 `JavascriptProcessKillDeviceTest` 和宿主 runner 增加 `--boundary approval --after-recovery deny`：真实 Goal 工具请求持久等待审批后 SIGKILL 主 App；第一次重启验证原待决状态，第二次通过生产 `ChatService.denyApproval` 显式拒绝原 approvalId，第三次启动验证 DENIED 持久化。API 29/36 两组均通过，共两次强杀、六次恢复检查；审批 consumedAt 仍为空、没有工具结果或 QuickJS worker，原 Turn 保持 INTERRUPTED、Goal PAUSED、模型调用和 token 预算不变。宿主模型请求总数各4（含连接探测），三个恢复阶段均无新增请求。

证据 `build/main-verification/approval-recovery-denial-summary.json` 与两系统原始日志；两台安装主/测试 APK hash 一致。本轮只扩展测试，不修改生产行为；显式拒绝由服务入口调用，不冒充 UI 点击验收。原执行中强杀与等待审批不操作的证据仍分别保留。

验证：初次误用不存在的 `:app:spotlessApply` 导致任务选择失败，日志保留；改用仓库根任务后 `./gradlew spotlessApply :app:assembleDeveloperDebugAndroidTest detekt` 通过（14秒），Python语法和文档门禁通过。接受审批后的处理、完整后端接线及文件/浏览器/UI动作恢复继续，HXA-102与开发Goal尚未完成。


## 2026-09-07：中断后的审批时间线显示修复

发现恢复后的 Turn 已 INTERRUPTED，工具行仍显示“待审批”但没有 live 审批卡。现仅在该组合下显示既有本地化“已中断（待恢复）”，保留原调用/审批/预算事实，不创建批准或重新执行路径。根因与边界见 [缺陷记录](../bug-fixes/2026-09-07-recovered-approval-timeline.md)。

API29/36两组真实审批等待强杀、六次恢复阶段通过，并验证生产 screen state 的历史行文案和 card=null；不是UI点击/截图验收。正常审批3项×2系统全部通过。Consumer JVM295/295、developer307/307，零跳过；双debug构建、根lintDebug、Spotless、Detekt通过。证据 `build/main-verification/recovered-approval-label-*`、`recovered-approval-live-emulator-*.log`。本次生产APK已变化，旧全量/真实模型评测保持原快照边界，不能称为本APK全量验收。HXA-102和开发Goal继续。


## 2026-09-07：PRoot 提交后失败的副作用语义

修复生产 PRoot 执行器统一返回 sideEffectFree=true 的问题：已接受后的失败全部为false，结果未知的等待/提交响应为requiresReview；已知失败终态仍为普通失败。详见 [缺陷记录](../bug-fixes/2026-09-07-proot-failure-effect-semantics.md)。真实guest先写后exit3的用例及既有导入/拒绝等5项在API29/36共10/10通过；未知等待/提交分支本轮为源码核验，不伪称已注入设备故障。

Developer JVM强制运行307/307、零跳过（初次缺样本条件的3条跳过日志保留）；根lintDebug、Spotless、Detekt与developer构建通过。证据 `build/main-verification/proot-failure-semantics-*`。同时确认生产执行器缺少提交前ToolCall与Job ID的持久关联，成功后审计不能覆盖主进程中断；该项继续修复，不能以直接Runtime客户端测试替代。


## 2026-09-07：PRoot生产调用与Job提交前持久关联

已修复主进程死亡后无法从原ToolCall定位Job的问题：生产执行器在Binder提交前记录版本化的 `proot.job_prepared`，绑定原Turn/ToolCall、Job/执行ID和输入hash；记录失败不提交，同一调用不能静默覆盖绑定。记录不表示已接受或成功，也不包含命令正文。[缺陷记录](../bug-fixes/2026-09-07-proot-durable-job-binding.md)。

完整Goal/Chat/审批/PRoot真实guest链路在API29/36各一次强杀、四次恢复通过，原Job查询/取消/对账、绑定hash/审批消费/预算和不重放均验证；证据 `build/main-verification/proot-goal-binding-summary.json`。这次不再是独立客户端保存测试marker代替生产持久关联；显式对账由测试调用生产API，用户对账UI仍未交付。新增持久化失败的提交前阻断测试，两系统LinuxRunTool套件共12/12；后者测试APK更新，完整Goal日志保留其原测试APKhash，生产APK相同。

Developer JVM307/307零跳过、根lintDebug、Spotless、Detekt、developer构建通过。所有证据位于 `proot-binding-*` 和 `proot-goal-binding-*`。其他后端和剩余边界继续，开发Goal保持active。


## 2026-09-07：M11完整Goal恢复、会话模型与Runtime冷启动

补齐 [CLI提交前模型调用关联](../bug-fixes/2026-09-07-cli-durable-model-job-binding.md)：本地协程上下文传递Turn/modelCallId，提交前持久平台/Job/request hash，元数据不进入模型请求。修复 [会话模型未用于请求](../bug-fixes/2026-09-07-session-selected-model-request.md)：初始/回填请求优先使用已存会话选择。另发现并修复 [CLI冷启动四TLS客户端初始化OOM](../bug-fixes/2026-09-07-cli-runtime-lazy-oauth.md)：按实际平台惰性创建OAuth客户端与控制器，仅关闭已初始化实例，不改变TLS验证。

最终同三APK，API29/36 × CODEX/CLAUDE/GROK/COPILOT完整Goal/Chat/订阅adapter/Runtime DEBUG模型强杀 **8/8、16次恢复**，原Job请求hash可核验，Goal暂停、预算不返还，无新提交，显式原ID取消/对账全部CANCELLED。证据 `build/main-verification/cli-goal-binding-summary.json`。这是生产接线+无账号fixture，不计作真实订阅/付费调用。初始两轮失败、Runtime OOM栈、用例定向清理均保留；早先模型选择问题判断不能替代OOM的直接失败证据。

双变体JVM602/602、CLI Runtime102/102零跳过，根lintDebug、Spotless、Detekt与构建通过，见 `cli-binding-host-summary.json` 及host/build日志。Provider临时测试模型、模式/预算/profile已恢复，无Runtime凭据或数据重置。不同会话模型的工具回填专门回归、用户恢复入口、其他执行阶段和全量最终快照继续，Goal保持active。


## 2026-09-07：会话模型选择的多轮回填验收

新增 `selectedSessionModelIsUsedForInitialAndBackfillRequests` 与 `legacySessionWithoutModelUsesProviderDefaultForEveryRound`。各场景真实ChatService执行两轮time.now并回填，逐条检查三个序列化请求的model；前者全部使用不同于Provider默认值的会话模型，后者在modelId为空时全部使用Provider默认值。原工具身份隔离、结果持久化与重复wire ID回填断言仍保留。

完整AttachmentE2eDeviceTest在API29/36 × consumer/developer四组 **112/112、零失败/跳过**，含本轮新增8例、其余既有104例。证据 `build/main-verification/session-model-backfill-summary.json`、各组原始instrumentation日志及安装hash。执行 `adb -s <serial> shell am instrument -w -r -e class com.helix.app.chat.AttachmentE2eDeviceTest <package>.test/com.helix.app.HelixAndroidJUnitRunner`。双变体测试APK构建、Spotless、Detekt通过。本轮未再修改生产逻辑；[会话模型缺陷](../bug-fixes/2026-09-07-session-selected-model-request.md)的专门回填缺口已关闭，其余HXA-102和全Goal待办继续。


## 2026-09-07：PRoot恢复查询/停止按钮与中断会话状态

中断的PRoot工具行现在提供“查询原作业”；确认原Job仍运行后提供“停止原作业”。操作经ChatService与生产恢复模块，核对原Turn/ToolCall、Job执行ID与输入hash，不重新提交，不自动导入或删除结果。状态文案区分原Job完成与结果尚未验证，consumer无此能力入口。查询/停止能力已接入时间线；完整导航和视觉布局仍归后续UI验收。

新增真实按钮点击覆盖：完整Goal/审批/guest开始后SIGKILL，两系统各一次；恢复时渲染生产组件并查询、实际点击停止，最终明确断言原Runtime Job CANCELLED，第二次启动继续核对身份与预算。最终 **2组/4次恢复/2次实际停止点击**，证据 `build/main-verification/proot-recovery-ui-summary.json`。对账清理仍由测试单独完成，UI查询/停止本身保留payload，不等同于结果恢复导入。

测试同时发现打开INTERRUPTED会话仍显示发送中，已在UI投影修复并加3项JVM边界测试；核心非终态恢复语义不变。[缺陷记录](../bug-fixes/2026-09-07-interrupted-chat-not-sending.md)。双变体JVM608/608、零跳过，根lintDebug、Spotless、Detekt和构建通过。首轮公共Cloudflare MCP网络超时、两台恢复清理失败及定向清理日志全部保留；最终新APK验证独立计数。其余后端、恢复结果验证、全量最终快照和统一UI仍继续。

## 2026-09-07 write 发布后失败结算修复

修正文件已替换后 I/O 异常仍声称“未写入”的问题：未知结果要求核对目标文件，保留 sideEffectFree=false。真实发布后注入异常的回归先失败后通过；files 110/110、framework 150/150、workspace 87/87，无跳过。两发行包 Debug 构建、root lintDebug、Spotless、Detekt 通过，相关未变化任务可复用 Gradle 结果。证据 `build/main-verification/write-after-publish-{green,build}.log`。该注入测试不等于文件阶段 SIGKILL 验收；见 [缺陷记录](../bug-fixes/2026-09-07-write-post-publication-failure.md)。

## 2026-09-07 执行后取消的待核对信号

修正 dispatcher 对执行后取消只写“副作用未知”却丢失 requiresReview 的问题。两个回归先失败后通过，包含阻塞执行线程取消；framework150/150、files110/110，无跳过，两发行包构建、root lintDebug、Detekt通过。证据 `build/main-verification/cancel-review-{red,green}.log`，见 [缺陷记录](../bug-fixes/2026-09-07-cancelled-execution-review.md)。该轮不是设备 SIGKILL 或完整会话端到端验收；超时执行边界继续单独核查。

## 2026-09-07 超时执行边界修复

区分执行器未提交的超时（确认零副作用）和提交后的超时（requiresReview=true）；保留 TIMEOUT 与原有有限重试/证明处理。两个回归先失败后通过；framework150/150、files110/110，无跳过，两发行包构建、Lint、Detekt通过。首次实现的 ReturnCount 静态失败保留，最终证据 `build/main-verification/timeout-review-final.log`。见 [缺陷记录](../bug-fixes/2026-09-07-timeout-execution-boundary.md)，不作为设备 SIGKILL 验收。

## 2026-09-07 原子文件层实际进程死亡验证

新增 FilePublishProcessKillDeviceTest 与 `scripts/run-file-publish-process-kill.py`。在真实 AtomicFileWriter.writeAtomicStream 写入临时文件后、正式发布后两个边界，由宿主对专用模拟器 App 执行 SIGKILL。API29/36各两次，共4次真实kill、8次恢复测试通过。临时阶段保留完整旧目标并回收一个孤儿临时文件；已发布阶段保留完整新目标。第二次重启复验内容稳定且再次显式cleanup返回0。每组最后删除自身夹具目录，不触及其他工作区。

证据 `build/main-verification/file-publish-kill-summary.json` 及两个 `file-publish-kill-emulator-*/` 原始目录；摘要记录主包与测试包SHA256。命令 `python3 scripts/run-file-publish-process-kill.py --serial <dedicated-emulator> --adb <sdk-adb> --output <fresh-output-directory>`，两次exit0。测试APK构建、Spotless与Detekt通过，Python语法检查通过。准备阶段按设计被杀，不计JUnit通过。

该验证调用生产原子文件实现，但不经过Goal/Dispatcher，也不证明App启动自动清理；孤儿清理由测试显式调用生产cleanup。完整文件ToolCall进程中断恢复仍待验收，不据此勾选整个HXA-102。未发现需要修改的原子写入生产缺陷。

## 2026-09-07 文件完成后 Goal 回填中断恢复

新增 FileGoalProcessKillDeviceTest 和 `scripts/run-file-goal-process-kill.py`：实际Goal/Chat/Dispatcher/write完成后，宿主收到工具结果回填请求并保持响应，再SIGKILL主App。API29/36最终同APK各一次实际kill、两次恢复校验，共2kill/4恢复通过。目标文件内容与mtime不变，原ToolCall保持COMPLETED、结果存在，Turn/run为INTERRUPTED，Goal为PAUSED，模型次数2且预算预留精确结算、不退款；第二次恢复状态稳定。宿主每组5次模型请求含连接探测，仅1次工具回填，恢复不增加请求。

证据 `build/main-verification/file-goal-kill-summary.json` 与 `file-goal-kill-final-api{29,36}/`。命令 `python3 scripts/run-file-goal-process-kill.py --serial <dedicated-emulator> --adb <sdk-adb> --output <fresh-directory>` 两次exit0；测试APK构建/Spotless/Detekt通过，Python语法检查通过。主APK SHA256及测试APK SHA256记录于摘要。

首轮脚本模块导入错误未启动设备测试。API29首设备轮因读取已结算modelCalls等待第二次调用而超时；改为实际modelCalls记录后API29通过，API36则暴露预算预留取样过早（基线12、恢复6808）。最终在MODEL预留落盘后记录基线，保留精确相等断言。API29首次abort与启动恢复竞争，等待open run结束后清理通过；API36失败夹具也已单独清理。上述失败日志与旧APK轮次均保留，不混入最终通过计数。

该边界是已完成文件ToolCall后的模型回填中断；它不证明发布后但ToolCall尚未结算的极窄窗口，也不覆盖浏览器/UI动作进程死亡。模型为脚本夹具，不是新增真实模型评测。未修改生产实现。

## 2026-09-07 浏览器点击与在途导航的 Goal 中断恢复

新增 BrowserGoalProcessKillDeviceTest 和 `scripts/run-browser-goal-process-kill.py`。在生产Browser创建页面并取得真实snapshot节点token，经实际Goal/Dispatcher/审批执行browser.click。宿主必须收到该点击触发的HTTP导航及模型工具结果回填，保持两个响应不返回，再SIGKILL主App。最终API29/36同一APK各1次点击导航、1次kill、2次恢复，共2导航/2kill/4恢复通过。

重启后原点击ToolCall保持COMPLETED且结果存在，Turn/run为INTERRUPTED、Goal为PAUSED，模型次数2、预算与落盘预留精确结算，无预算退款；再次重启状态不变。每设备宿主仅记录1次导航和1次工具回填，恢复不增加请求。证据 `build/main-verification/browser-goal-kill-summary.json` 与 `browser-goal-kill-verified-api{29,36}/`，摘要含主/测试APK SHA256。测试APK构建、Spotless、Detekt和Python语法检查通过。

原两API运行通过恢复断言但清理失败：启动后临时tab已不存在，重复close抛unknown tab。清理重试还暴露重复归档不被允许；夹具改为检查Goal/Provider/tab存在和会话未归档状态，再执行自身资源清理。初始失败、abort失败及被残留marker挡住的重跑全部保留，最终完整重跑成功后才计通过。生产实现未修改。

此边界证明真实浏览器点击已完成、导航响应仍在途时无启动重放，不代表页面加载完成、任意DOM动作或Accessibility UI动作均已验收。宿主使用专用模拟器su0发信号，不是真机或App Root授权证据；模型仍为脚本夹具。

## 2026-09-07 Accessibility Goal kill 夹具实施中

已新增 UiGoalProcessKillDeviceTest 与 `scripts/run-ui-goal-process-kill.py`，为测试APK的AutomationEvaluationActivity增加显式recordClicks模式下的落盘点击计数；普通固定评测不启用该模式。目标是生产ui.click后保持模型回填，再实际kill并核对计数不增加。当前尚未到达点击/kill边界，不能计验收通过。

测试APK构建、Spotless和Detekt最终通过；最初prepare超过LongMethod限制，已缩减重复提示而未放宽规则。Python语法检查通过。API29多轮停在服务CONNECTED前：secure设置已写入，现场dumpsys显示Enabled/Binding但Bound为空（ui-goal-accessibility-live.txt）。启用移至instrumentation报告UI_SERVICE_READY之后、再补正常MainActivity启动均未使该轮通过。此前将问题归因于单一启用时序仅是排查假设，现有证据不足以确认根因。

API36同一夹具已通过服务连接，但在等待FIXTURE_UNCHANGED前台页面时超时。结束后的截图显示旧PRoot任务在前台，此截图不证明超时期间的前台状态，下一轮需要在等待期间取样。证据 ui-goal-kill-foreground-api36/、ui-goal-api36-screen.png 和相关logcat。原失败全部保留；当前没有实际UI点击或SIGKILL通过声明。

两设备已通过专用abort清理（ui-goal-kill-foreground-abort-api29.log、ui-goal-kill-foreground-abort-api36.log），宿主finally恢复原enabled_accessibility_services/accessibility_enabled。后续从服务绑定与夹具前台生命周期继续定位，不修改生产权限规则或放宽断言。

## 2026-09-07 UI kill 前置诊断与夹具修复

已确认API36测试页面未显示的直接原因：AutomationEvaluationActivity新增局部可变点击计数生成kotlin.jvm.internal.Ref$IntRef，而独立测试APK进程未包含该类，导致NoClassDefFoundError。已将计数改为Activity字段；后续实际页面、ui.snapshot成功，说明该崩溃路径已排除。原crash记录ui-goal-api36-crash.txt保留。没有增加运行时依赖或修改生产页面。

API29在清理并重启专用模拟器后进入实际ui.click（此前停在Binding）；原失败仍保留，不能将一次重启视为服务绑定根因修复。API36一次短暂adb offline使宿主finally恢复失败，设备重新在线后已显式删除本轮enabled_accessibility_services并恢复accessibility_enabled，相关日志ui-goal-counter-fixed-api36.log留存。

夹具又修正两个协议问题：模型先调用生产ui.snapshot再取最新token，替代Goal启动前的旧token；生成ID使用合法字符，工具回填按实际[SUCCEEDED]前缀解析。ui-goal-snapshot-api36的PROTOCOL失败、ui-goal-node-api36的真实回填及后续ui-goal-wire-api36的STALE_TOKEN均保留，不误记为点击成功。

最新ui-goal-events-api36仍为STALE_TOKEN，没有kill通过。测试新增有界事件元数据（仅类型、窗口、包名、时间，不含节点文本）：测试页不变时System UI其他窗口有连续TYPE_WINDOW_CONTENT_CHANGED；当前HelixAccessibilityService对每个事件递增全局generation。该源码与现场事件构成下一步窗口过滤修复的依据，尚未证明修复生效，目标窗口改变、设备锁定、权限撤回和action前的目标复验不能弱化。

ui-goal-event-fixed-build.log记录最新测试APK构建/Spotless/Detekt通过；Python语法与文档门禁通过。所有本轮失败fixture均完成专用abort，包括ui-goal-events-abort-api36.log。当前继续HXA-102，UI动作kill验收仍未完成。

## 2026-09-07 Accessibility 窗口代次修复与 Goal kill通过

修正已知其他窗口TYPE_WINDOW_CONTENT_CHANGED错误失效当前snapshot的问题；同窗口、未知窗口、其他事件和执行前目标/指纹复验保持。JVM43/43，developer主/测试APK、root lintDebug、Spotless、Detekt通过。见 [缺陷记录](../bug-fixes/2026-09-07-accessibility-unrelated-window-generation.md)。

最终API29/36同APK，经真实ui.snapshot→ui.click各点击合成按钮一次，宿主确认落盘计数及模型回填再SIGKILL。共2点击/2kill/4恢复测试通过，计数一直为1、模型请求不增加、Goal暂停且预算精确结算。证据 `build/main-verification/ui-generation-kill-summary.json`、`ui-generation-final-api{29,36}/`。清理与宿主设置恢复完成。

首次修复轮API36已成功点击/kill，但三次调用恰好到Goal模型上限，恢复为BUDGET_EXHAUSTED(maxModelCalls)；夹具改成上限4以隔离中断语义。API29仍有目标窗口初始化事件，保留有效失效检查，改为准备阶段waitForIdle后开始Goal；专用模拟器再次重启后最终完整通过。原失败、环境变化与最终通过不合并计数，不据此宣称服务绑定根因已解决或全部UI验收完成。

## 2026-09-07 Accessibility 修复后全仓 JVM 与静态复验

在当前累计调度/文件/Accessibility修复后执行 `./gradlew -I build/main-verification/force-tests.gradle test lintDebug spotlessCheck detekt --max-workers=2`，提供既有Connector样本目录和交接ZIP环境变量。exit0，34个实际Test任务全部强制执行，2620/2620，零失败/错误/跳过；聚合test任务不重复计数。root lintDebug、Spotless、Detekt通过，未变化的静态任务可使用Gradle up-to-date结果。

证据 `build/main-verification/post-ui-full-host.log`、`post-ui-full-host-result.json`、独立`post-ui-full-host-xml/`和`post-ui-source-hashes.json`。1069个源码/配置hash在该轮前后保持一致。测试计划另存`post-ui-full-test-plan.log`，dry-run输出SKIPPED不代表实际测试跳过。

该轮刷新全仓JVM与静态证据，不替代尚未完成的设备/生命周期/Goal验收契约或统一交互优化，也不宣称真机和后置长稳通过。ADR-0028仍proposed，已重新向所有者请求具体方案决定，未据等待状态擅自接受或实现。

## 2026-09-07 输出校验失败的副作用待核对修复

执行器完成后，输出schema失败现在保留INVALID_OUTPUT并携带requiresReview=true/sideEffectFree=false，避免普通FAILED丢失效果不确定性。回归先149通过/1失败，修复后framework150/150和files110/110通过；两发行包构建、root lintDebug、Spotless、Detekt通过。证据 `build/main-verification/invalid-output-review-{red,green}.log`，见 [缺陷记录](../bug-fixes/2026-09-07-invalid-output-effect-review.md)。前一轮2620全仓快照早于此修复，不能作为此改动的全仓复验声明；本轮为相关模块验证。

## 2026-09-07 M11 CLI 原始Job恢复控制器

新增developer侧SubscriptionJobRecovery和公共状态类型。仅允许INTERRUPTED Turn及其INTERRUPTED模型调用，核对持久绑定turn/modelCall/job/requestSha256；query和cancel返回记录均再次核对身份。未知/不可用结果保持UNKNOWN，成功结果只标记SUCCEEDED_UNVERIFIED，不导入、不reconcile删除证据、不重新submit。停止只作用于已查询的原非终态Job；终态查询不再次取消。

CliGoalProcessKillDeviceTest改用该控制器执行原Job query/stop，再由测试核对Runtime终态与单次cli.job_prepared记录。API29/36 × CODEX/CLAUDE/GROK/COPILOT共8次实际主App SIGKILL、16次恢复通过，生产Runtime的DEBUG合成模型请求，非付费账号调用。证据 `build/main-verification/cli-recovery-controller-summary.json` 及八组 `cli-recovery-controller-api*-*/`，各组包含APK哈希。两发行包构建、测试APK、root lintDebug、Spotless、Detekt通过，日志cli-recovery-controller-build.log。

当前后端类及设备验证已完成，尚未接入SubscriptionProviderIntegration/ChatService/界面按钮，不作为用户可用入口交付。下一步继续同一HXA的显式查询/停止界面接线和实际点击验证；consumer不引入Runtime客户端。

## 2026-09-07 M11 中断订阅任务查询与停止入口

SubscriptionProviderIntegration 接入 developer 原 Job 恢复控制器；consumer 默认不引入 CLI 客户端。AppContainer 注入 ChatService 显式操作，服务串行处理查询/停止，保留取消语义；本地会话扫描只根据 INTERRUPTED Turn/modelCall 与 cli.job_prepared 证据生成条目，不绑定 Runtime，也保留较旧中断 Turn 的入口。ConversationContent 在会话列表中显示恢复组件，查询确认 RUNNING 后才显示停止按钮；三个语言资源明确区分未知、运行、停止请求、已停止、失败结束和结果未导入/验证。查询与停止不 submit、不导入结果、不消费 Runtime 证据。

CliGoalProcessKillDeviceTest 现在点击生产 SubscriptionRecoveryActions，绑定真实 ChatService，再核对原 Job、请求摘要、终态、预算和无重复提交。API29/36 × CODEX/CLAUDE/GROK/COPILOT 共8次实际 SIGKILL、16次恢复全部通过；使用 Runtime DEBUG 合成模型，无账号调用。证据 `build/main-verification/cli-recovery-ui-summary.json` 与八组 `cli-recovery-ui-api*-*/`，含安装 APK 哈希。这里是实际生产组件/服务按钮验收，不冒充完整导航和布局验收。

执行 `./gradlew spotlessApply :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest detekt lintDebug` 通过；首次新 Compose 函数命名检查失败，按现有 Compose 命名约定标注 FunctionName 后复跑通过，未放宽复杂度规则。使用 force-tests.gradle 和既有 Connector 样本执行 `:app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest spotlessCheck`，Consumer 298/298、Developer 310/310，零失败/跳过。日志 `cli-recovery-ui-build-final.log`、`cli-recovery-ui-jvm.log`。此前2620项全仓快照仍早于本次改动，不能替代最终全仓复验。

入口接线取代上一条“控制器未接 UI”的阶段状态；成功结果导入/验证、完整会话交互验收和其他待办继续推进。ADR-0028 仍 proposed，未收到所有者接受决定，未启用 Goal 完成证据规则；长稳与真机边界不变。

## 2026-09-07 订阅恢复入口发现边界

新增 SubscriptionRecoveryDiscoveryTest，使用每例独立 Room 数据库验证：新 Turn 不遮掉旧 INTERRUPTED 调用；另一会话与关闭会话不继承原列表；缺失 cli.job_prepared、错误审计类型与已完成 modelCall 被排除；刷新只保留匹配调用的忙碌/查询状态。这是本地入口发现测试，不启动 Runtime，不替代上一轮真实按钮/跨进程恢复证据。

API29/36 × consumer/developer 每组4/4，共16/16、无失败/跳过。安装主包/测试包哈希均核对，证据 `build/main-verification/subscription-discovery/result.json` 与四份 instrumentation 日志；两测试 APK 构建、Spotless、Detekt通过，日志 `subscription-discovery-build.log`。隔离数据库与内容目录逐例清理，未修改生产存储或 Runtime 凭据。本轮未改生产逻辑，因此不重复上一轮已通过的608项 App JVM及根Lint。

## 2026-09-07 CLI 恢复完整聊天页面路径

CliGoalProcessKillDeviceTest 从单独渲染 SubscriptionRecoveryActions 改为渲染生产 ChatScreen：关闭当前会话，从会话列表滚动查找唯一的合成会话标题，实际点击进入，再滚动至原任务查询按钮并执行查询/停止。每次恢复记录 CLI_RECOVERY_CONVERSATION_OPENED，继续核对同一 Job/hash、预算、不重发及 CANCELLED/INTERRUPTED 终态；fixture 标题增加随机后缀，避免与历史归档测试会话混淆。

API29/36 × 四订阅适配器共8次主 App SIGKILL、16次完整聊天页面进入与恢复通过。证据 `build/main-verification/cli-recovery-navigation-summary.json` 和八组 `cli-recovery-navigation-api*-*/`，含安装哈希及16个导航标记；测试 APK 构建、Spotless、Detekt通过（cli-recovery-navigation-build-final.log）。本轮仅改测试，生产 APK 与上一轮查询/停止入口的4c0c9cdf快照一致。无真实账号调用；该证据覆盖 ChatScreen 内部会话导航，不包括 MainActivity 外层导航、所有字体/语言布局或成功结果导入。

## 2026-09-07 文件发布后、ToolCall 结算前的主进程中断

FileGoalProcessKillDeviceTest 增加 --unsettled 宿主模式。仅在测试 APK 内通过反射包装本进程注册的真实 write executor：原执行器实际完成发布且返回 Completed 后暂停返回，保留整个生产 schema/Policy/Approval/执行路径与文件写入实现，主 App 的 ToolCall 仍为 RUNNING；包装不进入发行源码，不替换文件操作或伪造 ToolResult。测试先确认目标内容、mtime 和待结算预算，再向宿主发出可强杀标记。这个明确的测试暂停点用于命中原本短暂的发布/落库窗口，不当作自然时序统计。

API29/36 各实际 SIGKILL 一次、恢复两次：ToolCall 为 INTERRUPTED，ToolResult 仍不存在，recovery.turn_interrupted 审计准确绑定 uncertainToolCall；Goal PAUSED、run INTERRUPTED、原文件内容/mtime 保持、预算结算一致、无新增模型请求，宿主模型回填计数0。随后复跑原已完成调用/模型回填模式，两API同样2kill/4恢复通过，ToolCall保留COMPLETED且每组回填计数1。合计4kill/8恢复，证据 `build/main-verification/file-settlement-boundary-summary.json` 和 `file-unsettled-api*/`、`file-backfill-regression-api*/`，含APK哈希。

测试 APK 构建、Spotless、Detekt及Python语法检查通过，日志file-unsettled-build-final.log。首次测试类引用结果名错误及两处长行已修正后复跑，未修改生产实现或静态阈值。测试只验证已发布文件的不重放和不确定性保留，不等于成功结果导入或完整发布前所有指令边界；原独立 AtomicFileWriter 临时文件/原子发布实验仍是另一个证据层。

## 2026-09-07 CLI 结果保留读取前置

发现旧reconcile在管道写完即删除Runtime输出，早于主App持久导入，已登记 [未关闭缺陷](cli-result-durable-recovery-gap.md)。新增兼容性扩展fetchResult/transaction6，复用PFD限额与SHA-256校验，读取不删除或对账。两API四适配器重复读取8/8通过；CLI client30/30、Runtime102/102无跳过，developer/Runtime构建及根Lint/Spotless/Detekt通过。证据 `build/main-verification/cli-result-fetch/result.json`、cli-result-fetch-build.log、cli-result-fetch-jvm.log。

这只是持久导入前置；旧正常调用路径尚未改成持久落库后确认，缺陷保持open。下一步须接原Job身份验证、App持久结果、显式确认与中断窗口，不得把fetch接口或查询按钮当作成功结果恢复完成。

## 2026-09-07 CLI 正常调用先持久化后确认

当前SDK await改为非破坏fetch；正常订阅成功结果先验证持久Turn/modelCall/Job/request身份和输出SHA-256，再原子保存私有workspace事件文件、登记session所属artifact并回读校验，之后才发新transaction7确认。Runtime确认校验request/output两摘要、只处理终态且幂等；旧reconcile保留给旧调用者/测试清理，当前正常订阅路径不再使用。探测结果为有意临时结果，不注册会话产物。

双API×四适配器持久化/错误摘要拒绝/重复确认8/8；正常Provider→Chat流程另8/8，确认本地artifact摘要和Runtime已确认记录一致。CLI client30/30、Runtime102/102无跳过，相关构建、根Lint、Spotless/Detekt通过。证据 `build/main-verification/cli-durable-ack-summary.json`、cli-durable-ack/、cli-durable-normal/及相关日志。测试首次跨模块nullable智能转换编译失败已用局部非空变量修正。

[结果恢复缺口](../development/cli-result-durable-recovery-gap.md)仍open：接下来做主进程强杀窗口、持久结果读取/恢复界面，不能用上述正常流程测试替代。未将Goal标记完成，未启动长稳或真实账号调用。

## 2026-09-07 CLI 结果交接三窗口强杀与本地回读

SubscriptionResultStore增加按原Turn/modelCall/Job/request绑定回读，校验artifact归属、size上限及输出SHA-256后解码，保存时也核对modelCall归属。CliResultOwnerKillDeviceTest使用生产Runtime与真实私有结果存储，在已fetch未持久化、已持久化未确认、已确认三阶段由宿主SIGKILL主App，恢复时从Runtime或本地取回同一结果，持久化/确认可重复，保持一个modelCall和一条job_prepared记录。测试种入调用绑定而非完整Goal运行，不能替代生产Goal预算/UI矩阵。

API29/36三窗口合计6次SIGKILL、12次恢复通过；新增本地文件等长篡改拒绝测试后，两API四适配器共8/8通过，恢复原字节后可读。结果文件纳入session删除清单；合成fixture清理完成。证据 `build/main-verification/cli-result-owner-summary.json`、六组cli-result-owner-api*-*/及cli-result-read-corruption/。构建、root lintDebug、Spotless、Detekt与Python语法检查通过，日志cli-result-owner-build.log和cli-result-read-build.log。

成功结果的完整恢复界面仍待接线，结果交接缺口保持open；这里只完成存储/Runtime层强杀和篡改校验，不新增模型请求、不执行恢复事件中的工具、不推进Goal完成状态。

## 2026-09-07 CLI 结果恢复界面与启动刷新修复

显式“取回模型回复”经ChatService串行请求、developer恢复服务核对原Turn/modelCall/Job绑定；优先回读已验证本地artifact，否则fetch后持久保存再精确确认。UI只展示TextDelta/Refusal正文，分页保留全部文字并支持选择，分页不拆开emoji代理对；不执行事件中的工具、不回填Agent上下文、不将中断Turn/Goal完成。consumer接口默认无Runtime操作。

API36首次恢复发现数据库已INTERRUPTED而页面条目未更新，已修复启动恢复提交后的ChatService刷新通知，见 [缺陷记录](../bug-fixes/2026-09-07-recovery-open-conversation-stale.md)。确定性回归两API各1/1；修复后生产ChatScreen三个交接窗口共6kill/12次实际查询/取回/正文断言通过，Turn/modelCall仍INTERRUPTED。证据 `build/main-verification/cli-recovered-reply-ui-summary.json`、cli-result-ui-final-api*-*/。

新增空正文/跨页emoji JVM覆盖；App Consumer300/300、Developer312/312无跳过。Consumer外部Connector测试两次HTTP读取超时原始记录保留，随后单独执行完整Consumer通过（cli-recovery-consumer-serial.log）；不推定并发为根因。相关构建、root lintDebug、Spotless/Detekt任务通过，原KDoc格式失败已修复；日志cli-recovered-reply-refresh-final.log、cli-recovery-consumer-serial.log保留各任务与失败/重跑边界。

剩余：完整Goal成功结果强杀链路，以及Runtime暂不可用时直接读取已经保存在本地的结果；当前恢复入口仍先query Runtime，因此这一点明确未完成。长稳与真机边界不变。

## 2026-09-07 Runtime 不可用时读取已保存回复

本地artifact发现现在独立于Runtime状态，首次打开会话即可显示“查看已保存回复”。未查询到Runtime成功状态时，该入口只调用本地回读：核对原modelCall/Turn与job_prepared绑定、合法Job ID、artifact归属、大小和SHA-256，不执行Runtime query/fetch/ack。用户先显式查询成功状态再取回时仍走原持久化后确认链路；本地读取不隐式确认待处理的Runtime记录。

宿主在确认成功结果并强杀主App后禁用CLI Runtime，恢复时实际点击生产ChatScreen本地结果入口，确认Runtime查询为Unavailable但正文HELIX_OK可读；两API共2kill/4恢复通过。finally将Runtime启用状态恢复原值0，并由dumpsys核对；不清理账号数据。随后原fetched/persisted/acknowledged联网取回窗口另6kill/12恢复通过。合计8kill/16恢复，证据 `build/main-verification/cli-local-reply-summary.json` 与八组cli-local-reply-api*-*/，禁用/恢复状态原文随组保存。

两发行包构建、测试APK、root lintDebug、Spotless、Detekt通过（cli-local-reply-build-final.log）；App JVM串行强制复验Consumer300/300、Developer312/312，无失败/跳过（cli-local-reply-jvm.log）。首次长行格式失败已修正后复跑，未放宽规则。完整Goal成功结果强杀接入仍需验证；本轮是绑定fixture与真实结果存储/UI，不替代Goal预算证据。

## 2026-09-07 完整 Goal 成功回复恢复与 CLI journal 容量修复

CliGoalProcessKillDeviceTest 新增成功模式：通过生产 Goal/Chat/订阅适配器执行真实跨 UID Runtime 的 helix-fixture，在私有结果已持久化并精确 ACK、首个模型事件尚未交回时，由测试包装器暂停并由宿主 SIGKILL 主 App。恢复经生产 ChatScreen 会话导航、查询和回复入口显示 HELIX_OK；Goal 保持 PAUSED，run/Turn 中断，modelCall/job_prepared 不增加，预算结算不重复。测试包装器仅在测试 APK 中，无生产暂停钩子。

API29/36 × CODEX/CLAUDE/GROK/COPILOT 全8组通过，8次强杀、16次恢复；运行中断 CODEX 分支另2次强杀、4次恢复通过。证据 `build/main-verification/cli-goal-success-summary.json`、cli-goal-success-final-api*-*/ 与 cli-goal-running-regression-api*/。全部使用合成模型，无订阅账号或远程模型调用。完整 Goal 的成功边界仅为持久化+ACK后、首事件前；其他 fetch/persist 窗口仍由此前绑定 fixture 的强杀矩阵覆盖，不能混称完整 Goal 全窗口。

过程中两台 Runtime 均累积128条日志，暴露已确认终态不回收导致新请求永久拒绝。现仅清理过期或容量压力下最旧的已确认终态，保留活动/未确认/损坏记录；有效回归先2失败再通过，CLI Runtime104/104、Client30/30 无跳过，Runtime构建、root lintDebug、Spotless、Detekt通过（cli-journal-retention-green.log）。首次失败的 Goal configure 已按所属 fixture 清理，未清除 Runtime 数据或账号；原始失败记录保留。

当前正常 CLI 路径的结果持久化后确认缺口收口。legacy reconcile 的破坏性兼容语义仍保留，不作为正常恢复路径；30天未确认记录 evidence-expired marker、其他生命周期矩阵及统一交互优化仍待办。ADR-0028 保持 proposed，整个开发 Goal 保持 active；长稳后置、真机和付费调用边界不变。

## 2026-09-07 CLI 未确认结果到期标记实现

按 ADR-0007 为未确认终态增加30天到期处理：启动恢复、新任务容量检查、显式查询和结果取回触发维护。先写入 EVIDENCE_EXPIRED，保留原Job ID、request SHA和终态时间，移除成功证明，再删除request/events及其临时文件。删除失败向上传播；重启看到marker后重试清理，不重新执行。marker不被已确认记录的容量回收清除，也不被legacy reconcile改写为已确认。活动任务不因创建时间较早而直接过期，墙钟回拨不会触发提前清理。

客户端codec可往返该终态，恢复服务映射到明确页面文案；本地已有artifact仍可走独立回读。旧客户端遇到新枚举不能解码，保持不可用边界；未新增破坏性兼容回退。首次JVM回归2项中到期断言失败、时间边界项通过；实现后新增删除失败/重复恢复与同Job不再执行断言，共3项通过。CLI Runtime107/107、Client30/30，无失败/跳过；两发行包及Runtime构建、root lintDebug、Spotless、Detekt通过。证据 cli-evidence-expiry-red.log、cli-evidence-expiry-verified.log；首轮测试通配导入导致格式门禁失败，已修正，原日志保留。

当前仅关闭实现与JVM门禁，不勾选整个到期待办：新状态跨UID Binder/PFD传输、实际恢复页面及设备文件清理仍需API29/36验收。没有修改系统时间、访问账号凭据、运行长稳或真机测试。整个Goal保持active。

## 2026-09-07 CLI 到期结果跨 UID 与恢复 UI 验收

run-cli-result-owner-kill.py 增加 --expired：真实Runtime生成合成结果，宿主强杀主App后，只将本次owned Job终态时间调整为31天前；不修改设备系统时间，不涉及账号。分别覆盖未保存到App的fetched边界、App已有副本的persisted边界。API29/36共4kill/8恢复通过：生产Binder返回EVIDENCE_EXPIRED且无events，ChatScreen实际查询显示过期文案；无副本时无取回入口，有副本时仍可显示HELIX_OK。Turn/modelCall保持INTERRUPTED，原绑定和调用数量不增加。宿主核对Runtime仅剩无成功证明、无ACK的record.json，然后仅清理本次fixture marker。

未过期persisted回归另2kill/4恢复通过。App强制串行JVM Consumer300/300、Developer312/312，零失败/跳过（cli-expiry-app-jvm.log）。测试APK构建、Spotless、Detekt通过；两发行包/Runtime构建和root lintDebug在本次生产实现对应的cli-evidence-expiry-verified.log中通过，后续修改仅测试定位与宿主脚本。汇总 build/main-verification/cli-evidence-expiry-summary.json 保存安装APK哈希和各组结果。

API29首次文案可见性失败、显式滚动后仍无法定位的日志均保留；测试改从Compose页面资源读取当前语言文案，并独立断言状态EVIDENCE_EXPIRED后通过。原fixture随后恢复与清理，再以最新测试APK重跑完整矩阵，不把失败算通过。此项覆盖真实文件/协议/UI，日期通过owned metadata加速，不是31天实时时间经过或完整Goal强杀。到期待办可关闭；长期满marker容量仍按拒绝新任务保护原身份，不自动删除或重放。

## 2026-09-07 CLI 累计修改后的全仓 JVM 与 Goal 双变体设备基线

在当前源码上按既有34个实际Test任务重新强制执行，禁用测试up-to-date/cache并使用 --max-workers=1；显式提供本地Connector验收样本。逐任务核对Gradle执行行与当前XML，共2629/2629，failure/error/skipped均0。源码与配置完整清单1069文件在执行前后hash一致。root lintDebug、SpotlessCheck、Detekt及Consumer测试APK构建任务通过。完整命令保存在 build/main-verification/post-cli-host-command.json，输出post-cli-full-host.log，聚合post-cli-full-host-result.json；不沿用此前2620项快照作为当前结果。

随后两台专用API29/36模拟器分别执行Developer和Consumer：GoalRunCoordinatorDeviceTest、GoalTurnBindingDeviceTest、GoalUsageReservationsDeviceTest、GoalModelCancellationDeviceTest、GoalDialogDeviceTest、GoalEditorDeviceTest，每组28/28，共112/112，无instrumentation跳过/失败。覆盖生产Goal创建/显式Continue、绑定、持久预留/恢复、loopback模型socket停止与wake限额、实际Goal表单；安装前后校验main/test APK哈希。证据 post-cli-goal-device-summary.json 与四组post-cli-goal-api*-*/command.json、instrumentation.log、result.json。

本组未执行真实订阅账号、完成条件验证或非长稳生命周期全矩阵。ADR-0028仍proposed；HXA-102及整个持续Goal仍未完成。24小时长稳保持后置，真机边界不变；未暂存、提交或推送并行工作。

## 2026-09-07 前台服务与前后台资源门禁当前回归

修正DataSyncForegroundServiceDeviceTest的验收语义：API35以下超时回调使用显式SdkSuppress，不再return伪装通过；等待成功后实例缺失必须失败；通知停止测试发送实际通知所附PendingIntent，替代手写等价ACTION_STOP Intent。此次只有测试修改，无生产服务行为改变。

API29选择三个适用服务方法和AndroidResourceGateDeviceTest，每发行包4/4；API36另加onTimeout回调，每发行包5/5。Consumer/Developer四组合计18/18，实际选择的方法无失败/跳过；API29超时方法明确不适用，未计入分母。20次快速启动停止后等待异步foreground promotion检查，确认无实例/通知；WAITING_APPROVAL停止；Activity CREATED时并发降到1、RESUMED重新读取实际MemoryInfo/thermal。证据 build/main-verification/lifecycle-current-summary.json 与四组lifecycle-current-api*-*/命令、原始instrumentation日志、安装APK hash。

两测试APK构建、Spotless、Detekt通过（lifecycle-current-build.log），文档/ADR/diff门禁通过。onTimeout为直接调用真实服务回调，不是等待Android六小时配额；资源探针读取真实状态，但没有制造低内存/温控压力。实际旋转、断网、其他运行后端生命周期仍待验；本组不关闭整个非长稳矩阵。24小时长稳保持后置。

## 2026-09-07 Goal 模型流期间 Activity 实际旋转

GoalModelCancellationDeviceTest增加旋转路径，在生产Goal/ChatService/OkHttp连接已建立且服务端持有SSE时启动MainActivity，requestedOrientation切换到相反方向，并等待实际Configuration.orientation变更。验证原Goal仍RUNNING、run/Turn ID清单不变、服务器仅收到一个持有请求且连接未断。finally恢复原方向请求并确认原orientation，再沿原停止流程检查socket断开、单次模型计账、run关闭、预留结清。测试Provider为本地loopback，结束恢复mode/budgets并删除所属Provider/Goal，归档所属session。

API29/36 × Consumer/Developer每组旋转1项、原显式Stop与wake限额2项，合计12/12，无失败/跳过；其中新增旋转4项、原回归8项，不混算成12项旋转。证据 build/main-verification/goal-rotation-summary.json 与四组goal-rotation-api*-*/，含实际命令和安装main/test APK hash。两测试APK构建、Spotless、Detekt通过（goal-rotation-build.log）；本轮未改变生产代码。

这里是真实Activity配置方向变化，非单独scenario.recreate替代，但也不声称物理旋转传感器验收。断网、真实内存压力和其他后端生命周期仍有剩余；ADR-0028决定及统一交互阶段保持待办，整个Goal未完成。

## 2026-09-07 Goal 部分模型流连接中断

LoopbackModelServer暴露测试持有socket，GoalModelCancellationDeviceTest在生产请求收到HTTP/SSE部分正文后主动关闭服务端连接；不调用chat.stop代替断连。生产OkHttp/解码/Goal结算路径将Goal、run outcome与Turn置为FAILED，模型调用一次、run结束、wake用量清零、预留无剩余且无工具调用。服务端请求计数保持1，不自动重发。原停止、wake限时和实际旋转路径同时复跑。

API29/36 × Consumer/Developer每组4/4，共16/16，无失败/跳过；其中新增连接中断4项、原回归12项。证据 build/main-verification/goal-disconnect-summary.json、四组goal-disconnect-verified-api*-*/，保留命令、日志、main/test APK hash。两测试APK构建、Spotless、Detekt通过（goal-disconnect-wired-build.log）。本次只修改测试服务与设备用例，无生产逻辑修改。

首轮两Developer组合中断项因测试分支漏接socket.close而超时，实际未断连，不能视作产品网络故障；原goal-disconnect-api29/36-developer日志保留，所属fixture由finally清理。补接后重跑四组合，不将失败或未运行Consumer算通过。本组验证部分SSE连接丢失，不是飞行模式、Wi-Fi/蜂窝网络整体丢失、DNS/TLS故障或真机网络切换，相关边界仍保留。

## 2026-09-07 PRoot 成功结果恢复缺口与过期状态修正

源码复核确认ProotJobClient仅submit/query/cancel/reconcile；ProductionLinuxExecutor收到的output PFD落在随机临时目录，finally删除且没有持久结果定位绑定。Runtime虽保留output.zip，但未提供非破坏性重新取回；现有reconcile会记录确认并删除载荷。因此查询/停止或进程死亡后的临时残留不能充当成功结果恢复验收。后续按 [具体缺口](proot-result-durable-recovery-gap.md) 补取回、私有持久化、精确确认与只读展示，工作区新写入仍走既有工具审批。

另修正evidenceExpired=true的SUCCEEDED记录仍显示成功的错误，状态映射优先显示过期说明且无Stop；中英文资源同步。有效JVM回归先失败后通过。首次全组Developer遇外部Connector HTTP/2 SocketTimeoutException，原proot-expiry-report-green.log保留；完整复跑Developer313/313、Consumer300/300，无跳过（proot-expiry-report-retry.log、proot-expiry-report-jvm-summary.json）。该轮随后Detekt发现ReturnCount，改为等价if表达式后定向回归1/1、双包构建、root lintDebug、Spotless、Detekt通过（proot-expiry-report-final.log）。未放宽规则或删除测试。

本组只修正状态映射并明确缺失协议；未宣称PRoot结果恢复完成。过期状态实际设备导航、归档取回全链路仍待验，整个开发Goal保持active。

## 2026-09-07 PRoot 非破坏性结果取回原语

增加additive TX_JOB_FETCH_RESULT事务（原协议版本不变，旧Runtime不支持时无破坏性回退），Runtime通过可选ProotJobResultHandler返回SUCCEEDED、未过期、未确认且归档存在的只读PFD和终态记录。归档压缩字节上限136MiB，容纳原128MiB未压缩上限及封装开销；App后续仍需独立执行ZipJobExtractor/manifest校验。ProotResultClient核对返回记录与调用方原记录完全一致及描述符长度，成功后由调用方关闭PFD，验证异常关闭描述符。取回不提交、不取消、不reconcile，不导入或执行内容。

API29/36 ProotResultArchiveDeviceTest各2/2：测试handler接入真实ProotRuntimeServiceBinder/Parcel/PFD，两次读取保留原未确认记录与归档；过期、已确认、文件缺失和RUNNING均拒绝。证据 build/main-verification/proot-result-fetch-summary.json、proot-result-fetch-api29/36.log，含安装Runtime/test APK hash。字节为合成归档载荷，handler在Runtime测试进程内，不声称跨UID、真实PRoot执行或内容完整性验收。临时fixture目录在finally删除，不影响安装资产。

PRoot Client23/23、IPC40/40 JVM无失败/跳过；Runtime及测试APK构建、root lintDebug、Spotless、Detekt通过（proot-result-fetch-final-build.log）。首轮通用catch和复杂条件静态失败已按规则修正，保留原日志。客户端尚未接入产品恢复入口，私有持久保存/确认、跨UID强杀与UI仍见开放缺口；整个Goal保持active。

## 2026-09-07 PRoot 精确确认与删除失败修复

新增TX_JOB_ACK_RESULT，校验原terminalCommit（包含jobId/executionId/input与output manifest及终态字段）后才允许确认；ProotResultClient无旧协议破坏性回退。ProotJobStore确认与legacy reconcile均改成先检查完整载荷删除成功再保存回执；重复确认返回首次时间，过期证据不变造为已确认。显式确认与对账在store锁内串行。本次尚未把新确认接口接入App产品流程，调用前私有持久化仍是必须补齐的前置。

API29有效故障夹具将payload子目录设为只读，旧实现未抛错并继续确认，红日志proot-ack-red-api29.log保留；修复后原记录不变、删除失败抛出。两API各5/5：错误指纹拒绝/归档保留、正确确认/重复回执、删除失败、重复fetch与无效结果拒绝、真实PRoot作业legacy reconcile。证据 build/main-verification/proot-ack-summary.json、proot-ack-api29/36.log；权限及本次临时目录finally恢复/清理，无安装资产删除。新ACK的跨UID事务尚待专门执行，不能由直接store测试冒充。

PRoot Client/IPC既有JVM任务通过（23/40），Runtime与测试APK构建、root lintDebug、Spotless、Detekt通过（proot-ack-build.log）。归档内容验证、App持久导入与恢复UI仍保持开放缺口；整个Goal不标记完成。

### 2026-09-07：PRoot 大归档登记的流式校验前置修复

制品登记原先通过 `file.readBytes()` 校验 SHA-256，会为整个归档分配内存；现在复用新增的流式文件哈希重载，校验失败仍抛出异常。storage JVM 75/75 通过（含空文件、缓冲区边界及缺失文件测试），根级 `lintDebug`、Spotless 与 Detekt 通过（Lint 日志：`build/main-verification/proot-artifact-stream-lint.log`）。独立 JVM 在 32 MiB 最大堆下成功校验 128 MiB 文件，并与生成时计算的 SHA-256 一致；这证明该哈希路径不再需要整文件大小的堆分配，不代表 Android 全链路内存验收。证据：`build/main-verification/proot-artifact-stream-summary.json`、`proot-artifact-stream-hash.log` 和 `proot-stream-hash-low-heap/result.log`。首次独立验证因编译类目录配置错误未运行到产品代码，原始日志保留为 `classpath-error.log`。

PRoot 私有归档持久保存、回读验证后 ACK、只读恢复 UI 及跨 UID/进程终止矩阵仍待完成；此项不关闭结果恢复缺口。

### 2026-09-07：PRoot 私有结果保存组件

新增 `ProotResultStore`：核对原 Turn/ToolCall 与 job/execution/input manifest 绑定，限量接收归档，使用既有严格 ZIP 提取校验核对输出 manifest，原子写入会话私有制品并流式回读校验。重复保存核对既有登记，不覆盖已登记的不同结果；组件不发送 ACK、不执行归档内容、不导入用户工作区文件。

API29/36 各 2/2 通过，覆盖真实 Room 登记、重复保存、内容损坏、错误 execution/manifest、已有结果保留和会话删除返回制品清理路径。这里使用生成的合法 ZIP 与绑定夹具；未证明 Runtime 跨 UID 获取/ACK、进程终止恢复或隐私删除服务实际删除文件。Developer 与测试 APK 构建、根级 lintDebug、Spotless、Detekt 通过。证据：`build/main-verification/proot-result-store-summary.json`、`proot-result-store-emulator-5596.log`、`proot-result-store-emulator-5598.log`、`proot-result-store-verified-build.log`、`proot-result-store-lint.log`。前几次新增测试格式检查失败日志保留，最终修正后通过。

下一步将组件接入获取结果→持久保存及回读→精确 ACK，并完成只读结果 UI 与跨 UID/进程终止矩阵；结果恢复缺口仍开放。

### 2026-09-07：PRoot 恢复协调器的保存/确认顺序

新增 `ProotResultRecovery`，提供生产 Client 工厂和原调用恢复入口：仅允许 INTERRUPTED Turn，校验结果并持久保存/回读后才调用精确 ACK；已有本地归档也重新核对当前记录的 manifest 后才确认。`localOnly` 只读取已验证本地制品，不查询或唤起 Runtime。确认不可用时不会将其标为已确认，组件不提交新 Job、不完成 Goal。

API29/36 各 4/4 通过（含上一轮保存回归）：新增测试在 ACK 回调中验证落盘制品可读、错误 manifest 的 ACK 次数为零、本地读取不调用任一 Runtime 接口。使用真实 Room、合法 ZIP 和 PFD，query/fetch/ACK 为注入夹具；不冒充跨 UID Binder 验收。Developer/测试 APK、Spotless、Detekt、根级 lintDebug 通过；证据：`build/main-verification/proot-result-recovery-summary.json`、`proot-result-recovery-emulator-5596.log`、`proot-result-recovery-emulator-5598.log`、`proot-result-recovery-verified-build.log`、`proot-result-recovery-lint.log`。初次新增测试行宽失败日志保留。

待接入恢复界面，补真实 Runtime ACK 与进程终止矩阵，并处理正常执行路径的持久结果；完整结果恢复缺口仍开放。

### 2026-09-07：真实 PRoot 归档保存与跨 UID 确认

跨 APK 回归发现生产执行只写调用方 PFD、未生成 Runtime `output.zip`，导致成功后 fetch 返回空；现已先构建、同步并原子保存 Runtime 归档，再传给调用方。相同用例在 API29/36 从失败转为通过：重复 fetch、错误 ACK 拒绝、App 持久保存后精确 ACK、回执保持、ACK 后 Runtime 无归档、本地仍可读。真实命令运行于 PRoot，只有 INTERRUPTED Turn 的归属为夹具，不是完整 Goal/进程终止验收。

跨 UID 2/2 和 Runtime 执行/取消/超时/确认/归档回归 28/28 通过，Runtime/测试构建、根级 lintDebug、Spotless、Detekt 通过。证据：`build/main-verification/proot-cross-uid-result-summary.json`、`proot-cross-uid-result-fixed-emulator-5596.log`、`proot-cross-uid-result-fixed-emulator-5598.log`、`proot-retained-output-regression-emulator-5596.log`、`proot-retained-output-regression-emulator-5598.log`。原始失败日志保留为 `proot-cross-uid-result-emulator-5596.log` / `proot-cross-uid-result-emulator-5598.log`。缺陷记录：`docs/bug-fixes/2026-09-07-proot-result-missing-runtime-archive.md`。

剩余：恢复界面、进程终止窗口、正常 App 执行路径的持久制品；首次输出传输失败仍按既有规则记 FAILED，其结果恢复尚需另行覆盖。完整缺口仍开放。

### 2026-09-07：PRoot 只读结果预览数据

新增 `ProotResultPreview` 与跨分发共享的展示数据类型，严格校验并在临时目录提取已恢复归档，分别读取 stdout/stderr（各最多 65536 个 UTF-16 code units），截断时不拆分代理对，并保留显式截断标记；文件列表包含原路径、大小和 SHA-256。预览不执行内容，不导入工作区，完整归档保持不变。Developer 的恢复模块提供恢复并生成预览的入口；Consumer 保持能力不可用。

新增 JVM 4/4 通过：输出/文件元数据与归档保持、代理对截断、恰好达到限制、坏归档拒绝及临时提取清理。Developer/Consumer 编译、根级 lintDebug、Spotless、Detekt 通过。证据：`build/main-verification/proot-result-preview-summary.json`、`proot-result-preview-build.log`、`proot-result-preview-lint.log`。这不是 UI 验收：聊天卡片的数据状态、查看入口与展示组件仍待接入，随后进行模拟器界面与进程终止矩阵。

### 2026-09-07：聊天卡片接入 PRoot 结果查看

聊天恢复卡片增加查看入口，ChatService 先尝试本地已验证归档，再按需获取原作业结果、保存并确认；加载期间禁用重复操作，失败显示不可用提示，取消不吞掉协程取消。timeline 保留预览状态，显示 stdout/stderr、归档清单及截断提示，支持分页和选择复制，未执行任何恢复内容或完成 Goal。三组中英文资源已补齐。

API29/36 各 6/6 通过：Compose 原调用身份路由、分页/末页禁用，以及四项保存和 ACK 顺序回归。Developer/Consumer APK、Developer 测试 APK、Spotless、Detekt、根级 lintDebug 通过。证据：`build/main-verification/proot-result-ui-summary.json`、`proot-result-ui-emulator-5596.log`、`proot-result-ui-emulator-5598.log`、`proot-result-ui-verified-build.log`、`proot-result-ui-lint.log`。

界面入口已接入生产调用，但本轮组件测试不等同完整 ChatService→真实 Runtime→结果界面端到端验收。下一步补原进程终止后的真实结果恢复、离线本地查看和完整聊天界面验证；正常 App 执行路径持久制品及首次传输失败恢复仍开放。

### 2026-09-07：调用方死亡后的 Runtime 冻结诊断

新增 `--successful` 诊断路径，运行中 SIGKILL App 后观察原 Job，再经生产 ChatService/恢复按钮查看。API29 实测 1 次 SIGKILL、2 次重启读取成功且模型请求未增加；API36 未通过：原 Runtime/guest 仍存活，journal 保持 RUNNING，`dumpsys activity processes` 明确记录 `isFrozen=true`。重连后 Job 成为 TIMED_OUT（stdout 已有内容），不能按 SUCCEEDED 接受。停止验证因实际已超时而未满足 CANCELLED 断言，之后 `abort` 清理成功；原 Runtime Job 终态证据保留。证据位于 `build/main-verification/proot-success-kill-emulator-5596/` 和 `proot-success-kill-emulator-5598/`（含 runtime-process.txt、cancel-recover.log、cleanup-abort.log）。

ADR-0007 第 7～9 条要求：没有匹配的有效 FGS 继续路径时，Binder death 应终止 Job。故“任意 Shell 在 App 死亡后继续成功”不是当前验收目标；本次 API29 结果仅是诊断，不能当作后台继续能力通过。后续需补调用方死亡的终止契约，并把成功恢复窗口改为 terminal commit 已完成、App 尚未消费结果时 SIGKILL。不得禁用 freezer、后台常驻或扩大 service type 来制造成功。新增测试静态检查与 APK 构建通过（首次 TooManyFunctions 已拆分修正），总体场景仍未通过。

### 2026-09-07：修复 PRoot 排队取消失效

为调用方死亡处理核查取消路径时，发现 PENDING 作业取消没有传到执行线程。API29 新测试复现取消后仍 SUCCEEDED；现已共享每个 Job 的取消标记，并在 live process 登记后补查取消。API29/36 各 11/11 Runtime 回归通过，验证排队作业 CANCELLED 且未执行写文件命令；Runtime/测试构建、Spotless、Detekt 通过。证据：`build/main-verification/proot-pending-cancel-summary.json` 及对应 emulator 日志，红测日志为 `proot-pending-cancel-red-api29.log`。缺陷记录：`docs/bug-fixes/2026-09-07-proot-pending-cancel-ignored.md`。

这只是调用方死亡取消的前置修复。提交协议尚无 owner Binder/death recipient，不可据此声称 App 死亡会自动取消；下一步接入该通知并验证排队/运行/终态窗口。

### 2026-09-07：已接入 PRoot 调用方死亡取消

生产 Client 通过新增 owned-submit 事务发送进程 Binder，Runtime 为新接收的 Job 监听死亡并取消原 Job，终态释放监听；重复提交不替换 owner，普通 query/unbind 不触发取消，旧 Runtime 不支持时不回退 legacy submit。未增加 FGS、后台常驻或 freezer 绕过。

API29/36 共 2 次运行中 SIGKILL，主机在 App 重启前核实原 journal 已 CANCELLED；随后 4 次恢复通过且模型请求数未增长。正常跨 UID 成功执行、重复 fetch、保存后 ACK 回归 2/2 通过。证据：`build/main-verification/proot-owner-death-summary.json`、两个 `proot-owner-death-emulator-*/result.json` 及 `proot-owner-normal-emulator-*.log`。Client/IPC JVM、Runtime/App/test 构建、Spotless、Detekt、根级 lintDebug 通过（`proot-owner-death-final-build.log`）；首次缺失 IBinder import 编译失败已修正并保留日志。

缺陷记录：`docs/bug-fixes/2026-09-07-proot-owner-death-not-cancelled.md`。Legacy 原始提交仍无 owner；当前生产 Client 不再使用它。待补 dead-at-submit/排队 owner-death/终态 race，以及 terminal commit 已完成、App 未消费时终止进程的成功结果恢复。

### 2026-09-07：PRoot 终态提交后、App 消费前的真实恢复

`--successful` 已改为合法终态窗口：测试主机确认原 guest 开始后 SIGSTOP App（PID 归属核实），等待 Runtime SUCCEEDED/terminalCommit，再 SIGKILL 同一 PID。失败路径恢复 SIGCONT，避免遗留暂停进程；不会用“App 死亡后任意 Shell 继续执行”作为验收条件。旧诊断结果不追溯改成通过。

API29/36 共 2 次终态后 SIGKILL、4 次重启恢复通过：生产 Goal/Chat/Dispatcher/审批/PRoot 执行，点击生产恢复卡片，经 ChatService 保存并确认结果，断言实际文本节点含原输出；第二次重启仍可查看，Turn 保持 INTERRUPTED，模型请求计数未增加。终止前后 terminalCommit 一致。证据：`build/main-verification/proot-terminal-ui-kill-summary.json`、两组 `proot-terminal-ui-kill-emulator-*/terminal-before-kill.json` 与 phase 日志。Runtime 安装哈希另核实于 `proot-terminal-ui-runtime-hashes.json`。测试 APK、Spotless、Detekt 与 Python 语法检查通过（`proot-terminal-ui-kill-build.log`）；本轮未改生产代码。

该证据覆盖生产调用链与恢复卡片，不冒充全屏视觉验收、Runtime 不可用的离线恢复、App 保存/ACK 之间的终止或正常执行路径持久制品。这些边界以及 owner 的 dead-at-submit/排队/终态竞态仍待完成。

### 2026-09-07：Runtime 禁用后的本地结果查看

新增 `--successful --offline-final`：真实 Goal 的原 PRoot Job 终态后终止 App，首次重启通过生产恢复链保存并确认；随后核实测试 Runtime 被 disable-user，第二次重启只点击查看入口，不先查询 Runtime。实际文本节点继续显示原 stdout，Turn 仍 INTERRUPTED，模型请求数未增加。API29/36 共 2 次终止、4 次恢复通过，其中 2 次为 Runtime 禁用时的本地结果查看。

主机仅对默认启用的专用测试 Runtime 执行禁用，并在 finally 恢复；已另外核实两台 `enabled=0` 且 `stopped=false`。证据：`build/main-verification/proot-offline-summary.json`、两组 `proot-offline-kill-emulator-*/runtime-disabled.txt` / phase 日志及 `proot-offline-restored.json`。测试 APK、Spotless、Detekt、Python 语法及文档检查通过（`proot-offline-verified-build.log`）；初次测试方法 LongMethod 已拆分，原日志保留。本轮没有改动生产代码。

尚待验证保存/ACK 中途终止和 owner 边界；正常执行路径的持久制品、首次输出传输失败恢复等仍未关闭。

### 2026-09-07：PRoot 保存及 ACK 边界进程终止

新增恢复协调器确认回调的测试暂停点：真实 Goal/Job 终态后先终止 App；恢复使用真实 ZIP、Room 持久保存和 Binder Client，在“回读完成、ACK 前”或“精确 ACK 后”再 SIGKILL。主机在第二次终止前读取原 Runtime journal，分别核实无回执/有回执，避免只靠暂停标记宣称命中窗口。

API29/36 × persisted/acknowledged 四组全通过，共 8 次 SIGKILL、8 次后续 UI 恢复，文本节点显示原输出，Turn 保持 INTERRUPTED，模型请求数未增加。证据：`build/main-verification/proot-result-boundary-summary.json` 和四组 `proot-result-{persisted,acknowledged}-emulator-*/result-boundary-record.json` / phase 日志。测试 APK、Spotless、Detekt、Python 语法和文档检查通过（`proot-result-boundary-build.log`），本轮未改生产代码。

暂停点使用测试注入的确认回调；后续恢复按钮走生产 ChatService。本轮证明归档数据在这些窗口存活，不证明所有 ACK 重试/清理策略：已有本地结果走 localOnly，既有测试末尾仍调用 legacy reconcile 清理 Runtime。该边界不可冒充 UI 自动重试精确 ACK。正常执行路径持久制品、首次传输失败恢复及 owner 竞态仍待完成。

### 2026-09-07：正常 PRoot 执行接入持久制品

`ProductionLinuxExecutor` 在输出 manifest 验证后、工作区导入和返回成功前调用必填持久保存回调；生产 `ProotToolModule` 使用原 Turn/ToolCall 的 `ProotResultStore` 保存会话私有归档。保存失败返回 `OUTPUT_PERSIST_FAILED` 并要求核查原 Job，不伪造成功或重放。直接 IPC 测试没有会话归属时显式提供测试回调，生产装配不使用空实现。

API29/36 各 6/6 LinuxRunTool 执行回归通过；新增 Room 归属夹具围绕真实正常执行器，确认 execute 返回、scratch 清理后仍能读取登记归档和原 stdout，同时保留工作区输出导入验证。该夹具并非完整 Goal 新增验收。证据：`build/main-verification/proot-normal-persist-summary.json`、`proot-normal-persist-emulator-5596.log`、`proot-normal-persist-emulator-5598.log`。App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-normal-persist-verified-build.log`）。

本轮接入持久保存，未把正常路径 ACK 清理一并宣告完成；仍需覆盖保存失败、确认失败/重试、首次输出传输失败和 owner 竞态，再刷新全量门禁。

### 2026-09-07：正常路径精确 ACK 与失败证据

新增 `ProotResultCommitter`，生产装配在保存与回读成功后调用精确 ACK，并写 `proot.result_ack` 审计。ACK 不可用或 RemoteException 时记录 acknowledged=false，保留已验证本地结果，不因此重放成功命令；无效回执仍拒绝。持久保存失败在调用 ACK 前退出，执行器沿既有 OUTPUT_PERSIST_FAILED 核查路径处理。

API29/36 各 12/12 通过：正常 ProductionLinuxExecutor 执行及 Room 归属夹具验证 ACK 后 Runtime fetch 不再提供归档、本地 stdout 仍可读；新失败测试验证不可写目标下 ACK 次数为零，RemoteException 下结果保留且审计明确未确认。还包括现有执行/保存/恢复回归。证据：`build/main-verification/proot-normal-ack-summary.json`、`proot-normal-ack-emulator-5596.log`、`proot-normal-ack-emulator-5598.log`。App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-normal-ack-build.log`）。

正常保存后确认已接入；待确认审计不等于自动重试清理已实现。首次输出传输失败恢复、owner 竞态及最终全量回归仍开放。

### 2026-09-07：修复 owner 监听晚于作业启动的竞态

发现 owned submit 先调用普通 submit 入队，再 linkToDeath；已死 owner 的注册异常到达前命令可能已成功。API29 用延迟注册并报 RemoteException 的 Binder 夹具复现 SUCCEEDED（期望 CANCELLED）。现已共享同步提交路径：判重/预算、PENDING、取消标记、owner 监听全部完成后才入队，重复提交不替换 owner。

API29/36 各 12/12 runner 回归通过，新测试同时断言 CANCELLED 与未生成命令文件。真实生产 Goal 运行中 owner SIGKILL 又完成 2 次、后续 4 次恢复通过，App 重启前 journal 已取消且模型请求未增加。证据：`build/main-verification/proot-owner-order-summary.json`、`proot-dead-owner-emulator-*.log`、两组 `proot-owner-order-kill-emulator-*/result.json`；红测为 `proot-dead-owner-red-api29.log`。Runtime/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-dead-owner-build.log`）。缺陷记录：`docs/bug-fixes/2026-09-07-proot-job-start-before-owner-link.md`。

已死 owner 注册测试是可控 Binder 夹具，真实跨进程 SIGKILL 是另一组证据；不混为同一测试。排队 owner 死亡、释放/重复提交竞态及首次传输失败恢复仍需收口。

### 2026-09-07：首次结果传输失败保留 Runtime 成功证据

真实 runner 配合只读输出 PFD 复现：原命令成功且 Runtime 已保存 ZIP，但初次写失败使终态变为 FAILED，fetch 因而拒绝。现已分离持久归档和瞬时传输结果：仅传输 IOException 写独立诊断，保留原执行状态及 manifest；归档构建/保存失败仍走失败路径，不重放命令。

API29/36 各 13/13 runner 测试通过，新用例核实初次目标为空、重新 fetch 的记录完全一致、ZIP manifest 校验及 stdout 原文。证据：`build/main-verification/proot-delivery-summary.json`、`proot-delivery-emulator-5596.log`、`proot-delivery-emulator-5598.log`；红测 `proot-delivery-red-api29.log`。Runtime/测试 APK、Spotless、Detekt、根级 lintDebug 通过（`proot-delivery-final-build.log`）。最初模块级 Spotless 任务名错误和 terminal 方法长度静态失败已修正，原日志保留。缺陷：`docs/bug-fixes/2026-09-07-proot-transfer-failure-discarded-terminal-success.md`。

本轮仅关闭 Runtime 终态误判。继续检查发现 `ProductionLinuxExecutor` 的 OUTPUT_MISSING/OUTPUT_VERIFY_FAILED/OUTPUT_HASH_MISMATCH 尚未设置 requiresReview，而 UI 恢复入口依赖 INTERRUPTED；必须继续接通该失败路径并验证，不能声称首次传输失败的端到端 UI 恢复已完成。部分传输、归档构建失败注入、owner 排队/释放边界、ACK 重试及最终全量门禁仍开放。

### 2026-09-07：输出验证失败进入待核查并开放原结果恢复

`ProductionLinuxExecutor` 的输出缺失、manifest 缺失、归档校验失败、hash 不符均设置 requiresReview。继续追踪确认生产结算为 FAILED Turn / NEEDS_REVIEW ToolCall；新增共享资格判断，Chat timeline、query/stop 和结果恢复一致支持该组合及既有 INTERRUPTED 组合，运行中的调用不开放。读取恢复不更改失败 Turn，不完成 Goal、不重发命令。

API29/36 各 15/15 通过（执行器、结果存储、UI 组件）；新增用例真实跨 UID 执行后将 App 暂存输出清空或损坏，断言待核查，然后按原 Job 恢复 stdout、登记一个制品、精确 ACK 且提交次数始终为一。恢复阶段 FAILED/NEEDS_REVIEW 由夹具建立，不冒充完整 Goal 结算/导航验收。资格 JVM 测试遍历全部 Turn/ToolCall 状态组合，1/1 通过。证据：`build/main-verification/proot-output-review-summary.json`、`proot-output-review-verified-emulator-*.log`。红测 `proot-output-review-red-api29.log` 明确记录 requiresReview=false。

App 构建、Spotless、Detekt、根级 lintDebug 通过（`proot-output-review-eligibility-build.log`）；夹具非法 CREATED→FAILED 转换由状态机正确拒绝，随后改为合法转换并补齐 errorCode 参数，测试/静态构建通过（`proot-output-review-fixture-final-build.log`），失败日志保留。缺陷记录：`docs/bug-fixes/2026-09-07-proot-output-failure-missing-review-recovery.md`。

完整 Goal 中注入传输故障并从生产界面查看、缺失 manifest/hash 不符故障注入、owner 排队/释放边界、ACK 重试与最终全量门禁仍待验证；整个恢复缺口及 Goal 保持开放。

### 2026-09-07：完整 Goal 输出丢失与生产恢复动作验证

新增 `scripts/run-proot-goal-delivery-failure.py` 及专用 fixture phase：真实创建 Goal、显式 Continue、模型 ToolCall、精确审批、生产 Dispatcher 与跨 UID PRoot 执行。先确认原 Job RUNNING，再仅 unlink 本次新建 scratch 下的 output.zip；Runtime 持有已打开 PFD，保留独立归档。未改生产代码或注入伪终态。

API29/36 两组均通过：生产结算为 INPUT_REQUIRED Goal、FAILED Turn、NEEDS_REVIEW ToolCall，run outcome 为 INPUT_REQUIRED(NEEDS_REVIEW)，待结算 reservation 为空。生产 ChatService timeline 显示恢复入口，Compose 中渲染生产 query/view 动作，点击后文本节点显示原 PROOT_RESULT_READY；精确 ACK 已写回原 Job。读取前后 Goal（含预算）、Turn、ToolCall 完全一致，prepared audit 始终一条；每台模型请求 4 次（含连接探测），没有重放。测试结束恢复本 fixture 设置并清理拥有的 Goal/Provider。

证据：`build/main-verification/proot-goal-delivery-summary.json`、两组 `proot-goal-delivery-verified-emulator-*/result.json` / instrumentation.log。测试 APK、Spotless、Detekt 通过（`proot-goal-delivery-final-build.log`），Python 语法和文档检查通过。初次 fixture 误等 PAUSED，实际状态为 INPUT_REQUIRED；另一台准备 audit 早于 Runtime submit，现先轮询确认 RUNNING。初次失败与 abort 日志保留，其中一次 cleanup 在 active run 尚未结算时被正确拒绝，结算后重试清理通过。

本轮验证完整 Goal 生产结算及恢复动作，恢复控件置于测试 Compose 容器，未冒充整套 App 页面导航或竞品/UI 优化验收；故障为初始结果文件丢失，Runtime IOException 注入是前一轮独立测试。部分传输、manifest/hash 故障、owner 排队/释放、ACK 重试与最终全量回归仍待完成。

### 2026-09-07：owner 排队、重复归属与释放边界回归

新增可控 Binder owner 夹具与三组验证：真实 runner 首个 Job 占队时，第二个 owned Job 保持 PENDING，重复提交不注册替代 owner；原 owner 死亡使其 CANCELLED，命令文件没有创建。正常成功作业完成后仅解绑一次，后续 owner death 不改变终态。另以两个线程同步起跑，100 次交错检查已排队 death callback 和 terminal release 并发时仅 unlink 一次，重复 release 保持幂等。

API29/36 各 16/16 通过，共 200 次并发交错，已核对两台安装 Runtime hash 与本地构建一致。证据：`build/main-verification/proot-owner-boundaries-summary.json`、`proot-owner-boundaries-emulator-5596.log`、`proot-owner-boundaries-emulator-5598.log`。测试 APK、Spotless、Detekt 通过（`proot-owner-boundaries-build.log`），文档校验与 diff check 通过。本轮未改生产代码。

本轮 owner 回调为确定性测试夹具，不冒充跨进程 death；真实运行中 SIGKILL 已有独立证据。以上已覆盖已列出的排队/重复归属/释放边界，不声称证明所有可能线程调度。继续处理结果 ACK 重试、未覆盖的归档失败分支与最终全量验收，整个 Goal 保持运行。

### 2026-09-07：恢复 ACK 响应丢失不再隐藏本地结果

发现恢复协调器在私有 ZIP 保存/回读后直接传播 ACK RemoteException，ChatService 因而显示结果不可用；API29 红测复现。现将 ACK 不可用与归档读取分离，RemoteException 返回已验证文件及 acknowledged=false，非空回执要求原 terminalCommit 和确认时间均有效。

API29/36 各 14/14 通过。新增夹具模拟 ACK 已生效但响应丢失：首次返回本地结果未确认，localOnly 不调用 Runtime；随后显式协调器恢复查询到原记录已有回执，重验本地 ZIP 后重试 ACK，fetch 始终一次、制品仅一个、hash 和 INTERRUPTED Turn 不变。证据：`build/main-verification/proot-recovery-ack-summary.json`、`proot-recovery-ack-emulator-*.log`，红测 `proot-recovery-ack-red-api29.log`。App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-recovery-ack-build.log`）；文档和 diff 检查通过。缺陷：`docs/bug-fixes/2026-09-07-proot-recovery-ack-loss-hid-local-result.md`。

ACK 丢失通过回调注入，不冒充实际 Binder kill；当前产品 view 仍优先 localOnly。待确认提示与可操作的产品重试入口、正常执行后未确认清理，以及最终全量门禁仍需完成。

### 2026-09-07：结果确认状态与显式重试入口

结果预览增加三态确认信息：本地读取尚未核查（null）、确认未完成（false）、已确认（true）。结果卡新增“确认结果已保存”动作，等待期间禁用，确认后隐藏；独立 ChatService 入口调用原记录重验/精确 ACK，失败保留当前结果。普通“查看”仍优先 localOnly，不为显示已保存内容被动绑定 Runtime。COMPLETED Turn / SUCCEEDED Call 也开放结果读取与确认，供正常执行后待确认副本收尾；不改变 Turn/Goal 状态、执行工具或自动重发。

API29/36 各 17/17 执行器/存储/UI 组件回归通过；确认按钮原 turn/call 绑定、忙碌禁用、确认后消失均已测。资格 JVM 状态组合 1/1 通过。完整 Goal 输出丢失场景又通过两台：首次恢复后重复本地查看产生“尚未核查”，再点击生产确认按钮，经真实 Binder 幂等 ACK 回到已确认，原 Goal/Turn/Call 和预算不变，每台模型请求保持 4 次（含探测）。证据：`build/main-verification/proot-ack-ui-summary.json`、`proot-ack-ui-emulator-*.log` 和两组 `proot-goal-ack-ui-emulator-*/result.json`。

App/测试 APK、Spotless、Detekt、根级 lintDebug 通过（`proot-ack-ui-gates-build.log`），JVM 执行日志在 `proot-ack-ui-final-build.log`（该次随后因测试方法长度静态失败，已拆分夹具渲染修正并再次通过完整静态门禁）。首次代码替换断言、Compose 断言导入与命名/长度失败日志保留。三个语言资源同步；文档与 diff 检查通过。

入口已接入；正常完成调用的独立生产导航、真实 ACK 响应丢失后从界面重试仍需专项验证，不能由本轮已确认回执的幂等测试冒充。跨重启仅本地读取显示“尚未核查”，不会伪造已确认状态；没有后台无限重试或主动 Runtime 保活。其余归档异常和最终全量门禁仍待完成。

### 2026-09-07：正常 Goal 结果验收发现并修复状态域混淆

上一轮“COMPLETED Turn / SUCCEEDED Call”描述及判断有误：真实 App ToolCall 成功态为 COMPLETED，SUCCEEDED 属于 Runtime Job。完整正常 Goal 读取实际状态暴露问题；修正预期枚举后 JVM 红测也失败，原 XML 在 `build/main-verification/proot-completed-state-red.xml`。现使用 App 枚举进行成功资格判断，预期组合也改为枚举，避免不存在的字符串未被循环遍历却虚假通过。

API29/36 × 正常完成/初始输出丢失，共四组真实 Goal 场景通过。正常路径模型收到 ToolResult 后结束回复，Turn/Call 均 COMPLETED，Goal 保持 PAUSED / RUN_FINISHED；生产 ChatService 结果动作可显示持久 stdout，重复本地读取后点击确认，经 Binder 精确 ACK 成功。Goal（含预算）、Turn、Call 前后完全相同，每台请求 5 次（含探测）。输出丢失回归仍为 INPUT_REQUIRED / NEEDS_REVIEW，每台 4 请求，无重发。两组皆完成本 fixture 清理。

证据：`build/main-verification/proot-normal-goal-summary.json`、`proot-normal-goal-verified-emulator-*/result.json`、`proot-after-normal-failure-emulator-*/result.json`。资格 JVM 1/1、App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-normal-goal-fixed-build.log`）；Python/文档与 diff 检查通过。缺陷：`docs/bug-fixes/2026-09-07-proot-completed-call-state-mismatch.md`。

模型仍为脚本服务，恢复控件位于测试 Compose 容器但绑定生产 ChatService，未冒充完整页面导航/真实账号验收。真实 ACK 响应丢失后的 UI 重试、其余归档异常和最终全量门禁继续推进。

### 2026-09-07：归档保存与部分传输失败边界

新增三项 Runtime 设备故障验证：构建回调写部分内容后 IOException，最终归档不发布且 pending 清理；原子发布目标被本测试的非空目录占据，原内容保留且调用方未收到任何字节；真实 pipe 读到前缀后关闭，后续写失败明确 delivered=false，而 1 MiB 完整 Runtime 副本逐字节不变、诊断存在且 pending 不残留。

API29/36 各 18/18 通过（上述 3 项与既有 15 项真实 runner 回归），证据 `build/main-verification/proot-output-failures-summary.json`、`proot-output-failures-emulator-5596.log`、`proot-output-failures-emulator-5598.log`。测试 APK、Spotless、Detekt 通过（`proot-output-failures-build.log`）；文档和 diff 检查通过。本轮未改生产代码。helper 测试载荷为合成字节，专门验证发布/传输，不冒充 ZIP 格式验收；格式和 manifest 由已有真实归档测试证明。

清单首页的“只有查询/停止、产品保存/UI 未接线”已按当前源码与分项证据校正；PRoot 总项仍留开放，实际 ACK 响应丢失界面、完整导航和最终合并回归仍待完成。不会据本轮异常测试将整个 Goal 或所有 PRoot 验收标绿。

### 2026-09-07：PRoot 完整 ChatScreen 结果导航

恢复验证改为生产 ChatScreen：关闭会话返回列表，按本次唯一标题点击原会话，核对 sessionId 与原 call 的恢复资格，滚动到 query/view/确认按钮并实际点击，显示原结果文本。原组件容器验证仍是历史证据，本轮补上完整会话页面布局与导航。测试标题增加 UUID，避免与保留的历史归档会话重名；首轮选择器歧义与本 fixture abort 清理日志保留，没有删除其他历史会话。

API29/36 × 正常完成/输出丢失，四组均通过。正常路径请求数每台 5 次（含连接探测），输出丢失每台 4 次；Goal/Turn/Call、结算预算和原 Job 不变，查看后精确确认正常。证据：`build/main-verification/proot-result-navigation-summary.json`、`proot-normal-navigation-verified-emulator-*/result.json`、`proot-failure-navigation-emulator-*/result.json`。测试 APK、Spotless、Detekt 通过（`proot-navigation-unique-build.log`），文档与 diff 检查通过，本轮没有改生产代码。

本轮为真实生产 ChatScreen 页面，模型仍为脚本服务；不冒充外层应用导航、所有屏幕尺寸/语言/大字体和统一 UI 优化验收。PRoot 已列的结果页面导航项已获得证据，实际 ACK 响应丢失界面路径与最终合并门禁仍继续。

### 2026-09-07：PRoot 收尾后的合并宿主全量刷新

在 main 记录 1,122 项源码/配置指纹，核对全部 33 个含 test 源码模块对应的 34 个 JVM 任务（App 两 flavor 分别执行），使用 force-tests.gradle 禁用测试 up-to-date/cache 与 max-workers=1 强制重跑。逐任务核实实际执行行、当前生成 XML 和测试总数：2,637/2,637，零失败/错误/跳过。指纹在 Debug/Release 验证后均一致，没有用跨源码快照拼接通过。

根 lintDebug、lintRelease、Spotless、Detekt 通过；consumer/developer App 及 PRoot/CLI Runtime 的 Debug/Release 共八个主 APK 构建通过，另完成 App 双 flavor 测试 APK。Release 产物 unsigned，未签名/安装发布包或宣称 store 验收。证据：`build/main-verification/post-proot-full-host-result.json`（34 任务逐项计数）、`post-proot-host-command.json` / `post-proot-host.log`、`post-proot-release.log` / `post-proot-release-result.json`（八产物 SHA-256）、`post-proot-source-before.json` / `post-proot-source-after.json`。

本轮刷新宿主基线，没有新增生产改动；清单的 JVM/构建计数已从旧快照校正。设备专项、真实 ACK 响应丢失 UI、尚待决定的完成证据契约和统一界面优化仍独立推进。长稳保持后置，不以宿主全部通过宣告整体 Goal 完成。文档/ADR/diff 检查通过。

### 2026-09-08：确认交接进程终止与 PRoot 缺口收口

确认前（本地已回读，未 ACK）和确认后（真实 Runtime ACK 已返回给测试暂停回调，但尚未返回恢复协调器/UI）各在 API29/36 SIGKILL App。主机在终止前读取原 journal，分别证明无/有确认时间。四组共 8 次 SIGKILL、8 次后续完整 ChatScreen 页面恢复；本地查看后点击显式确认，结果状态变为已确认，Turn 保持 INTERRUPTED，原 Job/输入/预算不变，每组模型请求固定 4 次，无重发。

证据：`build/main-verification/proot-ack-kill-navigation-summary.json`、两组 `proot-ack-consumption-kill-emulator-*/result-boundary-record.json` 与两组 `proot-before-ack-kill-navigation-emulator-*/result-boundary-record.json` 及 phase 日志。测试 APK、Spotless、Detekt 通过（`proot-ack-kill-navigation-build.log`），本轮未改生产代码；文档/ADR/diff 检查通过。

此前“实际 ACK 响应丢失”的待办现明确区分证据：RemoteException 注入验证未知传输响应时保留本地结果；本轮真实进程终止验证确认尚未被恢复协调器/UI消费的窗口，未宣称截断 Binder 内核响应。两者覆盖所需的不确定确认行为与真实生命周期恢复。成功结果恢复记录按原四项要求给出对应表并关闭该有限缺口；HXA-102、整体设备矩阵、完成证据契约与统一 UI 优化仍继续，整个 Goal 不标记完成。


### 2026-09-08 ADR-0028 接受与验证规则基础

所有者明确回复“接受 ADR-0028，继续实现”，ADR 状态已改为 accepted。此决定仅授权 HXA-102 的完成证据契约，不代表生产完成入口或验收已完成。

新增共享的封闭验证绑定（Artifact SHA-256、UTF-8 字面包含、本地 Tool 成功、人工复核）；版本化 SHA-256 绑定条件 id、描述、方法和参数，长度前缀 UTF-8 编码避免字段串接歧义，拒绝未知版本和不可往返 Unicode。Tool 名称复用现有 ToolName 契约。新增有界 Artifact 内容检查：完整快照上限 1 MiB，先验证内容 hash，严格解码 UTF-8；超限、篡改、非法 UTF-8 和不适用方法返回明确结果，不解释脚本或文本指令。内容匹配本身不证明同 Goal 来源或远端业务副作用，也不直接创建 CriterionEvidence。

验证：JDK 17，`./gradlew :core:model:test :core:agent:test spotlessApply detekt --max-workers=1` 通过；model 138、agent 175 项，均 0 失败/错误/跳过，新增绑定 4 项与内容规则 6 项。首次格式检查发现混合条件表达式，随后复用 ToolName；首次 Detekt 发现 return 数量超限，拆分方法后复跑通过。日志保存在 `build/main-verification/criterion-binding-foundation.log`、`criterion-binding-foundation-retry.log`、`criterion-content-foundation.log`、`criterion-content-foundation-retry.log`。

下一步仍是版本化持久格式、同 Goal/run/Turn 证据溯源、用户绑定/人工复核 UI、Continue 内复验与原子完成，再做设备和真实模型验收。当前未启用生产完成入口；HXA-102 与持续 Goal 保持进行中，长稳后置及真机边界不变。上述增量之后，先前 2,637 项全仓结果仍仅对应原来源快照，需在接线稳定后刷新。


### 2026-09-08 ADR-0028 持久格式与条件状态接线

新增版本化验证记录，保存方法、绑定 hash、内容 hash、验证时间和 Goal/run/session/Turn 来源；CriteriaCodec 在既有条件 JSON 中增加可选 binding/verification，严格检查版本、字段顺序/类型和未知字段，保留旧 JSON 可读取性。旧裸引用保持历史记录，不被自动补成新验证记录。App 的 Goal 存储映射可完整往返这些字段；未改 Room 列或历史 run。

Criterion/reducer 现要求当前用户绑定匹配的宿主验证记录，并拒绝其他 Goal 的证据。条件修改会清除证据，修改后再还原不会复活旧记录。Goal 摘要按当前有效条件统计，旧裸引用和其他 Goal 的证据不计入进度。旧已完成 Goal 保留历史状态、可读取/恢复且不重开；新增完成仍只允许 RUNNING 并满足全部条件。宿主的真实来源/完整性复验及无未决副作用原子完成仍待下一段接线，这些值类型不自行授予完成权限。

验证命令：JDK 17，`./gradlew :core:model:test :core:agent:test :core:storage:testDebugUnitTest :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest spotlessApply detekt -I build/main-verification/force-tests.gradle --max-workers=1`，使用既有 Connector 验收目录和样包环境变量。5 个 JVM 任务强制运行，model 138、agent 181、storage 80、consumer 302、developer 320，总计 1,021 项，0 失败/错误/跳过；Spotless/Detekt 通过。绑定已补固定哈希向量；新增持久格式 5、条件失效/历史状态 6、App 映射 2 项测试。

最初无 Connector 环境配置的 App 任务各跳过 3 项；补配置强制运行时 Cloudflare 文档 MCP 发生一次 socket timeout，已保留失败 XML 与日志，重试一次后通过，不隐藏外部瞬时失败。证据：`build/main-verification/criterion-persistence-result.json`、`criterion-persistence-final.log`、`criterion-persistence-app-forced.log`、`criterion-persistence-app-first-failure/`、`criterion-persistence-forced-retry.log`。

当前仍未启用生产证据选择、人工复核和自动完成；下一步接入同 Goal 真实来源/完整性验证、用户绑定 UI、Continue 内复验与原子完成，再做设备和真实模型验收。HXA-102/持续 Goal 保持 active，长稳后置、真机与付费调用边界不变。


### 2026-09-08 ADR-0028 宿主 ToolResult 来源读取

新增 GoalToolEvidenceReader，读取现有持久 ToolResult 并核对注册的本地工具、COMPLETED 调用、SUCCEEDED/verified 结果、参数 hash、同 Goal/run/session/Turn 绑定、Turn 完成时间及合法 run 状态；结果内容完整性验证后生成稳定来源快照 hash。读取不执行工具、联网、重放或修改 Goal。完成证据专用 unsettled 查询覆盖全部非终态调用，包含 PENDING/AWAITING_APPROVAL/RUNNING/NEEDS_REVIEW/INTERRUPTED；原 Continue 的既有查询语义保持。

ContentStore 新增有界完整读取，读取前检查声明/实际大小，流式收集时仍限定字节数，避免先分配整个文件再校验；不接受截断前缀。4 项 JVM 边界测试覆盖 UTF-8 字节上限、虚假小元数据、大文件、同长度篡改、删除和空内容。

验证：`./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :core:storage:testDebugUnitTest spotlessApply detekt --max-workers=1` 通过；API 29/36 均安装当轮 APK，`GoalToolEvidenceReaderDeviceTest` 各 6 项通过，扩展断言逐项覆盖 5 种非终态调用。使用独立测试 Room 数据库与受控结果，属于持久来源校验，不作为真实工具执行/真实模型完成验收。日志及 APK hash 见 `build/main-verification/criterion-source-result.json`、`criterion-source-unsettled-build.log` 与 `criterion-source-unsettled-emulator-5596.log`/`5598.log`。

有界读取与初版来源检查之后已强制重跑 storage 84、consumer 302、developer 320 共 706 项 JVM，0 失败/错误/跳过（既有 Connector 环境配置），见 `criterion-source-host-regression.log`；随后扩展 unsettled SQL，并完成上述重构建、storage JVM 与双 API 设备回归。初次构建的 ToolVersion 类型和测试状态枚举错误已修复，失败日志保留。

当前来源读取器尚未接入生产完成入口；下一步是 Artifact 来源/快照、绑定编辑与人工复核、Continue 内重验、原子完成，然后补真实 Tool/模型和设备恢复矩阵。HXA-102 与整个持续 Goal 未完成。


### 2026-09-08 ADR-0028 ToolResult 快照与宿主验证入口

新增 GoalToolArtifactStore，把已验证的本地 ToolResult 正文保存为会话私有 text/plain Artifact，并按原调用来源指纹绑定 id/path；明确这是“工具结果文本快照”，不是自动宣称工具产出文件已验收。复验核对原结果、同会话注册记录、内容长度/hash/字节，严格限制实际读取量。缺失/篡改快照拒绝读取，已存在注册记录时 capture 也不重建删除文件或覆盖损坏内容；合法 crash 残留文件只有与原正文一致时可登记。

新增 GoalCriterionVerifier：对用户绑定的 SHA-256、UTF-8 字面条件或本地工具名称产生宿主记录。人工复核只接受显式选择携带的绑定 hash 和已展示来源 hash；任一变更即拒绝，自动入口不满足人工复核条件。本地工具成功规则不会附带未经验证的 Artifact 引用。当前仅提供宿主方法，尚未写入生产 Goal 完成或 UI 入口。

验证：JDK 17，`./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest spotlessApply detekt --max-workers=1` 通过。API 29/36 使用当轮 APK 运行 GoalToolEvidenceReaderDeviceTest 与 GoalCriterionVerifierDeviceTest，各 11 项、共 22 项通过；覆盖来源回归、幂等快照、删除/篡改不覆盖、三类自动规则和人工复核绑定变化。测试以独立 Room 已验证结果夹具开始，不称为真实工具/模型完成链路。构建初轮的格式错误与 nullable parent 警告已修复，日志保留。

证据：`build/main-verification/criterion-verifier-result.json`（含 APK hash）、`criterion-verifier-device-build-final.log`、`criterion-verifier-emulator-5596.log`/`5598.log`。下一步继续工具产出文件/其他已有 Artifact 的来源边界、用户绑定编辑与人工复核暂存、Continue 内重验和原子完成，以及真实 Tool/模型与生命周期设备验收。HXA-102/持续 Goal 保持进行中，未将本次快照子集作为全部 Artifact 或整体完成证据。


### 2026-09-08 ADR-0028 人工复核暂存与用户编辑边界

新增 CriterionPendingReview 独立持久字段，保存原调用、已展示来源 hash、绑定 hash、可选 Artifact 与复核时间；暂存不成为 CriterionEvidence、不计入已满足条件。绑定变更清除证据/暂存，验证证据入库时清除已消费选择。CriteriaCodec 严格检查版本、字段与手动复核绑定，Goal 存储映射完整保留该字段。

新增 GoalCriterionEditor 用户操作边界：只允许 parked 且没有 open run 时编辑；使用期望 Criterion 拒绝陈旧编辑。人工复核先调用宿主验证，再仅保存 selection，与审计同事务落库；clearReview 不继续 Goal。未增加模型 Tool 或权限入口。

验证：agent 184、storage 87 项 JVM，0 失败/错误/跳过，新增各 3 项覆盖未满足、条件变更、证据消费、编码往返及畸形版本。Developer App/test APK、Spotless/Detekt 通过（`criterion-review-editor-build.log`、`criterion-review-stage-device-build-retry.log`）。API 29/36 各 16 项、共 32 项 Room/验证设备回归通过，新增 5 项编辑测试证明：暂存不改 Goal 其他字段/run/预算、陈旧编辑拒绝、来源变更回滚、RUNNING 禁止编辑，以及耗尽预算不能被复核绕过。初轮测试格式检查失败已修复并保留日志。

证据：`build/main-verification/criterion-review-stage-result.json`（含 APK hash）、`criterion-review-stage-emulator-5596.log`/`5598.log`。当前仍未接 UI、显式 Continue 后消费或 Goal 原子完成；真实重启/kill 与全链路验收仍待后续接线，不能把 Room 夹具测试扩写为整体完成。


### 2026-09-08 ADR-0028 生产终态事务接线

GoalCompletionVerifier 已接入 AppContainer → ChatService → TurnCoordinator → GoalRunSettlement：预算/恢复结算后，仅 RUNNING、Turn 成功且没有任一非终态调用时复验。逐项重验旧记录/人工暂存，失效记录清除并审计；自动规则只从当前 Turn 已验证本地结果取得新证据。全部条件通过才请求 reducer 完成，并将 Goal、run outcome 和审计与原 Turn 终态同事务提交；重复结算保持首个结果。用户人工复核的暂存被重验后消费，历史 run outcome 保留。验证记录补充可选 sourceHash 保留已审阅来源指纹；旧记录不推断升级。

设备负向测试发现并修复 [内层证据读取使外层结算回滚](../bug-fixes/2026-09-08-goal-evidence-nested-transaction-rollback.md)：缺失内容原本错误保持 RUNNING。现在预期验证失败退出内层读取事务后再传播为无证据；外层真实写入/编程错误仍整体回滚。初次两 API 失败日志保留，未跳过负向用例。

验证：JDK 17，五任务 `:core:model:test :core:agent:test :core:storage:testDebugUnitTest :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest` 使用既有 Connector 环境配置及 `-I build/main-verification/force-tests.gradle --max-workers=1` 强制执行；138/184/88/302/320，总计 1,032，0 失败/错误/跳过。Developer App/test 构建、Spotless、Detekt 通过。API 29/36 各通过 22 项新完成/来源/复核测试和 30 项既有 Goal Continue/绑定/预算/取消/界面回归，总计 104 项。证据见 `build/main-verification/criterion-completion-result.json`，构建 `criterion-completion-transaction-fix-build.log`，JVM `criterion-completion-host-regression.log`。

当前真实 ChatService 已有结算回调，但证据编辑/查看 UI、其他工具产出文件和 Artifact 来源、真实模型与 kill/restart 完成验收仍未完成。人工暂存现于成功 Turn 终态消费；还须接 Continue 前置复验路径，避免已满足条件仍依赖无关模型调用。该项维持同一 HXA-102，不把已有组件测试扩写为整体 Goal 功能/优化完成。


### 2026-09-08 ADR-0028 显式 Continue 前置复验

GoalEvidenceContinue 已接入 ChatService 与 GoalDialog：用户 Continue 在既有预算和未决调用门禁后，先重验全部已有证据/人工暂存；满足时原子记录本次 USER_OPEN run、COMPLETED outcome 与审计，不创建模型调用、工具调用或虚构执行 Turn。证据失效清除后返回普通执行路径；预算耗尽、跨会话或未决调用拒绝，外层失败整体回滚。已完成重复调用保持幂等。直接完成后 GoalDialog 保持打开并定位到目标标题，显示持久完成状态。

验证：API 29/36 的 GoalEvidenceContinueDeviceTest、GoalEvidenceContinueUiTest、GoalCompletionDeviceTest、GoalDialogDeviceTest、GoalEditorDeviceTest 各 16 项，共 32 项通过，无跳过。真实 AppContainer 的 provider-free UI 测试使用已验证本地结果夹具，证明 Continue 不发送请求且保留可见完成状态；不作为真实模型验收。初次显示等待失败及后续测试主线程 Room 查询错误日志保留，修复后所有原断言通过。

JDK 17 强制执行 `:core:model:test :core:agent:test :core:storage:testDebugUnitTest :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest -I build/main-verification/force-tests.gradle --max-workers=1`（既有 Connector 环境），138/184/88/302/320，共 1,032 项，0 失败/错误/跳过。Developer App/test APK、Spotless、Detekt 通过。日志见 `build/main-verification/criterion-preflight-host-regression.log`、`criterion-preflight-visible-final-build.log`、`criterion-preflight-final-emulator-5596.log`/`5598.log`；汇总与 APK hash 为 `criterion-preflight-result.json`。

下一项仍是同一 HXA-102 的实际条件绑定/证据查看与人工复核 UI，之后补其他产出 Artifact 来源、真实模型与 kill/restart 完成验收。整个 HXA-102 与持续 Goal 未完成，长稳和真机范围不变。


### 2026-09-08 ADR-0028 条件绑定编辑界面

Goal 管理页已接入条件列表与验证规则编辑：显示已绑定方式/参数及未验证、已暂存复核、已保存验证记录状态；支持取消绑定及四类封闭规则。SHA/字面文本/工具名参数走既有值类型验证，只有显式保存才写入；保存清除该条件旧证据，不开启 run。当前 Artifact 规则界面明确命名为“结果文本快照”，工具成功规则明确其有限命题。中英文文案齐备。ChatService 经 GoalCriterionAccess 检查会话并调用已有 parked/stale-check 编辑边界，UI 不读 DAO。

验证：Developer App/test APK、Spotless/Detekt 通过；API 29/36 各 13 项相关回归，共 26 项通过，无跳过。新增表单用例验证无效 SHA、字面文本必填和显式保存；真实 AppContainer 从 Goal 管理入口保存 time.now 规则，确认数据库持久绑定且 Goal 状态/runCount 不变。既有编辑边界、Continue 可见性、Goal 管理/创建用例一并通过。强制 consumer/developer JVM 302/320，共 622 项，0 失败/错误/跳过。

证据：`build/main-verification/criterion-binding-ui-result.json`（APK hash）、`criterion-binding-ui-test-build-pass.log`、`criterion-binding-ui-host.log`、`criterion-binding-ui-emulator-5596.log`/`5598.log`。初次复杂度、格式和新增测试长度门禁失败保留；通过提取辅助方法修复，没有删除断言或跳过测试。

证据正文查看、人工选择/复核按钮与来源失效原因显示仍待接入；本次仅完成规则编辑界面，不宣称完整证据 UI、真实模型或进程恢复验收。HXA-102 与持续 Goal 保持进行中。


### 2026-09-08 ADR-0028 ToolResult 查看与人工复核界面

条件列表新增证据查看入口。GoalCriterionAccess 从同会话、同 Goal 的持久 run/Turn 列出已完成调用，用户打开后由既有宿主 reader 重验本地工具来源、完整性和未决调用；失败显示固定拒绝提示，不显示原始异常。正文使用分块 LazyColumn 展示完整有界内容，未截断后假称全文已读。详情展示条件、工具、run/Turn/call、结果摘要和正文；人工规则才提供明确的暂存复核动作，非 parked 状态不可确认。

确认沿既有 GoalCriterionEditor 保存已展示来源/绑定指纹；内容变化拒绝，保存不启动 run。显式 Continue 再次核实证据、预算及未决调用，满足后才原子完成。界面没有直接修改 DAO、verified 标志或生成完成事实的入口。

验证：Developer App/test APK、Spotless、Detekt 通过；API 29/36 各 19 项，共 38 项，0 失败/跳过。扩展实际 AppContainer 界面用例覆盖“读取真实持久结果夹具正文 → 明确复核 → 仍为 PAUSED → Continue → COMPLETED 可见”，并保留不发送模型请求、不关闭完成界面、没有额外 Turn 的断言；同时通过规则编辑/持久保存、来源篡改、陈旧绑定、预算耗尽、回滚与幂等回归。强制 consumer/developer JVM 302/320，共 622 项，0 失败/错误/跳过。

证据：`build/main-verification/criterion-review-ui-result.json`（APK hash）、`criterion-review-ui-build.log`、`criterion-review-ui-test-build-retry.log`、`criterion-review-ui-host.log`、`criterion-review-ui-emulator-5596.log`/`5598.log`。新增测试最初触发长度门禁，通过提取展示辅助方法修复，原失败保留。

本次是 ToolResult 证据路径的界面接线；其他产出文件/Artifact 的实际查看及来源验证、具体失效原因展示、用户流程取消/进程终止矩阵、真实模型任务完成仍待验收，不能把持久夹具扩写为真实模型完成。HXA-102/持续 Goal 保持进行中。


### 2026-09-08 ADR-0028 write 产物内容快照

源码核实 ArtifactEntity 仅具有会话归属，不能把任意同会话文件作为同 Goal 证据。新增 WrittenArtifactContent，仅解析宿主已验证的本地 write v1 输入/结果：路径规范化后相等、严格 UTF-8、字节上限、结果 sizeBytes/hash 与写入内容必须一致。GoalToolArtifactStore 用独立 id/path 保存写入内容快照，与既有结果 JSON 文本快照分开；旧引用行为保留，读取重新核对原调用指纹和完整字节，缺失已登记快照不重建。

生产自动 Artifact 验证现可匹配这两种有来源的快照，再通过既有 verifier/原子完成结算。规则界面名称更新为产物快照；这证明该次成功写入的内容，不证明源文件当前位置仍存在或保持不变，也没有开放任意 Workspace 文件读权限。

验证：Developer App/test APK、Spotless/Detekt 通过。强制 consumer/developer JVM 306/324，共 630 项，0 失败/错误/跳过，新增 4 项各 variant 覆盖字节还原、路径/size/hash 不符、错误工具/version 和畸形输入。API 29/36 各 13 项，共 26 项通过；新增用例真实执行 WriteTool 写文件，核对原文件和持久快照字节，走完成结算，并确认删除快照后 capture 拒绝重建。该设备用例在隔离 fixture 中调用真实 executor，再登记已验证结果；不是完整审批管线或真实模型验收。

证据：`build/main-verification/criterion-written-result.json`、`criterion-written-test-build-final.log`、`criterion-written-host.log`、`criterion-written-emulator-5596.log`/`5598.log`。初次返回数量静态门禁、测试格式和 ArtifactRef 类型错误均已修复，失败日志保留。

仍需写入快照的实际查看/人工选择界面、其他产物来源（如 edit/PRoot 文件）、具体失效原因，以及真实模型与 UI kill/restart 验收；不将 write 子集扩写为所有 Artifact 来源完成。HXA-102/持续 Goal 保持进行中。


### 2026-09-08 ADR-0028 写入快照查看与人工选择

证据候选将 write v1 的工具结果与写入内容快照分别展示；打开快照通过宿主验证、保存/重读有界 Artifact，并明确说明不证明原文件当前状态。预览模型携带实际正文与 ArtifactRef，人工选择保存该引用，Continue 按原来源和快照再次验证，最终完成记录保留所选引用。普通 ToolResult 查看仍不附加 Artifact 引用。

验证：Developer App/test APK、Spotless/Detekt 通过；API 29/36 各 18 项，共 36 项通过。实际 UI 测试分别选择工具结果/写入快照，滚动到正文后确认，仍保持 PAUSED，Continue 后 COMPLETED 可见且 Artifact 引用有无与选择一致。写入 UI 使用持久结果夹具；真实 WriteTool 执行及删除快照拒绝重建的既有设备用例同时通过，不扩写为真实模型验收。

强制 App JVM 首轮 consumer 306 项中 ConnectorExternalAcceptanceTest 的公共服务查询发生 SocketTimeoutException，developer 未执行；首轮 log/XML 已保留。按原环境和原两任务重试一次，consumer/developer 306/324，共 630 项，0 失败/错误/跳过。结果表示重试通过，不能声称首轮全绿或外部服务长稳。

证据：`build/main-verification/criterion-written-ui-result.json`、`criterion-written-ui-test-build.log`、`criterion-written-ui-host.log`/`criterion-written-ui-host-retry.log`、`criterion-written-ui-host-first-failure/`、`criterion-written-ui-emulator-5596.log`/`5598.log`。初次格式和测试函数长度门禁已修复，日志保留。

剩余为其他产物来源、具体失效反馈、证据流程取消/进程终止、真实模型完成与更广合并回归；HXA-102/持续 Goal 保持进行中。


### 2026-09-08 ADR-0028 证据失效原因与诊断恢复

新增稳定宿主失败类型，来源未完成、未决调用、跨 Goal/session、内容变化/删除、超限、绑定变化、外部来源和读取异常分别映射中英文提示；仍未知的旧校验使用固定通用提示，不解析原始异常文字。完成重验失败时将具体已知原因与清除证据同事务写入失效审计。条件列表通过诊断投影恢复原因，重新绑定/暂存/清除复核后清除旧提示；已满足或有新暂存时不展示历史失效。审计文本仅用于展示，不成为完成证据或授权。

验证：Developer App/test APK、Spotless/Detekt 通过。强制 consumer/developer JVM 308/326，共 634 项，0 失败/错误/跳过。API 29/36 各 33 项，共 66 项通过：取消来源、未决调用与内容删除的类型区分，正文显示后删除导致具体 UI 提示且不关闭，Continue 失效保持 PAUSED/no-new-run 并保留 CONTENT_CHANGED 审计；既有来源/快照/人工复核/完成/写入/预算回归一并通过。

初次各 API 32/33，新增取消夹具遗漏 CANCELLING 中间状态；修复为合法 RECEIVING_MODEL → CANCELLING → CANCELLED 后原断言通过，失败日志保留。未通过删除/跳过测试获得绿色。

证据：`build/main-verification/criterion-failure-result.json`、`criterion-failure-test-build-retry.log`、`criterion-failure-fixture-build.log`、`criterion-failure-host.log`、`criterion-failure-final-emulator-5596.log`/`5598.log`。下一步继续真实模型产物完成与证据流程取消/进程终止验收，并核实其他产物来源覆盖；HXA-102/持续 Goal 保持进行中。


### 2026-09-08 ADR-0028 真实模型产物完成矩阵

新增 GoalRealCompletionUiTest，显式启用 `helix.goalCompletion=true` 并提供 model/port/protocol；通过真实 UI 创建 Goal、保存 SHA-256 规则、Continue、批准本次 write，再核对真实文件与持久快照字节、Goal/run COMPLETED、模型用量及完成界面。测试只设置 Provider/session，不伪造 ToolResult/verified/完成状态。每轮目标/文件具有独立标识，截图和结果在 fixture 清理前保存。

本地 SSH 转发 30008 服务当前模型 Qwen3.8-27B：API 29/36 × OPENAI_CHAT_COMPLETIONS、OPENAI_RESPONSES、ANTHROPIC_MESSAGES 初轮 6/6 真实流程通过；每轮两次模型调用、一次 write，UI 显示已验证完成及 1/1。证据为 `build/main-verification/goal-real-completion/matrix.json` 与六个独立目录截图；Developer App/test 构建、Spotless、Detekt 通过。该矩阵验证三个本地兼容协议，不推导 OpenAI/Anthropic 官方付费服务验收。

汇总发现 Anthropic 两轮仅记录 769/834 token，已定位并修复 [缓存输入漏记](../bug-fixes/2026-09-08-anthropic-cached-input-accounting.md)。初轮 Anthropic 完成流程成立，计账数据有已知缺陷，不能列为正确预算验收。修复后 API 29/36 Anthropic 各 1/1 重跑通过，计数恢复 19,067/19,107；provider:anthropic/core:agent/App 两 flavor 强制 JVM 共 896 项，0 失败/错误/跳过。修复后 APK hash/数据见 `goal-real-completion/cache-fixed.json`，原始矩阵保留。

当前已形成真实模型 → 真实批准写入 → 产物验证 → Goal/run/界面完成的正向证据。剩余证据录入/复核/提交阶段取消和 kill/restart、其他产物来源覆盖核实、M0～M11 最新全量回归与统一 UI 优化仍未完成；持续 Goal/HXA-102 保持进行中。

### 2026-09-08 ADR-0028 与 Anthropic 修复后全量宿主刷新

重新枚举当前 33 个含 JVM 测试源码的模块，34 个测试任务全部纳入强制执行（禁用测试 up-to-date/cache）。2,690 项测试、0 failures、0 errors、0 skipped；根 `lintDebug`、Spotless、Detekt、consumer/developer Debug 主 APK 与两个测试 APK 构建通过。Connector 条件测试沿用现存验收样本及交接 ZIP，本轮无失败后重试。前后 1,146 个源码/配置文件 SHA-256 完全一致。

完整命令、各任务结果、指纹及原日志：`build/main-verification/post-evidence-host-command.json`、`post-evidence-host-result.json`、`post-evidence-host-source.json`、`post-evidence-host.log`。本轮替代 ADR-0028/Anthropic 变更前的 JVM 和 Debug 静态/构建快照；不替代 Release、模拟器完整回归、真机或长稳验收。取消交互与真实强杀边界分别见 HXA-102 本日记录。

### 2026-09-08 最新 Release 与可复跑强杀入口

`./gradlew lintRelease :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleDebug :runtime:proot-app:assembleRelease :runtime:cli-app:assembleDebug :runtime:cli-app:assembleRelease --max-workers=1` 通过。合并上轮 Debug 构建，八个 App/Runtime 主 APK 的大小及 SHA-256 保存于 `build/main-verification/post-evidence-release-result.json`，日志 `post-evidence-release.log`；1,146 项源码/配置与最新全量 JVM 快照一致。Release 均为 unsigned，不代表发布签名或设备功能验收。

新增可复跑入口 `python3 scripts/accept-goal-evidence-process-death.py <emulator-serial> --output <new-output-directory>`，需要预先安装匹配的 developer 主包/测试包。脚本核实准备信号 PID 属于当前 App，实际 SIGKILL 后核实该 PID 消失，再要求新进程恢复断言通过。输出目录必须新建，保留每个阶段日志；不安装/清理 Runtime，不读取凭据。API 29/36 各三边界复验通过，结果位于 `build/main-verification/goal-evidence-kill-script-api29/result.json` 与 `goal-evidence-kill-script-api36/result.json`。

### 2026-09-08 最新 Goal 双 API 双变体矩阵与删除夹具修复

当前 24 类、每组合 85 项 Goal 测试运行于 API 29/36 × consumer/developer，安装时核实主 APK 与测试 APK 的设备 SHA-256。首轮 339/340：API 36 developer 的 `GoalDeletionDeviceTest.productionDeletionRemovesQueuedAndPostedReminders` 等待通知超时；同一测试隔离重跑也失败。原始结果保留在 `build/main-verification/post-evidence-goal-api<api>-<flavor>/`，隔离日志 `goal-deletion-refresh-isolated.log` 与 `goal-deletion-isolated-logcat.log`。

根因是测试直接调度提醒，却保留 DRAFT、无持久检查点的 Goal；启动时 `GoalReminderReconciler` 会正确取消该作业。测试现改为在对账锁内持久化 PAUSED 和检查点，再通过生产 reconcile 调度。保持通知必须实际发布、删除后通知与排队工作必须清除的断言，不扩大超时、不修改生产规则。

修复后仅重跑受影响删除类，双 API 双变体各 `OK (3 tests)`，共 12/12。两种测试 APK 构建、Spotless 格式化及 Detekt 通过。结果 `post-evidence-goal-fixed-api<api>-<flavor>/result.json`、汇总 `post-evidence-goal-summary.json`；构建日志 `goal-deletion-durable-fixture-build.log`。首轮不记为全绿，测试夹具修复后的 12 项结果单列；其他 App/Runtime/平台模块矩阵仍需继续。

### 2026-09-08 App 操作与存储/CLI Client 当前矩阵

使用最新 App 主包与测试包，API 29/36 × consumer/developer 执行 20 类、每组合 59 项，共 236/236、零失败/跳过。涵盖文件预览/导入导出、审批卡、设置、Provider 配置、语音入口、诊断、A2A fixture 与 Connector 等既有测试。主包/测试包设备端 SHA-256 与构建文件一致；这组不包含真实付费订阅调用或长稳。清单及每组原始结果见 `build/main-verification/post-evidence-additional-api<api>-<flavor>/result.json` / `instrumentation.log`，汇总 `post-evidence-additional-summary.json`。

重新构建并核对安装 hash 后，`:core:storage` 设备套件 47/47 × 双 API、`:runtime:cli-client` 16/16 × 双 API，共 126/126、零失败/跳过。结果 `post-evidence-storage-cli-result.json`，原始日志 `post-evidence-storage-api29.log` / `api36.log`、`post-evidence-cli-client-api29.log` / `api36.log`；构建日志 `post-evidence-module-test-build.log`。CLI Client 套件不等同于订阅 Provider 真账号或完整 Runtime 后端验收。

`check-i18n.sh`、`check-secrets.sh`、`check-cli-runtime-boundary.sh`、`verify-variant-boundaries.sh`、`check-lockfiles.sh` 本轮全部 exit 0；国际化为 354 个生产源码文件、913 个 base/en/zh-rCN 一致资源键。各门禁结果及原日志位于 `post-evidence-gates.json`、`post-evidence-<script-name>.log`。这些静态门禁不替代设备权限、账号和分发审核结果。

### 2026-09-08 浏览器、文件、QuickJS、Android 系统桥接刷新

最新测试 APK 在 API 29/36 执行浏览器常规 27×2、文件 38×2、QuickJS 75×2、Android 系统桥接 30×2。API 29 文件首次 35/38，三个成功导入用例被 REFUSED；其余首轮已完成的 API 29 浏览器 27 项保留通过。

核实发现 `SafAdaptersInstrumentedTest.scopeRoot` 使用固定 `files/saf-it-*` 路径且不清理，设备上确有旧 `input/honest.txt`。生产导入拒绝覆盖已有文件是正确行为。测试改用 JUnit `TemporaryFolder` 为每例建立隔离目录并自动清理，不修改生产导入策略。修复后文件双 API 各 38 项通过，并在不清应用数据、不重装的条件下再各执行 38 项通过，证明可重复运行。首次失败日志 `build/main-verification/post-evidence-platform-files-api29.log` 保留；重复运行日志 `saf-fixture-repeat-api29.log` / `api36.log`。

最终四模块双 API 340 项均获得通过证据，文件重复验收额外 76 项；首轮不是全绿。各 APK 安装 hash、精确命令及结果见 `post-evidence-platform-result.json`、`post-evidence-platform-fixed-result.json` 和 `post-evidence-platform-summary.json`。浏览器 `continuousResourceSoak` 与 `rawPlatformWebViewLifecycleControl` 按所有者长稳后置决定不启动，不算通过；普通两项资源生命周期测试保留执行。测试构建与 Detekt 通过，日志 `post-evidence-platform-build.log`、`saf-fixture-isolation-build.log`。

### 2026-09-08 M11 CLI 结果读取/确认及运行恢复刷新

API 29 首轮 9 项中的 5 项运行中取消/死亡测试通过，4 项结果删除测试失败：旧测试假定 `submitAndAwait` 读取后会自动确认删除，但当前生产协议要求调用方持久化或明确丢弃后单独 `acknowledgeResult`，读取本身保留证据。

`CliRuntimeHandshakeE2eDeviceTest.verifyPayload` 改为断言读取后未 ACK，杀 Runtime 后 `fetchResult` 仍返回相同事件/request hash/output hash，再明确丢弃测试夹具并 ACK，随后再次杀 Runtime，确认对账后 payload 已删除。保留原成功状态与内容断言，无生产代码变化。

修正后原 9 项选择在 API 29/36 各 `OK (9 tests)`，18/18、零跳过；四平台均使用 `helix-fixture`/`helix-fixture-wait`，不调用真实付费账号，不清除 Runtime 凭据。首次失败保留 `build/main-verification/post-evidence-cli-recovery-api29.log` / `post-evidence-cli-recovery-result.json`；修正结果 `post-evidence-cli-recovery-fixed-result.json` 与双 API 原日志 `post-evidence-cli-recovery-fixed-api29.log` / `api36.log`。两端安装最新 CLI Runtime 与 developer 测试 APK；构建、格式化、Detekt 通过，日志 `cli-ack-fixture-build.log`。

这证明所选跨 UID 结果/取消/死亡协议，不替代 M11 真账号、全部 Provider UI 或主 App 执行中强杀验收。

### 2026-09-08 会话/附件/审批/调度/恢复 App 核心矩阵

按当前源码刷新 16 类清单，附件端到端由旧清单 24 项更新为当前 28 项，总计 91 项。API 29 因 `@SdkSuppress(minSdkVersion = 35)` 不运行前台服务 `onTimeout` 回调测试，每变体 90 项；API 36 每变体 91 项。双 API 双发行包合计 362/362、零测试失败。API 29 回调不适用单列，不记为该能力通过。首组 instrumentation 本身 `OK (90 tests)`，宿主初始按 91 项汇总的计数错误已按源码适用范围校正，未改原输出或重跑。

覆盖附件 Goal/普通发送与重试、会话生命周期、TurnCoordinator、审批唤醒、工具调度、QuickJS App 接线、Skill、生产迁移、进程恢复、模型恢复、Provider 模型发现、前台服务与资源闸门、导入导出 facade。使用本地夹具及现有设备测试，不扩写成真实账号或全部平台权限验收。每组核实设备安装 APK hash 与构建文件一致。

精确清单 `build/main-verification/post-evidence-core-classes.json`，原始命令/状态码/安装 hash 与日志 `post-evidence-core-api<api>-<flavor>/result.json` / `instrumentation.log`，汇总 `post-evidence-core-summary.json`。

### 2026-09-08 edit 真实模型审批与 Goal 完成矩阵

`GoalRealCompletionUiTest` 增加显式 `helix.goalCompletion.tool=edit` 模式；每例在独立文件中预置输入，模型获知原文本和前置 hash，通过实际 Goal 创建、SHA-256 绑定、Continue、真实编辑调用审批及生产执行，最后检查工作文件/私有快照全文和 Goal/run COMPLETED，并验证 UI 的“已验证完成”可见。预置文件是输入夹具，不作为模型生成证据。未注入 ToolResult/verified/Goal 完成行。

API 29/36 × OPENAI_CHAT_COMPLETIONS、OPENAI_RESPONSES、ANTHROPIC_MESSAGES 六例均 `OK (1 test)`，每例 2 次模型调用、1 次 edit；本地 SGLang `Qwen3.8-27B` 经 30008 转发。token 分别为 API29 19663/19269/19829、API36 19896/19537/19344。结果、截图、原始日志和 APK hash 位于 `build/main-verification/goal-real-edit/`（`result.json`、`artifacts.json`、各组合目录）；宿主日志 `goal-real-edit-host.log`。API29 Chat 完成截图经人工查看，状态可见，长目标文本布局问题留统一 UI 阶段。

设备使用真实本地模型/审批/编辑全链路，不代表官方付费服务或真机验收。PRoot 归档来源接线、其余生命周期和当前全量刷新仍继续。构建日志 `goal-real-edit-build.log`、`goal-real-edit-receipt-build.log`。

### 2026-09-08 PRoot Goal 归档全文读取基础

新增 developer 内部 `ProotEvidenceContent`，复用 `ZipJobExtractor` 完整归档验证后，按 manifest 中的精确路径读取完整文件，限制 1 MiB 并重验字节 SHA-256。独立于截断的展示预览，不联系 Runtime、不 ACK、不执行或导入产物。读取检查取消信号，退出时清理临时提取目录。

`JAVA_HOME=<JDK17> ./gradlew spotlessApply :app:testDeveloperDebugUnitTest --tests com.helix.app.proot.ProotEvidenceContentTest detekt --max-workers=1` 通过；5 项 JVM、零失败/跳过，覆盖超过展示长度的全文、空文件/精确上限、超限/缺失路径、manifest 哈希不符及读取中断清理后重读。首次失败是 `JobZipWriter` 在夹具构造阶段正确拒绝哈希不符，改用原始 ZIP 构造非法输入后复验通过；原日志 `build/main-verification/proot-evidence-content-test.log` 保留，最终日志 `proot-evidence-content-fixed.log`。

本组仅为归档内容读取组件证据；尚未接入 Goal 来源绑定、候选选择、持久快照及完成规则，不标记 PRoot Goal 产物验收完成。

### 2026-09-08 PRoot 归档接入 Goal 候选与完成验证

新增 variant-local `ProotGoalArtifacts` 和 `GoalArchiveArtifactStore`，从原调用绑定的持久归档枚举产物、读取完整文件并登记 Goal 私有快照；consumer 不提供 PRoot 来源。Goal 候选/全文预览、人工复核所用引用及自动 SHA-256/UTF-8 规则已接线。引用为原 ToolResult 指纹与归档内路径的固定长度哈希，符合 ArtifactRef 128 字符限制；查询归档使用模型 ToolCall ID，证据归属仍使用数据库行 ID。复验同时检查原归档、会话来源和私有快照，缺失快照不重建，无 Runtime 查询/启动/ACK/Job 重放。二进制可参与 SHA-256 验证；当前全文预览仅接受严格 UTF-8 无 NUL 文本，不把替换解码结果作为人工复核正文。

构建：`JAVA_HOME=<JDK17> ./gradlew spotlessApply :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :app:compileConsumerDebugKotlin detekt --max-workers=1` 通过。扩展测试后 developer 测试包构建、5 项归档读取 JVM 与 Detekt 再通过，日志 `build/main-verification/proot-goal-device-build.log`、`proot-goal-expanded-build.log`。

新 `GoalArchiveArtifactDeviceTest` 首轮 API29/36 各 1/1；扩展候选/全文预览/原 ZIP 篡改断言后，整个 `com.helix.app.goal` 包两 API 各 `OK (33 tests)`，合计 66/66，零失败/跳过。覆盖长中文文件名、不同数据库行/调用 ID、SHA-256 完成 Goal、跨调用引用拒绝、原 ZIP 篡改拒绝与恢复、删除私有快照拒绝重建。命令、APK hash 与原始日志在 `build/main-verification/proot-goal-package-device/`，首轮记录在 `proot-goal-archive-device/`。

这是实际 Room/归档/Goal 结算组件的验收；ToolCall/成功结果由测试夹具持久化，不冒充真实模型/审批/Runtime 全链路。PRoot 真实产物端到端、归档专属复核 UI 及读取生命周期仍继续验证，HXA-102 和整体 Goal 保持进行中。

现有 `GoalEvidenceFailureUiTest` 与 `GoalEvidenceContinueUiTest` 两类在 API29/36 各 `OK (5 tests)`，合计 10/10，确认候选键/参数扩展未破坏既有失败反馈、预览取消和显式复核 Continue；归档专属 UI 不计入本组。命令与原始日志在 `build/main-verification/proot-goal-evidence-ui/`。文档 272/130、ADR 28 与 `git diff --check` 通过。

### 2026-09-08 PRoot 归档人工复核 UI

新增 developer `ProotGoalReviewUiTest`，使用会话私有持久归档和生产 `GoalEvidenceDialog`/`GoalDialog`：点击归档文件候选、实际展示中文全文、显式确认复核后仍为 PAUSED 且 run 数未增加，再点击 Continue 后 COMPLETED，证据方式为 MANUAL_REVIEW、引用为 goal-archive，整个流程没有新增模型 Turn。清理仅删除本测试的 Goal/会话及其未引用产物，不操作 Runtime 凭据。

API29/36 各 `OK (1 test)`，2/2、零失败/跳过；命令、测试 APK hash 与日志在 `build/main-verification/proot-review-ui/`。构建、Spotless、Detekt 最终通过（`proot-review-ui-build-verified.log`）；前期测试方法长度和拆分时的 nullable 编译问题均已修正，原失败日志保留。归档与成功 ToolResult 为测试夹具，本组不替代真实模型生成产物。

### 2026-09-08 PRoot 真实模型归档完成六组合

`GoalRealCompletionUiTest` 增加显式 `helix.goalCompletion.tool=code.linux.run`，仅 developer 可用，验证前切换 Advanced 并执行现有 Runtime 验证入口，结束恢复原 profile。经真实 Goal 创建/哈希绑定/Continue，模型提出 PRoot script，用户审批后在独立 Runtime 生成 `result.txt`；不设置 `output` 导入路径，最后必须以 `goal-archive-` 私有快照的精确内容满足 SHA-256 并完成 Goal。未注入 ToolResult、verified 或完成行。

API29/36 × OPENAI_CHAT_COMPLETIONS、OPENAI_RESPONSES、ANTHROPIC_MESSAGES 共 6/6，均为 `OK (1 test)`；每例模型调用 2、工具调用 1。当前本地 SGLang `/v1/models` 实查为 `Qwen3.8-27B`、30008 转发；token 为 API29 18849/18696/18852，API36 19015/18677/18757。原始命令脚本 `build/main-verification/run-goal-real-proot.py`、宿主日志 `goal-real-proot-host.log`、结果/截图/APK hash 在 `goal-real-proot/`。API29 Chat 截图人工查看确认“已验证完成”与 1/1 条件可见，长目标占据弹窗的布局问题仍归统一 UI 阶段。

测试 APK 构建、consumer androidTest 编译及 Detekt 通过（`proot-real-ui-build.log`）。本组为真实本地模型、审批、Dispatcher、PRoot Runtime 和归档完成链路；不是官方付费 Provider 或真机验收。结合归档人工复核 UI 双 API 2/2，PRoot 两条完成路径已取得相应证据；证据读取中断/恢复及最终全量合并刷新仍继续。

### 2026-09-08 证据阻塞读取取消接线

发现候选/预览仅使用 `withContext(IO)`，关闭界面无法中断同步读取。改用 `readGoalEvidence`/`runInterruptible(IO)`，证据检查及快照登记前、文件循环和 PRoot 来源边界增加中断检查。真实归档读取的受控暂停测试验证取消后工作线程退出、没有返回内容、提取目录清理及后续重读；旧实现对照停留至 10 秒超时失败，修复后的严格 3 秒退出断言强制运行通过。读取相关 JVM 6/6，构建/Spotless/Detekt 通过；最终命令 `JAVA_HOME=<JDK17> ./gradlew spotlessApply :app:testDeveloperDebugUnitTest --tests com.helix.app.proot.ProotEvidenceCancellationTest --tests com.helix.app.proot.ProotEvidenceContentTest -I build/main-verification/force-tests.gradle detekt --max-workers=1`。

API29/36 当前安装包各 Goal 33/33、证据 UI 6/6，共 78/78；命令、APK hash 与原日志 `build/main-verification/evidence-read-cancel-device/`。严格 JVM 结果 `evidence-read-cancel-strict.log`，旧实现日志/XML 保留。详见 [缺陷记录](../bug-fixes/2026-09-08-goal-evidence-blocking-read-cancellation.md)。

取消不保证回滚已经完成的私有快照发布，且不授予复核或完成权限。受控读取暂停是实际 IO 线程取消证据，不替代读取阶段真实 SIGKILL/恢复；该生命周期边界及最终合并全量回归继续。

### 2026-09-08 证据快照读取阶段真实 SIGKILL

`GoalToolArtifactStore` 增加内部默认空的读取检查点，测试 APK 在已从快照文件读入一块、内容校验尚未结束且流未关闭的位置暂停；生产默认路径不暂停。`GoalEvidenceProcessKillDeviceTest` 新增 `reading` 边界，清除待复核选择后读取持久快照，宿主按检查点报告的当前 App PID 发送真实 SIGKILL 并确认该 PID 消失，然后启动新的 instrumentation 恢复。

API29/36 各 1 次真实强杀与 1 次恢复通过：Goal 仍为 PAUSED，无 pendingReview/evidence、run 数与预算不变；调用记录仍为原 1 Turn/1 ToolCall、零 ModelCall；显式完成检查拒绝且快照全文仍为原内容。所用 ToolResult 为夹具，强杀发生在实际快照读取检查点，不冒充自然磁盘挂起、PRoot 归档解压中断或真实模型调用。

命令：`python3 scripts/accept-goal-evidence-process-death.py <emulator> --boundary reading --output <new-evidence-directory>`，两台对应原始 prepare/kill/recover 日志在 `build/main-verification/evidence-read-kill-emulator-5596/`、`evidence-read-kill-emulator-5598/`；汇总与 APK hash 为 `evidence-read-kill-result.json`。Developer App/test APK、Spotless、Detekt 通过（`evidence-read-kill-build.log`）。

这与已执行的复核暂存、录入事务、完成事务提交前/后强杀以及 PRoot 实际读取线程取消形成不同阶段的证据；HXA-102 其他后端边界审计、最新全量合并回归和统一 UI 阶段继续。

### 2026-09-08 edit/PRoot/读取取消后最新全仓宿主刷新

34 个实际 Test 任务强制执行，2,710/2,710，零失败/错误/跳过；根 `lintDebug`、Spotless、Detekt 与 consumer/developer Debug 主包和测试包构建通过。1,159 项源码/配置指纹在运行前后完全一致。沿用已有 Connector 样本/交接 ZIP 环境，不跳过外部条件测试。精确 Gradle 参数 `build/main-verification/post-archive-host-command.json`，原始日志 `post-archive-host.log`，模块计数 `post-archive-host-result.json`，源码指纹 `post-archive-host-source.json`。

本次覆盖最新 edit/PRoot 产物接线与读取取消修改，取代早于这些修改的 2,690 项宿主快照。结合绑定/来源/完整性、人工复核/原子完成、write/edit/PRoot 真实模型和各证据阶段取消/强杀结果，Goal 完成证据实现子项收口；不等于整个 HXA-102、Release/全设备矩阵或整体持续 Goal 完成。其余后端边界对照、最新 Release 及设备验证和统一交互/UI 继续。

### 2026-09-08 最新 Release 与后端边界核对

根 `lintRelease`、consumer/developer Release 与 PRoot/CLI Runtime Debug/Release 构建通过；日志 `build/main-verification/post-archive-release.log`，八主 APK 指纹 `post-archive-release-result.json`。结合已完成的最新 Debug/JVM 门禁，当前宿主构建快照已刷新。Release unsigned，不作发布签名声明。

核对历史强杀记录后新增 [后端阶段对应表](hxa102-boundary-audit.md)，把既有证据与尚未证明的实际窗口分开；下一项明确为浏览器/UI 动作未结算边界与 MCP 显式后续处理，随后进行最终设备/M11/资源矩阵。整体 HXA-102 和持续 Goal 继续。

### 2026-09-08 浏览器/UI 动作未结算窗口强杀

BrowserGoalProcessKillDeviceTest、UiGoalProcessKillDeviceTest 与对应宿主脚本新增显式 `--unsettled`。测试 APK 的 `ActionResultHold` 包装原生产 executor：原动作实际完成后暂停返回结果，Dispatcher/审批/Goal/真实浏览器与 Accessibility 动作均保留。宿主同时确认实际导航/点击发生、模型未收到结果回填，然后 SIGKILL 当前 App PID。生产代码未改。

API29/36 × browser/ui 四组全部通过，4 次真实强杀、8 次恢复；每组动作计数保持 1、backfillRequests=0，Turn/ToolCall/run 为 INTERRUPTED，无 ToolResult，审计 uncertainToolCall 精确指向原调用，预算不返还、预留结清、无模型或动作重放。模型请求总数 browser=4、ui=5（含连接测试）；与原结果回填中强杀分别记录。

命令：`python3 scripts/run-browser-goal-process-kill.py --serial <emulator> --adb <adb> --unsettled --output <new-directory>`，UI 对应 `run-ui-goal-process-kill.py`。汇总 `build/main-verification/action-unsettled-result.json`，四组 `browser-unsettled-emulator-5596/5598`、`ui-unsettled-emulator-5596/5598` 目录包含安装 hash、prepare/恢复日志，各宿主日志同名前缀 `-host.log`。构建/Spotless/Detekt 最终通过（`action-unsettled-build-verified.log`），前期测试方法长度/函数数量静态失败已拆分修正、日志保留；两宿主脚本 Python 语法检查通过。

这是实际工具执行后的受控结果返回检查点，不冒充任意自然设备故障或真实网站业务。当前关闭浏览器/UI 动作未结算验证缺口；MCP 不明确结果后的显式 Continue 与最终设备/M11/资源矩阵仍继续。

### 2026-09-08 MCP 不明确结果后显式 Continue

扩展 McpProcessKillDeviceTest 的实际强杀恢复：启动恢复后打开原会话、清除旧提示，调用生产 ChatService 的 Continue 意图，等待本地化 `goal_continue_unavailable`；核对 Goal/run/Turn、原 ToolCall 与已消费审批完全不变、没有发送状态。两次独立恢复均执行相同检查，宿主核对远端 tools/call 仍为 1、模型请求仍为 4，无启动或显式 Continue 重发。

API29/36 两组全部通过，2 次真实 SIGKILL、4 次恢复和显式 Continue 检查。实际 MCP SDK/HTTP 与生产 Goal/审批/持久化链使用脚本模型和合成 MCP 服务；这是服务层用户意图，不冒充实际点击 Continue 按钮或远端业务效果已核清。当前没有可验证的远端结果，Continue 正确拒绝；本次没有增加人工强制结算或重放入口。

命令：`python3 scripts/run-mcp-process-kill.py --serial <emulator> --adb <adb> --output <new-directory>`；汇总 `build/main-verification/mcp-continue-result.json`，两组 `mcp-continue-emulator-5596/5598` 原始 prepare/recover 日志及安装 APK hash。测试构建/Spotless/Detekt 通过（`mcp-continue-build.log`），宿主 Python 语法检查通过；生产代码未改。MCP 后续 Continue 不重发这一检查点关闭，最终设备/M11/资源矩阵与统一 UI 继续。

### 2026-09-08 最新 App 核心/Goal/操作矩阵

按当前源码重新核对测试数量（包括同文件不同类的 RunControl 测试）：核心 91、Goal 通用 86、操作 59；developer 加归档组件/归档复核 UI 各 1。API29 的 API35+ 前台服务超时回调不适用，核心每变体为 90，不作能力通过。每组安装 APK 后比对设备 SHA-256 与构建文件，再执行精确类列表。

API29 consumer 90+86+59=235，developer 90+88+59=237；API36 consumer 91+86+59=236，developer 91+88+59=238。十二组 946/946，均首轮实际 instrumentation 通过，零失败/意外跳过（状态码仅 0/1）。覆盖当前会话/附件/审批/调度/迁移、Goal 生产接线/完成证据/提醒/UI、Provider/设置/文件/操作等；真实模型与实际进程强杀仍由各专项结果证明，不重复计入本组合。

清单 `build/main-verification/final-core-classes.json`、`final-goal-classes.json`、`final-additional-classes.json`；精确命令/安装指纹/原始日志在十二个 `final-app-<suite>-api<api>-<flavor>/`。完整结果 `final-app-matrix-result.json`、摘要 `final-app-matrix-summary.json`、宿主日志 `final-app-matrix-host.log`。矩阵运行期间未修改生产代码。

源码指纹差异另存 `post-archive-source-changes.json`：前次宿主至当前宿主的生产变化集中于 App，文件模块变化为先前已复验的 SAF 测试夹具；不据此把未执行的设备场景记为通过。M11/Runtime 和非长稳资源剩余专项核对继续，统一 UI 阶段尚未开始。


### 2026-09-08 M11 当前专项与失败 Job 对账测试修正

最新主包/测试包与 CLI Runtime 安装后逐包校验 SHA-256。首轮 API29 为 34/35：`fixedModelJobIsDurableAndNeverBlindlyResubmitted` 把显式 legacy reconcile 之后的记录仍与确认时间为空的原记录全等比较。当前 Binder 对无 payload 的 terminal Job 会持久化确认时间，符合既有协议；旧固定 Job ID 还会使后续运行复用已确认记录，掩盖首次对账分支。本次仅修测试：每轮新 Job ID，核对首次对账仅确认时间变化，Runtime 重启后记录保持，重复对账和重复提交不产生新执行。未修改生产协议或清理账号凭据。

修正后 API29/36 各 35/35：四平台普通 Provider probe/文本流/Chat 落盘及结果确认、显式账号 Activity、Binder 失败、运行取消/死亡、payload 保留/确认和 MCP Android 专项。新 Job 首次对账用例原地再次执行，两 API 各 1/1。四平台是 debug fixture，不代表真实付费网络或登录成功。

双 API、双发行包的入口 UI 另通过 8/8；`bash scripts/check-cli-runtime-boundary.sh` 通过，包含 Consumer DEX 无订阅实现检查。此入口组覆盖 Advanced/egress 的发行包差异，不能单独称为四订阅平台全部 UI 流程。M11 切换、失败/限额在完整 Chat 链中的覆盖仍需核对补齐，非长稳资源总审计和统一 UI 尚未关闭。

构建命令 `./gradlew spotlessApply detekt :app:assembleDeveloperDebugAndroidTest --max-workers=1`（JDK17）通过。证据均位于 `build/main-verification/`：首次失败 `final-m11-api29/instrumentation.log`；修正构建 `final-m11-test-fix-build.log`；双 API 精确类列表/命令/安装指纹/日志 `final-m11-fixed-api29/`、`final-m11-fixed-api36/` 与 `final-m11-fixed-result.json`；重复执行 `final-cli-reconcile-repeat-result.json`；入口 `final-entry-result.json`；边界 `final-cli-boundary.log`。此前 946 项 App 矩阵保持其原测试快照，本次测试修改由上述专项验证；没有重新宣称 946 项在新测试 APK 全量执行。


### 2026-09-08 四平台完整 Chat 边界与私有产物删除

新增 SubscriptionChatBoundaryDeviceTest，使用真实 ChatService、Room、跨 APK Runtime 与 debug fixture：四平台各验证有效最小预算拒绝（失败 ModelCall 审计保留、零 cli.job_prepared）、用户 Stop 使同一 Runtime Job CANCELLED、Runtime 死亡使 Chat FAILED 且重开不重发，以及相邻平台的会话切换。切换断言包含会话 Provider、实际 Job platform 绑定、当前 badge、原消息与单一 Turn；并非仅切换静态标签。

API29/36 每台 4 个测试方法，分别遍历四平台，共 8/8 方法、32 个平台场景。最新证据 `build/main-verification/subscription-chat-routing-result.json` 与两个 `subscription-chat-routing-api*/`（精确命令、安装 hash、原始日志）；上一轮无额外 routing 断言的 8/8 独立保留，不累计成更多唯一用例。未访问真实付费账号，也不宣称真实 HTTP 429 在完整 Chat 设备链上已验收。

测试发现实际会话隐私删除拒绝内部订阅结果路径，修复固定的订阅结果/Goal 证据目录删除，并增加删除后逐文件不存在断言。Workspace JVM 90/90，三项新回归有修复前失败证据；见 [缺陷记录](../bug-fixes/2026-09-08-private-artifact-session-deletion.md)。设备首轮两项失败中另有零预算不符合产品输入范围；第二轮修正预算审计预期及等待 UI 发送终态后，最终通过，原始失败均保留。

`./gradlew spotlessApply detekt :core:workspace:test :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebug :app:lintDeveloperDebug :app:lintConsumerDebug --max-workers=1` 最终通过，日志 `subscription-private-delete-verified-build.log`。首轮 Consumer Lint 揭示六条仅 Developer PRoot 文案误放公共资源，三语言原文迁移后修正；i18n 907 keys 通过。补强 routing 断言后的测试构建/Detekt 见 `subscription-chat-routing-build.log`。本轮存在 core/workspace 生产变化和资源迁移，先前 2710 JVM/946 App/Release 是此前快照；影响范围以本轮 90 JVM/8 设备和构建证据为准，最终合并门禁刷新仍待执行。


### 2026-09-08 私有产物删除修复后完整门禁刷新

完整命令沿用34个真实 JVM 任务的强制执行清单，保留 Connector 已授权样本条件。`post-private-delete-host-command.json` / `post-private-delete-host.log`：2,713/2,713、零失败/错误/跳过，1,162 项源码/配置指纹前后相同；根 Debug Lint、Spotless、Detekt、双变体主/测试 APK 构建通过。结果 `post-private-delete-host-result.json`。

根 `lintRelease`、App Consumer/Developer Release、CLI/PRoot Runtime Debug/Release 构建通过，完整日志 `post-private-delete-release.log`，八个主 APK 指纹 `post-private-delete-release-result.json`；Release 仍未签名。本轮新增三个 Workspace 测试使完整 JVM 计数由2710增加至2713，并非原测试跳过或重复计数。

源码差异 `post-private-delete-source-changes.json`：自上个完整宿主快照，生产变化仅 Workspace 私有产物隐私删除与六条 PRoot 文案三语言迁移，其他差异均为测试。针对这一范围，双 API 双发行包重新安装校验 hash，执行 GoalDeletionDeviceTest 3、GoalWrittenArtifactDeviceTest 2；Developer 再加归档组件1、复核 UI1、PRoot 结果 UI3，共30/30、零失败/跳过。记录 `post-private-delete-device-result.json` 及四个 `post-private-delete-device-api*-*/`。此前完整 App 946与后端强杀专项保留其准确快照，不冒充同一新 APK 全量重跑。

现存资源覆盖已确认：前后台资源/FGS18、真实旋转4与流连接断开4、PRoot宿主生命周期与显式forced idle、普通WebView/QuickJS资源回归均有独立证据。尚缺真实模拟器内存压力路径；旧API35短时资源结果存在，但应补当前宿主验收所选API35边界。物理设备、自然Doze/OEM热限制和24小时长稳仍按原边界后置。后续按资源覆盖核对补齐，不因宿主门禁通过而宣称全部功能测试已完成。
