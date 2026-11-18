# Bug Fix: Goal 提醒身份、导航与生命周期缺陷收口

Status: fixed
Date: 2026-09-06
Related HXA: HXA-102

以下按独立缺陷保留当时的原因、失败与验收范围；各节的 fixed 只指原候选，不合并测试计数或把后续通过回填到早期失败。当前契约仍查主题 ADR。

## Problem

<a id="case-2026-09-06-goal-reminder-identity-and-cancel"></a>

### Goal 提醒共享点击目标且取消后仍显示通知

Affected modules: app

所有 Goal 通知都用 requestCode=0、相同 Component 构建 PendingIntent，只有 extras 不同。Android 匹配 PendingIntent 时不比较 extras，FLAG_UPDATE_CURRENT 会更新同一个对象。通知本身只按 Goal ID 的 hash 数值区分，也存在不同 ID 的 hash 冲突。取消提醒只取消 WorkManager unique work，已经发布的通知没有移除。

<a id="case-2026-09-06-goal-reminder-navigation-lifecycle"></a>

### Goal 提醒导航早于导航栈就绪及重建重复消费

Affected modules: app, core/storage

原提醒 Intent 没有生产导航消费入口。补接后，真实 Activity 重建测试触发 `Cannot navigate to sessions. Navigation graph has not been set`；仅打开一次通过的结果没有覆盖初始化竞态。提醒请求若不记录消费状态，也会在 Activity 重建时再次打开。

<a id="case-2026-09-06-goal-repeat-notification-routing"></a>

### 已打开应用未重新消费同一 Goal 提醒

Affected modules: app

关闭提醒并重建 Activity 后，再次发送生产通知 PendingIntent，目标没有重新打开。新增 API 34 回归在未修改实现上失败，提示 New notification click was not consumed。

<a id="case-2026-09-06-goal-reminder-publication-cancellation"></a>

### 取消或替换提醒后旧 Worker 仍可发布通知

Affected modules: app

原生产路径调用 cancelUniqueWork 后立即取消系统通知，但取消操作异步且不会保证正在执行的 Worker 停止。Worker 若随后调用 notify，会重新显示已经取消的提醒；替换为未来检查点也可能保留旧通知。

<a id="case-2026-09-06-goal-checkpoint-recovery-replay"></a>

### Goal 恢复无条件替换同一检查点并重复提醒

Affected modules: app

恢复协调器只看到非空 nextCheckpoint 就 REPLACE，即使同一检查点的 work 仍待执行或已经成功结束。成功检查点也没有消费路径。

<a id="case-2026-09-06-goal-deletion-reminder-cleanup"></a>

### Goal 删除遗留提醒并可移除活动 run 状态

Affected modules: app, core/storage

PrivacyDeletionService.deleteGoal 原来直接删除 Goal/audit/plan，既不取消 WorkManager/系统提醒，也不检查是否存在活动 run。删除后的旧提醒恢复请求会抛出目标不存在异常。

## Impact

### Goal 提醒共享点击目标且取消后仍显示通知

多个目标的提醒可能共享最后更新的点击目标，hash 冲突时通知相互覆盖；用户取消提醒后仍可能看到已发布通知。

### Goal 提醒导航早于导航栈就绪及重建重复消费

点击提醒可能无法定位目标或导致应用崩溃；关闭提醒后的重建可能重新展示已关闭的目标。

### 已打开应用未重新消费同一 Goal 提醒

用户再次点击同一目标提醒时可能只回到应用，未重新进入该目标。

### 取消或替换提醒后旧 Worker 仍可发布通知

用户移除提醒或 Goal 进入需要取消提醒的状态后，仍可能收到旧通知。

### Goal 恢复无条件替换同一检查点并重复提醒

恢复重复创建 work；成功提醒可再次排入并重发，Goal 中已处理检查点长期残留。

### Goal 删除遗留提醒并可移除活动 run 状态

目标删除后仍可能留下通知或待执行提醒；运行中的 Goal 可失去预算、恢复和结算所需的持久记录；旧恢复快照可能中断后续提醒同步。

## Root cause

### Goal 提醒共享点击目标且取消后仍显示通知

把 Intent extras 当作 PendingIntent 身份，以及把 hash 当作唯一标识。任务队列取消和 NotificationManager 已发布通知的取消是不同操作。

### Goal 提醒导航早于导航栈就绪及重建重复消费

