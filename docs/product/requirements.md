# Helix 产品需求（Android 单机版）

文档状态：Baseline 1.3
基线日期：2026-08-31
目标读者：产品负责人、Android 开发者、测试人员、编码 Agent

## 1. 产品定义

Helix 是运行在 Android 手机上的个人执行型 Agent。用户用文字、语音或分享内容描述目标，Helix 在手机本地完成规划、调用受控工具、按需生成并执行代码、展示过程和结果。

“本地”在本文档中的准确含义：

- Agent Loop、任务状态、权限策略、审批、审计、Workspace 和代码执行器在手机上。
- 大语言模型第一阶段允许通过网络 API 调用，不承诺模型权重本地化。
- 不依赖电脑、云端 Worker 或自建服务器完成基础任务。

## 2. 当前范围

### 2.1 必须实现

1. 多会话对话与流式响应。
2. 用户配置一个或多个模型 Provider：OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、常用兼容厂商或 SGLang/Ollama 自建服务。
3. 有限状态 Agent Loop：模型调用、工具调用、结果回填、继续推理、完成或失败；支持 Chat、Plan、Act、Goal。
4. 强类型 Tool Registry 和 JSON Schema 参数校验。
5. 风险分级、审批、取消和审计。
6. 应用私有 Workspace、SAF 授权目录和用户主动开启的 All files access。
7. 内置文件管理器，以及 `read`、`write`、`edit`、检索、复制、移动、压缩和安全删除工具。
8. 轻量 JavaScript 生成与本地执行。
9. HTTP 只读获取工具，带 URL、大小、超时和重定向约束。
10. 内置 WebView 浏览器和受控页面读取、点击、输入、滚动、截图、下载工具。
11. Android 原生 Intent、分享导入、通知读取和日历草稿能力。
12. MCP Client：Streamable HTTP 和可选 PRoot stdio transport。
13. Agent Skills：发现、按需加载、用户导入、启停和受控脚本执行。
14. 用户主动开启的 Accessibility 自动化与可选 Root 高级能力。
15. 任务中断后的持久化恢复。
16. 可选 PRoot + Alpine Linux 开发者模式。
17. 确定性 Tool 编排：单一安全管线、有界读并发、写屏障、按模型 call sequence 回填、取消/恢复可回放和分阶段审计。
18. 会话纯文本与图片附件：一次性导入到 Workspace、可预览/移除、消息绑定、图片 vision、精确出网披露以及系统分享草稿；选择或分享不自动发送。

### 2.2 明确不实现

- 远程 Worker、云端沙箱或桌面配对。
- HarmonyOS 客户端。
- 当前路线不实现 ADB、Shizuku 和无线调试桥接；仅作为未排期未来研究候选，不构成当前功能或营销承诺。
- 自动付款、转账、下单。
- 未经确认自动发送消息、邮件或公开内容。
- 从模型响应中下载并加载 DEX/APK/native library。
- 手机端完整 Android SDK/Gradle 构建环境。
- 多 Agent 群体/递归编排、Agent 间任意 peer 通信；后期只允许 HXA-105/ADR-0009 评估 Advanced 的深度 1 只读委托。
- 云端任务舰队、远程 diff apply、可执行 Workflow/Policy DSL、Agent 自修改或自行挂载插件。
- MCP Server 托管。
- Skill 在线市场、自动下载依赖或未经检查的远程 Skill 安装。
- 提取浏览器 Cookie、复用其他 App token 或调用模型厂商未公开的订阅接口。

### 2.3 首个产品闭环与状态边界

本节定义产品闭环，不复制当前 HXA、已完成范围或实现快照；这些易变化事实只在[实施状态](../development/status.md)维护。

- 第一个可感知的本地执行闭环是“选择或导入目录 → Agent 读取并提出变更 → 用户查看任务摘要/diff → 执行 → 展示产物与失败状态”。它用于验证 Helix 相比纯 API 客户端的核心价值，但不等于 Alpha 或正式版完成。
- QuickJS、Browser、MCP/Skill、PRoot/CLI、Accessibility、Root 按路线依赖和风险逐级接入。未完成的高权限能力不得出现在默认营销承诺、首屏引导或可调用工具表中。
- 对外产品定位统一为“面向开发者与效率用户、Provider-neutral、能力优先的 Android 本机 AI 执行工作台”；市场与商业假设见[市场、用户与商业化分析](market-users-and-commercialization.md)。HarmonyOS 和系统 Agent 生态只作为竞品参照，不进入当前实现范围。

### 2.4 未排期未来能力候选

下列需求只记录方向与 Android 可行性边界，不分配当前 HXA，不得显示为已实现或确定交付：

