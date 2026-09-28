# 当前实施状态

更新：2026-09-28。此页只维护当前结论、下一步和未闭合边界；命令、制品和历史数字归完成记录与证据。现场 HEAD、工作树、远端及设备状态须重新核对。

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

- HXA-227 Eval evidence 与失败归因已完成主机范围交付，并完成所有者后续授权的模拟器补充验收，见[完成记录](../completion-records/HXA-227.md)与[设备证据](../evidence/development/hxa227-device-baseline-2026-09-27.md)：API29/API36 双渠道核心轨迹 40/40 方法、32/32 case；API36 consumer 当前 198 类中 172 PASS、10 专用 runner、16 条件跳过，原始方法 633 pass / 32 skip / 0 fail，0 crash。完整 host gate 通过；真实 Provider/真机未请求，SAF 历史间歇空列表仍未定根因。

- HXA-210 Workspace 已完成本地验收，见[完成记录](../completion-records/HXA-210.md)：单主目录身份与请求冻结、Path/SAF 能力、显式清理恢复已收口；完整 host gate、API29/API36 双渠道专项 192/192 与 HXA-227 host 对照 8/8 通过。Room 继续开发期 v1 baseline，仅保留文件；交付范围不代表完整 device trajectory baseline 或真实账号验收。
- 所有者随后要求 fork 复用来源 Workspace：新分支继承当前目录及子目录，权限仍用新会话默认值，后续绑定独立；原目录不可用时自动改用新空目录，会话设置支持更换及失败重试；详见[变更与验收](../evidence/development/recoverable-workspace-2026-09-27.md)。此次新行为的设备状态为 `not requested`，此前 HXA-210/HXA-227 设备结果不作替代。

- HXA-230 已完成 Global Memory 首版与 Project fail-closed API 的主机范围交付，见[完成记录](../completion-records/HXA-230.md)和[证据](../evidence/development/hxa230-memory-2026-09-27.md)：Markdown canonical、受限工具、按权限有界注入与管理 UI 已接入；完整 gate、Memory 6/6 与 HXA-227 8/8 host case 通过。Project production、真实模型效果与设备尚未验。

- HXA-222 本地模型首版已完成本地范围交付，见[完成记录](../completion-records/HXA-222.md)：类型化 Provider/Room v1、private-process Binder/JNI、资产/UI、可调 context、内存预检、模板 grammar 与真实能力探测已落地；完整 host gate、API36 生命周期及 320/360/412dp 大字体 UI 通过。4B/8K/8 GiB 固定任务 6 轮模型调用、6 次工具调用、产物回读和独立数值断言通过，约 14 分 46 秒；0.6B/1.7B 错误计算及首轮 4B 未完成仍保留。详见[收口证据](../evidence/development/hxa222-closeout-2026-09-28.md)与[会话分析](../evidence/development/hxa222-session-analysis-2026-09-28.md)。真机、真实远端账号、Android HTTP 下载端到端未验；未提交或推送。

## In progress


- [HXA-126](tasks/HXA-126.md)：预注册 public-client OAuth 核心切片已整合，见[修复与验证](../bug-fixes/2026-09-21-connector-oauth-merge.md)；两家真实服务与动态注册仍未完成。



结构治理见[结构审查](../research/modules/01-architecture-and-execution-engine.md)。HXA-223 已最终关闭 R4：未证明当前生产存在必现双 owner 终态 race，但 repository 的 stale snapshot 覆盖能力是真实结构风险，现已用 `expectedState + expectedStepCount` CAS fail closed；Turn terminal/review/recovery 与 Session next-work owner 已完成收口。

## Next task

所有者已授权按[剩余工作计划（2026-09-28）](remaining-work-plan-2026-09-28.md)继续执行。P0 post-HXA integration checkpoint 已完成变更归属审计与完整 `check-all.sh --all`；交叉的 Workspace recovery、Global Memory、Local Model 与 device/eval runner 不为漂亮历史强拆成不可构建中间态，收口证据见 [integration checkpoint](../evidence/development/post-hxa-integration-checkpoint-2026-09-28.md)。

