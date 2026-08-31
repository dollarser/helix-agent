# Helix 文档复核记录

复核日期：2026-08-31。复核后文档版本：Baseline 1.3。

## 1. 复核范围

- 根 `README.md`、`AGENTS.md`。
- 产品、架构、本地执行、路线、环境、开源参考、安全、Android 能力、Provider/MCP/Skills 和小模型指南。
- 当前实现状态、M0 完成证据、验收矩阵和 ADR 约定。
- `settings.gradle.kts`、version catalog、App/Runtime manifest、变体依赖、测试结果与 CI workflow。

本次重点是“文档是否准确描述当前仓库，以及下一个编码 Agent 能否无需猜测地继续开发”，不是重新选择已固定的产品架构或升级依赖。

## 2. 一致性结果

- 路线文档与验收矩阵均包含同一组 83 个 HXA，无缺失或额外任务。
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
- GitHub Actions 尚无远端运行证据，也尚未配置 emulator job；当前只有本地等价门禁和 HXA-003 本机 instrumentation 证据。
- M0 Compose 空壳中的中文说明仍硬编码在 Kotlin；这是原型壳层，正式的简体中文/英文资源化和硬编码扫描由 HXA-067 验收。在此之前不能声称 UI 本地化完成。
- 开源仓库状态、Android 政策、Provider/CLI 登录方式和依赖最新版本会变化。当前 lockfile 是可重复构建事实，不表示永久最新；进入相关 HXA 时按官方来源重新核实。
- M1 之后的绝大多数模块仍为空骨架。模块存在、marker 存在或 route 可见不等于业务能力完成。

## 5. 继续开发入口

编码 Agent 从[小模型继续开发交接](small-model-handoff.md)开始，人或更强模型在每个里程碑末按[安全与发布门禁](07-security-testing-release.md)和任务完成记录审查。当前不需要为普通实现细节增加更多 Prompt 限制；发现架构、安全、权限、许可证或关键依赖变化时，再针对具体证据升级审查。

## 6. 收口审查（M1 进行中，2026-08-31）

范围：ADR 体系、交接、完成记录、验收矩阵、门禁脚本，以及 HXA-010～012 完成后的全部规范文档；不涉及代码修改。

- ADR-0001 的 Context 仓库事实更正：version catalog 与 [05 第 4 节](05-development-environment.md) 基线其实已固定 `kotlinx-serialization-json` 1.9.0，只是无模块消费且未应用 serialization 编译器插件；Alternatives 第 1 条的比较措辞随之调整。决定不变，仍为 `proposed`。
- 新增 [ADR-0002](adr/0002-turn-state-intra-response-edges.md)（proposed）：把 HXA-011 的 3 条严格增量 Turn 状态边（预调用预算失败、同一响应内串行、`INTERRUPTED` 恢复/丢弃）从“契约解释”升级为正式决定；[02 第 5.2 节](02-architecture-design.md) 状态图同步补全（原图另漏 `RUNNING_TOOL→RECORDING_TOOL_RESULT`）。
- Plan 双重门措辞规范化（[02 §5.1](02-architecture-design.md)、[10 §6.1](10-provider-mcp-skills-modes.md)）：operation class 为主判断，动态风险 ≤ L1 为上限，与 HXA-012 `ModePolicy` 实现一致。
- [05 第 8 节](05-development-environment.md) 目录树补 `docs/completion-records/`、`config/` 与根 `LICENSE`/`THIRD_PARTY_NOTICES.md`。
- [small-model-handoff](small-model-handoff.md) 的当前事实更新到 HXA-013 入口；第 4/5 节标注为交接时快照。
- HXA-011/012 完成记录的决策记录行补 ADR-0002 引用与文档同步事实。

上述内容是小模型第一轮收口的历史记录；两项待决问题已在下节完成授权复核。

## 7. 二次收口与所有者授权决定（2026-08-31）

范围仍只包含文档与决策，不修改生产代码、测试、Gradle 配置或依赖。复核发现第一轮“已无矛盾”的结论仍有四个缺口：路线 HXA-012 保留了“与 L0/L1 无关”的旧措辞；ADR-0002 把 HXA-010 已存在的恢复/丢弃边误归入 HXA-011 新增边；ADR-0001 仍把 serialization 插件描述成影响所有模块且没有约束手写格式的扩张；handoff 虽加快照注记，代码块仍会诱导接收模型从 HXA-010 重做。

本轮决定和修正：

- [ADR-0001](adr/0001-canonical-json-storage-encoding.md)设为 `accepted`，但只接受 `core:model` 少量固定 shape 的内部存储编码；明确不适用于 Provider/MCP wire DTO、导入导出或审批 canonical JSON。只有具备 decoder、已知向量、round-trip 和 malformed-input 测试的类型才能把 JSON 当恢复来源；`PlanArtifact` 当前只有 canonical writer 时必须从规范化 Room 列重建，或在 HXA-014 前补 decoder 与迁移测试。
- [ADR-0002](adr/0002-turn-state-intra-response-edges.md)设为 `accepted`：接受 HXA-011 实际新增的三条边；`INTERRUPTED → BUILDING_CONTEXT/CANCELLED` 是 HXA-010 既有边，本轮只补规范图，不再把它们写成 HXA-011 新增实现。
- 新增并接受 [ADR-0003](adr/0003-plan-read-only-risk-ceiling.md)：Plan 同时要求 `operationClass=READ_ONLY` 和动态风险 ≤ L1；该安全边界同步到路线、架构、专项方案、安全测试和 HXA-012 完成记录。
- [small-model-handoff](small-model-handoff.md)删除硬编码的“当前 HXA”和旧启动代码，改为始终读取 `implementation-status.md` 的当前唯一任务；历史 checkpoint 仍保留在 Goal 表中，但不得重复实现。
- 持续开发实况可能快于状态文档。本轮观察到 HXA-013 源码已开始写入但尚无完成记录，因此只记为 in progress，不把源码存在当完成证据。

本轮没有调整 Kotlin/Compose、自研 reducer、Provider 分层、QuickJS isolated process、独立 PRoot/CLI Runtime、MCP/Skill 或 Android 权限方案；这些主路线仍然合理。仓库尚无首个 Git commit 仍是最高的过程风险，待持续开发到安全 checkpoint 且获得提交授权后应优先建立可回滚基线。（补记：2026-08-31 用户授权后基线已建立，见 §4 补记；本文其余结论在 M1 完成后仍然有效。）
