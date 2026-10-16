# 发布前崩溃窗口与结果结算审查

日期：2026-10-16。Owner 要求解释三个崩溃窗口并在发版前修复当前可复现缺陷，允许有界 ARM64 模拟器验证，真实模型按需；不跑长稳、不改服务器、不推送发布或启动整体重构。起点为干净 `main / 91bb9e35`，原 ARM64 候选保留。

## 窗口与当前证据

原文归 [HXA-287 联合检查点](../../development/tasks/HXA-287.md#原共同集成检查点)、[HXA-288](../../development/tasks/HXA-288.md) 和 [Agent ADR](../../adr/agent/001-turn-coordination.md)。它们是执行前、效果后提交前、提交后通知前的故障注入时点；不是三个已知 Bug，也不是只约束未来版本。

| 窗口 | 当前路径及用户后果 | 本轮审查分类 |
| --- | --- | --- |
| 开始屏障前 | Dispatcher 的 `onExecutionStarting` 先提交 RUNNING，再调用 executor；PENDING/AWAITING_APPROVAL 恢复为开始前取消 | 已有机制；写入失败零执行有 JVM 回归，本轮增加真实 Room 故障/重开验证。屏障提交后、executor 调用前死亡则保守不确定 |
| 效果后、结果提交前 | `ToolSettlementWriter` 将结果、验证位、调用状态和 Goal 计费原子提交；外部文件/点击不在事务内 | 已有机制；SQL 失败回滚及重开后不重放验证。实际外部效果不假称已回滚 |
| 结果提交后、通知前 | 已提交结果应保留，旧 Turn 不复活；界面可以重建 | 本轮复现通知异常从 `ChatToolSettlement` 向执行循环传播，已当前修复，不留给 287 |

邻近入口还确认 `ChatToolCalls.dispatchToolCall` 未沿批量入口读取 `ExecutionFailed.requiresReview`，把执行器明确声明的不确定结果保存成 FAILED。生产模型批量入口已有判断，不能夸大为所有模型调用均丢失核查状态；直接入口仍必须一致。

## 修复前复现

新增定向测试在未修改生产源码的 Standard/API36 ARM64 自有只读模拟器运行：9 项、2 项失败。失败分别为通知抛出 `injected projection failure after commit`，以及预期 NEEDS_REVIEW 实际 FAILED；其余事务失败、开始屏障、重复恢复检查通过。原始输出归忽略目录 `build/crash-window-review-2026-10-16/before-consumer/`，不修改期望或删除失败记录。

## 修复与验证结果

修复限制于具名调用结果分类和可丢的界面投影：直接入口复用原批量核查判断；持久提交仍先执行且异常向上传播，界面刷新异常单独记录，不改写执行结果；批次先记录原调用结算再通知 UI。没有增加重放、第二账本、全局锁或新执行层。

扩展到 35 项后，首批另有 4 项过时夹具失败：3 项仍断言 2026-10-07 已由 ADR-GOAL-001 撤销的累计模型/时间额度，1 项用空工具数组进入新模型步骤。前者改验用量仍持久、达到旧字段值不误阻断、重开只结算一次；后者提供真实非空调用，继续验证下一 ModelCall 与步骤先持久化。没有恢复旧限制、删除测试或跳过断言。

修复后 API36 ARM64 双渠道定向各 **35/35**、零跳过，包含 ToolSettlementRecoveryDeviceTest、ExecutionEffectBoundaryDeviceTest、ProcessRecoveryTest、TurnCommitStoreDeviceTest 和 TurnCancellationRaceDeviceTest。它们使用真实 Room/事务及受控外部文件效果；重开数据库不是实际杀进程，后者归下面 R8 检查。第一次连续复用端口时旧 ADB 项尚未消失，runner 在启动前拒绝；后续换空闲端口完成 Advanced，没有借用其他实例。

修复与测试提交为 `8ef710d3`。完整 `./scripts/check-all.sh --all` 最终 exit 0：Python 275/275；App Standard 1270 项通过、4 项既有外部输入条件跳过，Advanced 1391 项通过、4 项同类跳过；全仓 JVM、双渠道 lint/Debug/R8 构建、锁文件及 Runtime/渠道边界通过。App 单测在修复后完整执行，最终确认运行复用未变任务缓存；Tools/Agent 的本轮任务为 UP-TO-DATE，不冒充新执行。两渠道 AndroidTest 另外编译通过。

第一次主机静态检查发现本次函数长度及测试字符串长度问题，已提取具名投影方法、拆分字符串；后续临时生成的独立 R8 测试源码被全仓扫描，已移至忽略的 `build/` 生成目录，未改规则或排除生产源码。失败日志保留，最终命令、exit 与报告归 `build/crash-window-review-2026-10-16/host-confirmed-final.*`。

从干净 `8ef710d3` 另冻 Standard/Advanced ARM64 非调试 R8 候选，版本保持 0.0.6/code 1 和原开发证书；制品、mapping、源清单、签名校验、发行说明及依赖/包内 notice 清单归 `build/crash-window-review-2026-10-16/candidate/`。清单不冒充完整 SBOM。旧候选及 ARM64 分发清单哈希复核通过、原样保留；后续文档提交不改变这份生产来源。

### 实际 R8 进程恢复

`./scripts/build-release-apks.sh arm64-v8a` exit 0；另编独立测试 APK，通过公开 UI、受控 loopback OpenAI Chat 协议及只读 SQLite 检查实际冻结 APK，不将测试代码合入生产包。安装后的 APK 哈希与冻结清单一致，目标包确认为非 debuggable。

| 场景 | Standard | Advanced | 断言 |
| --- | --- | --- | --- |
| 正常 write/read 完成后，两次 force-stop/重启 | 1/1 | 1/1 | 文件内容、原结果/调用身份不变，调用数为 1，Turn 仍 COMPLETED |
| write 结果已进入下一模型请求、响应尚未返回时，两次 force-stop/重启 | 1/1 | 1/1 | 原工具仍 COMPLETED/SUCCEEDED、文件内容及结果不变；Turn 为 INTERRUPTED，不复活旧执行、不产生重复调用或 review |

设备为 `Helix_Validation_API36_20261012` 的自有 API36 ARM64 只读实例，`scripts/run-owned-emulator.py` 管理启动、键盘配置、安装和退出；飞行模式下仅使用设备 loopback，零真实服务调用。R8 共 4/4、零跳过，每项记录两次不同进程 PID。原始命令、JUnit、结果和清理归 `build/crash-window-review-2026-10-16/r8-final/`。启动检查中的实际拼音组合/中文提交另有 1/1，不混入恢复计数。

首轮独立夹具的 `ACTION_CLICK` 未触发当前会话的复合导航项，在工具开始前失败；源码及截图没有支持“入口禁用”的判断，改用同一可见控件的真实触屏后四项通过，生产 APK 未变。原失败 APK/截图/日志归 `r8-navigation-fixture-failure/` 及同层文件，未删失败或放宽结果断言。

Room 故障注入验证精确的开始/提交/投影异常；R8 验证真实进程死亡及已提交结果恢复，后者并非精确杀在通知前。两类证据分别成立，不能互相替代。没有重跑上一候选的完整 UI、Mobile Use、真实 Qwen 或长稳矩阵；旧证据保留原来源，不自动迁移为本候选通过。

## 收口与边界

生产改动仅 `ChatToolCalls.kt`、`ChatToolSettlement.kt`；新增 5 项回归在 `ExecutionEffectBoundaryDeviceTest.kt`、`ToolSettlementRecoveryDeviceTest.kt`，另修正 `ProcessRecoveryTest.kt` 和 `TurnCommitStoreDeviceTest.kt` 的 4 项过时夹具。架构基准/窗口解释更新原 Harness 计划、策略与 Agent ADR，不新增兼容层或整体重构。

当前发现的两个缺陷和本轮门禁失败均已收口，没有这次范围内待修的已复现阻塞；无法由有界测试证明软件绝无 Bug。真实断电、其他 OEM/真机、全协议/真实模型恢复及长稳未由本轮验证。外部效果已发生但未提交的区间仍须按不确定事实处理，这是真实执行边界，不允许用重放伪造成功。

测试主应用、测试 APK 和临时数据均在自有只读实例卸载，实例已退出；没有使用或修改日常模拟器安装。原候选保留，本轮只本地提交，不推送、运行远端 CI 或发布。未来发布需采用本轮新候选，并核对当时源码和授权；未来 HXA-287/288 替换实现仍需重复相关验收。

## 设计基准

不兼容旧版是可直接替换的许可；目标是当前职责合理、未来扩展/集成成本低和有实战依据。删除 ADR 的旧 v29/review-resume 兼容条款，不删除当前授权、取消、幂等与同版本恢复要求。Codex 的 thread/turn/取消身份实践仍按[既有研究](../../research/topics/tool-and-plugin-competitive-review-2026-10-15.md#10-2026-10-16-预览后重构与能力复核)采用；[MCP 取消规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation)提示取消与迟到结果的竞态，[SQLite 原子提交说明](https://www.sqlite.org/atomiccommit.html)只证明数据库事务边界，不覆盖外部工具副作用。本轮读取一手资料，不复制参考实现。

## Owner 追加：日常模拟器真实模型短验收

Owner 明确要求优先日常模拟器并现场查看交互，仅补最终包的两渠道正常路径，不重跑异常恢复或全量矩阵。沿用同一 `8ef710d3` 冻结 APK，当前文档基线 `f676148f`；APK 和原候选清单均未修改。设备是已有可见窗口的 `Helix_API_36 / emulator-5554 / API36 ARM64`，没有另外启动无头实例。两包通过覆盖安装更新，安装哈希与冻结清单一致，Room identity 一致；没有清空应用数据。

通过独立测试 APK 分小批执行公开 UI，每步留出观察时间；完成后停留结果页面，再进行一次 force-stop/启动。真实服务为 Owner 已授权的 Qwen3.8-27B / OpenAI Chat Completions，使用临时本机转发，密钥只在主机进程内存中，不写入手机或日志。模型连接/能力探测与任务调用分别存在，不将探测计为业务旅程。

| 渠道及保留会话 | 文件 | 本轮实际结果 |
| --- | --- | --- |
| Standard：`R8验收·Standard·1016` | `r8-consumer-1016.txt`，内容 `Helix Standard 验收成功。` | 1/1：一个 Turn、3 次 Qwen 模型调用、write/read 各一次，均完成；30 字节 UTF-8 文件、读回结果和最终可见回复一致 |
| Advanced：`R8验收·Advanced·1016` | `r8-developer-1016.txt`，内容 `Helix Advanced 验收成功。` | 1/1：同样一个 Turn、3 次模型调用、write/read 各一次，均完成；文件、读回和最终回复一致 |

两渠道均核实重启前后 PID 不同，原调用/结果/模型调用/消息/文件哈希完全一致，无新增动作。删除临时模型入口后再次核验同样事实。旅程按两条计数，UI 操作批次不是额外业务通过数；没有真实模型失败或任务重试。临时 Mac 窗口置前请求未及时返回，已取消该请求；没有因此重启模拟器或并发操作两套 UI。

Standard 原 7 个会话、Advanced 原 12 个会话，以及两渠道原各 2 条消息均保留，Advanced 原 5 个订阅入口配置未改。最终保留两份升级后的主应用、两条验收会话及非敏感文件；只卸载独立测试 APK、删除本轮临时 API 入口、移除本轮转发并退出凭据进程。日常模拟器继续运行，停在 Advanced 的验收结果会话。临时入口移除后仍可看结果；继续发送新任务需要另选可用模型，这与保留查看状态不冲突。

证据归 `build/daily-r8-model-2026-10-16/`：`baseline.json`、两渠道 `*-installed.json`、`*-before-restart.json` / `*-after-restart.json` / `*-after-cleanup.json`、UI 截图/操作日志和 `final-result.json`。只读 SQLite 用于事实核对，没有写入数据库替代 UI/模型。原候选 BUILDINFO 保留原验收快照，本轮新增证明在上述补充证据中；未改生产源码或重新构建候选。

### 可见界面观察

本轮正常路径中工具状态、最终文本和重启历史可读，没有遮挡关键按钮或阻塞任务。另记录两处具体优化点，本次不改冻结包：

- 读取工具标题显示完整 `scope:ws-…` 内部引用，占用多行；建议主标题使用相对文件名，完整引用放详情中，保留来源/权限事实。
- 模型配置的“本机回环（数据不出设备）”不能覆盖代理转发场景，本轮就是反例。准确表达应是“连接本机回环地址”；仅凭 URL 不能断言后端不会转发。该文案准确性问题未在本轮修复，不将正常旅程通过解释为所有界面问题已清零。

真实模型补验范围到此收口；没有追加视觉、Mobile Use、PRoot/媒体、异常恢复、真机或长稳，也没有推送、远端 CI 或发布。
