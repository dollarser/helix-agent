package com.helix.app.ui

import kotlinx.serialization.json.JsonPrimitive

/** Explicit static SVG only. No arbitrary HTML, DTD/entity processing or external resource resolution. */
internal object SvgPreviewPolicy {
    const val MAX_CHARS = 65_536
    const val BASE = "${FormulaPreviewPolicy.ORIGIN}/assets/svg/"
    const val PAGE = "${BASE}preview.html"

    fun valid(source: String): Boolean =
        source.isNotBlank() && source.length <= MAX_CHARS && '\u0000' !in source &&
            !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(source)

    fun asset(url: String): String? =
        when (url) {
            "${BASE}purify.min.js" -> "svg/purify.min.js"
            "${BASE}sanitize.js" -> "svg/sanitize.js"
            else -> null
        }

    fun html(
        source: String,
        background: Int,
        foreground: Int,
        size: Float,
        nonce: String,
    ): String {
        require(valid(source))
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
            style-src 'self' 'unsafe-inline'; font-src 'none'; img-src 'none'; connect-src 'none'; frame-src 'none'">
            <style>html,body{margin:0;background:${color(background)};color:${color(foreground)}}
            body{padding:12px;font-size:${size}px;overflow:auto}#preview{display:inline-block}</style>
            </head><body data-state="loading"><main id="preview"></main>
            <script src="${BASE}purify.min.js"></script><script src="${BASE}sanitize.js"></script>
            <script nonce="$nonce">try {
              document.getElementById('preview').replaceChildren(sanitizeDiagramSvg($data,true));
              document.body.dataset.state='ready';
            }catch(_){document.body.dataset.state='error';}</script></body></html>
            """.trimIndent()
    }
}

internal fun inlineDiagramKind(language: String?): BlockPreviewKind? =
    when (language?.lowercase(java.util.Locale.ROOT)) {
        "mermaid" -> BlockPreviewKind.MERMAID
        "svg", "image/svg+xml" -> BlockPreviewKind.SVG
        else -> null
    }

/** UI presentation eligibility, not a new file authority or model-upload format. */
internal fun svgFilePreview(
    name: String,
    info: com.helix.app.files.FileManagerService.FileMeta,
    text: String,
): Boolean =
    (info.mimeType.equals("image/svg+xml", true) || name.endsWith(".svg", true)) &&
        info.sizeBytes in 1..SvgPreviewPolicy.MAX_CHARS.toLong() &&
        text.toByteArray(Charsets.UTF_8).size.toLong() == info.sizeBytes
