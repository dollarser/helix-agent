# ADR-PLATFORM-005: 本机备忘与通知提醒

Status: accepted
Date: 2026-10-19
HXA: HXA-309
Deciders: Project owner（知识库后继续备忘/任务提醒）；实现 Agent（有界方案）

## Context

现有 Goal checkpoint 使用 WorkManager 发延后通知，不是个人备忘或精确定时器。Owner 要求本地创建、查看、编辑、完成/取消、日期与合理重复、通知点击回到条目，同时不提前实施完整 Harness 重构或后台 Agent 执行。

## Decision

- 本机 Agent 工具统一使用 `device.*`。Owner 授权参考 WorkBuddy 迁移且不兼容旧工具；个人通知提醒提供 `device.create_reminder/list_reminders/cancel_reminder`，不是系统 Clock 的响铃闹钟。创建只接受明确未来时间及 UTC offset，一次性近似通知，不从工具描述、外部文本或 ASR partial 获得授权；进入普通 Dispatcher/Policy/Approval 链路后复用本服务。当前工具不替换原有手动编辑与重复提醒能力。
- 查询最多返回 50 个活动定时条目及实际 scheduling 状态，返回 `hasMore`，更多条目通过现有提醒页查看。取消必须在同一会话先查询准确 ID/revision；服务在同一 mutation 边界重检持久状态/修订后，先保存 CANCELLED 再撤销投影。进程恢复后必须重新查询；查询快照不是持久授权，不改写旧审计或重放调用。创建身份绑定原会话、Turn、调用 ID，重复执行不会分配第二条身份；失败后需核对实际持久状态，不能把异常视为回滚或自动重试。
- 独立备忘/提醒页面与 `reminders.db` Room 持久领域，不把个人条目伪装成已有 Turn/Goal。纯备忘无需时间；定时条目保存原始本地日期、IANA 固定时区、一次/每日/每周/每月规则及下一发生时间。指定时区不跟随设备旅行变化；月末按原始锚点截到短月份月末，后续恢复原日；DST 缺口向后平移，重叠用较早偏移。界面展示实际偏移及重复含义，明确保存才创建。可选自然语言起草同样必须经用户核对与保存，知识库文本或模型提案不能授权创建任务。
- 自然语言入口只处理用户本次描述，用户选择既有 API/设备内模型并确认内容去向，发送描述、固定参照时间和时区；不读取/上传提醒库、聊天或知识库，不使用订阅 CLI。确认绑定 Provider 配置与目标，执行前变更使请求失效。模型无工具能力，只能返回严格有界的提案或澄清问题；缺少准确时间、含糊时段、不支持的重复不能静默补成可执行安排。提案仅有现有规则字段；拒绝未知字段、错误类型、过去时间、无效时区以及 NLP 阶段含糊的 DST 缺口/重叠。用户仍可在原有编辑页手动选择并核对现有日历解析规则。
- 自然语言提案不落库、不安排投影，进入编辑页时清楚标未保存，用户可修改后保存；关闭、取消或失败没有创建效果，也不自动模型重试。新编辑器预分配稳定身份，保存重试沿用该身份；未持久提案没有完成/取消已有条目的操作。此入口不改变提醒发布、错过、未知和恢复契约。
- AlarmManager RTC_WAKEUP + PendingIntent 接收器用于短通知。默认 `setAndAllowWhileIdle` 近似提醒；用户选精确且已授予 `SCHEDULE_EXACT_ALARM` 时使用 `setExactAndAllowWhileIdle`。不声明自动授予的 USE_EXACT_ALARM、不启动常驻服务、不绕过省电或勿扰。不获授权仍保存并近似安排，状态诚实可见。
- 通知权限和渠道开关按需引导；关闭记 NOTIFICATIONS_OFF/BLOCKED，不说已送达。系统 `notify` 接受后仅称 POSTED，不推断用户已看到。通知内容设私密，点击仅打开对应持久条目，不执行工具或模型。
- WorkManager 12 小时有界核对及启动/重启/覆盖安装/时钟或时区/精确权限授予事件用于恢复投影，不当精确定时器。AlarmManager 仍受 Android 配额、Doze、OEM 与权限限制；force-stop 后需用户重新打开才能恢复，不能承诺永不漏提醒。精确权限撤销会停止应用并取消精确闹钟，后续恢复时按当前权限降为近似。
- 修订号与发生时间构成回调身份；Alarm PendingIntent 以条目 ID 稳定定位，载荷保存修订/应到时间，因此清空下一时间后仍能恢复撤销。编辑/完成/取消先持久变更再撤销旧投影，旧或重复回调不得发布通知。恢复也撤销非活动条目的闹钟和通知。完成/取消结束整条重复，编辑可重新设置未来提醒。未修改日期规则的正文编辑保留原下一发生项，不能意外重发已处理通知。
- 发生项超过 15 分钟记 MISSED，重复条目合并跳过较晚区间，安排下个未来发生项，避免通知风暴；较短延迟可发布，事件保存应到/实际记录时间。POSTING 先落库再发布；异常/进程死亡后只凭相同条目/修订/发生身份的系统活动通知确认 POSTED，否则 UNKNOWN 并前进，不自动重放。关闭通知后重新启用不会补发已处理旧项。
- AlarmManager 与 WorkManager 只是可重建投影；条目与发生结果由 Room 负责。串行边界仅限本提醒服务的本机状态及通知发布，不建立全工具/文件全局锁。数据库不做旧预览迁移/兼容，也不删除既有会话、来源、用户设置。
- 未来定时 Agent 执行身份、去重、取消、未知副作用与结果归属归 [ADR-GOAL-001](../goal/001-lifecycle-and-completion.md)；本阶段没有模型/工具后台执行、远程调度或账号。

