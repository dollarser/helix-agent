# 会话存储优化（2026-10-16）

任务：[HXA-297](../../development/tasks/HXA-297.md)。起点为 `main` / `6bbff42d`，保留工作区既有 HXA-295/296 改动；未提交、推送、发布或操作设备。本文记录本轮实现和验证，不覆盖整个产品或后续宿主重组。

## 选择与实现

- **持久权威**：保留 Room/ContentStore。原 schema version 1 的 53 表增加 `message_search`，当前 54 表；预览版采用既有重建策略，不维护旧内部结构迁移，Android versionCode 未变。
- **查询**：会话按有效可见消息的最近活动排序；消息、搜索正文/折叠文本与活动时间在同一事务写入，标题变更同步投影。搜索限制返回命中数量，取消原 500 候选和正文截断造成的遗漏；保留字面 `%_`、中文及大小写匹配，返回有界原文片段。修订过滤、分支复制、删除级联有回归。
- **页面读取**：初始加载最近 100 条有效消息，主动加载更早消息；命中旧消息时读取附近窗口，支持前后查找、回到最新；主动发送时返回最新窗口，避免新消息留在屏幕之外。任务结果按原 Turn 读取。工具时间线先在 SQL 限定候选 200 条，再做展示投影。没有把页面窗口施加到模型上下文。
- **调用来源**：模型和工具分别持久化 Turn 内 ordinal/createdAt；模型增加 settledAt、工具批次消息关联，工具增加 modelCallId/callIndex。同 Turn 外键、唯一序号和批次索引阻止串联/重复身份；直接调用不伪造模型来源。正常、拒绝和失败准备路径保留来源。
- **GC**：补正文引用索引，目录按需遍历；引用复核和删除按单个候选与发布协调，不再持锁遍历整个目录。保留并发发布、符号链接和未知引用保护。
- **JSONL**：现有用户主动导出更新到 v2，包含时间、序号与显式来源；同步独立校验器，并修复其不识别已有跨会话引用记录的问题。原设备产生的 v1 夹具保留，新 v2 夹具标明为主机合成，不能当作当前 Android 导出实测。

## 竞品依据与取舍

