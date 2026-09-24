# Helix 审查再评估：真问题 / 误报 / 待补充

**输入**：`reviews/2026-09-24/2026-09-24-code-review.md`（今晨，五维度）+ `REVIEW-2026-09-24.md`（本文档，四线并行）
**基线**：HEAD `3cf89027`，工作树含 8 个未提交修改
**方法**：对两份报告的**冲突项**与**高危项**逐条回源码复验（不依赖任一份报告的转述）；共复验 17 条，其中 3 条被降级、1 条裁决为"结论正确但作用域不完整"、13 条确认为真

---

## 一、核心裁决：两份报告的唯一实质分歧

### 分歧点：进程死亡后的恢复路径

| 来源 | 结论 |
| --- | --- |
| 我的报告 | **P0**：并行工具批次中进程死亡 → 恢复永久失效、不可自愈 |
| REVIEW 簇C | 「重启恢复幂等」「**未发现会造成永久 pending 的路径**」 |
| REVIEW 开头 | 却把「进程死亡恢复路径」列为与我的报告「**互相印证**」处 |

**裁决：我的 P0 成立；REVIEW 的结论正确但作用域不完整，且其表述会误导读者。**

两者审的是**不同层**，不构成真冲突：

- REVIEW 审的是**结算引擎**（`128eb33e`/`747291c5`/`77b423e6` 的原子事务、调度屏障、启动对账）——那部分确实幂等，我认同。
- 但它没有审**恢复入口的数据契约**——而这正是让引擎跑不起来的那一层。

**决定性证据链（我逐环复验）**：

```kotlin
// 环 1：数据契约硬断言"至多 1 个 RUNNING"
// core/agent/.../RecoveryCoordinator.kt:32-36
// Serial execution (first version, doc 02 section 5.3): at most one call is RUNNING
require(toolCalls.count { it.state == ToolCallState.RUNNING } <= 1) {
    "at most one RUNNING tool call per turn (serial execution)"
}
```

```kotlin
// 环 2：调度器早已并行（并发度 2）
// tools/framework/.../ToolScheduler.kt:345
const val DEFAULT_MAX_CONCURRENCY = 2
// app/.../chat/ChatToolCalls.kt:261 —— 批量入口确实走调度器
toolPipeline.scheduler.scheduleBatch(requests)
// tools/framework/.../ToolDispatcher.kt:742 —— 每个被准入的调用各自置 RUNNING
request.onExecutionStarting()
// app/.../chat/ChatToolCalls.kt:421-423
.copy(onExecutionStarting = {
    executionStartTimes[toolCallId] = clock.now().toEpochMilli()
    storage.toolCalls.updateState(row, ToolCallState.RUNNING)   // ← 无守卫
```

```kotlin
// 环 3：写入 RUNNING 无状态机守卫（这是关键——若此处有守卫，我的 P0 不成立）
// core/storage/.../repository/ToolCallRepository.kt:68-73
fun updateState(call: ToolCallEntity, state: ToolCallState) {
    dao.updateState(call.id, state.name)      // 裸写，无前态校验
}
```

```kotlin
// 环 4：恢复入口遍历所有非终态 turn，任一越界即抛
// app/.../recovery/RecoveryCoordinatorApp.kt:135-148
private fun scanPersistedTurns(): List<PersistedTurn> =
    storage.turns.listActive().map { turn ->          // SQL: state NOT IN (COMPLETED,FAILED,CANCELLED)
        PersistedTurn(..., toolCalls = ...)           // ← init 断言在此触发
    }
```

```kotlin
// 环 5：异常被吞，且吞掉的是整个恢复块
// app/.../HelixApplication.kt:73-85
try {
    recoveryCoordinator.recover()
    appContainer.chatService.onRecoveryCompleted()    // ← 一并跳过
    GoalReminderReconciler(...).reconcileAll()        // ← 一并跳过
} catch (t: Exception) {
    Log.e(TAG, "process recovery failed; will retry at next start", t)   // ← 但下次必然再失败
}
```

