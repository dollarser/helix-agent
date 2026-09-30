# Helix 全量代码审查报告

## 2026-09-30 Runtime 故障矩阵追加修复

所有者要求“修复整个 runtime 故障矩阵”。本轮已把 QuickJS 隔离/原生、PRoot 前台/后台 Job、手动 PTY、订阅 CLI、设备内模型纳入同一[故障矩阵与验证记录](../../docs/evidence/development/runtime-fault-matrix-2026-09-30.md)。基线为 `5fe01812` 加既有工作树；保留并行修改，未提交、未推送，未运行设备或真实模型账户。

新增修复包括：native/前台 PRoot 在提交前持久保留物理执行 owner，宿主重启只对账/退役原执行，不重放；订阅取消区分 CANCEL_REQUESTED 与实际退出；订阅运行中不提前读取成功归档；PRoot 存活进程不伪装成已停止终态，损坏 journal 不视为从未提交；全部新增查询/取消/结果回执核验原身份；本地模型 IPC 使用独立有界 IO/控制/强退通道，未确认提交时的取消先终止原进程；QuickJS 部分 Parcel/拒绝请求关闭 PFD；PTY 绑定失败和迟到 callback 不留下或复活连接。

矩阵逐项区分主机回归、AndroidTest 编译和设备未实测。最终门禁/数量以矩阵验证记录为准，下方前轮测试统计仅属于当时变更。**不将“退出通知/代码已修复/编译通过”推导为完整 OEM、内核冻结、脱管子进程或远端副作用故障都已验收。**

本轮最终 `test + detekt + spotlessCheck + 双渠道 lint + AndroidTest 编译 + Debug/AndroidTest APK 构建` 联合门禁通过（退出码 0）。源码门禁通过。32 项矩阵分别标识实现/主机/设备边界，新增 20 个独立主机用例通过，5 个新增设备用例只编译。当前 App Consumer/Developer 测试分别 991/1043（各保留 4 个历史跳过），QuickJS 101、PRoot app/client/core/ipc 9/23/136/45、CLI app/client 134/47、工具框架 221，全部 0 failure/error。完整命令、增量执行说明和制品路径见上方矩阵，不能把它表述为全部设备/OEM 故障已经实测关闭。

## 2026-09-30 后续优化：退出证据、容量预检与预览身份

所有者在第一轮交付后要求“继续优化”。本节记录后续增量，基线为 `5fe01812` 加第一轮未提交工作树；下方第一轮测试数字不作为本增量的验证结果。既有并行修改保留，未提交、未推送，也未调用设备或真实模型账户。

### 新确认的问题与处理

| 范围 | 确认的问题 | 后续实现与回归 |
| --- | --- | --- |
| 原生 QuickJS 退出确认 | 同步 terminate 返回或抛出错误后就清理连接，不能证明原进程已经退出；将一般传输错误写成 dead 也混淆了退出证据。 | `JsProcessDeath` 只由原始死亡通知或 linkToDeath 明确的已死结果置位；发送控制、取消、超时不置位。客户端只在退出证据之后释放执行线程许可、死亡监听及连接。 |
| 清理 IPC 饥饿 | 原生脚本进入阻塞 Java API 或 Binder 线程不足时，不能仅依赖新的同步终止请求。 | 原生服务增加独立看门狗：绑定后初始 45 秒，合法执行只缩短为原截止时间加 2 秒，终止通知为单向。看门狗不依赖执行线程、Binder 池或主 Looper 执行退出动作。 |
| 异常与取消语义 | 取消意图原先仅在中断通知发送成功后记录，通知失败可被误报为超时；清理自身异常也不能绕过退出等待。 | 先记录取消意图；`stopAndAwait` 在 finally 等待原始死亡，原异常在确认后继续传播，不返回假成功。资源释放采用嵌套 finally；恢复线程中断标记，不用中断替代退出。 |
| Goal 容量预检 | `contextFitsChecked` 对已经包含系统提示词的 persistedHistory 再次添加同一提示词，重复计入 token 和消息数，可能误阻止继续。 | 请求复用已组装历史，`ContextCapacity.forContinuation` 只添加一次候选用户输入。新增临界容量、重复计数反例、原历史不变及新增输入越过消息上限的回归。 |
| 图片预览 | produceState 的任务 key 改变不等于状态容器立刻替换，新图解码时可能短暂保留旧图。 | `rememberArtifactBitmap` 用图片身份隔离整个状态容器，新图立即从空预览开始；保留后台采样解码，不改变保存文件。此项为源码与编译验证，未做设备视觉验收。 |

### 验证与边界

**本增量最终联合主机门禁通过（2026-09-30）。** 最后一次联合命令退出码 0，844 个任务中 43 个执行、801 个 UP-TO-DATE；不能把所有任务描述为重新执行。采用以下命令，未启用可选 Spike、设备或真实服务：

```bash
python3 scripts/with-host-slot.py -- ./gradlew \
  test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:compileConsumerDebugAndroidTestKotlin \
  :app:compileDeveloperDebugAndroidTestKotlin \
  :runtime:quickjs:compileDebugAndroidTestKotlin \
  --continue --configure-on-demand --no-configuration-cache --console=plain
./scripts/check-all.sh --source
git diff --check
```

三条命令分别通过。源码门禁验证 653 个 Markdown、215 个 HXA、35 个当前 ADR、1850 个多语言资源键一致及秘密扫描。精确报告目录统计如下，不递归计入 build 历史归档：

| 当前报告 | 用例数（包含跳过） | 失败/错误 | 跳过 |
| --- | --- | --- | --- |
| QuickJS `testDebugUnitTest` | 101 | 0 / 0 | 0 |
| App `testConsumerDebugUnitTest` | 983 | 0 / 0 | 4 |
| App `testDeveloperDebugUnitTest` | 1031 | 0 / 0 | 4 |

本增量新增 7 个原生生命周期 JVM 用例及 2 个上下文容量用例，均通过；后两项在 App 双渠道共享，不合计为独立功能场景。两渠道各 4 项既有跳过不计通过。统计脚本为 `scripts/debug/2026-09-30/summarize-optimization-followup.py`，有界统计输出在 `build/optimization-followup-2026-09-30/test-summary.json`。Device status: **not requested**；测试 APK 源码编译不等于设备运行或 Binder 故障注入通过。

新增 `JsNativeLifecycleTest` 使用真实 `ExecutionOwnership` 许可覆盖中断/终止失败不提前放行写操作、原异常不丢失、已观察死亡、只缩短期限和单次看门狗。`NativeJavascriptDeviceTest` 增加成功及阻塞调用返回前原 Binder 已死亡的两个用例，额外观察者仍持有连接，避免把单纯 unbind 当作退出；本轮只编译这些设备用例，不运行。

**剩余边界：** 这关闭的是当前宿主存活时的同步清理等待和无退出证据放行路径，不是全部 Runtime R2 或故障矩阵完成。内核冻结、进程整体暂停、宿主骤停后重启、恶意同 UID 代码、外部子进程/已提交远端任务及 OEM 行为仍需专门验证。没有死亡证据时许可保持，外层 Dispatcher 可超时，但不得伪造执行退出或副作用回滚。实际设备死亡通知、UI 闪图及功耗未运行验收，不报告性能百分比。长期契约已更新到[QuickJS ADR](../../docs/adr/runtime/003-quickjs.md)。

## 2026-09-30：逐项复核与修复（当前结论）

本节取代下方原始审查草稿中的问题状态、优先级和验证结论。范围同时覆盖原报告 P0-1～P3-29、资源/UI/安全/工程条目，以及对话中补充的 B1～B6。起始基线为 `b51687e0` 加工作树；收尾时 HEAD 为并行文档提交 `5fe01812`，该提交只涉及文档与开发说明。本修复的生产代码仍在未提交工作树，既有并行修复保留并重新验证。本任务没有执行提交、推送、设备运行或真实模型账户调用。

**原报告并非全部属实。** 其中有真实的状态/并发/内存与接线缺陷，也有设计取舍被当作 bug、尚未证明的风险和已经过时的环境描述。不能沿用“核心安全全部实现正确”“8 处确定泄漏”“所有问题都是重构新增”等泛化结论；不以文件长度、没有 close 方法或 grep 没找到调用点直接判定缺陷。下面的“修复”表示实现已修改，验证强度以本节最后的实际门禁结果为准，不等于设备验收。

### A. 原报告确定性问题的裁决

