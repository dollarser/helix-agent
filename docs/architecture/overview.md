# Helix 总体架构（Android 单机版）

文档状态：Baseline 1.3
基线日期：2026-08-31

## 1. 设计原则

1. 模型不等于权限主体。模型只能提出 ToolCall，Policy Engine 决定是否允许。
2. “已发起”不等于“已完成”。变更工具必须验证结果。
3. 生成代码是不可信输入。不得在主应用进程执行。
4. 先提供强类型原生工具，再用代码补足长尾计算。
5. 会话、Turn、ToolCall、Approval、Execution 分开建模，禁止用一张消息表代替运行状态。
6. UI 只展示状态和收集意图，不直接构造 DAO、HTTP Client 或 Runtime。
7. 系统权限、MCP annotation、Skill 指令和 Root grant 都不能代替 Tool Policy。
8. 当前单机实现不混入远程 Worker/HarmonyOS 传输代码，只保持执行目标接口可扩展。

运行时安全配置遵循 [ADR-0012](../adr/0012-capability-first-advanced-grants.md)：`STANDARD` 是所有安装默认，`ADVANCED` 只在 developer 变体显式可用。Gradle `consumer/developer` 决定代码是否进入 APK，Safety Profile 决定已编译能力在本次运行中是否可见；二者正交，均不能替代 Android 系统权限、Capability 实时状态或 Tool Policy。Trusted Workspace/有界规则只可自动执行动态风险不高于 L1 的匹配调用；通用 L2/L3 仍需精确用户批准。

发布角色遵循 [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)：Standard 是 Google Play、国内 Android 应用商店和官网的完整产品形态；consumer/developer 只是当前工程打包机制。渠道只按明确政策或真实审核做最小 manifest/Capability 差异，不分叉 Agent Core、数据模型和 Workspace。PRoot/CLI Runtime 是独立 UID 的可选 companion，不是另一个主应用，也不能持有或迁移主应用数据库。

Companion 生命周期遵循 [ADR-0007](../adr/0007-companion-runtime-lifecycle.md)：用户只需安装匹配的 PRoot/CLI Runtime，不必预先打开或保持其进程；Helix 只在用户触发并通过 Policy/Approval 的 Job 上按需冷绑定，断连后查询/对账，未知结果停泊而不自动重放。

### 1.1 “本机执行”与隔离强度

| 执行目标 | 物理执行位置 | 安全边界 | 不得宣称 |
| --- | --- | --- | --- |
| E0 原生 Tool | 手机、主 App UID | schema + scope + Policy + Approval + Android 权限 | 通用代码沙箱 |
| E1 QuickJS | 手机、系统分配的 isolated UID | 非导出 isolated Service、无权限、无 Host Bridge、输入输出有界 | Zipline/QuickJS 自身提供沙箱或 VM |
| E2 PRoot | 手机、独立 companion APK/UID | 签名 IPC、Job 快照、无 INTERNET、进程与资源限制 | PRoot 提供内核/虚拟机级隔离，或直接挂载真实 Workspace |
| E2C CLI Runtime | 手机、独立有网 companion APK/UID | 私有 Job、官方 CLI 自持凭据、签名 IPC；是否接入 Agent 取决于 Spike | 远程 Worker，或默认继承主 App 文件/权限 |
| LLM Provider | 设备或网络 endpoint | Context Builder + egress Policy；只收到明确选择的上下文 | 在手机上执行 Tool，或直接拥有 Android 权限 |

当前没有 E3 Remote Worker。以后即使新增远程执行，也必须使用新的执行目标、数据出境提示和威胁模型，不能把 Provider API 静默升级为远程 shell。Root 与 Accessibility 是高权限平台执行域，不是沙箱；它们依靠结构化工具、限时 scope、实时权限检查、逐次 Policy/Approval 和拒绝清单收口。

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

以下代码块是跨层**目标端口伪代码**：它固定输入、输出、取消和边界职责，不保证与当前 Kotlin 声明逐字符一致。已落位的类型以源码签名为准；未落位的端口仍是后续 HXA 的目标边界，不得据此声称功能已实现，也不得绕过 Dispatcher/Policy/Approval。

