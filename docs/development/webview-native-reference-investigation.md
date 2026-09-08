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

## 2026-09-08：HXA-152 短时拆分归因

历史 JNI 崩溃表末尾出现 `cleared jweak`，表摘要中存活对象数量远低于 51200；这支持继续追查 native 弱引用槽位释放，而不是直接当作 Java WebView 实例全部被强引用保留。历史 Activity Context 对照的系统 Binder descriptor 第一项为 `com.android.internal.os.IResultReceiver x5980`，测试 UID 的系统代理计数为 6002。这是进一步拆分对照的依据，不能把触发溢出的最后一个 `NewWeakGlobalRef` 调用当作泄漏创建者。

新增测试 APK 对照 `autofillOnly=true`：每轮仅调用 Android `AutofillManager.isAutofillSupported` 与 `getAutofillServiceComponentName`，不创建 WebView，不用 Helix host 或 instrumentation。主线程每轮间隔 5ms，不强制 GC，每 100 轮记录应用本地 Binder、代理与实际 GC 次数。

| 对照 | API29 | API36 |
| --- | --- | --- |
| 原生 WebView（application Context），目标 30000 轮 | FAIL；WebView91，最后日志 25500 轮，本地 Binder 21，GC 304 次；JNI weak global reference table overflow (max=51200) | FAIL；WebView133，最后日志 25500 轮，本地 Binder 256，GC 403 次；相同 JNI 溢出 |
| 仅 Autofill 查询，目标 30000 轮 | FAIL；最后日志 2900 轮，应用本地 Binder 5810，GC 6 次；系统以 Too many Binders sent to SYSTEM 终止 | FAIL；最后日志 3100 轮，应用本地 Binder 6216，GC 6 次；相同系统终止原因 |

**确认范围**：上述 Binder 终止可以在完全没有 WebView 的独立平台调用中复现，且应用 GC 已运行；新对照提供一个独立充分复现条件。不能据此认定 Autofill 是所有旧 WebView/Binder 失败的唯一来源，也不能将应用 GC 次数当作 system_server 已充分回收代理的证明。

Android 10 的 [AutofillManager 源码](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/view/autofill/AutofillManager.java) 显示这两个查询分别创建 SyncResultReceiver；[SyncResultReceiver](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/com/android/internal/util/SyncResultReceiver.java) 实现 IResultReceiver.Stub，与观察到的 descriptor 一致。Chromium 133 的 [AutofillManagerWrapper](https://chromium.googlesource.com/chromium/src/+/133.0.6943.137/components/android_autofill/browser/java/src/org/chromium/components/autofill/AutofillManagerWrapper.java) 仅在相应启用条件下查询服务组件，故不把两个查询对照等同于所有 WebView 实际调用序列。以上仅引用机制，不复制第三方代码。

复现命令（仅专用模拟器，目标次数显式给定）：

```bash
./gradlew :feature:browser:assembleDebugAndroidTest spotlessCheck detekt --max-workers=1
adb -s "$serial" install -r feature/browser/build/outputs/apk/androidTest/debug/browser-debug-androidTest.apk
adb -s "$serial" shell am force-stop com.helix.feature.browser.test
adb -s "$serial" shell am start -n com.helix.feature.browser.test/com.helix.feature.browser.webview.RawWebViewControlActivity --ei iterations 30000 --ez autofillOnly true
adb -s "$serial" logcat -d -v threadtime
```

省略 `autofillOnly` 即原生 WebView 创建/销毁路径。`created` 是最近一次日志批次，不是精确崩溃轮数。原始日志、系统/WebView 版本、APK hash 与独立结果 JSON 位于忽略目录 `build/webview-diagnostic/`。

生产代码未因此禁用 Autofill、替换 Context、强制 GC、周期重启或放宽门限。JNI 槽位创建/删除的具体 native 所有者及系统代理回收路径仍需分别定位；原有长稳 FAIL 和后置安排保留。

原生 WebView 两台均在多次 GC 后复现 JNI 溢出，而本地 Binder 未出现 Autofill 查询对照的每轮线性累积；因此本轮未把两种终止合并为单一根因。API36/WebView133 的失败补齐了较新系统上的独立复现证据。四组均为 FAIL，不是 30000 轮通过，更不是 24h 长稳通过。下一步应使用可符号化 WebView/ART 调试构建记录 JNI weak-global 创建/删除归属；Binder 分支另查 Autofill 查询回调与 system_server 代理的持有和释放，不使用隐藏 API 或产品能力降级掩盖。
