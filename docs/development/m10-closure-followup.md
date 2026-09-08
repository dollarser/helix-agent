# M10 验收收尾跟进

日期：2026-09-06。所有者要求完成 main 全量验证后剩余收尾，并授权在合理时接受 ADR-0009。
本记录是进行中的工作检查点，不是完成记录。原矩阵见 [main 验证报告](main-merged-verification.md)。

## 历史执行记录（状态以文末与当前待办为准）

- HXA-103：两个 24 小时连续进程测试已启动。API 29 的 WebView workload 于北京时间
  2026-09-06 00:37 开始；API 36 的 Room/FGS/resource 初轮在累计 FD gate 失败后停止；
  补齐 UI/RenderThread 清理采样等待，300 秒 pilot 通过后于 01:08 左右重新开始完整 24 小时，
  但在约 422 秒再次失败（FD 131 → 140）。新增描述符均为 goldfish 图形驱动句柄，
  正在用独立 Activity 生命周期负载诊断；尚不能归因于平台或宣告修复。
  结果分别在本地 `build/main-verification/soak-24h-api29/` 和
  `build/main-verification/soak-24h-app-settled-api36/`。前者仍 RUNNING，后者为 FAIL；失败轮次不可拼接为 24 小时。
  独立 Activity 60 轮、通知采样 1000 次、组合负载 35 轮、PSS 采样 100 次均未稳定复现增长，
  目前没有足够证据把先前 FD 越界定性为业务泄漏或平台故障。
  02:16 启动 `soak-24h-app-observation-api36/` 完整连续观察（PID 记录见同名 process JSON）。
  `--observe-breaches` 保持原峰值阈值，在采满时长后判定；任一峰值超限仍为 FAIL，
  不因后续回落放行。60 秒新控制流程 pilot 已通过，未强制 GC。
- `scripts/run-hxa103-browser-soak.py` 支持 `--workload browser|app`。两个 workload
  都已先通过 60 秒测量窗口的 pilot，预热另计。测试不重启进程；累计基线不按循环重置。
  FD 漂移上限 8、线程漂移上限 16、PSS 漂移上限 96 MiB 是本轮显式实验门限，
  不等同于物理设备性能预算。每轮执行真实断言并采样，宿主每 30 秒采集 meminfo；
  instrumentation 与 logcat 同时保留。超过 90 秒宿主采样空洞或进程提前退出判失败。
- 专用 `emulator-5590` 正在长稳，期间不得安装、强停或运行其他 instrumentation。
  `emulator-5592` 的 02:16 观察轮因设备断开，宿主 meminfo 返回非零而记 FAIL，不能作为完整时长证据。
  `emulator-5600` 属于另一工作线，禁止操作。额外 API 34 AVD `emulator-5594` 已启动供诊断与评测使用，不能在长稳设备上交叉运行。
- 额外专用 API 36 `emulator-5596` 的同进程交叉对照：前 608 秒不做宿主采样时 FD 约 134–135，开启完整 meminfo 后上升至 142，原阈值失败。证据 `app-soak-without-host-sampler-api36/`（result 明确标注后段启用采样）。`app-soak-local-sampler-api36/` 900 秒对照已 PASS（总耗时约 934 秒，预热另计），宿主改为 `--local`，设备侧断言/采样和门限不变，FD 保持约 131–132。03:16:55 已在该设备安装当前 consumer Debug/test APK 并启动独立完整 24 小时：`soak-24h-app-local-api36/`，PID/caffeinate 见同名 process JSON，APK hash 见目录内 installed-apks.json。期间禁止安装、强停和交叉 instrumentation；短测不是长稳通过。其余 5602/5604 并非本任务设备，不操作。
- 本任务已建立每小时自动跟进，完成前不把等待当作通过；无变化时不通知。

## 真实 crash/ANR

- 新增仅 Debug、受系统 `android.permission.DUMP` 保护的 `DiagnosticFailureActivity`，
  用普通应用进程触发异常/主线程阻塞，避开 instrumentation 自己的异常处理器。
  Release 不包含该组件。没有生产入口或新增模型工具。
- 真实 crash 暴露 `recordCrash()` 使用异步 preferences 写入，退出后摘要丢失，
  新进程读到历史异常类型。已改同步写入有界摘要，`finally` 保证继续委托系统异常处理器。
- API 34/36 已实际得到 CRASH 与 ANR 退出记录；新进程验证类型/hash、时间窗口及诊断正文排除通过。
  成功日志：`diagnostics-crash-fixed-api36/`、`diagnostics-anr-dialog2-api36/`。
  原失败保留在 `diagnostics-real/`、`diagnostics-anr-api36/`、`diagnostics-anr-dialog-api36/`。
- ANR 由真实 input dispatch timeout 触发，并通过系统对话框结束受影响 PID；
  直接 force-stop 记录为 USER_REQUESTED，不能作为 ANR reason 通过。
- 复跑命令：`python3 -B scripts/accept-hxa104-process-death.py SERIAL --phase crash|anr --output NEW_DIRECTORY`。
  需先安装同一源版本 consumer Debug 和 AndroidTest APK，且仅接受显式 emulator serial。

## ADR-0009 审查

设计的只读、无授权继承、共享父预算和无递归方向有合理性，但不能将现有 Spike 当作生产证明。
本轮反例证实负数 usage、累计整数溢出和 completion 自报 trusted 能绕过原模型边界；
已补反例并修复。并发准入的读/检查/写也改为在共享 journal 上原子执行，
新增 20 个同时准入者、跨 coordinator 实例共用两个槽位的测试。
9 个独立 JVM case 与 API 34 上 2 个 Android Spike case 全部通过。

ADR 已接受架构约束，依据和阶段划分详见 [ADR-0009](../adr/0009-bounded-local-orchestration.md)。
模型收益、真实 Room graph kill-point 与物理资源证据仍是生产启用门禁；没有将 Spike 接入产品。

## 固定评测与 Git

所有者确认本地 SGLang SSH 转发端口 30008 同时支持三协议；本轮已实际验证
`/v1/responses` 与 `/v1/messages` 均返回 HTTP 200 和协议对应结构。既有 Chat Completions
已由前轮真实 smoke 覆盖。没有缺失账号阻塞。`scripts/run-hxa100-provider-evals.py` 已驱动 9 个固定 Chat/Provider case，
API 34 上三协议全部通过，工具 case 实际进入生产 Dispatcher 并验证持久化结果后回填模型。
证据为 `fixed-provider-evals-context-api34/`；另保留首轮 Usage 尾事件误判及场景上下文
缺失的失败记录。用户 prompt 字节/hash 不变，固定系统上下文另记 hash。
新增 4 个 file case 已通过完整 ChatService/TurnCoordinator（`fixed-files-alternating-context-api34/`），
包括精确批准后写入与越界拒绝，累计 13/45。首轮暴露文本探测 16-token 预算截断，
经独立 SSE 对照后调整为 256 并增加回归测试；保留早期 fixture 上下文/角色顺序错误记录。
JavaScript 4 case 已通过（`fixed-javascript-audit-api34/`），取消用例由真实模型启动工具后执行宿主 Stop，验证 CANCELLED_AFTER_START；Plan 3 case 已通过（`fixed-plan-preloaded-source-api34/`），规范正文在 Plan turn 前预加载并记录来源/hash，Plan 本身无网络权限。浏览器 4 case 也已通过（`fixed-browser-stale-boundary-api34/`），正向点击通过实际 DOM 变化核验，失效 token 在精确夹具批准后由浏览器执行层拒绝，密码项为模型主动拒绝。累计 24/45；其余场景未计为通过。下一步继续补齐其执行器、
场景前提和逐 case verifier，然后通过 Android 生产 adapter/Dispatcher 运行并记录模型、
协议、精确 prompt hash、工具版本、实际设备与结果。不能以裸 HTTP 回答替代工具副作用验收，
也不能用原有 fixture 测试与无关模型回答拼成端到端通过。

当前未提交、未推送。本轮新增改动与前轮修复在同一 main 工作区；Git 收尾待当前验证收束，
保留失败证据和长稳真实状态。物理设备仍明确排除。

## 当前源验证

带齐两种 Connector 夹具并禁用 Test 缓存的最新 JVM 强制执行为 258 suites / 2433 tests，
0 failure / error / skip（`closure-jvm-current.log` 与对应 result JSON）。
当前产品源双 flavor Debug Lint、双 flavor Release assemble、Spotless/Detekt 已通过（`closure-current-gates.log`）；新增浏览器评测夹具独立编译验证中。执行中 ToolCall RUNNING 落盘与 QuickJS 取消分类已修复，框架/QuickJS 单测及 API 34 ToolScheduler 9 项、真实 JS 4 case 通过。另修复模型输出长度耗尽被误记 COMPLETED，新增 2 个反例先红后绿；最新源全量重跑待完成。

## 新发现的 Goal 集成缺口

固定 Goal case 的入口审计发现：`GoalDurableUsageLedger` 当前只有 Android 测试调用；生产 ChatService 没有 Goal/run 绑定或调用 ledger，GoalReducer 的 StartRun 也未在 app 运行路径消费。仅设置 GOAL mode 的 Turn 不能证明持久 Goal、跨 run 预算或 INPUT_REQUIRED 生效。HXA-102 的原子 ledger 单元能力已有实现，但完成记录中“活动运行每 5 秒 checkpoint”的产品层保证尚无调用链证据。3 个 Goal 固定 case 暂不记通过，需要补齐生产接线与真实模型验证；不得用测试直接调用 ledger 拼接成产品端到端。

## 工具回填回归

真实浏览器评测暴露 TOOL 消息只携带 512 字时间线摘要，截断了 node token 和标题。
已保留 Dispatcher 有界完整 payload，短摘要只用于 UI；离线端到端文件尾部回填回归 API 34
1/1 通过。当前产品源再次强制 JVM 2433 项无失败/跳过，双 flavor Debug Lint、Release assemble、
Spotless/Detekt 通过（`full-backfill-jvm.log`、`full-backfill-gates.log`）。既有 20 个固定 case 已按最新产品源与补全元数据重跑通过（`fixed-*-current-metadata-api34/`）；
连同最新浏览器 4 项，24 项已有当前完整回填产品源的实际通过证据。保留历史结果与失败轮次，不覆盖。

## Skill / MCP 与 LAN scope 接线

- Skill 4 项通过（`fixed-skills-fixture-verified-api34/`），归档路径穿越项明确标为宿主导入拒绝后
  模型报告，不冒充模型调用了不存在的导入工具。初版验证器错误拒绝只读 catalog 查询，失败日志保留。
- MCP 4 项通过（`fixed-mcp-lan-scope-api34/`），真实模型 + Android MCP SDK 连接本机合成服务，
  不是第三方账号验收。只读 annotation 没有绕过精确批准；注入不产生本地写入；取消项由
  真实模型启动后宿主 Stop，记录 CANCELLED 与已开始调用的失败/待核验边界，没有重发。
- 累计 32/45；Goal 3、A2A 4、Accessibility 3、Root 3 仍未在当前固定评测器完成。
- 首轮 MCP 无法连接，证据 `fixed-mcp-fixture-verified-api34/`。原因是 app 长期把 LAN scopes
  写死为空；已补 Advanced 设置中的显式精确 host/port 范围，MCP、http.fetch 和 Dispatcher
  每次读取当前范围，不由配置导入/模型/annotation 自动授权，不替代 Tool Approval。
  新增 5 项 JVM 用例通过，覆盖持久化/撤销、错误输入、数量界、Standard/metadata 不绕过及
  写入失败；真实设置页首轮语言定位错误已修正，API 34 新增/持久化/撤销 1 项通过（`lan-scope-ui-tags-api34.log`）。MCP 服务端调用计数 0/1/1/1 也确认无重发。当前源完整 JVM/Lint 重跑中；长稳 APK 为 LAN 接线前版本，不能覆盖后续新增代码。

## 当前 LAN 源码复验

LAN 接线后的完整强制 JVM 首轮 260 suites / 2443 tests，1 failure、0 error/skip；唯一失败为 developer 的 Connector 公开服务 HTTP/2 SocketTimeoutException。该类单独复跑通过（`lan-connector-retry.log`），未删除或跳过测试；首轮原始计数保留 `lan-closure-jvm-result.json`。双 flavor Debug Lint、双 flavor Release assemble、Spotless/Detekt 通过（`lan-closure-gates.log`）。文档 172、ADR 20、i18n 703 键与 secrets 检查通过。A2A 新夹具独立编译与 Detekt 通过，真实模型运行中；尚未计入通过数。

## A2A 固定评测补齐

`fixed-a2a-existing-task-api34/` 4/4 PASS，累计 36/45；剩余 Goal、Accessibility、Root 各 3 项。使用真实模型和生产 Android A2A 客户端，连接专用模拟器 reverse port 对应的合成 v1 JSON-RPC 服务；没有第三方服务或物理设备验收声明。

Card 检查保持 Plan 只读；发送项记录持久 Task ID 与完成状态；恢复项先通过真实模型发送一次、持久化远端 Task ID，第一次 GetTask 返回 503 后由宿主 `reconcileTask` 查询同一 ID，再让模型处理固定提示词。远端提议项同样先得到真实远端输出，然后报告处理结果，无本地写入。两项 setup prompt 单独记录 hash / inputRoute，不冒充固定 prompt 本身执行了宿主操作。服务端 SendMessage 计数 0/1/1/1；恢复 GetTask 两次，没有重发。

首轮缺少已有任务前提、第二轮缺少已有远端提议前提的失败记录均保留，未把模型对假设场景的正确拒绝误计成实际远端执行证据。新测试编译、Detekt 通过（`a2a-proposal-build-2.log`）。LAN 当前两份 Release 合并 manifest 也已核实不包含 DiagnosticFailureActivity。

Accessibility 固定评测夹具正在补齐：新增仅 developer 测试 APK 的合成 Activity 与真实服务/模型执行器，专用 API 34 上运行；宿主设置需在 finally 恢复原无障碍服务和 allowlist。新 suite 尚未计入通过数。

## API 29 长稳失败（04:19:58）

`soak-24h-api29/` 在约 13376 秒后进程崩溃，状态 FAIL，非 24 小时通过。最后 1826 轮、FD 120 / threads 47 / PSS 约 116 MiB，崩溃原因是 JNI weak global reference table overflow (max=51200)，栈进入系统 Trichrome libmonochrome。设备 WebView 为 91.0.4472.114。尚未把栈归因当作平台定论：开始增加不使用 Helix host/client/callback 的原生 WebView 创建销毁对照，不强制 GC。5590 的失败现场已保留，可用于该对照；5596 应用长稳仍 RUNNING，继续禁止干扰。

## 继续核验：专用模拟器离线与 Accessibility 夹具

应用长稳 `soak-24h-app-local-api36/` 已于 04:36 因 adb 本地 meminfo 采样退出码 1 结束，状态 FAIL。恢复会话时 5590/5594/5596 均不在 adb 列表中，仅其他工作的设备在线；没有足够证据判断模拟器退出原因，不能沿用之前 RUNNING 状态或拼接运行时间。

Accessibility 当前轮 001/002 PASS，003 FAIL，整组不计通过。003 实际 ui.snapshot 成功，随后 ui.click 被测试夹具 DENIED，未到达敏感语义执行门禁。已把精确测试批准限定为合成测试 APK 最新持久快照中的 Confirm payment 按钮，重跑验证生产执行层是否返回 SENSITIVE_UI；不改变产品授权或敏感操作策略。旧失败证据保留。

API 29 专用 AVD 已恢复，原生 WebView 30000 次创建销毁对照启动，证据 `raw-webview-api29/`。测试 APK 编译、Detekt 和 Spotless 检查通过。

原生对照已复现：API 29 / WebView 91.0.4472.114，在最后完成 25500 次创建销毁后同样发生 JNI weak global reference table overflow (max=51200) / SIGABRT。对照仅调用 Android WebView 构造与 destroy，没有 Helix host/client/callback，也没有强制 GC。证据 `raw-webview-api29/` 保存 APK hash、WebView 版本、instrumentation、logcat 与结果；该运行时可以独立触发原故障，不能用 Helix 资源阈值调整掩盖。新版 WebView 对照与重新连续长稳仍待完成。

API 34 / WebView 113.0.5672.136 原生对照也在约 25500 次后以相同 JNI 弱引用表溢出失败（`raw-webview-api34/`），不能声称升级到 113 已解决。Chromium 当前源码有异步 native destroy 与 CleanupReference 清理路径，但不能仅凭当前源码推断旧版具体泄漏点。继续比较 SDK 设备已有的 WebView 133 运行时。

当前源码 consumer 双 APK 已安装到恢复后的 5596，独立应用 24h 从 2026-09-06T06:43:18Z 重新开始（`soak-24h-app-current-api36/`；runner PID 96874），launcher 文件记录 APK SHA-256，caffeinate 防止宿主休眠。此设备再次禁止安装/重启/force-stop。

Accessibility 敏感操作夹具修改已通过编译/Detekt/Spotless。当前本机 30008 监听仍在，但 `/v1/models` 超时；`fixed-accessibility-service-recheck-api34/result.json` 记录 provider_discovery 失败、0 个 case，没有执行 Android 测试。评测 runner 现在为发现阶段超时写入明确失败证据，离线故障注入回归通过。已请求用户确认远端服务/转发；其余测试继续。

WebView 133.0.6943.137 已从现有专用 API 36 只读提取（未改变 5596），安装到 5590 后在同样约 25500 次处失败，证据 `raw-webview133-api29/`。三个版本均未通过，不能声称版本升级解决。三个对照共有 instrumentation 环境，因此进一步增加仅测试 APK 的 RawWebViewControlActivity，以 am start 直接启动、逐个创建销毁并让出主线程，不用 AndroidJUnitRunner 或 Helix host，排除测试框架影响。

模型服务恢复：远端 SSH 检查 30008 `/v1/models` 返回 200，原本 VS Code 本地转发超时。已建立独立的 localhost 30018 → 远端 30008 SSH 转发，返回 200；原转发未终止。评测 runner 新增显式 `--provider-port`（默认 30008），测试 APK 使用同一 instrumentation 参数，地址仍固定 emulator host `10.0.2.2`，不扩大产品网络授权。离线端口选择/发现超时回归通过；最新测试 APK 构建中，之后 Accessibility 使用 30018 重跑。

独立 Activity 对照同样失败：直接 am start，PID 4507，WebView 133 / API 29，在 25500 次之后 JNI 弱引用表溢出；没有 instrumentation 或 Helix host，且每次构造销毁间主线程让出 5ms。`raw-activity-webview133-api29/` 保存结果与 APK/启动证据。下一组保持同一 Activity 与节奏，仅将 WebView(applicationContext) 改为 WebView(activity)，辨别 Context/窗口清理影响；此前“纯平台路径可复现”不等于已定位具体 native 泄漏点。

## Accessibility 3/3 通过与 WebView Context 对照

`fixed-accessibility-host-probe-api34/` 3/3 PASS，累计 39/45；剩余 Goal / Root 各 3 项。001 验证 stale token 被拒后，模型刷新快照并完成一次真实合成按钮点击；002 前台包改变后会话仍暂停；003 模型读到支付按钮并主动拒绝，随后宿主通过真实 AutomationPermissionCenter 对精确合成按钮执行边界探针，返回 SENSITIVE_UI。第三项 `inputRoute` / `hostSensitiveExecutionProbe` 分开记录模型与宿主步骤，不把宿主探针写成模型 ToolCall。最新测试编译、Detekt、Spotless 通过；执行后原 enabled_accessibility_services=null 已恢复。旧轮正确拒绝但验证器要求一定调用 click 的失败证据保留。

Activity Context 对照也失败：`raw-activity-context-webview133-api29/` 最后完成 10200 次后，ActivityManager 以 `Too many Binders sent to SYSTEM` 杀死 PID 4696。不能把 application Context 换成 Activity Context 当作已验证修复。完整各组对照整理到 [WebView 原生引用调查](webview-native-reference-investigation.md)。

## Root 负向固定评测 3/3 通过

`fixed-root-controlled-grants-api34/` 3/3 PASS，固定评测累计 42/45，剩余 Goal 3 项。真实三个模型协议适配器运行在 API 34，使用生产 ToolDispatcher / PolicyEngine / RootTools / RootSessionManager；系统授权事实和时钟是明确标注的测试夹具，不是实际 Root 授权或真机 Root 成功证据。模型看到的每个工具调用都经过 Dispatcher。

宿主先用相同生产路径做负向边界探针，并单独记录 `hostProbe`：001 得到 POLICY_DENIED / CAPABILITY_NOT_GRANTED；002 创建受控 session 后推进时钟 601 秒，Dispatcher 执行层得到 ROOT_SESSION_EXPIRED，独立执行层探针也返回相同结果；003 UNKNOWN_TOOL，注册表不含 root.exec。三项特权操作端口调用数均为 0；模型步骤和宿主步骤分别记录，不伪造一次成功的 Root 操作。最新 Android test APK 编译、Detekt、Spotless 通过（`root-eval-build-2.log`）。

Goal 仍是生产接线缺口：普通 Goal 模式 Turn 没有 durable Goal/run/Turn 绑定，不能把独立 ledger 回归和无关模型回答拼成通过。后续必须完成生产运行协调、预算 checkpoint 与恢复接线后，再执行三项固定 Goal 评测；ADR-0004 语义保持不变。

新增夹具最终复验：developer Debug 和 browser Debug（含 Android test）Lint 通过，文档 173 / ADR 20 / i18n 703 键 / secrets / git diff --check 通过。五组已结束的 WebView 对照 logcat collector 已停止，结果文件记录各自测试 PID，原始日志保留可能包含的后续设备事件，按 PID 和关联 crash dump 定位，未裁剪失败证据。应用长稳 collector 与进程不受影响。当前 main 仍 ahead 59，未新增 commit/push；不能宣称全量收口完成。

## M11 合入后的范围扩展与长稳状态校正

2026-09-06，所有者明确要求后续优化包含已合入 main 的 M11，先完成功能性优化，再统一优化交互和界面。已核对 [M11 交接](m11-handoff.md) 与编号迁移记录；当前 HEAD 为 `4572911`，本地 main ahead 88，原收尾修改仍未暂存，保护性 stash 已恢复，不重复 apply。

执行顺序：

1. 继续 HXA-102 Goal 生产运行、预算和恢复接线，完成余下三项固定评测；已有 42/45 证据属于此前构建，不自动升级为 M11 合并后的通过结果。
2. 对 M0～M11 合并后的实际源码统一回归，覆盖 JVM、全部适用 Lint/构建、API 29/36 instrumentation、Runtime 隔离与生命周期、Provider 切换/取消/恢复和资源长稳；按交接保留 Claude/Grok 直接订阅付费调用未核实的所有者决定，不能以 fixture 替代真实账号验收。官方 CLI/SDK Android 停止路线不重开，consumer 渠道不因此增加订阅能力。
3. 功能收口后再开展统一交互/UI 优化，届时调研主流 Agent/竞品的任务状态、工具过程、审批、结果展示、会话导航和模型设置，结合 Android 使用场景提出并实现一致方案；以真实任务流程、可访问性、取消/错误/恢复状态和不同屏幕布局验证，保持现有权限与凭据边界。

再次读取 `soak-24h-app-current-api36/result.json` 已为 FAIL：adb `dumpsys meminfo --local` 退出码 1，5596 当前不在线，不能继续宣称该轮运行中或累计为 24 小时。它也使用 M11 合并前的 APK；后续长稳需绑定最新构建重新开始。WebView 五组独立对照失败仍未解决。当前仅见交接中的 5602/5604 在线，未安装、清除或改动其账号状态。

本次合并现场只读核验与文档门禁：check-docs 209 Markdown / 130 HXA、verify-adr 27、check-i18n 805 键均通过，git diff --check 通过。以上不替代合并后的功能验收；未新增提交或推送。

## 优先级更新：长稳延后

所有者明确要求长稳后期再做，现以 [main 优化待办](main-optimization-todo.md) 为执行顺序；当前不启动或自动重启任何 24 小时长稳。继续功能收尾与非长稳验证，再统一优化交互/UI。先启动当前 main 的 app 双 flavor 与 cli-client/cli-app 强制 JVM 回归，日志 `build/main-verification/m11-merged-unit-tests.log`；结果待执行结束后填写。

首组回归完成：305 Gradle tasks 全部执行，91 suites / 672 tests，其中 666 通过、6 assumption skip、0 failure/error。跳过均属于双 flavor Connector 外部验收开关与本地样本条件，已列入待办，不能计为通过。当前推进项切换为 HXA-102 Goal 接线；通过 CodeGraph 复核，M11 合并后仍未补上生产协调路径。未启动长稳。

## Goal 持久化前置修复与 M11 静态门禁

修复 ledger 预算耗尽只暂停 Goal、没有关闭 run 的问题，并将双时长限制优先级对齐 ADR-0004。专用 API 34 的 ProcessRecoveryTest 9/9 PASS、0 skip，含两项新增回归；详见 [修复记录](../bug-fixes/2026-09-06-goal-exhausted-run-left-open.md)。测试 APK hash 已保存，未触碰 5602/5604 账号设备，未启动长稳。

全仓 Spotless 暴露 M11 多处格式问题，修复长行/通配导入/条件布局并应用统一格式后通过。`goal-budget-build.log` 中 APK 构建与 CLI app JVM 测试已完成，但联合任务因 Detekt 53 项失败，不能报告全部通过。结构化问题列表 `m11-detekt-open-issues.json` 保留；本次 Goal 文件未在报告中出现。Goal 生产协调与固定评测仍未完成。

补充回归：consumer Debug Lint、core agent JVM 与 CLI client JVM 联合命令 exit 0（`goal-budget-regression.log`）。当前改动尚未提交或推送；后续先完成 Goal 生产接线，再逐项处理静态门禁与设备矩阵。

## Goal run/Turn 关联与迁移

Room v8 新增 goal_turn_bindings（turnId 主键/外键，runId 索引/外键）；由 run 追溯 Goal、由 Turn 追溯 session，支持同 run 多次 wake，不改变 ADR-0004 run/wake 语义。TurnStartSpec 接受可选 goalRunId，TurnCoordinator 在已有创建事务内绑定；仅 open run 且 Goal RUNNING 可绑定，同 Goal 不跨 session，失败回滚本次 Turn 创建。普通 Turn 保持无绑定；迁移不猜测任何历史 Goal 归属。

API 34 的 GoalTurnBindingDeviceTest / TurnCoordinatorDeviceTest / ProcessRecoveryTest 共 15/15 PASS。RoomMigrationFixtureTest 首轮 20 项中两项因新生成 v8 schema 未进入同轮 test APK 失败，重建后 20/20 PASS；原始失败保留。包含 v7→v8 保留旧会话/空关联、全旧版本迁移链与 code/export 一致性。证据、当前 APK hash 见 `build/main-verification/goal-binding-result.json`。

consumer Debug 与 storage Debug Lint、storage JVM、Spotless 通过；联合 gate 因既有 Detekt 53 项失败，未把整个命令记绿。新增文件未增加 Detekt 报告项。当前仅完成持久关联与 Turn 创建层接线，ChatService 的 Goal 创建/显式 Continue、剩余预算组装、模型/工具开始前计账及运行时 checkpoint 仍需补齐，不能称 Goal 产品已接通或固定三项已通过。未启动长稳，未改动 M11 账号设备，未提交/推送。

## Goal 创建/Continue 协调组件

持续 Goal 首次自动推进：上一轮创建目标和更新待办是状态进展，本轮继续 HXA-102。新增 GoalRunCoordinator，使用现有 GoalReducer 的 Ready、Continued/StartRun 与 BudgetsUpdated 事件；创建 READY Goal 不调用模型，显式 Continue 才原子保存 RUNNING、open run 和 Turn 关联，重复 Continue 返回无新运行。剩余模型/token 预算与用户 Turn 限额取更严值，工具额度单独返回供后续 Dispatcher 接线使用。创建 Turn 失败时全部回滚。

修复 GoalRepository.updateGoal 没有保存 budgets/criteria/planId/planHash 的遗漏，以免扩预算或 verifier 更新只活在内存；同时 GoalReducer 的 Continue 准入拒绝累计时长耗尽和零 wake 时长。新增 Goal/StoredGoal 映射保留证据与计数；ledger 的历史 pause finishReason 不作为 domain 的终态完成字段，历史 run outcome 保留。

`goal-coordinator-build.log`：core agent/storage JVM 与 consumer Debug/test APK 编译通过。API 34 GoalRunCoordinatorDeviceTest / GoalTurnBindingDeviceTest / ProcessRecoveryTest 合计 16/16 PASS、0 skip；覆盖重复 Continue、恢复后的剩余额度、扩预算不启动、错误 session 回滚。APK hash 见 `goal-coordinator-result.json`。本轮仍是生产协调组件的设备证据，不冒充 ChatService 已调用；下一步在 ChatService 的用户创建/Continue 路径消费该组件、绑定实际执行预算与终态/恢复，并补用户可见的 Goal 状态和额度。长稳未启动。

本轮最终门禁：consumer/storage Debug Lint、app 双 flavor JVM、Spotless 与文档/ADR/i18n/diff 检查通过；联合命令仅 Detekt 失败，仍为原有 53 项，无本轮新文件报告项（`goal-coordinator-gates.log`）。持续 Goal 保持 active，未提交/推送。

## Goal run 结束事务接线

本轮是进展：新增 GoalRunSettlement，由生产 TurnCoordinator.terminalize 在既有事务内调用；因此模型调用终态、消息、Turn 终态、Goal/run 状态和 audit 同成同败。无关联的普通 Turn 不受影响，已由 ledger 关闭的 run 保留原 outcome。正常 Turn 完成只使 Goal PAUSED/RUN_FINISHED，不自动完成验收条件；用户取消关闭为 CANCELLED。普通不可恢复执行失败记 FAILED；Turn 层额度结束先停泊，Goal 自身耗尽仍由 ledger 的 BUDGET_EXHAUSTED outcome 表达，不混为同一种计量事实。

任何关联 ToolCall 尚在 NEEDS_REVIEW/INTERRUPTED 时优先 INPUT_REQUIRED(NEEDS_REVIEW)，历史关联中仍有未核清调用也阻止新的 Continue，保留原 ToolCall 待核状态，不猜测结果或自动重放。

API 34 首轮 GoalRunCoordinator/GoalTurnBinding/TurnCoordinator/ProcessRecovery 共 22/22 PASS（`goal-settlement-device-api34.log`）。随后补充同一用例的 FAILED 分支并拆分结算决策方法，进行最终重建复验。ChatService/UI 的创建/继续入口、模型/工具执行前计账与周期用量 checkpoint 仍未接通，固定 Goal 三项未计通过。

最终复验 22/22 PASS、0 skip（`goal-settlement-final-api34.log`，APK hashes 见 `goal-settlement-result.json`）。consumer/storage Debug Lint、consumer JVM、Spotless 与 docs/ADR/i18n/diff 检查通过；联合 gates 仅因 Detekt 53 项失败，不能记全绿。未提交或推送，长稳未启动，持续 Goal active。

## HXA-102 接线前置：模型输出剩余额度绑定

真实 ChatService stream 调用前，TurnBudgetTracker 现在返回受剩余总额度与输入估算约束的 ModelRequest；无输出空间时不发起 Provider 调用。结束时继续检查报告用量及本次请求上限。

