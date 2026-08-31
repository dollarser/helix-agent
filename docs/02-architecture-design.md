# Helix Android 单机版总体技术方案

## 1. 设计原则

1. 模型不等于权限主体。模型只能提出 ToolCall，Policy Engine 决定是否允许。
2. “已发起”不等于“已完成”。变更工具必须验证结果。
3. 生成代码是不可信输入。不得在主应用进程执行。
4. 先提供强类型原生工具，再用代码补足长尾计算。
5. 会话、Turn、ToolCall、Approval、Execution 分开建模，禁止用一张消息表代替运行状态。
6. UI 只展示状态和收集意图，不直接构造 DAO、HTTP Client 或 Runtime。
7. 系统权限、MCP annotation、Skill 指令和 Root grant 都不能代替 Tool Policy。
8. 当前单机实现不混入远程 Worker/HarmonyOS 传输代码，只保持执行目标接口可扩展。

## 2. 系统上下文

```text
┌──────────────────── Android 应用沙箱 ────────────────────┐
│                                                         │
│  Compose UI                                             │
│      │                                                  │
│      ▼                                                  │
│  Application Services ──► Agent Runtime ──► LLM API     │
│      │                       │                           │
│      │                       ▼                           │
│      │                 Policy Engine                    │
│      │                       │                           │
│      │                       ▼                           │
│      ├──────────────► Tool Registry                     │
│      │                 │         │                      │
│      │                 │         ├─ Android/File Tools  │
│      │                 │         ├─ Browser/UI Tools    │
│      │                 │         ├─ MCP Dynamic Tools   │
│      │                 │         └─ Skill Loader        │
│      │                 │                                │
│      │                 └─ Binder ─► isolatedProcess     │
│      │                              QuickJS Executor    │
│      │                                                  │
│      └──────────────► Room / Files / Keystore           │
└─────────────────────────────────────────────────────────┘

         signature-protected Binder/PFD IPC
                         │
                         ▼
┌──── 独立 APK / 独立 Android UID（developer 可选）───────┐
│  PRoot Runtime Service ─► Alpine RootFS ─► Job copy     │
│  无主 App 权限；基线版本无 INTERNET 权限                │
└─────────────────────────────────────────────────────────┘

┌──── 独立 APK / 独立 Android UID（P2 可选）─────────────┐
│  CLI Runtime ─► official Codex/Claude CLI ─► Internet   │
│  凭据只在该 UID；未完成安全 Spike 前不驱动 Helix Act    │
└─────────────────────────────────────────────────────────┘
```

LLM API 是外部推理服务，不是远程 Worker。它不直接访问手机文件和权限，只接收 Context Builder 明确选择的内容。

## 3. Gradle 模块

```text
Helix/
├── app/                       # Application、Compose、导航、AppContainer
├── core/
│   ├── model/                 # 纯 Kotlin 领域模型和错误类型
│   ├── agent/                 # Agent Loop、上下文、状态机
│   ├── policy/                # 风险、审批、授权作用域
│   ├── storage/               # Room、Repository、迁移
│   └── workspace/             # 路径、配额、原子文件操作
├── provider/
│   ├── api/                   # ModelProvider、内部事件、能力
│   ├── openai-responses/      # Responses adapter
│   ├── openai-chat/           # Chat Completions adapter
│   ├── anthropic/             # Messages adapter
│   └── catalog/               # 厂商/自建模板，不含 secret
├── extensions/
│   ├── mcp/                   # 官方 Kotlin SDK Client + facade
│   └── skills/                # SKILL.md catalog、loader、validator
├── feature/
│   ├── browser/               # WebView engine、tabs、agent controller
│   ├── files/                 # consumer/developer 共用 Workspace/SAF UI
│   └── files-allfiles/        # developer-only All-files adapter/UI
├── runtime/
│   ├── quickjs/               # Binder client、isolated Service、Zipline
│   ├── proot-client/          # developer 变体的 IPC client
│   ├── proot-app/             # 独立 applicationId/UID 的 Runtime APK
│   ├── cli-client/            # developer-only、P2 IPC client
│   └── cli-app/               # P2 官方 CLI 订阅后端独立 APK
├── tools/
│   ├── framework/             # Tool、Schema、Registry、Dispatcher
│   ├── android/               # consumer 可用 Intent、日历、通知、剪贴板
│   ├── automation/            # developer-only Accessibility
│   ├── browser/               # browser.* Tool adapters
│   ├── files/                 # read/write/edit/files.*
│   └── root/                  # libsu facade 和高层 Root tools
└── testing/                   # FakeClock、FakeProvider、fixture、测试 DSL
```

