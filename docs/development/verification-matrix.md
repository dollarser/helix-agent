# Helix 验收命令矩阵

基线日期：2026-09-03。命令来自 Gradle 9.5.0 / AGP 9.3.2 工程的真实
`projects` 与 `tasks --all` 输出。每个 HXA 开始前必须确认对应命令仍存在；若模块、
variant 或 source set 改名，先更新本矩阵，再实现功能。

## 1. 通用约定

- 所有 Gradle 命令从仓库根目录执行，并且只使用 `./gradlew`。
- JVM 行无需设备；Android 行需要 `adb devices` 中存在已授权设备或模拟器。
- consumer 仪器测试验证共享功能与当前编译边界；修改共享逻辑、consumer route/manifest 或变体边界时必须运行对应 consumer task，但 consumer 不预设为最终商店包。
- developer 当前承载最完整能力，是开发阶段主要验收对象；涉及 Standard/Advanced、All-files、Accessibility、Root 或 Runtime client 时必须运行对应 developer task。HXA-120～123 再把真实渠道要求映射为 artifact，不能从 flavor 名推导产品能力。
- 真机/外部服务验收必须记录设备、API、ABI、服务版本和实际结果，不能用构建成功替代。
- Release、APK 内容和许可证总门禁始终追加第 4 节命令。

## 2. M0 任务命令（已完成）

| 任务 | 可复制命令 | 环境与预期证据 |
| --- | --- | --- |
| HXA-001 | `./gradlew projects`<br>`./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug`<br>`./gradlew :runtime:proot-app:assembleDebug :runtime:cli-app:assembleDebug`<br>`./gradlew test`<br>`./scripts/verify-variant-boundaries.sh` | 无设备；四个 debug APK、28 项目、四个 applicationId、consumer 无 developer marker、依赖图裁剪、根 `LICENSE` |
| HXA-002 | `./gradlew spotlessCheck detekt test lintConsumerDebug lintDeveloperDebug`<br>`./scripts/check-lockfiles.sh`<br>`./scripts/check-secrets.sh`<br>`./scripts/verify-adr.sh`<br>`./scripts/check-docs.sh`<br>`git diff --check` | 无设备；格式、静态检查、Lint、依赖锁、secret、ADR、文档契约、wrapper 与 verification metadata 门禁 |
| HXA-003 | `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest`<br>`./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug`<br>`./gradlew :app:connectedConsumerDebugAndroidTest` | 最后一行需 API 36 模拟器；七个 route、手工 `AppContainer`、consumer APK 可启动 |

## 3. 后续 HXA 命令

表中 Gradle task 均已存在。任务实现时必须把测试放进对应 task 的标准 source set，
不能另建无人执行的测试目录。

### M1：领域、Plan/Goal 与持久化

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-010 | `./gradlew :core:model:test` | 无 |
| HXA-011 | `./gradlew :core:agent:test` | 无 |
| HXA-012 | `./gradlew :core:agent:test` | 无 |
| HXA-013 | `./gradlew :core:agent:test :app:testConsumerDebugUnitTest` | `./gradlew :app:connectedConsumerDebugAndroidTest`；提醒/恢复 |
| HXA-014 | `./gradlew :core:storage:testDebugUnitTest` | `./gradlew :core:storage:connectedDebugAndroidTest`；Room migration fixture |
| HXA-015 | `./gradlew :core:agent:test :core:storage:testDebugUnitTest` | `./gradlew :app:connectedConsumerDebugAndroidTest`；进程恢复 fixture |
| HXA-016 | `./gradlew :core:agent:test` | 无 |

### M2：Provider

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-020 | `./gradlew :core:model:test :provider:api:test :core:storage:testDebugUnitTest` | `./gradlew :core:storage:connectedDebugAndroidTest :app:connectedConsumerDebugAndroidTest`；Android Keystore（真机/模拟器执行，非 fake） |
| HXA-021 | `./gradlew :core:model:test :provider:api:test` | 无（纯 JVM 契约；契约落位 core:model 共享内核，core:agent 仅依赖它） |
| HXA-022 | `./gradlew :provider:openai-responses:test` | 本地流 fixture |
| HXA-023 | `./gradlew :provider:openai-chat:test` | 本地流 fixture |
| HXA-024 | `./gradlew :provider:anthropic:test` | 本地流 fixture |
| HXA-025 | `./gradlew :provider:api:test :provider:catalog:test` | `./gradlew :app:connectedConsumerDebugAndroidTest`；手工连接另记 |
| HXA-026 | `./gradlew :provider:catalog:test` | 无 |
| HXA-027 | `./gradlew :provider:openai-chat:test` | `./gradlew :app:connectedDeveloperDebugAndroidTest`；真机 Ollama/SGLang |
| HXA-028 | `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:lintConsumerDebug :app:lintDeveloperDebug` | `./gradlew :app:connectedConsumerDebugAndroidTest :app:connectedDeveloperDebugAndroidTest`；consumer 仅 Standard、developer 默认 Standard、切换 Advanced 零权限/网络副作用 |

