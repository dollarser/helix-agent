# 移动本机 Agent：直接竞品档案

更新日期：2026-09-08。本文负责逐产品事实、长短板和待验证问题。除 AndCode/ClawMobile 与本轮新增产品已重新浏览一手来源，其余沿用同日上一轮核验；均非本轮 APK 实测。主分支与实际发行版不等同。

[返回竞品总览](competitive-landscape.md) · [方法与横评](competitive-evaluation.md) · [证据与校正](competitive-evidence.md)

## 1. 重点对象与选择依据

| 产品 | 核心用户任务 | 本机执行路径（公开说明） | 主要对照点 | 证据边界 |
| --- | --- | --- | --- | --- |
| Operit | 综合手机任务 | 原生工具与 Linux 用户空间 | 能力组合、工作区与扩展 | 维护者自述，发行版组合待试 |
| PalmClaw | 原生个人 Agent | 手机 Loop + API 推理 | 配置、工具边界、恢复 | 调用级恢复未独立审计 |
| RikkaHub | 聊天延伸至工作区 | README 列 PRoot Workspace | 获客入口、附件与历史体验 | 须绑定具体发行版 |
| AndCode | 移动开发 | 本机 PRoot；另有远程 OpenCode | 项目、diff、终端、安装 | 本机/远程分别评测 |
| PocketCode | Android IDE | 官网列终端与 BYOK Agent | 开发产物交付 | 官网声明，版本/付费待核 |
| DSHA | 安装即用 harness | Ubuntu + PRoot/proroot | 安装、修复与依赖生态 | 维护者设备证据不可外推 |

首批横评选这六个对象；ClawMobile 为 GUI 专项优先候选，BotDrop 为安装引导补充，QSH36/DroidClaw 为早期高重合观察。相关性不是质量排名，其他产品的证据缺失不等于功能缺失。

## 2. 综合工作台与原生 Agent

### Operit 与 Operit 2：能力覆盖和生态是压力来源

