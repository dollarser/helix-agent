# Bug Fix: 覆盖安装旧状态与插件导入

Status: fixed
Date: 2026-10-27
Related HXA: HXA-302, HXA-244
Affected modules: app, core/storage, extensions/mobile-use, runtime/proot

## Problem

真机覆盖安装后，终端和 Mobile Use 被遗留状态阻挡，普通失败提示无法指向恢复动作；系统 ZIP 选择器也不能交付输入。

## Impact

只清主库无法解除主库外的格式冲突，用户反复重试仍无法进入业务。

## Root cause

PLC110 的覆盖安装保留了主数据库之外的终端控制文件和 Mobile Use 配置。此前应用内清空只处理主库；格式读取异常又被合并成普通失败，提示没有指向重置。现场三个旧控制文件均为空记录，Mobile Use 的字段结构未变；经单次外部转换核实后恢复，不引入历史格式读取或迁移链。

运行环境还存在独立误判：组件字节未变，仅下载 URL／来源说明变化就要求更新。现按 ABI、组件集合、版本、大小、哈希和包清单核验；实际损坏、制品变化仍需要修复／更新。

## Fix and invariants

现在启动统一检测主库及不兼容插件／终端控制状态，确认页列出实际影响项目。读取和取消不清理；确认时再次校验目标内容及运行进程。非空或不可读执行身份须有严格更晚的系统启动证明，原始记录私有保留，不将 UNKNOWN 改成成功。部分失败重新核对剩余项目，不宣称全部完成。共享文档、模型、Linux 环境、账号凭据和有效插件配置保留。

插件导入新增复用现有文件管理器的只读选择入口，与系统选择器共享有界输入捕获和包解析。只使用已有授权来源，不展示 Root／授权／文件变更操作。取消传播，输入变化拒绝，错误区分格式、访问、冲突和存储，不显示原始响应或诊断详情。

## Alternatives considered

不添加历史格式兼容读取链，不在覆盖安装或启动时静默清数据；不为导入申请全盘权限。保留系统选择器并复用已有文件管理器，不另建文件系统。

## Regression verification

- 完整 Debug 主机门禁：源码／文档／ADR／本地化／密钥检查、Spotless、Detekt、两渠道 Lint、全量 JVM 测试、两渠道 APK 构建、依赖锁与包边界通过。未运行 Release/R8。
- 重置夹具覆盖空与非空旧状态、原主库已清、晚到进程、目标变化、部分失败、安全重试及无关文件保留。未在手机执行实际重置，不以此宣称真机破坏性重置验收。
- PLC110／Android 15 的 Advanced 本地 Debug 包保留数据覆盖安装，产品版本 0.0.8、versionCode 1。真实 shell 回显、退出后新建及 `/workspace` 检查通过；默认字号 15sp。
- 真机通过内置文件管理器选择合成 ZIP，预览并明确安装，读取安装后 SKILL.md 合成标记；测试插件及源 ZIP 已清理，Skill 快照按既有移除契约保留。文件选择取消不导入。Mobile Use 原范围加载／重复保存通过，未变更系统授权或操作其他应用。
- 系统选择器此前在交付 URI 前报告“暂时无法加载内容”；当时 ZIP 已存在且为 551 B，不能归因为缺失文件或 Helix 格式解析失败。本轮没有宣称修复 OEM 选择器。
- 真机没有故障注入旧格式、破坏性重置、外部模型调用或录音上传；Standard 此轮仅主机与 APK 边界验证。

## Follow-up: stable terminal numbering

用户随后报告每次新建都显示“终端 1”。原选择器按当前列表索引命名，新终端置于首位导致重复。改为应用私有的显示编号登记，按真实 session identity 同步持久分配；关闭、结算、列表排序和进程重启不回收编号，容量明确拒绝不分配编号；已受理的终端即使后来停止，也不回收编号。只读回同一身份不会递增，应用进程内多实例共享同步锁；未改 IPC、数据库版本或其他命名配置。当前没有用户自定义终端名称入口。

本次没有删除实际资源边界：最多四个手动 PTY，与 Job 共用六个活跃名额；低内存／温度压力会降低新准入额度。列表未按此裁剪，历史 UNKNOWN 不作为创建数量门禁。新增五项纯主机回归覆盖重复分配、关闭后新建、重启、并发、存储失败及无关设置保留。容量拒绝在原页给出简短提示并保留原连接、选中项和正文，不新建空白页。每个列表项显示正在运行、启动中、关闭中、已停止或待确认状态，历史 UNKNOWN 不用于预先拒绝新建。实际 START 在创建 PTY 和运行记录前由 Runtime 原子准入，环境维护中与容量满分别报告；界面不以旧列表计数替代此判定。初次进入优先选择仍可连接的终端，而非列表首条停止历史；明确选择历史后不暗中切换。新增原页保持、退出后恢复、并发争夺最后名额、初始选择及维护错误分类回归。

本段后续代码仅主机验证，尚未更新手机，前文真机结果不作为新编号 UI 的设备通过证据。

## Follow-up: Mobile Use setup navigation

原新会话入口把三个相近操作送到通用插件选择器；缺后端的分支关闭临时选择器后直接打开系统授权，因此返回时只有聊天页。插件管理内嵌设置的保存回调又仅处理非空的返回会话，独立进入保存后没有退出。顶部“返回权限”也没有反映实际来源。

改为唯一手机操作入口和带固定会话参数的专属路由，授权管理和范围设置压在其上，返回只查询事实。后端未就绪时先展示简短说明与去授权／取消，授权后仍由用户明确选用；普通插件保留通用选择器。全局范围设置统一使用导航路由，成功保存返回来源，失败留页；顶部返回使用通用返回文案。保存、授权和返回回调检查来源导航条目，旧页面的重复／晚到点击不能再弹出新页面。草稿保存失败不跳转，旧会话的晚到准备不能导航或选用新会话。快速重复启用、读写、取消与恢复由一个页面操作锁和已有应用服务核验，不引入业务恢复流程或自动启用队列。

