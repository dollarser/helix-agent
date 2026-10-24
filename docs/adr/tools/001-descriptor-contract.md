# ADR-TOOLS-001: 工具描述契约与审批绑定

Status: accepted
Date: 2026-09-16; revised 2026-10-17
HXA: HXA-042, HXA-231, HXA-276, HXA-277, HXA-279, HXA-286, HXA-288, HXA-289, HXA-290, HXA-292, HXA-303
Deciders: Project owner（2026-10-15 要求按评审更新目标设计和任务；接受范围见 Decision，实施另计）

## Context

工具参数 schema 不能表达执行目标、限制和能力变化；审批绑定需要可验证的工具契约身份。

[HXA-042](../../completion-records/HXA-042.md) 已交付 contractHash 与审批绑定。2026-09-30 所有者授权完整 R1 架构实施；本决定接受下述单一绑定、请求引用及撤销边界，不等于实现或验收已经完成。

## Decision

1. 引入覆盖**整个安全 descriptor** 的 `ToolDescriptor.contractHash`：对 descriptor 的规范化形式做 SHA-256。当前实现使用固定顺序 JSON array：首项 `helix-tool-contract-v2`，随后为字符串字段 `name`、`version`、`description`、`schemaHash`、`operationClass`、`timeout`(ms)、`maxOutputBytes`、`requiredCapabilities`(按 name 排序并用逗号连接)、`idempotency`、`executionTarget`，末项为来源字段 JSON array。来源依次为 `built-in`；`plugin/pluginId/pluginVersion/runtimeId`；`mcp/serverId/protocolVersion/sourceSchemaHash`；`a2a/agentId/skillId/interfaceOrigin/binding/protocolVersion/cardHash/skillHash`（斜线在这里分隔字段说明，不是运行时拼接符）。字符串按 JSON 转义、无空白编码后取 UTF-8 SHA-256，避免分隔符进入来源字段造成碰撞。`schemaHash` 原算法不变。`contractHash` 是 `schemaHash` 的**超集**（schema 变则两者都变）。
2. `contractHash` 作为**直接字段**并入 `ApprovalBinding`（在 `schemaHash` 之后），进入 binding 的 `canonicalJson` 与 `hash`。`executionTarget` 已是 binding 既有直接字段（HXA-034/035 精确绑定），保持不变。
3. 来源契约编码刻意**排除** `serverProvidedHints`：这些是不可信的、展示用文本，若纳入契约会让一个 MCP 服务器通过编辑 hint 使已授予的审批失效（反被服务器握有否决权）。`serverProvidedHints` 变化**不得**改变 `contractHash`（机械测试强制这一反向不变量）。
4. `description` **纳入**契约（fail-closed）：description 是模型可见的工具语义，若改动却保持 `schemaHash` 不变会误导模型；把它纳入 contractHash 使任何描述变化都强制新审批。代价是描述文案改动会使既有审批失效——这被判定为正确方向（宁可失效也不放行），内置描述由产品代码维护，外部描述仍是不可信声明，不能授予权限。
5. `ToolDispatcher.buildBinding` 在批准时从**本次请求绑定且仍有效的** descriptor 取 `contractHash` 写入 binding（与 `schemaHash` 同源、同点）。
6. 机械门禁：`ContractHashGateTest`（`tools/framework`）逐字段证明每个安全字段单独变化都保持 `schemaHash` 不变而改变 `contractHash`，进而改变 `ApprovalBinding.hash`（旧凭证不匹配）；`ToolDispatcherTest` 证明 dispatcher 实际把当前 descriptor 的 `contractHash` 绑进呈现给 broker 的 binding。二者是"拒绝"的证据，不是 KDoc 约定。


### 发现配套与执行权限（HXA-303）

ToolDescriptor.discoveryCompanions 是能力提供方声明的同来源曝光配套，允许有限图中互相引用；宿主在当前已准入/可用的描述快照内展开并去重，不按插件名称补规则。集合随原子发布深复制并冻结，撤销/替换/跨来源描述不能补入。它不是运行强制依赖、能力开关或批准，不能自动安装、选用、执行或扩大访问；缺失配套仍保持缺失。

