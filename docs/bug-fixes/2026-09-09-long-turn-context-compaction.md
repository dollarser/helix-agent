# Bug Fix: 长 Turn 无法释放已结算步骤的上下文

Status: fixed
Date: 2026-09-09
Related HXA: HXA-176
Affected modules: app/chat

## Problem

HXA-174 保护整个当前 Turn，只能摘要更早轮次。一个长工具循环即使拥有大量已结算结果，也无法回收当前 Turn 的上下文。最新实际 usage 又只参考较早 Turn，可能低估当前步骤压力。

## Impact

长 Chat/Act/Goal 工具循环可在 Turn 结束前触达模型容量限制。增加 Turn 间延迟不能解决；立即 Continue 本身也不是竞争条件，因为模型循环同步检查请求。旧 Goal 结算未把上下文容量错误归入可继续的 Turn 限制。

## Root cause

替换范围只有连续 sequence 边界，无法同时摘要当前 Turn 较早工具结果并保留边界之前的当前用户输入。摘要完整性检查不保证实际减小请求；失败重试必须使用 ModelEvent 的具体错误码与 retryable 标记，不能混用上层 NETWORK 分类。

## Fix and invariants

- checkpoint 可选 preservedMessageIds 与覆盖边界原子持久化；原消息和附件保持不变，缺少新字段的旧记录兼容。
- 优先处理较早历史，再处理当前 Turn 已结算步骤；保留当前用户输入、最新完整批次和未结算调用，工具调用与全部结果不得拆开。
- 新轮第一次请求没有已结算步骤时，可压缩最近上一轮较早步骤；不得因保留整个上一轮而完全失去压缩入口。
- 每次模型调用前检查，包括同 Turn 下一步和显式下一 Turn；不依赖空闲定时器、不打断模型流、不改变 Goal wake。
- 结合当前实际 usage 校准与输出预留；有收益才发布，每个普通调用间最多两次摘要尝试。
- 暂态 Provider 错误必须 retryable 且属于允许类别；摘要无收益/无效可重试一次。取消、拒绝、认证、非可重试服务错误、预算不足不得通过该路径重试。
- 重试同样记账；原文超硬限制时不得以 fallback 发送。Goal 容量限制进入 PAUSED，不判完成，不扩展预算或权限。
- 摘要基础输出额度保持 2048；确认推理能力后明确使用 LOW。OFF 省略推理参数不等于服务关闭推理，输出长度终止仍必须拒绝发布。

## Alternatives considered

仅在 Turn 结束压缩无法覆盖本缺陷。删除旧工具消息会破坏配对与审计；只为 Goal 增加等待时间或自动 wake 既不解决根因，也改变现有继续语义。一次必须压到只剩一个批次不成立：摘要请求也有输入上限，需允许多次有界推进。

## Regression verification

LongTurnCompactionDeviceTest 覆盖长 Goal 步骤、立即下一 Turn、未完成批次、连续三次 checkpoint、数据库重开、实际 usage、无收益回退与硬限制、非可重试错误。ChatCompactionFlowDeviceTest 通过真实 ChatService 和 loopback HTTP 验证摘要后继续、503 重试/账本、Goal 结算和取消/预算。ContextCompactionDeviceTest 保留旧 checkpoint 与恢复验收。具体命令及最终结果见 HXA-176 完成记录。

## Residual risk

token 仍为保守估算，摘要事实保真仍依赖模型。当前用户输入、最新完整批次或工具目录过大时仍可能不可压缩。重排历史的 retry 请求若无法精确映射来源则拒绝规划，不猜测消息身份。该修复不承诺任意长上下文或多天质量不衰减。

## Related records

- [ADR-0038](../adr/0038-step-boundary-context-compaction.md)
- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [原实现 HXA-174](../completion-records/HXA-174.md)
