# PRoot successful-result recovery gap

Status: closed
Date: 2026-09-08
Related HXA: HXA-102

## Closure scope and evidence

The four required closure items below are implemented and verified within the documented test boundaries. This closes the successful-result recovery gap, not HXA-102 or the overall optimization Goal.

| Required item | Implementation and evidence |
| --- | --- |
| Non-destructive original-result retrieval | Optional fetch protocol, bounded PFD, original execution/input/terminal identity checks; client/IPC JVM, Android protocol tests and real cross-UID fetch/ACK. Evidence: proot-result-fetch-summary.json, proot-cross-uid-result-summary.json and current post-proot-full-host-result.json. |
| Durable private ownership before exact ACK | Session artifact registration and hash readback, rejection without ACK, cleanup failure/idempotence, local read with disabled Runtime. Evidence: proot-result-store-summary.json, proot-ack-summary.json, proot-recovery-ack-summary.json and proot-offline-summary.json. |
| Explicit result and confirmation UI | Production ChatScreen session selection, original call query/view/confirmation; normal and unresolved Goal paths preserve execution state/budget. Evidence: proot-result-navigation-summary.json and proot-normal-goal-summary.json. |
| Failure and recovery boundaries | Missing/tampered/expired evidence, partial transfer, queued/dead owners, pre/post ACK App SIGKILL with repeated full-page recovery. Evidence: proot-output-failures-summary.json, proot-owner-boundaries-summary.json, proot-expiry-report-jvm-summary.json and proot-ack-kill-navigation-summary.json. |

All evidence files are under build/main-verification; dated entries preserve the original scope and APK hashes. Lost ACK transport response is covered by an injected RemoteException; real SIGKILL covers loss before confirmation and after Runtime confirmation but before the recovery callback/UI receives it. These are complementary evidence, not a claim of intercepting a Binder kernel reply. The protocol outcome under an unknown reply is explicit unconfirmed local data plus exact-id retry. No background retry loop or replay is introduced.

General device regression, outer application navigation, layout/accessibility optimization, Goal completion-evidence policy and deferred physical/soak gates remain owned by the main checklist. Historical open statements below describe the state at their date and are superseded by this closure table.

## Initial source evidence before the dated implementation below

ProotJobRecovery checks the original execution/input binding and exposes query/stop. A successful terminal record only yields a status label. ProotJobClient exposes submit/query/cancel/reconcile; there is no result-fetch transaction. ProductionLinuxExecutor receives output through the PFD supplied at submission, validates its archive manifest, and optionally imports result.txt. Its randomly named per-call scratch directory is deleted in finally and is not part of the durable job binding. A process-killed scratch remnant is therefore not a supported recovery contract.

The Runtime retains output.zip in ProotJobStore. Its reconcile method writes reconciliation metadata and deletes non-record files; it does not establish that the App durably imported the archive. The existing query/stop matrix must not be described as successful-result import acceptance.

## Required closure within ADR-0007

1. Add bounded, original-identity, non-destructive archive retrieval. Verify the terminal record, execution/input binding and output manifest. Unknown or older protocol support must remain unavailable, without fallback to a destructive transaction or resubmission.
2. Persist verified bytes as a private session-owned artifact before an exact, idempotent acknowledgement. Include privacy deletion ownership and local readback when Runtime is unavailable. Deletion failure must not fabricate successful acknowledgement.
3. Expose explicit read-only result viewing from the interrupted call. Show stdout/stderr and verified artifact availability without running output code, completing the Goal, or automatically importing files into a user workspace. Any new workspace mutation must follow the existing Tool/Policy/Approval path.
4. Verify fetch/persist/ack process-kill boundaries, repeated recovery, missing/tampered archive, expiry and API29/36 production navigation. Distinguish seeded binding fixtures from complete Goal execution and real PRoot execution from synthetic archives.

## Initial correction

Expired terminal records previously reached the SUCCEEDED UI branch. The status mapper now prioritizes evidenceExpired and disables stop for that terminal record. This correction does not implement archive retrieval. Validation and remaining boundaries are recorded in HXA-102 and the main verification report.

## Related records

- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
- [Current TODO](main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)

## 2026-09-07 PRoot 非破坏性结果取回原语

增加additive TX_JOB_FETCH_RESULT事务（原协议版本不变，旧Runtime不支持时无破坏性回退），Runtime通过可选ProotJobResultHandler返回SUCCEEDED、未过期、未确认且归档存在的只读PFD和终态记录。归档压缩字节上限136MiB，容纳原128MiB未压缩上限及封装开销；App后续仍需独立执行ZipJobExtractor/manifest校验。ProotResultClient核对返回记录与调用方原记录完全一致及描述符长度，成功后由调用方关闭PFD，验证异常关闭描述符。取回不提交、不取消、不reconcile，不导入或执行内容。

API29/36 ProotResultArchiveDeviceTest各2/2：测试handler接入真实ProotRuntimeServiceBinder/Parcel/PFD，两次读取保留原未确认记录与归档；过期、已确认、文件缺失和RUNNING均拒绝。证据 build/main-verification/proot-result-fetch-summary.json、proot-result-fetch-api29/36.log，含安装Runtime/test APK hash。字节为合成归档载荷，handler在Runtime测试进程内，不声称跨UID、真实PRoot执行或内容完整性验收。临时fixture目录在finally删除，不影响安装资产。

