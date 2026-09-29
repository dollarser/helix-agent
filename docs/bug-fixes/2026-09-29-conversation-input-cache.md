# Bug Fix: 输入缓存不应阻塞发送

Status: fixed
Date: 2026-09-29
Related HXA: HXA-214, HXA-215
Affected modules: app composer/chat, core/storage input cache

## Problem

用户报告输入框显示“草稿未保存，请重试”且无法发送，并明确要求不显示草稿相关状态、当前输入覆盖旧内容、每个会话独立缓存、发送后清除。

## Impact

普通发送和部分导航依赖自动保存成功；旧缓存冲突或写入错误可能使用户不断重试而无法提交当前输入。仅有通用状态文字，不能判断现场的具体失败分支。

## Root cause

旧 `ConversationDraftBuffer.persist` 将数据库 expectedRevision/已知快照冲突作为失败；ChatScreen 必须等待 persist 成功才调用 sendSubmission。不同失败统一显示保存失败，重试可能重复同一冲突。以上来自源码，不声称取得用户手机的异常堆栈或复现其唯一触发条件。

另外，部分旧 Turn 回执校验使用草稿相等作为依据；草稿改成可覆盖、可删除文件后必须改为核查已接受历史，不能将可变缓存当发送证明。

## Fix and invariants

- 普通输入框和编辑重发框移除保存中/已保存/未保存及重试保存控件；附件无效、模型未就绪、真实消息接收失败仍保留准确提示。
- 每会话一份当前输入文件，目录为应用私有 `noBackupFilesDir/composer-input`，以会话 ID 哈希命名。串行写入、同目录原子替换，不保存版本链；文件缺失/损坏作为缓存未命中，写失败保留当前进程内输入。
- 用户点击即冻结当前文本、附件与请求身份，直接进入原提交路径，不要求缓存写入成功。当前输入覆盖旧缓存，仅阻止旧页面晚到写入逆转之后的编辑。
- 消息 Accepted/Enqueued 后清理该输入及更旧缓存，不删除下一条新输入；等待确认、取消确认和拒绝不清输入。正式消息、队列和发送回执继续使用 Room，真实存储不可用不能报发送成功。
- 旧 Turn 的重复提交核查改用已接受历史、附件/引用绑定与输入指纹，不依赖已删除缓存。附件和出网批准、Turn/Goal 权限与执行状态不变。
- 非空新会话保留必要元数据，便于切换回来找到输入；仅输入正文移到文件。清空输入/接收消息/删除会话后清理当前文件，不建立额外草稿页面。
- 所有者随后明确要求删除无用表：移除 `composer_drafts`、`ComposerDraftDao`、`ComposerDraftEntity` 和数据库注册入口，更新单一开发期 v1 baseline。文件缓存改为独立 `input/ComposerInputCache` 与 `ComposerInputSnapshot`，不保留旧数据库草稿路径或注解。

主要接线：`ConversationDraftBuffer` 管当前输入/发送快照；`ChatScreen` 直接提交并静默缓存；`MessageRevisionDialog` 在编辑时即捕获快照；`ChatService` 做正式准入与回执核查；`ComposerInputCache` 管文件与最新编辑顺序；`HelixStorage` 提供私有目录和会话删除清理。

## Alternatives considered

没有直接删除 `if(saved)` 后继续信任旧数据库草稿，也没有采用无条件后台写覆盖，因为迟到的旧保存会覆盖用户新输入。没有把草稿变成长期消息表、版本冲突UI或独立工作流；普通缓存失败不得触发模型重试。第一切片曾为现有安装暂留旧表；本轮按用户新要求删除结构，而不是建立长期兼容壳。没有增加启动时无条件 deleteDatabase、fallbackToDestructiveMigration 或 pm clear，也没有操作未指定的用户数据库实例。

## Regression verification

### 第一切片：文件缓存与发送解耦（历史验证）

以下计数及 APK 哈希对应上一切片，不代表后续删表源码/制品。其文件缓存测试已随本轮迁移到 `input/ComposerInputCacheTest`，原有 11 个场景全部保留，并增加引用/附件/修订字段恢复覆盖。

- 文件缓存 JVM：`ComposerDraftFileStoreTest` 11/11，通过独立会话、覆盖、迟到写、接收删除/不删新输入、损坏缓存、写失败内存保留、清空和路径边界。
- 输入缓冲 JVM：`ConversationDraftBufferTest` 每渠道 21/21，通过缓存失败可提交、初始化晚到保留新输入、拒绝不清输入、保存与回执竞争、附件和修订归属。
- app Consumer JVM：884 项，其中 880 pass、4 skip；Developer JVM：929 项，其中 925 pass、4 skip；core/storage JVM：216 pass、0 skip；均 0 failure/error。这里是实际执行计数，不将两个渠道的相同测试说成独立场景。
- 两渠道 Debug APK 和 AndroidTest APK 已构建；新增直接发送/旧缓存不阻塞/删除缓存后回执去重的设备测试只编译，不记作设备通过。
- 第一轮组合门禁因新增代码复杂度检查失败；随后收敛条件/读取函数，保留单一缓存 owner 的合理函数数量说明。文档初次检查因 bug-fix 标题/章节元数据不符合约定失败，现改成规范格式。最终以修正后的源码重跑同一组合 gate：exit 0，detekt、两渠道 JVM/lint/Debug APK/AndroidTest APK 全部通过。source gate exit 0：621 Markdown、213 HXA、35 ADR、1804 i18n keys 和 secret scan 均通过；git diff --check 通过。初次失败仍保留，不拼接成一次通过。

