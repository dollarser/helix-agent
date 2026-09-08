# JNI/Binder 应用规避与方案评估

Date: 2026-09-08
Related HXA: HXA-158

## 结论与边界

已修复 Helix WebViewTabHost 未使用就分配系统 WebView 的行为，规避该路径触发的 JNI 槽位累积。系统 WebView 裸创建和 system_server Binder 清理滞后的缺陷仍开放；没有把 App 规避写成系统修复。新需求暂停，本轮不更换运行时、不启动 Connector 新功能。

## 为什么历史长稳会触发

原 continuousResourceSoak 每轮调用 repeatedCreateDestroyDoesNotGrowTargetProcessDescriptors；该方法执行 2 次 warmup + 12 次 measured 的未导航宿主创建/销毁。旧 WebViewTabHost 构造函数立即创建 WebView，每个实例在独立追踪中残留 2 个 AwContentsIoThreadClient 弱全局引用。因此 1826 轮约对应 51128 个此类槽位，接近 51200 的溢出阈值。数量解释支持触发机制，但不是对历史进程逐引用追溯；历史 FAIL 保留。

BrowserController 已在首次允许的 Load 才创建宿主，空标签/拒绝导航没有这类分配。因此此前把裸创建失败笼统叫作所有生产导航都泄漏不准确。此次把延迟分配进一步落实在 WebViewTabHost 内部，避免直接宿主调用重新引入缺口。

## 修复机制

宿主只在首次访问实际 View、load 或 evaluate 时初始化 WebView；暂停/恢复/停止/历史动作/清缓存和销毁不会唤醒未初始化 View。destroy 幂等，销毁后访问不能复活 View；已初始化的 View 仍停止、脱离父 View、清理回调并按原路径 destroy。安全设置及客户端在首次使用前完整安装。

没有销毁前强制导航、延迟销毁、强制 GC、隐藏 API、native hook 或周期重启进入产品。JNI agent 仅在诊断进程使用。

## JNI 同源观察器对照

两台 dedicated arm64 模拟器：API29 WebView91.0.4472.114、API36 WebView133.0.6943.137。同一 agent SHA-256 为 `362f762860855518703a408ae07dba19ef52a58648c36119534d13c27c8c2531`。各组 unmatchedDeletes=0。

| 路径 | 每台轮数 | API29 目标引用 new/delete/live | API36 目标引用 new/delete/live |
| --- | ---: | --- | --- |
| 旧宿主，未使用后销毁 | 240 | 960/480/480 | 960/480/480 |
| 新宿主，未使用后销毁 | 240 | 0/0/0 | 0/0/0 |
| 新宿主，evaluate 后立即销毁 | 240 | 1680/1680/0 | 1440/1440/0 |
| 新生产控制器，导航完成后关闭 | 1000 | 10000/10000/0 | 10000/10000/0 |
| 新生产控制器，HTTP 请求到达后停止关闭 | 100 | 1000/1000/0 | 1000/1000/0 |
| 裸平台 WebView 正对照 | 100 | 400/200/200 | 400/200/200 |

原始裸平台控制仍复现，证明并未删除失败场景或使观察器失效。未使用组全部观察表为空；其他正常组仍有少量其他类型残留，不能声称整个进程零引用。HTTP 两台均收到 100 个唯一轮次请求。

## Binder 核实

本轮 1000 次生产导航/关闭中，按每 100 轮采样，API29 应用 local Binder 在 43～168、proxy 在 34～54；API36 local 在 180～729、proxy 在 61～79。两台同一测试 PID 均完成全部轮次，计数多次下降，没有人为 GC 或进程重启。它支持本轮生产路径没有复现独立 Autofill 高频查询的持续线性累积，不证明 system_server 已修复或未来不会到达阈值。未重新执行历史 system_server GC 干预实验。