### 3.1 依赖方向

```text
app
 ├── core:agent ──► core:model
 ├── core:policy ─► core:model
 ├── core:storage ► core:model
 ├── provider:*   ► provider:api, core:model
 ├── extensions  ► core:model, tools:framework
 ├── feature     ► core:model, core:policy
 ├── tools        ► core:model, core:policy, core:workspace
 └── runtime      ► core:model
```

禁止：

- `core:*` 依赖 `app`。
- `core:agent` 直接依赖 Android SDK、Room、OkHttp、QuickJS。
- UI 直接依赖 DAO。
- Tool 实现调用 ViewModel。
- QuickJS 执行器持有主进程 Context 或 Provider 密钥。
- PRoot 在主 App 的普通 `android:process` 中执行；进程名不是权限边界。
- Agent Core 直接依赖 MCP SDK、Ktor、WebView、AccessibilityService 或 libsu。
- Skill/MCP 直接调用执行器绕过 Tool Dispatcher。

## 4. 核心领域接口

以下接口形状为实现契约，允许补充字段，不允许绕过边界。

```kotlin
interface ModelProvider {
    val descriptor: ProviderDescriptor
    suspend fun listModels(): ModelCatalogResult
    suspend fun validateConfiguration(): ProviderCheckResult
    fun stream(request: ModelRequest): Flow<ModelEvent>
}

interface AgentRuntime {
    suspend fun submit(command: SubmitTurnCommand): TurnId
    suspend fun resume(turnId: TurnId): ResumeResult
    suspend fun cancel(turnId: TurnId): CancelResult
    fun observe(turnId: TurnId): Flow<TurnSnapshot>
}

interface ContextBuilder {
    suspend fun build(request: ContextBuildRequest): ContextBuildResult
}

interface Tool {
    val descriptor: ToolDescriptor
    suspend fun execute(context: ToolExecutionContext, input: JsonObject): ToolResult
}

interface ToolRegistry {
    fun descriptors(): List<ToolDescriptor>
    fun resolve(name: ToolName, version: ToolVersion): Tool?
}

interface PolicyEngine {
    suspend fun evaluate(request: ToolExecutionRequest): PolicyDecision
}

interface ApprovalRepository {
    suspend fun create(request: ApprovalRequest): ApprovalId
    suspend fun decide(id: ApprovalId, decision: UserDecision): ApprovalRecord
    suspend fun consume(proof: ApprovalProof): ConsumeResult
}

interface WorkspaceFileSystem {
    suspend fun read(path: WorkspacePath, limit: ByteCount): ReadResult
    suspend fun writeAtomic(request: AtomicWriteRequest): WriteResult
    suspend fun moveToTrash(path: WorkspacePath, operationId: OperationId): TrashResult
}

interface CodeExecutor {
    suspend fun execute(request: CodeExecutionRequest): CodeExecutionResult
    suspend fun cancel(executionId: ExecutionId): CancelResult
}

interface BrowserController {
    suspend fun snapshot(scope: BrowserTabScope): BrowserSnapshot
    suspend fun perform(request: BrowserActionRequest): BrowserActionResult
}

interface CapabilityResolver {
    suspend fun resolve(required: Set<Capability>): CapabilitySnapshot
}

interface McpClientFacade {
    suspend fun connect(serverId: McpServerId): McpCapabilitySnapshot
    suspend fun call(request: McpToolRequest): McpToolResult
    suspend fun close(serverId: McpServerId)
}

interface SkillRepository {
    suspend fun catalog(scope: SkillScope): List<SkillMetadata>
    suspend fun load(id: SkillId, expectedHash: Sha256): SkillContent
}

interface ToolExecutor {
    val target: ExecutionTargetDescriptor
    suspend fun execute(request: ToolExecutionEnvelope): ToolResultEnvelope
    suspend fun cancel(executionId: ExecutionId): CancelResult
}
```

