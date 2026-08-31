# Helix 验收命令矩阵

基线日期：2026-08-31。命令来自 Gradle 9.5.0 / AGP 9.3.2 工程的真实
`projects` 与 `tasks --all` 输出。每个 HXA 开始前必须确认对应命令仍存在；若模块、
variant 或 source set 改名，先更新本矩阵，再实现功能。

## 1. 通用约定

- 所有 Gradle 命令从仓库根目录执行，并且只使用 `./gradlew`。
- JVM 行无需设备；Android 行需要 `adb devices` 中存在已授权设备或模拟器。
- consumer 仪器测试验证受限渠道的共享功能与裁剪边界；修改共享逻辑、consumer route/manifest 或变体边界时必须运行对应 consumer task。
- developer 是当前直接分发用户流程的主验收对象；涉及 developer 行为、Standard/Advanced、All-files、Accessibility、Root 或 Runtime client 时必须运行对应 developer task。consumer 裁剪测试仍是发布阻断项，Runtime APK 只在对应 companion 场景安装。
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

### M4：Workspace 与文件

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-040 | `./gradlew :core:workspace:test` | 无 |
| HXA-041 | `./gradlew :core:workspace:test` | 磁盘满/中断 fixture |
| HXA-042 | `./gradlew :tools:files:test :core:workspace:test` | 无 |
| HXA-043 | `./gradlew :tools:files:test :core:workspace:test` | 无 |
| HXA-044 | `./gradlew :feature:files:testDebugUnitTest` | `./gradlew :feature:files:connectedDebugAndroidTest`；恶意 ContentProvider |
| HXA-045 | `./gradlew :feature:files-allfiles:testDebugUnitTest` | `./gradlew :app:connectedDeveloperDebugAndroidTest`；专用设备 |
| HXA-046 | `./gradlew :feature:files:testDebugUnitTest :app:testConsumerDebugUnitTest` | `./gradlew :app:connectedConsumerDebugAndroidTest` |
| HXA-047 | `./gradlew :tools:files:test` | Zip Slip/膨胀比 fixture |

### M5：QuickJS

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-050 | `./gradlew :runtime:quickjs:testDebugUnitTest :runtime:quickjs:assembleDebug` | `./gradlew :runtime:quickjs:connectedDebugAndroidTest`；API 29/36、arm64/x86_64 |
| HXA-051 | `./gradlew :runtime:quickjs:testDebugUnitTest` | `./gradlew :runtime:quickjs:connectedDebugAndroidTest` |
| HXA-052 | `./gradlew :runtime:quickjs:testDebugUnitTest` | `./gradlew :runtime:quickjs:connectedDebugAndroidTest` |
| HXA-053 | `./gradlew :runtime:quickjs:testDebugUnitTest :tools:framework:test` | `./gradlew :app:connectedConsumerDebugAndroidTest` |
| HXA-054 | `./gradlew :runtime:quickjs:testDebugUnitTest` | `./gradlew :runtime:quickjs:connectedDebugAndroidTest`；真机崩溃/内存/取消 |

### M6：浏览器与 Android 工具

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-060 | `./gradlew :feature:browser:testDebugUnitTest` | `./gradlew :feature:browser:connectedDebugAndroidTest` |
| HXA-061 | `./gradlew :feature:browser:testDebugUnitTest` | `./gradlew :feature:browser:connectedDebugAndroidTest`；恶意页面 |
| HXA-062 | `./gradlew :tools:browser:testDebugUnitTest :feature:browser:testDebugUnitTest` | `./gradlew :feature:browser:connectedDebugAndroidTest` |
| HXA-063 | `./gradlew :feature:browser:testDebugUnitTest` | `./gradlew :feature:browser:connectedDebugAndroidTest` |
| HXA-064 | `./gradlew :tools:android:testDebugUnitTest` | `./gradlew :tools:android:connectedDebugAndroidTest` |
| HXA-065 | `./gradlew :tools:android:testDebugUnitTest` | `./gradlew :tools:android:connectedDebugAndroidTest` |
| HXA-066 | `./gradlew :tools:android:testDebugUnitTest :core:policy:test` | `./gradlew :tools:android:connectedDebugAndroidTest`；DNS rebinding/redirect/peer/scope 与 Standard/Advanced 网络边界 |
| HXA-067 | `./gradlew :app:testConsumerDebugUnitTest :feature:browser:testDebugUnitTest` | `./gradlew :app:connectedConsumerDebugAndroidTest` |

