# 会话应用状态职责与设备回归（2026-10-10）

HXA-257，起点 `0ac99b71`。所有者要求继续架构/历史测试维护，逐项提交并允许自有模拟器/现有模型验证。当前切片没有改变 Engine/Core、预算或授权契约，没有安装日常 APK 或操作真实手机。

## 状态与调用归属

SessionDirectoryService 持有会话行、检索状态、200ms 防抖和搜索 Job，IO/当前查询语义保持；取消之后即使旧后端继续返回，也必须在同步发布边界 ensureActive。历史页面接 SessionDirectory 与 SessionHistoryActions，归档/恢复/重命名仍经原应用入口，不直接访问持久实现。历史页和仅被它使用的列表迁到 chat/ui；共享导航、标签和对话框仍保留共享 UI 归属。

ComposerInputState 持有同一 ChatDraftStore、分享预填、附件不可变快照与同一准入锁。导入容量、迟到会话核对、恢复、移除及接受回执消费一起迁移；恢复复制输入列表，消息编辑恢复在锁内再次核对会话。Facade 的 getter 是兼容投影，不持第二份状态。原 SessionActionQueue、正式消息/回执和 Room Queue/Steer 事实不变。

TaskDashboardProjection 持有后台任务、PRoot Job、前台运输、Goal、计划和产物六类 Flow；查询、50 个产物上限、原 Job 动作刷新和 turnGate 保持。延迟创建用于保留原初始化依赖次序，不额外启动观察者、模型循环或执行 Job。ChatService KDoc 改为当前真实 Engine 权威；本轮不是把剩余所有应用协调搬进 Engine，也不以行数推导性能。

