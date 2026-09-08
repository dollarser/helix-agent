# Bug Fix: 模型输出截断被误记为完成

Status: fixed
Date: 2026-09-06
Related HXA: HXA-035, HXA-099, HXA-100
Affected modules: app

## Problem

真实 Plan 评测中，模型用尽输出预算，仅返回 reasoning，Turn 却记录为 COMPLETED，最终回答为空。

## Impact

用户看见错误的完成状态。包含已闭合工具参数的截断响应也可能继续进入工具循环。

## Root cause

ModelStreamState 忽略 ModelEvent.Completed 的 finishReason，未区分正常结束与 length。
Provider 已正确上报长度耗尽，错误发生在 App 的流状态归并。

## Fix and invariants

length 结束记为 TOKEN_BUDGET_LIMIT，Turn 失败，保留已有文字；不执行该截断响应中的工具。
用户取消仍优先归为取消。没有扩大预算、关闭 reasoning 或将不完整回答作为成功。

## Alternatives considered

没有只增加评测输出预算，因为任何有限预算仍可能耗尽，产品必须正确处理该终止原因。

## Regression verification

新增空回答/部分文字截断、已闭合工具调用随后截断的两项反例，修复前失败，修复后通过。
API 34 真实三协议 Plan 评测通过；规范材料在 Plan turn 前准备，未扩大 Plan 工具权限。
最新源全量 JVM 回归另见收尾跟进记录。

## Residual risk

Provider 仍须正确映射协议终止原因；该修复不为未报告完成事件的任意流假定正常结束。

## Related records

- [M10 收尾跟进](../development/m10-closure-followup.md)
- [HXA-100](../completion-records/HXA-100.md)
