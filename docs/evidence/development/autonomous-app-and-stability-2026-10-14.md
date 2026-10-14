# 自主 App 旅程与修后稳定性验收

日期：2026-10-14；范围：[HXA-282](../../development/tasks/HXA-282.md)。Owner本轮明确授权自有API36、既有30008 Qwen、文件/浏览器/Mobile Use任务及最长两小时长稳。实际App为抖音官方下载、安装、观看和卸载，全部效果必须由Helix自主执行，可以提供上下文；不登录，必须登录才可观看时由Helix直接卸载。不会覆盖日常模拟器，不继承旧R5的结果。

## Owner追加：新回执设备与真实模型验收

Owner重新明确授权自有API36、既有30008 Qwen、临时Shizuku/无障碍/悬浮窗：先最多45分钟短验，短验通过后一次连续两小时，合计不超过三小时。不使用日常模拟器或账号；抖音下载安装、观看和卸载仍须Helix自主执行。此为新的独立窗口，不延长或改写前轮失败。

本轮绝对窗口为UTC 03:51:27–06:51:27，短验截止04:36:27。当前源码`aca47a8f`；冻结Developer主APK SHA256 `53f49d1d0fbd1a22246b0fdedcf170ae272d6c3cc130f07351e54f82f7222d15`，测试APK `cb60cb7e91208931f6204b28a0ec0f1e906382e2af7e6e9d0d705cabaded30f0`。自有只读AVD、端口5600，先执行真实拼音，再分别运行原四模式触摸断言、Qwen离屏查找与中文表单。生成物与原始失败均留在忽略的`build/hxa282/layout-and-model-receipt-r1/`，不包含真实账号。

短回归完成：真实拼音1方法、`InstallerTouchFilterDeviceTest#sensitiveAndOrdinaryButtonsKeepTheirOriginalFrameworkAssertions`四种模式1方法、`ProgressiveSkillModelDeviceTest#mobileOffscreenFind`及`#mobileSyntheticForm`各1方法均通过，后两者实际Qwen共4业务和8次后台返回通过。普通/自绘控件实际点击计数为1，显式/隐式敏感控件计数为0且accepted=false；四者均回DISPATCHED/UNVERIFIED，不把系统拒绝误当业务成功。原断言全部保留。

离线核对模型请求中的12条唯一动作回执（set_text/scroll/click各4），均为DISPATCHED、UNVERIFIED、observationRequired=true；外层[SUCCEEDED]仍只表示工具执行完成，业务由实际表单结果独立判定。36次原模型调用均HTTP200、完整SSE且Turn/model-call/代理request ID及单响应ID关联齐全；另2次无身份探测不混入业务调用。该样本只证明契约可用，不声称消除了全部模型提前结束。原无障碍null/0恢复，录制器退出0、VM退出0；日常模拟器未动。

当前抖音使用新的自有实例及已授权临时Shizuku执行，完整旅程和两小时长稳pending。旧脚本中过期的绝对截止时间已改为必须提供且有效的新窗口，未修改生产权限、资源门禁或结果断言。所有生成物均留在上述忽略目录；复验用支持的`run-owned-emulator.py`冻结同一对APK、先拼音，再按上述三个方法分别instrument，真实方法传pluginSkillPort/pluginSkillModel，四模式显式传helixTouchReview=true和helixInstallerTouchProbe=true。

## Owner追加：动作回执语义优化

Owner在过滤诊断后要求优化“派发完成却返回success”的歧义。本阶段只改Mobile Use模型可见动作结果、输出schema、相关工具版本、描述和内置Skill；没有新的设备/模型授权，不启动模拟器、真机或Qwen，设备/真实模型均not requested。此前API36过滤复测证明的是原制品上的系统行为，不当作新工具契约的设备通过。

