# 当前实施状态

更新：2026-09-30。此页只维护当前结论、下一步和未闭合边界；命令、制品和历史数字归完成记录与证据。现场 HEAD、工作树、远端及设备状态须重新核对。

本次整合提交包含此前恢复/QuickJS、历史正确性修复和 HXA-231 R1；追加 API36 Consumer 49/49、Developer 50/50、真实 SGLang 15/15 及完整主机门禁，见[最终证据](../evidence/development/r1-device-service-closeout-2026-09-30.md)。旧条目中的未提交/未推送描述保留其记录时点，不代表当前远端状态；此次不是发布 APK 或 clean 正式 P5。

## Completed

- 2026-09-29 手动压缩 Goal 隔离：`/compact` 及其重试不再创建/绑定 Goal 或触发自动续跑，保留目标状态、用量与会话模式。双渠道主机定向检查与 API36 各 27/27 通过；范围及中间失败见[修复记录](../bug-fixes/2026-09-29-manual-compaction-goal-isolation.md)。本轮未提交/推送，已发布 APK 尚不包含此修复；真机 not requested。

- 2026-09-29 系统 UI 恢复收口：补齐文件/照片/产物导出选择器失败提示、分享拒绝与重试反馈；许可证页适配系统栏、独立滚动和大字体。双渠道主机检查通过，API36 consumer 3/3、developer 4/4，追加 320dp 大字体 1/1；详见[记录](../bug-fixes/2026-09-29-external-ui-recovery.md)。本次本地提交纳入，未推送，真机 not requested。

- 2026-09-29 输入恢复与 Runtime 维护审查：修复删除入口标签/动作不一致、语音与附件跨会话回填、失效附件阻挡本地命令、维护结果丢失及当前模式不可见；双渠道主机检查通过，API36 consumer 21/21、developer 会话/输入 21 项及 Runtime 追加 2/2 通过。中间失败、测试夹具修正与未修复旧问题见[审查记录](../bug-fixes/2026-09-29-interaction-recovery-audit.md)。本次本地提交纳入，未推送；真实语音和手机 not requested。

- 2026-09-29 输入命令与 Runtime 入口调整：前轮交互修复已本地提交 `5c192d96`，本轮追加命令先编辑再发送、新会话默认 ACT、Runtime 去重/Advanced 提示与系统语音入口修正。双渠道主机与 API36 consumer 18/18、developer 19/19 定向验收通过；详细范围及语音后续计划见[记录](../bug-fixes/2026-09-29-slash-runtime-voice.md)。追加修改纳入本次本地提交，未推送。

- 2026-09-29 交互与配置入口收口：抽屉按实际窗口取 2/3、显式打开，保留逐级返回与历史搜索；调整输入区布局、增加 Provider 协议选择、终端独立目录，修复重新生成主线程读库及草稿刷新/启动恢复竞争。新增 `helix.settings` 查询和待确认建议，不能授予权限或修改当前 Turn。双渠道 unit/lint/APK/test APK 与定向 API36 通过边界见[收口记录](../bug-fixes/2026-09-29-interaction-settings.md)；保留中间失败及修正后的复验，不声称全产品验收。改动已本地提交、未推送，真机 not requested。

- 2026-09-29 最终本地收口：生产修复已提交 `8c7a95b4`，全量主机门禁及双渠道 AndroidTest APK 通过；clean P5 完整15/15、准备1/1，追加实际 Agent 工具图片识别1/1，见[最终收口](../evidence/development/final-closeout-2026-09-29.md)。未推送；手机启动已修复，但后续实际流程因设备断连仍 pending。

- 2026-09-29 开发期覆盖升级：按所有者要求，Room v1 identity 不兼容时直接删库重建，数据库外文件保留；不增加迁移或恢复 UI。storage JVM、AndroidTest APK、developer APK、spotlessCheck/detekt 通过；PLC110/API35 定向 FreshSchemaDeviceTest 2/2，修复 APK 已覆盖安装并启动。旧会话及配置不保留；兼容库重开不清空。本轮纳入最终收口提交，未推送。

- 合并后 API36 定向回归（2026-09-29）：修复 Activity 输入快照的跨会话恢复边界；双渠道功能76/76、大字体24/24、实际进程恢复均通过，本机 SGLang 兼容代理的三协议9/9通过。见[设备证据](../evidence/development/merged-api36-regression-2026-09-29.md)。本轮修复纳入最终收口提交；不替代全量设备、原生服务端接口或真实视觉识别验收。

- 2026-09-29 分支收敛：主目录视觉/输入恢复工作与 Provider 设置/导航改动已提交并合并至本地 main，发布分支保留；重复开发工作树已归档。联合验证及 benchmark 待整合边界见[分支收敛记录](../evidence/development/branch-convergence-2026-09-29.md)。本轮未推送。

