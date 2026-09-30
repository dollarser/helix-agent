# 当前实施状态

更新：2026-09-30。本页只维护当前基线、开放工作、下一步及限制；测试数字、命令、提交/安装过程留在链接记录。现场 HEAD、工作树、远端和设备须重新核对，不从历史“本轮未提交/未推送”推导当前状态。

## Completed

当前整合基线包含恢复/QuickJS、历史正确性修复及 **HXA-231 R1 原子工具绑定**。主机、有界 API36 和真实 SGLang 的实际范围见[整合验证](../evidence/development/r1-device-service-closeout-2026-09-30.md)；它不是发布 APK、clean 正式 P5 或所有设备/任务通过的证明。

| 领域 | 已有能力与证据入口 | 不由此推导 |
| --- | --- | --- |
| 工具绑定 | [HXA-231](../completion-records/HXA-231.md)：单一 binding、请求/调度/审批/执行同源、全部来源迁移与撤销准入；[有效契约](../adr/tools/001-descriptor-contract.md) | 不再重做 R1；不等于完整 R2/R3/J1 |
| Core/恢复 | [HXA-220](../completion-records/HXA-220.md)、[221](../completion-records/HXA-221.md)、[223](../completion-records/HXA-223.md)：唯一 Engine、CAS 结算、旧 Turn 终结与 successor Turn | 不等于全部 Core/UI/Room 依赖已拆净 |
| 输入、导航与活动 | HXA-214～219、[226](../completion-records/HXA-226.md)、[228](../completion-records/HXA-228.md)、[229](../completion-records/HXA-229.md)；[跨会话输入与联合回归](../evidence/development/merged-api36-regression-2026-09-29.md) | 不把历史输入/草稿问题重新列为未修复 |
| 自主视觉 | [HXA-225](../completion-records/HXA-225.md)：view_image、浏览器视觉回填、三协议编码和有界校验；[产品边界](../product/image-reading.md) | 有限工具看图验证不覆盖全部协议、手机整屏截图或设备内视觉 |
| Linux Job/终端 | [HXA-196](../completion-records/HXA-196.md)～[199](../completion-records/HXA-199.md)：后台 start/status/cancel/collect 与双 PTY | 不等于通用 await/AUTO |
| 扩展 | [HXA-129](../completion-records/HXA-129.md)、[130](../completion-records/HXA-130.md)、[212](../completion-records/HXA-212.md)及[Plugin/Mobile Use MVP](../evidence/development/plugin-platform-mobile-use-mvp-2026-09-28.md) | 安装/市场基础不等于 R3 完整生命周期 |
| Workspace/Memory | [HXA-210](../completion-records/HXA-210.md)、[目录恢复](../evidence/development/recoverable-workspace-2026-09-27.md)、[HXA-230](../completion-records/HXA-230.md) | Global 已交付，完整 Project 接线仍未启用 |
| 设备内模型 | [HXA-222](../completion-records/HXA-222.md)、[收口](../evidence/development/hxa222-closeout-2026-09-28.md)、[安装](../evidence/development/p3-local-model-install-2026-09-28.md)/[首次使用](../evidence/development/p4-first-success-journey-2026-09-28.md) | 本地 Provider 可驱动完整 Loop，不等于所有设备与任务质量已验证 |
| Eval/整合 | [HXA-227](../completion-records/HXA-227.md)、[设备基线](../evidence/development/hxa227-device-baseline-2026-09-27.md)、[历史真机验收](../evidence/development/physical-oneplus-acceptance-2026-09-24.md) | 每份证据仅覆盖原基线；公共 benchmark 与生产 Harness 分开报告 |

2026-09-30 所有者追加的[输入布局与模型/推理入口收敛](../bug-fixes/2026-09-30-composer-layout.md)记录当前改动及主机/设备验证边界。

最近的界面与输入修复分别见[压缩/Goal 隔离](../bug-fixes/2026-09-29-manual-compaction-goal-isolation.md)、[系统选择器与分享](../bug-fixes/2026-09-29-external-ui-recovery.md)、[输入恢复/Runtime](../bug-fixes/2026-09-29-interaction-recovery-audit.md)、[命令/语音入口](../bug-fixes/2026-09-29-slash-runtime-voice.md)、[交互/配置](../bug-fixes/2026-09-29-interaction-settings.md)。较早配置“建议后另确认”和自动化周期确认须结合 HXA-232 的后续变更理解，不能恢复为当前规则。

开发期 Room 仍采用 v1 baseline：不兼容库重建、数据库外文件保留，兼容库重开不清空。验证与实际工具看图见[最终收口](../evidence/development/final-closeout-2026-09-29.md)；正式数据升级和签名身份归 HXA-122。

其他已交付工作查[完成记录索引](../completion-records/index.md)和[M0](../completion-records/M0.md)。[分支收敛](../evidence/development/branch-convergence-2026-09-29.md)、[权限/循环早期增量](../evidence/development/operation-permissions-loop-progress-2026-09-28.md)、[主目录整合](../evidence/development/main-operation-integration-2026-09-28.md)及[自动化恢复早期记录](../evidence/development/automation-recovery-progress-2026-09-28.md)仅作历史证据。

## 候选与有限接受范围