```kotlin
interface ModelProvider {
    val descriptor: ProviderDescriptor
    suspend fun listModels(): ModelCatalogResult
    suspend fun validateConfiguration(): ProviderCheckResult
    fun stream(../request: ModelRequest): Flow<ModelEvent>
}

interface AgentRuntime {
    suspend fun submit(../command: SubmitTurnCommand): TurnId
    suspend fun resume(../turnId: TurnId): ResumeResult
    suspend fun cancel(../turnId: TurnId): CancelResult
    fun observe(../turnId: TurnId): Flow<TurnSnapshot>
}

interface ContextBuilder {
    suspend fun build(../request: ContextBuildRequest): ContextBuildResult
}

interface Tool {
    val descriptor: ToolDescriptor
    suspend fun execute(../context: ToolExecutionContext, input: JsonObject): ToolResult
}

interface ToolRegistry {
    fun descriptors(): List<ToolDescriptor>
    fun resolve(../name: ToolName, version: ToolVersion): Tool?
}

interface PolicyEngine {
    suspend fun evaluate(../request: ToolExecutionRequest): PolicyDecision
}

interface ApprovalRepository {
    suspend fun create(../request: ApprovalRequest): ApprovalId
    suspend fun decide(../id: ApprovalId, decision: UserDecision): ApprovalRecord
    suspend fun consume(../proof: ApprovalProof): ConsumeResult
}

interface WorkspaceFileSystem {
    suspend fun read(../path: WorkspacePath, limit: ByteCount): ReadResult
    suspend fun writeAtomic(../request: AtomicWriteRequest): WriteResult
    suspend fun moveToTrash(../path: WorkspacePath, operationId: OperationId): TrashResult
}

interface CodeExecutor {
    suspend fun execute(../request: CodeExecutionRequest): CodeExecutionResult
    suspend fun cancel(../executionId: ExecutionId): CancelResult
}

interface BrowserController {
    suspend fun snapshot(../scope: BrowserTabScope): BrowserSnapshot
    suspend fun perform(../request: BrowserActionRequest): BrowserActionResult
}

interface CapabilityResolver {
    suspend fun resolve(../required: Set<Capability>): CapabilitySnapshot
}

interface McpClientFacade {
    suspend fun connect(../serverId: McpServerId): McpCapabilitySnapshot
    suspend fun call(../request: McpToolRequest): McpToolResult
    suspend fun close(../serverId: McpServerId)
}

interface SkillRepository {
    suspend fun catalog(../scope: SkillScope): List<SkillMetadata>
    suspend fun load(../id: SkillId, expectedHash: Sha256): SkillContent
}

interface ToolExecutor {
    val target: ExecutionTargetDescriptor
    suspend fun execute(../request: ToolExecutionEnvelope): ToolResultEnvelope
    suspend fun cancel(../executionId: ExecutionId): CancelResult
}
```

当前落位对照（未列出的伪代码字段不是可以直接 import 的 API）：

| 目标端口 | 当前落位 | 状态/边界 |
| --- | --- | --- |
| `ModelProvider` | `provider/api/.../ModelProvider.kt` | 已落位；以源码的流式契约为准 |
| `AgentRuntime` | 无同名端口；`core:agent` 有 `TurnReducer`，生产聊天仍由 `app` 的 `ChatService` 编排 | 目标形态；不得把 `ChatService` 当作已完成的唯一 Turn coordinator |
| `ContextBuilder` | `core:agent/.../ContextBuilder.kt` 的 `object ContextBuilder` | 已落位；不是 interface |
| `Tool` / `ToolRegistry` | `tools:framework` 的 `ToolDescriptor` + `ToolExecutor` + `ToolImplementationRegistry`，以及具体类 `ToolRegistry` | 已落位的工具执行契约与伪代码形状不同 |
| `PolicyEngine` | `core:policy/.../PolicyEngine.kt` 的具体类 | 已落位；动态风险与封闭结果以源码为准 |
| `ApprovalRepository` | `core:storage/.../ConversationRepositories.kt` 的具体类 | 已落位；只有类型化 `APPROVED` 可铸造/消费 Proof |
| `CapabilityResolver` | `core:policy/.../CapabilityResolver.kt` | 已落位 interface |
| `WorkspaceFileSystem` / `CodeExecutor` / `BrowserController` / `McpClientFacade` | 无同名生产端口 | 分别待 M4 / M5+M8 / M6 / M7 落位 |
| `SkillRepository` | `core:storage` 已有同名元数据 repository；本节的 content/hash 加载端口尚未落位 | 不得将同名存储类误当成 M7 Skill 运行时 |
| `ToolExecutor` | `tools:framework/.../ToolExecution.kt` 已使用该简名表示进程内单调用执行者 | 本节带 `target/execute/cancel` 的跨执行域形状仍是目标端口；后续落位时必须避免同包同名和旁路 Dispatcher |

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

以上状态图是 `core:model` `TurnState` 的规范性转移集合，完整矩阵由 `StateMachinesTest` 守卫。三条 HXA-011 增量边和 HXA-010 既有恢复/丢弃边的图面澄清由 [ADR-0002](../adr/0002-turn-state-intra-response-edges.md)记录（accepted）；上下文每模型响应构建一次，不在每个工具调用之间重建。

