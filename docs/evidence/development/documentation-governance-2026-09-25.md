# 2026-09-25 文档与 Agent 指令治理记录

性质：文档治理 evidence，不是 HXA，不代表产品功能交付。

## 本轮整理

2026-09-25 对文档体系和 Agent 指令做了集中收敛：

- ADR 改为功能级 living decision：同一功能更新原 ADR，并用 `Decision history` 记录重要变化；不再为每次重构创建 superseding ADR 文件。
- `docs/development/status.md` 成为唯一 current-state/next-work 入口；roadmap 只做 HXA 索引/长期排序；过期 `next-work-plan` 删除。
- `reviews/` 只保留时间点审查；Wave/playbook/按模型 handoff 的有效内容进入 ADR/HXA/evidence 后删除。
- 历史验证计划、早期文档审查、旧 handoff 迁入 evidence；research 与竞品材料按当前/历史分层。
- `AGENTS.md` 收敛为 Authority、Architecture invariants、Task discipline、Verification/device authorization、Repository/security hygiene 五类长期规则，删除已完成 HXA 编号耦合和重复权限说明。
- 设备验证规则明确为：GitHub CI host-only；AI 代理仅在项目所有者对**当前任务**明确要求时运行本地模拟器/真机。状态语义固定为 `not requested` / `pending` / `passed` / `failed`。
- Bug Fix 不再默认“一 bug 一文件”；普通当轮修复进入当前开发任务、测试和完成证据，只有跨任务长期根因/不变量保留独立 Bug Fix。

## HXA 口径修正

项目所有者后续明确：**HXA 主要是代码/产品开发记录，偏实现、修复、验证、迁移和发布，不为纯文档整理、文档重构或 Research 整合单独创建 HXA。**

因此此前临时建立的文档类 HXA-221～HXA-224 已从 HXA ledger 移除，编号保留为空洞、不复用；其有价值内容分别保存在本 evidence 和 `docs/evidence/research-history/research-modularization-2026-09-25.md`。

以后：

- 代码/产品功能开发、迁移、修复、发布/验收任务：可以有 HXA；
- 与代码 HXA 同步的 ADR/文档更新：属于该 HXA；
- 单纯文档清理、索引重构、Research 综合：直接修改文档并按需要留 evidence，不创建 HXA。

## 验证历史

治理过程中多次执行 `./scripts/check-all.sh --source`、`./scripts/check-docs.sh` 和 `git diff --check`，当时均通过；具体当前门禁以最新仓库运行结果为准，本记录不作为今天的绿色证明。
