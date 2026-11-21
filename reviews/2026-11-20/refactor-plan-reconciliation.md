# 历史重构计划复核：该做的、已做的，以及不应再做的

2026-11-20，`dev`，提交基线 `3b217383ba13` 加当时工作树。本文是审查快照，不是新的实施计划，不更改 ADR、HXA 状态或产品授权。判定单位是**可观察的职责或契约**，不是文档、目录、类名或 HXA 数量。把多份方案指向的同一项工作只算一次。

**同日实施回注。** 阶段 D 已标注同日实施结果。候选索引也已更正 SAF、表单与会话找回状态。实现和验证见[蓝图 24.1](../../docs/architecture/harness-refactor-plan.md#241-结论基线与范围)。

## 阅读结论

1. 早期“让 Core 拥有一次 Turn、让 UI 不再驱动执行、让扩展复用同一授权管线”的方向正确，而且现在已有生产路径。直接从老计划开工会做出第二个 Engine、第二套工具注册或一层空门面。
2. 目前路线图单列的架构项是 **HXA-288**，但其中有三种不同状态：已经交付、仍可局部改进、以及尚无实际消费者的增强。不能把整卡算作未做，也不能以若干子项通过宣布整卡完成。
3. 仍有具体的接线复杂性值得关注。例如审批卡接收端在容器启动时后置安装。这是一个能指到生产调用顺序的问题，不是引入 DI 框架、重写全部审批或按行数拆容器的理由。

## 范围和证据等级

对照当前仓库、可达 Git 历史中的改名/删除路径、正式蓝图、专题方案、历史审查及其撤回意见、现行 HXA、生产实现与已有验收。覆盖项目留存的计划族；不把未进入 Git 的私有草案说成已经核对，也不声称逐行重审所有 Kotlin 或重跑历史测试。11 月 20 日恢复、更新取消和插件暴露优化属于当时**未提交**的改动；不是某个历史版本已经具备它们的证明。

| 历史入口 | 如何使用，而不是再开一遍 |
| --- | --- |
| [9 月结构审查](../2026-09-24/2026-09-24-structure-review.md)、[独立二次裁决](../2026-09-24/independent-review/02-architecture.md)、[执行引擎比较](../../docs/evidence/research-history/execution-engine-comparison.md) | 区分真实单点、可测试接缝与已撤回建议。不能引用早版第 1.2 节而忽略后来的反向依赖复核。 |
| 9 月 28 日 `agent-capability-refactor-plan`、`plugin-platform-refactor`（可从 `7480141ba^:docs/architecture/` 查旧稿）、[整合后插件主方案](../../docs/architecture/plugin-platform-plan.md) | 移除旧方案文件不是删除产品能力。按 Turn、Job、插件安装/贡献归属分别核对。 |
| [10 月 10 日架构与测试审查](../../docs/research/topics/architecture-and-test-maintenance-audit-2026-10-10.md)、[复杂类保留理由](../../docs/evidence/development/maintenance-boundaries-2026-10-10.md)、[插件优化](../../docs/research/topics/helix-plugin-architecture-optimization-2026-10-11.md) | 技术债、测试入口治理、内容/工具贡献、浏览器及共享资源释放分别查证。它们不是一批待建的 Gradle 模块。 |
| [10 月架构演进](../../docs/evidence/research-history/architecture-evolution-2026-10-14.md)、[发行和重构顺序](../../docs/evidence/research-history/v0.0.6-release-and-refactor-order-review-2026-10-15.md)、[10 月 28 日复审](../2026-10-28/2026-10-28-comprehensive-audit.md) | 顺序是当时的决策；一次故障修复或一次发行不是架构替换。性能建议需要新的工作负载证据。 |
| [Harness 总蓝图](../../docs/architecture/harness-refactor-plan.md)、[功能/重构策略](../../docs/development/feature-refactor-strategy.md)、[当前路线图](../../docs/development/roadmap.md)、[候选裁决](../../docs/development/candidate-decisions.md) | 同一文档内晚期交付记录优先于早期将来时；交付记录还要与现行源码对齐。候选不自动升级为应实施。 |

## 已经接入生产的重构

| 历史计划或任务 | 今天由谁负责 | 裁决与剩余边界 |
| --- | --- | --- |
| 220/221/223 → R2-A（234）→ 287-A/B/C，收敛 Turn 生命周期 | [Core TurnEngine](../../core/agent/src/main/kotlin/com/helix/core/agent/runtime/TurnEngine.kt) 接纳 Turn，[Room 适配器](../../app/src/main/kotlin/com/helix/app/infrastructure/agent/RoomTurnLifecycleStore.kt)提交持久事实，[会话装配根](../../app/src/main/kotlin/com/helix/app/composition/ConversationComposition.kt)接入生产。 | **已实施**。恢复、取消与 UNKNOWN 的组合覆盖仍有边界。恢复界面不能重放工具。 |
| R1（231），原子绑定及准入 | [ToolBindingStore](../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolBindingStore.kt) 发布描述、执行器与绑定身份，Dispatcher 在执行准入时复核。 | **已实施**。11 月 20 日工作树又优化了按插件归属读取快照的成本。展示快照不能替执行授权。 |
| R3/R5（235），插件归属、选择与呈现 | [PluginRegistry](../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/PluginRegistry.kt)区分安装、当前会话选择、发布和就绪。 | **已实施主要生命周期**。共享资源的释放及撤权竞态仍按对应组合验证。 |
| J1/J2（236/248/249），观察和转后台 | 观察、停止等待和收取均关联原 Job。等待者、执行器与持久收据各有独立生命周期。 | **已实施**。释放前台等待不释放仍在运行的物理容量；进程退出、输出结束、结果提交分别取证。[现行执行边界](../../docs/development/tasks/HXA-288.md) |
| R2-A、286、293-A、294-A/B/C，工具指导、Skill 与上下文 | [请求组装](../../app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt)读取可用事实，[ContextCompiler](../../core/agent/src/main/kotlin/com/helix/core/agent/ContextCompiler.kt)生成模型请求内容。 | **主路径已实施**。目录发现与 Skill 激活有独立语义。293-B 的 Memory 对照经更正后两组各 3/4，样本不支持更换默认策略。 |
| 289–292，文件搜索、网页正文、MCP Resources、Browser 表单 | 各能力已有生产入口，沿既有工具准入和结果回填运行。 | 属于**功能交付与质量验证**。例如二进制引用进入下一次请求已有验证，模型能否正确理解仍需单独判定。[能力与证据索引](../../docs/development/roadmap.md) |

旧计划沿革也影响判读。`87f08c17d` 清理产品完成与后台终端计划，把工作区体验合入产品文档；`025af1ad8` 删除的 `next-work-plan.md` 同时含已交付与待办。`7480141ba` 将能力、插件与剩余工作三份方案改名整合；`47d74c276`、`bbfbe7a5a` 留下 10 月演进与发行顺序复核。旧文件消失只说明入口变化。9 月 R0 是基线、R4/R5 涉及跨域清理和体验，单项接线只证明对应切片。

## 11 月总体蓝图逐阶段复核

| 第 24 节阶段 | 判断 | 可据此采取的行动 |
| --- | --- | --- |
| A 基线 | 已纠正早期对 Core、Room 和模块的描述。 | 从蓝图 24.1 与当前构建图出发，避免重复提取。 |
| B 装配 | 主要会话切片已集中到 ConversationComposition。 | 保留唯一装配根，单独检查审批接收端的安装顺序。 |
| C 用例 | 提交、Goal 与工具结算已有分片；Goal 时间预算仍直接协调持久租约。 | 保持原子结算与租约恢复。遇到重复结算逻辑或无法隔离的时钟测试时再抽窄接口。[GoalTimeBudget](../../app/src/main/kotlin/com/helix/app/agent/GoalTimeBudget.kt) |
| D 上下文 | 复核时，提示词渲染只需要专家显示名、指令、建议模式与关联数量，却依赖存储类型 `ExpertProfile`。 | 同日已将字段投影移到事实读取端。预检读取当前会话配置，正式请求和工具回填沿用原 Turn 的快照。实现及等价性、快照隔离验证见[蓝图 24.1](../../docs/architecture/harness-refactor-plan.md#241-结论基线与范围)。 |
| E 编译边界 | `:feature:conversation`、`:ui:shared`、`:infrastructure:storage` 已成实际 Gradle 模块。 | 维护禁依赖方向。Room 的包名、schema 与运行身份可保持稳定。[模块声明](../../settings.gradle.kts) |
| F 综合验收 | 已有多项代表用例和有界 Debug/API36 记录。本次未找到覆盖蓝图全部代表路径、禁止依赖及资源收益的同一候选总验收。 | 将尚需证明的命题与候选、检查、结果逐一对应。引用旧记录时保留候选和范围，资源收益需同工作负载前后测量。[有界收尾](../../docs/evidence/development/architecture-closeout.md) · [总体蓝图](../../docs/architecture/harness-refactor-plan.md) |

两个容易影响排期的时间差值得单列。10 月蓝图还将 MCP Resources 描述为待实现，现有 [生产读取](../../extensions/mcp/src/main/kotlin/com/helix/extensions/mcp/SdkMcpResources.kt) 与 [二进制引用回填记录](../../docs/development/tasks/HXA-291.md) 已更新这一判断。模块迁移也已有实证：历史迁移候选记录 JVM 7,440 项通过、10 项跳过，API36 共 151 次方法执行通过；随后 SAF 修复候选的 storage 33 项另计。相关数字取自[蓝图交付记录](../../docs/architecture/harness-refactor-plan.md)，本次未重新运行这些制品。策略收益另见 [293-B 更正记录](../../docs/completion-records/HXA-293.md)。

## 剩余项与处理顺序

1. **HXA-288 按三个问题单独裁决。** 原 Job 身份、物理容量、观察/取消、类型化结果、日志/ACK、默认原位运行均已交付。① 当代表性扩展需要可信执行角色或重复解析原调用造成实际成本时，收窄 LaunchWait/观察接口，验证 no-start、旧 generation、取消、UNKNOWN；② 截图用途与请求路径投影只有在保持原身份、披露与授权后才值得统一；③ 显式 snapshot 的跨来源层级 manifest 确实未交付，等出现依赖相对路径而失败的具体工程，再定义重名、撤权和部分发布行为。[原任务逐项裁决](../../docs/development/tasks/HXA-288.md)
2. **审批接线和 Connector 轮询列为观察项。** 审批接收端有启动顺序依赖，详见下表。Connector 已把单次协议请求交给 [OAuth 协调器](../../app/src/main/kotlin/com/helix/app/mcp/oauth/McpOAuthCoordinator.kt)，设备码轮询循环仍由 [Compose 页面](../../app/src/main/kotlin/com/helix/app/connector/ConnectorSection.kt)的 `rememberCoroutineScope` 启动。只有产品要求离页继续、出现重复轮询，或轮询策略需要独立测试时，才下沉循环；保留超时、取消与尝试身份。本次未发现要求离页继续的契约。
3. **按原任务补证。** 232/234–240/248 侧重恢复、交互或运行组合，271/283/289/295/309/310 各留有明确范围；290–292 的开放项包括付费服务、模型实际理解和动态页面。120–123 属发行链，125/126/190/307 依赖对应服务、账号、签名或质量条件。按卡中仍开放的子项推进，11 月 18 日已由所有者结束的设备/OEM/长稳范围保留其当时状态。[路线图](../../docs/development/roadmap.md)

## 容易误标的架构建议：原问题和原处方分开判断

| 历史建议 | 今天的裁决 | 依据和改变判断的条件 |
| --- | --- | --- |
| “三条反向依赖”，把相关类型一律提升到 `core:model` | **误判，撤回** | [实际依赖](../../build.gradle.kts)：storage 实现用 policy 值；QuickJS 消费工具契约；Browser feature 实现 browser tool 的 port。三者都不是 Core 反向依赖 App/UI。提升类型反而迫使核心知道后端细节。 |
| 把巨型 `ChatService` 改成空壳，按功能复制多个协调器 | **问题成立，原处方过宽** | [当前装配](../../app/src/main/kotlin/com/helix/app/composition/ConversationComposition.kt)、[会话应用服务](../../feature/conversation/src/main/kotlin/com/helix/feature/conversation/application/ChatService.kt) 已分离运行与展示；提交、队列、停止不能另建并行入口。仅迁移拥有独立事实和生命周期的行为。 |
| 用 Service Locator/Hilt/Koin 消除大容器 | **不做；只保留具体接缝** | [容器](../../app/src/main/kotlin/com/helix/app/DefaultAppContainer.kt) 持有唯一装配图；[审批卡接收端](../../app/src/main/kotlin/com/helix/app/tool/ApprovalCardSinkHolder.kt) 确有装配时间依赖，未安装不等于默认批准。若出现启动前投递、订阅生命周期冲突或测试不可隔离，再改这个接缝，验收冷启动、重复 start、早到审批不丢失/不误批。不是已复现的审批故障。 |
| 统一所有 Runtime/Provider/文件后端/结果为一个接口或进程 | **错误抽象** | [运行域 ADR](../../docs/adr/runtime/001-execution-domains.md)：QuickJS isolated UID、同 UID 私有进程和远端 HTTP 的身份/退出语义不同。[手动文件操作](../../app/src/main/kotlin/com/helix/app/files/ManualFileOperations.kt) 可共享已证明的操作，但 URI、路径、授权根与运行副本不互换。可抽共同契约，不伪造共同保证。 |
| 把 Dispatcher 按阶段拆成独立权威、把 PRoot 启动/退出割开 | **保留单一权威；允许内部拆分** | [Dispatcher](../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDispatcher.kt)、[PRoot runner](../../runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt)。权限重验、审计与原调用身份需要连续；等待者取消不代表进程退出。仅当新接缝不制造双重结算或提前释放容量时再抽。 |
| 根 Gradle 必须迁移 convention plugins；每个功能、每个接口拆一个模块 | **无充分理由** | [模块声明](../../settings.gradle.kts)和[维护裁决](../../docs/evidence/development/maintenance-boundaries-2026-10-10.md)。已经形成实际隔离的 conversation/shared/storage 是合理迁移；其他目录需用禁止依赖、独立测试、构建瓶颈或多个实际消费者来证明成本值得。 |
| 把所有测试目录合并，或以无生产调用为由直接删除参考测试 | **误标** | 当前 `testing/` 没有 Git 跟踪文件，不是待删的 Gradle 模块；`:tests:release-smoke`、三个 `:spikes:*` 有独立 opt-in 开关。入口整合与删除覆盖是两件事；替换参考模型前必须映射生产不变量。 |
| 每个新模型端点都需新 Provider 架构；MCP 连接数增长就应改传输层 | **按触发条件，不按厂商数量做** | [ProviderFactory](../../app/src/main/kotlin/com/helix/app/provider/ProviderFactory.kt)按协议装配；相同协议的新端点与新增线协议不是一回事。仅在新协议、新 transport 或多处冲突分支有实际消费者时抽对应工厂，不开放动态宿主代码。 |

## 会改变排期的旧结论

| 早期资料的读法 | 后续证据及其适用范围 |
| --- | --- |
| SAF 自主定位仍被登录阻断、表单动作仍待开发 | Luna 已有一次自主定位成功及独立搜索样本；表单替换/追加、选择、按键、提交、上传已有实现，动态索引偏移已有 API36 定向回归。更多网站及模型选动作的可靠性仍开放。[289](../../docs/development/tasks/HXA-289.md) · [292](../../docs/development/tasks/HXA-292.md) |
| 真 Room 只编译过、真实 Binder 阻塞尚未测 | 扩展后的 Room 测试类在 API34 实跑 10 项；独立 Binder 进程阻塞期间的取消、超时、撤权及物理占槽已有验收。低内存和其他组合各自保留。[234](../../docs/development/tasks/HXA-234.md) · [236](../../docs/development/tasks/HXA-236.md) |
| 所有插件研究方向都要先扩底座 | Shizuku 基础操作、PDF/DOCX 阅读、Chat Reply 的纯 Skill 插件、绑定现有会话的定时 PLAN 已有生产入口。Tasker/Auto.js 兼容、无线 ADB 配对、生产子 Agent、入站 MCP/A2A、常驻 stdio、可执行 hooks 等各需产品消费者及独立契约。可识别包组件只证明解析成功。[候选裁决](../../docs/development/candidate-decisions.md) · [产品需求](../../docs/product/requirements.md) |
| 统一回滚、记忆、预览、语音和同步都可列作底座迁移 | Project Memory 已有可信会话作用域；知识库混合检索、离线块预览、连续分段转写和 SAF 双向同步各有基础。跨用户并行修改的 Agent checkpoint、全双工语音和模型检索质量需单独定义行为或测量。备份和同步不承诺撤销外部副作用。[候选边界](../../docs/development/candidate-decisions.md) |

## 竞品一手资料：采用语义，不复制部署拓扑

| 参考 | 有价值的机制 | 对 Android 单设备产品的裁决 |
| --- | --- | --- |
| [Codex App Server](https://developers.openai.com/codex/app-server/)、[Codex 插件](https://developers.openai.com/codex/plugins/) | thread/turn/item 分层，resume 与 fork 区分；请求和审批答复有对应身份。 | 保留 Turn/ToolCall/分支稳定身份及请求级审批；**不推导必须引入独立 server/RPC**。历史复制既不授予新能力，也不证明外部执行已回滚。 |
| [Claude Code 工作原理](https://code.claude.com/docs/en/how-claude-code-works)、[插件](https://code.claude.com/docs/en/plugins) | 模型提出动作，宿主提供工具并取得结果；skills、hooks、MCP 可打包。会话历史与文件检查点用途不同。 | 采用插件包与贡献项区分，不继承桌面插件作为本机用户执行代码的信任模型；文件检查点不是 Android 外部副作用事务。 |
| [Gemini CLI 扩展](https://geminicli.com/docs/extensions/)、[Policy Engine](https://geminicli.com/docs/reference/policy-engine/)、[会话管理](https://geminicli.com/docs/cli/session-management/) | 扩展声明、策略裁决和会话保留分开；无法询问时不把 `ask_user` 退化为允许。 | 保留发现/选择/执行的不同职责；不让扩展元数据给自己授权，不用 CLI 会话文件取代 Room 的持久事实。 |
| [Android 官方模块化模式](https://developer.android.com/topic/modularization/patterns) | feature 依赖实际契约；数据源隐藏于数据层；App 负责装配。 | 模块、包、进程、权限分别验证。即便不拆 Gradle 模块，一样可以移除 UI 对存储或网络的依赖；拆了模块也不能宣称获得凭据隔离。 |

竞品资料查阅于 2026-11-20，反映公开文档行为，不是对所有版本的实测。竞品差异只能支持设计判断，不授予执行权限，也不能代替 Helix 的运行证据。