Core Engine、Turn/Session owner、Dispatcher 与 permission/effect truth 继续冻结。P1 Memory + Workspace 增量设备验收已完成：API29/API36 × consumer/developer targeted matrix 各 31/31 通过，Memory 实际进程死亡后 Markdown、enabled setting 与 Prompt `UNTRUSTED` trust 恢复通过；Workspace fork/失效回退/旧 request binding 冻结与窄屏大字体通过。首次运行暴露的是测试夹具缺少真实 model-call 外键，修正 fixture 后全部通过；未修改 production。详见 [P1 设备证据](../evidence/development/p1-memory-workspace-device-acceptance-2026-09-28.md)。

P2 SAF bounded diagnosis 已完成且**当前无法稳定复现**：在固定 API36 consumer 制品上直接执行 1 次，再以独立 package-reset 执行 3 次 `FilesImportExportUiTest`，共 4/4 通过；没有因此修改 production SAF 策略。历史失败的 `files-saf-empty` 语义树仍保留，只能证明当时 UI 收到空 live-source 投影，不能证明 registry 丢失或瞬时 ContentResolver re-check 失败。详见 [P2 诊断证据](../evidence/development/p2-saf-bounded-diagnosis-2026-09-28.md)。

P3 本地模型安装最小闭环已完成。当前精选目录已按后续产品决策收敛为 **Qwen3 4B Instruct 2507 Q4_K_M** 单一主动支持模型，保留 ModelScope/Hugging Face pinned revisions；高级 URL/hash/size 导入仍可用。P3 当时使用 0.6B 做 Android HTTP cancel/resume 的历史设备证据继续保留，但不再作为当前精选模型或后续测试对象。统一下载器已覆盖空间预检、200 restart、精确 206 resume、hash/range/disk/duplicate/cancel/recovery，安装后自动 connection + capability probe，只有显式操作才绑定当前 Session。详见 [P3 证据](../evidence/development/p3-local-model-install-2026-09-28.md)。

P4 首次成功联合旅程已完成，未修改 production。owned API36 developer 从 `pm clear` 干净状态开始，经 P3 同一安装编排安装本地 GGUF、创建唯一当前 Session 与 managed Workspace，真实模型执行 `write`→`read` 两个工具调用并产生 durable artifact；setup 随后 `Process.killProcess`，新 PID 重开后同一 Session/model/Workspace/Turn/messages/tool timeline/artifact 全部恢复，2 秒观察窗内 Turn/model call/tool call 数量不变，确认无重复副作用。4B Instruct 2507 成功轮为 2 tool calls / 3 model calls / 6 persisted messages；artifact SHA 与 Workspace 文件一致。详见 [P4 证据](../evidence/development/p4-first-success-journey-2026-09-28.md)。

同一 P4 fixture 曾给出 0.6B 的失败对照；该事实作为历史证据保留，但按当前产品决策 **后续不再用 0.6B/1.7B 做主动测试**。设备内 4B 只承担最低可用证明：P3/P4 已证明安装、真实 Provider、基础 Tool 与恢复闭环，后续不再用本地模型承担长程 Harness 系统基线。

系统评测统一使用本机 SGLang：`http://localhost:30008/` / `Qwen3.8-27B`。Android emulator 通过 `10.0.2.2:30008/v1` 走正式 Provider；P5 复用既有 HXA-100 fixed eval，而不是新建第二套 Eval。详见 [Harness 系统基线](harness-system-baseline.md)。

P5/P6 第一轮已完成：合并后 API36 developer JS/Goal 定向 5/5；clean `b436247f` 完整 14/15（skill-003 冗余执行请求失败），base prompt 加入充分证据直接报告指导后 clean `99b7bee7` 完整 **15/15**。同模型、fixture、oracle 与 test APK；不修改权限或评测预算。见 [P5/P6 对照与局限](../evidence/development/p5-clean-baseline-and-p6-2026-09-28.md)。历史 goal-001 截断未复现，根因尚未确定；skill-003 仍有额外只读调用，单次对照不证明稳定性或因果提速。

新 Goal 的未自定义默认预算已扩大，保留用户已保存限额和现有 Goal；主机门禁及设备定向结果见 [预算与验收](../evidence/development/goal-budget-and-p5-targeted-2026-09-28.md)。下一步为 P7 联合恢复与支持/空间管理边界；不启动新架构或扩大工具功能面。