### M3：Tool、Policy、Approval 与 Capability

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-030 | `./gradlew :tools:framework:test` | 无 |
| HXA-031 | `./gradlew :tools:framework:test` | 无 |
| HXA-032 | `./gradlew :core:policy:test :tools:framework:test` | `./gradlew :app:connectedConsumerDebugAndroidTest` |
| HXA-033 | `./gradlew :core:policy:test` | 无 |
| HXA-034 | `./gradlew :core:policy:test :core:storage:testDebugUnitTest` | `./gradlew :core:storage:connectedDebugAndroidTest`；DENIED/过期不可生成或消费 Approval Proof，并发仅一个批准消费成功 |
| HXA-035 | `./gradlew :tools:framework:test` | 无 |
| HXA-036 | `./gradlew :app:testConsumerDebugUnitTest :app:lintConsumerDebug` | `./gradlew :app:connectedConsumerDebugAndroidTest` |
| HXA-037 | `./gradlew :tools:framework:test :core:agent:test :core:storage:testDebugUnitTest` | `./gradlew :app:connectedConsumerDebugAndroidTest`；并发读/排他屏障/固定回填顺序/receipt/取消/恢复/资源降级 |
| HXA-038 | `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest spotlessCheck detekt :app:lintConsumerDebug` | 无；纯 JVM characterization，不改变设备行为 |
| HXA-039 | `./gradlew :core:agent:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest` | `./gradlew :app:connectedConsumerDebugAndroidTest`；批量工具乱序结算/逐调用审批/取消/进程死亡/恢复等价性 |

### M4：Workspace 与文件

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-040 | `./gradlew :core:workspace:test` | 无 |
| HXA-041 | `./gradlew :core:workspace:test` | 磁盘满/中断 fixture |
| HXA-042 | `./gradlew :tools:files:test :tools:framework:test :core:workspace:test` | 无；首个业务工具注册前证明安全 descriptor 变化强制新 version/新 binding，或按 accepted ADR 验证完整 contract hash |
| HXA-043 | `./gradlew :tools:files:test :core:workspace:test` | 无 |
| HXA-044 | `./gradlew :feature:files:testDebugUnitTest` | `./gradlew :feature:files:connectedDebugAndroidTest`；恶意 ContentProvider |
| HXA-045 | `./gradlew :feature:files-allfiles:testDebugUnitTest` | `./gradlew :app:connectedDeveloperDebugAndroidTest`；专用设备 |
| HXA-046 | `./gradlew :feature:files:testDebugUnitTest :app:testConsumerDebugUnitTest` | `./gradlew :app:connectedConsumerDebugAndroidTest` |
| HXA-047 | `./gradlew :tools:files:test` | Zip Slip/膨胀比 fixture |
| HXA-048 | `./gradlew :core:workspace:test :tools:files:test :app:testConsumerDebugUnitTest spotlessCheck detekt` | `./gradlew :app:connectedConsumerDebugAndroidTest`；ChatService 单会话并发/取消、大目录 list/search 边界 |
| HXA-049 | `./gradlew :core:model:test :core:agent:test :core:storage:testDebugUnitTest :feature:files:testDebugUnitTest :app:testConsumerDebugUnitTest` | `./gradlew :core:storage:connectedDebugAndroidTest :feature:files:connectedDebugAndroidTest :app:connectedConsumerDebugAndroidTest`；Room migration、恶意 ContentProvider、picker/恢复/hash/egress binding |

### M5：QuickJS

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-050 | `./gradlew :runtime:quickjs:testDebugUnitTest :runtime:quickjs:assembleDebug` | `./gradlew :runtime:quickjs:connectedDebugAndroidTest`；API 29/36、arm64/x86_64 |
| HXA-051 | `./gradlew :runtime:quickjs:testDebugUnitTest` | `./gradlew :runtime:quickjs:connectedDebugAndroidTest` |
| HXA-052 | `./gradlew :runtime:quickjs:testDebugUnitTest` | `./gradlew :runtime:quickjs:connectedDebugAndroidTest` |
| HXA-053 | `./gradlew :runtime:quickjs:testDebugUnitTest :tools:framework:test` | `./gradlew :app:connectedConsumerDebugAndroidTest` |
| HXA-054 | `./gradlew :runtime:quickjs:testDebugUnitTest` | `./gradlew :runtime:quickjs:connectedDebugAndroidTest`；真机崩溃/内存/取消 |