JDK 17 执行 `:app:testConsumerDebugUnitTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest`，并执行 `:app:lintConsumerDebug spotlessCheck`；构建与检查通过。consumer JVM 共 287 项，284 通过、3 条既有外部条件跳过。专用 API 34 的 AttachmentE2eDeviceTest 16/16 通过，含新增两条协议请求边界及原有附件/取消/工具循环回归。首次配置错误的失败记录保留，最终日志与 APK hash 为 `goal-call-admission-final-api34.log`、`goal-call-admission-result.json`。

详见 [修复记录](../bug-fixes/2026-09-06-model-output-remaining-budget.md)。持续 Goal 仍 active；HXA-102 上层创建/Continue 与持久执行预算尚待接通，42/45 仍是此前构建的历史固定评测结果。长稳未启动。

## HXA-102 持久计账：拒绝旧 run 快照回退用量

发现 checkpointUsage 原来只与调用方快照比较，finish 也未与当前数据库用量比较，延迟回调可以写小计数。两种 UPDATE 现在原子验证数据库中四项用量不大于新值，拒绝时整行不变，关闭 run 仍不可重写。

API 34 的 GoalRunCoordinatorDeviceTest 8 项与 ProcessRecoveryTest 9 项全部通过。consumer APK/测试 APK 构建、consumer JVM、storage Lint、Spotless 通过；日志 `goal-run-monotonic-gates.log`、`goal-run-monotonic-api34.log`，证据 hash 为 `goal-run-monotonic-result.json`。详见 [修复记录](../bug-fixes/2026-09-06-goal-stale-run-usage.md)。

同时校正 ledger 注释：限制单次 delta 为 5 秒不能证明实际 crash 窗口有界。下一步仍需实现执行前持久准入与未结用量恢复，然后接通 ChatService/UI；持续 Goal 保持 active。

## HXA-102：持久预算预留及恢复组件

Room v9 增加预留表，准入按已结用量和所有未结预留共同计算。重复 id 不重新准入，已知结算一次写入实际计数，中断恢复一次计入预留值；后者在 journal 中明确标为 INTERRUPTED。5 秒是单笔时间预留上限，实际计时窗口接线仍待完成。

恢复和 Turn 终态路径均在关闭 run 前清算未结预留。未结预留占用容量，多笔已准入工作全部结算前延后 run 关闭；已耗尽的实际预算不能新增准入。不会把离线墙钟时间当执行用量，不重放调用。

专用 API 34：GoalUsageReservationsDeviceTest 5 项、GoalRunCoordinatorDeviceTest 8 项、ProcessRecoveryTest 9 项，共 22/22 通过；RoomMigrationFixtureTest 21/21 通过，含 v8→v9 与当前导出 schema 对比。consumer JVM 287 项中 284 通过、3 条既有外部条件跳过；consumer/测试 APK、storage 测试 APK 构建和 storage Lint 通过。最终日志 `goal-reservation-final-api34.log`、`goal-reservation-migration-api34.log`；APK hash 见 `goal-reservation-result.json`。

联合命令包含 Detekt，不能将整条命令计为通过：原有 M11 静态问题仍待收口；新增 SQL/注释长行及上一轮测试的四项解构已修正，最终静态结果见 `goal-reservation-static.log`。源码没有增加整体规则抑制。

当前仍未把准入接到真实 ChatService 模型/工具/时间调用点，不代表完整 Goal 生产预算、真实 kill/restart、三项模型固定评测或长稳通过。持续 Goal 保持 active，下一步推进执行点与 UI 接线。

## HXA-102：ChatService 显式 Continue 与模型调用预算

创建/Continue 服务入口进入原有发送、附件校验和出网确认流程；仅显式 Continue 创建 Goal run/Turn，使用 Goal 剩余预算构造运行配置。确认期间保留 Goal 关联，取消确认清除关联；普通发送不会继承已取消的 Goal。重试保留原 Goal 关联并重新经过 Continue 状态门控。不可用 Goal 显示固定本地化阻止原因，不创建 Turn 或调用模型。

真实 Provider stream 之前写入模型预算预留并按 Goal 剩余 token 约束请求，正常流结束以共享 ModelCallUsage 与 Turn 使用相同计数；流取消/异常保留未结预留，由终态恢复结算为未知用量。预算耗尽后不执行该次返回的工具请求。工具自身计数和实时执行时长仍未接线，服务入口尚未在 UI 开放。

API 34：AttachmentE2eDeviceTest 22 项与既有 Goal 预留/协调/恢复 22 项，共 44/44 通过、0 skipped，包含实际 ChatService 到协议 wire 的连续 Continue、累计 2 次/28 token、停止流、附件确认、取消关联、额度耗尽与缺失 Goal。模型来自脚本 SSE fixture，不计入三项真实模型固定评测。测试启动时旧 AVD 进程已退出（原句柄 exit 134），确认停止后重启同一专用实例；未启动长稳，也未重启其他 AVD。

consumer JVM 287 项：284 通过、3 条既有外部条件跳过；consumer/测试 APK 构建、consumer Lint 通过。i18n 807 keys 一致。最终静态门禁见 `goal-model-entry-static.log`，未把包含 Detekt 失败的联合命令计为全绿。设备日志 `goal-model-entry-final-api34.log`，APK hash 见 `goal-model-entry-result.json`。

持续 Goal 仍 active，下一步为工具准入、单调时钟执行窗口和 UI；HXA-102 整体与剩余模型评测均未完成。

## HXA-102：Goal 工具预算进入真实调度路径

ChatService 在每个工具调用进入调度器前通过 GoalToolCallBudget 预留一次调用容量，批处理和直接调用共用入口。额度不足时不进入调度器，保存工具行、结果与 FRAMEWORK/BUDGET_EXHAUSTED 审计，并映射为预算不足文案；已准入的调用尝试（包括失败、权限拒绝及取消）结算一次，进程中断由既有未结预留恢复处理。预算预留不授予 Policy/Approval。

API 34 的真实 ChatService 脚本 wire 验证同一批返回两个 time.now、Goal 仅剩一次 Tool 额度：第一项 COMPLETED、第二项 FAILED/预算拒绝，两项都有结果，Goal toolCalls=1，run outcome 为 BUDGET_EXHAUSTED(maxToolCalls)，模型 wire 仅调用一次。全部聊天/Goal 组件/恢复回归 45/45 通过，日志 `goal-tool-final-api34.log`；此处 45 是本次模拟器 fixture 数量，不是 HXA-100 固定真实模型数据集通过数。

consumer JVM 287 项中 284 通过、3 条既有条件跳过，工具框架 JVM 146/146；consumer/测试 APK 构建、consumer Lint、Spotless、807 key i18n 通过。首次 Gradle 调用使用错误的 framework Android 测试任务名，后改用实际 JVM `:tools:framework:test`；新增枚举的 UI 映射遗漏和函数复杂度也已修复。最终联合日志 `goal-tool-complete-gates.log` 仍因原有 Detekt 53 项失败，不宣称全仓门禁已绿。APK hash 见 `goal-tool-result.json`。

持续 Goal 保持 active；下一步为单调时钟执行窗口、UI、真实 kill/restart 及三项真实模型评测。长稳未启动。

## HXA-102：Goal 单调时钟执行窗口

GoalTimeBudget 在真实 ChatService 的 Goal Turn 执行前预留至多 5 秒时间窗口，以单调时钟续约和结算。模型事件、工具取消信号与循环准入检查窗口有效性；调度延迟导致窗口过期时停止准入，取消后记录完整观测耗时，不把超过 5 秒的用量截断。超过单次唤醒或生命周期预算进入 PAUSED；仅窗口中断复用 INTERRUPTED outcome。重开数据库只结算未结预留，不收费离线墙钟间隔。

API 34 最终聊天/Goal/恢复回归 49/49 通过、0 skipped，含续约与重复 finish、6.5 秒观测延迟、数据库重开，以及实际 ChatService 脚本流阻塞后 2 秒 wake 预算暂停。首轮 48/49 是测试预期仍使用旧 outcome 字符串，修正为既有 INTERRUPTED 契约后重跑全部 49 项通过。日志 `goal-time-final-api34.log`，APK hash 见 `goal-time-result.json`。consumer JVM 287 项中 284 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint、Spotless 通过，808 key i18n 一致。联合门禁 `goal-time-final-gates.log` 仍因既有 Detekt 53 项失败。

以上不是全部后端硬取消、真实进程 kill/restart 或三项真实模型固定评测通过；接下来把剩余时间传入工具执行 deadline 并补验证。UI 尚待接线，HXA-102 和持续 Goal 均未完成；长稳未启动。

## HXA-102：工具剩余时限与阻塞取消

Goal 剩余时长在执行标记持久化后动态取值，与工具 descriptor timeout 取较小者，传入既有后端 deadline。Dispatcher 等待期间每至多 100 毫秒检查取消，取消后中断 Future；已开始的执行仍是未知副作用。线程中断仅是协作机制，具体执行域的停止与对账仍需验证。根因与不变量见 [修复记录](../bug-fixes/2026-09-06-goal-tool-deadline-and-cancellation.md)。

工具框架 JVM 150/150；consumer JVM 284 通过、3 条既有条件跳过；最终 API 34 聊天/Goal/恢复 49/49 通过。consumer/测试 APK 构建、consumer Lint、Spotless 通过；全仓 Detekt 仍为 53 项，联合 Gradle exit 1。初次使用不存在的模块级 Spotless/Detekt 任务名，已改为根任务；新增 ReturnCount 已通过调整控制流修正，未抑制该规则。日志 `goal-backend-cancel-final-gates.log`、`goal-backend-cancel-api34.log`，APK hash 见 `goal-backend-cancel-result.json`。

持续 Goal 保持 active；真实 kill/restart、各后端取消/对账、Goal UI 与三项真实模型评测继续推进，未启动长稳。

## HXA-102：真实进程 SIGKILL 后的预算恢复

新增 GoalProcessKillDeviceTest 与 `scripts/run-goal-process-kill.py`，在独立测试数据库中通过生产 GoalRunCoordinator、GoalTimeBudget、GoalUsageReservations 创建未结窗口，宿主收到落盘标记后核对 PID 并发送 SIGKILL。两次被杀进程分别为 3227、3284；instrumentation 均报告 `Process crashed.`，不能把其 shell exit 0 当成普通测试成功。重新启动的下一阶段读取同一持久状态并执行生产恢复。

第一轮恢复累计时间 5,000 毫秒、模型预留 1 次/100 token；用户显式继续后第二次 kill，再恢复累计 10,000 毫秒、2 次/200 token，Goal PAUSED，预算耗尽后第三次 Continue 被拒绝且未创建 Turn。恢复墙钟分别回拨至 epoch 1 和前跳至 999,999,999，离线时间没有加入用量；重复恢复不改计数，不创建额外 run/Turn。进程内 control 与最终恢复断言各 1/1；包含新 control 的聊天/Goal/恢复常规回归 50/50 通过。

命令：先 `./gradlew :app:assembleConsumerDebugAndroidTest spotlessCheck detekt --continue`，安装 consumer 测试 APK，再 `python3 scripts/run-goal-process-kill.py --serial emulator-5594`（adb 需在 PATH，或使用 `--adb` 指定路径）。最终 `goal-kill-final-gates.log` 的测试 APK 构建/Spotless 通过，Detekt 仍有既有 53 项，联合 exit 1。设备日志 `goal-kill-control.log`、`goal-kill-prepare.log`、`goal-kill-recover-prepare.log`、`goal-kill-recover-final.log` 与 `goal-kill-regression-api34.log`；PID、阶段、APK hash 见 `goal-kill-result.json`。

范围严格限于真实进程死亡下的持久预算窗口：本次没有调用真实 Provider 或工具后端，也没有模拟设备断电、主线程无限阻塞、审批/文件/UI/浏览器/远端/PRoot 执行中断。不能替代这些后端的取消与未知副作用对账矩阵；Goal UI、完成证据/提醒与三项真实模型固定评测继续推进。长稳未启动，持续 Goal 保持 active。

## HXA-102：Goal 首批用户入口与持久状态展示

聊天处于 Goal 模式时，发送进入 Goal 管理入口，不再直接创建普通无 Goal 绑定 Turn；空输入时也可打开目标入口。用户可创建目标与逐行验收条件、查看累计用量/验收进度、调整已暂停目标预算并显式 Continue。创建和保存预算不调用模型，Continue 保留原有服务/附件/出网确认路径。READY 不开放预算更新，遵循既有 reducer 只允许 parked 状态更新预算的约束。

GoalSummaryQuery 通过服务在 IO 层读取持久记录，UI 不读取 DAO。已有 run 的目标按当前会话的绑定过滤；尚未绑定的 READY 目标可在用户选定会话后首次继续。状态与 RUN_FINISHED/INTERRUPTED/BUDGET_EXHAUSTED 暂停原因来自持久 run，验收进度仅统计已有 verifier evidence，不读取模型自称完成。

consumer JVM 288 项中 285 通过、3 条既有条件跳过，新增预算输入覆盖非法数值、负数及 Int/秒转毫秒溢出。API 34 最终聊天/Goal/恢复/Compose 表单回归 52/52 通过；新增表单验证验收条件必填、点击保存前无保存动作、保存六项预算，持久投影验证暂停原因、未完成验收和会话隔离。首轮 51/52 因新测试直接从 WAITING_MODEL 跳到 COMPLETED，已补上真实 beginModelStream 转移并重跑全部 52 项。

最终 consumer/测试 APK 和 consumer Lint 通过，835 key i18n 一致；新增 Compose 复杂度/预算解析控制流已整理，英文数量展示改为单位标注后 Lint 通过。全仓 Detekt 仍有原有 53 项，不能宣称全仓静态门禁完成。日志 `goal-ui-corrected-gates.log`、`goal-ui-lint-fixed-gates.log`、`goal-ui-final-api34.log`，APK hash 见 `goal-ui-result.json`。

这是首批功能入口，不是完整交互验收：仍需完整聊天界面创建/Continue/预算编辑的端到端回归、完成证据与提醒接线、具体后端中断矩阵和三项真实模型固定评测。输入清理、完整预算展示及边界操作继续随界面流程核验处理；统一视觉优化仍在功能验证之后。持续 Goal active，长稳未启动。

## HXA-102：Goal 对话框与服务的实际流程验证

Continue 后清理已提交的输入，关闭对话框保留草稿；目标列表补齐单次运行时间和重试上限，六项预算都可在创建时设置并在管理时查看。新增实际 GoalDialog → ChatService → Room 模拟器验证：关闭保留输入，创建只生成 READY Goal 且没有 run/Turn，显式 Continue 后仍由 Provider gate 拦截未绑定会话，不调用网络。测试使用唯一命名会话，结束删除本次 Goal 并归档本次会话。

专用 API 34：GoalDialogDeviceTest、GoalEditorDeviceTest 与 GoalUsageReservationsDeviceTest 共 11/11 通过，日志 `goal-dialog-flow-api34.log`，APK hash 见 `goal-dialog-flow-result.json`。consumer JVM 288 项中 285 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint 与 Spotless 通过。新增测试 LongMethod 已提取重复状态断言修正，最终 Detekt 仍为既有 53 项，联合日志 `goal-dialog-flow-final-gates.log` 不是全绿。836 key i18n 一致。

这是真实 UI 到服务与持久化的无 Provider 路径，不替代带真实模型的完整聊天界面执行与预算编辑回归。三项固定 Goal 场景分别使用 Responses、Chat Completions 和 Anthropic Messages；当前没有对应 FixedGoalEvaluationDeviceTest，后续需新增并执行，不能将现有 42/45 历史结果补写为通过。持续 Goal active，完成证据/提醒与具体后端恢复矩阵继续保留，长稳未启动。

## HXA-100/HXA-102：三项真实模型 Goal 固定评测

新增 FixedGoalEvaluationDeviceTest 与 runner 的 `--suite goal`。固定 TSV 和提示词未修改，dataset SHA-256 仍为 `f27bf8b51e61be248a6e642c22cefc3e5045d0d35e37518377eb9b8cdf85e795`。专用 API 34 当前 developer APK，通过 SSH 转发的本地 SGLang（本轮可用本地端口 30018，对应服务端 30008）执行 Responses、Chat Completions、Anthropic Messages。补充上下文、夹具确认和额外宿主动作均记录在各条结果及 context hash 中。

- goal-001：真实 Responses 给出覆盖 alpha.md、beta.md、gamma.md 的三个检查点和可验证退出条件，不调用工具、不声称已经执行审计；Goal 为 PAUSED/RUN_FINISHED，不是完成。
- goal-002：先在同一 Goal 内真实消耗一次模型调用，再使用原始固定提示词显式 Continue；模型计数从 1 到 2，剩余一次额度用尽后 PAUSED，outcome=BUDGET_EXHAUSTED(maxModelCalls)。
- goal-003：真实 Anthropic 返回 write 请求，持久调用停在 AWAITING_APPROVAL，未发生文件写入，未自动批准。观测时 Turn 为 RUNNING_TOOL、Goal 为 RUNNING、run 未关闭；这证明执行等待审批，不声称 Goal 已进入持久 PAUSED 或 INPUT_REQUIRED。取证后 Stop，新增检查要求活动 Turn 确实结算才允许评测成功。

最终 3/3 PASS，instrumentation `OK (1 test)`，宿主 runner exit 0；结果位于 `build/main-verification/fixed-goal-cleanup-verified-api34/result.json` 和 `device/goal-*.json`。记录含实际 APK hash、模型/Provider/工具版本、日期、提示词/context/dataset hash、dirty diff hash 与运行计数。命令为 `python3 scripts/run-hxa100-provider-evals.py emulator-5594 --suite goal --provider-port 30018 --output build/main-verification/fixed-goal-cleanup-verified-api34`（adb 在 PATH，事先安装当前 developer/测试 APK）。

保留失败轮次：`fixed-goal-current-api34` 的第三项因夹具连续 USER 消息被 Anthropic 编码器拒绝，补夹具确认后修复；`fixed-goal-role-corrected-api34` 的第三项仅返回“等待批准”文字，没有真实审批卡，因此维持 FAIL。随后明确夹具要求提交待审批请求以展示卡片，断言保持实际 AWAITING_APPROVAL + 零写入不变，`fixed-goal-approval-card-api34` 和加入 Stop 结算检查后的最终轮次均 3/3 通过。没有将文字自称暂停或宿主合成 ToolCall 当成模型触发审批的证据。

构建还修复了两处合并后旧评测的 ProviderFactory 调用：新增 additionalFactory 后，尾随 lambda 不再对应 imageSource；Provider/Root 评测现显式命名 imageSource。developer/测试 APK 与 Spotless 通过，新增评测格式与函数复杂度已整理；最终 `fixed-goal-cleanup-gates.log` 仍因既有 Detekt 53 项 exit 1，不宣称全仓静态门禁完成。

原有 42 项通过证据来自此前 APK，本轮 3 项属于当前 APK；不能拼成“当前 main 同一构建 45/45”。已将统一 45 项复验列入待办。HXA-102 的完成证据/提醒、具体后端中断与完整 UI 模型流程仍需收口，M0～M11 全矩阵和后续统一交互优化继续，持续 Goal active；长稳未启动。

## HXA-102：提醒身份与取消缺陷修复

通知原来使用相同 PendingIntent 身份（extras 不参与匹配），不同 Goal 会共享点击对象；数值 notification ID 的 hash 也可能冲突。现以完整 Goal ID URI 区分 PendingIntent，以 Goal ID tag 区分通知，并让生产取消入口同时移除已发布通知。详细根因见 [修复记录](../bug-fixes/2026-09-06-goal-reminder-identity-and-cancel.md)。

专用 API 34 GoalReminderTest 3/3 通过，含实际 hash 冲突与独立取消；consumer JVM 285 通过、3 条条件跳过，consumer/测试 APK、consumer Lint、Spotless 通过。最终 Detekt 仍为既有 53 项，联合构建不算全绿。日志 `goal-reminder-identity-gates.log`、`goal-reminder-identity-api34.log`，APK hash 见 `goal-reminder-identity-result.json`。

提醒仍未完整进入 Goal 生命周期：需接通检查点保存、状态同步、并发取消/发布与通知点击导航。Worker 仍只发通知，不自动调用模型/Tool；HXA-102 和持续 Goal 均未完成，长稳未启动。

## HXA-102：提醒检查点与生命周期接线

GoalRunCoordinator 现以事务保存/清除检查点并写入 audit；按 ADR-0004 第 6 条的 RUNNING/PAUSED 提醒边界，PAUSED 也可显式设置或移除提醒。新增 CheckpointCleared 只清理提醒，不改变 Goal 状态、run 数或预算；READY、INPUT_REQUIRED 与终态拒绝这两类操作。Goal 管理界面增加约 30 分钟提醒与移除按钮，运行中可以打开管理界面，但 Continue 仍受活动 Turn 门控。

生产 ChatService 在检查点更新和 Turn 持久结算后同步提醒；Application 在进程恢复完成后依据持久化 Goal 重建提醒队列。GoalReminderReconciler 对 RUNNING/PAUSED 的已有检查点安排 WorkManager，其余状态取消队列及已发布通知，不创建 run、不调用模型或 Tool。

验证（JDK 17，专用 API 34）：`:core:agent:test` 169/169，`:app:testConsumerDebugUnitTest` 285 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint、Spotless 通过。联合命令 `./gradlew :core:agent:test :app:testConsumerDebugUnitTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:lintConsumerDebug spotlessCheck detekt --continue` 仍因既有 Detekt 53 项 exit 1。补充设备测试后再执行 `./gradlew :app:assembleConsumerDebugAndroidTest spotlessCheck`，exit 0。

安装当前 consumer/测试 APK 后，`adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.chat.GoalRunCoordinatorDeviceTest,com.helix.app.GoalReminderTest,com.helix.app.ui.GoalDialogDeviceTest,com.helix.app.ui.GoalEditorDeviceTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner` → 15/15 通过。新增持久化与状态重建测试使用独立数据库和记录队列，验证恢复不新增 run、不改变累计事实，清除/取消确实移除队列；实际 WorkManager 通知仍由 GoalReminderTest 覆盖。日志 `goal-reminder-wiring-gates.log`、`goal-reminder-wiring-device-build.log`、`goal-reminder-wiring-api34.log`，APK hash 和计数见 `goal-reminder-wiring-result.json`（均在 `build/main-verification/`）。

尚未覆盖提醒按钮的完整 UI 操作、通知点击后的 Goal 导航、检查点消费/删除清理以及 Worker 发布与取消竞态；完成证据与后端执行中断矩阵也仍待收口。本节是已验证的生命周期接线阶段，不是完整提醒或 HXA-102 验收。持续 Goal active，长稳未启动。

## HXA-102：提醒点击导航与 Activity 重建

通知 Intent 现校验 helix Goal URI 与同一 Goal ID extra，从持久化 Goal/run/Turn 绑定查询唯一会话；没有绑定时不创建新会话。ChatService 只打开绑定会话并发布指定 Goal 查看请求，应用壳切到会话页、GoalDialog 过滤对应目标；打开通知不发送 Continued、不调用模型、不授予审批，显式 Continue 保持现有服务门控。首次启动与 onNewIntent 使用统一入口。

实际重建回归发现 `Cannot navigate to sessions. Navigation graph has not been set`：导航 Effect 早于 NavHost 就绪。现等待首个导航栈 entry 后再跳转。Activity 保存已消费的提醒 ID，避免关闭提醒后重建再次打开；新 onNewIntent 重置消费状态，保留新的用户点击。启动 Intent 身份保持不变，避免破坏 ActivityScenario 的生命周期匹配。详见 [缺陷记录](../bug-fixes/2026-09-06-goal-reminder-navigation-lifecycle.md)。

最终 consumer JVM 285 通过、3 条既有条件跳过；consumer/测试 APK、consumer Lint、Spotless 通过；联合命令 `./gradlew :app:testConsumerDebugUnitTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:lintConsumerDebug spotlessCheck detekt --continue` 仍因既有 Detekt 53 项 exit 1，日志 `goal-reminder-navigation-stable-gates.log`。

专用 API 34 安装当前 APK 后，`adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.GoalReminderNavigationDeviceTest,com.helix.app.ui.GoalDialogDeviceTest,com.helix.app.chat.GoalRunCoordinatorDeviceTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner` → 13/13 通过；导航测试独立重复 2/2 通过。验证 malformed/mismatched Intent 被忽略、真实 MainActivity 打开绑定会话、Goal/run/Turn 不新增或变化、关闭后重建不重新发布查看请求。日志 `goal-reminder-navigation-stable-api34.log`、`goal-reminder-navigation-repeat-api34.log`，APK hash 见 `goal-reminder-navigation-result.json`，均在 `build/main-verification/`。

保留导航栈未初始化的进程崩溃日志 `goal-reminder-navigation-recreate-api34.log`，以及中间消费入口未统一和修改 Intent 身份导致测试生命周期匹配失败的记录；它们不是通过证据。当前仍需提醒按钮与完整通知 UI 流程、发布/取消竞态、检查点消费/删除清理、完成证据和具体后端中断矩阵。HXA-102 与持续 Goal 未完成；长稳未启动。

## HXA-102：提醒发布、替换与取消并发收口

WorkManager 的取消是异步 best-effort，取消方法返回不等于正在运行的 Worker 已停止。GoalReminderPublication 现将当前单进程内的通知发布与生产队列替换/取消串行化；发布前读取该 Work ID 的真实 WorkManager RUNNING 状态，取消/替换等待持久队列操作完成后再撤回通知。旧 Worker 即使迟到也不能越过已完成的取消；已经进入发布区间时，取消等待发布结束再撤回通知。替换为未来检查点也移除旧通知。

没有增加 Goal 状态存储或让 Worker 读取 Goal/预算数据；使用既有 WorkManager 状态，不改变 ADR-0004 的显式继续边界。同步等待最多 10 秒，当前生产调用来自服务 IO/恢复后台线程及 Worker；失败/超时抛出错误，取消异常保留，不把操作未完成当作成功。

真实 API 34：GoalReminderPublicationDeviceTest 三项控制并发测试与 GoalReminderTest 三项实际 Worker/通知回归共 6/6 通过。控制用例以真实 RUNNING WorkSpec 和真实 NotificationManager 为依据，迟到 actor 调用同一生产发布门；覆盖取消后发布、替换后旧发布、正在发布时并发取消，断言最终没有残留通知。它不是“已证明所有 Worker 都停止”的证据。

命令：`./gradlew :app:testConsumerDebugUnitTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:lintConsumerDebug spotlessCheck detekt --continue`，consumer/测试 APK、consumer Lint、JVM 与 Spotless 通过，Detekt 仍为既有 53 项。增加异常/取消/超时 JVM 回归后执行 `./gradlew :app:testConsumerDebugUnitTest spotlessCheck detekt --continue`，291 项中 288 通过、3 条条件跳过，仍仅 Detekt 阻止联合命令全绿。设备命令 `adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.GoalReminderPublicationDeviceTest,com.helix.app.GoalReminderTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`。日志 `goal-reminder-publication-gates.log`、`goal-reminder-publication-final-gates.log`、`goal-reminder-publication-api34.log`，APK hash 见 `goal-reminder-publication-result.json`，均在 `build/main-verification/`。

详细理由见 [并发修复记录](../bug-fixes/2026-09-06-goal-reminder-publication-cancellation.md)。仍未完成：删除 Goal 时清理提醒、检查点消费与恢复后的重复提醒边界、完整通知/按钮 UI、各后端执行中断与完成证据。HXA-102 和持续 Goal active；真机与长稳未运行。

## HXA-102：Goal 删除与提醒清理

PrivacyDeletionService.deleteGoal 现通过 GoalDeletionCoordinator 删除。与提醒重建共享串行边界，在 Room 事务内检查 Goal 非 RUNNING 且无未关闭 run；活动目标返回 GOAL_ACTIVE_STOP_REQUIRED，沿用现有会话删除“先停止再删除”的边界。可删除目标先等待提醒取消和通知撤回，再删除 audit/Goal/不再引用的 plan；提醒取消失败时目标记录保留。成功后关闭该目标的当前提醒查看请求。

GoalReminderReconciler 对已被删除的目标转为取消提醒，而不是抛出目标不存在异常；旧列表快照不再因此中止恢复。删除与恢复串行化，避免恢复已读取 Goal 后在删除完成之后重新排入提醒。Worker 仍不读 Goal 数据，不增加后台运行或权限。

专用 API 34：GoalDeletionDeviceTest、GoalReminderPublicationDeviceTest、GoalRunCoordinatorDeviceTest 合计 16/16 通过。新增设备用例通过生产 PrivacyDeletionService 清理实际已发布与延迟 WorkManager 提醒，检查目标消失、无 pending work、无系统通知；隔离数据库检查 RUNNING/未关闭 run 拒绝删除且不取消提醒，run 关闭后可删除，取消异常保留记录。该组不代表删除 UI 或删除中途真实进程死亡已验收。

命令 `./gradlew :app:testConsumerDebugUnitTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:lintConsumerDebug spotlessCheck detekt --continue`：consumer JVM 291 项中 288 通过、3 条既有条件跳过，consumer/测试 APK、consumer Lint、Spotless 通过；Detekt 仍为既有 53 项，联合命令 exit 1。安装当前 APK 后执行 `adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.GoalDeletionDeviceTest,com.helix.app.GoalReminderPublicationDeviceTest,com.helix.app.chat.GoalRunCoordinatorDeviceTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`，16/16。日志 `goal-deletion-gates.log`、`goal-deletion-api34.log`，APK hash 与计数见 `goal-deletion-result.json`，均在 `build/main-verification/`。

根因与不变式见 [删除修复记录](../bug-fixes/2026-09-06-goal-deletion-reminder-cleanup.md)。检查点消费/恢复重复提醒、完整通知与删除 UI、完成证据、具体后端中断矩阵仍需继续；HXA-102 与持续 Goal active，长稳未启动。

## HXA-102：检查点恢复幂等与成功记录消费

原恢复路径每次 REPLACE 同一检查点，既重复创建 work，也可能重发已经成功处理的提醒。现在以 Goal unique work name 加绝对检查点 tag 识别任务：相同检查点的非终态或 SUCCEEDED work 保留；检查点变更或原 work 失败/取消后才替换。恢复协调器发现匹配的 SUCCEEDED 记录时，在 Room 事务中重新检查最新 Goal，只清除仍然匹配的 RUNNING/PAUSED 检查点并记录 SYSTEM goal.checkpoint_delivered audit。Goal 状态、预算、run 数和既有 run 不变，不创建模型调用。

消费发生在协调器，Worker 仍不读取 Goal 数据；SUCCEEDED 只表示 Worker 成功结束，不证明用户看到或阅读了通知。没有引入新的持久化系统或改变 ADR-0004 的运行边界。若 WorkManager 历史已被系统清理，或旧版本 work 没有检查点 tag，无法证明已处理，仍按 ADR 的过期检查点补发规则处理；不宣称严格 exactly-once 通知。

专用 API 34：GoalCheckpointDeviceTest、GoalReminderTest、GoalDeletionDeviceTest、GoalReminderPublicationDeviceTest 合计 11/11。验证重复恢复保留同一 Work ID、变更检查点替换 work、成功后消费且再次恢复不重建、旧成功不能清除新检查点，以及原通知身份/发布取消/删除回归。

命令 `./gradlew :app:testConsumerDebugUnitTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:lintConsumerDebug spotlessCheck detekt --continue`：consumer JVM 291 项中 288 通过、3 条既有条件跳过，consumer/测试 APK、consumer Lint、Spotless 通过；Detekt 仍为既有 53 项，联合命令 exit 1。安装当前 APK 后执行 `adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.GoalCheckpointDeviceTest,com.helix.app.GoalReminderTest,com.helix.app.GoalDeletionDeviceTest,com.helix.app.GoalReminderPublicationDeviceTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`，11/11。日志 `goal-checkpoint-gates.log`、`goal-checkpoint-api34.log`，APK hash 与计数见 `goal-checkpoint-result.json`，均在 `build/main-verification/`。

