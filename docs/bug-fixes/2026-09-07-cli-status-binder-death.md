# Bug Fix: CLI 状态握手遗漏 Binder 远端死亡异常

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102, HXA-110


## Problem

`CliStatusHandshakeClient.transact` 仅捕获 `RuntimeException`；Android 的 `DeadObjectException` 属于 `RemoteException`，可直接逃逸到 Runtime 验证调用方，而不是返回已有的 `Outcome.Failed`。专用 API 34 上通过本地 Binder 的 `onTransact` 注入 `DeadObjectException`，4 项设备测试中该项失败，其余未处理事务、空状态文档、调用者不匹配通过。

修复前日志：`build/main-verification/cli-handshake-before-api34.log`。这是实际 Android Parcel/Binder API 的异常注入测试，不是对真实 companion 进程执行 SIGKILL。

## Impact

Runtime 验证可能向调用方抛出远端死亡异常，无法返回已有的握手失败状态。

## Root cause

RemoteException 不属于 RuntimeException，原有 catch 未覆盖 Binder 的通信异常。

## Fix and invariants

显式捕获 `RemoteException` 并返回 `Failed`，保留 `CallerMismatch` 独立结果；提取回复解码，减少事务方法的嵌套返回。事务只发送一次，不增加连接重试、Job 重发、账号访问或自动启动；两个 Parcel 仍在 `finally` 释放。

## Alternatives considered

不采用失败后自动重试：这不能代替持久 Job ID 对账。本修复只返回已有的握手失败结果。

## Regression verification

`CliStatusHandshakeDeviceTest` 验证异常转换与回复分类。真实跨进程死亡、超时及 Job ID 恢复矩阵仍需独立验证，不能以本测试替代。

修复后 API 34 **4/4 通过**；CLI Client JVM 24/24、Developer JVM 300 通过/3 既有跳过。CLI Client Lint、构建及 Spotless 通过，根 Detekt 剩余 44 项未通过。原始日志和 APK hash：`build/main-verification/cli-handshake-result.json`。

## Residual risk

本地 Binder 注入不证明真实跨进程死亡、超时或 Job 效果恢复；这些矩阵仍保持待验证。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [ADR-0007](../adr/0007-companion-runtime-lifecycle.md)
