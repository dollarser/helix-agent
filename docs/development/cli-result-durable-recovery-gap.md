# Resolved normal-path gap: CLI result durability before acknowledgement

Status: resolved for current normal subscription path; legacy reconcile boundary retained
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: CLI Runtime Binder, CLI client, subscription result recovery

## Problem

CliRuntimeServiceBinder.writeReconcile calls finishReconcile after CliPfdChannel.write succeeds. CodexPayloadJobStore.finishReconcile deletes request/output payloads and records reconciledAtEpochMillis. A successful pipe write does not prove that the main App has verified and durably imported the result. Process death between those boundaries can leave only terminal metadata.

## Impact

A successful original Job can become impossible to recover without a new request. Resubmission is not an acceptable recovery strategy. Existing state/query/stop tests do not prove result durability.

## Root cause

The existing transaction couples reading bytes to acknowledging their durable receipt. Runtime transport completion is used as a substitute for the main App's persistence confirmation required by ADR-0007.

## Fix and invariants

First prerequisite: additive JOB_FETCH_RESULT transaction 6 and CliModelJobClient.fetchResult use the existing bounded PFD and output SHA-256 decoder while retaining payloads and the unreconciled record. Repeated fetch does not submit, cancel, acknowledge or delete. Unknown transactions on an older Runtime fail through the existing unavailable result path; this is not an automatic fallback to destructive reconcile.

The current subscription await path now fetches without deletion. For owned successful calls, SubscriptionResultStore verifies the Turn/modelCall/Job/request binding and output hash, atomically stores the encoded event bytes under the private workspace, registers a session-owned artifact and verifies its contents before acknowledgement. Transaction 7 checks both request and output hashes and only clears terminal records; repeated valid acknowledgement retains the original receipt. Probe outputs are intentionally ephemeral. The legacy reconcile transaction remains available for old callers and test cleanup; current normal subscription calls no longer use it. Crash-window and recovery UI validation are recorded in the dated evidence below.

## Alternatives considered

A pipe-write success, delay before deletion, or UI confirmation before durable storage cannot prove receipt. Automatically repeating the model request changes the original Job and may duplicate cost or effects.

## Regression verification

CliResultFetchDeviceTest submits account-free helix-fixture requests through production Binder, waits for terminal state, fetches each result twice and compares the full record/events while checking reconciledAtEpochMillis remains null. API29/36 times four adapters passed 8/8; explicit legacy reconcile is used only for test cleanup. Evidence: build/main-verification/cli-result-fetch/result.json and device logs, with installed APK hashes.

CLI client JVM30/30 and Runtime JVM102/102 passed without skips; developer main/test and Runtime builds, root lintDebug, Spotless and Detekt passed. Logs: cli-result-fetch-build.log and cli-result-fetch-jvm.log. These tests prove the new non-destructive transport primitive; they do not reproduce a main-process death during durable result import or fix legacy acknowledgement.

## Residual risk

The deletion window remains for legacy reconcile callers. The current owned normal path has durable storage before acknowledgement; the dated evidence below covers process-kill windows and user-facing readback. Historical pending statements describe their earlier checkpoints. No account credentials were read or reset.

## Related records

- [Runtime lifecycle decision](../adr/0007-companion-runtime-lifecycle.md)
- [Current TODO](main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)


## 2026-09-07 persistence and acknowledgement evidence

API29/36 four-adapter fetch/persist/wrong-hash rejection/repeated acknowledgement tests passed 8/8. Local events remain readable after Runtime payload deletion, and their artifact path participates in session privacy deletion. The four normal Provider/Chat contracts on both APIs passed another8/8 with assertions for persisted artifact hash and acknowledged Runtime record. CLI client30/30 and Runtime102/102 JVM tests passed, zero skips; developer/Runtime builds, root lintDebug, Spotless and Detekt passed. Evidence: build/main-verification/cli-durable-ack-summary.json, cli-durable-ack/, cli-durable-normal/, cli-durable-ack-build.log and cli-durable-ack-jvm.log. These are normal and repeated-operation tests, not main-process kill evidence.


## 2026-09-07 CLI 结果交接三窗口强杀与本地回读

SubscriptionResultStore增加按原Turn/modelCall/Job/request绑定回读，校验artifact归属、size上限及输出SHA-256后解码，保存时也核对modelCall归属。CliResultOwnerKillDeviceTest使用生产Runtime与真实私有结果存储，在已fetch未持久化、已持久化未确认、已确认三阶段由宿主SIGKILL主App，恢复时从Runtime或本地取回同一结果，持久化/确认可重复，保持一个modelCall和一条job_prepared记录。测试种入调用绑定而非完整Goal运行，不能替代生产Goal预算/UI矩阵。

