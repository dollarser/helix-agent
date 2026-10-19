# 本机备忘与提醒验收（2026-10-19）

范围：[HXA-309](../../development/tasks/HXA-309.md)、[ADR-PLATFORM-005](../../adr/platform/005-local-reminders.md)。起点 `655bd2f2`，工作区初始干净。Owner 已授权知识库后继续备忘提醒及未来定时执行生命周期；本轮不实施 TTS、后台 Agent、286–288 大重构或 Dot 自动化。版本 0.0.6/code 1、工具声明 1 不变；只本地提交，不发布。

## 交付与取舍

独立备忘/提醒条目支持创建、查看、编辑、完成或取消整条；无时间即备忘。日历规则为一次、每日、每周、每月，保存固定 IANA 时区，界面可使用原生日期/时间选择器，保存前显示实际偏移与重复含义。短月份回到月末后仍保留原日期锚点，DST 缺口顺延至有效时间、重叠取较早偏移。没有自动 NLP 创建，没有将知识库来源当指令。

Room 保存条目、revision、下一发生时间和发生事实。AlarmManager 到点执行短通知：默认近似、用户选择且具备特殊权限才精确；WorkManager 负责启动/开机解锁后/覆盖安装/时钟、时区与权限事件及低频核对，不作为准点计时器。没有保活服务、模型调用或新增账号。通知点击只回到具体条目。

拒绝通知或关闭渠道时标 BLOCKED；系统接受通知只称 POSTED，不代表已读。超过 15 分钟记 MISSED，重复旧区间合并跳过，不制造补发风暴。POSTING 先持久化，异常恢复只凭相同发生身份的系统活动通知确认，否则 UNKNOWN，不盲目重发。稳定条目 ID 定位闹钟投影，revision/应到时间校验回调；取消落库但撤销前进程退出，恢复继续撤销。点查发生身份独立于 UI 历史分页，时钟回拨不绕过去重。

参考 Android 官方 alarms/WorkManager/通知权限/强停行为、Todoist 日期与重复、TickTick 日期提醒；来源和采用/不采用部分见 ADR，没有复制参考代码。未来定时 Agent 的规则/发生/attempt 身份、授权冻结、去重、取消、中断/UNKNOWN、重试与结果会话归属已补入现有 [ADR-GOAL-001](../../adr/goal/001-lifecycle-and-completion.md)，不代表已实现自动运行。

## 最终主机与产物

| 检查 | 实际结果 |
| --- | --- |
| 两渠道 Debug、AndroidTest、应用 JVM、Storage JVM、Model JVM、两渠道 Debug lint、spotless/detekt | `build/reminder-final-host.log` 成功（1 分 27 秒） |
| 两渠道 R8/Release | `build/reminder-final-release.log` 成功（1 分 9 秒） |
| JVM XML 汇总 | Standard 1313、Advanced 1448 项（各 4 项既有外部 Connector/profile 条件跳过，不计通过）；Storage 242、Model 168 项，无失败/错误；日历新增 5 项覆盖月末、跳过旧区间、DST、周规则及一次不重复 |
| 源码门禁 | `scripts/check-all.sh --source`：主机脚本 279 项与文档/ADR/i18n/秘密扫描通过；文档最终补齐后另做文档/ADR/秘密检查 |

源码日志中的 FAIL/INCONCLUSIVE 来自验证门禁自己的负面夹具，不是本轮设备执行结果；门禁最终退出码为 0。

最终 APK 位于 `build/reminder/deliverables/Helix-{Standard,Advanced}-{debug,test,release-unsigned}.apk`。`build/reminder/evidence/provenance.json` 保存源码/测试输入摘要、JVM 报告与 APK 完整性。安装后读取设备实际包路径校验候选字节相同。Release 仅构建，不签名发布；设备验收使用普通 Debug，不外推 R8 运行或商店验收。

## 当前任务设备执行

日常 emulator-5554/API36/ARM64；必要的 OnePlus 6T/API34/ARM64 合成短提醒均在当前 Owner 授权内。不录音、不改全局时钟/时区、不更改用户通知/精确闹钟/麦克风权限，不清数据。