## 5. Agent 模式与状态机

### 5.1 模式边界

| 模式 | 允许行为 |
| --- | --- |
| Chat | 默认无工具；用户显式启用时仅允许 `operationClass=READ_ONLY` 且动态风险为 L0 的读取 |
| Plan | 只读调研并生成版本化 PlanArtifact；禁止写、代码执行、点击和外部动作 |
| Act | 在当前 Turn 内按 Policy 执行 |
| Goal | 持久化、多次唤醒、受预算和检查点约束；审批规则与 Act 相同 |

Tool Registry 在构建模型请求前同时按 mode、Capability、user scope 和 execution target 过滤。不可用工具不进入本次模型工具表。Plan 只允许 `operationClass=READ_ONLY`，不能用 `baseRisk <= L1` 代替这个判断；因此 L1 的新建文件、HTTP 请求和页面动作仍不可用。Plan 对 `READ_ONLY` 工具同时施加动态风险上限：动态风险升到 L2/L3 的读取（如读取 Root 日志、敏感联系人）在 Plan 同样不可用——operation class 是主判断，风险上限不替代 class 判断。

Goal 的首版唤醒源只有用户显式继续（打开 Goal/点击通知）。可选 WorkManager 只在 `nextCheckpoint` 附近发出提醒，不在后台发起模型请求或工具调用；Doze、强制停止和系统调度均可延迟/取消提醒，UI 不得将检查点显示为精确定时器。

### 5.2 Turn 状态

```text
CREATED
  └─► BUILDING_CONTEXT
        ├─► WAITING_MODEL
        │     ├─► RECEIVING_MODEL
        │     │     ├─► WAITING_APPROVAL
        │     │     │     ├─批准► RUNNING_TOOL
        │     │     │     └─拒绝► RECORDING_TOOL_RESULT
        │     │     ├─► RUNNING_TOOL
        │     │     ├─► COMPLETED
        │     │     └─► FAILED
        │     └─► FAILED
        └─► FAILED                        （预调用预算门控失败）

RUNNING_TOOL ─► RECORDING_TOOL_RESULT
RECORDING_TOOL_RESULT ─► BUILDING_CONTEXT ─► WAITING_MODEL    （该响应的最后一个调用记录完毕）
RECORDING_TOOL_RESULT ─► WAITING_APPROVAL | RUNNING_TOOL      （同一响应的下一个串行调用）

任意非终态 ─► CANCELLING ─► CANCELLED
进程异常终止 ─► INTERRUPTED
INTERRUPTED ─► BUILDING_CONTEXT（恢复，先完成副作用审查）| CANCELLED（丢弃）
```

以上状态图是 `core:model` `TurnState` 的规范性转移集合，完整矩阵由 `StateMachinesTest` 守卫。三条 HXA-011 增量边和 HXA-010 既有恢复/丢弃边的图面澄清由 [ADR-0002](adr/0002-turn-state-intra-response-edges.md)记录（accepted）；上下文每模型响应构建一次，不在每个工具调用之间重建。

终态：`COMPLETED`、`FAILED`、`CANCELLED`。`INTERRUPTED` 可由用户恢复，但恢复前必须检查是否存在可能已产生外部效果的 ToolCall。

