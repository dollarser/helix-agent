# ADR-0053: Goal 同会话连续运行与激活边界

Status: accepted
Date: 2026-09-16
HXA: HXA-208
Deciders: Project owner（要求依据 DSH 完善 Goal；明确离开前台也继续下一轮，并要求本次完成创建、编辑与所有依赖功能）
Supersedes: none
Superseded by: none

## Context

用户授权将 Goal 从每轮显式继续改为可连续推进。DSH driver 的核心是同会话空闲调度、独立激活、旧请求失效和恢复后不自动激活；这些不依赖桌面或 event sourcing。当前 main 的普通 Goal 模式发送还缺少持久目标绑定，文档中的“发送即创建”不能替代源码验收。

## Decision

部分替代 ADR-0004/0039 的每个新 run 都必须用户单独继续约束，保留 ADR-0040 的模型判断完成、Room/audit、预算和审批。

- Goal 模式发送及用户选择连续继续会激活同会话驱动；普通 Chat/Act 不推断持续运行授权。内部单轮执行入口保留明确的手动方式。目标持久状态与进程内激活分离，恢复、进程死亡不恢复激活。
- 连续轮仍使用 AgentRuntime.submit 和同一个 Turn/GoalRunCoordinator。前轮必须持久结算，下一轮需要精确的激活标识与前轮 ID，执行前重验；失效请求、重复通知和新用户输入不能多开一轮。
- 只有正常结束、仍有工作、无未知副作用且预算有余量才续跑。完成、blocked、等待输入、用户停止、异常、输出截断或预算耗尽停止驱动；不自动重试服务或持久化失败。
- 停止先解除激活再取消当前工作；新用户消息先解除旧激活并等待当前轮安全结算，再走普通发送门禁。新消息不隐含恢复未知副作用或增加预算。切换会话本身不取消工作。
- 自动轮来源持久记录为 FOREGROUND_CONTINUATION，不伪造新的用户授权。五维预算跨轮累计，不新增一套与它竞争的预算账本；单轮上限和安全门禁继续生效。
- 用户明确要求离开前台也继续下一轮。用户启动的连续任务沿用 dataSync 前台服务，轮次交接保留服务；Activity 停止或切换会话不解除激活。等待审批/输入不维持空转服务；用户停止、系统拒绝、超时或进程死亡解除激活，恢复需用户继续。WorkManager、系统启动和定时来源不自动启动模型。Android 强停、进程回收及服务限制仍是执行边界，不承诺无限后台存活。
- 工具面采用 `create_goal` / `get_goal` / `update_goal`，兼容 `goal.report`。读取包含目标、版本、待结算编辑和累计预算；更新必须带读取到的 id/revision。创建、目标/预算编辑、active/paused 只接受当前直接用户请求来源，并引用该请求原文；自动轮及重试不携带这个来源。模型负责理解请求语义，原文匹配是来源检查，不是独立语义证明。完成/进度/阻塞保持模型报告与宿主结算契约，不额外授予工具权限。
- 创建和编辑是封闭的会话内 `METADATA` 操作。Room v19 增加 `goal_controls`：会话归属、编辑版本和待结算修改；既有单会话绑定回填归属，迁移不激活任务。编辑在当前 Turn 成功结算后原子生效，不中途放大当前执行预算；取消、失败和进程恢复清理未生效编辑。创建的首个 Goal run 同样在当前 Turn 结算后开始，当前普通 Turn 仍受原 Turn 预算限制。激活仅在进程内保留，不存储自动恢复许可。
- UI 目标编辑、预算编辑与模型编辑共用版本冲突规则；已批准 Plan 的目标不能绕开 Plan 审阅直接修改。paused 解除运行激活；未启动的 READY 或已有 BLOCKED/INPUT_REQUIRED 保留原生命周期事实，运行中的一轮正常结算后停在 PAUSED。暂停/编辑不伪造完成，不抹掉已消耗预算。Plan 模式不开放创建或激活 Goal。
- 不重建 Room 为 event sourcing，不为四种展示 phase 删除已有恢复状态。三轮 blocked 门槛不机械套用于权限、预算和未知副作用；模型仍需具体阻塞说明，有可推进工作则报告 in_progress。

## Alternatives considered

仅放开 GoalWakePolicy 会漏掉停止、并发和恢复激活。整套移植 DSH 则重复 Android 持久化和预算。保留每轮手动继续不能满足本次核心诉求，作为单轮入口而非默认 Goal 体验。

## Consequences

连续运行增加实际模型使用量，始终受用户原预算限制。前后台切换不停止续跑；系统中断后用户恢复需明确继续。模型报告可追溯但不构成独立完成认证。

## Verification

架构接受依据为本轮所有者请求；实现与主机/设备验收另记 HXA-208。必须覆盖两轮完成、预算、停止/新输入、会话隔离、重复/旧激活、进程重启和前后台边界。实现与上述有界验收已完成，见[HXA-208](../completion-records/HXA-208.md)；真实账号/OEM长稳不由该记录替代。

## Reconsider when

需要定时/外部 Channel 自动激活或三轮模型阻塞策略时，补各自验收和来源契约；这些不是本次连续 Goal 的隐含唤醒权限。

## References

- [原 run/wake 决策](0004-goal-run-wake-budget-semantics.md)
- [暂停与阻塞](0039-background-results-and-goal-blockers.md)
- [完成报告](0040-model-judged-goal-completion.md)
- [DSH driver](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/goal/goal-round-driver/README.md)
- [DSH tools](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/goal/tool-goal/README.md)
- [Android 后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