| 范围 | 结果 |
| --- | --- |
| 最终 Standard 模拟器常规 | 18/18，33.921 秒 |
| 最终 Advanced 模拟器常规 | 18/18，25.024 秒 |
| 最终 Standard 实际重启/解锁恢复 | seed/verify 各 1/1；verify 0.113 秒，BOOT_COMPLETED 广播和实际记录迟到 14.015 秒有据，结果 BLOCKED |
| 最终 Standard 强停后打开恢复 | seed/verify 各 1/1；到期后仍 stopped=true，显式打开后 verify 0.095 秒，实际记录迟到 68.311 秒，结果 BLOCKED |
| 最终 Advanced 临时强制 Doze 下短闹钟、拒绝通知与条目回跳 | 1/1，11.151 秒；实际记录迟到 13 毫秒，已解除强制 Doze 回 ACTIVE。前一 Standard 候选同范围 1/1 独立保留 |
| OnePlus 实际通知及点击 | 前一候选 1/1，10.387 秒；直接使用系统已发布通知的 PendingIntent，验证进入对应标题编辑框。最终覆盖安装前设备断开，最终真机重测 pending |

18 项为持久状态/故障 12、UI 2、导航 4：备忘 CRUD、旧/重复回调、取消、通知拒绝、错过区间、未知发布/已存在通知核查、发布异常、取消落库断点、已触发项编辑不重发、无效时间/时区与过时编辑、历史分页不影响时钟回拨去重，以及手写/日历确认/完成/取消 UI 和短窗口两倍字体导航。

模拟器两渠道已有通知权限均为拒绝，保留原状；到点必须记录 BLOCKED，不能把该测试写成系统通知可见通过。OnePlus 原先已允许通知，可验证 POSTED 与真实通知点击。其前一成功候选字节保存在 `build/reminder/Advanced-intermediate.apk` 与测试 APK，安装摘要及 `alarm.log`、`effects.log` 对应；随后恢复补齐使用最终源码，不能用旧真机成功代替最终覆盖安装。

设备证据在 `build/reminder/devices/<device>/<flavor>/`，本机 runner、夹具 UUID 与归档脚本在忽略目录 `scripts/local/debug/2026-10-19/`。跨重启夹具 UUID 由主机明确持有，恢复清理必须匹配该 ID 和合成标记。只读盘点可找回中断夹具，不批量删除用户条目。

最终模拟器两渠道的偏好字节（进程诊断除外）、通知/麦克风授予、精确访问模式、固定系统时区与 IME 设置前后一致；强制 Doze 已退出。测试独立库/条目均按 finally/明确 UUID 清理，两个自有测试 APK 已移除，普通应用保留。没有真实长期提醒、私人物料或真实模型调用。

## 失败、修正和保留边界

开发中格式/复杂度门禁、ISO 日期排版 lint、KTX lint 和跨模块可空属性编译失败均已修正并重新构建，失败包不作候选。恢复测试第一次早于解锁，现有应用 CE 偏好不可用；随后发现测试 APK 私有目录写入的夹具身份未校验，改为主机 UUID 并先核查 boot-completed/RUNNING_UNLOCKED。原合成项已只读定位到明确 UUID 后恢复清理，未清库。不能把测试夹具失败称为产品已通过；最终重启结果独立记录。

OnePlus 最终动作 `adb install -r -t` 目标 `561e3b15`：连接不存在，安装未执行，非审批拒绝、无不确定外部写入；此前普通候选的短通知已完成，偏好字节（进程诊断除外）、通知/麦克风授予、时区/IME、精确权限模式已核对未变，测试 APK 已移除。当前独立主机/模拟器验证继续，真机最终重测需设备重新连接。

Android/OEM 可能延迟近似闹钟，精确闹钟也受配额影响；强停期间不能自动恢复，开机未解锁不承诺提醒。没有修改系统时钟/时区来做真实跳变矩阵；日历跳变由纯规则、持久恢复夹具与系统恢复接收路径覆盖。长稳、其他 OEM、真实长周期重复、锁屏未解锁、所有权限切换矩阵和 R8 设备运行未由此验收。

TTS 后续可沿条目 ID/revision/发生身份接入独立且用户明确选择的播报适配器，保留音频结果与 POSTED 通知事实分离；停止/取消沿同一发生身份处理。不能从通知权限推导后台音频、麦克风或 Agent 权限。本阶段未加入音频依赖。