| 原编号 | 复核结果 | 处理与代码依据 |
| --- | --- | --- |
| P0-1 | 属实，已有修复纳入复验 | `GoalReducer.onWakeFailed` 清除 checkpoint，保留错误事实并取消旧提醒；不能写出违反 Goal 模型约束的状态。 |
| P0-2 | 属实，已有修复纳入复验 | `TurnLaunchGate` 向 driver 传递启动失败，而不是用 Job.cancel 假装用户取消；启动失败与真实取消分开结算。 |
| P0-3 | 属实，修复 | `SessionRuntimeTools`/`UserQuestionTool` 使用受信任 metadata executor；仍经过 schema/权限/审批/审计，不让反问抢占外部执行 owner。 |
| P0-4 | 属实，修复 | `ToolDispatcher.commitExecutionStart` 在 Registry 短锁中完成准入排序，Room 审批消费在锁外；先准入的调用持原 executor，先撤销的调用不能消费或执行。补真实 Dispatcher 交错测试。 |
| P0-5 | 原结论过度，不确认为生产双写缺陷 | `ExecutionOwnership.guard/runOrdinary` 的 permit 在实际 executor 线程退出时释放；watchdog 超时不等于物理 worker 退出。保留该保护及有界容量，不以 scheduler slot 释放推导外部效果可重叠。 |
| P1-6 | 属实，已有修复纳入复验 | `AutomaticRecoverySettlement` 通过 `GoalEvent.RecoveryEnded`/reducer 生成合法错误终态，不再直接拼接非法 FAILED Goal。 |
| P1-7 | 部分属实：锁内 IO/竞争是优化对象，不是所有事务都可移出 | 保留 Turn 原子准入锁；本轮移除 Registry 中的持久写锁等待、工具曝光中的重复读，以及启动历史全量加载。大范围锁改造必须有具体交错反例，不能破坏状态检查与提交的一致性。 |
| P1-8 | 属实，修复 | `submissionAttempt` 保留稳定异常类型诊断；不把输入正文、凭据或原始绝对路径写入日志。 |
| P1-9 | 属实，已有修复纳入复验 | PRoot 输入描述符由流单一持有与关闭，避免重复 close。 |
| P1-10 | 误报，不修改业务语义 | 界面已明示自定义回答优先于选项；强行拼接 selected+custom 会改变既定产品行为。真正的答案投递问题见 B4。 |
| P1-11 | 属实，修复 | `AutomaticRuntimeCollection` 分离在途去重与有界已结束记录；失败/观察窗口耗尽进入冷却并记录原因，重开可重新观察；已完成结果不立即重复收集。 |
| P1-12 | 属实，修复 | PRoot 先查原任务 typed status；RUNNING 不计为失败，只有 SUCCEEDED 才拉取/确认成功归档。原报告“约 6 秒”不准确，原退避为 1+2 秒，另加查询耗时。 |
| P2-13 | 属实，已有修复纳入复验 | `UserScopeCodec` 在解析前验证字段数量，畸形输入返回规定的非法输入失败，不泄漏裸下标异常。 |
| P2-14 | 属实，修复/复验 | ContentStore 既有流式哈希修复保留；A2A 导入结果改用 `AtomicFileWriter.sha256Hex(Path)`，不为校验再分配整个文件。 |
| P2-15 | 属实，修复 | `JsBoundedOutput` 在分配前验证可信输出上限和文件长度，定长读取并检查 EOF；测试包含超大稀疏文件和长度不符。 |
| P2-16 | 属实，已有修复纳入复验 | BrowserDownloadQueue 用 `use` 明确关闭连接输入流。 |
| P2-17 | 属实，修复 | SAF 查询区分权限、取消、文件缺失和 provider IO 错误；元数据查询不再用 catch-all/null 隐藏所有失败。 |
| P2-18 | 属实，修复 | FileManager 操作错误使用稳定本地化提示，不直接展示底层异常 message。 |
| P2-19 | 属实，修复 | Provider probe ticket 按连接/能力/上下文模型分域，同类新探测仍淘汰旧结果；配置修改统一失效所有域。连接成功不覆盖较新的能力证据。 |
| P2-20 | “权限 fail-open”不成立，保留能力新鲜度边界 | capabilitySnapshot 表示模型功能证据，不是工具授权。暂时探测失败不能无条件抹除独立的已有能力；本轮修复不同探测相互覆盖，不把历史快照说成当前探测成功。 |
| P2-21 | 属实，修复 | `ProviderTestStatusStore` 的所有写/clear 共用进程内 RMW 锁，覆盖 create 旁路和多个实例；增加并发写入测试。 |
| P2-22 | 属实，修复 | `applySettings` 进入 SessionActionQueue → submissionGate → turnGate，和发送/用户配置变更有序；专用数据库入口与冻结配置见 B5。 |
| P2-23 | 属实，修复 | 反问先纯校验后存储，确定未修改的非法参数返回无副作用失败；读取损坏的问题记录隔离到单条，使用有界读取。没有添加当前 schema validator 不支持的 uniqueItems。 |
| P2-24 | 有界容量是保护，不按原建议回退 | 阻塞 worker 可以占用容量，必须显式报告 EXECUTOR_SATURATED；不能恢复无限 cached pool，也不能把超时当物理线程退出。长期阻塞仍需要 Runtime 边界验证。 |
| P2-25 | 误报 | BindingStore 把 host installation revision 与来源 revision 一起哈希；只看 register 默认 contractHash 漏掉了安装身份。incarnation 不进入稳定审批身份是明确设计。 |
| P2-26 | 保留同 UID 信任边界，不声称是跨 UID 漏洞 | native runtime 非导出、同应用 UID，明确不提供凭据隔离；客户端总开关不是恶意同 UID 代码的安全沙箱。完整强隔离不属于本次修复。 |
| P2-27 | 不吞 Fatal Error；原结论过度 | nativeReply 捕获业务 Exception，Fatal Error 由私有进程/失败边界处理。不能为“所有错误都返回成功 JSON”捕获并吞掉 OOM/VM Error。 |
| P2-28 | N+1/历史全量加载属实，修复 | 增加 `latestForUnarchivedSessions` 一次取得每会话最新 Turn；是否启动核查仍在原准入门禁内判定，不取消去重和用户停止边界。 |
| P3-29 | 拆项裁决 | 启动恢复异常补安全诊断，stopTask 异常显示失败而不伪造取消成功；其余细项见下文。 |

P3-29 中，Goal 检查预算还受原 Goal 累计账本约束；一次恢复 claim 后再次失败明确结束，符合当前 ADR-AGENT-001，而非无限自动重试遗漏；Steer 迁移后会重新读取、校验目标和 revision，不能仅凭未使用一个 Boolean 就宣称投递给旧目标；USER_STOP 不在自动恢复原因白名单。SYSTEM 消息位置、通用 Job 和 Core 拆分是需单独契约与验证的优化，不据此擅改模型协议。`LaunchedEffect` 使用新 List 实例不等于必然重新触发（不能忽略结构相等）；真正需要修复的是监听范围和答案状态变化，见 B4。

### B. 对话补充发现及跨模块修复

| 编号 | 问题与修复 | 回归边界 |
| --- | --- | --- |
| B1 / P1 | 后台 Job 持有 owner 时普通 tools.search 被拒绝，无法发现查询/取消/收集入口。生产 discovery 改为受信任 metadata executor，不扩大普通写入权限。 | `DiscoveryOwnershipTest` 从持有 owner 开始，经真实 discovery 注册/执行发现 collect，再调用受信任原任务控制入口；同时证明无关 writer 仍被拒绝。 |
| B2 / P1 | 与 P1-11/12 合并：运行中不是失败，观察结束不是任务成功，也不会重放原命令。 | `RuntimeCollectionRecoveryTest` 覆盖多轮运行后收集、失败冷却与重新观察；旧去重/取消测试继续保留。 |
| B3 / P2 | Fork 改 ID、清 turnId 后历史问题被重新激活。问题及跳过记录在分支中明确复制为 inert history，不复制原审批或执行身份。 | `QuestionAnswerAttemptTest` 验证历史状态分类；实际手机分支交互本次未运行。 |
| B4 / P2 | 已撤回或失败的 answer 记录仍关闭问题。按最新回答尝试的状态判断，合法重答使用稳定的新尝试 ID；排队、已送达、待处理记录不重复提交。 | 增加 Room answer revision Flow 驱动问题刷新，撤回无需重开会话；单测覆盖重答、并发去重身份和不可重答状态。 |
| B5 / P1 | 正常 settings.apply 来自 RUNNING_TOOL，但旧 selectModel SQL 只允许空闲，必然冲突。新增绑定当前 RUNNING 工具调用与 frozen runtime record 的 future-defaults 更新；已取消、已归档、队列未清空等均拒绝，确定未修改时返回 false。 | 当前 Turn 的 Provider/model/reasoning 与视觉能力继续用冻结快照；新增 `FutureSessionDefaultsDeviceTest` 验证真实 Room 条件和最新 Turn 查询。本轮仅编译该设备测试，不声称实际执行。 |
| B6 / P2 | 模型选择仍是独立 launch，选择后发送可乱序。模型选择与模式/推理修改一样进入 SessionActionQueue 和 submissionGate。 | 保留/扩展有序队列测试，覆盖连续模型选择、发送和旧快照；尚未做手机连点验收。 |

### C. 已落地的性能、安全和可维护性改进

- **工具曝光：** 每个 descriptor 一次可用性判断，在窗口锁外完成；Session workspace 从按 ID 查询取得，避免重复扫描全部会话。执行前的实时权限复验仍保留。
- **恢复结果：** `RecoveryEvidence` 提供带原调用身份、实际状态、stdout/stderr 或订阅文本的有界摘录，明确为不可信数据；凭据形状命中时隐藏该片段，输出截断有标记。它不授予权限、不确认 UNKNOWN、不代表完整归档分页能力。
- **UI：** Shell/Provider/权限页面只监听所需会话字段；浏览器用 lifecycle-aware collection；会话 entry 先分组再查表；Markdown/表格/thinking 缓存；图片移到后台有界采样；文件列表 LazyColumn 且 list/grid 稳定 key；目录过滤用 derivedStateOf。实际帧率提升未测，不编造性能百分比。
- **设置与导航：** QuickJS 开关经应用服务在 IO 线程读写，界面显示忙碌/失败；导航回调用 DisposableEffect 注册/清理，不在组合期直接写 service 字段。
- **浏览器脚本：** UserScript 名称用 JSON 编码，处理引号、反斜杠和换行；Eruda 固定 HTTPS 3.4.3 和 SHA-384 SRI。原草稿“updateScripts 没有生产调用、当前不可达”不成立，BrowserController 有接线。
- **信任文档：** 修正 CLI/PRoot verifier 对 manifest signature permission 与同 UID 检查的描述，保留校验，不夸大成凭据隔离。
- **QuickJS（第一轮状态）：** interrupt 改为 oneway，原生 terminate 当时保留同步等待，R2 清理阻塞风险未关闭。后续已改为单向终止、独立看门狗与原始 Binder 死亡确认，当前行为和未验证边界以本文顶部后续优化章节为准；控制排队始终不等于已退出。
- **工程：** QuickJS AAR 是特定 Test 的惰性输入，不在配置期枚举已解析文件；移除已确认没有调用的 EmptyDestination。依赖版本、锁文件与现有不相关工作不随意修改。

### D. 不应误修或仍需证据的问题