**后果**：Turn 永久停在 `RUNNING_TOOL`；两个 `tool_calls` 行永久 `RUNNING`；该会话修订被 `REVISION_SESSION_BUSY` 永久阻塞；**下次启动读到的还是那两行，必然再抛**——"will retry at next start" 是空承诺。且因 catch 包住整块，一个坏 turn 连带阻断**所有** turn 的恢复与 Goal 提醒对账。

**触发窗口**：模型一个响应里 ≥2 个可并行调用（两个非互斥只读，如两次 `file.read`），且进程在该窗口内被杀。窗口窄但真实，且**一旦命中即永久污染**。

**设计意图旁证**：`ToolCallState.canBecomeInterruptedOnProcessDeath()`（`core/model/.../ToolCallState.kt:44`）明确包含 `RUNNING`，说明"RUNNING 调用需被 park"是有意设计；但 `runningCallId` 只返回**第一个** RUNNING（`RecoveryCoordinator.kt:40-41`），数据结构本身无法表达"两个不确定调用"。这是 `TurnReducer` 串行时代遗留的契约，未随调度器并行化同步。

**建议的表述修订**：REVIEW 簇C 的「未发现会造成永久 pending 的路径」应限定为"结算引擎内部"；恢复链路的结论应以本条 P0 为准。

---

## 二、误报与需降级的项

### 2.1 需要降级的 REVIEW 结论（3 条）

#### 降级 1：quickjs deadline —— 真实缺陷，但**「100ms 全灭」不可达**，P0 → P1

REVIEW 把它列为 P0 首位：「timeoutMs 取下限 100ms 时…**每次执行稳定返回 REQUEST_REJECTED**，JS 根本没跑」。

复验结果：**结构缺陷成立，但该触发场景在生产不可达**。

```kotlin
// runtime/quickjs/.../tool/CodeJavascriptRunTool.kt:220-227 —— 生产唯一调用方
val limits = JsExecutionLimits.DEFAULTS          // ← 硬编码默认，永远 10s
val params = JsExecuteParams(
    executionId = UUID.randomUUID().toString(),
    source = code,
    inputJsonUtf8 = inputBytes,
    limits = limits,
)
```

```kotlin
// runtime/quickjs/.../JsExecutionLimits.kt:59-61
const val MIN_TIMEOUT_MS: Long = 100L
const val MAX_TIMEOUT_MS: Long = 30_000L
const val DEFAULT_TIMEOUT_MS: Long = 10_000L     // ← DEFAULTS 用的是这个
```

100ms 只存在于 `MIN_TIMEOUT_MS` 边界与测试用例（`JsExecutionLimitsTest.kt:58`），**没有任何生产调用方传入**。

**真实影响**：`deadlineNanos` 在 `prepareTransport`（bind）之前计算（`JsExecutionClient.kt:106` vs 之后的 bind），而 service 端 `JsServiceValidation.kt:20` 用同一 deadline 判过期。所以 bind 耗时（isolated service 冷启动，量级 100ms–2s）**从 10s 执行预算里扣掉**。JS 合法需要 9.5s 时会误报 TIMEOUT。

**修正后定级**：P1 / 中。修法同 REVIEW（deadline 移到 bind 之后，或 service 端用收到时刻重建 deadline），但不必按 P0 插队。

---

#### 降级 2：adblock 主框架拦截 —— 真实，但定「高」偏高，高 → 中

复验确认 `shouldInterceptRequest` **确实没有** `isForMainFrame()` 豁免（`WebViewTabHost.kt:167-180` 中 `isForMainFrame()` 属于 `onReceivedError` 回调，不是这里）：

```kotlin
// feature/browser/.../webview/WebViewTabHost.kt:167-180
override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
    val url = request.url.toString()
    if (adBlockEngine?.shouldBlock(url) == true) {          // ← 无主框架判断
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }
    return super.shouldInterceptRequest(view, request)
}
```

```kotlin
// feature/browser/.../engine/AdBlockEngine.kt:73-78,135-142
private fun hasBlockedPath(path: String): Boolean {
    for (pattern in BLOCKED_PATHS) { if (path.contains(pattern)) return true }
    return false
}
private val BLOCKED_PATHS = listOf("/pagead/", "/adservice/", "/adserver/", "/ads/banner/", "/mobads/")
```

