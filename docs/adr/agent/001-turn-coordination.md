# ADR-AGENT-001: Turn 批次协调与持久结算

Status: accepted
Date: 2026-09-22
HXA: HXA-011, HXA-039, HXA-216
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

Turn、模型调用和工具调用是不同持久化粒度，取消与恢复需要准确结算每个槽位。

## Decision

应用层唯一 TurnCoordinator 管理 Turn/ModelCall 生命周期。一次模型响应的 ToolCalls 为一个 batch，Turn 保存聚合 phase，每个调用独立保存排队、审批、运行、终态或待核查。

仅平台能证明不冲突的只读调用有界并发，模型结果按原调用顺序持久化并回填。每次流开始前更新当前 ModelCall checkpoint，取消/异常读取当前身份，不能误关上一轮调用。

最终 assistant 文本、Turn 终局、仍打开的 ModelCall 终局原子提交；工具步骤的模型结算与 tool-call 消息同事务，全部结果结算后按序回填并创建下一次请求。外部副作用不放数据库事务，事务失败不重放。

用户输入交付按 [ADR-AGENT-008](008-user-input-delivery.md)：Queue 在正常终局释放执行槽后按FIFO交付；Steer 仅在整批工具结果结算后或正常回答终局协调点追加USER。Steer接收与终局提交在同一持久事务边界竞争，接收在先则继续原Turn及预算，终局在先则明确目标过期，不静默转投新Turn。历史追加、消费映射与所绑定Turn/message一起提交；模型请求启动另记，不能把已追加历史或创建ModelCall行当作已经发送。显式停止事务同时停泊旧输入，进程恢复不自动交付。216设计已接受，现行实现迁移及功能证据仍按任务记录。

每个异常槽位保留自身异常与结果；重复 toolCallId 在执行前拒绝。无法证明未发生副作用的执行契约异常进入 NEEDS_REVIEW。Repository 拒绝非法跳转，进程死亡使用明确的 interrupted 结算入口，不另建串行兼容 reducer 决定生产调度。

## Alternatives considered

一个异常代表整批结果会丢失审计事实；所有调用串行并不能替代正确身份和事务；以数据库回滚代表外部副作用回滚不成立。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
