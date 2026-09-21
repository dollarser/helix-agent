# MCP、Skills、Connector 与 A2A

扩展负责提供能力描述和内容，不能授予本地权限。来源、信任级别、用户启用状态与契约版本必须贯穿发现、曝光、执行和审计。

| 类型 | 作用 | 当前契约 |
| --- | --- | --- |
| MCP | 客户端发现、会话工具曝光、远端调用 | [MCP](../adr/mcp/001-client-and-discovery.md) |
| Skill | 指令、资源和受控脚本组织 | [Skills](../adr/skills/001-authoring-and-installation.md) |
| Connector | 可迁移能力包与安装管理 | [可迁移包](../adr/connectors/001-portable-bundles.md)、[格式细节](connector-portability.md) |
| A2A | 用户配置的外部 Agent 服务 | [A2A Client](../adr/a2a/001-client-interoperability.md) |

## 发现到使用

发现结果展示来源、依赖与适用执行域；用户选择安装/启用后才能曝光。MCP 已有 tools.search 与会话曝光窗口，不另建平行注册系统。加载 Skill、项目指令或网页内容时保留来源与信任标签，排序与作用域覆盖不提升权限。

安装、修复、禁用和更新走应用服务，UI 不直接写数据库。工具禁用与授权预设按照[会话授权](../adr/permissions/001-session-authorization.md)演进；未经完成验收不能将计划中的新语义描述为已上线。

## 执行与故障

扩展工具执行仍经过统一 schema / Policy / 授权 / 限额 / 审计。MCP annotation、Skill 文本和远端 Agent 结果均不能证明效果安全或生成 Approval Proof。外部结果回到本地后的文件写入等效果必须重新分派。

A2A 仅为客户端互操作，不是 Helix Worker，也不继承本地 Secret、scope 或批准。远端任务身份、取消和 Artifact 应持久化，未知结果先对账。网络、认证和 Runtime 依赖失败要提供可定位的修复入口，不把安装成功当真实任务成功。

Connector OAuth 已由所有者明确授权修复并整合，见 accepted ADR-CONNECTORS-002；真实服务验收仍开放。安装所有权与签名索引 ADR 仍为 proposed，对应实现必须等待显式接受。具体未完成工作见[路线](../development/roadmap.md)，不沿用旧 Agent 交接中的任务顺序。