| 原分组 | 裁决 |
| --- | --- |
| 资源 1～8 | “未调用 cancel/shutdown”不足以证明泄漏。BrowserController.destroy 是页面释放，不是销毁整个应用 controller；关闭会话不得取消后台任务；进程级 audit、Runtime、日志泵和有界执行池应按其真实 owner 生命周期评价。未取得泄漏增长或 OEM 唤醒证据，不将这些条目写成已复现泄漏，也不粗暴逐项 shutdown。 |
| 诊断心跳 | 定时检测是诊断行为，不等于 Looper 永不 idle 或已证实 ANR；设备功耗与生命周期需单独测量。 |
| UI 10 / 大类拆分 | boxed state、重复渲染和大文件是技术债，不将全量机械替换/重构作为本次 bug 修复前提。 |
| 公开 API/重试参数 | 没有找到某个直接调用不证明整个模块死亡；ModePolicy 过滤有生产用途。maxAttempts 默认 1 不意味着应打开自动副作用重试。 |
| SignedConnectorIndexVerifier | `allowDowngrade=true` 是 ADR-CONNECTORS-004 明确接受的签名索引策略，不能作为实现 bug 静默改 false。回退标记与受信任签名校验保留。 |
| OAuth | v1-only 拒绝未知格式是 fail-closed；不凭空加入旧数据迁移。S256 缺元数据不走 plain 降级；exported callback 的 DoS 只是边界风险，本轮未证实可利用漏洞。 |
| 构建告警/缓存 | build/cxx 可随 clean 清理，不因告警迁移到新的永久隐藏缓存；忽略的历史证据不删除。测试统计必须指向本次任务实际的报告目录，不能递归把 build 下归档一起加总。 |

### E. 验证记录与剩余边界

**最终联合主机门禁通过。** 运行 `b5539e82-9065-4520-a890-233d2b055db3` 返回 exit 0 / `BUILD SUCCESSFUL`，842 个任务中 27 执行、815 up-to-date。此前同一修复验证中已实际执行过其余未变模块的测试；最终 App 两渠道单元测试重新执行，缓存命中不被表述为本轮每条测试都强制重跑。命令如下：

```bash
python3 scripts/with-host-slot.py -- ./gradlew \
  test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:compileConsumerDebugAndroidTestKotlin \
  :app:compileDeveloperDebugAndroidTestKotlin \
  :core:storage:compileDebugAndroidTestKotlin \
  --configure-on-demand --no-configuration-cache --console=plain --continue
```

| 门禁 | 实际结果 |
| --- | --- |
| root `test` 的常规 JVM/App 单元测试集合 | PASS；没有启用可选 `includeSpikes`，不能称全部 Spike 验收 |
| Consumer / Developer Android lint | 均 PASS；保留非阻断 hints，未新增 lint baseline 来掩盖错误 |
| detekt / spotlessCheck | 均 PASS |
| App 双渠道 AndroidTest Kotlin 编译 | 均 PASS，仅编译，没有运行设备测试 |
| core:storage AndroidTest Kotlin 编译 | PASS，包含新增真实 Room future-defaults 与 startup-query 回归夹具 |
| 源码/文档/ADR/i18n/秘密扫描 | 最终 `./scripts/check-all.sh --source` PASS，exit 0；运行 `cab96c2a-4cde-41c5-ad39-055bb9569d30`。验证 653 个 Markdown、215 个 HXA、35 个当前 ADR；扫描 818 个生产源码文件，base/en/zh-rCN 的 1850 个资源键一致，秘密扫描通过 |

当前精确测试报告目录的关键模块摘录如下；只读取这些模块的活动 `build/test-results/<task>/TEST-*.xml`，未递归加总 build 下历史归档。表中的“总数”包含跳过，不是新增测试数或独立产品场景数，App 双渠道共享大量测试。

| 模块 / 任务 | 总数 | 失败 / 错误 | 跳过 |
| --- | ---: | ---: | ---: |
| core:model / test | 161 | 0 / 0 | 0 |
| core:agent / test | 198 | 0 / 0 | 0 |
| core:policy / test | 189 | 0 / 0 | 0 |
| core:storage / testDebugUnitTest | 219 | 0 / 0 | 0 |
| tools:framework / test | 221 | 0 / 0 | 0 |
| extensions:mcp / test | 52 | 0 / 0 | 0 |
| extensions:plugin / test | 7 | 0 / 0 | 0 |
| runtime:quickjs / testDebugUnitTest | 94 | 0 / 0 | 0 |
| tools:automation / testDebugUnitTest | 55 | 0 / 0 | 0 |
| feature:browser / testDebugUnitTest | 134 | 0 / 0 | 0 |
| app / testConsumerDebugUnitTest | 981 | 0 / 0 | 4 |
| app / testDeveloperDebugUnitTest | 1029 | 0 / 0 | 4 |

**中间失败已保留归因而非抹去：** 离线缺锁定依赖后，当前宿主 Maven/Google 下载可用，在线编译通过；文件模块取消异常改为 JVM 类型；ProviderCapabilities 构造与 DAO fake 接口补齐；浏览器脚本单引号旧断言随 JSON 编码契约更新，并增加恶意形状名称回归；复杂度/行宽问题通过拆出职责明确的小函数解决；三个 Compose 直接读取 StateFlow.value 的 lint 错误改为窄 Flow 状态，随后修正初始标题的非空类型，最终联合门禁通过。未删除失败用例、增加跳过或放宽全局门禁。原草稿“当前无网络/无法复跑”的环境结论已失效。

新增回归位于 App 的 QuestionAnswerAttempt/RuntimeCollectionRecovery/DiscoveryOwnership/ProviderProbeGate/ProviderStatusConcurrency/ProviderProbePublication/RecoveryEvidence/SessionActionQueue 测试，以及工具框架的消费证明锁测试和 QuickJS 的有界输出测试。既有 Goal/启动门闩/scope 解码/流式文件修复一并复验。测试失败不通过删除断言或跳过用例解决。

设备、真实账号/模型、OEM/Doze/低内存、Binder 饥饿及进程骤停：**not requested**。本轮无设备通过声明；新增 Room 用例只有编译证据，不能替代实际执行。QuickJS 清理 IPC 极端阻塞风险 R2 仍开放；恢复输出目前是有界摘录，不是完整归档分页；实际 UI 帧率/功耗与一般任务完成率未测。原草稿的 938、4620 等统计只表示原先报告声称的历史结果，不作为本轮验收。没有为尚未证明的风险擅自扩大到全局引擎/插件重构。

## 原始草稿（历史记录，不作为当前结论）

<details>
<summary>展开原始审查文字：包含已纠正的误报、旧环境信息和旧优先级；以本文件上方逐项复核为准。</summary>


**审查基线**：HEAD `b51687e0`（"Complete atomic tool bindings and autonomous recovery"，300 文件 / +8144 / −1703）+ 当时工作树（5 modified + 53 untracked）
**代码规模**：32 个 Gradle 模块 / 2121 个 `.kt`（主源码 1283 / 单测 461 / androidTest 336）/ 主源码约 18.9 万行
**审查方式**：主机门禁实跑（detekt / spotless / unit test / `ci-run-gate.sh --source`）+ 分路只读代码走查（引擎与 Turn 生命周期、安全与信任边界、资源泄漏与错误处理、UI 性能与架构一致性、原子工具绑定与调度、自主恢复链路、QuickJS 原生桥、Provider 探测）；关键结论均回源码二次核验
**设备状态**：`not requested`（未启动模拟器/真机，未做设备验收）
**报告说明**：本报告合并了同日的两轮审查（`7480141b` 全量审查 + `b51687e0` 重构后复审），只保留当前基线下的状态。条目用 `[遗留]` / `[重构新增]` 标注来源。

> ⚠️ **快照声明**：审查期间仓库存在**并行工作线在实时写入**。两次实测：`docs/completion-records/HXA-231.md` 被改动使 `check-docs.sh` 由失败转通过；随后 `docs/development/implementation-guide.md` 被改动、`docs/research/harness-human-intervention-audit-2026-09-29.md` 被移除，使门禁再度失败。本报告是某一时刻的快照，复用前请按"当时基线 → 当前源码"复核。

---

## 结论摘要

| 维度 | 结论 |
| --- | --- |
| 客观门禁 | detekt + spotlessCheck 通过；`ci-run-gate.sh --source` 通过；重构核心 7 模块**实跑 938 用例全绿**；**全量测试无法在当前环境复跑**（沙箱无网络 + 缓存缺件，见第七节） |
| 核心安全不变量 | **实现正确**，未发现高危缺陷。工具管线、SSRF、路径穿越、凭据加密、审批原子性、WebView bridge 均经源码逐条核对为 fail-closed |
| 执行引擎 | **2 个高严重度缺陷仍在**（Goal 结算抛异常崩溃、启动失败被误判为取消并连带取消 Goal） |
| 工具绑定（本轮重构） | **3 个高严重度问题**：`ask_user` 缺所有权豁免、绑定锁内做 Room 写、超时后释放排他槽不同步 |
| 自主恢复（本轮重构） | **3 个高严重度问题**：绕过 reducer 写 `FAILED`、反问答案丢弃已选选项、自动收集失败后永久放弃 |
| 资源与生命周期 | **系统性**：至少 8 处协程/线程池/执行器创建后从不取消或关闭 |
| UI 性能 | 整个 App Shell 用非生命周期 `collectAsState`，随每个流式 token 重组 |
| 可维护性 | `ChatService.kt` 已到 **4432 行**（本轮重构未拆）；生产装配仍用 `FakeShellRepository` |

**最需要立即处理的三件事**

