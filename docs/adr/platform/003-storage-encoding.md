# ADR-PLATFORM-003: 领域值的严格存储编码

Status: accepted
Date: 2026-09-16
HXA: HXA-010, HXA-303
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

固定 shape 的领域值需要字节确定、人类可读和严格失败的存储编码，不能把用于 hash 的字符串误当可恢复数据。

## Decision

在 `core/model/src/main/kotlin/com/helix/core/model/internal/Json.kt` 中提供 `internal` 可见的手写严格 canonical JSON 编码器/解析器，作为 `core:model` 中少量、固定 shape 领域值的存储编码基础设施：

- 写入：每个领域类型选择固定字段顺序（`toStorageString()` 中的 `FIELDS` 列表）；map 字段按键排序；RFC 8259 转义；仅 64-bit 整数；紧凑分隔符（`,` 后无空格）；空对象/数组输出 `{}`/`[]`；可选字段为 `null`。
- 解析：只接受上述子集；拒绝浮点数、前导零、超出 signed 64-bit 的整数、重复键、未转义控制字符、尾随内容；所有解码失败抛 `IllegalArgumentException`（调用方映射为 `HelixError`）。
- 边界：该编码器是 `internal` 的，不属于公开 API；本 ADR 不适用于 Provider/MCP wire DTO、导入导出格式或工具参数 canonical 哈希。工具参数 canonical 哈希（HXA-034）必须单独实现并单独评审，不得假设两者字节兼容。
- 具有双向编码的领域类型包括 `TurnBudgets`、`HelixError`、`ExecutionTargetDescriptor`、`ExecutionLimits`、`ToolExecutionEnvelope`。新增类型只有在具备已知向量、round-trip 和 malformed-input 测试后，才能把该 JSON 当作恢复来源。
- canonical writer 本身不是可恢复格式。`PlanArtifact` 已增加同域严格 decoder，并以开放 JSON 文件保存未批准正文；DB 只保存生命周期、内容身份和审批时的不可变证据。已批准执行读取当时快照，不追随文件修改。其他新增可恢复类型仍须补齐已知向量、round-trip 与 malformed-input 测试。

## Alternatives considered

默认通用 JSON 不是确定的存储字节格式；每个类型重复手写解析会漂移；二进制格式增加当前不需要的 schema 工具成本。网络 DTO 仍使用适合其协议的序列化库，不扩展本编码器承担所有 JSON。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## Decision history — 2026-10-17

[HXA-303](../../development/tasks/HXA-303.md) 将 TurnBudgets 规范存储收敛为 maxOutputTokens，GoalBudgets 收敛为 maxGoalRounds；删除旧内部字段与旧 shape decoder，不保默认迁移。会话配置的输出/续跑列与当前 Room v1 schema 同步变化，使不兼容旧预览库沿既有开发基线规则重建，而非留到某个任务恢复时才解析失败。数据库外文件不由此删除；配置偏好格式损坏/过时回落受限默认，合法当前显式值重载不自动抬高。同版本 Room 事务、原调用来源和恢复事实仍须验证；源码与 AndroidTest 编译不代替设备执行。

## Decision history — 2026-10-17 预览升级确认

[HXA-304](../../development/tasks/HXA-304.md) 明确：不兼容旧设计不授权静默删除现有用户数据。旧预览库在容器和恢复启动前只读检查，前台说明删除范围并明确确认后才重建；取消、重启或后台入口不授予确认。确认绑定已检查的数据库且只能消费一次，文件变化或普通读取/腐败错误拒绝自动重置。通过文件复核后、删除前先取消原数据库所属的 WorkManager 提醒及已发通知；取消与删除之间失败时不宣称回滚。运行默认值须落盘成功后才向 UI 发布已保存，失败不能冒充成功。当前版本正常重开，数据库外文件不由该操作删除；不增加旧 schema 迁移、兼容 decoder 或持久化的自动重置开关。HXA-303 所述既有自动重建路径由此替换。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history — 2026-10-26 文档优先

Owner 授权整体文档优先改造。知识、笔记与原件固定共享；Agent 记忆、会话、计划及批准事实仍私有。计划新增严格 `PlanArtifact.parse`，删除规范化正文列及 `plan_steps`：提案正文为私有开放 JSON，Room 保留索引、状态和批准快照，批准事务不因原文件后来编辑/删除而重新绑定。正文发布而 Room 事务失败可留下无执行资格的文件；不能宣称文件写入被 DB 回滚。当前内部 schema 仍为 1，不加旧测试数据迁移/兼容读取器，也不自动清数据。验证及未完成边界见[文档优先记录](../../development/document-first-storage.md)。
