# 提交后独立复审：取消、执行退出与结果确认

## 2026-09-30 继续优化：F1～F6 实施记录

所有者在复审后要求“继续优化”“继续”，本轮据此落实 F1～F6。基线仍为 `d9bac501`，接续工作区中尚未完成验证的修复并复核实际接线，没有重新提交或推送。下方折叠内容是修复前的独立复审及当时失败证据，不代表当前实现状态。原有一次性 debug 脚本与其他工作区内容保留。

### 已实现的修复与后置条件

| ID | 修复 | 回归与约束 |
| --- | --- | --- |
| F1 | 每个 Job 在 worker 启动前持有 `SubscriptionCancellation`；模型对象和实际 HTTP Call 的迟到登记都继承停止意图，停止后不再进入新模型请求。删除全局 `activeModel`/旧 `cancelExecution` 取消入口，生产与测试复用同一登记路径。 | 原 `PostCommitCancellationAuditTest` 的取消后请求数仍断言为 0；PENDING 取消、流式输出/编码时取消、资源只关闭一次和下一任务不继承旧取消均保留。 |
| F2 | `ProotJobAwaiter` 将用户 Stop 与纯等待超时分开；轮询前后、休眠中断和查询抛中断时向原 Job 投递一次取消。控制绑定前暂时清除中断标记，结束后恢复；取消失败返回未确认而不是已退出。 | 原身份查询/取消、晚到回包、查询异常、普通完成、纯预算耗尽和非法参数；Linux 工具继续核验 job/execution/input 哈希，未证明退出不释放持久占用。 |
| F3 | `ProotProcessOwner` 在 `Process` 创建后立即接管，不依赖 PID、日志线程或 watchdog 初始化成功；终态发布读取这个实际 owner。失败清理等待真实退出，进程组停止失败仍尝试原 Process 停止。 | 无 PID、启动后初始化失败、group kill 失败、底层 destroy 返回但仍存活，以及启动被拒绝；不能把发送停止当成退出证明。 |
| F4 | 本地输出与 Runtime ACK 分开。`CliResultAckQueue` 在首次 ACK 前原子保存待确认身份；`SubscriptionAcknowledgements` 在应用重开及新确认请求时驱动有界重试，独立于聊天页面是否显示输出。完整终态身份和确认时间戳匹配后才删除待确认项。 | 实际 Runtime Store/Runner 与 host queue 组合测试覆盖 ACK 已落盘但回包丢失、队列重新打开、不重新生成输出，以及 128 条满 Journal 的确认清理后恢复容量；异任务、异输出、缺确认戳与损坏/错命名文件都不能清除证据。 |
| F5 | CLI Store 改用同目录临时文件、文件刷盘与 `ATOMIC_MOVE`，移除 rename 失败后的原地覆盖复制。 | 原子替换失败保持旧字节不变，新目标保持不存在；正常替换可重新读取。未提交请求的已知临时记录可回收，未知文件仍保留。 |
| F6 | CLI journal 在读前检查文件类型/长度，打开时不跟随符号链接，最多读取现有 8 KiB 上限加一字节，再进行严格 UTF-8 和 Codec 结构校验。 | 精确上限、读取期间增长、超大稀疏文件、无效 UTF-8、目录与符号链接。此限制只针对小型记录，不重新引入模型输出大小配额。 |

### 补齐的跨层问题

取消只在任务状态锁内提交停止意图并捕获原资源，实际 `close()` 在锁外执行，避免关闭过程等待 worker、worker 又等待状态锁的死锁。关闭 Runner 后拒绝新提交，旧取消完成不能关闭后续任务的资源。

恢复 UI 不再把 `output != null` 当成 ACK 成功；确认失败可以重试，Runtime 暂时不可用时仍展示已经验证的本地输出，不同时标成输出丢失。ACK 文件中合法但与文件名不一致的 jobId 在传输前拒绝。

