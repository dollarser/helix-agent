# HXA-245：全量模拟器验证与修复

日期：2026-10-03。所有者在[首版主机交付](hxa245-plugin-overlay-host-2026-10-03.md)后明确要求全量模拟器测试和修复。基线 `0619409dd1d7`，保留既有并行改动；不提交、不推送。本轮双渠道全量常规清单的失败类已修复复验，API29/36 扩展故障矩阵各 **37/37 通过**。条件跳过、历史失效夹具与耐久不确定项单列；不代表所有设备、账号和发布条件通过。

## 执行范围与证据

仅使用本任务创建的 API 29/36 ARM64 只读 AVD 实例，按 owner PID 清理，不使用真机。两渠道当前常规清单各为 Consumer 242 类、Developer 327 类，通过已有隔离 runner 逐类执行；分阶段恢复、可选账号/样本及耐久测试不混入常规通过数。源码身份、APK 和日志保存在忽略目录 `build/hxa245/`。

- `emulator-api29-attempt1`、`emulator-api36-attempt1`：首轮全量，保留全部失败与跳过。
- `emulator-api36-modules-attempt2`、`emulator-api29-modules-attempt1`：模块首轮及修复轮。
- `quickjs-api29-repeat`：原版崩溃连续复验 10 次，2 次失败，8 次通过。
- `quickjs-api29-fixed-repeat`：修复后相同崩溃断言连续 10 次通过；另有启动探针一次通过。
- `api29-corrected-recovery`：修复版 App 定向回归及恢复断点，进行中。

## 已定位问题

- QuickJS 真实竞争：EXECUTE 收到 `DeadObjectException` 可能早于原 Binder 的死亡回调。原实现抢先返回 `UNKNOWN`。修复增加有界等待，只有收到原死亡回调才报告 `CRASHED`；超时仍 UNKNOWN，不把传输失败当作进程退出或释放原生物理执行权限的证据。新增主机测试覆盖超时无退出证据及原回调到达。
- PRoot 产物发布真实缺陷：Android 私有目录的祖先别名与首次安全解析返回的规范路径混用，二次 containment 检查误拒绝合法文件。先用既有安全解析获得规范根，再维持同一根检查；新增祖先别名/重复发布/直接根符号链接拒绝回归，9 项产物 JVM 测试通过。此处使用合成产物，不代表 FFmpeg 编解码验收。
- MCP stdio 停止分支原先把 StopRequested 的即时回包直接返回，未走后续终态等待与结算。合并为有界等待原 Job 终态、核实后 reconcile 的路径；无终态证据仍保留空 record，不虚构取消完成。
- PRoot 输入准备到期原先抛普通 IOException，落入 FAILED；用明确的停止异常进入既有取消/超时结算，保留未启动、零退出码/输出的断言。
- Android SELinux 实测拒绝 app_data_file 硬链接，导致已验证产物无法发布。改用 CREATE_NEW 独占创建最终文件名、写入校验后的临时内容并 fsync，再校验和登记；绝不覆盖已有文件。中断遗留的部分文件不登记、重试摘要不符即拒绝，不能宣称文件系统回滚或原子完整发布。
- 终端尚保留原工作区时，旧清理准入误以为普通执行准入仍是全局互斥。清理现在与终端启动共享短期控制锁，查询原持久终端记录并拒绝删除其目录；记录不可用时保留目录。不对普通工具、无关工作区施加全局执行锁。
- 后台 Job 模型 fixture 连续搜索会替换发现窗口；改为模型脚本在每次所需工具前显式 tools.search，并为新增调用保留准确计数及足够预算，不改生产工具可见性或授权。
- 旧测试 fixture 与当前协调器不符：恢复/Goal 终止状态写死 step 0，而当前 turn 已开始 step 1。使用该原 turn 的实际 step，保留状态机校验。
- 两个 Kotlin 表达式测试隐式返回非 Unit，导致 JUnit 拒绝整个类。显式 Unit 后保留原断言。
- 设置导航与订阅连接按钮布局测试仍断言旧界面；按当前权限子页及账户→连接→模型顺序检查。Composer 空输入读取 EditableText，避免把提示文案当输入。重试收尾等待实际 UI 投影清除，保留最终不存在断言。
- API 29 测试调用 API 33 才有的 Parcelable API，改用已有 AndroidX 兼容接口。
- Runtime 测试仍依赖旧 companion/exported/signature 结构：CLI 绑定用本地测试服务提供真实 metadata，保留缺服务拒绝回归；PRoot 检查现有私有宿主进程边界。独立工具执行测试对齐已接受的无全局执行锁契约，仍验证原 ownership 身份、CAS 释放及逐调用 audit。
- CLI 真实账号测试的 ActivityScenario Rule 在 opt-in 检查前启动跨进程页面。移除未使用的 Rule，使未授权账号测试按原 opt-in 跳过。四个匿名公开订阅端点测试新增显式 `publicSubscriptionEndpoints=true`；缺失/false 跳过，非法值失败。首轮曾执行这四个匿名端点探针，未使用真实账号凭证或模型配额；后续默认不再访问这些端点，不能把首轮称为完全无外网测试。