[候选索引](candidate-decisions.md)集中记录有限接受、已有基础和未排期能力。R1 已交付；R2/Core/ContextCompiler、R3/插件生命周期、J1/J2 等仍不自动启动。本文不因文档整理接受候选，也不重开已完成的生命周期、输入或视觉任务。

## 本轮架构交付

R1 的生产迁移、全部来源切换、交错反例和验证已由 [HXA-231 完成记录](../completion-records/HXA-231.md)承接；这里不另维护迁移待办或第二份测试清单。

## In progress

| 开放工作 | 已有基础 | 仍需处理的范围 |
| --- | --- | --- |
| [HXA-232 自主恢复](tasks/HXA-232.md) | 自动化授权内恢复、Goal 局部额度衔接、无进展收尾、Runtime 查询/收取、UNKNOWN 只读核查、原 Goal 账本、队列重验证、统一授权；QuickJS 原生总开关、结构化反问与提示词资源化已有实现及有界验证 | 真实模型恢复完成率、完整进程/订阅/PRoot 故障矩阵及 OEM；不把诊断完成或有限场景通过当作原任务成功 |
| [历史正确性收口](../bug-fixes/2026-09-30-historical-correctness-audit.md) | Provider 探测发布/取消、压缩参数、Git、内容发布/删除并发和执行线程容量已有修复，纳入整合基线 | 仅按记录中遗留问题和新证据继续，不重新执行整个历史缺陷清单 |
| [偶发问题](../evidence/development/intermittent-closeout-2026-09-28.md) | 可复现 SAF 撤销/异步投影和 Goal 仅规划问题已修 | 历史截断、探测偶发失败与额外只读调用仍保留调查边界；未复现不等于根因关闭 |
| [HXA-126](tasks/HXA-126.md) | 预注册 public-client OAuth 核心已整合，见[记录](../bug-fixes/2026-09-21-connector-oauth-merge.md) | 两家真实服务验证、动态注册及相应外部输入 |

2026-09-30 的 [Runtime 故障矩阵修复](../evidence/development/runtime-fault-matrix-2026-09-30.md)覆盖原身份恢复、取消/退出区分、IPC/PFD 失败和有界执行通道；源码与主机结果不替代实际进程 kill、OEM 和真实账号验收。

HXA-232 的逐轮主机/设备证据留在任务文件，不在本页复制。HXA-223 的 stale snapshot/CAS 修复已完成，不再作为开放 R4 缺陷；具体结构边界见[结构研究](../research/modules/01-architecture-and-execution-engine.md)。

2026-09-30 所有者追加的 [Provider 模型管理统一](../evidence/development/provider-model-management-2026-09-30.md)将三类来源的目录、候选、默认与逐模型验证分开；主机/设备状态以该记录为准，不扩大订阅目录或真实账号验收结论。

随后所有者授权的 [Provider 使用链路收口](../evidence/development/provider-chain-closeout-2026-09-30.md)补齐真实应用回执、来源/模型失败分离、账号状态同步与精确模型请求；提交和最终验证以记录为准。设备旅程已准备，不据此声称实际内测通过。

2026-09-30 上述 Runtime/Provider 收尾已形成 `15b89830`。所有者追加的 [Antigravity 与订阅渠道边界](../evidence/development/subscription-antigravity-2026-09-30.md)保留五个账号入口并固定排序；consumer 只支持 API/本地模型。Kimi/MiniMax 的 Key 型套餐归 API。新增接入的主机、制品和真实账号边界以该记录为准，不用旧绿色替代。

随后获授权的 [Runtime / Provider 设备收口](../evidence/development/runtime-provider-device-closeout-2026-09-30.md)补验真实 SGLang UI、Provider 完整旅程和七组有界进程死亡/结果恢复，修正旧测试对模型管理、压缩和自动核查的预期。订阅使用合成任务，不能据此关闭真实账号或完整平台故障矩阵。

## Next task

**优先内测，并按当前明确的设备/账号授权补齐开放故障矩阵。** 继续配置→聊天工具→重开等实际用户流程；指定手机流程仍需恢复设备条件，真实用户试用尚未执行。P0～P4、P7 和 R1 的已有交付不重新排为底座开发。

后续结构与功能投入由实际瓶颈决定，选择方法见[开发策略](feature-refactor-strategy.md)，目标契约见[Harness 方案](../architecture/harness-refactor-plan.md)，非重构依赖见[内测](internal-pilot.md)和[发行就绪](release-readiness.md)。账号、真机和发行输入不阻塞无依赖的本地工作。

最近记录的 clean 正式 P5 来自 `92e93bf5`，固定 15/15 与准备 smoke 1/1 通过，详见[Runtime / Provider 设备收口](../evidence/development/runtime-provider-device-closeout-2026-09-30.md)和[系统基线](harness-system-baseline.md)。此前 `8c7a95b4` 和各 candidate 证据保留；新改动不自动继承历史绿色。

## Blocked

| 项目 | 缺少条件 / 决策 |
| --- | --- |
| 16 KiB 物理巡检 | Android 15+、16 KiB 页面物理硬件 |
| HXA-125 受保护 Connector | 独立账号下的凭据无效、权限拒绝、厂商撤销及重连；WorkBuddy 来源样本已有 |
| HXA-126 外部验收 | 两家服务账号、App 注册与 redirect 条件；动态注册未交付 |
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
