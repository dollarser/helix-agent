# JNI 弱引用与系统 Binder 释放路径追踪

Date: 2026-09-08
Status: HXA-153 completed（有界诊断交付）；生产缺陷仍 open

本记录继续 [原生引用调查](webview-native-reference-investigation.md)。没有改动生产浏览器，也没有重跑或宣称通过 24h 长稳。实验均为专用 arm64 模拟器的独立测试 APK。

## JNI：实际创建/删除配对

诊断 agent 通过 JVMTI 替换测试进程的 `NewWeakGlobalRef` / `DeleteWeakGlobalRef` 函数入口，原调用保持执行；按弱引用句柄配对，记录 Java 类签名、native 模块相对地址及调用栈。只观察附加后的调用，不代表进程启动以来全部引用。两台设备均先延迟 10 秒供附加，再执行 400 轮，不主动触发 GC。

| 系统 / WebView | 对照 | 目标类创建 | 删除 | 残留 | 全部观测残留 |
| --- | --- | ---: | ---: | ---: | ---: |
| API29 / 91.0.4472.114 | 创建后直接 destroy | 1600 | 800 | 800 | 806 |
| API36 / 133.0.6943.137 | 创建后直接 destroy | 1600 | 800 | 800 | 812 |
| API29 / 91.0.4472.114 | 挂载 View，about:blank 完成后 destroy | 2800 | 2800 | 0 | 6 |
| API36 / 133.0.6943.137 | 挂载 View，about:blank 完成后 destroy | 2400 | 2400 | 0 | 8 |

四组均完成 400 轮，`unmatchedDeletes=0`，无观察表溢出。API29 另一次 200 轮裸创建留下目标类 400 个引用，与每轮增加 2 个一致。导航对照的零残留仅针对目标类，不代表进程完全无引用或生产测试通过。

从实际安装 APK 的 DEX 确认，API29 的 `LG8;` 和 API36 的 `LWV/T6;` 都继承 `org.chromium.android_webview.AwContentsIoThreadClient`，并持有外部 `AwContents` 字段。因此这次记录的是具体残留对象类型，不是根据崩溃时最后一次分配猜测。

API36 的创建调用地址为 `libmonochrome_64.so+0x50ca568`，两组上游栈含 `+0x29080c8`、`+0x2908124`；API29 为 `+0x1cd71c4`。发行库未完整符号化，不能把相对地址直接当作已经验证的源码行。

### 源码所支持的机制

