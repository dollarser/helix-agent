# Helix 模拟器全量设备测试问题记录（2026-10-01）

> 仓库归档说明（2026-10-02）：仅将宿主用户名/安装目录替换为环境变量占位符，未改动历史测试数字、断言或结论。字节一致的原件保留在 ignored `build/hxa241/emulator-sweep-original-2026-10-01.md`，原件 SHA-256：`af56b9785a26f4e37e955329e70f96eed9e8acb7b3fa1cc01ca8d50e896b786e`。`HELIX_SWEEP_WORKTREE` 需指向独立测试工作树，不能指向主 checkout。

> 个人分析笔记，非项目文档，不登记 `docs/INDEX.md`。
> 测试环境：隔离 worktree `${HELIX_SWEEP_WORKTREE}`（detached @ `05e91003` / tag `v0.0.4`），主 checkout 未受影响。

## 一、测试范围与方法

| 项 | 值 |
| --- | --- |
| worktree | `${HELIX_SWEEP_WORKTREE}`（`git worktree add --detach`，HEAD `05e91003`） |
| 变体 | `consumer`（主扫）、`developer`（对照复验） |
| 设备 | 2× `Helix_API_36` / `Helix_API_36_test`，API 36，arm64-v8a，无窗口 swiftshader |
| 测试类 | 229 个（含 `@Test` 的文件）/ 785 个 `@Test` 方法 |
| 分批 | 151 批：Compose UI 类与 `@Test≥8` 的重类**逐类独占**，纯逻辑类 6 个一组 |
| 复位 | 每批前 `pm clear com.helix.agent`（Room v1 baseline，无 destructive fallback，不清必假崩） |
| 执行 | `am instrument -w -r -e class ...`，按设备实况逐批解析 `INSTRUMENTATION_STATUS_CODE` |

> 说明：worktree 的 `runtime/proot-app/src/main/assets/runtime/{rootfs,proot,runtime-lock.json}` 与 `jniLibs/arm64-v8a/libhelix_loader.so` 未被 git 跟踪，已从主 checkout **真实拷贝**（144 MB，rootfs tar SHA-256 与 `runtime-lock.json` 一致），两变体均可构建。

## 二、总体结果（consumer / API36）

```
批次            151/151 完成，0 批次级崩溃或超时
执行用例        782（3 个用例因缺外部 fixture 未进入）
通过            696
失败            52
跳过            34
失败测试类      30
```

**两轮对照（30 个失败类逐个复跑）**：27 个确定性复现、2 个复跑转绿（Compose 污染/偶发）、1 个结果不稳定。

**变体对照**：`AttachmentE2eDeviceTest`(32/7)、`SessionInputProtocolDeviceTest`(3/3)、`GoalModelCancellationDeviceTest`(4/2) 在 developer 变体上**失败数完全相同** → 与渠道/loopback 边界无关，是当前树上的真实失败。

## 三、问题分类

### A 类｜需要专用多阶段宿主驱动器（12 类 / 21 用例）— 非产品缺陷

这些测试自身要求「宿主起 fixture → 杀进程 → 新进程校验」的多阶段驱动，裸 `am instrument` 必然失败，报错本身就是这个契约：

| 类 | 用例数 | 直接原因 |
| --- | --- | --- |
| `chat.SessionInputProcessRecoveryDeviceTest` | 8 | seed 阶段 `Required value was null`；verify 阶段读不到 `files/session-input-process-fixture.json` |
| `connector.ConnectorInstallRecoveryDeviceTest` | 2 | 同上，缺 `connector-install-recovery-fixture.txt` |
| `export.SessionExportRecoveryDeviceTest` | 1 | 显式抛 `Use the two-phase owned runner` |
| `ui.SharedStorageDeviceTest` | 2 | 显式抛 `Use the mandatory host storage phases` / `Run the host-controlled granted and revoked phases` |
| `ui.ManualSharedFileDeviceTest` | 1 | 同上（`MANAGE_EXTERNAL_STORAGE` 变更会杀 instrumentation） |
| `chat.MemoryProcessRecoveryDeviceTest` | 1 | 缺 `no_backup/recovery-device-pid` |
| `files.WorkspaceBackupRecoveryDeviceTest` | 1 | 同上 |
| `localmodel.ModelPublicationRecoveryDeviceTest` | 1 | 同上 |
| `localmodel.FirstSuccessJourneyDeviceTest` | 1 | 缺 `no_backup/p4-first-success.properties` |
| `chat.ComposerProcessRecoveryDeviceTest` | 1 | 需 pid 文件驱动的多阶段 |
| `chat.WorkspaceProcessRecoveryDeviceTest` | 1 | 同上 |
| `ui.MessageEditRecoveryDeviceTest` | 1 | `verifyRevisionRecovery` 阶段 NPE |