**为何降级**：触发需主框架 URL 路径**恰好含这 5 个子串之一**。子资源命中是常态（这正是设计目的），主框架命中则相当罕见。后果（空白页、无错误页）确实严重，但概率低。

**定级**：中 / P1。修法同 REVIEW（主框架跳过路径规则，或路径规则限定已知广告域）。

---

#### 降级 3：browser「reload() 是 no-op」—— 描述不准，修法可以更简单

REVIEW 称「`reload()` 在重建的空 WebView 上是 no-op」。复验：**代码其实有类型化守卫，只是 UI 路径丢弃了结果**。

```kotlin
// feature/browser/.../BrowserController.kt:298-300
fun reloadOutcome(id: String): BrowserReloadResult {
    if (!isLive(id)) return BrowserReloadResult.NoTab
    if (hosts[id] == null) return BrowserReloadResult.NoChange("page-requires-navigation")   // ← 有结果
```

```kotlin
// feature/browser/.../BrowserController.kt:293-295 —— UI 入口
fun reload(id: String) {
    reloadOutcome(id)      // ← 返回值被丢弃，用户无任何反馈
}
```

而 **Agent 工具路径是消费该结果的**（`BrowserToolBridgeImpl.kt:129-132` 处理 `NoTab`/`NoChange`）。

**所以**：不是"静默 no-op"，而是"**UI 路径丢弃了已有的类型化结果**"。用户可见后果与 REVIEW 描述一致（死标签页），但修法比 REVIEW 建议的 `onSaveState`/`onRestoreState` 更轻：让 UI 路径消费 `page-requires-navigation`（提示 + 重新 navigate），或直接在 `reload` 时若 `hosts[id] == null` 且 `url != about:blank` 就重新 `navigate(url)`。`onSaveState` 是更完整的解，但可作为后续项。

**定级维持 高**（用户可见、触发常见：旋转/退后台/渲染进程崩溃）。

---

### 2.2 我自己的报告中需要修正的项

#### 修正 1：默认 locale = 中文 —— **误报，已撤回**

我在初版报告中列为 P1，后自行撤回。复核确认这是**有意设计**：`app/src/main/res/values/strings.xml:11-12` 明确注释 `HXA-069: base/primary locale is Simplified Chinese`，且由 `scripts/check-i18n.sh` 守护三份文件键集一致（实测 1358/1358/1358）。**结论：非缺陷。** REVIEW 未涉及此项，无冲突。

#### 修正 2：度量数值的小幅校正

| 指标 | 我初版报告 | 复验实测 | 处理 |
| --- | --- | --- | --- |
| 裸 `AlertDialog(` | 41 | **40** | 已按 40 计 |
| `CircularProgressIndicator` | 1 | **2** | 已按 2 计（结论"加载态严重不足"不变：2 个指示 vs 80+ IO 点） |
| `ChatService` 行数 | 4043 | **4047** | REVIEW 亦记 4047 |

结论方向均不受影响。

#### 修正 3：我的 P2-1（`ResourceKeyExtractor` 未装配）—— 维持 P2，但需重新表述

REVIEW 簇C 的补充核查证明**调度器逻辑本身正确**（`slotSignal` 捕获顺序、`releaseSlot` 锁内配对、queued-write 屏障语义均无问题）。所以这不是"调度器有 bug"，而是"**契约承诺（按归一化效果足迹判并发）只落地了一半**"——`DefaultAppContainer.kt:529-534` 构造 `ToolScheduler` 时未传 `resourceKeyExtractor`，默认 `NoResourceKeys`。**定级 P2 恰当，保持。**

---

## 三、确认为真的项（复验通过）

### 3.1 REVIEW 的结论中被我独立复验为真

