# Runtime、Provider 与端侧模型

> 研究基线：2026-09-25。后续 HXA-222 已交付端侧模型首版，当前引擎与验收范围见[Provider ADR](../../adr/provider/001-models-and-connection.md)和[完成记录](../../completion-records/HXA-222.md)。下文保留当时比较，不作为当前未立项或候选状态的依据。

## 1. Runtime 应按执行边界分层

Helix 当前合理的执行域是：

- app/core：类型化业务与轻量工具；
- QuickJS isolated process：离线生成代码，no privileged host bridge；
- PRoot/CLI private process：开发者/Linux 工具、长命令、PTY；
- subscription/provider private process：订阅客户端或特定 provider transport；
- Android system capabilities：通过显式 bridge + permission。

不要为了“统一运行时”把这些执行域压成一个万能 shell，也不要把 shared UID 当 credential isolation。

## 2. 桌面 Harness vs Android Runtime

桌面 Agent 可以假设稳定 shell/process/worktree；Android 必须处理：

- process/background kill；
- app sandbox/UID；
- foreground service；
- SAF/permission；
- ABI/native dependency；
- battery/memory；
- OEM 差异。

因此 Helix 应复用桌面的 Agent loop/permission/session 思想，但不能照搬“宿主 shell 就是一切”的 runtime 模型。

## 3. Provider

当前 Provider abstraction 应继续保证：

- protocol/stream normalization；
- capability probe；
- explicit provider/model identity；
- connection validation；
- residence/data-destination classification；
- credential owner 与 Runtime 分离。

Session/Turn 必须记录实际使用的 provider/model identity；UI 当前选择不是历史事实。

## 4. 本地模型综合判断

本地模型 Provider 是 Helix 的**一等 `ModelProvider`**，不是只服务摘要/分类的辅助模型。用户可以把本地模型选为当前 Session/Turn 的主 Provider，并让它直接驱动完整 Agent loop，包括工具调用、上下文压缩后的多轮执行和 Goal/Task 工作流。

架构不因为“模型在手机上运行”而禁止 `toolCalls=true`。是否适合完整 Agent loop 由**能力探测 + 模型元数据 + 真实任务 eval + 设备资源**决定，而不是由 `ON_DEVICE_LOCAL` residence 直接决定。

### 一等 Provider 必须满足的契约

- 走与远端模型相同的 `ModelProvider` / `ModelRequest` / `ModelEvent` 抽象；
- 显式标记本地 residence，不伪造 `http://127.0.0.1` endpoint；
- capability probe 能表达 context window、tool calls、parallel tool calls、vision、reasoning、structured/json-schema output 等真实能力；
- 工具调用最终仍进入同一 Dispatcher / Policy / Approval / Audit 管线，本地模型不因“可信设备内运行”获得额外权限；
- capability 不满足当前 Agent mode 时 fail closed 或做**用户可见**降级，不能悄悄把 ToolCall 任务当普通聊天；
- 模型切换、历史快照、request receipt、context compaction 与远端 Provider 采用同一身份语义。

### 完整 Agent loop 是正式目标，不只是实验

摘要、标题、分类、隐私过滤和 `toolCalls=false` 聊天仍然是低风险、低算力的优先验证场景，但它们只用于更快拿到质量/性能基线。它们**不是**本地 Provider 的产品上限。

完整 Agent loop 的验收应直接测：

- 多轮工具选择与调用成功率；
- schema/参数合法率；
- 长上下文下的状态保持；
- tool result 解释与错误恢复；
- Queue/Steer/Goal 下的连续任务；
- token/s、首 token、峰值内存、热降频、电量和后台存活；
- 同一任务与云端 Provider 的完成率/人工干预/成本对比。

### Runtime / engine 候选

llama.cpp + GGUF 仍是高价值候选，原因包括 ARM64 生态、量化、广泛模型兼容和 grammar-constrained decoding。MNN/其他移动推理框架也可作为后续候选；不应把某个推理引擎写死成 Provider 契约。

Grammar/JSON 约束可以提高结构合法性，但不能证明模型具备规划、工具选择和结果判断能力，因此完整 Agent loop 仍必须用真实任务 eval 决定支持级别。

## 5. Loopback vs in-process/private process

consumer cleartext/loopback 暴露、端口授权、恶意本机 App 访问和生命周期使“起 localhost 服务”不适合作为正式本地 Provider 形态。

推荐演进：

```text
S0 developer 外部/PRoot server 验证质量
S1 app 内 ModelProvider + JNI/本地 runtime
S2 若内存/OOM/稳定性证据要求，再移 private :llm process
S3 有真实收益后再做 NPU/SoC 加速
```

本地推理的 isolation 选择应由真实内存/崩溃测量驱动，不预先复制 PRoot/Subscription 的所有 IPC 复杂度。

## 6. 模型分发

真正的新工作面往往不是 JNI，而是：

- GB 级下载；
- resumable transfer；
- SHA-256/签名；
- storage quota；
- model metadata/licence；
- 删除/升级；
- ABI/native library size；
- 用户可见的数据去向。

模型资产应与用户 Workspace 文件分开管理，除非产品明确允许用户导入/导出模型。

## 7. Subscription / proxy / local provider

不同 provider transport 不应进入 Tool Dispatcher；模型调用仍是 Provider 路径。订阅客户端、API provider、本地模型都实现同一 ModelProvider surface，但 credential、network/isolation 和 capability probe 可以不同。

## 8. 冲突裁决

- “本地优先 = 本地模型优先”：否；本地执行/数据与本地推理分开。
- “所有 Runtime 一个 PRoot”：否；生成代码、Linux CLI、模型推理、订阅 transport 风险不同。
- “先 private process 最安全”：不必；先拿真实 OOM/稳定性证据，再付 IPC 复杂度。
- “本地模型只能做摘要/聊天”：不采用。本地 Provider 是一等完整 Agent Provider；实际支持级别由 capability/eval 和设备资源决定。
- “有 grammar 就一定能做 Agent”：不成立；grammar 只解决结构合法性，规划/工具选择/结果判断仍靠模型能力与 eval。

## 9. 重新评审条件

- 本地模型在完整 Helix Agent eval 上出现新的成功率/资源证据，需调整默认模型级别、能力门槛或 Runtime；
- Android 设备普遍提供稳定标准化 NPU LLM API；
- 本地 Provider 使用比例/设备资源数据证明需要改变默认启用策略、模型下载策略或进程隔离；
- consumer cleartext/loopback 或 platform local inference policy 发生根本变化。
