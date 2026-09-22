# Bug Fix: 执行引擎与浏览器本地收敛

Status: fixed
Date: 2026-09-22
Related HXA: HXA-037, HXA-060, HXA-208
Affected modules: app, core/storage, tools/framework, provider/api, provider/openai-chat, feature/browser

## Problem

工具调度可能越过排队写屏障或丢失槽位唤醒；工具结果部分落盘后父Turn终态可能遮蔽未结算调用；Chat协议DONE后可能仍等待连接关闭。候选浏览器分支另有满额新建抛异常、隐私文案失真、同步非原子保存及宿主设置恢复缺口。

## Impact

影响批次顺序、跨批次等待、失败恢复和浏览器操作可靠性。本轮以受控探针、真实Room故障注入及独占模拟器验证，不声称已发生用户数据损坏或真实服务延迟故障。

## Root cause

排队前项未参与冲突判断，等待信号读取晚于准入检查；结果/状态/预算各自提交且恢复仅扫描活动父Turn；Provider将业务完成与传输结束混用。浏览器初版缺少异步持久化成功回执和延迟宿主配置缓存。

## Fix and invariants

### 引擎

- R2：准入同时检查排队中的冲突前项；后置读不越过等待中的写，独立读仍可并行。回归验证后置读得到写入后的值，保留原有并行、取消和批次顺序测试。
- R3：在准入检查前捕获共享槽位信号，释放发生在检查/订阅之间时也能唤醒。新增受控交错测试经过真实scheduleBatch等待路径；槽位释放由反射注入，另有原有真实跨批次测试，不冒充压力测试。
- R6：正文先物化；结果索引、验证状态、ToolCall终态和Goal用量在同一Room事务提交，随后投影UI。重复结算校验既有结果并复用幂等预算。拒绝路径也原子提交；单槽结算/协调失败后继续结算其余已执行槽位，最终传播失败。启动对账覆盖终态父Turn下的未结算子调用：确认未开始的PENDING/AWAITING_APPROVAL结算为CANCELLED并补结果，执行结果不明者标记NEEDS_REVIEW；绝不自动重放外部效果。
- R7：区分Chat协议结束和Completed事件；完整`[DONE]`后停止读取并关闭响应，保留finish之后、DONE之前的usage。覆盖分块DONE和不结束的transport；Responses/Anthropic保留各自语义并回归。

R4的旧Stop竞态已被前序修正，未找到当前写者的同等复现，本轮不新增CAS。R8原“成功被二次改失败”的断言不成立；本轮强化工具结果提交/投影边界和重复结算，整条Turn通知/队列drain的故障注入仍是后续P2验证项，不宣称全部覆盖。Room不回滚正文文件；事务失败可能留下未引用内容对象，本轮不引入通用文件journal或全盘GC。

引擎主机验证：`:tools:framework:test`、`:provider:api:test`、`:provider:openai-chat:test`、`:provider:openai-responses:test`、`:provider:anthropic:test`及Detekt通过；`:core:storage:testDebugUnitTest`通过。日志在同目录engine-r3.log及settlement-build日志。

真实Room设备验证：`ToolSettlementRecoveryDeviceTest`在自建API29/36 consumer模拟器各3项通过，覆盖正文失败、结果insert/verified/调用state/Goal预算写入故障回滚、重复结算、数据库关闭重开、终态父级下子调用对账和预算只计一次。没有用主进程SIGKILL冒充此次数据库重开。首轮runner误用consumer测试包名，未执行测试；修正为实际manifest中的`com.helix.agent.test`后重跑，失败日志保留。

### 浏览器

原`80c62295`、`39985854`经同步main和`ca2ccf07`修复后整合：

- 新建标签使用非抛异常准入，普通/不记录历史模式共用容量；满额按钮禁用。
- 明确“不记录历史（共享站点数据）”，不声称Cookie/DOM/cache隔离；用户脚本不声称Tampermonkey API兼容。
- 串行后台IO读写，保存成功后才发布新状态；失败显示错误并保留旧数据。使用同目录原子替换及文件sync，取消非原子的覆盖复制回退；存储读写上限2MiB，脚本正文上限256KiB。
- 延迟创建及重建WebView时应用无图/桌面设置。
- 下载菜单接入实际下载列表及空状态，替换占位Toast；修复10处硬编码中文，补齐三套资源。

