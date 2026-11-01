package com.helix.app.ui

import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/** Closed local presentation resources. This is not an HTML/browser or Agent execution surface. */
internal object FormulaPreviewPolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val BASE = "$ORIGIN/assets/math/"
    const val PAGE = "${BASE}preview.html"
    const val MAX_CHARS = 8192

    fun asset(url: String): String? =
        runCatching {
            val uri = URI(url)
            require(uri.scheme == "https" && uri.rawAuthority == "appassets.androidplatform.net")
            require(uri.rawQuery == null && uri.rawFragment == null && uri.rawPath == uri.path)
            val path = uri.path.removePrefix("/assets/math/")
            require(
                path in setOf("katex.min.js", "katex.min.css") ||
                    path.matches(Regex("fonts/KaTeX_[A-Za-z0-9-]+\\.(woff2|woff|ttf)")),
            )
            require(uri.path == "/assets/math/$path")
            "math/$path"
        }.getOrNull()

    fun html(
        source: String,
        background: Int,
        foreground: Int,
        size: Float,
        nonce: String,
        inline: Boolean = false,
    ): String {
        require(source.isNotBlank() && source.length <= MAX_CHARS && '\u0000' !in source)
        require(nonce.matches(Regex("[a-zA-Z0-9_-]{16,64}")))
        require(size.isFinite() && size in 10f..64f)
        val data =
            JsonPrimitive(source)
                .toString()
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029")

        fun color(value: Int) = "#%06x".format(value and 0xffffff)
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'nonce-$nonce';
            style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'none'; connect-src 'none'; frame-src 'none'">
            <link rel="stylesheet" href="${BASE}katex.min.css">
            <style>html,body{margin:0;background:${if (inline) "transparent" else color(background)};color:${color(foreground)}}
            body{padding:${if (inline) 0 else 12}px;font-size:${size}px;overflow:auto}#preview{display:inline-block}
            .katex-display{margin:0}</style></head><body data-state="loading"><main id="preview"></main>
            <script src="${BASE}katex.min.js"></script><script nonce="$nonce">
            try {
              katex.render($data, document.getElementById('preview'), {displayMode:${!inline},trust:false,
                strict:'error',throwOnError:true,maxSize:10,maxExpand:256,output:'htmlAndMathml'});
              document.fonts.ready.then(function(){document.body.dataset.state='ready'},
                function(){document.body.dataset.state='error'});
            } catch (_) { document.body.dataset.state='error'; }
            </script></body></html>
            """.trimIndent()
    }
}
