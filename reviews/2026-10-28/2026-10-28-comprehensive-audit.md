# Helix 综合审查：2026-10-28

> 本文保留首次审查时的源码事实与验证结果，不代表整改后的当前状态。随后按二次评审实施的修复、定向回归和最终宿主验证见 [综合审查整改记录](../../docs/bug-fixes/2026-10-28-audit-remediation.md)。新验证不能倒填为本次历史审查已经执行。

## 基线、范围与结论边界

审查基线：`9362690bb1a6154185bbd40b83b0e9ae0f48d54b`（main），并读取当时工作树。审查期间存在其他工作线的知识库 UI、资源、设备测试和 bug-fix 文档修改；本审查不修改、不归属这些变化。工作树不静止，因此本次 Debug 结果不能直接归因于 clean HEAD。

本轮覆盖产品定位、竞品取舍依据、架构与扩展性、重复规则、正确性、安全可靠性、性能、测试和构建交付证据。采用风险驱动源码走查、四条独立只读审查线及主审复核，不代表逐行穷尽所有代码或完整产品验收。没有修改实现、启动/使用设备、使用真实服务账号、操作远端或提交代码。

**结论：当前主架构不需要推倒重来，但不能据此宣称已满足全部安全、可靠性和产品成功指标。发现 3 项高优先级实现缺陷、5 项中优先级正确性/性能问题，另有规则重复、产品契约陈旧和指标定义缺口。**以下“确认”指当前源码的控制流或数据流成立；没有把静态确认冒充故障注入、设备复现或实测性能。

P1：应优先处理的安全/副作用真实性问题。P2：明确触发条件下的正确性、扩容成本或验收缺口。设计建议不冒充功能故障。

## 一、实现缺陷

### 1. P1：HTTPS 原始 socket 未启用目标主机名校验