[Codex thread history](https://github.com/openai/codex/blob/main/codex-rs/thread-store/src/local/thread_history.rs) 使用 JSONL 与 SQLite 查询投影；采用其身份、顺序与读取边界思路，不复制其双介质职责。[Claude Code](https://code.claude.com/docs/en/how-claude-code-works) 保存 JSONL 对话，说明该格式适合检查和续聊，但不意味着 Android 应用必须实时双写。[OpenCode](https://github.com/anomalyco/opencode/blob/dev/packages/core/src/session/sql.ts) 使用 SQLite 的会话/类型化消息结构；[OpenHands SDK](https://docs.openhands.dev/sdk/guides/convo-persistence) 分离基础状态与事件 JSON。竞品并非都以 JSONL 为唯一权威，Helix 继续选择已有事务事实源。

为保持中文和任意字面子串语义，当前用持久搜索投影与 SQL `instr`，未引入 FTS/tokenizer 或向量库。代价是额外保存正文和折叠文本，搜索仍随总文本增长；索引改善按会话和引用定位，不保证全文检索为次线性。投影可重建但不新增第二份模型历史，内容丢失后的搜索片段也不冒充已验证的原文件。数据库和正文均不是自动无敏感信息或全库加密存储。

JSONL 当前用于诊断、检索、评测和后续迁移设计；reference_only/missing 等状态明确，不是完整可恢复备份。无导入、跨设备恢复或工具回放；可恢复备份须另行定义完整材料、身份与权限边界。没有因为可读到历史就继承任何执行授权。

## 验证结果与边界

- 已执行：SQLite 14 项、导出校验器 8 项通过；Storage 241/241、Agent 291/291；Standard App 1,247 通过/4 跳过，Advanced App 1,368 通过/4 跳过。跳过项为现有 ConnectorSuppliedArchive、WorkBuddySuppliedArchive 和 ConnectorExternalAcceptance 外部输入夹具，不计为通过；无新豁免。Spotless/Detekt 和两渠道 App/Storage AndroidTest APK 编译通过。
- 初轮发现并修复：schema 固定表数/列断言、DAO 参数和测试替身未跟进新契约、标题搜索投影期望；未跳过失败用例。全仓检查另发现长方法/行宽/职责数量门禁及显示投影构造参数缺失，已整理；历史窗口工具查询补齐上下界，剔除旧窗口已完成展示记录，保留活跃执行与恢复控件。导出 v2 合成夹具补齐已有 conversation_reference 计数，保留 v1 历史样本。
- 完整 `./scripts/check-all.sh --all` 退出 0：269 项主机脚本测试通过；文档/ADR/i18n/凭据、Spotless/Detekt、所有适用 lint、全仓 JVM、两渠道 Debug/Release、独立 Release smoke 测试 APK 编译、依赖锁和 Debug/Release APK 运行边界通过。Release smoke 未在设备执行。
- 最终差异检查通过，受验源码保持不变；原分支/HEAD 未变，抽查的 13 个无关既有 Kotlin 路径差异与基线相同。格式化仅限本轮指定文件，没有批量处理其他修改。原始输出位于忽略的 `build/hxa297/`。
- 设备、模拟器、真实 Provider：**not requested**。Room/UI 夹具编译不等于运行通过；大会话 Android 延迟、峰值内存与真实模型表现未测。

### 可复核命令

| 命令 | 当前结果 |
| --- | --- |
| `python3 -m unittest scripts.tests.test_session_export_validator scripts.tests.test_session_storage_queries` | 22/22 通过；查询、约束、索引、导出引用 |
| `./gradlew spotlessCheck detekt :core:storage:testDebugUnitTest :core:agent:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest` | 通过；计数及外部输入跳过项见上方 |
| `./scripts/check-all.sh --all` | 通过；最终日志 `build/hxa297/check-all-final.log`，前几轮失败记录保留 |
| `./gradlew :core:storage:assembleDebugAndroidTest :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` | 编译通过，未运行；最终日志 `build/hxa297/android-test-apks-final.log` |
| `python3 scripts/validate-session-export.py scripts/fixtures/session-export-v2.jsonl` | valid=true、materialsComplete=false，14 条合成记录；不是设备验收或完整备份 |
| `git diff --check` | 通过 |

## 主要改动位置

| 范围 | 文件入口 |
| --- | --- |
| schema、活动与来源 | `core/storage/.../HelixDatabase.kt`、`entity/ConversationEntities.kt`、`entity/MessageSearchEntity.kt` |
| 查询与事务写入 | `dao/SessionSearchDao.kt`、`MessageDao.kt`、`ModelCallDao.kt`、`ToolCallDao.kt` 及对应 Repository；`SessionHistoryDao/Repository` 标题投影 |
| 正文回收 | `content/StorageGarbageCollector.kt`；消息、结果、输入和引用快照的 contentRef 索引 |
| 模型到工具来源 | `core/agent/.../AgentLoop.kt`、`AgentToolGateway.kt`；App `TurnCoordinator.kt`、`ChatToolCalls.kt`、`ChatToolSettlement.kt` |
| 展示与导航 | App `ChatScreenProjection.kt`、`ChatService.kt`、`ChatTimelineOrder.kt`、`ConversationSection/Search/Intents`、会话列表及三组语言资源 |
| JSONL | Storage `export/SessionExport*`；`scripts/validate-session-export.py`、v2 合成夹具及校验测试 |
| 回归 | `scripts/tests/test_session_storage_queries.py`、Storage Repository/Export/schema JVM、App `ChatHistoryWindowTest`/搜索测试、Room/UI/TurnCoordinator 编译夹具 |
| 决策与接续 | ADR-AGENT-001/005、`architecture/session-export.md`、Harness §23.11、HXA-287/288/294/297、status/roadmap 与原审查文档 |

以上 `...` 表示对应模块既有 Kotlin 包路径；旧 HXA-295/296 的其他工作区改动不算本轮交付。

## 重构接续

更新原 ADR-AGENT-001/005、[目标方案 §23.11](../../architecture/harness-refactor-plan.md#2311-会话存储优化接续) 和 HXA-287/288/294。当前查询与来源实现是接续基线，不是完整 Turn 宿主、类型化产物或跨会话调度已经完成；后续替换入口时保留本轮事务/身份/分页契约并删除旧路径。
