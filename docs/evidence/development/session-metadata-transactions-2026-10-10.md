# 会话元数据事务职责验证

日期：2026-10-10。范围：[HXA-258](../../completion-records/HXA-258.md)，承接架构/测试维护审查；所有者授权继续实施、逐项提交、自有模拟器验证。没有覆盖日常APK、操作真机或调用真实模型。

## 实现与取舍

SessionCatalog集中原会话创建、草稿持久化、分支、标题、归档、工作区绑定审计，以及模型默认/运行配置的Room原子事务。共享原storage、clock、ID、SessionRunControlStore、工作区恢复与目录解析器。ChatService保留原动作队列、turnGate/submissionGate、输入归属、Provider可选性、Dispatcher重验、取消与页面反馈；同一Engine和冻结的执行配置不变。原相关会话写入不再散落于ChatService，读取和必要应用仲裁继续保留，不能称最终薄facade。

参考[OpenCode的职责及持久准入边界](https://raw.githubusercontent.com/anomalyco/opencode/dev/AGENTS.md)和[Android分层与单一事实来源](https://developer.android.com/topic/architecture/recommendations)，选取实际事务边界而非每个点击一层use case；没有复制参考代码或引入新框架。Room回滚不证明文件系统副作用也回滚。

## 主机

默认命令：`python3 scripts/with-host-slot.py -- ./gradlew spotlessApply test detekt spotlessCheck lintConsumerDebug lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`。最终catalog-final-host.log退出0、993任务；JVM、静态、双渠道Debug lint/App/AndroidTest均通过。新增5项真实Room测试仅编译不算设备通过，实际结果见下文。

首次至r3因新测试的长行在格式化后超界失败；r4因夹具Provider构造缺少imageSource/testStatus/ID失败。修正真实接口与格式后r5通过；追加有界错误码诊断及普通回答夹具的会话额度后最终重新通过。失败日志保留，不添加lint豁免或删除断言。

完成状态迁移后的catalog-closeout-source.log退出0：source门禁153项及文档/ADR/国际化/秘密扫描通过（787 Markdown、241 HXA、36 ADR）。原测试、取消/权限/256轮边界保留，生产预算未改。

## 自有API36设备及失败溯源

各次均由稳定`run-owned-emulator.py`启动自有Helix_Main_Verify_API36 arm64只读、无保存快照实例，4GiB、实际拼音候选/中文提交；APK/Test冻结并记录SHA，日常5554不借用。产物在ignored `build/maintenance-followup-20261010/`。

| 尝试 | 结果 | 意义 |
| --- | --- | --- |
| Consumer5654原矩阵 | 48/48、skip0；恢复after-script失败 | 元数据5项与分支/配置/草稿/分享/收据通过；编辑已在新PID恢复，但最终已接收会话查找只看可见列表 |
| Consumer5658恢复r2 | 6/6、skip0；全部after-script通过 | 原日期脚本最后查找改用既有30页有界精确标题滚动，原内容/身份/无重放断言不变 |
| Developer5656原矩阵 | 46/48、skip0 | 重新生成与分支普通回答未完成，未执行after-script、不计设备通过 |
| Developer5660诊断 | 1/3 | 两项错误均OUTPUT_TOKEN_LIMIT，持久本会话maxOutputTokens=1；不是仅凭超时推断 |
| Developer5662改动前APK对照 | 1/3，相同错误码与1-token事实 | 冻结HXA-256 APK配当次诊断TestAPK，精确两方法的公共接口兼容范围；确认继承配置问题，不证明整个混合配对兼容 |
| Developer5664最终矩阵 | 48/48、skip0；全部after-script通过 | 普通回答夹具显式设置自己的会话TurnBudgetBounds.DEFAULT，保留完成/历史/请求内容及1-token拒绝测试；不改生产默认或原AVD |
| Consumer5666最终定向补验 | 3/3、skip0 | 实际拼音及两项修改后的普通回答测试通过；不将此记录成一次最终48项重跑 |

Consumer恢复r2：composer PID4024→4348，revision4963→5350；实际正常UI写入与SIGKILL，新进程精确恢复文本，原已接收输入不重放。取消恢复INTERRUPTED、UNKNOWN原调用身份、新执行/模型0、重复恢复不变。Goal删除提交前5723及提交后5774两次SIGKILL、最终恢复1/1；TaskJourney及SessionSearch精确单方法setup真实退出/verify各1/1，不将phase assume-skip算通过。runner与closed均退出0。

最终Developer的composer PID4435→4959、revision5655→5999真实杀进程与恢复、TaskJourney发布后无重执行、SessionSearch进程恢复均通过；两个最终runner及closed均退出0。冻结Developer App SHA256 `b5cdae6e94af9f18fad653a2f331f6f7056ca29debdbb1a0b4179f396928ed43`、Test SHA256 `5fb64e5a809d4e27144fef61de120531da95d8e8eb5c66379db15c3dc8bb8977`；Consumer App SHA256 `eb72f00da42eed744ea84a545c2d1db82b2cd4ba49a931d1976f55a01ac6250a`、Test SHA256 `05bbfbd7f7d9295d230e6d4a87155aa14c9e3b62ccfbda875395082d3c012883`。

本轮两项OUTPUT_TOKEN_LIMIT的直接证据是实际会话输出上限1、同一错误码在改动前APK复现，以及只显式隔离夹具预算后通过。生产TurnBudgetBounds.DEFAULT为16,384 token/次，不因这些夹具失败提高默认值。输入计入上下文与累计用量统计，不是该错误的累计输入阈值。当前错误码有两个来源：TurnBudgetTracker在有效输出超过实际请求/会话上限时拒绝，以及ModelStreamState收到provider的finishReason=length时拒绝；不能单凭错误码判断是哪种。其他历史真实模型失败仍需逐请求核对实际max_tokens、有效上下文限额与provider用量，不能由本次两项推导全部相同根因。

## 复跑与边界

稳定runner用双渠道对应APK/Test/runner、授权且空闲端口、fresh输出，classes包括PinyinComposer、SessionCatalog、SessionFork、SessionRunControl、ChatSubmissionReceipt、SessionForkFlow、SessionDraft、ShareDraftUi、ProviderModelSelectionFeedback、RunControlSettingsUi、ConversationReceiptRace。恢复沿用原composer/revision seed+host PID kill/verify、Consumer稳定Goal删除及TaskJourney/SessionSearch setup/verify协议；单次普通JUnit不替代进程死亡。

不关闭真机卡顿/黑屏、其他API/OEM、真实模型、长期资源压力或整个审查。结构职责收敛不是已测性能收益；原失败及本轮实际APK身份继续保留。
