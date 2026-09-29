# 上下文、输入与会话交互

> 更新：2026-09-25。当前综合研究，不替代 ADR-AGENT-001/002/005。

## 1. 会话是长期容器，Turn 是一次执行

桌面 Agent 的共同趋势是把 Session/Thread 作为长期上下文容器：Codex Thread、Claude session、OpenCode session、DSH SessionEvent log 都允许多次 Turn/消息/工具执行发生在同一会话中。

Helix 应保持：

- Session 持久；
- Turn 有清晰开始/结束；
- 用户发送新输入不要求复活旧 Turn；
- Session history、Workspace、Goal、Artifact 提供连续性。

## 2. Queue 与 Steering

之前研究中 Queue/Steer 是缺口；HXA-214～216 已经交付基础能力。当前推荐仍是：

- 普通新消息默认 Queue，避免静默改变正在执行的上下文；
- 显式 Steer 只在安全 boundary 注入当前 Turn；
- Stop/Cancel 与新用户输入分离；
- 已正式接受的输入/队列保存 durable identity 与回执，不能只保内存字符串；这不要求未发送 composer 文本进入同一数据库；
- process death 后输入保持 parked，不自动消费。

这和 Codex 的 thread/turn/steer 原语方向一致，但 Android 端应更保守处理进程死亡和后台限制。

2026-09-29 的输入缓存变更已另行交付：每会话文件缓存静默覆盖，发送不以自动保存成功为前置；成功接收清理对应缓存，旧回执不能删除新输入。见[输入缓存修复](../../bug-fixes/2026-09-29-conversation-input-cache.md)。本段正式输入事实与未发送缓存不能混同，研究中的 draft 概念不要求 UI 显示保存状态。

## 3. 编辑、重发、Fork、Regenerate

研究综合结论：

- **最新用户消息**：允许会话内 revise + resend；
- **较早历史**：fork 更合理，避免重写大量已经发生的因果；
- **assistant answer regenerate**：创建 replacement Turn；旧 answer 只有在 replacement admission 同事务成功后才 supersede；
- requestId/fingerprint 是 accepted receipt，duplicate 必须先于新 side effect 判断。

Claude `/branch`、OpenCode session fork/revert、Claude checkpoint/rewind 都说明“历史分支/回退”应是显式 session-level 操作，而不是隐式篡改历史。

来源：
- https://code.claude.com/docs/zh-CN/commands
- https://dev.opencode.ai/docs/server/

## 4. Context 由 Harness 组装，但不替模型决定任务流程

Helix 已有 ChatRequestAssembler、ContextCompaction、PromptRegistry、request context manifest；这些是正确基础。

Harness 应保证：

- tool call/result 配对；
- message/attachment identity；
- summary coverage；
- current user input；
- Goal/objective 状态；
- unresolved effect facts；
- source/provenance；
- token/context limit。

模型决定：

- 哪些文件要再读；
- 是否需要再次验证；
- plan 如何更新；
- 工作是否已经结束。

## 5. Compaction / Memory

主流 Agent 都把 compaction/memory 做成 Harness maintenance：OpenCode 有 hidden compaction/summary agent，Claude Code 有 `/compact` 与 project memory，DSH 有 compaction events。

Helix 推荐继续：

- deterministic structure + optional model summary；
- summary 不能删除 unresolved error/review/tool facts；
- request manifest 记录“这次模型实际看到了什么”，但不复制全 request；
- memory/summary 不拥有授权。

## 6. Crash continuation

当前 accepted 恢复方向已经从“same-Turn resume”转为：

```text
Session history + durable world state
      + RecoverySummary
      -> successor Turn
```

RecoverySummary 应有界、结构化，至少区分：completed、interrupted、uncertain effect、artifacts/checks。模型继续时主动 inspect 当前世界。

UNKNOWN 的 review 是事实，不是 workflow：review 可以在 successor Turn 之前或期间解决，但不能让模型猜 effect 已发生。

## 7. @ 与 slash

移动端输入增强优先级仍低于发送可靠性/结果闭环。

- `@`：适合引用文件、Artifact、Task/Goal；应绑定稳定 identity，不把 UI label 当 identity。
- `/`：适合显式少量用户意图（new/plan/export 等），不应发展为第二套工具系统或 workflow DSL。

## 8. 冲突裁决

| 问题 | 综合裁决 |
| --- | --- |
| 用户新消息默认打断还是排队 | 默认 Queue，显式 Steer |
| 中断任务怎么继续 | successor Turn + recovery context，而不是依赖 old Turn control state |
| 模型是否自己判断“没做完” | 是，基于 durable facts/current world；Harness 不硬编码步骤 |
| 请求历史是否可任意编辑 | latest revise；older fork；assistant regenerate replacement Turn |
| context 是否越多越好 | 否，保关键事实/来源，压缩低价值历史 |
