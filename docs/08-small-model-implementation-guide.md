# 使用较小编码模型实施 Helix

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

1. 根 [README](../README.md)。
2. 根 `AGENTS.md` 与[当前实施状态](implementation-status.md)。
3. [总体技术方案](02-architecture-design.md) 中与任务相关章节。
4. [技术路线](04-roadmap-and-backlog.md) 中当前任务及前置任务。
5. 当前模块的 README/API/测试。

长程接力开发再读取[小模型继续开发交接](small-model-handoff.md)。

处理 Provider/MCP/Skill/Plan/Goal 时读取 [专项方案](10-provider-mcp-skills-modes.md)；处理浏览器、文件、Accessibility、Root 时读取 [Android 平台能力](09-android-platform-capabilities.md)；处理 QuickJS/PRoot/CLI Runtime 时读取本地执行方案。任务触发架构决定或改变既有决定时，再读取 [ADR 约定](adr/README.md) 和相关 ADR。不要每次把所有文档塞进上下文。

## 3. 主控工作流

```text
建立一个里程碑 Goal
  → 选择该 Goal 的第一个 HXA checkpoint
  → 检查前置任务和工作树
  → 检索相关 ADR 并判断本任务是否触发决策记录
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
docs/implementation-status.md、路线中的任务原文，以及与本任务直接相关的架构章节和现有代码。

在既定架构内自行选择简单、主流、可测试的实现。补齐正常、非法输入和任务原文要求的失败/恢复测试，运行 verification matrix 的真实命令，修复问题直到通过。不要用删除测试、伪造成功或提交 Secret 换取验收。

完成后写 docs/completion-records/HXA-ID.md，并同步 implementation-status：列出实际修改、命令、exit code、未实现范围和 ADR 状态。如果发现必须改变既定架构、安全边界或关键依赖，先给出具体冲突和候选方案，再暂停请求审查；普通可逆实现选择直接推进。
```

## 5. 只读审查 Prompt

```text
请只读审查 Helix 任务 <HXA-ID> 的当前 diff，不修改文件。

依据：
- 该任务需求和验收标准
- docs/02-architecture-design.md
- 如涉及执行/权限，docs/03-local-code-execution.md 和 docs/07-security-testing-release.md

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

### 安全

- 是否在主进程执行 QuickJS？
- 是否把 PRoot 放在主 App 的同 UID 子进程，而不是独立 Runtime APK？
- 是否给 JS 注册了文件、网络或 Android bridge？
- 是否只靠 Prompt 决定权限？
- 是否审批后重新生成/修改了参数？
- 是否记录了 Header、Key、通知或文件正文？
- 是否让 MCP annotation 或 Skill 指令降低风险？
- 是否让官方 CLI token 进入主 App/Room/日志？
- 是否把 All-files 系统授权等同于 Agent 可访问整个共享存储？
- 是否在启动时触发 Root 请求，或把 `root.exec` 放进普通工具表？

### Browser/Accessibility

- 是否对不可信 WebView 注册永久 privileged `addJavascriptInterface`？
- 是否用模型生成 JavaScript 直接控制 WebView？
- browser node token 是否绑定 tab/origin/navigation generation/TTL？
- Accessibility token 是否绑定 package/window/generation？
- 目标 App 或窗口变化时是否暂停？
- 是否拒绝支付、认证、系统授权、安装器和 Root 管理界面？

### MCP/Skills/Provider

- 是否把 Responses、Chat Completions 和 Anthropic Messages 混成猜测式 adapter？
- 是否只测文本，没有测分片 ToolCall？
- MCP schema hash 变化后旧审批是否仍有效？
- Skill 是否在 discovery 阶段把全部正文/资源塞入 context？
- Skill 脚本是否绕过了正常的 code/bash Tool？
- 是否把 ChatGPT/Claude 订阅当作可直接使用的 API key？

### 文件

- 是否直接拼 `File(root, userPath)`？
- 是否跟随了越界 symlink？
- 是否直接覆盖，没有临时文件/hash/conflict？
- 是否信任 ContentProvider 的 MIME/size/display name？

### 测试

- 是否只测 happy path？
- 是否使用真实网络导致不稳定？
- 是否为通过测试增加 sleep？
- 是否把异常 catch 后返回成功？

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
feat(agent): implement deterministic turn reducer [HXA-011]
test(workspace): cover symlink escape and atomic conflicts [HXA-040]
feat(runtime): add isolated QuickJS execution service [HXA-051]
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
2. 对照 [ADR 触发条件](adr/README.md#3-什么时候必须写-adr) 判断是否需要记录。
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

M0 的 HXA-001～003 已完成，不能重复生成。当前先建立 M1 Goal：

1. HXA-010 领域模型。
2. HXA-011 Turn reducer。
3. HXA-012 PlanArtifact 与模式策略。
4. HXA-013 Goal reducer 与预算。
5. HXA-014 Room schema。
6. HXA-015 恢复协调器。
7. HXA-016 Context Builder。

M1 全部退出条件通过并经审查后，再建立 M2 Goal，从 HXA-020 开始。不要把 HXA-020 或聊天 UI 混入当前 Goal。完整启动提示和 checkpoint 见[继续开发交接](small-model-handoff.md)。

不要先做漂亮聊天 UI、WebView 自动点击、Accessibility、Root、PRoot 或 CLI 登录。先让状态、协议、工具、Capability 和审批可测试。

## 13. 阶段性上下文摘要

`docs/implementation-status.md` 已存在。每完成一个 HXA 就最小更新它；不要重新生成并丢失之前的事实：

```markdown
# Implementation Status

## Completed
- HXA-xxx: <verified result and command>

## In progress
- HXA-yyy: <current concrete state>

## Next task
- HXA-zzz: <only the next eligible checkpoint>

## Blocked
- <blocker and evidence>

## Current interfaces
- <only cross-module contracts>

## Known limitations
- <unimplemented capability, missing device evidence, or command + error summary>
```

小模型开始新任务时读这个短状态文件，而不是猜测之前完成了什么。状态必须根据代码和测试更新，不能把计划写成完成。

## 14. 模型选择建议

- 20B–30B 级模型适合：单模块实现、测试补充、单一协议 parser、Room DAO、Compose 页面、Skill frontmatter validator。
- 更强模型/人工适合：跨模块架构、并发恢复、安全审查、WebView/Accessibility token 边界、MCP 动态工具、Root/跨 UID Runtime IPC、订阅 CLI 和许可证。
- 温度使用较低值，要求确定性修改。
- Provider 支持时开启足够上下文，但仍控制输入文件数量。
- 无论模型大小，都以编译、自动测试和真机证据作为完成依据。

文档的目标是让模型拥有足够实现自主性，同时把完成结论建立在可复现证据上。
