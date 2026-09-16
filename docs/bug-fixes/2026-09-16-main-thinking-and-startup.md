# Bug Fix: main thinking 连接检查与容器初始化竞态

Status: fixed
Date: 2026-09-16
Related HXA: HXA-193
Affected modules: app

## Problem

基线 main `8e603573`，对照 v0.0.1 `2251242c`；开始时 main 工作树干净。
当时两分支为 main 独有 65、v0.0.1 独有 2 个提交，不需要合并全部历史来修复两个缺陷。

1. `ProviderConnectionCheck` 确实只接受非空白 TextDelta。16-token 探测可能只有 reasoning，正常 Completed(length) 被错判为阶段 3 PROTOCOL。当前普通连接检查与可选五阶段 CapabilityProbe 是两条入口，不能把普通连接通过等同于工具/视觉能力通过。
2. `HelixApplication.appContainer` 原为普通 lazy；MainActivity 与恢复线程竞争首次访问。DefaultAppContainer 包含同步 Room 操作，developer 的订阅 Provider 登记也会访问存储，因此主线程赢得首次访问可能触发 Room 主线程异常。源码确认竞态，不声称每次冷启动必现。
3. main 已有存储权限的宿主分阶段授予/撤销及新进程验证；不移入 v0.0.1 的 assumeTrue 替换，避免把需验证的本地路径变成条件跳过。

## Impact

thinking 后端可能被错误标记连接失败；冷启动容器访问存在主线程 Room 崩溃风险。默认本地门禁未覆盖这两条组合路径，不应从历史绿灯推断不存在生产缺陷。

## Root cause

连接检查把可见答案当成生成成功的唯一证据；普通 lazy 仅保证一次初始化，不保证初始化线程。恢复线程先启动也不能保证它先取得 lazy 锁。

## Fix and invariants

- 普通连接探测接受非空白 TextDelta 或 ReasoningDelta；仍要求完成事件，完成后仅允许 Usage，任何 Error 都失败。空白 reasoning、无输出、缺少 Completed 和完成后继续输出均失败。只记录 CONNECTION_ONLY，不推断回答质量或额外能力。
- 使用 FutureTask 将容器工厂固定到后台线程，所有读取共享一次初始化的结果/原始异常。Application 提前调度；即使 UI 首次访问，也不能在 UI 线程执行工厂。保留 ADR-RUNTIME-001 的主进程判断，QuickJS 和私有 Runtime 不初始化主容器/恢复。
订阅认证短路和现有阶段编号保持原契约。

## Alternatives considered

不整提交 cherry-pick：v0.0.1 的 Application 修改会覆盖 main 的私有 Runtime 排除，且其手写 latch 状态没有必要带入。不使用 allowMainThreadQueries，也不通过增加外部 smoke 的跳过条件掩盖缺陷。

## Regression verification

主机回归覆盖 reasoning-only、尾随 Usage、空白/截断/错误，以及初始化并发单实例、后台线程和失败传播。设备回归使用真实 Room（不允许主线程访问）、主线程首次读取、实际 Activity 重建；本地 HTTP fixture 经过真实 ProviderService、OkHttp 和 SSE decoder，不需外部账号或网络 profile。

实际执行：

- `./scripts/check-all.sh --all` exit 0：source、Spotless、Detekt、全模块主机测试、Debug/Release lint与构建、35份依赖锁及APK/Runtime边界检查均通过。日志 `build/provider-startup-check-all.log`。
- `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest detekt --console=plain` 通过。两个 flavor 的 ProviderConnectionCheckTest 各 9/9，BackgroundInitializationTest 各 2/2，全量 app 主机单测同时通过。日志 `build/provider-startup-host.log`。
- `python3 scripts/debug/2026-09-16/run-pre-hxa-regressions.py --class com.helix.app.StartupAndThinkingDeviceTest` 通过：API29/36 × consumer/developer 各 2/2，共 8 次用例通过，0 失败、0 跳过。每个 API 使用新建独占模拟器进程，finally 关闭，最终 adb 列表为空。证据 `build/pre-hxa-device-20260916-104320/summary.json` 与同目录原始 instrumentation/logcat。
- 本轮不重跑全部设备套件，不声称真实服务或长稳验收。

## Residual risk

保留同步 getter：首次读取仍可能等待初始化，此修复不是异步启动界面或启动耗时优化，也不宣称消除了所有 ANR 风险。

真实 Qwen/SGLang 服务未在本轮请求中提供或运行；历史 smoke 结论不替代此次真实服务验收。外部 profile 保留 opt-in，已知协议缺陷进入默认本地回归。

## Related records

- [main 集成记录](../evidence/development/harness-main-integration-2026-09-16.md)
- [Runtime 进程决定](../adr/runtime/001-execution-domains.md)
- [本地基线与存储分阶段验证](2026-09-16-pre-hxa-baseline-regressions.md)
