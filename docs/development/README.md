# 开发控制面

本目录只保存**当前代码/产品开发控制面**，不是历史证据库。HXA 主要记录实现、重构、修复、迁移、验证和发布类开发工作；纯文档整理、索引重构或 Research 综合不单独创建 HXA。

- [status.md](status.md)：唯一当前状态、In progress、Next task 与 Blocked 入口。
- [候选需求与待裁决索引](candidate-decisions.md)：区分接受范围、已实现基础、未启用功能和进入开发条件；不维护第二份计划。
- [roadmap.md](roadmap.md)：HXA 清单/长期排序；不是第二份 current plan。
- [tasks/](tasks/)：所有尚未完成的 HXA 规格，包括进行中、外部依赖、发行队列和未来已授权任务。真正当前执行项必须同时出现在 `status.md`。
- [implementation-guide.md](implementation-guide.md)：通用开发/交接流程。
- [个人工程手册](engineering-playbook.md)：所有者开发偏好、决策方法与可追溯案例；跨项目复用的解释性资料，不替代 AGENTS、有效 ADR 或当前任务。
- [context-navigation.md](context-navigation.md)：复用 CodeGraph 定位结构与关联，结合精确搜索、当前源码和 Git 差异按需读取；不维护平行导航或资料包系统。
- [开发原则与后续推进策略](feature-refactor-strategy.md)：有限基础收敛、功能接入顺序、重构停止条件与评审分级；不替代 status/HXA 的当前排期。
- [verification-matrix.md](verification-matrix.md)：公共主机、构建、设备验证规则。
- [environment.md](environment.md)、[ci.md](ci.md)：开发环境与 GitHub CI 边界。
- [Agent Eval](agent-eval.md)、[Harness 系统基线](harness-system-baseline.md)：证据字段、比较方法与固定系统测量口径。
- [内测清单](internal-pilot.md)：用户任务、独立结果核验与反馈记录。
- [发行就绪条件](release-readiness.md)：非重构收口、外部输入与发行依赖，不维护第二份当前状态。

完成的 HXA 转入 `../completion-records/`；运行日志、诊断、历史验证计划和交接证据转入 `../evidence/`；方案研究转入 `../research/`。不要在本目录长期保存阶段性的 Wave 执行手册、按模型命名 handoff、已完成任务计划或时间点设备占用信息；`engineering-playbook.md` 只维护长期方法与案例，不承担阶段排期或交接职责。

当前设备规则以根 `AGENTS.md` 与 [verification-matrix.md](verification-matrix.md) 为准：GitHub CI host-only；AI 代理只在项目所有者对当前任务明确要求时执行本地模拟器/真机验证。

## Git 跟踪与留存

| 材料 | 处理方式 | 判断依据 |
| --- | --- | --- |
| 生产源码、资源、有效测试、测试夹具、CI/构建脚本 | 跟踪 | 能构建、验证或维护当前契约；测试缺少普通调用方不表示无用 |
| Gradle Wrapper、版本/依赖锁、校验元数据、许可文本 | 跟踪 | 固定可复现输入，不能当下载缓存删除 |
| 构建明确引用的 vendor 资产 | 按现有批准的资产/许可规则跟踪 | 例如 FFmpeg ZIP 是构建输入；禁止全局忽略 ZIP/JAR 来掩盖来源或漏掉依赖 |
| 当前 ADR/HXA、完成记录、缺陷机制、精选历史证据 | 跟踪 | 保留唯一事实与有效引用；旧日期不等于过期 |
| 固定 UI 参考图、经审查的验收截图、JSON/JSONL 夹具 | 按用途跟踪 | 不按扩展名批量忽略；原始截图/日志应先脱敏和精简 |
| APK/AAB/APKS、构建目录、堆转储、缓存、机器配置、凭据 | 忽略，不提交 | 可重建产物或本地敏感状态；凭据不能因需要复现而提交 |
| 一次性源码改写器、临时诊断脚本 | 默认本地保存，按用途决定是否晋升 | 放入 `scripts/debug/YYYY-MM-DD/local/` 可显式忽略；有复现价值且可移植的 runner 才纳入跟踪 |
| 工作树恢复包、真实模型原始结果、唯一失败轨迹 | 忽略，但不自动删除 | Git 无法恢复这些资料；需确认保留价值、所有权和恢复副本 |

`.gitignore` 不会解除已有文件的跟踪，也不是删除清单。`.env.example`/`.env.template` 仅放无凭据样例；新增二进制夹具若命中通用忽略规则，先明确用途并增加窄例外，不默认强制添加。

清理流程：核对引用和实际执行入口 → 确认替代实现/可重建输入 → 保存未提交或唯一证据并校验哈希 → 删除或合并 → 验证文档链接及相关门禁。不得对整个 `scripts/debug/`、`docs/evidence/` 或 `build/` 一键清空；本地归档不是永久备份，删除备份目录前仍需审查。

同一段维护逻辑保留一个实现，更新调用方；仅为历史命令或导入兼容而保留的薄入口须明确用途。文档合并按[文档职责](../README.md#文档职责)处理：状态、决定和历史结果分别保留，不把所有报告合成一份不断增长的文档。
