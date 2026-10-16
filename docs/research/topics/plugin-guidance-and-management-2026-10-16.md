# 插件提示与管理体验审查（2026-10-16）

依据当前源码及竞品一手资料，服务于 [HXA-298](../../development/tasks/HXA-298.md)。起点 `main` / `6bbff42d`，包含 HXA-295～297 未提交工作；研究结论不等于模型效果验收。

## Helix 如何提供插件提示

| 层次 | 来源与进入请求的条件 | 应放内容 |
| --- | --- | --- |
| 工具描述与参数 | 原生插件的 `ToolDescriptor` 或已连接 MCP 元数据，经既有发现/工作集和 Dispatcher 绑定 | 工具作用、输入约束、结果与错误语义；工具名不能代替当前 schema |
| 必要指导 | `HelixPlugin.requiredInstructions`，仅随当前会话已选择且实际就绪的原生 runtime 注入 | 每次操作必须遵循的领域规则，不承担长流程或通用系统提示 |
| Skill 目录 | 同一 SkillRepository 的来源、会话选择和调用资格过滤；source/name/snapshotHash/description | 何时使用、准确读取身份；选择插件不意味着注入全文 |
| Skill 正文 | 模型通过 `skills.read` 按需读取，或本次可信用户显式调用的持久激活 | 任务流程、决策与完成条件；用户参数单独标记 |
| 参考资料 | `skills.read_resource`，沿用同一快照 hash、来源及调用资格 | 详细规则、例子和长参考；内容变化使旧身份失效 |

当前入口：[PluginExecutionContext](../../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/PluginExecutionContext.kt)、[PluginRegistry](../../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/PluginRegistry.kt)、[ChatRequestAssembler](../../../app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt)。工具执行、权限、历史和压缩继续由既有宿主负责。Skill/MCP 文本不授予 capability、屏幕共享或跨会话权限；管理预览不等同执行读取。

Mobile Use 的 `required.md`、短 `SKILL.md` 与 Android 参考资料均随插件打包，运行引擎仍属于 `extensions/mobile-use`。Browser 使用同样模式；Documents 本轮将 Kotlin 中的短指导归入自身 `required.md`；Chat Reply 是零工具指导插件，操作手机依赖当前会话另行选用和授权的 Mobile Use。

## 竞品与采用范围