1. **`GoalReducer.onWakeFailed` 漏清 `nextCheckpoint`** —— 抛 `IllegalArgumentException` 穿透整个结算事务，导致 Turn 终态未落库、Goal 未结算、进程崩溃。
2. **`TurnExecutionDriver` 把启动期异常当作"用户取消"** —— 一次基础设施异常会把从未执行的 Turn 落成 `CANCELLED`，并**永久取消整个 Goal**。
3. **`AutomaticRecoverySettlement` 绕过 reducer 直接写 `FAILED`** —— 写出不带 `error` 的非法 Goal 行，之后任何 `reduce()` 都会抛异常。

---

## 一、确定性缺陷

### P0-1 `[遗留]` `onWakeFailed` 失败分支漏清 `nextCheckpoint`，结算事务抛异常并崩溃进程

- **位置**：`core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt:200-213`（对照 `:309-325` 的 complete/cancel 分支）
- **证据**：FAILED 分支只写 `state = FAILED, error, currentWakeMillis = 0L`，**没有** `nextCheckpoint = null`；而 `onCompleteRequested`（`:313`）与 `onCancelled`（`:325`）都显式清空。
- **触发**：`verify`（`:389-393`）要求 `nextCheckpoint != null` 时状态必须属于 `RUNNING/PAUSED/INPUT_REQUIRED/BLOCKED`。`nextCheckpoint` 可从 Room 经 `GoalStorageMapping.toRuntimeGoal()` 还原（`onCheckpointScheduled` 允许在 RUNNING 设置）。因此一个 RUNNING 且已排 checkpoint 的 Goal，只要一次 wake 失败（不可重试或重试耗尽），`step()` 内的 `verify` 就抛 `IllegalArgumentException`。
- **影响**：调用链 `GoalRunSettlement.settleBoundTurn` → `TurnSettlement.settleInTransaction` → `TurnExecutionDriver.terminalize` 无 `runCatching` 包裹，异常穿透结算事务：Turn 终态未落库、Goal 未结算、协程未捕获异常直接崩溃进程。
- **修复**：FAILED 分支补 `nextCheckpoint = null`，并补 `GoalEffect.ReminderCancelled` 与 complete/cancel 对齐。

### P0-2 `[遗留]` 启动期失败被当成"用户取消"，并把绑定的 Goal 永久置为 CANCELLED

- **位置**：`app/src/main/kotlin/com/helix/app/engine/TurnExecutionDriver.kt:84-99` 与 `:136-140`
- **证据**：`launch()` 在 `finally` 中 `if (!started) job.cancel()`；`run()` 中 `startGate.await()` 被取消后落到 `catch (e: CancellationException)`，直接 `terminalize(..., TurnState.CANCELLED, null)`。
- **触发**：`liveExecution.claim()`（`TurnLiveRegistry` 的 `require`）或 `hooks.beforeExecution()`（内部含 Room 读的 `refreshScreen()`）抛任何异常，即走上述路径。
- **影响**：一个已持久化（`WAITING_MODEL`）但**从未执行**的 Turn 被落成 `CANCELLED`；`GoalRunSettlement.decision` 把 `CANCELLED` 映射为 `GoalEvent.Cancelled`，于是**整个 Goal 被永久取消**。一次基础设施异常变成业务终态。
- **修复**：区分"启动期失败"与"运行期取消"。启动失败应走 `FAILED + INTERNAL` 或专用 launch-failure 终态，不复用 `CANCELLED`。

### P0-3 `[重构新增]` `ask_user` 未走 metadata 执行豁免，会抢占全局排他执行准入

- **位置**：`app/src/main/kotlin/com/helix/app/SessionRuntimeTools.kt:15`
- **证据**：
  ```kotlin
  CodeJavascriptRunTool.register(registry) { params, cancel -> javascript.execute(params, cancel) }
  UserQuestionTool.register(registry, questions)     // 没有 metadataExecutor 包装
  ```
  `UserQuestionTool.descriptor()` 的 `operationClass = ToolOperationClass.METADATA`（`UserQuestionTool.kt:38`）。而其它所有 METADATA 工具都经 `executionOwnership::metadataExecutor` 注册（`DefaultAppContainer.kt:495`、`:498`、`:505`、`:509`）。
- **为什么是问题**：`ExecutionOwnership.kt:113-114` 的分支是 `executor is MetadataExecutor -> runGuarded(...)`，否则 `else -> runOrdinary(...)`；`runOrdinary` 会 `acquire(callId, exclusive)`，失败即返回 `Failed("EXECUTION_BUSY: ...", sideEffectFree = true)`（`:250-255`）。`ask_user` 是纯问答工具，却因此参与排他执行所有权：任一 Runtime 所有者存在时它会失败；它自己也会占住排他 permit，阻塞 Runtime 启动/控制。
- **修复**：给 `registerSessionRuntimeTools` 增加 `executionOwnership` 参数，用 `metadataExecutor(...)` 包装 `UserQuestionTool`。

### P0-4 `[重构新增]` `ToolBindingStore.admit` 在全局绑定锁内执行 Room 写

- **位置**：`tools/framework/src/main/kotlin/com/helix/tools/framework/ToolBindingStore.kt:74-81` 与调用点 `tools/framework/.../ToolDispatcher.kt:804-810`
- **证据**：`admit` 的 KDoc 明确写 **"Only a short local admission commit is allowed here, never executor/approval/network work."**，但调用方传进去的第一件事就是批准消费：
  ```kotlin
  registry.admit(ref) {
      proof?.let { approvals.consume(it) }   // → StorageApprovalBroker.consume → Room 事务
      clock.now().also { ... }
  } ?: stopped(...)
  ```
- **为什么是问题**：`admit` 内是 `synchronized(lock)`。该 `lock` 同时保护 `resolve` / `snapshot` / `register` / `replace`，而 `ToolScheduler.resolveDescriptor` 会为**每个**调用构建 footprint。于是一次 `consume` 的磁盘提交期间，所有工具准入链被串到磁盘写后面——这正是新引入的全局串行点，且直接违反该函数自己的约定。
- **修复**：`admit` 的 `commit` 只做纯内存 CAS；`approvals.consume` 移到锁外（失败再拒绝/回滚）。

### P0-5 `[重构新增]` 超时/取消后立即释放排他槽，被放弃的 worker 可能仍在跑

- **位置**：`tools/framework/.../ToolScheduler.kt:302-309` 配合 `ToolDeadlineRunner.kt:72-76`
- **证据**：`dispatch` 返回后立刻 `releaseSlot(callId)`；而 `ToolDeadlineRunner` 超时路径只做 `future.cancel(true)`（best-effort）——worker 忽略中断会继续执行实际副作用。新增的 `ToolExecutionActivity.abandonedRunning` 正是为记录这个事实而加的，但它只进审计 `auditDetail`，**没有参与准入决策**。
- **为什么是问题**：一个写操作超时（worker 仍在写）后，调度器判定该调用终结并释放排他 lane，同批次中与其冲突的第二个写调用会被立即放行 → 两个写并发。这与项目"多个写操作保守串行、取消需等 terminal 对账"的意图相悖。
- **修复**：worker 真正退出前不释放排他槽；把 `abandonedRunning` 作为"lane 仍被占用"的事实源。

### P1-6 `[重构新增]` 自主恢复绕过 Goal reducer，直接写 `FAILED` 且不带 `error`

- **位置**：`app/src/main/kotlin/com/helix/app/engine/AutomaticRecoverySettlement.kt:46-51`
- **证据**：
  ```kotlin
  if (goal.state !in setOf("COMPLETED", "FAILED", "CANCELLED") &&
      storage.goalRuns.listOpenByGoal(goal.id).isEmpty()
  ) {
      storage.goals.updateGoal(goal.copy(state = "FAILED", currentWakeMillis = 0))
  }
  ```
- **为什么是问题**：`GoalReducer.verify`（`GoalReducer.kt:370-372`）要求 **`FAILED goal requires an error`**。这里直接写库绕过了 reducer，`error` / `finishReason` 均为 null，同时也没有发出 `ReminderCancelled` 效果。触发路径是 `AutomaticTurnRecovery` 的 `RECOVERY_UNAVAILABLE` 分支——此时 Goal 通常处于 `PAUSED`（启动恢复已 park），于是被静默改成 `FAILED`。该行之后一旦再经 `reduce()`（任何 Goal 事件）就会抛 `IllegalArgumentException`。
- **修复**：改走 `GoalReducer.reduce(goal, GoalEvent.WakeFailed(HelixError(...)))`，与 `GoalRunSettlement.inspectionFinished` 一致。

### P1-7 `[遗留]` 全局锁 `turnGate` 内嵌套阻塞 Room 事务

- **位置**：`app/.../chat/ChatService.kt:3822` 起 `synchronized(turnGate)`，其内 `:3929` 调用 `turnEngine.admit(...)`（`TurnAdmission` 内部走 `storage.withTransaction`）；同类还有 `:3451`→`:3453`、`:683`→`:686`。全文件 `turnGate` 出现 22 次。
- **证据**：`storage.withTransaction` 是阻塞调用（`HelixStorage` 的 `database.runInTransaction`）。`:3808` 的注释写"Room read runs OUTSIDE the gate"，对**那一处** provider 快照读成立，但 `admit` 的事务仍在锁内。
- **影响**：`turnGate` 是唯一的 admission/cancel/投影刷新全局锁，而 `refreshBackgroundTasks` 会被流式事件路径频繁调用。把 Room 事务放进该锁，会把"所有 Turn 准入"与"UI 投影刷新"串行化，单次准入可横跨多个事务，直接放大首 token 延迟。
- **修复**：锁内只做纯内存的 check-then-act 与 id 生成；持久化事务移出锁外。

### P1-8 `[遗留]` `submissionAttempt` 静默吞掉任意异常

- **位置**：`app/.../chat/ChatService.kt:2174-2181`（该文件共 7 处 `catch (_: Exception)`）
- **证据**：`@Suppress("TooGenericExceptionCaught", "SwallowedException")` + `catch (_: Exception) { ChatSubmissionOutcome.Rejected("ADMISSION_FAILED") }`。
- **影响**：发送准入链路（`sendSubmission` / `confirmSubmission` / `resumeSessionInput` / `sendQuestionAnswer` 等）全部经过这里。Room 异常、`require` 失败等一律折叠为对用户无意义、对日志无痕迹的 `ADMISSION_FAILED`，无法定位。
- **修复**：至少 `Log.e(TAG, ..., e)`；区分可重试/不可重试。

