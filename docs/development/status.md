# 当前实施状态

更新：2026-09-27。此页只维护当前结论、下一步和未闭合边界；命令、制品和历史数字归完成记录与证据。现场 HEAD、工作树、远端及设备状态须重新核对。

## Completed

全部已交付 HXA 见[完成记录索引](../completion-records/index.md)，M0 见[工程基线](../completion-records/M0.md)。完成仅限记录中的范围，不代表全部产品、真实账号或发行验收。

- 物理真机回归（OnePlus 6T / API 34）：2026-09-24 在真机 `561e3b15` 上完成了全量 184 项物理硬件测试（0 失败），涵盖 `core:storage` Room 1..28 完整迁移与外键约束、P0 核心能力、Root 调度分级与禁用、`MANAGE_EXTERNAL_STORAGE` AppOp 动态切换、HXA-129 连接器生命周期与通道边界，以及 HXA-196 PRoot 独立后台 Job 租期控制与主进程 SIGKILL 硬杀后的 `:proot` 独立进程存活及终态证明对账。详见[真机验收记录](../evidence/development/physical-oneplus-acceptance-2026-09-24.md)。
- 最近整合：HXA-130 离线签名索引、HXA-212 内置市场、HXA-213 会话 fork 及上下文压缩补强。2026-09-22 合并后完整主机门禁与四象限定向设备 276/276 通过；这是该次制品的历史结果，见[整合验证](../evidence/development/branch-integration-2026-09-22.md)与[压缩修复](../bug-fixes/2026-09-22-context-compaction-admission.md)。该次记录为本地整合、未推送，不推断当前远端。
- HXA-206 本地核心产品验收、HXA-198 双终端均已完成；同 fixture 对照、Git R1 debug/release、升级及实际恢复范围见[206完成记录](../completion-records/HXA-206.md)、[198完成记录](../completion-records/HXA-198.md)与[199/206证据](../evidence/development/acceptance-199-206-2026-09-21.md)。
- 191、192、193～195、197、202～205、207～209、211 等已有交付记录，不重新执行旧交接开发包。旧三态工具权限证据不替代 209 会话授权验收。
- HXA-214 普通 composer、发送回执与统一停止已交付，见[完成记录](../completion-records/HXA-214.md)；HXA-215 最新消息会话内修订已与其联合收敛，见[完成记录](../completion-records/HXA-215.md)。ADR-AGENT-001 已接受。
- HXA-216 默认排队/显式转向已完成本地验收，见[完成记录](../completion-records/HXA-216.md)；本地结果不替代 main 合并后的矩阵与远端 CI。
- HXA-218 第一批 UI 重构与 HXA-219 产物就地预览已完成各自本地范围，见[完成记录](../completion-records/HXA-218.md)和[完成记录](../completion-records/HXA-219.md)；仍保留其设备/整合边界。
- 上述214/215/216/218/219已在本轮整合至本地main：完整主机门禁、30批联合设备验证（920项）和恢复main文档后的源码门禁通过，见[收敛记录](../evidence/development/branch-convergence-2026-09-22.md)。本轮未推送或执行远端CI。

