# Helix 重构研究与产品演进候选方案

> 文档性质：研究、候选需求与重构评审材料，不是新一轮实施授权。
> 本轮复核：2026-09-13；源码基线为本地 `main` 的 HEAD `0bcd9d34d299974f950094101e5d34c956ebe569` **加当时未提交工作区**。
> 配套图集：[现状架构与候选演进图](helix-mermaid-architecture-diagrams.md)。
> 2026-09-14 分类更新：两份材料统一归入 `docs/research/`。下文的“现状/本轮”均指 2026-09-13 的 main 研究快照，不是 Harness 分支的当前实现；源码链接固定到取证结束时的 main 修订。重构收尾以[专项交接](../development/harness-2.0-next-work.md)和[实施状态](../development/status.md)为准。

## 1. 文档职责、证据与阅读路径

### 2026-09-14 演进更新：本节优先于历史快照

本次增量基线为 `worktree-harness-2.0` 的 `a4a64039` 加未提交的 HXA-192/193 与并行修复；不是该 commit 单独包含下述能力，也不代表 main 已合入。下文固定源码链接保留历史取证用途；涉及 Runtime 的当前决定按 ADR-0049，日志契约按 accepted ADR-0050、后台/手动终端按 accepted ADR-0051 判断。

| 事项 | 当前结论 | 与原方案的关系 |
| --- | --- | --- |
| 任务体验、产物与恢复 | 继续复用 Turn/Goal/ToolCall/Job 事实，不建第二套 Task 执行状态 | 保持主方向 |
| 执行入口统一 | Agent 请求统一准入、取消、观察；手动文件、浏览器和终端各经应用服务 | 修正原稿“所有入口进入 AgentRuntime”的过度归并 |
| Runtime 打包和权限 | ADR-0049 accepted：developer 单 APK，PRoot/Subscriptions 私有多进程共享主 UID；consumer 排除；QuickJS 仍 isolated UID | 已授权的实质架构变更，不是纯打包优化 |
| 终端与后台命令 | HXA-194～199 是后续开发计划；PTY、日志 IPC、detached owner 尚未实现，ADR-0050 日志/职责 accepted，ADR-0051 启用 accepted | Developer UX 扩展，不是 Harness 主干收尾门禁 |

PRoot 现为可信开发者执行环境，不再承诺强制离线、主数据或订阅凭据的 UID 隔离；输入快照仍是一次性 Job 的数据契约。订阅模块仍负责自己的 OAuth/Keystore，正常 API 不返回 token，但共享 UID 不构成对同 UID 代码的凭据保护。生成代码继续在 `:proot` 而非 UI 主进程执行。实现和设备证据见 [单 APK 专项记录](../development/integrated-developer-runtimes.md)，不能用决策接受代替整体验收。

本文负责回答：当前有哪些能力、哪些产品问题值得解决、职责如何分配、候选项如何拆分和验收。图集只负责表达调用关系、执行域、状态和恢复流程，不再维护另一套优先级、功能评分或任务清单。

| 材料 | 唯一职责 | 使用边界 |
| --- | --- | --- |
| 本文 | 事实复核、产品假设、候选设计、依赖与验收建议 | 不直接创建 HXA，不改变有效 ADR |
| [配套图集](helix-mermaid-architecture-diagrams.md) | 当前实现视图、渐进目标视图、研究扩展视图 | 图中的候选框不是现有模块或必须创建的类 |
| [实施状态](../development/status.md) | 当前进度、在途工作、阻塞与证据入口 | 不用本文快照替代实时状态 |
| [路线图](../development/roadmap.md)与[验证矩阵](../development/verification-matrix.md) | 已授权 HXA 范围、顺序与验收 | 候选项必须正式落入范围后才进入实现 |
| [架构](../architecture/overview.md)与[ADR](../adr/README.md) | 规范性契约与决策理由 | 历史伪代码、旧落位表仍须对照生产实现及后续 ADR |
| [竞品研究入口](../product/competitive-landscape.md)与[证据台账](../product/competitive-evidence.md) | 竞品资料、证据等级和长期维护 | 本文仅保留与重构相关的研究问题，避免复制完整横评 |

原稿的综合报告、Harness 专题、产品横评和行动清单存在重复与相互冲突的 P0。本版按“事实 → 产品问题 → 职责契约 → 候选批次 → 验收”重组，保留有价值的研究主题，撤回没有证据支持的成熟度排名和一次性全套 Harness 2.0 排期。

### 1.1 基线与可复现性

本轮实际读取本地源码、README、实施状态、HXA-190/191 和相关有效 ADR，并抽查 DeepSeek、Android 官方资料。未运行产品 JVM、设备或真实账号测试，未逐一安装竞品；历史完成记录只证明其记录范围。

两份原稿在本轮开始时均为未跟踪文件。工作区另有实现、测试、ADR 和文档的并行修改，因此不能把本文的“当前实现”解释成公开 GitHub `main` 或上述 HEAD 单独可复现的能力。未查询远端发布状态。HXA-190 仍在进行中，HXA-191 有已授权增量及局部实现；本次文档修订不关闭这些任务，不修改其他 worktree。

检查期间其他工作将本地 HEAD 推进至 `27b643e895591464d88ea71d48528635768bfd60`，两份原稿也已被跟踪；本轮未执行提交。重新核对下表 8 个源码文件的 hash 均未变化，故保留开始时的取证基线，并记录这次并行 Git 状态变化。

关键读取文件的 SHA-256 如下，用于识别**本次内容快照**，并非完整可构建发布快照：

| 文件（仓库相对路径） | SHA-256 |
| --- | --- |
| `app/src/main/kotlin/com/helix/app/chat/ChatModelLoop.kt` | `96cc914dbf2026a648f4cb58570b3595d3621b44620af0715bf822c81afa280b` |
| `app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt` | `77ed822748e52861ee8fb1ca11ed7397ce5b925e8648fa1fa6f5db0e530c5e6e` |
| `app/src/main/kotlin/com/helix/app/chat/ChatEnvironmentContext.kt` | `f918d383048999bd64d2790fa364f7591adda6cd8d516c29058743667f9a0409` |
| `core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt` | `c94b429c94fa05996a08548ca84773a1c4b23adf96670df6e227b29eb1dd4c9a` |
| `core/model/src/main/kotlin/com/helix/core/model/GoalState.kt` | `f048d8325a516ad786b432ac01c3af9b7f5460dd0fcfad10745158a7e66d35c3` |
| `app/src/main/kotlin/com/helix/app/chat/GoalRunSettlement.kt` | `9583ac02fa0ad54a14a25a4781f8845b3e6dc269d6b7741376450b8bc7a6e87a` |
| `app/src/main/kotlin/com/helix/app/mcp/McpToolDiscovery.kt` | `442fb8b95a160a06553b2039a8d59872596b45e70c75336abd060e57269a3562` |
| `feature/browser/src/main/kotlin/com/helix/feature/browser/ui/BrowserScreen.kt` | `d44f72d2c299f075621293bf7eaa31c921423ffe3766d9be3e87e53385f74589` |