PRoot 的执行占用检查复用同一辅助函数，资源退出清理和日志结束独立；确认恢复独立于 Provider 创建/能力探测。修复不修改全局 detekt 阈值，不新增依赖、不放宽审批、不重放原命令或模型请求。

### 本次验证

**最终联合门禁退出码 0，BUILD SUCCESSFUL（2m 43s；1013 个任务，104 executed / 909 up-to-date）。** 四个 Runtime 模块曾单独强制重跑，86 个任务全部 executed；随后增加查询中断与 App ACK 组合回归，最终联合命令重新执行受影响套件，其余兼容结果由 Gradle 复用。不是全部用例在最后一次命令中强制重跑。

早期格式检查的两处长行，以及联合门禁中的五项局部结构问题，均已通过拆分职责/条件与重排格式修正，再用相同联合命令通过。没有删除原红灯反例，没有新增跳过或放宽全局阈值。

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

源码门禁退出码 0：654 Markdown、215 HXA、35 当前 ADR；835 个生产源码参与 i18n 检查，base/en/zh-rCN 1850 资源键一致，secret scan 通过。`git diff --check` 通过。可选 Spike 集合未启用，未运行 release 门禁。

| 精确测试报告范围 | tests（含 skipped） | failures / errors | skipped |
| --- | ---: | ---: | ---: |
| App Consumer Debug | 993 | 0 / 0 | 4 |
| App Developer Debug | 1045 | 0 / 0 | 4 |
| CLI App | 141 | 0 / 0 | 0 |
| CLI Client | 54 | 0 / 0 | 0 |
| PRoot App | 12 | 0 / 0 | 0 |
| PRoot Client | 31 | 0 / 0 | 0 |

本次聚焦 **8 个测试类、27 个独立主机回归用例，全部通过且无跳过**：PostCommitCancellationAuditTest（3）、SubscriptionCancellationTest（2）、SubscriptionAckIntegrationTest（2）、CliJobRecordFileTest（4）、CliResultAckQueueTest（3）、ProotProcessOwnerTest（3）、ProotJobAwaiterTest（8）、SubscriptionAckCollectionTest（2）。最后一个在 App 两渠道共享运行，只计一次。原取消反例由红变绿，关键断言仍是取消后请求数 0；还保留了旧的取消/编码/结果清理回归。

统计入口：`scripts/debug/2026-09-30/summarize-post-commit-fixes.py`，读取精确 `build/test-results/<task>`，报告保存在忽略目录 `build/post-commit-fixes-2026-09-30/host-summary.json`。历史测试缓存与最终增量复验分别记账，不把 App 两渠道共享用例相加为独立场景。

双渠道 Debug APK 与 AndroidTest APK 构建目标通过，制品位于 `app/build/outputs/apk/` 的 consumer/developer 对应目录；未安装或执行设备用例。所有本次验证 Job 已正常结束，代码和文档未提交/未推送。

### 未验收的环境边界

设备、真实模型/订阅账户均为 **not requested**。本次没有启动模拟器、使用真机、运行 instrumentation 或消耗真实服务额度；AndroidTest 编译与 APK 构建不是设备通过。实际 Binder 阻塞、主进程骤停、断电/OEM/Doze、未受控子进程与远端已受理动作仍需各自证据。

已持久化的 ORPHANED 不因随后某个 Process 退出而改成成功；同启动周期内缺少完整退出证明仍保留物理占用与 UNKNOWN。ACK 重试有界，持续不可用时保留待确认项，待后续恢复触发或应用重开再观察；不承诺永久故障下无限重试或固定时间恢复。

<details>
<summary>d9bac501 修复前复审与原始失败证据（历史）</summary>

## 基线与操作范围

用户要求先提交此前修复，再重新评估剩余 bug。本轮已将 155 个代码、测试、构建和文档文件提交为以下 8 个本地提交；没有 push。生产代码复审基线为 `d9bac5019aca63b14d2cee47f8a30ed9091681f4`。原有 48 个一次性 debug 修改脚本保留未跟踪，不纳入提交，也没有删除。

