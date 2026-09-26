# Conversation-first 会话工作台与上下文交互设计

> 日期：2026-09-26。状态：产品/UI 设计裁决，作为 HXA-228 的设计输入。基线为 HXA-226 `2187f05d` 及其模拟器验证证据。本文不代表生产代码已经实现。

## 1. 结论摘要

HXA-226 已完成 Drawer / Settings / recovery authority 收敛，并在 API 36 模拟器上验证没有发现由该任务引入的行为回归。下一轮不再继续机械压缩菜单，而是把 Helix 从“先选会话，再进入 Agent”调整成真正的 **Conversation-first mobile agent workbench**：

1. 正常启动直接进入一个可输入的 Conversation，而不是全屏 Session list；
2. 有有效的上次会话时恢复上次会话，没有时创建 ephemeral new-conversation draft；
3. 会话历史降级到 Drawer 顶部的 New / Search / Recent / All conversations；
4. Composer 的 `+` 成为“给本次消息加内容”和“配置当前会话”的统一快捷入口；
5. Model、Permission 这类高影响会话状态继续在 Composer 附近可见，不藏进二级设置；
6. Expert / Skill / Connector / Mode 是 Session 级 binding；拍照 / 图片 / 文件 / Reference 是当前消息级 input；
7. Session settings 是当前会话配置的完整 authority；全局 Models / Extensions 仍负责安装、账号与连接管理；
8. Permission preset 必须重新校正“名称 ↔ 实际规则”，不能把当前 mutation=ASK 的 `READ_ONLY` 继续叫真正的只读；
9. 消息操作进一步图标化：Copy 维持现状，Edit & resend 改为铅笔图标，Fork 改为分支图标。

## 2. 已验证基线：HXA-226

模拟器验证见：

- `docs/evidence/development/hxa226-simulator-verification-2026-09-26.md`
- 对应 screenshots 目录 `hxa226-simulator-verification-2026-09-26-screenshots/`

关键结论：

- Drawer 一级入口与 primary/secondary Back 模型通过；
- Settings landing 恰好三个 authority；
- Models / Extensions / Setup / Permissions focused surface 均可达；
- 320 / 360 / 412dp × 1.0 / 1.3 / 2.0 字体矩阵没有裁剪、重叠或关键动作不可达；
- active Turn 下 Stop + Send 同排且 Queue / Steer 仍可用；
- 横屏和基础 TalkBack 焦点顺序通过；
- 未发现 HXA-226 引入的 behavioral regression；
- 唯一失败是既有 `TasksDashboardDeviceTest` fixture 没有先展开 Work 分组，不是产品缺陷；
- 真机、TalkBack 手势逐项播报、CANCELLING 瞬态和 process-death 两阶段恢复仍未覆盖。

因此 HXA-228 可以把 `2187f05d` 视为稳定 UI/IA checkpoint，不重新打开 HXA-226。

## 3. 竞品启示

### 3.1 WorkBuddy

WorkBuddy 当前公开 Task Bar 把一个任务/对话作为核心工作台：

- Ask / Craft / Plan 是任务级模式；
- 模型是任务上下文；
- Skills 和 Connectors 可从对话任务中选择；
- 权限区分默认确认与 Full Access；
- 每个任务维护自己的上下文/工作空间；
- 执行中的任务通过侧栏切换，而不是每次启动先进入历史列表。

来源：

- https://www.workbuddy.ai/docs/zh/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Task-Bar
- https://www.workbuddy.ai/docs/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Connector

Helix 不直接复制其 Ask/Craft/Plan 命名；当前 `CHAT / PLAN / ACT / GOAL` 已有更明确的 Harness 语义。

### 3.2 Operit

Operit 当前公开版本已经把 Android Agent 组织成 task-oriented workspace，并强化：

- 附件、工作区上下文、Tool progress、文件变化和多轮任务；
- MCP / Skill；
- 记忆库和历史引用；
- 角色卡 / persona；
- 角色与 model / memory / tools / Skill / MCP 的绑定；
- 对话分支；
- 工具级权限控制。

