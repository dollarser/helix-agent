from pathlib import Path
p=Path('docs/architecture/overview.md');s=p.read_text();s+='''

### 文件传输中断恢复（HXA-182 / ADR-0042）

手动复制/移动通过 app 私有 noBackup 操作日志保存阶段、scoped 路径及发布前树内容指纹；不记录在 Agent 可读根或聊天数据库。文件首页和目录页提供“传输恢复”，只在用户点击后重验权限并恢复，不在启动时自动写外部存储。发布前回滚到可重试状态；发布后核实目标并保留剩余源文件，不自动重放移动源删除。目标、备份变化或权限失效时保留现场与记录并明确失败。恢复成功后仍需用户检查剩余源目录。

这是进程中断对账，不是字节断点续传或持久后台队列；现有 picker 导入/导出、跨来源传输和旧版无日志暂存文件不在此契约内。方案见 [ADR-0042](../adr/0042-manual-transfer-recovery-journal.md)。

`FileManagerService` 委托 `FileManagerPreview` 处理有界预览/元数据/分享暂存，委托 `FileManagerTrash` 处理回收站查询/恢复/清空；公开服务入口与结果类型保持。`ChatStatusLabels` 负责协议错误到当前语言文案的映射，ChatService 保留生命周期与单会话准入。
''';p.write_text(s)
p=Path('docs/adr/0041-manual-file-management-mutations.md');s=p.read_text().replace('## References','进程中断的显式恢复由 [ADR-0042](0042-manual-transfer-recovery-journal.md) 部分扩展；不改变本决策的手动/Agent 权限分离。\n\n## References');p.write_text(s)
