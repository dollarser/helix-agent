# ADR-0015: 采用 Zipline 1.27.0 作为 E1 QuickJS 执行底座

Status: accepted
Date: 2026-09-03
HXA: HXA-050
Deciders: 项目所有者（经 roadmap HXA-050「按 ADR 约定产出决定与证据」授权；spike 证据 2026-09-03）
Supersedes: none
Superseded by: none

## Context

Helix 的 E1 本地代码执行层（[本地代码执行架构](../architecture/local-code-execution.md) §2）需要一个可嵌入 Android 的 QuickJS 引擎封装，提供：直接执行（`evaluate`）、内存上限（`memoryLimit`）、可中断执行（`InterruptHandler`）、JS 栈上限（`maxStackSize`），以及 Android 15+ 16 KiB page size 兼容。Zipline 自身不提供沙箱或进程隔离，因此必须叠加 Android `isolatedProcess` 隔离 Service（HXA-051）且不给 JavaScript 注册任何有权限的 Host Bridge。

HXA-050 spike 在 API 29 与 API 36 两台 arm64-v8a 模拟器（设备实测 page size 均为 4 KiB，`getconf PAGESIZE`=4096）上逐项验证，以下为本 ADR 依赖的已验证事实（逐项命令与异常类型见 [完成记录 HXA-050](../completion-records/HXA-050.md)）：

- `app.cash.zipline.QuickJs`（`app.cash.zipline:zipline:1.27.0` 的 Android 变体 `zipline-android`）是公共 API：`create()`、`evaluate(script, fileName)`、`memoryLimit`（默认 -1）、`interruptHandler`、`maxStackSize`（默认 524288 = 512 KiB）、`memoryUsage`、`close()`，无需依赖 internal 包。
- evaluate：数值返回 `java.lang.Integer`、字符串/Unicode（含 emoji 与 CJK）往返正确、大于 1 MiB（2 MiB）字符串往返正确、`JSON.stringify` 往返正确、`QuickJs.version = "2021-03-27"`。
- memoryLimit：2 MiB 与默认 64 MiB 限额下超大分配均抛出 JS 级 `app.cash.zipline.QuickJsException`，进程存活、同一实例可继续 evaluate。错误消息通常为 `out of memory`；API 29 + 64 MiB 限额观察到同一 OOM 以**空消息**抛出（堆耗尽时连 JS Error 对象都无法分配），两种形式均为 JS 级错误而非进程崩溃。
- InterruptHandler：按 poll 次数（>1000 次返回 true）与 monotonic 截止时间（300 ms）两种触发方式都中断 `while (true) {}`，抛 `QuickJsException`（消息含 `interrupted`），实例可继续 evaluate 并可正常关闭。
- `eval`/`Function`/constructor：Zipline **不删除**全局 `eval` 与 `Function`（`typeof eval` / `typeof Function` 均为 `"function"`，存根形式存在），但所有动态编译调用路径——`eval(...)`、`new Function(...)`、无 `new` 的 `Function(...)`、`Object.constructor.constructor(...)`、`String.constructor.constructor(...)`、`[].constructor.constructor(...)`——均抛 `QuickJsException`，消息 `eval is not supported`。即动态编译已被引擎层封堵，但封堵形式是“存根即抛错”，不是“全局不可见”。
- 栈：`maxStackSize` 是 JS 层上限。默认 512 KiB 下深度递归以 `QuickJsException`（`stack overflow`）结束、实例存活；在 16 MiB native 栈线程上把 `maxStackSize` 设为 8 MiB，同样以 JS 级 `stack overflow` 结束而非进程崩溃——大于 6 MiB 的调用线程栈可用。关键约束：**`QuickJs` 实例的栈基线在创建线程上捕获**，跨线程 evaluate（创建线程 ≠ 执行线程）立即以 `stack overflow` 失败；HXA-051 必须在专用执行线程上创建实例。
- 隔离：库 manifest 声明 `isolatedProcess=true`、`exported=false` 的最小 Service，经 `Context.bindIsolatedService`（API 29+ 框架签名；本模块 androidTest classpath 无 androidx.core，不使用 `ContextCompat`）以唯一 instance name 绑定后：service PID/UID 与调用方不同（API 36 观察到隔离 UID 99000，调用方 UID 10252），进程名形如 `<pkg>:<serviceFQCN>:<instanceName>`；`unbindService` 后系统回收该实例进程（PID 消失），以新 instance name 重绑得到新 PID——唯一实例回收成立。
- 16 KiB page：本环境无 16 KiB page 设备（两台模拟器均 4 KiB）。替代证据：`zipline-android-1.27.0.aar` 内四个 ABI 的 `libquickjs.so` ELF PT_LOAD 段对齐均为 0x4000（16 KiB）（JVM 测试 `QuickJsNativeLibraryElfTest` 锁定四 ABI；androidTest 在设备进程内从 APK 读取本 ABI 的 `.so` 复核同一性质），且 4 KiB 设备上该 `.so` 正常 `dlopen` 并执行全部能力测试。x86_64 无设备运行证据（本环境为 arm64 Mac），仅 AAR 内存在性与 ELF 对齐证据。

