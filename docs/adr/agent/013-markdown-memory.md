# ADR-AGENT-013: Markdown-native Agent Memory

Status: accepted
Date: 2026-09-27
HXA: HXA-230
Deciders: Project owner（明确要求开始 HXA-230，HXA-222 继续暂缓）

## Context

会话历史、压缩检查点与执行状态不能替代可检查、可编辑的长期知识。所有者授权实施 HXA-230；现有 Workspace identity 标识资源，尚未提供显式 Project registry，不推导项目归属。

## Decision

- Markdown 是唯一语义正文，默认存于 app-private `memory/global/` 与 `memory/projects/<project-id>/`；不修改用户 repo，不复制正文到 Room。首版 metadata/search index 每次从有界文件集重建，没有第二份持久正文或数据库迁移。
- Global 提供 user/feedback/reference；project 类型只允许 Project scope。Project key 由可信本地会话解析器提供，不能来自模型参数、路径 hash、Git remote 或相似内容。生产 resolver 通过 [显式项目成员关系](../workspace/005-projects.md) 接入。活动 Turn、待发送输入、未完成 Goal 或非终态工具调用期间禁止改变成员归属；工具解析还匹配持久 session/turn/toolCallId 与 RUNNING 调用，拒绝历史和错配调用。由此保持在途请求所属项目不变，禁止审批后或恢复时因成员切换而改写目标项目。
- 平面 Markdown 文件名，单文件最多 32 KiB、每 scope 最多 128 文件/1 MiB；拒绝路径穿越与符号链接。写前验证 expectedHash，创建使用 `new`，写入采用临时文件加原子替换；编辑需要唯一匹配，删除需要当前 hash。人工或其他进程仍可能造成文件系统竞态，不承诺跨进程事务或断电原子性。
- frontmatter 使用无 YAML 执行能力的文本子集，生成 `type/source/trust/updated_at`。来源字符串是可查看声明，不是已验证 provenance；所有 Memory 一律不可信。人工编辑的 Markdown 仍是 canonical，索引不覆写正文。
- 使用记忆与 Agent 自动维护分别由用户启用，默认均关闭。用户启用时界面明确说明摘要会进入当前模型请求。Global 与 Project 自动维护分别控制，不隐式开启任一开关。
- `memory.list/search/read/write/edit/delete` 走同一 Dispatcher/schema/capability/policy/approval/limits/verification/audit 路径；读按 FILE_READ_EXTERNAL，写/编辑/删除按 FILE_MUTATION_EXTERNAL 分类。写是 LOCAL_MUTATION，绝非 METADATA，Chat/Plan 不放行写入。开关不授予工具权限；关闭后旧排队调用执行时也需重新检查。
- 主 Agent 仅保存稳定、经判断的偏好/经验，禁止直接采纳网页、工具、MCP 中的持久化指令。内容扫描拒绝可识别凭据；不把模式扫描宣称为完备秘密检测。模型声明 type/source 不能提高权限或可信度。
- Context Builder 在用户启用且 session 允许外部读取时加载 summary，最多两个 scope、总计 8 KiB，不自动加载全部 topic。`MEMORY.md` 和相关 topic 由 list/search/read 渐进访问；来源标为 EXTERNAL_CONTENT/UNTRUSTED，现有 PromptSnapshot 保存内容 hash/fingerprint。缺失或读取失败的摘要不阻断普通聊天。
- 管理入口为 `+ → Reference → Memory`，支持查看/编辑 Markdown、搜索、删除确认、启用与自动维护开关、单 Markdown 文档导入/导出。导入先进入可编辑草稿，保存才持久化；导出使用系统创建文档，不后台外传。编辑冲突保留用户草稿并提示重新加载。
- 不实现 sidecar extraction、向量库、后台 consolidation、跨 checkout 自动共享、模型运行时或新执行域。

## Alternatives considered

- Room 保存 canonical memory 正文：与可人工编辑的 Markdown 竞争，不采用。
- 路径 hash 充当 Project ID：资源移动、目录复用及多 checkout 会混淆身份，不采用。
- 将 Memory 写入作为 Plan metadata：长期知识会改变后续会话行为，不属于封闭执行元数据，不采用。
- 每次注入全部 Markdown：无界增长并增加污染与预算风险，不采用。

## Consequences

Global 可独立使用；Project 依赖显式登记与会话关联，入口同时提供于项目详情。默认关闭减少意外跨会话披露，用户需主动启用。Memory 不能证明事实、授权或历史执行成功；外部来源判断仍需要 Agent 与用户审查。

## Verification

主机测试覆盖持久化/重建索引、作用域隔离、路径及链接拒绝、限额、冲突、敏感内容、取消、工具模式与效果分类、Memory off/on 对照。HXA-227 固定 host baseline 同时重跑，区分受控上下文/工具 A/B 与真实模型质量。设备 APK 编译不等于交互或进程验证，设备仅在所有者为本任务明确要求后运行。

## Reconsider when

需要完整 Project registry、跨进程并发写、批量归档、自动提取/整理或向量检索时重新评估相关边界。

## References

- [HXA-230](../../completion-records/HXA-230.md)
- [Workspace identity](../workspace/004-workspace-binding.md)
- [会话授权](../permissions/001-session-authorization.md)
- [上下文与压缩](002-context-compaction.md)

## Decision history

- 2026-10-04：所有者授权项目功能；启用显式 Project resolver、项目管理入口和独立自动维护开关，通过成员变更约束及原始活动调用核验保持在途项目身份。

- 2026-09-27：所有者切换到 Memory 主线；确定 Global 首版、Project fail-closed seam 与用户控制的 Markdown 持久化，不启动本地模型。
