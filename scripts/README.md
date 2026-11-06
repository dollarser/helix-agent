# 脚本入口与留存

`./scripts/check-all.sh` 是共享主机门禁入口，需要 JDK 17、Android SDK（ANDROID_HOME）和已配置的项目依赖。默认 `--debug` 执行 source → Debug 分析/测试/构建 → Debug artifacts，任一失败即停止；不运行 Release/R8 或设备测试。按变更选用，任务明确要求的门禁仍须执行；[CI](../docs/development/ci.md)显式分档，不依赖本地默认值。

| 分组 | 入口 / 约束 |
| --- | --- |
| 源码与文档 | `check-all.sh --source`：脚本自测、文档、ADR、i18n、Secret |
| 日常源码迭代 | 默认或 `--debug`：源码检查、Debug Spotless/Detekt/Lint、JVM/Android Debug 单测（debugUnitTests）、AndroidTest 源码编译、双渠道 Debug APK、锁与 APK 边界 |
| 主机脚本测试 | `python3 scripts/run-host-tests.py`：统一发现 `scripts/tests/test_*.py`；导入/断言失败、空套件、全跳过不算通过。测试实际设备 runner 的解析/Mock 不启动设备；真实设备验证仍显式运行 |
| 完整编译与回归 | `check-all.sh --build`：Debug/Release 分析、测试、主 APK 与 Runtime 构建、Release smoke APK 编译、锁文件；`--all` 另含 source 和 Debug/Release 制品边界，用于发布准备或相关优化回归 |
| APK 边界 | `--artifacts` 检查已有 Debug APK 的 variant 与 CLI Runtime 边界；`--release-artifacts` 检查已有 Release APK。均须先成功构建对应候选，不自行打包 |
| 记录索引 | `python3 scripts/generate-completion-index.py`，文档门禁检查生成结果是否过期 |
| 有外部依赖的发行验收 | `assetGate` 需要指定待发行 runtime assets；实际分发的第三方制品需核对锁定哈希与法律义务，不能用无资产的主机门禁代替。旧独立 CLI APK 下载门禁已退役，最终主 APK 使用现行 integrated Runtime 边界检查 |
| 设备与长稳 | `run-*`、`accept-hxa-*` 保留稳定入口及既有 HXA 参数；不由 check-all 自动调用 |
| 临时调试 | 一次性编辑器按日期放入 `scripts/local/debug/YYYY-MM-DD/`（忽略）；需维护的诊断/测试工具经审查后跟踪；产物写入 build |

有效历史验收保留或迁入稳定入口；失效改写器与冗余兼容入口经授权按逐路径清单退役，旧命令从记录的Git提交恢复。一次性编辑脚本禁止在最终源码重复执行。设备操作须有当前 owner 授权；默认自建独占实例，owner 指定现有实例时保留其数据且不套用自动关闭流程。`__pycache__/` 已由全局 gitignore 规则忽略，不提交解释器缓存。

Release 构建验证不等于签名发行、R8 行为验证、完整 SBOM/许可证验收或商店审核。CI 保留独立的提交区间 whitespace 检查。

代码导航复用已安装的 CodeGraph、限定范围的 `rg`、Git diff 和宿主读取工具，见[开发上下文导航](../docs/development/context-navigation.md)；不维护第二套导航脚本，也不把 CodeGraph 设为源码门禁的必装依赖。

## Stable validation runners

Reusable entry points live directly in `scripts/`; logs and generated evidence belong in ignored `build/` directories.

- `with-host-slot.py -- COMMAND`: serialize heavy builds and owned device runs across worktrees.
- `run-owned-emulator.py`: general exclusive emulator runner; validates the frozen APK manifests and instrumentation target before launch. Retired standalone CLI/PRoot APKs and their library self-test hosts cannot serve as product targets.
- `run-owned-acceptance-emulator.py`: acceptance runner with recovery/result collection support.
- `run-acceptance-matrix.py`: product/terminal batches; task-specific fixture helpers remain under dated paths until separately promoted.
- `run-hxa103-browser-soak.py SERIAL --workload app --flavor consumer|developer --preset pilot --output FRESH_PATH`: use only on the current task's owned emulator with matching App/Test already installed. The 900-second pilot preserves device-side FD/thread/PSS gates; `precheck` and `p24h` are separate duration requirements. `--workload browser` targets the independent library fixture, not a Developer app. Non-PASS results exit nonzero; a pilot never proves 24-hour stability.
  A physical device requires current owner authorization and explicit `--physical-device` with its serial; connectivity alone grants nothing. The runner rejects emulator serials in physical mode, labels device kind, and preserves all duration/resource/failure gates. It never installs or resets packages; back up user data and inspect fixture scope first. CI remains host-only.
  `--detach` starts an independent session with closed terminal input and adjacent owner/launcher logs; the run's manifest/result remain authoritative. On macOS a temporary `caffeinate` follows the worker. Keep the host awake and the device connected. Completion requires the exact unskipped instrumentation method, device-measured duration, matching process evidence and original resource gates; per-sample `meminfo/` retains memory categories for diagnosis.

