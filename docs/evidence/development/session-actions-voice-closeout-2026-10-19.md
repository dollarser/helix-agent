# 会话分支确认与普通包语音收尾（2026-10-19）

## 授权与范围

Owner 要求先处理会话分享及分支确认，再完成语音和当前版本收尾；随后明确暂停分享入口修改，等待产品方向。当前修改仅加入分支确认、相应回归及保留用户设置的测试参数。不启动知识库、大重构、TTS 或发布，版本保持 0.0.6 / code 1，插件/工具声明版本不变。

## 复现与修改

分支问题由实际调用链确认：`MessageActionRow` 原先直接调用 `onFork`，`ConversationSection` 将该事件直接接到服务。现在先显示确认弹窗，展示所选助手消息的原文预览和历史继承范围，说明原会话保留、文件不复制或回滚。待确认请求绑定当前会话；取消、返回或会话切换不调用创建，确认先清空请求再调用服务，防止重复点击。服务原有防重、历史完整性验证和切换会话后不抢回页面的行为保留。见 [ADR-AGENT-007](../../adr/agent/007-session-fork.md)。

新增纯 UI 检查覆盖预览、取消、双击确认、相同消息 ID 下切换会话；另用 UUID 标记的本地合成会话通过真实 `forkSession` 检查取消无新增、确认只新增一个分支、历史消息与 `SESSION_FORK` 来源元数据持久化、原消息保留。只删除本测试创建的会话，不重置已有会话或安全配置。原有分支完整历史与损坏恢复测试同步增加确认步骤，编译通过，本轮没有在用户配置上执行其重置型初始化。

分享只完成源码核查，尚未做设备故障复现，也没有修改或删除入口：

| 入口 | 当前实际行为 | 与其他入口的关系 |
| --- | --- | --- |
| 消息“全文复制” | 单条完整原文写剪贴板，包括 Markdown 源码 | 与消息分享使用相同文本 |
| 消息“分享” | `ACTION_SEND`、`text/plain`、`EXTRA_TEXT` 打开 Android Chooser | 没有云端上传或会话链接，内容与复制重叠 |
| 导出会话 JSONL | 系统文件选择器保存整会话消息、调用、结果、压缩及用量等结构化记录；大内容可能只有引用 | 范围不同，不等同于单条复制或完整备份 |
| 文件/产物分享 | `EXTRA_STREAM`、MIME 与 FileProvider 临时读取授权交给系统 Chooser | 是文件交付，与纯文本复制不同 |

因此不能在没有产品决定和设备复现的情况下断言“分享不能用”的具体原因，或把它描述成已经修好。没有向真实联系人或第三方应用发送会话内容。

## 主机验证

- `./scripts/check-all.sh --source`：279 项主机脚本测试通过，文档、ADR、i18n、秘密扫描通过。负向夹具输出的 `FAIL_FUNCTIONAL` / `INCONCLUSIVE` 是测试预期，不是本轮设备失败。
- `spotlessCheck detekt :app:lintConsumerDebug :app:lintDeveloperDebug :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerRelease :app:assembleDeveloperRelease --offline --console=plain`：通过。Consumer JVM 1321 项、Developer 1456 项，均零失败/错误，各 4 项既有可选外部输入检查跳过。
- 新增测试的编译、格式检查和首轮设备检查分别暴露读取实体字段、长行及遗漏分支来源元数据的测试问题，均修正，没有放宽门禁或删除边界断言。最终重跑 `spotlessApply detekt :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` 成功；只使用成功构建的最终测试 APK 形成通过证据。
- 普通 Debug 包在前述成功 Debug 构建中生成，未传 `helix.asrValidation` / `helix.asrBenchmark`。本轮 ASR 运行代码、模型和原生依赖均未变，仍为正式默认的 sherpa-onnx + 小型双语 Zipformer，详见[语音实施证据](offline-voice-input-2026-10-19.md)和[十模型对照](mobile-asr-comparison-2026-10-19.md)。

## 普通包设备验证

安装均为 `adb install -r`，逐个校验设备实际 APK 与本轮成功构建产物的 SHA-256。使用正式包名，不以独立验证包代替普通包验收。测试以 `helix.preserveUserSettings=true` 跳过测试 Runner 的语言固定，不调用通用的会话/配置重置助手。

| 设备 / API / ABI | 普通包 | 当前结果 |
| --- | --- | --- |
| 日常 Helix_API_36 / API36 / ARM64 | Standard `com.helix.agent` | 17 项通过：分支 UI/持久化 4，语音编辑/草稿/取消/后台/会话切换 UI 4，公开音频与合成控制器 6，权限拒绝/允许及焦点抢占 3 |
| 日常 Helix_API_36 / API36 / ARM64 | Advanced `com.helix.agent.developer` | 同组 17 项通过；本轮三个普通包累计 40 项设备检查通过 |
| OnePlus 6T / API34 / ARM64 | Advanced `com.helix.agent.developer` | 6 项公开音频与合成控制器通过；没有授权或启动手机录音 |

模拟器已关闭宿主麦克风路由，真实 AudioRecord 测试只收到模拟器静音；这不能当作实际环境识别。权限测试还检查释放录音和取消后草稿不变。手机测试仅直接注入公开 WAV 或合成 PCM，不打开 AudioRecord。

本轮公开双语 WAV 长 10.053 秒：模拟器 Standard 加载约 598 ms、含加载与加速解码总计 732 ms；OnePlus Advanced 加载约 2076 ms、含加载与加速解码总计 3221 ms。两台均输出多次增量更新（14 / 13），最终包含夹具中的中文关键短语；英文亦有误识别。这里只是单夹具回归，不是自然口述首字延迟、完整准确率评估或实时麦克风实测，不能替代十模型固定语料对照。

## 数据、设置与剩余边界

未清除应用数据，未重置用户安全配置、语言或输入设置，未关闭未知用户会话。OnePlus 上仍为 0.0.5 的 Standard 保持原安装，本轮只更新其既有 0.0.6 Advanced，不借语音任务迁移/删除旧版数据库。原有模拟器键盘和中文 IME 配置保持，未启动新 AVD。

测试前后对 SharedPreferences 文件做哈希对照：只有正常启动/生命周期更新的 `process-diagnostics-v1.xml` 发生变化（`ProcessEvidenceStore` 的心跳状态记录），其余用户设置相同，麦克风权限与原有 USER_SET/USER_FIXED 标志恢复并核对一致。只移除本轮自有测试 APK，普通包及用户数据保留。不将进程诊断记录恢复为旧心跳以伪造未运行。

分享入口去留仍等待 Owner 产品决定；真实手机麦克风口述需要 Owner 主动操作。Release R8 构建通过，本轮未做新增 Release 设备验收、签名发布、推送或商店提交。不能把这些有界检查称为所有 OEM、用户数据迁移或完整版本发布验收。

本地原始证据位于忽略的 `build/asr/closeout/`：各普通包/测试包、安装日志、分阶段 instrumentation 原始结果、配置哈希及权限前后快照、仅公开夹具的 ASR 指标日志，以及主机门禁日志。运行脚本在忽略的 `scripts/local/debug/2026-10-19/voice-closeout-device.py`。产物完整性值留在本地证据，不写入会随重新打包失效的文档库存。