PRoot Client23/23、IPC40/40 JVM无失败/跳过；Runtime及测试APK构建、root lintDebug、Spotless、Detekt通过（proot-result-fetch-final-build.log）。首轮通用catch和复杂条件静态失败已按规则修正，保留原日志。客户端尚未接入产品恢复入口，私有持久保存/确认、跨UID强杀与UI仍见开放缺口；整个Goal保持active。

## 2026-09-07 PRoot 精确确认与删除失败修复

新增TX_JOB_ACK_RESULT，校验原terminalCommit（包含jobId/executionId/input与output manifest及终态字段）后才允许确认；ProotResultClient无旧协议破坏性回退。ProotJobStore确认与legacy reconcile均改成先检查完整载荷删除成功再保存回执；重复确认返回首次时间，过期证据不变造为已确认。显式确认与对账在store锁内串行。本次尚未把新确认接口接入App产品流程，调用前私有持久化仍是必须补齐的前置。

API29有效故障夹具将payload子目录设为只读，旧实现未抛错并继续确认，红日志proot-ack-red-api29.log保留；修复后原记录不变、删除失败抛出。两API各5/5：错误指纹拒绝/归档保留、正确确认/重复回执、删除失败、重复fetch与无效结果拒绝、真实PRoot作业legacy reconcile。证据 build/main-verification/proot-ack-summary.json、proot-ack-api29/36.log；权限及本次临时目录finally恢复/清理，无安装资产删除。新ACK的跨UID事务尚待专门执行，不能由直接store测试冒充。

PRoot Client/IPC既有JVM任务通过（23/40），Runtime与测试APK构建、root lintDebug、Spotless、Detekt通过（proot-ack-build.log）。归档内容验证、App持久导入与恢复UI仍保持开放缺口；整个Goal不标记完成。

### 2026-09-07：PRoot 大归档登记的流式校验前置修复

制品登记原先通过 `file.readBytes()` 校验 SHA-256，会为整个归档分配内存；现在复用新增的流式文件哈希重载，校验失败仍抛出异常。storage JVM 75/75 通过（含空文件、缓冲区边界及缺失文件测试），根级 `lintDebug`、Spotless 与 Detekt 通过（Lint 日志：`build/main-verification/proot-artifact-stream-lint.log`）。独立 JVM 在 32 MiB 最大堆下成功校验 128 MiB 文件，并与生成时计算的 SHA-256 一致；这证明该哈希路径不再需要整文件大小的堆分配，不代表 Android 全链路内存验收。证据：`build/main-verification/proot-artifact-stream-summary.json`、`proot-artifact-stream-hash.log` 和 `proot-stream-hash-low-heap/result.log`。首次独立验证因编译类目录配置错误未运行到产品代码，原始日志保留为 `classpath-error.log`。

PRoot 私有归档持久保存、回读验证后 ACK、只读恢复 UI 及跨 UID/进程终止矩阵仍待完成；此项不关闭结果恢复缺口。

### 2026-09-07：PRoot 私有结果保存组件

新增 `ProotResultStore`：核对原 Turn/ToolCall 与 job/execution/input manifest 绑定，限量接收归档，使用既有严格 ZIP 提取校验核对输出 manifest，原子写入会话私有制品并流式回读校验。重复保存核对既有登记，不覆盖已登记的不同结果；组件不发送 ACK、不执行归档内容、不导入用户工作区文件。

API29/36 各 2/2 通过，覆盖真实 Room 登记、重复保存、内容损坏、错误 execution/manifest、已有结果保留和会话删除返回制品清理路径。这里使用生成的合法 ZIP 与绑定夹具；未证明 Runtime 跨 UID 获取/ACK、进程终止恢复或隐私删除服务实际删除文件。Developer 与测试 APK 构建、根级 lintDebug、Spotless、Detekt 通过。证据：`build/main-verification/proot-result-store-summary.json`、`proot-result-store-emulator-5596.log`、`proot-result-store-emulator-5598.log`、`proot-result-store-verified-build.log`、`proot-result-store-lint.log`。前几次新增测试格式检查失败日志保留，最终修正后通过。

下一步将组件接入获取结果→持久保存及回读→精确 ACK，并完成只读结果 UI 与跨 UID/进程终止矩阵；结果恢复缺口仍开放。

### 2026-09-07：PRoot 恢复协调器的保存/确认顺序

新增 `ProotResultRecovery`，提供生产 Client 工厂和原调用恢复入口：仅允许 INTERRUPTED Turn，校验结果并持久保存/回读后才调用精确 ACK；已有本地归档也重新核对当前记录的 manifest 后才确认。`localOnly` 只读取已验证本地制品，不查询或唤起 Runtime。确认不可用时不会将其标为已确认，组件不提交新 Job、不完成 Goal。

API29/36 各 4/4 通过（含上一轮保存回归）：新增测试在 ACK 回调中验证落盘制品可读、错误 manifest 的 ACK 次数为零、本地读取不调用任一 Runtime 接口。使用真实 Room、合法 ZIP 和 PFD，query/fetch/ACK 为注入夹具；不冒充跨 UID Binder 验收。Developer/测试 APK、Spotless、Detekt、根级 lintDebug 通过；证据：`build/main-verification/proot-result-recovery-summary.json`、`proot-result-recovery-emulator-5596.log`、`proot-result-recovery-emulator-5598.log`、`proot-result-recovery-verified-build.log`、`proot-result-recovery-lint.log`。初次新增测试行宽失败日志保留。

待接入恢复界面，补真实 Runtime ACK 与进程终止矩阵，并处理正常执行路径的持久结果；完整结果恢复缺口仍开放。

### 2026-09-07：真实 PRoot 归档保存与跨 UID 确认

