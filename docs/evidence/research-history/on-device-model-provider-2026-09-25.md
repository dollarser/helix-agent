# 端侧小模型作为 Provider：可行性与接入方案

**日期**：2026-09-25
**性质**：候选方案研究。**未立项**，不构成实现要求（见 [文档职责](../../README.md)）。
**基线**：HEAD `43e8aef6` + 当时工作树；`compileSdk = 37` / `minSdk = 29` / `targetSdk = 36`；真机 PLC110（OnePlus 6T）/ Android 15。
**问题**：如果希望手机端自己执行一个小模型并当作 Provider 使用，最合理的方案是什么？

---

## 一、结论摘要

| 判断 | 裁定 |
|---|---|
| 端侧模型该不该做 | **该做，但要重新定位**。不要指望它驱动 Agent 循环；最高价值落点是**上下文压缩摘要**（见 §六） |
| 走 loopback HTTP 服务（最常见的想法） | **❌ 在 consumer 构建走不通**。传输层明文被禁，且还有两道附加门（见 §三） |
| 那走什么 | **进程内 `ModelProvider` 实现**（llama.cpp + GGUF via JNI），不经过 HTTP（见 §四） |
| 要改多少契约 | **很少**。`ModelProvider` 接口、能力探测、会话级快照全可复用；只缺一个 `residence` 取值（见 §五） |
| 推理引擎 | **llama.cpp + GGUF**。第一理由是 Helix 特有的：**GBNF 语法约束解码**能把"小模型吐坏工具 JSON"变成结构上不可能（见 §六） |
| 立刻能做的验证 | **零代码**：developer 包 + 手机内跑 llama.cpp server + 已有 Ollama 模板（见 §九） |

**一句话**：Helix 的 Provider 抽象是**为"网络服务"设计的**——`endpoint` 必填、`residence` 从 endpoint 派生、三个协议全是 HTTP。本机模型不是网络服务，所以正确做法是**补一个本机档位**，而不是硬把它塞进 loopback 这条 consumer 已被封死的路。

---

## 二、现状：Helix 侧已具备的比预想多

### 2.1 "本机"已是一等公民

- `ProviderResidence` 是四值封闭枚举，其中 `ON_DEVICE_LOOPBACK` 的 KDoc 明确写着 "data stays on the device"（`core/model/.../ProviderResidence.kt:16`）。
- 这是 **P0 需求**：`FR-LLM-009`「Provider 数据去向分类」要求"按实际 endpoint 标记**本机**、已授权局域网、公有云或未知远端；**不按 Ollama/SGLang 等模板名猜测**」（[产品需求](../../product/requirements.md)）。

也就是说，"本机模型"在产品语义上**不需要新概念**；缺的是它在实现上只能被表达成 loopback。

### 2.2 模板目录已内置两个本机模板

`provider/catalog/.../ProviderTemplateCatalog.kt` 已交付 Ollama（默认 `http://127.0.0.1:11434/v1`，`:82-96`）与 LM Studio（默认 `http://127.0.0.1:1234/v1`，`:258-272`），二者都是 `OPENAI_CHAT_COMPLETIONS` 且 `credentialRequired = false`。`HXA-027` 已完成自建服务的真机 smoke。

### 2.3 接口本身完全可复用

[模型 Provider 与订阅通路](../../architecture/providers.md) 规定 `ModelProvider` 负责"模型请求、流事件、能力和错误归一"。接口是 `stream(request: ModelRequest): Flow<ModelEvent>`——**JNI 回调转 Kotlin Flow 是现成模式**，不需要为本地模型发明新流机制。

`ProviderCapabilities`（`streaming` / `toolCalls` / `parallelToolCalls` / `vision` / `reasoning` / `jsonSchemaOutput` / `maxContextTokens` + `CapabilitySource`）与四阶段连接测试同样可复用，本地模型也能被真实探测而非手工声明。

---

## 三、硬阻断：loopback HTTP 路线在 consumer 不可行

这是最容易踩的坑，因为"起一个本地 HTTP 服务再当 Provider 用"看起来零改动。

### 3.1 传输层：consumer 禁用明文

