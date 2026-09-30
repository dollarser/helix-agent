# Helix 全量代码审查报告

**审查基线**：HEAD `b51687e0`（"Complete atomic tool bindings and autonomous recovery"，300 文件 / +8144 / −1703）+ 当时工作树（5 modified + 53 untracked）
**代码规模**：32 个 Gradle 模块 / 2121 个 `.kt`（主源码 1283 / 单测 461 / androidTest 336）/ 主源码约 18.9 万行
**审查方式**：主机门禁实跑（detekt / spotless / unit test / `ci-run-gate.sh --source`）+ 分路只读代码走查（引擎与 Turn 生命周期、安全与信任边界、资源泄漏与错误处理、UI 性能与架构一致性、原子工具绑定与调度、自主恢复链路、QuickJS 原生桥、Provider 探测）；关键结论均回源码二次核验
**设备状态**：`not requested`（未启动模拟器/真机，未做设备验收）
**报告说明**：本报告合并了同日的两轮审查（`7480141b` 全量审查 + `b51687e0` 重构后复审），只保留当前基线下的状态。条目用 `[遗留]` / `[重构新增]` 标注来源。

> ⚠️ **快照声明**：审查期间仓库存在**并行工作线在实时写入**。两次实测：`docs/completion-records/HXA-231.md` 被改动使 `check-docs.sh` 由失败转通过；随后 `docs/development/implementation-guide.md` 被改动、`docs/research/harness-human-intervention-audit-2026-09-29.md` 被移除，使门禁再度失败。本报告是某一时刻的快照，复用前请按"当时基线 → 当前源码"复核。

---

## 结论摘要

| 维度 | 结论 |
| --- | --- |
| 客观门禁 | detekt + spotlessCheck 通过；`ci-run-gate.sh --source` 通过；重构核心 7 模块**实跑 938 用例全绿**；**全量测试无法在当前环境复跑**（沙箱无网络 + 缓存缺件，见第七节） |
| 核心安全不变量 | **实现正确**，未发现高危缺陷。工具管线、SSRF、路径穿越、凭据加密、审批原子性、WebView bridge 均经源码逐条核对为 fail-closed |
| 执行引擎 | **2 个高严重度缺陷仍在**（Goal 结算抛异常崩溃、启动失败被误判为取消并连带取消 Goal） |
| 工具绑定（本轮重构） | **3 个高严重度问题**：`ask_user` 缺所有权豁免、绑定锁内做 Room 写、超时后释放排他槽不同步 |
| 自主恢复（本轮重构） | **3 个高严重度问题**：绕过 reducer 写 `FAILED`、反问答案丢弃已选选项、自动收集失败后永久放弃 |
| 资源与生命周期 | **系统性**：至少 8 处协程/线程池/执行器创建后从不取消或关闭 |
| UI 性能 | 整个 App Shell 用非生命周期 `collectAsState`，随每个流式 token 重组 |
| 可维护性 | `ChatService.kt` 已到 **4432 行**（本轮重构未拆）；生产装配仍用 `FakeShellRepository` |

**最需要立即处理的三件事**

1. **`GoalReducer.onWakeFailed` 漏清 `nextCheckpoint`** —— 抛 `IllegalArgumentException` 穿透整个结算事务，导致 Turn 终态未落库、Goal 未结算、进程崩溃。
2. **`TurnExecutionDriver` 把启动期异常当作"用户取消"** —— 一次基础设施异常会把从未执行的 Turn 落成 `CANCELLED`，并**永久取消整个 Goal**。
3. **`AutomaticRecoverySettlement` 绕过 reducer 直接写 `FAILED`** —— 写出不带 `error` 的非法 Goal 行，之后任何 `reduce()` 都会抛异常。

---

## 一、确定性缺陷

### P0-1 `[遗留]` `onWakeFailed` 失败分支漏清 `nextCheckpoint`，结算事务抛异常并崩溃进程

- **位置**：`core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt:200-213`（对照 `:309-325` 的 complete/cancel 分支）
- **证据**：FAILED 分支只写 `state = FAILED, error, currentWakeMillis = 0L`，**没有** `nextCheckpoint = null`；而 `onCompleteRequested`（`:313`）与 `onCancelled`（`:325`）都显式清空。
- **触发**：`verify`（`:389-393`）要求 `nextCheckpoint != null` 时状态必须属于 `RUNNING/PAUSED/INPUT_REQUIRED/BLOCKED`。`nextCheckpoint` 可从 Room 经 `GoalStorageMapping.toRuntimeGoal()` 还原（`onCheckpointScheduled` 允许在 RUNNING 设置）。因此一个 RUNNING 且已排 checkpoint 的 Goal，只要一次 wake 失败（不可重试或重试耗尽），`step()` 内的 `verify` 就抛 `IllegalArgumentException`。
- **影响**：调用链 `GoalRunSettlement.settleBoundTurn` → `TurnSettlement.settleInTransaction` → `TurnExecutionDriver.terminalize` 无 `runCatching` 包裹，异常穿透结算事务：Turn 终态未落库、Goal 未结算、协程未捕获异常直接崩溃进程。
- **修复**：FAILED 分支补 `nextCheckpoint = null`，并补 `GoalEffect.ReminderCancelled` 与 complete/cancel 对齐。

### P0-2 `[遗留]` 启动期失败被当成"用户取消"，并把绑定的 Goal 永久置为 CANCELLED

- **位置**：`app/src/main/kotlin/com/helix/app/engine/TurnExecutionDriver.kt:84-99` 与 `:136-140`
- **证据**：`launch()` 在 `finally` 中 `if (!started) job.cancel()`；`run()` 中 `startGate.await()` 被取消后落到 `catch (e: CancellationException)`，直接 `terminalize(..., TurnState.CANCELLED, null)`。
- **触发**：`liveExecution.claim()`（`TurnLiveRegistry` 的 `require`）或 `hooks.beforeExecution()`（内部含 Room 读的 `refreshScreen()`）抛任何异常，即走上述路径。
- **影响**：一个已持久化（`WAITING_MODEL`）但**从未执行**的 Turn 被落成 `CANCELLED`；`GoalRunSettlement.decision` 把 `CANCELLED` 映射为 `GoalEvent.Cancelled`，于是**整个 Goal 被永久取消**。一次基础设施异常变成业务终态。
- **修复**：区分"启动期失败"与"运行期取消"。启动失败应走 `FAILED + INTERNAL` 或专用 launch-failure 终态，不复用 `CANCELLED`。

### P0-3 `[重构新增]` `ask_user` 未走 metadata 执行豁免，会抢占全局排他执行准入