跨 APK 回归发现生产执行只写调用方 PFD、未生成 Runtime `output.zip`，导致成功后 fetch 返回空；现已先构建、同步并原子保存 Runtime 归档，再传给调用方。相同用例在 API29/36 从失败转为通过：重复 fetch、错误 ACK 拒绝、App 持久保存后精确 ACK、回执保持、ACK 后 Runtime 无归档、本地仍可读。真实命令运行于 PRoot，只有 INTERRUPTED Turn 的归属为夹具，不是完整 Goal/进程终止验收。

跨 UID 2/2 和 Runtime 执行/取消/超时/确认/归档回归 28/28 通过，Runtime/测试构建、根级 lintDebug、Spotless、Detekt 通过。证据：`build/main-verification/proot-cross-uid-result-summary.json`、`proot-cross-uid-result-fixed-emulator-5596.log`、`proot-cross-uid-result-fixed-emulator-5598.log`、`proot-retained-output-regression-emulator-5596.log`、`proot-retained-output-regression-emulator-5598.log`。原始失败日志保留为 `proot-cross-uid-result-emulator-5596.log` / `proot-cross-uid-result-emulator-5598.log`。缺陷记录：`docs/bug-fixes/2026-09-07-proot-result-missing-runtime-archive.md`。

剩余：恢复界面、进程终止窗口、正常 App 执行路径的持久制品；首次输出传输失败仍按既有规则记 FAILED，其结果恢复尚需另行覆盖。完整缺口仍开放。

### 2026-09-07：PRoot 只读结果预览数据

新增 `ProotResultPreview` 与跨分发共享的展示数据类型，严格校验并在临时目录提取已恢复归档，分别读取 stdout/stderr（各最多 65536 个 UTF-16 code units），截断时不拆分代理对，并保留显式截断标记；文件列表包含原路径、大小和 SHA-256。预览不执行内容，不导入工作区，完整归档保持不变。Developer 的恢复模块提供恢复并生成预览的入口；Consumer 保持能力不可用。

新增 JVM 4/4 通过：输出/文件元数据与归档保持、代理对截断、恰好达到限制、坏归档拒绝及临时提取清理。Developer/Consumer 编译、根级 lintDebug、Spotless、Detekt 通过。证据：`build/main-verification/proot-result-preview-summary.json`、`proot-result-preview-build.log`、`proot-result-preview-lint.log`。这不是 UI 验收：聊天卡片的数据状态、查看入口与展示组件仍待接入，随后进行模拟器界面与进程终止矩阵。

### 2026-09-07：聊天卡片接入 PRoot 结果查看

聊天恢复卡片增加查看入口，ChatService 先尝试本地已验证归档，再按需获取原作业结果、保存并确认；加载期间禁用重复操作，失败显示不可用提示，取消不吞掉协程取消。timeline 保留预览状态，显示 stdout/stderr、归档清单及截断提示，支持分页和选择复制，未执行任何恢复内容或完成 Goal。三组中英文资源已补齐。

API29/36 各 6/6 通过：Compose 原调用身份路由、分页/末页禁用，以及四项保存和 ACK 顺序回归。Developer/Consumer APK、Developer 测试 APK、Spotless、Detekt、根级 lintDebug 通过。证据：`build/main-verification/proot-result-ui-summary.json`、`proot-result-ui-emulator-5596.log`、`proot-result-ui-emulator-5598.log`、`proot-result-ui-verified-build.log`、`proot-result-ui-lint.log`。

界面入口已接入生产调用，但本轮组件测试不等同完整 ChatService→真实 Runtime→结果界面端到端验收。下一步补原进程终止后的真实结果恢复、离线本地查看和完整聊天界面验证；正常 App 执行路径持久制品及首次传输失败恢复仍开放。

### 2026-09-07：调用方死亡后的 Runtime 冻结诊断

新增 `--successful` 诊断路径，运行中 SIGKILL App 后观察原 Job，再经生产 ChatService/恢复按钮查看。API29 实测 1 次 SIGKILL、2 次重启读取成功且模型请求未增加；API36 未通过：原 Runtime/guest 仍存活，journal 保持 RUNNING，`dumpsys activity processes` 明确记录 `isFrozen=true`。重连后 Job 成为 TIMED_OUT（stdout 已有内容），不能按 SUCCEEDED 接受。停止验证因实际已超时而未满足 CANCELLED 断言，之后 `abort` 清理成功；原 Runtime Job 终态证据保留。证据位于 `build/main-verification/proot-success-kill-emulator-5596/` 和 `proot-success-kill-emulator-5598/`（含 runtime-process.txt、cancel-recover.log、cleanup-abort.log）。

ADR-0007 第 7～9 条要求：没有匹配的有效 FGS 继续路径时，Binder death 应终止 Job。故“任意 Shell 在 App 死亡后继续成功”不是当前验收目标；本次 API29 结果仅是诊断，不能当作后台继续能力通过。后续需补调用方死亡的终止契约，并把成功恢复窗口改为 terminal commit 已完成、App 尚未消费结果时 SIGKILL。不得禁用 freezer、后台常驻或扩大 service type 来制造成功。新增测试静态检查与 APK 构建通过（首次 TooManyFunctions 已拆分修正），总体场景仍未通过。

### 2026-09-07：修复 PRoot 排队取消失效

