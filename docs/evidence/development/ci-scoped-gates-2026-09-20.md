# CI 分层验证（2026-09-20）

实现提交：`eeb5f04d`。设计与使用见 [CI 分层](../../development/verification-matrix.md#history-ci)，当前能力与验证边界见[当前状态](../../development/status.md)；后续任务由当前用户授权确定。不修改应用行为，不重新声明任何 HXA 设备或发行验收。

## 本地结果

| 命令 | 实际结果 |
| --- | --- |
| `./scripts/check-all.sh --source` | 通过；CI 策略/下载缓存 12 项、既有 review 6 项、JSONL 解析器 7 项；文档/ADR/i18n/secret 通过 |
| `./scripts/check-all.sh --debug-tests-build` | 通过；全部既有 test、双 flavor Debug APK 和依赖锁；585 actionable tasks，热增量 6 秒，不作为远端加速指标 |
| `./scripts/check-all.sh --debug-analysis` | 通过；Spotless、Detekt、Debug lint；803 actionable tasks，热增量 1 分 21 秒 |
| `./scripts/check-all.sh --artifacts` | 通过；两 flavor APK 边界、订阅/Runtime/普通聊天路径；重复的集成 APK 扫描只执行一次 |
| `actionlint .github/workflows/ci.yml` | 1.7.12 官方发行文件按其发布 SHA-256 校验后执行，通过 |
| `bash -n`（4 个变更 shell）及 `git diff --check` | 通过 |
| `HELIX_RUNTIME_DOWNLOAD_CACHE=… ./scripts/build-proot-assets.sh`，连续两轮 | 冷下载与热命中均通过；热轮 4 个锁定下载命中，RootFS 137287680 字节；两轮继续执行完整 asset gate，扫描 360 个 ELF，最小 PT_LOAD 对齐 16384 |

重型命令经现有 `with-host-slot.py` 串行运行。缓存损坏、错误下载与网络失败由合成 fixture 检查，真实资产补验使用固定锁；没有使用账号或模型调用。

早期验证发现仓库不存在 Release unit-test task；已移除不适用的排除参数。最终 Debug 命令保留全部既有 `test`，只减少 Release lint/assemble；不是删除或跳过测试。`--all` 的测试、完整构建与分析语义保留。

## 远端与边界

完整验证：[35516975534](https://github.com/dollarser/helix-agent/actions/runs/35516975534)，对应 `eeb5f04d`。五个 job 全部 completed/success。source 27 秒、资产 2 分 45 秒、tests-build 2 分 47 秒、analysis 4 分 26 秒、verify 6 秒；总墙钟约 8 分钟。与上一轮的缓存状态不同，不据此承诺固定加速比例。

本记录随纯文档提交推送，以已验证的实现提交为基线，预期 source 与 verify 成功、Runtime/Android 按计划跳过；实际结果查看该提交的 Actions 运行，不把预期当作完成。

Debug 档已实际执行本地主机门禁，尚未单独触发远端手动 Debug。静态 ELF 对齐不等于真实 16 KiB Android 设备验收；本轮不运行设备旅程、真实账号、OEM/Doze 或发行门禁。优化前的完整流水线基线与具体取舍见 CI 文档。

<a id="history-ci-parallel-gates-2026-09-18"></a>

## 远端 CI 收尾与并行门禁

日期：2026-09-18。范围是现有主机 CI 的调度与诊断，不关闭设备、账号或发行验收。

### 已完成的 main 验证

`3f5039e408323c95ff905fba9fd33b85c38c4737` 已推送并核对 origin/main；[运行 35312238290](https://github.com/dollarser/helix-agent/actions/runs/35312238290) completed/success。这是 193/195/204/205 合并提交本身的远端验证，不沿用来源分支的绿灯。

| 阶段 | 实测耗时 |
| --- | --- |
| runtime-assets | 2 分 24 秒 |
| verify job | 37 分 40 秒 |
| 格式、静态分析、测试及所有 lint 的首个 Gradle 调用 | 25 分 53 秒 |
| Debug/Release 打包的第二个 Gradle 调用 | 7 分 47 秒 |

从首个 job 开始到最后一个 job 完成为 40 分 12 秒，不包含排队。Gradle 日志确认启用了缓存，不能把慢归因于“没有缓存”；耗时主要集中在串行的分析与打包阶段。

### 优化实现

先在 Ubuntu 执行源码契约、Wrapper 与变更范围空白检查；失败即不启动资产或 macOS 构建。资产仍由固定 arm64 构建流程准备并核对哈希，不更改锁定内容。

两个 macOS job 并行：`analysis` 执行 Spotless、detekt、所有模块 Debug/Release lint 及双 flavor lint；`tests-build` 在一个 Gradle 图中执行所有单测及双 flavor/Runtime Debug/Release 构建，然后检查依赖锁和原 APK 边界。`fail-fast: false` 让另一半继续产出诊断。并行上限为两份 macOS 作业；这可能增加编译和 runner 总用量，优化目标是反馈等待时间，不声称计算成本下降。

保留名为 `verify` 的统一门禁，只有 source、runtime-assets、两个 Android 分片全部成功才成功；依赖失败、取消或跳过均不能变成绿灯。`check-all.sh --all/--build` 继续覆盖原完整集合，新增两个模式供 CI 分片复用，没有删减检查。

每阶段使用 `ci-run-gate.sh` 保留真实退出码，生成独立日志、秒数与状态；即使失败也上传日志和测试/lint 诊断，保留一天。push 的 whitespace 检查覆盖 `event.before..HEAD`，修正旧实现只检查最后一个提交的缺口。

### 验证

```sh
python3 scripts/debug/2026-09-18/verify-ci-gates.py
bash -n scripts/check-all.sh scripts/ci-run-gate.sh
./scripts/ci-run-gate.sh --source
```

均 exit 0。夹具确认完整任务集合、本地与分片命令一致、Gradle 失败码 17、锁检查失败码 19、日志/耗时保存及未知模式拒绝；这不是实际 Gradle 构建的替代品。官方发行的 actionlint 1.7.12（下载后核对官方 SHA-256 清单）检查 exit 0。

### 优化后远端实跑

[PR #2](https://github.com/dollarser/helix-agent/pull/2) 的 `74c11806` 在[运行 35318616570](https://github.com/dollarser/helix-agent/actions/runs/35318616570) 全部 completed/success：

| job | 实测耗时 |
| --- | --- |
| source | 25 秒 |
| runtime-assets | 2 分 29 秒 |
| android (analysis) | 2 分 47 秒 |
| android (tests-build) | 4 分 6 秒 |
| verify | 4 秒 |

从首个 job 开始到最后一个结束共 **7 分 18 秒**。本轮复用了前次 main 构建的 Gradle 缓存，且生产源码未变，不能将相对 40 分 12 秒的全部差值归因于并行化，也不承诺冷缓存同样耗时。可确认的是双分片同时运行、原检查集合全部通过、诊断与 APK 制品上传成功；本地/CI 门禁契约一致。

首次运行 35318494875 因 Ubuntu 缺少 ripgrep 在源码门禁明确失败；资产与 Android job 均跳过，`verify` 仍失败。补齐显式安装后上述重跑通过。这同时留下了依赖失败/跳过不被汇总成成功的真实证据。
