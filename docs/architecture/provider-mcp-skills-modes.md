# Helix Provider、MCP、A2A、Skills 与 Agent 模式架构

文档状态：Baseline 1.4
基线日期：2026-09-03

## 1. 设计目标

本方案解决六个相互关联但必须分层的问题：

1. 用多种模型协议驱动同一个 Helix Agent Loop。
2. 通过 MCP 发现外部工具、资源和 Prompt；首版只执行 tools，resources/prompts 只展示有界 metadata。
3. 通过 A2A 发现用户配置的外部 Agent，并把有界远端任务映射为普通 Helix ToolCall。
4. 通过 Agent Skills 为模型提供可复用流程、脚本和资料。
5. 提供 Chat、Plan、Act、Goal 四种运行模式。
6. 为未来远程 Worker、云端沙箱和桌面配对保留稳定执行抽象，但当前不实现传输协议。

模型 Provider、MCP Server、A2A Agent、Skill 和执行目标不是同一概念：

- Provider 产生模型事件。
- MCP Server 提供工具/资源/Prompt。
- A2A Agent 接收任务并返回消息、状态和 Artifact；它是外部服务，不进入 Helix `ExecutionTarget`。
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
    fun stream(../request: ModelRequest): Flow<ModelEvent>
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

“自建”或产品名称不等于“数据留在本机”。Helix 必须根据规范化实际 endpoint 标记 `ON_DEVICE_LOOPBACK`、`USER_AUTHORIZED_LAN`、`PUBLIC_CLOUD` 或 `CUSTOM_REMOTE_UNKNOWN`；同一个 Ollama/SGLang 模板可以落入任一类别。residence 只描述数据去向，不表示服务可信，也不能降低输入、输出、认证或 Prompt Injection 防护。

Ollama 支持部分 OpenAI API，包括 Chat Completions、Responses、streaming 和 tools；其 Responses stateful 字段并非全部支持。SGLang 提供 OpenAI-compatible endpoint，但不同模型的 tool-call parser 需要服务端正确配置。Helix 必须依赖能力测试，不根据产品名称猜测。

### 2.6 数据敏感度与发送门控

Provider 请求在发送前形成用户可见、可审计的 `EgressSummary`，至少包含 Provider ID、protocol、规范 origin、residence、数据类别、scope 和正文是否被裁剪。规则如下：

| 数据类别 | 示例 | `STANDARD` | `ADVANCED` |
| --- | --- | --- | --- |
| 普通内容 | 用户本轮主动输入、公开网页引用 | 显示当前 Provider/origin，按会话正常发送 | 同 Standard |
| 高敏内容 | 联系人、通知正文、精确位置、文件正文、浏览器页面、Accessibility 内容 | 每次发送前确认，不提供永久允许 | 默认逐次确认；可保存绑定 Provider ID + origin + 类别 + scope 的可撤销规则，期限仅 1h/24h/7d/30d，默认 24h、最大 30d |
| 禁止发送 | API key、OAuth token、Cookie、密码、验证码、认证字段、CLI credential | 拒绝 | 拒绝，不能通过专家设置放行 |

自建/LAN/loopback Provider 仍按相同数据分类执行；用户对某个 LAN host 的网络授权不等于同意发送全部通知、文件或屏幕内容。Provider endpoint、residence、数据类别、scope 或规则有效期任一变化都要重新门控。规则不滑动续期；当前时间早于 `createdAt` 或不早于 `expiresAt` 时按已过期处理。Safety Profile 契约见 [ADR-0012](../adr/0012-capability-first-advanced-grants.md)。

## 3. 订阅账号后端的诚实边界

ChatGPT Plus/Pro 与 Claude Pro/Max 不是普通 API Key 套餐。官方 CLI Android 路线已由
HXA-111/112 证明当前不可生产实现。经 accepted [ADR-0021](../adr/0021-third-party-subscription-protocol-adapter.md)，
后续改为研究显式标注为“第三方、非官方”的订阅协议 adapter。Helix 不提取浏览器 Cookie、
不复制其他 App/CLI token；adapter 只能在独立 Runtime UID 内通过用户主动 OAuth 获得自己的 grant。

允许的凭据所有权方式为：

