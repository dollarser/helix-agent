# 竞品研究：证据记录与材料校正

整理日期：2026-09-08。本文保留核验批次、来源与原材料取舍，避免把历史纠错混入决策主报告。它不是所有来源均在同一天全量重测的声明。

[返回竞品总览](competitive-landscape.md) · [横评方法](competitive-evaluation.md)

## 1. 核验批次与可追溯性

| 批次 | 来源范围 | 实际做了什么 | 没有做什么 |
| --- | --- | --- | --- |
| 09-03 历史快照 | 主流客户端、CLI、系统与 HarmonyOS 生态 | 原报告基于官方页面归纳；本轮保留来源与日期 | 不宣称 09-08 仍全量可用 |
| 09-08 前两轮 | DSH、Operit/PalmClaw/RikkaHub/PocketCode、远程/GUI/离线替代品、OEM 和旧报告 | 浏览官网、维护者仓库与部分 Releases，校正部署/版本混淆 | 未安装竞品 APK、未实测成功率/性能、未验签 |
| 09-08 本轮重组 | BotDrop、QSH36/DroidClaw、unitedbyai/droidclaw、DroidMind、Mobile Next；复核 AndCode、ClawMobile、AppFunctions | 检索候选并打开一手来源，对照运行路径和公开限制 | 未审计全源码、未调用收费服务、未验证用户规模 |
| Helix 对照 | 当前状态、相关架构与完成记录入口 | 读取当前工作区文档，标记发布/真机边界 | 未把本次文档检查视为重新验收代码 |

新条目的一手链接就在对应产品卡；历史来源索引见 §4。以上 GitHub 多为可变主分支 URL，尚未全面保存 commit/permalink，故仅属于文档级证据，不能复现为特定 APK 结论。正式横评前补齐 tag、commit、资产 SHA-256 与签名身份。

本轮补充检索围绕“Android on-device AI agent / BotDrop / DroidClaw / DroidMind”和移动 MCP 设备桥；不是全球穷尽搜索。保留执行路线不同或明显影响 Helix 用户任务的对象，排除无精确身份和重复包装的简单堆数。

## 2. DSH 材料校正

**对补充材料的纠偏：**

