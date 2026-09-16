# HXA-193 developer 单 APK Runtime 重构

> 2026-09-16 接手更新：所有者已授权本任务接手并提交本工作树剩余WIP，不再等待原并行所有方。完整本地主机/构建/lint/制品门禁已通过；原27 detekt、12 lint、2项JGit阻断均为历史结果。HXA-200/201已完成；其他任务按各自剩余验收判断。依赖允许为兼容性与维护升级，须同步版本锁、验证材料和设备证据。当前证据与提交范围见[WIP接手记录](wip-takeover-2026-09-16.md)，下文旧基线/未提交/失败数字只保留追溯用途。

状态：实现与本地专项验证完成，现已通过分支完整本地门禁并由接手任务收口提交；未推送、合并或通过发行验收。

基线：`worktree-harness-2.0`，HEAD `a4a64039b4273620e6472bcb35db754fbe312a91`，包含 HXA-192 未提交修复；两者证据分开记录，不将 HEAD 等同当前工作树。

决策记录：[ADR-0049](../adr/0049-integrated-developer-runtimes.md)（accepted；用户授权，不代表实现验收）。

## 最终职责

- `runtime:cli-app` 与 `runtime:proot-app` 改为 Android library，保留模块路径、自己的实现与测试；`app` 仅以 `developerImplementation` 链接。
- 主进程拥有 UI、Agent、Policy/Approval 和 Room 恢复；`:subscriptions` 拥有订阅协议、登录/网络设置和 vault；`:proot` 拥有 RootFS、执行、停止通知和 journal。Runtime 启动不会初始化主 AppContainer 或执行主数据库恢复。
- 两个私有进程共享 host UID；Service/Activity/Receiver 全部 non-exported。输入快照和环境筛查仍在，但不再宣称离线或主数据/凭据隔离。QuickJS 保留 isolated UID。
- 设置直接打开内置页面，删除子 APK 嵌入、安装器 Activity/FileProvider、安装权限和额外 launcher。历史安装包签名策略的纯函数回归保留，不作为现行安装路径。
- `code.linux.run` 升为 v2，描述和审批卡同步新边界；审批绑定包含工具版本和完整安全描述，旧 v1 精确授权不能用于 v2。L2 逐次审批、Advanced gate、取消、结果校验与持久结算保留。
- 客户端绑定当前 Context 的 package，并验证私有组件存在/启用/进程；不会尝试旧包或回退执行器。PRoot 验证锚改用新文件名，有效旧锚不激活新环境，也不删除旧锚。旧 journal 查询无匹配时保持 unknown，重复 ID 不重新执行。

## 迁移与构建

应用 ID 保持 `com.helix.agent.developer`；旧主数据库、产物和历史不搬迁。旧 companion 的 OAuth/Keystore 数据不读取、不复制，用户在内置页面重新登录；RootFS 重新初始化并验证。旧 companion 中的未对账结果须由用户核查，不能自动重跑。不会自动卸载或清除旧应用。

本工作树原先没有 gitignored RootFS/PRoot 制品。本轮先复用主工作区已有制品，核对两边 lock 完全一致和 RootFS SHA-256 `674aa3ac68200bfe67b01964573fce2c6780c726fe23ca640e998337cf47f509`，完成构建与设备验证。随后重新下载并校验三个锁定的 Termux 包，以同一份归档 RootFS 执行完整资产准备：360 个 ELF 的 16 KiB alignment gate 通过。文件仍被 Git 忽略，逐文件哈希保存在本地证据目录。

developer 构建前现在检查必要制品存在及 RootFS 与 lock 匹配，consumer 无此任务依赖。新工作树必须先运行 `scripts/build-proot-assets.sh`，不能靠缺失 payload 的 APK 冒充完整版本。脚本补齐可执行位、Linux 的 bsdtar 解包、Docker digest 引用和失败诊断；可用 `HELIX_ROOTFS_ARCHIVE` 提供已有 raw tar，哈希验证在解包前执行，且禁止借此生成新 lock。

CI 已接入 arm64 Linux 资产准备、同一 workflow 的 artifact 传输、macOS APK 构建前复核和两个主 APK 上传。可配置仓库变量 `HELIX_ROOTFS_ARCHIVE_URL`，指向上述 SHA 的公开 HTTPS raw tar；空值尝试锁定 Docker 构建。**上游重建本轮实际失败**：镜像索引已用 `xz-libs=5.8.4-r0` 替代锁定的 `5.8.3-r0`，退出 2；没有升级依赖或放宽哈希。合并前须发布已有锁定归档并配置 URL，或恢复可用的原包快照。已验证本地归档输入路径，未上传归档、修改远端变量或运行远端 CI。

## 实际验证

证据目录：`build/single-apk-refactor-2026-09-14/`（忽略制品；含日志、APK SHA、emulator owner/closed 记录）。所有设备运行创建独占模拟器进程，拒绝已有 serial，并在 finally 中只关闭自己启动的进程。未访问用户真实设备、登录账号或凭据。

