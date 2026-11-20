# 结果与日志补读收尾

本页的测试计数、提交与命令属于当时收尾；当前结果契约见 [ADR-AGENT-006](../adr/agent/006-model-data-budget-boundaries.md)，不从历史归档缺字段处理恢复已移除的旧格式兼容。

## 契约

工具业务动作保持单次执行。失败正文作为该调用的诊断数据持久化到既有 `tool_results` / ContentStore，摘要有界；失败数据不标为 verified success。`tools_read_result` 在同一受信会话内补读已保存的成功正文或失败/待核对诊断，拒绝进行中、无正文、损坏正文、跨会话和未验证的成功结果。读取失败诊断不改变原调用终态，不执行、补偿或重放原工具。

长正文继续按需补读。模型投影在独立有界字段中优先保留状态、退出码、错误、原 Job/执行身份、日志引用及截断事实。模型消息构建保留 `callRef`、`logRef`、副作用不确定性等平台交付字段，而不只把它们存在 UI 信封里。模型自行选择后续业务动作；日志与工具输出是数据，不能授予权限。

Developer 的 `code_linux_job_read_log` 接收同会话 `logRef`（turn/call）、`source`（saved/live）、`stream`（stdout/stderr）及 Unicode 码点 `offset`。继续分页可携带 `contentHash`，文本变化时拒绝拼接不同快照。它只根据原调用解析 Job 身份，不接受替换 jobId、任意路径或调用者提供的会话。

- `saved` 校验本机已持久化归档与原绑定后读取，不启动 Runtime、收取新作业、ACK 或重新执行。成功、失败和取消的终态均可保存日志证据；失败归档不发布为成功产物。保存原终态元数据以保留退出码和捕获截断事实。旧归档缺少元数据时保守标记截断未知，不声称完整。
- `live` 复用已有批准连接与日志游标协议，按有界页取得当前预览并进行 UTF-8 增量解码。连接/日志代次丢失时返回不可用，不重连提交、不启动进程。日志 EOF 不表示进程成功；响应明确是 observationOnly，callState 是数据库调用状态。预览本身仍受现有容量限制，不声称无限保存所有输出。
- 正文输出前复用现有凭据模式及当前安装秘密值进行脱敏，然后分页。归档原始证据留在应用私有数据中；不通过工具暴露任意文件读取。取消和截止时间沿读取路径传播。

## 验证

本轮先 Debug 回归与合成资料设备验收，稳定后一次显式 `scripts/check-all.sh --all`。主机结果在下文记录；构建成功不代表设备效果或真实模型/账号验收。

设备操作只针对已确定的模拟器与临时测试包，不清日常应用数据。离线设备用例只用合成资料；随后按 Owner 明确授权复用既有测试账号访问真实内置模型，仅发送合成测试内容。跨应用读取使用独立测试 APK 的合成接收 Activity，不能等同任意第三方应用实际送达。

本轮不加入关键词全局日志搜索、SAF 自动同步、整库备份或全 UI 重构。

## 本次实际验收

Owner 最后限定为模拟器与真实模型，不做真机。使用唯一连接的 API 36 ARM64 模拟器、Developer Debug 临时应用 ID `com.helix.agent.developer.asrvalidation`；日常安装及其数据不替换、不清除。APK 与测试 APK 的实际安装 hash、源码清单和原始报告保存在 ignored `build/log-closeout/`，不在本文复制变化的 hash。

- 核心 instrumentation 45 项通过：原位写文件、Binder 输出、Room 原文件引用登记、失败归档/受控补读、取消、长 Unicode 诊断正文、跨会话拒绝、持久化失败不伪造完成，以及知识库和历史 Provider 绑定回归。
- 补充 instrumentation 22 项通过，含与核心重叠的 3 项原位测试；覆盖终端真实容量、原连接重连、租期结束及长命令退出、ViewModel 恢复、字体/只读按键，以及跨测试 APK UID 的合成文件/文字读取、无写权限与分享面板取消。
- 独立两阶段各 1 项通过：保存失败原 Job 日志后，仅强制停止本轮临时包，再启动新进程离线读取；原失败状态、退出证据和文件保持，不新增原命令。
- 真实内置模型 `Qwen3.8-27B` 的 3 项 opt-in 测试通过：实际 `tools_search` 发现日志工具，正常 Dispatcher 执行原日志读取，模型根据未在提示中给出的合成错误标记回答；原 Job 提交数为 1、状态仍 FAILED。另两项为知识库真实检索问答与多来源草稿，检查固定来源、显式保存及未选来源不入准备输入。此有界用例不是对所有任务自主恢复策略的普遍保证。
- 复用已有授权测试账号，真实登录、目录和短回复成功。账号夹具的 setup 按设计结束进程，这一阶段不记为 JUnit pass；后续 verify 的 1 项完整通过，验证加密令牌重启恢复、刷新、退出、刷新失效、隐藏缓存目录/模型选择与拒绝已绑定请求。没有新建 Provider 或账号，不记录凭据或令牌。

