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

后续须测试行编辑/REPL、后台子进程、退出后无遗留、主进程/服务死亡、detach/attach、组件输入/渲染及资源压力；不要扩写这段同步探针作为生产 Binder 会话服务。
