# HXA-197 会话记录与 PRoot 关闭路径

日期：2026-09-20。范围为终端持久记录、原生关闭请求及设备检查；产品会话 owner、Binder、租期计时、Workspace 入口和终端 UI 尚未接线，不关闭 197。

## 持久记录

`PtySessionOrigin` 保存手动 Session/generation/execution、Runtime generation、原目录和时间/开机观察，不伪造 Agent Turn/ToolCall，也不授予目录权限。`PtySessionRecord` 把执行阶段、停止原因、停止证明、退出码和宿主已对账标志分开。

- STARTING 必须在 fork 前落盘；恢复遇到 STARTING，即使没有 PID，也按可能已启动处理。`runtimeLost` 变为 UNKNOWN，不自动启动 shell、不自动证明停止。
- PID 与 start ticks 一起记录。取消先到、fork 随后成功时，补记 PID 保留 CLOSING 与原取消原因，不回退 RUNNING；原目录、身份和已保存 PID 身份不能被覆盖。
- NEVER_STARTED 只供仍掌握启动事实的 live launcher 使用；进程树退出证明不能由 EOF、leader 退出或信号发送成功替代。
- 明确设备重启时保存严格增加的开机次数，UNKNOWN 继续保留，不捏造退出码。缺失、相同或倒退的开机观察不产生停止证明。没有停止证明不能确认结算；停止事实一旦保存，只能追加已对账标志。

`PtySessionStore` 是 Runtime 进程内唯一写入面，跨 wrapper 共用锁并执行完整记录 CAS。采用版本化二进制记录、临时文件/fsync/原子替换；不提供非原子 copy 回退。最多 128 个记录、单个最多 32 KiB；读入与解码均有界，未知版本、非法布尔/枚举、截断、尾随内容、错误身份或损坏目录抛错，不按“无记录/空闲”处理。容量耗尽不淘汰未知或未对账记录；只能删除完全匹配且已对账的记录。

记录中不保存输入/终端输出、凭据或 shell 内存。跨进程只有 Runtime 写入，应用占用仍由既有 `ExecutionOwnership` 独立持有；本存储本身不释放它。记录里的 deadline 只是已有启动契约的字段，**不是已实现租期计时器或已选择产品默认租期**。

主机 `PtySessionStoreTest` **7/7**：未保存 PID 的恢复、严格重启证明、取消/启动竞争、旧 CAS 和错误身份、损坏记录、容量及并发 wrapper。实际 Android 的 failed-exec 旅程在 fork 前落盘、保存真实 PID/start ticks，观察 127/EOF 后记录结果，重新打开校验、拒绝旧状态覆盖、确认并移除。这里的进程树退出证明只针对固定的不存在 executable，未运行用户程序或派生子进程；不把文件重开称为实际进程死亡验收。

## 复用锁定 PRoot 的关闭机制