终态：`COMPLETED`、`FAILED`、`CANCELLED`。`INTERRUPTED` 可由用户恢复，但恢复前必须检查是否存在可能已产生外部效果的 ToolCall。

Goal 另有 `DRAFT/READY/RUNNING/INPUT_REQUIRED/PAUSED/COMPLETED/FAILED/CANCELLED` 状态，不允许把 Goal 和单个 Turn 合为同一张状态表。

### 5.3 Agent Loop 伪代码

```kotlin
repeat(../MAX_STEPS) {
    ensureActive()
    val context = contextBuilder.build(../sessionId, turnId)
    val response = provider.stream(../context).persistAsItArrives()

    when (../response.terminal) {
        is FinalText -> return complete(../response.terminal)
        is ToolCalls -> {
            for (../call in response.terminal.calls) {
                val validated = toolValidator.validate(../call)
                val policy = policyEngine.evaluate(../validated)
                val result = when (../policy) {
                    Allow -> dispatcher.execute(../validated)
                    AskUser -> awaitAndConsumeApproval(../validated, policy)
                    Deny -> ToolResult.denied(../policy.reason)
                }
                persistToolResult(../call, result)
            }
        }
        is ProtocolError -> return fail(../response.terminal)
    }
}
return fail(../StepLimitExceeded)
```

规则：

- Provider 流事件边接收边持久化，不能只在完整结束后保存。
- 同一 Turn 内多个 ToolCall 第一版串行执行，避免审批和副作用竞态。
- 工具异常必须转换为结构化 `ToolError`，不能把堆栈直接发给模型。
- 用户拒绝也是合法 ToolResult，Agent 可以调整计划，但不得重复请求完全相同的高风险动作。
- Act/Goal 都持有 `TurnBudgets`：最大输入 token、输出 token、累计 token 和模型调用数。每次模型调用前计算剩余 step/token；限额取用户配置和 Provider capability 中更严者，无法准确 tokenizer 时使用保守字节估算，不得将未知 usage 当作 0。

### 5.4 Context Builder 契约

`ContextBuilder` 属于 `core:agent`，输入是持久化的会话/Turn 快照和 Provider capability，输出是可审计的 `ContextBuildResult`。每个 item 至少包含 `sourceType`、`sourceId`、`trust`、`contentRef/contentHash` 和估算 token。

裁剪顺序必须确定性且有测试：保留 system/mode/policy 契约、当前用户指令、未完成的 ToolCall 完整参数、审批上下文和对应 ToolResult；再按时间和相关性裁剪旧消息。不可将工具 JSON 或审批摘要截成语义不完整的文本。超限 ToolResult/文件转换为有界 summary + Artifact 引用/hash，后续通过 `read(../offset, maxBytes)` 分块获取。Secret 永不进入 Context；Web/File/MCP/Skill/Notification/Accessibility 内容标记 `UNTRUSTED`。

## 6. 模型协议与 Provider

### 6.1 内部统一事件