- 首选但当前不可行：官方 Codex/Claude CLI 在独立 Runtime 内登录并持有凭据。
- 新实验路线：第三方 adapter 在独立 Runtime 内完成用户主动 OAuth，自行持有和刷新凭据；
  主 App 只看 provider/login state，不接收 access/refresh/id token。

二者放入可选的 `cli-runtime` 独立 APK/UID：

```text
Helix main app
  └─ signature Binder/PFD
       └─ cli-runtime APK (../INTERNET, private app data)
            └─ explicitly third-party subscription protocol adapters
```

约束：

- `cli-runtime` 与离线 `proot-runtime`、主 App 使用不同 UID。
- 登录 URL 交给 Helix 浏览器或系统浏览器打开；token 不返回主 App。
- adapter 版本、来源、hash、许可证和服务条款必须锁定；不得把它描述为官方 CLI/SDK。
- Runtime vault 的候选集合为 Codex、Claude、Grok 与 GitHub Copilot；进入 vault 只表示可隔离
  保存其未来 OAuth grant，不表示登录、endpoint 或 Provider 已获准或实现。
- GitHub Copilot 官方 SDK 支持项目自有 GitHub OAuth App，但 HXA-117 验证的当前官方 runtime
  只有 glibc/musl arm64 发布物，不能直接在 Android/bionic 加载，因此当前打包路线已停止；
  不得复用参考插件固定的 CLI client identity/internal token endpoint。Grok CLI/proxy 路线只
  保留侧载研究。
- CLI 的 Android/Linux arm64 可执行形态和底座（原生或独立 PRoot/RootFS）由 HXA-111/112 Spike 验证并记录 ADR，此图不预先假定 RootFS。
- 默认不给 CLI 真实手机文件、Android 权限或主 App secret，只给 Job snapshot。
- adapter 只转换模型流；不得注册其自带工具。模型产生的工具请求必须转换为普通 Helix ToolCall。
- 供应商未明确支持第三方消费订阅客户端是发布与稳定性风险；没有可核验授权时只能作为
  developer/Advanced 侧载实验，不得进入商店 artifact 或宣称官方支持。

首版正式支持 API key 和自建服务器；第三方订阅 adapter 仍是 P2 实验能力，不承诺不存在的
官方通用订阅 API。

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
- MCP 网络调用复用 Provider 的数据分类与 `EgressSummary` 语义；Advanced 规则绑定 MCP server ID + 规范 origin + 数据类别 + scope + 有效期，不能因 bearer 已配置而静默发送高敏内容。
- 不采用单纯“每 N 次调用提醒”作为安全边界。会话维护有界调用/出网摘要；endpoint、tool schema hash 或敏感数据类别变化时强制检查点，结束时提供脱敏发送摘要。

## 5. Agent Skills 与 A2A

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

### 5.5 A2A Client

#### 5.5.1 协议和 Android 落位

A2A 与 MCP 互补：MCP 连接 Agent 与工具/数据，A2A 连接彼此不共享内部状态的独立 Agent。Helix 首版只实现 A2A v1.0 Client；不托管 Server，也不把远端 Agent 当作 Helix `ExecutionTarget` 或拥有本机权限的执行器。`a2a.*` 的本地 `ToolExecutor` 只是一层普通网络 ToolCall 适配器，仍完整经过 Dispatcher/Policy/Approval/Audit。

HXA-077 Android Spike 已由 accepted [ADR-0018](../adr/0018-a2a-minimal-android-client-base.md) 选定稳定 `A2aClientFacade` 后的 OkHttp + kotlinx.serialization 最小 v1.0 Client；官方 Java SDK 1.3.1.Final 的 JVM record/Client 构造通过，但真实 Client 路径在严格 Android R8 下引用 `java.net.http` 且带入无关 gRPC/protobuf/CDI 图，因此只保留为 Spike 证据。SDK 类型、protobuf 类型和 transport DTO 均不得泄漏到 `core:*` 或 `tools:framework`。API 29/36 运行和真实 App APK/SBOM 仍是发布前门禁，不能由 standalone R8 代替。

首版能力边界：

