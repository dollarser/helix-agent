# ADR-PLATFORM-006: 离线块级公式与图表预览

Status: accepted
Date: 2026-10-31
HXA: Owner-authorized useful incremental capabilities (2026-10-30/31)
Deciders: Project owner（允许按实际收益接入成熟依赖）

## Context

当前 Markdown 使用 Compose 展示文本、代码、链接和出处，不是 HTML 页面。公式排版采用成熟库，避免自制 TeX 解释器或为了排版迁移整套聊天渲染框架。

## Decision

完整 `math` 围栏、独占行 `$$` 和 `\[` 公式块保留源码、折叠与复制，提供用户按需打开的公式预览。行内美元符号、未结束的流式公式、普通 `latex` 代码围栏保持原文，不将货币或代码示例猜成公式。此能力是块级预览，不宣称完整 LaTeX 文档排版。

采用固定 KaTeX 官方 npm 分发、MIT 许可和离线字体；版本、完整性与原始许可进入锁文件和 APK。无需新增 Maven/JitPack 仓库、JNI 或服务端请求。原型示意不替代 Android 排版验收。

预览对话框独占一个生命周期绑定的 WebView，关闭或来源变更销毁，不在每条历史消息中常驻 WebView。只执行固定打包脚本；LaTeX 以转义 JSON 数据进入库。关闭 trust，限制字符、宏展开和尺寸；不支持或超限时明确显示源码，完整源码仍可复制。无 JavaScript 特权桥、文件/内容访问、DOM 存储、远端资源、外部导航或下载。CSP 和请求白名单均限制到已打包公式资源，不复用用户浏览器标签/授权或更改全局 Cookie/定时器设置。

## Alternatives considered

JLaTeXMath Android 的官方仓库已归档且标注 GPL-2.0；不在本任务采用其许可。AndroidMath 为 MIT，使用 JNI/FreeType 和 JitPack，需另验两 ABI、原生兼容与字体许可；此次选择无 JNI 的 KaTeX。Mermaid 采用官方 `@mermaid-js/tiny` 固定分发，避免引入当前不需要的 ELK 引擎、懒加载和第二份 KaTeX。保守运行依赖许可清单进入 APK，DOMPurify 选择 Apache-2.0 许可。Tiny 不支持的图种保留源码并明确失败，不伪造图形。

`mermaid` 围栏提供相同的按需离线图表预览，共享宿主而保持各自资源白名单。图定义仅为数据；固定 strict 模式、无 HTML labels、不调用绑定函数，拒绝 frontmatter 和 init 指令。库净化之后再以惰性 HTML 文档解析 SVG 并校验单根及 SVG 命名空间（与库的 HTML 序列化语义一致），限制标签和属性、去除链接/事件/图片/foreignObject，CSS 只保留受限声明和本地 fragment 引用。禁止网络、frame、外部字体及桥接，资源请求只接受包内固定脚本。安全策略不依赖图内配置或模型承诺。

每块最多 8192 字符、255 个换行，库限制 128 条边；输出 SVG 最多 1 MiB、2048 个元素和 20000 的宽高。原生等待最多 8 秒（就绪失败可提前）后显示源码并释放该 View；这不是可硬中止所有 native/renderer 工作的实时隔离保证。失败、旧 WebView 不支持脚本、超限或关闭均不丢源码；大图在有界窗口内滚动/缩放，不创建消息列表常驻 WebView。

## Consequences

离线资源增加有限包体；预览按需创建，关闭后释放。KaTeX 不支持的命令保留源码，不执行外部宏或远端内容。依赖升级须重新核对官方分发、许可、资源锁和实际设备排版。

## Reconsider when

需要行内混排、完整 LaTeX 文档或不同渲染引擎时重新评估；新图种、外部图标/字体、图内交互或配置支持须重新评估 SVG/配置、许可、资源与兼容边界，不从既有预览默认继承结论。

## Verification

验证分隔符与流式未闭合输入不吞正文；数据转义、资源 origin/path、错误和超限；实际离线分数/根式排版、失败源码、关闭/重开与 View 释放。不能把 DOM 或测试包编译单独当作视觉通过。当前设备证据不覆盖全部系统 WebView、OEM 或无障碍服务。

验证 Mermaid 流程图与时序图实际像素、深色/大字、HTML/链接/CSS 输入、配置和规模拒绝、错误与释放。仅当前指定模拟器 WebView 有设备证据，不代表所有 API/OEM/WebView 版本通过。

## References

- [KaTeX 浏览器接入](https://katex.org/docs/browser)
- [KaTeX 安全选项](https://katex.org/docs/security)
- [Android WebView 本地内容安全](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion)
- [JLaTeXMath Android 许可与归档](https://github.com/noties/jlatexmath-android)
- [AndroidMath 接入与许可](https://github.com/gregcockroft/AndroidMath)
- [第三方许可](../../../THIRD_PARTY_NOTICES.md)

- [Mermaid 官方 Tiny 分发](https://github.com/mermaid-js/mermaid/tree/mermaid%4012.2.0/packages/tiny)
- [Mermaid 配置与安全](https://mermaid.js.org/config/schema-docs/config.html)

## Decision history

- 2026-10-31：所有者允许合理成熟依赖降低工作量；采用按需块级离线预览，保留当前 Markdown 与来源交互，排版算法由 KaTeX 提供。

- 2026-10-31：所有者明确要求实现 Mermaid；复用生命周期宿主，加入独立 SVG/CSS/链接策略与预算，采用官方 Tiny 离线资源和完整许可清单。
