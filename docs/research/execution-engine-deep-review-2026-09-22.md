# 执行引擎核心路径复审与优化顺序

2026-09-22 更新。本次复审源码基线：`9a9b25dd`；原审查基线：`645fa680`。本轮在214/215/216及218/219合入后重新核对调用链并运行独立探针，替换旧的“9项均待修”结论。该次复审未修改生产代码、数据库及依赖；同日后续修复见下节。

## 后续修复进展

同日所有者授权实施后，R2/R3/R6/R7已修复并整合至本地main（完整主机门禁及最终四象限80项通过），见[修复与验证记录](../bug-fixes/2026-09-22-engine-browser-convergence.md)。下文保留修复前`9a9b25dd`的审查与探针事实，不能再将其表格当作当前未修清单。R4仍属条件防护建议，R8完整终局通知故障注入仍待后续验证；本轮没有远端CI。

## 修复前结论

先处理调度和工具结果结算，再做结构拆分。**R1、R5、R9的原问题已修正；R4降为底层API防护建议；R8需按当前幂等保护重新定界。** 上一轮920项联合设备验证是特定交互/恢复范围，不能替代以下并发与写失败专项。

| ID | 当前状态 | 证据与优先级 |
| --- | --- | --- |
| R1 取消提前释放准入 | 原问题关闭 | 当前准入检查`!job.isCompleted`；新探针验证取消清理期仍保留owner |
| R2 后置读取越过排队写 | 仍可复现 | P1；真实Scheduler/Dispatcher批次启动顺序为`[first,last,write]`；没有证明越权或运行中互斥失效 |
| R3 跨批次漏唤醒 | 受控交错仍可复现 | P1；真实私有组件探针，不是整链死锁压力测试 |
| R4 旧快照覆盖终态 | 原Stop调用链已修正，底层仍无CAS | P2防护评估；DAO替身证明API薄弱点，未确认当前生产写者可触发相同竞态 |
| R5 对外发布未提交终态 | 原问题关闭 | ChatService在gate中结算后重新读取持久态，标签/live frame均使用该值；本轮静态确认 |
| R6 工具结果部分落盘与恢复遗漏 | 仍有静态失败路径 | P1；源码确认，尚未注入真实Room/文件写失败，不能称为真机已复现 |
| R7 DONE后仍等待EOF | 仍可复现 | P2；内存WireBody收到Completed/DONE后仍等待，超时取消才关闭；不代表真实服务时延测量 |
| R8 提交后异常再次进入终局路径 | 风险收窄，仍需故障注入 | P2；Coordinator已终态会早退，不能再断言成功会被二次写成失败；投影/清理失败与续跑通知仍需验证 |
| R9 接收前清空草稿 | 原路径已被214/216替换 | 持久回执、草稿身份与Queue已交付；见[214](../completion-records/HXA-214.md)、[216](../completion-records/HXA-216.md)及[联合验证](../evidence/development/branch-convergence-2026-09-22.md) |

## R2：恢复批内写屏障，不取消合法读并行

位置：[ToolScheduler.admitNext/tryClaimSlot](../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolScheduler.kt)（当前229–267行）。准入遍历所有未提交项，只和运行集比较。`read A → write B → read C`中，B因A占槽等待，C仍可获准。探针实际只证明启动顺序；C读到B之前的数据是合理影响推断，尚未通过共享值断言动态验证。

运行中footprint检查仍受锁保护，因此这是批内依赖/屏障问题，不是授权绕过或已运行读写互斥失败。现有注释承诺exclusive full barrier与queue-order fairness，需要实现与契约一致。

最小优化：将前置排队exclusive纳入本批次准入，保持独立只读并行。验收增加同资源读→写→读且最后读看到新值、两个写之间的读、取消屏障、独立读并行；不额外承诺所有会话全局严格FIFO。

## R3：把检查与等待纳入同一唤醒协议

