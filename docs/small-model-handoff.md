# Helix 小模型继续开发交接

交接日期：2026-08-31。接收对象：约 20B–30B 级编码模型或其他可持续操作仓库的编程 Agent。

## 1. 当前事实

- M0 / HXA-001～003 已完成。证据见 [M0 完成记录](m0-completion-record.md)。
- M1 正在按 HXA-010～016 持续推进；已完成范围、当前唯一任务和下一任务只以 [implementation-status](implementation-status.md)及对应[完成记录](completion-records/README.md)为准，本文件不复制易漂移的 checkpoint 状态。Provider、Tool、文件、浏览器、MCP、Skill 和代码执行业务能力在各自完成记录出现前均视为未实现。
- 主 App 已有手工 `AppContainer`、`ShellRepository` fake 和七个空状态 route。不要把空页面当成功能实现。
- 当前仓库分支为 `main`，但尚无首个 Git commit，现有基线文件都在未跟踪工作树中。继续开发前必须先查看 `git status`；未经用户授权不要擅自创建提交或覆盖基线。
- 本机已配置 JDK 17.0.20.1、Android SDK 36、Gradle Wrapper 9.5.0，以及 `Helix_API_29`、`Helix_API_36` AVD。2026-08-31 复核时磁盘可用约 427 GiB。
- `local.properties` 已配置并被忽略；仓库不含业务 Secret。需要 Java 时可设置：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

## 2. 建议建立的长程 Goal

如果编码 Agent 支持持久 Goal，建立下列目标；若不支持，就把本节作为持续工作目标，不必模拟不存在的状态系统。

```text
Goal: HELIX-M1
Objective: 按 HXA-010 → HXA-016 的顺序完成 Helix M1，使领域模型、Turn/Plan/Goal 状态机、Room 持久化、恢复协调器和 Context Builder 形成可测试且可恢复的单机基础。
Success criteria:
1. HXA-010～016 各自需求与 verification-matrix 命令全部通过。
2. 每项都有 docs/completion-records/HXA-NNN.md 的真实证据。
3. docs/implementation-status.md 与源码、测试和实际设备证据一致。
4. M1 退出条件中的状态机、预算、迁移、恢复和上下文裁剪测试全部通过。
5. M2 Provider 网络实现尚未开始。
```

除非用户明确给出，不要自行设置 token 或时间预算。长程 Goal 只是持续推进的工作容器，不改变 Helix 产品中 Goal 模式的权限语义。

## 3. Goal checkpoint

Goal 跨越一个里程碑，但任一时刻只推进一个 HXA：

| 顺序 | checkpoint | 主要产物 | 最小验收 |
| --- | --- | --- | --- |
| 1 | HXA-010 | 领域 ID、状态、错误、预算、Capability、执行目标/envelope | `./gradlew :core:model:test` |
| 2 | HXA-011 | Turn reducer 和 effect | `./gradlew :core:agent:test` |
| 3 | HXA-012 | Chat/Plan/Act/Goal 模式策略与 PlanArtifact | `./gradlew :core:agent:test` |
| 4 | HXA-013 | Goal reducer、预算、checkpoint、人工输入状态 | matrix 中 JVM + Android 验收 |
| 5 | HXA-014 | Room schema、Repository 和 migration fixture | matrix 中 storage JVM + Android 验收 |
| 6 | HXA-015 | 进程恢复与不重放不明确副作用 | matrix 中 agent/storage/app 验收 |
| 7 | HXA-016 | 可审计 Context Builder 和确定性裁剪 | `./gradlew :core:agent:test` |

每个 checkpoint 通过后：写完成记录、更新状态、运行相关质量门禁，然后直接进入下一项。普通类名、私有函数拆分、测试 fixture 组织等可逆选择不需要反复询问用户。

只有以下情况暂停 Goal 并请求审查：需要改变既定安全/权限/模块边界；需要新增或升级关键依赖；需求与已接受决定冲突；外部设备/服务不可获得；存在不能用当前 HXA 内修改解决的证据化阻塞。普通编译或测试失败应继续诊断修复。

## 4. 每个 checkpoint 的启动上下文

开始或续接任一 HXA 时读取：

1. 根 `AGENTS.md`、`README.md`。
2. [当前实施状态](implementation-status.md)。
3. [路线文档](04-roadmap-and-backlog.md)中的当前 HXA 原文及其明确前置任务。
4. 当前 HXA 直接涉及的架构、安全或平台规范章节；不要为普通局部实现加载全部未来方案。
5. [小模型实施指南](08-small-model-implementation-guide.md)中主控工作流、测试反馈和完成记录章节。
6. 当前模块的源码、测试、构建配置、version catalog 和 [验收矩阵](verification-matrix.md)中的真实 task。

只实现当前 HXA；已经有完成记录的 checkpoint 不重复实现，尚未进入的 checkpoint 不提前占用其模块或公开契约。完成后执行当前 HXA 的矩阵命令、相关质量门禁，并把实际命令和结果写入完成记录。

## 5. 可直接交给编码 Agent 的启动提示

下面这段提示刻意保持简短；详细边界由仓库文档承担，不需要在每轮消息重复几十条禁止项。

```text
请接手 /Users/dollars/Helix 的后续开发。

先读取 AGENTS.md、README.md、docs/small-model-handoff.md、docs/implementation-status.md，以及路线中的当前任务。若你支持持久 Goal，请建立 handoff 中定义的 HELIX-M1 Goal；按 HXA-010 到 HXA-016 顺序持续推进，每次只让一个 HXA 处于进行中。

从 implementation-status.md 标明的当前唯一任务继续；已有完成记录的 HXA 不得重复实现。根据现有架构自行做简单、可逆、主流的实现选择，补齐测试并运行 verification matrix 的真实命令。一个 checkpoint 验收通过后，写完成记录、更新实施状态并继续下一项，不必为普通实现细节等待确认。

不要把计划或编译成功写成完成功能。只有遇到需要改变既定架构/权限、关键依赖升级、外部条件缺失或无法在当前 HXA 内解决的真实阻塞时再暂停并说明证据。不要提交 Git commit，除非用户另行授权。
```

## 6. 交接完成的判断

接收模型能回答以下问题才算真正接手：当前完成到哪里、当前唯一任务是什么、该任务允许产生哪些契约、验收命令是什么、哪些能力仍只是规划、何时应继续而不是询问、何时必须暂停。答案必须来自仓库当前文件和测试，而不是根据产品愿景猜测。
