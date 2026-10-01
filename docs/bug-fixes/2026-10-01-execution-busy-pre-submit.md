# Bug Fix: v0.0.4 JS/Bash EXECUTION_BUSY 提交前占用泄漏

Status: fixed
Date: 2026-10-01
Related HXA: HXA-236, HXA-237
Affected modules: app, tools/framework

2026-10-01 所有者反馈 v0.0.4 运行 JS 和 bash 显示 `EXECUTION_BUSY`/运行时在忙。本轮从 `refs/tags/v0.0.4` 对比到修复前 `4d449fb9`：ExecutionOwnership、NativeJavascriptOwnership、ForegroundProotOwnership 与手动终端启动的相关代码没有修复此缺陷；HXA-237 的字体/键盘改动不等于执行占用已经修好。

## Problem

找到并修复一个能够使 JS/Bash 一起持续报忙的确定性代码缺陷：**手动终端在 START 发出前连接失败，遗留持久占用。** 单凭用户提供的错误文字，不能确定其手机当时一定走了这一路；活着的终端、待收取后台 Job 或尚未确认停止的 native 执行也会触发同一保护。既有 v0.0.4 APK 不会因源码修改自动更新。

## Impact

确定未提交却遗留的全局 owner 会跨调用和应用重开阻止 JS/Bash，原终端未实际启动时也无法通过正常页面结束它。该缺陷可影响后续本地任务，不说明模型本身没有代码执行能力。

## Root cause

`DeveloperManualTerminal.start` 原先在 `PtySessionClient.connect()` 之前就保存终端 binding，并把全局 ExecutionOwnership 的普通调用许可转成持久 owner。若 connect 抛错，Wire.START 还没有发出，外层 use 只释放当前调用的内存许可，持久 owner 与终端 binding 却留下。原逻辑只处理明确 START_REFUSED/CAPACITY_EXHAUSTED，遗漏了提交前异常路径。

JS 和 bash 都经过应用级共享执行准入；它不是每个语言独立的“忙”状态，也不是订阅账号/模型额度错误。持久 owner 保留会拒绝后续写/代码执行；主进程重开不会自动清除此 owner。Runtime 没有对应终端记录时，页面还可能看不见一个可正常结算的会话。

`TerminalStartTransactionTest.version004ReservationBeforeConnectReproducesTheStrandedBusyOwner` 重放原本地准入顺序，在连接阶段注入失败，证明 retained owner 仍在且 JS/Bash 准入均被阻止。这是纯 JVM 的旧算法反例，不是用户真机取证，也不是实际运行旧 APK。

## Fix and invariants

新增 `TerminalStartTransaction`，生产第一/第二终端共用同一保留/提交边界：

- START 前确定失败：按精确 owner/generation 解除本次未提交保留，并清理自己的终端 binding；持久 CAS 已写入后抛错也先核对真实身份，不能把抛错等同没有写入。
- 明确 START_REFUSED/CAPACITY_EXHAUSTED：仍按确定未启动处理。
- START 已尝试发送、回执丢失或发生后置异常：保留原 owner/binding，不凭异常推断没有副作用，不重放命令。
- 第二终端失败不解除第一终端；其他 generation 不被旧清理覆盖；清理失败与原异常均保留。

ExecutionOwnership 仍 fail-closed，但返回细分原因 `RETAINED_EXECUTION`、`ACTIVE_EXECUTION`、`RECONCILING` 或竞争状态变化，保留稳定 `EXECUTION_BUSY` 前缀及 sideEffectFree 的失败事实。提示不暴露用户目录、命令或其他会话身份。终端帮助解释共享占用；确认停止但未结算时页面动态提示“结算并关闭”，不恢复长期占屏静态说明。

## Alternatives considered

不采用删除全局锁、让 JS/Bash 与未知终端同时运行、根据超时或 UI 缺记录清空 owner。这些方案会把未确认停止的执行当作结束，存在重复副作用和共享文件并发风险。本次保留原互斥，在可信启动事务内精确区分未提交与已尝试提交。

## Regression verification

九项新增 TerminalStartTransaction JVM 用例覆盖旧反例、修复后双语言准入、未知提交保留、成功后互斥、拒绝回滚、二级终端、其他 owner、存储提交后异常和细分诊断。原 ExecutionOwnership/NativeJavascriptOwnership 测试保留。完整主机和设备编译结果随 [HXA-236 后续验证](../evidence/development/hxa236-context-progress-2026-10-01.md)记账。本轮设备与真实服务 not requested，不宣称用户手机已复测通过；没有推送、发布或安装新 APK。

## Residual risk

| 当前事实 | 正确处理 |
| --- | --- |
| 手动终端仍在运行，即使只是等待输入；返回上一页只是 detach | 用户不再需要时停止该终端，确认停止后结算；两个终端分别处理 |
| 终端已停止但尚未结算、后台 Job 终态尚未收取 | 原身份结算/collect；执行结束不等于已经导入并释放占用 |
| native JS/PRoot 或 Binder 仍未真实退出 | 保留物理执行窗口；超时回执不代替退出证明 |
| 原执行处于 UNKNOWN、提交回执丢失或进程状态无法证明 | 走现有受控恢复/核查路径；不直接删 owner 文件或强制解锁 |

本修复防止新的确定未提交泄漏；对 v0.0.4 已留下且缺少“未提交”证据的旧占用，不能仅凭不存在 UI 行或应用重开安全清除。不要建议清除应用数据、卸载、强制删除持久 owner 或把重启设备本身当成已完成全部结算。需要进一步定位时，应取得当前 owner 分类、原会话/Job 回执及退出/恢复事实，不收集脚本正文和凭据。

## Related records

- [HXA-236](../development/tasks/HXA-236.md)：本轮 J1 收尾与执行占用追加修复。
- [实现与验证](../evidence/development/hxa236-context-progress-2026-10-01.md)：主机命令、源码身份与设备未请求边界。
- [终端与 Job 决策](../adr/runtime/002-terminal-and-jobs.md)：原执行身份、停止证明与结算，不以 UI/超时替代。
