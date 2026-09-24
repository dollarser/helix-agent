# 维度四:核心路径 bug

审查日期：2026-09-24。基线：`3cf89027`，包括 `8aa8ff97` 和现场工作树。现场有 16 个 tracked 修改文件，另有未跟踪的 Root 文件管理实现、测试和审查资料；不能把“16 改动文件”理解为全部差异。下述路径相对 `/Users/dollars/Helix`，行号取当前工作树。

方法：CodeGraph 定位后，以当前源码、调用方、DAO、分支及 Git diff 取证；没有运行 Gradle、设备测试、网络实验或故障注入。CONFIRMED 表示错误分支及后果有完整静态证据，不表示已在设备复现；实际 OOM、Binder 行为、异常注入、外部服务行为另行限定。仓库未写入，报告仅写在 `/tmp`。历史报告是候选来源，不是现状证明。

## 总评(各关键路径一句话风险评级)

| 关键路径 | 评级与结论 |
| --- | --- |
| Turn 发送、修订、排队、重新生成 | 高：普通启动有持久回执和 gate，重新生成绕过修订的原子替换路径，并可能隐藏后续用户消息。 |
| 停止与进程恢复 | 严重：非终态父 Turn 下两个 RUNNING 工具行使全局恢复在事务前失败；停止不能直接丢弃该状态。 |
| 工具批结算与授权 | 高：UNKNOWN 是框架合法结果，却被批次不变量拒绝；未确认模型授予权限或用户审批凭据绕过。 |
| 三种 Provider 流式 | 高：常见半截流和非法终态有拒绝处理，但 Responses 的参数副本没有累计上限。 |
| Room 1..28、内容存储与 GC | 中：迁移链完整，未找到具体迁移断链；GC 无生产触发，孤儿内容不能按新增功能预期回收。 |
| 产物、工作区 | 中：生产注册事务已消除历史 refresh→insert 缺口；文件与数据库不是跨介质原子事务，未提交 Root 后端又引入整文件堆物化。 |
| 压缩与预算 | 中：压缩在步骤边界、工具批结算之后；BudgetContinuation 仍有全历史查询，但历史“必然 O(T²)”结论不成立。 |
| A2A | 中：有持久 taskId、CAS 和不重发边界；取消/流回调竞态、同步网络调用中的取消延迟仍需时序验证，未列作确定 bug。 |
| MCP | 中：OAuth 结果事件可丢失；缺少应用级整体握手 deadline，但不能仅凭应用未写 withTimeout 就证明 SDK 永久等待。 |
| Runtime IPC | 高：QuickJS 实际输出上限与契约不符；PRoot 异常退出清理有孤儿进程风险，主进程死亡与租期机制不能替代异常路径清理。 |

计数：CONFIRMED **11 条：P0 1、P1 6、P2 4**；PLAUSIBLE **6 条：P0 0、P1 3、P2 3**。同根的历史发现合并计数；历史排除项及潜在改进不重复计数。

## CONFIRMED 问题(按严重度排序)

### C01 — P0：并行批次进程死亡后，全局启动恢复重复失败

- **根因机制**：生产调度已支持并行读，但恢复输入对象仍强制“每 Turn 最多一个 RUNNING”。断言发生在构造恢复计划阶段，早于事务及终态父子对账。
- **代码路径**：`app/src/main/kotlin/com/helix/app/agent/AgentLoop.kt:407-410` 开始批次；`tools/framework/src/main/kotlin/com/helix/tools/framework/ToolScheduler.kt:239-249` 可提交多个不冲突调用；`app/src/main/kotlin/com/helix/app/chat/ChatToolCalls.kt:423` 将各调用持久化为 RUNNING。重启进入 `app/src/main/kotlin/com/helix/app/HelixApplication.kt:68-81` → `app/src/main/kotlin/com/helix/app/recovery/RecoveryCoordinatorApp.kt:75` → `:135-147` → `core/agent/src/main/kotlin/com/helix/core/agent/RecoveryCoordinator.kt:29-36`。
- **具体状态序列**：T 进入 RUNNING_TOOL；A、B 两个获准并行的读调用均已写 RUNNING；在批次结果结算前 SIGKILL。重启时 `TurnDao.listActive`（`core/storage/src/main/kotlin/com/helix/core/storage/dao/TurnDao.kt:54-57`）选中 T，构造 `PersistedTurn` 时计数为 2，抛 `IllegalArgumentException("at most one RUNNING tool call per turn (serial execution)")`。不是历史报告部分文字所称的 ISE。
- **为什么每次启动失败**：`recover():75` 的参数扫描已抛出，`:78` 的事务完全没有执行；`:81` 的“终态父 Turn 下未结算子调用”补救也没机会运行，且 T 本就不是终态父。Application 只记录日志，不改行；下一次扫描仍读到同一组 RUNNING 行。
- **用户与数据影响**：T 保持 RUNNING_TOOL、调用保持 RUNNING，任务投影仍显示运行；`onRecoveryCompleted()`、排队输入 park、Goal park、提醒对账均被跳过。一次坏 Turn 可阻断整个此次恢复，不仅阻断自身。修订的 `TurnCoordinator.kt:602-606` 全会话终态检查也不通过。
- **手动清理边界**：停止走 `ChatService.kt:2659-2709`，重启后没有内存 active owner，进入只允许 INTERRUPTED 的分支并抛错，因此普通 Stop 不能修复。不是“任何办法都无法清理”：隐私永久删除会话的 `ChatService.kt:942-945` 只检查内存 owner，`PrivacyDeletionService.kt:36-43` 可删除此会话；这是破坏性删除，不是结果恢复。新会话也不等于清除全局恢复毒化行。
- **严重度理由**：不可自愈的持久状态，影响核心恢复及其他会话/Goal，P0。
- **修复方向**：恢复事实与报告改成 RUNNING 调用集合；接受合法并行快照；逐 Turn 隔离坏数据，并让失败可见；保留“不自动重放”原则。回归应固定非终态父 + 两个 RUNNING，连续运行恢复两次，并验证其他 Turn/Goal 仍能恢复。
- **提交判断**：`8aa8ff97` 没改这些文件；工作树 ChatService/SessionDao 的 INTERRUPTED 模型选择变更也不触达该断言。**历史 P0 仍成立。**

