# Helix 2026-09-24 审查复核与独立代码审查

> 复核基线：`main @ 3cf89027`，工作树存在未提交修改。  
> 范围：`reviews/2026-09-24/` 全部审查材料 + 当前源码/CI/状态文档交叉核验。  
> 方法：静态源码复核、Git 现场核对、现有测试/验收文档核对；本轮未重新执行 Gradle、模拟器或真机测试，因此涉及运行时触发概率的结论会明确区分“代码确定”与“需动态复现”。

---

## 核心总结

第一，`reviews/2026-09-24/` **整体审查质量是比较高的**，尤其是 `2026-09-24-review-reevaluation.md`，它已经主动纠正了前面几份报告中的一些误报和过度定级。但不能直接把所有问题照单全收。



我重新核验后，最值得优先处理的是这些：



- **并行 Tool Batch 与进程死亡恢复契约冲突**：这是当前最严重的问题。`RecoveryCoordinator` 仍硬编码“一个 Turn 最多一个 RUNNING call”，但生产 ToolScheduler 已允许并行执行。这个 P0 判断我认为成立。
- **Root 文件读取存在无界 `readAllBytes`**：而且就在当前未提交的 Root 文件功能中，建议合并前修掉。
- **`requireBatchSettled()` 错误拒绝 UNKNOWN**：UNKNOWN 本来是合法的“副作用不确定”状态，却被当成非法状态直接 `require`。
- **QuickJS 宣称支持 256 KiB 输出，但实际工具链超过 64 KiB 就可能失败**：ToolDescriptor 与 IPC transport 契约不一致。
- **Codex OAuth callback ServerSocket 没有限定 loopback**，当前代码使用 `ServerSocket(port, 4)`，建议显式绑定 `127.0.0.1/::1`。
- **Regenerate 先 supersede 历史，再启动新 Turn**，启动失败没有回滚，有用户历史暂时“消失”的风险。
- Browser reload、Tool duration、Tool Timeline 通过本地化字符串推断状态等问题也确认存在。



同时，我认为现有报告里有几项需要明显降级：

> `:app` 过大、`ChatService` 4047 行、`ui/` 目录平铺都是真实技术债，但**不应该定成 P0 生产故障**。

它们更适合作为 P1/P2 架构治理。



另外早期报告里所谓三条“反向依赖”：

```text
core:storage -> core:policy
runtime:quickjs -> tools:framework
feature:browser -> tools:browser
```

这个判断基本应该撤回。后两者尤其明显属于 **implementation → port/contract** 的正常 Ports & Adapters 依赖方式，并不是架构反转。



还有一个值得注意的地方是 **CI**。补充审查说“CI 从不跑设备测试”，这个事实我重新核对后成立：当前 `.github/workflows/ci.yml` 没有 emulator、`connectedAndroidTest` 或 managed device job。



但不能进一步推导成“项目缺乏设备测试”。当前 `status.md` 已经记录了 2026-09-24 OnePlus 6T/API34 的 184 项物理设备验收以及大量历史模拟器矩阵。所以更准确的说法应该是：

> **设备验证很多，但没有成为远端 CI 的持续自动门禁。**

我还发现了一个比较明确的文档一致性问题：当前 `status.md` 已经明确 HXA-196/198/199/206/217/218/219 等完成，但 `roadmap.md` 顶部仍保留“217 仅完成成本准备”“129 尚未合并 main”等旧叙事，而下面任务表又标成了已交付。现有审查对此判断是正确的。



如果按现在最值得投入的顺序，我建议下一轮不是继续加新功能，而是做一次 **Cross-layer Contract Convergence**：

```text
1. Process recovery × parallel tools
2. Batch UNKNOWN settlement
3. Root file bounded streaming
4. QuickJS descriptor × transport limits
5. OAuth loopback security boundary
6. UI typed state × projection
7. status × roadmap
8. 最小 device CI smoke
```

这些处理完，再做 `ChatService` 拆分、UI 分包和 release 工程，会比直接大规模重构稳得多。



## 1. 总结结论

`reviews/2026-09-24/` 的整体审查方向是合理的，尤其是后续的 `2026-09-24-review-reevaluation.md` 对早期报告做了有效纠偏。它们真正有价值的地方不在“问题数量多”，而在于识别出 Helix 当前最危险的一类失效模式：