- `app/src/main/AndroidManifest.xml:42`：`android:usesCleartextTraffic="false"`。
- **只有 `app/src/developer/` 带 `res/xml/network_security_config.xml`**，内容是 `<base-config cleartextTrafficPermitted="true">`。
- 该文件的注释自己写明了边界：

  > The consumer variant carries no NSEC and keeps the main manifest's explicit usesCleartextTraffic=false.

- Android 从 **17（API 37）** 起才为 loopback 提供隐式配置（默认允许明文，覆盖 `localhost`、`ip6-localhost`、以及 `InetAddress.isLoopback()` 为真的数字 IP）。本项目 `compileSdk = 37`，但 **`minSdk = 29` / `targetSdk = 36`**，且真机是 **Android 15（API 35）**——**拿不到该豁免**。

### 3.2 应用层：逐 host:port 授权，且端口不能漂移

`CleartextAuthorization.isPermitted`（`provider/api/.../CleartextAuthorization.kt:40`）对任何 `http` endpoint 都要求授权集合中存在**精确 (host, port)** 匹配——"授权绑定 host + port，不是全局 cleartext"。

[开发环境](../../development/environment.md) 也重申：

> 明文 HTTP 可达性不是授权：Helix 仍要求用户在 UI 中确认精确 `host:port`。

**推论**：若自建服务使用动态端口，用户**每次启动都要重新授权**。固定端口可以规避，但引入端口冲突与抢占问题。

### 3.3 安全面：loopback 端口对本机其他 App 可见

这一点 Helix 自己已经记录过——[竞品平台生态](../../product/competitive-platform-ecosystems.md) 在讨论手机本地 MCP 时列出：

> loopback HTTP 还需要解决**端口认证、恶意 App 访问、进程回收和前台服务**。

同源问题在这里原样复现，且**端侧模型不需要 root/PRoot**，本该是 consumer 能力——把它做成 developer-only 是方向性错误。

### 3.4 附带成本

即使上述都能绕开，loopback 方案仍要付：每 token 的 HTTP 序列化开销、前台服务 + wake lock 保活、以及"模型 OOM 时连带影响服务进程"的额外一层。

---

## 四、推荐路线：进程内 `ModelProvider`

新增一个模块（例如 `provider/local-llama`），实现既有的 `ModelProvider` 接口，内部用 JNI 调 llama.cpp，**不经过任何 HTTP**。

```
Agent 循环 → ChatService → ModelProvider（现有契约）
                              ├─ 云端：3 个 HTTP 协议 → 远端服务
                              └─ 本机：llama.cpp JNI → GGUF（app 私有目录）
```

这样同时消掉 §3 的全部四个问题：无明文、无端口授权、无 loopback 暴露、无 HTTP 开销。

**与"生成代码不在主进程运行"的关系**：AGENTS.md 的该约束针对的是**生成代码**（QuickJS 沙箱），模型推理不是生成代码，因此**不违反**该规则。但内存与崩溃风险是真实的——见 §八 的分阶段路径。

---

## 五、唯一的契约缺口

| 现状 | 问题 |
|---|---|
| `ProviderDescriptor.endpoint: NormalizedEndpoint` **必填非空** | 本机模型没有 endpoint |
| `ProviderDescriptor.residence` 是 **getter，从 endpoint 派生** | 没有 endpoint 就没有 residence |
| `ProviderProtocol` 封闭三值，全是 HTTP 协议 | 没有可表达"本机进程内"的协议 |

**建议**（需 ADR 裁决，主题落在 [Provider ADR 入口](../../adr/provider/README.md)）：

- 新增 `ProviderResidence.ON_DEVICE_LOCAL`（与 `ON_DEVICE_LOOPBACK` 并列，语义为"数据从不离开进程"）；
- 让本机 provider 的 `endpoint` 可空，或引入一个显式的"无 endpoint"表示（**不要**伪造 `http://127.0.0.1` 占位——那正好会踩回 §3）；
- `residence` 派生规则相应扩展：**仍只从可验证事实派生**，不得由模板名或用户标签决定（保持 `FR-LLM-009` 的 fail-closed 语义）。