### C02 — P1：重新生成先隐藏历史，再尝试启动，替换失败没有回滚

- **根因与路径**：`app/src/main/kotlin/com/helix/app/chat/ChatService.kt:2591-2602` 单独执行 supersede、刷新 UI，然后调用 submitTurn；`core/storage/src/main/kotlin/com/helix/core/storage/repository/MessageRepository.kt:92-98` 没有与新 Turn 创建绑定。`ChatService.kt:2800-2801,2834-2838` 可以返回 false，`:3450-3452` 可以拒绝 busy；调用者没有处理返回值。
- **触发场景**：supersede 成功后进程死亡；或入口检查后另一发送先占会话；或 Provider 在检查后变化，使新 Turn 准入拒绝。
- **错误结果与范围**：旧回答及之后有效消息被持久标记 superseded，普通历史查询过滤它们（`MessageDao.kt:18,49-56`），但没有替代 Turn/回答。`supersededBy=requestId` 可指向从未创建 Turn 的请求。审计/原始行仍在，不能称为正文物理删除；UI 没有此次失败的自动恢复。
- **严重度理由**：核心重新生成操作失败却改变有效历史，P1；不把保留的审计正文说成永久丢字节。
- **修复方向**：仿照 `TurnCoordinator.start():598-631` 的修订事务，在稳定会话和最新目标校验下，将 supersede、新 Turn、请求回执一起提交；启动拒绝前不改变有效历史。

### C03 — P1：最新“回答”的重新生成会隐藏其后的未获回答用户消息

- **根因与路径**：`app/src/main/kotlin/com/helix/app/ui/ConversationSection.kt:318-322` 只要求目标是最后一个非 user 消息，未要求它属于最新用户输入；`ChatService.kt:2568-2581` 只验 session/role 并找原 USER；`:2592` 调用从该 assistant.sequence 起的范围 supersede；`core/storage/src/main/kotlin/com/helix/core/storage/dao/MessageDao.kt:49-56` 将范围内全部有效消息隐藏。
- **可确定触发**：U1→A1 成功；再发 U2，Provider 在产生任何 assistant 消息前失败；此时不在发送中，最后非 user 仍是 A1，重新生成按钮可用。点击 A1 后，U2 也被 supersede，重跑的却是 U1。
- **影响**：用户只是重生成上一回答，后续已提交问题从有效历史及后续模型上下文消失；与 C02 不同，此问题在替换启动成功时也发生。过期 UI 回调还可传更早 assistant，服务端没有 latest/superseded 守卫。
- **严重度理由**：错误选择替换范围和用户输入，P1。
- **修复方向**：定义“最新可重生成回答”与最新用户输入的绑定；服务端在同一事务验证目标仍有效、目标之后没有新 USER/新输入消费，并限制替换范围。更早消息按 fork 或明确版本操作处理。

### C04 — P1：合法 UNKNOWN 结算阻止全批结果进入模型历史，Turn 归类 INTERNAL

