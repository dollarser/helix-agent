#!/usr/bin/env python3
"""One-shot helper extraction; stateful cleanup and transaction boundaries remain in owners."""
from pathlib import Path
import re
R=Path(__file__).resolve().parents[3]
def load(rel):
 p=R/rel;s=p.read_text();end=max(m.end() for m in re.finditer(r'^import .*\n',s,re.M));return p,s,s[:end]+'\n'
def new(p,name,h,b):
 t=p.with_name(name+'.kt');assert not t.exists(),t;t.write_text(h+b.rstrip()+'\n')
# Fix only moved-family constant collision.
for name in ['NotificationsCalendarTools','NotificationsQueryTool','CalendarPrepareEventTool','CalendarCommitEventTool']:
 p=R/f'tools/android/src/main/kotlin/com/helix/tools/android/{name}.kt';p.write_text(p.read_text().replace('ST_NO_HANDLER','CALENDAR_NO_HANDLER'))
# Client transport receives the caller-owned cleanup lists, never owns binding or finalization.
p,s,h=load('runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/JsExecutionClient.kt')
a=s.index('    private data class Transport');b=s.index('    /**\n     * Pre-flight rejections',a)
u=s.index('    private fun materializeTemp');v=s.index('    private fun readBounded',u)
body=s[a:b]+s[u:v];body=body.replace('private data class Transport','data class Transport').replace('private fun prepareTransport','fun prepareTransport')
new(p,'JsTransportPreparation',h+'internal class JsTransportPreparation(private val context: Context) {\n',body+'    private val PATH_UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")\n}\n')
s=s[:u]+s[v:];s=s[:a]+s[b:];s=s.replace('prepareTransport(params,','JsTransportPreparation(context).prepareTransport(params,')
s=s.replace('        private val PATH_UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")\n','')
a=s.index('    /**\n     * Pre-flight rejections');b=s.index('    private data class BoundInstance',a)
new(p,'JsClientPreflight',h+'internal object JsClientPreflight {\n',s[a:b].replace('private fun preflightReject','fun preflightReject')+'}\n')
s=s[:a]+s[b:];s=s.replace('preflightReject(params,','JsClientPreflight.preflightReject(params,');p.write_text(s)
# Isolated-service validation is stateless; one-shot slot + interrupt remain in ExecutionBinder.
p,s,h=load('runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/JsExecutionService.kt')
a=s.rfind('        /**',0,s.index('        private fun validateRequest'));b=s.index('        /** Reads exactly',a)
body=s[a:b];body=''.join(l[4:] if l.startswith('    ') else l for l in body.splitlines(True))
body=body.replace('private fun validateRequest','fun validateRequest').replace('private fun validatePayload','fun validatePayload')
new(p,'JsServiceValidation',h+'internal object JsServiceValidation {\n',body+'}\n');s=s[:a]+s[b:]
for n in ['validateRequest','validatePayload']:s=s.replace(n+'(', 'JsServiceValidation.'+n+'(')
p.write_text(s)
# Artifact output import keeps its existing global reconciliation lock in one helper owner.
p,s,h=load('app/src/main/kotlin/com/helix/app/a2a/A2aTaskRunner.kt')
a=s.index('    private fun output(');b=s.index('    private fun bearer(',a)
body=s[a:b].replace('private fun output(', 'fun output(')
constructor='''internal class A2aTaskArtifacts(
    private val storage: HelixStorage,
    private val workspace: WorkspaceArtifactStore,
    private val workspaceScopeId: String,
    private val resolveWorkspaceFile: (FileScopePath) -> java.io.File,
) {
'''
constants='''    private companion object {
        const val UNTRUSTED_MARKER = "UNTRUSTED_A2A_CONTENT"
        const val MAX_ARTIFACT_BYTES = 1024L * 1024
        const val MAX_BASE64_CHARS = 1_500_000
        val ARTIFACT_IMPORT_LOCK = Any()
        val BASE64_PATTERN = Regex("[A-Za-z0-9+/]*={0,2}")
    }
}
'''
new(p,'A2aTaskArtifacts',h+constructor,body+constants);s=s[:a]+s[b:]
s=re.sub(r'(?<!\w)output\(', 'taskArtifacts.output(',s)
pos=s.index('    /** User/recovery');s=s[:pos]+'    private val taskArtifacts = A2aTaskArtifacts(storage, workspace, workspaceScopeId, resolveWorkspaceFile)\n\n'+s[pos:]
for line in ['        const val MAX_BASE64_CHARS = 1_500_000\n','        val ARTIFACT_IMPORT_LOCK = Any()\n','        val BASE64_PATTERN = Regex("[A-Za-z0-9+/]*={0,2}")\n']:s=s.replace(line,'')
p.write_text(s)
# File batch orchestration calls the existing facade; public nested results remain source-compatible.
p,s,h=load('app/src/main/kotlin/com/helix/app/files/FileManagerService.kt')
a=s.index('    /**\n     * Applies [policy]');b=s.index('    /** One batched item\'s outcome',a);c=s.index('    private fun mapItem',b);d=s.index('    private companion object',c)
body=s[a:b]+s[c:d];body=body.replace('moveOrCopy(', 'files.moveOrCopy(').replace('nextAvailableName(', 'files.nextAvailableName(').replace('trash(scopeId,','files.trash(scopeId,')
imports='import com.helix.app.files.FileManagerService.BatchItem\nimport com.helix.app.files.FileManagerService.BatchResult\nimport com.helix.app.files.FileManagerService.ConflictPolicy\n'
new(p,'FileManagerBatchOperations',h+imports+'\ninternal class FileManagerBatchOperations(private val files: FileManagerService, private val loc: (Int) -> String) {\n',body+'}\n')
s=s[:c]+s[d:];s=s[:a]+'''    fun batchMoveOrCopy(
        scopeId: String,
        sources: List<String>,
        destinationDir: String,
        policy: ConflictPolicy,
        move: Boolean,
        progress: (Int, Int) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): BatchResult = FileManagerBatchOperations(this) { loc(it) }
        .batchMoveOrCopy(scopeId, sources, destinationDir, policy, move, progress, shouldCancel)

    fun batchTrash(
        scopeId: String,
        relativePaths: List<String>,
        progress: (Int, Int) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): BatchResult = FileManagerBatchOperations(this) { loc(it) }
        .batchTrash(scopeId, relativePaths, progress, shouldCancel)

'''+s[b:]
# joinPath is also used outside batch, retain a local utility for those calls.
pos=s.index('    private companion object');s=s[:pos]+'    private fun joinPath(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"\n\n'+s[pos:];p.write_text(s)
# Separate stable approval view data from its existing pure mapper.
p,s,h=load('app/src/main/kotlin/com/helix/app/approval/ApprovalCardUi.kt')
a=s.index('/**\n * Maps the execution-path facts');new(p,'ApprovalUiMapper',h,s[a:]);p.write_text(s[:a])
# Provider catalogue parser moves behind the existing protected override seam.
p,s,h=load('provider/api/src/main/kotlin/com/helix/provider/api/WireModelProvider.kt')
a=s.index('    /**\n     * Strict parse');b=s.index('    /**\n     * Joins the endpoint',a);u=s.index('private sealed interface ModelListParse');v=s.index('/**\n * Resolves the plaintext',u)
body=s[a:b].replace('protected open fun parseModelIds','fun parseModelIds')
new(p,'WireModelCatalogParser',h+'internal object WireModelCatalogParser {\n',body+'}\n\n'+s[u:v])
s=s[:u]+s[v:];s=s[:a]+'    protected open fun parseModelIds(body: String): ModelCatalogResult = WireModelCatalogParser.parseModelIds(body)\n\n'+s[b:];p.write_text(s)
