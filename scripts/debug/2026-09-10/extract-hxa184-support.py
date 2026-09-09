#!/usr/bin/env python3
"""One-shot extraction of pure mapping, deadline and UI support owners for HXA-184."""
from pathlib import Path
import re
R=Path(__file__).resolve().parents[3]
def load(rel):
 p=R/rel;s=p.read_text();end=max(m.end() for m in re.finditer(r'^import .*\n',s,re.M));return p,s,s[:end]+'\n'
def write(p,s): p.write_text(s)
def new(p,name,h,b):
 t=p.with_name(name+'.kt');assert not t.exists(),t;t.write_text(h+b.rstrip()+'\n')
# Compose sections execute the same service calls, state remains scoped to the same composable.
p,s,h=load('app/src/main/kotlin/com/helix/app/ui/SettingsScreen.kt')
a=s.index('/**\n * The PRoot Runtime section');b=s.rfind('/**', 0, s.index('private fun LanguageSection'))
new(p,'ProotRuntimeSection',h,s[a:b].replace('private fun ProotRuntimeSection','internal fun ProotRuntimeSection'))
new(p,'LanguageSection',h,s[b:].replace('private fun LanguageSection','internal fun LanguageSection'))
write(p,s[:a])
# Browser mappings contain no controller/Looper state.
p,s,h=load('feature/browser/src/main/kotlin/com/helix/feature/browser/BrowserToolBridgeImpl.kt')
a=s.index('    private fun successSnapshot');b=s.index('    // ---------------------------------------------------------------- helpers',a);c=s.index('    private fun JsonObject.str')
body=s[a:b]+s[c:s.rindex('}')]
body=body.replace('private fun successSnapshot','fun successSnapshot').replace('private fun failedSnapshot','fun failedSnapshot').replace('private fun mapAction','fun mapAction').replace('private fun mapScroll','fun mapScroll')
new(p,'BrowserToolResultMapper',h+'internal object BrowserToolResultMapper {\n    private val actionJson = Json { ignoreUnknownKeys = true }\n',body+'}\n')
s=s[:a]+s[b:c]+s[s.rindex('}'):]
for name in ['successSnapshot','failedSnapshot','mapAction','mapScroll']:
 s=re.sub(r'(?<![\w.])'+name+r'\(', 'BrowserToolResultMapper.'+name+'(',s)
write(p,s)
# Download queue and worker move together; WebView owners and invalidation stay in controller.
p,s,h=load('feature/browser/src/main/kotlin/com/helix/feature/browser/BrowserController.kt')
a=s.index('    private val _downloads');b=s.index('    /** Live tab state',a)
fields=s[a:b];x=s.index('    /**\n     * Entry point for the WebView download');y=s.index('    // ---------------------------------------------------------------- 独立的清除入口',x)
actions=s[x:y];u=s.index('    /** One fail-closed return per guard (non-2xx response');v=s.rindex('}')
helpers=s[u:v]
new(p,'BrowserDownloadQueue',h+'internal class BrowserDownloadQueue(private val appContext: Context) {\n',fields+'    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()\n\n'+actions+helpers+'}\n')
s=s[:u]+s[v:];s=s[:x]+'''    fun requestDownload(request: DownloadRequest) = downloadQueue.requestDownload(request)

    fun saveDownload(itemId: String, documentUri: Uri) = downloadQueue.saveDownload(itemId, documentUri)

    fun dismissDownload(itemId: String) = downloadQueue.dismissDownload(itemId)

'''+s[y:];s=s[:a]+'    private val downloadQueue = BrowserDownloadQueue(appContext)\n\n'+s[b:]
s=s.replace('= _downloads.asStateFlow()','= downloadQueue.downloads');write(p,s)
# Deadline execution observes the same Clock and cached executor; approval remains in dispatcher.
p,s,h=load('tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDispatcher.kt')
a=s.index('    /**\n     * Enforces the [ToolExecutor]');b=s.index('    /** Stage 7:',a)
body=s[a:b].replace('private fun executeWithinDeadline','fun executeWithinDeadline').replace('EXECUTOR_SERVICE','executorService')
new(p,'ToolDeadlineRunner',h+'internal class ToolDeadlineRunner(private val clock: Clock, private val executorService: ExecutorService) {\n',body+'}\n')
s=s[:a]+s[b:];s=s.replace('executeWithinDeadline(', 'deadlineRunner.executeWithinDeadline(')
pos=s.index('    private val deniedLock');s=s[:pos]+'    private val deadlineRunner = ToolDeadlineRunner(clock, EXECUTOR_SERVICE)\n\n'+s[pos:];write(p,s)
