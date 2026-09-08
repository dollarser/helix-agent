# 浏览器 Context 与生命周期优化分析

日期：2026-09-08。HXA-159 的分析交付；不是已实施的 Context 迁移，也不代表 Autofill 已验收。

## 当前事实

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
