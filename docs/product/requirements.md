# Helix 产品需求（Android 单机版）

文档状态：当前产品需求；具体交付以实施状态和任务验收为准
整理日期：2026-09-16
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
14. A2A Client：发现并连接用户配置的外部 Agent，把已启用的远端 Skill 作为受控 ToolCall 委托任务，并持久化远端 Task 状态、流式事件和 Artifact。
15. 用户主动开启的 Accessibility 自动化与可选 Root 高级能力。
16. 任务中断后的持久化恢复。
17. 可选 PRoot + Alpine Linux 开发者模式。
18. 确定性 Tool 编排：单一安全管线、按物理容量和具体不可重入资源有界并发、按模型 call sequence 回填、持久取消/恢复与分阶段审计；不按潜在文件写冲突设置全局写屏障，不重放未知副作用。
19. 会话纯文本与图片附件：一次性导入到 Workspace、可预览/移除、消息绑定、图片 vision、精确出网披露以及系统分享草稿；选择或分享不自动发送。
20. 国际化：提供简体中文和英文 UI，默认跟随系统，用户可在 App 内选择“跟随系统 / 简体中文 / English”。

### 2.2 明确不实现

- 远程 Worker、云端沙箱或桌面配对；M7 的 A2A Client 只调用用户配置的外部 Agent 服务，不把远端 Agent 注册为 Helix `ExecutionTarget`，也不把基础任务迁移为云端执行。
- HarmonyOS 客户端。
- 应用内无线 ADB 配对仍未实现；电脑启动的 ADB helper 已纳入 Android 12+ Advanced 的有界路径，不能与无线配对或通用调试桥混同。Shizuku 已按 HXA-243/244 纳入 Mobile Use 后端，现状与验证边界见[当前状态](../development/status.md)和[平台能力](../architecture/android-platform-capabilities.md)，不推导所有 OEM 支持。
- 自动付款、转账、下单。
- 未经确认自动发送消息、邮件或公开内容。
- 从模型响应中下载并加载 DEX/APK/native library。
- 手机端完整 Android SDK/Gradle 构建环境。
- 多 Agent 群体/递归编排、Agent 间任意 peer 通信；M7 只允许 Helix 作为单一 Client 向用户启用的 A2A Agent 发起有界任务，后期内部 child delegation 仍只允许 HXA-105/ADR-AGENT-004 评估 Advanced 的深度 1 只读方案。
- 云端任务舰队、远程 diff apply、可执行 Workflow/Policy DSL、Agent 自修改或自行挂载插件。
- MCP Server 托管。
- 当前 M7 不实现 A2A Server 托管、手机 webhook/push callback、远端 Agent 直接调用 Helix Tool，以及远端 Agent 获得本机 Capability/Approval/Secret；FUT-A2A-001～003 可在独立路线/ADR 后逐步提升。
- 不受信任来源的自动安装和自动下载执行依赖。受审核目录的发现、预览与用户明确安装已按插件 ADR 提供，不能与任意远程代码执行混同。
- 提取浏览器 Cookie、复用其他 App token 或调用模型厂商未公开的订阅接口。

### 2.3 首个产品闭环与状态边界

本节定义产品闭环，不复制当前 HXA、已完成范围或实现快照；这些易变化事实只在[实施状态](../development/status.md)维护。

- 第一个可感知的本地执行闭环是“选择或导入目录 → Agent 读取并提出变更 → 用户查看任务摘要/diff → 执行 → 展示产物与失败状态”。它用于验证 Helix 相比纯 API 客户端的核心价值，但不等于 Alpha 或正式版完成。
- QuickJS、Browser、MCP/A2A/Skill、PRoot/CLI、Accessibility、Root 按路线依赖和风险逐级接入。未完成的能力不得出现在默认营销承诺、首屏引导或可调用工具表中。
- 对外产品定位统一为“面向开发者与效率用户、Provider-neutral、能力优先的 Android 本机 AI 执行工作台”；市场与商业假设见[市场、用户与商业化分析](market-users-and-commercialization.md)。HarmonyOS 和系统 Agent 生态只作为竞品参照，不进入当前实现范围。

### 2.4 未排期未来能力候选

跨 ADR/任务的接受与实现边界见[候选索引](../development/candidate-decisions.md)；本节仍是 FUT 需求正文来源。

下列需求只记录方向与 Android 可行性边界，不分配当前 HXA，不得显示为已实现或确定交付：