- HXA-225 已交付 `view_image`、浏览器视觉回填、三协议编码与有界校验，见[完成记录](../completion-records/HXA-225.md)和[使用说明](../product/image-reading.md)。初次主机交付的设备/模型 `not requested` 保留原义；后续 API36 developer 的实际工具看图 1/1 单列于[最终收口](../evidence/development/final-closeout-2026-09-29.md)，不推广为浏览器、所有协议或任意视觉任务已验。

- 非重构收口、权限/循环优化与自动化恢复的历史主机、设备结果分别见下列证据。它们不是当前工作树全绿证明；不重复复制每次测试数、数据库表数、提交与安装状态。

- 所有者追加操作权限与循环优化：注册/Policy/审批/新审计取消 L0–L3，保留可信 effect、scope 与 ALLOW/ASK/DENY；新增持久结果驱动的无进展警告/停止，Goal 等待用户调整后继续。双通道 unit/lint/APK/test APK 与主机门禁通过；本轮设备 not requested。随后按 owner 要求与主目录 Mobile Use Plugin 工作本地集成，完整主机门禁再次通过，未推送；见[增量证据](../evidence/development/operation-permissions-loop-progress-2026-09-28.md)与[合并记录](../evidence/development/main-operation-integration-2026-09-28.md)。

- 所有者追加系统设置自动化修复：正式会话授权包含已安装系统设置/搜索组件，新增暂停确认恢复与有界原生滑块动作。主机gate、API36 UI恢复1/1通过；新Turn默认512工具轮/1024模型调用/3200万累计token（保留已有预算），最终亮度oracle2/2且两个Turn均COMPLETED。Plan执行入口同步改用当前Goal配置，API36执行闭环2/2通过。范围与历史失败见[修复记录](../evidence/development/automation-recovery-progress-2026-09-28.md)。

全部已交付 HXA 见[完成记录索引](../completion-records/index.md)，M0 见[工程基线](../completion-records/M0.md)。完成仅限记录中的范围，不代表全部产品、真实账号或发行验收。

已交付能力按领域查证，不把旧表数、旧风险分级或旧设备占用复制成今天的状态：

| 领域 | 交付入口与保留范围 |
| --- | --- |
| Core/恢复 | [HXA-220](../completion-records/HXA-220.md)、[221](../completion-records/HXA-221.md)、[223](../completion-records/HXA-223.md)；唯一 Engine、successor Turn 与开发期 baseline，后续 R1 不是重做这些任务 |
| 输入、导航与活动展示 | HXA-214～219、[226](../completion-records/HXA-226.md)、[228](../completion-records/HXA-228.md)、[229](../completion-records/HXA-229.md)；后续输入修复与联合证据见上方 |
| Linux Job/手动终端 | [HXA-196](../completion-records/HXA-196.md)～[199](../completion-records/HXA-199.md)；后台 start/status/cancel/collect 和双 PTY 已有，不等于通用 await/AUTO |
| 扩展 | [HXA-129](../completion-records/HXA-129.md)、[130](../completion-records/HXA-130.md)、[212](../completion-records/HXA-212.md)；安装/市场基础不等于 R3 全部完成 |
| Workspace/Memory | [HXA-210](../completion-records/HXA-210.md)、[目录恢复增量](../evidence/development/recoverable-workspace-2026-09-27.md)、[HXA-230](../completion-records/HXA-230.md)；Global 已交付，完整 Project 接线仍未启用 |
| 本地模型 | [HXA-222](../completion-records/HXA-222.md)、[收口](../evidence/development/hxa222-closeout-2026-09-28.md)、[P3](../evidence/development/p3-local-model-install-2026-09-28.md)/[P4](../evidence/development/p4-first-success-journey-2026-09-28.md)；设备内最低能力与真机普遍质量分开 |
| Eval/整合 | [HXA-227](../completion-records/HXA-227.md)、[设备证据](../evidence/development/hxa227-device-baseline-2026-09-27.md)、[历史物理验收](../evidence/development/physical-oneplus-acceptance-2026-09-24.md)；每份证据仅覆盖原基线 |

其他交付从完成记录索引进入。历史 191～219 的已完成切片、旧 Room 迁移测试和旧 UI 基线不因本页精简而重新打开；其命令、失败与验收数字仍在原完成/证据记录中。

## 候选与有限接受范围

[候选需求与待裁决索引](candidate-decisions.md)统一导航未来需求、有限接受的设计及其实现边界；当前执行顺序仍由本页决定。2026-09-29 的文档收敛已结束；2026-09-30 所有者进一步授权完整 R1，不自动启用其他候选。本轮变更与主分支整合验证见[收敛证据](../evidence/development/contract-document-convergence-2026-09-29.md)；其他未提交工作保持独立归属。

