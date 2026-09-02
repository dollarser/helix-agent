# Helix 移动端 Agent 竞品与定位

文档状态：产品与架构调研

核验日期：2026-09-02

适用范围：Helix 当前实现仍限 Android 单机；竞品观察覆盖 HarmonyOS/iOS，但不据此新增 HarmonyOS 客户端、远程 Worker、云端沙箱或桌面配对承诺

本文侧重技术能力、执行位置和平台边界；目标用户、场景、分发与付费假设见[市场、用户与商业化分析](market-users-and-commercialization.md)。

## 1. 结论摘要

“手机版 AI 客户端”和“手机 Agent”已经不能只按“会不会调用工具”二分。到 2026 年，市场形成了六条路线：

1. **跨平台 AI 客户端**：Chatbox、Cherry Studio Mobile、NextChat。优势是多 Provider、BYOK 和聊天体验；工具通常依赖应用内能力、MCP 或服务端，未必能操作 Android 本机。
2. **Agent 工作台/网关**：LobeHub、Msty Go。它们已经具备 Agent、工具或设备路由，但执行可能位于服务端、桌面端或另一个已注册运行时，不能仅凭手机 UI 判定为“手机本机执行”。
3. **终端 Coding Agent 移植**：Termux/PRoot 中运行 Codex CLI、Claude Code、Gemini CLI、Aider、goose 等。它们能读写工作目录和执行命令，但 Android/Termux 通常不在厂商正式支持矩阵内，安装、升级、后台存活和权限边界由用户自行承担。
4. **原生移动 Agent Runtime**：Operit、AndCode、ClawMobile。它们已经在 Android 上组合 Agent、文件、浏览器、终端/PRoot、工作区或 Accessibility，直接抬高了“安装后就能干活”的能力基线。
5. **传统 Android 自动化**：Tasker、Auto.js/AutoX.js、Hamibot、MacroDroid。它们没有完整 LLM Agent Loop 或 BYOK 工作台，但已经占据设备自动化、模板、定时触发和脚本分享的用户心智。
6. **系统原生 Agent 生态**：Gemini/Claude Android、HarmonyOS 的小艺/HMAF/Intents Kit，以及 Android AppFunctions、Apple App Intents。它们把 Agent-to-App 能力做成系统语义层；优势是入口、分发和系统能力，限制是平台专属、准入受控。

因此，Helix 的有效定位不是“第一个手机 Agent”，也不能只宣传“比聊天客户端更安全”。更准确的定位是：

> **面向开发者与效率用户、Provider 中立、Android 原生的本机 AI 执行工作台：把文件、网页、代码和 Android 高级能力组合成真实任务；Advanced 面向知情用户，能力优先，用户对主动开启和确认的操作承担最终责任。**

Helix 仍负责真实展示能力状态、执行目标、scope、数据去向和最终结果，不允许模型替用户开启能力或批准操作。这是高级能力可用、可调试的最低可信底座，不再作为第一市场卖点。

截至本报告核验日，Helix 已形成 Provider/聊天、Tool Loop 和首批 Workspace 文件能力，但尚未形成完整用户闭环。该句只是竞品判断所用的日期快照；当前已交付能力、检查点和限制一律以[实施状态](../development/status.md)为准，不能把路线图能力写成已交付能力。

## 2. 评估口径

### 2.1 不使用“真 Agent/假 Agent”作为唯一标签

本报告按六个可验证维度判断：

| 维度 | 要回答的问题 |
| --- | --- |
| Agent Loop | 模型是否能提出多步工具调用，并根据结果继续推理？ |
| 执行位置 | 工具实际在手机 App、手机 companion、桌面/服务器还是云端运行？ |
| Android 能力 | 是否能访问受授权文件、浏览器、系统应用、Accessibility、shell 或 Root？ |
| 授权模型 | 是 Android 粗粒度权限、工具弹窗、每应用授权，还是参数级、一次性批准？ |
| 可恢复与审计 | 崩溃/取消后是否有持久状态、去重、结果对账和用户可见审计？ |
| 开放性 | 是否支持 BYOK、自建 endpoint、多 Provider、MCP/Skill 或可替换执行后端？ |

“有 MCP”只证明可以连接工具协议，不证明工具在手机执行，也不证明 MCP 描述能够安全授权。“有 Android 权限”同样不等于每个具体 ToolCall 已获批准。

### 2.2 证据规则

- 优先采用产品官网、官方文档、官方仓库与官方 release。
- “未见公开证据”不等于功能不存在，只表示本轮不能据此作产品承诺。
- 产品自述的安全能力记为“公开声明”；没有可复现实验时，不提升为已验证安全结论。
- 移动端 UI、Agent Runtime 和工具执行位置分别判断，不因同一品牌桌面端有能力就推断移动端也有。

## 3. 对原始材料的校正

| 原判断 | 2026-09-01 校正 |
| --- | --- |
| Chatbox 严格说只是 API 客户端 | 仍以多模型客户端为核心，但官网已经公开文件、联网、知识库和 MCP 能力。更准确的说法是“增强型 AI 客户端”，而不是纯聊天壳；仍未见其提供通用 Android 文件/shell/Accessibility 执行域的公开证据。 |
| Cherry Studio 主要桌面、移动端弱或只能 PWA | 已有独立的官方 React Native 移动仓库和 iOS/Android 构建，官方 release 到 `v0.1.7`。它仍是较早期移动产品，不能把桌面端完整 MCP/Agent 能力自动算到移动端。 |
| LobeHub 只有 iOS App + PWA | 官方下载页已列出 iOS App Store 与 Android Google Play；产品也已经从聊天框架演进为 Agent 工作台，并支持异构本地/远程运行时路由。 |
| Termux + CLI 是手机上唯一能真正干活的 Agent | 不成立。AndCode、ClawMobile、Gemini Android、Claude Android 已提供不同程度的本机任务执行。Termux 仍是强大的高级用户路径，但多数桌面 CLI 没有把 Android/Termux 列入正式支持平台。 |
| Claude Code、Gemini CLI、Codex CLI、Aider、goose 都可“装上即用” | 需要逐项区分。Codex CLI 官方页面列 macOS/Linux/Windows；Claude Code列 macOS/Windows及 Ubuntu/Debian/Alpine；Gemini CLI列 macOS/Linux/Windows；goose 官方仓库明确 Termux 尚未正式支持。Aider 的 Python 安装路径相对可移植，但官方安装页也没有承诺 Android。 |

## 4. 市场分层与代表产品

### 4.1 A 类：增强型 AI 客户端