| 提交 | 范围 |
| --- | --- |
| `4d855576` | Core、存储、Goal 与执行所有权 |
| `88eec58d` | QuickJS、设备内模型、有界 IPC |
| `c5f2a342` | PRoot 身份、取消、恢复及 PTY |
| `e3519720` | 订阅状态与回执校验 |
| `3b6fc68c` | 会话配置顺序、恢复、问题回答 |
| `14ec7234` | Provider 探测、工具发现、文件/视觉边界 |
| `4d7da5c6` | UI、图片预览、浏览器 |
| `d9bac501` | 审查、ADR、Runtime 矩阵及两个证据统计脚本 |

提交后未修改生产代码。本报告与新诊断测试属于复审新增工作，暂未提交。没有运行模拟器、真机或真实模型/订阅账户。本报告不是全项目无缺陷证明，也不沿用历史设备通过结论。

## 结论

重新核实后仍有以下具体缺陷。P1 表示影响 Stop、执行占用或关键任务链路；P2 表示故障条件下的持久化/资源问题，不表示每次普通运行都会触发。只有 F1 本轮进行了可控主机复现，其他条目是读取当前生产接线得到的静态结论，必须保留各自触发条件。

| ID | 级别 | 问题 | 证据强度 |
| --- | --- | --- | --- |
| F1 | P1 | 订阅在 RUNNING 但 backend 尚未登记时取消，迟到 backend 仍可能发起请求 | 实际 runner + 与服务一致的注册/取消回调，主机复现 |
| F2 | P1 | 前台 PRoot 在 awaitTerminal 轮询期间 Stop，只结束等待，没有 cancel 原 Job | UI → Turn 取消 → 工具回调 → PRoot 客户端静态链路 |
| F3 | P1 | PRoot 已创建子进程但尚未放入 liveJobs 的失败窗口，仍可能提前发布可释放终态 | 有条件静态缺陷；真实进程故障未注入 |
| F4 | P2 | 订阅结果 ACK 失败被忽略，存在本地输出就不再重试 ACK | 正常结果路径及恢复路径同时存在 |
| F5 | P2 | CLI journal 仍以原地复制作为 rename 失败后的降级发布，破坏原子写约定 | 当前生产 Store 的失败分支 |
| F6 | P2 | CLI journal 的大小校验发生在整文件读取/分配之后 | 当前生产 Store → Codec 的调用顺序 |

## F1：取消不能覆盖迟到的 backend 注册

位置：`runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexPayloadJob.kt:226-237,282-302`；`runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CliRuntimeService.kt:31-44,65-77`。

Runner 先落 RUNNING，随后调用 executeModel。服务在解析 payload、准备前台运行及创建模型对象之后才 `activeModel.set(model)`；取消回调仅执行 `activeModel.getAndSet(null)?.close()`。如果取消发生在 activeModel 仍为空的窗口，持久状态虽然变成 CANCEL_REQUESTED，但回调没有可关闭对象。随后创建的模型没有检查已经发生的取消，仍然运行请求。worker 返回后又将记录结算为 CANCELLED。

影响：Stop 之后仍可产生新的模型网络请求、外发和额度消耗；最终 CANCELLED 不能反映该次停止意图曾被漏传。本轮没有证明任何真实收费或数据外发，复现使用计数器代替网络。

主机复现文件：`runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/PostCommitCancellationAuditTest.kt`。同一测试类两个用例：

- `cancellationBeforeBackendRegistrationMustPreventLateRequest`：失败；取消后的新请求预期 0，实际 1，且最终记录为 CANCELLED。
- `cancellationWhilePendingDoesPreventExecution`：通过；当前 PENDING 取消分支正确，不能把它再次列为 bug。

建议：每个 Job 持久/进程内取消意图应在创建 backend 前建立，并与 backend 安装进行统一协调。晚安装的 backend 必须立即获知之前的取消，不以瞬时 activeModel 是否为空决定取消是否已经处理。只在 runJob 开头增加一次检查仍有相同竞争窗口。