- **根因与路径**：`tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDispatcher.kt:896-908,958-963` 会合法返回 requiresReview；`app/src/main/kotlin/com/helix/app/chat/ChatToolCalls.kt:268-290,313-319` 先持久结算，再把 slot 标 UNKNOWN；`app/src/main/kotlin/com/helix/app/agent/TurnCoordinator.kt:125-128,297` 在消息事务前拒绝 UNKNOWN；`AgentLoop.kt:410-419` 无专用分支；`ChatService.kt:3703-3712` 归为 FAILED/INTERNAL。
- **触发**：一个正常完成的调用和一个超时/输出 schema 错误且 requiresReview 的调用同批，用户不必点击停止。由 timeout 单独即可走到异常；不依赖取消协程的竞争顺序。
- **影响**：tool_results 中已有结果，但包括成功 slot 在内的整批 TOOL 消息都未在 `TurnCoordinator.kt:306-314` 写入。用户看到内部错误，实际需复核的事实被混入程序错误。结果并非从结果表消失，应称“模型可见回填缺失”。
- **严重度理由**：合法核心结果触发错误终态且丢失模型回填，P1。
- **修复方向**：分离 PENDING 与 UNKNOWN；已完成结果先按原序原子回填，UNKNOWN 进入明确复核/中断终态，不开启下一请求。取消必须按持久 CANCELLING 优先处理。异常类型是 require 产生的 IAE，不是 ISE。

### C05 — P1：Responses 参数累计缓冲无上限，上层超限不会停止输入

- **根因与路径**：`provider/openai-responses/src/main/kotlin/com/helix/provider/openai/responses/ResponsesStreamDecoder.kt:275,291-305` 为每个调用建 StringBuilder 并无条件追加全部 delta，直到 done 才取出。`app/src/main/kotlin/com/helix/app/agent/ModelStreamState.kt:134-141` 的上限只保护另一份 arguments 并设置 errorCode；`AgentLoop.kt:365-372` 继续 collect。`provider/api/src/main/kotlin/com/helix/provider/api/WireModelProvider.kt:131-135` 只根据 decoder.protocolEnded 停止读取，未得到上层超限反馈。
- **触发**：自建/异常 Responses 服务对一个已开始函数调用持续发送合法的小块 arguments.delta，不发 done；各 SSE 事件可独立满足解析限制，累计参数超上层限制。
- **影响**：解码器仍保留完整累计文本，参数上限不能约束实际内存；可持续增加主进程堆占用。超时最多限制持续时间，不提供累计字节上限。实际 OOM 门槛依设备和速率，本审查未复现 OOM。
- **严重度理由**：网络输入可绕过设计的累计资源上限，属于核心流资源无界增长，P1。
- **修复方向**：decoder 自身增加逐调用及总参数字节上限，超限清空缓冲、结束流；让 accumulator 的不可恢复错误向传输传播取消。最终 done 参数也先限长再拼接/比较。

### C06 — P1：JavaScript 工具宣传 256 KiB，实际超过 64 KiB 就失败

- **路径与机制**：`runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/tool/CodeJavascriptRunTool.kt:94,222-230` 使用默认 256 KiB 输出限制但不设置 outputFile；`runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/JsExecutionClient.kt:53` 默认 null；`JsExecutionService.kt:380-388` 在无 PFD 且超过 PARCEL_INLINE_MAX_BYTES 时返回 OUTPUT_LIMIT。
- **触发**：`return "x".repeat(70000);`，序列化后仍小于 256 KiB，执行正常却因内联阈值失败。
- **影响**：所有通过工具入口返回 64–256 KiB 的结果；错误文案 `CodeJavascriptRunTool.kt:311` 还说超过 256 KiB，按该提示缩小到 100 KiB 仍失败。
- **严重度理由**：正常契约内输出无法完成，P1，不是静默截断。
- **修复方向**：生产工具创建私有 outputFile/PFD，成功后有界读回并 finally 清理；或者同步降低工具 schema/说明/错误和验收契约。

### C07 — P1：未提交 Root 文件后端将流式传输变成无界整文件堆缓存

- **路径与机制**：`app/src/main/kotlin/com/helix/app/AppFileServices.kt:156-168` 接入 root backend；`app/src/main/kotlin/com/helix/app/files/ManualFileTree.kt:26-38,98-105` 以流接口复制/验 hash；新文件 `app/src/developer/kotlin/com/helix/app/root/RootFileModule.kt:145-148` 实际先 readAllBytes，`:160-167` 写端用 ByteArrayOutputStream，close 再 toByteArray。`tools/root/src/main/kotlin/com/helix/tools/root/RootFileAccessor.kt:143-147` 先收集整份 Base64 行、join，再解码。
- **触发**：developer 文件管理器复制/移动大型 Root 文件，或向 Root 目标复制大型普通文件。`ManualFileOperations.kt:40-78` 没有文件尺寸准入，底层 read 在返回 InputStream 前也不能响应上层块间取消。
- **影响**：主进程同时持有 Base64 文本、拼接文本、解码数组，写端又可能复制数组；hash 复验再次整文件读取。确定的是内存复杂度随文件增长且取消检查被延迟，实际 OOM/卡顿程度依设备，不宣称已测得峰值。
- **严重度理由**：已接入的手动文件核心流失去流式资源界限，P1。
- **修复方向**：Root 进程与应用间使用真正流/PFD或私有暂存文件，分块读写和 hash；传播取消，并明确字节/磁盘配额。不要仅给 UI 预览限额，因为复制走另一条路径。

