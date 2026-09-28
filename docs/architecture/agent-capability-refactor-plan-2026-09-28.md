# Helix 能力与上下文重构方案

日期：2026-09-28。状态：所有者已授权先做有界问题收口与基线，再执行 R1，范围归 [HXA-231](../development/tasks/HXA-231.md)；其余阶段仍为后续设计，依据内测反馈决定。本文件不作为实时进度表。

## 1. 目标与取舍

以当前和未来最优的职责边界为目标，不为内部旧接口保留长期兼容层。目标是一个执行内核、一份工具绑定事实、一个上下文编译入口、一套插件安装与会话选择事实。通过分阶段替换抵达目标，不建设新旧双轨产品。

衡量收益：工具契约与实现不会错配；停用和授权撤销及时生效；上下文取舍可解释；插件失败可恢复；模型完成任务时更少无效调用。文件数量减少或类名统一不是验收标准。

丢弃内部历史包袱不等于删除用户文件、放松权限或推翻已验证的执行事实。继续开发期 Room v1 baseline 约定；不借本次设计引入历史数据库迁移链，亦不执行数据清理。模型 API、MCP 等外部协议兼容仍按当前支持范围保留。

## 2. 当前起点

本次核对基于本地主目录源码，而非把研究中的问题列表直接当成待开发清单。

| 已有能力 | 本轮方向 |
| --- | --- |
| TurnEngine、AgentLoop、SessionWorkScheduler 与 durable recovery | 保留唯一 owner；不再重写执行状态机 |
| ToolRegistry / ToolImplementationRegistry 分别加锁 | 合成一份不可拆分绑定与原子发布入口 |
| App 级 PluginRegistry、PluginOrigin、Mobile Use MVP | 在其基础上接入统一绑定与安装生命周期 |
| ModelToolExposureOrder、核心工具优先与发现机制 | 保留，纳入统一请求快照；不重做工具数量项目 |
| ChatRequestAssembler、PromptRegistry、压缩、容量预检、RequestContextManifest | 收敛为一个编译流程，复用已有算法与持久事实 |
| Connector 安装归属、准备/发布/清理、会话选择 | 提升为包管理基础，保留组件语义与账号边界 |
| ExecutionTargetDescriptor 与执行域 | 继续使用；当前不增加远程 Node 或重复 this-phone 抽象 |
| Linux detached Job、status/cancel/collect、Tasks 与 Runtime 持久 owner | 复用；增加指定 handle 的有界等待与上下文观察，不重建 Job 数据库 |

双注册表存在结构性错配窗口；目前不将其描述为已复现的线上故障。第一阶段用确定性并发测试证实旧结构问题，并证明新结构边界。

## 3. 目标架构与依赖

```text
Conversation / Tasks / Capability settings / Marketplace
                    │ application services
       ┌────────────┴─────────────────────┐
       │                                  │
 TurnEngine                         Plugin installation
       │                                  │
 ContextCompiler ← snapshots ← Capability catalog
       │                                  │
 ModelProvider                     ToolBindingRegistry
       │                                  │
 AgentLoop → ToolScheduler → ToolDispatcher┘
                              │ schema / policy / authorization
                              │ limits / execution / verification
                              ▼
                 existing native / MCP / A2A / runtime adapters
                              │
                     durable results and audit
```

图中 catalog 是只读投影职责，不要求新增服务或数据库。模型上下文可见性不是执行授权。插件管理不持有 AgentLoop；ContextCompiler 不执行工具；Dispatcher 不管理包安装。

先在现有 tools/framework、core/agent、app/chat、extensions/plugin 等模块内调整职责，依赖方向稳定后才决定是否抽模块。App 负责注入 Android/存储适配器；纯模型与选择逻辑不能反向依赖 App。

## 4. R1：统一工具绑定与执行准入

### 4.1 最小模型

以下为逻辑字段，不要求逐项新建类：

- ToolBinding：descriptor、executor、可信 owner、稳定实现 revision、进程内 generation。
- BindingRef：工具名/版本、contractHash、owner 身份及实现 revision；可记录到请求/审计，不序列化 executor。
- RegistrySnapshot：不可变绑定集合及 snapshot revision；请求中的 alias 解析也绑定该快照。

