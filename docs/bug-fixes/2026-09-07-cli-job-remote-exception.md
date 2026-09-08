# Bug Fix: CLI Job Binder 普通通信异常逃逸

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102, HXA-110
Affected modules: runtime/cli-client

## Problem

Job Binder 调用只捕获 DeadObjectException，其他 RemoteException 可逃逸。API 34 本地 Binder 注入测试中，普通通信异常失败，远端死亡和缺少状态文档两项通过。

## Impact

调用方可能收到原始通信异常，无法获得既有的不可用结果；本次未发现或宣称已发生重复执行。

## Root cause

DeadObjectException 只是 RemoteException 的子类，原 catch 未覆盖其余 Binder 通信异常。

## Fix and invariants

将 Binder/PFD 传输抽为内部 CliModelJobWire，保留外部 CliModelJobClient API 与轮询/对账顺序。统一捕获 RemoteException 并返回 HANDSHAKE_FAILED；提取回复解析，保留缺失文档拒绝、PFD 大小及输出 hash 检查。事务不重发，Parcel/PFD 释放路径保持。

## Alternatives considered

不通过自动重发解决异常；未知结果仍需按已有 Job ID 查询/对账。未改变协议或增加账号能力。

## Regression verification

新增 CLI Client 模块设备测试，使用仓库固定版本的 AndroidX 测试依赖及锁文件。真实 Android Binder 异常注入记录：`build/main-verification/cli-job-wire-before-api34.log`。测试同时断言通信失败时事务发送次数为一。

修复后 3/3 设备测试通过，CLI Client JVM 24/24、Developer JVM 300 通过/3 既有条件跳过；模块 Lint/构建/Spotless 通过，根 Detekt 剩余 42 项未通过。证据及安装 APK hash：`build/main-verification/cli-job-wire-result.json`。

## Residual risk

本地 Binder 注入不等于跨进程 SIGKILL。真实 Job 提交、PFD 中断和副作用恢复矩阵仍需独立验收。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [ADR-0007](../adr/0007-companion-runtime-lifecycle.md)
