# Bug Fix: 压缩摘要预算误判与模型窗口显示不一致

Status: fixed
Date: 2026-09-10
Related HXA: HXA-190

## Problem

手动压缩返回 TOKEN_BUDGET_LIMIT；Spark 圆环显示 200k。

## Impact

摘要已经消耗模型调用但不能提交 checkpoint；UI 展示的占用率和实际按模型判定的压力不一致。

## Root cause

只读真机调用记录显示失败调用输入 10890、输出 2351 tokens。摘要计划把目标正文长度同时用作 maxOutputTokens；订阅不接受该协议字段，实际返回超过这个较小目标时被本地结算拒绝。不能将该失败等同于整个上下文满或网络故障。

手机已存的精确模型目录包含 Spark context=128000。请求路径合并该元数据，ChatContextProjection 却直接读取模型设置表，缺记录时使用 200000。Provider 默认能力快照的 272000 也不能代替当前所选模型的窗口。

## Fix and invariants

SummaryOutputBudget 分离目标摘要长度与最多 4096 的调用额度，后者仍受用户输出额度和模型窗口四分之一约束；输入规划为完整额度留空间。实际 token 继续经过原 Turn/Goal 结算，未跳过超限、取消或摘要收益校验，未删除旧历史或发布失败摘要。

圆环与 ProviderService 共用 withDetectedWindow 合并规则：精确模型目录覆盖旧服务端窗口，手动值作为更小上限，未知时保留既有设置/默认值；没有硬编码 Spark 型号或 128k。

## Alternatives considered

跳过所有压缩预算会破坏用户额度；把 Spark 常量写进 UI 会再次与目录漂移。按现有额度为摘要及推理预留余量，并统一模型元数据合并即可修复本次错误。

## Regression verification

脚本 scripts/debug/2026-09-10/inspect-compaction-usage.py 只读调用计量及非凭据模型元数据，不输出消息正文。主机脚本 scripts/debug/2026-09-10/verify-tool-context-fixes.py 覆盖摘要实际计量回归、小预算/小窗口、Turn 原有限制、模型窗口切换和工具结果投影，并构建 developer APK。执行结果见 status.md；没有借用 Claude 的模拟器或发送新的真实模型任务。

## Residual risk

模型仍可能超过用户真正设置的输出/累计预算，这时拒绝有效；更大摘要仍需满足收益和结构校验。端到端压缩质量由所有者在原会话人工确认。

## Related records

- [工具返回梳理](../evidence/development/builtin-tool-result-review-2026-09-10.md)
- [ADR-AGENT-002](../adr/agent/002-context-compaction.md)
- [ADR-PROVIDER-001](../adr/provider/001-models-and-connection.md)
