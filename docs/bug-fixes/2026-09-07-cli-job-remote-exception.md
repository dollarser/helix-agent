# Bug Fix: CLI Binder/PFD 传输失败边界

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102, HXA-110

以下按独立缺陷保留当时的原因、失败与验收范围；各节的 fixed 只指原候选，不合并测试计数或把后续通过回填到早期失败。当前契约仍查主题 ADR。

## Problem

<a id="case-2026-09-07-cli-status-binder-death"></a>

### CLI 状态握手遗漏 Binder 远端死亡异常

`CliStatusHandshakeClient.transact` 仅捕获 `RuntimeException`；Android 的 `DeadObjectException` 属于 `RemoteException`，可直接逃逸到 Runtime 验证调用方，而不是返回已有的 `Outcome.Failed`。专用 API 34 上通过本地 Binder 的 `onTransact` 注入 `DeadObjectException`，4 项设备测试中该项失败，其余未处理事务、空状态文档、调用者不匹配通过。

修复前日志：`build/main-verification/cli-handshake-before-api34.log`。这是实际 Android Parcel/Binder API 的异常注入测试，不是对真实 companion 进程执行 SIGKILL。

<a id="case-2026-09-07-cli-job-remote-exception"></a>

### CLI Job Binder 普通通信异常逃逸

Affected modules: runtime/cli-client

Job Binder 调用只捕获 DeadObjectException，其他 RemoteException 可逃逸。API 34 本地 Binder 注入测试中，普通通信异常失败，远端死亡和缺少状态文档两项通过。

<a id="case-2026-09-07-cli-job-pfd-read-failure"></a>

### CLI Job PFD 读取异常逃逸

Affected modules: runtime/cli-client

对账回复携带不可读的 PFD 时，CliPfdChannel.read 抛出的 IOException 未被 Job 传输层捕获。API 34 实际 Android Parcel/PFD 测试得到 EBADF；新增三项中该项失败，有效结果和 hash 不匹配两项通过。

<a id="case-2026-09-07-cli-request-pipe-writer"></a>

### CLI 请求管道写线程未捕获 EPIPE

Affected modules: runtime/cli-client

Runtime 拒绝提交且未读取请求 PFD 时，后台写线程抛出未捕获 IOException/EPIPE。API 34 以 256 KiB 请求与拒绝事务复现，测试捕获了写线程的未处理异常。

## Impact

### CLI 状态握手遗漏 Binder 远端死亡异常

Runtime 验证可能向调用方抛出远端死亡异常，无法返回已有的握手失败状态。

### CLI Job Binder 普通通信异常逃逸

调用方可能收到原始通信异常，无法获得既有的不可用结果；本次未发现或宣称已发生重复执行。

### CLI Job PFD 读取异常逃逸

调用方无法收到已有的不可用结果。未发现或宣称已发生结果伪造或重复执行。

### CLI 请求管道写线程未捕获 EPIPE

正常 Android 默认未捕获异常处理可能终止应用进程。测试通过临时处理器观察指定写线程的异常，没有把其他线程异常吞掉，也没有把它描述为真实 Runtime SIGKILL。

## Root cause

### CLI 状态握手遗漏 Binder 远端死亡异常

RemoteException 不属于 RuntimeException，原有 catch 未覆盖 Binder 的通信异常。

### CLI Job Binder 普通通信异常逃逸

DeadObjectException 只是 RemoteException 的子类，原 catch 未覆盖其余 Binder 通信异常。

### CLI Job PFD 读取异常逃逸

传输层原捕获 RemoteException/RuntimeException，遗漏 PFD 创建或读取的 IOException。

### CLI 请求管道写线程未捕获 EPIPE

请求 PFD 写入在线程内执行，外层 Binder try/catch 无法捕获该线程的异常；原实现不记录上传失败或等待写线程结束。

## Fix and invariants

### CLI 状态握手遗漏 Binder 远端死亡异常

显式捕获 `RemoteException` 并返回 `Failed`，保留 `CallerMismatch` 独立结果；提取回复解码，减少事务方法的嵌套返回。事务只发送一次，不增加连接重试、Job 重发、账号访问或自动启动；两个 Parcel 仍在 `finally` 释放。

### CLI Job Binder 普通通信异常逃逸

将 Binder/PFD 传输抽为内部 CliModelJobWire，保留外部 CliModelJobClient API 与轮询/对账顺序。统一捕获 RemoteException 并返回 HANDSHAKE_FAILED；提取回复解析，保留缺失文档拒绝、PFD 大小及输出 hash 检查。事务不重发，Parcel/PFD 释放路径保持。

### CLI Job PFD 读取异常逃逸

显式捕获 IOException 返回 HANDSHAKE_FAILED，不返回部分事件、不重发事务。保留既有状态文档解析、输出 hash 校验、事件 codec 边界和描述符释放。

