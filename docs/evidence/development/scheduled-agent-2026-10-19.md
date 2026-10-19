# 最小定时 Agent 生命周期（2026-10-19 UTC）

范围：[HXA-314](../../development/tasks/HXA-314.md)、[ADR-GOAL-001](../../adr/goal/001-lifecycle-and-completion.md)。起点 `520755b5`；Owner“帮我完成上述工作”已包含最小定时 Agent，真机手测延后不取消开发。不启动整套 Harness 重构，不 push/发布，保留数据、设置和版本。

## 实际行为与边界

- 提醒页新增独立定时 Agent 入口。必须明确选择已有结果会话，输入任务、固定 IANA 时区和一次/日/周/月规则，核对模型/目标、会话上下文与工作区范围后保存；默认不启用，没有自动创建真实用户任务。规则本地保存不等于模型本地运行，界面说明会话当时历史、现有上下文和读取结果可发送至所选模型。订阅 CLI 不在首版范围。
- 首版 PLAN 只读分析/检索，复用原 AgentRuntime、schema/policy/权限/审批/限额/审计；原闭合元数据操作仍按既有模式准入，不授予文件写入或外部副作用。`directUserRequest=false`，不因定时事件创建新的人类请求/Goal 权限。无新账号、服务、依赖或框架。
- 单独 `scheduled-agents.db` schema 1 保存规则/revision、发生三元组 `(ruleId, revision, dueAt)`、冻结规则/授权/结果会话及每次新 attempt。Worker 只携带三元组，不能凭 metadata 扩权。并发、过时、提前、错过或忙会话不重复启动。一次日历结束后停用，释放 32 条启用名额；历史发生/attempt 保留。
- 授权快照绑定 Provider/模型/连接目标、工作区身份/版本、权限配置和工具持久绑定。每次实际模型请求（包括压缩）再核对当前范围、真实 request 的模型/工具及已记录工作区。Core 只新增可选请求前检查点，保持 Provider 实例及既有普通请求重试语义。进程内工具 incarnation 不写入持久身份；实际执行继续经过正常精确绑定。配置漂移或删除均暂停未来执行并记录失败，需重新确认。
- WorkManager 仅近似唤醒，不申请精确闹钟或新前台服务。超过 15 分钟合并记录错过、推进下个原日历时间，无补跑风暴；早到用当前 Worker 后继投影，不替换正在运行的 Worker。暂停/编辑只影响未来，停止本次沿原 Turn ID，真实 executor 退出前不释放物理容量。审批等待计入规则确认的运行期限（初始 120 秒可调整）；每次模型请求输出最多 2048 token，没有宣称累计模型调用次数上限。
- 启动恢复先等原 Turn/Goal 恢复结束。ADMITTING/RUNNING/WAITING/UNKNOWN 重新查询实际 Turn、审批及未决效果；缺失 ACK 用稳定 clientRequestId 查原执行，不再次 submit。已有 Turn ID 但原 Turn 缺失保持未知/需核对，不能当作从未执行。取消、超时、死亡/中断不自动重试；显式重试新建 attempt，沿原发生/授权/会话，活动执行、NEEDS_REVIEW、未决效果和范围变化仍门控。
- 提醒通知/条目打开请求优先离开定时页面，避免原提醒跳转被页面状态遮挡。失败、保存后投影失败和原记录事实分开；没有 catch-all 成功回执或 placeholder。

参考 n8n 的保存/启用、固定时区、默认跳过错过发生，以及 Android WorkManager 的最小延迟约束；官方链接及采用边界见 ADR，不复制参考实现。

## 主机验证

最终四段串行主机门禁全部通过，日志与逐阶段源码一致性核对见 `build/scheduled-host-serial.log`：

