# Helix 小模型继续开发交接

交接日期：2026-08-31。接收对象：约 20B–30B 级编码模型或其他可持续操作仓库的编程 Agent。

## 1. 当前事实

- M0 / HXA-001～003 已完成。证据见 [M0 完成记录](m0-completion-record.md)。
- M1 / HXA-010～016 已全部完成（领域模型、Turn/Goal reducer、PlanArtifact、Room 持久化、恢复协调器、Context Builder），M1 退出条件测试全部通过。证据见 [HXA 完成记录](completion-records/README.md)。
- 已完成范围、当前唯一任务和下一任务只以 [implementation-status](implementation-status.md) 及对应[完成记录](completion-records/README.md)为准，本文件不复制易漂移的 checkpoint 状态。Provider、Tool、文件、浏览器、MCP、Skill 和代码执行业务能力在各自完成记录出现前均视为未实现（M1 只交付领域状态与持久化，不是可对话的 Agent）。
- [ADR-0005](adr/0005-standard-advanced-safety-profiles.md)已接受两级安全边界：consumer 只能 `STANDARD`，developer 默认仍为 `STANDARD`，`ADVANCED` 仅显式开放受控能力且不削弱安全内核。该决定尚未实现；M2/HXA-020 起必须按实际 endpoint residence 和数据类别设计，不能把文档决定写成当前功能。
- [ADR-0006](adr/0006-single-direct-main-package.md)已接受单一直接分发主包：用户只获取 developer 构建的 Helix 主应用，在同一安装内从 Standard 进入 Advanced；consumer 只用于未来受限渠道，PRoot/CLI 是 companion。不要据此重命名 flavor/applicationId；最终身份属于 HXA-122，且尚无对外发布产物。
- [ADR-0007](adr/0007-companion-runtime-lifecycle.md)已接受 companion 生命周期：Runtime 安装后只由用户点击的零 Job 验证/修复/登录或批准 Job 显式冷绑定，不要求用户先打开/常驻；应用启动、切换 Advanced 和被动 Registry 刷新不启动 Runtime。空闲允许回收，断连只按 jobId 查询/对账，未知结果停泊且不重放。任意 Shell/CLI 计算不能冒充 `dataSync`，可选 wake lock 必须绑定用户可见 FGS、硬 deadline 和全路径释放。该决定仍未实现。
- 本机执行不代表主进程或 VM：E1 是手机上的 isolated UID，E2/E2C 是手机上的独立 companion UID，Provider 只做推理；当前没有远程 Worker。Root/Accessibility 也不是沙箱。
- 通用 L2/L3 只有精确 ToolCall 的逐次批准/拒绝；没有全局完全访问、模型自批或“Advanced 自动批准”。存储层已经封闭 `APPROVED`/`DENIED` 且 denied 不可消费，但 HXA-034 的 hash/expiry/Proof 和 HXA-036 UI 仍未实现。
- PRoot 计划包含的 Git 当前只用于离线 Job smoke，不是持久 Git 管理；ADR-0008 尚为 proposed，HXA-088 前不得实现 Git UI、导入零散 `.git`、remote Git 或凭据流。
- [11 手机端 Tool 编排](11-mobile-tool-orchestration.md)已确定推荐分层：HXA-035/037 实现单 Agent 的单一安全管线、effect footprint、有界读并发、固定回填顺序、持久回放和交互 receipt；HXA-105/ADR-0009 以后才可评估 Advanced 的深度 1 只读 child 和 JSON DAG。当前没有 child Agent、Workflow、云端任务或远程 Worker。
- [ADR-0004](adr/0004-goal-run-wake-budget-semantics.md)已接受 Goal 的 run/wake、显式继续、预算耗尽 PAUSED 与不重放语义。PAUSED 的原因必须通过稳定 run outcome + audit 持久化，不得只看进程内 effect；HXA-102 还必须实现并验证有界 durable usage checkpoint，使反复 crash 不能持续绕过预算。不要把 ADR 接受或 HXA-013/HXA-015 的现有测试写成这两项后续约束已经落地。
- 项目所有者允许参考 Claude Code、Gemini CLI、Codex CLI、Cline、Continue、Aider、OpenCode、DeepSeek Harness、goose、Amazon Q Developer CLI 和 Hermes 等主流 Agent 的思想。具体事实、维护/许可证状态和 Helix 映射以 [06 §5.11](06-open-source-references.md#511-主流-coding-agent--agent-harness-设计参考) 为准；这不是复制源码、引入依赖、复用订阅凭据或绕过 HXA/ADR 的授权。
- libsu/JitPack 当前没有加入构建；HXA-094 才能根据固定 artifact/checksum/license 证据形成依赖 ADR。不要把“已有计划路径”写成已采用依赖，也不要在 M9 前新增仓库。
- 主 App 已有手工 `AppContainer`、`ShellRepository` fake 和七个空状态 route。不要把空页面当成功能实现。
- 2026-08-31 交接快照：`main` 与 `origin/main` 同步于 `1824569`，共 9 个 commit（M0/M1 5 个基线提交 + 1 个综合审查修复 + 3 个 CI/依赖验证修复）；最新远端 CI 成功。该数字会漂移，继续开发前必须实际运行 `git status -sb`、`git rev-list --count HEAD` 并查看远端 CI，不能把本句当永久事实。按用户授权每完成一个 HXA 提交一版，不要合并多个 HXA，也不要提交机器路径或 Secret。
- 本机已配置 JDK 17.0.20.1、Android SDK 36、Gradle Wrapper 9.5.0，以及 `Helix_API_29`、`Helix_API_36` AVD。2026-08-31 复核时磁盘可用约 427 GiB。
- `local.properties` 已配置并被忽略；仓库不含业务 Secret。需要 Java 时可设置：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

## 2. 建议建立的长程 Goal

如果编码 Agent 支持持久 Goal，按下列模式为**当前里程碑**建立目标；若不支持，就把本节作为持续工作目标，不必模拟不存在的状态系统。

`Goal: HELIX-M1`（Objective：按 HXA-010 → HXA-016 的顺序完成 M1）已于 2026-08-31 完成并收口：五条 Success criteria（矩阵命令、完成记录、状态一致、退出测试、M2 未开始）全部满足，逐 HXA 证据见[完成记录](completion-records/README.md)。

下一 Goal 为 `HELIX-M2`（Provider，HXA-020 → HXA-028），**在用户明确授权 M2 启动后再建立**，Success criteria 按同一模式：各 HXA 需求与 verification-matrix 命令通过、逐项完成记录、状态文件一致、M2 退出条件（三协议 fixture、能力探测、取消和错误分类）通过、M3 未开始。

除非用户明确给出，不要自行设置 token 或时间预算。长程 Goal 只是持续推进的工作容器，不改变 Helix 产品中 Goal 模式的权限语义。

## 3. Goal checkpoint

Goal 跨越一个里程碑，但任一时刻只推进一个 HXA。下表为 M1 的 checkpoint（已全部完成，保留作历史参照）；M2 的 checkpoint 为 HXA-020～028，原文以[路线文档](04-roadmap-and-backlog.md)为准：

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
4. 当前 HXA 直接涉及的架构、安全或平台规范章节；不要为普通局部实现加载全部未来方案。涉及 Provider、Policy、网络、PRoot、Accessibility 或 Root 时必须同时读取 [ADR-0005](adr/0005-standard-advanced-safety-profiles.md)；涉及变体、applicationId、签名、下载清单或发布时读取 [ADR-0006](adr/0006-single-direct-main-package.md)。
5. [小模型实施指南](08-small-model-implementation-guide.md)中主控工作流、测试反馈和完成记录章节。
6. 当前模块的源码、测试、构建配置、version catalog 和 [验收矩阵](verification-matrix.md)中的真实 task。

只实现当前 HXA；已经有完成记录的 checkpoint 不重复实现，尚未进入的 checkpoint 不提前占用其模块或公开契约。完成后执行当前 HXA 的矩阵命令、相关质量门禁，并把实际命令和结果写入完成记录。

## 5. 可直接交给编码 Agent 的启动提示

下面这段提示刻意保持简短；详细边界由仓库文档承担，不需要在每轮消息重复几十条禁止项。

```text
请接手本仓库的后续开发（工作目录为仓库根目录）。

先读取 AGENTS.md、README.md、docs/small-model-handoff.md、docs/implementation-status.md，以及路线中的当前任务。M0 与 M1（HXA-001～016）已完成；若 implementation-status 标明 M2（HXA-020 起）已获用户授权，则按 handoff 中定义的模式建立 HELIX-M2 Goal，按 HXA-020 到 HXA-028 顺序持续推进，每次只让一个 HXA 处于进行中；M2 未获授权时不要开始任何 M2 工作。

从 implementation-status.md 标明的当前唯一任务继续；已有完成记录的 HXA 不得重复实现。根据现有架构自行做简单、可逆、主流的实现选择，补齐测试并运行 verification matrix 的真实命令。一个 checkpoint 验收通过后，写完成记录、更新实施状态并继续下一项，不必为普通实现细节等待确认。

不要把计划或编译成功写成完成功能。只有遇到需要改变既定架构/权限、关键依赖升级、外部条件缺失或无法在当前 HXA 内解决的真实阻塞时再暂停并说明证据。按用户授权每完成一个 HXA 提交一版 commit，不要合并多个 HXA，也不要提交机器路径或 Secret。

安全配置固定约束：consumer 只能 Standard；developer 首次启动仍是 Standard；Advanced 只能分能力显式开启，不能跳过 Policy/Approval/隔离/审计，也不能自动申请权限、请求 Root、安装 PRoot 或允许 LAN。Provider residence 按实际 endpoint 判定，Standard 高敏数据逐次确认，Secret/凭据在两级中都拒绝发送。

Advanced 高敏规则固定为 1h/24h/7d/30d（默认 24h、最大 30d），不滑动续期，到期或时钟回拨 fail closed。developer 的 manifest 能力可能出现在 Android 系统设置；Standard 只保证默认关闭、不自动启用且没有 Agent scope，不能承诺系统设置隐藏。

分发固定约束：直接分发只有一个用户主应用，由 developer 变体构建但产品名仍为 Helix；consumer 不进默认下载，Runtime 只作为 companion。未经 HXA-122 和新 ADR，不改 flavor/applicationId，不声称跨 applicationId 原地升级。

Runtime 固定约束：PRoot/CLI 只需安装，不要求用户手动打开或保持进程；只有用户点击的零 Job 验证/修复/登录或批准 Job 才用显式 `BIND_AUTO_CREATE` 冷绑定，应用启动、切换 Advanced 和被动 Registry 刷新不启动 Runtime，空闲解绑。Binder death 后只按 jobId 查询和核对 terminal proof，未知结果进入 INTERRUPTED，绝不自动重放或回退主 App shell。后台继续必须使用与真实工作匹配的用户可见 FGS；任意计算不得冒充 dataSync，可选 wake lock 必须有硬 deadline 和全路径释放。

Runtime journal 首版限制为 128 条/1 MiB metadata；额度满时拒绝新 Job，不删除 active/未对账证据。已对账 payload 立即删除、tombstone 最多 7 天；未对账 terminal 最多 30 天，之后只留 evidence-expired marker 并停泊 INTERRUPTED，仍不重放。

执行与审批固定约束：E0/E1/E2/E2C 当前都在手机 CPU/内核上运行，但分别受主 App、isolated UID、独立 companion UID 边界约束；不能把 QuickJS/PRoot 写成 VM。通用 L2/L3 只按精确摘要每次询问，不得增加 FULL_ACCESS、AUTO_APPROVE、模型自批或用 Advanced/系统权限/Root grant 代替 Approval Proof。`DENIED` 在 DAO 层不可消费，后续实现不得退回 `decision != null` 判断。

Git 固定约束：PRoot 里的 git 目前只承诺离线 Job 副本 smoke；`clone/fetch/pull/push` 和 credential helper 不可用，`.git` 不得零散导回 Workspace。HXA-088/ADR-0008 决定权威仓库和原子传输前，不实现持久 Git UI 或远程 Git。

Tool 编排固定约束：并发安全由平台从规范化参数生成 effect footprint，模型/MCP/Skill 不能自报；只并行无冲突读取，未知/写/代码/Root/UI/同 Runtime lane 默认排他，结果按原始 call sequence 回填。取消必须为每个排队调用留下 durable outcome；失败不得扩大权限/scope/target 或延迟网络审批。HXA-105/ADR-0009 前保持单 Agent，不实现 child、peer communication、递归派生、可执行 Workflow/Policy DSL、自修改插件或 cloud task。

外部 Agent 参考只用于当前 HXA 的具体设计问题：每次最多核实 1～2 个官方项目，提取 invariant/协议/测试并映射到 Helix 的 Android scope、Policy、Approval、UID 和恢复边界。不得因为 Claude Code/Codex/OpenCode 等在桌面允许 shell、插件或全仓库访问，就给 Helix 增加同等权限；若参考方案要求改变已接受决定，先提 proposed ADR 并停止实现。
```

## 6. 交接完成的判断

接收模型能回答以下问题才算真正接手：当前完成到哪里、当前唯一任务是什么、该任务允许产生哪些契约、验收命令是什么、哪些能力仍只是规划、何时应继续而不是询问、何时必须暂停。答案必须来自仓库当前文件和测试，而不是根据产品愿景猜测。
