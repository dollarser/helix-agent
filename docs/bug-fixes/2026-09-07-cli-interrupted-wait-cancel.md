# Bug Fix: CLI 等待线程中断未取消 Runtime Job

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102, HXA-110
Affected modules: runtime/cli-client, app developer instrumentation

## Problem

订阅 Provider 通过 runInterruptible 等待 CLI Job。等待线程被中断后，客户端原本只返回 TimedOut 并解绑，没有向原 Job 发送 CANCEL。当另一条连接仍绑定 Runtime 时，Job 会继续保持 RUNNING。

## Impact

调用方取消等待后，Runtime 进程内的工作可能继续占用执行槽及网络资源。这里的 Runtime 是同设备独立 APK，不是远程 Worker。

## Root cause

submitAndAwait 的 InterruptedException 分支只恢复线程中断标志。单连接测试会被最后一次解绑触发的 Service.onDestroy/runner.close 掩盖，不能证明客户端发送了取消请求。

## Fix and invariants

将等待状态机提取为 CliModelJobAwaiter；连接和唯一一次 SUBMIT 保留在 CliModelJobClient。等待中断时通过现有连接向同一 Job 发出一次 CANCEL，在 finally 恢复线程中断标志，然后保留原 TimedOut 结果。查询失败不重发，终态只对账；超时仍取消同一 Job。未引入新 Job、网络重试或权限变化。

## Alternatives considered

依赖最后一次解绑销毁服务无法覆盖其他连接仍然存在的情况；关闭全部 Runtime 连接会影响其他调用方。取消范围应绑定原 Job。

## Regression verification

JVM 新增六项状态机测试，修复前中断取消断言失败。API 34 首轮单连接用例通过，但保留额外观察连接后真实跨进程复现 expected CANCELLED but was RUNNING。两轮日志分别保留，没有用单连接结果替代多连接验收。

修复后 Client JVM 30/30、Developer 300 通过/3 既有跳过，API 34 跨进程 fixture 9/9，模块 Lint/构建/Spotless 通过；根 Detekt 仍有 18 项。完整结果、命令与 APK hash 见 `build/main-verification/cli-await-result.json`。设备测试在持有观察连接时中断提交线程，随后查询原 Job 的 CANCELLED 终态、重复查询一致及对账无结果；finally 清理测试 Job 与连接。

## Residual risk

取消事务仍可能因 Binder 死亡失败；TimedOut 不代表已收到取消确认，调用方不能据此认定网络请求已停止。设备证据使用 debug fixture，未覆盖真实订阅网络、全部 UI Stop 流程、主 App 进程死亡或真机。现有 Binder 故障与按 Job 恢复边界仍适用。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [主分支优化待办](../development/main-optimization-todo.md)
- [CLI Job RemoteException](2026-09-07-cli-job-remote-exception.md)
