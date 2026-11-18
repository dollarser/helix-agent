# HXA-214/215 对话输入整合边界

HXA-214 核心、普通 composer UI 与 HXA-215 会话内修订现已在 `codex/conversation-convergence` 收敛。本文件保留 2026-09-22 时点的核心接口和整合不变式，不作为当前 API 或 schema 规范，最终交付与验证以 [HXA-214 完成记录](../../completion-records/HXA-214.md)、[HXA-215 完成记录](../../completion-records/HXA-215.md)及[联合证据](conversation-convergence-2026-09-22.md)为准。

## 核心接口

- `ChatSubmission` 捕获 sessionId、revision、clientRequestId、文本、附件身份和可选修订目标。编辑创建新 revision 和 request ID；发送期间不改写已提交快照。
- `sendSubmission` 返回服务拥有的 Deferred 回执，结果区分 Accepted、PendingConfirmation、Rejected。UI 等待取消不等于取消已发出的提交，不自动停止普通 Turn，也不增加队列。
- `confirmSubmission` 和 `cancelSubmission` 绑定原始快照，拒绝旧版本或跨会话操作。已接收请求只从持久 Turn 查询回执，不重发。
- `saveComposerDraft` / `loadComposerDraft` 使用 expectedRevision 做 CAS；相同快照重复保存幂等，非法版本递增或旧写入不覆盖新稿。Accepted 只清除与持久 Turn、revision 和 clientRequestId 全部匹配的草稿。
- `stopTurn(turnId)` 是聊天、Tasks 和 AgentRuntime 的稳定停止入口。先持久 CANCELLING 再发取消信号，终局竞争返回真实 AlreadyTerminal；独立 Job/PTY 不随普通 Turn 批量取消。

## 普通 composer 整合

1. `ConversationDraftBuffer` 按会话保存不可变输入意图；500ms 防抖、生命周期 flush、显式发送和附件同步共享同一互斥边界。挂起自动保存完成后，发送复用同一 intent 的已持久快照，不以 revision 差异重复写入相同 request ID。
2. 文本或附件的真实用户修改立即生成新 clientRequestId。旧回执只能确认自身快照，不能清除切会话后的输入或同会话的新编辑。
3. 旋转或普通主进程恢复先查询持久回执，再恢复仍存在的当前附件。服务提交锁串行确认副作用，输入区锁串行回执结算与附件同步；ack、重载和本地状态更新使用短的不可取消临界段。
4. 发送中允许编辑下一份草稿，但 send 仍受 busy gate 限制；HXA-216 之前不启用 Queue/Steer，也不改变 Goal 新输入语义。
5. 附件从 artifact 元数据重建并重新校验 hash、类型和尺寸；缺失附件保留文本并提示，不把显示名称当作可发送证明。

## 与 HXA-215 修订整合

- Room v25 保留 `composer_drafts.revisedMessageId` 和 `messages.supersededBy`。非空修订目标只交由 `MessageRevisionDialog` 与修订回执处理；普通 composer 不覆盖、清除或重生成其身份。
- 修订编辑器保存后可主动关闭、返回列表并从同一消息重开；普通 composer 的生命周期 flush 对保留修订稿无害。应用重启默认恢复未关闭的修订编辑器。
- 修订确认保留原附件并在发送时重新检查。已接收回执由修订编辑器确认；确认与 editor 关闭是短的不可取消本地结算，避免 `screen.messages` 更新取消 effect 后留下已清稿但未关闭的对话框。
- 原消息、旧 Turn、工具/用量/审计仍保留；替代后缀不再进入有效历史、搜索、分页或压缩输入。更早历史编辑继续通过 HXA-213 的显式 fork，不在本轮改为原会话重写。

## Runner 与验收边界

`scripts/accept-conversation-interaction.py` 保留独立 `--scope 214`、`--scope 215`，并以 `--scope both` 动态发现当前方法集合。联合 scope 去重共享类，额外包含市场 UI 回归；每个 flavor/API/阶段新建独占模拟器进程，普通恢复使用 seed、SIGKILL 主 PID、verify 两阶段。存储迁移和 JSONL 使用独立 storage runner。

联合验收覆盖回执拒收与 CAS、会话/旋转/进程恢复、附件恢复与修订归属、稳定 Turn 停止及 CANCELLING 竞争、实际 loopback 请求、Goal/审批/fork/压缩回归和市场 UI。最终数字、APK hash、PID 变化、失败尝试取舍和远端 CI 见完成记录与联合证据。

loopback Provider 不是真实外部账号；模拟器 host 的 SIGKILL 权限不等于 App Root 或物理 OEM 验收。196/199 物理专项、125/126 真实服务账号、订阅实付、发行签名和渠道验收仍按各自任务保留。HXA-216 的队列/转向在独立分支实施，不把本轮接口存在写成该功能已交付。

<a id="history-hxa-214-core-2026-09-22"></a>

## HXA-214 核心切片验证

日期：2026-09-22。基线 `645fa680`，独立分支 `codex/hxa-214-core`；只验证本次核心切片，不关闭 HXA-214。接口及 UI 接线边界见[交接说明](../../evidence/development/hxa-214-core-integration-boundary-2026-09-22.md)。