这是**唯一**需要动的契约。能力探测、`ProviderCapabilities` 持久化、会话级 provider/model 快照、以及 [模型选择、元数据与连接验证](../../adr/provider/001-models-and-connection.md) 的其余决定全部沿用。

---

## 六、引擎选型：为什么是 llama.cpp + GGUF

### 6.1 决定性理由：GBNF 语法约束解码

Helix 是**工具调用驱动**的 Agent，每个工具的入参有严格 JSON Schema（`ToolDescriptor.inputSchema`），且调用必须经过 schema 校验、能力/策略、授权解析、执行限额、验证与审计。

**小模型最常见的失败不是"不够聪明"，而是"吐不出合法 JSON"。** llama.cpp 的 **GBNF 语法约束解码**可以按 schema 强制输出合法结构——这把该失败模式从**致命**变成**结构上不可能**。

MediaPipe LLM Inference API、ONNX Runtime GenAI 等的约束解码能力明显更弱或不可控。**这是选 llama.cpp 的第一理由，不是性能。**

### 6.2 工程参数（第三方实测，置信度中）

参考 2026-07 一篇针对骁龙 8 Gen 2 / 7B 的实测：

| 项 | 结论 |
|---|---|
| 量化 | **Q4_K_M 是甜点**：4.1 GB / 16 tok·s⁻¹；Q4_0 更快（18）但困惑度明显下降；Q5_K_M 4.8 GB 仅 12 |
| KV cache | **只量化 V**（`cache_type_v = q8_0`，K 保持 F16）：4096 ctx 内存 **约 2 GB → 约 1.1 GB**，近乎零成本，应默认开 |
| 线程 | **绑大核**。默认铺满全部核心反而更慢（14 → 16 tok·s⁻¹） |
| ABI | **只出 `arm64-v8a`**。`armeabi-v7a` 下 NEON 路径回退，性能断崖 |
| GPU / NPU | **不要先做**。GPU（OpenCL/Vulkan）只在 **< 1B** 有优势；7B 级受显存带宽限制，CPU 更稳定。NPU 需按 SoC 用 QNN/Genie 逐个适配 |
| 模型分发 | 模型 0.4–2.5 GB，**不能进 APK**，必须下载（见 §七） |

### 6.3 其他候选与不选的理由

| 方案 | 不选的主要理由 |
|---|---|
| MediaPipe LLM Inference API | 接入最简单，但约束解码能力弱、模型格式受限于 `.task` bundle、对旧 GPU 依赖重；难以为工具调用做 schema 级约束 |
| ONNX Runtime GenAI | 移动端优化成熟度低于服务端；LLM 场景需自行适配 KV Cache / RoPE 等结构 |
| MLC-LLM | 依赖 GPU/Vulkan 路径；本项目目标是"CPU 可移植基线优先" |
| 厂商 NPU SDK（QNN / NeuroPilot） | 需按 SoC 逐个适配，与"单 APK 可移植"冲突；只适合作为后续加速档 |
| AICore / Gemini Nano | 仅在少数特定机型可用（Pixel / 部分三星），**不是通用能力**，不能作为产品基线 |

---

## 七、两块必须提前算的账

### 7.1 APK 体积（release 阻断级）

现状：consumer release APK 已约 45 MiB、其中 DEX 约占 90%；**无 ABI 切分**（4 个 ABI 全打）、**R8 关闭**。

加入 native 推理库会把 4 个 ABI 的 `.so` 全打进包。**必须先做 ABI 切分**，或把 native 库改为按需下载 / dynamic feature，否则体积失控。

### 7.2 模型分发（全新工作面）

Helix 目前**没有大资产下载能力**（浏览器侧只有 `browser.download`）。端侧模型需要新增：

- 用户触发的下载（不能静默拉 GB 级文件）；
- 断点续传；
- 完整性校验（SHA-256）；
- 存储配额与用户可见的占用；
- 与 [应用私有 Workspace / SAF / All files access](../../architecture/overview.md) 存储模型的边界（模型应落在 app 私有目录，不占用用户可见 Workspace）。