来源：

- https://github.com/AAswordman/Operit

Helix 应借鉴“Session 是 Agent 上下文容器”，但继续坚持自己的 durable Turn、effect truth、approval 与 successor recovery 边界。

## 4. 启动落点：Conversation-first

### 4.1 正常启动

推荐顺序：

```text
App launch
  │
  ├─ explicit notification / deep link / share target
  │    → 打开明确目标
  │
  ├─ lastViewedSessionId 仍有效
  │    → 恢复上次会话
  │
  └─ 无有效会话
       → 创建 ephemeral New Conversation Draft
          首次有效提交时才 durable persist
```

原则：

- 启动必须落在“可以马上输入”的界面；
- 不因为没有历史会话就先显示一个空 Session list；
- 不为了恢复 UI 去恢复一个已经 execution-terminal 的旧 Turn；
- last viewed session 是导航偏好，不是新的 durable execution truth；
- notification/deep link 的显式目标优先于 last viewed session；
- Share intent 沿用现有“只填草稿、不自动发送”。

### 4.2 New conversation

New conversation 继续复用当前 ephemeral draft 语义：

- draft 可继承合适的当前/默认 Provider；
- 未发送前不需要创建完整历史记录；
- 首次提交才 materialize 成 durable Session；
- 切换/关闭时仍遵守当前 draft/attachment ownership。

## 5. Drawer：历史会话是导航，不是默认页面

建议 Drawer 顶部固定为：

```text
+ New conversation
Search conversations

Recent
  Android Agent 重构
  Provider 设计
  UI/IA review
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

### 5.1 Recent

- 只展示少量最近会话，例如 5～8 个；
- 点击直接切换 Session；
- active session 有明确 selected state；
- 不因为切换 Session 自动 cancel 其他 Session 的后台任务；
- archived session 默认不进入 Recent。

### 5.2 All conversations

保留现有完整 Session list/search/archive 管理能力，但降为 secondary history page：

- search；
- active / archived；
- rename；
- archive / restore；
- 以后可扩展 pin / group，但不是 HXA-228 前置。

因此 Conversation header 不再需要一个常驻 “Back to Sessions” 主动作。

## 6. Conversation Header

目标：

```text
[☰]  当前会话标题                  [Search] [New] [⋯]
```

职责：

- Drawer：全局导航 + recent conversations；
- title：当前 Session 身份；
- Search：当前 Conversation 内搜索；
- New：新建 ephemeral conversation；
- `⋯`：当前 Session details / settings / rename / export / directory / destructive actions。

删除：

- Conversation → full Session list 的常驻 Back；
- 与 Drawer history 重复的主入口。

返回键继续遵守 Android modal/focus/back stack，不用 header 伪造额外层级。

## 7. Composer：`+` 是统一上下文入口

目标结构：

```text
selected context chips when non-default
Android Expert · 2 Skills · GitHub

┌──────────────────────────────────────┐
│ 输入消息…                            │
│                                      │
│ [+] [Act] [Model] [Permission] 🎤 ■ ▶│
└──────────────────────────────────────┘
```

- idle：只有 Send；
- active Turn：Stop + Send 同排；Send 仍是 Queue / Steer 提交；
- Mode / Model / Permission 必须有可发现的当前值；
- selected Expert / Skill / Connector 只在非默认时用 compact context chips 展示。

### 7.1 `+` sheet

必须区分两个生命周期组。

#### 添加到本次消息

```text
拍照
图片 / 视频
文件
引用
  记忆
  其他会话
