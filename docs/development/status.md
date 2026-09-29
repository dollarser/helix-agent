# 当前实施状态

更新：2026-09-29。此页只维护当前结论、下一步和未闭合边界；命令、制品和历史数字归完成记录与证据。现场 HEAD、工作树、远端及设备状态须重新核对。

## Completed

- HXA-225 自主图片读取与工具视觉回填已于 2026-09-29 完成主机范围交付：`view_image`、浏览器视觉产物、三协议编码、精确披露、逐请求来源、窗口/压缩和有界字节复核均已接入；完整 host gate、双渠道 Debug/Release APK 与 AndroidTest APK 编译通过。见[完成记录](../completion-records/HXA-225.md)与[使用说明](../references/agent-image-reading.md)。没有新增表或清库，设备/真实模型/账号 `not requested`，新 APK 未安装或发布；不声称模型实际识别质量已验证。

- 非重构本地收口（2026-09-29）：状态和剩余计划已对齐，内测步骤及反馈材料已准备；补齐 Plugin/Mobile Use 依赖锁后，完整 `check-all.sh --all`（含 release 与制品边界、38 份锁）通过。见[收口记录](../evidence/development/non-refactor-closeout-2026-09-29.md)。本轮改动未提交；真实内测、设备/服务及发行验收不由本项替代。

- 所有者追加操作权限与循环优化：注册/Policy/审批/新审计取消 L0–L3，保留可信 effect、scope 与 ALLOW/ASK/DENY；新增持久结果驱动的无进展警告/停止，Goal 等待用户调整后继续。双通道 unit/lint/APK/test APK 与主机门禁通过；本轮设备 not requested。随后按 owner 要求与主目录 Mobile Use Plugin 工作本地集成，完整主机门禁再次通过，未推送；见[增量证据](../evidence/development/operation-permissions-loop-progress-2026-09-28.md)与[合并记录](../evidence/development/main-operation-integration-2026-09-28.md)。

- 所有者追加系统设置自动化修复：正式会话授权包含已安装系统设置/搜索组件，新增暂停确认恢复与有界原生滑块动作。主机gate、API36 UI恢复1/1通过；新Turn默认512工具轮/1024模型调用/3200万累计token（保留已有预算），最终亮度oracle2/2且两个Turn均COMPLETED。Plan执行入口同步改用当前Goal配置，API36执行闭环2/2通过。范围与历史失败见[修复记录](../evidence/development/automation-recovery-progress-2026-09-28.md)。

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

- HXA-227 Eval evidence 与失败归因已完成主机范围交付，并完成所有者后续授权的模拟器补充验收，见[完成记录](../completion-records/HXA-227.md)与[设备证据](../evidence/development/hxa227-device-baseline-2026-09-27.md)：API29/API36 双渠道核心轨迹 40/40 方法、32/32 case；API36 consumer 当前 198 类中 172 PASS、10 专用 runner、16 条件跳过，原始方法 633 pass / 32 skip / 0 fail，0 crash。完整 host gate 通过；该次真实 Provider/真机未请求；SAF 后续定向修复见当前限制。

- HXA-210 Workspace 已完成本地验收，见[完成记录](../completion-records/HXA-210.md)：单主目录身份与请求冻结、Path/SAF 能力、显式清理恢复已收口；完整 host gate、API29/API36 双渠道专项 192/192 与 HXA-227 host 对照 8/8 通过。Room 继续开发期 v1 baseline，仅保留文件；交付范围不代表完整 device trajectory baseline 或真实账号验收。
- 所有者随后要求 fork 复用来源 Workspace：新分支继承当前目录及子目录，权限仍用新会话默认值，后续绑定独立；原目录不可用时自动改用新空目录，会话设置支持更换及失败重试；详见[变更与验收](../evidence/development/recoverable-workspace-2026-09-27.md)。此次新行为的设备状态为 `not requested`，此前 HXA-210/HXA-227 设备结果不作替代。

- HXA-230 已完成 Global Memory 首版与 Project fail-closed API 的主机范围交付，见[完成记录](../completion-records/HXA-230.md)和[证据](../evidence/development/hxa230-memory-2026-09-27.md)：Markdown canonical、受限工具、按权限有界注入与管理 UI 已接入；完整 gate、Memory 6/6 与 HXA-227 8/8 host case 通过。Global 后续 API29/API36 双渠道设备与进程恢复已验，见 [P1 证据](../evidence/development/p1-memory-workspace-device-acceptance-2026-09-28.md)；Project production 与真实模型效果仍未验。

- HXA-222 本地模型首版已完成本地范围交付，见[完成记录](../completion-records/HXA-222.md)：类型化 Provider/Room v1、private-process Binder/JNI、资产/UI、可调 context、内存预检、模板 grammar 与真实能力探测已落地；完整 host gate、API36 生命周期及 320/360/412dp 大字体 UI 通过。4B/8K/8 GiB 固定任务 6 轮模型调用、6 次工具调用、产物回读和独立数值断言通过，约 14 分 46 秒；0.6B/1.7B 错误计算及首轮 4B 未完成仍保留。详见[收口证据](../evidence/development/hxa222-closeout-2026-09-28.md)与[会话分析](../evidence/development/hxa222-session-analysis-2026-09-28.md)。后续精选入口、真实 Android HTTP 安装与首次任务恢复分别见 [P3](../evidence/development/p3-local-model-install-2026-09-28.md)、[P4](../evidence/development/p4-first-success-journey-2026-09-28.md)。真机资源与普遍任务质量仍未验；已随当前本地 main 整合，未推送。