## 本轮架构交付

- [HXA-231](../completion-records/HXA-231.md)：所有者授权先做有界问题收口与当前基线，再实施 R1 原子工具绑定。前置收口修复模型发布最终读取后取消仍发布的问题；全量 host gate 通过，独占 API36 developer 13/13、consumer 4/4，实际发布中进程骤停与低空间注入分别留证，见[前置基线](../evidence/development/pre-r1-closeout-2026-09-28.md)。2026-09-30 已完成 R1 原子绑定生产迁移与完整主机门禁，见[当前证据](../evidence/development/hxa231-atomic-binding-2026-09-30.md)；随后授权的设备/服务回归已完成，见页首最终证据；后续 R2/J1 根据内测反馈决定，不同时扩张。

## In progress

- 2026-09-30 历史正确性审查：修复 Provider 过期探测发布/取消、压缩实际模型参数、Git 区域/首次提交/错误/预算、内容发布与删除并发，以及真实执行线程容量；范围、验证及遗留问题见[收口记录](../bug-fixes/2026-09-30-historical-correctness-audit.md)。随后按所有者要求实施 HXA-231 R1，工具搜索另补精确命中/零命中保留；完整插件生命周期未启动；随后有界设备和真实服务回归通过，纳入本次整合提交。

- [HXA-232](tasks/HXA-232.md)：2026-09-29 所有者授权自主恢复优化，已有交付边界如下。自动化已授权目标恢复、Goal 局部额度续跑、无进展收尾及空响应网络退避重试首批代码通过主机 gate，见[实施记录](../bug-fixes/2026-09-29-autonomous-recovery.md)。2026-09-30 追加 QuickJS 用户原生总开关、持久结构化反问、提示词资源化和原 Runtime 结果有界自动收集，见[增量记录](../evidence/development/quickjs-questions-host-2026-09-30.md)。后续修复反问工具注册 schema、慢订阅任务轮询与已收集输出保留，并接入配置修改工具授权；已补齐原执行器查询、持久去重只读核查及原 Goal 账本绑定、队列自动重验证/明确失败终态与外发统一授权交互。不能证明的副作用保留 UNKNOWN；不把核查成功当作任务成功。后续所有者明确授权 API36 定向验证，Consumer / Developer 各 57/57 通过，修复原生 API 异常跨 JNI 传递并校正旧夹具，见[设备证据](../evidence/development/hxa232-api36-2026-09-30.md)。真实模型恢复完成率、完整进程故障矩阵及 OEM 尚未验收，不声称全产品通过；既有实现纳入本次整合提交。

- 偶发问题追加收口（2026-09-29）：修复 SAF 撤销后不刷新及异步来源结果发布，双渠道 API36 10/10；澄清 Goal 仅规划提示，冻结版本三轮均 1 次模型调用/0 工具、Goal PAUSED。诊断能力探测 3/3、skill-003 3/3，但后者两轮仍有额外只读调用。全量主机 gate 通过；历史截断/探测偶发失败未复现、额外调用仍开放。失败与修复范围见[增量证据](../evidence/development/intermittent-closeout-2026-09-28.md)；已提交为 `70456eb5` 并快进到本地 main，未推送。



- [HXA-126](tasks/HXA-126.md)：预注册 public-client OAuth 核心切片已整合，见[修复与验证](../bug-fixes/2026-09-21-connector-oauth-merge.md)；两家真实服务与动态注册仍未完成。



结构治理见[结构审查](../research/modules/01-architecture-and-execution-engine.md)。HXA-223 已最终关闭 R4：未证明当前生产存在必现双 owner 终态 race，但 repository 的 stale snapshot 覆盖能力是真实结构风险，现已用 `expectedState + expectedStepCount` CAS fail closed；Turn terminal/review/recovery 与 Session next-work owner 已完成收口。

## Next task

本地 main 在 `70456eb5` 后继续整合了契约收敛、视觉/输入恢复与 Provider 设置交互；提交及联合验证见[分支收敛记录](../evidence/development/branch-convergence-2026-09-29.md)，本轮未推送。当前非重构收口集中于事实对齐、主机发行门禁和[内测准备](internal-pilot.md)，不是新的产品功能开发。[发行就绪条件](release-readiness.md)只维护非重构依赖和退出条件。

2026-09-30 所有者要求的完整 HXA-231 R1 已完成主机与有界 API36/真实 SGLang 验证：原子工具绑定、请求快照身份、调度/审批/执行同源和撤销准入。下一步优先内测，按具体设备/账号授权补齐尚未覆盖的故障矩阵；本次 candidate 15/15 不替代未来需要 clean 锚点时的正式 P5。HXA-232 保留其既有交付与未验证边界。后续 Core/上下文、插件、通用等待等不自动启动；选择原则见[开发策略](feature-refactor-strategy.md)，技术方案见[Harness](../architecture/harness-refactor-plan.md)。