实际组合命令：

```bash
python3 scripts/with-host-slot.py -- ./gradlew spotlessApply detekt \
  :core:storage:testDebugUnitTest :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  :app:lintConsumerDebug :app:lintDeveloperDebug
./scripts/check-all.sh --source
git diff --check
```

最终制品仅已构建，未安装或运行设备测试：

| 制品 | SHA-256 |
| --- | --- |
| consumer Debug APK | `b699df10ccea9228212a5fcdcc6dd8cbe6e891b64db0506e39343d32ddeba843` |
| developer Debug APK | `e4777c7d95c6f3f41db59e4f8ef0f14f15da8d99280ab0e52b6eb8d20f531215` |
| consumer AndroidTest APK | `9543b708550dd6129bbd7861c44a6cdde967648f133dcb8896be22ae6906bf45` |
| developer AndroidTest APK | `584c141e0eb8a6416276ea3b41797695e3688cbf9ec416909ad0af085a2dcbda` |

本轮没有 commit/push；源码在 `main` 的既有 `4a1443df` 工作树中修改，其他并行文档和文件保持原状。

### 第二切片：移除废弃 schema 与界面残留

本轮重新扫描 Room DAO accessor 的生产调用：仅 `composerDraftDao` 没有生产消费者；其余入口仍有存储/业务装配引用。这是有界引用审计，不等于证明所有其他数据模型都已经全局最简。`session_permission_drafts` 用于 CUSTOM 权限配置，`session_inputs`/附件/回执属于正式已接收输入，均不删除。

对话相关三套语言资源已无面向用户的“草稿”保存提示；另外删除了未再生产的 `DRAFT_CHANGED` 错误映射/资源，修复 `/clear` 的英文 fallback `Clear composer input draft`。Skill 创建器的工作区草稿属于仍在使用的文件编辑功能，不是聊天输入状态，本轮保留。

KSP 已重新导出 50-table v1 schema。与本任务起点逐表比对，唯一删除项为 `composer_drafts`，其余 50 张表的完整定义均未变；主机内存 SQLite 可按新 schema 建表、建立索引且 foreign_key_check 无异常。这不是运行 Android Room 的替代证据。schema SHA-256：`fc5b0a5b177ce0ac98d3f9bd4f783d00c906742f04ba3f38c63ef6b005334c1c`。

本轮主机结果：

- `ComposerInputCacheTest` 12/12；原 11 个行为场景保留，新增无 Room 表的附件/引用/修订信息往返。
- `DatabaseContractTest` 6/6，精确 50-table 集合且旧表不存在；`FreshSchemaDeviceTest` 补充同等断言并编译，未执行设备。
- `ComposerPresentationContractTest` 每渠道 3/3；`ConversationDraftBufferTest` 每渠道 21/21。
- Consumer JVM 887 项：883 pass、4 skip；Developer JVM 932 项：928 pass、4 skip；core/storage JVM 217 pass、0 skip，均 0 failure/error。
- 下列完整主机组合命令 exit 0：所有已纳入 `test` 的 JVM 任务（包括 includeSpikes）、detekt、库和双渠道 Debug lint、双渠道 Debug APK/App AndroidTest APK 与 storage AndroidTest APK 构建通过。源码门禁 exit 0：621 Markdown、213 HXA、35 ADR、1803 三语言资源键、secret scan 通过。

```bash
python3 scripts/with-host-slot.py -- ./gradlew -PincludeSpikes=true \
  spotlessApply detekt test lintDebug \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  :core:storage:assembleDebugAndroidTest
./scripts/check-all.sh --source
```

本轮新 Debug APK 身份（未安装）：consumer `5d8fe7dc8c7a3c2a722a155bfb4518930f9f4273420b492f9926b3f73d08d321`；developer `880d289c99a5717bbd2f93870199ea4b24899608a196d364b0269976d1f90eba`。文件级起点/变更核对、schema 与 XML 计数在本地主机 `build/composer-schema-cleanup/`；未触及本轮范围外的已有工作树修改。

辅助 SQLite 核查首次误假定所有 entity 都声明 `indices` 字段而抛 KeyError，改为正确处理无索引表后重跑通过；没有修改产品 schema 以绕过核查。设备测试状态仍为 `not requested`，不将 APK 编译记为 UI/设备验收。

## Residual risk

设备状态为 `not requested`：本轮修改的是源码及新库 schema，未实际清空手机数据库。保留 51-table 旧开发库直接覆盖此新 v1 制品可能遇到 Room identity/schema 不匹配，需要在指定开发安装上明确重建；这不是支持旧库无损升级的声明，也没有默认擦除任何数据库、模型文件或 Workspace。没有操作模拟器/真机、调用真实模型、更新用户手机或使用真实账号。文件缓存是尽力保留，突然终止发生在最近一次写入之前仍可能丢失最近编辑；它不是正式消息账本。进程重启、OEM低空间和真实输入法交互须用授权设备补充验收，不能以 JVM 测试代替。

## Related records

[Turn 与输入契约 §16](../adr/agent/001-turn-coordination.md)、[HXA-214 历史交付](../completion-records/HXA-214.md)、[HXA-215 历史交付](../completion-records/HXA-215.md)。本次为所有者新增反馈的独立修复，不重新打开历史任务，也不启动 R1 架构重构。
