# ADR-SKILLS-001: Skill 创作、安装与 MCP 配置闭环

Status: accepted
Date: 2026-09-16
HXA: HXA-148
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

Skill 内容、MCP 配置和安装动作的信任与所有权不同，统一产品入口不能形成新的执行授权来源。

## Decision

实现三个默认可发现的 Helix 内置 Skill：`skill-creator`、`skill-installer`、`mcp-installer`。知识层提供任务流程，原生工具负责校验、预览和安装；内置身份不授予工具权限。

### Skill Creator（HXA-148）

模型通过已有 scoped write/edit 在当前 Workspace 生成草稿目录，包含 SKILL.md 和实际需要的 references/assets/scripts；支持基于已固定旧快照创建新版本，不修改已安装快照。步骤为需求和触发场景、草稿生成、真实校验、用户预览、安装入口。用户可直接编辑草稿或在聊天中要求修改，失败显示可定位到文件/字段的错误。不得用尚未运行的脚本测试作为成功证据。

增加 `skills.preview`：只读取当前会话可访问的 Workspace 目录或 ZIP，复用 SkillImportService 校验，返回源 scope/相对路径、确定性内容 hash、文件清单/大小、依赖及诊断，不安装或启用。预览中维护的内部缓存不成为模型可指定的任意本机路径，也不构成授权。UI 提供“创建 Skill”和草稿预览入口；模型生成复用当前 Provider 与会话工具链，无隐藏网络调用。

### Skill Installer（HXA-149）

补齐架构已规划的 `skills.install`，L2 LOCAL_MUTATION，经统一 Dispatcher/Policy 判定；需要确认时精确绑定 source scope、相对路径和预期内容 hash。执行时重新读取及校验，内容变化拒绝安装并要求刷新预览；不能把已批准旧内容替换成新内容。安装复用固定快照和 USER_IMPORTED 来源，默认禁用；已有相同快照保留用户既有状态。返回实际 SkillKey/hash 与安装结果，后续启用复用既有 skills.enable 或 UI，不把安装当作能力授权。

第一版支持用户选取的目录/ZIP及当前 Workspace 草稿。公开远程来源先通过已有用户操作下载至本地再预览；不假设 http.fetch 的文本响应就是可安装 ZIP，不在本任务加入 Git credential、静默网络安装、自动市场更新或 Codex 专用路径。产品提示应明确可选来源和实际限制。

### MCP Installer（HXA-150）

增加 `connectors.preview` 和 `connectors.install`，分别用于本地 JSON/ZIP 的兼容预览及绑定 hash 的 L2 安装，沿用 ConnectorPackageReader/ConnectorService。补齐“粘贴 MCP JSON / 从文件导入”入口和安装结果卡，区分已安装、待配置认证、已连接、已选择工具。模型可生成不含凭据的 MCP JSON 或组合包草稿；不静默将 MCP 编译为 bash。

首版复用已实现的 HTTPS MCP 路径。安装只创建禁用记录和快照，不进行握手或自动连接。需要认证时打开现有原生凭据表单，Secret 不进入模型参数、草稿或结果；连接测试、工具选择与启用沿用既有用户流程。stdio/联网 CLI/OAuth/市场仍分别受 HXA-126/128/130 约束，不借安装器改变执行域。

### 共同执行与恢复契约

- Scope/路径解析复用 Workspace 服务，拒绝越界、链接逃逸和导入期间内容替换；校验和执行使用同一份有界快照。所有新工具必须注册真实 descriptor/schema/风险/效果，不由 Skill 自声明放宽。
- Preview 的 L1 READ_ONLY 与 install 的 L2 LOCAL_MUTATION 均走正常 Dispatcher；Plan 只能预览。安装有用户逐调用或已有有限精确批次批准，取消在产生持久副作用前检查。
- 写入后取消或中断必须查询已确认安装结果；使用内容 hash/现有快照机制避免重复安装，不盲目重放。安装失败可保留未启用快照，按 [ADR-CONNECTORS-001](../connectors/001-portable-bundles.md) 明确提示，不宣称跨文件与数据库的原子事务。
- 复用批准、审计、快照与安装后默认禁用语义；新增持久结构、依赖或执行能力须在对应任务明确设计和验收，不由安装动作隐式引入。
- UI 用用户语言展示来源、内容变更、依赖和下一步，保留中英文、小屏大字体与无障碍。不能只增加三个提示词文件就标记功能完成。

## Alternatives considered

- 仅加入三个内置提示词：容易交付，但模型无法验证和完成安装，用户仍需手动搬运；不能满足完整创建与安装体验。
- 原样执行 Codex/QwenWork 安装脚本：依赖桌面文件布局、联网 CLI、宿主认证或代理，无法沿用 Android 的隔离和授权契约。
- 仅提供原生表单：适合手工安装，但不能覆盖聊天中生成、迭代、校验和安装的连续流程；表单作为同一原生服务的另一个入口保留。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