HXA-158 验证时 BrowserController 使用 Application Context，未直接轮询 AutofillManager；[Android 官方文档](https://developer.android.com/reference/android/webkit/WebView#WebView(android.content.Context)) 明确此 Context 会限制 Autofill 和 JavaScript 对话框。因此不能把现有 Context 当作没有功能代价的修复，也不能用当前测试证明 Activity Context 下的 Autofill 完整性。本轮未新增禁用设置，亦未修改 Context。若后续修复此已有 Context 限制，应另以 Activity Context 与真实 Autofill 服务验证，不能用 Application Context 对照替代。

## 替代方案的取舍

| 方案 | 能解决什么 | 限制与本轮选择 |
| --- | --- | --- |
| 宿主延迟分配 | 避免未使用 WebView 的 JNI/Binder 分配 | 已实现并通过前后配对；不能修复任意裸平台调用 |
| 销毁前加载 about:blank / 主动 evaluate | 导航对照可成对释放目标引用 | 会增加不需要的 renderer 工作和回调，不能在 renderer 已死亡时使用；生产不采用 |
| 升级系统 WebView | 若上游已修复可能消除 native 缺陷 | 已测试的 91/113/133 均曾失败；未验证版本不承诺有效，不自动改用户 WebView |
| WebView 复用池 | 降低频繁新建次数 | 需验证跨标签历史、表单、回调、权限及私密数据清理；仅延缓尚存泄漏，不在无生产复现时引入 |
| 浏览器独立进程 | 可隔离部分 JNI 崩溃影响 | 需要 UI/工具 IPC 和恢复方案；同 UID 不能隔离 Binder UID 配额，因此不是两类故障的统一修复 |
| 自带/修补 Chromium 或替换内核 | 可控制底层实现 | 新运行时、分发体积、许可证、持续安全更新与兼容验证成本高；现有证据不支持为本问题替换整个方案 |
| 强制 GC / 重启 / 提高阈值 | 可能临时改变资源曲线 | 应用 GC 不清除 native 所有权或系统代理；重启会丢失页面状态；不采用 |

Binder 按 UID 计数并可 killUid 的依据见 [AOSP ActivityManagerService 固定源码](https://android.googlesource.com/platform/frameworks/base/+/99aae825ded253fe58695ceb853f2f631137f1c4/services/core/java/com/android/server/am/ActivityManagerService.java)。同 UID 分进程不能作为 Binder 配额隔离，是基于该机制的推论。

## 测试与产物

原始数据在忽略的 build/reference-trace/emulator-*-host-unused-240-before、host-unused-240、host-evaluate-240、settled-close-1000、network-stop-100、bare-100 下；每组保存 metadata、APK/agent hash、JNI 日志和 meminfo。既有同名旧轮次另存 pre-hxa158，未冒充本次结果。

复现旧/新宿主：`python3 scripts/diagnostics/run-jni-reference-trace.py SERIAL --count 240 --host-only`；JS 组再加 `--evaluate-host`。生产与原生正对照沿用脚本的 scenario/count 参数。使用专用模拟器实际 SERIAL；脚本只接受 emulator。

资源 FD 测试在延迟分配后显式执行正常 about:blank 加载再关闭，保持真实 WebView 资源覆盖；新增未使用宿主生命周期/销毁后不得复活测试。独立 rawPlatformWebViewLifecycleControl 保留。此测试工作负载调整不是对历史长稳结果的改写；24h 和真机均未重跑。

精确构建/设备命令与结果见 [HXA-158 完成记录](../completion-records/HXA-158.md)。底层释放证据见 [HXA-153](native-reference-release-trace.md)。

Application Context 的功能限制、Activity 级宿主建议、包装器/自定义对话框折中及迁移验收见 [HXA-159 Context 分析](browser-context-options.md)。该分析未改变本报告的已测实现或系统缺陷状态。

## HXA-160 Context 后续落地

Activity owner、JS 对话框与真实 AutofillService 回归已另行实施，见 [完成记录](../completion-records/HXA-160.md)。该记录保留同观察器改前/改后 JNI 配对与 Binder 原始采样；不覆盖本报告的历史失败，也不把 Activity Context 迁移宣称为系统 Binder 修复。

## 系统问题的竞品与上游源码复核（2026-09-09）

所有者要求继续参考竞品分析原因。本节仅补充研究，不修改生产代码、不重开已完成 HXA，也未运行新设备测试。重新读取下列固定版本源码，并与 HXA-153/158/160 的已有实验对照。竞品未做同负载设备对照，因此不宣称其无泄漏或已修复本问题。

### 两条独立的资源链

**JNI：强烈支持 native 映射清理遗漏，尚缺修补版验证。** Chromium 133 的 `ClientMapEntryUpdater` 在存在 primary main frame 时就登记 client；`RfhToIoThreadClientMap::Set` 分别向 frame-tree-node 与 RFH token 映射复制弱引用。删除依赖 `RenderFrameDeleted`，但 `RenderFrameHostImpl` 只有在 renderer frame 曾创建时才发送该通知。`WebContentsDestroyed` 清理的是另一张 WebContents 映射，没有兜底删除前述两个映射中的记录。这与每次裸创建/销毁残留两个目标句柄、导航后配对归零相符。清除 Java 对象或 Activity 引用不能自动释放 native 映射占据的 JNI 槽位。

本次重新核对的源码：[IoThreadClient](https://chromium.googlesource.com/chromium/src/+/133.0.6943.137/android_webview/browser/aw_contents_io_thread_client.cc)、[RenderFrameHostImpl](https://chromium.googlesource.com/chromium/src/+/133.0.6943.137/content/browser/renderer_host/render_frame_host_impl.cc)。最终源码级定因仍需符号化或修补版前后对照；不能凭源码形似断言某个未测试发行版本已修复。

**Binder：已证实系统代理回收可解除累积，更准确地说是回收滞后／分配速率问题。** Android 16 的 `isAutofillSupported()` 与 `hasEnabledAutofillServices()` 每次查询都构造 `SyncResultReceiver`。服务端 BinderProxy 使用弱引用表和 native allocation 清理；回收 native 数据后释放 Binder 引用并 flush，客户端 JavaBBinder 才有机会解除对 Java 回调的强全局引用。HXA-153 的系统 GC 干预可使约 2000 个引用回落，而应用 GC 无效，支持这条跨进程所有权链。它不是“对象永远无法回收”，也不能据此将所有 Binder 增长归因于 Autofill。

本次重新核对的源码：[AutofillManager](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/view/autofill/AutofillManager.java)、[BinderProxy](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/os/BinderProxy.java)、[native Binder 清理](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/jni/android_util_Binder.cpp)。尤其注意：`hasEnabledAutofillServices()` 判断的是调用应用提供的服务是否启用，不是通用的“用户是否启用了任意密码管理器”。独立查询压力测试不是实际表单使用频率的等价模型。

### 竞品实际做了什么

| 固定源码 | 已读路径的做法 | 对本问题的意义 |
| --- | --- | --- |
| [EinkBro TabManager](https://github.com/plateaukao/einkbro/blob/1ab77ff50e1ad7e699c0cec436326b868dab84c0/app/src/main/java/info/plateaukao/einkbro/activity/delegates/TabManager.kt) | 恢复后台标签时只保留元数据；切换到已有 View 时优先保留挂载，仅改变可见性；另有预热 WebView 和直接销毁预热实例的路径 | 惰性恢复和减少无意义 attach/detach 值得参考；预热后未使用就销毁可能接近 JNI 触发条件，不能照搬，也未据此认定竞品已复现 |
| [Fulguris WebViewEx](https://github.com/Slion/Fulguris/blob/fb8208ffda50b6864f56f8d31c6414aa4a0a9104/app/src/main/java/fulguris/view/WebViewEx.kt) | stop、pause、清理历史/子 View 后 destroy；有父 View 时为动画推迟销毁 | 属于 View 生命周期管理；清历史、绘图缓存或延迟销毁不能证明补齐 native 映射删除，更不能控制 system_server GC |
| [DuckDuckGo BrowserTabFragment](https://github.com/duckduckgo/Android/blob/80557a80cf1aadbbbc67d628a3347570003cf68d/app/src/main/java/com/duckduckgo/app/browser/BrowserTabFragment.kt) | 取消协程和界面任务，移除自身 Autofill JS 接口；销毁路径移除容器子 View、移除系统 Autofill callback、destroy 并置空 WebView | 说明需要清理应用自己注册的监听和任务；取消 callback 不等于释放已完成同步查询留在系统端的 IResultReceiver，也不支持 Helix 增加特权 JS bridge |

上述三个路径都没有提供直接删除 Chromium native 映射或强制释放 system_server 代理的应用层实现。这个结论仅限已读源码，不能扩展为全仓库审计。桌面 Agent 的浏览器进程管理无法直接验证 Android 这两条链；也不应以换引擎后绕开一条 WebView 路径来推断 Android Binder 问题同时消失。

### 对 Helix 的处理建议

1. 保留 HXA-158/160 的惰性分配、Activity 所有权与确定性解绑；当前 `feature/browser/src/main`、`app/src/main` 检索未发现直接调用上述两个 Autofill 查询，也没有应用级 AutofillManager 轮询可删除。系统 WebView 内部行为仍需另行追踪。
2. 不新增禁用 Autofill、Application Context 回退、强制 GC、预热池或每次销毁前导航。已测生产目标 JNI 引用可配对释放，不支持为裸平台失败破坏真实表单功能。Activity Context 是功能和生命周期修复，不能称为 Binder 根治。
3. 下一个有价值的短时诊断是同版 WebView 下固定真实表单工作量，对比无服务与真实 Autofill 服务，按轮次采样应用 local/proxy Binder、system_server 对该 UID 的代理和 IResultReceiver 类型，同时记录实际查询数量、耗时、自然 GC 与 PID。先判断生产是否持续增长，再考虑有证据的调用去重；不能只比两次测试的结束计数。该诊断尚未执行，长稳仍后置。
4. JNI 上游候选修复应围绕登记记录的确定性所有权：使从未创建 renderer 的 RFH 也能释放属于它的两个映射条目，并保持共享 frame-tree-node 记录的引用集合语义。需覆盖首次请求拦截、子 frame、导航替换、prerender、renderer crash 和重复清理；不要简单跳过登记或清空全局表。此为待验证修复方向，不是已编译补丁。
5. Binder 上游候选调查应围绕每查询分配回调与系统 native allocation/清理调度。应用无法安全代替系统释放仍被引用的代理；共享一个回调也必须解决并发、超时和迟到结果串线，不能直接替换。需要同工作量下的系统修补版对照才能称根治。

本次结论：现有证据支持继续使用系统 WebView；竞品参考没有给出足以推翻 ADR-0033 的依据。JNI 保留“系统清理缺口的强证据”，Binder 保留“已复现回收滞后，生产持续累积待验证”，分别跟踪，避免把二者合并成一个笼统内存泄漏。
