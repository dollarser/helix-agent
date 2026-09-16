# HXA-185 设备诊断协议参考

本页只保存可复用命令与诊断方法，不分配 Agent、工作树或设备。交付结论见[完成记录](../../completion-records/HXA-185.md)；命令执行前核实当前类与参数，过时测试数不作为验收目标。

## 主机协议

```sh
python3 scripts/debug/2026-09-10/test_soak_evidence.py
python3 scripts/test-run-browser-autofill-soak-faults.py
python3 scripts/test-run-ev04-mainapp-soak-logic.py
python3 scripts/test-run-ev04-mainapp-soak-fixture-logic.py
./gradlew spotlessApply detekt test lintDebug :app:lintConsumerDebug :app:lintDeveloperDebug --continue --max-workers=1
./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest :feature:browser:assembleDebugAndroidTest --max-workers=1
sh scripts/debug/2026-09-09/run-documentation-gates.sh
```

这些命令来自当时的诊断轮次；当前执行应选择相关回归并重新冻结制品。故障脚本里的模拟进程测试不是实际设备验收。

## 设备诊断

- **Goal短项**：GoalModelReportDeviceTest 全类（包含 modelBlockerRequiresExplicitRepair、manualPauseOverridesCompletedReport、cancellationOverridesCompletedReport）；EV-04 至少覆盖奇数完成和偶数用户暂停，核对当前run的goalMechanism、实际Tool结果、暂停持久字段。Goal 状态与恢复断言按当前 Goal ADR 核对，不复用历史语义。
- **Autofill短项**：BrowserAutofillDeviceTest 与 BrowserOwnerDeviceTest，之后新runner selftest、pilot；on/off 均验证。新 probe 未通过设备验证前不要开24h。失败时同时读 instrumentation.log、device-autofill-failure.json、device-cycles.jsonl、progress、窗口阶段与 webviewupdate-start.log。
- **FD对照**：每个workload使用独立进程和不同runId，固定相同APK/API/AVD内存、iterations=10、slotSeconds=30，其他模拟器负载尽量一致；先 idle，再 storage/notification/activity/combined。记录各臂耗时；超出slot即失败并重新审查配置，不能拼接或悄悄延长。测试方法为 `com.helix.app.diagnostics.DescriptorPhaseProbeDeviceTest#traceFixtureAndIdleDescriptorOwnership`，参数如下（仅由自己的独占runner传递）：

```text
-e helix.fd.phases true
-e helix.fd.workload idle|storage|notification|activity|combined
-e helix.fd.iterations 10
-e helix.fd.slotSeconds 30
-e helix.fd.runId <唯一且只含字母数字下划线或连字符>
```

取回 App external files 中 `fd-phases-<runId>.jsonl` 后执行：

```sh
python3 scripts/debug/2026-09-10/analyze-fd-phases.py <取回的JSONL文件>
```

FD编号/inode可能复用，采样只能定位增长阶段与存留模式，不能据此证明同一对象从未释放。goldfish 类型也不能单独证明纯模拟器问题；有阶段相关证据后才设计更细的创建/关闭路径诊断。这个诊断不替代原资源门禁。物理设备与系统 Binder 无权限采样仍是独立缺口。