> **局部实现、单元测试或历史验收是正确的，但生产组合路径存在跨层契约漂移，导致端到端行为与原设计不一致。**

当前最典型的例子是：

- `RecoveryCoordinator` 仍假设每个 Turn 最多一个 `RUNNING` 工具调用；
- 生产 `ToolScheduler` 已支持并行批次；
- 结果是“并行执行能力”和“进程死亡恢复数据契约”发生了版本漂移。

这类问题比单纯的大类、目录平铺、重复代码更值得优先修。

不过，现有审查也存在三类需要纠正的地方：

1. **严重度定级偏重**：`:app` 过大、`ChatService` 过大、UI 平铺等属于高维护成本问题，但不宜直接标为 P0 生产故障。
2. **架构方向误判**：把 `runtime:quickjs -> tools:framework`、`feature:browser -> tools:browser` 等依赖称为“反向依赖”并不准确，它们符合 ports & adapters 中“实现依赖契约”的常见方向。
3. **验证体系表述过度**：CI 当前确实不跑设备测试，但仓库已有大量模拟器/真机离线验收证据，因此准确表述应该是“CI 自动化缺少设备门禁”，而不是“项目没有设备验证”。

综合判断：现有审查材料可作为高质量参考，但**不建议直接照单实施**。应以本报告的“确认 / 降级 / 撤回 / 待动态验证”分类作为下一轮修复输入。

---

## 2. 对现有审查材料的可信度评价

| 文档 | 评价 | 建议用途 |
| --- | --- | --- |
| `REVIEW-2026-09-24.md` | 覆盖广、发现多，但早期定级有偏重，部分结论作用域不完整 | 作为问题池 |
| `2026-09-24-code-review.md` | 架构和 UI 审查较系统，但 F3“反向依赖”存在误判，F1/F2 P0 定级偏重 | 作为结构治理参考 |
| `2026-09-24-review-reevaluation.md` | 当前最可靠的一份；主动撤回误报并交叉复验高危项 | 建议作为主索引 |
| `2026-09-24-structure-review.md` | 对模块、目录和类级耦合的判断总体合理，且主动修正了 F3 | 作为中长期重构依据 |
| `2026-09-24-supplement-verification-and-release.md` | 对 CI、release、静态门禁盲区的补充有价值 | 作为发布前清单 |
| `independent-review/*` | 细节丰富，特别是核心 bug 和性能项；但部分条目仍需运行时证据 | 作为逐项验证来源 |

---

## 3. 本轮确认的最高优先级问题

### P0-1：并行 Tool Batch 与进程死亡恢复契约不兼容

**状态：确认存在；建议 P0。**

关键证据：

- `core/agent/.../RecoveryCoordinator.kt:32-36`
  - `PersistedTurn` 硬性要求 `RUNNING` 工具调用数量 `<= 1`。
- `tools/framework/.../ToolScheduler.kt`
  - 默认允许并发执行多个工具。
- `app/.../chat/ChatToolCalls.kt:261`
  - 生产批量调用进入 `scheduleBatch(...)`。
- `app/.../recovery/RecoveryCoordinatorApp.kt:135+`
  - 启动恢复会将活跃 Turn 直接构造成 `PersistedTurn`。
- `app/.../HelixApplication.kt:70-84`
  - 恢复异常被最外层统一 catch，且 `onRecoveryCompleted()`、Goal reminder reconcile 与恢复共用同一 try 块。

因此只要进程恰好在两个并行工具均已进入 `RUNNING` 后死亡，下一次启动就可能在构建 `PersistedTurn` 时抛异常；持久状态没有被修复，再启动仍会读到相同状态。

**影响：**

- Turn 可能永久停在非终态；
- 对应会话可能持续被“忙”状态阻塞；
- 一个坏 Turn 还可能阻断整个恢复阶段后续的 Goal 对账。

**建议修复：**

- 移除“最多一个 RUNNING”的恢复数据契约；
- 将 `uncertainToolCall` 改为集合语义；
- 对所有 `PENDING/RUNNING` call 分别 park；
- `scanPersistedTurns` 对单个异常 Turn 做隔离处理，不应炸掉全局恢复；
- 将 `HelixApplication` 中 recovery / chat recovery-complete / goal reconcile 拆成独立 failure boundary；
- 新增设备测试：同一 Turn 两个并发只读工具 -> 第二个进入 RUNNING 后 SIGKILL -> 重启 -> 两个调用均被安全 park，Turn 可恢复。

