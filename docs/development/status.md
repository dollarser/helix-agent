# 当前实施状态

更新：2026-10-19。本页记录实施事实、当前工作和开放条件，不从队列自动选择任务，也不覆盖当前对话的压缩摘要。历史流水见原任务、完成记录和证据；已完成项不重复实施。

## 当前实施

Owner 本轮追加范围已本地交付：`40288cf8` 精简指导并改写工作区目标，`45e2f0d7` 实现容量与信息小优化；完整主机及日常 API36 ARM64 Advanced 的 Debug 13 项、同源 R8 24 次方法执行与真实热状态/自然退出回收检查通过。用户表、偏好、原终端记录和显示输入设置保持；本轮资源/测试 APK 清理，新 R8 留在日常实例，见[本轮结果](../evidence/development/workspace-capacity-and-guidance-2026-10-19.md)。没有从后续队列自动启动新任务。

目标现为真实工作区原位操作，独立终端无需聊天；URI-native 直接使用 Provider，需要 POSIX 的具体消费者才物化，worktree/snapshot 明确选择，287/288 联合验两路。完整重构仍未开始，当前 `code.linux.run` 继续快照；本轮只报告来源/执行/输出、增强原 hash 冲突反馈、复用现有 Git diff。工具普通调用按 CPU 最多四、压力降一；手动终端最多四，Job 最多四，共用六槽。指导精简保留有效流程为按需参考，不增加新恢复手册或通用 diff 后端。

本轮授权主机及指定日常 API36 ARM64 模拟器验收、保留当前设置和用户数据、本地提交；不推送、不改 Release、不清库、不调用真实账号或模型。版本保持 0.0.6 / code 1，包/工具声明版本保持 1。同一任务已取得的许可跨压缩继续有效，历史独立任务的账号/删除/发布许可不扩展到本轮。

## 当前结论

