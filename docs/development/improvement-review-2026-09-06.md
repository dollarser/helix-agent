# Helix 全仓优化改进审查（2026-09-06）

文档状态：快照（review snapshot）
性质：一次性全仓代码与文档审查报告，**不是当前状态源**。当前状态以 [status.md](status.md) 为准；路线以 [roadmap.md](roadmap.md) 为准。本文的问题清单按"发现时的仓库状态"成立，实施前需逐项复核是否已被后续 HXA 修复。

## 0. 审查元信息

- **审查日期**：2026-09-06
- **基线**：`main` 61bad35 + 工作区 40 个文件未提交改动（HXA-125 进行中，含 CI、lockfile、核心代码）。结论基于工作区状态。
- **方法**：六个领域并行深度审查（core 五模块 / app+feature 七 source set / runtime 七模块 / provider+tools+extensions+spikes / 构建系统与工程设施 / 文档体系），高严重度发现由父会话逐行回源码复核（含两处独立复现：S1 重定向、R-H1 stdoutBytes 上限）。
- **范围**：780 个 Kotlin 文件 / 153,356 行；docs/ 全部 162 个 Markdown（20 ADR、91 份完成记录、roadmap、status、安全/架构/进展文档）；根构建文件、35 个 lockfile、20 个 scripts/ 脚本、CI workflow。
- **ADR 状态时点说明**：扫描时 `ADR-0009` 为 `proposed`；扫描后（2026-09-06）AGENTS.md 已将其引用为 `accepted`。凡涉及 ADR-0009 的决定项（M10 收口、bounded-orchestration spike 生命周期）需按当前 ADR 文件状态重新核对。

### 基线扫描结果（父会话实测）

| 项 | 结果 |
| --- | --- |
| TODO/FIXME/HACK（.kt） | **0**（6 处 grep 命中均为 `\uXXXX` 文档注释） |
| 全仓静态门禁 | `check-i18n.sh`（697 键三套 parity）/ `check-secrets.sh` / `check-docs.sh`（162 md）/ `verify-adr.sh`（20 ADR）全部 exit 0 |
| git 跟踪卫生 | `local.properties`/`.DS_Store`/`build/`/`.codegraph`/`.kotlin` 均未跟踪且被 .gitignore 覆盖 |
| 测试/main 文件比 | 全模块 ≥0.72（多数 >1.0；仅 `runtime/cli-client` main=1 test=0，该模块为 M11 基线单文件，合理） |
| 工作区 | **45 个路径有变更/未跟踪（40 文件 +689/-225 行未提交）** |
| 提交节奏 | 近 7 天 144 个 commit |
| 最大生产文件 | `ChatService.kt` 3041 / `FilesScreen.kt` 1871 / `BrowserTools.kt` 1537 / `ProotJobRunner.kt` 994 |

## 1. 总体评价

工程质量显著高于平均水平：零 TODO、fail-closed 纪律贯彻彻底、错误文案稳定不泄漏、静态门禁完整且全绿、git 卫生干净、测试面广且失败如实记录（HXA-103 archive 失败、HXA-099 SGLang 超时均未掩盖）、版本全 pinned + lockfile 全覆盖 + 无 mutable 版本/无 DI 框架。

问题集中在四个象限：**少数可造成数据外泄/越界的真实安全缺口**、**可稳定复现的用户可见缺陷**、**check-then-act 并发正确性**、**文档状态漂移**。跨域共性模式见 §3.5。

---

## 2. 安全关键问题（S 组，跨域汇总）

### S1. Provider 传输层跟随重定向：307/308 可把整个会话转发到未批准主机（已独立复核）

- **位置**：`provider/api/src/main/kotlin/com/helix/provider/api/wire/OkHttpWireClient.kt:43-48`（默认 `OkHttpClient.Builder()...build()`，无 redirect 配置）；生产接线 `app/src/main/kotlin/com/helix/app/provider/ProviderFactory.kt:106`（零配置 `OkHttpWireClient()`）。
- **证据**：OkHttp 默认 `followRedirects(true)` + `followSslRedirects(true)`；`grep -rn followRedirects provider/ app/src/main` 零命中。307/308 跳转时 OkHttp 跨主机只剥离 Authorization 头、**不剥离 body**——完整会话历史+工具输出+base64 图片被重发到跳转目标；https→http 跳转也被跟随（TLS 静默降级）。endpoint 的 residence/数据目的地分类按配置 URL 计算，实际落点可不同，与 provider 文档"数据目的地"语义不符。wire 测试与 HXA-025 完成记录均无 redirect 条目。
- **建议**：`followRedirects(false)`，3xx 按非 2xx 走 `mapHttpStatus` 稳定错误；若产品上需容忍同主机跳转，实现有界"同 origin + 限跳数"策略并补测试。~3 行 + 测试，关闭本次审查唯一可直接造成会话数据外泄的缺口。

### S2. PRoot guest 可经 symlink 逃逸模拟命名空间；输出归档跟随 symlink

- **位置**：`runtime/proot-app/.../ProotJobRunner.kt:244-263`（`prootArgs` 未含 PRoot `-S`/禁 symlink 选项）；输出侧 `:564-598`（`buildOutputArchive` 的 `workspace.walkTopDown().filter { it.isFile }` + `sha256OfFile`/`file.length()` 全部跟随链接）；`runtime/proot-core/.../JobZipWriter.kt` `writeEntry` 同样跟随。
- **证据**：PRoot 是 ptrace 路径重写，非内核级 chroot。guest 在 `/workspace` 建 `ln -s <宿主绝对路径> link` 后 `cat link`：guest 传给 openat 的是 `/workspace/link`，PRoot 重写前缀后由**内核在宿主空间**解析最终 symlink → 可读 companion UID 可见的任意宿主文件（companion 自己的 filesDir/journal/runtime lock/RootFS 安装树、同 UID 跨 Job 证据）。现有设备测试 `ProotIsolationDeviceTest.theGuestCannotReadTheCompanionAppData` 只测**直接路径**（被 guest 命名空间解析为 ENOENT），未覆盖 symlink 构造，KDoc "a read attempt cannot even fail open" 对该构造不成立。第二落点：guest 运行中创建的 symlink 进入输出 walk（指向宿主文件时 `isFile` 为 true），目标内容被 hash 并打进交付给主 app 的输出 zip（大小/数量上限仍生效，但等于把 companion 私有文件"洗"进 Job 输出）。
- **建议**：(a) `prootArgs` 加 PRoot `-S`（security 模式拒绝 symlink 解析），先真机 POC 验证 guest 行为并回归 `/proc` 依赖 symlink 的常用工具；(b) `buildOutputArchive`/`JobZipWriter` 对每个文件用 `toRealPath()` 校验落在 workspace 内 + 逐分量 `Files.isSymbolicLink` 检查；(c) 补逃逸设备测试（guest 内 `ln -s <marker> /workspace/link && cat /workspace/link` 必须失败）。

### S3. HttpFetch 传输层不兑现 deadline 契约：慢速滴流可无限挂住线程+socket

- **位置**：`tools/android/src/main/kotlin/com/helix/tools/android/HttpFetchBridgeImpl.kt:139`（`remaining` 只算一次）、`:198-199`（`soTimeout` 只在请求开始设一次）、`:296-309`/`:340-355`（`readBody`/`readFully`/`readUntilClosed` 读循环内无 deadline 复查）；契约声明 `HttpFetchBridge.kt:29-31`（"a timeout is TIMEOUT, not a hang"）。
- **证据**：`soTimeout = min(20s, 剩余)` 之后每次 `input.read` 最多阻塞 20s；服务器每 19.9s 吐 1 字节时读循环每次返回 1 字节、永不到 EOF、永不触 256 KiB cap——fetch 可挂数天而 `deadlineMillis`（工具超时 30s）被无视。dispatcher watchdog 会把**这次 dispatch** 结算为 TIMEOUT（用户可见结果正确），但被 abandon 的线程和 socket 不关闭（`closeQuietly` 只走错误分支）：每次慢速滴流在进程里永久留下一个活线程+一个 open socket，长期运行可被逐步耗尽。connect 阶段 `remaining` 在地址间也不刷新（最坏 2×10s）。
- **建议**：剩余时间传入读循环逐块复查（或按剩余时间逐读设 soTimeout），超期关 socket 返回 TIMEOUT。

### S4. A2A 通道缺 MCP 同级的 egress 地址门控

- **位置**：`extensions/a2a/.../A2aDiscoveryClient.kt:156-183`；对照 `extensions/mcp/.../McpNetworkPolicy.kt:34-48`、`SdkMcpClientFacade.kt:376-390`。
- **证据**：MCP 每次连接做全量 DNS 解析 + `SsrfAddressPolicy.check`（profile+LAN scope，fail-closed）+ `proxy(NO_PROXY)` + 钉扎 Dns；A2A 两条 client 只有超时 + `followRedirects(false)`，全链路无 `dns(...)`/permit（extensions/a2a + app/a2a 包 grep 零命中），只校验 scheme（https/loopback）与 interface origin == card origin。https 主机经 split-horizon DNS 解析到内网地址时 MCP 拒、A2A 放行（携带 bearer+用户数据）。缓解因素：user-explicit 配置 + 强制 TLS + 每次调用仍走 Dispatcher 的 `EgressRequest` policy。
- **建议**：复用 `McpSsrfEndpointGate` 并加 NO_PROXY + 地址钉扎，使两条远程 lane 策略一致（纯 JVM 可测）。

### S5. 原始异常文案（可能含真实文件路径）直接渲染给用户——系统性

- **位置/证据**：
  - `app/.../ui/FilesScreen.kt:360` `loadError = it.message ?: str(...)`（`fileManager.list` 的 NIO/IAE message 可能含 workspaces 真实路径）；
  - `FilesScreen.kt:573` `str(R.string.files_share_failed, e.message.orEmpty())`——`FileProvider.getUriForFile` 的 IAE 形如 "No file found for file:///data/user/0/..."，**直接违反"真实路径永不渲染"**；
  - `app/.../files/FileManagerService.kt:587/589/609/650/692-696/711-713` 多处 `FileOpResult.Error(e.message ?: loc(...))`，由 FilesScreen 455/459/529/530/541/542/1177/1181 原样显示（rename/mkdir/trash/restore/purge 全链路）；
  - `app/.../egress/EgressRuleSection.kt:80/114/118/127` `loadError/formError = it.message`，188-194、207-213 行渲染。
