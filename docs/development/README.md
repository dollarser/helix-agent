# 开发控制面

本目录只保存**当前代码/产品开发控制面**，不是历史证据库。HXA 主要记录实现、重构、修复、迁移、验证和发布类开发工作；纯文档整理、索引重构或 Research 综合不单独创建 HXA。

- [status.md](status.md)：唯一当前状态、In progress、Next task 与 Blocked 入口。
- [候选需求与待裁决索引](candidate-decisions.md)：区分接受范围、已实现基础、未启用功能和进入开发条件；不维护第二份计划。
- [roadmap.md](roadmap.md)：HXA 清单/长期排序；不是第二份 current plan。
- [tasks/](tasks/)：所有尚未完成的 HXA 规格，包括进行中、外部依赖、发行队列和未来已授权任务。真正当前执行项必须同时出现在 `status.md`。
- [implementation-guide.md](implementation-guide.md)：通用开发/交接流程。
- [开发原则与后续推进策略](feature-refactor-strategy.md)：有限基础收敛、功能接入顺序、重构停止条件与评审分级；不替代 status/HXA 的当前排期。
- [verification-matrix.md](verification-matrix.md)：公共主机、构建、设备验证规则。
- [environment.md](environment.md)、[ci.md](ci.md)：开发环境与 GitHub CI 边界。
- [Agent Eval](agent-eval.md)、[Harness 系统基线](harness-system-baseline.md)：证据字段、比较方法与固定系统测量口径。
- [内测清单](internal-pilot.md)：用户任务、独立结果核验与反馈记录。
- [发行就绪条件](release-readiness.md)：非重构收口、外部输入与发行依赖，不维护第二份当前状态。

完成的 HXA 转入 `../completion-records/`；运行日志、诊断、历史验证计划和交接证据转入 `../evidence/`；方案研究转入 `../research/`。不要在本目录长期保存 Wave/playbook、按模型命名 handoff、已完成任务计划或时间点设备占用信息。

当前设备规则以根 `AGENTS.md` 与 [verification-matrix.md](verification-matrix.md) 为准：GitHub CI host-only；AI 代理只在项目所有者对当前任务明确要求时执行本地模拟器/真机验证。
