# ADR-TOOLS-001: 工具描述契约与审批绑定

Status: proposed
Date: 2026-09-16
HXA: HXA-042, HXA-231
Deciders: pending

## Context

工具参数 schema 不能表达执行目标、限制和能力变化；审批绑定需要可验证的工具契约身份。

`proposed` 指整份长期规范仍需结合 R1 裁决，不表示功能尚未开发：[HXA-042](../../completion-records/HXA-042.md) 已交付 contractHash、审批绑定与测试；HXA-231 已获原子绑定实施授权但尚未完成。本轮仅核对并修复现有审批契约编码，不把既有代码或修复自动等同于接受完整 R1 规范。

## Decision

1. 引入覆盖**整个安全 descriptor** 的 `ToolDescriptor.contractHash`：对 descriptor 的规范化形式做 SHA-256。当前实现使用固定顺序 JSON array：首项 `helix-tool-contract-v2`，随后为字符串字段 `name`、`version`、`description`、`schemaHash`、`operationClass`、`timeout`(ms)、`maxOutputBytes`、`requiredCapabilities`(按 name 排序并用逗号连接)、`idempotency`、`executionTarget`，末项为来源字段 JSON array。来源依次为 `built-in`；`plugin/pluginId/pluginVersion/runtimeId`；`mcp/serverId/protocolVersion/sourceSchemaHash`；`a2a/agentId/skillId/interfaceOrigin/binding/protocolVersion/cardHash/skillHash`（斜线在这里分隔字段说明，不是运行时拼接符）。字符串按 JSON 转义、无空白编码后取 UTF-8 SHA-256，避免分隔符进入来源字段造成碰撞。`schemaHash` 原算法不变。`contractHash` 是 `schemaHash` 的**超集**（schema 变则两者都变）。
2. `contractHash` 作为**直接字段**并入 `ApprovalBinding`（在 `schemaHash` 之后），进入 binding 的 `canonicalJson` 与 `hash`。`executionTarget` 已是 binding 既有直接字段（HXA-034/035 精确绑定），保持不变。
3. 来源契约编码刻意**排除** `serverProvidedHints`：这些是不可信的、展示用文本，若纳入契约会让一个 MCP 服务器通过编辑 hint 使已授予的审批失效（反被服务器握有否决权）。`serverProvidedHints` 变化**不得**改变 `contractHash`（机械测试强制这一反向不变量）。
4. `description` **纳入**契约（fail-closed）：description 是模型可见的工具语义，若改动却保持 `schemaHash` 不变会误导模型；把它纳入 contractHash 使任何描述变化都强制新审批。代价是描述文案改动会使既有审批失效——这被判定为正确方向（宁可失效也不放行），内置描述由产品代码维护，外部描述仍是不可信声明，不能授予权限。
5. `ToolDispatcher.buildBinding` 在批准时从**当前注册** descriptor 取 `contractHash` 写入 binding（与 `schemaHash` 同源、同点）。
6. 机械门禁：`ContractHashGateTest`（`tools/framework`）逐字段证明每个安全字段单独变化都保持 `schemaHash` 不变而改变 `contractHash`，进而改变 `ApprovalBinding.hash`（旧凭证不匹配）；`ToolDispatcherTest` 证明 dispatcher 实际把当前 descriptor 的 `contractHash` 绑进呈现给 broker 的 binding。二者是"拒绝"的证据，不是 KDoc 约定。

## Alternatives considered

1. **强制安全字段变化必提升 `toolVersion`（roadmap 的另一选项）**：需要一套跨模块的静态检查在注册期比对"字段变化 vs version 是否递增"，而 registry 不保留旧 version 的 descriptor 历史（同 version 禁止重注册、不同 version 允许并存），无法在进程内可靠判定"相对上一次注册是否改了安全字段"。且模型请求 `(name, version)` 显式版本，version 语义已用于契约演进，用它额外编码安全字段会污染版本语义。未选择。
2. **只把 `timeout` 等个别字段单独加进现有九字段 binding**：与"完整契约"相反——遗漏任何一个安全字段（如 `maxOutputBytes` 从 8KiB 放宽到 8MiB 而不改变 hash）就复现同一缺口；逐字段枚举易漏且难证明覆盖完整。未选择。
3. **把 `schemaHash` 的定义扩到包含所有安全字段（即让 schemaHash 变成 contractHash）**：会让"schema 契约"与"安全契约"两个概念混为一谈，破坏 HXA-031 里 schemaHash 的稳定语义，且 `ApprovalBinding` 同时带两字段时无法区分"schema 变了"与"仅安全字段变了"。保留两个哈希、`contractHash` 为超集，语义更清晰。未选择。

## Consequences

v2 编码使旧 contractHash/精确批准不再匹配，须重新审批，不能兼容重用旧凭证。现有 `origin.canonicalOf()` 仍供来源启停键和展示使用，本轮不迁移这些键，避免把已有禁用设置意外恢复为默认启用。R1 必须单独处理稳定绑定身份、所有权、请求引用及撤销；此修复不声称已解决双注册表或完整来源生命周期。

同一主题维护一份规范；接受设计和实现验收仍分开记账。

## Verification

此方案仍 proposed，不因代码中存在 contractHash 字段就视为整份字段集合和规范化方案已接受。验收需逐字段变化、外部来源碰撞、描述变更及无关提示不影响契约的机械测试。当前编码修复及实际运行见[收敛证据](../../evidence/development/contract-document-convergence-2026-09-29.md)，不借用历史 HXA-042 数字证明新编码。

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
