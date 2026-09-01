# Helix 开源依赖与参考仓库清单

核实日期：2026-08-31。第三方仓库的活跃度、版本和许可证可能变化；纳入构建前必须重新核实 tag、commit、LICENSE、NOTICE、依赖树和发布渠道政策。

## 1. 分类规则

- **直接依赖**：进入 Helix Gradle/NDK 构建，必须固定版本和依赖锁。
- **运行时组件**：进入 APK 或 RootFS，必须固定二进制、源码、哈希和许可证。
- **设计参考**：只阅读架构和测试思路，不复制代码。
- **研究参考**：帮助理解方向，不作为产品正确性或安全性的证据。

“仓库是 MIT”不代表仓库下载、安装或启动的所有第三方 CLI、模型和 RootFS 都是 MIT。

Helix 项目源码使用根 `LICENSE` 声明的 Apache License 2.0。该选择不改变第三方组件和运行时资产的许可条件；PRoot、QuickJS、Alpine、官方 CLI 等仍须按 `THIRD_PARTY_NOTICES.md` 保留各自的 LICENSE、NOTICE、源码提供和再分发义务。

## 2. 建议直接依赖

### 2.1 Cash App Zipline

- 仓库：[cashapp/zipline](https://github.com/cashapp/zipline)
- 基线 release：`1.27.0`（2026-04-02）
- 许可证：Apache-2.0；内部 QuickJS 保留 MIT notice。
- 用途：Android/JNI QuickJS、`evaluate`、`memoryLimit`、`InterruptHandler`。
- 使用边界：只使用 QuickJS host API，不使用远程 bundle 更新作为 Helix 功能。
- 重要警告：上游 README 明确表示 Zipline 不提供 sandbox/process isolation；Helix 必须放进 Android isolated process。

### 2.2 AndroidX / Kotlin / OkHttp / kotlinx.serialization

- AndroidX：[androidx releases](https://developer.android.com/jetpack/androidx/versions)
- OkHttp：[square/okhttp](https://github.com/square/okhttp)
- kotlinx.serialization：[Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)
- 用途：UI、生命周期、Room、WorkManager、HTTPS/SSE、JSON。
- 约束：只从 Google Maven/Maven Central 获取；全部进入 dependency lock。

### 2.3 AndroidX WebKit

- 官方组件：[AndroidX WebKit](https://developer.android.com/jetpack/androidx/releases/webkit)
- 基线：`1.17.0`。
- 用途：在 Android System WebView 之上使用现代、可 feature-detect 的 WebView API。
- 选择理由：官方、薄封装、由系统 WebView 提供实际引擎；比 fork Chromium 或依赖小众 browser engine 更可维护。
- 边界：WebKit 不是完整浏览器 UI，也不会自动解决不可信 JavaScript bridge、下载和站点权限安全。

### 2.4 MCP Kotlin SDK

- 仓库：[modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)
- 基线 release：`0.15.0`（2026-07-28）。
- 许可证：正在从 MIT 迁移到 Apache-2.0，实际 artifact/source 必须保存对应 LICENSE/NOTICE。
- 用途：MCP Client、Streamable HTTP/stdio 协议类型和生命周期。
- 使用 artifact：`kotlin-sdk-client`，不引入 server umbrella；Android HTTP engine 使用 Ktor OkHttp。
- 约束：先做 Android/R8 Spike，SDK 类型不能泄漏到 Agent Core；正式升级需核对其支持的 MCP spec version。

### 2.5 libsu

- 仓库：[topjohnwu/libsu](https://github.com/topjohnwu/libsu)
- 基线 release：`6.0.0`。
- 许可证：Apache-2.0。
- 用途：Root shell 状态和 Binder RootService。
- 选择理由：Android Root 领域知名、API 边界清晰、包含 core/service/nio 设计。
- 供应链：通过 JitPack 获取时只允许该 group 的 exclusive content，固定 tag 并记录 artifact checksum；M9 前不加入构建。

## 3. 运行时组件

### 3.1 QuickJS

- 仓库：[bellard/quickjs](https://github.com/bellard/quickjs)
- 许可证：MIT。
- 状态：截至核实日仍活跃。
- 用途：由 Zipline 封装进入 APK。
- 要求：App 内第三方 notice 包含 QuickJS MIT 文本；升级 Zipline 时确认内含 QuickJS revision 和安全变更。

### 3.2 PRoot

- 仓库：[termux/proot](https://github.com/termux/proot)
- 许可证：GPL-2.0。
- 用途：developer 变体的 Linux 用户态路径/系统调用模拟。
- 要求：固定 tag/package、二进制 SHA-256、对应 source archive、patch 和完整 GPL 文本。
- 不能声称：PRoot 是 VM、内核容器或强安全隔离。

### 3.3 Alpine Linux minirootfs

- 官网：[Alpine Linux Downloads](https://alpinelinux.org/downloads/)
- 用途：developer 变体 RootFS。
- 许可证：按 package 分别记录，不存在一个覆盖全部内容的“Alpine MIT”。
- 要求：固定版本和架构、官方 URL、size、SHA-256/签名；生成 package+license 清单。

## 4. 重点设计参考

### 4.1 PalmClaw

- 仓库：[ModalityDance/PalmClaw](https://github.com/ModalityDance/PalmClaw)
- 核实 release：`v0.3.1`（2026-08-26）
- 许可证：AGPL-3.0，并提供商业许可证文件。
- 可参考：单机 Gateway Runtime、Agent Loop、Tool Registry、Workspace、Room、Android Service 生命周期、架构文档和大量测试分类。
- 不应直接复制：AGPL 源码进入非 AGPL Helix 会产生许可证义务。Helix 只采用独立设计和公开思想。
- 调研观察：其架构清楚地区分 Runtime、Agent、Provider、Tool、Storage、Channel，并强调“构造 adapter 不等于在线”和变更后验证，这些原则适合 Helix。

### 4.2 AndCode

- 仓库：[yuga-hashimoto/and-code](https://github.com/yuga-hashimoto/and-code)
- 核实 release：`v1.2.13`（2026-08-27）
- 自有 Android 代码许可证：MIT。
- 可参考：Kotlin/Compose UI、PRoot/Alpine 工作区、构建期从 Termux package 获取固定组件、SHA-256 lock、RootFS `.partial` 安装/回滚、第三方 notice、凭据数据流文档。
- 直接复用前必须审查：仓库同时打包 PRoot、Termux library、Alpine/Debian、第三方 CLI；这些各自具有 GPL/LGPL/其他许可证和服务条款。
- 推荐用法：把它作为 PRoot 工程证据和测试清单来源，不把整个项目 fork 成 Helix。

### 4.3 ClawMobile

- 仓库：[ClawMobile/ClawMobile](https://github.com/ClawMobile/ClawMobile)
- 核实 release：`v0.5.1`（2026-07-24）
- 许可证：MIT。
- 可参考：本机 runtime 与 Termux 高级 runtime 分层、generated skill 局限、Android force-stop/ADB/跨设备 UI 自动化的真实边界。
- 不纳入当前产品：Trusted Agent、远程连接、ADB 和跨手机协作。Accessibility 仅参考其已知边界，Helix 按自己的节点 token/Policy 方案实现。

### 4.4 PalmClaw 论文与 ClawMobile 论文

- [PalmClaw: A Native On-Device Agent Framework for Mobile Phones](https://arxiv.org/abs/2607.13027)
- [ClawMobile: Rethinking Smartphone-Native Agentic Systems](https://arxiv.org/abs/2602.22942)
- 用途：了解手机原生 Agent 的 runtime、工具、skills 和资源约束。
- 限制：研究或公开预览的结果不能替代 Helix 自己的安全测试、应用市场审核和真机验收。

## 5. 次级参考

### 5.1 Termux

- [termux/termux-app](https://github.com/termux/termux-app)：GPL-3.0。
- [termux/proot-distro](https://github.com/termux/proot-distro)：GPL-3.0，核实 release `v5.8.0`（2026-08-22）。
- 可参考：Android binary packaging、RootFS alias、登录命令、发行版安装与故障处理。
- 限制：不要直接复制 GPL 代码进不兼容许可证模块；不要依赖 Termux App 已安装。

### 5.2 Google AI Edge Gallery

- 仓库：[google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)
- 核实 release：`1.0.18`（2026-08-10）
- 许可证：Apache-2.0。
- 可参考：Android 本地模型下载、模型清单、设备能力、推理 UI 和性能展示。
- 当前用途：仅为未来手机本地模型准备；M0-M12 不要求接入。

### 5.3 llama.cpp

- 仓库：[ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp)
- 许可证：MIT。
- 可参考：GGUF、本地 CPU/GPU 推理和 Android native 构建。
- 当前不依赖：本地模型不是首版 Agent 正确性的前提，且模型内存、上下文和温控需要单独技术评估。

### 5.4 WebAssembly runtimes

- [wasm3/wasm3](https://github.com/wasm3/wasm3)：MIT。
- [WasmEdge/WasmEdge](https://github.com/WasmEdge/WasmEdge)：Apache-2.0。
- 可能用途：未来替代/补充 JavaScript 执行器。
- 当前不引入：生成 WASM 的开发体验和 Android 集成复杂度高于 QuickJS；除非 HXA-050 Spike 失败。

### 5.5 Sora Editor

- 仓库：[Rosemoe/sora-editor](https://github.com/Rosemoe/sora-editor)
- 许可证：LGPL-2.1。
- 可参考：Android 代码编辑和高亮。
- 当前不依赖：MVP 只需要代码审批预览，Compose `SelectionContainer` + 等宽文本足够。若引入必须审查 LGPL 和 native artifact。

### 5.6 Pi coding agent

- 仓库：[earendil-works/pi](https://github.com/earendil-works/pi)（原 `badlogic/pi-mono` 已迁移/重定向）。
- 核实状态：MIT，仓库活跃；coding agent README 的默认工具为 `read`、`write`、`edit`、`bash`。
- 可参考：小而稳定的基础工具名、provider adapter、subscription/API key 分类、Skill/extension/plan mode 边界和 Termux 平台说明。
- Helix 采用：四个短工具名及“能力可扩展、核心保持小”的思路。
- 不直接复用：TypeScript/TUI/Node agent runtime、第三方 OAuth 实现和桌面文件系统假设。Android 的四个工具必须加入 scope、Policy、Approval 和独立执行域。

### 5.7 Agent Skills 规范

- 仓库：[agentskills/agentskills](https://github.com/agentskills/agentskills)
- 状态：开放规范，Apache-2.0 code / CC-BY-4.0 docs；截至核实日活跃。
- 可参考：`SKILL.md` frontmatter、目录结构和 discovery → activation → execution 渐进披露。
- 重要边界：仓库的 `skills-ref` README 明确说明是 demonstration，不作为 production Android dependency。Helix 自行实现 Kotlin validator/loader，并用官方规范 fixture 测试。

### 5.8 浏览器参考

- [plateaukao/einkbro](https://github.com/plateaukao/einkbro)：GPL-3.0，活跃的轻量 WebView 浏览器；参考 tab/WebView 生命周期、下载和错误处理。
- [Slion/Fulguris](https://github.com/Slion/Fulguris)：文件混合 CPAL-1.0/MPL-2.0；参考浏览器 UI 和权限开关。
- [uazo/cromite](https://github.com/uazo/cromite)：GPL-3.0，大型 Chromium fork；只研究隐私/站点策略，不作为 Helix 底座。
- X 浏览器：闭源产品，只能作为轻量交互体验参考，不能作为源码依据或依赖。

结论：Helix 直接使用 System WebView + AndroidX WebKit，自行实现最小壳。以上代码均不得在未完成许可证决策时复制。

### 5.9 文件管理器参考

- [zhanghai/MaterialFiles](https://github.com/zhanghai/MaterialFiles)：GPL-3.0；参考路径、NIO、符号链接、冲突、Root 和长任务 UI。
- [TeamAmaze/AmazeFileManager](https://github.com/TeamAmaze/AmazeFileManager)：GPL-3.0；参考多选、归档、存储来源和错误呈现。
- 只采用公开设计思想和测试清单，不复制 GPL 实现。Helix 优先用 Android NIO/SAF 和自己的 scope adapter。

### 5.10 Accessibility 自动化参考

- [SuperMonster003/AutoJs6](https://github.com/SuperMonster003/AutoJs6)：MPL-2.0，原 Auto.js 的活跃衍生项目；参考节点查找、动作、等待和用户可停止体验。
- 原 Auto.js 已不适合作为维护底座；历史 fork 的维护和来源差异较大。
- Helix 不嵌入其 JavaScript 自动化 runtime，不复制源码；只实现强类型 `ui.*` 工具、目标包 allowlist 和敏感界面拒绝。

### 5.11 主流 Coding Agent / Agent Harness 设计参考

移动端市场定位、产品能力对照和后续跟踪指标统一在[移动端 Agent 竞品分析](12-competitive-landscape.md)维护；本节只负责设计参考、依赖候选与许可证/禁止复制边界。

下列项目可用于回答“成熟 Agent 如何组织 provider、session、tool loop、上下文、审批、扩展、验证与 UI”等设计问题，但**不是 Helix 的依赖候选清单**。每次调研只选择与当前 HXA 最相关的 1～2 个项目，引用官方仓库中的具体协议、状态机、测试或安全说明；不得凭产品印象声称某个边界已经安全，也不得把桌面 unrestricted shell、全仓库文件权限、自动 Git commit 或插件权限原样搬到 Android。

| 项目 | 当前可核实事实 | 适合参考 | Helix 不照搬的部分 |
| --- | --- | --- | --- |
| [Claude Code](https://github.com/anthropics/claude-code) | Anthropic 终端/IDE Agent；公开仓库许可证为 Anthropic 商业条款，并非开源源码许可证，因此不把“TypeScript/Bun 实现”当作可审计事实 | 用户确认、计划/执行交互、hooks/skills/MCP 的产品契约、长任务 UX | 源码复制、内部订阅认证、桌面 shell/文件权限假设 |
| [Gemini CLI](https://github.com/google-gemini/gemini-cli) | Google；TypeScript + Ink，Apache-2.0 | core/CLI 分包、工具声明、MCP/扩展、provider 路由、测试和 headless 工作流 | Node runtime、Google 登录令牌和主机权限模型 |
| [Codex CLI](https://github.com/openai/codex) | OpenAI；维护中的 CLI 核心为 Rust，npm 只是分发入口，Apache-2.0 | Rust core 与界面/协议分层、结构化事件、会话恢复、审批/sandbox、app-server 边界 | 复用 ChatGPT 凭据、桌面 sandbox 或把 CLI 内置工具绕过 Helix Policy |
| [Cline](https://github.com/cline/cline) | Cline 社区/公司；以 TypeScript 为主，Apache-2.0，现同时提供 SDK、CLI、VS Code/JetBrains 入口 | diff 审阅、逐步批准、provider 抽象、任务时间线、IDE 与核心解耦 | 编辑器信任区、任意命令执行和 hosted service 条款 |
| [Continue](https://github.com/continuedev/continue) | TypeScript，Apache-2.0；官方 README 当前将原仓库标为不再积极维护/最终 2.0.0，因此只作有日期的历史参考 | IDE adapter、上下文 provider、配置版本化、代码检查与 CI gate | 作为活跃底座或依据旧 API 设计稳定公开契约 |
| [Aider](https://github.com/Aider-AI/aider) | Python，Apache-2.0，终端结对编程 Agent | repo map、受控 edit/diff 格式、lint/test 反馈回路、Git 可回退体验、模型基准 | 自动提交默认值、全仓库读写和桌面进程执行 |
| [OpenCode](https://github.com/anomalyco/opencode) | TypeScript/Bun，MIT，终端/桌面 coding agent | client/server、session/event、provider adapter、工具和 TUI 分层 | Bun runtime、任意插件/命令权限和桌面文件系统假设 |
| [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) | DeepSeek；TypeScript monorepo，MIT，`everything-is-a-plugin`；官方明确为快速变化、未经安全审计的 developer preview | plugin 生命周期、scope、invariant、agent loop/driver 分层、可替换 preset 和测试组织 | 动态装卸插件即获得权限、生成代码执行和“preview 等于 production 安全” |
| [goose](https://github.com/aaif-goose/goose) | 起源于 Block，现由 Linux Foundation Agentic AI Foundation 托管；Rust core/CLI + Electron/TypeScript desktop，Apache-2.0 | interface/agent/extension 分层、MCP/ACP、Rust core 与多前端共享协议 | 桌面 extension 权限、ACP Agent 自行执行工具或把 MCP 当授权 |
| [Amazon Q Developer CLI](https://github.com/aws/amazon-q-developer-cli) | AWS；Rust，MIT OR Apache-2.0；开源仓库已停止常规维护，只接收关键安全修复，后续产品为闭源 Kiro CLI | 企业配置、遥测边界、身份/凭据隔离、Rust TUI 的历史实现 | 作为活跃底座、复制 AWS 登录/服务客户端或假定 Kiro 源码可审计 |
| [Hermes Agent](https://github.com/NousResearch/hermes-agent) | Nous Research；Python，MIT；是可嵌入库/CLI/多入口通用 Agent，不只限 coding | provider routing、持久 memory 与 skills 的职责区分、gateway allowlist、可嵌入 agent API | Telegram/Discord 等消息渠道、长期记忆自动出网和 unrestricted host tools |

这些项目给 Helix 的共同启发应转换为本仓库自己的约束与测试，而不是转换为同语言实现：

| 参考思想 | Helix 的落点 |
| --- | --- |
| core 与 UI/CLI 分离、结构化 event stream | `core:model`/`core:agent` 纯状态与 effect；UI 只订阅状态，不直接调用 Provider/DAO/Runtime |
| provider adapter 与能力探测 | M2 HXA-020～027；三个协议独立 fixture，不做猜测式统一 adapter |
| 小而稳定的工具集合、扩展协议 | M3 Tool Registry/Policy/Approval；M7 MCP/Skills；扩展描述永远不能授权 |
| repo map、渐进上下文和可复现编辑 | HXA-016 Context Builder + M4 Workspace；确定性裁剪、ArtifactRef、scope 与原子写后验证 |
| diff/审批/测试反馈闭环 | HXA-034～036 approval proof/timeline；Dispatcher 变更后 verifier；verification matrix 的真实命令 |
| session checkpoint、恢复和去重 | Turn/Goal/Room + ADR-0004；不明确副作用停泊；PRoot/CLI 使用 ADR-0007 jobId journal |
| 可插拔架构与 invariants | 只在编译期/注册表内采用强类型 adapter；运行时插件、MCP annotation、Skill 指令不能改变 Policy |

采用上述项目的代码、协议包或登录方式仍会触发第 8 节依赖审查；改变 Helix 安全/IPC/持久化/扩展边界时仍必须按 ADR 约定决策。“参考过某 Agent”不是跳过当前 HXA、测试或授权的理由。

### 5.12 面向 LLM 的设计方法参考

- [warlockee/llm-oriented-design-patterns](https://github.com/warlockee/llm-oriented-design-patterns)：以一个 Python LLM 训练框架的重构案例提出 Context Management、Feedback Loop、Tooling 三组原则，以及小文件、calling spec、纯函数工具、平面分发、严格 Schema 和结构化反馈等模式。它适合作为“减少编码模型无关上下文、明确概率/确定性边界”的检查清单，不是 Android/Kotlin 标准，也没有提供跨项目独立验证。
- Helix 采纳其中的小职责、显式契约、严格校验、薄编排和可操作反馈；不采纳固定 800 LOC 硬门、动态 import/弱类型字典分发、无界自动调参重试、反 OOP/SOLID 结论或“所有 Tool 必须纯且无副作用”。完整映射见[总体方案 §17](02-architecture-design.md#17-面向-llm-的工程设计)。
- 截至 2026-09-01，该仓库首页虽链接 `LICENSE`，仓库文件列表和链接目标未提供可读取的许可证文件；因此只引用思想与链接，不复制其正文、示例或代码。若未来要采用具体文本或实现，必须先核实许可证。

## 6. 不建议作为底座的仓库

### 6.1 AnyClaw / OpenClaude Android

- [OpenClawAndroid/openclaw-android-assistant](https://github.com/OpenClawAndroid/openclaw-android-assistant)
- [friuns2/openclaude-android](https://github.com/friuns2/openclaude-android)

原因：

- README 中涉及“泄漏/重写的 Claude Code”等来源表述，存在来源、商标和授权风险。
- 将多个大体量 CLI、RootFS、凭据和远程模型揉在一起，供应链面大。
- 项目自身 MIT 文件不自动解决内部第三方组件许可证。

可以观察 UI/打包做法，但不得复制来源不清代码或直接分发其 APK/二进制。

### 6.2 PalmClaw/Jenny 的 AGPL 源码

- [flagdizero/jenny-android-ai-agent](https://github.com/flagdizero/jenny-android-ai-agent)：AGPL-3.0。

可以研究功能边界，若 Helix 不采用 AGPL，则不能直接复制实现。小模型尤其容易根据相似代码“改名复制”，任务 Prompt 必须明确禁止。

### 6.3 不适合作为 Helix 自动化底座的研究项目

- [aohp-os/MobileClaw](https://github.com/aohp-os/MobileClaw)
- [X-PLUG/MobileAgent](https://github.com/X-PLUG/MobileAgent)

这些项目主要研究 ADB/视觉 GUI 自动化，常依赖外部电脑、截图模型或备用设备。Helix 的 Accessibility 能力采用 Android 节点树和本机审批，不把这些项目作为实现底座。

## 7. 模型 Provider 与自建服务参考

Helix 自己实现三个最小 adapter，不直接依赖大型 Agent SDK：OpenAI Responses、OpenAI Chat Completions、Anthropic Messages。每个 adapter 都准备独立脱敏 fixture：

1. 文本流。
2. 单个和多个 tool calls，arguments 分片。
3. 错误：401、429、5xx、连接中断。

自建服务：

- [ollama/ollama](https://github.com/ollama/ollama)：MIT；官方文档提供部分 OpenAI Chat Completions/Responses 兼容，Responses stateful 字段并非全部支持。
- [sgl-project/sglang](https://github.com/sgl-project/sglang)：Apache-2.0；活跃、高知名度，提供 OpenAI-compatible endpoint；ToolCall 依赖服务端为模型选择正确 parser。
- [vllm-project/vllm](https://github.com/vllm-project/vllm)：Apache-2.0；可作为另一个 OpenAI-compatible 自建模板。

模型名称（包括用户举例的 Qwen 版本）不是客户端编译常量。用户配置 `modelId`，Provider 能力探测返回实际可用性。不要在文档里假设某个未来或别名模型一定存在。

消费者订阅后端只参考厂商官方客户端：

- [openai/codex](https://github.com/openai/codex)：Apache-2.0；官方 app-server 有 ChatGPT browser/device-code login，凭据由 Codex 管理。
- [Anthropic Claude Code setup](https://docs.anthropic.com/en/docs/claude-code/getting-started)：官方文档说明 Pro/Max 登录；Helix 只使用公开 CLI/SDK 接口。

不要复制 Pi 或其他第三方的 OAuth token 实现来冒充官方 Provider；不提取浏览器 Cookie 或其他 App token。

## 8. 依赖引入检查表

引入任何第三方代码前回答：

- 是否真的不能用标准库或现有依赖实现？
- 最新稳定 tag 和目标 commit 是什么？
- LICENSE、NOTICE、依赖的 native library 分别是什么？
- 是否下载/执行额外代码、模型、CLI 或 RootFS？
- 是否需要新的 Maven repository？
- 是否收集数据或连接第三方服务？
- Android minSdk、16 KiB page、ABI、R8 是否兼容？
- 是否增加危险权限、后台服务或动态代码？
- 是否有已知 CVE、安全公告和维护者响应？
- consumer/developer 两个变体是否都需要？
- 能否固定版本、hash 并离线构建？

任何一项不清楚时，先写 Spike/ADR，不把依赖直接加入 production。

## 9. 可信度说明

本清单同时核对了项目 README、构建文件、LICENSE、GitHub release/仓库元数据和 Android 官方资料。它能支持架构决策，但不替代正式法律意见、安全审计或发布当日的应用市场政策确认。