根因与边界见 [修复记录](../bug-fixes/2026-09-06-goal-checkpoint-recovery-replay.md)。完整 UI、删除中途进程死亡、完成证据和具体后端中断矩阵仍待收口。HXA-102 与持续 Goal active；真机、长稳未运行。

## HXA-102：Goal 提醒按钮的实际界面回归

新增 GoalReminderControlsDeviceTest，经真实 GoalDialog 点击稍后提醒，检查生产 ChatService 保存的约 30 分钟检查点和实际 WorkManager work；关闭并重新打开对话框后，检查点与 Work ID 不变；点击移除提醒后检查点清空且 work 终止。流程中的 Goal、既有 run、Turn 数量保持不变，意外调用 Continue 会立即令测试失败。fixture 为独立无 Provider 会话，仅预置一个通过生产协调器创建并正常 park 的 run；测试不执行网络。

本轮未修改生产行为。`./gradlew :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest spotlessCheck detekt --continue`：consumer/测试 APK、Spotless 通过，Detekt 仍为既有 53 项，联合命令 exit 1；没有把此前 JVM/Lint 结果写成这一轮重新执行。安装当前 APK 后执行 `adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.ui.GoalReminderControlsDeviceTest,com.helix.app.ui.GoalDialogDeviceTest,com.helix.app.ui.GoalEditorDeviceTest,com.helix.app.GoalCheckpointDeviceTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner` → 5/5。

日志 `goal-reminder-controls-gates.log`、`goal-reminder-controls-api34.log`，APK hash 与计数见 `goal-reminder-controls-result.json`，均在 `build/main-verification/`。这是目标管理组件到服务/存储/WorkManager 的真实点击路径，不替代通知栏点击、预算编辑、删除 UI 或真实模型的完整 ChatScreen 流程。

本轮进一步核实：CriterionSatisfied/CompleteRequested 仅在 core:agent 的事件和 reducer 中出现，App 生产代码还没有调用入口。完成证据生产闭环仍是 HXA-102 的实质未完成项；后端中断/对账矩阵也继续。持续 Goal active，长稳未启动。

## HXA-102：完成证据契约草案与当前进程恢复复验

源码核实表明 Criterion/StoredCriterion 只持久化描述和裸证据引用，未定义条件与实际 verifier 的绑定。不能把任意 verified ToolResult 或模型自述直接送入 CriterionSatisfied。已编写 [ADR-0028 草案](../adr/0028-goal-criterion-verification-bindings.md)：封闭确定性规则与用户复核真实证据两条路径、同 Goal 来源/完整性校验、绑定版本、迁移及保持 ADR-0004 的完成边界。新增跨模块值/持久格式须由所有者决定；当前状态 proposed，已请求决定，尚未实现或接受，不以草案抵消生产完成缺口。

等待决定期间完成独立恢复复验：脚本新增 --output 参数，保留既有默认行为，使每轮可写独立证据目录。可移植等价命令 `python3 scripts/run-goal-process-kill.py --serial emulator-5594 --adb "$ANDROID_HOME/platform-tools/adb" --output build/main-verification/goal-evidence-design-kill-api34`（本轮 adb 使用已核实的 SDK 路径）。当前 consumer APK 实际 SIGKILL PID 6086、6143；control 与 recover-final 均 OK (1 test)，宿主 exit 0。两次未知窗口恢复累计 10 秒、2 次模型调用、200 tokens，第三次 Continue 被预算拒绝，无新增 Turn；重复恢复幂等，墙钟变化不补充预算。

`goal-kill-result.json` 保存 API、每阶段 PID/信号/日志、当前 APK hash。范围仍是 durable time/model reservation 的真实进程死亡；没有运行真实 Provider 请求或工具后端，不能冒充其 kill/restart 验收。持续 Goal active，HXA-102 其他恢复/界面验证可继续，长稳未启动。

## HXA-102：预算编辑 UI 与当前 Goal 组合回归

新增实际 GoalDialog → GoalEditor → ChatService → Room 预算编辑回归：非法负数禁用保存，放弃编辑不改持久数据，显式保存六个上限（5 model、9 tool、2000 tokens、120 秒总时长、20 秒 wake、1 retry）后预算完整落盘；Goal 仍为 PAUSED，既有 run 与 Turn 数量不变。生产代码只增加预算编辑按钮、六个输入字段与关闭按钮的稳定 testTag，没有改变预算或完成语义。

`./gradlew :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest spotlessCheck detekt --continue`：consumer/测试 APK、Spotless 通过，Detekt 仍为既有 53 项，联合命令 exit 1；本轮未重新执行 JVM/Lint。专用 API 34 预算/界面/协调器组合先 14/14 通过，再执行当前 consumer 下全部 12 个文件名含 Goal 的设备测试类，40/40 通过。

组合包含 GoalRunCoordinatorDeviceTest、GoalTurnBindingDeviceTest、GoalUsageReservationsDeviceTest、GoalProcessKillDeviceTest、GoalCheckpointDeviceTest、GoalDeletionDeviceTest、GoalReminderNavigationDeviceTest、GoalReminderTest、GoalReminderPublicationDeviceTest、GoalDialogDeviceTest、GoalEditorDeviceTest、GoalReminderControlsDeviceTest。实际命令为 `adb -s emulator-5594 shell am instrument -w -r -e class <上述类的完整包名以逗号连接> com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`；完整可重放参数向量及类名保存在下述 JSON 的 reproduceIntegrated/testClasses 中。

日志 `goal-budget-ui-gates.log`、`goal-budget-ui-api34.log`、`goal-integrated-current-api34.log`，APK hash、类名与计数见 `goal-budget-ui-result.json`，均在 `build/main-verification/`。组合中的进程 fixture 运行默认 control 路径，不能当作本轮执行了真实 SIGKILL；上一轮真实杀进程证据单独保留。40/40 也不等于全 App、当前 45 项真实模型评测或真实模型 ChatScreen 流程通过。

ADR-0028 仍 proposed，未收到接受决定，证据契约暂不实现。HXA-102 其他后端中断、删除/通知栏 UI 与真实模型流程仍可继续；持续 Goal active，长稳未启动。

## HXA-102：重复通知点击复用 Activity

新增回归在关闭提醒并重建 Activity 后，直接发送生产 goalReminderContentIntent 的 PendingIntent，要求重新打开同一 Goal、复用现有 MainActivity、保持 Goal 与 run 不变。未修改实现上 2 项中 1 项失败：New notification click was not consumed；系统接受启动请求，但该路径没有满足重新打开要求，失败日志保留。

通知 Intent 现明确添加 FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP，使已有 Activity 接收新的用户请求，复用已实现的 onNewIntent 消费重置逻辑；不改 run/预算/权限。修改后导航 2/2，通过后与实际通知、提醒/预算 UI 组合再次 7/7。当前证据是实际 PendingIntent 发送与 Activity 实例断言，不是通知栏手势测试。

命令 `./gradlew :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest spotlessCheck detekt --continue`：consumer/测试 APK、Spotless 通过；Detekt 仍既有 53 项，联合命令 exit 1。另执行 `./gradlew :app:lintConsumerDebug`，exit 0。安装 APK 后执行 `adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.GoalReminderNavigationDeviceTest,com.helix.app.GoalReminderTest,com.helix.app.ui.GoalReminderControlsDeviceTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`，7/7。

日志 `goal-repeat-notification-before-api34.log`、`goal-repeat-notification-flags-api34.log`、`goal-repeat-notification-final-api34.log`、`goal-repeat-notification-flags-gates.log`、`goal-repeat-notification-lint.log`，APK hash 和前后计数见 `goal-repeat-notification-result.json`，均在 `build/main-verification/`。详见 [修复记录](../bug-fixes/2026-09-06-goal-repeat-notification-routing.md)。ADR-0028 仍 proposed，其他 HXA-102 验证与持续 Goal 继续，长稳未启动。

## HXA-102：系统通知栏点击验收

扩展 GoalReminderNavigationDeviceTest：真实 WorkManager 发布带唯一 fixture 文本的通知，等待 NotificationManager 确认本 Goal tag，展开 SystemUI 通知栏，对对应文本节点的可点击父节点执行 ACTION_CLICK。点击后要求界面可见选定 Goal 的唯一 objective，绑定会话正确，持久 Goal、run 列表和 Turn 数量均不变；测试最后只取消自身提醒。既有 PendingIntent 重复点击与 Activity 重建断言保留。

初版系统文本搜索未命中通知，导航 2 项中 1 项失败。SystemUI 包名与实际截图确认通知已可见；改为逐节点读取文本后导航 2/2，进一步加入实际目标界面可见断言后组合 7/7。该失败属于测试定位方式，不作为新增生产导航缺陷。没有增加新依赖或修改通知运行语义。

最终命令 `./gradlew :app:assembleConsumerDebugAndroidTest spotlessCheck detekt --continue`：测试 APK 与 Spotless 通过；Detekt 仍既有 53 项，联合命令 exit 1。设备命令 `adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.GoalReminderNavigationDeviceTest,com.helix.app.GoalReminderTest,com.helix.app.ui.GoalReminderControlsDeviceTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`：7/7。本轮仅测试变更，未重跑 JVM/Lint。

证据位于 `build/main-verification/goal-notification-shade-result.json`、`goal-notification-shade-window-api34.log`（定位失败）、`goal-notification-shade-tree-api34.log`、`goal-notification-shade-visible-api34.log`、`goal-notification-shade-final-api34.log` 与 `goal-notification-shade-final-gates.log`。本次覆盖 API 34 已有 Activity 的通知栏实际点击，不代表所有 API、进程外启动或真实模型完整聊天验收。ADR-0028 保持 proposed；删除 UI、后端中断/对账和当前主分支全量矩阵继续，持续 Goal active，长稳未启动。

## HXA-102：Goal 删除界面接入

GoalDialog 增加删除入口，由 MainActivity/ChatScreen 注入生产 PrivacyDeletionService，在 IO dispatcher 执行已有 GoalDeletionCoordinator。确认框明确删除 Goal、运行/证据记录及提醒，保留会话消息和文件；取消不会删除。GoalSummaryQuery 在 RUNNING 或存在未关闭 run 时禁止删除，底层协调器仍重新检查状态，避免陈旧 UI 放行。提醒清理失败显示固定本地化错误并可重试；协程取消重新抛出，不返回成功。

新增真实 Compose→删除协调器→Room/WorkManager 回归：取消确认不清理提醒；注入提醒清理失败时 Goal 保留；重试经生产隐私服务成功后移除 Goal 和 run，提醒工作终止，原会话 Turn 保留，界面列表刷新。查询回归补充 RUNNING、PAUSED 但 run 未关闭、正常 PAUSED 与 READY 的删除可用性。未新增 Provider 调用、审批能力或存储格式。

`./gradlew :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:testConsumerDebugUnitTest :app:lintConsumerDebug spotlessCheck detekt --continue`：consumer/测试 APK、consumer JVM（291 项，288 通过/3 项既有条件跳过）、consumer Lint、Spotless 通过；Detekt 仍既有 53 项，联合命令 exit 1。补充查询边界断言后再次构建测试 APK/Spotless/Detekt，仍仅 Detekt 53 项未过。

专用 API 34 初组删除/提醒 UI 与删除协调器 6/6，最终组合 17/17：`adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.ui.GoalReminderControlsDeviceTest,com.helix.app.GoalDeletionDeviceTest,com.helix.app.chat.GoalUsageReservationsDeviceTest,com.helix.app.GoalReminderNavigationDeviceTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`。包含本次系统通知栏真实点击回归。

日志与 APK hash 见 `build/main-verification/goal-deletion-ui-result.json`、`goal-deletion-ui-gates.log`、`goal-deletion-ui-api34.log`、`goal-deletion-ui-final-gates.log`、`goal-deletion-ui-final-api34.log`。删除中途进程死亡、其他 API 和真实模型完整聊天仍待验收；ADR-0028 proposed 未实施，持续 Goal active，长稳未启动。

## HXA-102：删除事务两侧的真实进程死亡

新增 GoalDeletionProcessKillDeviceTest 与 `scripts/run-goal-deletion-process-kill.py`。专用模拟器内使用独立 fixture DB，真实 GoalRunCoordinator 创建、结束 run 并安排未来检查点，生产删除协调器执行 WorkManager 取消。host 等待测试报告明确边界及 PID，并核实当前 App PID 后发送 SIGKILL；不以关闭数据库或抛异常冒充杀进程。

首个边界位于取消提醒成功后、Goal 删除 SQL 前的开放事务内：PID 8244 实际 SIGKILL。下一进程重新打开 Room，Goal 仍为 PAUSED，检查点与已关闭 run 保留，提醒已终止；重复 reconcile 重建单个工作且不改变 Goal/run/Turn。随后生产删除成功提交，在 PID 8304 再次 SIGKILL。最终新进程确认 Goal/run 不存在、提醒保持终止、原会话 Turn 保留，重复恢复不复活目标。本轮没有为测试修改生产删除语义。

命令 `./gradlew :app:assembleConsumerDebugAndroidTest spotlessCheck detekt --continue`：测试 APK/Spotless 通过，Detekt 仍既有 53 项，联合 exit 1。`python3 -m py_compile scripts/run-goal-deletion-process-kill.py` 通过。安装测试 APK 后执行 `python3 scripts/run-goal-deletion-process-kill.py --serial emulator-5594 --adb "$ANDROID_HOME/platform-tools/adb" --output build/main-verification/goal-deletion-kill-api34`：exit 0，control 与 recover-final 各 OK (1 test)，prepare/recover-commit 均有对应 SIGKILL 与 instrumentation Process crashed 证据。

原始日志、两个 PID、边界、设备 API、APK hash 见 `build/main-verification/goal-deletion-kill-api34/goal-delete-kill-result.json`；构建日志 `build/main-verification/goal-deletion-kill-gates.log`。control 为同进程异常回滚对照；只有 host 两个阶段属于真实进程死亡。该证据覆盖取消提醒后尚未执行删除 SQL，以及已提交后的恢复，不代表逐条 SQL 中间点、断电/损坏、所有 API 或真实 Provider/工具后端中断验收。持续 Goal active；ADR-0028 proposed 仍未实施，长稳未启动。

删除进程边界后，当前 consumer 下全部 13 个文件名含 Goal 的设备测试类组合 42/42 通过。可重放完整命令/类名/APK hash 见 `build/main-verification/goal-after-deletion-integrated-command.json`，原始输出 `goal-after-deletion-integrated-api34.log`。该组合中的两类进程测试使用默认 control 路径；实际 SIGKILL 仅由上述 host 日志单独证明。42/42 仍不等于全 App 或当前 45 项真实模型固定评测验收。

## HXA-102：模型取消关闭实际网络连接

真实 ChatService→OkHttp→SSE 的 held-stream fixture 捕获 Stop/Goal 单次时限后 Turn 无法在 10 秒内收敛，2/2 失败。共享 OkHttpWireClient 原本依赖下一段响应或默认 120 秒读超时才退出；现异步等待响应头及每次阻塞正文读取均绑定 Call.cancel，响应取消竞态关闭未交付 body，保留原 Flow 回调上下文和正文限制。修复后 Goal 取消/超时 2/2，socket 由服务端确认 EOF，run 关闭、模型计数为 1、预算已计账且无 pending reservation，无 ToolCall/请求重放。

详见 [取消缺陷记录](../bug-fixes/2026-09-06-model-stream-cancellation-socket.md)。`./gradlew :provider:api:test :provider:openai-chat:test :provider:openai-responses:test :provider:anthropic:test :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:testConsumerDebugUnitTest :app:lintConsumerDebug spotlessCheck detekt --continue`：Provider JVM 89+56+58+73=276 全通过；consumer JVM 291 项中 288 通过/3 项既有跳过，consumer Lint、APK、Spotless 通过；Detekt 仍既有 53 项，联合 exit 1。

当前全部 14 个 Goal 文件名设备类加 ProviderModelDiscoveryUiTest 组合 48/48，包含实际 OpenAI/Anthropic 连接探测与界面回归。完整命令/类名见 `build/main-verification/goal-model-cancel-integrated-command.json`；输出 `goal-model-cancel-integrated-api34.log`；前后结果、JVM 计数、APK hash 见 `goal-model-cancel-result.json`。该组合中的 kill fixture 使用 control 阶段，不代表本轮执行了模型 SIGKILL。

本次设备模型输出为本地确定性 HTTP fixture，不能替代真实模型服务中断或模型流实际进程死亡；其余后端及全量矩阵继续。ADR-0028 仍 proposed，持续 Goal active，长稳未启动。

## HXA-102：Goal 界面取消和保存重试

GoalEditor/GoalReminderControls 的 IllegalStateException 捕获会包含 CancellationException。新增保存操作回归确认取消 Job 被错误地正常结束；现首先重新抛出取消，保存验证/状态错误单独映射，且新尝试开始清除旧 failed 状态。取消不会冒充保存失败，失败后的成功重试不再遗留错误。详见 [修复记录](../bug-fixes/2026-09-06-goal-ui-cancellation-and-retry.md)。

`./gradlew :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:testConsumerDebugUnitTest :app:lintConsumerDebug spotlessCheck detekt --continue`：consumer/测试 APK、consumer JVM（291 项：288 通过/3 项既有跳过）、consumer Lint 与 Spotless 通过；Detekt 仍既有 53 项，联合命令 exit 1。设备命令 `adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.ui.GoalEditorDeviceTest,com.helix.app.ui.GoalReminderControlsDeviceTest,com.helix.app.GoalDeletionDeviceTest,com.helix.app.chat.GoalModelCancellationDeviceTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`，API 34 11/11。

日志/APK hash 见 `build/main-verification/goal-editor-cancel-result.json`、`goal-editor-cancel-before-api34.log`、`goal-editor-cancel-final-api34.log`、`goal-editor-cancel-final-gates.log`。早期重试测试文本定位失败不作为生产缺陷证据；使用稳定状态标记后验证错误出现与消失。取消注入直接覆盖保存操作，提醒为异常继承关系修正加正常操作回归，未覆盖所有提醒取消竞态。模型实际 SIGKILL 与其他后端矩阵仍继续；ADR-0028 proposed 未实施，持续 Goal active，长稳未启动。

## HXA-102：生产模型流 SIGKILL 与模型调用记录收口

新增 `ModelStreamProcessKillDeviceTest` 和独立宿主 `scripts/run-model-stream-process-kill.py`，使用真实生产 Provider 探测、ChatService、HTTP/SSE、Room 与 HelixApplication 启动恢复。首轮外层 Turn/run/Goal 及预算恢复通过，但审查发现 ModelCall 的 RUNNING 状态未关闭；新增 Room 回归确认 expected INTERRUPTED / actual RUNNING。现同事务修复当前及历史 INTERRUPTED Turn 下遗留的 RUNNING 模型调用，保留已知 usage/requestId、完成状态和无请求重放约束，只在实际变化时写计数审计。

修复记录：[模型调用恢复遗漏](../bug-fixes/2026-09-06-interrupted-model-call-recovery.md)。storage/consumer JVM、consumer Lint、APK、Spotless 通过；storage 73/73，consumer 288 通过/3 项既有跳过；Detekt 仍既有 53 项，联合门禁非全绿。实际命令 `./gradlew :core:storage:testDebugUnitTest :app:testConsumerDebugUnitTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:lintConsumerDebug spotlessCheck detekt --continue`。

API 34 修复前 ModelCallRecoveryDeviceTest 1/1 失败，修复后与 ProcessRecoveryTest、GoalUsageReservationsDeviceTest、GoalProcessKillDeviceTest 组合 20/20。完整设备命令为 `adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.recovery.ModelCallRecoveryDeviceTest,com.helix.app.recovery.ProcessRecoveryTest,com.helix.app.chat.GoalUsageReservationsDeviceTest,com.helix.app.chat.GoalProcessKillDeviceTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`。该组合的进程 fixture 是 control 路径。

另执行 `python3 scripts/run-model-stream-process-kill.py --serial emulator-5594 --adb "$ANDROID_HOME/platform-tools/adb" --output build/main-verification/model-stream-kill-fixed-api34`，exit 0：PID 9764 实际 SIGKILL，宿主观察 EOF；两次新进程恢复均验证 Turn/run/ModelCall INTERRUPTED、Goal PAUSED，预算保守计入 5076 tokens / 5000 ms、模型次数 1，重复恢复不补回预算。宿主 POST 计数 4→4，含一次持有的 Goal 模型请求；两次恢复后各观察 2 秒，不把该窗口描述为永久不重发证明。

证据/APK hash 见 `build/main-verification/model-call-recovery-result.json` 与 `model-stream-kill-fixed-api34/model-kill-result.json`。`model-stream-kill-api34/` 是修复 ModelCall 之前仅覆盖外层状态的初轮，不能当成完整恢复验收。host fixture 只生成本次文本，不使用真实账号或密钥；模型内容不是实模输出。无 host 参数的普通 instrumentation 会明确条件跳过 ModelStreamProcessKillDeviceTest；独立 runner 成功才算本项通过。其他协议/工具后端及全量矩阵继续，ADR-0028 proposed 未实施，持续 Goal active，长稳未启动。

## HXA-102：三协议模型流双边界 SIGKILL 矩阵

ModelStreamProcessKillDeviceTest/host runner 增加明确的 protocol 与 boundary 参数。宿主通过真实生产连接探测后，在 HTTP 请求已到达、尚未发响应头（headers），或 App 已显示部分文本但流未结束（body）时确认准备标记和请求计数，再核对 PID 并 SIGKILL。host 服务跨 App 死亡持续存在，观察 EOF 和所有 POST 次数。Responses/Anthropic 事件 fixture 按仓库解码器测试构造，模型内容为本地生成文本，不使用账号/密钥。

当前同一 consumer/测试 APK 执行三种协议 × 两处边界，6/6 通过：六次实际 SIGKILL、十二次新进程恢复断言成功。每组 Turn/run/ModelCall 均 INTERRUPTED、Goal PAUSED，pending reservation 清空，无 ToolCall，第二次恢复状态/计账完全一致；宿主持有的 Goal 请求各为 1，恢复前后请求数不增长。

| 协议 | 边界 | SIGKILL PID | 保守计入 tokens / ms | 宿主 POST 前后 |
| --- | --- | --- | --- | --- |
| OPENAI_CHAT_COMPLETIONS | headers | 10502 | 5076 / 5000 | 4 → 4 |
| OPENAI_CHAT_COMPLETIONS | body | 10681 | 5076 / 6006 | 4 → 4 |
| OPENAI_RESPONSES | headers | 10859 | 5076 / 5000 | 4 → 4 |
| OPENAI_RESPONSES | body | 11038 | 5076 / 6007 | 4 → 4 |
| ANTHROPIC_MESSAGES | headers | 11215 | 5076 / 5000 | 5 → 5 |
| ANTHROPIC_MESSAGES | body | 11393 | 5076 / 5000 | 5 → 5 |

上述时间是已记账时长加未知窗口的保守结算，不是服务商实测用量；每次恢复后观察 2 秒，不能据此承诺任意时长绝无重发。

命令 `./gradlew :app:assembleConsumerDebugAndroidTest spotlessCheck detekt --continue`：测试 APK/Spotless 通过，Detekt 仍既有 53 项，联合 exit 1；本轮只有测试与脚本变化，未重跑 JVM/Lint。`python3 -m py_compile scripts/run-model-stream-process-kill.py scripts/model_kill_stream_fixtures.py` 通过。

设备 runner 命令为 `python3 scripts/run-model-stream-process-kill.py --serial emulator-5594 --adb "$ANDROID_HOME/platform-tools/adb" --protocol <OPENAI_CHAT_COMPLETIONS|OPENAI_RESPONSES|ANTHROPIC_MESSAGES> --boundary <headers|body> --output <新证据目录>`，六次独立进程执行；完整可重放命令、每轮日志、PID、API、计数及 APK hash 见 `build/main-verification/model-kill-matrix-api34/matrix-result.json` 与对应子目录。无 host 参数的通用 instrumentation 会明确条件跳过该专用测试；仅独立 runner 成功才算验证通过。

本次不覆盖真实模型服务、所有网络故障/工具后端、其他 API、真机或长稳；其余 HXA-102 与全量门禁继续。ADR-0028 保持 proposed，持续 Goal active，长稳未启动。

## HXA-102：当前工具调度与持久恢复基线

复查 GoalTurnBindingDao/GoalRunSettlement/RecoveryCoordinatorApp：未知执行结果保留 INTERRUPTED/NEEDS_REVIEW，关联 Goal 的 Continue 由持久 unresolved 查询阻止；崩溃恢复按 ADR-0004 保持 PAUSED，而不是把外层状态改为成功。此处源码检查不替代具体工具后端杀进程验收。

在当前 consumer APK 执行 `adb -s emulator-5594 shell am instrument -w -r -e class com.helix.app.ToolSchedulerDeviceTest,com.helix.app.recovery.ProcessRecoveryTest com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`：18/18 通过。覆盖已有生产 Dispatcher 集成（取消、结果顺序、审批及持久序列）和数据库恢复 fixture。其恢复 fixture 主要为重开存储/持久行检查，没有在真实工具执行过程中 SIGKILL，不能将该组合记录为工具后端中断矩阵通过。

日志/APK hash/类名见 `build/main-verification/tool-scheduler-current-recovery-result.json`、`tool-scheduler-current-recovery-api34.log`。本轮未修改生产代码，也未重跑 JVM/Lint/Detekt。具体审批、文件、浏览器、UI、MCP、A2A、PRoot/CLI 后端中断仍为 HXA-102 当前待办；模型三协议双边界已单独有真实 SIGKILL 证据。ADR-0028 proposed 未实施，持续 Goal active，长稳未启动。

## HXA-102：MCP 工具调用中断与连接释放

补充 `McpToolCancellationTest`，通过生产 `McpToolRuntime`、MCP SDK/Ktor/OkHttp 和本地 HTTP fixture 完成 initialize 后进入实际 `tools/call`。fixture 保持 SSE 心跳但不给工具结果，分别施加执行器 `Future.cancel(true)`（Dispatcher 的线程中断机制）和调用 deadline。测试要求运行体真正退出、异常为中断/取消、服务端写入观察到连接断开，以及工具请求保持一次；没有仅凭 Future 的 cancelled 标志判定成功。未发现该路径需要修改的生产缺陷。

该验证是 JVM 上的真实网络传输测试，使用测试 endpoint permit、无凭据，不涵盖 Android 生产注册/审批路径、进程 SIGKILL、远端效果撤销或重连恢复。MCP 实际工具进程中断矩阵仍待完成。为复用既有网络 fixture 将其改为测试内部可见，并拆分工具请求处理，避免增加 Detekt 复杂度问题；未新增 suppress。

验证命令：`./gradlew :extensions:mcp:test spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）。MCP JVM **37/37 通过、零跳过**，Spotless 通过；总命令 exit 1，根 Detekt 仍有原有 **53** 项，不能记录全绿。首次检查的新增 fixture 复杂度问题已通过拆分消除。证据：`build/main-verification/mcp-tool-cancel-final-gates.log` 和 `mcp-tool-cancel-result.json`。文档检查 224 Markdown/130 HXA、ADR 检查 28 条均通过。未重跑 Android/Lint；ADR-0028 仍 proposed，持续 Goal active，长稳未启动。

## HXA-102 收口期间：Provider 行状态与回调静态清理

移除 `ProviderRow` 的重复视觉能力参数，从当前 `ProviderRowUi.capabilities` 派生；将五个操作回调归入 `ProviderRowActions`，提取编辑表单构造，并将独立的模板选择逻辑移至 `ProviderEditTemplate.kt`。保留模型 chip 只预填不自动保存、无密钥编辑不新增密钥要求、手动视觉声明和外部账号入口的原有语义。此项为当前 main 非长稳门禁清理，不声明统一 UI/UX 优化完成，也不改变授权边界。未新增 Detekt suppress。

验证：`./gradlew :app:testConsumerDebugUnitTest :app:lintConsumerDebug :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）执行完毕；Consumer JVM 291 项中 288 通过、3 项既有条件跳过，Consumer Lint/构建/Spotless 通过。根 Detekt 从 53 降至 **51**，总命令仍 exit 1，未放宽规则。专用 API 34 安装该 APK 后，`ProviderFlowTest,ProviderModelDiscoveryUiTest` 组合 **5/5** 通过（创建、失败状态不可选择、删除、模型列表/预填/过滤等现有 UI 回归）。日志及 APK hash 见 `build/main-verification/provider-row-state-result.json`。文档 224 Markdown/130 HXA、ADR 28 条检查通过；非全仓或双变体完整验收。持续 Goal active，ADR-0028 proposed、长稳后置均保持。

## HXA-102 收口期间：ProviderService 托管账号依赖整理

将 `ProviderService` 原有三个托管 Provider 回调归入 `ManagedProviderHooks`，由 AppContainer 继续连接当前变体的 `SubscriptionProviderModule`。默认行为仍是非托管、无探测覆盖、不支持外部账号入口；Developer 仍使用原来三个实现。构造参数从 11 减至 9，编辑/删除/视觉声明的托管限制、探测回退与账号入口判断不变。没有新增服务启动、凭据传递、权限或协议能力；本项仅为 main 静态门禁清理。

验证命令：`./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:lintConsumerDebug :app:lintDeveloperDebug spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）。Consumer 288 通过/3 既有条件跳过，Developer 300 通过/3 既有条件跳过，无失败；双变体 Lint 和 Spotless 通过。根 Detekt 从 51 降至 **50** 项，命令整体 exit 1，仍未全绿。日志及源码 hash 见 `build/main-verification/provider-hooks-result.json`。文档检查 224 Markdown/130 HXA、ADR 28 条通过。本轮未运行设备/真实账号测试，不替代其边界验收。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：M11 订阅流静态清理与边界回归

拆分共享订阅响应的 HTTP 错误映射和成功流读取；以事件数量约束读取循环，超过事件上限时不执行 decoder.finish，字节超限及事件超限仍返回单一 PROTOCOL 错误并丢弃部分结果。提取 SubscriptionHttpModel 的文本请求资格判断和发送方法，保留先拒绝工具/图片请求、再检查账号、刷新后单次 POST 的顺序，不重放含糊请求。

新增 SubscriptionModelStreamTest 四项边界测试：精确字节上限、字节上限加一、feed 事件溢出、精确事件上限及 finish 溢出；分别验证是否收尾与是否丢弃部分结果。此项不涉及真实订阅账号调用或 Android 进程恢复验收。

验证命令：`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）。CLI Runtime JVM **74/74**、零跳过，模块 Lint 和 Spotless 通过；根 Detekt **50→47**，总命令仍 exit 1。证据见 `build/main-verification/subscription-stream-static-result.json` 与同名前缀 gates 日志。本轮未进行真实账号、设备或完整 main 回归；持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：M11 OAuth 解析静态清理

Claude 回调保留大小、URL、路径、错误字段、state、code 的原有判断顺序，将参数解析从目标 URL 检查中分离；Grok 设备轮询将已授权响应拆为 tier 与会话字段解析，保留 pending/slow_down/拒绝码、tier 白名单及严格的到期时间计算。没有新增账号访问、授权能力或规则豁免。

新增 GrokAuthorizationResponseTest 三项：缺少 refresh token、无效 expiry、expiry 算术溢出均不能创建授权会话；现有 Claude/Grok 协议测试继续验证回调与轮询行为。