Goal 另有 `DRAFT/READY/RUNNING/INPUT_REQUIRED/PAUSED/COMPLETED/FAILED/CANCELLED` 状态，不允许把 Goal 和单个 Turn 合为同一张状态表。

### 5.3 Agent Loop 伪代码

```kotlin
repeat(MAX_STEPS) {
    ensureActive()
    val context = contextBuilder.build(sessionId, turnId)
    val response = provider.stream(context).persistAsItArrives()

    when (response.terminal) {
        is FinalText -> return complete(response.terminal)
        is ToolCalls -> {
            for (call in response.terminal.calls) {
                val validated = toolValidator.validate(call)
                val policy = policyEngine.evaluate(validated)
                val result = when (policy) {
                    Allow -> dispatcher.execute(validated)
                    AskUser -> awaitAndConsumeApproval(validated, policy)
                    Deny -> ToolResult.denied(policy.reason)
                }
                persistToolResult(call, result)
            }
        }
        is ProtocolError -> return fail(response.terminal)
    }
}
return fail(StepLimitExceeded)
```

规则：

- Provider 流事件边接收边持久化，不能只在完整结束后保存。
- 同一 Turn 内多个 ToolCall 第一版串行执行，避免审批和副作用竞态。
- 工具异常必须转换为结构化 `ToolError`，不能把堆栈直接发给模型。
- 用户拒绝也是合法 ToolResult，Agent 可以调整计划，但不得重复请求完全相同的高风险动作。
- Act/Goal 都持有 `TurnBudgets`：最大输入 token、输出 token、累计 token 和模型调用数。每次模型调用前计算剩余 step/token；限额取用户配置和 Provider capability 中更严者，无法准确 tokenizer 时使用保守字节估算，不得将未知 usage 当作 0。

### 5.4 Context Builder 契约

`ContextBuilder` 属于 `core:agent`，输入是持久化的会话/Turn 快照和 Provider capability，输出是可审计的 `ContextBuildResult`。每个 item 至少包含 `sourceType`、`sourceId`、`trust`、`contentRef/contentHash` 和估算 token。

裁剪顺序必须确定性且有测试：保留 system/mode/policy 契约、当前用户指令、未完成的 ToolCall 完整参数、审批上下文和对应 ToolResult；再按时间和相关性裁剪旧消息。不可将工具 JSON 或审批摘要截成语义不完整的文本。超限 ToolResult/文件转换为有界 summary + Artifact 引用/hash，后续通过 `read(offset, maxBytes)` 分块获取。Secret 永不进入 Context；Web/File/MCP/Skill/Notification/Accessibility 内容标记 `UNTRUSTED`。

## 6. 模型协议与 Provider

### 6.1 内部统一事件

```kotlin
sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class ReasoningDelta(val text: String) : ModelEvent
    data class ToolCallStarted(val index: Int, val id: String, val name: String) : ModelEvent
    data class ToolArgumentsDelta(val index: Int, val jsonFragment: String) : ModelEvent
    data class ToolCallFinished(val index: Int) : ModelEvent
    data class Usage(val inputTokens: Long?, val outputTokens: Long?) : ModelEvent
    data class Refusal(val safeReason: String?) : ModelEvent
    data class Error(val code: ModelErrorCode, val retryable: Boolean) : ModelEvent
    data class Completed(val finishReason: String?) : ModelEvent
}
```

### 6.2 Provider 约束

