# 当前实施状态

2026-10-01 阶段收尾：回放、OAuth 本地增量、R2-A、R3 与 J1 基础观察/等待形成已验证的本地主机候选；[源码/APK 身份、集成复核与交接](../evidence/development/phase-closeout-2026-10-01.md)。该候选仍是未提交工作树，不是下方 v0.0.4 发布内容或 clean P5。完整 J1 仍有观察上下文、可信类型进展判定与设备闭环未完成；本次不扩展 J2/Project Memory。

2026-10-01 v0.0.4 开发预览整合与回放记录保留修复完成主机及 API36 定向验证（21/21）；版本范围与发布限制见[验证记录](../evidence/development/v0.0.4-release-2026-10-01.md)。

2026-10-01 README 中英双语与 Antigravity Workspace 参数回填修复完成；files.list→读图、真实浏览器截图的 API36/模型定向结果见[验证记录](../evidence/development/files-vision-readme-2026-10-01.md)。

2026-10-01 会话上下文圆环、手动无收益压缩与 Antigravity `ask_user` 回放校验已完成定向修复；主机、API36 及所选真实模型的候选续答/压缩范围见[验证记录](../evidence/development/session-context-ask-user-2026-10-01.md)。

更新：2026-10-01。本页只维护当前基线、开放工作、下一步及限制；测试数字、命令、提交/安装过程留在链接记录。现场 HEAD、工作树、远端和设备须重新核对，不从历史“本轮未提交/未推送”推导当前状态。

所有订阅的账号/模型设置已按所有者要求统一收敛；取消默认模型，新会话仅继承当前精确模型，无绑定则为空。[实现与定向验证](../evidence/development/subscription-model-settings-2026-09-30.md)。

最新 Antigravity 定向修复：用户确认登录成功；API36 Developer 上精确模型 `gemini-3.8-flash-tiered` 已通过真实基础生成、会话选模和短聊天。修复 SSE 查询参数及 16-token 探测额度导致的假 PROTOCOL；[证据与边界](../evidence/development/antigravity-foreground-login-2026-09-30.md)。这不代表其他模型或工具/视觉能力已验证，下文较早“真实订阅未通过”保留为当时记录。

## Completed

当前整合基线包含恢复/QuickJS、历史正确性修复及 **HXA-231 R1 原子工具绑定**。主机、有界 API36 和真实 SGLang 的实际范围见[整合验证](../evidence/development/r1-device-service-closeout-2026-09-30.md)；它不是发布 APK、clean 正式 P5 或所有设备/任务通过的证明。

