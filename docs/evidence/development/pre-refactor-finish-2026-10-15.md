# 重构前收尾与内置插件分层复核

日期：2026-10-15。Owner 要求继续未完成收尾，并逐项分析直接内置和内置市场的归属；随后指定使用日常模拟器，替代未连接的 OnePlus。本轮只使用现有 `Helix_API_36` / `emulator-5554` / API36 / ARM64，没有启动新设备或操作真机。

## 范围与实际改动

保留 `main` / `348632a8979467eaf83e300c07bef7cad67fba5c` 上既有 APK、交互和架构文档工作。开始时的工作区状态与 diff、后续命令、测试报告和制品身份均在忽略目录 `build/pre-refactor-finish-2026-10-15/`；本轮没有提交、推送、发布或实施 HXA-286～294。

- GitLab 精选条目原先引导使用旧式 `api` scope PAT，与[官方认证契约](https://docs.gitlab.com/user/model_context_protocol/mcp_server/#authentication)不符。改成 OAuth 类型，三语言提示说明 OAuth 或具有 MCP Execute 权限的细粒度 PAT，并加入现有目录测试。没有真实 GitLab 登录或业务调用，也没有迁移用户凭据。
- 独立 Release 测试宿主增加 Mobile Use 配置、会话选择与进程重启检查；目标保持 non-debuggable R8，宿主不引用生产内部类，不加宽 keep 规则。运行器增加显式 `--suite mobile-use`，仅允许 Developer 隔离包，启用自有无障碍服务前保存设置，结束移除自有服务、保留其他服务并清理测试安装。
- 最终审查补齐清理失败的结果语义：即使方法已通过，自有安装清理不完整也必须将 `result.json` 标为 failed，保留原始失败原因和逐包状态；新增 Python 反例验证，不让调用方误读成完整通过。
- 插件分层与逐项理由已合入原[工具/插件评审 §6.2](../../research/topics/tool-and-plugin-competitive-review-2026-10-15.md#62-直接内置与内置市场的重新划分)，同步原 ADR 与导航。没有新增第二份重构方案，也没有迁移插件或删除既有能力。
- 完整门禁复现继承的 Detekt/lint 并发故障：Detekt 从仓库根遍历，在排除 `build` 前已遇到 lint 正在替换的 SARIF 文件。将扫描入口限定为 Kotlin 源码目录及既有脚本夹具目录，保留原受检文件、规则和报告，不通过关闭并发或跳过检查掩盖问题。

## 验证记录

最终候选使用新的 `com.helix.validation.finish20261015` 身份（Developer 加对应后缀），含本轮 GitLab 修正，仍是测试签名，不能用于宣称正式分发。APK、R8 mapping、独立宿主及完整性数据在 `final-candidate/`。下表只计最终候选，前轮制品在当前模拟器的基础检查与 `mobile-release-12/` 首次旅程通过单独保留，不重复累计。

| 检查 | 结果 | 证据与范围 |
| --- | --- | --- |
| 完整主机门禁 | passed，退出 0 | `final-gates-3.log`；Spotless、Detekt、双渠道 Debug/Release lint、构建、42 份依赖锁与渠道/进程/制品边界均通过 |
| JVM 报告 | 5,914 passed、8 skipped、0 failures/errors | `host-summary.json`；来自本轮 Gradle 测试目标的 5,922 项有效报告，包含缓存/UP-TO-DATE 与既有 spike。8 项为两渠道各 4 项可选外部 Connector/用户归档输入，未计通过 |
| 最终源码与文档复核 | passed，退出 0 | `final-source.log`；253 项 Python 回归、890 份 Markdown、277 个 HXA、37 份 ADR、2,298 个三语言资源键及秘密扫描通过 |
| 两渠道最终 ARM64 R8 候选与独立测试宿主 | 编译 passed | `final-isolated-build.log`；目标 non-debuggable 与包名由运行器再次核对；两包各 9 份中文帮助资源通过 UTF-8/标题读取检查 |
| Standard / consumer R8 基础 UI | 2 passed，0 skipped | `final-consumer-basic/`；启动、模型设置导航、返回、实际安装版本 |
| Developer R8 基础 UI | 2 passed，0 skipped | `final-developer-basic/`；同上 |
| Developer R8 Mobile Use | 1 个完整旅程 passed，0 skipped | `final-developer-mobile-use/`；搜索并只选择自有应用、保存不自动选择会话、手动选入、实际 force-stop/重启后仍选中、手动撤销/再次重启后仍撤销、保存的应用范围仍在 |
| 测试清理 | passed | 三次 `result.json` 均确认宿主与目标卸载；最终只读枚举仅保留原三个日常/测试包，无障碍服务恢复 `null`、总开关恢复 `0`，见 `final-packages.txt` 与 `final-accessibility-*.txt` |

`final-candidate/` 阶段设备合计 5 个方法通过。测试宿主本身可调试，目标 Helix 是独立 UID 的 R8 Release；两者不混淆。完整门禁中的 Python 252 项通过；最后清理失败回归补充后，定向运行器 7 项及最终源码门禁 253 项通过，不用 APK 构建或设备启动替代。

本轮具体入口：`./scripts/check-all.sh --all`；隔离编译使用 `-I scripts/release-smoke.init.gradle -PincludeReleaseSmoke=true -Phelix.smokeApplicationId=com.helix.validation.finish20261015 -Phelix.abi=arm64-v8a` 执行两渠道 `assemble*Release` 及 `:tests:release-smoke:assembleDebugAndroidTest`；设备调用 `python3 scripts/run-release-smoke.py`，指定 `--serial emulator-5554`、对应 `--application-id`、冻结 APK/宿主、全新输出目录和 `--suite basic` 或 `--suite mobile-use`。重跑细节见[运行器说明](../../../scripts/device_baseline/README.md#independent-r8-ui-checks)。

初次尝试暴露的是测试夹具问题：未取消“整个手机”草稿开关便点击禁用的应用选择按钮；精确文本误匹配了搜索框自身；页面切换和选择刷新尚未完成便断言节点；滚动辅助误将屏幕下部可见按钮当作需要继续滚动；触摸发生在选择控件未就绪期间。已改为显式范围操作、区分编辑框与应用条目、等待实际页面/可操作状态，并执行明确的无障碍选框动作。早期编译中的错误平台常量、Detekt 条件复杂度与格式问题也已修正；其中一次编译失败后的运行仍使用旧宿主，保留为旧夹具失败，不算新实现验证。保留每次失败与清理原始记录，不将重试计作新增通过。

## R8 执行边界追加验收

Owner 随后要求在同一日常 API36 模拟器、隔离安装中补验执行中取消、UNKNOWN 与重启后的旧节点失效。本段是后续实际执行，不将前面的配置/选择旅程等同于工具执行验收。

追加发现并修复了一处产品缺陷：`ui.wait` 已进入读取循环后按 Stop，插件返回通用 `Cancelled`，Dispatcher 无从确认效果，最终错误地留下 `NEEDS_REVIEW`。改为插件通过已有 `CancelledWithEffectTruth` 明确报告 `AUTOMATION_WAIT_CANCELLED`、无副作用、无需核查，没有修改 Dispatcher 的保守默认，也没有按工具标签推断所有操作都安全。新增 JVM 回归在第一次实际观察后触发取消，检查没有节点或全局动作。

独立宿主通过公开 UI 保存回环 Provider、插件应用范围和当前会话权限；工具请求仍经过真实 R8 Dispatcher 与无障碍后端。所有效果属于测试 Activity，未使用第三方应用或真实 Provider。只读 SQLite 探针仅访问隔离包，模型展示标记用于关联本地调用，实际结果保留 Helix 生成的原执行身份；不写数据库、不使用生产内部类或新增 keep 规则。

验证语义按现有 ADR：运行中 Stop 的只读等待属于无副作用取消，调用结果保留执行开始后的失败事实，Turn 正常取消；进程死亡中的手势则关闭旧调用/Turn 为 `INTERRUPTED`，不伪造结果或人工核查，这仍属于未解决的 effect uncertainty。它不同于进程存活时的 `NEEDS_REVIEW`，不能为了断言统一而修改恢复实现。

`execution-final-gates-3.log` 的完整主机门禁退出 0，包含 254 项 Python、文档/ADR/三语言、秘密扫描、静态分析、JVM、两渠道构建及制品/进程边界。相关报告为 Mobile Use 294 项、工具框架 260 项，均无失败或跳过；框架报告包含 UP-TO-DATE 复用。后续独立夹具变更另以 `execution-build-27.log` 通过编译、Spotless、Detekt 与 lint，不以编译通过替代设备结果。

以下三项使用相同 `execution-final-candidate/developer.apk`，均为 API36 ARM64、non-debuggable R8、测试签名隔离包；每项 1 passed、0 skipped。取消/UNKNOWN 使用冻结的 `test-host-5.apk`，旧节点使用后续修正的 `test-host-12.apk`。目标 APK 未变，没有为新夹具重复计数已通过项目。

| 边界 | 实际结果 | 证据目录 |
| --- | --- | --- |
| 执行中取消 | 确认 `ui.wait` 为 RUNNING 后按 Stop；调用保留 `FAILED / AUTOMATION_WAIT_CANCELLED`，Turn 为 CANCELLED；重启后同一记录不变、调用仅 1 次 | `execution-matrix-cancel-1/` |
| UNKNOWN 恢复与不重放 | 确认实际手势触摸计数 1 且调用 RUNNING 后终止进程；旧调用和 Turn 为 INTERRUPTED、无伪造结果、无人工核查记录；二次重启同一身份与事实不变，触摸仍为 1 | `execution-matrix-unknown-1/` |
| 旧节点失效与新节点可用 | 保存真实节点 token 后重启并重连自有无障碍后端；旧 token 返回 TOKEN_UNKNOWN，新观察的 token 调用成功，实际按钮点击仅 1 次；没有重新修改会话选择或授权范围 | `execution-matrix-stale-8/` |

三份 `result.json` 均确认自有目标/宿主卸载和无障碍清理。最终只读核对 `execution-final-packages.txt` 仍仅含原三个日常/测试包，`execution-final-accessibility-services.txt` 为 `null`、`execution-final-accessibility-enabled.txt` 为 `0`。没有覆盖或清空日常安装。运行方式沿用前文命令，增加 `--suite mobile-use-execution --boundary cancel|unknown|stale`。

失败尝试保留在 `execution-*` 目录：表单/页面转换的旧节点、连接检测与能力检测分离、模型 wire ID 与本地执行 ID 混淆、UiAutomation 命令不解释 SQL 引号、进程死亡误套 live `NEEDS_REVIEW` 断言均为夹具问题。Android 拒绝了注入的中断触摸，因此改为真实手势进入后终止目标进程。旧节点场景还修正了测试输入：无障碍设置文本/按钮状态不足以证明实际输入，改为系统确认前台启动、键盘输入、清理测试端缓存与真实触摸发送。

`execution-matrix-stale-6` 已真正送出新请求，但 Android force-stop 后后端断连，产品正确返回 BACKEND_UNAVAILABLE；测试随后只重连自有服务。`stale-7` 已确认旧 token 为 TOKEN_UNKNOWN，正向新 token 则在启动动画中被正确拒绝为 STALE_TOKEN；最终 `stale-8` 先通过 `ui.wait` 确认目标按钮与窗口稳定，再取新 token 完成正向对照。这些不属于“旧 token 可继续执行”的产品缺陷，也没有放宽原校验。`execution-cancel-9` 是上述产品取消缺陷的实际失败，不能与夹具问题混同。

早期 `execution-final-gates.log` 的 Detekt 失败与后续夹具长度/格式失败均保留：将固定取消结果移至文件级、局部拆出稳定性断言并整理测试辅助，没有压制规则。最新完整门禁与独立宿主结果如上。

## 竞品对照追加收尾

Owner 在上述验收后要求“结合竞品继续收尾优化”。初始主机修复阶段的设备、真实模型及账号均为 not requested；随后 Owner 明确追加模拟器真实模型验收，见下方实际记录。不复用前轮 R8 设备结果宣称新候选已验。原工作区差异与失败/成功命令单独保存在忽略目录 `build/mobile-use-closeout-2026-10-15/`，未提交或发布。

设计核对及一手链接见[插件评审 §5.4](../../research/topics/tool-and-plugin-competitive-review-2026-10-15.md#54-追加竞品核对与取消事实)：采纳按需装载完整可用能力及执行器终态事实，继续使用单一 Dispatcher。没有引入兼容层、第二个执行引擎或远程部署。

- 将语义工具入口、浮窗准备、等待及点击匹配观察阶段的确定性取消/超时改成带 `sideEffectFree` 的既有效果结果；统一插件内的派发前检查并复用设备工具已有的错误码。未派发时不误报待核查，实际动作回执与 watchdog 的未知状态保持。既有动作后观察断言继续核查“已派发不被取消抹除”。
- 默认曝光加入 `ui.device`；单独发现截图时补齐同来源 frame 工具。新增回归覆盖请求会话隔离、禁用/撤销、当前请求准入范围及插件/版本/runtime 来源不匹配；依赖不会授权或执行工具。HXA-286 的声明式贡献重组仍未实施。
- 修正 `ui.system` 中锁屏撤销授权的旧提示，明确暂停执行、保留会话许可及解锁后重新观察；未改权限存储或 Android 锁屏边界。
- 真实模型首轮成功完成截图、中文输入及一次完成，但没有调用 `ui.launch`，最终仍停留在目标页。工具已经曝光；这属于收尾指导遵循缺口，不是返回 API 执行失败。插件必要指导改成明确的“验证业务 → 返回 → 观察原对话 → 回复”顺序，并保留停留、持续前台活动、手动步骤与取消例外；不在宿主增加强制跳转或推断成功。

失败基线：`red-tests-2.log` 实际执行 15 项插件定向测试，其中 7 项失败；4 项发现测试中 2 项失败。更早一次新增夹具误用了不存在的 descriptor 方法，属于编译失败，修正后才得到上述发现运行结果。后续首次编译漏改截图发布前检查的调用点、格式化参数/行长及旧测试对通用 Cancelled 的断言均已定位并修正；保留日志，不把编译失败计为运行失败或通过。

`full-gates.log` 对应 `./scripts/check-all.sh --all`，退出 0。源码/文档/ADR/三语言、秘密扫描、Spotless、Detekt、两渠道 Debug/Release lint、JVM、构建及制品/进程/42 份锁文件边界通过。`host-summary.json` 记录 Mobile Use 299/299、工具框架 260/260；App 两渠道共 2,589 项报告，2,581 passed、8 条件 skipped，零失败/错误，包含 UP-TO-DATE/缓存复用。新增 4 项终态测试、4 项发现测试（两渠道各执行）及 1 项动作进入后未知回执保持测试；既有准备阶段断言改为同时要求无副作用与零动作，不删除反例。

Owner 随后明确授权模拟器真实模型验收：指定的日常 API36 `emulator-5554` 在线，既有服务目录确认 `Qwen3.8-27B`；使用受限回环转发，凭据只驻留内存、临时文件启动即删除。最终隔离 Developer Debug 的返回/停留各重复两次，4/4 业务旅程 passed、0 skipped；另有 1 项真实连接/能力探测准备检查 passed，不混入业务计数。每轮均实际成功截图、输入中文、滚动并只点击一次完成；返回轮实际确认原对话标题/焦点，停留轮保持结果页，35 次工具调用均无 FAILED 状态。证据为 `real-model-2/`，首轮失败不删除。该样本不证明任意视觉目标定位、坐标手势或普遍模型遵循率。

Debug 运行器已卸载两份自有包、撤回自有反向端口并恢复无障碍原值，原 Helix 包清单相同。真实模型转发进程已核对身份后关闭，临时凭据文件不存在；下方 R8 可控取消/重建边界为独立执行结果，不将上述 Debug 结果继承为 Release 通过。

设备夹具在原两项真实模型旅程上增加可选 `helixRequireScreenshot=true`：必须存在成功截图且完整结果包含 `pixelsAttached=true`，并继续通过独立测试页面查询验证 `TEST_DONE:你好 Helix`、按钮恰好点击一次、原会话身份、实际窗口焦点与对话标题。首次新夹具编译的可空结果读取错误已修正；`isolated-build-2.log` 才是通过的编译，不能把此前失败算成运行。`real-model-1/` 保留上述实际返回失败及成功清理。提示修正后冻结独立 `isolated-final/` 制品，再执行重复旅程，不改变用户任务提示或削弱原断言。

最终 R8 目标为 `isolated-final/release.apk`，由 `isolated-build-3.log` 编译通过，运行器核对 non-debuggable 与隔离包身份；三个执行边界及配置旅程均使用同一目标和修正后的独立 `r8-host-3.apk`，共 4 passed、0 skipped：

| 当前候选检查 | 断言事实 | 证据目录 |
| --- | --- | --- |
| 执行中取消 | `ui.wait` 实际 RUNNING 后 Stop，结果为 `AUTOMATION_WAIT_CANCELLED`、Turn CANCELLED，重启原记录相同、仅一次调用 | `r8-cancel-3/` |
| 进程死亡 UNKNOWN / 不重放 | 手势实际进入、触摸计数 1 后终止进程；原调用和 Turn INTERRUPTED、无伪造结果；两次重启保持同一身份，触摸仍为 1 | `r8-unknown-1/` |
| 恢复后旧节点 | 旧 token 为 TOKEN_UNKNOWN；重连自有后端后通过新观察的 token 点击一次，未重新授予会话范围 | `r8-stale-1/` |
| 配置与显式撤销 | 保存应用范围不自动选择会话；手动选入并重启后仍选择；手动撤销并再次重启后仍撤销，原保存范围仍在 | `r8-configuration-1/` |

R8 初次失败 `r8-cancel-1/` 发生在配置准备阶段：固定 400ms 延迟后立即断言选中状态。改为有界等待实际选中，再执行相同动作/结果断言；随后三个执行边界均通过，没有修改产品选择或权限逻辑。`r8-cancel-2/` 是重编宿主遗漏 `helix.smokeApplicationId` 导致 Android 包可见性不匹配，在方法构造时失败；修正构建参数后使用 `r8-host-3.apk`，失败未计入通过。`r8-fixture-build-2.log` 的 Spotless/Detekt/宿主 lint 与编译通过，`r8-fixture-build-3.log` 确认正确目标包可见性的宿主编译/lint 通过。

最终默认双渠道 Debug/Release 和两渠道 AndroidTest APK 另经 `default-final-build.log` 编译、Spotless/Detekt 通过；默认产物的渠道/进程/Release 边界由 `final-artifacts.log`、`final-release-artifacts.log` 通过，不将隔离测试身份留在正常 App 输出中。业务入口为 `MobileUseReturnModelDeviceTest` 的两方法，显式参数 `helixRealModel=true`、已配置 Provider/model 与 `helixRequireScreenshot=true`；R8 入口为 `python3 scripts/run-release-smoke.py --serial emulator-5554`，指定同一冻结目标/宿主、新证据目录与 `--suite mobile-use-execution --boundary cancel|unknown|stale`，配置旅程用 `--suite mobile-use`。全部运行仅操作自有合成页面。

所有最终运行器均确认自有安装卸载与无障碍清理；`final-cleanup.json` 额外核对原三个 Helix 包清单相同、原无障碍值 `null/0`、自有反向端口撤回、模型端口关闭和临时凭据文件不存在，全部 passed。日常应用未覆盖/清库，未启动新 AVD 或操作真机。最终 `final-source.log` 退出 0：254 项 Python、890 份 Markdown/277 个 HXA、37 份 ADR、2,298 个三语言资源键与秘密扫描通过；与前面的完整主机门禁和实际设备证据分开。正常独立测试宿主也已恢复默认参数并编译通过，见 `default-test-host-build.log`。

本切片改动入口：[取消/截止事实](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/AutomationTermination.kt)、语义/设备/点击匹配执行器及浮窗包装器；[默认工具](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/MobileUsePlugin.kt)、[发现配套](../../../app/src/main/kotlin/com/helix/app/mcp/ToolDiscoveryWorkset.kt)、[插件必要指导](../../../extensions/mobile-use/src/main/assets/plugins/mobile-use/required.md)。回归入口为新增 `AutomationTerminationTest`、`MobileUseToolDiscoveryTest`、既有 `MobileUsePresentedExecutorTest`/`AutomationPostActionObservationTest` 以及 [真实模型旅程](../../../app/src/androidTestDeveloper/kotlin/com/helix/app/eval/MobileUseReturnModelDeviceTest.kt)。审查确认这些路径继续使用原会话与完整来源绑定，未改变授权存储、任务恢复或 Dispatcher 默认未知语义。

watchdog 已抢先结束时，插件迟到的结果不自动覆盖原调用的未知事实；本次不宣称解决全部取消竞态或恢复矩阵。发行身份/签名、HXA-286～294 及暂停专项边界保持。

## 审查结论与后续边界

直接内置、默认选入会话、得到授权是三个独立决定。Mobile Use 在当前支持它的 Developer 渠道保持独立原生插件；Browser、Documents 在两渠道随包提供。基础帮助和安装指导直接可用，第三方账号连接、专项工作流适合市场选装；Chat Reply 与重复文件整理指导是后续收敛候选，不在旧结构里再建依赖兼容层。

不建议为“重构前补齐”增加远程执行、子 Agent、定时循环、OCR 或新的执行域。APK 中的原生运行时不能通过移动市场卡片变成按需下载，移动仍随包保存的 Skill 文本也不等于明显缩小 APK。

上述有界 R8 取消、进程死亡 UNKNOWN/不重放及旧 node 失效对照已通过；仍不覆盖全部 live UNKNOWN 回调组合、旧 gesture frame 矩阵、Root/Shizuku 或其他 OEM。SQLite 的模拟器只读取证不代表 Mobile Use Root 后端验收。R8 边界测试使用受控回环 Provider；追加的 Qwen 真实业务旅程是独立 Developer Debug 证据，不继承为 R8 真实 Provider 通过。未进行第三方账号业务或媒体端到端；Notion/Linear 已结案，提前结束专项和长稳继续暂停。

正式 v0.0.6 的候选源码冻结、发行身份、签名及发布仍按[发行条件](../../development/release-readiness.md)处理，不能用隔离测试签名包宣称发布完成。预览不保留旧数据的决定已落实；日常模拟器应用不因此获得被清库或覆盖的授权。

主要文件：[市场目录](../../../app/src/main/kotlin/com/helix/app/marketplace/MarketplaceCatalog.kt)、三语言资源与目录测试；[等待工具修复](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/tools/AutomationTools.kt)与对应 JVM 测试；[配置旅程](../../../tests/release-smoke/src/androidTest/kotlin/com/helix/smoke/MobileUseReleaseTest.kt)、[执行边界宿主](../../../tests/release-smoke/src/androidTest/kotlin/com/helix/smoke/MobileUseExecutionReleaseTest.kt)及独立回环/Activity/只读探针；[运行器](../../../scripts/run-release-smoke.py)及 Python 回归；[Detekt 构建规则](../../../build.gradle.kts)、原插件评审、ADR-CONNECTORS-005、status 与本记录。