## 候选与有限接受范围

[候选需求与待裁决索引](candidate-decisions.md)统一导航未来需求、有限接受的设计及其实现边界；当前执行顺序仍由本页决定。2026-09-29 所有者明确本轮先收敛现有实现与文档，R1 保留为下一实施任务，不自动启用其他候选。本轮变更与主分支整合验证见[收敛证据](../evidence/development/contract-document-convergence-2026-09-29.md)；其他未提交工作保持独立归属。

## In progress

- 偶发问题追加收口（2026-09-29）：修复 SAF 撤销后不刷新及异步来源结果发布，双渠道 API36 10/10；澄清 Goal 仅规划提示，冻结版本三轮均 1 次模型调用/0 工具、Goal PAUSED。诊断能力探测 3/3、skill-003 3/3，但后者两轮仍有额外只读调用。全量主机 gate 通过；历史截断/探测偶发失败未复现、额外调用仍开放。失败与修复范围见[增量证据](../evidence/development/intermittent-closeout-2026-09-28.md)；已提交为 `70456eb5` 并快进到本地 main，未推送。

- [HXA-231](tasks/HXA-231.md)：所有者授权先做有界问题收口与当前基线，再实施 R1 原子工具绑定。前置收口修复模型发布最终读取后取消仍发布的问题；全量 host gate 通过，独占 API36 developer 13/13、consumer 4/4，实际发布中进程骤停与低空间注入分别留证，见[前置基线](../evidence/development/pre-r1-closeout-2026-09-28.md)。R1 尚未实现，任务保持开放；后续 R2/J1 根据内测反馈决定，不同时扩张。


- [HXA-126](tasks/HXA-126.md)：预注册 public-client OAuth 核心切片已整合，见[修复与验证](../bug-fixes/2026-09-21-connector-oauth-merge.md)；两家真实服务与动态注册仍未完成。



结构治理见[结构审查](../research/modules/01-architecture-and-execution-engine.md)。HXA-223 已最终关闭 R4：未证明当前生产存在必现双 owner 终态 race，但 repository 的 stale snapshot 覆盖能力是真实结构风险，现已用 `expectedState + expectedStepCount` CAS fail closed；Turn terminal/review/recovery 与 Session next-work owner 已完成收口。

## Next task

本地 main 已于 2026-09-29 快进至 `70456eb5`；归属清晰的修复、测试和研究输入已提交，未推送。当前非重构收口集中于事实对齐、主机发行门禁和[内测准备](internal-pilot.md)，不是新的产品功能开发。[剩余工作](remaining-work-plan-2026-09-28.md)只维护依赖和退出条件。

已完成的产品化阶段不重新排为开发任务：

- **P0/P1：整合与 Memory/Workspace**。本地整合检查点及 API29/API36 × 双渠道专项各 31/31，包含实际进程恢复、fork/目录失效和请求绑定。见 [整合](../evidence/development/post-hxa-integration-checkpoint-2026-09-28.md)、[P1](../evidence/development/p1-memory-workspace-device-acceptance-2026-09-28.md)。
- **P2：SAF**。此前 4 次未复现不是关闭依据；2026-09-29 已复现并修复撤销后不刷新及来源状态发布，双渠道 10/10。保留历史失败和设备范围，见[追加收口](../evidence/development/intermittent-closeout-2026-09-28.md)。
- **P3/P4：本地模型安装与首次成功**。精选入口当前聚焦 Qwen3 4B Instruct 2507 Q4_K_M，支持 ModelScope/Hugging Face 及高级导入；Android HTTP 安装、Provider/probe、write/read 产物和进程死亡重开已有证据。见 [P3](../evidence/development/p3-local-model-install-2026-09-28.md)、[P4](../evidence/development/p4-first-success-journey-2026-09-28.md)。0.6B/1.7B 只留历史，不继续主动测试。
- **P5/P6：系统基线与优化**。最新完整 clean 锚点仍为 `8f0aa933` 的 15/15；当前 main 的完整 P5 尚未刷新，2026-09-29 Goal 3/3 不能替代它。SGLang 测 Harness，本地 4B 只证明设备内最低能力，身份/统计口径见[系统基线](harness-system-baseline.md)。BFCL 小样本及 AndroidWorld 亮度适配仅是诊断，后者授权/预算修复见[自动化证据](../evidence/development/automation-recovery-progress-2026-09-28.md)，不作为官方榜单。
- **P7：本地恢复和支持切片**。恢复 UI、主动脱敏诊断、模型残片/分类空间、孤立正文保护与实际发布中断恢复已经实现并验收。见 [恢复](../evidence/development/p7-recovery-first-batch-2026-09-28.md)、[支持 UI](../evidence/development/p7-recovery-diagnostics-storage-2026-09-28.md)、[模型空间](../evidence/development/p7-model-storage-2026-09-28.md)、[分类空间](../evidence/development/p7-data-space-2026-09-28.md)、[发布残留](../evidence/development/p7-publication-residue-2026-09-28.md)、[实际发布中断](../evidence/development/pre-r1-closeout-2026-09-28.md)。剩余真机满盘、OEM/JNI/Binder 长稳和模型偶发边界单独保留，不把 P7 笼统写成全部完成。

