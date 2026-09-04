# ADR-0016: A2A Client 互操作边界

Status: accepted
Date: 2026-09-04
HXA: HXA-077
Deciders: Project owner（2026-09-04 明确接受 M7 Client-only A2A v1.0 互操边界；具体 SDK/transport 仍由 HXA-077 证据决定）
Supersedes: none
Superseded by: none

## Context

A2A Protocol v1.0 已成为独立 Agent 之间发现能力、提交任务、流式交换消息和传递 Artifact 的稳定开放协议。它与 MCP 的职责不同：MCP 连接 Agent 与工具/数据，A2A 连接彼此不共享内部实现的 Agent。本决定提出时，Helix M7 只规划 MCP Client 与 Agent Skills，无法以标准协议调用用户已有的远端专业 Agent。

Helix 同时坚持 Android 单机父 Agent：Agent Loop、Policy、Approval、审计、Workspace 和本机执行器都留在手机。把 A2A Agent 当成远程 `ExecutionTarget`、让其反向调用本机 Tool，或直接继承本机批准，会混淆远端服务调用与远程 Worker，并破坏当前恢复与授权边界。

官方 A2A Java SDK 已提供 Client、JSON-RPC、HTTP+JSON/REST、gRPC 和 Android HTTP adapter，但其 API 29、R8、Java record/serialization、SSE、体积与依赖树尚未在 Helix 工程和设备上验证。因此本记录只接受产品、协议和信任边界，不预先选定具体 SDK 或 transport 实现。

Client-only 是首个实现阶段，而不是对 Android 双向 A2A 能力的永久否定。Android 可以在用户主动运行的前台会话中托管局域网 A2A Server，也可以借助用户自建 VPN、反向隧道或 relay 获得远程可达性；但普通手机通常处于 NAT/动态网络之后，后台还受进程回收、Doze、前台服务用途及时长约束。稳定公网 Server/webhook 因而不只是增加一个 HTTP listener，还会引入可达地址、TLS、身份、入站生命周期和额外基础设施。

递归多 Agent 和远端反向调用也不是 A2A 兼容性的必要条件。前者新增父子/peer 图、预算传播、循环终止、级联取消和进程恢复；后者会把外部服务调用升级为联网设备控制面，需要定义远端身份、可调用工具/scope、授权期限、重放和离线行为。这些问题即使由 Advanced 用户承担更多风险，也仍需要确定的状态、费用和恢复语义，不能由“用户负责”替代。

## Decision

接受在 M7 增加 Client-only A2A v1.0 互操作，边界如下。本决定不选定 SDK/transport；只有 HXA-077 Spike 以 API 29/36、R8、体积、依赖和许可证据选出可用方案后，HXA-078/079 才能进入生产实现。若所有候选均不合格，必须停止并重新评估或取代本 ADR，不得为了落地而降低门禁：

1. Helix 只连接用户配置并通过连接测试的 A2A endpoint，不托管 A2A Server、公开 webhook 或 peer mesh。
2. A2A Agent 是外部服务，不是 Helix `ExecutionTarget`/远程 Worker。每个启用的远端 Skill 以 `a2a.<agent>.<skill>` 动态 Tool 暴露给父 Turn，并走完整 schema、Policy、Approval、执行限制、Verification 和 Audit 管线；HXA-079 必须增加独立的可信 A2A `ToolSource`/origin 类型，不能冒充现有 MCP source。
3. 配置固定 endpoint、Agent Card hash、AgentInterface（binding/version）与 Skill hash。任一字段变化都撤销旧工具注册、长期规则和审批，不静默降级协议版本。
4. 首版覆盖 Agent Card、SendMessage/SendStreamingMessage、GetTask、CancelTask、SubscribeToTask，以及有界 text、structured data 和 Workspace Artifact 副本。手机不注册 push notification callback；gRPC、custom binding、extensions、OAuth/mTLS 与 v0.3 compatibility 后置。
5. 本地持久化 toolCallId、taskId/contextId、snapshot/input hash 和事件进度。断线或进程死亡只查询/订阅原 Task；发送是否到达不明确时进入 `NEEDS_REVIEW`，不创建新 Task 重发。
6. 远端 Card、Skill、消息和 Artifact 均为不可信内容。A2A Agent 不继承 pending approval、Approval Proof、Android Capability、Workspace scope、Secret、UI token、Root/Automation session 或本机工具表；远端建议的本机动作必须由父 Turn 创建新 ToolCall。
7. 先以 `A2aClientFacade` 隔离协议 DTO。HXA-077 优先验证官方 Java SDK；若 Android/R8/体积/依赖不合格，则比较 OkHttp + kotlinx.serialization 的最小 v1.0 Client，不能让 SDK 类型泄漏到 core。

本决定只冻结 M7 的首阶段边界。后续能力若另行排期，建议按以下依赖顺序演进：Advanced 前台局域网 Server → 用户自备 VPN/隧道/relay 的远程可达 → 远端任务只生成本机 Tool proposal → 经独立 ADR 决定是否允许有界远端 Tool scope；递归/peer 编排继续由 HXA-105/ADR-0009 单独评估，不与网络接入绑在一起。

