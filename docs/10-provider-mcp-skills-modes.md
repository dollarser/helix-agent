# Helix Provider、MCP、Skills 与 Agent 模式专项方案

文档状态：Baseline 1.3
基线日期：2026-08-31

## 1. 设计目标

本方案解决五个相互关联但必须分层的问题：

1. 用多种模型协议驱动同一个 Helix Agent Loop。
2. 通过 MCP 发现外部工具、资源和 Prompt；首版只执行 tools，resources/prompts 只展示有界 metadata。
3. 通过 Agent Skills 为模型提供可复用流程、脚本和资料。
4. 提供 Chat、Plan、Act、Goal 四种运行模式。
5. 为未来远程 Worker、云端沙箱和桌面配对保留稳定执行抽象，但当前不实现传输协议。

模型 Provider、MCP Server、Skill 和执行目标不是同一概念：

- Provider 产生模型事件。
- MCP Server 提供工具/资源/Prompt。
- Skill 提供按需加载的操作知识及可选资源。
- Execution Target 决定工具在哪里执行。

## 2. Provider 分层

### 2.1 内部统一协议

所有网络协议先转换为内部 `ModelRequest` / `ModelEvent`，Agent Loop 不读取供应商 JSON。

```kotlin
interface ModelProvider {
    val descriptor: ProviderDescriptor
    suspend fun listModels(): ModelCatalogResult
    suspend fun validateConfiguration(): ProviderCheckResult
    fun stream(request: ModelRequest): Flow<ModelEvent>
}

enum class ProviderProtocol {
    OPENAI_RESPONSES,
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES,
}
```

不要把“OpenAI 格式”只实现成一个模糊开关。Responses 和 Chat Completions 的请求、流事件、工具结果和状态语义不同，必须是两个 adapter。Claude 格式指 Anthropic Messages API adapter。

内部事件至少覆盖：文本、reasoning、工具调用开始、参数增量、工具调用完成、usage、结束原因、拒绝、服务端错误和连接中断。每个 adapter 都用供应商 fixture 验证乱序/拆包/多工具和截断。

### 2.2 Provider 类别

| 类别 | 配置 | 示例 | 首版 |
| --- | --- | --- | --- |
| 官方 API | API key + endpoint | OpenAI、Anthropic | P0 |
| OpenAI-compatible | base URL + key + model | DeepSeek、DashScope/Qwen、OpenRouter、Moonshot/Kimi、智谱、MiniMax、xAI、Groq | P1 模板 |
| 自建服务器 | LAN/HTTPS URL + 可选 key | SGLang、Ollama、vLLM、LM Studio | P0/P1 |
| 官方 CLI 订阅 | CLI 自己 OAuth 登录 | Codex CLI、Claude Code | P2 实验 |

“模板”只预填协议、官方 endpoint 形式和必要 header，不硬编码会过期的模型 ID。用户仍可手动新增兼容 Provider。

### 2.3 首批内置配置模板

P0：

- OpenAI：优先 `OPENAI_RESPONSES`，保留 Chat Completions adapter 兼容旧服务。
- Anthropic：`ANTHROPIC_MESSAGES`。
- Generic OpenAI-compatible：用户配置 base URL、API key、model ID。
- Ollama：默认 `http://127.0.0.1:11434/v1`，允许局域网明文必须单独开启并展示风险。
- SGLang：默认 `<server>/v1`，工具调用前运行能力探测。

P1 模板：DeepSeek、Alibaba DashScope/Qwen、OpenRouter、Moonshot/Kimi、Zhipu/GLM、MiniMax、xAI、Groq、vLLM、LM Studio。模板上线前逐个用官方文档和真实 fixture 验证，不能仅因“声称 OpenAI compatible”就认为所有 tool call 字段一致。

### 2.4 能力探测

```kotlin
data class ProviderCapabilities(
    val streaming: Boolean,
    val toolCalls: Boolean,
    val parallelToolCalls: Boolean,
    val vision: Boolean,
    val reasoning: Boolean,
    val jsonSchemaOutput: Boolean,
    val maxContextTokens: Long?,
    val source: CapabilitySource,
)
```

连接测试分为：

1. DNS/TLS/HTTP 和认证。
2. 模型列表（若服务支持）。
3. 最小文本流。
4. 最小工具调用 fixture。

用户可覆盖探测结果，但 UI 必须标记为“手动声明”。没有工具调用能力的模型只能进入 Chat/Plan，不得进入 Act/Goal；此时 Plan 只能使用用户已提供的上下文，UI 必须标明“未进行工具取证”。

### 2.5 自建服务网络规则