| 检查 | 结果与范围 |
| --- | --- |
| App 两个 Debug unit task | consumer 494 项（490 通过、4 跳过）；developer 520 项（516 通过、4 跳过）；零失败。跳过来自原 Connector 外部制品/验收测试，本轮未新增跳过 |
| CLI app/client 与 PRoot app/client unit task | 126 + 36 + 2 + 23 = 187 通过，零失败/跳过；合计上两行 1193 通过、8 跳过 |
| App consumer/developer Debug + Release assemble | 四个构建通过；Release 是未配置发行签名的制品，不是发行证明 |
| App developer 与两个 Runtime library 的 androidTest 编译 | 通过；库测试 host 有自己的测试版本号，单个 clipboard ActivityScenario 使用本地测试进程，真实跨进程页面另测 |
| API 36 集成运行 | 31 项执行/恢复 + 1 项真实跨进程页面 = 32 通过；两次独占运行 |
| API 29 集成运行 | 同一组 32 项一次运行通过 |
| API 36 订阅最小 library host | 6 项通过：clipboard、redacted status、Keystore 保存/删除、篡改处理、私有服务、该测试 host 权限；不是生产 UID 隔离证明 |
| 新锚/冷启动/生命周期 | 有效旧锚不继承；两个 Service 与主进程同 UID 不同 PID；PRoot death/rebind、null bind、unknown ID、重复 ID、不重放、MCP stdio cancel；订阅四 Provider fixture、running cancel/death 通过 |
| 产物检查 | `check-all.sh --artifacts` 通过；额外 Release APK 检查通过。consumer 无两组件依赖/dex/manifest/assets/native，developer 无嵌套 APK/安装权限/额外 launcher，并含真实锁定 RootFS 与 native loader |
| 构建输入负例 | 使用 init script 指向仅有 lock 的隔离 fixture，构建按预期拒绝 `Developer runtime inputs missing`；没有移动真实运行制品 |
| 资产流水线 | 三个上游 deb 下载与 lock 校验通过；已有锁定 RootFS 输入 + 360 ELF / 16 KiB gate 通过；无效归档按哈希拒绝。Docker 重建因旧 xz-libs 包版本不在镜像索引而失败；远端归档来源尚待配置 |
| Source 与格式 | `check-all.sh --source`、Spotless、`git diff --check` 通过 |
| 依赖锁 | `check-lockfiles.sh` 35 个锁文件复核通过；两个 Runtime 锁文件仅因 library 的配置闭包变化更新，没有升级依赖版本 |
| Runtime lint | cli-app、cli-client、proot-app、proot-client 的 Debug lint 均无问题 |
| 全量门禁 | `check-all.sh` 已执行且失败：原有 27 项 detekt，主 App lint 12 错（API/Composable/i18n/JGit 等）；没有放宽门禁。新代码 detekt 问题已消除 |

首轮设备失败原因和修复保留在 `device-api36-first`：缺失 RootFS；订阅测试未按 v16 scope 引用读取产物；Codex 测试沿用了早期文本 probe 假设。已补正确制品和 scope 读取，Codex 无账号 fixture 必须验证认证目录 probe 失败，不能宣称具备真实 tool/vision 能力；真实模型流通过 fixture 另验。

复现主要命令（先设置本机 JDK 17 与 `ANDROID_HOME`，不把本机路径写入仓库）：

```bash
HELIX_ROOTFS_ARCHIVE=/path/to/locked-rootfs.tar ./scripts/build-proot-assets.sh
./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerRelease :app:assembleDeveloperRelease
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :runtime:cli-app:testDebugUnitTest :runtime:cli-client:testDebugUnitTest :runtime:proot-app:testDebugUnitTest :runtime:proot-client:testDebugUnitTest
./scripts/check-all.sh --source
./scripts/check-all.sh --artifacts
python3 scripts/verify-integrated-runtime-apks.py --build-type release
python3 scripts/verify-integrated-runtimes.py --avd Helix_API_29 --port 5622 --output build/integrated-api29-new
python3 scripts/verify-integrated-runtimes.py --avd Helix_API_36 --port 5620 --output build/integrated-api36-new
./scripts/check-all.sh
```

新 wrapper 固定本轮的 32 个场景并调用已验证的独占模拟器 runner。原 `accept-hxa-083-*` 等跨 APK、独立卸载/force-stop、权限隔离脚本属于旧快照证据，不是新形态的验收命令；部分历史 phase 测试同理，不能借它们判定当前进程隔离强度。

## 后续责任

1. HXA-192：本地主机门禁已清零，继续补原计划尚缺的Plan用户闭环与授权隔离设备证据；主分支合并仍需独立授权。ADR-0048 已于 2026-09-14 接受有界架构，集成验收未完成；上方旧门禁数量不作为新执行结果。
2. 发行/CI：流水线代码已接入，发布锁定 RootFS 归档并配置 `HELIX_ROOTFS_ARCHIVE_URL`（或恢复原包快照），然后跑远端 CI；可以按所有者授权升级RootFS依赖，但必须完成来源、许可证、hash和设备验证后更新lock，不能只重算hash掩盖缺包。另配置最终单 APK 发行签名，验证升级、安装体积与发行渠道要求。
3. 真实账号与设备：所有者重新登录后验证刷新/退出、旧任务人工核查；真机后台/Doze/OEM、实际系统授权组合与长稳单列。此次无凭据模拟测试不能替代这些验收。
4. 后续若要求执行不可信脚本同时隔离主数据/订阅凭据，需要新的隔离底座/ADR；不能把同 UID 私有进程包装成安全沙箱。

本记录是本地实现与有界证据，不是 main 集成、真实账号或发布许可。
