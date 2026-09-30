# Research 历史快照

本目录保存不同阶段形成、已被现行决定或后续设计承接的原始研究。保留原日期、源码证据、设计理由与当时判断；**历史归档不是判定全文错误，也不是删除未交付候选，更不是当前实现授权**。

当前研究结论从[综合模块](../../research/modules/README.md)进入；当前事实看 [status](../../development/status.md)，实施范围看对应 HXA，长期契约看 accepted ADR。

历史文件中的“当前/计划/缺口”按原基线理解。源码证明行为，accepted ADR 定义接受范围，状态与任务说明交付；冲突需记录，不能按研究日期自动裁决。新归档页明确指出承接入口，正文不改写成今天的事实。

本目录不继续维护 current 状态；逐字旧结论和当时来源可用于追溯为什么后来发生某个决策。

## 2026-09-29 新归档与现行承接

| 历史材料 | 归档原因 | 当前入口 |
| --- | --- | --- |
| [Linux 命令集成](helix-linux-command-integration.md) | 旧 APK/UID、QuickJS 进程和风险等级已不适用；原记录没有统一日期 | [执行域](../../architecture/local-code-execution.md)、[终端](../../architecture/terminal.md) |
| [后台完成机制（09-10）](background-task-completion-2026-09-10.md) | 旧开发宿主与当时产品阶段混合 | [终端](../../architecture/terminal.md)、[Harness §8](../../architecture/harness-refactor-plan.md) |
| [Memory 与 Activity（09-26）](agent-memory-and-activity-presentation-2026-09-26.md) | HXA-229/230 已承接，完整 Project 仍单列 | [Memory](../../product/memory.md)、[HXA-229](../../completion-records/HXA-229.md) |
| [Conversation-first（09-26）](conversation-first-session-workbench-2026-09-26.md) | HXA-228 已交付，不重开原 UI 计划 | [HXA-228](../../completion-records/HXA-228.md)、[操作体验](../../product/task-experience.md) |
| [Workspace 对比（09-27）](workspace-competitive-contracts-2026-09-27.md) | HXA-210/Workspace ADR 已承接 | [Workspace ADR](../../adr/workspace/004-workspace-binding.md) |
| [能力架构收敛（09-28）](helix-agent-capability-architecture-convergence-2026-09-28.md) | 早期横向路线由详细方案与推进原则承接 | [Harness 方案](../../architecture/harness-refactor-plan.md)、[开发策略](../../development/feature-refactor-strategy.md) |

仍在使用的专项比较由[专题研究](../../research/topics/README.md)导航。只移动/标注历史，不擅自接受、关闭或删除候选功能。

## 2026-09-30 后续归档

| 历史材料 | 当前承接 | 保留边界 |
| --- | --- | --- |
| [Harness 人工介入审查](harness-human-intervention-audit-2026-09-29.md) | [HXA-232](../../development/tasks/HXA-232.md)、[Harness 职责准则](../../architecture/harness-refactor-plan.md#136-模型与-harness-的职责优化及减法准则) | 保留原失败/建议；不重新引入周期确认，不关闭未验矩阵 |
| [QuickJS 访问与恢复备选](quickjs-access-and-autonomous-recovery-2026-09-29.md) | [QuickJS ADR](../../adr/runtime/003-quickjs.md)、HXA-232 | 原“待选”已获后续决定；任意原生访问的共享 UID 边界不变 |

## 原始报告到当前模块的映射

| 历史报告 | 当前综合模块 |
| --- | --- |
| `helix-agent-complete-research-and-product-plan.md` | 产品定位、架构、UI、工具等多个模块；以 `docs/research/modules/README.md` 为总入口 |
| `project-structure-and-engine-review.md` / `execution-engine-deep-review-2026-09-22.md` / `execution-engine-comparison.md` | `01-architecture-and-execution-engine.md` |
| `process-death-recovery-vs-competitors-2026-09-25.md` | `process-death-recovery-and-harness-depth.md` |
| `conversation-context-and-steering.md` | `02-context-input-and-session.md` |
| `ui-interaction-optimization.md` | `03-ui-ia-and-workbench.md` |
| `tool-exposure-optimization.md` / `codex-browser-vs-helix-2026-09-24.md` | `04-tools-browser-and-extensions.md` |
| `on-device-model-provider-2026-09-25.md` | `05-runtime-provider-and-on-device-models.md` |
| `hxa-217-request-context-cost-evaluation-2026-09-22.md` | `06-evaluation-and-evidence.md` |
| `helix-mermaid-architecture-diagrams.md` | 历史图集；当前结构看 `docs/architecture/overview.md` 与模块 01 |