## Decision

采用 Cash App Zipline `1.27.0`（Apache-2.0；内嵌 QuickJS 为 MIT）的 `app.cash.zipline.QuickJs` 作为 Helix E1 的 QuickJS 执行底座，版本锁定在 `gradle/libs.versions.toml`（`zipline = "1.27.0"`），不升级版本。

边界与约束：

- Zipline 只负责引擎与执行控制（evaluate/memoryLimit/InterruptHandler/maxStackSize）。安全边界由 Android isolated UID 进程（HXA-051）+ 无权限 Host Bridge（架构文档 §2.1）+ 输入封装（HXA-052）共同提供；Zipline 本身不是不可信代码的安全边界。
- 每个 execution 使用全新 `QuickJs` 实例，**在专用执行线程上创建**（栈基线线程约束），执行前设置 `memoryLimit` 与 `InterruptHandler`，执行线程 native 栈大于 6 MiB（spike 验证值：16 MiB），执行结束必须 `close()`（同一线程）。
- 动态编译面已由引擎封堵（所有调用路径抛 `eval is not supported`）；HXA-052 的输入封装（严格模式 IIFE/常量输入）仍按架构文档 §3 实施，并用攻击测试锁定上述封堵在锁定版本上保持有效。本 ADR 不声称 Zipline 单独完成全部代码注入面收敛。

## Alternatives considered

- **自行维护 QuickJS JNI 封装**：可完全控制引擎构建（宏、对齐、错误消息），但需自建四 ABI 构建链、16 KiB 对齐维护、内存/中断/栈语义实现与回归测试；Zipline 已提供全部所需公共 API 且 AAR 四 ABI 对齐达标。除非 Zipline 停止维护或许可变化，不选。
- **Duktape 等其他嵌入式 JS 引擎**：体积更小，但缺少 memoryLimit/InterruptHandler/maxStackSize 这类执行控制公共 API，需自行实现；生态与审计强度低于 QuickJS/Zipline。不选。
- **QuickJS-NG / V8**：与 E1“轻量文本/数据计算”定位不符（体积、启动成本、许可与 ABI 负担更重），且当前 Zipline 已满足全部 spike 能力。不选。

## Consequences

收益：

- 四个 ABI（arm64-v8a/armeabi-v7a/x86/x86_64）的 `libquickjs.so` 随 AAR 分发，PT_LOAD 对齐 16 KiB，Android 15+ 16 KiB page 设备可直接 `dlopen`（真机运行验证归 HXA-054/环境补齐）。
- evaluate/memoryLimit/InterruptHandler/maxStackSize 为稳定公共 API，HXA-051/052 的执行控制协议可直接落在引擎能力上。
- 动态编译（eval/Function/constructor 全路径）在引擎层即被封堵，HXA-052 的攻击面收敛工作量下降。

