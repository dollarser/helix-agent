# Helix Bug 修复记录约定

Bug 修复记录保存“缺陷为什么会发生、修复后哪些不变式必须长期成立、哪些替代方案被放弃”。它受 DeepSeek Harness Agent Notes 的 `bug-fix` 分类启发，但按 Helix 已有 HXA/ADR/完成记录体系保持轻量。

## 与其他文档的边界

| 文档 | 唯一职责 |
| --- | --- |
| 产品/架构专题文档 | 定义当前产品、运行时、安全和平台设计 |
| ADR | 记录会改变架构、安全、持久化、IPC、权限或重要依赖的决策及替代方案 |
| HXA | 路线图中的原子交付任务，包含范围、依赖与验收命令；HXA 编号本身不是设计文档 |
| HXA 完成记录 | 保存某次交付的实际命令、exit code、设备、产物和当时限制 |
| Bug 修复记录 | 保存非平凡缺陷的症状、影响、根因、修复不变式、替代方案、回归证据和剩余风险 |
| [Postmortem](../postmortems/README.md) | 复盘已越过既有安全网的系统性事故，重点是为什么审查、测试或流程没有拦住 |
| `development/status.md` | 唯一当前状态源；只保存当前能力、任务和限制，不堆叠修复历史 |

Bug 修复如果改变 accepted ADR，必须另建获授权的 superseding ADR；Bug 记录不能替代架构决策。如果缺陷尚未修复，它属于 `development/status.md` 的 Blocked/Known limitations 或带所有者的未来 HXA，不得提前创建 `Status: fixed` 记录。

## 何时必须写

满足以下任一条，且修复已有可重复回归证据时，新建或更新一份 Bug 修复记录：

- 安全、审批、权限、Secret、真实路径或用户数据泄漏；
- 数据损坏、迁移、原子发布、恢复或重复副作用；
- 并发、取消、超时、死锁、未知结果或资源无界；
- Provider/工具/持久协议误解会污染后续 Turn 或回放；
- HXA 已完成后才发现的跨模块缺陷；
- 缺陷机制、放弃的备选方案或防回归手段值得后续实现者重用。

普通编译错误、拼写、机械 import 修复、当轮测试代码的局部错误，以及没有独立长期不变式的小修改不单独建档。

## 命名、状态与格式

文件名为 `YYYY-MM-DD-short-kebab-title.md`，日期是缺陷修复形成稳定决定的日期。不维护手写编号索引；目录和全文搜索就是活跃清单。

```markdown
# Bug Fix: <标题>

Status: fixed
Date: YYYY-MM-DD
Related HXA: HXA-NNN
Affected modules: <模块列表>

## Problem
## Impact
## Root cause
## Fix and invariants
## Alternatives considered
## Regression verification
## Residual risk
## Related records
```

`Status` 只允许 `fixed` 或 `superseded`。`fixed` 必须描述当前仍成立的修复机制；后续决定替代该机制时，新建记录并将旧记录改为 `superseded`，两者交叉链接。不在文末追加时间线式“后来又修了”；当前机制变化时更新事实，决策反转则用新记录取代。

`Regression verification` 必须指向能因该缺陷机制失败的测试或可执行命令；单独的构建成功不足以证明缺陷已关闭。`Residual risk` 如无剩余风险也要明确写“无已知剩余风险”，不得省略。

## 与 HXA 完成记录的关系

在 HXA 完成前发现并修复的非平凡 Bug，完成记录保存该次验收证据，Bug 记录保存长期根因和不变式。HXA 完成后发现的非平凡 Bug，不把大段新修复史反向堆入旧完成记录；新 Bug 记录链接原 HXA、修复所属 HXA/审查与实际验收证据。

`scripts/check-docs.sh` 机械检查文件名、状态、日期、HXA 引用与必要章节，防止空模板或无所有者记录进入仓库。