- **违反**："真实路径永不渲染"契约与 ADR-0014 §7（用户可见错误不含内部信息），且均为未本地化英文文案（i18n 缺口）。
- **建议**：统一"封闭错误码 → string-res id"映射（项目已有 `ApprovalUiMapper`/`ConnectionTestMapping` 先例）：`FileManagerService` catch 分支只保留错误码，message 由 strings resolver 在 UI 解析；share 路径固定用资源键不带 `e.message`。

### S6. SSRF：scoped 主机名的跨类 rebinding 仍被放行

- **位置**：`core/policy/.../SsrfAddressPolicy.kt:93-118`（`checkLanHost` 只拒 `PUBLIC || OTHER_RESERVED`）；`core/model/.../NormalizedEndpoint.kt:80-91`（单标签名/`.local` 等归为 `USER_AUTHORIZED_LAN`）。
- **证据**：ADVANCED 下用户对 `router.local:9000` 建精确 scope 后，敌对 mDNS/本地 DNS 可把它 rebind 到 `127.0.0.1` 或 `169.254.x`（非 metadata）——scope 语义只锁 host:port，不锁地址类，Helix 会去连设备自身 loopback/link-local 服务。其余 SSRF 向量已验证安全：十进制/十六进制 IP 在 `NormalizedEndpoint.parse` 即拒；IPv4-mapped/compatible IPv6 解包后按内嵌 IPv4 分类（含 Azure IMDS v6）；userinfo/query/fragment/IDN 全拒；redirect 每跳完整重跑 parse→resolve→check→connect→peer 复验且直连已验证 IP；`revalidatePeer` 拦 in-flight rebinding。
- **建议**：`NetworkOriginScope` 记录创建时的期望地址类（由 literal/residence 推导），`checkLanHost` 拒绝与期望类不一致的解析结果。

### S7. 时间窗口边界检查 Long 溢出

- **位置**：`tools/android/.../NotificationsCalendarTools.kt:198`（`until - since > MAX_NOTIFICATION_WINDOW_MS`）；`tools/android/.../CalendarBridgeImpl.kt:40-43`（`end - start > MAX_EVENT_DURATION_MILLIS`）。
- **证据**：`since=Long.MIN, until=Long.MAX` 时差值回绕为负 → 绕过 24h/30d 检查（`integerSchema` 本身不带 min/max，`intArg` 只验"是合法 long"）。
- **建议**：改 `until > since + WINDOW` 或先 clamp 到 1970..2100。

### S8. A2A 15 分钟超时 × 50ms 固定轮询

- **位置**：`extensions/a2a/.../A2aDynamicToolBridge.kt:124`（timeout=15min）× `app/.../A2aTaskRunner.kt:295-299`（`settleByPolling` 每 50ms 一次真实 `getTask` GET，`:603` `POLL_MILLIS=50L`）。
- **证据**：单次长任务最多 ~18,000 次 HTTP GET（20 req/s）——电池/NAT/远端压力，是范围内最显著的资源缺陷。
- **建议**：非流式 settle 改指数退避（1s→10s 封顶），deadline/取消语义不变（~20 行）。

### S9. 无障碍服务对每条事件无门控跨进程取树

- **位置**：`tools/automation/.../HelixAccessibilityService.kt:60-68` + `:163-178`。
- **证据**：服务开着时**每条** AccessibilityEvent 都执行 `deviceLocked()` + `rootInActiveWindow()`（跨进程）+ recycle；active-session 门控在 `targetObserved` 内部但取树本身不在门控内——无会话时全是纯开销（电量/IPC/对任意前台 App 的持续窗口窥探面）。
- **建议**：`onAccessibilityEvent` 开头无 active session 即 return。

---

## 3. 用户可见缺陷与并发正确性

### 3.1 可稳定复现的用户可见缺陷（高 ROI 小改动）

**U1. FilesScreen 移除 SAF scope 后 composition 崩溃**
- **位置**：`app/src/main/kotlin/com/helix/app/ui/FilesScreen.kt:230` + `1610-1616`。
- **证据**：`val currentSource = sources.first { it.scopeId == selectedScopeId }`（每次 composition 求值）；SAF 面板"移除"回调只更新 `sources = fileManager.sources()`（1613 行），从不重置 `selectedScopeId`。复现：浏览某 SAF scope → 打开 SAF 面板 → 点该 scope 的"移除" → `first{}` 抛 `NoSuchElementException` → 崩溃。FilesScreenTest 无该路径测试（现有 6 用例全在 workspace）。
- **建议**：`firstOrNull { ... } ?: sources.first()` + `LaunchedEffect(sources)` 回落 workspace；补 revoke 后不崩溃的设备测试。

**U2. 发送被门禁拦截时 composer 文本丢失**
- **位置**：`app/src/main/kotlin/com/helix/app/ui/ChatScreen.kt:113-116`。
- **证据**：`onSend = { chatService.send(input.trim()); input = "" }`——send 是 fire-and-forget，门禁（凭据扫描、provider 未测试、明文未确认、vision 未确认、egress 拒绝…）在 workScope 异步执行并 setBlocked；UI 已同步清空输入框。用户看到拦截横幅但刚才打的字没了。
- **建议**：send 返回受理结果（或先做同步可判定的预检），仅在受理/进入 Confirm 时清空；Blocked 时保留 composer 文本。

**U3. MainActivity 每次 Activity 重建都重放 share intent → 重复建草稿会话**
- **位置**：`app/src/main/kotlin/com/helix/app/MainActivity.kt:73-74`。
- **证据**：`onCreate` 无条件执行 `ShareIntentDraft.draftFrom(intent)` + `chatService.acceptShareDraft(...)`，无 `savedInstanceState == null` 守卫。以 ACTION_SEND 启动的任务，root activity 的 `getIntent()` 在配置变更（旋转）/进程重建后仍是原 share intent → 每次重建新建一个 provider-free 草稿会话并重新导入图片，旧草稿被丢弃（`acceptShareDraft` 会 `clearStagedAttachments`）。
- **建议**：`if (savedInstanceState == null) { ...acceptShareDraft(...) }`；onNewIntent 路径保持不变。

**U4. >1 MiB 输出的 PRoot Job 必然假失败（已独立复核）**
- **位置**：`runtime/proot-ipc/.../ProotJobRecord.kt:71-72`（`require(stdoutBytes in 0..MAX_FIELD_BYTES)`，`MAX_FIELD_BYTES = 1 MiB`，:159）；写入点 `runtime/proot-app/.../ProotJobRunner.kt:549-550`（`terminal()` 里 `pending.copy(stdoutBytes = ...)`）；允许的输出上限 `runtime/proot-ipc/.../ProotJobWire.kt:71`（`maxOutputBytes in 1_024L..64 MiB`）。
- **证据链**：`terminal()` 先把输出归档写入客户端 PFD、关闭 PFD，然后构造 `ProotJobRecord` 时 init 的 require 对 `stdoutBytes>1 MiB` 抛 IAE → 被 `runJob` 外层 catch（:404-415）吞掉 → 走 `terminalFailed`：记录变 FAILED、无 `outputManifestSha256`、failure.txt 写 "lifecycle failure: stdoutBytes out of bounds"。归档其实已成功交付，但记录上没有可对账的 manifest hash → 客户端永远无法 reconcile。**任何 stdout+stderr 合计捕获 >1 MiB 的成功 Job 一律假失败。**现有测试没抓到：`ProotJobRunnerDeviceTest.specFor` 固定 `maxOutputBytes = 1_048_576L` 且所有用例输出极小（断言只到 `stdoutBytes >= 12`）。
- **建议**：`stdoutBytes/stderrBytes` 上限改为独立的输出上限常量（= `ProotJobSpec` 64 MiB 上限），不要把"journal 元数据字段预算"复用为"捕获字节数上限"；补一条设备测试（`maxOutputBytes=2 MiB`、`yes | head -c 1600000`，断言 SUCCEEDED + `stdoutBytes > 1 MiB` + manifest 可验证）。改动 <10 行。

**U5. PENDING 状态的 Job 无法被取消（取消传播缺口）**
- **位置**：`runtime/proot-app/.../ProotJobRunner.kt:605-616`（`cancel()`）与 `:186`/`:217`（`cancelRequested` 是 `runJob` 的**局部** AtomicBoolean）。
- **证据**：`cancel()` 只对 `liveJobs[jobId]`（进程启动后才注册，:361）置位并 kill；PENDING 阶段 `liveJobs` 里没有该 Job，`cancel()` 什么都不做（注释 "PENDING job is cancelled via the flag the job thread checks" 是错的——那个 flag 没有任何外部写入路径）。PENDING 窗口包含整包解压+逐文件重 hash（≤128 MiB/4096 文件，秒级到分钟级），期间通知"停止"按钮（`ProotJobStopReceiver`）与 binder `cancel` 都被静默忽略。
- **建议**：把 cancel 意图持久化到 per-job 存储（如 `proc.txt` 旁加 `cancel.req`，或 journal 记录字段），`runJob` 各 phase 检查点读取；补"提交后立即 cancel → 终态 CANCELLED"测试。

**U6. 1 GiB workspace 文件注册 artifact 时 OOM**
- **位置**：`core/storage/src/main/kotlin/com/helix/core/storage/repository/ConversationRepositories.kt:744`。
- **证据**：`require(FileContentStore.sha256Hex(file.readBytes()) == sha256)`。workspace 默认配额 `DEFAULT_MAX_WORKSPACE_BYTES = 1L shl 30`（WorkspaceQuota.kt:103），一个 1 GiB 合法文件注册 artifact 时 `readBytes()` 要在 JVM heap 分配 1 GiB ByteArray（Android 设备 heap 通常 256~512 MiB）→ OOM。同仓库已有正确做法：`AtomicFileWriter.sha256Hex(Path)` 是 1 MiB direct buffer 流式哈希（AtomicFileWriter.kt:187-202），`WorkspaceArtifactStore.streamCopyAndHash` 也是流式的，唯独这条持久化路径回退了。
- **建议**：改用 `AtomicFileWriter.sha256Hex(file.toPath())`（或把流式哈希器提为共享 helper）。3 行改动 + 1 个测试。

**U7. Skill 导入/激活上限 dead zone**
- **位置**：`extensions/skills/.../SkillLoader.kt:308`（`MAX_SKILL_BYTES=1 MiB`）vs `SkillTools.kt:272`（`MAX_INSTRUCTION_BYTES=512 KiB`）。
- **证据**：512 KiB–1 MiB 的 SKILL.md 能通过导入全部校验，但 `skills.read` 永远失败 "exceed activation limit"，用户无法感知该 skill 永远不可用。
- **建议**：激活上限前移到导入阶段（或统一常量）。