[Termux 包构建](https://github.com/termux/termux-packages/blob/master/packages/proot/build.sh)当前版本为 `5.1.107.92`，对应上游 tag `v5.1.107.92`，解引用 commit `7266fb3e8516535682f5a9c8f3a7e70f6506eddb`，与包内锁定版本一致。APK 内二进制包含 `--kill-on-exit` 选项；实际行为另由下列设备证据验证，不仅凭字符串推定。

该固定源码的 [event.c](https://github.com/termux/proot/blob/7266fb3e8516535682f5a9c8f3a7e70f6506eddb/src/tracee/event.c) 为 SIGQUIT 安装清理 tracee 的处理路径，并继续通过事件循环退出；[tracee.c](https://github.com/termux/proot/blob/7266fb3e8516535682f5a9c8f3a7e70f6506eddb/src/tracee/tracee.c) 实现所跟踪进程的清理及 kill-on-exit 行为。源码仅用于阅读，不复制进 Helix。

- 设备启动参数加入 `--kill-on-exit`，验证 shell 正常退出时结束其后台 tracee。
- 新增生产内部 `requestProotExit()`，向原未回收的 tracer PID 发送 SIGQUIT。仍由现有同步包装避免使用已回收 PID，**返回仅代表请求已发出**，不能据此更新停止证明。
- 新测试同时创建普通后台 `sleep`、经 fork/setsid 进入其他 session 的 Python 子进程，以及与 PRoot 无关的系统进程哨兵。正常 Ctrl-D 与前台命令运行时的 SIGQUIT 分别验证 tracer 正常退出、所建作业 PID 消失、哨兵存活。
- 初始 API29 定向测试 6/6，两个关闭场景 tracer exit=0，后台和 detached PID 消失，哨兵保留。该 exit=0 是 tracer 退出状态，不表示被中止的用户命令成功。

这替代此前“扫描 session 的各进程组”探针作为后续关闭实现方向；不把杀 tracer 初始组当成完整关闭。尚需在生产 owner 中处理启动早期尚未就绪、I/O 故障、tracer 被强杀、租期到期和真正进程死亡。缺乏停止证明时保留 UNKNOWN/占用；不声称 PRoot 是隔离边界或任意恶意共享 UID 程序均可完整回收。

## 验证记录

所有 Gradle/设备命令经 `python3 scripts/debug/2026-09-18/with-host-slot.py --` 执行。现有 owned runner 拒绝借用设备，每次新目录与新进程，finally 只关闭自有模拟器。

初始记录主机门禁 `build/hxa197-session-journal-all-v1.log` exit 0。关闭探针第一次构建暴露 Android `Os` 未提供 `getpgid` 以及探针类函数数/长行检查；改用该诊断已持有 PID 的 `/proc/stat` 读取进程组/session，提取共有诊断清理/等待函数并拆行，没有放宽门禁。第二次 `detekt :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest` 通过，日志 `build/hxa197-session-close-build-v2.log`。

定向命令：`python3 scripts/debug/2026-09-09/run-owned-emulator.py --avd Helix191_API29 --port 5582 --output build/hxa197-session-close-api29-v1 --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner --classes com.helix.app.proot.ProotPtyNativeDeviceTest --timeout 300`；6/6、owned exit 0。最终版本另补前台 PID 消失和回收后拒绝 SIGQUIT 的断言，以后续完整回归为最终证据。

最终门禁（不与前版相加）：

| 命令 | 实际结果 | 证据 |
| --- | --- | --- |
| `./gradlew spotlessApply`，随后 `./scripts/check-all.sh --all` 与 `./gradlew :app:assembleDeveloperDebugAndroidTest` | exit 0；格式/静态分析、主机测试、lint、双 flavor debug/release 构建、锁、变体/Runtime 边界通过 | `build/hxa197-session-close-format-v3.log`、`build/hxa197-session-close-all-v2.log` |
| `python3 scripts/verify-integrated-runtime-apks.py --build-type release` | exit 0；诊断服务未进入 release/consumer | `build/hxa197-session-close-release-v2.log` |
| `python3 scripts/verify-integrated-runtimes.py --avd Helix191_API29 --port 5584 --output build/hxa197-session-runtime-api29-v2` | 43/43；owned emulator exit 0 | 同名目录 |
| `python3 scripts/verify-integrated-runtimes.py --avd Helix191_API36 --port 5586 --output build/hxa197-session-runtime-api36-v2` | 43/43；owned emulator exit 0 | 同名目录 |

两 API 最终主 APK SHA-256 均为 `05b3bfdbc0fd3f53b5c60985f838dc18ad19b1e02698e6f5a7a9381ce4bbec71`，测试 APK 均为 `d74278c13f4d9c1d77cdcbe532b332d6d17ef70ee9a0f04bf2b18eef954d3fa0`。合计 86/86；两种关闭场景在两 API 的 tracer exit 均为 0，前台命令（主动关闭场景）、后台与 detached PID 消失，哨兵仍存活。原生 PTY 12 项包含于这 86 项，不重复计数。

新增关闭两项后，公共 Runtime suite 为每 API 43 项，其中原生 PTY 6 项。arm64-v8a/x86_64 release `libproot_native.so` 全部 LOAD 段保持 `0x4000` 对齐，记录 `build/hxa197-session-<abi>-elf-v2.txt`；不是 16 KiB 设备运行证据。完整产品终端、真实账号、OEM/Doze/长稳仍未因此验收。