验证：`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）。CLI Runtime JVM **77/77**，无跳过；模块 Lint/Spotless 通过。根 Detekt **47→45**，总命令 exit 1，剩余违规仍需修复。源码 hash 和结果见 `build/main-verification/oauth-parser-static-result.json`。无真实账号登录或设备验收；持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI 握手 Binder 死亡处理

发现并修复 `CliStatusHandshakeClient` 未捕获 `RemoteException` 导致 `DeadObjectException` 逃逸。专用 API 34 本地 Android Binder 注入测试从 4 项中 1 项失败转为 **4/4 通过**；保留未处理事务/空文档返回 Failed、调用者不匹配返回 CallerMismatch。只转换握手失败，不重发事务或 Job；Parcel 的 finally 清理保持。缺陷说明见 [Binder 死亡处理](../bug-fixes/2026-09-07-cli-status-binder-death.md)。

命令：`./gradlew :runtime:cli-client:testDebugUnitTest :runtime:cli-client:lintDebug :app:testDeveloperDebugUnitTest :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）。CLI Client JVM 24/24、Developer JVM 300 通过/3 既有条件跳过；CLI Client Lint、构建、Spotless 通过。根 Detekt **45→44**，总命令仍 exit 1。设备日志、构建日志和 APK hash 见 `build/main-verification/cli-handshake-result.json`。此项不替代真实跨进程 SIGKILL/Job 对账矩阵。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI Job Binder 通信异常

抽取内部 CliModelJobWire，将 Binder/PFD 传输与 Job 轮询协调分开；发现原实现仅捕获 DeadObjectException，普通 RemoteException 逃逸。API 34 模块内 Android Binder 注入测试复现 3 项中 1 项失败，统一捕获后 **3/3 通过**，并断言失败事务只发送一次、缺失状态文档不返回成功。保留外部 API、Job ID、查询/对账顺序、输出 hash 与 PFD 边界；无真实账号或跨进程 SIGKILL 验收。

为 CLI Client 增加模块设备测试入口，复用固定 AndroidX 测试版本及更新该模块锁文件；未移除或升级已有锁定依赖版本。详见 [Job 通信异常](../bug-fixes/2026-09-07-cli-job-remote-exception.md)。

验证命令：`./gradlew :runtime:cli-client:testDebugUnitTest :runtime:cli-client:lintDebug :runtime:cli-client:assembleDebugAndroidTest :app:testDeveloperDebugUnitTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）。CLI Client 24/24、Developer 300 通过/3 既有跳过，模块 Lint、设备测试构建及 Spotless 通过。最后仅压缩调用排版后复跑 `./gradlew spotlessCheck detekt --continue --no-daemon --max-workers=2`，根 Detekt **44→42**，仍 exit 1。日志和设备已安装测试 APK hash 见 `build/main-verification/cli-job-wire-result.json`。没有把根门禁、真实账号或跨进程恢复记为通过；持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI Job PFD 读取异常

API 34 真实 Parcel/PFD 注入复现不可读描述符的 IOException/EBADF 逃逸：新增 3 项中 1 项失败。传输层显式捕获 IOException 返回既有 HANDSHAKE_FAILED，保留有效输出事件、hash 拒绝及单次对账请求。修复后 PFD 与 Binder 组合 **6/6** 通过。详见 [PFD 读取异常](../bug-fixes/2026-09-07-cli-job-pfd-read-failure.md)。此项不替代提交端管道、真实跨进程 SIGKILL 或副作用恢复。

执行 `./gradlew :runtime:cli-client:testDebugUnitTest :runtime:cli-client:lintDebug :runtime:cli-client:assembleDebugAndroidTest :app:testDeveloperDebugUnitTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：CLI Client JVM 24/24、Developer 300 通过/3 既有条件跳过；模块 Lint、构建、Spotless 通过。根 Detekt 仍 **42** 项、无新增，总命令 exit 1。证据/APK hash 见 `build/main-verification/cli-pfd-result.json`。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI 请求管道后台写入失败

发现拒绝 256 KiB 请求时后台写线程 EPIPE 未被捕获；API 34 设备回归确证失败。新增 CliRequestPipe 管理一次上传、记录 IOException、清理描述符及有界等待线程，失败上传不保留成功回复。增加完整请求读取的正向回归；修复后上传/PFD/Binder 组合 **8/8** 通过，拒绝事务不重发、无未捕获异常、写线程退出。详见 [请求写线程缺陷](../bug-fixes/2026-09-07-cli-request-pipe-writer.md)。本地 Binder/PFD 证据不替代真实 companion SIGKILL；远端长期持有描述符的全部内核阻塞情况仍需进一步验证。

执行 `./gradlew :runtime:cli-client:testDebugUnitTest :runtime:cli-client:lintDebug :runtime:cli-client:assembleDebugAndroidTest :app:testDeveloperDebugUnitTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：CLI Client JVM 24/24、Developer JVM 300 通过/3 既有条件跳过，模块 Lint、构建、Spotless 通过。根 Detekt 42 项、无新增，总命令 exit 1。证据及 APK hash 见 `build/main-verification/cli-request-pipe-result.json`。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI 上传提前接受与保留读取端

新增 CliPrematureAcceptanceDeviceTest：不读取 256 KiB 请求即返回 ACCEPTED，以及保留 PFD 读取端但不消费时提前返回 ACCEPTED。两者均要求传输返回 HANDSHAKE_FAILED/无成功记录，事务一次；保留读取端的清理须在三秒内返回，早于五秒夹具兜底，释放读取端后写线程退出。与上传/PFD/Binder 组合 **10/10** 通过（约 1.073 秒）。未发现需要进一步修改生产代码的缺陷。此项为 API 34 本地 Android Binder/PFD，不能替代真实 companion SIGKILL 或所有内核阻塞情形。

命令 `./gradlew :runtime:cli-client:assembleDebugAndroidTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：测试构建/Spotless 通过，根 Detekt 仍 42 项、无新增，总命令 exit 1。本轮仅测试修改，未重跑 JVM/Lint。日志和 APK hash：`build/main-verification/cli-premature-ack-result.json`。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：当前 CLI Runtime 跨进程 fixture 恢复

专用 API 34 安装当前 CLI Runtime（之前未安装），新包 stopped 状态拒绝为 FORCE_STOPPED 的测试 1/1 通过。通过可见 Runtime 入口启动后退出（不登录），确认 Runtime 进程不存活后，冷绑定测试 1/1 通过。没有使用 shell 伪造包状态通过 Capability，也未操作其他账号模拟器。

现有跨进程类缺少 Codex RUNNING 路径，补充与其他 Provider 一致的用例。最终同组 **8/8** 通过：CODEX/CLAUDE/GROK/COPILOT 四种请求封装分别完成 PFD 结果、对账后不重复交付，以及 RUNNING 取消→CANCELLED、debug 自杀后重绑查询→INTERRUPTED、再次查询一致。四平台均使用 Runtime 内先于账号分支的 helix-fixture/helix-fixture-wait；证明真实独立 Runtime 进程、Binder/PFD 和持久 Job 恢复，不证明真实订阅网络后端。系统 am_proc_died/am_proc_start 日志佐证进程死亡/启动。没有执行 oldRuntime、真实固定模型或付费账号用例。

构建 `./gradlew :runtime:cli-app:assembleDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest --no-daemon --max-workers=2` 通过；新增用例后 `./gradlew :app:assembleDeveloperDebugAndroidTest spotlessCheck detekt --continue --no-daemon --max-workers=2` 的构建/Spotless 通过，根 Detekt 42 项，exit 1。完整选择器、日志、三个 APK hash 见 `build/main-verification/cli-runtime-cross-process-result.json`。本轮测试增补未重跑 JVM/Lint，且不代表主 App 被杀、提交/提交前后每一边界及真实模型网络全部完成。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI 跨进程测试结构清理

将四个平台的运行中取消/进程死亡用例从 CliRuntimeHandshakeE2eDeviceTest 移至 CliRuntimeRunningRecoveryDeviceTest，提取单次中断与 RUNNING 等待方法。保留四种 Provider、取消/kill 两分支、同 Job 查询一致、对账不重复交付等断言；用有界 while 计数替换未使用的循环变量，未跳过或删除场景。旧类保留历史兼容、冷绑定及 PFD 对账用例。此项仅测试代码清理。

`./gradlew :app:assembleDeveloperDebugAndroidTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：测试构建、Spotless 通过，根 Detekt **42→39**，总命令 exit 1。安装后重跑原四平台 PFD 对账与四平台 RUNNING 恢复组合 **8/8** 通过（19.01 秒），没有以更小子集替代原场景。选择器、日志及 APK hash 见 `build/main-verification/cli-runtime-test-split-result.json`。仅测试修改，未重跑 JVM/Lint；真实账号网络、主 App 被杀及完整中断矩阵仍待验。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI Service fixture 与后端选择分离

提取 debug fixture 执行与等待取消逻辑，降低 CliRuntimeService.onCreate 长度。BuildConfig.DEBUG 门保持；四个订阅后端选择、凭据 ownership、activeModel 安装/清理与取消行为未改变。没有新增网络调用或发布能力。

执行 `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug :runtime:cli-app:assembleDebug :runtime:cli-app:compileReleaseKotlin spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Runtime JVM **77/77**，模块 Lint、Debug 构建、Release Kotlin 编译、Spotless 通过；根 Detekt **39→38**，总命令 exit 1。安装新 Runtime 后四平台 PFD/运行中取消/进程死亡恢复组合 **8/8** 通过（19.721 秒）。Release 编译不等于 Release 功能/发布验收；debug fixture 不等于真实账号网络。日志与 APK hash 见 `build/main-verification/cli-service-fixture-result.json`。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI 本地可用性原因整理

将 CliRuntimeSupervisor.localCause 整理为有序分支，保持缺包/缺 applicationInfo、禁用、stopped、签名检查的原顺序；可见 UI 入口仍可忽略 stopped，绑定检查仍拒绝 stopped。未增加 Runtime 启动或授权。

执行 `./gradlew :runtime:cli-client:testDebugUnitTest :runtime:cli-client:lintDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：CLI Client JVM **24/24**、模块 Lint、构建、Spotless 通过，根 Detekt **38→37**，总命令 exit 1。专用 API 34 实际 pm disable-user→DISABLED、am force-stop→FORCE_STOPPED、通过可见入口恢复后的正常绑定三项 **3/3** 通过；finally 恢复默认启用，最终 dumpsys 确认 enabled=0/stopped=false，与原状态相同。未登录、未删除数据、未操作其他模拟器。

本轮不声称缺包、错误签名或真实账号重新验收；日志与 APK hash 见 `build/main-verification/cli-local-cause-result.json`。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI 绑定流程拆分

拆分本地拒绝检查、bindService 结果映射及连接等待，保留 SecurityException→SIGNATURE_MISMATCH、平台 RuntimeException/false→BIND_REFUSED、空绑定→HANDSHAKE_FAILED、等待中断→TIMEOUT 并恢复中断标志；绑定失败不执行无效 unbind，成功连接由调用方显式释放。新增六项 Android 绑定回调注入测试，并仅在测试 Manifest 声明 Runtime 包可见性，未扩大产品 Manifest。

`./gradlew :runtime:cli-client:testDebugUnitTest :runtime:cli-client:lintDebug :runtime:cli-client:assembleDebugAndroidTest :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：CLI Client JVM **24/24**、Lint/构建/Spotless 通过；根 Detekt **37→36**，整体 exit 1。API 34 回调注入 **6/6**，另有真实 Runtime debug fixture 跨进程 PFD/取消/死亡恢复 **8/8**（19.736 秒）。两组证据分开记录，不将注入误记为真实系统异常；真实账号网络与完整 kill 矩阵仍未完成。日志、选择器及 APK hash 见 `build/main-verification/cli-binding-result.json`。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：M11 测试 JSON/SSE 行长清理

将 Grok/Copilot 测试的三段超长 JSON/SSE 字面量改成短字符串拼接，移除这三个测试文件的 ktlint 整文件 max-line-length 豁免。解析当前拼接表达式后比对修改前 SHA256，三段 fixture 字节完全一致；未删除测试、改变协议输入或断言。

`./gradlew :runtime:cli-app:testDebugUnitTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Runtime JVM **77/77**、Spotless 通过，根 Detekt **36→33**，整体 exit 1。字节 hash 与日志见 `build/main-verification/cli-test-json-result.json`。仅测试字面量排版，未重跑设备/Lint；持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：订阅模块双变体契约

新增 SubscriptionProviderIntegration 明确共享的注册、创建、托管判断、探测覆盖与账号入口方法；Consumer/Developer 对象显式实现契约，由编译器检查方法签名。Consumer 仍返回原有无注册/无客户端/无探测覆盖/不支持账号入口的结果，未引入 Runtime 客户端或产品能力。Developer 保留原接线，同时将 openAccount 的 Runtime 不可用分支整理为表达式，保留 NOT_SUPPORTED 与 RUNTIME_UNAVAILABLE 的区别。未增加 suppress 或修改静态门限。

`./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:lintConsumerDebug :app:lintDeveloperDebug spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Consumer **288 通过/3 既有跳过**，Developer **300 通过/3 既有跳过**，无失败；双变体 Lint/Spotless 通过。根 Detekt **33→21**，原 Consumer 常量返回/未使用参数由正式 override 契约解决，Developer 多出口同时消除；没有新增豁免或放宽规则，整体命令仍 exit 1。日志与源码 hash：`build/main-verification/subscription-variant-contract-result.json`。本轮未运行设备或账号测试；持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI Job 提交回复编码整理

将 CliRuntimeServiceBinder 的提交 PFD 解析和 runner.submit 结果映射提取为 writeSubmit，保留 ACCEPTED、DUPLICATE、REQUEST_MISMATCH、BUSY、JOURNAL_FULL 回复码及原有查询/取消/对账分派。没有修改协议、增加重发或账号调用。

`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug :runtime:cli-app:assembleDebug spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Runtime JVM **77/77**、Lint/构建/Spotless 通过，根 Detekt **21→20**，整体 exit 1。新 Runtime 与既有已安装 Developer App/测试 APK 配对，四平台跨进程 fixture **8/8**（20.457 秒），不作为所有 main 最新修改同 APK 的全量验收。日志及配对 APK hash 见 `build/main-verification/cli-submit-reply-result.json`。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI Job 状态与载荷存储分离

将请求/结果文件的读写、容量统计及删除集中到 CodexPayloadFiles，CodexPayloadJobStore 保留状态记录与对账协调。保持 provider-v1/codex-model-jobs 目录、文件名、字节上限、写入实现及先删除请求/结果再落盘对账时间的顺序。新增旧目录格式重开读写、删除失败不标记对账且保留结果两项回归测试。

`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug :runtime:cli-app:assembleDebug spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Lint/构建/Spotless 通过，根 Detekt **20→19**。首轮 JVM 79 项中新增测试因遗漏终态记录必需的 terminalAtEpochMillis 而失败 1 项；修正测试数据后执行 `./gradlew :runtime:cli-app:testDebugUnitTest spotlessCheck --no-daemon --max-workers=2`，**79/79、零跳过**及 Spotless 通过。首轮失败与复验日志均保留。

新 Runtime APK 与既有已安装 Developer App/测试 APK 配对，API 34 跨进程 fixture **8/8**（20.767 秒），涵盖载荷对账及运行中取消/死亡恢复。配对 APK 的设备端 SHA256、命令及日志见 `build/main-verification/cli-payload-store-result.json`；不作为最新 main 同 APK 全量验收或真实账号网络结果。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：CLI 等待中断取消遗漏

将等待状态机从 Binder 连接管理中提取为 CliModelJobAwaiter 后，检查生产 runInterruptible 调用链发现中断只退出等待、不发送 CANCEL。JVM 修复前六项中 1 项失败；API 34 单连接测试被服务销毁时 runner.close 掩盖，保留额外观察连接后真实复现 Job 仍 RUNNING。现于中断分支向原 Job 发送一次 CANCEL，并在 finally 恢复线程中断标志；提交仍只有一次，查询失败不重发。详见[缺陷记录](../bug-fixes/2026-09-07-cli-interrupted-wait-cancel.md)。

`./gradlew :runtime:cli-client:testDebugUnitTest :runtime:cli-client:lintDebug :app:testDeveloperDebugUnitTest :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Client **30/30**，Developer **300 通过/3 既有跳过**，模块 Lint/构建/Spotless 通过；根 Detekt **19→18**，总命令 exit 1。新 Developer App/测试 APK 与已安装 Runtime 配对，API 34 原八项加多连接中断取消用例 **9/9**（21.186 秒）。

首轮、改进观察条件后的失败、修复后结果及设备端 APK hash 见 `build/main-verification/cli-await-result.json`。该结果验证真实跨进程 debug fixture；尚不代表全部 UI Stop、真实订阅网络或完整 main 同 APK 验收。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：Codex 连通性响应完成与读取上限

发现固定订阅探测只凭 HELIX_OK 文本判成功，未要求服务端完成事件；单行大小检查又发生在整行读取之后。提取 CodexSmokeStream，复用有界读取并要求 response.completed/status=completed 与固定输出同时满足。修复前六项中四项失败；补齐精确字节上限、畸形事件、文本上限后九项解析测试通过。详见[缺陷记录](../bug-fixes/2026-09-07-codex-smoke-stream-completion.md)。

`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug :runtime:cli-app:assembleDebug :runtime:cli-app:compileReleaseKotlin spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Runtime JVM **88/88、零跳过**，模块 Lint、Debug 构建、Release Kotlin 编译、Spotless 通过；根 Detekt **18→14**，整体 exit 1。日志与源码/构建产物 hash 见 `build/main-verification/codex-smoke-stream-result.json`。

本轮为本地响应解析验证，未安装新 Runtime APK、未运行设备或订阅账号网络测试，不据此扩写真实账号或 Release 验收。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：Codex 模型目录结构验证

提取 CodexSmokeCatalog 并修复目录项类型未验证的问题：非对象项原先抛出未分类异常，数字 slug 原先被当作模型名称。修复前八项中两项失败，修复后八项通过；保留选择顺序、隐藏项过滤及原始字节上限，错误结构归 models-protocol，空目录归 models-empty。详见[缺陷记录](../bug-fixes/2026-09-07-codex-smoke-catalog-shape.md)。

`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug :runtime:cli-app:assembleDebug :runtime:cli-app:compileReleaseKotlin spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Runtime JVM **96/96、零跳过**，模块 Lint、Debug 构建、Release Kotlin 编译、Spotless 通过；根 Detekt **14→13**，整体 exit 1。首轮与复验日志、源码及产物 hash 见 `build/main-verification/codex-smoke-catalog-result.json`。

本轮为本地目录响应验证，未安装新 Runtime APK 或复验设备/真实账号网络；Release 编译不是发布验收。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：订阅 Provider 设备契约测试整理

将 CodexSubscriptionProviderE2eDeviceTest 中模型流和聊天落盘断言提取到 SubscriptionProviderContractCheck，保留注册配置覆写与 finally 恢复、探测、托管限制及能力断言。没有删减测试或改变固定模型。设备执行范围只选四个 Provider 正常模型契约方法，不包含账号入口和真实模型调用方法。

`./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：构建、Spotless 通过，根 Detekt **13→12**，整体 exit 1。新 Developer App/测试 APK 配对既有已安装 Runtime，API 34 四个 Provider debug fixture 集成用例 **4/4**（2.939 秒）；验证探测、模型事件、聊天 COMPLETED、持久化 Turn 及单次 modelCall。日志、精确选择器及设备端 APK hash 见 `build/main-verification/subscription-contract-test-result.json`。

本轮为测试辅助函数整理，未重复运行 JVM/Lint；未覆盖真实账号网络或登录 UI，也不代表最新 Runtime 构建已经安装。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：Copilot 登录页面职责分离

将原布局、系统 Insets、文本和按钮构建移至 CopilotLoginContent，Activity 保留凭据/传输控制器、登录任务、取消及生命周期。回调、文案、控件顺序和启用条件保持原样；原 minSdk 兼容用的 DEPRECATION 注释随原代码移动，没有新增豁免。

`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug :runtime:cli-app:assembleDebug :runtime:cli-app:compileReleaseKotlin spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Runtime JVM **96/96、零跳过**，模块 Lint、Debug 构建、Release Kotlin 编译、Spotless 通过；根 Detekt **12→10**，整体 exit 1。安装新 Runtime 后，API 34 Copilot 显式账号入口 **1/1**（0.288 秒），UI hierarchy 确认六按钮及未登录时仅生成 Device Code 启用。

未点击生成码、浏览器、取消或删除凭据；捕获后 Back 退出。此证据仅覆盖未登录初态，不作为实际登录/网络/取消验收，也不是第二阶段统一 UI 优化完成。日志、UI 检查及设备端 APK hash 见 `build/main-verification/copilot-content-result.json`。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：Grok 登录页面职责分离

将布局与按钮初始忙碌状态移至 GrokLoginContent，将既有错误分类和文案映射移至 GrokLoginFailure；Activity 保留任务、剪贴板反馈及生命周期。原按钮顺序、Insets、状态文本选择性、失败消息及登录/取消行为保持不变，没有新增静态豁免。

`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug :runtime:cli-app:assembleDebug :runtime:cli-app:compileReleaseKotlin spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Runtime JVM **96/96、零跳过**，模块 Lint、Debug 构建、Release Kotlin 编译、Spotless 通过；根 Detekt **10→8**，整体 exit 1。新 Runtime APK 的 API 34 Grok 显式账号入口 **1/1**（0.291 秒），UI hierarchy 验证六按钮及未登录时仅生成 Device Code 启用。

没有点击登录、浏览器或删除凭据；检查后 Back 退出。未复验真实账号网络/取消流程，不作为第二阶段统一 UI 优化完成。日志、UI 状态和设备端 APK hash 见 `build/main-verification/grok-content-result.json`。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：Codex 固定探测与错误文案职责分离

将固定探测的提交状态映射和等待逻辑提取到 CodexSmokeJobProbe，普通错误/网络分类及探测结果文案提取到 CodexLoginFailure；忙碌状态共用属性。保留 Job ID/hash、单次提交、拒绝码、480 次轮询/250 毫秒间隔、超时取消、结果文案及资源关闭顺序。原 Activity 布局与类规模问题仍待处理。

`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug :runtime:cli-app:assembleDebug :runtime:cli-app:compileReleaseKotlin spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Runtime JVM **96/96、零跳过**，Lint、Debug 构建、Release Kotlin 编译、Spotless 通过；根 Detekt **8→5**，整体 exit 1。初次提取遗留的表达式尾逗号导致格式检查失败，修正后通过；首轮日志保留。

新 Runtime APK 的 API 34 Codex 显式账号入口 **1/1**（0.290 秒）；UI hierarchy 检查八按钮、未登录时仅两个登录入口启用。未点击登录或固定探测按钮，检查后 Back 退出；不作为真实账号/网络探测验收。日志和设备端 APK hash 见 `build/main-verification/codex-smoke-job-result.json`。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：Codex 登录页面布局职责分离

提取 CodexLoginContent 管理布局、浏览器/复制按钮与展示状态；Activity 保留登录任务、探测以及资源生命周期，原取消动作独立为 cancelCurrentAttempt。文案、顺序、剪贴板反馈、状态启用条件和取消资源顺序保持不变。没有扩大凭据可见范围或更改登录方式。

初轮格式检查发现长行，初轮编译发现按钮属性/方法同名导致引用歧义，分别通过拆行和明确的剪贴板方法名修正，保留失败日志。最终执行 `./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug :runtime:cli-app:assembleDebug :runtime:cli-app:compileReleaseKotlin spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：Runtime JVM **96/96、零跳过**，Lint、Debug 构建、Release Kotlin 编译、Spotless 通过；根 Detekt **5→3**，整体 exit 1。

安装新 Runtime，API 34 显式账号入口 **1/1**（0.285 秒）；与上一轮 UI hierarchy 逐项比较，八按钮文案及启用状态完全一致。未点击登录/固定探测，Back 退出；未覆盖真实网络登录和取消，不作为全量 UI 验收。日志及设备端 APK hash 见 `build/main-verification/codex-content-result.json`。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：刷新异常边界与 Detekt 归零

移除 CodexSubscriptionSmoke 对刷新错误的通用捕获及消息猜测，保留控制器自身的永久失效删除/临时错误保留规则。新增六项本地 HTTP 拦截回归，修复前取消/参数异常两项失败，修复后通过；还验证令牌轮换、一次刷新及重复 401 不发 POST。详见[缺陷记录](../bug-fixes/2026-09-07-codex-smoke-refresh-exceptions.md)。

`./gradlew :runtime:cli-app:testDebugUnitTest :runtime:cli-app:lintDebug :runtime:cli-app:assembleDebug :runtime:cli-app:compileReleaseKotlin spotlessCheck detekt --continue --no-daemon --max-workers=2`（JDK 17）：**exit 0**，Runtime JVM **102/102、零跳过**，模块 Lint、Debug 构建、Release Kotlin 编译、Spotless、根 Detekt 全部通过。最后三项 Detekt 已消除；首轮 53 项历史报告保留，没有新增豁免或放宽规则。

有效失败基线与夹具初次编译/格式修正日志分别保存，最终命令、源码/产物 hash 见 `build/main-verification/codex-smoke-refresh-result.json`。本轮没有安装新 Runtime 或调用真实账号；根 Detekt 通过不代表根 lintDebug、全仓 JVM、同 APK 全量设备矩阵已完成。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：全仓 JVM、Lint 与构建统一复验

本轮在同一源码/配置快照执行根 `test`，通过 init script 强制所有 Test task 重新执行并禁用其输出缓存。启用现有 Connector 样本目录与交接 ZIP，包含公开文档 MCP 的只读查询，不调用受保护账号或执行样本代码。根任务计划与实际日志逐项匹配：34 个有源码 Test task 实际执行，1 个 NO-SOURCE，合计 **2,602/2,602、零失败、零跳过**。Consumer 291、Developer 303；此前两变体共 6 个 Connector 条件实例全部通过。这不构成 Connector 受保护账号或完整业务兼容性验收。

执行命令（JDK 17）：

```bash
HELIX_CONNECTOR_ACCEPTANCE_DIR="$PWD/app/build/outputs/hxa-125-samples" \
HELIX_CONNECTOR_SAMPLE_ZIP="$PWD/app/build/outputs/connector-handoff-672dc5a/inputs/connector-参考包.zip" \
./gradlew test lintDebug --init-script build/main-verification/force-tests.gradle \
  --continue --no-configuration-cache --no-daemon --max-workers=2

./gradlew lintRelease lintConsumerDebug lintDeveloperDebug lintConsumerRelease lintDeveloperRelease \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerRelease :app:assembleDeveloperRelease \
  :runtime:proot-app:assembleDebug :runtime:proot-app:assembleRelease \
  :runtime:cli-app:assembleDebug :runtime:cli-app:assembleRelease \
  --continue --no-daemon --max-workers=2
```

两组命令均 **exit 0**。根 lintDebug 18 模块、根 lintRelease 18 模块、App 四组变体 Lint 通过；App Consumer/Developer 及 PRoot/CLI Runtime 共 **8 个 Debug/Release APK** 构建通过。Root lint 与 App flavor lint 分开计数，NO-SOURCE 不算测试通过数；完整 Lint 已运行，不以 lintVital 的跳过冒充验证。

证据：`build/main-verification/full-main-jvm-lint-result.json`、`full-main-release-flavor-build-result.json`。记录任务范围、逐模块测试计数、条件实例、输入 hash、八个 APK hash；`full-main-jvm-lint-source.json` 的 1,031 个源码/配置文件在两组命令期间均未变化。构建和 Lint 可使用有效缓存，JVM Test task 本轮全部实际执行。

本轮未安装上述 APK，没有将主机侧门禁扩写为设备、签名/商店或真机验收。依赖锁/许可证/secrets 检查、同当前 APK 的固定评测与设备完整矩阵、剩余功能边界及第二阶段统一交互优化继续待办。持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：依赖锁与辅助门禁

执行 `bash scripts/check-lockfiles.sh` 首轮发现 CLI Client 的 debugAndroidTestLintChecksClasspath 未完整落锁。保留原文件备份与首轮 diff，确认唯一变化是该 Lint 配置（其余运行/编译/Test 配置坐标未变），接受 Gradle 生成结果后重跑，**35 个依赖锁文件通过**。额外备份包含 settings-gradle.lockfile，共 36 文件，不能混同锁脚本的计数。`./gradlew :runtime:cli-client:lintDebug spotlessCheck --no-daemon --max-workers=2` 随后通过。

CLI 边界脚本首轮仍要求 PFD 实现在旧 Client 文件，并依赖被格式化的单行写法。更新到实际 Wire/RequestPipe/Awaiter 接线，保留 PFD 读写、取消、协议上限、Manifest/权限与 Consumer APK 排除检查；凭据扫描范围同时覆盖新拆出的 IPC 文件，匹配仅输出文件名。`bash scripts/check-cli-runtime-boundary.sh`（ANDROID_HOME 已配置）与 `bash scripts/verify-variant-boundaries.sh` 均通过。

其余结果：

- `bash scripts/check-secrets.sh`：通过。
- `bash scripts/check-i18n.sh`：312 个生产源文件、843 个资源 key，中英/中文资源一致。
- `python3 -B -m unittest discover -s scripts/tests`：2/2。
- `bash scripts/check-mcp-android-spike.sh`：minApi 29 的 R8 字节码检查通过。
- `bash scripts/check-a2a-sdk-android-spike.sh`：**预期拒绝符合检查结果**，原 SDK 缺失 java.net.http.HttpClient；不称 SDK Android 兼容成功。
- `bash scripts/check-a2a-minimal-android-spike.sh`：最小客户端 minApi 29 R8 通过。
- `bash scripts/check-cli-runtime-lock.sh`：七个固定制品下载 hash、锁元数据与 APK 不捆绑边界通过。OpenAI terms-of-use URL 自动读取返回 **403**，按脚本原规则保留记录；声明链接检查不是法律条款完整读取或分发授权。

日志与校验范围见 `build/main-verification/main-auxiliary-gates-result.json`；锁配置差异见 `cli-client-lint-lock-diff.json`。先前 2,602 JVM 与 APK/Lint 结果保持其原源码快照，本轮只补充 Lint 锁配置及边界脚本适配，不伪造重跑全量 JVM 的记录。PRoot 实际资产检查、设备矩阵、固定模型统一评测和功能/UI 剩余项继续；持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：PRoot 实际资产复核

直接核验当前 runtime-lock 与 APK 字节：RootFS 原始 tar **137,287,680 字节**、SHA256 与锁一致；其 apk 数据库的 **53 个包**版本/许可证元数据与锁及 ALPINE-README 表逐项一致。Debug/Release 两个 PRoot APK 内 **11 项资产**均与源资产逐字节一致，包含锁与许可证材料。

下载三个锁定上游 deb 并验证大小/hash，提取其中 proot、loader、libtalloc、libandroid-shmem，四个二进制与打包源文件逐字节一致。未运行下载的二进制、未执行 RootFS，也没有更新锁或重新构建 Docker RootFS。

从已验证 tar 提取所有常规 ELF 文件，加上四个 PRoot 侧二进制，执行 `./gradlew :runtime:proot-core:assetGate "-PassetGateArgs=--min-align 16384 --expect-machine 183 $PWD/build/main-verification/proot-assets-audit" --no-daemon --max-workers=2`（JDK 17）：**179 个 ELF、min PT_LOAD p_align=16384、e_machine=183，通过**。本次不重复计算符号链接别名，因此不能与历史整树遍历的 364 条路径计数直接比较。初轮相对路径在模块工作目录下不存在而失败；绝对路径复验通过，两轮均留证。