```

这些只影响当前 composer submission。

#### 配置此会话

```text
专家
模式
技能
连接器
```

这些修改 Session config，影响后续接受的 Turn，不 retroactively 改历史 Turn。

### 7.2 为什么 Mode 既可见又能从 `+` 进入

Mode 是高影响状态，因此当前值应保持可见；`+ → 模式` 是 contextual shortcut，点击 Mode chip 也应打开同一个 selector。二者指向同一个 Session config authority，而不是两份状态。

Model / Permission 同理，但不建议把它们只藏在 `+` 中：

- Model 决定请求目标；
- Permission 决定 Tool effect 的自动允许 / ask / deny；
- 用户在发送前应能一眼看到当前值。

## 8. 生命周期矩阵

| 项目 | 生命周期 | 当前入口 | 完整 authority | 对 active Turn 的影响 |
| --- | --- | --- | --- | --- |
| 拍照 / 图片 / 文件 | message | `+` | composer staging | 无，下一 submission 才使用 |
| 引用记忆 / 会话 | message | `+` | reference picker | 无，submit 时 materialize snapshot |
| Expert | session | `+` / chip | Session settings | 不修改 active Turn |
| Mode | session | `+` / chip | Session settings | 不修改 active Turn；下一 Turn snapshot |
| Skill | session | `+` | Session settings | 不修改 active Turn |
| Connector | session | `+` | Session settings | 不修改 active Turn |
| Model | session | visible chip | Session settings；全局管理在 Models | 不修改 active ModelCall |
| Permission | session | visible chip | Session settings；全局默认在 Settings | tighten 仍按既有线性化规则处理 |

## 9. Session settings：当前会话配置的唯一完整页面

Header `⋯ → Session settings`：

```text
Session settings

Expert
  Android development expert

Mode
  Act

Model
  Qwen ...

Permissions
  Ask before changes

Skills
  Android
  Git

Connectors
  GitHub

Memory / references
  [仅在真实 memory backend 存在时显示]

Workspace
  [HXA-210 决策完成后接入]
```

### 9.1 Global management 与 Session binding

必须继续遵守 HXA-226 的 authority：

- Models：新增/删除 Provider、账号、endpoint、connection/capability；
- Session settings：只选择当前 Session 使用哪个已可用 model/provider；
- Extensions：安装/删除/update Skill/Connector；
- Session settings：只绑定/启用当前 Session 的 Skill/Connector；
- Settings / Permissions & safety：默认 permission 与全局安全管理；
- Session settings：只管理当前 Session 的 permission snapshot。

## 10. Expert 语义

当前源码没有 Expert / Persona domain，因此不能只做一个 UI 菜单假装功能存在。

推荐定义：

> Expert = 一个 Session 同时最多绑定一个的 behavior preset。

最低字段：

```text
ExpertProfile
  id
  displayName
  instruction/profile content
  optional recommendedSkillIds
  optional recommendedConnectorIds
  optional recommendedMode
```

约束：

- Expert 可建议 Skill / Connector / Mode，但不能静默启用高权限能力；
- Expert 永远不能修改 Session Permission；
- recommended model 只能作为 suggestion，不能绕过用户当前 model selection；
- Expert 只影响以后接受的请求；
- 历史 Turn 继续保存当时实际 prompt/context facts；
- 如果实现时发现 Expert 需要 marketplace / import / trust model，应拆独立 ADR/HXA，不把复杂角色系统偷偷塞进 HXA-228。

## 11. Skill / Connector

### Skill

`+ → Skills`：

- 多选已安装 Skill；
- 只做 Session enable/disable；
- 提供 `Manage Skills → Extensions`；
- 不在 sheet 中安装、删除或更新 Skill。

### Connector

`+ → Connectors`：

- 多选已配置并可用 Connector；
- 只做 Session binding/enablement；
- OAuth、endpoint、credential、install/update 继续归 Extensions；
- binding 不等于自动批准 Tool effect。

## 12. Mode

继续复用当前：

```text
CHAT
PLAN
ACT
GOAL
```

用户文案建议：

- Chat：问答与分析，默认无工具；
- Plan：只读研究并生成 PlanArtifact；
- Act：执行当前任务；
- Goal：持续推进长期目标。

约束：

- mode change 只影响未来 admission；
- active Turn 的 mode snapshot 不变；
- PLAN 继续受当前 read-only / metadata operation gate；
- GOAL 不因为 UI 选择而绕过 approval；
- 是否把 Act 设为新 Session 默认值，应由产品默认值单独裁决，不在 UI 组件里硬编码。

## 13. Reference：必须 snapshot，不用 live pointer

`+ → Reference` 是 message-scoped input。

### 13.1 Other conversation

推荐流程：

```text
Reference
  → Other conversation
  → Search/select session
  → select bounded messages / available summary
  → composer shows reference chip
  → submit
  → materialize immutable bounded content snapshot