- **位置**：`app/src/main/kotlin/com/helix/app/SessionRuntimeTools.kt:15`
- **证据**：
  ```kotlin
  CodeJavascriptRunTool.register(registry) { params, cancel -> javascript.execute(params, cancel) }
  UserQuestionTool.register(registry, questions)     // 没有 metadataExecutor 包装
  ```
  `UserQuestionTool.descriptor()` 的 `operationClass = ToolOperationClass.METADATA`（`UserQuestionTool.kt:38`）。而其它所有 METADATA 工具都经 `executionOwnership::metadataExecutor` 注册（`DefaultAppContainer.kt:495`、`:498`、`:505`、`:509`）。
- **为什么是问题**：`ExecutionOwnership.kt:113-114` 的分支是 `executor is MetadataExecutor -> runGuarded(...)`，否则 `else -> runOrdinary(...)`；`runOrdinary` 会 `acquire(callId, exclusive)`，失败即返回 `Failed("EXECUTION_BUSY: ...", sideEffectFree = true)`（`:250-255`）。`ask_user` 是纯问答工具，却因此参与排他执行所有权：任一 Runtime 所有者存在时它会失败；它自己也会占住排他 permit，阻塞 Runtime 启动/控制。
- **修复**：给 `registerSessionRuntimeTools` 增加 `executionOwnership` 参数，用 `metadataExecutor(...)` 包装 `UserQuestionTool`。

### P0-4 `[重构新增]` `ToolBindingStore.admit` 在全局绑定锁内执行 Room 写

- **位置**：`tools/framework/src/main/kotlin/com/helix/tools/framework/ToolBindingStore.kt:74-81` 与调用点 `tools/framework/.../ToolDispatcher.kt:804-810`
- **证据**：`admit` 的 KDoc 明确写 **"Only a short local admission commit is allowed here, never executor/approval/network work."**，但调用方传进去的第一件事就是批准消费：
  ```kotlin
  registry.admit(ref) {
      proof?.let { approvals.consume(it) }   // → StorageApprovalBroker.consume → Room 事务
      clock.now().also { ... }
  } ?: stopped(...)
  ```
- **为什么是问题**：`admit` 内是 `synchronized(lock)`。该 `lock` 同时保护 `resolve` / `snapshot` / `register` / `replace`，而 `ToolScheduler.resolveDescriptor` 会为**每个**调用构建 footprint。于是一次 `consume` 的磁盘提交期间，所有工具准入链被串到磁盘写后面——这正是新引入的全局串行点，且直接违反该函数自己的约定。
- **修复**：`admit` 的 `commit` 只做纯内存 CAS；`approvals.consume` 移到锁外（失败再拒绝/回滚）。

### P0-5 `[重构新增]` 超时/取消后立即释放排他槽，被放弃的 worker 可能仍在跑

- **位置**：`tools/framework/.../ToolScheduler.kt:302-309` 配合 `ToolDeadlineRunner.kt:72-76`
- **证据**：`dispatch` 返回后立刻 `releaseSlot(callId)`；而 `ToolDeadlineRunner` 超时路径只做 `future.cancel(true)`（best-effort）——worker 忽略中断会继续执行实际副作用。新增的 `ToolExecutionActivity.abandonedRunning` 正是为记录这个事实而加的，但它只进审计 `auditDetail`，**没有参与准入决策**。
- **为什么是问题**：一个写操作超时（worker 仍在写）后，调度器判定该调用终结并释放排他 lane，同批次中与其冲突的第二个写调用会被立即放行 → 两个写并发。这与项目"多个写操作保守串行、取消需等 terminal 对账"的意图相悖。
- **修复**：worker 真正退出前不释放排他槽；把 `abandonedRunning` 作为"lane 仍被占用"的事实源。

### P1-6 `[重构新增]` 自主恢复绕过 Goal reducer，直接写 `FAILED` 且不带 `error`

- **位置**：`app/src/main/kotlin/com/helix/app/engine/AutomaticRecoverySettlement.kt:46-51`
- **证据**：
  ```kotlin
  if (goal.state !in setOf("COMPLETED", "FAILED", "CANCELLED") &&
      storage.goalRuns.listOpenByGoal(goal.id).isEmpty()
  ) {
      storage.goals.updateGoal(goal.copy(state = "FAILED", currentWakeMillis = 0))
  }
  ```
- **为什么是问题**：`GoalReducer.verify`（`GoalReducer.kt:370-372`）要求 **`FAILED goal requires an error`**。这里直接写库绕过了 reducer，`error` / `finishReason` 均为 null，同时也没有发出 `ReminderCancelled` 效果。触发路径是 `AutomaticTurnRecovery` 的 `RECOVERY_UNAVAILABLE` 分支——此时 Goal 通常处于 `PAUSED`（启动恢复已 park），于是被静默改成 `FAILED`。该行之后一旦再经 `reduce()`（任何 Goal 事件）就会抛 `IllegalArgumentException`。
- **修复**：改走 `GoalReducer.reduce(goal, GoalEvent.WakeFailed(HelixError(...)))`，与 `GoalRunSettlement.inspectionFinished` 一致。

### P1-7 `[遗留]` 全局锁 `turnGate` 内嵌套阻塞 Room 事务

- **位置**：`app/.../chat/ChatService.kt:3822` 起 `synchronized(turnGate)`，其内 `:3929` 调用 `turnEngine.admit(...)`（`TurnAdmission` 内部走 `storage.withTransaction`）；同类还有 `:3451`→`:3453`、`:683`→`:686`。全文件 `turnGate` 出现 22 次。
- **证据**：`storage.withTransaction` 是阻塞调用（`HelixStorage` 的 `database.runInTransaction`）。`:3808` 的注释写"Room read runs OUTSIDE the gate"，对**那一处** provider 快照读成立，但 `admit` 的事务仍在锁内。
- **影响**：`turnGate` 是唯一的 admission/cancel/投影刷新全局锁，而 `refreshBackgroundTasks` 会被流式事件路径频繁调用。把 Room 事务放进该锁，会把"所有 Turn 准入"与"UI 投影刷新"串行化，单次准入可横跨多个事务，直接放大首 token 延迟。
- **修复**：锁内只做纯内存的 check-then-act 与 id 生成；持久化事务移出锁外。

### P1-8 `[遗留]` `submissionAttempt` 静默吞掉任意异常

- **位置**：`app/.../chat/ChatService.kt:2174-2181`（该文件共 7 处 `catch (_: Exception)`）
- **证据**：`@Suppress("TooGenericExceptionCaught", "SwallowedException")` + `catch (_: Exception) { ChatSubmissionOutcome.Rejected("ADMISSION_FAILED") }`。
- **影响**：发送准入链路（`sendSubmission` / `confirmSubmission` / `resumeSessionInput` / `sendQuestionAnswer` 等）全部经过这里。Room 异常、`require` 失败等一律折叠为对用户无意义、对日志无痕迹的 `ADMISSION_FAILED`，无法定位。
- **修复**：至少 `Log.e(TAG, ..., e)`；区分可重试/不可重试。