| 领域 | 已有能力与证据入口 | 不由此推导 |
| --- | --- | --- |
| 工具绑定 | [HXA-231](../completion-records/HXA-231.md)：单一 binding、请求/调度/审批/执行同源、全部来源迁移与撤销准入；[有效契约](../adr/tools/001-descriptor-contract.md) | 不再重做 R1；不等于完整 R2/R3/J1 |
| Core/恢复 | [HXA-220](../completion-records/HXA-220.md)、[221](../completion-records/HXA-221.md)、[223](../completion-records/HXA-223.md)：唯一 Engine 与 CAS；[HXA-234](tasks/HXA-234.md) 补齐唯一 Core Loop 和有界上下文迁移的本地主机交付 | 后续候选未提交；不等于设备全验、R2-B 收益或 JobObservation 自动纳入 |
| 输入、导航与活动 | HXA-214～219、[226](../completion-records/HXA-226.md)、[228](../completion-records/HXA-228.md)、[229](../completion-records/HXA-229.md)；[跨会话输入与联合回归](../evidence/development/merged-api36-regression-2026-09-29.md) | 不把历史输入/草稿问题重新列为未修复 |
| 自主视觉 | [HXA-225](../completion-records/HXA-225.md)：view_image、浏览器视觉回填、三协议编码和有界校验；[产品边界](../product/image-reading.md) | 有限工具看图验证不覆盖全部协议、手机整屏截图或设备内视觉 |
| Linux Job/终端 | [HXA-196](../completion-records/HXA-196.md)～[199](../completion-records/HXA-199.md)：后台 Job 与双 PTY；[HXA-236](tasks/HXA-236.md) 的原身份观察、jobs.await 和停止等待已接线并完成基础主机验证 | 完整 J1 的本地衔接与设备仍开放；AUTO/手动后台化尚未交付 |
| 扩展 | [HXA-129](../completion-records/HXA-129.md)、[130](../completion-records/HXA-130.md)、[212](../completion-records/HXA-212.md)；[HXA-235](tasks/HXA-235.md) 已接统一安装目录、会话选择与停用/更新/修复，主机通过 | 不重做 R3 主体；真实 Room/设备旅程尚未验收 |
| Workspace/Memory | [HXA-210](../completion-records/HXA-210.md)、[目录恢复](../evidence/development/recoverable-workspace-2026-09-27.md)、[HXA-230](../completion-records/HXA-230.md) | Global 已交付，完整 Project 接线仍未启用 |
| 设备内模型 | [HXA-222](../completion-records/HXA-222.md)、[收口](../evidence/development/hxa222-closeout-2026-09-28.md)、[安装](../evidence/development/p3-local-model-install-2026-09-28.md)/[首次使用](../evidence/development/p4-first-success-journey-2026-09-28.md) | 本地 Provider 可驱动完整 Loop，不等于所有设备与任务质量已验证 |
| Eval/整合 | [HXA-227](../completion-records/HXA-227.md)、[设备基线](../evidence/development/hxa227-device-baseline-2026-09-27.md)、[历史真机验收](../evidence/development/physical-oneplus-acceptance-2026-09-24.md) | 每份证据仅覆盖原基线；公共 benchmark 与生产 Harness 分开报告 |

2026-09-30 所有者追加的[输入布局与模型/推理入口收敛](../bug-fixes/2026-09-30-composer-layout.md)记录当前改动及主机/设备验证边界；[后续收口](../evidence/development/composer-oauth-closeout-2026-09-30.md)已完成 Consumer 24/24、Developer 19/19 定向验收，并移除内置 Antigravity OAuth 参数、清理未推送历史后正常推送。随后所有者接受恢复 Developer 实验性公开客户端默认值及针对性扫描例外，并重新授权真实 SGLang 与 API36；恢复提交已通过固定参数的 GitHub 针对性放行；真实 SGLang 连续压缩定向用例通过，过程中一次 UNKNOWN 回答仍保留偶发可靠性边界，结果见[复验记录](../evidence/development/public-client-sglang-revalidation-2026-09-30.md)。真实订阅不算通过。

最近的界面与输入修复分别见[压缩/Goal 隔离](../bug-fixes/2026-09-29-manual-compaction-goal-isolation.md)、[系统选择器与分享](../bug-fixes/2026-09-29-external-ui-recovery.md)、[输入恢复/Runtime](../bug-fixes/2026-09-29-interaction-recovery-audit.md)、[命令/语音入口](../bug-fixes/2026-09-29-slash-runtime-voice.md)、[交互/配置](../bug-fixes/2026-09-29-interaction-settings.md)。较早配置“建议后另确认”和自动化周期确认须结合 HXA-232 的后续变更理解，不能恢复为当前规则。

开发期 Room 仍采用 v1 baseline：不兼容库重建、数据库外文件保留，兼容库重开不清空。验证与实际工具看图见[最终收口](../evidence/development/final-closeout-2026-09-29.md)；正式数据升级和签名身份归 HXA-122。

其他已交付工作查[完成记录索引](../completion-records/index.md)和[M0](../completion-records/M0.md)。[分支收敛](../evidence/development/branch-convergence-2026-09-29.md)、[权限/循环早期增量](../evidence/development/operation-permissions-loop-progress-2026-09-28.md)、[主目录整合](../evidence/development/main-operation-integration-2026-09-28.md)及[自动化恢复早期记录](../evidence/development/automation-recovery-progress-2026-09-28.md)仅作历史证据。

## 候选与有限接受范围

