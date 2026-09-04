# Helix 小模型实施指南

本指南面向约 20B–30B 参数级的编码模型，包括用户计划使用的 Qwen 系列模型。具体模型 ID 由实际 Provider 决定，文档不假定“Qwen3.8-27B”一定是正式且可用的名称。

## 1. 给清晰目标，不遥控每个实现细节

Helix 同时涉及 Android 生命周期、模型流协议、文件系统、动态代码和权限。让小模型一次“实现整个 App”容易出现：

- UI 直接调用网络/DAO。
- 把模型输出当授权。
- 在主进程执行生成代码。
- 用 happy-path 演示替代恢复和攻击测试。
- 为解决编译错误擅自升级依赖或删测试。
- 复制参考仓库导致许可证问题。

正确方法不是在 Prompt 中重复大量禁止项，而是把稳定架构留在仓库文档中，把当前里程碑设为长程 Goal，再把每个 HXA 作为可验证 checkpoint。模型可以自行决定类和私有函数如何拆分、测试 fixture 放在哪里、采用哪种简单惯用写法；只要不改变公开边界，并用实际测试证明结果。

## 2. 模型每次任务必须读取的材料

最小集合：

1. 根 [README](../../README.md)。
2. 根 `AGENTS.md` 与[当前实施状态](status.md)。
3. [总体技术方案](../architecture/overview.md) 中与任务相关章节。
4. [技术路线](roadmap.md) 中当前任务及前置任务。
5. 当前模块的 README/API/测试。

处理 Provider/MCP/A2A/Skill/Plan/Goal 时读取 [专项方案](../architecture/provider-mcp-skills-modes.md)；处理 A2A 时还要读取 accepted [ADR-0016](../adr/0016-a2a-client-interoperability.md)。ADR 接受不等于 SDK 或实现通过：必须先执行 HXA-077 Spike 并选出有证据的可行实现，才能开始 HXA-078/079 生产 bridge。处理浏览器、文件、Accessibility、Root 时读取 [Android 平台能力](../architecture/android-platform-capabilities.md)；处理 QuickJS/PRoot/CLI Runtime 时读取本地执行方案。任务触发架构决定或改变既有决定时，再读取 [ADR 约定](../adr/README.md) 和相关 ADR。不要每次把所有文档塞进上下文。

