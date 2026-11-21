# 实施与交接指南

## 任务与按需参考

[AGENTS.md](../../AGENTS.md) 记录 Owner 的工作约定。[任务索引](roadmap.md)定位 HXA，[status](status.md)记录当前进展。已完成 HXA 不重复实施，纯文档整理不新建 HXA。只有用户明确要求建立持久 Goal 时才创建。

## 工作与决策

功能与重构的取舍见[开发策略](feature-refactor-strategy.md)，技术卡见 [Harness](../architecture/harness-refactor-plan.md)，发行依赖见[就绪条件](release-readiness.md)。

## 应用能力与 Agent 工具的规划规则

先区分用户直接操作、模型工具、宿主执行基础设施。应用服务不自动变成 ToolDescriptor；同一服务可以同时有手动入口与受控工具入口，手动授权不扩大模型 scope。

涉及新增能力时说明用户路径、领域归属、复用服务/协议和是否改变模型曝光。新增工具还需说明模型调用场景、现有能力为何不足、发现/启停、输入输出、权限和资源边界；纯文案/UI 修改不强加一份工具设计。

| 类型 | 保留原则 |
| --- | --- |
| 手动应用功能 | 主题、历史、模型/凭据、组件准备、任务查看、导出保留用户入口，不因页面存在就新增模型工具 |
| 业务能力 | 优先复用 MCP/Connector/Skill/通用工具；确需平台行为再增原生工具，不能为每服务造 Loop；Skill 不授予权限 |
| 执行基础设施 | 保留上下文、授权、持久结算、取消、对账和资源限制；普通任务不强制 Goal、PRoot、子 Agent 或 Workflow |
| 工具曝光 | 模式/用户启用/发现决定曝光，不塞入所有 API；禁用后发现与调用都不可达，来源描述不授权 |

已有 plugins_preview/install 等工具沿当前契约；撤除或改语义需明确影响。评审以用户结果和边界为准，不按工具数、状态机数或层数验收。

## 验证与提交

纯文档改动检查文档、链接和语义一致性。代码交付执行当前请求和 HXA 要求的[验证命令](verification-matrix.md)。设备与服务使用遵循 [AGENTS 授权](../../AGENTS.md#verification-and-device-authorization)。候选诊断与结果汇总见[调试手册](debugging-playbook.md)。

本地 `build/` 保存任务所需的候选、交付物和验证原件。清理已修复问题的失败日志、设备转储和专用测试 APK 时，按[原始输出保留边界](../evidence/README.md#本地原始输出的保留边界)核对同场景复验与修复记录。对补丁、未提交脚本和工作树恢复包，先核对归属及其独有内容；其他任务仍需恢复的内容须有经过校验的副本才能清理。归属或恢复需求不明时保留原件。发布归档、当前基准和交付物按各自用途保留。长期文档只记稳定结论。

## 开发 Agent Skills

`.agents/skills/` 用于仓库开发；`extensions/` 下的插件 Skill 随 APK 交付。

- 候选收尾、发行准备或核对验收证据时使用 [$helix-acceptance-closeout](../../.agents/skills/helix-acceptance-closeout/SKILL.md)。
- 身份、授权、生命周期、路径、结果或插件贡献契约有变化时使用 [$helix-contract-change-review](../../.agents/skills/helix-contract-change-review/SKILL.md)。

修改开发 Skill 后检查 frontmatter、发现元数据、相对链接与所述授权边界，无需产生真实外部效果。

## 交接输出

范围和剩余工作留在任务，当前结论在 status，验证结果在完成记录或 evidence。交接沿用当前任务的摘要和有效授权，注明仍在运行的执行及其身份，分别说清是重连观察、查询状态、重新授权还是发起新调用，避免一律写成 `resume`；不从其他历史任务继承 serial、账号、预算或许可。无需为不同模型另写交接文档。