[候选索引](candidate-decisions.md)集中记录有限接受、已有基础和未排期能力。R1 已交付。2026-10-01 所有者明确要求依次完成审查列出的剩余工作，包含 R2-A/Core、R3/插件生命周期、J1/J2、Project Memory 的实施推进；按下方当前顺序逐项建立具体任务和验收。R2-B 仍须等价基线与收益实验，不因授权预报收益；未排期的 PDF/视频/子 Agent/远程等不是此次自动扩展目标。不重开已完成的生命周期、输入或视觉任务。

## 本轮架构交付

R1 的生产迁移、全部来源切换、交错反例和验证已由 [HXA-231 完成记录](../completion-records/HXA-231.md)承接；这里不另维护迁移待办或第二份测试清单。

## In progress

所有者追加 [HXA-241](tasks/HXA-241.md)：历史模拟器问题复核的主机与 API36 / ARM64 模拟器专项验收已通过，所有者已授权本地提交；Git 提交状态以当前日志为准，不推送。两渠道普通矩阵各 18 类 / 86 方法，以及适用的恢复断点、共享存储、队列/草稿/编辑重发和真实本地模型首次产物场景均有最终源码/APK 对应证据；覆盖原报告 30 个失败类，不等于全量 782 用例、真机、16 KiB 或 FFmpeg 执行验收。已修复失败标签、补齐工具并行依赖提示，并收敛测试/宿主驱动与结果真实性；逐类判断、22 项新增主机回归及设备证据见[复核记录](../evidence/development/hxa241-emulator-sweep-reconciliation-2026-10-02.md)，不改其他代理的任务排序。

所有者追加 [HXA-240](tasks/HXA-240.md)：FFmpeg 改为复用 Advanced 已有 Bash/Linux Job/PTY，以显式 Bionic CLI 桥暴露裁剪能力；撤回未验收的独立媒体运行时与五预设接口。标准版不增加 PRoot/FFmpeg；原 Job 身份、权限、日志、取消和结果收取保持。当前实现、联合主机/制品验证已通过，27 项新增主机检查与包体结果见[交付记录](../evidence/development/hxa240-ffmpeg-proot-2026-10-02.md)；3 项设备回归仅编译，设备未请求，不改变其他代理的 HXA 顺序。

2026-10-02 当前优先 [HXA-239](tasks/HXA-239.md)：未选模型前阻止发送并保留草稿、指令补全同时定位光标、输入框底部当前会话权限直接切换。三项本地实现及完整适用主机整合已通过，8 项新增 JVM 回归通过，转收尾验收；[交付记录](../evidence/development/hxa239-composer-2026-10-02.md)保留设备未执行边界。不扩 J2/Project Memory。前置 [HXA-238](tasks/HXA-238.md) 已提交为 `9d0ab5c0`，未推送；原[证据记录](../evidence/development/hxa238-execution-provider-2026-10-02.md)中的未提交状态是当时快照，不再代表当前工作树。

所有者追加的 [HXA-237](tasks/HXA-237.md) 已完成订阅测试引导、Codex 客户端兼容版本输入、终端帮助/字体/键盘及开发者入口的本地主机交付，见[验证记录](../evidence/development/hxa237-subscription-terminal-2026-10-01.md)；设备/真实服务未执行，保留收尾验收。上一阶段已保存为 `aca92e11`，未推送；阶段证据中的未提交状态是当时快照，不再作为当前事实。

本轮本地主机与文档阶段收尾见[阶段记录](../evidence/development/phase-closeout-2026-10-01.md)。[HXA-126](tasks/HXA-126.md)、[233](tasks/HXA-233.md)、[234](tasks/HXA-234.md)、[235](tasks/HXA-235.md) 为本地交付后的收尾验收，不重复实施主体。[HXA-236](tasks/HXA-236.md) 已补统一 JobObservation 上下文、可信类型进展判定及联合回归接线，当前为收尾验收；[后续记录](../evidence/development/hxa236-context-progress-2026-10-01.md)维护最新主机结果与设备/真实模型边界。同步修复用户反馈 v0.0.4 的一项[终端提交前占用泄漏](../bug-fixes/2026-10-01-execution-busy-pre-submit.md)，不把所有 BUSY 当作缺陷或强制释放未知执行。J2/Project Memory 尚未启动。