新增 15 项主机回归与 7 项现有设置回归通过，覆盖提示／取消、权限往返不自动选用、范围保存不启用、停用插件重新加载启用、会话切换、并发点击、部分后端、权限撤销、持久化失败与取消后的真实读回。会话入口的设备回归已更新为专属页／草稿保留契约；本轮只编译，不执行设备测试，导航栈与系统页面的实际往返仍待验。

## Residual risk

实际破坏性重置与 OEM 系统选择器故障仍未真机验证；共享存储不可访问时仍需合法授权，内置选择器不绕过权限。Skill 快照按既有契约保留。

## Related records

相关契约：[存储编码](../adr/platform/003-storage-encoding.md)、[终端](../adr/runtime/002-terminal-and-jobs.md)、[Skill 安装](../adr/skills/001-authoring-and-installation.md)。原始本地结果位于忽略目录 `build/preview-reset-import-2026-10-27/`，不作为永久历史产物要求。

## Follow-up: navigation return contract

本轮统一交互职责，不替换业务服务或引入兼容路由：

- 抽屉属于切换功能区，清掉上一个功能区的详情栈，以会话为稳定首页；功能内跳转仍压入来源页。来源标记由实际导航入口设置，嵌套打开 Models 等页面显示返回键，直接从抽屉进入显示菜单键。
- 顶栏返回继续走系统返回分发器。知识编辑、文件选择／搜索／目录、系统权限的内部文件页先处理自身层级；终端返回仅退出界面，不终止 shell。
- 异步完成绑定原 Nav entry，只消费一次；离开／暂停后失效，不能在回到同一页后重放。保存失败留页，取消不提交；知识引用仍固定目标会话并经过原应用命令校验。
- 抽屉切页与外部打开请求先执行当前页面的离开处理：会话草稿成功落盘才离开，知识编辑复用保存／放弃／继续编辑，Mobile Use 复用原未保存范围确认。外部请求取消后不因旋转重新弹出或导航；它不产生授权。
- Provider 表单及进行中的保存由页面 ViewModel 持有，旋转不重提保存；密钥不写入 SavedState。普通浏览位置、搜索、技能编辑、提醒编辑和终端设置子页恢复必要 UI 状态；文件权限、实际内容和删除意图不作为恢复事实，重新加载服务状态。

保留差异：账号密码表单仍为短生命周期、不持久化；文件选择取消不导入；系统授权返回只刷新真实状态，Mobile Use 仍需明确会话启用；提醒的明确放弃返回不保存或安排任务。未改 Agent、权限、存储版本或应用安装身份。

主机回归覆盖来源失效／一次消费、重复点击、取消／失败后重试、页面替换、文件恢复后的失联来源与破坏性意图排除、Provider 保存去重／失败保留／迟到结果。两渠道设备交互回归只编译，设备测试本阶段未请求，不能将主机通过称为手机或模拟器验收。

最终源码的完整 Debug 门禁通过：Spotless／Detekt、两渠道 Lint、全量 JVM 测试、两渠道 APK 和 AndroidTest 代码编译；新增 14 项主机回归全部通过。源码／文档／ADR／本地化／密钥与依赖锁检查通过。本阶段未运行 Release/R8，未安装或操作手机／模拟器，未清数据、提交、推送或发布；版本保持 0.0.8／versionCode 1。设备上的返回栈、手势和系统授权往返仍需后续明确授权的交互验收。

## Owner revision: stopped terminals and Mobile Use dialog

手动终端以实际退出证明过滤展示。停止／异步退出关闭当前 viewport 并选取仍运行项；最后一个退出回到新建入口，不自动启动替代 shell。STOPPING 和未确认的 UNKNOWN 不当作已停止。所选停止并关闭不再自动删除 Runtime 控制记录或 Agent 审计，持久编号继续递增。新增四项主机回归覆盖最后退出、其他运行项接续、异步退出和未知身份保留；终端主机回归共 55 项通过。

Owner 随后要求将 Mobile Use 专属页面改为弹窗。当前实现以原会话／来源 entry／request identity 维护授权往返，删除旧会话页路由和 URI builder。全局设置仅保留系统授权主入口及单一折叠高级范围配置，移除重复的后端详情／连接操作及第三步说明；系统授权页的真实 Root／Shizuku／无障碍检查继续使用原实现。被移除 UI 的专属展示映射与过时测试随之删除，权限策略与其失败检查未放宽。

设备状态按 APK 阶段区分：此前页面式 Mobile Use 在 PLC110 通过缺后端提示、取消、授权管理返回及范围设置保存返回；它不能证明新弹窗或停止关闭行为已在设备通过。手机已保留数据更新一次终端候选；新弹窗候选仍需完成主机验证后更新与交互验收。不清数据、不新授权、不发送真实模型请求；合成草稿 `NAVTEST_nav_20261027` 尚未发送。

## Final dialog and terminal device verification

本节更新前述待验状态，不将旧页面或旧 APK 的结果归入新弹窗。最终源码完整 Debug 门禁通过：Spotless、Detekt、两渠道 Lint、全量 JVM 测试、两渠道 Debug APK 与 AndroidTest 编译；源码／文档／ADR／本地化／秘密、依赖锁和最终 APK 渠道边界检查通过。两次中间 Lint 分别发现直接读取 StateFlow 和未记忆的导航 entry 查询，已按生命周期订阅和实际 entry 键修正后重新通过；未跳过检查，未运行 Release/R8。

PLC110／Android 15／API 35 的 Advanced 保留数据覆盖安装最终 Debug 候选，版本仍为 0.0.8／versionCode 1，签名不变，安装后 APK 字节校验一致。实际通过：