最初核心轮 43 项中 4 项失败，分享/终端轮 19 项中 6 项失败；保留原始报告，未将其计为通过。设备揭示 Android 目录 creationTime 随内容变化的问题，目录见证改用稳定文件系统身份键；取消时跳过/中止产物哈希，但保留日志交付。内部证据和用户 OUTPUT 分开验证。旧终端夹具按已接受的新开 shell 与实际容量契约修正，保留不接管 UNKNOWN、不重放命令、物理容量不绕过和完整回收检查。

`GRADLE_OPTS='-Dorg.gradle.daemon=false -Dorg.gradle.workers.max=1' scripts/check-all.sh --all` 已退出 0，覆盖主机测试、格式/静态分析、双渠道 lint、Debug/Release 与 R8、release-smoke 编译、锁文件和 APK 边界。该轮构建期间补了一处失败详情补读结构化退出码的纯投影修复；针对最终源码的补充检查结果见下文，不将先前模拟器候选声称为包含这处后续修复。设备通过不意味着所有网络故障、所有第三方分享接收方、SAF 自动同步、整库备份、环境删除重建或 OEM 行为已验收。实际执行了终端停止/回收与应用进程重建；没有重启整个模拟器、清空日常应用数据或操作真机。

测试账号已退出，账号夹具 verify 全部通过；临时主包及测试包已移除，创建的端口转发/凭据桥已清理，原日常安装与模拟器保留。秘密扫描初次命中合成脱敏单测中的凭据样式字面量，改为运行时构造相同数据后通过；未增加扫描例外或放宽断言。脱敏沿既有凭据模式与 SecretStore 当前值，不声称能识别任意未知秘密。

## 主机证据与范围

完整门禁原始日志：ignored `build/log-closeout/final-release-host-verified.log`。核心与补充设备报告分别为 `device-core-final.log`、`device-sharing-terminal-final.log`，进程重建为 `device-restart-prepare.log` / `device-restart-read.log`，真实模型为 `live-models.log`，账号生命周期为 `live-account-verify.log`。均位于同一 ignored 证据目录。账号 setup 的预期进程退出单独保留，不冒充测试通过。

失败详情投影新增 JVM 回归，验证旧摘要未携带退出码时从结构化正文读取 exitCode；保留旧摘要明确退出码的既有优先级。最终补充命令包含 `spotlessCheck detekt`、双渠道 Debug 单元测试、双渠道 Debug/Release lint 和 APK 构建，已退出 0，原始日志为 ignored `build/log-closeout/final-exitcode-regression-verified.log`。

以上是组合工作区的验证，包含保留的其他 UI 改动；分组提交构成依赖序列，不宣称每个中间提交都单独完成设备验收。未提交的视觉/原型工作不在本轮提交范围。未推送。

设备使用 `adb -s emulator-5554 shell am instrument -w -r -e class <本轮测试类列表> com.helix.agent.developer.asrvalidation.test/com.helix.app.HelixAndroidJUnitRunner`，每轮原始输出再由 `python3 scripts/instrumentation_junit.py <报告路径>` 验证。真实模型轮额外显式传入 `-e helix.logLiveModel true -e helix.knowledgeLiveModel true`，类为 `com.helix.app.proot.ResultLogLiveModelDeviceTest,com.helix.app.knowledge.KnowledgeLiveModelDeviceTest`。构建采用 `-Phelix.asrValidation=true :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest`，并与实际安装候选核对。进程重建用例只对这一临时包执行 `am force-stop`，随后运行独立读取阶段，不重新执行原 Job。

