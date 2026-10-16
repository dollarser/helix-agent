# 会话管理首版实施与主机验证

Date: 2026-10-16
Scope: [HXA-295 A](../../development/tasks/HXA-295.md)；B/C 为后续重构计划

## 起点与范围

起点 `main` / `6bbff42d`，工作区干净。Owner 明确选择现在实现必要首版，并要求当前最优、便于扩展、不兼容旧内部版本。只修改会话查询/标题、注册/发现提示、测试和相关文档；不改变 Mobile Use 插件、版本号、冻结的旧发行候选或暂不推送发布决定。

一手资料、源码复核与范围取舍见[研究](../../research/topics/session-management-2026-10-16.md)。现有 UI 功能不是本次新交付；模型任意跨会话访问/创建/发消息没有伪装成已实现。

## 实现

新增宿主 `session.status/history/search/read/rename`。`SessionHistoryDao` 查询既有表并对标题做身份与旧值条件更新；`SessionHistoryRepository` 校验原调用与内容，`SessionTools` 提供具名 Schema、有界结果和取消检查。共用正常工具注册、可用性、Policy 与审计；rename 为普通本地变更，不增加 Plan 元数据豁免。

只读取当前会话有效文本及已绑定引用快照；系统提示、被替代历史和其他会话的实时正文不在范围内。查询不启动 Turn，不执行原工具、不打开旧附件、不复制授权；超过正文上限报告未读。标题变更不改目录、模型或权限，通知失败仍保留已提交事实。

## 本轮验证

| 检查 | 本轮结果与范围 |
| --- | --- |
| 定向 JVM：`SessionToolsTest`、`SessionToolEffectClassifierTest`、`McpToolDiscoveryTest` | Developer 定向 74/74 通过、0 skipped：分别 11、30、33；完整门禁中 Consumer/Developer 各 74/74。本次新增 13 项，其余为相关回归 |
| `detekt` 与本次路径的 Kotlin 格式检查/修正 | 通过；工具描述、查询输出与执行分别承担职责，未增加复杂度阈值豁免 |
| `:core:storage:assembleDebugAndroidTest` | 通过；新增 2 项真实 Room 用例已编译，未执行 |
| `./scripts/check-all.sh --all` | exit 0；Python 主机 254 项、全仓 JVM、Spotless/Detekt/全部 lint、双渠道 Debug/Release（R8）构建、Release Smoke 测试 APK 编译/lint、依赖锁和制品边界检查通过 |

完整门禁的 source 检查：895 Markdown、278 HXA、37 ADR 通过；1119 个生产源码的国际化检查及 2297 个资源键对齐通过；secret scan 通过。最后仅回填验收文档，再运行文档/ADR 门禁与 `git diff --check`；未因文档状态回填重复执行 APK 构建。

定向命令：`./gradlew -I scripts/local/debug/2026-10-16/session-format.init.gradle spotlessKotlinApply detekt :app:testDeveloperDebugUnitTest --tests com.helix.app.chat.SessionToolsTest --tests com.helix.app.tool.SessionToolEffectClassifierTest --tests com.helix.app.mcp.McpToolDiscoveryTest :core:storage:assembleDebugAndroidTest`。格式脚本只列出本次拥有的路径，未批量格式化其他工作。

首轮门禁曾发现新增 Kotlin 长行、任务文档缺少标准章节，以及新增工具方法复杂度；拆分描述、查询和执行职责，修正调用测试及文档结构后重跑通过，没有删除反例或降低规则。失败与成功日志均保留。完整门禁重跑曾被自动审批根据模拟设备失败日志拦下；核对 `check-all.sh` 的主机执行项及 `scripts/tests/` 中替换设备命令的 mocks 后，以源码证据重新申请原命令获准，没有改成真实设备运行或跳过测试。

日志保存于忽略目录 `build/session-management-2026-10-16/`，定向成功为 `targeted-refined-2.log`，完整成功为 `host-all-final.log`。设备、真实 Provider、长稳：not requested；没有启动模拟器、连接真机或消费真实模型账号。实际 SQL/重开用例随 `SessionHistoryStorageDeviceTest` 准备，编译不记为运行。未提交、推送或发布。

## 改动定位

- 执行/描述/分页输出：`app/.../chat/SessionTools.kt`、`SessionToolDescriptors.kt`、`SessionToolHistory.kt`；`DefaultAppContainer.kt` 注册，`SessionToolEffectClassifier.kt` 沿正常授权分类。
- 存储：`core/storage/.../dao/SessionHistoryDao.kt`、`repository/SessionHistoryRepository.kt`，由 `HelixDatabase.kt` / `HelixStorage.kt` 暴露；未新增表、迁移或第二历史副本。
- 回归：`app/.../SessionToolsTest.kt`、`SessionToolEffectClassifierTest.kt`、`McpToolDiscoveryTest.kt` 和 `core/storage/.../SessionHistoryStorageDeviceTest.kt`。
- 用户与模型指导：`app/src/main/resources/prompts/base.md`、`tool-discovery-aliases.json`、`extensions/skills/.../HelixUserGuide.kt` 及中文 `helix-docs` 导航/入门。
- 设计与排期：本次研究、HXA-295、既有 Agent/Permissions ADR、Harness §23.10、HXA-286/287/294 与 status/roadmap/索引；不新增兼容设计文档。

## 审查与剩余边界

- 首版不等于受控跨会话控制完成；用户目标授权、目录范围与创建/消息回执由 B/C 接续 HXA-286/287。
- 搜索是有界字面匹配，有未读/超限信息，不承诺任意历史全文、向量相关性或完成率提升。
- 历史陈述不升级为已验证执行事实；Mobile Use scope、旧 frame/node 及运行身份均不从历史或引用恢复。
- 新增源码不改变已经冻结的 v0.0.6 旧候选来源；若未来要发布本次能力，须从届时确定的源码重新构建和验收。
