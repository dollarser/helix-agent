# CI 分层与验证边界

更新：2026-09-21。实现入口：[workflow](../../.github/workflows/ci.yml)、[范围选择](../../scripts/ci/plan.py)、[共享门禁](../../scripts/check-all.sh)、[暂存区快检](../../scripts/check-staged.py)。

## 本地与远端分工

不要在每次 commit 上运行 `--all`，也不要把本地回归全部移到远端。按反馈成本分为四层：

| 时点 | 检查 | 边界 |
| --- | --- | --- |
| 可选 pre-commit | 已暂存 blob 的密钥模式、空白/冲突标记 | 不启动 Gradle、不联网、不扫描整个工作树；常规小提交目标 3 秒以内 |
| 编辑及提交前的定向验证 | 修改模块的测试/编译；文档、ADR、资源变更执行对应脚本 | 不要求每次 commit 都重跑；已通过且未受后续改动影响的结果可以复用 |
| HXA 收口与集成 | 任务规定的 `--all`、设备及实际旅程 | 完整验收仍在本地执行；CI 不能替代真机、OEM、Doze、真实账号 |
| GitHub Actions | 根据累计变化选择 source/debug/full，统一 `verify` 结论 | 远端提供独立、可追溯的主机门禁；不是每次提交都跑全量 Release |

3 秒是实测反馈目标，不是超时放行条件。密钥规则命中、缺少扫描器、读 blob 失败或冲突会阻断；大文件/大量修改可能更久，不静默漏查。密钥扫描只覆盖已知模式，不保证发现所有凭据。硬编码 UI 文本和 ADR 格式错误通常可逆，不应统称“不可逆失误”；i18n 检查也不是禁止注释、文档或所有中文。

提交钩子只读 Git index，包括暂存的二进制字节和符号链接文本；删除文件不再扫描，gitlink 只提交引用、不扫描子模块内容。部分暂存时，工作区的修复不能掩盖仍在 index 中的密钥，未暂存改动也不能污染本次提交判断。密钥和空白失败不回显正文，避免日志再次泄漏。规则与全量 `check-secrets.sh` 共用 `secret-pattern.txt`。

2026-09-21 本机本轮 10 个暂存文件实测 `0.137 s`；暂存/工作区不一致、二进制/特殊文件名、4 MiB blob、重命名/删除、符号链接、扫描器失败及共享规则回归 10/10 通过。该样本不是所有机器或任意提交大小的 3 秒保证。

单次使用：

```bash
python3 scripts/check-staged.py
git -c core.hooksPath=.githooks commit
```

希望持续启用时可自行执行 `git config --local core.hooksPath .githooks`；该仓库配置可能影响同仓库其他 worktree，应先确认现有 hooks。本轮不自动覆盖共享配置。Git hooks 不随 clone 自动启用，也可被绕过，因此 CI 仍独立执行完整 source 契约，不能把钩子当作强制远端安全边界。

## 自动选择

| 变化 | 档位 | 执行内容 |
| --- | --- | --- |
| 只有 docs、README、AGENTS、LICENSE、历史 scripts/debug | source | Wrapper、文档/ADR/i18n/secret、CI 策略与导出校验器单测、diff 检查；不准备 Runtime、不启动 macOS 构建 |
| 普通 Kotlin/Java 与 Android 资源 | debug | source + 锁定 Runtime + Spotless/Detekt/Debug lint + JVM/Debug tests + 两 flavor Debug APK + 制品/依赖边界 |
| Workflow、构建/依赖/Manifest、原生代码、Release 路径、未知类型 | full | 原有完整主机门禁，包含全部 tests、Debug/Release lint 和 assemble |
| 手动 workflow_dispatch | 默认 full，可选 debug | 用于主动完整验证或复核日常 Debug 路径，不提供手动跳过 Android 的入口 |

Push 使用最近 30 次成功运行内、属于 main 祖先且全部 Android job 成功的 **push** 提交作为比较基线；source-only 成功和手动运行不成为基线。这样代码 CI 被取消后再推文档，仍会验证累计代码变化。PR 使用完整 merge-base 差异，删除/移动按两端路径判断。无可信基线、API 不可用或未知文件时运行 full。

`verify` 保留稳定检查名：source 必须成功；仅 source 档允许两个重型阶段按计划 skipped，其余失败、取消和意外跳过都失败。分支保护如另行强制要求 Android 子 job，需与这一聚合检查策略保持一致。

## 省去的重复工作

- 纯文档/历史脚本提交省掉资产与 Android 构建。脚本开发所需实际设备验收仍按任务规格执行。
- 日常代码省掉 Release 重复编译/lint，完整门禁仍用于构建配置变更、手动验证和 HXA 收口。该取舍不是 Release 已验证声明。
- 锁定原始 Runtime 下载按 lock 与流水线版本缓存，每次命中重新检查 SHA-256 和长度；解包、ELF/ABI 及最终 APK 边界继续执行。只有 main 的可信运行保存缓存，PR 只读；缓存不含账号、Runtime home 或真实模型响应。
- 组合制品门禁只扫描一次相同的集成 Runtime APK；独立调用订阅边界脚本仍自带扫描。
- full 档额外运行 `--release-artifacts`，验证实际 Release APK 的组件/dex/native 边界。原 `--artifacts` 只检查 Debug，不能因为构建了 Release 就声称 Release 边界也通过；本地 `--all` 现包含此补充，Debug 档保持原范围。

source 在 Linux、资产在 arm64 Linux、Android 两分片在 macOS runner；当前 CI 不启动模拟器，也不执行付费模型、真实账号或 OEM 长稳。暂不迁移 runner 平台，先测量上述改动的实际收益。

## 验证与使用

手动完整验证：`gh workflow run ci.yml --ref main -f scope=full`；手动 Debug：`gh workflow run ci.yml --ref main -f scope=debug`。同一 ref 的新运行会取消旧运行，应等待前一轮完成再触发。

本地新增命令：`./scripts/check-all.sh --debug-analysis`、`./scripts/check-all.sh --debug-tests-build`；`--all` 保持完整语义。CI 策略单测覆盖取消后文档推送、来源/基线筛选、失败和跳过聚合、缓存损坏与网络失败。修改 CI 本身自动走 full。

不要把命令列表简单称作“8 种 Lint 变体”：当前 full 分析入口为 2 个聚合 lint 任务和 4 个 flavor/build-type 任务，Gradle 按依赖图去重；实际工作量取决于模块和缓存。历史远端约 16 分钟不等于每次本地都需 10～20 分钟。上一轮暖缓存本地两段 Gradle 分别为 40 秒与 1 秒，另有 source/制品扫描开销；比较优化前后必须注明机器、缓存、范围与端到端时间。

调整前基线：[382674c3 / 35515226699](https://github.com/dollarser/helix-agent/actions/runs/35515226699)，总时长约 16 分 40 秒；source 22 秒、资产 2 分 39 秒、analysis 9 分 26 秒、tests-build 13 分 23 秒，两个 Android 分片并行。耗时依赖 runner 与缓存冷热，不能把不同档位的时长差当作同等覆盖加速比例。

新流水线的实际命令、结果和未验边界见[验证记录](../evidence/development/ci-scoped-gates-2026-09-20.md)。后续工作排序见[工作计划](next-work-plan.md)。
