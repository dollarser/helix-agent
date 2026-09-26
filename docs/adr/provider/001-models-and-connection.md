# ADR-PROVIDER-001: Provider 分类、模型选择、元数据与连接验证

Status: accepted
Date: 2026-09-16
HXA: HXA-166, HXA-190, HXA-191, HXA-222
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

用户需要按会话选择模型，并能区分账号可用、服务端声明与真实能力。模型目录和推理参数不能写死。Provider 可以来自用户配置的 API/自建服务、订阅/managed account，或设备内模型资产；这些是产品 provisioning 方式，不等于 transport 或数据 residence。执行位置、认证方式和产品来源都不应隐式决定它能否驱动完整 Agent loop。

## Decision

- 用户在没有活动轮次或待确认发送时选择会话 Provider/model；草稿只改内存，首次发送才持久化。不修改其他会话或 Provider 默认值，不自动发送。历史消息保留，每轮保存实际目标快照，模型切换重置推理到默认。
- **Provider 分类采用正交维度，不使用一个 `API | SUBSCRIPTION | LOCAL` 枚举同时表达所有事实。** 产品 UI 可以提供 `API / Self-hosted`、`Subscription / Managed account`、`On-device` 三组，但底层至少分别记录 provisioning/ownership、transport、residence 与 auth source。
- provisioning 表示“配置与凭据由谁管理”：当前目标值为 `USER_CONFIGURED`、`MANAGED_ACCOUNT`、`ON_DEVICE_ASSET`；它决定编辑权和账号/资产管理入口，不直接决定网络位置。
- transport 表示“模型请求怎么送达”：`Network(protocol, endpoint)` 或 `OnDeviceLocal`。`MANAGED_ACCOUNT` 通常仍走 `Network`；`USER_CONFIGURED` 也可以指向 loopback、LAN 或公网。
- auth source 与 transport/residence 正交：network Provider 可以无认证、使用 SecretStore alias，或由 managed account/token 提供认证。API key 是否存在不能用来判断 Provider 是否“线上/本地”。
- **设备内本地模型是一等 Provider。** `ON_DEVICE_ASSET + OnDeviceLocal` 可以作为当前 Session/Turn 的主 `ModelProvider`，并在 capability 满足时直接驱动完整 Agent loop、工具调用与 Goal/Task 流程；摘要、标题、分类和纯聊天只是低风险验证/降级场景，不是架构上限。
- Provider 的运行位置与 Agent 能力正交。network API、自建服务、managed subscription、on-device 都通过同一 `ModelRequest`/`ModelEvent` 和 capability 语义进入 AgentLoop；工具调用最终仍走统一 Dispatcher/Policy/Approval/Audit，设备内模型不因运行在本机而获得更高权限。
- `OnDeviceLocal` 使用显式 `ON_DEVICE_LOCAL` residence，不伪造 loopback HTTP endpoint；它与现有 `ON_DEVICE_LOOPBACK` 严格区分。前者是不经过网络 endpoint 的直接本地 runtime，后者仍是 HTTP/其他 network transport 指向 127.0.0.1/::1。
- **Ollama、SGLang、vLLM 属于自建 network Provider，而不是 `OnDeviceLocal`。** 只要 Helix 通过 endpoint 请求它们，就按规范 endpoint 计算 residence：loopback→`ON_DEVICE_LOOPBACK`，私网→`USER_AUTHORIZED_LAN`，公网/未知远端→相应 remote residence；不能按模板名、是否同一台设备部署或是否需要 key 猜测。
- 完整 Agent loop 的可用性由真实 capability probe、模型元数据、上下文容量、结构化/工具调用能力和任务 eval 决定。能力不足时必须 fail closed 或做用户可见降级；不能仅因为模型是“本地模型/小模型”就禁止 `toolCalls=true`，也不能仅因为支持 grammar/JSON 就假定其规划能力足够。
- 模型目录按 Provider transport identity 与精确模型 ID 保存版本化公开元数据：network identity 使用规范 endpoint；on-device 使用稳定非 URL 的本地 transport/asset identity。推理选项、视觉、上下文窗口允许未知。未知不等于不支持，空推理列表表示不可选。不由模型名字猜能力、价格或固定维护服务端模型白名单。
- 推理选项使用有界 token 值；本地 OFF 表示不发送 effort，服务端 none 是独立选项。UI 与请求组装读取同一目录，发送前校验；无逐模型目录时，只能使用已有精确探测或用户可见配置，不外推到其他模型。
- `MANAGED_ACCOUNT` 是 provisioning/auth 语义，不是第三种 transport。具有真实认证目录的订阅适配器以认证目录请求成功且有效为账号连接成功；不选模型、不发生成、不用缓存冒充认证成功。401 最多一次刷新。其实际模型请求仍由对应 network adapter/endpoint 执行，并遵守 network residence/egress 规则。无认证目录能力的适配器仍做真实连接验证。
- 连接成功不证明生成、工具、视觉、推理或配额。能力检测是用户显式动作，使用合成工具/图片且不执行设备副作用；未测标未知，不因某档推理未测而否定全部连接。
- 生成协议检查识别合法的推理/内容流事件，不能仅把可见 TextDelta 当唯一活性证据；仅成功建立网络连接也不能证明协议成功。
- 协议需要函数名转换时使用请求内确定映射，历史和返回调用共用映射。未知名称不能进入 Dispatcher，内部工具身份、参数、调用 ID 和授权绑定不改名。

