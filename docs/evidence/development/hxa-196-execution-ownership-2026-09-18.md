# HXA-196：跨调用执行占用切片

日期：2026-09-18。基线：`ea60a282`，独立分支 `codex/hxa-196-product`。这是生产接线的前置切片，不是 HXA-196 完成记录。

## 实现与边界

`ExecutionOwnership` 接入生产 `ToolDispatcher`，在实际 executor 线程内部取得占用。取消或超时先返回时，只要 executor 尚未退出，占用仍保留；普通读之间是否冲突沿用平台效果分类。

异步启动执行器可以在提交 IPC 前将独占许可转为持久 `executionId/generation`。启动调用返回不会清除该身份；只有原执行对账结算才能释放。启动线程尚在运行时不能结算，旧 generation 不能释放新身份。主进程使用原子替换和文件同步持久化身份，损坏或写入失败不会被解释为“空闲”。这个文件不复制 Runtime 状态、日志或结果，也不授予权限。

当前没有生产异步执行器调用转移接口。后台 start/status/cancel、绑定校验后的控制入口、任务投影、结果导入和 Goal 重叠时间结算仍待实现。设备用例以合成身份验证真实 Dispatcher 与 Android 文件系统，不启动 Runtime，也不代表进程死亡、锁屏或 Doze 验收。单个手动 PTY 和会话 JSONL 导出未实现。

## 验证

通过共享主机锁执行以下命令；完整输出保存在忽略的 `build/hxa196-ownership-final.log`：

```sh
./gradlew spotlessApply
./scripts/check-all.sh --all
./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
```

以上全部通过。新增主机用例：框架占用 8 项、取消后执行线程占用 1 项、持久存储每个 flavor 3 项，失败数均为零。完整主机门禁包含格式、静态分析、lint、单元测试、双 flavor 构建及产物边界检查。

设备 runner 执行 `ExecutionOwnershipDeviceTest` 1 项和 `PlanSubmitIntegrationDeviceTest` 4 项，覆盖 API29/36 × consumer/developer。第一轮 consumer/API29 的 5 项通过；第二组因 ADB 短暂残留刚关闭的设备编号而在启动前拒绝。改为四个不同端口后完整重跑 **20/20 通过**，四个独占模拟器均正常退出，同 flavor 的 APK/test APK 哈希在两种 API 间一致。仍拒绝任何已有设备，不清理或借用其他会话的模拟器。结果位于 `build/hxa196-ownership-v3-summary.json`，日志为 `build/hxa196-ownership-device-v3.log`。

```sh
python3 scripts/debug/2026-09-18/with-host-slot.py -- bash -c 'set -e
bash scripts/debug/2026-09-18/run-execution-ownership-device.sh hxa196-ownership-v3
python3 scripts/debug/2026-09-18/summarize-execution-ownership.py hxa196-ownership-v3'
```

## 下一切片

优先完成后台 Job 的预算归属和绑定恢复，再接异步工具。Goal heartbeat 与 Job 重叠的时间不得重复计费，Turn 返回后的后台时间不得遗漏；查询不得续租，未知提交不得重放。结果导入完成前仍须保留冲突执行限制。真实物理设备后台验收独立保留。
