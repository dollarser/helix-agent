"""Make Codex connection checks authenticated catalog-only, independent of selected model."""
from pathlib import Path
p=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexSmokeCatalog.kt');s=p.read_text().replace('        return models(bytes).firstNotNullOfOrNull(::visibleSlug)','        return models(bytes).firstNotNullOfOrNull(::visibleSlug)');# preserve legacy generation diagnostics
p=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexSubscriptionSmoke.kt');s=p.read_text();pos=s.index('    fun run(): CodexSmokeResult')
s=s[:pos]+'''    /** Authenticated account check only. No model selection or generation request. */
    fun checkConnection() {
        var session = vault.load(CliSubscriptionProvider.CODEX)
        var result = discoverModel(session.accessToken, accountId(session))
        if (result.httpCode == 401) {
            oauth.refresh()
            session = vault.load(CliSubscriptionProvider.CODEX)
            result = discoverModel(session.accessToken, accountId(session))
        }
        if (result.model == null) throw CodexSmokeException("models", result.httpCode)
    }

'''+s[pos:];p.write_text(s)
p=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexLoginActivity.kt');s=p.read_text();start=s.index('        val jobId = ',s.index('    private fun runSubscriptionSmoke()'));end=s.index('\n    }\n}',start)
s=s[:start]+'''        worker.execute {
            val result = runCatching { smoke.checkConnection() }
            smoke.close()
            if (activeSmoke !== smoke) return@execute
            activeSmoke = null
            finishAttempt(
                result.fold(
                    onSuccess = { getString(R.string.codex_connection_success) },
                    onFailure = {
                        CodexLoginFailure.smokeResult(this, Result.failure(it))
                    },
                ),
            )
        }'''+s[end:];s=s.replace('import java.security.MessageDigest\n','').replace('import java.util.UUID\n','');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/provider/SubscriptionConnectionProvider.kt');p.write_text('''package com.helix.app.provider

import com.helix.provider.api.ModelCatalogResult

/** Only an authenticated remote account catalog qualifies; local fallback model lists do not. */
internal interface SubscriptionConnectionProvider {
    suspend fun connectionCatalog(): ModelCatalogResult?
}
''')
p=Path('app/src/developer/kotlin/com/helix/app/provider/CodexSubscriptionProvider.kt');s=p.read_text().replace(') : ModelProvider {',') : ModelProvider, SubscriptionConnectionProvider {',1);pos=s.index('    override suspend fun listModels()');s=s[:pos]+'''    override suspend fun connectionCatalog(): ModelCatalogResult? =
        if (catalogLoader != null) listModels() else null

'''+s[pos:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/provider/ProviderConnectionProbe.kt');s=p.read_text();a=s.index('    private suspend fun checkConnection(');body=s[a:];body=body.replace('    private suspend fun checkConnection(', '    suspend fun run(').replace('        provider: ModelProvider,','        provider: ModelProvider,\n        previous: ProviderCapabilities?,',1).replace('        val catalog = provider.listModels()','        val accountCatalog = (provider as? SubscriptionConnectionProvider)?.connectionCatalog()\n        val catalog = accountCatalog ?: provider.listModels()');body=body.replace('        // Exactly one ordinary, short generation.', '        if (accountCatalog != null) return connected(previous, catalog)\n        // Exactly one ordinary, short generation.');body=body.replace('        val previous = (testStatus.statusFor(config.id) as? ConnectionTestStatus.Passed)?.capabilities\n        return ProbeOutcome.Ok(','        return connected(previous, catalog)\n    }\n\n    private fun connected(previous: ProviderCapabilities?, catalog: ModelCatalogResult): ProbeOutcome = ProbeOutcome.Ok(')
imports='\n'.join(x for x in s.splitlines() if x.startswith('import ') and any(y in x for y in ['ModelErrorCode','ModelEvent','ModelMessage','ModelRequest','ModelRole','CapabilitySource','ModelCatalogResult','ModelProvider','ProbeOutcome','ProviderCapabilities','ProviderConfig','flow.toList']))
# connected used expression body: original function closing brace is no longer needed
body=body.rsplit('    }\n}',1)[0]+'}\n'
Path('app/src/main/kotlin/com/helix/app/provider/ProviderConnectionCheck.kt').write_text('package com.helix.app.provider\n\n'+imports+'\n\ninternal object ProviderConnectionCheck {\n'+body)
s=s[:a]+'}\n';s=s.replace('                checkConnection(config, provider)','                ProviderConnectionCheck.run(\n                    config, provider,\n                    (testStatus.statusFor(config.id) as? ConnectionTestStatus.Passed)?.capabilities,\n                )')
for name in ['ModelErrorCode','ModelEvent','ModelMessage','ModelRequest','ModelRole'] :s=s.replace(f'import com.helix.core.model.{name}\n','')
for name in ['CapabilitySource','ModelCatalogResult','ModelProvider','ProviderConfig']:s=s.replace(f'import com.helix.provider.api.{name}\n','')
# ProviderConfig is still used in constructor
s=s.replace('import com.helix.provider.api.ProviderCapabilities\n','import com.helix.provider.api.ProviderConfig\n');s=s.replace('import kotlinx.coroutines.flow.toList\n','');p.write_text(s)
import re
for locale in ['values','values-zh-rCN','values-en']:
 p=Path('runtime/cli-app/src/main/res')/locale/'strings.xml';s=p.read_text();en=locale=='values-en'
 texts={'codex_smoke_action':'Check Codex subscription connection' if en else '测试 Codex 订阅连接','codex_smoke_running':'Checking authentication and model catalog; no generation request…' if en else '正在验证认证与模型目录，不调用模型生成…','codex_connection_success':'Subscription connected. Authentication and model catalog verified; no generation was requested. Test specific model capabilities in Helix.' if en else '订阅连接正常：认证与模型目录已验证，未调用模型生成。具体模型能力请在 Helix 中检测。'}
 for k,v in texts.items():
  tag=f'<string name="{k}">{v}</string>'
  if f'name="{k}"' in s:s=re.sub(f'<string name="{k}">.*?</string>',lambda _:tag,s)
  else:s=s.replace('</resources>','    '+tag+'\n</resources>')
 p.write_text(s)
