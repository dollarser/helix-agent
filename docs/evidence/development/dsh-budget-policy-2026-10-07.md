# DSH 预算契约对齐

日期：2026-10-07。来源：所有者要求预算部分完全参考 DSH，并明确允许按新契约更新断言、完整回归。参考固定提交 `0d1f50007f9bca3f52b06e1c3074fa14d5fb0720` 的 agent-loop、goal、goal-round-driver 与 compaction-basic 文档及实现；参考材料未复制为项目代码。

## 实现范围

- 普通 Turn 不再由累计输入/输出 token、模型调用数或工具轮数终止；用量仍持久记录。单次输出上限、模型窗口容量和协议边界继续生效。
- 普通请求及摘要请求均不再读取旧的独立输入额度。压缩按模型上下文窗口判断，与累计任务用量分离；本次不替换 Helix 已接受的摘要内容、选择及图像策略。
- Goal 只保留自动轮数限制，默认 256。按持久化 run/turn 绑定计算，人工输入与恢复不消耗自动轮数，取消不退还已经开始的轮数。第 257 轮准入被阻止；显式提高额度后可解除阻塞。
- 旧配置字段保留读取兼容，不再作为执行额度。无需变更数据库 schema。资源账本、预留身份、恰好一次结算、取消、UNKNOWN 核查和运行时租期继续保留。
- 异常失败不自动重试；旧本地额度失败不会自动创建后继 Turn。设置页只展示单次输出与 Goal 自动轮数两个对应选项。

有效契约见 [模型数据边界](../../adr/agent/006-model-data-budget-boundaries.md)、[Goal 生命周期](../../adr/goal/001-lifecycle-and-completion.md)和[上下文压缩](../../adr/agent/002-context-compaction.md)。

## 当前验证

以下命令执行成功，日志为本地忽略产物 `build/dsh-budget-final-verified.log`：

```sh
./gradlew spotlessApply test detekt \
  :app:assembleDeveloperDebug :app:assembleConsumerDebug \
  :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebugAndroidTest \
  :app:lintDeveloperDebug :app:lintConsumerDebug --offline
```

结果：BUILD SUCCESSFUL；990 tasks，45 executed，945 up-to-date。

| 相关 JVM 套件 | 总数 | 失败 / 错误 | 跳过 |
| --- | --- | --- | --- |
| core/model | 163 | 0 / 0 | 0 |
| core/agent | 289 | 0 / 0 | 0 |
| app consumer | 1132 | 0 / 0 | 4 |
| app developer | 1244 | 0 / 0 | 4 |

跳过为既有外部 Connector/WorkBuddy 样本配置未提供的用例，不是预算回归。新增/更新断言覆盖旧额度不阻止执行、输出容量、负数用量拒绝、旧配置读取、失败不重试，以及旧输入额度仅 1 token 时摘要仍按窗口准入。

设备测试 APK 已编译，包含 256/257 轮、重开计数、取消不退款、手动输入不计数、账本和取消/租期场景；未执行设备测试。设备状态 **not requested**，未安装 APK、未使用模拟器或真机、未调用付费模型。本记录不证明设备 Room/进程恢复验收，也不关闭先前真机卡顿/黑屏报告。工作树保留先前 Mobile Use 等未提交修改；本次未提交、推送或发布。

## 后续所有者授权模拟器回归

2026-10-07 所有者明确要求“帮我模拟器测试”。以上未运行状态为此前主机阶段；此次设备状态 **passed（下列定向范围）**。

- 设备：独立验证 AVD `Helix_Main_Verify_API36` 的临时只读实例，`emulator-5596`，API 36、arm64-v8a、420 dpi；Developer debug 0.0.5。保留日常 `emulator-5554`，未覆盖其安装或数据。通过现有 runner 设置硬件键盘和软键盘可见选项；此次未验证拼音输入，不交付新 AVD。
- runner：`scripts/run-owned-emulator.py`，instrumentation 为 `com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`，`--raw-results --timeout 900`。运行 GoalRoundAllowance、GoalRunCoordinator、GoalContinuation、GoalUsageReservations、GoalTimeLease、GoalLeaseClock、GoalModelCancellation、RunControlSettingsUi、GoalReminderControls 九个 DeviceTest 类。
- 最终 **53/53 通过，0 失败，0 跳过**，用时 40.122 秒；原始结果、设备身份和 APK 哈希保存在 `build/dsh-budget-emulator-20261007-r4/`，控制台在 `build/dsh-budget-emulator-r4-console.log`。临时实例由 runner 正常关闭。
- 实际验证包含 256/257 轮边界、Room 重开计数、取消不退款、人工输入不计数、重复恢复结算不重复、显式取消关闭模型连接、旋转保持原运行身份、断流不重放、旧时长额度不终止连接、运行时租期仍有界、设置显式保存及 Activity 重建。
- 首次尝试因日常 AVD 不允许并发副本而未启动；第二次使用默认 runner 导致入口未找到，未执行断言。修正为项目 runner 后首次执行 52/53：恢复账本测试仍期待旧额度下的 2 次调用，而实际三项合法预留均正确入账。更新为 3 次及允许下一轮，并补充重复恢复后账本保持不变，随后完整重跑 53/53。未改生产代码。
- 修正测试后双渠道 AndroidTest APK 构建及 spotlessCheck 成功，`build/dsh-budget-device-fix-build.log`；`git diff --check` 通过。
- 应用 APK SHA-256：`5eabd1f3ddbabc1424963570f2894da29f9dcfa971c3c2f849d7b850ffd8516b`；最终测试 APK：`3fd44c1fa2536e36cfd6a61b4f9161241d5100824c5a0069b61b93f7f008e404`。

未运行 Consumer instrumentation、其他 API/OEM、真实服务/付费模型、真实进程死亡矩阵和长上下文端到端压缩测试。Room 重开不等同于完整进程死亡验证；本次结果不关闭真机卡顿/黑屏问题。未提交、推送或发布。

2026-10-08 所有者继续授权收尾，双渠道/两 API、模拟服务完整压缩流程、真实进程死亡及日常安装的后续结果见[补充验证记录](dsh-budget-followup-2026-10-08.md)。上方未运行条目只描述前轮范围。