P7 第一批联合恢复已完成：同一 `99b7bee7` APK 在 API36 developer 上普通断言 26/26、专用进程死亡恢复 7/7；首次两类错误启动协议及修正完整保留，见 [P7 第一批证据](../evidence/development/p7-recovery-first-batch-2026-09-28.md)。当前下一工作为错误恢复 UI、支持诊断与空间管理边界核对；P7 整体、真机长稳和发行尚未完成。

后续 prompt/fixture v2 已完成：修正 files/Plan/Goal 报告指导，skill-003 最终请求改为报告既有拒绝并记录独立 fixture/hash；clean `fce488ce` **15/15**，skill-003 本次 0 工具调用。见 [v2 证据](../evidence/development/prompt-fixture-v2-2026-09-28.md)，不可与旧夹具比较提示词因果收益。

P7 UI 增量已交付：导出准备失败可就地重试；诊断与审计提供用户主动的无正文报告预览/复制，失败可重试，不自动上传。API36 developer 联合 **38/38**、consumer 新入口 **8/8**，双渠道主机 gate 通过；空间删除/共享引用边界已核对。见 [P7 UI 与空间证据](../evidence/development/p7-recovery-diagnostics-storage-2026-09-28.md)。后续空间管理进度见下段；历史截断与偶发多余调用仍开放，P7 不整体关闭。

P7 本地模型空间增量已交付：安装页展示已识别模型与可续传下载大小；残片清理需要明确确认，与下载互斥并拒绝过期确认，切换模型不再隐式删除旧下载。双渠道主机 gate 及 API36 developer **3/3**、consumer **3/3** 定向验证通过，见 [模型空间证据](../evidence/development/p7-model-storage-2026-09-28.md)。分类占用及后续清理修复见下段；应用总占用、资产发布中断临时文件与真机存储压力未完成。

P7 分类占用已交付：设置提供记录/大正文、私有工作目录/产物/元数据、Memory 的只读文件大小与删除范围说明；扫描有界、可取消，外部目录不扫描，受限时标记部分统计。核对时发现并修复孤立正文清理会跟随软链接的缺陷，保留保护期/引用检查并在删除前复核路径。扫描器 **5/5**、GC **7/7**、双渠道主机 gate，以及 API36 developer **3/3**、consumer **3/3** 通过，首次测试文本匹配失败与修正见 [分类空间证据](../evidence/development/p7-data-space-2026-09-28.md)。后续发布残留及压力回归进度见下段；不宣称应用总占用、真机或 P7 整体完成，历史输出截断与偶发多余调用仍开放。

P7 模型发布残留清理已补齐：发布副本纳入显式确认与占用，保留实例/revision/文件变化保护；资产 store **7/7**、下载器 **9/9**、双渠道主机 gate、API36 每渠道 **4/4**（各含 32 轮合成重开/清理/低空间循环）通过，见 [发布残留证据](../evidence/development/p7-publication-residue-2026-09-28.md)。实际发布中硬杀、真机满盘/长稳仍未验；历史截断、额外调用与系统根因不标关闭。所有者已授权下一阶段 **BFCL 诊断＋AndroidWorld 小规模试跑**，使用现有本机 SGLang 并单列公开协议适配边界，不追榜或扩大生产工具权限。

工具数量优化与公开评测试跑已完成候选验证：默认19个核心工具，已有有效自动化会话增至28，其余通过16项窗口按需发现，64上限及权限保持；修正 `ui.scroll` 的合法方向schema。隔离分支双渠道主机gate通过；BFCL模型直连小样本60/60，AndroidWorld API36适配0/2，主要受现有Settings/SystemUI保护限制，不是官方榜单分数。详见[公开评测试跑](../evidence/development/public-agent-pilot-2026-09-28.md)。当前正在为该候选执行clean提交上的完整15项P5回归；结果尚未确认，不以旧15/15代替。插件改动由另一端负责，本分支不包含插件实现。

| 顺序 | 工作 | 交付与退出条件 | 启动条件 |
| --- | --- | --- | --- |
| P5 | SGLang Harness 性能与任务质量基线 | 当前源码/APK 下，Files/JavaScript/Skills/Goal 共 15 个固定 case 形成独立 oracle、Turn/Tool 事实与端到端 elapsed 基线 | 使用 `localhost:30008` 的 `Qwen3.8-27B`；本地 4B 不再跑长程系统测试 |
| P6 | 证据驱动 Harness 优化 | 同 fixed dataset、同 SGLang 服务、同设备条件做 baseline/candidate A/B；只保留系统任务完成率或端到端耗时的真实收益 | 模型服务本身的 prefill/TTFT/decode/GPU 性能不混入 Harness 指标 |
| P7+ | hardening→内测→发行 | 错误可恢复、真实用户反馈后再进入 120→122→121→123 | Project Memory、Subagent 等不自动进入近期主线 |