### C08 — P2：孤儿 GC 没有生产触发入口

- **机制与路径**：`app/src/main/kotlin/com/helix/app/privacy/PrivacyDeletionService.kt:89-90` 只封装 `HelixStorage.collectGarbage():225-230`，全仓生产调用检索没有 cleanOrphanFiles 的调用者，也没有其他生产 collectGarbage 入口。
- **触发**：`app/src/main/kotlin/com/helix/app/chat/ToolSettlementWriter.kt:24-25` 写正文后数据库事务失败，或进程在文件发布与索引提交之间退出；已存在孤儿内容经过一小时仍不会自行回收。
- **影响**：新增提交并未形成端到端孤儿回收功能；正文文件占用随失败积累。不能说 supersede 本身产生孤儿：旧 revision 仍保留引用，正确 GC 本来也不该删除它。
- **严重度理由**：维护功能不可达，通常非立即核心失败，P2；不能仅凭存在 collector 宣称旧孤儿问题已修复。
- **修复方向**：先解决 P06 中并发协议，再接入明确的闲时/用户触发清理入口，记录结果和有限扫描预算。

### C09 — P2：聊天工具时间线先全量查库、最后才截断

- **路径**：`app/src/main/kotlin/com/helix/app/chat/ChatScreenProjection.kt:43-78` 查询全部 Turn，再逐 Turn 查询调用，再逐调用查询结果，最后 takeLast；`ChatService.kt:3868` 起的刷新流程会反复使用投影。
- **触发**：长期会话积累大量历史工具调用，继续打开/刷新会话。
- **影响**：显示容量有限而读取成本不受容量约束，约为 O(T+C) 次行遍历及 N+1 查询，而非“只读最新若干项”。延迟/ANR 的设备表现未实测。
- **严重度理由**：次要但可增长的交互退化，P2。
- **修复方向**：DAO 按 session 关联 turns/tool_calls/tool_results，先 ORDER BY/LIMIT，再构造投影；live overlay 单独合并。BudgetContinuation 的全历史 settled 判定改 EXISTS 查询，避免逐 Turn 取工具列表。

### C10 — P2：QuickJS 拒绝已解析请求时没有显式关闭接收的 PFD

- **路径**：`runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/JsExecutionService.kt:110-133` 已得到 envelope 后，validation 拒绝及 busy/used 分支直接返回；关闭 PFD 只在 `:181-185`，这两个分支未进入。
- **触发**：源/输入走 PFD，绑定耗时使 deadline 到期，被 validation 拒绝；或直接协议客户端重复使用同一实例。
- **影响**：service 端复制的描述符没有确定性释放；client finally 关闭自己的副本不能替代 service 关闭。一次性 isolated 实例被回收后可释放，不能称为永久/无限系统 FD 泄漏。畸形 parcel 部分读取的泄漏不能仅凭这一段断言，未扩大结论。
- **严重度理由**：有界生命周期中的资源清理缺陷，P2。
- **修复方向**：取得 envelope 后统一所有权 finally；验证拒绝、busy 也 close，避免正常执行路径重复所有权不清。

### C11 — P2：OAuth 回调结果通过 replay=0 事件流发送，离页后的完成反馈不可恢复

- **路径**：`app/src/main/kotlin/com/helix/app/mcp/oauth/McpOAuthCoordinator.kt:49-50,125-134` 用默认 replay=0 SharedFlow 和 tryEmit；`app/src/main/kotlin/com/helix/app/connector/ConnectorSection.kt:261-282` 只在页面订阅到 Success 后自动 testOAuth，Failure 只写局部 oauthError。
- **触发**：授权期间页面离开 composition，回调在没有订阅者时完成，之后回到 Connector 页。
- **影响**：成功 token 已保存，可由 `:252` 重新探测，不是凭据丢失；但成功后的自动测试不会补执行，失败原因也消失。extraBufferCapacity 不能为无订阅者保存 replay=0 的事件。
- **严重度理由**：登录反馈/后续测试链断开，可手动重试，P2。
- **修复方向**：以 serverId+attemptId 保存可重读的状态/结果，页面挂载读取并幂等消费；不要只扩大临时 event buffer。

## PLAUSIBLE 问题

### P01 — P1：PRoot 运行期异常可能留下无人管理的进程组

- **机制与路径**：`runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt:449-476` 启动进程、登记 watchdog 后调用通知等外部设施；`:526-538` catch 在 process.isAlive 时仅取 null exit 并 terminalFailed；`:539-546` 取消 watchdog、移除 liveJobs/窗口和 owner，没有 kill。
- **触发假设**：进程仍存活时 postRunning/记录 I/O/waitFor 抛异常。清理缺口确定，但本轮没有触发真实平台异常，故 PLAUSIBLE。
- **影响与定级**：任务可能显示 FAILED，命令仍继续访问文件/网络且失去常规取消和租期控制；资源不可控，P1。service 重启后的扫尾不等于当前运行实例已回收。
- **修复方向**：从 spawn 起拥有 process/PGID；异常和最终清理中先终止并验证组停止，再发布可证明终态；清理操作自身异常不得跳过后续清理。HXA-196 正常租期及主进程 SIGKILL 验收不能覆盖此异常分支。

