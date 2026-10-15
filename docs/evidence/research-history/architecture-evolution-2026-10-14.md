# 架构与能力演进索引（截至 2026-10-14）

性质：依据现有 ADR、HXA、研究与 Git 记录整理的历史导航。核对基线为 `main/14824251` 加当时未提交的执行边界提案；本次未重新执行历史功能验收。本页解释重要变化及其来源，不维护下一步、有效契约全文或新的重构要求。后续变化写回对应主题 ADR/任务，不需要为了实施新方案先更新这份快照。

## 先区分四种材料

| 要知道什么 | 唯一责任入口 | 本页的作用 |
| --- | --- | --- |
| 现在做什么、还有哪些验收边界 | [status](../../development/status.md) → 当前 HXA | 只指向历史节点，不产生待办 |
| 哪些设计已经接受 | [主题 ADR](../../adr/README.md) 的有效 Decision 与适用 Decision history | 解释变化原因，不保存第二份契约 |
| 下一轮重构讨论什么 | [Harness §23](../../architecture/harness-refactor-plan.md#23-2026-10-14-执行边界重构提案)及[研究 §10](../../research/modules/01-architecture-and-execution-engine.md#10-2026-10-14-执行边界与新目标设计复核) | 截至本次是提案，未接受/未实施的部分不能从历史验收继承通过 |
| 当时为什么这样做、测过什么 | 下表原任务、完成记录、证据及 Git | 按原日期、源码与设备范围阅读 |

## 主要演进节点

日期表示记录形成或主要变更阶段，不推断任务严格串行；HXA 编号也不等于实际执行顺序。“交付”只指原记录范围，主机、设备、真实模型、资源及发行结论分别保留。

| 阶段 | 增加或改变了什么，为什么 | 可追溯来源与边界 |
| --- | --- | --- |
| 2026-08-31：工程基础 | 建立 Gradle 多模块、AppContainer、导航和质量/供应链门禁，为能力接入提供基础 | [M0 / HXA-001～003](../../completion-records/M0.md)；当时依赖版本与设备结果不是当前版本证明 |
| 早期 Agent 与工具基础 | 领域状态、Room、模型协议、工具策略/审批、文件/浏览器、MCP/Skill/A2A 逐项接入；早期 Turn 使用串行 reducer | [HXA-011](../../completion-records/HXA-011.md)、[任务清单](../../development/roadmap.md)；早期 reducer 后来由 HXA-220 删除，不能按旧完成记录恢复 |
| 2026-09：Runtime 部署取舍 | 从早期独立 Runtime APK/UID 方案收敛到 Developer 单 APK 内的 PRoot/订阅私有进程；实际 RootFS/文件访问验证影响隔离选择 | [Runtime ADR](../../adr/runtime/001-execution-domains.md)、[09-16 可行性记录](../development/isolated-proot-feasibility-2026-09-16.md)、[HXA-193](../../completion-records/HXA-193.md)；共享 UID 与 QuickJS isolated UID 的区别不能被旧图抹掉 |
| 2026-09-09～10：应用职责整理 | 对大类职责、文件传输恢复与装配做收敛，减少跨层混合；这轮没有一次完成后续所有应用边界 | [HXA-182](../../completion-records/HXA-182.md)、[HXA-183](../../completion-records/HXA-183.md)、[HXA-184](../../completion-records/HXA-184.md) |
| 2026-09-16～24：持续任务、权限与执行控制 | 完整 Goal、会话授权预设、排队/转向及后台 Job/手动终端逐步形成；任务意图、批准、命令存活开始按不同责任管理 | [HXA-208](../../completion-records/HXA-208.md)、[209](../../completion-records/HXA-209.md)、[216](../../completion-records/HXA-216.md)、[196](../../completion-records/HXA-196.md)、[199](../../completion-records/HXA-199.md)；后台存活与账号/发行证据不通用 |
| 2026-09-26：Turn 生命周期大收敛 | TurnEngine 统一 durable/live owner；清理旧串行状态机及同 Turn 恢复入口，明确 successor Turn；随后补 stale-state CAS、终态/review 事务与会话调度边界 | [HXA-220](../../completion-records/HXA-220.md)、[221](../../completion-records/HXA-221.md)、[223](../../completion-records/HXA-223.md) → [Agent ADR](../../adr/agent/001-turn-coordination.md)；当时 clean-slate 清库决定不是后续改动的通用清库授权 |
| 2026-09-27～29：会话、记忆与视觉 | Conversation-first、模型活动展示、显式 Workspace、Global Memory/Project scope API、本地模型与工具视觉反馈分别接入 | [HXA-228](../../completion-records/HXA-228.md)、[229](../../completion-records/HXA-229.md)、[210](../../completion-records/HXA-210.md)、[230](../../completion-records/HXA-230.md)、[222](../../completion-records/HXA-222.md)、[225](../../completion-records/HXA-225.md)；230 首版的 Project 未启用描述随后由 246 承接 |
| 2026-09-28～10-01：Harness R/J 方案与落地 | 从统一职责设计拆出原子工具绑定、纯 Core/上下文等价迁移、插件安装归属、原 Job 观察等待；自主恢复强调确定性核查与模型策略分工 | [原方案 §18](../../architecture/harness-refactor-plan.md#18-逐卡重构步骤与退出条件)、[HXA-231](../../completion-records/HXA-231.md)、[232](../../development/tasks/HXA-232.md)、[234](../../development/tasks/HXA-234.md)、[235](../../development/tasks/HXA-235.md)、[236](../../development/tasks/HXA-236.md)；实现接线与完整设备/策略收益不能合并成“全部完成” |
| 2026-10-02～05：Mobile Use 能力与插件体验 | 从全机/应用范围、原生截图手势，进一步改成会话持久授权、截图披露复用、运行凭据重建和插件浮窗；授权寿命与运行状态分开 | [HXA-243](../../development/tasks/HXA-243.md)、[244](../../development/tasks/HXA-244.md)、[245](../../development/tasks/HXA-245.md) → [权限 ADR](../../adr/permissions/001-session-authorization.md)；锁屏/断连不自动撤销会话授权，旧节点/手势凭据不能恢复复用 |
| 2026-10-05～10：项目、Job 与应用状态 | Project Memory、文件交接、J2 同执行 AUTO/后台释放继续承接前轮；目录/Composer/任务投影和 SessionCatalog 各自拥有状态与事务 | [HXA-246](../../development/tasks/HXA-246.md)、[247](../../development/tasks/HXA-247.md)、[248](../../development/tasks/HXA-248.md)、[257](../../completion-records/HXA-257.md)、[258](../../completion-records/HXA-258.md)；Core 已分层不等于完整 Turn 宿主已脱离 App/Room |
| 2026-10-10～12：插件与平台可靠性 | 修复 Mobile Use 后台返回/观察问题，补浮窗审批与会话交互；统一 Skill 内容/调用资格、就绪投影、Browser 原生贡献、真实资源引用与有界等待 | [HXA-269](../../development/tasks/HXA-269.md)、[270](../../development/tasks/HXA-270.md)、[271](../../development/tasks/HXA-271.md)、[插件方案任务映射](../../research/topics/helix-plugin-architecture-optimization-2026-10-11.md#7-任务承接与验收)；272～279 已有界交付，历史“尚未实施”不再代表当前状态 |
| 2026-10-12～14：发现、市场与观察反馈 | 取消全局 64 工具截断，采用同源发现与请求工作集；支持外部插件市场/包的明确子集；后续验证服务、市场恢复和动作后观察 | [HXA-280](../../completion-records/HXA-280.md)、[281](../../completion-records/HXA-281.md)、[282](../../development/tasks/HXA-282.md)；成本结果与长稳证据依样本/冻结制品解释，不承诺普遍提升 |
| 2026-10-14：统一文档插件 | PDF 专项读取扩展为 documents 插件，加入 DOCX 结构/图片、不可变快照、inspect/read/view 和阅读 Skill，删除旧等效入口 | [HXA-283](../../development/tasks/HXA-283.md) → [284](../../development/tasks/HXA-284.md)、[Workspace 文档 ADR](../../adr/workspace/006-pdf-reading.md)；截至本次为主机/制品交付，设备与真实模型未请求 |
| 2026-10-14：下一轮执行边界提案 | 从局部 UI/Memory 接缝清理扩展为整体职责设计：工具应用、执行结果/物理退出、Memory/产物、完整 Turn 领域宿主一起评估；允许重组模块，最终只保留一套实现 | [Harness §23](../../architecture/harness-refactor-plan.md#23-2026-10-14-执行边界重构提案)、[研究 §10.5](../../research/modules/01-architecture-and-execution-engine.md#105-同日审核目标架构与完整-turn-范围)；设计已写入方案，尚未作为新增契约接受或实施 |

## 同名阶段和重要取舍如何区分

| 容易混淆的说法 | 演进关系与读取方式 |
| --- | --- |
| “Core 重构已完成/冻结”与“完整 Turn 宿主继续抽离” | 220/223 收敛生命周期权威，234 将实际 Loop/上下文端口迁入 Core；257/258 收敛应用状态。§23 针对仍存在的应用/持久耦合，属于新的提案范围。历史冻结保护已验证行为，不冻结目录、接口和模块布局 |
| R1 / R2-A / R2-B / R3 / J1 / J2 与新的 A～E | 前者属于 09-28 方案：分别由 231、234、策略实验、235、236、248 等承接；§23 的 A～E 是新提案内的技术切片。两套字母不是同一任务编号，也不能把旧阶段验收转记为新阶段通过 |
| Mobile Use 初版结束许可与会话持久授权 | 243 保留初期规则及追加记录；244 和后续权限 ADR 改变授权寿命，245/270/271 承接浮窗与交互。系统事件可让运行不可用、移除浮窗或失效凭据，不据此恢复旧的自动撤权设计 |
| Global Memory 首版与 Project Memory | 230 的正文存储/全局首版和项目接口先交付，246 后接显式 Project 与跨会话组织；最新边界只维护在 Memory ADR，不从旧报告推断项目仍未启用 |
| PDF 候选与 documents 插件 | 文件工作流候选先由 283 承接 PDF，再由 284 统一 PDF/DOCX 阅读；编辑/生成、OCR 和完整 Office 排版未随读取一起接受 |
| 历史验收授权与本次授权 | 旧任务写过“全量模拟器/真机/现有账号”只描述当次执行；下一次按当前 Owner 范围验证。主机、设备、模型与 FFmpeg/PRoot 媒体端到端分别计证据 |

## 下一轮重构如何使用这些记录

1. 从 status、当前 Owner 请求及适用 ADR 确定范围；设计讨论直达 Harness §23，不顺序执行旧 §18 卡片或已完成批次。
2. 用当前源码核实哪些机制已经满足目标；本页只帮助定位原因和回归证据，不把旧类名、阈值、目录或版本当新接口要求。
3. 新决定获接受后更新同一主题 ADR 的有效正文与 Decision history，并由对应任务记录实际交付；不另建“兼容旧重构”的实现或多份 current 方案。
4. 需要当时原话时查 Git：`git log --follow -- <path>` 定位变更，再用 `git show <commit>:<path>` 查看原文。未提交提案以当时工作区为准；本索引不冒充其提交身份。

本次整理及门禁记录见[文档整理记录](../development/documentation-convergence-2026-09-29.md)。保留原任务、失败与限定验收证据，不迁走当前提案、不改实施顺序、不接受新增契约。
