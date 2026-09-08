# Bug Fix: CLI 请求管道写线程未捕获 EPIPE

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102, HXA-110
Affected modules: runtime/cli-client

## Problem

Runtime 拒绝提交且未读取请求 PFD 时，后台写线程抛出未捕获 IOException/EPIPE。API 34 以 256 KiB 请求与拒绝事务复现，测试捕获了写线程的未处理异常。

## Impact

正常 Android 默认未捕获异常处理可能终止应用进程。测试通过临时处理器观察指定写线程的异常，没有把其他线程异常吞掉，也没有把它描述为真实 Runtime SIGKILL。

## Root cause

请求 PFD 写入在线程内执行，外层 Binder try/catch 无法捕获该线程的异常；原实现不记录上传失败或等待写线程结束。

## Fix and invariants

CliRequestPipe 负责单次上传，记录 IOException，关闭描述符并有界等待写线程（最多一秒）；等待中断保留当前线程中断标志。传输结束后，已知失败或仍在运行的上传不能返回成功结果。先回收 Parcel 引用再清理管道，拒绝路径可以结束阻塞写入。无事务或 Job 重发。

## Alternatives considered

不使用全局异常处理器作为产品修复，不吞异常后保留成功回复，不无限等待写线程。

## Regression verification

CliRequestPipeDeviceTest 覆盖拒绝 256 KiB 上传时无未捕获异常、线程退出、事务一次，以及完整读取请求字节后仍接受。修复前日志：`build/main-verification/cli-request-pipe-before-api34.log`。

修复后上传/PFD/Binder 8/8 通过，CLI Client JVM 24/24、Developer 300 通过/3 既有跳过；模块 Lint、构建和 Spotless 通过，根 Detekt 仍 42 项未通过。日志与 APK hash：`build/main-verification/cli-request-pipe-result.json`。

追加提前 ACCEPTED 与保留读取端但不消费两项回归，组合 10/10 通过；保留读取端场景在三秒内返回失败，释放后线程退出。证据：`build/main-verification/cli-premature-ack-result.json`。仅覆盖该有界本地夹具，不扩展为所有跨进程阻塞保证。

## Residual risk

本地 Binder/PFD 测试不替代真实跨进程死亡或恶意远端长期持有描述符的验收；一秒上限约束清理等待，不证明所有内核阻塞均已终止。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [ADR-0007](../adr/0007-companion-runtime-lifecycle.md)
