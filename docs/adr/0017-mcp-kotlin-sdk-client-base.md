# ADR-0017: MCP Kotlin SDK Client 与 Android HTTP 底座

Status: accepted
Date: 2026-09-04
HXA: HXA-070
Deciders: Project owner（2026-09-05 明确接受 SDK 0.15.0 + Ktor OkHttp 底座）
Supersedes: none
Superseded by: none

## Context

M7 需要在 Android 主 App 中连接用户配置的远程或局域网 MCP Server。首版只需要
Client 和 Streamable HTTP，不托管 MCP Server；所有动态工具仍必须进入 Helix 的
schema、Policy、Approval、执行、验证和审计管线。具体 SDK 类型不能进入 `core:*` 或
`tools:framework`，否则 SDK/transport 替换会改变核心公开契约。

架构基线把官方 `io.modelcontextprotocol:kotlin-sdk-client:0.15.0` 和 Ktor OkHttp engine
列为首选候选。HXA-070 当前已确认以下仓库与本地实验事实：

- `kotlin-sdk-client:0.15.0` 可由本项目 Kotlin 2.3.21 编译器消费，但把 Kotlin stdlib
  解析到 2.4.0，并带入 coroutines/serialization 1.11.0；这些变化只锁在
  `:extensions:mcp`，不能借此升级其他模块的版本基线。
- SDK 的 `StreamableHttpClientTransport.protocolVersion` 在 0.15.0 源码中有公开字段，
  但握手后没有赋值。module-internal `Transport` 装饰器现在按 initialize request ID
  捕获对应 `InitializeResult.protocolVersion`，保存到 Helix 会话快照，并回填底层
  transport，供后续请求发送 `MCP-Protocol-Version` header。该路径没有硬编码版本，
  fixture 已验证服务端返回的 `2025-03-26` 同时进入快照和后续 ping header。
- `kotlin-sdk-core-jvm` 通过 `ktor-server-websockets` 带入 server core、
  `kotlin-reflect` 和 Typesafe Config，但 SDK 共用 transport 实际引用的是
  `io.ktor.websocket.*`，该 API 已由 client core 的 `ktor-websockets` 提供。排除 server
  artifact 后，Streamable HTTP、取消、重连和 R8 fixture 均仍通过。
- 官方 SDK 仓库处于 MIT 到 Apache-2.0 的许可证过渡期；0.15.0 Maven artifact POM 标记
  MIT，仓库 LICENSE 要求按各贡献的原始 MIT/Apache-2.0 状态保留义务。当前其余 runtime
  artifact 为 Apache-2.0，SLF4J 为 MIT；发布 notice 仍须与最终 APK/SBOM 对账。

离线 JVM 和 R8 证据不能替代 API 29/36 设备上的前后台、TLS、取消、重连和大消息运行。
当前已补齐路线要求的模拟器、后台和 transport 证据；项目所有者已接受本决定，最终签名、
全 App SBOM/notice 和真机发布矩阵按 M12 门禁执行，不能倒灌成 HXA-070 的伪前置条件。

## Decision

基于已通过的 HXA-070 Android 设备门禁，MCP Client 采用以下底座：

1. 只依赖 `io.modelcontextprotocol:kotlin-sdk-client:0.15.0`，不依赖 umbrella/server
   artifact；HTTP transport 固定为 Ktor 3.5.2 OkHttp engine，并解析到项目既有的
   OkHttp 5.5.0。
2. 在 `:extensions:mcp` 的所有配置中排除 `io.ktor:ktor-server-websockets`；由 JVM
   transport fixture 和 minApi 29 R8 gate 固定该排除不会破坏 Client 路径。若后续 SDK
   真正需要 server artifact，先撤销排除并重新评估体积与 Android 兼容性。
3. 对外只暴露 Helix 自有 `McpClientFacade`、`McpClientSession` 和值类型。SDK、Ktor、
   OkHttp、JSON-RPC transport DTO 均保持 module-internal。
4. 首版 Client 不声明 sampling、elicitation 或 roots 能力；HXA-071/072 只在现有架构
   边界内增加配置、握手快照和动态 Tool bridge。
5. 协商协议版本必须来自 initialize result。module-internal transport 装饰器按 request
   ID 关联响应，在 SDK protocol callback 前提取 `InitializeResult.protocolVersion`，
   同时更新 Helix 快照和底层 transport header 状态；连接完成后仍无版本则失败并关闭
   Client/HTTP 资源。不得硬编码最新版本或从 initialize 请求反推服务端选择。
6. R8 只忽略 OkHttp 的可选 JVM TLS provider 和 kotlin-logging 的可选 Logback adapter；
   其他 missing class 保持 hard failure。