**U8. bounded() 防御分支恰好超 schema 1 字符**
- **位置**：`tools/browser/.../BrowserTools.kt:206-209` 与 `tools/android/.../AndroidSystemTools.kt:105-108`（两份拷贝）：`t.take(MAX) + "…"` 在 >512 时返回 **513** 字符，而输出 schema `reason` maxLength=512 → 防御分支触发时输出违反自己的 schema，dispatch 落 `INVALID_OUTPUT` 而非稳定错误文案。android 侧 Completed 路径还有裸 `put("reason", out.reason)`（schema 128）不过 bounded。
- **建议**：`take(MAX-1)+"…"`；Completed 路径统一过 bounded。

### 3.2 core 并发与正确性（M 组）

**C1. A2aTaskRepository 更新无 CAS 守卫 + markDirectCompleted 可静默清空 taskId**
- **位置**：`core/storage/.../A2aTaskRepository.kt:159-191`；SQL 在 `core/storage/.../dao/A2aDaos.kt:97-113`。
- **证据**：`updateRemoteState` 的 WHERE 只有 `toolCallId = :toolCallId`，无旧 state/旧 sequence 条件；仓库层的 `sequence >= current.lastEventSequence`、`updatedAt >= current.updatedAtEpochMillis` 对照的是**调用方传入的陈旧 in-memory 实体**，不是 DB 当前行。两个并发 reconcile 路径（Binder 丢失后的 job 恢复 与 仍在飞的流式回调）读同一行各自更新 → 后写者覆盖先写者，sequence 相对 DB 实际值可回退。`check(affected == 1)` 只防"行不存在"，不防 lost update。附带：`markDirectCompleted`（:144-157）无条件把 `taskId` 写回 null——若任务已被 `markAccepted` 绑定过 taskId，这里直接抹掉远端任务身份，而 `markAccepted` 明明用 "Task id cannot change during reconciliation" 守卫同一字段。
- **建议**：UPDATE 加 `AND state = :oldState AND lastEventSequence = :oldSequence`（CAS），affected==1 才是真正的并发守卫；`markDirectCompleted` 加 `current.taskId == null` require。A2A 任务跨进程重启 reconcile 是 HXA-079 核心路径。

**C2. UserScopeCodec 对 tag "a"/"r" 缺字段数守卫 → 未捕获的 IndexOutOfBoundsException**
- **位置**：`core/policy/.../UserScopeCodec.kt:122-139`（`decodeAutomation`、`decodeRoot`）。
- **证据**：`decode()` 的 KDoc 承诺 "any malformed … input returns null"；`safeDecode` 只 `catch (e: IllegalArgumentException)`。tag "w"/"s"/"b"/"f" 都走 `exactly(fields, n)` 守卫，唯独 `decodeAutomation` 直接 `fields[2]`/`fields[3]`、`decodeRoot` 直接 `fields[0..2]`——输入如 `hsr1<SOH>a<SOH>x` 抛 `IndexOutOfBoundsException`（不是 IAE）直接上抛。影响：`HighSensitivityRuleRepository.all()`（EgressRuleRepositories.kt:57）对每行做 `toStoredRule()`，一行字段截断的 "a"/"r" 脏数据让整个规则列表加载以不受控异常失败（而非 KDoc 说的 `error("corrupt egress rule")` 受控失败）。安全上仍 fail-closed（不会误匹配），但契约被破坏且无测试覆盖（`UserScopeCodecTest.aWrongFieldCountDecodesToNull` 只测了 tag "w"）。
- **建议**：两个函数各加 `exactly(fields, 4)`/`exactly(fields, 3)`；测试对 6 个 tag 各补一条"字段不足"用例。

**C3. SHA-256 校验规则在 7 处实现且互不一致**
- **位置/证据**：只查 `length == 64` 不查字符集：`ArtifactRepository.register`（ConversationRepositories.kt:741，文案还写 "must be a hex string"）、`RuntimeInstallRepository.register`（ConfigRepositories.kt:147）、`McpCapabilityRepository.register`（:333）、`SkillRepository.register`（:443）、`SkillSnapshotRepository.record`（:480）；查小写 hex：`McpCapabilitySpec`（:395）、`A2aTaskRepository.requireSha256`（A2aTaskRepository.kt:193-200）、`MessageAttachmentRepository.isSha256`（ConversationRepositories.kt:200）。
- **后果**：同一个 64 字符大写 hex 字符串能进一条持久化路径、进不了另一条；approval binding / artifact 哈希绑定等安全相关字段依赖这些校验。
- **建议**：core:model 已有 `Hex`/`Sha256` 类型，抽共享 `requireSha256(value, label)`（小写 hex 64）供全部仓库使用。

**C4. HelixStorage.deleteSessionPermanently 的 content 删除 check-then-delete 竞态**
- **位置**：`core/storage/.../HelixStorage.kt:135-143`。
- **证据**：删除事务提交后才逐个 `countByContentRef(encoded)`，非 0 才 `contentStore.delete(...)`。两个会话并发时（内容寻址 → 相同正文 = 相同 hash），会话 A 的 count 与 delete 之间会话 B 插入引用同一 hash 的新行 → A 把 B 仍在引用的正文删掉，B 后续 `read()` fail-closed 报 corrupt（数据丢失而非泄漏）。窗口很窄，但这是隐私删除路径上唯一有数据丢失可能的点。
- **建议**：把 count+delete 放进同一个 Room 事务（contentStore.delete 是文件操作可留事务外，判定必须在事务内）；或给 content 表加引用计数列做原子递减。

**C5. writeAtomic 前置 hash（乐观并发）是 check-then-act**
- **位置**：`core/workspace/.../AtomicFileWriter.kt:54-74`。
- **证据**：`writeAtomic(target, bytes, expectedPreviousSha256)` 先哈希 target，再 temp 写 + `ATOMIC_MOVE + REPLACE_EXISTING`。检查与替换之间另一会话（配额 KDoc 明确承认跨会话并发存在）改写了目标 → 被静默覆盖，前置 hash 形同虚设。
- **建议**：要么在 KDoc/ADR 里明确"前置 hash 只在每会话串行写保证下成立"（成本最低），要么下沉守卫（inode+size+mtime 三元组条件替换；NIO 无原生支持，退路是文档化 + 单会话锁）。

**C6. 缺失文件在 readAll 与 readWindow 上的契约不一致**
- **位置**：`core/workspace/.../WorkspaceArtifactStore.kt:239` vs `259-261`。
- **证据**：`readAll` 对"不存在/非常规文件"**静默返回 ByteArray(0)**（KDoc 未声明），`readWindow` 对同一条件抛 `FileNotFoundException`。用 `readAll` 的调用方无法区分"空文件"和"文件不存在"。
- **建议**：与 `readWindow` 对齐（抛 FileNotFoundException），或 KDoc 显式声明并给调用方加断言。

**C7. Proot submit check-then-act 非原子**
- **位置**：`runtime/proot-app/.../ProotJobRunner.kt:129-166`（`entries` 查重 → `put`）。
- **证据**：两个并发 binder 线程同 jobId/executionId 可同时通过查重、各自调度一次 `runJob`（单线程执行器会把已终态记录回退为 PENDING 再跑第二遍，违反 exactly-once 语义）。当前主 app 串行调用所以概率低，但服务端应自洽。
- **建议**：submit 快速路径加进程内锁（顺带缩小 binder 线程的并发 store 访问面）。

**C8. ToolDispatcher 中断/线程池不对称**
- **位置**：`tools/framework/.../ToolDispatcher.kt:947`（`EXECUTOR_SERVICE = Executors.newCachedThreadPool` 无界）、`:705`（`InterruptedException` 路径直接 rethrow 不调 `future.cancel(true)`，与超时路径不对称——dispatch 线程被中断时已启动的执行被无声 abandon，可能仍有副作用）。
- **证据**：生产路径全部经 ToolScheduler（`scheduleBatch`，maxConcurrency≤4，已验证 app 无直接 dispatch），瞬时并发有界；但每个超时后被 abandon 的 stuck 执行者永久留一个活 daemon 线程（注释自认 "abandoned"），与 S3 叠加成长期线程增长。
- **建议**：固定池 + 上限告警（或对 abandoned 线程计数）；中断路径补 best-effort `future.cancel(true)`。另：`ToolSchemaValidator` 与 `ToolSchema.check` 无递归深度上限，深度嵌套的模型参数或 MCP 深 schema 可致 StackOverflowError（dispatcher `catch(Throwable)` 结算为 TOOL_FAILED，fail-closed 但错误码退化）——两处各加 ~64 层深度计数并映射稳定拒绝。
- **总体评价**：978 行由 ~15 个聚焦阶段方法组成，每段小且单测充分（44+ 命名场景覆盖失败/取消/重试/边界/审计），策略常量集中 companion，复杂度**可接受**。若要拆分，优先抽"输出绑定+截断（surrogate 处理）"和"同 turn 拒绝 LRU"为独立可测单元。

**C9. submit 快速路径异常时服务端泄漏两个 PFD**
- **位置**：`runtime/proot-ipc/.../ProotRuntimeServiceBinder.kt:191-223`（`jobSubmit`：`readPfds` 先于 `handler.submit`，PFD 所有权已转入 handler）+ `ProotJobRunner.kt:129-166`（`submit` 只在 Duplicate/JOURNAL_FULL 两个分支关闭 PFD）。
- **证据**：`store.entries()` / `pruneAndBudgetAvailable`（含 `deleteRecursively`）/ `store.put`（磁盘满/IO 错）抛异常时，异常冒到 `handleJobTransaction` 的 catch（:172-184）→ 回 `REPLY_JOB_REJECTED/INVALID_SPEC`，但 companion 进程里已 dup 的两个 fd 无人关闭。低频但确定性的 fd 泄漏，磁盘满是真实场景。
- **建议**：`jobSubmit` 对 `handler.submit` 包 try/catch，异常时关闭 input/output 再回 REJECTED；或让 `submit` 的 PFD 所有权"所有出口必关"。

**C10. 重活跑在 binder 线程（ANR / binder 池压力）**
- **位置**：`ProotJobRunner.cancel()` → `killProcessGroup`（:663-685：`Runtime.exec("kill")` waitFor 2s + 最多 2 轮全量 /proc 扫描 + 2×100ms sleep，最坏 ~3-4s）；`ProotJobStore.reconcile`（:97-108：`deleteRecursively` 最多 4096 文件/128 MiB payload）；`submit`（:150 `pruneAndBudgetAvailable` → `prune` 可 `deleteRecursively` 多个 job 目录）。三者都从 `ProotRuntimeServiceBinder.onTransact` 的 binder 线程直达。
- **影响**：单次有界，但接近 10s binder 超时预算，companion 的 16 个 binder 线程可被同类调用占满；客户端 `awaitTerminal` 每 500ms query 叠加 cancel/reconcile 时放大。
- **建议**：kill/删除/清理挪到 job executor 或专用 IO executor，binder 事务只做入队+快速应答。

