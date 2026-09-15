# HXA-201 验收证据与边界

日期：2026-09-16。源码基线：`aefc9789` 加本轮 HXA-201 修复；Harness 工作树包含其他所有方的并行 WIP，不把整个工作树当作本任务提交。

## 功能映射

| 要求 | 实现与验证 |
| --- | --- |
| 允许/询问/禁止与恢复默认 | 同一 ToolApprovalSettingsModel/PreferenceService；选择 GLOBAL、SESSION、WORKSPACE，恢复只删除选定范围，继承 DENY/ASK 不被较窄 ALLOW 覆盖 |
| 设置与执行一致 | 生产组合根将持久会话目录接入偏好 source；设置、工具曝光、Dispatcher 共用该 source；真实 Room 与执行器断言验证会话隔离和 Workspace 适用范围 |
| 来源与实际结果 | 设置行展示状态及规则来源，变更流刷新；卡片明确保存 GLOBAL，保存结果与本次批准分离；null/异常/失效契约不显示成功 |
| 旧卡与高风险 | 呈现 contractHash 随回调传递，旧契约 ALLOW 拒绝；动态风险 L2/L3 隐藏未来 ALLOW；保存 DENY 后批准旧卡不能执行 |
| UI 与生命周期 | 三语言、深色、2x 字体、360dp/宽屏响应式；真实 Activity recreate 恢复搜索/范围和持久设置；真实不同 PID 重启保留 ALLOW |
| 停止与审批回归 | HXA-200 Preference10、Scheduler13、Stop2，以及 ApprovalFlow3；停止 fixture 走真实 ChatService.send/stop，迟到批准不复活 |

## 主机门禁

JDK17，沿用当前锁定依赖，无真实账号或外部服务调用参数。以下 Gradle 命令 exit0（`build/hxa201-closeout/final-host3.log`）；缓存命中按日志保留，不声称每项强制重执行。

```sh
./gradlew spotlessApply spotlessCheck detekt \
 :core:model:test :core:policy:test :core:agent:test :tools:framework:test \
 :core:storage:testDebugUnitTest \
 :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest \
 :core:storage:assembleDebugAndroidTest \
 :app:assembleConsumerDebug :app:assembleDeveloperDebug \
 :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
 :app:lintConsumerDebug :app:lintDeveloperDebug --continue --console=plain
python3 scripts/debug/2026-09-16/summarize-hxa201-host.py
```

核心 model142/policy1177/agent271/framework164/storage89，共1843通过；app consumer529/developer555通过，各4项既有外部材料条件跳过。合计2927通过/8跳过/0失败。新增保存反馈2项、设置模型12项均见本次 XML。补充停止断言后，`spotlessCheck detekt` 和双flavor测试APK重新构建通过（`final-static4.log`），并用于上述最终设备矩阵。双 flavor Debug lint各0错误/0警告；构建含主APK及测试APK。汇总及APK SHA256：`build/hxa201-closeout/host-summary.json`。

## 四象限真实设备

```sh
python3 scripts/debug/2026-09-15/run-hxa201-acceptance.py
```

最终归档：`build/hxa201-device-20260916-002808/`，runner exit0。包括缺失会话边界和停止后未决定/未消费的直接断言。

| API / flavor | 普通阶段 | 同一安装跨进程重启阶段 |
| --- | --- | --- |
| 29 consumer | 52通过/1预期跳过/0失败 | 16通过/0跳过/0失败 |
| 29 developer | 52通过/1预期跳过/0失败 | 16通过/0跳过/0失败 |
| 36 consumer | 52通过/1预期跳过/0失败 | 16通过/0跳过/0失败 |
| 36 developer | 52通过/1预期跳过/0失败 | 16通过/0跳过/0失败 |

共272次通过、4次阶段性跳过。普通阶段 Settings16中的重启用例按协议跳过，随后 force-stop 保留数据并在新进程运行全类16项，PID必须变化且该用例必须通过。其余普通阶段为Lifecycle1/Card8/Preference10/Scheduler13/Stop2/ApprovalFlow3。重复阶段不是272个不同测试。

