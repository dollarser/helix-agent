# ADR-0039: 后台任务回收与 Goal 阻塞、暂停分离

Status: accepted
Date: 2026-09-09
HXA: HXA-177
Deciders: Project owner（明确要求实现非阻塞后台任务列表、结果回收与 blocked/暂停区分）
Supersedes: none
Superseded by: none

## Context

现有执行器按会话独立运行，但缺少跨会话任务入口；前台服务错误地观察打开会话。ADR-0004 的暂停混合了可继续与必须先解决依赖的情形。

## Decision

部分替代 ADR-0004 的等待语义；保留预算记账、显式 Continue、证据完成与不重放契约。

- 任务以持久 Turn 为身份，列表可查看不同会话的运行、审批、结束与中断；打开列表、切换会话不取消任务。结果回收只确认用户已查看持久结果，不重新调用模型、不自动发送到其他会话。
- 回收时间与用户暂停请求落盘。取消/暂停针对精确 turn ID，旧列表操作不能取消该会话的新任务。进程重启不重放未知副作用。
- 前台服务观察全部活跃任务，任一任务正在传输即保持服务；等待审批和无任务时停止。仍受 Android 生命周期约束，不增加常驻或定时模型唤醒。
- BLOCKED 是非终态：缺预算、未知副作用、缺少可验收证据或其他必须先修复的依赖；不能直接 Continue。记录原因，显式重新检查通过后转为 PAUSED，之后才可 Continue。
- PAUSED 是可恢复的停泊，包括用户手动暂停与进程中断。用户暂停先持久请求，再取消当前执行；所有副作用先结算，存在未知结果时 BLOCKED 优先。
- 普通 Turn 结束但仍有可执行步骤继续保留显式 Continue；本次不增加自动跨 Turn 唤醒或生产子 Agent。
- 模型自称完成不改变 Goal；全部用户绑定条件验证通过且无未决副作用，仍由 Harness 完成。

## Alternatives considered

仅重命名 UI 无法保证恢复与 Continue 门控；另造任务执行器会重复现有按会话执行、审批与记账。采用现有持久 Turn 投影和最小回收元数据。

## Consequences

新增 BLOCKED 状态与数据库迁移，旧终态和证据不改写。任务回收保留原消息与工具结果。未回收结果不受最近历史数量限制；已回收列表保留最近任务窗口，较早正文仍在原会话。后台运行不承诺 Android 强杀后的连续执行。

## Verification

已核实按会话 admission、Goal 结算与前台服务接线。实施验收要求：跨会话互不阻塞、精确取消、回收幂等/重启、暂停恢复、阻塞不能直接继续、预算与证据不绕过，以及独立模拟器验证。受影响 JVM 1140 项（8 个既有外部 skip）、独立模拟器双版本/迁移合计 160/160 通过，详见 [完成记录](../completion-records/HXA-177.md)。

## Reconsider when

需要自动跨 Turn 调度、生产子 Agent、跨会话自动注入结果或任意后台启动时单独扩展决策。

## References

- [原 Goal 决策](0004-goal-run-wake-budget-semantics.md)
- [Goal 完成证据](0028-goal-criterion-verification-bindings.md)
- [Runtime 生命周期](0007-companion-runtime-lifecycle.md)
- [Claude Code 子 Agent](https://code.claude.com/docs/en/sub-agents)
- [DeepSeek Agent 生命周期](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/agent/README.md)

## Goal 指南采纳

已阅读所有者提供的 [Goal 使用参考](../references/goal-feature-guide.md)，并核对 DeepSeek 当前官方 [goal-round-driver 文档](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/goal/goal-round-driver/README.md)。用户指南是指定历史版本的个人参考，不冒充上游规范或 Helix 的实现证据。采纳生命周期与执行开关分离、用户暂停中止当前轮、恢复不自动重放、稳定阻塞原因及执行前复查。保留 Helix 的证据验收；不采用模型独立自证完成。参考文档的三轮门槛针对主观阻塞报告；Helix 本次没有新增模型报告 blocked 的工具，不把此门槛应用于已证实的预算/容量/未知副作用。此次仍不增加自动续跑驱动，原显式 Continue 约束保留；自动调度需要另行定义运行激活与轮次准入。

完成与绑定部分由 [ADR-0040](0040-model-judged-goal-completion.md) 替代；其余执行/预算/恢复机制保留。
