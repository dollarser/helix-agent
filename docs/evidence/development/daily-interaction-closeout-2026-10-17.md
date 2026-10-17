# 日常交互收尾 — 2026-10-17

关联：[HXA-306](../../development/tasks/HXA-306.md)。本轮范围已完成本地收尾。Owner 允许分阶段本地提交、日常模拟器和已配置 Qwen；保留现有数据，不推送、不创建应用 Release。上一轮导航补丁为 `1dd08d93`；本轮产品修复为 `a7a52313`、最新会话布局修正为 `8529d5bf`，最终候选从包含夹具整理的干净 `d3422488` 构建。之后的结案文档不改变候选生产代码。

## 已确认原因与修改

- 抖音首页在 Helix 中可读取 DOM，但主体高度为零；同机 Chrome 与独立 Activity WebView 显示正常。只读 DevTools 几何对照显示原生视口 635 CSS px，而网页根容器、main 与轮播主体高度为 0。Compose 的 AndroidView 默认布局使 WebView 使用不定高度；宿主明确 MATCH_PARENT 后，真实百分比高度回归由 ratio=0 失败转为通过，不改网站代码、UA、TLS 或 Safe Browsing。
- 标准模式的终端后端拒绝启动，但入口只显示通用失败。现在页面明确能力要求，通过既有风险确认显式切换并启动；后端再次检查 Profile。
- 输入草稿缓存提前把本地化“新建会话”落盘，之后发送不再经过命名路径。未命名改为持久空标题，输入受理同事务一次初始化；手动标题不覆盖、旧占位标题不猜测、不额外请求模型。目录/标题读写事务避免回写旧值。
- 按 Owner 最新要求，完全访问仅文字使用两主题提醒色，不加背景或警示符号。输入区去掉默认文本框多余内边距，保留语音、正文、添加、停止和发送同排；权限/模型按钮缩减视觉内边距，仍保留可访问点击区域。
- 会话列表同轮间距由 8dp 收紧为 4dp，用户新消息保留分隔；工具去掉重复的上下内边距，思考折叠区取消双层留白，仅思考消息不再绘制空 Markdown 正文。底部固定 48dp 空白改为 8dp；工具/思考标题保留 48dp 点击高度，正文段落、审批、恢复及滚动跟随语义保留。
- 浏览器常驻清理行移除，菜单清理说明真实范围；Cookie 回调后刷盘，缓存不删除网站存储，空白页也能清理已关闭页面留下的共享缓存。导航触控目标和读屏标签、地址提交/键盘行为补齐，现代主文档错误保留错误码并保留用户停止语义。设备回归进一步发现旧映射手写的 Android 错误数值与 SDK 不符，现直接引用 WebViewClient 常量，避免 DNS/SSL/重定向相互误报。
- 双语 README 增加手机操作、网页成果、持续任务场景和独立开源定位；豆包手机助手是替代体验方向，不声明官方关联或系统级等价。

## 验证过程

原始页面、Chrome、独立 WebView、DOM 几何和编译/执行日志保留在忽略的 `build/hxa306/`。视口回归初版夹具缺少 LifecycleOwner 导致进程失败；换为 ComponentActivity 后，旧生产代码得到真实 ratio=0 断言失败，修复后 1/1 通过。一次编译失败后误用旧测试 APK 出现 ClassNotFound，已标记为无效运行，不计产品或修复证据。后续安装由成功构建串联。

第一轮主机文档门禁指出 HXA 缺少规定章节；后续静态检查指出临时诊断源码、命名与长行问题，均保留失败记录并修正。临时探针不进入生产或常规测试源集，正式视口回归独立保留；未弱化断言或跳过失败用例。AndroidTest 编译还指出平台 WebResourceError 构造器不可直接伪造和一个被替换的旧宿主缓存测试调用；改用实际资源失败、收紧真实主文档错误断言并在 JVM 验证停止语义，最终编译通过。

设备首轮 46 项中 43 项通过、1 项失败、2 项按原条件跳过，真实不可达主机用例因上述错误映射失败；保留失败记录并修正生产映射及平台常量回归，严格断言不回退到 UNKNOWN。修复后的同一浏览器选择集逐方法核对为 44 项通过、0 失败、2 跳过；跳过项为需专用诊断次数的原生 WebView 对照和需专门宿主 runner 的连续资源长稳，未用框架的 OK (46 tests) 冒充全通过。真实 Room 标题持久、手动优先及事务回滚 3/3 通过；修正错误映射后的完整主机门禁退出 0。

