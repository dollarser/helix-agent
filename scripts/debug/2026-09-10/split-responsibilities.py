#!/usr/bin/env python3
"""One-shot HXA-183 extraction from the audited working-tree snapshot; not a migration."""
from pathlib import Path
import re
import textwrap

ROOT = Path(__file__).resolve().parents[3]

def split_file(relative, groups):
    path = ROOT / relative
    original = path.read_text()
    lines = original.splitlines(keepends=True)
    header = original[:original.index('\n\n', original.index('import '))] + '\n\n'
    removed = set()
    for name, ranges, expose in groups:
        body = ''.join(''.join(lines[a-1:b]) for a,b in ranges)
        for symbol in expose:
            body = re.sub(r'private (fun |suspend fun |data class |sealed interface )'+symbol+r'\b', r'internal \1'+symbol, body)
        (path.parent / name).write_text(header + body)
        for a,b in ranges: removed.update(range(a-1,b))
    path.write_text(''.join(line for i,line in enumerate(lines) if i not in removed))

split_file('app/src/main/kotlin/com/helix/app/ui/ChatScreen.kt', [
    ('SessionListSection.kt', [(225,340)], ['SessionListSection']),
    ('ConversationSection.kt', [(341,699)], ['ConversationSection']),
    ('ConversationModeControls.kt', [(700,758)], []),
    ('ToolTimelineItem.kt', [(759,842)], []),
])
split_file('app/src/main/kotlin/com/helix/app/ui/ProviderScreen.kt', [
    ('ProviderFormDialog.kt', [(244,488),(497,517),(747,831)], ['ProviderForm','SaveResult','TemplatePickerDialog','ProviderFormDialog','editingProviderForm','attemptSave']),
    ('ProviderRow.kt', [(489,496),(518,746)], ['ProviderRowActions','ProviderRow']),
])

# Import and export have distinct permission/conflict paths. Keep the original facade.
p = ROOT / 'app/src/main/kotlin/com/helix/app/files/FileManagerServiceTransfers.kt'
s = p.read_text(); lines = s.splitlines(keepends=True)
header = ''.join(lines[:26]) + '\n'
def chunk(a,b): return ''.join(lines[a-1:b])
common = chunk(133,138)
ctor = '''(\n    private val store: WorkspaceArtifactStore,\n    private val workspaceScopeId: String,\n    private val transfers: SafImportExportAccess,\n    private val strings: (Int, Array<out Any>) -> String,\n'''
(p.parent/'FileManagerImports.kt').write_text(header+'/** User-picked document/tree imports; owns workspace conflict handling. */\ninternal class FileManagerImports'+ctor+') {\n'+common+chunk(140,283)+chunk(375,410)+chunk(418,477)+chunk(690,745)+chunk(801,814)+'}\n')
(p.parent/'FileManagerExports.kt').write_text(header+'/** User-requested export; owns live WRITE checks, destination conflicts and verification. */\n@Suppress("TooManyFunctions")\ninternal class FileManagerExports'+ctor+'    private val saf: SafTreeScopeAccess?,\n) {\n'+common+chunk(285,371)+chunk(411,416)+chunk(479,689)+chunk(746,800)+chunk(815,830)+'}\n')
p.write_text(chunk(1,122).replace('@Suppress("TooManyFunctions") // one cohesive transfer seam: single import, tree import, export + policy mapping\n','')+'''class FileManagerTransfers(
    store: WorkspaceArtifactStore,
    workspaceScopeId: String,
    saf: SafTreeScopeAccess?,
    transfers: SafImportExportAccess,
    strings: (Int, Array<out Any>) -> String,
) {
    private val imports = FileManagerImports(store, workspaceScopeId, transfers, strings)
    private val exports = FileManagerExports(store, workspaceScopeId, transfers, strings, saf)

    fun importSingleDocument(sourceUri: String, policy: ConflictPolicy, cancel: SafCancelToken,
        onProgress: (Long, Long) -> Unit): TransferResult =
        imports.importSingleDocument(sourceUri, policy, cancel, onProgress)

    fun importTree(treeUri: String, policy: ConflictPolicy, cancel: SafCancelToken,
        onFileProgress: (Int, Int) -> Unit): TransferResult =
        imports.importTree(treeUri, policy, cancel, onFileProgress)

    fun exportDocument(sourceRelativePath: String, target: ExportTarget, policy: ConflictPolicy,
        cancel: SafCancelToken, onProgress: (Long, Long) -> Unit): TransferResult =
        exports.exportDocument(sourceRelativePath, target, policy, cancel, onProgress)
}
''')

# Message encoding has no dispatch/approval authority.
p = ROOT/'app/src/main/kotlin/com/helix/app/chat/ChatToolCalls.kt'
s = p.read_text(); start=s.index('    fun assistantToolStepJson'); end=s.index('    // ---',start)
body=s[start:end].replace('settled: SettledCall','settled: ChatToolCalls.SettledCall')
header=s[:s.index('/** Owns')]
(p.parent/'ChatToolMessageEncoder.kt').write_text(header+'/** Encodes ordered tool messages; never dispatches or settles a call. */\ninternal class ChatToolMessageEncoder(private val strings: (Int, Array<out Any>) -> String) {\n    private fun str(id: Int, vararg args: Any): String = strings(id, args)\n'+body+'}\n')
s=s[:start]+'''    private val messageEncoder = ChatToolMessageEncoder(strings)
    fun assistantToolStepJson(batch: LocalToolCallBatch): String = messageEncoder.assistantToolStepJson(batch)
    fun toolResultDraft(settled: SettledCall): TurnMessageDraft = messageEncoder.toolResultDraft(settled)

'''+s[end:]
p.write_text(s)

status=ROOT/'docs/development/status.md'
s=status.read_text().replace('无。HXA-182 已完成，保留当前工作分支，未提交、推送或合并 main。','HXA-183：按大类审查拆分 7 项优先职责，保持行为与契约，实施与回归中；不提交、推送或合并 main。')
status.write_text(s)
with (ROOT/'docs/development/roadmap.md').open('a') as f:
    f.write('''

### HXA-183 大类职责拆分

状态：in progress。所有者授权按大类审查拆分；本批处理 ChatService、ChatToolCalls、ChatScreen、ProviderScreen、FileManagerTransfers、LinuxRunTool、ProotJobRunner 七项优先职责。允许 app/chat、app/UI、app/files、app/proot、runtime/proot-app、相关测试、docs、scripts/debug。保持公开 facade、Tool/IPC/持久格式和状态资源所有权，无新功能与依赖。验收：双 app 与 PRoot JVM、双 flavor/companion APK 和测试 APK、Spotless/Detekt/lintDebug/双 app lint、独占 API29/36 聊天/文件/Provider 与 PRoot 真 guest/取消/恢复回归、文档门禁。不提交、推送或合并 main。
''')
