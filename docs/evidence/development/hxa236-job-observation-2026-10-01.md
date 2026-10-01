# HXA-236：J1 基础观察与有界等待阶段验证

日期：2026-10-01。基于 `main@05e910039e03095d98649d96a9e64a3a71bcb078` 的未提交工作树。所有者要求阶段性收尾、更新文档和记录剩余工作；本次不启动 J2、Project Memory、设备、真实账号或发行。源码和 APK 指纹见[阶段收尾记录](phase-closeout-2026-10-01.md)。

## 已接入的生产范围

`DetachedJobRegistration` 将原 Linux start/status/cancel/collect 与 `jobs.await` 注册到同一 ToolRegistry；status/await 使用 `JobObservationService`，cancel/collect 保留原控制、授权和结算路径。模型只传原调用句柄，`LinuxJobObservationPort` 校验会话、Turn、执行、Job 和输入指纹；fork 不因此取得原执行控制权。

`ToolDispatcher.dispatchCompletion` 仅对可信宿主创建的观察执行器走异步完成路径，仍经过原 schema、能力、权限、审批、准入、输出校验和审计。`ToolScheduler` 在观察期间释放业务槽，按原调用顺序回填。普通工具不因名字包含 await 获得无副作用超时待遇。

`JobObservationWait` 区分 ANY/ALL 满足、等待到期、来源不可用、需核查、资源饱和和取消；`JobQueryLane` 保留尚未实际返回的物理查询占用。观察逻辑结束不取消原任务、不续租、不导入输出、不释放原 execution owner。`BackgroundJobActions` 分离一个查询槽和一个控制槽，晚到查询不覆盖新控制回执。

生产边界沿用 HXA-236：最多 16 个观察者、每会话 4 个、每次 8 个句柄、最多两路无排队物理 query、内部等待 15 秒、单 query 观察上限 2 秒、外层收尾预留 250ms。参数及权限来源仍由宿主控制。

## 复核与回归范围

本次直接复核 `ToolDispatcher`、`ToolScheduler`、`JobObservationService/Wait/Executor`、`JobQueryLane`、`LinuxJobObservationPort`、`BackgroundJobActions` 及 PluginRegistry/PluginService 的相关接线。对照测试检查单次结算、发布前原绑定/权限重验、共享查询的观察者取消隔离、IPC 占用与控制容量隔离、插件停用及重新启用不复活旧 binding。

前期已修复的共享查询取消误伤、发布前复核缺失、查询阻塞取消入口、迟到回执覆盖及配置上界仍由原测试保护；本次未重新计作新代码交付。没有修改生产代码、放宽权限或增加执行管线。这是有界集成复核，不是整个工作树逐行审计或所有并发时序正确性的证明。

## 已实际执行的主机验证

前轮 `build/hxa236-host-verified.log` / `.exit` 记录完整主机命令退出 0：989 tasks，118 executed / 871 up-to-date。原日志保留。

本次重新执行 `bash scripts/debug/2026-10-01/validate-stage-closeout.sh`，退出 0：989 tasks，19 executed / 970 up-to-date。包含全仓 test、detekt、spotlessCheck、双渠道 lint、Debug APK、AndroidTest APK；未执行设备。构建前后复用 `scripts/run-agent-eval.py provenance` 记录源码清单并比较一致。这是增量整合检查，不声称所有测试在最后一次强制重跑。

| 定向 XML 套件 | tests | failures/errors/skipped |
| --- | ---: | --- |
| JobObservationServiceTest | 10 | 0/0/0 |
| JobObservationDispatchTest | 6 | 0/0/0 |
| JobObservationIsolationTest | 4 | 0/0/0 |
| JobObservationLimitsTest | 2 | 0/0/0 |
| LinuxJobObservationPortTest（Developer） | 7 | 0/0/0 |
| BackgroundJobActionsTest（两渠道各自） | 5 / 5 | 各 0/0/0 |

前四组共 22 项，Linux adapter 7 项，共享后台操作 5 项；共享渠道用例不重复相加，也不把全部定向套件称为本次新增。复用已有 `summarize-j1-host.py` 汇总，原摘要副本与本次结果保存在 `build/phase-closeout-2026-10-01/j1-reports-before.json`、`j1-reports.json`。

全套 XML 当前范围：tools/framework 243 项、core/agent 289 项，失败/错误/跳过均为 0；App Consumer 1008 项、Developer 1077 项，均无失败/错误，两渠道各保留 4 项条件性跳过。tests 数量包含 skipped，跳过不算通过。

## 尚未完成：不得关闭完整 J1

1. **J1-3 统一观察候选。** 当前等待结果通过普通 ToolResult 持久回填；`ContextCompiler` 仍是有界历史压缩选择，没有独立 JobObservation 候选、变化摘要和 revision/resultRef 的自动纳入。按 Harness §8.6/§16/§18 接同一上下文端口，不新建第二历史；自动纳入不能自行激活新 ModelCall。
2. **可信类型的无进展判定。** `DurableToolLoopProgress` 仍有 `LIVE_OBSERVATIONS` 名称白名单，尚未完成按受信任观察类型处理 RUNNING 的契约。后续覆盖稳定 RUNNING、WAIT_EXPIRED、真正停滞和普通同名工具，不能仅添加 `jobs.await` 名称豁免，也不能把变化的时间戳当实际任务进展。这是实现契约缺口，本次未复现或宣称已修复用户任务误停止。
3. **联合反例与设备旅程。** `JobAwaitLoopDeviceTest.loopAwaitsOriginalExecutionThenCollectsVerifiedOutput` 使用本地脚本模型，覆盖真实 Provider 流、Core Loop、Dispatcher、Runtime 和输出哈希，但本次只编译。fork/换目录/插件停用/重开/取消/迟到回包的联合路径须逐项映射现有测试、补缺并在明确授权的指定设备上执行；分模块通过不替代整个路径。

基础观察/等待候选可以阶段保存；完整 HXA-236 继续开放。J2 AUTO/手动后台化、Project Memory、真实模型效果、OEM/长稳及发行不属于本次完成结论。

## 实践参照

2026-10-01 重新读取官方 [Codex App Server](https://developers.openai.com/codex/app-server) 的原进程身份与独立查询/终止入口、[Claude 后台命令](https://code.claude.com/docs/en/interactive-mode#background-bash-commands) 的任务 ID/结果观察，以及 [Anthropic 长任务 Harness](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents) 的逐项推进和明确交接。采用职责和验证方法，不复制桌面超时/续期政策，不把外部文档当作 Android 设备验证。
