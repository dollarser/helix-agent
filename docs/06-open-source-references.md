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