### M5A：多模态附件

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-055 | `./gradlew :core:model:test :provider:openai-responses:test :provider:openai-chat:test :provider:anthropic:test :app:testConsumerDebugUnitTest` | `./gradlew :app:connectedConsumerDebugAndroidTest`；API 29/36 与低内存真机图片归一化、能力门控、取消/恢复 |
| HXA-056 | `./gradlew :core:agent:test :core:storage:testDebugUnitTest :provider:openai-responses:test :provider:openai-chat:test :provider:anthropic:test :feature:files:testDebugUnitTest :app:testConsumerDebugUnitTest spotlessCheck detekt` | `./gradlew :app:connectedConsumerDebugAndroidTest`；文本/图片 picker+share E2E、进程回收、脱敏、UTF-16/文档/音频/视频统一拒绝；另记至少一个真实 vision endpoint smoke |

### M5B：文件工作台剩余闭环

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-057 | `./gradlew :core:workspace:test :feature:files:testDebugUnitTest :tools:files:test :app:testConsumerDebugUnitTest` | `./gradlew :feature:files:connectedDebugAndroidTest :app:connectedConsumerDebugAndroidTest`；恶意 ContentProvider、persisted grant 重启/撤销/只读/跨 scope |
| HXA-058 | `./gradlew :core:workspace:test :feature:files:testDebugUnitTest :app:testConsumerDebugUnitTest` | `./gradlew :feature:files:connectedDebugAndroidTest :app:connectedConsumerDebugAndroidTest`；导入导出冲突、部分流、磁盘满、取消、进程回收与结果校验 |
| HXA-059 | `./gradlew :provider:api:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest` | `./gradlew :app:connectedConsumerDebugAndroidTest :app:connectedDeveloperDebugAndroidTest`；模型列表带出/点选预填/手输、`Unsupported`/`Failed` 两态、超大列表有界、本地 SGLang 真环境 smoke |

### M6：浏览器、Android 工具与国际化

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-060 | `./gradlew :feature:browser:testDebugUnitTest` | `./gradlew :feature:browser:connectedDebugAndroidTest` |
| HXA-061 | `./gradlew :feature:browser:testDebugUnitTest` | `./gradlew :feature:browser:connectedDebugAndroidTest`；恶意页面 |
| HXA-062 | `./gradlew :tools:browser:testDebugUnitTest :feature:browser:testDebugUnitTest` | `./gradlew :feature:browser:connectedDebugAndroidTest` |
| HXA-063 | `./gradlew :feature:browser:testDebugUnitTest` | `./gradlew :feature:browser:connectedDebugAndroidTest` |
| HXA-064 | `./gradlew :tools:android:testDebugUnitTest` | `./gradlew :tools:android:connectedDebugAndroidTest` |
| HXA-065 | `./gradlew :tools:android:testDebugUnitTest` | `./gradlew :tools:android:connectedDebugAndroidTest` |
| HXA-066 | `./gradlew :tools:android:testDebugUnitTest :core:policy:test` | `./gradlew :tools:android:connectedDebugAndroidTest`；DNS rebinding/redirect/peer/scope 与 Standard/Advanced 网络边界 |
| HXA-067 | `./gradlew :app:testConsumerDebugUnitTest :feature:browser:testDebugUnitTest` | `./gradlew :app:connectedConsumerDebugAndroidTest`；语音识别 unavailable/denied/cancel/error、草稿不自动发送 |
| HXA-068 | `./gradlew :app:testDeveloperDebugUnitTest :core:policy:test :core:storage:testDebugUnitTest` | `./gradlew :app:connectedDeveloperDebugAndroidTest`；规则创建/撤销/到期/重启/时钟回拨/切回 Standard，consumer 无入口 |
| HXA-069 | `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest lintConsumerDebug lintDeveloperDebug` + `scripts/check-i18n.sh`（生产源码 CJK 字符串字面量硬编码扫描 + base/`values-en`/`values-zh-rCN` 翻译键一致性门禁；注释与测试排除） | `./gradlew :app:connectedConsumerDebugAndroidTest :app:connectedDeveloperDebugAndroidTest`；API 29/36 跟随系统/简体中文/English、Activity/进程重建、API 33+ App languages 同步、通知与关键界面；验证 Provider/Tool/审计稳定字段不受 locale 影响 |