- HXA-129 已在 `codex/hxa-129-connector-lifecycle` 本地交付并已整合入 main：安全替换、安装归属与会话启停，完整主机门禁及272项定向设备验证通过，见[完成记录](../completion-records/HXA-129.md)。
- HXA-217 轻量请求来源记录与 JSONL 可追踪性已完成本地实现与验收，Room 27→28 迁移与设备测试通过，UI 第二阶段对齐 Operit（Thinking Accordion、工具执行内联预览）落地，见[完成记录](../completion-records/HXA-217.md)。
- HXA-196 有租期的独立后台命令 Job 已交付，见[完成记录](../completion-records/HXA-196.md)；异步工具 `code.linux.job.start/status/cancel/collect` 注册、Tasks 面板投影、租期超时控制以及主进程 SIGKILL 硬杀后的 `:proot` 存活与终态证明落盘已全量通过。
- HXA-199 终端专项集成与交付已交付，见[完成记录](../completion-records/HXA-199.md)；双 API 模拟器 144 项矩阵、真实 2 小时租期与 30 分钟脱离 idle、双 shell 恢复、覆盖安装升级及 OnePlus 6T 物理真机核心专项均通过验收。
- HXA-220 Core Engine / TurnEngine 生命周期收敛已完成主机验收，见[完成记录](../completion-records/HXA-220.md)：Engine-owned AgentLoop driver/observation、successor recovery、独立 review receipt 已落地，旧 AgentTurnHost/TurnLiveFrames/serial Turn reducer/Turn-level WAITING_APPROVAL 已删除；设备 `not requested`。
- HXA-221 Pre-release clean-slate baseline cleanup 已完成主机验收，见[完成记录](../completion-records/HXA-221.md)：Room 重置为唯一 v1 / 45-table baseline，1→31 migration 链与旧 Connector/Provider/Criteria/Chat 内部兼容路径已删除；外部协议/Android 兼容保留，设备 `not requested`。
- HXA-223 Post-clean-slate Core Boundary Convergence 已完成，见[完成记录](../completion-records/HXA-223.md)：Turn state CAS、Engine-owned terminal/review/recovery、SessionWorkScheduler 与 SessionInput delivery 边界已收口；Core Engine 第二阶段重构冻结，设备 `not requested`。
- HXA-226 UI / IA 第二轮收敛已交付，并完成 targeted smoke 与当前设备基线收敛，见[完成记录](../completion-records/HXA-226.md)、[HXA-226 模拟器证据](../evidence/development/hxa226-simulator-verification-2026-09-26.md)与[183-class 当前设备基线](../evidence/development/current-device-baseline-2026-09-26.md)：fixture/helper 漂移已收敛，runner 已加固为原子 single-writer + failure-signature 分类；强模型复核后 production `NEW_REGRESSION = 0`。物理真机仍 `not requested`。
- HXA-228 Conversation-first Shell 与 Session Context Control 已交付，见[完成记录](../completion-records/HXA-228.md)与[API 36 simulator evidence](../evidence/development/hxa228-simulator-verification-2026-09-27.md)：Session RunControl 已从全局状态拆成 per-Session durable snapshot，Session Settings / Composer context workbench / Expert / Other-conversation Reference 已落地；Room clean-slate v1 当前为 48 tables。targeted API36 app 16/16、Reference storage 13/13、Reference export 8/8 通过；Memory 仍归 HXA-230，物理真机未在本任务覆盖。
- HXA-229 Model-authored Agent Activity Presentation 已交付，见[完成记录](../completion-records/HXA-229.md)与[API 36 simulator evidence](../evidence/development/hxa229-simulator-verification-2026-09-27.md)：model-facing Tool schema 统一注入 optional `__helix_intent`，provider-neutral boundary 在业务 validation/permission/effect/dispatcher 前 strip；`tool_calls.modelIntent` durable 保存 sanitized presentation，UI intent-first 且 Harness status/result/approval/UNKNOWN truth 独立。最终 API36 app targeted 19/19、storage reopen/export 9/9 通过。

- HXA-227 Eval evidence 与失败归因已完成主机范围交付，见[完成记录](../completion-records/HXA-227.md)与[主机证据](../evidence/development/hxa227-host-verification-2026-09-27.md)：三套 fixed adapter、八类设备轨迹入口、JSON/Markdown aggregator 和 fresh host boundary baseline 8/8；设备/真实 Provider not requested，不替代完整 device trajectory baseline。

- HXA-210 Workspace 已完成本地验收，见[完成记录](../completion-records/HXA-210.md)：单主目录身份与请求冻结、Path/SAF 能力、显式清理恢复已收口；完整 host gate、API29/API36 双渠道专项 192/192 与 HXA-227 host 对照 8/8 通过。Room 继续开发期 v1 baseline，仅保留文件；交付范围不代表完整 device trajectory baseline 或真实账号验收。

## In progress

- [HXA-126](tasks/HXA-126.md)：预注册 public-client OAuth 核心切片已整合，见[修复与验证](../bug-fixes/2026-09-21-connector-oauth-merge.md)；两家真实服务与动态注册仍未完成。

## Planned / deferred

- [HXA-230](tasks/HXA-230.md)：Markdown-native Hierarchical Agent Memory。Global/User + Project Markdown memory、progressive disclosure、Agent 主动维护；建议 HXA-227 baseline 后实施，完整 Project Memory 等待稳定 Project identity。
- [HXA-222](tasks/HXA-222.md)：设备内本地模型一等 Provider 已完成设计与开发计划，但**暂缓实施**。Provider contract 使用 provisioning × transport × residence × auth 正交维度；内置 llama.cpp 明确定义为 `:model-runtime` private-process 下的 Local Inference Runtime backend（Binder/typed IPC + JNI），不做 localhost inference server。Ollama/SGLang/vLLM/llama-server 只要通过 endpoint 调用都属于 Network transport；native/runtime/model asset 不进入当前主线。

