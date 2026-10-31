package com.helix.app.ui

import kotlinx.serialization.json.JsonPrimitive

/** Data-only diagrams; no author configuration, interactive callbacks, external resources or HTML. */
internal object MermaidPreviewPolicy {
    const val BASE = "${FormulaPreviewPolicy.ORIGIN}/assets/mermaid/"
    const val PAGE = "${BASE}preview.html"

    fun valid(source: String): Boolean =
        source.isNotBlank() && source.length <= FormulaPreviewPolicy.MAX_CHARS &&
            '\u0000' !in source && source.count { it == '\n' } < 256 &&
            !source.trimStart().startsWith("---") && !source.contains("%%{")

    fun asset(url: String): String? = if (url == "${BASE}mermaid.tiny.js") "mermaid/mermaid.tiny.js" else null

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
            <script src="${BASE}mermaid.tiny.js"></script><script nonce="$nonce">
            function sanitizeDiagramSvg(markup){
              if(markup.length>1048576) throw Error('size');
              const doc=new DOMParser().parseFromString(markup,'text/html');
              const svg=doc.body.firstElementChild;
              if(!svg||svg.localName!=='svg'||doc.body.children.length!==1||doc.head.children.length) throw Error('svg');
              const nodes=Array.from(svg.querySelectorAll('*'));
              if(nodes.length>2048) throw Error('complexity');
              const tags=new Set(['svg','g','defs','marker','path','rect','circle','ellipse','line','polyline',
                'polygon','text','tspan','textPath','title','desc','style','clipPath','linearGradient',
                'radialGradient','stop','pattern','mask']);
              const properties=new Set(['fill','fill-opacity','fill-rule','stroke','stroke-width','stroke-opacity',
                'stroke-dasharray','stroke-dashoffset','stroke-linecap','stroke-linejoin','stroke-miterlimit',
                'opacity','color','font-family','font-size','font-weight','font-style','text-anchor',
                'text-decoration','dominant-baseline','alignment-baseline','white-space','display','visibility',
                'paint-order','transform','transform-origin','marker-start','marker-mid','marker-end',
                'stop-color','stop-opacity','clip-path','mask','rx','ry']);
              function safeValue(value){return !/@|[<>]|expression\s*\(/i.test(value)&&
                !/url\s*\(/i.test(value.replace(/url\(\s*['"]?#[\w-]+['"]?\s*\)/gi,''));}
              function cleanDeclarations(declaration){
                const keep=[];for(const name of Array.from(declaration)){
                  const value=declaration.getPropertyValue(name);
                  if(properties.has(name)&&safeValue(value)) keep.push(name+':'+value);
                }return keep.join(';');
              }
              for(const node of [svg,...nodes]){
                if(node.localName==='a'){node.replaceWith(...Array.from(node.childNodes));continue;}
                if(node.namespaceURI!=='http://www.w3.org/2000/svg'||!tags.has(node.localName)){node.remove();continue;}
                if(node.localName==='style'){
                  const sheet=new CSSStyleSheet();sheet.replaceSync(node.textContent);
                  node.textContent=Array.from(sheet.cssRules).filter(rule=>rule.type===1&&
                    !/[<>@]/.test(rule.selectorText)).map(rule=>rule.selectorText+'{'+cleanDeclarations(rule.style)+'}').join('');
                }
                for(const attr of Array.from(node.attributes)){
                  const name=attr.localName.toLowerCase(), value=attr.value;
                  if(name.startsWith('on')||name==='src'||name==='srcset'||name==='base'||name==='target'||
                    (name==='href'&&!/^#[\w-]+$/.test(value))||!safeValue(value)) node.removeAttributeNode(attr);
                  else if(name==='style'){
                    const tmp=document.createElement('span');tmp.style.cssText=value;
                    const cleaned=cleanDeclarations(tmp.style);
                    if(cleaned) node.setAttribute('style',cleaned);else node.removeAttributeNode(attr);
                  }
                }
              }
              const box=svg.viewBox.baseVal;
              if(!box||!Number.isFinite(box.width)||!Number.isFinite(box.height)||box.width<=0||box.height<=0||
                box.width>20000||box.height>20000) throw Error('bounds');
              svg.setAttribute('width',String(box.width));svg.setAttribute('height',String(box.height));
              svg.style.maxWidth='none';
              return svg;
            }
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
