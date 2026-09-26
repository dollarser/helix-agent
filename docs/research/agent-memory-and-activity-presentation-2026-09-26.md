# 主流 Agent Memory 与 Tool Activity Presentation 调研

> 日期：2026-09-26。目的：为 Helix HXA-229（模型生成的 Agent Activity Presentation）与 HXA-230（Markdown-native Hierarchical Agent Memory）冻结设计依据。调研优先参考官方文档、官方仓库与公开实现/issue；“已实现”和“公开提案”严格区分。

## 1. 结论

本轮调研后，Helix 对两项争议做如下修正：

1. **Memory 采用 Markdown-native，而不是数据库承载一份竞争性的 semantic truth。**
   - Markdown 是人和 Agent 都能直接读写的长期知识载体；
   - SQLite/Room 只用于索引、检索缓存、mtime/hash、provenance 辅助和 UI 查询；
   - 至少区分 global/user 与 project memory；
   - 当前 Session/Turn 继续由 Session history、compaction、Goal/Turn durable state 表达，不另外制造一份“Session MEMORY.md”复制会话状态；
   - Memory 使用 progressive disclosure，不把全部文件每次塞进 prompt。

2. **Tool/Activity 的“做什么/为什么做”优先由发起 ToolCall 的模型生成。**
   - 模型最了解行为目的；纯规则只能识别“读文件/跑命令”，通常无法表达“查看隔离运行进度汇总”；
   - Harness 不取代模型生成 intent，但继续拥有 status/effect/result truth；
   - UI 第一层显示 model-authored intent，第二层显示 tool/command，第三层保留 raw args/result/audit；
   - 确定性 `ToolPurpose` 只作为缺失/无效 intent 的 fallback。

核心边界：

> **模型负责语义意图，Harness 负责执行事实。**

> **Markdown 负责记忆内容，结构化索引负责找到、筛选和管理它，而不是取代它。**

## 2. Memory：竞品实现

### 2.1 CodeBuddy / WorkBuddy

官方文档已经形成完整分层：

- 用户记忆：`~/.codebuddy/CODEBUDDY.md`；
- 用户规则：`~/.codebuddy/rules/*.md`；
- 项目记忆：`./CODEBUDDY.md` 或 `./.codebuddy/CODEBUDDY.md`；
- 项目本地私有记忆：`./CODEBUDDY.local.md`；
- Auto Memory 项目作用域：`~/.codebuddy/memories/{project-id}/`；
- Auto Memory 全局作用域：`~/.codebuddy/memories/global/`。

Auto Memory 的项目目录包含 `MEMORY.md` 索引；官方建议把详细内容拆到 `preferences.md`、`decisions.md` 等 topic files，并从 `MEMORY.md` 链接。Typed Memory 默认用 YAML frontmatter，并区分 `user / feedback / project / reference`。相关性选择默认开启，按用户查询最多注入 5 个相关 memory。后台 extraction 是可选项。

团队模式又允许把项目 memory 放进：

```text
{project}/.codebuddy/memories/@{user-id}/
```

因此它不是“一个 MEMORY.md”，而是：

```text
global namespace
project namespace
MEMORY.md index
topic markdown
typed frontmatter
relevance selection
optional extraction/consolidation
```

来源：

- https://www.workbuddy.ai/docs/zh/cli/memory
- https://www.workbuddy.ai/docs/zh/cli/settings

**对 Helix 的启示**：Markdown-first + global/project 分层 + topic files + bounded relevance retrieval 是目前最直接可借鉴的形态。

### 2.2 Claude Code

Claude Code 的当前公开目录结构同时存在：

- global/project `CLAUDE.md`；
- global/project rules；
- project/global subagent memory；
- `~/.claude/projects/<project>/memory/` Auto Memory。

官方 `/memory` 可以编辑 `CLAUDE.md`、启停 auto-memory、查看自动记忆。项目目录和用户目录的 Markdown instructions 会跨会话加载；Auto Memory 则按项目存放 Claude 写给自己的 notes。Claude Code 同时把 conversation transcript、project auto memory、project/global instructions、subagent persistent memory 分开。