### P1-9 `[遗留]` `ProotJobRunner` 对同一 fd 双重关闭

- **位置**：`runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt:300-304`
- **证据**：
  ```kotlin
  inputPfd.use { pfd ->
      FileInputStream(pfd.fileDescriptor).use { input ->
          FileOutputStream(inputArchive).use { out -> input.copyTo(out) }
      }
  }
  ```
  `FileInputStream(fd)` 关闭时会关掉底层 fd，外层 `inputPfd.use` 又对同一 `ParcelFileDescriptor` 调 `close()`。
- **影响**：fd 二次 `close()`；若期间 fd 号已被其它线程复用，会误关他人 fd（fd 竞态），至少也会抛 `EBADF` 掩盖真实失败原因。
- **修复**：改用 `ParcelFileDescriptor.AutoCloseInputStream(pfd)`，只保留一条关闭路径。

### P1-10 `[重构新增]` 结构化反问：自定义文本会静默丢弃已选选项

- **位置**：`app/src/main/kotlin/com/helix/app/chat/UserQuestionService.kt:83`
- **证据**：`val value = custom.trim().ifBlank { question.options.filter { it in selected }.joinToString("; ") }`
- **为什么是问题**：`UserQuestionDialog` 允许"选中 chip **或**填自定义"，两者可同时非空。此时 `custom` 非空 → 选项被完全忽略，答案与用户实际选择不符。
- **修复**：`selected` 与 `custom` 合并（`selected + custom`），而不是二选一。

### P1-11 `[重构新增]` 自动收集失败即永久放弃，且静默无日志

- **位置**：`app/src/main/kotlin/com/helix/app/chat/AutomaticRuntimeCollection.kt:15`、`:22`、`:32-34`、`:49-52`
- **证据**：`private val requested = mutableSetOf<String>()`，只在 `DEFERRED` 分支 `requested.remove(key)`；`COMPLETE` 与失败耗尽两条路径都**不移除**。异常被 `catch (_: Exception) { Observation.RETRY }` 吞掉，无日志。
- **为什么是问题**：key 永久占位，同一恢复结果**永远不会再被自动收集**，UI 停在"不可用"；且失败原因完全不可见。
- **修复**：放弃时移除 key（带重试上界），并记录原因。

### P1-12 `[重构新增]` proot 自动收集没有 RUNNING 态，约 6 秒后永久放弃

- **位置**：`app/src/main/kotlin/com/helix/app/chat/AutomaticRecoveryCollection.kt:78-109`（判定在 `:103-107`）
- **证据**：订阅路径有 `RUNNING` 态会重置 `failures` 并最多轮询 60 次；proot 只有 `COMPLETE/RETRY`，`collectProot` 返回 null 即 `RETRY`，累计 `MAX_FAILURES` 次后放弃。
- **为什么是问题**：proot 后台作业超过约 6 秒才落结果时，恢复输出不再被自动收集。
- **修复**：给 proot 一个"运行中"观察态或显著更长的上界。

### P2-13 `[遗留]` `UserScopeCodec` 的 automation/root 解码用裸下标，违反自身 fail-closed 契约

- **位置**：`core/policy/src/main/kotlin/com/helix/core/policy/UserScopeCodec.kt:122-124`、`:134-137`
- **证据**：`decodeAutomation` 直接取 `fields[2]`、`fields[3]`；`decodeRoot` 直接取 `fields[0..2]`。而唯一的兜底 `catch (e: IllegalArgumentException)`（`:96`）**捕不到** `IndexOutOfBoundsException`。KDoc（`:69-71`）明确承诺"any malformed input returns null"。
- **触发**：`decode("hsr1\u0001a")` → `parts.size == 2` 通过检查，`tag = "a"`，`fields` 为空 → 抛 `IndexOutOfBoundsException`。
- **影响**：当前唯一生产调用方按 `?: error(...)` 处理，恰好也是 fail-closed，**无实际安全影响**；但任何新调用方若按 KDoc 契约依赖 `null`，会得到未预期异常。测试也有覆盖缺口。
- **修复**：改用已存在的 `exactly(fields, n) ?: return null`（与 `decodeBrowserTab`/`decodeAllfiles` 对齐），或让 `safeDecode` 额外捕获 `IndexOutOfBoundsException`。

### P2-14 `[遗留]` 已存在流式哈希实现，却仍用整文件 `readBytes()`

- **位置**：`core/storage/.../content/ContentStore.kt:62`、`:122`；`app/.../a2a/A2aTaskArtifacts.kt:104`
- **证据**：`ContentStore` 内部已有流式 `fun sha256Hex(file: File)`（`:139`），但写路径与 `readHashOrNull` 仍用 `sha256Hex(tmp.readBytes())`。`writeContent` 的 `content: String` 已在内存，写完后又一次全量读入，峰值内存翻倍；文档类内容可达 32 MiB 级。
- **修复**：统一改用流式 `sha256Hex(file)`。

### P2-15 `[遗留]` `JsExecutionClient.readBounded` 先全量读入再校验大小

- **位置**：`runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/JsExecutionClient.kt:537-546`（精确行号 541）
- **证据**：方法名叫 `readBounded`，实际 `file.readBytes()` 后才比较 `expectedBytes`；而 `expectedBytes` 来自 JS 服务进程上报的 `result.outputBytes`，真正的上限 `maxOutputBytes` 直到后续 `JsOutputArtifact.verify` 才校验。`File.readBytes()` 在 >2 GiB 时抛的是 `OutOfMemoryError`（不是 `IOException`），`:531` 的 catch 接不住。
- **影响**：行为异常的 JS 服务进程上报超大 `outputBytes` 时，客户端先把整个文件读进堆内存，可致 OOM。
- **修复**：读前 `require(file.length() <= maxOutputBytes)`，并用带上限的流式读取。

### P2-16 `[遗留]` `BrowserDownloadQueue` 未关闭 `connection.inputStream`

- **位置**：`feature/browser/.../BrowserDownloadQueue.kt:115-123`
- **证据**：只有 `out` 被 `use`，`connection.inputStream` 直接作为参数传入 `copyWithCap`，仅依赖 `finally` 的 `disconnect()`。
- **影响**：`disconnect()` 对 keep-alive 连接不保证立即关闭已打开的输入流；`copyWithCap` 抛异常时输入流泄漏。
- **修复**：`connection.inputStream.use { ... }`。

### P2-17 `[遗留]` provider 异常被伪装成 "not found"

- **位置**：`feature/files/.../ContentResolverSafTreeReader.kt:205-216`
- **证据**：`catch (e: Exception) { throw notFound(parentDocId) }`。
- **影响**：`SecurityException`（授权被撤销）、`RemoteException`（provider 崩溃）等全部被重写为 `FileNotFoundException`，上层把"权限失效/Provider 故障"当作"文件不存在"，掩盖真实原因。
- **修复**：区分异常类型，`SecurityException` 原样上抛或映射为权限类错误。

### P2-18 `[遗留]` 原始异常 `message` 直接透传给 UI

- **位置**：`app/.../files/FileManagerService.kt:500`、`:502`、`:516`、`:542`
- **证据**：`FileOpResult.Error(e.message ?: loc(R.string.files_error_...))`。
- **影响**：内部绝对路径、SAF authority、断言文本等直接渲染到用户可见的错误提示，既泄露实现细节又不可本地化。
- **修复**：按异常类型映射到稳定的本地化错误码，原始异常走结构化日志。

### P2-19 `[重构新增]` Provider 探测的分代粒度过粗，合法结果被作废并显示为失败

- **位置**：`app/src/main/kotlin/com/helix/app/provider/ProviderProbeGate.kt:13-21`
- **证据**：`begin(id, ...)` 用 `generations[id] = token` 按 **providerId** 分代；`ProviderConnectionProbe.runChecked` 与 `ProviderService.discoverContextWindow` 对同一 providerId 都调 `begin`。
- **为什么是问题**：用户点"测试连接"期间再打开上下文对话框，后者覆盖 generation，前者 `publish` 时 token 不匹配 → 一次**成功**的连接测试被丢弃，UI 反而显示 `PROBE_SUPERSEDED` 失败。反向同理，刚探测到的 `serverWindow` 会丢失。
- **修复**：generation key 带操作维度，或让"窗口发现"与"连接测试"各自串行化；"配置失效"只应由 `mutate` 触发。

### P2-20 `[重构新增]` 能力探测失败不清理 `capabilitySnapshot`（fail-open 残留）

- **位置**：`app/.../provider/ProviderConnectionProbe.kt:51-62`；读取方 `app/.../provider/ProviderService.kt:530-545`
- **证据**：`ProbeOutcome.Failed` 分支对 `detectCapabilities` 直接 `return@publish`（什么都不写），非能力探测只 `recordFailed(...)` 改状态行，**不动 DB 的 `capability_snapshot`**。而发送路径的视觉门控读的是快照。
- **为什么是问题**：先前探测通过（`vision=true`）的 Provider 重测失败后，仍被当作支持 vision/toolCalls。本轮措辞容易让人以为已 fail-closed，实际是"失败保留旧的已支持"。
- **修复**：失败时把快照重置为保守值（或加 `verifiedAt`/过期），并让门控同时要求"最近一次状态为 Passed"。

### P2-21 `[重构新增]` `ProviderService.create` 绕过 gate，状态存储读-改-写非原子

