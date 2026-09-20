# CI 分层与验证边界

更新：2026-09-20。实现入口：[workflow](../../.github/workflows/ci.yml)、[范围选择](../../scripts/ci/plan.py)、[共享门禁](../../scripts/check-all.sh)。

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

source 在 Linux、资产在 arm64 Linux、Android 两分片在 macOS runner；当前 CI 不启动模拟器，也不执行付费模型、真实账号或 OEM 长稳。暂不迁移 runner 平台，先测量上述改动的实际收益。

## 验证与使用

本地新增命令：`./scripts/check-all.sh --debug-analysis`、`./scripts/check-all.sh --debug-tests-build`；`--all` 保持完整语义。CI 策略单测覆盖取消后文档推送、来源/基线筛选、失败和跳过聚合、缓存损坏与网络失败。修改 CI 本身自动走 full。

调整前基线：[382674c3 / 35515226699](https://github.com/dollarser/helix-agent/actions/runs/35515226699)，总时长约 16 分 40 秒；source 22 秒、资产 2 分 39 秒、analysis 9 分 26 秒、tests-build 13 分 23 秒，两个 Android 分片并行。耗时依赖 runner 与缓存冷热，不能把不同档位的时长差当作同等覆盖加速比例。

新流水线验证以实际运行记录为准；全量、手动 Debug 和纯文档路径分别验收。后续工作排序见[工作计划](next-work-plan.md)。
