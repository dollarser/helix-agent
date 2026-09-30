# Runtime 故障矩阵与修复验证（2026-09-30）

## 范围与基线

所有者要求“修复整个 runtime 故障矩阵”。本轮基于 `5fe01812` 加前轮审查修复的未提交工作树，覆盖 QuickJS 隔离/原生、PRoot 前台/后台 Job、手动 PTY、订阅 CLI 和设备内模型。保留并行工作，不提交或推送，不变更依赖版本和全局检查阈值。恢复观察和结果提交已局部提取；既有 composition root/PRoot 生命周期 owner 的大小检查及显式端口参数数量采用有理由的局部 suppression，没有关闭全局规则或削弱测试断言。

本轮设备与真实账号执行状态：**not requested**。没有启动模拟器、使用真机、执行设备 instrumentation 或调用真实模型账户。本文件中的主机可控故障测试、AndroidTest 编译与真实进程故障验收是不同证据，不互相替代。

相关入口：[审查报告](../../../reviews/2026-09-30/2026-09-30-full-audit.md)、[HXA-232](../../development/tasks/HXA-232.md)、[执行域](../../adr/runtime/001-execution-domains.md)、[终端/Job](../../adr/runtime/002-terminal-and-jobs.md)、[QuickJS](../../adr/runtime/003-quickjs.md)、[设备内模型](../../adr/provider/001-models-and-connection.md)。

## 结算准则

“取消请求已接收”“执行线程已退出”“原进程已死亡”“输出已验证”“副作用已核查”分别记录。前三者不自动证明后两者，消息入队、客户端超时、普通 RemoteException、空日志或未知 job 都不是退出证明。原始调用身份、输入哈希和 generation 不得被后续同名执行替代。

恢复只查询、收取或退役原执行器，不重新提交原代码、不延长原预算、不恢复撤销的权限。已授权的 native/PRoot 共享 UID 执行不是恶意代码沙箱；原进程死亡不能证明任意外部系统的动作已经撤回。物理占用释放不清除 ToolCall 的 UNKNOWN、副作用核查或原错误结果。

## 矩阵

证据入口标记：**H** 为可执行主机回归，是否通过以本文件验证记录为准；**D** 为 AndroidTest 源码/编译入口，本轮没有运行；**S** 为生产调用链复核，不能冒充故障注入。已有入口保留，不因本轮未运行而删除测试。