| ID | 候选需求 | 当前可验收口径 |
| --- | --- | --- |
| FUT-AUTO-001 | Tasker 互操作 | 研究官方插件 action/event/state、命名 Task 调用和结果回传；不承诺导入任意 Tasker profile 后语义不变 |
| FUT-AUTO-002 | Auto.js/AutoJs6 脚本兼容 | 接受任意来源脚本导入并生成引擎/API/权限/模块兼容报告；按公开兼容矩阵逐步执行，不承诺任意脚本零差异 |
| FUT-AUTO-003 | 独立脚本 Runtime | 脚本在独立应用/UID 中执行，经有界 IPC 调用 Helix；不复用主进程 QuickJS 的隔离边界，不复制许可证不兼容代码 |
| FUT-SYS-001 | Shizuku 能力 Provider | 用户显式启动服务；处理 unavailable/denied/granted/lost、Binder death、撤销和 OEM 差异；Shizuku 状态不等于 ToolCall 批准 |
| FUT-SYS-002 | 无线 ADB | Android 11+ 由用户启用开发者选项并配对；研究本机 client、密钥、前台生命周期与供应链；断连不盲目重放 |
| FUT-MEDIA-001 | PDF、PPT/PPTX、DOC/DOCX 对话读取 | 当前仅识别为未支持附件；未来先比较平台/第三方解析、许可证、APK/内存、恶意文档和中文/表格质量，不承诺 OCR 或格式保真 |
| FUT-MEDIA-002 | 视频附件理解 | 当前仅识别为未支持附件；未来分别评估端上有界抽帧与 Provider 原生上传，覆盖时长/帧数/像素/音轨/成本/数据保留和 Provider 可移植性 |

“兼容任意脚本”在需求层表示任意输入可导入、可诊断并得到明确结果，不表示未经兼容矩阵验证即可成功执行。Shizuku/ADB 只有在未来单独形成路线、ADR、许可证/供应链审查和真机矩阵后才可进入实现范围。

## 3. 目标用户

### P1：开发者与高级用户

希望在手机上处理项目、文件、网页和数据，愿意检查 Agent 生成的代码，并启用 Linux Runtime、Git、Shell、All-files、Accessibility 或 Root 等高级能力；接受额外下载、功耗、兼容性提示，并对主动开启和确认后的设备操作承担最终责任。

### P2：Android 效率与自动化用户

希望用自然语言完成文件整理、信息提取、系统操作和可复用任务，不愿继续手写或维护复杂 Tasker/Auto.js 类脚本。

### P3：普通效率用户

希望整理文件、摘要通知、生成日历草稿和转换数据，不愿接触终端。该群体只有在能力安装、错误恢复和首次任务路径足够成熟后才作为主要增长对象。

### 3.1 两级安全配置

目标用户不直接等于安全配置。Helix 采用 [ADR-0012](../adr/0012-capability-first-advanced-grants.md)定义的两级运行时边界：

| 配置 | 默认与可用范围 | 产品要求 |
| --- | --- | --- |
| `STANDARD` | 所有安装默认；Google Play、国内 Android 应用商店和官网的完整产品形态 | 完整核心任务能力、低配置默认；可按目标渠道保留 All-files、Accessibility、解释脚本等允许能力；高敏数据和系统能力按需启用，不因抽象安全偏好预先裁剪 |
| `ADVANCED` | 在渠道政策和 artifact 能力允许时显式开启；当前 developer 变体承载最完整实现 | 分能力启用；可开放 Root、离线 PRoot/CLI、Agent UI 自动化、精确 LAN origin 和开发者控制台；每项有 scope、状态、停止和撤销 |

`consumer/developer` 是当前编译期机制，`STANDARD/ADVANCED` 是运行时 Policy/体验边界，Google Play/国内商店/官网是分发渠道，三者不能合并为一个开关。所有主应用首次启动为 `STANDARD`；切换 `ADVANCED` 不自动申请系统权限、不安装 Runtime、不请求 Root、不连接新端点。

Advanced 扩大的是可选能力和可配置范围，不是绕过安全内核。schema/Policy/Approval、Secret 隔离、进程/UID 隔离、敏感界面拒绝、审计、取消和变更后验证在两级中完全一致。Advanced 可以持久保存 Capability 状态、Trusted Workspace scope、风险不高于 L1 的有界工具规则和精确高敏出网规则；一次界面操作也可以批准已完整披露的有限 ToolCall 列表，但列表中每个调用仍使用独立的一次性 proof。

产品优先级以能力和任务完成率为主，而不是以审批数量或安全功能数量为主。Advanced 用户负责选择 Provider、开启能力、限定 scope、备份重要数据并承担自己确认的操作后果；Helix 负责不伪造结果、不隐瞒执行目标与数据去向、不在用户关闭能力后继续执行，也不允许模型替用户开启权限或产生批准。

分发遵循 [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)：用户获取的是同一个 Helix 产品，普通用户直接使用完整 Standard，高级用户在同一安装内进入 Advanced，不因运行配置更换 applicationId。当前 consumer/developer 只是工程打包机制；最终 channel/flavor/applicationId 由 HXA-122 结合签名和升级证据决定。

## 4. 核心用户旅程

### UJ-01：文件分析

