#!/usr/bin/env python3
"""Clean imports only in the explicitly tracked HXA-184 extraction files."""
from pathlib import Path
import re
R=Path(__file__).resolve().parents[3]
manifest=R/'build/debug/2026-09-10/hxa184/organized-paths.txt'
paths={R/p for p in manifest.read_text().splitlines()}
extra={
'app/src/main/kotlin/com/helix/app':['AppContainer','DefaultAppContainer','AppWorkspaceTools','AppAndroidTools'],
'app/src/main/kotlin/com/helix/app/approval':['ApprovalCardUi','ApprovalUiMapper'],
'app/src/main/kotlin/com/helix/app/ui':['SettingsScreen','ProotRuntimeSection','LanguageSection'],
'app/src/main/kotlin/com/helix/app/files':['FileManagerService','FileManagerBatchOperations'],
'app/src/main/kotlin/com/helix/app/provider':['ProviderService','ProviderConnectionProbe'],
'app/src/main/kotlin/com/helix/app/a2a':['A2aTaskRunner','A2aTaskArtifacts'],
'feature/browser/src/main/kotlin/com/helix/feature/browser':['BrowserController','BrowserDownloadQueue','BrowserToolBridgeImpl','BrowserToolResultMapper'],
'extensions/mcp/src/main/kotlin/com/helix/extensions/mcp':['SdkMcpClientFacade','SdkMcpClientSession','NegotiatedProtocolTransport','McpSdkContentMapping','McpHttpClientFactory'],
'runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs':['JsExecutionClient','JsTransportPreparation','JsClientPreflight','JsExecutionService','JsServiceValidation'],
'core/workspace/src/main/kotlin/com/helix/core/workspace':['WorkspaceArtifactStore','WorkspaceTrashOperations'],
'provider/api/src/main/kotlin/com/helix/provider/api':['WireModelProvider','WireModelCatalogParser'],
 'tools/android/src/main/kotlin/com/helix/tools/android':['HttpFetchBridgeImpl','HttpFetchResponseParser'],
 'tools/framework/src/main/kotlin/com/helix/tools/framework':['ToolDispatcher','ToolDeadlineRunner'],
}
for d,names in extra.items():paths.update(R/d/(n+'.kt') for n in names)
for p in paths:
 if not p.exists(): continue
 s=p.read_text().replace('import com.helix.core.workspace.WorkspaceArtifactStore.TRASH_ENTRY_NAME','import com.helix.core.workspace.WorkspaceArtifactStore.Companion.TRASH_ENTRY_NAME')
 s=s.replace('import com.helix.app.files.FileManagerService.ConflictPolicy','import com.helix.app.files.FileManagerService.FileOpResult')
 if p.stem in ['AndroidSystemTools','AndroidOpenUriTool','AndroidShareTool','ClipboardReadTool','ClipboardWriteTool']:
  s=re.sub(r'\bST_REFUSED\b','ANDROID_SYSTEM_REFUSED',s)
 if p.stem=='BrowserToolBridgeImpl':s=s.replace('        val actionJson = Json { ignoreUnknownKeys = true }\n','')
 body=re.sub(r'^import .*\n','',s,flags=re.M)
 def keep(m):
  sym=m[0].strip().split('.')[-1]
  return m[0] if sym in ['getValue','setValue'] or ' as ' in m[0] or re.search(r'\b'+re.escape(sym)+r'\b',body) else ''
 p.write_text(re.sub(r'^import .*\n',keep,s,flags=re.M))
manifest.write_text('\n'.join(sorted(str(p.relative_to(R)) for p in paths))+'\n')