## 首轮及中间复验（历史快照）

插件真实 AccessibilityOverlay 已在两 API 验证透视像素、状态区点击穿透、截图隐藏恢复及接管；API 29 另通过返回原任务及关闭窗口用例。最终同版重验仍在推进。

`device-fix-static.log`：Spotless、detekt、QuickJS JVM/lint、Mobile Use lint 与 AndroidTest 构建、CLI/PRoot AndroidTest 构建联合退出 0。编译不等于设备通过。所有失败原始日志保留，不以分类标签豁免修复。

API 29 两渠道首轮已完成：Consumer 242 类、Developer 327 类均无漏类或进程无结论；首轮分别有 11/26 个失败类，待修复重验覆盖，不能算全量通过。API 29 双渠道各 13 个实际进程死亡恢复断点及各 2 个旧版存储检查通过；驱动在收尾相对路径打印发生异常，原始 30 份通过 XML 保留，驱动已修正绝对路径。

API 36 修复后模块：QuickJS 79、CLI client 23、storage 64、files 38、android 30、PRoot IPC 5 项通过；其余模块有明确 opt-in/权限/分阶段跳过但无失败。两 API 的三个插件窗口用例均已实际通过，最终源码关联重验继续进行。

API 29 浏览器 25 分钟 pilot 已完成，但因模拟器缺失系统 UID/Binder 代理采样标为 `INCONCLUSIVE`，不是 PASS。API 36 pilot、App 首轮/修复重验和其余进程阶段仍在运行。真机、真实 Provider/账号、未提供模型样本、24 小时耐久及 FFmpeg 媒体端到端不因常规模拟器通过而获得验收。

后续进展：API 36 首轮亦完成，Consumer/Developer 分别 9/25 个失败类，无漏类或进程无结论。两 API 的 Consumer 修复复验各 12 类全部通过；Developer 仍有失败和测试清理阶段崩溃，未关闭验收。两 API 双渠道各 13 个实际死亡恢复断点及适用存储阶段通过（`api29-failure-retest`、`api36-failure-retest-2` 中 recovery-failures 均为空），Accessibility 强制停止后恢复通过。API 29 QuickJS 全部 79 项及实际通知权限下的 PRoot 3 项通过。API 36 浏览器 pilot 最终亦为 INCONCLUSIVE，原因同 API 29。

`device-fix-build-7.log`：相关 Developer/PRoot JVM、双渠道 App/AndroidTest APK、Spotless/detekt 联合通过；该版的剩余失败复验、原进程 SIGKILL 矩阵和会话输入/队列恢复正在执行，后续测试脚本计数修正尚需新包复验。

## 全量常规清单复验汇总

按原始完整清单逐类合并修复复验，保留每轮原始记录；不是宣称所有类在同一次运行或最后一次 APK 上重跑。Consumer 首轮 242 类、Developer 首轮 327 类均已执行，无漏类。`api29-failure-retest`、`api36-failure-retest-2`、两 API 的 `remaining-final` 与 `phase-retry2/retest` 覆盖原失败类。两 API 最后均为：

| 渠道 | 常规通过类 | 需要分阶段驱动的类 | 条件跳过类 | 剩余常规失败/崩溃类 |
| --- | ---: | ---: | ---: | ---: |
| Consumer | 210 | 13 | 19 | 0 |
| Developer | 258 | 13 | 56 | 0 |

类数不能与方法数相加。条件跳过包括未提供的真实账号、模型样本、Root 与耐久条件；不算通过。额外分阶段矩阵仍独立验收，不因常规失败归零而关闭。

已实际通过的专项：双渠道、双 API 的 13 个基础进程死亡断点；队列四种断点共 16 个渠道/API 批次；会话恢复 HXA-214/215 共 8 个批次；两 API 无障碍服务 force-stop/恢复；两 API PRoot 通知权限已授予条件下各 3 项。产物、后台 Job、Job await、多终端与无账号订阅边界的原失败类均已复验通过。相关目录：`conversation-recovery-final`、`queue-recovery`、`api29-failure-retest`、`api36-failure-retest-2`、`api36-notifications-final`、`api29/36-remaining-final` 与 `api29/36-phase-retry2`。

