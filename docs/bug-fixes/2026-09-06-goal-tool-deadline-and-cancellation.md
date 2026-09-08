# Bug Fix: Goal 剩余时长未传入工具 deadline，阻塞执行等待未检查取消

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: app, tools/framework

## Problem

Goal 时间窗口已经能够停止模型和后续准入，但工具执行 deadline 仍只取 descriptor timeout。Dispatcher 的 Future.get 一次等待完整时限，期间没有主动检查取消信号。

## Impact

Goal 预算耗尽或时间窗口中断后，阻塞工具可能继续占用调度等待，直到工具自身时限结束；等待审批所消耗的 Goal 时间也没有缩短后端 deadline。

## Root cause

可信调度请求没有提供运行预算入口，且 watchdog 仅处理 timeout 和线程 interrupt，默认执行器主动响应 CancelSignal。

## Fix and invariants

ToolDispatchRequest 新增可信宿主的剩余执行时间回调。Dispatcher 在审批消费和持久化执行标记之后取值，与 descriptor timeout 取更小值传给原有 ExecutableToolCall deadline；负值归零，不扩大工具预算，不接受模型参数设置该限制。GoalTimeBudget 使用单调时钟返回剩余 wake/lifetime 时间；失效或已关闭窗口返回零。

等待执行结果时以单调时钟计量剩余等待预算，每至多 100 毫秒检查取消。取消中断 Future，并记录 CANCELLED_AFTER_START，绝不声明零副作用；调度线程自身中断也取消 Future 后继续抛出。已经完成的 Future 优先返回其真实结果。线程中断仍是协作式机制，不声称能够强杀任意阻塞代码或远端任务。

## Alternatives considered

只取消协程不能保证同步工具等待立即结束；只在组装请求时固定剩余时间会遗漏审批等待。没有给所有工具统一套 5 秒固定 timeout，正常续约的 Goal 可以执行更长但受预算约束的任务。

## Regression verification

新增 JVM 覆盖执行标记之后重新取剩余时间、不能放宽 descriptor、零/负剩余时间不进入执行器，以及阻塞执行中取消后及时结算且保持副作用未知。工具框架 150/150 通过；consumer JVM 287 项中 284 通过、3 条既有条件跳过。最终 Gradle 日志 `build/main-verification/goal-backend-cancel-final-gates.log` 中构建、consumer Lint 与 Spotless 通过；全仓 Detekt 仍有原有 53 项问题，联合命令 exit 1，不是全绿。

## Residual risk

需继续验证 QuickJS、PRoot、浏览器与远端协议等具体执行域的取消、对账和进程 kill/restart。Future 中断不能证明实际执行已停止，取消或超时后的副作用必须沿既有恢复路径核查。Goal UI 与三项真实模型评测尚未完成。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