不使用每轮会卸载数据的 connectedAndroidTest 驱动恢复；直接 am instrument，保存原始 status、logcat、退出记录与空 adb-final。每轮拒绝已有设备，只结束自建只读 AVD 子进程。截图包括 `small-dark-large-font`、`selected-session`、`real-card-save-feedback`，与真实点击/Room/执行断言配对；窄屏截图外围空白是固定360dp测试容器外部，不代表产品全屏布局。宽度变化只证明响应式布局，Activity 重建由独立 recreate 用例证明，不混称设备旋转事件。

## 全套回归归因

匹配前置基线为 `git archive aefc9789` 加任务开始时的已跟踪补丁和未跟踪文件，独立生成 `build/hxa201-matching-baseline/`，不stash、不切换工作树。已逐文件确认：除本任务明确编辑的共享文件外，原158项并行改动保持捕获内容。本次测试与当前都使用 API29 consumer、直接 instrumentation、无外部服务参数。

捕获文件SHA256：`initial.patch` = `e31e075e3472156615a57ba06ff57f88fc71f1fbed37c5ea01d2a1d5d90113c5`；`initial-status.txt` = `5b50dbbedf4ea7a6fce0bec6246a09a06e6e1f82e21d1b352b31063aa8b58190`，位于 `build/hxa201-closeout/`。

| 完整运行 | 通过 | 条件跳过 | 失败 | 总用例 |
| --- | --- | --- | --- | --- |
| 匹配前置基线 `build/hxa201-matching-baseline/build/hxa201-device-20260916-004030/` | 347 | 26 | 19 | 392 |
| 当前 `build/hxa201-device-20260916-001915/` | 355 | 26 | 18 | 399 |

两次runner均因真实失败正确返回非零；完整套件仍红。18项当前失败全部在匹配基线复现，首个异常相同（JGit忽略随机安装路径），无新增失败用例。停止用例为唯一消除的基线失败；另有7个新增测试通过。Connector重建在这两次匹配运行均通过，但此前诊断有波动，不声称已作针对性修复。

原始对照：`build/hxa201-closeout/matching-full-comparison.json`。命令：

```sh
python3 scripts/debug/2026-09-15/prepare-hxa201-baseline.py hxa201-matching-baseline
# 将同一runner复制到匹配基线；在两个目录分别执行：
python3 scripts/debug/2026-09-15/run-hxa201-acceptance.py --full --api29 --consumer
python3 scripts/debug/2026-09-15/compare-hxa201-device-results.py \
 build/hxa201-matching-baseline/build/hxa201-device-20260916-004030/29-Consumer-full.txt \
 build/hxa201-device-20260916-001915/29-Consumer-full.txt
```

