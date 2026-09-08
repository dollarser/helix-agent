# ADR-0029: 内置 Skill 创作与 Skill/MCP 安装流程

Status: accepted
Date: 2026-09-08
HXA: HXA-148
Deciders: project owner (2026-09-08)
Supersedes: none
Superseded by: none

## Context

所有者要求参考当前 Codex 及已提供的 QwenWork、WorkBuddy 实现，在 Helix 内置 Skill Creator、Skill Installer、MCP Installer。产品方向已经授权；本决策明确模型可调用的安装契约及三个 HXA 的共同边界，所有者已于下述日期接受。

当前源码已具备 BuiltInSkills、SkillLoader、SkillImportService、SkillRepository、ConnectorPackageReader、ConnectorService 和 MCP 连接测试。SkillTools 注册 list/read/read_resource/enable/disable/remove；架构文档规划了 L2 skills.install，但生产工具尚未注册安装能力。Connector 安装当前从本地 picker 预览开始，安装记录与 Skill 快照复用既有存储，连接测试才注册 MCP。

参考依据：本机 Codex skill-creator 强调精准触发描述、渐进披露和可验证脚本；skill-installer 使用独立确定性安装脚本、明确来源、冲突处理。QwenWork 的 mcp-installer 依赖 uv/CLI/代理等源宿主环境，不能在 Android 原样执行。WorkBuddy GitHub/可灵市场包体现 MCP 端点与一个或多个 Skill 的组合；账号启用/绑定状态与可迁移内容分离。只参考这些流程，不复制第三方实现或源包正文。

## Decision

实现三个默认可发现的 Helix 内置 Skill：`skill-creator`、`skill-installer`、`mcp-installer`。知识层提供任务流程，原生工具负责校验、预览和安装；内置身份不授予工具权限。

### Skill Creator（HXA-148）

模型通过已有 scoped write/edit 在当前 Workspace 生成草稿目录，包含 SKILL.md 和实际需要的 references/assets/scripts；支持基于已固定旧快照创建新版本，不修改已安装快照。步骤为需求和触发场景、草稿生成、真实校验、用户预览、安装入口。用户可直接编辑草稿或在聊天中要求修改，失败显示可定位到文件/字段的错误。不得用尚未运行的脚本测试作为成功证据。

增加 `skills.preview`：只读取当前会话可访问的 Workspace 目录或 ZIP，复用 SkillImportService 校验，返回源 scope/相对路径、确定性内容 hash、文件清单/大小、依赖及诊断，不安装或启用。预览中维护的内部缓存不成为模型可指定的任意本机路径，也不构成授权。UI 提供“创建 Skill”和草稿预览入口；模型生成复用当前 Provider 与会话工具链，无隐藏网络调用。

### Skill Installer（HXA-149）

补齐架构已规划的 `skills.install`，L2 LOCAL_MUTATION，经现有 Dispatcher/Policy/精确审批绑定 source scope、相对路径和预期内容 hash。执行时重新读取及校验，内容变化拒绝安装并要求刷新预览；不能把已批准旧内容替换成新内容。安装复用固定快照和 USER_IMPORTED 来源，默认禁用；已有相同快照保留用户既有状态。返回实际 SkillKey/hash 与安装结果，后续启用复用既有 skills.enable 或 UI，不把安装当作能力授权。

第一版支持用户选取的目录/ZIP及当前 Workspace 草稿。公开远程来源先通过已有用户操作下载至本地再预览；不假设 http.fetch 的文本响应就是可安装 ZIP，不在本任务加入 Git credential、静默网络安装、自动市场更新或 Codex 专用路径。产品提示应明确可选来源和实际限制。

### MCP Installer（HXA-150）

增加 `connectors.preview` 和 `connectors.install`，分别用于本地 JSON/ZIP 的兼容预览及绑定 hash 的 L2 安装，沿用 ConnectorPackageReader/ConnectorService。补齐“粘贴 MCP JSON / 从文件导入”入口和安装结果卡，区分已安装、待配置认证、已连接、已选择工具。模型可生成不含凭据的 MCP JSON 或组合包草稿；不静默将 MCP 编译为 bash。

