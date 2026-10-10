# DSH 预算收尾：压缩、恢复、渠道与日常安装

日期：2026-10-08。所有者要求完成预算改动剩余可完成事项，并要求后续优化和新功能尽量调研相关 Agent 竞品。预算行为仍参考 DSH 固定提交 `0d1f50007f9bca3f52b06e1c3074fa14d5fb0720`，不复制参考代码；前轮实现和主机结果见[预算对齐记录](dsh-budget-policy-2026-10-07.md)。

## 本轮范围与设计

普通 Turn 累计资源用量只统计、Goal 默认 256 自动轮次的生产实现未再次改动。本轮补全既有设备测试与回环模型夹具，完成压缩、实际进程死亡、双渠道/两 API 验证及日常模拟器安装。后台 AUTO 和用户 promotion 属于独立新功能，不作为预算验收的隐含实现；本轮不将其报告为已交付。

- `ChatCompactionFlowDeviceTest`：旧模型调用额度为 1 时摘要和后续请求仍完成；近窗口压力下旧输入额度、累计额度均为 1 仍完成两次摘要及原请求。保留手动摘要、取消、可恢复摘要失败重试和原文不变检查。Goal 摘要只记账，`goal.report` 通过原 Dispatcher 完成任务，超过旧模型调用额度不会阻塞。
- `LongTurnCompactionDeviceTest`：实际步骤用量、重复压缩、不可分段容量、无收益、原工具配对、取消/发布边界和数据库重开。
- `GoalProcessKillDeviceTest`：两次真实 SIGKILL 后记录自动轮数、预留用量及中断结果；不恢复进程内自动激活，不重复结算，不以旧时长/资源额度拒绝第三轮。
- 回环服务只在请求提供工具时生成 Goal 状态工具响应，摘要请求得到文本。夹具本身不授予权限；结果继续走生产准入、调度和审计路径。
- 项目现有服务/Room 测试检查目录共享、历史隔离、重开、删除保留、活动调用与事务回滚；不是完整项目 UI 或项目记忆端到端验收。

## 执行与证据

主机命令：`./gradlew test detekt spotlessCheck :app:lintDeveloperDebug :app:lintConsumerDebug --offline`，结果成功；日志 `build/dsh-followup-host-final.log`。双渠道 APK/AndroidTest APK、Storage AndroidTest APK 构建成功；最终日志 `build/dsh-followup-build-r3.log`。文档/source 与 diff 检查另在收尾执行。

设备矩阵由 `scripts/debug/2026-10-08/dsh-followup-matrix.py` 调用已有 `scripts/run-owned-emulator.py`，每组在闲置验证 AVD 的临时只读实例执行；指定 class 及完整命令保存在 `build/dsh-followup-20261008-r2/matrix.json`。App 使用 `com.helix.app.HelixAndroidJUnitRunner`。后续脚本运行 3 项 Storage 项目持久化用例，再使用已有 `scripts/run-goal-process-kill.py` 的 control、prepare、recover-prepare、recover-final 四阶段协议。

| API / 渠道 | App 定向测试 | 项目 Room 测试 | 真实进程死亡 |
| --- | --- | --- | --- |
| 36 Developer | 74 通过 | 3 通过 | 2 次 SIGKILL，control/最终恢复通过 |
| 36 Standard / consumer | 74 通过 | 3 通过 | 2 次 SIGKILL，control/最终恢复通过 |
| 29 Developer | 74 通过 | 3 通过 | 2 次 SIGKILL，control/最终恢复通过 |
| 29 Standard / consumer | 74 通过 | 3 通过 | 2 次 SIGKILL，control/最终恢复通过 |

合计 316 项测试断言通过，0 失败、0 跳过（App 296 + 项目 Room 12 + 进程 control/最终恢复 8）；另实际完成 8 次 SIGKILL。四组 runner 均 exit 0 并关闭自己的临时实例。所有数量来自本轮执行，不继承旧绿色。

