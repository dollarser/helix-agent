# Bug Fix: Tool Scheduler admission and settlement remain bounded

Status: fixed
Date: 2026-09-01
Related HXA: HXA-037, HXA-039
Affected modules: `tools:framework`, `app`, `core:agent`

## Problem

Tool Scheduler 的早期实现有三个可以使批次无法稳定结算的缺陷：线程池任务捕获扫描循环的可变索引；footprint 在任务实际启动时才占用槽位；协调循环直接 `join` 全部 pending future 的 `anyOf`。

## Impact

可变索引会产生 stale index/AIOOBE，使 worker 退出但对应 future 永不完成；延迟 footprint 占位会让同一次准入扫描超额启动，并可因值相等删除错误槽位；所有 pending future 都异常时，`anyOf().join()` 把子任务失败变成协调器失败。结果可表现为超出手机并发上限、错误释放隔离槽位或整批死等。

## Root cause

准入、资源占有和完成通知没有被建模为同一个稳定身份的状态转换。实现依赖可变扫描位置和 future 的成功终止语义，而不是依赖 ToolCall 身份和“任意终态都是协调器的正常输入”。

## Fix and invariants

每个 worker 在提交时按值捕获稳定槽位身份。Scheduler 在准入检查通过的同一个临界区内占用由全局 `toolCallId` 标识的 footprint，只有对应身份结算后才释放。协调器使用“首个 future 进入任意终态即唤醒”的 signal future，并把成功、类型化 outcome 和 thrown cause 都视为必须按原 call sequence 结算的槽位结果。每个准入调用最终都必须有 durable outcome；不允许 worker 失败通过漏唤醒或空槽位使整批停滞。

## Alternatives considered

**把并发度降为 1。** 放弃，因为这只隐藏超额准入和结算漏洞，也会丢失 HXA-037 已验收的无冲突只读并发。

**任务启动后再二次检查冲突。** 放弃，因为执行已经开始后无法撤回已发生的副作用，准入与占位必须原子。

**在协调循环捕获 `CompletionException` 后继续。** 放弃，因为这仍然把子任务异常当作控制流，不能给每个槽位产生稳定结果。

## Regression verification

- JVM Scheduler/footprint 测试覆盖并发上限、公平准入、排他屏障、异常 future 和取消。
- API 36 arm64-v8a 设备套件用 900 ms/40 ms 乱序完成、READ_ONLY 重叠与排他屏障、资源门 2→1、单失败不牵连其余调用证明稳定结算。
- 已记录验收命令：`./gradlew :tools:framework:test :core:agent:test :core:storage:testDebugUnitTest` 与 `./gradlew :app:connectedConsumerDebugAndroidTest`。

## Residual risk

真实低内存、后台和热限制信号的接线仍属 HXA-099；当前 `resourceGate` 的“只降不升”机制和上限已有测试，但信号源未实现。

## Related records

- [HXA-037 完成记录](../completion-records/HXA-037.md)
- [HXA-039 batch-safe Turn Coordinator](../completion-records/HXA-039.md)
- [手机端 Tool 编排](../architecture/mobile-tool-orchestration.md)