### P02 — P1：重新生成等待附件检查期间切换会话，可能把原请求重跑在新会话

- **路径**：`ChatService.kt:2565` 捕获会话 A；`:2589` 是 suspend 附件检查；`:2592` 修改 A 历史；`:2596-2602` 未传 expectedSessionId；`submitTurn():2800-2820` 重新取当前会话 B，却携带 A 的 retryTurnId。`ChatHistoryBuilder.kt:156-159` 在 B 历史找不到 A 的 USER 时只返回 B 的 rest。
- **触发假设**：检查期间切换至使用相同 Provider 的 B，B 满足启动/上下文准入。异 Provider 可被后续 gate 拦住，但仍已隐藏 A 历史。
- **影响与定级**：A 回答消失；B 可能按自己的历史发起无关 Turn，或失败；P1。未声称必然跨会话泄露 A 文本，因为历史构造按 B 查询。
- **修复方向**：以明确 sessionId 贯穿命令与原子替换；附件检查后在 gate 内重验，提交前不修改 A。需要用屏障固定切会话时序验证。

### P03 — P1：MCP 缺少应用级整体握手/元数据 deadline

- **路径**：`extensions/mcp/src/main/kotlin/com/helix/extensions/mcp/McpHandshake.kt:78-102` 覆盖 endpoint、connect、snapshotMetadata、close 却无整体 deadline；`McpHttpClientFactory.kt:16-36` 未设置 callTimeout；`SdkMcpClientFacade.kt:28-39` 使用 SDK ClientOptions 默认值。
- **触发假设**：异常服务持续发送小 SSE keep-alive，或多次元数据请求/清理停滞。`McpOkHttpResponseLimit.kt` 是单事件/JSON 字节限制，不是总时间限制。
- **影响与定级**：设置页操作长时间 busy、连接资源占用，P1 候选。
- **证据边界**：没有展开 SDK 0.15.0 内部 timeout 实现，也未网络复现，因此历史“没有任何超时、initialize 必然永久挂起”不应继续当 CONFIRMED。应用没设置 timeout 并不证明依赖没有默认 timeout。
- **修复方向**：为整个测试定义有界 deadline，覆盖发现、初始化、分页与关闭，并提供取消；验证 SDK 的默认超时到底包住发送还是仅等待响应。

### P04 — P2：QuickJS bind 消耗执行 deadline，并可能被误归类为非法请求

- **路径**：`runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/JsExecutionClient.kt:106-145` 在 transport 与 bind 前固定 deadline；`JsServiceValidation.kt:20` 到期返回 deadline already expired；`JsExecutionService.kt:116-120` 映射 REQUEST_REJECTED。
- **触发假设**：采用允许的 100 ms 限制或冷启动慢于限额，服务开始处理时已过期。
- **影响与理由**：脚本尚未执行就失败，且预算到期显示为请求拒绝；P2。生产工具 `CodeJavascriptRunTool.kt:222` 固定 DEFAULTS=10 秒，不支持模型设 100 ms，故撤回历史“所有 JS 小超时必灭/P0”外推。实际冷绑定延迟未测。
- **修复方向**：明确总调用 deadline 与 engine budget 的关系；若限额本就是含启动总时间，保留计时但返回 TIMEOUT；若契约保证 engine 时间，bind 后设置 engine deadline，同时另设整体上限。

### P05 — P2：QuickJS 大输入在 isolated 进程中先完整包装，后检查大小

- **路径**：`runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/JsExecutionService.kt:323-332` build 完整程序后 toByteArray；`JsAbiAssembly.kt:137-171,188-191` 字符串拼接和 6 倍最大展开预算；`JsExecutionLimits.kt:73-74` 默认 2 MiB、允许上限 32 MiB。
- **触发假设**：底层客户端把输入提高至 32 MiB，输入有大量需要转义的字符，且设备堆紧张。
- **影响与理由**：JVM 字符串及副本的峰值不受 QuickJS heap 限制，可能导致 isolated 服务 OOM/失败，P2；这是 isolated 进程，历史“host 堆 150–250 MiB”不能照抄。当前 MAX_SOURCE+6*MAX_INPUT+overhead 没有 Int 溢出的证据。
- **修复方向**：包装前计数和容量准入，减少副本，按进程内存预算限制输入，而非只限制 native QuickJS heap；实测后再写峰值。

### P06 — P2：GC 接线后，旧内容复用能绕过一小时宽限并被并发删除

