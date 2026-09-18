# Bug Fix: PRoot 大输出结算计数边界

Status: fixed
Date: 2026-09-18
Related HXA: HXA-195
Affected modules: runtime:proot-ipc

## Problem

HXA-195 双流压力设备测试中，合法的 6 MiB 输出任务最终成为 FAILED。

## Impact

大输出命令已经执行，却无法保存真实成功的结算记录，影响最终结果与恢复判断。

## Root cause

`ProotJobSpec` 接受最高 64 MiB 的 stdout/stderr 合计输出预算；`ProotJobRecord` 却将两个输出字节**计数值**与 1 MiB 的 journal 文档预算混用。单流超过 1 MiB 时，命令输出与归档已经完成，但构造终态记录抛出异常，外层生命周期处理将任务记录为 FAILED。

## Fix and invariants

现将执行预算上限统一为 `ProotJobSpec.MAX_OUTPUT_BYTES`，结果计数按相同上限校验，并限制合计不超过 64 MiB。journal 文件大小、日志预览配额、默认执行预算均未扩大；仅序列化数值，未把输出正文放进 Binder 或 journal。既有 schema、字段及 terminalCommit 算法不变。

## Alternatives considered

缩小压力测试或把执行预算降至 1 MiB 会掩盖已支持合同中的缺陷；放宽 journal 文档大小则无法修正计数和文档大小混用。采用独立的输出计数边界。

## Regression verification

新增 `ProotJobRecordTest.outputCountersUseTheExecutionCapRatherThanTheJournalDocumentSize`：6 MiB 双流计数与 64 MiB 边界 round-trip，超过合计上限、负数及极大计数拒绝；最大合法计数的记录仍小于 1 KiB。

设备测试保留 `ProotLogStreamDeviceTest.slowReaderAndBothStreamPressureDoNotTruncateTheVerifiedArchive`：stdout/stderr 各 3 MiB、执行预算 8 MiB，期间不读取预览；要求命令成功、预览明确截断且不超过 4 MiB、两份最终输出完整并校验归档 manifest hash。最初 API36 的 `head /dev/zero` 和 API29 的内建 `printf` 均复现失败，排除了单一输出命令解释。修复后 API29/36 各6项日志测试与35项既有回归通过，具体范围见[HXA-195交付证据](../completion-records/HXA-195.md)。

## Residual risk

此修复不提供后台 Job，不扩大模型结果正文预算，也不改变用户授权。64 MiB 是已有合同上限，边界通过 codec 单元测试验证，不据 6 MiB 设备用例宣称 64 MiB 真机内存压力验收。

## Related records

- [当前任务索引](../development/roadmap.md)
- [Runtime 决定](../adr/runtime/002-terminal-and-jobs.md)