这是独立工作面，不应低估。

---

## 八、产品定位：先接辅助调用，不要驱动 Agent 循环

**1–2B 参数模型撑不起 Helix 的 Agent 循环**：多步工具调用、严格 JSON Schema、十几个工具（含 12 个 `browser.*`）、并行批次、UNKNOWN 停泊与恢复对账。把这些压给一个小模型，失败率会高到不可用。

真正高价值的落点，按优先级：

1. **上下文压缩摘要（最佳第一落点）**。[ADR-AGENT-002](../../adr/agent/002-context-compaction.md) 明确规定「摘要使用**无工具调用**，生成结构化连续工作笔记」——这正好是一个**不需要工具调用**的模型调用。而它当前会把整段对话发给云端 Provider。换成本地模型是**实打实的隐私收益**，且对质量容忍度高、失败可回退。
2. 标题生成、分类、发送前敏感信息脱敏等辅助调用。
3. 离线 / 私密聊天（`toolCalls = false` 的纯对话场景）。
4. 主 Agent 驱动仍留给云端模型；若作为 Advanced 可选项提供，必须**明确标注预期成功率**，不能默认。

---

## 九、分阶段路径

| 阶段 | 内容 | 成本 |
|---|---|---|
| **S0 零代码验证** | developer 包 + 手机内（Termux/PRoot）跑 llama.cpp server 或 Ollama + **已有的 Ollama 模板**指向 `127.0.0.1:11434`，授权一次 | 极低，**建议先做** |
| **S1 进程内 Provider** | 新增 `provider/local-llama` 模块 + `ModelProvider` 实现 + llama.cpp JNI；补 `ProviderResidence.ON_DEVICE_LOCAL`；只接摘要 / 聊天 | 中 |
| **S2 隔离进程** | 推理搬到独立非导出 `:llm` 进程，走私有 Binder/PFD IPC | 高，**等 S1 出现 OOM/崩溃证据再上** |
| **S3 加速** | 面向旗舰 SoC 接 NPU（QNN / NeuroPilot） | 高，可选 |

**S0 的价值**：它能在**写任何 native 代码之前**回答真正的未知数——"小模型到底能不能驱动 Helix 的工具循环"。它同时也满足"模型跑在手机上"这个字面诉求，只是没有打进 APK。

**S2 为什么不是第一步**：`:proot` / `:subscriptions` 已经是"非导出私有进程 + 有界快照 + 私有 Binder/PFD IPC"的既有模式（见 [执行域 ADR](../../adr/runtime/001-execution-domains.md)），照着做风险不高，但它会显著扩大首版工作量。**先用进程内版本拿到真实的内存与崩溃数据，再决定是否付这笔钱。**

---

## 十、设备现实

当前真机 PLC110（OnePlus 6T）是 **骁龙 845**：4×Cortex-A75 + 4×Cortex-A55，支持 ARMv8.2-A dot-product 指令，**不支持 i8mm**（ARMv8.6 才有）；LPDDR4X 带宽约 30 GB/s。

按 §6.2 的 8 Gen 2 数据打折，**1.5B 级 Q4_K_M 大致落在个位数 tok·s⁻¹**。这个量级：短摘要与聊天可用；多步 Agent 循环会非常慢。

**该数字是推算，不是实测**（见 §十二）。做 S0 时应实测目标机型的首 token 延迟与生成速度，而不是依赖推算。

---

## 十一、流程与落位

- 查过 [实施状态](../../development/status.md)、[开发路线](../../development/roadmap.md)、[实施指南](../../development/implementation-guide.md)、[开发环境](../../development/environment.md) 与 `docs/product/`：**目前没有任何端侧模型 / 本地推理相关条目**。
- 因此这是**新的 roadmap 项（新 HXA）**，且因触及 Provider 契约，需先走 `docs/adr/provider/` 的 ADR，再按 roadmap 提升。**不能塞进现有 HXA。**
- 新增 Gradle 模块需改 3 处（`settings.gradle.kts` + 根 `build.gradle.kts` 的 `androidLibraries`/`jvmLibraries` + `projectDependencies`）——本仓 28/32 个模块没有自己的 build 脚本。
- 验收按 [公共验收规则](../../development/verification-matrix.md)；设备侧证据按现有边界（CI 不执行设备测试）。

