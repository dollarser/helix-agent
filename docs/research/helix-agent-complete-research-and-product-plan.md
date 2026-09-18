# Helix 重构研究与后续产品问题

> 性质：研究与方案评审材料，不是实施清单或架构授权。
> 复核日期：2026-09-16；本地源码基线 `782a70424fa3430f62067e0abb1024f246d8aebf`，开始时工作树干净。本轮核对源码、有效 ADR、任务与既有证据，没有重跑产品测试、竞品横评或外部账号。
> 配套：[架构研究图集](helix-mermaid-architecture-diagrams.md)。状态与顺序只在[当前状态](../development/status.md)和[任务索引](../development/roadmap.md)维护；规范以[架构](../architecture/overview.md)及[ADR](../adr/README.md)为准。

## 1. 本文现在解决什么问题

最初的研究用于纠正现状判断、识别产品缺口；其中一部分如今已有代码或交付证据。继续把它们写成“下一轮从零建设”会导致重复实现。本文因此只保留三类内容：当前可复用基础、后续产品问题、尚需验证的研究假设。

- **已实现**：有当前源码，不自动等于完整设备/账号/发行验收通过。
- **已接受待交付**：有效 ADR 已决定方向，对应 HXA 仍有实现或验收缺口。
- **研究候选**：没有实施授权，须先说明收益、边界与证据。

不再维护 S0～S4 或 HX2 的第二套优先级，不保留“本节优先于历史正文”的叠加规则。旧稿与旧 hash 表可从 Git 历史读取；这里的源码链接使用仓库相对路径，复现时按上述基线 checkout，不把本地能力描述成公开远端状态。

## 2. 已有基础与真实剩余差距

| 主题 | 本次源码/证据锚点 | 剩余问题与任务归属 |
| --- | --- | --- |
| 执行入口 | [AgentRuntime](../../core/agent/src/main/kotlin/com/helix/core/agent/AgentRuntime.kt)、[AppAgentRuntime](../../app/src/main/kotlin/com/helix/app/chat/AppAgentRuntime.kt) 已有 submit/cancel/observe | 审计新增入口是否遵守同一准入，不再创建另一套 AgentRuntime |
| 单循环 | [AgentLoop](../../app/src/main/kotlin/com/helix/app/agent/AgentLoop.kt) 已承载模式共用执行 | 不再讨论“四套Loop统一”或仅为改名重构；收益要落在可测依赖和行为上 |
| 请求与压缩 | [ChatRequestAssembler](../../app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt)、[ContextCompactionRound](../../app/src/main/kotlin/com/helix/app/agent/ContextCompactionRound.kt) 是生产主干 | 保留历史配对、摘要、附件和恢复；旧 ContextBuilder 不再是待合并的第二套生产系统 |
| Prompt | [PromptRegistry](../../core/agent/src/main/kotlin/com/helix/core/agent/PromptRegistry.kt) 与内置 sections 已存在 | 可研究预算、冲突诊断和来源呈现，不把 Registry 再列为待新建模块 |
| Plan 与步骤 | [PlanReviewService](../../app/src/main/kotlin/com/helix/app/plan/PlanReviewService.kt)、[PlanReviewDialog](../../app/src/main/kotlin/com/helix/app/ui/PlanReviewDialog.kt)、[TaskLedgerProjection](../../app/src/main/kotlin/com/helix/app/todo/TaskLedgerProjection.kt) 已存在 | [HXA-192](../completion-records/HXA-192.md) 已交付用户审阅→执行→当前授权的设备证据；元数据不是工具批准 |
| 任务与产物 | [TasksScreen](../../app/src/main/kotlin/com/helix/app/ui/TasksScreen.kt)、[ArtifactsScreen](../../app/src/main/kotlin/com/helix/app/ui/ArtifactsScreen.kt) 已有 | 202/203/204补直接导航、实际可用性、恢复交互，不重建任务库 |
| MCP 与浏览器 | [McpToolDiscovery](../../app/src/main/kotlin/com/helix/app/mcp/McpToolDiscovery.kt) 已有发现；[BrowserScreen](../../feature/browser/src/main/kotlin/com/helix/feature/browser/ui/BrowserScreen.kt) 已有标签与下载区 | 207完善添加到使用闭环；浏览器后续优化须具体描述，不能再写“补tab UI” |
| Goal | [HXA-208](../completion-records/HXA-208.md) 交付完整模型工具与前后台连续运行；[GoalDriver](../../core/agent/src/main/kotlin/com/helix/core/agent/GoalDriver.kt) 已有 | Schedule、Channel、Hooks不因此自动获得授权；不把GoalDriver放回候选清单 |
| 授权 | 200/201的规则、审计和设置有交付证据；[ADR-PERMISSIONS-001](../adr/permissions/001-session-authorization.md) 是当前已接受目标 | [HXA-209](../completion-records/HXA-209.md) 已交付；旧三态工具偏好不是新方案验收证据 |
| 执行域 | developer 单APK内置PRoot/Subscriptions，私有进程共享UID；QuickJS isolated UID | [HXA-193](../completion-records/HXA-193.md) 收口资产/升级/CI，不重做单APK接线；同UID不承诺凭据、文件或网络隔离 |
| Git | [GitWorkspaceReader](../../app/src/main/kotlin/com/helix/app/git/GitWorkspaceReader.kt)、[GitStatusScreen](../../app/src/main/kotlin/com/helix/app/ui/GitStatusScreen.kt) 已有只读状态/diff | 不据此宣称完整仓库写操作、远端认证、clone/PR闭环；普通文件交付不依赖远程Git |
| 终端 | 同步Job与最终结果已有；[终端设计](../architecture/terminal.md) 已接受 | 194～199仍需命令详情、实时日志、后台owner、PTY、多会话与专项验收；Goal续轮不等于这些功能 |
| Provider验证 | [SGLang补验](../bug-fixes/2026-09-16-sglang-smoke-synchronization.md) 有指定端点的自动化证据 | 连接、能力、真实任务和全部供应商覆盖分别统计；不扩大成所有账号/模型已验收 |

