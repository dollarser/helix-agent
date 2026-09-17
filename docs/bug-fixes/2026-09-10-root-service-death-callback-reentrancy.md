# Bug Fix: RootService 死亡回调重入连接清理

Status: fixed
Date: 2026-09-10
Related HXA: HXA-188
Affected modules: tools/root

## Problem

已授权 RootService 死亡时，Helix 客户端可能在主线程抛出 ArrayIndexOutOfBoundsException，而不是稳定进入 LOST。真机 Root 高层工具测试在前一个授权/死亡测试之后复现，堆栈指向 libsu RootServiceManager.dropConnections 与 ArrayMap.removeAt。

## Impact

Root 后端异常退出可能连带导致主 App 崩溃、打断当前交互；故障不要求执行任意 Root 命令，也不能通过仅验证 su 授权成功排除。

## Root cause

libsu 6.0.0 的连接清理在遍历 ArrayMap 时先通知断开，再由迭代器删除条目。默认回调在主线程可能同步执行；Helix 的 onServiceDisconnected → notifyLost → clearConnection 会立即 RootService.unbind，重入同一连接表，导致随后迭代删除越界。

旧的全局 Binder deathRecipient 还可能从 Binder 线程调用主线程专用 unbind；死亡回调没有绑定具体连接身份，迟到通知可能影响后来建立的连接。

## Fix and invariants

使用现有 RootService.bind 的 Executor 重载，始终通过 Handler.post 排队回调，而非在 libsu 的迭代调用栈中清理。所有死亡/IPC异常清理也进入主线程；每个 deathRecipient 捕获对应 ServiceConnection 与 Binder，执行前核对身份。IPC执行观察的 Binder 引用通过 volatile 发布。

长期不变式：

- 不在 libsu 连接遍历栈中同步 unbind；连接字段和清理归主线程所有。
- 旧连接/Binder 的死亡通知不得关闭新的连接。
- 丢失后清除 Binder/PID、进入 LOST，不自动重绑、不重放工具；只有显式新请求才可重建。
- 不通过吞掉 ArrayIndexOutOfBoundsException、放宽断言或绕开服务死亡来使测试通过。

## Alternatives considered

升级/替换 libsu 会扩大依赖与验收范围，当前已有 Executor 接口可解决重入，因此不采用。仅捕获异常会掩盖连接表损坏；仅延迟 unbind 不能同时修复 Binder线程访问和旧死亡通知身份问题。固定 sleep 无法保证回调顺序，不采用。

## Regression verification

[HXA-188](../completion-records/HXA-188.md) 保留修复前实际崩溃与修复后制品/原文。API35 Root 模块6通过、1rootless条件跳过；LibsuRootAccessDeviceTest.d_repeatedServiceDeathAllowsOnlyExplicitRebind 连续3轮真实杀死自身 RootService 后确认 LOST、无PID、无自动重连，再显式连接。RootHighLevelToolsDeviceTest 同时验证有界读取、越界拒绝和服务死亡后工具失败；Root JVM21通过。

复跑：`python3 scripts/debug/2026-09-10/run-physical-library-regression.py --serial "$PHONE_SERIAL" --output "$OUTPUT" --modules tools/root --instrument-arg hxa094ExpectedRoot granted --instrument-arg hxa095ExpectedRoot granted`。脚本拒绝模拟器与已有测试包；必须使用明确授权的 Root 真机，Android SDK 由 ANDROID_HOME 指定。

## Residual risk

本轮是API35/MagiskSU30.7的短回归，不代表所有Root管理器、长期后台、Root控制台写操作或管理器撤销未来授权时立即杀死存活进程的验收。Root库升级需重新验证回调时序。应用后台主动关闭Root授权的既有语义保持不变。

## Related records

- [原HXA-094任务](../completion-records/HXA-094.md)
- [ADR-RUNTIME-004](../adr/runtime/004-root-service.md)
- [libsu RootServiceManager](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java)
- [libsu RootService Executor 接口](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/ipc/RootService.java)
