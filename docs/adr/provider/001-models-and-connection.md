# ADR-PROVIDER-001: Provider 分类、模型选择、元数据与连接验证

Status: accepted
Date: 2026-09-16
HXA: HXA-166, HXA-190, HXA-191, HXA-222
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

用户需要按会话选择模型，并能区分账号可用、服务端声明与真实能力。模型目录和推理参数不能写死。Provider 可以来自用户配置的 API/自建服务、订阅/managed account，或设备内模型资产；这些是产品 provisioning 方式，不等于 transport 或数据 residence。执行位置、认证方式和产品来源都不应隐式决定它能否驱动完整 Agent loop。

## Decision

- 会话输入区只保留一个模型入口；模型选择展开页内继续选择推理强度。切换提交完成前不暴露旧模型推理选项，可用强度仍来自实际所选模型能力。

- Provider 管理首页按构建渠道提供入口：Standard/consumer 只有本地模型与 API / 自建服务；Advanced/developer 额外提供订阅账号。订阅受构建依赖、来源解析和适配器准入共同限制，不仅隐藏按钮；旧受管记录不删除但不能在 consumer 调用。配置页统一命名为“模型”，常驻分类直接切换相应来源，不再增加来源首页及重复标题。开发者包中的交互/权限模式不改变安装包渠道。
- **按接入方式分类，不按付费方式分类。** Kimi Code、MiniMax Token Plan 等若使用 endpoint + API Key，属于 `USER_CONFIGURED`，两渠道均可接入；不登记为 managed subscription，不要求专用登录 Runtime。套餐 Key 与按量 Key 的适用范围由厂商决定，Helix 不自动切换计费来源。
- 网络首次接入表单顺序为名称、endpoint、API Key、高级选项（自定义 header 名称和值）、初始模型。API Key 始终显示且可留空，服务端认证失败由连接测试报告；编辑时留空保留已有密钥，不因模板非必填而删除。已有来源的接入设置不承载模型多选，候选使用统一模型管理。高级 header 仍受原有白名单约束，不能代替凭据存储。
- 保存前可显式在线发现模型并多选，也可手输 ID。发现使用当前表单和临时凭据，不能保存临时 Provider 或连接成功状态；用户配置的 HTTP endpoint 只显示非阻塞风险提示，不再单独确认；过期响应不得覆盖修改后的表单。用户选择持久保存，但不充当能力检测证据。
- 三类来源共用模型管理：完整目录、显式有序候选与精确模型证据分开，不再设置新会话默认模型。新建会话仅继承当前会话的精确 Provider/model；没有当前绑定则为空，不从其他来源或候选顺序回退。旧偏好中的 default 字段被忽略，新写入不包含该字段。空选择不回退全量，目录刷新不改变偏好。API/订阅可手动添加模型 ID；设备内只允许已安装资产。候选隐藏不删除文件、不退出账号、不改变现有 Session/Turn 的目标。
- 订阅首页按管理登录、管理对话模型、检查账号连接排序；精确模型的基础生成测试、能力检测和上下文设置集中在模型详情，不在账号首页重复。模型名称与整行可勾选，只保留一个候选选择框。未接入完整目录的适配器明确范围，不能用单一模型回退伪装完整服务端目录；未返回上下文窗口时明确未知，估算值不能冒充官方上限。
- 模型偏好和显示名称更新不废止已有连接/能力证据；真实接入配置和相关本地运行参数变化仍失效。能力检测绑定精确 provider/model 与配置生命周期，不先修改来源基础模型；检测 B 不外推 A 或其他模型的工具能力。

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
- 基础生成测试保持一次无工具短请求，输出预算为 2048 tokens，为服务端默认推理留出空间。合法 `length` 结束但无内容记为 `OUTPUT_TOKEN_LIMIT`，不误报协议损坏，也不冒充生成通过；不隐式追加付费重试。
- 协议需要函数名转换时使用请求内确定映射，历史和返回调用共用映射。未知名称不能进入 Dispatcher，内部工具身份、参数、调用 ID 和授权绑定不改名。

### 选择回执、模型健康与账号生命周期

