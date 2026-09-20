# HXA-197 私有进程 PTY 可行性探针

本目录是 Helix 自写的诊断源码，不参与生产构建。父级 `prepare-termlib-build-spike.py` 将它复制到忽略的 `build/hxa197-termlib-build-spike`，并复制工程当前的 proot-core、Android 安装器、锁定资产和已包内 loader。组件编译探针与本 PTY 探针在同一 APK 中，但 PTY 测试不调用 termlib，不证明渲染或产品已接线。

使用 `posix_openpt`、`fork`、`setsid`、`TIOCSCTTY`、`dup2` 与 `execve` 启动 shell（早期为系统 shell，当前为锁定 PRoot 内的 Alpine shell）。child 的 fork/exec 之间不调用 JVM；Service 非导出、运行于 `:pty`，测试校验 PID 与 instrumentation 主进程不同。失败路径终止并回收直接子进程/进程组，关闭 master。它不是生产会话管理器，不接受任意命令参数，也不暴露 Agent 工具。

## 执行

先运行父级准备脚本；在工程根通过 `scripts/debug/2026-09-18/with-host-slot.py` 包装以下 Gradle 命令：

```sh
./gradlew --project-dir build/hxa197-termlib-build-spike assembleDebug assembleDebugAndroidTest
```

再通过同一 slot 包装 `python3 scripts/debug/2026-09-18/run-owned-emulator-207.py`，参数如下（每次换未使用输出目录和空闲偶数端口，范围 5554～5682）：

```sh
--avd Helix191_API29 --port 5618 \
--apk build/hxa197-termlib-build-spike/build/outputs/apk/debug/termlib-compatibility-probe-debug.apk \
--test-apk build/hxa197-termlib-build-spike/build/outputs/apk/androidTest/debug/termlib-compatibility-probe-debug-androidTest.apk \
--classes com.helix.spike.termlib.PtyProbeTest \
--runner com.helix.spike.termlib.test/android.test.InstrumentationTestRunner \
--output build/hxa197-pty-api29-v5
```

API36 本次使用 `Helix191_API36`、5620 和 `build/hxa197-pty-api36-v5`。runner 拒绝现有设备并在 finally 关闭自己启动的进程；证据保存 APK 哈希、instrumentation 文本和 closed.json。

## 证据与限制

最终 v5 双 API 各 1/1，通过真实 TTY、UTF-8、跨命令 cwd/env、24×80 到 37×101 resize、Ctrl-D/EOF 退出和 waitpid 回收；相同 APK 哈希见任务记录。

v1/v2 到 EOF 时超时；v3/v4 增加提示符等待后暴露系统 mksh 配置覆盖 PS1，且 `stty -echo` 报错。v5 移除该不成立的设置，启动后显式设置 PS1 并关闭 shell 行编辑模式，等待命令输出后的提示符才发送下一项。这是**规范模式的内核 PTY 探针**，不能据此声称默认交互编辑已验收。原失败证据保留于 build 目录。

## Ctrl-C 补验（v8）

新增 `sleep 30` 前台命令，先通过 `tcgetpgrp` 确认终端前台已离开 shell，才发送字节 `0x03`。随后等待 shell 提示符，检查 `$?=130`、原环境变量仍在、前台命令 PID 已不存在，再执行 EOF 并回收 shell。不是直接向命令 PID 发送 SIGINT 来替代键盘输入。

v6 暴露旧探针把读取次数误作时间预算，逐字符回显可能提前耗尽次数；现已改用 CLOCK_MONOTONIC 的 10 秒期限。v7 显示 `^C` 但命令不结束；v8 在 fork 后、exec 前清空子进程信号屏蔽集合，并将 INT/QUIT/TERM/HUP/CHLD/PIPE/TSTP/TTIN/TTOU 恢复默认，双 API 通过。这一对照支持继承的宿主信号状态是问题来源，但没有分别隔离 mask 与 handler 的贡献。父 JVM 不改动信号状态。

最终 v8 双 API 各 1/1，证据 `build/hxa197-pty-api29-v8`（5626）、`build/hxa197-pty-api36-v8`（5628），模拟器均正常退出。主 APK SHA-256 `e953695056d8e3a5ed067c2a6b6605c3129e6ee66d6f2eec82862b6cc8acfd31`，测试 APK `3d6f7ed4b6f940ca55e7bf8be47271cf61d9fd15dbe40435b3197acb371ae0b7`。

## 锁定 PRoot 补验（proot-v3）