| 阶段 | 实际结果 |
| --- | --- |
| spotless/detekt、双渠道 JVM/Debug/AndroidTest、Storage/Agent JVM | 通过，28s；Standard 1320、Advanced 1455 项，各四项既有外部 profile 跳过；Storage 242、Agent 295，零失败/错误 |
| 双渠道 Debug lint | 通过，1m 50s |
| Standard R8 Release | 通过，1m 4s |
| Advanced R8 Release | 通过，1m 9s |

新增 Core 检查点单测两项：普通和压缩请求都在真正 stream 前核对，拒绝时无 Provider 调用。

冻结源码的首个最终 Debug/AndroidTest、spotless/detekt 命令成功（`build/scheduled-debug-final.log`，23 秒）。双渠道安装均使用该成功候选，未用失败构建留下的 APK。首次完整命令在 4 GiB 长驻 Gradle JVM 并行 lint/desugar/R8 时出现明确 `Java heap space`，Consumer desugar 和 Advanced R8 失败；保留 `build/scheduled-agent/history/parallel-heap-failure.log`，随后停止该失败构建。没有删弱测试或抑制 lint；改为本机新 6 GiB JVM、最多两个 worker、禁项目并行，JVM/Debug、lint、Standard R8、Advanced R8 各自串行且独立进程，逐阶段核对相同源码/测试摘要。结果及日志在 `build/scheduled-agent/host/`，运行摘要 `build/scheduled-host-serial.log`。

此前修复了可空时间断言、请求 budget 跨模块 smart cast 及格式器无法自动断行的嵌套表达式；暂时格式诊断标记已经移除，最终完整规则通过。最早一轮 20 项/首个模型成功只算迭代证据；最终新增数为 24，含后续名额、缺失 Turn、提醒跳转及长任务确认页滚动/取消修复。

最后复核补上取消清理的并发投影：收到取消但尚未完成清理的 producer 仍拥有原执行，刷新不能覆盖 STOP_REQUESTED。物理退出测试在退出屏障前主动刷新，断言停止意图和容量等待保持；最终同源四段主机和双渠道 24 项均已重跑。长重试任务的目标/范围预览也可滚动，四项 UI 测试覆盖真实滚动与取消无第二次执行。

## 最终设备与真实模型

指定日常 emulator-5554 / API36 / ARM64，串行安装普通 Debug，校验实际安装包哈希与留存候选一致。隔离数据库/工作区，只有合成材料，未清数据、修改系统时间或触发真实通知、录音/外放。

| 渠道 | 新生命周期/Runtime/UI/系统投影 | 知识库/问答/提醒回归 | 自然语言提醒回归 | 内置 Qwen 定时合成任务 |
| --- | --- | --- | --- | --- |
| Standard | 24/24，17.902 秒 | 50/50，43.369 秒 | 10/10，13.228 秒 | 1/1，5.518 秒 |
| Advanced | 24/24，18.25 秒 | 50/50，45.324 秒 | 10/10，11.856 秒 | 1/1，6.26 秒 |

新增覆盖确认前无效果、未选会话不能保存、启用确认/过时确认、并发重复和过时/提前时钟、错过合并、绑定会话忙、暂停/编辑不改当前快照、范围变化/配置删除、停用后名额释放、停止等待物理退出、超时、丢 ACK 查原 Turn、启动恢复账本核查、未知效果禁止重试、解决后新 attempt、投影失败保持持久事实、原结果会话、正常只读工具回填/审计、越权写不执行、真实 WorkManager 投影保持一份并只取消本测试未来项，以及提醒入口优先跳转、长任务重试时可滚动核对原模型目标且取消不执行。

真实模型用现有内置 Qwen3.8-27B 与全新隔离会话/工作区，只注册安全 `time.now` 工具，无私人记忆、会话历史、文件或新凭据。任务提供合成负责人 TEAM_SYNTH_42、137 与 42；模型必须调用实际工具，再在正文报告负责人、179 并逐字复制工具返回的完整 utcIso。断言 ToolResult verified、至少两个真实模型调用、正常 Dispatcher 审计、正文实际消费精确工具值、结果只进入原 bound 会话且 foreground 会话无 Turn。定向摘要在 `model-evidence.log`，不存私人内容或密钥，没有付费 fallback。