- P0 transport 为 HTTPS 上的 JSON-RPC 2.0 或 HTTP+JSON/REST；根据 Agent Card 的 `supportedInterfaces` 与协议版本显式协商，不静默降级到 v0.3。
- 支持获取公开/扩展 Agent Card、SendMessage、SendStreamingMessage、GetTask、CancelTask 和 SubscribeToTask；ListTasks、gRPC、custom binding 与 extension 按 Spike 结果后置。
- Android 手机不适合充当稳定公网 webhook，首版不注册 push notification callback；长任务只在用户可见活动连接中订阅，或稍后用已保存 task ID 主动查询。
- 认证首版复用 SecretStore 的手工 bearer/header alias；OAuth/OIDC discovery、mTLS 和设备授权流必须另行设计，不能把 Provider/MCP secret 复用给 A2A endpoint。

#### 5.5.2 发现、动态工具和生命周期

保存配置时默认 disabled。用户完成连接测试后，Helix 保存 Agent Card hash、AgentInterface（url/binding/protocolVersion）、provider、capabilities、input/output modes 与 Skills snapshot，并逐项启用远端 Skill。每个启用项转换为固定 schema 的动态工具：

```text
a2a.<agentSlug>.<skillSlug>
```

工具参数只包含用户任务、选择的本地 Artifact/context refs、期望输出模式和有界执行选项；Agent Card description、Skill description 和远端消息全部标记 `UNTRUSTED_A2A_CONTENT`。Agent Card、Skill、endpoint、binding 或协议版本变化会产生新 contract hash，并撤销旧工具注册、长期规则和审批。

本地 `toolCallId` 必须与远端 `taskId/contextId`、Agent/Skill/interface snapshot、input hash、最后事件序号和不透明 SSE event ID 持久绑定。断线、取消、进程死亡或 App 重启后，只能 GetTask/SubscribeToTask（带已保存的 `Last-Event-ID`）对账同一个远端 task；SendMessage 是否到达不明确时进入 `NEEDS_REVIEW`，禁止用新 task 重发。远端取消是 best effort，服务端拒绝取消或状态未知必须如实展示。

#### 5.5.3 数据、Artifact 与授权边界

- 首版允许 text、结构化 data，以及由用户选择且 hash 复核通过的 Workspace Artifact 副本；内联 raw Artifact 在有界 base64 解码后由 Workspace 原子写入并重新计算 size/SHA-256。远端 file/URI 在独立的下载大小、MIME、重定向与 hash 门禁接入前一律拒绝，不能把任意 URL 当作已验证文件。
- 每次请求沿用网络 ToolCall 的 origin、数据类别、scope、预算、摘要和审计语义。可复用规则必须精确绑定 A2A agent ID、规范 origin、Agent Card/Skill contract hash、数据类别、scope 和期限。
- A2A Agent 不继承 Helix 的 pending approval、Approval Proof、Android Capability、Workspace scope、Secret、UI token、Root/Automation session 或本机工具表。
- 远端输出只能作为不可信 ToolResult/Artifact 回到父 Turn；若它建议写文件、操作 UI 或调用本机工具，Helix 必须创建新的本地 ToolCall，经同一 Dispatcher/Policy/Approval/Verification/Audit 管线处理。
- A2A Agent 的 `completed` 只证明远端协议 Task 已完成，不证明本机目标或远端副作用真实完成；Helix 模型应结合实际结果判断目标完成，远端状态不直接控制本机 Goal（ADR-0040）；本机工具副作用仍由工具验证器核实。

本节是 accepted [ADR-0016](../adr/0016-a2a-client-interoperability.md) 的目标边界；具体 Client 底座由 accepted [ADR-0018](../adr/0018-a2a-minimal-android-client-base.md) 固定。HXA-078/079 只能实现该最小 Client 边界；在 API 29/36 运行和完整互操作门禁补齐前，不得声称 M7 已设备验收或可发布。

#### 5.5.4 为什么 M7 先做 Client-only

这是一条交付顺序，不是永久产品禁令：