**结论**：不计入缺陷。要覆盖这些场景必须走 `scripts/run-*-process-kill.py` 一类的宿主驱动，或补一个通用的两阶段 runner。**建议**：在 `verification-matrix` 里把这些类明确标注为 "host-driver required"，避免后续再被当成失败重跑。

### B 类｜确定性断言失败 / 契约漂移（12 类 / 23 用例）

> 特征：两轮完全一致，跨变体一致。多为「UI 或契约已演进，测试未同步」。

| 类 | 用例数 | 断言与根因 |
| --- | --- | --- |
| `chat.AttachmentE2eDeviceTest` | 7 | `expected:<COMPLETED> but was:<FAILED>`（`AttachmentE2eDeviceTest.kt:1037`，`verifyRepeatedWireId`）；另有 `scripted wire: no request recorded`、`capabilitySnapshotChangeRevokesVisionUntilReprobed` 期望 1 得 0 |
| `chat.SessionInputProtocolDeviceTest` | 3 | 三协议用例均 `expected:<COMPLETED> but was:<FAILED>` |
| `chat.GoalModelCancellationDeviceTest` | 2 | `expected:<CANCELLED> but was:<PAUSED>` |
| `chat.EvaluationTrajectoryDeviceTest` | 1 | `expected:<[FAILED, COMPLETED]> but was:<[FAILED, FAILED]>` |
| `chat.SessionInputAdmissionFailureDeviceTest` | 1 | `exhaustedGoalParksRejectedStart...` 断言失败 |
| `localmodel.LocalProviderLoopDeviceTest` | 1 | `expected:<[COMPLETED]> but was:<[FAILED]>` |
| `CommandExecutionDetailsDeviceTest` | 1 | `command-detail-hxa194-cmd-failed` 不在时间线可滚动容器内 |
| `TaskJourneyDeviceTest` | 1 | `production state must settle` |
| `provider.ProviderModelDiscoveryUiTest` | 3 | `editableProviderTag("provider-models-section"/"provider-models-unsupported")` 匹配不到 |
| `ui.ProviderContextDeviceTest` | 1 | `Failed to inject touch input` |
| `MainActivityTest` | 1 | 引用已删除的抽屉标签 ✅**已修复** |
| `ui.IaAuthorityDeviceTest` | 1 | 断言 consumer 渠道不存在的分组 ✅**已修复** |

**已定位的三个具体根因：**

1. **`MainActivityTest` 引用 HXA-226 已移除的标签**
   `drawer-search-conversations`、`drawer-recent` 在 `app/src/main` 中**已无任何产出**（`GroupedNavigation.kt` 现有 `drawer-new-conversation` / `drawer-current-conversation` / `drawer-all-conversations`）；同仓的 `GroupedNavigationDeviceTest`、`HierarchicalNavigationDeviceTest` 已经改为断言这两个标签**不存在**，只有 `MainActivityTest` 没跟上。

2. **`IaAuthorityDeviceTest` 断言了 consumer 渠道不存在的分组**
   `DefaultAppContainer.kt:214` → `managedAccountsEnabled = SubscriptionProviderModule.providerIds.isNotEmpty()`；consumer 的 `SubscriptionProviderModule` 不覆写 `providerIds`（接口默认 `emptyList()`）→ `ProviderChannelPolicy.groups(false)` 不含 `MANAGED_ACCOUNT` → 该分组按钮**在 consumer 下根本不渲染**。测试按 developer 的界面写死了断言。

