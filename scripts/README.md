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

Spike sources and historical evidence remain tracked. This changes default project participation, not product capability or experimental acceptance. The `:testing` module retains its fixed-evaluation coverage; module or test counts are not an acceptance target.

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