### 改动与基线

- 复用现有 Turn 的 clientRequestId 与输入 fingerprint，提交返回 Accepted / PendingConfirmation / Rejected。确认与取消确认绑定原始快照；拒绝、旧回执不清空新稿。
- 原 ChatDraftStore 只存瞬态新会话配置/附件，没有持久 composer 正文，因此必要地新增 Room v24 的 composer_drafts；一会话一条，带 revision CAS，硬删除会话时级联清理。草稿不进入消息历史、模型上下文或 JSONL 导出。
- 聊天、Tasks 与 AppAgentRuntime 的停止汇合到具体 Turn ID；CANCELLING 先提交，再通知活循环取消，结束竞争返回真实终态。保持现有 Goal 让位语义与 Job/PTY 所有权。
- 起点主机与四象限基线见[分支收敛记录](branch-integration-2026-09-22.md)。本次初跑发现并修复旧页面附件拒绝后 shareDraftText 恢复回退；其后补齐重复确认的持久回执恢复。保留失败日志，不作为最终通过证据。

### 验证命令

重型命令通过 `scripts/debug/2026-09-18/with-host-slot.py` 串行，环境 `GRADLE_OPTS='-Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false -Dorg.gradle.daemon=false'`，JDK17 与本地 Android SDK。执行顺序：

```bash
./gradlew spotlessApply
./scripts/check-all.sh --all
./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest :core:storage:testDebugUnitTest :core:storage:assembleDebugAndroidTest
python3 scripts/debug/2026-09-22/accept-hxa214-core.py --output build/hxa214/devices-6
python3 scripts/debug/2026-09-22/accept-hxa214-storage.py --output build/hxa214/storage-devices-2
```

统一日志 `build/hxa214/full-6.log`。设备 runner 每组新建独占实例，固定执行前的方法清单和测试源码 hash；保留 owner/closed、APK hash、逐方法 TestRunner 事件及收集结果，不复用他人的模拟器。存储模块本身不区分 flavor，独立执行 API29/36 两组，不将它计为四象限应用验收。

### 结果

主机全量门禁通过（静态分析/Lint、JVM 测试、四 APK 及变体/Runtime 边界）；附加测试 APK 构建与 storage JVM 通过，整条命令 exit=0。文档收口后追加 `./scripts/check-all.sh --source` 与 `git diff --check` 通过。

| 设备批次 | 通过 / 预期 | 失败 / 跳过 |
| --- | --- | --- |
| 应用 consumer API29 | 40 / 40 | 0 / 0 |
| 应用 consumer API36 | 40 / 40 | 0 / 0 |
| 应用 developer API29 | 40 / 40 | 0 / 0 |
| 应用 developer API36 | 40 / 40 | 0 / 0 |
| Room API29 | 36 / 36 | 0 / 0 |
| Room API36 | 36 / 36 | 0 / 0 |

全部六组 `DEVICE_BATCH_PASS`，无不完整用例；owner PID 与 closed PID 匹配，均已退出。完整分组结果在上述两个输出目录的 `batches.json` 与各组 `*-report.json`，未将无设备执行计为通过。

应用矩阵每组执行 40 项：新增 3 项提交/确认回执、2 项草稿存储恢复，加上已有附件、Stop 页面、Tasks 与 Goal 回归；160 项不是 160 个新增用例。JVM 新增结束赢得停止竞争的返回值测试。Room 矩阵覆盖完整迁移 fixture，包含本次 v23→v24 与代码/导出 schema 一致性。

实际设备 APK SHA-256（双 API 使用同一 flavor 的制品，runner 留存副本并复核）：

| 制品 | SHA-256 |
| --- | --- |
| consumer debug | `f80ce715b88219ece57046df61da492be83aebf44fd479d0964dd0aa22452e8c` |
| consumer androidTest | `f509d29fbd7f1cf625be2a72754bb9266951e60ff59a58548a8bd179384ed3d5` |
| developer debug | `806c0c9e508876f56db87cda5c3d12b68d2152302e0f2305c2f30084dfac70f8` |
| developer androidTest | `9686d9eedd04a2fe3ce35d1850a56632320855a7c1c8cbfe9dcef33a9fcef7cb` |
| storage androidTest | `5c197ba3c38fd6d1dda0dcc5170fe99294086643f9e55cef70a01645ef80d2e9` |

### 未闭合边界

- ChatScreen 尚未消费 typed receipt、持久编辑版本或去掉立即清空输入；旧入口保持兼容。UI、三语提示、运行中编辑和渲染时 Turn ID 接线仍待完成。
- ConversationDraftRecoveryDeviceTest 当前验证数据库关闭重开，不是普通应用主进程死亡；附件 ID 的存储恢复也不等同重新挂载附件的产品旅程。
- 完整任务要求的两个新增产品测试类、scope214 runner、普通应用 seed/kill/recover 留给后续 UI 切片。当前 core runner 不能替代完整任务的验收入口。
- 无真实模型/外部账号、物理 OEM/Doze 验收；不实现 Queue/Steer，不新增权限或 Agent Tool。