### P1-9 `[遗留]` `ProotJobRunner` 对同一 fd 双重关闭

- **位置**：`runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt:300-304`
- **证据**：
  ```kotlin
  inputPfd.use { pfd ->
      FileInputStream(pfd.fileDescriptor).use { input ->
          FileOutputStream(inputArchive).use { out -> input.copyTo(out) }
      }
  }
  ```
  `FileInputStream(fd)` 关闭时会关掉底层 fd，外层 `inputPfd.use` 又对同一 `ParcelFileDescriptor` 调 `close()`。
- **影响**：fd 二次 `close()`；若期间 fd 号已被其它线程复用，会误关他人 fd（fd 竞态），至少也会抛 `EBADF` 掩盖真实失败原因。
- **修复**：改用 `ParcelFileDescriptor.AutoCloseInputStream(pfd)`，只保留一条关闭路径。

### P1-10 `[重构新增]` 结构化反问：自定义文本会静默丢弃已选选项

- **位置**：`app/src/main/kotlin/com/helix/app/chat/UserQuestionService.kt:83`
- **证据**：`val value = custom.trim().ifBlank { question.options.filter { it in selected }.joinToString("; ") }`
- **为什么是问题**：`UserQuestionDialog` 允许"选中 chip **或**填自定义"，两者可同时非空。此时 `custom` 非空 → 选项被完全忽略，答案与用户实际选择不符。
- **修复**：`selected` 与 `custom` 合并（`selected + custom`），而不是二选一。

### P1-11 `[重构新增]` 自动收集失败即永久放弃，且静默无日志

- **位置**：`app/src/main/kotlin/com/helix/app/chat/AutomaticRuntimeCollection.kt:15`、`:22`、`:32-34`、`:49-52`
- **证据**：`private val requested = mutableSetOf<String>()`，只在 `DEFERRED` 分支 `requested.remove(key)`；`COMPLETE` 与失败耗尽两条路径都**不移除**。异常被 `catch (_: Exception) { Observation.RETRY }` 吞掉，无日志。
- **为什么是问题**：key 永久占位，同一恢复结果**永远不会再被自动收集**，UI 停在"不可用"；且失败原因完全不可见。
- **修复**：放弃时移除 key（带重试上界），并记录原因。

### P1-12 `[重构新增]` proot 自动收集没有 RUNNING 态，约 6 秒后永久放弃

- **位置**：`app/src/main/kotlin/com/helix/app/chat/AutomaticRecoveryCollection.kt:78-109`（判定在 `:103-107`）
- **证据**：订阅路径有 `RUNNING` 态会重置 `failures` 并最多轮询 60 次；proot 只有 `COMPLETE/RETRY`，`collectProot` 返回 null 即 `RETRY`，累计 `MAX_FAILURES` 次后放弃。
- **为什么是问题**：proot 后台作业超过约 6 秒才落结果时，恢复输出不再被自动收集。
- **修复**：给 proot 一个"运行中"观察态或显著更长的上界。

### P2-13 `[遗留]` `UserScopeCodec` 的 automation/root 解码用裸下标，违反自身 fail-closed 契约

- **位置**：`core/policy/src/main/kotlin/com/helix/core/policy/UserScopeCodec.kt:122-124`、`:134-137`
- **证据**：`decodeAutomation` 直接取 `fields[2]`、`fields[3]`；`decodeRoot` 直接取 `fields[0..2]`。而唯一的兜底 `catch (e: IllegalArgumentException)`（`:96`）**捕不到** `IndexOutOfBoundsException`。KDoc（`:69-71`）明确承诺"any malformed input returns null"。
- **触发**：`decode("hsr1\u0001a")` → `parts.size == 2` 通过检查，`tag = "a"`，`fields` 为空 → 抛 `IndexOutOfBoundsException`。
- **影响**：当前唯一生产调用方按 `?: error(...)` 处理，恰好也是 fail-closed，**无实际安全影响**；但任何新调用方若按 KDoc 契约依赖 `null`，会得到未预期异常。测试也有覆盖缺口。
- **修复**：改用已存在的 `exactly(fields, n) ?: return null`（与 `decodeBrowserTab`/`decodeAllfiles` 对齐），或让 `safeDecode` 额外捕获 `IndexOutOfBoundsException`。

### P2-14 `[遗留]` 已存在流式哈希实现，却仍用整文件 `readBytes()`

- **位置**：`core/storage/.../content/ContentStore.kt:62`、`:122`；`app/.../a2a/A2aTaskArtifacts.kt:104`
- **证据**：`ContentStore` 内部已有流式 `fun sha256Hex(file: File)`（`:139`），但写路径与 `readHashOrNull` 仍用 `sha256Hex(tmp.readBytes())`。`writeContent` 的 `content: String` 已在内存，写完后又一次全量读入，峰值内存翻倍；文档类内容可达 32 MiB 级。
- **修复**：统一改用流式 `sha256Hex(file)`。

### P2-15 `[遗留]` `JsExecutionClient.readBounded` 先全量读入再校验大小

- **位置**：`runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/JsExecutionClient.kt:537-546`（精确行号 541）
- **证据**：方法名叫 `readBounded`，实际 `file.readBytes()` 后才比较 `expectedBytes`；而 `expectedBytes` 来自 JS 服务进程上报的 `result.outputBytes`，真正的上限 `maxOutputBytes` 直到后续 `JsOutputArtifact.verify` 才校验。`File.readBytes()` 在 >2 GiB 时抛的是 `OutOfMemoryError`（不是 `IOException`），`:531` 的 catch 接不住。
- **影响**：行为异常的 JS 服务进程上报超大 `outputBytes` 时，客户端先把整个文件读进堆内存，可致 OOM。
- **修复**：读前 `require(file.length() <= maxOutputBytes)`，并用带上限的流式读取。

### P2-16 `[遗留]` `BrowserDownloadQueue` 未关闭 `connection.inputStream`

- **位置**：`feature/browser/.../BrowserDownloadQueue.kt:115-123`
- **证据**：只有 `out` 被 `use`，`connection.inputStream` 直接作为参数传入 `copyWithCap`，仅依赖 `finally` 的 `disconnect()`。
- **影响**：`disconnect()` 对 keep-alive 连接不保证立即关闭已打开的输入流；`copyWithCap` 抛异常时输入流泄漏。
- **修复**：`connection.inputStream.use { ... }`。

