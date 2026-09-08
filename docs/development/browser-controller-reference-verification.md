# BrowserController 生产路径引用核实

Date: 2026-09-08
Status: HXA-154 bounded verification completed；原生裸创建/Binder 问题仍 open

本轮核实修正了 [HXA-153](native-reference-release-trace.md) 对生产影响的推断边界：**裸 WebView 创建后销毁的残留不能直接等同于 Helix 日常导航/关闭泄漏。** BrowserController 已经在第一次允许的 Load 时才创建 host；空标签、拒绝导航均不创建 WebView。此次没有发现需要修改生产回收逻辑的直接证据，因此未添加强制 GC、销毁前导航、延迟销毁或周期重启。

## 真实生产路径的有界对照

测试 APK 的 `ControllerReferenceControlActivity` 使用原有 BrowserController、WebViewTabHost 和全部原始 callbacks；没有用测试 WebViewClient 替换生产行为。导航内容为本地 `data:text/html`，加载路径把返回的 WebView 挂载到 Activity，close/clearHistory 之后断言 host 已移除、View 已脱离父级、旧 tab 已消失。立即关闭/停止在同一个主线程调用内完成；加载完成路径等待生产 tab 的 navigationGeneration 和 loading 状态。

API29 / WebView91.0.4472.114 与 API36 / WebView133.0.6943.137 各路径执行 400 轮，合计 4800 轮；JNI agent 在循环开始前附加，所有组 `unmatchedDeletes=0`。

| 生产路径 | API29 目标类创建 / 删除 / 残留 | API36 目标类创建 / 删除 / 残留 |
| --- | --- | --- |
| 空标签后关闭 | 0 / 0 / 0 | 0 / 0 / 0 |
| file: 策略拒绝后关闭 | 0 / 0 / 0 | 0 / 0 / 0 |
| 发起本地导航后立即关闭 | 3993 / 3993 / 0 | 3921 / 3921 / 0 |
| 发起导航、停止、立即关闭 | 3985 / 3985 / 0 | 3926 / 3926 / 0 |
| 加载完成后关闭 | 4000 / 4000 / 0 | 4000 / 4000 / 0 |
| 加载完成后清理历史 | 4000 / 4000 / 0 | 4000 / 4000 / 0 |

目标类仍为实际 DEX 确认的 `LG8;` / `LWV/T6;`，即 AwContentsIoThreadClient 子类。空标签与拒绝组的全部观测表均为空。其余组全部观测表尚有 API29 6～7、API36 8～10 个其他引用，不能把目标类零残留说成进程零引用。

这些结果表明：在本次本地导航对照中，**等待页面加载完成并非引用成对释放的必要条件**。HXA-153 的“完成导航后无残留”是一个充分对照，不能据此要求生产销毁前等待导航。真实网络请求尚未建立 renderer 的窗口、后台/Activity 重建、不同 WebView 版本和长稳不由此次覆盖。

## 诊断优化与复核

- 原始导航夹具以前挂载后直接 destroy；补上从父 View 移除，符合生产 disposeView 已有做法。此问题仅在测试夹具，未修改生产代码。
- 增加循环 BEGIN 与 agent END 标记，脚本拒绝观察器晚于循环开始或 dump 未结束的证据；显式拒绝观察表溢出。
- 允许真实的空观测表，不再把缺少 CLASS 行自动当成 agent 失败。
- 每组保存设备 fingerprint、WebView 状态、APK/agent SHA-256 和 PID，避免只凭可变宿主构建文件认定设备版本。
- 修正后的原生夹具各补跑 100 轮：两台裸创建均仍残留目标类 200 个引用；挂载并完成 about:blank 后销毁均为 0。这是正/负对照，确认工具仍能检测已有问题，历史失败没有被清除。

[当前 Chromium 源码快照](https://chromium.googlesource.com/chromium/src/+/7c285c4cd9f9c201a9c8e7e1cc3a578bd9886291/android_webview/browser/aw_contents_io_thread_client.cc) 仍有 main frame 初始化登记及 RenderFrameDeleted 清理两映射的结构。它不等于当前发行 APK 的实测，不能承诺“升级 WebView 就已修复”。Helix 既有架构明确使用系统 WebView、不 fork Chromium；对 native 清理的修补版验证应属于独立上游/外部诊断，不能未经架构决定变成内置 Chromium。

## 命令和产物

```bash
./gradlew :feature:browser:testDebugUnitTest :feature:browser:assembleDebugAndroidTest spotlessCheck detekt :feature:browser:lintDebug --max-workers=1
bash scripts/diagnostics/build-jni-reference-trace.sh
# serial 必须为实际专用模拟器；scenario 为表中六种路径之一。
python3 scripts/diagnostics/run-jni-reference-trace.py emulator-5596 --count 400 --scenario early-close
python3 scripts/diagnostics/run-jni-reference-trace.py emulator-5598 --count 400 --scenario early-close
# 其余 scenario：empty、denied、stop-close、settled-close、clear-history。
# 原生正/负对照各 100 轮：省略 --scenario，分别不加和加 --navigate。
adb -s emulator-5596 shell am instrument -w -r -e class com.helix.feature.browser.BrowserSecurityDeviceTest com.helix.feature.browser.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5598 shell am instrument -w -r -e class com.helix.feature.browser.BrowserSecurityDeviceTest com.helix.feature.browser.test/androidx.test.runner.AndroidJUnitRunner
```

执行 instrumentation 前 force-stop 诊断测试进程，使 JNI agent 随进程退出，安全回归不依赖 agent。NDK/JDK/环境变量要求沿用 HXA-153。宿主 Gradle、JVM（116 项，无失败/跳过）、NDK 严格编译和 Python 语法检查通过；浏览器安全设备结果见完成记录。

忽略目录 `build/reference-trace/emulator-*-<scenario>-400/` 保存全部配对日志与 metadata；`emulator-*-bare-100`、`emulator-*-navigate-100` 为本轮原生对照。APK SHA-256：`43a1e5139071ee8488ae4748f6a2b1cda809320c9ea911ae2f9286a4ec98d6f4`；agent SHA-256：`362f762860855518703a408ae07dba19ef52a58648c36119534d13c27c8c2531`。上游固定源码已与即时 main 内容逐字核对一致，下载文件留在忽略目录。

下一步保留为独立验证：真实网络取消/后台生命周期的引用矩阵、更多系统 WebView 发行版本及上游清理方案。系统 Binder 回收滞后的事实未改变；本轮不对 system_server 发 GC 信号，不重跑 24h 长稳或真机验收。