[Operit Android](https://github.com/AAswordman/Operit)公开列出 Workspace、Ubuntu/PRoot、浏览器、设备工具、MCP/Skill/ToolPkg、工作流、本地推理与多模态输入。相比仅运行 harness 的 APK，它更接近完整移动工作台。Helix 值得借鉴的是“对话引用工作区 → 查看修改 → 预览/导出”的连贯流程和扩展入口；不应照搬全部功能。其广泛功能也带来依赖、权限与兼容性维护面，本轮未证明其所有组合均稳定。

[Operit 2](https://github.com/AAswordman/Operit2)是独立的 Rust Core、平台 Host 与 Flutter/CLI 实现，包含跨设备连接和同步方向，公开文档仍标明预览与待验证范围。不能把一代 Android 成熟度直接转移到二代每个平台。它对 Helix 的启示是清晰表达执行节点与平台能力；跨设备协作不进入 Helix 当前范围。**判断：Operit Android 应是最高优先级的综合体验对照，Operit 2 作为架构演进观察。**

### PalmClaw：对“原生可信 Agent”定位最直接的挑战

[PalmClaw](https://github.com/ModalityDance/PalmClaw)明确在 Android 管理 Loop、会话、上下文、记忆、Skills 与工具，推理由用户配置的 API 提供；公开描述 schema、系统权限、确认与 Workspace 边界，以及 MCP、定时和渠道入口。

它证明无需完整 Node/Linux harness 也能形成产品。Helix 应比较配置步骤、原生工具可用性、会话恢复和授权理解度；独立执行 UID、精确绑定与 Job 对账可以作为待验证的区别，不能只看双方有无“安全”文档。PalmClaw 的 Always-on/渠道功能不等于 OEM 长稳保证，也不是 Helix 自动对外发送的理由。**判断：加入首批真机横评，优先级高于继续搜集同质 DSH 壳。**

### RikkaHub：聊天获客到执行工作台的转换路径

[RikkaHub 主仓库](https://github.com/rikkahub/rikkahub)当前 README 已列 PRoot Workspace、多 Provider、MCP、文档输入、消息分支、记忆与 Provider 二维码导入。不能再将它固定归类为纯聊天客户端；但主分支功能是否进入具体商店版仍需版本核对。

它的竞争力在于用户可先因聊天体验留存，再逐步使用工具。Helix 应测试首次 Provider 配置、历史对话查找、文件引用与从聊天切换任务是否同样自然。其通用 Android 控制、调用级证明、执行域隔离与恢复语义本轮未审计。**判断：同时作为直接 Workspace 竞品和 onboarding 参照，不把“未披露”写成“不具备”。**

### PocketCode：以专业工作台而非聊天窗口交付价值

[PocketCode 官网](https://www.pocketcodeapp.com/en)公开定位为 Android IDE，列出代码编辑/LSP、终端、数据库、API 调试、BYOK Agent、市场与工作流。这里是官网功能声明，不是独立功能验收；本轮未确认每项功能的版本、付费边界或完整离线可用性。

它对 Helix 的压力是让开发者直接看到编辑器、预览和可交付项目。Helix 若主打移动开发，仅有 bash 结果和文件列表不一定够；若主打综合效率，则无需追赶数据库管理器和完整 IDE。**判断：用“修改小项目并审阅产物”实测决定开发用户定位，避免无依据扩成全功能 IDE。**

## 3. 垂直开发与设备任务

### AndCode

[AndCode](https://github.com/yuga-hashimoto/and-code) 是移动开发方向的重要直接竞品（09-08 复核主仓库）：原生 Android GUI、手机内 PRoot/Alpine、Git/文件树/diff/终端、OpenCode 稳定支持、Claude Code/Antigravity beta、工具批准、定时任务和 Keystore 凭据均已有公开实现说明。

它的竞争优势：

- 用户不需要先学习 Termux，安装后可直接初始化 Runtime；
- Coding Agent、Git 和 diff review 是完整垂直闭环；
- 能打开真实设备文件，并提供 All-files 路径；
- PRoot、RootFS、CLI 资产与第三方 notice 已形成实际工程经验。

公开安全说明也明确：PRoot 不是完整安全沙箱；full-access/bypass-permissions 模式可让 CLI 不再逐项询问。Helix 的对照应落在实际 Tool 的 scope、批准和恢复语义；当前 E2C 是订阅协议 adapter，不是完整官方 CLI，不应宣传已经拦截任意外部 CLI 内置工具。是否更适合用户需以完整开发任务验证。

### ClawMobile

[ClawMobile](https://github.com/ClawMobile/ClawMobile) 是另一直接参照：Android app-local runtime + 可选 Termux/OpenClaw gateway，能使用本机文件、Android 状态、截图、OCR、Accessibility/ADB 控制、可复用 Skill 和 trusted-agent messaging。官方将其标为 public preview，并提醒 API Key、截图、trace、log 和生成 Skill 可能包含敏感信息。

它的竞争优势：

- 手机控制与可复用 UI Skill 已进入可演示状态；
- 能从 App-local runtime 渐进开启 Termux、Accessibility/ADB 等更强能力；
- “手机是 Runtime，而不是远程屏幕”的叙事清晰。

Helix 不应照搬其 ADB、跨设备 trusted agent、Telegram 或生成 Skill 自动扩权。Helix 的对照重点应是：目标 App allowlist、窗口/节点 token、敏感界面拒绝、实时 Capability 检查、逐调用 Approval、取消恢复与审计，是否能在不牺牲可用性的前提下完成同类任务。

09-08 补核：[AndCode 主仓库](https://github.com/yuga-hashimoto/and-code)还提供远程 OpenCode 连接；其 PRoot 路线按引擎区分 Alpine 与附加 Debian，不能笼统当成一种环境。手机本机模式不要求电脑，但不代表远程模式也没有电脑依赖。支持表中的 Stable/Beta 是维护者标记，不是本轮验收。

[ClawMobile 主仓库](https://github.com/ClawMobile/ClawMobile)另列 iOS App，聚焦 app-local 任务、分享内容、产物与 Skill 交互；Android 仍承担完整 Accessibility/ADB 控制演示。不可将 Android 权限能力平移到 iOS，也不把可下载页面当作所有用户无需邀请码的保证。

## 4. DSH 安装交付路线

本组比较同一上游的不同部署产品，不把同源 fork 计作彼此独立的市场需求证据；以下为 09-08 公开资料快照。

| 项目与一手来源 | 运行位置与公开能力 | 对 Helix 的竞争压力 / 可借鉴项 |
| --- | --- | --- |
| [DSHA](https://github.com/DSH-APP/DSHA) | 本机 Ubuntu + proroot/PRoot，原生管理页与 Web 对话；无线 ADB、插件、备份和诊断。当前 README 的 rc1.4 标准/兼容版约 212.87/289.69 MiB，Android 11+/6+；维护者列出 Android 13 验证及未覆盖边界 | 安装即用、环境修复、插件交付与恢复说明形成产品优势；体积、环境维护和多设备验证是成本。不能只用 README 中旧的“重构较慢”判断维护速度 |
| [woaiys3](https://github.com/woaiys3/deepseek-harness-android-app) | 本机 DSH + Node、源码补丁与前端适配；Root/Shizuku 与用户开启的 Accessibility 互补。README 列 DSH 0.1.0-rc.6、targetSdk 28 | 手机控制和授权引导有直接参考价值；跟随上游补丁、现代 target 迁移需持续验证。版本较旧是观察，不足以证明维护必然失败 |
| [FunnelCakes](https://github.com/FunnelCakes/deepseek-harness-android) | Termux/bionic 本机部署脚本，有原生模块、文件操作与前端补丁；公布 Mate 60 / HarmonyOS 4.2 测试环境，并链接上游讨论 | 专家可定制性与问题上游反馈值得借鉴；脚本小不等于完整环境占用小，讨论提案也不等于上游已合并。该设备证据不是 HarmonyOS NEXT 原生支持 |
| [deepcode-lab](https://github.com/deepcode-lab/deepseek-harness-mobile) | 当前 README 为单 Debian/glibc RootFS，harness 与 bash 同一 PRoot 环境，含目录桥、更新/回滚和后台服务；README 概述约 80 MB APK | 单环境降低集成复杂度；目录桥在 API 30+ 还依赖 All-files，并非任意 SAF provider 都能直映 Linux 路径。共享环境的凭据/命令边界与 Helix 分离执行域不同 |
| [Venompool888](https://github.com/Venompool888/deepseek-harness-mobile) | 原生 Android **远程客户端**，连接用户自托管 Harness；提供步骤、上下文和 ColorOS 流体云展示 | 属于远程客户端的任务呈现参照；不能当成本机 shell。借鉴任务可见性不需要引入 Helix 远程 Worker |

版本、体积、签名与性能表述的详细校正见[证据记录](competitive-evidence.md#2-dsh-材料校正)。Venompool888 为同源远程对照项，不纳入本机性能汇总。

## 5. OpenClaw 变体与早期本机产品

| 项目 / 一手来源 | 已公开能力与状态边界 | 对 Helix 的意义与待查问题 |
| --- | --- | --- |
| [4AIs / 8crsk](https://github.com/8crsk/openclaw-android) | 手机内 Node/OpenClaw gateway、Accessibility、风险确认、审计、动作后 diff；维护者明确 early，首次安装与流式仍在打磨 | 观察“操作后确认发生了什么”的反馈；需查运行时与设备桥权限、首装下载与取消行为，不把 early 等同不可用 |
| [AnyClaw](https://github.com/OpenClawAndroid/openclaw-android-assistant) | 公开将 OpenClaw、Codex 与 Claw Code/OpenClaude 放入同一 Linux APK；后者不能据名称认定官方 Claude Code | 一包安装和多引擎入口值得试用；源码来源、实际二进制、登录支持与许可证须独立核查。作为产品观察，不采纳其代码/资产 |
| [MobileClaw / ChenKuanSun](https://github.com/ChenKuanSun/MobileClaw) | 公开列 Accessibility、云 Provider、本机 LiteRT-LM 推理和 Skills | 观察“离线推理 + 设备动作”；模型内存、热状态、动作成功率需真机证明，不接受“任意 App”普适保证 |
| [RikkaHub Agent Promax](https://github.com/AAAelina/rikkahub-agent) | 独立 fork，列 Workspace、Termux/SSH/Shizuku、Skills 与配置工具面 | 可借鉴能力管理可见性；不能将其功能、授权模型或缺陷归给 RikkaHub 官方。模型可配置范围与用户授权边界需专项审计 |

以上都不足以仅凭 README 证明比 Helix 更强或更弱。AnyClaw、MobileClaw、ClawMobile 和 OpenClaw 官方 App 名称相近，但仓库、签名、运行方式和维护责任不同，选型时必须固定 owner/repo 和安装资产。

### BotDrop：把部署过程做成产品

[BotDrop / zhixianio](https://github.com/zhixianio/botdrop-android)公开以 Termux 基础包装 OpenClaw，提供认证、Agent、安装、渠道四步引导、多 Provider、消息渠道和后台网关重启。竞争重点不是新模型能力，而是让用户不接触终端即可配置现有引擎。

对 Helix 的价值是比较下载进度、配置失败修复和恢复入口；后台自重启不证明任务副作用不重复，也不保证 OEM 长稳。README 的 GPLv3/Termux 来源要求独立许可证审查，本轮只参考交互，不采用代码。暂列第二轮安装体验对照，不因它支持 Telegram/Discord 扩张 Helix 的对外消息范围。

### DroidClaw / QSH36：高重合、但仍是早期测试

[QSH36/DroidClaw](https://github.com/QSH36/DroidClaw)公开组合 Android AI 会话、文件工具、PTY、权限路由、无障碍、Skills/MCP。README 标记 0.1.0 早期测试，并逐项列出 OEM SAF、远端 PTY、设备交互等待真机；MCP 只有 HTTP 最小闭环，stdio/话题生命周期未完成。

它对 Helix 的直接压力在“多能力统一工作台”的产品描述，值得比较 PTY 与能力状态交互。自动测试是维护者报告，不能抬成独立验证；不依据“替我审批/完全访问”等按钮名称判断授权强弱，需审查实际作用域和调用路径。它与 unitedbyai/droidclaw、levilyf/droidclaw 不是同一产品，本报告始终带 owner 区分。

## 6. 共同产品机会与供应链检查

每次试用除功能，还要记录：主 APK 与 Runtime 总下载/安装占用、外部下载域、ABI/libc、签名与升级兼容、故障修复、第三方资产许可证、隐私说明和卸载后残留。没有采集就写“未采集”，不按 commit 数、目录整齐程度或 CI 徽章排名。

最值得吸收的是安装引导、可见的执行环境、文件/修改预览、可复用模板和失败自助修复。Helix 不应为工具数量竞争而默认加入联网 Linux、完整 IDE、跨设备或无限工作流；产品验证建议见[总览](competitive-landscape.md#5-产品应对建议)。
