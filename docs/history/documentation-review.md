# Helix 文档复核历史

初始复核日期：2026-08-31。复核后文档版本：Baseline 1.3。本文是累积复核记录；唯一当前进度以[实施状态](../development/status.md)为准。

## 1. 复核范围

- 根 `README.md`、`AGENTS.md`。
- 产品、架构、本地执行、路线、环境、开源参考、安全、Android 能力、Provider/MCP/Skills 和小模型指南。
- 当前实现状态、M0 完成证据、验收矩阵和 ADR 约定。
- `settings.gradle.kts`、version catalog、App/Runtime manifest、变体依赖、测试结果与 CI workflow。

本次重点是“文档是否准确描述当前仓库，以及下一个编码 Agent 能否无需猜测地继续开发”，不是重新选择已固定的产品架构或升级依赖。

## 2. 一致性结果

- 路线文档与验收矩阵均包含同一组 90 个 HXA，无缺失或额外任务；HXA-038/039 负责模型流状态拆分与批量语义 Turn Coordinator 收口，HXA-068/099 收编此前无主的规则管理与运行控制，HXA-088 负责 Git Workspace 语义，HXA-037 负责确定性 Tool Scheduler，HXA-105 只负责有界委托/Workflow Spike，不把规划误算为已实现能力。
- 所有仓库内 Markdown 相对链接可解析；检查已固化为 `scripts/check-docs.sh` 并接入 CI。
- 工具链版本与当前配置一致：JDK 17、AGP 9.3.2、Gradle 9.5.0、Kotlin 2.3.21、compile/target SDK 36、min SDK 29、Compose BOM 2026.06.01。
- 架构模块清单与 `settings.gradle.kts` 的 28 个子项目一致。
- M0 完成状态与现有代码、6 个变体单元测试执行结果、1 个 API 36 instrumentation 测试及 APK 冷启动证据一致。
- Apache License 2.0 已同时反映在根许可证、README、路线和完成记录中。

## 3. 本次修正

- 把文档基线更新为 1.3，并把“初始库基线”“CI 建议”等实施前措辞改成当前事实。
- 区分当前无设备 CI、开发机 API 36 arm64-v8a 证据和未来远端 emulator job，避免把尚未发生的远端 run 写成已通过。
- 修正小模型任务序列，不再要求重复 HXA-001/HXA-002；M0 完成后唯一下一任务是 HXA-010。
- 将小模型 Prompt 从长禁止清单改为“仓库规范 + 当前 HXA + 真实验收”，允许模型自行完成普通可逆实现选择。
- 新增长程 `HELIX-M1` Goal 交接：Goal 覆盖 HXA-010～016，每次只推进一个 checkpoint，通过后自动继续。
- 新增每个 HXA 独立完成记录目录和文档契约自动校验。

## 4. 当前已知缺口