该字段不进入执行安全 contractHash：它不修改参数、工具语义、执行目标、能力、限额或副作用。运行依赖/readiness 的完整表达仍由 286 接续，不能将发现提示直接升级为执行权限或启动条件。验证覆盖未知插件、传递环、缺失/撤销/替换、来源隔离、预算和发布不可变性。

### 原子绑定与请求身份（HXA-231 R1）

`ToolRegistry` 是唯一注册事实源；`ToolBindingStore` 原子保存 descriptor/executor、可信结构化 owner、实现 revision 和进程 incarnation。Built-in 在容器对外可用之前成对装配；Plugin/MCP/A2A 先构造完整候选，再整批发布。重复或跨 owner 碰撞、持久提交失败保留旧集合。发布时深复制并冻结 schema、能力集合及来源提示，调用方继续修改原集合不会改变快照。独立可写 `ToolImplementationRegistry` 与 `PluginToolBinding` 已删除，不留双写兼容层。

每次 ModelCall 的实际曝光名称通过 `ModelToolBindings` 映射到 `ToolBindingRef`，写入已有 `RequestContextManifest.tools` 有界 tuple：`[exposedName, name, version, contractHash, owner, implementationRevision, incarnation]`。不持久化 executor，不将该字段发给 Provider。返回的内部全名、未曝光名称和过期 alias 都不能重新查找最新实现。未找到的绑定也冻结为拒绝；等待期间出现同名工具不会改变该结论。Tool schema、参数解释、调度 footprint、审批和 executor 均来自同一绑定。合法 alias 映射到其绑定的内部名字，不另开权限路径。

`implementationRevision` 是安装身份与来源 revision 的 SHA-256：本机安装身份取 Android 包名和 `lastUpdateTime`，内置默认 revision 使用契约哈希，Plugin 使用 manifest 版本/runtime；MCP/A2A 加入完整 endpoint 与契约哈希的摘要。它标识可信装配身份，不声称检测远端服务内部代码。`contractHash` 表达工具契约；随机 incarnation 只处理本次进程内撤销/替换。同一候选（原 executor、descriptor、revision）重复投影保留 incarnation；实际重连换 executor 会失效旧请求。无关 owner 更新不造成全局失效。

`ApprovalBinding.implementationIdentity` 保存结构化 `[owner, implementationRevision]` 并进入 canonical hash，不包含 incarnation。应用重启不会仅因新 incarnation 改变稳定批准身份；安装或来源实现身份变化不复用旧批准。新增字段使升级前的精确批准不再匹配，不能绕过重新准入。

发布写者串行准备，存储提交期间不占用读者/准入锁；候选成功后在短临界区一次替换。发布回调只允许原子本地存储提交和活跃引用更新，禁止递归发布、网络或工具执行。MCP 启用配置在 Room 事务中提交；A2A 显式启用同理，Card 变化撤回旧投影。准入与撤销在同一短临界区排序，审批等待和 executor 均在锁外。撤销先完成则未启动调用不得执行；准入先完成则持有原执行器并按原事实结算，不伪造副作用已撤销。旧引用只存于在途工作，Registry 不保留无限历史实现。

权限、effect owner、审批消费和 UNKNOWN 的原有事实语义不变。绑定失效是执行前拒绝，模型可重新发现并发起新调用；不能自动重放写操作。通用 Job、Core 分层、插件安装生命周期和上下文策略不是本次 R1 的隐含范围。

### Decision history — 2026-09-30：审批持久消费与短准入锁

所有者授权的审查修复保持原撤销排序：Registry 锁内只提交内存准入标记，随后在锁外消费绑定的持久审批证明；消费失败不执行工具。已经先准入的调用保留其原 executor，后到的撤销不伪造已准入动作没有发生；先撤销则不能准入、消费或执行。这样不在目录锁内等待 Room，也不开放跳过授权的执行路径。

