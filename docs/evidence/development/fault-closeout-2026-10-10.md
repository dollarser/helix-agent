# 停止与恢复组合收尾

日期：2026-10-10；范围：[HXA-264](../../completion-records/HXA-264.md)。Owner本轮明确授权模拟器与既有模型，保留逐项提交授权。真实Qwen连续压缩在HXA-263，本矩阵使用合成loopback模型；不混记真实服务。

API36 arm64、Helix_Main_Verify_API36自有只读实例、4096MiB/2核，最终Consumer5660、Developer5662。使用scripts/with-host-slot.py及scripts/run-owned-emulator.py，main/after上限各1200秒；不更新日常5554，不清数据、不改系统安全权限。每实例实际拼音组合和中文commit通过，owner/AVD逐步核查，finally仅关闭自有进程，两个closed.json均exit0。

main九类为PinyinComposer、TurnCancellationRace、SessionInputQueue、GoalModelCancellation、ToolSettlementRecovery、AutomaticRecoveryPersistence、JobObservationJournal、PluginLifecycle、RecoveryJourney DeviceTest。两个instrumentation通过原fail-closed解析器：各42/42，零失败、零skip。setup选择RecoveryJourneyDeviceTest#recoveryFactsSurviveProcessDeathWithoutReexecution，main传recoveryPhase=verify；setup确实Process.killProcess，主回归核对新PID、原持久事实和无自动重放。不是仅force-stop再启动。

追加复用scripts/run-goal-process-kill.py（两渠道各两次SIGKILL）、scripts/run-model-stream-process-kill.py（Consumer OPENAI_CHAT_COMPLETIONS/OPENAI_RESPONSES/ANTHROPIC_MESSAGES，各headers/body六个SIGKILL）、scripts/run-file-publish-process-kill.py（Developer temporary/published两个SIGKILL）。含两次setup共14个进程死亡边界。Goal成功control/final共4项，HTTP两次恢复各1项共12项，文件两次恢复各1项共4项，共20项实际成功检查；prepare预期进程死亡不计测试通过。HTTP实际socketEOF、原调用用量和身份保留；恢复可产生合法的只读核查请求，不声称请求数零增加。文件只证明原子发布/显式孤儿清理，不证明Goal/ToolCall任务验收。

失败与修复：原Consumer42/42及追加协议通过；原Developer四项失败，独立10项队列/Pinyin再现四失败。在仍存活的自有实例读取合成Room事实，原Turn FAILED/OUTPUT_TOKEN_LIMIT，turnBudgetsJson的maxOutputTokens=1；这是正向夹具继承旧负向预算，当前生产默认16384未改。两项完成断言失败，两项Steer因额外恢复Turn失败。修正只使自有正向Session使用TurnBudgetBounds.DEFAULT并等待持久设置，finally恢复先前预算；原完成/原身份/无重放断言完整保留，负向预算测试未弱化。

首次修正全仓门禁发现fixture LongMethod 63>60；提取原初始化步骤为helper，未增加静态抑制。错误地在未核实门禁终态前启动的Consumer r2被限定PID检查后取消，未运行测试，owned finally正常关闭；保留为cancelled，不计pass。最终重新冻结r3后双渠道全部通过。fresh全仓test/detekt/spotlessCheck/双渠道lint/App和AndroidTest编译：47秒、993任务（32执行），退出0。source门禁独立重新执行，结果随最终提交记录。

冻结基础39ab176b，队列fixture SHA-256 edd4caed4c78b69fa56cb7e45ed37351b58cdf4f566617f0127b8b034896c69a：

| 制品 | SHA-256 |
| --- | --- |
| Consumer App | b6c8a9972e7278d7c3a4564494ce88fe6a7b4a8a6f7a86ce690efd9eca5ffbe5 |
| Consumer Test | 1256631e3311048bc9f19e0d8dafdf9b3200114d34a617d25faacba427a8451c |
| Developer App | 557dce87f27db98129f22bd3c4505608b05203e73f01379d75c8b72d69b84633 |
| Developer Test | f8d7abd5442e01a1f72e24b656143aeabb03ecf2783d0748218773e4fec6011f |

原始证据均在ignored build/maintenance-followup-20261010/：fault-closeout-*-r3的owner/artifacts/closed、setup/main/实时logcat、JUnit、goal/三协议/file-publication JSON与原始分阶段日志；queue-diagnostic-observation.log及queue-failure-snapshot、原失败尝试、cancelled r2和fault-closeout-fix-host-r2.log保留。日志/数据库只含本轮合成fixture，不提交原始数据库。

此范围未覆盖所有订阅/PRoot/远程服务故障、其他OEM、长稳或真机卡顿黑屏；历史缺少逐调用证据的OUTPUT_TOKEN_LIMIT仍不能唯一归因。本次可复现的1token夹具缺陷已经修复，不能据此泛化全部历史截断。
