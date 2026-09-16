# Bug Fix: PRoot manifest 管道读端确定性释放

Status: fixed
Date: 2026-09-10
Related HXA: HXA-189
Affected modules: runtime/proot-ipc

## Problem

PfdManifestChannel.readFromStart 的接口承诺消费并关闭 PFD，但旧实现仅创建 AutoCloseInputStream，未执行 close/use。AutoClose 名称表示关闭流时关闭 PFD，不表示读到 EOF 自动释放。

## Impact

重复跨 APK manifest 读取会延迟释放文件描述符；成功、输入超限及读异常都可触发，长时间运行可能耗尽进程 FD。该源码缺陷不能直接用于解释另一个 WebView/JNI/Binder 长稳的全部增长曲线。

## Root cause

流资源没有明确词法作用域。CLI 同类通道已有 use，PRoot 未遵循自己的所有权契约。写端使用裸 FileOutputStream 和 PFD 两层关闭，所有权表达也不统一。

## Fix and invariants

读端在 AutoCloseInputStream.use 中执行有界读取，无论正常退出、协议超限或 IOException 都关闭；写端使用 AutoCloseOutputStream。维持原 manifest 上限、空输入、稳定异常映射及调用方拥有另一端的契约，不新增 transport 模块，不改变 IPC schema、签名或审批语义。

## Alternatives considered

依赖 GC/finalizer 不满足接口的确定性释放；只在 return 前 close 会漏异常路径。立即提取三个 Runtime 的共享框架会引入不必要的协议/生命周期耦合，本轮不采用。

## Regression verification

新增 PfdManifestChannelDeviceTest：正常读写、空流、超限写入、超限输入、无效读端五项；断言发生在测试 finally 清理之前，不能由夹具补关掩盖产品泄漏。设备 APK 已编译，尚未执行；独占模拟器执行条件见 [审查复核交接](../evidence/development/improvement-review-2026-09-10-followup.md)。主机 PRoot IPC 40项及全量门禁通过，详见 [HXA-189](../completion-records/HXA-189.md)。

## Residual risk

Android PFD 关闭行为仍需 API29/36 设备回归；本轮源码修复和 JVM 通过不能冒充设备泄漏曲线通过，也不关闭 EV-02、OEM 系统引用问题或 24h 验收。

## Related records

- [HXA-083 原 IPC 任务](../completion-records/HXA-083.md)
- [原审查](../evidence/development/improvement-review-2026-09-10.md)
- [复核与交接](../evidence/development/improvement-review-2026-09-10-followup.md)
