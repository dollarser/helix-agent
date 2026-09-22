package com.helix.feature.browser.engine

import android.webkit.WebView
import org.json.JSONException
import org.json.JSONObject

/**
 * Page utilities: Eruda mobile console, page source extraction, dark mode filter,
 * and reader mode content extraction.
 */
object WebPageTools {
    /**
     * Applies or removes a clean dark theme CSS filter on the current page.
     */
    fun applyNightMode(
        webView: WebView,
        enable: Boolean,
    ) {
        val js =
            """
            (function() {
                var id = 'helix-night-mode-style';
                var style = document.getElementById(id);
                if ($enable) {
                    if (!style) {
                        style = document.createElement('style');
                        style.id = id;
                        style.textContent = 'html { filter: invert(90%) hue-rotate(180deg) !important; background: #121212 !important; } img, video, canvas, svg { filter: invert(100%) hue-rotate(180deg) !important; }';
                        (document.head || document.documentElement).appendChild(style);
                    }
                } else {
                    if (style) style.remove();
                }
            })();
            """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Injects the Eruda mobile developer console into the page.
     */
    fun injectEruda(webView: WebView) {
        val js =
            """
            (function () {
                if (window.eruda) {
                    if (window.eruda._isInit) {
                        window.eruda.show();
                    } else {
                        window.eruda.init();
                    }
                    return;
                }
                var script = document.createElement('script');
                script.src = "//cdn.jsdelivr.net/npm/eruda";
                script.onload = function () {
                    if (window.eruda) window.eruda.init();
                };
                (document.head || document.body || document.documentElement).appendChild(script);
            })();
            """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Extracts full HTML source of the page.
     */
    fun extractSource(
        webView: WebView,
        onResult: (String) -> Unit,
    ) {
        val js = "(function() { return document.documentElement.outerHTML; })();"
        webView.evaluateJavascript(js) { raw ->
            val unquoted =
                if (raw != null && raw.startsWith("\"") && raw.endsWith("\"")) {
                    try {
                        JSONObject("{ \"v\": $raw }").getString("v")
                    } catch (
                        @Suppress("SwallowedException") e: JSONException,
                    ) {
                        raw.substring(1, raw.length - 1)
                    }
                } else {
                    raw.orEmpty()
                }
            onResult(unquoted)
        }
    }

    /**
     * Extracts readable article content from the page (lightweight reader mode).
     */
    fun extractReaderContent(
        webView: WebView,
        onResult: (title: String, content: String) -> Unit,
    ) {
        val js =
            """
            (function() {
                var title = document.title || '';
                var article = document.querySelector('article') || document.querySelector('main') || document.body;
                var paragraphs = article ? article.querySelectorAll('p, h1, h2, h3, h4, blockquote') : [];
                var texts = [];
                for (var i = 0; i < paragraphs.length; i++) {
                    var t = paragraphs[i].innerText.trim();
                    if (t.length > 0) texts.push(t);
                }
                return JSON.stringify({ title: title, text: texts.join('\n\n') });
            })();
            """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            if (raw.isNullOrBlank() || raw == "null") {
                onResult("", "")
                return@evaluateJavascript
            }
            try {
                // If wrapped in double json string quotes
                val jsonStr =
                    if (raw.startsWith("\"") && raw.endsWith("\"")) {
                        JSONObject("{ \"v\": $raw }").getString("v")
                    } else {
                        raw
                    }
                val obj = JSONObject(jsonStr)
                onResult(obj.optString("title", ""), obj.optString("text", ""))
            } catch (
                @Suppress("SwallowedException") e: JSONException,
            ) {
                onResult("", "")
            }
        }
    }

    /**
     * Injects cosmetic CSS into the page.
     */
    fun injectCss(
        webView: WebView,
        css: String,
        id: String = "helix-injected-css",
    ) {
        val escaped = css.replace("'", "\\'").replace("\n", " ")
        val js =
            """
            (function() {
                var style = document.getElementById('$id');
                if (!style) {
                    style = document.createElement('style');
                    style.id = '$id';
                    style.textContent = '$escaped';
                    (document.head || document.documentElement).appendChild(style);
                }
            })();
            """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