| REVIEW 结论 | 复验证据 | 结果 |
| --- | --- | --- |
| **孤儿 GC 从未接线**（标"高"） | `PrivacyDeletionService.cleanOrphanFiles`（`:89`）与 `HelixStorage.collectGarbage`（`:225`）在**全仓生产代码零调用方**（Grep 全库仅定义处 + 测试） | ✅ **决定性确认**，是最典型的"实现存在+单测通过+端到端不存在" |
| **JS 输出 64KiB vs 契约 256KiB** | `PARCEL_INLINE_MAX_BYTES = 64 * 1024`（`JsProtocol.kt:44`）；`DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024`（`JsExecutionLimits.kt`）；`JsExecutionService.kt:380-387` 无 outputPfd 且超 64KiB 时 fail-closed；工具构造 `JsExecuteParams` **不传 outputFile**（`CodeJavascriptRunTool.kt:220-227` 实测无该参数） | ✅ 确认 |
| **工具耗时投影断链**（标"高"） | `ChatScreenProjection.toolTimelineFor` 构造 `ToolTimelineRow` **不设 durationMs**，live overlay 的 `row.copy(...)` **不携带 `live.durationMs`**；而 `ToolTimelineItem.kt:154` 读 `row.durationMs` | ✅ 确认 |
| **`isRunning` 靠解析本地化文案** | `ToolTimelineItem.kt:132-134`：`row.stateLabel.contains("...") \|\| contains("…") \|\| (resultSummary == null && card?.state != DENIED)` | ✅ 确认（且与我"UI 无显式状态字段"的结论同根） |
| **浏览器首页搜索点击是空 stub** | `BrowserScreen.kt:214`：`onSearchClick = { /* Focus Omnibox handled via urlText */ }` | ✅ 确认 |
| **`/help` 落入 else 分支** | `ComposerCommandParser.kt:78-79` 定义 `help` 命令（id 为 `slash:help`）；`ConversationComposer.kt` when 仅覆盖 plan/act/goal/compact/clear → `slash:help` 走 `else → applySuggestion` 把字面文本插入输入框 | ✅ 确认 |
| **slash 选择清空整份草稿** | `ConversationComposer.kt:82-105` 所有分支均 `onInput("")`（plan/act/goal/compact/clear 全如此） | ✅ 确认 |
| **Codex loopback 通配绑定** | `CodexLoopbackServer.kt:106` `ServerSocket(port, 4)`、`:116` `ServerSocket(0, 4)` —— 均未指定 bind 地址 | ✅ 确认（一行可修：加 `InetAddress.getLoopbackAddress()`） |

### 3.2 我的结论中被复验为真、且 REVIEW 未覆盖

| 我的结论 | 说明 |
| --- | --- |
| **P0 恢复永久失效** | 见第一节裁决 |
| **P1 `requireBatchSettled` 在事务前抛** | 与 REVIEW 2.1-#6 **独立重合**（REVIEW 标 中/PLAUSIBLE）。双报告确认 → 建议提升为 P1。证据：`TurnCoordinator.kt:125-129` 对 UNKNOWN 直接 `require` 抛；`:297` 调用点在 `storage.withTransaction`（`:300`）**之前** |
| **P1 斜杠 `/plan` `/act` 映射到 CHAT** | `ConversationComposer.kt:83-91` 两分支均 `onMode(AgentMode.CHAT)`（`slash:goal` 正确映射 GOAL）。**REVIEW 未发现此项**，互补 |
| **`TurnReducer` 生产零引用** | 全仓生产代码中 `TurnReducer` 仅出现在 KDoc；生产用 `TurnCoordinator.kt` 的 `BatchTurnRuntime`。**REVIEW 未提及** |
| **3 个死 Adapter 类** | `OpenAiChatAdapter`/`OpenAiResponsesAdapter`/`AnthropicAdapter` 各仅 1 处引用（即自身定义），生产 0 引用。**REVIEW 未提及** |
| **`:testing` 孤儿模块** | 全 `.kts` 中 `project(":testing")` 零命中。**REVIEW 未提及** |
| **UI 无设计系统** | 实测：硬编码 dp 304 处、裸 AlertDialog 40 处、`CircularProgressIndicator` 2 处、硬编码颜色 **0** 处。**REVIEW 未覆盖此维度** |
| **文件管理器 → 会话断链** | `stageAttachment` 生产调用点仅 `ChatScreen.kt:401`（composer 选择器）；`app/.../files/` 下零命中。**REVIEW 未提及** |
| **文档 roadmap 三处状态矛盾** | REVIEW 行动清单第 24 项已采纳（"roadmap 状态表（与 reviews/ 报告 P1 合并）"）→ **被 REVIEW 认可** |
| **`ChatService` 4047 行** | 双报告确认；REVIEW 补充了恶化轨迹（2876 → 4047） |