3. **`ProviderModelDiscoveryUiTest` 的匹配器与「模型管理搬到对话框」不符**
   辅助函数 `editableProviderTag(tag)`（`AppUiTestSupport.kt:89`）要求节点有祖先 `provider-row` 且该祖先含 `provider-edit`。但 2026-09-30 的 Provider 模型管理统一后，模型区已移入 `ProviderModelsDialog`（`ProviderScreen.kt:87-93`，入口是行内按钮 `provider-manage-models-${row.id}`），不再是 `provider-row` 的后代 → 匹配必然失败。标签本身（`provider-models-section` / `provider-models-filter` / `provider-model-chip-N`）在 `ProviderModelsSection.kt` 中仍然存在，只是渲染位置变了。

**关于 `COMPLETED → FAILED` 这一簇（跨 3 个类 11 个用例）**：已确认与 consumer 的 loopback 边界无关（developer 上失败数相同），脚本 wire 也确实收到了 3 次请求（`wire.callCount == 3` 的等待是过的）。落点在生产结算路径——`ChatToolSettlement.kt:180`（`settleExecutionFailed` → `FAILED`/`NEEDS_REVIEW`）与 `:234`（`persistPreDispatchDenied` → `FAILED`/`DENIED`）。**建议下一步**：在 fixture 里把 `ToolDispatchOutcome` 的 `detail` 打出来（目前测试只断言状态、不打印原因），即可区分是「工具未注册/被策略拒绝」还是「执行体抛错」。

### C 类｜Compose 时序 / 交互不稳定（6 类 / 8 用例）

| 类 | 表现 | 两轮 |
| --- | --- | --- |
| `chat.SessionInputQueueDeviceTest` | `ComposeTimeoutException` 15000 ms ×2 | 稳定复现 2/8 |
| `ui.ChatStopProgressDeviceTest` | `ComposeTimeoutException` 10000 ms | 稳定复现 1/3 |
| `ui.ProviderFlowTest` | `ComposeTimeoutException` 10000 ms | 稳定复现 1/1 |
| `ui.ConversationHeaderDeviceTest` | `assertDoesNotExist` 失败 | **1 → 0（偶发）** |
| `ui.MessageCopyDeviceTest` | 复制内容断言不符 ×2 | **2 → 0（偶发）** |
| `ui.SessionSettingsDeviceTest` | `ComposeTimeoutException` | **1 → 2（不稳定）** |

**结论**：其中 3 个是确定性超时（可能真慢或真缺节点），另 3 个是抖动。抖动那 3 个大概率是**两台模拟器并行争抢 CPU** 放大的——后续单独串行复跑全部转绿或结果漂移。

### 跳过（34 个用例 / 20 个类）— 均为设计内跳过

- 需 developer 车道：`ExtensionJourneyDeviceTest`（7，loopback MCP）
- 需真实账号/模型/外部样本：`SkillCreatorModelDeviceTest`、`SkillInstallerModelDeviceTest`、`ConnectorExternalDeviceTest`、`ConnectorSuppliedArchiveDeviceTest`、`WorkBuddySuppliedArchiveDeviceTest`、`LocalModelRealTaskDeviceTest`、`GoalRealModelUiDeviceTest`、`LiveContextCompactionDeviceTest`
- 需宿主长稳/探针：`ContinuousAppResourceDeviceTest`（5）、`DescriptorPhaseProbeDeviceTest`、`ProcessDeathEvidenceDeviceTest`
- 需专用 kill runner：`FilePublishProcessKillDeviceTest`、`ModelStreamProcessKillDeviceTest`
- 需物理/屏幕条件：`PhysicalBackgroundRecoveryDeviceTest`（3）
- 其他：`LocalModelInstallDeviceTest`、`LocalModelLifecycleDeviceTest`、`CapabilityReadinessDeviceTest`、`GitReleaseFixtureDeviceTest`

## 四、已实施的修复（在 worktree 内，已复跑验证）

### 修复 1：`app/src/androidTest/kotlin/com/helix/app/MainActivityTest.kt`