结构治理见[结构审查](../research/modules/01-architecture-and-execution-engine.md)。HXA-223 已最终关闭 R4：未证明当前生产存在必现双 owner 终态 race，但 repository 的 stale snapshot 覆盖能力是真实结构风险，现已用 `expectedState + expectedStepCount` CAS fail closed；Turn terminal/review/recovery 与 Session next-work owner 已完成收口。

## Next task

HXA-210 实现已冻结。所有者于 2026-09-27 授权提交与推送 HXA-210/HXA-227；推送当前开发分支不等于合并 main、远端 CI 通过或发布。后续按以下顺序推进，每次只执行一个 checkpoint：

| 顺序 | 工作 | 交付与退出条件 | 启动条件 |
| --- | --- | --- | --- |
| 1 | HXA-227 device trajectory baseline 补充验收 | 以已提交源码固定 APK/fixture/environment；执行 core-device 的 Queue、Steer、Cancel/UNKNOWN、Review、Recovery、Goal、Compaction、Tool failure recovery 八类入口，保存 raw/JUnit、envelope 和覆盖分母；逐项说明 Room reopen 与实际 kill 的区别 | 先准备脚本与制品；API29/36、渠道与模拟器执行范围需本次专项明确授权，旧 Workspace 授权不外推 |
| 2 | 稳定性与全量设备基线收敛 | 先归因轨迹失败；产品缺陷补回归，fixture/环境问题独立记录。修复后重跑受影响项，再以当前清单核对历史 183-class baseline，形成固定制品完整基线；不以盲目重试或放宽断言换绿 | 完成第一步；全量设备范围另行明确授权；串行安排重负载 host gate 与 API36 模拟器 |
| 3 | 条件验收与发行准备 | 真实 Provider/Connector 按 HXA-125/126/190 独立 profile 验证；发行遵循 120→122→121→123，先确定渠道约束，再定 applicationId、签名及数据升级策略 | 真实账号/付费额度、物理设备及发行身份分别由所有者提供或授权；无输入保持未验，不消耗账号 |
| 4 | 再决定下一功能 | 根据基线失败、用户需求和验收成本选择下一 HXA；Memory 要重新核对显式 Project identity、权限与跨会话隔离，不因 Workspace 完成自动启用 | HXA-230 Memory、HXA-222 本地模型继续暂缓；开始实现须明确改变当前优先级 |

当前允许继续准备第一步的离线材料；本计划不自动启动设备、真实账号、发布或新的持续 Goal。保留 HXA-227 已完成的 host 范围记录，后续设备结果作为补充证据，不重启已交付的架构重构。HXA-126 的外部条件不阻塞独立本地工作。

使用[实施指南](implementation-guide.md)交接；任务规格保存范围，完成记录保存结果，不新增按执行者命名的长期指令。已结束交接的归属见[历史汇总](../evidence/development/completed-handoffs-2026-09-22.md)。开始 HXA 前解决强制基线失败，历史绿色不能替代当前验证。

## Blocked

| 项目 | 缺少条件 / 决策 |
| --- | --- |
| 16 KiB 物理硬件巡检 | 搭载 Android 15+ 的 16 KiB 页面物理硬件设备 |
| HXA-125 受保护 Connector | WorkBuddy 来源样本已补；仍需独立账号验证凭据无效、权限拒绝、厂商撤销及重连 |
| HXA-126 外部验收 | 两家独立服务账号、App 注册与 redirect 条件；动态注册未交付 |
| HXA-190 真实订阅 | Claude/Grok 付费调用账号不可用，按所有者决定暂缓；fixture 不算真实调用通过 |
| 发布验收 | HXA-122 稳定 applicationId、渠道命名、签名与升级路径待决定；发行顺序 120→122→121→123 |

设备、账号和发行条件项不阻塞无依赖的本地工作；会话目录绑定 ADR-WORKSPACE-004 已接受，HXA-210 已完成本地验收。

## Current interfaces