| ID | 候选需求 | 当前可验收口径 |
| --- | --- | --- |
| FUT-AUTO-001 | Tasker 互操作 | 研究官方插件 action/event/state、命名 Task 调用和结果回传；不承诺导入任意 Tasker profile 后语义不变 |
| FUT-AUTO-002 | Auto.js/AutoJs6 脚本兼容 | 接受任意来源脚本导入并生成引擎/API/权限/模块兼容报告；按公开兼容矩阵逐步执行，不承诺任意脚本零差异 |
| FUT-AUTO-003 | 独立脚本 Runtime | 脚本在独立应用/UID 中执行，经有界 IPC 调用 Helix；不复用主进程 QuickJS 的隔离边界，不复制许可证不兼容代码 |
| FUT-SYS-001 | Shizuku 能力 Provider（已由 HXA-243/244 承接） | 不再是未实施候选；用户显式启动与授权，撤销、Binder death、OEM 和逐调用权限边界继续适用，当前交付范围见平台能力文档 |
| FUT-SYS-002 | 无线 ADB | Android 11+ 由用户启用开发者选项并配对；研究本机 client、密钥、前台生命周期与供应链；断连不盲目重放 |
| FUT-MEDIA-001 | 扩展文档对话读取与保真 | 直接附件入口仍拒绝文档二进制；显式启用的 [文档插件](document-processing-plugin.md) 已覆盖有界 PDF/DOCX 阅读，不等于整文上传。旧 DOC、PPT/PPTX、宏、编辑/生成及通用 OCR/格式保真仍不属于当前承诺；扩展前评估许可证、内存、恶意文档和中文/表格质量 |
| FUT-MEDIA-002 | 视频附件理解 | 当前仅识别为未支持附件；未来分别评估端上有界抽帧与 Provider 原生上传，覆盖时长/帧数/像素/音轨/成本/数据保留和 Provider 可移植性 |
| FUT-A2A-001 | Advanced 前台局域网 A2A Server | 用户主动开启并持续显示前台状态；绑定明确接口/端口，验证配对、TLS/局域网身份、网络切换、Doze、进程回收、停止和重启后默认关闭 |
| FUT-A2A-002 | 用户自备远程可达通道 | 研究 VPN、反向隧道或自建 relay；Helix 不默认建设云控制面，不把中继在线冒充手机 Agent 在线，并分别记录通道身份、断连和费用 |
| FUT-A2A-003 | 远端到本机的双向任务 | 第一阶段只接受结构化 Tool proposal，由父 Turn 创建正常 ToolCall；未来直接调用必须绑定远端身份、工具/scope、期限和会话，并覆盖重放、撤销、锁屏/离线和不明确副作用 |

“兼容任意脚本”在需求层表示任意输入可导入、可诊断并得到明确结果，不表示未经兼容矩阵验证即可成功执行。应用内无线 ADB 配对仍是候选；现有电脑启动 helper 不代表无线配对或所有设备矩阵已完成。FUT-A2A-001～003 说明双向 A2A 在 Android 上是有条件可行的后续能力，不属于 M7/HXA-077～079，也不等于已经承诺日期、云 relay 或递归 Agent。

## 3. 目标用户

### P1：开发者与高级用户

希望在手机上处理项目、文件、网页和数据，愿意检查 Agent 生成的代码，并启用 Linux Runtime、Git、Shell、All-files、Accessibility 或 Root 等高级能力；接受额外下载、功耗、兼容性提示，并对主动开启和确认后的设备操作承担最终责任。

### P2：Android 效率与自动化用户

希望用自然语言完成文件整理、信息提取、系统操作和可复用任务，不愿继续手写或维护复杂 Tasker/Auto.js 类脚本。

### P3：普通效率用户

希望整理文件、摘要通知、生成日历草稿和转换数据，不愿接触终端。该群体只有在能力安装、错误恢复和首次任务路径足够成熟后才作为主要增长对象。

### 3.1 渠道能力与独立授权

目标用户不直接等于会话授权。能力集合按 [ADR-PLATFORM-001](../adr/platform/001-distribution.md) 固定于构建渠道，授权仍遵循 [ADR-PERMISSIONS-003](../adr/permissions/003-dispatch-and-audit.md)：

| 配置 | 默认与可用范围 | 产品要求 |
| --- | --- | --- |
| `STANDARD` | Consumer 固定；渠道允许的完整产品形态 | 完整核心任务能力、低配置默认；可按目标渠道保留 All-files、Accessibility、解释脚本等允许能力；高敏数据和系统能力按需启用，不因抽象安全偏好预先裁剪 |
| `ADVANCED` | Developer 固定；渠道允许的全部能力，无需选择档位 | 分能力启用；可开放 Root、PRoot/CLI、Agent UI 自动化、精确 LAN origin 和开发者控制台；每项有 scope、状态、停止和撤销 |

`consumer/developer` 是编译期能力集合，`STANDARD/ADVANCED` 仅是对应渠道的内部 Policy 身份，不再是可选运行时配置。Developer 首次启动和重启均为 `ADVANCED`，Consumer 为 `STANDARD`；不显示档位开关。不自动申请系统权限、安装 Runtime、请求 Root 或连接新端点；会话审批预设与具体授权独立。

Advanced 扩大的是可选能力和可配置范围，不是绕过安全内核。schema/Policy/Approval、Secret 隔离、进程/UID 隔离、敏感界面拒绝、审计、取消和变更后验证在两级中完全一致。用户可保存 Capability、文件 scope、会话授权模式及精确高敏出网规则；是否询问依据当前操作类别和有效授权，不能按旧 L0–L3 等级推导。需要询问的调用使用精确一次性 proof。

产品优先级以能力和任务完成率为主，而不是以审批数量或安全功能数量为主。Advanced 用户负责选择 Provider、开启能力、限定 scope、备份重要数据并承担自己确认的操作后果；Helix 负责不伪造结果、不隐瞒执行目标与数据去向、不在用户关闭能力后继续执行，也不允许模型替用户开启权限或产生批准。

分发遵循 [ADR-PLATFORM-001](../adr/platform/001-distribution.md)：用户获取的是同一个 Helix 产品，Consumer 直接使用完整 Standard，Developer 直接使用 Advanced，不再在同一安装内切换能力档位。当前 consumer/developer 只是工程打包机制；最终 channel/flavor/applicationId 由 HXA-122 结合签名和升级证据决定。

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

1. 用户安装提供 Linux 能力的 Advanced 构建；渠道身份固定，不在设置中切换档位。
2. 首次获准的新作业或用户手动打开终端时准备固定资源；被动查看和旧任务恢复不安装资源。损坏或不匹配环境走明确维护入口。
3. Agent 提议命令及其请求绑定目录、超时和环境变量。
4. 授权后主 App 按需冷绑定同 APK、同 UID 的非导出私有 PRoot 进程；受支持的真实目录原位映射，SAF 不伪装为 POSIX 路径，也不静默复制项目。该进程边界不是凭据隔离或恶意代码沙箱。
5. 结果被截断、结构化并写入审计日志；空闲后解绑，Runtime 进程可以被系统回收。
6. 若执行中断或 Runtime 被强制停止，Helix 显示中断原因和“修复 Runtime”入口，不自动重复执行命令。