### P2-17 `[遗留]` provider 异常被伪装成 "not found"

- **位置**：`feature/files/.../ContentResolverSafTreeReader.kt:205-216`
- **证据**：`catch (e: Exception) { throw notFound(parentDocId) }`。
- **影响**：`SecurityException`（授权被撤销）、`RemoteException`（provider 崩溃）等全部被重写为 `FileNotFoundException`，上层把"权限失效/Provider 故障"当作"文件不存在"，掩盖真实原因。
- **修复**：区分异常类型，`SecurityException` 原样上抛或映射为权限类错误。

### P2-18 `[遗留]` 原始异常 `message` 直接透传给 UI

- **位置**：`app/.../files/FileManagerService.kt:500`、`:502`、`:516`、`:542`
- **证据**：`FileOpResult.Error(e.message ?: loc(R.string.files_error_...))`。
- **影响**：内部绝对路径、SAF authority、断言文本等直接渲染到用户可见的错误提示，既泄露实现细节又不可本地化。
- **修复**：按异常类型映射到稳定的本地化错误码，原始异常走结构化日志。

### P2-19 `[重构新增]` Provider 探测的分代粒度过粗，合法结果被作废并显示为失败

- **位置**：`app/src/main/kotlin/com/helix/app/provider/ProviderProbeGate.kt:13-21`
- **证据**：`begin(id, ...)` 用 `generations[id] = token` 按 **providerId** 分代；`ProviderConnectionProbe.runChecked` 与 `ProviderService.discoverContextWindow` 对同一 providerId 都调 `begin`。
- **为什么是问题**：用户点"测试连接"期间再打开上下文对话框，后者覆盖 generation，前者 `publish` 时 token 不匹配 → 一次**成功**的连接测试被丢弃，UI 反而显示 `PROBE_SUPERSEDED` 失败。反向同理，刚探测到的 `serverWindow` 会丢失。
- **修复**：generation key 带操作维度，或让"窗口发现"与"连接测试"各自串行化；"配置失效"只应由 `mutate` 触发。

### P2-20 `[重构新增]` 能力探测失败不清理 `capabilitySnapshot`（fail-open 残留）

- **位置**：`app/.../provider/ProviderConnectionProbe.kt:51-62`；读取方 `app/.../provider/ProviderService.kt:530-545`
- **证据**：`ProbeOutcome.Failed` 分支对 `detectCapabilities` 直接 `return@publish`（什么都不写），非能力探测只 `recordFailed(...)` 改状态行，**不动 DB 的 `capability_snapshot`**。而发送路径的视觉门控读的是快照。
- **为什么是问题**：先前探测通过（`vision=true`）的 Provider 重测失败后，仍被当作支持 vision/toolCalls。本轮措辞容易让人以为已 fail-closed，实际是"失败保留旧的已支持"。
- **修复**：失败时把快照重置为保守值（或加 `verifiedAt`/过期），并让门控同时要求"最近一次状态为 Passed"。

### P2-21 `[重构新增]` `ProviderService.create` 绕过 gate，状态存储读-改-写非原子

- **位置**：`app/.../provider/ProviderService.kt:281`（`create` 内直接 `testStatus.clear(id)`）；`ProviderTestStatusStore.kt:153-163`
- **证据**：`clear` / `replace` 都对共享 key 做 `lines()` → `setLines()` 的读-改-写，`LineStore` 不做整段 CAS。
- **为什么是问题**：`create` 与另一个 Provider 的 `recordPassed`（在 gate 内、IO 线程）并发时后写覆盖先写，丢一条状态行。`delete`/`update` 都走 `mutate` 不受影响，唯独 `create` 漏了。
- **修复**：`create` 的 `clear` 纳入 `probeGate`，或按 providerId 分 key。

### P2-22 `[重构新增]` `applySettings` 工具路径绕过 `submissionGate`，与发送准入不互斥

- **位置**：`app/.../chat/ChatService.kt:1482-1522`
- **证据**：UI 路径统一走 `sessionActions.submit { submissionGate.withLock { synchronized(turnGate) { ... } } }`（`:519-557`），新增的工具授权配置路径只锁 `turnGate`。
- **为什么是问题**：`helix.settings.apply` 执行期间，一次新的 `sendSubmission` 可并发进入 `admitSubmission`，两者都会读写 `sessionRunControls`；`settingsChangeAdmitted` 只检查"当前 pending 为空"，挡不住检查后新到的发送以旧 control 被接纳。
- **修复**：工具路径也纳入同一序列化域。

### P2-23 `[重构新增]` `ask_user` 参数校验抛异常，被结算为"副作用未知"并挂进 NEEDS_REVIEW

- **位置**：`app/.../chat/UserQuestionTool.kt:63-64` → `UserQuestionService.kt:105-110`
- **证据**：schema 只有 `maxItems: 6`，没有 `uniqueItems`；service 里 `require(options.distinct().size == options.size)`。模型给出重复 options 即抛 `IllegalArgumentException`。
- **为什么是问题**：异常从 executor 抛出 → 调度器记为 `Thrown` → `unsettledSlotSettlement` 得到 `sideEffectFree = false` → 被判为需人工复核。**一个纯参数错误把会话挂进 NEEDS_REVIEW**。另外 `pending()` 对畸形持久化行也会抛，可能让对话 UI 的 `LaunchedEffect` 崩溃。
- **修复**：schema 加 `uniqueItems`；executor/service 对非法参数返回稳定拒绝而非抛异常；`pending()` 对畸形行降级跳过。

### P2-24 `[重构新增]` 普通执行池从 cached 改为固定 32，阻塞型 executor 会耗尽全局容量

- **位置**：`tools/framework/.../ToolExecutionPools.kt:9-11`（`bounded(32, "tool-executor")`，替换了原先的 `newCachedThreadPool`）
- **为什么是问题**：忽略中断且阻塞的 executor 累计 32 个即永久占满，之后**所有会话**的工具调用都命中 `EXECUTOR_SATURATED`，且 `sideEffectFree = true` 使有界重试也无法恢复。新增的容量测试只验证"单个被放弃 worker 占 1 槽"，量级不同。
- **修复**：为阻塞型 executor 提供隔离/回收，或对持续增长的 `abandonedRunning` 做熔断。

### P2-25 `[重构新增]` 内置工具的 `implementationRevision` 默认等于 `contractHash`，身份无法区分实现变更

