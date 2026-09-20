# CI 分层验证（2026-09-20）

实现提交：`eeb5f04d`。设计与使用见 [CI 分层](../../development/ci.md)，后续任务见[工作计划](../../development/next-work-plan.md)。不修改应用行为，不重新声明任何 HXA 设备或发行验收。

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