| 一手来源 | 参考行为 | Helix 取舍 |
| --- | --- | --- |
| [Codex Skills](https://developers.openai.com/codex/skills) | 先提供名称/描述，正文与参考资料按需读取；目录有上下文预算 | 保留现有渐进加载及准确快照，不再造第二套 Skill 系统；统一总预算仍归既有 HXA-293，不宣称已实现 |
| [Claude Code Skills](https://code.claude.com/docs/en/skills) | 描述负责发现；区分用户与模型调用资格，支持按需参考资料 | 继续宿主可信激活、来源隔离和调用资格检查，不将 allowed-tools 当授权 |
| [Claude 插件管理](https://code.claude.com/docs/en/discover-plugins) | 管理入口集中；详情列贡献，配置缺项有直接 Configure 入口；可检查常驻与调用内容成本 | 采用统一分区与可审查内容；不把认证表单藏入技术详情，不照搬桌面安装作用域和任意 hooks |
| [OpenAI 插件](https://developers.openai.com/codex/plugins) | 插件打包 Skill/MCP，能力与连接设置共同呈现 | 保持插件容器，连接作为组件；Helix 会话选择与权限保持独立，安装不自动选用或登录 |
| [OpenCode Skills](https://opencode.ai/docs/skills/) | 工具发现目录后按需读取 Skill，独立权限检查 | 方向一致；Android 上不用桌面路径推断安装或执行权限 |

没有复制参考项目源码。官网页面会演进；本表描述本轮读取的行为，不承诺所有版本/客户端一致。

## 本轮问题与处理

1. **展示与真实加载不符**：旧提示称技能随插件自动加载，实际上只有目录和必需指导常驻。改为说明按需正文及明确用户调用；内置原生记录不含导入 Skill 行，不能用该行数显示“0 技能”。
2. **配置入口分裂**：MCP 认证与连接藏在“连接与诊断”，Mobile Use 有单独按钮。所有插件统一“配置／提示与工具／详情”；无额外配置的插件明确说明，专属表单仍由自身实现负责。
3. **使用对象丢失**：原卡片只有 Mobile Use 传插件 ID，其余传 null 导致展示所有插件。统一传当前安装 ID，不自动勾选，不修改默认集合。
4. **内容审查缺口**：管理页只展示短 Skill 和工具描述，看不到实际必要指导与原生参考资料。本轮提供只读展开，沿用已打包内容，不读取凭据或新增执行通路。
5. **提示维护问题**：Mobile Use 必要指导去重，保留新观察、附带观察、提交语义、未知副作用不重放、截图凭据及有条件返回原会话；长流程仍留参考资料。Documents 统一资源化并明确不可混用文档版本。Chat Reply 明确能力依赖、禁止发送与观察核验；不再暗示 Skill 能禁止宿主正常历史/审计记录。
6. **卸载交互**：移除会删除本地连接凭据，需显示目标和后果并确认；取消不改变安装。内置插件不显示虚假的独立卸载/更新操作。
7. **模拟器发现的并发死锁**：卸载清理持有插件 mutation lock 等待 Skill snapshot lock；页面刷新反向持锁读取原生贡献。当前线程转储确认互相等待，修复为插件与 Skill 关联元数据共享同一门闩，保留原子发布/隐私清理保护；这不是工具执行的全局串行锁。补充 JVM 并发与 Android 生产接线回归。

## 验证及后续

生产原生内容与模型读取仍共用既有契约，管理预览不改变注册、默认集合、会话选择或权限。覆盖实际 Room 安装/删除、配置入口、精确选择、状态恢复、必要指导/资源预览及 Mobile Use 授权回归；主机与模拟器结果以 HXA-298 交付记录为准。

本轮不进行真实模型对照，不能据文本缩短宣布 token、延迟或完成率改善。统一插件贡献的后续重构应迁移本轮界面行为与回归，不保留一套旧 UI 兼容分支；上下文预算、跨插件依赖自动解析和未来执行域不在本轮实现范围。

## 后续授权的诊断切片（HXA-299）

Owner 随后明确实施前述建议并允许联网、日常模拟器与真实模型。[Claude Code 的上下文说明](https://code.claude.com/docs/en/how-claude-code-works)用 `/context` 解释空间使用；[Skills 文档](https://code.claude.com/docs/en/skills)区分发现描述与按需正文。[Codex Skills](https://developers.openai.com/codex/skills)同样采用渐进披露。Helix 本次借鉴可解释成本和按需载入原则，不复制窗口百分比或桌面 Runtime 生命周期。

- **现在做**：既有 PromptSnapshot 的 UTF-8 字节、估算文本 token 与装配省略原因进入同一模型调用/审计记录；只解析一次 provider，不存额外正文。插件注册、就绪与指纹计算采用有界单进程计数，随现有诊断导出，不新增普通用户提示。
- **测量后做**：模拟器确认未选插件也会执行就绪探测。提前检查会话选择，减少无关运行时调用；不缓存就绪/权限，不改变已选插件的实时校验，也不声称全部原生插件已懒初始化。
- **实测修复**：多段 `ask-user.md` 被直接用作禁止控制字符的工具描述，导致真实工作流在请求装配时失败。只在这个内置模板进入工具描述时合并段落，保留既有严格 schema 与全部指导，并覆盖模型 schema/展示装配路径。
- **留给重构**：env.plugins 内部的目录/必要指导/显式正文进一步分项、统一窗口预算及相关性选择归 286/293；通用依赖解析归 286；真实资源延迟开启/关闭归 288。本次数据和回归作为输入，内部 DTO 不要求兼容保留。

真实样本的本地解析、匿名联网 MCP、模拟器导入与真实 Qwen 工作流分别验收，结果见[HXA-299 记录](../../evidence/development/plugin-diagnostics-and-samples-2026-10-16.md)。不将一次工作流通过或微基准耗时外推为整体完成率改善。

## 包内账号服务与脚本运行条件

2026-10-16 Owner 追加询问。以下是当前源码与已整理原样本的只读分析，不新增账号调用、安装依赖、执行第三方脚本或自动启动重构。HXA-299 已有验收范围没有未完成步骤；真实包业务运行是另一个交付范围。Owner 补充中途在切换网络，原失败与复测保留，不据此继续扩展网络缺陷排查。

### 当前底座与真实缺口

远程 MCP 已有连接和 OAuth 凭据协调路径。`PluginPackage.kt` 的 `endpoint` 对 HTTP 配置保留端点，剥离源凭据并提示重新配置；对 command/stdio 返回 `STDIO_REQUIRES_ANDROID_RUNTIME`，不注册可运行端点。`SkillTools` 提供正文和资源读取，不提供包内脚本的安装/执行承诺。`McpStdioJobBridge` 已有固定 argv、有界 Job、取消与对账底座，但当前引用仍在专门设备测试中，没有接成导入插件的通用 stdio 服务生命周期。

当前 [Runtime 决策](../../adr/runtime/001-execution-domains.md)已是 Developer 单 APK 内私有进程、共享 UID 和网络能力；`ProotToolModule` 将 `code.linux.run` 接到既有 Runtime。不要沿用 [HXA-128 历史评估](../../evidence/development/connector-stdio-portability.md)的独立 APK/无网结论，也不能将私有进程称作凭据安全隔离。Standard 不含 PRoot，可复用远程 MCP；默认隔离 QuickJS 不是 Node/Python 的通用替代品。

| 样本/路径 | 已确认内容 | 真正运行还需要什么 |
| --- | --- | --- |
| WorkBuddy GitHub、可灵 | 两个市场包带 MCP/Skill，本轮原 ZIP 中没有 `scripts/` 文件；未携带账号登录态 | 确认可供 Helix 客户端使用的服务地址与认证契约，在 Helix 单独连接账号，核验工具发现/参数/结果及 Skill 指导。配置没有认证字段不证明服务匿名可用；若端点依赖 WorkBuddy 私有代理/账号注入，须厂商提供开放接入或采用官方公开服务适配，不能迁移源登录态冒充支持 |
| QwenWork 钉钉 | Skill 声明 `dws` 二进制依赖，样本不是完整 CLI 安装资产 | 可核验来源、固定版本、许可证、ARM64/libc 适配、依赖与登录方式；先检查是否有可复用官方远程接口，再选择 CLI 适配 |
| QwenWork 企业微信 | Skill 保留 MuleRun 宿主 `wecom` 入口契约；另带 `build_docx.py` | 业务入口需独立实现可授权的服务/CLI 适配；本地 DOCX 脚本可单独评估，Python 模块 `docx` 等依赖及产物验证不能由 Skill 导入替代，也不需要为本地制文先接企业账号 |
| QwenWork mcp-installer | 含 Shell/Python 安装、改写、运行辅助脚本；Python 引用了 `fastmcp.cli`、`dotenv` | 明确源宿主配置、路径和依赖，逐项判断用途；已有 MCP 发现/连接功能应复用，不能原样运行安装器去改写 Helix 内部配置或绕过安装事务 |
| 公开 Playwright stdio 配置 | 当前仅识别并报告运行时缺口 | 固定 Node/npm 依赖、浏览器资产及平台兼容验证，再补 stdio 双向会话、进程 owner、取消/退出/重连对账。原有一次 Job 的桥不证明任意常驻 MCP 服务可用 |

本轮只读取 ZIP 及 Python import 语法，没有执行包内代码或探测业务服务。导入模块名不代表依赖已安装，也不据此断言具体包版本兼容。

### 推荐实现顺序

1. **先接一项远程业务 MCP**：选定账号、服务、最小测试对象和成功条件；复用现有登录/连接、会话选择、Dispatcher 与审计。验证握手、工具 schema、只读任务、失效/拒绝和恢复；写入或付费生成另给明确测试范围。官方服务支持现有协议时可能无需新执行架构；专有认证、额外 header 或宿主工具名差异按实际证据补适配。
2. **再跑一个纯本地脚本**：固定 Skill 快照、精确入口、解释器/依赖版本、工作目录和输入输出；经正常工具授权进入现有 Runtime，校验退出、超时/取消、产物及崩溃后的结果归属。依赖按插件环境管理，避免互相覆盖；安装脚本不是插件启用的隐含动作。不新增绕过 Dispatcher 的执行捷径，也不把第三方脚本放主进程。
3. **最后推广 CLI/常驻 stdio**：补安装/版本/移除、只读就绪检查、登录/登出及凭据 owner；运行进程绑定原身份，网络切换不重放未知副作用。PRoot 共享 UID，第三方可信 CLI 与对抗性不可信代码必须区分；若目标要求凭据隔离，先设计真实隔离机制或使用远程受控服务，不能仅靠环境变量清理或目录划分作保证。

需要 Owner 选择具体业务与测试账号、测试对象及可接受副作用；凭据在 Helix 配置，不写入样本或聊天。CLI 还需可分发资产/源码、依赖与许可证资料；若厂商未提供可独立使用的入口，这是外部依赖，不能靠重构补齐。

此顺序与 [WorkBuddy 官方规范](https://open.workbuddy.cn/docs/connector)对远程 MCP 优先、CLI 显式声明运行时/认证生命周期的划分一致；[Agent Skills 规范](https://agentskills.io/specification)也明确脚本语言支持取决于宿主。[MCP 授权规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)要求服务身份/授权绑定；包内认证文本不能直接成为当前会话授权。参考行为须服从 Helix Android 与当前权限边界，不照搬桌面安装命令。

通用组件/依赖声明接续 HXA-286，执行与长期资源接续 HXA-288；完整上下文预算继续 HXA-293。具体可用 HTTP 服务或纯本地脚本可先做有界纵切，无需等待整轮重构，但本次分析不将上述扩展标成已实施或已授权账号验收。

## 导入体验优化（HXA-300）

Owner 后续要求实施导入评审。当前源码确认：导入成功的“使用”仍传空 ID，更新差异计算仅被测试调用，本地预览直接展示完整 hash/URL，错误只显示笼统失败。同名不同内容经普通入口可能成为另一条安装记录；这不能通过自动按名称合并解决。

[Codex 插件说明](https://learn.chatgpt.com/docs/plugins)将详情、安装与连接分步处理；[Claude Code 管理说明](https://code.claude.com/docs/en/discover-plugins)展示组件及安装后的可用状态，并区分发现目录与安装；[WorkBuddy 连接器规范](https://open.workbuddy.cn/docs/connector)区分 MCP 认证和 CLI 生命周期。采用这些信息组织原则，不引入桌面自动依赖安装、项目级共享授权或无实际支持的脚本入口。

[HXA-300](../../development/tasks/HXA-300.md) 接通本地/市场共用预览、固定错误分类、安装结果精确导航和实际更新摘要。同名包展示明确候选与独立安装选择；端点完整绑定相同才沿用已有连接。兼容性摘要是解析事实，不是账号可用或任务成功证明。测试应覆盖原样本、取消/陈旧预览、认证绑定变化与正常/失败后导航；结果以任务验收为准。

这批调整不依赖完整重构。HXA-286/288 仍承接通用依赖与实际运行资源，不因导入界面改善而宣称已支持 CLI/常驻 stdio。没有复制竞品代码。