保留 descriptor 中已有 origin、operationClass、executionTarget 等字段，避免复制出两份可变真相。contractHash 表达契约；实现 revision 表达运行实现/包版本；generation 仅用于本进程失效校验，不作为跨重启身份。远程服务 revision 只能证明已发现的声明，不能证明远端代码未改变。

### 4.2 发布与调用

1. 各来源先构造完整候选绑定，检查 schema、owner、重复名及别名碰撞。
2. 同一 owner 的 replace/remove 通过一次原子发布生效；失败保持旧快照完整可用。owner 取可信注册身份，不靠名称前缀认领其他工具。
3. 编译请求时捕获快照；模型返回的短名解析到该请求实际暴露的 BindingRef，不能静默重定向到新实现。
4. Dispatcher 从同一绑定取得 descriptor 和 executor，继续现有安全管线；等待审批后的调用必须重新检查来源、会话选择及授权。
5. 执行前建立一次短临界区的准入判定，与停用/替换有明确先后关系；不持锁执行网络、工具或审批。停用先完成则未执行调用不能启动；准入先完成的调用照常结算，不能承诺撤回已发出的副作用。
6. 绑定失效且尚未执行时返回可恢复的结构化结果，下一轮可重新发现；不自动重放写操作，不要求用户重开整个会话。已经执行或状态未知时继续原有结算/review。

不要因无关 owner 的更新让全部调用失效，也不要因单纯进程 generation 变化要求用户重复审批。审批是否复用继续依据有效契约和授权规则；不可证明实现身份兼容时不得复用旧精确批准。

### 4.3 删除与验收

完成切换后删除独立可写 ToolImplementationRegistry 及双表注册调用；如分提交需要内部适配层，只允许单向委托新 registry，并在该阶段结束前删除。PluginToolBinding 收敛到公共绑定类型。

必须覆盖：替换和 resolve 的交错、完整批次发布、失败无半注册、跨 owner 碰撞、同名新实现、旧请求返回、等待审批后停用、执行中停用、别名一致、无关更新不误失效、进程重建。使用 barrier/latch 控制交错，不依赖随机 sleep 或测试运气。

## 5. R2：统一上下文编译

### 5.1 输入、流水线与输出

输入为已接受的用户请求、持久历史/检查点、当前有效会话权限与来源选择、Workspace 请求绑定、附件/引用、Memory/Skill 内容、工具快照与 Provider 容量。区分冻结配置与执行前仍需即时检查的撤销状态，不复制成另一份可写 Session。

流水线：读取事实快照 → 标注来源/信任 → 选择工具与上下文 → 容量规划 → 必要时请求既有压缩流程 → 协议物化与校验 → 发布实际请求清单。

ContextCompiler 的选择与容量核心尽量为纯函数。文件读取、摘要模型调用、数据库发布由现有适配器和 Engine 协调；不在纯编译函数中隐藏模型调用或预算扣费。

最小 ContextItem 包含 kind、sourceRef、trust/authority、scope、estimatedTokens、atomicGroup 和内容引用。复用现有 Prompt/Context 类型能表达的字段；只有出现具体选择规则时再加 freshness/priority 等字段。

输出为 ModelRequest、精确工具绑定映射、实际 included/omitted/compressed 清单、容量诊断。扩展现有 RequestContextManifest，不另建上下文数据库。

### 5.2 必须保持的语义

- 平台指令来自受信任代码；用户输入保留其角色；外部结果、Skill、Memory、附件与其摘要不因被拼入 prompt 而升级权限。
- 一组 tool calls 与对应 results 不可拆散；未结算结果不伪造。保留当前用户请求、最新完整步骤和必要恢复事实。
- 工具 schema 按已获准范围、核心优先和按需发现选入；发现结果不授予执行权限，所发现工具进入后续请求快照后才可按该绑定调用。
- 分类额度是可借用的软分配；Provider 窗口、用户已设额度、输出预留、协议和内存边界仍是硬约束。无法容纳必要输入时明确诊断，不静默删掉任务目标。
- 只压缩已结算内容，复用既有摘要预算、收益检查、失败回退和 checkpoint 原子发布。摘要没有收益就不循环摘要。
- 清单记录引用和决策理由，诊断默认不保存第二份正文/凭据；导出继续走现有脱敏边界。
- __helix_intent 继续只作 presentation；业务参数、审批 hash、provider history 不重放该元数据。