- 会话内手机操作弹窗的缺后端提示、取消、授权管理顶部返回／系统返回恢复原弹窗、横屏重建保留弹窗，未自动启用或授予系统权限。
- 独立手机操作设置默认仅系统授权与折叠高级配置；现有全手机范围未变，不改范围保存后返回插件来源页。
- 新建终端 13～16，对应四组真实 shell 进程；无害 echo 正确回显。第五次新建请求在原终端页提示容量满，无空白页。终端 13 实际退出后立即从列表消失，切到仍运行的终端 16；释放后新建为 17，拒绝未消耗编号。离开／重新进入重连原 17，未新建替代 shell。
- 本轮终端 13～17 全部自行 exit，依次移除并切到剩余项；最终无测试 shell 进程。旧 1～3 仍为状态待确认，没有当作已停止隐藏或擅自删除身份。列表完全为空的分支由主机测试覆盖，未为此清理旧状态。
- 知识编辑顶部／系统返回均先确认未保存修改，继续编辑保留正文，保存并返回回到列表；跨插件、知识库切换后返回会话，不累积旧功能区栈。
- 系统文件选择器取消返回原会话，草稿保留；文件管理器返回先退出搜索，再回文件首页。

本轮没有新增失败的设备用例。以下仍未在此最终包实机执行：新增系统授权后的实际启用、主动撤权、保存失败注入、进程死亡、外部提醒投递、Standard 交互与其他 OEM／手势组合；相关主机覆盖不代替设备通过。未向其他应用执行手机操作，未请求真实模型或录音。竖屏设置已恢复，合成会话草稿清除；保留一篇正文为 `NAVTEST_UNSAVED_20261027` 的保存测试笔记，未删除共享内容。未提交、推送或发布。

## Owner revision: four reusable manual windows

用户改为最多四个窗口，显示号复用 1～4，替代上一节持续递增策略。已删除递增编号实现与对应旧行为测试，新增独立窗口槽位登记；当前消费者统一使用槽位，不读取旧编号格式。窗口号不参与 PTY 或进程寻址。

PLC110 旧窗口 1～3 的宿主身份仍在，但三份对应 PTY journal 均不存在；旧实现将缺失记录映射为 UNKNOWN 并要求退出证明才能移除，形成不可用且无法关闭的入口。新列表只呈现可恢复的实际窗口，缺失记录不再占展示槽位；内部原身份与未知执行结果保留，未删除记录或猜测 PID 杀进程。已存在的正常 shell 优先保留和重连。关闭仍存活窗口需停止其准确身份，停止中不提前释放物理容量；未连接窗口退出展示不等于宣称底层命令成功或终止。

新增槽位重启／重排稳定、释放复用、四槽拒绝不改登记、并发包装器一致、未知记录排除与启动／停止占位回归；现有原子容量、取消、进程身份和已退出展示测试继续保留。最终完整检查及装机结果待本轮实际执行完成后更新。装机前检测到两个不属于本轮的活跃 shell，已请求用户先结束，尚未覆盖安装或停止它们。

本轮最终源码完整双渠道 Debug 门禁通过，终端 JVM 共 57 项，无失败或跳过；补充槽位存储失败与冲突校验后再次通过定向检查。源码／文档／ADR／本地化／秘密、依赖锁及最终 APK 边界检查通过。两渠道 Debug APK 已生成，版本 0.0.8／code 1，签名未变。新槽位候选尚未安装：PLC110 仍有两个非本轮创建的活跃 shell，已请求用户先结束，未替用户停止或覆盖安装。上一节设备通过仍仅适用于旧递增候选，不能作为本节 1～4 复用与失联窗口移除的设备通过证据。本轮不清数据、不删控制记录、不提交或发布。

### Direct upgrade interruption verified on PLC110

用户随后明确允许保留两个运行终端直接覆盖测试，因此解除上一段装机阻塞。正常同签名 `adb install -r` 更新 Advanced，仍为 0.0.8／code 1；安装后实际 APK 与主机已验候选字节一致，首次安装时间不变，未清数据或手动先停止原 shell。

安装前记录应用、PRoot 宿主及两个 shell 的 PID、父进程和内核启动时间，未读取命令内容。安装完成后应用 UID 下这些进程全部结束；重新启动是新进程身份，两个原 shell 没有继续后台执行或被假装恢复。本结论是 PLC110 的实际覆盖结果，不承诺所有 Android／OEM 的任意外部守护进程行为。普通离开终端页面与覆盖安装不同：前者保留 shell，已在新包验证返回重连。

新包实际通过：旧三个缺失 PTY 的条目不再呈现；新建四个真实窗口编号 1～4；第五次原页容量提示且登记不变；显式关闭 2 号释放槽位，新建复用 2 号但 UUID 不同，其他三号身份不变；无害 echo 正确回显；离开再进入保持当前窗口 UUID；本轮测试 shell 全部 exit 后窗口列表为空、只提供启动入口，没有自动新建或待确认死条目。未删除原控制记录、数据库或 Linux 文件，未新增系统授权，未读取原终端命令或重发它们。未提交、推送或发布。

## Proven-ended manual state reclamation

Owner approved implementation and host validation, not deletion of existing phone data. New reclamation waits for exact exit proof and all attached views, including read-only observers. A durable short retry bridges host settlement and Runtime removal; successful cleanup removes the pending entry. Metadata quarantines are deterministic and recoverable after interruption. Native fd-relative operations do not traverse guest links; partial failure preserves the original proof and can retry. The same C implementation runs in host fixtures and the private Android Runtime. No new execution domain or migration chain.

Triggers are manual-terminal entry/refresh and detachment of ended windows. Reclaim matching manual owner/journal/temporary directories; preserve workspace files, shell history, runtime/model installations, credentials and Agent results. UNKNOWN without independent exit proof remains retained privately. No phone or emulator operation was performed for this reclamation change; installation must first confirm the concrete old manual states to be permanently removed. Earlier phone results apply to the window simplification candidate, not this new cleanup implementation.

