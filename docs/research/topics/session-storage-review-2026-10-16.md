# 数据库与会话存储审查（2026-10-16）

本轮按 Owner 要求对照当前源码、Room schema 和竞品一手资料，审查数据库表、会话保存内容、历史查询与恢复边界。起点为 `main` / `6bbff42d`，包含工作区已有 HXA-295/296 改动；未覆盖既有工作，未修改生产代码、数据库或当前实施顺序。本文是研究输入，不代表下述建议已经实施，也不另立重构计划。

**结论：保留 Room/SQLite 管理持久事实、文件保存正文与产物的方向。优先改进查询和读取边界，再在既有重构中补齐执行关联和类型化历史；表数量本身不是问题。**

后续实施：Owner 已授权 [HXA-297](../../development/tasks/HXA-297.md)。下文 53 表、搜索候选限制及来源缺口保留为审查起点；当前实现与验证见[存储优化记录](../../evidence/development/session-storage-optimization-2026-10-16.md)，不得继续把这些基线缺口当作当前现状。

## 1. 审查起点保存了什么

### 1.1 表的职责

当前 [HelixDatabase](../../../core/storage/src/main/kotlin/com/helix/core/storage/HelixDatabase.kt) 与[当前导出 schema](../../../core/storage/src/androidTest/assets/com.helix.core.storage.HelixDatabase/1.json)一致：schema version 1，53 张业务表。以下按审查职责分组，不是建议改成六个数据库；AndroidTest 的其他历史 schema 不计入当前结构。

| 职责 | 数量 | 当前表 |
| --- | ---: | --- |
| 会话、历史与待处理输入 | 9 | `sessions`、`messages`、`message_attachments`、`message_reference_snapshots`、`document_snapshots`、`session_inputs`、`session_input_attachments`、`session_run_controls`、`session_experts` |
| 执行、结果与恢复事实 | 10 | `turns`、`model_calls`、`tool_calls`、`tool_results`、`executions`、`artifacts`、`audit_events`、`turn_runtime_records`、`tool_call_reviews`、`turn_review_receipts` |
| 授权、范围与交互回执 | 9 | `approvals`、`interaction_receipts`、`capability_grants`、`execution_targets`、`high_sensitivity_rules`、`session_permission_configs`、`tool_availability`、`session_permission_defaults`、`session_permission_drafts` |
| 项目与工作区绑定 | 5 | `projects`、`project_sessions`、`workspaces`、`session_workspaces`、`model_call_workspaces` |
| 计划与持久 Goal | 7 | `plans`、`plan_steps`、`goals`、`goal_runs`、`goal_turn_bindings`、`goal_usage_reservations`、`goal_controls` |
| Provider、Runtime 与扩展配置 | 13 | `provider_configs`、`runtime_installs`、`connector_installations`、`connector_skill_ownership`、`session_connectors`、`connector_endpoints`、`mcp_servers`、`mcp_capabilities`、`skills`、`skill_snapshots`、`a2a_agents`、`a2a_capabilities`、`a2a_tasks` |

53 张表覆盖整个应用，不能与竞品的两三张“聊天历史表”直接比较。审批、执行与恢复事实单独建模符合 Android 后台进程可能死亡、Mobile Use 动作不可假设回滚的需求。

### 1.2 会话与执行的实际结构

依据 [ConversationEntities](../../../core/storage/src/main/kotlin/com/helix/core/storage/entity/ConversationEntities.kt)：

