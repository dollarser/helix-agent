# ADR-0011: 以覆盖完整安全 descriptor 的 contractHash 关闭审批失效缺口

Status: proposed
Date: 2026-09-02
HXA: HXA-042
Deciders: pending
Supersedes: none
Superseded by: none

## Context

HXA-042 是首个非 `time.now` 业务工具（`read`/`write`/`edit`/`files.*`）进入生产工具表的门槛。审批凭证（`ApprovalBinding`）此前由 `(name, version, schemaHash)` 三元组加上 scope/session/target/ui/args 哈希构成；`schemaHash` 只覆盖规范化后的 **输入/输出 schema**。

这留下一个审批失效缺口：一个 descriptor 若只改变**安全字段**（`timeout`、`maxOutputBytes`、`requiredCapabilities`、`operationClass`、`baseRisk`、`idempotency`、`origin`），而 `(name, version, schemaHash)` 保持不变，则会产生**相同**的 `ApprovalBinding.hash`——一份为旧契约铸造的审批凭证就能授权被篡改的新契约。roadmap 明确要求：仅修改这些字段但保持 `(name, version, schemaHash)` 不变必须被测试为**拒绝**，不得只靠 KDoc 约定或声称 timeout 已直接进入现有 binding。

`ToolRegistry` 的第一道防线是禁止重复注册同一 `(name, version)`（一次注册、静默覆盖不可能发生）。但生产 dispatcher（HXA-035/036）在**批准时**从*当前注册*的 descriptor 现算 binding，而审批凭证以不透明的 `bindingHash` 落库（`ApprovalEntity` 无逐字段列）；更早由旧契约铸造、仍在 TTL 内且未消费的凭证，仍可能与一个改了安全字段的 descriptor 的 binding 哈希碰撞。这一层需要机械测试 + 一个进入 binding 的完整契约哈希来关闭。

## Decision

1. 引入覆盖**整个安全 descriptor** 的 `ToolDescriptor.contractHash`：对 descriptor 的规范化形式做 SHA-256。规范化形式用 NUL 分隔拼接：`name`、`version`、`description`、`schemaHash`、`operationClass`、`baseRisk`、`timeout`(ms)、`maxOutputBytes`、`requiredCapabilities`(按 name 排序)、`idempotency`、`executionTarget`、`origin.canonicalOf()`。`contractHash` 是 `schemaHash` 的**超集**（schema 变则两者都变）。
2. `contractHash` 作为**直接字段**并入 `ApprovalBinding`（在 `schemaHash` 之后），进入 binding 的 `canonicalJson` 与 `hash`。`executionTarget` 已是 binding 既有直接字段（HXA-034/035 精确绑定），保持不变。
3. `origin.canonicalOf()` 刻意**排除** `serverProvidedHints`：这些是不可信的、展示用文本，若纳入契约会让一个 MCP 服务器通过编辑 hint 使已授予的审批失效（反被服务器握有否决权）。`serverProvidedHints` 变化**不得**改变 `contractHash`（机械测试强制这一反向不变量）。
4. `description` **纳入**契约（fail-closed）：description 是模型可见的工具语义，若改动却保持 `schemaHash` 不变会误导模型；把它纳入 contractHash 使任何描述变化都强制新审批。代价是描述文案改动会使既有审批失效——这被判定为正确方向（宁可失效也不放行），且生产工具描述是代码常量、非用户可改。
5. `ToolDispatcher.buildBinding` 在批准时从**当前注册** descriptor 取 `contractHash` 写入 binding（与 `schemaHash` 同源、同点）。
6. 机械门禁：`ContractHashGateTest`（`tools/framework`）逐字段证明每个安全字段单独变化都保持 `schemaHash` 不变而改变 `contractHash`，进而改变 `ApprovalBinding.hash`（旧凭证不匹配）；`ToolDispatcherTest` 证明 dispatcher 实际把当前 descriptor 的 `contractHash` 绑进呈现给 broker 的 binding。二者是"拒绝"的证据，不是 KDoc 约定。

## Alternatives considered