Standard 偏好字节（排除进程诊断）与初始基线一致；Advanced 最后一轮初始全文件比较发现 helix-ui.xml 摘要变化，原基线只有整文件摘要，无法追溯确切旧键值，不能声称整轮偏好逐字节未变。未覆盖/恢复任何用户设置，也没有发现合成夹具标记写入模型选择/会话目标/安全设置。保留初始差异，随后对当前全部偏好逐键取摘要，按相同候选重跑全部 24+50+10 项与真实模型 1 项，每个阶段均无用户设置键值变化；可复现差异仅为五个固定订阅来源的本地就绪状态 UNAVAILABLE→LOGGED_OUT，login revision 保持空，由现有 ProviderService/SubscriptionProviderModule 的本地 IPC 探测更新，不是凭据或用户选择。所有其他键精确比较，其他偏好文件仍逐字节相同；未调用订阅服务推理或登录。通知/麦克风授予、精确闹钟 app-op、时区和 IME 一致。详细复验在 build/scheduled-agent/evidence/advanced-preference-revalidation/，原差异在 history/advanced-original-preference-delta/；新基线不能倒推原变化原因。测试 APK 已移除，普通应用保留。fixture 数据库/文件清理；只取消本测试 UUID 的未来 WorkManager 投影。安装、测试及设置证据在 `build/scheduled-agent/devices/emulator-5554/`。

## 验收限制与产物

最终 `scripts/check-all.sh --source` exit 0，283 项主机脚本、954 文档/297 HXA、40 ADR、2631 国际化键及秘密扫描通过；结果在 `build/scheduled-source-final.log`。门禁负向夹具中的 FAIL/INCONCLUSIVE 是预期输出，不是本轮设备失败。

真实模型测试通过服务入口输入合成到期事件，系统测试单独验证真实 WorkManager 持久投影/去重/取消；没有把二者描述成休眠后 OS Worker→联网模型的一项端到端验收。恢复采用预置持久状态的核查夹具，并非物理断电或正在联网模型的强杀重建矩阵。没有测试任意 ACT 写入、订阅 CLI、长稳、其他 API/OEM、Release 运行或真人通知/听感。OnePlus 手测 pending，Owner 已选择稍后。

最终六份 APK 在 `build/scheduled-agent/deliverables/Helix-{Standard,Advanced}-{debug,test,release-unsigned}.apk`；成功构建的源码/测试/JVM/产物摘要在 `build/scheduled-agent/evidence/provenance.json`。Debug 安装包与最终归档字节一致；未签名 Release 只证明 R8 构建，不冒充设备运行验收。版本仍 0.0.6 / code 1，工具声明版本 1，不发布或推送。

## 审查补充：规则预算与真实授权变更

基线提交 fd28cc76。审查指出原两分钟是实现自行选择，不能当作用户接受的固定产品上限。本轮改为规则编辑页的最大运行时长，初始 120 秒可编辑，确认页显示实际值。预算保存在规则已有 scopeJson 中、参与 scopeHash 并冻结在 occurrence.ruleJson，Room schema 1 不改表。原缺少字段的历史固定默认确认仍解释为原 120 秒，不删除来源、会话或规则。

Service 的计时与每次实际模型请求的剩余授权窗口均读取原发生项预算。编辑只产生未来 revision，不能延长运行中或原次重试的旧预算。用户输入按精确正毫秒验证，支持更长时长和小数秒；拒绝零、负数、非数字、不可精确表示及 Long 表示溢出，没有新增任意业务上限。