## Alternatives considered

1. **只保留 MCP 与 Skills。** 范围最小，但 MCP 不表达远端 Agent Task 生命周期，用户需要为每个 Agent 自制非标准 Tool wrapper，无法利用 A2A 生态。
2. **把 A2A Agent 实现为远程 ExecutionTarget/Worker。** 可让远端直接执行更广任务，但会引入授权传播、远端 verifier、Workspace 同步和 remote diff apply，超出当前单机产品边界。
3. **同时实现 A2A Client 和 Server。** 能让其他 Agent 调用 Helix，但 Android 后台、稳定入站地址、认证、用户可见前台服务和本机权限暴露成本明显更高；首版没有必要。
4. **直接固定官方 Java SDK。** 上游协议覆盖最完整，但 Android 兼容、R8、依赖和体积仍需实测；在 Spike 前固定会把候选变成未经验证的架构承诺。
5. **仅实现自有简化 HTTP Agent API。** 初始成本低，但失去 Agent Card、标准任务状态和生态互操作，且最终仍需维护另一套协议。

## Consequences

- Helix 可连接专业远端 Agent，而无需共享其内部模型、工具或记忆；MCP、A2A 与 Skills 分别承担工具、Agent 和流程知识三类扩展。
- M7 增加 HXA-077～079，并需要新的 `extensions:a2a` 模块、持久 Task 映射、动态 Tool bridge、fixture 与 Android 设备矩阵。
- A2A 会产生可选数据出网和远端费用；UI/Policy 必须按真实 agent ID、origin、Card/Skill snapshot、数据类别和 scope 表达，不能因“Agent 已启用”跳过普通网络调用边界。
- 不托管 webhook 意味着远端长任务不能主动唤醒已被系统停止的 App；首版依赖用户可见流式连接或后续主动 GetTask。
- 远端 `completed` 不是 Helix 本机任务的 verifier evidence，可能增加一次本地复验或后续 ToolCall，但避免把远端自述误当成真实副作用证据。
- Client-only 降低首版状态空间，但不封死能力路线：局域网 Server、用户自备远程通道和 proposal 型反向调用可以分别验证，不要求一开始就建设 Helix 云 relay。

## Verification

Current evidence：

- 2026-09-04，项目所有者明确接受本 ADR 的 Client-only 产品、协议和信任边界；该接受不等于 SDK 选型或实现验收。
- 官方 A2A 文档声明 v1.0 为稳定协议，并定义 Agent Card、版本协商、JSON-RPC/HTTP+JSON/gRPC binding、流式与 Task 生命周期。
- 官方 Java SDK 仓库提供 Client transport 和 Android HTTP client 代码路径；尚无 Helix API 29/36、R8、体积或设备证据。
- 截至本决定接受时，只完成文档和任务规划；没有 A2A module、依赖、生产实现或兼容性声明。

Required before HXA-078/079 production implementation（HXA-077）：

- API 29/36 验证 Agent Card、JSON-RPC/HTTP+JSON、SSE、取消、重连、大消息和 TLS/auth error；R8 release build 无反射/record/serialization 缺失。
- 比较官方 SDK 与最小 Kotlin Client 的 APK/方法数、依赖树、许可证、协议覆盖、维护成本和错误模型，选定一种或拒绝 A2A 实现。
- 固定 v1.0 fixture 覆盖版本不支持、接口替换、畸形 Card、重复/乱序事件、task cancel/get/subscribe、进程死亡和不明确送达。
- 证明远端不能获得本机 Secret/Approval/Capability/Tool Registry，Artifact 和结果限制在攻击输入下 fail closed。

## Reconsider when

- 官方 SDK 无法在 API 29/36 或 R8 下稳定运行，且最小 Client 的长期维护成本不可接受。
- A2A 协议出现不兼容的主版本，或 Android 官方提供更合适的系统级 Agent-to-Agent API。
- 产品要求 Helix 托管 A2A Server、接收 webhook、允许远端反向调用本机 Tool，或把远端 Agent 作为 Worker；这些不是协议上不可行，但均需要新的 Android 生命周期/可达性证据、任务恢复合同与 superseding ADR。
- API 29/36 真机证明前台局域网 Server 可稳定运行，或目标用户已普遍具备可复用的 VPN/自建 relay；届时可以优先提出 Server/远程可达能力，不必等待递归多 Agent。
- HXA-105 的内部 child delegation 被接受；届时仍需保持内部 child 与外部 A2A Task 的预算、授权和恢复语义分离。

## References

- [Provider、MCP、A2A、Skills 与模式](../architecture/provider-mcp-skills-modes.md)
- [产品需求](../product/requirements.md)
- [开发路线](../development/roadmap.md)
- [A2A Protocol v1.0 specification](https://a2a-protocol.org/latest/specification/)
- [A2A v1.0 changes](https://a2a-protocol.org/latest/whats-new-v1/)
- [Official A2A Java SDK](https://github.com/a2aproject/a2a-java)
- [ADR-0009：有界本机委托](0009-bounded-local-orchestration.md)