- Base URL 必须为 HTTPS；局域网 HTTP 只允许用户为明确 `host:port` 单独启用，不存在全局 cleartext 开关。`localhost`/`127.0.0.1` 也按同一规则处理。
- 重定向后重新执行 origin 校验，Authorization 不跨 origin 转发。
- SSE Parser 必须处理一行拆包、多行 data、UTF-8 边界和 `[DONE]`。
- Tool arguments 使用增量字符串缓冲，完成后一次 JSON parse 和 Schema validation。
- 日志只记录 host、状态码、耗时、request ID，不记录 Authorization 或完整正文。
- Provider 配置快照写入 Turn，但 secret 只保存 Keystore alias。
- `OPENAI_RESPONSES`、`OPENAI_CHAT_COMPLETIONS`、`ANTHROPIC_MESSAGES` 是独立 adapter，不做失败后猜测式换协议。
- Ollama/SGLang 等自建服务先做文本和 ToolCall 能力探测；兼容性结论记录到 snapshot。
- ChatGPT/Claude 消费者订阅只允许通过官方 CLI 自己登录；token 不进入主 App。详见 [专项方案](10-provider-mcp-skills-modes.md)。

## 7. Tool 模型

```kotlin
data class ToolDescriptor(
    val name: ToolName,
    val version: ToolVersion,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val operationClass: ToolOperationClass,
    val baseRisk: RiskLevel,
    val timeout: Duration,
    val maxOutputBytes: Long,
    val requiredCapabilities: Set<Capability>, // core:model 的统一 enum
    val idempotency: Idempotency,
)
```

`ToolOperationClass` 至少包含 `READ_ONLY`、`LOCAL_MUTATION`、`NETWORK`、`EXTERNAL_ACTION`、`CODE_EXECUTION`、`PRIVILEGED`。它描述操作效应，与动态风险等级正交；MCP annotation 不能将工具降为 `READ_ONLY`。

### 7.1 执行管线

```text
模型 ToolCall
  → 名称/版本解析
  → JSON 语法验证
  → JSON Schema 验证
  → 参数规范化
  → 动态风险计算
  → Policy Decision
  → Approval Proof（如需要）
  → Timeout/Cancellation 包装
  → Tool.execute
  → 输出大小限制
  → 变更后验证
  → 审计与模型回填
```

内置工具名不能由模型动态注册。MCP 工具只能由已连接、用户启用的 Server 动态注册，并强制命名为 `mcp.<server>.<tool>`；schema hash 变化会撤销旧审批。代码执行也只是固定工具 `code.javascript.run` 或 `bash`，源码/命令属于参数。

`read`、`write`、`edit`、`bash` 是面向模型的稳定短名称；namespaced 文件工具是扩展集合，两者必须委托给同一实现和 Policy，不能复制逻辑。

## 8. Policy Engine

动态风险由以下因素合成：

- 工具基础风险。
- 读/写/删除/执行/联网类别。
- 目标是否为用户授权 Workspace。
- 是否覆盖已有数据。
- 是否包含 secret、联系人、通知或位置。
- 是否访问新的网络 origin。
- 代码或命令是否发生改变。
- 数据来自 Workspace、SAF、All-files、浏览器、Accessibility、MCP 还是 Root。
- MCP server/tool schema hash、Skill snapshot hash、浏览器页面代次或 UI window/package 是否变化。

审批参数摘要必须使用规范 JSON 计算：

```text
approvalHash = SHA-256(
  toolName || toolVersion || toolSchemaHash || canonicalArguments ||
  scopeId || sessionId || executionTargetId || transientTokenBinding
)
```

任何参数、代码、命令、文件列表、网络权限、超时变化都会使旧审批无效。

## 9. 数据模型