### 5.3 切换与验收

先以固定历史 fixture 对比现有请求与新编译输出，再切换唯一生产入口。对比发生在测试中，不在生产重复调用模型。移除旧的重复选择/容量路径，保留 Provider 必需的编码差异。

覆盖三 Provider 协议、工具成组完整性、无工具模式、超大结果分页、窄窗口、未知窗口、摘要失败/取消、权限撤销、恶意 Memory/Skill、附件与跨会话引用、恢复与 JSONL 脱敏。记录 schema token 占比、压缩次数、拒绝原因和无效调用，不能仅以 prompt 字数变短验收。

## 6. R3：插件安装与会话选择闭环

Plugin 是交付单位；Tool 是执行能力；Skill 是指导内容；MCP/A2A 是连接协议。统一包的管理，不统一这些组件的运行方式。

沿用 Connector 的稳定安装 ID、不可变 revision、精确组件归属和 Room 已提交事实。将通用包职责迁至 PluginInstallationService/现有等价服务，Connector 保留连接配置与认证语义。包解析器复用现有 archive hardening，不新建通用安装事务引擎。

安装流程：完整暂存验证 → 原子发布已提交安装 → 按该 revision 激活 registry 投影 → 幂等清理旧的无引用资产。Room 与 registry 不是分布式原子事务：调用必须验证当前 committed revision；投影尚未就绪时标明暂不可用并可重试。进程重启依据 durable 安装记录重建投影。

安装和会话选择同阶段交付。安装不自动授予工具权限，也不静默改变其他会话；fork 复制选择快照。关闭来源后 UI、Skill list/read、schema 与实际执行必须一致。共享 Skill 的独立安装引用不能被卸载另一个包误删。

更新保持稳定安装身份与会话选择；凭据仅在认证/端点绑定完全不变时保留。候选失败保留旧安装；未知远端副作用不以安装回滚为由重试。原生 runtime 只允许 APK 已知实现，不加入下载 DEX/JAR 执行。

验收包含取消、低空间、恶意 archive、提交前后进程死亡、并发更新、重复安装、共享组件卸载、账号绑定变化、session/fork/reopen、等待审批时停用和已发送调用结算。设备测试先备齐，执行需要当前任务单独授权。

## 7. R4 / R5：边界清理与产品呈现

R4 只处理 R1–R3 暴露的重复 owner、反向依赖和残留入口；所有 Turn 继续经 TurnEngine admission，Goal 复用同一循环，调度与权限不迁到插件层。若没有具体重复职责和测试证据，不为“整洁”重新拆 Core Engine。

R5 将 Marketplace 和能力设置改为面向包的管理，保留组件详情及独立工具禁用入口。明确展示已安装、当前会话已选择、需配置/不可用；授权和 UNKNOWN/review 保持视觉权威。无关插件失败不阻塞普通聊天，可恢复问题提供就地重试；安全上无法证明可执行时只阻止对应操作。

验收包括会话选择/配置/更新/卸载旅程、320/360/412dp 和大字体。不要先做 Marketplace 页面，再补生命周期与执行约束。

## 8. J1 / J2：异步观察、等待与安全后台化

本节吸收[异步 Job 调研](../research/async-jobs-wait-and-background-execution-competitive-study-2026-09-28.md)的 launch/join 分离建议。属于新增能力提案，不将研究视为实现授权。现有 Linux Job 是基础；不采用泛化 sleep、background(anyToolCall)、全局等待所有任务或 Job 完成自动唤醒模型。

### 8.1 两个生命周期与一个真实 owner

```text
launch ToolCall → durable submission → terminal result: accepted + handle
                                          │
                                   Runtime Job continues
                                          │
new status / await / collect ToolCall ← durable observation / result
```

launch 的 COMPLETED 只表示提交步骤完成；accepted 是结果内容，不新增 ToolCall 状态，也不表示 Job 成功。后续终态不能回写原 launch ToolResult。提交回执丢失时按预先持久化的执行身份对账，不重新启动命令；无法确定是否提交则保留未知事实。

