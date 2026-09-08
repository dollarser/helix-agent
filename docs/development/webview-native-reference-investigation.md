# WebView 原生弱引用表溢出调查

Date: 2026-09-06
Related HXA: HXA-103
Status: open

## 已确认的现象

连续浏览器长稳在 API 29 运行 13376 秒后以 SIGABRT 结束，最后完成 1826 轮。
崩溃为 `JNI ERROR (app bug): weak global reference table overflow (max=51200)`。
末次 FD 120、线程 47、PSS 116630 KiB，未先触发既有资源漂移阈值。
该运行是 FAIL，不是 24 小时通过。

| 对照 | 系统 / WebView | 路径 | 结果 |
| --- | --- | --- | --- |
| 原始长稳 | API 29 / 91.0.4472.114 | Helix host | 约 3h43m 后溢出 |
| 原生创建销毁 | API 29 / 91.0.4472.114 | instrumentation，application Context | 最后完成 25500 次后溢出 |
| 原生创建销毁 | API 34 / 113.0.5672.136 | instrumentation，application Context | 最后完成 25500 次后溢出 |
| 原生创建销毁 | API 29 / 133.0.6943.137 | instrumentation，application Context | 最后完成 25500 次后溢出 |
| 独立 Activity | API 29 / 133.0.6943.137 | am start，application Context | 最后完成 25500 次后溢出 |
| Activity Context | API 29 / 133.0.6943.137 | am start，Activity Context | 最后完成 10200 次后被系统终止：Too many Binders sent to SYSTEM |

所有原生对照均不使用 Helix host、WebViewClient 或业务回调，不强制 GC。
独立 Activity 每个实例销毁后延迟 5ms 投递下一次创建，让主线程处理异步清理；只存在于测试 APK。
原生对照要求 30000 次，失败前最后一个日志批次为 25500，不将批次计数冒充精确崩溃实例数。

## 归因边界

不经过 instrumentation 的测试也能复现，已经排除 Helix 浏览器组件和 AndroidJUnitRunner
作为复现所必需的条件。当前仍未定位具体未释放的 native 引用所有者，不能因此声称 Helix 所有
生命周期路径无缺陷，也不能声称升级到 WebView 113 或 133 已解决。

Chromium 当前 [AwContents 源码](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/android_webview/java/src/org/chromium/android_webview/AwContents.java)
包含异步 native 销毁及 CleanupReference 清理路径。当前主干源码与已安装旧版不同，仅用于调查线索，
不能直接当作旧版本根因或修复证据。Activity Context 对照也未通过，因此不能直接通过更换 Context 宣称修复。需要继续定位 native/Binder 资源所有者，再选择有证据的修复或运行时策略。
不通过增加资源阈值、周期重启或强制 GC 将失败改写为通过。

## 证据

忽略目录 `build/main-verification/` 下保留：

- `soak-24h-api29/`
- `raw-webview-api29/`
- `raw-webview-api34/`
- `raw-webview133-api29/`
- `raw-activity-webview133-api29/`
- `raw-activity-context-webview133-api29/`

对照目录包括启动/instrumentation 日志、logcat、运行时与 APK hash；不提交 APK 或完整运行日志。
WebView 133 从既有专用 API 36 设备只读提取，仅安装到专用 API 29 设备；API 36 应用长稳未被改动。
完整收尾状态见 [M10 收尾跟进](m10-closure-followup.md)。
