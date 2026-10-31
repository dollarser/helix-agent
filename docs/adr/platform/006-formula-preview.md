# ADR-PLATFORM-006: 离线块级公式预览

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

JLaTeXMath Android 的官方仓库已归档且标注 GPL-2.0；不在本任务采用其许可。AndroidMath 为 MIT，使用 JNI/FreeType 和 JitPack，需另验两 ABI、原生兼容与字体许可；此次选择无 JNI 的 KaTeX。Mermaid 独立评估，不能因同为 JS 便按同一个库、许可或资源预算交付。

## Consequences

离线资源增加有限包体；预览按需创建，关闭后释放。KaTeX 不支持的命令保留源码，不执行外部宏或远端内容。依赖升级须重新核对官方分发、许可、资源锁和实际设备排版。

## Reconsider when

需要行内混排、完整 LaTeX 文档或不同渲染引擎时重新评估；Mermaid 的 SVG/配置和依赖边界另行验证，不从公式预览默认继承安全结论。

## Verification

验证分隔符与流式未闭合输入不吞正文；数据转义、资源 origin/path、错误和超限；实际离线分数/根式排版、失败源码、关闭/重开与 View 释放。不能把 DOM 或测试包编译单独当作视觉通过。当前设备证据不覆盖全部系统 WebView、OEM 或无障碍服务。

## References

- [KaTeX 浏览器接入](https://katex.org/docs/browser)
- [KaTeX 安全选项](https://katex.org/docs/security)
- [Android WebView 本地内容安全](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion)
- [JLaTeXMath Android 许可与归档](https://github.com/noties/jlatexmath-android)
- [AndroidMath 接入与许可](https://github.com/gregcockroft/AndroidMath)
- [第三方许可](../../../THIRD_PARTY_NOTICES.md)

## Decision history

- 2026-10-31：所有者允许合理成熟依赖降低工作量；采用按需块级离线预览，保留当前 Markdown 与来源交互，排版算法由 KaTeX 提供。