- 仓库尚无首个 Git commit，全部基线仍处于未跟踪工作树；用户授权提交前，继续开发者必须谨慎保留这些文件。（补记：该缺口已于 2026-08-31 关闭——用户授权建立 Git 基线 `e5e3558`，此后每完成一个 HXA 提交一版。）
- GitHub Actions 已有 4 次远端运行证据（最早 1 次失败，随后 3 次连续成功；截至 2026-08-31 最新为 [33364284426](https://github.com/dollarser/helix-agent/actions/runs/33364284426)），但仍未配置 emulator job；HXA-003 instrumentation 仍只有本机证据，远端构建成功不能替代设备验收。
- M0 Compose 空壳中的中文说明仍硬编码在 Kotlin；这是原型壳层。原计划由 HXA-067 在语音输入中附带验收，2026-09-03 后拆分为独立 HXA-069，统一负责简体中文/英文资源化、App 语言切换和硬编码扫描。在此之前不能声称 UI 国际化完成。
- 开源仓库状态、Android 政策、Provider/CLI 登录方式和依赖最新版本会变化。当前 lockfile 是可重复构建事实，不表示永久最新；进入相关 HXA 时按官方来源重新核实。
- M1 之后的绝大多数模块仍为空骨架。模块存在、marker 存在或 route 可见不等于业务能力完成。

## 5. 继续开发入口

编码 Agent 从根 `AGENTS.md`、当前状态和当前 HXA 开始，人或更强模型在每个里程碑末按[安全与发布门禁](../security/testing-and-release.md)和任务完成记录审查。当前不需要为普通实现细节增加更多 Prompt 限制；发现架构、安全、权限、许可证或关键依赖变化时，再针对具体证据升级审查。

## 6. 收口审查（M1 进行中，2026-08-31）

范围：ADR 体系、交接、完成记录、验收矩阵、门禁脚本，以及 HXA-010～012 完成后的全部规范文档；不涉及代码修改。

- ADR-0001 的 Context 仓库事实更正：version catalog 与 [05 第 4 节](../development/environment.md) 基线其实已固定 `kotlinx-serialization-json` 1.9.0，只是无模块消费且未应用 serialization 编译器插件；Alternatives 第 1 条的比较措辞随之调整。决定不变，仍为 `proposed`。
- 新增 [ADR-0002](../adr/0002-turn-state-intra-response-edges.md)（proposed）：把 HXA-011 的 3 条严格增量 Turn 状态边（预调用预算失败、同一响应内串行、`INTERRUPTED` 恢复/丢弃）从“契约解释”升级为正式决定；[02 第 5.2 节](../architecture/overview.md) 状态图同步补全（原图另漏 `RUNNING_TOOL→RECORDING_TOOL_RESULT`）。
- Plan 双重门措辞规范化（[02 §5.1](../architecture/overview.md)、[10 §6.1](../architecture/provider-mcp-skills-modes.md)）：operation class 为主判断，动态风险 ≤ L1 为上限，与 HXA-012 `ModePolicy` 实现一致。
- [05 第 8 节](../development/environment.md) 目录树补 `docs/completion-records/`、`config/` 与根 `LICENSE`/`THIRD_PARTY_NOTICES.md`。
- 历史 `small-model-handoff.md` 的当前事实曾更新到 HXA-013 入口；第 4/5 节标注为交接时快照。该快照已在 2026-09-01 完成信息迁移后删除。
- HXA-011/012 完成记录的决策记录行补 ADR-0002 引用与文档同步事实。

上述内容是小模型第一轮收口的历史记录；两项待决问题已在下节完成授权复核。

## 7. 二次收口与所有者授权决定（2026-08-31）

范围仍只包含文档与决策，不修改生产代码、测试、Gradle 配置或依赖。复核发现第一轮“已无矛盾”的结论仍有四个缺口：路线 HXA-012 保留了“与 L0/L1 无关”的旧措辞；ADR-0002 把 HXA-010 已存在的恢复/丢弃边误归入 HXA-011 新增边；ADR-0001 仍把 serialization 插件描述成影响所有模块且没有约束手写格式的扩张；handoff 虽加快照注记，代码块仍会诱导接收模型从 HXA-010 重做。

本轮决定和修正：

- [ADR-0001](../adr/0001-canonical-json-storage-encoding.md)设为 `accepted`，但只接受 `core:model` 少量固定 shape 的内部存储编码；明确不适用于 Provider/MCP wire DTO、导入导出或审批 canonical JSON。只有具备 decoder、已知向量、round-trip 和 malformed-input 测试的类型才能把 JSON 当恢复来源；`PlanArtifact` 当前只有 canonical writer 时必须从规范化 Room 列重建，或在 HXA-014 前补 decoder 与迁移测试。
- [ADR-0002](../adr/0002-turn-state-intra-response-edges.md)设为 `accepted`：接受 HXA-011 实际新增的三条边；`INTERRUPTED → BUILDING_CONTEXT/CANCELLED` 是 HXA-010 既有边，本轮只补规范图，不再把它们写成 HXA-011 新增实现。
- 新增并接受 [ADR-0003](../adr/0003-plan-read-only-risk-ceiling.md)：Plan 同时要求 `operationClass=READ_ONLY` 和动态风险 ≤ L1；该安全边界同步到路线、架构、专项方案、安全测试和 HXA-012 完成记录。
- 历史 `small-model-handoff.md` 删除硬编码的“当前 HXA”和旧启动代码，改为始终读取当前唯一状态源（现为 `docs/development/status.md`）；其长期规则后来迁入 `AGENTS.md` 和小模型实施指南，文件本身已删除。
- 持续开发实况可能快于状态文档。本轮观察到 HXA-013 源码已开始写入但尚无完成记录，因此只记为 in progress，不把源码存在当完成证据。

本轮没有调整 Kotlin/Compose、自研 reducer、Provider 分层、QuickJS isolated process、独立 PRoot/CLI Runtime、MCP/Skill 或 Android 权限方案；这些主路线仍然合理。仓库尚无首个 Git commit 仍是最高的过程风险，待持续开发到安全 checkpoint 且获得提交授权后应优先建立可回滚基线。（补记：2026-08-31 用户授权后基线已建立，见 §4 补记；本文其余结论在 M1 完成后仍然有效。）

## 8. 本地执行、审批与 Git 收口（2026-08-31）

- 把“沙箱”拆成可验证原语：E1 是手机本机 isolated UID，E2/E2C 是手机本机独立 companion UID；Provider 可远程推理但不是远程 Worker，QuickJS/PRoot 不宣称 VM。
- 通用 L2/L3 审批固定为精确 ToolCall 的逐次批准/拒绝；不提供模型自批、Advanced 自动批准或全局完全访问。审查同时发现并修复 Room `decision IS NOT NULL` 可消费 denied 的基础守卫漏洞；真实设备测试证明 pending/denied 不可消费。
- 区分 PRoot 中的 Git binary/smoke 与持久 Git 产品。新增 HXA-088 和 proposed ADR-0008，要求先比较仓库权威位置、原子交换、崩溃恢复和 Git 隐式执行面；在接受决定前不实现 Git UI、remote Git、凭据或零散 `.git` 导入。
- 本轮只修改上述必要存储守卫和测试，没有提前实现 Provider、Dispatcher、QuickJS、PRoot、Git UI 或 M2/M3 业务。

## 9. 手机端 Tool 编排取舍（2026-08-31）

- 首版建议吸收确定性和安全机制，而非桌面端规模：统一 Dispatcher、参数级 effect footprint、有界读并发/写屏障、按模型 call sequence 回填、持久事件回放、分阶段 timing 和一次性 interaction receipt。
- 不照搬 approval→sandbox escalation：Helix 失败后不得自动扩大 Android 权限、scope、origin 或回退低隔离 target；网络连接/发送前必须完成门控，禁止 deferred approval。
- 子 Agent 有潜在价值，但只作为 HXA-105/ADR-0009 的后期 Advanced 候选：深度 1、只读、父预算、无审批凭证/Secret/高权限 session；变更只返回 proposal 给父 Turn。
- Workflow 若成立只用静态有界 JSON DAG，并编译回相同 Dispatcher。可执行 JS/Starlark Policy/Workflow、自修改插件、递归/peer Agent、cloud tasks、Remote Worker 和另一个 ralph 生命周期均不进入当前路线。
- 本轮只更新文档、backlog 和 proposed ADR，没有修改生产代码或声明上述能力已实现。

## 10. 技术选型、产品方案与交接复核（2026-09-01）

本轮对照当前代码接线、HXA-001～037 完成证据、移动端竞品分析和 M4 之后路线进行复核。结论是主架构继续成立，不需要更换技术栈或引入大型 Agent 框架；主要风险来自状态文档漂移、首个可感知本地能力尚未交付，以及若干必须等 Spike/ADR 才能决定的高成本集成。

### 10.1 保留的技术选型

- 保留 Kotlin + Compose、Android 原生多模块和手工依赖注入。当前问题是业务能力覆盖率，不是 UI 技术栈或 DI 框架不足；此时迁移 Flutter、React Native 或 Hilt 会扩大迁移面而不改善安全边界。
- 保留自研有限状态机、单一 Dispatcher/Policy/Approval 管线和 Provider 协议岛，不引入 LangChain 类通用框架。HXA-020～037 已证明该组合可以把供应商协议、状态恢复、精确审批、确定性调度和持久回填分离验证。
- 保留分层执行：E0 原生工具、E1 isolated QuickJS、E2 独立 PRoot Runtime、E2C 独立官方 CLI Runtime。Termux 适合作为参考生态和人工验证环境，不适合作为 Helix 的生产宿主；PRoot 只是一项用户态 Linux 兼容技术，真正的产品边界仍来自独立 APK/UID、Binder/PFD、Job 快照、Policy 与审批。
- 保留 Android System WebView、Workspace/SAF 和 MCP Client-only 方向。MCP 只解决 Agent 与工具服务的调用协议，不替代 Android Intent/App Actions、系统权限、Accessibility、Binder 或每次 ToolCall 的安全决策。

### 10.2 产品方案优化

- 近期产品主线应从“框架完整”转向“首个可感知闭环”：M4 先交付受 scope 约束的 Workspace 路径、内容存储和 `read/write/edit`，让聊天、审批、调度和审计第一次连接到真实本地任务。
- 后续按风险递增推进 QuickJS、Browser、MCP，再通过独立 Runtime 评估 PRoot/CLI。高权限 Accessibility/Root 不应被当作早期差异化卖点；只有能力可见性、逐次批准、执行结果与审计都在产品 UI 中可解释时才开放。
- 对外定位应保持“Android 原生、Provider-neutral、local-first execution、每次调用可控”，不宣称尚未实现的系统级控制、完整 Linux、持久 Git、多 Agent 或 HarmonyOS 支持。HarmonyOS 的系统 Agent 生态可作为产品体验参照，但不进入当前 Android 实施范围。
- ChatBox/Cherry Studio/LobeChat 一类 API 客户端、Termux + CLI、系统 Agent 与 Helix 应分赛道比较：前者验证模型接入体验，CLI 验证工具广度，系统 Agent 验证 OS 集成；Helix 的竞争力取决于把这些能力收敛到同一 Android 安全管线，而不是简单堆插件数量。

### 10.3 仍需按触发点决策

- HXA-050：QuickJS/Zipline 的二进制、启动、内存和取消 Spike；未通过前不扩大 E1 API。
- HXA-080～084：PRoot 可行性、RootFS 供应链和 Runtime IPC；不得把 Termux 用户量或 PRoot 包兼容性直接当作集成验收。
- HXA-088 + proposed ADR-0008：Git Workspace 权威位置、原子传输、恢复、hooks/config/credential 边界。
- HXA-094：libsu/JitPack artifact、checksum 与许可证证据；当前不新增仓库或依赖。
- HXA-105 + proposed ADR-0009：只评估有界只读 child/JSON DAG，不预设一定引入多 Agent。
- HXA-122：首次稳定发布前决定最终 applicationId、签名和升级路径。

### 10.4 本轮文档修正

- 产品需求新增“当前产品阶段与近期闭环”，明确 M4 文件闭环是首次本地价值验证，不等于 Alpha 或正式版完成。
- 当轮 README 与状态摘要曾更新到已验证 M3 / HXA-037、下一项 M4 / HXA-040；后续 HXA-038 架构收口已在第 12 节继续更新当前状态。
- 当前状态源（现为 `docs/development/status.md`）区分已落地框架和未落地业务执行器，并修正 M2/M3、Approval、Dispatcher/Scheduler 的历史措辞。
- 小模型实施指南移除硬编码 M1/M2 checkpoint、commit/机器快照，统一动态读取唯一状态源；原 `small-model-handoff.md` 的长期规则迁入 `AGENTS.md`、开发环境和实施指南后删除。文档门禁改为校验实施指南中的动态状态源、禁止重复实现和显式 Goal 授权契约。
- HXA-037 完成记录补齐“决策记录”，恢复文档契约门禁。

本轮没有修改已接受 ADR、依赖或生产代码，因此不触发新的 ADR。未来若改变执行域、权限模型、分发身份、PRoot/CLI 生命周期、Git 权威位置或 child Agent 边界，必须按对应 HXA 与 ADR 触发器单独决策。

## 11. 面向 LLM 的架构与交接退役复核（2026-09-01）

- 删除已完成使命的 `small-model-handoff.md`；动态任务续接、禁止重复 HXA、显式 Goal 授权和暂停条件分别迁入 `AGENTS.md`、小模型实施指南、开发环境与文档门禁。历史复核记录保留文件名和删除原因，不保留失效链接。
- 对 `llm-oriented-design-patterns` 的三组原则进行适用性审查：采纳上下文管理、结构化反馈和确定性边界；拒绝固定 LOC 硬门、动态 import/弱类型字典分发、无界自愈和“所有 Tool 无副作用”等不适合 Android 安全 Agent 的绝对规则。
- Helix 的目标运行时架构高度面向 LLM：统一 ModelEvent、受限 Tool Schema、显式状态机、确定性 Dispatcher、可恢复回填和精确审批都在收窄概率输出。当前生产接线仅部分达标：`TurnReducer` 尚未驱动 `ChatService` 的生产状态推进，导致领域状态机与应用层直接 Room 写入并存；其次才是 `ChatService` 多职责大文件和 Provider SSE framing 重复。
- 总体方案新增 §17，给出项目级 LLM-oriented 约束和结构热点顺序。该节是现有边界内的可维护性解释，没有改变模块依赖、权限、安全管线或外部契约，因此不新增 ADR。

## 12. HXA-038 架构切片与全局复扫（2026-09-01）

- 生产代码核验确认：M1 `TurnReducer` 逐调用串行推进，而 HXA-037 `ToolScheduler`/`ChatService` 已按批次执行无冲突读取；因此“直接把旧 reducer 接上生产”会回退或错误表达现有批量语义，不能作为机械重构。
- HXA-038 把 `ModelEvent` 累积与 terminal decision 从 `ChatService` 抽为纯 JVM `ModelStreamState`，以 10 个 characterization tests 固定文本、usage null、原始工具参数、总量上限、截断失败关闭、拒绝/错误/取消优先级；Room/UI/Dispatcher 副作用仍留在应用层。
- 全局复扫后的顺序保持克制：HXA-039 先用 ADR 决定 batch-safe reducer 的演进/取代并接入唯一 coordinator；`ToolDispatcher` 不按 LOC 拆掉单入口；Provider SSE framing 先补共享 golden tests 再抽；repository 文件只在触碰相应聚合根时机械拆文件。
- 本切片不改变外部契约、权限、安全边界或已接受 ADR；HXA-039 若改变 M1 reducer 契约，必须走 ADR-0002 的部分取代/补充流程。

## 13. 外部文档架构审核复评（2026-09-01）

- 按当前代码、Room schema、HXA 记录与 Git 状态重新核对外部审核，不直接接受其旧快照结论。审核所列 `StorageApprovalBroker`/设备测试等“无 HXA 归属”改动集已不是当前工作树；本轮复评期间出现的 HXA-038 改动已有独立完成记录与状态条目，不把它混入旧快照结论。verification matrix 已覆盖 HXA-037 的 receipt/取消/恢复；完成记录模板也已包含手工设备、决策和后续风险字段，因此不重复修改。
- 确认并修正三个实质漂移：审批公式与 `ApprovalBinding` 九字段/两步哈希不一致；总体方案的唯一规范 Room 清单缺 `interaction_receipts`；规范文档的 `ABORTED_BEFORE_START` 与已落位稳定枚举 `CANCELLED_BEFORE_START` 不同。
- 核心接口代码块改为明确的“目标端口伪代码”，新增当前源码落位表，特别标明 `ContextBuilder`/`PolicyEngine`/`ToolRegistry` 形态差异、未落位端口，以及两个 `ToolExecutor` 概念的同名不同形；Provider/MCP/Skills 专项删除重复签名，改为引用唯一权威位置。
- 不回填 ADR-0010/0011/0013：HXA-037 是已审查规范内实现；ADR-0001 已明确切分 storage JSON 与 approval arguments，本轮只将其 HXA-031/034 引用从未来时更正为已落位事实；Room v1→v3 未更换持久化底座。当前没有新决定或外部契约变更，倒填 ADR 反而违反“不为填满目录制造历史 ADR”的原则。
- 产品需求、竞品分析与开源参考补职责链接；目录树标明为目标布局而非实时实现地图。这些是导航和事实性修正，不改变 HXA-039 交付范围、安全边界或 ADR 状态。

## 14. M3 收口代码审查与修复（2026-09-01）

本轮是 HXA-030～037 完成后的跨层缺陷审查，不改变各 HXA 的原交付范围：

- `core:storage` 将 provider 覆盖从会触发外键 `ON DELETE SET NULL` 的 `REPLACE` 改为原地 UPDATE；Keystore 临时文件改用唯一名称；Goal criteria 非字符串引用改为显式失败。迁移链 v1→v2→v3、审批一次性消费与并发守卫重新核验通过。
- `tools:framework` 修正取消与真失败的审计分类，保留 audit sink 的原始 suppressed 异常；Scheduler 槽位改为全局 toolCallId、准入检查与占槽原子化，并按精确 `(../name, version)` 解析 footprint。技术重试仍只接受逐 attempt 确认零副作用的结果。
- `app` 将屏状态更新改为原子操作，保留跨会话待审批卡，清理取消槽和 Turn cancel 生命周期；工具参数 working set 设 1,048,576 UTF-16 code unit 上限，截断工具流 fail closed，空参数归一为 `{}`，审计页明确过滤只作用于已加载页。
- 三个 Provider 解码器拒绝跨 index 重复 toolCallId；Responses 补齐孤儿/重复/未闭合工具流检查。`core:agent` 收紧 `CancelFinished` 的 uncertain call 归属。
- 验证：JVM 全矩阵 747/0，设备 app 39/39 + storage 38/38（API 36 arm64 模拟器），app lint、Spotless、Detekt、五个门禁脚本和 `git diff --check` 通过。
- 保留并显式记录的取舍：审计页只加载最近 200 行后内存过滤；`MAX_TOOL_ROUNDS_PER_TURN=8`；`SKIPPED_DEPENDENCY` v1 尚未触发；assistant TOOL_CALLS 行保存模型原始参数、binding 使用 canonical 参数；同 Turn 拒绝 check-then-mark 非原子；阶段时间戳遇时钟回拨 fail closed。真实资源信号接线已归 HXA-099。

## 15. 状态源与无主事项收口（2026-09-01）

- 当前状态源（现为 `docs/development/status.md`）从约 60 KiB 的逐 HXA 字段复述压缩为当前摘要、完成索引、唯一 Next task、接口和限制；实现字段、命令和测试数量只在 completion records 维护，历史复核只在本文维护。
- 修正执行目标措辞：领域枚举定义四类本机目标不等于四类执行器已实现；当前生产只有 `LOCAL_ANDROID/time.now`，QuickJS/PRoot/CLI 分别等待 M5/M8/M11。
- 审批文档不再声称 timeout 单独变化已直接进入九字段 `ApprovalBinding`。安全 descriptor 字段属于不可运行期修改的契约；首个业务工具 HXA-042 必须机械证明变更会提升 toolVersion，或先以 ADR 决定完整 contract hash。
- 新增 HXA-068，负责 Advanced 有界出网规则的持久化与创建/撤销 UI；新增 HXA-099，负责 Mode/TurnBudgets UI 与真实低内存/后台/热状态只降并发接线。路线与 verification matrix 同步到 90 个 HXA。

## 16. HXA-039 批量 Turn Coordinator 收口（2026-09-01）

- accepted ADR-0010 取代 ADR-0002 的同响应串行生产决定：HXA-037 的有界只读并发继续保留，生产聊天改由唯一 batch-safe `TurnCoordinator` 持有 Turn aggregate phase、当前 ModelCall/stream checkpoint 和事务化模型回填。M1 reducer 只保留历史测试与旧恢复数据兼容。
- 修复第二轮以后异常仍引用首个 ModelCall 的状态错误；终局 assistant/Turn/ModelCall、工具步骤 close/assistant ToolCalls、按序结果回填/下一 ModelCall/Turn 回环分别形成原子 Room 边界。外部工具副作用始终在事务外，恢复只对账、不盲目重放。
- Scheduler 由“nullable outcome + 全批 first error”升级为每调用 `Outcome/Thrown(../cause)`，未知副作用进入 `NEEDS_REVIEW`，重复 ToolCall identity 在准入前拒绝；同时修复异常 future 先唤醒、slot 后释放造成的漏唤醒死等。
- Provider-neutral 流边界新增累计文本、调用数、聚合参数和事件序列失败关闭；发送路径增加模型可见长度/NUL 检查，并在可配置预算 UI 落地前使用固定输出 token 上限。
- API 36 arm64-v8a 模拟器通过完整 consumer instrumentation；第二 ModelCall 失败、部分文本、合法状态边和进程恢复均有设备 fixture。当前状态转入 M4/HXA-040，不把本次架构收口扩写成文件业务工具已经可用。

## 17. M4 文件工具对抗性审查与复审（2026-09-02）

本轮针对 HXA-040~044（workspace 文件工具与工具分发框架）处理一份外部对抗性审查：逐条确认属实后修复，并在 commit 前由独立 LLM 复审 agent 重审（逐条 P1 核对 + 检查修复是否引入新洞），复审发现并入同一 commit：

- **P1-1 路径泄漏：属实，修复。** 十个文件工具的 catch 链末端全部具备 sanitized `IOException`（read 与 meta 四件套 stat/list/search/mkdir 本次补齐）；`PathResolution` 的两处裸 `toRealPath()` 改 fail-closed 助手（root → `ScopeNotAvailable(../"scope root is not available")`，中间段竞态删除 → `FileNotFoundException(../"file not found")`），新增测试断言错误消息不含真实路径。复审 agent 逐工具与 store 错误路径复核后确认 CLOSED。
- **P1-2 工具 timeout 无执行方：属实，修复。** `ToolDispatcher` 新增看门狗：executor 在 daemon 线程池执行，`deadline`（execStart + descriptor.timeout，同一 clock）到期即结算为稳定 TIMEOUT（阻塞线程 best-effort 中断后放弃），新增 2 个测试（30s 睡眠按 400ms 超时结算且只结算一次；deadline 前异常按内联传播）。复审确认池的并发实际上被 ToolScheduler 硬顶 ≤4 限制，不会失控；approval proof 在执行 START 时消费、超时结算语义正确。
- **P1-3 目录 fsync 顺序：属实，修复。** `writeAtomic` 与 `writeAtomicStream`（044 的流式路径同修）均改为 file data fsync → 原子 rename → 目录 fsync，持久化的目录项是 target 的。复审发现并补修派生问题：重排后 rename **之后**的目录 fsync 失败原会“报失败但文件已发布”（部分文件系统目录 fsync 恒返回 ENOSYS），与工具层“失败 = 未执行”的不变量冲突——已把 rename 后的目录 fsync 降级为 best-effort（失败仅掉电持久性降级，不报失败），KDoc 同步。
- **P1-4 内存无界：属实，修复。** copy/跨 scope move 改 64 KiB 块流式复制 + 增量 SHA-256（新增多 chunk 测试：逐字节一致、hash 覆盖全文件、源保留、无 temp 残留）；edit 设 50 MiB 上限 + probe 门 + 整文件严格 UTF-8 解码，并按复审意见在 readAll 紧邻前补 size 复检（收窄 probe→readAll TOCTOU 的 OOM 维度窗口；`expectedSha256` 前置哈希在解码之后触发，只兜 clobber 不兜内存）；write 的 content 设 schema maxLength 4 MiB + `parseArgs` 防御复检（复检才是真边界）。
- **P1-5 trash NAME_MAX：不修复，显式推迟。** trash entry 名为 24 字节前缀 + 转义原路径，转义后超 255 字节（约 226+ 字符相对路径）时 rename 失败——当前行为 fail-closed（稳定 sanitized 错误、原文件保留、无数据丢失）；长路径改名策略归 HXA-046 文件管理 UI，`docs/development/status.md` 记为已知限制。
- **P1-0 AbandonedWrite：无动作。** 审查指其“not open”；当前代码已是 `open class AbandonedWrite`（HXA-044 的 Cancelled/LimitExceeded 子类化依赖 open），`writeAtomicStream` 的 catch 路径删 temp 并原样重抛。
- **复审补修（并入同一 commit）**：看门狗 Callable 的 `finally` 清粘滞中断标志（线程池复用不再让无辜的下一个 dispatch 虚假失败）；dispatch catch-all 的 model-visible detail 与 checked 异常包装只保留异常类名/固定消息（raw 消息可能含真实路径，doc 10 纵深防御；该 catch-all 在 MCP executor 落地前是 P1-1 保证的单点依赖）。另记录不修复项：executor 改在裸 daemon 线程执行后不再携带调用线程的 thread-local/协程上下文，MCP 接入时需迁移上下文（文档记录，非缺陷）。
- **复审发现、不修复、显式推迟**：超时 abandon 会留下 `.helix-tmp-*` 孤儿，且唯一回收 API `reclaimTempFiles` 无生产调用点（temp 计入 scope 配额；触发需“不可中断 I/O 挂死 + 超时”，罕见且影响限于该 scope）。不在此 bolt-on：`reclaimTempFiles` 无 age 阈值，接入写路径会误删并发活写的 temp，需要 age-based reclaim 的 API 设计，归后续文件管理任务（与 HXA-046 同批）；`docs/development/status.md` 记为已知限制。
- **两个 drive-by 的复审判定**：`ApprovalFlowTest` 期望 key set 补 `contractHash` 与 `ApprovalBinding.canonicalJson`（HXA-042/ADR-0011 的 10 字段）1:1 一致——测试是 HXA-037 时代的过期断言，在 HEAD 即失败；`check-lockfiles.sh` 两处 find 排除 `.claude/` 正确且有效（本地 session worktree 携带自己的 29 份 lockfile 副本使计数 29→58；项目 lockfile 不可能位于 `.claude/` 下，不会放过真实回归）。
- 验证（main checkout）：`:core:workspace`、`:tools:framework`、`:tools:files`、`:app`（consumer + developer 双变体）单元测试与全量 test 矩阵绿；Spotless、Detekt 与五个门禁脚本 exit 0。

## 18. 文档目录分层与命名收口（2026-09-02）

- 12 份平铺编号文档按职责迁入 `product/`、`architecture/`、`development/`、`security/` 和 `references/`；目录已经表达阅读关系，因此移除文件名中的 `01`～`12` 顺序编号。
- `docs/development/status.md` 继续作为唯一当前状态源；路线、验收矩阵、开发环境和实施指南集中在 `development/`。M0 证据并入 `completion-records/M0.md`，本文迁入 `history/`，两者只改变位置，不改变证据内容。
- 新增 `docs/README.md`，按任务提供阅读入口并明确“状态、计划、决定、证据、历史”边界；根 README 只保留高频入口，避免维护第二份完整目录。
- 对 12 份主文档、M0 记录和本文逐一检查独有职责。没有发现内容完全被替代且不含独有决定或验收证据的文件，因此本轮没有丢弃文档内容；旧路径由新路径取代。
- `scripts/check-docs.sh` 同步新路径，并新增必需入口和“根 `docs/` 只允许 README”的结构门禁；全仓 Markdown 相对链接继续由同一脚本验证。

## 19. 能力优先的市场与产品定位复评（2026-09-02）

- 基于外部手机 Agent 竞品材料重新评估目标用户、自动化替代品、渠道和商业化。只吸收有产品价值的分析框架；Star、成功率、用户迁移比例、精确价格等二手或时点数字不进入长期结论。
- 新增 `product/market-users-and-commercialization.md`，独立维护目标用户、Jobs to be done、能力包装、Advanced 责任模型、模板策略、分发、商业模式与验证指标；原竞品文档继续维护执行位置、平台协议和技术能力证据。
- 产品第一目标由普通效率用户调整为开发者与高级用户；定位从“安全型本机 Agent”改为“能力优先的 Android 本机 AI 执行工作台”。Operit、Open-AutoGLM、Tasker/Auto.js/Hamibot 被加入直接能力、GUI Agent 与用户替代品观察。
- 用户对主动开启 Advanced、具体能力、scope 和确认后的设备操作承担最终责任；Helix 仍承担真实展示、不越权和不伪造结果的产品责任。此次调整不修改 accepted ADR-0005 的执行规则；长期授权、自动批准或其他安全内核变化必须另行形成取代决定。
- 竞品文档同步当前 HXA-045 事实：原生文件工具、SAF 与 All-files 适配已有交付，当前差距是 HXA-046 用户文件工作台，以及后续 Browser、QuickJS、PRoot/CLI 和 Android 自动化。

## 20. 未来自动化兼容与 Advanced 授权变更（2026-09-02）

- 项目所有者明确要求把“兼容任意 Tasker/Auto.js 脚本”和 Shizuku/ADB 写为可能支持、暂不实现的能力，并要求重新放宽 Advanced 的长期授权方向。
- Android 可行性核验结论：Tasker 可走官方插件 action/event/state；Auto.js/AutoJs6 需要独立兼容 Runtime 和版本/API/权限/模块矩阵，当前证据不能支持“任意脚本零差异执行”；Shizuku 由用户通过 Root/ADB 启动服务；Android 11+ 提供无线调试配对。这些能力均未获得 HXA 或实现证据。
- 按 ADR 约定没有改写 accepted ADR-0005 的历史正文，而是新增 accepted [ADR-0012](../adr/0012-capability-first-advanced-grants.md)完整取代，并把 ADR-0005 标为 superseded。新决定接受 Trusted Workspace、动态风险不高于 L1 的有界长期规则、精确批量批准和作为文件 scope 的 `Full Workspace Access`。
- 所有者请求中的模型自授权、全局自动批准、全局 Full Access 和 L2/L3 长期 wildcard 放行没有进入 accepted 决定：它们违反项目不可变安全内核，也无法把真实手机上的高影响动作绑定到用户的精确授权。ADR-0012 将其作为被拒绝替代方案显式留档，而不是静默遗漏。
- 产品需求新增 FUT-AUTO-001～003、FUT-SYS-001～002 和 §11 需求变更记录；Android 能力文档、路线、状态、市场与竞品分析同步“未排期候选/非实现证据”口径。

## 21. Standard 商店产品边界调整（2026-09-02）

- 项目所有者明确 Standard 应以 Google Play 和国内 Android 应用商店为目标渠道，同时保留尽可能完整的产品能力，不因抽象安全偏好或未经核验的准则预先裁剪。
- 官方政策核验显示：Google Play 允许文件/文档管理核心用途申请 All-files，也允许非辅助工具在声明、显著披露和同意后使用 Accessibility；但明确禁止 Accessibility 驱动的 Agent 自主规划执行，只允许狭窄、用户可理解的确定性自动化，并禁止从 Play 外下载 DEX/JAR/native executable code。
- 新增 accepted [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)取代 ADR-0006：Standard 改为商店/官网完整产品；渠道差异必须有当前政策原文或真实审核反馈，并局限于对应渠道。consumer/developer 降为当前工程机制，不再分别代表“商店阉割版/官网完整版”。
- 同步产品需求、Android 能力、总体架构、安全门禁、M12 路线与验收矩阵、开发环境、实施指南、市场与竞品文档。本次没有提交商店、没有获得审核结果，也没有修改 flavor/applicationId 或能力实现代码。

## 22. 全仓文档去重与权威层收口（2026-09-02）

- 再次按产品需求、架构规范、开发治理、决定、交付证据和历史六类职责复核全仓文档。没有删除 superseded ADR、完成记录或既有 Bug 记录：它们保留独有的决定理由、验收命令或根因证据，不属于可丢弃重复内容。
- 根 README 删除重复维护的 SDK、模块、applicationId、Runtime、Git 和 Tool 细节，只保留产品入口与稳定边界；详细规范统一链接产品、架构和有效 ADR。
- `development/status.md` 删除 M4 字段级实现复述、历史 Git 基线、已修复细节和复核过程，只保留里程碑索引、当前接口、未完成能力与仍然成立的限制。完成细节继续由 completion records 和 Bug 记录承载。
- 产品需求不再复制当前 HXA 与实现快照；竞品和市场文档将实现描述明确标为核验日快照，并链接唯一状态源。实施指南不再内嵌可覆盖状态文件的旧模板。
- 发布门禁删除 consumer/developer 作为产品等级的旧分法，改为 Standard 共同门禁、Google Play、国内应用商店、官网与 Advanced 四组渠道证据；本地代码执行文档也明确 flavor 只是当前构建/测试机制。
- 文档中心新增维护约束：产品和架构不复制实时任务状态，渠道、flavor 与运行时安全配置不得合并为单一“受限/完整”开关。
- 验证：`./scripts/verify-adr.sh`、`./scripts/check-docs.sh` 与 `git diff --check` 均通过。本轮只修改文档与文档门禁，没有修改业务代码或生成发布证据。