末轮双渠道 app Debug 单元测试报告分别为 Consumer 1458 项、Developer 1616 项，均为 failures=0/errors=0，各有 5 项既有 skipped；这些是渠道执行次数，不是去重测试数。新增退出码投影回归在两渠道各 21 项投影测试中通过。首次补充检查保留了旧摘要/正文冲突的失败证据，随后只收窄实现并保留原断言，未删除或跳过该测试。

## 本地提交

- `73118079`：设备与成组工具契约。
- `8cbfd1d6`：知识库与保留式分享。
- `d64af01c`：终端恢复、历史模型来源校验。
- `e71eaf59`：有界技术恢复与未知动作不重放。
- `cb9ba3a7`：原位执行、失败诊断持久化与日志补读。
- `4cad0f5e`：日常 Debug / 显式完整 Release 门禁。

保留工作区中的账号/抽屉视觉与 HTML 原型等其他任务修改；没有提交测试凭据、生成证据、运行时文件或用户数据。此列表记录功能提交，后续仅更新验证记录的提交可通过本文 Git history 追溯。

最终源码冻结后再次运行同一显式完整命令 `scripts/check-all.sh --all`（上述单次 daemon / 单 worker 配置），退出 0；日志为 ignored `build/log-closeout/final-stable-host-all.log`。复用末轮已成功构建的双渠道 Debug/Release/R8 缓存，重新完成全局主机门禁、release-smoke 编译检查、锁文件及 Debug/Release APK 组件/进程/UID/载荷/网络配置边界。最终主机 APK 与源码清单保存在 `final-host-candidate.json`；它不宣称安装或设备验证，模拟器实际候选仍单独保存在 `candidate/`。正式 Release 只做主机验收，未将其冒充 Release 设备验收。

<a id="history-technical-recovery-closeout"></a>

## 前序：技术恢复与业务动作边界

本页保留初版技术恢复的实现/验收，当前策略以 [执行 ADR](../adr/agent/001-turn-coordination.md) 与源码 `ModelRecoveryPolicy` 为准：当前直接推理的可重试空响应最多额外五次，任何输出或工具片段后不重启；工具业务调用仍不因此重放。后续失败结果补读已在 [结果日志收尾](result-log-closeout.md) 落地，下方“后续范围”是当时研究边界。

### 授权与取舍