为调用方死亡处理核查取消路径时，发现 PENDING 作业取消没有传到执行线程。API29 新测试复现取消后仍 SUCCEEDED；现已共享每个 Job 的取消标记，并在 live process 登记后补查取消。API29/36 各 11/11 Runtime 回归通过，验证排队作业 CANCELLED 且未执行写文件命令；Runtime/测试构建、Spotless、Detekt 通过。证据：`build/main-verification/proot-pending-cancel-summary.json` 及对应 emulator 日志，红测日志为 `proot-pending-cancel-red-api29.log`。缺陷记录：`docs/bug-fixes/2026-09-07-proot-pending-cancel-ignored.md`。

这只是调用方死亡取消的前置修复。提交协议尚无 owner Binder/death recipient，不可据此声称 App 死亡会自动取消；下一步接入该通知并验证排队/运行/终态窗口。

### 2026-09-07：已接入 PRoot 调用方死亡取消

生产 Client 通过新增 owned-submit 事务发送进程 Binder，Runtime 为新接收的 Job 监听死亡并取消原 Job，终态释放监听；重复提交不替换 owner，普通 query/unbind 不触发取消，旧 Runtime 不支持时不回退 legacy submit。未增加 FGS、后台常驻或 freezer 绕过。

API29/36 共 2 次运行中 SIGKILL，主机在 App 重启前核实原 journal 已 CANCELLED；随后 4 次恢复通过且模型请求数未增长。正常跨 UID 成功执行、重复 fetch、保存后 ACK 回归 2/2 通过。证据：`build/main-verification/proot-owner-death-summary.json`、两个 `proot-owner-death-emulator-*/result.json` 及 `proot-owner-normal-emulator-*.log`。Client/IPC JVM、Runtime/App/test 构建、Spotless、Detekt、根级 lintDebug 通过（`proot-owner-death-final-build.log`）；首次缺失 IBinder import 编译失败已修正并保留日志。

缺陷记录：`docs/bug-fixes/2026-09-07-proot-owner-death-not-cancelled.md`。Legacy 原始提交仍无 owner；当前生产 Client 不再使用它。待补 dead-at-submit/排队 owner-death/终态 race，以及 terminal commit 已完成、App 未消费时终止进程的成功结果恢复。

### 2026-09-07：PRoot 终态提交后、App 消费前的真实恢复

`--successful` 已改为合法终态窗口：测试主机确认原 guest 开始后 SIGSTOP App（PID 归属核实），等待 Runtime SUCCEEDED/terminalCommit，再 SIGKILL 同一 PID。失败路径恢复 SIGCONT，避免遗留暂停进程；不会用“App 死亡后任意 Shell 继续执行”作为验收条件。旧诊断结果不追溯改成通过。

API29/36 共 2 次终态后 SIGKILL、4 次重启恢复通过：生产 Goal/Chat/Dispatcher/审批/PRoot 执行，点击生产恢复卡片，经 ChatService 保存并确认结果，断言实际文本节点含原输出；第二次重启仍可查看，Turn 保持 INTERRUPTED，模型请求计数未增加。终止前后 terminalCommit 一致。证据：`build/main-verification/proot-terminal-ui-kill-summary.json`、两组 `proot-terminal-ui-kill-emulator-*/terminal-before-kill.json` 与 phase 日志。Runtime 安装哈希另核实于 `proot-terminal-ui-runtime-hashes.json`。测试 APK、Spotless、Detekt 与 Python 语法检查通过（`proot-terminal-ui-kill-build.log`）；本轮未改生产代码。

该证据覆盖生产调用链与恢复卡片，不冒充全屏视觉验收、Runtime 不可用的离线恢复、App 保存/ACK 之间的终止或正常执行路径持久制品。这些边界以及 owner 的 dead-at-submit/排队/终态竞态仍待完成。

### 2026-09-07：Runtime 禁用后的本地结果查看

新增 `--successful --offline-final`：真实 Goal 的原 PRoot Job 终态后终止 App，首次重启通过生产恢复链保存并确认；随后核实测试 Runtime 被 disable-user，第二次重启只点击查看入口，不先查询 Runtime。实际文本节点继续显示原 stdout，Turn 仍 INTERRUPTED，模型请求数未增加。API29/36 共 2 次终止、4 次恢复通过，其中 2 次为 Runtime 禁用时的本地结果查看。

主机仅对默认启用的专用测试 Runtime 执行禁用，并在 finally 恢复；已另外核实两台 `enabled=0` 且 `stopped=false`。证据：`build/main-verification/proot-offline-summary.json`、两组 `proot-offline-kill-emulator-*/runtime-disabled.txt` / phase 日志及 `proot-offline-restored.json`。测试 APK、Spotless、Detekt、Python 语法及文档检查通过（`proot-offline-verified-build.log`）；初次测试方法 LongMethod 已拆分，原日志保留。本轮没有改动生产代码。

尚待验证保存/ACK 中途终止和 owner 边界；正常执行路径的持久制品、首次输出传输失败恢复等仍未关闭。

### 2026-09-07：PRoot 保存及 ACK 边界进程终止

新增恢复协调器确认回调的测试暂停点：真实 Goal/Job 终态后先终止 App；恢复使用真实 ZIP、Room 持久保存和 Binder Client，在“回读完成、ACK 前”或“精确 ACK 后”再 SIGKILL。主机在第二次终止前读取原 Runtime journal，分别核实无回执/有回执，避免只靠暂停标记宣称命中窗口。

API29/36 × persisted/acknowledged 四组全通过，共 8 次 SIGKILL、8 次后续 UI 恢复，文本节点显示原输出，Turn 保持 INTERRUPTED，模型请求数未增加。证据：`build/main-verification/proot-result-boundary-summary.json` 和四组 `proot-result-{persisted,acknowledged}-emulator-*/result-boundary-record.json` / phase 日志。测试 APK、Spotless、Detekt、Python 语法和文档检查通过（`proot-result-boundary-build.log`），本轮未改生产代码。