### 9.1 Room 表

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `sessions` | id, title, providerId, modelId, createdAt, archivedAt | 会话元数据 |
| `messages` | id, sessionId, turnId, role, kind, contentRef, sequence | 时间线内容 |
| `turns` | id, sessionId, state, stepCount, startedAt, endedAt, errorCode | Agent Turn |
| `model_calls` | id, turnId, providerSnapshot, state, usage, requestId | 模型调用，不存 secret |
| `tool_calls` | id, turnId, callId, name, version, argsJson, argsHash, state | 工具请求 |
| `tool_results` | id, toolCallId, status, summary, contentRef, verified | 工具结果 |
| `approvals` | id, toolCallId, argsHash, decision, decidedAt, consumedAt | 一次性审批 |
| `executions` | id, toolCallId, runtime, limitsJson, exitCode, signal | 代码/命令执行 |
| `artifacts` | id, sessionId, relativePath, mediaType, size, sha256 | 产物索引 |
| `audit_events` | id, correlationId, type, actor, redactedPayload, timestamp | 审计 |
| `provider_configs` | id, displayName, protocol, endpoint, model, headersJson, secretAlias, capabilitySnapshot | 无明文 key |
| `runtime_installs` | id, type, version, state, manifestHash, installedAt | PRoot/RootFS |
| `plans` / `plan_steps` | objective, version, hash, state, evidenceRef | 版本化计划 |
| `goals` / `goal_runs` | objective, criteriaRef, budgets, state, planId, checkpoint / goalId, wakeReason, outcome | 持久目标与唤醒记录 |
| `mcp_servers` / `mcp_capabilities` | transport, endpointRef/commandRef, authAlias, enabled, trustState / serverId, protocolVersion, kind, name, schemaHash, enabled | MCP 配置和快照 |
| `skills` / `skill_snapshots` | name, source, version, rootRef, contentHash, enabled / runId, skillId, contentHash, catalogEntry | Skill 渐进加载和固定版本 |
| `capability_grants` | type, systemState, userScopeRef, checkedAt | 权限状态缓存，不代替实时检查 |
| `execution_targets` | type, descriptor, capabilitySnapshot | 当前只含本机目标 |

本节是 Room schema 的唯一规范性字段清单；专项文档只能引用它或说明语义，不得重复定义另一套字段。大型正文和二进制存文件，Room 只保存引用、哈希和元数据。

### 9.2 数据一致性

- Room foreign key 全部启用。
- Turn/ToolCall 状态更新与对应审计事件放同一事务。
- ToolCall 的 `argsJson` 保存 canonical JSON；`argsHash` 不可修改。
- 产物先写文件并计算哈希，再插入 `artifacts`。
- 数据库迁移必须有 schema export 和 migration instrumentation test。

## 10. Workspace 设计

```text
files/
└── workspaces/
    └── <workspace-id>/
        ├── input/
        ├── work/
        ├── output/
        └── .helix/
            ├── metadata.json
            ├── trash/
            └── executions/
```

`WorkspacePath` 不是普通 String。构造时完成：NUL 检查、分隔符归一化、绝对路径拒绝、`.`/`..` 解析、根路径确认。工具层不得自行拼接真实路径。

SAF 默认工作流：读取 `content://` → 检查元数据和大小 → 用户确认 → 流式复制到 `input/` → 计算 SHA-256 → 登记 Artifact。用户可以显式创建长期 `DocumentTreeScope`，但 URI grant 保存在平台适配层，模型只看到 scopeId。

All files access 通过系统设置授权后仍要求用户在 Helix 内选择 roots。Root 文件系统使用独立 `RootScope` 和高风险工具，不复用普通 scope 伪装成同一权限级别。

## 11. Android 平台适配

- `NotificationListenerService`：只在用户开启系统权限后工作；Provider 层返回 `PermissionMissing`，不能返回空列表冒充成功。
- Calendar：优先使用系统 Intent 生成用户可见草稿；直接 Provider 写入属于 L2。
- 文件：Workspace 为默认；支持 SAF 和用户主动开启的 `MANAGE_EXTERNAL_STORAGE`，但 Tool 仅能访问 Helix scope。
- 后台：WorkManager 用于可延期维护和 Goal 提醒，不用于精确唤醒或未经用户继续的 Agent 执行。前台服务只覆盖用户主动发起、正在执行且符合平台用途的 Provider/MCP 传输或本地文件处理，基线声明 `foregroundServiceType="dataSync"`、`FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_DATA_SYNC`。等待审批/人工输入时停止服务；实现 `Service.onTimeout()` 并在 Android 15+ 共享的 6 小时/24 小时限额前结束。不得用前台服务把 Goal 变成无人值守循环。强制停止后无法自恢复。
- 浏览器：System WebView + AndroidX WebKit；不可信页面无永久 privileged JS bridge。
- Accessibility：用户从系统设置开启，目标包 allowlist、限时 session、节点 token 和停止入口；敏感系统/支付/认证界面拒绝。
- Root：用户明确触发 libsu Root 请求；高层只读工具优先，`root.exec` 默认不对 Agent 开放。
- Network Security Config：公网 release 禁止 cleartext；用户可为明确的局域网模型/MCP host 开启受限 HTTP 配置。