- **位置**：`app/.../provider/ProviderService.kt:281`（`create` 内直接 `testStatus.clear(id)`）；`ProviderTestStatusStore.kt:153-163`
- **证据**：`clear` / `replace` 都对共享 key 做 `lines()` → `setLines()` 的读-改-写，`LineStore` 不做整段 CAS。
- **为什么是问题**：`create` 与另一个 Provider 的 `recordPassed`（在 gate 内、IO 线程）并发时后写覆盖先写，丢一条状态行。`delete`/`update` 都走 `mutate` 不受影响，唯独 `create` 漏了。
- **修复**：`create` 的 `clear` 纳入 `probeGate`，或按 providerId 分 key。

### P2-22 `[重构新增]` `applySettings` 工具路径绕过 `submissionGate`，与发送准入不互斥

- **位置**：`app/.../chat/ChatService.kt:1482-1522`
- **证据**：UI 路径统一走 `sessionActions.submit { submissionGate.withLock { synchronized(turnGate) { ... } } }`（`:519-557`），新增的工具授权配置路径只锁 `turnGate`。
- **为什么是问题**：`helix.settings.apply` 执行期间，一次新的 `sendSubmission` 可并发进入 `admitSubmission`，两者都会读写 `sessionRunControls`；`settingsChangeAdmitted` 只检查"当前 pending 为空"，挡不住检查后新到的发送以旧 control 被接纳。
- **修复**：工具路径也纳入同一序列化域。

### P2-23 `[重构新增]` `ask_user` 参数校验抛异常，被结算为"副作用未知"并挂进 NEEDS_REVIEW

- **位置**：`app/.../chat/UserQuestionTool.kt:63-64` → `UserQuestionService.kt:105-110`
- **证据**：schema 只有 `maxItems: 6`，没有 `uniqueItems`；service 里 `require(options.distinct().size == options.size)`。模型给出重复 options 即抛 `IllegalArgumentException`。
- **为什么是问题**：异常从 executor 抛出 → 调度器记为 `Thrown` → `unsettledSlotSettlement` 得到 `sideEffectFree = false` → 被判为需人工复核。**一个纯参数错误把会话挂进 NEEDS_REVIEW**。另外 `pending()` 对畸形持久化行也会抛，可能让对话 UI 的 `LaunchedEffect` 崩溃。
- **修复**：schema 加 `uniqueItems`；executor/service 对非法参数返回稳定拒绝而非抛异常；`pending()` 对畸形行降级跳过。

### P2-24 `[重构新增]` 普通执行池从 cached 改为固定 32，阻塞型 executor 会耗尽全局容量

- **位置**：`tools/framework/.../ToolExecutionPools.kt:9-11`（`bounded(32, "tool-executor")`，替换了原先的 `newCachedThreadPool`）
- **为什么是问题**：忽略中断且阻塞的 executor 累计 32 个即永久占满，之后**所有会话**的工具调用都命中 `EXECUTOR_SATURATED`，且 `sideEffectFree = true` 使有界重试也无法恢复。新增的容量测试只验证"单个被放弃 worker 占 1 槽"，量级不同。
- **修复**：为阻塞型 executor 提供隔离/回收，或对持续增长的 `abandonedRunning` 做熔断。

### P2-25 `[重构新增]` 内置工具的 `implementationRevision` 默认等于 `contractHash`，身份无法区分实现变更

- **位置**：`tools/framework/.../ToolBinding.kt:11`、`ToolRegistry.kt:16-21`
- **为什么是问题**：`implementationRevision` 默认回落 `descriptor.contractHash.hex`，于是"契约不变、executor 换了"的两个实现得到相同的 `implementationIdentity`（`ToolDispatcher.kt:1095-1103`）。唯一区分是 `ref.incarnation`，而它被明确排除在可复用审批身份之外。当前因每次调用重新 mint 尚不可直接利用，但身份语义是错的。
- **修复**：内置/插件工具传入真实代码修订，或把 incarnation 纳入 `implementationIdentity`。

### P2-26 `[重构新增]` QuickJS 原生总开关只在客户端强制，服务端不校验

- **位置**：`runtime/quickjs/.../JsNativeExecutionService.kt:4-12`、`JsExecutionService.kt:44-46`
- **证据**：`JsNativeExecutionService` 的 `override val nativeAccess: Boolean = true` 是编译期常量；`JsExecutionService` 里 `binder = ExecutionBinder(if (nativeAccess) this else null)`。唯一读 `JsNativeAccessSettings` 的地方是客户端 `JsExecutionClient.execute`。该 service 在 manifest 中只是 `exported="false"` 的**同 UID 普通进程**。
- **为什么是问题**：同 UID 内任何代码只要直接 `bindService(JsNativeExecutionService)` 就拿到完整原生能力，与总开关状态无关。总开关成了单点、可绕过的授权（纵深防御缺口，不是已可利用漏洞）。
- **修复**：服务端 `onBind`/`handleExecute` 也读该设置并 fail-closed。

### P2-27 `[重构新增]` `nativeReply` 只捕获 `Exception`，`Error` 仍会跨字符串 ABI 抛出

- **位置**：`runtime/quickjs/.../JsNativeHost.kt:143-152`
- **证据**：`catch (error: Exception)` 里 `if (cause is Error) throw cause`。而 `Class.forName` 的静态初始化失败抛 `ExceptionInInitializerError`/`NoClassDefFoundError`（都是 `Error`），JS 构造的深嵌套 JSON 可触发 `StackOverflowError`。
- **为什么是问题**：本文件注释明说"native API 异常必须走字符串 ABI，不能逃出执行线程"，但 `Error` 会逃出。测试把该行为固化为"预期"，对不可信 JS 输入而言这是把控制面异常交回 native 边界。
- **修复**：对可预期 `Error` 也转成 error JSON；解析前先限制请求嵌套深度。

### P2-28 `[重构新增]` 启动恢复对每个会话做 N+1 扫描，并在 `submissionGate` 内起 Turn

- **位置**：`app/.../chat/ChatService.kt:888-902`
- **证据**：`storage.sessions.list().filter{...}.forEach { storage.turns.listBySession(it.id).lastOrNull()?.let { turn -> submissionGate.withLock { automaticTurnRecovery.recover(turn.id) } } }`
- **为什么是问题**：冷启动关键路径上的串行 DB 扫描 + 逐会话持锁调 `recover`（可能 `launchTurn` 起新 Turn）。
- **修复**：用一条"按会话取最后 Turn"的查询；把 `recover` 的准入与 `submissionGate` 解耦。

### P3-29 `[重构新增]` 其他

- **`ChatRequestAssembler.kt:477-485`** 把**全部** SYSTEM 消息提到历史最前（`restored.filter { role == SYSTEM } + restored.filter { role != SYSTEM }`），削弱了 `loop_warning`/`loop_exhausted` 等控制通知"紧随工具结果"的语义。修复：只前置尾部控制通知。
- **`AutomaticRecoveryPolicy.kt:20-22`** 对 boundGoal 用满额预算（不扣减已消耗），依赖 `RecoveryGoalRunStart` 的夹取；夹取失败时检查回合可拿到超过原 Turn 的额度。
- **`AutomaticInputRecovery.kt:45-55`** `input-recovery:<id>` 审计事件在成功恢复后仍保留，同一输入再次 delivery 失败即直接 `failPending` → **一次瞬时失败被放大为永久失败**。
- **`AutomaticInputRecovery.kt:32-34`** 忽略 `requeueAfterTargetFinished` 的返回值，可能把输入重新投递给旧目标。
- **`AutomaticInputRecovery.kt:17-18`** 只看最新 turn 的停止态，较早 turn 被 park 的输入会在用户已停止后被恢复。
- **`AutomaticTurnRecovery.kt:43-53`** catch-all 静默吞掉 `start` 异常（无日志），编程错误被转成"恢复不可用"。
- **`ToolScheduler.kt:346-352`** `catch (IllegalArgumentException)` 已成死代码（`resolveBinding` 返回可空、从不抛），建议删除以免误导。
- **`UserQuestionDialog.kt:35-37`** `LaunchedEffect` 用 `screen.toolTimeline` 作 key，每次投影刷新都是新 List 实例 → 频繁触发 `service.pending()` 的多次 DB 读。
- **`app/.../engine/TurnEngine.kt:204-206`** 对"非终态且无 live driver"直接 `error(...)`；调用方 `ChatService.stopTask`（`:2889-2905`）的 `workScope.launch` 无 try/catch，异常会作为未捕获异常终止协程。

---

## 二、资源与生命周期泄漏

共性：**创建后从不取消/关闭**。这类问题在进程级单例上不会立即崩溃，但会持续占用线程/唤醒、让"关闭/销毁"语义失效，并妨碍测试隔离。

| # | 位置 | 问题 |
| --- | --- | --- |
| 1 | `feature/browser/.../BrowserController.kt:534`（启动 `:541`、`:572`，销毁 `:752`） | `persistenceScope` 创建后全文件无 `.cancel()`，`destroy()` 不取消它，销毁后仍可能有写入任务触碰已 `clear()` 的 host |
| 2 | `app/.../audit/AuditLogService.kt:43`（`init` 中 `:68-82` 无限 `collect`） | 无 `close()`/`shutdown()`，进程级 IO scope + 永不结束的 collect |
| 3 | `feature/browser/.../BrowserDownloadQueue.kt:23` | `downloadExecutor` 从不 `shutdown()`，排队下载无法取消 |
| 4 | `runtime/proot-app/.../ProotJobRunner.kt:102`、`:106` | `jobExecutor` / `watchdogExecutor` 从不 shutdown；`:187` 还注册了周期性任务 |
| 5 | `tools/framework/.../ToolScheduler.kt:62` | 工具调度线程池无 `close()`；`ToolExecutionPools.bounded(...)` 同样 |
| 6 | `runtime/proot-core/.../JobLogSpool.kt:29` | 日志泵 `Executor` 从不 shutdown（需从 `Executor` 改为 `ExecutorService`） |
| 7 | `app/.../chat/ChatService.kt:161`（生产装配 `DefaultAppContainer.kt:800` 未传 `scope`） | 默认新建的 `SupervisorJob + IO` scope 永不取消；`closeSession()` 不停止在途 `launch/async` |
| 8 | `app/.../diagnostics/ProcessDiagnostics.kt:145`、`:155-163` | 心跳以固定间隔永久 `postDelayed`，主 Looper 永不 idle；替换的全局未捕获异常处理器无 `stop()` 恢复路径 |

