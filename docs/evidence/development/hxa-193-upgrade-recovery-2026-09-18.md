# HXA-193 收口证据：升级恢复验证与完整本地验收（2026-09-18）

> 整合审查修正：下文为原执行者交付快照。当前仅确认构造历史数据后的Runtime冷启动恢复，不含实际旧APK覆盖升级；原 completed/闭合声明不作为完整HXA验收，HXA-193保持待收尾，见[任务](../../development/tasks/HXA-193.md)。

本记录属于 HXA-193 收口轮（第二个执行者，worktree `Helix-runtime-search`，分支 `codex/runtime-closeout-session-search`，基线 `c33cb893`）。前序 CI 现场复核见 [ci-runtime-assets-2026-09-17](ci-runtime-assets-2026-09-17.md)；本轮聚焦该记录留下的两项开放项中的可执行部分：**升级后已有用户数据/配置/结果/旧验证锚的恢复验证**，并补齐实际 CI 最终状态与本地全门禁。

## 结论

- 新增 `UpgradeRecoveryDeviceTest`（3 例）并入规范套件，套件由 32 例扩为 **35 例**；API36 与 API29 独占模拟器各 **OK (35 tests) / 0 失败 / 0 跳过**。
- 实际 CI：main 推送运行 **35248674608**（合并 PR #1）读取到的实际最终状态为 `completed / success`，两个 job（`runtime-assets`、`verify`）均 `success`；此前 PR 运行 35242909407 两 job 通过已在 2026-09-17 记录。
- 本地全门禁（source/build/artifacts + `git diff --check`）与双 flavor × 双构建 APK 边界全部通过（命令与结果见下）。
- 不降低任何 hash/ELF/许可证门禁；未修改 lock；未做依赖升级（现有锁定固定归档路径已验证，升级不满足"真正需要"）。
- 开放边界（外部、既有、明确记录）：默认滚动镜像 Docker 重建仍无法取到锁定的 `xz-libs 5.8.3-r0`（上游镜像状态）；设计上的持久路径是锁定固定归档（CI `runtime-assets` 与本地均按其验证），详见[边界](#边界与外部事实)。

## 本轮新增测试（实现要求，非既有证据）

`app/src/androidTestDeveloper/kotlin/com/helix/app/proot/UpgradeRecoveryDeviceTest.kt`，通过 `scripts/verify-integrated-runtimes.py` 的 CASES 并入规范套件（该脚本 docstring 32→35）。设计依据真实源码核对：journal 位于 `<app filesDir>/provider-v1/codex-model-jobs/<jobId>/record.json`（`CodexPayloadJobStore` → `CodexModelJobStore(File(root,"provider-v1"))`），`recoverInterrupted` 仅在 `:subscriptions` 冷启动（runner init）结算非终态记录，终态记录原样保留，重复提交只返回 Duplicate 不重跑。

1. `priorVersionSucceededJobKeepsResultAndReconcilesExactlyOnce`：种入上一版本的 SUCCEEDED 记录与 `events.json` 结果字节（经真实 codec 编码、sha256 自洽）→ `debugKillRuntime` 强杀 → 冷启动后 `fetchResult` 原样取回结果（服务端 `require(sha256==outputSha256)` 与客户端再校验双层通过）→ `query` 仍未 reconcile → `reconcile` 恰好投递一次并结算 `reconciledAtEpochMillis` → 二次 `reconcile` 无事件；全程状态保持 SUCCEEDED，无重放。
2. `priorVersionHalfDoneJobSettlesInterruptedWithoutReplay`：种入上一版本的 PENDING 记录与部分 `request.json` → 冷启动 `recoverInterrupted` 结算为 INTERRUPTED（`terminalAtEpochMillis` 置位）→ `reconcile` 事件为 null → 状态不再变化，未重新执行。
3. `existingConfigAndUserDataSurviveRuntimeRecoveryCycle`：宿主 SharedPreferences 配置与用户数据文件在强杀+冷启动恢复周期后逐字节可读且内容不变；服务对未知 job 返回 Unknown（恢复后仍正常应答）。

旧验证锚（`proot-runtime/verified-runtime.json`）不激活新环境的断言由套件内既有 `IntegratedRuntimeDeviceTest` 覆盖，本轮不重复实现。

## 制品身份（本轮实际安装/检查对象）

| 制品 | SHA-256 |
| --- | --- |
| developer debug app | `7af888c306b97ed8c00e33c2fdf61a5dcfd63dacf51ab0a7dd531ce230c58b09` |
| developer debug androidTest（最终，spotless 修复后重建） | `7abc63ec22baff52491f9b3f4a846597842c054a2682dcb3aed147996d6ec447` |
| consumer debug | `f74c77f037e8d28ed7efd86f2760168532daae7dcd8fb8d71c468f01591489e5` |
| consumer release（未签名） | `658f1816b99889854008beee869bcfaa637f348891303578eb3ab0c0532b80ed` |
| developer release（未签名） | `453449be025ae9d6db5c01e5cbe5ed164823418ae85d66538c982b4e4436e6f6` |

androidTest dex 已确认含 `UpgradeRecoveryDeviceTest` 及其 3 个方法；每次设备运行后核对 `apks 新于源码`（G2 规则）。

## 命令与结果（全部实际执行）

设备矩阵（规范 35 例；独占 AVD 自启自闭，`-read-only -no-snapshot` 全新启动，1080x2400@420，仅 teardown 自有进程组）：

| 命令 | 结果 |
| --- | --- |
| `bash scripts/debug/2026-09-18/run-193-integrated.sh Helix193_API36 5638 build/193-closeout/api36-final` | `OK (35 tests)`，Time 64.411；`artifacts.json` 身份同上；`closed.json` exit 0 |
| `bash scripts/debug/2026-09-18/run-193-integrated.sh Helix193_API29 5640 build/193-closeout/api29-final` | `OK (35 tests)`，Time 38.167；`closed.json` exit 0 |

两矩阵逐类输出均为 8 类全绿点号（含 `UpgradeRecoveryDeviceTest:...` 3 点）。`api36-upgrade-smoke`（仅新类，OK 3 tests）与 `api36-fresh`/`api29-fresh`（spotless 修复前 APK `c0eb6926…`，各 OK 35 tests）为过程记录，最终验收以 `*-final`（APK `7abc63ec…`）为准。API36 首次 `api36-fresh` 尝试因自有 smoke 拆解后的瞬时 TIME_WAIT 在端口绑定处失败（`run-owned-emulator.py` 按设计拒跑），socket 释放后重试成功；未触碰任何其他 serial。

APK 边界与门禁：

| 命令 | 结果 |
| --- | --- |
| `python3 scripts/verify-integrated-runtime-apks.py` | consumer/developer debug：组件、进程/UID 契约、载荷与 launcher verified |
| `python3 scripts/verify-integrated-runtime-apks.py --build-type release` | consumer/developer release：同上 verified |
| `bash scripts/check-all.sh --source` | exit 0（test-review-gates / check-docs / verify-adr / check-i18n 554 文件 1298 键平价 / check-secrets） |
| `bash scripts/check-all.sh --artifacts` | exit 0（variant boundaries + CLI Runtime 边界） |
| `./gradlew spotlessCheck detekt test lintDebug lintRelease lintConsumerDebug lintDeveloperDebug lintConsumerRelease lintDeveloperRelease` | BUILD SUCCESSFUL |
| `./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :runtime:proot-app:assembleDebug :runtime:cli-app:assembleDebug :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease` | BUILD SUCCESSFUL |
| `./scripts/check-lockfiles.sh` | Dependency lock verification passed (35 files) |
| `git diff --check` | clean |

CI（只读核对，`gh run view`）：

| 运行 | 实际状态 |
| --- | --- |
| main 推送 35248674608（Android CI，合并 PR #1 到 main） | `status=completed, conclusion=success`；jobs：`runtime-assets success`、`verify success` |
| PR #1 运行 35242909407 | 两 job 通过（2026-09-17 已记录，本轮不重跑） |

资产构建（本轮前段，同一收口）：

| 命令 | 结果 |
| --- | --- |
| `HELIX_ROOTFS_ARCHIVE=<锁定原始 tar> scripts/build-proot-assets.sh` | PASS；归档 sha256 `674aa3ac68200bfe67b01964573fce2c6780c726fe23ca640e998337cf47f509`（137287680 B）与 lock 一致；Kotlin 资产门禁 360 ELF（aarch64，16 KiB PT_LOAD）通过；日志 `build/193-closeout/assets.log` |
| `scripts/build-proot-assets.sh`（默认 Docker 重建路径，现测复核） | exit 2：滚动镜像缺少锁定的 `xz-libs 5.8.3-r0` —— 复现已有边界，不作为通过证据 |

## 模拟器纪律

独占创建自有 AVD `Helix193_API36`/`Helix193_API29`（`~/.android/avd/`），唯一端口 5638/5640；每次运行由 `run-owned-emulator.py` 自启、校验 AVD 身份、仅 teardown 自有进程组（`owner.json`/`closed.json` 留痕，自有 pid，exit 0）。未借用、未关闭任何其他执行者的模拟器；未向真机安装/卸载；运行结束后 `adb devices` 无遗留。

## 边界与外部事实

- **默认滚动镜像重建（外部、既有、未闭合）**：上游 Alpine 滚动镜像已不提供锁定的 `xz-libs 5.8.3-r0`，默认 Docker 重建路径 exit 2（本轮现测复现）。`build-proot-assets.sh` 的设计即以此为界：可变镜像不是持久归档，锁定固定归档 + 最终 raw-tar hash 是权威，CI `runtime-assets` 与设备安装路径均走锁定归档并已验证。不修改 lock、不降低校验、不做"为过而升级"。
- 真实订阅行为归 HXA-190；签名身份与发行归 HXA-122/121；本轮不覆盖。
- 未使用缓存或 `--help` 输出充当验收；无真实付费账号、无真实用户会话数据（新测试仅使用自造 fixture job id 与内容）。

## 复现入口

```bash
# 资产（锁定归档路径）
HELIX_ROOTFS_ARCHIVE=<已验证锁定 tar> scripts/build-proot-assets.sh
# 35 例设备矩阵（独占 AVD/端口）
bash scripts/debug/2026-09-18/run-193-integrated.sh <AVD> <port> <output-dir>
# APK 边界
python3 scripts/verify-integrated-runtime-apks.py [--build-type release]
# 门禁
bash scripts/check-all.sh --source
bash scripts/check-all.sh --artifacts
git diff --check
```

## 建议 status 更新（供协调者整合，本执行者不直接改全局 status/roadmap/index）

- HXA-193 标记为完成（本任务范围：包内 Runtime 可复现资产与升级验收），注明外部边界：默认滚动镜像 Docker 重建仍缺锁定 `xz-libs 5.8.3-r0`（上游镜像状态，不阻塞；持久路径为已验证的锁定固定归档）。
- 完成记录：`docs/development/tasks/HXA-193.md`（Completed 索引与 `completion-records/index.md` 由协调者维护）。
- 文档契约：完成记录落地后，活动任务文件 `docs/development/tasks/HXA-193.md` 已由后续文档修复提交删除（`check-docs.sh` 要求活动任务集合 = roadmap − 已完成记录）；原任务文件内的收口记录摘要以本证据文档为准。