位置：[ToolScheduler.scheduleReservedBatch/releaseSlot](../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolScheduler.kt)（159–173、304–312行）。B准入失败后、读取signal之前，A释放槽并替换/完成旧signal；B拿到新的未完成signal，此时自己的future还未提交。若没有下一次释放，等待可能一直持续。探针验证旧signal已完成、新waiter未完成，而槽位已经可获取；它不是完整`scheduleBatch`端到端卡死测试。

最小优化：准入前捕获signal/版本，并在一致的锁/复查协议下登记等待；或复用锁关联条件等待。补完整两批次latch测试，精确控制“检查失败→释放→订阅”窗口，并验证取消能唤醒等待者。不要只补future回调而保留读取signal之前的空窗。

## R6：让每个已执行槽位都有可恢复的结算事实

位置：[ChatToolCalls.runToolBatch](../../app/src/main/kotlin/com/helix/app/chat/ChatToolCalls.kt)（241–295）、[ChatToolSettlement](../../app/src/main/kotlin/com/helix/app/chat/ChatToolSettlement.kt)（36–90）、[RecoveryCoordinatorApp](../../app/src/main/kotlin/com/helix/app/recovery/RecoveryCoordinatorApp.kt)（73–125）。

batch执行后按序`map`写结果。成功路径先写ToolCall COMPLETED，再append正文/结果、markVerified、投影UI，最后结算Goal预算；外层没有覆盖这些写入的统一事务。局部写失败会中止后续槽位结算，随后Turn终局写仍可能成功。于是可能出现COMPLETED无结果、后续已执行槽位仍未结算；启动恢复只扫描非终态Turn，不能保证找回终态父级下的遗漏。

这里假设局部或瞬态写失败，而非“磁盘永久不能写但终局总能成功”。准备阶段逐项失败也需要覆盖。本轮未做Android故障注入，不将这条静态路径升级为实际用户数据损坏报告。

最小优化：每槽状态、结果索引、验证状态和预算以现有Room事务提交；正文先物化，再提交引用并清理孤儿，不能声称Room回滚文件。每槽失败留恢复依据，不能因一个失败丢掉其他已执行结果；对终态父级下的不一致子记录也能对账。复用现有call/result身份，不另造通用journal、执行框架或自动重放机制。

验收：在append、verify、budget和第二槽分别注入失败并重启，核对每槽结果、引用和预算恰好一次结算，未知外部效果只核查、不重放。

## R7：协议结束后关闭读取，保留尾部usage

位置：[WireModelProvider.stream](../../provider/api/src/main/kotlin/com/helix/provider/api/WireModelProvider.kt)（131–146）、[ChatCompletionsStreamDecoder.handle](../../provider/openai-chat/src/main/kotlin/com/helix/provider/openai/chat/ChatCompletionsStreamDecoder.kt)（119–149）。chunk回调持续返回true，decoder忽略`[DONE]`。兼容服务在协议结束后延迟关闭响应时，flow仍等待EOF；普通立即EOF服务不暴露此问题。

不能收到Completed就立即切断，因为finish之后还可能有usage。最小优化是显式区分协议终止和单个事件完成；覆盖finish→usage→DONE、跨chunk DONE、合法EOF无DONE、bare DONE无finish及取消/错误关闭。若调整公共decoder端口，同时核对Responses/Anthropic各自结束语义，不把Chat协议规则硬套到其他Provider。

## R4/R8：先补证明，再决定防护与拆分

R4底层：[TurnRepository.updateState](../../core/storage/src/main/kotlin/com/helix/core/storage/repository/TurnRepository.kt)（69–85）仍按传入快照校验，[TurnDao](../../core/storage/src/main/kotlin/com/helix/core/storage/dao/TurnDao.kt)（59–69）仍仅按id更新。替身探针能重现旧值覆盖，但当前`cancelTurn`已在`turnGate + storage.withTransaction`内重新resolve；Coordinator迁移/终局和启动恢复也在事务内读取。**旧文“当前stopTask可把COMPLETED写回CANCELLING”应撤回。** 是否加CAS，应先核查全部写者；采用时用真Room验证两个写者、过期step和失败行数，不能只重复DAO替身实验。

