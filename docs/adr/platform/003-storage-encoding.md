# ADR-PLATFORM-003: 领域值的严格存储编码

Status: accepted
Date: 2026-09-16
HXA: HXA-010
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

固定 shape 的领域值需要字节确定、人类可读和严格失败的存储编码，不能把用于 hash 的字符串误当可恢复数据。

## Decision

在 `core/model/src/main/kotlin/com/helix/core/model/internal/Json.kt` 中提供 `internal` 可见的手写严格 canonical JSON 编码器/解析器，作为 `core:model` 中少量、固定 shape 领域值的存储编码基础设施：

- 写入：每个领域类型选择固定字段顺序（`toStorageString()` 中的 `FIELDS` 列表）；map 字段按键排序；RFC 8259 转义；仅 64-bit 整数；紧凑分隔符（`,` 后无空格）；空对象/数组输出 `{}`/`[]`；可选字段为 `null`。
- 解析：只接受上述子集；拒绝浮点数、前导零、超出 signed 64-bit 的整数、重复键、未转义控制字符、尾随内容；所有解码失败抛 `IllegalArgumentException`（调用方映射为 `HelixError`）。
- 边界：该编码器是 `internal` 的，不属于公开 API；本 ADR 不适用于 Provider/MCP wire DTO、导入导出格式或工具参数 canonical 哈希。工具参数 canonical 哈希（HXA-034）必须单独实现并单独评审，不得假设两者字节兼容。
- 具有双向编码的领域类型包括 `TurnBudgets`、`HelixError`、`ExecutionTargetDescriptor`、`ExecutionLimits`、`ToolExecutionEnvelope`。新增类型只有在具备已知向量、round-trip 和 malformed-input 测试后，才能把该 JSON 当作恢复来源。
- 仅使用 canonical writer 计算 hash 的类型不因此自动具备可恢复存储格式。例如 `PlanArtifact` 若没有领域 decoder，持久化层必须从规范化的 `plans`/`plan_steps` 列重建，或先补齐 decoder 与迁移测试，不能只保存一段无法恢复的 JSON。

## Alternatives considered

默认通用 JSON 不是确定的存储字节格式；每个类型重复手写解析会漂移；二进制格式增加当前不需要的 schema 工具成本。网络 DTO 仍使用适合其协议的序列化库，不扩展本编码器承担所有 JSON。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
