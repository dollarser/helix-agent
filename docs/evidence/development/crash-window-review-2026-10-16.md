# 发布前崩溃窗口与结果结算审查

日期：2026-10-16。Owner 要求解释三个崩溃窗口并在发版前修复当前可复现缺陷，允许有界 ARM64 模拟器验证，真实模型按需；不跑长稳、不改服务器、不推送发布或启动整体重构。起点为干净 `main / 91bb9e35`，原 ARM64 候选保留。

## 窗口与当前证据

原文归 [HXA-287 联合检查点](../../development/tasks/HXA-287.md#原共同集成检查点)、[HXA-288](../../development/tasks/HXA-288.md) 和 [Agent ADR](../../adr/agent/001-turn-coordination.md)。它们是执行前、效果后提交前、提交后通知前的故障注入时点；不是三个已知 Bug，也不是只约束未来版本。

| 窗口 | 当前路径及用户后果 | 本轮审查分类 |
| --- | --- | --- |
| 开始屏障前 | Dispatcher 的 `onExecutionStarting` 先提交 RUNNING，再调用 executor；PENDING/AWAITING_APPROVAL 恢复为开始前取消 | 已有机制；写入失败零执行有 JVM 回归，本轮增加真实 Room 故障/重开验证。屏障提交后、executor 调用前死亡则保守不确定 |
| 效果后、结果提交前 | `ToolSettlementWriter` 将结果、验证位、调用状态和 Goal 计费原子提交；外部文件/点击不在事务内 | 已有机制；SQL 失败回滚及重开后不重放验证。实际外部效果不假称已回滚 |
| 结果提交后、通知前 | 已提交结果应保留，旧 Turn 不复活；界面可以重建 | 本轮复现通知异常仍从 `ChatToolSettlement` 向执行循环传播；这是当前待修缺陷，不留给 287 |

邻近入口还确认 `ChatToolCalls.dispatchToolCall` 未沿批量入口读取 `ExecutionFailed.requiresReview`，把执行器明确声明的不确定结果保存成 FAILED。生产模型批量入口已有判断，不能夸大为所有模型调用均丢失核查状态；直接入口仍必须一致。

## 修复前复现

新增定向测试在未修改生产源码的 Standard/API36 ARM64 自有只读模拟器运行：9 项、2 项失败。失败分别为通知抛出 `injected projection failure after commit`，以及预期 NEEDS_REVIEW 实际 FAILED；其余事务失败、开始屏障、重复恢复检查通过。原始输出归忽略目录 `build/crash-window-review-2026-10-16/before-consumer/`，不修改期望或删除失败记录。

## 修复与验证进度

修复限制于具名调用结果分类和可丢的界面投影：直接入口复用原批量核查判断；持久提交仍先执行且异常向上传播，界面刷新异常单独记录，不改写执行结果；批次先记录原调用结算再通知 UI。没有增加重放、第二账本、全局锁或新执行层。

扩展到 35 项后，首批另有 4 项过时夹具失败：3 项仍断言 2026-10-07 已由 ADR-GOAL-001 撤销的累计模型/时间额度，1 项用空工具数组进入新模型步骤。前者改验用量仍持久、达到旧字段值不误阻断、重开只结算一次；后者提供真实非空调用，继续验证下一 ModelCall 与步骤先持久化。没有恢复旧限制、删除测试或跳过断言。

修复后 API36 ARM64 双渠道定向各 **35/35**、零跳过，包含 ToolSettlementRecoveryDeviceTest、ExecutionEffectBoundaryDeviceTest、ProcessRecoveryTest、TurnCommitStoreDeviceTest 和 TurnCancellationRaceDeviceTest。它们使用真实 Room/事务及受控外部文件效果；重开数据库不是实际杀进程，后者归下面 R8 检查。第一次连续复用端口时旧 ADB 项尚未消失，runner 在启动前拒绝；后续换空闲端口完成 Advanced，没有借用其他实例。

完整主机和受影响 R8 路径 pending；生产源码变化后另冻同源 ARM64 候选，旧制品原样保留。本段将在实际执行后更新，不以编译代替运行。

## 设计基准

不兼容旧版是可直接替换的许可；目标是当前职责合理、未来扩展/集成成本低和有实战依据。删除 ADR 的旧 v29/review-resume 兼容条款，不删除当前授权、取消、幂等与同版本恢复要求。Codex 的 thread/turn/取消身份实践仍按[既有研究](../../research/topics/tool-and-plugin-competitive-review-2026-10-15.md#10-2026-10-16-预览后重构与能力复核)采用；[MCP 取消规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation)提示取消与迟到结果的竞态，[SQLite 原子提交说明](https://www.sqlite.org/atomiccommit.html)只证明数据库事务边界，不覆盖外部工具副作用。本轮读取一手资料，不复制参考实现。
