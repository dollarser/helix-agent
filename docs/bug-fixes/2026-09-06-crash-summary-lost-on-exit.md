# Bug Fix: 崩溃摘要在进程退出前丢失

Status: fixed
Date: 2026-09-06
Related HXA: HXA-104
Affected modules: app

## Problem

普通 Debug 应用主线程实际抛出 IllegalStateException 后，系统记录 CRASH，
新进程读取诊断摘要却仍返回上次测试留下的 IllegalArgumentException。
同进程 read-after-write 的原测试无法覆盖退出窗口。

## Impact

崩溃后的诊断预览可能丢失最近的异常摘要，影响用户定位实际退出原因。

## Root cause

`recordCrash()` 使用 SharedPreferences 异步 apply。内存值更新不代表磁盘完成，
Android 系统异常处理器结束进程时可能尚未 flush。

## Fix and invariants

仅崩溃摘要改为同步 commit，内容仍是有界类型与 stack hash。
异常处理器以 finally 继续委托系统，记录失败不截断系统处理链。
新增受系统 DUMP 权限保护、仅 Debug 可用的故障 Activity，通过普通进程触发异常；
host runner 随后启动新进程读取真实生产诊断预览。

## Alternatives considered

延时退出或全局改为同步 preferences 会扩大影响；仅在崩溃的有界摘要路径同步提交，
正常 heartbeat 与检查点继续异步。不同进程验证避免假设内存可见就等于磁盘持久化。

## Regression verification

API 34/36 `scripts/accept-hxa104-process-death.py` crash 阶段通过：
系统 CRASH 与新进程类型/hash/时间窗口、正文排除同时满足。
另以真实 input dispatch timeout 和系统关闭对话框验证 ANR，未把 force-stop reason 当作 ANR。
本地成功证据：`build/main-verification/diagnostics-crash-fixed-api36/`、
`build/main-verification/diagnostics-anr-dialog2-api36/`；失败日志完整保留。

## Residual risk

跨进程故障须由 host 分阶段测试，不以当前进程内读到 preferences 新值证明持久化。
Debug 组件不进入 Release；普通 suite 的 host-only 验证使用显式 phase，不能把 assumption 算作通过。

## Related records

- [HXA-104](../completion-records/HXA-104.md)
- [M10 收尾跟进](../development/m10-closure-followup.md)