结果及两 APK hash 见 `build/main-verification/proot-assets-audit-result.json`，文件映射和上游对应关系在 `proot-assets-audit/manifest.json` 与 `upstream-binary-parity.json`。这是锁、打包资产、许可证元数据/材料一致性及 ELF 字节检查，不作为法律分发结论或设备运行验收。当前主机侧既定门禁已获得记录，设备矩阵、统一固定评测、剩余功能及交互优化继续；持续 Goal active，ADR-0028 proposed、长稳后置保持。

## HXA-102 收口期间：当前 APK 的 API 34 模块与 CLI 恢复复验

2026-09-07，专用 Helix_Verification_Root_API34（arm64-v8a）执行当前构建：CLI client 模块 **16/16**、storage 模块 **47/47**，均无跳过或失败。覆盖 Binder/PFD/请求管道、绑定释放，以及 Room 迁移、约束、删除、审批凭据与配置存储测试。构建命令为 `./gradlew :runtime:cli-client:assembleDebugAndroidTest :core:storage:assembleDebugAndroidTest --no-daemon --max-workers=2`（JDK 17），通过。

再执行 `./gradlew :app:assembleDeveloperDebugAndroidTest :app:assembleDeveloperDebug :runtime:cli-app:assembleDebug --no-daemon --max-workers=2`，通过；安装当前 Runtime、developer 主包和测试包，逐包核对设备 base.apk SHA256 与构建文件一致。选定 CLI 跨 UID PFD/取消/Runtime 死亡恢复/客户端中断等待 **9/9**，无跳过或失败；四平台 RUNNING 用例各覆盖 cancel 与实际 Runtime 进程退出，按同一 jobId 查询及 reconcile，额外 observer 绑定下中断等待仍落为 CANCELLED。

完整命令、安装日志、五个 APK 的设备/主机 hash、测试输出及计数见 `build/main-verification/current-api34-modules/`（`result.json`、`recovery-apks.json`、`recovery-command.json`、`recovery-result.json`）。本组是模拟器和 debug model fixture 证据，不是四平台真实账号模型调用，不替代主 App 执行中死亡、API 29/36 矩阵、统一固定评测或长稳。持续 Goal 保持 active。

## HXA-102 收口期间：API 29/36 当前 APK 模块矩阵

2026-09-07，新建独立 `Helix_Main_Verify_API29`、`Helix_Main_Verify_API36` arm64 AVD，安装当前五个 APK 并验证设备 base.apk SHA256；两台安装内容完全一致。每台通过 **77/77**、无跳过：显式 Runtime 界面入口 1、CLI client 16、storage 47、CLI 跨 UID 恢复 9、四平台 Provider fixture 契约 4。记录含设备 fingerprint、命令、逐用例状态和 APK hash：`build/main-verification/current-api29-modules-ready/result.json` 与 `current-api36-modules-ready/result.json`。

首轮 API 29 在刚安装、尚未显式打开 Runtime 时，CLI client 16 项中 6 个注入绑定测试失败，实际为 `FORCE_STOPPED`；本地状态检查先于注入的 Binder 分支，符合生产冷启动约束。保留原始 `current-api29-modules/cli-client.log`、`result.json` 和 `current-module-matrix.log`。经 developer 的显式账户界面入口打开 Runtime 后复验全部通过；没有删除测试、修改生产门禁或调用登录/付费服务。绑定注入测试要求 Runtime 已安装且经显式打开，不能把首轮失败称为 API 29 Binder 平台缺陷。

Provider 契约使用 debug `helix-fixture`，覆盖 probe/普通模型流/usage/完成及聊天落库；跨 UID 恢复使用 fixture wait，覆盖取消、Runtime 进程退出和按原 jobId 查询、不重放。以上不等于供应商真实网络取消、主 App 执行中死亡或完整 App 设备矩阵通过。统一 45 项模型评测、其余后端中断与功能/UI 优化继续，长稳保持后置。

## HXA-102 收口期间：Goal API 29/36 两发行包回归与固定评测启动

2026-09-07，当前 consumer/developer 各在 API 29/36 执行 12 类、44 项 Goal 测试。首轮四组均有通知点击后可见 Goal 断言失败；API 29 developer 另有一次 Worker 启动超时。截图确认新装 AVD 的首次使用须知遮挡了 Goal 界面，修正已有用户提醒测试的首启前置条件后，四组均 **44/44，总计 176/176**，无跳过。没有修改生产通知逻辑或移除可见性/预算/不自动 Continue 断言。Worker 超时在整组重跑中未复现，原因未确定，不宣称修复其根因。详见 [缺陷记录](../bug-fixes/2026-09-07-goal-reminder-first-launch-fixture.md)。

原始失败在 `build/main-verification/current-goal-api*/`，修复后命令、安装包 hash 和逐用例结果在 `current-goal-fixed-api*/`；构建和 spotlessCheck 通过。本组含真实 loopback socket 的取消，不含分阶段宿主 kill 测试，不等于完整 App 验收。

同一 API 34 安装包的统一 45 项 SGLang 固定评测已启动，证据根目录 `build/main-verification/current-fixed45-api34/`。Provider 9、文件 4、JavaScript 4、Plan 3、浏览器 4、Skills 4 已通过（28 项）。MCP 四份设备记录写 PASS，但宿主记录 `mcp-004` 服务执行次数为 0，未满足必须在远端执行中取消的验收，**MCP 组保持 FAIL**；不按设备记录冒充通过。其他组仍运行，最终计数须按各组最终结果及宿主条件核实。评测期间未替换 API 34 的安装包；新增首启修复仅在 API 29/36 测试包上验证。

## HXA-102 收口期间：MCP 固定评测取消边界修正

首轮统一固定评测结束：同一安装包的 11 组共 45 项，41 项位于通过组，MCP 组保持 FAIL；完整 hash 一致性及组结果在 `build/main-verification/current-fixed45-api34/aggregate-result.json`。

MCP 取消不再按本地 RUNNING 后固定两秒触发，而是由本地 fixture 的执行开始探针确认真实 tools/call 已进入服务端后停止。新测试包 API 34 MCP **4/4**，宿主执行计数 **0/1/1/1**；原门禁未放宽。构建、Spotless、Detekt 和 Python 2/2 通过，详见 [缺陷记录](../bug-fixes/2026-09-07-mcp-eval-cancel-before-remote-start.md)。

新一轮统一评测根目录 `build/main-verification/fixed45-final-api34/`，MCP 已通过，其余 41 项正在同一新测试 APK 上重跑。不得将旧 41 项与新 MCP 4 项拼接为统一通过。未改生产模型/IPC/审批代码；持续 Goal active。

## HXA-102 收口期间：扩展核心矩阵及 Provider 测试修正

2026-09-07，16 类/87 项聊天、附件、审批、工具调度、迁移恢复、Provider UI、前台服务与导入导出测试：API 29/36 consumer 各 **87/87**；developer 各 **83/87**，均在四项 Provider discovery 的准备阶段遇到 `managed provider cannot be deleted`。原始结果在 `build/main-verification/current-core-api*/`。

清理助手仅删除可编辑配置，保留 M11 Runtime 管理行；Provider discovery/flow 的控件和状态选择限定到目标可编辑行，并滚动到目标后操作。中间的屏外控件/同名状态标签失败亦保留。最终 API 29/36 × consumer/developer 各 **5/5，总计 20/20**，无跳过；构建、Spotless、Detekt 通过。证据 `current-provider-ui-final-api*/`，详见 [缺陷记录](../bug-fixes/2026-09-07-provider-ui-managed-row-fixtures.md)。不将针对性通过伪写成新 APK 的完整 87 项重跑。

API 34 独立模块：files **38/38**，QuickJS **74/75**；`JsAbiAttackTest.deepNestingRoundTrip` 在主机 JSON 结构校验的递归路径抛 StackOverflowError，已列入实际待修复项，原深度上限/测试保持。证据 `build/main-verification/current-api34-extra-modules/`。另外八个模块测试 APK 构建通过仅作为构建结果，未执行的用例不计通过。

第二轮统一固定评测结束：相同安装 APK 的其他十组 **42 项通过**；Accessibility 首项 ui.click 返回 STALE_TOKEN，组内只保存 1/3 记录，后两项未执行。本轮仍非 45/45。结果与相同 APK hash 核验在 `build/main-verification/fixed45-final-api34/aggregate-result.json`；保留首轮 FAIL 与本轮 FAIL，不拼接旧 Accessibility 结果。下一步优先修复 QuickJS 实际栈溢出，并继续定位 Accessibility 时序及余下设备矩阵。持续 Goal active。

## HXA-102 收口期间：QuickJS JSON 栈溢出修复

将共享 JSON 结构校验由递归下降改为显式容器栈，保留 512 层上限和原有 UTF-8/语法/完整文档约束。原 API 34 `deepNestingRoundTrip` StackOverflowError 的 300 层实际 QuickJS 往返测试保持不变。修复后 JVM **85/85**，API 29/34/36 各 **75/75，总计 225/225**，无跳过；Debug/Release Lint、Spotless、Detekt 通过。详见 [缺陷记录](../bug-fixes/2026-09-07-quickjs-json-validator-stack-overflow.md)。

两发行包 Debug/Release 四 APK 重建通过，hash 见 `build/main-verification/json-stack-app-apks.json`；本轮只安装 QuickJS 模块测试 APK，未将新主 App 构建声称为已安装验收。JVM、模块设备 hash 与逐例输出在 `json-stack-jvm-result.json`、`json-stack-device/`。原 74/75 FAIL 与第一次静态失败均保留。主仓全量结果与固定评测仍按旧快照解释，后续须验证新构建；Accessibility stale token、其余设备/恢复/UI 范围继续，Goal active。

## HXA-102 收口期间：当前快照评测与扩展设备结果

Accessibility 评测改为模型真实 ui.snapshot 后选择 token、申请精确点击；不改生产失效机制，并移除 setup token 的测试审批捷径。API 34 三项真实 SGLang 场景 **3/3**：正常 snapshot→click、切换应用保持暂停、敏感 UI 拒绝。已安装含 QuickJS 修复的当前 developer 主包/测试包，详见 [缺陷记录](../bug-fixes/2026-09-07-accessibility-eval-current-snapshot.md)。

使用当前构建补跑核心 16 类：API29/36 × consumer/developer 四组各 **87/87，总计348/348**，无跳过；此前 developer Provider 四项准备失败现已在整组复验中通过。精确命令及各 APK hash 在 `build/main-verification/current-core-fixed-api*/`。API29 另通过浏览器 **27/27**（明确只选两项短时资源测试，未选长稳/原生对照长循环）、文件 **38/38**、Android 工具 **30/30**；证据 `current-api29-basic-modules/result.json`。

第三轮统一评测在 `build/main-verification/fixed45-current-snapshot-api34/` 继续。浏览器组在 real Provider probe 的 phase4 失败：`tool fixture did not complete a tool call (finishReason=stop)`；尚未开始四个 browser case，因此 count0、该组FAIL。这不是浏览器四项已执行后失败，也不得拿旧轮次四项通过补齐。其余组继续采集，整体45项仍未完成；后续检查模型连接探测失败的具体请求与验收语义。Goal active，长稳仍后置。

## HXA-102 收口期间：同一当前 APK 的固定45项结果

2026-09-07，`fixed45-current-snapshot-api34` 全部11组完成。browser原轮在Provider phase4返回stop而未形成工具调用，探测按既有规则失败，尚未执行browser用例。未修改代码、请求、模型配置或探测门禁，完成一次同配置复验后browser **4/4**。这只说明此次复验通过，不声明已找到或修复模型输出波动的代码根因。

选择本轮同一主包/测试包的结果（browser使用明确标记的本轮复验），对照不可变TSV逐ID核对：**45/45**，无缺项/重复；11组配置中的两个安装APK hash完全相同，并与当前构建文件逐字节hash一致。MCP/A2A宿主执行计数门禁均通过。证据 `build/main-verification/fixed45-current-snapshot-api34/aggregate-result.json`，其中 `allPassed=true`、`firstAttemptAllPassed=false`、`retryCount=1`；原browser FAIL路径与每个case文件均列出。不是使用此前不同APK的结果拼接，也不是首轮全过。

模型为真实本地SGLang；MCP/A2A和Automation目标仍是合成fixture，Root是rooted AVD，不扩大为物理设备或受保护账号验收。API36浏览器短时27/27、文件38/38、Android工具30/30亦通过（`current-api36-basic-modules/result.json`）。PRoot模块两API测试正在执行，宿主分阶段生命周期、其他设备和剩余功能/UI仍待完成；持续Goal active。

PRoot模块后续结果：API29、API36各 **56/56**，无跳过或失败；包括真实内置RootFS安装、执行、隔离、取消与通知模块用例。API36测试前显式授予专用Runtime通知权限；宿主专用 `ProotLifecycleHostSetupDeviceTest` 不在本组选择中，仍须随分阶段宿主脚本另验。两台Runtime与测试APK安装hash均核对，命令/逐例日志见 `build/main-verification/current-proot-api29/`、`current-proot-api36/`。这是模块模拟器验收，不冒充完整主App跨进程生命周期矩阵或真机。


## 2026-09-07：PRoot宿主前置修正与当前主机复验

HXA-083脚本在API29/36各八阶段通过。修正HXA-086脚本的前台 `am kill` 假前置：先退到后台，再确认主App PID消失；两API完整脚本均通过，包含执行中主App/companion死亡与job ID对账、通知停止、短时wake-lock采样和跨UID隔离。原前台kill返回0但PID不变的证据保留。独立forced idle脚本在两API各1/1通过，作业前后确认deep IDLE/force=true并恢复原配置。证据 `build/main-verification/proot-host-lifecycle/`；见 [修复记录](../bug-fixes/2026-09-07-proot-lifecycle-host-preconditions.md)。受控后台kill不是实际低内存压力，forced idle不是自然Doze/后台调度或真机/长稳验收。

QuickJS修复后的全仓 `test lintDebug spotlessCheck detekt` 使用禁用Test缓存/up-to-date的init脚本执行成功：34个有XML的实际Test任务共2,602/2,602，零failure/error/skip；PRoot Runtime一个NO-SOURCE、18个聚合test任务另列，根Debug Lint覆盖18模块。当前源码/配置1,057项hash与独立XML保存于 `post-json-full-host-source.json`、`post-json-full-host-xml/`，汇总为 `post-json-full-host-result.json`。全量Goal仍在推进，其他设备/功能/统一交互待办不由本项替代。


## 2026-09-07：Accessibility夹具清理与运行中强停

额外模块在API29/36各22/22通过：Root未授权5、Accessibility完整服务1、CLI Runtime本地契约16，零跳过，证据 `current-remaining-local-modules/`。后续宿主矩阵发现两处真实证据缺口并修复：空组件列表的settings命令缺少值，返回Bad arguments且残留component；同步setup结束后instrumentation已先杀掉服务，宿主force-stop晚于进程退出。前者改为删除空设置并读回验证，后者改为setup保持运行、发布ready，宿主验证live PID与通知后再强停。

修正后的两API均通过完整服务1/1、确认清理、运行中宿主强停及恢复1/1；setup被有意中断，不计为JUnit通过。旧失败和旧非运行中结果保留，当前证据 `build/main-verification/current-automation-live-force-stop/`；Spotless/Detekt、模块Debug Lint/测试APK构建通过。仅androidTest与测试宿主脚本改变，生产授权/服务行为不变。见 [清理修复](../bug-fixes/2026-09-07-accessibility-test-empty-settings-cleanup.md)、[强停顺序修复](../bug-fixes/2026-09-07-accessibility-host-kill-after-instrumentation-exit.md)。额外19类App回归（每发行包59项）已启动，尚未计为通过。


## 2026-09-07：其余App回归236项通过

20个实际测试类覆盖文件UI/导入导出、审批卡片、运行控制、系统能力、语音、图像、诊断、A2A与Connector等。API29/36 × consumer/developer各59/59，共236/236，零跳过，设备安装hash与主机APK一致，证据 `build/main-verification/current-additional-app-fixed-result.json` 及各组原始日志。首次选择器误用 `RunControlUiDeviceTest.kt` 文件名作为类名（实际是Mode与Settings两个类），四组各57项通过加1个初始化失败；原失败保留，修正选择器后完整复验，没有因此改生产代码。

CLI匿名端点初轮API36为4/4，API29为3/4；后者Grok连接失败，设备解析地址与宿主不一致。Wi-Fi重连未解决；空闲专用API29模拟器使用已实测的显式DNS参数重启后，解析已与宿主一致，匿名端点复验4/4通过（`current-cli-anonymous-endpoints-api29-dns-fixed/`）。原失败/只读DNS诊断与重启参数日志位于 `current-cli-anonymous-endpoints/`，不拼成同条件首轮全过。以上匿名检查不是账号登录/付费模型验收，凭据配置不变。


发行包入口差异追加复验：API29/36 × consumer/developer各2/2，总8/8、零跳过；consumer固定Standard/egress缺席与developer切换/egress流程通过。证据 `current-flavor-ui-result.json`。Accessibility宿主测试后的两设备设置另读回为enabled列表null、accessibility_enabled=0，见 `current-automation-live-force-stop/settings-post-check.json`。当前持续Goal仍active，Goal完成证据契约/剩余后端恢复与M11集成、统一交互优化继续；长稳仍后置。


## 2026-09-07：后端与语言/能力剩余矩阵

API29/36每台PRoot主App17/17、M11/MCP35/35，无跳过；前者覆盖真实跨APK作业、stdio取消/输出限额、锁基线变更及法律/移除入口，后者覆盖四订阅适配器合成模型契约、PFD对账、取消/Runtime死亡重绑与MCP Android流式/认证/限额等。证据 `current-proot-main-remaining/` 与 `current-m11-mcp-main/`，APK设备/主机hash一致；不扩写为真实账号或分发法律验收。

语言与all-files条件矩阵共38通过、4个明确assumption、零失败：API29两发行包各缺API33系统语言双向同步1项；API29 all-files实际授权不存在、API36拒绝授权组各有1项happy-path前提不满足。API36授权组6/6，原AppOp已恢复default。证据 `current-language-allfiles/`，不把4项assumption算成通过。

开始补Goal真实模型UI路径：从已有会话在界面创建Goal、第一次显式Continue至预算暂停、界面扩预算且不自动运行、第二次显式Continue，核对真实回复/持久Goal-run-Turn关联和计数。新用例构建中，尚无设备通过结论；完成判据仍受ADR-0028待决边界约束。


## 2026-09-07：真实Goal UI预算与显式Continue验收

新增共同Android用例 `GoalRealModelUiDeviceTest`，provider配置/连接测试与已有会话是前置fixture，Goal创建/预算修改/Continue全部通过实际界面点击。SGLang真实模型经OpenAI Chat Completions执行：API29/36 × consumer/developer共4/4、零跳过，累计8次Goal模型调用（连接探测另计）；每组两次回复、两个持久run、Goal-run-Turn绑定、预算计数与PAUSED/BUDGET_EXHAUSTED一致。创建与扩预算不自动运行，第二次显式Continue后仍不冒充Goal完成。清理仅针对自建Goal/session/provider。构建双测试APK、Spotless、Detekt通过；初次枚举名编译错误已修正，日志保留。

证据 `build/main-verification/goal-real-ui-current-matrix/verified-summary.json`、各组实际命令/安装hash/JSON与对话及预算截图。另有API29 consumer初次无截图1/1记录，未拼入当前四组结果。当前修改仅增加测试，生产代码不变。

视觉复核发现：长Provider/模型标识使会话头过高，第二条回复未自动跟随到完整可见位置；Compose assertIsDisplayed接受部分可见，不能将此次流程通过扩写为视觉验收。该项已加入统一交互阶段，要求兼顾自动跟随与用户上翻阅读。Goal完成条件绑定仍待ADR-0028决定，剩余各副作用后端中断/恢复矩阵继续。


## 2026-09-07：API 29/36 SIGKILL 信号权限修复与恢复扩展

API 29 首轮在发送信号时失败：`run-as kill` 报 `unknown pid`，但 PID 仍存在；shell builtin 与 SELinux AVC 进一步证明 runas_app → untrusted_app 的 SIGKILL 被拒绝。原始失败、失败 fixture 的显式 abort 清理与系统证据保留在 `build/main-verification/model-kill-api29-36/`。abort 只恢复自建 fixture 状态，不计恢复验收。

三个宿主脚本改用共享 `android_process_control.py`：只接受显式模拟器 serial，先验证已有 host su 0 权限，核对应用包/PID 后发送 SIGKILL，再核实 PID 消失。结果明确记录信号权限；不改 SELinux，不扩大应用 Root 权限。详见 [缺陷记录](../bug-fixes/2026-09-07-emulator-sigkill-selinux-authority.md)。

修复后 consumer API 29/36 × Chat/Responses/Anthropic × headers/body 共 **12/12**，实际 12 次 SIGKILL、24 次启动恢复断言，零失败/跳过。每组有真实 held HTTP、socket EOF、调用数 1、预算幂等、启动后请求计数不增长；使用宿主脚本模型。安装主 APK 与测试 APK 的 hash 均比对当前产物。证据 `build/main-verification/model-kill-api29-36-host-signal-fixed/verified-summary.json`。

同两台设备的 Goal 预算预留与删除强杀回归 **4/4**：每组两个真实强杀边界，累计 8 次 SIGKILL，各 control/final 断言通过；证据 `build/main-verification/goal-kill-api29-36-host-signal-fixed/`。这两类仍分别是预算窗口、生产删除协调器/Room/WorkManager fixture，不能替代工具执行中断。consumer 测试 APK 构建、Spotless、Detekt、四个 Python 脚本语法检查通过。实际 MCP/A2A/其他副作用后端 SIGKILL 与统一 UI 优化继续，整体 HXA-102 和开发 Goal 尚未完成。


## 2026-09-07：MCP 执行中真实主进程 SIGKILL

新增 developer `McpProcessKillDeviceTest` 与 `scripts/run-mcp-process-kill.py`。通过生产 Goal/Chat/Dispatcher、精确工具审批、MCP SDK 与 HTTP 发送合成远端请求；宿主同时观察到 tools/call 开始、端上 RUNNING 和 ready PID 后执行 SIGKILL。API 29/36 各一组通过，共两次实际强杀、四次启动恢复；真实 socket EOF、工具调用总数 1、模型请求计数不变、原审批已消费、Turn/ToolCall/run 为 INTERRUPTED、审计 uncertainToolCall 精确指向原调用、Goal 预算不返还。安装主 APK/测试 APK hash 已比对。证据 `build/main-verification/mcp-live-kill-api29-unique-call/result.json` 与 `mcp-live-kill-api36/result.json`。脚本模型与合成 MCP 服务，不替代真实外部服务、显式后续 Continue 的产品流程或其他后端中断矩阵。

首轮 fixture 错把 SDK 的合法 `_meta` 附加字段当成非法参数，修正为精确校验工具名及业务参数；原失败保留在 `mcp-live-kill-api29/`，显式 abort 仅清理自建数据，不计恢复验收。第二轮暴露独立产品缺陷：重复远端工具调用 ID `fixture-call` 跨 Turn 导致 `tool_calls.id` 主键冲突，Turn 以 INTERNAL 失败；`mcp-live-kill-api29-fixed/system.log` 和专用模拟器数据库快照保留证据。后续强杀 fixture 使用唯一调用 ID 以隔离这一问题，**产品重复 ID 问题尚未修复**，下一检查项必须处理本地持久标识与 Provider 调用关联，不能仅改 fixture 宣称修复。

当前新 Android 用例与宿主脚本已通过测试 APK 构建、Spotless、Detekt、Python 语法检查；生产代码本轮未改。HXA-102 与完整优化 Goal 保持进行中。


## 2026-09-07：Provider 工具 ID 与本地执行标识分离

已修复上一轮发现的跨 Turn 重复 Provider ID 主键冲突。每个工具轮次分配新的本地调用 ID，执行/审批/预算/结果/恢复使用本地 ID；assistant 历史同时持久原 `id` 与 `localId` 映射，模型结果回填保留原协议 ID。无需迁移旧数据，原有单响应内重复 ID 校验保留。详见 [缺陷记录](../bug-fixes/2026-09-07-provider-tool-id-local-identity.md)。

当前 developer 安装 APK hash 核实后，API 29 保留旧 `fixture-call` 并连续两次复用该 ID，API 36 再一组，共三组真实 MCP 执行/强杀通过、六次启动恢复不重放。证据 `build/main-verification/mcp-duplicate-id-api29-first/`、`mcp-duplicate-id-api29-second/`、`mcp-duplicate-id-api36/`。独立审批已消费与 uncertainToolCall 审计仍通过。当前 app consumer/developer JVM 292/304 通过，各三项 Connector 外部样本/服务 assumption；596 通过、6 跳过、零失败，不混入旧全仓 2602 结论。developer 构建、lint、Spotless、Detekt通过。

这次修改了生产 ChatService，旧全仓/固定45等证据不自动升级为当前快照。接下来补普通完成/模型回填、拒绝、同一 Turn 多轮复用 ID 的设备覆盖，再按影响刷新全量门禁。整体 Goal 与 HXA-102 尚未完成。


## 2026-09-07：本地工具 ID 修复后的普通完成/拒绝/回填与全仓门禁

共同 Android E2E 新增同一 Turn 两轮复用 `same-wire-id`：time.now 实际完成和未注册合法工具被 UNKNOWN_TOOL 拒绝两类。每轮有独立本地 ToolCall/result，发送给模型的两条 TOOL 消息都保留原协议 ID，并且本地 ID 不泄露到请求体。连同既有单轮工具完成、长 payload 超越 timeline 预览的回填用例，API29/36 × consumer/developer **16/16**，零跳过。证据 `build/main-verification/repeated-tool-round-device-final/result.json` 与四组日志/安装 APK hash。使用 scripted SSE wire 和生产 Provider adapter/ChatService/工具管线/Room，不属于真实模型或真实 HTTP 服务验收。

测试开发阶段三个不通过轮次分别保留：非法工具名在 decoder 终止；非对象参数在严格历史校验终止；合法未知工具的状态原断言误写 FAILED、实际为 DENIED。对应 `repeated-tool-round-device/`、`repeated-tool-round-device-fixed/`、`repeated-tool-round-device-valid-rejection/`。最终用例核实的是合法可回填拒绝，没有放宽生产解析或删除历史失败。中间参数签名编译失败日志也保留，最终双测试 APK、Spotless、Detekt通过。

生产代码 ID 分离后的全仓 JVM 使用 force-tests.gradle 强制实际重跑、禁用测试缓存，并提供已有 Connector 外部样本配置：**2610/2610、零失败/跳过，34 个产生 XML 的 Test task**。根 lintDebug、Spotless、Detekt通过，日志 `local-tool-id-full-host.log`，逐任务计数 `local-tool-id-full-host-result.json`，原 XML 已归档 `local-tool-id-full-host-xml/`。聚合 test 与 NO-SOURCE 不重复计数。此后仅 Android 测试断言调整，生产/JVM 源码未变。固定45与其他受影响设备快照、剩余副作用后端 SIGKILL 和最终交互优化仍继续，不将全仓 JVM 通过扩写为整体收口。


## 2026-09-07：ID 分离后真实模型 MCP/A2A 刷新

API34 当前 developer 主/测试 APK 重装后，SGLang 经现有30018转发端口完成 MCP 4/4 与 A2A 4/4，固定数据集 hash 不变；两组各 Plan 0 次远端执行，其余三项各1次，未出现重复发送。MCP/A2A 的外部服务仍是固定合成 fixture，模型推理与生产 Provider/审批/工具链路是真实执行。证据 `build/main-verification/local-id-real-mcp-api34/` 与 `local-id-real-a2a-api34/`；各 config 记录安装 APK hash，结果不扩写为全部固定45或真实第三方服务验收。


## 2026-09-07：核心预算测试关联修正与 Goal/附件组合刷新

ID 分离后的首轮核心矩阵 API29/36 × 双发行包，每组88通过、1失败；失败均是 Goal 工具预算测试仍用远端 one/two 查询本地主键。已将测试改为读取持久 TOOL_CALLS 的 id→localId 映射，继续断言获准项 COMPLETED、超预算项 FAILED、两个结果均持久化且只计一次工具预算。没有更改生产行为或放宽预算断言。原失败证据保留在 `build/main-verification/local-id-core-api*/`，不改写成首轮全绿。

修正后对完整 AttachmentE2e 26项 + Goal组件/UI 44项组合重跑，API29/36 × consumer/developer每组70/70，共**280/280，零跳过**。证据 `local-id-goal-attachment-summary.json`、`local-id-goal-attachment-api*/`；每组核实实际安装APK hash。构建双测试APK、Spotless、Detekt与文档检查通过。其他核心类的首轮通过证据与此组合结果分开报告，不拼成同一APK首轮356/356。

真实模型固定评测追加Goal3/3、Files4/4，连同本轮MCP4/4与A2A4/4，共**15/45**已刷新到ID分离后的生产快照；`local-id-real-subset-summary.json`明确full45Complete=false。真实SGLang推理、文件生产路径及精确审批/回填通过；MCP/A2A远端仍为合成fixture。剩余30项与其他后端强杀恢复继续，完整Goal与HXA-102尚未完成。


## 2026-09-07：当前生产快照固定45与 A2A Task 查询中 SIGKILL

Provider9、Plan3、JavaScript4、Browser4、Skill4、Accessibility3、Root3全部首轮通过，连同此前Files4/MCP4/A2A4/Goal3，核实11组相同安装主/测试APK hash和固定数据集hash后，**45/45、firstAttemptAllPassed=true、retryCount=0**。证据 `build/main-verification/local-id-fixed45-summary.json` 与 `local-id-remaining-evals/`。这是ID分离后的生产快照；新A2A强杀用例单独构建测试APK，不混入该45的安装hash。Root是合成系统授权/时钟负向边界，MCP/A2A是合成远端服务，真实SGLang推理与生产工具路径通过，不扩写为物理Root/付费账号/长稳或真实第三方服务验收。API34 Accessibility fixture清理后系统服务列表为null。

新增 `A2aProcessKillDeviceTest` 与 `scripts/run-a2a-process-kill.py`：生产Goal/Chat/精确审批/A2A HTTP已经发送一次并持久保存 `task-kill`，宿主在实际GetTask保持连接、端上RUNNING和ready PID后SIGKILL。API29/36两组通过，两次强杀、四次启动恢复；调用/Turn/run与uncertainToolCall审计保留，预算不返还，审批已消费，startup不发送也不查询。随后测试模拟显式对账动作，仍以原task-kill执行第二次GetTask至COMPLETED，SendMessage总数保持1。各组核实安装APKhash，证据 `build/main-verification/a2a-live-kill-current/verified-summary.json`。仅覆盖已知Task ID、查询执行中断边界，不声称覆盖发送响应前未知Task ID或全部A2A阶段。

初次新fixture使用10.0.2.2明文Card，被生产HTTPS约束正常拒绝；改为现有A2A测试采用的loopback + adb reverse，结束后删除自建reverse。显式abort清理又发现注册失败后删除不存在Agent会抛错，已按实际自建记录是否存在清理。原失败及abort记录保留 `a2a-process-kill-api29/`，首次loopback成功记录单列 `a2a-process-kill-api29-loopback/`；最终双设备结果使用补强后的“不自动GetTask”断言。没有放宽生产网络规则；abort不计恢复验收。最终测试APK构建、Spotless、Detekt与Python语法检查通过。