### Agent path audit — separate from manual windows

Agent `bash`/Linux execution uses `LinuxJobExecution`, `ProotJobRunner` and detached Job services, not the manual PTY window registry. Physical job capacity is released in the executor's cleanup `finally`; releasing a service reservation or retaining historical results does not masquerade as physical exit. `ProotJobStore.reconcile` removes collected payloads while keeping metadata and detached ownership. Its existing retention policy keeps reconciled tombstones for seven days and expires uncollected payloads after thirty days; results needed by read/wait/audit must not be treated as manual-window scratch data.

A bounded local probe reproduced a separate admission limitation: 128 expired unreconciled markers, with zero active jobs, still reject a fresh journal entry. `prune` skips already-expired markers and `pruneAndBudgetAvailable` counts all metadata toward its 128-entry budget. A second probe verifies physical completion releases the independent execution slot. These are host fixtures, not real model or device runs. That probe describes the pre-fix baseline. The subsequent host-only implementation separates outstanding results from short-lived receipts, removes expired identity markers after the bounded retention window, and preserves active/unknown jobs and unread results. Physical limits remain unchanged. Launch scratch is reclaimed only after confirmed completion; collection and expiry commit only after payload deletion succeeds. Output descriptor acquisition is serialized with collection. Host-persisted execution results and audit are untouched. No existing device records were changed.

Reclamation closeout: final-source complete Debug gate passed for both channels, including all JVM tests and AndroidTest compilation. Terminal tests: 64 passed; proot-core: 202 passed, including nine reclamation filesystem cases. The separate Agent budget probe passed its two bounded assertions; its initial temporary-source wiring failure was corrected before the actual run. Source/document/ADR/i18n/secrets, dependency locks and Debug APK boundary checks passed.

Because this change adds a JNI entry, a focused Advanced ARM64 Release lint/R8/assemble check also passed. The final APK retains the exact native class/method descriptor and exports the matching ELF symbol. This is an unsigned host-validation artifact, not a published or device-validated release; Standard Release was not rerun. Intermediate formatter/static-analysis failures and the macOS Java-directory-stream limitation were resolved; filesystem tests now execute the same bounded fd-relative C implementation used by Android.

No device was installed, stopped or cleared in this phase. Current phone still has the previously tested reusable-window build. Before testing reclamation against existing phone state, inventory and explicitly confirm the exact proven-ended owner/journal and per-terminal `/tmp` contents to be permanently removed. Workspaces, shell home/history and Agent audit/results are excluded. Unknown identities without independent stop proof remain private and do not consume manual-window capacity. No commit, push or publication.

### Agent lifecycle fix — host closeout

Final complete Debug gate passed for Standard and Advanced: source/document/ADR/i18n/secrets checks, formatting/Detekt/Lint, JVM tests, Debug packaging, dependency locks and APK/process/channel boundaries. Runtime app JVM suite: 45 passed, including 14 journal lifecycle regressions. Coverage includes 128 expired markers, retained recent receipts, unread-result backpressure, active/unknown preservation, cancellation/failure/timeout scratch, restart retention, open output descriptors, lost-ACK retry, cleanup failure/retry and concurrent journal access. Initial static-rule failures were corrected before the successful final gate.

Capacity release still uses the original executor exit boundary; no physical limit was raised. Historical receipts no longer consume outstanding-result admission, while host-persisted results/audit remain untouched. Known-ended launch scratch is reclaimed after exit or on a later eligible sweep; unknown outcomes remain retained. Tests model interruption and restart in isolated host fixtures; they do not prove Android process-death, OEM behavior or real-device reclamation. No phone/emulator operation, real Agent execution, existing-data deletion, commit, push or release was performed for this fix. The earlier manual-window device results do not validate the new manual or Agent reclamation implementation.

### Daily emulator lifecycle validation

Owner explicitly authorized the daily Helix_API_36 emulator (API 36). Validation used a newly installed independent Advanced `.developer.asrvalidation` package, version 0.0.8/code 1, and the isolated Runtime library test host. Existing daily Standard/Advanced packages and their data were not updated or cleared; PLC110 was not accessed. No model/account calls or new system permissions.

Final device checks passed: seven Runtime cases cover 128 expired markers followed by a real command, unread archive preservation, an open descriptor after ACK, exact/failed acknowledgement, running/pre-input cancellation, completion ownership release and orphan identity safety. The real manual-window page passed four live PTYs, original-page capacity notice with no new identity, natural exit and slot reuse, explicit close/reopen, fresh identities, owner/journal/temporary removal and workspace-file preservation. Normal-UI package replacement interrupted two actual shell processes; the old app/Runtime/shell processes ended, recovery reclaimed the old state, and a fresh independent window reused slot 1. This is emulator evidence, not physical-device or OEM acceptance.

Device validation found one additional production defect: capacity rejection left an 8-byte empty owner control file. Reclamation now compares/removes only the empty current-format header and resumes an interrupted empty-header quarantine. A host regression preserves a nonempty active identity while testing repeated and interrupted empty-header removal. The final device inventory contained no owner or PTY journal files, and fixture assertions checked the corresponding per-terminal temporary directories. Workspaces/history/results remain outside this cleanup scope.

Retained failures: the first new fixture had a JUnit non-void return, followed by an incorrect expected workspace path; both were test defects. The later empty-owner assertion exposed the production defect above. An instrumentation-driven overwrite initially could not establish package-replacement exit proof because Android reported the Runtime as force-stopped by instrumentation teardown. The product correctly retained UNKNOWN rather than weakening proof. A subsequent ordinary-UI overwrite, without an instrumented preparation process, passed recovery with real platform package-update exit evidence. No evidence was discarded and no database/reset workaround was used.