源码中的历史 KDoc 不是反向修改现行行为的依据。例如 GoalState 注释仍有“只有显式用户动作创建run”的旧表述；当前续轮应对照 Driver、准入与 ADR-GOAL-001。注释漂移不能被当成尚未实现连续运行的证据。

## 3. 产品问题：补齐已有能力的使用闭环

产品目标仍是 Android 本机执行工作台：用户选择模型，在手机上组合文件、网页、代码与设备能力取得可检查、可恢复的结果。本机执行不表示模型权重本地化、所有数据不出设备或后台永不终止。

| 用户问题 | 应有体验 | 当前任务入口 |
| --- | --- | --- |
| 不知道任务做到哪里 | 真实动作、等待原因、取消中的结算状态；不伪造百分比 | [202](../completion-records/HXA-202.md) 已交付任务过程导航与跨页面入口，[194](../completion-records/HXA-194.md) 已交付命令详情，[203](../completion-records/HXA-203.md) 已交付产物交付 |
| 找不到命令输出或返回工作区 | 任务→命令详情→输出/产物→来源会话可直接导航 | [194](../completion-records/HXA-194.md)、202 |
| 生成了文件但打不开 | 展示真实scope、来源与可用性；区分删除、变更、撤权、不支持格式 | [203](../completion-records/HXA-203.md) 已交付 |
| 出错后不知道能否重试 | 历史恢复、结果对账、用户继续、新调用重试分别表达 | [204](../completion-records/HXA-204.md) |
| 环境未准备好 | 按目标展示模型、目录、系统能力与Runtime的下一步；被动浏览不启动执行 | [205](../completion-records/HXA-205.md) |
| 扩展安装后不会用 | 来源预览→启用→真实调用→禁用/修复，连接成功不冒充业务成功 | [207](../development/tasks/HXA-207.md) |
| 审批太碎或规则难懂 | 会话预设与工具启用分别设置，显示生效范围和未停止的旧任务 | [209](../completion-records/HXA-209.md) |
| 手机界面难检索、夜间难用 | 系统主题/深色与会话历史搜索；不重复已有审批折叠 | [191](../development/tasks/HXA-191.md) |

手动文件管理、浏览器、安装/修复各有独立应用服务，不必建立Agent Turn。手动授权不会自动扩大模型工具权限。任务页面从Turn/Goal/ToolCall/Job派生，不创建第二套执行状态机；标题不是任务身份。

Turn结束、Goal完成、业务结果正确是三个不同事实。没有结构化Act摘要时可以展示回复与遗留事项，不强制每次调用额外的turn.report。产物存在、可打开、内容满足需求也要分别验证。

## 4. 架构优化应保留的职责与不变量

