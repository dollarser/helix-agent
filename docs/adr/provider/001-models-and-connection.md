# ADR-PROVIDER-001: Provider 分类、模型选择、元数据与连接验证

Status: accepted
Date: 2026-09-16
HXA: HXA-166, HXA-190, HXA-191, HXA-222
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

用户需要按会话选择模型，并能区分账号可用、服务端声明与真实能力。模型目录和推理参数不能写死。Provider 可以来自用户配置的 API/自建服务、订阅/managed account，或设备内模型资产；这些是产品 provisioning 方式，不等于 transport 或数据 residence。执行位置、认证方式和产品来源都不应隐式决定它能否驱动完整 Agent loop。

## Decision

- Provider 管理首页固定为本地模型、API / 自建服务（填写 endpoint）、订阅账号三个入口；进入分类才显示 Provider 子列表及该类新增/管理入口。分类不改变底层正交维度。
- 网络配置表单顺序为名称、endpoint、API Key、高级选项（自定义 header 名称和值）、模型 ID。API Key 始终显示且可留空，服务端认证失败由连接测试报告；编辑时留空保留已有密钥，不因模板非必填而删除。高级 header 仍受原有白名单约束，不能代替凭据存储。
- 保存前可显式在线发现模型并多选，也可手输 ID。发现使用当前表单和临时凭据，不能保存临时 Provider 或连接成功状态；明文 endpoint 仍需先确认，过期响应不得覆盖修改后的表单。用户选择持久保存，但不充当能力检测证据。
- 订阅 Provider 的操作统一为管理订阅登录、上下文窗口、连接测试、能力检测；保留各适配器的可用性与真实账号边界。

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

### On-device runtime baseline

- `LocalModelProvider` 只依赖 framework-free `LocalInferenceRuntimePort`，复用现有 `ModelEvent`；主进程通过非导出的 `:model_runtime` 服务和 typed Binder 调用 JNI，llama.cpp 是 backend，不运行 HTTP server。
- 首版 CPU、一个 loaded model、一个 active generation；资产只读 PFD handoff，主进程与服务分别验证 SHA-256/大小。模型及 generation handle 是进程本地 ownership，不是 durable Turn identity。
- 大请求/结果通过文件描述符传输，各限 1 MiB；首版 native 完整生成后解析为 text/reasoning/tool/usage/terminal，再发出既有事件。此版本为有界缓冲输出，不承诺首 token 流式延迟。使用资产内置 chat template，不按模型名字猜格式；工具最终仍由 Harness 校验。
- 取消必须得到 executor exit 或 Binder death 证明；仅收到请求/ACK 不释放 owner。强制终止后的请求返回 `LOCAL_CANCEL_TIMEOUT`，不能返回成功；进程死亡不重放旧 generation。
- 默认 context 4096、2 threads、greedy decoding；现有上下文设置允许显式选择 1024–32768，实际不超过模型 metadata 上限。metadata/probe/generation 使用同一配置，不因查询 metadata 重置 context。首版不接受图片、非零 temperature 或自定义 stop sequences。模板自身 stop markers 和工具 grammar 由 backend 处理，grammar 不替代工具校验或保证答案正确。
- 加载前使用当前可用内存、权重大小与 F16 KV 估算及保留空间检查资源预算，失败返回 `LOCAL_RUNTIME_OOM`，允许用户降低 context 重试，不静默缩小请求配置。估算不是跨架构/OEM 的容量保证，Binder death 仍须处理。输出预算耗尽返回 usage + `LOCAL_OUTPUT_LIMIT`，不执行截断的调用；能力探测对明确不支持图片的设备内 runtime 执行文本/工具阶段并保持 vision=false，不影响网络 Provider 的五阶段探测。
- 模型不打包入 APK。显式 direct HTTPS URL + SHA-256 + 大小下载，Range 重试、临时文件校验及原子发布；每资产最多 8 GiB、总资产 12 GiB、最多 16 个，仅保留一个待续传文件。切换模型不自动删除此前下载；用户可先明确确认清理后再下载其他模型。清理只处理私有下载目录内已识别的普通残片文件及模型资产目录中的中断发布副本，与下载共用互斥锁并校验确认快照；忙碌或快照变化时保留文件并允许刷新重试。占用展示是已识别模型与下载残片的逻辑文件大小，不代表应用总占用。模型文件在开发期 Room baseline 重建后重新登记为未测试。
- native source 固定 commit 与 archive SHA-256，许可证随 APK 携带；版本事实和设备验收边界写入 HXA-222 证据。共享 UID 的 private process 提供 crash/lifecycle 隔离，不构成凭据安全沙箱。