现有 `ProcessRecoveryTest` 的 RUNNING 场景仍以单调用为主，不能覆盖这个组合态。

---

### P0-2：Root 文件后端存在无界整文件读入内存

**状态：确认；而且位于当前未提交工作树，建议在合并前阻断。**

`tools/root/.../RootFileAccessor.kt` 当前同时存在：

- `readBytes(targetPath, maxBytes)`：有界；
- `readAllBytes(targetPath)`：通过 `base64` 将整个文件拉入 JVM 内存。

当前 developer wiring 中已有生产调用：

- `app/src/developer/.../RootFileModule.kt` 调用 `RootFileAccessor.readAllBytes(...)`。

这会把 Root 文件读取从“流式/有界”退化为“shell 输出 + Base64 字符串 + decode ByteArray”的多份内存峰值模型。面对大文件时可能造成明显内存压力甚至进程 OOM。

**建议：**

- 不允许生产路径调用 `readAllBytes`；
- 改为 PFD/stream 或 chunked copy；
- 上层必须先 stat 并应用最大大小；
- 为 64 MiB / 256 MiB 等大文件加入拒绝或流式传输测试；
- 当前这组 Root 工作树改动在修复前不建议提交。

---

## 4. P1：确认存在的正确性 / 安全 / 用户可见问题

### P1-1：`requireBatchSettled()` 把 UNKNOWN 当作非法状态

`app/.../agent/TurnCoordinator.kt:125-129`：

```kotlin
require(batchCalls.values.none { it == BatchCallResolution.UNKNOWN })
```

而 UNKNOWN 本身是系统为“副作用不确定、需要人工核查”设计的合法状态。

当前 `openNextModelCall()` 在进入事务之前调用 `runtime.requireBatchSettled()`。因此一个合法 UNKNOWN 会直接抛异常，而不是进入明确的 `NEEDS_REVIEW` / 用户核查路径。

**建议：**

- `PENDING` 才应阻止结算；
- `UNKNOWN` 应作为合法终态输入进入 review/recovery 语义；
- 已完成的其他 tool result 仍应持久化；
- 为“同批次：一个 SETTLED + 一个 UNKNOWN”添加单测和进程恢复回归。

---

### P1-2：QuickJS 工具声明 256 KiB 输出，但生产链路实际上 64 KiB 以上会失败

证据：

- `CodeJavascriptRunTool` 描述：固定 `256 KiB output limit`；
- `JsExecutionLimits.DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024`；
- `JsProtocol.PARCEL_INLINE_MAX_BYTES = 64 * 1024`；
- `JsExecutionService.deliverResult()`：无 `outputPfd` 时，超过 64 KiB 直接 fail；
- `CodeJavascriptRunTool.executeOnce()` 创建 `JsExecuteParams` 时没有提供 `outputFile`。

因此工具对模型/用户宣称的能力与实际能力不一致。

**修复方案二选一：**

1. 工具层真正配置临时 output file / PFD，使 64–256 KiB 按设计走文件通道；
2. 若不准备支持，则把 ToolDescriptor 契约明确降为 64 KiB。

推荐方案 1。

---

### P1-3：QuickJS execution deadline 从 bind 前开始计时

`JsExecutionClient.execute()` 在 `prepareTransport()` 和 `bindInstance()` 之前就计算：

```kotlin
val deadlineNanos = System.nanoTime() + limits.timeoutMs * ...
```

因此 isolated service 冷启动时间会侵蚀 JS 自己的执行预算。

现有报告早期把“100ms 时必失败”定为 P0 是过度描述，因为生产调用固定使用 10s 默认值；但结构问题本身成立。

**建议：** bind 成功后再开始执行 deadline，或拆成“bind deadline + execution deadline”。

---

### P1-4：Codex OAuth loopback server 默认绑定所有接口

`runtime/cli-app/.../CodexLoopbackServer.kt`：

```kotlin
ServerSocket(port, 4)
ServerSocket(0, 4)
```

未显式绑定 `InetAddress.getLoopbackAddress()`。

对于 OAuth callback server，期望的边界应是 localhost。当前实现可能监听所有本地接口。

**建议：**