### M7：MCP、A2A 与 Skills

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-070 | `./gradlew :extensions:mcp:test`<br>`./scripts/check-mcp-android-spike.sh`<br>`./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease --no-configuration-cache` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :app:connectedDeveloperDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.helix.app.mcp.McpAndroidSpikeDeviceTest --no-configuration-cache`；API 29/36 本地 fixture，initialize/ping、协商版本 header、缺失 wire `protocolVersion` fail-closed、取消后会话复用、关闭后全新 facade 重连、SSE 断线 + `Last-Event-ID` 重连、1 MiB 响应、HTTP 401、TLS handshake 中断、bearer + 精确 loopback/DNS pinning、非 SSE JSON 及 SSE 单事件 16 MiB wire ceiling，以及 `MainActivity` onStop/onStart 前后台切换期间 session 可用性；release 命令当前产出 unsigned artifact，签名/SBOM/notice 按 M12 发布矩阵执行 |
| HXA-071 | `./gradlew :extensions:mcp:test` | 恶意 schema/result fixture |
| HXA-072 | `./gradlew :extensions:mcp:test` | `./gradlew :app:connectedConsumerDebugAndroidTest` |
| HXA-073 | `./gradlew :extensions:mcp:test :runtime:proot-ipc:testDebugUnitTest :runtime:proot-client:testDebugUnitTest :runtime:proot-app:testDebugUnitTest :app:testDeveloperDebugUnitTest`<br>`ALPINE_MIRROR=<mirror> bash scripts/build-proot-assets.sh` | API 29/36：companion `ProotJobRunnerDeviceTest,ProotRuntimeBindingDeviceTest` 各 18/18；`:app:connectedDeveloperDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.helix.app.proot.ProotJobE2eDeviceTest --no-configuration-cache` 各 8/8、0 skip；锁定 argv/指纹、严格 stdout JSON-RPC、独立 bounded stderr、环境筛选、进程组取消与 Job 对账 |
| HXA-074 | `./gradlew :extensions:skills:test` | 无 |
| HXA-075 | `./gradlew :extensions:skills:test :tools:framework:test` | 无 |
| HXA-076 | `./gradlew :app:testConsumerDebugUnitTest :extensions:mcp:test :extensions:skills:test` | `./gradlew :app:connectedConsumerDebugAndroidTest` |
| HXA-077 | `./gradlew :spikes:a2a-sdk:testDebugUnitTest :spikes:a2a-minimal:testDebugUnitTest` + `./scripts/check-a2a-sdk-android-spike.sh` + `./scripts/check-a2a-minimal-android-spike.sh` + `./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :spikes:a2a-minimal:connectedDebugAndroidTest`；两端各 3/3：JSON-RPC/HTTP+JSON/SSE/取消/同 Task 重连/1 MiB/Bearer/HTTP 401/TLS failure/cleartext 与解析边界；物理真机、签名产物及完整 SBOM/notice 归 M12 |
| HXA-078 | `./gradlew :extensions:a2a:test :core:storage:testDebugUnitTest :app:testConsumerDebugUnitTest` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :app:connectedConsumerDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.helix.app.a2a.A2aDiscoveryDeviceTest`；两端各 2/2：真实 public/extended Card、Secret alias、版本/接口/Skill snapshot、401/非 JSON/超大 Card、hash 变化后 Room + Registry 同步撤销 |
| HXA-079 | `./gradlew :extensions:a2a:test :tools:framework:test :core:storage:testDebugUnitTest :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest` | `A2aTaskRunnerDeviceTest` 在专用 API 29/36 arm64-v8a 模拟器各 2/2；本地 fixture 覆盖 stream/cancel/restart/GetTask/Artifact 幂等复用与篡改拒绝/不明确送达不重发/远端反向调用拒绝 |

### M8：PRoot Runtime

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-080 | `./gradlew :runtime:proot-app:testDebugUnitTest :runtime:proot-app:assembleDebug` | manifest/lock schema fixture；无真机要求 |
| HXA-081 | `./gradlew :runtime:proot-app:testDebugUnitTest :runtime:proot-app:assembleDebug` | 真机验证固定资产 hash/ABI/license/ELF alignment 与三类体积 |
| HXA-082 | `./gradlew :runtime:proot-app:testDebugUnitTest` | `./gradlew :runtime:proot-app:connectedDebugAndroidTest`；安装/smoke/原子激活/回滚 |
| HXA-083 | `./gradlew :runtime:proot-client:testDebugUnitTest :runtime:proot-app:testDebugUnitTest` | `./gradlew :runtime:proot-app:connectedDebugAndroidTest`；签名权限/跨 UID/冷绑定/空闲回收/Binder death/用户触发修复入口 |
| HXA-084 | `./gradlew :runtime:proot-app:testDebugUnitTest` | `./gradlew :runtime:proot-app:connectedDebugAndroidTest`；runner 超时/取消/洪泛/job journal/断连对账/后台生命周期 |
| HXA-085 | `./gradlew :tools:files:test :runtime:proot-client:testDebugUnitTest` | `./gradlew :app:connectedDeveloperDebugAndroidTest`；bash Tool/审批/Runtime 状态/禁止回退 |
| HXA-086 | `./gradlew :runtime:proot-app:testDebugUnitTest :runtime:proot-client:testDebugUnitTest` | 4 KiB/16 KiB 真机 Python/Node/Git/ripgrep；kill/Doze/强制停止/通知/可选 wake lock |
| HXA-087 | `./gradlew :runtime:proot-app:testDebugUnitTest :runtime:proot-app:assembleDebug` | `./gradlew :runtime:proot-app:connectedDebugAndroidTest`；同签名更新/回滚/卸载/法律页 |
| HXA-088 | `./gradlew :core:workspace:test :runtime:proot-app:testDebugUnitTest :runtime:proot-client:testDebugUnitTest` | `./gradlew :runtime:proot-app:connectedDebugAndroidTest`；三种仓库权威方案 Spike、snapshot/kill/并发/恶意 Git fixture，产出并决定 ADR-0008；不测试 remote Git |