证据：[HttpFetchBridgeImpl.kt:175–202](../../tools/android/src/main/kotlin/com/helix/tools/android/HttpFetchBridgeImpl.kt#L175)，生产装配见 [AppAndroidTools.kt:61–74](../../app/src/main/kotlin/com/helix/app/AppAndroidTools.kt#L61)。

`tlsIfNecessary()` 使用默认 SSLContext 的 socketFactory，将已连接 socket 包装为 TLS，并传入 `endpoint.host`；随后直接取得输出流写请求。没有给实际 SSLSocket 设置 HTTPS endpoint identification，也没有显式 hostname verifier。传入 host 可用于 SNI/会话信息，不等于原始 SSLSocket 自动执行 HTTPS 域名匹配。注释声称证书绑定原 hostname，但实现未建立该保证。

触发：目标连接被导向一个持有其他域名可信证书的服务。可信证书链不等于服务就是请求域名；SSRF/IP 校验也不能替代 TLS 身份校验。影响是接受错误服务的响应，不将其夸大为所有 Provider 凭据都会泄露。

建议：保持已验证地址直连，在发送请求字节之前配置 HTTPS endpoint identification 并完成握手。回归必须包含“可信 CA + 错误 hostname 拒绝”，以及正确 hostname 成功，不能只用自签名证书被拒绝证明身份验证。

验证级别：源码确认；本轮未建立本地 TLS 试验或设备复现。

### 2. P1：重定向和后续连接复用请求开始时的网络权限快照

证据：[HttpFetchBridgeImpl.kt:94–153](../../tools/android/src/main/kotlin/com/helix/tools/android/HttpFetchBridgeImpl.kt#L94)，与生产接线说明 [AppAndroidTools.kt:65–68](../../app/src/main/kotlin/com/helix/app/AppAndroidTools.kt#L65) 不符。

`policyProvider.current()` 只在 `fetch()` 开始调用一次。`fetchHops()`、地址检查、连接 peer 检查和递归重定向均复用该 `policy`，没有重新读取 live profile/scope。

触发：首跳请求等待期间用户撤销 LAN scope 或切回 Standard，随后服务返回指向原先获准 LAN origin 的重定向。下一跳仍可能按旧授权发起新连接。此问题不是要求撤回已经发出的网络字节，而是新副作用未重新确认撤销事实。

建议：每个 hop、新连接前重读实时策略并取原批准边界与 live 授权的交集；不因刷新策略扩大原调用许可。回归以可控首跳阻塞，在撤权后放行重定向，断言第二跳没有建立连接。

验证级别：源码确认；未执行网络故障注入。

### 3. P1：跨 scope 移动的部分副作用被报告为“未移动”

证据：[WorkspaceArtifactStore.kt:620–625](../../core/workspace/src/main/kotlin/com/helix/core/workspace/WorkspaceArtifactStore.kt#L620)、[FilesMoveTool.kt:116–122](../../tools/files/src/main/kotlin/com/helix/tools/files/FilesMoveTool.kt#L116)。

普通 Path 后端跨 scope 移动先 `streamCopyAndHash(source, target)` 原子发布目标，再 `Files.delete(source)`。源删除若抛 IOException，目标已经新增或被覆盖，源却仍存在。工具 catch 将其转成普通失败“the move was not performed”，不设置 `requiresReview`。

结果与真实文件状态相反，后续决策可能错误重试，也无法可靠提示用户核查被覆盖目标。相邻 document rename 路径已用 `WorkspaceMutationUncertain` 表达副作用不确定性，普通跨 scope 分支没有覆盖这一边界。

建议：目标发布之后的失败必须记录部分/不确定结果，返回核查状态和原始两端身份；不能声称事务回滚。加入可注入的源删除失败测试，覆盖新目标和覆盖已有目标，并验证工具结算而非只验证 store 抛异常。

验证级别：源码确认；未执行删除失败注入，没有改用户文件。

### 4. P2：提醒编辑在已落库但调度失败后，同页重试被旧 revision 拒绝

证据：[ReminderService.kt:71–95](../../app/src/main/kotlin/com/helix/app/reminder/ReminderService.kt#L71)、[ReminderScreen.kt:367–386](../../app/src/main/kotlin/com/helix/app/reminder/ReminderScreen.kt#L367)。

`save()` 先递增 revision 并保存，再撤销旧闹钟、撤通知、安排新闹钟/保存调度状态。后半段异常时编辑器只显示 error、保留草稿；再次提交仍带 `original.revision`，必然命中 stale 检查。修改可能已生效，但用户得到失败提示且不能在原编辑页再次保存。

建议：将 durable commit 与调度投影结果分开反馈；失败后按本次提交身份对账、更新编辑基线，不能简单重试旧 revision 或盲目覆盖新数据。覆盖“编辑已有提醒 → commit 后失败 → 同页恢复/重试”。已有新建草案重试不等于此场景通过。

验证级别：源码确认；设备 not requested。

### 5. P2：旧提醒编辑页的完成/取消会结束后来修改的新版本

证据：[ReminderScreen.kt:393–402](../../app/src/main/kotlin/com/helix/app/reminder/ReminderScreen.kt#L393)、[ReminderService.kt:106–131](../../app/src/main/kotlin/com/helix/app/reminder/ReminderService.kt#L106)。

用户打开 revision R 的编辑页，同一提醒被工具或其他入口修改为 R+1。旧页只按 id 调用 `finish()`；服务重读最新记录，直接完成/取消并清除 nextAt。用户针对旧内容的动作因此结束了未见过的新提醒。

mutation 锁只保证串行，不保证所见版本。服务已有 `cancelRevision()` 作为版本检查对照。

建议：人工终结动作也绑定所见 revision；过期时要求重读/确认。测试旧页保存之外的完成、取消两个入口。

验证级别：源码确认；未设备复现。

### 6. P2：并发会话目录刷新允许旧快照覆盖新结果

证据：[SessionDirectory.kt:40–47](../../app/src/main/kotlin/com/helix/app/chat/SessionDirectory.kt#L40)。

`refresh()` 在共享工作 scope launch，`refreshNow()` 无条件执行 `rows.value = readRows()`。没有覆盖读取和发布的串行边界或 generation 检查。一个旧读取暂停在发布前，另一个重命名后的刷新先发布新标题，旧读取再发布，就会把目录退回旧状态，直到后续刷新。

数据库本身未损坏，问题属于读模型 owner 的发布顺序。相邻搜索的取消保护不保护目录刷新。

建议：在目录服务内为所有刷新入口统一小范围串行化，或定义 generation 丢弃过期快照；不要增加全局业务写锁。加入 latch 控制的迟到旧刷新测试。

验证级别：源码竞态确认；本轮未运行控制调度测试。

### 7. P2：知识笔记加载对 origins 元信息产生 N×M 次文件读取/解码

证据：[KnowledgeStore.kt:103–107](../../core/storage/src/main/kotlin/com/helix/core/storage/knowledge/KnowledgeStore.kt#L103)、[KnowledgeStore.kt:411–419](../../core/storage/src/main/kotlin/com/helix/core/storage/knowledge/KnowledgeStore.kt#L411)、[PrivateDocumentStorage.kt:39–51](../../core/storage/src/main/kotlin/com/helix/core/storage/content/PrivateDocumentStorage.kt#L39)。

`noteRecords()` 对每篇笔记调用 `find("origins", note.id)`；每次 find 都重新列出、读取并解码全部 origins。N 篇笔记与 M 条 origins 造成 N×M 次元信息读取/解码，N≈M 时为二次方。单文件/文件数上限及切到 IO 线程都不消除重复读取。

这不是要求立即实施延期的全库分页或新索引系统。一次加载只读一遍 origins 并按 id 关联即可去掉该放大，仍可保留当前文档权威模型。

建议：加载内构建临时 map，避免跨调用失效复杂度；测试用可计数 DocumentStorage 断言读取次数随 N+M 增长。没有设备时延、ANR或耗电数据，不声称已经测得卡顿。

验证级别：读取复杂度源码确认；性能量值未测。

### 8. P2：聊天消息分页未限制完整 Turn 查询和投影，负载 fixture 未覆盖真实多轮历史

证据：[ChatService.kt:4314–4348](../../app/src/main/kotlin/com/helix/app/chat/ChatService.kt#L4314)、[ChatScreenProjection.kt:192–198](../../app/src/main/kotlin/com/helix/app/chat/ChatScreenProjection.kt#L192)及 [261–266](../../app/src/main/kotlin/com/helix/app/chat/ChatScreenProjection.kt#L261)、[SessionProjectionLoadDeviceTest.kt:52–59](../../app/src/androidTest/kotlin/com/helix/app/chat/SessionProjectionLoadDeviceTest.kt#L52)。

一次刷新在 Room transaction 内读取全部会话 Turn，生成完整 `screen.turns`；history presentation、retryTarget 也拉取全部 Turn。消息页大小并没有为这些查询和分配设上界。

现有 1000 消息 fixture 全为 turnId=null 的 USER 消息，不创建 Turn，因此不能检验实际长期多轮会话的这一成本。负载数据看起来很多，不等于命中真实增长维度。

建议：对当前消息页关联 Turn、最新/活跃/重试目标分别使用所需的有界查询；保留正确恢复语义，不能为性能丢弃尚未解决的状态。补充大量真实 Turn、失败、预算诊断和 superseded 历史的 fixture，测查询次数、投影大小及事务时长。

验证级别：无界读取与 fixture 缺口源码确认；未证明具体设备已经达到延迟阈值。

## 二、架构、冗余与未来扩展性

### 已有合理基础，不应按旧状态重开

Core 循环/生命周期 owner、Room 持久权威、应用层展示适配、执行域隔离、权限路径和未知结果不盲目重放是当前主架构的合理基础。QuickJS、PRoot/CLI、MCP/Skill/A2A 不能因为都叫扩展而合并成一个权限域；这些区别有实际平台、安全和执行语义依据。

没有证据支持引入新应用框架、第二套 AgentLoop、泛化执行平台或预先实现远程/多用户产品。未来扩展性应以添加一种受支持能力时，需要修改哪些权威规则、执行边界及测试来衡量，而不是接口数量。

### 中优先级设计改进：准入语义重复解释和全历史扫描

[TurnSubmissionCommandHandler.kt:178–192](../../app/src/main/kotlin/com/helix/app/application/agent/TurnSubmissionCommandHandler.kt#L178) 在应用层自行解释 `!isTerminal && != NEEDS_REVIEW`，[DurableSessionGate.kt:11–19](../../app/src/main/kotlin/com/helix/app/infrastructure/agent/DurableSessionGate.kt#L11) 再解释同一规则，均读取完整会话历史。

这是具体的规则冗余，而不是按类长度判断“代码太多”。未来状态语义改变容易漏改，历史增长增加准入工作量。当前多重检查仍然 fail-closed；**没有确认准入绕过**。

建议复用唯一 blocker 语义，将活跃状态查询约束为所需记录；保留事务内最终权威复检。不能把消除重复与删除 TOCTOU 防护混为一谈。

与本轮性能问题共同反映的变化成本：状态枚举变化会影响多个解释点；历史增长影响读模型和准入；新增文件后端需要一致表达“已经发布但后续失败”；撤权需要各执行域的新副作用入口读取实时事实。这些局部边界比大规模重构更值得先处理。

## 三、产品、竞品与成熟实践

### 1. 当前产品范围文档没有完整反映文档插件

[requirements.md:79](../../docs/product/requirements.md#L79) 仍将 PDF/DOCX 对话读取统一列为未来、当前未支持附件；[competitive-evaluation.md:63](../../docs/product/competitive-evaluation.md#L63) 将 PDF 排除当前通过率。较新的 [文档插件方案:9–18](../../docs/product/document-processing-plugin.md#L9) 明确首版 PDF/DOCX 阅读及限制。

确认的是**当前范围/评测文档不完整**，不是已经证明附件安全拒绝实现错误。直接附件发送与显式启用插件后的受控阅读是不同入口；[requirements.md:446](../../docs/product/requirements.md#L446) 的附件拒绝不能粗暴删除，也不能用插件阅读自动授权整文上传。

建议按格式、入口、API/模型能力列适用性，区分 PDF/DOCX 阅读、旧 DOC/PPT、宏、生成/编辑和上传。否则产品评估会漏掉已交付价值，验收人员也可能使用过时 oracle。

### 2. 成功率指标没有明确冻结任务分母及其映射

[requirements.md:359–360](../../docs/product/requirements.md#L359) 要求固定40条支持场景 E2E≥80%、有效输入确定性工具成功率≥95%。[验收场景:401–447](../../docs/product/requirements.md#L401) 实际混列43条任务、安全拒绝、恢复和未来条件；竞品 C01–C07 又是另一组。

现有固定 Harness suite 可提供具体能力证据，但不能自动证明这一产品指标。缺少40条具名任务与输入、oracle、渠道/能力/provider适用性、尝试次数和失败分类的映射，指标无法独立重算。不能由测试总数或 Turn COMPLETED 推导任务成功率。

建议安全零绕过作为独立硬闸；任务结果统计单列正确产物、覆盖率、人工接管、配置时间和失败重试成本。支持范围之外记不支持/不适用，不能靠缩小分母宣称优胜。

### 3. 默认体验与商业边界：待验证假设，不列为已证实 Bug

[EmptyConversationHint.kt:81–93](../../app/src/main/kotlin/com/helix/app/ui/EmptyConversationHint.kt#L81) 将研究、文档、规划用于普通模式，文件/成果任务较集中于 Goal。此选择可能降低入门复杂度，也可能让本机执行和可核验产物的区别不够直观。没有本轮用户试用数据，不能认定默认示例导致流失。

可验证方向是普通模式提供一个边界小、合成输入、明确文件产物的首任务，与当前示例比较首次正确产物时间和人工介入，而非只看点击率。实施前无需新增长期 Goal 或新框架。

[商业化假设:258–268](../../docs/product/market-users-and-commercialization.md#L258) 将完整 SAF UI、Browser Agent、Git/diff 等列 Pro 候选，同时承诺 Standard 完整。免费与 Standard 并不必然同义，收费也不必然违反完整 Standard；但商业 entitlement、渠道政策、系统权限和运行配置需要正交说明。此处是未来定价决策前的定义缺口，不是要求现在实现计费或删减免费功能。

### 4. 竞品取舍的审查结论

无需电脑、开放模型、手机本机文件/网页执行与可审阅产物，是可解释的目标组合。不能据此证明比手机助手、Android Agent、远程编程客户端或确定性自动化工具更好；这些产品的依赖、授权成本和适用任务不同。

当前竞品文档提供比较对象、假设和方法。本轮未安装/运行竞品、登录账号或进行新的版本实测，因此不发布功能排名、价格/市场份额或最佳体验优胜结论。已有资料只能支撑取舍推理，不能代替当前对照实验。明确排除的支付、完整IDE和远程Worker不计为产品 Bug。

本项目需要的成熟实践并非更多抽象，而是：TLS身份验证、live撤权、部分提交结果真实性、版本化编辑、读模型发布顺序、有界查询，以及可重算的用户任务指标。这些均有本轮具体问题或契约作为依据。

## 四、验证、构建与交付证据

### 本轮实跑

| 检查 | 结果与边界 |
| --- | --- |
| `./scripts/check-all.sh --source` | 通过，exit 0。300 项宿主脚本测试；996 Markdown/297 HXA 文档检查；40 个 ADR；i18n 1284 生产源文件、2844 resource keys；secret scan 通过。 |
| `ANDROID_HOME=… ./scripts/check-all.sh --debug` | 失败，exit 1。Spotless/Detekt 阶段未通过；不能宣称 JVM 全套、双渠道新APK及最终边界通过。 |
| Release/R8/full | 本轮未运行；源码审查保留 gate 不能代替执行通过。 |
| 设备/API29/API36 | not requested；没有启动或使用 emulator/真机。 |
| 真实模型/外部服务/竞品账号 | 未使用。 |

Debug 失败明细：

- 并行工作树的 `KnowledgeNoteEditor.kt:49–50` 行长和格式，Spotless/Detekt 均报告。
- `scripts/local/debug/2026-10-28/credential-analysis/OneTimeCredentialConversionFixtureTest.kt` 被 Detekt 扫入，报告包路径、通配 import 和行长。该文件位于忽略的本地调试目录；本轮未创建、执行、删除或修改它。
- 这说明当前本地 gate 输入包含未提交/忽略文件，不能把失败等同于 HEAD 回归。日志保存在忽略的 `build/audit-2026-10-28-debug.log`。
- 本轮保持只读实现约束，不格式化他人的在途文件、不删除临时文件、不弱化静态门禁。没有在不改变输入的情况下盲目重跑，也没有拿失败构建留下的旧 APK 作为新候选证据。

### 工程保障中值得保留的部分

CI 的 source/debug/full 分类、可信完整基线、未知/API失败保守 full、始终汇总失败/取消/跳过、固定 action commit、锁定资产与缓存命中重新验证，是合理的工程措施。当前 GitHub workflow 保持 host-only。`normalize_junit` 拒绝空 suite，未将疑似空评测假通过列为发现。

测试数量、编译通过和静态架构 guard 不证明真实执行/隔离/用户成果。当前 source 全绿仍未发现本报告的 TLS、部分移动和重定向撤权问题，说明需要针对安全性质和故障边界的行为测试，而非仅增加枚举或固定值断言。

供应链有锁、哈希校验、限定仓库和第三方 notices 基础；本轮没有逐个二进制逆向、完整 SBOM/license复核、签名发布验证。不能把未发现问题写成发行供应链全面验收。

## 五、处置顺序与报告性质

1. 优先修复 HTTPS 身份校验、网络撤权后的新连接、移动部分副作用结算，并加入可控失败回归。
2. 接着处理提醒版本/投影失败、目录发布顺序。
3. 消除知识元信息重复读取、补真实 Turn 负载覆盖及有界投影，复用准入状态语义。
4. 同步产品范围与可重算指标；默认体验和商业边界保持为待验证假设，不凭审查擅自改产品路线。

这是时间点审查，不是新 HXA、实施授权或长期 ADR。所有问题均未在本轮修复，当前工作树修改归原工作线；没有 commit/push。后续实施应逐项复核当时源码，并把采用的契约变化写回所属 ADR/HXA，而非维护第二份长期路线图。