新增 UI 回归覆盖窄屏/大字体、原同排布局、命令与权限保存失败、思考流式转正文、工具展开、长内容及滚动跟随。首轮拼音失败时 Gboard 仅显示实体键盘工具条；展开后确认原设置为小鹤双拼，而夹具输入的是全拼 `nihao`，因此候选只转换前缀。另发现旧夹具可点击弹出动画中尚在屏幕外的节点，补上可见范围检查，并分开等待节点与真实触摸注入。临时选择全拼后，基础输入框与 Helix 输入框都严格提交“你好”且不发送；最后恢复原小鹤方案。未用中文注入替代拼音，也未放宽精确文本断言。期间不完整的执行器中断日志、错误 runner 启动及失败构建均保留，不纳入通过结果。

## 最终验收与候选

| 层次 | 本轮结果与证据 |
| --- | --- |
| 完整主机 | `./scripts/check-all.sh --all` 退出 0；Python 276 项，当前任务图 JVM 6,088 通过、8 原条件跳过、0 失败；文档/ADR/i18n/敏感信息、Spotless/Detekt、双渠道 lint、Debug/Release/R8、依赖锁与制品边界通过。日志 `build/hxa306/host-all-complete.log`；不统计旧任务残留 XML。 |
| AndroidTest 编译 | Browser、Storage 和双渠道 App 测试 APK 编译通过；人工终端门禁夹具和新增导航断言只编译，其实际行为另由下方 R8 手测覆盖。 |
| API36 独立设备测试 | 浏览器 44 通过、2 专用诊断跳过；真实 Room 标题事务 3/3；会话 UI 接受集 36 项通过，由 `ui-device-accepted.log` 的 35 项及修正环境/夹具后的 `pinyin-final-pass.log` 1 项组成，保留此前失败，不描述为同一次无失败全量运行。 |
| ARM64/R8 候选 | `./scripts/build-release-apks.sh arm64-v8a` 成功；两个包均为 0.0.6 / code 1、不可调试、沿用原开发签名。成功制品、源码状态、哈希和签名保存在 `build/hxa306/candidate-final/`。安装读回字节一致，两渠道各 56 张数据库表安装前后摘要完全一致，无重置。 |
| R8 可见交互 | 首阶段 `a7a52313` 的标准模式终端说明/取消/显式切换、实际终端输出、Consumer 无终端入口、浏览器清理说明及取消通过。最终候选再次确认抖音完整主体与下载按钮实际绘制、清理底栏已移除、两主题纯文字完全访问样式及紧凑同排输入；自动/手动标题在追加消息和重启后保留。 |
| 真实 Qwen | 已配置的 Qwen3.8-27B / OpenAI Chat Completions：首阶段完成网页读取和创建后读回文件；最终候选重新执行浏览器及文件读取两个 Turn，均 COMPLETED，工具结果落盘、内容哈希和模型实际消费分别核实。文件独立读取为 11 字节 `HELIX306_OK`；浏览器返回实际标题与 5 个下载标签。 |

最终真实模型浏览器 Turn 为 `cf688dbd882c37f4a65f757e64f3b42c`，文件 Turn 为 `b908364689cebc398cb6d042c7d7cb1e`。浏览器首次调用未发现工具，随后多次检索恢复；网页从加载中经等待转为可快照，不能将整轮成功改写为每次工具调用都成功。未点击下载或安装。最终截图为 `final-full-access-light.png`、`final-full-access-dark.png`、`final-conversation-spacing.png`、`final-douyin-visible.png`，均在 `build/hxa306/`；早期有背景的权限截图已被最终样式替代。

主要改动集中于 `app` 的 Composer/Message/Timeline/Thinking 展示、`ChatDraftStore`/`ChatService`/`SessionCatalog`、Developer 人工终端入口，`core/storage` 会话标题事务，以及 `feature/browser` 的宿主尺寸、导航错误和清理 UI。标题不新增表或兼容迁移；完整路径清单可从上述本地提交查看。竞品取舍及契约沿原 HXA、Agent/Browser ADR 更新，没有启动整体架构重构。

## 清理与剩余边界