### M9：Accessibility 与 Root

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-090 | `./gradlew :tools:automation:testDebugUnitTest` | `./gradlew :tools:automation:connectedDebugAndroidTest`；专用自动化设备 |
| HXA-091 | `./gradlew :tools:automation:testDebugUnitTest` | `./gradlew :tools:automation:connectedDebugAndroidTest`；敏感界面拒绝 |
| HXA-092 | `./gradlew :tools:automation:testDebugUnitTest` | `./gradlew :tools:automation:connectedDebugAndroidTest`；包/窗口切换/停止 |
| HXA-093 | `./gradlew :tools:automation:testDebugUnitTest` | `./gradlew :tools:automation:connectedDebugAndroidTest`；检查点/快速批准/敏感界面/恢复 |
| HXA-094 | `./gradlew :tools:root:testDebugUnitTest` | `./gradlew :tools:root:connectedDebugAndroidTest`；libsu/JitPack 依赖证据、Root grant/loss/crash 与 Profile 切换不触发 `su` |
| HXA-095 | `./gradlew :tools:root:testDebugUnitTest` | `./gradlew :tools:root:connectedDebugAndroidTest`；scope/失权/崩溃 |
| HXA-096 | `./gradlew :app:testDeveloperDebugUnitTest` | `./gradlew :app:connectedDeveloperDebugAndroidTest`；确认无普通 `root.exec` |
| HXA-097 | `./gradlew :app:testDeveloperDebugUnitTest :tools:automation:testDebugUnitTest :tools:root:testDebugUnitTest` | `./gradlew :app:connectedDeveloperDebugAndroidTest` |

### M10：单机硬化

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-099 | `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :core:agent:test :tools:framework:test` | `./gradlew :app:connectedDeveloperDebugAndroidTest`；Mode/预算 UI、低内存/后台/热信号只降并发、重启与边界值 |
| HXA-100 | `./gradlew test` | 固定场景集；记录模型/工具版本和证据 |
| HXA-101 | `./gradlew test` | 攻击语料集，不调用真实付费模型 |
| HXA-102 | `./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease` | 真机低内存/后台/Doze/进程回收；持久化 pause reason、有界 usage checkpoint、反复 crash/墙钟回拨不增加预算、不重放副作用 |
| HXA-103 | `./gradlew lintConsumerRelease lintDeveloperRelease test` | API 29/36 模拟器与 API 34+/36 真机 |
| HXA-104 | `./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleRelease` | `./scripts/verify-variant-boundaries.sh`；APK 权限/内容/ABI/体积 |
| HXA-105 | `./gradlew :core:agent:test :tools:framework:test :core:storage:testDebugUnitTest` | `./gradlew :app:connectedDeveloperDebugAndroidTest`；只读 child depth/cap/父预算/无审批凭证/恢复/温升与 JSON DAG Spike，产出并决定 ADR-0009 |