来源：

- https://code.claude.com/docs/zh-CN/commands
- https://code.claude.com/docs/fr/claude-directory

**对 Helix 的启示**：Session history 与 long-term memory 是两类数据；project-scoped auto memory 是合理默认边界，global memory 更适合用户偏好/跨项目规则。

### 2.3 Cursor

Cursor Memories 官方说明：

- Memories 根据 Chat 自动生成；
- scope 是当前 git repository/project；
- 使用 sidecar model 被动观察对话并提取 memory；
- 后台生成的 memory 在保存前要求用户批准；
- Agent 也可以通过 tool call 创建 memory；
- User Rules 是 global；Project Rules 是 repo scope。

Cursor Automations 又提供 persistent memory，默认名为 `MEMORIES.md`，存储在 automation working filesystem 之外，并允许 Agent 增删 memory 文件。

来源：

- https://docs.cursor.com/en/context/memories
- https://docs.cursor.com/context/rules
- https://cursor.com/docs/cloud-agent/automations

**对 Helix 的启示**：自动提取可保留“主模型主动写”和“sidecar/consolidation”两条路径；项目隔离和用户可审阅很重要。

### 2.4 OpenAI Codex / Agents

Codex CLI 当前正式机制是：

- `~/.codex` global AGENTS instructions；
- repo root → CWD 的层级 `AGENTS.md`；
- 长期任务官方建议把 spec/plan/status 固定在 Markdown 文件中；
- Goal 是 thread-scoped persisted state，明确不是 global memory，也不是 project instructions。

OpenAI Agents Sandbox 的 Memory capability 默认 workspace layout：

```text
memories/
  memory_summary.md
  MEMORY.md
  raw_memories.md
  phase_two_selection.json
  raw_memories/
    <rollout-id>.md
  rollout_summaries/
    <rollout-id>_<slug>.md
```

读取采用 progressive disclosure：

1. run 开始注入 `memory_summary.md`；
2. 相关时 Agent 搜索 `MEMORY.md`；
3. 需要更多细节时才打开 rollout summaries。

run 结束后先抽取 raw memories，再 consolidation 到 `MEMORY.md` 和 `memory_summary.md`。Memory 与 SDK conversation Session memory 明确分离。

来源：

- https://developers.openai.com/api/docs/guides/latest-model
- https://developers.openai.com/blog/run-long-horizon-tasks-with-codex
- https://developers.openai.com/api/docs/guides/agents/sandboxes
- https://developers.openai.com/cookbook/examples/codex/using_goals_in_codex

**对 Helix 的启示**：Markdown 可以直接作为 canonical memory artifact；summary → MEMORY.md → raw/rollout detail 的 progressive disclosure 非常适合移动端有限 context。

### 2.5 OpenCode

OpenCode 官方 V2 主要提供：

- global `~/.config/opencode/AGENTS.md`；
- project/nested `AGENTS.md`；
- 随文件访问动态发现 nested instructions；
- project guidance 推荐 commit 到 repo。

官方尚没有和 CodeBuddy/Claude 等价的统一 Auto Memory contract。社区已经出现把：

```text
~/.config/opencode/MEMORY.md
.opencode/MEMORY.md
```

作为 global/project memory 并通过 instructions 注入的做法，但这属于社区扩展，不应写成 OpenCode 官方内建事实。

来源：

- https://opencode.ai/v2/docs/instructions
- https://opencode.ai/docs/zh-cn/rules/
- 社区示例：https://github.com/osmontero/opencode-skills/blob/main/AGENTS.md

### 2.6 Operit

Operit 当前公开说明强调：

- 智能记忆库；
- AI 自动分类管理；
- 时间查询；
- 导入导出；
- 自动总结；
- 历史对话智能搜索；
- 问题库迁移到记忆体系；
- 角色卡可绑定独立 memory/tools/Skill/MCP。

公开材料没有证明其核心 memory canonical format 是 Markdown；它更偏应用内记忆库/图谱化检索。

来源：

- https://github.com/AAswordman/Operit

