# Research 历史快照

本目录保存 2026-09-25 模块化整合前，不同阶段、不同 Agent 形成的原始 research 报告。它们保留当时基线、逐字推理、行级源码证据和阶段性竞品判断，**不是当前综合研究入口，也不是实现授权**。

当前研究结论统一从 [`docs/research/modules/`](../../research/modules/README.md) 进入；当前事实看 `docs/development/status.md`，实施范围看 active HXA，长期契约看 accepted ADR。

历史文件中的“当前/计划/缺口”必须按文件日期理解。若与模块研究冲突，以当前 Research 的裁决顺序处理：当前源码/accepted ADR > 最新一手资料 > 可复现证据 > 较旧研究。

本目录不继续维护 current 状态；逐字旧结论和当时来源可用于追溯为什么后来发生某个决策。

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
