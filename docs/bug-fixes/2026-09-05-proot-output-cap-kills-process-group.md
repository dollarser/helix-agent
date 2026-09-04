# Bug Fix: PRoot 输出预算必须终止进程组

Status: fixed
Date: 2026-09-05
Related HXA: HXA-073
Affected modules: `runtime/proot-app`, `app/src/androidTestDeveloper`

## Problem

PRoot Job 的 stdout/stderr capture 达到预算后只停止继续读取；若 guest 进程仍在运行，runner 会继续等待其自然退出。

## Impact

有界存储没有变成有界执行时间。恶意或故障 MCP server 可先灌满 stderr，再保持进程存活直到 deadline；首轮设备用例因此等待了脚本中的 60 秒 sleep。状态最终虽为输出超限，但资源释放不及时。

## Root cause

旧 `BoundedCapture` 只设置 `hitLimit` 并结束 reader；终止逻辑要等主 runner 在 `waitFor` 返回后才观察该标志，形成循环依赖：进程不退出，runner 就不能处理超限。

## Fix and invariants

- runner 在启动 capture 前解析并固定 child PID。
- 任一流第一次命中自身或共享输出预算时，同步触发 `killProcessGroup(childPid)`；即使本次 read 只有部分字节可接收，也必须标记超限并杀组。
- stdout、stderr 仍共享总预算，同时 stderr 有独立更小上限；终态保持稳定 `OUTPUT_LIMIT_EXCEEDED`，不伪装成功。

长期不变式：输出预算既限制证据字节，也必须立即限制产生这些字节的整个 guest 进程组生命周期。

## Alternatives considered

- 只关闭 pipe 依赖 guest 收到 SIGPIPE，不适用于忽略写错误或停止写入后继续运行的进程。
- 缩短所有 Job deadline 会改变正常工作负载语义，且不能保证预算命中后立即释放，因此拒绝。

## Regression verification

- `mcpStdioStderrFloodIsKilledAtItsOwnCap`：server 写满 1,024 B 独立 stderr cap 后执行 `sleep 60`；API 29/36 均在 15 秒硬上限内稳定得到输出超限。
- `ProotJobE2eDeviceTest`：API 29/36 各 8/8、0 skip，覆盖成功、噪声拒绝、取消和 stderr flood。

## Residual risk

进程组 kill 仍依赖 HXA-084 已验证的 PID/starttime 与 PRoot 进程组机制；厂商真机的调度时延归发布真机矩阵。

## Related records

- [HXA-073 完成记录](../completion-records/HXA-073.md)
- [HXA-084 完成记录](../completion-records/HXA-084.md)