**对 Helix 的启示**：移动端产品需要 Memory 管理 UI、搜索和角色/Session 绑定，但底层格式不必复制 Operit；Helix 更适合透明、可编辑、可移植的 Markdown。

## 3. Memory：主流共同模式

| 产品 | Global | Project | Markdown-first | Auto write/extract | Progressive / relevance |
| --- | --- | --- | --- | --- | --- |
| CodeBuddy | 是 | 是 | 是 | 是 | 是，最多相关 5 项 |
| Claude Code | 是（instructions） | 是 | 是 | 是，project auto memory | 有分层加载 |
| Cursor | User Rules | 是 | memory 为规则/条目 | 是，sidecar + tool | project relevance |
| Codex CLI | AGENTS global | AGENTS/project docs | 是 | 主要由 Agent/工作流维护 | 按目录/任务读取 |
| OpenAI Sandbox Memory | layout 可配置 | workspace scoped | 是 | 是，extract + consolidate | summary → MEMORY → rollout |
| OpenCode | AGENTS global | AGENTS project | 是 | 官方无统一 auto memory | nested discovery |
| Operit | 有用户/角色上下文 | workspace/history | 未证明 | 是 | 智能检索 |

因此 Helix 不应选择“所有项目一个 Memory”或“只有项目 Memory”二选一，而应采用：

```text
Global/User Memory
Project Memory
Session/Turn state（不是另一份长期 Memory）
```

## 4. Helix Memory 最终裁决

### 4.1 Markdown 是 semantic source of truth

推荐 app-private memory root：

```text
memory/
  global/
    memory_summary.md
    MEMORY.md
    user.md
    feedback.md
    references.md

  projects/
    <project-id>/
      memory_summary.md
      MEMORY.md
      architecture.md
      decisions.md
      workflows.md
      pitfalls.md
      references.md
```

默认放 app-private，而不是擅自改用户 repo。

原因：

- Android app 不应为了 Auto Memory 默认修改 git worktree；
- 用户仍可在 Helix 内直接打开/编辑 Markdown；
- 后续可提供 export/import；
- HXA-210 稳定后可增加 team/shared project memory 显式选项；
- 不需要让 Room 保存一份语义内容与 Markdown 竞争。

### 4.2 Room/索引的职责

允许可重建索引：

```text
MemoryIndex
  scope
  projectId
  path
  title
  type
  description
  contentHash
  mtime
  lastIndexedAt
  source hints
```

但 index 可重建，Markdown 才是内容真值。

### 4.3 MEMORY.md 是 bounded index，不是无限日志

参考 CodeBuddy/OpenAI：

- `MEMORY.md` 保持短小；
- topic detail 拆分；
- 可另有 `memory_summary.md` 作为更短自动注入层；
- raw extraction/rollout summary 不直接全部注入；
- Context Builder 只加载 summary + relevant topics。

### 4.4 Scope

**Global/User memory** 适合：

- 跨项目稳定偏好；
- 用户明确反馈；
- 一般工作习惯；
- 全局安全/工作流偏好。

**Project memory** 适合：

- 架构决定；
- build/test 命令；
- 项目坑点；
- 项目长期工作背景；
- 已验证环境事实。

例如 “Helix developer build 需要 JDK 17 + PRoot assets” 是 project memory，不应进入 global。

### 4.5 Auto write

建议：

- explicit “remember this” → 立即走 memory tool；
- project auto-memory → 默认可启用，Agent 可主动维护；
- global auto-memory → 默认更保守，可由设置分别控制；
- 可选 background extraction/consolidation，作为后续优化；
- Memory 写入必须可见、可编辑、可删除；
- 不从网页/MCP/工具输出直接无条件写 durable memory，避免 poisoning。

### 4.6 Project identity

完整 Project Memory 需要稳定 `ProjectId`。

在 HXA-210 Workspace binding 仍未裁决前：

- 可以冻结 Markdown layout、Memory tool、global memory；
- project scope API 使用抽象 `ProjectMemoryScopeKey`；
- 不把当前 `directoryRef` 临时硬编码成永久 memory identity；
- HXA-210 给出稳定 project/workspace identity 后再启用完整 project auto-memory。