暂停点使用测试注入的确认回调；后续恢复按钮走生产 ChatService。本轮证明归档数据在这些窗口存活，不证明所有 ACK 重试/清理策略：已有本地结果走 localOnly，既有测试末尾仍调用 legacy reconcile 清理 Runtime。该边界不可冒充 UI 自动重试精确 ACK。正常执行路径持久制品、首次传输失败恢复及 owner 竞态仍待完成。

### 2026-09-07：正常 PRoot 执行接入持久制品

`ProductionLinuxExecutor` 在输出 manifest 验证后、工作区导入和返回成功前调用必填持久保存回调；生产 `ProotToolModule` 使用原 Turn/ToolCall 的 `ProotResultStore` 保存会话私有归档。保存失败返回 `OUTPUT_PERSIST_FAILED` 并要求核查原 Job，不伪造成功或重放。直接 IPC 测试没有会话归属时显式提供测试回调，生产装配不使用空实现。

API29/36 各 6/6 LinuxRunTool 执行回归通过；新增 Room 归属夹具围绕真实正常执行器，确认 execute 返回、scratch 清理后仍能读取登记归档和原 stdout，同时保留工作区输出导入验证。该夹具并非完整 Goal 新增验收。证据：`build/main-verification/proot-normal-persist-summary.json`、`proot-normal-persist-emulator-5596.log`、`proot-normal-persist-emulator-5598.log`。App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-normal-persist-verified-build.log`）。

本轮接入持久保存，未把正常路径 ACK 清理一并宣告完成；仍需覆盖保存失败、确认失败/重试、首次输出传输失败和 owner 竞态，再刷新全量门禁。

### 2026-09-07：正常路径精确 ACK 与失败证据

新增 `ProotResultCommitter`，生产装配在保存与回读成功后调用精确 ACK，并写 `proot.result_ack` 审计。ACK 不可用或 RemoteException 时记录 acknowledged=false，保留已验证本地结果，不因此重放成功命令；无效回执仍拒绝。持久保存失败在调用 ACK 前退出，执行器沿既有 OUTPUT_PERSIST_FAILED 核查路径处理。

API29/36 各 12/12 通过：正常 ProductionLinuxExecutor 执行及 Room 归属夹具验证 ACK 后 Runtime fetch 不再提供归档、本地 stdout 仍可读；新失败测试验证不可写目标下 ACK 次数为零，RemoteException 下结果保留且审计明确未确认。还包括现有执行/保存/恢复回归。证据：`build/main-verification/proot-normal-ack-summary.json`、`proot-normal-ack-emulator-5596.log`、`proot-normal-ack-emulator-5598.log`。App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-normal-ack-build.log`）。

正常保存后确认已接入；待确认审计不等于自动重试清理已实现。首次输出传输失败恢复、owner 竞态及最终全量回归仍开放。

### 2026-09-07：修复 owner 监听晚于作业启动的竞态

发现 owned submit 先调用普通 submit 入队，再 linkToDeath；已死 owner 的注册异常到达前命令可能已成功。API29 用延迟注册并报 RemoteException 的 Binder 夹具复现 SUCCEEDED（期望 CANCELLED）。现已共享同步提交路径：判重/预算、PENDING、取消标记、owner 监听全部完成后才入队，重复提交不替换 owner。

