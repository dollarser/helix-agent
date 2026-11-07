package com.helix.ui.shared

import kotlinx.serialization.json.JsonPrimitive

/** Data-only diagrams; no author configuration, interactive callbacks, external resources or HTML. */
internal object MermaidPreviewPolicy {
    const val BASE = "${FormulaPreviewPolicy.ORIGIN}/assets/mermaid/"
    const val PAGE = "${BASE}preview.html"

    fun valid(source: String): Boolean =
        source.isNotBlank() && source.length <= FormulaPreviewPolicy.MAX_CHARS &&
            '\u0000' !in source && source.count { it == '\n' } < 256 &&
            !source.trimStart().startsWith("---") && !source.contains("%%{")

    fun asset(url: String): String? =
        if (url ==
            "${BASE}mermaid.tiny.js"
        ) {
            "mermaid/mermaid.tiny.js"
        } else {
            SvgPreviewPolicy.asset(url)
        }

    @Suppress("LongMethod")
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
            <script src="${SvgPreviewPolicy.BASE}purify.min.js"></script>
            <script src="${SvgPreviewPolicy.BASE}sanitize.js"></script>
            <script src="${BASE}mermaid.tiny.js"></script><script nonce="$nonce">
            (async function(){try {
              const config={startOnLoad:false,securityLevel:'strict',htmlLabels:false,suppressErrorRendering:true,
                maxTextSize:8192,maxEdges:128,look:'classic',layout:'dagre',theme:'base',fontFamily:'sans-serif',
                deterministicIds:true,deterministicIDSeed:'helix',
                themeVariables:{fontSize:'${size}px',fontFamily:'sans-serif',background:'${color(background)}',
                  primaryColor:'${color(background)}',primaryTextColor:'${color(foreground)}',
                  primaryBorderColor:'${color(foreground)}',lineColor:'${color(foreground)}',
                  secondaryColor:'${color(background)}',tertiaryColor:'${color(background)}',
                  textColor:'${color(foreground)}',nodeTextColor:'${color(foreground)}'},
                flowchart:{htmlLabels:false,useMaxWidth:false},sequence:{useMaxWidth:false}};
              config.secure=Object.keys(config).concat(['secure','themeCSS','dompurifyConfig']);
              mermaid.initialize(config);
              const result=await mermaid.render('helix-diagram',$data);
              const svg=sanitizeDiagramSvg(result.svg);
              document.getElementById('preview').replaceChildren(document.importNode(svg,true));
              document.body.dataset.state='ready';
            } catch (_) { document.body.dataset.state='error'; }})();
            </script></body></html>
            """.trimIndent()
    }
}