- **潜在路径**：`core/storage/src/main/kotlin/com/helix/core/storage/content/ContentStore.kt:37-69` 对已存在相同 hash 的内容复用且不刷新 mtime；`StorageGarbageCollector.kt:71-74,110-117` 先按旧 mtime 准入，查询三表无引用后独立 delete；`ToolSettlementWriter.kt:24-28` 文件写入/复用与结果索引提交分离。
- **具体交错**：旧孤儿文件 F 已超过一小时；GC 查无引用；活跃调用复用 F 并提交 contentRef；GC 随后 delete F。或者复用后、提交前 GC 删除。随后正文读取报 content not found。
- **当前边界与定级**：C08 确认今天没有生产触发者，因此不能宣称当前活跃 Turn 已能被 GC 误删。作为新增 API 的潜在缺陷计 P2，接入后若无互斥可升级为数据丢失 P0 风险。
- **修复方向**：内容发布/引用提交与 GC 用共享锁、pin 或两阶段 tombstone 协议；单靠 mtime 宽限不是事务保护。只在“查询引用”上加 Room 事务仍不能约束文件侧复用。严格限定扫描根和路径布局，避免未来调用方传入宽根。

## 历史审查发现的状态复核表

编号前缀 D4 指 `reviews/2026-09-24/2026-09-24-code-review.md` 的维度四；R 指 `REVIEW-2026-09-24.md` 的 2.1。状态针对原断言，必要时区分“代码现象”与“夸大的后果”。

| 编号 | 原结论 | 当前状态 | 当前证据与严重度校正 |
| --- | --- | --- | --- |
| D4-P0 | 两 RUNNING 使启动恢复永久失效 | **仍成立** | C01；RecoveryCoordinator.kt:34 在恢复事务前抛 IAE；普通 Stop 不能修复，但隐私删除会话可清除坏行。 |
| D4-P1-1 / R6 | UNKNOWN→ISE→FAILED/INTERNAL，成功结果丢失 | **仍成立** | C04，P1；是 IAE；丢的是 model-visible 消息，不是已持久 tool_results。超时路径无需取消竞态即可到达。 |
| D4-P1-2 | Goal 时长预算被当作用户取消 | **不成立** | GoalTimeBudget.kt:259 设置 GOAL_BUDGET_LIMIT，:214-217 重抛 GoalTimeLimitException；ChatService.kt:3689-3691 专门终局。 |
| D4-P1-3 | turnGate 内 Room 事务构成核心故障 | **不成立**（作为已确认故障） | ChatService.kt:2659/2671、3422/3481 的锁内事务现象仍在；未构造相反锁序、死锁或用户错误结果，不按 P1 bug 计。 |
| D4-P2-1 | ResourceKeyExtractor 未接线、scopeIds 不参与冲突 | **仍成立**（实现事实） | ToolScheduler.kt:54 默认 NoResourceKeys，EffectFootprint.kt:38-42；写/代码/Root 和 runtime lane 仍保守串行。缺少具体错误读时序，不单列并发 bug。 |
| D4-P2-2 | Proot submit 拒绝导致 PFD 永久泄漏/PENDING | **不成立**（所举路径未证实可达） | ProotJobRunner.kt:102-105 无界队列单线程 executor，未发现 shutdown；不能把假定的 RejectedExecutionException 当常规复现。 |
| D4-P2-3 | OAuth replay=0 丢回调反馈 | **仍成立** | C11，P2；凭据持久化不丢，丢的是事件驱动测试与失败反馈。 |
| R1 | bind 前计时使 QuickJS 小预算失效，P0 | **仍成立**（计时事实，P0 不成立） | P04；生产工具默认 10 秒，100 ms 来自底层允许参数，耗时未测；P2 PLAUSIBLE。 |
| R2 | 实际 64 KiB、契约 256 KiB | **仍成立** | C06，P1；无 outputFile 的生产调用已核实。 |
| R3 | proot 异常不杀进程组 | **仍成立**（缺失清理） | P01，P1 PLAUSIBLE；实际平台异常未注入，不能宣称已复现孤儿进程。 |
| R4 | 未 ACK 终端记录永久锁死容量 | **不成立**（“永久锁死”） | ProotTerminalHost.kt:42-44 确实计未 ACK；DeveloperManualTerminal.kt:49-60 可列出持久绑定，:109-119 query+ACK 结算，释放入口存在。未 ACK 证据保留本身不足证明 bug。 |
| R5 | MCP testConnection 无任何超时、永久等待 | **不成立**（绝对结论证据不足） | P03：应用整体 deadline 缺失仍成立；SdkMcpClientFacade.kt:30-39 委托 SDK 默认 ClientOptions，未验证依赖内部，降 PLAUSIBLE。 |
| R7 | QuickJS 拒绝分支 PFD 泄漏 | **仍成立** | C10，P2；确定性 close 缺失，限定到 isolated 生命周期。 |
| R8 | outputFile 在服务验证前被 truncate | **仍成立**（底层 API） | JsTransportPreparation.kt:54-64 的 MODE_TRUNCATE；生产工具未提供 outputFile，未作为当前产品新增计数。调用者复用已有文件才有数据影响。 |
| R9 | oneway EXECUTE 必然崩 isolated 进程 | **不成立**（崩进程未证实） | JsExecutionService.kt:73 确实在 try 外 requireNotNull(reply)，但异常抛出不等于 Android Binder 必然终止进程；生产 client 的 :322 transact flags=0。不计确定崩溃。 |
| R10 | Skill sessionOverrides 无界增长 | **未纳入本轮裁定** | 本轮重点是指定的 Runtime #1/#7/#9/#12 和前六条；没有完整追踪 Skill 会话清理生命周期，不继承为发现。 |
| R11 | deadline 后取消被报 TimedOut | **不成立**（作为缺陷） | JsExecutionService.kt:423-444 明确 deadline-first；已超过限时再取消并不能自动证明 timeout 是错误终态。 |
| R12 | input 包装导致 host 堆放大/可能溢出 | **仍成立**（先物化后检查） | P05；进程归属、默认输入、未证实的峰值和 Int 溢出均已修正。 |
| R13 | 解压失败留下部分文件 | **未纳入本轮裁定** | 没有重新追踪整个 ArchiveCodec 文件发布/清理链，不继承严重度或复现结论。 |
| 历史 Workspace | refresh→insert 非原子 | **已修复**（生产调用链） | ToolArtifactRegistrationSink.kt:29-40 withTransaction 包住 ArtifactRepository.kt:51-60；INSERT OR IGNORE 和稳定 ID 保留附件。不把 repository 单独无事务当作生产竞态。 |
| 历史性能 | ChatScreenProjection N+1 | **仍成立** | C09，查询在 takeLast 前完成。 |
| 历史性能 | BudgetContinuation 每次必然 O(T²) | **不成立**（复杂度描述） | BudgetContinuation.kt:23-31 先要求该 Turn 为最新；只有最新预算失败 Turn 执行全历史工具查询，单次为 O(T+C)/N+1，不是嵌套 T×T。仍可优化。 |
| 旧压缩 | 摘要准入不足/切断工具批 | **已修复**（已读路径） | 2026-09-22 修复记录与 AgentLoop.kt:135、194-230、407-419 的步骤边界一致；未发现当前压缩在工具执行中发布检查点的分支。 |
| 旧引擎 | 单槽部分提交、终态父子漏恢复 | **已修复**（原局部问题） | ToolSettlementWriter.kt:25-36 同事务写结果/状态/预算；RecoveryCoordinatorApp.kt:81-100 处理终态父子。C01 在更早阶段阻断，C04 是另一个批次消息问题，均未被此局部修复覆盖。 |