| 职责 | 所有者与约束 | 优化的证明方式 |
| --- | --- | --- |
| 提交/停止/观察 | AgentRuntime与应用协调持有Turn身份；观察不启动，停止不等于副作用回滚 | 双击去重、旧页面取消不影响新Turn、非当前会话观察正确 |
| 上下文 | 生产Assembler、历史、附件、压缩组件；不复制历史源 | 同一输入的工具ID/顺序、图片绑定、摘要覆盖与恢复一致 |
| Prompt | 内置Registry与来源/作用域；排序不会提高信任 | 冲突/缺失/缓存诊断、来源可追溯、预算不挤掉关键上下文 |
| 工具 | Scheduler/Dispatcher归一效果、解析授权、开始前复检、持久结算 | 拒绝/取消/异常每槽都有结果；并发只针对证明无冲突的读取 |
| Goal | 生命周期、激活、run、预算分别有事实来源 | 新消息抢占、停止、崩溃disarm、累计预算不清零、不重复续轮 |
| Runtime | 应用拥有来源/授权，Runtime拥有进程与退出事实 | 原Job身份对账；未知副作用不重放；导回结果scope/hash一致 |
| 产品投影 | 查询已有事实并发出明确用户动作 | 页面重建无重执行，文件可打开，恢复不会多做一次 |

请求优化不能丢失：工具调用/结果配对、失败与拒绝记录、原消息附件、摘要覆盖区间、当前用户请求、Goal预算及下一步操作所需hash。模型可见精简结果与完整持久结果可以不同，但不能通过删去错误制造成功。

订阅通路是 ModelProvider→订阅客户端→私有Binder/PFD→订阅进程→服务端；模型输出ToolCall后才进入Dispatcher。不能将订阅Adapter画成普通工具执行器。正常API不返回token是模块契约，同UID不是安全隔离。

## 5. 授权与 Goal：已决定的部分不再作为开放问题

### 5.1 HXA-209 的目标，而非当前已交付声明

权威契约为[会话授权](../adr/permissions/001-session-authorization.md)：工具只有ENABLED/DISABLED；操作授权由FULL_ACCESS、WORKSPACE、READ_ONLY或CUSTOM决定，三个预设都允许Agent工具联网。

CUSTOM按效果与范围合并DENY > ASK > ALLOW；文件修改禁令必须跨write/edit/Shell等途径生效。统一工具禁网已按所有者取舍移出本轮，保留现有PRoot联网；禁用网络工具不等于阻止Shell联网。联网也不自动允许读取工作区外文件。特殊删除确认仅针对契约明确的rm命令规则，不推广成所有删除必询问。Chat/Plan模式边界仍独立成立。

需要询问的调用才产生精确证明，在执行开始时一次性消费；旧proof不能覆盖新禁用/DENY。规则收紧不伪称撤销已发生效果，已启动任务提供明确停止入口。Plan批准仅绑定审阅版本，不创建未来全部工具的批准。

因此不能再用“L2/L3恒出卡”“只能L0/L1免确认”作为新开发要求。209的首要技术风险是同UID执行域下约束是否真实可执行；无法保证且可能违反DENY的调用应拒绝，同时须证明正常读取/联网任务可用，不能全部拒绝后宣称功能完成。

### 5.2 HXA-208 已交付的 Goal 边界

[Goal契约](../adr/goal/001-lifecycle-and-completion.md)保留Room、审计、多维预算和模型完成报告，并增加独立激活与连续轮次。Activity退后台不自动解除用户激活；运行仍受系统可用路径、预算与中断约束。重启不恢复激活。

create_goal/get_goal/update_goal与goal.report已有实现；创建/编辑/恢复需要可信当前用户请求来源，Plan不借元数据操作激活Goal。报告绑定当前Goal/Turn，在合法结算时消费；普通文本结束不等于完成，可修复的测试失败不自动blocked。

持久生命周期与运行资格不是同一状态。Goal停泊后能否继续，取决于停泊原因、有效激活、前轮身份、预算和未决副作用；恢复旧记录不会自动满足这些条件。这里不复制全部状态迁移表，图集只表达决策关系。

## 6. 尚需研究的方向与进入开发的条件

以下不是新的HXA；是否立项必须回到任务/ADR入口。