---

## 三、性能与 Compose

1. **整个 App Shell 用非生命周期 `collectAsState`，随每个流式 token 重组全壳** —— `app/.../MainActivity.kt:240-247`（3 处）、`:655`。`HelixApp` 在 body 中读 `drawerScreen` 并重建 `ConversationDrawerState`，`chatService.screen` 每变化一次（流式期间每 token）整个 `HelixApp` 重组，`NavHost`/`Scaffold`/drawer 全部重新求值；且非生命周期收集在 Activity 停止后仍在跑。同项目 `ChatScreen.kt:74` 已正确使用 `collectAsStateWithLifecycle`。`feature/browser/.../BrowserScreen.kt:73-81` 同类（11 处）。
2. **`LazyColumn` content 内 O(n²) 分组与过滤** —— `app/.../ui/ConversationSection.kt:265-297` 配合 `app/.../chat/ConversationEntry.kt:19-25`：每个 key 对全量 `messages` / `toolTimeline` / `subscriptionRecoveries` 各 `filter` 一次，复杂度约 O(keys × messages)，且两处 `messages.filter{}` 每帧新建列表。
3. **组合期在主线程解码 Bitmap** —— `app/.../ui/ArtifactsScreen.kt:344-347`：`BitmapFactory.decodeByteArray(...)` 无 `remember`、无 IO 派发。对照 `FilesScreenEffects.kt:67-77` 已正确放到 `Dispatchers.IO`。
4. **消息渲染每帧重新解析 Markdown / thinking** —— `app/.../ui/MarkdownText.kt:106`（`markdownInline`）、`:137-139`（表格 `split('|')`）、`app/.../ui/ConversationMessage.kt:147`（`ThinkingParser.parse`）均未 `remember`（同文件 `markdownBlocks` 已 `remember`）。
5. **组合期写 service 可变字段** —— `app/.../ui/ChatScreen.kt:151-152` 直接赋值 `chatService.recoveryModelsNavigation = onModels`，属 Compose 禁止的副作用模式。
6. **文件列表未懒加载 / 缺 key** —— `app/.../ui/FilesScreenLayout.kt:172-180`：LIST 模式用 `Column + verticalScroll` 全量组合；`:189` 的 `items(visible)` 缺 `key`。
7. **`visibleEntries` 是无缓存 getter** —— `app/.../ui/FilesScreenState.kt:136`，被 `FilesScreenLayout.kt:43` 每次重组读取；搜索逐字符输入时对大目录反复全量过滤。
8. **主线程同步 `SharedPreferences.commit()` + UI 直连 runtime.quickjs** —— `app/.../ui/QuickJsSettingsSection.kt:32`、`:45` → `runtime/quickjs/.../JsNativeAccessSettings.setEnabled`（`commit()` 同步落盘，失败还会 `check()` 抛异常）。同时与 AGENTS.md"UI 不直接访问 QuickJS"不符。
9. **为读单个字段收集整个 `ChatScreenState`** —— `app/.../ui/ProviderScreen.kt:60`、`app/.../ui/SessionPermissionSection.kt:139-144` 只关心 `openSessionId`，却订阅高频 `chatService.screen`。
10. **84 处 `mutableStateOf<Int>` 未用 `mutableIntStateOf`** —— Android Lint `AutoboxingStateCreation` 共 84 条（`ArtifactsScreen.kt:73`、`:475` 等）；项目内已有正确用法（`FilesScreenState.kt:35`），属不一致。

---

## 四、可维护性与架构一致性

- **`ChatService.kt` 4432 行**（全仓最大；其后 `ToolDispatcher.kt` 1199、`ProotJobRunner.kt` 942、`WorkspaceArtifactStore.kt` 935、`DefaultAppContainer.kt` 919）。单文件承载聊天状态、投影、恢复、草稿、任务等职责。本轮重构未拆。
- **生产装配仍用 `FakeShellRepository`** —— `app/.../DefaultAppContainer.kt:153-155`，且带 `@Suppress("SENSELESS_COMPARISON")`；接口唯一实现却叫 `Fake`。
- **3 个公开 API 生产零调用（仅测试引用）**，但 KDoc 声称是权威来源：
  - `core/agent/.../GoalForeground.kt:47`（声称"前台卡片生命周期的单一事实源"）
  - `core/agent/.../DeveloperRuntime.kt:18`
  - `core/agent/.../ModePolicy.kt:23`（声称"Exposure uses trusted operation contracts"）
- **`EmptyDestination` 为死代码** —— `app/.../MainActivity.kt:659-690`，全仓无引用，自带 `@Suppress("UnusedPrivateMember")`。
- **字节格式化三份并行实现**：`app/.../ui/UiLabels.kt:70` `formatBytes`（KiB/MiB）、`app/.../ui/FilesScreenComponents.kt:223` `formatSize`（KB/MB/GB）、`app/.../ui/LocalModelDialog.kt:319` `formatBytes`（GiB）——单位标签不一致。
- **近重复渲染块**：`app/.../ui/SessionPermissionSection.kt:59-93` 与 `:96-124`；`app/.../ui/TaskResultDialog.kt:27-72` 与 `app/.../ui/ArtifactsScreen.kt:465-521`；`ConversationSearch.kt:95-148` 与 `ConversationSection.kt:265-326` 各自实现同一套 entry 遍历。
- **`maxAttempts` 无任何生产调用点传值**（全仓 `src/main` 无 `maxAttempts =` 赋值），恒为默认 1，使 `ToolDispatcher.kt:321` 的 `attempt < request.maxAttempts` 重试分支为死代码（HXA-037 有界重试未接线）。

---

## 五、安全与信任边界

**总体结论：未发现高危缺陷。** `AGENTS.md` 声明的核心不变量经源码逐条核对，实现正确且 fail-closed：

- **工具调用管线顺序**：`tools/framework/.../ToolDispatcher.kt:344-362`、`:379-432`、`:721-782` 严格按 schema 校验 → capability → policy → session permission → approval → 原子消费证明 → 执行 → 输出绑定 → 单次审计；任一步抛出仍先结算审计再传播。
- **MCP/A2A 元数据不能授予权限**：`ToolDescriptor.kt:97-99` 在构造期禁止远端 origin 工具归类为 `READ_ONLY`/`METADATA`；`McpDynamicToolBridge.kt:180`、`ToolSource.kt:224` 硬编码 `operationClass = NETWORK`、`requiredCapabilities = emptySet()`。
- **SSRF 双重防线**：决策期 `PolicyEngine.RESERVED_HOSTS` + 连接期 `SsrfAddressPolicy.check`；`HttpFetchBridgeImpl` 每跳重校验并在连接后 `revalidatePeer`；TLS 用原始 hostname 做 SNI。
- **WebView 无特权 bridge**：全仓（除测试）无任何 `addJavascriptInterface`；`shouldOverrideUrlLoading` 恒返回 true 并重准入；`BrowserSecuritySpec` 的 file/content/universal access 全 false 且 `assertHardened` 逐项 `require`。
- **凭据加密真实生效**：`SecretStore` / `CliSubscriptionCredentialVault` 均为 AndroidKeyStore AES-256-GCM、随机 IV、0600、原子 `rename`、`finally` 删除临时文件。
- **审批原子一次性**：`ApprovalDao.consumeByBinding` 把 `decision/consumedAt/expiresAt/bindingHash` 放入**单条 SQL UPDATE**。
- **路径穿越**：`SkillRepository.resourcePath`、`PathResolution.resolveWithinRoot`、`FileScopePath`、`RootServiceOperations.readFile` 四处独立实现且均完整。
- **QuickJS 进程边界**：`isolatedProcess="true"` + `exported="false"`，slot 用 `compareAndSet` 防重放。
- **Provider 探测的 stale publish 与取消语义已修好**：`ProviderProbeGate` 的分代 + `mutate` 删除分代 + `publish` 的 `ensureActive`，加上配置实体二次结构比对，旧成功/失败无法覆盖新状态；同一 Provider 的多探测由单一 `Mutex` 串行。
- **`GoalDurableUsageLedger` 无丢失更新/重复计数**：读改写与分块心跳在同一个 `withTransaction` 内，进程中途死亡整体回滚。
- **自主恢复无无限重试/活锁**：`AutomaticTurnRecovery` 的 `isInspection` 后继不再生后继，`eligible` 排除 `isInspection`，Goal 续跑用 `clientRequestId` 去重。
- **UNKNOWN 语义未被破坏**：检查回合用 `isInspection` 强制只读（`ChatRequestAssembler` 的 `recoveryOnly` 过滤 + `ChatToolCalls.recoveryEffectRejection`），`GoalRunSettlement` 对检查回合走 `WakeFailed`。唯一破坏点是 P1-6 那条直接写 `FAILED`。
- **`ask_user` 的答案绑定是安全的**：`question:<sessionId>:<turnId>:<toolCallId>` 的 id 与 `require(pending(...).any { it == question })` 保证答案只绑定到发起反问的那次调用。
- **QuickJS 的 slot/句柄/文件无泄漏**：服务端 slot 经 `finally → markUsed()` 回收，PFD/临时文件在客户端与服务端各自 `finally` 关闭。

以下是中低严重度的真实问题或契约不一致：