## 扩展恢复夹具修正

- 按现行 ADR 校验原 Turn、原 GoalRun、原调用和原账本不被重放/重复结算，允许至多一个带 `auto-recovery:<原 Turn>` 身份的 PLAN 核查后继；核查不是原外部动作成功。未知副作用对应原 run 的 `BLOCKED(NEEDS_REVIEW)`，不再断言所有 run 都是 INTERRUPTED。
- 合成模型在恢复阶段返回只读核查结论；原远端请求数、脚本 worker、文件/UI 实际副作用仍单独检查。没有放宽生产授权或删除原执行身份断言。
- Browser/A2A/PRoot 故障夹具显式准备当前工具发现窗口；PRoot 使用已内置 RootFS 的安装步骤，不把服务可绑定误当作 guest 已安装。
- 订阅夹具只替换账号就绪事实，保留真实 ChatService、原 Job、私有 Runtime 与回收链路；禁止访问外部 Wire/凭证。离线等待模型不持有网络前台租约，因此测试显式保留连接直到取消终态证据读取。真实账号边界仍由独立 opt-in 测试负责。
- `run-owned-emulator.py` 的准备阶段参数置于共享参数之后，保证 setup 不被 verify 覆盖；早期额外重启尝试未到达准备断点，保留为失败。连续新实例改用不同端口，遇到尚未消失的 ADB 记录拒绝借用。
- 悬浮窗口挂载后才分配 windowId；事件回调重新读取本插件窗口身份并保留隐藏窗口 ID，避免初次事件因身份尚未刷新而影响节点代次。新增真实可交互窗口归属断言；被动状态窗明确退出无障碍树，不以可访问窗口数证明像素是否存在。

最终受影响 JVM 与两渠道 Debug lint：`final-host-regression.log` 退出 0。当前文档/源码门禁 `final-source2.log` 与制品边界 `final-artifacts.log` 通过；最新悬浮窗修改后的编译/静态门禁 `device-final-build-15.log`、`device-final-build-16.log` 通过。扩展恢复矩阵与重启补验继续保留 **pending**，最后结果以随后的矩阵记录为准。

### 收尾过程快照（最终结果见下节）

当前 37 个扩展场景按原始矩阵及逐项复验合并：API29 32 通过、5 未关闭；API36 33 通过、4 未关闭。剩余均为 PRoot 4 个收取/恢复场景，API29 另有无障碍重连准备阶段未到达断点。`phase-final` 的 API36 Mobile Use 两个实际点击断点均通过；API29 完成结果断点通过。

`background-final/results.json` 已全部 **6/6 通过**：两 API 实际重启的正常/缺失记录结算各一次，以及两 API 普通主进程 SIGKILL 后原 Runtime PID 存活、原 Job 完成与重复收取幂等。之前 `setup-running` 的 instrumentation 进程被杀时，ActivityManager 的 `finished inst` 明确强停同包 `:proot`，该旧夹具的 SUCCEEDED 断言失败不能当作普通主进程保活失败；保留其失败记录，由实际普通进程场景提供单独证据。

PRoot 持久化/ACK cutpoint 使用既有 AndroidJUnitRunner 的显式测试断点分支暂不启动正常 AppContainer，以便对真实 Room/IPC 原结果精确暂停；之后用正常 runner 验证启动恢复。该人工暂停阶段不是正常启动性能或后台存活证明。工具详情滚出 LazyColumn 后展开状态丢失已修复为 rememberSaveable，新增真实滚动移出/返回回归，正在双渠道编译与设备复验。当前不能宣称 37 场景全通过。


## 最终分项结果

本轮已有完整常规清单加修复复验，不声称所有测试在同一 APK/同一次运行重新执行。当前生产变更最后构建为 `device-final-build-19.log`，后续到 `device-final-build-22.log` 仅修复测试/驱动；APK 身份保存在 `apk-closeout-sha256.txt` 及每次 runner 的 artifacts/installedApks 字段。

| 范围 | API29 | API36 |
| --- | --- | --- |
| Consumer 常规原清单 | 210 类通过、13 类分阶段、19 类条件跳过、0 剩余失败 | 同左 |
| Developer 常规原清单 | 258 类通过、13 类分阶段、56 类条件跳过、0 剩余失败 | 同左 |
| 扩展进程故障矩阵 | 37/37 通过 | 37/37 通过 |
| 插件真实窗口 | 3/3 通过 | 3/3 通过 |
| 工具详情/展开/滚动复验 | 双渠道各 14 方法通过，含新增滚出再返回用例 | 同左 |
| 普通主进程 SIGKILL、真实重启及缺失记录 | 3/3 通过 | 3/3 通过 |