| ID | 执行域与故障 | 当前处理/本轮修复 | 证据入口与边界 |
| --- | --- | --- | --- |
| Q01 | QuickJS 请求非法、预启动取消、未授权 native | 仍在绑定前拒绝；参数不授予原生权限，不执行脚本 | H：既有 QuickJS/tool/preflight 测试；D：NativeJavascriptDeviceTest |
| Q02 | native 执行中取消、超时或控制消息失效 | 单向控制、独立进程看门狗、原始 Binder 死亡确认；未确认退出前保留真实 effect permit | H：JsNativeLifecycleTest；D：NativeJavascriptDeviceTest；内核/OEM 未实测 |
| Q03 | 主应用在 native 提交后死亡并重启 | 新增 NativeJavascriptOwnership：提交前持久化物理 owner；重启 NativeJavascriptRecovery 仅退役原 singleton，不执行旧代码；确认退出后 CAS 释放 | H：NativeJavascriptOwnershipTest、RuntimeOwnerDiskRecoveryTest（重新打开真实持久文件并复核损坏拒绝）；D：实际主进程 kill 矩阵仍需运行 |
| Q04 | 绑定拒绝、中断、空绑定、死亡回调 | 绑定阶段统一负责清理，空/死亡回调结束连接等待；失败不留下错误的已连接状态 | S：JsExecutionClient.bindInstance；D：既有协议/绑定用例 |
| Q05 | 部分 Parcel 解码失败、非法 PFD presence、非法/重复 EXECUTE | 解码器在失败时关闭已取得描述符；ExecuteEnvelope 统一 close；非法请求只 CAS IDLE→DONE，不覆盖正在执行的槽 | D：新增 JsEnvelopeOwnershipDeviceTest（2 个真实 Parcel/PFD 用例，无脚本） |
| Q06 | 输出超限、文件增长/截断、错误哈希 | 保留分配前可信大小检查、定长读取、EOF/hash/JSON 校验；异常不生成成功 | H：JsBoundedOutputTest、输出契约测试；D：大输入/输出协议测试 |
| P01 | 前台 PRoot 提交后调用者取消/超时 | 新增 ForegroundProotOwnership，在 submit 前持久化原身份；提交后的 Stop 返回带未知副作用事实的取消结果，不当成未执行 | H：ForegroundProotOwnershipTest；S：LinuxJobExecution 真实生产接线 |
| P02 | 前台 PRoot 宿主重启、查询不可用、孤儿记录 | 原物理 owner 阻止新写；有界观察只查询原任务；有效精确终态或可信更新 boot count 才释放；同 boot 的 ORPHANED、过期/缺失日志不释放 | H：ForegroundProotOwnershipTest；D：ProotOwnerProcessKillDeviceTest |
| P03 | 停止命令失败或回执属于其他任务 | query/cancel 都核验 jobId、executionId、输入哈希；失败不再显示“已请求停止”或“已停止” | H：ProotRecoveryIdentityTest；S：ProotJobRecovery |
| P04 | kill 超时但子进程仍存活 | publishTerminal 不再把存活进程记为可释放的 CANCELLED/TIMED_OUT；写 ORPHANED，保留 live reservation，工作线程继续等待真实退出 | H：ProotFaultTruthTest；D：实际进程组/子进程故障仍待验 |
| P05 | PRoot journal 截断、畸形、原子发布失败 | load 区分缺失与损坏；损坏不变成“从未提交”；取消非原子覆盖降级，记录发布必须原子移动；启动 sweep 遇损坏保留拒绝准入并记录诊断 | H：ProotFaultTruthTest；S：ProotJobStore/ProotOrphanSweep；断电持久性未验 |
| P06 | 同身份异输入、执行器拒绝队列、提交资源失败 | 重复记录核验身份/输入；入队失败显式拒绝并结算未执行记录；submit 的 PFD 从入口到 worker handoff 有唯一兜底清理 | S：ProotJobRunner；D：ProotJobRunnerDeviceTest |
| P07 | 输入归档过大、传输取消/预算耗尽 | 传输期间即应用现有归档字节上限并检查取消/预算，不等填满文件后才验证 | H：ProotFaultTruthTest 停止/正常拷贝；S：copyJobInput；阻塞内核读仍不保证固定返回时间 |
| P08 | 后台 Job 前台服务启动拒绝/owner death/租期耗尽 | 保留原 detached 持久绑定、应用持久 owner、FGS 准入、租期与终态查询；不将前台修复接到后台的第二套提交路径 | H：已有 detached/ownership 测试；D：DetachedJobLaunchDeviceTest、ProotDetachedOwnerDeathDeviceTest |
| P09 | 后台结果导入失败、重复 collect、输出过期 | 保留实际输出验证、原身份收取和幂等确认；未知效果不自动成功，不因原始文件过期重放 | H：已有 detached 结果回归；D：DetachedJobCollectionDeviceTest |
| P10 | 后台 Runtime 死亡/设备重启 | 保留完整 binding 和可信 boot 证据；同 boot 不由 UNKNOWN 释放，更新 boot 仅证明旧本地进程不能存活，不改任务成功状态 | H：已有 boot proof/ownership 回归；D：DetachedJobRebootDeviceTest、DetachedGoalRuntimeDeathDeviceTest |
| T01 | PTY 绑定失败/中断/空绑定 | connect 的成功 handoff 之前必须释放注册；失败实例不可重用；不会启动第二个终端来冒充恢复 | D：新增 PtyConnectionFailureDeviceTest |
| T02 | PTY 迟到回调、重复 connect、close 竞争 | one-shot 生命周期锁；close/disconnect 后回调不恢复 endpoint；重复 connect 的拒绝不关闭原有效连接 | D：PtyConnectionFailureDeviceTest；S：PtySessionClient |
| T03 | PTY 原会话死亡、租期失效或对账失败 | 保留 slot/generation 身份检查、原日志/元数据与显式终态；新客户端不复用已关闭实例，不自动重放用户命令 | H：既有 PTY 协议/状态测试；D：手动终端故障用例未运行 |
| C01 | 订阅仍在运行却提前读取成功归档 | 新增 SubscriptionCollectionPolicy，先查状态；RUNNING/STOP_REQUESTED 继续观察，仅成功终态拉取成功结果 | H：SubscriptionCollectionPolicyTest、RuntimeCollectionRecoveryTest |
| C02 | 订阅执行器忽略取消 | 新增 CANCEL_REQUESTED 非终态；收到取消不提前 ack/清理/放行下一任务，只有 worker 返回才落 CANCELLED | H：SubscriptionCancellationTruthTest、CodexPayloadJobTest |
| C03 | 订阅取消发生在流输出/编码/发布之间 | 停止后不发布新 stream delta，不写成功 payload；结果确认要求原 request/output 身份与实际终态 | H：CodexPayloadJobTest 的 late encoding/cancel 回归 |
| C04 | 合法但异任务的回执、查询状态异常 | wire 与 awaiter 核验原 job/request hash；非终态 reconcile 不算完成；不自动补发 submit | H：CliModelJobAwaiterTest；D：CliModelJobWireDeviceTest 新增错绑定回执 |
| C05 | 系统时间调整、非法 polling 参数 | 等待耗时使用单调时钟，非法负超时/零间隔拒绝；保留调用方明确选择的已有零超时契约 | H：CliModelJobAwaiterTest；S：PRoot 对应等待路径 |
| C06 | 订阅服务死亡/绑定失效/部分请求上传失败 | 保留请求一次性与失败后只查询；supervisor 清理早期拒绝连接、失效 callback 清引用，交付前检查 Binder 存活 | H：既有协议/请求编码；D：CliRuntimeRunningRecoveryDeviceTest、CliOwnerProcessKillDeviceTest、CliResultOwnerKillDeviceTest |
| L01 | 本地模型 IPC/管道读永久阻塞 | 新增 LocalRuntimeCalls：IO 2、控制 2、强退 1 分离有界无队列通道；调用方取消不在 handler 同步执行远端 IPC | H：LocalRuntimeCallsTest；S：LocalInferenceRuntimeClient 生产接线 |
| L02 | generate 已入传输队列但未返回时取消 | 未取得 submit ACK 不接受“当前无 active”作为退出；先终止并确认原进程，避免迟到 generate 在取消之后启动 | S：submissionAcknowledged 与非取消清理；D：真实 JNI/模型验证未运行 |
| L03 | 模型进程死后旧 handle 被复用 | 新绑定使旧 loaded/window 失效，生成不静默重连；清理快照绑定原 service/death/connection，不能清掉新的实例 | H：已有 local provider/handle 回归；S：LocalInferenceRuntimeClient |
| L04 | 模型取消/终止失败、异常管道关闭 | 未证实退出保留 active 所有权，但本地 pipe/temp file 仍进入 finally；退出记录只在确认之后写入 | H：LocalRuntimeCallsTest 的阻塞控制；S：finishGeneration/terminate |
| L05 | 模型服务拒绝 load/generate、worker 入队失败 | 入参 PFD 在验证失败也关闭；active/output 只随真正 handoff 保留，执行线程最终释放后才清除 | S：LocalModelRuntimeService；D：LocalModelRuntimeDeviceTest 未运行 |
| X01 | 任意 Runtime 超时后底层仍运行 | 保留原 ExecutionOwnership/实际线程容量；不得以 scheduler timeout 或 Future.cancel 作为物理退出 | H：ExecutionOwnershipTest、ToolExecutionCapacityTest、JsNativeLifecycleTest |
| X02 | 模型/流程试图借恢复扩权或重放 UNKNOWN | 所有新执行仍回原 Dispatcher/审批/预算；恢复结果是数据而非授权；仅释放物理占用不清除 review | H：原框架/恢复回归；S：本轮原身份恢复接线 |

