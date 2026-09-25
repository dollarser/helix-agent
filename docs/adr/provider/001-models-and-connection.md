# ADR-PROVIDER-001: 模型选择、元数据与连接验证

Status: accepted
Date: 2026-09-16
HXA: HXA-166, HXA-190, HXA-191, HXA-222
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

用户需要按会话选择模型，并能区分账号可用、服务端声明与真实能力。模型目录和推理参数不能写死。Provider 可以是远端 API、订阅适配器或设备内本地推理；执行位置不应隐式决定它能否驱动完整 Agent loop。

## Decision

- 用户在没有活动轮次或待确认发送时选择会话 Provider/model；草稿只改内存，首次发送才持久化。不修改其他会话或 Provider 默认值，不自动发送。历史消息保留，每轮保存实际目标快照，模型切换重置推理到默认。
- **本地模型是一等 Provider。** 设备内模型可以作为当前 Session/Turn 的主 `ModelProvider`，并在 capability 满足时直接驱动完整 Agent loop、工具调用与 Goal/Task 流程；摘要、标题、分类和纯聊天只是低风险验证/降级场景，不是架构上限。
- Provider 的运行位置与 Agent 能力正交。远端、本地、订阅通路都通过同一 `ModelRequest`/`ModelEvent` 和 capability 语义进入 AgentLoop；工具调用最终仍走统一 Dispatcher/Policy/Approval/Audit，本地模型不因在设备内运行而获得更高权限。
- 本地 Provider 使用可验证的本地 residence（例如 `ON_DEVICE_LOCAL` 或等价显式表示），不伪造 loopback HTTP endpoint。Provider/transport 契约应允许“无网络 endpoint”的实现；具体 JNI/进程内/private-process 推理引擎属于 Runtime 实现选择，不写死为 Provider 语义。
- 完整 Agent loop 的可用性由真实 capability probe、模型元数据、上下文容量、结构化/工具调用能力和任务 eval 决定。能力不足时必须 fail closed 或做用户可见降级；不能仅因为模型是“本地模型/小模型”就禁止 `toolCalls=true`，也不能仅因为支持 grammar/JSON 就假定其规划能力足够。
- 模型目录按 Provider/endpoint 和精确模型 ID 保存版本化公开元数据：推理选项、视觉、上下文窗口允许未知。未知不等于不支持，空推理列表表示不可选。不由模型名字猜能力、价格或固定维护服务端模型白名单。
- 推理选项使用有界 token 值；本地 OFF 表示不发送 effort，服务端 none 是独立选项。UI 与请求组装读取同一目录，发送前校验；无逐模型目录时，只能使用已有精确探测或用户可见配置，不外推到其他模型。
- 具有真实认证目录的订阅适配器以认证目录请求成功且有效为账号连接成功；不选模型、不发生成、不用缓存冒充认证成功。401 最多一次刷新。无此能力的适配器仍做真实连接验证。
- 连接成功不证明生成、工具、视觉、推理或配额。能力检测是用户显式动作，使用合成工具/图片且不执行设备副作用；未测标未知，不因某档推理未测而否定全部连接。
- 生成协议检查识别合法的推理/内容流事件，不能仅把可见 TextDelta 当唯一活性证据；仅成功建立网络连接也不能证明协议成功。
- 协议需要函数名转换时使用请求内确定映射，历史和返回调用共用映射。未知名称不能进入 Dispatcher，内部工具身份、参数、调用 ID 和授权绑定不改名。

## Alternatives considered

不以目录第一项或名称猜廉价模型；不把认证成功显示成全部能力通过；不以修改全局 Provider 设置实现会话切换。不把本地模型限制为摘要/辅助调用，也不把 `127.0.0.1` 假 endpoint 当作正式本地 Provider 抽象。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。本地模型因此需要模型资产、能力探测、资源/热/内存评估和本地 Runtime 实现，但不会另造第二套 AgentLoop 或权限体系。accepted 表示决定，不代表本地 Provider 已实现或已经通过设备任务验收。

## Verification

验证目录/认证失败、模型切换的并发拒绝、推理重置、目录变更、reasoning-only 流及请求名称映射；本地 Provider 还需覆盖完整 Agent loop 的工具调用、长上下文、错误恢复、资源/热/内存、模型资产完整性和与远端 Provider 的任务成功率对比。证据见实施状态和未来 Provider 对应代码任务；真实账号或设备验证按各自显式授权执行。

## Decision history

- **2026-09-16**：接受会话级 Provider/model 选择、真实能力探测、认证与连接验证边界。
- **2026-09-25**：明确设备内本地模型是一等 `ModelProvider`，允许直接驱动完整 Agent loop；模型运行位置不再被用作工具调用/Agent 能力限制，能力由 probe/eval/设备资源决定。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
