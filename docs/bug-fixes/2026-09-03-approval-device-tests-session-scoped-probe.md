# Bug Fix: 审批/审计设备测试依赖"打开的会话"的 UI 探针，跨测试类残留状态导致顺序相关的批量失败

Status: fixed
Date: 2026-09-03
Related HXA: HXA-036, HXA-053, HXA-048
Affected modules: `app`（androidTest 测试代码）

## Problem

`:app:connectedDeveloperDebugAndroidTest` 全量（API 29 + API 36 双设备并发）中，
3 个测试类共 **9 例**审批/审计设备测试全部失败，统一表象
`IllegalStateException: no pending card for <callId>`：

- `ApprovalFlowDeviceTest` ×3（B1/B2/B3，HXA-036 验收）
- `CodeJavascriptRunDeviceTest` ×5（HXA-053 验收）
- `AuditScreenTest` ×1（HXA-036 审计页验收）

单独运行任何一个类都通过（历史 HXA 验收均如此），只在**全量套件、特定类执行顺序**
下失败——即顺序相关的 flake。status.md 曾记录为"4 个 HXA-036 审批/审计测试因 seed
竞态失败"，本次全量复跑将实际范围修正为 9 例（跨 3 个类）。

## Impact

- 设备门禁可信度受损：developer 全量套件红，但每一例单独都绿——无法区分"真缺陷"
  与"顺序污染"，违背"不能把局部设备通过写成全量通过"的既定纪律。
- 不直接影响生产功能（纯测试基础设施缺陷）。

## Root cause

三类测试都用同一个探针取待审批卡的 approval id：轮询
`chatService.screen.value.toolTimeline` 里 callId 对应行的 `card`。
HXA-048（统一 ChatService 每会话 turn 模型）之后，`screen.toolTimeline`
**不再**是全局 live overlay：`refreshScreen` 按**打开的会话**重建合并视图
（`toolTimelineFor`：持久化行 + 仅属于打开会话的 live 行；live 卡片只附着在
打开会话的 turn 上）。

而测试自己 seed 的会话（`flow-session` / `js-session` / `audit-session`）
**并不是打开的会话**——App 进程在一次 instrumentation 运行内跨测试类存活，
之前跑过的其他测试类（compose 规则启动过 MainActivity、切换过会话）可能留下
任意"打开的会话"。打开会话 ≠ seed 会话时，live 卡片行被 `toolTimelineFor`
过滤掉，探针 15 s 超时报 "no pending card"。失败与否取决于类执行顺序与哪些类
打开过会话——单独运行（无残留打开会话，open session 为 null 时 live 行原样保留）
必然通过。

探针本身还有第二层脆弱：`refreshScreen` 是 work scope 异步刷新，UI 状态读取
天然滞后于 dispatch 线程的发布。

## Fix and invariants

三个测试类统一改为（不触碰生产代码）：

1. **建立自己的会话上下文**：`setUp` 中先持久化会话行、再
   `chatService.openSession(seedSessionId)`——时间线 scoping 从此对本类确定。
   （"先建行再打开"同时回避了
   [stale open session 杀进程](2026-09-03-stale-open-session-kills-app-process.md)
   的陷阱：open 一个尚不存在的 id 在修复前会杀死 App 进程。）
2. **审批 id 探针改读存储**：`approvalIdOf` 改为有界轮询
   `storage.approvals.byToolCall(callId)`——待审批决定的**事实源**
   （broker 在 `acquire` 第一步就创建 PENDING 记录，先于卡片发布，且与 UI 状态
   完全无关）。
3. 仍断言 UI 的两处（B1"卡片仍 live"、B2"重放行无卡片 + 稳定拒绝标签"）改用
   有界轮询的 `awaitTimelineRow` helper，容忍异步刷新的一 tick 滞后。

不变式：**设备测试断言聊天屏 UI 状态前必须自己建立会话上下文；待审批决定的
探针读存储事实源，不读屏**。

## Alternatives considered

- 保持读 `screen.value` 但无视会话 scoping：不可行——scoping 是 HXA-048 的产品
  行为（他会话的卡片不得出现在本会话），测试必须与产品一致。
- 在 UI 上真正切换会话再断言（操作会话列表节点）：可行但慢且脆（UI 竞态更多）；
  读存储探针直接命中被测事实，UI 断言只保留在真正验证 UI 的两处。
- 给每个测试类分配独立的 App 进程/数据清屏：改动面过大，且掩盖而非修复
  "跨类共享进程"这一真实运行形态。

## Regression verification

- 修复前基线（全量复跑，双设备）：API 36 9/71 失败、API 29 12/71 失败
  （多出的 3 例是 AllFiles 的独立缺陷，见
  [2026-09-03-api29-allfiles-device-test-missing-guard](2026-09-03-api29-allfiles-device-test-missing-guard.md)）。
- 修复后 `:app:connectedDeveloperDebugAndroidTest` 全量（双设备，**不清 App 数据、
  保留跨类残留打开会话的真实运行形态**）：9 例审批/审计测试全部通过，套件全绿
  （含新增 `ChatSessionLifecycleDeviceTest` ×2 与 `GoalReminderTest` ×2；
  完整数字见 status.md 设备矩阵条目）。

## Residual risk

- 未来新增"读聊天屏 UI 状态"的设备测试必须沿用同一模式（先建会话上下文 +
  决定类探针读存储）；若再出现 "no pending card for ..."，先检查测试类是否
  打开了自己 seed 的会话。

## Related records

- [2026-09-03-stale-open-session-kills-app-process](2026-09-03-stale-open-session-kills-app-process.md)
- [2026-09-03-api29-allfiles-device-test-missing-guard](2026-09-03-api29-allfiles-device-test-missing-guard.md)
