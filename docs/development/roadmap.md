# 开发任务索引

任务范围与验收只在未完成任务文件维护；已完成任务只链接交付证据。编号不是强制执行顺序，不补造空号。当前优先级见[实施状态](status.md)，通用规则见[实施指南](implementation-guide.md)及[验收规则](verification-matrix.md)。

当前保留13项未闭合义务：3项收尾验收、1项待实现、2项集成验收、3项待决策、4项发行队列。没有仅因缺完成文件而要求重做已有功能，也没有把撤销旧范围伪装为测试通过。尚未立项的 proposed ADR 不计入 HXA 任务数。

## 能力交付取舍

遵循[能力规划规则](implementation-guide.md#应用能力与-agent-工具的规划规则)：191搜索/主题、204恢复、205准备是应用功能；195实时输出是宿主协议与展示；207复用扩展与统一工具管线；206验收这些边界。任务数量和默认顺序不因本次规则增加或重排，已授权并行切片仍按其所有权执行。

后续业务扩展优先完善MCP/Connector/Skill接入与现有通用工具；新增原生工具必须有模型主动调用的具体需求。JSONL 已获所有者实施授权并独立立项 HXA-211，首版为用户主动导出；生产子Agent、同Turn转向、通用hooks与新编排框架不因对标而自动进入批次B/C。此取舍不撤销已接受设计，也不关闭既有验收义务。

工具按需曝光的候选优化见[建议文档](../research/tool-exposure-optimization.md)：先测量、再评审立项，不改变当前任务依赖或验收状态。

## 执行顺序与依赖

209 会话授权与192 Plan闭环已交付，保留回归，不再列为开发前置；统一禁网已撤下，不恢复该承诺。以下是产品优先级，不把可独立任务误写为技术依赖。单个执行者同一时间只推进一个 HXA。

| 批次 | 默认顺序 | 用户可验收结果 |
| --- | --- | --- |
| A：看懂任务、拿到结果 | **202 → 194 → 203** | 会话进入对应任务，准确显示等待/取消/结束，打开命令详情及任务产物，并能返回来源；194交付时补验202入口 |
| B：能恢复、能准备 | **204 → 205**；193已收尾 | 区分重连/对账/继续/重试，新用户完成准备，初始化失败可修复；193提供干净资产、升级及CI证据 |
| C：过程可见、扩展可用 | **207 → 191**（195已交付） | 长命令结束前可见真实输出；扩展完成添加到实际调用；会话搜索与深色主题可用 |
| D：核心路径验收 | **206** | 汇总A～C、已交付192/209及自身验收项，不等待完整终端 |

- **批次A（202→194→203）与批次B（204→205，193已收尾）已全部交付**：完整用户路径证据（会话→任务→命令详情/产物→来源）与恢复/首次准备证据见各完成记录；C批次的207、191、195均已交付，197单终端也已交付，211 也已交付，后续按 status 整理 198 与集成验收计划。
- **提前设计206验收**：从A开始用206固定场景记录fixture、操作步骤、失败/跳过、设备与制品身份；各HXA实现时补相应证据。206仍是最终集成验收，不提前标完成，不以单项绿代替集成。
- **独立任务的调整规则**：194技术上不等待202；205不等待193远端CI，但实际初始化故障必须修；195依赖194展示而不等待后台Job/PTY。191可在完整HXA边界提前穿插，不抢占进行中的任务。193的账号/远端条件缺失独立记账，不伪造通过或阻塞无依赖工作。
- **后续执行环境链**：196后台Job、197手动PTY、198多会话、199专项验收；197不必等待196。193、194、195不因归属Runtime而一起后移；207不等待完整终端。
- **条件队列（不是自动启动）**：190真实订阅、125受保护Connector按设备/账号条件收尾；126/129/130仍待ADR决定，保留并行工作所有权。
- **发行队列**：120渠道审计→122身份/签名与升级→121候选包验收→123准备与获准后的提交。可以复用206/199证据，不能用构建成功替代发行证明。

各任务的前置条件、现有实现、移出范围和验收以任务文件为准；仅有外部证据缺口不应阻塞无依赖的本地开发。复核依据见[本轮记录](../evidence/development/open-hxa-review-2026-09-16.md)。

| 任务 | 分类 | 范围 | 规格或证据 |
| --- | --- | --- | --- |
| HXA-001 | 已交付 | Gradle 多模块工程 | [交付证据](../completion-records/M0.md) |
| HXA-002 | 已交付 | 质量和供应链门禁 | [交付证据](../completion-records/M0.md) |
| HXA-003 | 已交付 | AppContainer 与导航壳 | [交付证据](../completion-records/M0.md) |
| HXA-010 | 已交付 | 领域 ID、错误和执行目标 | [交付证据](../completion-records/HXA-010.md) |
| HXA-011 | 已交付 | Turn reducer | [交付证据](../completion-records/HXA-011.md) |
| HXA-012 | 已交付 | PlanArtifact 与模式策略 | [交付证据](../completion-records/HXA-012.md) |
| HXA-013 | 已交付 | Goal reducer 与预算 | [交付证据](../completion-records/HXA-013.md) |
| HXA-014 | 已交付 | Room schema 与 Repository | [交付证据](../completion-records/HXA-014.md) |
| HXA-015 | 已交付 | 恢复协调器 | [交付证据](../completion-records/HXA-015.md) |
| HXA-016 | 已交付 | Context Builder | [交付证据](../completion-records/HXA-016.md) |
| HXA-020 | 已交付 | SecretStore 与 Provider 配置 | [交付证据](../completion-records/HXA-020.md) |
| HXA-021 | 已交付 | 内部 ModelRequest/ModelEvent | [交付证据](../completion-records/HXA-021.md) |
| HXA-022 | 已交付 | OpenAI Responses adapter | [交付证据](../completion-records/HXA-022.md) |
| HXA-023 | 已交付 | OpenAI Chat Completions adapter | [交付证据](../completion-records/HXA-023.md) |
| HXA-024 | 已交付 | Anthropic Messages adapter | [交付证据](../completion-records/HXA-024.md) |
| HXA-025 | 已交付 | Provider 连接与能力探测 | [交付证据](../completion-records/HXA-025.md) |
| HXA-026 | 已交付 | 模板目录 | [交付证据](../completion-records/HXA-026.md) |
| HXA-027 | 已交付 | 自建服务真机 smoke | [交付证据](../completion-records/HXA-027.md) |
| HXA-028 | 已交付 | 聊天 UI | [交付证据](../completion-records/HXA-028.md) |
| HXA-030 | 已交付 | ToolDescriptor、Registry 和 ToolSource | [交付证据](../completion-records/HXA-030.md) |
| HXA-031 | 已交付 | JSON Schema 子集 | [交付证据](../completion-records/HXA-031.md) |
| HXA-032 | 已交付 | Capability Center | [交付证据](../completion-records/HXA-032.md) |
| HXA-033 | 已交付 | Policy Engine | [交付证据](../completion-records/HXA-033.md) |
| HXA-034 | 已交付 | Approval hash 与一次性消费 | [交付证据](../completion-records/HXA-034.md) |
| HXA-035 | 已交付 | Dispatcher 与审计 | [交付证据](../completion-records/HXA-035.md) |
| HXA-036 | 已交付 | Timeline 和审批卡 | [交付证据](../completion-records/HXA-036.md) |
| HXA-037 | 已交付 | 确定性 Tool Scheduler 与交互 receipt | [交付证据](../completion-records/HXA-037.md) |
| HXA-038 | 已交付 | 模型流状态合同与 ChatService 第一阶段拆分 | [交付证据](../completion-records/HXA-038.md) |
| HXA-039 | 已交付 | 批量语义 Turn Coordinator | [交付证据](../completion-records/HXA-039.md) |
| HXA-040 | 已交付 | WorkspacePath 和 FileScopePath | [交付证据](../completion-records/HXA-040.md) |
| HXA-041 | 已交付 | Artifact、配额和原子文件操作 | [交付证据](../completion-records/HXA-041.md) |
| HXA-042 | 已交付 | Pi 风格基础工具 | [交付证据](../completion-records/HXA-042.md) |
| HXA-043 | 已交付 | Copy/Move/Delete/Trash | [交付证据](../completion-records/HXA-043.md) |
| HXA-044 | 已交付 | SAF adapter | [交付证据](../completion-records/HXA-044.md) |
| HXA-045 | 已交付 | All files access | [交付证据](../completion-records/HXA-045.md) |
| HXA-046 | 已交付 | 文件管理 UI | [交付证据](../completion-records/HXA-046.md) |
| HXA-047 | 已交付 | Archive 工具 | [交付证据](../completion-records/HXA-047.md) |
| HXA-048 | 已交付 | 全项目审查后续收敛 | [交付证据](../completion-records/HXA-048.md) |
| HXA-049 | 已交付 | 会话附件导入、持久化与文本输入 | [交付证据](../completion-records/HXA-049.md) |
| HXA-050 | 已交付 | Zipline Spike | [交付证据](../completion-records/HXA-050.md) |
| HXA-051 | 已交付 | isolated Service/Binder | [交付证据](../completion-records/HXA-051.md) |
| HXA-052 | 已交付 | JS ABI 与限制 | [交付证据](../completion-records/HXA-052.md) |
| HXA-053 | 已交付 | `code.javascript.run` | [交付证据](../completion-records/HXA-053.md) |
| HXA-054 | 已交付 | 攻击和端到端测试 | [交付证据](../completion-records/HXA-054.md) |
| HXA-055 | 已交付 | 图片输入与 Provider 视觉能力 | [交付证据](../completion-records/HXA-055.md) |
| HXA-056 | 已交付 | 文本/图片附件端到端硬化与发布边界 | [交付证据](../completion-records/HXA-056.md) |
| HXA-057 | 已交付 | persisted SAF tree scope 接线 | [交付证据](../completion-records/HXA-057.md) |
| HXA-058 | 已交付 | 文件管理器导入/导出入口 | [交付证据](../completion-records/HXA-058.md) |
| HXA-059 | 已交付 | Provider 模型自动发现与选择 | [交付证据](../completion-records/HXA-059.md) |
| HXA-060 | 已交付 | 最小 WebView 浏览器 | [交付证据](../completion-records/HXA-060.md) |
| HXA-061 | 已交付 | Browser snapshot | [交付证据](../completion-records/HXA-061.md) |
| HXA-062 | 已交付 | Browser actions | [交付证据](../completion-records/HXA-062.md) |
| HXA-063 | 已交付 | Browser download | [交付证据](../completion-records/HXA-063.md) |
| HXA-064 | 已交付 | 分享、Intent 和剪贴板 | [交付证据](../completion-records/HXA-064.md) |
| HXA-065 | 已交付 | 通知和日历 | [交付证据](../completion-records/HXA-065.md) |
| HXA-066 | 已交付 | HTTP fetch 与前台任务 | [交付证据](../completion-records/HXA-066.md) |
| HXA-067 | 已交付 | 语音输入 | [交付证据](../completion-records/HXA-067.md) |
| HXA-068 | 已交付 | Advanced 有界出网规则管理 | [交付证据](../completion-records/HXA-068.md) |
| HXA-069 | 已交付 | 国际化与 App 语言切换 | [交付证据](../completion-records/HXA-069.md) |
| HXA-070 | 已交付 | MCP Kotlin SDK Android Spike | [交付证据](../completion-records/HXA-070.md) |
| HXA-071 | 已交付 | MCP Server 配置和握手 | [交付证据](../completion-records/HXA-071.md) |
| HXA-072 | 已交付 | MCP 动态 Tool bridge | [交付证据](../completion-records/HXA-072.md) |
| HXA-073 | 已交付 | MCP stdio bridge | [交付证据](../completion-records/HXA-073.md) |
| HXA-074 | 已交付 | Skill validator 和 catalog | [交付证据](../completion-records/HXA-074.md) |
| HXA-075 | 已交付 | Skill 导入和快照 | [交付证据](../completion-records/HXA-075.md) |
| HXA-076 | 已交付 | Skill 工具与首批内置 Skills | [交付证据](../completion-records/HXA-076.md) |
| HXA-077 | 已交付 | A2A v1.0 Android Client Spike | [交付证据](../completion-records/HXA-077.md) |
| HXA-078 | 已交付 | A2A Agent 配置、发现与快照 | [交付证据](../completion-records/HXA-078.md) |
| HXA-079 | 已交付 | A2A Task Tool bridge 与恢复 | [交付证据](../completion-records/HXA-079.md) |
| HXA-080 | 已交付 | Runtime manifest/许可证 schema | [交付证据](../completion-records/HXA-080.md) |
| HXA-081 | 已交付 | 构建期固定资产 | [交付证据](../completion-records/HXA-081.md) |
| HXA-082 | 已交付 | RootFS installer | [交付证据](../completion-records/HXA-082.md) |
| HXA-083 | 已交付 | 独立 Runtime APK/IPC | [交付证据](../completion-records/HXA-083.md) |
| HXA-084 | 已交付 | PRoot runner | [交付证据](../completion-records/HXA-084.md) |
| HXA-085 | 已交付 | `bash` Tool | [交付证据](../completion-records/HXA-085.md) |
| HXA-086 | 已交付 | 真机 smoke 与隔离 | [交付证据](../completion-records/HXA-086.md) |
| HXA-087 | 已交付 | 更新/卸载/法律页 | [交付证据](../completion-records/HXA-087.md) |
| HXA-088 | 已交付 | Git Workspace 语义 Spike 与 ADR | [交付证据](../completion-records/HXA-088.md) |
| HXA-090 | 已交付 | Accessibility Service 与权限中心 | [交付证据](../completion-records/HXA-090.md) |
| HXA-091 | 已交付 | UI snapshot/token | [交付证据](../completion-records/HXA-091.md) |
| HXA-092 | 已交付 | UI actions | [交付证据](../completion-records/HXA-092.md) |
| HXA-093 | 已交付 | Accessibility 攻击/恢复测试 | [交付证据](../completion-records/HXA-093.md) |
| HXA-094 | 已交付 | Root 依赖与授权生命周期验收 | [交付证据](../completion-records/HXA-094.md) |
| HXA-095 | 已交付 | Root 高层工具作用域与失权验收 | [交付证据](../completion-records/HXA-095.md) |
| HXA-096 | 已交付 | Root L3 控制台 | [交付证据](../completion-records/HXA-096.md) |
| HXA-097 | 已交付 | Android UI Skill | [交付证据](../completion-records/HXA-097.md) |
| HXA-099 | 已交付 | 模式、预算与资源降级运行控制 | [交付证据](../completion-records/HXA-099.md) |
| HXA-100 | 已交付 | 固定评测集 | [交付证据](../completion-records/HXA-100.md) |
| HXA-101 | 已交付 | Prompt injection | [交付证据](../completion-records/HXA-101.md) |
| HXA-102 | 已交付 | 恢复和副作用 | [交付证据](../completion-records/HXA-102.md) |
| HXA-103 | 已交付 | 资源/稳定性 | [交付证据](../completion-records/HXA-103.md) |
| HXA-104 | 已交付 | 隐私/诊断/删除 | [交付证据](../completion-records/HXA-104.md) |
| HXA-105 | 已交付 | 有界只读委托与声明式 Workflow Spike | [交付证据](../completion-records/HXA-105.md) |
| HXA-110 | 已交付 | CLI Runtime manifest 与独立 UID | [交付证据](../completion-records/HXA-110.md) |
| HXA-111 | 已交付 | Codex app-server 登录 Spike | [交付证据](../completion-records/HXA-111.md) |
| HXA-112 | 已交付 | Claude Code stream-json/SDK Spike | [交付证据](../completion-records/HXA-112.md) |
| HXA-113 | 已交付 | 工具与审批兼容性结论 | [交付证据](../completion-records/HXA-113.md) |
| HXA-114 | 已交付 | DSH subscription adapter 来源、凭据与条款 Spike | [交付证据](../completion-records/HXA-114.md) |
| HXA-115 | 已交付 | 独立 Runtime 凭据 vault | [交付证据](../completion-records/HXA-115.md) |
| HXA-116 | 已交付 | 订阅平台目录与 0.7.0 供应链重基线 | [交付证据](../completion-records/HXA-116.md) |
| HXA-117 | 已交付 | GitHub Copilot 官方 SDK/OAuth App Android Spike | [交付证据](../completion-records/HXA-117.md) |
| HXA-118 | 已交付 | Codex 第三方侧载 OAuth 登录生命周期 | [交付证据](../completion-records/HXA-118.md) |
| HXA-119 | 已交付 | GitHub Copilot Free 第三方侧载 Device Code 登录生命周期 | [交付证据](../completion-records/HXA-119.md) |
| HXA-137 | 已交付 | Claude Free 身份登录与 Claude Code 订阅资格门禁 | [交付证据](../completion-records/HXA-137.md) |
| HXA-138 | 已交付 | Grok Free 官方 Device Code 登录与套餐门禁 | [交付证据](../completion-records/HXA-138.md) |
| HXA-139 | 已交付 | Device Code 可用性、诊断与 Codex 官方兼容流 | [交付证据](../completion-records/HXA-139.md) |
| HXA-140 | 已交付 | Codex 订阅最小模型 Smoke 与实验边界收口 | [交付证据](../completion-records/HXA-140.md) |
| HXA-141 | 已交付 | Runtime 持久 Codex 模型 Job 内核 | [交付证据](../completion-records/HXA-141.md) |
| HXA-142 | 已交付 | 订阅协议实验停止线与注册门禁 | [交付证据](../completion-records/HXA-142.md) |
| HXA-143 | 已交付 | Developer 订阅 Provider 渠道门禁修正 | [交付证据](../completion-records/HXA-143.md) |
| HXA-131 | 已交付 | CLI Runtime 共享握手契约 | [交付证据](../completion-records/HXA-131.md) |
| HXA-132 | 已交付 | 主 App 冷绑定与状态握手 | [交付证据](../completion-records/HXA-132.md) |
| HXA-133 | 已交付 | 固定 Codex 跨 APK 持久 Job 控制面 | [交付证据](../completion-records/HXA-133.md) |
| HXA-134 | 已交付 | 有界模型载荷与对账删除 | [交付证据](../completion-records/HXA-134.md) |
| HXA-135 | 已交付 | Developer 对话 Provider 接入 | [交付证据](../completion-records/HXA-135.md) |
| HXA-136 | 已交付 | 订阅登录入口与真实对话验收 | [交付证据](../completion-records/HXA-136.md) |
| HXA-144 | 已交付 | Claude 订阅 Provider 与显式平台 Job 路由 | [交付证据](../completion-records/HXA-144.md) |
| HXA-145 | 已交付 | Grok 订阅 Provider | [交付证据](../completion-records/HXA-145.md) |
| HXA-146 | 已交付 | Copilot 订阅 Provider | [交付证据](../completion-records/HXA-146.md) |
| HXA-120 | 发行队列 | 渠道能力与最终制品审计 | [任务规格](tasks/HXA-120.md) |
| HXA-121 | 发行队列 | 候选发布包综合验收 | [任务规格](tasks/HXA-121.md) |
| HXA-122 | 发行队列 | 发行身份、签名与升级路径 | [任务规格](tasks/HXA-122.md) |
| HXA-123 | 发行队列 | 选定渠道提交准备与审核证据 | [任务规格](tasks/HXA-123.md) |
| HXA-124 | 已交付 | Connector 调研、插件导入与管理 | [交付证据](../completion-records/HXA-124.md) |
| HXA-125 | 收尾验收 | Connector 受保护服务与来源验收 | [任务规格](tasks/HXA-125.md) |
| HXA-126 | 待决策 | Connector OAuth 登录层 | [任务规格](tasks/HXA-126.md) |
| HXA-127 | 已交付 | 大 catalog 渐进工具发现 | [交付证据](../completion-records/HXA-127.md) |
| HXA-128 | 已交付 | CLI/stdio Connector 可移植性 Spike | [交付证据](../completion-records/HXA-128.md) |
| HXA-129 | 待决策 | Connector 所有权、版本与安装事务 | [任务规格](tasks/HXA-129.md) |
| HXA-130 | 待决策 | Connector 签名索引与来源验证 | [任务规格](tasks/HXA-130.md) |
| HXA-147 | 已交付 | 任务交互与移动界面统一 | [交付证据](../completion-records/HXA-147.md) |
| HXA-148 | 已交付 | Skill Creator 与草稿校验 | [交付证据](../completion-records/HXA-148.md) |
| HXA-149 | 已交付 | Skill Installer 与精确内容安装 | [交付证据](../completion-records/HXA-149.md) |
| HXA-150 | 已交付 | MCP Installer 与 Connector 导入接线 | [交付证据](../completion-records/HXA-150.md) |
| HXA-151 | 已交付 | Skill/MCP 原生操作状态收敛 | [交付证据](../completion-records/HXA-151.md) |
| HXA-152 | 已交付 | 浏览器原生引用短时归因 | [交付证据](../completion-records/HXA-152.md) |
| HXA-153 | 已交付 | JNI 引用与 Binder 代理释放追踪 | [交付证据](../completion-records/HXA-153.md) |
| HXA-154 | 已交付 | 浏览器生产路径引用核实 | [交付证据](../completion-records/HXA-154.md) |
| HXA-155 | 已交付 | 浏览器网络取消与 Activity 生命周期 | [交付证据](../completion-records/HXA-155.md) |
| HXA-156 | 已交付 | 状态与产品研究文档收口 | [交付证据](../completion-records/HXA-156.md) |
| HXA-157 | 已交付 | 工具发现被模型数量上限截断修复 | [交付证据](../completion-records/HXA-157.md) |
| HXA-158 | 已交付 | 未使用 WebView 宿主延迟分配与原生问题折中验证 | [交付证据](../completion-records/HXA-158.md) |
| HXA-159 | 已交付 | 大文件职责重构与浏览器 Context 评估 | [交付证据](../completion-records/HXA-159.md) |
| HXA-160 | 已交付 | Activity 级浏览器宿主与 Context 功能验证 | [交付证据](../completion-records/HXA-160.md) |
| HXA-161 | 已交付 | 真机会话输入区与推理参数接线 | [交付证据](../completion-records/HXA-161.md) |
| HXA-162 | 已交付 | 会话时间线与复制 | [交付证据](../completion-records/HXA-162.md) |
| HXA-163 | 已交付 | 草稿会话与目录归属 | [交付证据](../completion-records/HXA-163.md) |
| HXA-164 | 已交付 | 即时消息发布与 Markdown | [交付证据](../completion-records/HXA-164.md) |
| HXA-165 | 已交付 | 参考移动对话界面的视觉整理 | [交付证据](../completion-records/HXA-165.md) |
| HXA-166 | 已交付 | 会话内模型选择 | [交付证据](../completion-records/HXA-166.md) |
| HXA-167 | 已交付 | 单行横向选项栏 | [交付证据](../completion-records/HXA-167.md) |
| HXA-168 | 已交付 | 顶部空间与胶囊选项 | [交付证据](../completion-records/HXA-168.md) |
| HXA-169 | 已交付 | 顶部主菜单与标题编辑 | [交付证据](../completion-records/HXA-169.md) |
| HXA-170 | 已交付 | 导航样式与扩展入口修复 | [交付证据](../completion-records/HXA-170.md) |
| HXA-171 | 已交付 | 独立文件管理界面 | [交付证据](../completion-records/HXA-171.md) |
| HXA-172 | 已交付 | 归档列表、共享存储入口与上下文用量 | [交付证据](../completion-records/HXA-172.md) |
| HXA-173 | 已交付 | 归档恢复与共享根目录 | [交付证据](../completion-records/HXA-173.md) |
| HXA-174 | 已交付 | 窗口配置和上下文压缩 | [交付证据](../completion-records/HXA-174.md) |
| HXA-175 | 已交付 | 调试脚本留存、紧凑会话控件与系统栏避让 | [交付证据](../completion-records/HXA-175.md) |
| HXA-176 | 已交付 | 长 Turn / Goal 压缩实战强化 | [交付证据](../completion-records/HXA-176.md) |
| HXA-177 | 已交付 | 后台任务与 Goal 阻塞恢复 | [交付证据](../completion-records/HXA-177.md) |
| HXA-178 | 已交付 | 模型判断 Goal 完成 | [交付证据](../completion-records/HXA-178.md) |
| HXA-179 | 已交付 | ChatService 职责拆分 | [交付证据](../completion-records/HXA-179.md) |
| HXA-180 | 已交付 | 独立文件管理变更能力 | [交付证据](../completion-records/HXA-180.md) |
| HXA-181 | 已交付 | 当前规范清理 | [交付证据](../completion-records/HXA-181.md) |
| HXA-182 | 已交付 | 大类职责继续收敛与文件传输恢复 | [交付证据](../completion-records/HXA-182.md) |
| HXA-183 | 已交付 | 大类职责拆分 | [交付证据](../completion-records/HXA-183.md) |
| HXA-184 | 已交付 | 谨慎提取与文件装配整理 | [交付证据](../completion-records/HXA-184.md) |
| HXA-185 | 已交付 | 合并后长稳夹具适配与失败取证 | [交付证据](../completion-records/HXA-185.md) |
| HXA-186 | 已交付 | API35 真机隔离回归 | [交付证据](../completion-records/HXA-186.md) |
| HXA-187 | 已交付 | QuickJS 真机超时停滞修复 | [交付证据](../completion-records/HXA-187.md) |
| HXA-188 | 已交付 | 真机浏览器冻结、后台恢复与 Root 验证 | [交付证据](../completion-records/HXA-188.md) |
| HXA-189 | 已交付 | 第三轮审查复核与缺陷修复 | [交付证据](../completion-records/HXA-189.md) |
| HXA-190 | 收尾验收 | 订阅 Provider 能力与真实账号验收 | [任务规格](tasks/HXA-190.md) |
| HXA-191 | 已交付 | 深色主题与会话搜索 | [交付证据](../completion-records/HXA-191.md) |
| HXA-192 | 已交付 | Plan 审阅到执行的用户闭环验收 | [交付证据](../completion-records/HXA-192.md) |
| HXA-193 | 已交付 | 包内 Runtime 可复现资产与升级验收 | [交付证据](../completion-records/HXA-193.md) |
| HXA-194 | 已交付 | 命令详情与现有结果导航 | [交付证据](../completion-records/HXA-194.md) |
| HXA-195 | 已交付 | 有界日志协议与实时输出 | [交付证据](../completion-records/HXA-195.md) |
| HXA-196 | 收尾验收 | 有租期的独立后台命令 Job（真机待验） | [任务规格](tasks/HXA-196.md) |
| HXA-197 | 已交付 | 单个手动 PTY 终端 | [交付证据](../completion-records/HXA-197.md) |
| HXA-198 | 待实现 | 手动终端多会话与重连 | [任务规格](tasks/HXA-198.md) |
| HXA-199 | 集成验收 | 终端专项集成与交付 | [任务规格](tasks/HXA-199.md) |
| HXA-200 | 已交付 | 三态审批偏好与执行解析 | [交付证据](../completion-records/HXA-200.md) |
| HXA-201 | 已交付 | 工具设置与审批卡 | [交付证据](../completion-records/HXA-201.md) |
| HXA-202 | 已交付 | 任务过程与跨页面操作导航 | [交付证据](../completion-records/HXA-202.md) |
| HXA-203 | 已交付 | 产物可用性与文件交付闭环 | [交付证据](../completion-records/HXA-203.md) |
| HXA-204 | 已交付 | 跨执行域错误与恢复交互 | [交付证据](../completion-records/HXA-204.md) |
| HXA-205 | 已交付 | 首次配置与能力准备修复 | [交付证据](../completion-records/HXA-205.md) |
| HXA-206 | 集成验收 | 核心产品闭环综合验收 | [任务规格](tasks/HXA-206.md) |
| HXA-207 | 已交付 | 现有扩展来源的添加到使用闭环 | [交付证据](../completion-records/HXA-207.md) |
| HXA-208 | 已交付 | 完整 Goal 工具与前后台连续运行 | [交付证据](../completion-records/HXA-208.md) |
| HXA-209 | 已交付 | 会话授权预设、工具禁用与自定义权限 | [交付证据](../completion-records/HXA-209.md) |
| HXA-211 | 已交付 | 按会话导出 JSONL 执行记录 | [交付证据](../completion-records/HXA-211.md) |
