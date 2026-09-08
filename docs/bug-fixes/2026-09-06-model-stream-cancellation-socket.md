# Bug Fix: 模型流取消后仍阻塞等待网络读取

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: provider/api, app device tests

## Problem

本地 HTTP fixture 经生产连接探测后保持 SSE 正文未结束。真实 ChatService 发起 Goal 模型请求，分别点击服务 Stop 或耗尽单次运行时限；原实现两项均未在 10 秒内将 Turn 收为终态。失败位于已收到模型请求之后，不是连接探测或发送门禁。

## Impact

用户停止和 Goal 时限结束后，模型网络请求仍可能占用连接并拖延 run/预算结算；界面无法及时进入终态。

## Root cause

OkHttpWireClient.open 使用阻塞 execute，正文直接阻塞 source.read。取消协程没有调用 OkHttp Call.cancel；无下一段数据时，只能等待网络读超时（默认 120 秒）。这会拖延用户停止和 Goal 时限结束。原实现注释也明确依赖下一 chunk 或读超时退出，与及时取消要求不符。

## Fix and invariants

响应头改为 enqueue + suspendCancellableCoroutine，等待响应头期间取消直接关闭 Call；响应已到达但协程未接收时，取消回调关闭未交付的 body。每次阻塞正文读取也绑定 Call.cancel；读取回调及 Flow emission 保持在原收集协程，不新增上下文跳转。保留正文大小上限、协议解码、请求配置及凭据不记录约束。

## Alternatives considered

缩短统一读超时会影响正常慢模型，且仍不能及时响应取消。只取消协程不能打断阻塞 socket read。将整个正文回调搬入另一协程会破坏 Flow 上下文要求。因此在传输资源层执行取消，并保持消费回调上下文。

## Regression verification

新增 JVM 真实 socket 回归分别在等待响应头、已收到部分正文时取消；要求 2 秒内完成取消并由服务端观察连接 EOF。API 34 生产 ChatService/OkHttp/SSE/Room 回归：Stop 后 Goal CANCELLED，单次时限后 PAUSED/BUDGET_EXHAUSTED；run 关闭、模型计数为 1、时间已计账、无未结算 reservation/ToolCall，socket EOF 且无重复模型请求。修复后 2/2，原实现 2/2 超时失败。

`./gradlew :provider:api:test :provider:openai-chat:test :provider:openai-responses:test :provider:anthropic:test :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:testConsumerDebugUnitTest :app:lintConsumerDebug spotlessCheck detekt --continue`：Provider API 89、Chat 56、Responses 58、Anthropic 73 全通过；consumer JVM 291 项中 288 通过/3 项既有跳过，consumer Lint、APK、Spotless 通过。Detekt 仍既有 53 项，联合命令 exit 1。

证据：`build/main-verification/goal-model-cancel-order-api34.log`（修复前失败）、`goal-model-cancel-wire-api34.log`（修复后 2/2）、`goal-model-cancel-wire-gates.log` 和 `goal-model-cancel-result.json`。更早的 `goal-model-cancel-api34.log` 是 fixture 在探测前创建绑定会话的准备错误，不能用作生产缺陷证据。

## Residual risk

本次设备取消使用真实 HTTP 连接与本地脚本模型回复，不是真实模型服务，也不是模型流中实际 SIGKILL。其他协议设备中断、进程恢复、具体工具后端中断、其他 API 和全量矩阵仍待验证；真机与长稳未运行。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