对表中 R10/R13 明确保留未裁定，而不强塞“仍成立/已修复/不成立”：本轮没有足够新证据给这两个额外条目三态结论；不计入发现数量。

## 新提交 8aa8ff97 与未提交改动的缺陷评估

**8aa8ff97**：新增重新生成和 GC，不修改恢复断言、工具 UNKNOWN 结算、decoder 或 Runtime，因此不能作为 C01/C04 已修复的证据。重新生成产生 C02/C03，并有 P02 会话切换窗口。requestId 和 Turn id 都走 idGenerator，未找到确定的重复 ID 路径；问题是 supersededBy 可以没有对应成功启动回执，而不是 UUID 冲突。旧 Turn/工具/审计行保留，不应泛称“孤儿 Turn”；但投影用 supersededTurns 隐藏旧工具时间线，不能据此推断旧工具副作用被撤销。重生成还可能再次让模型请求写工具，普通授权路径依然适用，不应把重生成自动等同于无副作用重播。

GC 的三表引用检查包含全部 revision 的 messages、tool_results、session_inputs；没有发现仅因消息 superseded 就误删它的分支。引用查询异常默认当作仍引用，属于保守行为。潜在问题是时间窗不是 pin 协议（P06），且目前完全无接线（C08）。扫描 `walkTopDown().toList()` 也不是有界清理，接线时需要限制批量；这不是当前已发生的活跃 Turn GC 竞态。

**工作树 ChatService / SessionDao**：模型选择现在允许 INTERRUPTED，并把 draft 更新限制到对应 session。这没有让 RUNNING_TOOL 通过恢复，也没有修改 regenerate。`sessionHasNonTerminalTurn` 只看最后 Turn、SQL 则检查所有 Turn 的差异存在；当前 UI 最终 SQL 会拒绝旧活动 Turn，未确认越权更新，不另报新 bug。

**工作树文件管理**：已读取 AppFileServices/DefaultAppContainer/FileManagerService 的 diff，并继续读未跟踪的 developer RootFileModule 和 RootFileAccessor。发现 C07，来源是此次未提交实现，不能归因于 8aa8ff97。Root 手动用户授权与模型授权是两个入口；本报告不把用户主动 Root 文件操作认作模型越权。consumer 实现及设备测试文件存在不证明新路径已验收，本轮未运行测试。

