# Bug Fix: Goal 保存吞掉取消且重试遗留错误

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: app

## Problem

GoalEditor 与 GoalReminderControls 将 IllegalStateException 转为本地化错误，但 CancellationException 也属于该类型。保存回归中主动抛出取消后，操作 Job 正常结束而不是取消，修复前断言 Save cancellation must propagate 失败。另从源码确认保存开始时未重置 failed，失败后成功重试会保留旧错误。

## Impact

离开界面或取消操作时，协程取消语义被吞掉并可能写入不应显示的失败状态；保存重试成功后仍显示旧错误，用户无法判断操作是否完成。

## Root cause

错误映射先捕获通用状态异常，缺少取消的显式重新抛出。保存的 saving 状态在 finally 复位，但 failed 没有在新尝试开始时复位。

## Fix and invariants

保存与提醒首先重新抛出 CancellationException。保存的验证/状态异常处理独立成函数，取消不会调用失败回调；每次显式保存先清除旧失败状态。保留输入校验、按钮忙碌保护及既有持久服务调用，未改变 Goal/run/预算或审批规则。错误文本增加稳定测试标记。

## Alternatives considered

扩大异常捕获会进一步吞掉取消；只隐藏错误文本不能恢复协程语义。现保留窄异常映射和取消传播，并仅在用户发起新尝试时清除旧错误。

## Regression verification

新增真实 Compose 回归断言取消后保存 Job.isCancelled 且没有错误状态；状态错误可见后重试成功会清除旧错误。修复前取消断言确实失败。另一项早期断言使用应用 Context 的翻译文本定位测试 Activity，未能命中错误文本，随后改为稳定状态标记；这项初始定位失败不算生产缺陷证据。

最终 GoalEditor、提醒/预算/删除 UI、删除协调器、实际 HTTP 模型取消组合 API 34 11/11。`./gradlew :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:testConsumerDebugUnitTest :app:lintConsumerDebug spotlessCheck detekt --continue`：consumer/测试 APK、JVM 288 通过/3 项既有条件跳过、consumer Lint 与 Spotless 通过；Detekt 仍既有 53 项，联合命令 exit 1。

证据：`build/main-verification/goal-editor-cancel-before-api34.log`、`goal-editor-cancel-final-api34.log`、`goal-editor-cancel-final-gates.log`、`goal-editor-cancel-result.json`。

## Residual risk

取消注入回归直接覆盖保存操作；提醒分支按相同异常继承关系修正，并执行正常提醒 UI 回归，不据此声称已覆盖所有提醒取消竞态。未执行所有 API、真实模型界面、模型 SIGKILL、真机或长稳。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