API29/36 各 12/12 runner 回归通过，新测试同时断言 CANCELLED 与未生成命令文件。真实生产 Goal 运行中 owner SIGKILL 又完成 2 次、后续 4 次恢复通过，App 重启前 journal 已取消且模型请求未增加。证据：`build/main-verification/proot-owner-order-summary.json`、`proot-dead-owner-emulator-*.log`、两组 `proot-owner-order-kill-emulator-*/result.json`；红测为 `proot-dead-owner-red-api29.log`。Runtime/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-dead-owner-build.log`）。缺陷记录：`docs/bug-fixes/2026-09-07-proot-job-start-before-owner-link.md`。

已死 owner 注册测试是可控 Binder 夹具，真实跨进程 SIGKILL 是另一组证据；不混为同一测试。排队 owner 死亡、释放/重复提交竞态及首次传输失败恢复仍需收口。

### 2026-09-07：首次结果传输失败保留 Runtime 成功证据

真实 runner 配合只读输出 PFD 复现：原命令成功且 Runtime 已保存 ZIP，但初次写失败使终态变为 FAILED，fetch 因而拒绝。现已分离持久归档和瞬时传输结果：仅传输 IOException 写独立诊断，保留原执行状态及 manifest；归档构建/保存失败仍走失败路径，不重放命令。

API29/36 各 13/13 runner 测试通过，新用例核实初次目标为空、重新 fetch 的记录完全一致、ZIP manifest 校验及 stdout 原文。证据：`build/main-verification/proot-delivery-summary.json`、`proot-delivery-emulator-5596.log`、`proot-delivery-emulator-5598.log`；红测 `proot-delivery-red-api29.log`。Runtime/测试 APK、Spotless、Detekt、根级 lintDebug 通过（`proot-delivery-final-build.log`）。最初模块级 Spotless 任务名错误和 terminal 方法长度静态失败已修正，原日志保留。缺陷：`docs/bug-fixes/2026-09-07-proot-transfer-failure-discarded-terminal-success.md`。

本轮仅关闭 Runtime 终态误判。继续检查发现 `ProductionLinuxExecutor` 的 OUTPUT_MISSING/OUTPUT_VERIFY_FAILED/OUTPUT_HASH_MISMATCH 尚未设置 requiresReview，而 UI 恢复入口依赖 INTERRUPTED；必须继续接通该失败路径并验证，不能声称首次传输失败的端到端 UI 恢复已完成。部分传输、归档构建失败注入、owner 排队/释放边界、ACK 重试及最终全量门禁仍开放。

### 2026-09-07：输出验证失败进入待核查并开放原结果恢复

`ProductionLinuxExecutor` 的输出缺失、manifest 缺失、归档校验失败、hash 不符均设置 requiresReview。继续追踪确认生产结算为 FAILED Turn / NEEDS_REVIEW ToolCall；新增共享资格判断，Chat timeline、query/stop 和结果恢复一致支持该组合及既有 INTERRUPTED 组合，运行中的调用不开放。读取恢复不更改失败 Turn，不完成 Goal、不重发命令。

API29/36 各 15/15 通过（执行器、结果存储、UI 组件）；新增用例真实跨 UID 执行后将 App 暂存输出清空或损坏，断言待核查，然后按原 Job 恢复 stdout、登记一个制品、精确 ACK 且提交次数始终为一。恢复阶段 FAILED/NEEDS_REVIEW 由夹具建立，不冒充完整 Goal 结算/导航验收。资格 JVM 测试遍历全部 Turn/ToolCall 状态组合，1/1 通过。证据：`build/main-verification/proot-output-review-summary.json`、`proot-output-review-verified-emulator-*.log`。红测 `proot-output-review-red-api29.log` 明确记录 requiresReview=false。

App 构建、Spotless、Detekt、根级 lintDebug 通过（`proot-output-review-eligibility-build.log`）；夹具非法 CREATED→FAILED 转换由状态机正确拒绝，随后改为合法转换并补齐 errorCode 参数，测试/静态构建通过（`proot-output-review-fixture-final-build.log`），失败日志保留。缺陷记录：`docs/bug-fixes/2026-09-07-proot-output-failure-missing-review-recovery.md`。

完整 Goal 中注入传输故障并从生产界面查看、缺失 manifest/hash 不符故障注入、owner 排队/释放边界、ACK 重试与最终全量门禁仍待验证；整个恢复缺口及 Goal 保持开放。

### 2026-09-07：完整 Goal 输出丢失与生产恢复动作验证

新增 `scripts/run-proot-goal-delivery-failure.py` 及专用 fixture phase：真实创建 Goal、显式 Continue、模型 ToolCall、精确审批、生产 Dispatcher 与跨 UID PRoot 执行。先确认原 Job RUNNING，再仅 unlink 本次新建 scratch 下的 output.zip；Runtime 持有已打开 PFD，保留独立归档。未改生产代码或注入伪终态。

API29/36 两组均通过：生产结算为 INPUT_REQUIRED Goal、FAILED Turn、NEEDS_REVIEW ToolCall，run outcome 为 INPUT_REQUIRED(NEEDS_REVIEW)，待结算 reservation 为空。生产 ChatService timeline 显示恢复入口，Compose 中渲染生产 query/view 动作，点击后文本节点显示原 PROOT_RESULT_READY；精确 ACK 已写回原 Job。读取前后 Goal（含预算）、Turn、ToolCall 完全一致，prepared audit 始终一条；每台模型请求 4 次（含连接探测），没有重放。测试结束恢复本 fixture 设置并清理拥有的 Goal/Provider。

证据：`build/main-verification/proot-goal-delivery-summary.json`、两组 `proot-goal-delivery-verified-emulator-*/result.json` / instrumentation.log。测试 APK、Spotless、Detekt 通过（`proot-goal-delivery-final-build.log`），Python 语法和文档检查通过。初次 fixture 误等 PAUSED，实际状态为 INPUT_REQUIRED；另一台准备 audit 早于 Runtime submit，现先轮询确认 RUNNING。初次失败与 abort 日志保留，其中一次 cleanup 在 active run 尚未结算时被正确拒绝，结算后重试清理通过。

本轮验证完整 Goal 生产结算及恢复动作，恢复控件置于测试 Compose 容器，未冒充整套 App 页面导航或竞品/UI 优化验收；故障为初始结果文件丢失，Runtime IOException 注入是前一轮独立测试。部分传输、manifest/hash 故障、owner 排队/释放、ACK 重试与最终全量回归仍待完成。

### 2026-09-07：owner 排队、重复归属与释放边界回归

新增可控 Binder owner 夹具与三组验证：真实 runner 首个 Job 占队时，第二个 owned Job 保持 PENDING，重复提交不注册替代 owner；原 owner 死亡使其 CANCELLED，命令文件没有创建。正常成功作业完成后仅解绑一次，后续 owner death 不改变终态。另以两个线程同步起跑，100 次交错检查已排队 death callback 和 terminal release 并发时仅 unlink 一次，重复 release 保持幂等。

API29/36 各 16/16 通过，共 200 次并发交错，已核对两台安装 Runtime hash 与本地构建一致。证据：`build/main-verification/proot-owner-boundaries-summary.json`、`proot-owner-boundaries-emulator-5596.log`、`proot-owner-boundaries-emulator-5598.log`。测试 APK、Spotless、Detekt 通过（`proot-owner-boundaries-build.log`），文档校验与 diff check 通过。本轮未改生产代码。

本轮 owner 回调为确定性测试夹具，不冒充跨进程 death；真实运行中 SIGKILL 已有独立证据。以上已覆盖已列出的排队/重复归属/释放边界，不声称证明所有可能线程调度。继续处理结果 ACK 重试、未覆盖的归档失败分支与最终全量验收，整个 Goal 保持运行。

### 2026-09-07：恢复 ACK 响应丢失不再隐藏本地结果

发现恢复协调器在私有 ZIP 保存/回读后直接传播 ACK RemoteException，ChatService 因而显示结果不可用；API29 红测复现。现将 ACK 不可用与归档读取分离，RemoteException 返回已验证文件及 acknowledged=false，非空回执要求原 terminalCommit 和确认时间均有效。

API29/36 各 14/14 通过。新增夹具模拟 ACK 已生效但响应丢失：首次返回本地结果未确认，localOnly 不调用 Runtime；随后显式协调器恢复查询到原记录已有回执，重验本地 ZIP 后重试 ACK，fetch 始终一次、制品仅一个、hash 和 INTERRUPTED Turn 不变。证据：`build/main-verification/proot-recovery-ack-summary.json`、`proot-recovery-ack-emulator-*.log`，红测 `proot-recovery-ack-red-api29.log`。App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-recovery-ack-build.log`）；文档和 diff 检查通过。缺陷：`docs/bug-fixes/2026-09-07-proot-recovery-ack-loss-hid-local-result.md`。