```kotlin
ServerSocket(port, backlog, InetAddress.getLoopbackAddress())
```

ephemeral 分支也同样处理，并补测试断言绑定地址为 loopback。

---

### P1-5：Regenerate 先 supersede 历史，再启动新的 Turn，失败时没有回滚

`ChatService.regenerateLatestTurn()` 当前顺序：

1. attachment/provider 检查；
2. `storage.messages.supersedeFrom(...)`；
3. `refreshScreen()`；
4. `submitTurn(...)`。

如果第 4 步因为竞态、准入、运行时异常等没有真正启动新 Turn，旧回答已经被隐藏。

这属于用户可见的数据呈现一致性问题。

**建议：**

- 将 supersede 与新 Turn 成功创建放进一个可恢复事务协议；
- 或先创建新 Turn，再在 durable admission 成功后 supersede；
- 至少对 submit 失败提供 rollback。

---

### P1-6：Tool Timeline 状态仍通过本地化字符串推断

`ToolTimelineItem.kt:132-134`：

```kotlin
row.stateLabel.contains("...") ||
row.stateLabel.contains("…") ||
(...)
```

业务状态不应从 UI 文案反向解析。

这会导致：

- 文案/语言变化改变行为；
- 状态展示与逻辑耦合；
- 新状态容易误分类。

**建议：** `ToolTimelineRow` 直接携带 typed `ToolCallState` / `isRunning`，`stateLabel` 仅用于渲染。

---

### P1-7：Tool duration 投影断链

`ChatScreenProjection.toolTimelineFor()`：

- 从持久化行创建 `ToolTimelineRow` 时没有 duration；
- live overlay 的 `row.copy(...)` 也没有拷贝 `live.durationMs`；
- UI 却明确读取 `row.durationMs`。

因此 UI 具备展示能力，但主投影没有完整传值。

建议最小修复先让 live overlay 保留 duration；长期则明确 duration 是否应持久化。

---

### P1-8：Browser reload 对“宿主已丢失”的 tab 没有 UI 恢复动作

`BrowserController.reloadOutcome()` 已经正确返回：

```text
NoChange("page-requires-navigation")
```

所以“reload 本身是 no-op”这个原始描述不精确。

真正的问题是 UI `reload(id)` 丢弃了这个结果，用户看不到错误，也不会重新 navigate。

**建议：**

- UI 消费 `BrowserReloadResult`；
- 对 `page-requires-navigation` 重新加载 tab 当前 URL；
- 如果目标是完整恢复，再考虑 WebView save/restore state。

---

## 5. P2：真实但不应与生产故障混排的问题

### P2-1：孤儿 GC 有实现但没有生产触发入口

`PrivacyDeletionService.cleanOrphanFiles()` 和 `HelixStorage.collectGarbage()` 存在，但生产调用方缺失。

这是典型的“局部功能已实现，产品闭环未接线”。

建议：

- 在安全时机接入低优先级 GC；
- 或明确删除未使用能力和“已闭环”表述。

严重度建议 **P2 / 维护与磁盘卫生**；只有已有证据表明长期运行会显著泄漏空间时再提升。

---

### P2-2：`ChatService` 已明显过大，应继续拆分

当前约 4k 行，横跨：

- session；
- drafts；
- attachment；
- goal；
- provider；
- turn launch；
- retry/regenerate；
- UI state；
- egress；
- recovery。

现有报告把它直接定为 P0 不合适。它不是“立即导致生产不可用”的问题，而是：

- 修改爆炸半径过大；
- 状态所有权难验证；
- 新 bug 容易出现在跨职责交界处；
- 单元测试隔离困难。

**建议定级：P1 架构治理 / P2 功能排期。**

不要一次性“大爆炸重写”；按已有 collaborator 边界逐步抽离。

---

### P2-3：`:app/ui` 平铺严重

100+ 文件集中在单层目录，建议按功能域迁移：

- `ui/conversation`
- `ui/files`
- `ui/provider`
- `ui/settings`
- `ui/tasks`
- `ui/audit`

这是可维护性问题，不是生产 P0。

---

### P2-4：`DefaultAppContainer` 手工 DI 规模继续增长

当前主要风险不是“没用 Hilt/Koin”，而是一个组合根承担了过多 domain wiring 和可变 holder。

