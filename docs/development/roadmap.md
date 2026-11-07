# 开发任务索引

按工作类型区分尚未完成的范围与已完成记录；每个 HXA 只出现一次。分类不是执行顺序，也不因登记而取得代码、设备、账号或发行授权。当前事实看 [status](status.md)，任务范围与验收以原 HXA 为准，未接受的建议看[候选索引](candidate-decisions.md)。

已实现但缺明确验收证据的任务归“缺陷修复与验证收尾”，不是重新开发。已结案的有界任务归完成记录，原失败、未请求与未覆盖边界仍保留；完成不等于全设备、所有模型或正式发行通过。

## 如何选择后续工作

先处理影响当前用户路径的确定缺陷与已承诺验收；功能新增独立选择可交付范围。结构工作只为实际耦合与消费者服务，沿[总体蓝图第 24 节](../architecture/harness-refactor-plan.md#24-总体架构蓝图与渐进重构路线)的当前基线和纵切推进，不重启已完成的 Engine、内容贡献或输入边界提取。功能与重构可在文件/契约不冲突时并行，不互相成为全局前置。

服务选型、真实账号、设备与签名条件只阻塞依赖它们的切片。未接受的新平台、子 Agent、远程和可编程扩展留在候选索引，不与已批准任务混排。方法与停止条件见[开发策略](feature-refactor-strategy.md)，本页不保留历史批次排期。

## 功能新增与能力扩展

已有基础不归零；网页 Fetch 与 Search 服务接线、MCP 正文、表单新动作及跨会话投递分别验收。

| 任务 | 状态 | 范围 | 规格或证据 |
| --- | --- | --- | --- |
| HXA-290 | 独立能力待实施；Search 服务选型待决 | 网页搜索与正文读取工作流 | [任务](tasks/HXA-290.md) |
| HXA-291 | 资源正文闭环待实施；认证连接不重做 | MCP Resources 发现、读取与引用 | [任务](tasks/HXA-291.md) |
| HXA-292 | 既有动作基线已交付；新表单能力与待验组合分开 | Browser 表单与 Mobile Use 动作契约 | [任务](tasks/HXA-292.md) |
| HXA-295 | A 已交付，301补有界运行；B/C 未实施 | 会话查询、管理与受控任务投递 | [任务](tasks/HXA-295.md) |

## 架构重构

总体剩余结构范围以蓝图 24.1 为准，包含 conversation 表现/应用层及 Room 编译边界；当前工作树已有相关迁移，须先核对实际交付，不从旧基线重复开发。蓝图不是另一个 HXA 队列；本表只列仍开放的原结构任务，已交付阶段在完成区。

| 任务 | 状态 | 范围 | 规格或证据 |
| --- | --- | --- | --- |
| HXA-288 | 部分基线已交付；局部接缝按证据实施 | 执行观察与产物接缝局部收敛 | [任务](tasks/HXA-288.md) |

## 缺陷修复与验证收尾

主分类为已有能力的缺陷、恢复或证据补齐；个别任务仍含小范围实现余项，必须读原规格，不把整项标为仅待测试。

| 任务 | 状态 | 范围 | 规格或证据 |
| --- | --- | --- | --- |
| HXA-232 | 实现已交付；恢复效果与故障矩阵待验 | 自主恢复与最小人工介入 | [任务规格](tasks/HXA-232.md) |
| HXA-234 | 收尾验收 | R2-A 中立 Core 与上下文等价迁移 | [任务规格](tasks/HXA-234.md) |
| HXA-235 | 收尾验收 | R3 插件安装、组件归属与会话生命周期 | [任务规格](tasks/HXA-235.md) |
| HXA-236 | 收尾验收 | J1 原执行身份观察与有界等待 | [任务规格](tasks/HXA-236.md) |
| HXA-237 | 收尾验收 | 订阅引导、Codex 客户端版本与移动终端体验 | [任务规格](tasks/HXA-237.md) |
| HXA-238 | 收尾验收 | 执行锁收窄与 Provider 表单反馈 | [任务规格](tasks/HXA-238.md) |
| HXA-239 | 收尾验收 | 输入框选模、指令光标与权限快捷切换 | [任务规格](tasks/HXA-239.md) |
| HXA-240 | 收尾验收 | Advanced Bash / Linux Job 复用 FFmpeg | [任务规格](tasks/HXA-240.md) |
| HXA-248 | 主机及有界模型旅程已交付；完整组合与恢复矩阵待验 | J2 同执行 AUTO 与用户后台释放 | [任务规格](tasks/HXA-248.md) |
| HXA-271 | 阶段实现与有界验收已交付；进程恢复/视觉等明确余项 pending | 会话执行交互与信息层级 | [任务](tasks/HXA-271.md) |
| HXA-283 | 旧 PDF 入口已由284接替；原卡验收余项未单独结案 | 有界 PDF 读取与总结 | [任务](tasks/HXA-283.md) |
| HXA-289 | 原生有界能力已交付；SAF／模型自主定位待验 | Workspace 路径匹配与文件内容搜索 | [任务](tasks/HXA-289.md) |
| HXA-309 | 主机/模拟器已交付；最终候选真机手测 pending | 本机备忘与任务提醒 | [任务](tasks/HXA-309.md) / [证据](../evidence/development/local-reminders-2026-10-19.md) |
| HXA-310 | 主机/模拟器/下载已交付；实际口述、外放听感及真机质量待手测 | 本机回复主动朗读 | [任务](tasks/HXA-310.md) / [证据](../evidence/development/read-aloud-2026-10-19.md) |

## 发行与外部条件

账号/服务或发行条件单列；Notion/Linear 已结束的既定测试不重开。正式发行依赖 120/122 的渠道与身份输出，再做 121 候选验收、123 提交；顺序不授权实际发布。

| 任务 | 状态 | 范围 | 规格或证据 |
| --- | --- | --- | --- |
| HXA-120 | 发行队列 | 渠道能力与最终制品审计 | [任务规格](tasks/HXA-120.md) |
| HXA-121 | 发行队列 | 候选发布包综合验收 | [任务规格](tasks/HXA-121.md) |
| HXA-122 | 发行队列 | 发行身份、签名与升级路径 | [任务规格](tasks/HXA-122.md) |
| HXA-123 | 发行队列 | 选定渠道提交准备与审核证据 | [任务规格](tasks/HXA-123.md) |
| HXA-125 | 条件性范围；Notion/Linear子范围已结案 | 其他Connector受保护服务与来源验收仅按另行选定范围推进 | [任务规格](tasks/HXA-125.md) |
| HXA-126 | 本地实现已交付；Notion/Linear真实服务子范围已结案 | 自有HTTPS/App Link与正式签名为独立发行条件；不重开已关闭账号测试 | [任务规格](tasks/HXA-126.md) |
| HXA-190 | 收尾验收 | 订阅 Provider 能力与真实账号验收 | [任务规格](tasks/HXA-190.md) |
| HXA-307 | 模型/账号及HTTP/WS有界接入已交付；语音严格质量 failed、服务/声学边界保留 | 内置云端模型、账号与语音接入 | [任务](tasks/HXA-307.md) |

## 已完成任务

仅保存索引，不复制验收流水。完整清单也可按标题查[完成记录索引](../completion-records/index.md)。原记录的时间点状态、失败及未验范围不改写为当前全绿。

| 任务 | 状态 | 范围 | 规格或证据 |
| --- | --- | --- | --- |
| HXA-001 | 已交付 | Gradle 多模块工程 | [交付证据](../completion-records/M0.md) |
| HXA-002 | 已交付 | 质量和供应链门禁 | [交付证据](../completion-records/M0.md) |
| HXA-003 | 已交付 | AppContainer 与导航壳 | [交付证据](../completion-records/M0.md) |
| HXA-010 | 已完成（原任务范围） | 领域 ID、错误和执行目标 | [完成记录](../completion-records/HXA-010.md) |
| HXA-011 | 已完成（原任务范围） | Turn reducer | [完成记录](../completion-records/HXA-011.md) |
| HXA-012 | 已完成（原任务范围） | PlanArtifact 与模式策略 | [完成记录](../completion-records/HXA-012.md) |
| HXA-013 | 已完成（原任务范围） | Goal reducer 与预算 | [完成记录](../completion-records/HXA-013.md) |
| HXA-014 | 已完成（原任务范围） | Room schema 与 Repository | [完成记录](../completion-records/HXA-014.md) |
| HXA-015 | 已完成（原任务范围） | 恢复协调器 | [完成记录](../completion-records/HXA-015.md) |
| HXA-016 | 已完成（原任务范围） | Context Builder | [完成记录](../completion-records/HXA-016.md) |
| HXA-020 | 已完成（原任务范围） | SecretStore 与 Provider 配置 | [完成记录](../completion-records/HXA-020.md) |
| HXA-021 | 已完成（原任务范围） | 内部 ModelRequest/ModelEvent | [完成记录](../completion-records/HXA-021.md) |
| HXA-022 | 已完成（原任务范围） | OpenAI Responses adapter | [完成记录](../completion-records/HXA-022.md) |
| HXA-023 | 已完成（原任务范围） | OpenAI Chat Completions adapter | [完成记录](../completion-records/HXA-023.md) |
| HXA-024 | 已完成（原任务范围） | Anthropic Messages adapter | [完成记录](../completion-records/HXA-024.md) |
| HXA-025 | 已完成（原任务范围） | Provider 连接与能力探测 | [完成记录](../completion-records/HXA-025.md) |
| HXA-026 | 已完成（原任务范围） | 模板目录 | [完成记录](../completion-records/HXA-026.md) |
| HXA-027 | 已完成（原任务范围） | 自建服务真机 smoke | [完成记录](../completion-records/HXA-027.md) |
| HXA-028 | 已完成（原任务范围） | 聊天 UI | [完成记录](../completion-records/HXA-028.md) |
| HXA-030 | 已完成（原任务范围） | ToolDescriptor、Registry 和 ToolSource | [完成记录](../completion-records/HXA-030.md) |
| HXA-031 | 已完成（原任务范围） | JSON Schema 子集 | [完成记录](../completion-records/HXA-031.md) |
| HXA-032 | 已完成（原任务范围） | Capability Center | [完成记录](../completion-records/HXA-032.md) |
| HXA-033 | 已完成（原任务范围） | Policy Engine | [完成记录](../completion-records/HXA-033.md) |
| HXA-034 | 已完成（原任务范围） | Approval hash 与一次性消费 | [完成记录](../completion-records/HXA-034.md) |
| HXA-035 | 已完成（原任务范围） | Dispatcher 与审计 | [完成记录](../completion-records/HXA-035.md) |
| HXA-036 | 已完成（原任务范围） | Timeline 和审批卡 | [完成记录](../completion-records/HXA-036.md) |
| HXA-037 | 已完成（原任务范围） | 确定性 Tool Scheduler 与交互 receipt | [完成记录](../completion-records/HXA-037.md) |
| HXA-038 | 已完成（原任务范围） | 模型流状态合同与 ChatService 第一阶段拆分 | [完成记录](../completion-records/HXA-038.md) |
| HXA-039 | 已完成（原任务范围） | 批量语义 Turn Coordinator | [完成记录](../completion-records/HXA-039.md) |
| HXA-040 | 已完成（原任务范围） | WorkspacePath 和 FileScopePath | [完成记录](../completion-records/HXA-040.md) |
| HXA-041 | 已完成（原任务范围） | Artifact、配额和原子文件操作 | [完成记录](../completion-records/HXA-041.md) |
| HXA-042 | 已完成（原任务范围） | Pi 风格基础工具 | [完成记录](../completion-records/HXA-042.md) |
| HXA-043 | 已完成（原任务范围） | Copy/Move/Delete/Trash | [完成记录](../completion-records/HXA-043.md) |
| HXA-044 | 已完成（原任务范围） | SAF adapter | [完成记录](../completion-records/HXA-044.md) |
| HXA-045 | 已完成（原任务范围） | All files access | [完成记录](../completion-records/HXA-045.md) |
| HXA-046 | 已完成（原任务范围） | 文件管理 UI | [完成记录](../completion-records/HXA-046.md) |
| HXA-047 | 已完成（原任务范围） | Archive 工具 | [完成记录](../completion-records/HXA-047.md) |
| HXA-048 | 已完成（原任务范围） | 全项目审查后续收敛 | [完成记录](../completion-records/HXA-048.md) |
| HXA-049 | 已完成（原任务范围） | 会话附件导入、持久化与文本输入 | [完成记录](../completion-records/HXA-049.md) |
| HXA-050 | 已完成（原任务范围） | Zipline Spike | [完成记录](../completion-records/HXA-050.md) |
| HXA-051 | 已完成（原任务范围） | isolated Service/Binder | [完成记录](../completion-records/HXA-051.md) |
| HXA-052 | 已完成（原任务范围） | JS ABI 与限制 | [完成记录](../completion-records/HXA-052.md) |
| HXA-053 | 已完成（原任务范围） | `code.javascript.run` | [完成记录](../completion-records/HXA-053.md) |
| HXA-054 | 已完成（原任务范围） | 攻击和端到端测试 | [完成记录](../completion-records/HXA-054.md) |
| HXA-055 | 已完成（原任务范围） | 图片输入与 Provider 视觉能力 | [完成记录](../completion-records/HXA-055.md) |
| HXA-056 | 已完成（原任务范围） | 文本/图片附件端到端硬化与发布边界 | [完成记录](../completion-records/HXA-056.md) |
| HXA-057 | 已完成（原任务范围） | persisted SAF tree scope 接线 | [完成记录](../completion-records/HXA-057.md) |
| HXA-058 | 已完成（原任务范围） | 文件管理器导入/导出入口 | [完成记录](../completion-records/HXA-058.md) |
| HXA-059 | 已完成（原任务范围） | Provider 模型自动发现与选择 | [完成记录](../completion-records/HXA-059.md) |
| HXA-060 | 已完成（原任务范围） | 最小 WebView 浏览器 | [完成记录](../completion-records/HXA-060.md) |
| HXA-061 | 已完成（原任务范围） | Browser snapshot | [完成记录](../completion-records/HXA-061.md) |
| HXA-062 | 已完成（原任务范围） | Browser actions | [完成记录](../completion-records/HXA-062.md) |
| HXA-063 | 已完成（原任务范围） | Browser download | [完成记录](../completion-records/HXA-063.md) |
| HXA-064 | 已完成（原任务范围） | 分享、Intent 和剪贴板 | [完成记录](../completion-records/HXA-064.md) |
| HXA-065 | 已完成（原任务范围） | 通知和日历 | [完成记录](../completion-records/HXA-065.md) |
| HXA-066 | 已完成（原任务范围） | HTTP fetch 与前台任务 | [完成记录](../completion-records/HXA-066.md) |
| HXA-067 | 已完成（原任务范围） | 语音输入 | [完成记录](../completion-records/HXA-067.md) |
| HXA-068 | 已完成（原任务范围） | Advanced 有界出网规则管理 | [完成记录](../completion-records/HXA-068.md) |
| HXA-069 | 已完成（原任务范围） | 国际化与 App 语言切换 | [完成记录](../completion-records/HXA-069.md) |
| HXA-070 | 已完成（原任务范围） | MCP Kotlin SDK Android Spike | [完成记录](../completion-records/HXA-070.md) |
| HXA-071 | 已完成（原任务范围） | MCP Server 配置和握手 | [完成记录](../completion-records/HXA-071.md) |
| HXA-072 | 已完成（原任务范围） | MCP 动态 Tool bridge | [完成记录](../completion-records/HXA-072.md) |
| HXA-073 | 已完成（原任务范围） | MCP stdio bridge | [完成记录](../completion-records/HXA-073.md) |
| HXA-074 | 已完成（原任务范围） | Skill validator 和 catalog | [完成记录](../completion-records/HXA-074.md) |
| HXA-075 | 已完成（原任务范围） | Skill 导入和快照 | [完成记录](../completion-records/HXA-075.md) |
| HXA-076 | 已完成（原任务范围） | Skill 工具与首批内置 Skills | [完成记录](../completion-records/HXA-076.md) |
| HXA-077 | 已完成（原任务范围） | A2A v1.0 Android Client Spike | [完成记录](../completion-records/HXA-077.md) |
| HXA-078 | 已完成（原任务范围） | A2A Agent 配置、发现与快照 | [完成记录](../completion-records/HXA-078.md) |
| HXA-079 | 已完成（原任务范围） | A2A Task Tool bridge 与恢复 | [完成记录](../completion-records/HXA-079.md) |
| HXA-080 | 已完成（原任务范围） | Runtime manifest/许可证 schema | [完成记录](../completion-records/HXA-080.md) |
| HXA-081 | 已完成（原任务范围） | 构建期固定资产 | [完成记录](../completion-records/HXA-081.md) |
| HXA-082 | 已完成（原任务范围） | RootFS installer | [完成记录](../completion-records/HXA-082.md) |
| HXA-083 | 已完成（原任务范围） | 独立 Runtime APK/IPC | [完成记录](../completion-records/HXA-083.md) |
| HXA-084 | 已完成（原任务范围） | PRoot runner | [完成记录](../completion-records/HXA-084.md) |
| HXA-085 | 已完成（原任务范围） | `bash` Tool | [完成记录](../completion-records/HXA-085.md) |
| HXA-086 | 已完成（原任务范围） | 真机 smoke 与隔离 | [完成记录](../completion-records/HXA-086.md) |
| HXA-087 | 已完成（原任务范围） | 更新/卸载/法律页 | [完成记录](../completion-records/HXA-087.md) |
| HXA-088 | 已完成（原任务范围） | Git Workspace 语义 Spike 与 ADR | [完成记录](../completion-records/HXA-088.md) |
| HXA-090 | 已完成（原任务范围） | Accessibility Service 与权限中心 | [完成记录](../completion-records/HXA-090.md) |
| HXA-091 | 已完成（原任务范围） | UI snapshot/token | [完成记录](../completion-records/HXA-091.md) |
| HXA-092 | 已完成（原任务范围） | UI actions | [完成记录](../completion-records/HXA-092.md) |
| HXA-093 | 已完成（原任务范围） | Accessibility 攻击/恢复测试 | [完成记录](../completion-records/HXA-093.md) |
| HXA-094 | 已完成（原任务范围） | Root 依赖与授权生命周期验收 | [完成记录](../completion-records/HXA-094.md) |
| HXA-095 | 已完成（原任务范围） | Root 高层工具作用域与失权验收 | [完成记录](../completion-records/HXA-095.md) |
| HXA-096 | 已完成（原任务范围） | Root L3 控制台 | [完成记录](../completion-records/HXA-096.md) |
| HXA-097 | 已完成（原任务范围） | Android UI Skill | [完成记录](../completion-records/HXA-097.md) |
| HXA-099 | 已完成（原任务范围） | 模式、预算与资源降级运行控制 | [完成记录](../completion-records/HXA-099.md) |
| HXA-100 | 已完成（原任务范围） | 固定评测集 | [完成记录](../completion-records/HXA-100.md) |
| HXA-101 | 已完成（原任务范围） | Prompt injection | [完成记录](../completion-records/HXA-101.md) |
| HXA-102 | 已完成（原任务范围） | 恢复和副作用 | [完成记录](../completion-records/HXA-102.md) |
| HXA-103 | 已完成（原任务范围） | 资源/稳定性 | [完成记录](../completion-records/HXA-103.md) |
| HXA-104 | 已完成（原任务范围） | 隐私/诊断/删除 | [完成记录](../completion-records/HXA-104.md) |
| HXA-105 | 已完成（原任务范围） | 有界只读委托与声明式 Workflow Spike | [完成记录](../completion-records/HXA-105.md) |
| HXA-110 | 已完成（原任务范围） | CLI Runtime manifest 与独立 UID | [完成记录](../completion-records/HXA-110.md) |
| HXA-111 | 已完成（原任务范围） | Codex app-server 登录 Spike | [完成记录](../completion-records/HXA-111.md) |
| HXA-112 | 已完成（原任务范围） | Claude Code stream-json/SDK Spike | [完成记录](../completion-records/HXA-112.md) |
| HXA-113 | 已完成（原任务范围） | 工具与审批兼容性结论 | [完成记录](../completion-records/HXA-113.md) |
| HXA-114 | 已完成（原任务范围） | DSH subscription adapter 来源、凭据与条款 Spike | [完成记录](../completion-records/HXA-114.md) |
| HXA-115 | 已完成（原任务范围） | 独立 Runtime 凭据 vault | [完成记录](../completion-records/HXA-115.md) |
| HXA-116 | 已完成（原任务范围） | 订阅平台目录与 0.7.0 供应链重基线 | [完成记录](../completion-records/HXA-116.md) |
| HXA-117 | 已完成（原任务范围） | GitHub Copilot 官方 SDK/OAuth App Android Spike | [完成记录](../completion-records/HXA-117.md) |
| HXA-118 | 已完成（原任务范围） | Codex 第三方侧载 OAuth 登录生命周期 | [完成记录](../completion-records/HXA-118.md) |
| HXA-119 | 已完成（原任务范围） | GitHub Copilot Free 第三方侧载 Device Code 登录生命周期 | [完成记录](../completion-records/HXA-119.md) |
| HXA-124 | 已完成（原任务范围） | Connector 调研、插件导入与管理 | [完成记录](../completion-records/HXA-124.md) |
| HXA-127 | 已完成（原任务范围） | 大 catalog 渐进工具发现 | [完成记录](../completion-records/HXA-127.md) |
| HXA-128 | 已完成（原任务范围） | CLI/stdio Connector 可移植性 Spike | [完成记录](../completion-records/HXA-128.md) |
| HXA-129 | 已完成（原任务范围） | Connector 安全替换、安装归属与会话启停 | [完成记录](../completion-records/HXA-129.md) |
| HXA-130 | 已完成（原任务范围） | Connector 签名索引与来源验证 | [完成记录](../completion-records/HXA-130.md) |
| HXA-131 | 已完成（原任务范围） | CLI Runtime 共享握手契约 | [完成记录](../completion-records/HXA-131.md) |
| HXA-132 | 已完成（原任务范围） | 主 App 冷绑定与状态握手 | [完成记录](../completion-records/HXA-132.md) |
| HXA-133 | 已完成（原任务范围） | 固定 Codex 跨 APK 持久 Job 控制面 | [完成记录](../completion-records/HXA-133.md) |
| HXA-134 | 已完成（原任务范围） | 有界模型载荷与对账删除 | [完成记录](../completion-records/HXA-134.md) |
| HXA-135 | 已完成（原任务范围） | Developer 对话 Provider 接入 | [完成记录](../completion-records/HXA-135.md) |
| HXA-136 | 已完成（原任务范围） | 订阅登录入口与真实对话验收 | [完成记录](../completion-records/HXA-136.md) |
| HXA-137 | 已完成（原任务范围） | Claude Free 身份登录与 Claude Code 订阅资格门禁 | [完成记录](../completion-records/HXA-137.md) |
| HXA-138 | 已完成（原任务范围） | Grok Free 官方 Device Code 登录与套餐门禁 | [完成记录](../completion-records/HXA-138.md) |
| HXA-139 | 已完成（原任务范围） | Device Code 可用性、诊断与 Codex 官方兼容流 | [完成记录](../completion-records/HXA-139.md) |
| HXA-140 | 已完成（原任务范围） | Codex 订阅最小模型 Smoke 与实验边界收口 | [完成记录](../completion-records/HXA-140.md) |
| HXA-141 | 已完成（原任务范围） | Runtime 持久 Codex 模型 Job 内核 | [完成记录](../completion-records/HXA-141.md) |
| HXA-142 | 已完成（原任务范围） | 订阅协议实验停止线与注册门禁 | [完成记录](../completion-records/HXA-142.md) |
| HXA-143 | 已完成（原任务范围） | Developer 订阅 Provider 渠道门禁修正 | [完成记录](../completion-records/HXA-143.md) |
| HXA-144 | 已完成（原任务范围） | Claude 订阅 Provider 与显式平台 Job 路由 | [完成记录](../completion-records/HXA-144.md) |
| HXA-145 | 已完成（原任务范围） | Grok 订阅 Provider | [完成记录](../completion-records/HXA-145.md) |
| HXA-146 | 已完成（原任务范围） | Copilot 订阅 Provider | [完成记录](../completion-records/HXA-146.md) |
| HXA-147 | 已完成（原任务范围） | 任务交互与移动界面统一 | [完成记录](../completion-records/HXA-147.md) |
| HXA-148 | 已完成（原任务范围） | Skill Creator 与草稿校验 | [完成记录](../completion-records/HXA-148.md) |
| HXA-149 | 已完成（原任务范围） | Skill Installer 与精确内容安装 | [完成记录](../completion-records/HXA-149.md) |
| HXA-150 | 已完成（原任务范围） | MCP Installer 与 Connector 导入接线 | [完成记录](../completion-records/HXA-150.md) |
| HXA-151 | 已完成（原任务范围） | Skill/MCP 原生操作状态收敛 | [完成记录](../completion-records/HXA-151.md) |
| HXA-152 | 已完成（原任务范围） | 浏览器原生引用短时归因 | [完成记录](../completion-records/HXA-152.md) |
| HXA-153 | 已完成（原任务范围） | JNI 引用与 Binder 代理释放追踪 | [完成记录](../completion-records/HXA-153.md) |
| HXA-154 | 已完成（原任务范围） | 浏览器生产路径引用核实 | [完成记录](../completion-records/HXA-154.md) |
| HXA-155 | 已完成（原任务范围） | 浏览器网络取消与 Activity 生命周期 | [完成记录](../completion-records/HXA-155.md) |
| HXA-156 | 已完成（原任务范围） | 状态与产品研究文档收口 | [完成记录](../completion-records/HXA-156.md) |
| HXA-157 | 已完成（原任务范围） | 工具发现被模型数量上限截断修复 | [完成记录](../completion-records/HXA-157.md) |
| HXA-158 | 已完成（原任务范围） | 未使用 WebView 宿主延迟分配与原生问题折中验证 | [完成记录](../completion-records/HXA-158.md) |
| HXA-159 | 已完成（原任务范围） | 大文件职责重构与浏览器 Context 评估 | [完成记录](../completion-records/HXA-159.md) |
| HXA-160 | 已完成（原任务范围） | Activity 级浏览器宿主与 Context 功能验证 | [完成记录](../completion-records/HXA-160.md) |
| HXA-161 | 已完成（原任务范围） | 真机会话输入区与推理参数接线 | [完成记录](../completion-records/HXA-161.md) |
| HXA-162 | 已完成（原任务范围） | 会话时间线与复制 | [完成记录](../completion-records/HXA-162.md) |
| HXA-163 | 已完成（原任务范围） | 草稿会话与目录归属 | [完成记录](../completion-records/HXA-163.md) |
| HXA-164 | 已完成（原任务范围） | 即时消息发布与 Markdown | [完成记录](../completion-records/HXA-164.md) |
| HXA-165 | 已完成（原任务范围） | 参考移动对话界面的视觉整理 | [完成记录](../completion-records/HXA-165.md) |
| HXA-166 | 已完成（原任务范围） | 会话内模型选择 | [完成记录](../completion-records/HXA-166.md) |
| HXA-167 | 已完成（原任务范围） | 单行横向选项栏 | [完成记录](../completion-records/HXA-167.md) |
| HXA-168 | 已完成（原任务范围） | 顶部空间与胶囊选项 | [完成记录](../completion-records/HXA-168.md) |
| HXA-169 | 已完成（原任务范围） | 顶部主菜单与标题编辑 | [完成记录](../completion-records/HXA-169.md) |
| HXA-170 | 已完成（原任务范围） | 导航样式与扩展入口修复 | [完成记录](../completion-records/HXA-170.md) |
| HXA-171 | 已完成（原任务范围） | 独立文件管理界面 | [完成记录](../completion-records/HXA-171.md) |
| HXA-172 | 已完成（原任务范围） | 归档列表、共享存储入口与上下文用量 | [完成记录](../completion-records/HXA-172.md) |
| HXA-173 | 已完成（原任务范围） | 归档恢复与共享根目录 | [完成记录](../completion-records/HXA-173.md) |
| HXA-174 | 已完成（原任务范围） | 窗口配置和上下文压缩 | [完成记录](../completion-records/HXA-174.md) |
| HXA-175 | 已完成（原任务范围） | 调试脚本留存、紧凑会话控件与系统栏避让 | [完成记录](../completion-records/HXA-175.md) |
| HXA-176 | 已完成（原任务范围） | 长 Turn / Goal 压缩实战强化 | [完成记录](../completion-records/HXA-176.md) |
| HXA-177 | 已完成（原任务范围） | 后台任务与 Goal 阻塞恢复 | [完成记录](../completion-records/HXA-177.md) |
| HXA-178 | 已完成（原任务范围） | 模型判断 Goal 完成 | [完成记录](../completion-records/HXA-178.md) |
| HXA-179 | 已完成（原任务范围） | ChatService 职责拆分 | [完成记录](../completion-records/HXA-179.md) |
| HXA-180 | 已完成（原任务范围） | 独立文件管理变更能力 | [完成记录](../completion-records/HXA-180.md) |
| HXA-181 | 已完成（原任务范围） | 当前规范清理 | [完成记录](../completion-records/HXA-181.md) |
| HXA-182 | 已完成（原任务范围） | 大类职责继续收敛与文件传输恢复 | [完成记录](../completion-records/HXA-182.md) |
| HXA-183 | 已完成（原任务范围） | 大类职责拆分 | [完成记录](../completion-records/HXA-183.md) |
| HXA-184 | 已完成（原任务范围） | 谨慎提取与文件装配整理 | [完成记录](../completion-records/HXA-184.md) |
| HXA-185 | 已完成（原任务范围） | 合并后长稳夹具适配与失败取证 | [完成记录](../completion-records/HXA-185.md) |
| HXA-186 | 已完成（原任务范围） | API35 真机隔离回归 | [完成记录](../completion-records/HXA-186.md) |
| HXA-187 | 已完成（原任务范围） | QuickJS 真机超时停滞修复 | [完成记录](../completion-records/HXA-187.md) |
| HXA-188 | 已完成（原任务范围） | 真机浏览器冻结、后台恢复与 Root 验证 | [完成记录](../completion-records/HXA-188.md) |
| HXA-189 | 已完成（原任务范围） | 第三轮审查复核与缺陷修复 | [完成记录](../completion-records/HXA-189.md) |
| HXA-191 | 已完成（原任务范围） | 深色主题与会话搜索 | [完成记录](../completion-records/HXA-191.md) |
| HXA-192 | 已完成（原任务范围） | Plan 审阅到执行的用户闭环验收 | [完成记录](../completion-records/HXA-192.md) |
| HXA-193 | 已完成（原任务范围） | 包内 Runtime 可复现资产与升级验收 | [完成记录](../completion-records/HXA-193.md) |
| HXA-194 | 已完成（原任务范围） | 命令详情与现有结果导航 | [完成记录](../completion-records/HXA-194.md) |
| HXA-195 | 已完成（原任务范围） | 有界日志协议与实时输出 | [完成记录](../completion-records/HXA-195.md) |
| HXA-196 | 已完成（原任务范围） | 有租期的独立后台命令 Job | [完成记录](../completion-records/HXA-196.md) |
| HXA-197 | 已完成（原任务范围） | 单个手动 PTY 终端 | [完成记录](../completion-records/HXA-197.md) |
| HXA-198 | 已完成（原任务范围） | 手动终端多会话与重连 | [完成记录](../completion-records/HXA-198.md) |
| HXA-199 | 已完成（原任务范围） | 终端专项集成与交付 | [完成记录](../completion-records/HXA-199.md) |
| HXA-200 | 已完成（原任务范围） | 三态审批偏好与执行解析 | [完成记录](../completion-records/HXA-200.md) |
| HXA-201 | 已完成（原任务范围） | 工具设置与审批卡 | [完成记录](../completion-records/HXA-201.md) |
| HXA-202 | 已完成（原任务范围） | 任务过程与跨页面操作导航 | [完成记录](../completion-records/HXA-202.md) |
| HXA-203 | 已完成（原任务范围） | 产物可用性与文件交付闭环 | [完成记录](../completion-records/HXA-203.md) |
| HXA-204 | 已完成（原任务范围） | 跨执行域错误与恢复交互 | [完成记录](../completion-records/HXA-204.md) |
| HXA-205 | 已完成（原任务范围） | 首次配置与能力准备修复 | [完成记录](../completion-records/HXA-205.md) |
| HXA-206 | 已完成（原任务范围） | 核心产品闭环综合验收 | [完成记录](../completion-records/HXA-206.md) |
| HXA-207 | 已完成（原任务范围） | 现有扩展来源的添加到使用闭环 | [完成记录](../completion-records/HXA-207.md) |
| HXA-208 | 已完成（原任务范围） | 完整 Goal 工具与前后台连续运行 | [完成记录](../completion-records/HXA-208.md) |
| HXA-209 | 已完成（原任务范围） | 会话授权预设、工具禁用与自定义权限 | [完成记录](../completion-records/HXA-209.md) |
| HXA-210 | 已完成（原任务范围） | 独立会话目录、外部资源绑定与生命周期 | [完成记录](../completion-records/HXA-210.md) |
| HXA-211 | 已完成（原任务范围） | 按会话导出 JSONL 执行记录 | [完成记录](../completion-records/HXA-211.md) |
| HXA-212 | 已完成（原任务范围） | 内置扩展市场与典型目录 | [完成记录](../completion-records/HXA-212.md) |
| HXA-213 | 已完成（原任务范围） | 按消息创建会话分支 | [完成记录](../completion-records/HXA-213.md) |
| HXA-214 | 已完成（原任务范围） | 发送回执、草稿保留与停止一致性 | [完成记录](../completion-records/HXA-214.md) |
| HXA-215 | 已完成（原任务范围） | 最新用户消息在会话内修订重发 | [完成记录](../completion-records/HXA-215.md) |
| HXA-216 | 已完成（原任务范围） | 普通 Turn 与 Goal 统一排队和转向 | [完成记录](../completion-records/HXA-216.md) |
| HXA-217 | 已完成（原任务范围） | 轻量请求来源记录与 JSONL 可追踪性 | [完成记录](../completion-records/HXA-217.md) |
| HXA-218 | 已完成（原任务范围） | 会话工作台 UI 与交互重构（第一批） | [完成记录](../completion-records/HXA-218.md) |
| HXA-219 | 已完成（原任务范围） | 产物就地预览 | [完成记录](../completion-records/HXA-219.md) |
| HXA-220 | 已完成（原任务范围） | Core Engine / TurnEngine 生命周期收敛 | [完成记录](../completion-records/HXA-220.md) |
| HXA-221 | 已完成（原任务范围） | Pre-release clean-slate baseline cleanup | [完成记录](../completion-records/HXA-221.md) |
| HXA-222 | 已完成（原任务范围） | 设备内本地模型一等 Provider | [完成记录](../completion-records/HXA-222.md) |
| HXA-223 | 已完成（原任务范围） | Post-clean-slate Core Boundary Convergence | [完成记录](../completion-records/HXA-223.md) |
| HXA-225 | 已完成（原任务范围） | Agent 自主图片读取与工具视觉回填 | [完成记录](../completion-records/HXA-225.md) |
| HXA-226 | 已完成（原任务范围） | UI / IA 第二轮收敛 | [完成记录](../completion-records/HXA-226.md) |
| HXA-227 | 已完成（原任务范围） | 统一轨迹级 Agent Eval 与失败归因 | [完成记录](../completion-records/HXA-227.md) |
| HXA-228 | 已完成（原任务范围） | Conversation-first Shell 与 Session Context Control | [完成记录](../completion-records/HXA-228.md) |
| HXA-229 | 已完成（原任务范围） | Model-authored Agent Activity Presentation | [完成记录](../completion-records/HXA-229.md) |
| HXA-230 | 已完成（原任务范围） | Markdown-native Hierarchical Agent Memory | [完成记录](../completion-records/HXA-230.md) |
| HXA-231 | 已完成（原任务范围） | 问题收口与原子工具绑定 | [完成记录](../completion-records/HXA-231.md) |
| HXA-233 | 已完成（原任务范围） | 订阅回放依赖生命周期与有界清理 | [完成记录](../completion-records/HXA-233.md) |
| HXA-241 | 已完成（原任务范围） | 历史模拟器扫描与当前主线缺陷复核 | [完成记录](../completion-records/HXA-241.md) |
| HXA-242 | 已完成（原任务范围） | HTTP 仅提示、滚动/输入反馈与自动化体验 | [完成记录](../completion-records/HXA-242.md) |
| HXA-243 | 已完成（原任务范围） | Mobile Use 全机许可、原生手势/截图与结果收敛 | [完成记录](../completion-records/HXA-243.md) |
| HXA-244 | 已完成（原任务范围） | Conversation 持久授权、应用选择与屏幕共享 | [完成记录](../completion-records/HXA-244.md) |
| HXA-245 | 已完成（原任务范围） | Mobile Use 插件半透明穿透悬浮交互 | [完成记录](../completion-records/HXA-245.md) |
| HXA-246 | 已完成（原任务范围） | 本机项目与跨会话工作组织 | [完成记录](../completion-records/HXA-246.md) |
| HXA-247 | 已完成（原任务范围） | 文件发现、会话交接与成果定位 | [完成记录](../completion-records/HXA-247.md) |
| HXA-249 | 已完成（原任务范围） | 实际模型多 system 消息协议兼容 | [完成记录](../completion-records/HXA-249.md) |
| HXA-250 | 已完成（原任务范围） | Mobile Use 完成任务后返回原对话 | [完成记录](../completion-records/HXA-250.md) |
| HXA-251 | 已完成（原任务范围） | 重构余项设备/模型对照与状态核对 | [完成记录](../completion-records/HXA-251.md) |
| HXA-252 | 已完成（原任务范围） | 通用工具发现提示与多任务对照 | [完成记录](../completion-records/HXA-252.md) |
| HXA-253 | 已完成（原任务范围） | 主机测试统一发现与漏测保护 | [完成记录](../completion-records/HXA-253.md) |
| HXA-254 | 已完成（原任务范围） | 项目会话实时读模型 | [完成记录](../completion-records/HXA-254.md) |
| HXA-255 | 已完成（原任务范围） | 权限页面外发规则应用接口 | [完成记录](../completion-records/HXA-255.md) |
| HXA-256 | 已完成（原任务范围） | 测试生命周期夹具迁移与生产桥清理 | [完成记录](../completion-records/HXA-256.md) |
| HXA-257 | 已完成（原任务范围） | 会话目录、输入与任务投影职责 | [完成记录](../completion-records/HXA-257.md) |
| HXA-258 | 已完成（原任务范围） | 会话元数据事务职责 | [完成记录](../completion-records/HXA-258.md) |
| HXA-259 | 已完成（原任务范围） | 项目与模型页面功能归属 | [完成记录](../completion-records/HXA-259.md) |
| HXA-260 | 已完成（原任务范围） | 历史入口与复杂边界维护 | [完成记录](../completion-records/HXA-260.md) |
| HXA-261 | 已完成（原任务范围） | 有界会话性能对照 | [完成记录](../completion-records/HXA-261.md) |
| HXA-262 | 已完成（原任务范围） | 页面迁移与真实模型连续压缩补验 | [完成记录](../completion-records/HXA-262.md) |
| HXA-263 | 已完成（原任务范围） | 自有设备测试日志完整采集 | [完成记录](../completion-records/HXA-263.md) |
| HXA-264 | 已完成（原任务范围） | 停止与恢复组合本地收尾 | [完成记录](../completion-records/HXA-264.md) |
| HXA-265 | 已完成（原任务范围） | 双渠道有界资源稳定性收尾 | [完成记录](../completion-records/HXA-265.md) |
| HXA-266 | 已完成（原任务范围） | 当前Goal契约与真实模型旅程收尾 | [完成记录](../completion-records/HXA-266.md) |
| HXA-267 | 已完成（原任务范围） | 门禁与历史调试脚本维护 | [完成记录](../completion-records/HXA-267.md) |
| HXA-268 | 已完成（原任务范围） | 历史版本脚本与日志退役 | [完成记录](../completion-records/HXA-268.md) |
| HXA-269 | 已完成（原任务范围） | 真机后台返回黑屏诊断、主线程修复与Qwen回归；其他后端补验独立保留 | [完成记录](../completion-records/HXA-269.md) |
| HXA-270 | 已完成（原任务范围） | Mobile Use 悬浮审批与等待状态同步 | [完成记录](../completion-records/HXA-270.md) |
| HXA-272 | 已完成（原任务范围） | 统一 Skill 内容贡献与原生插件独立激活 | [完成记录](../completion-records/HXA-272.md) |
| HXA-273 | 已完成（原任务范围） | Skill 用户与模型调用资格及显式激活 | [完成记录](../completion-records/HXA-273.md) |
| HXA-274 | 已完成（原任务范围） | 插件就绪原因与配置生效状态统一投影 | [完成记录](../completion-records/HXA-274.md) |
| HXA-275 | 已完成（原任务范围） | 插件组件兼容诊断与可执行作者样例 | [完成记录](../completion-records/HXA-275.md) |
| HXA-276 | 已完成（原任务范围） | Browser 原生插件贡献与统一选择入口 | [完成记录](../completion-records/HXA-276.md) |
| HXA-277 | 已完成（原任务范围） | 原生插件资源归属与停止释放契约 | [完成记录](../completion-records/HXA-277.md) |
| HXA-278 | 已完成（原任务范围） | Skill 渐进装载与上下文失效一致性 | [完成记录](../completion-records/HXA-278.md) |
| HXA-279 | 已完成（原任务范围） | UI 观察反馈与 Browser 标签发现及有界等待 | [完成记录](../completion-records/HXA-279.md) |
| HXA-280 | 已完成（原任务范围） | 工具发现与请求工作集优化 | [完成记录](../completion-records/HXA-280.md) |
| HXA-281 | 已完成（原任务范围） | 工具发现指导与外部插件市场复用 | [完成记录](../completion-records/HXA-281.md) |
| HXA-282 | 已完成（原任务范围） | 外部服务、市场恢复与连续 Agent 验收 | [完成记录](../completion-records/HXA-282.md) |
| HXA-284 | 已完成（原任务范围） | 统一PDF/DOCX文档插件 | [完成记录](../completion-records/HXA-284.md) |
| HXA-285 | 已完成（原任务范围） | 引用历史与压缩快照一致性收尾 | [完成记录](../completion-records/HXA-285.md) |
| HXA-286 | 已完成（原任务范围） | 统一工具贡献与上下文接缝 | [完成记录](../completion-records/HXA-286.md) |
| HXA-287 | 已完成（原任务范围） | 应用命令与 Turn 接缝收敛 | [完成记录](../completion-records/HXA-287.md) |
| HXA-293 | 已完成（原任务范围） | 内容预算接缝与 Memory 选择实验 | [完成记录](../completion-records/HXA-293.md) |
| HXA-294 | 已完成（原任务范围） | 已交付切片的集成与恢复验收 | [完成记录](../completion-records/HXA-294.md) |
| HXA-296 | 已完成（原任务范围） | 系统提示一致性与文件读写契约收尾 | [完成记录](../completion-records/HXA-296.md) |
| HXA-297 | 已完成（原任务范围） | 会话存储、查询与执行来源优化 | [完成记录](../completion-records/HXA-297.md) |
| HXA-298 | 已完成（原任务范围） | 插件提示与统一配置管理 UI、清理并发修复 | [完成记录](../completion-records/HXA-298.md) |
| HXA-299 | 已完成（原任务范围） | 插件上下文诊断与可复用外部样本验收 | [完成记录](../completion-records/HXA-299.md) |
| HXA-300 | 已完成（原任务范围） | 插件导入预览、更新与完成引导 | [完成记录](../completion-records/HXA-300.md) |
| HXA-301 | 已完成（原任务范围） | v0.0.6 最新工作区本地发布验收 | [完成记录](../completion-records/HXA-301.md) |
| HXA-302 | 已完成（原任务范围） | 插件、工作导航、终端与会话图片体验 | [完成记录](../completion-records/HXA-302.md) |
| HXA-303 | 已完成（原任务范围） | 有效预算契约、声明式工具发现与重构任务收敛 | [完成记录](../completion-records/HXA-303.md) |
| HXA-304 | 已完成（原任务范围） | 预览升级确认与重构前双渠道验收 | [完成记录](../completion-records/HXA-304.md) |
| HXA-305 | 已完成（原任务范围） | 内置 Linux 环境默认可用与维护体验 | [完成记录](../completion-records/HXA-305.md) |
| HXA-306 | 已完成（原任务范围） | 手测反馈、浏览器与会话交互 | [完成记录](../completion-records/HXA-306.md) |
| HXA-308 | 已完成（原任务范围） | 系统分享收集与本机知识库第一阶段 | [完成记录](../completion-records/HXA-308.md) |
| HXA-311 | 已完成（原任务范围） | 三阶段收尾与回复朗读等待优化 | [完成记录](../completion-records/HXA-311.md) |
| HXA-312 | 已完成（原任务范围） | 本地知识库检索问答 | [完成记录](../completion-records/HXA-312.md) |
| HXA-313 | 已完成（原任务范围） | 确认式自然语言提醒 | [完成记录](../completion-records/HXA-313.md) |
| HXA-314 | 已完成（原任务范围） | 最小定时 Agent 生命周期 | [完成记录](../completion-records/HXA-314.md) |
