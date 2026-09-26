# HXA-226 simulator verification

- **基线**：`2187f05d refactor(ui): converge navigation and settings authority`（主 worktree `/Users/dollars/Helix`，分支 `refactor/clean-slate-engine`，工作树干净）
- **设备**：`emulator-5554` / API 36 / arm64-v8a / google_apis / 1080x2400 @ 420dpi（= 411.4dp）/ 无窗口
- **变体**：`consumer`（`com.helix.agent`）+ `developer`（`com.helix.agent.developer`），均 debug
- **日期**：2026-09-26
- **授权**：项目所有者对本轮明确要求模拟器验证
- **执行方式**：按类隔离的 `am instrument`（多类混跑会让 Compose UI 测试整体 `ComposeTimeoutException`），配合 host 侧截图夹具与手动 UI 驱动

## APK 指纹

| 产物 | SHA-256（前 16） |
| --- | --- |
| `app-consumer-debug.apk` | `fe4f38eb4c87c008` |
| `app-consumer-debug-androidTest.apk` | `d558bc0ee9dfeacf` |
| `app-developer-debug.apk` | `a8a25d5d1a472f8e` |
| `app-developer-debug-androidTest.apk` | `d8b5f642a0d9d482` |

---

## PASS

### 1. 导航 IA（Drawer 一级入口收敛 + primary/secondary 返回模型）

| 证据 | 结果 |
| --- | --- |
| `GroupedNavigationDeviceTest.everyDestinationRemainsReachableOnAShortLargeFontWindow` | consumer `OK (1)` / developer `OK (1)` |
| `IaAuthorityDeviceTest.drawerAndSettingsExposeOnlyPrimaryAuthorities` | consumer `OK` / developer `OK` |
| `ConversationTopBarDeviceTest.destinationHeadersAndRealExtensionsAreReachable` | consumer `OK (4)` / developer `OK (4)` |
| `SystemBarInsetsDeviceTest.allDestinationHeadersStayBelowSystemBarsAfterNavigationAndRecreation` | consumer `OK (1)` / developer `OK (1)` |

- 旧 `Capabilities` / `Readiness` / `Permissions` / `Audit` 的 `navigation-<route>` tag **不存在**（测试断言 + 实测 Drawer 截图）。
- Drawer 一级结构实测为 `Sessions` / `Work ▾` / `Configure ▾` / `Settings` 四组，与协调者要求一致（`GroupedNavigation.kt` 分组：conversations / work / configure / settings）。320dp 与 412dp 下均一致。
- **primary 用 Drawer、secondary 用 Back**：实测会话列表与 Settings landing 有 `App navigation`；`settings/permissions` 页面**只有 `Back`、没有 `App navigation`**，与 `ConversationTopBarDeviceTest` 的 3 个 secondary route 断言同构。

### 2. Settings authority（一个能力一个完整管理面）

| 证据 | 结果 |
| --- | --- |
| `IaAuthorityDeviceTest.focusedAuthoritiesOwnTheirCompleteManagementSurface` | consumer `OK` / developer `OK` |
| 实测 Settings landing 无障碍树 | 恰好 3 项：`App & agent defaults` / `Permissions & safety` / `Diagnostics & audit` |
| `grep -cE "provider-add\|connector-import\|settings-proot-status" SettingsScreen.kt` | **0** |

- Models 拥有完整 Provider 管理（`provider-add` 可达）；Extensions 拥有 Skill/Connector 管理（`connector-import` 可达）；Setup 可进入 Readiness / Capabilities / Runtime（三个 `setup-open-*` 均可达）。
- Settings landing **无**任何完整功能的重复入口。

### 3. Recovery / deep-link

| 目标 | 证据 | 结果 |
| --- | --- | --- |
| 缺 Provider → Models | `SystemPermissionsNavigationDeviceTest.missingProviderOffersSettingsNavigation` | consumer `OK (2)` / developer `OK (2)` |
| permission repair → System permissions | 同上 `bothChannelsExposePermissionActionsWithoutRequestingOnEntry` | `OK` |
| Readiness 的 Runtime verify/repair → Runtime | `CapabilityReadinessDeviceTest.runtimeRepairRoutesToRuntimeAuthorityAndReturns` | **consumer 跳过（-4）/ developer 通过** |
| Capabilities 的 notification → system permissions | `CapabilitiesCenterDeviceTest`（点击 `capability-notifications-manage` → `permission-notifications`） | consumer `OK (1)` / developer `OK (1)` |
| developer-only Runtime 旅程 | `CapabilityReadinessRuntimeDeviceTest` | developer `OK (2)` |