| 当前失败用例（类#方法） | 实际首个失败 | 后续归属 |
| --- | --- | --- |
| `chat.AttachmentE2eDeviceTest#remainingTokenBudgetBoundsTheActualWireRequest` | java.lang.AssertionError: expected:<1> but was:<0> | 既有回归分诊；不计为201通过 |
| `chat.AttachmentE2eDeviceTest#imageRetryIsBlockedWhenTheNormalizedArtifactIsTampered` | 视觉能力 gate 阻止发送，等待 egress disclosure 超时（与基线一致） | 既有回归分诊；不计为201通过 |
| `chat.AttachmentE2eDeviceTest#goalWakeTimeBudgetStopsAStalledModelAndParksInsteadOfCancellingTheGoal` | org.junit.ComparisonFailure: expected:<[PAUS]ED> but was:<[BLOCK]ED> | 既有回归分诊；不计为201通过 |
| `chat.AttachmentE2eDeviceTest#imageAttachmentCarriesTheNormalizedPayloadAndHistoryReResolves` | 视觉能力 gate 阻止发送，等待 egress disclosure 超时（与基线一致） | 既有回归分诊；不计为201通过 |
| `chat.AttachmentE2eDeviceTest#capabilitySnapshotChangeRevokesVisionUntilReprobed` | 视觉能力 gate 阻止发送，等待 egress disclosure 超时（与基线一致） | 既有回归分诊；不计为201通过 |
| `chat.AttachmentE2eDeviceTest#noPathUriOrBase64LeaksIntoPersistedOrUserVisibleState` | 视觉能力 gate 阻止发送，等待 egress disclosure 超时（与基线一致） | 既有回归分诊；不计为201通过 |
| `chat.GoalProcessKillDeviceTest#repeatedProcessDeathDoesNotReplenishGoalBudget` | org.junit.ComparisonFailure: expected:<[PAUS]ED> but was:<[BLOCK]ED> | 既有回归分诊；不计为201通过 |
| `provider.ProviderModelDiscoveryUiTest#aPhaseTwoFailureShowsTheStableErrorWithoutAModelSection` | androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 30000 ms | HXA-204/205 / 错误与准备体验 |
| `ui.ArtifactCenterDeviceTest#artifactsPageListsCompletedResultAndExposesActions` | androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 10000 ms | HXA-203/204 / 文件与交付 |
| `ui.ConversationComposerDeviceTest#englishGoalActionsHaveRoomAtDoubleFontSize` | java.lang.AssertionError: Failed to assert the following: (is enabled) | HXA-202 / 任务入口 |
| `ui.GitStatusDeviceTest#gitPageShowsStagedUnstagedUntrackedAndOpensDiff` | JGit SilentFileInputStream.readNBytes(I)[B 不存在（与基线一致） | HXA-192 / Git兼容性 |
| `ui.LiveGoalModelReportDeviceTest#realModelCompletesAnUnboundGoal` | java.lang.IllegalArgumentException: Required value was null. | 既有回归分诊；不计为201通过 |
| `ui.ManualSharedFileDeviceTest#userCanManageSharedFilesWithoutProviderAndDeleteRequiresConfirmation` | java.lang.AssertionError | HXA-203/204 / 文件与交付 |
| `ui.ProviderFlowTest#untestedAndFailedProvidersAreNotChatSelectable` | java.lang.AssertionError: Assert failed: The component with Text + InputText + EditableText contains '失败阶段：网络与认证' (ignoreCase: false) as substring is not displayed! | HXA-204/205 / 错误与准备体验 |
| `ui.ShareDraftUiDeviceTest#shareTextIntentPreFillsTheComposerAndNeverSends` | androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 15000 ms | HXA-203/204 / 文件与交付 |
| `ui.ShareDraftUiDeviceTest#shareImageIntentImportsAndStagesNeverSends` | androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 15000 ms | HXA-203/204 / 文件与交付 |
| `ui.TasksDashboardDeviceTest#tasksDestinationListsReadyPlanAndCancelLeavesTheQueue` | java.lang.AssertionError: Failed: assertExists. | HXA-202 / 任务入口 |
| `ui.TasksDashboardDeviceTest#planCancelThroughTheServiceUpdatesTheOpenDashboard` | java.lang.AssertionError: Failed: assertExists. | HXA-202 / 任务入口 |


证据纠正：最初的基线脚本从嵌套子目录运行git apply，导致已跟踪补丁被跳过；旧 `build/hxa201-baseline/` 实际是HEAD已跟踪文件加捕获的未跟踪文件，不能当匹配工作树。旧完整运行346/26/20和ProviderFlow隔离复现仅为诊断材料，不用于上述最终归因。脚本已改为从仓库根使用显式 `--directory`，重新生成、核对并运行匹配基线；不追认前次“22个失败类”的无原始证据数字。

第一次当前全套 `build/hxa201-device-20260916-001359/` 在发现缺失会话查询回归后被显式中止，不作完整套件证据；修复后完整重跑恢复相关用例，未删除或跳过。所有运行均使用自建独占模拟器并清理，原始status/logcat/退出记录留在对应目录。

## 验收边界

JGit TrustAll 已由 HXA-200 修复，本次双flavor lint仍绿。基线 GitStatus 的 `SilentFileInputStream.readNBytes(I)[B` 是 API29 上单独复现的兼容性失败，不是 TrustAll，不凭未改文件推断为依赖漂移。

未执行完整仓库 check-all --build/--all、真实账号/外部服务、Release设备、长稳或发布验收。任务范围是否完成以对应完成记录为准；本页保留可复核的局部和全套证据边界。