1. 用户通过系统文件选择器导入 CSV。
2. Helix 把文件复制到会话 Workspace，并记录来源类别、净化显示名和哈希；原始 `content://` URI 只在导入适配层短暂使用，不进入模型、审计或诊断。
3. 用户在发送前看到附件名称、类型、大小和目标 Provider；选择文件本身不自动出网，发送时按内容类别执行出网门控。
4. Agent 获得带来源和哈希的有界文本上下文；完整内容通过内置 `read(offset,maxBytes)` 分块，需要计算时生成 JavaScript。
5. Helix 展示代码、输入文件、限制和预期产物。
6. 用户批准后，隔离执行器运行代码。
7. Agent 根据真实执行结果继续推理；如需生成文件，再单独调用 Workspace 写入工具。
8. 用户审批写入并显式导出结果。

### UJ-02：通知摘要

1. 用户主动开启 Notification Listener 权限。
2. Helix 读取限定时间范围和限定应用的通知快照。
3. 内容进入模型前显示隐私提示，并允许排除应用。
4. Agent 返回摘要；默认不回复任何消息。

### UJ-03：创建日历草稿

1. Agent 从文本或图片 OCR 结果中提取时间。
2. 调用 `calendar.prepare_event`，只生成结构化草稿。
3. UI 显示标题、时间、时区、地点和备注。
4. 用户确认后由 Android Calendar Intent 或 Calendar Provider 写入。
5. 工具重新读取或检查返回结果，禁止仅根据“调用成功”宣称完成。

### UJ-04：Linux 开发者模式

1. 用户在设置中阅读风险说明并启用开发者变体能力。
2. 用户安装与 Helix 同签名的独立 PRoot Runtime APK，并点击一次“验证 Runtime”完成零 Job 的签名/协议/ABI 握手；Runtime 安装固定版本、带哈希的 Alpine RootFS。
3. Agent 提议一条命令及其目录、超时、环境变量和输入快照。
4. 用户批准后，主 App 按需冷启动/绑定独立 UID 的 PRoot Runtime，并把 Workspace 输入副本传入；用户不需要预先打开或保持 Runtime 应用在前台。
5. 结果被截断、结构化并写入审计日志；空闲后解绑，Runtime 进程可以被系统回收。
6. 若执行中断或 Runtime 被强制停止，Helix 显示中断原因和“修复 Runtime”入口，不自动重复执行命令。

### UJ-05：网页研究与受控交互

1. 用户在 Helix 内置浏览器打开网页。
2. Agent 获取当前页面的有界语义 snapshot；网页内容被标记为不可信。
3. Agent 生成研究计划并引用实际 URL；页面跳转由 `browser.navigate` 完成。
4. 点击、输入、下载等动作展示目标元素、origin 和影响并按风险审批。
5. 页面发生导航或结构变化后，旧 node token 失效，必须重新 snapshot。

### UJ-06：跨 App 自动化

1. 用户在权限中心主动开启 Accessibility，并选择允许的目标 App。
2. Helix 创建限时 Automation Session，显示常驻停止入口。
3. Agent 读取当前 UI tree，按节点而非盲坐标提出动作。
4. 每个高影响动作经审批，包名或窗口变化时暂停确认。
5. 支付、认证、系统授权和 Root 管理界面始终拒绝自动操作。

### UJ-07：MCP 与 Skill

1. 用户添加 MCP Server，连接测试并查看服务器声明的 tools/resources/prompts。
2. 用户逐项启用需要的工具；动态工具以 `mcp.<server>.<tool>` 注册。
3. 用户从本地目录或 zip 导入 Skill，检查 `SKILL.md`、脚本、资源和 hash。
4. Agent 只预载 Skill 名称/描述，匹配任务后再按需读取正文。
5. MCP annotation 和 Skill 指令不能绕过 Helix 的 Policy、Approval 或执行限制。

## 5. 功能需求

需求优先级：P0 为首次可用版本不可缺失；P1 为单机正式版；P2 为后续增强。

### 5.1 会话与消息

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-CHAT-001 | P0 | 创建、重命名、归档会话 | 进程重启后保持 |
| FR-CHAT-002 | P0 | 文本输入和 SSE 流式输出 | 可取消；断流有错误状态 |
| FR-CHAT-003 | P0 | 展示模型文本、工具请求、工具结果和审批卡片 | 四类消息可区分 |
| FR-CHAT-004 | P1 | 系统分享文字/图片/文件进入指定或新会话 | 不自动发送给模型 |
| FR-CHAT-005 | P1 | 语音转文字 | 优先使用系统识别，不后台常驻监听 |
| FR-CHAT-006 | P0 | 会话内选择、预览、移除并发送纯文本/图片附件 | 先复制为带 hash 的 Workspace Artifact；重启可恢复；一次性导入不要求 SAF tree grant |
| FR-CHAT-007 | P0 | 附件请求按媒体类型物化 | 纯文本为有界不可信上下文，图片走 vision part；二进制 base64 不冒充理解 |
| FR-CHAT-008 | P0 | 附件出网披露与精确绑定 | 点击发送前展示 Provider/origin/类型/大小/数据类别；绑定消息与 Artifact hashes，内容或目标变化重新评估 |

