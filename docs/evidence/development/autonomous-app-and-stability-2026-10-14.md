# 自主 App 旅程与修后稳定性验收

日期：2026-10-14；范围：[HXA-282](../../development/tasks/HXA-282.md)。Owner本轮明确授权自有API36、既有30008 Qwen、文件/浏览器/Mobile Use任务及最长两小时长稳。实际App为抖音官方下载、安装、观看和卸载，全部效果必须由Helix自主执行，可以提供上下文；不登录，必须登录才可观看时由Helix直接卸载。不会覆盖日常模拟器，不继承旧R5的结果。

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
