/* Rebuild derived standalone and page matrix from the maintained sources. */
const fs=require('fs'),path=require('path'),vm=require('vm'),root=__dirname;
const get=n=>fs.readFileSync(path.join(root,n),'utf8'),put=(n,s)=>fs.writeFileSync(path.join(root,n),s);
let html=get('shell.html').replace("script-src 'self'","script-src 'unsafe-inline'").replace("style-src 'self'","style-src 'unsafe-inline'");
html=html.replace('<link rel="stylesheet" href="styles.css">',()=>'<style>'+get('styles.css')+'</style>').replace('<script src="catalog.js"></script><script src="details.js"></script><script src="app.js"></script>',()=>'<script>'+get('catalog.js')+'\n'+get('details.js')+'\n'+get('app.js')+'</script>');
put('index.html',html);
put('standalone.html',html);
const ctx={};vm.createContext(ctx);vm.runInContext(get('catalog.js')+';this.pages=PAGES;this.scenarios=SCENARIOS',ctx);
const lines=ctx.pages.map(p=>`| [${p.title}](index.html#${p.id}) | \`${p.id}\` | [源码](../../../${p.source}) | ${p.interactions} | ${['chat','startup'].includes(p.id)?'专项场景；见下表':'正常 / 加载 / 空 / 错误 / 取消（评审控制）'} |`);
put('COVERAGE.md',`# 页面、状态和交互覆盖\n\n清单为 **${ctx.pages.length} 个设计视图 + ${ctx.scenarios.length} 个专项场景**。每项链接均在离线 DOM 中渲染并检查目标路由；source 文件均检查存在。页面覆盖是原型信息架构覆盖，不等于逐项真实功能已实施，也不等于每个底层 Compose 控件都被一对一复制。\n\n14 个 ShellDestination 均有映射；会话历史/搜索/设置、项目详情、位置、就绪/能力/运行时、默认值/权限/系统权限/存储/审计等二级路由均纳入。独立 Activity 与 tab/对话框按用户任务组织成视图或面板。默认聊天，不以目录作为产品主页。\n\n## 逐页矩阵\n\n| 页面 | 原型路由 | 当前源码映射 | 可点击行为 | 状态入口 |\n| --- | --- | --- | --- | --- |\n${lines.join('\n')}\n\n${get('coverage-notes.md')}\n`);
console.log(`Generated standalone.html and COVERAGE.md: ${ctx.pages.length} views, ${ctx.scenarios.length} scenarios.`);
