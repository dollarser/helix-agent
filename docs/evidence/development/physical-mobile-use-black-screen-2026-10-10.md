# 真机 Mobile Use 后台返回黑屏诊断与修复

2026-10-10，[HXA-269](../../development/tasks/HXA-269.md)。Owner报告后台返回只有Helix页面黑屏，开启Mobile Use后更容易发生。先完成原包与新版有界诊断，随后Owner明确授权实施修复并真机/Qwen回归。历史ANR主线程节点等待路径已移除，最终APK有界回归通过；**视觉黑屏在修复前也未复现，不据此宣称所有黑屏原因或长后台验收完成**。下方原诊断阶段结果保持原有验证边界，最终实现/结果见末节。

## 设备与安装身份

唯一连接的USB实体手机为OnePlus PLC110，API35，Developer debug，包名`com.helix.agent.developer`。同时连接的模拟器未操作；未发现并行设备测试。新旧显示版本均为0.0.5/code1，必须用APK哈希区分。

| APK | SHA-256 | 身份核验 |
| --- | --- | --- |
| 手机原包 | `c933b4b28febec03f75f8ca832b5d86941bef70f46db6dfe2900b48073c090dc` | 与2026-10-06发布BUILDINFO的Developer产物匹配，源码commit `9f4a09ef3b9861d8fae85cb52f83570aef14893f` |
| 诊断阶段更新（未含本次修复） | `557dce87f27db98129f22bd3c4505608b05203e73f01379d75c8b72d69b84633` | `adb install -r`成功；重新读取手机base.apk，确认与本地主APK完全一致 |
| 最终修复APK | `d7c60f340f958d5647929a0f37792ce04304a3d288eb03d839c42fcc78a6fc31` | 同签名覆盖安装成功，重新读取实体手机base.apk匹配；当前保留在手机 |

签名证书相同。新旧及手机原库Room identity均为`c34065205d0987bb3b495a2f4a663c43`；仓库外原库/WAL诊断副本integrity_check为ok。Owner在获知开发库重建兜底风险后明确允许更新；本次安装保留原诊断会话及Qwen配置，未清数据、卸载或重置。

## 本轮操作与结果

Owner指定Qwen3.8-27B，先通过本机转发验证连接，随后使用Owner授权HTTPS隧道。连接测试通过，真实文本会话回复READY。地址与密钥仅保留在被Git忽略、权限0600的`.env`及手机新增的诊断API配置；报告不记录地址、密钥或用户内容。

每组普通后台返回均采用Home、等待2秒、显式启动MainActivity，保留约0.3秒及随后2秒的截图、窗口状态、PID和gfxinfo。下表的命令耗时不是首帧耗时；进程稳定也不等于所有生命周期路径通过。

| APK/状态 | 循环 | 返回命令耗时范围 | 观察 |
| --- | --- | --- | --- |
| 原包，新建空会话 | 3 | 0.118–0.150秒 | 页面正常、同一进程；后续确认会话Mobile Use原本已勾选，未进行屏幕工具操作 |
| 原包，真实模型只读应用查询前后 | 3 | 0.116–0.123秒 | 页面正常、同一进程；ui.apps查询系统设置成功，工具卡48ms |
| 原包，无障碍/手机工具/悬浮控件启用 | 3 | 0.103–0.127秒 | 页面正常、同一进程；ui.launch打开系统设置154ms，ui.snapshot195ms |
| 新版，无障碍启用、空闲 | 3 | 0.114–0.134秒 | 页面正常、同一进程 |
| 新版，真实模型ui.launch完成后 | 3 | 0.123–0.127秒 | 系统设置打开、模型完成回复、返回页面正常、同一进程 |

初始无障碍未绑定、Root未连接，未发现Shizuku或所查询的Root管理器包，**不据此声称手机完全没有Root能力**。Owner随后允许调整相关系统权限，临时启用Helix无障碍并确认Bound service。旧版的返回模型任务在ui.back等待批准时，被诊断脚本主动返回Helix；为避免随后back操作改变已返回的前台，取消了该合成任务。因此该轮仅证明launch/snapshot及人工返回，不算模型自动返回验收。新版任务限定单次launch、不读取页面，模型遵守限制；宿主截图独立确认系统设置已打开。

本轮没有新增应用FATAL或Helix ANR。过滤logcat含系统进程Window Session Crash，栈为`WindowManagerService.windowForClientLocked → updateTapExcludeRegion`的空IWindow异常，原包未执行手机工具时也出现，且同时页面正常；尚不能作为黑屏根因。采集器包含已有缓冲内容，异常次数不能解释为全部在该阶段新增。