自配置 Provider 的协议可由用户显式选择 OpenAI Chat Completions、OpenAI Responses 或 Anthropic Messages，不由品牌模板锁定。协议变更使在线目录结果失效；保存与目录探测使用所选协议，保存后的能力与连接需按现有流程重新验证。订阅及本地模型不暴露无意义的 HTTP 协议切换。

## Alternatives considered

不以目录第一项或名称猜廉价模型；不把认证成功显示成全部能力通过；不以修改全局 Provider 设置实现会话切换。不把本地模型限制为摘要/辅助调用，也不把 `127.0.0.1` 假 endpoint 当作正式 `OnDeviceLocal` 抽象。不采用 `ProviderKind = API | SUBSCRIPTION | LOCAL` 单枚举，因为它会把 provisioning、transport、residence 与 auth 四个不同问题压成一个维度，并错误地把 Ollama/SGLang/vLLM 或 managed subscription 分类成特殊 Agent 路径。

## Consequences

同一主题使用一份有效契约。`ProviderConfig`/`ProviderDescriptor` 使用类型化 connection；Room v1 baseline 持久化 provisioning/transport/auth，非法组合 fail closed。设备内推理免于 network egress 提示，但随后工具效果不因此免于授权。实现和 host 编译不等于真实模型完整 Agent loop、性能、热或内存验收；accepted 始终只表示设计决定。

## Verification

验证目录/认证失败、模型切换的并发拒绝、推理重置、目录变更、reasoning-only 流及请求名称映射；增加分类矩阵：同一 Ollama/SGLang/vLLM 模板的 loopback/LAN/public endpoint 必须得到不同 residence，但 transport 均为 Network；managed subscription 必须是 Network + ManagedAccount 而不是独立 transport；on-device 配置不得含 endpoint/protocol/secret。设备内 Provider 还需覆盖完整 Agent loop 的工具调用、长上下文、错误恢复、资源/热/内存、模型资产完整性和与 network Provider 的同任务 eval。真实账号或设备验证按各自显式授权执行。

## Decision history

- 2026-09-29：所有者要求自配置 Provider 支持协议选择，协议与厂商品牌解耦。

- **2026-09-29**：所有者明确上述三级来源入口、可选 Key、配置字段顺序、模型发现多选及订阅操作一致性要求。

- **2026-09-16**：接受会话级 Provider/model 选择、真实能力探测、认证与连接验证边界。
- **2026-09-25**：明确设备内本地模型是一等 `ModelProvider`，允许直接驱动完整 Agent loop；模型运行位置不再被用作工具调用/Agent 能力限制，能力由 probe/eval/设备资源决定。
- **2026-09-26**：将 Provider 分类收敛为 provisioning × transport × residence × auth 四个正交维度；产品 UI 仍可呈现 API/Self-hosted、Subscription、On-device 三组。明确 Ollama/SGLang/vLLM 始终属于 endpoint-based Network transport，loopback 不等于 `OnDeviceLocal`；managed subscription 也仍是 Network transport。

- **2026-09-28**：落实已授权 HXA-222 private-process/JNI 部署，记录单模型 CPU、有界 PFD 缓冲输出、资产恢复与 cancel/exit 合同；真实模型设备验收独立保留。
- **2026-09-28（资源收口）**：根据 API36 32K 被 LMK 终止、metadata 重置配置及输出截断的实测，采用可调有界 context、加载前内存检查、模板工具 grammar 与保留 usage 的输出上限错误；将运行时正确性与模型任务正确性分别验收。

- **2026-09-28（空间管理）**：补充下载残片显式确认清理、互斥与过期确认保护，保留单个待续传文件边界，不删除共享目录或已安装模型。

- **2026-09-28（发布残留）**：显式清理覆盖中断发布副本；确认绑定实例、发布 revision 与 metadata，发布中拒绝重入清理，已安装资产不纳入残留。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [Provider 设置实现与验证](../../evidence/development/provider-settings-2026-09-29.md)

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history — 2026-09-30：探测归属与实际模型

历史审查修复保持现有配置契约：异步探测只能向开始时同一配置生命周期发布目录、窗口、能力与连接状态；修改/删除或更新探测会使旧结果失效。网络工作随调用方取消，已获准的短本地发布完成收尾。压缩与普通请求均按实际会话模型解析支持的推理强度，不能从默认模型能力推断另一模型支持 LOW。实现与验证边界见[审查收口](../../bug-fixes/2026-09-30-historical-correctness-audit.md)。
