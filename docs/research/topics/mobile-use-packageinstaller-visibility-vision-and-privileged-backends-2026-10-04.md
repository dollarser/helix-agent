# Mobile Use：PackageInstaller Install 不可见、视觉回退与特权后端调研交接

> 日期：2026-10-04  
> 性质：历史调查与逐阶段证据，不是当前功能状态页。后续实现、权限归属和未闭合工作以[当前状态](../../development/status.md)及有效 ADR 为准。下文“当前”“本轮”均指该段记录阶段。
> 设计原则：继续遵循 Helix 的浅 Harness 方向——模型决定流程，Harness 提供真实能力、权限、观测和动作原语；不要为 APK 安装写专用状态机。

后续抖音实测：API36 已通过 Dispatcher 的 Shizuku 截图与手势点击实际完成抖音 40.7.0 安装；坐标由人工观察当前截图给出，不是模型自主定位验收。一次真实模型任务下载成功后超时，保留失败边界；见[安装与统一扩展验证](../../evidence/development/douyin-and-unified-extensions-2026-10-04.md)。

2026-10-05 早期续验：默认语义观察和节点动作也接入 Root → Shizuku → Accessibility，关闭普通无障碍的 Shizuku 观察通过。当时卸载抖音后的两轮模型尝试分别超时和过早结束，该阶段未安装；见[该阶段验证与限制](../../evidence/development/mobile-use-privileged-semantics-2026-10-05.md)。

随后[抖音/酷安安装实测](../../evidence/development/douyin-coolapk-model-install-2026-10-05.md)记录了成功安装、追加提示和只读核查，不能继续把上段失败当成最终结论，也不将多轮提示包装为一次请求完成。最新[合成任务评测](../../evidence/development/current-model-capability-eval-2026-10-05.md)补充屏幕外节点、输入法透明区域及工具说明修复；它未重新执行真实 App 安装，也不证明设备当前安装状态。

---

## 0. 2026-10-04 首轮续作范围与证据边界（设备授权前）

本次按所有者“继续文档的工作，解决该文档的问题”推进现有视觉回退及主机验证。起始 HEAD 为 `53bb00f65d95d0127fa1c5aa6b072b8a6f96caa1`，工作树已有大量未提交实验改动；本文不把它们全部归为本轮交付，也不提交或覆盖其他工作。

- 第 2～5、8、10 节中的 AVD / Qwen 结果来自原交接记录，**不是本轮重新执行**；缺少原始运行目录、APK SHA、完整动作轨迹的记录不能用作新候选验收。
- 当前主线已没有 Accessibility direct-query fallback，`ui.click_match` 使用 fresh snapshot 与唯一 clickable target；第 14 节更新原先过时的 detekt/保留或回退待办。
- 主机收敛：通用语义/视觉恢复提示、连接测试不能冒充视觉证明、纯视觉 fixture 的结果身份与同帧/成功动作断言、compiled XML flags 回归。
- 当前任务的设备和真实模型执行状态为 **not requested**，未调用 ADB、模拟器或 Qwen。第 16 节列的是未来获授权后的验收步骤，不是可自动执行的命令。
- 检查期间检测到同工作区并行加入 Shizuku 实验依赖/入口；它们不属于本轮视觉修复，不能据此宣称已有正式后端或整树验收。

