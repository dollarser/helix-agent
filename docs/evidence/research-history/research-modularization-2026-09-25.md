# 2026-09-25 Research 模块化整合记录

性质：Research 文档整理 evidence，不是 HXA，不是实现授权。

## 目的

原 `docs/research/` 同时存在多个阶段、多个 Agent 的架构、UI、竞品、恢复、工具和 Provider 报告；每份都有当时的“当前结论”，后续容易选错。

本轮将 current Research 收敛为 `docs/research/modules/`：

- 产品定位与竞品基线；
- 架构与执行引擎；
- 上下文、输入与会话；
- UI / IA / 工作台；
- 工具、浏览器与扩展；
- Runtime、Provider 与端侧模型；
- 评估与证据方法；
- 进程死亡恢复/Harness 深度专题。

旧阶段报告整体迁入 `docs/evidence/research-history/`，保留原日期、基线、行级证据和历史推理。

## 冲突裁决口径

`当前 Helix 源码 / accepted ADR > 最新一手官方资料或维护者仓库 > 可复现项目证据 > 较旧 research`。

无法证明更合理方案时采用最新研究作为临时推荐，并标记不确定性；Research 不直接改变 accepted ADR/生产行为。

## 主要研究更新

- Harness 方向收敛为 **shallow policy, deep invariants**：模型主导工作流，Harness 主导 Session/context、工具、权限、取消、幂等、持久事实与副作用不确定性。
- crash recovery 更推荐 old Turn close + successor Turn + RecoverySummary，而不是 same-Turn control-state rehydrate；effect truth/review 仍必须 fail closed。
- UI 保留 conversation-first、grouped drawer、in-place result，Workspace 作为 Files/Git/Terminal/Artifacts 的操作上下文。
- Tool/Browser 保留 typed tools 与安全 token，优先研究 tool-result multimodal，不把 QuickJS/Node bridge 变成权限绕过。
- **本地模型 Provider 后续经项目所有者修正为一等 Provider，可直接驱动完整 Agent loop；摘要/分类只是低风险验证入口，不是能力上限。**

当前综合结论以 `docs/research/README.md` 和 `docs/research/modules/` 为准。

## 后续裁决

同日项目所有者接受两个修正：本地模型 Provider 是一等完整 Agent Provider；successor-Turn recovery 被提升到 ADR-AGENT-001 / ADR-GOAL-001，并由 HXA-220 执行。此段是对整理后决策变化的追记。