**C11. Job payload 无磁盘预算（journal 只限元数据）**
- **位置**：`runtime/proot-app/.../ProotJobStore.kt:38-41`（只有 128 条/1 MiB 的**记录**预算）；payload（input.zip ≤128 MiB、workspace ≤128 MiB、`_stdout.txt`/`_stderr.txt` ≤64 MiB×2）删除时机只有两条：客户端 reconcile（:97-108）或未对账满 30 天（:144-148）。
- **影响**：主 app 崩溃/被卸载/逻辑遗漏 reconcile 时，128 个终态 Job 可囤积数十 GiB（30 天窗口内），companion 无自我保护。
- **建议**：加 payload 总量上限（如 2-4 GiB，超出按 terminalAt 最旧优先回收 payload、保留 record+failure.txt 证据）；FAILED/INPUT_INVALID 的 payload 更短 TTL。

**C12. 浏览器快照 payment 类字段 value 可进模型 Context**
- **位置**：`tools/browser/.../BrowserTools.kt:159`（`value` 字段只屏蔽 password）。
- **证据**：已填值的 payment 类字段（type=text、name=card_number）按 schema 可进模型 Context；`SensitiveFieldClassifier` 只门控输入不门控 snapshot 输出。
- **建议**：snapshot 侧对 classifier 判 PAYMENT/OTP 的字段强制 `value=""`。

**C13. calendar.commit 写入"第一个可写日历"非确定**
- **位置**：`tools/android/.../CalendarBridgeImpl.kt:191-208`（查询 Calendars **无 sort order**，取第一个 `ACCESS_LEVEL >= CONTRIBUTOR`）。
- **证据**：多日历账号（含对他人可见的共享/同步日历）下，L2 审批只说"写一个日历"，事件实际落哪不稳定；输出只有 eventId，无日历回显。
- **建议**：确定性选择（本地/主日历优先）+ 审批卡/输出回显目标日历名。

**C14. ui.wait 双实现 + AndroidPackageName 全小写**
- `tools/automation/.../AutomationTools.kt:98-115`（内联 while+Thread.sleep）与 `AutomationActions.kt:427-469`（`AutomationWaiter`）是同一轮询逻辑两份；工具走内联版，`AutomationWaiter` 在工具路径上是死代码（仅测试引用），内联版 cancel 只能等下一轮 poll 才可见。建议工具改调 `AutomationWaiter`，sleeper 分片查 cancel。
- `tools/automation/.../AndroidPackageName.kt:5` 正则 `[a-z][a-z0-9_]*(\.[a-z]...){0,50}` 只接受全小写：真实含大写段的包名（如 com.NetEase.*）无法进 allowlist——`replaceAllowlist` 直接 `require` 抛异常、`start` 返回不透明 INVALID_PACKAGE、`packages()` 静默过滤已存名。建议放开到 `[A-Za-z0-9_]`。

### 3.3 性能问题

**P1. 每个流式 token 触发一次 SharedPreferences 写（+ 全量 screen 收集放大）**
- **位置**：`app/.../AppContainer.kt:696-702`；`app/.../diagnostics/ProcessDiagnostics.kt:60-80`（checkpointTurn 每次 4×putString+putLong 的 `preferences.edit`）。
- **证据**：`ChatService.applyEvent`（ChatService.kt:1762-1770）每收到一个 text delta 就 `publishTurn(TurnUi(..., acc.text, ...))` → `screen` StateFlow 每 token 一次 emission；容器里 `appScope.launch { it.screen.collect { ... dataSyncController.onTurnState(...); processEvidenceStore.checkpointTurn(...) } }` 对**整个 screen**（含 streamingText）做 collect，流式期间每 token 一次 SP 编辑 + 一次 controller 调用。
- **建议**：为 dataSync/evidence 单独暴露低频信号（`screen.map{it.activeTurn?.state}.distinctUntilChanged()` 后 collect），checkpoint 按状态变化而非 token 触发。

**P2. FilesScreen 单 composable 巨型化 + 列表非懒加载 + 组合期 IO**
- **位置**：`app/.../ui/FilesScreen.kt`（1871 行）。
- **证据**：一个 composable 内 ~40 个 `remember { mutableStateOf }`（152-228）+ 一组 local 非 composable handler（str/runImportSingle/doRename/startBatch…）+ 1070 行的 local @Composable `TransferResultPanel`（580-1645 区内）——每次重组都重建局部函数；列表视图是 `Column + verticalScroll + entries.forEach`（838-851）**非懒加载**，大目录全量组合；网格 `items(entries)` 无 key（859）；`remember { mutableStateOf(fileManager.sources()) }`（152）在 composition 期调用 `liveSources()` → 对每个已存 grant 做实时 ContentResolver 复核（SafTreeScopeService.kt:27-32 注释自述 "re-verification is on EVERY call"）——多 grant 时进屏幕即主线程 binder IO（同文件 picker 回调却走 Dispatchers.IO，不一致）；预览图 `BitmapFactory.decodeByteArray` 全分辨率无降采样（387）；文件级共享 `SimpleDateFormat`（1813）非线程安全（当前只在 UI 线程调用，脆弱点）；`formatSize` 英文单位 + `Locale.US` 时间（1813-1827）未本地化。
- **建议**：拆 FilesListPane/FilesToolbar/ImportDialog/ExportDialog/SafPanel 子组件；列表换 LazyColumn+key；sources() 首载移入 `LaunchedEffect(IO)`；预览加降采样。

**P3. ChatScreen ConversationIntents 每帧重建 → ConversationSection 永远重组**
- **位置**：`app/.../ui/ChatScreen.kt:110-127`。
- **证据**：`intents = ConversationIntents(13 个 lambda...)` 在 ChatScreen 体内内联构造；data class 的 equals 比较 lambda 引用，screen 每 token 变化 → 每次重组参数都不等 → 整个 ConversationSection（含 ModeControlSection、header、composer 区）无法跳过重组。
- **建议**：`remember(chatService, providerService) { ConversationIntents(...) }`，或直接把 service 传入子组件取方法引用。

**P4. ProotCallerVerifier 每笔事务重算自身证书 SHA-256**
- **位置**：`runtime/proot-app/.../ProotCallerVerifier.kt:30`（`expectedCertSha256s = selfCertSha256s(context)` 是默认参数——每笔事务在 binder 线程上重算自身包 getPackageInfo + SHA-256）。
- **建议**：进程内缓存一次即可（KDoc 自己要求 verifier "fast"）。

**P5. BoundedCapture 2× 内存峰值**
- **位置**：`runtime/proot-app/.../ProotJobRunner.kt:861-898`：64 MiB 上限下 buffer + `finish()` 拷贝 + `_stdout.txt` 落盘，峰值约 2× 捕获量（~128 MiB）驻留 companion 内存。可接受，建议注释说明或改流式落盘。

**P6. JsExecutionService join 超时后执行线程继续跑**
- **位置**：`runtime/quickjs/.../JsExecutionService.kt:391-404`：超时后返回 TIMEOUT 但执行线程（非 daemon）继续跑，进程存活到 unbind/回收；客户端 `JsExecutionClient` give-up 后 worker 线程仍阻塞在 transact。均有界，建议注释明确或顺手 interrupt。

**P7. Accessibility 无门控取树**（见 S9，性能与安全双重属性）。

### 3.4 架构与可维护性

**A1. ChatService.kt（3041 行）职责过重**
- **位置**：`app/src/main/kotlin/com/helix/app/chat/ChatService.kt`。
- **证据**：一个类同时拥有 ① 会话管理（open/close/archive/create，260-550 区段）② share 草稿 ③ 附件 staging+图像归一化（446-913）④ 发送/egress 门禁与 pending disclosure（925-1300）⑤ turn 执行+工具轮（1545-1800）⑥ 工具调用 prepare/settle+审批卡片 UI 映射（2060-2770）⑦ 屏幕状态构建 refreshScreen/messagesFor/toolTimelineFor（2773-2980）⑧ 错误码→资源 id 映射（160-210）。KDoc 自述"一个类拥有所有事实"，但 ⑥⑦⑧ 是纯展示/映射职责，③ 是独立流水线。
- **建议**：先抽三个低风险纯 JVM 单元：`ChatErrorLabels`（⑧，立即可 JVM 单测）→ `ChatScreenStateBuilder`（⑦，需 storage 只读 seam）→ `ToolCallPreparer/Settler`（⑥，含 publishToolRow/卡片状态）；staging ③ 次之。每抽一个都能把对应行为从"只能设备测"拉回 JVM 测。

**A2. ChatService 编排层只能设备测（结构性）**
- **证据**：`app/src/test` 29 个 JVM 测试全是纯 mapper/pipeline；ChatService 的 send/confirm/retry/staging/pending-disclosure/terminalize 编排只在 androidTest（靠真实 Room + ScriptedSseWire）。根因：三个核心 seam 都是**具体类**（HelixStorage internal constructor、ProviderService、ToolPipeline——均无接口），JVM 上无法构造 fake。后果：最有价值的安全不变量（"未出现在披露对话框的附件永不离机"、target 漂移作废确认、staged 集合漂移）回归只能跑模拟器。
- **建议**：为 HelixStorage 的 ChatService 子面（sessions/turns/messages/toolCalls/artifacts/messageAttachments）抽接口或可注入 fake（Room 实现在 Android seam 后）；至少把 providerService 的 5 个被调方法提成接口。

**A3. EgressRuleSection 是 UI 直连 storage 仓库的唯一架构违规**
- **位置**：`app/.../egress/EgressRuleSection.kt:59`（`rules: HighSensitivityRuleRepository`）、`:72/107/124`（`withContext(Dispatchers.IO){ rules.all()/save()/revoke() }`）；注入点 `MainActivity.kt:223`、`SettingsScreen.kt:42/159`。
- **说明**：`HighSensitivityRuleRepository` 是 core:storage 的 Room 仓库类（`core/storage/.../EgressRuleRepositories.kt:37`）。这是 app/ 里唯一一处 UI 绕过 service 直连存储层，破坏其余屏幕"UI→Service→Storage"的一致模式，也让该段逻辑无法按项目惯例注入 fake 测试。
- **建议**：新增 EgressRuleService（或并入现有 service 层）暴露 load/save/revoke + 封闭错误码，UI 只接门面。

