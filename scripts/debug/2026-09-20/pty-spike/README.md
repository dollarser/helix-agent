# HXA-197 私有进程 PTY 可行性探针

本目录是 Helix 自写的诊断源码，不参与生产构建。父级 `prepare-termlib-build-spike.py` 将它复制到忽略的 `build/hxa197-termlib-build-spike`。组件编译探针与本 PTY 探针在同一 APK 中，但 PTY 测试不调用 termlib，不证明渲染或 PRoot 已接线。

使用 `posix_openpt`、`fork`、`setsid`、`TIOCSCTTY`、`dup2` 与 `execve` 启动系统 shell。child 的 fork/exec 之间不调用 JVM；Service 非导出、运行于 `:pty`，测试校验 PID 与 instrumentation 主进程不同。失败路径终止并回收直接子进程/进程组，关闭 master。它不是生产会话管理器，不接受任意命令参数，也不暴露 Agent 工具。

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

后续须测试生产选定的 PRoot shell、行编辑/REPL、Ctrl-C、后台子进程、退出后无遗留、主进程/服务死亡、detach/attach、组件输入/渲染及资源压力；不要扩写这段同步探针作为生产 Binder 会话服务。