### 5.2 模型 Provider

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-LLM-001 | P0 | 配置 Base URL、Model ID、API Key/认证别名 | Secret 不进入 Room 和日志 |
| FR-LLM-002 | P0 | OpenAI Responses 流式文本与工具调用 | 独立协议 fixture 覆盖分片 JSON |
| FR-LLM-003 | P0 | OpenAI Chat Completions 和 Anthropic Messages | 两个 adapter 分别测试，不猜测式 fallback |
| FR-LLM-004 | P0 | 分层连接测试与能力探测 | 区分 DNS、TLS、认证、模型、文本流和 ToolCall |
| FR-LLM-005 | P1 | 多 Provider 切换 | 会话记录协议、Provider/Model 和能力快照 |
| FR-LLM-006 | P1 | 常用厂商模板与自建服务 | SGLang/Ollama 真机连接；不硬编码模型名 |
| FR-LLM-007 | P1 | 上下文裁剪 | 永不截断待执行工具参数和审批上下文 |
| FR-LLM-008 | P2 | Codex/Claude 官方 CLI 订阅后端 | CLI 持有凭据；安全 Spike 前不冒充纯 ModelProvider |
| FR-LLM-009 | P0 | Provider 数据去向分类 | 按实际 endpoint 标记本机、已授权局域网、公有云或未知远端；不按 Ollama/SGLang 等模板名猜测 |
| FR-LLM-010 | P0 | 高敏数据出网门控 | Standard 逐次展示数据类别、Provider/origin 和 scope；Advanced 仅允许精确、限时、可撤销规则；凭据类数据始终拒绝 |

### 5.3 Agent Runtime

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-AGENT-001 | P0 | 单会话内串行 Turn，不同会话可并发 | 并发测试无交叉写入 |
| FR-AGENT-002 | P0 | 每 Turn 最多 12 个模型/工具循环 | 超限以结构化错误结束 |
| FR-AGENT-003 | P0 | 工具结果必须回填模型后才能继续 | 事件序列可审计 |
| FR-AGENT-004 | P0 | 支持取消 | 模型请求和执行器均收到取消信号 |
| FR-AGENT-005 | P0 | 失败不伪装成功 | 完成态必须有模型 final 或经验证产物 |
| FR-AGENT-006 | P1 | 进程重启恢复 | `RUNNING` 变为 `INTERRUPTED`，由用户恢复 |
| FR-AGENT-007 | P0 | Plan 模式 | 只能调用只读工具，输出版本化 PlanArtifact |
| FR-AGENT-008 | P1 | Goal 模式 | 持久目标、验收条件、预算、检查点和人工输入状态 |
| FR-AGENT-009 | P1 | Goal 证据完成 | 只有真实 ToolResult/Artifact verifier 支持时才完成 |
| FR-AGENT-010 | P0 | Context Builder | 来源/信任标记、确定性 token 预算、大结果 Artifact 引用；不截断当前工具/审批契约 |

### 5.4 工具系统

每个工具必须包含：稳定名称、版本、描述、输入 Schema、输出 Schema、操作类别（是否只读）、风险等级、超时、最大输出、权限声明、幂等性说明和实现者。Plan 只看操作类别是否为 `READ_ONLY`，不使用风险等级猜测。

同一模型响应包含多个 ToolCall 时，由平台根据规范化参数生成 effect footprint。只读且资源不冲突的调用可在手机有界并行；写入、代码、Root、Accessibility、同一页面/Runtime lane 或未知效应默认串行。实际完成顺序不改变模型历史：结果按原始 call sequence 回填，真实 timing 单独审计。模型、MCP 和 Skill 不能声明自己“并发安全”。