按所有者 2026-09-29 指令，HXA-225 自主视觉已完成主机交付；后续继续 HXA-231 已授权 R1，本轮不夹带实施；非重构工作优先小范围试用与指定真机验证，再用实际反馈决定优化。内测步骤及反馈表已准备，真实用户试用尚未执行。Memory off/on 效果、本地模型真机性能与首发服务验收均不是已有设备恢复证据的自动延伸。完整 P5 在源码/APK 冻结并具备当前设备/服务授权后执行，不无限重复定向测试。

当前开发期 Room 继续 v1 baseline、仅保留文件的约定；正式数据升级、备份与签名身份归 HXA-122，不提前承诺。发行沿用 120 → 122 → 121 → 123；账号、真机和发行输入不阻塞无依赖本地工作。

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
- **Runtime / Provider**：QuickJS 在非导出 isolated UID 服务；PRoot/订阅在 developer APK 私有进程、共享主 UID，经私有 Binder/PFD 交换有界数据。ADR-PROVIDER-001 已接受设备内本地模型作为一等 `ModelProvider`，能力满足时可直接驱动完整 Agent loop；本地 Runtime/模型资产已实现，host gate 与完整 loop/真实模型验收边界见 HXA-222。后台 Job 与手动 PTY 所有权独立，未知副作用只对账、不自动重放。
- **应用能力**：手动文件管理与 Agent scope 分离；搜索、主题、准备、终端管理、导出不因 UI 存在而成为 Agent Tool。WebView 由浏览器 Activity owner 持有。
- **扩展**：MCP、Skill、A2A Client 经统一工具管线；A2A 是外部服务而非本地子 Agent。市场与离线签名索引不等于在线分发系统；OAuth 本地切片不等于外部服务验收。

## Known limitations

- **SAF 范围**：本轮可复现的撤销后残留与异步来源投影问题已修，API36 双渠道 10/10；见[修复证据](../evidence/development/intermittent-closeout-2026-09-28.md)。不把全部历史空列表或所有 OEM 根因归到同一问题；仅遇到新失败再开启对应诊断。
- **模型行为**：历史 OUTPUT_TOKEN_LIMIT、能力探测偶发失败本轮未复现，仍保留取证入口；skill-003 本轮两次额外只读调用仍是开放边界。Goal 仅规划提示修复的三轮成功不证明所有长程任务稳定。
- **系统与长稳**：模拟器 24 小时相关测试及应用释放路径已有证据，但系统 JNI/Binder 根因仍 open，goldfish FD/UID-proxy Binder 维度不能由模拟器关闭；见[释放调查](../evidence/development/native-reference-release-trace.md)、[浏览器引用验证](../evidence/development/browser-controller-reference-verification.md)与[优化记录](../evidence/development/main-optimization-todo.md)。
- **物理设备**：Root 094/095 的 OnePlus API35 专项及[P0基线修复](../bug-fixes/2026-09-17-physical-p0-baseline.md)是固定源码证据；其他 OEM、低内存、热压、Doze、Root grant/revoke/loss 和真实 16 KiB 按矩阵单独验收。x86_64 静态制品不证明实际运行。
- **文件与 Runtime 恢复**：182 不承诺断电事务、字节续传、跨 Provider 原子性或自动后台队列；目标/备份变化需核查。订阅终态完整结果物化与真机资源压力仍有边界，见[授权/Runtime收敛](../bug-fixes/2026-09-18-authorization-runtime-convergence.md)。
- **模型与附件**：导入不等于模型理解；图片受视觉能力和预算约束，任意文档/音视频/OCR 不因现有管线而交付。真实 token 偏差与物理内存峰值未由本次压缩回归覆盖。
- **未来能力**：只读 Git 状态/diff 已有；完整持久 Git 写操作、remote Git/凭据、生产子 Agent/Workflow 未交付。accepted ADR 不等于实现完成。
- **发行与测试跳过**：consumer/developer debug 及 CI 不等于签名 release、渠道申报或商店审核。HXA-184 的外部材料 JVM 条件跳过、浏览器长稳/诊断设备条件跳过均未计通过。外部 profile 缺输入可明确跳过，提供但无效必须失败，见[公共验收规则](verification-matrix.md)。

历史 CI、分支合并、诊断与缺陷结果统一查[证据入口](../evidence/README.md)和完成记录；不在本页维护多份互相覆盖的提交/测试流水账。
