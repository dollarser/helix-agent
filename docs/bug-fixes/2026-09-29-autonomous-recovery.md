# Bug Fix: 自主恢复首批实现与主机验证

Status: fixed
Date: 2026-09-29
Related HXA: HXA-232
Affected modules: app, tools/automation

日期：2026-09-29。基线：main `7480141b` 加未提交工作树。本记录覆盖 HXA-232 首批实现，不代表整个自主恢复闭环交付。

> 后续承接：本文下方“尚未实现/等待验收”只描述首批交付时点。2026-09-30 的实现与有界验证、仍未覆盖的故障矩阵统一见 [HXA-232](../development/tasks/HXA-232.md)；保留本页原失败与测试事实，不以此恢复旧待办。

## Problem

自动化每十次动作要求人工恢复，Goal 单轮额度耗尽不能自动接续；连续无进展转为等待用户处理。循环警告还可能使下一模型请求以 SYSTEM 消息结束，空响应的暂时网络错误也直接终止任务。

## Impact

用户必须介入本可自动恢复的技术故障，长程任务容易提前中断；模型请求历史顺序可能不符合 Provider 约束。

## Root cause

既有恢复流程将技术暂停与权限审批混在一起；后继调度只接纳成功 Turn，且没有为无进展提供受预算约束的最终回复。循环控制提示的插入位置与历史投影缺少一致规则。

## Fix and invariants

- 自动化取消每十次动作的人工暂停；成功的新快照可恢复已授权目标。原包集合、敏感节点、锁屏、5 分钟/30 动作许可、撤销和 Stop 不变。snapshot/find/wait 输出契约升级为 v3，用 `requiresAuthorization` 表达新目标授权，不再要求用户到设置页做恢复。
- 连续 Goal 的局部调用/工具轮/token 上限允许在原激活和总额度内自动接续；保留唯一前轮请求键、配置快照、run 结算、剩余额度和未决效果校验。普通会话、输出截断、容量不足、总额度不足及取消不通过此入口自动续跑。局部额度不再把已排队用户输入停泊，用户队列仍优先。
- 稳定无进展先警告策略调整，达到停止条件后给模型一次禁止工具的收尾响应机会；仍受原预算和取消约束。模型即使返回工具调用也不执行；最终状态保留 FAILED，不转 INPUT_REQUIRED、不假报完成。
- 修复循环警告在工具结果之后导致下一请求末尾为 SYSTEM 的问题；兼容历史记录投影，旧 Turn 的循环控制提示不进入新用户任务。
- 直接 HTTP 推理遇到没有文字/任何工具片段的可重试失败时，最多退避重试两次（1 秒、2 秒）。每次保存独立 ModelCall、失败记录并计费；部分响应、永久错误和订阅 Runtime 不重试，不重置 Turn/Goal 额度。
- 提示词要求自主检查、权限内补救，无法完成时保留成果、明确结束，不将文件冲突等技术恢复转交用户。

## Alternatives considered

不采用取消全部额度、盲目重放部分响应、自动清除 UNKNOWN 或只隐藏恢复按钮的方式。恢复必须保留原授权、执行事实与预算，未具备后端恢复能力的入口保留为后续工作。

## Regression verification

通过共享 host slot 执行：

```sh
./gradlew spotlessApply \
  :tools:automation:testDebugUnitTest :runtime:quickjs:testDebugUnitTest \
  :core:model:test :core:agent:test :core:storage:testDebugUnitTest \
  :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest detekt
bash scripts/check-all.sh --source
```

最终 r4 主机批次成功。automation 55、QuickJS 88、model 158、agent 193、storage 217 项通过；consumer 928 项通过/4 项跳过，developer 976 项通过/4 项跳过，均零失败。跳过来自既有 Connector 外部材料/账号测试，不算验收。双渠道 lint/APK/test APK、detekt、源码门禁通过；追加 spotlessCheck 和 git diff --check 均通过。

本机日志位于忽略目录 `build/autonomous-recovery-gates-r4.log` 与 `build/autonomous-recovery-source.log`。早期批次因格式、静态分析和新增设备夹具缺少参数失败，修正后重跑，未删除失败测试。前轮 QuickJS 原型的反射 varargs/分支说明、方法拆分及夹具格式问题一并处理以恢复主机门禁，未将原型标为完成。

## Residual risk

所有者明确要求 QuickJS 完成后再用模拟器，本轮设备 **not requested**；测试 APK 编译不等于执行。未使用真实模型、账号或真机，未提交/推送。

HXA-232 仍开放：UNKNOWN 的自动执行器对账/有依据核查、Runtime 结果自动回收、队列失败/进程重开的统一自动恢复，以及配置/数据外发统一审批均尚未实现。既有相应 UI 和契约仍存在；没有为了减少按钮而丢掉执行事实或自动解除权限。QuickJS 可配置权限也尚未完成。

新 Goal 局部额度设备夹具已编译，主机策略测试覆盖暂停/取消/未知/总额度/容量/网络终局等反例；实际 Android 生命周期、自动化新快照恢复及模型恢复轨迹等待后续明确授权的定向设备验收。

## Related records

- [HXA-232 自主恢复任务](../development/tasks/HXA-232.md)
- [当前开发状态](../development/status.md)
- [人工介入审查](../evidence/research-history/harness-human-intervention-audit-2026-09-29.md)
