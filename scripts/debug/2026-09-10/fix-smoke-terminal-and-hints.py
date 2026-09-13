"""Stop diagnostic reads at protocol completion and improve bounded failure hints."""
from pathlib import Path
import re
root=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app')
p=root/'CodexSmokeStream.kt';s=p.read_text();a=s.index('        val bytes = readBounded(source)');b=s.index('    private fun parseEvent',a)
s=s[:a]+'''        val text = StringBuilder()
        var remaining = MAX_STREAM_BYTES
        while (remaining > 0) {
            val newline = source.indexOf('\\n'.code.toByte(), 0, remaining + 1)
            if (newline < 0 && source.buffer.size > remaining) throw CodexSmokeException("response-too-large")
            val count = if (newline >= 0) newline + 1 else source.buffer.size
            if (count == 0L) break
            if (count > remaining) throw CodexSmokeException("response-too-large")
            remaining -= count
            val event = parseEvent(source.readUtf8(count).trimEnd('\\n', '\\r')) ?: continue
            if (accept(event, text)) {
                // SSE completion is the end of this request; the server need not close the socket.
                val normalized = text.toString().trim()
                if (normalized != EXPECTED_TEXT) throw CodexSmokeException("unexpected-output")
                return normalized
            }
        }
        if (remaining == 0L) throw CodexSmokeException("response-too-large")
        throw CodexSmokeException("response-incomplete")
    }

'''+s[b:];p.write_text(s)
p=root/'CodexLoginFailure.kt';s=p.read_text().replace('context.getString(R.string.codex_smoke_failed, it.stage, it.httpCode?.toString() ?: "none")','context.getString(R.string.codex_smoke_failed, it.stage, it.httpCode?.toString() ?: "none") +\n                        " " + context.getString(smokeHint(it.httpCode))')
s=s.replace('    private fun IOException.safeNetworkCategory()', '''    internal fun smokeHint(httpCode: Int?): Int =
        when (httpCode) {
            401 -> R.string.codex_smoke_auth_hint
            403 -> R.string.codex_smoke_forbidden_hint
            429 -> R.string.codex_smoke_rate_hint
            in 500..599 -> R.string.codex_smoke_server_hint
            else -> R.string.codex_smoke_protocol_hint
        }

    private fun IOException.safeNetworkCategory()''');p.write_text(s)
translations={
'codex_smoke_auth_hint':('认证已被服务端拒绝；请在此重新登录后重试。','The server rejected authentication. Sign in again here, then retry.'),
'codex_smoke_forbidden_hint':('服务端拒绝访问；请检查账号权限、网络出口或代理，不一定是登录过期。','Access was denied. Check account access and network/proxy routing; this does not necessarily mean the login expired.'),
'codex_smoke_rate_hint':('服务限流或额度不足，请稍后重试并检查订阅用量。','Rate or usage limit reached. Check subscription usage and retry later.'),
'codex_smoke_server_hint':('服务端暂时异常，请稍后重试，无需先删除登录。','Temporary server failure. Retry later; do not delete your login first.'),
'codex_smoke_protocol_hint':('请重试；若持续失败，请提供此阶段和 HTTP 状态以排查协议或模型响应。','Retry. If it persists, report this stage and HTTP status to investigate the protocol or model response.'),
'codex_smoke_success':('订阅模型测试成功。模型：%1$s；响应：%2$s。','Subscription model test passed. Model: %1$s; reply: %2$s.'),
'codex_smoke_running':('正在获取模型目录并测试一个极小回复…','Fetching the model catalog and testing one minimal reply…'),
}
for locale in ['values','values-zh-rCN','values-en']:
 p=Path('runtime/cli-app/src/main/res')/locale/'strings.xml';s=p.read_text()
 for key,texts in translations.items():
  text=texts[locale=='values-en'];tag=f'<string name="{key}">{text}</string>'
  if f'name="{key}"' in s:s=re.sub(f'<string name="{key}">.*?</string>',lambda _:tag,s)
  else:s=s.replace('</resources>','    '+tag+'\n</resources>')
 p.write_text(s)
for locale in ['values','values-zh-rCN','values-en']:
 p=Path('app/src/main/res')/locale/'strings.xml';s=p.read_text();text='认证或访问权限被服务端拒绝。API Provider 请检查 key；订阅 Provider 请检查登录与账号权限。若 HTTP 403，也可能是网络出口被拒绝。' if locale!='values-en' else 'Authentication or access was rejected. Check the API key, or subscription login and account access. HTTP 403 can also indicate blocked network routing.'
 s=re.sub('<string name="conn_error_auth">.*?</string>',lambda _:f'<string name="conn_error_auth">{text}</string>',s);p.write_text(s)