| 开放工作 | 已有基础 | 仍需处理的范围 |
| --- | --- | --- |
| [HXA-239 会话输入](tasks/HXA-239.md) | 选模前置、指令光标与底部权限快捷入口已接线，主机与双渠道构建通过 | 指定设备 IME、窄屏/大字体、会话切换与实际设置恢复；5 项新增设备场景及原旅程仅编译 |
| [HXA-238 执行与表单](tasks/HXA-238.md) | 多执行身份、实际引擎/容量协调、PRoot Job/终端共存、Provider 滚动/缺项定位已接线 | 本地主机/制品已通过；指定设备并发、取消恢复及窄屏/键盘旅程仍需补验，真实模型结果不由主机代替 |
| [HXA-236 J1](tasks/HXA-236.md) | 原身份观察/等待、同一 ContextCompiler 状态投影、可信 Dispatcher 进展证据已接生产；[后续验证](../evidence/development/hxa236-context-progress-2026-10-01.md) | 指定设备真实 Room/任务/取消恢复组合及真实模型效果；代码编译和主机通过不关闭这些范围 |
| [HXA-233 回放生命周期](tasks/HXA-233.md) | 引用感知清理、归属/分支/在途保护、存储反馈及 24 项新增主机回归已通过；[当前证据](../evidence/development/hxa233-replay-lifecycle-2026-10-01.md) | 8 项设备场景已编译但未执行；保守保留未知归属，不用主机证明进程/真机验收 |
| [HXA-232 自主恢复](tasks/HXA-232.md) | 自动化授权内恢复、Goal 局部额度衔接、无进展收尾、Runtime 查询/收取、UNKNOWN 只读核查、原 Goal 账本、队列重验证、统一授权；QuickJS 原生总开关、结构化反问与提示词资源化已有实现及有界验证 | 真实模型恢复完成率、完整进程/订阅/PRoot 故障矩阵及 OEM；不把诊断完成或有限场景通过当作原任务成功 |
| [历史正确性收口](../bug-fixes/2026-09-30-historical-correctness-audit.md) | Provider 探测发布/取消、压缩参数、Git、内容发布/删除并发和执行线程容量已有修复，纳入整合基线 | 仅按记录中遗留问题和新证据继续，不重新执行整个历史缺陷清单 |
| [偶发问题](../evidence/development/intermittent-closeout-2026-09-28.md) | 可复现 SAF 撤销/异步投影和 Goal 仅规划问题已修 | 历史截断、探测偶发失败与额外只读调用仍保留调查边界；未复现不等于根因关闭 |
| [HXA-126](tasks/HXA-126.md) | issuer/resource、准备取消、CIMD/显式 DCR、持久归属及配置修复已通过主机集成；[新增 24 项回归](../evidence/development/hxa126-client-registration-2026-10-01.md) | 新 UI 设备验证、两家真实服务、自有域名/App Link/签名仍需输入 |

2026-09-30 的 [Runtime 故障矩阵修复](../evidence/development/runtime-fault-matrix-2026-09-30.md)覆盖原身份恢复、取消/退出区分、IPC/PFD 失败和有界执行通道；源码与主机结果不替代实际进程 kill、OEM 和真实账号验收。

HXA-232 的逐轮主机/设备证据留在任务文件，不在本页复制。HXA-223 的 stale snapshot/CAS 修复已完成，不再作为开放 R4 缺陷；具体结构边界见[结构研究](../research/modules/01-architecture-and-execution-engine.md)。

2026-09-30 所有者追加的 [Provider 模型管理统一](../evidence/development/provider-model-management-2026-09-30.md)将三类来源的目录、候选、默认与逐模型验证分开；主机/设备状态以该记录为准，不扩大订阅目录或真实账号验收结论。