- **位置**：`tools/framework/.../ToolBinding.kt:11`、`ToolRegistry.kt:16-21`
- **为什么是问题**：`implementationRevision` 默认回落 `descriptor.contractHash.hex`，于是"契约不变、executor 换了"的两个实现得到相同的 `implementationIdentity`（`ToolDispatcher.kt:1095-1103`）。唯一区分是 `ref.incarnation`，而它被明确排除在可复用审批身份之外。当前因每次调用重新 mint 尚不可直接利用，但身份语义是错的。
- **修复**：内置/插件工具传入真实代码修订，或把 incarnation 纳入 `implementationIdentity`。

### P2-26 `[重构新增]` QuickJS 原生总开关只在客户端强制，服务端不校验

- **位置**：`runtime/quickjs/.../JsNativeExecutionService.kt:4-12`、`JsExecutionService.kt:44-46`
- **证据**：`JsNativeExecutionService` 的 `override val nativeAccess: Boolean = true` 是编译期常量；`JsExecutionService` 里 `binder = ExecutionBinder(if (nativeAccess) this else null)`。唯一读 `JsNativeAccessSettings` 的地方是客户端 `JsExecutionClient.execute`。该 service 在 manifest 中只是 `exported="false"` 的**同 UID 普通进程**。
- **为什么是问题**：同 UID 内任何代码只要直接 `bindService(JsNativeExecutionService)` 就拿到完整原生能力，与总开关状态无关。总开关成了单点、可绕过的授权（纵深防御缺口，不是已可利用漏洞）。
- **修复**：服务端 `onBind`/`handleExecute` 也读该设置并 fail-closed。

### P2-27 `[重构新增]` `nativeReply` 只捕获 `Exception`，`Error` 仍会跨字符串 ABI 抛出

- **位置**：`runtime/quickjs/.../JsNativeHost.kt:143-152`
- **证据**：`catch (error: Exception)` 里 `if (cause is Error) throw cause`。而 `Class.forName` 的静态初始化失败抛 `ExceptionInInitializerError`/`NoClassDefFoundError`（都是 `Error`），JS 构造的深嵌套 JSON 可触发 `StackOverflowError`。
- **为什么是问题**：本文件注释明说"native API 异常必须走字符串 ABI，不能逃出执行线程"，但 `Error` 会逃出。测试把该行为固化为"预期"，对不可信 JS 输入而言这是把控制面异常交回 native 边界。
- **修复**：对可预期 `Error` 也转成 error JSON；解析前先限制请求嵌套深度。

### P2-28 `[重构新增]` 启动恢复对每个会话做 N+1 扫描，并在 `submissionGate` 内起 Turn

- **位置**：`app/.../chat/ChatService.kt:888-902`
- **证据**：`storage.sessions.list().filter{...}.forEach { storage.turns.listBySession(it.id).lastOrNull()?.let { turn -> submissionGate.withLock { automaticTurnRecovery.recover(turn.id) } } }`
- **为什么是问题**：冷启动关键路径上的串行 DB 扫描 + 逐会话持锁调 `recover`（可能 `launchTurn` 起新 Turn）。
- **修复**：用一条"按会话取最后 Turn"的查询；把 `recover` 的准入与 `submissionGate` 解耦。

### P3-29 `[重构新增]` 其他

- **`ChatRequestAssembler.kt:477-485`** 把**全部** SYSTEM 消息提到历史最前（`restored.filter { role == SYSTEM } + restored.filter { role != SYSTEM }`），削弱了 `loop_warning`/`loop_exhausted` 等控制通知"紧随工具结果"的语义。修复：只前置尾部控制通知。
- **`AutomaticRecoveryPolicy.kt:20-22`** 对 boundGoal 用满额预算（不扣减已消耗），依赖 `RecoveryGoalRunStart` 的夹取；夹取失败时检查回合可拿到超过原 Turn 的额度。
- **`AutomaticInputRecovery.kt:45-55`** `input-recovery:<id>` 审计事件在成功恢复后仍保留，同一输入再次 delivery 失败即直接 `failPending` → **一次瞬时失败被放大为永久失败**。
- **`AutomaticInputRecovery.kt:32-34`** 忽略 `requeueAfterTargetFinished` 的返回值，可能把输入重新投递给旧目标。
- **`AutomaticInputRecovery.kt:17-18`** 只看最新 turn 的停止态，较早 turn 被 park 的输入会在用户已停止后被恢复。
- **`AutomaticTurnRecovery.kt:43-53`** catch-all 静默吞掉 `start` 异常（无日志），编程错误被转成"恢复不可用"。
- **`ToolScheduler.kt:346-352`** `catch (IllegalArgumentException)` 已成死代码（`resolveBinding` 返回可空、从不抛），建议删除以免误导。
- **`UserQuestionDialog.kt:35-37`** `LaunchedEffect` 用 `screen.toolTimeline` 作 key，每次投影刷新都是新 List 实例 → 频繁触发 `service.pending()` 的多次 DB 读。
- **`app/.../engine/TurnEngine.kt:204-206`** 对"非终态且无 live driver"直接 `error(...)`；调用方 `ChatService.stopTask`（`:2889-2905`）的 `workScope.launch` 无 try/catch，异常会作为未捕获异常终止协程。

---

## 二、资源与生命周期泄漏

共性：**创建后从不取消/关闭**。这类问题在进程级单例上不会立即崩溃，但会持续占用线程/唤醒、让"关闭/销毁"语义失效，并妨碍测试隔离。

| # | 位置 | 问题 |
| --- | --- | --- |
| 1 | `feature/browser/.../BrowserController.kt:534`（启动 `:541`、`:572`，销毁 `:752`） | `persistenceScope` 创建后全文件无 `.cancel()`，`destroy()` 不取消它，销毁后仍可能有写入任务触碰已 `clear()` 的 host |
| 2 | `app/.../audit/AuditLogService.kt:43`（`init` 中 `:68-82` 无限 `collect`） | 无 `close()`/`shutdown()`，进程级 IO scope + 永不结束的 collect |
| 3 | `feature/browser/.../BrowserDownloadQueue.kt:23` | `downloadExecutor` 从不 `shutdown()`，排队下载无法取消 |
| 4 | `runtime/proot-app/.../ProotJobRunner.kt:102`、`:106` | `jobExecutor` / `watchdogExecutor` 从不 shutdown；`:187` 还注册了周期性任务 |
| 5 | `tools/framework/.../ToolScheduler.kt:62` | 工具调度线程池无 `close()`；`ToolExecutionPools.bounded(...)` 同样 |
| 6 | `runtime/proot-core/.../JobLogSpool.kt:29` | 日志泵 `Executor` 从不 shutdown（需从 `Executor` 改为 `ExecutorService`） |
| 7 | `app/.../chat/ChatService.kt:161`（生产装配 `DefaultAppContainer.kt:800` 未传 `scope`） | 默认新建的 `SupervisorJob + IO` scope 永不取消；`closeSession()` 不停止在途 `launch/async` |
| 8 | `app/.../diagnostics/ProcessDiagnostics.kt:145`、`:155-163` | 心跳以固定间隔永久 `postDelayed`，主 Looper 永不 idle；替换的全局未捕获异常处理器无 `stop()` 恢复路径 |

