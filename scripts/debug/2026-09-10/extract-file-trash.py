from pathlib import Path
import re
p=Path('app/src/main/kotlin/com/helix/app/files/FileManagerService.kt');s=p.read_text();a=s.index('    /** The current trash contents');b=s.index('    // --- Batch',a);block=s[a:b];da=s.index('    // --- Trash-entry name decoding');db=s.index('    private fun joinPath(',da);decode=s[da:db];head=s[:s.index('/**')];out=head+'''import com.helix.app.files.FileManagerService.FileOpResult
import com.helix.app.files.FileManagerService.TrashEntryView

/** Workspace recycle-bin queries and explicit recovery/purge; not shared-storage deletion. */
internal class FileManagerTrash(
    private val store: WorkspaceArtifactStore,
    private val workspaceScopeId: String,
    private val directoryTrash: ManualWorkspaceTrash,
    private val strings: (Int, Array<out Any>) -> String,
) {
    private fun loc(id: Int): String = strings(id, emptyArray())
    private fun joinPath(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"
'''+block+decode+'''    private companion object { const val MAX_LIST_ENTRIES = 500 }
}
''';body='\n'.join(l for l in out.splitlines() if not l.startswith('import '));out='\n'.join(l for l in out.splitlines() if not l.startswith('import ') or re.search(r'\b'+re.escape(l.rsplit('.',1)[-1])+r'\b',body))+'\n';Path(p.parent/'FileManagerTrash.kt').write_text(out);s=s[:da]+s[db:];s=s[:a]+'''    private val trashOps = FileManagerTrash(store, workspaceScopeId, directoryTrash, strings)

    fun listTrash(scopeId: String): List<TrashEntryView> = trashOps.listTrash(scopeId)
    fun restore(scopeId: String, entryName: String): FileOpResult = trashOps.restore(scopeId, entryName)
    fun purge(scopeId: String, entryName: String): FileOpResult = trashOps.purge(scopeId, entryName)
    fun emptyTrash(scopeId: String): Int = trashOps.emptyTrash(scopeId)

'''+s[b:];p.write_text(s)