## 已证实的故障线索与性能边界

手机exit-info保留2026-10-06 22:41:02的Helix ANR：`Input dispatching timed out (Application does not have a focused window)`。这是历史真实故障，不能因为本轮未复现而关闭。系统ANR trace普通ADB最初读取Permission denied。Owner追加相关系统权限授权后，收尾发现`su`为MAGISKSU30.7；通过已可用Root只读取得该Helix历史trace，未启用新的持久访问。主线程状态为TimedWaiting，调用链为：

```text
onAccessibilityEvent
→ observeActiveTarget
→ currentAccessibilityRoot
→ freshActiveWindowRoot
→ AccessibilityService.getRootInActiveWindow
→ AccessibilityInteractionClient.waitForResultTimedLocked
→ Object.wait
```

诊断时`HelixAccessibilityService.onAccessibilityEvent`仍直接调用`observeActiveTarget`，此文件自原包commit至诊断源码无差异。这证实历史ANR现场发生主线程同步等待无障碍节点结果；随后修复移除该回调的节点查询。它与Mobile Use启用后卡顿及窗口不能及时就绪相符，但不能证明本轮尚未复现的每次黑屏均由该路径造成。

[Android官方诊断文档](https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs#no-focused-window)将此类ANR与首帧过慢或主窗口不可聚焦关联。原包MainActivity在首次setContent前同步取得appContainer；当前实现先显示启动页面，再等待初始化。这是合理候选及已有改进，**此次取得的故障堆栈指向无障碍事件查询，而非该启动路径，不将初始化改进作为本次ANR根因**。Mobile Use小型悬浮窗的NOT_FOCUSABLE是控件设计属性，不等于MainActivity不可聚焦；本轮窗口焦点均回到Helix。

卡顿尚有慢帧证据：原包累计3862帧、353 janky、p99 69ms；新版累计593帧、157 janky、p99 105ms。两轮模型文本、键盘、通知和操作数量不同，不是公平性能对照。仅看各组第2+3次返回的gfxinfo增量，原包只读组3/38慢帧、工具悬浮组3/36，新版空闲组3/6、新版launch后组7/37；样本太小，不能证明性能改善或普遍严重程度。冷启动单次TotalTime原包1764ms、新版609ms也受缓存/状态影响，不作性能结论。

## 诊断阶段收尾与后续建议

临时无障碍已恢复原始未启用状态，Bound/Enabled services为空；自建ADB30008反向映射已移除，过滤日志采集器已停止。保留手机新增Qwen配置和合成诊断会话，未操作第三方账号；新版保留在手机。原始私有截图/日志可能含通知或地址，不进入仓库；只保留去除敏感内容的摘要。

本轮命令包括显式实体serial的`adb devices -l`、`dumpsys package/window/accessibility/activity exit-info/gfxinfo`、`exec-out screencap`、Home/MainActivity切换、Owner授权的相关secure设置调整与恢复、签名校验和`adb install -r`。没有运行模拟器、设备instrumentation或新生产代码补丁。`./scripts/check-all.sh --source`退出0，206项主机测试及文档/ADR/i18n/secret检查通过；补充历史ANR堆栈后重新运行文档检查通过。

优先修复无障碍事件回调中的同步节点查询：回调只记录失效信号，窗口/节点观察转移到有界后台执行，合并事件风暴并保留generation、安全目标切换、取消及服务销毁语义；不可单纯取消安全目标观察。补阻塞查询下UI响应、队列边界和失效窗口回归，再在同一真机/Qwen验证。Owner日常再次发生黑屏时同步采集窗口、主线程/Binder/渲染时间线，区分剩余生命周期和OEM窗口异常。Root/Shizuku路径未验证，短后台循环不覆盖长时间休眠、进程回收、锁屏和所有历史会话。待取得现场证据后选择修复及回归范围，不新增无证据的强制重建Activity或禁用插件绕过。

## Owner追加授权后的最终修复与验证

Owner明确回复“实施修复并真机回归”。保持同一OnePlus/API35 Developer及已有Qwen3.8-27B配置，不更改生产权限合同或数据库结构。参考[Android无障碍服务](https://developer.android.com/guide/topics/ui/accessibility/service)、[AOSP服务实现](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/accessibilityservice/AccessibilityService.java)的回调/同步查询边界及[agent-device](https://github.com/callstack/agent-device)的显式观察、动作、证据分离方式；采用Helix现有快照/token/授权链，不复制竞品代码或引入其框架。Android事件对象不跨回调持有，工作线程仅查询新根、复制package/window标量并回收节点。

`onAccessibilityEvent`现在仅检查锁屏/自有悬浮窗，立即更新快照generation并提交观察请求。`AccessibilityTargetObserver`共享一个后台线程、至多一个排队任务，同一实例合并事件风暴；旧服务查询未退出时不会因取消新建并行查询线程。结果回到服务主Looper，只有revision及生命周期仍匹配才发布安全目标观察；unbind/destroy关闭观察器，重新连接建立新观察器。已知无关窗口内容保留generation，未知窗口保守失效；后台发现内容事件对应的新窗口或失效窗口时补失效，首次观察/相同窗口重验不再重复使新快照失效。查询失败返回不可用，不报告验证成功，不恢复目标授权。现有动作执行仍使用新根、原许可及token验证。

新增8项JVM回归覆盖1000事件合并、阻塞查询下调用者仍能提交/取消、物理查询未结束前不释放容量、查询期间新事件丢弃旧结果、排队回调不能越过新事件或关闭、窗口失效/异常恢复、线程繁忙重试，以及内容事件窗口替换和无关窗口不失效。原generation/授权/动作测试保持；Mobile Use最终224项全部通过、无skip。阻塞Binder模拟在JVM边界执行，未在用户手机强制制造ANR。

最终主机命令：`./scripts/check-all.sh --source`（206项及文档/ADR/i18n/secret检查退出0）、`ORG_GRADLE_PROJECT_includeSpikes=true ./gradlew test`（全仓配置测试退出0，未改模块允许up-to-date）、`./gradlew spotlessCheck detekt :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleDeveloperDebugAndroidTest`退出0，双渠道Debug主APK编译退出0。首次子项目spotless任务选择错误，以及新测试的UseCheck/格式、观察器复杂度检查失败已修正并重跑；未削弱或跳过测试。AndroidTest只编译，本轮不在用户数据库上运行instrumentation。

最终APK安装后PID在本轮6个循环内保持同一身份。复现路径：开启Helix无障碍与该合成会话Mobile Use → 空闲时Home等待2秒/返回3次 → Qwen单次打开系统设置、单次快照 → 任务完成后人工返回Helix，再Home等待2秒/返回3次。每次保存返回早期/稳定截图和window/gfxinfo，六个场景观察到正常对话页和Helix焦点；命令往返0.102–0.134秒是am-start测量，不等同首帧时间。另12次限定Helix合成会话的交替滚动完成，PID不变、窗口焦点正确，系统线程列表核实只有一个目标观察线程。

最终Qwen任务Room状态COMPLETED、无error：`ui.launch`输出`SUCCEEDED`，`ui.snapshot`实际领域输出`SUCCESS`、backend=`accessibility`；JSON内容哈希与工具结果关联后读取，未仅依赖模型描述或外层执行SUCCEEDED。该任务按提示留在设置用于宿主诊断，不能算模型自动返回验收。中间构建的快照曾领域拒绝`TARGET_CHANGED`，模型却把外层完成误报为成功；这两次拒绝保留为失败观察，不能计入快照通过。另一中间任务通过悬浮“回会话”人工接管，随后待审批返回launch被取消，无重放；也不算自动返回通过。

从修复阶段开始连续过滤的新日志中Helix ANR及FATAL EXCEPTION均为0；仍有2次系统Window Session Crash，签名为`IWindow.asBinder null → windowForClientLocked → updateTapExcludeRegion`，与原包诊断阶段线索相同。没有同时观察到黑屏，不证明该OEM系统异常无影响，也不将其认定为本次根因。已有慢帧累计数据仍不是公平前后对照，不宣称整体流畅度提升比例。

收尾已恢复原始`accessibility_enabled=0`、enabled/Bound services空，自有日志采集器停止，ADB reverse为空；最终APK、原会话/测试模型配置保留，无清数据/卸载/模拟器操作/提交/推送。去敏汇总、代码/APK哈希、领域结果、滚动及主机日志保存在ignored `build/physical-model-fix-20261010/`；含用户通知、设置节点、数据库的原始私有临时资料在汇总后删除，Owner指定.env继续按要求忽略且0600。已修复确认的主线程阻塞路径并完成有界真机回归；原始视觉黑屏未复现、长后台/休眠/进程回收、Root/Shizuku和其他OEM仍开放。
