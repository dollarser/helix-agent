# ADR-PLATFORM-005: 本机备忘与通知提醒

Status: accepted
Date: 2026-10-19
HXA: HXA-309
Deciders: Project owner（知识库后继续备忘/任务提醒）；实现 Agent（有界方案）

## Context

现有 Goal checkpoint 使用 WorkManager 发延后通知，不是个人备忘或精确定时器。Owner 要求本地创建、查看、编辑、完成/取消、日期与合理重复、通知点击回到条目，同时不提前实施完整 Harness 重构或后台 Agent 执行。

## Decision

- 独立备忘/提醒页面与 `reminders.db` Room 持久领域，不把个人条目伪装成已有 Turn/Goal。纯备忘无需时间；定时条目保存原始本地日期、IANA 固定时区、一次/每日/每周/每月规则及下一发生时间。指定时区不跟随设备旅行变化；月末按原始锚点截到短月份月末，后续恢复原日；DST 缺口向后平移，重叠用较早偏移。界面展示实际偏移及重复含义，明确保存才创建。不加猜测式自然语言创建，知识库文本不能授权创建任务。
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
- [Todoist dates](https://www.todoist.com/help/todoist/features/schedule-a-date-and-time-for-your-todoist-tasks-q7VobO)、[recurring dates](https://www.todoist.com/help/todoist/features/introduction-to-recurring-dates-YUYVJJAV)：日期与重复含义先确认，借鉴条目编辑/完成模型；不引入云端账号和自动 NLP。
- [TickTick recurring tasks](https://help.ticktick.com/articles/7055782206349770752)、[reminders](https://help.ticktick.com/articles/7055782395743567872)：日期/重复配置可见；本阶段不做循环强响、多提醒、位置或全屏闹钟。

只参考官方行为和产品设计，没有复制参考实现。

## Consequences

到点通知不依赖模型或聊天活跃状态，条目可在拒绝通知时继续保存为备忘。重启后在用户解锁、凭据加密存储可用时恢复。近似时延、强停恢复和未知发布状态需要诚实显示；可用性不等于告警级 SLA。

## Verification

[HXA-309](../../development/tasks/HXA-309.md) 维护范围与主机/当前授权设备合成验证；accepted 不代表测试通过。

## Reconsider when

Owner 要求自然语言解析、复杂重复、TTS 播报、日历同步或实际定时 Agent 执行时扩展对应契约，不能从普通通知自动扩权。

## References

官方资料见上；[通知相关 Goal 契约](../goal/001-lifecycle-and-completion.md)、[Turn 执行身份](../agent/001-turn-coordination.md)、[实施状态](../../development/status.md)。

## Decision history

- 2026-10-19：Owner 授权按顺序推进备忘/任务提醒与未来定时执行生命周期设计；接受本机日历规则、AlarmManager/WorkManager 分工和诚实发布/错过事实，本阶段不运行定时 Agent。