Use `--help` for runner arguments. Retired dated shims are recovered through Git provenance; retained callers use stable paths. Existing devices need explicit owner designation; owned runners retain their exclusive-process requirements. Successful helper tests do not constitute device acceptance.

`scripts/debug/` contains retained diagnostics and task-specific fixtures. Consult its README before execution; promote supported utilities here. Repository-wide tracking and deletion rules live in [Git 跟踪与留存](../docs/development/README.md#git-跟踪与留存), including the distinction between ignored outputs and irreplaceable local evidence.

## Experimental Gradle projects

Ordinary Gradle builds exclude the `spikes` modules. To reproduce experiments use `./gradlew -PincludeSpikes=true ...`, including for historical commands that name a Spike project. Product tests in `check-all.sh` do not opt in; `check-lockfiles.sh` still includes experimental projects for lock verification. The A2A Spike checks explicitly select their experiments. `gradle-projects.py` inventories all declared projects, including optional experiments.

Spike sources and historical evidence remain tracked. This changes default project participation, not product capability or experimental acceptance. Fixed-evaluation catalog checks live in `scripts/tests/test_fixed_eval_catalog.py` and run through `run-host-tests.py` (also `check-all.sh --source` / `--debug`); they validate catalog coverage, reproduction metadata and integrity, not model task success. Module or test counts are not an acceptance target.

## 测试与调试维护

[调试维护手册](../docs/development/debugging-playbook.md)集中说明动态结果门禁、主机/设备模板、常见误判与留存。`instrumentation_junit.py LOG`严格验证实际方法；`summarize-owned-runs.py DIR... --output FRESH_PATH`合并同一渠道的owned结果，校验原身份、退出和APK一致性。

## Maintained components and dependencies

- [Device baseline](device_baseline/README.md): source manifest generation, explicit-serial execution and strict result classification. Generated manifests live in `build/device-baseline/`.
- [Acceptance fixtures](fixtures/README.md): private helpers called by the existing acceptance runners; grouped by conversation, extension recovery, product evidence and performance.
- `run-mobile-use-eval.py`: opt-in model evaluation with its original evidence and restoration checks; imports the shared instrumentation parser from `scripts/`.
- [FFmpeg experiments](experiments/ffmpeg/README.md): pinned Lite/Plus/Ready sources, licenses, host contracts and artifact preparation; frozen archive paths are documented there.

The shared host gates remain `check-all.sh` and `run-host-tests.py`. Task-specific
AndroidTest compilation and source-provenance checks use the explicit commands in
[the maintenance playbook](../docs/development/debugging-playbook.md), rather than
new copies of dated host wrappers. Current callers must use maintained paths;
original historical commands are recoverable through the
[curation inventory](debug/archive/2026-10-15-script-curation.json).

## Offline development reference reset

`python3 scripts/maintenance/prepare-reference-reset.py --database BACKUP_DB
--composer-input BACKUP_COMPOSER_DIR` audits a complete stopped-app backup, printing
counts only. It neither connects to a device nor changes the backup. Sidecars,
unknown formats and unsettled Turns must be resolved before preparing a reset.

Adding `--output-database NEW_DB --output-composer-input NEW_DIR
--acknowledge-reference-reset` creates separate review copies: old SUMMARY and
RECENT_MESSAGES quote bindings and draft selections are removed; queued inputs
with those references require withdrawal and reselection before resending. Authored
text, attachments, current HISTORY references and other settings remain. Content
files are not erased. This is an external development maintenance operation, not
an App compatibility reader; device backup/restore and any data loss require
explicit task authorization. Never restore a partial output from a failed run.

`verify-preview-assets.py` 离线核对公式/图表预览的 KaTeX、Mermaid Tiny、独立 DOMPurify 固定资源与许可清单，以及 SVG 资源目录，不下载或执行包脚本；由 `check-all.sh` 的源码检查调用。

`build-mermaid-preview.py` 从 `config/mermaid/mermaid.lock.json` 固定的官方 Tiny、core-js 与对应主机 esbuild 归档重现图表资产：显式提供 `--source-archive`、`--tool-archive`、`--runtime-archive`、`--output`。脚本不下载、不运行安装钩子，校验归档、工具和最终字节；日常检查只验证已审阅资产，无需重新生成。

`python3 scripts/prepare-vad-assets.py` prepares the fixed MIT Silero VAD endpoint
model for continuous dictation using the existing speech runtime. `--verify-lock`
checks reviewed source/license metadata without downloading; `--verify` checks
prepared assets and `--apk <path>` checks packaged model/license bytes. Recognition
models remain on demand; this does not record audio or contact a speech service.

## 电脑 ADB helper 原型

仅支持 Android 12+ 上当前安装的 **Developer Debug 单 APK**（`com.helix.agent.developer`），电脑需要 Python 3 和已授权的 ADB。Release 和 Consumer 没有此原型的交接入口；启动不会替用户选择手机操作插件、改变会话范围或申请系统权限。

```sh
python3 scripts/start-adb-helper.py --serial emulator-5554
python3 scripts/start-adb-helper.py --serial emulator-5554 --stop
```

启动命令返回后 helper 继续在手机端运行；关闭电脑终端或断开 USB 不要求重新启动。默认进程收据在 ignored `build/adb-helper/<serial>.json`，可用 `--ready-file` 指定另一路径，停止时须提供同一路径。收据不含启动凭据；停止命令核对设备本次启动身份、原 PID 和进程启动时间，不误停重启后的进程或复用该 PID 的进程。启动失败仍逐项尝试停止本次进程、清理本次临时文件；清理失败与原始启动错误分别报告。

helper 复用手机操作插件的主屏／任务屏、观察、截图、输入与授权链。已连接的 Root 优先，否则手动启动的 ADB helper 优先于 Shizuku；只在新调用选择，既有帧不跨连接迁移，显示按下述机制恢复。宿主进程退出时 helper 释放并退出，断连不会自动重放输入；需要用户再次执行启动命令，再重新观察目标。原型不含应用内无线配对、自动重启看门狗或任意 shell 接口。设计依据及参考许可见 [ADR-PERMISSIONS-001](../docs/adr/permissions/001-session-authorization.md#decision-history--2026-11-06电脑-adb-启动-helper-原型)。

此原型的独立进程初始化和 Provider 交接使用 Android 非公开平台接口。既有 Debug 设备证据来自 API 36 模拟器和 API 34 真机；Android 12+ 是本轮明确保留的范围，不代表所有版本和 OEM 已验证。接口不匹配会报告启动失败，不自动改用其他操作后端重放任务。

ADB helper 的任务屏由 Helix 持有系统显示 token：helper 重启优先接上原屏并重新观察；原屏消失后只在显式 `ui.launch` 时迁移记录的原 task，不重新启动应用。恢复线索限同一 Helix 进程与当前授权，Helix 本身退出后不保证接回旧 task。显示配置变化仍可能导致 Android 重建 Activity。

任务恢复例外按原 task 消耗，不会永久开启“允许从主屏迁移”；恢复成功后用户再把任务移回主屏，仍须遵守该设置。跨 Helix 进程恢复、手机内无线 ADB 和纯无障碍独立任务屏不在本轮实现范围。

提交的核心验证入口：

| 核心契约 | 入口 |
| --- | --- |
| 原进程停止、跨启动身份与失败清理 | `python3 -m unittest discover -s scripts/tests -p test_start_adb_helper.py` |
| Debug 入口不进入发行产物 | 成功构建后运行 `scripts/verify-variant-boundaries.sh` 和 `scripts/verify-variant-boundaries.sh --helper-release-boundary`；`check-all.sh --all` 已包含两者 |
| 后端选择、固定绑定、任务恢复权限、拒绝与未知结果 | `:extensions:mobile-use:testDebugUnitTest` |
| Binder 断连不重放 | `AdbHelperTransportDeviceTest`（注入测试，不等于真实进程死亡） |
| 任务屏正常流程、跨轮保留与撤权清理 | `BackgroundDisplayDeviceTest#productionToolsReuseDisplayAcrossTurnsAndReleaseItAfterGrantRevocation` |
| 真实模型闭环 | `MobileUseCurrentModelJourneyDeviceTest`，显式 `helixRealModel=true`，仅使用已有授权模型配置与专用测试应用 |
| Root 身份与点击／派发前拒绝 | `RootMobileUseProbeDeviceTest#applicationGrantedRootObservesAndClicksFixture`，显式 `helixRootProbe=true`；已知服务拒绝环境使用独立方法 `authorizedRootWithBlockedServicesRefusesWithoutClicking` 并另传 `helixRootServiceRefusal=true` |

设备测试只在 owner 当前授权时运行，并传 `helix.preserveUserSettings=true`。任务屏测试以 `helixAdbHelper=true` 或 `helixRootHelper=true` 明确后端；ADB 用例报告 `ADB_HELPER_START_NOW` 后再运行上面的启动命令。异常专项由外部控制器响应 `BACKGROUND_DISCONNECT_READY`（核对原进程后终止），`helixRecovery=true` 验证接管与迁移，`helixInFlight=true` 验证实际 DOWN 后中断与持久未决记录；二者不是普通冒烟，不能无人响应就当设备失败或通过。`RECOVERY_OBSERVE` 标记给出暂停阶段和 app cache 文件：外部核对／操作专用测试 task 后删除该标记才继续，`migrated` 阶段将已恢复 task 移回主屏，`recoveredPrimaryRefused` 阶段确认未被工具移走后移回新屏。`BACKGROUND_FOCUS_WAIT` 则在核对 UNKNOWN 后创建标记。阶段编排、持续采样、环境快照和历史原始证据留在 ignored 本地目录，不作为通用 CI 或重复提交的测试框架。
