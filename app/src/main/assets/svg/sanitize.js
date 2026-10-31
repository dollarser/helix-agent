function sanitizeDiagramSvg(markup, requireComplete=false){
  if(markup.length>1048576) throw Error('size');
  if(requireComplete){
    const original=new DOMParser().parseFromString(markup,'image/svg+xml');
    if(original.querySelector('parsererror')||original.documentElement.localName!=='svg') throw Error('syntax');
    const elements=Array.from(original.getElementsByTagName('*'));
    if(elements.length>2048) throw Error('complexity');
    for(const node of elements){
      if(node.localName==='a') continue;
      for(const attr of Array.from(node.attributes)){
        if(attr.localName==='href'&&!/^#[\w-]+$/.test(attr.value)) throw Error('resource');
      }
    }
  }
  const cleaned=DOMPurify.sanitize(markup,{USE_PROFILES:{svg:true,svgFilters:false},ADD_TAGS:['style','use','symbol'],
    ALLOW_DATA_ATTR:false,FORBID_TAGS:['script','foreignObject','image','iframe','object','embed']});
  if(requireComplete&&DOMPurify.removed.some(r=>{
    const name=r.element&&r.element.localName&&r.element.localName.toLowerCase();
    return name&&!['html','head','body'].includes(name);
  })) throw Error('unsupported');
  const doc=new DOMParser().parseFromString(cleaned,'text/html');
  const svg=doc.body.firstElementChild;
  if(!svg||svg.localName!=='svg'||doc.body.children.length!==1||doc.head.children.length) throw Error('svg');
  const nodes=Array.from(svg.querySelectorAll('*'));
  if(nodes.length>2048) throw Error('complexity');
  const tags=new Set(['svg','g','defs','marker','path','rect','circle','ellipse','line','polyline',
    'polygon','text','tspan','textPath','title','desc','style','clipPath','linearGradient',
    'radialGradient','stop','pattern','mask','symbol','use','view']);
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
      if(requireComplete&&!safeValue(value)) throw Error('resource');
      if(properties.has(name)&&safeValue(value)) keep.push(name+':'+value);
    }return keep.join(';');
  }
  for(const node of [svg,...nodes]){
    if(node.localName==='a'){node.replaceWith(...Array.from(node.childNodes));continue;}
    if(node.namespaceURI!=='http://www.w3.org/2000/svg'||!tags.has(node.localName)){
      if(requireComplete) throw Error('unsupported');node.remove();continue;}
    if(node.localName==='style'){
      if(requireComplete&&/@/.test(node.textContent)) throw Error('unsupported');
      const sheet=new CSSStyleSheet();sheet.replaceSync(node.textContent);
      node.textContent=Array.from(sheet.cssRules).filter(rule=>rule.type===1&&
        !/[<>@]/.test(rule.selectorText)).map(rule=>rule.selectorText+'{'+cleanDeclarations(rule.style)+'}').join('');
    }
    for(const attr of Array.from(node.attributes)){
      const name=attr.localName.toLowerCase(), value=attr.value;
      if(requireComplete&&((name==='href'&&!/^#[\w-]+$/.test(value))||!safeValue(value))) throw Error('resource');
      if(name.startsWith('on')||name==='src'||name==='srcset'||name==='base'||name==='target'||
        (name==='href'&&!/^#[\w-]+$/.test(value))||!safeValue(value)) node.removeAttributeNode(attr);
      else if(name==='style'){
        const tmp=document.createElement('span');tmp.style.cssText=value;
        const cleaned=cleanDeclarations(tmp.style);
        if(cleaned) node.setAttribute('style',cleaned);else node.removeAttributeNode(attr);
      }
    }
  }
  let box=svg.viewBox.baseVal;
  if(!box||box.width<=0||box.height<=0){
    const width=svg.width.baseVal.value,height=svg.height.baseVal.value;
    if(width<=0||height<=0) throw Error('bounds');
    svg.setAttribute('viewBox','0 0 '+width+' '+height);box=svg.viewBox.baseVal;
  }
  const uses=Array.from(svg.querySelectorAll('use'));
  if(uses.length>128) throw Error('complexity');
  function checkUse(node, ancestors, depth){
    if(depth>16) throw Error('complexity');
    const href=node.getAttribute('href')||node.getAttributeNS('http://www.w3.org/1999/xlink','href');
    if(!href||!/^#[\w-]+$/.test(href)) return;
    const target=doc.getElementById(href.slice(1));
    if(!target||ancestors.has(target)) throw Error('reference');
    const next=new Set(ancestors);next.add(target);
    const nested=target.localName==='use'?[target]:Array.from(target.querySelectorAll('use'));
    nested.forEach(child=>checkUse(child,next,depth+1));
  }
  uses.forEach(node=>checkUse(node,new Set([node]),0));
  if(!box||!Number.isFinite(box.width)||!Number.isFinite(box.height)||box.width<=0||box.height<=0||
    box.width>20000||box.height>20000) throw Error('bounds');
  svg.setAttribute('width',String(box.width));svg.setAttribute('height',String(box.height));
  svg.style.maxWidth='none';
  return svg;
}