## F2：PRoot 轮询中的 Stop 没有发送到 Runtime

位置：`app/src/main/kotlin/com/helix/app/chat/ChatService.kt:3289-3332`；`app/src/developer/kotlin/com/helix/app/proot/LinuxJobExecution.kt:246-271`；`runtime/proot-client/src/main/kotlin/com/helix/runtime/proot/client/ProotJobClient.kt:228-248`。

Chat Stop 设置取消信号并取消执行协程。LinuxJobExecution 只有提交刚返回后的那一次 isCancelled 检查会调用 client.cancel。进入 awaitTerminal 之后，取消仅通过 shouldContinue 结束循环，或通过 InterruptedException 返回 TimedOut；这两条退出路径都没有向原 Job 发送 cancel。调用者把它按超时/中断返回，后续自动恢复只查询，不代替用户发送 Stop。

影响：聊天停止等待后，原前台命令仍可能继续到自己的执行 deadline，并继续产生副作用。持久 owner 防止了新写重叠，但没有完成停止请求的投递。不是所有 Stop 都失败：提交刚结束就取消的早期分支已有 cancel。

建议：将用户取消与等待预算耗尽分开建模。已提交任务的用户取消必须通过受控原身份路径投递一次 cancel，并保留取消请求、退出确认和副作用事实的区别；通知失败仍保持 UNKNOWN，不能直接报告已停止。

## F3：已启动进程的所有权登记过晚

位置：`runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt:453-509,563-591,763-779`；`app/src/developer/kotlin/com/helix/app/proot/ForegroundProotOwnership.kt:30-37,75-76`。

builder.start 返回后，仍需解析 childPid、创建并启动两条流读取线程、保存元数据和注册 watchdog，最后才赋值 live 并写 liveJobs。childPid 不可取得时只调用 destroyForcibly，然后直接 terminalFailed；此时没有等待实际退出，live 仍为空。登记前的其他异常也可能进入同一缺口。

publishTerminal 只看 liveJobs 的 process.isAlive；缺少 liveJobs 条目被解释为 false。finally 的退出等待也只处理非空 live。因而这些路径可以将仍未确认退出的进程发布为 FAILED，宿主随后把它当作可释放占用的终态。

建议：Process 一旦创建就立即有清理 owner，不以 PID 解析、日志线程或 watchdog 注册成功作为持有 Process 的前提。未知登记状态不能等同已退出；精确进程组追踪与原 Process 的退出证明分别处理。测试应注入 start 已成功但 PID 解析失败/后续资源分配失败。

## F4：结果可读不等于 ACK 已确认

位置：`app/src/developer/kotlin/com/helix/app/provider/SubscriptionResultRecovery.kt:32-42`；`app/src/developer/kotlin/com/helix/app/provider/CodexSubscriptionProvider.kt:232-242`；`app/src/main/kotlin/com/helix/app/chat/AutomaticRecoveryCollection.kt:48-69`。

正常请求与恢复请求都调用 acknowledgeResult，却不检查返回的 StateOutcome。恢复只要取得本地 output 就返回 COMPLETE，后续刷新也因 output 非空跳过确认。ACK 传输失败不会丢失已持久化结果，但 Runtime 的未确认记录和 payload 得不到自动清理。

影响：反复发生后可积累 unreconciled 记录，最终触及 CLI Store 的 128 条记录上限；即使本地结果齐全，后续任务仍可能被 JournalFull 拒绝。单次失败并不意味着立即满额。

建议：将本地结果可用与远端确认状态分开持久化，允许幂等 ACK 重试；不能为重试 ACK 重新生成模型输出或重复外部动作。

## F5：CLI 记录发布仍有非原子降级

位置：`runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexModelJob.kt:26-43`。生产 CodexPayloadJobStore 的 records 字段实际使用这个 Store，不是仅供旧测试使用的代码。