### UJ-05：网页研究与受控交互

1. 用户在 Helix 内置浏览器打开网页。
2. Agent 获取当前页面的有界语义 snapshot；网页内容被标记为不可信。
3. Agent 生成研究计划并引用实际 URL；页面跳转由 `browser_navigate` 完成。
4. 点击、输入、下载等动作展示目标元素、origin 和影响并按风险审批。
5. 页面发生导航或结构变化后，旧 node token 失效，必须重新 snapshot。

### UJ-06：跨 App 自动化

1. 用户在“插件与专家”配置可用 Mobile Use 后端，并在目标会话选用插件、确认任务范围。
2. 会话授权与 Android 系统后端授权分别判断；始终提供停止与撤销入口。
3. Agent 基于当前节点或截图观察提出动作，目标、后端和观察失效时重新观察。
4. 点击或输入提交不等于业务完成；未知结果先检查当前状态，不跨后端盲目重复副作用。
5. 敏感界面、锁屏和后台屏边界依 [Mobile Use 契约](../architecture/android-platform-capabilities.md)，不能因插件可用而扩大授权。

### UJ-07：MCP 与 Skill

1. 用户添加 MCP Server，连接测试并查看服务器声明的 tools/resources/prompts。
2. 用户逐项启用需要的工具；动态工具以 `mcp.<server>.<tool>` 注册。
3. 用户从本地目录或 zip 导入 Skill，检查 `SKILL.md`、脚本、资源和 hash。
4. Agent 只预载 Skill 名称/描述，匹配任务后再按需读取正文。
5. MCP annotation 和 Skill 指令不能绕过 Helix 的 Policy、Approval 或执行限制。

### UJ-08：委托给 A2A Agent

1. 用户添加 A2A endpoint，Helix 获取 Agent Card，展示 provider、origin、协议版本、输入/输出模式和 Skill 列表。
2. 用户连接测试并启用需要的远端 Skill；只有固定 Agent Card/Skill snapshot 才进入 Tool Registry，命名为 `a2a.<agent>.<skill>`。
3. Agent 提议委托任务时，Helix 展示远端 Agent、origin、发送的数据类别/Artifact、目标 Skill 和可取消性，再按普通网络 ToolCall 执行。
4. Helix 保存本地 call ID 与远端 task/context ID 的映射；同步或 SSE 事件有界回填，断线后只查询同一远端 Task，不盲目重发。
5. 远端文本、状态和 Artifact 均标记为不可信；它们不能产生 Approval Proof、直接调用本机工具或充当本机副作用的验证证据。需要本机后续动作时，由 Helix 创建新的 ToolCall。

## 5. 功能需求

需求优先级：P0 为首次可用版本不可缺失；P1 为单机正式版；P2 为后续增强。

### 5.1 会话与消息

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-CHAT-001 | P0 | 创建、重命名、归档会话 | 进程重启后保持 |
| FR-CHAT-002 | P0 | 文本输入和 SSE 流式输出 | 可取消；断流有错误状态 |
| FR-CHAT-003 | P0 | 展示模型文本、工具请求、工具结果和审批卡片 | 四类消息可区分 |
| FR-CHAT-004 | P1 | 系统分享文字/图片/文件进入指定或新会话 | 不自动发送给模型 |
| FR-CHAT-005 | P1 | 语音转文字 | 按语音 ADR 的系统、本地与云端配置执行；转写可编辑，不自动发送，不后台常驻监听 |
| FR-CHAT-006 | P0 | 会话内选择、预览、移除并发送纯文本/图片附件 | 先复制为带 hash 的 Workspace Artifact；重启可恢复；一次性导入不要求 SAF tree grant |
| FR-CHAT-007 | P0 | 附件请求按媒体类型物化 | 纯文本为有界不可信上下文，图片走 vision part；二进制 base64 不冒充理解 |
| FR-CHAT-008 | P0 | 附件出网披露与精确绑定 | 点击发送前展示 Provider/origin/类型/大小/数据类别；绑定消息与 Artifact hashes，内容或目标变化重新评估 |

### 5.2 模型 Provider

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-LLM-001 | P0 | 网络 API/自建服务配置 endpoint、Model ID 与可选认证；订阅账号和设备内模型使用各自 provisioning 流程 | Secret 不进入 Room 和日志；On-device 不伪造 Base URL/API Key |
| FR-LLM-002 | P0 | OpenAI Responses 流式文本与工具调用 | 独立协议 fixture 覆盖分片 JSON |
| FR-LLM-003 | P0 | OpenAI Chat Completions 和 Anthropic Messages | 两个 adapter 分别测试，不猜测式 fallback |
| FR-LLM-004 | P0 | 分层连接测试与能力探测 | 区分 DNS、TLS、认证、模型、文本流和 ToolCall |
| FR-LLM-005 | P1 | 多 Provider 切换 | 会话记录协议、Provider/Model 和能力快照 |
| FR-LLM-006 | P1 | 常用厂商模板与 endpoint-based 自建服务 | Ollama/SGLang/vLLM 按实际 endpoint 作为 Network Provider；不硬编码模型名或按品牌猜 residence |
| FR-LLM-007 | P1 | 上下文裁剪 | 永不截断待执行工具参数和审批上下文 |
| FR-LLM-008 | P2 | 第三方订阅协议 Provider | 官方 CLI 路线停止；订阅模块持有 token（同 UID，不构成凭据隔离），developer/Advanced 与 API Key Provider 统一选择，consumer 关闭 |
| FR-LLM-009 | P0 | Provider 数据去向分类 | Network 按实际 endpoint 标记 loopback、已授权局域网、公有云或未知远端；直接设备内推理单独标 `ON_DEVICE_LOCAL`；不按 Ollama/SGLang/vLLM 模板名猜测 |
| FR-LLM-010 | P0 | 高敏数据出网门控 | Standard 逐次展示数据类别、Provider/origin 和 scope；Advanced 仅允许精确、限时、可撤销规则；凭据类数据始终拒绝；`ON_DEVICE_LOCAL` 推理本身不是网络出网 |
| FR-LLM-011 | P1 | Provider provisioning / transport / auth 正交建模 | UI 可分 API/Self-hosted、Subscription、On-device；底层不以三分类替代 Network/On-device transport、residence 与认证事实 |

