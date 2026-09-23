# 当前实施状态

更新：2026-09-23。此页只维护当前结论、下一步和未闭合边界；命令、制品和历史数字归完成记录与证据。现场 HEAD、工作树、远端及设备状态须重新核对。

## Completed

全部已交付 HXA 见[完成记录索引](../completion-records/index.md)，M0 见[工程基线](../completion-records/M0.md)。完成仅限记录中的范围，不代表全部产品、真实账号或发行验收。

- 最近整合：HXA-130 离线签名索引、HXA-212 内置市场、HXA-213 会话 fork 及上下文压缩补强。2026-09-22 合并后完整主机门禁与四象限定向设备 276/276 通过；这是该次制品的历史结果，见[整合验证](../evidence/development/branch-integration-2026-09-22.md)与[压缩修复](../bug-fixes/2026-09-22-context-compaction-admission.md)。该次记录为本地整合、未推送，不推断当前远端。
- HXA-206 本地核心产品验收、HXA-198 双终端均已完成；同 fixture 对照、Git R1 debug/release、升级及实际恢复范围见[206完成记录](../completion-records/HXA-206.md)、[198完成记录](../completion-records/HXA-198.md)与[199/206证据](../evidence/development/acceptance-199-206-2026-09-21.md)。
- 191、192、193～195、197、202～205、207～209、211 等已有交付记录，不重新执行旧交接开发包。旧三态工具权限证据不替代 209 会话授权验收。
- HXA-214 普通 composer、发送回执与统一停止已交付，见[完成记录](../completion-records/HXA-214.md)；HXA-215 最新消息会话内修订已与其联合收敛，见[完成记录](../completion-records/HXA-215.md)。ADR-AGENT-009 已接受。
- HXA-216 默认排队/显式转向已完成本地验收，见[完成记录](../completion-records/HXA-216.md)；本地结果不替代 main 合并后的矩阵与远端 CI。
- HXA-218 第一批 UI 重构与 HXA-219 产物就地预览已完成各自本地范围，见[完成记录](../completion-records/HXA-218.md)和[完成记录](../completion-records/HXA-219.md)；仍保留其设备/整合边界。
- 上述214/215/216/218/219已在本轮整合至本地main：完整主机门禁、30批联合设备验证（920项）和恢复main文档后的源码门禁通过，见[收敛记录](../evidence/development/branch-convergence-2026-09-22.md)。本轮未推送或执行远端CI。

- HXA-129 已在 `codex/hxa-129-connector-lifecycle` 本地交付并已整合入 main：安全替换、安装归属与会话启停，完整主机门禁及272项定向设备验证通过，见[完成记录](../completion-records/HXA-129.md)。
- HXA-217 轻量请求来源记录与 JSONL 可追踪性已完成本地实现与验收，Room 27→28 迁移与设备测试通过，UI 第二阶段对齐 Operit（Thinking Accordion、工具执行内联预览）落地，见[完成记录](../completion-records/HXA-217.md)。

## In progress

- [HXA-126](tasks/HXA-126.md)：预注册 public-client OAuth 核心切片已整合，见[修复与验证](../bug-fixes/2026-09-21-connector-oauth-merge.md)；两家真实服务与动态注册仍未完成。
- [HXA-196](tasks/HXA-196.md)：后台 Job 与结果回收已有实现；普通 Act 主进程死亡、缺失记录对账与预算释放已有证据。2026-09-20 所有者豁免当次 HOME/锁屏/Doze 真机验收，不计为通过，也不替代其他任务的物理验收。
- [HXA-199](tasks/HXA-199.md)：双 API、实际 30 分钟脱离/默认两小时租期、双 shell 恢复及覆盖升级已验，剩物理专项与收口。

结构治理见[结构审查](../research/project-structure-and-engine-review.md)。[深度复审](../research/execution-engine-deep-review-2026-09-22.md)已按`9a9b25dd`复核：R1/R5/R9原问题关闭；R2/R3调度、R6结算与R7协议结束已完成本地修复整合（完整主机门禁及最终四象限80项通过），见[修复记录](../bug-fixes/2026-09-22-engine-browser-convergence.md)。R4仍需实际竞态证据，R8完整终局通知故障注入作为P2后续项。

## Next task

129 与 217 均已完成并在 main 整合，后续按[工作计划](next-work-plan.md)推进。

- 129 与 217 已完成本地交付与主线收敛，远端推送并核对 CI 状态。
- 后续根据工作计划与架构决策评估 HXA-126、HXA-196、HXA-199 物理专项或待接受的后续提案。

使用[实施指南](implementation-guide.md)交接；任务规格保存范围，完成记录保存结果，不新增按执行者命名的长期指令。已结束交接的归属见[历史汇总](../evidence/development/completed-handoffs-2026-09-22.md)。开始 HXA 前解决强制基线失败，历史绿色不能替代当前验证。

## Blocked

| 项目 | 缺少条件 / 决策 |
| --- | --- |
| HXA-199 物理专项 | OEM/HOME/安全锁屏/Doze/热压/物理长稳及真实 16 KiB 设备；模拟器不能替代 |
| HXA-125 受保护 Connector | WorkBuddy 来源样本已补；仍需独立账号验证凭据无效、权限拒绝、厂商撤销及重连 |
| HXA-126 外部验收 | 两家独立服务账号、App 注册与 redirect 条件；动态注册未交付 |
| HXA-190 真实订阅 | Claude/Grok 付费调用账号不可用，按所有者决定暂缓；fixture 不算真实调用通过 |
| 发布验收 | HXA-122 稳定 applicationId、渠道命名、签名与升级路径待决定；发行顺序 120→122→121→123 |

设备、账号和发行条件项不阻塞无依赖的本地工作；会话目录绑定 ADR-WORKSPACE-004 仍 proposed，不自动启动 HXA-210。

## Current interfaces

- **执行引擎**：AgentRuntime 统一提交/观察接口，ChatService 仍持有运行协调与 UI 状态；AgentLoop、TurnCoordinator、ToolScheduler/Dispatcher 分别承担模型循环、持久结算、平台并发和授权执行。职责拆分已有交付，不表示状态所有权已经完全分离。
- **授权与 Goal**：209 实现用户选择的会话预设/CUSTOM、工具启用/禁用与执行前解析；208 按 ADR-GOAL-001 交付。模型及外部扩展不能授予权限；Goal persistence 不扩大 scope。Plan 审阅到执行见 192。
- **上下文与结果**：工具显示和模型投影分离，大结果可按会话只读分页；模型请求与压缩统一容量准入并保留诊断。Goal、未知副作用和预算停止各有恢复路径；窗口默认值可为估算。
- **Runtime**：QuickJS 在非导出 isolated UID 服务；PRoot/订阅在 developer APK 私有进程、共享主 UID，经私有 Binder/PFD 交换有界数据，consumer 排除。按需冷绑定，不把 PRoot 描述成凭据隔离或离线沙箱。后台 Job 与手动 PTY 所有权独立，未知副作用只对账、不自动重放。
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