`tools.search` 和封闭的 `ask_user` 通过受信任 metadata executor 避免被原后台 Job owner 阻断；发现与反问不授予执行权限，普通写入/Runtime 启动仍受原 owner 互斥控制。

### 插件增量复用同一绑定与结果契约（HXA-276/277/279）

Browser 的 12 个现有工具由唯一 PluginOrigin 发布，原 built-in 路径直接删除；新增标签发现和条件等待同样提供真实 descriptor/schema、操作类别、目标/能力、期限、大小限制与 executor，纳入既有发现窗口。契约变化通过 version/contractHash 和实现身份表达，不能保留不匹配的旧审批或在调用时转查同名新实现。

注册引用与调用身份保持原子绑定规则；HXA-277 的资源 owner 和释放契约由 [ADR-CONNECTORS-003](../connectors/003-ownership-and-installation.md#11-原生实例共享资源与释放hxa-277)维护。工具注册被撤销不等于已准入 executor 退出；执行器未退出前不能释放其物理容量或所需资源。共享宿主服务不构成执行旁路，复合调用仍经 Dispatcher，不直接调用其他插件 executor。

Browser/Mobile Use 的观察结果复用现有 ToolResult、InvocationOutcome 和视觉产物通路，只增加确有缺失的事实：匹配数量与歧义、截断、实际后端、观察是否失效、像素是否真的提供。不是要求所有工具输出统一大 JSON；字段由真实执行器或图片发布结果产生，不能把文件 URI/截图路径当成模型已收到像素，也不能把截断列表上的未找到报告为全局不存在。

区分派发成功、条件满足、业务结果确认及效果未知；等待命中只证明所声明条件满足，不自动证明用户目标完成。未知结果的后续核查保持只读、原目标与原身份，不自动重放动作；被拒绝不获得换账号/后端绕行的许可。取消、deadline、无 host、导航/owner 失效分别返回真实结果，不能 catch 后报成功。受影响的旧内部结果形态直接替换，不增加兼容层或新的执行引擎。

### 2026-10-15 目标工具契约与执行事实

[HXA-286](../../development/tasks/HXA-286.md) 将工具的规范名、语义、参数、描述和前置依赖作为重构输入。模型侧保持具名工具，通用操作优先采用已形成惯例的 read/write/edit、glob/grep、web_search/web_fetch；领域和外部来源保留必要限定。名称不能替代稳定来源/Binding，也不暴露一个万能 execute(action,args)。同一请求不同时提供等价执行别名，外来名称仅作发现线索。

描述按用途/适用时机、前置范围、参数及副作用、结果/下一步组织，采用一致英文动作句，UI 继续本地化。Schema 明确路径身份、单位、替换/追加/提交、空结果及截断；保留内容 hash 和目标校验。契约变化同步实现、调用者、Skill、提示、批准身份和测试，直接删除旧内部入口，不靠别名维持双轨；具体参数由所属任务冻结。

HXA-286 同时冻结跨域身份和完成语义：Turn/call 由原持久准入与调用建档创建；实际异步路径才创建 jobId/executionId；长期 ExecutionResource 的 resourceId/generation、生命周期 owner 与控制调用分开；artifactId 由原发布路径创建并保存来源与引用。MCP 内容 URI 不作为执行资源句柄。各 ID 的创建、持久、引用与清理责任沿原 owner，不以 ID 代替授权，也不让 287 按尚未定义的 Job 语义固定存储端口。

[HXA-288](../../development/tasks/HXA-288.md) 在既有执行/结果通路上按实际缺口接续这些契约，不重建 Dispatcher/Job/产物平台：调用返回、实际执行退出、输出收尽与结果落盘分别有判据，关联原 Job 状态和产物发布；覆盖直接调用、观察和启动后观察。无进程/输出流时明确不适用，MCP/HTTP 不套 POSIX exit code 或完整 Job 生命周期。产物包含可信来源、会话/调用归属、用途、内容身份与引用；内部观察、交付文件、模型视觉输入不能靠工具名推断。保存、用户展示和实际披露给模型是不同事实，GC 服从活跃引用；产物不授予权限。

现有 R1 原子绑定、execution-start 屏障、审批消费和 UNKNOWN 不重放继续成立。逻辑完成/取消不证明 executor 退出；开始后失败不能宣称零副作用。新增工具必须沿同一 Dispatcher，实际 Runtime 隔离和平台约束不因统一契约而放宽。

cancel requested、后端 confirmed 与实际退出分开；确认受理不证明副作用回滚。迟到回调仅对原调用/实例补充可核实事实，不覆盖替代实例或重复结算。286 定义当前版本重启 reconcile/清理责任，287/288 分别验证持久宿主和真实执行；外部效果后落盘前保持 UNKNOWN，不盲重放，落盘后通知失败不重新执行。契约、最小纵切和每个适配阶段均积累取消/崩溃/输出/插件缺失证据，294 汇总并回归。

### 后续 hooks 的执行阶段约束

HXA-286/287/288 保留参数准备、规范化/schema 校验、policy/授权、持久 execution-start、执行、结算、后置通知的明确顺序；本轮不注册可执行 hooks。未来前置扩展如能改参数，必须保留原意图与修改事实，并让最终参数重新经过目标解析、校验、权限/批准绑定；旧批准不能授权变化后的输入，不改写已开始调用的持久输入。

执行前拒绝必须零启动；执行后通知只能消费已发生的事实，失败/超时不撤销副作用、不伪造原调用失败或重发。外部代码不在数据库事务或准入锁内运行；事件标识、重复交付及重入/截止策略由未来 hook 功能明确，不能把内部观察通知接口当任意脚本执行入口。hook 输出/插件元数据不生成权限，后续效果仍进入原治理链。

当前验证针对参数改变、旧批准拒绝、开始屏障与通知失败，不要求为反例预建 hook runner。类型和目录应容纳新增具名控制工具，不能把现有直接/观察/启动后观察的集合误作所有未来工具的永久封闭分类。

### 目录与结果事实（2026-10-17 目标契约）

文件/执行工具保持独立具名 Schema，参数经原请求捕获的可信 Workspace/environment/mapping 解析后进入同一验证、授权、限额和审计链；模型可按 Schema 请求已暴露目标，但模型输出、MCP URI 或插件描述不能自行声明可信执行身份或赋予权限。ResourceRef、网页 URL、执行路径、运行句柄和 artifact 引用明确区分，同名或相同字符串不建立等价关系。

工具向模型提供实际环境/cwd、live/snapshot、输入映射和产物去向；不支持、不可用、输入未导入、源冲突、已保存未发布有不同可操作结果。产物发布和源回写分别结算，Provider 无真实条件写机制时不宣称原子 CAS；外部效果后失败保留部分/未知事实。UI 标题仍只显示操作和文件名，完整来源、参数与状态放详情；显示投影不修改执行身份。迁移和反例归 [Harness §23.3.1](../../architecture/harness-refactor-plan.md#2331-目录资源与执行环境) 与 286～294 的实际改动；现有工具名/JSON 展示适配不能成为第二身份权威，也不因有分支就自动列为无效代码。

## Alternatives considered

- 为插件内部组合直接调用其他 executor：会绕过请求绑定、权限、期限与审计，仍使用同一 Dispatcher；可信宿主服务复用按资源 owner 的最小接口处理。
- 只增加提示词或统一 success 字段：不能证明实际后端、观察有效性和像素回流；在既有结果通路提供真实事实，不引入通用 Observation 平台。

1. **强制安全字段变化必提升 `toolVersion`（roadmap 的另一选项）**：需要一套跨模块的静态检查在注册期比对"字段变化 vs version 是否递增"，而 registry 不保留旧 version 的 descriptor 历史（同 version 禁止重注册、不同 version 允许并存），无法在进程内可靠判定"相对上一次注册是否改了安全字段"。且模型请求 `(name, version)` 显式版本，version 语义已用于契约演进，用它额外编码安全字段会污染版本语义。未选择。
2. **只把 `timeout` 等个别字段单独加进现有九字段 binding**：与"完整契约"相反——遗漏任何一个安全字段（如 `maxOutputBytes` 从 8KiB 放宽到 8MiB 而不改变 hash）就复现同一缺口；逐字段枚举易漏且难证明覆盖完整。未选择。
3. **把 `schemaHash` 的定义扩到包含所有安全字段（即让 schemaHash 变成 contractHash）**：会让"schema 契约"与"安全契约"两个概念混为一谈，破坏 HXA-031 里 schemaHash 的稳定语义，且 `ApprovalBinding` 同时带两字段时无法区分"schema 变了"与"仅安全字段变了"。保留两个哈希、`contractHash` 为超集，语义更清晰。未选择。

## Consequences

插件来源和新增观察结果继续使用同一绑定与结算通路。增加事实字段会占用输出预算，需保持有界且与模型实际收到的产物一致；内部契约改变后，旧请求与不匹配批准直接失效，调用方重新发现并按当前规则准入。

v2 编码使旧 contractHash/精确批准不再匹配，须重新审批，不能兼容重用旧凭证。现有 `origin.canonicalOf()` 仍供来源启停键和展示使用，本轮不迁移这些键，避免把已有禁用设置意外恢复为默认启用。原子绑定按上述 R1 决定实施；完整插件安装/更新生命周期仍不在此范围。

同一主题维护一份规范；接受设计和实现验收仍分开记账。

## Verification

HXA-276/277已交付有界验收；279观察增量已实现，实际结果见[279证据](../../evidence/development/hxa279-browser-observation-2026-10-12.md)。需证明单一 owner、旧绑定拒绝、取消但 executor 未退出时资源不释放，以及新观察事实真正进入下一 ModelRequest。覆盖歧义/截断、像素未提供、deadline、导航/owner 失效和未知动作，不由历史 R1 结果推定通过；具体主机、设备与模型验收见对应 HXA。

本方案已接受；当前 R1 主机迁移与交错验收见[主机证据](../../evidence/development/hxa231-atomic-binding-2026-09-30.md)。包含逐字段变化、来源碰撞、请求别名、审批/排队替换、可变集合与无关更新反例；设备/真实服务本轮 not requested。当前编码修复及实际运行见[收敛证据](../../evidence/development/contract-document-convergence-2026-09-29.md)，不借用历史 HXA-042 数字证明新编码。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## Decision history — 2026-10-15

顺序复核落地：身份、四个完成条件、取消确认/迟到回调、当前版本 reconcile 与清理契约前置到 286；287/288 在其上分别建立最小 Turn 纵切和执行核心，再共同集成，非进程工具保留真实语义。本次仅更新文档，未实施或新增验收结果。

扩展性补充：补执行前干预与执行后通知的责任边界，参数变化重验授权，已发生效果保持原事实；执行器与通知均不得跨越原治理或持锁调用外部代码。 本次只更新目标约束，未实现四类后续能力。

接受统一语义/前置依赖先行与执行结果/产物合并设计，承接 HXA-286/288～290/292；既有 R1 绑定不重建。改名收益与新增能力收益须分别验证。 本轮只更新设计与任务，代码和验收由对应 HXA 记录。

## References

- [HXA-276：Browser 贡献](../../development/tasks/HXA-276.md)
- [HXA-277：原生资源](../../development/tasks/HXA-277.md)
- [HXA-279：UI 观察反馈](../../development/tasks/HXA-279.md)
- [插件研究与任务映射](../../research/topics/helix-plugin-architecture-optimization-2026-10-11.md#7-任务承接与验收)
- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history — 2026-10-17

Owner 授权当前独立优化与后续任务收敛：303 将配套发现从宿主名称分支移到贡献描述，采用当前快照内的有限闭包，排除安全 hash 并冻结集合。已删除 PREFERENCE_DENIED 旧审计兼容枚举及对应展示；新预览存储基线不承诺读取旧审计行。286 的完整贡献协议及 288 的类型化执行/产物仍待实施，验收归 [303](../../development/tasks/HXA-303.md)。

按 Owner 目录架构研究补可信环境/映射和工具结果分类，产物发布与源回写独立，模型与 UI 使用同一原事实的不同投影。接受目标设计而非提前交付远程/新映射，现有 R1 绑定、具名工具和权限链不重建；实现按原任务完整替换。

## Decision history — 2026-09-28

按所有者授权移除风险等级，contractHash 不再包含 baseRisk；operationClass、scope/目标与其余执行约束继续绑定。规范形式变化使旧批准失效，不能跨契约重用。

## Decision history — 2026-09-29

所有者要求先收敛现有实现与文档，R1 保留下一任务。核对发现旧来源冒号拼接存在不同字段元组的身份碰撞；修复审批哈希为版本化、结构化编码，并保留来源启停键。ADR 仍 proposed，完整原子绑定的身份/撤销/迁移决定在 R1 同主题收敛。

## Decision history — 2026-09-30：原子绑定实施

所有者要求完整架构优化，接受 HXA-231 R1 的最小契约：

- 单一 Registry 持有不可分的 descriptor/executor、可信 owner、implementationRevision 和进程内 incarnation；来源批次先校验再一次发布，失败保留旧集合。
- 请求仅持有实际曝光的 BindingRef，不序列化 executor。调度、审批与执行解析同一绑定；旧请求不得静默解析最新同名实现。
- implementationRevision 是可信来源实现身份，contractHash 是契约身份，进程 incarnation 只处理同进程替换/撤销。相同 revision 的重复投影可保留原绑定；无关来源更新不作全局失效。
- 执行准入与来源替换在线性化点排序；锁内不运行工具或网络、不等待审批。已准入动作继续按原执行事实结算，不伪称已撤销副作用。
- 删除独立可写实现表与双写辅助接口。权限、effect、审批消费、UNKNOWN 和原执行所有权继续由原有边界维护。

实施按[HXA-231](../../completion-records/HXA-231.md)验证，R2/J1/插件生命周期不由本决定自动启动。

## Decision history — 2026-10-11：插件来源与可核实观察结果

所有者要求同步新增插件 HXA 的设计决定。接受 Browser 唯一 PluginOrigin、同一 Dispatcher 下的资源引用，以及执行器提供的最小观察事实；内部来源和结果契约直接替换，不保留旧批准转换或结果兼容层。资源归属由 Plugin/Browser ADR 维护，当前 R1 绑定算法不重建；新增功能尚待各任务实现和验收。

## Decision history — 2026-10-12：最小观察事实接线

279直接替换Browser snapshot/find/screenshot及Mobile Use snapshot/find/wait的所涉结果版本。Browser采用实际WEBVIEW_DOM、观察时间和已知TTL/页面失效事实；查找总数与返回上限分离，截断不证明唯一或全局不存在。Mobile Use保留实际后端、不编造不同后端的统一TTL。pixelsAttached表示经视觉准备提供artifact，实际请求图像仍由原receipt与最后读取/编码证明；仅保存路径为false，字段不能替代授权。两项只读工具沿现有schema/policy/授权/执行/审计，R1绑定算法、发现上限与未知副作用结算不改。

## Decision history — 2026-10-24：已交付绑定与内容贡献分开

Registry/Binding 与 303 发现配套是现行基线，不再迁移。286 聚焦当前插件内容/目录/readiness 的来源快照；293-A 消费同一快照协调可选预算，不是权限或新注册表。内部观察 executor 类型、原调用 ID 校验和截图名条件当前有安全/容量职责；仅在等价可信契约有明确消费者收益且保留反例时局部替换。没有证据要求所有结果/JSON 一次迁移，不允许以远端声明取得内部观察资格。