随后所有者授权的 [Provider 使用链路收口](../evidence/development/provider-chain-closeout-2026-09-30.md)补齐真实应用回执、来源/模型失败分离、账号状态同步与精确模型请求；提交和最终验证以记录为准。设备旅程已准备，不据此声称实际内测通过。

2026-09-30 上述 Runtime/Provider 收尾已形成 `15b89830`。所有者追加的 [Antigravity 与订阅渠道边界](../evidence/development/subscription-antigravity-2026-09-30.md)保留五个账号入口并固定排序；consumer 只支持 API/本地模型。Kimi/MiniMax 的 Key 型套餐归 API。新增接入的主机、制品和真实账号边界以该记录为准，不用旧绿色替代。

随后获授权的 [Runtime / Provider 设备收口](../evidence/development/runtime-provider-device-closeout-2026-09-30.md)补验真实 SGLang UI、Provider 完整旅程和七组有界进程死亡/结果恢复，修正旧测试对模型管理、压缩和自动核查的预期。订阅使用合成任务，不能据此关闭真实账号或完整平台故障矩阵。

## Next task

HXA-239 本地实现和主机整合完成；下一独立开发仍按 J2-1 AUTO → J2-2 用户后台按钮 → Project Memory 推进，不重做 HXA-238 或 HXA-236 基础 join、上下文/进展以及 R2-A/R3。前置检查点 `9d0ab5c0` 不自动证明本轮输入体验，239 使用自己的交付证据；固定 P5 仍需选定 clean 提交、指定设备和模型，真实内测另需试用者。

| 顺序/分支 | 剩余工作 | 依赖与退出条件 |
| --- | --- | --- |
| 1．HXA-239 / 238 / 236 收尾验收 | 输入体验与执行接线已有主机交付，实际设备/模型仍待补 | 原身份与未知事实仍可查，但不阻挡普通无关任务；不把放通执行变成放宽权限，也不由编译证明手机验收 |
| 2．J2-1 → J2-2 | 同次执行 AUTO，再接“继续在后台”按钮 | 原执行身份、日志、预算与租期不变；证明无重新启动/重复副作用，取消/终态竞争与 UI 回执明确 |
| 3．Project Memory | 显式项目身份、会话关联、请求冻结、隔离及管理接线 | Global 已有；不能用 Workspace 路径代替 Project，验证切换/fork/撤销/跨项目污染 |
| 条件性补验 | HXA-232 故障矩阵/真实恢复；233/234/235/236/237/238/239 设备；125/126/190 服务；同候选 P5/独立内测 | 指定设备、账号及当次授权；缺条件只阻塞对应项，不把 fixture、编译或旧绿色当通过 |
| 有证据再做 | R2-B、工具发现/Mobile Use、模型/Memory 效果与长稳 | 固定对照或实际瓶颈；区分完成率、时延、成本、资源与 OEM，不凭减少 token 宣称提升 |
| 发行 | HXA-120 → 122 → 121 → 123 | 渠道审计→身份/签名/升级承诺→同签名候选验收→材料/提交；发布另需明确授权 |

上述顺序是原授权工作的阶段更新，不新增未排期 PDF/视频/子 Agent/远程能力。真实用户试用尚未执行，P0～P4、P7 和 R1 的已有交付不重排为底座开发。

后续结构与功能投入由实际瓶颈决定，选择方法见[开发策略](feature-refactor-strategy.md)，目标契约见[Harness 方案](../architecture/harness-refactor-plan.md)，非重构依赖见[内测](internal-pilot.md)和[发行就绪](release-readiness.md)。账号、真机和发行输入不阻塞无依赖的本地工作。

最近记录的 clean 正式 P5 来自 `92e93bf5`，固定 15/15 与准备 smoke 1/1 通过，详见[Runtime / Provider 设备收口](../evidence/development/runtime-provider-device-closeout-2026-09-30.md)和[系统基线](harness-system-baseline.md)。此前 `8c7a95b4` 和各 candidate 证据保留；新改动不自动继承历史绿色。

## Blocked