API 36 使用 `Helix_Main_Verify_API36`，API 29 使用 `Helix_Main_Verify_API29`，arm64-v8a、420 dpi。现有 runner 启用硬件键盘及软键盘可见；不交付新 AVD，本轮未测试拼音输入。临时实例只由所属 runner 关闭；未改变系统安全配置。过程中的 host-admin SIGKILL 只验证已授权的隔离实例，不代表 Helix App 获得 Root。

## 失败尝试与修正

- 新增断言曾错误访问不存在的 ModelCall 输入字段，首轮编译失败；改为读取生产持久 Turn 用量，重新编译通过。
- 第一组实际执行 72/74：近窗口场景实际有两次摘要，旧 fixture 期待一次；Goal 夹具在摘要中发出工具响应。修正精确调用数及夹具请求类型判断后重新完整验证，不删除或跳过场景。
- 第二组启动曾因上一实例已退出但 ADB 传输记录尚未移除而被 runner 拒绝；未执行断言。矩阵改用不同端口，继续未通过的组，保留原成功记录。

## 日常模拟器安全安装

`scripts/debug/2026-10-08/dsh-install-daily.py` 只访问已确认的 `emulator-5554 / Helix_API_36`。安装前检查没有活动执行，验证 Room v1 identity hash `c34065205d0987bb3b495a2f4a663c43` 与当前 schema 相同；本地忽略目录保留数据库快照。`adb install -r` 更新 Developer debug 后启动并再次读取数据库，所有原有 session/message/turn/goal/project 身份均保留，79 个会话、1762 条消息、78 个 Turn 前后数量一致。未清数据、卸载、重置或调用真实模型。

安装和验证元数据：`build/dsh-daily-install-20261008/`。截图确认实际会话首屏正常显示；原额度失败是保留的历史事实，未自动重放。新包仍标为开发预览 0.0.5；版本名不等于旧 APK。此次检查未复现模拟器启动黑屏，不关闭未连接实体手机上的卡顿/黑屏报告。

日常安装应用 SHA-256：`5eabd1f3ddbabc1424963570f2894da29f9dcfa971c3c2f849d7b850ffd8516b`。安装成功后前后台状态及 AndroidRuntime 日志未发现当前启动崩溃；没有将一次首屏检查扩展为性能或长稳结论。

## 改动审查与剩余边界

预算生产改动集中于 `core/model`、`core/agent` 的预算/压缩/Goal 契约，以及 app 的准入、账本、恢复和预算设置页；本轮新增改动仅为上述两组设备测试、共享回环夹具、调试 runner 和证据。`GoalRoundAllowance.kt`、`GoalRoundAllowanceDeviceTest.kt` 属于前轮预算交付。工作树另有 Mobile Use 设置、插件/会话选择和启动优化，资源字符串及部分 UI 文件共享；提交时需按功能及共享 hunks 分组，不能整树纳入预算提交。

竞品参考要求已写入根 `AGENTS.md`：选择实现前尽量阅读相关 Agent 的一手文档/源码，结合 Android、权限、持久事实和当前范围记录采用点及取舍。未提交、推送、发布或调用真实账号。

剩余边界：实体机卡顿/黑屏及 OEM/低内存/Doze；真实模型摘要质量和长稳；完整项目 UI、记忆使用与进程死亡旅程；后台 AUTO/用户 promotion 的独立设计实施。模拟服务完整流程不等于真实模型语义质量，SIGKILL 账本 fixture 不等于所有 Runtime/外部副作用故障矩阵通过。

## 2026-10-09 提交前复核

所有者授权每完成一项任务提交一次。当前工作树复跑 `./gradlew test detekt spotlessCheck :app:lintDeveloperDebug :app:lintConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest --offline`，49 秒成功，940 个任务（21 执行）；日志 `build/hxa-baseline-20261009-host.log`。预算 checkpoint 按生产/测试路径和共享资源键分组；Mobile Use、启动优化和其他历史调试脚本保留未暂存，不随此提交。历史设备矩阵仍按上文日期记录，未冒称本日重跑。

历史材料维护（2026-10-10）：本页提及的`dsh-install-daily.py`已退役；以上为当时执行记录，现行入口与删除来源见[整理摘要](historical-version-retirement-2026-10-10.md)。