非重构工作继续内测与已授权的指定手机实际流程，配置→聊天工具→重开验收仍需恢复设备条件；真实用户试用尚未执行。P0～P4 和 P7 已交付切片不重新排为底座开发；剩余模型效果、OEM/资源与账号问题按下方边界处理。

最近完整 P5 为 clean `8c7a95b4` 的 15/15、准备 1/1，见上方最终收口与[系统基线](harness-system-baseline.md)。后续工作树变化不自动继承这个结果；按影响范围和当前设备/服务授权决定新验证，不无限刷定向测试。公共 BFCL/AndroidWorld 与生产 Harness 基线分别报告，不从原计划或报告标题推导全量可比成绩。

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
- **Runtime / Provider**：QuickJS 默认在非导出 isolated UID 服务；用户开启原生总开关后可选择应用 UID 私有进程，文件/网络/Android 能力整体授权，不声称凭据隔离；PRoot/订阅在 developer APK 私有进程、共享主 UID，经私有 Binder/PFD 交换有界数据。ADR-PROVIDER-001 已接受设备内本地模型作为一等 `ModelProvider`，能力满足时可直接驱动完整 Agent loop；本地 Runtime/模型资产已实现，host gate 与完整 loop/真实模型验收边界见 HXA-222。后台 Job 与手动 PTY 所有权独立，未知副作用只对账、不自动重放。
- **应用能力**：手动文件管理与 Agent scope 分离；搜索、主题、准备、终端管理、导出不因 UI 存在而成为 Agent Tool。WebView 由浏览器 Activity owner 持有。
- **扩展**：MCP、Skill、A2A Client 经统一工具管线；A2A 是外部服务而非本地子 Agent。市场与离线签名索引不等于在线分发系统；OAuth 本地切片不等于外部服务验收。

## Known limitations

- **SAF 范围**：本轮可复现的撤销后残留与异步来源投影问题已修，API36 双渠道 10/10；见[修复证据](../evidence/development/intermittent-closeout-2026-09-28.md)。不把全部历史空列表或所有 OEM 根因归到同一问题；仅遇到新失败再开启对应诊断。
- **模型行为**：历史 OUTPUT_TOKEN_LIMIT、能力探测偶发失败保留取证入口；较早 skill-003 两次额外调用与后续 P5 的 0 调用分属不同运行，不能互相抹除。最终收口仍记录 skill-001 额外调用；少量 Goal 或工具视觉成功不证明所有长程任务稳定。
- **系统与长稳**：模拟器 24 小时相关测试及应用释放路径已有证据，但系统 JNI/Binder 根因仍 open，goldfish FD/UID-proxy Binder 维度不能由模拟器关闭；见[释放调查](../evidence/development/native-reference-release-trace.md)、[浏览器引用验证](../evidence/development/browser-controller-reference-verification.md)与[优化记录](../evidence/development/main-optimization-todo.md)。
- **物理设备**：Root 094/095 的 OnePlus API35 专项及[P0基线修复](../bug-fixes/2026-09-17-physical-p0-baseline.md)是固定源码证据；其他 OEM、低内存、热压、Doze、Root grant/revoke/loss 和真实 16 KiB 按矩阵单独验收。x86_64 静态制品不证明实际运行。
- **文件与 Runtime 恢复**：182 不承诺断电事务、字节续传、跨 Provider 原子性或自动后台队列；目标/备份变化需核查。订阅终态完整结果物化与真机资源压力仍有边界，见[授权/Runtime收敛](../bug-fixes/2026-09-18-authorization-runtime-convergence.md)。
- **模型与附件**：导入不等于模型理解；图片受视觉能力和预算约束，任意文档/音视频/OCR 不因现有管线而交付。真实 token 偏差与物理内存峰值未由本次压缩回归覆盖。
- **未来能力**：只读 Git 状态/diff 已有；完整持久 Git 写操作、remote Git/凭据、生产子 Agent/Workflow 未交付。accepted ADR 不等于实现完成。
- **发行与测试跳过**：consumer/developer debug 及 CI 不等于签名 release、渠道申报或商店审核。HXA-184 的外部材料 JVM 条件跳过、浏览器长稳/诊断设备条件跳过均未计通过。外部 profile 缺输入可明确跳过，提供但无效必须失败，见[公共验收规则](verification-matrix.md)。

历史 CI、分支合并、诊断与缺陷结果统一查[证据入口](../evidence/README.md)和完成记录；不在本页维护多份互相覆盖的提交/测试流水账。