| 项目 | 缺少条件 / 决策 |
| --- | --- |
| 16 KiB 物理巡检 | Android 15+、16 KiB 页面物理硬件 |
| HXA-125 受保护 Connector | 独立账号下的凭据无效、权限拒绝、厂商撤销及重连；WorkBuddy 来源样本已有 |
| HXA-126 外部验收 | 两家服务账号、App 注册与 redirect 条件；动态注册及 CIMD 本地实现已交付，自有 HTTPS/App Link 与真实服务仍待输入 |
| HXA-190 真实订阅 | Claude/Grok 付费账号不可用，按所有者决定暂缓；fixture 不替代 |
| 发布验收 | HXA-122 的稳定 applicationId、渠道命名、签名与升级路径；保持 120 → 122 → 121 → 123 的发行顺序 |

## Current interfaces

职责与调用路径统一查[当前总体架构](../architecture/overview.md)。TurnEngine 是 live driver 和 durable 生命周期 owner；SessionWorkScheduler 仲裁 Queue/Goal，TurnCoordinator 保留 round checkpoint。R1 绑定契约见[工具 ADR](../adr/tools/001-descriptor-contract.md)。

会话配置在 Turn 准入冻结；跨会话引用在输入接受时形成有界快照。模型 per-call intent 只作展示，不进入业务参数、审批或效果事实；大结果分页与模型/UI 投影保持分离。具体规则见[上下文](../adr/agent/002-context-compaction.md)、[结果/预算](../adr/agent/006-model-data-budget-boundaries.md)、[Goal](../adr/goal/001-lifecycle-and-completion.md)。

QuickJS 默认 isolated UID，原生总开关对应共享应用 UID 私有进程；PRoot/订阅也不声称凭据隔离，见[执行域](../architecture/local-code-execution.md)。后台 Job/手动 PTY 独立，A2A Client 不等于本地子 Agent；手动文件/配置/任务控制不必创建模型 Turn，WebView 仍由浏览器 Activity owner 持有。

## Known limitations

| 范围 | 当前保留边界与证据 |
| --- | --- |
| SAF/模型偶发行为 | 已修复项不重开；其他 OEM 空列表、OUTPUT_TOKEN_LIMIT、探测偶发失败、skill-001/003 多余调用保留原轨迹，见[偶发记录](../evidence/development/intermittent-closeout-2026-09-28.md)与[最终收口](../evidence/development/final-closeout-2026-09-29.md) |
| 系统与长稳 | JNI/Binder、goldfish FD/UID-proxy Binder 根因未全部关闭；[释放调查](../evidence/development/native-reference-release-trace.md)、[浏览器核验](../evidence/development/browser-controller-reference-verification.md)、[剩余调查](../evidence/development/main-optimization-todo.md) |
| 真机/OEM/资源 | OnePlus API35 Root 与[P0修复](../bug-fixes/2026-09-17-physical-p0-baseline.md)是固定范围；其他 OEM、低内存、热压、Doze、Root grant/revoke/loss、16 KiB 另验；x86_64 静态制品不证明运行 |
| 文件与 Runtime 恢复 | HXA-182 不承诺断电事务、字节续传、跨 Provider 原子性或自动后台队列；目标/备份变化需核查。订阅结果物化与资源压力的历史边界结合 HXA-232 后续切片判断，见[原记录](../bug-fixes/2026-09-18-authorization-runtime-convergence.md) |
| 模型与附件 | 导入不等于理解；图片受视觉能力/预算约束；任意文档、音视频、OCR 未因管线存在而交付；真实 token 偏差与物理内存峰值仍须测量 |
| 未来能力 | 完整 Project Memory、持久 Git 写操作/远程凭据、生产子 Agent/Workflow 未交付；其余见候选索引，accepted 不等于实现完成 |
| 发行/测试跳过 | debug/CI 不等于签名 release、商店审核或渠道验收；HXA-184 外部材料、长稳和设备条件跳过不计通过；外部 profile 缺失与无效分开，见[公共验收](verification-matrix.md) |

历史命令、测试失败、提交与安装快照继续保存在原[完成记录](../completion-records/index.md)和[证据](../evidence/README.md)，不因本页收短而删除或扩大验证结论。
