# Helix 当前架构决策

这里只维护当前有效决定和仍有价值的候选方案。过时记录、旧编号、增量补丁说明和版本兼容规则不保留；需要追查决策演变时查看 `v0.0.1` 分支及 Git 历史。没有历史 ADR 目录或旧编号跳转层。

## 按需求阅读

| 主题 | 范围 |
| --- | --- |
| [Goal](goal/README.md) | 目标生命周期、连续执行、预算与完成 |
| [Provider](provider/README.md) | 模型选择、元数据与连接验证、订阅适配、任务路由与流式结果 |
| [权限与审批](permissions/README.md) | 会话授权预设、自定义权限与工具禁用、Chat/Plan 审阅与内置元数据操作、工具执行准入、精确审批与持久审计 |
| [工作目录与文件](workspace/README.md) | 会话目录、相对路径与内容身份、独立文件管理与传输恢复、Git 仓库一致性与产品边界、独立会话工作目录与外部资源绑定 |
| [Runtime 与终端](runtime/README.md) | 执行域、打包、生命周期与结果对账、命令日志、后台 Job 与手动终端、QuickJS 隔离执行底座、RootService 依赖与调用边界 |
| [工具契约](tools/README.md) | 工具描述契约与审批绑定 |
| [MCP](mcp/README.md) | MCP Client、传输与工具接入 |
| [Skill](skills/README.md) | Skill 创作、安装与 MCP 配置闭环 |
| [Connector](connectors/README.md) | Connector 能力包、Connector public-client OAuth、Connector 版本所有权与安装事务、Connector 签名索引与来源 |
| [A2A](a2a/README.md) | A2A Client 与远端任务对账 |
| [Agent 执行与上下文](agent/README.md) | Turn 批次协调与持久结算、模型请求上下文与步骤边界压缩、附件快照与请求物化、有界只读委托与工作流边界、按会话 JSONL 导出 |
| [平台与基础设施](platform/README.md) | 产品完整性与渠道分发、浏览器 View 与逻辑标签生命周期、领域值的严格存储编码 |

## 决策与交付

- `accepted` 是已授权的设计；实际能力和证据查[实施状态](../development/status.md)、[路线](../development/roadmap.md)及完成记录。
- `proposed` 仍需决定，不能因整理文档而接受。会话工作目录绑定、工具 descriptor 完整契约和 Connector 扩展保持候选。
- 会话权限新方案已接受并由 HXA-209 交付（见[完成记录](../completion-records/HXA-209.md)）；Goal 按 HXA-208 的范围交付。二者不能共用“全绿”结论。
- 已授权方案可以要求重构现有代码。实现尚未跟上不是架构冲突，不恢复被废弃的兼容行为；未授权的范围变化才需新的决定。

## 编写与更新

文件为 `<topic>/NNN-short-title.md`，标题为 `ADR-TOPIC-NNN`。编号只在主题内唯一，不映射旧的全局编号。跨主题职责用链接，单个决定不复制到多个目录。

使用 `Status`、`Date`、`HXA`、`Deciders` 字段及 Context、Decision、Alternatives considered、Consequences、Verification、Reconsider when、References 章节。新方案默认 proposed；只有所有者明确授权才 accepted。不要使用 implemented 作为 ADR 状态。

同一职责的调整直接收敛现行文本；重要新取舍先以 proposed 评审，授权后合并有效内容并删除失效部分。过时方案只在 Alternatives 中保留有用的“不采用及原因”，不再保留 Supersedes/Superseded by 链或历史副本。删除文档不授权删除用户数据或审计证据。

改变授权、信任、执行域、数据持久化、跨模块契约、核心依赖或发行边界需要明确决策。普通 bug 修复和事实性路径更新不制造新 ADR。任务完成记录链接当前相关决定并说明验收范围；过去的完成记录不能被解释为新方案已通过。

依赖名称和选型理由可写入决定，当前确切版本以 catalog/lockfile 为准；不要把一次 Spike 版本或旧测试数量写成永久约束。Verification 区分验收要求与已执行证据，不复制长篇流水账。

## 检查

运行 `./scripts/check-all.sh --source`：递归检查主题/编号/标题、状态、字段、章节及本地链接。代码、数据库和设备改动还需对应 HXA 的功能门禁。文档整理不修改验收结论，也不降低现有测试要求。