Existing daily installations, shared files, credentials, models and Linux environments were untouched. Only self-created fixture shells were started/closed; no user shell was stopped. Independent validation packages/runtime fixtures remain installed, without active test windows; no uninstall or app-data clearing was performed. No commit, push or release.

### Daily Advanced installation follow-up

Owner then authorized updating the real daily Advanced package on Helix_API_36 (emulator-5554), independently of the validation package above. Preflight found no live shell, all stored Turns/model calls completed, five stopped PTY records with explicit process-exit proofs, and a main database matching the current schema identity. The installed and candidate debug certificates matched. A normal preserve-data update installed 0.0.8/code 1; no uninstall, database deletion, global reset, Standard update or physical-device operation.

Ordinary UI startup passed. Mobile Use opened its session dialog and returned without changing the existing enabled state, application scope or system permissions. A new terminal produced synthetic echo output; leaving and re-entering preserved the same window and allowed another echo. Natural exit removed the window, and another explicit start reused display number 1 with a distinct identity. Both owned smoke shells exited normally and their journals disappeared; the manual owner directory ended empty. Four pre-existing unbound stopped PTY journals were retained privately, rather than deleting unrelated historical state to achieve an empty inventory; they did not occupy the window list or physical capacity. No historical command contents were read.

This is an unpublished local Debug build containing the lifecycle/navigation fixes, not a replacement of the public v0.0.8 Release assets. README already distinguishes `main` work from published APKs and retains the public preview boundaries, so no download/version claim was changed. Remaining work is review and owner-authorized grouped commits; physical-device acceptance and public redistribution remain separate. No new production code change was needed during this installation smoke.

### Expanded daily-emulator regression — coverage matrix

Owner requested all feasible emulator checks. This matrix tracks functional paths on the designated Helix_API_36/API 36, not all combinations/devices. Existing unchanged lifecycle evidence is reused explicitly. Fault fixtures and synthetic content use the independent Advanced validation package; daily Advanced is limited to nondestructive UI smoke. No real model/ASR/account operations or new system permissions.

| Area | Evidence / boundary | Final scoped status |
| --- | --- | --- |
| Terminal and Agent lifecycle | Prior nine device cases, ordinary-UI overwrite and daily smoke; no unchanged long-run repetition | Reused passed; OEM/real device not tested |
| Conversation/navigation/drafts | HierarchicalNavigation, ConversationDraftRecovery, TaskPreparationFlow | Passed, bounded cases below |
| Provider selection/failure | ProviderModelSelectionFeedback and loopback-only ProviderFlow | Passed, bounded cases below |
| Files and return layers | FilesHome; isolated document-provider/SAF fixtures; real chooser cancel where available | Passed, bounded cases below |
| Plugin/Skill import | PluginImport, Plugin lifecycle/install/authoring; malformed/cancel/concurrent changes | Passed, bounded cases below |
| Mobile Use | Owned no-backend dialog, authorization-page return/cancel; existing daily enabled-dialog smoke | Passed: no-backend prompt/return/recreation, save and error UI; no new backend grant |
| Knowledge/reference UI | Knowledge shell, reading/edit/save recovery, reference selection/version/cancel | Passed, bounded cases below |
| Shared documents/recovery | MediaStore CRUD/external edits, restore and recovery-authority fixtures | Passed with real IO and injected picker; no real persisted OS grant |
| Voice client | Synthetic PCM/ASR/consent/download fixtures only | Passed synthetic PCM/transport and actual permission denial; real mic/cloud excluded |
| Other local contracts | Existing relevant reminder/connector/Skill/approval fixtures after safety review | Passed, bounded cases below |

Missing permissions/accounts, physical-device/OEM behavior, external publishing and paid/real service calls remain untested rather than simulated passes. Existing data and historical records are not reset to obtain passing results.

Expanded regression result: 144 distinct current-task device methods passed, with zero unresolved failures or skips in their final per-method records. Reused nine unchanged lifecycle checks are separate, not counted again. This is bounded Advanced/API 36 coverage, not the entire repository test inventory or all feature combinations. It includes navigation/history/provider drafts and selection, permission-page navigation, Mobile Use prompt/return/recreation and save/failure behavior, local JSON/plugin ZIP/Skill ZIP import through the production file picker, real system-picker open/cancel, shared document/reference recovery, synthetic voice, approvals, cancellation races, plan authorization, local reminder contracts, artifact delivery and browser entry/clear cancellation. The isolated unknown-terminal fixture also proved new real PTY admission while retaining unknown control facts. No active user task was stopped.

Initial failures and fixes were test-contract/fixture issues, not suppressed product failures: drawer expectations still used the superseded 360dp/88% specification instead of the accepted 330dp/86% design; flavor detection mistook a validation package for Standard; a model-picker test used a removed button tag; Mobile Use tests did not wait for loading or account for collapsed advanced settings/current BackHandler; system-picker cancellation incorrectly used Espresso outside the app. The missing-backend journey explicitly saves the synthetic application scope first, keeping scope configuration separate from Android authorization. Artifact fixtures shared fixed IDs and left synthetic results unverified across app restarts, so normal startup correctly parked them for review. Each artifact case now has independent identities and a verified synthetic settled result; UI waits for data readiness and scrolls actual lazy lists. Existing historical rows were neither reset nor marked successful. The complete affected artifact class passed again.

No production code changed during this expanded regression; changes are device tests and this record. The real daily Advanced remains the previously installed and smoke-tested local build; Standard and PLC110 remain untouched. Explicit remaining boundaries: no new accessibility/Shizuku/root/ADB or directory grant, real cross-app Mobile Use, real account/OAuth/paid model/online ASR/microphone flow, actual reminder notification delivery, actual reinstall takeover of a user library, API/OEM/device-size matrix or long-duration soak. Restore/SAF tests use controlled document providers and injected grant facts; synthetic PCM and local loopback responses are not live-service acceptance. Independent validation/test packages remain installed under the no-uninstall constraint. No commit/push/release.