renameTo 失败后，代码调用 tmp.copyTo(file, overwrite = true)。原记录可能先被截断，复制期间发生异常、磁盘写满或进程死亡会留下部分记录；这与错误信息所称的 atomic write 不一致。PRoot Store 已移除此降级，CLI 尚未同步。

建议：同目录临时文件、刷盘和原子替换，替换失败保留原文件与失败证据；不以覆盖复制冒充原子性。没有进行真实断电测试，不能把静态风险说成已发生的数据损坏。

## F6：CLI journal 校验晚于大分配

位置：`runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexModelJob.kt:20-23`；`runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelJobRecord.kt:72-74`。

load 先 file.readText()，Codec 随后才通过 encodeToByteArray().size 检查 8 KiB 上限。超大或错误增长的 record.json 会先完整分配字符串，再分配 UTF-8 数组；大小上限只能拒绝已读数据，不能限制读取峰值。普通合法记录不受影响，本轮未制造 OOM。

建议：先检查文件类型和可信长度，再有界读取最多上限加一字节；仍保留读取后长度/结构校验以覆盖读取期间增长。不要只新增一次 file.length 检查而保留无限 readText。

## 不列为新 bug 的已排除项与优化边界

- PENDING 订阅取消已直接落 CANCELLED，新主机对照用例通过；不能因 runJob 的早退分支就推断它必卡在 CANCEL_REQUESTED。
- 前台 PRoot observer 当前将仍保留占用的普通查询映射为 RUNNING，不是上轮旧的“三次运行中即失败”模式。
- 本地模型 Provider 缓存 terminal，等待 runtime.generate 返回及取消/退出处理之后才向上发布终态。不能仅看到 client 内 emit 就声称业务提前成功。
- 问题回答家族查询用 instr 精确前缀，不用 SQL LIKE；生产工具持久 id 与 callId 在当前写入点相同。未发现足以重新打开这些旧缺陷的证据。
- ORPHANED 在真实 Process 后续退出后仍不提供新的持久退出证明，可能迫使宿主等到设备重启；值得补独立退出回执，但不能改写原未知副作用或简单释放 owner。
- 同步 PRoot/CLI Binder transact 和原生极端阻塞仍需设备故障注入；外层轮询 deadline 不自动使单次同步 IPC 有界。这里只保留风险，不声称本轮已复现 Binder 饥饿或 OEM 故障。

## 本轮实际验证

提交前对最终代码树执行联合主机门禁：根 test、detekt、spotlessCheck、App 双渠道 lint、App/QuickJS/CLI Client/PRoot App AndroidTest Kotlin 编译。退出码 0，`BUILD SUCCESSFUL`；859 个任务中仅 2 个执行、857 个 up-to-date，不能称为全量强制重跑。提交只固定上述相同代码树，没有修改生产源码。

提交后执行 `./scripts/check-all.sh --source`：退出码 0；654 Markdown、215 HXA、35 ADR、多语言 1850 键及 secret scan 通过。这个数字来自新增本报告之前。

随后临时新增上述诊断测试，执行：

```bash
python3 scripts/with-host-slot.py -- ./gradlew \
  :runtime:cli-app:testDebugUnitTest \
  --tests com.helix.runtime.cli.app.PostCommitCancellationAuditTest \
  --configure-on-demand --no-configuration-cache --console=plain
```

退出码 1，**2 tests / 1 failure / 0 errors / 0 skipped**。失败断言为 `expected:<0> but was:<1>`；不是编译或依赖错误。测试直接使用实际 CodexPayloadJobRunner 和与 CliRuntimeService 一致的 activeBackend 注册/关闭方式，网络请求仅以计数器替代。

诊断文件保留在工作区且未提交，失败未修复、未弱化断言。**因此当前含诊断测试的工作区并非全绿；常规基线通过与新增反例失败必须分别记账。** 本报告及该测试不代表用户已授权实施这些新修复。修复优先级：F1/F2 的 Stop 投递，F3 的启动即持有 Process，再处理 F4/F5/F6 的确认与存储边界。

</details>