- **发布快照混用了不同版本。** [deepcode-lab Releases](https://github.com/deepcode-lab/deepseek-harness-mobile/releases) 的 v0.2.0-rc.1 明确 arm64-only；x86_64 出现在 v0.1.6-rc.1，不能拼成“最新版同时支持”。页面列 rc.1 于 8 月 18 日发布，不能写成 9 月 8 日前两天连续发版。75 MB 与 README 的约 80 MB 应分别绑定资产和单位；本轮未下载 APK 验签。原材料的 563 次下载与 star/fork 数不继续当实时事实，也不能换算活跃用户。
- **无障碍仍需用户开启。** “免 Root/Shizuku”不等于“免授权”；项目自身开启步骤与 [Android Accessibility 文档](https://developer.android.com/guide/topics/ui/accessibility/service)均要求系统启用。“唯一能控制手机”也与 DSHA 和本报告其他产品相矛盾。
- **缺少 bwrap 不等于没有内核隔离。** [Android Application Sandbox](https://source.android.com/docs/security/app-sandbox)仍以 UID/SELinux 等约束应用。应审查生成命令与宿主是否同 UID、哪些目录/凭据可达、是否有高权限桥；不能将所有产品统一判作“完全无安全边界”。PRoot 自身也不是安全沙箱。
- **容器兼容性是有条件的优势。** glibc 环境有利于 Linux 依赖，但不能保证所有插件、系统调用或后台任务可用。Helix 的 Alpine 为 musl 路线，不等同 Debian/Ubuntu 的 glibc 插件兼容性。proroot 的维护者性能数字不能外推为 Helix 或所有设备收益，零 ptrace 不等于零总开销。
- **CI、哈希、签名是不同证据。** SHA-256 校验完整性；若摘要和文件同源被替换，不能独立证明可信来源。CI 发布也不自动证明可复现构建、签名身份可信或设备安全。“CI 最严”“架构最干净”“四家都不安全”缺少统一测评，不采纳排名；私钥不进源码仓库是正常做法，不能作为负面证据。
- **其他绝对推论不成立。** 只监听 loopback 会缩小网络暴露，但仍需本地鉴权；远程客户端把执行风险移到服务器，不保证凭据与手机缓存全不落地，也不是唯一隔离方案。FUSE 的 660 不能脱离 Android 用户组、存储权限与 SELinux 推导“所有 App 可读”。

因此，保留原材料的运行时分类和产品观察，去掉未经共同测试的质量排序、用户量推断和“容器必胜”结论。完整 glibc harness 更适合现成 Linux 插件需求；原生分层工作台更适合手机能力组合。两者的胜负取决于目标任务的成功率与总成本。

## 3. 用户旧报告的取舍

输入为用户《手机端 AI Agent App 竞品分析报告》（数据截止自述 2026-09-02）。其可验证产品内容已按主题迁入直接竞品、替代方案、系统生态；商业假设归商业化文档。

| 原报告观点 | 处理 | 合并后的口径 |
| --- | --- | --- |
| Android 优先、BYOK、本机文件/代码/网页组合 | 采纳，与当前定位一致 | 强调具体任务交付，不承诺完整 Claude Code/Tasker 三合一兼容 |
| 部署门槛、任务可靠性、数据去向影响采用 | 采纳为用户研究维度 | 记录首次配置、自助恢复、产物正确性和出网理解度 |
| 自动化用户是潜在客户 | 采纳但改写 | 同时是替代品用户；先收集任务和付费意愿，不预设必然迁移 |
| 预装/OEM 合作形成进入壁垒 | 采纳但改写 | 对系统入口竞争不利，不等于没有细分机会；也不假定厂商永远不做开发工具 |
| Auto.js 63% 外流、行业成功率 70%～85%、Operit 4.8/5 或热度第一 | 不采纳数字/排名 | 缺样本、方法、可比版本及可靠原始证据；不用于市场规模和销售论证 |
| Hamibot 设备天费及“3 台 30 天 150 元” | 不采纳旧报价 | 原文按 1 元/台/天只算出 90 元，150 元需额外计费项解释；不把投诉或旧套餐作为定价锚点 |
| 所有 BYOK 客户端没有执行能力 | 拒绝泛化 | RikkaHub 当前主仓库已列 Workspace；每个产品/版本分别核验 |
| 免费开源没有商业支持、个人商业模式只有买断/分成 | 不采纳绝对结论 | 许可证、收费、服务承诺相互独立；商业模式须用支持成本和用户付费验证 |
| 云端大厂都是重资产、BYOK 边际成本近零 | 改写 | BYOK 可减少代付推理费用，但适配、发行、支持、测试与退款仍有成本 |
| GitHub/社区/视频即可不买量获客 | 改为渠道实验 | 衡量有效安装、首个任务与留存，不能用 star 或点赞证明免费获客成立 |
| 技能市场是唯一网络效应、必须一键迁移旧脚本 | 不采纳 | 先维护方模板；网络效应与脚本兼容需要独立验证，不能突破既有路线 |
| ChatGPT 的 Messages 集成、Siri 指定版本已经全面可用 | 未采纳新增断言 | 原文缺可追溯官方依据；保留主报告已有协议边界，不从传闻推出跨 App 权限或发布时间 |
| 所有效果可回滚、主要风险只在 UI 自动化 | 不采纳 | 文件覆盖、代码执行、凭据/出网也有风险；可恢复操作、补偿操作与不可撤回操作需分清 |

商业启示与渠道验证已归入[市场、用户与商业化](market-users-and-commercialization.md)，避免在竞品报告中再维护另一份价格和路线图。原文的内部工具/凭据缺失说明不属于产品证据，未复制到项目文档。

### 更早聊天材料的校正

| 原判断 | 2026-09-01 校正 |
| --- | --- |
| Chatbox 严格说只是 API 客户端 | 仍以多模型客户端为核心，但官网已经公开文件、联网、知识库和 MCP 能力。更准确的说法是“增强型 AI 客户端”，而不是纯聊天壳；仍未见其提供通用 Android 文件/shell/Accessibility 执行域的公开证据。 |
| Cherry Studio 主要桌面、移动端弱或只能 PWA | 已有独立的官方 React Native 移动仓库和 iOS/Android 构建，官方 release 到 `v0.1.7`。它仍是较早期移动产品，不能把桌面端完整 MCP/Agent 能力自动算到移动端。 |
| LobeHub 只有 iOS App + PWA | 官方下载页已列出 iOS App Store 与 Android Google Play；产品也已经从聊天框架演进为 Agent 工作台，并支持异构本地/远程运行时路由。 |
| Termux + CLI 是手机上唯一能真正干活的 Agent | 不成立。AndCode、ClawMobile、Gemini Android、Claude Android 已提供不同程度的本机任务执行。Termux 仍是强大的高级用户路径，但多数桌面 CLI 没有把 Android/Termux 列入正式支持平台。 |
| Claude Code、Gemini CLI、Codex CLI、Aider、goose 都可“装上即用” | 需要逐项区分。Codex CLI 官方页面列 macOS/Linux/Windows；Claude Code列 macOS/Windows及 Ubuntu/Debian/Alpine；Gemini CLI列 macOS/Linux/Windows；goose 官方仓库明确 Termux 尚未正式支持。Aider 的 Python 安装路径相对可移植，但官方安装页也没有承诺 Android。 |

这些是历史纠错，不是重新查询后的精确版本清单。所有旧版本/支持平台需在选型时重新核实。

## 4. 既有主要来源

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
- A2A：[A2A Protocol v1.0 specification](https://a2a-protocol.org/latest/specification/)、[v1.0 变化](https://a2a-protocol.org/latest/whats-new-v1/)、[官方 Java SDK](https://github.com/a2aproject/a2a-java)
- HarmonyOS Agent 生态：[HarmonyOS 7 与 HMAF 2.0](https://www.huawei.com/cn/news/2026/6/harmonyos7-hdc)、[HarmonyOS AI 能力与端/云 A2A](https://developer.huawei.com/consumer/cn/harmonyos-ai)、[Intents Kit](https://developer.huawei.com/consumer/cn/sdk/intents-kit)、[HarmonyOS 意图框架](https://developer.huawei.com/consumer/cn/huawei-hag/)、[HMAF 与小艺开放平台开发模式](https://developer.huawei.com/consumer/cn/activity/incentive/ai/)、[小艺开放平台精选案例](https://developer.huawei.com/consumer/cn/celia?ha_source=InfoQ&ha_sourceId=70000011)、[HarmonyOS 文档中心](https://developer.huawei.com/consumer/cn/doc/?catalogVersion=V2)、[元服务](https://developer.huawei.com/consumer/cn/fa)
- 鸿蒙代表性智能体：[小艺 App 智能体](https://consumer.huawei.com/cn/support/content/zh-cn16076199/)、[小艺深度解题](https://consumer.huawei.com/cn/support/content/zh-cn16051425/)、[客服小艺](https://consumer.huawei.com/cn/support/content/zh-cn16101343/)

本报告不替代各产品的许可证、隐私政策、商店地区可用性和服务条款审查。若某项能力进入 Helix 依赖、分发或登录方案，仍须按 [开源依赖与参考仓库](../references/open-source-projects.md) 和 [ADR 约定](../adr/README.md)单独决策。

## 5. 维护规则

每条新增事实记录来源、读取日期、所属版本/渠道、推理/Loop/工具执行位置、限制与验证等级。变更事实更新所属主题文件；改变 Helix 产品判断才更新总览。版本资产及性能结果不复制到多个正文维护。每季度或上游重大改版时人工复核；本文件不创建自动监控任务。

## 6. 2026-09-08 文档收口审核

HXA-156 将上述分主题研究及目录入口纳入 Git，核对研究/实现/横评边界、来源批次、内部链接和 Helix 当前状态。此批属于整理审核，不是重新浏览全部一手来源，也没有新增竞品 APK 成绩；动态版本与价格仍按各段日期理解。后续采用某项能力时重新核验对应原始来源。