AsyncHandle 只是现有持久绑定的通用投影，包含原 session/turn/call、executionTarget、providerRef 和 provider generation。generation 来自执行身份，不能用重启后的进程计数替代。真实阶段、退出证明、日志和租期继续由原 Runtime 保存；通用层不复制一张可写 Job 状态表。

Handle 不授予权限。模型提交的 handle 必须解析为可信存储绑定；拒绝跨会话、伪造、过期 generation 与目标替换。会话更换 Workspace 后仍按原 Job 绑定查询/收集，不将结果自动写入新目录。fork 不自动继承原会话 Job 控制权。

### 8.2 J1：先只接 Linux Job 的 observation / join

建议统一入口为 jobs.status、jobs.await、jobs.cancel；collect 继续复用现有 Linux 收集路径，因为它可能产生文件/产物效果，不能伪装成只读 status。原入口与新入口若需过渡，必须指向同一实现和权限，模型工具面只暴露一套等价操作，避免增加工具冗余。先证明单 provider 的薄接口，再考虑更多 provider。

jobs.await(handles, condition=ANY|ALL) 只等待本次明确列出的有限集合。平台控制单次等待时限和 handle 数量，优先事件订阅加 durable 复核，不消耗模型调用反复轮询。具体资源阈值随 HXA 测量确定，不能凭研究写死任意限制。

等待结果必须区分：

| 返回原因 | 语义 |
| --- | --- |
| CONDITION_MET | ANY 至少一个、ALL 全部具有已确认执行终态；逐项返回成功/失败/取消，不把终态等同成功 |
| WAIT_EXPIRED | 本次等待结束；Job 可以仍运行，不取消、不改写 Job 状态 |
| REVIEW_REQUIRED / SOURCE_UNAVAILABLE | 未知效果或来源不可达；返回原始事实和修复入口，不冒充成功或无限等候 |
| 调用取消 | 停止本次观察；不隐式向所观察 Job 发 cancel |

已终态的 handle 立即返回；重复 handle 去重。空集合、非法/越权 handle 在订阅前明确拒绝，不静默等待剩余子集。ANY 返回触发项和其余已知快照；ALL 遇到无法判断的项可提前返回明确诊断。订阅建立前后复核 revision，避免“刚好完成”事件丢失。

await ToolCall 在驻留等待期间仍属于当前 batch；其返回后按原有 batch settlement 才进入下一 ModelCall。它不允许同一 Turn 绕过未结算工具继续推理，也不承诺等待中同会话同时开另一 Turn。用户可停止等待后继续，已获准独立运行的 Job 保留。等待超时不是副作用未知：观察工具本身与被观察 Job 的效果应分别判断。

正常 await 被 Job 完成唤起后可按当前 Turn 继续，这是已有调用返回；与“无人等待时创建新模型调用”不同。主进程死亡不恢复旧 coroutine/Turn；旧 Turn 按现有规则终结，后续 successor Turn 重新查询原 Job。无活跃等待者时完成事件仅更新 durable fact/UI，默认不产生模型调用。

### 8.3 ContextCompiler、资源与 Tasks 的接线

增加 JOB_OBSERVATION 候选：仅当前会话有权查看且与任务相关、明确等待或新出现重要变化的 Job。携带 provider revision、观察时间、原始状态、简短摘要与 resultRef；执行状态来自 Runtime，日志/输出摘要仍是不可信内容。沿用 RequestContextManifest 记录实际纳入的 revision，去重和限量，完整日志按需读取。不因所有后台任务存在就注入全部状态。

后台执行仍持有原 effect footprint 与资源 owner，直到真实退出/对账证明允许释放。启动 ToolCall 结算不释放 Job 的执行资源；因此模型可继续推理、执行已证明不冲突的操作，但不能假定任意其他工具可并行。J1 不扩大现有 PRoot/Workspace 并发范围。

保留现有无进展保护：受信任 Job 观察应按既有状态工具语义处理，不能仅因结果仍为 RUNNING 就误判死循环，也不能对任意名字含 jobs 的扩展工具豁免。等待和实际运行分别记账，不重复预留或退款；Turn/Goal deadline 到期停止等待，Job 是否继续由原有 Job lease/Goal 契约决定，不默认续期。