真实集成测试在第一次正常 time.now 执行完成后，用同一隔离会话的真实 Room 配置 API 改变权限预设或工具 SESSION 资格，随后由同一 AgentLoop 建立第二个模型请求；断言失败、Provider Wire 调用仍只有一次，第二次未出网。不是替换一个假的 scopeHash，也不是只手动调用校验函数。另补超过两分钟窗口仍受本规则预算允许、预算到期拒绝、授权预算篡改、旧默认数据、运行中编辑与实际编辑页确认/无效值测试。

补充候选沿用新 6 GiB JVM、最多两个 worker、禁项目并行的四段主机门禁，全部通过且逐段源码/测试输入一致：spotless/detekt、双渠道 JVM/Debug/AndroidTest、双渠道 lint、双渠道 R8。Standard JVM 1323、Advanced 1458（各四项既有外部 profile 跳过），Storage 242、Agent 295，零失败。`scripts/check-all.sh --source` exit 0，283 主机脚本、954 文档/297 HXA、40 ADR、2633 国际化键及秘密扫描通过。日志 `build/scheduled-budget/host/`、`build/scheduled-budget-host-serial.log`、`build/scheduled-budget-source-final.log`。

设备使用相同源码构建与安装候选，新增数改为 30（生命周期 16、Runtime 8、UI 5、投影 1）。权限和工具资格漂移各自断言 Core 已记录第二次模型请求，但 Provider Wire 仍仅一次；第二次被本次原授权拒绝。超过两分钟测试通过合成原 attempt 时间验证三分钟仍允许、超过五分钟拒绝，并非实际等待三分钟或长稳验收。

Standard 首轮旧知识库回归第 13 项进程原生崩溃：API36 `libhwui.so / BaseRenderNodeAnimator::pushStaging`，RenderThread SIGSEGV 空指针，未出现 Java 断言失败。完整退出/崩溃记录与失败 instrumentation 保留在本轮 consumer 目录（`crash-buffer.log`、`exit-info.log`、`integration-previous.log`）。未改产品或弱化测试、未清数据或改系统动画设置；同候选触发单项复验 1/1，通过后完整回归 50/50。只能确认本轮复验未复现，不能声称修复系统渲染根因。

普通 [CoroutineWorker 平台执行窗口](https://developer.android.com/reference/androidx/work/CoroutineWorker)与规则最大值分开，系统可更早中断；没有把平台十分钟窗口另设为产品上限，也不声称本轮实现了十分钟以上持续后台或长稳验收。


| 补充候选渠道 | 新增定时 Agent | 知识库/问答/提醒 | 自然语言提醒 | 真实 Qwen 合成任务 |
| --- | --- | --- | --- | --- |
| Standard | 30/30，22.570 秒 | 50/50，38.794 秒（崩溃后同候选完整重跑） | 10/10，12.554 秒 | 1/1，5.507 秒 |
| Advanced | 30/30，26.273 秒 | 50/50，43.811 秒 | 10/10，12.082 秒 | 1/1，5.791 秒 |

本轮两渠道全部用户偏好逐键值、其他偏好文件字节均与本轮基线一致（排除进程诊断）；没有发生五项就绪缓存变化。通知/麦克风授予、精确闹钟访问、时区与 IME 一致。测试 APK 已移除、普通应用保留；未清数据、录音、播放或使用真机。旧基线的不确定性仍保留，不由此次精确比较倒推消除。

本轮实际安装及测试 APK 与最终归档逐一校验相同，六份 APK 在 `build/scheduled-budget/deliverables/Helix-{Standard,Advanced}-{debug,test,release-unsigned}.apk`；构建源码/测试摘要、JVM 结果和产物摘要在 `build/scheduled-budget/evidence/provenance.json`，设备原始日志/设置比较在 `build/scheduled-budget/devices/emulator-5554/`。版本 0.0.6/code 1/工具声明 1 保持，未 push/发布。没有新增外部账号或凭据；OnePlus 真人手测依 Owner 选择延后。