| 对象 | 当前关键内容 | 当前边界 |
| --- | --- | --- |
| Session | 标题、默认 Provider/模型、创建/归档时间、目录引用 | 没有活动时间列；分支来源另存历史元数据 |
| Message | 会话/Turn、角色、kind、正文引用、会话内 sequence、supersededBy | 有稳定会话内顺序；没有逐消息时间戳和直接 modelCallId |
| Turn | 状态、开始/结束、步数、错误、客户端请求身份、恢复来源 | 是任务执行与恢复事实，不等于一条聊天气泡 |
| ModelCall | Turn、Provider 快照、状态、usage、服务端请求 ID、提示指纹/分段信息、请求 manifest | 不默认保存完整 wire 请求和全部提示正文；没有显式调用 ordinal/起止时间列 |
| ToolCall / Result / Execution | Turn、协议 callId、工具身份/版本、参数及哈希、状态、结果摘要/正文/验证、执行限制与退出情况 | ToolCall 没有直接指向产生它的 ModelCall；与历史中的工具协议投影分别保存 |
| SessionInput | 排队/调整输入、版本、目标 Turn、配置快照、正文/附件、消费后的 Turn/消息/请求绑定 | 未消费输入与已进入模型历史的消息分开，避免 UI 草稿冒充已执行输入 |
| Artifact / 附件 / 引用快照 | 文件路径、媒体类型、长度/哈希、用途、消息绑定、引用来源和捕获内容 | 文件仍需实际存在并通过身份检查；数据库引用不等于文件永远可用 |

会话中不只有用户与助手文字。[ChatHistoryBuilder](../../../app/src/main/kotlin/com/helix/app/agent/ChatHistoryBuilder.kt)还保存工具调用组、工具结果和过程消息；压缩 checkpoint、恢复提示、分支来源等也使用消息 kind。这里的正文既可能是普通文本，也可能是结构化 JSON。

### 1.3 正文、文件与上下文

- [ContentStore](../../../core/storage/src/main/kotlin/com/helix/core/storage/content/ContentStore.kt)在应用私有 `filesDir/helix-content/content/` 下按 SHA-256 分片保存正文。非空消息正文也走文件，并非只有“大结果”才外置。引用带路径、长度和哈希，读取校验身份；相同正文可复用同一文件。优点是去重及保持 Room 行小，代价是更多文件读取和跨数据库/文件的一致性工作。
- 图片、附件和用户产物通过 Artifact/工作区引用管理；普通会话文字、工具结果、图像来源不能合并成一个无来源的文本字段。用户最终成果与内部截图的用途边界需要保留。
- [ContextHistory](../../../app/src/main/kotlin/com/helix/app/agent/ContextHistory.kt)已有元数据分页和上下文有界物化；[ContextCompaction](../../../app/src/main/kotlin/com/helix/app/agent/ContextCompaction.kt)保存覆盖范围、摘要和保留消息身份，压缩不会直接删除原始消息。**持久历史、当前模型上下文与 UI 展示是三种读取视图。**
- 请求 manifest/提示指纹用于记录输入来源和身份，不是完整请求录制。不能据此承诺精确重放模型回复，也不应为“记录完整”默认保存全部敏感提示或内部推理。
- 长期 Memory 另由 [MarkdownMemoryStore](../../../core/workspace/src/main/kotlin/com/helix/core/workspace/memory/MarkdownMemoryStore.kt)按全局/项目保存 Markdown，文件为权威，查询时重建有界元数据索引。它不在这 53 张表中，也不等于会话压缩摘要；项目文件中的记忆与应用自身 Memory 的权限和用途仍需区分。
- Managed credentials 有独立 SecretStore；这不意味着聊天正文会自动消除用户粘贴的秘密，也不意味着数据库/正文文件采用了额外的应用级加密。

### 1.4 已有能力，不应重复列为缺失

[SessionFork](../../../app/src/main/kotlin/com/helix/app/chat/SessionFork.kt)在事务中复制选定历史前缀并重映射消息身份，复用内容引用，保存 `SESSION_FORK` 来源。它不复制旧工具执行、审批和 Goal，不回滚共享工作区或外部应用。对应 [ADR-AGENT-007](../../adr/agent/007-session-fork.md)。

[PrivacyDeletionService](../../../app/src/main/kotlin/com/helix/app/privacy/PrivacyDeletionService.kt)、[HelixStorage](../../../core/storage/src/main/kotlin/com/helix/core/storage/HelixStorage.kt)与 [StorageGarbageCollector](../../../core/storage/src/main/kotlin/com/helix/core/storage/content/StorageGarbageCollector.kt)已有显式隐私删除、共享正文引用检查和孤儿文件清理；归档只是日常隐藏，不等于隐私删除。用户文件/共享工作区不会随会话删除自动清空。