---

## 三、性能与 Compose

1. **整个 App Shell 用非生命周期 `collectAsState`，随每个流式 token 重组全壳** —— `app/.../MainActivity.kt:240-247`（3 处）、`:655`。`HelixApp` 在 body 中读 `drawerScreen` 并重建 `ConversationDrawerState`，`chatService.screen` 每变化一次（流式期间每 token）整个 `HelixApp` 重组，`NavHost`/`Scaffold`/drawer 全部重新求值；且非生命周期收集在 Activity 停止后仍在跑。同项目 `ChatScreen.kt:74` 已正确使用 `collectAsStateWithLifecycle`。`feature/browser/.../BrowserScreen.kt:73-81` 同类（11 处）。
2. **`LazyColumn` content 内 O(n²) 分组与过滤** —— `app/.../ui/ConversationSection.kt:265-297` 配合 `app/.../chat/ConversationEntry.kt:19-25`：每个 key 对全量 `messages` / `toolTimeline` / `subscriptionRecoveries` 各 `filter` 一次，复杂度约 O(keys × messages)，且两处 `messages.filter{}` 每帧新建列表。
3. **组合期在主线程解码 Bitmap** —— `app/.../ui/ArtifactsScreen.kt:344-347`：`BitmapFactory.decodeByteArray(...)` 无 `remember`、无 IO 派发。对照 `FilesScreenEffects.kt:67-77` 已正确放到 `Dispatchers.IO`。
4. **消息渲染每帧重新解析 Markdown / thinking** —— `app/.../ui/MarkdownText.kt:106`（`markdownInline`）、`:137-139`（表格 `split('|')`）、`app/.../ui/ConversationMessage.kt:147`（`ThinkingParser.parse`）均未 `remember`（同文件 `markdownBlocks` 已 `remember`）。
5. **组合期写 service 可变字段** —— `app/.../ui/ChatScreen.kt:151-152` 直接赋值 `chatService.recoveryModelsNavigation = onModels`，属 Compose 禁止的副作用模式。
6. **文件列表未懒加载 / 缺 key** —— `app/.../ui/FilesScreenLayout.kt:172-180`：LIST 模式用 `Column + verticalScroll` 全量组合；`:189` 的 `items(visible)` 缺 `key`。
7. **`visibleEntries` 是无缓存 getter** —— `app/.../ui/FilesScreenState.kt:136`，被 `FilesScreenLayout.kt:43` 每次重组读取；搜索逐字符输入时对大目录反复全量过滤。
8. **主线程同步 `SharedPreferences.commit()` + UI 直连 runtime.quickjs** —— `app/.../ui/QuickJsSettingsSection.kt:32`、`:45` → `runtime/quickjs/.../JsNativeAccessSettings.setEnabled`（`commit()` 同步落盘，失败还会 `check()` 抛异常）。同时与 AGENTS.md"UI 不直接访问 QuickJS"不符。
9. **为读单个字段收集整个 `ChatScreenState`** —— `app/.../ui/ProviderScreen.kt:60`、`app/.../ui/SessionPermissionSection.kt:139-144` 只关心 `openSessionId`，却订阅高频 `chatService.screen`。
10. **84 处 `mutableStateOf<Int>` 未用 `mutableIntStateOf`** —— Android Lint `AutoboxingStateCreation` 共 84 条（`ArtifactsScreen.kt:73`、`:475` 等）；项目内已有正确用法（`FilesScreenState.kt:35`），属不一致。

---

## 四、可维护性与架构一致性

- **`ChatService.kt` 4432 行**（全仓最大；其后 `ToolDispatcher.kt` 1199、`ProotJobRunner.kt` 942、`WorkspaceArtifactStore.kt` 935、`DefaultAppContainer.kt` 919）。单文件承载聊天状态、投影、恢复、草稿、任务等职责。本轮重构未拆。
- **生产装配仍用 `FakeShellRepository`** —— `app/.../DefaultAppContainer.kt:153-155`，且带 `@Suppress("SENSELESS_COMPARISON")`；接口唯一实现却叫 `Fake`。
- **3 个公开 API 生产零调用（仅测试引用）**，但 KDoc 声称是权威来源：
  - `core/agent/.../GoalForeground.kt:47`（声称"前台卡片生命周期的单一事实源"）
  - `core/agent/.../DeveloperRuntime.kt:18`
  - `core/agent/.../ModePolicy.kt:23`（声称"Exposure uses trusted operation contracts"）
- **`EmptyDestination` 为死代码** —— `app/.../MainActivity.kt:659-690`，全仓无引用，自带 `@Suppress("UnusedPrivateMember")`。
- **字节格式化三份并行实现**：`app/.../ui/UiLabels.kt:70` `formatBytes`（KiB/MiB）、`app/.../ui/FilesScreenComponents.kt:223` `formatSize`（KB/MB/GB）、`app/.../ui/LocalModelDialog.kt:319` `formatBytes`（GiB）——单位标签不一致。
- **近重复渲染块**：`app/.../ui/SessionPermissionSection.kt:59-93` 与 `:96-124`；`app/.../ui/TaskResultDialog.kt:27-72` 与 `app/.../ui/ArtifactsScreen.kt:465-521`；`ConversationSearch.kt:95-148` 与 `ConversationSection.kt:265-326` 各自实现同一套 entry 遍历。
- **`maxAttempts` 无任何生产调用点传值**（全仓 `src/main` 无 `maxAttempts =` 赋值），恒为默认 1，使 `ToolDispatcher.kt:321` 的 `attempt < request.maxAttempts` 重试分支为死代码（HXA-037 有界重试未接线）。

---

## 五、安全与信任边界

**总体结论：未发现高危缺陷。** `AGENTS.md` 声明的核心不变量经源码逐条核对，实现正确且 fail-closed：

