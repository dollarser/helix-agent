# Bug Fix: 订阅终态流式传输与后台租期提交窗口

Status: fixed
Date: 2026-09-18
Related HXA: HXA-196, HXA-190
Affected modules: runtime/cli-app, runtime/cli-client, runtime/proot-core, runtime/proot-client, app androidTestDeveloper

## Problem

订阅预览虽然已落盘，终态仍将全部事件编码为 ByteArray，取回时再次完整读取；客户端再构造完整 JSON 文本、JSON 树和事件列表。大结果在传输结束时仍有多份完整副本。

后台命令客户端将绑定服务前取得的剩余预算原样交给 Runtime；冷绑定期间消耗的时间没有扣除，实际租期可能超过调用方原有窗口。

## Impact

第一项增加端侧内存峰值与回收压力；第二项可能延长后台执行时间。并不据此声称已经复现 OOM，也不把平台核心实现视作 HXA-196 产品接线完成。

## Root cause

预览、终态编码、PFD 发送及客户端解码采用不同路径。前一轮优化只解决预览与部分事件累积，终态接口仍以完整字节数组交换。后台租期服务使用收到预算时的单调时间起算，而客户端绑定耗时发生在此之前。

## Fix and invariants

- 事件逐条编码到暂存文件，写入时计算 SHA-256；flush/fsync 后原子发布，不以复制覆盖作为失败降级。原 version 1 JSON 协议与哈希语义保持一致。
- 编码期间不持有 runner 锁；发布前在同一取消锁下核对任务仍未终止。取消/ACK 先发生时，迟到编码不能重新发布已清理结果；编码失败仍结算 FAILED 并释放活动槽位。
- 结果取回先校验文件哈希并保留打开的文件描述符，再通过 PFD 复制。并发 ACK 删除目录项后，已打开的读取仍能完成；设置失败、发送失败和成功均有明确关闭路径。
- 客户端从带摘要计算的 InputStream 逐事件解码，整体哈希、格式版本和唯一末尾终止事件均通过后才向调用者返回事件列表。没有提前向模型发布尚未验证的内容。
- 后台提交预算取请求租期和调用方预算的较小值，再扣除本次绑定经过的单调时间。少于 1 秒直接拒绝，不向上补足最低租期，不提交命令。原请求身份不因扣时改变，重复提交仍不能续租。

## Alternatives considered

未重新增加累计响应配额，也未截断终态来掩盖内存问题。未改变 IPC 格式、恢复来源或 ACK 契约。客户端完整事件列表的进一步替换需要连同 Provider 消费与持久化接口评估，本次不以新的并行状态源替代。

## Regression verification

主机新增回归覆盖：超过 20 MiB 的惰性事件源逐条编码、Unicode 分片读取、格式/哈希/终止事件异常、ACK 后已打开结果仍可读、编码期间取消与 ACK 后不复活文件，以及租期耗时扣除和过期边界。原结果发布失败后释放 runner 的测试保留。

验证命令与结果（均通过共享主机锁执行）：

- `./scripts/check-all.sh --all`：最终完整运行 exit 0，主机测试、静态检查、构建及 APK/订阅边界通过；日志 `build/runtime-final-gates.log`。首次运行发现边界脚本仍要求旧的整块读取标记，更新为流式校验契约后重新完整运行通过。
- `./gradlew :app:assembleDeveloperDebugAndroidTest`：通过。
- `bash scripts/debug/2026-09-18/run-runtime-stream-lease-regression.sh runtime-stream-lease-v1`：API29/36 各 35 项 Runtime 回归和 13 项结果取回/后台 Job 回归，共 96 项 instrumentation（包含 2 项进程死亡探针准备），另有 2 项独立主进程死亡检查通过。四个独占模拟器运行均正常关闭，exit 0。
- `python3 scripts/debug/2026-09-18/summarize-runtime-stream-lease.py runtime-stream-lease-v1`：汇总校验通过，证据 `build/runtime-stream-lease-v1-summary.json`。

设备复现入口：[run-runtime-stream-lease-regression.sh](../../scripts/debug/2026-09-18/run-runtime-stream-lease-regression.sh)。四轮使用同一 developer APK，SHA-256 `04239c02bad35c337ef967ee5a063e3ec13a98da6ff6a18aede7120ffe2bcabd`；测试 APK 为 `f4af6ca505d35aed7a66037dc3055b0b4188a583e4bed2e3725b8682bbcfaaf8`。这是定向模拟器验证，不替代全产品、真实账号或 OEM 验收。

## Residual risk

客户端仍持有最终 List<ModelEvent>；单个巨大事件也仍需物化。非 Codex 适配器的上游事件累积、请求正文以及后续本地归档不据此成为恒定内存。没有真机 OOM/热压力/长稳基准，不宣称所有资源问题关闭。

HXA-196 尚缺生产异步工具、持久互斥与预算接线、任务投影及真机 HOME/锁屏/Doze 验证；本次租期修复不关闭整项任务。

## Related records

- [前一轮授权与 Runtime 收敛](2026-09-18-authorization-runtime-convergence.md)
- [HXA-196 完成记录](../completion-records/HXA-196.md)
- [Runtime 有效决定](../adr/runtime/README.md)
