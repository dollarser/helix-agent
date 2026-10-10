# 测试生命周期夹具与生产桥隔离（2026-10-10）

HXA-256，起点 `045be56e`。所有者要求继续完成架构/测试维护审查，逐项提交，允许自有模拟器及现有模型验证。当前切片不改变生产预算、授权、Room schema 或 Engine 执行身份。

## 实施与测试保留

32 个设备测试文件的原 163 个测试保留。三个 deprecated TurnCoordinator 桥移入 AndroidTest；测试结算、review 和终态事务回调经真实 TurnEngine，构造归属保留原 Room、可变时钟和 ID 闭包。未绑定 coordinator 必须失败，不猜全局容器。生产 TurnSettlement 的无调用包装移除，其实际事务实现保留。新增两项设备回归验证原时钟/ID 以及未注册无副作用失败。

依据 accepted ADR-AGENT-001 单一 Engine、持久状态与取消语义；采用[审查](../../research/topics/architecture-and-test-maintenance-audit-2026-10-10.md)中的 Codex 真实实现测试组织原则，未复制竞品代码。用 WeakHashMap 保存测试构造归属，不存执行状态、Job 或新授权。

旧预算用例按所有者已批准的 DSH 契约改名和更新：累计用量恢复结算一次，重复恢复不重复结算，PAUSED 可启动新轮；取消和原用量仍核对。Goal continuation 首次自动轮为 `1/maxGoalRounds`，人工首轮不计；负断言改为检查新格式前缀，避免格式变化让旧负断言失效。未删除测试或降低 256 轮边界。

旧 UI 模型夹具曾继承其他测试的 128 输出上限，诊断明确为 OUTPUT_TOKEN_LIMIT；改为独立有限默认预算、等待配置持久状态，结束恢复原预算。真实压缩夹具明确 8192 输出上限，仍核对三次摘要、原约束、无消息删除和无 pending 请求。直接 Room seed 后通过正常 openSession 刷新同会话，再等待界面 idle 与同 Engine 无 active execution；不将缓存界面的拒绝入口误判为模型请求已发出。

原始 instrumentation 的 `sendStatus(0, {stream})` 是诊断输出而非测试。解析器只忽略这一精确组合，不制造 pass；缺 class/test、未结束、diagnostic-only、其他额外字段继续失败。新增两项 host 回归；scripts/tests 从 151 到 153。

## 主机与制品

完整默认构建命令：`python3 scripts/with-host-slot.py -- ./gradlew spotlessApply test detekt spotlessCheck lintConsumerDebug lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`。`fixture-host.log`、`fixture-host-r2.log` exit=0，各 993 tasks；默认全仓 JVM、静态、双渠道 Debug lint/App/AndroidTest 通过。既有每渠道四项可选外部 Connector 输入仍 skip，不能计外部服务验收。

最后夹具投影修正后 `fixture-projection-host.log` exit=0，834 tasks/33 executed，45s，覆盖编译、静态、双渠道 lint 与两 AndroidTest APK。临时 Kotlin 草稿曾被 detekt 扫描，改为 `.kt.txt`；LongMethod 通过提取有意义的等待/诊断辅助解决，没有 suppress 或跳过门禁。source gates 的 `fixture-source-r2.log`：153 host tests、文档、ADR、国际化及秘密检查通过。

制品隔离检查实际读取两渠道 APK 的 dex：五个 fixture 类型/方法标记均不在主 APK、均在测试 APK。最终检查 JSON 在 `build/maintenance-followup-20261010/fixture-apk-isolation.json`；仅证明所列标记隔离，不称为全 APK 安全审计。

| 制品 | SHA256 |
| --- | --- |
| Consumer App | `3b6eb120c045e3a248055671b327baa06bfa0053d4a3bea87e0d41ec4aff969e` |
| Consumer AndroidTest | `a8bf73c5064b53c0afe5edda437ec06b3b07d69c519860a587ab6729c8f6ecab` |
| Developer App | `6a3958f20442f00bf596afbedc001713b095f49949fb739682142643027f5edd` |
| Developer AndroidTest | `eccb48cd4e34e6f048252af14b0ce3f53287023cc1b0131aad445d977172b153` |