Final host closeout for the expanded pass: complete Debug/source/format/Detekt/Lint/JVM/build/lock/APK-boundary gate passed, both Standard and Advanced AndroidTest APKs compiled successfully, and the updated documentation check passed. The first interrupted gate had no terminal result and was not counted. Production files were unchanged during this expanded pass, so the previously installed daily Advanced smoke remains applicable; no redundant daily update, Release/R8 or real-service rerun was made. Standalone Runtime-only crash/whole-device reboot/long-soak combinations were not added to this bounded run; ordinary-UI package interruption evidence and the separate unknown-state fixture remain explicitly scoped.

## Internal format baseline and replacement installation

The owner requested necessary App-owned format versions remain 1 and redundant versions be removed. Subscription request envelopes (previously 1–5), encrypted subscription credential payloads (previously written as 3 and accepting 1–3), pending MCP OAuth attempts (2), and redacted prompt audit payloads (3) no longer carry a version field. Single-package request/credential/attempt readers validate their actual current structure and reject replaced formats instead of maintaining numbered branches. Pure request-budget and context-compaction diagnostic payloads also drop redundant version fields. Product versionName remains 0.0.8, versionCode and database baselines remain 1. External protocols, dependency releases, content revisions and rejection-test samples are not renumbered. The obsolete database comment describing an additive version-2 migration was corrected; no database shape or migration was added.

Retained version-1 boundaries have specific consumers: Room requires database versions; PRoot/QuickJS/CLI IPC checks independently decoded component messages; terminal ownership, execution/job journals and grant/config stores reject incompatible safety-relevant persistent records; retained model output and session exports have independent read/verification consumers; plugin manifests and native tool descriptors participate in the extension/discovery contracts. Diagnostic bundle exports retain their explicit external file format. No replacement schema number or compatibility alias was added to the versionless payloads.

The audit was expanded to all production source sets (`main`, `consumer`, `developer`), packaged assets/resources, native sources and build/asset generators, not just numeric values greater than 1. Marker searches included version/schema/protocol/format/magic, JSON/properties keys, binary reads/writes and versioned filenames/namespaces. Test-only invalid versions, historical reports, generated build copies and dependency versions were separated from live contracts. The following is the coverage/disposition checklist, not a claim that every feature was device-tested:

| Production area / current source | Disposition and reason |
|---|---|
| App/build configuration and generated manifests: `app/build.gradle.kts`, `settings.gradle.kts` | Keep owner-assigned product 0.0.8 and Android-required code 1. Channel/validation suffixes are package identities, not format versions. No release operation. |
| Core storage: `HelixDatabase`, `ReminderStore`, `ScheduledAgentStore`, exported Room schema resources | Keep all three DB baselines at 1: Room consumes schema version and identity. No migration readers or schema bump introduced. |
| Model/domain, Turn/session state: `SessionInputValidation`, `TurnRuntimeRecordRepository`, `SessionRunControlRepository`, `ExecutionTarget`, `ToolExecutionEnvelope` | Keep current format/config/protocol 1 for durable admitted-input and recovery/dispatch contracts. Generic value types still represent external descriptors; actual content revisions/sequences/generations are not release versions. |
| Permissions/identity: `UserScopeCodec`, `SessionPermissionConfig`, `SessionPermissionEditService`, `SessionPermissionAuditPayload`, `MobileUseGrantStore`, `MobileUseScreenConsent` | Keep current 1/`v1` discriminators. Their decoders distinguish persisted scope/approval/consent forms; malformed data must not become a grant. No new version branches. |
| Runtime IPC and output: `JsProtocol`, `ProotRuntimeProtocol`, `ProotLogWire`, `CliRuntimeProtocol`, `CliModelEventCodec`, `CliModelProgressCodec` | Keep 1 at independently decoded private-process/wire and retained-output boundaries. Subscription request-body numbering is removed because one exact current body schema sits inside that IPC. |
| Runtime/PTY/Agent journals: `RuntimeManifestCodec`, `RuntimeLockCodec`, `CliRuntimeLockCodec`, `PtySessionRecordCodec`, `ExecutionOwnershipStore`, `ProotJobRecord`, `ProotJobBindingStore`, `DetachedJobStore`, `DetachedJobObservationStore`, `SubscriptionJobBindingStore` | Keep 1 for durable runtime identity/admission/result ownership, installation activation and recovery. Binary PTY record writer/reader both use 1. Their PID/start/generation identities and outcome facts remain truthful. No terminal-capacity or collection semantics changed. |
| Native/JNI and bundled components: `proot_native.c`, `proot_pty.c`, `proot_state_files.c`, `workspace_identity.c`, `helix_model.cpp`, runtime asset locks/builders | No hidden Helix format increment found. JNI/OS constants, inode generation, ELF/TAR formats and upstream RootFS/CLI/model versions are not Helix release counters. Asset lock formats stay 1; upstream pinned versions/hashes remain unchanged. |
| Knowledge/memory/plans: `KnowledgeDocumentCodec`, `MarkdownMemoryStore`, `PlanRepository`, document snapshots | Keep shared document `format=1` as the discriminator for independently edited/imported Markdown metadata. Memory bodies do not gain a version field. Plan approvals/content revisions and immutable document hashes remain real content identities, not reset to 1. |
| Files/workspace: `SafGrantStore`, `AllFilesRootsStore`, `ManualTransferJournal`, `FileLibraryCodec` | Keep current format 1 for persisted grant/root and write-ahead recovery decoding; FileLibrary's current navigation-metadata codec rejects unsupported records before overwriting favorites. Canonical path/size/duplicate checks remain. No new or renamed preference namespace or automatic reset. |
| Browser: `BrowserSnapshotScript`, `BrowserActionScript`, `SnapshotToken` | Keep script/token 1: Kotlin, untrusted-page JS and snapshot/action readers check the actual boundary identity. Android/WebView package versions and browser content changes are external/runtime facts. |
| Extensions/Skills/plugins/connectors: bundled `plugin.json`, `PluginManifest`, `InstalledPlugin`, `SignedConnectorIndex`, `HelixUserGuide` | Built-in plugin versions and installed-record format stay 1. Signed index `/v1` and external Agent Plugins schema IDs remain valid interface discriminators. External imported plugin versions are not normalized. Remove unused formatVersion from the derived help manifest; its content revision remains a hash. |
| Tools across files/android/browser/root/device/framework, code/memory/plan/plugin tools | Keep native descriptor ToolVersion 1: registry identity, discovery, binding and authorization consume it. Short aliases keep the same contracts. Imported MCP/A2A/extension tool metadata is not rewritten as an App release version. |
| Provider/network/account/voice: API/OpenAI/Anthropic/MCP/A2A adapters, account/session and speech settings | External protocol dates, JSON-RPC 2.0, A2A 1.0, HTTP versions and OAuth endpoint paths are unchanged. Existing cryptographic key aliases, notification channel IDs, preference keys and hash-domain prefixes containing `v1` are stable identities, not independently incrementing versions; renaming them would detach valid state or keys. No version-only key renaming. |
| OAuth and admitted snapshots: `OAuthCredentialCodec`, `OAuthClientRegistrations`, `ExpertProfileSnapshotCodec`, `GoalDetachedBudget` | Keep current 1 where the persisted decoder guards bound issuer/client/generation, frozen admitted configuration or transferred execution-budget facts. These have no multi-version fallback. Remove numbered branches only from the subscription vault and pending attempt body identified above; never clear credentials to pass a test. |
| Exports/diagnostics/audit: `SessionExportFormat`, `DiagnosticBundlePreview`, `PromptSnapshotRecord`, `RequestBudgetDiagnostics`, `ContextCompactionRound`, `SessionFork` | Keep externally consumed export/bundle schema 1. Remove redundant versions from prompt/budget/compaction diagnostics and the write-only fork-origin audit; historical audit bytes remain unchanged. |