沿用 Tasks 与现有命令卡；区分运行、请求取消、确认取消、未知与待收集。插件停用/更新不丢弃已启动 Job 的控制与对账身份；保留 host 管理的检查/停止入口及必要 Runtime，不能通过卸载移除唯一终态证据。此入口不准许启动新工作，收集仍需有效授权。

### 8.4 J2：同一次执行的前台转后台

仅在 Runtime 已证明能力时提供 FOREGROUND/BACKGROUND/AUTO；AUTO 必须与运行中 promotion 一起通过验收，不能先提供一个实际 cancel+restart 的占位模式。先限定 Linux command，不扩展下载、MCP 或子 Agent。

foregroundWaitBudget 只控制同步等候；executionDeadline/lease/Goal 额度控制实际执行。promotion 不重置任何预算、scope、凭据、Workspace、ExecutionTarget 或 effect owner。Runtime 必须从启动起拥有可持续的身份和日志；现有 one-shot 路径若做不到，应先在 Runtime 层改造，不能由 AgentLoop 重提命令。

用户卡片操作记录为可信用户控制事件，调用相同 Runtime promotion 接口，不伪造模型 ToolCall。与退出、取消、deadline 并发时只能发布一个确定结果：已经退出则返回原终态；成功转后台则返回同一 handle；无法证明则不给出后台成功回执。不支持 promotion 的 executor 保持现有行为，不显示按钮。

J2 是独立且风险较高的 Runtime 切片，先完成 J1 再决定是否投入；它不依赖远程 Node，也不应被安排到远程平台项目中。未来自动 continueWhenComplete 另属用户授权触发与 fresh admission，不在 J1/J2 范围内。

### 8.5 验收

- launch batch 可结算且 Job 继续；最终结果不改写 launch；丢失提交回执不重复执行。
- ANY/ALL、已完成、重复/非法/越权 handle、订阅竞态、等待超时/取消、来源离线和 UNKNOWN。
- 进程死亡后身份及 owner 保留，旧 Turn 不复活；Workspace 变更、fork、插件停用后无控制权泄漏。
- 后台写任务仍阻止冲突操作；cancel receipt 不提前释放 owner；collect 重验授权且不重复物化。
- completion 无等待者时不自动调用模型；观察注入有界且不会信任外部日志指令。
- promotion 与退出/取消/deadline 交错，同一命令仅启动一次，租期和预算不重置。

host 测试先用虚拟时钟、可控事件与 Runtime fixture；真实 detach、Binder、进程死亡、Tasks UI 须在获得当前设备授权后验证，host mock 不能替代。consumer 不暴露不可用的 Linux Job 工具；developer 仍服从 Advanced 与原授权边界。

## 9. 分阶段交付与 gate

| 阶段 | 独立交付结果 | 退出条件 |
| --- | --- | --- |
| R0 | 当前事实表、源码基线、对应 ADR 变更提案与阶段任务 | 设计接受后才改变任务顺序；明确测试和 owner |
| R1 | 单一工具绑定注册/读取路径 | 所有来源迁完；双表写路径删除；并发与撤销测试通过 |
| R2 | 唯一上下文编译入口 | 配对/信任/预算/恢复回归通过；旧重复路径删除 |
| J1 | Linux Job 的通用观察与有界 join | 原 owner 不变；等待/取消/恢复与上下文观察验收通过 |
| R3 | 包管理与会话选择闭环 | 归属、更新、恢复、即时停用跨入口一致 |
| J2（后续独立切片） | Linux 同一次执行的 AUTO / promotion | 无重放；退出竞态、身份、资源、预算及设备证据齐全 |
| R4 | 有证据的依赖与 owner 清理 | 无第二执行路径；若无实际问题可不做代码改动 |
| R5 | 插件管理产品体验 | host gate 完整，设备与模型结果分别列出 |

R1 → R2 → J1 → R3 → R5 为建议串行主线；J1 无需等待完整插件平台，R3 必须处理已运行 Job 的生命周期归属。R4 随各阶段消除必要依赖，末尾统一复核。J2 在 J1 验证后独立评估优先级，不阻塞 R3/R5。每次只启动一个阶段，不以整个大重构为由长期积累不可构建分支。不保留长期运行时双轨开关；阶段回退以源码提交为单位，不自动回滚用户文件或副作用。

