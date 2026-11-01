# ADR-PLATFORM-006: 离线公式与图表预览

Status: accepted
Date: 2026-10-31
HXA: Owner-authorized useful incremental capabilities (2026-10-30/31)
Deciders: Project owner（允许按实际收益接入成熟依赖）

## Context

当前 Markdown 使用 Compose 展示文本、代码、链接和出处，不是 HTML 页面。公式排版采用成熟库，避免自制 TeX 解释器或为了排版迁移整套聊天渲染框架。

## Decision

完整 `math` 围栏、独占行 `$$` 和 `\[` 公式块保留源码、折叠与复制，提供用户按需打开的公式预览。完整 `\\(...\\)` 与保守识别的 `$...$` 行内公式使用原生 Text 位图占位混排；货币、代码、链接内公式、未结束输入和不支持语法保留原文，不宣称完整 LaTeX 文档排版。

采用固定 KaTeX 官方 npm 分发、MIT 许可和离线字体；版本、完整性与原始许可进入锁文件和 APK。无需新增 Maven/JitPack 仓库、JNI 或服务端请求。原型示意不替代 Android 排版验收。

公式与显式放大对话框使用生命周期绑定的 WebView，关闭、暂停或来源变更即释放。内嵌图使用一个短暂渲染器排队生成原生位图，完成即销毁，不在每条历史消息中常驻 WebView。只执行固定打包脚本；LaTeX 以转义 JSON 数据进入库。关闭 trust，限制字符、宏展开和尺寸；不支持或超限时明确显示源码，完整源码仍可复制。无 JavaScript 特权桥、文件/内容访问、DOM 存储、远端资源、外部导航或下载。CSP 和请求白名单均限制到已打包公式资源，不复用用户浏览器标签/授权或更改全局 Cookie/定时器设置。

## Alternatives considered

JLaTeXMath Android 的官方仓库已归档且标注 GPL-2.0；不在本任务采用其许可。AndroidMath 为 MIT，使用 JNI/FreeType 和 JitPack，需另验两 ABI、原生兼容与字体许可；此次选择无 JNI 的 KaTeX。Mermaid 采用官方 `@mermaid-js/tiny` 固定分发，避免引入当前不需要的 ELK 引擎、懒加载和第二份 KaTeX。保守运行依赖许可清单进入 APK，DOMPurify 选择 Apache-2.0 许可。Tiny 不支持的图种保留源码并明确失败，不伪造图形。

完整闭合的 `mermaid` 与 `svg`/`image/svg+xml` 围栏在消息中默认显示图，提供源码切换、复制和按需放大；块级公式保持原有按需交互，行内公式默认混排并可点击放大。未闭合的流式围栏只显示源码，不启动渲染。共享宿主而保持各自资源白名单。图定义仅为数据；固定 strict 模式、无 HTML labels、不调用绑定函数，拒绝 frontmatter 和 init 指令。显式固定 DOMPurify 再净化后，以惰性 HTML 文档解析 SVG 并校验单根及 SVG 命名空间（与库的 HTML 序列化语义一致），限制标签和属性、去除链接/事件/图片/foreignObject，CSS 只保留受限声明和本地 fragment 引用。禁止网络、frame、外部字体及桥接，资源请求只接受包内固定脚本。安全策略不依赖图内配置或模型承诺。

每块最多 8192 字符、255 个换行，库限制 128 条边；输出 SVG 最多 1 MiB、2048 个元素和 20000 的宽高。原生等待最多 8 秒（就绪失败可提前）后显示源码并释放该 View；这不是可硬中止所有 native/renderer 工作的实时隔离保证。失败、旧 WebView 不支持脚本、超限或关闭均不丢源码；大图在有界窗口内滚动/缩放，不创建消息列表常驻 WebView。

静态 SVG 使用系统 WebView/Chromium 的成熟 SVG 渲染，不新增自制 SVG 引擎。显式 SVG 先做完整 XML 语法检查并拒绝 DTD/entity，再经 DOMPurify（Apache-2.0 选项）及共用 SVG/CSS 白名单；脚本/事件、外部资源、动画、filter、foreignObject 等不执行，不支持的内容明确退回源码。支持有界静态形状、文字、样式及本地片段引用；片段引用限制数量/深度，拒绝循环。SVG 源码最多 65536 字符，输出沿用元素/尺寸预算。不把任意 HTML 或模型附件上传能力扩为 HTML/SVG 执行面。

现有文件管理预览只在 SVG MIME/扩展名明确、大小有界且读取文本字节数与文件元信息一致时接入同一渲染器；截断文件仍按原有文本预览，不把前缀当完整图。保留原读权限与路径边界，不新增文件读取旁路、远端下载或 SVG 视觉模型输入。

内嵌只在可见且 RESUMED 时渲染，单个临时引擎占用物理容量直到 View 真正销毁；显式放大对话框独立且至多一个。位图最多 200 万像素/2048 宽，进程内 LRU 最多 16 MiB，键包括完整源码 hash、种类、主题、字号、显示密度和宽度。离屏释放局部位图引用与渲染任务，但保留测得占位高度以避免列表抖动；重新进入可复用有界缓存。取消、重组、换来源和旧回调不更新另一代状态。缓存不是正文存储，不写磁盘；任何失败都不覆盖原始源码。