Owner 要求先完成知识库/分享，再优化重试；同时明确浅 harness 不等于零运行保障。第一阶段已独立完成，见[知识库/分享记录](knowledge-conversation-integration.md#history-knowledge-sharing-closeout)。本阶段仅代码和主机故障注入，不操作设备、真实服务/账号、分享目标或用户终端环境，不提交/推送。

采用小的共用 `TechnicalRecovery` 时间策略，而非业务恢复规划器。自有恢复最多两次额外尝试，约 1/2 秒指数退避、±20% 抖动；新重试须在十秒恢复准入窗口内且小于调用剩余时间。该窗口控制重试准入，不是普通模型生成的总时长上限；MCP 所有连接/业务等待受原 deadline 的 `withTimeout`，取消保留类型并传播；连接清理另最多一秒，退出前不释放实际执行槽位。

### 该批次行为

| 边界 | 恢复/交付契约 |
| --- | --- |
| Dispatcher | 每个业务 ToolCall 单次 executor；无 maxAttempts 覆盖和审批退款重跑入口。保留 schema、权限、当前授权复核、未消费 admission proof、取消/期限、审计及原序结算；新模型动作仍走正常授权 |
| 自配直接推理 | Provider 能力由包装委托保留；仅空响应且可重试错误恢复。任何 text/reasoning/tool fragment 后不重启；最多两次额外 ModelCall，失败记录及用量保留；重试次数、退避和已用时间持久化 |
| 内置账号模型 | 保留原推理/配额不重放边界，不把其包装变成一般重试许可；401 在原账号、原 endpoint、响应体交付前最多刷新后再请求一次；换账号/退出和取消仍阻断迟到数据 |
| 自配 HTTP | 默认拒绝重定向；关闭隐式连接恢复，POST body 单次使用，503/Retry-After 不重发业务体。安全 GET 的服务端明确 503/Retry-After:0 跟随属于只读协议恢复，实测两次 GET 后成功；没有再叠加一层 GET 业务重跑 |
| MCP bootstrap | 只对已分类的连接丢失/超时等，且尚未 tools/call 的阶段重连；每次重核网络范围，固定原 endpoint/凭据，凭据变化停止。TLS/参数/凭据等错误不当作暂时网络失败 |
| MCP 已提交业务 | 接受一次 POST；回包丢失/超时报告 UNKNOWN 和原本地 call ID，要求核查，不制造服务端查询句柄。服务没给 operation 查询协议则无法保证核实远端是否完成/停止 |
| MCP 资源 | 初始化失败也关闭 HTTP engine，保留原异常；已收到有效结果时，清理失败不丢结果，另外返回 connectionClosed=false。重连次数、是否 tools/call 已提交进入受限审计及短错误事实 |
| 等待/读取 | 保留原身份的 jobs 状态/await/collect、终端 attach、Mobile Use 观察及 SDK 的 Last-Event-ID SSE 结果重连，不把它们当作动作重执行 |
| 模型错误投递 | 错误短摘要直接保留，增加 sideEffectFree/requiresReview/callRef。成功结果另报 executorPayloadTruncated 与原输出 hash，避免把模型预览截断与更早丢失混成同一件事；callRef 不冒充失败结果可读句柄 |

MCP 的 NOT_SENT 仅指尚未 tools/call，不能据此声称 OAuth 刷新、远端初始化资源等完全没有效果，所以失败的 sideEffectFree 仍保守为 false。远端 readOnly/idempotent hints 仍不是权限、幂等保护或重试证明。直接推理恢复可能产生额外远端计算/费用，不承诺网络 exactly-once；不会因此重执行已完成的本机工具。

### 发现并修正的问题

`provider is WireModelProvider` 无法识别应用的 `BoundModelProvider`；改为显式只读能力，默认关闭，HTTP 推理声明，账号/订阅边界保留。恢复分支的 `model.automatic_retry` 原不在 Room journal 诊断白名单，现同源常量接线。reasoning 曾不计 outputSizeBytes，补以 receiving 事实拒绝重启。审批重用只限尚未执行阶段的实时准入复核，不误删为退款重跑。

MCP/HTTP 的 one-shot 防护通过真实本机 socket 的丢回复、503/Retry-After:0、重定向场景核对，不以设置 retryOnConnectionFailure=false 就宣称绝无重发。MCP SDK 版本为锁定的 0.15.0；核对其本机编译产物中的 POST 与 SSE 重连分支，保留读取/观察重连。

### 验证

最终 `scripts/check-all.sh --all` 通过，日志 `build/technical-recovery-all.log`：283 项主机脚本测试、文档/ADR/国际化/秘密扫描、Spotless、Detekt、全部 debug/release lint、全量 JVM 测试、两渠道及独立 runtime debug/release 打包、release-smoke APK 编译/lint、依赖锁和 debug/release 渠道/runtime 边界。静态阶段 2m49s、测试/打包 1m26s、smoke 519ms。

针对性命令 `./gradlew :core:model:test :core:policy:test :core:agent:test :provider:api:test :extensions:mcp:test :tools:framework:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest detekt :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` 通过，日志 `build/technical-recovery-regression-final.log`。app 两渠道分别 1452/1600 项，零失败/错误，各五项既有条件跳过；MCP 83、framework 259、core agent 301、provider API 121 项均零失败/错误/跳过。两渠道 AndroidTest 编译通过。

本轮新增或替换二十八项针对性 JVM 用例：共用时序/网络分类四项，模型策略/真实循环六项，HTTP 四项，MCP 连接/未知结果/截止时间/取消/清理与交付九项，应用 Provider 包装及模型事实三项，单次执行/审批消费替换两项。具体网络故障使用真实本机 HTTP sockets 与受控延时/失败注入，不访问真实服务或账号。旧通用自动重试行为测试按新契约替换，原权限、UNKNOWN、取消、排队、原调用身份和并发断言保留。

中间/最终证据位于 ignored `build/technical-recovery-*`；源输入摘要与六个 APK 摘要存本地 evidence JSON。设备 **not requested**；AndroidTest 编译不代表设备执行。未安装、提交或推送。

### 日志调研边界

本轮另行只读核对结果、Runtime journal、审计与 logcat 的存储/可达性。没有新建通用日志读取或搜索工具，没有开放整个审计库。`tools_read_result` 仍仅返回同会话已成功验证的保存结果；失败通常只有摘要，无法承诺存在完整失败日志。通用大结果前缀未保证优先保留每种业务关键字段，超出 Runtime/Dispatcher 上限的内容也不保证补取；这是后续结果投影/授权读取的独立改进范围，不冒充本轮已修复。
