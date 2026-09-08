# Bug Fix: Turn 恢复后模型调用仍显示运行中

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102
Affected modules: app, core/storage

## Problem

实际 HTTP 模型流 SIGKILL 后，原恢复代码可以把 Turn/run 关闭、Goal 暂停并结算预留，却没有关闭 model_calls 中的 RUNNING 记录。新增真实 Room 回归明确失败：expected INTERRUPTED but was RUNNING。旧版本已经恢复过的 INTERRUPTED Turn 同样可能保留这类模型调用。

## Impact

同一次调用的持久状态互相矛盾：外层已经中断，模型调用仍显示运行。诊断及后续状态读取无法正确判断该次请求是否仍活跃。

## Root cause

RecoveryCoordinatorApp 只处理 Turn、ToolCall、Goal/run 与预算预留，没有处理 ModelCall。只在本次活动 Turn 上补写仍会遗漏旧版本留下的孤立运行状态。

## Fix and invariants

在同一个恢复事务中，将所属 Turn 已为 INTERRUPTED 的 RUNNING 模型调用改为 INTERRUPTED。SQL 同时覆盖本次关闭和历史遗留记录；保留 usage/requestId，不修改已完成调用、不重复提交请求、不伪造模型实际用量。仅有记录实际变化时新增一条有界计数审计，重复恢复不重复写入。未修改持久表结构或 Room 版本。

## Alternatives considered

只更新本次恢复计划内的 Turn 会遗漏历史记录；把所有终态调用重写为 INTERRUPTED 会破坏正常完成状态和已知结果。现使用所属 Turn 状态与调用状态的双条件，只修改确实遗留的运行标记。

## Regression verification

ModelCallRecoveryDeviceTest 修复前 1/1 失败，修复后与 ProcessRecoveryTest、GoalUsageReservationsDeviceTest、GoalProcessKillDeviceTest 的默认 control 路径组合 20/20。断言当前及历史记录修复、已完成状态及 usage/requestId 保留、审计幂等。

独立宿主 HTTP fixture 在生产 ChatService 发出模型请求并持久化预算预留后，确认 App PID 9764 再实际 SIGKILL，服务端观察 socket EOF。两次重新启动均断言 Turn、run、ModelCall 为 INTERRUPTED，Goal PAUSED；保守计入 5076 tokens / 5000 ms，模型调用数为 1，无 pending reservation 或 ToolCall。宿主 POST 计数恢复前后均为 4（含探测），其中实际持有的模型请求始终为 1；两轮各有 2 秒恢复后观察窗口，未出现重发。最后恢复原运行控制配置并清理本次 Goal/Provider/marker，保留归档测试会话。

`./gradlew :core:storage:testDebugUnitTest :app:testConsumerDebugUnitTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:lintConsumerDebug spotlessCheck detekt --continue`：storage JVM 73 全通过，consumer JVM 288 通过/3 项既有跳过，consumer Lint、APK、Spotless 通过；Detekt 仍既有 53 项，联合命令 exit 1。

证据：`build/main-verification/model-call-recovery-before-api34.log`、`model-call-recovery-fix-api34.log`、`model-call-recovery-fix-gates.log`、`model-call-recovery-result.json`；真实 kill 的阶段日志/PID/计数/APK hash 在 `build/main-verification/model-stream-kill-fixed-api34/model-kill-result.json`。

## Residual risk

设备请求经过真实 HTTP/OkHttp/SSE 与生产 App 启动恢复，但模型输出由宿主脚本生成，不是真实模型服务验收。首组仅 OpenAI Chat。后续在同一 APK 补齐 Chat/Responses/Anthropic × 等待响应头/部分正文，六组真实 SIGKILL 均通过，矩阵见 `build/main-verification/model-kill-matrix-api34/matrix-result.json`。仍只覆盖 API 34；工具后端、其他网络阶段及完整主分支矩阵继续。测试需要配套 host runner，无参数的通用 instrumentation 会按明确外部 fixture 条件跳过，不能据此算通过。真机与长稳未运行。

## Related records

- [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当前待办](../development/main-optimization-todo.md)