## 5. Tool/Activity Presentation：竞品实现

### 5.1 OpenCode：明确的 model-generated Bash description

OpenCode 当前公开 issue/源码路径显示 Bash tool 输入包含：

```text
command
timeout
workdir
description
```

其中 `description` 的 schema 文案要求类似“5–10 个词，清晰、简洁描述命令做什么”。

Web UI 的 shell trigger 使用：

```text
title: Shell
subtitle: props.input.description
```

真实 command 放在默认折叠内容中。旧 TUI 也被反馈“只显示 description，不显示 command”，反过来证明 description 已是一等展示字段。

来源：

- https://github.com/anomalyco/opencode/issues/13146
- https://github.com/anomalyco/opencode/issues/14422
- https://github.com/anomalyco/opencode/issues/1736

### 5.2 Claude Code：Bash input 有 optional description

Claude Code Agent SDK 的公开 Bash input 包含：

```text
command: string
timeout?: number
description?: string
run_in_background?: boolean
```

官方 repo issue 也能看到 Bash confirmation/tool input 同时依赖 `command` 和 `description`。

Claude Code 2026 的 `/focus` 视图明确提供“最后用户 prompt + 带 edit diffstats 的单行 tool-call summaries + 最终响应”。

来源：

- https://github.com/anthropics/claude-code/issues/19693
- https://github.com/anthropics/claude-code/issues/30770
- https://code.claude.com/docs/zh-CN/commands

### 5.3 Codex：model-authored progress/preamble

Codex 当前基础 instructions 要求长任务中模型主动给用户 concise progress updates，并要求 tool call 前的消息说明接下来做什么和原因。

这类 update 是模型生成的任务语义，而不是 Harness 从 command 反推。

来源：

- https://github.com/openai/codex/blob/main/codex-rs/protocol/src/prompts/base_instructions/default.md
- https://developers.openai.com/api/docs/guides/latest-model

### 5.4 CodeBuddy：事件层已有 description / completion summary

CodeBuddy Headless 文档的后台 task 事件包含：

- `description / task_type`：started/progress 的任务命令描述；
- `summary`：notification 的 human-readable completion summary；
- `tool_use_id`：关联 tool use；
- `output_file`：读取完整输出。

公开文档没有把 description 的生成者精确写死为主模型，因此这里只记录“数据模型已经区分人类可读 description/summary 与 full output”，不把生成方式过度推断。

来源：

- https://www.workbuddy.ai/docs/cli/headless

### 5.5 Operit：公开提案正在推动 model-generated description

Operit issue #684（2026-07）明确指出现状是工具名 + 原始参数可读性差，并提出参考 OpenCode：

1. LLM 在发起 ToolCall 时生成一句 description；
2. description 是附加元数据，不参与执行；
3. UI 优先展示 description；
4. 原始参数展开查看。

这是公开提案，不是已证明合并的生产事实。

来源：

- https://github.com/AAswordman/Operit/issues/684

## 6. Helix 当前实现差距

当前：

```text
ToolTimelineItem
  ToolTimelineHeading:
    "Tool: <toolName>"
    state
    duration

  ToolPurpose.text(toolName, args)
    write → 写入
    read → 读取
    inspect → 检查
    search → 搜索
    bash → 运行
    + destination/path/source/tabId/name

  collapsed result preview

  expand:
    full canonical args
    bounded result summary
```

优点：

- raw request/result 可审计；
- approval/UNKNOWN/recovery identity 保留；
- 已有 compact/expand；
- `ToolPurpose` 不虚构模型未声明的目的。

缺口：

- Bash 只能显示“运行”，无法表达“查看隔离运行进度汇总”；
- structured tools 也多是“读取 · path”这种功能描述，而不是任务目的；
- UI 第一层仍是 toolName，而不是 Agent activity；
- 结果 preview 偏 raw；
- 多个 ToolCall 没有显式 model-authored activity group。

## 7. Helix Tool Activity 最终裁决

### 7.1 模型生成 intent 是主路径