最终重启确认没有活跃 Turn，测试文件和手动/自动标题仍在。两个正式应用均保数据保留为最终 R8；自有测试 APK 和 ADB 转发已移除，临时终端已停止并结算，Standard 能力模式及会话需要批准模式恢复；浅色模式、Consumer 跟随系统语言、原中文小鹤方案和实体键盘下显示 IME 的设置恢复。日常模拟器保留运行并显示 Advanced，已配置模型可继续手测。

两项专用诊断为 `rawPlatformWebViewLifecycleControl`（原生 WebView 重复创建/销毁对照，需指定次数）与 `continuousResourceSoak`（同进程 FD/线程/PSS 连续资源诊断，需专用 runner 和时长）；本轮未运行长稳。8 项 JVM 跳过来自四种外部样本/公开服务验收在两渠道重复执行时未提供本轮开关/输入，不当作通过。真机、其他 OEM、抖音下载安装/播放及 FFmpeg/PRoot 媒体端到端未在本轮重测。Qwen 仍出现冗余工具检索，保留为观察到的效率问题；本轮没有扩大为工具发现架构重写。没有当前范围内的未修复功能阻塞；本地通过不等于远端 CI、商店或应用 Release 已完成。

## 2026-10-18 追加收尾

Owner 追加要求补齐收尾并更新文档，并明确允许卸载日常模拟器已有抖音及其数据；Helix 和其他应用数据保留。起点 `998bfc96`、工作区干净；两个已安装 R8 包仍与上文干净 `d3422488` 候选字节一致，后续文档不改变生产实现。新增证据保存在 `build/hxa306/closeout-2026-10-18/`，与前一轮的跳过、失败和通过分别记账。

- `python3 scripts/connector-samples.py test offline --output .../connectors-offline`：已有本地来源/哈希核验样本，Consumer、Developer 各 3 项通过、0 跳过，覆盖 QwenWork、WorkBuddy 和固定公开配置；不执行包内脚本或迁移账号。
- `python3 scripts/connector-samples.py test public-mcp --output .../connectors-public`：两渠道各 1 项通过、0 跳过，实际访问公开文档 MCP。此前 8 项跳过现已得到这次独立执行证据，不能将它们改写为原完整门禁当时已经执行。
- `:feature:browser:assembleDebugAndroidTest` 成功；日常 API36 独立 Browser 测试宿主以 `helix.rawWebView.count=100` 运行 `rawPlatformWebViewLifecycleControl`，逐方法解析 1/1 通过。它是原生平台生命周期对照，不是 24 小时稳定性证明。
- 两渠道 `McpToolDiscoveryTest` 33 项、`ModelToolExposureOrderTest` 6 项分别以 `--rerun` 重新执行，共 78 通过、0 跳过。原真实模型 Turn 的 14 份持久请求 manifest 显示 `browser.open`、`browser.snapshot` 始终存在；模型多次搜索期间没有丢失这些 schema。初始 `browser.tabs` 未曝光时正确拒绝，精确搜索后曝光并成功执行。当前证据支持“已有工具仍被模型重复搜索”，不支持加载丢失或需要放宽绑定校验；保留为模型效率限制，不借收尾重写发现架构。

- `python3 scripts/run-hxa103-browser-soak.py emulator-5554 --workload browser --preset pilot --output .../browser-soak-15m`：runner 判定 PASS，独立 Browser Debug 测试宿主单 PID、122 个循环、设备端 906.98 秒；FD 峰值 174、线程峰值 51、PSS 峰值 125,927 KiB，未触发资源门禁。宿主采样 30/31 成功，最后一次进程退出后无可解析 PSS，原始失败保留；设备端门禁为判定依据。后半程同一模拟器还有真实 Helix/Chrome 操作，不宣称独占空闲设备。它验证 15 分钟夹具与资源边界，不是生产 R8、2 小时或 24 小时长稳结果。已移除本轮独立 Browser 测试安装。

### 抖音任务与悬浮窗

最终 R8 的 Qwen 会话 `d268bb335f29019feef288c7d8894773` 首先完成已有抖音卸载；Turn `bc888ca3d3cbd3dc59818979464c7fc9` 的工具、模型报告和独立包查询/数据目录缺失一致，宿主未代做卸载。安装阶段使用当次官网页面给出的 Android 短链接作为明确输入，不记为无辅助发现入口。