| 候选 | 可能价值 | 最低研究证据与边界 |
| --- | --- | --- |
| Prompt/schema预算优化 | 减少上下文成本而保持工具可达性 | 固定任务比较token、选工具正确率和完成率；复用现有Registry与MCP发现，不默认加router或插件框架 |
| 文件变更预览 | 手机审阅时更容易理解真实修改 | 对比同一文件快照，处理编码/二进制/过期版本；不能靠模型给的diff证明实际变化；已有只读Git视图优先复用 |
| PDF/Office/OCR等解析 | 扩大可用输入类型 | 逐格式定义解析成功、截断、失败、资源/许可证与源引用；203不自动承担通用解析器 |
| Schedule/Channel自动激活 | 无人在前台时按明确意图工作 | 定义用户开启、事件身份与去重、错过/延迟/拒绝执行、停用和重启disarm、预算；普通提醒不能当自动执行证明 |
| 可选CompletionHook | 固定工作流增加确定性验收 | 用户选择、触发点、超时/取消/记账、可修复失败继续；不能恢复普通Goal全局verifier或让Hook自授权 |
| QuickJS Code Mode | 减少模型与工具之间往返 | 先证明收益，再设计跨UID身份、能力桥、嵌套等待/死锁、取消和资源限制；现有无特权Host Bridge决定不能默默放开 |
| 子Agent/Workflow | 可分解任务的有限并行收益 | 按[有界委托](../adr/agent/004-bounded-delegation.md)门禁；不从Spike推导生产启用、递归编排或远程Worker |
| 项目工作目录/扩展生命周期 | 减少安装和项目迁移摩擦 | Workspace与Connector的proposed决定各自审查，不因本文存在就接受 |

定时研究必须分别给出延迟、错过、重复事件、系统拒绝后台启动、等待审批/输入、用户停止、崩溃和来源撤销的结果。到期只提供尝试准入的机会，实际操作仍按当时会话规则；不是所有调用必出卡，也不是日程自动授予权限。平台精确限制在立项时重新核实官方资料，本文不冻结旧SDK规则和时长数字。

## 7. 对标方式与可重复产品验证

方向仍是先补使用闭环，再用真实任务证明差异价值。严密的内部状态或更多预算维度不是“超过竞品”的充分证据；会话授权更接近常见用法也不证明任务成功率更高。

竞品版本、来源与证据集中在[竞品研究](../product/competitive-landscape.md)、[证据台账](../product/competitive-evidence.md)及[横评方法](../product/competitive-evaluation.md)。本轮没有重查或实测竞品，不再复制未经逐项验证的产品能力表、分数或“明显领先”结论。引用DSH、Codex、Claude Code或Operit的机制前，须记录版本/commit、源码或设备证据、模型与输入、结果和平台差异。

| 样本 | 固定输入与检查 | 防止虚假改善 |
| --- | --- | --- |
| 文件整理 | 合成文件集、真实scope、预期分类及hash | 核查遗漏、覆盖、拒绝和取消，不只看模型总结 |
| 网页研究 | 固定页面或归档输入、带来源报告 | 分开记录事实/推断、来源支持性、文件能否打开 |
| 项目分析 | 小型项目、已知问题、预期修改与测试 | 已运行与未运行检查分开；不要求未实现的remote Git |
| Android操作 | 测试应用/合成数据、明确用户授权 | 比较实际目标状态、撤权和拒绝，不只看工具返回码 |
| 中断恢复 | 固定副作用边界、进程/网络中断点 | 对账、重复副作用、可继续与只能核查分别记录 |

记录每任务的首次可用结果时间、人工步骤/审批次数、结果质量、失败/放弃、恢复结果、重复副作用和资源成本。分母同时报告全部尝试与满足前置条件的任务；不能剔除配置失败后只展示高成功率。模型报告完成与独立结果检查分别统计，未知成本不记零。

先建立基线再设改善目标。没有用户研究就不编造7/30日复用率；不以工具数量、测试数量、抽象数量或主观架构分数代替产品价值。测试与遥测不默认采集正文、凭据或真实用户文件。

## 8. 维护与验证

进入开发的内容只在对应任务维护范围、顺序与验收；本文保留问题与取舍，图集保留职责/边界，不复制第三份任务状态。已实现项不得继续标为“候选新建”，proposed不得画成已授权。

本轮只做文档与图形校验，不新增产品验收结论。实际检查命令和图形产物随本次修订记录。研究材料不能覆盖架构规范、用户授权或历史交付证据。