授权与能力边界以 [权限 ADR](../../adr/permissions/001-session-authorization.md)、[Android 平台能力](../../architecture/android-platform-capabilities.md#5-accessibility-自动化)和[当前状态](../../development/status.md)为准。历史 HXA-243/244/245 设备结果不自动继承到本次候选。

## 1. 问题摘要

目标场景：让 Helix Mobile Use 在 Android 上自动安装已经下载好的微信 APK。

当前最核心的问题不是“模型不知道该点 Install”，而是：

1. Android 16 / API 36 的 PackageInstaller 确认页肉眼可见 Install。
2. 系统测试自动化通道（uiautomator / UiAutomation）能读取到：
   - text="Install"
   - resource-id="android:id/button1"
   - clickable=true
   - enabled=true
3. Helix 生产 Mobile Use 使用普通第三方 AccessibilityService 时，多次 fresh snapshot 只得到 Cancel，拿不到 Install。
4. Android Accessibility 的 direct query（findAccessibilityNodeInfosByText/ViewId）也已实测返回 TARGET_NOT_FOUND。
5. Qwen3.8-27B 实际支持视觉，并且已经通过 Helix 正式 capability probe + 当前屏幕 screenshot E2E 验证：
   - vision=true
   - pixelsAttached=true
   - 模型能准确识别 PackageInstaller 上的 Cancel 和 Install
6. 因此当前优先验证的生产候选方向是（识别成功不等于安装成功）：
   - semantic Accessibility 优先；
   - semantic 不可见时，如果 provider 已验证 vision=true，走 screenshot + 同帧 gesture；
   - Root / Shizuku / ADB 作为可选增强后端，而不是普通设备默认依赖。

---

## 2. 测试环境

### Android

- Emulator：emulator-5554
- AVD：Helix_API_36
- API：36 / Android 16
- ABI：arm64
- PackageInstaller：com.google.android.packageinstaller
- Chrome：com.android.chrome
- 微信：com.tencent.mm

已下载 APK 曾包括：

~~~text
/sdcard/Download/weixin8079android3200_0x28004f30_arm64.apk
/sdcard/Download/weixin8079android3200_0x28004f30_arm64 (1).apk
/sdcard/Download/weixin8079android3200_0x28004f30_arm64 (2).apk
~~~

### 模型 / Provider

- Model：Qwen3.8-27B
- SGLang OpenAI-compatible endpoint：
  - host：127.0.0.1:30008
  - emulator：10.0.2.2:30008
- SGLang /get_model_info 明确返回：
  - has_image_understanding=true
  - model_type=qwen3_5
  - architectures=["Qwen3_5ForConditionalGeneration"]

---

## 3. 最重要的已确认事实

| 能力路径 | 结果 | 结论 |
| --- | --- | --- |
| Helix Accessibility snapshot | ❌ 看不到 Install | 只看到 Cancel |
| Accessibility flags | ✅ 已完整打包 | 实际 APK 中 accessibilityFlags=0x52 |
| active/focused window root + prefetch + refresh | ❌ 未解决 | 仍看不到 Install |
| Accessibility direct query | ❌ TARGET_NOT_FOUND | 不是 DFS 遍历问题 |
| uiautomator dump / UiAutomation | ✅ 能看到 Install | 测试/系统 automation 通道能力更高 |
| Qwen capability probe | ✅ vision=true | 模型视觉能力已正式验证 |
| ui.screenshot → Qwen | ✅ pixelsAttached=true | 像素真的送入模型 |
| Qwen 识别当前安装页 | ✅ | 准确报告 Cancel + Install |
| Helix libsu RootService（当前 emulator） | ❌ DENIED/DISCONNECTED | adb shell root ≠ App 获得 root |
| adb shell | ✅ uid=0(root) | 当前 AVD 的 adbd/shell 是 root |

---

## 4. Accessibility 为什么“肉眼有 Install，但 Helix 没有”

### 系统测试通道看到的节点

在 PackageInstaller 确认页上，adb shell uiautomator dump 曾稳定拿到：

~~~text
text="Cancel"
resource-id="android:id/button2"
clickable=true
enabled=true

text="Install"
resource-id="android:id/button1"
clickable=true
enabled=true
~~~

### Helix 生产 Accessibility snapshot

同一个界面，Helix Mobile Use 的 production snapshot 多次只得到：

~~~text
WeChat
Do you want to install this app?
Cancel
android:id/button2
~~~

没有：

~~~text
Install
android:id/button1
~~~

### 已排除的配置问题

已恢复并实际打包：

~~~text
flagReportViewIds
flagRetrieveInteractiveWindows
flagIncludeNotImportantViews
~~~

APK analyzer 显示：

~~~text
android:accessibilityFlags="0x52"
~~~

0x52 = 0x40 + 0x10 + 0x02，即上述三个 flag 都已进入 APK。

因此问题不是“源码写了 flag、APK 没带进去”。

### 已尝试的 root 获取增强

HelixAccessibilityService 已实验性尝试：

- AccessibilityWindowInfo.isFocused/isActive
- API 33+ window.getRoot(prefetchFlags)
- getRootInActiveWindow(prefetchFlags)
- descendants depth-first prefetch
- FLAG_PREFETCH_UNINTERRUPTIBLE
- AccessibilityNodeInfo.refresh()

生产测试仍然只看到 Cancel。

### Direct query 也失败

又尝试了 Android Accessibility connection 级查询：

~~~text
findAccessibilityNodeInfosByText("Install")
findAccessibilityNodeInfosByViewId("android:id/button1")
~~~

实际设备测试：

~~~text
DIRECT_QUERY_RESULT=TARGET_NOT_FOUND
~~~

因此当前证据支持：

> 在 API 36 的 PackageInstaller 最终安装确认 UI 上，普通第三方 AccessibilityService 得到的可访问节点集合，比 UiAutomation / uiautomator 的系统测试节点集合更少。至少在当前 AVD 上，Install 不对 Helix 的普通 AccessibilityService 暴露。

不要继续把问题当成 Helix DFS 漏遍历。

### 平台机制与未闭合的归因

Android 官方 [View API](https://developer.android.com/reference/android/view/View#ACCESSIBILITY_DATA_SENSITIVE_AUTO)说明：API 34 起，`ACCESSIBILITY_DATA_SENSITIVE_AUTO` 会结合 `filterTouchesWhenObscured` 和父节点敏感性限制非 `isAccessibilityTool` 服务的交互。这为“按钮可见但普通服务查不到”提供了具体机制；三个 retrieval flags 不能据此被认为会覆盖该过滤。

**这是机制支持的候选解释，不是当前 Google PackageInstaller APK 的根因证明。** 仍缺该设备精确系统构建/安装器版本与源码或 APK 对照，不能把 AOSP 或其他版本的实现直接等同于 `com.google.android.packageinstaller`。不要仅为获取 Install 声明 `isAccessibilityTool=true`，也不要移除现有 `SENSITIVE_UI` 拒绝。

官方 [UiAutomation API](https://developer.android.com/reference/android/app/UiAutomation)将其定位于测试自动化；测试通道观察并不证明普通 App 具有同一权限。单独缺失语义节点时可尝试授权范围内的平台截图/手势，但如果平台或 Helix 已明确报告敏感/受保护 UI、拒绝或未知动作结果，必须保留该事实，不能用视觉/特权通道规避。


---

## 5. 视觉能力：已经验证成功

### 之前为什么误判成 VISION_UNAVAILABLE

早期 pilot 只调用：

~~~text
providerService.runConnectionTest(provider)
~~~

连接测试只证明 transport/model 基本可用，保存的是：

~~~text
source=CONNECTION_ONLY
vision=false
toolCalls=false
...
~~~

所以 ToolImagePreparer 会 fail closed：

~~~text
VISION_UNAVAILABLE
pixelsAttached=false
~~~

这不是 Qwen 没视觉，而是 Helix 尚未探测该 provider/model 的可选能力。

### 正式 capability probe 结果

使用：

~~~text
providerService.runCapabilityTest(provider, MODEL)
~~~

得到：

~~~text
streaming=true
toolCalls=true
parallelToolCalls=false
vision=true
reasoning=true
jsonSchemaOutput=false
source=PROBED
~~~

这是 Helix 自己 phase-5 图片 probe 得出的，不是按模型名猜测。

### 当前 PackageInstaller 的视觉 E2E

在用户保持当前安装确认页不动时，Helix 执行：

~~~text
ui.device
→ ui.screenshot
→ Qwen3.8-27B
~~~

截图结果：

~~~json
{
  "status": "SAVED",
  "pixelsAttached": true,
  "imageWidth": 1080,
  "imageHeight": 2400,
  "note": ""
}
~~~

Qwen 视觉识别结果明确包含：

~~~text
WeChat
Do you want to install this app?

Exact visible action buttons:
1. Cancel
2. Install
~~~

结论：

> 视觉识别本身已经证明可用，当前最值得收口的是“semantic failure → screenshot → same-frame gesture”的正式产品路径。

---

## 6. 通用视觉回退契约

默认路径：fresh semantic observation → 已观察到的唯一目标动作；有界语义查询仍无目标时，由模型决定切换到 `ui.device → ui.screenshot → ui.gesture → 新观察`。不再要求生产执行 direct query，也不为 PackageInstaller 硬编码英文按钮、重开次数或安装状态机。

| 边界 | 当前要求 |
| --- | --- |
| 视觉能力 | 精确 provider/model 已确认 vision；`CONNECTION_ONLY` 不构成证明。未知或仅连接状态由既有 capability probe 求证，明确 MANUAL/PROBED false 不自动覆盖 |
| 像素 | `pixelsAttached=true`；仅返回图片路径、SAVED 或模型自述不等于收到像素 |
| 坐标 | 模型从本次图像识别；通过 `imageWidth/imageHeight` 与 `screenBounds` 映射到物理屏幕像素 |
| frame | screenshot 与 gesture 使用同一 token；两者之间再调 `ui.device` 会替换 frame。窗口/旋转/许可变化后重新观察 |
| 结果 | 动作成功回执仅证明 dispatch；必须重新观察真实 end state。UNKNOWN 不盲目重试 |
| 权限 | 既有 Dispatcher / Conversation scope / 敏感 UI / 图片来源与接收方校验保持；不能因语义失败提升权限 |

frame 是观察身份与几何/许可绑定，不是屏幕像素永远不变的保证；动画、延迟和同窗口内容变化仍需设备验证。安装完成应来自 fresh success UI 或正向 package 事实。`ui.apps` 只列 Android 可见、授权且可启动的应用，并可能截断；未列出微信不能单独证明尚未安装。

---

## 7. 提示词与 Skill 收敛

`app/src/main/resources/prompts/base.md` 与内置 `android-ui-task` 统一表达第 6 节通用规则，移除反复指定 `text="Install"`、固定一次重开和“先禁止截图、再允许截图”的安装专用流程。使用当前观察中的本地化标签；不按英文界面猜目标。

保留两项场景语义：原生 Settings/权限入口优先，以保留发起 Activity 的返回流程；明确授权的安装请求可以包含普通安装确认，但不覆盖策略/平台拒绝。是否恢复、重开或改换观察由模型按 fresh evidence 决定。

执行任务不能以 “Let me… / I’ll…” 的未来叙述冒充完成；动作后验证目标结果，无法继续则如实报告 blocker。该约束是模型指引，不是 Harness 对安装业务的自动验收器。

---

## 8. 历史“微信安装成功”的归因问题

这里很容易误导后续 Agent，必须单独记录。

### 已确认的安装完成事件

系统日志曾记录：

~~~text
10-03 11:27:41.941
PackageManager: installation completed for package:com.tencent.mm
~~~

随后 PackageInstaller 进入 InstallSuccess。

### 不能归因给 direct query

11:26:41 的 direct query 测试明确是：

~~~text
DIRECT_QUERY_RESULT=TARGET_NOT_FOUND
~~~

比安装成功早约一分钟。

所以 direct query 不是这次安装的触发源。

### 不能归因给 11:40 的视觉 pilot

11:40 的 Mobile Use WeChat pilot 工具链是：

~~~text
ui.device
ui.screenshot
ui.find("weixin")
ui.click(APK)
ui.device
ui.screenshot
ui.click_match("Done")
~~~

没有 ui.gesture Install，也没有 ui.click_match("Install")。

助手文本明确是：

~~~text
The package installer is now showing...
WeChat installed successfully. Let me dismiss the dialog.
~~~

说明该 pilot 打开 APK 时已经是 App installed 状态，只负责点 Done。

### 当前结论

> 11:27:41 那次真实安装成功发生在多轮能力测试之间，但触发 Install 的具体单一来源尚未闭合归因。

因此以后禁止用历史 package 状态证明某条能力路径成功。

每条能力测试必须：

1. 先确保 pm path com.tencent.mm 为空；
2. 确保当前是 fresh Install 确认页；
3. 只允许一种候选能力路径；
4. 记录该测试的所有 tool/actions；
5. 最终再检查 package 是否存在。

---

## 9. 纯视觉安装 fixture

入口：`app/src/androidTestDeveloper/kotlin/com/helix/app/eval/CurrentInstallVisualGestureDeviceTest.kt`，显式参数 `helixPackageInstallerVisualFallback=true`。缺参数为 opt-in skip；提供非 `true` 值必须失败。现有 Qwen endpoint/model 是历史实验配置，运行前须与当次授权一致。

本轮加强了以下断言：

1. 起始 `PackageManager` 检查微信不存在；外部准备 fresh 安装确认页，fixture 不自行卸载用户数据。
2. 正式 `runCapabilityTest` 确认 vision；Turn 必须 `COMPLETED`。
3. 仅允许 `ui.device/ui.screenshot/ui.gesture/ui.apps`，拒绝 semantic、Root、shell 等替代工具混入。
4. 通过持久 `ToolCallEntity.id` 查结果（不能混用模型 `callId`）；结果必须 verified，截图为 SAVED 且 `pixelsAttached=true`。
5. 每次 gesture 前必须有匹配 frame 的已交付截图；`ui.device` 或前一次 gesture 使该测试的截图资格失效；动作结果必须 `SUCCEEDED`。
6. 至少一次成功 gesture，最后动作后有新观察，最终 package 存在。

这些断言加强因果证据，但无法阻止测试外的人手/另一进程同时点 Install。设备验收必须独占该场景并保留完整轨迹，不能把 fixture 编译当成 clean E2E 通过。Developer 中另有 `VisualInstallEvalService` 实验；本轮加上 `android.permission.DUMP` 调用保护、debuggable 检查与显式启用参数，拆分过长方法，并修复其结果身份查询。该 Service 未具有相同的严格同帧/动作断言，因此输出改为 `DIAGNOSTIC_ONLY`，不再输出 `PASS`；正式验收使用上述 instrumentation fixture。

---

## 10. 历史 Root 路线调查结果

### 当前 emulator 的 shell 是 root

~~~text
adb shell id
→ uid=0(root)
~~~

但 adbd/shell 是 root，不代表 Helix App 能通过 libsu 获取 root。

### Helix 自己的 libsu RootService

运行 Helix 现有 root device test，期待 granted，结果：

~~~text
RootAccessStatus(
  grant=DENIED,
  service=DISCONNECTED
)
~~~

当前 emulator 没有 Magisk / KernelSU / APatch 这类 root manager。

所以：

- host ADB 可以 root；
- App 侧 libsu 当前不能 root。

### Helix Root 架构当前是刻意 high-level only

tools/root 明确没有 root.exec。

现有 typed operations：

- FileRead
- PackageInfo
- ProcessList
- LogRead

这是好的边界，建议继续保持。

### 如果增加 Root UI backend，建议新增 typed operation

不要加：

~~~text
root.exec("uiautomator ...")
~~~

更合理的是：

~~~text
RootOperationRequest.UiHierarchyRead
RootOperationResult.UiHierarchy
~~~

或者进一步做：

~~~text
RootUiObservation
RootUiAction
~~~

能力内部可以使用 root/shell uiautomator dump 或其他系统级 UI automation API，但 Agent 只看到 high-level Mobile Use observation/action，不看到任意 root shell。

---

## 11. UiAutomation / uiautomator 路线

### 已确认能力

系统测试通道能看到普通 Accessibility 看不到的 Install。

因此它是一个有价值的增强后端。

### 不能直接搬进普通生产 APK

Android UiAutomation 通常由 instrumentation/test harness 获得。

普通 App 不能直接从 InstrumentationRegistry 获取 UiAutomation 并把它当作常规生产能力。

所以需要一个额外的 privileged bridge。

候选：

1. RootService
2. Shizuku
3. ADB companion / desktop bridge
4. developer-only instrumentation backend（只用于测试，不用于用户版）

---

## 12. Shizuku / ADB 方向建议

### Shizuku

推荐作为非 Root 的可选“shell 权限增强后端”优先评估。

预期形态：

~~~text
Mobile Use
   ↓
UiBackend interface
   ├─ AccessibilityBackend
   ├─ VisionBackend
   ├─ ShizukuShellBackend
   └─ RootBackend
~~~

Shizuku backend 可以考虑提供 typed：

- hierarchy read
- shell-level package status
- bounded input/tap
- system settings observation

不要直接把 Shizuku shell 暴露成无限命令 Agent tool。

### ADB

ADB 更适合：

- developer mode
- 桌面 Companion
- 调试/设备测试
- 用户主动连接电脑的高级模式

不适合作为“Helix 单机手机端默认 Mobile Use”依赖。

但可以做一个 optional connector/plugin：

~~~text
Mobile Use ADB Bridge
~~~

用于：

- uiautomator dump
- input tap
- package manager
- screenshot
- window/focus observation

---

## 13. 当前源码定位与并行工作

下表替代会快速过时的 tracked/untracked 全量清单；准确状态使用当前 `git status` 和 diff。

| 责任 | 入口 |
| --- | --- |
| 视觉能力与图片准入 | `app/.../vision/VisionCapabilityResolver.kt`、`ToolVisionServices.kt` |
| 模型规则 | `app/src/main/resources/prompts/base.md`、`extensions/skills/.../BuiltInSkills.kt` |
| 同帧截图与动作 | `tools/automation/.../AutomationDeviceTools.kt`、`AutomationDeviceAccess.kt`、`AutomationDeviceContract.kt` |
| 语义唯一目标 | `tools/automation/.../AutomationClickMatchExecutor.kt`、`AutomationClickTarget.kt` |
| APK flags 契约 | `scripts/verify-integrated-runtime-apks.py`、`scripts/tests/test_mobile_use_contract.py` |
| 设备实验 | 第 9 节 fixture 及其他 MobileUse pilot；未执行不记 passed |

本轮起始 diff 保存在忽略目录 `build/mobile-use-2026-10-04/baseline.patch`，关键未跟踪文件和改前提示词另有副本。并行新增的 Shizuku 依赖、Activity/Service 不得随本轮研究结果被一并视为已审查/可发布。

---

## 14. Direct-query 实验已不在当前生产路径

本轮核对 `AutomationClickMatchExecutor`、`AutomationTools` 与 `HelixAccessibilityService`，当前 `ui.click_match` 使用 fresh snapshot，映射唯一 clickable target 后调用既有 `nodeAction`。不存在 `findAccessibilityNodeInfosByText/ViewId` 的生产 fallback。

因此原“保留或回退 direct-query、解决其 detekt”的待办已过时。历史 `TARGET_NOT_FOUND` 保留为诊断结果，不再增加重复查询。初次主机检查的实际 detekt/格式失败来自并行 `VisualInstallEvalService`，不能归到已移除的 direct-query。

---

## 15. verifier 修复

Accessibility XML 在源码中是 symbolic flags，但编译 APK 中会变成数值：

~~~text
0x52
~~~

因此 scripts/verify-integrated-runtime-apks.py 已实验性修改为同时支持：

- symbolic flags
- numeric flags

后续应保留这个修复，否则 release contract 会把正确的 compiled XML 误判成 flag 缺失。

---

## 16. 剩余验收与退出条件

| 工作 | 退出条件 | 当前边界 |
| --- | --- | --- |
| 通用视觉回退 | 无安装专用状态机；capability/同帧/结果约束主机通过 | 本轮源码与主机验证，见第 20 节 |
| Clean 视觉安装 | fresh 未安装状态 → 已交付截图 → 同帧成功 gesture → 新观察 → package 安装，单一动作来源 | 设备/真实服务 not requested，尚未通过 |
| 回退可靠性 | 无视觉、拒绝、stale frame、目标变化与 UNKNOWN 保留真实结果 | 主机范围与设备范围分别记录 |
| 特权后端 | owner 确认范围，ADR 接受，typed bridge 权限/撤销/进程死亡/审计及实机矩阵完成 | 独立候选；研究优先级不构成实施授权 |

获本任务设备与服务授权后，才可准备下面的 clean run：

1. 明确指定模拟器、Qwen endpoint/model、允许卸载的微信测试数据；记录 build fingerprint、安装器版本、Helix/Test APK SHA、APK 来源与目标包身份。
2. 独立核验目标未安装；只在允许清除测试数据时卸载。打开同一已验证 APK 的 fresh 确认页；不使用安装命令替代 UI 过程。
3. 运行第 9 节 fixture，期间无人手或其他自动化操作；不启动第二个 UiAutomation/uiautomator。
4. 记录 screenshot/gesture/result 的顺序、frame、图片来源、成功回执及最终 package 事实。工具轨迹混入其他动作、像素缺失或错帧均失败。
5. 发布实际 pass/fail/skip、恢复的测试配置和剩余边界；失败后不能用历史已安装状态补一个 pass。

Root/Shizuku/ADB 的失败或缺失不会改变默认路径权限。新增后端需另立当前授权任务，不能在本次文档收敛中顺带扩展架构。

---

## 17. 不要重复的错误

### 不要看到 HTTP 400 就判断模型没有视觉

先查：

~~~text
/get_model_info
runCapabilityTest
~~~

当前 Qwen3.8-27B 已证明 vision=true。

### 不要把 uiautomator 结果当成 Helix Accessibility 结果

它们是不同权限/automation 通道。

### 不要把历史 pm path 非空当成当前能力方案成功

测试会反复 uninstall/reopen APK，状态污染严重。

### 不要在 instrumentation 运行时同时从 host 启第二个 uiautomator

之前出现过 UiAutomation 冲突并导致测试进程被杀。

### 不要用固定坐标修 PackageInstaller

当前 UI 位置只是该 AVD/分辨率/rotation 的证据，不是生产 API。

视觉 gesture 必须来自 fresh screenshot frame。

### 不要把 PackageInstaller 流程写进 Harness 状态机

保持通用 Mobile Use 能力和模型控制。

---

## 18. 候选架构（特权分支并非已交付）

~~~text
                     ┌──────────────────────┐
                     │        Model         │
                     │ planning / recovery  │
                     └──────────┬───────────┘
                                │
                     ┌──────────▼───────────┐
                     │   Mobile Use tools   │
                     │ semantic / visual    │
                     └──────────┬───────────┘
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                 │
    ┌─────────▼────────┐ ┌──────▼───────┐ ┌──────▼───────────┐
    │ Accessibility    │ │ Vision       │ │ Privileged       │
    │ default backend  │ │ fallback     │ │ optional backend │
    │ snapshot/action  │ │ screenshot + │ │ Shizuku / Root / │
    │ semantic query   │ │ frame gesture│ │ ADB companion    │
    └──────────────────┘ └──────────────┘ └──────────────────┘

Harness responsibilities:
- capability truth
- authorization
- frame/token freshness
- action accounting
- target/package scope
- effect/audit truth

Model responsibilities:
- decide which observation is enough
- choose fallback
- locate visual target
- perform/retry
- verify requested end state
~~~

---

## 19. 首轮结论（最新设备结果见第 21 节）

历史证据支持：该 API36 AVD 的 Install 对 Helix 普通 Accessibility 不可见，direct query 也失败；测试通道能看到，Qwen 能从正式截图识别。Android API 提供了敏感节点过滤这一候选机制，但具体安装器根因与纯视觉安装动作尚未闭环。

本轮选择继续既有 Accessibility + 视觉通用原语，清除固定安装流程提示，强化能力和 fixture 证据契约。**看到 Install、生成 gesture 调用、Turn COMPLETED、历史 package 已存在都不能独立证明本次自动安装成功。** 特权后端只保留独立候选定位，设备验收仍待当次明确请求。

---

## 20. 首轮主机验证记录

执行日期：2026-10-04。主工作区并行增加 Shizuku 依赖，首轮 App 编译遇到 `dev.rikka.shizuku:{api,provider,aidl,shared}:13.1.5` 不在 dependency lock state。未替其他工作更新锁文件，而是从上述 HEAD 建立隔离候选，应用起始 diff、必要未跟踪源码及本轮修复，排除随后加入的 Shizuku 改动。已有 PRoot 构建输入仅复制供主机编译校验，没有执行 Android Runtime。

修复过程中发现并处理：原实验 Service 的 LongMethod/格式问题、fixture 的 `instrumentation.arguments` 编译错误（改用 `InstrumentationRegistry.getArguments()`）；debug 门槛采用当前 `ApplicationInfo.FLAG_DEBUGGABLE`，本项目未生成 `BuildConfig`。失败日志保留，不把首轮失败隐藏为通过。

| 检查 | 本轮结果 |
| --- | --- |
| `:tools:automation:testDebugUnitTest` | 113 tests，0 failure/error/skip |
| `:tools:android:testDebugUnitTest` | 61 tests，0 failure/error/skip |
| `:extensions:skills:test` | 48 tests，0 failure/error/skip |
| `:app:testDeveloperDebugUnitTest` 指定 `com.helix.app.vision.*`、`ModelToolExposureOrderTest`、`SessionToolEffectClassifierTest` | 81 tests，0 failure/error/skip；其中视觉 resolver 为 7 项 |
| `:app:compileDeveloperDebugAndroidTestKotlin` | passed；只编译，未执行 instrumentation |
| 根 `detekt spotlessCheck` | 隔离候选 passed |
| `python3 -m unittest discover -s scripts/tests -p test_mobile_use_contract.py` | 9 tests passed |
| `./scripts/check-all.sh --source` | 主工作区执行时 passed；含文档/ADR/i18n/secret 与 Python gates，不等于 App 构建 |
| `git diff --check` | passed |
| 设备、真实模型、安装 E2E、APK 发布 | **not requested / 未执行**；不算 passed |

隔离验证命令：

~~~sh
./gradlew :tools:automation:testDebugUnitTest :tools:android:testDebugUnitTest \
  :extensions:skills:test :app:testDeveloperDebugUnitTest \
  --tests 'com.helix.app.vision.*' \
  --tests 'com.helix.app.chat.ModelToolExposureOrderTest' \
  --tests 'com.helix.app.tool.SessionToolEffectClassifierTest' \
  :app:compileDeveloperDebugAndroidTestKotlin detekt spotlessCheck \
  --console=plain --max-workers=2 --continue
~~~

303 项 JVM tests 是上述范围总数，不是全仓单测数。日志及原始 JUnit 副本位于忽略目录 `build/mobile-use-2026-10-04/`：`isolated-host-passed.log`、`source.log`、`test-summary.json`、`junit/`。`verified-source-sha256.json` 核对本轮 7 个主要 Kotlin/提示词文件与被测候选字节一致；Developer manifest 只验证视觉 Service 的增量保护，不覆盖并行 Shizuku 注册。

本记录只接受该隔离视觉候选的主机结果，不声称当前整个脏工作树、Shizuku、设备或发布渠道通过。后续修改需要重新验证对应范围。

## 21. 后续授权设备实测：无障碍失败，Shizuku shell 更新成功

所有者补充：已安装微信由另一 Agent 使用 Qwen 视觉路径完成，并明确要求本聊天尝试无障碍，失败后继续修改现有 ADB/Shizuku 或 Root 方案，另一 Agent 已停止。此授权更新前文首轮的 `not requested` 状态；本轮未重新运行 Qwen，视觉成功保留为所有者报告，不能与第 8 节更早那次未归因安装混为一谈。

本轮在 `emulator-5554` / API36 / Developer 实测：

1. **普通 Accessibility 路径 failed**：fresh 同版本更新页中，10 秒 snapshot 仍只有 Cancel、没有 Update/button1，未发出确认动作。去除了原 fixture 的卸载、Chrome app-op 修改和固定坐标准备步骤，保留微信数据。
2. **Shizuku 错误目标反例 passed**：不存在的文字返回 TARGET_NOT_FOUND，包更新时间不变。
3. **修复后的 Shizuku shell 诊断 passed**：server/UserService 均为 UID 2000，由两次实时层级定位唯一 Update，复核 bounds/rotation 后在 UserService 内派发点击；系统随后显示 App installed，微信更新时间从设备时间 `12:22:28` 变为 `12:52:25`，首次安装时间不变。

该方案不要求 Qwen 或 Root，ADB 只负责本次设备测试准备、触发及结果收取；确认动作来自 Shizuku 进程，未用 host `input tap Update` 或 `pm install` 替代。已有裸 x/y + 固定 PASS 标记已改成 typed selector、运行身份、绑定/命令清理和真实动作状态。

**验收仅覆盖微信 8.0.79 的同版本覆盖安装，不是全新安装或生产 Mobile Use 后端交付。** 完整过程、命令、APK SHA、主机回归和剩余权限/取消/UNKNOWN 边界见[本轮设备证据](../../evidence/development/mobile-use-backends-2026-10-04.md)。前文 Root 的 uid=0 是历史环境；本轮 ADB/Shizuku 均实测 uid=2000，没有新增 Root 路径验证。

## 22. 所有者选择 Shizuku：正式工具接入

所有者随后明确“不自建，接入 Shizuku”。本轮已将诊断能力接入 Advanced/developer 的 Mobile Use：设置显示状态并提供显式授权入口，`ui.device` v2 返回 `shizukuState`，`ui.click_match` v4 接受显式 `backend=shizuku` 和精确 `packageName` / `viewId` / `text`。默认仍走普通 Accessibility，不自动提权、切换或重放；Consumer APK 不包含 Shizuku 实现和依赖。

生产路径保留原 Dispatcher、会话批准范围、无障碍租约和共享输入占用。诊断阶段的 `uiautomator dump` 会抑制无障碍服务，不能直接搬入正式路径；现改为 Shizuku UserService 内 `UiAutomation`，使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，双次新鲜节点观察与注入前 guard。输入派发后的页面变化不再误判为执行失败；取消、期限及原授权有效性仍检查，未知结果不自动重试。

API36 模拟器正式 Dispatcher 用例已通过：只读、旧范围、范围外目标和不存在节点均拒绝，精确 Update 点击后微信同版本更新时间从设备时间 `13:41:50` 变为 `13:47:38`，首次安装时间不变，并核验工具审计。设置组件用例通过；142 项定向 JVM、静态检查、依赖锁及渠道 APK 检查通过。此前实际安装成功却返回 UNKNOWN 的失败运行也完整保留，未作为成功计数。

本轮交付限定为可选精确节点点击，仍依赖无障碍连接和默认显示，不是完整截图/手势/输入后端。首次授权旅程、其他 API/OEM、真机/root、真实模型自主选择后端和设备故障矩阵未验收。详见[正式接入证据及制品指纹](../../evidence/development/mobile-use-shizuku-integration-2026-10-04.md)；第 21 节保留为先前诊断阶段，不再代表当前接线状态。

## 23. 插件边界与模块职责复核

所有者追加要求分析 Mobile Use 是否仍是插件，以及 `tools/automation` 与 `extensions/mobile-use` 是否需要合并。按当前源码，它是**编译进 developer APK 的宿主原生插件，具有真实插件身份和启停路径，但不是可脱离 Helix 独立加载的应用**；不能仅凭类名宣称完全解耦。

`MobileUsePlugin` 实现 `HelixPlugin`，读取插件 manifest，工具带 `PluginOrigin`，通过 PluginRegistry/目录选择/工具发布参与宿主的统一 Dispatcher。插件没有自己的规划循环、Turn 引擎或数据库。停用后已发布工具与插件窗口受既有插件生命周期约束。这些是实际插件行为，不只是包名包装。

| 层 | 当前职责 | 判断 |
| --- | --- | --- |
| `extensions/mobile-use` | manifest/版本/工具来源、工具组合、PluginTaskHost 适配、悬浮状态窗与接管交互 | 插件与呈现层 |
| `tools/automation` | AccessibilityService、快照/token、节点/手势/截图执行、物理输入占用、会话范围执行校验、工具 schema、可选特权端口 | Android 自动化执行底座；不仅是薄工具 wrapper |
| `app` 的 flavor `AutomationModule`、设置与特权适配器 | 注册注入、Conversation 设置 UI、屏幕共享目标接线、Shizuku/Root Android 传输实现 | 明显的宿主耦合，仍可改善 |
| `core/policy` / framework | 持久授权范围、策略、审批、原调用身份、审计 | 宿主必须拥有的通用安全边界，不能下放给插件自行授权 |

目前依赖方向是 `extensions/mobile-use → tools/automation`；没有两套独立的查找、手势或截图实现。插件的 ToolBinding 组合与执行层的 schema/executor 是不同职责，悬浮窗经可选 presentation 接口参与遮挡/隐藏，也不是重复执行引擎。因此**没有因功能重合而立即合并的必要**。合并只能减少一个 Gradle 模块，不能消除 App 中的特殊接线，反而会把 Android 服务、工具协议和插件 UI 的改动边界混在一起。

耦合证据也必须保留：`DefaultAppContainer` 直接建立 Mobile Use grant store 并注册；本节检查时 `ChatDispatchRequests` 按 `ui.*` 取 AutomationModule scope、标记来源（后续已在第 28 节改为完整契约匹配）；`SettingsScreen` 直接调用专用设置；具体特权适配器放在 App 的 developer 源集；工具底座中已有 Conversation/presentation 静态控制状态。故更准确的结论是“插件身份与统一执行成立，边界尚不完全独立”，不能称为纯通用外部插件。

建议保持两层，后续若授权结构调整，优先将 Mobile Use 专属设置与具体 Root/Shizuku 适配器移入插件实现，宿主仅注入原有通用 grant store、图片发布、任务/导航能力；再依据真实调用方去除 `ui.*` 特殊接线。不为了移动文件新建插件框架，也不把通用策略与持久授权复制进插件。本轮是源码分析与定向后端接入，没有顺带进行全模块合并或大范围迁移。

## 24. Root 接入与当前自动优先级

所有者追加 Root 验证并明确选择 Root → Shizuku → Accessibility。已在既有 API34 Root AVD 验证真实应用 UID 0；由于旧数据库版本不兼容，保留旧数据，并按授权新建 `Helix_MobileUse_Root_API34_20261004`。官方 Magisk 临时 setup + 正常管理器授权提供应用 Root，ADB shell 仍 UID 2000；原 API36 的 shell su 不能充当 Helix 应用授权。

当前 `ui.click_match` v5 默认为 `backend=auto`：对精确 packageName/viewId/text 且无其他筛选参数的点击，执行前按已授权且连接的 Root、Shizuku、Accessibility 选择一次。显式后端固定路径，失败/UNKNOWN 不重放。`ui.device` v3 报告两种特权状态和精确点击首选后端；其他操作仍使用现有 Accessibility 能力。本节更新第 22 节的默认后端结论，第 22 节保留为上一阶段证据。

Root 复用 libsu 非 daemon 服务与共享有界特权点击协议。Mobile Use 有独立显式授权/断开入口，允许跨应用连接存续；原 `root.*` 后台失权行为不变。系统权限不代替原 Dispatcher/会话 scope/租约/取消/审计，也不自动创建 Root shell。Root manager 撤销未来授权未必杀死已运行服务，设置已明确提示并提供断开入口。

170 项定向 JVM 与 4 项正式设备用例通过：API34 上 Root 点击 fixture 后计数 1，断开确认 Binder 死亡，新的 AUTO 调用用无障碍后计数 2；Root 正式点击安装器完成微信 8.0.79 首次安装；API36 上无应用 Root 时 AUTO 选择 Shizuku 完成覆盖更新；设置组件验证通过。实际安装通过独立包时间核实，宿主未代点 INSTALL/Update 或 `pm install` 微信。详见[Root 优先级验证与制品](../../evidence/development/mobile-use-root-priority-2026-10-04.md)，不扩展为完整特权 UI 后端、真机/OEM 或发布验收。

## 25. 微信/抖音可检测性与 Computer Use 对照

2026-10-04 所有者追加设计分析。本节为官方资料和当前源码复核，未操作微信/抖音账号、未执行新的设备验证，未实现检测规避。

Mobile Use 不能保证无法检测。Android 可提供控制、屏幕捕获、覆盖窗口及设备完整性信号；Root、模拟器和 Hook 可能影响完整性结果。Google 的公开机制证明这种检测能力存在，但**不能据此断言国内微信/抖音采用了 Play Integrity，或推导封号概率**。两者具体风控策略、服务端行为模型与阈值未验证。Root → Shizuku → Accessibility 是当前能力优先级，不是隐蔽性或账号安全排名；现有特权点击仍需 Accessibility 连接，换传输不会消除这项事实。[Android 完整性与访问风险说明](https://developer.android.com/google/play/integrity/verdicts)

不存在可以承诺可靠的“防检测开关”。随机延迟或视觉点击都不等于真人，更不能消除设备完整性和业务侧信号。应优先使用任务适用且获平台授权的接口，保持官方客户端与支持的设备环境，遇到验证/风险提示暂停交还用户，避免未知结果重放和批量异常操作。这些是控制风险与保持正确结果的措施，不是绕过检测。抖音现行协议第 5.1 条明确限制自动化工具接入及信息处理；微信官方协议本轮抓取失败，不拿第三方转载代替现行条款核验。[抖音协议](https://www.douyin.com/agreements/?id=6773906068725565448)

官方桌面 Computer Use 文档确认它以插件提供 server 与 skill，并与系统权限、按 App 授权分开管理；公开资料未给出足以核实其全部内部进程/Binder 等实现的源码。本轮只借鉴公开契约，不把 API 示例推定为 Codex 私有实现。Responses API 的 Computer Use 文档允许继续使用自定义 function/MCP UI 工具，由宿主执行并回传观察；所以 Helix 无需为了相似形式把本机工具全部改成 MCP。[桌面说明](https://learn.chatgpt.com/docs/computer-use)、[Computer Use API](https://developers.openai.com/api/docs/guides/tools-computer-use)

建议保持交互链：模型提出动作 → 宿主绑定原调用身份并做策略/授权 → 插件执行器选择已获准后端 → 返回观察/实际结果 → 原 Agent 决定下一步。插件不拥有第二个 AgentLoop、模型凭据或独立审批权。Helix 已有语义与视觉观察、frame/token、取消/接管、PluginOrigin 与结果核查，方向一致；`PluginTaskHost` 仅暴露任务投影、停止和回会话，是应保留的窄接口。

隔离必须分层描述：当前 Mobile Use 主体和 AccessibilityService 没有独立进程声明，是宿主内可信原生代码的模块边界，不能称为第三方代码沙箱。Root/Shizuku 辅助进程有 Binder 边界，也不是整个插件的隔离证明。未来若允许不可信原生插件，应另行设计 OS 身份/进程与封闭 RPC；仅增加 `:process`、MCP 或 Gradle 模块不隔离同 UID 数据。当前不应为了类比桌面引入可执行脚本/任意 root shell，也不应硬套桌面多窗口后台操作到 Android 单物理显示。

## 26. Agent Plugins 公开标准与 Helix 的实际符合程度

确有公开的 **Agent Plugins 1.0.0**：它定义插件包，不是 UI 自动化协议或沙箱。MCP 负责工具/资源通信，Agent Skills 负责可按需加载的工作流；A2A 面向 Agent 间交互，不能替代上述边界。标准允许只支持部分组件，缺少 stdio 或某个宿主扩展本身不等于不合规。[Agent Plugins 标准](https://agent-plugins.org/specification)、[MCP](https://modelcontextprotocol.io/docs/getting-started/intro)、[Agent Skills](https://agentskills.io/home)、[A2A](https://a2a-protocol.org/latest/)

| 当前检查项 | 源码结论 |
| --- | --- |
| Mobile Use manifest | 已使用标准 schema/name/version，原生绑定在 `extensions.com.helix.agent.runtime`；不是伪装成标准顶层字段 |
| 可移植组件解析 | 已有 manifest 校验、固定 skills/ 与 mcp.json、未知字段诊断、组件局部失败、包路径防护及测试 |
| 外来包权限 | 原生扩展仅诊断 `HOST_NATIVE_COMPONENT_NOT_IMPORTED`；不据 manifest 加载任意代码，凭据独立配置 |
| 原生插件可移植性 | Mobile Use 资源包目前只有 manifest，实际 Kotlin/Android 执行依赖宿主编译；复制到 Codex 不会获得手机控制能力 |
| 完整客户端符合性 | 不能宣称完整符合；分析时发现的目录入口和共存清单缺口已在第 28 节修复，未做完整标准符合性认证 |

分析阶段定位的具体缺口如下；前两项的修复与当前边界见第 28 节：

1. `PluginPackageReader.parse` 先要求所有已知 manifest 总数不超过 1，再进入标准分支。合法 root `plugin.json` 与可选 `.codex-plugin/plugin.json` 共存会被 `CONNECTOR_AMBIGUOUS_MANIFEST` 拒绝。官方 OpenAI 包装说明允许这种兼容布局。应让已识别的标准根清单决定核心字段，其他宿主清单不覆盖它；不存在标准根时才执行外来格式适配。此结论来自确定的源码分支，尚未新增该用例复现。[OpenAI 包装说明](https://developers.openai.com/plugins/build/plugins)
2. `PluginInstallationService.preview` 明确要求 regular file，生产导入链为 ZIP/JSON；本轮未找到从目录加载插件的入口。标准 §11.1 要求该能力；若产品不实现，应明确定位为标准包的有界导入器，避免宣称完整客户端兼容。
3. 标准组件解析会验证 stdio/sse，但 endpoint 安装会明确跳过；实际是 Skills + 可支持的 HTTP MCP 子集。应在预览中按组件展示支持/需配置/不支持，不能仅凭包导入成功就声称业务可运行。

分析阶段执行 `./gradlew :extensions:plugin:test --max-workers=2`，**44 项通过**（含 17 项 PortablePluginContractTest）。最初误用 Android 模块任务 `testDebugUnitTest`，任务不存在，改为该纯 JVM 模块的 `test` 后通过。这次通过结果不覆盖当时新指出的全部符合性缺口；日志在 `build/mobile-use-root-2026-10-04/plugin-analysis-test.log`。该阶段只追加分析；随后所有者授权的生产优化见第 28 节。

## 27. 建议优化顺序

以下为分析阶段的优先级；已完成的范围以第 28 节为准，未列为交付的建议不代表已经实现。

1. **兼容正确性先行**：补 root/overlay 共存用例与加载优先级；明确目录加载支持范围和组件支持矩阵。不要为“完整兼容”盲目执行桌面插件的命令/hooks。
2. **按能力表达后端**：把状态、已授权能力、选择原因与实际结果后端分开呈现；当前只有精确点击使用 Root/Shizuku。可评估用户按应用限定后端的偏好，不静默改变已授权的 Root 优先级，也不宣传它为防检测选项。
3. **收敛宿主耦合**：`ChatDispatchRequests` 仍按 ui.* 前缀取 scope/data origin，`SettingsScreen` 直接引用 AutomationModule，具体 Root/Shizuku 适配器位于 App。以可信工具来源绑定的宿主 context 接口逐步替代名称判断，将专用设置/适配器归入插件所有权；持久授权、审计和凭据仍由宿主控制。沿用既有框架，不新建通用大平台。
4. **复用工作流而非再造 Agent**：已有 `android-ui-task` 在 BuiltInSkills 中提供新鲜观察、视觉回退与结果核查；如要插件自包含，迁移或关联这份 Skill，不复制第二份，也不加入应用专属安装/聊天状态机。
5. **用边界测试决定是否拆进程**：先补插件禁用/取消与在途特权调用、断连、转屏/窗口变化、人工接管、恶意屏幕文字和观察过期测试。可信内置代码可先改善接口；不可信代码扩展才需要单独的隔离设计。保持 `extensions/mobile-use → tools/automation` 分工，不因封装不够独立而直接合并模块。

## 28. 所有者授权后的插件优化

本次优化沿用既有 Plugin、Workspace 与 Dispatcher 边界，未新增规划器或执行域：

- 标准根 `plugin.json` 优先，其他宿主清单允许共存但不覆盖标准配置。无效标准根不会退回外来格式；没有标准根时，外来清单歧义仍拒绝。
- `connectors.preview/install` 支持工作区标准插件目录。源内容先经既有 Workspace 权限和有界复制，再解析私有快照；符号链接、深度/条目/字节超限均拒绝。目录和 ZIP 的相同文件获得同一内容 hash，安装时重新捕获、校验预览 hash，之后不再读取源目录。文件选择器仍是 ZIP/JSON，不支持任意系统路径。
- Mobile Use 的调用 scope/data origin 从名称前缀判断改为插件实际声明的完整 descriptor 匹配。相同名称但来源、版本或执行契约不同的工具不获得该上下文；插件停用时不提供路由。这个匹配不授予权限，真实 binding、会话选择和调用准入仍由原 Dispatcher 检查。
- 复用原 `android-ui-task` Skill，说明 Root → Shizuku → Accessibility 的精确三字段点击适用范围、其他操作的无障碍依赖，以及 UNKNOWN 后不得切换后端盲重试。设置页 Root 状态使用本地化说明。更高权限不被描述为防检测能力。

仍保留 `extensions/mobile-use → tools/automation` 两层：前者负责插件组合/呈现，后者负责 Android 执行。设置入口与 Root/Shizuku 具体适配器仍有 App 耦合；本次没有完成任意第三方原生插件隔离，也没有新增按应用伪装或隐藏 Root。组件支持仍是已实现的 Skills/HTTP MCP 子集，目录入口修复不等于所有插件可运行。

测试、构建与未验收边界见[本次优化证据](../../evidence/development/mobile-use-plugin-optimization-2026-10-04.md)。

## 29. 精确坐标点击的小幅扰动

所有者要求精确点击增加随机扰动，视觉点击保持精准或更小扰动。Root/Shizuku 共用的 `ShizukuClickOperation` 现在在两次层级观察确认目标未变化后，仅采样一次中心附近落点。每轴偏移范围为 `±min(floor(轴尺寸 / 20), 4)` 屏幕像素，包含零偏移；例如 20×20 目标最多偏移各 1 像素，80×80 及更大目标最多各 4 像素。小于 20 像素的轴保持中心，窄目标不会沿窄轴偏出。

实际采样坐标送入既有 live guard，再用同一坐标注入；拒绝时不重新采样、不回中心、不换后端重放。两次观察间的节点或旋转变化仍直接拒绝，注入后失败仍返回 UNKNOWN。无障碍语义点击使用控件 ACTION_CLICK，没有可添加偏移的坐标；视觉 `ui.gesture` 保留已校验的原始坐标，额外扰动为零。没有加入随机等待或行为伪装，也不据此宣称不可检测。

本次主机执行 `:app:testDeveloperDebugUnitTest --tests com.helix.app.automation.shizuku.*`：19 项通过，含新增 4 项扰动边界测试；Automation 的 125 项报告按未变输入增量复用。developer debug APK 构建、developer AndroidTest Kotlin 编译、`spotlessCheck detekt` 通过。日志位于 `build/mobile-use-click-jitter-2026-10-04/`。设备验证 **not requested**，未验证微信/抖音实际命中率或风控效果，未提交/推送。

## 30. 开源自动化与应用检测：公开证据及适用边界

2026-10-04 按所有者要求查阅开源工具和平台一手资料。本节补充第 25 节，只形成研究结论；没有修改生产执行链、操作真实账号或验证检测规避效果。隐私政策披露的是可能收集及使用的数据类别，不是检测算法、权重或阈值，也不意味着每台设备均能读取全部字段。下列平台材料针对中国大陆微信、抖音和小红书，不混用 WeChat/rednote 的不同政策。

### 30.1 三家公开披露了什么

| 平台及本轮读取版本 | 与安全判断有关的公开信息 | 不能据此推导的结论 |
| --- | --- | --- |
| 微信，2026-08-17 更新/生效，§1.2 | 设备与网络信息、登录 IP、操作及服务日志；为安全目的可能收集已安装/卸载应用、运行进程或内存数据；异常登录等场景可触发身份核验 | 不能证明微信按某个包名、点击间隔或 Root 状态必然限制账号 |
| 抖音，2026-09-11 更新、09-18 生效，§1.7 | 设备配置、应用列表、进程、应用运行频率、故障/性能及来源，结合账号、设备和日志作安全判断；部分第三方应用信息采集与风险场景有关 | 不能把推荐系统使用的每项互动数据都断言为自动化检测特征，更没有公开的安全点击频率 |
| 小红书，官方英文政策 2026-04-27 更新，§2.3 | 设备/系统、网络、传感器、应用安装与使用、运行进程和软件使用日志用于安全风控，异常登录等可触发进一步核验 | 不能推出视觉输入免检、某一输入后端安全，或某个特征足以判定机器人 |

来源：[微信隐私保护指引](https://weixin.qq.com/cgi-bin/readtemplate?lang=zh_CN&t=weixin_agreement&s=privacy)、[抖音隐私政策](https://www.douyin.com/draft/douyin_agreement/douyin_agreement_privacy.html?id=6773901168964798477)、[小红书官方隐私政策](https://agree.xiaohongshu.com/h5/terms/ZXXY20230227002/-1)。微信页面通过公开网页读取，小红书动态页面通过浏览器读取；不以搜索摘要或第三方“防封教程”替代正文。

因此应把可能的信号分为三层：设备与运行环境、客户端输入与控制能力、账号及业务行为。公开资料支持这些类别存在，但**不足以给三家排出主要检测因素的权重**。大量重复互动、异常登录或请求模式属于合理调查方向，不能据此编造已验证的专属风控模型。

Android 提供读取已启用无障碍服务的 API；接收输入的应用可观察 MotionEvent 的来源、设备、工具类型和时间等属性。官方文档说明 deviceId 为 0 表示不来自物理设备，但不能反推非零就是人工，也不能把它当成所有输入路径通用的机器人判定器。语义 ACTION_CLICK 与触摸事件不是同一种调用。Google Play Integrity 还公开控制/捕获/覆盖窗口风险信号，但这不证明上述国内应用采用该服务；经过 Google Play 相应审核的无障碍服务豁免，也不等于自行设置一个标志即可豁免。[AccessibilityManager](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager)、[MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)、[Integrity verdicts](https://developer.android.com/google/play/integrity/verdicts)

开源 [RootBeer](https://github.com/scottyab/rootbeer) 展示了 Root 检测的常见启发式：su、系统属性、测试签名、可写路径及管理工具等。这只能说明检测思路，不是微信/抖音/小红书实际集成该库的证据；隐藏单一包名也不能证明其余环境信号消失。

### 30.2 开源工具值得借鉴的部分

| 项目 | 可核实的设计 | 对 Helix 的启示与限制 |
| --- | --- | --- |
| Appium UiAutomator2 / openatx uiautomator2 | UI 层级、选择器、设备端服务/Instrumentation；Appium 提供空闲等待、选择器等待和动作确认相关配置 | 借鉴状态等待、超时和结果确认；安装服务或能够控制 UI 不是隐身保证 |
| Airtest / Poco | 图像定位与输入后端分离；Airtest Android 支持多种触摸实现，Poco 提供 UI 层级驱动 | 视觉只改变定位方式，最终仍由输入后端执行；游戏 SDK 集成模式不能直接套到第三方应用 |
| AutoJs6 | 无障碍、图像/OCR、Root/Shizuku 等能力组合及状态查询 | 复用能力发现、显式后端和失败诊断思路；不能把其支持的后端当成已通过三家风控；不复制 MPL 代码 |
| scrcpy | SDK 模式在 Android API 层注入；UHID 模拟 HID 鼠标；AOA 经 USB，可不依赖 ADB 控制 | 证明输入路径可分层，不证明人工身份；HID 鼠标不等于手指触摸，且不能消除设备/账号信号。AOA 需要外部 USB 主机，不符合当前单手机产品范围 |

来源：[Appium driver](https://github.com/appium/appium-uiautomator2-driver)、[uiautomator2](https://github.com/openatx/uiautomator2)、[Airtest](https://github.com/AirtestProject/Airtest)、[Poco](https://github.com/AirtestProject/Poco)、[AutoJs6](https://github.com/SuperMonster003/AutoJs6)、[scrcpy 输入模式](https://github.com/Genymobile/scrcpy/blob/master/doc/mouse.md)。本轮未找到上述项目对三家应用提供可复现、持续有效的不可检测证明。

### 30.3 Helix 应采取的措施

本节分析时 Root/Shizuku 精确点击共用系统 `input tap` 执行链（后续模拟短按见第 31 节），Root 只改变执行权限；第 29 节的小幅坐标扰动不会改变设备环境、无障碍连接、输入来源或账号行为。视觉误差同样不是隐蔽性保障。继续保持已授权的 Root → Shizuku → Accessibility 默认，不把它标注为风控风险排序。

1. **先诊断具体失败层**：区分界面没有命中、控件拒绝输入、环境不受支持、登录验证及业务限制。错误报告记录实际后端、观察时间与结果，不从一次失败推断封号原因。
2. **减少无必要的环境改动**：优先官方客户端及受支持系统；仅按功能需要启用权限和辅助组件。若应用不支持 Root 环境，使用受支持的未修改设备通常比不断伪装环境更可维护；这是兼容性选择，不承诺免检。未来可评估按应用固定后端，但不得在 UNKNOWN 后换后端重放。
3. **改善执行正确性**：依据界面就绪和实际结果推进，保留有界等待、取消、去重与 UNKNOWN 恢复；不靠固定 sleep 或随机延迟代替状态核验。不公布没有证据的“每分钟安全次数”。
4. **风险提示交还用户**：遇到验证码、身份核验或平台限制暂停当前动作，由用户处理；不以循环重试、换账号/网络/设备继续执行来掩盖失败。任务适用时采用平台授权接口。
5. **先建立自有输入诊断 fixture**：后续经设备验证授权，在自有 Activity 对比真实触摸、无障碍、Shizuku、Root 的动作结果和可见事件字段；准确测量差异，避免在真实账号上试探阈值。此项尚未实施，亦不能代替第三方应用内部检测证据。

本轮只更新文档。没有实施隐藏 Root、Hook 风控接口、设备指纹伪造或验证码绕过；没有证据支持将这些作为当前 Helix 的可靠优化。研究结论是应优化可观测性、兼容性和动作正确性，不能把开源工具的执行能力包装成规避检测能力。

## 31. 模拟点击的短按持续时间

所有者追加实现请求后，Root/Shizuku 精确点击在既有单次落点扰动之外，增加 60–120 ms 的单次短按时长采样。执行固定 `input touchscreen -d 0 swipe x y x y duration`：起终点使用同一个经 guard 校验的坐标，不增加滑动距离，不拆成独立 DOWN/UP 进程。AOSP 的输入命令实现支持这类同点、带时长的手势；这是系统注入，不冒充硬件触摸，也未复制参考项目代码。[AOSP InputShellCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/services/core/java/com/android/server/input/InputShellCommand.java)

无障碍语义点击和视觉 `ui.gesture` 保持原有行为，视觉坐标不叠加噪声。原权限、窗口/旋转校验、取消和截止时间、进程终止及 UNKNOWN 不重放约束继续有效。请求时长不保证实际时长，OEM 或自定义触摸处理仍需设备验证；第 24 节的历史设备通过不覆盖这一变更。

环境伪造尚未实现：仍需明确是哪个环境信号、出现什么提示及适用设备。修改 Helix 自身的环境值不能证明其他应用观察到相同变化；不在信息不足时加入全局系统属性改写或第三方 Hook 依赖。当前短按改动不等于完成“防检测”，没有微信/抖音/小红书规避效果证据。

主机验证：`:app:testDeveloperDebugUnitTest --tests com.helix.app.automation.shizuku.*` 的 22 项测试通过（新增 3 项覆盖短按范围、固定显示/同点和非法坐标）；`:app:assembleDeveloperDebug`、`:app:compileDeveloperDebugAndroidTestKotlin`、`spotlessCheck detekt` 及 APK 静态边界检查通过。APK 检查中的 consumer 使用既有制品，本轮只重建 developer。日志位于 `build/mobile-use-touch-press-2026-10-04/`。本增量设备验证 **not requested**，未安装或操作账号，未提交/推送。

随后所有者明确要求模拟器验证，状态更新为本次限定范围 **passed**：API36 Shizuku 和 API34 应用 Root 各完成 3 次生产路径短按，DOWN/UP/click 计数一致，实测分别为 98/73/101 ms 和 125/122/111 ms；预取消、只读及授权边界检查通过。实际时长可能超过请求的 120 ms 上限，不能称为严格实时保证。测试夹具的独立进程、API36 遮挡与状态等待问题已修复；中间失败与恢复过程见[模拟器验证记录](../../evidence/development/mobile-use-touch-press-device-2026-10-04.md)。这不证明检测规避、真实账号兼容或完整取消时序矩阵。