R8：[ChatService.runTurn/terminalize](../../app/src/main/kotlin/com/helix/app/chat/ChatService.kt)（3612–3744）仍将终局与后置投影放在同一异常域，但[TurnCoordinator.terminalize](../../app/src/main/kotlin/com/helix/app/agent/TurnCoordinator.kt)（497–503）在已终态时早退，ChatService随后读取持久结果。因此原文“再次提交FAILED必然非法/改写原结果”过强。剩余问题是提交后清理、投影、提醒、队列drain中途失败的幂等与补偿；在提交后/释放前/通知中注入异常，再确定最小提交结果对象与通知重试边界。

R1当前保留按turnId释放的保护，正常持久终局后允许显式释放占用，不必为解决旧isActive问题再增加一套会话占用状态机。R5现有重新读库已解决原来的投影不一致；返回不可变receipt可作为后续简化，但不再列为未修复P1。

## 后续优先顺序

1. **R2/R3调度修复**：小范围修改既有Scheduler，转成禁止错误顺序和漏唤醒的生产回归。
2. **R6故障注入及结算修复**：由熟悉Room、结果引用与恢复的核心所有者负责；R8相关的提交后边界一并验证。
3. **R7协议结束修复**：范围相对独立，可与上述工作并行；保留usage和Provider差异。
4. **R4条件防护与结构优化**：在证明缺口后决定CAS/统一写端口；再按状态所有权提取app内执行宿主。不先拆大文件或新增Gradle模块。

以上是待安排的维护建议，不代表本轮已修复或新HXA已接受。功能开发顺序仍见[工作计划](../development/next-work-plan.md)：129安全替换与会话启停、217轻量请求来源记录均有自己的设计门槛。市场失败保留归129，不另起同义任务；结构与文档治理归[结构审查](project-structure-and-engine-review.md)。

## 本轮可复跑证据

审查工具位于[deep-review](../../scripts/debug/2026-09-22/deep-review/README.md)。必须使用新的ignored输出目录；prepare拒绝复用已有基线，verify核对1944个生产/测试/构建文件的SHA及新生成探针XML，不能拿旧报告冒充本轮。

```sh
python3 scripts/debug/2026-09-22/deep-review/prepare-review.py --output build/document-review-convergence/probes-main
python3 scripts/debug/2026-09-18/with-host-slot.py -- ./gradlew \
  -I scripts/debug/2026-09-22/deep-review/review.init.gradle \
  -PreviewOutput=build/document-review-convergence/probes-main \
  :core:agent:test --tests '*AdmissionReviewProbe' \
  :tools:framework:test --tests '*SchedulerReviewProbe' \
  :core:storage:testDebugUnitTest --tests '*StorageReviewProbe' \
  :provider:openai-chat:test --tests '*ProviderReviewProbe' \
  --no-configuration-cache --no-daemon --max-workers=2 --no-parallel --rerun-tasks
python3 scripts/debug/2026-09-22/deep-review/verify-review.py --output build/document-review-convergence/probes-main
```

实际使用JDK17和配置的Android SDK：Gradle exit0，35个任务实际执行，构建18秒；5个探针、0失败/错误/跳过，生产文件SHA变化0。这里**R1通过表示修复不变量成立；R2/R3/R4/R7通过表示诊断行为仍可复现**，不是5项产品缺陷都已修好，也不能把这些诊断断言永久接入CI。

新证据位于ignored `build/document-review-convergence/probes-main/`，含HEAD、源码/探针SHA、Gradle日志、XML及verification.json。旧基线曾执行683项（含5个旧诊断探针），保留于 `build/deep-review/`；这次只执行5项，不重复宣称683项通过。DAO/transport使用替身，R3使用反射受控交错；本轮没有新的Room设备、真实网络或性能结论。
