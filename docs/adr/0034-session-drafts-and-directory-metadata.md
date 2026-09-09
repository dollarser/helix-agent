# ADR-0034: 草稿会话与目录归属元数据

Status: accepted
Date: 2026-09-09
HXA: HXA-163
Deciders: Project owner（明确要求首次发送才保存、自动标题、可改名及目录归属；本决定限定在该授权范围）
Supersedes: none
Superseded by: none

## Context

新建按钮提前保存空会话；sessions 只有标题和 Provider，没有目录归属。用户要求类似项目会话的组织体验。直接把目录选择当作授权或 PRoot cwd 会改变既有执行边界，超出本次 UI 请求。

## Decision

新建只创建内存草稿，首次显式发送才落 sessions；空白退出不保存。首次有效文本压缩空白并取前 20 个 Unicode code point 作为标题；允许用户改名。草稿附件暂存选择，发送时才走既有导入与批准管线，不自动发送。分享入口既有语义保留。

Room v10 为 sessions 增加可空 directoryRef，保存既有 FileScopePath 的模型安全引用；旧行迁移为 null。用户从已可浏览目录选择，归属仅组织会话，不授予 capability、approval、改变全局 cwd、创建 Git worktree 或移动文件。目录失效可重新选择，不隐藏会话或删除历史。改名及归属不提供为模型 Tool。

## Alternatives considered

不采用提前创建再删除空会话：会引入删除与审计竞态。不以目录名编码标题：改名会丢失归属。不复制桌面 Git/权限模型，复用 Android 文件服务。

## Consequences

新增可兼容的 schema 列及 migration，必须验证旧行和内容保留。草稿不跨进程保存；首次发送后的失败仍保留会话以便恢复。目录归属不保证模型自动使用该目录，执行 cwd 与上下文增强另设检查点，UI 不宣称已切换执行根。

## Verification

HXA-163 覆盖旧 schema 迁移、空草稿、首句落库、重复发送保护、改名和目录选择/清除。实现与测试证据见完成记录；accepted 本身不表示实现完成。

## Reconsider when

需要跨进程恢复未发送草稿、目录作为实际执行 cwd 或目录变更自动调整权限时，另行评估。

## References

- [存储与 Workspace 规范](../architecture/overview.md)
- [能力与授权边界](0012-capability-first-advanced-grants.md)
- [HXA-163](../development/roadmap.md#hxa-163-草稿会话与目录归属)
