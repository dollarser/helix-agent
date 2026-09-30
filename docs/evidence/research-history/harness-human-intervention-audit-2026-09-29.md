# Harness 人工介入审查（历史快照）

> **2026-09-30 归档说明：**下文是原基线的发现与当时建议，保留原话，不是当前待办。周期确认、已授权目标恢复、只读核查、队列和授权交互已由 [HXA-232](../../development/tasks/HXA-232.md)承接；有限设备验证与尚未完成矩阵分别看该任务。当前分工以 [Harness §13.6](../../architecture/harness-refactor-plan.md#136-模型与-harness-的职责优化及减法准则) 为准，必要业务澄清、系统认证不因旧文“仅审批”概括而被禁止。本次归档不关闭剩余验收，也不恢复旧人工流程。

日期：2026-09-29。基线：本地 main `7480141b` 加当前未提交工作树。结论：**不满足“人工只处理工具审批，其余由模型处理或明确放弃”的新要求**。

本轮是源码与契约审查，没有修改生产行为、执行设备测试或声称全产品验收。QuickJS 原生访问原型仍未提交、未通过全部主机门禁，也没有设备验收；它不会使以下既有缺口自动消失。并行文档和前轮 `/compact` 修复保持原样。

后续实施：所有者授权 HXA-232 后，已修改其中部分路径并完成首批主机验证，见[实施记录](../../bug-fixes/2026-09-29-autonomous-recovery.md)。下表保留审查时观察，不应再把周期确认、Goal 无进展 INPUT_REQUIRED 等旧行为当作当前代码结论；2026-09-30 已追加原执行器查询、持久去重只读核查、原 Goal 预算绑定、队列明确终态和统一授权交互，见 [HXA-232](../../development/tasks/HXA-232.md)。本表为历史问题清单；当前主机结果与尚未执行的设备边界见任务证据，不从源码修复推导真实设备完成率。

## 判断标准

任务开始后，除真实的工具授权审批外，不应因恢复、重试、查询结果、循环、额度、队列投递或配置修复而把操作责任转交用户。模型应获得结构化失败事实、必要的检查／恢复能力和继续执行机会；无法完成时停止并报告已完成部分与未解决原因。

用户主动停止、暂停、修改任务或选择只规划属于用户意图，不应被自动恢复覆盖。初次配置页面、查看日志和用户主动发起的文件管理不因存在按钮而构成违规。Android／服务端拒绝、登录凭据缺失、不可访问模型以及用户已设额度仍是实际限制；无可用路径时如实结束，不绕过授权、不凭空生成“模型判断”，也不悬挂成待人工维修任务。

## 已确认差距

以下行号对应本次工作树，后续修改可能移动。

| 优先级 | 触发与当前行为 | 源码依据 | 应收敛方向 |
| --- | --- | --- | --- |
| P1 | 自动化会话每 10 次尝试强制 checkpoint 暂停；模型收到要求用户到权限页确认恢复的指示。30 次动作或 5 分钟上限也会终止该自动化会话。不是 Dispatcher 的逐工具审批 | `AutomationSessionManager.kt:206-249`；`AutomationTools.kt:191-204,289-290`；developer `AutomationModule.kt:119-141` | checkpoint 改为模型自检；确需授权的目标／范围变化进入统一工具审批，已有授权内恢复不再去设置页确认；保留用户 Stop 与权限撤销 |
| P1 | 非 COMPLETED Turn 一律解除 Goal 自动续跑，review 也解除激活。失败后的模型恢复没有统一接续入口 | `SessionWorkScheduler.kt:43-80`；`GoalRunSettlement.kt:119-155` | 将用户停止、不可恢复终止与可恢复失败区分；为后者建立持久去重的自动恢复准入，沿用 Engine，不能简单对全部终态自动重试 |
| P1 | UNKNOWN 核查契约仍以人工 decision 为准，缺少 review 行会报 `INCOMPLETE_REVIEWS`。源码未找到注册给模型的核查结算工具 | `ReviewResolutionSupport.kt:158-167`；`ToolEffectReviewService.kt:67-82`；`ToolCallReviewRepository.kt:12-23`；ADR-AGENT-001 §5 | 模型可检查并提交带来源／依据的恢复判断，或明确放弃；原 UNKNOWN 与 executor 结果保留，不将模型推测伪造成回执。服务方法存在不等于生产 UI 已有三种核查按钮，本审查不作该推断 |
| P1 | 最终网络／认证失败后，恢复路径是用户去模型设置修复再重试；能力缺失是用户去权限页操作；普通错误是手动重试 | `RecoveryFactsProjection.kt:188-247`；`TurnRecoveryActions.kt:38-82`；`TurnRecoveryPanel.kt:120-195` | 模型／平台先做现有授权内恢复与替代；所需授权走工具审批；不能修复时结束并报告，不能只删按钮留下死等状态 |
| P1 | 工具重复无进展被映射为 `INPUT_REQUIRED(TOOL_LOOP_NO_PROGRESS)`；预算恢复依赖“从已有结果继续”或编辑预算，不能保证模型自主收尾 | `GoalRunSettlement.kt:140-148`；`BudgetContinuation.kt:12-31`；`TurnRecoveryPanel.kt:181-194`；`GoalSettingsSection.kt:60,91-104` | 先把循环证据反馈模型，允许改变方案或放弃；在既有总额度内自动接续局部窗口。总额度不能静默扩大，无额度时明确结算，不要求用户维修额度 |
| P1 | 投递失败、未被消费的 Steer、配置／来源变化会将输入置为 NEEDS_ATTENTION；调度仅消费 PENDING，界面提供手动恢复 | `SessionWorkScheduler.kt:54-66,102-110,118-130`；`ChatService.kt:3349-3397,3465-3486`；`SessionInputQueuePanel.kt:426-428` | 按原因自动重验证／迁移到合法后继或明确结束。不得把因用户 Stop 停泊的输入擅自重新发送；来源／目标改变需要的授权纳入唯一审批入口 |
| P2 | 订阅与 PRoot 的查询、取回结果、确认回执失败后的重试主要由界面回调触发，没有纳入模型恢复闭环 | `SubscriptionRecoveryActions.kt:28-55`；`ChatRecoveryActions.kt:26-91,111-182`；`ProotAcknowledgementActions.kt` | 对账／查询／结果回收由自动恢复执行；用户仍可查看结果，查看按钮不承担启动恢复的责任 |
| P2 | 提示词仍允许把无法安全协调的文件冲突或 blocker 交给用户解决，和新规则冲突 | `prompts/base.md:4`；`prompts/files.md:10`；`AutomationTools.kt:200,289-290` | 改为自主检查、在权限内补救、可解释放弃；提示词修改不能替代恢复工具与调度实现 |

路径入口：[自动化](../../../tools/automation/src/main/kotlin/com/helix/tools/automation/AutomationSessionManager.kt)、[调度](../../../app/src/main/kotlin/com/helix/app/chat/SessionWorkScheduler.kt)、[核查结算](../../../app/src/main/kotlin/com/helix/app/engine/ReviewResolutionSupport.kt)、[恢复投影](../../../app/src/main/kotlin/com/helix/app/ui/RecoveryFactsProjection.kt)、[Goal 结算](../../../app/src/main/kotlin/com/helix/app/agent/GoalRunSettlement.kt)、[队列](../../../app/src/main/kotlin/com/helix/app/ui/SessionInputQueuePanel.kt)、[Runtime 恢复](../../../app/src/main/kotlin/com/helix/app/chat/ChatRecoveryActions.kt)、[提示词](../../../app/src/main/resources/prompts/base.md)。

## 不是工具审批的其他确认入口

- `helix.settings` 的 propose 返回 `AWAITING_USER_CONFIRMATION`、`applied=false`，在 Turn 空闲后另弹配置确认框（`HelixSettingsTool.kt:107-120`、`HelixSettingsProposalDialog.kt:69-89`）。如果配置修改是完成当前任务所必需，这仍会把执行移交人工；应变为真实配置操作的工具审批，而非工具“成功”后另等点击。
- 附件／敏感内容外发在模型请求前进入 `PendingConfirmation`（`ChatService.kt:2347-2362`），不是工具审批。应保留真实目标与内容身份绑定，把它纳入统一授权交互或预先授权的会话规则，不能为减少人工而静默批准外发。
- Plan 的“审阅后执行”是用户主动选择模式的语义（`PlanReviewDialog.kt:32-78`），不能为追求自动化而偷偷从只读 Plan 开始写操作。只要求计划时输出计划即可结束；用户已要求执行的任务不应额外被转进计划确认流程。

## 实施顺序与验收

1. 更新执行／Goal／权限 ADR 的当前决定，定义唯一允许等待人工的状态为工具授权审批；旧人工 review、预算与显式续跑条款不能和新要求同时充当 authority。
2. 在现有 Engine／SessionWorkScheduler 内接入自动恢复准入、持久去重、原 executor 对账和模型检查／恢复／放弃能力，不增加第二个执行器。
3. 迁移自动化 checkpoint、Runtime 回收、队列与网络恢复；把配置／外发等确需授权的动作收敛到审批，其他恢复 UI 改成状态展示。
4. 最后更新提示词、文案与测试；不能靠屏蔽 NEEDS_REVIEW、强制写成功或无限新建 Turn 达标。

退出条件：所有非审批的可恢复问题均实际到达模型／平台恢复路径；不可恢复情况明确终结，不等待人工处理。覆盖超时前后部分副作用、原 executor 未退出、重复通知、进程重开、用户 Stop、权限撤销、模型不可用、额度耗尽、无进展和恢复再失败。测试断言同时检查“不出现人工恢复请求”与“未越权／未重复执行／未假报成功”。