```kotlin
sealed interface ModelEvent {
    data class TextDelta(../val text: String) : ModelEvent
    data class ReasoningDelta(../val text: String) : ModelEvent
    data class ToolCallStarted(../val index: Int, val id: String, val name: String) : ModelEvent
    data class ToolArgumentsDelta(../val index: Int, val jsonFragment: String) : ModelEvent
    data class ToolCallFinished(../val index: Int) : ModelEvent
    data class Usage(../val inputTokens: Long?, val outputTokens: Long?) : ModelEvent
    data class Refusal(../val safeReason: String?) : ModelEvent
    data class Error(../val code: ModelErrorCode, val retryable: Boolean) : ModelEvent
    data class Completed(../val finishReason: String?) : ModelEvent
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
- ChatGPT/Claude 消费者订阅只允许通过官方 CLI 自己登录；token 不进入主 App。详见 [专项方案](provider-mcp-skills-modes.md)。
- Provider 数据去向根据规范化后的实际 endpoint 分类为 `ON_DEVICE_LOOPBACK`、`USER_AUTHORIZED_LAN`、`PUBLIC_CLOUD` 或 `CUSTOM_REMOTE_UNKNOWN`；模板名、自建标签和手工声明不能替代 endpoint 校验，也不代表端点可信。
- Provider 请求在发送前携带可审计的数据类别清单。API key、OAuth token、Cookie、密码、验证码和认证字段始终拒绝进入请求；联系人、通知、位置、文件正文、浏览器或 Accessibility 内容按 ADR-0012 的 Safety Profile 门控。
- `STANDARD` 的高敏数据每次发送都展示 Provider、规范 origin、数据类别和 scope；`ADVANCED` 只允许保存绑定 Provider ID + origin + 数据类别 + scope + 有效期的规则，新 origin/类别或规则过期时重新确认。

### 6.3 会话附件与多模态请求

目标架构遵循 [ADR-0014（accepted）](../adr/0014-session-attachment-materialization.md)；路线图对应任务完成并留下验收证据前不得写成当前能力。系统文件选择器或 Photo Picker 返回的一次性 URI 先经受限导入 pipeline 复制为会话 Workspace 中的 app-private Artifact，再与用户消息绑定；这条单文件路径不依赖 persisted SAF tree grant，长期目录 scope 仍由独立 `DocumentTreeScope` 负责。

请求物化第一阶段只有两个明确分支：经 MIME、扩展名和有界字节 probe 一致确认的 UTF-8 文本形成带 Artifact 来源、hash 和 `UNTRUSTED` 标记的有界 context item；图片由 app 层受限 resolver 解析为现有 `ImageReference`，三套 Provider adapter 只负责协议编码。UTF-16、PDF/PPT/DOC、音频、视频和未知二进制统一返回 `UNSUPPORTED_ATTACHMENT_TYPE`，以封闭 category 区分原因。不注册文档解析/渲染/OCR、媒体解码/抽帧/音轨/转码或 Provider file upload，也不把二进制 base64 放进模型 Context。文件管理器读取原始字节不代表模型理解对应媒体。

附件在点击发送前不出网。每次请求都对绑定时 Artifact hash 重新核验，并由 egress Policy 展示和绑定 Provider ID、规范 origin、消息、附件类型/大小/数据类别/scope 与 hashes；任何内容、Provider 或 origin 变化都重新评估。vision 为未知/不支持时保留本地图片并返回可操作错误，不能静默丢弃。图片解码、归一化和总请求字节使用集中预算；具体上限由对应 HXA 的 API 29/36 与低内存真机证据固化。未支持文档类型在出网前终止，不创建模型请求。

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

### 7.2 手机端确定性调度

详细且规范性契约只在[手机端 Tool 编排方案 §3](mobile-tool-orchestration.md#3-首版确定性-tool-scheduler)维护。本文只保留跨层不变式：并发性由平台生成的 effect footprint 决定，仅已证明不冲突的读取可并行；结果按原始 call sequence 回填；未启动取消持久为 `CANCELLED_BEFORE_START`，已启动项必须对账 terminal/unknown outcome；只有相同 envelope、确认零副作用且不降低隔离的技术失败可有界重试。

必须维护 `model-visible ⇔ persisted` 不变量：所有进入下一次模型请求的 ToolResult、用户回答、委托结果和 compaction summary 都能从持久事件及 content hash/ref 重建。瞬时 telemetry 可以更详细，但不能成为恢复的唯一真相。

### 7.3 后期委托与 Workflow 边界

当前产品仍是单 Agent Tool Loop。proposed [ADR-0009](../adr/0009-bounded-local-orchestration.md)只允许 HXA-105 评估两项后期能力：

1. developer/Advanced 的深度 1 只读 child delegation；child 不持有 Approval Proof、Secret、Root/Automation session 或写工具，需要变更时只向父 Turn 返回 proposal。
2. 有版本 JSON DAG 的声明式 Workflow 子集；所有节点编译回同一 Dispatcher，不执行模型生成的 JS/Starlark Policy/Workflow，也不允许自修改插件。

Goal 已提供持久目标、预算和恢复，不再另建 ralph/无限自治生命周期。Remote Worker、云端任务舰队、cloud diff apply、任意 Agent peer 通信和递归群体编排继续不实现。

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

审批绑定必须使用两步哈希，字段集与 `core:policy` 的 `ApprovalBinding` 一致：

```text
argsHash = SHA-256(../UTF-8(CanonicalArgs(arguments)))