首次安装 Turn `696f62df4a515a21cde9883f1442f7b0` 未完成业务任务：实际上已重复下载两份完整 APK，宿主只读拉取后的签名校验和包信息确认是抖音 33.6.0，不能称为最新版。模型未打开下载管理器，把 `chrome://downloads` 写入地址栏却未成功提交，之后下拉通知栏手势返回 `ACTION_OUTCOME_UNKNOWN`，原调用保留 NEEDS_REVIEW。自动只读复核 Turn `0319a63ab855acec1c98044b7e88bd56` 完成后，原 Turn 结算为 INTERRUPTED / RECOVERY_INSPECTION_FINISHED；复核未重放写操作，也没有安装/播放成功证据。期间一次宿主 `uiautomator dump` 与 Shizuku 的 UiAutomation 注册冲突，该探针失败，不是 Helix 应用崩溃；后续停止使用这一竞争探针，改用截图与只读持久记录。

Owner 反馈悬浮窗不可见：日常 Developer 没有系统“显示在其他应用上层”权限，且无可用无障碍窗口通道；Shizuku 操作授权不会授予此权限。通过 Android 权限设置开启 Developer 悬浮权限后，在续测的 Chrome 下载管理器上实际观察到半透明状态和接管/查看会话窗口，WindowManager 与截图一致（`overlay-active-chrome.png`、`overlay-active-windows.txt`）。Consumer 权限未变。该插件窗口仅在绑定任务活动且离开会话时显示，截图和物理输入期间暂时隐藏；权限修正不等于取消这些既有条件。

把独立核实的下载事实和 Chrome 菜单入口追加给模型后，续测 Turn `e9ab022367fe2fe8c3c9a2b6ac84e84c` 在约 265 秒、50 个模型步骤后 COMPLETED。模型通过 Mobile Use 打开已有 APK、系统安装、接受普通首启条款并拒绝通知权限，播放公开视频后卸载，最后回到原 Developer 会话。宿主没有代做安装、播放或卸载；这是有下载事实/导航辅助的通过，首次尝试失败保留，不记为一次自主任务成功。

独立采样确认安装前不存在、安装后存在、最终包及数据目录再次不存在。播放采用 `douyin-resume/samples.json` 中 114.06～180.07 秒的 7 次前台截图/音轨采样：66.01 秒内同一视频画面持续变化，目标进程有活动 AudioFlinger 音轨；模拟器启动带 `-no-audio`，不声称主机听到了声音。模型的两次 60 秒 `ui.wait` 实际返回 INCOMPLETE_SNAPSHOT，不作为计时或播放证据；状态栏分钟变化也不能单独证明经过 60 秒。播放结论依赖独立时间采样。

三张成功工具截图均有持久 TOOL_OBSERVATION 绑定与后续已完成请求中的消息引用，模型描述与画面对应；另一次启动切换中的 TARGET_CHANGED 明确保留。独立拉取最后一张工具图片的哈希与回执一致，画面没有悬浮窗残留，而设备旁观截图中的悬浮窗仍可见，验证了截图隐藏与恢复。完整回执在 `build/hxa306/final-model-d268bb335f29019feef288c7d8894773/`，请求和附件关联在追加证据目录；没有抓取或归档 Provider 凭据。

### 追加清理与边界

测试已结束；恢复原 Standard 能力模式、测试会话“需要批准”和原中文小鹤输入子类型。手动取消仅本轮新建会话的 Mobile Use 选择，其他会话与全局插件配置保留。Developer 系统悬浮权限保留开启，供 Owner 后续使用；未替 Consumer 开启。两份本轮下载的重复 APK 逐一核对与归档原件哈希一致后删除，旧下载和其他应用数据未清理。模型会话、截图及回执保留，日常模拟器继续运行；本轮没有新增转发或终端。

本次追加没有修改生产代码或重建 APK；复用原完整主机/制品证据的依据是生产源与安装字节未变。补验不证明全自主导航可靠性、其他网站/应用、真机/OEM、2 小时/24 小时长稳或 FFmpeg/PRoot 媒体端到端。模型重复下载、冗余检索与时间推断仍是观察到的质量限制，不用放宽权限/结果未知边界掩盖。未推送、发布或启动整体重构。