代价与风险：

- 线程约束是硬边界：实例必须在使用它的执行线程上创建与关闭，HXA-051 的执行线程设计必须把 `QuickJs.create()` 放在该线程上（spike 已回归锁定跨线程 evaluate 以 `stack overflow` 失败）。
- 错误语义随引擎版本演进可能变化（异常类名 `QuickJsException`、消息 `out of memory`/`interrupted`/`stack overflow`/`eval is not supported`，以及 64 MiB 限额下 OOM 空消息的边界行为）；版本锁定 + 完成记录的异常证据用于回归对照，升级 Zipline 必须重跑 HXA-050 矩阵。
- API 34 与 x86_64 设备、16 KiB page 真机的运行验证在本环境不可得（见 Verification 的 required before acceptance），由 HXA-051/054 与后续设备矩阵继续覆盖。
- QuickJS（MIT）、Zipline（Apache-2.0）许可证义务按架构文档 §9 维护（`THIRD_PARTY_NOTICES.md` 归后续发布任务）。

## Verification

已执行（HXA-050 spike；逐项命令、exit code、逐测试证据见 [完成记录 HXA-050](../completion-records/HXA-050.md)）：

- `./gradlew :runtime:quickjs:testDebugUnitTest :runtime:quickjs:assembleDebug`（JVM ELF 四 ABI 对齐测试 + 构建）exit 0。
- `./gradlew :runtime:quickjs:connectedDebugAndroidTest`（API 29 arm64-v8a 与 API 36 arm64-v8a 模拟器各 25 项 androidTest 全部通过）exit 0。
- `zipline-android` AAR 四 ABI `libquickjs.so` PT_LOAD 对齐 0x4000（JVM 测试锁定；设备侧对本 ABI 复核）。
- 隔离 Service：唯一 instance 绑定、PID/UID 隔离（隔离 UID 99000）、进程名含 instance name、unbind 回收、重绑新 PID，两台设备均通过。

Required before acceptance（spike 阶段环境限制，不阻塞本决定；归 HXA-051/054 与后续设备矩阵继续覆盖）：

- API 34 设备验证（本环境无 API 34 系统镜像；已覆盖 minSdk API 29 与目标上界 API 36）。
- x86_64 设备运行验证（本环境为 arm64 Mac，无 x86_64 模拟器镜像；已有 AAR 内 x86_64 `.so` 存在性与 ELF 对齐证据，未声称在 x86_64 设备上运行）。
- 16 KiB page 真机运行验证（本环境设备均 4 KiB page；以 ELF PT_LOAD 对齐 16 KiB + 4 KiB 设备全能力运行为替代证据，未把 4 KiB 设备当作 16 KiB 验证）。

## Reconsider when

- Zipline 停止维护，或 1.27.x 出现无法在锁定版本内修复的引擎缺陷（OOM/中断/栈/线程语义回退）。
- 任一关键能力在后续 API/ABI 矩阵（API 34、x86_64 设备、16 KiB page 真机）上失败。
- 后续版本重新启用动态编译或改变 `eval is not supported` 封堵形式（HXA-052 攻击测试将首先发现）。
- `QuickJs` 线程约束（创建线程绑定）或隔离 Service 回收行为在目标 API 上变化。
- Zipline/QuickJS 许可证或上游合规状态变化。

## References

- [本地代码执行架构 §2（QuickJS 技术选型与进程模型）](../architecture/local-code-execution.md)
- [完成记录 HXA-050](../completion-records/HXA-050.md)
- [ADR 约定](README.md)
- [Cash App Zipline](https://github.com/cashapp/zipline)

编号说明：本 ADR 使用 0015。0014 已被主 worktree 进行中的 ADR 占用（本分支基线 057fe68 时尚未合入），为避免合入时编号冲突跳过 0014。