API29/36三窗口合计6次SIGKILL、12次恢复通过；新增本地文件等长篡改拒绝测试后，两API四适配器共8/8通过，恢复原字节后可读。结果文件纳入session删除清单；合成fixture清理完成。证据 `build/main-verification/cli-result-owner-summary.json`、六组cli-result-owner-api*-*/及cli-result-read-corruption/。构建、root lintDebug、Spotless、Detekt与Python语法检查通过，日志cli-result-owner-build.log和cli-result-read-build.log。

成功结果的完整恢复界面仍待接线，结果交接缺口保持open；这里只完成存储/Runtime层强杀和篡改校验，不新增模型请求、不执行恢复事件中的工具、不推进Goal完成状态。

## 2026-09-07 CLI 结果恢复界面与启动刷新修复

显式“取回模型回复”经ChatService串行请求、developer恢复服务核对原Turn/modelCall/Job绑定；优先回读已验证本地artifact，否则fetch后持久保存再精确确认。UI只展示TextDelta/Refusal正文，分页保留全部文字并支持选择，分页不拆开emoji代理对；不执行事件中的工具、不回填Agent上下文、不将中断Turn/Goal完成。consumer接口默认无Runtime操作。

API36首次恢复发现数据库已INTERRUPTED而页面条目未更新，已修复启动恢复提交后的ChatService刷新通知，见 [缺陷记录](../bug-fixes/2026-09-07-recovery-open-conversation-stale.md)。确定性回归两API各1/1；修复后生产ChatScreen三个交接窗口共6kill/12次实际查询/取回/正文断言通过，Turn/modelCall仍INTERRUPTED。证据 `build/main-verification/cli-recovered-reply-ui-summary.json`、cli-result-ui-final-api*-*/。

新增空正文/跨页emoji JVM覆盖；App Consumer300/300、Developer312/312无跳过。Consumer外部Connector测试两次HTTP读取超时原始记录保留，随后单独执行完整Consumer通过（cli-recovery-consumer-serial.log）；不推定并发为根因。相关构建、root lintDebug、Spotless/Detekt任务通过，原KDoc格式失败已修复；日志cli-recovered-reply-refresh-final.log、cli-recovery-consumer-serial.log保留各任务与失败/重跑边界。

剩余：完整Goal成功结果强杀链路，以及Runtime暂不可用时直接读取已经保存在本地的结果；当前恢复入口仍先query Runtime，因此这一点明确未完成。长稳与真机边界不变。

## 2026-09-07 Runtime 不可用时读取已保存回复

本地artifact发现现在独立于Runtime状态，首次打开会话即可显示“查看已保存回复”。未查询到Runtime成功状态时，该入口只调用本地回读：核对原modelCall/Turn与job_prepared绑定、合法Job ID、artifact归属、大小和SHA-256，不执行Runtime query/fetch/ack。用户先显式查询成功状态再取回时仍走原持久化后确认链路；本地读取不隐式确认待处理的Runtime记录。

宿主在确认成功结果并强杀主App后禁用CLI Runtime，恢复时实际点击生产ChatScreen本地结果入口，确认Runtime查询为Unavailable但正文HELIX_OK可读；两API共2kill/4恢复通过。finally将Runtime启用状态恢复原值0，并由dumpsys核对；不清理账号数据。随后原fetched/persisted/acknowledged联网取回窗口另6kill/12恢复通过。合计8kill/16恢复，证据 `build/main-verification/cli-local-reply-summary.json` 与八组cli-local-reply-api*-*/，禁用/恢复状态原文随组保存。

两发行包构建、测试APK、root lintDebug、Spotless、Detekt通过（cli-local-reply-build-final.log）；App JVM串行强制复验Consumer300/300、Developer312/312，无失败/跳过（cli-local-reply-jvm.log）。首次长行格式失败已修正后复跑，未放宽规则。完整Goal成功结果强杀接入仍需验证；本轮是绑定fixture与真实结果存储/UI，不替代Goal预算证据。

## 2026-09-07 完整 Goal 成功回复恢复与 CLI journal 容量修复

CliGoalProcessKillDeviceTest 新增成功模式：通过生产 Goal/Chat/订阅适配器执行真实跨 UID Runtime 的 helix-fixture，在私有结果已持久化并精确 ACK、首个模型事件尚未交回时，由测试包装器暂停并由宿主 SIGKILL 主 App。恢复经生产 ChatScreen 会话导航、查询和回复入口显示 HELIX_OK；Goal 保持 PAUSED，run/Turn 中断，modelCall/job_prepared 不增加，预算结算不重复。测试包装器仅在测试 APK 中，无生产暂停钩子。