## 12. 生命周期与并发

- `AppContainer` 创建进程级 Repository、Provider Factory、Tool Registry 和 Runtime Supervisor。
- 单个 `SessionTurnCoordinator` 使用 `Mutex` 保证一个会话只有一个活动 Turn。
- 不同会话使用有限并发 Dispatcher，默认最多 2 个活动模型 Turn。
- 代码执行全局最多 1 个，避免手机资源争用。
- Application 退后台不意味着 Turn 自动结束；是否提升前台服务取决于任务类型和用户可感知状态。
- UI 通过 Repository/Runtime 的 `Flow` 恢复，不持有执行 Job 所有权。

## 13. 错误契约

```kotlin
data class HelixError(
    val code: ErrorCode,
    val userMessage: String,
    val retryable: Boolean,
    val safeDetails: Map<String, String> = emptyMap(),
    val correlationId: String,
)
```

稳定错误类别：`VALIDATION`、`PERMISSION`、`APPROVAL`、`POLICY`、`NETWORK`、`PROVIDER_AUTH`、`PROVIDER_RATE_LIMIT`、`TOOL_TIMEOUT`、`EXECUTION`、`STORAGE`、`INTERRUPTED`、`INTERNAL`。

禁止将异常 message 原样显示或发送给模型，因为其中可能含路径、URL query、Header 或 secret。

## 14. 可观察性

每条链路使用：

```text
sessionId → turnId → modelCallId/toolCallId → approvalId/executionId
```

日志使用结构化字段和脱敏器。release 默认 INFO，源码、模型正文、文件正文、Authorization、Cookie、通知正文默认不记日志。

## 15. 未来扩展点

远程 Worker、云端沙箱或桌面配对以后可以实现新的 `ToolExecutor/ExecutionTarget` transport，HarmonyOS 以后实现自己的 Platform Capability Adapter。当前只定义 envelope 和领域接口，不添加不可测试的网络空实现。PRoot/CLI companion 虽然通过 IPC 调用，但仍是同一手机上的本地执行器，不属于远程 Worker。

## 16. 架构验收

- `core:agent` JVM 测试无需 Android Runner。
- QuickJS Service 被声明为 `isolatedProcess=true`、`exported=false`。
- QuickJS 超时实例的回收有 PID/Binder death 证据，后续执行使用新 instance name 和新进程。
- PRoot Runtime 使用独立 applicationId/UID；跨 App Service 只允许同签名客户端绑定，并执行协议版本握手。
- 主进程崩溃重启后，活动 Turn 不会静默标记完成。
- UI 源码中不存在 DAO、OkHttpClient、QuickJs 或 PRoot 直接调用。
- Tool 实现没有静态全局 Context。
- 参数改变后审批 proof 必定失效。
- 未经 Registry/Policy/Approval 管线无法调用任何变更工具。
- Plan 模式无法注册或执行 mutating tool；Goal 恢复不重放不明确副作用。
- WebView、Accessibility 和 Root 的动作都能追溯到同一个 ToolCall/Approval correlation chain。
- MCP schema 或 Skill content hash 变化后，正在运行的 snapshot 不被静默替换。