---

## 十二、置信度与未核实项

| 结论 | 依据 | 置信度 |
|---|---|---|
| consumer 构建禁用明文，且无 NSEC | `app/src/main/AndroidManifest.xml:42`；`app/src/developer/res/xml/network_security_config.xml` 及其注释 | **高**（本仓源码） |
| `ProviderResidence` 四值与 `ON_DEVICE_LOOPBACK` 语义 | `core/model/.../ProviderResidence.kt`；`FR-LLM-009` | **高** |
| Ollama / LM Studio 模板已内置且指向 loopback | `provider/catalog/.../ProviderTemplateCatalog.kt:82-96`、`:258-272` | **高** |
| `ProviderDescriptor.endpoint` 必填、`residence` 由 endpoint 派生 | `provider/api/.../ModelProvider.kt:19-45` | **高** |
| 明文 HTTP 需逐 host:port 授权 | `provider/api/.../CleartextAuthorization.kt:40`；[开发环境](../../development/environment.md) | **高** |
| 摘要调用不使用工具调用 | [ADR-AGENT-002](../../adr/agent/002-context-compaction.md) | **高** |
| Android 17 起 loopback 有明文隐式配置 | Android 官方「网络安全配置」文档的 Localhost configuration 一节 | **中高**——文档未明确"显式 `cleartextTrafficPermitted="false"` 是否覆盖该隐式配置"，且本项**不影响结论**（`minSdk=29` 与真机 API 35 都不在豁免范围） |
| llama.cpp 量化 / KV cache / 线程 / ABI 参数 | 第三方 2026-07 实测文章（骁龙 8 Gen 2 / 7B） | **中**——单一来源，未在目标机型复现 |
| 骁龙 845 的 tok·s⁻¹ 推算 | 由上述数据外推 | **低**——**必须实测** |
| 小模型无法可靠驱动 Helix 的 Agent 循环 | 基于模型规模与工具 schema 复杂度的判断 | **中**——**应由 S0 实测证伪或证实** |

**未核实项**：

1. Helix 的 UI 是否允许用户对 `127.0.0.1:11434` 完成 `CleartextAuthorization` 授权（HXA-027/028 的风险展示与授权面在 developer 包的实际可达性未逐项核对）。
2. 目标机型上 llama.cpp 的实际 tok·s⁻¹、首 token 延迟与峰值内存。
3. 1–3B 级、经工具调用微调的模型在 Helix 真实工具 schema 下的成功率（是否足以支撑 `toolCalls = true`）。
4. 新增 native 库后 consumer APK 的精确体积增量，以及 ABI 切分的实际收益。

---

## 十三、参考来源

**本仓**：

- [模型 Provider 与订阅通路](../../architecture/providers.md)
- [产品需求](../../product/requirements.md)（`FR-LLM-009`、`FR-LLM-006`）
- [Provider ADR 入口](../../adr/provider/README.md)、[模型与连接](../../adr/provider/001-models-and-connection.md)
- [上下文与步骤边界压缩](../../adr/agent/002-context-compaction.md)
- [执行域](../../adr/runtime/001-execution-domains.md)
- [竞品平台生态](../../product/competitive-platform-ecosystems.md)
- [开发环境](../../development/environment.md)、[公共验收规则](../../development/verification-matrix.md)
- 当前执行引擎决策与任务：[ADR-AGENT-001](../../adr/agent/001-turn-coordination.md)、[HXA-220](../../development/tasks/HXA-220.md)

**外部**：

- Android 官方「网络安全配置」（cleartext 与 localhost 隐式配置）
- llama.cpp（GGUF 格式、GBNF 语法约束解码）
- 第三方 2026-07 Android GGUF / llama.cpp 实测文章

---

*本文只做可行性论证与方案建议，未修改任何产品代码；结论不直接成为实现要求。落位需先经 Provider 主题 ADR 与 roadmap 提升。*
