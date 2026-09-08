# Bug Fix: 执行中工具状态与 JavaScript 取消结果不一致

Status: fixed
Date: 2026-09-06
Related HXA: HXA-035, HXA-037, HXA-053
Affected modules: app, tools:framework, runtime:quickjs

## Problem

真实模型 JavaScript 取消评测发现：执行器已经运行，ToolCall 仍为 PENDING 或 AWAITING_APPROVAL；
用户 Stop 后 Turn 为 CANCELLED，JavaScript 的中断却进入普通 TOOL_FAILED 路径，
没有被识别为稳定的 CANCELLED_AFTER_START。

## Impact

运行中时间线和持久化恢复状态不准确。进程中断可能无法把已运行的工具识别为中断待恢复；
用户主动取消被错误归因为普通 JavaScript 失败。

## Root cause

ChatService 只持久化排队、等待批准和最终结果，缺少实际执行开始时的 RUNNING 更新。
QuickJS 服务对运行中 interrupt 返回 INTERRUPTED，工具适配层没有结合调用方的实时取消信号，
直接将其映射为普通失败。

## Fix and invariants

Dispatcher 接受可信调用方的执行开始持久化回调，在批准消费后、执行器进入前调用。
ChatService 在该回调中写 RUNNING 并更新已有时间线，保留批准卡。回调失败时不执行工具。
拒绝、开始前取消不调用该回调；有限技术重试仍逐次经过同一执行边界。

仅在 QuickJS 返回 INTERRUPTED 且调用方取消信号为真时返回工具执行层 Cancelled，
由既有 Dispatcher 记录 CANCELLED_AFTER_START。已开始的调用仍保留框架的副作用未知语义，
不会把它冒充成“执行前取消”；持久化 ToolCall 的 FAILED 本身不是本次缺陷。
没有用户取消的 INTERRUPTED 仍为失败；没有增加重试、工具权限或批准继承。

## Alternatives considered

没有在排队或批准点击时提前标记 RUNNING，因为这些时刻都不证明调用已经进入执行阶段。
也没有把所有 INTERRUPTED 一概当成用户取消。

## Regression verification

- API 34 真实 App pipeline 的阻塞执行反例：执行器已进入时实际 PENDING，期望 RUNNING，红测已保存。
- JavaScript 映射反例：后端接收用户取消后返回 INTERRUPTED，期望工具 CANCELLED，红测已保存。
- 新增回调顺序、拒绝/取消不启动、持久化失败阻止副作用的 JVM 测试。
- 修复后的框架/QuickJS JVM 测试与 API 34 完整 ToolSchedulerDeviceTest（9 项）通过。
- 真实模型 JavaScript 4 项固定评测通过，取消项验证 CANCELLED_AFTER_START，
  不把它错误要求成仅适用于执行前取消的 ToolCall CANCELLED。

## Residual risk

RUNNING 证明平台已经进入执行边界，不能证明外部副作用完成；中断恢复仍需原有核验。
真机、物理 Root 验收不在本次范围内。

## Related records

- [M10 收尾跟进](../development/m10-closure-followup.md)
- [HXA-037](../completion-records/HXA-037.md)
- [HXA-053](../completion-records/HXA-053.md)