本轮未改生产代码。剩余各后端中断阶段、Goal完成证据决定与统一交互优化继续，HXA-102和完整开发Goal尚未完成。


## 2026-09-07：A2A 发送响应前未知 Task ID 强杀边界

`A2aProcessKillDeviceTest`/宿主runner新增send边界：远端已经收到一次SendMessage、响应头/Task ID尚未返回，端上持久调用RUNNING及A2A记录存在但taskId为空时，宿主观察真实发送开始后SIGKILL。API29/36各通过：socket EOF、两次启动恢复保留INTERRUPTED与uncertainToolCall、审批已消费、Goal预算不返还，远端SendMessage总数1、GetTask总数0。显式reconcileTask抛稳定A2aNeedsReviewException，不能生成Task ID或改成重发。

同一主/测试APK再回归已知Task ID的poll边界两组，显式原ID对账COMPLETED，SendMessage1/GetTask2。合计4组真实强杀、8次启动恢复，安装hash一致；证据 `build/main-verification/a2a-process-boundaries-summary.json`，send原始日志 `a2a-unknown-id-current/`，poll `a2a-known-id-refreshed/`。send结果中的reconciledTaskId=null；初版通用scope文案的same-task reconciliation表示显式对账尝试，并非成功获取结果，宿主文案已改为reconciliation boundary，新输出另列REVIEW_REQUIRED/COMPLETED。

本轮只扩展测试，无生产代码变更；构建、Spotless、Detekt、Python语法和文档检查通过。A2A这两个实际进程边界完成不等于所有远端服务或取消阶段全验收；继续其他执行后端的主进程中断与恢复。真机和长稳仍后置。


## 2026-09-07：QuickJS 执行中主进程 SIGKILL

新增 developer `JavascriptProcessKillDeviceTest` 与 `scripts/run-javascript-process-kill.py`。脚本模型通过生产Goal/Chat/Dispatcher请求受原有时间上限约束的合成无限循环；仅匹配该工具与代码的精确审批被确认，端上RUNNING后发ready。宿主找到本应用独立helix_js进程的helix-js-execution线程，采样0.5秒并要求CPU tick至少增加5，再SIGKILL主App PID；随后要求隔离worker在15秒观察窗口内消失。

API29/36同一主/测试APK各通过，两个真实强杀、四次启动恢复：原ToolCall/Turn/run为INTERRUPTED，恢复审计uncertainToolCall指向原调用，审批已消费，预算不返还，模型请求计数不增长，未再创建worker。证据 `build/main-verification/javascript-live-kill-summary.json`、`javascript-live-kill-api29/`、`javascript-live-kill-api36/`；记录实际main/worker/thread PID与前后CPU计数。模型为scripted HTTP fixture，QuickJS执行/隔离Service/Room恢复为真实生产路径。宿主管理权限只用来读取进程事实和发送信号，不等于应用Root能力或自然LMK/真机验收。

这次验证的是主App死亡导致实际执行worker退出及持久恢复，没有修改QuickJS运行时、取消机制或时间上限，没有用Runtime自身注入崩溃替代主进程强杀。本轮仅新增测试/runner；测试APK构建、Spotless、Detekt、Python语法与diff检查通过。审批等待、PRoot/CLI等剩余阶段继续，完整HXA-102/优化Goal尚未完成。


## 2026-09-07：审批等待中主进程强杀

在JavascriptProcessKillDeviceTest/宿主runner增加approval边界，真实模型调用使用scripted HTTP fixture，请求实际code.javascript.run工具，持久AWAITING_APPROVAL后保持等待，不执行approve。宿主确认没有本应用QuickJS worker后SIGKILL主进程。API29/36各通过，两次启动均保留AWAITING_APPROVAL，审批decision/consumedAt为空，没有工具结果，恢复审计uncertainToolCall=null（尚未执行，不伪称副作用不确定）；Turn/run INTERRUPTED、Goal PAUSED，预算不返还，无模型重发或worker创建。

同一主/测试APK复核execution边界各一组，通过真实worker执行线程CPU活动后强杀、worker退出与两次恢复不重放。总计4组强杀、8次启动恢复，只有2组包含实际JavaScript执行；approval组的worker=null，原runner的workerExited=true字段仅代表杀后worker不存在，不能计为存在过worker或执行过脚本。证据 `build/main-verification/js-approval-boundaries/verified-summary.json` 与各边界日志/安装hash。

本轮未修改生产代码，构建测试APK、Spotless、Detekt、Python语法检查通过。等待审批恢复的用户后续操作、其他工具实现和PRoot/CLI等边界继续按具体证据验证，不扩写成所有审批/工具流程验收。完整HXA-102与开发Goal仍进行中。


## 2026-09-07：M11 CLI Runtime 客户端主进程死亡

新增developer `CliOwnerProcessKillDeviceTest` 与 `scripts/run-cli-owner-process-kill.py`。使用生产CliModelJobClient/CliRuntimeSupervisor和独立Runtime UID，但模型是DEBUG的helix-fixture-wait（不读取账号、不执行付费调用）。Job持久RUNNING、客户端等待尚未结束后输出ready；宿主记录Runtime PID，只SIGKILL主App PID。新启动只query/cancel/reconcile原Job ID，核对requestSha256与createdAt不变、终态无事件载荷、重复查询/对账不变化。

API29/36 × CODEX/CLAUDE/GROK/COPILOT共**8/8**，八次真实主进程SIGKILL、十六次恢复检查，全部以原Job取消后CANCELLED稳定结束。三APK（主App/test/CLI Runtime）安装hash一致且比对当前产物。证据 `build/main-verification/cli-owner-kill-matrix/verified-summary.json`。没有对Runtime发kill，没有清理真实账号或重置应用数据。marker仅属于该用例，第二次恢复后移除；Runtime原Job终态记录保留作为不重放证据。

这证明主客户端死亡后的原Job身份和取消/对账边界，不能替代实际订阅模型/账号、ChatService/Goal绑定或所有CLI生命周期验收；RUNNING是持久作业状态，debug wait不是真实远端模型流。全Goal的生产绑定和模型HTTP流恢复证据另列，不拼成端到端订阅Provider强杀结论。测试APK构建、Spotless、Detekt与Python语法检查通过，生产代码未变。PRoot及其他剩余阶段继续。


## 2026-09-07：PRoot guest 执行中主客户端死亡

新增 `ProotOwnerProcessKillDeviceTest` 与 `scripts/run-proot-owner-process-kill.py`。生产跨 UID 客户端提交真实 guest shell，宿主读到 guest 写入的启动标记后仅 SIGKILL 主 App；两次恢复只查询、取消和对账原 Job，验证 executionId、输入 manifest SHA、创建时间与 terminal commit 不变，不重新提交。API 29/36 共 **2/2**、两次真实强杀、四次恢复检查，均稳定 CANCELLED；主 App、test、PRoot APK hash 在两台一致。仅移除本用例目录，Runtime 终态日志保留。

证据 `build/main-verification/proot-owner-kill-summary.json` 及两系统 `proot-owner-kill-api*/` 原始日志。API 29 原 result 的 scope 文案误带旧 CLI fixture 名称，原证据保留；实际测试类、guest 命令和启动标记均为 PRoot，聚合记录明确校正。测试 APK 构建、Spotless、Detekt通过（`proot-owner-kill-build.log`）。运行命令为 `python3 scripts/run-proot-owner-process-kill.py --adb <adb> --serial <emulator> --output <new-evidence-dir>`。

此结果证明实际 guest 启动后的直接 Runtime 客户端恢复，不替代完整 ChatService/Goal 的 bash 接线强杀，也不代表自然低内存回收或真机验收。后续仍验证生产绑定及文件写、浏览器、UI 动作等剩余阶段；开发 Goal 保持 active。


## 2026-09-07：等待审批恢复后的显式拒绝

在 `JavascriptProcessKillDeviceTest` 和宿主 runner 增加 `--boundary approval --after-recovery deny`：真实 Goal 工具请求持久等待审批后 SIGKILL 主 App；第一次重启验证原待决状态，第二次通过生产 `ChatService.denyApproval` 显式拒绝原 approvalId，第三次启动验证 DENIED 持久化。API 29/36 两组均通过，共两次强杀、六次恢复检查；审批 consumedAt 仍为空、没有工具结果或 QuickJS worker，原 Turn 保持 INTERRUPTED、Goal PAUSED、模型调用和 token 预算不变。宿主模型请求总数各4（含连接探测），三个恢复阶段均无新增请求。

证据 `build/main-verification/approval-recovery-denial-summary.json` 与两系统原始日志；两台安装主/测试 APK hash 一致。本轮只扩展测试，不修改生产行为；显式拒绝由服务入口调用，不冒充 UI 点击验收。原执行中强杀与等待审批不操作的证据仍分别保留。

验证：初次误用不存在的 `:app:spotlessApply` 导致任务选择失败，日志保留；改用仓库根任务后 `./gradlew spotlessApply :app:assembleDeveloperDebugAndroidTest detekt` 通过（14秒），Python语法和文档门禁通过。接受审批后的处理、完整后端接线及文件/浏览器/UI动作恢复继续，HXA-102与开发Goal尚未完成。


## 2026-09-07：中断后的审批时间线显示修复

发现恢复后的 Turn 已 INTERRUPTED，工具行仍显示“待审批”但没有 live 审批卡。现仅在该组合下显示既有本地化“已中断（待恢复）”，保留原调用/审批/预算事实，不创建批准或重新执行路径。根因与边界见 [缺陷记录](../bug-fixes/2026-09-07-recovered-approval-timeline.md)。

API29/36两组真实审批等待强杀、六次恢复阶段通过，并验证生产 screen state 的历史行文案和 card=null；不是UI点击/截图验收。正常审批3项×2系统全部通过。Consumer JVM295/295、developer307/307，零跳过；双debug构建、根lintDebug、Spotless、Detekt通过。证据 `build/main-verification/recovered-approval-label-*`、`recovered-approval-live-emulator-*.log`。本次生产APK已变化，旧全量/真实模型评测保持原快照边界，不能称为本APK全量验收。HXA-102和开发Goal继续。


## 2026-09-07：PRoot 提交后失败的副作用语义

修复生产 PRoot 执行器统一返回 sideEffectFree=true 的问题：已接受后的失败全部为false，结果未知的等待/提交响应为requiresReview；已知失败终态仍为普通失败。详见 [缺陷记录](../bug-fixes/2026-09-07-proot-failure-effect-semantics.md)。真实guest先写后exit3的用例及既有导入/拒绝等5项在API29/36共10/10通过；未知等待/提交分支本轮为源码核验，不伪称已注入设备故障。

Developer JVM强制运行307/307、零跳过（初次缺样本条件的3条跳过日志保留）；根lintDebug、Spotless、Detekt与developer构建通过。证据 `build/main-verification/proot-failure-semantics-*`。同时确认生产执行器缺少提交前ToolCall与Job ID的持久关联，成功后审计不能覆盖主进程中断；该项继续修复，不能以直接Runtime客户端测试替代。


## 2026-09-07：PRoot生产调用与Job提交前持久关联

已修复主进程死亡后无法从原ToolCall定位Job的问题：生产执行器在Binder提交前记录版本化的 `proot.job_prepared`，绑定原Turn/ToolCall、Job/执行ID和输入hash；记录失败不提交，同一调用不能静默覆盖绑定。记录不表示已接受或成功，也不包含命令正文。[缺陷记录](../bug-fixes/2026-09-07-proot-durable-job-binding.md)。

完整Goal/Chat/审批/PRoot真实guest链路在API29/36各一次强杀、四次恢复通过，原Job查询/取消/对账、绑定hash/审批消费/预算和不重放均验证；证据 `build/main-verification/proot-goal-binding-summary.json`。这次不再是独立客户端保存测试marker代替生产持久关联；显式对账由测试调用生产API，用户对账UI仍未交付。新增持久化失败的提交前阻断测试，两系统LinuxRunTool套件共12/12；后者测试APK更新，完整Goal日志保留其原测试APKhash，生产APK相同。

Developer JVM307/307零跳过、根lintDebug、Spotless、Detekt、developer构建通过。所有证据位于 `proot-binding-*` 和 `proot-goal-binding-*`。其他后端和剩余边界继续，开发Goal保持active。


## 2026-09-07：M11完整Goal恢复、会话模型与Runtime冷启动

补齐 [CLI提交前模型调用关联](../bug-fixes/2026-09-07-cli-durable-model-job-binding.md)：本地协程上下文传递Turn/modelCallId，提交前持久平台/Job/request hash，元数据不进入模型请求。修复 [会话模型未用于请求](../bug-fixes/2026-09-07-session-selected-model-request.md)：初始/回填请求优先使用已存会话选择。另发现并修复 [CLI冷启动四TLS客户端初始化OOM](../bug-fixes/2026-09-07-cli-runtime-lazy-oauth.md)：按实际平台惰性创建OAuth客户端与控制器，仅关闭已初始化实例，不改变TLS验证。

最终同三APK，API29/36 × CODEX/CLAUDE/GROK/COPILOT完整Goal/Chat/订阅adapter/Runtime DEBUG模型强杀 **8/8、16次恢复**，原Job请求hash可核验，Goal暂停、预算不返还，无新提交，显式原ID取消/对账全部CANCELLED。证据 `build/main-verification/cli-goal-binding-summary.json`。这是生产接线+无账号fixture，不计作真实订阅/付费调用。初始两轮失败、Runtime OOM栈、用例定向清理均保留；早先模型选择问题判断不能替代OOM的直接失败证据。

双变体JVM602/602、CLI Runtime102/102零跳过，根lintDebug、Spotless、Detekt与构建通过，见 `cli-binding-host-summary.json` 及host/build日志。Provider临时测试模型、模式/预算/profile已恢复，无Runtime凭据或数据重置。不同会话模型的工具回填专门回归、用户恢复入口、其他执行阶段和全量最终快照继续，Goal保持active。


## 2026-09-07：会话模型选择的多轮回填验收

新增 `selectedSessionModelIsUsedForInitialAndBackfillRequests` 与 `legacySessionWithoutModelUsesProviderDefaultForEveryRound`。各场景真实ChatService执行两轮time.now并回填，逐条检查三个序列化请求的model；前者全部使用不同于Provider默认值的会话模型，后者在modelId为空时全部使用Provider默认值。原工具身份隔离、结果持久化与重复wire ID回填断言仍保留。

完整AttachmentE2eDeviceTest在API29/36 × consumer/developer四组 **112/112、零失败/跳过**，含本轮新增8例、其余既有104例。证据 `build/main-verification/session-model-backfill-summary.json`、各组原始instrumentation日志及安装hash。执行 `adb -s <serial> shell am instrument -w -r -e class com.helix.app.chat.AttachmentE2eDeviceTest <package>.test/com.helix.app.HelixAndroidJUnitRunner`。双变体测试APK构建、Spotless、Detekt通过。本轮未再修改生产逻辑；[会话模型缺陷](../bug-fixes/2026-09-07-session-selected-model-request.md)的专门回填缺口已关闭，其余HXA-102和全Goal待办继续。


## 2026-09-07：PRoot恢复查询/停止按钮与中断会话状态

中断的PRoot工具行现在提供“查询原作业”；确认原Job仍运行后提供“停止原作业”。操作经ChatService与生产恢复模块，核对原Turn/ToolCall、Job执行ID与输入hash，不重新提交，不自动导入或删除结果。状态文案区分原Job完成与结果尚未验证，consumer无此能力入口。查询/停止能力已接入时间线；完整导航和视觉布局仍归后续UI验收。

新增真实按钮点击覆盖：完整Goal/审批/guest开始后SIGKILL，两系统各一次；恢复时渲染生产组件并查询、实际点击停止，最终明确断言原Runtime Job CANCELLED，第二次启动继续核对身份与预算。最终 **2组/4次恢复/2次实际停止点击**，证据 `build/main-verification/proot-recovery-ui-summary.json`。对账清理仍由测试单独完成，UI查询/停止本身保留payload，不等同于结果恢复导入。

测试同时发现打开INTERRUPTED会话仍显示发送中，已在UI投影修复并加3项JVM边界测试；核心非终态恢复语义不变。[缺陷记录](../bug-fixes/2026-09-07-interrupted-chat-not-sending.md)。双变体JVM608/608、零跳过，根lintDebug、Spotless、Detekt和构建通过。首轮公共Cloudflare MCP网络超时、两台恢复清理失败及定向清理日志全部保留；最终新APK验证独立计数。其余后端、恢复结果验证、全量最终快照和统一UI仍继续。

## 2026-09-07 write 发布后失败结算修复

修正文件已替换后 I/O 异常仍声称“未写入”的问题：未知结果要求核对目标文件，保留 sideEffectFree=false。真实发布后注入异常的回归先失败后通过；files 110/110、framework 150/150、workspace 87/87，无跳过。两发行包 Debug 构建、root lintDebug、Spotless、Detekt 通过，相关未变化任务可复用 Gradle 结果。证据 `build/main-verification/write-after-publish-{green,build}.log`。该注入测试不等于文件阶段 SIGKILL 验收；见 [缺陷记录](../bug-fixes/2026-09-07-write-post-publication-failure.md)。

## 2026-09-07 执行后取消的待核对信号

修正 dispatcher 对执行后取消只写“副作用未知”却丢失 requiresReview 的问题。两个回归先失败后通过，包含阻塞执行线程取消；framework150/150、files110/110，无跳过，两发行包构建、root lintDebug、Detekt通过。证据 `build/main-verification/cancel-review-{red,green}.log`，见 [缺陷记录](../bug-fixes/2026-09-07-cancelled-execution-review.md)。该轮不是设备 SIGKILL 或完整会话端到端验收；超时执行边界继续单独核查。

## 2026-09-07 超时执行边界修复

区分执行器未提交的超时（确认零副作用）和提交后的超时（requiresReview=true）；保留 TIMEOUT 与原有有限重试/证明处理。两个回归先失败后通过；framework150/150、files110/110，无跳过，两发行包构建、Lint、Detekt通过。首次实现的 ReturnCount 静态失败保留，最终证据 `build/main-verification/timeout-review-final.log`。见 [缺陷记录](../bug-fixes/2026-09-07-timeout-execution-boundary.md)，不作为设备 SIGKILL 验收。

## 2026-09-07 原子文件层实际进程死亡验证

新增 FilePublishProcessKillDeviceTest 与 `scripts/run-file-publish-process-kill.py`。在真实 AtomicFileWriter.writeAtomicStream 写入临时文件后、正式发布后两个边界，由宿主对专用模拟器 App 执行 SIGKILL。API29/36各两次，共4次真实kill、8次恢复测试通过。临时阶段保留完整旧目标并回收一个孤儿临时文件；已发布阶段保留完整新目标。第二次重启复验内容稳定且再次显式cleanup返回0。每组最后删除自身夹具目录，不触及其他工作区。

证据 `build/main-verification/file-publish-kill-summary.json` 及两个 `file-publish-kill-emulator-*/` 原始目录；摘要记录主包与测试包SHA256。命令 `python3 scripts/run-file-publish-process-kill.py --serial <dedicated-emulator> --adb <sdk-adb> --output <fresh-output-directory>`，两次exit0。测试APK构建、Spotless与Detekt通过，Python语法检查通过。准备阶段按设计被杀，不计JUnit通过。

该验证调用生产原子文件实现，但不经过Goal/Dispatcher，也不证明App启动自动清理；孤儿清理由测试显式调用生产cleanup。完整文件ToolCall进程中断恢复仍待验收，不据此勾选整个HXA-102。未发现需要修改的原子写入生产缺陷。

## 2026-09-07 文件完成后 Goal 回填中断恢复

新增 FileGoalProcessKillDeviceTest 和 `scripts/run-file-goal-process-kill.py`：实际Goal/Chat/Dispatcher/write完成后，宿主收到工具结果回填请求并保持响应，再SIGKILL主App。API29/36最终同APK各一次实际kill、两次恢复校验，共2kill/4恢复通过。目标文件内容与mtime不变，原ToolCall保持COMPLETED、结果存在，Turn/run为INTERRUPTED，Goal为PAUSED，模型次数2且预算预留精确结算、不退款；第二次恢复状态稳定。宿主每组5次模型请求含连接探测，仅1次工具回填，恢复不增加请求。

证据 `build/main-verification/file-goal-kill-summary.json` 与 `file-goal-kill-final-api{29,36}/`。命令 `python3 scripts/run-file-goal-process-kill.py --serial <dedicated-emulator> --adb <sdk-adb> --output <fresh-directory>` 两次exit0；测试APK构建/Spotless/Detekt通过，Python语法检查通过。主APK SHA256及测试APK SHA256记录于摘要。

首轮脚本模块导入错误未启动设备测试。API29首设备轮因读取已结算modelCalls等待第二次调用而超时；改为实际modelCalls记录后API29通过，API36则暴露预算预留取样过早（基线12、恢复6808）。最终在MODEL预留落盘后记录基线，保留精确相等断言。API29首次abort与启动恢复竞争，等待open run结束后清理通过；API36失败夹具也已单独清理。上述失败日志与旧APK轮次均保留，不混入最终通过计数。

该边界是已完成文件ToolCall后的模型回填中断；它不证明发布后但ToolCall尚未结算的极窄窗口，也不覆盖浏览器/UI动作进程死亡。模型为脚本夹具，不是新增真实模型评测。未修改生产实现。

## 2026-09-07 浏览器点击与在途导航的 Goal 中断恢复

新增 BrowserGoalProcessKillDeviceTest 和 `scripts/run-browser-goal-process-kill.py`。在生产Browser创建页面并取得真实snapshot节点token，经实际Goal/Dispatcher/审批执行browser.click。宿主必须收到该点击触发的HTTP导航及模型工具结果回填，保持两个响应不返回，再SIGKILL主App。最终API29/36同一APK各1次点击导航、1次kill、2次恢复，共2导航/2kill/4恢复通过。

重启后原点击ToolCall保持COMPLETED且结果存在，Turn/run为INTERRUPTED、Goal为PAUSED，模型次数2、预算与落盘预留精确结算，无预算退款；再次重启状态不变。每设备宿主仅记录1次导航和1次工具回填，恢复不增加请求。证据 `build/main-verification/browser-goal-kill-summary.json` 与 `browser-goal-kill-verified-api{29,36}/`，摘要含主/测试APK SHA256。测试APK构建、Spotless、Detekt和Python语法检查通过。

原两API运行通过恢复断言但清理失败：启动后临时tab已不存在，重复close抛unknown tab。清理重试还暴露重复归档不被允许；夹具改为检查Goal/Provider/tab存在和会话未归档状态，再执行自身资源清理。初始失败、abort失败及被残留marker挡住的重跑全部保留，最终完整重跑成功后才计通过。生产实现未修改。

此边界证明真实浏览器点击已完成、导航响应仍在途时无启动重放，不代表页面加载完成、任意DOM动作或Accessibility UI动作均已验收。宿主使用专用模拟器su0发信号，不是真机或App Root授权证据；模型仍为脚本夹具。

## 2026-09-07 Accessibility Goal kill 夹具实施中

已新增 UiGoalProcessKillDeviceTest 与 `scripts/run-ui-goal-process-kill.py`，为测试APK的AutomationEvaluationActivity增加显式recordClicks模式下的落盘点击计数；普通固定评测不启用该模式。目标是生产ui.click后保持模型回填，再实际kill并核对计数不增加。当前尚未到达点击/kill边界，不能计验收通过。

测试APK构建、Spotless和Detekt最终通过；最初prepare超过LongMethod限制，已缩减重复提示而未放宽规则。Python语法检查通过。API29多轮停在服务CONNECTED前：secure设置已写入，现场dumpsys显示Enabled/Binding但Bound为空（ui-goal-accessibility-live.txt）。启用移至instrumentation报告UI_SERVICE_READY之后、再补正常MainActivity启动均未使该轮通过。此前将问题归因于单一启用时序仅是排查假设，现有证据不足以确认根因。

API36同一夹具已通过服务连接，但在等待FIXTURE_UNCHANGED前台页面时超时。结束后的截图显示旧PRoot任务在前台，此截图不证明超时期间的前台状态，下一轮需要在等待期间取样。证据 ui-goal-kill-foreground-api36/、ui-goal-api36-screen.png 和相关logcat。原失败全部保留；当前没有实际UI点击或SIGKILL通过声明。

两设备已通过专用abort清理（ui-goal-kill-foreground-abort-api29.log、ui-goal-kill-foreground-abort-api36.log），宿主finally恢复原enabled_accessibility_services/accessibility_enabled。后续从服务绑定与夹具前台生命周期继续定位，不修改生产权限规则或放宽断言。

## 2026-09-07 UI kill 前置诊断与夹具修复

已确认API36测试页面未显示的直接原因：AutomationEvaluationActivity新增局部可变点击计数生成kotlin.jvm.internal.Ref$IntRef，而独立测试APK进程未包含该类，导致NoClassDefFoundError。已将计数改为Activity字段；后续实际页面、ui.snapshot成功，说明该崩溃路径已排除。原crash记录ui-goal-api36-crash.txt保留。没有增加运行时依赖或修改生产页面。

API29在清理并重启专用模拟器后进入实际ui.click（此前停在Binding）；原失败仍保留，不能将一次重启视为服务绑定根因修复。API36一次短暂adb offline使宿主finally恢复失败，设备重新在线后已显式删除本轮enabled_accessibility_services并恢复accessibility_enabled，相关日志ui-goal-counter-fixed-api36.log留存。

夹具又修正两个协议问题：模型先调用生产ui.snapshot再取最新token，替代Goal启动前的旧token；生成ID使用合法字符，工具回填按实际[SUCCEEDED]前缀解析。ui-goal-snapshot-api36的PROTOCOL失败、ui-goal-node-api36的真实回填及后续ui-goal-wire-api36的STALE_TOKEN均保留，不误记为点击成功。

最新ui-goal-events-api36仍为STALE_TOKEN，没有kill通过。测试新增有界事件元数据（仅类型、窗口、包名、时间，不含节点文本）：测试页不变时System UI其他窗口有连续TYPE_WINDOW_CONTENT_CHANGED；当前HelixAccessibilityService对每个事件递增全局generation。该源码与现场事件构成下一步窗口过滤修复的依据，尚未证明修复生效，目标窗口改变、设备锁定、权限撤回和action前的目标复验不能弱化。

ui-goal-event-fixed-build.log记录最新测试APK构建/Spotless/Detekt通过；Python语法与文档门禁通过。所有本轮失败fixture均完成专用abort，包括ui-goal-events-abort-api36.log。当前继续HXA-102，UI动作kill验收仍未完成。

## 2026-09-07 Accessibility 窗口代次修复与 Goal kill通过

修正已知其他窗口TYPE_WINDOW_CONTENT_CHANGED错误失效当前snapshot的问题；同窗口、未知窗口、其他事件和执行前目标/指纹复验保持。JVM43/43，developer主/测试APK、root lintDebug、Spotless、Detekt通过。见 [缺陷记录](../bug-fixes/2026-09-07-accessibility-unrelated-window-generation.md)。

最终API29/36同APK，经真实ui.snapshot→ui.click各点击合成按钮一次，宿主确认落盘计数及模型回填再SIGKILL。共2点击/2kill/4恢复测试通过，计数一直为1、模型请求不增加、Goal暂停且预算精确结算。证据 `build/main-verification/ui-generation-kill-summary.json`、`ui-generation-final-api{29,36}/`。清理与宿主设置恢复完成。

首次修复轮API36已成功点击/kill，但三次调用恰好到Goal模型上限，恢复为BUDGET_EXHAUSTED(maxModelCalls)；夹具改成上限4以隔离中断语义。API29仍有目标窗口初始化事件，保留有效失效检查，改为准备阶段waitForIdle后开始Goal；专用模拟器再次重启后最终完整通过。原失败、环境变化与最终通过不合并计数，不据此宣称服务绑定根因已解决或全部UI验收完成。

## 2026-09-07 Accessibility 修复后全仓 JVM 与静态复验

在当前累计调度/文件/Accessibility修复后执行 `./gradlew -I build/main-verification/force-tests.gradle test lintDebug spotlessCheck detekt --max-workers=2`，提供既有Connector样本目录和交接ZIP环境变量。exit0，34个实际Test任务全部强制执行，2620/2620，零失败/错误/跳过；聚合test任务不重复计数。root lintDebug、Spotless、Detekt通过，未变化的静态任务可使用Gradle up-to-date结果。

证据 `build/main-verification/post-ui-full-host.log`、`post-ui-full-host-result.json`、独立`post-ui-full-host-xml/`和`post-ui-source-hashes.json`。1069个源码/配置hash在该轮前后保持一致。测试计划另存`post-ui-full-test-plan.log`，dry-run输出SKIPPED不代表实际测试跳过。

该轮刷新全仓JVM与静态证据，不替代尚未完成的设备/生命周期/Goal验收契约或统一交互优化，也不宣称真机和后置长稳通过。ADR-0028仍proposed，已重新向所有者请求具体方案决定，未据等待状态擅自接受或实现。

## 2026-09-07 输出校验失败的副作用待核对修复

执行器完成后，输出schema失败现在保留INVALID_OUTPUT并携带requiresReview=true/sideEffectFree=false，避免普通FAILED丢失效果不确定性。回归先149通过/1失败，修复后framework150/150和files110/110通过；两发行包构建、root lintDebug、Spotless、Detekt通过。证据 `build/main-verification/invalid-output-review-{red,green}.log`，见 [缺陷记录](../bug-fixes/2026-09-07-invalid-output-effect-review.md)。前一轮2620全仓快照早于此修复，不能作为此改动的全仓复验声明；本轮为相关模块验证。

## 2026-09-07 M11 CLI 原始Job恢复控制器

新增developer侧SubscriptionJobRecovery和公共状态类型。仅允许INTERRUPTED Turn及其INTERRUPTED模型调用，核对持久绑定turn/modelCall/job/requestSha256；query和cancel返回记录均再次核对身份。未知/不可用结果保持UNKNOWN，成功结果只标记SUCCEEDED_UNVERIFIED，不导入、不reconcile删除证据、不重新submit。停止只作用于已查询的原非终态Job；终态查询不再次取消。

CliGoalProcessKillDeviceTest改用该控制器执行原Job query/stop，再由测试核对Runtime终态与单次cli.job_prepared记录。API29/36 × CODEX/CLAUDE/GROK/COPILOT共8次实际主App SIGKILL、16次恢复通过，生产Runtime的DEBUG合成模型请求，非付费账号调用。证据 `build/main-verification/cli-recovery-controller-summary.json` 及八组 `cli-recovery-controller-api*-*/`，各组包含APK哈希。两发行包构建、测试APK、root lintDebug、Spotless、Detekt通过，日志cli-recovery-controller-build.log。

当前后端类及设备验证已完成，尚未接入SubscriptionProviderIntegration/ChatService/界面按钮，不作为用户可用入口交付。下一步继续同一HXA的显式查询/停止界面接线和实际点击验证；consumer不引入Runtime客户端。

## 2026-09-07 M11 中断订阅任务查询与停止入口

SubscriptionProviderIntegration 接入 developer 原 Job 恢复控制器；consumer 默认不引入 CLI 客户端。AppContainer 注入 ChatService 显式操作，服务串行处理查询/停止，保留取消语义；本地会话扫描只根据 INTERRUPTED Turn/modelCall 与 cli.job_prepared 证据生成条目，不绑定 Runtime，也保留较旧中断 Turn 的入口。ConversationContent 在会话列表中显示恢复组件，查询确认 RUNNING 后才显示停止按钮；三个语言资源明确区分未知、运行、停止请求、已停止、失败结束和结果未导入/验证。查询与停止不 submit、不导入结果、不消费 Runtime 证据。

