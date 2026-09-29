# Workspace 契约竞品核对

> **2026-09-27 研究快照，2026-09-29 归档。**[HXA-210](../../completion-records/HXA-210.md)已完成其记录范围，本文最后的建议顺序不是当前排期。当前目录行为见[Workspace ADR](../../adr/workspace/004-workspace-binding.md)和[产品说明](../../product/workspace.md)；完整 Project Memory 仍单独见[候选索引](../../development/candidate-decisions.md)。本次不重新验证外部来源。

---

调研日期：2026-09-27。范围：官方文档中的目录、会话、权限与 worktree 契约；不是竞品实机验收，也不推断其内部持久化实现。

| 官方来源 | 已核实的产品行为 | 对 Helix 的意义 |
| --- | --- | --- |
| [Claude Code common workflows](https://code.claude.com/docs/en/common-workflows) | 可在普通目录启动，非代码文件夹也可工作；并行会话可显式使用独立 Git worktree | 外部目录原位工作符合常见用法；不应强制复制或要求 Git |
| [Claude Code memory](https://code.claude.com/docs/en/memory) | 主目录影响项目指令发现；额外目录的访问与指令加载有分别配置 | 文件访问范围和自动上下文发现应分开，选择目录不授权脚本执行 |
| [Codex projects](https://learn.chatgpt.com/docs/projects) 与 [worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees) | 主目录用于新会话及默认 Git/指令发现，可附加其他目录；Local 与 Worktree 是不同工作位置，sandbox 负责实际约束 | 资源、逻辑项目、会话与执行隔离分别建模；首版单主目录是有意缩小范围，不是假称功能完全一致 |
| [Cursor worktrees](https://prod.cursor.com/docs/configuration/worktrees) 与 [Agent security](https://prod.cursor.com/docs/agent/security) | worktree 用于并行修改隔离，工作区文件可直接修改；审批与安全设置另有规则 | 工作树隔离不能被解释为权限隔离，竞品默认授权不能覆盖 Helix 的既定授权契约 |
| [VS Code workspaces](https://code.visualstudio.com/docs/editing/workspaces/workspaces) | 单文件夹可直接作为 workspace，多根 workspace 是显式组合 | 不引入强制项目模板；附加目录可后续独立扩展 |
| [Android SAF](https://developer.android.com/training/data-storage/shared/documents-files) | 通过用户选择获得文档树访问；持久授权仍受资源移动/删除等影响，操作受 Provider 能力约束 | SAF 不是桌面路径；不得伪造 cwd、原子写或永久可用保证 |

## 结论与拟议调整

Helix 与上述产品在“目录承载工作、多个会话可以使用同一资源、权限独立判断、worktree 按需”的方向一致。但“新会话无需项目即可获得 App 管理目录”是手机产品选择，不能表述为所有竞品的共同默认；Android SAF 的能力差异也不能照搬桌面 POSIX 契约。

保留 ADR-WORKSPACE-004 的默认私有目录与外部原位绑定。补充 workspaceId（实际资源）、可选 projectId（逻辑归属）、session binding（会话当前目录）之间的区别：不因路径名、Git remote URL、同名目录或内容相似自动合并项目记忆；移动/重授权必须验证或由用户显式重绑定，失效资源不静默替换。

主目录切换应在模型请求边界可见。已经发出的模型请求及其返回 ToolCall 必须保留发起时绑定，避免模型按旧指令生成的相对路径被解释到新目录。执行时仍检查当前权限与资源可用性。此项比仅冻结已排队的 ToolCall 更完整。

建议 HXA-227 baseline 后实施 HXA-210，再打开完整 Project Memory。Global Memory 技术上不依赖 Workspace；这一排序是产品优先级建议。首版不扩多根、Git worktree、云同步、远程执行或自动记忆。完整 HXA-210 仍须覆盖所支持后端的失败恢复与删除契约，阶段完成不等于整项关闭。
