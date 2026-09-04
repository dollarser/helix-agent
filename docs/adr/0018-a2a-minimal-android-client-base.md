# ADR-0018: A2A v1.0 最小 Android Client 底座

Status: accepted
Date: 2026-09-04
HXA: HXA-077
Deciders: HXA-077 Spike（roadmap 明确授权以 Android/R8/依赖证据形成实现选型）
Supersedes: none
Superseded by: none

## Context

[ADR-0016](0016-a2a-client-interoperability.md) 已接受 M7 的 Client-only 产品、协议和信任
边界，但刻意没有选定 SDK 或 transport。HXA-077 必须先比较官方 Java SDK 与位于稳定
`A2aClientFacade` 后的最小 Client，才能创建生产 `extensions:a2a`。

Spike 固定官方 `org.a2aproject.sdk` Java SDK `1.3.1.Final`，并实际构造
`AndroidA2AHttpClient`、JSON-RPC 与 HTTP+JSON/REST 两种 `Client`。JVM 测试证明 v1 Agent
Card 的 Java record/Gson 反序列化以及两种 Client 构造可用。但是该组合的
`debugRuntimeClasspath` 含 26 个 program JAR，并通过 HTTP client 间接带入
`spec-grpc`、protobuf、CDI/Jakarta API。对保留真实两种 Client 路径的 bytecode 使用
AGP 9.3.2 内置 R8 9.3.16、`android-36` library 与 `minApi=29` 严格收缩时，构建因
`JdkA2AHttpClient` 引用 Android 不提供的 `java.net.http.HttpClient` 等类型而失败。Spike
不以 `-dontwarn java.net.http.**` 把平台缺失伪装成通过。

回退 Spike 只复用工程已有的 OkHttp `5.5.0`、`okhttp-sse` `5.5.0` 与
kotlinx.serialization JSON `1.9.0`，在 Helix 自有 `A2aClientFacade` 后验证：

- HTTPS 默认门禁，以及只供本机 fixture 使用的 literal loopback HTTP 例外；
- JSON-RPC 的 `application/json` 与 HTTP+JSON 的 `application/a2a+json`；
- 2 MiB request/response/event 上限、JSON object 校验和 header CR/LF 拒绝；
- SSE 事件、取消后 callback 抑制、由调用方携带 `Last-Event-ID` 的同 Task 重连；
- 1 MiB payload、无 `Content-Length` 时的流式上限和超限 fail-closed。

该最小组合的严格 R8 输出为 11 个 program JAR/AAR、16,064-byte DEX、169 method IDs、
37 classes。它证明 Android bytecode/依赖成本可行，不等于已完成 API 29/36 设备运行、
后台生命周期、TLS/auth 互操作或最终 App APK 增量验收。

## Decision

M7 生产 A2A Client 采用 Helix 自有、稳定的 `A2aClientFacade`，底层使用工程已固定的
OkHttp `5.5.0`、`okhttp-sse` `5.5.0` 与 kotlinx.serialization JSON `1.9.0` 实现所需的
最小 A2A v1.0 HTTPS Client。官方 Java SDK `1.3.1.Final` 不进入生产依赖。

具体边界如下：

1. `extensions:a2a` 的公开面只暴露 Helix 值类型、会话与错误模型；OkHttp、SSE、JSON
   transport DTO 和任何未来 SDK 类型保持 module-internal，不进入 `core:*` 或
   `tools:framework`。
2. 首版只实现 ADR-0016 固定的 Agent Card、JSON-RPC/HTTP+JSON、SendMessage、
   SendStreamingMessage、GetTask、CancelTask 和 SubscribeToTask；不为减少工作量静默
   接受 v0.3、gRPC、custom binding、push webhook、OAuth 或 mTLS。
3. 生产实现必须把 Agent Card/Skill/task/message/artifact 解码为有界、严格的 Helix
   模型；SSE 事件、HTTP body 和 Artifact 各自有独立上限。解析失败不得返回部分成功。
4. 取消、重连和进程恢复由 facade 之上的 Task 状态机控制。重连只携带已保存的同一
   task ID、context ID 和事件游标；SendMessage 送达不明确时不得新建 Task 重发。
5. R8 对 missing platform/protocol class 保持 hard failure；只允许对 OkHttp 明确记录的
   可选 JVM TLS provider 使用定点 `-dontwarn`。
6. 设备验收仍是 M7 完成条件。当前用户要求不运行模拟器或真机测试，因此 API 29/36
   前后台、TLS/auth、取消/重连与真实 App APK 增量必须在发布验收前补齐，不能由本 ADR
   或 standalone R8 结果替代。