**A4. AppContainer：无注入缝隙、顺序耦合、三处手工断环**
- **位置**：`app/.../AppContainer.kt:226-738`。
- **证据**：`DefaultAppContainer(context)` 唯一构造参数是 Context，全部子图内联构造——无法在测试中部分替换生产接线；`visionImageSource`（272-274）依赖后面声明的 `workspaceStore`（354），被迫 `by lazy`（注释自认 "declared later"）；三处断环手法并存：`ApprovalCardSinkHolder`（573-575，broker↔chatService）、`mcpService.aalso { toolPipeline.installMcpFactsProvider }`（637-639）、`a2aService...installA2aFactsProvider`（659-661）——图密度已超出"一遍拓扑构造"，靠约定维持；构造期主线程磁盘 IO：`Files.createDirectories`（333-337）、`workspaceStore.ensureLayout`（355）、`AllFilesModule.init(context)`（464）都在容器构造（通常首帧主线程）执行；接口默认 `connectorService get() = error(...)`（155-156）用抛异常隐藏缺省接线，首用才炸。
- **建议**：拆 2-3 个阶段化 builder（storage 层 / tool-pipeline 层 / 服务层），每阶段暴露接口；断环改两阶段装配（先建对象再 wire），替代 Holder + after-construction install；构造期目录创建移入首用或启动后台任务。

**A5. 根 build.gradle.kts 单文件承载 34 模块，模块清单三份平行真相源**
- **位置**：`build.gradle.kts:204-588`（`subprojects { when(path) {...} }`；`androidLibraries` L86-104、`jvmLibraries` L136-153、`projectDependencies` L155-202）；`settings.gradle.kts:26-60`（include 列表）；`scripts/check-lockfiles.sh:34,39-44`（硬编码 `35` + 第三份 projects 数组）。
- **证据**：除 `:app`、`:runtime:proot-app`、`:runtime:cli-app` 外，其余 31 个模块没有自己的 build.gradle.kts——namespace、逐模块依赖、okhttp substitution、KSP arg、systemProperty 全在根文件。新增一个模块要同步改 4 处（settings include、根文件 2-3 个 map、check-lockfiles 数组+计数 35），漂移风险高且无编译期保护（count 不匹配才报错）。
- **建议**：抽 included build `build-logic`（convention plugins：`helix.android-library` / `helix.kotlin-jvm` 承载 compileSdk/minSdk/Java17/lint/junit 公共块），每模块 ≤20 行薄 build.gradle.kts；check-lockfiles 从 `subprojects` 动态派生（删硬编码 35 与 projects 数组）。分步：先去硬编码 35 → 迁 3 个 app → 存量。

**A6. 三个 Provider adapter 的 SSE 读取器近字节级三份拷贝**
- **位置**：`AnthropicSseReader`（262 行）/`ChatSseReader`（242 行）/`ResponsesSseParser`（258 行）。
- **证据**：UTF-8 逐字节解码、换行处理、`event:`/`data:` 组装、失败语义、1 MiB/8 MiB 上限完全同构，唯一协议差异是"是否跟踪 `event:` 字段、payload 是 String 还是 typed event"。HXA-023/024 完成记录明确引 roadmap"独立实现/零共享代码"作为协议岛纪律，且 `WireModelProvider`+`Wire`+`mapHttpStatus` 已证明 provider:api 就是共享 transport 层——把协议无关的 SSE 机制放进 provider:api（三个模块本就都依赖它）**不违反**"失败不切协议"纪律（该纪律由 `WireModelProvider` 已保证）。另有 `ImagePayload`/`ImageResolver` 三个模块各一份等价边界（KDoc 自认 "independent boundary"）、三个解码器各自拷贝 `ProtocolViolation`、terminal guard、`requireString`、`MAX_TOOL_CALLS`。协议相关的 JSON 映射部分（各 370-470 行）差异真实，不建议合并。
- **建议**：作为协议验收后的一次性清理，抽 `SseEventReader`（typed/data-only 两个策略），消除 ~400-500 行，上限常量单点。

**A7. 同语义上限散落/不同值（收敛清单）**
- `MAX_TOOL_CALLS=32`：3 个 provider 解码器 + `core/agent/TurnEvent.kt:203`（`MAX_CALLS=32`，private）**共 4 份拷贝**，一致性仅靠注释（"same bound as core:agent"）——建议提升到 core:model 共享。
- SSE `MAX_LINE_LENGTH`/`MAX_EVENT_DATA_LENGTH` 三份拷贝。
- `bounded(512)` helper：browser/android 各一份。
- ui.wait 的 10s/50ms/1s 上限三处重复（`AutomationTools.kt:249-250` schema、`:99-100` 默认值、`AutomationActions.kt:466-468` companion）。
- automation 查询字段上限 2000/512 vs 快照引擎 `MAX_FIELD_CHARACTERS=256`（>256 的 CONTAINS 查询永不命中）。
- **建议**：把"边界常量 + bounded/arg helper + wait 逻辑"收敛进 tools/framework（core 侧常量进 core:model），一次收敛同时修掉 U8 的 513 字符越界与 S7 的溢出写法。

**A8. spikes/ 目录生命周期**
- 生产代码对 `com.helix.spikes` **零引用**（grep 全仓确认）。
- `spikes/a2a-minimal` + `spikes/a2a-sdk`：HXA-077 使命已完成，生产 `:extensions:a2a` 已交付且是手写 OkHttp 客户端（lockfile 确认 `org.a2aproject` 零命中，生产**不**依赖 a2a-java SDK）；spike 代码已被替代，`a2a-sdk` 还让官方 A2A SDK（3 artifact + 传递依赖）继续留在构建与 CI `test` 任务里。
- `spikes/bounded-orchestration`：HXA-105 已完成；ADR-0009 扫描时为 proposed，扫描后 AGENTS.md 已引用为 **accepted**——若维持 accepted，该 spike 是未来实现起点但产品集成仍需通过其 enablement gates；在 settings.gradle.kts 加一行注释标记生命周期状态。
- **建议**：M10 release evidence 完成后归档/删除 a2a 两个 spike（证据已在 HXA-077 完成记录）；bounded-orchestration 按 ADR-0009 当前状态处置。

### 3.5 跨域共性模式（归纳）

1. **边界常量多份拷贝**：同一语义的上限散落在 2-4 处，一致性靠注释（A7、U7、U8）。
2. **check-then-act 并发**：content 删除（C4）、前置 hash（C5）、submit 查重（C7）、A2A 更新（C1）——窄窗口但都在安全/隐私路径上。
3. **错误码封闭集不完整**：raw `e.message` 上屏（S5）、异常统一映射 `INVALID_SPEC`（R-L1）、`CONNECTOR_JSON_DEPTH` 不可达（E-L2）、两种错误文案风格并存（T-L11）。
4. **安全关键路径的 JVM 测试缺口**：HttpFetch 传输层（T-M1）、ConversationRepositories/PlanGoalRepositories（core 最大缺口）、取消前置分支（T-L17）。
5. **取消/超时后资源不回收**：PENDING 不可取消（U5）、abandon 线程（C8/S3）、PFD 泄漏（C9）、join 超时线程（P6）。
6. **组合期 IO / 每帧分配**：FilesScreen（P2）、ConversationIntents（P3）、每 token SP 写（P1）。

---

## 4. 分域 LOW 级发现（逐条）

### 4.1 core/（8 项）

- **L1** 十六进制编码 `digest.joinToString("") { "%02x".format(it) }` 重复 5 处且逐字节 `String.format`：`AtomicFileWriter.kt:201/206`、`WorkspaceArtifactStore.kt:212/711`、`FileContentStore.kt:108`。热路径（大文件哈希）浪费明显；core:model 已有 `Hex.encode`。抽共享 helper 同时解决重复与性能。
- **L2** `UserScopeCodec.kt:27-28` 的分隔符是源码里的**不可见控制字符**（`FIELD_SEP`/`LIST_SEP` 为字面量 U+0001/U+0002，已用 od 确认）。KDoc 用 U+0001/U+0002 描述，代码里却是裸控制字符——任何格式化器/编辑器/拷贝粘贴都可能无声破坏。改为 `"\u0001"`/`"\u0002"` 转义。
- **L3** 两套 ADR-0001 严格 JSON 解析器 + 三套 escape 实现的漂移风险：`core/model/internal/Json.kt`（parser+escape）、`core/storage/internal/MiniJson.kt`（parser）、`CriteriaCodec.escape`（第三份 escape）。按 ADR-0001 是刻意的模块自治，但三处手工实现同一语法子集：目前 `CriteriaCodec` 要求**固定键序**而 model 的 `requireObject` 接受任意键序——有意还是漂移无法从代码判断。建议 androidTest 加共享语料（合法/非法各 ~20 条）的双 parser parity 测试。
- **L4** `TurnReducer.kt:363/383/419` 的 `accounts.last()` 隐含"当前调用账目必是列表末尾"：当前构造下成立（调用按序追加、串行处理），但三个处理器都依赖未言明的顺序假设；未来任何"补账"机制会悄悄打偏。建议显式 `requireNotNull(state.callAccounts.firstOrNull { it.callId == event.callId })`。
- **L5** `InteractionReceiptRepository.open` 的 insert+supersede 非事务（`ConversationRepositories.kt:614-616`）：两步之间崩溃 → 新旧版本 receipt 同时 PENDING，旧版本在下次 open 前仍可被 `answer()` 接受。与 C4 同类的窄窗口持久化缺口；建议包进 `HelixStorage.withTransaction`。
- **L6** `SecretStore.put` 的 `renameTo` 回退是**非原子** copy（`SecretStore.kt:84-89`）：同目录 rename 在 Android 上基本必成功，回退路径近似死代码；真触发时并发 `get()` 会读到半写文件 → GCM 拒绝，报 "corrupt or master key reset"（fail-closed 但误报）。可接受，加一行注释说明即可。
- **L7** `moveToTrash` 的 ATOMIC_MOVE 无回退而 `moveFile` 有（`WorkspaceArtifactStore.kt:535` vs `493-502`）：同一文件系统下 moveFile 能降级非原子移动，moveToTrash 直接失败——行为不一致。统一成私有 helper。
- **L8** `NormalizedEndpoint` 接受 `[::]`（未指定 IPv6）为合法 host：`residence()` 归入 CUSTOM_REMOTE_UNKNOWN（偏严方向，fail-closed 安全侧），仅列边界观察项；建议 parse 里显式拒绝 0/128 与 IPv6 未指定地址。