CliGoalProcessKillDeviceTest 现在点击生产 SubscriptionRecoveryActions，绑定真实 ChatService，再核对原 Job、请求摘要、终态、预算和无重复提交。API29/36 × CODEX/CLAUDE/GROK/COPILOT 共8次实际 SIGKILL、16次恢复全部通过；使用 Runtime DEBUG 合成模型，无账号调用。证据 `build/main-verification/cli-recovery-ui-summary.json` 与八组 `cli-recovery-ui-api*-*/`，含安装 APK 哈希。这里是实际生产组件/服务按钮验收，不冒充完整导航和布局验收。

执行 `./gradlew spotlessApply :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest detekt lintDebug` 通过；首次新 Compose 函数命名检查失败，按现有 Compose 命名约定标注 FunctionName 后复跑通过，未放宽复杂度规则。使用 force-tests.gradle 和既有 Connector 样本执行 `:app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest spotlessCheck`，Consumer 298/298、Developer 310/310，零失败/跳过。日志 `cli-recovery-ui-build-final.log`、`cli-recovery-ui-jvm.log`。此前2620项全仓快照仍早于本次改动，不能替代最终全仓复验。

入口接线取代上一条“控制器未接 UI”的阶段状态；成功结果导入/验证、完整会话交互验收和其他待办继续推进。ADR-0028 仍 proposed，未收到所有者接受决定，未启用 Goal 完成证据规则；长稳与真机边界不变。

## 2026-09-07 订阅恢复入口发现边界

新增 SubscriptionRecoveryDiscoveryTest，使用每例独立 Room 数据库验证：新 Turn 不遮掉旧 INTERRUPTED 调用；另一会话与关闭会话不继承原列表；缺失 cli.job_prepared、错误审计类型与已完成 modelCall 被排除；刷新只保留匹配调用的忙碌/查询状态。这是本地入口发现测试，不启动 Runtime，不替代上一轮真实按钮/跨进程恢复证据。

API29/36 × consumer/developer 每组4/4，共16/16、无失败/跳过。安装主包/测试包哈希均核对，证据 `build/main-verification/subscription-discovery/result.json` 与四份 instrumentation 日志；两测试 APK 构建、Spotless、Detekt通过，日志 `subscription-discovery-build.log`。隔离数据库与内容目录逐例清理，未修改生产存储或 Runtime 凭据。本轮未改生产逻辑，因此不重复上一轮已通过的608项 App JVM及根Lint。

## 2026-09-07 CLI 恢复完整聊天页面路径

CliGoalProcessKillDeviceTest 从单独渲染 SubscriptionRecoveryActions 改为渲染生产 ChatScreen：关闭当前会话，从会话列表滚动查找唯一的合成会话标题，实际点击进入，再滚动至原任务查询按钮并执行查询/停止。每次恢复记录 CLI_RECOVERY_CONVERSATION_OPENED，继续核对同一 Job/hash、预算、不重发及 CANCELLED/INTERRUPTED 终态；fixture 标题增加随机后缀，避免与历史归档测试会话混淆。

API29/36 × 四订阅适配器共8次主 App SIGKILL、16次完整聊天页面进入与恢复通过。证据 `build/main-verification/cli-recovery-navigation-summary.json` 和八组 `cli-recovery-navigation-api*-*/`，含安装哈希及16个导航标记；测试 APK 构建、Spotless、Detekt通过（cli-recovery-navigation-build-final.log）。本轮仅改测试，生产 APK 与上一轮查询/停止入口的4c0c9cdf快照一致。无真实账号调用；该证据覆盖 ChatScreen 内部会话导航，不包括 MainActivity 外层导航、所有字体/语言布局或成功结果导入。

## 2026-09-07 文件发布后、ToolCall 结算前的主进程中断

FileGoalProcessKillDeviceTest 增加 --unsettled 宿主模式。仅在测试 APK 内通过反射包装本进程注册的真实 write executor：原执行器实际完成发布且返回 Completed 后暂停返回，保留整个生产 schema/Policy/Approval/执行路径与文件写入实现，主 App 的 ToolCall 仍为 RUNNING；包装不进入发行源码，不替换文件操作或伪造 ToolResult。测试先确认目标内容、mtime 和待结算预算，再向宿主发出可强杀标记。这个明确的测试暂停点用于命中原本短暂的发布/落库窗口，不当作自然时序统计。

API29/36 各实际 SIGKILL 一次、恢复两次：ToolCall 为 INTERRUPTED，ToolResult 仍不存在，recovery.turn_interrupted 审计准确绑定 uncertainToolCall；Goal PAUSED、run INTERRUPTED、原文件内容/mtime 保持、预算结算一致、无新增模型请求，宿主模型回填计数0。随后复跑原已完成调用/模型回填模式，两API同样2kill/4恢复通过，ToolCall保留COMPLETED且每组回填计数1。合计4kill/8恢复，证据 `build/main-verification/file-settlement-boundary-summary.json` 和 `file-unsettled-api*/`、`file-backfill-regression-api*/`，含APK哈希。

测试 APK 构建、Spotless、Detekt及Python语法检查通过，日志file-unsettled-build-final.log。首次测试类引用结果名错误及两处长行已修正后复跑，未修改生产实现或静态阈值。测试只验证已发布文件的不重放和不确定性保留，不等于成功结果导入或完整发布前所有指令边界；原独立 AtomicFileWriter 临时文件/原子发布实验仍是另一个证据层。

## 2026-09-07 CLI 结果保留读取前置

发现旧reconcile在管道写完即删除Runtime输出，早于主App持久导入，已登记 [未关闭缺陷](cli-result-durable-recovery-gap.md)。新增兼容性扩展fetchResult/transaction6，复用PFD限额与SHA-256校验，读取不删除或对账。两API四适配器重复读取8/8通过；CLI client30/30、Runtime102/102无跳过，developer/Runtime构建及根Lint/Spotless/Detekt通过。证据 `build/main-verification/cli-result-fetch/result.json`、cli-result-fetch-build.log、cli-result-fetch-jvm.log。

这只是持久导入前置；旧正常调用路径尚未改成持久落库后确认，缺陷保持open。下一步须接原Job身份验证、App持久结果、显式确认与中断窗口，不得把fetch接口或查询按钮当作成功结果恢复完成。

## 2026-09-07 CLI 正常调用先持久化后确认

当前SDK await改为非破坏fetch；正常订阅成功结果先验证持久Turn/modelCall/Job/request身份和输出SHA-256，再原子保存私有workspace事件文件、登记session所属artifact并回读校验，之后才发新transaction7确认。Runtime确认校验request/output两摘要、只处理终态且幂等；旧reconcile保留给旧调用者/测试清理，当前正常订阅路径不再使用。探测结果为有意临时结果，不注册会话产物。

双API×四适配器持久化/错误摘要拒绝/重复确认8/8；正常Provider→Chat流程另8/8，确认本地artifact摘要和Runtime已确认记录一致。CLI client30/30、Runtime102/102无跳过，相关构建、根Lint、Spotless/Detekt通过。证据 `build/main-verification/cli-durable-ack-summary.json`、cli-durable-ack/、cli-durable-normal/及相关日志。测试首次跨模块nullable智能转换编译失败已用局部非空变量修正。

[结果恢复缺口](../development/cli-result-durable-recovery-gap.md)仍open：接下来做主进程强杀窗口、持久结果读取/恢复界面，不能用上述正常流程测试替代。未将Goal标记完成，未启动长稳或真实账号调用。

## 2026-09-07 CLI 结果交接三窗口强杀与本地回读

SubscriptionResultStore增加按原Turn/modelCall/Job/request绑定回读，校验artifact归属、size上限及输出SHA-256后解码，保存时也核对modelCall归属。CliResultOwnerKillDeviceTest使用生产Runtime与真实私有结果存储，在已fetch未持久化、已持久化未确认、已确认三阶段由宿主SIGKILL主App，恢复时从Runtime或本地取回同一结果，持久化/确认可重复，保持一个modelCall和一条job_prepared记录。测试种入调用绑定而非完整Goal运行，不能替代生产Goal预算/UI矩阵。

API29/36三窗口合计6次SIGKILL、12次恢复通过；新增本地文件等长篡改拒绝测试后，两API四适配器共8/8通过，恢复原字节后可读。结果文件纳入session删除清单；合成fixture清理完成。证据 `build/main-verification/cli-result-owner-summary.json`、六组cli-result-owner-api*-*/及cli-result-read-corruption/。构建、root lintDebug、Spotless、Detekt与Python语法检查通过，日志cli-result-owner-build.log和cli-result-read-build.log。

成功结果的完整恢复界面仍待接线，结果交接缺口保持open；这里只完成存储/Runtime层强杀和篡改校验，不新增模型请求、不执行恢复事件中的工具、不推进Goal完成状态。

## 2026-09-07 CLI 结果恢复界面与启动刷新修复

显式“取回模型回复”经ChatService串行请求、developer恢复服务核对原Turn/modelCall/Job绑定；优先回读已验证本地artifact，否则fetch后持久保存再精确确认。UI只展示TextDelta/Refusal正文，分页保留全部文字并支持选择，分页不拆开emoji代理对；不执行事件中的工具、不回填Agent上下文、不将中断Turn/Goal完成。consumer接口默认无Runtime操作。

API36首次恢复发现数据库已INTERRUPTED而页面条目未更新，已修复启动恢复提交后的ChatService刷新通知，见 [缺陷记录](../bug-fixes/2026-09-07-recovery-open-conversation-stale.md)。确定性回归两API各1/1；修复后生产ChatScreen三个交接窗口共6kill/12次实际查询/取回/正文断言通过，Turn/modelCall仍INTERRUPTED。证据 `build/main-verification/cli-recovered-reply-ui-summary.json`、cli-result-ui-final-api*-*/。

新增空正文/跨页emoji JVM覆盖；App Consumer300/300、Developer312/312无跳过。Consumer外部Connector测试两次HTTP读取超时原始记录保留，随后单独执行完整Consumer通过（cli-recovery-consumer-serial.log）；不推定并发为根因。相关构建、root lintDebug、Spotless/Detekt任务通过，原KDoc格式失败已修复；日志cli-recovered-reply-refresh-final.log、cli-recovery-consumer-serial.log保留各任务与失败/重跑边界。

剩余：完整Goal成功结果强杀链路，以及Runtime暂不可用时直接读取已经保存在本地的结果；当前恢复入口仍先query Runtime，因此这一点明确未完成。长稳与真机边界不变。

## 2026-09-07 Runtime 不可用时读取已保存回复

本地artifact发现现在独立于Runtime状态，首次打开会话即可显示“查看已保存回复”。未查询到Runtime成功状态时，该入口只调用本地回读：核对原modelCall/Turn与job_prepared绑定、合法Job ID、artifact归属、大小和SHA-256，不执行Runtime query/fetch/ack。用户先显式查询成功状态再取回时仍走原持久化后确认链路；本地读取不隐式确认待处理的Runtime记录。

宿主在确认成功结果并强杀主App后禁用CLI Runtime，恢复时实际点击生产ChatScreen本地结果入口，确认Runtime查询为Unavailable但正文HELIX_OK可读；两API共2kill/4恢复通过。finally将Runtime启用状态恢复原值0，并由dumpsys核对；不清理账号数据。随后原fetched/persisted/acknowledged联网取回窗口另6kill/12恢复通过。合计8kill/16恢复，证据 `build/main-verification/cli-local-reply-summary.json` 与八组cli-local-reply-api*-*/，禁用/恢复状态原文随组保存。

两发行包构建、测试APK、root lintDebug、Spotless、Detekt通过（cli-local-reply-build-final.log）；App JVM串行强制复验Consumer300/300、Developer312/312，无失败/跳过（cli-local-reply-jvm.log）。首次长行格式失败已修正后复跑，未放宽规则。完整Goal成功结果强杀接入仍需验证；本轮是绑定fixture与真实结果存储/UI，不替代Goal预算证据。

## 2026-09-07 完整 Goal 成功回复恢复与 CLI journal 容量修复

CliGoalProcessKillDeviceTest 新增成功模式：通过生产 Goal/Chat/订阅适配器执行真实跨 UID Runtime 的 helix-fixture，在私有结果已持久化并精确 ACK、首个模型事件尚未交回时，由测试包装器暂停并由宿主 SIGKILL 主 App。恢复经生产 ChatScreen 会话导航、查询和回复入口显示 HELIX_OK；Goal 保持 PAUSED，run/Turn 中断，modelCall/job_prepared 不增加，预算结算不重复。测试包装器仅在测试 APK 中，无生产暂停钩子。

API29/36 × CODEX/CLAUDE/GROK/COPILOT 全8组通过，8次强杀、16次恢复；运行中断 CODEX 分支另2次强杀、4次恢复通过。证据 `build/main-verification/cli-goal-success-summary.json`、cli-goal-success-final-api*-*/ 与 cli-goal-running-regression-api*/。全部使用合成模型，无订阅账号或远程模型调用。完整 Goal 的成功边界仅为持久化+ACK后、首事件前；其他 fetch/persist 窗口仍由此前绑定 fixture 的强杀矩阵覆盖，不能混称完整 Goal 全窗口。

过程中两台 Runtime 均累积128条日志，暴露已确认终态不回收导致新请求永久拒绝。现仅清理过期或容量压力下最旧的已确认终态，保留活动/未确认/损坏记录；有效回归先2失败再通过，CLI Runtime104/104、Client30/30 无跳过，Runtime构建、root lintDebug、Spotless、Detekt通过（cli-journal-retention-green.log）。首次失败的 Goal configure 已按所属 fixture 清理，未清除 Runtime 数据或账号；原始失败记录保留。

当前正常 CLI 路径的结果持久化后确认缺口收口。legacy reconcile 的破坏性兼容语义仍保留，不作为正常恢复路径；30天未确认记录 evidence-expired marker、其他生命周期矩阵及统一交互优化仍待办。ADR-0028 保持 proposed，整个开发 Goal 保持 active；长稳后置、真机和付费调用边界不变。

## 2026-09-07 CLI 未确认结果到期标记实现

按 ADR-0007 为未确认终态增加30天到期处理：启动恢复、新任务容量检查、显式查询和结果取回触发维护。先写入 EVIDENCE_EXPIRED，保留原Job ID、request SHA和终态时间，移除成功证明，再删除request/events及其临时文件。删除失败向上传播；重启看到marker后重试清理，不重新执行。marker不被已确认记录的容量回收清除，也不被legacy reconcile改写为已确认。活动任务不因创建时间较早而直接过期，墙钟回拨不会触发提前清理。

客户端codec可往返该终态，恢复服务映射到明确页面文案；本地已有artifact仍可走独立回读。旧客户端遇到新枚举不能解码，保持不可用边界；未新增破坏性兼容回退。首次JVM回归2项中到期断言失败、时间边界项通过；实现后新增删除失败/重复恢复与同Job不再执行断言，共3项通过。CLI Runtime107/107、Client30/30，无失败/跳过；两发行包及Runtime构建、root lintDebug、Spotless、Detekt通过。证据 cli-evidence-expiry-red.log、cli-evidence-expiry-verified.log；首轮测试通配导入导致格式门禁失败，已修正，原日志保留。

当前仅关闭实现与JVM门禁，不勾选整个到期待办：新状态跨UID Binder/PFD传输、实际恢复页面及设备文件清理仍需API29/36验收。没有修改系统时间、访问账号凭据、运行长稳或真机测试。整个Goal保持active。

## 2026-09-07 CLI 到期结果跨 UID 与恢复 UI 验收

run-cli-result-owner-kill.py 增加 --expired：真实Runtime生成合成结果，宿主强杀主App后，只将本次owned Job终态时间调整为31天前；不修改设备系统时间，不涉及账号。分别覆盖未保存到App的fetched边界、App已有副本的persisted边界。API29/36共4kill/8恢复通过：生产Binder返回EVIDENCE_EXPIRED且无events，ChatScreen实际查询显示过期文案；无副本时无取回入口，有副本时仍可显示HELIX_OK。Turn/modelCall保持INTERRUPTED，原绑定和调用数量不增加。宿主核对Runtime仅剩无成功证明、无ACK的record.json，然后仅清理本次fixture marker。

未过期persisted回归另2kill/4恢复通过。App强制串行JVM Consumer300/300、Developer312/312，零失败/跳过（cli-expiry-app-jvm.log）。测试APK构建、Spotless、Detekt通过；两发行包/Runtime构建和root lintDebug在本次生产实现对应的cli-evidence-expiry-verified.log中通过，后续修改仅测试定位与宿主脚本。汇总 build/main-verification/cli-evidence-expiry-summary.json 保存安装APK哈希和各组结果。

API29首次文案可见性失败、显式滚动后仍无法定位的日志均保留；测试改从Compose页面资源读取当前语言文案，并独立断言状态EVIDENCE_EXPIRED后通过。原fixture随后恢复与清理，再以最新测试APK重跑完整矩阵，不把失败算通过。此项覆盖真实文件/协议/UI，日期通过owned metadata加速，不是31天实时时间经过或完整Goal强杀。到期待办可关闭；长期满marker容量仍按拒绝新任务保护原身份，不自动删除或重放。

## 2026-09-07 CLI 累计修改后的全仓 JVM 与 Goal 双变体设备基线

在当前源码上按既有34个实际Test任务重新强制执行，禁用测试up-to-date/cache并使用 --max-workers=1；显式提供本地Connector验收样本。逐任务核对Gradle执行行与当前XML，共2629/2629，failure/error/skipped均0。源码与配置完整清单1069文件在执行前后hash一致。root lintDebug、SpotlessCheck、Detekt及Consumer测试APK构建任务通过。完整命令保存在 build/main-verification/post-cli-host-command.json，输出post-cli-full-host.log，聚合post-cli-full-host-result.json；不沿用此前2620项快照作为当前结果。

随后两台专用API29/36模拟器分别执行Developer和Consumer：GoalRunCoordinatorDeviceTest、GoalTurnBindingDeviceTest、GoalUsageReservationsDeviceTest、GoalModelCancellationDeviceTest、GoalDialogDeviceTest、GoalEditorDeviceTest，每组28/28，共112/112，无instrumentation跳过/失败。覆盖生产Goal创建/显式Continue、绑定、持久预留/恢复、loopback模型socket停止与wake限额、实际Goal表单；安装前后校验main/test APK哈希。证据 post-cli-goal-device-summary.json 与四组post-cli-goal-api*-*/command.json、instrumentation.log、result.json。

本组未执行真实订阅账号、完成条件验证或非长稳生命周期全矩阵。ADR-0028仍proposed；HXA-102及整个持续Goal仍未完成。24小时长稳保持后置，真机边界不变；未暂存、提交或推送并行工作。

## 2026-09-07 前台服务与前后台资源门禁当前回归

修正DataSyncForegroundServiceDeviceTest的验收语义：API35以下超时回调使用显式SdkSuppress，不再return伪装通过；等待成功后实例缺失必须失败；通知停止测试发送实际通知所附PendingIntent，替代手写等价ACTION_STOP Intent。此次只有测试修改，无生产服务行为改变。

API29选择三个适用服务方法和AndroidResourceGateDeviceTest，每发行包4/4；API36另加onTimeout回调，每发行包5/5。Consumer/Developer四组合计18/18，实际选择的方法无失败/跳过；API29超时方法明确不适用，未计入分母。20次快速启动停止后等待异步foreground promotion检查，确认无实例/通知；WAITING_APPROVAL停止；Activity CREATED时并发降到1、RESUMED重新读取实际MemoryInfo/thermal。证据 build/main-verification/lifecycle-current-summary.json 与四组lifecycle-current-api*-*/命令、原始instrumentation日志、安装APK hash。

两测试APK构建、Spotless、Detekt通过（lifecycle-current-build.log），文档/ADR/diff门禁通过。onTimeout为直接调用真实服务回调，不是等待Android六小时配额；资源探针读取真实状态，但没有制造低内存/温控压力。实际旋转、断网、其他运行后端生命周期仍待验；本组不关闭整个非长稳矩阵。24小时长稳保持后置。

## 2026-09-07 Goal 模型流期间 Activity 实际旋转

GoalModelCancellationDeviceTest增加旋转路径，在生产Goal/ChatService/OkHttp连接已建立且服务端持有SSE时启动MainActivity，requestedOrientation切换到相反方向，并等待实际Configuration.orientation变更。验证原Goal仍RUNNING、run/Turn ID清单不变、服务器仅收到一个持有请求且连接未断。finally恢复原方向请求并确认原orientation，再沿原停止流程检查socket断开、单次模型计账、run关闭、预留结清。测试Provider为本地loopback，结束恢复mode/budgets并删除所属Provider/Goal，归档所属session。

API29/36 × Consumer/Developer每组旋转1项、原显式Stop与wake限额2项，合计12/12，无失败/跳过；其中新增旋转4项、原回归8项，不混算成12项旋转。证据 build/main-verification/goal-rotation-summary.json 与四组goal-rotation-api*-*/，含实际命令和安装main/test APK hash。两测试APK构建、Spotless、Detekt通过（goal-rotation-build.log）；本轮未改变生产代码。

这里是真实Activity配置方向变化，非单独scenario.recreate替代，但也不声称物理旋转传感器验收。断网、真实内存压力和其他后端生命周期仍有剩余；ADR-0028决定及统一交互阶段保持待办，整个Goal未完成。

## 2026-09-07 Goal 部分模型流连接中断

LoopbackModelServer暴露测试持有socket，GoalModelCancellationDeviceTest在生产请求收到HTTP/SSE部分正文后主动关闭服务端连接；不调用chat.stop代替断连。生产OkHttp/解码/Goal结算路径将Goal、run outcome与Turn置为FAILED，模型调用一次、run结束、wake用量清零、预留无剩余且无工具调用。服务端请求计数保持1，不自动重发。原停止、wake限时和实际旋转路径同时复跑。

API29/36 × Consumer/Developer每组4/4，共16/16，无失败/跳过；其中新增连接中断4项、原回归12项。证据 build/main-verification/goal-disconnect-summary.json、四组goal-disconnect-verified-api*-*/，保留命令、日志、main/test APK hash。两测试APK构建、Spotless、Detekt通过（goal-disconnect-wired-build.log）。本次只修改测试服务与设备用例，无生产逻辑修改。

首轮两Developer组合中断项因测试分支漏接socket.close而超时，实际未断连，不能视作产品网络故障；原goal-disconnect-api29/36-developer日志保留，所属fixture由finally清理。补接后重跑四组合，不将失败或未运行Consumer算通过。本组验证部分SSE连接丢失，不是飞行模式、Wi-Fi/蜂窝网络整体丢失、DNS/TLS故障或真机网络切换，相关边界仍保留。

## 2026-09-07 PRoot 成功结果恢复缺口与过期状态修正

源码复核确认ProotJobClient仅submit/query/cancel/reconcile；ProductionLinuxExecutor收到的output PFD落在随机临时目录，finally删除且没有持久结果定位绑定。Runtime虽保留output.zip，但未提供非破坏性重新取回；现有reconcile会记录确认并删除载荷。因此查询/停止或进程死亡后的临时残留不能充当成功结果恢复验收。后续按 [具体缺口](proot-result-durable-recovery-gap.md) 补取回、私有持久化、精确确认与只读展示，工作区新写入仍走既有工具审批。

另修正evidenceExpired=true的SUCCEEDED记录仍显示成功的错误，状态映射优先显示过期说明且无Stop；中英文资源同步。有效JVM回归先失败后通过。首次全组Developer遇外部Connector HTTP/2 SocketTimeoutException，原proot-expiry-report-green.log保留；完整复跑Developer313/313、Consumer300/300，无跳过（proot-expiry-report-retry.log、proot-expiry-report-jvm-summary.json）。该轮随后Detekt发现ReturnCount，改为等价if表达式后定向回归1/1、双包构建、root lintDebug、Spotless、Detekt通过（proot-expiry-report-final.log）。未放宽规则或删除测试。

本组只修正状态映射并明确缺失协议；未宣称PRoot结果恢复完成。过期状态实际设备导航、归档取回全链路仍待验，整个开发Goal保持active。

## 2026-09-07 PRoot 非破坏性结果取回原语

增加additive TX_JOB_FETCH_RESULT事务（原协议版本不变，旧Runtime不支持时无破坏性回退），Runtime通过可选ProotJobResultHandler返回SUCCEEDED、未过期、未确认且归档存在的只读PFD和终态记录。归档压缩字节上限136MiB，容纳原128MiB未压缩上限及封装开销；App后续仍需独立执行ZipJobExtractor/manifest校验。ProotResultClient核对返回记录与调用方原记录完全一致及描述符长度，成功后由调用方关闭PFD，验证异常关闭描述符。取回不提交、不取消、不reconcile，不导入或执行内容。

API29/36 ProotResultArchiveDeviceTest各2/2：测试handler接入真实ProotRuntimeServiceBinder/Parcel/PFD，两次读取保留原未确认记录与归档；过期、已确认、文件缺失和RUNNING均拒绝。证据 build/main-verification/proot-result-fetch-summary.json、proot-result-fetch-api29/36.log，含安装Runtime/test APK hash。字节为合成归档载荷，handler在Runtime测试进程内，不声称跨UID、真实PRoot执行或内容完整性验收。临时fixture目录在finally删除，不影响安装资产。

PRoot Client23/23、IPC40/40 JVM无失败/跳过；Runtime及测试APK构建、root lintDebug、Spotless、Detekt通过（proot-result-fetch-final-build.log）。首轮通用catch和复杂条件静态失败已按规则修正，保留原日志。客户端尚未接入产品恢复入口，私有持久保存/确认、跨UID强杀与UI仍见开放缺口；整个Goal保持active。

## 2026-09-07 PRoot 精确确认与删除失败修复

新增TX_JOB_ACK_RESULT，校验原terminalCommit（包含jobId/executionId/input与output manifest及终态字段）后才允许确认；ProotResultClient无旧协议破坏性回退。ProotJobStore确认与legacy reconcile均改成先检查完整载荷删除成功再保存回执；重复确认返回首次时间，过期证据不变造为已确认。显式确认与对账在store锁内串行。本次尚未把新确认接口接入App产品流程，调用前私有持久化仍是必须补齐的前置。

API29有效故障夹具将payload子目录设为只读，旧实现未抛错并继续确认，红日志proot-ack-red-api29.log保留；修复后原记录不变、删除失败抛出。两API各5/5：错误指纹拒绝/归档保留、正确确认/重复回执、删除失败、重复fetch与无效结果拒绝、真实PRoot作业legacy reconcile。证据 build/main-verification/proot-ack-summary.json、proot-ack-api29/36.log；权限及本次临时目录finally恢复/清理，无安装资产删除。新ACK的跨UID事务尚待专门执行，不能由直接store测试冒充。

PRoot Client/IPC既有JVM任务通过（23/40），Runtime与测试APK构建、root lintDebug、Spotless、Detekt通过（proot-ack-build.log）。归档内容验证、App持久导入与恢复UI仍保持开放缺口；整个Goal不标记完成。

### 2026-09-07：PRoot 大归档登记的流式校验前置修复

制品登记原先通过 `file.readBytes()` 校验 SHA-256，会为整个归档分配内存；现在复用新增的流式文件哈希重载，校验失败仍抛出异常。storage JVM 75/75 通过（含空文件、缓冲区边界及缺失文件测试），根级 `lintDebug`、Spotless 与 Detekt 通过（Lint 日志：`build/main-verification/proot-artifact-stream-lint.log`）。独立 JVM 在 32 MiB 最大堆下成功校验 128 MiB 文件，并与生成时计算的 SHA-256 一致；这证明该哈希路径不再需要整文件大小的堆分配，不代表 Android 全链路内存验收。证据：`build/main-verification/proot-artifact-stream-summary.json`、`proot-artifact-stream-hash.log` 和 `proot-stream-hash-low-heap/result.log`。首次独立验证因编译类目录配置错误未运行到产品代码，原始日志保留为 `classpath-error.log`。

PRoot 私有归档持久保存、回读验证后 ACK、只读恢复 UI 及跨 UID/进程终止矩阵仍待完成；此项不关闭结果恢复缺口。

### 2026-09-07：PRoot 私有结果保存组件

新增 `ProotResultStore`：核对原 Turn/ToolCall 与 job/execution/input manifest 绑定，限量接收归档，使用既有严格 ZIP 提取校验核对输出 manifest，原子写入会话私有制品并流式回读校验。重复保存核对既有登记，不覆盖已登记的不同结果；组件不发送 ACK、不执行归档内容、不导入用户工作区文件。

API29/36 各 2/2 通过，覆盖真实 Room 登记、重复保存、内容损坏、错误 execution/manifest、已有结果保留和会话删除返回制品清理路径。这里使用生成的合法 ZIP 与绑定夹具；未证明 Runtime 跨 UID 获取/ACK、进程终止恢复或隐私删除服务实际删除文件。Developer 与测试 APK 构建、根级 lintDebug、Spotless、Detekt 通过。证据：`build/main-verification/proot-result-store-summary.json`、`proot-result-store-emulator-5596.log`、`proot-result-store-emulator-5598.log`、`proot-result-store-verified-build.log`、`proot-result-store-lint.log`。前几次新增测试格式检查失败日志保留，最终修正后通过。

下一步将组件接入获取结果→持久保存及回读→精确 ACK，并完成只读结果 UI 与跨 UID/进程终止矩阵；结果恢复缺口仍开放。

### 2026-09-07：PRoot 恢复协调器的保存/确认顺序

新增 `ProotResultRecovery`，提供生产 Client 工厂和原调用恢复入口：仅允许 INTERRUPTED Turn，校验结果并持久保存/回读后才调用精确 ACK；已有本地归档也重新核对当前记录的 manifest 后才确认。`localOnly` 只读取已验证本地制品，不查询或唤起 Runtime。确认不可用时不会将其标为已确认，组件不提交新 Job、不完成 Goal。

API29/36 各 4/4 通过（含上一轮保存回归）：新增测试在 ACK 回调中验证落盘制品可读、错误 manifest 的 ACK 次数为零、本地读取不调用任一 Runtime 接口。使用真实 Room、合法 ZIP 和 PFD，query/fetch/ACK 为注入夹具；不冒充跨 UID Binder 验收。Developer/测试 APK、Spotless、Detekt、根级 lintDebug 通过；证据：`build/main-verification/proot-result-recovery-summary.json`、`proot-result-recovery-emulator-5596.log`、`proot-result-recovery-emulator-5598.log`、`proot-result-recovery-verified-build.log`、`proot-result-recovery-lint.log`。初次新增测试行宽失败日志保留。

待接入恢复界面，补真实 Runtime ACK 与进程终止矩阵，并处理正常执行路径的持久结果；完整结果恢复缺口仍开放。

### 2026-09-07：真实 PRoot 归档保存与跨 UID 确认

跨 APK 回归发现生产执行只写调用方 PFD、未生成 Runtime `output.zip`，导致成功后 fetch 返回空；现已先构建、同步并原子保存 Runtime 归档，再传给调用方。相同用例在 API29/36 从失败转为通过：重复 fetch、错误 ACK 拒绝、App 持久保存后精确 ACK、回执保持、ACK 后 Runtime 无归档、本地仍可读。真实命令运行于 PRoot，只有 INTERRUPTED Turn 的归属为夹具，不是完整 Goal/进程终止验收。