依据 accepted ADR-AGENT-001 并追加 Decision history。采用[Android 状态持有者/UDF](https://developer.android.com/topic/architecture/recommendations)、[OpenCode Client 与持久准入边界](https://raw.githubusercontent.com/anomalyco/opencode/dev/AGENTS.md)、[RikkaHub 会话状态集中管理](https://raw.githubusercontent.com/rikkahub/rikkahub/master/app/src/main/java/me/rerere/rikkahub/service/ConversationSessionManager.kt)的职责原则；Room/权限/取消边界以 Helix 为准，未复制竞品代码或引入新依赖框架。

## 主机

`python3 scripts/with-host-slot.py -- ./gradlew spotlessApply test detekt spotlessCheck lintConsumerDebug lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`。

首次文件归属调整缺少共享 UiLabels import，编译失败，修复后 chat-owner-host-r2.log exit=0：993 tasks/62 executed，1m44s。新增 JVM 每渠道6/6，skip=0：并发容量、跨会话迟到/恢复、快照与旧回执；即时精确查询/界限字段、非合作旧扫描取消、清空取消。既有全仓 JVM/静态、双渠道 Debug lint/App/AndroidTest passed；既有每渠道4项可选真实 Connector 缺输入仍 skip。

旧预算设备断言适配后两次 LongMethod 失败均保留；提取批次响应构造与计账断言辅助，不增加 suppress、baseline 或跳过测试。最后 chat-owner-contract-host-r3.log exit=0：993 tasks/33 executed，45s，全仓 JVM/静态、双渠道 lint/App/AndroidTest passed。Source gates 保持153项及文档/ADR/i18n/秘密检查，chat-owner-final-source.log passed。

## 继承失败的基线与契约

初次 API36 Consumer5636 **55/62 passed**，Developer5638 **56/62 passed**；两者6项附件 E2E 预算失败相同，Consumer 另有一次独立 Pinyin 候选等待超时（预编辑误分为已提交 ni 与 hao）。保留原始 instrumentation、截图诊断和关闭记录。

用拆分前冻结的 HXA-256 APK/Test APK，Consumer5640 单独验证相同6项和Pinyin：**1/7 passed**，6项同样失败，Pinyin passed。制品来自 fixture-api36-consumer-recovery-r7，证明这6项来自既有契约漂移；不能把独立 IME 偶发失败反推为架构根因。

按照所有者明确批准的新 DSH 契约更新6处测试名/断言；所有测试仍保留。人工首轮加一自动轮的夹具配置 maxGoalRounds=1，并供给全部有限脚本响应，检查精确轮数/停止原因；累计模型/工具数量不拒绝已获准调用，每个批次槽和结果必须结算；旧 wake duration 不停流，显式 Stop 仍结算原用量、时间和预留；请求输出不扣累计输入，实际超长响应必须 OUTPUT_TOKEN_LIMIT。附件确认用例同时等待有限后续轮终结并核对所有原 Goal 归属，避免瞬间 PAUSED 被误当全部完成。

本轮没有修改生产预算。r2 Consumer5642 **62/64 passed**，剩两项是测试夹具漏配响应（INTERNAL 源自 scripted wire 无响应）和错误预期 run.outcome=CANCELLED；实际用户停止语义为 USER_PAUSED，Turn 本身仍 CANCELLED。修正精确输入和持久 outcome，保持失败分类/暂停/无重放断言。r2 Developer5644 同为62/64。r3 Consumer5646、Developer5648 各63/64；剩一项均为拼音预编辑拆分，所有预算、附件、搜索、草稿、Dashboard与两项实际256轮/取消边界通过。未把这些失败轮称为一次全绿。

## 验证边界

r4 Consumer5650、Developer5652 各3/3：Pinyin1、原fixture归属2。测试输入在捕获首次失败后仅允许一次有界重试：真实Delete键清掉IME预编辑和已提交Latin文本，再逐键输入；精确“你好”候选/commit、两输入框和零发送断言保留，不注入中文。Developer实际使用一次retry、Consumer直接通过，原诊断/截图仍保留。最后chat-owner-ime-host.log exit=0，993 tasks/32 executed，44s；source gates见chat-owner-ime-source.log，153及其他门禁passed。此为测试输入稳态处理，不称生产键盘/OEM根因已修。

r4随后每渠道composer seed/verify各1、revision seed/verify各1、TaskJourney真实setup kill/verify1、SessionSearch真实setup kill/verify1通过。Consumer正常composer PID4018→4352、编辑4936→5336；Developer4098→4364、5104→5395；取消恢复INTERRUPTED/原UNKNOWN调用身份/新执行与新模型次数0，重复恢复不变。Consumer Goal删除control1、提交前5725与提交后5776两次SIGKILL、recover-final1通过，Developer不冒充执行这两次kill。两runner及closed exit=0。记录在chat-owner-api36-{consumer,developer}-ime-recovery-r4。

最终App/Test SHA256：Consumer `940c288e770a3d86886085e89146f04b16aad181e082acd8115dbef9900f10d8` / `f5b3b736672d79574a23688e6030baf94355f25f8a4747faf4247b086c418638`；Developer `98149f73a506da143c16d53f35f98a2596ce1ca33e80ac82b8cb7c740fa051b7` / `c5e0f34e90bf0511d004314e31a8a4f626b6c235b09114be4548fcfe9e0926ac`。最终主APK与r3相同；测试APK之后只修改独立Pinyin夹具，其他63项未改。不称最终APK一次全矩阵64/64；如需全量同次验收，应使用该8类选择和全新的owned输出。

复现入口为稳定scripts/run-owned-emulator.py，avd=Helix_Main_Verify_API36、raw-results、4GiB、对应渠道APK/Test/runner、当轮明确授权且空闲port、新build输出。r3 classes=PinyinComposer/SessionSearch/SessionDraft/ShareDraftUi/TasksDashboard（com.helix.app.ui）、AttachmentE2e/ChatServiceAttachmentRetry/GoalRoundAllowance（com.helix.app.chat）；r4 classes=PinyinComposer与com.helix.app.test.TurnLifecycleFixtureDeviceTest。恢复使用原日期composer/revision脚本的seed+实际PID kill/verify协议、稳定run-goal-deletion-process-kill.py，以及TaskJourney/SessionSearch精确单方法recoveryPhase=setup/verify；不能用常规单次JUnit冒充进程死亡。

本切片交付完成。自有 API36 arm64-v8a、4GiB、read-only/no-snapshot，实际拼音及真实 PID 恢复为验收条件；日常5554未借用/覆盖/清数据。脚本 SSE 使用生产适配器与真实 Room，不等于真实模型；本切片未消费外部账号或真实模型额度。API29、OEM、长稳及实体手机卡顿/黑屏独立开放，结构变化不证明性能改善。