| 产品 | 已核实的移动形态 | BYOK/Provider | Agent/工具边界 | 对 Helix 的意义 |
| --- | --- | --- | --- | --- |
| [Chatbox](https://chatboxai.app/en/install) | iOS、Android、Web、桌面，含 Android 直装 APK | 支持自带 API Key；可导入 OpenAI-compatible Provider 配置 | 文件、联网、知识库；付费页公开 MCP。未见通用 Android 本机 shell/Accessibility Runtime 证据 | 最强的低门槛 Provider onboarding 与跨设备聊天参照 |
| [Cherry Studio Mobile](https://github.com/CherryHQ/cherry-studio-app) | 官方 React Native iOS/Android App；[release](https://github.com/CherryHQ/cherry-studio-app/releases) 仍处移动早期版本 | 多 Provider，功能持续补齐 | README 明确助手、对话、文件/迁移等；仓库含 MCP Streamable HTTP 包，但不能据此推断与桌面端完全等价 | 观察桌面强产品如何缩减到移动端，以及移动/桌面数据迁移 |
| [NextChat](https://github.com/ChatGPTNextWeb/NextChat) | 响应式 Web/PWA，另列 iOS 与多桌面平台 | OpenAI-compatible 与自部署模型 | 轻量聊天、Prompt/Mask、流式响应；不是 Android 系统能力执行器 | 轻量、快速首屏、自托管和本地浏览器存储的体验基线 |

这类产品的核心竞争点是“几分钟内开始聊天”。Helix 即使工具安全更强，如果 Provider 配置、连接测试、模型选择、费用/出网提示明显更复杂，也会在首次使用阶段流失用户。

### 4.2 B 类：Agent 工作台与运行时路由

| 产品 | 已核实能力 | 执行位置判断 | 主要差异 |
| --- | --- | --- | --- |
| [LobeHub](https://lobehub.com/downloads) | iOS/Android/桌面；Agent、MCP、Skill、异构 Agent 与设备网关。2026-08 release 明确本地 stdio/LAN MCP 是桌面能力 | 手机可作为 Agent 前端；具体工具可能由云、桌面或已注册设备执行，必须按 target 分辨 | 生态、Agent 市场、多 Agent 与跨设备路由强；不是“所有能力都在当前手机本机”的同义词 |
| [Msty](https://msty.ai/) | Studio、Go、Nexus、Stack；Go 宣传有边界、可逐步审阅、可从 desktop/mobile 控制的任务 Agent | 官网表述强调跨端控制与治理，未据此确认所有工具在手机本机执行 | 企业治理、模型网关和知识层是强项；Helix 当前不做组织级控制平面 |

这类产品提醒 Helix：UI 必须持续展示 execution target、数据去向与能力来源。只显示“工具正在运行”会掩盖本机、桌面网关和云端执行的本质差异。

### 4.3 C 类：Termux/PRoot + 桌面 CLI Agent

| CLI | 官方支持面 | Android/Termux 结论 |
| --- | --- | --- |
| [Codex CLI](https://learn.chatgpt.com/zh-Hans/docs/codex/cli) | macOS、Linux、Windows；能检查/编辑代码、运行本机命令，并有权限配置 | 可由社区尝试移植，但官方页面不把 Android/Termux 列为受支持平台，不应写成保证可用 |
| [Claude Code](https://code.claude.com/docs/en/installation) | macOS、Windows、Ubuntu、Debian、Alpine；x64/ARM64 | PRoot Linux 可能满足部分条件，原生 Termux 仍不是官方支持组合 |
| [Gemini CLI](https://google-gemini.github.io/gemini-cli/) | Node.js 20+；macOS、Linux、Windows | 社区已有运行经验，但 Android/Termux 不在官方支持列表 |
| [Aider](https://aider.chat/docs/install.html) | Python 3.8～3.13，多种 pip/uv/pipx 路径 | 技术上较可移植；官方安装页未承诺 Android，依赖构建与 Git/文件权限仍需用户维护 |
| [goose](https://github.com/aaif-goose/goose/blob/main/BUILDING_LINUX.md) | 桌面 Linux/macOS/Windows 路径为主 | 官方仓库明确写明 Termux 尚未正式支持，需要补丁或非官方构建 |

Termux 路线的优势是能力上限高、现有 CLI 生态可直接利用；弱点是：

- 安装/升级/二进制 ABI/Node 或 Python 依赖由用户承担；
- Android 12+ 可能清理 phantom 或高 CPU 进程，官方 Termux 仓库也明确提示不稳定风险；
- CLI 的桌面权限与 approval 语义不会自动变成 Android 参数级 scope；
- `termux-setup-storage`、All-files、Termux:API、PRoot bind mount 等能力容易汇聚到同一长寿命环境；
- OAuth/API Key 通常由各 CLI 自持，跨 CLI 搬运 token 不应成为集成方案。

Helix 的 E2C CLI Runtime 因而应定位为后期、独立 UID 的兼容实验，而不是产品主执行内核。官方 CLI 自持登录，主 App 只接收有界会话事件；任何 CLI 内置工具不能绕过 Helix Policy 成为主 App 的隐形万能工具。

Termux 与 PRoot 也不能作为同类产品直接二选一：Termux 是原生 Android 命令行环境、终端和包生态，性能与社区规模占优；PRoot 是可嵌入独立 Runtime 的 Linux RootFS 兼容层，文件密集任务较慢且不提供安全隔离。对 Helix，外部 Termux 更适合研发 Spike/专家自带环境，正式 E2 路线仍采用固定资产、无网、独立 UID 的 PRoot companion；常用能力优先 E0 原生 Tool，避免为所有任务支付 PRoot 开销。完整功能、社区、用户量、性能、许可证和集成路径比较见[本地代码执行方案 §6.2](../architecture/local-code-execution.md#62-termux-与-proot-对比及集成结论)。

### 4.4 D 类：原生移动 Agent Runtime（直接竞品）

#### AndCode

[AndCode](https://github.com/yuga-hashimoto/and-code) 是当前最接近 Helix 的直接竞品：原生 Android GUI、手机内 PRoot/Alpine、Git/文件树/diff/终端、OpenCode 稳定支持、Claude Code/Antigravity beta、工具批准、定时任务和 Keystore 凭据均已有公开实现说明。

它的竞争优势：

- 用户不需要先学习 Termux，安装后可直接初始化 Runtime；
- Coding Agent、Git 和 diff review 是完整垂直闭环；
- 能打开真实设备文件，并提供 All-files 路径；
- PRoot、RootFS、CLI 资产与第三方 notice 已形成实际工程经验。

公开安全说明也明确：PRoot 不是完整安全沙箱；full-access/bypass-permissions 模式可让 CLI 不再逐项询问。与 Helix 相比，最值得守住的差异不是“也能跑 PRoot”，而是 Helix 不提供全局 bypass，把每个外部 Agent/CLI 请求重新封装为 schema + scope + Policy + exact Approval + Verification + Audit 是否可行。该差异必须以后续 E2C Spike 的真实协议证据证明，不能只停留在架构宣言。

#### ClawMobile

[ClawMobile](https://github.com/ClawMobile/ClawMobile) 是另一直接参照：Android app-local runtime + 可选 Termux/OpenClaw gateway，能使用本机文件、Android 状态、截图、OCR、Accessibility/ADB 控制、可复用 Skill 和 trusted-agent messaging。官方将其标为 public preview，并提醒 API Key、截图、trace、log 和生成 Skill 可能包含敏感信息。

它的竞争优势：

- 手机控制与可复用 UI Skill 已进入可演示状态；
- 能从 App-local runtime 渐进开启 Termux、Accessibility/ADB 等更强能力；
- “手机是 Runtime，而不是远程屏幕”的叙事清晰。

Helix 不应照搬其 ADB、跨设备 trusted agent、Telegram 或生成 Skill 自动扩权。Helix 的对照重点应是：目标 App allowlist、窗口/节点 token、敏感界面拒绝、实时 Capability 检查、逐调用 Approval、取消恢复与审计，是否能在不牺牲可用性的前提下完成同类任务。

### 4.5 E 类：系统级原生助手（强相邻竞品）

| 产品 | 当前公开能力 | 与 Helix 的关系 |
| --- | --- | --- |
| [Gemini on Android](https://support.google.com/gemini/answer/15235441?hl=en) | 设备设置、闹钟/计时器、媒体、通知读取/回复、打开 App/页面等；[屏幕自动化](https://support.google.com/gemini/answer/16940971?hl=en)按 App 提供 Always allow / Ask every time / Do not allow | 系统集成、分发和低摩擦体验远强于独立 App；但不是 Provider 中立、BYOK、本地代码/Workspace Agent |
| [Claude on Android](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps) | 可通过 Android 系统/第三方 App 起草或发送消息、邮件、日历、地图等任务，具体能力依权限而异 | 证明“官方聊天 App + Android actions”已成为基准；公开范围仍不同于通用本机 shell/文件 Runtime |

这类产品会抬高用户对语音入口、系统分享、默认助手、低延迟和权限引导的预期。Helix 的差异是开放 Provider、可检查的本机工作区与更细的安全/审计契约，而不是覆盖面或预装优势。

### 4.6 F 类：HarmonyOS 原生 Agent 生态（重要系统级参照）

鸿蒙生态不应只用“小艺是不是聊天助手”来评价。它已经形成从系统入口到开发者能力注册、智能体编排、应用/元服务执行和跨设备分发的完整参照系：

| 层级 | 官方能力 | Agent 价值 | 与 MCP 的关系 |
| --- | --- | --- | --- |
| 系统 Agent 入口 | 小艺 / Harmony Intelligence | 理解用户意图，路由系统能力、应用、元服务和垂域智能体 | 不是通用 MCP Host，而是鸿蒙系统与小艺生态入口 |
| Agent 协同框架 | HMAF / HMAF 2.0 | 定义 OS、鸿蒙应用/元服务与智能体的协同范式；小艺开放平台提供 LLM、Workflow、A2A 编排 | A2A/鸿蒙智能体通信属于生态协议，不能与 MCP 画等号 |
| Agent-to-App 语义层 | Intents Kit | 把应用/元服务的功能、内容和词条注册为 HarmonyOS 级意图，供搜索、推荐和小艺任务执行 | 最接近鸿蒙侧的系统函数注册表，不要求每个 App 自建 MCP Server |
| App 内 Agent 入口 | Agent Framework Kit | 应用可通过 UI 控件主动拉起智能体组合 | 面向鸿蒙应用内体验，不是跨平台工具协议 |
| 服务载体与分发 | 元服务 | 轻量应用形态，可经小艺、搜索、建议等入口触达，并覆盖多设备 | 是业务载体，不是 Agent 协议 |
| 基础能力 | IPC Kit、AI Kits、Service Collaboration Kit | 进程通信、端侧推理、语音/视觉、跨设备能力调用 | 是执行基础设施；拥有 IPC/AI Kit 不等于拥有 Agent 授权模型 |

HMAF 2.0 在 HarmonyOS 7 开发者 Beta 中提出“意图即服务”，并开放更多系统级 AI 与 GUI 操控能力。华为公布的“复杂任务成功率 90% 以上”等数字来自厂商实验室，应视为产品发布口径，不作为独立横评结果，也不能证明第三方智能体具备参数级审批、可恢复执行或完整审计。

#### 鸿蒙生态代表性“精品”

| 产品/形态 | 已公开体验 | 为什么值得纳入竞品分析 |
| --- | --- | --- |
| **小艺系统 Agent** | 从问答入口扩展到意图理解、系统感知、任务规划与应用/元服务执行；HMAF 2.0 增加系统 AI 和 GUI 操控开放能力 | 代表“OS 自带 Agent + 系统能力注册表”的最高分发形态，是 Helix 在系统入口、低摩擦调用和能力发现上的上限参照 |
| **客服小艺** | 覆盖手机、平板和鸿蒙电脑，可做官方知识问答、设备故障检测、服务查询、转人工以及部分系统一键操作 | 不是泛聊天，而是知识、诊断工具、服务流程和系统动作闭环的垂域 Agent 样板 |
| **小艺深度解题** | 拍题、批改、互动讲解、学习诊断、错题练习、悬浮窗和跨应用图片入口 | 体现视觉输入、长期学习资产、应用间入口与专用工作流如何组合成完整垂域产品 |
| **小艺运动健康 / 翻译助手** | 在手表等终端提供健康数据分析、运动建议和翻译；同一广场还接入 DeepSeek、讯飞学习搭子等三方智能体 | 证明智能体市场与穿戴设备入口已落地，但不同设备、系统版本和语言/地区存在能力差异 |
| **小雅 AI / AiPPT.cn / 讯飞晓医 / 深航飞飞** | 官方精选案例分别覆盖音频搜推与播控、一句话生成 PPT、健康问答，以及航班查询、订票和值机选座 | 说明三方智能体已经覆盖内容、生产力、健康和出行，不只是模型聊天；健康建议等高风险结果仍需单独评估准确性与责任边界 |
| **京东 Agent / 同程程心 Skill** | 官方提供云 A2A 账号授权与会话交互案例，以及端云协同/场景化 Skill 开发范例 | 体现现有业务 Agent 接入系统入口的开发路径；竞争单位从单 App 扩展为“智能体市场 + 开发平台 + 系统分发” |
| **元服务** | 轻量服务可通过小艺建议、搜索和场景入口直接触达，并支持多设备部署/流转 | 给 Helix 的启发不是复制鸿蒙形态，而是让结构化能力可发现、可组合、按场景呈现 |

“鸿蒙开发者知识 MCP”出现在官方 AI 开发资源中，可作为开发知识与工具入口；本轮没有发现它是面向任意手机 App 的系统级 MCP Registry。鸿蒙当前更关键的系统协议是 Intents Kit/HMAF/A2A，而不是把所有应用接口统一包装成 MCP。

#### 为什么鸿蒙生态看起来更完整

这种完整感主要来自“产品与生态的一体化”，不等于每一层都比 Android/iOS 更开放或更成熟：

1. **同一厂商贯通全链路**：华为同时控制 HarmonyOS、系统 Agent 入口、小艺开放平台、Intents Kit/HMAF、应用与元服务分发以及终端产品，开发者看到的是一条连续路径，而不是多个厂商协议的拼装。
2. **智能体市场被显式产品化**：小艺广场集中呈现官方和三方垂域 Agent，并提供开发、调试、审核与上架链路。Android 和 iOS 的类似能力更多散落在 Connected Apps、OEM 助手、App Store、Shortcuts 和具体 App 内。
3. **元服务适合作为 Agent 履约单元**：轻量服务可以从语音、搜索、建议卡片等系统入口直达，弱化“先安装并打开完整 App”的交互成本。
4. **统一的命名和发布节奏**：HMAF 2.0、“意图即服务”、端/云 A2A、GUI 操控和精选案例在同一发布周期集中出现，市场感知比 Android 的 Google/OEM 分层以及 Apple 的框架式表达更强。
5. **中国本地服务闭环集中**：健康、教育、出行、内容和客户服务可以围绕小艺入口形成可展示的垂域闭环，因而比单纯展示一个开发 API 更容易被用户感知。

需要保留三个校准：HMAF 2.0 与部分 GUI 能力仍处开发者 Beta/渐进开放阶段；第三方 Agent 的可用设备、地区、语言和版本并不一致；平台审核和系统权限也不能证明参数级审批、恢复语义与审计已经达到 Helix 的目标标准。

#### HarmonyOS、Android 与 iOS 系统 Agent 生态对照

| 层级 | HarmonyOS | Android | iOS |
| --- | --- | --- | --- |
| 系统 Agent 入口 | 小艺 / Harmony Intelligence | Gemini；Samsung 设备另有 Bixby/Galaxy AI | Siri / Apple Intelligence |
| App 能力语义层 | Intents Kit | AppFunctions | App Intents |
| 系统编排 | HMAF，支持 LLM、Workflow、端/云 A2A | Gemini Connected Apps、Device Assistance、AppFunctions；OEM 另有私有编排 | Apple Intelligence system orchestrator、app toolbox、Shortcuts |
| 第三方分发 | 小艺智能体市场、Skill、元服务 | Play 应用、Connected Apps、OEM 助手生态、Bixby Capsule，入口分散 | App Store、Shortcuts、App Intents；没有独立的系统 Agent 市场 |
| GUI 自动化 | HMAF 2.0 公布面向开发者的 GUI 操控能力 | Gemini Screen Automation Beta，限定设备、地区、语言和 App | 更偏向结构化 App Intents；通用跨 App GUI 自动化受沙箱限制 |
| App 内 Agent 开发 | HMAF、Agent Framework Kit、HarmonyOS AI Kits | AppFunctions、传统 Android API 与各厂商 AI SDK | Foundation Models 的结构化生成/Tool calling + App Intents |
| MCP 定位 | 可接开发知识或云端工具，但不是 OS 核心 App 协议 | 可作外部工具协议，但 AppFunctions 才是系统函数层 | 可作远程连接协议，但 App Intents 才是系统动作层 |

三者并不是简单的“鸿蒙有、另外两个没有”：

- **Android：能力覆盖广但碎片化。** Gemini 已通过 Device Assistance 和 Connected Apps 操作设备、消息、日历、媒体及部分第三方服务；部分设备还提供多步 Screen Automation。AppFunctions 则让 App 声明类型化函数，供受信任、系统特权的 Agent 发现和执行，但当前仍是 beta/experimental preview。Samsung 又在 Android 之上提供 Bixby、Galaxy AI、SmartThings 和多 Agent 入口，因此最接近鸿蒙完整产品形态的是“Galaxy AI + Bixby + Gemini + Samsung Apps”，而不是抽象的裸 Android。
- **iOS：底层体系完整但表达更克制。** App Intents 把 App 动作和实体提供给 Siri、Shortcuts、Spotlight、控件和 Apple Intelligence。Apple 在 2026 年进一步将其描述为 semantic index、app toolbox 与 system orchestrator；Foundation Models 还允许 App 使用端侧/私有云或服务器模型进行结构化生成和 Tool calling。Apple 的产品叙事更接近“让每个 App 融入系统智能”，而不是建立一个显眼的第三方 Agent 广场。
- **HarmonyOS：统一度最高。** 系统、助手、协议、市场和元服务都由同一生态组织，产品闭环最容易理解；代价是平台专属、准入受控，跨平台可移植性和第三方 Agent 的系统级权限仍需逐项核验。

如果按不同指标判断：生态呈现与开发者接入闭环目前以 HarmonyOS 最统一；结构化系统集成和端侧开发框架方面 iOS 已具备同级参照；Android 的开放性、厂商选择和自动化覆盖最广，但一致性最弱。三者都不是“任意 Provider + 任意系统能力 + 逐调用精确审批”的开放通用 Runtime。

#### 对 Helix 的直接启示

1. **优先结构化能力，再退化到 GUI 自动化**：Intents Kit 的“意图连接业务功能”与 Android AppFunctions 同方向。Helix 应优先 Intent/System API/AppFunctions adapter，Accessibility 只处理没有正式接口的旧 App。
2. **把发现、编排和执行拆开**：小艺入口、HMAF 编排、Intents Kit 注册与应用/元服务执行是不同层。Helix 也不应因发现了 Tool/MCP 就默认它可执行或已获授权。
3. **做垂域闭环，而不只做通用聊天**：客服小艺和深度解题的竞争力来自“输入—工具—业务结果—后续服务”的闭环。Helix 应先用受控文件工作区形成同样可演示、可验收的闭环。
4. **保留逐调用安全差异**：系统权限、平台审核、HMAF/A2A 声明或 GUI 能力都不能替代 Helix 的 exact ToolCall Approval、Verification 与 Audit。
5. **研究但不扩张实现范围**：鸿蒙多端、端云 A2A 和元服务是产品参照，不因此新增 HarmonyOS、云端 Agent、跨设备 Worker 或多 Agent HXA。

鸿蒙专项跟踪项：持续核验 HMAF 2.0 白皮书与通信协议的公开细节；比较 Intents Kit、Android AppFunctions 和 Apple App Intents 的 schema、发现、权限、取消及恢复语义；每季度抽查小艺智能体广场中可复现的垂域闭环。以上均为调研 TODO，不是已批准实现任务。

## 5. 移动端 Agent 调 App 协议与系统级 MCP

### 5.1 MCP 不是手机上唯一的工具协议

手机平台已经有多层 App 调用机制。MCP 适合跨平台服务和独立 Runtime；本机 App 能力通常通过系统协议暴露：

| 层级 | Android | HarmonyOS | iOS | 适合场景 |
| --- | --- | --- | --- | --- |
| 系统语义化函数 | AppFunctions（Android 16+，实验预览） | Intents Kit + HMAF | App Intents | Agent 发现并调用 App 声明的类型化动作 |
| 传统跨 App 调用 | Intent、App Link、Deep Link、Sharesheet、App Actions | Ability/Want、应用链接、分享等系统能力 | URL Scheme、Universal Link、Share Extension、Shortcuts | 打开页面、分享、预填内容、调用常见系统动作 |
| 系统数据接口 | ContentProvider、SAF、Health Connect、CalendarProvider | 各 HarmonyOS Kit 与应用授权能力 | HealthKit、EventKit、PhotoKit 等 | 在各平台权限模型内读写结构化数据 |
| App/Runtime IPC | Binder/AIDL、Bound Service、PFD | IPC Kit、Ability Kit | XPC、App Extension | 同厂商 App、主 App 与 companion Runtime |
| 跨平台 Agent 工具 | MCP Streamable HTTP；PRoot/CLI 内 stdio | 云/端 A2A、MCP 服务或自建 Agent 后端 | 远程 MCP；本地 stdio 受沙箱限制 | SaaS、自建服务、数据库和独立 Agent Runtime |
| 无正式接口的兜底 | Accessibility、截图/OCR | HMAF 2.0 GUI 操控能力及平台自动化 | 系统自动化能力更受限 | 旧 App 或没有结构化接口的流程 |

Android Intent/App Link 适合一次性跳转或让用户在目标 App 中完成操作；ContentProvider/Health Connect 等接口适合受权限保护的数据访问；Binder/AIDL 适合已建立签名/权限关系的 companion。它们都不是 MCP，但往往比在手机里常驻一个本地 HTTP/stdio MCP Server 更符合平台生命周期。

### 5.2 Android AppFunctions：最接近“系统级移动 MCP”

[Android AppFunctions](https://developer.android.com/ai/appfunctions?hl=en) 从 Android 16/API 36 起提供 OS Registry、类型化参数/结果、发现、状态查询和执行接口。Google 将其描述为 MCP tools 的移动端对应机制：App 像 on-device MCP Server 一样贡献函数，获授权的 Agent/Assistant 通过 `AppFunctionManager` 调用，而不需要 App 自建网络服务。

当前限制必须同时记录：

- API 与 Jetpack 库仍是 experimental preview；截至 2026-05，Gemini 端到端接入仍是 trusted tester/private preview。
- 调用方需要 `EXECUTE_APP_FUNCTIONS`。该权限虽标记为 `normal`，但系统仍执行运行时 allowlist 检查；Helix 不能假设普通安装后即可枚举和调用所有 App。[权限说明](https://developer.android.com/reference/android/Manifest.permission#EXECUTE_APP_FUNCTIONS)
- AppFunctions 只提供系统调用通道，不替代目标 App 的业务校验，也不替代 Helix 对参数、scope、风险、Approval、Verification 和 Audit 的判断。
- Helix 可以先研究“向系统 Agent 暴露 Helix 函数”；“Helix 作为调用方控制其他 App”则必须等公开访问条件和真机证据。

iOS 对应方向是 [App Intents](https://developer.apple.com/documentation/appintents)：App 以 schema 声明动作和实体，供 Apple Intelligence、Siri、Spotlight、Shortcuts 等系统体验使用。它不是 MCP，且 iOS 客户端不在 Helix 当前范围，只作为跨平台产品判断依据。

### 5.3 当前手机 MCP 与系统 Agent 能力

| 产品/系统 | 手机端现状 | 是否等于手机本机系统 MCP |
| --- | --- | --- |
| Android AppFunctions | OS Registry + 类型化 App 函数；Android 16+ 实验预览 | **最接近，但尚未全面开放** |
| HarmonyOS Intents Kit/HMAF | OS 级意图标准、应用/元服务能力注册、LLM/Workflow/A2A 编排及小艺执行入口 | 否；是鸿蒙原生 Agent-to-App/Agent 协同栈，不是通用 MCP Registry |
| 小艺开放平台 A2A | 支持端 A2A、云 A2A 和三方 Agent 接入，多端调试与上架 | 否；属于小艺/HMAF 生态协议，运行位置与授权方式需按模式核验 |
| 鸿蒙开发者知识 MCP | 官方 AI 开发资源入口之一 | 否；当前证据指向开发知识工具，不是操作手机所有 App 的系统 MCP |
| Gemini Android | Device Assistance、Connected Apps 和按 App 屏幕自动化 | 否；主要是 Google/Android 内部系统接口，不是面向任意 Agent 的 MCP Server |
| [Claude Mobile Connectors](https://support.claude.com/en/articles/11176164-use-connectors-to-extend-claude-s-capabilities) | iOS/Android 可使用 Web Connectors，也可连接支持 MCP 的服务 | 是 MCP Client 能力，但通常调用远程 HTTPS 服务，不是手机系统 MCP Registry |
| Claude Android actions | 消息/邮件/日历/闹钟/地图/Health Connect；官方说明使用分享、Intent 和系统权限 | 否；这是 Android 原生接口，与 Claude 的远程 MCP Connectors 分开 |
| Cherry Studio Mobile | `v0.1.6` 起明确支持 Streamable HTTP MCP | 是移动 MCP Client；未因此获得任意 Android App 系统能力 |
| LobeHub Mobile | 移动端可使用平台 Agent/MCP 服务；本地 stdio/LAN MCP 的完整能力主要在桌面端 | 执行位置可变，必须区分云、桌面网关与当前手机 |
| Chatbox Mobile | 产品页宣传集成 MCP，但移动端 transport、自定义 Server 和执行位置公开说明不足 | 暂不计为已证实的系统级 MCP Host |
| [ChatGPT MCP Apps](https://help.openai.com/en/articles/12584461-developer-mode-apps-and-full-mcp-connectors-in-chatgpt-beta) | 官方 FAQ 当前明确自定义/full MCP Apps 为 Web only | 当前不是移动端 MCP Host |
| Termux/PRoot + CLI | 可在 Linux 用户态内运行 stdio MCP Server/Client | 只属于该 Runtime，不会自动注册成 Android 系统能力 |
| AndCode/ClawMobile | 使用各自 Agent/runtime/tool bridge，可再承载 CLI/MCP | 属于应用私有协议与 Runtime，不是 OS 级 MCP Registry |

手机上本地 MCP 较少的原因是 stdio 要求 Host 管理子进程，而普通 App 受 UID、沙箱和后台生命周期限制；loopback HTTP 还需要解决端口认证、恶意 App 访问、进程回收和前台服务。现实路径因此以远程 Streamable HTTP、独立 PRoot/CLI Runtime，以及 AppFunctions/App Intents 这类系统协议为主。

### 5.4 Helix 协议接入原则与 TODO

Helix 应把不同协议统一转换为内部 `ToolDescriptor`，所有调用继续经过同一条 Registry → schema validation → Capability → Policy → Approval → execution → Verification → Audit 管线：

```text
模型 ToolCall
    └── Helix Dispatcher
          ├── Android Intent/System API adapter
          ├── Android AppFunctions adapter（实验、条件满足后）
          ├── MCP Streamable HTTP adapter
          ├── PRoot stdio MCP adapter
          └── Accessibility adapter（无结构化接口时的最后兜底）
```

| TODO | 当前归属/触发条件 | 完成定义 |
| --- | --- | --- |
| 用结构化 Intent/System API 覆盖分享、打开 URI、剪贴板、通知和日历 | 已有 HXA-064/065；保持原任务顺序 | 目标/参数可预览；需要用户在目标 App 完成的步骤不冒充已执行；权限关闭返回稳定错误 |
| 验证手机 MCP Client 与动态 Tool bridge | 已有 HXA-070～072 | API 29/36、Streamable HTTP、schema hash、origin/egress、取消、重连与恶意 Server 测试通过 |
| 验证 PRoot stdio MCP | 已有 HXA-073，依赖 HXA-084 | 固定命令、环境 allowlist、stdout JSON-RPC、bounded stderr、取消和 Runtime 对账通过 |
| 跟踪 AppFunctions GA、allowlist 与 Gemini/第三方 Agent 开放条件 | **调研 TODO，不是已批准 HXA**；Android 官方状态变化时复核 | 明确 Helix 是否能作为 caller/provider、支持设备/OEM、权限与用户开关、失败/取消/恢复语义 |
| AppFunctions 真机 Spike | 仅在公开 caller 条件成立或 Helix 获测试资格后，由项目所有者授权新 HXA | API 36+ provider/caller fixture；函数枚举、启停、URI grant、敏感参数、取消、进程死亡和跨 App 审计均有证据；若改变安全边界则先走 ADR |
| Accessibility 调 App 仅作最后兜底 | 已有 HXA-090～093 | 目标包 allowlist、窗口/节点 token、敏感界面拒绝、每步复验和用户可停止，不因 AppFunctions/MCP 失败自动降级 |

结论：MCP 继续承担 Helix 的跨平台外部工具协议；Android AppFunctions 是最值得跟踪的本机 Agent-to-App 协议；现阶段可交付的系统 App 能力仍应优先使用结构化 Intent 和 Android API。协议自身的权限、annotation 或系统授权都不能成为 Helix Approval Proof。

## 6. 横向对照

符号说明：`是`=本轮官方来源明确；`有限`=范围或平台受限；`外部/可变`=取决于服务端、桌面或另一个 Runtime；`未证实`=没有足够公开证据。

| 产品/路线 | BYOK/多 Provider | 多步 Agent | 手机本机 shell/代码 | 系统/App 控制 | 调用级批准 | 持久审计/恢复 | 竞争关系 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Chatbox | 是 | 有限（MCP/应用内工具） | 未证实 | 未证实 | 未证实 | 会话有；执行审计未证实 | 体验/获客参照 |
| Cherry Studio Mobile | 是 | 有限，移动端仍演进 | 未证实 | 未证实 | 未证实 | 会话/迁移有；工具恢复未证实 | 体验/生态参照 |
| NextChat | 是 | 有限 | 否 | 否 | 未证实 | 浏览器会话为主 | 轻量体验参照 |
| LobeHub | 是 | 是 | 外部/可变 | 外部/可变 | 部分能力未证实 | 有 Agent/设备状态，安全语义需逐 target 核验 | Agent 平台参照 |
| Msty Go | 是/模型中立 | 是 | 外部/可变 | 未证实 | 公开称逐步审阅 | 企业治理/审计是卖点 | 治理参照 |
| Termux + CLI | 视 CLI 而定 | 是 | 是，Termux/PRoot 域 | 需 Termux:API/额外桥接 | 由 CLI 决定，粒度不统一 | 由 CLI 决定 | 高级用户替代方案 |
| AndCode | 是/自有账户 | 是 | 是，PRoot | 文件/默认助手；非通用 Accessibility 定位 | 危险工具批准；另有 bypass 模式 | 会话/定时任务/时间线 | **最直接竞品** |
| ClawMobile | 是 | 是 | 可选 Termux Runtime | 是，Accessibility/ADB 等 | 渐进授权；精确调用证明未证实 | logs/traces/skills | **最直接竞品** |
| Operit | 是，含云端/本地模型 | 是 | 是，Ubuntu 用户空间与终端 | 是，Browser/系统/UI 自动化 | 自动允许/询问/禁止 | 任务、工作区、工作流日志 | **能力密度直接竞品** |
| Open-AutoGLM | 模型/部署可替换 | 是 | 否，执行焦点是 GUI | 是，视觉屏幕动作 | 依具体实现 | 依具体实现 | **GUI Agent 参照** |
| Tasker/Auto.js/Hamibot | 不适用或外接 | 规则/脚本为主 | 脚本环境依产品 | 是，传统自动化强项 | 用户配置脚本/任务 | 运行日志依产品 | **用户与任务替代品** |
| Gemini Android | 否（Google 体系） | 是 | 否 | 是 | Android 权限 + 按 App 屏幕自动化选择 | 平台闭源 | 系统助手参照 |
| Claude Android | 否（Anthropic 体系） | 有限任务链 | 否 | 是，限定 App/Intent 能力 | 依系统权限/交互 | 平台闭源 | 系统助手参照 |
| 小艺 / HarmonyOS | 否（华为/鸿蒙生态） | 是 | 否，非通用 shell Runtime | 是，Intents Kit、系统能力与 HMAF GUI 路径 | 平台权限/交互明确；参数级一次性批准未证实 | 平台闭源；垂域智能体有业务记录 | **系统原生 Agent 生态参照** |
| **Helix 当前** | **是** | **是** | **否；已有原生文件工具** | **SAF/All-files 适配已落位，UI/Browser/设备动作未完成** | **是，exact binding + 一次性消费** | **是，Turn/Goal/Tool/Audit 基础已实现** | 文件能力底座已有，用户闭环尚待交付 |
| **Helix 目标** | **是** | **是** | **QuickJS + 独立 PRoot/CLI UID** | **Browser/SAF/All-files/Accessibility/Root** | **用户显式启用 Advanced 与具体能力；高影响动作保持真实可见** | **任务、产物、失败与恢复可观察** | Provider 中立、能力优先的本机执行工作台 |

## 7. Helix 的可守差异与短板

### 7.1 可守差异

1. **一个本机任务组合多类能力**：原生文件、Browser、QuickJS、PRoot/CLI 和 Android 高级能力共用同一 Agent 工作台，而不是把用户留在聊天客户端或零散脚本之间。
2. **Provider 中立**：OpenAI Responses、OpenAI Chat Completions、Anthropic Messages 和自建 OpenAI-compatible endpoint 已有独立适配与能力探测，后续还可承接官方 CLI。
3. **执行域分层**：E0 原生 Tool、E1 isolated QuickJS、E2 PRoot、E2C CLI Runtime 可以针对任务选择能力与成本，而不是所有任务都启动完整 Linux 环境。
4. **Standard/Advanced 与渠道解耦**：Standard 是 Google Play、国内商店和官网的完整产品；All-files、Accessibility、解释脚本等能力优先通过核心用途声明和审核保留，只把明确禁止的差异限制在对应渠道。官网 artifact 可保留商店不允许的能力，Advanced 则在实际 artifact 能力范围内呈现 Root、PRoot/CLI 和 Agent UI 自动化等专家入口。
5. **任务可观察和可继续**：用户可以看到工具过程、产物、失败与恢复状态；这既支持高级用户调试，也使手机碎片时间中的长任务可继续。

### 7.2 核验日短板

1. **文件工具尚未形成用户闭环**：底层 `read`/`write`/`edit`/`files.*`、SAF 和 All-files 适配已存在，但 HXA-046 的浏览、导入导出、trash 与 scope UI 尚未交付。
2. **与 Operit 的能力密度差距最大**：Browser、终端、工作流、本地模型、语音和市场均已有公开形态；Helix 当前不能用架构完整性代替这些可见能力。
3. **与 AndCode 的开发闭环差距存在**：PRoot、Git、diff、terminal 和 Agent Runtime 已可演示；Helix 的 E2/E2C 仍在后续里程碑。
4. **与 ClawMobile/Open-AutoGLM 的 Android 动作差距明显**：Accessibility、视觉 GUI Agent、通知和日历尚未实现。
5. **首次价值时间偏长**：Provider、Workspace、能力启用和审批信息较多，需要以模板、连接测试、能力中心和真实任务引导降低摩擦。

## 8. 产品与路线建议

### P0：先证明文件工作台的用户闭环

- 优先完成[实施状态](../development/status.md)所列的下一项文件工作台检查点，形成目录浏览、导入导出、scope 选择与 trash 管理闭环。
- 首个演示使用真实用户路径：配置 Provider → 选择目录 → 分析文件 → 预览变更 → 执行 → 查看结果与产物。
- 宣传“Android 本机文件工作台”，Browser/PRoot/Accessibility 未完成前不把目标能力写成现状。

### P1：把 Advanced 做成能力中心

- 每项能力统一展示用途、状态、安装/授权、验证、修复、暂停和清理。
- 用户显式开启 Advanced 与具体能力后承担操作责任；Helix 负责不隐瞒目标、scope、数据去向和失败状态。
- 减少重复说明和无意义确认，把信息集中在任务摘要、能力状态和高影响动作上；任何长期授权变化先按 ADR 决策。

### P1：缩短 BYOK 首次价值时间

- 保持模板化 Provider、连接测试和动态模型清单，避免要求普通用户理解协议细节。
- 将“只聊天”和“可执行任务”在首屏清晰分层；没有可用 Tool 时不要让 UI 暗示 Agent 已能操作手机。
- 以 Chatbox 的 Provider 易用性为下限，以“配置后立即运行一个本机任务”形成差异。

### P2：加快 PRoot/CLI 与 Android 自动化的可见能力

- E2/HXA-080～088 将 Runtime 安装、Workspace、Git、diff、terminal 和 `bash` 组织成一条移动开发闭环，而不是只交付后台 Runtime。
- E2C 优先验证一到两个真正有人使用的官方 CLI，完成登录、模型选择、会话、工具过程与产物回填。
- Accessibility/HXA-090～093 选择少量高传播力任务做真机演示，同时明确支持 App、版本、用户接管和失败边界。
- Shizuku/无线 ADB 与 Tasker/Auto.js 兼容 Runtime 作为未排期未来候选，不进入当前 HXA；未来立项前先完成协议/许可证、独立执行域、OEM 真机矩阵和断连恢复验证。云端 Worker 和群控仍不进入当前范围。

### P2：先做官方任务模板，再决定市场

- 从文件、网页、代码和系统 Intent 中选择 5～10 个维护方模板，随版本做回归验证。
- 记录模板复用率、修改成本和跨 ROM/Provider 成功率。
- 在签名、审核、撤销、兼容性和退款责任清楚前，不开放无边界 UGC 技能/脚本市场。

## 9. 建议的对外定位

推荐：

> Helix 是面向开发者和效率用户的 Android 本机 AI 执行工作台。它连接用户自己的模型，把文件、网页、代码和 Android 高级能力组合成真实任务；Advanced 由知情用户主动开启，用户掌握能力和最终决定。

第二层可信说明：Helix 如实展示任务目标、作用范围、数据去向、执行过程和结果。Trusted Workspace 与有界长期规则可以减少低风险重复确认，但模型不能替用户开启权限、创建授权或批准高影响操作。

避免：

- “首个/唯一真正的手机 Agent”；
- “PRoot/QuickJS 是虚拟机级沙箱”；
- “开启 Advanced 后无需再关心能力范围或操作后果”；
- “有 MCP 就能安全执行任意任务”；
- “支持 Claude Code/Codex CLI”——在 E2C Spike 和厂商条款/真机验收完成前只能写“计划评估”。

## 10. 后续跟踪指标

每次重大里程碑或每季度复核一次：

| 指标 | 目标 |
| --- | --- |
| 首次 Provider 配置到首条回复 | 能与主流 BYOK 客户端同量级完成 |
| 安装到首个本机任务完成 | 作为核心激活指标，记录步骤、失败和所需人工帮助 |
| 首个本机文件任务完成率 | 以固定 fixture 衡量，不以“ToolCall 成功”替代任务成功 |
| 7/30 日真实任务留存 | 只统计再次完成文件、网页、代码或 Android 工具任务的用户 |
| Advanced 能力启用与修复 | 记录安装、授权、验证、失效和修复漏斗 |
| 高风险误执行 | 0；无 Proof、过期 Proof、参数/scope/target 变化均不得执行 |
| 恢复重复副作用 | 0；unknown outcome 必须先对账 |
| 审批理解度 | 用户能从卡片辨认目标、影响、出网和验证方式 |
| 手机资源 | 前台/后台分别记录耗时、内存、热状态、电量与被系统回收情况 |
| 竞品状态 | 重点跟踪 Operit、AndCode、ClawMobile、Open-AutoGLM、Tasker/Auto.js/Hamibot、Gemini/Galaxy AI、LobeHub mobile 与系统 Agent 生态 |

用户、渠道、价格和商业指标详见[市场、用户与商业化分析](market-users-and-commercialization.md)。

## 11. 主要来源

- Chatbox：[安装与 BYOK](https://chatboxai.app/en/install)、[Provider 导入格式](https://docs.chatboxai.app/guides/providers/import-config)
- Cherry Studio：[移动端仓库](https://github.com/CherryHQ/cherry-studio-app)、[移动端 releases](https://github.com/CherryHQ/cherry-studio-app/releases)、[桌面端 MCP 环境](https://docs.cherry-ai.com/cherry-studio-wen-dang/en-us/advanced-basic/mcp/install)
- NextChat：[官方仓库](https://github.com/ChatGPTNextWeb/NextChat)
- LobeHub：[下载页](https://lobehub.com/downloads)、[2026 releases](https://github.com/lobehub/lobehub/releases)
- Msty：[产品体系](https://msty.ai/)、[Msty 1.x/Studio 能力对照](https://docs.msty.app/getting-started/onboarding)
- Codex CLI：[OpenAI 官方文档](https://learn.chatgpt.com/zh-Hans/docs/codex/cli)
- Claude Code：[官方安装与系统要求](https://code.claude.com/docs/en/installation)
- Gemini CLI：[官方文档](https://google-gemini.github.io/gemini-cli/)
- Aider：[官方安装文档](https://aider.chat/docs/install.html)
- goose：[官方 Termux 构建状态](https://github.com/aaif-goose/goose/blob/main/BUILDING_LINUX.md)
- Termux：[官方仓库与 Android 12+ 进程限制说明](https://github.com/termux/termux-app)
- Operit：[官方仓库与功能说明](https://github.com/AAswordman/Operit)
- AndCode：[官方仓库、安全与 Runtime 说明](https://github.com/yuga-hashimoto/and-code)
- ClawMobile：[官方仓库、架构与 preview 限制](https://github.com/ClawMobile/ClawMobile)
- Open-AutoGLM：[官方仓库](https://github.com/zai-org/Open-AutoGLM)
- Tasker：[官方站点](https://tasker.joaoapps.com/)
- Hamibot：[官方定价与版本](https://www2.hamibot.cn/pricing)
- Gemini Android：[设备辅助](https://support.google.com/gemini/answer/15235441?hl=en)、[Connected Apps](https://support.google.com/gemini/answer/13695044?co=GENIE.Platform%3DAndroid&hl=en)、[屏幕自动化](https://support.google.com/gemini/answer/16940971?hl=en)
- Samsung Android Agent 生态：[Bixby 设备 Agent](https://news.samsung.com/us/samsung-introduces-new-bixby-one-ui-8-5)、[Galaxy AI 多 Agent 生态](https://www.samsung.com/ae/news/local/galaxy-ai-expands-multi-agent-ecosystem-to-give-users-more-choice-and-flexibility/)、[Bixby 开发平台](https://developer.samsung.com/bixby)
- Claude Android：[Android App actions](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps)
- Android Agent-to-App：[AppFunctions 概览](https://developer.android.com/ai/appfunctions?hl=en)、[`EXECUTE_APP_FUNCTIONS` 权限](https://developer.android.com/reference/android/Manifest.permission#EXECUTE_APP_FUNCTIONS)
- Apple Agent-to-App：[App Intents](https://developer.apple.com/documentation/appintents)、[WWDC 2026 system orchestrator 与 app toolbox](https://developer.apple.com/videos/play/wwdc2026/112/)、[Foundation Models](https://developer.apple.com/documentation/FoundationModels)
- 移动 MCP：[Claude Mobile Connectors](https://support.claude.com/en/articles/11176164-use-connectors-to-extend-claude-s-capabilities)、[Cherry Studio Mobile releases](https://github.com/CherryHQ/cherry-studio-app/releases)、[ChatGPT MCP Apps 移动端限制](https://help.openai.com/en/articles/12584461-developer-mode-apps-and-full-mcp-connectors-in-chatgpt-beta)
- HarmonyOS Agent 生态：[HarmonyOS 7 与 HMAF 2.0](https://www.huawei.com/cn/news/2026/6/harmonyos7-hdc)、[HarmonyOS AI 能力与端/云 A2A](https://developer.huawei.com/consumer/cn/harmonyos-ai)、[Intents Kit](https://developer.huawei.com/consumer/cn/sdk/intents-kit)、[HarmonyOS 意图框架](https://developer.huawei.com/consumer/cn/huawei-hag/)、[HMAF 与小艺开放平台开发模式](https://developer.huawei.com/consumer/cn/activity/incentive/ai/)、[小艺开放平台精选案例](https://developer.huawei.com/consumer/cn/celia?ha_source=InfoQ&ha_sourceId=70000011)、[HarmonyOS 文档中心](https://developer.huawei.com/consumer/cn/doc/?catalogVersion=V2)、[元服务](https://developer.huawei.com/consumer/cn/fa)
- 鸿蒙代表性智能体：[小艺 App 智能体](https://consumer.huawei.com/cn/support/content/zh-cn16076199/)、[小艺深度解题](https://consumer.huawei.com/cn/support/content/zh-cn16051425/)、[客服小艺](https://consumer.huawei.com/cn/support/content/zh-cn16101343/)

本报告不替代各产品的许可证、隐私政策、商店地区可用性和服务条款审查。若某项能力进入 Helix 依赖、分发或登录方案，仍须按 [开源依赖与参考仓库](../references/open-source-projects.md) 和 [ADR 约定](../adr/README.md)单独决策。