### 5.3 Agent Runtime

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-AGENT-001 | P0 | 单会话内串行 Turn，不同会话可并发 | 并发测试无交叉写入 |
| FR-AGENT-002 | P0 | 有界模型与工具执行预算 | 采用当前预算及循环控制契约，明确耗尽/阻塞，不以历史固定轮数替代现行配置 |
| FR-AGENT-003 | P0 | 工具结果必须回填模型后才能继续 | 事件序列可审计 |
| FR-AGENT-004 | P0 | 支持取消 | 模型请求和执行器均收到取消信号 |
| FR-AGENT-005 | P0 | 失败不伪装成功 | 完成态必须有模型 final 或经验证产物 |
| FR-AGENT-006 | P1 | 进程重启恢复 | `RUNNING` 变为 `INTERRUPTED`，由用户恢复 |
| FR-AGENT-007 | P0 | Plan 模式 | 用户文件和外部副作用只读；仅允许权限 ADR 明确列出的闭合元数据操作，输出版本化 PlanArtifact |
| FR-AGENT-008 | P1 | Goal 模式 | 持久目标、预算、检查点和人工输入状态；用户激活后前后台跨轮推进，create_goal/get_goal/update_goal 支持创建、读取、目标/预算编辑和暂停恢复；取消、系统中断及重启停驻，见 ADR-GOAL-001 |
| FR-AGENT-009 | P1 | Goal 模型完成判断 | 模型依据工作结果报告完成及理由；Harness 检查任务归属、正常结算、取消和未决副作用，不强制证据绑定，见 ADR-GOAL-001 |
| FR-AGENT-010 | P0 | Context Builder | 来源/信任标记、确定性 token 预算、大结果 Artifact 引用；不截断当前工具/审批契约 |

### 5.4 工具系统

每个工具须有稳定名称、版本、描述、Schema、可信操作类别、执行限额、权限与来源契约。Plan 按可信只读及权限 ADR 明确列出的闭合元数据操作准入，不用第三方注解、工具名称或历史风险等级推断授权。

同一模型响应的 ToolCall 按物理容量及具体不可重入引擎/控制资源有界调度；写入、代码或终端本身不产生全局锁，也不按潜在用户文件冲突代替用户和模型验收。结果按原始 call sequence 回填，真实 timing 单独审计；取消后执行器未退出仍占物理容量。模型、MCP、A2A 和 Skill 不授予执行或调度权限。

