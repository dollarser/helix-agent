# HXA-197 有界字节缓冲与门禁修复

日期：2026-09-20。范围是 proot-core 的 PTY 传输基础及独立探针维护；生产会话服务、Binder、终端页面尚未接线，不关闭 197。

## 实现

- `PtyOutputBuffer`：固定 256 KiB 环形内存，单次追加/读取最多 8 KiB；原始 UTF-8/ANSI 字节不提前解码。游标绑定 session/generation，拒绝外来、畸形和未来游标。慢消费者收到近期内容与显式缺口；重读未被覆盖的数据不改变字节。UI 后续需在缺口处重建解析器并提示，不能隐瞒丢失。EOF 只代表输出排空，不能结算进程或执行占用。
- `PtyInputBuffer`：同时限制 32 KiB 与 64 块，每块最多 8 KiB。整块复制接收或拒绝，不截断粘贴；单 writer 在队列外另持有至多一块。关闭返回被丢弃的待写字节数并拒绝新输入，不声称已停止在途 native 写入或 shell。
- 两类均不持有授权、不创建线程、不访问文件，不替代 session owner、持久身份或 Runtime 结果对账。后续服务负责校验当前写连接、处理部分写入和通信不确定性，禁止自动重放。

`PtyBuffersTest` **7/7**：跨页多字节/ANSI、环绕、慢读缺口、错误与过期游标、数组拷贝、EOF 后拒写、并发生产/消费、两种输入拥塞及关闭丢弃。并发测试按绝对偏移核对每个字节，允许显式丢失，不允许错位或静默缺口。

## 完整门禁暴露的探针问题

此前独立探针构建和 source gate 没有覆盖主工程 detekt。此次完整门禁发现平铺目录与 Kotlin 包不匹配、测试长行/返回数量问题，以及回收诊断中的显式 GC 告警。

将探针迁至标准 `src/main`、`src/androidTest` 包目录并更新复制脚本，使其接受原有格式与静态检查；重写返回结构并拆分长行。回收测试必须主动触发 GC 才能检查 native callback 强引用释放，沿用项目诊断测试惯例，只在该测试函数标记 `ExplicitGarbageCollectionCall`，保留 20 个对象全部回收的断言。没有修改全局 detekt 配置、移除测试或缩小测试数量。

## 验证

所有 Gradle 和设备命令均经 `python3 scripts/debug/2026-09-18/with-host-slot.py --` 独占执行。

| 命令/场景 | 实际结果 | 证据 |
| --- | --- | --- |
| `./gradlew spotlessApply`，之后 `./scripts/check-all.sh --all` 与 `./gradlew :app:assembleDeveloperDebugAndroidTest` | exit 0；源码、格式、detekt、全主机测试/lint/debug/release 构建、锁与变体/Runtime 边界通过 | `build/hxa197-buffers-format-v4.log`、`build/hxa197-buffers-all-v4.log` |
| `python3 scripts/verify-integrated-runtimes.py --avd Helix191_API29 --port 5560 --output build/hxa197-buffers-runtime-api29-v1` | 37/37，owned emulator exit 0 | 同名目录的 instrumentation、制品与退出记录 |
| `python3 scripts/verify-integrated-runtimes.py --avd Helix191_API36 --port 5562 --output build/hxa197-buffers-runtime-api36-v1` | 37/37，owned emulator exit 0 | 同名目录 |
| 重新执行两个 termlib 准备脚本与独立工程 `assembleDebug assembleDebugAndroidTest` | exit 0 | `build/hxa197-buffers-spike-build-v1.log` |
| 独立 probe 的视图、释放、解析、PTY 四类（见[复现命令](../../../scripts/debug/2026-09-20/pty-spike/README.md)） | API29 5/5（5564）、API36 5/5（5566），owned emulator exit 0 | `build/hxa197-buffers-probe-api29-v1`、`build/hxa197-buffers-probe-api36-v1` |

生产 Runtime 两 API 的 APK SHA-256 相同：主 APK `ef11d2fca9c343570059d35961519a96cf9b7fb86078b84689c3801c4ffb55d6`，测试 APK `e393cad475242df316d8a2e5fd7e66a5996dfd9821684786cbb67b86fe2e8431`。独立探针两 API 同制品：主 APK `c9b24a7bf2cb63e245e4b1b7fe12f474ca1d6b2f5b1c289de020e16e16a66c0c`，测试 APK `bedb1d08dbcc4bf3d1f78fc01bfd89216288463d617c734910c94ec8170f79f6`。

完整门禁前的失败保留在 `build/hxa197-buffers-all-v1.log`、`build/hxa197-buffers-all-v2.log` 与 `build/hxa197-buffers-analysis-v3.log`；最终以 v4 为准。Runtime 74 项是现有产品回归，探针 10 项是隔离组件/PTY 检查；它们不代替新增缓冲的生产接线、实际终端长输出/粘贴压力、Activity 重建、租期/目录及关闭/进程死亡验收。真实账号、OEM/Doze 和 16 KiB 设备边界不扩大。