未来：

```text
Model decides action
  ├─ toolName
  ├─ tool args
  └─ modelIntent = "查看隔离运行进度汇总"

Harness
  ├─ validate/sanitize modelIntent
  ├─ persist ToolCall + modelIntent
  ├─ execute actual tool
  └─ own status/effect/result truth

UI
  ├─ first line: modelIntent
  ├─ second line: tool/status/duration
  └─ expand: command/args/result/audit
```

### 7.2 intent 不是 result

允许：

- “查看隔离运行进度汇总”；
- “检查 Gradle 测试失败原因”；
- “读取 Provider 配置”；
- “比较两个实现差异”。

禁止 pre-execution intent 声称：

- “修复了构建”；
- “测试全部通过”；
- “部署成功”。

`RUNNING / SUCCEEDED / FAILED / DENIED / UNKNOWN / NEEDS_REVIEW` 永远来自 Harness truth。

### 7.3 协议实现建议：reserved presentation metadata

标准 function/tool call 没有通用的跨 Provider display-intent 字段。Helix 可以在**发送给模型的 tool schema**上注入 reserved optional presentation property，例如：

```json
{
  "__helix_intent": "查看隔离运行进度汇总"
}
```

要求：

- Model-facing schema augmentation；
- Provider adapter 解析后提升为内部 `ToolCallPresentation.modelIntent`；
- 在业务 schema validator/Dispatcher 前 strip；
- executor/MCP/Skill 不看到该 reserved field；
- 原始 tool schema 若占用 reserved key，fail closed；
- 不改变 Tool effect classification；
- 不让 intent 参与权限判断；
- intent 有长度、控制字符和 secret redaction 限制。

这与 OpenCode Bash `description` 的思路一致，但 Helix 做成 provider/tool 无关的 Harness metadata，而不是只对 Bash 特判。

### 7.4 fallback

若 Provider/模型不支持、漏填或产生非法 intent：

```text
valid modelIntent
→ ToolPurpose deterministic fallback
→ generic tool label
```

不因为没有 intent 拒绝合法 ToolCall。

### 7.5 UI

默认：

```text
✓ 查看隔离运行进度汇总                  0.4s
  bash
```

展开：

```text
查看隔离运行进度汇总

Tool
bash

Command / Arguments
cat ...

Result
...

Status / duration / call identity
...
```

Pending approval / Failed / UNKNOWN / NEEDS_REVIEW 不能被折叠成“成功 Activity”。

### 7.6 Activity grouping

多个 ToolCall 聚合为一个 Activity 的方向正确，但第一阶段不靠 UI 邻接 heuristic 猜。

只有模型能显式提供 stable activity identity/title 时再聚合。HXA-229 第一阶段先完成 **per-call modelIntent**；grouping 是后续 slice。

## 8. 推荐实施顺序

```text
HXA-228
Conversation-first Shell + Session Context
        ↓
HXA-229
Model-authored Agent Activity Presentation
        ↓
HXA-227
冻结后的 trajectory baseline
        ↓
HXA-230
Markdown-native Hierarchical Agent Memory
```

理由：

- HXA-228 改变 Session input/config contract；
- HXA-229 改变 Tool schema/prompt surface，可能轻微影响模型调用轨迹；
- 两者冻结后再建立 HXA-227 baseline 更稳定；
- HXA-230 直接改变 Context Builder 和记忆注入，应在 baseline 之后实施，才能量化它改善还是污染任务表现；
- HXA-230 完整 project memory 又依赖稳定 Project identity，与 HXA-210 有边界关系。

## 9. 最终产品原则

### Memory

> Memory 是 Agent 与用户共同维护的可读知识，不是隐藏数据库状态。

> Global 记人和跨项目偏好；Project 记项目知识；Session history/Goal/Turn state 继续表达当前工作的真实执行状态。

### Activity

> 对话流首先回答“Agent 在做什么/为什么做”，展开后再回答“具体调用了哪个工具、参数和结果是什么”。

> 模型生成行为语义；Harness 证明是否执行、是否成功、是否有副作用、是否需要审查。
