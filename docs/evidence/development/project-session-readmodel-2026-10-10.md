# 项目会话实时读模型验收（2026-10-10）

Owner request：提交既有工作、参考当前状态继续优化；本轮明确允许模拟器和真实模型测试。本项 HXA-254 从 `2a40a661` 开始，仅改变项目会话展示、存储窄查询和回归夹具，不改变数据库 schema、模型协议或执行 owner。

## 缺陷与实现

原页面以成员集合/加入模式触发一次性 `SessionEntity` 读取。成员不变时，其他入口重命名、归档/恢复会话不会刷新；加入页也不会看到新候选。可复现步骤：进入项目会话页，保持成员集合，从另一个入口重命名或归档成员；原页面保留旧标题/状态。

现在 DAO 的 Room Flow 仅查询 ID、标题和归档标记，ProjectService 转成应用读模型，页面按生命周期订阅。加载、读取失败、成功空列表分别显示；读取失败可重新订阅，取消/致命错误不转为普通失败。项目切换和归档清理临时加入状态，归属转移、活动 Turn 拒绝和目录保护保持原事务 owner。

参考 [Android 官方 UDF/生命周期](https://developer.android.com/topic/architecture/recommendations)、[OpenCode Client 边界](https://raw.githubusercontent.com/anomalyco/opencode/dev/AGENTS.md)、[RikkaHub 页面 Flow](https://raw.githubusercontent.com/rikkahub/rikkahub/master/app/src/main/java/me/rerere/rikkahub/service/ConversationSessionManager.kt)。只采纳分层/订阅原则，不复制参考代码、引入框架或用进程 Map 取代 Room。

## 主机验证

`python3 scripts/with-host-slot.py -- ./gradlew spotlessApply test detekt spotlessCheck lintConsumerDebug lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` exit=0。默认构建全仓 JVM 测试、静态检查、双渠道 Debug lint/App/AndroidTest 编译通过；项目实现及最终拼音夹具修正后分别记录 `build/maintenance-20261010/project-host-full-r3.log`、`project-host-final.log`。最终 993 tasks、33 executed，41s；不将此 Debug 验证称为发行门禁或 opt-in spike 全验收。

新增读模型 JVM 用例每渠道四项通过，覆盖顺序/空/归档、失败与重订阅、重复显示状态去重、取消/致命错误传播。协程 stacktrace recovery 可复制异常，断言保留类型、消息及原异常 cause 身份；不依赖顶层引用相等。原仓储测试保留，fake 的固定快照不冒充 Room invalidation 验证。

Room identity 仍为 `c34065205d0987bb3b495a2f4a663c43`，baseline/schema 未变，未重建日常数据库。源门禁 151 项 host fixture、review、文档、ADR、国际化及秘密扫描通过，初次日志 `source-gates-hxa254.log`，最终文档同步后再次执行记录 `source-gates-final.log`。既有 ConnectorExternalAcceptance/ConnectorSuppliedArchive/WorkBuddySuppliedArchive 缺可选真实输入的每渠道四项 skip 仍是 skip，未将其升级为当前外部服务通过；本项新增四项读模型每渠道 skip=0。

## 当前设备记录

使用自有 `Helix_Main_Verify_API36`、API36 arm64-v8a、只读/no-snapshot 克隆，Consumer 独立端口5598、Developer5600；原日常5554未借用、未安装/清数据。runner 保存 APK SHA256、启动 PID、设备身份、原始 instrumentation 及最终退出记录。项目套件为 ProjectServiceDeviceTest 四项、ProjectLifecycleUiDeviceTest 一项、ProjectSessionsUiDeviceTest 两项；另验实际拼音输入一项。

首次 Consumer 项目 7/7 通过，拼音 0/1 失败，不能将该次整体验收标为通过。输入法确有中文候选，但旧夹具的 `startsWith("你好")` 接受了“你好啊”。收紧中文词匹配后的第二次仍失败，拉丁预测候选、大小写忽略与键盘动画使节点按键不可靠，IME 出现 IH/“记号”而非目标拼音。最终使用大小写精确匹配、等待按键矩形稳定250ms及触摸后的异步更新，必要时展开更多候选；增加每次触摸 label/节点/坐标日志。保留中文草稿严格等于“你好”及零发送断言，未替换为 UTF-8 注入。两次失败日志 `api36-consumer/pinyin.txt`、`api36-consumer-r2/pinyin.txt`，两次自有进程均关闭 exit=0；r3 的7+1先通过，最终格式修正/全仓主机后又重新验最终制品。

Developer 首次启动被 runner 的端口排他检查拒绝（5598 Address already in use），尚未创建 AVD或执行测试，日志 `api36-developer-final-run.log`；未杀占用者，改用空闲5600。该启动失败不计作设备通过或产品失败。

## 最终设备矩阵与制品

| 渠道 | 项目/Room/UI | 实际拼音及中文零发送 | 结果与日志 |
| --- | --- | --- | --- |
| Consumer Debug | 7/7 | 1/1 | passed；`api36-consumer-final-run.log`、`api36-consumer-final/pinyin.txt` |
| Developer Debug | 7/7 | 1/1 | passed；同一 instrumentation **8/8**，`api36-developer-final-r2-run.log` |

均为 API36、arm64-v8a，1080×2400/420dpi、4GiB；构建指纹 `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`。两次最终 runner exit=0，自有进程 closed exit=0。`hw.keyboard=yes`、`show_ime_with_hard_keyboard=1`，使用已有 Gboard 中文拼音，无新输入法安装。当前日志确认 probe/ConversationComposer 分别实际按下 n/i/h/a/o、中文候选及“你好”提交；IME 内部预编辑不要求等同 TextField composition。

SHA256 来自最终 runner 的 `artifacts.json`：

| 制品 | SHA256 |
| --- | --- |
| Consumer App | `de27cb5344937fe3b7377fdd4d5baf15818941cd10d7f659968c4c78a730e226` |
| Consumer AndroidTest | `621a0cbbf17c28fb859951e36235b18e9d797fbe7c210943a32e65748d9957d8` |
| Developer App | `f5dff6a3f269cf57e41e990ba423b658ddbee403b13b2e96f3b597a7d3acc65a` |
| Developer AndroidTest | `953645ab76d6969f58443548e5f509a38cf5e788e9d7636def4336176c3229e5` |

稳定复跑入口为 `scripts/run-owned-emulator.py`，须使用 owner 本轮授权且空闲的端口；`--classes` 选择 `com.helix.app.projects.ProjectServiceDeviceTest,com.helix.app.ui.ProjectLifecycleUiDeviceTest,com.helix.app.ui.ProjectSessionsUiDeviceTest,com.helix.app.ui.PinyinComposerDeviceTest`，consumer runner=`com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`，developer runner=`com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`，相应 Debug APK/AndroidTest APK、API36自有AVD、`--timeout 600 --memory-mb 4096`。Consumer 本次7项主套件加同一自有进程 after-script 的1项拼音；Developer已直接用上述四类验证8项聚合入口。语言未配置时先在自有实例准备拼音，不借用日常实例或将缺条件计为 pass。

## 留存与边界

磁盘接近满时仅删除本轮先前恢复调查生成、无人占用且可从保留 qcow2 重建的两份 raw 转换副本；原 userdata、encryption key、qcow2 与失败日志保留。删除前检查记录在忽略的 `build/maintenance-20261010/derived-image-cleanup.json`，没有清理原 AVD 或唯一用户数据。

本项不改推理路径，未调用真实模型或消费 Qwen 用量。模拟器局部通过不证明真机/OEM、后台长稳或大规模列表性能；所有者原报卡顿/黑屏仍需实体设备复现、主线程/窗口/生命周期证据，不能由本次架构整理关闭。