- 静态核实 `CapabilitiesScreen.kt:199-235` 与 `MainActivity.kt:269-275` 的接线全部正确：
  `notifications`/`calendar`/`accessibility` → `settings/permissions/system`；`root` → `settings/permissions`（Permissions & safety）；`runtime` → `setup/runtime`；`mcp` → `Extensions`。
- ⚠️ **consumer 上 Readiness→Runtime repair 用例被 `assumeTrue(ProotToolModule.AVAILABLE)` 跳过**（返回码 -4）。该 deep-link **只在 developer 变体上被实际执行**，这是设计上的门控，不是缺陷；已在 developer 轮补齐。

### 4. Conversation / Composer

| 证据 | 结果 |
| --- | --- |
| `ConversationComposerDeviceTest`（含 240dp+1.8 字体、320dp+2.0 字体、长模型名、options sheet、空闲 Send、Stop+Queue/Steer 同排） | consumer `OK (6)` / developer `OK (6)` |
| `ChatStopProgressDeviceTest`（停止闭合流 + 持久停止态 + 失败重试） | consumer `OK (3)` / developer `OK (3)` |
| `ConversationArtifactsDeviceTest` | consumer `OK (5)` / developer `OK (5)` |
| 实测 3×3 分辨率×字体矩阵（27 张截图） | 无裁剪、无重叠、无溢出 |
| 实测横屏（412dp 旋转） | 正常，`Choose model ▾` 完整可见 |

逐项对照协调者清单：

- **320 / 360 / 412dp × 1.0 / 1.3 / 2.0 字体**：9 组全部正常。最坏组合 320dp×2.0 下标题 `New s…` 正确省略、空态文案 3 行换行、composer 行（🎤 / `+` / `Chat ▾` / `⋯` / `➤`）无裁剪重叠。
- **长模型名**：`running.png` 中模型胶囊显示 `fixture-moc…`，省略号截断正常；横屏下同一控件显示完整 `Choose model ▾`。
- **附件 / options sheet**：`refactor-composer-sheet.png` 实测 `回复选项` sheet 正常（关闭 / 推理：默认 / 上下文窗口）。
- **空闲 Send**：无输入且无附件时 `chat-send` 为**禁用态**（实测灰化 + `ChatStopProgressDeviceTest.verifyEmptyConversation` 的 `assertIsNotEnabled`）。
- **运行中 Send + Stop 同时存在，Send 仍走 Queue/Steer**：`01-running-stop-and-send-coexist.png` 实测同排存在 `■`（Stop）与 `➤`（Send），且 composer 上方出现 `排队 ✓ / 转向当前回合`（Queue / Steer）选择器。机制来源：`ConversationDraftBuffer.canSubmit = !sending && missingAttachments.isEmpty()`（在途提交标志）与 `ChatUiModels.isSending`（Turn 活跃标志）是两个独立量，提交被 ack 后 `canSubmit` 恢复 true。
- **CANCELLING 时 Stop 状态**：见下方「覆盖率缺口」——代码层已确定，但**无自动化断言**。

### 5. 视觉 / 可访问性 smoke

| 项 | 证据 | 结果 |
| --- | --- | --- |
| 顶部 inset | `SystemBarInsetsDeviceTest`：9 个 route × 2 轮（含 `recreate()`），`shell-top-bar` 顶部不得与 systemBars/displayCutout 重叠 | consumer `OK (1)` / developer `OK (1)` |
| secondary Back 触控 | 实测 `settings/permissions` 页 Back 容器 `[11,136][137,262]` = **126×126 px = 48×48 dp @420dpi**，标签 `Back` | 达标 |
| 横屏 | 412dp 旋转后会话列表与 composer 均正常，无重叠 | 通过 |
| TalkBack 基本焦点顺序 | TalkBack 启用后首个焦点落在左上角 drawer 按钮；无障碍树遍历顺序严格自上而下，会话列表与 composer 的**全部可交互节点均有标签**（`App navigation` / `New session` / `Search` / `Session list` / `Chat settings` / `Voice` / `Attachment` / `Chat ▾` / `Choose model ▾` / `Response options` / `Send`） | 通过 |