- 模型候选保存不等于会话切换。用户选择立即进入发送同序队列；对话框等待实际提交回执。忙碌、会话已变化和不可用分别反馈，持久模型与推理重置一起提交；不修改活动 Turn。
- 来源连接、精确模型基础生成、可选能力分开记账。基础模型 A 的普通生成错误不能使同来源 B 失效；认证/来源错误影响来源。订阅认证目录成功不产生基础生成成功证据；显式生成检测单独披露资源/额度使用。
- Runtime 公开本地账号状态与随机登录 revision，不公开凭据或账号身份。新登录、退出和损坏使旧账号证据与在途探测失效；刷新保留登录 revision。旧凭据刷新不得复活退出、覆盖新登录或删除较新的成功轮换。暂时不可查询不等于已退出。
- AgentLoop 以准入冻结的精确模型创建适配器，后续回填/压缩/继续使用同一目标。账号/接入状态变化不能静默换目标；本地校验不承诺撤回远端已经接受的请求。

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

- **2026-10-04**：按所有者要求，选模弹窗独立于已有 Provider 提供 API、本地模型及渠道允许的订阅配置入口，直接进入对应分类；空列表与搜索无结果仍能配置。模型页默认 API，保留当前分类并使用常驻分类切换，移除重复 Provider 标题及“返回模型来源”。来源切换不触发登录、下载或探测，返回沿用应用导航栈。

- **2026-09-30（订阅渠道与 Key 分类）**：所有者指定 consumer 只保留 API/本地模型；developer 的账号订阅顺序为 Codex、Claude、Google Antigravity、GitHub Copilot、Grok (X Premium)。Key 型套餐仍使用普通 API 表单和模型管理，详见[新增接入证据](../../evidence/development/subscription-antigravity-2026-09-30.md)。

- **2026-09-30 Provider 使用链路收口**：所有者要求完成使用链路、验证与候选提交，明确应用回执、三级健康、登录 revision 和实际适配器目标；不扩展为 Project Memory 或其他候选。实现及验证见[收口记录](../../evidence/development/provider-chain-closeout-2026-09-30.md)。

- **2026-09-30 模型管理统一**：所有者要求参考主流竞品优化三类 Provider。采用目录/选择器可见性分离、共同模型管理、精确模型参数与检测、友好本地名称及隐藏不打断会话。候选偏好不是权限来源；来源认证和各 Runtime 生命周期保持独立。实现、官方参考与验证见[模型管理记录](../../evidence/development/provider-model-management-2026-09-30.md)。

- 2026-09-30 Runtime 矩阵修复：设备内模型的 IO、取消控制和强退使用独立有界无队列通道；调用方超时不证明远端退出。尚未收到 generate 提交返回时，取消必须退役原进程，不能以过早的无 active 回执放行。原绑定死亡使旧 handle 失效，旧清理不得清除新绑定；拒绝请求也关闭 PFD。主机/设备证据分开记录于[故障矩阵](../../evidence/development/runtime-fault-matrix-2026-09-30.md)。

- **2026-09-30 审查修复**：用户模型选择和发送共用有序准入。已授权的 `helix.settings.apply` 可以通过绑定当前工具调用的专用入口修改会话未来默认值；不能改当前 Turn 冻结参数，所有 backfill/视觉目标继续读取 runtime snapshot。普通 UI 模型选择仍要求空闲，不放开任意活动会话 SQL 更新。Provider 连接、能力与精确模型上下文探测使用独立 ticket；配置变更统一撤销旧 ticket，连接结果不能覆盖独立的新能力证据。

- 2026-09-29：所有者要求自配置 Provider 支持协议选择，协议与厂商品牌解耦。

- **2026-09-29**：所有者明确上述三级来源入口、可选 Key、配置字段顺序、模型发现多选及订阅操作一致性要求。

- **2026-09-16**：接受会话级 Provider/model 选择、真实能力探测、认证与连接验证边界。
- **2026-09-25**：明确设备内本地模型是一等 `ModelProvider`，允许直接驱动完整 Agent loop；模型运行位置不再被用作工具调用/Agent 能力限制，能力由 probe/eval/设备资源决定。
- **2026-09-26**：将 Provider 分类收敛为 provisioning × transport × residence × auth 四个正交维度；产品 UI 仍可呈现 API/Self-hosted、Subscription、On-device 三组。明确 Ollama/SGLang/vLLM 始终属于 endpoint-based Network transport，loopback 不等于 `OnDeviceLocal`；managed subscription 也仍是 Network transport。