## 本轮新增与保留测试

新增主机类：NativeJavascriptOwnershipTest、ForegroundProotOwnershipTest、SubscriptionCollectionPolicyTest、SubscriptionCancellationTruthTest、ProotRecoveryIdentityTest、ProotFaultTruthTest、LocalRuntimeCallsTest、RuntimeOwnerDiskRecoveryTest。既有 CliModelJobAwaiterTest/CodexPayloadJobTest 扩展断言，未删除或跳过失败用例。

新增 AndroidTest 类：JsEnvelopeOwnershipDeviceTest、PtyConnectionFailureDeviceTest；CliModelJobWireDeviceTest 新增错绑定回执测试。这些测试可在不接真实账号的 Android 环境执行，但本轮只构建。此前已有的真实 Runtime 测试仍保留，文件存在或编译成功不算设备矩阵通过。

## 验证记录

**最终联合门禁退出码 0，BUILD SUCCESSFUL（1m 41s，1013 个任务：66 executed、947 up-to-date）。** 为增量联合验证，不能说全部用例都在最后一次调用强制重跑；各修改相关套件已在本轮执行，通过后由 Gradle 在最终联合命令中复用兼容结果。主机门禁没有新增跳过，没有削弱或删除断言。早期清理接口引用和局部复杂度问题均已修订并复验。