浏览器JVM测试、Detekt通过。`BrowserRedesignDeviceTest`、`BrowserOwnerDeviceTest`、`BrowserSecurityDeviceTest`在自建API29/36模拟器各18项通过；包含满额准入、延迟创建/替换宿主设置、超限保存失败与原内容保留，以及原有宿主/安全回归。原子提交失败另有JVM故障注入。API29后仅移除误导性的Tampermonkey标题文字，API36使用更新后的APK；各批APK身份分别保留，不声称两批制品字节相同。

浏览器分支原始日志在其ignored `build/browser-convergence/`；整合后的完整门禁与设备验证另外记账，不用分支历史数字冒充整合结果。

## Alternatives considered

未引入通用journal、第二套执行框架、全局严格FIFO或盲目重放。未以删除Spike测试绕开构建检查，也未通过伪称WebView站点隔离解决隐私文案问题。R4没有当前生产竞态证据，不预先增加CAS。

## Regression verification

### 基线与构建整理

基线 `03e4d59e` 的完整门禁发现4份审查探针以普通`.kt`文件存放在脚本目录，被全仓Detekt扫描，触发20项规范错误。`abc61596`将其明确为`.kt.txt`诊断模板，专用prepare仍按原字节复制为build内测试源码，没有删除测试或放宽生产扫描。随后 `./scripts/check-all.sh --all`完整通过，包括36份依赖锁、Debug/Release构建与APK边界。日志：ignored `build/local-engine-convergence/baseline-r2.log`。

整合设备命令：

```sh
python3 scripts/with-host-slot.py -- env ANDROID_HOME="$ANDROID_HOME" python3 scripts/debug/2026-09-22/accept-engine-browser-convergence.py --output build/local-engine-convergence/device-integration-r4
```

API29/36 × consumer/developer各20项，共80/80通过，runner退出0；每批独占模拟器均已关闭。覆盖ToolSettlementRecovery、BrowserActivityLifecycle、BrowserRedesignUi、GoalUsageReservations、ParkedTurnCancelSettlement及ConversationStopConsistency。每批APK哈希及设备所有权记录随日志保存在ignored `build/local-engine-convergence/device-integration-r4/`。这是本轮定向回归，不替代全产品设备套件。

前一轮72项通过后增加页面两项与未执行调用取消断言。扩展矩阵首批曾19/20通过：按钮带图标前缀导致精确文本查找失败；改用稳定testTag验证禁用，并保留substring文案可见断言后重跑四象限80项全部通过，未删除或跳过失败测试。随后修正完整Lint检出的浏览器问题，对最终提交重新执行r4四象限，仍为80/80通过。

最终代码提交`77b423e6`（此前浏览器合并`16056928`、引擎`128eb33e`/`747291c5`），完整主机门禁通过：

```sh
python3 scripts/with-host-slot.py -- env JAVA_HOME="$JAVA_HOME" ANDROID_HOME="$ANDROID_HOME" ./scripts/check-all.sh --all
```

日志`build/local-engine-convergence/integrated-all-r5.log`，exit 0；源码/文档检查、Spotless、Detekt、JVM测试、Debug/Release Lint与组装、36份依赖锁及四种APK变体边界、订阅/Runtime边界全部通过。中间失败包括硬编码中文、文档字段缺失、格式、函数复杂度及浏览器Compose Lint；分别以资源化、补齐规定章节、格式化、提取判断函数、配置感知字符串及标准参数顺序修复，未放宽检查。原始失败与通过日志均保留在ignored build目录。

本轮已将浏览器与引擎修复收敛到本地main；按所有者要求未push、未触发或核验远端CI。浏览器原工作树保留其分支验收日志，BioHelix工作树未改动。

## Residual risk

R8完整Turn通知/队列drain故障注入仍为P2后续验证。Room不会回滚已物化的正文文件；失败可能留下未引用对象，未实现全盘GC。尚无真机OEM/Doze/热压、真实服务账号或远端CI结论。浏览器不记录历史模式共享站点数据，用户脚本在页面上下文执行，不提供Tampermonkey API或隔离世界。

## Related records

- [引擎复审](../research/execution-engine-deep-review-2026-09-22.md)
- [原分支审查](../evidence/development/branch-convergence-2026-09-22.md)
- [构建整理](../evidence/development/repository-hygiene-2026-09-22.md)