追加文档后的 `./scripts/check-all.sh --source` 退出 0：276 项主机脚本测试及文档/ADR/i18n/敏感信息门禁通过；`git diff --check` 通过。最终两个已安装 APK 再次读回与候选一致，Consumer 数据库摘要完全不变，Developer 的 Provider 配置摘要不变、增加本轮有归属的任务证据；无活跃 Turn。具体摘要在 `final-state.json`，不将敏感配置正文写入证据。设备没有启用普通无障碍服务，Shizuku UiAutomation 连接保留给日常使用。新增跟踪改动仅为本记录、HXA-306 与 status，未提交本次追加文档。

### 通知栏追加核查

Owner 随后要求核对下拉是否成功及模型为何绕到通知栏。本次只读检查已有回执、公开进展文字、系统日志和当前源码，没有重新操作设备。首次安装 Turn 实际尝试四次下拉：依次为 ACTION_NOT_DISPATCHED、NO_ACTIVE_SESSION、NO_ACTIVE_SESSION、ACTION_OUTCOME_UNKNOWN；中间两次复用已失效 frame，不能将其错误解释为用户授权被撤销。最后一次为 `(540,40) → (540,1500)`、600ms。日志在北京时间 03:33:59.008 为 NotificationShade 注册返回回调、03:33:59.445 清除，随后恢复 Chrome；03:34:06 的宿主截图和只读复核节点都仍是下载网页。因此没有稳定展开并读取通知栏的成功证据，不能说四次完全未触达系统窗口，也不能将未知回执改成无副作用失败。

模型公开进展明确表示想查看 Chrome 下载完成通知并直接点击安装。它曾尝试 `chrome://downloads`，但没有成功提交导航；在此前的重新下载确认中已经知道同名文件存在，却仍选择再下载。没有通过菜单打开“下载内容”后才改变路线，属于路径选择与状态判断低效；这不是 Helix 固定下载流程。后续菜单路径成功证明入口本身可用。实现已有 `ui.system(action=notifications)`，但首次安装末次请求的 13 个 `ui.*` schema 不包含 `ui.system`；模型没有按需发现它，不能称为忽略已加载的系统动作工具。

另确认一个需要定向复现的实现疑点：`PrivilegedGestureExecution` 在每段输入前调用 `privilegedGesturePermitted`，即使 wholeDisplay 授权也先要求当前目标与原窗口相等；输入开始后只有 revision 被忽略，窗口改变仍拒绝。`PrivilegedGestureRunner` 随后取消剩余触摸并返回 UNKNOWN。通知栏展开改变目标窗口时，这条路径可能取消用户原本期望的跨窗口手势，与本次短暂系统窗口现象吻合；原回执未记录失败的是授权、窗口检查还是输入注入，尚不能把该分支定为现场唯一根因。本轮未改生产实现；后续修复须区分起始 frame 校验、执行中显示/授权边界与输入所属窗口，不能简单移除目标/权限检查，也不能用已有辅助任务通过覆盖此疑点。

### 通知栏与过程说明修复

Owner 追加要求实施修复，并明确手势本身通常会改变界面。以 `998bfc96` 加本轮局部补丁工作；开始时仅有上述三个文档的未提交追加，全部保留。旧 R8 的独立复现 Turn `0c36c373e7bbebb52a9f077d5c5253a9` 先启动 Chrome、等待稳定、取得新 frame，再执行同一 `(540,40) → (540,1500)`、600ms 下拉，仍为 ACTION_OUTCOME_UNKNOWN；后续只读复核看到 Chrome 而非稳定通知栏。该失败保留，没有回写成成功。

修复沿 Mobile Use 插件内部实现：`PrivilegedGestureGuard` 将初始完整 frame 校验与执行中范围检查分开。全手机且无排除应用时持续验证物理显示映射，允许活动窗口变化或根节点短暂缺失；受限范围允许同一应用内窗口变化，但轨迹必须仍在当前窗口且无其他窗口覆盖。原授权、锁屏、取消、截止时间、ACTION_CANCEL 清理、物理槽占用及 UNKNOWN 不变。`ShizukuHierarchy` 从显示器取得独立坐标事实，未增加执行域、授权或安装专用状态机。Skill 的详细参考页补充发现系统动作和按已观察的下载入口导航；简短入口仍满足原预算。契约沿 ADR-PERMISSIONS-001 更新。

过程说明问题来自展示层按未经整理的 Provider 文本统计长度/换行：实际记录大量短进度含首尾空行，误触发折叠并占掉三行预览；纯空白也能产生展开后无正文的入口。`AssistantMessageBody` 只在显示时修剪首尾空白，以有效正文判断折叠；短进度直接显示、空正文没有展开入口，长内容仍保留完整展开与搜索。原消息存储、模型上下文、思考标签、同排输入布局和触控范围未改变。