Compose LaunchedEffect 开始不代表 NavHost 已经安装 graph。Activity 的启动请求也不是一次性的 UI 事件，必须区分新的 onNewIntent 与旧请求的重建。

### 已打开应用未重新消费同一 Goal 提醒

通知 Intent 未明确指定已有 Activity 的复用及重新投递方式。onNewIntent 已实现重置消费状态，但原默认启动路径未满足这个回归场景的重新打开要求。失败前后仅修改启动标志，实际 PendingIntent 回归验证了该路由修复；不据此宣称所有 Android 启动模式都有同一问题。

### 取消或替换提醒后旧 Worker 仍可发布通知

把 WorkManager 取消请求当作已完成取消，并且没有序列化发布和通知撤回。官方文档明确取消是 best-effort，已经执行的 work 可能继续，见 [WorkManager API](https://developer.android.com/reference/androidx/work/WorkManager.html)。

### Goal 恢复无条件替换同一检查点并重复提醒

调度只计算相对延迟，未携带绝对检查点身份，也未比较既有 WorkManager 状态；Goal 恢复不消费成功结果。

### Goal 删除遗留提醒并可移除活动 run 状态

将删除仅视为数据库行删除，遗漏提醒生命周期和正在执行的 run；恢复路径则假定此前列出的目标始终存在。

## Fix and invariants

### Goal 提醒共享点击目标且取消后仍显示通知

Intent data 使用按完整 Goal ID 编码的独立 URI，保持 FLAG_IMMUTABLE；通知使用完整 Goal ID 作为 tag，与稳定数值 ID 共同定位。生产 GoalReminderScheduler.cancelReminder 同时取消 unique work 和本 Goal 已发布通知，并清理旧版本同数值 ID 的无 tag 通知。没有增加模型调用、Tool 执行或自动 Continue。

### Goal 提醒导航早于导航栈就绪及重建重复消费

从持久化绑定查询唯一会话；Intent 的 URI 与 extra 只提供 Goal ID，不接受外部传入的会话绑定。导航协程等待第一个 back-stack entry，再打开会话页面。统一首次启动和 onNewIntent 的消费入口，并保存已消费 ID；onNewIntent 才重新接收一次用户点击。保持原始启动 Intent 身份不变。打开提醒仅导航，不新建 run/Turn、不调用模型或批准工具；显式 Continue 仍通过服务和 reducer。

### 已打开应用未重新消费同一 Goal 提醒

增加 FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP，复用现有 MainActivity 处理新的通知请求。Activity 重建仍不会重新消费旧请求，新的用户请求则进入 onNewIntent；不新增 Goal run/Turn，不修改预算或审批，也不自动继续。

### 取消或替换提醒后旧 Worker 仍可发布通知

当前单进程提醒路径使用统一发布临界区。发布前读取该 Work ID 的真实 WorkManager RUNNING 状态；生产替换/取消在同一临界区等待队列操作完成后取消对应通知。已经发布则后续取消移除，取消先完成则旧发布被拒绝。替换也撤回旧通知。操作等待上限 10 秒；失败、取消、超时不返回成功。

不增加新的持久化系统，不读取 Goal 状态，不改变模型、审批或执行边界。WorkManager 仍仅负责可延迟通知，运行必须用户显式继续。

### Goal 恢复无条件替换同一检查点并重复提醒

以 Goal unique work name 和绝对检查点 tag 匹配既有任务。相同检查点的非终态或 SUCCEEDED work 保留，不同检查点或失败/取消的 work 才替换。协调器读取匹配成功事实，在 Room 事务中重读 Goal，仅清除仍匹配的 RUNNING/PAUSED 检查点并记 SYSTEM audit。不得覆盖更新的检查点，不改变 Goal 状态、预算或 run，不自动 Continue。

Worker 不读 Goal 数据，既有 WorkManager 是任务状态依据。成功记录仅表示 Worker 正常结束，不表示用户看到、阅读或接受通知。

### Goal 删除遗留提醒并可移除活动 run 状态

GoalDeletionCoordinator 与 GoalReminderReconciler 共用串行边界。在同一 Room 事务中先要求目标不是 RUNNING 且所有 run 已关闭，随后等待提醒取消和通知撤回成功，最后删除既有 audit/Goal/无引用 plan。活动目标要求先停止；取消异常使数据库记录保留。删除成功后清除该目标的当前提醒查看请求。

恢复以可空查找处理被删除的 Goal，转为取消提醒，不因旧快照终止。相同串行边界防止已读取旧目标的恢复操作越过删除后重新排程。没有自动停止后重放、没有后台模型调用或权限变化。

## Alternatives considered

### Goal 提醒共享点击目标且取消后仍显示通知

只修改 requestCode 仍可能有 hash 冲突；仅修改 extras 无法区分 PendingIntent。只取消 WorkManager 不能撤回已经发布的通知，所以显式处理两种生命周期。

### Goal 提醒导航早于导航栈就绪及重建重复消费

固定延迟不能证明导航栈已就绪；捕获异常后忽略会丢失点击请求。修改 Intent data 会改变 Activity 启动身份，实际导致 ActivityScenario 无法匹配生命周期；改为保存独立消费状态。没有用移除重建断言来取得通过。

### 已打开应用未重新消费同一 Goal 提醒

清除已消费标识而不区分重建与新请求会重新打开已关闭提醒。每次新建 Activity 会累积导航实例且绕过既有 onNewIntent 路径。现明确复用方式，并同时断言 Activity 实例与 Goal/run 不变。

### 取消或替换提醒后旧 Worker 仍可发布通知

仅检查 isStopped 留下检查到 notify 之间的竞态。仅等待取消操作而不串行化发布，也不能防止已经通过检查的发布。加入内存取消 ID 集合无法覆盖进程重启；额外持久化取消代次会重复 WorkManager 已有状态。现复用既有持久任务状态和当前单进程同步边界。

### Goal 恢复无条件替换同一检查点并重复提醒

单纯 KEEP 在任务已完成时仍可新建 work，且不能区分新旧检查点；仅按 Goal ID 消费会错误清除用户更新后的检查点。按进程内集合去重不能覆盖恢复。现复用既有持久任务身份，不另建调度数据库。

### Goal 删除遗留提醒并可移除活动 run 状态

删除后才取消提醒会在进程死亡或取消失败时留下无主提醒。只检查 Goal 状态不足以防护仍有未关闭 run 的不一致状态，因此同时检查两者。直接删除活动 run 会破坏预算/副作用结算；本次沿用已有会话删除的先停止边界。

## Regression verification

### Goal 提醒共享点击目标且取消后仍显示通知

API 34 的 GoalReminderTest 3/3 通过，包括实际 WorkManager 发布、替换/取消，以及两个具有相同 hash 的 Goal 同时显示、PendingIntent 不同、取消一个后另一个仍在。consumer JVM 288 项中 285 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint、Spotless 通过。联合 Gradle 仍因既有 Detekt 53 项 exit 1。日志 `build/main-verification/goal-reminder-identity-gates.log`、`goal-reminder-identity-api34.log`，APK hash 见 `goal-reminder-identity-result.json`。

### Goal 提醒导航早于导航栈就绪及重建重复消费

当前 consumer APK，API 34：GoalReminderNavigationDeviceTest、GoalDialogDeviceTest、GoalRunCoordinatorDeviceTest 合计 13/13，通过后导航用例再次 2/2 通过。覆盖实际 Activity 入口、持久绑定会话、Goal/run/Turn 不变、关闭后重建不再发布打开请求。consumer JVM 285 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint、Spotless 通过。Detekt 仍有既有 53 项，未宣称全仓门禁完成。证据见 `build/main-verification/goal-reminder-navigation-result.json` 与 `goal-reminder-navigation-stable-api34.log`、`goal-reminder-navigation-repeat-api34.log`。

### 已打开应用未重新消费同一 Goal 提醒

API 34，未修改实现的导航 2 项中 1 项失败；修复后 2/2，再与真实通知及提醒/预算 UI 组合 7/7。测试直接发送生产 PendingIntent，并检查重新打开和同一 Activity 实例。consumer/测试 APK、Spotless、consumer Lint 通过；Detekt 仍既有 53 项，联合门禁不是全绿。

证据：`build/main-verification/goal-repeat-notification-result.json`、`goal-repeat-notification-before-api34.log`、`goal-repeat-notification-flags-api34.log`、`goal-repeat-notification-final-api34.log`。

### 取消或替换提醒后旧 Worker 仍可发布通知

API 34：三项实际 WorkManager 状态/系统通知的并发用例，加三项真实 GoalReminderWorker 发布、替换、hash 身份回归，共 6/6。并发用例控制迟到 publisher 调用同一生产发布门，覆盖取消后发布、替换后旧发布、发布进入临界区后取消；最终系统通知不存在。consumer JVM 291 项中 288 通过、3 条既有条件跳过，包括失败/取消/超时不误报成功。consumer/测试 APK、consumer Lint、Spotless 通过；Detekt 仍有既有 53 项。

命令、日志与 APK hash 见 `build/main-verification/goal-reminder-publication-result.json`、`goal-reminder-publication-gates.log`、`goal-reminder-publication-final-gates.log`、`goal-reminder-publication-api34.log`。

### Goal 恢复无条件替换同一检查点并重复提醒

API 34：GoalCheckpointDeviceTest 与原提醒/删除/并发用例合计 11/11。覆盖 pending Work ID 保留、变更替换、成功消费与重复恢复不重建、旧成功不清除新检查点。consumer JVM 291 项中 288 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint、Spotless 通过。Detekt 仍为既有 53 项。

命令与产物：`build/main-verification/goal-checkpoint-gates.log`、`goal-checkpoint-api34.log`、`goal-checkpoint-result.json`。

### Goal 删除遗留提醒并可移除活动 run 状态

API 34：GoalDeletionDeviceTest、GoalReminderPublicationDeviceTest、GoalRunCoordinatorDeviceTest 共 16/16。生产删除入口清理实际已发布/延迟提醒；隔离数据库覆盖活动 Goal 与未关闭 run 保留、关闭 run 后删除，以及取消异常保留 Goal。consumer JVM 291 项中 288 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint、Spotless 通过。Detekt 仍为既有 53 项，未声称全仓门禁通过。

证据：`build/main-verification/goal-deletion-result.json`、`goal-deletion-gates.log`、`goal-deletion-api34.log`。

## Residual risk

### Goal 提醒共享点击目标且取消后仍显示通知

本次覆盖通知身份和已发布通知的取消，不等于完整 Goal 提醒流程完成。检查点用户入口、Goal 状态同步、并发取消/发布、点击通知定位目标以及系统延迟/强停恢复仍待生产接线与验证。真机和长稳未运行。

### Goal 提醒导航早于导航栈就绪及重建重复消费

提醒按钮、通知栏到目标详情再显式 Continue 的完整 UI 流程以及 onNewIntent 独立设备断言仍需补齐；Worker 发布/取消竞态、检查点消费和删除清理未在本次收口。真机、长稳未运行。

### 已打开应用未重新消费同一 Goal 提醒

后续补充 API 34 实际通知栏节点点击：真实通知打开后选定 Goal 可见，状态/run/Turn 不变，相关组合 7/7；证据见 `build/main-verification/goal-notification-shade-result.json`。这不是全部 API 版本或进程外启动验收；其他 API 设备矩阵仍在整体待办中。完整真实模型流程和 ADR-GOAL-001 完成证据契约未在本次解决。真机、长稳未运行。

### 取消或替换提醒后旧 Worker 仍可发布通知

适用于目前的同进程 WorkManager 提醒路径；不声称跨进程临界区或终止任何模型/工具后端。进程在取消操作或通知撤回中间死亡仍依赖恢复重建；删除 Goal 的通知/队列清理与检查点消费仍需接线验证。系统可延迟或丢弃提醒，真机和长稳未运行。

### Goal 恢复无条件替换同一检查点并重复提醒

WorkManager 历史可能被系统清理；旧版本记录也没有检查点 tag。无法确认成功时保留 ADR-GOAL-001 的过期补发行为。通知发布与 Worker 成功记录不是跨系统事务，不能保证 exactly-once。真机、长稳和完整通知 UI 尚未在本组验收。

### Goal 删除遗留提醒并可移除活动 run 状态

删除中途进程死亡和完整删除 UI 尚未设备验收。取消已成功但删除事务尚未提交时若失败，Goal 仍在，后续恢复可能依据保留检查点重新安排提醒；这属于未完成删除，不返回成功。底层 repository 供存储/隔离测试使用，产品删除必须经过生产协调入口。真机和长稳未运行。

## Related records

- [ADR-GOAL-001](../adr/goal/001-lifecycle-and-completion.md)
- [HXA-102](../completion-records/HXA-102.md)
- [当时优化跟进](../evidence/development/main-optimization-todo.md)