建议优先拆小型 domain container/factory，而不是为了框架而框架化。

---

### P2-5：TurnReducer 与生产 BatchTurnRuntime 概念分叉

`core/agent/TurnReducer` 在当前生产主链路中并未承担实际 Batch Turn 状态推进，而生产使用 `TurnCoordinator` / `BatchTurnRuntime`。

这件事本身不一定要求“删除 TurnReducer”，但必须解决：

- 文档是否仍把它描述为生产权威状态机；
- 测试是否错误地把 reducer 绿灯当成生产行为证明；
- 串行时代不变量是否继续泄漏到生产契约（当前恢复 P0 已证明存在这种风险）。

建议把“参考 reducer / 生产 coordinator”的地位写清楚，并逐个迁移共享不变量。

---

## 6. 现有审查中应撤回或降级的结论

### 6.1 “三条反向依赖”应撤回

早期 `2026-09-24-code-review.md` 的 F3 把以下依赖定为架构反转：

- `core:storage -> core:policy`
- `runtime:quickjs -> tools:framework`
- `feature:browser -> tools:browser`

后续结构复核已经正确指出：

- storage 持久化 policy 的领域值对象，并不天然构成反转；
- runtime/feature 实现 tools 中定义的 port，本身符合依赖契约的方式。

应从“需要修正的架构违规”改为“需要保持边界清晰并避免契约泄漏”。

---

### 6.2 “`:app` 是 P0 单体”应降级

`:app` 体积过大是真问题，但 P0 通常应留给：

- 数据丢失；
- 安全边界破坏；
- 主流程不可恢复；
- 高概率崩溃；
- 发布阻断。

模块体积更适合 P1/P2 技术债治理。

---

### 6.3 “CI 不跑设备测试 = 验证体系 P0”应重新表述

当前 `.github/workflows/ci.yml` 确实没有 emulator / `connectedAndroidTest` / managed device job。

但 `docs/development/status.md` 已记录 9 月 24 日 OnePlus 6T/API34 物理设备 184 项验收，以及历史双 API 模拟器矩阵。

因此准确结论是：

> **设备验证存在，但不是远端 CI 的持续自动门禁。**

建议级别：P1 测试体系 / 发布可靠性。

---

## 7. 文档一致性问题

### 7.1 `roadmap.md` 顶部状态叙述已过期

`status.md` 已明确：

- HXA-196 已交付；
- HXA-198 已交付；
- HXA-199 已交付；
- HXA-206 已交付；
- HXA-217 已交付；
- HXA-218/219 已完成并整合。

但 `roadmap.md` 顶部仍保留：

- “历史复核基线保留 12 项未闭合义务”；
- “217 仅完成格式成本准备”；
- “129 尚未合并 main”等旧叙事。

后面的任务表又把它们标成已交付。

这是当前最明确的文档自相矛盾之一。

**建议：**
- `roadmap.md` 顶部只保留“当前未闭合项”；
- 历史执行顺序移到 history/evidence；
- 不要在 roadmap 同时维护“过去的计划”和“当前事实”。

---

### 7.2 构建注释仍有旧的 cross-APK 叙事

`app/build.gradle.kts` 中仍有：

> shared cross-APK protocol types

而当前 runtime 已是 developer 单 APK 内私有进程模型。

虽然只是注释，但会继续制造认知债。建议随下一次 Runtime 改动统一清理。

---

## 8. CI 与发布就绪度

### 8.1 CI 当前没有设备测试

这是事实。

现有 CI 主要执行：

- source gate；
- analysis；
- JVM / build tests；
- APK artifact boundary；
- release artifact 静态门禁。

没有 emulator / physical device test。

**建议：**

最少增加一个低成本 smoke：

- API 29 或 API 36 managed emulator；
- 只跑核心恢复、Room migration、基础 chat/tool path；
- 真机 Root/PRoot/Doze 保持外部 nightly/manual。

不建议把完整 900+ instrumentation 全塞到每次 PR。

---

### 8.2 Release 仍明显未完成

`app/build.gradle.kts` 当前：

- `versionCode = 1`
- `versionName = "0.1.0"`
- `release.isMinifyEnabled = false`
- 未见正式 release signing 配置。

这些与 `status.md` 的“发行队列尚未开始”一致，因此应视为**未完成的发布工程**，不是实现 bug。