```

不能让已经 accepted 的 Turn 持有 “session-X 当前内容” 这种 live pointer。

### 13.2 Memory

当前 Helix 没有真正的长期 Memory repository/domain。

因此：

- 设计中保留 `Reference → Memory`；
- 生产 UI 只有在真实 memory backend 存在时才显示；
- HXA-228 不得创建一个只有静态文本的假 Memory 菜单；
- 如果要在 HXA-228 同时交付长期记忆，需要单独扩大范围并明确 storage/trust/deletion contract。

## 14. Session Permission：重新校正用户心智

当前代码：

```text
FULL_ACCESS
WORKSPACE
READ_ONLY
CUSTOM
```

但当前 `READ_ONLY` 实际规则不是“写操作 DENY”，而是：

- workspace read = ALLOW；
- external read = ASK；
- workspace/external mutation = ASK；
- remote business mutation = ASK；
- device mutation = ASK；
- command = ASK。

因此从产品文案看，它实际上更接近 **Ask before changes / Approval required**，不是真正只读。

### 14.1 推荐 clean-slate preset

HXA-228 开始实现前必须先更新 ADR-PERMISSIONS-001，推荐：

| Preset | 用户心智 | 基本规则 |
| --- | --- | --- |
| Approval required | 默认；敏感/修改操作询问 | 当前旧 READ_ONLY 的语义可迁到这里 |
| Workspace trusted | Workspace 内更少确认 | Workspace 内 read/write allow；workspace 外与 command/system/remote mutation 默认 ask |
| Full access | 尽可能自动执行 | effect allow，但仍受全局 hard-deny / credential / special destructive rule |
| Read only | 真正只读 | mutation / command / remote write / system mutation deny；合法 read 按 scope 执行 |
| Custom | 高级用户 | 每个 OperationEffect 显式 ALLOW / ASK / DENY |

重点：

- Permission preset 不应继续把“只读”和“需要批准”混成一个名字；
- Full access 不是绕过所有 global safety invariants；
- Workspace trusted 是否允许 remote business mutation 必须重新裁决；当前 preset 允许，和名称并不完全一致；
- 这是 policy contract change，不只是 UI rename，必须先改 ADR 和测试再改 UI。

Composer 常驻文案可简化为：

```text
🛡 Ask
🏠 Workspace
🔓 Full
🔒 Read only
⚙ Custom
```

但 accessibility label 使用完整名称。

## 15. 消息操作图标化

当前代码已经：

- Copy = `ic_chat_copy`，成功后切 `ic_check`；
- Share 已有独立 action；
- Edit & resend 仍是文字按钮；
- Fork 仍是文字按钮。

HXA-228 目标：

```text
[Copy icon] [Share] [Edit pencil] [Fork/branch]
```

具体：

### Edit & resend

- 使用 pencil / edit icon；
- 48dp touch target，icon 20～24dp；
- contentDescription = “编辑并重发”；
- 保留原子语义：不是只改历史显示，而是 HXA-215 的 edit + resend flow；
- 不修改现有 durable fork/revision semantics。

### Fork

- 使用 branch / account-tree / git-fork 风格图标；
- 不使用 Share 图标；
- 48dp touch target；
- contentDescription = “从此消息创建分支”；
- 不改变当前“继承所选位置前完整历史、文件不复制/回滚”的语义。

### Copy

维持当前实现：

- normal = copy icon；
- success = check icon；
- 不改行为。

## 16. Durable / ownership 不变量

HXA-228 不能因为 Session UX 变得更丰富就重新制造第二 owner：

1. UI 只编辑 Session config/application state；
2. active Turn 继续由 TurnEngine durable owner；
3. Session config change 不 retroactively mutate active Turn / existing ModelCall；
4. Tool approval 仍由统一 Dispatcher / Policy / Approval；
5. Skill/Connector enablement 不等于 effect approval；
6. Reference 在 submission acceptance 时 materialize bounded snapshot；
7. Expert 不拥有 Tool 权限；
8. history drawer 不负责 cancel/resume Turn；
9. lastViewedSessionId 是 UI navigation preference，不是 execution truth；
10. app process death 后不恢复一个旧 open Turn；仍按 successor-Turn recovery。

## 17. 推荐 route / surface

```text
conversation                 primary launch surface
conversation/history         all conversations
conversation/settings        session settings