- **2026-09-28**：落实已授权 HXA-222 private-process/JNI 部署，记录单模型 CPU、有界 PFD 缓冲输出、资产恢复与 cancel/exit 合同；真实模型设备验收独立保留。
- **2026-09-28（资源收口）**：根据 API36 32K 被 LMK 终止、metadata 重置配置及输出截断的实测，采用可调有界 context、加载前内存检查、模板工具 grammar 与保留 usage 的输出上限错误；将运行时正确性与模型任务正确性分别验收。

- **2026-09-28（空间管理）**：补充下载残片显式确认清理、互斥与过期确认保护，保留单个待续传文件边界，不删除共享目录或已安装模型。

- **2026-09-28（发布残留）**：显式清理覆盖中断发布副本；确认绑定实例、发布 revision 与 metadata，发布中拒绝重入清理，已安装资产不纳入残留。

## Decision history — 2026-10-02：HTTP 只提示与渠道一致性（HXA-242）

所有者明确要求：自行配置的 API / 自建服务可使用 HTTP，风险告知不应成为额外授权或发送门槛。两渠道共用允许 HTTP 的 Android 网络配置；动态用户地址不能靠静态域名表枚举。移除 Provider 保存、发现、探测、聊天、Plan 与队列投递中的明文授权检查，以及旧确认框、绑定存储与查询 API；旧存储键不再读取，不因残留值阻塞请求。

配置表单、来源信息与当前会话提示连接未加密，可能披露消息、附件及 API Key，建议 HTTPS；不增加确认弹窗。HTTP 风险与工具授权、真实发送目标、模型选择、资源容量和结果真实性正交，其他检查不因该变更自动取消。保留默认系统 CA 与主机名校验，模型传输不自动跨 HTTP/HTTPS 协议重定向，不将 HTTPS 失败改为 HTTP 重试。订阅来源、模型下载与工具网络权限未因此获得新授权。

当前验收范围为主机测试、编译与实际 APK 静态配置核验；这条决定不冒充设备执行已通过。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [Provider 设置实现与验证](../../evidence/development/provider-settings-2026-09-29.md)

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history — 2026-09-30：探测归属与实际模型

历史审查修复保持现有配置契约：异步探测只能向开始时同一配置生命周期发布目录、窗口、能力与连接状态；修改/删除或更新探测会使旧结果失效。网络工作随调用方取消，已获准的短本地发布完成收尾。压缩与普通请求均按实际会话模型解析支持的推理强度，不能从默认模型能力推断另一模型支持 LOW。实现与验证边界见[审查收口](../../bug-fixes/2026-09-30-historical-correctness-audit.md)。

- 2026-09-30：所有者要求合并会话模型与推理入口；保持模型切换落库后再配置对应推理强度，不改变 Provider 权限和默认模型。
- 2026-09-30：真实 Antigravity 测试确认 16-token 探测返回空 `MAX_TOKENS`。基础生成测试预算调整为 2048，空额度结束独立分类，不扩大能力通过范围或自动追加请求。
- 2026-09-30：所有者要求所有订阅统一收敛账号与模型入口，移除默认模型概念，新会话继承当前精确模型、无绑定则为空。替代此前可空默认模型的设计；上下文上限仅呈现服务实际元数据，不把固定估算当成官方值。
- 2026-10-01：所有者要求移除模型管理及本地安装中的“用于当前会话”入口；模型管理只保存候选，实际选择统一在会话中完成。“模型与连接”提升为侧栏一级入口，排在 Work 上方；侧栏去掉 Recent 列表，保留当前会话与完整历史入口。

- 2026-10-01：Antigravity 私有回放先验证原始协议记录的完整性及账号、模型、调用身份绑定，再保留签名并使用 Harness 持久化的 canonical business args 回填。Workspace 相对路径绑定与展示字段剥离由 Harness 负责；适配器不得用原始模型参数相等检查拒绝合法规范化，也不通过回填授予执行权限。

- 2026-10-01（回放保留）：Antigravity 签名记录是 durable 会话依赖，不作为按条数淘汰的缓存。取消跨会话的 128 条清理；在未掌握所有会话、fork 和历史引用之前不得自动删除记录。单记录大小限制和原子发布保留；已被旧版本删除的签名无法凭空恢复。引用感知清理另行实现，不以自动删除有效历史换取空间。
