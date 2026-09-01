# ADR-0010: 以批量语义 Turn Coordinator 取代串行生产状态机

Status: accepted
Date: 2026-09-01
HXA: HXA-039
Deciders: Project owner（在 2026-09-01 架构扫描结论后授权直接实施优化）
Supersedes: [ADR-0002](0002-turn-state-intra-response-edges.md)
Superseded by: none

## Context

HXA-011 的 `TurnReducer` 按同一模型响应内逐调用串行推进：队首调用依次经过审批、执行、记录，再启动下一个调用。HXA-037 已在生产聊天链路中采用平台根据 `EffectFootprint` 决定的批量有界并发：多个无冲突只读调用可以同时执行，但结算和模型回填仍固定按模型调用顺序。把旧 reducer 直接接到生产会回退已验收的并发语义，也无法同时表达批内多个 pending、running 和 unknown outcome。

复核还发现生产 `ChatService` 的当前 ModelCall/stream checkpoint 只在整个 Tool Loop 正常返回后更新。第二个及以后模型调用发生取消或异常时，外层可能终结第一条已经完成的 ModelCall，并丢失当前调用的部分流。assistant message、Turn 终局和 ModelCall 终局也分别提交，进程中断可能留下部分终局。

## Decision

1. 新增唯一的 application-level `TurnCoordinator` 作为生产聊天 Turn/ModelCall 生命周期语义来源。M1 `TurnReducer` 保留为历史领域测试和旧恢复数据兼容层，不接入新生产 Turn，也不得决定批内调度顺序。
2. 一个模型响应的 ToolCalls 构成一个 batch。Turn 只持久化 aggregate phase；每个调用的 pending、awaiting approval、running、terminal 或 needs review 由 ToolCall 行表达。安全只读调用仍可有界并发，结果必须按原调用顺序持久化后才能进入下一次模型调用。
3. coordinator 在每次模型流开始前切换当前 ModelCall/`ModelStreamState` checkpoint。任何取消、异常或终局均读取该 checkpoint，不使用 Tool Loop 外层的旧 call id。
4. assistant 最终文本、Turn 终局和仍打开的 ModelCall 终局在一个 Room transaction 中提交。模型工具步骤的 ModelCall close 与 assistant tool-call message 同事务；所有外部 ToolCall 完成后，按序 ToolResult 回填、下一 ModelCall 创建和 Turn 回环同事务。外部副作用本身绝不放进数据库事务，也不因事务失败重放。
5. Scheduler 为每个异常槽位保留自己的 `Throwable`，首个异常只用于 turn-level propagation，不能替其他槽位分类。非审批取消的 dispatcher contract throw 视为副作用未知，ToolCall 进入 `NEEDS_REVIEW`；同批或并发批次重复 `toolCallId` 在执行前失败关闭。
6. `TurnRepository` 拒绝非法状态跳转；进程死亡到 `INTERRUPTED` 使用显式的 process-death 例外。ADR-0002 的预调用预算失败和恢复约束继续保留；其“同响应逐调用串行”决定被本 ADR 取代。旧 `RECORDING_TOOL_RESULT → WAITING_APPROVAL/RUNNING_TOOL` 边只用于既有 reducer/持久数据兼容，新 coordinator 不产生这些路径。

## Alternatives considered

1. **演进现有 `TurnReducer` 并直接接线**：需要把单一 `uncertainToolCallId`、队首 effect 和串行 `ResultsRecorded` 同时改成批量状态，并迁移大量 M1 fixture；在迁移完成前容易形成第三套半接线语义。未选择。
2. **回退 Scheduler 为串行以匹配 reducer**：实现较小，但丢失 HXA-037 已验收的安全只读并发和移动端吞吐，不符合当前产品决定。未选择。
3. **继续让 `ChatService` 直接写 Room，只修 activeCallId**：能修局部错误，但状态跳转、事务和恢复仍有多个事实来源，后续业务工具会继续放大一致性风险。未选择。

## Consequences

- 收益：第二轮以后失败会终结正确 ModelCall；部分流保留在当前 checkpoint；模型可见回填具有事务边界；批内异常按调用独立分类；生产状态语义不再依赖旧串行 reducer。
- 代价：`TurnCoordinator` 暂位于 app/chat application 层并依赖 `HelixStorage`；聊天 UI 投影和 ToolCall 具体落库仍由 `ChatService`/Dispatcher 协作，后续只可沿 facade 内部继续提取，不能暴露绕过 Dispatcher 的入口。
- 兼容：Room schema 不变。旧 `WAITING_APPROVAL` Turn 行仍可由 HXA-015 恢复；新批次以 `RUNNING_TOOL` 作为 aggregate phase。
- 风险：文件内容先写 content store、再由 Room 引用；事务回滚可能留下不可达 content blob，但不会产生模型可见行。blob 垃圾回收属于存储维护任务，不允许用回滚后重放外部工具解决。

## Verification

HXA-039 验收要求：

- `./gradlew :core:agent:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest`
- `./gradlew :tools:framework:test :core:model:test spotlessCheck detekt`
- `./gradlew :app:connectedConsumerDebugAndroidTest`（API 36；覆盖恢复 fixture 与既有聊天/审批/调度设备合同）
- `scripts/check-docs.sh`、`scripts/verify-adr.sh`、`scripts/check-secrets.sh`

实际命令、exit code、设备和限制记录在 [HXA-039 完成记录](../completion-records/HXA-039.md)。

## Reconsider when

- Turn 需要跨进程/跨设备协调，当前单进程 coordinator checkpoint 不再足够。
- Room transaction 或 content-store 引用模型变化，需要 transactional outbox 或内容垃圾回收协议。
- 批内 ToolCall 数量或移动端资源证据要求改变并发硬上限。
- 旧 reducer 的所有恢复数据已迁移，可删除串行兼容边和历史 reducer。

## References

- [ADR-0002](0002-turn-state-intra-response-edges.md)
- [总体技术方案](../02-architecture-design.md)
- [手机端 Tool 编排](../11-mobile-tool-orchestration.md)
- [安全、测试与发布](../07-security-testing-release.md)
- [HXA-039](../04-roadmap-and-backlog.md)