下表是产品级首批核心工具；完整名称、风险和实施顺序以 [Android 平台能力 §7](../architecture/android-platform-capabilities.md#7-android-基础工具最小集合) 及 backlog 为准。短工具名采用 Pi Agent 已验证的模型交互习惯，但执行实现必须符合 Android scope：

| 工具 | 优先级 | 默认风险 | 说明 |
| --- | --- | --- | --- |
| `time.now` | P0 | L0 | 返回本地时间、UTC、时区 |
| `read` | P0 | L0/L1 | scopeId + 相对路径，限大小、编码检测 |
| `write` | P0 | L1/L2 | 原子写，覆盖时升为 L2 |
| `edit` | P0 | L1/L2 | old text 唯一匹配或带前置 hash |
| `files.list` | P0 | L0/L1 | Workspace/SAF/All-files 作用域，限定数量 |
| `files.search` | P0 | L0/L1 | 限文件数、命中数和时间 |
| `files.stat` | P0 | L0 | 有界元数据和可选 hash |
| `files.mkdir` | P0 | L1 | 禁止越界 |
| `files.copy` | P1 | L1/L2 | 目标冲突和跨 scope 必须显式策略 |
| `files.move` | P1 | L2 | 默认审批 |
| `files.delete` | P1 | L2 | 每次审批，优先进入 Helix 回收站 |
| `http.fetch` | P1 | L1 | 默认 GET；Standard 仅公网；Advanced 仅可访问用户预建的精确 LAN/loopback origin scope；metadata 和凭据转发始终拒绝 |
| `code.javascript.run` | P0 | L2 | 必须展示代码并审批 |
| `bash` | P1 | L2 | 仅独立 PRoot Runtime；每次展示 script/argv |
| `browser.open` / `browser.snapshot` | P1 | L1 | 内置浏览器导航和页面语义快照 |
| `browser.click` / `browser.type` | P1 | L2 | node token 绑定页面代次；敏感字段拒绝 |
| `skills.list` / `skills.read` | P1 | L0/L1 | Skill 渐进加载 |
| `mcp.<server>.<tool>` | P1 | 动态 | MCP annotation 不能降低 Helix 风险 |
| `ui.snapshot` | P2 | L1 | 限时 Accessibility Session |
| `ui.click` / `ui.set_text` | P2 | L2 | 目标包 allowlist；敏感界面拒绝 |
| `root.status` | P2 | L0 | 用户主动请求 Root 后报告真实状态 |
| `android.open_uri` | P1 | L1 | 只打开，不代替用户确认外部动作 |
| `android.share` / `android.app_info` | P1 | L1 | 分享先预览；应用信息有界 |
| `clipboard.read` / `clipboard.write` | P1 | L1/L2 | 仅前台可见会话；敏感内容和跨 App 外发升级 |
| `calendar.prepare_event` | P1 | L1 | 生成草稿 |
| `calendar.commit_event` | P1 | L2 | 每次审批 |
| `notifications.query` | P1 | L1 | 权限、应用和时间范围受限 |

### 5.5 审批与风险

| 等级 | 定义 | 默认行为 |
| --- | --- | --- |
| L0 | 只读且数据不离开本机 | 可自动执行，仍记审计 |
| L1 | 有限读取、联网或可恢复写入 | 首次或作用域变化时审批 |
| L2 | 删除、覆盖、执行代码、外部写入 | 每次展示参数并审批 |
| L3 | Root 写入、通用 Root 命令、权限边界变化或不可逆高影响动作 | 默认拒绝；仅明确的开发者控制台流程逐次确认 |

审批决定只对“工具名/版本/schema hash + 参数摘要 + scope + 会话 + 执行目标 + 短期页面/UI token + 一次执行”有效。MVP 不提供永久允许生成代码。

只有明确的用户批准决定能生成并消费 `ApprovalProof`。拒绝决定可以记录为已处理，但不能被消费为执行授权；并发消费最多一个批准凭证成功。Advanced 可按 [ADR-0012](../adr/0012-capability-first-advanced-grants.md)保存精确数据发送规则、Trusted Workspace 和动态风险不高于 L1 的有界工具规则；通用 L2/L3、生成代码、Shell/Root 命令、删除覆盖和敏感 UI 操作不能永久放行。模型、MCP、Skill、网页和脚本均不能自授权。

### 5.6 Workspace

- 每个会话一个逻辑 Workspace；可关联 SAF 或 All-files scope，但默认复制到应用私有目录处理。
- 相对路径必须规范化；拒绝绝对路径、`..` 越界、符号链接越界和特殊设备文件。
- 默认单文件 10 MiB、单次工具输出 256 KiB、单会话 Workspace 500 MiB。
- `read` 支持 `offset` + `maxBytes`；超过当前 Context 或 QuickJS 2 MiB input 上限的文件必须分块处理，不允许通过提高 JS 限额绕过。
- 写入先生成临时文件、`fsync` 后原子替换；保留变更前哈希。
- 删除进入 `.helix/trash/<operation-id>`；用户清空回收站才物理删除。
- All files access 只扩大 Android 能访问的共享存储，Agent 仍只能操作用户在 Helix 内选择的 roots。
- `Full Workspace Access` 只表示用户选择了较宽的文件 roots；它不扩大到其他 App 私有目录、系统设置、凭据或未知工具，也不替代逐调用动态风险与 Policy。
- 外部导出必须由 SAF、明确的文件管理动作或已审批 scope 写入完成。

### 5.7 本地代码执行

- QuickJS 仅接收 UTF-8 JavaScript 源码和 JSON 输入。
- 不暴露 Java/Kotlin 对象、Android Context、任意文件 API、网络 API、动态 import 或 native API。
- 默认上限：10 秒、64 MiB QuickJS heap、256 KiB JSON 输出；QuickJS 不直接读写文件。
- 必须运行在 `android:isolatedProcess="true"` 的独立 Service。
- PRoot 规范详见 [本地代码执行方案](../architecture/local-code-execution.md)。

### 5.8 浏览器与网页工具

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-WEB-001 | P1 | 内置多标签 WebView 浏览器 | 地址、前进后退、刷新停止、查找、分享、下载和数据清除 |
| FR-WEB-002 | P1 | 页面语义 snapshot | 大小有界；页面内容标记不可信；node token 有 TTL |
| FR-WEB-003 | P1 | 受控 click/type/scroll | token 绑定 tab/origin/navigation generation；导航后失效 |
| FR-WEB-004 | P1 | 下载到 Workspace/授权目录 | 重定向、大小、MIME、文件名和冲突策略受控 |
| FR-WEB-005 | P1 | 敏感网页动作拒绝 | 密码、验证码、支付、授权和账号恢复不自动操作 |

### 5.9 MCP 与 Skills

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-MCP-001 | P1 | MCP Streamable HTTP Client | initialize/list/call/cancel/close 和认证错误完整 |
| FR-MCP-002 | P1 | 动态能力启用 | 新 server 默认全部禁用，用户选择后才进 Tool Registry |
| FR-MCP-003 | P1 | MCP 动态工具安全 | namespace + schema hash；annotation 不能降低风险 |
| FR-MCP-004 | P2 | PRoot stdio MCP | 固定 server command、严格 stdout、bounded stderr |
| FR-MCP-005 | P1 | Resource/Prompt 发现 | 首版只展示有界 metadata；不读取正文、不注册 Prompt 为指令 |
| FR-SKILL-001 | P1 | Agent Skills 规范兼容 | `SKILL.md` frontmatter 和目录校验通过官方 fixture |
| FR-SKILL-002 | P1 | 渐进加载 | 启动只载 name/description，激活后载正文，资源按需读取 |
| FR-SKILL-003 | P1 | 本地导入和管理 | zip/目录校验、内容 hash、启停、移除、版本快照 |
| FR-SKILL-004 | P1 | Skill 不授权 | scripts 必须通过现有 code/bash Tool 和审批 |

### 5.10 Accessibility 与 Root

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-UI-001 | P2 | 用户主动启用 Accessibility | 权限中心说明、系统设置、目标包 allowlist、停止入口 |
| FR-UI-002 | P2 | UI snapshot 和节点动作 | token 绑定 package/window/generation；无盲坐标点击 |
| FR-UI-003 | P2 | 自动化预算 | 默认 5 分钟/30 动作，每 10 动作检查点 |
| FR-UI-004 | P2 | 敏感界面拒绝 | 系统授权、安装器、Root 管理、支付、密码、认证和锁屏 |
| FR-ROOT-001 | P2 | 用户主动请求 Root | 区分 unavailable/denied/granted/lost，不启动时弹授权 |
| FR-ROOT-002 | P2 | 高层 Root 工具 | status、受限读、包/进程/日志；短时 RootSession |
| FR-ROOT-003 | P2 | 通用 Root 命令限制 | `root.exec` 默认不进 Agent Registry，仅开发者 L3 控制台 |

### 5.11 商店、官网分发与能力降级

- Google Play、选定国内 Android 应用商店和官网都是正式目标渠道；所有渠道的默认用户体验称为 Helix Standard，不向用户暴露 consumer/developer 工程名。
- Standard 必须保留 Provider/BYOK、Goal、Workspace/SAF、文件工作台、Browser、QuickJS、MCP/Skills、Android 基础工具、模板、恢复和审计，不得退化为聊天壳。
- All-files、Accessibility、解释脚本、Tasker 等能力优先通过核心用途声明、显著披露、用户同意和渠道审核保留；只有明确政策条款或真实审核结果要求时，才对受影响渠道做最小替换或移除。
- Google Play 当前不允许使用 Accessibility 自主发起、规划并执行 UI 动作；Play artifact 只提供审核允许的确定性、用户可理解自动化。该限制不扩展为官网或其他合法渠道的永久 Standard 限制。
- Google Play 若以文件/文档管理核心用途申报 All-files，产品首页、商店描述和真实功能必须一致；未获批准时回退 SAF/MediaStore，但保留完整文件工作台。
- PRoot/CLI binary 仍位于独立 APK/UID。某商店不接受 companion 或 Root 不可用时，只让对应 Capability 不可用，不删除其他 Standard 能力。
- 当前 consumer/developer flavor 和 applicationId 保持工程事实；HXA-122 决定稳定主 ID、渠道命名与升级路径，不能仅靠文档宣称跨 ID 原地升级。
- 未安装 Runtime、未 Root、未开启 All-files/Accessibility 或未配置 MCP 时，相关工具不进入模型工具表，其余能力正常运行。
- “以上架为目标”不等于保证第三方审核通过；只有真实提交、审核通过和可下载证据才能标记为已上架。每个国内目标商店在提交时单独核验，不套用 Google Play 或其他商店结论。

## 6. 非功能需求

| ID | 类型 | 指标 |
| --- | --- | --- |
| NFR-001 | 正确性 | 固定 40 条支持场景端到端成功率至少 80% |
| NFR-002 | 工具可靠性 | 确定性工具在有效输入上的成功率至少 95% |
| NFR-003 | 安全 | 自动化测试中未审批 L2/L3 执行次数为 0 |
| NFR-004 | 恢复 | Activity 重建、进程终止后无已提交消息丢失 |
| NFR-005 | 性能 | 非模型 UI 操作 P95 小于 200 ms；冷启动目标小于 2.5 s |
| NFR-006 | 稳定性 | 24 小时交互测试无未处理崩溃、无 Agent Loop 泄漏 |
| NFR-007 | 隐私 | API Key、完整 Authorization Header 不出现在日志、Room、崩溃报告 |
| NFR-008 | 可观察性 | 每个 Turn、ToolCall、Approval、Execution 有 correlation ID |
| NFR-009 | 可测试性 | core 模块 JVM 单元测试；Android 边界用 instrumentation 测试 |
| NFR-010 | 可维护性 | 无循环模块依赖；UI 不直接访问 DAO、HTTP 或执行器 |
| NFR-011 | Standard 默认 | 所有渠道首次启动为完整 Standard；未主动启用的系统能力不产生权限、Root、Runtime 安装或网络副作用，但已允许的核心能力不因 Profile 名称被裁剪 |
| NFR-012 | 单一产品 | 渠道 artifact 共享产品身份、数据模型与核心任务矩阵；同一 applicationId 内切换 Standard/Advanced 不丢失会话、Workspace 或 Provider 配置 |
| NFR-013 | Runtime 生命周期 | companion 无需预先打开/常驻即可冷绑定；进程回收后可重连，未知执行结果不自动重放 |

## 7. 页面需求

1. 首次启动：隐私说明、Provider 配置、连接测试；所有渠道主应用默认 Standard，不向用户展示工程 flavor 选择。
2. 会话列表：创建、重命名、归档、模型标识。
3. 会话页面：时间线、输入、停止、工具展开、审批卡片。
4. Workspace：文件树、文本预览、Diff、导入、导出。
5. 任务详情：状态机、模型调用、工具调用、耗时、产物。
6. Provider 设置：密钥、Endpoint、Model、连接检查。
7. 权限中心：通知、日历、麦克风、文件授权及用途。
8. Runtime 设置：QuickJS 限额；developer 变体的 Linux 安装、状态、更新、修复与删除。进程存活不作为“已安装/可用”的显示条件。
9. 审计日志：按会话、工具、风险、日期过滤。
10. 内置浏览器：地址栏、标签、站点权限、下载和 Agent 动作指示。
11. 文件管理器：Workspace/SAF/All-files/Root 来源标识、冲突和长任务进度。
12. MCP 与 Skills：Server 连接、动态能力启停、Skill 导入/校验/快照。
13. 运行配置：渠道允许时展示 Standard/Advanced、分能力启用状态、scope/规则期限和一键撤销；Standard 本身必须是完整可用产品。
14. Agent 模式与 Goal：模式切换、Plan、验收条件、预算和检查点。
15. 高级能力：Accessibility、Root、Linux/CLI Runtime 的独立风险页和停止/卸载入口。

首版 UI 跟随系统语言，提供简体中文和英文资源；用户可见字符串不得硬编码在 Kotlin/Compose 中。

## 8. 数据保留与删除

- 用户可以删除单会话及其 Workspace、审计和产物。
- Provider API Key 单独删除，不随会话导出。
- 日志默认保留 30 天，可由用户调整或立即清除。
- PRoot RootFS 可独立卸载；CLI Runtime 可单独 logout/清除或卸载，卸载会删除其中由官方 CLI 管理的凭据。
- 导出诊断包必须先脱敏，并允许用户预览文件列表。

## 9. 产品验收场景

至少覆盖：

1. JSON 排序并生成 Markdown。
2. CSV 聚合并输出新 CSV。
3. 批量重命名建议，只预览不执行。
4. 修改文本并生成 Diff。
5. 执行无限循环 JavaScript，被超时终止。
6. JavaScript 尝试访问 Android API，失败。
7. JavaScript 尝试联网，失败。
8. 删除文件未审批，失败。
9. 审批参数被模型修改，旧审批失效。
10. 模型流中途断开，任务进入可重试失败态。
11. 用户取消模型请求。
12. 用户取消代码执行。
13. 进程被杀后恢复会话，无工具重复执行。
14. 导入同名文件产生明确冲突策略。
15. 路径穿越、符号链接越界被拒绝。
16. HTTP 请求指向 localhost、私网或 metadata 地址，被拒绝。
17. 通知权限关闭时返回可操作错误，而不是空成功。
18. 日历写入前展示最终时区与时间。
19. PRoot 下载哈希不匹配时拒绝激活。
20. RootFS 更新失败后回滚旧版本。
21. All files access 已授予但 scope 外路径仍被拒绝。
22. WebView 页面含 prompt injection 时不能注册工具或读取其他文件。
23. 页面导航后旧 browser node token 失效。
24. Accessibility 目标包切换时自动暂停；支付/权限界面拒绝操作。
25. Root 管理器拒绝授权时返回 `Denied`，不以存在 `su` 冒充成功。
26. MCP Server 修改 tool schema 后旧审批失效。
27. 恶意 Skill zip 路径穿越和压缩炸弹被拒绝。
28. Plan 模式调用 `write`、`bash`、`browser.click` 或 `ui.click` 被拒绝。
29. Goal 预算耗尽进入暂停/失败而非完成，重启后不重复副作用。
30. Ollama/SGLang 不支持某协议字段时明确降级，不静默丢 ToolCall。
31. PRoot/CLI 已安装但进程未运行且从未手动打开时，批准 Job 能冷绑定并完成握手；空闲回收后下一 Job 仍可执行。
32. Runtime 在 RUNNING/terminal commit 边界被杀后只按 jobId 对账；未知结果进入 `INTERRUPTED`，命令不重复执行。
33. 两个无冲突只读 Tool 可有界并行，但模型收到结果顺序与原始 call sequence 一致；重跑 fixture 结果确定。
34. 并行队列取消时，未启动调用留下 `CANCELLED_BEFORE_START`，已启动调用有 terminal/unknown outcome，重启不自动重放。
35. 调度失败不能回退到更低隔离 target、扩大 scope/权限或先联网后补审批。
36. 若未来启用 child delegation，child 只读、深度 1、共享父预算且无 Approval Proof/Secret；写入 proposal 必须由父 Turn 重新审批。
37. 选择或分享附件只导入本地会话，不自动发送；发送前能移除并看到目标 Provider 与数据类别。
38. 文本附件在进程重启后仍绑定同一 hash；文件缺失或变化时旧发送/出网决定失效，不能读取替代字节。
39. Provider 不支持或未确认 vision 时图片保留本地并给出可操作错误，不能静默丢图或把 base64 文本当作成功。
40. 恶意图片、解码取消或低内存不得造成 OOM、越界读取、孤儿临时文件或正文/base64 日志泄露。
41. UTF-16、PDF/PPT/DOC、音频、视频和未知二进制附件统一返回 `UNSUPPORTED_ATTACHMENT_TYPE` 与正确的封闭 category；不得启动解析、渲染/OCR、媒体解码/抽帧/音轨/转码、Provider upload、模型 context/base64 或派生 Artifact。

## 10. 成功定义

首次单机正式版完成的标志不是“可以聊天”，而是：用户能给出一个涉及网页、文件或手机 UI 的目标，Helix 能选择 Provider、加载所需 Skill/MCP 能力、生成计划、请求必要审批、在正确的本地执行域中运行工具或代码、基于真实输出验证产物，并留下完整可审计记录；全过程不依赖电脑或远程 Worker。

可持续跟踪的产品指标见[竞品分析 §10](competitive-landscape.md#10-后续跟踪指标)；该表是里程碑/季度跟踪框架，不替代[安全、测试与发布门禁](../security/testing-and-release.md)的硬性验收。

## 11. 需求变更记录

### 2026-09-02：未来自动化兼容与 Advanced 授权

- 所有者请求：未来兼容任意 Tasker/Auto.js 脚本；把 Shizuku/ADB 保留为可能支持但暂不实现；为 Advanced 增加长期工具授权、Trusted Workspace、自动批准、Full Access 与模型自授权。
- 纳入需求：Tasker 官方插件桥接、任意来源 Auto.js 脚本导入与兼容诊断、独立兼容 Runtime、Shizuku/无线 ADB 未来研究；Trusted Workspace、动态风险不高于 L1 的有界长期工具规则、精确批量批准和作为文件 scope 的 `Full Workspace Access`。
- 未纳入：模型自授权、全局自动批准、全局 Full Access，以及 L2/L3 的永久 wildcard 放行。原因是这些方案无法把真实手机上的高影响调用绑定到用户的精确授权，并违反项目不可变安全内核；它们已在 [ADR-0012](../adr/0012-capability-first-advanced-grants.md)作为被拒绝替代方案留档。
- 实现状态：本次仅修改需求、架构和决定记录；没有新增 Runtime、Tasker/Auto.js/Shizuku/ADB 代码，也没有把候选能力分配给当前 HXA。

### 2026-09-03：会话附件范围

- 所有者决定先规划 UTF-8 文本与图片附件；PDF/PPT/DOC 和视频读取只保留未来方向，不实现解析、OCR、抽帧、音轨、转码或 Provider 原生上传。
- 当前任务只要求未支持媒体统一分类并稳定拒绝，避免把二进制 base64、文件管理器导入成功或未来能力写成模型已理解。
- 系统分享只创建可预览的本地草稿，不自动发送；原始 Android URI 不进入模型、审计和诊断。

### 2026-09-02：Standard 商店产品边界

- 所有者决定：Standard 以 Google Play 和国内 Android 应用商店上架为产品边界，不为抽象安全偏好或未经核验的准则限制过多能力。
- 纳入需求：Standard 成为完整核心产品；All-files、Accessibility、解释脚本等优先通过核心用途声明、披露、同意和审核保留；渠道仅因明确政策或真实审核反馈做最小差异。
- 外部硬约束：Google Play 当前禁止 Accessibility 驱动的 Agent 自主规划执行，但允许狭窄、用户可理解的确定性自动化；外部 DEX/JAR/native executable 下载也受禁止。这些限制只作用于相关渠道，不扩大成所有 Standard 版本的永久限制。
- 证据边界：上架是明确目标，不是已完成事实；只有真实提交、审核通过和可下载记录才能声称已上架。分发决定见 [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)。
