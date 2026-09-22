package com.helix.feature.browser.engine

import android.webkit.WebView
import com.helix.feature.browser.storage.UserScript
import java.util.regex.PatternSyntaxException

/**
 * Executes Tampermonkey-style user scripts in WebView safely via evaluateJavascript.
 * User scripts run in an isolated IIFE with try/catch to avoid breaking page scripts.
 */
class UserScriptEngine(
    private var scripts: List<UserScript> = emptyList(),
) {
    fun updateScripts(newScripts: List<UserScript>) {
        scripts = newScripts
    }

    fun injectMatchingScripts(
        webView: WebView,
        url: String,
        runAt: String,
    ) {
        val activeScripts = scripts.filter { it.enabled && it.runAt.equals(runAt, ignoreCase = true) }
        for (script in activeScripts) {
            if (matches(script.matchPattern, url)) {
                val wrapped = wrapScript(script.name, script.code)
                webView.evaluateJavascript(wrapped, null)
            }
        }
    }

    companion object {
        fun matches(
            pattern: String,
            url: String,
        ): Boolean {
            val p = pattern.trim()
            if (p.isEmpty() || p == "*") return true

            val sb = StringBuilder("^")
            for (char in p) {
                when (char) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append(".")
                    '(', ')', '[', ']', '{', '}', '.', '^', '$', '+', '|', '\\' -> sb.append('\\').append(char)
                    else -> sb.append(char)
                }
            }
            sb.append("$")

            return if (url.isEmpty()) {
                false
            } else {
                try {
                    Regex(sb.toString(), RegexOption.IGNORE_CASE).containsMatchIn(url)
                } catch (
                    @Suppress("SwallowedException") e: PatternSyntaxException,
                ) {
                    url.contains(p, ignoreCase = true)
                } catch (
                    @Suppress("SwallowedException") e: IllegalArgumentException,
                ) {
                    url.contains(p, ignoreCase = true)
                }
            }
        }

        fun wrapScript(
            name: String,
            code: String,
        ): String {
            val safeName = name.replace("'", "\\'")
            return """
                (function() {
                    try {
                        $code
                    } catch(e) {
                        console.error('[Helix UserScript: $safeName]', e);
                    }
                })();
                """.trimIndent()
        }
    }
}