| # | 位置 | 问题 |
| --- | --- | --- |
| 1 | `feature/browser/.../engine/WebPageTools.kt:54-59` | `injectEruda` 用协议相对 URL `//cdn.jsdelivr.net/npm/eruda` 加载**未锁版本、无 SRI** 的第三方脚本。若当前页面是 `http://`，会以 http 加载子资源（不触发 mixed-content 拦截），同网攻击者可替换脚本在页面上下文执行任意 JS。修复：锁精确版本 + 强制 https，或打包为本地 asset 经 `WebViewAssetLoader` 加载 |
| 2 | `extensions/skills/.../connector/index/SignedConnectorIndexVerifier.kt:29` | `allowDowngrade: Boolean = true` 默认宽松，sequence 回退仅标记 `isDowngrade` 不拒绝。当前生产零调用点，但未来接线并沿用默认值即构成版本回滚攻击面。修复：默认改 `false` |
| 3 | `runtime/proot-app/.../ProotCallerVerifier.kt:11-16` | KDoc 声称"platform signature permission already guarantees…"，但该模块 manifest 无任何 `<permission>` 声明，真实保护只来自 `exported="false"` + 同 UID 校验。属文档与实现不符；风险在于误导后续把组件改成 `exported="true"` |
| 4 | `runtime/cli-app/.../CliCallerVerifier.kt:15-21`、`ProotCallerVerifier.kt:32-40` | 通过 `callingUid == myUid()` 之后，包名与证书比对必然成立，是恒真的自校验；KDoc 描述为"narrows further"不准确。代码作为纵深防御应保留，但文档需修正 |
| 5 | `feature/browser/.../engine/UserScriptEngine.kt:68-82` | `wrapScript` 的 `safeName` 只转义单引号，未处理反斜杠/换行。当前 `updateScripts(...)` 无生产调用点（脚本恒为空）故不可利用，但接入用户脚本导入后 `@name` 属不可信输入。修复：改用 JSON 编码注入 |
| 6 | `app/.../mcp/oauth/OAuthCredentialCodec.kt:47` | 硬编码 `require(version == 1)`，无 `when(version)` 迁移分支 |
| 7 | `extensions/mcp/.../oauth/McpOAuthMetadata.kt:45` | `supportsS256()` 把"字段未声明"当作"支持 S256"（RFC 8414 语义应为未声明）。因全流程强制 S256 且无 `plain` 降级，失败是 fail-closed，风险有限 |
| 8 | `app/src/main/AndroidManifest.xml:105-119` | `McpOAuthCallbackActivity` 是除 `MainActivity` 外唯一 `exported="true"` 组件（OAuth 回调必需）。防护已到位（一次性 attempt + `matches` 全等比对 + 异常统一收敛），但构成集中 DoS 面 |

---

## 六、构建与工程卫生

- **C/C++ staging 目录告警**：`app/build/cxx` 位于临时构建目录内，每次 clean 后不保留。建议改用默认 `app/.cxx` 或临时目录外的路径。
- **配置期解析 classpath**：`runtime:quickjs` 在 configuration time 解析 `debugRuntimeClasspath`（Gradle 明确报为 build performance issue）。
- **Lint 规则被放宽 6 项**：`config/lint/lint.xml` 忽略了 `AndroidGradlePluginVersion`、`GradleDependency`、`NewerVersionAvailable`、`OldTargetApi`、`PluralsCandidate`、`UnusedResources`。其中 `UnusedResources` 被关闭意味着资源删除不会被自动发现（`EmptyDestination` 这类死代码正是靠人工发现）。
- **`build/` 下堆积大量历史归档**：`build/worktree-archives/`、`build/worktree-retirement/2026-09-13/`、`build/main-verification/`、`build/ci-integration-2026-09-21/` 等，整个 `build/` 约 3.6 万个文件。都在 gitignore 内，不影响提交，但会污染任何基于 `build/**` 的统计（聚合测试结果时会把归档算进来，实测把 4614 个用例虚报成 29878）。
- **环境前置**：本机 PATH 缺少 `rg` 与 `java`（`rg` 实际在 `/opt/homebrew/bin`，JDK 17 在 `/opt/homebrew/opt/openjdk@17`）。未显式配置时 `check-secrets` 会因缺 `rg` 直接失败、Gradle 无法启动。这不是项目缺陷，但会让门禁在干净环境下产生假失败。

---

## 七、门禁与验证边界

### 7.1 实跑结果

| 门禁 | 结果 |
| --- | --- |
| `./gradlew detekt spotlessCheck` | ✅ 通过（46s） |
| `./scripts/ci-run-gate.sh --source` | ✅ 通过（10 组 python 单测全 OK；首跑曾因沙箱临时错误 `EEXIST` 失败一次，重跑通过） |
| 单元测试（重构核心 7 模块实跑） | ✅ **938 用例 / 0 失败**：`tools/framework` 220、`core/agent` 195、`core/policy` 187、`core/model` 161、`provider/api` 116、`extensions/mcp` 52、`extensions/plugin` 7 |
| 单元测试（磁盘上全部结果） | 4620 用例 / 0 失败 / 8 跳过 |
| Android Lint | 92 Hint / 0 warning / 0 error（`abortOnError` + `warningsAsErrors` 已开） |
| `check-docs.sh` | ⚠️ 当前失败，**原因来自并行工作线**（见下） |

### 7.2 无法完成的验证（环境限制，非项目缺陷）

**全量 `./gradlew test` 无法在本环境复跑**：

- 沙箱**无外网**：`curl https://dl.google.com/...` 返回 `HTTP=000`；Gradle 报 `Remote host terminated the handshake`。
- 本地 Gradle 缓存**缺件**：`:feature:files:debugUnitTestRuntimeClasspath` 锁定的 `androidx.annotation:annotation:1.8.1` 不在缓存（缓存里只有 `1.9.1`/`1.10.0`/`1.3.0`），`--offline` 同样失败；`runtime/terminal-renderer` 还缺 `kotlinx-serialization-core:1.7.3`、`kotlinx-coroutines-android:1.9.0`。
- 这些版本由**各模块自己的 `gradle.lockfile`** 钉住（不同 configuration 合法地钉不同版本），重构提交**未修改任何 lockfile**（`git show b51687e0 -- '*lockfile*'` 为空），所以不是重构引入的回归。
- `./scripts/check-lockfiles.sh` 也因此跑不完（被 SIGTERM）。

**需要在有网络的环境执行 `./gradlew test` 与 `./scripts/check-lockfiles.sh` 才能闭合。**

### 7.3 并行工作线造成的门禁失败（非本报告引起）

审查期间 `check-docs.sh` 两次被并行工作线改变状态：

- 一次由失败转通过（`docs/completion-records/HXA-231.md` 补上 `决策记录：`）。
- 一次由通过转失败，当前失败项**全部来自其他工作线的在改文件**：
  - `docs/development/implementation-guide.md` 缺少 4 段门禁要求的契约文本（`docs/development/status.md`、`已有完成记录的 HXA 不得重复实现`、`只有用户明确要求建立持久 Goal 时才创建`、`verification matrix`）；
  - `docs/architecture/harness-refactor-plan.md`、`docs/bug-fixes/2026-09-29-autonomous-recovery.md`、`docs/development/tasks/HXA-232.md` 三处指向的 `docs/research/harness-human-intervention-audit-2026-09-29.md` 不存在。

本报告自身的链接均解析正常，未被列为失败项。

### 7.4 未覆盖范围

- 未做设备验收（`not requested`）。
- 未逐行核查：`tools/files/` 的归档解压与符号链接处理、`runtime/proot-app/` 的安装校验、`extensions/mobile-use/`、`app/.../network/LanScopeStore` 的用户创建路径。
- 静态走查 + 门禁通过不等于功能/安全验收；AndroidTest 仅编译未运行。

---

## 八、优先级建议

### 第一梯队：能确定性崩溃或造成错误终态

1. `GoalReducer.kt:210` 补 `nextCheckpoint = null`（P0-1）。
2. `TurnExecutionDriver` 区分启动失败与取消（P0-2）。
3. `AutomaticRecoverySettlement` 改走 `GoalReducer.reduce`（P1-6）。
4. `SessionRuntimeTools.kt:15` 给 `ask_user` 补 metadata 豁免（P0-3）。
5. `ProotJobRunner.kt:300-304` fd 双重关闭（P1-9）。

### 第二梯队：并发与准入正确性

6. `ToolBindingStore.admit` 内的 `approvals.consume` 移到锁外（P0-4）。
7. 排他槽释放与 worker 真实退出对齐（P0-5）。
8. `ChatService` 的 `turnGate` 内移出阻塞 Room 事务（P1-7）；`submissionAttempt` 补日志（P1-8）。
9. 工具配置路径纳入 `submissionGate`（P2-22）。

### 第三梯队：恢复链路健壮性

10. 自动收集失败后可重试，并区分"运行中"与"失败"（P1-11、P1-12）。
11. 反问答案合并 `selected + custom`（P1-10）；schema 加 `uniqueItems`（P2-23）。
12. Provider 分代粒度、能力快照 fail-open、`create` 旁路（P2-19、P2-20、P2-21）。

### 第四梯队：性能与技术债

13. 8 处 scope/executor 的 `cancel()`/`shutdown()`（第二节全部）。
14. `MainActivity`/`BrowserScreen` 改用 `collectAsStateWithLifecycle` 并只派生所需字段；`ArtifactsScreen` 图片解码移出组合期；`ConversationSection` 分组加 `remember`（第三节 1-4）。
15. 全量 `readBytes()` → 流式哈希/有界读（P2-14、P2-15）。
16. 拆解 `ChatService.kt`（4432 行）；`FakeShellRepository` 重命名；删 `EmptyDestination`；`ModePolicy`/`GoalForeground`/`DeveloperRuntime` 要么接线要么降级为 `internal`。
17. 收敛三份 `formatBytes`；合并重复渲染块；84 处 `mutableStateOf<Int>` → `mutableIntStateOf`。
18. `injectEruda` 锁版本；`allowDowngrade` 默认 `false`；`maxAttempts` 死代码处理；修正 `ProotCallerVerifier`/`CliCallerVerifier` 的 KDoc。
19. 清理 `build/` 下的历史归档；修正 C/C++ staging 目录与 quickjs 配置期解析。

</details>
