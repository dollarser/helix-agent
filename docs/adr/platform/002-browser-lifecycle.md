# ADR-PLATFORM-002: 浏览器 View 与逻辑标签生命周期

Status: accepted
Date: 2026-09-16; revised 2026-10-17
HXA: HXA-160, HXA-276, HXA-277, HXA-279
Deciders: Project owner（2026-10-11 要求同步 Browser 插件贡献、资源与交互优化 ADR；不兼容旧版）

## Context

WebView 必须由正确的 Activity 生命周期持有，逻辑标签与页面实例不能混为一个跨 Activity 单例。

HXA-276/277/279 将 Browser 接入插件贡献，并增加标签发现、有界条件等待；这些能力必须复用现有 Activity owner、页面身份和观察链。目标设计已接受；HXA-276 单一贡献已交付并完成授权有界验收，277 资源接线已完成有界验收，279 增量已实现，有界验收按对应证据结算。

## Decision

### View 与逻辑标签所有权

Activity 持有 BrowserViewOwner，使用真实 Activity Context 惰性创建宿主；BrowserController 保留逻辑标签和当前 owner 的弱绑定。替换 owner 使旧绑定失效，迟到解绑不能销毁新 owner。所有旧页回调校验 owner/宿主身份。

后台切换不销毁 View；pause/resume 作用于匹配 owner，不把 onPause 当作 JS 全局暂停。仅当前活动标签、可用前台窗口显示 JS 对话框；切后台、导航、关闭、解绑或取消时终结结果一次。保留平台 Autofill 能力并以真实服务 fixture 验证。

无宿主时非空 open 返回明确失败且不新建标签，navigate 返回稳定宿主不可用原因；历史/重载缺少真实页面时不假报执行成功。空标签仍可独立存在。页面销毁清理 loading/history 可用性与 DOM token，保留 URL/标题供显式导航；不自动加载 URL、重放 POST、表单或工具。恢复后由原任务正常继续路径决定下一步，不自动拉起 Activity、不改变 Goal 预算或批准契约。

页面发起的导航和显式导航共用 URL 策略。允许的页面请求由 WebView 在原 frame 中继续，不能通过 `loadUrl` 重放或把 iframe 提升为主页面；被拒绝的应用协议/本地文件请求只取消该次跳转，不把仍可用的来源页面改成错误页，也不启动外部应用。若禁止地址已经进入主文档提交回调，则停止并明确失效。快照区分加载中、页面错误、宿主不可用和观察期间页面变化，不能统一误报无页面。

### 原生插件贡献与手动浏览（HXA-276）

`:extensions:browser` 保存宿主已知的 manifest、工具贡献和网页任务 Skill，App composition root 注入已有 BrowserToolBridge 与视觉服务；执行器复用现有工具实现，不创建独立 browser server。12 个原有工具及279两项新增工具统一为 Browser 的 PluginOrigin，走唯一 ToolRegistry 原子发布与同一 Dispatcher；直接删除旧 built-in 注册及内部来源假设，不保留双注册或旧批准转换。

新会话默认集合与组件就绪规则由 [ADR-CONNECTORS-003](../connectors/003-ownership-and-installation.md)定义。插件贡献不持有 Activity/WebView，也不因选择或状态查看而拉起 UI。手动浏览、书签、历史和用户导航属于浏览器功能；撤销 Agent 来源仅撤下后续准入，不能销毁用户正在浏览的标签。无 host、页面失效或视觉不可用返回真实原因；URL/origin、目标范围、generation/token 与像素披露要求保持有效。

### 资源引用与停止（HXA-277）