HXA-125/126/190、物理设备/16 KiB 与发行政策属于条件线，有输入时独立验收，不阻塞无依赖本地工作。HXA-222 的历史 PROTOCOL 未保留原始回复，不声称唯一根因已定位；4B 一次固定任务通过也不外推为手机性能或普遍模型可靠性。

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
- **Runtime / Provider**：QuickJS 在非导出 isolated UID 服务；PRoot/订阅在 developer APK 私有进程、共享主 UID，经私有 Binder/PFD 交换有界数据。ADR-PROVIDER-001 已接受设备内本地模型作为一等 `ModelProvider`，能力满足时可直接驱动完整 Agent loop；本地 Runtime/模型资产已实现，host gate 与完整 loop/真实模型验收边界见 HXA-222。后台 Job 与手动 PTY 所有权独立，未知副作用只对账、不自动重放。
- **应用能力**：手动文件管理与 Agent scope 分离；搜索、主题、准备、终端管理、导出不因 UI 存在而成为 Agent Tool。WebView 由浏览器 Activity owner 持有。
- **扩展**：MCP、Skill、A2A Client 经统一工具管线；A2A 是外部服务而非本地子 Agent。市场与离线签名索引不等于在线分发系统；OAuth 本地切片不等于外部服务验收。

## Known limitations

- **SAF 稳定性**：来源移除 UI 曾间歇显示空来源列表，根因未定位。2026-09-28 P2 在冻结 API36 consumer 制品上做有界 4 次执行均未复现，因此未修改 production；历史失败、当前 live-source 链路与 bounded 诊断见 [HXA-227 补验](../evidence/development/hxa227-device-baseline-2026-09-27.md)和 [P2 证据](../evidence/development/p2-saf-bounded-diagnosis-2026-09-28.md)。不把“当前未复现”写成已修复。
- **系统与长稳**：模拟器 24 小时相关测试及应用释放路径已有证据，但系统 JNI/Binder 根因仍 open，goldfish FD/UID-proxy Binder 维度不能由模拟器关闭；见[释放调查](../evidence/development/native-reference-release-trace.md)、[浏览器引用验证](../evidence/development/browser-controller-reference-verification.md)与[优化记录](../evidence/development/main-optimization-todo.md)。
- **物理设备**：Root 094/095 的 OnePlus API35 专项及[P0基线修复](../bug-fixes/2026-09-17-physical-p0-baseline.md)是固定源码证据；其他 OEM、低内存、热压、Doze、Root grant/revoke/loss 和真实 16 KiB 按矩阵单独验收。x86_64 静态制品不证明实际运行。
- **文件与 Runtime 恢复**：182 不承诺断电事务、字节续传、跨 Provider 原子性或自动后台队列；目标/备份变化需核查。订阅终态完整结果物化与真机资源压力仍有边界，见[授权/Runtime收敛](../bug-fixes/2026-09-18-authorization-runtime-convergence.md)。
- **模型与附件**：导入不等于模型理解；图片受视觉能力和预算约束，任意文档/音视频/OCR 不因现有管线而交付。真实 token 偏差与物理内存峰值未由本次压缩回归覆盖。
- **未来能力**：只读 Git 状态/diff 已有；完整持久 Git 写操作、remote Git/凭据、生产子 Agent/Workflow 未交付。accepted ADR 不等于实现完成。
- **发行与测试跳过**：consumer/developer debug 及 CI 不等于签名 release、渠道申报或商店审核。HXA-184 的外部材料 JVM 条件跳过、浏览器长稳/诊断设备条件跳过均未计通过。外部 profile 缺输入可明确跳过，提供但无效必须失败，见[公共验收规则](verification-matrix.md)。

历史 CI、分支合并、诊断与缺陷结果统一查[证据入口](../evidence/README.md)和完成记录；不在本页维护多份互相覆盖的提交/测试流水账。