## Alternatives considered

1. **直接用 OkHttp + kotlinx.serialization 实现最小 JSON-RPC/Streamable HTTP Client。**
   可减少 SDK 依赖和版本漂移，也能直接保存协商版本；代价是自行维护 session、SSE
   resumption、取消、错误映射和协议兼容。若设备或后台门禁失败，或协议版本缺口无法
   有界修复，这是首选回退。
2. **依赖 SDK umbrella 或 server artifact。** API 覆盖更完整，但会把 Server、更多
   Ktor runtime 和无关能力带进 Android APK，违反 Client-only 和最小依赖面目标。
3. **固定更旧的 Kotlin SDK。** 可能降低 Kotlin metadata 版本和传递依赖，但会失去
   0.15.0 的 Streamable HTTP/session 修复；除非 0.15.0 设备门禁失败且旧版有明确修复
   证据，否则不回退。
4. **立即升级整个工程到 Kotlin 2.4/coroutines 1.11/serialization 1.11。** 这会扩大到
   所有模块并引入与 HXA-070 无关的回归面，不采纳。依赖收敛问题应在真实 app 集成时
   单独评估。

## Consequences

- MCP 实现可在不改变 Core/Tool Framework 契约的情况下替换。
- 排除 server runtime 后，HXA-070 的隔离基线从 32 个 program JAR、2,231,316-byte DEX、
  13,854 method IDs、2,643 classes 降为 28 个 program JAR、1,171,152-byte DEX、
  7,593 method IDs、1,585 classes。HXA-071 接入 `:core:model`、`:core:policy`、SSRF
  endpoint gate 与握手 metadata 后，同一脚本为 30 个 program JAR、1,181,968-byte DEX、
  7,637 method IDs、1,594 classes。HXA-072 再接入 `:tools:framework`、动态 Tool bridge 与
  有界结果映射、wire/event ceiling 和 initialize wire guard 后为 31 个 program JAR、
  1,198,764-byte DEX、7,726 method IDs、1,617
  classes；这些都是 standalone 数据，不是 APK 增量。
- SDK 仍会在 `:extensions:mcp` 内解析 Kotlin 2.4 与 kotlinx 1.11 runtime；已通过
  App 双 flavor release/R8 与独立增量基线检查它们对 APK 的影响，后续 SDK 升级仍必须
  重跑同一口径。
- 协商版本装饰器依赖 SDK 0.15.0 的 `Transport`/JSON-RPC DTO 形态，但保持 module-internal；
  SDK 升级时必须重跑固定 fixture。`InitializeResult.protocolVersion` 在 SDK schema 中有
  默认值，因此装饰器看到的是 SDK 解码后的协商结果，不能区分服务端显式返回与 SDK 对
  缺失字段的默认补齐；设备门禁和后续互操作测试必须覆盖不合规 Server 的降级行为。
- 发布时必须保留 MCP SDK 混合 MIT/Apache-2.0 过渡许可证文本，以及所有实际打包依赖
  的 notice；POM 清单不等于最终 APK/SBOM 对账。

## Verification

已执行：

```bash
./gradlew :extensions:mcp:test --no-configuration-cache
./scripts/check-mcp-android-spike.sh
./gradlew spotlessCheck detekt --no-configuration-cache
./scripts/check-lockfiles.sh
./scripts/check-secrets.sh
./scripts/check-docs.sh
./scripts/verify-adr.sh
```

当前 JVM fixture 覆盖 initialize/ping、连接取消、in-flight 请求取消后会话复用、真正的
SSE 断线 + `Last-Event-ID` 重连、重新建连、1 MiB 响应和公共签名无 SDK/Ktor 泄漏。
initialize/ping fixture 还验证协商版本进入 Helix 快照，并用于后续请求 header。
`check-mcp-android-spike.sh` 使用 AGP 9.3.2 内置 R8 9.3.16、android-36 library 和
`minApi=29`，输出单 DEX；同时固定 SDK/Ktor/OkHttp 坐标并拒绝 runtime classpath 重新带入
Ktor Server、Typesafe Config 或 `kotlin-reflect`。它不启动模拟器。