Mermaid 官方 Tiny 分发先经固定 esbuild 转换到 Chrome 91，显式降级 class static blocks；离线 core-js 标准运行库补齐 Object.hasOwn、Array.at 等运行时 API。转换不改变图表权限策略，不回退 Mermaid 版本，也不升级设备 WebView。来源归档、构建工具、参数、运行库和生成资产均固定完整性；维护脚本只接收显式离线归档，校验后构建，不运行 npm 安装脚本。仅对实际测试的图种和引擎给出验收，不承诺全部历史 WebView 或全部标准 API 可模拟。

## Consequences

离线资源增加有限包体；预览按需创建，关闭后释放。KaTeX 不支持的命令保留源码，不执行外部宏或远端内容。依赖升级须重新核对官方分发、许可、资源锁和实际设备排版。

## Reconsider when

需要完整 LaTeX 文档或不同渲染引擎时重新评估；新图种、外部图标/字体、图内交互或配置支持须重新评估 SVG/配置、许可、资源与兼容边界，不从既有预览默认继承结论。

## Verification

验证分隔符与流式未闭合输入不吞正文；数据转义、资源 origin/path、错误和超限；实际离线分数/根式排版、失败源码、关闭/重开与 View 释放。不能把 DOM 或测试包编译单独当作视觉通过。当前设备证据不覆盖全部系统 WebView、OEM 或无障碍服务。

验证 Mermaid 流程图与时序图实际像素、深色/大字、HTML/链接/CSS 输入、配置和规模拒绝、错误与释放。本轮代表场景在 Android 10/API29 的 WebView 91.0.4472.114 和 API36 的 WebView 133.0.6943.137 通过；91 是本轮实际验证的最老引擎，不是所有图种/API/OEM 或更老引擎的兼容承诺。

RikkaHub 的官方实现展示内嵌图与放大入口；Open WebUI 的 CodeBlock 直接集成 Mermaid 渲染与代码控制，作为交互证据而非代码来源。未复制其代码。AndroidSVG/Coil 是成熟原生候选，但 AndroidSVG 的静态 SVG/CSS 能力有限，与 Mermaid 浏览器 CSS 不完全一致；本次继续复用系统 WebView，额外净化采用官方 DOMPurify，避免引入第二套渲染差异和 JNI。

## References

- [esbuild target 与运行时 API 边界](https://esbuild.github.io/api/#target)
- [core-js 官方标准运行库](https://github.com/zloirock/core-js)
- [V8 静态初始化块支持](https://v8.dev/blog/v8-release-94)

- [KaTeX 浏览器接入](https://katex.org/docs/browser)
- [KaTeX 安全选项](https://katex.org/docs/security)
- [Android WebView 本地内容安全](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion)
- [JLaTeXMath Android 许可与归档](https://github.com/noties/jlatexmath-android)
- [AndroidMath 接入与许可](https://github.com/gregcockroft/AndroidMath)
- [第三方许可](../../../THIRD_PARTY_NOTICES.md)

- [Mermaid 官方 Tiny 分发](https://github.com/mermaid-js/mermaid/tree/mermaid%4012.2.0/packages/tiny)
- [Mermaid 配置与安全](https://mermaid.js.org/config/schema-docs/config.html)

- [RikkaHub Mermaid 交互源码（仅参考）](https://github.com/rikkahub/rikkahub/blob/master/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Mermaid.kt)
- [Open WebUI 消息代码块（仅参考）](https://github.com/open-webui/open-webui/blob/main/src/lib/components/chat/Messages/CodeBlock.svelte)
- [DOMPurify 官方安全用法](https://github.com/cure53/DOMPurify)
- [AndroidSVG 支持范围与许可](https://bigbadaboom.github.io/androidsvg/)
- [Coil SVG 支持](https://coil-kt.github.io/coil/svgs/)
- [WebView 绘制就绪回调](https://developer.android.com/reference/android/webkit/WebView#postVisualStateCallback(long,android.webkit.WebView.VisualStateCallback))

## Decision history

- 2026-10-31：所有者允许合理成熟依赖降低工作量；采用按需块级离线预览，保留当前 Markdown 与来源交互，排版算法由 KaTeX 提供。

- 2026-10-31：所有者明确要求实现 Mermaid；复用生命周期宿主，加入独立 SVG/CSS/链接策略与预算，采用官方 Tiny 离线资源和完整许可清单。

- 2026-10-31：所有者要求 Mermaid 默认内嵌与静态 SVG；采用源码/预览切换与复制，单临时引擎、有界原生位图缓存、离屏/暂停释放，增加独立 DOMPurify 与显式 SVG 完整性及资源边界。

- 2026-10-31：Android 10 默认 WebView 91 实测揭示官方分发的静态块语法与标准 API 缺失；采用固定 esbuild 加 core-js 离线构建，保留库版本与隔离边界，分别复验 API29 与 API36。

- 2026-10-31：Owner 授权行内公式；复用 KaTeX 和同一有界临时引擎，关闭 trust、宏共享和网络，以透明位图嵌入原生文本，保留链接、原始替代文字与放大。每段最多 8 项、每式 1024 字符、段落 64 KiB，超限原文保留；离屏释放像素但保留占位尺寸，不增加常驻 WebView。
