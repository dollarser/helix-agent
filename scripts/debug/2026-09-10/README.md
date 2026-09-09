# 2026-09-10 调试与测试脚本

HXA-182 跨日继续。所有权约束和制品冻结复用前一日的 `run-owned-emulator.py`。

- `run-responsibility-recovery-regression.sh`：双 API/双 flavor 的文件、聊天职责及跨进程恢复组合；发布阶段在测试 backend 内杀死测试 App，检查持久 PID 后启动新进程通过原生 UI 恢复。预期死亡只允许在 setup；正式用例必须非空且全通过。
- `run-transfer-recovery-device.sh`：仅运行上述跨进程恢复专项。
- `extract-file-trash.py`、`add-process-recovery-runner.py`、`refine-unresolved-publication.py`、`use-real-process-death.py`：本轮一次性编辑的来源记录，不作为可重复修复器。

输出保持在忽略的 build 目录；仅测试合成文件，不连接用户手机，不借用其他模拟器。

## HXA-183 职责拆分

- `run-hxa183-device.sh variant api port output`：独占 API29/36 上运行聊天、草稿、审批、Provider、文件与 Goal/压缩/后台回归；随后在同一独占实例执行 PRoot 验收，最后关闭实例。
- `run-hxa183-proot.py serial output`：只由上述运行器调用；验证 owner，复用 `accept-hxa-086-lifecycle.sh` 的真实跨 APK 生命周期阶段，再运行 companion 和主 App 的归档/ACK/Tool 回归；冻结 APK SHA 并检查未漂移。脚本必须显式接收 serial，不自动选择 adb 中的设备。
- `run-hxa183-runtime-device.sh api port output`：app 回归已通过后，仅在新建独占实例补跑 PRoot。
- `restore-locked-proot-assets.py source-checkout`：只读复用相同 lock 的被忽略构建资产，验证 RootFS 锁定哈希与 loader 一致性；不修改源工作树、lock 或版本。
- `verify-hxa183-evidence.py app29 app36 runtime29 runtime36`：分别核对成功阶段、当前 APK 哈希和关闭记录；`close-hxa183.py` 通过这些检查后才生成完成记录与状态。
- `split-responsibilities.py`、`split-runtime-and-chat.py`、`refine-extracted-owners.py`、`wire-draft-owner.py`、`refine-split-file-layout.py`、`clean-extracted-imports.py`：本轮一次性编辑来源记录，只适用于编辑当时的源码，不要在最终树重复执行。
- `inventory-large-production-files.py`、`write-large-class-audit.py`：拆分前审查的清单与文档生成来源；人工分类不能随清单重跑自动视为当前结论。

`run-owned-emulator.py` 的 `--after-script` 在主 suite 成功后、释放独占实例前运行后续验收；后续异常同样进入关闭流程，不能把前半段通过当成整轮通过。

## HXA-184 大类 B/C 整理

- `split-hxa184-declarations.py`、`extract-hxa184-support.py`、`extract-hxa184-execution.py`、`extract-hxa184-boundaries.py`、`refine-hxa184-owners.py`：一次性职责提取脚本，基于执行当时的精确声明边界；不要在已经拆分的源码上再次执行。
- `refine-hxa184-layout.py`：仅清理机器清单内的拆分文件导入；机器清单位于忽略的 build 目录。
- `run-hxa184-device.sh <variant> <api> <port> <output>`：调用独占模拟器生命周期 runner，运行 app 集成后执行下述库级验证；拒绝现有 serial，finally 关闭自建实例。
- `run-hxa184-modules.py <serial> <output>`：仅供独占 runner 回调；逐项校验父进程所有权，冻结模块 APK SHA，记录非空 JUnit 与 assumption 数量，包括独立 Accessibility force-stop/setup/recovery。
- `fix-hxa184-races.py`：记录设备回归触发的审批注册/通知竞态和 Goal 删除时提醒关联读取竞态的初次修复；后续编译修正以当前生产源码为准，脚本不用于重放修复。

- `verify-hxa184-evidence.py`：核对主机结果、双 API 原始测试计数、冻结制品与关闭记录；`close-hxa184.py` 仅在验证成功后一次性生成收口文档。
- `recount-hxa184-module-results.py`：根据保留的原始日志修正 assumption 统计（AndroidJUnitRunner 使用 -4；-3 是 ignored），不改原始证据，不重跑设备。