需要比较 Agent 设计时再读取[主流 Coding Agent / Harness 参考](../references/open-source-projects.md#511-主流-coding-agent--agent-harness-设计参考)，并遵守以下顺序：

1. 先写清当前 HXA 的具体问题，例如“tool result 如何进入事件流”，不要泛泛地“研究 Claude Code”。
2. 只选择 1～2 个能回答该问题且仍可核实的参考；优先官方仓库、协议、测试和安全说明。
3. 摘录可验证的 invariant、接口形状或攻击/恢复用例，不复制整段实现，不依赖二手架构图。
4. 把桌面 shell、Git、插件、OAuth 和文件权限假设逐项映射到 Android scope/Policy/Approval/UID 生命周期；无法映射就不采纳。
5. 最终方案以 Helix 当前规范、代码和测试为准；若参考方案要求改变已接受边界，起草 proposed ADR 并暂停，不得以“主流工具这样做”直接改写。

## 3. 主控工作流

```text
建立一个里程碑 Goal
  → 选择该 Goal 的第一个 HXA checkpoint
  → 检查前置任务和工作树
  → 检索相关 ADR 并判断本任务是否触发决策记录
  → 如需外部参考，只围绕当前问题核实 1～2 个官方实现/协议
  → 简短列出计划和实际需要修改的范围
  → 实现最小纵向切片
  → 运行局部测试
  → 运行任务验收命令
  → 写完成记录并更新短状态
  → 自动进入下一个 checkpoint
  → 在里程碑末做独立 Codex/人工审查
```

实现和审查最好使用两个独立上下文。长程 Goal 不等于一次修改多个 HXA；它只让模型在一个 checkpoint 验收后继续工作，减少人工反复发“继续”。审查模型先看 diff、需求和测试结果，再看实现模型的说明。

## 4. 通用实现 Prompt

```text
请实现 Helix 的 <HXA-ID>。先读取 AGENTS.md、README.md、
docs/development/status.md、路线中的任务原文，以及与本任务直接相关的架构章节和现有代码。

在既定架构内自行选择简单、主流、可测试的实现。补齐正常、非法输入和任务原文要求的失败/恢复测试，运行 verification matrix 的真实命令，修复问题直到通过。不要用删除测试、伪造成功或提交 Secret 换取验收。

完成后写 `docs/completion-records/HXA-ID.md`，并同步 `docs/development/status.md`：列出实际修改、命令、exit code、未实现范围和 ADR 状态。如果发现必须改变既定架构、安全边界或关键依赖，先给出具体冲突和候选方案，再暂停请求审查；普通可逆实现选择直接推进。
```

## 5. 只读审查 Prompt

```text
请只读审查 Helix 任务 <HXA-ID> 的当前 diff，不修改文件。

依据：
- 该任务需求和验收标准
- docs/architecture/overview.md
- 如涉及执行/权限，docs/architecture/local-code-execution.md 和 docs/security/testing-and-release.md

按严重程度报告：
1. 会造成越权、数据泄露、重复副作用或错误成功状态的问题；
2. 违反模块边界或状态机的问题；
3. 缺少的失败、取消、恢复、边界测试；
4. 不可复现依赖、许可证或构建问题；
5. 一般正确性问题。

每项必须给出文件、具体符号/行、触发条件和最小修复方向。不要报告纯风格偏好。若无问题，明确列出已检查的验收点和剩余人工验证项。
```

## 6. 每个任务的输入包

给模型的任务上下文应包含：

- 任务 ID 和原文。
- 前置实现状态或接口。
- 最相关的 production/test 文件入口；模型可继续检索实际依赖，不把初始列表当文件白名单。
- 精确验收命令。
- 已知失败输出（如有），不要只说“编译错误”。

不要给：

- 整个第三方仓库源码。
- 与当前任务无关的长日志。
- API Key 和用户数据。
- “你自由发挥做完整产品”类开放指令。

## 7. 任务粒度示例

不合格：

> 实现 Tool 系统和所有手机能力。

合格拆分：

1. 实现 `ToolDescriptor` 和重复注册测试。
2. 实现 Schema 子集和 unknown keyword 拒绝测试。
3. 实现 canonical JSON 与 approval hash。
4. 实现一次性 approval consume 并发测试。
5. 实现 `time.now` 测试工具。
6. 实现 Dispatcher 顺序和超时测试。
7. 最后做 UI 审批卡集成。

文件数量不是完成质量指标。优先小而完整的纵向切片；如果任务本身需要 production、fixture、测试和文档共同变化，可以修改更多文件，但不要顺带重构无关模块。新的跨模块接口应由当前 HXA 原文或既有架构直接支持。

## 8. 小模型容易犯错的固定检查

### Android

- 是否在 Composable 中创建 Repository/Client？
- 是否把 Activity/Context 泄漏到进程级对象？
- 是否认为 Coroutine 进入 `Dispatchers.IO` 就能长期后台存活？
- 是否把 WorkManager 当实时长连接？
- Service/Receiver 是否明确 exported？

### Agent

- 是否把 assistant 文本中的 JSON 当 ToolCall？
- 是否忽略 tool arguments streaming 分片？
- 是否在 ToolResult 前把 Turn 标成 completed？
- 是否在恢复时重放有副作用工具？
- 是否没有 step/output/context 上限？
- 是否把 Plan 当作普通文本，没有保存 artifact/hash？
- 是否把 Goal 预算耗尽或无证据状态标成 completed？
- 是否让模型/MCP/A2A/Skill 自报“并发安全”？并发必须由规范化参数的 effect footprint 决定，未知/写/代码/UI/Root 默认排他。
- 并发结果是否按完成速度而不是原始 call sequence 回填模型？取消后未启动项是否从持久状态消失？两者都不允许。
- Tool 失败后是否自动扩大 scope/权限、切换低隔离 target、请求 Root/LAN，或先联网再补审批？必须 fail closed 或创建新的明确审批。

### 安全

- 是否在主进程执行 QuickJS？
- 是否把 PRoot 放在主 App 的同 UID 子进程，而不是独立 Runtime APK？
- 是否误把“本机执行”理解成主进程执行，或把 isolated process/PRoot 宣称为 VM？必须写清 CPU/内核、UID、权限、网络与数据入口。
- 是否给 JS 注册了文件、网络或 Android bridge？
- 是否只靠 Prompt 决定权限？
- 是否审批后重新生成/修改了参数？
- 是否记录了 Header、Key、通知或文件正文？
- 是否让 MCP annotation 或 Skill 指令降低风险？
- 是否让官方 CLI token 进入主 App/Room/日志？
- 是否把 All-files 系统授权等同于 Agent 可访问整个共享存储？
- 是否在启动时触发 Root 请求，或把 `root.exec` 放进普通工具表？
- 是否把 `consumer/developer` 构建变体、`STANDARD/ADVANCED` 运行配置和 Google Play/国内商店/官网渠道合成了一个布尔值？Standard 是完整商店产品；当前 flavor 只是实现机制。
- 是否承诺 Standard 能从 Android 系统设置隐藏 developer 已声明的 All-files/Accessibility 组件？只能承诺默认关闭、不自动启用和无 Agent scope。
- Advanced 高敏出网规则是否使用固定 1h/24h/7d/30d（默认 24h、最大 30d）、不滑动续期，并在到期或时钟回拨时 fail closed？
- 是否因为抽象安全偏好或未经核验的商店猜测裁剪 Standard？每个渠道差异必须指向提交时的政策原文或审核反馈，并保持同一产品身份、数据模型和核心任务矩阵；applicationId/渠道命名由 HXA-122 决定。
- 是否把 PRoot/CLI companion 写成第二个主应用，或让它复制主 App 数据？companion 只通过受保护 IPC 接收有界 Job。
- 是否要求用户先打开/常驻 Runtime，或把“进程当前存在”当成 Tool 可用条件？只有用户点击的零 Job 验证/修复/登录，或批准后的真实 Job，才能显式 `BIND_AUTO_CREATE` 冷绑定；应用启动、切换 Advanced 和被动 Registry 刷新不得启动 Runtime，空闲允许回收。
- Binder death 后是否直接重提 argv/script？必须先按 jobId 查询并核对 input hash/terminal proof；未知结果停泊 `INTERRUPTED`，不得自动重放。
- 是否把任意 PRoot/CLI 计算标成 `dataSync` 前台服务，或认为 FGS 永远不会被杀？类型必须匹配真实用途；无合法类型就前台有界并在退后台暂停/取消。
- 如使用 wake lock，是否只存在于用户可见 FGS 的 RUNNING 窗口、带硬 timeout，并在成功/失败/取消/timeout/异常路径全部释放？
- Runtime journal 是否限制为 128 条/1 MiB metadata，额度满时拒绝新 Job而不是删除 active/未对账证据，并按 7 天已对账 tombstone/30 天未对账 evidence-expired 清理？
- 是否让 Advanced 跳过 schema/Policy/Approval、Secret/UID 隔离、审计、敏感界面拒绝或变更后验证？Advanced 只能扩大显式 scope 内能力。
- 是否因切换 Advanced 自动申请权限、安装/启动 PRoot、请求 Root、连接 LAN 或扩大 Tool Registry？这些必须分别由用户启用。
- 是否把 `DENIED` 或任意非空 decision/consumedAt 当作可消费的 Approval Proof？只有类型化批准能授权。
- 是否新增 `FULL_ACCESS`、自动批准、模型代用户批准，或把 Advanced/Profile/系统权限/Root grant 当成 Approval Proof？这些路径都禁止。
- URL 检查是否只解析一次做分类，却让 transport 在建连时走未验证的二次 DNS？必须限制到已验证地址集合并逐跳复验。

### Browser/Accessibility

- 是否对不可信 WebView 注册永久 privileged `addJavascriptInterface`？
- 是否用模型生成 JavaScript 直接控制 WebView？
- browser node token 是否绑定 tab/origin/navigation generation/TTL？
- Accessibility token 是否绑定 package/window/generation？
- 目标 App 或窗口变化时是否暂停？
- 是否拒绝支付、认证、系统授权、安装器和 Root 管理界面？

### MCP/A2A/Skills/Provider

- 是否把 Responses、Chat Completions 和 Anthropic Messages 混成猜测式 adapter？
- 是否只测文本，没有测分片 ToolCall？
- MCP schema hash 变化后旧审批是否仍有效？
- A2A Agent Card/Skill/endpoint/interface/version 变化后旧 Tool、规则和审批是否失效？断线是否只对账原 task ID，而不是重发？
- A2A 远端输出是否保持不可信，且无法获得本机 Capability/Approval/Secret 或反向调用 Tool？
- Skill 是否在 discovery 阶段把全部正文/资源塞入 context？
- Skill 脚本是否绕过了正常的 code/bash Tool？
- 是否把 ChatGPT/Claude 订阅当作可直接使用的 API key？
- 是否根据 Ollama/SGLang/“自建”模板名称猜测数据驻留位置？必须按规范化实际 endpoint 分类。
- Standard 是否允许高敏数据永久放行？Advanced 规则是否遗漏 Provider/MCP ID、origin、数据类别、scope、有效期或撤销？
- API key、OAuth token、Cookie、密码、验证码或认证字段是否存在任何 Advanced 放行路径？这些始终拒绝发送。

### 文件

- 是否把 PRoot 里存在 `git` 二进制误写为持久 Git 管理？`.git` 权威位置和原子交换在 ADR-0008 接受前未定，禁止提前做 Git UI、remote Git 或凭据流。
- 是否提前实现 subagent、Agent graph 或 Workflow DSL？ADR-0009 仍 proposed；HXA-105 前保持单 Agent，不复制云端任务、自修改插件、递归/peer Agent 或可执行 JS/Starlark 编排。
- 是否直接拼 `File(../root, userPath)`？
- 是否跟随了越界 symlink？
- 是否直接覆盖，没有临时文件/hash/conflict？
- 是否信任 ContentProvider 的 MIME/size/display name？

### 测试

- 是否只测 happy path？
- 是否使用真实网络导致不稳定？
- 是否为通过测试增加 sleep？
- 是否把异常 catch 后返回成功？
- 是否在没有 HXA-122/迁移 ADR 和代码证据时重命名 flavor、交换 applicationId，或声称不同 applicationId 可以原地升级？

## 9. 测试反馈策略

给模型提供最小完整失败：命令、exit code、首个根因及相关上下文。不要一次贴数万行。

修复顺序：

1. 编译错误。
2. 当前模块 unit test。
3. 相关模块 test。
4. lint/static checks。
5. app build。
6. instrumentation/真机。

禁止做法：

- 用 `-x test` 绕过。
- 把 lint 改为 warning。
- 删除 assertion。
- 把 timeout 无限制调大。
- 为一个测试引入全局 mutable singleton。

## 10. 完成与提交边界

用户授权创建提交时，建议一个 HXA 任务一个 commit：

```text
feat(../agent): implement deterministic turn reducer [HXA-011]
test(../workspace): cover symlink escape and atomic conflicts [HXA-040]
feat(../runtime): add isolated QuickJS execution service [HXA-051]
```

无论是否提交，每个 HXA 完成时都输出：

```text
Task: HXA-xxx
Changed: <files/modules>
Tests: <exact commands and pass/fail>
Not done: <out-of-scope items>
Risks: <remaining manual checks>
ADR: <ADR-NNNN + status，或 N/A + 具体原因>
```

### 10.1 ADR 决策边界

ADR 记录“为什么决定”，不重复源码和规范，也不证明功能已经实现。小模型执行每个 HXA 时：

1. 用机制、模块、协议和候选方案关键词检索 `docs/adr/`。
2. 对照 [ADR 触发条件](../adr/README.md#3-什么时候必须写-adr) 判断是否需要记录。
3. 契约内普通实现填写“不适用”及具体原因，不为凑数量创建 ADR。
4. 新决定默认写 `proposed`；HXA 明确要求形成结论且证据已完成时，才提交给授权者确认 `accepted/rejected/superseded`。
5. 如果实现与 `accepted` ADR 冲突，停止实现并报告；不得静默改旧决定。
6. `accepted`、设计文档未来时态和构建成功都不能作为实现完成证据。

事实性路径或符号更新可以随当前 HXA 同步，但决定、替代方案和历史理由只能通过新 ADR 显式取代。格式、必需章节和双向链接见 `docs/adr/README.md`。

## 11. 需要升级审查的事项

以下改变影响长期兼容性或安全边界。小模型可以收集证据、写 Spike 或起草 `proposed` ADR，但不能静默把候选方案变成已批准结论：

- 修改安全边界、风险等级或审批规则。
- 引入/升级 native runtime、RootFS、动态代码或 Maven repository。
- 修改 min/target/compile SDK、AGP、Kotlin、NDK。
- 新增危险权限、exported component、后台常驻机制。
- WebView native bridge、Accessibility 动作、Root 工具或 CLI credential boundary。
- MCP SDK/spec 升级、MCP sampling/elicitation/roots 开放。
- 许可证兼容性结论。
- 应用商店政策结论。
- 删除/迁移用户数据。
- 将失败状态改为自动重试有副作用工具。

## 12. 当前推荐 Goal 与后续序列

已完成范围、进行中任务和下一检查点只从[实施状态](status.md)读取；本指南不再硬编码里程碑编号，避免交接文本落后于真实代码。已有完成记录的 HXA 不得重复实现。

只有用户明确要求建立持久 Goal 时才创建 Goal；Goal 覆盖用户授权的里程碑或范围，但任一时刻只允许一个 HXA checkpoint 处于进行中。当前 checkpoint 完成后，必须先写完成记录、更新唯一状态源并通过验收矩阵，再根据路线依赖进入下一项。

实现顺序以[路线文档](roadmap.md)为准：先交付可测试的底层契约、安全边界和最小业务闭环，再接入依赖它们的 UI 或高权限能力。暂停条件以根 `AGENTS.md` 的任务纪律为准。

## 13. 阶段性上下文摘要

`docs/development/status.md` 已存在，禁止按模板重新生成或改写为逐 HXA 日志。每完成一个 HXA 只做最小更新：推进 `Current summary`、`In progress` 与 `Next task`，在 `Current interfaces` 记录仍有效的跨模块事实，在 `Known limitations` 只保留当前会影响开发、验收或发布的缺口。字段级交付详情进入对应完成记录，Bug 根因进入 Bug 修复记录，跨阶段取舍进入历史文档。

小模型开始新任务时读取该状态文件，不从路线、旧交接或完成记录推测当前进度。状态必须由当前代码和测试支撑；计划、accepted ADR 和构建成功都不能单独写成完成。

## 14. 模型选择建议

- 20B–30B 级模型适合：单模块实现、测试补充、单一协议 parser、Room DAO、Compose 页面、Skill frontmatter validator。
- 更强模型/人工适合：跨模块架构、并发恢复、安全审查、WebView/Accessibility token 边界、MCP 动态工具、Root/跨 UID Runtime IPC、订阅 CLI 和许可证。
- 温度使用较低值，要求确定性修改。
- Provider 支持时开启足够上下文，但仍控制输入文件数量。
- 无论模型大小，都以编译、自动测试和真机证据作为完成依据。

文档的目标是让模型拥有足够实现自主性，同时把完成结论建立在可复现证据上。
