# Bug Fix: 设备全量套件重复执行隔离

Status: fixed
Date: 2026-09-05
Related HXA: HXA-072
Affected modules: `app/src/androidTest`

## Problem

合并后设备全量套件在已运行过测试的模拟器上不能稳定重复执行：recovery fixture 会撞固定主键，API 35+ dataSync timeout fixture 会错误操作上一用例正在销毁的 Service 实例。

## Impact

失败会让 API 29 的 4 个恢复用例红灯，并让 API 36 App 进程以 `ForegroundServiceDidNotStartInTimeException` 崩溃、终止剩余套件。产品代码契约未受影响，但设备验收不能作为可靠证据。

## Root cause

1. API 29 重跑 consumer 全量套件时，`ProcessRecoveryTest` 四例以 `UNIQUE constraint failed: sessions.id` 失败。测试使用固定数据库名和固定实体 ID，但没有在跨 instrumentation 运行间清理数据库；首轮遗留数据会污染下一轮。
2. API 36 的 `dataSyncForegroundStopsOnTheApi35TimeoutCallback` 偶发触发 `ForegroundServiceDidNotStartInTimeException`。上一用例的通知已经消失但 Service 的 `onDestroy` 尚未完成；下一用例把旧的 `runningInstance` 当成新实例并调用 `onTimeout`，导致新一次 `startForegroundService` 尚未执行 `startForeground` 就被停止。

## Fix and invariants

- recovery fixture 的数据库和内容目录加入每个 JUnit 实例唯一 UUID，并在 `@After` 关闭连接、删除本次精确命名的数据库与内容目录；同一测试内部的“死亡前/恢复后”仍打开同一个文件。
- dataSync 设备测试在每例前后显式停止前一 fixture Service；API 35 timeout 用例同时等待新实例和前台通知成立后才调用回调。

没有清空整台模拟器、没有降低断言、没有增加重试掩盖失败。

## Alternatives considered

- 清空整个模拟器或卸载 App 能暂时绕过固定数据，但不能保证 CI/开发者重复运行，且会破坏并行设备状态，因此拒绝。
- 单纯延长等待不能区分旧 Service 与新 Service，也不能修复固定主键，因此拒绝。

## Regression verification

- API 29：先定向重跑 `ProcessRecoveryTest` 4/4，再重跑 consumer 全量，117 tests、1 个按 SDK 设计 skip、0 failed。
- API 36：先定向重跑 `DataSyncForegroundServiceDeviceTest` 3/3，再重跑 consumer 全量，116 tests、0 failed。

## Residual risk

真机厂商对 FGS 生命周期调度仍可能有差异，归发布真机矩阵；本次两个确定性跨轮污染机制均已有定向回归。

## Related records

- [HXA-072 完成记录](../completion-records/HXA-072.md)
- [M7 合并与验证进展](../development/m7-non-device-progress.md)
