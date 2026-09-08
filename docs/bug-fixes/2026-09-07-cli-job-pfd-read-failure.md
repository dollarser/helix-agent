# Bug Fix: CLI Job PFD 读取异常逃逸

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102, HXA-110
Affected modules: runtime/cli-client

## Problem

对账回复携带不可读的 PFD 时，CliPfdChannel.read 抛出的 IOException 未被 Job 传输层捕获。API 34 实际 Android Parcel/PFD 测试得到 EBADF；新增三项中该项失败，有效结果和 hash 不匹配两项通过。

## Impact

调用方无法收到已有的不可用结果。未发现或宣称已发生结果伪造或重复执行。

## Root cause

传输层原捕获 RemoteException/RuntimeException，遗漏 PFD 创建或读取的 IOException。

## Fix and invariants

显式捕获 IOException 返回 HANDSHAKE_FAILED，不返回部分事件、不重发事务。保留既有状态文档解析、输出 hash 校验、事件 codec 边界和描述符释放。

## Alternatives considered

不返回空事件成功，也不为读取失败重新提交 Job。结果恢复必须沿已有 Job ID 对账。

## Regression verification

CliModelPfdDeviceTest 使用真实 ParcelFileDescriptor 管道：验证有效结果事件一致、错误 hash 拒绝、不可读描述符返回失败；每例对账事务一次。修复前日志：`build/main-verification/cli-pfd-before-api34.log`。

修复后 PFD/Binder 组合 6/6 通过。CLI Client JVM 24/24、Developer 300 通过/3 既有跳过，模块 Lint/构建/Spotless 通过；根 Detekt 42 项未通过。原始日志与 APK hash：`build/main-verification/cli-pfd-result.json`。

## Residual risk

本地 Binder/PFD 注入不是跨进程 SIGKILL。提交端写管道、超时、真实 Runtime 及副作用对账仍需独立验证。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [ADR-0007](../adr/0007-companion-runtime-lifecycle.md)