通用实例句柄、引用释放及错误规则归 [Plugin 资源契约](../connectors/003-ownership-and-installation.md#11-原生实例共享资源与释放hxa-277)。Browser 的注册监听和调用观察引用只拥有其生命周期内的订阅/工作；Activity 拥有 View，BrowserController 拥有逻辑标签，插件不能越权关闭其他消费者的宿主。

撤销、导航、owner 替换、页面销毁或 Stop 使相关观察/等待绑定失效，结束订阅并按原调用结算；迟到回调校验原 owner 和页面身份，不能唤醒替代实例。发出取消不释放尚未退出 executor 的引用；实际退出后仅释放一次。清理失败如实保留未释放事实，不能通过重放导航/表单恢复。

### 标签发现与有界等待（HXA-279）

首版只增加两项工具契约：

- **标签发现**：在原会话/目标 scope 和输出上限内返回准确 tab ID、允许披露的标题/URL、选中状态与页面/host 状态。只读列表不加载页面、不获取额外权限；别的上下文内容不能因共享 BrowserController 自动披露。无可操作页面与空列表按实际原因区分。
- **条件等待**：在已有只读页面观察上等待首版明确支持的页面/目标条件，绑定准确 tab、owner、页面身份及原调用 deadline；参数校验限定条件集合、等待上限与输出大小，不接受任意页面 JS。达到条件、超时、取消、页面/host 失效分别结算，页面变化不把旧 token 指向新页面；需要继续时重新观察并发起新调用。等待引用使用上节生命周期，不建第二个调度器。

首版`browser.tabs`只披露原会话调用创建的逻辑标签，或原准确BrowserTabScope；未识别scope不回退。来源标记只保存在现有不持久化逻辑标签，不是授权，也不回填手动标签或旧来源。未明确目标scope的手动/其他会话标签不进入列表。`browser.wait`只支持已settled文档的语义子串PRESENT/ABSENT，最多10秒并受原deadline及单调时间约束；加载中NO_PAGE，导航/host变化明确失效。截断树不能证明ABSENT，多匹配不提供唯一token。

标签列表只提供元数据，URL含HTML也不能作为当前页面正文的证据；读取正文使用真实snapshot。列表和快照的URL/标题按声明上限返回完整Unicode前缀并明确各自截断，不扩大schema；完整页面URL、origin、fingerprint和token身份不变，前缀不能当作完整导航目标。

匹配数量/歧义、截断、实际后端、观察过期与像素是否提供仅补执行器能够证明的字段；通用输出语义见 [ADR-TOOLS-001](../tools/001-descriptor-contract.md)。超时或未知效果不自动重放点击、发送、安装等动作，拒绝也不触发后端绕行。首版不扩展 tab close、完整 role/label locator、任意脚本 API 或通用 Observation 平台；Mobile Use 已有 `ui.wait` 继续复用。

## Alternatives considered

- Application Context 继续使用：保留现状但缺少完整窗口功能。
- MutableContextWrapper：不足以保证旧 View 内部服务/窗口全部重新绑定。
- 独立进程/内核：扩大运行时与 IPC 成本，尚无证据可消除系统 Binder 缺陷。
- WebView 池/预热/configChanges 绕过：增加生命周期复杂度，暂不采用。
- 由 Browser 插件接管 WebView 或关闭全部共享标签：混淆 Activity、手动浏览与 Agent 来源归属，采用资源句柄和准确实例引用。
- 一次引入完整 Playwright 式 API：超出当前六类验收旅程，先交付标签发现、有界等待和必要观察事实。

## Consequences

Browser 进入统一插件选择与工具治理，手动浏览继续按 Activity 生命周期运行。标签发现与等待增加真实订阅和页面失效处理，需要限定披露范围、期限与引用；缺少可用 host 时仍会失败，插件化不能替代 Android 宿主条件。

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

HXA-276 已完成单一贡献、选择与手动浏览的主机/API36 有界验收，见[276证据](../../evidence/development/hxa276-browser-contribution-2026-10-12.md)。HXA-277 资源接线与授权 API36 有界验收见[277证据](../../evidence/development/hxa277-native-resource-lifetime-2026-10-12.md)；HXA-279已接入14工具，实际验收见[279证据](../../evidence/development/hxa279-browser-observation-2026-10-12.md)；覆盖工具唯一来源、选择撤销、无 host、空列表与 scope 过滤、等待 deadline/取消、导航失效、owner 替换、迟到回调、共享引用及清理失败。设备/模型对照固定已有标签、延迟条件、旧 token、同名控件歧义、截图未披露、动作结果未知六类旅程，并记录手动浏览及图像回流是否保持；具体授权和入口由对应 HXA 管理。

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [HXA-276：Browser 贡献](../../development/tasks/HXA-276.md)
- [HXA-277：原生资源](../../development/tasks/HXA-277.md)
- [HXA-279：标签发现与有界等待](../../development/tasks/HXA-279.md)
- [插件研究与任务映射](../../research/topics/helix-plugin-architecture-optimization-2026-10-11.md#7-任务承接与验收)
- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history — 2026-10-11：Browser 贡献与交互增量

所有者要求将新增 HXA 同步为现有 ADR。本主题接受 Browser 原生贡献、Activity/View 归属与插件资源引用分离，以及首版标签发现/有界等待；直接删除旧 built-in 发布，不转换旧会话或批准。这样统一能力治理并复用真实浏览器生命周期，保持手动浏览与 Agent 来源独立。设计接受不表示工具或设备旅程已交付。

## Decision history — 2026-10-12：单一 Browser 贡献交付

按所有者授权完成 HXA-276：Browser 贡献与 Activity-owned WebView 分离，选择不创建页面或授予网站权限；API36 两渠道实际工具、手动标签和像素披露回流通过。资源释放及新增标签/等待由 277/279 独立验收，不由本轮结果推导完成。

## Decision history — 2026-10-12：Browser 观察引用与宿主退休

HXA-277 保留 Activity 的 View 所有权和手动浏览；独立观察回调不互相取消，销毁时明确结算原请求，旧 owner/导航结果不更新新页面。观察注册结束不表示渲染器执行退出；插件停用不关闭手动标签。API36 共享引擎与双渠道应用有界验收通过，新增 tabs/wait 仍由 279 承接。

## Decision history — 2026-10-12：有界标签发现与条件等待

Owner要求完成279。采用[Playwright Pages](https://playwright.dev/docs/pages)的上下文隔离和[Actionability](https://playwright.dev/docs/actionability)的有界观察思路，保留Android现有Activity/page身份与权限路径；不复制参考代码或引入完整locator/任意JS。原会话来源仅为逻辑资源事实；原调用的精确scope、owner/generation和取消/deadline限定披露及等待，未知动作不重放。手动标签无原会话来源时需要明确目标scope，这是首版范围限制，不能通过共享Controller自动扩权。固定合成旅程与像素编码、真实SDK失效分别验收。

实测长data URL导致原快照输出schema拒绝，按上限提供显式前缀/截断而保留完整执行身份；实测模型仅列标签结束读取任务，澄清元数据与当前正文证据。测试汇总原始及恢复全部Turn，像素自述使用明确二值答复和实际artifact事实，结果与遗留边界见279证据。

## Decision history — 2026-10-17：页面跳转拒绝不破坏来源文档

Owner 日常模拟器抖音手测暴露：应用协议跳转被正确拒绝，但旧回调把原 HTTPS 页面标成策略错误，后续快照只返回 `no_page`。按 [Android WebViewClient 官方契约](https://developer.android.com/reference/android/webkit/WebViewClient#shouldOverrideUrlLoading(android.webkit.WebView,%20android.webkit.WebResourceRequest)) 修正为同一 URL 策略决定原请求继续或取消；保留显式导航与禁止文档提交的失败边界，不增加外部应用启动或任意脚本权限。合成页面已在旧实现复现，修复和当前验证范围见 [v0.0.6 交付记录](../../evidence/development/v0.0.6-handoff-2026-10-17.md)。