---

## 四、仍需补充的（两份报告合起来仍存在的盲区）

REVIEW 已诚实标注了部分覆盖不足，我在此汇总并补充它未意识到的盲区：

### 4.1 REVIEW 自述的覆盖不足（我未补齐，建议排期）

1. **proot 日志流**（`JobLogSpool` / `LogStream` 客户端）—— 因代理 API 400 中断
2. **`tools/automation` 全文** —— 同上（注：该模块 27 文件，非空壳）
3. **`extensions/a2a` 全文** —— 同上（注：a2a 涉及远端不可信输出，值得单独一轮）

### 4.2 两份报告共同的盲区（新增）

4. **两份报告都是纯静态审查，均未运行 gradle / 模拟器 / 真机**。所有"高"级结论（含我的 P0）都是代码路径推演，**没有一条有运行时复现证据**。我的 P0 置信度高（五环证据链完整），但"进程在 2 个 RUNNING 窗口被杀"这一步仍是推演。**建议**：为 P0 写一条 androidTest——注入两次并发只读调用 + 在 `onExecutionStarting` 第二次触发后强杀进程，重启断言恢复成功。这条测试同时能验证 REVIEW 簇C 的对账逻辑。

5. **性能结论全部无实测基线**。REVIEW §4.2 列了 `ContentStore` 读时全量 SHA-256、`toolTimelineFor` N+1、`BudgetContinuation` O(T²) 三处，我补充了"2 个进度指示 vs 80+ IO 点"。但**没有任何一方提供 profiler 数据、帧时间、DB 往返计数**。建议以 `ChatScreenProjection` 与 `BudgetContinuation` 为目标跑一次 Macrobenchmark，再决定优化优先级——否则这些条目会像 §4.1/§4.2 一样跨两个审查周期持续"仍存在"。

6. **`scripts/` 904 个文件只做了卫生统计，未做正确性审查**。其中有 106 个 `.sh` 验收脚本（`accept-hxa-*.sh`）是项目自己的门禁与验收证据来源。若这些脚本本身有缺陷（例如断言不覆盖关键路径），会系统性地放大"测试通过但功能不存在"的问题——而两份报告都识别出这是本项目最突出的失效模式（孤儿 GC、工具耗时投影、quickjs 输出上限三条同源）。**建议**：抽查 5–10 个最近使用的 `accept-hxa-*.sh`，确认它们真的触达生产路径而非仅调用单元函数。

7. **我的报告未深入 `extensions/mcp` / `extensions/skills` 实现细节**（REVIEW 覆盖了 MCP 超时与 OAuth，但 skills 仅覆盖了 `sessionOverrides` 无界增长一条）。`extensions/skills` 14 个源文件 + `extensions/mcp` 21 个，值得一轮专项。

8. **数据迁移的长尾**：`core/storage` 有 28 版 Room 迁移与 109 个源文件。REVIEW 提到 `MIGRATION_22_23` 的 `require` 不可达（我亦独立确认），但**从 v1 到 v28 的全链路迁移未做端到端验证**（REVIEW 的 `RoomMigrationFixtureTest` 覆盖是分段的）。建议补一条"v1 库直接升到 v28"的夹具测试。

---

## 五、修订后的行动清单（合并去重）

### P0（用户可见功能失效 / 不可自愈）

1. **修复进程死亡恢复的并行假设**（我的报告 P0，本轮裁决确认）
   - `PersistedTurn` 改 `runningCallIds: List<ToolCallId>`，或删除该 `require`
   - `TurnRecovery.Interrupt.uncertainToolCall` 改为集合；`runningCallId` 单值 getter 同步改
   - `scanPersistedTurns` 遇异常数据应降级为"标记待核查"，不得抛
   - **拆分 `HelixApplication.kt:73-85` 的 try 块**，让单 turn 失败不阻断全局恢复与 Goal 对账
   - 恢复失败必须可见（上报/阻塞 UI），不能只写日志
   - 配一条 androidTest：并发只读 ×2 + 执行中强杀 + 重启断言恢复