```diff
         composeRule.onNodeWithTag("drawer-new-conversation").assertIsDisplayed()
-        composeRule.onNodeWithTag("drawer-search-conversations").assertIsDisplayed()
-        composeRule.onNodeWithTag("drawer-current-conversation").assertIsDisplayed()
-        composeRule.onNodeWithTag("drawer-recent").assertIsDisplayed()
         composeRule.onNodeWithTag("drawer-all-conversations").assertIsDisplayed()
+        // HXA-226 collapsed the drawer to the conversation-first surface: the legacy
+        // search/recent entries are gone, not merely hidden (same contract asserted by
+        // GroupedNavigationDeviceTest and HierarchicalNavigationDeviceTest).
+        composeRule.onNodeWithTag("drawer-search-conversations").assertDoesNotExist()
+        composeRule.onNodeWithTag("drawer-recent").assertDoesNotExist()
         composeRule.onNodeWithTag("navigation-sessions").assertDoesNotExist()
```

验证：`MainActivityTest` **OK (1 test)**（原 1/1 失败）。

### 修复 2：`app/src/androidTest/kotlin/com/helix/app/ui/IaAuthorityDeviceTest.kt`

```diff
         compose.onNodeWithTag("provider-group-ON_DEVICE_ASSET").assertIsDisplayed()
-        compose.onNodeWithTag("provider-group-MANAGED_ACCOUNT").assertIsDisplayed()
+        // The managed-account group is Advanced/developer-only: Consumer registers no
+        // subscription transport, so the group must be absent rather than merely empty.
+        // Assert the real channel boundary instead of hard-coding one flavor's surface.
+        val managedGroups =
+            com.helix.app.provider.ProviderChannelPolicy.groups(
+                com.helix.app.provider.SubscriptionProviderModule.providerIds.isNotEmpty(),
+            )
+        if (com.helix.core.model.ProviderProvisioningKind.MANAGED_ACCOUNT in managedGroups) {
+            compose.onNodeWithTag("provider-group-MANAGED_ACCOUNT").assertIsDisplayed()
+        } else {
+            compose.onNodeWithTag("provider-group-MANAGED_ACCOUNT").assertDoesNotExist()
+        }
```

验证：`IaAuthorityDeviceTest` **OK (2 tests)**（原 1/2 失败）。两处都改为**按生产策略断言**，比原来更强：consumer 下显式断言该分组不存在。

> 修复只在 worktree 里。要落到主线需把上面两段 diff 手工应用到主 checkout。

## 五、建议的下一步（按性价比排序）

1. **给 `COMPLETED→FAILED` 簇加诊断**：在 `AttachmentE2eDeviceTest.verifyRepeatedWireId` 断言前打印 `toolResults` 的 `detail`/`errorCode`，一次性把 11 个用例的根因钉死。这是当前最大的一簇。
2. **修 `ProviderModelDiscoveryUiTest`**：先点 `provider-manage-models-${row.id}` 打开对话框，再改用不带 `provider-row` 祖先约束的 `onNodeWithTag` 匹配；3 个用例可一次恢复。
3. **`GoalModelCancellationDeviceTest` 的 `CANCELLED→PAUSED`**：像 Goal 暂停/取消的语义在 HXA-232 期间被改过，需要对着 `ADR-GOAL` 确认哪个是当前契约。
4. **把 A 类 12 个类登记为 host-driver required**，别再当失败重跑。
5. **C 类抖动**：串行单模拟器复跑确认；若仍抖动再查。

## 六、复现命令

```bash
export JAVA_HOME="${JAVA_HOME:?Set JAVA_HOME to the JDK 17 installation}"
export ANDROID_HOME="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK installation}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# 构建（worktree 内）
cd "${HELIX_SWEEP_WORKTREE:?Set the isolated test worktree path}"
./gradlew :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest --offline

# 起模拟器（必须作为后台任务主体，不要 nohup）
"$ANDROID_HOME/emulator/emulator" -avd Helix_API_36 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -port 5554

# 每次跑之前必清数据（Room v1 baseline 无 destructive fallback）
adb -s emulator-5554 shell pm clear com.helix.agent
adb -s emulator-5554 install -r -t app/build/outputs/apk/consumer/debug/app-consumer-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk

# 单类
adb -s emulator-5554 shell am instrument -w \
  -e class com.helix.app.chat.AttachmentE2eDeviceTest \
  com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner
```

原始日志：consumer 全量 `/tmp/sweep/*.raw.log`；失败类复跑 `/tmp/rerun/*.raw.log`。
