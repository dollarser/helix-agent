# ADR-0040: 模型判断 Goal 完成，移除强制证据绑定

Status: accepted
Date: 2026-09-09
HXA: HXA-178
Deciders: Project owner（明确要求按主流机制实现，完全抛弃旧方案）
Supersedes: [ADR-0028](0028-goal-criterion-verification-bindings.md)
Superseded by: none

## Context

强制绑定规则把开放目标变成预配置验证流程，缺少绑定还阻止继续工作。公开的 DeepSeek Goal 由模型判断证据充分性，Claude Code 的独立完成检查是可配置 hook，并非通用强制验收器。所有者授权替换，而非新增可选旧模式。

## Decision

完全取代 ADR-0028；部分替代 ADR-0004 的 criterion/完成语义和 ADR-0039 的证据绑定阻塞。保留预算、显式 Continue、暂停、恢复、真实 Tool 安全验证与前台服务。

模型通过 goal.report 结构化报告 complete/in_progress/blocked 和依据。报告只归属当前活动 Goal/Turn，经正常 Dispatcher 的 schema、Policy、执行、结果记录路径；没有任意 Goal ID 参数。模型决定语义完成，Harness 不推断自然语言是否满足预设规则，不把裸文本关键词当完成指令。

Harness 在正常 Turn 结算时消费当前轮最后有效报告；取消、用户暂停、未决副作用与预算中止优先。只接受活动 Goal 的报告，不靠工具执行成功本身判断任务完成。没有报告则保持可继续，不因缺少绑定阻塞。模型 blocked 附原因，用户确认依赖已修复并重新检查后可继续。完成理由及报告引用持久保留，明确来源为模型判断。

移除绑定编辑、人工证据选择与独立 Goal verifier 生产路径。历史数据与审计保留兼容读取，不重开已完成目标；旧绑定阻塞迁移为 PAUSED，不启动模型。不会以迁移清除权限、未决副作用或预算约束。

## Alternatives considered

保留严格/普通双模式会留下用户明确要求淘汰的旧方案；按最终文本关键词判断不能区分引用与控制意图。选当前轮结构化报告，不增加额外裁判模型请求。

## Consequences

只填写目标即可创建，补充要求为可选描述。开放目标无需手工绑定，模型也可能误判完成。UI 的完成仅表示模型报告完成，不保证独立认证。工具权限、审批、取消和副作用核实不放宽。自动跨 Turn 驱动及子 Agent 不属于本次完成机制替换。

## Verification

所有者明确授权作为接受依据。实现与主机、双版本、独占 API29/36、旧库迁移、真实 SGLang 和文档验收已完成，见 [HXA-178](../completion-records/HXA-178.md)。模型报告/跨会话/取消/预算/失败/旧绑定迁移、无绑定完成和历史报告隔离均有回归证据。

## Reconsider when

用户要求特定工作流强制执行可配置验收门禁；应另行引入可选 hook，而不是恢复全局强制绑定。

## References

- [旧绑定方案](0028-goal-criterion-verification-bindings.md)
- [run/wake](0004-goal-run-wake-budget-semantics.md)
- [暂停和阻塞](0039-background-results-and-goal-blockers.md)
- [DeepSeek 官方限制](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/goal/goal-round-driver/README.md#known-limitations-and-deferred-work)
- [Claude 完成 hook](https://code.claude.com/docs/en/hooks#taskcompleted)