建议继续按既定 HXA-120 → 122 → 121 → 123 处理。

---

## 9. 性能问题：建议先测量再改

现有审查提到的以下问题从代码结构上成立：

- `ChatScreenProjection.toolTimelineFor()` 多次 Room 查询；
- message 列表逐条内容读取；
- streaming UI 大对象频繁 emit；
- Markdown 重解析；
- ContentStore read-path hash；
- BudgetContinuation 多次扫描。

但目前缺少统一的：

- frame time；
- recomposition count；
- Room query count；
- allocation / heap peak；
- startup / long-turn baseline。

因此这些不应该与正确性 bug 混排。

建议建立 3 组 benchmark：

1. **200 turns + 400 tool calls 的 session 打开/滚动**
2. **长 streaming answer（5k–20k tokens）**
3. **100+ artifact / 50+ image context 的模型组装**

拿到指标后再做批量查询、state 拆分、hash cache 等优化。

---

## 10. 本轮新增的建议优先级

### 第一批：必须先修

1. 并行 RUNNING × process death 恢复契约
2. Root `readAllBytes` 无界生产路径
3. UNKNOWN batch settlement
4. OAuth loopback 绑定 loopback 地址
5. QuickJS 256 KiB 契约与 64 KiB 传输不一致
6. regenerate supersede 的失败回滚

### 第二批：主流程可靠性

7. Browser lost-host reload 恢复
8. Tool Timeline typed state
9. Tool duration 投影
10. recovery / goal reconcile failure boundary 拆分
11. CI 增加最小 device smoke

### 第三批：治理

12. 修正 roadmap/status 矛盾
13. `ChatService` 渐进拆分
14. `ui/` 按 feature 分包
15. 明确 TurnReducer vs BatchTurnRuntime 权威边界
16. 清理 stale runtime 注释

### 第四批：发布

17. application id / version / signing
18. R8/RProguard shrink 策略
19. ABI / bundle 策略
20. release candidate matrix

---

## 11. 建议新增回归测试

### Recovery

- 两个并行 read-only call 均进入 RUNNING 后 SIGKILL；
- 重启后两个 call 均进入可解释状态；
- 一个坏 Turn 不阻断其他 Turn/Goal reconcile。

### Batch Settlement

- SETTLED + UNKNOWN；
- UNKNOWN + CANCEL；
- UNKNOWN 后用户确认/拒绝；
- 结果消息是否完整持久化。

### QuickJS

- 63 KiB / 65 KiB / 255 KiB JSON 输出；
- isolated service 冷启动接近 timeout；
- output PFD failure。

### Root files

- 64 MiB / 256 MiB 文件读取；
- cancel；
- process memory upper bound；
- root permission loss mid-stream。

### OAuth

- server address 必须 loopback；
- callback from non-loopback interface rejected；
- duplicate callback；
- timeout/cancel。

### Regenerate

- supersede 后 submit failure；
- provider removed between verify and submit；
- attachment changes during regenerate；
- session switch race。

---

## 12. 最终评价

Helix 当前的核心架构质量仍然是偏高的：

- module DAG 总体清晰；
- policy/tool/runtime 责任边界有明确设计；
- consumer/developer 边界做了 artifact 级验证；
- Room migration、物理设备、Root/PRoot、终端等已有大量真实验收；
- 文档和 ADR 体系远超一般个人项目。

当前最需要警惕的不是“代码不规范”，而是：

> **项目演进速度已经超过部分早期核心契约的演进速度。**

典型表现就是：

- 串行 Tool 时代的 recovery invariant 遗留到并行 Tool 时代；
- QuickJS 的工具层 output contract 与 IPC transport contract 不一致；
- UI 已经有 typed outcome，但 UI adapter 丢弃它；
- feature 实现已完成，但 roadmap 顶部叙事仍停留在旧阶段。

因此下一轮最有效的工作不应继续大规模加功能，也不建议立即进行大爆炸架构重写；更合理的是做一轮 **Cross-layer Contract Convergence**：

1. 恢复契约；
2. 批次结算契约；
3. Tool descriptor ↔ transport limit；
4. UI typed state ↔ projection；
5. 文档 status ↔ roadmap；
6. CI evidence ↔ release gate。

把这些跨层契约收敛后，再推进大类拆分和 release 工程，会比继续堆 HXA 功能更稳。
