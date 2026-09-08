# 浏览器 Context 与生命周期优化分析

> HXA-160 已按所有者授权实施下述 Activity owner 方案（ADR-0033）；当前实现与验收以文末落地记录及 HXA-160 完成记录为准。HXA-159 与竞品研究部分保留当时的分析边界。

日期：2026-09-08。前文保留 HXA-159 当时的分析交付，后续实现见文末 HXA-160。

## HXA-159 时的事实

- `HelixApplication` 持有进程级 `AppContainer`，后者持有 `BrowserController`；控制器将传入 Context 转成 `applicationContext`，`WebViewTabHost` 用它构建 WebView。
- `MainActivity.onDestroy()` 调用全局控制器的 `destroy()`。当前逻辑标签与宿主生命周期通过这个入口关联，不能只把构造参数换成 Activity 就宣称完成改造。
- 宿主没有自定义 `onJsAlert/onJsConfirm/onJsPrompt`。Android 官方明确说明，WebView 应使用 Activity Context，Application Context 会限制 JavaScript 对话框和 Autofill。[WebView 构造器文档](https://developer.android.com/reference/android/webkit/WebView#WebView(android.content.Context))
- HXA-158 的延迟分配已经消除了“未使用宿主先创建 WebView”的额外分配；它没有恢复上述功能，也没有修复系统 JNI/Binder 根因。历史 JNI 与 Binder 对照继续以 [应用规避报告](native-reference-mitigation.md) 为准。

## 建议方向：Activity 持有 View，应用持有逻辑状态

建议继续使用系统 WebView，给可见页面提供真实 Activity Context，并明确分离两个生命周期：

1. 应用级控制器保留标签元数据、工具入口和逻辑状态，不长期保存已销毁 Activity 或其 View。
2. Activity 级宿主拥有 WebView；attach/detach 携带宿主身份或代次。旧 Activity 的迟到销毁只能释放旧宿主，不能销毁新 Activity 已接管的宿主。
3. 旋转、重建和关闭时取消旧宿主回调，释放 View；使旧 DOM token 失效。页面重建须沿用导航策略，不能自动重放 POST、表单提交、click 等副作用。
4. 没有可用 Activity 时，对依赖窗口的操作给出明确可恢复状态。不能静默改用 Application Context 并声称功能相同。是否允许无前台窗口的浏览器操作，需要先明确现有后台任务契约与恢复语义。
5. 保留 HXA-158 的延迟分配。仅有逻辑标签、空宿主或被取消的未开始操作，不创建 WebView。

这是依据当前所有权关系提出的工程方案，尚未获得此生命周期拆分的设备证据。迁移前需按 ADR 约定评估其对后台执行、任务恢复和用户可见行为的影响。HXA-159 只进行职责重构与方案分析，未修改浏览器 Context 或后台契约。

## 可选折中与局限

| 方案 | 能解决的部分 | 取舍 |
| --- | --- | --- |
| Activity 级 WebView owner | 提供真实窗口/Activity 上下文，符合官方构造要求 | 推荐方向；需要同步处理重建、迟到回调、恢复和后台行为，不能只换一个参数 |
| `MutableContextWrapper` | 可在后续调用时委派到新的 base Context | 官方只承诺切换委派对象，没有承诺已创建 WebView 的窗口、缓存服务与 Autofill 状态全部重新绑定；不能据此认定无泄漏或功能完整 |
| `ContextThemeWrapper` 包装 Application | 提供主题资源 | 主题包装不能替代 Activity 生命周期/窗口身份，不作为完整功能修复 |
| 自定义 `WebChromeClient` 对话框 | 可由当前 Activity 显示 JS alert/confirm/prompt | 局部补救，仍需处理导航/关闭时 `JsResult` 取消与只回调一次；不等于恢复原生 Autofill |
| Application Context 继续使用 | 维持当前已验证路径 | 需要继续明确 JS 对话框/Autofill 限制，不能作为完整浏览器功能的最终结论 |

`MutableContextWrapper.setBaseContext` 的委派语义见 [官方 API](https://developer.android.com/reference/android/content/MutableContextWrapper#setBaseContext(android.content.Context))。表中“已创建对象不保证重新绑定”是对该 API 保证范围的工程判断，并非已复现所有 WebView 版本都失败。

独立进程、WebView 池、替换内核和强制 GC 不应混入本次重构。它们不能仅凭设计名称证明 JNI/Binder 根因已解决；现有对照、失败记录与适用边界见规避报告。历史 Activity Context 压测失败，也不能单独证明上述正确限定所有权的方案不可行。

## 未来迁移的必要验收

- API29/36：真实 Activity 页面中的 JS alert/confirm/prompt，确认、取消、关闭标签、导航和旋转期间的结果交付。
- 使用真实 AutofillService 验证填写、保存、授权撤销与 Activity 重建；普通输入框输入成功不是 Autofill 验收。
- 后台/前台切换、连续重建、迟到回调、取消中的工具调用；确保旧 Activity 无强引用残留、新宿主不被旧 owner 销毁、不重复提交副作用。
- 对同一生产路径重新记录 native 引用配对与 Binder 代理采样。短时功能通过、长稳与真机分别报告。

本轮设备回归使用当前 Application Context 实现，仅证明本次职责拆分没有在已测用例中引入回归；不作为上述未来迁移的验收证据。

## 竞品源码复核与收敛方案（2026-09-08 补充）

本节是所有者后续要求的源码研究，补充前述分析，不重开 HXA-159，也不宣称迁移已实施。通过官方 GitHub API 固定以下提交并阅读相关文件；不是竞品设备实测。EinkBro/Fulguris 只参考设计，不复制源码。桌面 Agent 的浏览器自动化不能直接证明 Android Activity/Autofill 路径，因此本轮选取同平台浏览器。

### 可核实的实现

| 项目与固定提交 | 源码事实 | Helix 可采纳的部分 |
| --- | --- | --- |
| EinkBro `1ab77ff50e1ad7e699c0cec436326b868dab84c0` | `BrowserActivity.createebWebView()` 传入 Activity；`TabManager.addRestoredTab()` 仅恢复标题/URL，激活时才将惰性标签实体化；Activity 销毁调用容器清理，容器先移除父 View 再 destroy | Activity 所有权与惰性标签结合，保留 HXA-158 不提前分配的收益 |
| Fulguris `fb8208ffda50b6864f56f8d31c6414aa4a0a9104` | `WebPageTab` 通过 Activity inflater 创建 WebView，显式启用 Autofill 参与；销毁时注销配置监听和下载监听，再释放 View。`autoDestruction()` 因动画可能延迟到脱离父 View 后销毁 | Context 与所有者匹配，清理生命周期外部订阅；不能只调用 destroy 而保留监听 |
| DuckDuckGo Android `80557a80cf1aadbbbc67d628a3347570003cf68d` | `BrowserTabFragment` 用自身 inflater 创建 WebView；`onDestroyView()` 停止加载并解绑 View 监听，`onDestroy()` 再注销 Autofill callback、destroy 并置空。`BrowserChromeClient` 对非活动标签的 JS 对话框调用 cancel；另有按 tabId 保存 Bundle/滚动位置的独立 session storage | 区分 View/标签销毁，结束 pending UI 结果，逻辑恢复信息独立于原 WebView |

固定源码入口：

- EinkBro：[Activity 创建](https://github.com/plateaukao/einkbro/blob/1ab77ff50e1ad7e699c0cec436326b868dab84c0/app/src/main/java/info/plateaukao/einkbro/activity/BrowserActivity.kt#L1128)、[惰性标签恢复](https://github.com/plateaukao/einkbro/blob/1ab77ff50e1ad7e699c0cec436326b868dab84c0/app/src/main/java/info/plateaukao/einkbro/activity/delegates/TabManager.kt#L183)、[容器销毁](https://github.com/plateaukao/einkbro/blob/1ab77ff50e1ad7e699c0cec436326b868dab84c0/app/src/main/java/info/plateaukao/einkbro/browser/BrowserContainer.kt#L36)。
- Fulguris：[创建与 Autofill](https://github.com/Slion/Fulguris/blob/fb8208ffda50b6864f56f8d31c6414aa4a0a9104/app/src/main/java/fulguris/view/WebPageTab.kt#L491)、[监听清理](https://github.com/Slion/Fulguris/blob/fb8208ffda50b6864f56f8d31c6414aa4a0a9104/app/src/main/java/fulguris/view/WebPageTab.kt#L1206)、[动画与销毁](https://github.com/Slion/Fulguris/blob/fb8208ffda50b6864f56f8d31c6414aa4a0a9104/app/src/main/java/fulguris/view/WebViewEx.kt#L263)。
- DuckDuckGo：[Fragment 创建](https://github.com/duckduckgo/Android/blob/80557a80cf1aadbbbc67d628a3347570003cf68d/app/src/main/java/com/duckduckgo/app/browser/BrowserTabFragment.kt#L4177)、[销毁](https://github.com/duckduckgo/Android/blob/80557a80cf1aadbbbc67d628a3347570003cf68d/app/src/main/java/com/duckduckgo/app/browser/BrowserTabFragment.kt#L5250)、[JS 对话框](https://github.com/duckduckgo/Android/blob/80557a80cf1aadbbbc67d628a3347570003cf68d/app/src/main/java/com/duckduckgo/app/browser/BrowserChromeClient.kt#L177)、[独立会话快照](https://github.com/duckduckgo/Android/blob/80557a80cf1aadbbbc67d628a3347570003cf68d/app/src/main/java/com/duckduckgo/app/browser/session/WebViewSessionStorage.kt#L44)。

这些证据支持 Activity/Fragment 级 View 所有权的可行性；不能证明竞品无 JNI/Binder 缺陷、所有 AutofillService 都兼容，或简单换 Context 就能修复 Helix 的原生问题。

### 推荐的最小改造边界

建议沿用当前 MainActivity，不增加浏览器 Activity、引擎依赖或独立进程。以下命名为候选设计，尚无这些新实现：

| 组成 | 所有者与职责 |
| --- | --- |
| 现有 BrowserController | 仍由应用持有逻辑标签、导航状态与工具入口；转发到当前有效宿主；不直接拥有跨 Activity 的 View 集合 |
| BrowserViewOwner | MainActivity 持有本代 WebViewTabHost 集合，以真实 Activity Context 创建 View；实际需要页面时才分配 |
| 宿主绑定 | 主线程执行 attach/detach，并用 ownerId/generation 核对身份；解绑仅作用于同代 owner，所有持有 Activity 的引用在解绑时断开 |
| UI 结果 | JS 对话框仅交给有效窗口；标签关闭、宿主销毁或取消时结束对应结果一次。不能以系统对话框响应替代 Helix 工具审批 |

必须区分以下情况，避免以恢复功能为名降低后台任务能力：

- **切换 Helix 页面或后台标签**：不等同于销毁 Activity，不应因此重建全部 WebView。
- **Activity 暂时进入后台**：保留原 View 所有权，沿用已批准任务和当前资源策略；依赖可见窗口的请求另行处理，不对所有 `browser.*` 一刀切增加“必须前台”条件。
- **Activity 真正销毁、进程死亡或 renderer 丢失**：释放旧 View，使 DOM token 和回调代次失效；保留可恢复的逻辑状态。恢复 View 不自动恢复未决工具副作用，不能盲目重复 click、POST 或表单提交。
- **冷启动且无 Activity**：不回退为 Application Context 伪装完整浏览器。页面操作如何停泊/唤醒必须与既有 Goal 和后台契约一起确定；不能为此未经决定自动拉起 Activity。

应用若保存当前 owner 的临时强引用，必须有确定性解绑；“只有 Activity 创建 View”本身不保证无泄漏。WeakReference 可避免该条引用保活，但不能替代身份校验、调用终结或取消清理。

### 不照搬的部分与平台边界

1. EinkBro 同时存在 WebView 预热，Helix 不采用：现有未使用宿主零分配已获实测，预热会重新增加这类分配。其 Manifest 还自行处理多种 configChanges；Helix 不直接复制该列表，需先验证语言、主题、窗口大小及嵌入 AndroidView 的更新。Android 官方说明，关闭重建会把配置更新责任交给应用：[配置变化处理](https://developer.android.com/guide/topics/resources/runtime-changes)。
2. Fulguris 为动画延迟销毁是其 UI 约束，不作为 Helix 的默认资源池或通用防泄漏手段。
3. DuckDuckGo 的 Bundle 恢复不应被理解为完整页面/JS 堆快照。官方 `saveState/restoreState` 不保存/恢复 display data；Helix 第一阶段继续采用现有逻辑占位和显式导航语义，历史栈/滚动恢复作为后续独立验证项。[WebView 状态保存](https://developer.android.com/reference/android/webkit/WebView#saveState(android.os.Bundle))
4. 当前 Helix 两处 pause 注释将 `onPause()` 描述为停止 JS timers，这不符合 API 保证。官方说明它不暂停 JavaScript，而 `pauseTimers()` 影响所有 WebView。迁移时须明确每个标签/任务的运行策略，不能直接补上全局 pauseTimers 来停止单个标签。[WebView.onPause](https://developer.android.com/reference/android/webkit/WebView#onPause()) 本轮记录这一语义偏差，未改变运行时调用。
5. 在以上核实的三个创建路径中，实际采用 Activity/inflater；没有看到依靠 MutableContextWrapper 换 base Context 来保留同一 WebView 的方案。这仅描述已读路径，不代表对三个仓库所有用途的全量否定。

### 实施顺序与决策条件

先完成有界的 Activity owner 试验，验证真实 Context 的 JS 对话框/Autofill、重建时身份校验和旧对象释放，同时保留既有工具调用的取消与恢复测试。首阶段不加入页面快照持久化、预热池、configChanges 绕过或引擎替换，以便独立判断 Context 改造的收益与代价。

验收除前述 API29/36 清单外，必须有旧 Activity 销毁发生在新 owner attach 之后的顺序测试、后台标签不重建测试、JS 对话框结果终结测试，以及同版 System WebView 下改前/改后的生产 JNI/Binder 对照。Autofill 需真实服务填写/保存，不能只检查 `importantForAutofill`。

只有功能和引用生命周期均验证后，才能决定将 Activity owner 接入生产。它改变 browser/app 的生命周期协作以及无 Activity 时的恢复契约，应先形成拟议 ADR；本节是供决策的推荐方案，不是新 ADR 已接受或底层系统问题已修复。

## HXA-160 落地

`MainActivity` 持有 `BrowserViewOwner`，应用级 `BrowserController` 仅弱绑定 owner 并保留逻辑标签。WebView 使用真实 Activity Context 惰性创建；下载仍使用 Application Context 的 ContentResolver。attach/detach 与旧页回调校验身份，旧 Activity 不能释放新 owner。后台切换保留 View；真正销毁清理 View/待处理 JS 对话框和 DOM token，保留 URL/标题供显式导航，不重放页面或工具。

仅选中的、已 resumed 且窗口可用的标签显示 JS alert/confirm/prompt/beforeunload；后台、导航、停止、关闭和解绑取消待处理结果。没有 owner 时非空 browser.open 明确失败、不增加标签；navigate 返回 browser-host-unavailable，缺少真实页面的历史/重载不伪造成功。

Autofill 使用系统绑定的测试服务完成真实填写、保存、Activity 重建和撤销。API36/WebView133 的夹具先定位真实输入框并通过 WebView 的公开 InputConnection 提交字符触发服务；选择填充和编辑后等待 DOM 反映结果再保存，避免把输入事件入队误当作页面已处理。详情、失败过程与 JNI/Binder 证据见 [HXA-160](../completion-records/HXA-160.md)。这不是对所有密码管理器、OEM 或真机的兼容承诺，也不消除系统 JNI/Binder 问题。