- release 默认只允许 HTTPS。
- 用户可以为明确的局域网 host 开启 HTTP；授权绑定 host + port，不是全局 cleartext。
- Android 模拟器访问宿主机使用 `10.0.2.2`，真机不能把 `localhost` 当电脑。
- 连接 SGLang/Ollama 时不自动扫描局域网。
- 证书错误不得静默降级 HTTP；自签 CA 通过用户显式导入/固定证书处理。
- Provider 请求仍只包含 Context Builder 选择的数据，自建服务器也不能直接读取手机文件。

Ollama 支持部分 OpenAI API，包括 Chat Completions、Responses、streaming 和 tools；其 Responses stateful 字段并非全部支持。SGLang 提供 OpenAI-compatible endpoint，但不同模型的 tool-call parser 需要服务端正确配置。Helix 必须依赖能力测试，不根据产品名称猜测。

## 3. 订阅账号后端的诚实边界

ChatGPT Plus/Pro 与 Claude Pro/Max 不是普通 API Key 套餐。Helix 不提取浏览器 Cookie、不复制其他 App token、不反向调用未公开接口。

允许的实现只有官方客户端拥有凭据的方式：

- Codex：运行官方开源 Codex CLI/app-server，由其执行 ChatGPT OAuth/device-code 登录、保存和刷新凭据。
- Claude：运行官方 Claude Code CLI，使用其公开的登录与 stream-json/SDK 接口；凭据由 Claude Code 管理。

二者放入可选的 `cli-runtime` 独立 APK/UID：

```text
Helix main app
  └─ signature Binder/PFD
       └─ cli-runtime APK (INTERNET, private app data, official CLIs)
            ├─ codex app-server
            └─ claude non-interactive/SDK process
```

约束：

- `cli-runtime` 与离线 `proot-runtime`、主 App 使用不同 UID。
- 登录 URL 交给 Helix 浏览器或系统浏览器打开；token 不返回主 App。
- CLI 版本、来源、hash、许可证和服务条款必须锁定。
- CLI 的 Android/Linux arm64 可执行形态和底座（原生或独立 PRoot/RootFS）由 HXA-111/112 Spike 验证并记录 ADR，此图不预先假定 RootFS。
- 默认不给 CLI 真实手机文件、Android 权限或主 App secret，只给 Job snapshot。
- 官方 CLI 通常是完整 Agent，不一定等价于“纯模型流 API”。因此在 Spike 证明能禁用/代理内置工具、保留 Helix 审批语义之前，只作为独立的“CLI 会话后端”，不能冒充 `ModelProvider`。
- 若不能可靠拦截 CLI 的工具和副作用，就不得让它驱动 Helix Act/Goal，只允许在隔离 Job 内使用。

首版正式支持 API key 和自建服务器；Codex/Claude 订阅列为 P2 实验能力。这样既提供可行路线，又不承诺不存在的通用订阅 API。

## 4. MCP Client

### 4.1 技术选型

直接依赖官方 [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk) 的 client artifact，基线 `0.15.0`，从 Maven Central 获取。SDK 使用 Ktor，Android 端选 `ktor-client-okhttp` engine。

正式接入前完成 Android Spike：API 29/36、R8、SSE/Streamable HTTP、取消、重连、前后台和大消息。若 SDK 在 Android 有阻断问题，保留 `McpClientFacade`，可在其后实现最小 JSON-RPC transport；不能让 SDK 类型泄漏到 Agent Core。

### 4.2 Transport

| Transport | 执行位置 | 优先级 | 说明 |
| --- | --- | --- | --- |
| Streamable HTTP | 主 App | P0 | 远程/局域网 MCP；支持认证 |
| stdio | PRoot Runtime | P1 | Runtime 启动固定、已安装 MCP Server |
| legacy SSE | 主 App | P2 兼容 | 仅对旧服务器开放并提示迁移 |

Streamable HTTP endpoint 必须做 origin、TLS、重定向和认证检查。stdio server 的 stdout 只能是 MCP JSON-RPC，stderr 单独有界记录。

### 4.3 MCP 生命周期

```text
保存配置（disabled）
  → 用户连接测试
  → initialize + protocol negotiation
  → capabilities snapshot
  → list tools/resources/prompts
  → 用户选择允许暴露的 tools；resources/prompts 仅查看 metadata
  → Agent session 建立短期连接
  → timeout/cancel/close
```

MCP 动态工具转换为 Helix ToolDescriptor：

```text
mcp.<serverSlug>.<toolName>
```

必须保存 server ID、协议版本、工具 schema hash 和 capability snapshot。服务器更新 schema 后，旧审批全部失效。

### 4.4 MCP Policy