bindingHash = SHA-256(../UTF-8(canonical JSON object {
  argsHash, executionTarget, scopeRef, schemaHash, sessionId,
  toolCallId, toolName, toolVersion, uiToken
}))
```

`ApprovalBinding.canonicalJson` 使用固定字母序键顺序与完整 JSON 转义。九个绑定字段的任意一个变化都生成新 `bindingHash`；`argsHash` 绑定完整规范参数，不是 UI 摘要。

参数、代码、命令、文件列表、scope、session、execution target、UI token、工具版本或输入/输出 schema 的变化会通过上述九字段使旧审批失效。`timeout`、`maxOutputBytes`、`requiredCapabilities`、`operationClass`、`baseRisk`、`idempotency` 和 origin 等 descriptor 契约字段**当前不直接进入** `bindingHash`；它们不是运行期可调参数，任何变化都必须提升 `toolVersion`，由版本字段使旧审批失效。首个非 `time.now` 业务工具进入生产前，HXA-042 必须用机械门禁/合同测试证明这条版本纪律，或先通过 proposed ADR 改为覆盖完整安全 descriptor 的 contract hash；在此之前不得声称单独修改 timeout 已自动撤销旧审批。

审批记录和执行授权必须分开表达：`DENIED` 可以持久化和显示为已处理决定，但只有明确批准且未过期的记录可以生成/消费 `ApprovalProof`。Dispatcher 不得把 `decision != null` 或 `consumedAt != null` 单独解释为获准执行；HXA-034 必须覆盖拒绝、过期和并发消费。

Safety Profile 不是 Tool 参数或模型可见的可写 Capability。切换 Profile 只能由设置 UI 中的用户操作触发，进入 `ADVANCED` 不自动授予系统权限、创建 scope、安装 Runtime、请求 Root 或允许网络 origin。

### 8.1 审批 UI 与授权模式

- 通用 L2/L3 使用 `ASK_EACH_TIME`：每个 ToolCall 都展示规范化后的完整授权摘要；一次用户操作可以批准界面中已披露的有限调用列表，但每个调用仍生成独立、一次性的精确 proof。批准后修改参数、scope、origin、代码、target 或 transient token 必须重新询问。
- 模型、MCP、Skill、网页、通知或 Runtime 都不能代替用户按下批准，也不能请求系统生成“帮我批准”的决定。拒绝后同一 Turn 不得用相同动作骚扰式重试。
- 产品不提供全局 `FULL_ACCESS`、`AUTO_APPROVE_MODEL` 或“Advanced = 全部批准”。`Full Workspace Access` 仅是用户选择的较宽文件 scope。Profile 切换、Android 权限、Root/Shizuku/ADB 状态、Runtime 安装和 Tool Approval 是彼此独立的状态。
- ADR-0012 的 Advanced 高敏出网规则是精确绑定 Provider/origin/数据类别/scope/期限的可撤销 Policy 规则，不是通用 Tool Approval Proof；Trusted Workspace/有界工具规则必须固定 tool/version/contract/scope/target 等绑定，且动态风险不高于 L1。
- 低风险长期规则必须具备期限、参数上限、撤销入口和 fail-closed 恢复测试。任何会减少通用 L2/L3 精确批准的设计仍属于安全边界变更，必须先有新 ADR、攻击测试和所有者接受。

## 9. 数据模型

### 9.1 Room 表

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `sessions` | id, title, providerId, modelId, createdAt, archivedAt | 会话元数据 |
| `messages` | id, sessionId, turnId, role, kind, contentRef, sequence | 时间线内容 |
| `message_attachments` | messageId, artifactId, ordinal, purpose, boundSha256 | 规划字段：消息到不可变 Artifact 快照的有序关系；ADR-0014 接受并完成 schema migration 后才可视为落位 |
| `turns` | id, sessionId, state, stepCount, startedAt, endedAt, errorCode | Agent Turn |
| `model_calls` | id, turnId, providerSnapshot, state, usage, requestId | 模型调用，不存 secret |
| `tool_calls` | id, turnId, callId, name, version, argsJson, argsHash, state | 工具请求 |
| `tool_results` | id, toolCallId, status, summary, contentRef, verified | 工具结果 |
| `approvals` | id, toolCallId, bindingHash, decision, decidedAt, consumedAt, expiresAt | `decision` 仅 `APPROVED`/`DENIED`；`bindingHash` 是 ApprovalBinding 全量哈希（tool/version/schema/scope/session/target/UI token/args，canonical JSON，HXA-034）；只有 `APPROVED` 且未过期可生成类型化 Approval Proof 并一次性消费（consume 在 SQL 原子守卫内复核哈希与 `expiresAt`，迁移行默认过期 fail closed） |
| `interaction_receipts` | id, sessionId, turnId, requestId, version, questionSummary, state, createdAt, expiresAt, answerHash, answeredAt | 结构化用户问题的一次性 receipt；状态为 `PENDING/ANSWERED/CANCELLED/SUPERSEDED`，回答仅保存 hash；表中无 Approval binding/proof 字段，不能替代 Tool Approval Proof |
| `executions` | id, toolCallId, runtime, limitsJson, exitCode, signal | 代码/命令执行 |
| `artifacts` | id, sessionId, relativePath, mediaType, size, sha256 | 产物索引 |
| `audit_events` | id, correlationId, type, actor, redactedPayload, timestamp | 审计 |
| `provider_configs` | id, displayName, protocol, endpoint, model, headersJson, secretAlias, capabilitySnapshot | 无明文 key |
| `runtime_installs` | id, type, version, state, manifestHash, installedAt | PRoot/RootFS |
| `plans` / `plan_steps` | objective, version, hash, state, evidenceRef | 版本化计划 |
| `goals` / `goal_runs` | objective, criteria, budgets, state, planId, planHash, nextCheckpoint, correlationId, 累计计数器（runCount/modelCalls/toolCalls/totalTokens/runTimeMillis/currentWakeMillis/retries，ADR-0004）, lastWakeReason, error, finishReason / goalId, wakeReason, outcome, startedAt, endedAt, wakeDurationMillis, modelCalls, toolCalls, tokens | 持久目标与唤醒记录；PAUSED 原因使用稳定 outcome + 同事务 audit 表达，不只依赖进程内 effect |
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

会话附件是上述 SAF 默认工作流的受限特例：目标固定为
`input/attachments/<attachment-id>/`，原始 URI 只在平台 adapter 的短生命周期内使用；导入后以 Artifact/hash 参与发送、重试和恢复，消息/Context/审计/诊断只保留净化来源元数据。源附件绑定消息后不可原地修改；图片归一化产生新的派生 Artifact，并在取消、失败或会话删除时按引用关系回收。PDF/PPT/DOC、音频和视频当前不产生任何解析、渲染、抽帧、音轨或转码派生 Artifact。

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
- 通用 URL Policy：解析得到的全部 A/AAAA 地址先规范化和分类，HTTP client 只能尝试本次已验证的地址集合；连接时保持原 hostname 的 Host/SNI/证书验证，并复验实际 peer address。每次重定向重新执行 scheme/origin/DNS/IP/credential Policy，拒绝 IPv4/IPv6 编码变体、DNS rebinding、云 metadata 和越出授权 scope 的地址。

## 12. 生命周期与并发

- `AppContainer` 创建进程级 Repository、Provider Factory、Tool Registry 和 Runtime Supervisor。
- 单个 `SessionTurnCoordinator` 使用 `Mutex` 保证一个会话只有一个活动 Turn。
- 不同会话使用有限并发 Dispatcher，默认最多 2 个活动模型 Turn。
- 代码执行全局最多 1 个，避免手机资源争用。
- Application 退后台不意味着 Turn 自动结束；是否提升前台服务取决于任务类型和用户可感知状态。
- UI 通过 Repository/Runtime 的 `Flow` 恢复，不持有执行 Job 所有权。

### 12.1 Companion Runtime Supervisor

- `RuntimeSupervisor` 是进程级客户端，不属于 Activity/ViewModel。它检查目标 package 是否安装/启用、签名是否匹配，并用 explicit `ComponentName` + signature permission + `BIND_AUTO_CREATE` 冷绑定；进程预先存活或用户手动打开 Runtime 都不是前置条件。
- 切换 `ADVANCED`、应用启动和被动 Registry 刷新不安装、不启动、不绑定 Runtime。只有用户点击“验证/修复/登录”时可建立零 Job 握手，或对应 ToolCall 已通过 Registry、Policy、Approval 且输入快照已固定后，才允许绑定并提交 Job；进程是否存活不影响已验证 descriptor。
- `ServiceConnection` 连接后先完成 protocol/runtime/ABI/capability 握手。未安装、禁用、被强制停止、签名/版本不符时 fail closed，不得回退到主 App shell、QuickJS 或权限更大的执行目标。
- 每个 Job 绑定 `executionId/jobId`、input manifest hash、deadline 和 output limit。Runtime 在独立 UID 私有目录原子记录有界 journal/terminal commit；主 App Room 仍是 Agent/审批权威，Runtime journal 只提供恢复证据。
- Binder death、`DeadObjectException` 或主 App 恢复后，先重连并按 jobId 查询；只接受与输入 hash 一致的完整 terminal record/output manifest。无法证明结果时进入现有 `ExecutionState.INTERRUPTED`，不得重新提交原命令。
- 无活动 Job、结果传输或登录交互后主动解绑，允许系统回收 Runtime。CLI 首次登录/重新认证可以打开 companion UI，但正常调用不要求该 UI 在前台。
- 需要退后台继续的用户主动任务，只有存在与真实工作匹配的 foreground service type 时，才由 Runtime 自己进入 started + bound 前台服务并显示停止通知；`dataSync` 不能伪装任意 Shell/CLI 计算。无合法类型时任务保持前台有界，并在退后台时暂停或取消。
- 前台服务仍可能被系统/OEM 终止。若真机证明确需 wake lock，只能在用户可见前台服务的 `RUNNING` 窗口持有带硬超时的 `PARTIAL_WAKE_LOCK`，并在所有 terminal/cancel/timeout 路径释放；不得持有无限 wake lock。
- 强制停止、禁用、设备重启或 OEM 限制后不自动启动 Runtime、不自动恢复或重放 Job；UI 显示中断/不可用及恢复步骤。若平台要求显式恢复 stopped package，只在用户点击后打开 companion 的最小设置/修复入口。

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
- PRoot/CLI Runtime 未运行且未手动打开时可按需冷绑定；空闲解绑后可被回收，下次 Job 可重新冷启动。
- Runtime Binder 断连后按 jobId 查询/对账；没有匹配 input hash 的 terminal proof 时停泊为 `INTERRUPTED`，不会自动重放。
- 需要后台继续的 Runtime Job 使用与真实用途匹配的用户可见前台服务；任意计算不冒充 `dataSync`，所有可选 wake lock 都有硬超时和释放证据。
- 主进程崩溃重启后，活动 Turn 不会静默标记完成。
- UI 源码中不存在 DAO、OkHttpClient、QuickJs 或 PRoot 直接调用。
- Tool 实现没有静态全局 Context。
- 参数改变后审批 proof 必定失效。
- 未经 Registry/Policy/Approval 管线无法调用任何变更工具。
- Plan 模式无法注册或执行 mutating tool；Goal 恢复不重放不明确副作用。
- WebView、Accessibility 和 Root 的动作都能追溯到同一个 ToolCall/Approval correlation chain。
- MCP schema 或 Skill content hash 变化后，正在运行的 snapshot 不被静默替换。

## 17. 面向 LLM 的工程设计

Helix 区分两种“面向 LLM”：

1. **运行时面向 LLM**：模型面对稳定、短小、强类型的 Tool/Model/Event 契约，只负责提出意图、参数和策略；平台负责权限、确定性执行、验证、恢复与审计。
2. **开发时面向 LLM**：编码 Agent 能用有限上下文定位责任、理解调用契约、执行验收并从唯一状态源续接，不依赖阅读整仓库或猜测隐式约定。

按这个定义，Helix 的目标运行时架构高度面向 LLM：`ModelEvent` 统一协议差异，`ToolDescriptor`/Schema 封闭模型可表达空间，Context Builder 管理可信度与预算，Dispatcher 把概率性 ToolCall 收敛到确定性安全管线，`model-visible ⇔ persisted` 保证回填可恢复。当前实现是**部分达标**：`core:agent` reducer 显式表达了 M1 状态转移，但尚未成为生产唯一语义来源；开发侧的模块边界、ADR、HXA、完成记录和纯 JVM 合同测试较好，仍有少数大文件要求编码模型一次加载过多无关责任。

### 17.1 对 LLM-Oriented Design Patterns 的取舍

[warlockee/llm-oriented-design-patterns](https://github.com/warlockee/llm-oriented-design-patterns)提出 Context Management、Feedback Loop、Tooling 三组原则。它是基于单个 Python/LLM 训练框架重构经验形成的设计宣言，不是跨语言标准或经独立验证的通用架构方法；其中的数字应视为案例结果，不能直接作为 Android 项目门禁。

| 主张 | Helix 判断 | 本项目采用方式 |
| --- | --- | --- |
| 小而单一职责的模块 | 合理，但固定 800 LOC 只能作气味提示 | 先看职责、依赖和测试 seam；超过约 600 LOC 进入复核，超过 1000 LOC 且混合职责时必须形成拆分计划，不按行数机械切文件 |
| 文件顶部 calling spec | 方向合理，不应复制一套容易漂移的接口文档 | 公共 Kotlin 类型、KDoc、不变量、错误/副作用说明和合同测试共同构成 calling contract |
| 纯函数、Schema 与逻辑分离 | 合理 | Reducer、canonical 编码、Schema validator、Policy 计算优先纯 JVM/无 Android 状态；I/O 留在 adapter/repository |
| Registry/平面分发优于深继承 | 有条件合理 | 使用封闭 enum、sealed interface 和类型化 Registry；不采用字符串 `dict`、反射或动态 import，因为它们削弱编译期校验、供应链边界和可审计性 |
| 薄 Orchestrator | 合理 | 顶层对象描述阶段顺序，阶段逻辑由可独立测试的 collaborator 承担；但安全管线保留一个公开入口，不能为“变薄”暴露旁路 |
| 多层反馈与自动自愈 | 只接受可测反馈，不接受无界自适应 | 每阶段返回结构化结果并持久化；只有确认零副作用、相同 envelope、同/更强隔离的技术失败可有界重试，不能自动改参数、扩大 scope/权限或更换执行目标 |
| 确定性逻辑封装成 Tool | 核心原则合理 | 模型决定“请求什么”，平台封闭“能否做、如何做、如何验证”；Tool 可以有受控副作用，不要求所有 Tool 都是纯函数 |

因此，本项目不接受“OOP/SOLID 天然是 context poison”“Strategy 一律不如字典分发”“所有工具都必须无副作用”这类绝对化结论。Kotlin 的 value class、sealed hierarchy、接口隔离和构造注入能同时提供上下文压缩、编译期穷尽检查与安全边界，通常比动态分发更适合 Helix。

### 17.2 当前结构热点与优化顺序

下表 LOC 是 2026-09-01 复核时的工作树快照，只用于表达相对规模，不是验收门禁；职责和契约比行数更权威。

| 热点 | 当前判断 | 优化要求 |
| --- | --- | --- |
| `core:agent` reducer 与生产聊天链路 | HXA-039/ADR-0010 已选择新的 batch-safe application `TurnCoordinator`：生产 Turn 以 batch aggregate phase + 每调用 ToolCall 状态表达并发、结算和 unknown outcome；当前 ModelCall/stream checkpoint 与模型可见回填由 coordinator 统一持有。M1 串行 `TurnReducer` 只保留历史测试和旧恢复兼容 | 新生产路径不得调用旧 reducer；旧 `RECORDING_TOOL_RESULT → WAITING_APPROVAL/RUNNING_TOOL` 边只作兼容。后续改变 batch/恢复契约必须取代 ADR-0010，并继续用聊天、乱序结算、取消、失败和恢复 fixture 验证 |
| `ChatService.kt`（约 1600 LOC） | HXA-038 已抽出 `ModelStreamState`；HXA-039 又把 Turn/ModelCall checkpoint、合法状态推进和事务化终局/回填抽到 `TurnCoordinator`。本类仍承担 egress/send gate、Tool Loop 调用、ToolCall 准备、审批卡/Timeline 投影和 UI facade | 继续按 egress gate、tool pipeline adapter、timeline/approval projection 三个真实 seam 渐进提取；UI 仍只依赖一个 application-service facade，不新增 Manager/DAO 旁路 |
| `ToolDispatcher.kt`（853 LOC） | 文件偏大，但八段安全管线具有强顺序不变量，机械拆分类会增加绕过风险 | 保留唯一公开 `dispatch` facade；新增能力导致阶段继续增长时，只抽取 package-internal validator/approval/execution/result/audit phase，并用端到端合同测试证明阶段不可跳过 |
| 三套 Provider SSE reader | UTF-8、行边界、data framing 存在相似实现，重复修复风险较高 | 先建立三协议共享 framing golden tests，再抽取无 vendor 语义的 `SseFramer`；各 Provider 的事件映射、终止和错误语义继续独立 |
| `ConversationRepositories.kt`（约 710 LOC） | 多个 repository 同文件，运行时边界尚清楚但开发上下文过宽 | 后续触碰对应 repository 时按聚合根拆文件，不改变 `HelixStorage` 组合入口或事务语义 |
| `TurnReducer.kt`（694 LOC） | 体量较大但纯函数、单一状态机、测试密集 | 不因 LOC 单独拆分；只有状态族出现独立不变量和独立测试 seam 时再提取 transition helper |
| `AppContainer.kt`（201 LOC） | 手工 composition root，依赖方向清晰，没有深工厂链 | 保留；按 feature 增长可抽取 package-internal assembler，但不引入 Hilt 或 Service Locator |

### 17.3 面向 LLM 的项目级约束

- 每个公开边界写清输入、输出、失败、持久化、副作用和权限；优先由类型和测试表达，不维护与源码重复的大段 calling spec。
- 面向模型的名字保持短而稳定；面向平台的实现保持类型化、可穷尽、可审计。模型看到的简单不等于内部使用字符串和弱类型。
- 拆分以“减少完成一个改动所需的无关上下文”为目标，同时把跨文件不变量集中在 facade/contract test；不追求最小文件数量或最少 LOC。
- 反馈必须转化为结构化状态、指标或可操作错误；日志不是唯一事实，自适应不能越过 Policy、Approval、预算和恢复规则。
- 先测再抽象：只有至少两个真实调用方或重复协议逻辑，并且能写共同合同测试时，才建立共享抽象；不为未来猜测创建 Manager/Factory/Provider-of-Provider。
- 领域状态机只能有一个生产语义来源。UI/application service 不得以直接 DAO 状态写入复制 reducer 转移；临时迁移期必须用 characterization test 锁定旧行为，并按事件族逐段切换。