`PtyRuntime` 使用当前生产 RootFsInstaller、RuntimeLock 与 ProotRuntimeInstaller 在探针自己的数据目录安装资产，校验 APK 中可执行 loader 与安装后锁定 loader 的哈希一致。没有 adb 代解包、借用另一个应用的安装状态或 Root 权限。JNI 在 fork 前构造有限长度的路径，复用 `/system/bin/linker64`、PRoot、LD_LIBRARY_PATH、PROOT_LOADER、PROOT_TMP_DIR 启动链，并映射探针私有临时目录和 Workspace；不证明产品真实 Workspace 映射。

首次 PRoot 运行到 Ctrl-C 时失败：直接子 PID 是 tracer，不能用它判断 shell 前台组是否已切换。改为在提示符后读取 `tcgetpgrp` 保存 shell 的真实组，再等待命令的不同前台组。修正后同一组语义检查在 API29/36 各 1/1，通过中文、状态保持、resize、Ctrl-C 状态 130、命令 PID 回收、shell 存活及 EOF 后 tracer 回收。仍使用探针固定提示符/关闭行编辑的设置。

最终证据 `build/hxa197-pty-proot-api29-v3`（5632）、`build/hxa197-pty-proot-api36-v3`（5634），均正常关闭。主 APK SHA-256 `7c47bb6f8bcab0b775a43c0a99ef48455c55dee107493090e39586440041af2f`，测试 APK `362612027dac70b32adf47450f54c3314115b6024efca0a86a38f9cd6dbea0c8`。构建命令同上；安装器首次增加 serialization 依赖时仅在独立探针内生成锁/校验 metadata，生产文件未改变。

## 行编辑与真实 Python REPL（repl-v1）

当前探针已撤掉 `set +o emacs; set +o vi`，保留 Alpine shell 的正常行编辑，只设置可识别提示符。输入含实际 DEL 字节的命令，将 `oX` 编辑为 `ok` 后执行，检查最终输出。随后真正启动包内 `python3 -q`，等待 Python 提示符，分次输入赋值和带中文的打印命令；验证变量保持与结果 42，Ctrl-D 离开 Python 后验证原 shell 的环境变量，再 Ctrl-D 退出 shell。没有以 `python -c` 或 mock REPL 代替。

相同 APK 在 API29/36 各 1/1 通过，同时重新覆盖 resize/Ctrl-C/EOF。证据 `build/hxa197-pty-repl-api29-v1`（5636）、`build/hxa197-pty-repl-api36-v1`（5638），两个 emulator 均正常退出。主 APK SHA-256 `17206529767aba9cf88d57e3ff86378bacb99005039dabd66e4669394d969b2d`，测试 APK `d7da5e6d0f87eb42571af60ccbe6a7a2fbbd2a29955d0481202963706b044281`。

这证明 PTY 字节输入和真实程序交互，不证明 Android IME、可视终端和屏幕重建。

## 后台作业与显式关闭（close-v3）

新增独立用例：在交互 shell 提示符启动 `sleep 30 &`，核验 PID 属于该 PTY 的内核 session，但作业进程组与 tracer 不同；另 fork 一个独立 session 的哨兵，关闭终端后它必须仍活着。它是内核进程隔离检查，不是完整生产 Job owner 交叉验收。

- close-v1 向 tracer 组发 SIGKILL：tracer 消失，但后台作业仍在，测试失败。
- close-v2 向 tracer 组发 SIGTERM：在 3 秒期限内未完成关闭，测试失败。
- close-v3 保留 tracer，枚举当前内核 session 中的作业组，发送信号前重查 PID 的 session/group；跳过 tracer 自身组，终止作业后等待 tracer 退出，再验证后台 PID 不存在。独立哨兵必须仍存活，由测试 finally 另行终止并 waitpid。两个已观察到的失败不删除。

最终 API29/36 各 **2/2**（原交互/EOF 用例和新增关闭用例），证据 `build/hxa197-pty-close-api29-v3`（5644）、`build/hxa197-pty-close-api36-v3`（5646），同 APK、模拟器正常退出。主 APK SHA-256 `77d0da9d77cdedf5e0f0c86af5fc9a8c4245572a7caa86941f70b8f73db9664e`，测试 APK `fcce6b276cfd97a4ad1685d56c1876d6b90a23d30bd49904893ee22012fc996c`。

**生产接线限制：**只杀最初进程组不能证明交互终端无后台执行；异常 tracer/服务死亡必须保留未知执行与占用，直到可信对账。探针的枚举是活 tracer、受控单个后台作业的可行性证据，不是完整生产清理算法；仍需稳定身份/start-time 绑定、扫描/并发派生边界、超时升级、异常死亡及独立真实 owner 回归。主动另建 session 的任意程序未覆盖，不作“任意后代全部回收”承诺。不要扩写这段同步探针作为生产 Binder 会话服务。