ACK 丢失通过回调注入，不冒充实际 Binder kill；当前产品 view 仍优先 localOnly。待确认提示与可操作的产品重试入口、正常执行后未确认清理，以及最终全量门禁仍需完成。

### 2026-09-07：结果确认状态与显式重试入口

结果预览增加三态确认信息：本地读取尚未核查（null）、确认未完成（false）、已确认（true）。结果卡新增“确认结果已保存”动作，等待期间禁用，确认后隐藏；独立 ChatService 入口调用原记录重验/精确 ACK，失败保留当前结果。普通“查看”仍优先 localOnly，不为显示已保存内容被动绑定 Runtime。COMPLETED Turn / SUCCEEDED Call 也开放结果读取与确认，供正常执行后待确认副本收尾；不改变 Turn/Goal 状态、执行工具或自动重发。

API29/36 各 17/17 执行器/存储/UI 组件回归通过；确认按钮原 turn/call 绑定、忙碌禁用、确认后消失均已测。资格 JVM 状态组合 1/1 通过。完整 Goal 输出丢失场景又通过两台：首次恢复后重复本地查看产生“尚未核查”，再点击生产确认按钮，经真实 Binder 幂等 ACK 回到已确认，原 Goal/Turn/Call 和预算不变，每台模型请求保持 4 次（含探测）。证据：`build/main-verification/proot-ack-ui-summary.json`、`proot-ack-ui-emulator-*.log` 和两组 `proot-goal-ack-ui-emulator-*/result.json`。

App/测试 APK、Spotless、Detekt、根级 lintDebug 通过（`proot-ack-ui-gates-build.log`），JVM 执行日志在 `proot-ack-ui-final-build.log`（该次随后因测试方法长度静态失败，已拆分夹具渲染修正并再次通过完整静态门禁）。首次代码替换断言、Compose 断言导入与命名/长度失败日志保留。三个语言资源同步；文档与 diff 检查通过。

入口已接入；正常完成调用的独立生产导航、真实 ACK 响应丢失后从界面重试仍需专项验证，不能由本轮已确认回执的幂等测试冒充。跨重启仅本地读取显示“尚未核查”，不会伪造已确认状态；没有后台无限重试或主动 Runtime 保活。其余归档异常和最终全量门禁仍待完成。

### 2026-09-07：正常 Goal 结果验收发现并修复状态域混淆

上一轮“COMPLETED Turn / SUCCEEDED Call”描述及判断有误：真实 App ToolCall 成功态为 COMPLETED，SUCCEEDED 属于 Runtime Job。完整正常 Goal 读取实际状态暴露问题；修正预期枚举后 JVM 红测也失败，原 XML 在 `build/main-verification/proot-completed-state-red.xml`。现使用 App 枚举进行成功资格判断，预期组合也改为枚举，避免不存在的字符串未被循环遍历却虚假通过。

API29/36 × 正常完成/初始输出丢失，共四组真实 Goal 场景通过。正常路径模型收到 ToolResult 后结束回复，Turn/Call 均 COMPLETED，Goal 保持 PAUSED / RUN_FINISHED；生产 ChatService 结果动作可显示持久 stdout，重复本地读取后点击确认，经 Binder 精确 ACK 成功。Goal（含预算）、Turn、Call 前后完全相同，每台请求 5 次（含探测）。输出丢失回归仍为 INPUT_REQUIRED / NEEDS_REVIEW，每台 4 请求，无重发。两组皆完成本 fixture 清理。

证据：`build/main-verification/proot-normal-goal-summary.json`、`proot-normal-goal-verified-emulator-*/result.json`、`proot-after-normal-failure-emulator-*/result.json`。资格 JVM 1/1、App/测试构建、Spotless、Detekt、根级 lintDebug 通过（`proot-normal-goal-fixed-build.log`）；Python/文档与 diff 检查通过。缺陷：`docs/bug-fixes/2026-09-07-proot-completed-call-state-mismatch.md`。

模型仍为脚本服务，恢复控件位于测试 Compose 容器但绑定生产 ChatService，未冒充完整页面导航/真实账号验收。真实 ACK 响应丢失后的 UI 重试、其余归档异常和最终全量门禁继续推进。

### 2026-09-07：归档保存与部分传输失败边界

