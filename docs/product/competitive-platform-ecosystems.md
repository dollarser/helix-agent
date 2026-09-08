# 系统 Agent 生态与移动 App 调用协议

更新日期：2026-09-08。HarmonyOS、Apple、Gemini 等内容沿用 09-03 官方来源快照；OEM 增补来自同日上一轮核验；本轮重新核验 Android AppFunctions。这里是系统生态参照，不是 Helix 接入许可或支持设备清单。

[返回竞品总览](competitive-landscape.md) · [来源与证据](competitive-evidence.md)

## 1. 比较方法

不要用“更完善”作无条件排名。分别看系统入口一致性、开发者能力注册、业务覆盖、第三方准入、跨平台可移植性和可复现任务质量。宣传集中程度可以解释生态感知，但不能替代这些指标。宣称来自厂商、开发者 Beta、设备实际可用与全量发布必须分开。

## 2. Android 助手入口

| 产品 | 当前公开能力 | 与 Helix 的关系 |
| --- | --- | --- |
| [Gemini on Android](https://support.google.com/gemini/answer/15235441?hl=en) | 设备设置、闹钟/计时器、媒体、通知读取/回复、打开 App/页面等；[屏幕自动化](https://support.google.com/gemini/answer/16940971?hl=en)按 App 提供 Always allow / Ask every time / Do not allow | 系统集成、分发和低摩擦体验远强于独立 App；但不是 Provider 中立、BYOK、本地代码/Workspace Agent |
| [Claude on Android](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps) | 可通过 Android 系统/第三方 App 起草或发送消息、邮件、日历、地图等任务，具体能力依权限而异 | 证明“官方聊天 App + Android actions”已成为基准；公开范围仍不同于通用本机 shell/文件 Runtime |

这类产品会抬高用户对语音入口、系统分享、默认助手、低延迟和权限引导的预期。Helix 的差异是开放 Provider、可检查的本机工作区与更细的安全/审计契约，而不是覆盖面或预装优势。

## 3. HarmonyOS：产品、协议与业务分发

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

### 鸿蒙生态代表性“精品”

| 产品/形态 | 已公开体验 | 为什么值得纳入竞品分析 |
| --- | --- | --- |
| **小艺系统 Agent** | 从问答入口扩展到意图理解、系统感知、任务规划与应用/元服务执行；HMAF 2.0 增加系统 AI 和 GUI 操控开放能力 | 代表“OS 自带 Agent + 系统能力注册表”的预装分发形态，是 Helix 在系统入口、低摩擦调用和能力发现上的上限参照 |
| **客服小艺** | 覆盖手机、平板和鸿蒙电脑，可做官方知识问答、设备故障检测、服务查询、转人工以及部分系统一键操作 | 不是泛聊天，而是知识、诊断工具、服务流程和系统动作闭环的垂域 Agent 样板 |
| **小艺深度解题** | 拍题、批改、互动讲解、学习诊断、错题练习、悬浮窗和跨应用图片入口 | 体现视觉输入、长期学习资产、应用间入口与专用工作流如何组合成完整垂域产品 |
| **小艺运动健康 / 翻译助手** | 在手表等终端提供健康数据分析、运动建议和翻译；同一广场还接入 DeepSeek、讯飞学习搭子等三方智能体 | 证明智能体市场与穿戴设备入口已落地，但不同设备、系统版本和语言/地区存在能力差异 |
| **小雅 AI / AiPPT.cn / 讯飞晓医 / 深航飞飞** | 官方精选案例分别覆盖音频搜推与播控、一句话生成 PPT、健康问答，以及航班查询、订票和值机选座 | 说明三方智能体已经覆盖内容、生产力、健康和出行，不只是模型聊天；健康建议等高风险结果仍需单独评估准确性与责任边界 |
| **京东 Agent / 同程程心 Skill** | 官方提供云 A2A 账号授权与会话交互案例，以及端云协同/场景化 Skill 开发范例 | 体现现有业务 Agent 接入系统入口的开发路径；竞争单位从单 App 扩展为“智能体市场 + 开发平台 + 系统分发” |
| **元服务** | 轻量服务可通过小艺建议、搜索和场景入口直接触达，并支持多设备部署/流转 | 给 Helix 的启发不是复制鸿蒙形态，而是让结构化能力可发现、可组合、按场景呈现 |

“鸿蒙开发者知识 MCP”出现在官方 AI 开发资源中，可作为开发知识与工具入口；本轮没有发现它是面向任意手机 App 的系统级 MCP Registry。鸿蒙当前更关键的系统协议是 Intents Kit/HMAF/A2A，而不是把所有应用接口统一包装成 MCP。

### 为什么鸿蒙生态看起来更完整

这种完整感主要来自“产品与生态的一体化”，不等于每一层都比 Android/iOS 更开放或更成熟：

1. **同一厂商贯通全链路**：华为同时控制 HarmonyOS、系统 Agent 入口、小艺开放平台、Intents Kit/HMAF、应用与元服务分发以及终端产品，开发者看到的是一条连续路径，而不是多个厂商协议的拼装。
2. **智能体市场被显式产品化**：小艺广场集中呈现官方和三方垂域 Agent，并提供开发、调试、审核与上架链路。Android 和 iOS 的类似能力更多散落在 Connected Apps、OEM 助手、App Store、Shortcuts 和具体 App 内。
3. **元服务适合作为 Agent 履约单元**：轻量服务可以从语音、搜索、建议卡片等系统入口直达，弱化“先安装并打开完整 App”的交互成本。
4. **统一的命名和发布节奏**：HMAF 2.0、“意图即服务”、端/云 A2A、GUI 操控和精选案例在同一发布周期集中出现，市场感知比 Android 的 Google/OEM 分层以及 Apple 的框架式表达更强。
5. **中国本地服务闭环集中**：健康、教育、出行、内容和客户服务可以围绕小艺入口形成可展示的垂域闭环，因而比单纯展示一个开发 API 更容易被用户感知。

需要保留三个校准：HMAF 2.0 与部分 GUI 能力仍处开发者 Beta/渐进开放阶段；第三方 Agent 的可用设备、地区、语言和版本并不一致；平台审核和系统权限也不能证明参数级审批、恢复语义与审计已经达到 Helix 的目标标准。

### HarmonyOS、Android 与 iOS 系统 Agent 生态对照

| 层级 | HarmonyOS | Android | iOS |
| --- | --- | --- | --- |
| 系统 Agent 入口 | 小艺 / Harmony Intelligence | Gemini；Samsung 设备另有 Bixby/Galaxy AI | Siri / Apple Intelligence |
| App 能力语义层 | Intents Kit | AppFunctions | App Intents |
| 系统编排 | HMAF，支持 LLM、Workflow、端/云 A2A | Gemini Connected Apps、Device Assistance、AppFunctions；OEM 另有私有编排 | Apple Intelligence system orchestrator、app toolbox、Shortcuts |
| 第三方分发 | 小艺智能体市场、Skill、元服务 | Play 应用、Connected Apps、OEM 助手生态、Bixby Capsule，入口分散 | App Store、Shortcuts、App Intents；本快照未核实独立系统 Agent 市场 |
| GUI 自动化 | HMAF 2.0 公布面向开发者的 GUI 操控能力 | Gemini Screen Automation Beta，限定设备、地区、语言和 App | 更偏向结构化 App Intents；通用跨 App GUI 自动化受沙箱限制 |
| App 内 Agent 开发 | HMAF、Agent Framework Kit、HarmonyOS AI Kits | AppFunctions、传统 Android API 与各厂商 AI SDK | Foundation Models 的结构化生成/Tool calling + App Intents |
| MCP 定位 | 可接开发知识或云端工具，但不是 OS 核心 App 协议 | 可作外部工具协议，但 AppFunctions 才是系统函数层 | 可作远程连接协议，但 App Intents 才是系统动作层 |

三者并不是简单的“鸿蒙有、另外两个没有”：

- **Android：能力覆盖广但碎片化。** Gemini 已通过 Device Assistance 和 Connected Apps 操作设备、消息、日历、媒体及部分第三方服务；部分设备还提供多步 Screen Automation。AppFunctions 则让 App 声明类型化函数，供受信任、系统特权的 Agent 发现和执行，但当前仍是 beta/experimental preview。Samsung 又在 Android 之上提供 Bixby、Galaxy AI、SmartThings 和多 Agent 入口，因此最接近鸿蒙完整产品形态的是“Galaxy AI + Bixby + Gemini + Samsung Apps”，而不是抽象的裸 Android。
- **iOS：底层体系完整但表达更克制。** App Intents 把 App 动作和实体提供给 Siri、Shortcuts、Spotlight、控件和 Apple Intelligence。Apple 在 2026 年进一步将其描述为 semantic index、app toolbox 与 system orchestrator；端侧 Foundation Models 的结构化生成/Tool calling 还需与系统云服务能力分别核实，不能混为任意服务器模型通道。Apple 的产品叙事更接近“让每个 App 融入系统智能”，而不是建立一个显眼的第三方 Agent 广场。
- **HarmonyOS：产品呈现集中。** 系统、助手、协议、市场和元服务都由同一生态组织，产品闭环最容易理解；代价是平台专属、准入受控，跨平台可移植性和第三方 Agent 的系统级权限仍需逐项核验。

根据上述公开产品组织方式，可推断 HarmonyOS 的生态呈现更集中；iOS 提供结构化系统集成与端侧开发框架参照；Android 则存在 Google 与 OEM 分层。这里没有统一样本支撑“最成熟/最开放”的绝对排名。三者都不是“任意 Provider + 任意系统能力 + 逐调用精确审批”的开放通用 Runtime。

### 对 Helix 的直接启示

1. **优先结构化能力，再退化到 GUI 自动化**：Intents Kit 的“意图连接业务功能”与 Android AppFunctions 同方向。Helix 应优先已获授权的 Intent/System API；AppFunctions 仅作准入条件满足后的研究候选，Accessibility 只处理没有正式接口的旧 App。
2. **把发现、编排和执行拆开**：小艺入口、HMAF 编排、Intents Kit 注册与应用/元服务执行是不同层。Helix 也不应因发现了 Tool/MCP 就默认它可执行或已获授权。
3. **做垂域闭环，而不只做通用聊天**：客服小艺和深度解题的竞争力来自“输入—工具—业务结果—后续服务”的闭环。Helix 应先用受控文件工作区形成同样可演示、可验收的闭环。
4. **保留逐调用安全差异**：系统权限、平台审核、HMAF/A2A 声明或 GUI 能力都不能替代 Helix 的 exact ToolCall Approval、Verification 与 Audit。
5. **只吸收跨平台协议价值**：鸿蒙多端、元服务和系统级编排仍只是参照；Helix 已有通用 A2A Client 分项验收，不新增 HarmonyOS、跨设备 Worker、A2A Server 或递归多 Agent。

鸿蒙专项跟踪项：持续核验 HMAF 2.0 白皮书与通信协议的公开细节；比较 Intents Kit、Android AppFunctions 和 Apple App Intents 的 schema、发现、权限、取消及恢复语义；每季度抽查小艺智能体广场中可复现的垂域闭环。以上均为调研 TODO，不是已批准实现任务。

## 4. 中国 Android OEM：同样存在系统与业务闭环

以下是 09-08 官方公开能力观察，不按品牌作成熟度或成功率排名。

| 产品 / 来源 | 保留的公开信息 | 对 Helix 的意义与边界 |
| --- | --- | --- |
| [荣耀 YOYO / MagicOS](https://www.honor.com/cn/magic-os/)、[技能接入文档](https://developer.honor.com/doc/guides/101776) | 系统 Agent 和开发者技能入口；文档列 GUI task 工具及多 App 任务拆分方式 | 学习意图到能力发现、任务拆分和状态展示；平台准入/适配是优势，但不是“个人开发者没有入场券”的证明，也不代表 Helix 已具备接入资格 |
| [超级小爱 / HyperOS](https://hyperos.mi.com/)、[隐私说明](https://privacy.mi.com/xiaomihyperxiaoai/zh_CN/) | 屏幕交互、个人上下文与应用操作方向；具体功能仍依设备/版本 | 原文“只停留在语音指令”已不适合作为当前结论；端云数据路径按功能核验，不能统称全部端侧或全部云端 |
| [OPPO AI / 小布](https://www.oppo.com/cn/discover/technology/oppo-ai/) | 记忆、问屏、简报和多模态交互等系统入口 | 可借鉴从当前屏幕开始任务与结果卡片；不是本机通用代码工作台，也不能据品牌宣传推导任意 App 自动化 |
| [vivo 蓝心小V 快捷指令](https://www.vivo.com.cn/service/questions/all?categoryId=170&questionId=2052) | 查看、创建、执行一键指令的官方使用说明 | 模板可发现性是参照；不使用旧版文档给整个品牌当前 Agent 能力排名 |

## 5. 移动端 MCP、A2A、Agent-to-App 与系统协议

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

- API 与 Jetpack 库仍是 experimental preview；本轮官方页仍说明有限 App/系统 Agent 的 EAP 准入，登记不等于获得访问。
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

A2A 补上的是另一层：它不把远端能力压平成单次 Tool API，而是通过 Agent Card 发现 Agent/Skill，并维护可流式、可取消、可恢复的 Task。官方 v1.0 已稳定并提供 Java SDK，因此值得进入 M7；但移动端仍应以 Client-only 为先，避免让手机承担公网入站 webhook/Server 生命周期。对 Helix 来说，A2A endpoint 是可选外部 Agent 服务，不是本机系统 MCP，也不是远程 Worker。

### 5.4 对 Helix 的映射与研究边界

MCP 是外部工具协议，A2A 是用户配置的外部 Agent 任务协议，Skills 是可移植流程知识；它们不替代本机授权。系统接口、MCP 或 Accessibility 失败都不能触发未经批准的能力升级。

已有 Android Tools、MCP HTTP/PRoot stdio、A2A Client、Accessibility 的实现与验收只在[实施状态](../development/status.md)和完成记录维护，不再在竞品报告复制为“待开发 TODO”。本轮不变更架构。

仅保留两项研究候选：

- AppFunctions：官方开放条件变化或 Helix 获正式测试资格后，先核实 caller/provider 身份、设备、权限、取消和进程死亡语义，再由所有者决定是否立新 HXA。
- 系统语义层对照：对 Intents Kit、AppFunctions、App Intents 使用同一任务检查发现、授权、结果与恢复；不把任一生态注册结果当成 Helix ToolCall Approval。

规范性边界见[Provider 与扩展架构](../architecture/provider-mcp-skills-modes.md)。跨设备、HarmonyOS 客户端、A2A Server 和远程 Worker 不因本调研进入实现范围。

### 5.5 本轮 AppFunctions 复核

[Android 官方概览](https://developer.android.com/ai/appfunctions)仍将 AppFunctions 定为实验功能，完整链路仅向有限 App/系统 Agent 开放；应用可实现并测试函数，EAP 登记不等于获准全链路接入。因此，Helix 可研究 provider 侧实现，不应把 caller 侧普遍可用作为产品前提。

“AppFunctions 类似移动 MCP”仅是能力描述类比：它是 Android OS 函数通道；MCP 本身既可通过网络，也可通过本地 stdio 使用，不能因官网的云服务对比措辞写成“MCP 必须在云端”。

## 6. 需要继续核实的生态事实

发布前重查系统版本、机型、地区/语言、账户要求、第三方上架准入及实际调用 App 清单。当前未完成跨 OEM/iOS/HarmonyOS 同任务横评。豆包手机助手的合作机型、所谓 App 兼容数量与二代协议名单仍缺足够一手证据，不沿用旧材料数字。系统产品源链接集中保留在[来源记录](competitive-evidence.md#4-既有主要来源)。