1. **强制安全字段变化必提升 `toolVersion`（roadmap 的另一选项）**：需要一套跨模块的静态检查在注册期比对"字段变化 vs version 是否递增"，而 registry 不保留旧 version 的 descriptor 历史（同 version 禁止重注册、不同 version 允许并存），无法在进程内可靠判定"相对上一次注册是否改了安全字段"。且模型请求 `(name, version)` 显式版本，version 语义已用于契约演进，用它额外编码安全字段会污染版本语义。未选择。
2. **只把 `timeout` 等个别字段单独加进现有九字段 binding**：与"完整契约"相反——遗漏任何一个安全字段（如 `maxOutputBytes` 从 8KiB 放宽到 8MiB 而不改变 hash）就复现同一缺口；逐字段枚举易漏且难证明覆盖完整。未选择。
3. **把 `schemaHash` 的定义扩到包含所有安全字段（即让 schemaHash 变成 contractHash）**：会让"schema 契约"与"安全契约"两个概念混为一谈，破坏 HXA-031 里 schemaHash 的稳定语义，且 `ApprovalBinding` 同时带两字段时无法区分"schema 变了"与"仅安全字段变了"。保留两个哈希、`contractHash` 为超集，语义更清晰。未选择。

## Consequences

- 收益：任何安全 descriptor 变化（含 MCP origin 的 server/protocol 变化）都强制新审批凭证；旧凭证因 binding 哈希不同而被原子 SQL 守卫（decision=APPROVED + hash 匹配 + 未过期 + 未消费）拒绝；缺口由机械测试而非文档约定关闭。
- 代价：`ApprovalBinding` 增加一个 `contractHash` 字段（`isSha256Hex` 校验）。`ApprovalEntity` 只存不透明 `bindingHash`，**无逐字段列**，故**不需要 Room 迁移**。生产/测试中所有构造 `ApprovalBinding` 的站点需补 `contractHash`（本任务已全量补齐：`ToolDispatcher`、`ToolDispatcherTest`、`ContractHashGateTest`、`RoomMigrationFixtureTest`、`RoomGuardAndConfigTablesTest`、`ApprovalWakeLatencyDeviceTest`、`ApprovalCardUiMapperTest`）。
- 兼容：description 文案改动会使既有审批失效（判定为正确方向）；`serverProvidedHints` 变化**不会**使审批失效（刻意，见 Decision 3）。
- 风险：规范化形式的字段顺序与编码是**契约的一部分**，未来改它会使所有历史 binding 哈希失配——因此字段顺序/编码一旦接受即冻结（Reconsider when）。`contractHash` 计算是纯 CPU（SHA-256 over 一段字符串），无 I/O，对审批热路径无可测开销。

## Verification

HXA-042 验收要求（`docs/verification-matrix.md` 行 HXA-042）：

- `./gradlew :tools:files:test :tools:framework:test :core:workspace:test`
- 门禁：`./gradlew :tools:files:test :tools:framework:test :core:workspace:test detekt spotlessCheck`
- `scripts/check-docs.sh`、`scripts/check-lockfiles.sh`、`scripts/check-secrets.sh`、`scripts/verify-adr.sh`、`scripts/verify-variant-boundaries.sh`

设备列：无（首个业务工具注册前以机械测试证明安全 descriptor 变化强制新 binding，无需模拟器）。

实际命令、exit code 和限制记录在 [HXA-042 完成记录](../completion-records/HXA-042.md)。

## Reconsider when

- `contractHash` 的规范化字段集合或顺序需要改变（会使历史 binding 哈希失配，需配套迁移或作废策略）。
- MCP origin 引入需要进入契约的新可信字段（当前只有 `serverId`/`protocolVersion`）。
- 产品决定 `description` 应可改而不使审批失效（当前 fail-closed 纳入）。
- `ApprovalEntity` 从"不透明 bindingHash"改为逐字段存储，需重新审视哈希绑定方式。

## References

- [ADR-0005](0005-standard-advanced-safety-profiles.md)
- [总体技术方案](../02-architecture-design.md)
- [安全、测试与发布](../07-security-testing-release.md)
- [手机端 Tool 编排](../11-mobile-tool-orchestration.md)
- [HXA-042](../04-roadmap-and-backlog.md)
