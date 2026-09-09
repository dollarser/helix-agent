# ADR-0038: 长 Turn 的步骤边界压缩

Status: accepted
Date: 2026-09-09
HXA: HXA-176
Deciders: Project owner（明确授权优化长 Turn、压缩收益、计量、摘要质量和失败恢复，并核实 Goal 连续执行）
Supersedes: [ADR-0037](0037-context-window-and-compaction.md)
Superseded by: none

## Context

完整 Turn 保护导致单次长工具循环无法释放上下文；摘要长度和格式检查不足以保证释放空间。所有者已明确授权上述优化。

## Decision

保留 ADR-0037 的 Provider 配置、无工具摘要、原历史不删除、原子 checkpoint 和预算边界。在普通完整历史压缩之外，允许压缩当前 Turn 已完成的较早工具步骤；工具调用与全部结果必须整组，当前用户输入及最新完整工具步骤保留。checkpoint 增加可选 preservedMessageIds，缺失按旧格式处理，支持覆盖边界之前的当前用户原文保留。

每次模型调用前同步检查，不依赖空闲或 Turn 间延迟。新 Turn 尚无可压缩步骤时，允许处理最近上一 Turn 的较早已结算步骤，保留该轮最新步骤与用户原文。结合当前 Turn 最新实际输入和局部增量估算压力，预留协议/图片余量。摘要输出基础额度 2048，较大请求最多 4096，仍受配置和窗口约束；探测确认支持推理的 Provider 使用 LOW 摘要，否则保持 OFF 默认协议行为。要求结构化连续工作笔记。发布前检查实际估算收益；每个原任务调用间限制摘要次数，无收益不反复摘要。

摘要失败保留原历史并如实关闭调用；仅对无工具、无副作用的可恢复摘要失败做一次预算内重试。原上下文仍满足输入/窗口/消息硬限制时允许跳过本次自动压缩继续，否则明确返回可恢复的容量限制。取消、拒绝及预算不足不重试，不创造 Goal wake 或放宽审批。

ADR-0004 的显式继续、暂停、Goal 累计预算和 crash 不重放保持不变。正常 Goal Turn 完成仍暂停；一个 Turn 内所有模型步骤以及显式下一 Turn 都进入同一检查点。

## Alternatives considered

延迟到 Turn 结束压缩不能处理长 Turn；删除工具结果破坏配对与证据；通过新增自动 Goal wake 回避长 Turn 会改变用户未请求的继续语义。保留当前输入 ID 比保留全部历史用户消息更节省空间。

## Consequences

原消息仍完整保留，checkpoint 支持稀疏保留但模型请求始终按原始顺序重建。摘要质量仍有模型依赖，需多轮压缩和约束保持实测；不可分割的巨大输入或最新工具批次仍可能超限。

## Verification

HXA-176 覆盖长 Turn 工具配对、缺失结果不压缩、旧 checkpoint、重复压缩、收益不足/失败恢复、最新 usage、取消/预算/重启、Goal 连续步骤和下一 Turn，以及真实小模型多轮质量评测。

## References

- [ADR-0037](0037-context-window-and-compaction.md)
- [ADR-0004](0004-goal-run-wake-budget-semantics.md)

## Reconsider when

需要提供模型主动回查原始会话工具、原生服务端 compaction 或改变 Goal 自动 wake 语义时另立决策。