跨 UID 2/2 和 Runtime 执行/取消/超时/确认/归档回归 28/28 通过，Runtime/测试构建、根级 lintDebug、Spotless、Detekt 通过。证据：`build/main-verification/proot-cross-uid-result-summary.json`、`proot-cross-uid-result-fixed-emulator-5596.log`、`proot-cross-uid-result-fixed-emulator-5598.log`、`proot-retained-output-regression-emulator-5596.log`、`proot-retained-output-regression-emulator-5598.log`。原始失败日志保留为 `proot-cross-uid-result-emulator-5596.log` / `proot-cross-uid-result-emulator-5598.log`。缺陷记录：`docs/bug-fixes/2026-09-07-proot-result-missing-runtime-archive.md`。

剩余：恢复界面、进程终止窗口、正常 App 执行路径的持久制品；首次输出传输失败仍按既有规则记 FAILED，其结果恢复尚需另行覆盖。完整缺口仍开放。

### 2026-09-07：PRoot 只读结果预览数据

新增 `ProotResultPreview` 与跨分发共享的展示数据类型，严格校验并在临时目录提取已恢复归档，分别读取 stdout/stderr（各最多 65536 个 UTF-16 code units），截断时不拆分代理对，并保留显式截断标记；文件列表包含原路径、大小和 SHA-256。预览不执行内容，不导入工作区，完整归档保持不变。Developer 的恢复模块提供恢复并生成预览的入口；Consumer 保持能力不可用。

新增 JVM 4/4 通过：输出/文件元数据与归档保持、代理对截断、恰好达到限制、坏归档拒绝及临时提取清理。Developer/Consumer 编译、根级 lintDebug、Spotless、Detekt 通过。证据：`build/main-verification/proot-result-preview-summary.json`、`proot-result-preview-build.log`、`proot-result-preview-lint.log`。这不是 UI 验收：聊天卡片的数据状态、查看入口与展示组件仍待接入，随后进行模拟器界面与进程终止矩阵。

### 2026-09-07：聊天卡片接入 PRoot 结果查看

聊天恢复卡片增加查看入口，ChatService 先尝试本地已验证归档，再按需获取原作业结果、保存并确认；加载期间禁用重复操作，失败显示不可用提示，取消不吞掉协程取消。timeline 保留预览状态，显示 stdout/stderr、归档清单及截断提示，支持分页和选择复制，未执行任何恢复内容或完成 Goal。三组中英文资源已补齐。

API29/36 各 6/6 通过：Compose 原调用身份路由、分页/末页禁用，以及四项保存和 ACK 顺序回归。Developer/Consumer APK、Developer 测试 APK、Spotless、Detekt、根级 lintDebug 通过。证据：`build/main-verification/proot-result-ui-summary.json`、`proot-result-ui-emulator-5596.log`、`proot-result-ui-emulator-5598.log`、`proot-result-ui-verified-build.log`、`proot-result-ui-lint.log`。

界面入口已接入生产调用，但本轮组件测试不等同完整 ChatService→真实 Runtime→结果界面端到端验收。下一步补原进程终止后的真实结果恢复、离线本地查看和完整聊天界面验证；正常 App 执行路径持久制品及首次传输失败恢复仍开放。

### 2026-09-07：调用方死亡后的 Runtime 冻结诊断

新增 `--successful` 诊断路径，运行中 SIGKILL App 后观察原 Job，再经生产 ChatService/恢复按钮查看。API29 实测 1 次 SIGKILL、2 次重启读取成功且模型请求未增加；API36 未通过：原 Runtime/guest 仍存活，journal 保持 RUNNING，`dumpsys activity processes` 明确记录 `isFrozen=true`。重连后 Job 成为 TIMED_OUT（stdout 已有内容），不能按 SUCCEEDED 接受。停止验证因实际已超时而未满足 CANCELLED 断言，之后 `abort` 清理成功；原 Runtime Job 终态证据保留。证据位于 `build/main-verification/proot-success-kill-emulator-5596/` 和 `proot-success-kill-emulator-5598/`（含 runtime-process.txt、cancel-recover.log、cleanup-abort.log）。

ADR-0007 第 7～9 条要求：没有匹配的有效 FGS 继续路径时，Binder death 应终止 Job。故“任意 Shell 在 App 死亡后继续成功”不是当前验收目标；本次 API29 结果仅是诊断，不能当作后台继续能力通过。后续需补调用方死亡的终止契约，并把成功恢复窗口改为 terminal commit 已完成、App 尚未消费结果时 SIGKILL。不得禁用 freezer、后台常驻或扩大 service type 来制造成功。新增测试静态检查与 APK 构建通过（首次 TooManyFunctions 已拆分修正），总体场景仍未通过。

### 2026-09-07：修复 PRoot 排队取消失效

为调用方死亡处理核查取消路径时，发现 PENDING 作业取消没有传到执行线程。API29 新测试复现取消后仍 SUCCEEDED；现已共享每个 Job 的取消标记，并在 live process 登记后补查取消。API29/36 各 11/11 Runtime 回归通过，验证排队作业 CANCELLED 且未执行写文件命令；Runtime/测试构建、Spotless、Detekt 通过。证据：`build/main-verification/proot-pending-cancel-summary.json` 及对应 emulator 日志，红测日志为 `proot-pending-cancel-red-api29.log`。缺陷记录：`docs/bug-fixes/2026-09-07-proot-pending-cancel-ignored.md`。

这只是调用方死亡取消的前置修复。提交协议尚无 owner Binder/death recipient，不可据此声称 App 死亡会自动取消；下一步接入该通知并验证排队/运行/终态窗口。

### 2026-09-07：已接入 PRoot 调用方死亡取消

生产 Client 通过新增 owned-submit 事务发送进程 Binder，Runtime 为新接收的 Job 监听死亡并取消原 Job，终态释放监听；重复提交不替换 owner，普通 query/unbind 不触发取消，旧 Runtime 不支持时不回退 legacy submit。未增加 FGS、后台常驻或 freezer 绕过。

API29/36 共 2 次运行中 SIGKILL，主机在 App 重启前核实原 journal 已 CANCELLED；随后 4 次恢复通过且模型请求数未增长。正常跨 UID 成功执行、重复 fetch、保存后 ACK 回归 2/2 通过。证据：`build/main-verification/proot-owner-death-summary.json`、两个 `proot-owner-death-emulator-*/result.json` 及 `proot-owner-normal-emulator-*.log`。Client/IPC JVM、Runtime/App/test 构建、Spotless、Detekt、根级 lintDebug 通过（`proot-owner-death-final-build.log`）；首次缺失 IBinder import 编译失败已修正并保留日志。

缺陷记录：`docs/bug-fixes/2026-09-07-proot-owner-death-not-cancelled.md`。Legacy 原始提交仍无 owner；当前生产 Client 不再使用它。待补 dead-at-submit/排队 owner-death/终态 race，以及 terminal commit 已完成、App 未消费时终止进程的成功结果恢复。

### 2026-09-07：PRoot 终态提交后、App 消费前的真实恢复

`--successful` 已改为合法终态窗口：测试主机确认原 guest 开始后 SIGSTOP App（PID 归属核实），等待 Runtime SUCCEEDED/terminalCommit，再 SIGKILL 同一 PID。失败路径恢复 SIGCONT，避免遗留暂停进程；不会用“App 死亡后任意 Shell 继续执行”作为验收条件。旧诊断结果不追溯改成通过。

API29/36 共 2 次终态后 SIGKILL、4 次重启恢复通过：生产 Goal/Chat/Dispatcher/审批/PRoot 执行，点击生产恢复卡片，经 ChatService 保存并确认结果，断言实际文本节点含原输出；第二次重启仍可查看，Turn 保持 INTERRUPTED，模型请求计数未增加。终止前后 terminalCommit 一致。证据：`build/main-verification/proot-terminal-ui-kill-summary.json`、两组 `proot-terminal-ui-kill-emulator-*/terminal-before-kill.json` 与 phase 日志。Runtime 安装哈希另核实于 `proot-terminal-ui-runtime-hashes.json`。测试 APK、Spotless、Detekt 与 Python 语法检查通过（`proot-terminal-ui-kill-build.log`）；本轮未改生产代码。

该证据覆盖生产调用链与恢复卡片，不冒充全屏视觉验收、Runtime 不可用的离线恢复、App 保存/ACK 之间的终止或正常执行路径持久制品。这些边界以及 owner 的 dead-at-submit/排队/终态竞态仍待完成。

### 2026-09-07：Runtime 禁用后的本地结果查看

新增 `--successful --offline-final`：真实 Goal 的原 PRoot Job 终态后终止 App，首次重启通过生产恢复链保存并确认；随后核实测试 Runtime 被 disable-user，第二次重启只点击查看入口，不先查询 Runtime。实际文本节点继续显示原 stdout，Turn 仍 INTERRUPTED，模型请求数未增加。API29/36 共 2 次终止、4 次恢复通过，其中 2 次为 Runtime 禁用时的本地结果查看。

主机仅对默认启用的专用测试 Runtime 执行禁用，并在 finally 恢复；已另外核实两台 `enabled=0` 且 `stopped=false`。证据：`build/main-verification/proot-offline-summary.json`、两组 `proot-offline-kill-emulator-*/runtime-disabled.txt` / phase 日志及 `proot-offline-restored.json`。测试 APK、Spotless、Detekt、Python 语法及文档检查通过（`proot-offline-verified-build.log`）；初次测试方法 LongMethod 已拆分，原日志保留。本轮没有改动生产代码。

尚待验证保存/ACK 中途终止和 owner 边界；正常执行路径的持久制品、首次输出传输失败恢复等仍未关闭。

### 2026-09-07：PRoot 保存及 ACK 边界进程终止

新增恢复协调器确认回调的测试暂停点：真实 Goal/Job 终态后先终止 App；恢复使用真实 ZIP、Room 持久保存和 Binder Client，在“回读完成、ACK 前”或“精确 ACK 后”再 SIGKILL。主机在第二次终止前读取原 Runtime journal，分别核实无回执/有回执，避免只靠暂停标记宣称命中窗口。

API29/36 × persisted/acknowledged 四组全通过，共 8 次 SIGKILL、8 次后续 UI 恢复，文本节点显示原输出，Turn 保持 INTERRUPTED，模型请求数未增加。证据：`build/main-verification/proot-result-boundary-summary.json` 和四组 `proot-result-{persisted,acknowledged}-emulator-*/result-boundary-record.json` / phase 日志。测试 APK、Spotless、Detekt、Python 语法和文档检查通过（`proot-result-boundary-build.log`），本轮未改生产代码。

暂停点使用测试注入的确认回调；后续恢复按钮走生产 ChatService。本轮证明归档数据在这些窗口存活，不证明所有 ACK 重试/清理策略：已有本地结果走 localOnly，既有测试末尾仍调用 legacy reconcile 清理 Runtime。该边界不可冒充 UI 自动重试精确 ACK。正常执行路径持久制品、首次传输失败恢复及 owner 竞态仍待完成。

### 2026-09-07：正常 PRoot 执行接入持久制品

`ProductionLinuxExecutor` 在输出 manifest 验证后、工作区导入和返回成功前调用必填持久保存回调；生产 `ProotToolModule` 使用原 Turn/ToolCall 的 `ProotResultStore` 保存会话私有归档。保存失败返回 `OUTPUT_PERSIST_FAILED` 并要求核查原 Job，不伪造成功或重放。直接 IPC 测试没有会话归属时显式提供测试回调，生产装配不使用空实现。

API29/36 各 6/6 LinuxRunTool 执行回归通过；新增 Room 归属夹具围绕真实正常执行器，确认 execute 返回、scratch 清理后仍能读取登记归档和原 stdout，同时保留工作区输出导入验证。该夹具并非完整 Goal 新增验收。证据：`build/main-verification/proot-normal-persist-summary.json`、`proot-normal-persist-emulator-5596.log`、`proot-normal-persist-emulator-5598.log`。App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-normal-persist-verified-build.log`）。

本轮接入持久保存，未把正常路径 ACK 清理一并宣告完成；仍需覆盖保存失败、确认失败/重试、首次输出传输失败和 owner 竞态，再刷新全量门禁。

### 2026-09-07：正常路径精确 ACK 与失败证据

新增 `ProotResultCommitter`，生产装配在保存与回读成功后调用精确 ACK，并写 `proot.result_ack` 审计。ACK 不可用或 RemoteException 时记录 acknowledged=false，保留已验证本地结果，不因此重放成功命令；无效回执仍拒绝。持久保存失败在调用 ACK 前退出，执行器沿既有 OUTPUT_PERSIST_FAILED 核查路径处理。

API29/36 各 12/12 通过：正常 ProductionLinuxExecutor 执行及 Room 归属夹具验证 ACK 后 Runtime fetch 不再提供归档、本地 stdout 仍可读；新失败测试验证不可写目标下 ACK 次数为零，RemoteException 下结果保留且审计明确未确认。还包括现有执行/保存/恢复回归。证据：`build/main-verification/proot-normal-ack-summary.json`、`proot-normal-ack-emulator-5596.log`、`proot-normal-ack-emulator-5598.log`。App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-normal-ack-build.log`）。

正常保存后确认已接入；待确认审计不等于自动重试清理已实现。首次输出传输失败恢复、owner 竞态及最终全量回归仍开放。

### 2026-09-07：修复 owner 监听晚于作业启动的竞态

发现 owned submit 先调用普通 submit 入队，再 linkToDeath；已死 owner 的注册异常到达前命令可能已成功。API29 用延迟注册并报 RemoteException 的 Binder 夹具复现 SUCCEEDED（期望 CANCELLED）。现已共享同步提交路径：判重/预算、PENDING、取消标记、owner 监听全部完成后才入队，重复提交不替换 owner。

API29/36 各 12/12 runner 回归通过，新测试同时断言 CANCELLED 与未生成命令文件。真实生产 Goal 运行中 owner SIGKILL 又完成 2 次、后续 4 次恢复通过，App 重启前 journal 已取消且模型请求未增加。证据：`build/main-verification/proot-owner-order-summary.json`、`proot-dead-owner-emulator-*.log`、两组 `proot-owner-order-kill-emulator-*/result.json`；红测为 `proot-dead-owner-red-api29.log`。Runtime/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-dead-owner-build.log`）。缺陷记录：`docs/bug-fixes/2026-09-07-proot-job-start-before-owner-link.md`。

已死 owner 注册测试是可控 Binder 夹具，真实跨进程 SIGKILL 是另一组证据；不混为同一测试。排队 owner 死亡、释放/重复提交竞态及首次传输失败恢复仍需收口。

### 2026-09-07：首次结果传输失败保留 Runtime 成功证据

真实 runner 配合只读输出 PFD 复现：原命令成功且 Runtime 已保存 ZIP，但初次写失败使终态变为 FAILED，fetch 因而拒绝。现已分离持久归档和瞬时传输结果：仅传输 IOException 写独立诊断，保留原执行状态及 manifest；归档构建/保存失败仍走失败路径，不重放命令。

API29/36 各 13/13 runner 测试通过，新用例核实初次目标为空、重新 fetch 的记录完全一致、ZIP manifest 校验及 stdout 原文。证据：`build/main-verification/proot-delivery-summary.json`、`proot-delivery-emulator-5596.log`、`proot-delivery-emulator-5598.log`；红测 `proot-delivery-red-api29.log`。Runtime/测试 APK、Spotless、Detekt、根级 lintDebug 通过（`proot-delivery-final-build.log`）。最初模块级 Spotless 任务名错误和 terminal 方法长度静态失败已修正，原日志保留。缺陷：`docs/bug-fixes/2026-09-07-proot-transfer-failure-discarded-terminal-success.md`。

本轮仅关闭 Runtime 终态误判。继续检查发现 `ProductionLinuxExecutor` 的 OUTPUT_MISSING/OUTPUT_VERIFY_FAILED/OUTPUT_HASH_MISMATCH 尚未设置 requiresReview，而 UI 恢复入口依赖 INTERRUPTED；必须继续接通该失败路径并验证，不能声称首次传输失败的端到端 UI 恢复已完成。部分传输、归档构建失败注入、owner 排队/释放边界、ACK 重试及最终全量门禁仍开放。

### 2026-09-07：输出验证失败进入待核查并开放原结果恢复

`ProductionLinuxExecutor` 的输出缺失、manifest 缺失、归档校验失败、hash 不符均设置 requiresReview。继续追踪确认生产结算为 FAILED Turn / NEEDS_REVIEW ToolCall；新增共享资格判断，Chat timeline、query/stop 和结果恢复一致支持该组合及既有 INTERRUPTED 组合，运行中的调用不开放。读取恢复不更改失败 Turn，不完成 Goal、不重发命令。

API29/36 各 15/15 通过（执行器、结果存储、UI 组件）；新增用例真实跨 UID 执行后将 App 暂存输出清空或损坏，断言待核查，然后按原 Job 恢复 stdout、登记一个制品、精确 ACK 且提交次数始终为一。恢复阶段 FAILED/NEEDS_REVIEW 由夹具建立，不冒充完整 Goal 结算/导航验收。资格 JVM 测试遍历全部 Turn/ToolCall 状态组合，1/1 通过。证据：`build/main-verification/proot-output-review-summary.json`、`proot-output-review-verified-emulator-*.log`。红测 `proot-output-review-red-api29.log` 明确记录 requiresReview=false。

App 构建、Spotless、Detekt、根级 lintDebug 通过（`proot-output-review-eligibility-build.log`）；夹具非法 CREATED→FAILED 转换由状态机正确拒绝，随后改为合法转换并补齐 errorCode 参数，测试/静态构建通过（`proot-output-review-fixture-final-build.log`），失败日志保留。缺陷记录：`docs/bug-fixes/2026-09-07-proot-output-failure-missing-review-recovery.md`。

完整 Goal 中注入传输故障并从生产界面查看、缺失 manifest/hash 不符故障注入、owner 排队/释放边界、ACK 重试与最终全量门禁仍待验证；整个恢复缺口及 Goal 保持开放。

### 2026-09-07：完整 Goal 输出丢失与生产恢复动作验证

新增 `scripts/run-proot-goal-delivery-failure.py` 及专用 fixture phase：真实创建 Goal、显式 Continue、模型 ToolCall、精确审批、生产 Dispatcher 与跨 UID PRoot 执行。先确认原 Job RUNNING，再仅 unlink 本次新建 scratch 下的 output.zip；Runtime 持有已打开 PFD，保留独立归档。未改生产代码或注入伪终态。

API29/36 两组均通过：生产结算为 INPUT_REQUIRED Goal、FAILED Turn、NEEDS_REVIEW ToolCall，run outcome 为 INPUT_REQUIRED(NEEDS_REVIEW)，待结算 reservation 为空。生产 ChatService timeline 显示恢复入口，Compose 中渲染生产 query/view 动作，点击后文本节点显示原 PROOT_RESULT_READY；精确 ACK 已写回原 Job。读取前后 Goal（含预算）、Turn、ToolCall 完全一致，prepared audit 始终一条；每台模型请求 4 次（含连接探测），没有重放。测试结束恢复本 fixture 设置并清理拥有的 Goal/Provider。

证据：`build/main-verification/proot-goal-delivery-summary.json`、两组 `proot-goal-delivery-verified-emulator-*/result.json` / instrumentation.log。测试 APK、Spotless、Detekt 通过（`proot-goal-delivery-final-build.log`），Python 语法和文档检查通过。初次 fixture 误等 PAUSED，实际状态为 INPUT_REQUIRED；另一台准备 audit 早于 Runtime submit，现先轮询确认 RUNNING。初次失败与 abort 日志保留，其中一次 cleanup 在 active run 尚未结算时被正确拒绝，结算后重试清理通过。

本轮验证完整 Goal 生产结算及恢复动作，恢复控件置于测试 Compose 容器，未冒充整套 App 页面导航或竞品/UI 优化验收；故障为初始结果文件丢失，Runtime IOException 注入是前一轮独立测试。部分传输、manifest/hash 故障、owner 排队/释放、ACK 重试与最终全量回归仍待完成。

### 2026-09-07：owner 排队、重复归属与释放边界回归

新增可控 Binder owner 夹具与三组验证：真实 runner 首个 Job 占队时，第二个 owned Job 保持 PENDING，重复提交不注册替代 owner；原 owner 死亡使其 CANCELLED，命令文件没有创建。正常成功作业完成后仅解绑一次，后续 owner death 不改变终态。另以两个线程同步起跑，100 次交错检查已排队 death callback 和 terminal release 并发时仅 unlink 一次，重复 release 保持幂等。

API29/36 各 16/16 通过，共 200 次并发交错，已核对两台安装 Runtime hash 与本地构建一致。证据：`build/main-verification/proot-owner-boundaries-summary.json`、`proot-owner-boundaries-emulator-5596.log`、`proot-owner-boundaries-emulator-5598.log`。测试 APK、Spotless、Detekt 通过（`proot-owner-boundaries-build.log`），文档校验与 diff check 通过。本轮未改生产代码。

本轮 owner 回调为确定性测试夹具，不冒充跨进程 death；真实运行中 SIGKILL 已有独立证据。以上已覆盖已列出的排队/重复归属/释放边界，不声称证明所有可能线程调度。继续处理结果 ACK 重试、未覆盖的归档失败分支与最终全量验收，整个 Goal 保持运行。

### 2026-09-07：恢复 ACK 响应丢失不再隐藏本地结果

发现恢复协调器在私有 ZIP 保存/回读后直接传播 ACK RemoteException，ChatService 因而显示结果不可用；API29 红测复现。现将 ACK 不可用与归档读取分离，RemoteException 返回已验证文件及 acknowledged=false，非空回执要求原 terminalCommit 和确认时间均有效。

API29/36 各 14/14 通过。新增夹具模拟 ACK 已生效但响应丢失：首次返回本地结果未确认，localOnly 不调用 Runtime；随后显式协调器恢复查询到原记录已有回执，重验本地 ZIP 后重试 ACK，fetch 始终一次、制品仅一个、hash 和 INTERRUPTED Turn 不变。证据：`build/main-verification/proot-recovery-ack-summary.json`、`proot-recovery-ack-emulator-*.log`，红测 `proot-recovery-ack-red-api29.log`。App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-recovery-ack-build.log`）；文档和 diff 检查通过。缺陷：`docs/bug-fixes/2026-09-07-proot-recovery-ack-loss-hid-local-result.md`。

ACK 丢失通过回调注入，不冒充实际 Binder kill；当前产品 view 仍优先 localOnly。待确认提示与可操作的产品重试入口、正常执行后未确认清理，以及最终全量门禁仍需完成。

### 2026-09-07：结果确认状态与显式重试入口

结果预览增加三态确认信息：本地读取尚未核查（null）、确认未完成（false）、已确认（true）。结果卡新增“确认结果已保存”动作，等待期间禁用，确认后隐藏；独立 ChatService 入口调用原记录重验/精确 ACK，失败保留当前结果。普通“查看”仍优先 localOnly，不为显示已保存内容被动绑定 Runtime。COMPLETED Turn / SUCCEEDED Call 也开放结果读取与确认，供正常执行后待确认副本收尾；不改变 Turn/Goal 状态、执行工具或自动重发。

API29/36 各 17/17 执行器/存储/UI 组件回归通过；确认按钮原 turn/call 绑定、忙碌禁用、确认后消失均已测。资格 JVM 状态组合 1/1 通过。完整 Goal 输出丢失场景又通过两台：首次恢复后重复本地查看产生“尚未核查”，再点击生产确认按钮，经真实 Binder 幂等 ACK 回到已确认，原 Goal/Turn/Call 和预算不变，每台模型请求保持 4 次（含探测）。证据：`build/main-verification/proot-ack-ui-summary.json`、`proot-ack-ui-emulator-*.log` 和两组 `proot-goal-ack-ui-emulator-*/result.json`。

App/测试 APK、Spotless、Detekt、根级 lintDebug 通过（`proot-ack-ui-gates-build.log`），JVM 执行日志在 `proot-ack-ui-final-build.log`（该次随后因测试方法长度静态失败，已拆分夹具渲染修正并再次通过完整静态门禁）。首次代码替换断言、Compose 断言导入与命名/长度失败日志保留。三个语言资源同步；文档与 diff 检查通过。

入口已接入；正常完成调用的独立生产导航、真实 ACK 响应丢失后从界面重试仍需专项验证，不能由本轮已确认回执的幂等测试冒充。跨重启仅本地读取显示“尚未核查”，不会伪造已确认状态；没有后台无限重试或主动 Runtime 保活。其余归档异常和最终全量门禁仍待完成。

### 2026-09-07：正常 Goal 结果验收发现并修复状态域混淆

上一轮“COMPLETED Turn / SUCCEEDED Call”描述及判断有误：真实 App ToolCall 成功态为 COMPLETED，SUCCEEDED 属于 Runtime Job。完整正常 Goal 读取实际状态暴露问题；修正预期枚举后 JVM 红测也失败，原 XML 在 `build/main-verification/proot-completed-state-red.xml`。现使用 App 枚举进行成功资格判断，预期组合也改为枚举，避免不存在的字符串未被循环遍历却虚假通过。

API29/36 × 正常完成/初始输出丢失，共四组真实 Goal 场景通过。正常路径模型收到 ToolResult 后结束回复，Turn/Call 均 COMPLETED，Goal 保持 PAUSED / RUN_FINISHED；生产 ChatService 结果动作可显示持久 stdout，重复本地读取后点击确认，经 Binder 精确 ACK 成功。Goal（含预算）、Turn、Call 前后完全相同，每台请求 5 次（含探测）。输出丢失回归仍为 INPUT_REQUIRED / NEEDS_REVIEW，每台 4 请求，无重发。两组皆完成本 fixture 清理。

证据：`build/main-verification/proot-normal-goal-summary.json`、`proot-normal-goal-verified-emulator-*/result.json`、`proot-after-normal-failure-emulator-*/result.json`。资格 JVM 1/1、App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-normal-goal-fixed-build.log`）；Python/文档与 diff 检查通过。缺陷：`docs/bug-fixes/2026-09-07-proot-completed-call-state-mismatch.md`。

模型仍为脚本服务，恢复控件位于测试 Compose 容器但绑定生产 ChatService，未冒充完整页面导航/真实账号验收。真实 ACK 响应丢失后的 UI 重试、其余归档异常和最终全量门禁继续推进。

### 2026-09-07：归档保存与部分传输失败边界

新增三项 Runtime 设备故障验证：构建回调写部分内容后 IOException，最终归档不发布且 pending 清理；原子发布目标被本测试的非空目录占据，原内容保留且调用方未收到任何字节；真实 pipe 读到前缀后关闭，后续写失败明确 delivered=false，而 1 MiB 完整 Runtime 副本逐字节不变、诊断存在且 pending 不残留。

API29/36 各 18/18 通过（上述 3 项与既有 15 项真实 runner 回归），证据 `build/main-verification/proot-output-failures-summary.json`、`proot-output-failures-emulator-5596.log`、`proot-output-failures-emulator-5598.log`。测试 APK、Spotless、Detekt 通过（`proot-output-failures-build.log`）；文档和 diff 检查通过。本轮未改生产代码。helper 测试载荷为合成字节，专门验证发布/传输，不冒充 ZIP 格式验收；格式和 manifest 由已有真实归档测试证明。

清单首页的“只有查询/停止、产品保存/UI 未接线”已按当前源码与分项证据校正；PRoot 总项仍留开放，实际 ACK 响应丢失界面、完整导航和最终合并回归仍待完成。不会据本轮异常测试将整个 Goal 或所有 PRoot 验收标绿。

### 2026-09-07：PRoot 完整 ChatScreen 结果导航

恢复验证改为生产 ChatScreen：关闭会话返回列表，按本次唯一标题点击原会话，核对 sessionId 与原 call 的恢复资格，滚动到 query/view/确认按钮并实际点击，显示原结果文本。原组件容器验证仍是历史证据，本轮补上完整会话页面布局与导航。测试标题增加 UUID，避免与保留的历史归档会话重名；首轮选择器歧义与本 fixture abort 清理日志保留，没有删除其他历史会话。

API29/36 × 正常完成/输出丢失，四组均通过。正常路径请求数每台 5 次（含连接探测），输出丢失每台 4 次；Goal/Turn/Call、结算预算和原 Job 不变，查看后精确确认正常。证据：`build/main-verification/proot-result-navigation-summary.json`、`proot-normal-navigation-verified-emulator-*/result.json`、`proot-failure-navigation-emulator-*/result.json`。测试 APK、Spotless、Detekt 通过（`proot-navigation-unique-build.log`），文档与 diff 检查通过，本轮没有改生产代码。

本轮为真实生产 ChatScreen 页面，模型仍为脚本服务；不冒充外层应用导航、所有屏幕尺寸/语言/大字体和统一 UI 优化验收。PRoot 已列的结果页面导航项已获得证据，实际 ACK 响应丢失界面路径与最终合并门禁仍继续。

### 2026-09-07：PRoot 收尾后的合并宿主全量刷新

在 main 记录 1,122 项源码/配置指纹，核对全部 33 个含 test 源码模块对应的 34 个 JVM 任务（App 两 flavor 分别执行），使用 force-tests.gradle 禁用测试 up-to-date/cache 与 max-workers=1 强制重跑。逐任务核实实际执行行、当前生成 XML 和测试总数：2,637/2,637，零失败/错误/跳过。指纹在 Debug/Release 验证后均一致，没有用跨源码快照拼接通过。

根 lintDebug、lintRelease、Spotless、Detekt 通过；consumer/developer App 及 PRoot/CLI Runtime 的 Debug/Release 共八个主 APK 构建通过，另完成 App 双 flavor 测试 APK。Release 产物 unsigned，未签名/安装发布包或宣称 store 验收。证据：`build/main-verification/post-proot-full-host-result.json`（34 任务逐项计数）、`post-proot-host-command.json` / `post-proot-host.log`、`post-proot-release.log` / `post-proot-release-result.json`（八产物 SHA-256）、`post-proot-source-before.json` / `post-proot-source-after.json`。

本轮刷新宿主基线，没有新增生产改动；清单的 JVM/构建计数已从旧快照校正。设备专项、真实 ACK 响应丢失 UI、尚待决定的完成证据契约和统一界面优化仍独立推进。长稳保持后置，不以宿主全部通过宣告整体 Goal 完成。文档/ADR/diff 检查通过。

### 2026-09-08：确认交接进程终止与 PRoot 缺口收口

确认前（本地已回读，未 ACK）和确认后（真实 Runtime ACK 已返回给测试暂停回调，但尚未返回恢复协调器/UI）各在 API29/36 SIGKILL App。主机在终止前读取原 journal，分别证明无/有确认时间。四组共 8 次 SIGKILL、8 次后续完整 ChatScreen 页面恢复；本地查看后点击显式确认，结果状态变为已确认，Turn 保持 INTERRUPTED，原 Job/输入/预算不变，每组模型请求固定 4 次，无重发。

证据：`build/main-verification/proot-ack-kill-navigation-summary.json`、两组 `proot-ack-consumption-kill-emulator-*/result-boundary-record.json` 与两组 `proot-before-ack-kill-navigation-emulator-*/result-boundary-record.json` 及 phase 日志。测试 APK、Spotless、Detekt 通过（`proot-ack-kill-navigation-build.log`），本轮未改生产代码；文档/ADR/diff 检查通过。

此前“实际 ACK 响应丢失”的待办现明确区分证据：RemoteException 注入验证未知传输响应时保留本地结果；本轮真实进程终止验证确认尚未被恢复协调器/UI消费的窗口，未宣称截断 Binder 内核响应。两者覆盖所需的不确定确认行为与真实生命周期恢复。成功结果恢复记录按原四项要求给出对应表并关闭该有限缺口；HXA-102、整体设备矩阵、完成证据契约与统一 UI 优化仍继续，整个 Goal 不标记完成。
