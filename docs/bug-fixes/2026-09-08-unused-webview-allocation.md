# Bug Fix: 未使用宿主不应提前创建系统 WebView

Status: fixed
Date: 2026-09-08
Related HXA: HXA-158
Affected modules: feature/browser

## Problem

WebViewTabHost 在构造时无条件创建 WebView，即使调用者只销毁宿主。受影响的系统 WebView 会在未建立 renderer frame 的销毁路径留下 native 弱全局引用槽位。

## Impact

直接宿主创建/销毁循环会消耗有限 JNI 槽位；历史资源长稳反复执行这种路径。生产 BrowserController 已对首次 Load 延迟创建宿主，不能把该缺口推论为每次生产导航都泄漏。

## Root cause

资源所有者 WebViewTabHost 没有把昂贵平台 View 的分配与实际使用绑定。两台旧实现各 240 次未使用宿主产生 480 个目标引用残留。平台 RFH 映射的候选机制和验证边界保存在原生追踪报告，App 无法通过 Java GC 替代其 DeleteWeakGlobalRef。

## Fix and invariants

宿主延迟初始化平台 View，实际 load/evaluate/View 访问前安装原安全设置和客户端。未初始化状态的 stop/pause/resume/历史动作/清缓存/destroy 不分配 View；destroy 幂等，后续显式访问不得复活。已分配 View 仍及时停止、detach、撤销回调并销毁。

## Alternatives considered

不在生产销毁前强制导航，不延迟销毁，不发送 GC，不周期重启，不禁用 Autofill。更换内核或引入复用池需要解决额外的 UI/状态/数据隔离问题；当前证据支持更小的分配修复。

## Regression verification

两台各 240 次旧/新宿主对照：目标残留 480→0；新 evaluate、正常导航与 HTTP 取消均目标残留 0，裸平台正对照仍残留。新增 unusedHostLifecycleNeverAllocatesOrResurrectsAWebView 与 firstUseAllocatesOnceAndDestroyDropsLateEvaluation；既有安全、renderer 退出、动作、snapshot、资源测试回归。精确结果见 HXA-158。

## Residual risk

这是已确认触发路径的应用规避，系统 WebView/Binder 问题未修复。显式请求 webView 再不使用就销毁仍可能触发裸平台缺陷；生产控制器正常先 Load，不依赖这种用法。Application Context 的 Autofill/JS 对话框限制未解决；24h 与真机未重跑。

## Related records

- [HXA-158](../completion-records/HXA-158.md)
- [规避与方案评估](../development/native-reference-mitigation.md)
- [JNI/Binder 释放路径](../development/native-reference-release-trace.md)
