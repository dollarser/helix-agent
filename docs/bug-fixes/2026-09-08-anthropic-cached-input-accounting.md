# Bug Fix: Anthropic 缓存输入 token 漏记

Status: fixed
Date: 2026-09-08
Related HXA: HXA-102
Affected modules: provider:anthropic（影响共享 ModelEvent usage 的 Turn/Goal 计账）

## Problem

真实 Goal 完成矩阵中，API 29/36 的 Anthropic Messages 两次模型调用仅记录 769/834 token，而同模型其他协议约 19,000。完成状态、真实文件和审批流程通过，但计量差异不能视为正确预算验收。

## Impact

缓存命中时 Goal/Turn 少计输入 token，正常结算会释放过多 token reservation，后续调用可能超过用户设置的累计 token 预算。此预算衡量处理的 token 数，不是缓存折扣后的费用。

## Root cause

AnthropicStreamDecoder 只读取 message_start.usage.input_tokens，漏掉 cache_creation_input_tokens 和 cache_read_input_tokens。官方协议的完整输入量是三者相加，缓存写入的细分对象不能再次重复累加。见 [Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)。本地 SGLang 的受控 SSE 请求确实返回 cache_read_input_tokens，排除了单纯 UI 格式差异。

## Fix and invariants

AnthropicUsage 统一计算完整输入量并交给原 ModelEvent.Usage 和共享 Goal/Turn 计账。缓存字段缺省按零，基础输入缺失或任一字段非法则保持未知、走既有估算；非负整数相加溢出饱和到 Long.MAX_VALUE，不回绕。只读取顶层聚合缓存计数，不重复计算细分字段，不调整既有预算/Continue 状态语义。

## Alternatives considered

- 继续使用未缓存输入数：混淆计量与计费，保留预算漏洞，拒绝。
- 修改 Goal 特判 Anthropic：会使普通 Turn 与 Goal 的同一调用收费不一致，拒绝。
- 以估算固定替换所有服务 usage：丢失合法精确计量，拒绝；仅缺失/非法时保留原回退路径。

## Regression verification

JDK 17，`./gradlew :provider:anthropic:test :core:agent:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest -I build/main-verification/force-tests.gradle --max-workers=1`（既有 Connector 环境）强制执行 78/184/308/326，共 896 项，0 失败/错误/跳过。新增 AnthropicUsageTest 覆盖缓存读/写、全缓存、未缓存、细分不重复、畸形字段、溢出及真实 SSE decoder 终态 Usage。

Developer App/test APK、Spotless、Detekt 通过。API 29/36 的 GoalRealCompletionUiTest 使用本地 SGLang Qwen3.8-27B、Anthropic Messages，真实 UI 创建/绑定/Continue/审批写入/完成各 1 项通过；仍为两次模型调用/一次工具调用，token 变为 19,067/19,107。首轮返回数量静态门禁失败修复后通过，日志保留。

证据：`build/main-verification/anthropic-cache-usage-build-fixed.log`、`anthropic-cache-usage-host.log`、`goal-real-completion/cache-fixed.json`、`goal-real-completion/anthropic-tools-usage-controlled.json`。

## Residual risk

本次真实服务为本地 SGLang 兼容协议，不是 Anthropic 官方付费服务或所有缓存策略验收。历史错误计数不凭推断回写；历史记录缺少原始缓存 usage 时无法精确补账。证据流程进程终止与完整 main 回归仍由 HXA-102/合并验证跟进。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [main 验证报告](../development/main-merged-verification.md)
