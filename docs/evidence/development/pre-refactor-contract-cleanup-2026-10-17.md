# 重构前契约清理与任务收敛

Date: 2026-10-17
Task: [HXA-303](../../development/tasks/HXA-303.md)
Status: 已完成授权范围；主机门禁通过，设备/真实服务 not requested

## 起点与范围

起点 `684fbcd8`，main 干净；无既有未提交差异。CodeGraph 可用但索引滞后，以当前源码和调用者复核。没有本地技能目录；未修改历史记忆、未操作设备或真实服务，未提交/推送/发布。

## 实现与决策

- 删除 Turn/Goal 的无效预算字段和旧默认值转换，仅保存输出限额与自动轮数；用量事实和恢复流程继续独立保存。移除旧审计枚举与已无消费者的三语文案。
- 预算 shape 与会话配置列同步更新到当前预览基线，不添加旧 decoder、迁移链或双写。数据库仍为 v1，versionCode 不变；安装新构建后，不兼容预览库按既有规则重建，数据库外文件不由此删除。本轮未安装构建。
- 能力提供方声明发现配套，宿主不再按插件 ID/工具名选择配套组。配套只从当前允许的同来源描述中展开；已撤销或替换的原描述不能重新进入工作集。有限图中的环正常去重，不把互相配套误作运行依赖错误。
- 发现提示不进入安全 contractHash；发布时冻结集合，改变提示不能增加权限或修改执行语义。目标完整贡献协议仍归 286。
- 更新 286/287/288/293/294 的替换清单及行为验收；最小 Room 联合集成之后优先接 Mobile Use 完整观察/动作/恢复路径。收拢 status 的重复已完成段落，历史任务和原证据仍保留。
- 同步 Goal/压缩/输入恢复夹具：自动轮数与累计用量分开，额外模型请求检测仍保留。固定 Goal 评测记录独立 verifierVersion；保留历史数据集与原提示，禁止将修改判定器后的分数和历史预算耗尽结果作同条件对比，见 [Eval 说明](../../../evals/m10/README.md#current-goal-verifier)。

## 主要改动文件

| 范围 | 实现与回归入口 |
| --- | --- |
| 有效预算 | [TurnBudgets](../../../core/model/src/main/kotlin/com/helix/core/model/TurnBudgets.kt)、[GoalBudgets](../../../core/model/src/main/kotlin/com/helix/core/model/GoalBudgets.kt)、[RunControlStore](../../../app/src/main/kotlin/com/helix/app/runcontrol/RunControlStore.kt)、[偏好回归](../../../app/src/test/kotlin/com/helix/app/runcontrol/RunControlStoreTest.kt)；其余构造调用和夹具同步 |
| 当前存储基线 | [SessionRunControlEntity](../../../core/storage/src/main/kotlin/com/helix/core/storage/entity/SessionRunControlEntity.kt)、[Repository](../../../core/storage/src/main/kotlin/com/helix/core/storage/repository/SessionRunControlRepository.kt)、Room 导出 schema 及契约测试 |
| 插件配套声明 | [ToolDescriptor](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDescriptor.kt)、[发布快照](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDescriptorSnapshot.kt)、[工作集](../../../app/src/main/kotlin/com/helix/app/mcp/ToolDiscoveryWorkset.kt)；Mobile Use/Browser/Documents/PRoot Job 贡献方 |
| 新反例 | [发现闭包](../../../app/src/test/kotlin/com/helix/app/mcp/ToolDiscoveryCompanionsTest.kt)、[发布及权限不变](../../../tools/framework/src/test/kotlin/com/helix/tools/framework/ToolDiscoveryContractTest.kt)，以及实际插件声明回归 |
| 后续任务 | [Harness §23.7.4](../../architecture/harness-refactor-plan.md#2374-当前清理基线与替换验收)、286/287/288/291/293/294、状态与索引；Tools/Goal/Storage 原主题 ADR 及 Decision history |

## 验证记录

原始输出：忽略目录 `build/hxa303/`。

- 基线 Gradle 命令退出 0；App 定向任务实际执行，Core/Framework 为已有缓存结果，不能称为本轮全量复跑。
- 迁移阶段编译暴露尚未转换的 fixture copy/嵌套参数，存储单测指出当前 schema 列名断言需同步。保留失败日志，修正实际调用者和当前结构断言，不跳过测试。
- App 定向回归指出测试自建描述缺少新配套声明、错误推理等级反例误用了允许的服务端自定义 token；修正夹具并保留正反例。完整门禁还发现任务标题不符合文档模板、既有类增加声明后超过长度门槛、长测试字符串和无用 fixture 参数；分别修正文档结构、提取同文件无状态 helper、拆行并按实际自动轮数契约重写夹具，不添加豁免或放松门槛。

| 最终命令 | 当前执行结果 |
| --- | --- |
| `./scripts/check-all.sh --all` | exit 0。Python 275 项通过；文档 917 篇/286 个 HXA、37 份 ADR、三语 2,299 key、秘密扫描、Spotless、Detekt、Debug/Release lint、全仓 JVM 测试通过；双渠道 App 及 PRoot/CLI Runtime Debug/Release APK、release-smoke 测试 APK 构建通过；42 份锁文件及 Debug/Release 集成运行时/渠道边界检查通过。日志 `all-gates-verified.log` |
| `./gradlew spotlessCheck detekt lintConsumerDebug lintDeveloperDebug :core:storage:assembleDebugAndroidTest :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` | exit 0；最终夹具/注释调整后的补验，包含两渠道 App 与存储测试 APK 编译、静态和 lint。日志 `androidtest-build.log`；未执行 instrumentation |
| `./scripts/check-docs.sh`、`./scripts/verify-adr.sh`、`git diff --check` | 完成记录回填后再次通过；纯文档收口，无新增运行语义 |

全仓 Gradle 门禁关联的 XML 报告共 6,065 条：6,057 通过、0 失败/错误、8 跳过。包含渠道重复和部分有效缓存结果，不声称 6,065 个独立用例都重新执行。受影响的 Model/Agent/Framework/Browser/Mobile Use 与双渠道 App 测试在最终全仓门禁中实际执行；Storage 241 项在前序修复回归通过，最终门禁为 UP-TO-DATE。汇总入口为本地 `test-summary.json`。

| 相关测试任务 | 报告总数 | 跳过 | 失败/错误 |
| --- | ---: | ---: | ---: |
| App Consumer Debug | 1,281 | 4 | 0 |
| App Developer Debug | 1,402 | 4 | 0 |
| core:model | 162 | 0 | 0 |
| core:agent | 293 | 0 | 0 |
| core:storage Debug | 241 | 0 | 0 |
| tools:framework | 261 | 0 | 0 |
| tools:browser Debug | 45 | 0 | 0 |
| extensions:mobile-use Debug | 306 | 0 | 0 |
| extensions:documents Debug | 27 | 0 | 0 |

每渠道的 4 个跳过项分别需要公开 MCP 网络验收、固定公开配置输入、Connector 样本 ZIP、WorkBuddy 样本目录。本轮未启用对应的 opt-in 输入，不计通过，不削弱或移除测试。

### Owner 追加的 8 项补验

2026-10-17 Owner 明确要求尝试原跳过项后，复用已准备且经 manifest 哈希校验的本地样本，执行 `python3 scripts/connector-samples.py test offline --output build/hxa303/skipped-followup-offline`，两渠道分别 3/3 通过；再执行 `python3 scripts/connector-samples.py test public-mcp --output build/hxa303/skipped-followup-public-mcp`，两渠道分别 1/1 通过。共 8/8、零失败/跳过；选定 Gradle 测试任务强制重跑，不复用旧通过结果。匿名 Cloudflare 文档 MCP 实际完成协议协商、工具发现和只读查询。未执行包内脚本、账号服务或设备安装；这不是模型任务或第三方账号业务验收。

后续 Owner 又授权升级确认提示、独立 ARM64 模拟器/真实模型验收及本地提交，作为新的追加范围推进；新构建不继承本记录的设备结论。直接覆盖旧库的清空风险必须在产品启动前改为明确确认，不能将不兼容旧设计视作删除现有用户数据的授权。

## 审查结论

- 本轮删除的是已失效的内部配置/审计兼容负担，未重新引入累计用量阻断或自动重试；有效输出/续跑轮数及持久用量各有回归。
- 插件贡献提供发现线索，实际授权和执行仍经过既有 Dispatcher；同源闭包、发布不可变、禁用/替换过滤和实际插件声明分别验证。
- 旧 Turn/Goal 宿主准入类型和遗留不可达分支已在 287 列出具体删除责任；完整结果/产物、MCP Resources 与上下文策略没有被局部交付宣称完成。
- 分支仍为 main、HEAD 仍为 `684fbcd8`；本轮改动留在工作区，未提交、推送或发布，无工作区冲突阻塞。

## 边界与接续

设备、真机、模拟器、真实 Provider、PRoot/FFmpeg 媒体端到端：not requested。测试 APK 编译不证明 Room 设备重建、运行恢复或模型任务成功。

完整 Turn 宿主、类型化执行结果、文件/执行环境映射、MCP Resources 正文读取和统一可选上下文预算仍由各后续任务实施。当前交付不等于 286～294 完成，不改变暂缓发布或整体重构顺序。
