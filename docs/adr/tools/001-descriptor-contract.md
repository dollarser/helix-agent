# ADR-TOOLS-001: 工具描述契约与审批绑定

Status: accepted
Date: 2026-09-16
HXA: HXA-042, HXA-231
Deciders: Project owner（2026-09-30 明确要求按完整架构实施 HXA-231 R1）

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

## Alternatives considered

1. **强制安全字段变化必提升 `toolVersion`（roadmap 的另一选项）**：需要一套跨模块的静态检查在注册期比对"字段变化 vs version 是否递增"，而 registry 不保留旧 version 的 descriptor 历史（同 version 禁止重注册、不同 version 允许并存），无法在进程内可靠判定"相对上一次注册是否改了安全字段"。且模型请求 `(name, version)` 显式版本，version 语义已用于契约演进，用它额外编码安全字段会污染版本语义。未选择。
2. **只把 `timeout` 等个别字段单独加进现有九字段 binding**：与"完整契约"相反——遗漏任何一个安全字段（如 `maxOutputBytes` 从 8KiB 放宽到 8MiB 而不改变 hash）就复现同一缺口；逐字段枚举易漏且难证明覆盖完整。未选择。
3. **把 `schemaHash` 的定义扩到包含所有安全字段（即让 schemaHash 变成 contractHash）**：会让"schema 契约"与"安全契约"两个概念混为一谈，破坏 HXA-031 里 schemaHash 的稳定语义，且 `ApprovalBinding` 同时带两字段时无法区分"schema 变了"与"仅安全字段变了"。保留两个哈希、`contractHash` 为超集，语义更清晰。未选择。

## Consequences

v2 编码使旧 contractHash/精确批准不再匹配，须重新审批，不能兼容重用旧凭证。现有 `origin.canonicalOf()` 仍供来源启停键和展示使用，本轮不迁移这些键，避免把已有禁用设置意外恢复为默认启用。原子绑定按上述 R1 决定实施；完整插件安装/更新生命周期仍不在此范围。

同一主题维护一份规范；接受设计和实现验收仍分开记账。

## Verification

本方案已接受；当前 R1 主机迁移与交错验收见[主机证据](../../evidence/development/hxa231-atomic-binding-2026-09-30.md)。包含逐字段变化、来源碰撞、请求别名、审批/排队替换、可变集合与无关更新反例；设备/真实服务本轮 not requested。当前编码修复及实际运行见[收敛证据](../../evidence/development/contract-document-convergence-2026-09-29.md)，不借用历史 HXA-042 数字证明新编码。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

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