1. **先验证出站互操作。** Client 只需要 Android 主动建立 HTTPS/SSE 连接，能直接验证 Agent Card、Task、Artifact、取消和恢复，不要求手机具备稳定入站地址。
2. **Server 先从局域网前台会话开始。** Android 可在用户主动开启、持续可见的前台会话中监听明确网络接口；但公网托管还需要处理 NAT/动态 IP、TLS/域名、Doze/进程回收和前台服务限制，因此不能把“能监听端口”写成“稳定公网可用”。
3. **远程可达由用户选择基础设施。** Advanced 后续可以支持用户已有 VPN、反向隧道或自建 relay；Helix 不必为了首版双向协议先建设云控制面。直接公网暴露、平台云 relay 和第三方隧道应分别验收，不能共享模糊的“远程已开启”状态。
4. **反向调用先返回 proposal。** 远端 Agent 可以通过 A2A Task 返回结构化本机动作建议，由 Helix 父 Turn 转换为普通 ToolCall；只有未来定义远端身份、工具/scope、期限、重放、锁屏/离线和撤销后，才考虑让远端直接持有有界调用权。
5. **递归编排单独演进。** A2A 网络互操作不要求递归。父子/peer 图、预算传播、循环终止、级联取消和恢复由 HXA-105/ADR-0009 评估，避免协议连通性与多 Agent Runtime 相互阻塞。

建议的能力梯度是：`M7 Client → Advanced 前台 LAN Server → 用户自备 VPN/隧道/relay → proposal 型反向调用 → 经新 ADR 的有界远端 Tool scope`。任何阶段都必须保留真实 Task 状态、取消和重复副作用边界；Advanced 用户承担启用网络入口和外部基础设施的选择责任。

## 6. Agent 模式

### 6.1 模式语义

| 模式 | 目的 | 默认可用工具 | 是否持久运行 |
| --- | --- | --- | --- |
| Chat | 问答和解释 | 默认无工具；显式启用时仅 `operationClass=READ_ONLY` 且动态风险为 L0 | 否 |
| Plan | 调研并生成计划 | 仅 `operationClass=READ_ONLY` 且动态风险 ≤ L1（class 为主判断，风险上限不替代 class 判断） | 否 |
| Act | 完成当前交互任务 | 按 Policy 开放 | 当前 Turn |
| Goal | 持续推进目标，可附补充要求 | 按 Policy 开放，受预算和检查点约束 | 是，可恢复 |

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

Goal 是唯一跨轮自治原语，不另外实现 ralph/fresh-agent 无限循环。单个 Turn 内的安全 Tool 并发由[手机端 Tool 编排](mobile-tool-orchestration.md)的确定性 scheduler 负责，不改变 Goal 预算或审批。后期 child delegation 不是第五种用户模式：它只是父 Act/Goal 内部的只读执行单元，必须共享父预算，且须通过已接受 ADR-0009 的生产启用门禁后才可用。A2A Client 也不是第五种模式；它是父 Turn 发起的外部网络 ToolCall，不等于内部 child，也不取得本机执行权。

### 6.2 状态

```text
DRAFT → READY → RUNNING
                 ├─ INPUT_REQUIRED ─┐
                 ├─ PAUSED ─────────┴─► RUNNING（仅用户显式继续）
                 ├─ BLOCKED ──► PAUSED（解决依赖并重新检查）
                 ├─ COMPLETED
                 ├─ FAILED
                 └─ CANCELLED
```

恢复边（`INPUT_REQUIRED → RUNNING`、`PAUSED → RUNNING`）由用户显式 Continue 触发。BLOCKED 必须先解决阻碍并重新检查转 PAUSED，不能直接继续；预算耗尽不是完成。原因来自持久 run outcome 与 audit。模型使用当前 Goal 的 `goal.report` 报告 complete/in_progress/blocked；正常 Turn 结算且没有用户暂停、取消或未决副作用时，宿主消费当前轮最后有效报告。普通回复结束不等于 Goal 完成，也不因缺少证据绑定而阻塞。宿主检查执行约束，模型判断自然语言目标，详见 [ADR-0040](../adr/0040-model-judged-goal-completion.md)。

## 7. 数据模型扩展