## Alternatives considered

不以目录第一项或名称猜廉价模型；不把认证成功显示成全部能力通过；不以修改全局 Provider 设置实现会话切换。不把本地模型限制为摘要/辅助调用，也不把 `127.0.0.1` 假 endpoint 当作正式 `OnDeviceLocal` 抽象。不采用 `ProviderKind = API | SUBSCRIPTION | LOCAL` 单枚举，因为它会把 provisioning、transport、residence 与 auth 四个不同问题压成一个维度，并错误地把 Ollama/SGLang/vLLM 或 managed subscription 分类成特殊 Agent 路径。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。当前 `ProviderConfig(protocol, endpoint, secretAlias, ...)` 与 UI `managedExternally: Boolean` 只是 pre-HXA-222 的 network-first 实现，不是长期类型边界；HXA-222 实施时应收敛为 provisioning + transport + auth 的类型化 contract，并从 transport 派生 residence。本地模型还需要模型资产、能力探测、资源/热/内存评估和本地 Runtime 实现，但不会另造第二套 AgentLoop 或权限体系。accepted 表示决定，不代表 on-device Provider 已实现或已经通过设备任务验收。

## Verification

验证目录/认证失败、模型切换的并发拒绝、推理重置、目录变更、reasoning-only 流及请求名称映射；增加分类矩阵：同一 Ollama/SGLang/vLLM 模板的 loopback/LAN/public endpoint 必须得到不同 residence，但 transport 均为 Network；managed subscription 必须是 Network + ManagedAccount 而不是独立 transport；on-device 配置不得含 endpoint/protocol/secret。设备内 Provider 还需覆盖完整 Agent loop 的工具调用、长上下文、错误恢复、资源/热/内存、模型资产完整性和与 network Provider 的同任务 eval。真实账号或设备验证按各自显式授权执行。

## Decision history

- **2026-09-16**：接受会话级 Provider/model 选择、真实能力探测、认证与连接验证边界。
- **2026-09-25**：明确设备内本地模型是一等 `ModelProvider`，允许直接驱动完整 Agent loop；模型运行位置不再被用作工具调用/Agent 能力限制，能力由 probe/eval/设备资源决定。
- **2026-09-26**：将 Provider 分类收敛为 provisioning × transport × residence × auth 四个正交维度；产品 UI 仍可呈现 API/Self-hosted、Subscription、On-device 三组。明确 Ollama/SGLang/vLLM 始终属于 endpoint-based Network transport，loopback 不等于 `OnDeviceLocal`；managed subscription 也仍是 Network transport。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