API29/36 × CODEX/CLAUDE/GROK/COPILOT 全8组通过，8次强杀、16次恢复；运行中断 CODEX 分支另2次强杀、4次恢复通过。证据 `build/main-verification/cli-goal-success-summary.json`、cli-goal-success-final-api*-*/ 与 cli-goal-running-regression-api*/。全部使用合成模型，无订阅账号或远程模型调用。完整 Goal 的成功边界仅为持久化+ACK后、首事件前；其他 fetch/persist 窗口仍由此前绑定 fixture 的强杀矩阵覆盖，不能混称完整 Goal 全窗口。

过程中两台 Runtime 均累积128条日志，暴露已确认终态不回收导致新请求永久拒绝。现仅清理过期或容量压力下最旧的已确认终态，保留活动/未确认/损坏记录；有效回归先2失败再通过，CLI Runtime104/104、Client30/30 无跳过，Runtime构建、root lintDebug、Spotless、Detekt通过（cli-journal-retention-green.log）。首次失败的 Goal configure 已按所属 fixture 清理，未清除 Runtime 数据或账号；原始失败记录保留。

当前正常 CLI 路径的结果持久化后确认缺口收口。legacy reconcile 的破坏性兼容语义仍保留，不作为正常恢复路径；30天未确认记录 evidence-expired marker、其他生命周期矩阵及统一交互优化仍待办。ADR-0028 保持 proposed，整个开发 Goal 保持 active；长稳后置、真机和付费调用边界不变。

## 2026-09-07 CLI 未确认结果到期标记实现

按 ADR-0007 为未确认终态增加30天到期处理：启动恢复、新任务容量检查、显式查询和结果取回触发维护。先写入 EVIDENCE_EXPIRED，保留原Job ID、request SHA和终态时间，移除成功证明，再删除request/events及其临时文件。删除失败向上传播；重启看到marker后重试清理，不重新执行。marker不被已确认记录的容量回收清除，也不被legacy reconcile改写为已确认。活动任务不因创建时间较早而直接过期，墙钟回拨不会触发提前清理。

客户端codec可往返该终态，恢复服务映射到明确页面文案；本地已有artifact仍可走独立回读。旧客户端遇到新枚举不能解码，保持不可用边界；未新增破坏性兼容回退。首次JVM回归2项中到期断言失败、时间边界项通过；实现后新增删除失败/重复恢复与同Job不再执行断言，共3项通过。CLI Runtime107/107、Client30/30，无失败/跳过；两发行包及Runtime构建、root lintDebug、Spotless、Detekt通过。证据 cli-evidence-expiry-red.log、cli-evidence-expiry-verified.log；首轮测试通配导入导致格式门禁失败，已修正，原日志保留。

当前仅关闭实现与JVM门禁，不勾选整个到期待办：新状态跨UID Binder/PFD传输、实际恢复页面及设备文件清理仍需API29/36验收。没有修改系统时间、访问账号凭据、运行长稳或真机测试。整个Goal保持active。

## 2026-09-07 CLI 到期结果跨 UID 与恢复 UI 验收

run-cli-result-owner-kill.py 增加 --expired：真实Runtime生成合成结果，宿主强杀主App后，只将本次owned Job终态时间调整为31天前；不修改设备系统时间，不涉及账号。分别覆盖未保存到App的fetched边界、App已有副本的persisted边界。API29/36共4kill/8恢复通过：生产Binder返回EVIDENCE_EXPIRED且无events，ChatScreen实际查询显示过期文案；无副本时无取回入口，有副本时仍可显示HELIX_OK。Turn/modelCall保持INTERRUPTED，原绑定和调用数量不增加。宿主核对Runtime仅剩无成功证明、无ACK的record.json，然后仅清理本次fixture marker。

未过期persisted回归另2kill/4恢复通过。App强制串行JVM Consumer300/300、Developer312/312，零失败/跳过（cli-expiry-app-jvm.log）。测试APK构建、Spotless、Detekt通过；两发行包/Runtime构建和root lintDebug在本次生产实现对应的cli-evidence-expiry-verified.log中通过，后续修改仅测试定位与宿主脚本。汇总 build/main-verification/cli-evidence-expiry-summary.json 保存安装APK哈希和各组结果。

API29首次文案可见性失败、显式滚动后仍无法定位的日志均保留；测试改从Compose页面资源读取当前语言文案，并独立断言状态EVIDENCE_EXPIRED后通过。原fixture随后恢复与清理，再以最新测试APK重跑完整矩阵，不把失败算通过。此项覆盖真实文件/协议/UI，日期通过owned metadata加速，不是31天实时时间经过或完整Goal强杀。到期待办可关闭；长期满marker容量仍按拒绝新任务保护原身份，不自动删除或重放。
