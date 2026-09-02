# Bug Fix: Denied approval cannot be consumed

Status: fixed
Date: 2026-08-31
Related HXA: HXA-014, HXA-034
Affected modules: `core:model`, `core:storage`, `core:policy`

## Problem

`ApprovalDao.consume` 曾使用 `decision IS NOT NULL` 作为消费条件，Repository 同时接受任意非空决定字符串。这使“决定已记录”与“用户已授权执行”在持久化原语中混为同一条件。

## Impact

缺陷发现时 Dispatcher 和可执行业务工具尚未落位，因此没有形成已知工具绕过；但存储原语本身会把 `DENIED` 行视为可消费决定，如果被后续 Dispatcher 直接复用将破坏每次 ToolCall 的用户授权边界。

## Root cause

早期 schema 只表达“pending 或已处理”，没有在类型和 SQL 中同时封闭决定值与可消费条件。`decision != null` 是 UI/审计处理状态，不是授权凭证。

## Fix and invariants

`ApprovalDecision` 封闭为 `APPROVED` 和 `DENIED`，Repository 不再接受自由字符串。DAO 的决定写入在 SQL 层拒绝封闭集合外的值，消费使用单条原子 UPDATE 要求 `decision = 'APPROVED' AND consumedAt IS NULL`。HXA-034 进一步将唯一授权路径收紧为未过期、binding hash 匹配的类型化 `ApprovalProof`；`DENIED` 只能记录和展示，永不能铸造或消费 Proof。

## Alternatives considered

**只在 Repository 里检查 `APPROVED`。** 放弃，因为直接 DAO 调用、竞态或未来重构仍可绕过应用层检查。

**等 HXA-034 Dispatcher 一起修。** 放弃，因为不安全的存储原语不应在基础层继续存在，并且当时修复不需要改变表字段。

**将所有非空决定视为“可消费的用户回答”。** 放弃，因为拒绝是审计事实，不是执行授权。

## Regression verification

- `RoomGuardAndConfigTablesTest` 覆盖任意字符串写入被拒绝、pending/denied/重复消费全部 fail closed。
- `ApprovalProofLifecycleTest` 在真实 Room/SQLite 上覆盖 pending、denied、过期、binding 不匹配和 8 线程并发消费恰一成功。
- 已记录验收命令：`./gradlew :core:policy:test :core:storage:testDebugUnitTest` 与 `./gradlew :core:storage:connectedDebugAndroidTest`。

## Residual risk

审批安全仍依赖所有执行入口只经 `ToolDispatcher`，不得新建直接根据 `decision` 列执行的旁路。当前 `ApprovalProof` 和 DAO 原子守卫已封闭已知消费路径。

## Related records

- [HXA-014 完成后安全加固](../completion-records/HXA-014.md#完成后安全加固2026-08-31)
- [HXA-034 Approval Proof 完成记录](../completion-records/HXA-034.md)
- [ADR-0005 Standard/Advanced 安全边界](../adr/0005-standard-advanced-safety-profiles.md)
