# HXA-196：Runtime 提交预算与文件描述符回收

Status: verified slice
Date: 2026-09-18
Related HXA: HXA-196
Affected modules: app, runtime/proot-client

## Problem

同步 Linux 工具在构造输入快照之后计算剩余时间，但将结果强制抬到至少 1 秒。输入准备已消耗全部窗口时仍可能继续提交。计算同时依赖可调整的墙钟，快照期间的时钟回退也可能增加剩余时间。

`ProotJobClient.submit` 只在部分拒绝或异常分支关闭客户端 PFD，成功和重复提交后仍保留本地描述符；Parcel 转移的是独立描述符，Runtime 关闭其副本不能替客户端释放。输出 PFD 打开失败时，调用端也可能遗留先打开的输入 PFD。

## Fix and invariants

- executor 入口一次采样调用截止时间，再按单调时钟扣除准备耗时；少于 1 秒直接返回 `BUDGET_EXHAUSTED_BEFORE_SUBMIT`，不生成 Job 绑定或发起提交。取消在快照后再次检查。
- 同步 client 冷绑定完成后扣除绑定耗时，并再次读取调用方剩余额度；取两者较小值，只缩短相对执行窗口。过期则关闭连接和 PFD，不发出提交事务。已经准备的身份记录不被当作运行证据，查询仍为原 Job。
- client 在统一 finally 中关闭两个客户端描述符，覆盖成功、重复、拒绝、Binder 错误与连接异常。Runtime 持有的副本继续负责实际 I/O。
- 输入和输出的打开操作嵌套在资源管理块中，输出打开失败也释放输入。
- 不改变命令、授权、Job 身份、原结果查询或重放规则。

## Regression verification

新增主机边界测试覆盖过期、不足最小窗口、准备耗时、单调时间回退、最大值和溢出边界。真实 PRoot 作业测试断言 accepted/duplicate 返回后客户端描述符失效，同时继续等待并验证输出归档；另验证冷绑定后预算耗尽时，原 Job 查询为 Unknown、输出为空、两端本地描述符关闭。

验证入口：

```sh
./scripts/check-all.sh --all
./gradlew :app:assembleDeveloperDebugAndroidTest
bash scripts/debug/2026-09-18/run-job-submission-regression.sh hxa196-submit-v1
python3 scripts/debug/2026-09-18/summarize-job-submission.py hxa196-submit-v1
```

构建和设备测试均通过共享主机锁运行，最终完整流水线 exit 0。主机新增预算测试每个 flavor 3 项通过，完整格式/静态分析/lint/单元测试/双 flavor 构建/产物边界门禁通过。设备 API29/36 各 17 项通过，共 **34/34**；两个独占模拟器均正常退出，APK/test APK 哈希跨 API 一致。日志 `build/hxa196-submit-final.log`，汇总 `build/hxa196-submit-v1-summary.json`。首次检查发现格式、函数数量及文档模板问题，修正后重新跑完整门禁；客户端将资源回收、预算准入与传输拆成方法，类函数数量作局部说明豁免，未删除或跳过测试。

## Residual risk

本次预算修复贯通同步工具准备及客户端绑定阶段。后台 `DetachedJobClient` 已独立扣除绑定时间，但后台产品仍需从调用入口贯通快照、预算预留、绑定、提交及结算。Goal heartbeat 与后台 Job 的重叠计费未在本修复中解决；IPC/Runtime 排队到实际启动的端到端时长仍需整体预算接线验证。

HXA-196 的异步工具、任务投影、结果回收和物理设备后台验收仍未完成；HXA-197/211 不因本修复获得实现证据。

## Related records

- [HXA-196](../../development/tasks/HXA-196.md)
- [执行占用切片](hxa-196-execution-ownership-2026-09-18.md)