- **工具调用管线顺序**：`tools/framework/.../ToolDispatcher.kt:344-362`、`:379-432`、`:721-782` 严格按 schema 校验 → capability → policy → session permission → approval → 原子消费证明 → 执行 → 输出绑定 → 单次审计；任一步抛出仍先结算审计再传播。
- **MCP/A2A 元数据不能授予权限**：`ToolDescriptor.kt:97-99` 在构造期禁止远端 origin 工具归类为 `READ_ONLY`/`METADATA`；`McpDynamicToolBridge.kt:180`、`ToolSource.kt:224` 硬编码 `operationClass = NETWORK`、`requiredCapabilities = emptySet()`。
- **SSRF 双重防线**：决策期 `PolicyEngine.RESERVED_HOSTS` + 连接期 `SsrfAddressPolicy.check`；`HttpFetchBridgeImpl` 每跳重校验并在连接后 `revalidatePeer`；TLS 用原始 hostname 做 SNI。
- **WebView 无特权 bridge**：全仓（除测试）无任何 `addJavascriptInterface`；`shouldOverrideUrlLoading` 恒返回 true 并重准入；`BrowserSecuritySpec` 的 file/content/universal access 全 false 且 `assertHardened` 逐项 `require`。
- **凭据加密真实生效**：`SecretStore` / `CliSubscriptionCredentialVault` 均为 AndroidKeyStore AES-256-GCM、随机 IV、0600、原子 `rename`、`finally` 删除临时文件。
- **审批原子一次性**：`ApprovalDao.consumeByBinding` 把 `decision/consumedAt/expiresAt/bindingHash` 放入**单条 SQL UPDATE**。
- **路径穿越**：`SkillRepository.resourcePath`、`PathResolution.resolveWithinRoot`、`FileScopePath`、`RootServiceOperations.readFile` 四处独立实现且均完整。
- **QuickJS 进程边界**：`isolatedProcess="true"` + `exported="false"`，slot 用 `compareAndSet` 防重放。
- **Provider 探测的 stale publish 与取消语义已修好**：`ProviderProbeGate` 的分代 + `mutate` 删除分代 + `publish` 的 `ensureActive`，加上配置实体二次结构比对，旧成功/失败无法覆盖新状态；同一 Provider 的多探测由单一 `Mutex` 串行。
- **`GoalDurableUsageLedger` 无丢失更新/重复计数**：读改写与分块心跳在同一个 `withTransaction` 内，进程中途死亡整体回滚。
- **自主恢复无无限重试/活锁**：`AutomaticTurnRecovery` 的 `isInspection` 后继不再生后继，`eligible` 排除 `isInspection`，Goal 续跑用 `clientRequestId` 去重。
- **UNKNOWN 语义未被破坏**：检查回合用 `isInspection` 强制只读（`ChatRequestAssembler` 的 `recoveryOnly` 过滤 + `ChatToolCalls.recoveryEffectRejection`），`GoalRunSettlement` 对检查回合走 `WakeFailed`。唯一破坏点是 P1-6 那条直接写 `FAILED`。
- **`ask_user` 的答案绑定是安全的**：`question:<sessionId>:<turnId>:<toolCallId>` 的 id 与 `require(pending(...).any { it == question })` 保证答案只绑定到发起反问的那次调用。
- **QuickJS 的 slot/句柄/文件无泄漏**：服务端 slot 经 `finally → markUsed()` 回收，PFD/临时文件在客户端与服务端各自 `finally` 关闭。

以下是中低严重度的真实问题或契约不一致：

| # | 位置 | 问题 |
| --- | --- | --- |
| 1 | `feature/browser/.../engine/WebPageTools.kt:54-59` | `injectEruda` 用协议相对 URL `//cdn.jsdelivr.net/npm/eruda` 加载**未锁版本、无 SRI** 的第三方脚本。若当前页面是 `http://`，会以 http 加载子资源（不触发 mixed-content 拦截），同网攻击者可替换脚本在页面上下文执行任意 JS。修复：锁精确版本 + 强制 https，或打包为本地 asset 经 `WebViewAssetLoader` 加载 |
| 2 | `extensions/skills/.../connector/index/SignedConnectorIndexVerifier.kt:29` | `allowDowngrade: Boolean = true` 默认宽松，sequence 回退仅标记 `isDowngrade` 不拒绝。当前生产零调用点，但未来接线并沿用默认值即构成版本回滚攻击面。修复：默认改 `false` |
| 3 | `runtime/proot-app/.../ProotCallerVerifier.kt:11-16` | KDoc 声称"platform signature permission already guarantees…"，但该模块 manifest 无任何 `<permission>` 声明，真实保护只来自 `exported="false"` + 同 UID 校验。属文档与实现不符；风险在于误导后续把组件改成 `exported="true"` |
| 4 | `runtime/cli-app/.../CliCallerVerifier.kt:15-21`、`ProotCallerVerifier.kt:32-40` | 通过 `callingUid == myUid()` 之后，包名与证书比对必然成立，是恒真的自校验；KDoc 描述为"narrows further"不准确。代码作为纵深防御应保留，但文档需修正 |
| 5 | `feature/browser/.../engine/UserScriptEngine.kt:68-82` | `wrapScript` 的 `safeName` 只转义单引号，未处理反斜杠/换行。当前 `updateScripts(...)` 无生产调用点（脚本恒为空）故不可利用，但接入用户脚本导入后 `@name` 属不可信输入。修复：改用 JSON 编码注入 |
| 6 | `app/.../mcp/oauth/OAuthCredentialCodec.kt:47` | 硬编码 `require(version == 1)`，无 `when(version)` 迁移分支 |
| 7 | `extensions/mcp/.../oauth/McpOAuthMetadata.kt:45` | `supportsS256()` 把"字段未声明"当作"支持 S256"（RFC 8414 语义应为未声明）。因全流程强制 S256 且无 `plain` 降级，失败是 fail-closed，风险有限 |
| 8 | `app/src/main/AndroidManifest.xml:105-119` | `McpOAuthCallbackActivity` 是除 `MainActivity` 外唯一 `exported="true"` 组件（OAuth 回调必需）。防护已到位（一次性 attempt + `matches` 全等比对 + 异常统一收敛），但构成集中 DoS 面 |

---

## 六、构建与工程卫生

- **C/C++ staging 目录告警**：`app/build/cxx` 位于临时构建目录内，每次 clean 后不保留。建议改用默认 `app/.cxx` 或临时目录外的路径。
- **配置期解析 classpath**：`runtime:quickjs` 在 configuration time 解析 `debugRuntimeClasspath`（Gradle 明确报为 build performance issue）。
- **Lint 规则被放宽 6 项**：`config/lint/lint.xml` 忽略了 `AndroidGradlePluginVersion`、`GradleDependency`、`NewerVersionAvailable`、`OldTargetApi`、`PluralsCandidate`、`UnusedResources`。其中 `UnusedResources` 被关闭意味着资源删除不会被自动发现（`EmptyDestination` 这类死代码正是靠人工发现）。
- **`build/` 下堆积大量历史归档**：`build/worktree-archives/`、`build/worktree-retirement/2026-09-13/`、`build/main-verification/`、`build/ci-integration-2026-09-21/` 等，整个 `build/` 约 3.6 万个文件。都在 gitignore 内，不影响提交，但会污染任何基于 `build/**` 的统计（聚合测试结果时会把归档算进来，实测把 4614 个用例虚报成 29878）。
- **环境前置**：本机 PATH 缺少 `rg` 与 `java`（`rg` 实际在 `/opt/homebrew/bin`，JDK 17 在 `/opt/homebrew/opt/openjdk@17`）。未显式配置时 `check-secrets` 会因缺 `rg` 直接失败、Gradle 无法启动。这不是项目缺陷，但会让门禁在干净环境下产生假失败。