## Alternatives considered

1. **官方 Java SDK `1.3.1.Final` + Android HTTP adapter。** 协议覆盖和上游维护最好，
   JVM record/serialization 与两种 Client 构造也通过；但严格 Android R8 仍看到
   `java.net.http` 缺失，且 Client-only 路径带入无关的 gRPC/protobuf/CDI 图。当前不选。
2. **官方 SDK 加 `-dontwarn java.net.http.**`。** R8 可能随后收缩未使用的 JDK client，
   但这会把平台缺失从 hard failure 改为人工假设，而且没有解决依赖面。HXA-077 不用
   warning suppression 充当 Android 兼容证据。
3. **fork 或裁剪官方 SDK。** 可移除 JDK/gRPC 路径，但 Helix 将承担上游补丁、协议同步和
   许可证追踪；相较于只实现首版必需方法的稳定 facade，维护面更大。
4. **暂不实现 A2A。** 风险最低，但最小方案已经通过 JVM transport fixture 与严格 R8，
   足以继续非设备生产实现；设备证据缺口会继续作为显式验收门禁保留。

## Consequences

- `extensions:a2a` 不新增 HTTP/JSON 技术栈，依赖版本与现有 Provider 路径一致；最终 App
  仍需按实际解析结果更新 lockfile、verification metadata 与许可证清单。
- Helix 需要自行维护 A2A v1 DTO、方法编码、SSE 解码、错误映射和协议 fixture；稳定
  facade 降低未来切回官方 SDK 或替换 transport 的跨模块成本。
- 官方 SDK 的 Spike module 与锁文件只保留为可重复决策证据，生产模块不得依赖它。
- standalone DEX 数字不能直接解释为 App 增量；HXA-078/079 接线后必须对 consumer 与
  developer 的真实 release/R8 产物重新测量。
- 当前没有 API 29/36 运行证据，因此不能声称 M7 A2A 已设备验收或可发布。

## Verification

已执行：

```bash
./gradlew :spikes:a2a-sdk:testDebugUnitTest :spikes:a2a-minimal:testDebugUnitTest --no-configuration-cache
./scripts/check-a2a-sdk-android-spike.sh
./scripts/check-a2a-minimal-android-spike.sh
./gradlew :spikes:a2a-sdk:dependencies --configuration debugRuntimeClasspath \
  :spikes:a2a-minimal:dependencies --configuration debugRuntimeClasspath --no-configuration-cache
```

结果：官方 SDK JVM fixture 通过，严格 R8 以固定的
`missing-java.net.http.HttpClient` 原因被预期拒绝；最小 facade 的 3 个 transport/boundary
fixture 通过，严格 R8 生成 `minApi=29 programJars=11 dexBytes=16064 methodIds=169
classDefs=37`。

当前按用户要求未执行，且在 M7/发布验收前仍必需：

- API 29/36 上的 Agent Card、JSON-RPC/HTTP+JSON、SSE、取消、同 Task 重连、大消息、
  TLS/auth error 与前后台生命周期；
- consumer/developer 真实 release/R8 构建的 APK/方法数增量和 SBOM/notice 对账；
- HXA-078/079 的恶意 Card、Task 恢复、不明确送达、Artifact 与远端反向调用拒绝设备链路。

## Reconsider when

- 官方 Java SDK 发布不再引用 Android 缺失的 `java.net.http`、提供真正裁剪的 Client-only
  依赖图，并在 API 29/36/R8 上有可重复证据；
- A2A v1 发生不兼容变更，使最小实现的长期维护成本超过采用 SDK；
- 最小实现的设备、后台、取消/重连或真实 App R8 门禁失败；
- 产品扩大到 gRPC、custom binding、OAuth/mTLS、Server/webhook 或递归编排。

## References

- [ADR-0016：A2A Client 互操作边界](0016-a2a-client-interoperability.md)
- [Provider、MCP、A2A、Skills 与模式](../architecture/provider-mcp-skills-modes.md)
- [M7 路线与 HXA-077](../development/roadmap.md)
- [A2A v1.0 specification](https://a2a-protocol.org/latest/specification/)
- [Official A2A Java SDK v1.3.1.Final](https://github.com/a2aproject/a2a-java/tree/v1.3.1.Final)
- [Official Android HTTP client artifact](https://github.com/a2aproject/a2a-java/blob/v1.3.1.Final/extras/http-client-android/pom.xml)
- [Official SDK license](https://github.com/a2aproject/a2a-java/blob/v1.3.1.Final/LICENSE)
