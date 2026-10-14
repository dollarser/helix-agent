# 引用上下文与压缩一致性收尾

日期：2026-10-14。任务：[HXA-285](../../development/tasks/HXA-285.md)。起点：`14824251`；工作区已有并行研究/架构文档及历史脚本，未覆盖、删除或将其全部归入本次修改。Owner要求先收尾；设备、真实模型、提前结束排查与长稳均未启动。

## 缺陷与修复

原请求把输入接受时保存的跨会话引用追加到消息正文，压缩源仅读取原正文，严格一致性检查因此拒绝计划。此前JVM探针已经复现；本次修复生产物化路径，不放宽比较，也不将引用提升为权限。

`ContextHistoryContent`统一原正文与引用边界；`ContextHistory.load`一次读取并缓存有界规范正文，引用正文/标记也计入8 MiB上限。正常发送/backfill/retry/恢复的模型投影与压缩源共用该文本。`TurnContextRequest.historySnapshot`仅携带本请求的瞬时Core快照，模型协议不编码它，不新建持久历史；压缩规划复用原快照。checkpoint发生变化时规划拒绝；摘要原事务发布前再次核对checkpoint，防止旧摘要覆盖新边界。使用原ContentStore的hash校验，未改变附件披露、工具配对、取消与UNKNOWN语义。

上下文占用的增量投影同样读取缓存的规范内容。没有修改窗口阈值、输出上限、Memory检索、Skill选择或生产模型策略；没有声明token/完成率/性能收益。

## 验证与失败记录

- `build/hxa285-unit.txt`：首次命令退出1；错误使用模块级`:app:spotlessApply`，该任务不存在，未产生测试通过结论。
- `build/hxa285-unit-r2.txt`：修正命令后退出0；App `ContextReferenceCompactionTest`5项、`CompactionContinuityTest`1项、Core `ContextCompilerTest`13项，均0失败/0跳过。覆盖规范引用的摘要输入/尾部保留、正文差异仍拒绝、引用边界和无引用语义。
- 格式化使用根任务`spotlessKotlinApply`，只改本轮Kotlin路径；最终全仓与测试APK结果待下方补充。
- `build/hxa285-all.txt`：228项主机脚本通过后，文档门禁因新任务缺少必需的“交付”章节退出1；补齐章节后重跑。
- `build/hxa285-all-r2.txt`：源码门禁通过，静态分析失败。两处请求构建函数因新增快照字段触及LongMethod阈值，已将共同历史绑定提取为同一小函数；同时发现既有忽略目录中的四项诊断探针存在两处超长行及独立测试包路径告警。仅换行及给该独立注入探针添加包路径例外说明，未删测试或改任何断言，未放宽全仓门禁。
- `build/hxa285-format-r3.txt`/`build/hxa285-all-r3.txt`：收紧缓存map读取为`getValue`后，一行超过120字符，格式化/检查失败；显式拆行后`format-r4`退出0。后续以修正后的完整命令为准，不继承失败轮通过结论。
- `build/hxa285-all-r4.txt`：新增快照构建使`persistedHistory`达到62行并被LongMethod拒绝；将规范快照构造提取为独立纯装配步骤。`build/hxa285-static-r5.txt`的根格式化及detekt已实际退出0，未新增生产抑制或调整阈值。

最终结果：`build/hxa285-all-final.txt`对应`./scripts/check-all.sh --all`实际退出0，228项主机脚本、完整JVM、文档/ADR/i18n/secret、spotless/detekt、双渠道Debug/Release lint与APK构建、41份依赖锁和集成制品/进程/UID/渠道边界检查通过。报告器主机测试打印的FAIL/INCONCLUSIVE是受控负例，不是本轮设备失败；没有运行设备。

最终JVM XML复核：新增引用回归5项、连续性1项、原ContextCompiler13项，均0失败/0跳过。`build/hxa285-test-apks.txt`对应`:app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`实际退出0；Room新增三项夹具验证保存引用与规范映射、展开引用超限、旧checkpoint不能发布。它们仅编译，本轮未请求设备，不能记为运行通过。

当前Debug制品SHA-256：Consumer `9252a4ef18c2a2c20eb1d4adc1f91c937ce4df049b0ed52d270def2e96c8c0f3`；Developer `8037ff8f014105936132980a911dc30b100be13ff57e00b6680841fb3fe823b0`。原始清单`build/hxa285-apk-sha256.txt`；未更新任何设备APK、未改Room schema或现有数据库。

最终状态整理后，`build/hxa285-docs-final.txt`的文档检查实际退出0：863份Markdown、268项HXA；`git diff --check`通过。仅暂存本轮源码、任务/证据、内测/发行说明及roadmap/status的HXA-285专属hunk；并行架构提案、研究和其他历史文档未混入本轮提交。

## 剩余边界

App/Room完整发送→自动压缩及进程恢复的设备运行、真实模型摘要忠实度、PDF/DOCX实际渲染和最新Mobile Use长稳均未在本轮执行。Notion/Linear账号配置与正式发行输入仍等待Owner条件。本次主机修复不能代替这些验收。
