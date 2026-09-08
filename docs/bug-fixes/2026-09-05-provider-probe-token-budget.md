# Bug Fix: 连接探测预算截断合法工具调用

Status: fixed
Date: 2026-09-05
Related HXA: HXA-025
Affected modules: provider:api, app

## Problem

SGLang 真实 UI 连接测试偶发在最小工具调用阶段失败；此前 API 29 同一服务通过。

## Impact

推理模型的连接测试可能误报不支持工具，导致配置无法用于聊天。

## Root cause

工具探测默认仅 64 output tokens，思考与 ToolCall 共用预算。独立 Stream 请求前 9 次完成，随后一次 finish_reason=length、completion_tokens=64，证明不依赖 Android UI 或 Helix parser 也能复现。

## Fix and invariants

工具探测默认预算有界调整为 256；截断、不闭合调用、错误和 Refusal 的失败规则不变。普通 Turn 预算没有变化。

## Alternatives considered

没有把 length 当成功，也没有通过重试至成功掩盖失败；未改服务端或关闭模型思考。

## Regression verification

同一独立流式请求在 256-token 预算下 10/10 tool_calls。CapabilityProbeTest.boundedReasoningCanFinishAToolProbeButTruncatedCallsStillFail 验证短预算仍失败、默认预算可完成；两台真实 UI 完整结果见验证报告。

### 2026-09-06 Responses 复核

完整文件评测在 phase 3 复现相同类别问题：文本/视觉探测仍只有 16 tokens。
独立 Responses SSE 在 16 时返回 `status=incomplete`、`max_output_tokens`；256 时完成并返回 ok。
文本/视觉探测预算也调整为 256，保持截断错误失败，增加不足预算与默认预算的回归对照。
实际 Android 全链路结果继续记入 M10 收尾记录。

## Residual risk

模型仍可能拒绝或超出 256 tokens，届时必须如实失败；本测试不是任意模型的支持保证。

## Related records

- [HXA-025](../completion-records/HXA-025.md)
- [main 验证报告](../development/main-merged-verification.md)
