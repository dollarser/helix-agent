from pathlib import Path
p=Path('docs/development/roadmap.md')
p.write_text(p.read_text()+'''\n\n### HXA-182 大类职责继续收敛与文件传输恢复\n\n状态：in progress。所有者授权继续收敛大类职责、文件传输中断恢复。允许 app/chat、app/files、app/UI、相关测试、docs、scripts/debug；不提交、推送或合并 main。保持公开接口，拆分文件预览和聊天展示映射；手动复制/移动持久阶段记录、重启显式恢复及权限/内容重验，不盲目重放源删除。验收：双 app JVM、双 flavor APK/测试 APK、Spotless/Detekt/lintDebug/双 app lint、独占 API29/36 文件与聊天回归、恢复故障边界和文档门禁。\n''')
p=Path('docs/development/status.md');s=p.read_text();s=s.replace('无。本批 HXA-179～181 已完成，保留当前工作树，尚未提交、推送或合并 main。','HXA-182：大类职责继续收敛与文件传输恢复。所有者已授权；保持当前工作分支，不合并 main。');s=s.replace('等待所有者选择下一项工作或提交范围；不自动启动新功能。','完成 HXA-182 的职责拆分、持久恢复和对应验收。');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/FileManagerService.kt');s=p.read_text();a=s.index('    // --- Preview');b=s.index('    // --- Mutations',a);block=s[a:b];meta=block[block.index('    data class FileMeta('):block.index('    /**\n     * The real [File]')];block=block.replace(meta,'');block=block.replace('FileMeta','FileManagerService.FileMeta');header=s[:s.index('/**')];helpers=s[s.index('    private fun sha256Hex'):s.index('\n}\n\n/** The access source')];helpers=helpers.replace('    private fun joinPath(\n        dir: String,\n        name: String,\n    ): String = if (dir.isEmpty()) name else "$dir/$name"\n\n','');support=s[s.index('    /** True when [scopeId]'):s.index('    // --- 导入')];Path(p.parent/'FileManagerPreview.kt').write_text(header+'''/** Bounded preview, metadata and transient sharing; no mutation or transfer ownership. */
internal class FileManagerPreview(
    private val store: WorkspaceArtifactStore,
    private val roots: ScopeRootResolver,
    private val saf: SafTreeScopeAccess?,
) {
'''+support+block+helpers+'\n}\n');delegates='''    private val preview = FileManagerPreview(store, roots, saf)

    fun previewText(scopeId: String, relativePath: String, maxBytes: Long = DEFAULT_PREVIEW_BYTES): String? =
        preview.previewText(scopeId, relativePath, maxBytes)

    fun previewImageBytes(scopeId: String, relativePath: String, maxBytes: Long = MAX_IMAGE_PREVIEW_BYTES): ByteArray =
        preview.previewImageBytes(scopeId, relativePath, maxBytes)

    fun mimeTypeFor(scopeId: String, relativePath: String): String = preview.mimeTypeFor(scopeId, relativePath)

    fun fileInfo(scopeId: String, relativePath: String, maxHashBytes: Long = MAX_HASH_BYTES): FileMeta =
        preview.fileInfo(scopeId, relativePath, maxHashBytes)

    fun realFileFor(scopeId: String, relativePath: String): File = preview.realFileFor(scopeId, relativePath)

''';s=s[:a]+delegates+meta+s[b:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/ChatService.kt');s=p.read_text();a=s.index('    /**\n     * The localized label for a terminal');b=s.index('    private val _sessions',a);block=s[a:b].replace('private fun terminalLabel','fun terminalLabel').replace('private fun egressRejectedLabel','fun egressRejectedLabel');imports='\n'.join(l for l in s.splitlines() if l.startswith('import ') and any(t in l for t in ['.R','.TurnState','.ModelErrorCode','.ForbiddenContentGuard']));Path(p.parent/'ChatStatusLabels.kt').write_text('package com.helix.app.chat\n\n'+imports+'''\n\n/** Stable protocol/error facts mapped to localized presentation at display time. */
internal class ChatStatusLabels(private val strings: (Int, Array<out Any>) -> String) {
    private fun str(id: Int): String = strings(id, emptyArray())
'''+block+'}\n');s=s[:a]+'''    private val labels = ChatStatusLabels(strings)
    private fun terminalLabel(state: TurnState, errorCode: String?): String? = labels.terminalLabel(state, errorCode)
    private fun egressRejectedLabel(code: String): String = labels.egressRejectedLabel(code)

'''+s[b:];p.write_text(s)
