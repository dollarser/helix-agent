# Bug Fix: 短回合结束与前台服务提升竞态

Status: fixed
Date: 2026-09-05
Related HXA: HXA-066
Affected modules: app

## Problem

快速真实聊天结束后，API 36 报 ForegroundServiceDidNotStartInTimeException，测试随后失败。

## Impact

已结束的短 Turn 可能在系统延迟检查时引发应用异常。

## Root cause

startForegroundService 的提升延迟至 onStartCommand。运输状态可以在 Service 创建与该回调之间结束，controller 的 stopService 与尚未履行的提升义务发生竞态。

## Fix and invariants

在 onCreate 创建 channel 后立即 startForeground，再发布 runningInstance；launcher 停止只撤销运输请求。已运行实例在主线程用 stopSelfResult(latestStartId) 停止，尚未调度的启动由 onStartCommand 提升后再处理停止，不取消它的提升义务。新 startId 未确认时保持前台，避免误停更新请求；onDestroy 只清除自己的实例引用。保留 STOP、onTimeout 与非黏性退出，不增加后台常驻。

## Alternatives considered

不通过延长任务、吞掉系统异常或隐藏通知规避。单独提前到 onCreate 提升仍在快速回归中失败，故必须处理未确认的启动请求；也不使用可能被后台限制拒绝的新 startService 来发送停止。

## Regression verification

DataSyncForegroundServiceDeviceTest.rapidStartAndStopDoesNotLeaveAPendingForegroundPromotionOrNotification 执行 20 次短 start/stop 并等待 12 秒观察迟到错误、实例与通知清理；既有通知停止、等待审批停止和 API 35 timeout 断言保留。

## Residual risk

真实 OEM 和长稳仍需物理设备；模拟器的快速竞态回归不证明 24 小时稳定性。

## Related records

- [HXA-066](../completion-records/HXA-066.md)
- [main 验证报告](../development/main-merged-verification.md)
