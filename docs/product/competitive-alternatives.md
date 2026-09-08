# 移动 Agent：替代方案与技术参照

更新日期：2026-09-08。本文件按用户任务组织相邻产品，不把聊天客户端、远程控制器、GUI 模型和自动化脚本放进同一个功能总分。客户端/CLI 沿用 09-03 快照，其余沿用 09-08 核验；新增 DroidClaw、DroidMind、Mobile Next 于本轮核验。

[返回竞品总览](competitive-landscape.md) · [方法与横评](competitive-evaluation.md) · [证据与校正](competitive-evidence.md)

## 1. 聊天入口与 Agent 平台

### 增强型 AI 客户端

| 产品 | 已核实的移动形态 | BYOK/Provider | Agent/工具边界 | 对 Helix 的意义 |
| --- | --- | --- | --- | --- |
| [Chatbox](https://chatboxai.app/en/install) | iOS、Android、Web、桌面，含 Android 直装 APK | 支持自带 API Key；可导入 OpenAI-compatible Provider 配置 | 文件、联网、知识库；付费页公开 MCP。未见通用 Android 本机 shell/Accessibility Runtime 证据 | 低门槛 Provider onboarding 与跨设备聊天参照 |
| [Cherry Studio Mobile](https://github.com/CherryHQ/cherry-studio-app) | 官方 React Native iOS/Android App；[release](https://github.com/CherryHQ/cherry-studio-app/releases) 仍处移动早期版本 | 多 Provider，功能持续补齐 | README 明确助手、对话、文件/迁移等；仓库含 MCP Streamable HTTP 包，但不能据此推断与桌面端完全等价 | 观察桌面强产品如何缩减到移动端，以及移动/桌面数据迁移 |
| [NextChat](https://github.com/ChatGPTNextWeb/NextChat) | 响应式 Web/PWA，另列 iOS 与多桌面平台 | OpenAI-compatible 与自部署模型 | 轻量聊天、Prompt/Mask、流式响应；不是 Android 系统能力执行器 | 轻量、快速首屏、自托管和本地浏览器存储的体验基线 |

这类产品的核心竞争点是“几分钟内开始聊天”。Helix 即使投入更多工具边界工程，如果 Provider 配置、连接测试、模型选择、费用/出网提示明显更复杂，也会在首次使用阶段流失用户。

### Agent 工作台与运行时路由

| 产品 | 已核实能力 | 执行位置判断 | 主要差异 |
| --- | --- | --- | --- |
| [LobeHub](https://lobehub.com/downloads) | iOS/Android/桌面；Agent、MCP、Skill、异构 Agent 与设备网关。2026-08 release 明确本地 stdio/LAN MCP 是桌面能力 | 手机可作为 Agent 前端；具体工具可能由云、桌面或已注册设备执行，必须按 target 分辨 | 生态、Agent 市场、多 Agent 与跨设备路由强；不是“所有能力都在当前手机本机”的同义词 |
| [Msty](https://msty.ai/) | Studio、Go、Nexus、Stack；Go 宣传有边界、可逐步审阅、可从 desktop/mobile 控制的任务 Agent | 官网表述强调跨端控制与治理，未据此确认所有工具在手机本机执行 | 企业治理、模型网关和知识层是强项；Helix 当前不做组织级控制平面 |

这类产品提醒 Helix：UI 必须持续展示 execution target、数据去向与能力来源。只显示“工具正在运行”会掩盖本机、桌面网关和云端执行的本质差异。

## 2. 专家自带环境：Termux 与 PRoot

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

Helix 的 E2C 当前采用独立 UID 的第三方订阅协议适配实验，并非在 Android 完整运行官方 CLI；凭据由 Runtime 自持，主 App 通过有界协议使用模型服务。官方 CLI 路线受平台可行性限制，现行决定见 [ADR-0021](../adr/0021-third-party-subscription-protocol-adapter.md)。订阅登录、实际模型调用、服务商支持与商店分发是不同证据，不能互相替代。

Termux 与 PRoot 也不能作为同类产品直接二选一：Termux 是原生 Android 命令行环境、终端和包生态，不能与底层兼容工具直接比较用户量；PRoot 是可嵌入独立 Runtime 的 Linux RootFS 兼容层，系统调用拦截可能增加文件密集任务开销，具体性能需同机测量，且自身不提供安全隔离。对 Helix，外部 Termux 更适合研发 Spike/专家自带环境，正式 E2 路线仍采用固定资产、无网、独立 UID 的 PRoot companion；常用能力优先 E0 原生 Tool，避免为所有任务支付 PRoot 开销。完整功能、社区、用户量、性能、许可证和集成路径比较见[本地代码执行方案 §6.2](../architecture/local-code-execution.md#62-termux-与-proot-对比及集成结论)。

## 3. 远程与云端成果产品

| 产品 / 一手来源 | 运行位置与长板 | 局限及 Helix 可借鉴项 |
| --- | --- | --- |
| [Happy](https://github.com/slopus/happy) | 电脑运行包装 CLI；iOS/Android/Web 控制会话，公开列端到端加密与移动交互 | 电脑仍需可达；借鉴任务返回、批准与移动/终端切换。加密传输不等于执行端没有权限风险 |
| [HAPI](https://github.com/tiann/hapi) | CLI 会话由部署机器运行，Web/PWA/Telegram Mini App 远程控制，多 Agent 适配 | “local-first”指自托管，不等于手机本机；借鉴一个入口管理不同模型/会话，不因此引入远程 Worker |
| [OpenClaw 官方 Android](https://docs.openclaw.ai/platforms/android) | 官方明确 companion node，Android 不托管 Gateway；手机能力经配对节点提供 | 配对、连接状态与设备能力说明是体验参照；网关需要另一台机器，不能与第三方本机移植版混为一谈 |

对已有常开电脑的开发者，这类产品可能比手机本地安装 Linux 更省事。因此 Helix 的“无需电脑”是特定用户价值，不是所有开发者的优势。远程编译、大仓库与长任务是它们的合理场景；Helix 应优先证明随身文件和 Android 本机任务。

| 云端/开发产品 | 产品证据与价值 | 与 Helix 的区别 |
| --- | --- | --- |
| [Replit Mobile](https://replit.com/products/mobile) | 从手机描述需求、构建和发布网站/App 的产品入口 | 学习需求到预览/成果的闭环；官网明确原生移动 App 的完整创建与商店提交流程需电脑 Web 入口，不能沿用“全程手机即可上架”的笼统结论 |
| [Manus 产品页](https://manus.im/)、[移动下载](https://manus.im/download) | 有移动入口及报告、演示稿等成果交付定位 | 属成果体验参照；有手机 App 不证明在手机内执行 shell，本轮不认定其完整移动能力、积分或收费状态 |

云端算力、现成服务和报告/演示稿交付是合理竞争力；“有移动 App”不等于 shell 在手机。复杂办公格式仍非 Helix 已交付输入，不能把研究参照转成首发承诺。

## 4. GUI 模型、控制框架和设备桥

| 项目 / 一手来源 | 是什么、部署证据 | 值得学习 / 不应推导的结论 |
| --- | --- | --- |
| [DroidRun / MobileRun](https://github.com/droidrun/mobilerun) | Python/CLI Agent 框架，连接手机 Portal，可用 UI 树、截图与动作 | 学习状态提取、App 指引、轨迹与结果检查；框架控制真机不证明完整 Loop 在 APK 内 |
| [hanxi/droidrun-agent](https://github.com/hanxi/droidrun-agent) | Portal 的 Python HTTP/WebSocket client 和 MCP Server，使用认证 token | 是设备能力桥参考；“无需 Root/ADB”不等于单 APK、无需外部 Python 或无需系统授权 |
| [MAI-UI / Qwen-UI-Agent](https://github.com/Tongyi-MAI/MAI-UI) | GUI 模型/研究家族，当前仓库包含后续 Qwen-UI-Agent；有真实设备评测方向 | 学习 GUI 定位、长任务和评测方法；榜单分数不能迁移成 Helix 在用户 App 的成功率，也不证明手机能本地推理该模型 |
| [MobiAgent](https://github.com/IPADS-SAI/MobiAgent) | 含 Android App、部署服务、ADB runner、记录/回放及 MobiFlow 评测 | App 入口不能证明无需服务；学习轨迹复用和阶段结果评估，不能把历史成功轨迹当新任务批准 |
| [Mobile-Agent](https://github.com/X-PLUG/MobileAgent) | GUI Agent 家族与研究实现，覆盖移动等界面场景 | 作为视觉控制与记忆策略参照；每代模型/框架/运行条件分开，不按统一 App 产品评分 |
| [AgentCPM-GUI](https://github.com/OpenBMB/AgentCPM-GUI) | 手机 GUI 模型与推理/评测代码；示例包含 CUDA 推理 | 小模型动作输出可研究；“on-device”目标不等于任意 Android 的性能/安装证据，本轮未部署 |
| [Mobile-MCP](https://github.com/system-pclub/mobile-mcp) | Android Intent、PackageManager 发现与 PendingIntent 回调的协议/原型 | 补充“Agent 调 App 不只有网络 MCP”的案例；不是 Android 官方标准，也不证明已有广泛第三方 App 适配生态 |

对 Helix 当前最有用的是**缩小观测、稳定定位、动作后复核、可重现评测**。只有实际瓶颈证明通用 Provider 不够时，再按新任务评估专用 GUI 模型；本轮不新增模型、ADB 服务或自动回放机制。

### Android 社区 GUI App

| 项目 | 公开路线 | 待验证边界 |
| --- | --- | --- |
| [Luokavin/AutoGLM-For-Android](https://github.com/Luokavin/AutoGLM-For-Android) | 独立 Android 社区实现，使用 Shizuku、兼容视觉 API，列暂停/继续/取消、模板与悬浮进度 | 与官方 Python/ADB 路径分列；“免电脑”仍有系统能力启用成本，不采纳过期的模型限时免费承诺 |
| [GiggleWang/MobileAgent-Android](https://github.com/GiggleWang/MobileAgent-Android) | 找到原材料所指的具体原生 Android 项目：视觉模型驱动手机操作，无需 PC/ADB 的实现方向 | 作为手机 GUI 体验候选；不能与 X-PLUG 的研究家族混同，Manager/Executor 等角色拆分也不自动证明可靠性 |

### 新增：DroidClaw / unitedbyai

[unitedbyai/droidclaw](https://github.com/unitedbyai/droidclaw)的 README 示例以 Bun + ADB 驱动手机，包含屏幕状态变化、重复动作检测、视觉兜底，以及模型工作流/确定性 flows。仓库同时有 APK 和服务端入口，但 APK 存在不能证明默认完整 Loop 已在手机运行；本轮确认的是文档中的宿主机控制路径，其他模式待专项核验。

可参考无效重复动作提示和轨迹反馈；不能把维护者演示转为“任意 App 保证可用”，也不采纳自动发送、订购等示例进入 Helix。与 QSH36 同名 App 分列；levilyf 同名项目本轮不扩展成第三份重点档案。

### 新增：DroidMind 与 Mobile Next MCP

| 项目 / 一手来源 | 已核实的部署路线 | 研究价值与不能推出的结论 |
| --- | --- | --- |
| [DroidMind / hyperb1iss](https://github.com/hyperb1iss/droidmind) | Python MCP Server，经 ADB 连接 Android；列文件、日志、App 管理、shell 和 UI 动作 | 开发诊断与设备工具 schema 的参照；不是独立手机 Agent，也不是 Android 系统 MCP。风险校验属于项目声明，未独立审计 |
| [Mobile Next / mobile-next/mobile-mcp](https://github.com/mobile-next/mobile-mcp) | 宿主机 MCP 对接 Android ADB、iOS 真机/模拟器；另提供云设备路径 | 可参考结构化 UI snapshot 与截图兜底；iOS 测试设备可控不等于普通 iOS App 可跨 App 操作。云设备与本地设备分开记录 |

Mobile Next 与 [system-pclub/mobile-mcp](https://github.com/system-pclub/mobile-mcp)是不同实现：前者是自动化工具服务，后者研究 Android App 能力协议。不得合并功能、用户量或许可。上述桥接实现只是技术参照，不授权给 Helix 新增 ADB/云设备依赖。

## 5. 离线模型、浏览器助手与确定性自动化

| 产品 / 一手来源 | 核验到的产品方向 | 对 Helix 的优势压力 / 边界 |
| --- | --- | --- |
| [PocketPal AI 主仓库](https://github.com/a-ghorbani/pocketpal-ai) | iOS/Android 本地 GGUF、TTS、Pals 与工具 Loop、设备 benchmark | 模型下载后可离线使用是独立价值；工具列表不等于通用 Android 控制。Helix 本机执行但经公网模型推理时，不能宣称同样离线 |
| [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) | 本机模型示例与 Agent Skills；提供模型/技能试用路径 | 学习设备适配、模型加载和技能展示；Gallery 是示例型产品，不能据此推导完整任务恢复/文件工作台；联网 Skill 也不因推理本地就自动离线 |
| [Perplexity Android Assistant](https://www.perplexity.ai/help-center/en/articles/10450852-how-to-use-the-perplexity-android-assistant) | 默认助手入口、语音及支持 App 的任务 | 争夺日常助理入口，低摩擦价值明显；非 BYOK 通用 shell，App/地区与账户可用性按实际版本判定 |
| [Comet](https://www.perplexity.ai/comet) | 官方列 Android/iOS 等平台的 AI 浏览器 | 网页上下文和浏览器内助理是 Helix Browser 的体验参照；桌面功能不能自动视为移动端等价，不替代本机文件/设备执行 |
| [Automate](https://llamalab.com/automate/) | Android 可视化流程自动化，[Interact](https://llamalab.com/automate/doc/block/interact.html)提供界面操作 | 稳定重复任务不必每次消耗模型；学习触发、条件、故障提示与流程可读性，完整可执行工作流仍不进入 Helix 当前范围 |
| [MacroDroid](https://macrodroid.com/) | 触发器、动作与约束组成自动化，面向易用配置 | 简单场景可能比 Agent 更快、更可预测；Helix 应比较完成成本，不能只比工具数。与 Tasker/Auto.js 同属替代方案，但交互门槛不同 |

离线模型、语音入口和确定性自动化不是同一条路线。Helix 应接受在“离线聊天”“固定触发操作”“重型远程开发”上其他方案可能更合适，不以扩充全部能力作为默认应对。

### Tasker、Auto.js 与 Hamibot

[Tasker](https://tasker.joaoapps.com/)与 [Hamibot](https://www2.hamibot.cn/pricing)保留为任务替代参照，具体套餐不采用历史报价；Auto.js/AutoX.js 必须固定具体维护者与版本，不能统一宣称兼容。稳定、重复、规则清楚的任务，应同时与手工操作和确定性脚本比较。自然语言降低编写门槛的收益，需要扣除模型调用、排错和人工接管成本。

## 6. 观察池与排除规则

[Opclaw 商店页](https://play.google.com/store/apps/details?id=com.opclaw.android)与 [MobiClaw.ai](https://mobiclaw.ai/)是可继续核实的移动 OpenClaw 产品线索；本轮未建立足够的仓库—签名—具体版本能力链，暂不评分。普通聊天 fork、只有截图的项目和相同上游的重复包装也不逐个堆入主表。

AutoGLM 消费服务、Open-AutoGLM 模型/代码和社区 Android APK 分开记录；[当前消费入口](https://autoglm.zhipuai.cn/)不能代表全部同名路线。百度心响/GenFlow、扣子 App、Reins/BotGem、AppAgentX 仍为线索，未建立精确版本能力链，不加入评分。

新增对象至少满足一项：改变目标任务选择、有明确不同执行路线、可安装产品或可审阅实现。相同上游重复包装、只有宣传图或无法识别维护者的项目，先留观察池。这里不是穷尽名录；优先用实测减少不确定性，而非无限增加名称。