参考[OpenAI Computer use](https://developers.openai.com/api/docs/guides/tools-computer-use)和[Claude Computer use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool)的动作执行、返回观察、模型继续评估流程；Claude对普通动作的短确认也不等于用户任务完成。采纳这种职责分离，结合Android无法给普通无障碍回传目标View过滤原因的限制，Helix明确返回`status=DISPATCHED`、`effectStatus=UNVERIFIED`及必须观察的提示，不声称竞品使用同名字段。没有复制参考代码或引入执行引擎。

- 手势、节点动作、系统动作及启动共用同一回执schema。DISPATCHED仅确认当前输入/平台调用完成；schema拒绝SUCCEEDED、VERIFIED、缺失核验标记或不要求观察的假结果。受影响动作工具版本升级；device/apps/screenshot与snapshot/find/wait版本保持，历史结果不改写。
- 原生`AutomationActionStatus.SUCCEEDED`及外层ToolCall完成仍是执行事实，未改core、Room、预算、审批、物理租约或Loop。正常的UNVERIFIED不触发UNKNOWN/NEEDS_REVIEW；原ACTION_FAILED/ACTION_OUTCOME_UNKNOWN的review事实、执行前拒绝及取消/超时保持。
- SENSITIVE_UI明确为Helix自身的派发前拒绝，不冒充Android提供了过滤回执。缺少节点、页面未变化或截图中的按钮敏感都不能凭空生成`touchFiltered=true`等诊断。没有添加不可靠的自动结果判定、动作重试或跨后端重放。
- 修正click_match原“无需人工点击”的绝对承诺，说明安装授权不排除平台要求人工确认。Skill明确新回执含义、动作后直接业务核验及无可见进展时不猜测原因。图片披露、返回会话及scope边界保持。

回归保留原unsupported/uncertain两项测试，并增加正常回执、严格schema、全状态恢复事实、本地敏感拒绝四项；实际语义/手势executor测试增加新字段断言及派发前取消不得触达后端检查。旧设备用例只更新模型可见payload断言，实际点击计数、敏感拒绝、安装包状态及原生回调SUCCEEDED断言仍保持。完整主机及双渠道/SDK测试APK结果在本阶段结算；不把编译当设备验收。

最终`./scripts/check-all.sh --all`退出0（`build/hxa282/action-acknowledgement/host-all-r1.txt`），source/host脚本、JVM、格式/Detekt、四variant lint/APK及制品/Runtime边界通过。Mobile Use Debug JVM263项零失败/跳过，其中共享结果类原2项及新增4项均通过；没有声明未配置执行的Release JVM通过。双渠道App及SDK AndroidTest最终编译退出0（`androidtest-final.txt`），定向早期日志保留；差异复核确认原测试方法完整保留，输出断言按新契约更新。设备/真实模型not requested，未安装、未启动新长稳，不预报模型遵循率收益或把未知过滤原因宣称已可检测。

## Owner追加：安装触摸过滤定点复测

Owner随后要求重新测试并查官方依据，判断坐标偏移还是触摸过滤，以及可用的输入接口。本次重新授权的范围是定点诊断，使用自有API36只读实例和自有小型`Helix Install Fixture`，不重新启动长稳、不调用真实模型、不操作抖音或账号。宿主对合成测试包的Shell点击是诊断对照，绝不计作Helix自主安装抖音。

### 官方机制与接口边界

[Android官方安全说明](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data)明确：Android 16的敏感视图保护限制未声明为辅助功能工具的服务读取和交互；`setFilterTouchesWhenObscured(true)`也会使视图默认成为敏感视图。这与必须存在浮窗遮挡的过滤分支不同，不能把名称中的obscured理解为“隐藏浮窗后必然可点”。[AOSP View](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/view/View.java)的`onFilterTouchEventForSecurity`分别检查遮挡位，以及“来自无障碍服务、不是Accessibility Tool、目标敏感”的组合；[AOSP安装器](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/packages/PackageInstaller/src/com/android/packageinstaller/PackageInstallerActivity.java)对确认按钮启用该属性。分支原文及SHA256保存在忽略的`build/hxa282/touch-filter-retest/source-provenance.json`，不把参考分支冒充当前模拟器系统的精确构建源码。

- `dispatchGesture`的完成回调只说明手势序列完成，不说明目标View接受事件。截图、重复点按、长按或修改图片大小不能改变输入来源。
- 已明确授权的Shell/Root/Shizuku是不同权限与输入路径；当前Shell对照可判断此无障碍来源过滤，不代表所有OEM、安全窗口、锁屏或其他保护都可操作。现有Helix后端优先级与授权边界不变，不新增跨后端自动重放。
- `UiAutomation.injectInputEvent`是测试/特权执行环境的接口，不是普通App拿到无障碍权限后就能自由使用的公开全局注入能力。当前Helix已有Shizuku后端，本次不新增输入引擎。
- 普通App可用[PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)发起安装，但首次安装通常仍需系统用户确认；`setRequireUserAction(USER_ACTION_NOT_REQUIRED)`具有更新归属及权限等前提，不是通用静默安装开关。[官方参数约束](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int))。
- [isAccessibilityTool的官方定义](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#isAccessibilityTool())是用于帮助残障用户的服务。Helix的通用Agent定位保持，不为此将声明改为true，也不修改系统安全开关或安装器。

### 本次执行与保留失败

新`InstallerTouchFilterDeviceTest`使用生产`PermissionCenterDevicePort`与实际会话授权，先通过只读特权观察获取确认按钮的真实屏幕矩形，核对其敏感标记；无障碍树独立核对节点缺失。原截图尺寸、屏幕尺寸、旋转及同坐标均记录，先调用Helix无障碍手势再被动查包，确认页面与按钮矩形未变后才发出合成包的Shell对照。没有依赖模型坐标，也没有把特权观察树当作普通无障碍树。另以恢复包装复用原`AccessibilityTouchReviewDeviceTest`四模式及全部原断言，不复制或放宽其用例。

`device-r1`：实际拼音通过；安装按钮已观察为`sensitive=true`且普通AX树缺失，截图遇到`TARGET_OR_AUTHORIZATION_CHANGED`后失败，尚未发送触摸。夹具增加最多10次、每次300ms间隔的只读重观察/截图，仅允许失效目标类状态，不重放动作；原失败保留。首次构建误用不存在的App级Spotless任务及首轮完整主机的新增测试长行静态错误也保留，均已修正。

`device-r2`：实际拼音1方法及安装页对照1方法通过（17.006秒）。Developer/API36 arm64，系统fingerprint为`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`。主APK SHA256 `576cf3723aea4a9e9ff8411e903e550d787a8f06131087e2f36753df14a86ee3`，测试APK `5c16d0760d12093db37348019129ba096c5f370f41d4d74cdc1a2b0335a8eb6d`。

实际按钮矩形`(810,1301)-(978,1443)`、中心`(894,1372)`，原截图与物理屏幕均1080×2400、旋转0；服务`isAccessibilityTool=false`，按钮敏感，普通树缺少`android:id/button1`。无障碍手势`SUCCEEDED`后3秒合成包仍不存在，按钮位置不变；UID2000 Shell在完全相同坐标点击，包安装成功。因此这一对照排除了模型定位和图片缩放误差，结合敏感标记与平台源码，支持无障碍来源过滤归因；没有声称取得系统安装器进程内部过滤回调。

上述实例已退出0，无障碍恢复`null/0`，测试来源安装AppOp恢复default，日常模拟器未使用。最终四模式复跑与完整主机结果继续在本节结算；不以历史通过代替当前结果。

`device-final`两方法同进程尝试：安装页对照再次通过，旧合成用例在触摸前收到`OVERLAY_HIDE_NOT_CONFIRMED`；不能把未派发当系统过滤。恢复原合成用例所需的MainActivity初始化后，`device-r4`四模式实际通过：normal与no-semantics各点击1次、flags2048、accepted=true；显式sensitive与隐式obscured各0次、flags2048、accepted=false。没有遮挡位，证明合成按钮确实是无障碍来源/敏感视图过滤，不是坐标偏移或浮窗遮挡。该组随后同进程切换AX服务时安装页观察为空，整体仍failed；各方法改为单独instrumentation进程、独立连接/恢复，方法间等原设置恢复后再继续，保留全部初始尝试，不修改生产门禁或原断言。

`device-r5`继续保留两项时序失败：安装框初始矩形在操作期间移动49px，严格“矩形不变”断言拒绝将其算有效同坐标对照；合成用例首次呈现发布使帧失效，动作被`STALE_TOKEN`拒绝。夹具在触摸前要求按钮矩形连续1秒稳定，原合成用例增加初次呈现发布的只读观察/主线程空闲屏障再取新帧；截图失效仍只允许有界只读重采，所有动作及实际计数断言不变，没有跨后端自动重放。这说明新弹窗确实需要防布局变化，但不推翻前面位置不变的有效过滤对照。

最终`device-r6`：实际拼音1方法、安装页对照1方法（12.445秒）、原合成四模式1方法（11.173秒）均通过；两个诊断方法分别运行于自有instrumentation进程。主APK仍为上述576cf372，最终测试APK SHA256 `a604102573f96a60a404477760661665cfb220e818361a12f7ad7aea4ad01fe8`。安装按钮仍为敏感、普通服务仍非Accessibility Tool，稳定中心(894,1372)的无障碍手势完成但包不存在，Shell同坐标安装成功；四模式再次取得与r4一致的接受/拒绝计数及flags2048记录。原始轨迹、截图、完整日志、JUnit、制品hash及恢复信息保存在忽略的`build/hxa282/touch-filter-retest/`。

最终VM退出0，原AX null/0与来源AppOp default恢复；所有实例均关闭、日常5554未操作，未新增Shizuku服务或真实模型调用。合成View直接观察到安全过滤拒绝；系统安装器归因来自敏感标记、同坐标对照及源码的共同支持，没有侵入安装器内部。本次不关闭抖音完整自主旅程或修后连续长稳，亦不推广其他API/OEM。生产代码、isAccessibilityTool声明、权限边界和隐藏确认门禁均未修改。

完整`./scripts/check-all.sh --all`最终退出0（`host-all-closed.txt`），包括225项host脚本回归、JVM/格式/Detekt、四variant lint/APK及制品/Runtime边界；Developer AndroidTest最终另编译通过（`compile-last.txt`），文档检查通过。首次新增屏障使旧测试超过方法长度门禁，提取为`awaitInitialPresentation`后完整复验，调用顺序及断言未变；设备冻结APK来自提取前的同等序列，末次源码提取仅经主机/编译验证，未重复设备测试。失败的静态检查、初始化与坐标布局尝试均保留，没有删测或更改门禁换通过。

复现前置：显式Owner授权的自有API36实例，Developer及匹配测试APK；自建或核实最小无代码`com.helix.validation.installfixture`包，暂存到测试APK的`cache/install-fixture.apk`，只为该测试来源临时允许安装。本轮合成APK SHA256 `e5200e015008af15cb2214f485df83456f76fa4dce42eae62a30e56edefa8c4d`。两个诊断方法使用`helixInstallerTouchProbe=true`、`helixTouchReview=true`分别执行，方法间核对恢复；缺少前置不得借用日常设备或第三方安装包。暂存、Shell控制、独立observer及原始日志均属诊断夹具，不属于Helix自主业务结果。

## 验收设计

- 复用支持的`run-owned-emulator.py`、实际拼音夹具、连续Mobile Use资源夹具与原两小时报告器。冻结主/测试APK、记录所有者PID；只读AVD退出不保存快照。
- 固定两次文件汇总、Skill、浏览器、离屏查找、中文表单；文件检查原CSV未变及实际两行输出，浏览器检查实际DOM及单次点击，Mobile Use保留实际表单结果和后台返回验证。
- 抖音完整旅程使用单个明确原Turn，最多一次人工上下文提示；两次请求及失败均保存，不通过模型“完成”或仅Turn COMPLETED判定业务成功。模型自行选择、调用工具和操作系统UI；测试宿主不下载APK、不安装或卸载抖音、不操作其界面。
- 被动观察包状态、窗口、截图及media诊断，独立核对安装后卸载。包断言通过只证明安装/卸载顺序；观看或真实强制登录分支必须另核对实际证据，证据不足为pending/inconclusive。截图变化或前台停留本身不能证明视频播放。[Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)提供编解码/渲染信息，但目标App未必向系统报告足够可读统计，不能把没有指标误判为没播放。
- 测试Provider明确来自`generic-openai`自定义入口，使Debug关联头可实际验收。录制器只增加本机元数据记录，原请求/响应字节转发；记录Turn/model-call、代理request ID，原SSE响应ID可从私有原帧独立核对。
- 临时AX共用`OwnedAccessibilityBackend`连接、失效重绑与恢复夹具；停止原执行后清理，恢复前后设置独立比对。没有改生产loop、默认预算、权限或资源阈值。

官方入口：[抖音移动下载页](https://m.douyin.com/app_download)。主机只核实页面，没有替Helix获取安装包。模拟器外部网络/厂商策略和模型效果单独归因，不将失败隐藏为通过。

## 当前执行

主机、固定业务、实际抖音旅程、Debug真实请求关联和修后两小时长稳正在本轮逐步验收；实际命令、退出码、结果和清理终态随后补入。旧R5整体FAIL_FUNCTIONAL保持，服务端有效模板运行时hash及EAGLE单变量对照仍需服务器侧支持。

早期夹具尝试保留于忽略的`build/hxa282/journey-*`：首次用了不存在的App级Spotless任务；随后修正JsonObject写入、格式化长行，完整检查发现共享夹具规模与循环复杂度，已提取共同AX生命周期并合并退出条件。未删除或放宽任何业务/资源断言。

### 固定业务与抖音原失败

`build/hxa282/real-tasks-r1`：自有API36/Developer实际拼音通过；Skill、浏览器、离屏/普通表单各两次，共8次业务及8次后台返回通过。文件两次计算正确但额外写入总计行，保持原断言失败；明确“恰好两行、无总计”后，r2固定两次实际文件读回通过，不修改源CSV、不放宽断言。r1抖音夹具在Provider连接验证前创建会话，被生产就绪门禁正确拒绝，实际App任务尚未发出；已改为连接验证后创建，并让早期失败同样进入清理。

r1主APK SHA-256 `49e28d32c3d738a907fa747616d7fa6c869b820ddf8678342bab9d77ebcfda71`，测试APK `0cb3bfb8736cf3ded9ca6a8c23b649990f6ffd9926c869daeb56984a52ca5bab`；r2主APK相同，测试APK `c559389e9b50376d01df5eb9497854c36b30614e2f77aeabfe78abf80698cc48`。两次owned VM与录制器均退出0，AX恢复前后完全一致；日常5554未操作。

`real-tasks-r2`实际抖音原Turn为`7e9a8a91ebddcafe4a168822d5a09826`，111次model call、116次工具调用，COMPLETED只表示响应结束：模型自行核查包、探索网页、打开Chrome、使用官方下载入口、下载并打开安装确认、开启Chrome对应未知来源设置。独立目录/截图核对APK为359,908,938字节，SHA-256 `88075d689a674d645f383250359ea82dc5280ffce2f9a5404f4e11e4449e90cf`；Chrome显示官方CDN `lf9-apk.ugapk.cn`。Install在截图中可见，但AX观察缺失该节点、注入动作后未推进；当前AX路径被阻挡，模型请求人工确认。宿主没有代点、下载安装或卸载抖音。

唯一补充上下文的Turn为`f68312d020bcdb308c2fd0996774da16`，原15分钟期限到达而失败，停止后执行已退出并保留清理轨迹；全程未观察到包安装，观看、登录分支和卸载均未执行，完整自主旅程failed。最初追加询问临时Shizuku范围未获新答复；随后复核Owner已明确允许测试中安装Shizuku及必要系统权限，本轮也明确请求自有API36验证，因此按既有授权准备只读实例的临时后端，而非把未答复视为批准。关闭只读VM未保存360MB下载或临时设置，不声称已由Helix完成卸载。

### 实际请求关联

r1共71个原model call，r2共146个原model call：各自全部完整HTTP200、带两项Helix身份、代理request ID及单个SSE响应ID，无离线解析错误。另有5个/5个无Owner的连接探测POST，按设计没有Helix身份。原帧、元数据与对应表在忽略的`wire/`、`wire-identities.json`，没有把消息/图片/账号信息写入仓库。这验证客户端发送及代理响应关联，不代替服务器侧日志保存验收或证明EOS根因已关闭。

### 测试发现的两项生产UI问题

1. 普通字体英文“Take over”在固定80dp宽、48dp高的控件内换行并被裁切。当前按实际本地化/字体/变换后的文字及原生padding计算宽度，保留状态与控制窗间距；按钮高度自适应、最小48dp，窄屏单行省略。审批、接管、查看会话和隐藏确认逻辑保持。[Android触控目标指导](https://developer.android.com/guide/topics/ui/accessibility/apps#large-controls)是尺寸依据；不引入桌面布局或复制竞品代码。新增真实原生窗口四种语言/字体组合及回调回归，待当前设备运行结算。
2. 补充Turn的`view_image`已COMPLETED，但原model call `e5b077183b223f850a2bd5693fd19116`在出网前等待普通图片披露。原InteractionReceipt创建/到期为UTC19:21:52.876/19:31:52.876，最终CANCELLED；没有收到允许或替用户上传。普通披露600秒等待与图片预处理是来源，不能归为模型提前stop。新增精确session/turn/到期的非阻塞显示快照，浮窗提示查看会话完成图片确认；普通图片回执仍独立、不成为工具Approval。工具与Skill提示当前画面优先`ui.screenshot`，不通过`view_image`重读保存文件绕过披露。两项新JVM测试保留拒绝/取消/过期/跨身份保护；真实浮窗及离线图像管线补验待运行。

### 修后UI与图像结算

`layout-and-model-r1`实际按钮四组合（en/zh-CN × 1/2倍字体）、只导航控制、App实际图片管线6方法、Qwen离屏/普通表单各两轮共4业务、8次后台返回通过。图片等待提示已真实显示，但夹具立刻查询尚未发布的AX按钮节点导致空值；增加原有有界只读节点等待后，`layout-and-model-r2`该方法通过，原失败保留。两次VM和录制器退出、AX设置一致恢复；这是SDK原生窗口与App图像管线/模型分层验证，不把SDK夹具当完整App任务。

上述主APK SHA256 `4e81bfdb4ff407ae4c7b587227c840545ecefb855e3e508786ccdf4b97575422`，测试APK `feb124f03310393a4c4028331babbb6312d3804a9ac368727e8c2bcf5c4bc113`。33个修后原model call全部完整200并有三层关联。普通图像等待、取消、过期和跨身份JVM类最终两渠道各13/13；全仓`app-disclosure-host-all-r3.txt`及SDK/两渠道AndroidTest通过。显示回调只读宿主已发布身份/到期快照，不在主线程访问DAO，不改变真实回执权限。最终点击指引明确把观察中的clickTargetToken/suggestedClickToken值放入ui.click的token，不把观察字段作为额外参数；本项来自实际schema拒绝，修后模型效果尚未补验。

### Shizuku夹具与最终绝对边界

后端仅复用已核对[官方13.6.0发布源](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)的缓存APK，SHA256 `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`，启动UID2000、实际系统授权Helix。没有开启自启动，不操作日常或账号。r1准备脚本误将“未安装”的pm path返回1作为命令失败；r2确认授权后，instrumentation的Binder接收未准备而未发出抖音任务，清理又将正常pidof返回1误作异常。均修正并保留原记录，不填业务通过。

`real-tasks-shizuku-r3`请求原Turn `d5cff14fa4e13e806d63c88340fcaab8`。夹具创建的UiAutomation占用系统连接，Shizuku随后connect失败，close报告“Cannot call disconnect() while connecting”。`ui.launch`结果NEEDS_REVIEW后自动只读恢复完成，原Turn为INTERRUPTED/RECOVERY_INSPECTION_FINISHED。这是实际UNKNOWN保护，不是模型stop被误识；没有进程死亡或启动恢复根因的证据。主产品部分连接失败时清理可能覆盖原始异常的诊断硬化仍可后续主机处理；本轮不通过吞异常改变动作结果。夹具改为Shizuku模式不连接instrumentation UiAutomation，并恢复临时普通浮窗AppOps。

最终`real-tasks-shizuku-r4`原Turn `2439bbd0b3da94d90706fade3045a2a1`、74个model call记录/74工具记录；已完成73个原网络model call，UTC20:58:22开始、最后请求21:03:31/响应结束21:03:37，均完整200及三层关联。TOOL消息可见实际Shizuku后端；未再出现上述CONNECTING竞争，但模型仍在浏览器/手机页面探索，包状态所有采样均未安装。第一轮在UTC21:03:43绝对窗口边界停止，清理后CANCELLED，无UNKNOWN；不启动补充请求。观看、真实强制登录分支和卸载未执行，完整旅程failed。时间停止不是成功，后端可用不证明业务完成。

本轮两小时以首个真实POST UTC19:03:43为起点，最晚21:03:43停止。没有启动新7200秒长测，也没有将多次短测时长相加当连续长稳。最终VM退出0、Shizuku server停止0且pidof为空、AX恢复null/0、浮窗恢复default，30009录制器关闭，仅日常5554仍连接；没有持久保存新授权、下载或第三方App。实际模型/设备窗口已结束，之后仅主机检查、文档与提交。新的完整长稳和自主App复测需要另指定验证窗口；服务端有效模板hash及EAGLE单变量端口仍缺输入。

本轮完整网络原model call计384（71+146+33+61+73），全部有身份/代理/SSE关联；该数不包含无Owner探测、不把记录已创建但未发网的调用算网络请求，也不当作全部业务成功率。记录器/私有原帧及逐任务结果均在忽略的`build/hxa282/`目录，原失败未覆盖。

### 窗口结束后的主机硬化

Shizuku bootstrap原来在connect失败后直接close；close的InvocationTargetException会覆盖原connect异常，与“rethrow unchanged”注释不符。现释放后仍抛出同一个原异常，清理异常只作为suppressed附加；自抑制及取消保持。三个新JVM故障/取消测试通过，不从catch返回成功，不更改UNKNOWN、授权、物理容量或恢复规则。SDK连接竞争是夹具原因；此修正只改可诊断性，不宣称独占连接问题或业务失败由它治愈。该源码变更在设备窗口结束后实施，设备补验pending。

归档夹具还修正了tool_results的外键查找：使用ToolCallEntity.id，而非上游callId；原轨迹的TOOL消息仍包含完整事实，原失败不改写。最终完整主机/两渠道及SDK测试APK、文档检查继续结算；没有新的模型、设备或账号操作。

最终全仓 `./scripts/check-all.sh --all` → `build/hxa282/final-host-all-r6.txt` 退出0：source/JVM、Spotless、Detekt、四种variant lint/APK、锁文件、制品/Runtime边界通过。r5清理辅助函数的通用异常静态告警保留；该catch仅附加清理异常且始终重抛原异常，局部说明后重新完整通过，没有弱化行为测试。三个清理单元用例通过；最终双渠道/SDK AndroidTest编译另见`final-androidtest-compile.txt`。文档校验851份/265 HXA通过。本轮未推送、发布或覆盖日常APK，当前源码晚于设备冻结4e81制品的Skill说明和异常诊断硬化只做主机验证，设备pending。

### 主机与后续验收

夹具阶段全仓`journey-host-all-r2/r3.txt`、双渠道AndroidTest及最终文件/夹具修正编译均通过；首组业务/安装准备失败完整保留。UI/披露修正完整主机、SDK及两渠道测试APK继续验证；此前布局检查触发函数数量静态门禁，保持同一个窗口owner并记录局部结构取舍，不删除行为测试。修后主APK真实表单及最长两小时长稳未执行，仍pending。运行中有效HF模板完整hash、关闭EAGLE对照仍需服务器侧测试端口/配置，公开服务信息只确认既有版本/解析器/EAGLE，不能确定实际模板内容。
