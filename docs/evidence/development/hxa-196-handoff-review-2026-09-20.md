# HXA-196 并行交接复核与收敛

日期：2026-09-20。复核对象为所有者提供的另一模型 walkthrough，以及 main 工作区未提交的 `LinuxDetachedTools` 实现。结论基于当前源码，不把交付陈述或新增测试文件视为执行通过证据。

## 当前落点

- main HEAD 为 `5bfce200`；交接修改尚未提交。交接证据文档写的基线为 `32de8e8d`，不足以证明当前工作区制品的来源。
- 本次延续工作树为 `codex/hxa-196-product`，复核基线 `111eb5bc`。已有 `code.linux.job.start/status/cancel/collect`、持久执行占用、Goal 租期、Tasks 用户操作及模型循环验收；不再并行注册另一套后台工具。
- main 的修改及原有 REVIEW 文件原样保留。本次没有替换、删除、stash 或提交他方内容。

## 不直接采纳的差异

| 交接实现 | 源码问题与影响 | 收敛选择 |
| --- | --- | --- |
| `LinuxDetachedTools.statusDescriptor` 声明 READ_ONLY，`createStatusExecutor` 却解压并调用 `persistVerifiedResult` | 状态读取与宿主写入混合，效果分类不能表达实际副作用；查询不应隐式结算产物 | 维持只读 status，导入及结算由 collect 承担 |
| `createStatusExecutor` 捕获所有导入异常后仍返回 Completed/SUCCEEDED；存在 result.txt 即设置 outputImported | 执行成功不证明导入成功，回执可能掩盖验证或持久化失败 | 保留现有独立执行终态/宿主待结算状态；失败保留原结果并允许重试收取 |
| `createCancelExecutor` 在 `reply.record == null` 时默认 CANCELLED，并无条件输出 cancelled=true | 无回执不能证明已停止，超时或 Binder 故障可能被报告成取消成功 | 原绑定控制返回不确定/失败，只有 Runtime 终态可确认取消 |
| start 返回后没有持久 ExecutionOwnership 或 Goal prepare/reject/settle 接线 | 工具槽位释放后仍有后台副作用；不能据此证明跨 Turn 互斥和 Goal 预算结算 | 继续使用已验证的 DetachedJobLaunch/Collection 与 GoalDetachedBudget |
| ACCEPTED 投影为 RUNNING | 提交获接受与实际执行/宿主结算不是同一个事实 | 保留 SUBMITTED、终态及 settlementPending 独立投影 |

交接的 argv/script、环境屏蔽、会话绑定与变体边界思路和当前实现一致，可作为检查项；无需复制另一套工具代码。交接所列主机/制品验证不能替代设备 instrumentation：没有随交接提供匹配的设备执行命令、非零结果、API/PID 和制品哈希，不认定其设备闭环已通过。本次也未重新运行 main 的未提交实现。

## 接续工作

继续在现有四工具链路完成 HXA-196。已补原结果完成但未导入时的真实主进程死亡恢复，最新证据见 [196 任务记录](../../development/tasks/HXA-196.md)。其后仍需运行中主进程/Runtime 死亡、不确定结果处置与物理设备 G4；不能把交接中的“只剩真机”作为任务关闭依据。
