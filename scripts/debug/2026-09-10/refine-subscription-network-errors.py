"""Separate smoke network stages and preserve safe, actionable failure information."""
from pathlib import Path
root=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app')
p=root/'CodexSubscriptionSmoke.kt';s=p.read_text();s=s.replace('        client.newCall(request).execute().use { response ->','        smokeNetwork("response") { client.newCall(request).execute().use { response ->',1).replace('            return CodexSmokeResult(model, CodexSmokeStream.read(response.body.source()))\n        }','            return CodexSmokeResult(model, CodexSmokeStream.read(response.body.source()))\n        } }',1)
s=s.replace('        client.newCall(request).execute().use { response ->','        smokeNetwork("models") { client.newCall(request).execute().use { response ->',1).replace('            return ModelDiscovery(model, response.code)\n        }','            return ModelDiscovery(model, response.code)\n        } }',1)
s += '''
internal class CodexSmokeNetworkException(val stage: String, cause: java.io.IOException) : java.io.IOException(cause)

private inline fun <T> smokeNetwork(stage: String, block: () -> T): T =
    try { block() } catch (failure: java.io.IOException) {
        android.util.Log.w("HelixSubscriptionIo", "phase=$stage causes=" +
            generateSequence<Throwable>(failure) { it.cause }.take(6).joinToString(",") { it.javaClass.simpleName })
        throw CodexSmokeNetworkException(stage, failure)
    }
''';p.write_text(s)
p=root/'CodexLoginFailure.kt';s=p.read_text().replace('            is IOException -> {','            is CodexSmokeNetworkException -> {\n                context.getString(R.string.codex_smoke_network_error, error.stage, error.safeNetworkCategory())\n            }\n\n            is IOException -> {')
s=s.replace('            else -> "io"','            causes.any { it is java.net.SocketException } -> "socket"\n            else -> "io"');p.write_text(s)
for locale in ['values','values-zh-rCN','values-en']:
 p=Path('runtime/cli-app/src/main/res')/locale/'strings.xml';s=p.read_text();text='Model test network failure (stage %1$s, category %2$s). Check DNS mappings, proxy/VPN and network access; this does not establish login expiry. Existing credentials are retained.' if locale=='values-en' else '模型测试网络失败（阶段 %1$s，类别 %2$s）。请检查域名解析、代理/VPN及网络连通性；这不代表登录过期，已有凭据保留。'
 s=s.replace('</resources>',f'    <string name="codex_smoke_network_error">{text}</string>\n</resources>');p.write_text(s)