| 范围 | 最新事实与证据 | 保留边界 |
| --- | --- | --- |
| 终端布局与容量 | `c4f95a06`/`ef8fd10c` 原布局交付；本轮四终端横竖屏/两倍字体、热压新建拒绝、原身份/目录/环境、退出回收及单步关闭通过，见[本轮验收](../evidence/development/workspace-capacity-and-guidance-2026-10-19.md) | 先前固定两个不是 Android 限制；新结果仅指定 API36 ARM64 Advanced，不外推物理手机、其他 API/OEM、实际低内存或长稳 |
| 终端生命周期 | `b1bb9101` 修复同 boot 覆盖安装 UNKNOWN、错误 ATTACH 与单步关闭进程树；[故障记录](../bug-fixes/2026-10-19-terminal-package-replacement.md) | 手动 PTY 与聊天 Job 独立；真实手机、其他 API/OEM 未因此验收 |
| 内置模型/插件 | [HXA-307](tasks/HXA-307.md) 动态可用目录只进对话选模，无 Provider 配置和会话传输提醒；无变化启动/健康 repair revision 稳定；[7 个真实 Turn、9 次调用](../evidence/development/built-in-model-2026-10-18.md#真实内置服务与会话提醒移除)已完成 | 不再把旧阶段待白名单当当前阻塞；档位接受不证明思考语义，部署模板/容量/长稳未由此验证 |
| 发布 | v0.0.6 已发布，版本 Release 只保留两 APK 和标签源码；固定对应源码/许可在既有 runtime-assets；[整理记录](../evidence/development/v0.0.6-publication-2026-10-18.md) | 原标签、APK 字节不变；mapping 用于排障，不代表当前必然有 Bug；本轮无新发布授权 |
| 工作区目标 | [Harness §23.3.1](../architecture/harness-refactor-plan.md#2331-目录资源与执行环境)、[工作区 ADR](../adr/workspace/004-workspace-binding.md)维护目标与现行实现差异 | 设计接受不等于生产原位执行已交付，远程平台/透明同步未交付 |

## 已交付与历史证据

| 领域 | 现有交付入口 |
| --- | --- |
| 近期交互、浏览器、恢复 | [HXA-306 日常收尾](../evidence/development/daily-interaction-closeout-2026-10-17.md)、[恢复复核](../evidence/development/crash-window-review-2026-10-16.md)；旧通知栏问题已修复，不是当前任务 |
| Linux 环境与目录展示 | [HXA-305](../evidence/development/linux-environment-closeout-2026-10-17.md)、[路径展示](../evidence/development/path-presentation-2026-10-17.md)、[HXA-303 配套](../evidence/development/pre-refactor-contract-cleanup-2026-10-17.md) |
| 工具与插件 | [R1 原子绑定](../completion-records/HXA-231.md)、[插件优化承接](../research/topics/helix-plugin-architecture-optimization-2026-10-11.md#7-任务承接与验收)、[市场/模型](../evidence/development/external-mcp-market-and-model-2026-10-13.md)、[发现](../evidence/development/tool-discovery-workset-2026-10-12.md) |
| 文档、Memory、Job | [HXA-284 有界验收](../evidence/development/pre-refactor-closeout-2026-10-15.md)、[HXA-246](tasks/HXA-246.md)、[HXA-248](tasks/HXA-248.md)、[HXA-249](tasks/HXA-249.md)；不重做已交付主体 |
| 观察、资源和真机 | [观察闭环](../evidence/development/mobile-use-observation-loop-2026-10-14.md)、[旧 R6 长稳](../evidence/development/autonomous-app-and-stability-2026-10-14.md)、[资源报告复核](../evidence/development/resource-gate-review-2026-10-12.md#完成后的离线核对)；旧绿色不迁给新版本 |
| 维护与旧验收 | [完成记录索引](../completion-records/index.md)、[证据索引](../evidence/README.md)、[系统基线](harness-system-baseline.md)；黑屏和 Notion/Linear 测试按 Owner 决定结案，不自动重启 |

## 后续架构队列（未启动）

HXA-286～294 的完整生产重构尚未实施；303 的独立配套和本轮小优化不表示整项退出。下表只表达已记录的设计依赖，不是当前任务选择器；具体范围与验收归各 HXA。完整 Turn/真实 Room 与执行核心分别有据，不要求完整任务互等。

| 顺序 | 任务 | 推进方式与依赖 |
| --- | --- | --- |
| 1 | [HXA-286](tasks/HXA-286.md)：统一工具契约、插件贡献与前置依赖 | 共同定义 Turn/call/Job/长期资源/产物身份、四个完成条件、取消/迟到、重启 reconcile、清理及上下文正确性；与规范工具/发现依赖和删除清单一起落地，参与设计不依赖后续代码先完成 |
| 2 | [HXA-287](tasks/HXA-287.md)：应用命令与完整 Turn；[HXA-288](tasks/HXA-288.md)：结果、Job 与产物 | P0：286 后分别完成 287-A/288-A，再共同接最小真实 Room、读取及受控副作用，覆盖三个崩溃窗口；随后各自扩宿主和领域适配，整任务不互设启动前置 |
| 3 | 近期能力：[HXA-292-M](tasks/HXA-292.md) Mobile Use、[HXA-289](tasks/HXA-289.md) 检索、[HXA-291](tasks/HXA-291.md) Resources、[HXA-290](tasks/HXA-290.md) Fetch | P1：优先主打移动流程；各片在对应来源/执行/产物接缝稳定后可交付，不等完整宿主。已实现授权/浮窗/导入/编辑不重做 |
| 4 | [HXA-292-B](tasks/HXA-292.md) Browser 表单、[HXA-290](tasks/HXA-290.md) Search、[HXA-295 B/C](tasks/HXA-295.md) 跨会话能力 | 独立按所需接缝推进；B 查询授权 P1，C 创建/投递 P2。真实 Search 需 provider/配置；这些依赖不阻塞 Fetch 或结构结算，不重开已结案 OAuth |
| 5 | [HXA-293](tasks/HXA-293.md)：Memory/上下文质量 | A 共同预算/内容端口 P1，B 相关性选择 P2；285/299 已交付基线保留，配对/恢复正确性在 286/287 前置，策略单变量评估，无收益不改默认 |
| 6 | [HXA-294](tasks/HXA-294.md)：冻结后的集成与恢复验收 | 汇总各阶段已积累的正确性证据，回归冻结范围并清理旧路径；不是第一次验证取消/身份/恢复，设备/模型/时长仍按当轮授权 |


已交付的插件 HXA-272～279、应用接口/状态收敛 HXA-253～268 和既有 R1/R3/J1/J2 不重新排为底座开发。R2-B、子 Agent、远程、hooks/LSP、模型 PTY 等仍按[候选索引](candidate-decisions.md)的进入条件，不随文档整理启动。两个小时/24 小时长稳与提前结束专项继续暂停；新故障有证据时定向处理。

## 开放条件与限制

| 范围 | 条件或边界 |
| --- | --- |
| 本轮验收 | 本轮主机/指定日常 Advanced 有界验收已完成；两渠道 JVM 各 4 项外部输入跳过。其他设备、实际低内存、长稳和真实模型未跑，不算通过 |
| 真实服务 | Notion/Linear 全部测试已结案；Claude/Grok 付费账号按 Owner 决定暂缓，不追加调用。其他服务另需指定输入及当前授权 |
| 设备/资源 | 其他 API/OEM、物理低内存/热压/Doze/16 KiB 硬件、24h 与全部 Runtime 组合未由日常模拟器覆盖 |
| 发行 | 稳定应用身份、签名/升级与渠道材料仍按[发行就绪](release-readiness.md)；本轮仅本地提交，历史一次性发布/删数据授权不沿用 |

## Current interfaces

职责与调用路径统一查[当前总体架构](../architecture/overview.md)。TurnEngine 是 live driver 和 durable 生命周期 owner；SessionWorkScheduler 仲裁 Queue/Goal，TurnCoordinator 保留 round checkpoint。R1 绑定契约见[工具 ADR](../adr/tools/001-descriptor-contract.md)。

会话配置在 Turn 准入冻结；跨会话引用在输入接受时形成有界快照。模型 per-call intent 只作展示，不进入业务参数、审批或效果事实；大结果分页与模型/UI 投影保持分离。具体规则见[上下文](../adr/agent/002-context-compaction.md)、[结果/预算](../adr/agent/006-model-data-budget-boundaries.md)、[Goal](../adr/goal/001-lifecycle-and-completion.md)。

QuickJS 默认 isolated UID，原生总开关对应共享应用 UID 私有进程；PRoot/订阅也不声称凭据隔离，见[执行域](../architecture/local-code-execution.md)。后台 Job/手动 PTY 独立，A2A Client 不等于本地子 Agent；手动文件/配置/任务控制不必创建模型 Turn，WebView 仍由浏览器 Activity owner 持有。

## Known limitations

| 范围 | 当前保留边界与证据 |
| --- | --- |
| SAF/模型偶发行为 | 已修复项不重开；其他 OEM 空列表、OUTPUT_TOKEN_LIMIT、探测偶发失败、skill-001/003 多余调用保留原轨迹，见[偶发记录](../evidence/development/intermittent-closeout-2026-09-28.md)与[最终收口](../evidence/development/final-closeout-2026-09-29.md) |
| 系统与长稳 | HXA-265双渠道900秒及OnePlus/API34 Developer两小时应用资源precheck的设备门禁通过；[两小时原报告失败、修复与离线核对](../evidence/development/resource-gate-review-2026-10-12.md#完成后的离线核对)独立保留，24h、其他OEM及全部Runtime未由此验收。JNI/Binder、goldfish FD/UID-proxy Binder 根因未全部关闭；[释放调查](../evidence/development/native-reference-release-trace.md)、[浏览器核验](../evidence/development/browser-controller-reference-verification.md)、[剩余调查](../evidence/development/main-optimization-todo.md) |
| 真机/OEM/资源 | OnePlus API35 Root 与[P0修复](../bug-fixes/2026-09-17-physical-p0-baseline.md)是固定范围；其他 OEM、低内存、热压、Doze、Root grant/revoke/loss、16 KiB 另验；x86_64 静态制品不证明运行 |
| 文件与 Runtime 恢复 | HXA-182 不承诺断电事务、字节续传、跨 Provider 原子性或自动后台队列；目标/备份变化需核查。订阅结果物化与资源压力的历史边界结合 HXA-232 后续切片判断，见[原记录](../bug-fixes/2026-09-18-authorization-runtime-convergence.md) |
| 模型与附件 | 导入不等于理解；图片受视觉能力/预算约束；任意文档、音视频、OCR 未因管线存在而交付；真实 token 偏差与物理内存峰值仍须测量 |
| 未来能力 | Project Memory 的完整设备验收仍开放；持久 Git 写操作/远程凭据、生产子 Agent/Workflow 未交付；其余见候选索引，accepted 不等于实现完成 |
| 发行/测试跳过 | debug/CI 不等于签名 release、商店审核或渠道验收；HXA-184 外部材料、长稳和设备条件跳过不计通过；外部 profile 缺失与无效分开，见[公共验收](verification-matrix.md) |

历史命令、测试失败、提交与安装快照继续保存在原[完成记录](../completion-records/index.md)和[证据](../evidence/README.md)，不因本页收短而删除或扩大验证结论。