- 静态核实 `CompactPageHeader.kt:34` 使用 `Modifier.size(48.dp)`，并给 `‹` 加 `clearAndSetSemantics { contentDescription = common_back }`。
- `TurnProgressLabel.kt:69` 对 `chat-turn-progress` 声明 `liveRegion = LiveRegionMode.Polite`，状态变化会被 TalkBack 播报。

### 6. 其余 UI 面回归（本轮一并跑）

`NavigationLayoutDeviceTest` `OK (1)`、`AuditScreenTest` `OK (1)`、`ProviderContextDeviceTest` `OK (1)`、`ProviderFlowTest` `OK (1)`、`ProviderModelDiscoveryUiTest` `OK (4)`、`ConnectorUiDeviceTest` `OK (1)`、`AppLanguageDeviceTest` `OK (6)`、`HelixThemeDeviceTest` `OK (6)`、`RunControlModeUiDeviceTest` `OK (1)`、`RunControlSettingsUiDeviceTest` `OK (2)` —— consumer 与 developer 均通过。

---

## FAIL

### F1. `TasksDashboardDeviceTest.tasksDestinationListsReadyPlanAndCancelLeavesTheQueue` —— **既有失败，非 HXA-226 回归**

- **断言**：`TasksDashboardDeviceTest.kt:50` → `onNodeWithTag("navigation-tasks").assertExists()`
- **错误**：`Expected exactly '1' node but could not find any node that satisfies: (TestTag = 'navigation-tasks')`
- **归属证据**（同一断言在三个基线上逐字相同）：

  | 基线 | 结果 |
  | --- | --- |
  | `7d7f9053`（clean-slate baseline） | `Tests run: 2, Failures: 1` — 同 tag 找不到 |
  | `a015222d`（post-clean-slate freeze） | `Tests run: 2, Failures: 1` — 同 tag 找不到 |
  | `2187f05d`（HXA-226） | `Tests run: 2, Failures: 1` — 同 tag 找不到 |

- **根因**：测试直接打开 Drawer 后断言 `navigation-tasks`，**没有先展开 `navigation-group-work`**。`GroupedNavigation.kt:31-38` 只展开「包含当前 route」的分组；`resetDeterministicUiState()` 落在 `sessions`，因此 work 组折叠，`navigation-tasks` 不渲染。这不是产品缺陷——`ShellDestination` 里 Tasks 仍在，且同类第二个测试 `planCancelThroughTheServiceUpdatesTheOpenDashboard` 用 `navigateTo("tasks")` **通过**，证明 Tasks 经生产 IA 可达。
- 该缺陷在 HXA-226 之前就存在（`GroupedNavigation` 的 work 分组在 `2187f05d` 未被改动），且 HXA-226 的完成记录声明设备 `not requested`，说明此用例在改动后从未被执行过。

---

## visual issue

### V1.（轻微）横向滚动选项行中的模型胶囊被截断

- **现象**：412dp × 2.0 字体下模型胶囊显示 `Choos…`（见 `08-composer-412dp-font2.0.png`）；320dp × 2.0 下该胶囊完全移出可视区（见 `07-composer-320dp-font2.0.png`）。
- **判定**：**属既有设计**，非本轮引入。`ConversationComposerDeviceTest.optionsRemainInOneScrollableRowOnANarrowScreen` 明确断言 `chat-options-row` 可通过左滑露出屏幕外选项，并断言 `chat-model-menu` 可 `performScrollTo` 后显示。选项行本身横向可滚，不存在溢出或重叠。
- **建议**：不需要修。若产品希望提升可发现性，可在 HXA-228 的 Conversation/Composer 第二轮中考虑右缘渐隐或 context-chip 布局；不重新打开 HXA-226。

---

## behavioral regression

**未发现 HXA-226 引入的行为回归。**

- consumer 20 类隔离运行：**19 类通过 / 1 类失败（F1，既有）**。
- developer 16 类隔离运行：**15 类通过 / 1 类失败（F1，既有）**。
- 唯一在两轮中出现的失败 F1 已用三基线对比证明为既有问题。

---

## reproduction steps

### R1. 复现 F1（`navigation-tasks` 断言失败）

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb -s emulator-5554 shell pm clear com.helix.agent
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.helix.app.ui.TasksDashboardDeviceTest \
  com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner
# → FAILURES!!! Tests run: 2, Failures: 1
#   Reason: ... (TestTag = 'navigation-tasks')
```

### R2. 按类隔离跑 HXA-226 契约（consumer）

```bash
cd /Users/dollars/Helix
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest
./scripts/debug/2026-09-26/run-isolated.sh \
  scripts/debug/2026-09-26/hxa226-consumer-classes.txt /tmp/helix-hxa226/isolated consumer
```

### R3. 补齐 consumer 上被跳过的 Readiness→Runtime deep-link（developer）

```bash
cd /Users/dollars/Helix
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest
adb install -r -t app/build/outputs/apk/developer/debug/app-developer-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk
./scripts/debug/2026-09-26/run-isolated.sh \
  scripts/debug/2026-09-26/hxa226-developer-classes.txt /tmp/helix-hxa226/developer developer
```

**developer 构建的两个前提**（缺一不可）：

1. `JAVA_HOME` 指向 **JDK 17**。用 Android Studio 自带的 jbr（JDK 25）会在 `:runtime:proot-core:compileKotlin` 报 `Cannot find a Java installation ... languageVersion=17`。
2. `runtime/proot-app/src/main/assets/runtime/{proot,rootfs}/` 必须已存在。这两个目录被 `.gitignore:18-19` 排除，需先跑 `scripts/build-proot-assets.sh`；`:app:verifyDeveloperRuntimeAssets` 是**真检查**（比对 rootfs SHA-256 与 `runtime-lock.json`），并挂在 `preDeveloper*Build` 的依赖链上。本机资产已就绪（rootfs 137 MB，2026-09-14），故直接通过；**干净检出仍需先补齐资产**。

### R4. 取回合成布局截图（fixture 驱动）

```bash
./scripts/debug/2026-09-26/capture-hxa226-layout.sh /tmp/helix-hxa226/capture-consumer-zh zh-CN consumer
```

### R5. 3×3 分辨率 × 字体矩阵 + 横屏

```bash
./scripts/debug/2026-09-26/matrix-hxa226-resolutions.sh /tmp/helix-hxa226/manual
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
```

### R6. TalkBack 焦点顺序

```bash
adb shell settings put secure enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
adb shell settings put secure accessibility_enabled 1
# 焦点顺序以 uiautomator 无障碍树遍历顺序为准（TalkBack 的手势遍历无法经 adb 稳定驱动）
adb shell uiautomator dump /sdcard/ui.xml && adb exec-out cat /sdcard/ui.xml
```

---

## screenshot / evidence

截图目录：[hxa226-simulator-verification-2026-09-26-screenshots/](hxa226-simulator-verification-2026-09-26-screenshots/)

| 文件 | 内容 | 对应协调者项 |
| --- | --- | --- |
| `01-running-stop-and-send-coexist.png` | RUNNING：`■` Stop 与 `➤` Send 同排共存，上方 `排队 ✓ / 转向当前回合` 选择器，模型名 `fixture-moc…` 截断 | 4 |
| `02-stopped-stop-gone-send-kept.png` | CANCELLED：Stop 与 Queue/Steer 行消失，Send 保留，标签「本次执行已停止 / 已取消：不会自动继续」 | 4 |
| `03-composer-options-sheet.png` | `回复选项` options sheet（关闭 / 推理：默认 / 上下文窗口） | 4 |
| `04-conversation-top-bar.png` | 会话顶部栏（会话拥有顶部、`shell-top-bar` 不存在） | 1 / 5 |
| `05-drawer-412dp-font1.0.png` | Drawer 412dp：仅 Sessions / Work / Configure / Settings | 1 |
| `06-drawer-320dp-font1.0.png` | Drawer 320dp（全宽）：同样 4 个一级项，无旧入口 | 1 |
| `07-composer-320dp-font2.0.png` | 最坏组合 320dp × 2.0：标题省略、空态换行、composer 行无裁剪 | 4 |
| `08-composer-412dp-font2.0.png` | 412dp × 2.0 | 4 |
| `09-composer-360dp-font1.3.png` | 360dp × 1.3 | 4 |
| `10-landscape-composer.png` | 横屏 composer，`Choose model ▾` 完整可见 | 5 |
| `11-talkback-first-focus.png` | TalkBack 启用后首个焦点在 drawer 按钮 | 5 |
| `12-settings-landing-3-entries.png` | Settings landing 恰好 3 项 | 2 |
| `13-secondary-back-48dp.png` | secondary 页 `Back`（48×48dp）+ 无 `App navigation` | 1 / 5 |

原始日志（未入库，可经上述脚本复现）：consumer `/tmp/helix-hxa226/isolated/summary.tsv`、developer `/tmp/helix-hxa226/developer/summary.tsv`。

---

## recommended fix scope

按优先级，**全部为测试/fixture 范围，不触及架构、Queue/Steer、权限、Turn/Goal 生命周期**。

### S1（建议修，测试 fixture）修正 `TasksDashboardDeviceTest` 的 Drawer 断言

- **位置**：`app/src/androidTest/kotlin/com/helix/app/ui/TasksDashboardDeviceTest.kt:47-52`
- **改法**：删掉手写的 `open-navigation` + `navigation-tasks` 断言，改为复用 `navigateTo("tasks")`（`AppUiTestSupport.navigatePrimary` 已内置「目标不在可视区就先点 `navigation-group-work`」的逻辑）；若要保留「Tasks 是 primary」的断言，则在 `navigation-tasks` 断言前补一次 `navigation-group-work` 点击。
- **理由**：这是**唯一**在本轮两轮运行中出现的失败，且已被三基线对比证明为既有 fixture 缺陷。修掉它可让 HXA-226 相关的设备面全绿，避免后续把噪声当回归。
- **边界**：只改测试，不改 `GroupedNavigation` 或 `ShellDestination`。

### S2（建议补，测试覆盖）为 CANCELLING 瞬态加断言

- **现状**：`ChatStopProgressDeviceTest.verifyStop` 只断言**终止态**（`chat-stop` `assertDoesNotExist`、`chat-turn-progress` 等于 `chat_cancelled`）。CANCELLING 期间「Stop 仍渲染但 `enabled=false`」这一行为（`ConversationComposer.kt:162-170`）**没有任何设备断言**。
- **本轮外部捕获尝试**：`screencap` 轮询（~2fps）与 `screenrecord` 30fps 抽帧都**未能取到可判读的 CANCELLING 帧**——该状态在本 fixture 配置下短于一次抽帧间隔。这本身说明它不适合用外部截图验证。
- **改法**：在 `ChatStopProgressDeviceTest` 内加一个进程内断言——点击 `chat-stop` 后、等待终态前，用 `compose.waitUntil { chat.screen.value.activeTurn?.state?.name == "CANCELLING" }` 捕获瞬态，并断言 `chat-stop` 存在且 `assertIsNotEnabled()`、`chat-send` 仍在。若瞬态确实不可观测，则该用例应改为**断言 CANCELLING 标签不可达**（即承认 `chat_cancelling` 是死资源），并据此决定是否删除该字符串。
- **辅助工具**：`scripts/debug/2026-09-26/helix-sse-fixture.py` 是 host 侧 OpenAI 兼容 SSE 夹具（配合 `adb reverse tcp:18443 tcp:18443`），可持续吐 token 把 Turn 长时间保持在 RUNNING，用于手动放大 CANCELLING 窗口。本轮未使用（结论已可由代码确定），留给后续需要真机可复现证据时使用。
- **边界**：只加测试；若发现需要改状态机才能让瞬态可观测，**停下来交给强模型**，不在本轮改。

### S3（建议补，测试覆盖）补齐 Capabilities 的其余 deep-link 断言

- **现状**：`CapabilitiesCenterDeviceTest` 只点击了 `capability-notifications-manage`；`calendar` / `accessibility` → system permissions、`root` → Permissions & safety、`mcp` → Extensions 这四条**只有静态代码证据**（`CapabilitiesScreen.kt:199-235` + `MainActivity.kt:269-275`）。
- **改法**：在同一测试里对另外 4 行各做一次展开 + 点击 + 目标页断言（`permission-calendar` / `permission-accessibility` / `screen-settings-permissions` / `screen-extensions`）。
- **边界**：只加测试断言，不改生产回调。

### S4（观察项，不建议本轮动手）consumer 的 `usesCleartextTraffic="false"` 对 loopback 路径未生效

- **事实**：`app/src/main/AndroidManifest.xml` 有 `android:usesCleartextTraffic="false"`，consumer 无 NSEC（已核对 `merged_manifest/consumerDebug`）。但 `ChatStopProgressDeviceTest`(3/3)、`AttachmentE2eDeviceTest`(32/32)、`StartupAndThinkingDeviceTest`(2/2)、`ChatSubmissionReceiptDeviceTest`(8/8)、`SessionInputQueueDeviceTest`(7/7) 都在 consumer debug 下用真实 `ServerSocket(127.0.0.1)` + 真实 OkHttp 通过。
- **判定**：有效边界是应用层 `CleartextAuthorization`（`app/src/developer/res/xml/network_security_config.xml` 的注释亦如此自述），manifest 属性对 loopback 路径不构成约束。**机制未定，本轮不下结论。**
- **建议**：若要让该属性成为真正的纵深防御，需要一个针对性的探针任务（不是 HXA-226 范围）；在那之前，**任何"consumer 会阻断明文 loopback"的表述都不应写进文档或测试前提**。

---

## 与 `verify/engine-emulator-20260926` 分支（`a015222d`）的关系

本轮**不覆盖**那条分支的结论。两者定位、基线、范围都不同：

| | `verify/engine-emulator-20260926` | 本轮（HXA-226） |
| --- | --- | --- |
| 基线 | `a015222d`（`2187f05d` 的**祖先**） | `2187f05d` |
| 范围 | **179 类全量**（539 通过 / 59 失败 / 31 跳过） | **36 类次**（20 consumer + 16 developer） |
| 目的 | 首次真跑设备套件、暴露 fixture 漂移 | 验 HXA-226 的 UI/IA 收敛 |

**重叠极小**：`a015222d` 的 19 个失败类中，本轮只碰到 **2 个**（consumer：`RunControlUiDeviceTest`、`TasksDashboardDeviceTest`）/ **1 个**（developer：`TasksDashboardDeviceTest`），**其余 17 个完全未覆盖**；全量 179 类中本轮未覆盖 **160 类**。

**仍未收敛的部分**：

- **13 处 fixture 修复仍停在 `verify/engine-emulator-20260926` 的工作树里（未提交），主 worktree 上没有。** 实证：`app/src/androidTest/kotlin/com/helix/app/TestChatSubmission.kt` 在 `2187f05d` 上仍然是 `sendSubmission(ChatSubmission(sessionId, 0, UUID.randomUUID().toString(), text))` —— **不传 `attachmentIds`**，即那 25 个被 `ATTACHMENTS_CHANGED` 静默拒绝的测试在主分支上**仍然恒失败**。本轮 36 类次未触及这些类，因此看不到。
- `a015222d` 剩余失败的分类（host 相位缺失 17 / 环境限制 9 / 待定 8）未在 `2187f05d` 上复核。
- 179 类全量结果属于 `a015222d`，**不能直接套用到 `2187f05d`**。

**本轮对那条分支结论的一处实质修正**：

- `RunControlUiDeviceTest` 在 `a015222d` 与 `7d7f9053` 上都被记为「环境/注入限制」，实测是**类名 ≠ 文件名**的 harness 错误：日志逐字为 `Caused by: java.lang.ClassNotFoundException: com.helix.app.ui.RunControlUiDeviceTest`。该文件实际声明 `RunControlModeUiDeviceTest` + `RunControlSettingsUiDeviceTest`，两者在 consumer 与 developer 上**均通过**（本轮实测）。全仓仅此一例影响面较大。

**两轮结论一致的部分**：loopback 与明文无关（那条分支已在第二轮自行推翻，本轮再次确认）。

---

## 未覆盖边界

- **真机**：本轮只在模拟器（API 36 / arm64-v8a）验证。真机 `3B1587004UE00000`（OnePlus 6T / Android 15）未使用。
- **`CapabilityReadinessDeviceTest.readinessStateRecoversAfterProcessReopen` 的两阶段恢复**：本轮按单进程方式运行（未传 `-e recoveryPhase setup|verify`），只覆盖了「无相位参数」的单进程路径；真实两阶段进程死亡恢复需要 `scripts/run-*-process-kill.py` 家族，本轮未跑。
- **consumer 上被跳过的 `runtimeRepairRoutesToRuntimeAuthorityAndReturns`**：已在 developer 补齐，consumer 侧仍为 `assumeTrue` 跳过（设计门控）。
- **CANCELLING 瞬态**：见 S2，无设备证据。
- **TalkBack 手势遍历**：经 adb 的 `input swipe` 被 Compose 手势消费（实测反而打开了 Drawer），因此焦点顺序以 uiautomator 无障碍树遍历顺序为准，未做逐项 TalkBack 播报核对。
- **release 变体**：未构建（无 `signingConfig`，产物为 `*-release-unsigned.apk`，装不上机）。