- **执行引擎**：TurnEngine 已统一拥有 fresh admission、AgentLoop live driver/observation、cancel、review park/resolve、terminal settlement、startup recovery 与 durable session gate；Turn state mutation 有 expected-state/step CAS；SessionWorkScheduler 单独仲裁 Queue 与 Goal continuation；TurnCoordinator 只保留 model/tool/compaction round checkpoint。Room 是 HXA-221 的 v1 clean-slate durable truth，old Turn execution-terminal + successor Turn continuation 已完成。
- **授权与 Goal**：209 实现用户选择的会话预设/CUSTOM、工具启用/禁用与执行前解析；208 按 ADR-GOAL-001 交付。模型及外部扩展不能授予权限；Goal persistence 不扩大 scope。Plan 审阅到执行见 192。
- **上下文与结果**：默认启动为 Conversation-first；RunControl/Expert/Skill/Connector/Permission 是 per-Session config，Turn 在 admission 再冻结；Other-conversation Reference 在 submission acceptance 冻结为 bounded immutable snapshot，不持 live Session pointer。Tool presentation 现在优先显示模型生成的 per-call intent，但 reserved metadata 在业务 validation/permission/effect/dispatcher 前 strip，Harness 继续独占 status/result/approval/UNKNOWN truth。大结果可按会话只读分页；模型请求与压缩统一容量准入并保留诊断。Goal、未知副作用和预算停止各有恢复路径；窗口默认值可为估算。
- **Runtime / Provider**：QuickJS 在非导出 isolated UID 服务；PRoot/订阅在 developer APK 私有进程、共享主 UID，经私有 Binder/PFD 交换有界数据。ADR-PROVIDER-001 已接受设备内本地模型作为一等 `ModelProvider`，能力满足时可直接驱动完整 Agent loop；具体本地推理 Runtime/模型资产尚未实现。后台 Job 与手动 PTY 所有权独立，未知副作用只对账、不自动重放。
- **应用能力**：手动文件管理与 Agent scope 分离；搜索、主题、准备、终端管理、导出不因 UI 存在而成为 Agent Tool。WebView 由浏览器 Activity owner 持有。
- **扩展**：MCP、Skill、A2A Client 经统一工具管线；A2A 是外部服务而非本地子 Agent。市场与离线签名索引不等于在线分发系统；OAuth 本地切片不等于外部服务验收。

## Known limitations

- **系统与长稳**：模拟器 24 小时相关测试及应用释放路径已有证据，但系统 JNI/Binder 根因仍 open，goldfish FD/UID-proxy Binder 维度不能由模拟器关闭；见[释放调查](../evidence/development/native-reference-release-trace.md)、[浏览器引用验证](../evidence/development/browser-controller-reference-verification.md)与[优化记录](../evidence/development/main-optimization-todo.md)。
- **物理设备**：Root 094/095 的 OnePlus API35 专项及[P0基线修复](../bug-fixes/2026-09-17-physical-p0-baseline.md)是固定源码证据；其他 OEM、低内存、热压、Doze、Root grant/revoke/loss 和真实 16 KiB 按矩阵单独验收。x86_64 静态制品不证明实际运行。
- **文件与 Runtime 恢复**：182 不承诺断电事务、字节续传、跨 Provider 原子性或自动后台队列；目标/备份变化需核查。订阅终态完整结果物化与真机资源压力仍有边界，见[授权/Runtime收敛](../bug-fixes/2026-09-18-authorization-runtime-convergence.md)。
- **模型与附件**：导入不等于模型理解；图片受视觉能力和预算约束，任意文档/音视频/OCR 不因现有管线而交付。真实 token 偏差与物理内存峰值未由本次压缩回归覆盖。
- **未来能力**：只读 Git 状态/diff 已有；完整持久 Git 写操作、remote Git/凭据、生产子 Agent/Workflow 未交付。accepted ADR 不等于实现完成。
- **发行与测试跳过**：consumer/developer debug 及 CI 不等于签名 release、渠道申报或商店审核。HXA-184 的外部材料 JVM 条件跳过、浏览器长稳/诊断设备条件跳过均未计通过。外部 profile 缺输入可明确跳过，提供但无效必须失败，见[公共验收规则](verification-matrix.md)。

历史 CI、分支合并、诊断与缺陷结果统一查[证据入口](../evidence/README.md)和完成记录；不在本页维护多份互相覆盖的提交/测试流水账。