每阶段先定向测试，再完整执行 source/JVM、双渠道 unit/lint/debug APK/AndroidTest APK、spotlessCheck、detekt、check-all.sh --source 和 git diff --check。通过仓库 host-slot wrapper 运行工程任务；如所建 HXA 要求更广门禁，以它为准。无相关代码变更时不反复跑全工程。

模型和设备验证需要当次授权。授权后先跑改动影响的轨迹，再在最终干净已提交源码运行既有 SGLang 系统基线；需要提交时按当前授权处理。BFCL 只作工具诊断，AndroidWorld 只作指定环境端到端补充，不替代 Helix 自身安全和恢复 oracle。固定模型、fixture、采样参数与 oracle；多轮报告成功率分母和长尾，不把一次成功当可靠性提升。

不得回归的硬门槛：无越权、无错配 executor、无工具协议孤儿、无副作用盲目重放、无丢失 durable 结算。效率目标先记录同条件基线，再决定阈值，不事先编造百分比收益。历史输出截断、偶发多余调用、SAF 间歇问题继续单独记录，不因重构完成宣称消失。

## 10. ADR 与任务落地

本提案不分配未经检查的 HXA 编号，不将已有 proposed ADR 自动标记 accepted。设计接受后按未占用编号建立 R1/R2/R3 的任务；不重新打开已完成的 HXA-220/223/227。

| 设计领域 | 应更新的现有决定 |
| --- | --- |
| Binding 身份、原子发布、审批一致性 | [工具契约](../adr/tools/001-descriptor-contract.md)，当前仍 proposed；明确本轮接受的字段/边界 |
| 编译、信任、配对、压缩 | [上下文](../adr/agent/002-context-compaction.md)、[预算与结果投影](../adr/agent/006-model-data-budget-boundaries.md) |
| 安装归属、启停、更新、恢复 | [Connector 安装与会话选择](../adr/connectors/003-ownership-and-installation.md)；在同一决定内推广包管理语义 |
| 执行 owner 与恢复 | [Turn 执行](../adr/agent/001-turn-coordination.md)；保持现有语义，只有真实契约变化才追加决定 |
| Job 观察、await、promotion 与资源归属 | [后台 Job 与终端](../adr/runtime/002-terminal-and-jobs.md)；J1/J2 分别建立任务，明确 launch 与 Job 生命周期分离 |

每阶段有独立范围、删除清单、验收与完成记录；status 只维护当前阶段，roadmap 只维护任务索引。本文件保持目标设计，不维护第二份实时进度。

## 11. 暂缓范围与主要风险

- 不引入远程 Node、子 Agent、Trigger 框架、通用 Workflow、动态 native 插件加载或另一套事件溯源存储。
- 不把所有能力机械变成插件，不以重构要求用户重复授权、不静默增加调用额度。
- 最大风险是“快照一致”被误解为“旧授权永远有效”：必须用 R1 的执行准入/撤销交错测试约束。
- 第二风险是编译器重新实现已有预算、压缩与记忆系统：只迁移职责，并删除旧重复路径。
- 第三风险是 Room 发布与外部激活被误当一笔事务：必须测试中间态、重启和幂等恢复。
- 长程能力提升需真实轨迹证据；架构改善本身不证明模型更聪明或手机性能更好。

## 12. 依据

- [原能力架构调研](../research/helix-agent-capability-architecture-convergence-2026-09-28.md)
- [Plugin Platform 方案](plugin-platform-refactor-2026-09-28.md)
- [异步 Job、等待与前后台执行调研](../research/async-jobs-wait-and-background-execution-competitive-study-2026-09-28.md)
- [当前状态](../development/status.md)
- 当前源码入口：ToolRegistry、ToolImplementationRegistry、PluginRegistry、ChatRequestAssembler、PromptRegistry。工具注册事实与调用关系通过 CodeGraph 核对。

输入文档为设计背景；其中 PluginOrigin 尚不存在、Mobile Use 工具数量和旧阶段顺序等文字不能覆盖当前源码。本次补充核对了 ADR-RUNTIME-002、DetachedJobTools/Collection/Store 及现有 Tasks 调用关系。竞品功能描述沿用调研作为设计输入，本次不新增对其当前版本能力的独立核验结论；本方案不依赖某一家 Provider 的原生 async API，也不承诺外部插件格式的完整标准兼容性。
