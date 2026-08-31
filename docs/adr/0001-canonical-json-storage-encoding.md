# ADR-0001: core:model 使用手写严格 canonical JSON 作为领域值存储编码

Status: accepted
Date: 2026-08-31
HXA: HXA-010
Deciders: Project owner（通过 Codex 文档与决策收口审查授权）
Supersedes: none
Superseded by: none

## Context

HXA-010 在 `core:model` 实现了多个必须持久化到 Room 的领域值（`TurnBudgets`、`HelixError`、`ExecutionTargetDescriptor`、`ExecutionLimits`、`ToolExecutionEnvelope`），它们需要一个确定性的字符串编码：

- 编码必须是字节级确定的（同一值永远得到同一字符串），否则 Room 中的行无法被用于恢复判定、审计比对和未来的哈希绑定；
- 解码必须严格：来自 Room 的字符串可能损坏或被手工编辑，解析失败必须产生可诊断的 `HelixError`（STORAGE/VALIDATION），而不是静默降级；
- Room 行在调试、崩溃分析和审计时需要人类可读，团队用 `sqlite3`/Studio 直接查看；
- `core:model` 是纯 JVM 模块（无 Android 依赖），且 AGENTS 规则禁止新增未经任务授权的重量级第三方组件。version catalog 与 [05-development-environment.md](../05-development-environment.md) 第 4 节基线已固定 `kotlinx-serialization-json` 1.9.0，但截至本 ADR 没有模块消费该依赖，Gradle 也没有应用 serialization 编译器插件；`core:model` 是否引入该库仍需单独决策（见 Alternatives 第 1 条）。

设计文档 [02-architecture-design.md](../02-architecture-design.md) 还规划了另一个不同的 canonical JSON：工具**参数**的规范化（用于审批哈希，`toolName || toolVersion || toolSchemaHash || canonicalArguments || ...`），该实现按路线属于 `tools:framework`（HXA-031），其规则更严格（如键排序的语义、对嵌套工具 schema 的处理），不必然与本 ADR 的存储编码相同。

## Decision

在 `core/model/src/main/kotlin/com/helix/core/model/internal/Json.kt` 中提供 `internal` 可见的手写严格 canonical JSON 编码器/解析器，作为 `core:model` 中少量、固定 shape 领域值的存储编码基础设施：

- 写入：每个领域类型选择固定字段顺序（`toStorageString()` 中的 `FIELDS` 列表）；map 字段按键排序；RFC 8259 转义；仅 64-bit 整数；紧凑分隔符（`,` 后无空格）；空对象/数组输出 `{}`/`[]`；可选字段为 `null`。
- 解析：只接受上述子集；拒绝浮点数、前导零、超出 signed 64-bit 的整数、重复键、未转义控制字符、尾随内容；所有解码失败抛 `IllegalArgumentException`（调用方映射为 `HelixError`）。
- 边界：该编码器是 `internal` 的，不属于公开 API；本 ADR 不适用于 Provider/MCP wire DTO、导入导出格式或工具参数 canonical 哈希。工具参数 canonical 哈希（HXA-031）必须单独实现并单独评审，不得假设两者字节兼容。
- HXA-010 已双向编码的领域类型为 `TurnBudgets`、`HelixError`、`ExecutionTargetDescriptor`、`ExecutionLimits`、`ToolExecutionEnvelope`。新增类型只有在具备已知向量、round-trip 和 malformed-input 测试后，才能把该 JSON 当作恢复来源。
- 仅使用 canonical writer 计算 hash 的类型不因此自动具备可恢复存储格式。例如 `PlanArtifact` 若没有领域 decoder，HXA-014 必须从规范化的 `plans`/`plan_steps` 列重建，或先补齐 decoder 与迁移测试，不能只保存一段无法恢复的 JSON。

## Alternatives considered