## 设备尝试：不能合并成一次全绿

自有 API36 arm64-v8a `Helix_Main_Verify_API36`，4 GiB、read-only/no-snapshot；稳定 owned runner 核对 serial/PID、冻结制品并 finally 关闭。日常 5554 没有借用、安装或清数据；没有真机。失败尝试及测试数据库仅保存在 ignored build。

| 尝试 | Consumer | Developer | 结果解释 |
| --- | --- | --- | --- |
| 首次全类 | 5608：154 pass/11 fail/1 skip | 5610：160 pass/13 fail/1 skip | 缺归属绑定、旧契约、阶段协议、预算污染；保留失败 |
| r2 全类，阶段类另验 | 5612：162 pass/2 fail | 5614：168 pass/4 fail | 其余受影响类已过；需修正首次自动轮和模型 fixture |
| r3 聚焦 | 5616：19 pass/1 fail | 5618：17 pass/3 fail | 真实压缩重入拒绝；Developer 两项明确 OUTPUT_TOKEN_LIMIT |
| r4 聚焦 | 5620：19 pass/1 fail | 5622：19 pass/1 fail | 输出配置已隔离；仅压缩后续入口未产生请求，八 turn 均 COMPLETED |
| r5 聚焦 | 5624：20/20 pass | 5626：20/20 pass | Pinyin、TaskJourney、ReceiptRace、GoalInputScheduling、真实重复压缩五类；随后旧恢复 host 脚本失败，不能称 runner 整体通过 |

真实模型为授权已有本地 keyless Qwen3.8-27B（端口 30008，模型列表确认上下文 262144），只发送合成夹具；没有读取用户 Provider 配置或使用真实账号。每渠道三次压缩与三次后续回复的原约束断言通过，不推导其他模型摘要质量或通用成功率。Pinyin 实际 n/i/h/a/o 预编辑、候选、“你好”草稿及零发送通过；不以 UTF-8 注入代替。

进程恢复初次 after-script 失败原因另记：seed 的历史时间使会话在首屏以下，旧脚本不滚动；后续发现旧 host 文本含连字符，中文 IME 生成全角且与当前设备断言 `214001214` 不一致。修正 host 导航和精确 fixture 输入，不修改恢复、取消、UNKNOWN 或禁止重执行的断言。恢复阶段仍需实际 PID 死亡/重开证据，Activity 重建不能代替。

## 验证边界

r7 实际恢复补验：Consumer 5632、Developer 5634，两个 runner exit=0、closed exit=0。每渠道归属测试2/2；普通 composer seed/verify各1、编辑 seed/verify各1，TaskJourney setup真正终止发布进程、verify1/1。Consumer composer PID 3895→4398、编辑4936→5384；Developer composer3928→4274、编辑4849→5135。取消边界恢复为INTERRUPTED，UNKNOWN调用身份保留，newExecutions/newModelCalls均0、重复恢复不变。Consumer Goal删除 control1、prepare提交前SIGKILL(5770)、recover-commit提交后SIGKILL(5822)、recover-final1通过；Developer未重复此两次kill。证据分别在fixture-api36-{consumer,developer}-recovery-r7的normal-process.json、phase日志及closed.json。

r6 的导航修正后仍因旧脚本连字符输入失败；保留失败目录，不计pass。已更新两个原日期host恢复脚本的有限列表遍历；composer输入对齐设备现有精确数字断言。历史命令和断言来源保留，没有另建一套恢复语义。最后source gates见fixture-source-final.log（153项及其余门禁passed）；完成文档更新后再核对门禁和staged秘密。API29/OEM、长稳、真实账号及实体手机卡顿/黑屏不由本切片关闭；结构与夹具整理不能证明性能改善。