对应版本的 [Chromium 91 IoThreadClient 源码](https://chromium.googlesource.com/chromium/src/+/91.0.4472.114/android_webview/browser/aw_contents_io_thread_client.cc) 与 [Chromium 133 IoThreadClient 源码](https://chromium.googlesource.com/chromium/src/+/133.0.6943.137/android_webview/browser/aw_contents_io_thread_client.cc) 均在 updater 初始化时按存在的 main frame 登记 client；`RfhToIoThreadClientMap::Set` 向 frame-tree-node 与 render-frame-host 两张映射复制弱引用，`Erase` 由 `RenderFrameDeleted` 回调驱动。

[Chromium 133 RenderFrameHostImpl](https://chromium.googlesource.com/chromium/src/+/133.0.6943.137/content/browser/renderer_host/render_frame_host_impl.cc) 的销毁路径仅在 renderer frame 曾创建时发送该删除通知。IoThreadClient 的 `WebContentsDestroyed` 不遍历清理上述两张 RFH 映射。

结合每轮恰好残留两个句柄、实际类身份和完成导航后配对归零，强烈支持以下机制：**尚未创建 renderer frame 时已经登记 native 弱引用；过早销毁时缺少对应删除回调，两个映射条目留存。** Java GC 可以清除弱引用指向的对象，但不会替 native 映射调用 `DeleteWeakGlobalRef`，因此 JNI 槽位仍累积。

尚未构建修补后的 Chromium，也没有完整符号化该发行库；最终源码级修复仍需验证。不能直接把“销毁前强制导航”变成产品补丁：它可能增加网络、生命周期和取消行为；简单跳过未 live frame 的登记也需要验证首个请求拦截语义。下一步应审查两张映射的所有权与销毁清理，再在修补版和生产关闭/取消路径验证。

## Binder：系统代理回收决定客户端引用释放

独立进程只调用 Autofill 两项查询，各 1000 轮，不创建 WebView、不加载 JNI agent。先等待 5 秒，再依次对应用和 system_server 发诊断性 SIGUSR1，分别等待 2 秒。每一步核对原始 PID，避免把进程重启误认为释放。

| 观测 | API29 应用 Local Binders | API36 应用 Local Binders |
| --- | ---: | ---: |
| 1000 轮结束 | 2010 | 2016 |
| 空闲 5 秒 | 2010 | 2015 |
| 应用 GC 后 | 2010 | 2015 |
| system_server GC 后 | 10 | 14 |

对应应用 UID 在 system_server 的代理数：API29 `2035 → 2035 → 17`，API36 `2017 → 2017 → 15`（结束、应用 GC、系统 GC）。系统侧此前约 2000 个 `IResultReceiver` 代理在系统 GC 后不再占据列表头部。两进程 PID 均未改变。

这是**干预实验，不是稳定性验收**，结果明确标记 `DIAGNOSTIC_INTERVENTION_NOT_ACCEPTANCE`。ART 的 [Android 10 SignalCatcher](https://android.googlesource.com/platform/art/+/android-10.0.0_r1/runtime/signal_catcher.cc) 与 [Android 16 SignalCatcher](https://android.googlesource.com/platform/art/+/android-16.0.0_r1/runtime/signal_catcher.cc) 确认 SIGUSR1 触发 GC；日志也记录了信号接收。未将强制 GC 加入产品，也未用本结果覆盖 HXA-152 的阈值终止失败。

### 释放链

1. [AutofillManager](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/view/autofill/AutofillManager.java) 为查询构造 `SyncResultReceiver`，它是本地 `IResultReceiver.Stub`；系统服务向该回调返回结果。
2. system_server 的 [BinderProxy](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/os/BinderProxy.java) 使用弱引用代理表及 native allocation 注册清理。native 数据中的 `sp<IBinder>` 在清理前仍持有 Binder 代理。
3. [BinderProxy_destroy / JavaBBinder](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/jni/android_util_Binder.cpp) 删除代理 native 数据并 `flushCommands()`；[BpBinder 析构](https://android.googlesource.com/platform/frameworks/native/+/android-16.0.0_r1/libs/binder/BpBinder.cpp) 更新跟踪计数、释放 handle，经驱动引用释放，使客户端 JavaBBinder 得以析构。
4. JavaBBinder 构造时持有 Java 回调的 `NewGlobalRef`，析构时执行 `DeleteGlobalRef`。因此系统代理仍持有它时，应用自身 GC 无法解除这项 native 强引用。Android 10 对应实现亦核对同一路径。

实验支持**系统代理清理滞后导致客户端 Binder 累积并触及系统阈值**，不是证明这些对象永远无法释放；也不证明历史所有 WebView Binder 失败都唯一来自 Autofill。这里的 Binder Java 强全局引用与上节 WebView 弱全局引用是两条不同链。

## 复跑与证据

前提：专用 API29/36 arm64 模拟器；安装 Android NDK 28.2.13676358、JDK17，设置 `ANDROID_HOME`、`ANDROID_NDK_HOME`、`JAVA_HOME`。Binder 信号实验还要求模拟器可用 `su 0`；脚本验证设备及信号权限，不改变 SELinux。

```bash
./gradlew :feature:browser:assembleDebugAndroidTest spotlessCheck detekt --max-workers=1
bash scripts/diagnostics/build-jni-reference-trace.sh
python3 scripts/diagnostics/run-jni-reference-trace.py emulator-5596 --count 400
python3 scripts/diagnostics/run-jni-reference-trace.py emulator-5596 --count 400 --navigate
python3 scripts/diagnostics/run-jni-reference-trace.py emulator-5598 --count 400
python3 scripts/diagnostics/run-jni-reference-trace.py emulator-5598 --count 400 --navigate
python3 scripts/diagnostics/trace-binder-release.py emulator-5596
python3 scripts/diagnostics/trace-binder-release.py emulator-5598
```

设备序号只对应本次专用实例，复跑必须改成实际专用模拟器。NDK 编译启用 `-Wall -Wextra -Werror`；agent 不链接进 APK。复跑会覆盖同名忽略目录，保留旧证据时先复制输出。

本次原始日志和结果保存在忽略目录 `build/reference-trace/`，四组 `emulator-*-bare-400` / `emulator-*-navigate-400` 含 `logcat.log`、`summary.log`；两组 `emulator-*-binder-release` 含阶段性 meminfo、系统代理快照和结果 JSON。实际安装测试 APK SHA-256 为 `07e8c7984346e565cec7328a1f7f7227b984490e65887b67962774b6bfe90390`；两台实际加载 agent SHA-256 均为 `af4d249ec70d45c39039bbea3a7cd545de0d8f08620a8ed4d24072064a5f6452`。同源构建脚本随后以绝对源路径编译，含调试路径的产物 SHA-256 为 `79d0a64df8e646c008ef8989ae9b3b80715238c45b5cba1492baefaf4cf27b80`；不能把后者冒充实测加载二进制。

### 无效观测单独保留

- agent 初版 JNI 替换表使用栈生命周期，造成附加后 SIGSEGV/SIGBUS；已改为静态进程生命周期，初版日志标记 invalid，不归因于产品。
- 未挂载 WebView 的导航对照会超时；最终对照挂载到 Activity，完成后移除再销毁，两台均完成 400 轮。
- 安装更新 APK 会清空 agent 的 code_cache 副本；早期一次运行只有对照、没有 agent。最终脚本每次安装后复制，并要求附加成功及引用 dump，只有上述最终配对结果计入结论。

目前交付的是可复核的定位证据及测试工具。生产修复、修补版对照、真机和长稳仍未完成。
