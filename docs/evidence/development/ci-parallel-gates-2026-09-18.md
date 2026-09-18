# 远端 CI 收尾与并行门禁

日期：2026-09-18。范围是现有主机 CI 的调度与诊断，不关闭设备、账号或发行验收。

## 已完成的 main 验证

`3f5039e408323c95ff905fba9fd33b85c38c4737` 已推送并核对 origin/main；[运行 35312238290](https://github.com/dollarser/helix-agent/actions/runs/35312238290) completed/success。这是 193/195/204/205 合并提交本身的远端验证，不沿用来源分支的绿灯。

| 阶段 | 实测耗时 |
| --- | --- |
| runtime-assets | 2 分 24 秒 |
| verify job | 37 分 40 秒 |
| 格式、静态分析、测试及所有 lint 的首个 Gradle 调用 | 25 分 53 秒 |
| Debug/Release 打包的第二个 Gradle 调用 | 7 分 47 秒 |

从首个 job 开始到最后一个 job 完成为 40 分 12 秒，不包含排队。Gradle 日志确认启用了缓存，不能把慢归因于“没有缓存”；耗时主要集中在串行的分析与打包阶段。

## 优化实现

先在 Ubuntu 执行源码契约、Wrapper 与变更范围空白检查；失败即不启动资产或 macOS 构建。资产仍由固定 arm64 构建流程准备并核对哈希，不更改锁定内容。

两个 macOS job 并行：`analysis` 执行 Spotless、detekt、所有模块 Debug/Release lint 及双 flavor lint；`tests-build` 在一个 Gradle 图中执行所有单测及双 flavor/Runtime Debug/Release 构建，然后检查依赖锁和原 APK 边界。`fail-fast: false` 让另一半继续产出诊断。并行上限为两份 macOS 作业；这可能增加编译和 runner 总用量，优化目标是反馈等待时间，不声称计算成本下降。

保留名为 `verify` 的统一门禁，只有 source、runtime-assets、两个 Android 分片全部成功才成功；依赖失败、取消或跳过均不能变成绿灯。`check-all.sh --all/--build` 继续覆盖原完整集合，新增两个模式供 CI 分片复用，没有删减检查。

每阶段使用 `ci-run-gate.sh` 保留真实退出码，生成独立日志、秒数与状态；即使失败也上传日志和测试/lint 诊断，保留一天。push 的 whitespace 检查覆盖 `event.before..HEAD`，修正旧实现只检查最后一个提交的缺口。

## 验证

```sh
python3 scripts/debug/2026-09-18/verify-ci-gates.py
bash -n scripts/check-all.sh scripts/ci-run-gate.sh
./scripts/ci-run-gate.sh --source
```

均 exit 0。夹具确认完整任务集合、本地与分片命令一致、Gradle 失败码 17、锁检查失败码 19、日志/耗时保存及未知模式拒绝；这不是实际 Gradle 构建的替代品。官方发行的 actionlint 1.7.12（下载后核对官方 SHA-256 清单）检查 exit 0。

## 优化后远端实跑

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