The additional write-only help/fork fields were removed after the first structural candidate passed its gate; final build/acceptance must therefore reference the final candidate below, not infer success from the earlier binary. Current prefixed identities remain fixed at 1; no new alternative marker replaces a removed field.

The `Cli*` names belong to the existing Advanced subscription service boundary, not a command-line executable invocation. `SubscriptionProvider` calls `CliModelJobClient`, the non-exported `CliRuntimeService` decodes the request, and subscription adapters use HTTP transports. Its private encrypted credential vault is still used by those login/controllers and adapters. The module was not deleted or renamed during this format correction.

| State carried over by installation | Current treatment |
|---|---|
| Subscription request | One structural schema always includes providerId, images and per-message images; replayOwner is optional and validated for its provider. Any old numbered schema, including implicit-Codex 1, is rejected, not guessed or replayed. App/client and service are packaged in the same APK. Interrupted jobs retain the existing terminal/recovery/result contracts; installation is not uninterrupted model execution. |
| Subscription credential | Current fields require the existing login revision. Old numbered blobs and revisionless payloads fail closed as CREDENTIAL_ERROR; the existing UI asks the user to sign in again. Reading does not rewrite, remove or regenerate login identity. A valid current blob reopens normally. AES/Keystore ownership and key alias are unchanged. No real credential was read, transformed or deleted. |
| Pending MCP OAuth | Current attempts retain issuer/resource/PKCE binding and require the exact metadata fields. Old numbered and inline-codeVerifier attempts are rejected. A callback cannot exchange tokens from an unreadable attempt; the existing concise failure directs the user to retry plugin login. Stored credentials and unrelated attempts are not reset by this change. |
| Prompt snapshot | New redacted audit payloads have no version field. Historical audit bytes are not rewritten and are not used as a live request or startup migration. No prompt body is added to storage. |

Focused JVM coverage: 37 cases across request/provider/image/replay codecs, credential ownership/revision, issuer binding and prompt snapshots passed for the initial version-1 candidate; that candidate was superseded by the owner's instruction to remove redundant fields. Credential fixtures also cover replaced versions and revisionless payloads remaining unchanged after rejection, logout/late refresh not resurrecting another login, and current credentials reopening. This is synthetic host evidence, not an Android encrypted-vault or real-login upgrade claim.

Replacement validation uses only daily Helix_API_36 / emulator-5554, API 36, independent `com.helix.agent.developer.asrvalidation`. The test APK seeds old request-1, OAuth-2, audit-3 and a content sentinel into its own private fixture directory before the application APK is replaced with `adb install -r`. Both device methods passed for the initial candidate; final structural-schema acceptance is recorded separately below rather than inferred from that run. The checks require all seeded bytes to survive, obsolete live formats to be rejected, and five provider envelopes to complete durable local `helix-fixture` service calls and acknowledge their synthetic results. No network/model/login call or new system grant is used. Only the test's enumerated synthetic files are removed at its normal successful end. APK identities, installation results and raw method logs are under ignored `build/internal-version-one-2026-10-27/`.

The first validation build stopped at formatting of a long test fixture literal; splitting the literal fixed it without changing the JSON or assertions, and the next build passed. Daily Standard/Advanced and the physical phone were not updated. Covering an existing installation can require signing in again or restarting an in-flight authorization; it must not be advertised as universally seamless. No App migration/legacy reader, automatic credential reset, internal version bump, AGENTS edit, commit, push or release was introduced. Real credential/Keystore upgrade, real OAuth and interrupted remote effects remain untested.

Final correction found during the full-scope review: `McpOAuthAttemptStore.cleanupExpired` previously treated unreadable records as expired. It now removes only records it can parse and positively establish as expired; replaced/corrupt attempt metadata and its verifier remain untouched. This does not accept an old schema or allow its token exchange. A new host fixture verifies unchanged bytes and verifier aliases for both an old numbered attempt and malformed JSON, alongside the retained normal expiry/one-time consumption tests.