```bash
python3 scripts/with-host-slot.py -- ./gradlew \
  test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:compileConsumerDebugAndroidTestKotlin :app:compileDeveloperDebugAndroidTestKotlin \
  :runtime:quickjs:compileDebugAndroidTestKotlin :runtime:cli-client:compileDebugAndroidTestKotlin \
  :runtime:proot-app:compileDebugAndroidTestKotlin \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --continue --configure-on-demand --no-configuration-cache --console=plain
./scripts/check-all.sh --source
```

源码门禁退出码 0：654 Markdown、215 HXA、35 当前 ADR；base/en/zh-rCN 1850 资源键一致，秘密扫描通过。可选 Spike 集合未启用，未运行 release/真实设备或真实账号矩阵，不将这些范围计为通过。

| 精确报告范围 | tests（含 skipped） | failures/errors | skipped |
| --- | ---: | ---: | ---: |
| app / Consumer Debug | 991 | 0 / 0 | 4 |
| app / Developer Debug | 1043 | 0 / 0 | 4 |
| runtime:quickjs | 101 | 0 / 0 | 0 |
| runtime:proot-app | 9 | 0 / 0 | 0 |
| runtime:proot-client | 23 | 0 / 0 | 0 |
| runtime:proot-core | 136 | 0 / 0 | 0 |
| runtime:proot-ipc | 45 | 0 / 0 | 0 |
| runtime:cli-app | 134 | 0 / 0 | 0 |
| runtime:cli-client | 47 | 0 / 0 | 0 |
| tools:framework | 221 | 0 / 0 | 0 |

相对本轮起点增加 **20 个独立主机用例**（8 个新测试类中 17 个，加 CliModelJobAwaiterTest 的 3 个），已通过。App 两渠道共享用例，不把共享测试相加当作独立场景。新增 **5 个 AndroidTest 用例**（两个新类共 4 个，加 CLI wire 1 个）仅编译。保留并增强既有 CodexPayloadJobTest 的取消/编码/确认断言。

统计脚本：`scripts/debug/2026-09-30/summarize-runtime-fault-matrix.py`。只读取显式模块的 `build/test-results/<task>`，不递归历史归档。精确报告时间、APK 大小及 SHA-256 保存在忽略目录 `build/runtime-fault-matrix-2026-09-30/host-summary.json`。

已构建但未安装的制品：

- `app/build/outputs/apk/consumer/debug/app-consumer-debug.apk`
- `app/build/outputs/apk/developer/debug/app-developer-debug.apk`
- `app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk`
- `app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk`

本记录是未提交工作树的 host 验证，不是签名 release、clean 正式 P5 或设备故障注入通过。

## 尚未关闭的验收边界

真实主进程 kill/restart、服务 Binder 死亡交错、设备重启、低内存/Doze/热/OEM、FD 压力及未受控子进程仍需实际设备矩阵。恢复耗时、原任务完成率、真实订阅远端取消/配额和生成质量需要相应账户与设备，不由主机替身证明。

内核不可中断等待、整个进程冻结、恶意共享 UID 代码及其主动脱离管理的子进程，不能通过 Java finally、普通线程超时或 Binder 死亡通知获得普遍的固定时间/完整退出保证。本轮保留拒绝并行副作用及 UNKNOWN，不声称引入了内核级进程树隔离或回滚能力。恢复观察预算耗尽后仍保留原事实，不将“无法确认”改成成功。

## 后续设备执行准则

取得当前明确的目标设备授权后，按 Q→P→T→C→L 分组运行，先验证普通成功与预启动拒绝，再依次注入提交前/提交后、输出前/输出后、确认前/确认后的死亡或取消。每个场景检查实际副作用次数、下一写操作是否被正确阻挡/释放、原身份和错误是否持久、输出是否仍可核验。记录目标设备/API/渠道及每例 pass/fail/skip；不能只检查界面无弹窗、应用未崩溃或最终有一条回复。