2. **孤儿 GC 接线或明确移除**（REVIEW，复验决定性确认）
   `PrivacyDeletionService.cleanOrphanFiles` 与 `HelixStorage.collectGarbage` 生产零调用方。要么接线（例如会话关闭/低存储时触发），要么删除并撤回提交记录中的"已完成"表述。

3. **工具耗时投影断链**（REVIEW，复验确认）
   `ChatScreenProjection` 构造 `ToolTimelineRow` 与 `row.copy` 均未携带 `durationMs`。最小修复一行：`row.copy(..., durationMs = live.durationMs ?: row.durationMs)`；彻底修复则把 duration 落到 `tool_calls` 持久化。

4. **browser 页面丢失后不可恢复**（REVIEW，复验确认并修正修法）
   `BrowserController.reload()`（`:293`）丢弃了 `reloadOutcome` 的类型化结果。最小修复：UI 路径消费 `NoChange("page-requires-navigation")` 或直接重新 `navigate(url)`。

5. **`regenerateLatestTurn` 的 `workScope.launch` 无守护**（REVIEW）
   `goalRuns.resolve` 对悬挂 binding 抛 `IllegalArgumentException`，`SupervisorJob` scope 无 handler → 进程崩溃。补 try/catch。

### P1（正确性 / 可靠性）

6. **`requireBatchSettled()` 语义拆分**（我的 P1 + REVIEW 2.1-#6，**双报告确认**）
   允许 UNKNOWN、禁止 PENDING；UNKNOWN 存在时照常落库已完成结果并进 NEEDS_REVIEW 终态，而非整轮 FAILED/INTERNAL。
   同时修 REVIEW 指出的直接路径分类不一致（`ChatToolCalls.kt:551-552` 与 `:313` 的 `requiresSettlementReview` 不统一）。

7. **斜杠 `/plan` `/act` 映射修正 + `/help` 分支**（我的 + REVIEW，互补）
   `ConversationComposer.kt:83-91` 改为 `AgentMode.PLAN`/`AgentMode.ACT`；补 `slash:help` 分支；slash 选择改为只替换命令行而非清空整份草稿。

8. **quickjs deadline 起算点**（REVIEW，**降级 P0 → P1**）
   deadline 移到 bind 成功之后，或 service 端以收到时刻重建。**不按 P0 插队**——生产调用方固定 10s，影响是预算被 bind 侵蚀，非"全灭"。

9. **JS 输出契约对齐**（REVIEW，复验确认）
   工具传入 app 私有临时文件作 `outputFile`（成功后读回、finally 删除），或把契约上限改为 64KiB。

10. **adblock 主框架豁免**（REVIEW，**降级 高 → 中**）
    `shouldInterceptRequest` 对主框架跳过路径规则，或路径规则限定已知广告域。

11. **proot 异常路径 kill 进程组 + 终端容量只计活会话**（REVIEW #3/#4，未独立复验）

12. **MCP `testConnection` 加 callTimeout + withTimeout**（REVIEW #5，未独立复验）

13. **消息分享 try/catch + 去思考标签 + 截断保护**（REVIEW 2.3-B-5）

14. **`e.message` 泄漏收口**（REVIEW 上次遗留 B3，我未复验但方向合理）

15. **安全三件套**（REVIEW，其中 loopback 绑定我已复验确认）
    `CodexLoopbackServer.kt:106,116` 显式绑回环（一行）；下载双重扩展名检查；`file_paths.xml` 的 `all-files` 收敛。

16. **browser 信任面**：用户脚本加警示 + 默认禁用 + KDoc 更正；eruda 固定版本或本地化（REVIEW）

17. **manifest 两处 ADR 合规**：`recordRequestManifest` 加 try/catch 隔离；压缩路径改用真实 `messageRefs`（REVIEW）

### P2（性能 / 维护 / 卫生）

18. **`ChatService` 拆分**（双报告确认，4047 行 / 81 公开方法 / 11 个 MutableStateFlow）
    按我报告给出的 9 组件方案：先抽 `ChatStateHolder` + `TurnLauncher` + `SubmissionAdmission`。