产品要求覆盖有界文件读写/检索、网页与 HTTP、代码/终端、扩展发现与远端委托、手机观察/操作及 Android 基础能力。短工具别名仍使用相同的作用域和授权实现，不形成旁路。有独立 FR 的能力优先级见对应表；其余基础能力的产品要求保留如下。具体工具名称、Schema、限额及支持后端统一维护在 [Android 平台能力 第 7 节](../architecture/android-platform-capabilities.md#7-android-基础工具分组) 和对应主题 ADR，不在产品需求中另维护逐工具参数与风险表。

| 能力要求 | 优先级 | 用户可见结果 |
| --- | --- | --- |
| 时间与文件基本操作 | P0 | 读取当前时间/时区；读取、创建、修改、列举、检索文件、创建目录及查看元数据 |
| 文件组织 | P1 | 复制、移动、删除，并显示冲突与实际回收/删除后果 |
| 网络与执行 | P1 | 有界 HTTP 请求、受控 Linux 终端；JavaScript 仍按本地执行需求列为 P0 |
| Android 系统交互 | P1 | 打开 URI、分享、查看应用信息、受控剪贴板、日历草稿/提交及范围化通知查询 |
| 手机高级操作 | P2 | 经授权的 UI 观察/输入及 Root 状态和受控操作，未授权时给出可操作错误 |

### 5.5 会话授权与效果限制

会话采用当前权限 ADR 的 APPROVAL_REQUIRED、FULL_ACCESS、READ_ONLY、CUSTOM 等模式和操作类别规则；不再使用旧 WORKSPACE 或 READ_ONLY=ASK 语义。工具启用、系统能力、文件 scope 和操作授权分别检查，禁写同时约束 Shell；网络和闭合元数据边界以现行 ADR 为准。

用户授权由确定性规则解析，模型和外部内容不能自授权。需要询问时显示可审阅的动作及范围；目标、参数或规则变化不得沿用失效批准。Plan 审阅不授予全部工具权限；证明生命周期和递归删除的具体判定只由权限 ADR 维护。

规范见[会话授权](../adr/permissions/001-session-authorization.md)、[执行审计](../adr/permissions/003-dispatch-and-audit.md)；HXA-209 已按[完成记录](../completion-records/HXA-209.md)交付，完成记录不等于签名发行或全量发布验收。工具说明不授予权限，询问行为统一按此处和 ADR 判定。

### 5.6 Workspace

- 每个会话具有请求绑定的工作目录，可选择应用私有、已授权本地或 SAF 来源；按后端能力原位操作，不默认复制外部目录。附件副本、执行映射和成果发布与原目录身份分开。
- 相对路径必须规范化；拒绝绝对路径、`..` 越界、符号链接越界和特殊设备文件。
- 文件、工具输出与存储配额按[Workspace 规范](workspace.md)及对应工具契约分别限制，不把旧固定配额套用于全部后端。
- 大文件必须有界分页或分块处理；具体读取参数与执行器输入限额由 Workspace／Runtime 契约维护，不因单次任务提高限制绕过。
- 支持原子替换的后端使用临时文件和一致性校验；SAF 等后端如实报告能力与失败，不能把多步写入宣称为原子事务。
- 删除按后端实际支持的回收/物理删除方式执行，显示真实范围和后果，不承诺所有来源均有回收站。
- All files access 只扩大 Android 能访问的共享存储，Agent 仍只能操作用户在 Helix 内选择的 roots。
- `Full Workspace Access` 只表示用户选择了较宽的文件 roots；它不扩大到其他 App 私有目录、系统设置、凭据或未知工具，也不替代逐调用动态风险与 Policy。
- 外部导出必须由 SAF、明确的文件管理动作或已审批 scope 写入完成。

### 5.7 本地代码执行

- QuickJS 仅接收 UTF-8 JavaScript 源码和 JSON 输入。
- 默认使用无特权桥的 isolated process；用户显式开启原生总开关后可选应用 UID 私有进程，文件/网络/Java/Android 原生能力不构成三类独立隔离。
- 具体输入、堆、输出和期限由当前代码执行契约限制；生成代码始终不在主应用/UI 进程执行。
- PRoot 规范详见 [本地代码执行方案](../architecture/overview.md#本地执行域与恢复)。

### 5.8 浏览器与网页工具

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-WEB-001 | P1 | 内置多标签 WebView 浏览器 | 地址、前进后退、刷新停止、查找、分享、下载和数据清除 |
| FR-WEB-002 | P1 | 页面语义 snapshot | 大小有界；页面内容标记不可信；node token 有 TTL |
| FR-WEB-003 | P1 | 受控 click/type/scroll | token 绑定 tab/origin/navigation generation；导航后失效 |
| FR-WEB-004 | P1 | 下载到 Workspace/授权目录 | 重定向、大小、MIME、文件名和冲突策略受控 |
| FR-WEB-005 | P1 | 敏感网页动作拒绝 | 密码、验证码、支付、授权和账号恢复不自动操作 |

### 5.9 MCP、A2A 与 Skills

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-MCP-001 | P1 | MCP Streamable HTTP Client | initialize/list/call/cancel/close 和认证错误完整 |
| FR-MCP-002 | P1 | 动态能力启用 | 新 server 默认全部禁用，用户选择后才进 Tool Registry |
| FR-MCP-003 | P1 | MCP 动态工具安全 | namespace + schema hash；annotation 不能降低风险 |
| FR-MCP-004 | P2 | PRoot stdio MCP | 固定 server command、严格 stdout、bounded stderr |
| FR-MCP-005 | P1 | Resource/Prompt 发现 | Resources 支持有界发现、模板和文本/二进制读取；二进制走宿主产物，不注入 base64。Prompts 不自动注册为可信指令，详见 MCP ADR |
| FR-A2A-001 | P1 | A2A v1.0 Client 与 Agent Card 发现 | Android API 29/36 完成 HTTPS、JSON-RPC 或 HTTP+JSON、SSE、取消、版本协商与 R8 Spike；SDK 类型不泄漏 core |
| FR-A2A-002 | P1 | A2A Agent/Skill 启用与快照 | endpoint 默认 disabled；用户连接测试并启用 Skill；Agent Card、接口和 Skill hash 变化后旧 Tool/审批失效 |
| FR-A2A-003 | P1 | A2A Task 生命周期 | SendMessage/stream、GetTask、CancelTask 和 SubscribeToTask 映射为持久状态；断线/重启查询原 task ID，不自动重发不明确请求 |
| FR-A2A-004 | P1 | A2A 内容与 Artifact 桥接 | 首版支持有界 text、structured data 和 Workspace Artifact 副本；输入/输出 mode 不匹配明确失败，远端内容标记不可信 |
| FR-A2A-005 | P1 | A2A 授权与数据去向 | auth 只从 SecretStore 注入；Policy 绑定 agent ID、规范 origin、Agent Card/Skill snapshot、数据类别和 scope；远端不能调用本机 Tool 或生成 Approval Proof |
| FR-SKILL-001 | P1 | Agent Skills 规范兼容 | `SKILL.md` frontmatter 和目录校验通过官方 fixture |
| FR-SKILL-002 | P1 | 渐进加载 | 启动只载 name/description，激活后载正文，资源按需读取 |
| FR-SKILL-003 | P1 | 本地导入和管理 | zip/目录校验、内容 hash、启停、移除、版本快照 |
| FR-SKILL-004 | P1 | Skill 不授权 | scripts 通过现有受控执行工具及当前授权解析，不因 Skill 声明获得批准 |

### 5.10 Accessibility 与 Root

| ID | 优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FR-UI-001 | P2 | 用户主动启用 Accessibility | 权限中心说明、系统设置、目标包 allowlist、停止入口 |
| FR-UI-002 | P2 | UI snapshot、节点与截图动作 | token/frame 绑定目标、窗口、代次和后端；坐标动作须来自当前实际观察 |
| FR-UI-003 | P2 | 自动化执行边界 | 当前插件选择与范围持续校验；不恢复旧 5 分钟/30 动作或每 10 动作确认，取消不提前释放物理容量 |
| FR-UI-004 | P2 | 敏感数据与系统边界 | 按当前权限 ADR 区分密码/锁屏、公开系统选项与受授权动作，不以应用类别替代实际准入 |
| FR-ROOT-001 | P2 | 用户主动请求 Root | 区分 unavailable/denied/granted/lost，不启动时弹授权 |
| FR-ROOT-002 | P2 | 高层 Root 工具 | status、受限读、包/进程/日志；短时 RootSession |
| FR-ROOT-003 | P2 | 通用 Root 命令限制 | `root.exec` 默认不进 Agent Registry，仅显式受控入口，不能由工具名称获得权限 |

### 5.11 商店、官网分发与能力降级

- Google Play、选定国内 Android 应用商店和官网都是正式目标渠道；产品界面不向用户提供能力档位开关；Consumer 为 Standard，Developer 为 Advanced，能力由渠道固定。
- Standard 必须保留 Provider/BYOK、Goal、Workspace/SAF、文件工作台、Browser、QuickJS、MCP/A2A/Skills、Android 基础工具、模板、恢复和审计，不得退化为聊天壳。
- All-files、Accessibility、解释脚本、Tasker 等能力优先通过核心用途声明、显著披露、用户同意和渠道审核保留；只有明确政策条款或真实审核结果要求时，才对受影响渠道做最小替换或移除。
- Google Play 当前不允许使用 Accessibility 自主发起、规划并执行 UI 动作；Play artifact 只提供审核允许的确定性、用户可理解自动化。该限制不扩展为官网或其他合法渠道的永久 Standard 限制。
- Google Play 若以文件/文档管理核心用途申报 All-files，产品首页、商店描述和真实功能必须一致；未获批准时回退 SAF/MediaStore，但保留完整文件工作台。
- PRoot/Subscriptions 在 developer 单 APK 的私有进程内，consumer 排除组件制品；渠道限制只影响对应 Capability，不删除无关能力。
- 当前 consumer/developer flavor 和 applicationId 保持工程事实；HXA-122 决定稳定主 ID、渠道命名与升级路径，不能仅靠文档宣称跨 ID 原地升级。
- 未安装 Runtime、未 Root、未开启 All-files/Accessibility、未配置 MCP 或未连接并启用 A2A Agent 时，相关工具不进入模型工具表，其余能力正常运行。
- “以上架为目标”不等于保证第三方审核通过；只有真实提交、审核通过和可下载证据才能标记为已上架。每个国内目标商店在提交时单独核验，不套用 Google Play 或其他商店结论。

## 6. 非功能需求

| ID | 类型 | 指标 |
| --- | --- | --- |
| NFR-001 | 正确性 | 固定 40 条支持场景端到端成功率至少 80%；保留目标，不用测试总数替代任务结果 |
| NFR-002 | 工具可靠性 | 确定性工具在有效输入上的成功率至少 95% |
| NFR-003 | 安全 | 自动化测试中未经当前规则授权的执行次数为 0，明确 DENY 与工具禁用不可绕过 |
| NFR-004 | 恢复 | Activity 重建、进程终止后无已提交消息丢失 |
| NFR-005 | 性能 | 非模型 UI 操作 P95 小于 200 ms；冷启动目标小于 2.5 s |
| NFR-006 | 稳定性 | 24 小时交互测试无未处理崩溃、无 Agent Loop 泄漏 |
| NFR-007 | 隐私 | API Key、完整 Authorization Header 不出现在日志、Room、崩溃报告 |
| NFR-008 | 可观察性 | 每个 Turn、ToolCall、Approval、Execution 有 correlation ID |
| NFR-009 | 可测试性 | core 模块 JVM 单元测试；Android 边界用 instrumentation 测试 |
| NFR-010 | 可维护性 | 无循环模块依赖；UI 不直接访问 DAO、HTTP 或执行器 |
| NFR-011 | 渠道固定能力 | Consumer 固定 Standard，Developer 固定 Advanced，不提供运行档位选择；能力可用不自动授予系统/Root/范围权限，不在启动或被动查看时安装 Runtime 或连接新端点 |
| NFR-012 | 单一产品 | 渠道 artifact 共享产品定位、数据模型与核心任务矩阵；能力固定于渠道，不保留应用内 Standard/Advanced 切换或相关偏好兼容链 |
| NFR-013 | Runtime 生命周期 | 同 APK 私有 Runtime 无需预先打开/常驻即可冷绑定；进程回收后可重连，未知执行结果不自动重放 |

NFR-001 与 NFR-002 的结果必须可重算：[M10 固定集](../../evals/m10/README.md) 已有 45 条冻结场景，不是“没有固定评测”。该集合包含能力、拒绝和恢复验证，不自动等于 NFR-001 的 40 条支持任务。验收报告需明确具名任务映射、输入/oracle、渠道/插件/Provider 适用性、执行次数、正确产物、失败分类与原始证据；尚未冻结这份映射或缺少当前实际运行时，标记指标未验收，不改分母或用 Turn 完成/构建通过凑足成功率。安全拒绝与零绕过单独作为硬闸，不当作完成用户任务的分子。本轮未运行设备或真实模型评测。

## 7. 页面需求

1. 首次启动：隐私说明、Provider 配置、连接测试；能力按渠道固定，不向用户提供运行档位或工程 flavor 选择。
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
12. MCP、A2A 与 Skills：MCP Server/A2A Agent 连接、动态能力启停、A2A Task 恢复、Skill 导入/校验/快照。
13. 运行配置：语言可选跟随系统/简体中文/English；渠道允许时展示 Standard/Advanced、分能力启用状态、scope/规则期限和一键撤销；Standard 本身必须是完整可用产品。
14. Agent 模式与 Goal：模式切换、Plan、验收条件、预算和检查点。
15. 高级能力：Accessibility、Root、Linux/CLI Runtime 的独立风险页和停止/卸载入口。

首版 UI 默认跟随系统语言，提供简体中文和英文资源，并允许用户在 App 内覆盖为简体中文或 English；用户可见字符串不得硬编码在 Kotlin/Compose 中。Tool 名、schema/协议字段、稳定错误码、审计类型和持久化 enum 不随 UI 语言翻译或改变。

## 8. 数据保留与删除

- 用户可以删除会话记录；真实目录、外部文件及仍被其他会话/任务/产物引用的内容按[工作目录删除与清理规则](workspace.md#删除与恢复)独立处理，不随会话删除一并推定可清理。
- Provider API Key 单独删除，不随会话导出。
- 日志保留与清理遵循[执行与审计契约](../adr/permissions/003-dispatch-and-audit.md)，可用设置以当前实现为准。
- PRoot RootFS 可独立卸载；订阅协议实验 Runtime 可单独 logout/清除或卸载，卸载会删除其 adapter vault 凭据。
- 导出诊断包必须先脱敏，并允许用户预览文件列表。

## 9. 产品验收场景

至少覆盖：

1. JSON 排序并生成 Markdown。
2. CSV 聚合并输出新 CSV。
3. 批量重命名建议，只预览不执行。
4. 修改文本并生成 Diff。
5. 执行无限循环 JavaScript，被超时终止。
6. 默认隔离 JavaScript 尝试访问 Android API 时失败；显式原生模式按其授权契约单独验证。
7. 默认隔离 JavaScript 尝试联网时失败；显式原生模式不得绕过当前网络授权。
8. 删除文件缺少当前规则要求的授权时失败；已允许的操作不因旧风险等级重复询问。
9. 审批参数被模型修改，旧审批失效。
10. 模型流中途断开，任务进入可重试失败态。
11. 用户取消模型请求。
12. 用户取消代码执行。
13. 进程被杀后恢复会话，无工具重复执行。
14. 导入同名文件产生明确冲突策略。
15. 路径穿越、符号链接越界被拒绝。
16. Standard 拒绝私网/loopback；Advanced 仅允许预建的精确 origin scope；metadata 地址始终拒绝。
17. 通知权限关闭时返回可操作错误，而不是空成功。
18. 日历写入前展示最终时区与时间。
19. PRoot 下载哈希不匹配时拒绝激活。
20. RootFS 更新失败后回滚旧版本。
21. All files access 已授予但 scope 外路径仍被拒绝。
22. WebView 页面含 prompt injection 时不能注册工具或读取其他文件。
23. 页面导航后旧 browser node token 失效。
24. Mobile Use 的目标或观察身份变化时重新校验；范围外、锁屏或失效授权拒绝，未知效果不重放。
25. Root 管理器拒绝授权时返回 `Denied`，不以存在 `su` 冒充成功。
26. MCP Server 修改 tool schema 后旧审批失效。
27. 恶意 Skill zip 路径穿越和压缩炸弹被拒绝。
28. A2A Agent Card/Skill 或 endpoint 变化后旧工具与审批失效；SSE 断线、取消或重启只对账原 task ID，远端结果不能触发本机未审批动作。
29. Plan 拒绝用户文件修改及外部副作用；受支持的只读调用和闭合元数据例外按权限 ADR 验证，不仅凭工具名判定。
30. Goal 预算耗尽进入 BLOCKED，调整预算后显式恢复；用户暂停与系统中断停驻，重启不重新激活或重复副作用。
31. Ollama/SGLang 不支持某协议字段时明确降级，不静默丢 ToolCall。
32. PRoot/CLI 已安装但进程未运行且从未手动打开时，批准 Job 能冷绑定并完成握手；空闲回收后下一 Job 仍可执行。
33. Runtime 在 RUNNING/terminal commit 边界被杀后只按 jobId 对账；未知结果进入 `INTERRUPTED`，命令不重复执行。
34. 工具按物理容量与具体不可重入资源有界并行，写入或终端不施加全局串行锁；模型收到结果顺序与原始 call sequence 一致。
35. 并行队列取消时，未启动调用留下 `CANCELLED_BEFORE_START`，已启动调用有 terminal/unknown outcome，重启不自动重放。
36. 调度失败不能回退到更低隔离 target、扩大 scope/权限或先联网后补审批。
37. 若未来启用 child delegation，child 只读、深度 1、共享父预算且无 Approval Proof/Secret；写入 proposal 必须由父 Turn 重新审批。
38. 选择或分享附件只导入本地会话，不自动发送；发送前能移除并看到目标 Provider 与数据类别。
39. 文本附件在进程重启后仍绑定同一 hash；文件缺失或变化时旧发送/出网决定失效，不能读取替代字节。
40. Provider 不支持或未确认 vision 时图片保留本地并给出可操作错误，不能静默丢图或把 base64 文本当作成功。
41. 恶意图片、解码取消或低内存不得造成 OOM、越界读取、孤儿临时文件或正文/base64 日志泄露。
42. UTF-16、PDF/PPT/DOC、音频、视频和未知二进制附件统一返回 `UNSUPPORTED_ATTACHMENT_TYPE` 与正确的封闭 category；不得启动解析、渲染/OCR、媒体解码/抽帧/音轨/转码、Provider upload、模型 context/base64 或派生 Artifact。
43. 用户在 API 29/36 选择“跟随系统 / 简体中文 / English”后 UI 立即更新且重启仍保持；切换语言不改变 Provider 请求、Tool schema、稳定错误码、审计字段或用户/外部 Agent 内容。

## 10. 成功定义

首次单机正式版完成的标志不是“可以聊天”，而是：用户能给出一个涉及网页、文件或手机 UI 的目标，Helix 能选择 Provider、加载所需 Skill/MCP/A2A 能力、生成计划、请求必要审批、在正确的本地执行域中运行工具或代码、基于真实输出验证产物，并留下完整可审计记录；基础任务全过程不依赖电脑或远程 Worker，A2A 只是用户显式配置的可选外部 Agent 服务。

可持续跟踪的产品指标见[竞品分析指标](competitive-landscape.md#8-后续跟踪指标)；这些指标是里程碑/季度跟踪框架，不替代[安全、测试与发布门禁](../security/testing-and-release.md)的硬性验收。

## 11. 需求变更记录

### 2026-09-03：国际化与 App 语言切换拆分为独立 HXA

- 所有者请求：为当前 App 安排独立国际化 HXA，不再把全局资源化附属在语音输入任务中。
- 纳入需求：HXA-069 负责完整 fallback/English/简体中文资源、“跟随系统 / 简体中文 / English”App 内切换与持久化、API 29/36 兼容、用户可见硬编码/翻译键 CI 门禁和 locale 不影响机器契约的回归测试。
- 任务调整：HXA-067 仅保留语音输入并先跟随系统 locale；HXA-069 后续负责资源化，并将语音识别默认语言接到当前 App locale。
- 实现状态：本次只安排需求、路线和验收门禁；当前仍只有 `app_name` 资源，没有 App 语言切换或中英文验收证据。

### 2026-09-03：M7 增加 A2A Client

- 所有者请求：在 M7 的 MCP 与 Skills 之外加入已成为主流互操作协议的 A2A。
- 纳入需求：A2A v1.0 Client、Agent Card/Skill 发现与启用、`a2a.<agent>.<skill>` Tool bridge、持久 Task/Artifact/流式事件、断线对账和 Android API 29/36/R8 验证；HXA-077～079 已完成对应 Client-only 交付。
- 产品边界：A2A 与 MCP 互补而非替代；Helix 仍是本机父 Agent，A2A endpoint 是用户配置的外部服务，不是 `ExecutionTarget`/远程 Worker。M7 不托管 A2A Server/webhook，不开放远端直接 ToolCall、任意 peer 通信、递归多 Agent 或凭据/批准继承；这是首阶段边界而非永久否定。后续可按“前台 LAN Server → 用户自备 VPN/隧道/relay → proposal 型反向调用 → 单独决定有界远端 scope”演进。
- 阶段理由：普通手机的公网入站受 NAT/动态网络和 Android 后台生命周期约束；递归编排还需要预算、循环、级联取消和恢复合同。Advanced 用户可以承担网络入口与外部基础设施的选择责任，但不能替代可达性、任务状态和重复副作用语义。
- 决定状态：项目所有者于 2026-09-04 接受 ADR-A2A-001 的 Client-only 产品、协议和信任边界。HXA-077 选择自有 OkHttp/kotlinx.serialization 最小 Client（官方 Java SDK 仅保留 Spike），HXA-078/079 已交付 Agent 配置/发现、Task Tool bridge、持久恢复与 API29/36 专项模拟器证据；物理真机、真实第三方 Agent/代理网络和签名发行仍按发布/外部验收边界单独记账。

### 2026-09-02：未来自动化兼容与 Advanced 授权

- 所有者请求：未来兼容任意 Tasker/Auto.js 脚本；把 Shizuku/ADB 保留为可能支持但暂不实现；为 Advanced 增加长期工具授权、Trusted Workspace、自动批准、Full Access 与模型自授权。
- 纳入需求：Tasker 官方插件桥接、任意来源 Auto.js 脚本导入与兼容诊断、独立兼容 Runtime、Shizuku/无线 ADB 未来研究；Trusted Workspace、动态风险不高于 L1 的有界长期工具规则、精确批量批准和作为文件 scope 的 `Full Workspace Access`。
- 会话级显式授权按 ADR-PERMISSIONS-001 与 HXA-209 实施；允许用户选择完整访问，不允许模型自授权，不把同 UID 执行域描述成沙箱。
- 实现状态：本次仅修改需求、架构和决定记录；没有新增 Runtime、Tasker/Auto.js/Shizuku/ADB 代码，也没有把候选能力分配给当前 HXA。

### 2026-09-03：会话附件范围

- 所有者决定先规划 UTF-8 文本与图片附件；PDF/PPT/DOC 和视频读取只保留未来方向，不实现解析、OCR、抽帧、音轨、转码或 Provider 原生上传。
- 当前任务只要求未支持媒体统一分类并稳定拒绝，避免把二进制 base64、文件管理器导入成功或未来能力写成模型已理解。
- 系统分享只创建可预览的本地草稿，不自动发送；原始 Android URI 不进入模型、审计和诊断。

### 2026-09-02：Standard 商店产品边界

- 所有者决定：Standard 以 Google Play 和国内 Android 应用商店上架为产品边界，不为抽象安全偏好或未经核验的准则限制过多能力。
- 纳入需求：Standard 成为完整核心产品；All-files、Accessibility、解释脚本等优先通过核心用途声明、披露、同意和审核保留；渠道仅因明确政策或真实审核反馈做最小差异。
- 外部硬约束：Google Play 当前禁止 Accessibility 驱动的 Agent 自主规划执行，但允许狭窄、用户可理解的确定性自动化；外部 DEX/JAR/native executable 下载也受禁止。这些限制只作用于相关渠道，不扩大成所有 Standard 版本的永久限制。
- 证据边界：上架是明确目标，不是已完成事实；只有真实提交、审核通过和可下载记录才能声称已上架。分发决定见 [ADR-PLATFORM-001](../adr/platform/001-distribution.md)。
