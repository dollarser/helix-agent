# Bug Fix: Goal 证据预览取消未中断阻塞读取

Status: fixed
Date: 2026-09-08
Related HXA: HXA-102
Affected modules: app

## Problem

关闭 Goal 证据界面会取消其协程，但候选与预览服务只使用 `withContext(Dispatchers.IO)`。取消不会中断正在执行的同步归档/文件读取，后台读取可能继续至操作自然结束。

## Impact

界面离开后继续消耗读取与归档提取资源，并可能继续产生私有快照。没有发现该行为可以自动确认复核或完成 Goal；两者仍有独立的显式操作和来源验证。

## Root cause

协程取消与阻塞线程中断未连接。PRoot 读取器已有线程中断检查，但 UI 取消不会设置该线程的中断状态。

## Fix and invariants

候选与预览调用通过 `readGoalEvidence` 使用 `runInterruptible(Dispatchers.IO)`。证据事务入口/出口、快照登记前、文件读取循环及 PRoot 来源边界检查线程中断。预览取消不变成用户复核；已有证据和显式 Continue/预算语义不变。归档提取仍有既有资源上限，中断后在检查点退出并清理临时目录。

取消不承诺撤销已经完成的私有快照写入：发布与取消发生竞争时可能保留无复核权限的快照或未登记文件，后续仍须完整验证，不能当作复核/完成事实。没有增加 Runtime 调用、ACK、重放或文件 scope。

## Alternatives considered

- 只取消 UI 等待：保留后台读取，无法满足读取中断验证。
- 将阻塞工作放在主线程：会阻塞交互，拒绝。
- 取消时删除所有已有证据：会破坏有效历史引用，与本次问题无关，拒绝。

## Regression verification

`ProotEvidenceCancellationTest` 在实际归档读取检查点使用受控 latch 暂停 IO，取消调用协程后要求在释放 latch 之前退出、没有返回内容、提取目录已清理，随后可重新读取原归档。旧 `withContext(IO)` 对照中，读取线程未因取消退出，持续至 10 秒 latch 超时而失败；随后强化为释放 latch 前 3 秒内必须退出的断言，并对修复版本强制复验通过。对照后原生产实现立即恢复；原日志与 XML 为 `build/main-verification/evidence-read-cancel-baseline.log` / `.xml`。该对照不是自然磁盘阻塞或进程死亡验收。

修复后的读取 JVM、构建及 Detekt 结果见 `build/main-verification/evidence-read-cancel-build.log`；恢复后的复验见 `evidence-read-cancel-verified.log`，最终严格断言的强制执行为 `evidence-read-cancel-strict.log`（6 项、零失败/跳过）。两 API 的 Goal 包各 33 项及证据 UI 各 6 项，共 78/78，通过；命令、APK hash 和原始日志在 `evidence-read-cancel-device/`。

## Residual risk

同步归档提取阶段在有界操作返回后的检查点响应中断，不声称任意文件系统调用都能即时停止。真实读取阶段 SIGKILL/恢复及最终全量合并测试继续由 HXA-102 跟进；本组不替代真机或长稳。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [main 验证报告](../development/main-merged-verification.md)
- [ADR-0028](../adr/0028-goal-criterion-verification-bindings.md)
