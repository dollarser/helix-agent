from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/files/FileManagerService.kt');s=p.read_text();a=s.index('/**');b=s.index('@Suppress',a);s=s[:a]+'''/**
 * User-operated browse, preview, transfer and trash facade. Manual operations do not create
 * model ToolCalls or expand Agent scopes. Workspace metadata remains private; shared storage
 * and writable SAF trees use independently injected, live-permission-checked backends.
 *
 * [WorkspaceArtifactStore] preserves existing workspace and import/export contracts.
 * [ManualFileOperations] handles directory transfers and external mutations. JVM tests inject
 * NIO backends; Android SAF and OS permissions are composed by AppFileServices, never by UI.
 * [realFileFor] supplies a transient sharing file, not a model-visible absolute path.
 */
'''+s[b:];s=s.replace('// HXA-057: the governed SAF tree scope access (browse/preview/share, read-only). Null only in','// Governed SAF browse access; manual mutations use their own injected backend. Null only in');s=s.replace('all-files roots (developer, read-only) + any SAF tree scopes STILL LIVE right now (read-only).','all-files roots (developer, read-only) + live SAF and manual shared-storage capabilities.');a=s.index('    /**\n     * One rename/move/copy through the store.');b=s.index('    @Suppress',a);s=s[:a]+'''    /** Manual operations use the injected backend; legacy workspace-only tests use the store. */
'''+s[b:];a=s.index('        // HXA-057: SAF tree scopes are read-only in this milestone');b=s.index('        if (isSaf(scopeId))',a);s=s[:a]+'        // A missing manual backend never falls through to a writable SAF implementation.\n'+s[b:];s=s.replace('        relativePath: String,\n    ): FileOpResult {\n        if (scopeId != workspaceScopeId', '        relativePath: String,\n        shouldCancel: () -> Boolean = { false },\n    ): FileOpResult {\n        if (scopeId != workspaceScopeId');s=s.replace('manual.delete(scopeId, relativePath)', 'manual.delete(scopeId, relativePath, shouldCancel)').replace('mapItem(rel, trash(scopeId, rel))','mapItem(rel, trash(scopeId, rel, shouldCancel))');s=s.replace('listTrash(scopeId)\n            .also { entries -> entries.forEach { purge(scopeId, it.entryName) } }\n            .size','listTrash(scopeId).count { purge(scopeId, it.entryName) is FileOpResult.Ok }');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/AppFileServices.kt');s=p.read_text().replace('The file manager consumes\n     * it read-only;', 'The file manager reuses\n     * the same live grant service for reads and manual writes;').replace('SAF\n     * scopes are browse/preview/share-only in this milestone (the all-files precedent: mutations\n     * hidden; a read-only grant\'s write re-verification fails closed regardless).','Manual SAF writes are separately composed below and never reuse an Agent Tool scope.').replace('is the always-present, mutable workspace; all-files roots (developer) are appended read-only,\n     * and SAF tree scopes (HXA-057) are appended read-only and re-verified on every browse.','is the always-present workspace. Explicit manual shared-storage and writable SAF operations\n     * are separate from developer Agent all-files roots and recheck their own permissions.');p.write_text(s)
p=Path('docs/architecture/overview.md');s=p.read_text().replace('下表 LOC 是 2026-09-01 复核时的工作树快照，只用于表达相对规模，不是验收门禁；职责和契约比行数更权威。','除注明新 HXA 的行外，下表是 2026-09-01 的历史结构评估；不将旧 LOC 当作当前源码规模或未完成任务。职责和契约比行数更权威。');lines=s.splitlines();lines=[('| `ChatService.kt`（HXA-179） | 保留会话准入、草稿/附件、Turn 生命周期和 UI facade；工具执行、结果持久化、时间线、模型循环、Goal 显式操作与恢复分别委托给职责组件 | UI 仍只依赖 application-service；取消、预算与活动会话状态不复制，按真实边界继续维护，验收见 HXA-179 |' if line.startswith('| `ChatService.kt`') else line) for line in lines];s='\n'.join(lines)+'\n';s+='''
### 独立文件管理边界（HXA-180 / ADR-0041）

文件页不依赖已配置模型或已创建会话。Workspace、共享存储和用户授权 SAF 分别按实际权限提供浏览与整理；手动变更由 ManualFileOperations、ManualFileTree 和 NIO/SAF backend 执行，不注册为 Agent 工具。共享根仍只存在于手动 resolver。

当前目录内支持新建文件夹、文件/文件夹重命名、复制、移动、删除及冲突选择；Workspace 文件和文件夹进入回收站，可恢复/永久删除。共享/SAF 删除须确认并永久执行。跨来源仍使用导入/导出入口。复制先流式写入临时兄弟节点，重读验证后发布；覆盖保留旧目标到发布成功，移动保留源到目标验证，取消或失败显示实际部分结果。Provider 不支持重命名/删除/创建等能力时明确失败。

这些是显式用户操作能力，不自动扩大 Agent 的 Workspace、SAF 或 all-files scope。Android/data、其他 App 私有目录、后台长期传输队列和商店审核仍不由此获得保证。
''';p.write_text(s)
p=Path('README.md');s=p.read_text().replace('M7 规划的 A2A 仅作为','M7 的 A2A 仅作为');s=s.replace('- 所有工具进入同一条', '- 文件管理器可独立于模型与会话使用；手动文件权限不自动成为 Agent 的可用范围。\n- 所有工具进入同一条');p.write_text(s)