### M7：MCP 与 Skills

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-070 | `./gradlew :extensions:mcp:test` | 本地 Streamable HTTP fixture |
| HXA-071 | `./gradlew :extensions:mcp:test` | 恶意 schema/result fixture |
| HXA-072 | `./gradlew :extensions:mcp:test` | `./gradlew :app:connectedConsumerDebugAndroidTest` |
| HXA-073 | `./gradlew :extensions:skills:test` | 恶意归档/路径 fixture |
| HXA-074 | `./gradlew :extensions:skills:test` | 无 |
| HXA-075 | `./gradlew :extensions:skills:test :tools:framework:test` | 无 |
| HXA-076 | `./gradlew :app:testConsumerDebugUnitTest :extensions:mcp:test :extensions:skills:test` | `./gradlew :app:connectedConsumerDebugAndroidTest` |

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
| HXA-100 | `./gradlew test` | 固定场景集；记录模型/工具版本和证据 |
| HXA-101 | `./gradlew test` | 攻击语料集，不调用真实付费模型 |
| HXA-102 | `./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease` | 真机低内存/后台/Doze/进程回收；持久化 pause reason、有界 usage checkpoint、反复 crash/墙钟回拨不增加预算、不重放副作用 |
| HXA-103 | `./gradlew lintConsumerRelease lintDeveloperRelease test` | API 29/36 模拟器与 API 34+/36 真机 |
| HXA-104 | `./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleRelease` | `./scripts/verify-variant-boundaries.sh`；APK 权限/内容/ABI/体积 |
| HXA-105 | `./gradlew :core:agent:test :tools:framework:test :core:storage:testDebugUnitTest` | `./gradlew :app:connectedDeveloperDebugAndroidTest`；只读 child depth/cap/父预算/无审批凭证/恢复/温升与 JSON DAG Spike，产出并决定 ADR-0009 |

### M11：官方 CLI 隔离实验

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-110 | `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:assembleDebug` | 固定 artifact 来源/hash/license/版本；独立 UID/权限/冷绑定 manifest |
| HXA-111 | `./gradlew :runtime:cli-client:testDebugUnitTest :runtime:cli-app:testDebugUnitTest` | `./gradlew :runtime:cli-app:connectedDebugAndroidTest`；登录/退出/跨 UID/Activity 退出后冷绑定 |
| HXA-112 | `./gradlew :runtime:cli-app:testDebugUnitTest` | `./gradlew :runtime:cli-app:connectedDebugAndroidTest`；恶意工作区/工具拦截/进程死亡对账 |
| HXA-113 | `./gradlew :app:testDeveloperDebugUnitTest :runtime:cli-app:assembleDebug` | `./gradlew :app:connectedDeveloperDebugAndroidTest`；jobId 查询/不重放；不合格则保持独立 CLI |

### M12：直接分发

| 任务 | JVM/构建命令 | Android/外部验收 |
| --- | --- | --- |
| HXA-120 | `./gradlew lintConsumerRelease lintDeveloperRelease test` | 第 4 节全部 release/供应链门禁；下载清单仅一个 developer-derived Helix 主包，consumer 无 Advanced 且不进默认下载，Runtime 标为 companion |
| HXA-121 | `./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease` | API 29/34+/36 真机；同一主应用 Standard/Advanced 组合、SBOM/notice/hash/权限/数据流/已知限制 |
| HXA-122 | `./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease` | 最终主应用 applicationId 决策、离线签名、同 ID 升级/回滚、companion 安装顺序和签名握手；不同 ID 不冒充原地升级 |
| HXA-123 | `./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease` | 具体商店/企业渠道政策与 consumer 发布结论；未形成渠道决定时 consumer 不进用户下载 |

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
