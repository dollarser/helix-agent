# ADR-0023: Connector 可迁移能力包

Status: proposed
Date: 2026-09-05
HXA: HXA-124
Deciders: pending
Supersedes: none
Superseded by: none

## Context

项目所有者要求在新的 worktree 调研并实现类似 Codex、Claude Code、WorkBuddy、QwenWork 的 connector 概念与插件迁移。当前已有 MCP HTTP、Skill snapshot、SecretStore 和统一 Tool Dispatcher；MCP 首版只有 bearer，离线 PRoot 不能运行需要网络凭据的连接器。源产品的 manifest 和账号授权不是统一协议。

## Decision

提议把 connector 作为多个 MCP endpoint 与 Skill snapshot 的产品组合，通过数据格式适配复用既有执行层。HXA-124 在当前信任边界内实现本地 ZIP/JSON 导入与组件管理，不更改已接受的授权/Runtime 决定。持久化只含源格式标识、内容 hash、端点和 Skill 引用；用户独立配置 SecretStore bearer。新 config 禁用，用户测试和选择后注册工具。core storage 仅增加现有 authAlias 的更新操作（同时禁用），无 Room schema 变化。

本 ADR 尚为 proposed，未来 OAuth、CLI 网络底座、统一会话 enablement、市场都未获得本记录的接受或实现证明。HXA-124 的可运行代码不等于平台全兼容。

## Alternatives considered

- **全部编译为 bash + Skill**：便于桌面复用，但 Android 离线 PRoot 不适合认证网络 CLI，也丢失现有结构化 MCP 工具管线的直接复用优势。
- **复刻每个源 host**：支持 hooks/agents/策略格式将引入多套执行授权语义，维护成本大；适合将来独立兼容 Runtime，不适合第一批迁移。
- **只手工新增 MCP**：改动最少，但丢失多 Skill 包的分发、来源 hash 与迁移差异展示；作为无法导出的平台连接器的回退方案。

## Consequences

可直接复用开放的 MCP/Skills 子集；专有平台 app ID、OAuth 登录态和桌面可执行依赖需要重接。第一版组件级开关不是统一事务状态机；进程重启后 MCP 不自动重连。部分安装失败可留未启用 Skill snapshot，更新版本并存。停用不撤回已经发送的远端副作用，移除不等于厂商撤销 consent。

## Verification

HXA-124 的 JVM、双 flavor debug 构建与 API 29/36 专项设备验证已通过，详见 [HXA-124 完成记录](../completion-records/HXA-124.md)。required before acceptance：所有者设计评审；未来 OAuth/CLI/市场需各自的实现与真实服务证据。官方格式与用户观察的证据等级见调研文档。

## Reconsider when

真实迁移样本主要依赖 OAuth 或带网络凭据 CLI；多个包共享 Skill 导致组件开关语义难以理解；catalog 大到影响上下文预算；包更新需要跨存储原子 rollback；Android/商店约束要求改变执行域。

## References

- [Connector 调研与设计](../architecture/connector-portability.md)
- [当前 MCP 与 Skill 契约](../architecture/provider-mcp-skills-modes.md)
- [能力优先授权](0012-capability-first-advanced-grants.md)
- [Runtime 生命周期](0007-companion-runtime-lifecycle.md)