19. **性能项需先建基线再优化**（见盲区 5）
    `ContentStore` 读时哈希 / `toolTimelineFor` N+1 / `BudgetContinuation` O(T²) / 会话内搜索主线程扫描——先 Macrobenchmark，再排序。

20. **停止 `:app` 继续膨胀 + 修反向依赖**（我的报告）
    把 `ToolDescriptor`/`SessionPermissionConfig` 上提 `core:model`，切断 `core:storage→core:policy`、`runtime:quickjs→tools:framework`、`feature:browser→tools:browser` 三条边。

21. **决定 `TurnReducer` 去留**（我的报告，与 P0-1 同根）
    合并语义或删除 + 清理约 2000 行绑定在非生产实现上的测试。

22. **UI 设计系统**（我的报告）
    建 `Spacing`/`Shapes` 令牌，收敛 304 处 dp 与 40 处 AlertDialog；补全链路加载态。

23. **一行级清理**
    `environment.md:389` 的 `../`、`CodexPayloadJob.MAX_PAYLOAD_BYTES` 死常量、`desktopMode` 死偏好、3 个死 Adapter、`:testing` 孤儿模块、`McpOAuthPkce.kt:57` 死 typealias、KSP 2.3.11 → 匹配 kotlin 2.3.21。

24. **文档补齐**
    `roadmap.md:5,19,24` 三处状态矛盾（我的 P1，REVIEW 已采纳）；regenerate/GC 语义；结算原子事务；启动对账；browser 模块树登记；composer/terminal destination 登记。

---

## 六、对两份报告的方法论评估

**REVIEW-2026-09-24.md 的强项**：覆盖面广（4 条并行线 + 安全专项 6 个信任边界），对"静默失效"型问题的识别很准（孤儿 GC、工具耗时投影、quickjs 输出上限三条同源），并诚实地标注了代理中断导致的覆盖不足与"核实后剔除的候选"（防止把设计决策误报为 bug——这个做法值得保留）。

**REVIEW 的弱点**：对已发现的缺陷做定级时，**未回源确认触发场景在生产是否可达**。三条降级项（quickjs 100ms、adblock 主框架、reload no-op）都源于此：结构缺陷看对了，但严重度是按"最坏理论情形"而非"生产可达情形"给的。建议定级时加一问：**"生产调用方会传入这个参数吗？"**

**我的报告的强项**：对单点做了逐环证据链（P0 的五环），并对 UI 一致性给了可量化基线（304 dp / 40 AlertDialog / 2 个进度指示 / 0 硬编码颜色）。

**我的报告的弱点**：覆盖面窄得多（未触及 runtime 各模块、安全面、近两周新功能），且**同样没有运行时验证**。

**两份报告共同的失效模式**：都在识别"测试通过但端到端不成立"这一本项目的核心风险，但**都没有审查产生"通过"结论的那些验收脚本本身**（见盲区 6）。这是最值得补的一轮。

### 盲区 6 已由补充审查回答

`2026-09-24-supplement-verification-and-release.md` §1 给出了机制性答案：**CI 从不执行任何设备测试**（`check-all.sh:22` 只跑 `./gradlew test`；`grep connectedAndroidTest|managedDevices` 在 `scripts/` 与 `.github/` 零命中），而 `:app` 的 androidTest 文件数（194）是 unit test（114）的 1.7 倍。设备测试是**一次性证据生成器，不是回归门禁**——这正是"测试通过但端到端不成立"的成因。

**另需修正本报告 §六的一处数量陈述**：我写的「`scripts/` 里 106 个 `accept-hxa-*.sh`」是错的——106 是 `scripts/` 下 `.sh` 文件总数，`accept-hxa-*.sh` 实际为 **10 个**（`check-*.sh` 为 12 个）。问题性质不变，量级差一个数量级。详见补充审查 §1.3。

---

*本再评估对 17 条结论做了回源码复验，其中 3 条降级、1 条裁决为"作用域不完整"、13 条确认为真。未复验的项已明确标注。审查过程未修改任何项目文件。*