**其他 tracked 变更**：导航、资源及 smoke 测试参数变更不修复核心恢复链。本轮未逐个验证新增 UI 与所有 Root shell 操作，因此不声明整个工作树可发布。

**存储/协议审查边界**：检查了 HelixMigrations 中 1→25 和拆分的 SessionInputMigration 25→26、ConnectorMigration 26→27、RequestManifestMigration 27→28，以及 HelixStorage 的 ALL_MIGRATIONS 注册。没有发现缺中间版本、重复列顺序错误或 destructive fallback。1→2 复制审批表并让旧授权过期、15→16 重建 unique index 后统一 scope 前缀、19→20 转 DENY 再于20→21移除旧表均有匹配依赖。静态阅读不替代 Room schema 校验、磁盘满或升级故障注入；没有自行执行历史记录所称的物理测试。

**授权/取消补充**：SessionPermissionResolver 按可信配置及 effect 判定，DENY、ASK、rm 命中均有独立分支；未见读取模型文本来授予权限。StorageApprovalBroker.kt:158-167 在锁内写审批行并注册 waiter，:233-242 决策在同锁下校验并落库，:180-184 清理 waiter；拒绝、过期与取消不是凭据。正常取消先持久 CANCELLING，再发 cancel/审批唤醒/Job.cancel（ChatService.kt:2671-2735）。这排除了“普通审批必丢唤醒”与“所有取消都 INTERNAL”的泛化，但不能补上 C04 的 UNKNOWN 分支。

**Provider 补充**：Chat `finish()` 无 terminal 时产生 retryable PROTOCOL，DONE 结束 transport；Anthropic 对 open block 终态拒绝；Responses 对 unfinished function calls 终态拒绝。HTTP 非 2xx 与 IO/timeout 在 WireModelProvider.kt:93-145 映射为错误，finally 关闭 body；未见 decoder 自行重试工具副作用。Usage 在 ModelStreamState.kt:92-95 按快照覆盖，没有将重复累计 usage 相加的证据。未知字段/终态后处理仍需协议 fixture 覆盖，本轮发现的是 C05，不把缺覆盖本身算 bug。

**A2A 补充**：A2aTaskRunner.kt:76-77 已有任务走 resumeOnly，:124-135 校验 agent/skill/hash 并按持久 taskId getTask；发送不确定时 :109-111 标 UNKNOWN，不盲重发。A2aTaskRepository 的 updateRemoteState 使用旧 sequence/time/state/delivery CAS，排除了“旧回调无条件覆盖新记录”的候选。流回调与取消都可 persistUpdate，CAS 冲突后是否应自动重新查询需要定向时序验证；目前不能给出确定数据丢失结论。deadline 到时保留远端任务对账是明确行为，不等于承诺远端已取消；同步 get/cancel 网络调用中的取消延迟取决于传输 timeout。远端输出进入工具结果/产物，不直接授予本地权限，未发现绕过 Dispatcher 的后续本地执行路径。

**PRoot #196 边界**：ProotJobRunner.kt:260 仅 owner 非空时注册死亡取消；有租期的 detached 工作与 owner-bound 工作需要区别。正常 watchdog :460-469 按单调剩余时间 kill；手动终端 Host.tick():155-159 检查租期与 idle。历史主进程硬杀验收说明的是特定 detached 路径，不能推出所有异常路径都有停止证明。P01 恰好在 catch/finally 撤销这些保护后留下漏洞。

## 风险汇总(哪些组合最危险)

1. **并行读 + 进程死亡 + 全局恢复单一 try**：C01 使一个合法批次快照阻断全部启动对账，是首先需要修复的阻断项；8aa8ff97 和当前工作树均未修复。
2. **工具超时/未知输出 + 多调用批次**：C04 把真实复核需求映射成 INTERNAL，还阻断成功结果回填；用户再重试可能不知道哪些效果已发生。
3. **重新生成 + 后续未答用户消息/竞争发送/切会话**：C02/C03/P02 会改变错误范围的历史，或在替换根本未开始时隐藏旧内容；必须将 admission、目标校验和历史替换合并。
4. **Responses 持续参数流 + 上层只标错不停流**：C05 绕过参数内存上限；若进程恰好另有并行工具 Turn，内存杀进程还可接上 C01。
5. **未提交 Root 大文件传输 + 并行聊天**：C07 在主进程放大堆占用和取消延迟，影响不止文件页；应在合入前恢复真实流式实现。
6. **未来 GC 接线 + 老 hash 复用**：不能只把 C08 接线当作完成，必须同时解决 P06。现阶段没有生产调用，不能将潜在误删冒充已发生的 GC 缺陷。
7. **PRoot 仍存活 + 通知/持久化异常**：P01 可能造成“UI 已失败，外部命令仍运行”，租期正常测试不能关闭该风险。

建议修复验证顺序：C01 → C02/C03/C04 → C05/C06/C07 → P01 时序注入 → GC 协议与接线 → 其余 P2。此顺序不构成对仓库的修改授权或验收通过声明。
