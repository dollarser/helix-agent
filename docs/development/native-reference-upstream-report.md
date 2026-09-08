# 原生引用问题：待上游核实的复现材料

2026-09-08。状态：报告准备完成，未对外发送，原生缺陷未关闭。

## WebView JNI weak global reference accumulation

环境：arm64 Android API29 / WebView91.0.4472.114，以及 API36 / WebView133.0.6943.137。最小动作：主线程反复 WebView(applicationContext).destroy()，没有 Helix 生产 controller，也不强制 GC。独立控制复现 weak global reference table overflow (max=51200)。

JVMTI 配对：400 轮中 AwContentsIoThreadClient 子类 new=1600 / delete=800 / residual=800；完成实际导航的对照及 Helix controller 发起导航后立即关闭均 residual=0。Java GC 不会代替 native 所有者执行 DeleteWeakGlobalRef。

候选所有者为 aw_contents_io_thread_client.cc 的两张 RFH 映射：初始化时登记主 frame，而移除依赖 RenderFrameDeleted；WebContentsDestroyed 没有两张映射的兜底清理。尚未以符号化/修补版确认源码行，不能将候选机制写成已验证补丁。

请求上游核实：renderer frame 尚未创建的 WebContents 销毁时，已登记的两张映射是否有对称释放；修复须同时覆盖首个请求拦截、frame 切换、prerender 和重复删除。不能简单要求所有调用者销毁前导航，也不能为消除残留破坏首请求 interception。

## Android Binder proxy cleanup lag

最小动作：无 WebView，仅重复 AutofillManager.isAutofillSupported 和 autofillServiceComponentName 查询。历史有界对照触发 Too many Binders sent to SYSTEM。1000 轮后应用 Local Binders 约 2010；应用 GC 后基本不变，system_server 诊断 GC 后降至约 10，同时系统 IResultReceiver 代理减少。

源码链：SyncResultReceiver 本地 Stub → 系统 BinderProxy/native sp<IBinder> → 系统 native cleanup/BpBinder 析构 → Binder 驱动释放 → 客户端 JavaBBinder 析构/DeleteGlobalRef。数据支持系统代理清理滞后，不证明永久不可回收；也不能把所有历史浏览器 Binder 终止唯一归因于 Autofill。

请求平台核实：大量短期 IResultReceiver 代理在低 Java 堆压力下的清理触发与 proxy watermark 是否匹配，能否在平台侧避免清理前过早终止发送方。产品不应向 system_server 发送 GC 信号，也不应禁用 Autofill 掩盖阈值。

## 复现附件与交付边界

全部精确命令、版本源码链接、安装 hash、计数、无效观测与局限见 [JNI/Binder 追踪](native-reference-release-trace.md)、[生产本地路径](browser-controller-reference-verification.md)、[网络及后台路径](browser-network-reference-verification.md)。原始日志留在忽略的 build/reference-trace 与 build/webview-diagnostic，发布前单独审核附件，不能夹带其他运行日志、账号或机器数据。

Helix 架构要求系统 WebView，不 fork Chromium；当前没有符合既有边界且经实测的应用内 native 修复。这里交付可审查的上游材料，不宣称平台缺陷已修复，也不未经所有者明确授权对外发 issue。
