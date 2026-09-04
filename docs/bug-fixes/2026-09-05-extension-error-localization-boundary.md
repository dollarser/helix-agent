# Bug Fix: 扩展执行器原始诊断越过国际化 UI 边界

Status: fixed
Date: 2026-09-05
Related HXA: HXA-069, HXA-072, HXA-079
Affected modules: `app`、`extensions:mcp`、`extensions:a2a`、`scripts/check-i18n.sh`

## Problem

M7 合入后，MCP/A2A Tool 的失败 detail 和来源进入了既有审批卡与 timeline。内部 detail
本应是稳定、语言无关的协议/审计数据，但拒绝和执行失败路径把它直接传给 Compose-facing
UI sink；来源映射也只区分 built-in/MCP，没有穷举新增的 A2A origin。原 HXA-069 门禁只
扫描 App/feature 的 CJK 字面量，不能覆盖 extension 源码或 raw diagnostic 流入 UI。

## Impact

- 用户切换中文或英文后，仍可能看到未国际化的远端/执行器错误。
- 原始 detail 可能携带不适合用户界面的实现诊断，破坏“协议/audit 稳定字段与 UI 文案
  分层”的既有边界。
- 新增 `ToolOrigin` 时若复用布尔判断，容易把 A2A 错标为内置或漏掉来源说明。

## Root cause

HXA-069 建立国际化门禁时，MCP/A2A production UI 尚未合入；扫描范围和映射接口都按当时
模块集合设计。后续分支各自通过测试，但合并点没有重新审计“新稳定诊断 → 既有 UI sink”
这条跨模块数据流，也没有要求来源映射对 sealed origin 穷举。

## Fix and invariants

- `ApprovalUiMapper.sourceLabel` 接受 `ToolOrigin` 并穷举 built-in、MCP、A2A；新增 origin
  若没有用户可见资源映射必须编译失败，而不是落入 generic 布尔分支。
- raw executor detail 继续用于持久化、audit 和模型/ToolResult 处理；到审批卡或 timeline
  前，按稳定错误码、origin 和 `requiresReview` 映射成 Android string resource。
- `check-i18n.sh` 扫描 `extensions/*/src/main`，并机械阻止 `outcome.detail` 直接进入
  `setCardStateForCall` 或 `publishToolRow`。
- Tool/schema 名、Agent/Server ID、URL、协议字段、audit type、稳定错误码和持久 enum
  不翻译；用户可见标签、失败摘要、无障碍描述和操作提示必须资源化。

## Alternatives considered

- 直接翻译或改写持久化 detail：会破坏审计、恢复和协议稳定性，不采用。
- 在 UI 显示“本地化摘要 + raw detail”：仍让内部诊断跨越用户界面边界，也可能泄漏远端
  内容，不采用。
- 只继续扫描 `app`：无法在扩展新增用户可见字面量时尽早失败，不采用。

## Regression verification

- `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest`，其中
  `ApprovalCardUiMapperTest.sourceAndProviderLabels` 覆盖三种 origin，
  `executionFailureLabelsNeverExposeExecutorDetail` 覆盖 generic/MCP/A2A/review 映射。
- `./scripts/check-i18n.sh` 验证三套资源 key parity、App/feature/extension 生产源码 CJK
  字面量边界，以及 raw `outcome.detail` 不进入两个 Compose-facing sink。
- `./gradlew lintConsumerDebug lintDeveloperDebug` 在 `extensions:a2a` 的 producer 模块
  应用 OkHttp JVM artifact substitution 后通过；对应模块 lockfile 已同步。

## Residual risk

当前 raw-detail sink 检查针对 `ChatService` 两个已知入口。未来新增 UI sink、错误 DTO 或
非 Compose 展示面时，必须同时扩展映射和门禁；仅通过资源 key parity 不能证明运行时没有
显示协议诊断。

## Related records

- [HXA-069 完成记录](../completion-records/HXA-069.md)
- [M7 合并与验证进展](../development/m7-non-device-progress.md)
- [Provider、MCP、A2A、Skills 和模式方案](../architecture/provider-mcp-skills-modes.md)