Final acceptance, after all eight redundant field families and the expiry correction: complete `scripts/check-all.sh --debug` passed, both normal-channel AndroidTest APKs assembled, and 41 targeted host cases passed across request/image/replay codecs, credential revision/ownership, OAuth issuer/attempt storage and prompt records. Earlier candidate passes are intermediate evidence, not substitutes for this final gate. No Release/R8 rerun was needed for these ordinary format/diagnostic changes.

The final independent package was tested by restoring the original pre-change validation APK without clearing data, installing the final test APK, and seeding the fixture against the original production readers. Both the old implicit-Codex request-1 and complete OAuth-attempt-2 decoded successfully before the final APK replacement. After `install -r`, both device methods passed; the old files/sentinel were unchanged, old live bodies were rejected, five provider-local fixture jobs succeeded, and only the test's own fixture files/results were acknowledged/cleaned normally. The final `MainActivity` reached the resumed state. Installed APK bytes match the signed final candidate, 0.0.8/code 1; identities and the two-method XML are recorded in `build/internal-version-one-2026-10-27/accepted-identity.json` and `accepted-upgrade.xml`.

The final tracked-source changes are uncommitted. Necessary formats remain 1; redundant subscription/pending-OAuth/prompt/budget/compaction/help/fork version fields are absent. The existing daily app packages, physical phone, real subscriptions/credentials and published v0.0.8 assets were not changed. An old Advanced subscription-vault blob requires explicit sign-in again; an unfinished MCP authorization must be restarted. Existing Helix account login and stored MCP credentials use different, unchanged stores. No universal real-account upgrade or uninterrupted-execution claim is made.

## Authorized commit and CI follow-up

The owner subsequently authorized committing and pushing this completed work to test CI. The earlier uncommitted/no-push statements above describe their validation checkpoints. Current work is being grouped into application/runtime behavior, internal formats, and CI/test-maintenance commits; remote verification is pending. This follow-up does not authorize a new release, tag, device run or account operation.

## PLC110 follow-up: approved credential conversion and current Debug acceptance

On 2026-10-28 the owner authorized a one-time device-local conversion of the two existing Advanced subscription blobs, followed by an update and bounded physical-device regression. The source was `9362690b` (the seven host-only CI jobs passed); the public v0.0.8 assets were not replaced. The freshly assembled normal Advanced Debug APK remained 0.0.8/code 1, used the existing debug signer, and matched the installed APK bytes. Artifact identity and scoped raw results are under ignored `build/physical-regression-2026-10-28/`.

The Codex and Copilot payloads both passed exact old-v3 structure, original login-revision and existing-Keystore-key validation. A separately signed-with-the-existing-debug-key instrumentation helper ran in the target application's UID, retained two private encrypted backups, removed only the redundant version field, encrypted with the unchanged AES-GCM envelope and atomically replaced the two files. All remaining plaintext fields were compared in device memory. The new production vault reader then opened both credentials successfully. No tokens were exported, logged, refreshed or sent to a service; no replacement key/revision was generated. The helper is local-only and was uninstalled afterwards; no compatibility reader or migration path was added to the App. These results establish local readability, not server-side validity or a universal upgrade guarantee.

| Physical acceptance on PLC110 / Android 15 / Advanced | Result |
|---|---|
| Real shell prompt and synthetic echo | Passed after waiting for the terminal view and focusing its input. An earlier unfocused Enter activated navigation; it was not counted as a shell failure or pass. |
| Four running manual windows; fifth creation | Passed: original-page capacity dialog, no blank window; exit released a slot and reused display number 4 with a new identity. |
| Leave/re-enter; stop/exit | Passed: return retained the same running window; each owned exit removed the stopped window and selected the remaining running window or empty entry. |
| Owned temporary state | Passed: all five created identities had no remaining `.pty`, `.owner` or matching runtime temporary directory after exit. No old records were manually removed. |
| Mobile Use dialog and navigation | Passed: missing backend explained before navigation; authorization-page Back restored the original dialog; cancel retained the draft; unchanged application-scope save returned to its source. No new backend permission was enabled. |
| Local plugin/Skill import | Passed: synthetic ZIP and loopback-only MCP JSON preview/install, standalone Skill ZIP installed disabled, persisted synthetic Skill marker readback, malformed JSON feedback. Own plugin connections were removed afterwards. |
| System file picker | Opened the real DocumentsUI picker without the previously reported loading error; cancellation returned. No personal file or new directory grant was selected. This does not establish all provider/file cases. |
| Knowledge and references | Passed: synthetic Markdown edit, unsaved-return confirmation/continue, save/readback, add/remove reference without sending, draft survives Mobile Use cancellation. External-editor/reinstall takeover was not rerun. |
| Agent Runtime in independent test package | Five tests passed: normal release, cancellation, reconciled payload/proof separation, orphan handling, and 128 expired records followed by real execution, scratch reclamation, unread archive preservation, open-reader-after-ACK and retained receipt. Initial embedded-runtime installation accounted for most of the four-test run's 211 seconds; the subsequent 128-record test took about two seconds. |

The test-only Runtime package was uninstalled after its completed runs. The daily Advanced remains updated; Standard, the emulator, original shared documents, ordinary login stores, models and Linux environment were not reset. The private encrypted credential backups remain. A clearly named synthetic note, a disabled synthetic Skill snapshot and the import fixture files remain for inspection; the synthetic draft/reference were cleared and test shells exited normally. No true model/ASR/OAuth/network-login request, cross-app phone control, new accessibility/Shizuku/root authorization, arbitrary old-state cleanup, process-death matrix or long-duration soak was performed. No production source change, commit, push or publication was made for this follow-up.