- MCP server 提供的 `readOnlyHint`、`destructiveHint` 等 annotation 只作提示，不能降低 Helix 计算的风险。
- 新 server 默认禁用全部工具，由用户逐项或按风险启用。
- Server 名称、Tool description、Resource 内容均视为不可信文本。
- Tool output 有字节、内容块、图片尺寸和资源数量上限。
- 首版 HTTP MCP 认证只支持用户手工提供的 bearer token，保存在 SecretStore；不进入模型、Room 或日志。OAuth/PKCE 需单独威胁模型和 backlog，首版不实现。
- MCP resources/prompts 首版只保存有界 metadata/hash，不读取正文、不注入 Context、不转换为 Tool/Skill/system instruction。未来读取正文必须通过新的只读 Tool、Policy 和 `UNTRUSTED_MCP_CONTENT` 标记。
- stdio server 环境变量使用 allowlist；Provider key 不自动传入。
- MCP sampling/elicitation/roots 默认关闭，分别设计后再开放。
- 首版不实现 MCP Server 托管，只实现 Client。

## 5. Agent Skills

### 5.1 格式

遵循 [Agent Skills](https://github.com/agentskills/agentskills) 开放规范：每个 Skill 是包含 `SKILL.md` 的目录，frontmatter 至少包含 `name` 和 `description`，可带 `scripts/`、`references/`、`assets/`。

不直接把官方 `skills-ref` 当 production 依赖；其 README 明确定位为示范。Helix 用 Kotlin 实现规范所需的 frontmatter 校验、目录扫描和按需读取，并用官方规范 fixture 做兼容测试。

### 5.2 渐进加载

1. Discovery：只读取合法 skill 的 name、description、source 和 hash，生成 catalog。
2. Activation：模型选中后，`skills.read` 加载完整 `SKILL.md`。
3. Execution：仅在指令明确需要时，通过 `skills.read_resource` 读取 reference/asset；脚本必须走正常代码执行工具。

首批工具：

| Tool | 风险 | 说明 |
| --- | --- | --- |
| `skills.list` | L0 | 返回 metadata catalog |
| `skills.read` | L0/L1 | 加载完整指令，标记来源 |
| `skills.read_resource` | L0/L1 | 只读 skill 内相对路径 |
| `skills.install` | L2 | 用户从目录/zip 导入，验证后安装 |
| `skills.enable` / `skills.disable` | L1 | 会话或全局开关 |
| `skills.remove` | L2 | 删除已安装副本，不删除源目录 |

### 5.3 安装与信任

- 来源分 `BUILT_IN`、`USER_IMPORTED`、`PROJECT`；远程市场不在首版。
- zip 导入防 Zip Slip、symlink、文件数、单文件、总大小和压缩炸弹。
- `SKILL.md` frontmatter 严格校验；未知字段保留但不能自动授予权限。
- 安装时展示脚本、依赖声明、引用文件和 hash。
- Skill 是指令，不是权限。文本中的“允许 bash/root/browser”无效。
- Skill 脚本只能通过 `code.javascript.run`、`bash` 等已注册 Tool 执行，仍需 schema、Policy 和审批。
- Skill 更新产生新内容 hash；正在运行的 Goal 继续使用已固定 snapshot，下一次运行再升级。
- 内置 Skill 也不能绕过 Tool Policy。

### 5.4 首批内置 Skills

只内置小而可测试的流程，并按底层能力验收时间分期：

- `organize-files-preview`：先给文件整理计划和 diff，不自动删除。
- `web-research`：浏览器检索、记录来源、生成摘要。
- `android-ui-task`：M9/HXA-097；先 snapshot，再按节点动作，每步验证。
- `repo-inspection`：list/search/read，必要时 bash 运行只读命令。
- `data-transform`：优先 JS，失败后建议 PRoot。
- `notification-digest`：M7/HXA-076，依赖 HXA-065；用户选择应用和时间窗后摘要。

## 6. Agent 模式

### 6.1 模式语义

| 模式 | 目的 | 默认可用工具 | 是否持久运行 |
| --- | --- | --- | --- |
| Chat | 问答和解释 | 默认无工具；显式启用时仅 `operationClass=READ_ONLY` 且动态风险为 L0 | 否 |
| Plan | 调研并生成计划 | 仅 `operationClass=READ_ONLY` 且动态风险 ≤ L1（class 为主判断，风险上限不替代 class 判断） | 否 |
| Act | 完成当前交互任务 | 按 Policy 开放 | 当前 Turn |
| Goal | 持续推进有验收条件的目标 | 按 Policy 开放，受预算和检查点约束 | 是，可恢复 |

Plan 不是“模型说一段计划文字”。它产生版本化 `PlanArtifact`：

```kotlin
data class PlanArtifact(
    val id: PlanId,
    val objective: String,
    val assumptions: List<String>,
    val steps: List<PlanStep>,
    val acceptanceCriteria: List<String>,
    val risks: List<String>,
    val version: Int,
)
```

Plan 模式完成后，用户明确选择“按此计划执行”才创建 Act 或 Goal；原计划 hash 写入运行记录。

Goal 是持久化目标：

```kotlin
data class Goal(
    val id: GoalId,
    val objective: String,
    val acceptanceCriteria: List<Criterion>,
    val state: GoalState,
    val planId: PlanId?,
    val budgets: GoalBudgets,
    val nextCheckpoint: Checkpoint?,
)
```

`GoalBudgets` 至少包含最大模型调用、工具调用、累计 token、运行时长、单次唤醒时长和失败重试次数。Goal 模式不扩大权限：L2/L3 仍逐次审批；权限撤销、目标包变化和不明确副作用会暂停为 `INPUT_REQUIRED`。

首版只有用户显式继续才创建新 `goal_run`。WorkManager 可在 `nextCheckpoint` 附近发提醒通知，但不得在后台调用模型/工具，且调度可被 Doze、强制停止和系统限制延迟。`wakeReason` 记录 `USER_OPEN`、`NOTIFICATION_ACTION` 等真实来源。

### 6.2 状态

```text
DRAFT → READY → RUNNING
                 ├─ INPUT_REQUIRED ─┐
                 ├─ PAUSED ─────────┴─► RUNNING（仅用户显式继续）
                 ├─ COMPLETED
                 ├─ FAILED
                 └─ CANCELLED
```

恢复边（`INPUT_REQUIRED → RUNNING`、`PAUSED → RUNNING`）只能由用户显式继续（`Continued`）触发，见 [ADR-0004](adr/0004-goal-run-wake-budget-semantics.md) 与 `GoalState` 全矩阵测试。只有验收条件由真实 ToolResult/Artifact verifier 支持时才能 `COMPLETED`。预算耗尽是 `PAUSED` 或 `FAILED(BUDGET_EXCEEDED)`，不是成功。

## 7. 数据模型扩展

Room 表和规范性关键字段只在 [总体方案 §9.1](02-architecture-design.md#91-room-表) 定义。本专项不重复维护第二份 schema；语义上要求 `provider_configs` 保留 protocol/capability snapshot，`goal_runs` 保留真实 wake reason，MCP/Skill 运行保留固定 schema/content hash。Secret 只保存 alias，MCP/Skill 大型正文、资源和 schema 存文件并保存 hash。

## 8. 未来远程执行扩展点

当前只实现 `LOCAL_ANDROID`、`LOCAL_QUICKJS`、`LOCAL_PROOT`、`LOCAL_CLI_RUNTIME`。现在定义：

```kotlin
interface ToolExecutor {
    val target: ExecutionTargetDescriptor
    suspend fun execute(request: ToolExecutionEnvelope): ToolResultEnvelope
    suspend fun cancel(executionId: ExecutionId): CancelResult
}
```

Envelope 包含协议版本、ToolDescriptor hash、输入 hash、限额、审批 proof 引用、correlation ID 和产物 manifest。未来远程 Worker 通过新的 transport 实现该接口；当前不写 socket、配对、云端账号或空网络模块。HarmonyOS 以后实现 Platform Capability Adapter，不复用 Android 权限代码。

## 9. 完成标准

- OpenAI Responses、OpenAI Chat Completions、Anthropic Messages 分别有协议 fixture 和真实 smoke。
- Ollama/SGLang 通过能力探测成功调用文本和 ToolCall；不支持的字段被明确降级。
- MCP HTTP server 可 initialize/list/call/cancel，schema 变化使审批失效。
- Skill catalog 只预载 metadata，正文按需读取；恶意 zip 和越界 resource 被拒绝。
- Plan 模式不能执行写入/代码/UI 动作。
- Goal 可在进程重启后恢复，预算和验收证据一致，绝不自动重放不明确副作用。
- CLI 订阅后端只有在官方客户端持有凭据且 Helix 不接触 token 时才可启用；未完成安全 Spike 前不得列为正式 ModelProvider。

## 10. 主要依据

- [OpenAI Responses API](https://platform.openai.com/docs/api-reference/responses/create)
- [Anthropic Claude Code 登录](https://docs.anthropic.com/en/docs/claude-code/getting-started)
- [MCP Transport 规范](https://modelcontextprotocol.io/specification/draft/basic/transports)
- [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
- [Agent Skills 规范](https://github.com/agentskills/agentskills/blob/main/docs/specification.mdx)
- [Ollama OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)
- [SGLang quickstart](https://github.com/sgl-project/sglang/blob/main/docs/docs/get-started/quickstart.mdx)
- [Pi coding agent](https://github.com/earendil-works/pi)