### M11：官方 CLI 隔离实验

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-110 | `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug`<br>`./scripts/check-cli-runtime-lock.sh`<br>`$ANDROID_HOME/build-tools/36.0.0/aapt2 dump permissions runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :runtime:cli-app:connectedDebugAndroidTest --no-configuration-cache`；API 29/36 arm64-v8a 固定 artifact 来源/hash/license/terms/版本、独立 UID、仅 INTERNET、signature-protected 冷绑定 Service manifest；HXA-110 只锁 metadata，不打包或运行 CLI executable |
| HXA-111 | `./gradlew :runtime:cli-client:testDebugUnitTest :runtime:cli-app:testDebugUnitTest`<br>`./scripts/verify-codex-android-spike.sh` | `ANDROID_SERIAL=<api29-or-36> ./scripts/verify-codex-android-spike.sh`；先判定官方 Android 支持门禁；门禁失败时停止打包并明确列出未执行的登录/退出/跨 UID/Activity 冷绑定证据 |
| HXA-112 | `./gradlew :runtime:cli-app:testDebugUnitTest`<br>`./scripts/check-cli-runtime-lock.sh`<br>`./scripts/verify-claude-android-spike.sh` | `ANDROID_SERIAL=<api29-or-36> ./scripts/verify-claude-android-spike.sh`；先判定官方 Android 支持与 loader 门禁；失败时停止打包并明确列出未执行的登录/取消/输出限制/恶意工作区/工具拦截/进程死亡对账证据 |
| HXA-113 | `./gradlew :runtime:cli-client:testDebugUnitTest :app:testDeveloperDebugUnitTest :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-lock.sh` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :runtime:cli-app:connectedDebugAndroidTest --no-configuration-cache`；API 29/36 验证 Runtime 明确报告 unsupported/未注册且 APK 仍无 executable；因 HXA-111/112 平台门禁失败，不存在可执行 Job，`jobId` 查询/不重放与内置工具代理明确为未执行证据，Act/Goal Provider 注册必须 fail closed |

### M11A：第三方订阅协议适配器研究

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-114 | `./scripts/audit-dsh-subscriptions-spike.sh <installed-plugin-dir>`<br>`./scripts/check-lockfiles.sh`<br>`./scripts/check-secrets.sh`<br>`./scripts/check-docs.sh`<br>`./scripts/verify-adr.sh` | 只读源码/metadata 与官方文档核验；禁止读取真实 auth store、登录或模型请求。记录插件版本/hash、访问日期、token owner、官方 CLI 身份、Android/job 协议缺口与 ADR 状态 |
| HXA-115 | `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-lock.sh` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :runtime:cli-app:connectedDebugAndroidTest --no-configuration-cache`；API 29/36 验证 Runtime UID 私有 Keystore vault round-trip/覆盖/logout/篡改 fail closed，Binder status 只有登录布尔状态且不含 token |
| HXA-116 | `./scripts/audit-dsh-subscriptions-spike.sh <installed-plugin-dir>`<br>`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache` | 本地 `0.7.0` 与 npm tarball/integrity 无差异；核验四 provider 与官方文档；API 29/36 arm64-v8a 验证四 provider vault 隔离和 redacted status，且 `agentBackendState=NOT_REGISTERED` |
| HXA-117 | `./scripts/verify-copilot-sdk-android-spike.sh`<br>`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-lock.sh` | `ANDROID_SERIAL=<api29-or-36> ./scripts/verify-copilot-sdk-android-spike.sh` 与 `ANDROID_SERIAL=<api29-or-36> ./gradlew :runtime:cli-app:connectedDebugAndroidTest --no-configuration-cache`；API 29/36 arm64-v8a 必须证明官方 glibc/musl runtime 的直接 Android loader 结果。门禁失败即停止打包，OAuth/模型/工具/jobId 项明确记为未执行，不得误报通过 |
| HXA-118 | `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :runtime:cli-app:connectedDebugAndroidTest --no-configuration-cache`；API 29/36 验证 PKCE/state、loopback callback、取消/超时/失败、token 轮换与 logout，status 仍仅 redacted。真实账号登录单列人工侧载证据；未登录不得阻止代码级验收，不得执行模型请求 |
| HXA-119 | `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :runtime:cli-app:connectedDebugAndroidTest --no-configuration-cache`；API 29/36 验证 device code、interval/slow_down、取消/超时/拒绝、交换失败不落盘、vault/logout 与 redacted status。固定身份只允许 ADR-0026 的非官方个人侧载实验；不得执行模型请求 |
| HXA-137 | `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :runtime:cli-app:connectedDebugAndroidTest --no-configuration-cache`；API 29/36 验证随机 loopback、PKCE/state、取消/超时/失败、refresh/logout、Free/未知资格不落盘与 redacted status。真实 Free 账号验证授权页升级门禁或授权后资格拒绝；不得执行模型请求 |
| HXA-138 | `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :runtime:cli-app:connectedDebugAndroidTest --no-configuration-cache`；API 29/36 验证官方 Device Code endpoint、URL/code、interval/slow_down、取消/过期/拒绝、refresh/logout、Free/X Basic/未知 tier 不落盘与 redacted status；不得调用模型/proxy |
| HXA-139 | `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug :runtime:cli-app:lintDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :runtime:cli-app:connectedDebugAndroidTest --no-configuration-cache`；API 29/36 验证 Codex 官方匿名 Device Code endpoint、PKCE、poll 网络恢复、取消/过期/失败不落盘与前台剪贴板；真人 Codex 账号只验证 Runtime 私有 vault，不调用模型。Grok 验证 code/URL 分别复制及稳定拒绝原因 |
| HXA-140 | `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug :runtime:cli-app:lintDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | `ANDROID_SERIAL=<api29-or-36> ./gradlew :runtime:cli-app:connectedDebugAndroidTest --no-configuration-cache`；API 29/36 验证固定无工具请求及客户端边界。另在有真实 Codex 订阅 vault 的独占 API 36 arm64-v8a 模拟器，从 Runtime 可见 UI 主动执行一次固定 `HELIX_OK` smoke；记录实际模型与结果，不读取 token。Provider/Tool/Job 仍不得注册 |
| HXA-141 | `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug :runtime:cli-app:lintDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | JVM 覆盖成功、失败、journal 边界、取消迟到结果、同 jobId 重复/请求 hash 冲突与进程恢复 `INTERRUPTED` 不重放；API 29/36 继续执行 CLI Runtime 全量设备套件。真实 Codex 账号从可见 UI 执行一次，确认私有 journal 只有模型 ID/输出 hash，无 token/account id/正文。跨 APK Provider/Job 仍不得注册 |
| HXA-142 | `./gradlew :runtime:cli-client:test :app:testDeveloperDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | 证明供应商分发授权是独立 fail-closed 注册门禁；即使 Android/工具控制/jobId 三项均为真，授权缺失仍不得注册。确认 consumer 不依赖 `cli-client`、Provider catalog 无 CLI/订阅 adapter，CLI APK 仍无任意 prompt 或跨 APK模型 Job transaction |
| HXA-143 | `./gradlew :runtime:cli-client:test :app:testDeveloperDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | 同一完整技术 evidence 在 `DEVELOPER_ADVANCED` 可忽略供应商授权，在 `CONSUMER_STORE` 缺授权必须拒绝；当前不完整 evidence 仍不可注册。consumer 不依赖 `cli-client`，本 HXA 不新增 Provider 或模型 transaction |
| HXA-131 | `./gradlew :runtime:cli-client:test :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug :runtime:cli-app:lintDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | client/runtime 共用唯一 protocol/permission/ComponentName/status 上限和 deadline；companion status binder 继续编译并使用共享契约；不得出现第二份协议或模型 Job transaction |
| HXA-132 | `./gradlew :runtime:cli-client:test :runtime:cli-client:lintDebug :app:testDeveloperDebugUnitTest :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug :runtime:cli-app:lintDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | 在 API 29/36 arm64-v8a 安装 developer App、test APK 与 Runtime，运行 `CliRuntimeHandshakeE2eDeviceTest`：分别验证 `NOT_INSTALLED`、正常握手、`DISABLED`、`FORCE_STOPPED`；正常握手后无活动 Service binding，force-stop 后启动主 App 不得拉起 Runtime。不得出现模型 Job、Provider 或 token 通道 |
| HXA-133 | `./gradlew :runtime:cli-client:test :runtime:cli-client:lintDebug :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug :runtime:cli-app:lintDebug :app:testDeveloperDebugUnitTest :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | API 29/36 arm64-v8a 运行 `CliRuntimeHandshakeE2eDeviceTest#fixedModelJobIsDurableAndNeverBlindlyResubmitted`：无凭据时形成持久 `FAILED`，相同 jobId 返回同一记录，未知 query/cancel 稳定，debug Binder death 后只 query 原记录且不重跑；journal 不含 token/account/正文，终态后无活动 binding。仍不得传任意 prompt/输出或注册 Provider |
| HXA-134 | `./gradlew :runtime:cli-client:test :runtime:cli-client:lintDebug :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug :runtime:cli-app:lintDebug :app:testDeveloperDebugUnitTest :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | API 29/36 arm64-v8a 运行 HXA-133 回归及 `CliRuntimeHandshakeE2eDeviceTest#modelPayloadUsesPfdAndIsDeletedAfterReconcile`：严格有界 `ModelRequest` 经 PFD 提交，统一 `ModelEvent` 经 PFD 对账且 hash 一致；reconcile 后 request/event 正文删除，redacted record 不含 token/account/正文，最终无活动 binding。Provider/对话/Dispatcher/Audit 仍未接入 |
| HXA-135 | `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :app:lintDeveloperDebug :runtime:cli-client:test :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | API 29/36 arm64-v8a 运行 `CodexSubscriptionProviderE2eDeviceTest#developerProviderUsesTheNormalModelContract`：developer 受管理 Provider 注册/测试后可由普通 Session 选择，经 `ChatService` 完成并持久化 Turn/model-call/message；能力保持 tool/vision false，终态无 binding。consumer APK DEX 不含订阅 Provider/cli-client。真实订阅调用可复用 HXA-140/141 的 Runtime 证据，但本 HXA 若未重跑必须明确记录 |
| HXA-136 | `./gradlew :runtime:cli-client:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :app:lintDeveloperDebug :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug --no-configuration-cache`<br>`./scripts/check-cli-runtime-boundary.sh` | API 29/36 arm64-v8a 运行 `CodexSubscriptionProviderE2eDeviceTest#managedAccountOpensTheExplicitRuntimeUi`，含 Runtime force-stop 后用户入口恢复，并确认无持续 binding；consumer APK 无入口/cli-client。另在用户于独占 API 36 arm64-v8a 模拟器完成 Runtime 登录后，以 `-e realSubscription true` 显式运行 `CodexSubscriptionProviderRealAccountDeviceTest#realSubscriptionCompletesThroughHelixChat`，记录模型、固定 prompt hash、`HELIX_OK`、Turn/model-call 和空闲解绑 |