### CLI 请求管道写线程未捕获 EPIPE

CliRequestPipe 负责单次上传，记录 IOException，关闭描述符并有界等待写线程（最多一秒）；等待中断保留当前线程中断标志。传输结束后，已知失败或仍在运行的上传不能返回成功结果。先回收 Parcel 引用再清理管道，拒绝路径可以结束阻塞写入。无事务或 Job 重发。

## Alternatives considered

### CLI 状态握手遗漏 Binder 远端死亡异常

不采用失败后自动重试：这不能代替持久 Job ID 对账。本修复只返回已有的握手失败结果。

### CLI Job Binder 普通通信异常逃逸

不通过自动重发解决异常；未知结果仍需按已有 Job ID 查询/对账。未改变协议或增加账号能力。

### CLI Job PFD 读取异常逃逸

不返回空事件成功，也不为读取失败重新提交 Job。结果恢复必须沿已有 Job ID 对账。

### CLI 请求管道写线程未捕获 EPIPE

不使用全局异常处理器作为产品修复，不吞异常后保留成功回复，不无限等待写线程。

## Regression verification

### CLI 状态握手遗漏 Binder 远端死亡异常

`CliStatusHandshakeDeviceTest` 验证异常转换与回复分类。真实跨进程死亡、超时及 Job ID 恢复矩阵仍需独立验证，不能以本测试替代。

修复后 API 34 **4/4 通过**；CLI Client JVM 24/24、Developer JVM 300 通过/3 既有跳过。CLI Client Lint、构建及 Spotless 通过，根 Detekt 剩余 44 项未通过。原始日志和 APK hash：`build/main-verification/cli-handshake-result.json`。

### CLI Job Binder 普通通信异常逃逸

新增 CLI Client 模块设备测试，使用仓库固定版本的 AndroidX 测试依赖及锁文件。真实 Android Binder 异常注入记录：`build/main-verification/cli-job-wire-before-api34.log`。测试同时断言通信失败时事务发送次数为一。

修复后 3/3 设备测试通过，CLI Client JVM 24/24、Developer JVM 300 通过/3 既有条件跳过；模块 Lint/构建/Spotless 通过，根 Detekt 剩余 42 项未通过。证据及安装 APK hash：`build/main-verification/cli-job-wire-result.json`。

### CLI Job PFD 读取异常逃逸

CliModelPfdDeviceTest 使用真实 ParcelFileDescriptor 管道：验证有效结果事件一致、错误 hash 拒绝、不可读描述符返回失败；每例对账事务一次。修复前日志：`build/main-verification/cli-pfd-before-api34.log`。

修复后 PFD/Binder 组合 6/6 通过。CLI Client JVM 24/24、Developer 300 通过/3 既有跳过，模块 Lint/构建/Spotless 通过；根 Detekt 42 项未通过。原始日志与 APK hash：`build/main-verification/cli-pfd-result.json`。

### CLI 请求管道写线程未捕获 EPIPE

CliRequestPipeDeviceTest 覆盖拒绝 256 KiB 上传时无未捕获异常、线程退出、事务一次，以及完整读取请求字节后仍接受。修复前日志：`build/main-verification/cli-request-pipe-before-api34.log`。

修复后上传/PFD/Binder 8/8 通过，CLI Client JVM 24/24、Developer 300 通过/3 既有跳过；模块 Lint、构建和 Spotless 通过，根 Detekt 仍 42 项未通过。日志与 APK hash：`build/main-verification/cli-request-pipe-result.json`。

追加提前 ACCEPTED 与保留读取端但不消费两项回归，组合 10/10 通过；保留读取端场景在三秒内返回失败，释放后线程退出。证据：`build/main-verification/cli-premature-ack-result.json`。仅覆盖该有界本地夹具，不扩展为所有跨进程阻塞保证。

## Residual risk

### CLI 状态握手遗漏 Binder 远端死亡异常

本地 Binder 注入不证明真实跨进程死亡、超时或 Job 效果恢复；这些矩阵仍保持待验证。

### CLI Job Binder 普通通信异常逃逸

本地 Binder 注入不等于跨进程 SIGKILL。真实 Job 提交、PFD 中断和副作用恢复矩阵仍需独立验收。

### CLI Job PFD 读取异常逃逸

本地 Binder/PFD 注入不是跨进程 SIGKILL。提交端写管道、超时、真实 Runtime 及副作用对账仍需独立验证。

### CLI 请求管道写线程未捕获 EPIPE

本地 Binder/PFD 测试不替代真实跨进程死亡或恶意远端长期持有描述符的验收；一秒上限约束清理等待，不证明所有内核阻塞均已终止。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [ADR-RUNTIME-001](../adr/runtime/001-execution-domains.md)