Room 表和规范性关键字段只在 [总体方案 §9.1](overview.md#91-room-表) 定义。本专项不重复维护第二份 schema；语义上要求 `provider_configs` 保留 protocol/capability snapshot，`goal_runs` 保留真实 wake reason 和稳定 outcome（含 ADR-0004 的 pause reason），MCP/A2A/Skill 运行保留固定 schema/content hash。Secret 只保存 alias，MCP/A2A/Skill 大型正文、资源、schema 和 Agent Card 存文件并保存 hash。

## 8. 未来远程执行扩展点

当前领域枚举已定义 `LOCAL_ANDROID`、`LOCAL_QUICKJS`、`LOCAL_PROOT`、`LOCAL_CLI_RUNTIME` 四类本机目标，但生产工具执行路径目前只有 `LOCAL_ANDROID`（`time.now`）；其余三类仍分别等待 M5、M8、M11 的执行器和设备验收。目标定义不等于 Runtime 已实现。

跨执行域目标端口只在[总体方案 §4](overview.md#4-核心接口)维护；该处同时区分目标伪代码与当前 `tools:framework` 进程内 `ToolExecutor` 的同名不同形。Envelope 包含协议版本、ToolDescriptor hash、输入 hash、限额、审批 proof 引用、correlation ID 和产物 manifest。A2A Client 不使用这些远程执行扩展点；未来远程 Worker 必须通过新 transport 且仍进入同一 Dispatcher。HarmonyOS 以后实现 Platform Capability Adapter，不复用 Android 权限代码。

## 9. 完成标准

- OpenAI Responses、OpenAI Chat Completions、Anthropic Messages 分别有协议 fixture 和真实 smoke。
- Ollama/SGLang 通过能力探测成功调用文本和 ToolCall；不支持的字段被明确降级。
- MCP HTTP server 可 initialize/list/call/cancel，schema 变化使审批失效。
- A2A v1.0 Android/R8/transport Spike 通过；Agent Card/Skill 快照、Task 对账、取消、流式事件和 Artifact 边界有固定 fixture。
- Skill catalog 只预载 metadata，正文按需读取；恶意 zip 和越界 resource 被拒绝。
- Plan 模式不能执行写入/代码/UI 动作。
- Goal 可在进程重启后恢复，预算和模型报告保留，绝不自动重放不明确副作用。
- 单 Turn 多 ToolCall 只并行平台证明无冲突的读取，取消/恢复有持久结果，模型回填顺序不随完成速度变化。
- child delegation/JSON Workflow 须通过已接受 ADR-0009 的生产启用门禁后才可用；即使以后启用也不是新模式，不能继承审批或扩大父 Goal 预算。
- CLI 订阅后端只有在官方客户端持有凭据且 Helix 不接触 token 时才可启用；未完成安全 Spike 前不得列为正式 ModelProvider。
- Provider/MCP/A2A 数据去向取自实际 endpoint；Standard/Advanced 的高敏出网门控和禁止发送类别均有 Policy/UI 测试。

## 10. 主要依据

- [OpenAI Responses API](https://platform.openai.com/docs/api-reference/responses/create)
- [Anthropic Claude Code 登录](https://docs.anthropic.com/en/docs/claude-code/getting-started)
- [MCP Transport 规范](https://modelcontextprotocol.io/specification/draft/basic/transports)
- [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
- [A2A Protocol v1.0 specification](https://a2a-protocol.org/latest/specification/)
- [A2A Java SDK](https://github.com/a2aproject/a2a-java)
- [Agent Skills 规范](https://github.com/agentskills/agentskills/blob/main/docs/specification.mdx)
- [Ollama OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)
- [SGLang quickstart](https://github.com/sgl-project/sglang/blob/main/docs/docs/get-started/quickstart.mdx)
- [Pi coding agent](https://github.com/earendil-works/pi)

### Goal 阻塞与暂停更新（HXA-177）

[ADR-0039](../adr/0039-background-results-and-goal-blockers.md) 部分替代早期 PAUSED 三义：BLOCKED 记录待解决依赖，禁止直接 Continued；宿主在用户修复动作后复查，满足门槛才转 PAUSED，后续显式继续创建新 run。用户暂停保留原 Turn 的实际终态与暂停请求；Goal 不因暂停而取消。预算与证据仍跨 run 保留。此增量不增加自动续跑或子 Agent。

### HXA-178 模型报告替代强制绑定

ADR-0040 完全替代 ADR-0028，取消规则绑定、人工证据复核与独立 Goal verifier。成功 ToolResult 仅证明一次工具执行，不自动完成 Goal；模型报告附结果、检查和剩余工作，UI 明示为模型判断。旧绑定类型只用于历史存储兼容，不再进入运行时条件或完成门控。工具自身的验证、审批和权限不变。