2026-09-05 新增 `McpAndroidSpikeDeviceTest`，并在 API 29/36 arm64-v8a 模拟器各执行
12/12 通过：真实 Android 网络栈上的 initialize/ping、协商版本 header、in-flight ping
取消后同会话复用、SSE 断线后携带 `Last-Event-ID` 重连、1 MiB 未知 initialize 字段
不越过 Helix facade、HTTP 401 不产生已连接会话、TLS handshake 中断 fail-closed，以及
bearer 只经带精确 loopback scope 和固定 DNS 地址的握手链路发送且不进入返回快照。
非 SSE HTTP 响应另由 OkHttp network interceptor 在 Ktor/SDK 解码前执行 16 MiB wire
ceiling；JVM 与 Android fixture 均覆盖无 `Content-Length` 的 chunked initialize 响应并在
17 MiB 处 fail-closed。真实 `MainActivity` 从 `RESUMED` 进入 `CREATED`（onPause/onStop）
期间 session 仍可 ping，回到 `RESUMED` 后继续可用。SSE 不使用累计连接上限，而是在
OkHttp source 上逐事件执行同一 16 MiB ceiling；JVM 与 Android fixture 均验证 17 MiB
未完成事件在进入 SDK 解析前关闭。另由 OkHttp initialize guard 在 SDK 解码前扫描有界
64 KiB 原始 UTF-8 前缀，并按 JSON 对象深度要求非空 `result.protocolVersion`；嵌套 decoy、
字段缺失或被推迟到前缀之外均 fail-closed，不接受 SDK 默认补齐。API 29/36 均已覆盖；
关闭旧 session 后创建全新 facade 的重新 initialize 也在两端通过。2026-09-05
项目所有者明确接受本底座；签名 artifact 和全 App SBOM/notice 是 M12 发布门禁。

同日执行 `:app:assembleConsumerRelease :app:assembleDeveloperRelease`，两种 unsigned release
均通过 R8 与 lintVital：consumer 为 39,648,694 bytes、190,076 method references，developer
为 39,690,294 bytes、190,205 method references。以 M7 合入前 `f2757ec` 同口径重建，二者
分别为 32,571,386 bytes/147,150 references 与 32,612,982 bytes/147,256 references；因此
当前可确认的是整个 M7 合入带来约 7.08 MB、约 42.9k references 的上界，不得把它冒充
HXA-070/MCP 的独立增量。

为分离直接 MCP 开销，又以 `acc85400ee79` 建立临时基线，并与包含本 ADR
所列未提交 MCP 硬化改动的当前工作树对比：基线仅移除 `:extensions:mcp` App 依赖、
`McpAppService`/`McpStorageBridge` 及其 App/ToolPipeline/ChatService 接线，保留 A2A、Skills、
共享 Room MCP schema 与其他 M7 实现。该基线 consumer 为 35,567,924 bytes/169,008
references，developer 为 35,609,524 bytes/169,177 references，同样通过 release/R8/lintVital。
因此当前直接 MCP runtime 与集成层增量在两个 flavor 均为 4,080,770 bytes
（约 3.89 MiB），method references 分别增加 21,068 与 21,028。由于基线刻意保留了
共享 MCP Room schema，这是可直接删除的 runtime/集成层下界，不是全部 MCP 功能总量；
上述约 7.08 MB/42.9k references 仍只是整个 M7 的上界。

`runtimeClasspath` 锁定闭包已与 Gradle lock/verification metadata 核对：MCP SDK 0.15.0
为 MIT；Kotlin/Kotlinx、Ktor 3.5.2、OkHttp 5.5.0/Okio 3.18.1、kotlin-logging 8.0.4
与 JetBrains annotations 为 Apache-2.0；SLF4J 2.0.18 缓存 JAR 内 `META-INF/LICENSE.txt`
为 MIT。这完成了 HXA-070 依赖许可证据闭包；签名发布 artifact 和最终全 App
SBOM/notice 仍按 M12 与实际打包内容对账。

签名 release、全 App SBOM/notice、真机发布矩阵仍由 M12 统一执行；它们不是接受 client
底座方向所需的 HXA-070 前置证据，也不能因 ADR 接受而视为已完成发布验收。

## Reconsider when

- SDK 在 API 29/36、R8、后台或取消/重连测试中不稳定；
- SDK transport/DTO 改动导致装饰器无法在不 fork SDK 或伪造值的情况下保存协商版本；
- 排除 `ktor-server-websockets` 后出现上游支持的 Client 路径缺失；
- SDK 的 Kotlin/serialization 版本迫使全项目升级或 APK/方法数超过可接受增量；
- 上游完成许可证迁移、停止维护 0.15.x，或发布修复上述缺口的兼容版本。

## References

- [Provider、MCP、A2A、Skills 与 Agent 模式架构](../architecture/provider-mcp-skills-modes.md)
- [M7 路线与 HXA-070](../development/roadmap.md)
- [MCP Kotlin SDK 0.15.0](https://github.com/modelcontextprotocol/kotlin-sdk/tree/0.15.0)
- [MCP Kotlin SDK 0.15.0 LICENSE](https://github.com/modelcontextprotocol/kotlin-sdk/blob/0.15.0/LICENSE)