| HXA-144 | 设计审查中：`./scripts/check-docs.sh`、`./scripts/verify-adr.sh`；生产前置为 ADR-0027 接受。实现后运行 roadmap 列出的 cli-client/cli-app/app JVM、双变体构建与 lint 命令 | 尚未实现/验收。API 29/36 arm64-v8a 必须覆盖 Claude fixture 普通对话、取消、断连/重建、旧 Runtime 拒绝 v2、Codex 回归和空闲解绑；设备测试类与 R8/APK 精确命令须在实现开始前补齐。无付费账号，Claude 真实模型调用保持未核实 |

### M12：商店与官网多渠道发布

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-120 | `./gradlew lintConsumerRelease lintDeveloperRelease test` | 第 4 节全部 release/供应链门禁；Google Play/国内商店/官网逐渠道 capability/manifest/SDK/listing/降级矩阵，每项差异有明确政策或审核依据 |
| HXA-121 | `./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease` | API 29/34+/36 真机；各渠道 Standard 核心任务矩阵、Standard/Advanced 组合、SBOM/notice/hash/权限/数据流；Play Accessibility 仅确定性自动化且无外部 executable 下载 |
| HXA-122 | `./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease` | 稳定产品 applicationId、flavor/channel 命名、离线签名、同 ID 升级/回滚、companion 签名握手；不同 ID 不冒充原地升级 |
| HXA-123 | `./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease` | Google Play 与首批国内商店提交包/声明/视频/隐私材料；分别记录准备、提交、审核、拒绝或通过证据，不以构建成功声称上架 |