新增三项 Runtime 设备故障验证：构建回调写部分内容后 IOException，最终归档不发布且 pending 清理；原子发布目标被本测试的非空目录占据，原内容保留且调用方未收到任何字节；真实 pipe 读到前缀后关闭，后续写失败明确 delivered=false，而 1 MiB 完整 Runtime 副本逐字节不变、诊断存在且 pending 不残留。

API29/36 各 18/18 通过（上述 3 项与既有 15 项真实 runner 回归），证据 `build/main-verification/proot-output-failures-summary.json`、`proot-output-failures-emulator-5596.log`、`proot-output-failures-emulator-5598.log`。测试 APK、Spotless、Detekt 通过（`proot-output-failures-build.log`）；文档和 diff 检查通过。本轮未改生产代码。helper 测试载荷为合成字节，专门验证发布/传输，不冒充 ZIP 格式验收；格式和 manifest 由已有真实归档测试证明。

清单首页的“只有查询/停止、产品保存/UI 未接线”已按当前源码与分项证据校正；PRoot 总项仍留开放，实际 ACK 响应丢失界面、完整导航和最终合并回归仍待完成。不会据本轮异常测试将整个 Goal 或所有 PRoot 验收标绿。

### 2026-09-07：PRoot 完整 ChatScreen 结果导航

恢复验证改为生产 ChatScreen：关闭会话返回列表，按本次唯一标题点击原会话，核对 sessionId 与原 call 的恢复资格，滚动到 query/view/确认按钮并实际点击，显示原结果文本。原组件容器验证仍是历史证据，本轮补上完整会话页面布局与导航。测试标题增加 UUID，避免与保留的历史归档会话重名；首轮选择器歧义与本 fixture abort 清理日志保留，没有删除其他历史会话。

API29/36 × 正常完成/输出丢失，四组均通过。正常路径请求数每台 5 次（含连接探测），输出丢失每台 4 次；Goal/Turn/Call、结算预算和原 Job 不变，查看后精确确认正常。证据：`build/main-verification/proot-result-navigation-summary.json`、`proot-normal-navigation-verified-emulator-*/result.json`、`proot-failure-navigation-emulator-*/result.json`。测试 APK、Spotless、Detekt 通过（`proot-navigation-unique-build.log`），文档与 diff 检查通过，本轮没有改生产代码。

本轮为真实生产 ChatScreen 页面，模型仍为脚本服务；不冒充外层应用导航、所有屏幕尺寸/语言/大字体和统一 UI 优化验收。PRoot 已列的结果页面导航项已获得证据，实际 ACK 响应丢失界面路径与最终合并门禁仍继续。

### 2026-09-07：PRoot 收尾后的合并宿主全量刷新

在 main 记录 1,122 项源码/配置指纹，核对全部 33 个含 test 源码模块对应的 34 个 JVM 任务（App 两 flavor 分别执行），使用 force-tests.gradle 禁用测试 up-to-date/cache 与 max-workers=1 强制重跑。逐任务核实实际执行行、当前生成 XML 和测试总数：2,637/2,637，零失败/错误/跳过。指纹在 Debug/Release 验证后均一致，没有用跨源码快照拼接通过。

根 lintDebug、lintRelease、Spotless、Detekt 通过；consumer/developer App 及 PRoot/CLI Runtime 的 Debug/Release 共八个主 APK 构建通过，另完成 App 双 flavor 测试 APK。Release 产物 unsigned，未签名/安装发布包或宣称 store 验收。证据：`build/main-verification/post-proot-full-host-result.json`（34 任务逐项计数）、`post-proot-host-command.json` / `post-proot-host.log`、`post-proot-release.log` / `post-proot-release-result.json`（八产物 SHA-256）、`post-proot-source-before.json` / `post-proot-source-after.json`。

本轮刷新宿主基线，没有新增生产改动；清单的 JVM/构建计数已从旧快照校正。设备专项、真实 ACK 响应丢失 UI、尚待决定的完成证据契约和统一界面优化仍独立推进。长稳保持后置，不以宿主全部通过宣告整体 Goal 完成。文档/ADR/diff 检查通过。

### 2026-09-08：确认交接进程终止与 PRoot 缺口收口

确认前（本地已回读，未 ACK）和确认后（真实 Runtime ACK 已返回给测试暂停回调，但尚未返回恢复协调器/UI）各在 API29/36 SIGKILL App。主机在终止前读取原 journal，分别证明无/有确认时间。四组共 8 次 SIGKILL、8 次后续完整 ChatScreen 页面恢复；本地查看后点击显式确认，结果状态变为已确认，Turn 保持 INTERRUPTED，原 Job/输入/预算不变，每组模型请求固定 4 次，无重发。

证据：`build/main-verification/proot-ack-kill-navigation-summary.json`、两组 `proot-ack-consumption-kill-emulator-*/result-boundary-record.json` 与两组 `proot-before-ack-kill-navigation-emulator-*/result-boundary-record.json` 及 phase 日志。测试 APK、Spotless、Detekt 通过（`proot-ack-kill-navigation-build.log`），本轮未改生产代码；文档/ADR/diff 检查通过。

此前“实际 ACK 响应丢失”的待办现明确区分证据：RemoteException 注入验证未知传输响应时保留本地结果；本轮真实进程终止验证确认尚未被恢复协调器/UI消费的窗口，未宣称截断 Binder 内核响应。两者覆盖所需的不确定确认行为与真实生命周期恢复。成功结果恢复记录按原四项要求给出对应表并关闭该有限缺口；HXA-102、整体设备矩阵、完成证据契约与统一 UI 优化仍继续，整个 Goal 不标记完成。