---

## 七、门禁与验证边界

### 7.1 实跑结果

| 门禁 | 结果 |
| --- | --- |
| `./gradlew detekt spotlessCheck` | ✅ 通过（46s） |
| `./scripts/ci-run-gate.sh --source` | ✅ 通过（10 组 python 单测全 OK；首跑曾因沙箱临时错误 `EEXIST` 失败一次，重跑通过） |
| 单元测试（重构核心 7 模块实跑） | ✅ **938 用例 / 0 失败**：`tools/framework` 220、`core/agent` 195、`core/policy` 187、`core/model` 161、`provider/api` 116、`extensions/mcp` 52、`extensions/plugin` 7 |
| 单元测试（磁盘上全部结果） | 4620 用例 / 0 失败 / 8 跳过 |
| Android Lint | 92 Hint / 0 warning / 0 error（`abortOnError` + `warningsAsErrors` 已开） |
| `check-docs.sh` | ⚠️ 当前失败，**原因来自并行工作线**（见下） |

### 7.2 无法完成的验证（环境限制，非项目缺陷）

**全量 `./gradlew test` 无法在本环境复跑**：

- 沙箱**无外网**：`curl https://dl.google.com/...` 返回 `HTTP=000`；Gradle 报 `Remote host terminated the handshake`。
- 本地 Gradle 缓存**缺件**：`:feature:files:debugUnitTestRuntimeClasspath` 锁定的 `androidx.annotation:annotation:1.8.1` 不在缓存（缓存里只有 `1.9.1`/`1.10.0`/`1.3.0`），`--offline` 同样失败；`runtime/terminal-renderer` 还缺 `kotlinx-serialization-core:1.7.3`、`kotlinx-coroutines-android:1.9.0`。
- 这些版本由**各模块自己的 `gradle.lockfile`** 钉住（不同 configuration 合法地钉不同版本），重构提交**未修改任何 lockfile**（`git show b51687e0 -- '*lockfile*'` 为空），所以不是重构引入的回归。
- `./scripts/check-lockfiles.sh` 也因此跑不完（被 SIGTERM）。

**需要在有网络的环境执行 `./gradlew test` 与 `./scripts/check-lockfiles.sh` 才能闭合。**

### 7.3 并行工作线造成的门禁失败（非本报告引起）

审查期间 `check-docs.sh` 两次被并行工作线改变状态：

- 一次由失败转通过（`docs/completion-records/HXA-231.md` 补上 `决策记录：`）。
- 一次由通过转失败，当前失败项**全部来自其他工作线的在改文件**：
  - `docs/development/implementation-guide.md` 缺少 4 段门禁要求的契约文本（`docs/development/status.md`、`已有完成记录的 HXA 不得重复实现`、`只有用户明确要求建立持久 Goal 时才创建`、`verification matrix`）；
  - `docs/architecture/harness-refactor-plan.md`、`docs/bug-fixes/2026-09-29-autonomous-recovery.md`、`docs/development/tasks/HXA-232.md` 三处指向的 `docs/research/harness-human-intervention-audit-2026-09-29.md` 不存在。

本报告自身的链接均解析正常，未被列为失败项。

### 7.4 未覆盖范围

- 未做设备验收（`not requested`）。
- 未逐行核查：`tools/files/` 的归档解压与符号链接处理、`runtime/proot-app/` 的安装校验、`extensions/mobile-use/`、`app/.../network/LanScopeStore` 的用户创建路径。
- 静态走查 + 门禁通过不等于功能/安全验收；AndroidTest 仅编译未运行。

---

## 八、优先级建议

### 第一梯队：能确定性崩溃或造成错误终态

1. `GoalReducer.kt:210` 补 `nextCheckpoint = null`（P0-1）。
2. `TurnExecutionDriver` 区分启动失败与取消（P0-2）。
3. `AutomaticRecoverySettlement` 改走 `GoalReducer.reduce`（P1-6）。
4. `SessionRuntimeTools.kt:15` 给 `ask_user` 补 metadata 豁免（P0-3）。
5. `ProotJobRunner.kt:300-304` fd 双重关闭（P1-9）。

### 第二梯队：并发与准入正确性

6. `ToolBindingStore.admit` 内的 `approvals.consume` 移到锁外（P0-4）。
7. 排他槽释放与 worker 真实退出对齐（P0-5）。
8. `ChatService` 的 `turnGate` 内移出阻塞 Room 事务（P1-7）；`submissionAttempt` 补日志（P1-8）。
9. 工具配置路径纳入 `submissionGate`（P2-22）。

### 第三梯队：恢复链路健壮性

10. 自动收集失败后可重试，并区分"运行中"与"失败"（P1-11、P1-12）。
11. 反问答案合并 `selected + custom`（P1-10）；schema 加 `uniqueItems`（P2-23）。
12. Provider 分代粒度、能力快照 fail-open、`create` 旁路（P2-19、P2-20、P2-21）。

### 第四梯队：性能与技术债

13. 8 处 scope/executor 的 `cancel()`/`shutdown()`（第二节全部）。
14. `MainActivity`/`BrowserScreen` 改用 `collectAsStateWithLifecycle` 并只派生所需字段；`ArtifactsScreen` 图片解码移出组合期；`ConversationSection` 分组加 `remember`（第三节 1-4）。
15. 全量 `readBytes()` → 流式哈希/有界读（P2-14、P2-15）。
16. 拆解 `ChatService.kt`（4432 行）；`FakeShellRepository` 重命名；删 `EmptyDestination`；`ModePolicy`/`GoalForeground`/`DeveloperRuntime` 要么接线要么降级为 `internal`。
17. 收敛三份 `formatBytes`；合并重复渲染块；84 处 `mutableStateOf<Int>` → `mutableIntStateOf`。
18. `injectEruda` 锁版本；`allowDowngrade` 默认 `false`；`maxAttempts` 死代码处理；修正 `ProotCallerVerifier`/`CliCallerVerifier` 的 KDoc。
19. 清理 `build/` 下的历史归档；修正 C/C++ staging 目录与 quickjs 配置期解析。