### M13 Connector 可迁移能力包

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-124 | `./gradlew :extensions:skills:test :extensions:mcp:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebug :app:assembleDeveloperDebug --no-configuration-cache`；`python3 -m unittest discover -s scripts/tests -p test_export_codex_mcp.py` | `./gradlew :app:assembleConsumerDebugAndroidTest --no-configuration-cache` + `./scripts/accept-hxa-124-connectors.sh <serial>`；API 29/36（显式选定设备；含跨进程两阶段恢复）；真实第三方服务需独立测试账号 |
| HXA-125 | `python3 scripts/fetch-hxa-125-samples.py`；`HELIX_CONNECTOR_ACCEPTANCE_DIR="$PWD/app/build/outputs/hxa-125-samples" ./gradlew :app:testConsumerDebugUnitTest --tests "com.helix.app.connector.ConnectorExternalAcceptanceTest" --rerun --no-configuration-cache`；公开来源 hash、生产 reader 解析及真实 SDK 匿名只读调用；用户样本：`HELIX_CONNECTOR_SAMPLE_ZIP="<sample.zip>" ./gradlew :app:testConsumerDebugUnitTest --tests "com.helix.app.connector.ConnectorSuppliedArchiveTest" --rerun --no-configuration-cache` | `./gradlew :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest --no-configuration-cache` + `./scripts/accept-hxa-125-connectors.sh <dedicated-serial>`；API 29/36 匿名服务真实 App/Dispatcher 与跨进程恢复；用户参考包：`./scripts/accept-hxa-125-sample.sh <dedicated-serial> <sample.zip>`，断言原包 4 Skill / 2 endpoints 安装、原文保留及禁用默认；不等于 CLI 业务兼容；bearer 服务拒绝/厂商撤销仍需账号，不以本测试替代 |
| HXA-126 | planned；通用文档门禁 `./scripts/check-docs.sh`、`./scripts/verify-adr.sh`；专项命令在任务启动前依据实际模块补齐 | 未验收；Connector OAuth 登录层需对应真实服务、fixture 或设备证据；不得以 HXA-124 结果替代 |
| HXA-127 | planned；通用文档门禁 `./scripts/check-docs.sh`、`./scripts/verify-adr.sh`；专项命令在任务启动前依据实际模块补齐 | 未验收；大 catalog 渐进工具发现需对应真实服务、fixture 或设备证据；不得以 HXA-124 结果替代 |
| HXA-128 | planned；通用文档门禁 `./scripts/check-docs.sh`、`./scripts/verify-adr.sh`；专项命令在任务启动前依据实际模块补齐 | 未验收；CLI/stdio Connector 可移植性 Spike需对应真实服务、fixture 或设备证据；不得以 HXA-124 结果替代 |
| HXA-129 | planned；通用文档门禁 `./scripts/check-docs.sh`、`./scripts/verify-adr.sh`；专项命令在任务启动前依据实际模块补齐 | 未验收；Connector 完整生命周期需对应真实服务、fixture 或设备证据；不得以 HXA-124 结果替代 |
| HXA-130 | planned；通用文档门禁 `./scripts/check-docs.sh`、`./scripts/verify-adr.sh`；专项命令在任务启动前依据实际模块补齐 | 未验收；Connector 市场设计与来源验证需对应真实服务、fixture 或设备证据；不得以 HXA-124 结果替代 |

## 4. 跨任务发布门禁

```bash
./gradlew spotlessCheck detekt
./gradlew test
./gradlew lintConsumerRelease lintDeveloperRelease
./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease
./gradlew :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease
./scripts/check-lockfiles.sh
./scripts/check-secrets.sh
./scripts/verify-adr.sh
./scripts/verify-variant-boundaries.sh
git diff --check
```

当前 debug APK 路径：

```text
app/build/outputs/apk/consumer/debug/app-consumer-debug.apk
app/build/outputs/apk/developer/debug/app-developer-debug.apk
runtime/proot-app/build/outputs/apk/debug/proot-app-debug.apk
runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk
```