1. **kotlinx.serialization（库已在 catalog 固定为 1.9.0）**：类型安全、生态成熟，serialization 编译器插件可以只应用到需要的模块，并不会把 `@Serializable` 强加给所有模块；插件版本仍由根构建统一解析。未选择它处理本 ADR 的固定 shape，是因为默认 JSON 不是这里要求的严格 canonical 子集，重复键、数值范围和字节级输出仍需额外校验，而 HXA-010 的小型实现和已知向量已经存在。M2 Provider DTO 应独立评审并优先采用该主流库，不应扩展本手写格式去承载网络协议。
2. **Moshi / Gson**：运行时反射，输出顺序依赖运行时类型信息，默认不是 canonical；引入新的第三方运行时组件到 core 路径，许可证和供应链审查成本与收益不匹配（core:model 的存储格式只有 5 种固定 shape）。
3. **二进制格式（MessagePack/Protobuf/FlatBuffers）**：编码紧凑、解析快，但 Room 行不可直接阅读，破坏调试和审计可读性；需要引入 schema 管理工具和依赖。当前数据量（每行 < 1KB）下没有性能需求支持这个复杂度。
4. **每个类型各自手写字符串拼接（无共享解析器）**：写入容易，但解析/严格校验代码会在 5 个类型中重复 5 遍，且容易漂移；共享 `internal` 解析器把严格性规则（重复键、尾随内容、64-bit 范围）集中在一处并有独立单元测试。

## Consequences

- 收益：零新增依赖；字节级确定、可审计、人类可读；严格解码集中在一个有完整测试的内部组件；core:model 保持纯 JVM。
- 代价：手写解析器约 400 行，需要维护；Kotlin 编译器不做类型推导（所有字段手工映射）；`internal` 组件不能给其他模块复用——其他模块若需要存储编码，必须各自决策。
- 后续约束：HXA-014 只能对具备 decoder 的领域值直接持久化 `toStorageString()` 原文；任何格式变更（字段增删、顺序调整）必须同步字段集合、已知向量和 Room migration fixture。Room schema version 是数据库迁移的权威版本，不能假设旧 JSON 会被新 decoder 自动兼容。
- 风险：手写解析器存在被绕过严格性检查的回归风险——由 `CanonicalJsonTest`（internal 包内直接测试解析器）和每个领域类型的 malformed-input 测试共同兜底；detekt 对 `JsonParser` 的 `TooManyFunctions` 做了**仅限该类的**抑制（递归下降解析器每产生式一个函数，属规则误报），不放宽项目级门禁。

## Verification

已执行（HXA-010 工作树，JDK 17，Gradle 9.5.0）：

- `./gradlew :core:model:test` → 64 tests, 0 failures, 0 skipped，exit 0。其中 `internal/CanonicalJsonTest` 覆盖转义/反转义、重复键、尾随内容、截断、64-bit 边界、前导零、浮点拒绝；每个领域类型的测试覆盖已知向量、round-trip 和 malformed 输入。
- `./gradlew spotlessCheck detekt` → exit 0。
- `./scripts/check-lockfiles.sh` → 29 个 lockfile 一致，exit 0。

2026-08-31 文档与决策收口审查接受本 ADR 的上述窄范围：保留 HXA-010 已实现并测试的固定 shape 编码，不把它扩张为项目通用 JSON、网络协议或审批哈希实现。接受决定不替代 HXA-014/015 的 Room migration 和恢复验收。

## Reconsider when

- M2 Provider 层（HXA-021 起）需要跨模块序列化复杂嵌套 DTO，手写映射维护成本超过引入 kotlinx.serialization 的成本；
- 存储编码需要跨进程/跨设备稳定（当前仅单机 Room，不触发）；
- HXA-031 的工具参数 canonical 实现与本实现规则冲突且无法兼容；
- 手写解析器在 HXA-014/015 的实际恢复路径中暴露出严格性缺陷（例如需要支持浮点 token 计数）。

## References

- [02-architecture-design.md（ToolDescriptor / 审批哈希 / argsJson）](../02-architecture-design.md)
- [07-security-testing-release.md（canonical JSON 单元测试范围）](../07-security-testing-release.md)
- [04-roadmap-and-backlog.md（HXA-010、HXA-014、HXA-031）](../04-roadmap-and-backlog.md)
- 实现：`core/model/src/main/kotlin/com/helix/core/model/internal/Json.kt`
- 测试：`core/model/src/test/kotlin/com/helix/core/model/internal/CanonicalJsonTest.kt`
