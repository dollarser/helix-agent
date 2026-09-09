#!/usr/bin/env python3
"""One-shot extraction of remaining HXA-184 storage, HTTP, provider and composition boundaries."""
from pathlib import Path
import re
R=Path(__file__).resolve().parents[3]
def load(rel):
 p=R/rel;s=p.read_text();end=max(m.end() for m in re.finditer(r'^import .*\n',s,re.M));return p,s,s[:end]+'\n'
def new(p,name,h,b):
 t=p.with_name(name+'.kt');assert not t.exists(),t;t.write_text(h+b.rstrip()+'\n')
# Trash uses exactly the store's root and containment resolver; no second access policy.
p,s,h=load('core/workspace/src/main/kotlin/com/helix/core/workspace/WorkspaceArtifactStore.kt')
a=s.rfind('    /**',0,s.index('    fun moveToTrash'));b=s.index('    /** Fail-closed conflict pre-check',a)
u=s.index('    private fun uniqueTrashEntryName');v=s.index('    private fun resolve(scope',u)
body=s[a:b]+s[u:v]
new(p,'WorkspaceTrashOperations',h+'import com.helix.core.workspace.WorkspaceArtifactStore.TRASH_ENTRY_NAME\n\ninternal class WorkspaceTrashOperations(\n    private val resolve: (String) -> Path,\n    private val resolveContained: (FileScopePath, Path) -> Path,\n    private val ensureLayout: (String) -> Unit,\n) {\n',body+'}\n')
s=s[:u]+s[v:];s=s[:a]+'''    private val trashOperations = WorkspaceTrashOperations(::resolve, ::resolveContained, ::ensureLayout)

    fun moveToTrash(path: FileScopePath): TrashEntry = trashOperations.moveToTrash(path)

    fun restoreFromTrash(trashRef: FileScopePath): TrashRestoreOutcome = trashOperations.restoreFromTrash(trashRef)

    fun purgeTrashEntry(trashRef: FileScopePath): PurgeOutcome = trashOperations.purgeTrashEntry(trashRef)

    fun deletePermanentlyForPrivacy(path: FileScopePath): Boolean = trashOperations.deletePermanentlyForPrivacy(path)

    fun clearForPrivacy(scope: String) = trashOperations.clearForPrivacy(scope)

'''+s[b:];p.write_text(s)
# HTTP response parsing has no ability to choose addresses, open connections or follow redirects.
p,s,h=load('tools/android/src/main/kotlin/com/helix/tools/android/HttpFetchBridgeImpl.kt')
a=s.index('    private fun parseStatus');b=s.index('    private fun connect(',a)
u=s.index('    private class ProtocolError');v=s.index('    private sealed interface Conn',u)
types=s[u:v].replace('private data class ParsedResponse','data class ParsedResponse')
body=s[a:b];parser='''    fun read(input: InputStream, request: HttpFetchRequest): ParsedResponse {
        val statusLine = readLine(input) ?: throw ProtocolError()
        val status = parseStatus(statusLine)
        val headers = readHeaders(input)
        val (body, bodyBytes, truncated) = if (request.method == "HEAD") {
            Triple("", 0L, false)
        } else {
            readBody(input, headers.contentLength, headers.chunked, request.maxBodyBytes)
        }
        return ParsedResponse(status, headers.location, headers.contentType, body, bodyBytes, truncated)
    }

'''
new(p,'HttpFetchResponseParser',h+'@Suppress("TooManyFunctions")\ninternal object HttpFetchResponseParser {\n',parser+body+types+'}\n')
s=s[:u]+s[v:];s=s[:a]+s[b:]
a=s.index('        val input = secure.getInputStream()');b=s.index('\n    private fun writeRequest',a)
s=s[:a]+'        return HttpFetchResponseParser.read(secure.getInputStream(), request)\n    }\n'+s[b:]
s=s.replace('ParsedResponse','HttpFetchResponseParser.ParsedResponse');p.write_text(s)
# Probe coordinates detection, snapshot/status writes; service retains config and secret mutation.
p,s,h=load('app/src/main/kotlin/com/helix/app/provider/ProviderService.kt')
a=s.index('    /** The probe itself');b=s.index('    /**\n     * The typed config',a)
body=s[a:b].replace('private suspend fun runConnectionTestNow','suspend fun run').replace('_networkOperations.value += 1','onNetworkOperation()').replace('        refreshNow()\n','')
new(p,'ProviderConnectionProbe',h+'''@Suppress("LongParameterList")
internal class ProviderConnectionProbe(
    private val storage: HelixStorage,
    private val factory: ProviderFactory,
    private val testStatus: ProviderTestStatusStore,
    private val probe: CapabilityProbe,
    private val clock: Clock,
    private val managed: ManagedProviderHooks,
    private val storedConfig: suspend (String) -> ProviderConfig,
    private val discoverContextWindow: suspend (String, String) -> ProviderContextSettings,
    private val onNetworkOperation: () -> Unit,
) {
''',body+'}\n')
s=s[:a]+s[b:];s=s.replace('            runConnectionTestNow(providerId)','            connectionProbe.run(providerId).also { refreshNow() }')
pos=s.index('    /**\n     * Re-reads persisted');s=s[:pos]+'''    private val connectionProbe = ProviderConnectionProbe(
        storage, factory, testStatus, probe, clock, managed, ::storedConfig, ::discoverContextWindow,
        { _networkOperations.value += 1 },
    )

'''+s[pos:];p.write_text(s)
# Composition root interface and implementation separate, then extract domain registrations.
p,s,h=load('app/src/main/kotlin/com/helix/app/AppContainer.kt')
a=s.index('internal class DefaultAppContainer')
body=s[a:];p.write_text(s[:a]);new(p,'DefaultAppContainer',h,body)
p=p.with_name('DefaultAppContainer.kt');s=p.read_text()
a=s.index('        // HXA-042: the first non-time');b=s.index('        // HXA-053: the isolated QuickJS',a)
body=s[a:b]
new(p,'AppWorkspaceTools',h+'''internal object AppWorkspaceTools {
    fun register(
        toolRegistry: ToolRegistry,
        toolImplementations: ToolImplementationRegistry,
        workspaceStore: WorkspaceArtifactStore,
    ) {
''',body+'    }\n}\n')
s=s[:a]+'        AppWorkspaceTools.register(toolRegistry, toolImplementations, workspaceStore)\n'+s[b:]
a=s.index('        // HXA-064: the android.open_uri');b=s.index('\n    }',a)
body=s[a:b]
body=body.replace('object : EgressPolicyProvider {\n                    override fun current(): EgressPolicy = EgressPolicy(profileStore.profile, lanScopeStore.current())\n                }','egressPolicy')
new(p,'AppAndroidTools',h+'''internal object AppAndroidTools {
    fun register(
        context: Context,
        toolRegistry: ToolRegistry,
        toolImplementations: ToolImplementationRegistry,
        egressPolicy: EgressPolicyProvider,
    ) {
''',body+'\n    }\n}\n')
s=s[:a]+'''        AppAndroidTools.register(
            context, toolRegistry, toolImplementations,
            object : EgressPolicyProvider {
                override fun current(): EgressPolicy = EgressPolicy(profileStore.profile, lanScopeStore.current())
            },
        )'''+s[b:];p.write_text(s)