首版复用已实现的 HTTPS MCP 路径。安装只创建禁用记录和快照，不进行握手或自动连接。需要认证时打开现有原生凭据表单，Secret 不进入模型参数、草稿或结果；连接测试、工具选择与启用沿用既有用户流程。stdio/联网 CLI/OAuth/市场仍分别受 HXA-126/128/130 约束，不借安装器改变执行域。

### 共同执行与恢复契约

- Scope/路径解析复用 Workspace 服务，拒绝越界、链接逃逸和导入期间内容替换；校验和执行使用同一份有界快照。所有新工具必须注册真实 descriptor/schema/风险/效果，不由 Skill 自声明放宽。
- Preview 的 L1 READ_ONLY 与 install 的 L2 LOCAL_MUTATION 均走正常 Dispatcher；Plan 只能预览。安装有用户逐调用或已有有限精确批次批准，取消在产生持久副作用前检查。
- 写入后取消或中断必须查询已确认安装结果；使用内容 hash/现有快照机制避免重复安装，不盲目重放。安装失败可保留未启用快照，按 ADR-0023 明确提示，不宣称跨文件与数据库的原子事务。
- 不引入新的 Room schema、第三方依赖、执行 Runtime 或长期权限规则；复用当前批准、审计、快照和禁用默认语义。需要这些扩展时另行评审。
- UI 用用户语言展示来源、内容变更、依赖和下一步，保留中英文、小屏大字体与无障碍。不能只增加三个提示词文件就标记功能完成。

## Alternatives considered

- 仅加入三个内置提示词：容易交付，但模型无法验证和完成安装，用户仍需手动搬运；不能满足完整创建与安装体验。
- 原样执行 Codex/QwenWork 安装脚本：依赖桌面文件布局、联网 CLI、宿主认证或代理，无法沿用 Android 的隔离和授权契约。
- 仅提供原生表单：适合手工安装，但不能覆盖聊天中生成、迭代、校验和安装的连续流程；表单作为同一原生服务的另一个入口保留。

## Consequences

三个 Skill 有真实原生操作支撑，用户可以在现有会话完成草稿制作、校验和安装审批，安装后的启用和联网状态仍清晰。增加新的工具 schema、结果卡和内容绑定检查，需要专项恢复与设备测试。第一版不提供一键安装任意桌面插件或自动适配所有 CLI 的承诺；完整 Connector 生命周期事务仍留给 HXA-129。

## Verification

本提案已核对当前 SkillTools/SkillImportService/ConnectorService/BuiltInSkills 源码，以及本机 Codex 两个系统 Skill 的流程。既有 QwenWork/WorkBuddy 真实样本导入结果见 HXA-125 进展，只证明既有导入基础，不证明本提案实现。

所有者于 2026-09-08 明确接受 ADR-0029 并要求完成 HXA-148～150；实现授权已具备。Required before completion: 各 HXA 的失败、取消、hash 变化、重复安装、默认禁用、Plan 拒绝写入与 API29/36 UI/恢复测试；真实模型至少一次创建草稿并经工具校验、审批安装及后续读取。MCP 使用可控 HTTP 服务验证连接与禁用边界，独立受保护服务验收仍归 HXA-125。每项的精确命令和证据见 roadmap；不得用构建或 opt-in 跳过充当端到端验收。

## Reconsider when

用户需要直接从远程市场下载/更新、OAuth 自动登录、联网 stdio、跨包原子更新，或现有快照/审计契约不足以正确恢复安装；这些情况下先补对应独立设计，不静默扩展本接口。

## References

- [Skill 和 MCP 架构](../architecture/provider-mcp-skills-modes.md)
- [ADR-0012](0012-capability-first-advanced-grants.md)
- [ADR-0023](0023-connector-portable-bundles.md)
- [HXA-125 样本验证](../development/hxa-125-progress.md)
- [Codex 官方 Skill 入口](https://developers.openai.com/codex/skills)
- [实施路线](../development/roadmap.md)