扩展矩阵使用 `summarize-final-matrix.py` 按原 37 项身份合并每项最后一次结果；原失败文件不覆盖。`final-matrix-summary.json` 给出每项来源。最终 PRoot 四分支在 `api29/36-final-pass`，查询投影竞争在 `api29/36-query-repeat`；API29 同场景连续 3/3、API36 1/1 通过。UI 点击两个断点、原 Conversation grant 保留和不重复点击均实际通过；截图/透视/穿透/接管与原会话返回由插件窗口用例验证。

模块设备基线包含 storage 64、files 38、browser 40、QuickJS 79、Android tools 30、CLI client 23、PRoot IPC 5 等通过方法。API36 最后模块批次为 367 通过、24 条件/阶段跳过；随后通知权限分支和无障碍服务 force-stop 按独立阶段补验。API29 QuickJS 79 项及崩溃连续 10 次已复验通过，PRoot 原失败项也通过。各模块的跳过按原报告保留，不把跨批次重复方法累加为“唯一用例”。

受影响主机报告合计 **2,525 次通过、0 失败、8 条件跳过**：Consumer 1,077、Developer 1,175、Mobile Use 8、Plugin 44、Automation 105、QuickJS 103、PRoot 13。8 次跳过属于双渠道重复的 opt-in/缺少样本用例；汇总器保留 INCOMPLETE。两渠道 Debug lint（含最新测试入口）、Spotless/detekt、APK 构建、源码/文档/ADR/i18n/秘密检查和实际制品边界通过。最新日志：`final-host-regression.log`、`device-final-build-19.log`、`device-final-build-22.log`、`final-test-lint.log`、`closeout-source.log`、`closeout-artifacts.log`；最终文档/任务格式修正后 `final-docs-pass.log` 通过。

### 改动文件与审查记录

- 插件展示与通用宿主接口：`extensions/mobile-use/.../MobileUseOverlay{,State,Windows}.kt`、三语言资源、`extensions/plugin/.../PluginTaskHost.kt`/`PluginRegistry.kt`；App 的 `plugin/PluginTaskHostAdapter.kt`、`PluginTaskNavigation.kt`、两渠道 `AutomationModule.kt` 和 `MainActivity.kt` 接线。
- 自动化边界：`tools/automation/.../AutomationRuntimePresentation.kt`、`HelixAccessibilityService.kt`、`AutomationServiceController.kt`、`AutomationScreenshotCapture.kt`、`AutomationGestureDispatch.kt`。
- 设备发现的生产修复：QuickJS `JsExecutionClient.kt`/`JsProcessDeath.kt`；PRoot `ProotJobInput.kt`/`JobInputStoppedException.kt`/`ProotJobRunner.kt`；App `ProotProducedFiles.kt`、`McpStdioJobBridge.kt`、`ManualTerminal.kt`/`DeveloperManualTerminal.kt`、`DefaultAppContainer.kt`、`ui/ToolTimelineItem.kt`。后者用可保存状态保留滚出列表后的展开选择。
- 对应 JVM/AndroidTest、分阶段驱动及 `scripts/run-owned-emulator.py`；修改前读取当前内容，保留初始并行 Provider/恢复工作。本记录生成时未提交；后续所有者授权全仓收口后，HXA-245 相关改动分别进入 `22afd418`、`6612319f`、`510b625e`、`9e57a1e4`，未推送。完整初始/结束差异仅保存在忽略目录，不将全工作树归属本次功能。

### 剩余边界

- 两 API 浏览器约 25 分钟 pilot 的资源计量为 **INCONCLUSIVE**（系统 UID/Binder 采样不可用），不是耐久通过；未执行 24 小时正式耐久。
- 无真实账号/真实 Provider 模型验收、真机/OEM/低内存长期保活、未提供模型样本或 FFmpeg 媒体端到端验收。前台服务不保证永不被系统回收。
- `setup-running` 旧 instrumentation 夹具会被 Android 连同私有 Runtime 强停，原“原进程仍存活”断言失败已保留；其目标由 `background-final` 的普通进程真实 SIGKILL 验证，不能把旧夹具失败改记通过。人工 persistence/ACK 暂停阶段不代表正常 Application 启动流程；恢复阶段仍使用正常入口。
- 旋转/分屏/所有大字体布局及长期 OEM 行为不由本轮窗口三个用例覆盖。插件首版没有完整悬浮聊天输入或拖拽布局。
