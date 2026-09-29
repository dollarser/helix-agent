# UI、IA 与移动工作台

> 原研究基线：2026-09-26；实施归属核对：2026-09-29。下文保留 Conversation-first 的设计解释和示意，不作为尚未开发的 UI 清单。[HXA-228](../../completion-records/HXA-228.md)与[HXA-229](../../completion-records/HXA-229.md)已有交付；当前状态、后续输入恢复和设备覆盖分别看[status](../../development/status.md)。详细原方案已归[历史设计](../../evidence/research-history/conversation-first-session-workbench-2026-09-26.md)。

## 1. 当前产品方向

Helix 不应该变成“手机 IDE 缩小版”，也不应该退回纯聊天。目标是：

> **Conversation-first mobile agent workbench**：Conversation 是默认任务入口；Session 承载模型、模式、权限、Expert/Skill/Connector 等上下文；Files/Tasks/Artifacts/Browser/Terminal/Git 是围绕任务的专业视图。

这和 WorkBuddy 当前 task workbench、Operit 的 task-oriented agent workspace 演进方向一致，但 Helix 继续坚持自己的 durable Turn、effect truth、approval 和 successor recovery。

## 2. HXA-226 已验证基线

HXA-226 已完成：

- Drawer 只保留 primary authorities；
- Models / Extensions / Setup / Settings 分工；
- Readiness / Capabilities 只 deep-link 唯一 management authority；
- Settings landing 不再嵌 Provider/Extension/Runtime 长列表；
- active Turn 下 Stop + Send 同排，Send 保留 Queue/Steer；
- primary route 用 Drawer，secondary route 用 Back。

模拟器验证：

- API 36 arm64 emulator；
- consumer + developer；
- 320/360/412dp × 1.0/1.3/2.0；
- 横屏；
- 基础 TalkBack focus；
- 没有发现 HXA-226 引入的 behavioral regression。

证据：[hxa226-simulator-verification-2026-09-26.md](../../evidence/development/hxa226-simulator-verification-2026-09-26.md)。

唯一失败是既有 `TasksDashboardDeviceTest` fixture 没有先展开 Work group，不是产品回归。真机、CANCELLING 瞬态、TalkBack 手势逐项播报和两阶段 process-death recovery 仍未覆盖。

## 3. Conversation-first 启动原则

原研究曾把默认完整 Session list 列为待改造项；该方向已由 HXA-228 承接。以下是当时的设计路径，具体恢复优先级和输入缓存以当前交付为准，不重复启动这一轮 UI 重构：

```text
explicit deep link/share
    ↓
目标 Conversation

否则 last viewed session 有效
    ↓
恢复上次 Conversation

否则
    ↓
ephemeral new-conversation draft
```

原则：

- App 打开即可输入；
- full Session list 不再是默认页面；
- new draft 沿用“首次提交才 durable persist”；
- last viewed 是导航偏好，不是 execution truth；
- 不恢复旧 open Turn，process death 仍走 successor recovery。

## 4. Drawer 变成“历史 + 全局工作台”

推荐：

```text
+ New conversation
Search conversations

Recent
  Session A
  Session B
  ...

All conversations >

────────────
Work
  Tasks
  Artifacts
  Files
  Git
  Browser
  Terminal

Configure
  Models
  Extensions
  Setup

Settings
```

full Session list 仍保留 search/archive/restore，但作为 secondary history management page。

Conversation header 因此不再需要常驻 “Back to Sessions”；Drawer 已经承担 session switching。

## 5. Conversation Header

目标：

```text
[☰] 当前会话标题               [Search] [New] [⋯]
```

`⋯` 进入：

- Session settings；
- rename；
- export；
- directory；
- 其他低频 session action。

Header 不承担 Provider/Runtime/permission 的完整管理。

## 6. Composer 与 `+`

Composer 是 Session 上下文的最高频控制面：

```text
Android Expert · 2 Skills · GitHub

┌──────────────────────────────────────┐
│ 输入消息…                            │
│ [+] [Act] [Model] [Permission] 🎤 ■ ▶│
└──────────────────────────────────────┘
```

### `+` sheet

必须区分生命周期。

**添加到本次消息**

- Camera；
- Photos；
- File；
- Reference；
  - Memory（真实 backend 存在时才显示）；
  - Other conversation。

**配置此会话**

- Expert；
- Mode；
- Skills；
- Connectors。

Mode / Model / Permission 的当前值应可见；`+` 里的入口只是 contextual shortcut，不是另一份状态。

## 7. Session settings

Session settings 是当前 Session config 的唯一完整页面：

```text
Expert
Mode
Model
Permissions
Skills
Connectors
Memory / references   # 条件存在
Workspace             # 等 HXA-210
```

继续保持 global management 与 Session binding 分离：

- Models 管 Provider/account/endpoint；Session settings 只选当前模型；
- Extensions 管 Skill/Connector install/config；Session settings 只绑定当前会话；
- Settings 管默认 Permission/Safety；Session settings 管当前 Session snapshot。