## 组件 native 解析与 OSC（renderer-v3）

新增 `RendererProbeTest`：真实创建固定版 termlib emulator，逐字节输入含中文的 OSC 133 命令输出，读取最终内容核对 UTF-8；在有焦点的 Activity 中设置非敏感剪贴板哨兵，输入 OSC 52 写入和查询，等待实际主线程队列处理后确认剪贴板未改变；核对 resize 的公开尺寸。Activity 只是获得系统剪贴板访问所需焦点，不是终端 Compose 界面。

renderer-v2 出现 `NoSuchMethodError`：组件工厂签名包含 Compose `Color`，但 Maven 将 graphics 发布为 runtime-only；缺少编译类型时，探针生成了 boxed `create$default` 调用，与 AAR 中实际 `create-0quPzfM$default` 不符。显式加入同 BOM 的 graphics 编译依赖后，renderer-v3 双 API 通过。独立探针的 Kotlin 编译器由 AGP 提供（锁记录 2.2.10），不将它冒充主工程 Kotlin 2.3.21/Compose 完整迁移验收。

最终 API29/36 各 **3/3**（组件解析 1 + PTY 2），证据 `build/hxa197-renderer-api29-v3`（5650）、`build/hxa197-renderer-api36-v3`（5652），同 APK，模拟器正常退出。主 APK SHA-256 `df60c788a77119269fcb8c6ff5ab1e6730a136a6f41046d6b69884ace2162dbb`，测试 APK `5c6d915b6cf5a687b0a9c0f4c201572f6a92a09f81ac2c8bc8d8b2917bfc6705`。这些通过不证明可视渲染、软键盘、长输出资源压力或释放正确。

## 组件释放是接入前待修项

固定源码 `TerminalEmulator` 无公开 close/dispose；实现以 `TerminalNative(this)` 建立 native 实例，C++ `NewGlobalRef(callbacks)` 持有 emulator，析构时才 DeleteGlobalRef。内部 TerminalNative 有 close/finalize，但公开调用方无法按会话关闭；Compose 的 DisposableEffect 只隐藏 IME。此引用链不能靠公开 API 或默认 GC 实现可核验的会话释放。当前测试仅创建一个 emulator，结束 owned 进程不等于证明会话资源回收；尚未做堆增长量测。

## 固定源码显式释放补丁（close-component-v3）

先执行父级准备脚本，再执行 `prepare-termlib-close-probe.py`。后者校验原 tar SHA，解包到忽略目录，保留 Apache-2.0 与 bundled MIT 许可证，以唯一锚点应用本地修正；不修改发布 AAR、不反射内部字段、不导入源码到生产模块。独立构建改用 `:patched-terminal`，Kotlin/Compose 插件固定 2.3.21，graphics 作为公开 API 依赖。生成锁与校验 metadata 的命令仍仅作用于独立工程。

补丁增加 public `close()`：约定调用方先停止生产者并移除视图，在 callback looper 调用；排空已接收键盘任务，在 damage lock 下关闭图片策略、清除该 Handler 的回调和图片缓存、关闭 native；后续显示快照看到 closed 即返回。重复 close 幂等，关闭后 native 输入明确拒绝。这个约定不承诺调用方继续并发输入时仍能自动建立关闭边界，生产 owner 必须履行停止/卸载顺序。

新增 `TerminalCloseProbeTest` 连续创建并关闭 20 个对象，检查两次 close、关闭后写入拒绝和全部弱引用进入 ReferenceQueue。首次用 get() 轮询弱引用留下 1 个暂时存活对象；改为不读取 referent 的队列统计后全部回收，阈值没有放宽。其余 native 解析与 PTY 3 项同步回归。

最终双 API 各 **4/4**，证据 `build/hxa197-close-component-api29-v3`（5656）、`build/hxa197-close-component-api36-v3`（5658），同 APK、模拟器正常退出。主 APK SHA-256 `c2fc48143ae0a080b49959a1f9f2e2b8096bf9bf943edd133f15dd92e705da27`，测试 APK `5b63b00d8b57c3bf03be9d12cfdb9020b1f3a1dd54aff4aad92c44a6af798f2c`。编译与 API29/36 回收通过，不是长稳内存增长、真实视图卸载或多线程关闭压力验收。

原始 0.2.1 的 public API 缺口仍存在，补丁版本尚未生产采纳；补丁验证不是宣称 Maven AAR 已修复。下一步验证实际 Compose 视图、IME、detach/attach 与关闭顺序，然后完成组件/目录/租期契约及生产接线。