+ sheet
  message/
    camera
    photo
    file
    reference
  session/
    expert
    mode
    skills
    connectors

global
  models
  extensions
  setup
  settings
```

可以保留内部现有 route 名作为实现细节，但产品导航语义应按上述结构收敛；项目尚未上线，如果 route rename 能明显简化实现，可直接 clean-slate 调整。

## 18. 不做的事情

HXA-228 不应顺手做：

- Workspace binding HXA-210；
- HXA-222 local model runtime；
- HXA-225 vision feedback；
- Subagent；
- 新 Tool permission engine；
- HXA-229 的 model-authored Tool/Activity presentation；
- HXA-230 的 Markdown-native long-term Memory backend；本任务只在真实 backend 已存在时显示 Memory reference 入口；
- Expert marketplace / 第三方角色包格式；
- bottom navigation。

Memory 与 Tool Activity 的竞品实现、最终格式与任务拆分见 `docs/research/agent-memory-and-activity-presentation-2026-09-26.md`。HXA-228 不再承担这两个系统的实现。

## 19. 推荐实施顺序

```text
D1  Session launch/history authority
D2  Session config contract + permission ADR
D3  Drawer recent conversations + conversation-first launch
D4  Session settings
D5  Composer + sheet
D6  Expert / Skill / Connector / Mode binding
D7  message reference + camera/photo/file
D8  message action iconization
D9  simulator/device regression
```

强模型负责：

- D1/D2；
- permission preset contract；
- Expert/session config storage shape；
- reference snapshot boundary；
- active Turn vs future Turn linearization；
- review D3～D8。

小模型适合：

- Drawer/Compose mechanical layout；
- string/icon/testTag/resource migration；
- camera/photo picker wiring；
- focused AndroidTest；
- simulator layout matrix。

## 20. 验收重点

### Startup/history

- cold launch 有上次有效 Session → 直接进入该 Conversation；
- 无历史 → 直接进入 new draft；
- explicit deep link/share 优先；
- Drawer recent / search / all conversations 可达；
- 不需要先进入 full session list。

### Session config

- Expert/Mode/Skills/Connectors/Model/Permission 修改只影响未来 Turn；
- active Turn 不变；
- activity recreation / process reopen 后 durable session config 一致；
- global management 与 Session binding 不重复。

### Composer

- `+` 两个 lifecycle group 可理解；
- 320/360/412dp × 1.0/1.3/2.0；
- IME / 横屏 / TalkBack；
- active Stop + Queue/Steer Send 不退化；
- selected context chips 有界、可滚/折叠而不挤掉 Send。

### Permission

- preset label 与实际 ALLOW/ASK/DENY 完全一致；
- Full access 不突破 hard deny；
- Read only 确实不能通过一次 approval 执行 mutation；
- tighten/loosen linearization 保持现有保障。

### Message actions

- Edit / Fork icon 48dp；
- TalkBack label 明确；
- Copy success feedback 保持；
- edit/resend/fork durable behavior不变。