[JSONL 导出 ADR](../../adr/agent/005-session-jsonl-export.md)定义的是按用户操作生成的诊断/关系快照，不是第二套实时历史权威，也不是可恢复的全量备份。预览阶段不保留旧数据库是 Owner 已接受的产品约束；本轮不把迁移兼容重新设为发布阻塞。

## 2. 与竞品的差异

来源核验日期为 2026-10-16。Codex 使用公开 `main`，OpenCode 使用公开 `dev`；这些分支会继续变化，不代表每个已发布版本。Claude Code 只采用官方披露的本地会话机制，OpenHands 比较范围为 SDK 本地持久化，均不推断闭源云端数据库结构。

| 产品 | 一手资料确认的保存方式与内容 | 对 Helix 的启示 |
| --- | --- | --- |
| Codex | 本地 rollout JSONL 配合 SQLite 历史投影；记录 Turn/Item 身份、顺序、时间与状态，投影进度和记录在事务中推进；ThreadStore 提供分页读取边界 | 借鉴明确持久化接口、执行身份、可重建读模型；Helix 已有 Room 权威，不需要另加实时 JSONL 权威。[历史投影源码](https://github.com/openai/codex/blob/main/codex-rs/thread-store/src/local/thread_history.rs)、[ThreadStore](https://github.com/openai/codex/blob/main/codex-rs/thread-store/src/lib.rs) |
| Claude Code | 消息、工具调用和结果写本地明文 JSONL；恢复沿用会话 ID，分支复制历史到新 ID，另有文件 checkpoint | 借鉴易导出和明确的 resume/fork 用户语义；Helix 的恢复还必须保留手机动作的授权和 UNKNOWN 边界。文件 checkpoint 不能代替外部效果核查。[官方说明](https://code.claude.com/docs/en/how-claude-code-works) |
| OpenCode | 当前 schema 有 Session 元数据、分支来源/边界、时间、成本/Token；类型化 `session_message` 带 seq/时间/JSON 数据，另有 pending/inbox 和 instruction 状态表 | 借鉴可直接查询的元数据、类型与顺序；Helix 已有持久输入队列，重点是统一消费及历史投影，不是再建一套队列。[当前 schema](https://github.com/anomalyco/opencode/blob/dev/packages/core/src/session/sql.ts) |
| OpenHands SDK | 每会话 `base_state.json` 保存状态，`events/` 保存事件；事件增量保存并按需加载，重新加载会话状态后继续工作 | 借鉴状态与历史分工、按需读取。其 SDK 文件方案不能证明 Android Room 应被替换，也不能直接套用手机副作用恢复语义。[官方持久化说明](https://docs.openhands.dev/sdk/guides/convo-persistence) |

竞品也不都采用全文索引：Codex 当前跨会话搜索路径仍会搜索 rollout 文件，再结合分页元数据筛选和排序，支持 created/updated/recency 等排序。因此应比较搜索覆盖、来源和成本，不将“用 FTS”当作对标目标本身。[Codex 搜索实现](https://github.com/openai/codex/blob/main/codex-rs/thread-store/src/local/search_threads.rs)

## 3. 具体发现与建议

优先级表示改进顺序，不表示已经发生用户数据损坏。本轮未证实丢记录、越权恢复或崩溃。

### 3.1 优先：搜索会漏掉旧会话里的新消息

[SessionDao](../../../core/storage/src/main/kotlin/com/helix/core/storage/dao/SessionDao.kt)按 `createdAt DESC` 列出会话；[SessionDirectory](../../../app/src/main/kotlin/com/helix/app/chat/SessionDirectory.kt)及列表 UI 沿用该顺序。[MessageDao](../../../core/storage/src/main/kotlin/com/helix/core/storage/dao/MessageDao.kt)的正文候选先按会话创建时间、再按会话内 sequence 排序。[SessionSearchRepository](../../../core/storage/src/main/kotlin/com/helix/core/storage/repository/SessionSearchRepository.kt)默认只读 500 个候选，每正文最多 256 KiB，再做字符串匹配；会返回截断/跳过事实，不能说它假装完成了全库搜索。

**已在合成 SQL 中复现：新会话有 500 条记录，随后往旧会话添加一条记录，这条刚写入的记录不进入默认正文搜索候选。** 调高扫描上限只推迟同一问题。会话标题搜索不受这 500 条正文候选限制，应与正文搜索区分。

建议定义明确的 `lastActivityAt` 与消息时间/顺序，以稳定复合游标分页；活动时间由什么事件更新必须明确，不能把后台审计噪声都当用户活动。正文搜索可建立可重建的搜索投影，先限定用户可见文本和授权范围，再验证中文、短词、删除/分支/修订更新。选择 SQLite/Room 可支持的索引方案前，需验证支持的 Android 版本；不默认要求向量库。

### 3.2 优先：UI 有展示上限，但底层读取仍随全部历史增长

[ChatScreenProjection.messagesFor](../../../app/src/main/kotlin/com/helix/app/chat/ChatScreenProjection.kt)读取当前会话全部有效消息，再逐条读取可见正文。工具时间线也是先读取全部 Turn、工具调用及结果，最后才 `takeLast(TOOL_TIMELINE_CAP)`。LazyColumn 和最后截断都不能减少此前的数据读取。

这是静态确认的增长路径，**未做设备耗时、PSS 或 OOM 测量**。建议 UI 使用真正的历史窗口和向前翻页；工具时间线的限制下推至查询，并维护原调用顺序及审批/待核查项的可见性。模型上下文和 HXA-295 会话历史工具已有分页/限额，不应误判为所有历史入口都无界。

### 3.3 优先：正文引用索引不足，GC 的共享锁持有时间可能增长

GC 在与正文发布共用的协调锁内遍历文件、收集文件列表，并为候选执行引用检查。使用当前导出 schema 的主机 SQLite `EXPLAIN QUERY PLAN` 结果：

| 引用检查 | 当前查询计划 |
| --- | --- |
| `messages.contentRef` | 全表扫描 |
| `message_reference_snapshots.contentRef` | 全表扫描 |
| `tool_results.contentRef` | 全表扫描 |
| `session_inputs.textRef OR referenceContentRef` | 全表扫描；只有 textRef 单侧索引不够 |
| `document_snapshots.contentRef` | 使用已有覆盖索引 |

因此候选文件增多时，GC 可能反复扫描增长的表，并延长对正文发布的阻塞。这是查询计划和锁范围证据，**不是已测出的实际卡顿或耗电结论**；短路引用检查也意味着并非每个文件都会运行全部五次查询。

建议先补必要索引并验证查询计划，再评估有界批次/文件遍历。每一批仍须保护“检查引用—删除”和新引用发布之间的竞态，不能直接移除共享协调锁。也不应为这些局部问题先引入远程存储或通用事件框架。

### 3.4 架构阶段：执行关联和排序不宜继续依赖隐含信息

`model_calls` 与 `tool_calls` 都关联 Turn，但工具调用没有直接的来源 ModelCall；两者 DAO 按 SQLite `rowid` 排序。消息中保存的调用组、协议 callId 与审计可以提供进一步线索，所以这不是“当前无法配对”或“已经串调用”的证明。问题在于查询、导出、重建和未来同 Turn 多阶段调用需要理解多处隐含关系。

建议在完整 Turn 宿主/执行契约中显式定义来源模型调用、调用组顺序、工具组内序号和必要时间点。人工命令等非模型入口应有明确来源类型，不伪造 ModelCall。原执行身份和工具原始回填顺序保持稳定；不能用模型摘要替代执行结果。

### 3.5 架构阶段：历史内容类型与读模型职责需要更明确

Message 的 kind 与文件中的 JSON 已表达多种内容，但数据库对 checkpoint 覆盖范围、fork 来源、工具组关联的可查询性弱。建议使用有版本的类型化载荷，明确哪些字段必须可索引、哪些只需保留正文，并统一生成 UI、模型上下文、搜索与导出视图。

不要求每个 kind 新建一张表，也不将审批/执行状态全部塞进一个万能事件 JSON。历史中的模型协议投影与 `tool_calls/tool_results` 的执行事实用途不同；应统一写入责任、事务和身份，而不是因为内容看似重复就删除其中一份。

### 3.6 产品边界与低优先级整理

- fork 来源已经保存，但不是 Session 上的直接查询字段；未来需要分支树/跨会话投递时再将关系纳入领域契约，不重复实现现有分支。
- JSONL 导出不等于可导入备份。正式产品若需要备份，必须覆盖 Room、ContentStore、产物、缺失引用和凭据重连；导入历史不得恢复旧授权或重放旧动作。本轮没有提出云同步实现。
- 正文“先发布文件、再写引用”允许产生孤儿文件，现有 GC 有职责处理。突然断电时的文件持久性、文件系统损坏及 DB/正文联合恢复需单独故障注入；本轮不能从普通 rename 和哈希校验推导电源故障安全。
- `SessionEntity`/`SessionRepository` 中仍有只归档、不删除的旧注释，与现有显式隐私删除入口不一致；后续同范围维护可修正文案，不能据此删除隐私能力。

## 4. 如何承接现有重构

以下是当时的建议落点，不改变当前对话任务，也不把 HXA-286～294 标记为已启动；实施事实见 [status](../../development/status.md)。

| 建议 | 合适边界 | 验收重点 |
| --- | --- | --- |
| GC 引用索引与有界清理 | 可独立小改；保留现有 ContentStore 所有权 | 查询计划、共享引用/并发发布不误删、取消和失败事实 |
| 最近活动、UI 分页、搜索覆盖 | 会话查询层；与 [HXA-295](../../development/tasks/HXA-295.md) 当前/引用会话授权对齐 | 旧会话新消息、同时间游标、长会话、中文、归档/隐私删除、引用隔离 |
| ModelCall→ToolCall、类型化历史与统一持久入口 | [HXA-287](../../development/tasks/HXA-287.md) Turn 宿主、[HXA-288](../../development/tasks/HXA-288.md) 执行结果协同设计 | 多次模型调用、并发工具顺序、迟到结果、取消/UNKNOWN、恢复不重放 |
| 分支/投递的直接关系与审计 | HXA-295 后续切片，接续现有 [Harness §23.10](../../architecture/harness-refactor-plan.md#2310-会话管理与受控任务投递) | 分支不复制授权，重试不重复投递，消息/执行会话分清 |
| 综合恢复与规模验证 | [HXA-294](../../development/tasks/HXA-294.md) | 主机契约与授权设备实测分别验收；数据库恢复不冒充外部效果恢复 |

不必保留旧内部接口或双实现。但改结构仍需保留现有能力和权限事实；预览数据库可重建不等于可以不设计同版本运行中的一致性与恢复。

## 5. 本轮证据和限制

- 已核对当前实体、DAO、ContentStore、历史构建/压缩、列表/搜索/UI 投影、分支、隐私删除及相关 ADR，并使用 CodeGraph 查找持久层结构；源码优先于历史索引。
- 本轮临时脚本：`scripts/local/debug/2026-10-16/session_storage_queries.py`；结果：`build/session-storage-review-2026-10-16/sqlite-query-probe.json`。均位于忽略路径，只使用当前导出 schema 和虚构数据的内存 SQLite，没有打开真实用户数据库。
- 主机 SQLite 3.53.4：53 张表及索引成功建库，8 条查询的计划已检查；搜索遗漏与创建时间排序断言通过，合成库外键检查通过。首次探针因无索引实体省略 `indices` 字段而失败，修正探针读取可选字段后完整重跑通过；生产源码未变。
- 查询计划来自主机 SQLite，不等于 Android Room/设备运行通过，也不是性能基准。未运行本轮 JVM/Gradle、模型服务、模拟器或真机；设备与真实模型均为 `not requested`。既有任务测试不计为本轮结果。
- `./scripts/check-docs.sh` 通过：899 份 Markdown、279 个 HXA；`./scripts/verify-adr.sh` 通过：37 个当前 ADR；`git diff --check` 通过。53 个表名与当前导出 schema 的唯一集合一致；辅助清单校验最初的正则遗漏含数字的 A2A 表名，修正后重跑通过。尚无生产修复或性能提升可宣称。
