# Goal 对话式入口与默认预算

日期：2026-09-10。范围：HXA-190/191 所有者追加的 Goal 创建简化。仅本地主机验证，未操作设备、模拟器或真实账号。

## 问题与改动

原发送按钮在 Goal 模式只打开管理弹窗，再要求填写目标、可选说明、六项预算；新目标默认累计 100000 token、10 分钟，容易先于复杂任务完成耗尽。

现在选择 Goal 后，在普通输入框描述任务并发送即可。补充要求直接写在同一条消息里；附件继续经过既有导入和外发披露。空文本不能创建 Goal。创建只发生在 Provider、附件和披露通过后的串行 Turn 启动门内，并与首个 run/Turn 在同一事务落库。失败不留下无 run 的新目标。原始长消息完整保存在会话并进入模型请求，Goal 的有界 objective 使用前 1024 字作为简述，不截断用户消息正文。

当前会话最近绑定的 Goal 尚未结束时，后续发送显式继续它，沿用原目标预算与累计用量。BLOCKED、预算不足和未决副作用仍由原协调器拒绝，不另造新 Goal 绕过；管理入口保留阻塞处理、继续与删除。终态目标之后的任务或新会话创建新 Goal。

“设置 → Goal 运行设置”集中维护新目标默认值、恢复默认值，以及当前会话已暂停目标的额度调整与提醒。新默认只影响未来目标，修改当前目标必须点选其独立入口；不会修改其他目标或隐式启动任务。无需再经过创建表单。

## 默认值与模型能力

| 项目 | 新目标默认 |
| --- | --- |
| 累计模型调用 | 128 次 |
| 累计工具调用 | 256 次 |
| 累计输入与输出 token | 4000000 |
| 累计实际执行时间 | 120 分钟 |
| 单次 wake 执行时间 | 30 分钟 |
| Goal 失败 wake 重试 | 0 次，保留原策略 |

这些是 Helix 的可编辑产品起点，不是行业统一标准，也不是预计会消耗的额度。累计 token 包含反复发送的历史输入，不等于上下文窗口；400 万提供多轮执行空间，不允许单次请求超过模型容量。现有单轮默认与产品上限保留，请求仍与 Provider 当前模型元数据、上下文窗口、输出限制及剩余 Goal 预算取交集。小窗口模型不会因为 Goal 额度较大收到超窗口请求；未知元数据沿用现有 Provider fallback，不按型号猜测。

设置使用既有 LineStore 的独立偏好 key；旧 Turn 配置及已有 Goal 不迁移额度。损坏 Goal 偏好回到新默认，不重置模式或 Turn 自定义值；拒绝新默认的零 token/零时长与 wake 大于总时长，零值仍不是无限。

## 参考与取舍

- [Pi Agent loop](https://github.com/badlogic/pi-mono/blob/main/packages/agent/src/agent-loop.ts)：以 prompt 消息启动执行，输入入口与运行配置分离。
- [Pi settings](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/docs/settings.md)：常用模型与思考参数集中设置。
- [OpenCode agents](https://opencode.ai/docs/agents/#max-steps)：步骤限制是可配置项；其不设步骤限制的默认不直接搬到 Android 持久化 Goal 的累计预算体系。

决策记录：不适用新 ADR。本轮是现有 Goal 创建、用户显式 Continued 和预算编辑契约内的 UI/默认值优化；不修改 [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md) 的结算、唤醒与恢复语义，不修改 [ADR-0040](../adr/0040-model-judged-goal-completion.md) 的模型完成报告机制。没有自动跨 Turn 驱动或模型自增预算。

## 验证与人工验收

已执行 `scripts/debug/2026-09-10/verify-goal-entry.py`，以及 `summarize-goal-entry.py`：

- `./gradlew :app:testDeveloperDebugUnitTest --tests '*Goal*' --tests '*RunControlStoreTest'`：25 项通过，0 失败、0 跳过。
- `./gradlew :app:assembleDeveloperDebug :app:assembleConsumerDebug :app:compileDeveloperDebugAndroidTestKotlin`：通过；设备用例只编译。
- `./gradlew :app:lintDeveloperDebug :app:lintConsumerDebug`：通过。
- 定向 `spotlessApply`、`bash scripts/check-i18n.sh`、`bash scripts/check-docs.sh`、`git diff --check`：通过。
- 扩展 `./gradlew detekt`：仍有 21 处共享工作区告警，涉及文件工具、订阅、审计和设置装配等。此次 Goal 入口增加的复杂条件及参数数目告警已消除；未扩大到其他模块清理，不能宣称全局静态门禁通过。详情保存在忽略目录 `build/debug/2026-09-10/goal-entry/detekt-final.log`。

没有提交、推送或安装 APK。

新增存储恢复/非法配置回归；设备测试源码补充创建与完整长消息、同一 Goal 继续不重置预算、阻塞不创建替代目标、跨会话隔离和创建失败事务回滚。本轮只编译设备用例，没有把它们计为已执行通过。

人工验收：

1. 新会话选择 Goal，输入任务并发送：不弹创建表单，用户消息立即进入会话，出现目标执行状态。
2. 打开设置修改新目标默认值并重启：保存生效；当前目标额度仍不变，可单独调整暂停目标额度。
3. 暂停目标后发送补充要求：继续同一目标；阻塞时保留处理入口，不新建目标绕过阻塞。
4. 附件外发取消不创建 Goal；管理入口继续、删除、跳转设置均可用。
