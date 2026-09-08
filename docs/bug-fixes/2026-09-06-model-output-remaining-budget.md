# Bug Fix: 模型请求输出未受剩余总预算约束

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: app

## Problem

发送前只检查输入估算，输出上限仍使用单次配置，没有同时扣除已经消费的 token 和本次输入估算。请求可能结束后才发现总预算超限。

## Impact

多次模型调用的同一 Turn，可能在可用总额度不足时仍向 Provider 请求完整输出额度；Goal 生产接线复用这一调用点时也会继承该缺口。

## Root cause

`TurnBudgetTracker.beginCall` 仅返回是否准入，无法把本轮剩余额度绑定到实际 `ModelRequest`。`ChatService` 直接发送原请求。

## Fix and invariants

`prepareCall` 返回准入结果和绑定后的请求。输出上限取原请求上限、单次输出预算、剩余总额度减输入估算三者最小值。没有正输出空间时不消费模型调用次数，且不调用 Provider。ChatService 在真实 stream 调用点使用绑定请求。

完成后仍使用报告用量或既有字节估算核算，拒绝 Provider 超出本次绑定输出上限或累计总额度的结果。此修复不把估算宣称为精确 tokenizer，不实现尚未接通的 Goal 崩溃预算预留。

## Alternatives considered

只在流结束后失败无法约束已经发出的请求；只缩减设置中的单次上限也无法适应工具循环后变化的剩余额度。因此在每次发送前绑定实际请求，保留结束时核算。

## Regression verification

`TurnBudgetTrackerTest` 7/7 通过，包括连续调用、显式更小上限、输入耗尽总额度、拒绝不消费调用次数和 Provider 超额报告。consumer 单元测试共 287 项，284 通过、3 条既有外部条件测试跳过，0 failure/error；consumer Debug Lint、APK 构建、Spotless 通过。日志位于本地 `build/main-verification/goal-call-admission-build.log` 和 `goal-call-admission-gates.log`。

专用 API 34 模拟器 `AttachmentE2eDeviceTest` 16/16 通过、0 skipped，包括实际 wire `max_tokens=19`（总额度 20、输入估算 1）、没有输出空间时 wire 调用数为 0，以及原有附件、工具循环、取消和历史恢复。日志 `goal-call-admission-final-api34.log`，APK hash 见 `goal-call-admission-result.json`。最初两次执行分别因 runner 包名和测试设置违反已有预算校验而失败，失败日志保留；修正测试参数后通过，未放宽产品校验。

## Residual risk

输入使用已有字节估算，无法保证服务端实际 tokenizer 用量；结束时仍核算实际报告。Goal 全生命周期预算持久化、生产入口和固定评测尚未完成。未执行长稳或真机测试。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