**core 测试质量**：agent 模块测试是范本级（TurnReducer 按 lifecycle/tool-loop/budget/cancellation/interruption/failure 分文件；ContextBuilder 测确定性/fail-closed 超限/信任标记/hash 一致性；RecoveryCoordinator 有跨 process death 一致性测试）；workspace 错误路径覆盖扎实（前置 hash 不匹配、配额、region 逃逸、symlink 双类拒绝、流式 cap、trash 往返）；storage 迁移测试走真实 v1→v7 全链路 + schema 导出 parity + FK 运行时启用断言；全量扫描无"只断言不抛异常"的空测试，无测试间共享可变状态。
**core 最大测试缺口**：`ConversationRepositories.kt`（809 行，11 个仓库类）与 `PlanGoalRepositories.kt` 几乎零直接 JVM 单测——Session/Message/Turn/ModelCall/ToolCall/ToolResult/InteractionReceipt/Execution/Artifact/AuditEvent/Plan/Goal/GoalRun 仓库在 core/storage 的 test 与 androidTest 里都没有针对自身的测试（ApprovalRepository 仅由 ApprovalProofLifecycleTest 间接覆盖，SessionRepository 仅 SessionBindProviderTest 覆盖一个方法）。sequence 分配、receipt supersede、artifact 三重复核（存在/size/hash）这些核心逻辑没有任何单测钉住。另：UserScopeCodec 错字段数测试只覆盖 tag "w"（对应 C2）；A2A 仓库无并发/CAS 语义测试（对应 C1）。

### 4.2 app/ + feature/（8 项）

- **L1** `ChatService.dispatchFacts` 泄漏：`prepareToolCall`（ChatService.kt:2245）写入，仅 terminalize（:1789）清理；`dispatchToolCall`（:2317-2347）注释自认 "direct path has no turn-level finalizer"，只 remove 了 turnCancels，每个直接调用的 DispatchFacts（含 args JsonObject）进程级滞留。
- **L2** `collectAsState()` 与 `collectAsStateWithLifecycle()` 混用：`MainActivity.kt:274`（AuditScreenDestination）、`BrowserScreen.kt:62-63`——离屏仍收集，与其余屏幕不一致（轻微功耗/一致性）。
- **L3** `ChatScreen` NewSessionDialog 只捕获 IllegalArgumentException（`ChatScreen.kt:309`）；Room 等其他 RuntimeException 会从 dialog 的 `rememberCoroutineScope` 未捕获上抛 → 崩溃。
- **L4** `ProviderScreen` 删除 provider（含 Keystore 密钥）无确认对话框（160-168）。
- **L5** AppContainer 构造期主线程磁盘 IO（见 A4）：首帧多几 ms 目录创建；可移后台。
- **L6** FilesScreen testTag 用原始文件名（`files-entry-${entry.name}`，1716/1723/1758/1781）：含空格/特殊字符或与测试查找语义冲突；跨目录重名还会撞 tag。
- **L7** `turnGate` synchronized 块内含 `TurnCoordinator.start`（Room 写，`ChatService.kt:1576-1600`）——monitor 持锁跨 IO；注释已说明是刻意为之（register 先于 start），列为已知风险备案。
- **L8** `AppContainer.connectorService` 接口默认 `error(...)`（155-156）：缺省即运行时炸弹，建议改由构造期显式装配。

**app+feature 测试质量**：app/src/test 29 个 JVM 测试（纯 mapper/pipeline）+ 设备测试面广（42+13 个文件）。缺口即 A2（编排层只能设备测）与 U1（SAF revoke 路径无测试）。

### 4.3 runtime/（9 项）