本机忽略目录 `build/docs-refinement-2026-09-13/` 留存原稿、开始时 Git 状态、已跟踪差异和校验输出。该目录不是发布证据，也不是完整工作区备份。后续实施必须刷新 HEAD、dirty paths、受影响文件 hash 和测试基线；不能仅复用本文日期。

### 1.2 证据标记

- **已实现**：本轮有生产源码依据；是否通过完整验收另行说明。
- **历史验证**：引用完成记录，注明原任务、设备或样本范围；不折算为本轮测试通过。
- **计划/候选**：可讨论的设计，只有已进入路线图的部分具有实施范围。
- **研究**：涉及新运行语义、权限或平台可行性，需先决策与验证。
- **待核验**：尚无足够源码、版本或设备证据；不等同“功能不存在”。

## 2. 复核结论与事实修正

建议采用“任务体验优先、按实际问题渐进收敛架构”的方向。已有单 Agent 循环、工具管线、持久历史、Goal 和恢复机制构成可复用基础；新增抽象必须有具体调用者、唯一状态所有者与迁移收益。

### 2.1 当前生产事实

| 主题 | 本轮核对结果 | 对候选方案的影响 |
| --- | --- | --- |
| 四种模式与 Loop | [ChatModelLoop.runToolLoop](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/ChatModelLoop.kt) 接收 `RunControlConfig`；Chat/Plan/Act/Goal 共用循环，差异通过工具曝光、Policy 和 Goal 接线实现 | 撤回“四套 Loop 尚未统一”。更名是可选表达优化，不单独构成架构收益 |
| 双 Context 路径 | [ContextBuilder](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/core/agent/src/main/kotlin/com/helix/core/agent/ContextBuilder.kt) 的 `build` 调用在本轮检索中位于测试；生产使用 [ChatRequestAssembler](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt)、[ChatHistoryBuilder](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/ChatHistoryBuilder.kt) 等 | 以生产路径为主干吸收来源、信任和预算规则；不反向替换为旧 Builder |
| 上下文压缩 | Loop 每次请求前调用 [ContextCompactionRound.prepare](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/ContextCompactionRound.kt)，摘要结算后重新组装 | 不能用旧 Builder 的近期保留策略评价整个生产压缩系统 |
| 内置 Prompt | 工作区已有 [ChatEnvironmentContext](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/ChatEnvironmentContext.kt) 和 `app/src/main/resources/prompts/`，按文件工具与 Plan 模式选择模板 | Prompt sections 是对已有模板的增量整理，不从零建设插件 Registry |
| 工具按需发现 | [McpToolDiscovery](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/mcp/McpToolDiscovery.kt) 已有 `tools.search`、会话曝光窗口与模式过滤交集 | 剩余问题是内置工具选择、schema 总预算及发现入口可达性，不另造 MCP 发现协议 |
| 浏览器 | [BrowserScreen](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/feature/browser/src/main/kotlin/com/helix/feature/browser/ui/BrowserScreen.kt) 已调用 `TabStrip` 并展示下载区 | 删除“P0 补 tab UI”；持久下载历史、检索、恢复体验分别评估 |
| 任务与产物 | [当前接口](../development/status.md#current-interfaces)、[HXA-177](../completion-records/HXA-177.md) 已有跨会话任务投影、后台结果回收；工具结果和 Artifact refs 已存在 | Tasks 与产物入口先聚合已有数据，不增加另一套执行状态库 |
| 配置与审批 | HXA-190/191 已有审批折叠、工具用途摘要、组件安装等工作区增量 | 先验证当前界面剩余摩擦，避免重复实现整个 Capability Center |
| Goal 完成注释 | [GoalReducer](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt) 当前已写明 ADR-0040 模型报告完成；复核所指旧完成注释已被并行工作修正 | 删除“立即清理未修缺陷”的重复任务；本轮不改 Kotlin |
| Goal 预算注释 | 同文件 KDoc 仍有历史 `WakeUsageReported → PAUSED` 描述，而实际预算处理进入 `BLOCKED`，与 [GoalState](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/core/model/src/main/kotlin/com/helix/core/model/GoalState.kt) 和 ADR-0039 一致 | 图与本文按当前处理绘制；将这处剩余注释漂移作为后续源码维护项，不据旧注释改状态机 |
| Git | [ADR-0008](../adr/0008-git-workspace-management.md) 已接受，但 [当前限制](../development/status.md#known-limitations) 明确持久 Git Workspace/结构化 UI 未实现 | 离线 Job 中有 Git 不代表持久仓库管理；Git 不进入普通文件体验 P0 |
| 子 Agent | [ADR-0009](../adr/0009-bounded-local-orchestration.md) 有设计与隔离 Spike，生产 child/Workflow 未启用 | 架构接受不是产品实现证据，也不是本轮实现授权 |

### 2.2 复核意见的采纳方式

| 复核建议 | 本版处理 |
| --- | --- |
| 固定源码基线、纠正现状、拆开现状图与目标图 | 采纳；新增工作区边界、hash 表和图例 |
| 优先任务进度、产物、恢复和审批体验 | 采纳；作为现有能力的增量投影，优先验证用户收益 |
| 统一入口与请求组装 | 有条件采纳；先定义 submit/cancel/observe 所有者和事务，不一次创建八个服务 |
| Plan、Todo、Act 完成协议 | 调整；先轻量显示与用户审阅，结构化元数据另立契约；不强制每次 Act 调用新工具 |
| GoalDriver、Schedule、Hooks、Code Mode | GoalDriver 已由 ADR-0053/HXA-208 单独授权；Schedule、Hooks、Code Mode 保持研究 |
| Goal 旧完成注释清理 | 当前工作区已完成，撤回重复动作；保留新发现的预算 KDoc 漂移说明 |
| 竞品强弱与成熟度评分 | 撤回无逐项依据的分数；改为可验证的研究问题和样本要求 |

## 3. 产品方向与近期体验范围

候选定位：**Helix 是 Android 上的 AI 执行工作台，连接用户选择的模型，组合文件、网页、代码和手机能力完成可检查、可恢复的任务。** 模型推理位置取决于 Provider；本机执行不代表所有数据不出设备，不承诺任意任务被系统中断后都能直接续跑。

主要场景是手机上的文件整理、带来源的网页研究、日志/项目分析和有界 Android 操作。Coding 场景先使用已授权 Workspace 与可用执行能力；远程 clone、PR 和持久 Git 不作为现成演示前提。

Standard 继续是面向商店的完整产品，Advanced、构建 flavor 和分发渠道分别表达运行配置、编译接线和发行约束。任务页面、产物与配置优化不能顺带把 Standard 缩成聊天壳；渠道能力差异须有对应政策或审核依据，沿用 [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)。

### 3.1 导航与页面职责

先在现有导航内改善任务聚合、摘要和入口，再用用户观察决定是否升为一级页。以下是职责模型，不是立即改成五个 Tab 的 UI 要求。

| 页面/入口 | 应负责 | 数据和动作边界 |
| --- | --- | --- |
| 对话 | 输入、模式、历史、即时进度、当前任务结果 | 发起/停止 Agent 请求；历史输出继续可见 |
| 任务视图 | 跨会话运行、等待用户、已结束、失败/中断和结果回收 | 聚合已有 Turn/Goal/Approval；不自行启动模型 |
| 文件与 Workspace | 独立文件管理、打开产物、变更查看 | 无模型配置也可使用；手动 SAF/共享存储授权不扩大 Agent scope |
| 浏览器 | 标签页、地址、网页与下载 | 保留独立入口和 Activity/WebView owner；不要求手动浏览经过 Loop |
| 能力与设置 | 安装、连接、授权、范围、修复、撤销和 Provider 配置 | 用户点击触发安装/验证；被动刷新不冷启动 companion |

### 3.2 任务显示模型

Task 首版是读模型：独立 Turn 以 `turnId` 标识；Goal 以 `goalId` 聚合关联的 run/Turn。不能按标题合并，也不能通过复制状态枚举引入第二套完成判定。

| 展示内容 | 事实来源 | 避免的误读 |
| --- | --- | --- |
| 当前活动、工具进度 | 持久 Turn、ModelCall、ToolCall/结果及运行快照 | 已完成 3 次调用不代表业务进度 3/8 |
| 等待用户 | 待审批、输入、能力缺失、Goal blocker 的类型化原因 | `Needs You` 是界面分组，不是新的 Goal 状态 |
| 完成情况 | Turn 终态、Goal 报告、实际工具验证、剩余事项分别显示 | “本轮结束”不自动显示成“用户任务已完成” |
| 产物 | 已存在的 Artifact/ref、文件与来源调用 | 工具声称写完不保证文件可打开或满足需求 |
| 恢复 | 历史恢复、任务中断原因、对账结果 | “已恢复记录”不等于“已恢复执行” |
| 成本与预算 | 已记账用量、未知 usage 的既有处理 | 更换界面或新建 run 不能清零 Goal 累计预算 |

简短任务允许只有结果摘要；复杂任务可展开计划、实际检查和剩余工作。没有结构化报告时显示“本轮结束，见回复”，不直接记失败，不额外强制 `turn.report`。Goal 继续使用现有 `goal.report`。

### 3.3 产物、恢复和审批的首版细节

产物入口优先索引已有引用，展示名称、类型、来源任务、生成时间及可用性；文件被删除、移动、SAF 授权撤销时显示具体原因。打开与分享使用现有用户动作路径；模型外发仍走普通 Tool Policy。索引不得复制所有文件、建立不受 Workspace 配额管理的新仓库，或把任意下载自动当作可信报告。

恢复摘要分别展示“已确认完成”“待核查”“未开始”。待核查操作先按原 `jobId/taskId`、输入 hash 和结果记录对账；结果未知时提供检查与处置入口。继续创建新的有界执行，已完成调用不重放；结果回收只标记已查看，不自动向其他会话发送或调用模型。

审批默认展示操作目的、真实目标、范围、变更摘要和批准方式，完整字段可展开。Trusted Workspace 和长期规则仅用于既有允许的 L0/动态 L1；L2/L3 仍是精确调用或完整披露的有限批次。不能用“允许本任务”覆盖任务后续未知写入、删除或外发。依据为 [ADR-0012](../adr/0012-capability-first-advanced-grants.md)，[ADR-0005](../adr/0005-standard-advanced-safety-profiles.md) 已被取代。

## 4. 渐进重构的职责与契约

### 4.1 所有权分配

以下“候选职责”可先由现有类/纯函数承载，只有实际依赖需要时再形成接口或模块。`core` 不依赖 app UI、Room、OkHttp 或 Android 执行实现。

| 职责 | 当前承载 | 候选收敛方式 | 不应接管的职责 |
| --- | --- | --- | --- |
| Agent 请求准入与取消 | `ChatService`、运行控制、Goal 启动接线 | app 层入口 facade；逐个迁移已有调用者 | 手动文件操作、组件安装、浏览器 owner |
| 活动 Turn 与事务 | `TurnCoordinator` | 保留唯一所有者及持久结算边界 | Provider 协议、Tool 权限决定 |
| 模型/工具循环 | `ChatModelLoop` | 保留单循环，必要时更名并缩小 UI 依赖 | 自动调度、计划存储、插件系统 |
| 请求与上下文 | `ChatRequestAssembler`、历史/附件/压缩组件 | 一条生产请求主干；来源与预算契约渐进进入 | 旧 Builder 反向替换生产历史 |
| Prompt sections | 内置模板、环境和 Goal 上下文 | 有序、可追溯的内置组件 | 修改 Policy、创建用户授权 |
| 模型协议 | `ModelProvider` 与各 adapter | 统一内部请求/事件；供应商格式留在 adapter | 执行模型返回的工具 |
| 工具与授权 | Scheduler、Dispatcher、Capability、Policy、Approval | 保持原管线与证明消费点 | 由模型决定并发安全或批准自己 |
| Goal 生命周期 | reducer、运行准入、预算及 `GoalRunSettlement` | ADR-0053 的独立激活、连续轮次与模型报告 | 由 UI Todo 或定时回调决定完成 |
| 任务/产物页面 | 查询、投影与文件引用 | 组合既有事实，增加必要展示元数据 | 独立 Task 执行器或第二套事件存储 |
| Runtime 生命周期 | QuickJS/PRoot/Subscriptions 的各自 client/supervisor | 按 ADR-0049 保持进程职责、绑定、取消与 Job 对账；QuickJS 仍 isolated UID | 在 UI 主进程运行生成代码；把模块 token 所有权误称 UID 隔离 |

### 4.2 统一执行入口：先明确语义

`submit/cancel/observe` 是候选端口的职责描述，不是已实现 API。不要把泛化 `resume(turnId)` 同时用于历史加载、结果查询、工具重试和 Goal 继续。

| 操作 | 输入与结果契约 | 事务和竞争边界 |
| --- | --- | --- |
| submit | 绑定 session、用户输入/附件快照、模式和运行配置，返回稳定 Turn 身份或明确拒绝 | 准入与 Turn/初始输入提交须有单一所有者；提交成功后才允许执行。重复事件须去重；忙碌拒绝保留草稿/附件 |
| cancel | 精确 turn ID 与操作原因；取消已结束任务为幂等结果 | 先记录停止意图，禁止新工作，再传播取消并结算；旧页面不能取消同会话新 Turn |
| observe | 按稳定 ID 查询快照与增量 | 订阅不启动任务；观察者消失不等于取消；UI 事件不能成为唯一持久事实 |
| continueGoal | 用户动作、精确 Goal 身份、当前状态与预算 | 复用既有准入；新 run 与绑定落库后启动；blocked 先显式复查 |
| reconcile | 原 execution/job/task 身份及输入指纹 | 查询既有工作结果，不重新提交命令；Room 事务不包住外部副作用 |

入口 facade 不增加第二份内存运行表。跨会话并发、停止/提交竞争、Activity 重建、绑定失效和持久化失败必须保留当前语义。迁移成功的证据是调用者和状态所有者收敛，不是类名包含 `Agent`。

### 4.3 请求、历史与压缩

迁移次序：先固定当前合成请求 fixture，再提取纯组装职责，最后逐项吸收旧 ContextBuilder 的可用规则。首批只在现有生产路径使用，不双写历史，不让新旧路径分别调用模型。

必须保留的请求不变量：

1. assistant 工具调用与工具结果的 ID 配对、顺序和历史缺陷兼容；失败、拒绝、取消也可重建。
2. 图片引用与原用户消息/重试绑定，防止附件错配或重复注入。
3. 持久摘要、摘要覆盖范围和摘要后重建；每次请求仍进行窗口与预算准入。
4. 当前用户请求、未完成工具状态、Goal 约束及必要系统指令不因简单近期裁剪丢失。
5. 模型窗口、工具 schema、图片成本、输出预留、Turn 与 Goal 剩余额度联合约束；摘要目标长度不等于摘要调用硬额度。
6. 工具结果完整持久记录与模型可见精简投影分离，保留后续操作需要的 hash、引用和错误原因。
7. Provider 编码保持协议差异；例如工作区订阅修复将 SYSTEM 内容映射到相应 instructions 字段，不能统一成所有 Provider 都接受同一 wire message。

验收以同一持久输入生成的请求结构、工具配对和附件绑定为主，再比较 token/时延。允许明确批准的文案差异；禁止为了缩短上下文删掉恢复所需信息。旧 Builder 是否保留、重用或删除由依赖审计决定，不能仅凭“双路径”先删除测试。

### 4.4 Prompt sections 与信任边界

DeepSeek 官方区分静态/可求值的 `PromptSection` 和动态 `PromptContext`，提供顺序与 scope 规则。这支持 Helix 整理内置提示词，但不证明应复制其完整插件框架。[官方说明](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/system-prompt.md)

Helix 首版候选字段：`id/order/scope/source/trust/contentHash/content`。字段是设计说明，未形成公共 schema。建议组装顺序为：内置身份与运行约束 → 模式/Goal → 当前能力与 Workspace 信息 → 工具指导 → 用户上下文及外部材料。外部内容使用明确来源包装；具体 Provider role 由 adapter 映射，role 和文本位置不授予权限。

| 来源 | 处理规则 |
| --- | --- |
| 随应用发布的内置模板 | 白名单选择，重复 ID/顺序冲突可诊断；本次请求使用一致快照 |
| 用户直接要求 | 保留为用户输入；不能被项目文件覆盖成“用户已批准” |
| Workspace 项目指令 | 仅从已选 scope 加载，记录路径、hash、版本；冲突可见，不能覆盖系统边界或用户当前要求 |
| Skill、MCP、A2A、网页、文件与通知内容 | 标记来源与不可信数据边界，不接受其声明为 Policy/Approval |
| 动态能力信息 | 来自实时状态的提示，不替代执行开始前的 Capability/Policy 检查 |

排序、scope 同名覆盖和加载项目指令都不提升信任级别。首版不开放外部注册器、任意可执行 provider 或“整段替换系统提示词”能力。取消组装、模式/模型/工作目录切换、模板缺失与过期缓存均需可诊断；不要把所有系统状态无预算地注入每次请求。

### 4.5 工具曝光与执行管线

继续复用 `tools.search` 与会话 MCP 窗口。内置工具选择可按真实任务 fixture 评估，但必须保留发现入口、必要的 Goal 报告工具和跨领域下一步可达性；选错工具集应能恢复，不能因隐藏 schema 放宽 Policy。比较 schema tokens、工具选择正确率和任务完成率后再决定是否增加 router。

工具调用的约束仍来自 [工具编排规范](../architecture/mobile-tool-orchestration.md)：规范化参数/作用范围、schema、实时能力与 Policy、审批解析、执行限制、结果验证、审计与持久回填。并发由平台根据规范化影响范围判定，只有证明无冲突的读取才并行；结果按原调用顺序进入模型。

需要审批证明时，只有类型化 `APPROVED` 可消费；证明在执行开始阶段消费，排队时取消不得提前消费。开始后取消、超时或异常可能已有副作用，必须持久结算为可确认结果或待核查。验证失败不自动证明“未执行”；只有已确认无副作用才允许原契约内有界技术重试。

订阅 Provider 是模型调用链：`ModelProvider → 订阅客户端 → 私有 Binder/PFD → :subscriptions 进程 → 服务端`。它不放在 ToolDispatcher 的普通执行目标下面；模型返回 ToolCall 后才进入工具管线。developer 当前共享主 UID，token 正常接口不外传是模块契约，不是安全隔离保证；按 [ADR-0049](../adr/0049-integrated-developer-runtimes.md) 和 [ADR-0021](../adr/0021-third-party-subscription-protocol-adapter.md) 的未被取代部分描述第三方协议适配，不宣称官方 CLI 已在 Android 可用。

## 5. 明确的新功能：分别立项，不混入纯重构

### 5.1 Plan 审阅

普通任务可直接 Act，不强制进入计划表单。首步可在现有只读 Plan 回复上增加“继续规划、修改、按此执行”的用户交互。`PlanArtifact` 数据类型存在不等于完整审阅/版本化执行入口已经落地。

结构化 Plan 的候选元数据包括目标、假设、步骤、预期变更、风险、验收建议和版本。`planHash` 只绑定用户看过的计划版本，用户执行按钮表达按该方案开始工作的意图；后续写入、删除、外发仍重新经过既有授权判断。

如果新增 `plan.submit` 或模型 Todo 更新，需独立定义内部元数据操作契约：只绑定当前 session/Turn、禁止任意路径或其他 Goal ID、有大小/版本/更新冲突限制、保留来源与审计；计划导出到文件另走写入授权。不能把持久元数据写入伪装成普通 READ_ONLY，或因此让 Plan 模式任意写文件。涉及模式 Policy/公开契约变化时按 ADR 流程评审。

验收覆盖：计划过期、用户修改后重新审阅、重复点击执行、切换模式、重启、取消、文本计划回退及高风险调用仍需精确审批。

### 5.2 Todo 与 Act 摘要

Todo 是模型工作记忆，Plan 是方案，Goal 是持久目标。首版可先显示从工具活动得到的进度摘要；只有用户观察证明结构化步骤有价值时才增加独立 Todo 元数据。

候选 Todo 只记录步骤 ID、文本、状态和可选来源引用；`done` 表示模型更新的进度，不直接改变 Goal、证明测试通过或创建工具权限。版本冲突、删除/重排、跨会话越权和摘要后恢复必须定义。Act 的 summary、remaining、artifacts、checks 可作为显示契约，不强制额外模型调用或 `turn.report`。

### 5.3 文件变更预览与 Git

普通文件变更预览可从现有操作/hash/输出快照派生，不依赖 Git。预览须绑定真实前后版本，执行前发现输入变化时重新确认，不能用旧 diff 批准新内容。

持久 Git 单独沿 ADR-0008 与后续 HXA 推进：明确权威仓库位置、完整事务、锁与并发、`.git` 一致性、hooks/filter 等隐式执行、空间预算和中断对账。离线 `status/diff/log` 与 `init/add/commit` 的权限分别评估；remote Git、凭据、PR 和联网执行域另行决策，不放进首版文件 UI 增量。

### 5.4 文档附件

PDF/DOCX 解析是新增输入能力，不是“支持文件导入”的自然结论。每种格式分别定义文本提取、页码/段落定位、表格和图片边界、加密/损坏/空文本行为、内存/时间/大小预算、取消和临时文件清理。OCR、音视频、PPT 与重排版另行评估。

输出作为带来源的派生文本/Artifact，不能把文档内指令提升为系统指令；外部 OCR/解析服务还需数据出网范围。依赖选型、许可证和恶意样本测试完成前，不承诺“任意文档理解”。

### 5.5 移动入口与模板

Share 先补足已支持文本/图片/文件的输入流转：URI 生命周期、权限失效、输入大小、目标会话、用户取消、重复 Intent 和忙碌时草稿保留。Widget、语音、默认助理分别评估用户启动行为和权限，不因“入口统一”自动获得后台执行许可。

可复用任务模板先保存用户可查看、可编辑的输入与参数，运行时重新计算能力和审批。Skill、Connector 和 A2A 的界面可降低配置成本，但配置成功、登录成功、能力探测成功与真实任务成功分开显示。持久记忆、学习型移动 Skill 和市场不作为近期重构依赖。

## 6. 自动化与扩展研究边界

### 6.1 Goal 完成与现有运行语义

当前采用 [ADR-0040](../adr/0040-model-judged-goal-completion.md)：模型通过当前活动 Goal/Turn 的 `update_goal`（兼容 `goal.report`）提交 `complete/in_progress/blocked`；Harness 在合法 Turn 结算时消费最后有效报告。取消、用户暂停、未决副作用和预算处理优先。没有报告保持可继续，不因缺少证据绑定阻塞；不恢复 ADR-0028 的独立 verifier。

[GoalRunSettlement](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/GoalRunSettlement.kt)、[GoalBlockerResolution](https://github.com/dollarser/helix-agent/blob/27b643e895591464d88ea71d48528635768bfd60/app/src/main/kotlin/com/helix/app/chat/GoalBlockerResolution.kt) 与 reducer 共同决定实际行为。普通结束可 park 到 PAUSED；预算不足/未知副作用可 BLOCKED；修复后显式复查转 PAUSED，再由用户 Continue。终态 COMPLETED/FAILED/CANCELLED 不直接重新激活。

[ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md) 的 run/wake/累计预算仍有效；每轮显式继续约束已由 ADR-0053 部分替代，首次激活与中断后恢复仍须用户动作，预算阻塞与用户暂停结合 [ADR-0039](../adr/0039-background-results-and-goal-blockers.md)，完成部分以 ADR-0040 为准。不能只读 ADR-0004 的历史 PAUSED 描述，也不能把某个上游 Driver 的行为直接当成 Helix 当前运行契约。

### 6.2 GoalDriver：已验收的行为扩展

2026-09-16 实现及设备证据见[HXA-208](../completion-records/HXA-208.md)。下述边界已落实，不能再列为仅研究或下一轮待做。

2026-09-16 所有者已通过 [ADR-0053](../adr/0053-goal-continuation-activation.md) 授权 [HXA-208](../development/roadmap.md#hxa-208-完整-goal-工具与前后台连续运行)：实现同会话前后台自动续轮和完整 create_goal/get_goal/update_goal，复用现有执行、累计预算、模型报告与恢复。独立激活不是桌面专属机制。Room v19 保存会话归属与编辑版本，激活留在进程内；应用重启不自行恢复。定时及 Channel 激活仍不在本次授权内。

DeepSeek 的 Driver 在已激活、整体空闲且有剩余轮次时续跑；恢复/分叉不自动重新激活，用户工作与停止影响自动准入。借鉴点是显式运行激活和竞争防护，不是后台常驻承诺。[官方 Driver 契约](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/goal/goal-round-driver/README.md)

以下为本次 HXA-208 的实施和验收边界，验收状态以完成记录为准：

| 问题 | 候选要求 |
| --- | --- |
| 激活 | 用户明确选择连续运行范围、预算与停止条件；activation 与持久 Goal 状态分离 |
| 准入 | 同一 Goal/会话只有一个有效轮次预约；按身份、版本和序号去重，持久提交后才启动 |
| 用户新消息 | 先撤销未启动的自动预约；明确当前轮取消/排队规则，不能在用户新请求后执行旧计划 |
| 停止 | 立即关闭后续准入并取消当前轮；已开始副作用继续结算，不能宣称撤销了已发生动作 |
| 预算 | 所有模型、工具、token、执行时长按原 Goal 累计；预约不凭空收费，实际执行不漏记或重复记 |
| 崩溃/恢复 | 重启默认未激活；先恢复历史与结果，用户重新激活后才允许执行 |
| 阻塞/失败 | 可修复失败仍可推进；没有剩余主动步骤或存在实际依赖阻塞才停泊；取消不自动重启 |
| 验收 | 停止竞争、消息抢占、重复调度、版本失效、落库失败、进程死亡和预算边界均有证据 |

### 6.3 CompletionHook 与其他 Hooks

CompletionHook 只能是**用户选择的特定工作流验收门禁**，独立于普通 Goal 的模型完成报告。例：修复测试任务执行检查失败，且仍能改代码时继续修复；只有无法主动推进、需要外部依赖或预算/副作用门控时才进入相应阻塞/暂停路径。不能把任意测试失败直接映射成 BLOCKED。

后续 Hook 契约须规定触发点、输入来源、取消/超时、失败处理、最大调用次数与费用记账。需要执行命令的 Hook 仍创建普通 ToolCall，并满足原授权；不能用用户勾选“验收”覆盖未来任意 shell。观察事件可先作为内置日志/投影，外部可执行插件注册器属于另一项设计。

模型 Reviewer 可作为解释建议，不能铸造 Approval Proof 或替代用户规则授权。不复制“让第二个模型批准主模型”的新权限主体。Hooks/Skill 也不能通过改写结果隐藏失败或改变审计事实。

### 6.4 QuickJS Code Mode

当前 QuickJS 位于非导出的 Android isolated Service，没有特权 Host Bridge。`helix.files.read()` 等回调会新增隔离 UID 向宿主请求能力的桥，即使再次进入 Dispatcher 也不是低成本小扩展；必须先处理 [本地执行契约](../architecture/local-code-execution.md) 与相关 ADR 的变更。

研究至少包括：调用身份与输入快照、跨 UID 请求/返回协议、审批等待期间生命周期、嵌套调用与死锁、级联取消、CPU/内存/输出/调用次数预算、进程死亡与部分结果、未知副作用对账。脚本不能得到主 App 的 Context、ContentResolver、凭据、批准证明或任意网络桥。

首选继续用现有 QuickJS 做无 Host Bridge 的纯数据转换，并通过普通模型 ToolCall 组合文件等能力。只有固定任务证明往返/token 收益足以承担新边界成本，才推进 Code Mode。

### 6.5 Schedule 与 Android 可行性

当前 checkpoint reminder 是可延迟提醒，通知到达不运行模型；用户点击才进入现有继续路径。Daily/Weekly 自动执行属于新功能，不能用 FGS 字样代替平台方案。

Android 对后台启动 FGS 有限制；涉及 while-in-use 权限的服务还需满足对应前台条件，普通权限查询返回已授予也不能证明后台创建服务合法。[Android 官方限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

WorkManager 周期任务执行时刻受约束和系统优化影响，周期最短为 15 分钟，条件不满足时可能延迟或跳过；不能拿它承诺每天精确某分钟执行。[官方 WorkRequest 说明](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

候选方案需要明确以下产品结果：

| 条件 | 必须定义的行为 |
| --- | --- |
| 到期但网络/电量/平台条件不满足 | 显示延迟原因和下一次检查条件，不重复创建模型 Turn |
| 错过多个时段 | 首版候选为合并成一次待处理记录或跳过并记录；不得默认追赶式连续执行 |
| 时区、夏令时、手动改时钟 | 以固定时区或跟随设备的明确规则生成 occurrence 身份；避免重复/漏跑 |
| FGS 启动被拒、权限撤销 | 持久记录拒绝原因，回到用户可操作入口，不循环重启服务 |
| 进程死亡、重启、强制停止 | 记录中断/错过；重新打开先对账，不凭持久 Schedule 自动激活 Goal |
| 用户禁用/删除 | 撤销未来 occurrence；当前轮如何停止单独明确，已发生副作用保留 |
| 到期需要 L2/L3 操作 | 等待现有精确审批；调度授权只表达何时尝试任务，不批准未来具体动作 |

平台选型、FGS type/时限、通知权限、Doze、锁屏、OEM、最低支持 API 与 target SDK 的验证矩阵是独立 Spike 交付物。沿用 [ADR-0007](../adr/0007-companion-runtime-lifecycle.md)：没有合法后台路径时保持前台有界执行，不能以 `dataSync` 包装任意长计算。本文不承诺后台准时性或 24/7 存活。

### 6.6 子 Agent、Workflow 与跨设备

只读 child 的既有候选边界来自 ADR-0009：深度 1、并发 2、每父 Turn 最多 4 个、父预算、无批准/Secret/可写能力继承。生产启用仍缺收益、资源、真实持久化和设备门禁；不把 Background Tool 或 A2A Client 改名后当 child 实现。

A2A 继续是用户配置的外部服务，经普通 ToolCall 发起与原 taskId 对账；远端输出不可信，不能反向调用本地工具或继承本地 scope。依据 [ADR-0016](../adr/0016-a2a-client-interoperability.md)，M7 已完成的 Client 契约不需要重启历史 Spike。

Tasker/Auto.js、Shizuku/ADB、学习型移动 Skill、任意 Workflow、外部 Channel、A2A Server、Web Access/远程控制、桌面配对和 Remote Worker 均保持独立研究或当前范围外。不会因排在“P2/P3”就自动获得实现授权，也不新增占位模块。

## 7. 候选实施顺序与验收

### 终端能力在 2.0 中的位置

终端是已有本机执行能力的产品化延伸，不替代上下文、运行准入、工具授权和结果恢复主干。HXA-192/193 的相关代码基线先收口；不能为“完成 2.0”强制加入 PTY、多会话或持续后台。任务列表可以聚合 Job 和手动 Session 的展示，但二者必须保留各自身份与状态所有者。

交付拆为三条能力线，具体允许模块和命令由 [终端开发计划](../development/terminal-and-background-execution-plan.md) 维护：

- 结果与观察：HXA-194 复用最终结果；HXA-195 新增有界日志、游标、背压和尾部结算。日志预览不自动成为验证产物。
- 有期限后台 Job：HXA-196 定义 owner 移交、租期、预算、停止和平台拒绝；accepted 只表示启动受理，不代表命令或 Goal 完成。旧同步 Job 的 owner/cancel 与上限不静默改变。
- 手动交互：HXA-197/198 的 PTY、多会话、单写连接和重连属于用户主动操作；不开放模型向 PTY 输入，不因人工会话存在扩大工具审批。

架构上前台单 PTY 不依赖 detached Job 实现成功；日志、进程组取消和会话身份可复用。执行包与路线图已解除 197 对 196 的硬依赖；前台切片仍需 ADR-0051 对应条件齐备，每次一个 checkpoint，不跳过其独立门禁。HXA-199 负责所选范围的综合验收，不将新增终端能力反向设为既有 Harness 交付条件。

ADR-0051 已接受初版手动域与 Agent 本地代码/文件变更互斥、最多两个手动会话及异步 Job 默认5/最大30分钟限额。等待须可见可取消，用户关闭会话且进程组回收后释放占用；不能仅凭 prompt 判断无后台子进程。后续更细粒度并发须用体验/设备证据修改契约，不靠提高线程数实现。限额不是 Android 保活保证。

日志和职责已由 [ADR-0050](../adr/0050-terminal-sessions-and-detached-jobs.md) 接受；后台及手动终端启用留在 accepted [ADR-0051](../adr/0051-terminal-runtime-enablement.md)。该终端增量不改变 Goal 完成报告、审批证明和未知副作用不重放；Goal 连续运行另由 ADR-0053/HXA-208 接受。

以下 S0～S4 是讨论批次，**不是 HXA 编号或新授权**。先完成当前已授权工作，再据实际问题选择下一项。每次只推进有明确产出和验收的一小项，不绑定完成整套图中方框。

| 批次 | 具体范围与主要责任 | 依赖/触发条件 | 可评审产物与验收 |
| --- | --- | --- | --- |
| S0 事实与边界 | 两份研究文档 | 本轮授权 | 固定基线、纠错表、现状/目标/研究图、来源与检查；不改运行语义 |
| S1a 任务与恢复投影 | app UI/查询层，必要时已有 repository 投影 | 复用当前 Turn/Goal/后台结果 | 精确 ID 取消、跨会话不串状态、未知副作用入口、打开列表不启动任务 |
| S1b 产物与能力入口 | app UI、文件/浏览器既有入口 | 先确认 HXA-190/191 剩余缺口 | 产物可打开/失效态、审批完整展开、安装拒绝/返回恢复、无模型仍能手动操作 |
| S2a 请求主干与 Prompt | app chat 组装层，必要的 core 纯类型 | 具体重复/冲突证据及请求 fixture | 工具配对/图片重试/压缩恢复/窗口预算/模式隔离不回退；内置 sections 可追溯 |
| S2b 执行入口 facade | app admission、TurnCoordinator 调用者 | 至少有需要统一的现有入口，事务契约清楚 | submit 去重、忙碌输入保留、精确取消、持久失败停泊；同一运行状态所有者 |
| S3a Plan/Todo/变更预览 | UI、范围受限的元数据与文件投影 | S1 用户价值证据；新契约决策 | 版本失效、重启、取消、只读模式边界、计划批准不代替 Tool 审批 |
| S3b 文档附件 | 附件解析与存储 | 格式范围、依赖/许可证和预算方案 | 文本/页段定位、恶意/加密/损坏输入、取消/大文件、无静默误读 |
| S3c 持久 Git | 独立 Git HXA，沿 ADR-0008 | 仓库事务/设备证据与明确授权 | 完整性、隐式执行防护、并发/中断、空间与性能；remote Git 单列 |
| S4 自动化扩展 | 每项独立 ADR/Spike/HXA | GoalDriver、Schedule、Hook、Code Mode 或 child 的明确收益 | 用户激活、停止、预算、权限、跨 UID、平台失败及恢复门禁；不合并成一个 P0 |

### 7.1 原行动项去向

| 原编号/主题 | 新归属 | 处理 |
| --- | --- | --- |
| HX2-01 AgentRuntime | S2b | 定义所有者与入口迁移；不吸收手动页面 |
| HX2-02 AgentLoop | S2b 的可选整理 | 已有单循环，更名不独立立项 |
| HX2-03 Context | S2a | 以生产路径吸收旧抽象规则 |
| HX2-04 Prompt Registry | S2a | 先内置 sections，外部插件后置 |
| HX2-05 Plan | S3a | 先用户审阅，再元数据契约 |
| HX2-06 Act Completion | S1a/S3a | 显示层区分，本轮不强制新工具 |
| HX2-07 Task Ledger | S3a | 有需求才增加，避免复制 Goal 状态 |
| HX2-08 GoalDriver | HXA-208 | ADR-0053 前后台连续和完整模型工具面已完成 HXA-208 专项验收 |
| PX-01 Tasks / PX-02 Artifacts | S1a/S1b | 增强已有任务和引用投影 |
| PX-03 Git Diff | S3a/S3c | 普通文件预览与持久 Git 拆分 |
| PX-04 Capability Center | S1b | 对齐 HXA-190/191，不重复安装/折叠实现 |
| PX-05 文档 / PX-06 Share | S3b/独立入口增量 | 区分输入流转与新增格式理解 |
| Hooks、Schedule、Code Mode、child | S4 | 单项验证，非近期架构前置依赖 |

### 7.2 每个实现切片应具备的评审材料

每项写明：用户触发与前后行为、当前源码/dirty 基线、允许模块、唯一状态所有者、公开 API/持久格式变化、ADR 关系、失败/取消/恢复行为、迁移和回退办法、精确验证命令与未执行项。

不改变存储格式的重构，优先保留原数据与回退接线；需要新格式时定义旧版本读取/迁移、活动任务边界和升级失败处理。不能把工作区中其他人的修改纳入迁移基线而不说明，也不能通过双执行新旧路径验证输出。

未来代码修改按对应 HXA 运行主机与独占设备门禁。本轮仅修订 Markdown，检查链接、图语法、语义一致性和 diff；不以文档通过替代真实任务、设备或发布验收。

## 8. 竞品研究与产品验证

### 8.1 保留研究问题，撤回排名

原稿列出的 Operit、Operit2、PalmClaw、RikkaHub、AndCode、ClawMobile、DSHA，以及桌面/通用 Harness 仍可作为研究对象。但原稿没有逐项固定 release/commit/APK/设备，因此原数值评分、圆点成熟度和“明显领先/更严谨”等横向结论不保留为事实。

下表只记录**原稿引出的待验证问题**，不声明产品当前一定具备对应能力。仓库链接是研究入口，不是实测证据。

| 对象 | 下一次研究的问题 | 影响 Helix 的证据要求 |
| --- | --- | --- |
| [Operit](https://github.com/AAswordman/Operit) | 多能力入口、Plan 与复杂配置怎样影响首次成功？ | 固定版本，记录同任务步骤、授权、失败与人工接管 |
| [Operit2](https://github.com/AAswordman/Operit2) | 跨设备任务的执行位置和接续成本是什么？ | 区分手机 UI、手机工具、远端推理与远端 Worker；不据此扩大 Helix 范围 |
| [PalmClaw](https://github.com/ModalityDance/PalmClaw) | 提醒/周期任务/常驻体验在真实 Android 限制下如何表现？ | Doze、强制停止、错过执行、重复副作用和耗电记录 |
| [RikkaHub](https://github.com/rikkahub/rikkahub) | 模型配置、附件和历史浏览怎样降低首次使用成本？ | 固定模型/账号条件，区分配置成功与实际能力 |
| [AndCode](https://github.com/yuga-hashimoto/and-code) | Diff、测试结果与审批怎样支持手机上的开发任务？ | 确认运行域、项目来源、修改结果及用户操作成本 |
| [ClawMobile](https://github.com/ClawMobile/ClawMobile) | 一次 UI 操作如何转为可复用步骤？ | 页面变化、坐标/语义锚点失效、重新授权和结果检查 |
| [DSHA](https://github.com/DSH-APP/DSHA) | Android 上安装 Harness/运行环境的成本与价值如何？ | 安装体积、冷启动、任务成功、设备资源；不能从项目存在推断用户接受度 |
| DeepSeek Harness | sections、Driver、Goal 与 Tool 生命周期怎样解耦？ | 本轮仅确认本文引用的两个官方契约；其他包另查版本和源码 |
| Codex、Claude Code、pi、WorkBuddy | 项目上下文、变更审阅、恢复及扩展哪些适合手机？ | 沿[已有证据台账](../product/competitive-evidence.md)逐项核验；桌面权限/后台假设不直接移植 |

每条比较记录最少包含 `产品 + version/commit + 获取日期 + 证据类型 + source path/URL + device/OS + model/provider + task/input + outcome + 限制`。缺字段时标记待核验；README 声明、源码路径、fixture、真机结果、用户研究分别记载，不能互相代替。

### 8.2 可重复的真实任务样本

| 样本 | 输入与产物 | 成功判据与边界 |
| --- | --- | --- |
| 文件整理 | 用户选定且已进入 Agent scope 的合成文件集合；分类结果与变更摘要 | 路径正确、无遗漏/意外覆盖、取消可解释；不能把手动 Downloads grant 当 Agent 授权 |
| 网页研究 | 固定公开网页/快照；Markdown 报告与来源 | 可打开、引用支持结论、区分事实与推断；网络与模型变化记录在样本中 |
| 日志/项目分析 | 固定小型 Workspace 与可用 Runtime；诊断/变更/检查结果 | 可复现问题及修复；没有执行的测试明确标记；不要求尚未实现的 Git |
| Android 操作 | 合成日历/测试应用数据与用户授权 | 优先可用原生 API/Intent；审批、拒绝、撤权与实际目标状态可验证 |
| 中断恢复 | 已知副作用边界上的进程/网络/绑定中断 | 事实可恢复、未知项不重放、用户继续后正确；不以普通重开 App 代替 kill-point 验收 |

上述是验收样本设计，不是本轮已完成的 Demo。外发、删除和设备扰动在独立测试数据及授权范围内执行；未实现能力不放入对外宣传视频。

### 8.3 指标口径

| 指标 | 统计口径 |
| --- | --- |
| 首次任务成功率 | 已开始且满足前置条件的首次真实任务中，产物/目标状态验收成功的比例；另报配置失败与放弃 |
| 任务完成率 | 模型报告完成、独立结果检查成功分别统计，不能只数 Turn COMPLETED |
| 首次价值时间 | 从开始配置到首个可用结果，记录用户操作时间和模型/运行等待 |
| 人工介入与审批负担 | 每任务确认/接管次数、耗时、重复请求、拒绝原因；不以降低必要审批换取高分 |
| 恢复率 | 注入中断的任务中，完成对账且正确继续/明确停泊的比例；分别报告两类结果 |
| 重复副作用 | 按文件/外部动作身份核对恢复与 retry 的重复发生；每个事件单独调查 |
| 产物成功率 | 产物存在、可打开、内容满足样本要求三项分别记录 |
| 资源与成本 | 同一设备/模型/输入的 token、墙钟、网络、内存、热量/电量；未知值不记为零 |
| 复用 | 7/30 日真实任务再次使用；模板复用与普通聊天活跃分别统计 |

先采集固定样本基线，再为每个候选切片设定改善目标及不回退门槛；当前没有可引用的用户成功率，不编造数值目标达成。评测日志使用合成或经授权数据，正文与凭据不进入默认遥测。

## 9. 维护与本轮检查边界

本版维护原则：优先级只在第 7 节维护，源码事实只在第 2 节维护，状态/调用关系交由图集。新设计进入规范文档前，先对应 HXA/ADR；不把本研究图直接复制成“已实现架构”。

2026-09-13 修订曾保留根目录原路径，导致分类门禁失败；2026-09-14 已按用户要求归入研究分类并修复引用。下表保留原轮次检查结果，归档后的检查见[专项交接](../development/harness-2.0-next-work.md)，不覆盖历史失败记录。

本轮实际检查结果：

| 检查 | 结果 |
| --- | --- |
| `git diff --check` | 通过；两份修订无空白错误 |
| `./scripts/verify-adr.sh` | 通过，47 条 ADR 及当前状态声明检查通过；本轮未修改 ADR |
| `./scripts/check-docs.sh` | 未通过；唯一报告为这两份文件位于 `docs/` 根目录的既有分类问题，未报告断链 |
| Mermaid CLI `11.17.0` 渲染 | 14/14 图成功生成 SVG；抽查总览、工具、Goal、恢复、Driver 五张图的可视输出 |
| 关键源码 hash 复核 | 8/8 与取证表一致；本轮最终工作区差异仅两份文档 |

图渲染使用临时 npm 工具和本机独立 headless 浏览器进程，不修改项目依赖；结果位于前述忽略目录。仅文档机械校验和图渲染计为本轮证据。产品测试、竞品设备横评、完整 HXA 验收和发布状态均未由本次修订重新验证。