主机执行 `./scripts/check-all.sh --all` 最终退出 0：276 项脚本测试、6093 项 JVM 通过/8 项既有条件跳过，Mobile Use 311/311 包含 5 项新增手势回归；格式、Detekt、双渠道 Debug/Release lint、构建、依赖锁及 APK/运行边界通过。首轮新增 Skill 内容超出入口预算，移入详细参考页而不放宽断言；另因本机磁盘不足导致文件夹具/打包失败，仅清理可重建 Gradle 缓存后重跑，原证据和用户数据保留。期间格式失败日志也保留，最终结果见 `build/hxa306/notification-fix/host-all-final.log` 与 `jvm-summary.json`。

日常 API36 / ARM64 的 Consumer Debug `MessagePresentationDeviceTest` 9/9 通过、零跳过，包含 3 项新增空白/短进度/长预览回归，以及原展开、搜索、流式思考和点击边界。使用签名/存储预检保数据临时安装，测试前后 56 张表摘要完全一致；测试 APK 随后移除。执行命令为 `adb -s emulator-5554 shell am instrument -w -r -e class com.helix.app.ui.MessagePresentationDeviceTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`，原日志经 `scripts/instrumentation_junit.py` 校验。

`./scripts/build-release-apks.sh arm64-v8a` 成功，双渠道 0.0.6 / code 1 / 非 debuggable / 既有开发签名的 R8 已保数据安装。构建来源、补丁、APK 身份和安装前后摘要保存在 `build/hxa306/notification-fix/candidate/`；这是新的候选，不能引用旧包的设备结果代替。本节后续文档整理不改变已安装代码。

最终 Developer R8 / API36 / Shizuku / 指定 Qwen 的两个定向 Turn 均通过：`8252ac80cd9596f516413942857862d4` 用新 frame 执行完全相同的 600ms 下拉，7 次工具调用后完成；ui.gesture 返回 DISPATCHED，后续语义观察为 SystemUI、截图发布成功，绑定图片确实进入后续已完成模型请求。独立宿主采样持续看到 NotificationShade，终态截图可见完整通知栏，未用 Turn 完成或模型自述代替效果。`0a2ceddd8e5c0877e1f2d9f7631354e0` 经 tools.search 发现 `ui.system`，执行 notifications 后再观察，4 次调用完成，独立采样同样确认通知栏保持展开。两个检查均未下载安装或点击通知；通知栏按测试要求留在前台，结束后由测试人员关闭并返回 Helix。

证据在同一目录的 `gesture-run/`、`system-run/`、对应模型回执及 `gesture-attachment-proof.json`。修复后允许界面变化，没有扩大用户范围或自动重放失败动作。新增回归覆盖初始 frame 过期、执行中显示 ID/尺寸/旋转变化、受限范围跨应用/覆盖窗口拒绝，以及同应用窗口切换；既有 Runner 取消、清理失败、延迟开始及 host 会话隔离测试继续通过。普通 Accessibility 路径经源码复核只在派发前检查初始 target，本次没有改写该路径。

测试结束后恢复该测试会话“需要批准”，取消临时 Mobile Use 选择、保留原 browser 选择；中文小鹤子类型和实体键盘下显示 IME 已恢复，Developer 悬浮权限继续保留。两个已安装 APK 再次核对与本节新候选一致，均无活跃 Turn；Consumer 56 张表摘要不变，两个渠道 Provider 配置摘要不变，Developer 保留本轮会话、工具和图片证据。自有 UI 测试 APK 已移除。只读终态摘要在同目录 `final-state.json`，不保存配置中的密钥正文。

最终文档整理后的 `./scripts/check-all.sh --source` 退出 0，276 项脚本测试及文档、ADR、双语资源、敏感信息门禁通过，日志为同目录 `source-final.log`；`git diff --check` 通过。构建快照比对确认代码、测试及插件资产没有在成功构建后变动，仅三个收尾文档追加结果。

本轮是上述两个缺陷的有界验收，不重跑抖音安装/播放/卸载整条业务链，也不代表 Root、普通 Accessibility、真机/OEM 或长稳通过；单应用窗口变化本轮由 JVM 回归验证。安装包及当前配置留给 Owner 手测，未提交、推送、发布或启动整体重构。