## 8. 生命周期必须显式

| 能力 | 生命周期 |
| --- | --- |
| Camera / Photo / File | 当前 message |
| Reference | 当前 message；submit 时 materialize snapshot |
| Expert | Session |
| Mode | Session / future Turn |
| Skill | Session |
| Connector | Session |
| Model | Session / future ModelCall |
| Permission | Session；仍遵守 tighten/loosen 线性化 |

active Turn 不因这些 UI 变更 retroactively 改写。

## 9. Expert / Skill / Connector

### Expert

当前没有 Expert domain。下一轮若实现，先定义一个 Session-level behavior preset；一个 Session 最多一个 primary Expert。

Expert 可以建议：

- Skill；
- Connector；
- Mode。

Expert 不能：

- 自动提升 Permission；
- 绕过当前 Model；
- 绕过 Tool Policy/Approval。

### Skill / Connector

Session 内只绑定已安装/已配置项。安装、OAuth、endpoint、删除/update 仍归 Extensions。

Session enablement 永远不等于 Tool effect approval。

## 10. Mode

继续使用现有：

- CHAT；
- PLAN；
- ACT；
- GOAL。

不复制 WorkBuddy 的命名，只借鉴“任务级模式在 Composer 可见、可快速切换”的交互。

mode change 只影响 future Turn；active Turn snapshot immutable。

## 11. Reference

Reference 不能是 live pointer。

Other conversation：

```text
select session/messages
  ↓
composer reference chip
  ↓
submit acceptance
  ↓
immutable bounded content snapshot
```

Memory 只有 HXA-230 的真实 Markdown-native memory backend 存在时才展示。不能先做一个空“记忆”菜单。Memory 的 global/project scope、`MEMORY.md`/topic Markdown 与 progressive disclosure 见 `../agent-memory-and-activity-presentation-2026-09-26.md`。

## 12. Session Permission 需要产品语义修正

当前：

```text
FULL_ACCESS
WORKSPACE
READ_ONLY
CUSTOM
```

但当前 `READ_ONLY` 对 mutation/command 是 ASK，不是真正 DENY，因此用户心智更接近 “Ask before changes”。

推荐下一轮在 ADR-PERMISSIONS-001 中重新裁决：

- Approval required；
- Workspace trusted；
- Full access；
- Read only；
- Custom。

其中真正的 Read only 必须 deny mutation/command；不能一次批准后又执行写操作。

这是 policy contract change，不是纯 UI rename。

## 13. 消息动作

当前 Copy 已是 icon，并在成功后切 check。

下一轮：

- Edit & resend → pencil/edit icon；
- Fork → branch/account-tree/git-fork icon；
- 48dp touch target；
- 完整 TalkBack label；
- 不改变 HXA-215 edit/resend 或 HXA-213 branch durable semantics。

Share/Regenerate 是否进一步 icon 化可以后续按实际密度判断，不作为本轮强制范围。

## 14. Tool/Artifact 仍沿用现有方向

保留：

- ToolCall compact row；
- request/result/approval 身份；
- UNKNOWN/review 不隐藏；
- Artifact 就地预览；
- command/detail/Files/Terminal 专业页面。

不要为了 Conversation-first 把全部专业页面塞回 Chat。

ToolCall 的下一轮优化归 HXA-229：默认对话流应优先显示模型在发起 ToolCall 时生成的行为 intent，例如“查看隔离运行进度汇总”；Harness 继续提供 running/success/failure/approval/UNKNOWN 等事实，原始命令和结果折叠在详情。当前 `ToolPurpose` 只作为缺失 intent 的 fallback。

## 15. 竞品启示

### WorkBuddy

当前官方 Task Bar 明确展示 task mode、model、Skills、Connectors 和 permission，并通过侧栏切换任务。值得借鉴的是“任务上下文在输入区可见”，不是具体 Ask/Craft/Plan 命名。

来源：https://www.workbuddy.ai/docs/zh/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Task-Bar

### Operit

当前 Android 版强调 task-oriented workspace、附件/工作区上下文、tool progress、MCP/Skill、记忆、角色卡、分支和工具权限。值得借鉴的是 Session 作为能力绑定容器。

来源：https://github.com/AAswordman/Operit

## 16. 冲突裁决

- Session list vs Conversation-first：Conversation-first；history 降为 Drawer/secondary page；
- `+` 是否等于一种状态：否；它只是不同生命周期能力的统一入口；
- Model/Permission 是否藏进 `+`：否；保持当前值可见；
- Expert 是否能自动授权：否；
- Reference 是否 live：否，submit-time snapshot；
- READ_ONLY 是否继续 mutation=ASK：不建议，下一轮通过 Permission ADR 解决；
- bottom nav vs drawer：继续 grouped drawer；
- 后续顺序：HXA-228 先冻结 Session input/config；HXA-229 再冻结 model-authored Tool presentation schema；HXA-227 建立 trajectory baseline；HXA-230 最后接入 Markdown-native Memory，并用 baseline 评估长期记忆的收益与污染。