## Alternatives considered

不把 WorkManager 延迟当精确到点；不为普通提醒保活或使用 AlarmClock/全屏打断。仅一次和日/周/月日历重复先覆盖常用场景，不加入复杂 RRULE、多个预提醒、位置提醒或从完成时间重复。保留已有 Goal checkpoint 路径，其状态属于 Goal，不与个人条目混合。

## 官方参考与产品取舍

- [Android alarms](https://developer.android.com/develop/background-work/services/alarms)：AlarmManager 外部进程生命周期与精确权限、Doze、开机重建边界；采用短接收器通知，不运行长任务。
- [Android 14 exact alarms](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)、[notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)、[Android 15 stopped state](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state)：不假设默认权限或强停后自动运行。
- [Work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)：用于可延后重建，而非承诺准点。
- [Todoist dates](https://www.todoist.com/help/todoist/features/schedule-a-date-and-time-for-your-todoist-tasks-q7VobO)、[recurring dates](https://www.todoist.com/help/todoist/features/introduction-to-recurring-dates-YUYVJJAV)：借鉴识别结果可见、可修改、保存才生效；采用可选所选模型提案，不引入其账号、全部语法或默认时段猜测。
- [TickTick recurring tasks](https://help.ticktick.com/articles/7055782206349770752)、[reminders](https://help.ticktick.com/articles/7055782395743567872)：日期/重复配置可见；本阶段不做循环强响、多提醒、位置或全屏闹钟。

只参考官方行为和产品设计，没有复制参考实现。

## Consequences

到点通知不依赖模型或聊天活跃状态，条目可在拒绝通知时继续保存为备忘。重启后在用户解锁、凭据加密存储可用时恢复。近似时延、强停恢复和未知发布状态需要诚实显示；可用性不等于告警级 SLA。

## Verification

[HXA-309](../../development/tasks/HXA-309.md) 维护提醒范围，[HXA-313](../../development/tasks/HXA-313.md) 维护自然语言提案与确认边界的主机/当前授权设备及合成真实模型验证；accepted 不代表测试通过。

## Reconsider when

Owner 要求自然语言解析、复杂重复、TTS 播报、日历同步或实际定时 Agent 执行时扩展对应契约，不能从普通通知自动扩权。

## References

官方资料见上；[通知相关 Goal 契约](../goal/001-lifecycle-and-completion.md)、[Turn 执行身份](../agent/001-turn-coordination.md)、[实施状态](../../development/status.md)。

## Decision history

- 2026-10-21：Owner 授权尽可能参考 WorkBuddy 迁移本机工具并明确删除旧入口。接受 `device.*` 统一目录、通知提醒与响铃闹钟分开、先查询准确身份再取消；保留原提醒持久化和人工编辑契约。设备工具迁移范围与验证见 [接入说明](../../development/device-tools-integration.md)。
- 2026-10-19：Owner 授权按顺序推进备忘/任务提醒与未来定时执行生命周期设计；接受本机日历规则、AlarmManager/WorkManager 分工和诚实发布/错过事实，本阶段不运行定时 Agent。
- 2026-10-19：Owner 授权在知识库检索问答之后实施确认式自然语言提醒。接受所选模型、明确发送范围、提案/澄清与最终用户保存分离；不自动授权工具、安排通知或推断含糊时间，保留现有本机提醒与通知契约。