- **L1** `ProotRuntimeServiceBinder.kt:172-184`：所有异常（含 store IO、OOM）统一映射 `INVALID_SPEC`——拒绝原因撒谎，排障困难。建议给 closed set 加一个 `INTERNAL_ERROR` refusal wire。
- **L2** `ProotJobRunner.kt:861-898` `BoundedCapture` 峰值 2× 捕获量驻留内存（见 P5）。
- **L3** `ProotCallerVerifier.kt:30` 每笔事务重算自身证书摘要（见 P4）。
- **L4** `JsExecutionService.kt:391-404` join 超时后执行线程继续跑（见 P6）。
- **L5** `quickjs/.../JsAbiAssembly.kt` `build()`：KDoc 声称"组装被破坏的 source 一律 parse 期 fail-closed"，但 source 以行续 `\` 结尾时可构造出**合法但结构不同**的程序（用户块吞掉 wrapper 控制段，`helixMain` 永不被调用，顶层返回 undefined → 编码为 `"null"` → **假 SUCCESS**）。不构成 ABI 逃逸（仍在 strict IIFE 作用域内），但属于语义破坏+假成功。建议组装前拒绝尾部续行符（或加哨兵块），并在 `JsAbiAttackTest` 加用例。
- **L6** `ProotRuntimeServiceBinder.kt:61-67`：协议版本只在 `TX_HANDSHAKE` 校验，四个 Job 事务只 enforceInterface（descriptor 后缀 `/1` 是静态的）。客户端总是先握手，风险低；建议 Job 事务也带版本检查（或 descriptor 嵌版本）作纵深。
- **L7** `ProotJobStore.kt:26-28` KDoc 声称"所有方法都跑在 runner 单 job 线程"——实际 `submit/query/cancel/reconcile` 全在 binder 线程调用，文档与事实不符（也是 C7/C9/C10 竞态的根因注脚）；`put()` rename 后未 fsync 父目录（持久性小瑕疵）；`OUTPUT_FILE` 常量为死代码。
- **L8** `quickjs/.../JsExecutionLimits.kt:65`：`MAX_MEMORY_BYTES` 上限 1 GiB 的 JS 堆对手机端偏激进（默认 64 MiB），建议压到 256 MiB。
- **L9** submit check-then-act 非原子（见 C7）。

**runtime 测试质量**：覆盖好的——exactly-once 重放、orphan sweep 的 pid 复用保护（`anOrphanedRecordFromADeadIncarnationIsSweptWithoutKillingInnocents`）、篡改归档 fail-closed、journal 预算满拒、通知停止→组 kill、deadline 超时、reconcile 墓碑、隔离四件套（app data/共享存储/网络/权限）、QuickJS 攻击套件（wrapper 注入、stringify 覆写、循环结果、±1 字节边界、深度 JSON、PFD 洪泛、崩溃注入、cancel race、冷绑定竞态）。缺口与问题一一对应：① 无 >1 MiB 输出 Job 用例（会直接抓 U4）；② 无 PENDING 阶段 cancel 用例（U5）；③ 无 symlink 逃逸用例（S2）；④ 无 submit 异常路径 PFD 泄漏用例（C9，需注入磁盘故障 seam）；⑤ 无并发 submit 竞态用例（C7）。QuickJS 侧对 L5 这类"合法但结构异变"的组装变体也未覆盖。

### 4.4 provider/ + tools/ + extensions/（17 项）

**extensions**
- **L1** `SkillTools` executor 只 catch `IllegalArgumentException`（`SkillTools.kt:117-129`）；校验后文件被删的 `IOException` 竞态会裸抛穿出 dispatcher（MCP/A2A 同类 executor 全捕获并映射稳定 `Failed`）。
- **L2** `CONNECTOR_JSON_DEPTH` 错误码不可达：`requireJsonDepth` 的 IAE 被 `json()` 的 `catch (IllegalArgumentException)` 吞成 `CONNECTOR_INVALID_JSON`（`ConnectorPackage.kt:227-263`）。
- **L3** canonicalJson 不归一 JSON 数字字面量（`1.0` vs `1.00`）→ 远程方可触发多余 schemaHash/cardHash checkpoint（fail-closed 方向，仅 churn）；且 canonicalJson 在 MCP/A2A 是**三份同型拷贝**（`McpMetadataHash.kt:41-56` 等）。
- **L4** bearer 校验不一致：MCP 字符白名单（`McpHandshake.kt:107-114`）vs A2A 仅 blank/CRLF/长度（`OkHttpA2aTaskClient.kt:353-358`）。
- **L5** MCP 运行时类路径仍带未使用的 Ktor CIO 引擎（`ktor-http-cio`+`ktor-network`）；ktor-server 路径排除已验证干净。
- **L6** `McpClients.sdk()` 是无网络许可的公开工厂（当前仅测试用，footgun），建议降 internal 或注释标注。
- **L7** `A2aSessionCheckpointTracker` 按 agent 单三元组存储：交替调用同 agent 两个 skill 时 `checkpointRequired` 恒真（保守 churn）；MCP 按 toolName 存储无此问题。
- **L8** `SkillRepository.scanSnapshots` `runCatching` 静默吞全部扫描失败，损坏快照无声从 `list()` 消失（fail-closed 方向对，可观测性缺失）。
- **L9** MCP 出站 tools/call 请求体无显式字节上限（server schema 可无 maxLength，Dispatcher 无 arg 上限），与 A2A 2 MiB 不对称（纵深防御缺口）。

**provider**
- **L10** `OkHttpWireClient` 的 8 MiB `maxBodyBytes` 对**流式** SSE 也生效：超长合法流（大量工具参数 chunk）会在 8 MiB 处中途中断为可重试 TRANSPORT 错误。建议流式与非流式分别设界（或文档化接受）。
- **L11** RootTools 错误 detail 用 SCREAMING_SNAKE 码（`ROOT_TOOL_UNKNOWN` 等）而其他工具用可读脱敏句子——两种风格都稳定，但词汇表不统一（`RootTools.kt:92-104`）。

**tools**
- **L12** 浏览器快照 `value` 字段只屏蔽 password（见 C12）。
- **L13** HttpFetch 细节：IPv6 Host 头不带方括号（`Host: ::1`，RFC 3986 违规，`HttpFetchBridgeImpl.kt:389-392`）；非 ASCII path 被 US_ASCII 编码损坏进 request line；首地址 connect 超时即放弃不再试其余已验证地址（:145-149）；ERROR/TIMEOUT 的 redirectCount 硬编码 0 而 REFUSED 记录 hops（审计不一致，:403-412）。
- **L14** `HelixNotificationStore` 进程内快照无条数上限（输出侧 cap 20 已做，内存随全设备通知增长）。
- **L15** `CalendarBridgeImpl.evictIfOverBound` 按 ConcurrentHashMap 任意序驱逐草稿，注释称 "not observable to the model"，但被驱逐 draftId 会让 commit 返回 draft-not-found——可观察。
- **L16** 注释漂移：`BrowserTools.kt` 文件头 "11 browser.* tools"，实际注册 12 个。
- **L17** 取消覆盖缺口：browser 12 个与 automation 9 个工具的 `cancel.isCancelled()` 前置分支无测试钉住（仅 http 工具有）；`AutomationServiceDeviceTest` 单 @Test 串十余个 assert helper 的巨型矩阵，无失败隔离。

### 4.5 构建系统与工程设施（5 项）

- **L1** `config/lint/lint.xml` 仅 4 条版本类 ignore（AndroidGradlePluginVersion 等），无自定义规则；`config/detekt/detekt.yml` 23 行（maxIssues=0 + 3 处定制）——覆盖保守但合理，记录为"覆盖窄"备忘。
- **L2** `THIRD_PARTY_NOTICES.md` 目前只覆盖 libsu + exifinterface，zipline/okhttp/room/mcp/a2a 等 shipped 依赖未入清单；文档自述 M12 前生成完整清单——保持跟踪即可。
- **L3** `.claude/` 顶层（CLAUDE.local.md、settings.local.json）未完全 ignore，`git status` 有 5 个 untracked 噪音文件（worktrees 已被单独 ignore）；建议 `.gitignore` 补 `.claude/`。
- **L4** `check-lockfiles.sh` 每次 CI 全量解析 35 个项目的所有 configuration（`gradlew :x:dependencies --write-locks`），是 CI 里除 lint 外的第二大时间源；模块数增长后考虑只对变更模块 + root 跑。
- **L5** 三个 application 模块（app/proot-app/cli-app）各自重复 android 公共块（compileSdk/minSdk/targetSdk/Java17/lint/junit 基线），与根文件对 16 个 library 的集中式 convention 是"同一约定的两种写法"——A5 的 convention plugin 一并解决。

**构建系统 MEDIUM 备忘**（详见 §2 关联项之外的独立项）：
- **M1** Configuration-cache：`:runtime:quickjs` 在配置期解析 debugRuntimeClasspath 的 AAR 文件（`build.gradle.kts:392-412` `afterEvaluate { ...artifactView{...}.files... }` → `helix.zipline.aar` systemProperty）——AAR 路径固化进 CC 条目，升级 zipline pin 或跨 checkout 命中 CC 时可能读到过期路径（测试静默拿错 AAR）。旁证：三个 spike 脚本已有 `--no-configuration-cache` 逃生舱。建议把解析挪到任务执行期（Test 任务 input 的延迟 file collection），去掉 afterEvaluate；顺带评估 3 个逃生舱的必要性。
- **M2** CI 单 job 串行 + 缺失门禁：唯一 job `verify`（timeout 60，macos-15）把 spotless+detekt+test+6 个 lint+4 个 assemble 全串行；无 release assemble（`isMinifyEnabled=false` 是文档化 M0 决策，但 `proguard-rules.pro` 是空文件，开 minify 前 CI 没有 R8 回归通道）；`check-mcp-android-spike.sh`/两个 a2a spike gate（CI 已装 SDK 36，本可跑）、`check-cli-runtime-lock.sh`（依赖 CI 刚构建的 APK）均未接入。建议拆 2-3 个并行 job；spike gate 接入或 `workflow_dispatch` 化；minify 开启前预置 unsigned release assemble。
- **M3** 脚本质量：无 shellcheck（全仓零命中）；`check-cli-runtime-lock.sh:21` 用 `shasum`（macOS-only；CI 是 macos-15 所以 CI 安全，Linux dev 机挂）——对比 `check-lockfiles.sh:29-33` 正确处理了 sha256sum/shasum 双栈，属遗漏；`check-mcp-android-spike.sh:41-43,60` 硬编码 pin（`kotlin-sdk-client:0.15.0`、`ktor-client-okhttp:3.5.2`、`okhttp:5.5.0`、`mcp-0.1.0-SNAPSHOT.jar`）与 libs.versions.toml 双写，升级 catalog 时脚本静默失配。建议 CI 加 5 分钟 shellcheck job；复用双栈模式；spike 脚本 pin 改从 libs.versions.toml 读取。
- **M4** 工具链版本对齐疑点（需一次人工确认，非 blocker）：`libs.versions.toml:3`（kotlin=2.3.21）/`:9`（ksp=2.3.11）/`:28`（detekt=1.23.8）。KSP 历史要求与编译器精确配套的发布线，`2.3.11` vs `2.3.21` 确认发布线即可；detekt CLI 1.23.8 自带 `kotlin-compiler-embeddable 2.0.21`（root lockfile 可查），落后项目 Kotlin 两个小版本，2.1+ 新语法若进入生产代码 detekt 解析可能失真；detekt 配置仅 23 行，升级成本低，建议排期评估。
- **M5** 设备测试 flake 治理靠文档，无构建层机制：已知 flake 记录规范做得好（HXA-055 `FilesScreenTest` ~1/12 全量轮次留档、HXA-059 双 flake 带 main 同签名复现证据、HXA-048 时序 flake 根因修复落 bug-fixes/），但"重跑必绿"是口头约定，`accept-*.sh` 无自动复跑/标记，CI 无设备矩阵。建议给设备脚本加"单类失败整类复跑一次并标记 FLAKE-RETRY"薄封装。

**构建系统正面发现**：`local.properties`/`.DS_Store`/`build/` 未入库且被 .gitignore 覆盖；wrapper 有 sha256 pin；CI 所有 action 全 SHA pin + `concurrency: cancel-in-progress`；`gradle/verification-metadata.xml` 在位；35 个 lockfile 与 34 个 include 一一对应；版本 catalog 使用彻底（59 处 `dependencies.add` 全走别名）；minSdk 29/targetSdk 36/compileSdk 36 全仓一致；20 个 shell 脚本全部 `set -euo pipefail`；`check-secrets.sh` 对 rg 缺失/异常 fail-closed；evals/m10 fixture 组织好（TSV + sha256 pin + run-metadata 模板 + `:testing` 模块 `FixedEvalCatalogTest` 双重校验）。

### 4.6 文档体系（7 项）

- **L1** `security/testing-and-release.md` §7.5 病句："在 accepted 前、RUNNING、terminal commit 前后分别 kill 主 App/Runtime"——"accepted 前"语义不明（疑为 ADR-0007 验收前后之误）。建议按 ADR-0007 生命周期状态改写。
- **L2** 38 份完成记录引用旧编号 "doc 02/doc 09/doc 10/doc 11"，而架构文档已去编号化（docs/README："文件名不再使用 01～12 的人工顺序编号"），无映射表。记录按约定是不可变快照可不改，建议在 completion-records/README 加一行 doc 编号→现文件名映射。
- **L3** `docs/development/m9-rooted-emulator-experiment.md` 全仓无入链（HXA-094/095 的 rooted 模拟器工程证据无人可发现）；建议从 status.md "Known limitations" Root 行链出。
- **L4** `verification-gaps-progress.md` "status.md 只读过期项" 一节自身已过期（4 项中 2 项已被 main 修订），且该文档未标注"结论已被 main-merged-verification 取代"。
- **L5** roadmap §2 里程碑表 M13 行被拆成独立单行表格（L45-46，排版断裂）；各里程碑完成度标注口径不一（仅 M1 有 "M1 已完成（2026-08-31）" 行内注）。建议 M13 行并入主表，统一"完成度以 status.md 为准"脚注。
- **L6** 同日多个 commit 基线散落：hxa-125-progress（main b480b25 + Connector 7710918）、connector-handoff（672dc5a）、main-merged-verification（基线 61bad35），均 2026-09-05，读者需自行推断先后；status.md 无"当前 main 基线 commit"字段。
- **L7** `HXA-088` 完成记录 L11 "ADR-0008 保持 proposed（待所有者决定）" 与文档 HIGH 组同源：记录是其日期（09-04）快照，合规，但无任何"后续已被接受"指引，易被当现状。

**文档体系 HIGH/MEDIUM 备忘**（完整条目见 §6 未决事项与改进建议）：AGENTS.md 仍把 ADR-0008 标为 proposed（ADR 文件已 accepted 2026-09-05）；ADR-0011 已实现进生产（HXA-042 contractHash 门禁）但状态仍 proposed/Deciders pending；`main-merged-verification.md` 残留"最终矩阵仍在执行"占位行；status.md "当前检查点"漏 M10/M13 且 M10 收口条件无里程碑级表述；status.md 内部 SAF scope 三处表述互相矛盾（L95/L122 vs L123）；`verification-gaps-progress.md` 为孤儿文档（全仓无入链），8 份 worktree 进展文档游离于文档中心；HXA-089 幽灵前置 + HXA/ADR 编号缺口无治理说明；ADR-0008 已 accepted 但 roadmap 无实现 HXA 且文件内 L59 小节标题仍写 "Status 仍为 proposed"；status.md "Current summary" 大表（单行 8,858 字符）与 Completed bullet 重复维护同一事实；`implementation-guide.md` 残留 ADR-0008 接受前措辞。

**文档体系正面发现**：507 条相对链接全部可解析；20 个 ADR 标题/编号/6 字段/7 章节全合规、superseded 链双向一致（0002→0010、0005→0012、0006→0013）；91/91 完成记录含"决策记录"字段且 check-docs 强制记录↔status 一致性；抽查 9 份完成记录（M3~M13）的验证命令中所有 gradle 模块、脚本、门禁均真实存在，失败也如实记录；CI 门禁与安全文档 §8/§9 描述一致。

---

## 5. 当前未决事项清单

汇总自 status.md（In progress / Next task / Current interfaces / Known limitations）、`verification-gaps-progress.md`"尚未执行"、`connector-handoff.md`"待完成项"、`hxa-125-progress.md`"剩余门禁"。本清单是 2026-09-06 快照，实施时以 status.md 为准。

**A. 进行中任务**
1. M13/HXA-125（in progress）：① 独立测试账号/受保护 MCP 服务的 bearer 握手、无效凭据、权限拒绝、厂商撤销与重连；② WorkBuddy 真实导出样本（现仅规范衍生 fixture）；③ Codex/Claude 完整插件（工具名+脚本依赖）具体样本；④ QwenWork 参考包的 CLI 与账号业务（`requires.bins=dws` 未运行，仅 Skill/endpoint 导入层通过）。

**B. Next task**
2. M9/HXA-094~095 rooted 物理设备验收：grant/revoke/loss、RootService crash、真实高阶读取门禁；无合格设备时保持未完成（不得用模拟器替代）。

**C. 真机/设备门禁（模拟器证据已闭环，物理证据缺失）**
3. HXA-086 遗留：真机 4 KiB/16 KiB 页 smoke、Doze/安全锁屏/OEM 热限、最低设备集真机证据。
4. M10 release evidence（HXA-099~103 记录自留）：24 小时长稳、真实 Doze/secure keyguard/OEM 热限与物理低内存 kill、物理设备（含 16 KiB）、真实 crash/ANR `ApplicationExitInfo` 联合证据、多 Provider/模型逐 case 评测。
5. HXA-105 遗留：30 分钟真机收益/资源对照；以及 ADR-0009 的所有者决定——**扫描后 AGENTS.md 已引用 ADR-0009 为 accepted，需按当前 ADR 文件状态重新核对 M10 收口条件与 bounded-orchestration spike 生命周期**。
6. verification-gaps"尚未执行"：16 KiB `FilesScreenTest.previewsTextFileWithHashInfo` 新失败的处理与 16 KiB developer 全量重跑、root manager revoke 修复后 rooted AVD 回归、显式 HTTP proxy device fixture。
7. x86_64：仅 AAR/ELF 静态证据，无设备运行证据。

**D. 未实现功能（status Known limitations 明示）**
8. SAF 写后端（浏览树内通用变更外部文档；HXA-058 的授权树导出是受限写路径，不扩大边界）。
9. ADR-0012 的 Trusted Workspace 与精确批量批准（高敏出网持久规则已由 HXA-068 落地，其余两项未实现）。
10. 持久 Git Workspace/Git UI/remote Git/凭据：ADR-0008 已接受但 roadmap 无实现 HXA（待立项）。
11. 生产 child Agent/Agent graph/Workflow：未实现，仅有 HXA-105 隔离 Spike（ADR-0009 状态见 C5）。
12. 文件层低优先项：age-based reclaim（trash 配额回收）。

**E. 里程碑整体（均未开始）**
13. M11/HXA-111~113：Codex app-server 登录 Spike、Claude Code Spike、工具拦截结论（含 CLI 底座 ADR；注意并行分支已占用 ADR-0022 编号）。
14. M12/HXA-120~123：渠道矩阵、发布门禁、签名/稳定 applicationId、商店提交与审核证据；"发布状态：仅开发/测试产物"。
15. M13/HXA-126~130：OAuth 登录层（需独立 ADR）、大 catalog 渐进发现、CLI/stdio Spike、完整生命周期、市场设计。

**F. 外部/环境依赖**
16. **Connector 交接材料未入 Git**（`app/build/outputs/connector-handoff-672dc5a/`，150 文件含 QwenWork 原包与设备日志，受 build/ 忽略保护，`gradlew clean` 会删除，需另行备份）。
17. HXA-103 记录遗留：API 36 全量中 1 例外部 SGLang endpoint smoke 超时失败，需开发机 endpoint 可达时重跑（不计为功能失败）。
18. ADR-0011 的 accept/reject 所有者决定（生产已在执行其契约）。

---

## 6. 优先级路线图

### P0（安全，本周可完成，总改动量 < 100 行）

1. **S1** provider 传输层禁用（或有界化）重定向——~3 行 + 测试。
2. **S4** A2A 通道补齐 egress 地址门控——复用 `McpSsrfEndpointGate` + NO_PROXY + 钉扎。
3. **S5** 消灭 raw 异常文案上屏——统一"封闭错误码→资源键"映射（复用 ApprovalUiMapper 模式），一次解决安全（路径泄漏）+ 文案一致性 + i18n 三件事。
4. **S7** 时间窗口 Long 溢出——改 `until > since + WINDOW`。
5. **S8** A2A 非流式 settle 改指数退避——~20 行。
6. 工程快赢：ci.yml 加 `./scripts/check-i18n.sh`（1 行）；spotless/detekt 排除 `.claude`/`.codegraph`（几行）。

### P1（功能性缺陷，小改动大收益）

1. **U4** stdoutBytes 上限常量修复 + 1 条设备测试（<10 行）。
2. **U1** SAF 移除崩溃 + **U3** share 重放（合计 ~5 行 + 2 个设备测试）。
3. **U2** blocked send 保留 composer 文本（小契约改动）。
4. **U6** ArtifactRepository 流式哈希替换 `file.readBytes()`（3 行 + 1 测试）。
5. **S3 + HttpFetch 测试搬 JVM + Content-Length 按实际字节产出**（同一条代码路径一次收）——安全关键代码进无设备 CI。
6. core 守卫：C1 A2A CAS + taskId 守卫、C2 UserScopeCodec exactly() 守卫、C3 统一 SHA-256 helper、C6 readAll/readWindow 对齐。

### P2（加固与架构）

1. **S2** PRoot symlink 加固（先真机 POC）+ **U5** PENDING 可取消 + **C9** PFD 总所有权 + **C10** 重活移出 binder 线程 + **C11** payload 磁盘预算。
2. **A1/A2** ChatService 三单元拆分（⑧→⑦→⑥）+ 存储 seam 接口化（解锁编排层 JVM 测试）。
3. **A3** EgressRule 收进 service 层；**A4** AppContainer 阶段化 builder + 两阶段装配。
4. **P1-P3** 性能：低频 turn-state 信号、FilesScreen 拆分/懒加载/降采样、ConversationIntents remember。
5. **S6** NetworkOriginScope 钉住地址类关闭跨类 rebinding（纯 JVM 可全量单测）。

### P3（工程设施）

1. **A5** convention plugin 抽取（build-logic included build）——根治 31KB 单文件与"加模块改 4 处"，独立 HXA 渐进迁移（先 3 个 app + check-lockfiles 去硬编码 35）。
2. CI 拆并行 job + 接入 3 个 spike gate + minify 前预置 unsigned release assemble。
3. configuration-cache：quickjs AAR 解析移任务执行期；评估 3 个逃生舱。
4. 脚本：shellcheck CI job、`check-cli-runtime-lock.sh` 双栈、spike 脚本 pin 从 catalog 读取。
5. 工具链：KSP 配套确认、detekt 升级评估。

### P4（文档治理）

1. ADR 状态单一事实化：修正 AGENTS.md（ADR-0008）与 implementation-guide.md 两处滞后引用；推动 ADR-0011 所有者决定；在 verify-adr.sh/check-docs.sh 增加"文档中 ADR 状态引用与文件头一致"的机械检查。
2. 消灭权威报告占位行：回填 main-merged-verification.md 的"最终矩阵仍在执行"行；check-docs 对 development/ 进展文档禁"仍在执行/待更新"残留词（白名单 in-progress 任务）。
3. 进展文档治理：8 份 worktree 进展文档在 docs/README.md 统一登记并标注性质；已合入且结论已入 status 的移入 history/；"尚未执行"清单与本文 §5 合并为 status.md 单一"开放门禁"小节。
4. status.md 结构瘦身：Current summary 表与 Completed bullet 去重（>8000 字符单元格改链接）；检查点行与 Completed 段机械一致性校验。
5. 编号治理：补"编号分配"小节（HXA 空号 017~019、029、089、098、106~109、114~119；ADR 空号 0020~0022 含跨分支占用 0022；HXA-089 来历）；adr/README 增加"跨分支占号需登记"。

### P5（中长期）

1. **A6** SSE 读取器收敛到 provider:api（~400-500 行去重，协议验收后一次性清理）。
2. **A7** 边界常量/bounded/arg helper/wait 逻辑收敛（tools/framework + core:model）——多 bug 修复 + 消灭 6+ 份拷贝。
3. **A8** spikes 归档（a2a 两个）+ bounded-orchestration 按 ADR-0009 状态处置。
4. **ConversationRepositories/PlanGoalRepositories 补 Fake-DAO 单测**（core 最大测试缺口，复用 storage 测试现成 helper 与 Fake DAO 模式）。
5. 其余 LOW 项按序排入后续 HXA（§4 各条已附改法）。

---

## 7. 附录：做得好、不应改动的部分

- **状态机**：TurnReducer/GoalReducer 纯函数 + `verify()` 全量不变量 + 非法事件统一 `TurnStep.unchanged` 语义，设计理由写在代码旁边。
- **错误体系**：HelixError 构造期校验（有界文案、safeDetails 字符集）；PathResolution/Workspace 异常消息全部预写、不嵌入文件系统细节；PolicyEngine/Ssrf 拒绝码是稳定 enum。
- **SSRF 分层**：decision-time floor（PolicyEngine）+ connection-time 全地址分类（含 IPv4-mapped/compatible 解包、Azure IMDS v6）+ connect 后 peer 复验，三层都测了。
- **原子写**：fsync 顺序（data fsync → rename → 目录 best-effort fsync）注释讲清楚了为什么；temp 回收不跟随 symlink。
- **纪律**：零 TODO、零阻塞调用、零共享可变状态、API 29 兼容性有意识规避（Collectors.toList()、手写 skip 都有注释说明原因）。
- **flavor 隔离**：consumer/developer 同名 seam + developerImplementation + `verify-variant-boundaries.sh` 机械门禁（dex+arsc+manifest 标记扫描），未发现 consumer 泄漏。
- **日志纪律**：生产代码仅 8 条 Log，全部只记不透明 id，无 prompt/路径/密钥。
- **PRoot/IPC 协议**：逐事务签名再校验、fail-closed 状态机、PFD 有界传输、terminalCommit 防篡改、exactly-once 重放、orphan sweep pid 复用保护、篡改归档 fail-closed。
- **QuickJS 通道**：wrapper JSON ABI 攻击面收得紧（strict IIFE、11 状态闭合集、±1 字节边界、深度 JSON、PFD 洪泛、崩溃注入、cancel race 全有测试）。
- **files 工具**：错误脱敏（`toModelReference()` + 固定文案 + IOException 兜底丢弃原始 message）+ "无真实路径泄漏"测试断言。
- **MCP/A2A 不可信数据隔离**：MCP 拒 READ_ONLY 分类、hints 只存不消费；A2A schema 全常量 + UNTRUSTED 前缀；Skill 默认 disabled。
- **provider 解码器测试**：各 30+ 命名场景（任意字节切块/UTF-8 跨块/terminal guard 全分支/oversize/fail-closed）；ToolDispatcher 44+ 场景；encoder 测试用结构化断言而非大段 golden 字符串；fixture 全部 127.0.0.1 或 `.example` 保留域、凭据占位串，唯一真实公网端点在 opt-in 验收测试（env+assumeTrue 门控，离线 CI 不触发）。
- **Room 迁移**：v1→v7 全链路 MigrationTestHelper 测试 + schema 导出 parity + FK 运行时启用断言。
- **依赖卫生**：版本全 pinned（catalog）、每模块 lockfile、`lockAllConfigurations()`、`FAIL_ON_PROJECT_REPOS` + JitPack `exclusiveContent` 限定 libsu 单 group、无 mutable 版本/无 Hilt/LangChain 类框架、各模块依赖面最小。
- **文档纪律**：507 链接零失效、ADR 格式 100% 合规、完成记录模板字段齐备、失败如实记录。
